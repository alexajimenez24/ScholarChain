package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.AwsHttp
import upickle.default.ReadWriter
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.util.Try

/** The queue message body. Its JSON must equal what the embedding worker's `EmbedMessage` decodes
  * (`{"runId":..., "document": SemanticDocument}`); storage cannot import services, so the shape is
  * repeated here and pinned by a test.
  */
final case class EmbedJobMessage(runId: String, document: SemanticDocument) derives ReadWriter

/** One entry of a batch the queue did not accept. `senderFault` means the entry itself is bad. */
final case class SendFailure(index: Int, code: String, senderFault: Boolean)

/** The producer side of the embedding queue. A `Right` lists only the entries not accepted. */
trait QueueSender:
  def send(bodies: Vector[String]): Either[RemoteError, Vector[SendFailure]]

object QueueSender:
  /** SQS limits of one `SendMessageBatch`: 10 entries and 1 MiB in total
    * (docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SendMessageBatch.html).
    */
  val MaxBatch = 10
  val MaxBatchBytes = 1024 * 1024

  /** One message: the queue's `max_message_size` (infra compute stack) and the worker's own bound,
    * both 256 KiB.
    */
  val MaxMessageBytes = 256 * 1024

/** [[QueueSender]] over the SQS JSON protocol. `http` must make one attempt per call: the caller's
  * [[RetryPolicy]] retries. A repeated send can deliver a message twice, which the worker's
  * hash-and-model skip makes harmless. Errors carry the HTTP status only.
  */
final class SqsQueueSender(
    http: AwsHttp,
    region: String,
    queueUrl: String,
    endpointOverride: Option[String] = None
) extends QueueSender:
  private val endpoint = endpointOverride.getOrElse(s"https://sqs.$region.amazonaws.com/")

  def send(bodies: Vector[String]): Either[RemoteError, Vector[SendFailure]] =
    val body = ujson.Obj(
      "QueueUrl" -> queueUrl,
      "Entries" -> ujson.Arr.from(
        bodies.zipWithIndex.map((b, i) => ujson.Obj("Id" -> s"m$i", "MessageBody" -> b))
      )
    )
    http.send(
      "POST",
      endpoint,
      "sqs",
      body.render().getBytes(UTF_8),
      Map(
        "content-type" -> "application/x-amz-json-1.0",
        "x-amz-target" -> "AmazonSQS.SendMessageBatch"
      )
    ) match
      case Left(_)           => Left(RemoteError.Transient("SQS SendMessageBatch: no response"))
      case Right(r) if !r.ok =>
        val why = s"SQS SendMessageBatch: HTTP ${r.status}"
        if r.status >= 500 || r.status == 429 || r.body.contains("Throttl") then
          Left(RemoteError.Transient(why))
        else Left(RemoteError.Permanent(why))
      case Right(r) =>
        Try {
          val j = ujson.read(r.body).obj
          def list(k: String) = j.get(k).map(_.arr.toVector).getOrElse(Vector.empty)
          val ok = list("Successful").map(_("Id").str).toSet
          val failed = list("Failed")
            .map(f => f("Id").str -> (f("Code").str, f("SenderFault").bool))
            .toMap
          bodies.indices.toVector.filterNot(i => ok(s"m$i")).map { i =>
            val (code, fault) = failed.getOrElse(s"m$i", ("missing from the reply", false))
            SendFailure(i, code, fault)
          }
        }.toEither.left.map(_ => RemoteError.Permanent("SQS SendMessageBatch: malformed reply"))

