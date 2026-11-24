package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.Fixtures.{cfg, layout, withStub, http}
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

/** A small export shaped exactly like the Phase 06 tables: graph-nodes (core GraphNode),
  * graph-edges (core GraphEdge with string properties as Export.scala writes them) and
  * coauthor-edges (core CoauthorEdge), one COAUTHORED_WITH edge per pair per window. The real local
  * run is `run-769e0d672ecd3c19`; the fixture uses its own run ID and the same policy versions
  * (graph-v1 for edges and for venue, topic and institution nodes; er-v1 for researchers and
  * works).
  */
object NeptuneFx:
  val policy = "graph-v1"
  val erPolicy = "er-v1"
  val snapshot = "2026-11-22"
  val run: String = Ids.run("""{"fixture":"neptune"}""")
  val windows: Vector[TimeWindow] =
    Vector(TimeWindow(1950, 2026), TimeWindow(2010, 2014), TimeWindow(2015, 2026))
  val w1950 = windows(0)
  val w2010 = windows(1)
  val evidenceBase = "s3://sc-graph/spark/snapshot=2026-11-22/run_id=" + run

  /** A destructive statement, assembled so that this file holds no such literal. */
  val wipe: String = "MATCH (n) DETACH " + "DELETE n"

  val r1 = "orcid:0000-0002-7347-9716"
  val r2 = "sc:r:v1:0123456789abcdef0123"
  val r3 = "sc:r:v1:fedcba9876543210fedc"
  val wk1 = "dblp:conf/x/A14"
  val wk2 = "dblp:conf/x/B14"
  val wk3 = "dblp:conf/x/C12"
  val venue = "venue:openalex:S4306463937"
  val topic = "openalex:T10427"
  val inst = "openalex:I1174212"

  def prov(id: String, schema: String, pol: String): Provenance =
    Provenance(SourceType.SCHOLARCHAIN, id, snapshot, run, schema, Some(pol))

  def researcher(id: String, name: String, extra: Map[String, String] = Map.empty): GraphNode =
    GraphNode(
      id,
      "Researcher",
      Map(
        "displayName" -> name,
        "mentionCount" -> "2",
        "mentionIds" -> s"${Ids.mention(wk1, 0)}|${Ids.mention(wk2, 0)}",
        "identityConfidence" -> "0.9",
        "unresolved" -> "false",
        "identityConflict" -> "false"
      ) ++ extra,
      prov(id, SchemaVersions.GraphNode, erPolicy)
    )

  def work(id: String, year: Int, title: String): GraphNode =
    GraphNode(
      id,
      "Work",
      Map(
        "title" -> title,
        "year" -> year.toString,
        "recordType" -> "inproceedings",
        "memberWorkIds" -> id,
        "unresolved" -> "false",
        "identityConflict" -> "false"
      ),
      prov(id, SchemaVersions.GraphNode, erPolicy)
    )

  def simple(id: String, label: String, props: Map[String, String]): GraphNode =
    GraphNode(id, label, props, prov(id, SchemaVersions.GraphNode, policy))

  val nodes: Vector[GraphNode] = Vector(
    researcher(r1, "Ada Lovelace", Map("orcid" -> "0000-0002-7347-9716")),
    researcher(r2, "Grace \"Amazing\" Hopper, Jr."),
    researcher(r3, "Edsger Dijkstra"),
    work(wk1, 2014, "Graphs, \"Hybrid\" Methods\nand Their Limits"),
    work(wk2, 2014, "Second Paper"),
    work(wk3, 2012, "Third Paper"),
    simple(venue, "Venue", Map("name" -> "Vision Reference", "kind" -> "JOURNAL")),
    simple(topic, "Topic", Map("name" -> "Graph theory")),
    simple(inst, "Institution", Map("name" -> "Univ. A", "countryCode" -> "US"))
  )

  def edge(
      tpe: String,
      from: String,
      to: String,
      evidence: String,
      props: Map[String, String]
  ): GraphEdge =
    val undirected = GraphLabels.UndirectedEdgeLabels(tpe)
    GraphEdge(
      Ids.graphEdge(tpe, from, to, policy, evidence),
      if undirected then Ids.orderedPair(from, to)._1 else from,
      tpe,
      if undirected then Ids.orderedPair(from, to)._2 else to,
      props,
      prov(if evidence.isEmpty then from else evidence, SchemaVersions.GraphEdge, policy)
    )

  def authored(r: String, w: String, pos: Int, year: Int): GraphEdge =
    val m = Ids.mention(w, pos)
    edge(
      "AUTHORED",
      r,
      w,
      m,
      Map("position" -> pos.toString, "teamSize" -> "2", "year" -> year.toString, "mentionIds" -> m)
    )

  def record(
      a: String,
      b: String,
      window: TimeWindow,
      first: Int,
      last: Int,
      works: Vector[String],
      count: Long
  ): CoauthorEdge =
    val id = Ids.coauthorEdge(a, b, window, policy)
    CoauthorEdge(
      SchemaVersions.Edge,
      id,
      a,
      b,
      count,
      first,
      last,
      works,
      window,
      policy,
      prov(id, SchemaVersions.Edge, policy)
    )

  /** The graph-edges row Export.scala writes for a coauthor-edges record. */
  def coEdge(r: CoauthorEdge): GraphEdge =
    edge(
      "COAUTHORED_WITH",
      r.researcherA,
      r.researcherB,
      r.edgeId,
      Map(
        "coauthorEdgeId" -> r.edgeId,
        "window" -> r.window.label,
        "jointPublicationCount" -> r.jointPublicationCount.toString,
        "firstCollaborationYear" -> r.firstCollaborationYear.toString,
        "lastCollaborationYear" -> r.lastCollaborationYear.toString,
        "representativeWorkIds" -> r.representativeWorkIds.mkString("|")
      )
    )

  val records: Vector[CoauthorEdge] = Vector(
    record(r1, r2, w1950, 2014, 2014, Vector(wk1, wk2), 2),
    record(r1, r2, w2010, 2014, 2014, Vector(wk1, wk2), 2),
    record(r2, r3, w1950, 2012, 2012, Vector(wk3), 1),
    record(r2, r3, w2010, 2012, 2012, Vector(wk3), 1)
  )

  val edges: Vector[GraphEdge] = Vector(
    authored(r1, wk1, 0, 2014),
    authored(r2, wk1, 1, 2014),
    authored(r1, wk2, 0, 2014),
    authored(r2, wk2, 1, 2014),
    authored(r2, wk3, 0, 2012),
    authored(r3, wk3, 1, 2012),
    edge("PUBLISHED_IN", wk1, venue, "", Map.empty),
    edge("HAS_TOPIC", wk1, topic, "", Map.empty),
    edge("AFFILIATED_WITH", r1, inst, "", Map("supportingMentionCount" -> "1"))
  ) ++ records.map(coEdge)

  def input(
      ns: Iterable[GraphNode] = nodes,
      es: Iterable[GraphEdge] = edges,
      rs: Iterable[CoauthorEdge] = records
  ): GraphExportInput =
    GraphExportInput(run, snapshot, policy, windows, ns, es, rs, evidenceBase)

  lazy val plan: NeptuneLoadPlan = NeptuneExport.build(input())

  def file(p: NeptuneLoadPlan, name: String): CsvFile = p.files.find(_.name == name).get

  /** Rows of one CSV file as maps from the header cell to the value. */
  def table(p: NeptuneLoadPlan, name: String): Vector[Map[String, String]] =
    val (h, rows) = NeptuneCsv.parse(file(p, name).bytes).toOption.get
    rows.map(r => h.zip(r).toMap)

  def reasons(p: NeptuneLoadPlan): Vector[String] = p.rejections.map(_.reason)

