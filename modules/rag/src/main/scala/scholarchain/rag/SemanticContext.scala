package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import upickle.default.ReadWriter
import java.nio.charset.StandardCharsets.UTF_8

/** Where a context item's text came from: the source record, run and rendering versions. */
final case class ContextProvenance(
    source: SourceType,
    sourceRecordId: String,
    snapshot: String,
    runId: String,
    template: String,
    chunkPolicy: String
) derives ReadWriter

/** One retrieved chunk, as the synthesis agent sees it (format `semantic-context-v1`, JSON schema
  * `prompts/schemas/semantic-context-v1.json`).
  *
  * The text was read from ordinary S3 through the vector's `textUri` pointer, never from vector
  * metadata, and its hash was checked against the vector's `textSha256` before it was sanitized
  * ([[UntrustedText]]) and bounded. `sanitized` and `truncated` say whether `text` differs from the
  * stored text that `textSha256` names.
  *
  * Descriptive only. The one number is `score`, the cosine similarity of the query to the chunk (1
  * minus the cosine distance, 4 decimals): a retrieval rank, never evidence of a coauthorship edge
  * or a graph metric. The format has no field for a count, path, hop, component, community size or
  * bridge value; those come only from graph and metric evidence ([[GraphValuePolicy]]). Numbers
  * written inside `text` stay untrusted words.
  */
final case class SemanticContextItem(
    evidenceId: String,
    evidenceType: EvidenceType,
    chunkId: String,
    index: String,
    documentType: String,
    entityId: String,
    window: Option[String],
    researcherIds: Vector[String],
    score: Double,
    embeddingModel: String,
    textUri: String,
    textSha256: String,
    sourceUri: String,
    provenance: ContextProvenance,
    text: String,
    sanitized: Boolean,
    truncated: Boolean
) derives ReadWriter:

  /** The core evidence record of this item, for the bundle's `semantic` partition. Its `values` are
    * descriptive strings only; its text hash is of the context text, and the stored text's hash is
    * kept as `storedTextSha256`.
    */
  def toEvidence: EvidenceItem =
    EvidenceItem(
      SchemaVersions.Evidence,
      evidenceId,
      evidenceType,
      Option.when(documentType == SemanticDocument.PaperAbstractType)(entityId),
      researcherIds,
      textUri,
      Some(chunkId),
      None,
      score,
      Map(
        "contextFormat" -> SemanticContext.Version,
        "trust" -> SemanticContext.Trust,
        "documentType" -> documentType,
        "index" -> index,
        "entityId" -> entityId,
        "embeddingModel" -> embeddingModel,
        "storedTextSha256" -> textSha256,
        "sourceUri" -> sourceUri
      ) ++ window.map("window" -> _),
      Some(text),
      Some(Hashing.tagged(text)),
      Provenance(
        provenance.source,
        provenance.sourceRecordId,
        provenance.snapshot,
        provenance.runId,
        SchemaVersions.Evidence,
        Some(provenance.template)
      )
    )

/** The compact semantic context handed to the synthesis agent: untrusted descriptive data. */
final case class SemanticContext(
    format: String,
    trust: String,
    embeddingModel: String,
    items: Vector[SemanticContextItem]
) derives ReadWriter:
  def toJson: String = JsonLines.encode(this)

object SemanticContext:
  val Version = "semantic-context-v1"
  val Trust = "untrusted-data"

/** Size bounds of one semantic context. `maxCharsPerItem` (UTF-16 units) equals the chunk policy's
  * maximum, so a whole chunk normally fits; `maxTotalChars` is about 2,000 tokens, a sixth of the
  * configured 12,000-token context budget, leaving the rest for graph evidence.
  */
final case class ContextBudget(maxItems: Int, maxCharsPerItem: Int, maxTotalChars: Int):
  def validate: Either[String, ContextBudget] =
    if maxItems < 1 || maxItems > 50 then Left("maxItems must be 1..50")
    else if maxCharsPerItem < 100 || maxCharsPerItem > 4000 then
      Left("maxCharsPerItem must be 100..4000")
    else if maxTotalChars < maxCharsPerItem then Left("maxTotalChars must be >= maxCharsPerItem")
    else Right(this)

