package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.*
import scholarchain.core.{Hashing, Provenance, SourceType}
import LocalSpark.*

/** Known cases for the Phase 08 step 00 export additions, on hand-built frames.
  *
  * Paper abstracts ([[Abstracts.build]]). Source works and the er-v1 work map:
  *   - openalex:W1 and openalex:W2 are both members of canonical work dblp:p9 (a merged work), each
  *     with a valid abstract: W1 is emitted under dblp:p9, W2 is ABSTRACT_SUPERSEDED_SAME_WORK;
  *   - openalex:W3 (canonical dblp:p3): its export `abstractTextRef` is another hash:
  *     ABSTRACT_REF_MISMATCH;
  *   - openalex:W4 (canonical dblp:p4): blank text: ABSTRACT_EMPTY;
  *   - openalex:W5: in the export but not in the work map: ABSTRACT_WORK_NOT_CANONICAL;
  *   - openalex:W6 (canonical dblp:p6): valid, emitted with its text byte for byte;
  *   - openalex:W7: not in the export: ABSTRACT_WORK_NOT_FOUND.
  *
  * 7 read, 2 emitted, 5 not emitted, one row per canonical work.
  */
class ExportSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(4, "min")
  private val run = runInfo()
  private val HadoopRun = "run-1111111111111111"

  private def abstractLine(id: String, hash: String, text: String) = ujson
    .Obj(
      "workId" -> id,
      "textHash" -> hash,
      "text" -> text,
      "provenance" -> upickle.default.writeJs(
        Provenance(
          SourceType.OPENALEX,
          id.stripPrefix("openalex:"),
          "2026-11-22",
          HadoopRun,
          "abstract-v1",
          Some("norm-v1")
        )
      )
    )
    .render()

  private val texts = Map(
    "openalex:W1" -> "First abstract.",
    "openalex:W2" -> "Second abstract of the same paper.",
    "openalex:W3" -> "Third.",
    "openalex:W4" -> "   ",
    "openalex:W5" -> "Fifth.",
    "openalex:W6" -> "Sixth: \"quoted\", naïve, 図, two\nlines.",
    "openalex:W7" -> "Seventh."
  )

  private lazy val result =
    val abstracts = Inputs
      .parse(
        lines(texts.toSeq.sorted.map((id, t) => abstractLine(id, Hashing.tagged(t), t))),
        Schemas.abstractRecord,
        Schemas.Required.abstractRecord
      )
      .valid
    val ref = (id: String) =>
      if id == "openalex:W3" then Hashing.tagged("another text") else Hashing.tagged(texts(id))
    val works = frame(
      StructType(
        Seq(StructField("workId", StringType), StructField("abstractTextRef", StringType))
      ),
      (1 to 6).map(i => s"openalex:W$i").map(id => Row(id, ref(id)))
    )
    val workMap = frame(
      StructType(Seq(StructField("memberWorkId", StringType), StructField("workId", StringType))),
      Seq(
        Row("openalex:W1", "dblp:p9"),
        Row("openalex:W2", "dblp:p9"),
        Row("openalex:W3", "dblp:p3"),
        Row("openalex:W4", "dblp:p4"),
        Row("openalex:W6", "dblp:p6")
      )
    )
    Abstracts.build(abstracts, works, workMap, run, "er-v1", inputPresent = true)

  test("abstracts: one row per canonical work, text byte for byte, provenance of the source row") {
    val rows = result.rows
      .orderBy("workId")
      .select(
        col("workId"),
        col("text"),
        col("textHash"),
        col("sourceWorkId"),
        col("upstreamRunId"),
        col("provenance.source"),
        col("provenance.sourceRecordId"),
        col("provenance.runId"),
        col("provenance.schemaVersion"),
        col("provenance.policyVersion")
      )
      .collect()
      .map(r => (0 until r.length).map(r.get).toVector)
      .toVector
    val w6 = texts("openalex:W6")
    assertEquals(
      rows,
      Vector(
        Vector(
          "dblp:p6",
          w6,
          Hashing.tagged(w6),
          "openalex:W6",
          HadoopRun,
          "OPENALEX",
          "W6",
          run.runId,
          "abstract-v1",
          "er-v1"
        ),
        Vector(
          "dblp:p9",
          "First abstract.",
          Hashing.tagged("First abstract."),
          "openalex:W1",
          HadoopRun,
          "OPENALEX",
          "W1",
          run.runId,
          "abstract-v1",
          "er-v1"
        )
      )
    )
    assertEquals(
      result.rows.columns.toSeq.diff(Abstracts.ExtraFields),
      Seq("workId", "textHash", "text", "provenance")
    )
  }

  test("abstracts: every abstract not emitted is a counted reject with its reason") {
    val rejects = result.rejects
      .select(col("reason"), col("input"))
      .collect()
      .map(r => ujson.read(r.getString(1))("sourceWorkId").str -> r.getString(0))
      .toMap
    assertEquals(
      rejects,
      Map(
        "openalex:W2" -> "ABSTRACT_SUPERSEDED_SAME_WORK",
        "openalex:W3" -> "ABSTRACT_REF_MISMATCH",
        "openalex:W4" -> "ABSTRACT_EMPTY",
        "openalex:W5" -> "ABSTRACT_WORK_NOT_CANONICAL",
        "openalex:W7" -> "ABSTRACT_WORK_NOT_FOUND"
      )
    )
    val r = result.report
    assertEquals((r("read").num, r("emitted").num, r("notEmitted").num), (7.0, 2.0, 5.0))
    assertEquals(r("invariant_abstractsUnaccounted").num, 0.0)
    assertEquals(r("invariant_abstractDuplicateWorkIds").num, 0.0)
  }

  test("abstracts: a run without abstracts emits none and says so") {
    val empty = Inputs
      .parse(lines(Seq.empty), Schemas.abstractRecord, Schemas.Required.abstractRecord)
      .valid
    val works = frame(
      StructType(
        Seq(StructField("workId", StringType), StructField("abstractTextRef", StringType))
      ),
      Seq(Row("dblp:p1", null))
    )
    val workMap = frame(
      StructType(Seq(StructField("memberWorkId", StringType), StructField("workId", StringType))),
      Seq(Row("dblp:p1", "dblp:p1"))
    )
    val r = Abstracts.build(empty, works, workMap, run, "er-v1", inputPresent = false)
    assertEquals(r.rows.count(), 0L)
    assertEquals(
      (r.report("inputPresent").bool, r.report("read").num, r.report("emitted").num),
      (false, 0.0, 0.0)
    )
  }

  private def coauthorTable(byWindow: Map[String, Long]) =
    Export.Table(
      "coauthor-edges",
      "/x/coauthor-edges",
      "coauthor-edge-v1",
      Seq("windowLabel"),
      byWindow.values.sum,
      byWindow.size.toLong,
      byWindow.map((w, n) => s"windowLabel=$w" -> n)
    )

  test("neptune windows: every exported coauthor window is listed, counts checked per window") {
    val configured = Seq("1950-2026", "2000-2009", "2010-2014", "2015-2026")
    // Two configured windows hold edges; the empty ones stay listed as configured.
    val ok = Export.coauthorWindows(
      coauthorTable(Map("1950-2026" -> 5L, "2010-2014" -> 2L)),
      Map("1950-2026" -> 5L, "2010-2014" -> 2L),
      configured
    )
    assertEquals(ok.listed, configured)
    assertEquals(ok.exported, Map("1950-2026" -> 5L, "2010-2014" -> 2L))
    assertEquals(ok.mismatched, Seq.empty)
    // A window the configuration does not name is still listed, after the configured ones; a
    // count that differs between coauthor-edges and the graph edges is reported.
    val odd = Export.coauthorWindows(
      coauthorTable(Map("1950-2026" -> 5L, "1990-1999" -> 1L)),
      Map("1950-2026" -> 4L, "1990-1999" -> 1L),
      configured
    )
    assertEquals(odd.listed, configured :+ "1990-1999")
    assertEquals(odd.mismatched, Seq("1950-2026"))
  }

  // C14: the communities table as JSON lines ---------------------------------------------------------

  test("communities JSON lines: known graph G, community sizes, both record kinds, strict core") {
    import java.nio.file.{Files, Path}
    import scala.jdk.CollectionConverters.*
    import scholarchain.core.{EvidenceItem, EvidenceType, JsonLines, SchemaVersions, Validate}
    // Graph G of GraphAlgorithmsSuite as two-author works (year 2020) and n10 alone: communities
    // {n1..n4} 4, {n5,n6,n7} 3, {n8,n9} 2, {n10} 1, and one cross-community edge n4-n5.
    val gEdges = Seq(
      "n1" -> "n2",
      "n1" -> "n3",
      "n2" -> "n3",
      "n3" -> "n4",
      "n4" -> "n5",
      "n5" -> "n6",
      "n5" -> "n7",
      "n6" -> "n7",
      "n8" -> "n9"
    )
    val works = gEdges.map((a, b) => (s"w-$a-$b", Seq(a, b))) :+ ("w-n10" -> Seq("n10"))
    val authored = frame(
      StructType(
        Seq("researcherId", "workId").map(StructField(_, StringType)) ++
          Seq("year", "teamSize").map(StructField(_, IntegerType))
      ),
      works.flatMap((w, rs) => rs.map(r => Row(r, w, 2020, rs.size)))
    )
    val pairWorks = frame(
      StructType(
        Seq("a", "b", "workId").map(StructField(_, StringType)) :+ StructField("year", IntegerType)
      ),
      gEdges.map((a, b) => Row(a, b, s"w-$a-$b", 2020))
    )
    val r = WindowAnalytics.run(authored, pairWorks, All, run)
    val base = Files.createTempDirectory("scholarchain-communities").toString
    val table = Export.write(
      Export.communities(r, run, "s3://graph/communities"),
      base,
      "communities",
      SchemaVersions.Evidence,
      Seq("windowLabel", "recordKind"),
      Some(Export.CommunitiesJson)
    )
    val j = table.jsonLines.getOrElse(fail("no JSON-lines copy"))
    val community = "windowLabel=1950-2026/recordKind=community"
    val cross = "windowLabel=1950-2026/recordKind=cross_community_edge"
    assertEquals(
      (j.rows, j.byPartition, j.idMismatches),
      (5L, Map(community -> 4L, cross -> 1L), 0L)
    )
    assertEquals(j.byPartition, table.byPartition)
    val dir = Path.of(s"$base/jsonl/communities")
    assert(Files.isDirectory(dir.resolve(community)) && Files.isDirectory(dir.resolve(cross)))
    val ls = Files
      .walk(dir)
      .iterator
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
      .toVector
      .flatMap(p => Files.readAllLines(p).asScala.filter(_.nonEmpty))
    assertEquals(ls.size, 5)
    // Dropping the extra fields leaves exactly the core record, decoded strictly and validated.
    val items = ls.map { l =>
      val o = ujson.read(l).obj
      assertEquals(o("windowLabel").str, "1950-2026")
      assertEquals(o("recordKind").str, o("values")("recordKind").str, "column = value")
      Export.CommunitiesJson.extraFields.foreach(o.remove)
      JsonLines
        .decodeValid[EvidenceItem](ujson.write(o), Validate.evidence)
        .fold(e => fail(s"$e\n$l"), identity)
    }
    assert(items.forall(_.evidenceType == EvidenceType.COMMUNITY_RECORD))
    assertEquals(items.map(_.evidenceId).distinct.size, 5)
    val (cms, xs) = items.partition(_.values("recordKind") == "community")
    // Community sizes by member: the community ID of each researcher comes from the metrics rows.
    val cmOf = r.metrics
      .select("researcherId", "communityId")
      .collect()
      .map(x => x.getString(0) -> x.getString(1))
      .toMap
    val size = cms.map(c => c.values("communityId") -> c.values("communitySize")).toMap
    assertEquals(
      Seq("n1", "n5", "n8", "n10").map(n => size(cmOf(n))),
      Seq("4", "3", "2", "1")
    )
    assert(cms.forall(c => c.values("window") == "1950-2026" && c.researcherIds.isEmpty))
    assertEquals(xs.size, 1)
    val x = xs.head
    assertEquals(x.researcherIds, Vector("n4", "n5"))
    assertEquals(
      Set(x.values("communityA"), x.values("communityB")),
      Set(cmOf("n4"), cmOf("n5"))
    )
    assertEquals((x.values("jointPublicationCount"), x.values("window")), ("1", "1950-2026"))
    assert(!x.values.contains("communitySize"), "cross rows carry no community size")
  }
