package scholarchain.rag

import scholarchain.core.*
import java.nio.file.{Files, Path}
import RagWorld.*

/** LocalRuntime on a temporary export of the hand-built run, written in the Phase 08 layout. */
class LocalRuntimeSuite extends munit.FunSuite:
  private val Windows = Set(Recent.label, Early.label)

  private def manifestText(er: String = ErRunId) =
    ujson
      .Obj(
        "fixture" -> "rag-local-runtime",
        "snapshot" -> SmokeFixture.Snapshot,
        "inputs" -> ujson.Obj(
          "entityResolution" -> ujson.Obj("runId" -> er, "policyVersion" -> "er-v1")
        ),
        "graph" -> ujson.Obj("policyVersion" -> "graph-v1", "metricVersion" -> "metrics-v1")
      )
      .render()

  private def write(dir: Path, lines: Vector[String]): Unit =
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("part-00000.json"), lines.mkString("", "\n", "\n"))

  private def extra(json: String, fields: (String, ujson.Value)*): String =
    val j = ujson.read(json)
    fields.foreach((k, v) => j(k) = v)
    j.render()

  /** Writes the run and returns the run manifest path; `tamper` edits the neptune manifest.
    * `communities` (C14) gives the run's `communities` table rows; None writes an export without
    * that table, as before Phase 11. `tamperDynamo` edits the dynamodb manifest.
    */
  private def exportRun(
      out: Path,
      manifest: String,
      tamper: ujson.Value => Unit = _ => (),
      communities: Option[RagWorld => Vector[EvidenceItem]] = None,
      tamperDynamo: ujson.Value => Unit = _ => ()
  ): Path =
    val runId = Ids.run(manifest)
    val w = new RagWorld(runId)
    val folder = out.resolve(s"spark/snapshot=${w.snapshot}/run_id=$runId")
    val jl = folder.resolve("jsonl")
    def counts[A](xs: Vector[A])(key: A => String) =
      ujson.Obj.from(xs.groupBy(key).toVector.sortBy(_._1).map((k, v) => k -> ujson.Num(v.size)))
    w.nodes
      .groupBy(_.label)
      .foreach((l, ns) => write(jl.resolve(s"graph-nodes/label=$l"), ns.map(JsonLines.encode(_))))
    w.edges
      .groupBy(_.edgeType)
      .foreach((t, es) =>
        write(jl.resolve(s"graph-edges/edgeType=$t"), es.map(JsonLines.encode(_)))
      )
    w.coauthorEdges.groupBy(_.window.label).foreach { (l, es) =>
      write(
        jl.resolve(s"coauthor-edges/windowLabel=$l"),
        es.map(e =>
          extra(
            JsonLines.encode(e),
            "supportingWorkIds" -> ujson.Arr.from(e.representativeWorkIds),
            "windowLabel" -> l
          )
        )
      )
    }
    w.metricRows.groupBy(_.window.label).foreach { (l, ms) =>
      write(
        jl.resolve(s"researcher-metrics/windowLabel=$l"),
        ms.map(m =>
          extra(
            JsonLines.encode(m),
            "maximumTeamWorkCount" -> 1,
            "inLargestComponent" -> false,
            "windowLabel" -> l
          )
        )
      )
    }
    w.summaries.groupBy(e => (e.values("docType"), e.values("window"))).foreach {
      case ((dt, l), es) =>
        write(
          jl.resolve(s"semantic-summaries/docType=$dt/windowLabel=$l"),
          es.map(SparkShapedRun.summaryLine)
        )
    }
    write(jl.resolve("paper-abstracts"), w.abstracts.map(JsonLines.encode(_)))
    val lm = folder.resolve("load-manifests")
    Files.createDirectories(lm)
    def entry(rows: ujson.Obj, extras: String*) =
      ujson.Obj(
        "jsonLines" -> ujson.Obj("extraFields" -> ujson.Arr.from(extras), "rowsByPartition" -> rows)
      )
    val neptune = ujson.Obj(
      "runId" -> runId,
      "nodes" -> entry(counts(w.nodes)(n => s"label=${n.label}")),
      "edges" -> entry(counts(w.edges)(e => s"edgeType=${e.edgeType}")),
      "coauthorEdges" -> entry(
        counts(w.coauthorEdges)(e => s"windowLabel=${e.window.label}"),
        "supportingWorkIds",
        "windowLabel"
      )
    )
    tamper(neptune)
    Files.writeString(lm.resolve("neptune.json"), neptune.render(2))
    val dynamo = ujson.Obj(
      "runId" -> runId,
      "tables" -> ujson.Arr(
        ujson.Obj(
          "source" -> entry(
            counts(w.metricRows)(m => s"windowLabel=${m.window.label}"),
            "maximumTeamWorkCount",
            "inLargestComponent",
            "windowLabel"
          )
        )
      )
    )
    communities.map(_(w)).foreach { rows =>
      def part(e: EvidenceItem) =
        s"windowLabel=${e.values("window")}/recordKind=${e.values("recordKind")}"
      rows.groupBy(part).foreach { (p, es) =>
        write(
          jl.resolve(s"communities/$p"),
          es.map(e =>
            extra(
              JsonLines.encode(e),
              "windowLabel" -> e.values("window"),
              "recordKind" -> e.values("recordKind")
            )
          )
        )
      }
      dynamo("communities") =
        ujson.Obj("source" -> entry(counts(rows)(part), "windowLabel", "recordKind"))
    }
    tamperDynamo(dynamo)
    Files.writeString(lm.resolve("dynamodb.json"), dynamo.render(2))
    val byDocType = w.summaries.groupMapReduce(_.values("docType"))(_ => 1)(_ + _)
    val vectors = ujson.Obj(
      "runId" -> runId,
      "snapshot" -> w.snapshot,
      "target" -> "s3vectors",
      "dimension" -> w.cfg.vectors.dimension,
      "source" -> ujson.Obj(
        "table" -> "semantic-summaries",
        "rows" -> w.summaries.size,
        "jsonLines" -> ujson.Obj("extraFields" -> ujson.Arr("docType", "windowLabel"))
      ),
      "indexes" -> ujson.Arr.from(byDocType.toVector.sorted.map { (dt, n) =>
        ujson.Obj(
          "docType" -> dt,
          "index" -> w.cfg.vectors.indexes(scholarchain.storage.SemanticDocument.IndexKeys(dt)),
          "rows" -> n
        )
      }),
      "abstracts" -> ujson.Obj(
        "docType" -> "paper-abstract",
        "index" -> w.cfg.vectors.indexes("abstracts"),
        "source" -> ujson.Obj(
          "table" -> "paper-abstracts",
          "rows" -> w.abstracts.size,
          "jsonLines" -> ujson.Obj("extraFields" -> ujson.Arr("sourceWorkId", "upstreamRunId"))
        ),
        "rows" -> w.abstracts.size
      )
    )
    Files.writeString(lm.resolve("s3vectors.json"), vectors.render(2))
    Files.createDirectories(out.resolve("run-manifests"))
    val path = out.resolve(s"run-manifests/$runId.json")
    Files.writeString(path, manifest)
    path

  private def withOut[A](f: Path => A): A =
    val out = Files.createTempDirectory("rag-local-runtime")
    try f(out)
    finally Files.walk(out).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  private def load(path: Path, er: String = ErRunId) =
    LocalRuntime.load(SmokeFixture.cfg, path, er, LocalRuntimeSettings(Some(Windows), None))

  test("loads every partition through the manifests and answers like the in-memory world") {
    withOut { out =>
      val path = exportRun(out, manifestText())
      val rt = load(path).fold(e => fail(e), identity)
      val w = new RagWorld(rt.ctx.graphRunId)
      assertEquals(rt.ctx.graphRunId, Ids.run(manifestText()))
      assertEquals(rt.ctx.erRunId, ErRunId)
      val rows = rt.report.rows.toMap
      assertEquals(rows("graph-nodes/label=Researcher"), 8L)
      assertEquals(rows("coauthor-edges/windowLabel=2015-2026"), 4L)
      assertEquals(rows("researcher-metrics/windowLabel=2000-2009"), 2L)
      assertEquals(rows("graph-edges/edgeType=AFFILIATED_WITH"), 4L)
      assert(rt.report.vectorChunks >= w.summaries.size.toLong, rt.report.toString)
      val r = rt
        .pipeline()
        .run(
          "qry-local",
          AnalysisRequest(
            "What is the shortest path between Alice Arden and Dan Dorsey since 2015?"
          )
        )
        .fold(e => fail(e.describe), identity)
      assertEquals(
        r.bundle.deterministic
          .filter(_.evidenceType == EvidenceType.GRAPH_PATH)
          .flatMap(_.graphPath),
        Vector(Vector(Alice, Bob, Carol, Dan))
      )
      assert(r.bundle.deterministic.forall(_.provenance.runId == rt.ctx.graphRunId))
      assert(r.bundle.semantic.nonEmpty)
      // Stages 1 to 10 on the loaded run.
      val log = new InMemoryAuditLog
      val full = rt
        .workflow(audit = log)
        .run(
          "qry-local-e2e",
          AnalysisRequest(
            "What is the shortest path between Alice Arden and Dan Dorsey since 2015?"
          )
        )
        .fold(e => fail(e.describe), identity)
      assertEquals(full.answer.verification, VerificationStatus.VERIFIED)
      assert(
        full.answer.claims.exists(
          _.text.contains("Alice Arden -> Bob Brandt -> Carol Chen -> Dan Dorsey")
        )
      )
      assertEquals(
        log.get("qry-local-e2e").map(_.map(_.graphRunId)),
        Right(Some(rt.ctx.graphRunId))
      )
    }
  }

  test("repair M6: a window subset is the runtime's only windows; no unloaded window is queried") {
    withOut { out =>
      val rt = load(exportRun(out, manifestText())).fold(e => fail(e), identity)
      // loaded: 2015-2026 and 2000-2009; configured also 1950-2026 and 2010-2014
      assert(SmokeFixture.cfg.graph.windows.map(_.label).contains("1950-2026"))
      assertEquals(rt.ctx.cfg.graph.windows.map(_.label).toSet, Windows)
      // No years: the default window spans the loaded years (2000-2026), which no loaded window
      // contains, so the widest loaded window answers, with a note. The fixture chain
      // Alice-Bob-Carol-Dan lies in 2015-2026; an unloaded 1950-2026 would find no path.
      val r = rt
        .pipeline()
        .run(
          "qry-local-nowindow",
          AnalysisRequest("What is the shortest path between Alice Arden and Dan Dorsey?")
        )
        .fold(e => fail(e.describe), identity)
      assertEquals(r.plan.window, TimeWindow(2000, 2026))
      assertEquals(r.window, Recent)
      assert(r.windowNote.exists(_.contains("2015-2026")), r.windowNote.toString)
      assertEquals(
        r.bundle.deterministic
          .filter(_.evidenceType == EvidenceType.GRAPH_PATH)
          .flatMap(_.graphPath),
        Vector(Vector(Alice, Bob, Carol, Dan))
      )
      assert(r.graphCalls.flatMap(_.window).forall(Windows), r.graphCalls.toString)
      assert(!r.notes.exists(_.contains("1950-2026")), r.notes.toString)
      // an explicitly requested unloaded window is answered from a loaded one, never queried
      val e = rt
        .pipeline()
        .run(
          "qry-local-unloaded",
          AnalysisRequest(
            "Who has the most distinct coauthors?",
            Constraints(yearFrom = Some(2010), yearTo = Some(2014))
          )
        )
        .fold(x => fail(x.describe), identity)
      assert(Windows(e.window.label), e.window.toString)
      assert(e.windowNote.exists(_.contains("2010-2014")), e.windowNote.toString)
      assert(e.graphCalls.flatMap(_.window).forall(Windows), e.graphCalls.toString)
    }
  }

  test("refuses another ER run, a manifest whose name is not its hash, and a row-count mismatch") {
    withOut { out =>
      val path = exportRun(out, manifestText())
      assert(load(path, "run-0000000000000000").left.exists(_.contains("consumed ER run")))
      val renamed = path.resolveSibling("run-1111111111111111.json")
      Files.copy(path, renamed)
      assert(load(renamed).left.exists(_.contains("not the hash")))
    }
    withOut { out =>
      val path = exportRun(
        out,
        manifestText(),
        n => n("nodes")("jsonLines")("rowsByPartition")("label=Researcher") = 9
      )
      assert(load(path).left.exists(_.contains("the load manifest says 9")), load(path).toString)
    }
  }

  // C14: community sizes ------------------------------------------------------------------------

  /** The quantum community (3 members in 2015-2026) and one cross-community edge row. */
  private def communityRows(w: RagWorld): Vector[EvidenceItem] =
    def row(key: String, values: (String, String)*) =
      w.ctx.evidence(
        EvidenceType.COMMUNITY_RECORD,
        key,
        "s3://fixture/graph/communities",
        Vector.empty,
        values.toMap,
        "graph-v1"
      )
    Vector(
      row(
        "community|quantum",
        "recordKind" -> "community",
        "communityId" -> SmokeFixture.QuantumCommunity,
        "communitySize" -> "3",
        "window" -> Recent.label
      ),
      row(
        "cross|bob-carol",
        "recordKind" -> "cross_community_edge",
        "edgeId" -> "ce:1",
        "communityA" -> SmokeFixture.QuantumCommunity,
        "communityB" -> SmokeFixture.BiologyCommunity,
        "jointPublicationCount" -> "1",
        "window" -> Recent.label
      )
    )

  test("C14: the communities table feeds the community reader; sizes reach the answer") {
    withOut { out =>
      val rt = load(exportRun(out, manifestText(), communities = Some(communityRows)))
        .fold(e => fail(e), identity)
      val rows = rt.report.rows.toMap
      assertEquals(rows("communities/windowLabel=2015-2026/recordKind=community"), 1L)
      assert(!rows.contains("communities/windowLabel=2000-2009/recordKind=community"))
      val reader = rt.stores.communities.getOrElse(fail("no community reader"))
      val got = reader
        .community(rt.ctx.graphRunId, SmokeFixture.QuantumCommunity, Recent)
        .fold(e => fail(e.toString), identity)
      assertEquals(got.map(_.size), Some(3L))
      assertEquals(
        reader.community(rt.ctx.graphRunId, SmokeFixture.BiologyCommunity, Recent),
        Right(None)
      )
      val a = rt
        .workflow()
        .run(
          "qry-local-c14",
          AnalysisRequest(
            "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?"
          )
        )
        .fold(e => fail(e.describe), identity)
      val sized = a.answer.claims.filter(_.text.contains("of 3 members"))
      assert(sized.nonEmpty && sized.forall(_.verificationStatus == VerificationStatus.VERIFIED))
      assert(!a.answer.summary.contains(s"community size of ${SmokeFixture.QuantumCommunity}"))
    }
  }

  test("C14: an export without the communities table serves no community store (sizes missing)") {
    withOut { out =>
      val rt = load(exportRun(out, manifestText())).fold(e => fail(e), identity)
      assertEquals(rt.stores.communities, None)
      assert(!rt.report.rows.exists(_._1.startsWith("communities/")))
      val a = rt
        .workflow()
        .run(
          "qry-local-c14-old",
          AnalysisRequest(
            "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?"
          )
        )
        .fold(e => fail(e.describe), identity)
      assert(a.answer.claims.forall(c => !"""\b\d+\s+members\b""".r.findFirstIn(c.text).isDefined))
      assert(
        a.answer.summary.contains(
          s"community size of ${SmokeFixture.QuantumCommunity} is not available: the Spark community table is not in any store the runtime reads"
        ),
        a.answer.summary
      )
    }
  }

  test("C14: a community row-count mismatch or a bad community row fails the load") {
    withOut { out =>
      val path = exportRun(
        out,
        manifestText(),
        communities = Some(communityRows),
        tamperDynamo = d =>
          d("communities")("source")("jsonLines")("rowsByPartition")(
            "windowLabel=2015-2026/recordKind=community"
          ) = 2
      )
      assert(load(path).left.exists(_.contains("the load manifest says 2")), load(path).toString)
    }
    withOut { out =>
      val path = exportRun(
        out,
        manifestText(),
        communities = Some(w =>
          communityRows(w).map(e =>
            if e.values("recordKind") == "community" then
              e.copy(values = e.values.updated("communitySize", "0"))
            else e
          )
        )
      )
      assert(
        load(path).left.exists(_.contains("communities: 1 rows rejected")),
        load(path).toString
      )
    }
  }
