package scholarchain.services.worker

import cats.effect.IO
import org.http4s.HttpRoutes
import scholarchain.services.common.*
import scholarchain.storage.{RowSource, SemanticDocument, VectorLoader, WorkQueue}
import upickle.default.ReadWriter
import scala.concurrent.duration.*
import scala.util.Try

/** One embedding job on the queue: the run it belongs to and the document (text already rendered).
  */
final case class EmbedMessage(runId: String, document: SemanticDocument) derives ReadWriter

final case class QueueMessage(id: String, body: String)

/** The queue as the worker sees it, shaped like SQS: a received message stays in flight until it is
  * acknowledged (deleted); a message that is never acknowledged is redelivered and, after the
  * queue's redrive limit, moved to the dead-letter queue by the queue itself (infrastructure). The
  * worker never deletes a message it could not process.
  */
trait QueueConsumer:
  def receive(max: Int): Vector[QueueMessage]
  def acknowledge(ids: Vector[String]): Unit

/** Adapter over the Phase 08 [[WorkQueue]] of message bodies. A received message that is not
  * acknowledged is kept in `deadLetters` (what SQS would redrive to the DLQ).
  */
final class WorkQueueConsumer(queue: WorkQueue[String]) extends QueueConsumer:
  private val inFlight = scala.collection.mutable.LinkedHashMap.empty[String, QueueMessage]
  private var seq = 0L

  def receive(max: Int): Vector[QueueMessage] = synchronized {
    queue.drain(max).map { b =>
      seq += 1
      val m = QueueMessage(s"m-$seq", b)
      inFlight(m.id) = m
      m
    }
  }

  def acknowledge(ids: Vector[String]): Unit = synchronized { ids.foreach(inFlight.remove(_)) }
  def deadLetters: Vector[QueueMessage] = synchronized(inFlight.values.toVector)

/** What one drain did. `malformed` messages could not be parsed or validated; `rejected` parsed but
  * the loader refused the document; `failedBatches` are loads that failed as a whole. Nothing that
  * is counted in those three is acknowledged.
  */
final case class DrainResult(
    received: Int,
    acknowledged: Int,
    malformed: Int,
    rejected: Int,
    failedBatches: Int,
    written: Long,
    unchanged: Long
)

/** Drains queue messages through the idempotent [[VectorLoader]]: stable vector keys, conditional
  * writes and the hash-and-model skip mean that a redelivered message changes nothing. Good
  * messages are grouped by run so a mixed batch still loads, and a poison message never blocks the
  * others: it is left unacknowledged for the dead-letter queue.
  */
final class EmbeddingWorker(
    consumer: QueueConsumer,
    loader: VectorLoader,
    sink: MetricsSink,
    batchSize: Int = 50,
    maxMessageBytes: Int = 256 * 1024
):
  private def parse(m: QueueMessage): Either[String, EmbedMessage] =
    if m.body.length > maxMessageBytes then Left("message too large")
    else
      Try(upickle.default.read[EmbedMessage](m.body)).toEither.left.map(_ => "not an embed message")

  def drainOnce: IO[DrainResult] =
    for
      msgs <- IO.blocking(consumer.receive(batchSize))
      parsed = msgs.map(m => m -> parse(m))
      good = parsed.collect { case (m, Right(e)) => (m, e) }
      malformed = parsed.count(_._2.isLeft)
      groups = good.groupBy(_._2.runId).toVector.sortBy(_._1)
      outcomes <- IO.blocking(groups.map((run, items) => load(run, items)))
      acked = outcomes.flatMap(_.acked)
      _ <- IO.blocking(if acked.nonEmpty then consumer.acknowledge(acked))
      result = DrainResult(
        msgs.size,
        acked.size,
        malformed,
        outcomes.map(_.rejected).sum,
        outcomes.count(_.failed),
        outcomes.map(_.written).sum,
        outcomes.map(_.unchanged).sum
      )
      _ <- emit(result).handleError(_ => ())
    yield result

  private final case class Outcome(
      acked: Vector[String],
      rejected: Int,
      failed: Boolean,
      written: Long,
      unchanged: Long
  )

  private def load(run: String, items: Vector[(QueueMessage, EmbedMessage)]): Outcome =
    Try(loader.load(run, RowSource.of(items.map(_._2.document)))).toEither.left
      .map(_.getClass.getSimpleName)
      .flatten match
      case Right(r) =>
        val bad = r.rejected.map(_.input).toSet
        val (refused, ok) =
          items.partition((_, e) => bad(e.document.chunkId) || bad(e.document.entityId))
        Outcome(ok.map(_._1.id), refused.size, false, r.written, r.unchanged)
      case Left(_) => Outcome(Vector.empty, 0, true, 0L, 0L)

  private def emit(r: DrainResult): IO[Unit] =
    IO.realTime.flatMap(t =>
      sink.emit(
        Emf.line(
          "ScholarChain/Services",
          Map("Service" -> "embedding-worker"),
          Seq(
            Metric("Received", r.received.toDouble),
            Metric("Acknowledged", r.acknowledged.toDouble),
            Metric("Malformed", r.malformed.toDouble),
            Metric("Rejected", r.rejected.toDouble),
            Metric("FailedBatches", r.failedBatches.toDouble),
            Metric("VectorsWritten", r.written.toDouble)
          ),
          Map.empty,
          t.toMillis
        )
      )
    )

  /** The polling loop of the deployed worker: drain, and pause when the queue was empty. */
  def loop(idle: FiniteDuration = 5.seconds): IO[Nothing] =
    (drainOnce.flatMap(r => if r.received == 0 then IO.sleep(idle) else IO.unit)).foreverM

/** The worker has no public routes; it serves health and readiness only, through the shared kit. */
object WorkerApp:
  def routes(kit: ServiceKit): HttpRoutes[IO] = kit.wrap(HttpRoutes.empty[IO])