object ContextBudget:
  val V1: ContextBudget = ContextBudget(maxItems = 10, maxCharsPerItem = 2000, maxTotalChars = 8000)

/** Retrieved text is untrusted data. `untrusted-text-v1` replaces every control (C0, DEL, C1),
  * format (zero-width, bidirectional override, BOM), private-use, unassigned, lone-surrogate, line-
  * and paragraph-separator character, and every whitespace, with a space, then collapses spaces.
  * The text never becomes instructions: it is only ever the JSON string `text` of an item.
  * Instruction-like words are kept as words, not filtered: a filter of phrases is unreliable, and
  * the item stays marked `untrusted-data`.
  */
object UntrustedText:
  val Version = "untrusted-text-v1"

  private val UnsafeTypes: Set[Int] = Set(
    Character.CONTROL,
    Character.FORMAT,
    Character.PRIVATE_USE,
    Character.SURROGATE,
    Character.UNASSIGNED,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR
  ).map(_.toInt)

  private def unsafe(cp: Int): Boolean =
    UnsafeTypes(Character.getType(cp)) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)

  def sanitize(s: String): String =
    val sb = new java.lang.StringBuilder(s.length)
    var pendingSpace = false
    s.codePoints().forEach { cp =>
      if unsafe(cp) then pendingSpace = sb.length > 0
      else
        if pendingSpace then sb.append(' ')
        pendingSpace = false
        sb.appendCodePoint(cp)
    }
    sb.toString

  /** At most `max` UTF-16 units, never splitting a surrogate pair. */
  def bound(s: String, max: Int): String =
    if s.length <= max then s
    else if Character.isHighSurrogate(s.charAt(max - 1)) then s.substring(0, max - 1)
    else s.substring(0, max)

/** Which evidence may state an exact count, shortest path, component, community or bridge value
  * (`graph-value-policy-v1`): only records from the graph and metric stores. Semantic context
  * (vector hits and their text) is descriptive and never supports such a value.
  */
object GraphValuePolicy:
  val Version = "graph-value-policy-v1"
  import EvidenceType.*
  val Authoritative: Set[EvidenceType] =
    Set(METRIC_RECORD, COAUTHOR_EDGE, GRAPH_PATH, COMPONENT_RECORD, COMMUNITY_RECORD)
  val Descriptive: Set[EvidenceType] = Set(SEMANTIC_PROFILE, TEXT_CHUNK)

  def maySupportGraphValue(e: EvidenceItem): Boolean = Authoritative(e.evidenceType)

/** Typed filters of one index query. They are validated and become metadata filter terms; user text
  * never becomes a filter or a query, only the embedded query vector.
  */
final case class ContextFilter(
    window: Option[TimeWindow] = None,
    entityId: Option[String] = None,
    researcherId: Option[String] = None
)

final case class ContextTarget(docType: String, topK: Int, filter: ContextFilter = ContextFilter())

final case class ContextQuery(text: String, targets: Vector[ContextTarget])

/** A vector hit that did not become a context item, and why (kept for the audit, not the LLM). */
final case class DroppedHit(index: String, key: String, reason: String)

final case class SemanticRetrieval(context: SemanticContext, dropped: Vector[DroppedHit])

/** Builds semantic context from S3 Vectors hits through their ordinary-S3 text pointers.
  *
  * For each hit the metadata must name a document type of the queried index, the query's embedding
  * version, and a `textUri` inside the configured semantic-context zone. The object there must be
  * at most [[SemanticContextRetriever.MaxObjectBytes]], decode as a `semantic-doc-v2` document of
  * the same chunk, document type, entity, window and run, and its text must hash to both its own
  * `textHash` and the vector's `textSha256`. A hit that fails any check is dropped with the reason;
  * nothing is repaired or guessed. Items are ordered by score, then chunk ID, and bounded by the
  * [[ContextBudget]].
  */