class NeptuneIdentifierSuite extends munit.FunSuite:
  import NeptuneFx.*

  test("the fixture builds a clean plan with one file per label and type") {
    assertEquals(plan.rejections, Vector.empty[Rejection])
    assertEquals(
      plan.files.map(_.name),
      Vector(
        "nodes-Institution.csv",
        "nodes-Researcher.csv",
        "nodes-Topic.csv",
        "nodes-Venue.csv",
        "nodes-Work.csv",
        "edges-AFFILIATED_WITH.csv",
        "edges-AUTHORED.csv",
        "edges-COAUTHORED_WITH.csv",
        "edges-HAS_TOPIC.csv",
        "edges-PUBLISHED_IN.csv"
      )
    )
  }

  test("every Neptune node ID equals a Spark nodeId and every edge ID a Spark edgeId") {
    val nodeIds =
      plan.files.filter(_.kind == "node").flatMap(f => table(plan, f.name).map(_(":ID")))
    val edgeIds =
      plan.files.filter(_.kind == "relationship").flatMap(f => table(plan, f.name).map(_(":ID")))
    assertEquals(nodeIds.sorted, nodes.map(_.nodeId).sorted)
    assertEquals(edgeIds.sorted, edges.map(_.edgeId).sorted)
    // the relationship ends are Spark node IDs too
    val ends = plan.files
      .filter(_.kind == "relationship")
      .flatMap(f => table(plan, f.name).flatMap(r => Vector(r(":START_ID"), r(":END_ID"))))
    assert(ends.forall(nodeIds.toSet))
  }

  test("a coauthor graph edge ID is the core ID, deterministic and distinct per window") {
    val rows = table(plan, "edges-COAUTHORED_WITH.csv")
    assertEquals(rows.size, 4)
    rows.foreach { r =>
      assertEquals(
        r(":ID"),
        Ids.graphEdge(
          "COAUTHORED_WITH",
          r(":START_ID"),
          r(":END_ID"),
          policy,
          r("coauthorEdgeId:String")
        )
      )
      assert(Ids.isEdgeId(r(":ID")))
      assert(r(":START_ID") < r(":END_ID"), "pair order")
    }
    assertEquals(rows.map(_(":ID")).distinct.size, 4)
  }

  test("each COAUTHORED_WITH edge resolves to its supporting works, authored by both ends") {
    val workIds = table(plan, "nodes-Work.csv").map(_(":ID")).toSet
    val authored = table(plan, "edges-AUTHORED.csv").map(r => r(":START_ID") -> r(":END_ID")).toSet
    val rows = table(plan, "edges-COAUTHORED_WITH.csv")
    rows.foreach { r =>
      val works = r("representativeWorkIds:String").split("\\|").toVector
      assert(works.nonEmpty)
      works.foreach { w =>
        assert(workIds(w), s"$w is a Work node")
        assert(authored(r(":START_ID") -> w) && authored(r(":END_ID") -> w), s"$w authored by both")
      }
    }
  }

  test("COAUTHORED_WITH carries count, years, window and an evidence pointer") {
    assertEquals(
      file(plan, "edges-COAUTHORED_WITH.csv").header,
      ":ID,:START_ID,:END_ID,:TYPE,coauthorEdgeId:String,window:String,windowStartYear:Int," +
        "windowEndYear:Int,jointPublicationCount:Long,firstCollaborationYear:Int," +
        "lastCollaborationYear:Int,representativeWorkIds:String,evidencePointer:String," +
        "source:String,sourceRecordId:String,snapshot:String,runId:String,policyVersion:String"
    )
    val r = table(plan, "edges-COAUTHORED_WITH.csv")
      .find(x => x("window:String") == "2010-2014" && x(":END_ID") == r3)
      .get
    assertEquals(r("jointPublicationCount:Long"), "1")
    assertEquals(r("firstCollaborationYear:Int"), "2012")
    assertEquals(r("lastCollaborationYear:Int"), "2012")
    assertEquals(r("windowStartYear:Int"), "2010")
    assertEquals(r("windowEndYear:Int"), "2014")
    assertEquals(
      r("evidencePointer:String"),
      s"$evidenceBase/coauthor-edges/windowLabel=2010-2014#${r("coauthorEdgeId:String")}"
    )
    assertEquals(r("runId:String"), run)
  }

  test("every coauthor edge carries one configured window; overlapping windows are parallel") {
    val rows = table(plan, "edges-COAUTHORED_WITH.csv")
    assert(rows.forall(r => windows.map(_.label).contains(r("window:String"))))
    val perPair = rows.groupBy(r => r(":START_ID") -> r(":END_ID"))
    assertEquals(perPair.size, 2)
    assert(perPair.values.forall(_.map(_("window:String")).distinct.size == 2))
  }

  test("the manifest states what is and is not exported") {
    val m = plan.manifest
    assertEquals(
      m.nodeCounts.map(c => c.label -> c.count).toMap,
      Map("Researcher" -> 3, "Work" -> 3, "Venue" -> 1, "Institution" -> 1, "Topic" -> 1)
    )
    assertEquals(
      m.edgeCounts.map(c => c.label -> c.count).toMap,
      Map(
        "AUTHORED" -> 6,
        "COAUTHORED_WITH" -> 4,
        "PUBLISHED_IN" -> 1,
        "HAS_TOPIC" -> 1,
        "AFFILIATED_WITH" -> 1
      )
    )
    assertEquals(m.notExportedEdges.toSet, Set("RESOLVES_TO", "DERIVED_FROM", "SUPPORTED_BY"))
    assertEquals(m.notExportedNodes.toSet, Set("AuthorMention", "TextChunk", "DatasetSnapshot"))
    assertEquals(m.windows, windows.map(_.label))
  }

