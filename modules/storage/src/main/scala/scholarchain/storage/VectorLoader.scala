package scholarchain.storage

import scholarchain.core.*
import upickle.default.ReadWriter
import java.nio.charset.StandardCharsets.UTF_8

/** One document to embed: the text, who and what it describes, and where it came from. The same
  * record is stored (as JSON) in ordinary S3, in the semantic-context zone; the vector keeps only a
  * pointer to it. `paper-abstract` documents have no window.
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
    sourceUri: String
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
  val Version = "semantic-doc-v1"
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

  /** A Spark `semantic-summaries` row (an `EvidenceItem` of type SEMANTIC_PROFILE). The row must
    * carry its chunk ID, text, the matching text hash, and `docType`, `entityId` and `window` in
    * `values`; nothing is guessed.
    */
  def fromEvidence(e: EvidenceItem): Either[String, SemanticDocument] =
    def value(k: String) = e.values.get(k).filter(_.trim.nonEmpty)
    for
      _ <- Either.cond(e.schemaVersion == SchemaVersions.Evidence, (), s"schema ${e.schemaVersion}")
      _ <- Either.cond(
        e.evidenceType == EvidenceType.SEMANTIC_PROFILE,
        (),
        s"evidence type ${e.evidenceType} is not SEMANTIC_PROFILE"
      )
      chunk <- e.chunkId.toRight("no chunkId")
      text <- e.text.toRight("no text")
      _ <- Either.cond(
        e.textHash.contains(Hashing.tagged(text)),
        (),
        "textHash does not match text"
      )
      docType <- value("docType").toRight("values.docType is missing")
      entity <- value("entityId").toRight("values.entityId is missing")
      label <- value("window").toRight("values.window is missing")
      window <- label match
        case WindowLabel(a, b) => Right(TimeWindow(a.toInt, b.toInt))
        case other             => Left(s"values.window '$other' is not a window label")
    yield SemanticDocument(
      Version,
      docType,
      entity,
      chunk,
      text,
      Hashing.tagged(text),
      Some(window),
      e.researcherIds,
      value("topicId"),
      value("institutionId"),
      value("communityId"),
      value("vectorIndex"),
      e.provenance.source,
      e.provenance.sourceRecordId,
      e.provenance.snapshot,
      e.provenance.runId,
      Some(e.evidenceId),
      e.sourceUri
    )

  /** A Hadoop abstract. The chunk ID is the core formula with `sourceVersion` (the normalization
    * version) and ordinal 0; a text longer than the embedder takes is refused by the embedder.
    */
  def fromAbstract(a: PaperAbstract, sourceVersion: String): Either[String, SemanticDocument] =
    for
      _ <- Either.cond(a.text.nonEmpty, (), "empty abstract")
      _ <- Either.cond(a.textHash == Hashing.tagged(a.text), (), "textHash does not match text")
    yield SemanticDocument(
      Version,
      PaperAbstractType,
      a.workId,
      Ids.chunk(a.workId, sourceVersion, PaperAbstractType, 0, a.text),
      a.text,
      a.textHash,
      None,
      Vector.empty,
      None,
      None,
      None,
      None,
      a.provenance.source,
      a.provenance.sourceRecordId,
      a.provenance.snapshot,
      a.provenance.runId,
      None,
      s"abstract:${a.workId}"
    )

/** The vector metadata of a document: compact, filterable fields plus pointers; never the text.
  *
  * Filterable: `documentType`, `entityId`, `source`, `runId`, `snapshot`, `embeddingModel`,
  * `window`, `windowStartYear`, `windowEndYear`, `researcherIds`, `communityId`, `topicId`,
  * `institutionId`, `workId`, `pathId`. Non-filterable (see [[VectorIndexes.NonFilterable]]):
  * `textUri`, `textSha256`, `evidenceId`, `sourceUri`.
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
    common ++ window ++ entityKey.toVector ++ optional

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

final case class VectorLoadResult(
    read: Long,
    written: Long,
    unchanged: Long,
    duplicatesDropped: Long,
    rejected: Vector[RejectRecord],
    perIndex: Vector[IndexCount],
    report: StoredObject,
    reportOutcome: WriteOutcome,
    replay: Boolean
)

final case class VectorLoaderSettings(
    batchSize: Int = VectorLoader.DefaultBatch,
    retry: RetryPolicy = RetryPolicy(),
    /** When set, a chunk ID must equal `Ids.chunk(entity, chunkSourceVersion, docType, 0, text)`.
      */
    chunkSourceVersion: Option[String] = None
)

