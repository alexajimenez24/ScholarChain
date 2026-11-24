package scholarchain.storage

import scholarchain.core.*
import upickle.default.ReadWriter
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/** One chunk of one document to embed: the chunk text, who and what it describes, and where it came
  * from. The same record is stored (as JSON) in ordinary S3, in the semantic-context zone; the
  * vector keeps only a pointer to it. `paper-abstract` documents have no window.
  *
  * The text comes only from a [[DocumentRenderer]] (see `fromEvidence` and `fromAbstract`). The
  * last seven fields describe the rendering and default for a document that was not split:
  * `ordinal` and `chunkCount` place the chunk, `documentTextHash` is the hash of the whole rendered
  * text, and the two URIs (set by the loader when it stores the document) point at the stored
  * source row and, for a multi-chunk document, at the stored whole rendered document.
  */
final case class SemanticDocument(
    schemaVersion: String,
    docType: String,
    entityId: String,
    chunkId: String,
    text: String,
    textHash: String,
    window: Option[TimeWindow],
    researcherIds: Vector[String],
    topicId: Option[String],
    institutionId: Option[String],
    communityId: Option[String],
    declaredIndex: Option[String],
    source: SourceType,
    sourceRecordId: String,
    snapshot: String,
    runId: String,
    evidenceId: Option[String],
    sourceUri: String,
    template: String = "",
    chunkPolicy: String = "",
    ordinal: Int = 0,
    chunkCount: Int = 1,
    documentTextHash: String = "",
    documentUri: Option[String] = None,
    sourceRecordUri: Option[String] = None
) derives ReadWriter

/** A Hadoop `abstracts` row (`abstract-v1`): the text kept apart from the work record. The shape
  * equals the Hadoop module's `AbstractRecord`, so its JSON lines decode here without an import.
  */
final case class PaperAbstract(
    workId: String,
    textHash: String,
    text: String,
    provenance: Provenance
) derives ReadWriter