class NeptuneValidationSuite extends munit.FunSuite:
  import NeptuneFx.*

  private def withoutNode(id: String) = nodes.filterNot(_.nodeId == id)

  /** Replace a record and rebuild its graph edge from the changed record. */
  private def withRecord(old: CoauthorEdge, changed: CoauthorEdge) =
    (
      records.map(r => if r == old then changed else r),
      edges.map(e => if e == coEdge(old) then coEdge(changed) else e)
    )

  test("a missing endpoint Researcher rejects the edge and records the reason") {
    val p = NeptuneExport.build(input(ns = withoutNode(r3)))
    assert(
      p.rejections.exists(r =>
        r.kind == "edge" && r.reason.contains(s"$r3 is not a loaded Researcher")
      )
    )
    assertEquals(table(p, "edges-COAUTHORED_WITH.csv").size, 2)
  }

  test("a coauthor record that no graph edge references is rejected") {
    val rec = records.head
    val p = NeptuneExport.build(input(es = edges.filterNot(_ == coEdge(rec))))
    assert(
      p.rejections.exists(r =>
        r.kind == "coauthor-edge" && r.id == rec.edgeId &&
          r.reason.contains("no COAUTHORED_WITH graph edge")
      )
    )
    assertEquals(table(p, "edges-COAUTHORED_WITH.csv").size, 3)
  }

  test("endpoints in the wrong order are rejected") {
    val rec = records.head
    val badEdge = coEdge(rec).copy(fromId = rec.researcherB, toId = rec.researcherA)
    val p = NeptuneExport.build(input(es = edges.map(e => if e == coEdge(rec) then badEdge else e)))
    assert(
      p.rejections.exists(r =>
        r.id == badEdge.edgeId && r.reason.contains("not canonically ordered")
      )
    )
    assertEquals(table(p, "edges-COAUTHORED_WITH.csv").size, 3)
  }

  test("a record whose pair is not in canonical order is rejected") {
    val rec = records.head
    val flipped = rec.copy(researcherA = rec.researcherB, researcherB = rec.researcherA)
    val (rs, es) = withRecord(rec, flipped)
    val p = NeptuneExport.build(input(es = es, rs = rs))
    assert(reasons(p).exists(_.contains("canonically ordered")))
  }

  test("inconsistent years are rejected: reversed, outside the window") {
    val rec = records.head
    val reversed = rec.copy(firstCollaborationYear = 2014, lastCollaborationYear = 2013)
    val (rs, es) = withRecord(rec, reversed)
    val p = NeptuneExport.build(input(es = es, rs = rs))
    assert(reasons(p).exists(_.contains("years reversed")))
    val outside = records(1).copy(lastCollaborationYear = 2020) // window 2010-2014
    val (rs2, es2) = withRecord(records(1), outside)
    val p2 = NeptuneExport.build(input(es = es2, rs = rs2))
    assert(reasons(p2).exists(_.contains("years outside window 2010-2014")))
  }

  test("a window that is not configured is rejected") {
    val odd = TimeWindow(2012, 2013)
    val rec = record(r1, r2, odd, 2012, 2013, Vector(wk1), 1)
    val p = NeptuneExport.build(input(es = edges :+ coEdge(rec), rs = records :+ rec))
    assert(reasons(p).exists(_.contains("window 2012-2013 is not a configured window")))
  }

  test("a graph edge whose window property differs from its record is rejected") {
    val e = coEdge(records.head)
    val bad = e.copy(properties = e.properties.updated("window", "2010-2014"))
    val p = NeptuneExport.build(input(es = edges.map(x => if x == e then bad else x)))
    assert(reasons(p).exists(_.contains("window 2010-2014 differs from record window 1950-2026")))
  }

  test("an edge without its coauthor-edge record has no evidence and is rejected") {
    val p = NeptuneExport.build(input(rs = records.tail))
    assert(reasons(p).exists(_.contains("no coauthor-edge record")))
    assertEquals(table(p, "edges-COAUTHORED_WITH.csv").size, 3)
  }

  test("a supporting work that is not a Work node is rejected") {
    val p = NeptuneExport.build(input(ns = withoutNode(wk1)))
    assert(reasons(p).exists(_.contains(s"supporting work $wk1 is not a loaded Work node")))
  }

  test("a supporting work not authored by both researchers is rejected") {
    val missing = authored(r2, wk3, 0, 2012)
    val p = NeptuneExport.build(input(es = edges.filterNot(_ == missing)))
    assert(
      reasons(p).exists(_.contains(s"supporting work $wk3 is not AUTHORED by both researchers"))
    )
    assertEquals(table(p, "edges-COAUTHORED_WITH.csv").size, 2)
  }

  test("a work year outside first..last, or more works than the joint count, is rejected") {
    val oldWork = work(wk3, 2001, "Third Paper")
    val p = NeptuneExport.build(input(ns = nodes.map(n => if n.nodeId == wk3 then oldWork else n)))
    assert(reasons(p).exists(_.contains("has a year outside 2012-2012")))
    val rec = records.head
    val few = rec.copy(jointPublicationCount = 1) // two works for one joint publication
    val (rs, es) = withRecord(rec, few)
    val p2 = NeptuneExport.build(input(es = es, rs = rs))
    assert(reasons(p2).exists(_.contains("outnumber the joint publication count")))
  }

  test("a graph edge that disagrees with its record is rejected, a wrong edge ID too") {
    val e = coEdge(records.head)
    val wrongCount = e.copy(properties = e.properties.updated("jointPublicationCount", "9"))
    val p = NeptuneExport.build(input(es = edges.map(x => if x == e then wrongCount else x)))
    assert(reasons(p).exists(_.contains("counts, years or works differ")))
    val wrongId = e.copy(edgeId = Ids.graphEdge("COAUTHORED_WITH", r1, r2, policy, "other"))
    val p2 = NeptuneExport.build(input(es = edges.map(x => if x == e then wrongId else x)))
    assert(reasons(p2).exists(_.contains("not the core ID")))
  }

  test("an unknown property, a derived property and a malformed value are rejected") {
    val a = researcher(r3, "Edsger Dijkstra", Map("favoriteColor" -> "blue"))
    val p = NeptuneExport.build(input(ns = nodes.map(n => if n.nodeId == r3 then a else n)))
    assert(
      p.rejections.exists(r =>
        r.id == r3 && r.reason.contains("unknown or loader-derived property 'favoriteColor'")
      )
    )
    val d = researcher(r3, "Edsger Dijkstra", Map("runId" -> "x"))
    val p2 = NeptuneExport.build(input(ns = nodes.map(n => if n.nodeId == r3 then d else n)))
    assert(p2.rejections.exists(r => r.id == r3 && r.reason.contains("loader-derived")))
    val y = work(wk2, 2014, "Second Paper")
    val bad = y.copy(properties = y.properties.updated("year", "twenty"))
    val p3 = NeptuneExport.build(input(ns = nodes.map(n => if n.nodeId == wk2 then bad else n)))
    assert(
      p3.rejections.exists(r => r.id == wk2 && r.reason.contains("year: 'twenty' is not an Int"))
    )
  }

  test(
    "a label that Phase 06 does not export is rejected, and so is an edge of an unexported type"
  ) {
    val chunk = simple("c:0123456789abcdef0123", "TextChunk", Map.empty)
    val p = NeptuneExport.build(input(ns = nodes :+ chunk))
    assert(
      p.rejections.exists(r =>
        r.id == chunk.nodeId && r.reason.contains("not exported by Phase 06")
      )
    )
    val supported = GraphEdge(
      Ids.graphEdge("SUPPORTED_BY", wk1, chunk.nodeId, policy, ""),
      wk1,
      "SUPPORTED_BY",
      chunk.nodeId,
      Map.empty,
      prov("x", SchemaVersions.GraphEdge, policy)
    )
    val p2 = NeptuneExport.build(input(ns = nodes :+ chunk, es = edges :+ supported))
    assert(
      p2.rejections.exists(r =>
        r.id == supported.edgeId && r.reason.contains("not exported by Phase 06")
      )
    )
  }

  test("an edge to a node of the wrong label is rejected") {
    val e = edge("HAS_TOPIC", wk1, venue, "", Map.empty)
    val p = NeptuneExport.build(input(es = edges :+ e))
    assert(p.rejections.exists(r => r.id == e.edgeId && r.reason.contains("is not a loaded Topic")))
  }

  test("a record from another run is rejected") {
    val otherRun = prov(r3, SchemaVersions.GraphNode, erPolicy).copy(runId = Ids.run("""{"x":1}"""))
    val other = researcher(r3, "Edsger Dijkstra").copy(provenance = otherRun)
    val p = NeptuneExport.build(input(ns = nodes.map(n => if n.nodeId == r3 then other else n)))
    assert(p.rejections.exists(r => r.id == r3 && r.reason.contains("is not the run")))
  }

  test("identical duplicates collapse and are counted, conflicting duplicates are all rejected") {
    val p = NeptuneExport.build(input(ns = nodes ++ nodes.take(2), es = edges ++ edges.take(3)))
    assertEquals(p.rejections, Vector.empty[Rejection])
    assertEquals(p.manifest.duplicatesDropped, 5)
    assertEquals(table(p, "nodes-Researcher.csv").size, 3)
    val clash = researcher(r1, "Someone Else")
    val p2 = NeptuneExport.build(input(ns = nodes :+ clash))
    assert(
      p2.rejections.exists(r => r.id == r1 && r.reason.contains("different records share this ID"))
    )
    assertEquals(table(p2, "nodes-Researcher.csv").map(_(":ID")).toSet, Set(r2, r3))
    // the same ID under another label would let Neptune merge labels: it is a conflict too
    val asTopic = simple(r2, "Topic", Map("name" -> "x"))
    val p3 = NeptuneExport.build(input(ns = nodes :+ asTopic))
    assert(p3.rejections.exists(_.id == r2))
  }

  test("a malformed researcher ID is rejected") {
    val bad = researcher("not-an-id", "X")
    val p = NeptuneExport.build(input(ns = nodes :+ bad))
    assert(
      p.rejections.exists(r => r.id == "not-an-id" && r.reason.contains("malformed Researcher ID"))
    )
  }

  test("Spark manifest counts reconcile with the rows read, and a short read is named") {
    val manifest = ujson
      .Obj(
        "runId" -> run,
        "snapshot" -> snapshot,
        "nodes" -> ujson.Obj(
          "rows" -> nodes.size,
          "rowsByPartition" -> ujson.Obj(
            "label=Researcher" -> 3,
            "label=Work" -> 3,
            "label=Venue" -> 1,
            "label=Institution" -> 1,
            "label=Topic" -> 1
          )
        ),
        "edges" -> ujson.Obj(
          "rows" -> edges.size,
          "rowsByPartition" -> ujson.Obj(
            "edgeType=AUTHORED" -> 6,
            "edgeType=COAUTHORED_WITH" -> 4,
            "edgeType=PUBLISHED_IN" -> 1,
            "edgeType=HAS_TOPIC" -> 1,
            "edgeType=AFFILIATED_WITH" -> 1
          )
        ),
        "coauthoredWith" -> ujson.Obj("windows" -> ujson.Arr("1950-2026", "2010-2014", "2015-2026"))
      )
      .render()
    val m = SparkNeptuneManifest.parse(manifest).toOption.get
    assertEquals(SparkNeptuneManifest.reconcile(m, input()), Vector.empty[String])
    val short = SparkNeptuneManifest.reconcile(m, input(es = edges.take(5)))
    assert(short.exists(_.startsWith("edge rows: manifest 13, read 5")), short.toString)
    assert(SparkNeptuneManifest.parse("{}").isLeft)
  }

