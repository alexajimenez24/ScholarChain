package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.{
  DocumentRenderer,
  PaperAbstract,
  RenderedChunk,
  RenderedDocument,
  SemanticDocument
}

/** A semantic-summary template: a fixed layout of literal anchors. Spark's `Summaries` job fills
  * the layout from computed tables and source records; rag pins the layout per version. A row's
  * text conforms when it starts with the first anchor and holds every later anchor in order. The
  * anchors are built only from template literals and the row's structured fields (window, entity,
  * researcher IDs, graph path), so a text that conforms names the same entity, window and path as
  * its fields.
  */
sealed trait SummaryTemplate:
  def id: String
  def version: Int
  def docType: String
  final def tag: String = s"$id-v$version"

  /** Checks the shape of the structured fields, then gives the ordered anchors. */
  def anchors(window: String, entityId: String, e: EvidenceItem): Either[String, Vector[String]]

private def need(ok: Boolean, why: => String): Either[String, Unit] = Either.cond(ok, (), why)

object ResearcherProfileTemplate extends SummaryTemplate:
  val id = "tpl-researcher-profile"
  val version = 1
  val docType = SemanticDocument.ResearcherProfile
  def anchors(w: String, ent: String, e: EvidenceItem) =
    for
      _ <- need(Ids.isResearcherId(ent), s"entity $ent is not a researcher ID")
      _ <- need(e.researcherIds == Vector(ent), "researcherIds must be the profiled researcher")
      _ <- need(e.graphPath.isEmpty, "a researcher profile has no graphPath")
    yield Vector(
      s"Researcher profile, window $w. Name: ",
      s" ($ent). Publications: ",
      ". Distinct coauthors: ",
      ". Total coauthor occurrences: ",
      ". Average coauthors per paper: ",
      ". Largest team: ",
      " coauthors on one paper; works with that team size: ",
      ", listed: ",
      ". Weighted degree (joint publications summed over coauthors): ",
      ". Component ",
      ". Community: ",
      ". Coreness: ",
      ". Coauthors outside own community: ",
      ". Communities connected: ",
      ". Participation coefficient: ",
      ". Sampled betweenness estimate: ",
      ". Topics (works): ",
      ". Venues (works): ",
      ". Institutions (works): ",
      ". Identity confidence: "
    )

object CommunityProfileTemplate extends SummaryTemplate:
  val id = "tpl-collaboration-community"
  val version = 1
  val docType = SemanticDocument.CommunityProfile
  def anchors(w: String, ent: String, e: EvidenceItem) =
    for
      _ <- need(ent.startsWith("cm:"), s"entity $ent is not a community ID")
      _ <- need(e.researcherIds.isEmpty, "a community profile lists no researcherIds")
      _ <- need(e.graphPath.isEmpty, "a community profile has no graphPath")
    yield Vector(
      s"Collaboration community $ent, window $w. Members: ",
      ". Coauthor edges inside the community: ",
      ". Coauthor edges to other communities: ",
      ". Members (distinct coauthors in the window): ",
      ". Topics of members' works (works): ",
      ". Venues of members' works (works): ",
      ". Institutions of members (works): "
    )

object PathSummaryTemplate extends SummaryTemplate:
  val id = "tpl-collaboration-path"
  val version = 1
  val docType = SemanticDocument.PathSummary
  def anchors(w: String, ent: String, e: EvidenceItem) =
    for
      _ <- need(
        Ids.isEvidenceId(ent) && ent.startsWith("ev:graph_path:"),
        s"entity $ent is not a GRAPH_PATH evidence ID"
      )
      path <- e.graphPath.toRight("a path summary needs a graphPath")
      _ <- need(path.size >= 2 && path.distinct.size == path.size, "graphPath is not a simple path")
      _ <- need(path.forall(Ids.isResearcherId), "graphPath holds a non-researcher ID")
      _ <- need(
        e.researcherIds == Vector(path.head, path.last),
        "researcherIds must be the path's two endpoints"
      )
      _ <- need(!e.values.contains("institutionId"), "a path summary has no institutionId")
    yield Vector(
      s"Collaboration path, window $w: ",
      s" (${path.head}) to ",
      s" (${path.last}). Shortest path length: ${path.size - 1} hops; ",
      " shortest paths of this length. Chain: ",
      ". Coauthor edges: "
    )

object TopicSummaryTemplate extends SummaryTemplate:
  val id = "tpl-topic-institution-summary"
  val version = 1
  val docType = SemanticDocument.TopicSummary
  def anchors(w: String, ent: String, e: EvidenceItem) =
    for
      _ <- need(!ent.exists(_.isWhitespace), s"entity '$ent' contains whitespace")
      _ <- need(e.researcherIds.isEmpty, "a topic summary lists no researcherIds")
      _ <- need(e.graphPath.isEmpty, "a topic summary has no graphPath")
    yield Vector(
      s"Topic summary, window $w: ",
      s" ($ent). Works: ",
      ", published ",
      ". Researchers: ",
      ". Institutions on these works (works): ",
      ". Venues (works): "
    )