final class SemanticContextRetriever private (
    vectors: VectorStore,
    objects: ObjectStore,
    embedder: Embedder,
    indexes: Map[String, String],
    zonePrefix: String,
    budget: ContextBudget
):
  import SemanticContextRetriever.*

  val embeddingModel: String = embedder.versionId

  private def need(ok: Boolean, why: => String): Either[String, Unit] = Either.cond(ok, (), why)

  private def filterTerms(t: ContextTarget): Either[String, Vector[FilterTerm]] =
    val f = t.filter
    for
      _ <- f.window.fold(Right(()))(w =>
        need(Validate.window(w).isEmpty, s"bad window ${w.label}").flatMap(_ =>
          need(t.docType != SemanticDocument.PaperAbstractType, "paper-abstract has no window")
        )
      )
      _ <- f.entityId.fold(Right(()))(e =>
        need(
          e.nonEmpty && e.length <= 400 && !e.exists(_.isWhitespace),
          "malformed entity ID filter"
        )
      )
      _ <- f.researcherId.fold(Right(()))(r =>
        need(Ids.isResearcherId(r), "malformed researcher ID filter")
      )
    yield Vector(
      f.window.map(w => FilterTerm.Eq("window", MetaValue.Str(w.label))),
      f.entityId.map(e => FilterTerm.Eq("entityId", MetaValue.Str(e))),
      f.researcherId.map(r => FilterTerm.Eq("researcherIds", MetaValue.Str(r)))
    ).flatten

  /** Embeds the question once, queries each target index, and builds the bounded context. */
  def retrieve(q: ContextQuery): Either[String, SemanticRetrieval] =
    val text = q.text.trim
    for
      _ <- need(
        text.nonEmpty && text.length <= MaxQueryChars,
        s"query text must be 1..$MaxQueryChars"
      )
      _ <- need(q.targets.nonEmpty && q.targets.size <= MaxTargets, s"1..$MaxTargets targets")
      plans <- q.targets.foldLeft[Either[String, Vector[(String, Int, Vector[FilterTerm])]]](
        Right(Vector.empty)
      ) { (acc, t) =>
        for
          done <- acc
          ix <- indexes.get(t.docType).toRight(s"unknown document type ${t.docType}")
          _ <- need(t.topK >= 1 && t.topK <= MaxTopK, s"topK must be 1..$MaxTopK")
          terms <- filterTerms(t)
        yield done :+ (ix, t.topK, terms)
      }
      qv <- embedder.embed(text).left.map(e => s"query embedding failed: ${e.text}")
      hits <- plans.foldLeft[Either[String, Vector[(String, VectorHit)]]](Right(Vector.empty)) {
        case (acc, (ix, k, terms)) =>
          acc.flatMap(done =>
            vectors
              .query(ix, qv, k, terms)
              .left
              .map(e => s"query $ix: ${e.text}")
              .map(hs => done ++ hs.map(ix -> _))
          )
      }
    yield assemble(hits)

  /** Bounded, ordered context from hits already returned by the vector store. */
  def assemble(hits: Vector[(String, VectorHit)]): SemanticRetrieval =
    val unique = hits.distinctBy((ix, h) => (ix, h.key))
    val built = unique.map((ix, h) => fromHit(ix, h).left.map(DroppedHit(ix, h.key, _)))
    val ok = built.collect { case Right(i) => i }.sortBy(i => (-i.score, i.chunkId))
    val kept = Vector.newBuilder[SemanticContextItem]
    val over = Vector.newBuilder[DroppedHit]
    var n = 0
    var chars = 0
    ok.foreach { i =>
      if n < budget.maxItems && chars + i.text.length <= budget.maxTotalChars then
        kept += i
        n += 1
        chars += i.text.length
      else over += DroppedHit(i.index, i.chunkId, "context budget")
    }
    SemanticRetrieval(
      SemanticContext(
        SemanticContext.Version,
        SemanticContext.Trust,
        embeddingModel,
        kept.result()
      ),
      built.collect { case Left(d) => d } ++ over.result()
    )

  /** One hit to one item, through its S3 pointer; `Left` gives the reason the hit is dropped. */
  def fromHit(index: String, hit: VectorHit): Either[String, SemanticContextItem] =
    def str(k: String): Either[String, String] = hit.metadata.get(k) match
      case Some(MetaValue.Str(s)) if s.nonEmpty => Right(s)
      case _                                    => Left(s"metadata $k is missing")
    for
      docType <- str("documentType")
      _ <- need(indexes.get(docType).contains(index), s"$docType does not belong to index $index")
      model <- str("embeddingModel")
      _ <- need(model == embeddingModel, s"embedded with $model, queried with $embeddingModel")
      uri <- str("textUri")
      _ <- need(uri.startsWith(zonePrefix), s"textUri is outside $zonePrefix")
      sha <- str("textSha256")
      entity <- str("entityId")
      runId <- str("runId")
      // cosine distance is 0..2; float rounding can put an identical vector a hair below 0
      _ <- need(hit.distance > -1e-6 && hit.distance < 2.0 + 1e-6, s"distance ${hit.distance}")
      bytes <- objects.get(uri).left.map(e => s"text object unreadable: ${e.take(200)}")
      _ <- need(bytes.length <= MaxObjectBytes, s"text object exceeds $MaxObjectBytes bytes")
      doc <- JsonLines
        .decode[SemanticDocument](new String(bytes, UTF_8))
        .left
        .map(e => s"text object is not a semantic document: ${e.take(200)}")
      _ <- need(doc.schemaVersion == SemanticDocument.Version, s"schema ${doc.schemaVersion}")
      _ <- need(doc.chunkId == hit.key, s"text object holds chunk ${doc.chunkId}")
      _ <- need(
        doc.text.nonEmpty && doc.textHash == Hashing.tagged(doc.text),
        "stored text does not match its own hash"
      )
      _ <- need(doc.textHash == sha, "stored text differs from the vector's textSha256")
      _ <- need(
        (doc.docType, doc.entityId, doc.runId) == (docType, entity, runId),
        "text object names another document type, entity or run than the vector"
      )
      _ <- need(
        hit.metadata.get("window") == doc.window.map(w => MetaValue.Str(w.label)),
        "text object names another window than the vector"
      )
      clean = UntrustedText.sanitize(doc.text)
      text = UntrustedText.bound(clean, budget.maxCharsPerItem)
      _ <- need(text.nonEmpty, "no text left after sanitizing")
      evType =
        if docType == SemanticDocument.PaperAbstractType then EvidenceType.TEXT_CHUNK
        else EvidenceType.SEMANTIC_PROFILE
      item = SemanticContextItem(
        Ids.evidence(evType, hit.key),
        evType,
        hit.key,
        index,
        docType,
        doc.entityId,
        doc.window.map(_.label),
        doc.researcherIds,
        math.rint(math.max(-1.0, math.min(1.0, 1.0 - hit.distance)) * 10000.0) / 10000.0,
        model,
        uri,
        sha,
        doc.sourceUri,
        ContextProvenance(
          doc.source,
          doc.sourceRecordId,
          doc.snapshot,
          doc.runId,
          doc.template,
          doc.chunkPolicy
        ),
        text,
        clean != doc.text,
        text.length < clean.length
      )
      _ <- Validate.evidence(item.toEvidence) match
        case Vector() => Right(())
        case errs     => Left(s"context evidence invalid: ${errs.mkString("; ").take(200)}")
    yield item