class NeptuneCsvSuite extends munit.FunSuite:
  import NeptuneFx.*

  test("CSV round trip: commas, quotes and line breaks in a field survive") {
    val rows = Vector(Vector("a,b", "say \"hi\"", "two\nlines", " pad ", "", "plain"))
    val bytes = ("h1,h2,h3,h4,h5,h6\n" + rows.map(NeptuneCsv.line).mkString).getBytes(UTF_8)
    val (h, parsed) = NeptuneCsv.parse(bytes).toOption.get
    assertEquals(h.size, 6)
    assertEquals(parsed, rows)
  }

  test("the generated files parse back to the fixture values") {
    val w = table(plan, "nodes-Work.csv").find(_(":ID") == wk1).get
    assertEquals(w("title:String"), "Graphs, \"Hybrid\" Methods\nand Their Limits")
    assertEquals(w("year:Int"), "2014")
    assertEquals(w(":LABEL"), "Work")
    assertEquals(w("policyVersion:String"), erPolicy)
    val r = table(plan, "nodes-Researcher.csv").find(_(":ID") == r2).get
    assertEquals(r("displayName:String"), "Grace \"Amazing\" Hopper, Jr.")
    assertEquals(r("orcid:String"), "")
    assertEquals(r("identityConfidence:Double"), "0.9")
    assertEquals(r("unresolved:Bool"), "false")
  }

  test("malformed CSV is a Left") {
    assert(NeptuneCsv.parse("a,b\n\"unterminated,1\n".getBytes(UTF_8)).isLeft)
    assert(NeptuneCsv.parse("a,b\n1\n".getBytes(UTF_8)).isLeft)
    assert(NeptuneCsv.parse(Array.emptyByteArray).isLeft)
  }

  test("the load manifest round trips through JSON and holds no timestamp") {
    val json = plan.manifestJson
    assertEquals(NeptuneLoadManifest.parse(json), Right(plan.manifest))
    assert(!json.toLowerCase.contains("time"), "no timestamp field")
    assert(NeptuneLoadManifest.parse("{\"schemaVersion\":\"x\"}").isLeft)
    assert(NeptuneLoadManifest.parse("not json").isLeft)
    plan.manifest.files.foreach { f =>
      assertEquals(f.sha256, Hashing.sha256Hex(file(plan, f.name).bytes))
      assertEquals(f.bytes, file(plan, f.name).bytes.length.toLong)
    }
  }

  test("regeneration from the same input gives the same bytes, whatever the input order") {
    val again = NeptuneExport.build(input(nodes.reverse, edges.reverse, records.reverse))
    assertEquals(again.manifestJson, plan.manifestJson)
    assertEquals(again.files.map(f => f.name -> f.sha256), plan.files.map(f => f.name -> f.sha256))
    plan.files.zip(again.files).foreach((a, b) => assertEquals(a.bytes.toVector, b.bytes.toVector))
  }

  test("a changed property changes the file hash, so a replay cannot hide it") {
    val changed = nodes.map(n => if n.nodeId == r3 then researcher(r3, "Edsger W. Dijkstra") else n)
    val p = NeptuneExport.build(input(ns = changed))
    assertNotEquals(
      file(p, "nodes-Researcher.csv").sha256,
      file(plan, "nodes-Researcher.csv").sha256
    )
    assertEquals(file(p, "nodes-Work.csv").sha256, file(plan, "nodes-Work.csv").sha256)
  }

  test("the writer is idempotent: a replay is Unchanged, a changed file is a conflict") {
    val store = new InMemoryObjectStore
    val aw = new ArtifactWriter(store, layout)
    val w = new NeptuneLoadWriter(aw, layout)
    val first = w.write(plan).toOption.get
    assertEquals(first.sourcePrefix, s"s3://sc-graph/graph/neptune-load/run_id=$run/")
    assert(first.outcomes.forall(_ == WriteOutcome.Created))
    assertEquals(first.csvObjects.size, plan.files.size)
    val keysBefore = store.keys
    val again = w.write(plan).toOption.get
    assert(again.outcomes.forall(_ == WriteOutcome.Unchanged), again.outcomes.toString)
    assertEquals(store.keys, keysBefore)
    // the loader prefix holds only CSV files
    val inPrefix = store.list(first.sourcePrefix).toOption.get
    assert(inPrefix.nonEmpty && inPrefix.forall(_.endsWith(".csv")))
    // the storage manifest verifies every stored hash
    val sm = aw.readManifest(Zone.Graph, "neptune-load-manifest", run).toOption.get
    assertEquals(aw.verify(sm), Vector.empty[String])
    assertEquals(sm.objects.size, plan.files.size + 1)
    assert(sm.objects.forall(_.sources.exists(_.sourceRecordId == s"spark:$run")))
    // the same run with different content must not overwrite
    val changedNodes =
      nodes.map(n => if n.nodeId == r3 then researcher(r3, "Other Name") else n)
    val changed = NeptuneExport.build(input(ns = changedNodes))
    w.write(changed) match
      case Left(ArtifactError.Conflict(uri, _, _)) => assert(uri.endsWith("nodes-Researcher.csv"))
      case other                                   => fail(s"expected a conflict, got $other")
    val stored = first.csvObjects.find(_.uri.endsWith("nodes-Researcher.csv")).get.uri
    assertEquals(
      store.get(stored).map(Hashing.sha256Hex(_)),
      Right(file(plan, "nodes-Researcher.csv").sha256)
    )
  }

  test("an empty plan is not written") {
    val w = new NeptuneLoadWriter(new ArtifactWriter(new InMemoryObjectStore, layout), layout)
    val empty = NeptuneExport.build(input(ns = Vector.empty, es = Vector.empty, rs = Vector.empty))
    assert(w.write(empty).isLeft)
  }