/** Representative-paper template: rendered here from the `paper-abstracts` row. The header holds
  * only structured fields of the row; the abstract follows byte for byte.
  *
  * v2 (Phase 08 step 01b) drops the work ID from the header that v1 had (`Work: {workId}. `). The
  * work ID already travels as the document's `entityId` and the vector's `workId` metadata, and the
  * loader refuses a chunk whose text repeats a metadata value of 40 or more characters (71 of the
  * 6,292 real abstracts of run-ccf619c509611f06 have such a DBLP key). An identifier in the
  * embedded text added no meaning either.
  */
object PaperAbstractTemplate:
  val id = "tpl-paper-abstract"
  val version = 2
  val docType = SemanticDocument.PaperAbstractType
  val tag: String = s"$id-v$version"
  val SourceSchema = "abstract-v1"

  def text(a: PaperAbstract): String =
    s"Paper abstract. Source record: ${a.provenance.source} ${a.provenance.sourceRecordId}. " +
      s"Abstract: ${a.text}"

object Templates:
  val Summaries: Vector[SummaryTemplate] =
    Vector(
      ResearcherProfileTemplate,
      CommunityProfileTemplate,
      PathSummaryTemplate,
      TopicSummaryTemplate
    )

  /** docType to template tag, for every document type. */
  val Versions: Map[String, String] =
    Summaries.map(t => t.docType -> t.tag).toMap +
      (PaperAbstractTemplate.docType -> PaperAbstractTemplate.tag)

  def summary(docType: String): Option[SummaryTemplate] = Summaries.find(_.docType == docType)

  /** A C0 control or DEL never comes from a template; such a text is refused, never cleaned. */
  def hasControl(s: String): Boolean = s.exists(c => c < ' ' || c == '\u007f')

  /** Every anchor in order, the first at offset 0, and a final full stop. */
  def conforms(text: String, anchors: Vector[String]): Either[String, Unit] =
    if anchors.isEmpty || !text.startsWith(anchors.head) then
      Left(s"text does not start with '${anchors.headOption.getOrElse("")}'")
    else
      anchors.tail
        .foldLeft[Either[String, Int]](Right(anchors.head.length)) { (at, a) =>
          at.flatMap { i =>
            val j = text.indexOf(a, i)
            if j < 0 then Left(s"text lacks '${a.trim}' in template order") else Right(j + a.length)
          }
        }
        .flatMap(_ => need(text.endsWith("."), "text does not end with a full stop"))

/** Renders the text of every semantic document, the one place this happens (rag). Pure: the same
  * row always gives a byte-identical [[RenderedDocument]].
  *
  *   - Semantic summaries: the Spark text is adopted byte for byte after the row passes the core
  *     evidence validator, the identity checks (chunk ID, evidence ID, hash, provenance) and its
  *     template. Re-rendering is impossible from the row's fields alone and would give one identity
  *     a second text; a row that does not conform is refused with the reason.
  *   - Paper abstracts: rendered by [[PaperAbstractTemplate]].
  *
  * Both are then cut by the chunk policy; chunk IDs follow [[Chunking.chunkIds]]. This is rag's
  * implementation of the storage port [[scholarchain.storage.DocumentRenderer]]: the vector loader
  * takes every document text, chunk span and chunk ID from here, and never renders text itself.
  */