/** Loads semantic documents into the five S3 Vectors indexes.
  *
  * Keys: the vector key is the document's `chunkId` (`c:` + 20 hex, core `Ids.chunk`), which hashes
  * the entity, source version, document type, ordinal and text, so a changed text is a new key. Per
  * index, in groups of `batchSize` (1..100, the `GetVectors` limit; `PutVectors` allows 500):
  *
  *   1. `GetVectors` the group's keys (bounded retry of transient failures).
  *   2. A key that exists with the same `textSha256` and `embeddingModel` is `unchanged`: no
  *      embedding call, no write. A key bound to different text, or to the same text under another
  *      embedding model, is rejected; nothing is overwritten (use a new index version for a new
  *      model).
  *   3. A new document: its JSON is stored create-only in the semantic-context zone (content hash
  *      compared on replay), then embedded, then its metadata is checked against the limits and the
  *      compactness policy.
  *   4. The valid vectors are put in one `PutVectors` (bounded retry). A batch that still fails has
  *      every vector rejected with the reason; a replay retries those.
  *
  * Rejected: malformed rows, wrong run, unknown document type, text hash mismatch, wrong dimension,
  * metadata too large or text-like, duplicate keys with different content (identical duplicates are
  * dropped and counted), key bound to other text, embedding failure, failed put. Rejections are
  * returned, written to the stored report and never repaired. The ledger stage is `load-vectors`.
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
  val LogicVersion = "vector-load-v1"
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
          d.chunkId == Ids.chunk(d.entityId, v, d.docType, 0, d.text),
          (),
          "chunk ID does not match its entity, version, document type and text"
        )
      )
    yield index

  private def fatal[A](r: Either[RemoteError, A], what: String): Either[String, A] =
    r.left.map(e => s"$what: ${e.text}")

  def load(
      runId: String,
      source: RowSource[SemanticDocument],
      expectedRows: Option[Long] = None
  ): Either[String, VectorLoadResult] =
    stage
      .run(runId, Stage, LogicVersion) {
        val all = source.rows.toVector
        val rejects = Vector.newBuilder[RejectRecord]
        def reject(input: String, reason: String): Unit =
          rejects += RejectRecord.of(Stage, reason, input, runId)
        if expectedRows.exists(_ != all.size.toLong) then
          Left(s"expected ${expectedRows.get} rows, the source gave ${all.size}")
        else
          val checked = all.zipWithIndex.flatMap {
            case (Left(e), i)  => reject(s"row $i", s"malformed row: $e"); None
            case (Right(d), i) =>
              validate(d, runId) match
                case Left(why) =>
                  reject(if d.chunkId.nonEmpty then d.chunkId else s"row $i", why); None
                case Right(ix) => Some((ix, d))
          }
          val groups = checked.groupBy((ix, d) => (ix, d.chunkId))
          val distinct = groups.toVector.sortBy(_._1).flatMap { case ((ix, key), ds) =>
            if ds.map(_._2).distinct.size == 1 then Some(ix -> ds.head._2)
            else
              ds.foreach(_ => reject(key, "duplicate vector key with different content"))
              None
          }
          val dropped =
            groups.values.map(ds => if ds.map(_._2).distinct.size == 1 then ds.size - 1 else 0).sum
          val perIndex = distinct.groupBy(_._1).toVector.sortBy(_._1)
          var written = 0L
          var unchanged = 0L
          val counts = Vector.newBuilder[IndexCount]
          val failure = perIndex.iterator
            .map { (index, docs) =>
              loadIndex(index, docs.map(_._2), runId, reject).map { (w, u) =>
                written += w; unchanged += u
                counts += IndexCount(index, w + u, w, u)
              }
            }
            .collectFirst { case Left(e) => e }
          failure.toLeft(()).map { _ =>
            val rejected = rejects.result()
            val per = counts.result()
            val report = LoadReport.of(
              "vectors",
              runId,
              LogicVersion,
              Seq(
                "read" -> all.size.toLong,
                "present" -> (written + unchanged),
                "duplicatesDropped" -> dropped.toLong,
                "rejected" -> rejected.size.toLong
              ) ++ per.map(c => s"index:${c.index}" -> c.present),
              rejected
            )
            ((all.size.toLong, written, unchanged, dropped.toLong, per, report.rejected), report)
          }
      }
      .map { o =>
        val (read, written, unchanged, dropped, per, rejected) = o.value
        VectorLoadResult(
          read,
          written,
          unchanged,
          dropped,
          rejected,
          per,
          o.report,
          o.reportOutcome,
          o.replay
        )
      }

  private def loadIndex(
      index: String,
      docs: Vector[SemanticDocument],
      runId: String,
      reject: (String, String) => Unit
  ): Either[String, (Long, Long)] =
    val spec = VectorIndexes.spec(index, cfg.dimension)
    var written = 0L
    var unchanged = 0L
    val batches = docs.grouped(settings.batchSize)
    var err: Option[String] = None
    while err.isEmpty && batches.hasNext do
      val batch = batches.next()
      fatal(
        settings.retry.run(vectors.get(index, batch.map(_.chunkId))),
        s"GetVectors $index"
      ) match
        case Left(e)         => err = Some(e)
        case Right(existing) =>
          val have = existing.map(v => v.key -> v).toMap
          val fresh = Vector.newBuilder[VectorRecord]
          batch.foreach { d =>
            have.get(d.chunkId) match
              case Some(v) =>
                // Unchanged: the stored vector keeps the FIRST run's runId/textUri. Invariant: one
                // served run per vector index; the human rebuilds the index for a new run.
                (v.metadata.get("textSha256"), v.metadata.get("embeddingModel")) match
                  case (Some(MetaValue.Str(h)), Some(MetaValue.Str(m)))
                      if h == d.textHash && m == embedder.modelId =>
                    unchanged += 1
                  case (Some(MetaValue.Str(h)), _) if h != d.textHash =>
                    reject(d.chunkId, "vector key already bound to different text")
                  case (Some(MetaValue.Str(_)), _) =>
                    reject(
                      d.chunkId,
                      s"vector key was embedded with another model than ${embedder.modelId}; load into a new index version"
                    )
                  case _ => reject(d.chunkId, "stored vector has no textSha256; not overwritten")
              case None =>
                // After an artifact failure, skip prepare: no further S3 write or paid embedding.
                if err.isEmpty then
                  prepare(d, runId, spec) match
                    case Left(ArtifactFailure(msg)) => err = err.orElse(Some(msg))
                    case Left(Rejected(why))        => reject(d.chunkId, why)
                    case Right(rec)                 => fresh += rec
          }
          val toPut = fresh.result()
          if err.isEmpty && toPut.nonEmpty then
            settings.retry.run(vectors.put(index, toPut)) match
              case Right(_) => written += toPut.size
              case Left(e)  =>
                toPut.foreach(v => reject(v.key, s"PutVectors $index failed: ${e.text}"))
    err.toLeft((written, unchanged))

  private sealed trait Prep
  private final case class Rejected(why: String) extends Prep
  private final case class ArtifactFailure(msg: String) extends Prep

  private def prepare(
      d: SemanticDocument,
      runId: String,
      spec: IndexSpec
  ): Either[Prep, VectorRecord] =
    val bytes = upickle.default.write(d).getBytes(UTF_8)
    val stored = writer.write(
      Zone.SemanticContext,
      d.docType,
      runId,
      s"${d.chunkId}.json",
      bytes,
      "application/json",
      Vector(SourceRef(d.source, d.sourceRecordId, d.snapshot))
    ) match
      case Left(ArtifactError.Conflict(uri, _, _)) =>
        Left(Rejected(s"stored text $uri differs from this document; not overwritten"))
      case Left(ArtifactError.Failure(m)) => Left(ArtifactFailure(s"text object: $m"))
      case Right((obj, _))                => Right(obj)
    for
      obj <- stored
      vec <- settings.retry
        .run(embedder.embed(d.text))
        .left
        .map(e => Rejected(s"embedding failed: ${e.text}"))
      md = VectorMetadata.of(d, embedder.modelId, obj.uri)
      _ <- VectorMetadata.check(md, d.text).left.map(Rejected(_))
      rec <- VectorLimits.validate(VectorRecord(d.chunkId, vec, md), spec).left.map(Rejected(_))
    yield rec

object VectorLoader:
  val DefaultBatch = 100

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
    else Right(new VectorLoader(vectors, embedder, writer, ledger, cfg, settings))
