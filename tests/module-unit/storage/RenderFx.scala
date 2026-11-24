package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.VecFx.*

/** A stub renderer for storage tests: the storage module never renders text, so the tests stand in
  * for rag. Summaries keep the row text; abstracts get a header, so a test can tell the rendered
  * text from the raw one. Texts longer than `maxChunk` are cut into fixed spans.
  */
object RenderFx:
  val Template = "tpl-stub-v1"
  val Policy = "chunk-stub-v1"

  def stub(
      maxChunk: Int = 100000,
      failOn: Set[String] = Set.empty,
      throwOn: Set[String] = Set.empty,
      idFor: (String, String, String, Int, String) => String = (ent, dt, full, i, chunk) =>
        if full == chunk then Ids.chunk(ent, mv, dt, 0, chunk)
        else Ids.chunk(ent, s"$mv+stub+${Hashing.sha256Hex(full)}", dt, i, chunk)
  ): StubRenderer = new StubRenderer(maxChunk, failOn, throwOn, idFor)

  final class StubRenderer(
      maxChunk: Int,
      failOn: Set[String],
      throwOn: Set[String],
      idFor: (String, String, String, Int, String) => String
  ) extends DocumentRenderer:
    var calls = 0
    def templateVersions: Map[String, String] = Map("all" -> Template)

    private def assemble(
        docType: String,
        entity: String,
        window: Option[TimeWindow],
        evidenceId: Option[String],
        text: String
    ): RenderedDocument =
      val spans = text.grouped(maxChunk).toVector
      val chunks = spans.zipWithIndex.map { (c, i) =>
        val start = i * maxChunk
        RenderedChunk(
          idFor(entity, docType, text, i, c),
          i,
          start,
          start + c.length,
          c,
          Hashing.tagged(c)
        )
      }
      RenderedDocument(
        docType,
        entity,
        window,
        vcfg.indexes(SemanticDocument.IndexKeys(docType)),
        Template,
        Policy,
        mv,
        evidenceId,
        text,
        Hashing.tagged(text),
        chunks
      )

    def renderSummary(e: EvidenceItem): Either[String, RenderedDocument] =
      calls += 1
      val id = e.chunkId.getOrElse(e.evidenceId)
      if throwOn(id) then throw new IllegalStateException("boom")
      else if failOn(id) then Left("stub refuses this row")
      else
        val Array(a, b) = e.values("window").split('-')
        Right(
          assemble(
            e.values("docType"),
            e.values("entityId"),
            Some(TimeWindow(a.toInt, b.toInt)),
            Some(e.evidenceId),
            e.text.get
          )
        )

    def renderAbstract(a: PaperAbstract): Either[String, RenderedDocument] =
      calls += 1
      if throwOn(a.workId) then throw new IllegalStateException("boom")
      else if failOn(a.workId) then Left("stub refuses this row")
      else
        Right(
          assemble(
            SemanticDocument.PaperAbstractType,
            a.workId,
            None,
            None,
            s"Paper abstract. Work: ${a.workId}. Abstract: ${a.text}"
          )
        )

  /** A `semantic-summaries` row in the Spark export shape. */
  def summaryRow(
      docType: String,
      entity: String,
      text: String,
      window: TimeWindow = w2010,
      runId: String = run,
      researchers: Vector[String] = Vector.empty
  ): EvidenceItem =
    val chunk = Ids.chunk(entity, mv, docType, 0, text)
    EvidenceItem(
      SchemaVersions.Evidence,
      Ids.evidence(EvidenceType.SEMANTIC_PROFILE, chunk),
      EvidenceType.SEMANTIC_PROFILE,
      None,
      researchers,
      "s3://sc-graph/spark/semantic-summaries",
      Some(chunk),
      None,
      0.0,
      Map(
        "docType" -> docType,
        "vectorIndex" -> vcfg.indexes(SemanticDocument.IndexKeys(docType)),
        "entityId" -> entity,
        "window" -> window.label
      ),
      Some(text),
      Some(Hashing.tagged(text)),
      Provenance(
        SourceType.SCHOLARCHAIN,
        entity,
        "2026-11-22",
        runId,
        SchemaVersions.Evidence,
        Some(mv)
      )
    )

  def profileRow(n: Int, text: String = ""): EvidenceItem =
    val ent = s"sc:r:v1:${"%020x".format(n)}"
    summaryRow(
      SemanticDocument.ResearcherProfile,
      ent,
      if text.isEmpty then s"Researcher profile number $n works on graph topics and databases"
      else text,
      researchers = Vector(ent)
    )

  def abstractRow(n: Int, text: String = ""): PaperAbstract =
    val t = if text.isEmpty then s"Abstract number $n about distributed graph processing" else text
    PaperAbstract(
      s"dblp:conf/x/Paper$n",
      Hashing.tagged(t),
      t,
      Provenance(SourceType.DBLP, s"dblp:conf/x/Paper$n", "2026-11-22", run, "abstract-v1", None)
    )

  def sources(rows: Seq[EvidenceItem | PaperAbstract]): RowSource[SourceRecord] =
    RowSource.of(rows.map {
      case e: EvidenceItem  => SourceRecord.Summary(e)
      case a: PaperAbstract => SourceRecord.Abstract(a)
    })