object SemanticDocument:
  val Version = "semantic-doc-v2"
  val ResearcherProfile = "researcher-profile"
  val CommunityProfile = "collaboration-community"
  val PaperAbstractType = "paper-abstract"
  val PathSummary = "collaboration-path"
  val TopicSummary = "topic-institution-summary"

  /** docType to the key of `vectors.indexes` (the names in the Spark run manifest). */
  val IndexKeys: Map[String, String] = Map(
    ResearcherProfile -> "researchers",
    CommunityProfile -> "communities",
    PaperAbstractType -> "abstracts",
    PathSummary -> "paths",
    TopicSummary -> "topicInstitutions"
  )

  private val WindowLabel = "^(\\d{4})-(\\d{4})$".r

  private final case class Facts(
      researcherIds: Vector[String],
      topicId: Option[String],
      institutionId: Option[String],
      communityId: Option[String],
      source: SourceType,
      sourceRecordId: String,
      snapshot: String,
      runId: String,
      sourceUri: String
  )

  /** The rendered document must be self-consistent: the hashes match the texts, the chunks are
    * spans of the text with ordinals 0..n-1 and distinct well-formed IDs, and a document of one
    * chunk keeps the core chunk ID formula. A multi-chunk ID formula belongs to the renderer.
    */
  private def checkRendered(r: RenderedDocument): Either[String, Unit] =
    def ok(c: Boolean, why: => String) = Either.cond(c, (), why)
    for
      _ <- ok(r.text.nonEmpty, "rendered text is empty")
      _ <- ok(r.textHash == Hashing.tagged(r.text), "rendered textHash does not match its text")
      _ <- ok(r.template.nonEmpty && r.chunkPolicy.nonEmpty, "no template or chunk policy named")
      _ <- ok(r.chunks.nonEmpty, "the renderer gave no chunks")
      _ <- ok(
        r.chunks.zipWithIndex.forall((c, i) => c.ordinal == i),
        "chunk ordinals are not 0..n-1"
      )
      _ <- ok(
        r.chunks.forall(c =>
          c.start >= 0 && c.start < c.end && c.end <= r.text.length &&
            r.text.substring(c.start, c.end) == c.text
        ),
        "a chunk is not a span of the document text"
      )
      _ <- ok(
        r.chunks.forall(c => c.textHash == Hashing.tagged(c.text)),
        "a chunk textHash does not match its text"
      )
      _ <- ok(r.chunks.forall(c => Ids.isChunkId(c.chunkId)), "malformed chunk ID")
      _ <- ok(r.chunks.map(_.chunkId).distinct.size == r.chunks.size, "duplicate chunk IDs")
      _ <- ok(
        r.chunks.size > 1 ||
          r.chunks.head.chunkId == Ids.chunk(r.entityId, r.sourceVersion, r.docType, 0, r.text),
        "the chunk ID of a one-chunk document does not follow core Ids.chunk"
      )
    yield ()

  private def build(r: RenderedDocument, f: Facts, evidenceId: Option[String]) =
    r.chunks.map { c =>
      SemanticDocument(
        Version,
        r.docType,
        r.entityId,
        c.chunkId,
        c.text,
        c.textHash,
        r.window,
        f.researcherIds,
        f.topicId,
        f.institutionId,
        f.communityId,
        Some(r.index),
        f.source,
        f.sourceRecordId,
        f.snapshot,
        f.runId,
        evidenceId,
        f.sourceUri,
        r.template,
        r.chunkPolicy,
        c.ordinal,
        r.chunks.size,
        r.textHash
      )
    }

  /** One document per rendered chunk, for a Spark `semantic-summaries` row (an `EvidenceItem` of
    * type SEMANTIC_PROFILE) and what the renderer made of it. The row must carry `docType`,
    * `entityId` and `window` in `values`, and the rendering must name the same identity; the text
    * is the renderer's, never the row's.
    */
  def fromEvidence(e: EvidenceItem, r: RenderedDocument): Either[String, Vector[SemanticDocument]] =
    def value(k: String) = e.values.get(k).filter(_.trim.nonEmpty)
    for
      _ <- Either.cond(e.schemaVersion == SchemaVersions.Evidence, (), s"schema ${e.schemaVersion}")
      _ <- Either.cond(
        e.evidenceType == EvidenceType.SEMANTIC_PROFILE,
        (),
        s"evidence type ${e.evidenceType} is not SEMANTIC_PROFILE"
      )
      docType <- value("docType").toRight("values.docType is missing")
      entity <- value("entityId").toRight("values.entityId is missing")
      label <- value("window").toRight("values.window is missing")
      window <- label match
        case WindowLabel(a, b) => Right(TimeWindow(a.toInt, b.toInt))
        case other             => Left(s"values.window '$other' is not a window label")
      _ <- Either.cond(
        (r.docType, r.entityId, r.window) == (docType, entity, Some(window)),
        (),
        "the rendering names another document type, entity or window than the row"
      )
      _ <- Either.cond(
        r.evidenceId.forall(_ == e.evidenceId),
        (),
        "the rendering names another evidence ID than the row"
      )
      _ <- checkRendered(r)
    yield build(
      r,
      Facts(
        e.researcherIds,
        value("topicId"),
        value("institutionId"),
        value("communityId"),
        e.provenance.source,
        e.provenance.sourceRecordId,
        e.provenance.snapshot,
        e.provenance.runId,
        e.sourceUri
      ),
      Some(r.evidenceId.getOrElse(e.evidenceId))
    )

  /** One document per rendered chunk, for a Hadoop/Spark abstract and its rendering. */
  def fromAbstract(
      a: PaperAbstract,
      r: RenderedDocument
  ): Either[String, Vector[SemanticDocument]] =
    for
      _ <- Either.cond(a.text.nonEmpty, (), "empty abstract")
      _ <- Either.cond(a.textHash == Hashing.tagged(a.text), (), "textHash does not match text")
      _ <- Either.cond(
        (r.docType, r.entityId, r.window) == (PaperAbstractType, a.workId, None),
        (),
        "the rendering names another document type, work or window than the abstract"
      )
      _ <- checkRendered(r)
    yield build(
      r,
      Facts(
        Vector.empty,
        None,
        None,
        None,
        a.provenance.source,
        a.provenance.sourceRecordId,
        a.provenance.snapshot,
        a.provenance.runId,
        s"abstract:${a.workId}"
      ),
      r.evidenceId
    )

/** The vector metadata of a document: compact, filterable fields plus pointers; never the text.
  *
  * Filterable: `documentType`, `entityId`, `source`, `runId`, `snapshot`, `embeddingModel`,
  * `window`, `windowStartYear`, `windowEndYear`, `researcherIds`, `communityId`, `topicId`,
  * `institutionId`, `workId`, `pathId`, `template`, `chunkOrdinal`, `chunkCount`. Non-filterable
  * (see [[VectorIndexes.NonFilterable]]): `textUri`, `textSha256`, `evidenceId`, `sourceUri`.
  */