object SemanticContextRetriever:
  val MaxQueryChars = 2000
  val MaxTargets = 10
  val MaxTopK = 20

  /** A chunk object is a few kilobytes; a larger object is refused before it is decoded. */
  val MaxObjectBytes = 64 * 1024

  def apply(
      cfg: ScholarChainConfig,
      vectors: VectorStore,
      objects: ObjectStore,
      embedder: Embedder,
      budget: ContextBudget = ContextBudget.V1
  ): Either[String, SemanticContextRetriever] =
    val byDocType =
      SemanticDocument.IndexKeys.flatMap((d, k) => cfg.vectors.indexes.get(k).map(d -> _))
    val missing = SemanticDocument.IndexKeys.keySet -- byDocType.keySet
    for
      b <- budget.validate
      _ <- Either.cond(
        missing.isEmpty,
        (),
        s"no index for ${missing.toVector.sorted.mkString(", ")}"
      )
      _ <- Either.cond(
        embedder.dimension == cfg.vectors.dimension,
        (),
        s"embedder dimension ${embedder.dimension} differs from vectors.dimension ${cfg.vectors.dimension}"
      )
      bucket <- new S3Layout(cfg.storage).bucket(Zone.SemanticContext)
    yield new SemanticContextRetriever(
      vectors,
      objects,
      embedder,
      byDocType,
      s"s3://$bucket/${Zone.SemanticContext.prefix}/",
      b
    )