final class SemanticRenderer private (indexes: Map[String, String], val policy: ChunkPolicy)
    extends DocumentRenderer:
  private val WindowLabel = "^(\\d{4})-(\\d{4})$".r
  private val ValueKeys = Set("docType", "vectorIndex", "entityId", "window", "institutionId")

  def templateVersions: Map[String, String] = Templates.Versions

  def renderSummary(e: EvidenceItem): Either[String, RenderedDocument] =
    def value(k: String) = e.values.get(k).filter(_.trim.nonEmpty).toRight(s"values.$k is missing")
    for
      _ <- Validate.evidence(e) match
        case Vector() => Right(())
        case errs     => Left(errs.mkString("; "))
      _ <- need(e.evidenceType == EvidenceType.SEMANTIC_PROFILE, s"type ${e.evidenceType}")
      _ <- need(e.workId.isEmpty, "a semantic summary has no workId")
      extra = e.values.keySet -- ValueKeys
      _ <- need(extra.isEmpty, s"unknown values ${extra.toVector.sorted.mkString(", ")}")
      docType <- value("docType")
      tpl <- Templates.summary(docType).toRight(s"no summary template for $docType")
      ent <- value("entityId")
      label <- value("window")
      window <- label match
        case WindowLabel(a, b) if Validate.window(TimeWindow(a.toInt, b.toInt)).isEmpty =>
          Right(TimeWindow(a.toInt, b.toInt))
        case other => Left(s"values.window '$other' is not a window label")
      index <- indexes.get(docType).toRight(s"no index for $docType")
      _ <- need(e.values.get("vectorIndex").contains(index), s"vectorIndex is not $index")
      text <- e.text.filter(_.nonEmpty).toRight("no text")
      _ <- need(e.textHash.contains(Hashing.tagged(text)), "textHash does not match text")
      _ <- need(!Templates.hasControl(text), "text holds a control character")
      _ <- need(e.provenance.source == SourceType.SCHOLARCHAIN, "source is not SCHOLARCHAIN")
      _ <- need(e.provenance.sourceRecordId == ent, "provenance names another entity")
      sv <- e.provenance.policyVersion.filter(_.nonEmpty).toRight("no policyVersion")
      _ <- need(
        e.chunkId.contains(Ids.chunk(ent, sv, docType, 0, text)),
        "chunkId does not match entity, policy version, document type and text"
      )
      _ <- need(
        e.evidenceId == Ids.evidence(EvidenceType.SEMANTIC_PROFILE, e.chunkId.get),
        "evidenceId does not match chunkId"
      )
      anchors <- tpl.anchors(label, ent, e)
      _ <- Templates.conforms(text, anchors).left.map(w => s"${tpl.tag}: $w")
      doc <- assemble(docType, ent, Some(window), index, tpl.tag, sv, Some(e.evidenceId), text)
    yield doc

  def renderAbstract(a: PaperAbstract): Either[String, RenderedDocument] =
    val t = PaperAbstractTemplate
    for
      _ <- need(Ids.isWorkId(a.workId), s"malformed work ID ${a.workId}")
      _ <- need(a.text.trim.nonEmpty, "empty abstract")
      _ <- need(a.textHash == Hashing.tagged(a.text), "textHash does not match text")
      _ <- need(!Templates.hasControl(a.text), "abstract holds a control character")
      _ <- need(a.provenance.schemaVersion == t.SourceSchema, s"schema is not ${t.SourceSchema}")
      rec = a.provenance.sourceRecordId
      _ <- need(rec.nonEmpty && !rec.exists(_.isWhitespace), s"malformed source record '$rec'")
      index <- indexes.get(t.docType).toRight(s"no index for ${t.docType}")
      doc <- assemble(t.docType, a.workId, None, index, t.tag, t.tag, None, t.text(a))
    yield doc

  private def assemble(
      docType: String,
      entityId: String,
      window: Option[TimeWindow],
      index: String,
      template: String,
      sourceVersion: String,
      evidenceId: Option[String],
      text: String
  ): Either[String, RenderedDocument] =
    Chunking.spans(text, policy).map { spans =>
      val ids = Chunking.chunkIds(entityId, sourceVersion, docType, text, spans, policy)
      val chunks = spans.zip(ids).zipWithIndex.map { case ((s, id), i) =>
        val c = text.substring(s.start, s.end)
        RenderedChunk(id, i, s.start, s.end, c, Hashing.tagged(c))
      }
      RenderedDocument(
        docType,
        entityId,
        window,
        index,
        template,
        policy.version,
        sourceVersion,
        evidenceId,
        text,
        Hashing.tagged(text),
        chunks
      )
    }

object SemanticRenderer:
  /** Needs a distinct index for each of the five document types (`vectors.indexes`). */
  def apply(
      cfg: VectorConfig,
      policy: ChunkPolicy = ChunkPolicy.V1
  ): Either[String, SemanticRenderer] =
    val byDocType = SemanticDocument.IndexKeys.flatMap((d, k) => cfg.indexes.get(k).map(d -> _))
    val missing = SemanticDocument.IndexKeys.keySet -- byDocType.keySet
    if missing.nonEmpty then Left(s"no index for ${missing.toVector.sorted.mkString(", ")}")
    else if byDocType.values.toSet.size != byDocType.size then Left("index names must be distinct")
    else policy.validate.map(p => new SemanticRenderer(byDocType, p))

  /** Document identities `(docType, entityId, window)` that received two different texts, and chunk
    * IDs bound to two different texts. Empty means one identity, one text.
    */
  def conflicts(docs: Iterable[RenderedDocument]): Vector[String] =
    val byIdentity = docs
      .groupBy(d => (d.docType, d.entityId, d.window.map(_.label).getOrElse("")))
      .collect { case (k, ds) if ds.map(_.textHash).toSet.size > 1 => s"identity $k has two texts" }
    val byChunk = docs
      .flatMap(_.chunks)
      .groupBy(_.chunkId)
      .collect { case (k, cs) if cs.map(_.textHash).toSet.size > 1 => s"chunk $k has two texts" }
    (byIdentity ++ byChunk).toVector.sorted