object VectorMetadata:
  /** A metadata string longer than this is not a compact field; it is refused as long text. */
  val MaxValueBytes = 512
  val MaxResearcherIds = 16
  private val ForbiddenKeys =
    Set("text", "body", "content", "abstract", "profile", "summary", "description")

  def of(d: SemanticDocument, embeddingModel: String, textUri: String): Map[String, MetaValue] =
    import MetaValue.*
    val common = Map[String, MetaValue](
      "documentType" -> Str(d.docType),
      "entityId" -> Str(d.entityId),
      "source" -> Str(d.source.toString),
      "runId" -> Str(d.runId),
      "snapshot" -> Str(d.snapshot),
      "embeddingModel" -> Str(embeddingModel),
      "textUri" -> Str(textUri),
      "textSha256" -> Str(d.textHash),
      "sourceUri" -> Str(d.sourceUri)
    )
    val window = d.window.toVector.flatMap(w =>
      Vector(
        "window" -> Str(w.label),
        "windowStartYear" -> Num(w.startYear.toLong),
        "windowEndYear" -> Num(w.endYear.toLong)
      )
    )
    val entityKey = d.docType match
      case SemanticDocument.CommunityProfile  => Some("communityId" -> Str(d.entityId))
      case SemanticDocument.PaperAbstractType => Some("workId" -> Str(d.entityId))
      case SemanticDocument.PathSummary       => Some("pathId" -> Str(d.entityId))
      case SemanticDocument.TopicSummary      => Some("topicId" -> Str(d.entityId))
      case _                                  => None
    val optional = Vector(
      d.topicId.map("topicId" -> Str(_)),
      d.institutionId.map("institutionId" -> Str(_)),
      d.communityId.map("communityId" -> Str(_)),
      d.evidenceId.map("evidenceId" -> Str(_)),
      Option.when(d.researcherIds.nonEmpty)("researcherIds" -> Strs(d.researcherIds))
    ).flatten
    val chunk = Vector[(String, MetaValue)](
      "chunkOrdinal" -> Num(d.ordinal.toLong),
      "chunkCount" -> Num(d.chunkCount.toLong)
    ) ++ Option.when(d.template.nonEmpty)("template" -> Str(d.template))
    common ++ window ++ entityKey.toVector ++ optional ++ chunk

  /** The compactness policy on top of the S3 Vectors limits: no text-like key, no long string. */
  def check(md: Map[String, MetaValue], text: String): Either[String, Unit] =
    val strings = md.values.toVector.flatMap {
      case MetaValue.Str(s)   => Vector(s)
      case MetaValue.Strs(xs) => xs
      case _                  => Vector.empty
    }
    md.keys.find(k => ForbiddenKeys(k.toLowerCase)) match
      case Some(k) => Left(s"metadata key '$k' is text-like; long text belongs in ordinary S3")
      case None    =>
        if strings.exists(_.getBytes(UTF_8).length > MaxValueBytes) then
          Left(s"a metadata value exceeds $MaxValueBytes bytes: long text belongs in ordinary S3")
        else if md.get("researcherIds").exists {
            case MetaValue.Strs(xs) => xs.size > MaxResearcherIds
            case _                  => false
          }
        then Left(s"more than $MaxResearcherIds researcherIds")
        else if text.length >= 40 && strings.exists(s => s.length >= 40 && text.contains(s)) then
          Left("a metadata value repeats a span of the text")
        else Right(())

/** Per index: vectors present afterwards, and how many this run wrote or found unchanged. */
final case class IndexCount(index: String, present: Long, written: Long, unchanged: Long)

/** What a load did. `read` counts source rows; `chunks` the documents (one per rendered chunk) they
  * became. `retries` counts retried transient calls by `retry:<call>:<index>`; `failureRecords` is
  * the number of RunLedger failure records written (each rejection, each retried call, capped).
  */
final case class VectorLoadResult(
    read: Long,
    written: Long,
    unchanged: Long,
    duplicatesDropped: Long,
    rejected: Vector[RejectRecord],
    perIndex: Vector[IndexCount],
    report: StoredObject,
    reportOutcome: WriteOutcome,
    replay: Boolean,
    chunks: Long = 0L,
    putBatches: Long = 0L,
    retries: Map[String, Long] = Map.empty,
    failureRecords: Long = 0L,
    queueHighWater: Int = 0
)