class GraphTemplateSuite extends munit.FunSuite:
  import NeptuneFx.*
  private val tpl = GraphTemplates.forConfig(windows)
  private val Param = "\\$([A-Za-z]+)".r

  test("every allowlisted template is read-only, with unique IDs") {
    assertEquals(GraphTemplates.All.map(_.id).distinct.size, GraphTemplates.All.size)
    GraphTemplates.All.foreach { t =>
      assertEquals(ReadOnlyCypher.violation(t.cypher), None, t.id)
      assert(t.cypher.contains("MATCH"), t.id)
    }
    assertEquals(GraphTemplates.All.size, 14)
  }

  test("a write keyword is detected") {
    Vector("CREATE (n)", "MATCH (n) SET n.x = 1", wipe, "MERGE (n:A)", "CALL db.x()")
      .foreach(q => assert(ReadOnlyCypher.violation(q).isDefined, q))
    assertEquals(ReadOnlyCypher.violation("MATCH (n) RETURN n.offset"), None)
  }

  test("each template's text uses exactly its declared parameters, never a literal value") {
    GraphTemplates.All.foreach { t =>
      val used = Param.findAllMatchIn(t.cypher).map(_.group(1)).toSet
      assertEquals(used, t.params.map(_._1).toSet, t.id)
      assert(!t.cypher.contains("'") && !t.cypher.contains("\""), s"${t.id} has a string literal")
    }
  }

  test("every template that reads COAUTHORED_WITH filters on exactly one window parameter") {
    val co = GraphTemplates.All.filter(_.cypher.contains("COAUTHORED_WITH"))
    assertEquals(co.size, 8) // direct, pair and shortest path d1..d6
    co.foreach { t =>
      assert(t.params.contains("window" -> ParamKind.Window), t.id)
      assert(t.cypher.contains("e.window = $window"), t.id)
    }
    // the neighbors templates cannot be asked for COAUTHORED_WITH at all
    assert(tpl.neighbors(r1, Set("COAUTHORED_WITH"), "both", 5).isLeft)
    assert(tpl.neighbors(r1, Set("AUTHORED", "COAUTHORED_WITH"), "both", 5).isLeft)
  }

  test("a window outside the configured set is rejected, a configured one is bound") {
    assert(tpl.directCoauthors(r1, "1999-2001", 5).isLeft)
    assert(tpl.directCoauthors(r1, "2010-2014' OR 1=1", 5).isLeft)
    val q = tpl.directCoauthors(r1, "2010-2014", 5).toOption.get
    assertEquals(q.templateId, "direct-coauthors-v1")
    assertEquals(q.parameters("window").str, "2010-2014")
    assertEquals(q.parameters("limit").num.toInt, 5)
    assertEquals(q.parameters("id").str, r1)
  }

  test("user text lands only in parameters; the query text is the constant template") {
    val hostile = "x'})RETURN`1//"
    val n = tpl.node(hostile).toOption.get
    assertEquals(n.cypher, GraphTemplates.ById("node-v1").cypher)
    assert(!n.cypher.contains(hostile))
    assertEquals(n.parameters("id").str, hostile)
    val q = tpl.neighbors(r1, Set("AUTHORED", "HAS_TOPIC"), "out", 10).toOption.get
    assertEquals(q.templateId, "neighbors-out-v1")
    assertEquals(q.parameters("edgeTypes").arr.map(_.str).toVector, Vector("AUTHORED", "HAS_TOPIC"))
    assert(!q.cypher.contains("AUTHORED"))
  }

  test("ID, limit, depth and direction are validated") {
    assert(tpl.node("").isLeft)
    assert(tpl.node("a b").isLeft)
    assert(tpl.node("x" * 451).isLeft)
    assert(tpl.directCoauthors("not-a-researcher", "2010-2014", 5).isLeft)
    assert(tpl.directCoauthors(r1, "2010-2014", 0).isLeft)
    assert(tpl.directCoauthors(r1, "2010-2014", GraphTemplates.MaxLimit + 1).isLeft)
    assert(tpl.directCoauthors(r1, "2010-2014", GraphTemplates.MaxLimit).isRight)
    assert(tpl.workAuthors("not a work", 5).isLeft)
    assert(tpl.workAuthors(wk1, 5).isRight)
    assert(tpl.researcherWorks(wk1, 5).isLeft)
    assert(tpl.shortestPath(r1, r2, "2010-2014", 0).isLeft)
    assert(tpl.shortestPath(r1, r2, "2010-2014", 7).isLeft)
    assert(tpl.shortestPath(r1, r1, "2010-2014", 3).isLeft)
    val d3 = tpl.shortestPath(r1, r2, "2010-2014", 3).toOption.get
    assertEquals(d3.templateId, "shortest-path-d3-v1")
    assert(d3.cypher.contains("*1..3"))
    assert(tpl.neighbors(r1, Set("AUTHORED"), "sideways", 5).isLeft)
    assert(tpl.neighbors(r1, Set.empty, "out", 5).isLeft)
    assert(tpl.neighbors(r1, Set("DROP_ALL"), "out", 5).isLeft)
    assert(tpl.coauthorPair(r1, r1, "2010-2014").isLeft)
    assert(tpl.coauthorPair(r1, r2, "2010-2014").isRight)
  }

  test("a tampered query is not allowlisted") {
    val q = tpl.node(r1).toOption.get
    assert(GraphTemplates.isAllowlisted(q))
    assert(!GraphTemplates.isAllowlisted(q.copy(cypher = q.cypher + " " + wipe)))
    assert(!GraphTemplates.isAllowlisted(q.copy(templateId = "unknown-v1")))
    assert(
      !GraphTemplates.isAllowlisted(q.copy(parameters = ujson.Obj("id" -> r1, "extra" -> "x")))
    )
  }