/** In-memory queue enforcing the SQS batch and message limits. */
final class InMemoryQueueSender extends QueueSender:
  private val sent = mutable.ArrayBuffer.empty[String]
  var calls = 0
  def messages: Vector[String] = sent.toVector

  def send(bodies: Vector[String]): Either[RemoteError, Vector[SendFailure]] =
    calls += 1
    val sizes = bodies.map(_.getBytes(UTF_8).length)
    if bodies.isEmpty || bodies.size > QueueSender.MaxBatch then
      Left(RemoteError.Permanent("a batch holds 1..10 messages"))
    else if sizes.sum > QueueSender.MaxBatchBytes ||
      sizes.exists(_ > QueueSender.MaxMessageBytes)
    then Left(RemoteError.Permanent("batch or message too long"))
    else
      sent ++= bodies
      Right(Vector.empty)

/** What an enqueue did. `messages` counts those the queue accepted (or, in a preview, would be
  * sent).
  */
final case class EnqueueResult(
    read: Long,
    chunks: Long,
    messages: Long,
    bytes: Long,
    duplicatesDropped: Long,
    retriedMessages: Long,
    rejected: Vector[RejectRecord],
    report: Option[StoredObject],
    replay: Boolean
)

/** Renders source rows and puts one embedding job per chunk on the queue, as the RunLedger stage
  * `enqueue-embeddings`.
  *
  * Idempotency: the same input gives byte-identical messages with the same vector keys. A replay
  * sends them again (the queue cannot be asked what it already took); the worker then finds each
  * key with the same text hash and model version and neither embeds nor writes it. Within one call
  * an identical copy of a key is dropped and a conflicting one rejected. A send that still fails
  * after the retries fails the stage, and a rerun resends everything. Every rejection is in the
  * stored report and (capped) in the ledger's failure records.
  */