final case class VectorLoaderSettings(
    batchSize: Int = VectorLoader.DefaultBatch,
    retry: RetryPolicy = RetryPolicy(),
    /** When set, a one-chunk document's ID must equal
      * `Ids.chunk(entity, chunkSourceVersion, docType, ordinal, text)`. A multi-chunk document is
      * exempt: its ID formula belongs to the renderer (the renderer's own checks cover it).
      */
    chunkSourceVersion: Option[String] = None,
    /** The bound of the embedding work queue: documents waiting between the reader and the batches.
      * It bounds only the documents waiting: the `seen` fingerprints and the `objects` memo grow
      * with the keys of the load.
      */
    queueCapacity: Int = VectorLoader.DefaultQueue,
    /** At most this many rejections are itemized as RunLedger failure records; the rest are one
      * counted overflow record (the stored report always lists every rejection).
      */
    maxFailureRecords: Int = VectorLoader.DefaultMaxFailureRecords
)

/** A source row that could not become documents: what it was and why. */
private final case class Rej(input: String, reason: String)

/** The stored objects of a source row: the authoritative source row (JSON) and the rendering. */
private[storage] final case class DocContext(
    sourceJson: String,
    sourceRef: SourceRef,
    rendered: RenderedDocument
)

private[storage] final case class Entry(doc: SemanticDocument, ctx: Option[DocContext])

/** Loads semantic documents into the five S3 Vectors indexes.
  *
  * Input: [[loadRendered]] takes source rows and a [[DocumentRenderer]]; the document text comes
  * only from the renderer, one document per rendered chunk, and a renderer `Left` is a rejection of
  * the row. [[load]] takes ready documents (built by [[SemanticDocument.fromEvidence]] and
  * `fromAbstract`, which also need a rendering).
  *
  * Keys: the vector key is the document's `chunkId` (`c:` + 20 hex, core `Ids.chunk`), which hashes
  * the entity, source version, document type, ordinal and text, so a changed text is a new key.
  *
  * Flow: rows are read lazily into a bounded work queue ([[WorkQueue]]); when it is full it is
  * drained into per-index pending batches; a batch of `batchSize` (1..100, the `GetVectors` limit;
  * `PutVectors` allows 500) is processed as:
  *
  *   1. `GetVectors` the batch's keys (bounded retry of transient failures).
  *   2. A key that exists with the same `textSha256` and `embeddingModel` (the version
  *      `<modelId>@<dimension>`) is `unchanged`: no embedding call, no write. A key bound to
  *      different text, or to the same text under another model version, is rejected; nothing is
  *      overwritten (use a new index version for a new model).
  *   3. A new document: the source row and, for a multi-chunk document, the whole rendered document
  *      are stored create-only (content-addressed names), then the chunk's JSON, then it is
  *      embedded, and its metadata is checked against the limits and the compactness policy.
  *   4. The valid vectors are put in one `PutVectors` (bounded retry). A batch that still fails has
  *      every vector rejected with the reason; a replay retries those.
  *
  * Duplicates: an identical copy of a key is dropped and counted. A conflicting copy is rejected;
  * if the first copy is still waiting it is rejected with it, so neither text wins silently. A
  * first copy already written stays and is never overwritten.
  *
  * Failures: every rejection and every retried call is recorded in the RunLedger (`recordFailure`,
  * with a count), besides the stage entry (`attempt` counts stage retries). Rejected: malformed
  * rows, rendering failures, wrong run, unknown document type, text hash mismatch, wrong dimension,
  * metadata too large or text-like, duplicate keys with different content, key bound to other text,
  * embedding failure, failed put. Rejections are returned, written to the stored report and never
  * repaired. The ledger stage is `load-vectors`.
  */