class NeptuneAdapterSuite extends munit.FunSuite:
  import NeptuneFx.*
  private val neptune = NeptuneConfig("neptune.test.internal", 8182, 5000, true)
  private val tpl = GraphTemplates.forConfig(windows)
  private val roleArn = "arn:aws:iam::123456789012:role/sc-neptune-loader"
  private val loadId = "0123abcd-0000-1111-2222-333344445555"

  private def form(body: String): Map[String, String] =
    body
      .split("&")
      .map(_.split("=", 2))
      .map(kv => URLDecoder.decode(kv(0), UTF_8) -> URLDecoder.decode(kv(1), UTF_8))
      .toMap

  test("the graph store posts the template and the parameters as separate signed form fields") {
    val reply = """{"results":[{"researcherId":"sc:r:v1:x","jointPublicationCount":2}]}"""
    withStub(_ => (200, reply, Map.empty)) { (url, seen) =>
      val store = new NeptuneGraphStore(http(), neptune, Some(url))
      val q = tpl.directCoauthors(r1, "2010-2014", 5).toOption.get
      val rows = store.run(q).toOption.get
      assertEquals(rows.map(_("jointPublicationCount").num.toInt), Vector(2))
      val s = seen.head
      assertEquals(s.method, "POST")
      assertEquals(s.uri, "/openCypher")
      assert(s.headers("authorization").contains("/us-east-2/neptune-db/aws4_request"))
      assertEquals(s.headers("content-type"), "application/x-www-form-urlencoded")
      val f = form(s.body)
      assertEquals(f("query"), q.cypher)
      assertEquals(ujson.read(f("parameters")), ujson.read(q.parameters.render()))
      assert(!f("query").contains(r1), "the user value is not in the query text")
    }
  }

  test("a non-allowlisted query is refused before any call") {
    withStub(_ => (200, """{"results":[]}""", Map.empty)) { (url, seen) =>
      val store = new NeptuneGraphStore(http(), neptune, Some(url))
      val q = tpl.node(r1).toOption.get
      val hostile = q.copy(cypher = wipe)
      assert(store.run(hostile).left.exists(_.contains("not an allowlisted template")))
      assertEquals(seen.size, 0)
    }
  }

  test("HTTP errors, malformed replies and a missing endpoint are Left") {
    val q = tpl.node(r1).toOption.get
    withStub(_ => (500, "boom", Map.empty)) { (url, _) =>
      val r = new NeptuneGraphStore(http(), neptune, Some(url)).run(q)
      assert(r.left.exists(_.contains("HTTP 500")))
    }
    withStub(_ => (200, """{"nope":1}""", Map.empty)) { (url, _) =>
      val r = new NeptuneGraphStore(http(), neptune, Some(url)).run(q)
      assert(r.left.exists(_.contains("results")))
    }
    assertEquals(
      new NeptuneGraphStore(http(), neptune.copy(endpoint = ""), None).run(q),
      Left("neptune.endpoint is not configured")
    )
  }

  test("the bulk loader starts an openCypher load of the run prefix and returns the load ID") {
    val reply = s"""{"status":"200 OK","payload":{"loadId":"$loadId"}}"""
    withStub(_ => (200, reply, Map.empty)) { (url, seen) =>
      val loader = new NeptuneBulkLoader(http(), neptune, "us-east-2", roleArn, Some(url))
      val prefix = s"s3://sc-graph/graph/neptune-load/run_id=$run/"
      assertEquals(loader.start(prefix), Right(loadId))
      val s = seen.head
      assertEquals(s.uri, "/loader")
      assert(s.headers("authorization").contains("/us-east-2/neptune-db/aws4_request"))
      val b = ujson.read(s.body)
      assertEquals(b("format").str, "opencypher")
      assertEquals(b("source").str, prefix)
      assertEquals(b("iamRoleArn").str, roleArn)
      assertEquals(b("region").str, "us-east-2")
      assertEquals(b("mode").str, "AUTO")
      assertEquals(b("userProvidedEdgeIds").str, "TRUE")
      assertEquals(b("updateSingleCardinalityProperties").str, "TRUE")
      assertEquals(b("failOnError").str, "TRUE")
    }
  }

  test("the bulk loader refuses a bad prefix, a bad role and a reply without a load ID") {
    withStub(_ => (200, """{"payload":{}}""", Map.empty)) { (url, seen) =>
      val loader = new NeptuneBulkLoader(http(), neptune, "us-east-2", roleArn, Some(url))
      assert(loader.start("sc-graph/x/").isLeft)
      assert(loader.start("s3://sc-graph/x").isLeft)
      val noRole = new NeptuneBulkLoader(http(), neptune, "us-east-2", "role", Some(url))
      assert(noRole.start("s3://b/x/").isLeft)
      assertEquals(seen.size, 0)
      assert(loader.start("s3://sc-graph/x/").left.exists(_.contains("loadId")))
    }
  }

  test("the loader status is parsed, and errors make a load not clean") {
    val ok =
      """{"status":"200 OK","payload":{"overallStatus":{"status":"LOAD_COMPLETED","totalRecords":29,"totalDuplicates":0,"parsingErrors":0,"datatypeMismatchErrors":0,"insertErrors":0}}}"""
    withStub(_ => (200, ok, Map.empty)) { (url, seen) =>
      val loader = new NeptuneBulkLoader(http(), neptune, "us-east-2", roleArn, Some(url))
      val s = loader.status(loadId).toOption.get
      assertEquals(seen.head.method, "GET")
      assertEquals(seen.head.uri, s"/loader/$loadId")
      assertEquals(s.totalRecords, 29L)
      assert(s.completedClean)
      assert(loader.status("../etc").isLeft)
    }
    val bad = ok.replace("\"insertErrors\":0", "\"insertErrors\":2")
    withStub(_ => (200, bad, Map.empty)) { (url, _) =>
      val loader = new NeptuneBulkLoader(http(), neptune, "us-east-2", roleArn, Some(url))
      assert(!loader.status(loadId).toOption.get.completedClean)
    }
    withStub(_ => (404, "{}", Map.empty)) { (url, _) =>
      val loader = new NeptuneBulkLoader(http(), neptune, "us-east-2", roleArn, Some(url))
      assert(loader.status(loadId).isLeft)
    }
  }

  test("the graph fake records allowlisted queries and refuses any other") {
    val fake = new InMemoryGraphStore(_ => Right(Vector(ujson.Obj("n" -> 1))))
    val q = tpl.node(r1).toOption.get
    assertEquals(fake.run(q).map(_.size), Right(1))
    assertEquals(fake.executed, Vector(q))
    assert(fake.run(q.copy(cypher = "CREATE (n)")).isLeft)
    assertEquals(fake.executed.size, 1)
  }