final class EmbeddingEnqueuer(
    queue: QueueSender,
    ledger: RunLedger,
    writer: ArtifactWriter,
    retry: RetryPolicy = RetryPolicy()
):
  val Stage = "enqueue-embeddings"
  val LogicVersion = "enqueue-v1"
  private val MaxFailureRecords = VectorLoader.DefaultMaxFailureRecords
  private val stage = new LoadStage(ledger, writer)

  def enqueue(
      runId: String,
      source: RowSource[SourceRecord],
      renderer: DocumentRenderer
  ): Either[String, EnqueueResult] =
    stage
      .run(runId, Stage, LogicVersion) {
        val t = process(runId, source, renderer, send = true)
        recordFailures(runId, t)
        t.fatal.toLeft(()).map { _ =>
          val report = LoadReport.of(
            "embedding-queue",
            runId,
            LogicVersion,
            Seq(
              "read" -> t.read,
              "chunks" -> t.chunks,
              "messages" -> t.messages,
              "duplicatesDropped" -> t.dropped,
              "rejected" -> t.rejects.size.toLong
            ),
            t.rejects.toVector
          )
          (t.result, report)
        }
      }
      .map(o => o.value.copy(report = Some(o.report), replay = o.replay))

  /** Renders and checks everything and sends and records nothing. */
  def preview(
      runId: String,
      source: RowSource[SourceRecord],
      renderer: DocumentRenderer
  ): Either[String, EnqueueResult] =
    val t = process(runId, source, renderer, send = false)
    t.fatal.toLeft(t.result)

  private final class Tally:
    var read, chunks, messages, bytes, dropped, retried = 0L
    var fatal: Option[String] = None
    val rejects = mutable.ArrayBuffer.empty[RejectRecord]
    val seen = mutable.Map.empty[(String, String), String]
    val batch = mutable.ArrayBuffer.empty[(String, String, Int)] // key, body, size
    def result: EnqueueResult =
      EnqueueResult(
        read,
        chunks,
        messages,
        bytes,
        dropped,
        retried,
        rejects.toVector,
        None,
        false
      )

  private def process(
      runId: String,
      source: RowSource[SourceRecord],
      renderer: DocumentRenderer,
      send: Boolean
  ): Tally =
    val t = new Tally
    def reject(input: String, reason: String): Unit =
      t.rejects += RejectRecord.of(Stage, reason, input, runId)
    def flush(): Unit =
      if t.batch.nonEmpty && t.fatal.isEmpty then
        if send then deliver(t, t.batch.toVector, reject)
        else
          t.messages += t.batch.size
          t.bytes += t.batch.map(_._3.toLong).sum
      t.batch.clear()

    t.fatal = source.problem
    val rows = if t.fatal.isDefined then Iterator.empty else source.rows
    while t.fatal.isEmpty && rows.hasNext do
      t.read += 1
      rows.next() match
        case Left(e)    => reject(s"row ${t.read - 1}", s"malformed row: $e")
        case Right(rec) =>
          documents(rec, renderer) match
            case Left(why)   => reject(rec.id, why)
            case Right(docs) =>
              docs.foreach { d =>
                t.chunks += 1
                val body = upickle.default.write(EmbedJobMessage(runId, d))
                val size = body.getBytes(UTF_8).length
                val key = (d.docType, d.chunkId)
                if d.runId != runId then
                  reject(d.chunkId, s"document belongs to run ${d.runId}, not $runId")
                else if size > QueueSender.MaxMessageBytes then
                  reject(d.chunkId, s"message of $size bytes exceeds the queue limit")
                else
                  t.seen.get(key) match
                    case Some(h) if h == Hashing.sha256Hex(body) => t.dropped += 1
                    case Some(_) => reject(d.chunkId, "duplicate vector key with different content")
                    case None    =>
                      t.seen(key) = Hashing.sha256Hex(body)
                      if t.batch.size >= QueueSender.MaxBatch ||
                        t.batch.map(_._3).sum + size > QueueSender.MaxBatchBytes
                      then flush()
                      t.batch += ((d.chunkId, body, size))
              }
    flush()
    t

  private def documents(
      rec: SourceRecord,
      renderer: DocumentRenderer
  ): Either[String, Vector[SemanticDocument]] =
    renderer.render(rec).left.map(w => s"render failed: $w").flatMap { r =>
      val docs = rec match
        case SourceRecord.Summary(e)  => SemanticDocument.fromEvidence(e, r)
        case SourceRecord.Abstract(a) => SemanticDocument.fromAbstract(a, r)
      docs.left.map(w => s"rendering refused: $w")
    }

  private def deliver(
      t: Tally,
      batch: Vector[(String, String, Int)],
      reject: (String, String) => Unit
  ): Unit =
    var pending = batch
    var calls = 0
    val outcome = retry.run {
      calls += 1
      if calls > 1 then t.retried += pending.size
      queue.send(pending.map(_._2)).flatMap { failures =>
        val failed = failures.map(f => f.index -> f).toMap
        val again = Vector.newBuilder[(String, String, Int)]
        pending.zipWithIndex.foreach { (m, i) =>
          failed.get(i) match
            case None =>
              t.messages += 1
              t.bytes += m._3
            case Some(f) if f.senderFault => reject(m._1, s"queue refused the message: ${f.code}")
            case Some(_)                  => again += m
        }
        pending = again.result()
        Either.cond(
          pending.isEmpty,
          (),
          RemoteError.Transient(s"${pending.size} messages not accepted by the queue")
        )
      }
    }
    outcome.left.foreach(e => t.fatal = Some(e.text))

  /** A ledger failure here never changes the outcome of the sends. */
  private def recordFailures(runId: String, t: Tally): Unit =
    def rec(subject: String, reason: String, times: Long): Unit =
      ledger.recordFailure(
        runId,
        Stage,
        subject.replace('#', '_').take(200),
        reason,
        math.min(times, 100000L).toInt
      ): Unit
    t.rejects.take(MaxFailureRecords).foreach(r => rec(s"reject:${r.input}", r.reason, 1L))
    val over = t.rejects.size - MaxFailureRecords
    if over > 0 then
      rec("reject:overflow", "further rejections not itemized; see the report", over.toLong)
    if t.retried > 0 then rec("retry:SendMessageBatch", "messages sent again", t.retried)
    t.fatal.foreach(f => rec("send:fatal", f, 1L))