final class VectorLoader private (
    vectors: VectorStore,
    embedder: Embedder,
    writer: ArtifactWriter,
    ledger: RunLedger,
    cfg: VectorConfig,
    settings: VectorLoaderSettings
):
  val Stage = "load-vectors"
  val LogicVersion = "vector-load-v2"
  private val stage = new LoadStage(ledger, writer)

  def indexOf(docType: String): Option[String] =
    SemanticDocument.IndexKeys.get(docType).flatMap(cfg.indexes.get)

  def specs: Vector[IndexSpec] =
    SemanticDocument.IndexKeys.values.toVector.sorted
      .flatMap(cfg.indexes.get)
      .map(n => VectorIndexes.spec(n, cfg.dimension))

  private def validate(d: SemanticDocument, runId: String): Either[String, String] =
    for
      index <- indexOf(d.docType).toRight(s"unknown document type ${d.docType}")
      _ <- Either.cond(
        d.schemaVersion == SemanticDocument.Version,
        (),
        s"schema ${d.schemaVersion}"
      )
      _ <- Either.cond(d.runId == runId, (), s"document belongs to run ${d.runId}, not $runId")
      _ <- Either.cond(d.text.nonEmpty, (), "empty text")
      _ <- Either.cond(d.textHash == Hashing.tagged(d.text), (), "textHash does not match text")
      _ <- Either.cond(Ids.isChunkId(d.chunkId), (), s"malformed chunk ID ${d.chunkId}")
      _ <- Either.cond(d.entityId.trim.nonEmpty, (), "empty entityId")
      _ <- Either.cond(
        d.chunkCount >= 1 && d.ordinal >= 0 && d.ordinal < d.chunkCount,
        (),
        s"chunk ${d.ordinal} of ${d.chunkCount} is out of range"
      )
      _ <- Either.cond(
        d.declaredIndex.forall(_ == index),
        (),
        s"document names index ${d.declaredIndex.getOrElse("")}, configured is $index"
      )
      _ <- Either.cond(
        (d.docType == SemanticDocument.PaperAbstractType) == d.window.isEmpty,
        (),
        "window is required except for paper-abstract"
      )
      _ <- d.window.fold[Either[String, Unit]](Right(()))(w =>
        Either.cond(Validate.window(w).isEmpty, (), s"bad window ${w.label}")
      )
      _ <- settings.chunkSourceVersion.fold[Either[String, Unit]](Right(()))(v =>
        Either.cond(
          d.chunkCount > 1 || d.chunkId == Ids.chunk(d.entityId, v, d.docType, d.ordinal, d.text),
          (),
          "chunk ID does not match its entity, version, document type and text"
        )
      )
    yield index

  private def fatal[A](r: Either[RemoteError, A], what: String): Either[String, A] =
    r.left.map(e => s"$what: ${e.text}")

  /** Loads ready documents (text already rendered). */
  def load(
      runId: String,
      source: RowSource[SemanticDocument],
      expectedRows: Option[Long] = None
  ): Either[String, VectorLoadResult] =
    val rows = source.rows.zipWithIndex.map {
      case (Left(e), i)  => Left(Rej(s"row $i", s"malformed row: $e"))
      case (Right(d), _) => Right(Vector(Entry(d, None)))
    }
    run(runId, expectedRows, source.rows.size.toLong, rows)

  /** Renders every source row through `renderer` and loads one document per rendered chunk. */
  def loadRendered(
      runId: String,
      source: RowSource[SourceRecord],
      renderer: DocumentRenderer,
      expectedRows: Option[Long] = None
  ): Either[String, VectorLoadResult] =
    val rows = source.rows.zipWithIndex.map {
      case (Left(e), i)    => Left(Rej(s"row $i", s"malformed row: $e"))
      case (Right(rec), _) =>
        val built = renderer.render(rec).left.map(w => s"render failed: $w").flatMap { r =>
          val docs = rec match
            case SourceRecord.Summary(e)  => SemanticDocument.fromEvidence(e, r)
            case SourceRecord.Abstract(a) => SemanticDocument.fromAbstract(a, r)
          docs.left
            .map(w => s"rendering refused: $w")
            .map { ds =>
              val (json, ref) = rec match
                case SourceRecord.Summary(e) =>
                  (
                    upickle.default.write(e),
                    SourceRef(
                      e.provenance.source,
                      e.provenance.sourceRecordId,
                      e.provenance.snapshot
                    )
                  )
                case SourceRecord.Abstract(a) =>
                  (
                    upickle.default.write(a),
                    SourceRef(
                      a.provenance.source,
                      a.provenance.sourceRecordId,
                      a.provenance.snapshot
                    )
                  )
              ds.map(d => Entry(d, Some(DocContext(json, ref, r))))
            }
        }
        built.left.map(w => Rej(rec.id, w))
    }
    run(runId, expectedRows, if expectedRows.isDefined then source.rows.size.toLong else 0L, rows)

  /** The mutable state of one load. */
  private final class State(runId: String):
    val rejects = Vector.newBuilder[RejectRecord]
    var rejectCount = 0
    var rowsRead = 0L
    var chunks = 0L
    var dropped = 0L
    var putBatches = 0L
    var fatalError: Option[String] = None
    val counts = mutable.LinkedHashMap.empty[String, (Long, Long)]
    val retries = mutable.LinkedHashMap.empty[String, (Long, String)]
    val seen = mutable.Map.empty[(String, String), String]
    val conflicted = mutable.Set.empty[(String, String)]
    val pending = mutable.Map.empty[String, mutable.LinkedHashMap[String, Entry]]
    val objects = mutable.Map.empty[String, (Option[String], Option[String])]
    val queue = new InMemoryWorkQueue[Entry](settings.queueCapacity)

    def reject(input: String, reason: String): Unit =
      rejectCount += 1
      rejects += RejectRecord.of(Stage, reason, input, runId)

    def noteRetry(subject: String, reason: String, n: Long): Unit =
      val (c, _) = retries.getOrElse(subject, (0L, ""))
      retries(subject) = (c + n, reason)

    def add(index: String, written: Long, unchanged: Long): Unit =
      val (w, u) = counts.getOrElse(index, (0L, 0L))
      counts(index) = (w + written, u + unchanged)

  /** `RetryPolicy.run` that counts the retries it made (calls beyond the first). */
  private def counted[A](st: State, call: String, index: String)(
      op: => Either[RemoteError, A]
  ): Either[RemoteError, A] =
    var calls = 0L
    var last = ""
    val r = settings.retry.run {
      calls += 1
      val x = op
      x.left.foreach(e => last = e.text)
      x
    }
    if calls > 1 then st.noteRetry(s"retry:$call:$index", last, calls - 1)
    r

  private def run(
      runId: String,
      expectedRows: Option[Long],
      countedRows: => Long,
      rows: Iterator[Either[Rej, Vector[Entry]]]
  ): Either[String, VectorLoadResult] =
    stage
      .run(runId, Stage, LogicVersion) {
        val st = new State(runId)
        val have = expectedRows.map(_ => countedRows)
        if expectedRows.exists(e => !have.contains(e)) then
          Left(s"expected ${expectedRows.get} rows, the source gave ${have.get}")
        else
          while st.fatalError.isEmpty && rows.hasNext do
            st.rowsRead += 1
            rows.next() match
              case Left(r)        => st.reject(r.input, r.reason)
              case Right(entries) =>
                entries.foreach { e =>
                  st.chunks += 1
                  while st.fatalError.isEmpty && !st.queue.offer(e) do drain(st, runId)
                }
          if st.fatalError.isEmpty then drain(st, runId)
          if st.fatalError.isEmpty then flushAll(st, runId)
          val failureRecords = recordFailures(st, runId)
          st.fatalError.toLeft(()).map { _ =>
            val rejected = st.rejects.result()
            val per = st.counts.toVector.sortBy(_._1).map { case (ix, (w, u)) =>
              IndexCount(ix, w + u, w, u)
            }
            val written = per.map(_.written).sum
            val unchanged = per.map(_.unchanged).sum
            val report = LoadReport.of(
              "vectors",
              runId,
              LogicVersion,
              Seq(
                "read" -> st.rowsRead,
                "chunks" -> st.chunks,
                "present" -> (written + unchanged),
                "duplicatesDropped" -> st.dropped,
                "rejected" -> rejected.size.toLong
              ) ++ per.map(c => s"index:${c.index}" -> c.present),
              rejected
            )
            val retries = st.retries.map { case (k, (n, _)) => k -> n }.toMap
            (
              (
                st.rowsRead,
                st.chunks,
                written,
                unchanged,
                st.dropped,
                per,
                report.rejected,
                st.putBatches,
                retries,
                failureRecords.toLong,
                st.queue.highWater
              ),
              report
            )
          }
      }
      .map { o =>
        val (read, chunks, written, unchanged, dropped, per, rejected, batches, retries, fr, hw) =
          o.value
        VectorLoadResult(
          read,
          written,
          unchanged,
          dropped,
          rejected,
          per,
          o.report,
          o.reportOutcome,
          o.replay,
          chunks,
          batches,
          retries,
          fr,
          hw
        )
      }

  /** Records rejections and retried calls in the RunLedger; returns how many records were written.
    * A ledger failure here never turns a finished load into a failed one: it is counted out.
    */
  private def recordFailures(st: State, runId: String): Int =
    def subject(s: String) = s.replace('#', '_').take(200)
    var n = 0
    def rec(sub: String, reason: String, times: Long): Unit =
      ledger.recordFailure(runId, Stage, subject(sub), reason, math.min(times, 100000L).toInt) match
        case Right(_) => n += 1
        case Left(_)  => ()
    val all = st.rejects.result()
    all.take(settings.maxFailureRecords).foreach(r => rec(s"reject:${r.input}", r.reason, 1L))
    val over = all.size - settings.maxFailureRecords
    if over > 0 then
      rec("reject:overflow", "further rejections not itemized; see the stored report", over.toLong)
    st.retries.foreach { case (sub, (c, why)) => rec(sub, why, c) }
    n

  private def drain(st: State, runId: String): Unit =
    st.queue.drain(st.queue.size).foreach(e => if st.fatalError.isEmpty then intake(st, runId, e))

  private def intake(st: State, runId: String, e: Entry): Unit =
    val d = e.doc
    validate(d, runId) match
      case Left(why) => st.reject(if d.chunkId.nonEmpty then d.chunkId else d.entityId, why)
      case Right(ix) =>
        val k = (ix, d.chunkId)
        val fp = Hashing.sha256Hex(upickle.default.write(d))
        val dupMsg = "duplicate vector key with different content"
        def waiting = st.pending.getOrElseUpdate(ix, mutable.LinkedHashMap.empty)
        if st.conflicted(k) then st.reject(d.chunkId, dupMsg)
        else
          st.seen.get(k) match
            case None =>
              st.seen(k) = fp
              waiting(d.chunkId) = e
              if waiting.size >= settings.batchSize then flush(st, runId, ix)
            case Some(f) if f == fp => st.dropped += 1
            case Some(_)            =>
              st.conflicted += k
              st.reject(d.chunkId, dupMsg)
              if waiting.remove(d.chunkId).isDefined then st.reject(d.chunkId, dupMsg)

  private def flush(st: State, runId: String, index: String): Unit =
    val w = st.pending(index)
    val batch = w.take(settings.batchSize).toVector
    batch.foreach((id, _) => w.remove(id))
    if batch.nonEmpty then
      processBatch(st, runId, index, batch.map(_._2)).left.foreach(m => st.fatalError = Some(m))

  private def flushAll(st: State, runId: String): Unit =
    st.pending.keys.toVector.sorted.foreach { ix =>
      while st.fatalError.isEmpty && st.pending(ix).nonEmpty do flush(st, runId, ix)
    }

  private def processBatch(
      st: State,
      runId: String,
      index: String,
      batch: Vector[Entry]
  ): Either[String, Unit] =
    val spec = VectorIndexes.spec(index, cfg.dimension)
    val model = embedder.versionId
    fatal(
      counted(st, "GetVectors", index)(vectors.get(index, batch.map(_.doc.chunkId))),
      s"GetVectors $index"
    ).flatMap { existing =>
      val have = existing.map(sv => sv.key -> sv).toMap
      val fresh = Vector.newBuilder[VectorRecord]
      var unchanged = 0L
      var err: Option[String] = None
      batch.foreach { e =>
        val d = e.doc
        have.get(d.chunkId) match
          case Some(sv) =>
            // Unchanged: the stored vector keeps the FIRST run's runId/textUri. Invariant: one
            // served run per vector index; the human rebuilds the index for a new run.
            (sv.metadata.get("textSha256"), sv.metadata.get("embeddingModel")) match
              case (Some(MetaValue.Str(h)), Some(MetaValue.Str(m)))
                  if h == d.textHash && m == model =>
                unchanged += 1
              case (Some(MetaValue.Str(h)), _) if h != d.textHash =>
                st.reject(d.chunkId, "vector key already bound to different text")
              case (Some(MetaValue.Str(_)), _) =>
                st.reject(
                  d.chunkId,
                  s"vector key was embedded with another model than $model; load into a new index version"
                )
              case _ => st.reject(d.chunkId, "stored vector has no textSha256; not overwritten")
          case None =>
            // After an artifact failure, skip prepare: no further S3 write or paid embedding.
            if err.isEmpty then
              prepare(st, e, runId, spec, model) match
                case Left(ArtifactFailure(msg)) => err = Some(msg)
                case Left(Rejected(why))        => st.reject(d.chunkId, why)
                case Right(rec)                 => fresh += rec
      }
      val toPut = fresh.result()
      var written = 0L
      if err.isEmpty && toPut.nonEmpty then
        st.putBatches += 1
        counted(st, "PutVectors", index)(vectors.put(index, toPut)) match
          case Right(_) => written = toPut.size.toLong
          case Left(x)  =>
            toPut.foreach(r => st.reject(r.key, s"PutVectors $index failed: ${x.text}"))
      st.add(index, written, unchanged)
      err.toLeft(())
    }

  private sealed trait Prep
  private final case class Rejected(why: String) extends Prep
  private final case class ArtifactFailure(msg: String) extends Prep

  private def failure(e: ArtifactError, what: String): Prep = e match
    case ArtifactError.Conflict(uri, _, _) =>
      Rejected(s"stored $what $uri differs from this document; not overwritten")
    case ArtifactError.Failure(m) => ArtifactFailure(s"$what object: $m")

  /** The source row and (for several chunks) the whole rendering, stored once per document. */
  private def documentObjects(
      st: State,
      d: SemanticDocument,
      ctx: DocContext,
      runId: String
  ): Either[Prep, (Option[String], Option[String])] =
    val memo =
      s"${d.docType}|${d.entityId}|${d.window.map(_.label)}|${ctx.rendered.textHash}|${Hashing.short(ctx.sourceJson)}"
    st.objects.get(memo) match
      case Some(done) => Right(done)
      case None       =>
        val srcBytes = ctx.sourceJson.getBytes(UTF_8)
        for
          src <- writer
            .write(
              Zone.SemanticContext,
              s"${d.docType}-source",
              runId,
              s"${Hashing.sha256Hex(srcBytes).take(40)}.json",
              srcBytes,
              "application/json",
              Vector(ctx.sourceRef)
            )
            .left
            .map(failure(_, "source text"))
          doc <-
            if ctx.rendered.chunks.size > 1 then
              val bytes = upickle.default.write(ctx.rendered).getBytes(UTF_8)
              writer
                .write(
                  Zone.SemanticContext,
                  s"${d.docType}-document",
                  runId,
                  s"${Hashing.sha256Hex(bytes).take(40)}.json",
                  bytes,
                  "application/json",
                  Vector(ctx.sourceRef)
                )
                .left
                .map(failure(_, "rendered text"))
                .map(r => Some(r._1.uri))
            else Right(None)
        yield
          val done = (Some(src._1.uri), doc)
          st.objects(memo) = done
          done

  private def prepare(
      st: State,
      e: Entry,
      runId: String,
      spec: IndexSpec,
      model: String
  ): Either[Prep, VectorRecord] =
    val d = e.doc
    for
      refs <- e.ctx.fold[Either[Prep, (Option[String], Option[String])]](Right((None, None)))(
        documentObjects(st, d, _, runId)
      )
      full = d.copy(sourceRecordUri = refs._1, documentUri = refs._2)
      bytes = upickle.default.write(full).getBytes(UTF_8)
      obj <- writer
        .write(
          Zone.SemanticContext,
          d.docType,
          runId,
          s"${d.chunkId}.json",
          bytes,
          "application/json",
          Vector(SourceRef(d.source, d.sourceRecordId, d.snapshot))
        )
        .left
        .map(failure(_, "text"))
      vec <- counted(st, "Embed", spec.name)(embedder.embed(d.text)).left
        .map(x => Rejected(s"embedding failed: ${x.text}"))
      md = VectorMetadata.of(d, model, obj._1.uri)
      _ <- VectorMetadata.check(md, d.text).left.map(Rejected(_))
      rec <- VectorLimits.validate(VectorRecord(d.chunkId, vec, md), spec).left.map(Rejected(_))
    yield rec

object VectorLoader:
  val DefaultBatch = 100
  val DefaultQueue = 1000
  val DefaultMaxFailureRecords = 500

  def apply(
      vectors: VectorStore,
      embedder: Embedder,
      writer: ArtifactWriter,
      ledger: RunLedger,
      cfg: VectorConfig,
      settings: VectorLoaderSettings = VectorLoaderSettings()
  ): Either[String, VectorLoader] =
    val needed = SemanticDocument.IndexKeys.values.toVector
    val missing = needed.filterNot(k => cfg.indexes.get(k).exists(_.trim.nonEmpty))
    if missing.nonEmpty then Left(s"vectors.indexes lacks ${missing.mkString(", ")}")
    else if cfg.indexes.filter((k, _) => needed.contains(k)).values.toSet.size != needed.size then
      Left("vector index names must be distinct")
    else if embedder.dimension != cfg.dimension then
      Left(
        s"embedder dimension ${embedder.dimension} differs from vectors.dimension ${cfg.dimension}"
      )
    else if settings.batchSize < 1 || settings.batchSize > VectorLimits.MaxGetBatch then
      Left(s"batchSize must be 1..${VectorLimits.MaxGetBatch}")
    else if settings.queueCapacity < 1 then Left("queueCapacity must be at least 1")
    else if settings.maxFailureRecords < 0 then Left("maxFailureRecords must not be negative")
    else Right(new VectorLoader(vectors, embedder, writer, ledger, cfg, settings))
