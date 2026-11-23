package scholarchain.er.eval

import java.nio.file.{Files, Path}

import scholarchain.core.*
import scholarchain.er.{ErFixtures, MatchPolicy, Resolver}
import scholarchain.er.review.ReviewQueue

/** Every label here is synthetic and written in the test; no file under datasets/ is read. */
class EvaluationSuite extends munit.FunSuite:
  private val cfg = EvalConfig.load().fold(e => throw new AssertionError(e), identity)
  private val thresholds = ErFixtures.policyConfig.thresholds
  private val runId = Ids.run("""{"fixture":"er-eval"}""")

  private def mention(i: Int): String = Ids.mention("dblp:conf/e/" + i, 0)

  private def decision(
      i: Int,
      outcome: MatchOutcome,
      path: MatchPath,
      score: Double,
      kind: EntityKind = EntityKind.RESEARCHER
  ): MatchDecision =
    val (a, b) = kind match
      case EntityKind.RESEARCHER => (mention(i), mention(i + 1000))
      case EntityKind.WORK       => (s"dblp:conf/w/$i", s"crossref:10.1/$i")
    val rule = (kind, path) match
      case (EntityKind.RESEARCHER, MatchPath.PROBABILISTIC) => "R-R9"
      case (EntityKind.WORK, MatchPath.PROBABILISTIC)       => "R-W9"
      case _                                                => "R-X"
    MatchDecision(
      SchemaVersions.Match,
      Ids.decision(kind, a, b, "er-v1"),
      kind,
      a,
      b,
      Map(Resolver.ScoreFeature -> score),
      Vector(rule),
      outcome,
      path,
      score,
      Vector("TEST"),
      "er-v1",
      None,
      None,
      Provenance(
        SourceType.SCHOLARCHAIN,
        s"mc:p$i",
        "2026-11-22",
        runId,
        SchemaVersions.Match,
        Some("er-v1")
      )
    )

  private def row(
      i: Int,
      label: String,
      kind: String = "researcher",
      swap: Boolean = true,
      note: String = ""
  ) =
    val (a, b) =
      if kind == "researcher" then (mention(i), mention(i + 1000))
      else (s"dblp:conf/w/$i", s"crossref:10.1/$i")
    val (l, r) = if swap then (b, a) else (a, b) // the row may name the endpoints in either order
    Vector(
      s"mc:p$i",
      kind,
      "S",
      l,
      s"left name $i",
      "",
      "",
      "",
      "",
      "",
      "S",
      r,
      s"right name $i",
      "",
      "",
      "",
      "",
      "",
      label,
      note
    )

  private def csv(rows: Vector[Vector[String]], bom: Boolean = false): String =
    val text = (ReviewQueue.Columns +: rows)
      .map(_.map(ReviewQueue.csvField).mkString(","))
      .mkString("", "\r\n", "\r\n")
    if bom then "﻿" + text else text

  private val P = MatchPath.PROBABILISTIC
  private val decisions = Vector(
    decision(1, MatchOutcome.ACCEPTED, P, 0.95),
    decision(2, MatchOutcome.ACCEPTED, P, 0.92),
    decision(3, MatchOutcome.REJECTED, P, 0.2),
    decision(4, MatchOutcome.REJECTED, P, 0.3),
    decision(5, MatchOutcome.UNRESOLVED, P, 0.8),
    decision(6, MatchOutcome.ACCEPTED, MatchPath.EXACT_ID, 0.99),
    decision(7, MatchOutcome.CONFLICT, MatchPath.EXACT_ID, 0.5),
    decision(8, MatchOutcome.UNRESOLVED, P, 0.5),
    decision(10, MatchOutcome.ACCEPTED, P, 0.95),
    decision(
      21,
      MatchOutcome.ACCEPTED,
      MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
      0.97,
      EntityKind.WORK
    ),
    decision(22, MatchOutcome.REJECTED, MatchPath.EXACT_ID, 0.1, EntityKind.WORK)
  )
  private val goldRows = Vector(
    row(1, "MATCH"),
    row(2, "NON_MATCH", note = "different people, same name"),
    row(3, "non_match"),
    row(4, " MATCH "),
    row(5, "MATCH"),
    row(6, "MATCH", swap = false),
    row(7, "NON_MATCH"),
    row(8, "UNSURE"),
    row(9, "MATCH"), // no decision for this pair
    row(11, "") // unlabeled
  ) ++ Vector(
    Vector(
      "mc:p10",
      "researcher",
      "S",
      mention(99),
      "n",
      "",
      "",
      "",
      "",
      "",
      "S",
      mention(1010),
      "n",
      "",
      "",
      "",
      "",
      "",
      "MATCH",
      ""
    )
  ) ++ Vector(row(21, "MATCH", "work"), row(22, "NON_MATCH", "work"))

  private lazy val rows = GoldCsv.parse(csv(goldRows, bom = true)).fold(e => fail(e), identity)
  private lazy val report =
    Evaluation.evaluate(rows, decisions, cfg, thresholds).fold(e => fail(e), identity)

  test("the gold CSV reader: BOM, CRLF, quoted commas and newlines, labels read exactly") {
    assertEquals(rows.size, 13)
    assertEquals(rows.find(_.pairId == "mc:p3").flatMap(_.label), Some(GoldLabel.NON_MATCH))
    assertEquals(rows.find(_.pairId == "mc:p11").map(_.label), Some(None))
    val tricky = csv(Vector(row(1, "MATCH", note = "a, \"quoted\"\nnote")))
    assertEquals(GoldCsv.parse(tricky).map(_.head.note), Right("a, \"quoted\"\nnote"))
    val noNewline = csv(Vector(row(1, ""))).stripSuffix("\r\n")
    assertEquals(GoldCsv.parse(noNewline).map(_.head.label), Right(None))
  }

  test(
    "the gold CSV reader refuses unknown labels, duplicates, missing columns and broken quoting"
  ) {
    assert(GoldCsv.parse(csv(Vector(row(1, "maybe")))).left.exists(_.contains("line 2")))
    assert(
      GoldCsv
        .parse(csv(Vector(row(1, "MATCH"), row(1, "MATCH"))))
        .left
        .exists(_.contains("more than once"))
    )
    assert(GoldCsv.parse("pair_id,kind\r\nmc:x,work\r\n").left.exists(_.contains("no column")))
    assert(GoldCsv.parse(csv(Vector(row(1, "MATCH"))) + "\"open").isLeft)
    assert(GoldCsv.parse(csv(Vector(row(1, "MATCH", kind = "person")))).isLeft)
  }

  test("researcher confusion matrix and metrics, derived by hand; UNSURE is not counted") {
    val c = report.researcher.confusion
    assertEquals(c, Confusion(2, 1, 1, 0, 1, 1, 0, 1))
    val m = report.researcher.overall
    assertEquals(
      (m.labeled, m.tp, m.fp, m.tn, m.fn, m.unresolved, m.conflict),
      (7, 2, 1, 1, 1, 1, 1)
    )
    assertEqualsDouble(m.precision.get, 2.0 / 3, 1e-12)
    assertEqualsDouble(m.recall.get, 2.0 / 4, 1e-12) // the unresolved MATCH counts against recall
    assertEqualsDouble(m.recallDecided.get, 2.0 / 3, 1e-12)
    assertEqualsDouble(m.f1.get, 4.0 / 7, 1e-12)
    assertEqualsDouble(m.coverage.get, 5.0 / 7, 1e-12)
    assertEqualsDouble(m.unresolvedRate.get, 1.0 / 7, 1e-12)
    assertEquals(
      report.researcher.unsure,
      UnsureSummary(1, Map("UNRESOLVED" -> 1), Map("PROBABILISTIC" -> 1))
    )
  }

  test("metrics by match path, and per kind") {
    val byPath = report.researcher.byPath
    // every path is listed; a path without labeled pairs has labeled = 0 and n/a metrics
    assertEquals(byPath.keySet, MatchPath.values.map(_.toString).toSet)
    assertEquals(byPath("LLM_ADJUDICATED").labeled, 0)
    assertEquals(byPath("LLM_ADJUDICATED").precision, None)
    assertEquals(byPath("DETERMINISTIC_BIBLIOGRAPHIC").labeled, 0)
    assertEquals(byPath("PROBABILISTIC").precision, Some(0.5))
    assertEqualsDouble(byPath("PROBABILISTIC").recall.get, 1.0 / 3, 1e-12)
    assertEquals((byPath("EXACT_ID").tp, byPath("EXACT_ID").conflict), (1, 1))
    val w = report.work.overall
    assertEquals((w.labeled, w.tp, w.tn), (2, 1, 1))
    assertEquals(report.work.byPath("DETERMINISTIC_BIBLIOGRAPHIC").tp, 1)
    assertEquals(report.work.byPath("PROBABILISTIC").labeled, 0)
  }

  test(
    "rows are traced to their decisions: missing decisions and endpoint mismatches are listed, not counted"
  ) {
    assertEquals(report.goldRows, 13)
    assertEquals((report.countedRows, report.unsureRows, report.unlabeledRows), (9, 1, 1))
    assertEquals(report.rowsWithoutDecision, Vector("mc:p9"))
    assertEquals(report.endpointMismatches.size, 1)
    assert(report.endpointMismatches.head.startsWith("mc:p10"))
  }

  test("error analysis lists name the pairs and keep the human note") {
    assertEquals(report.researcher.falsePositives.map(_.pairId), Vector("mc:p2"))
    assertEquals(report.researcher.falsePositives.head.note, "different people, same name")
    // the names come from the row's display columns; the row names the endpoints swapped
    val fp = report.researcher.falsePositives.head
    assertEquals((fp.leftName, fp.rightName), ("left name 2", "right name 2"))
    assertEquals(fp.rulesFired, Vector("R-R9"))
    assertEquals(
      report.researcher.unsureNotAccepted.map(e => (e.pairId, e.label)),
      Vector("mc:p8" -> GoldLabel.UNSURE)
    )
    assertEquals(report.researcher.falseNegatives.map(_.pairId), Vector("mc:p4"))
    assertEquals(report.researcher.matchesNotDecided.map(_.pairId), Vector("mc:p5"))
    assertEquals(report.researcher.nonMatchesNotDecided.map(_.pairId), Vector("mc:p7"))
  }

  test("threshold sensitivity re-decides only probabilistic decisions and marks the er-v1 point") {
    val sweep = report.researcher.thresholdSensitivity
    assert(sweep.forall(p => p.reject < p.accept))
    val policyPoint = sweep.filter(_.isPolicy)
    assertEquals(policyPoint.map(p => (p.accept, p.reject)), Vector((0.9, 0.35)))
    assertEquals(policyPoint.head.metrics, report.researcher.overall)
    val lower = sweep.find(p => p.accept == 0.75 && p.reject == 0.35).get.metrics
    assertEquals(lower.tp, 3) // the 0.8 MATCH is now accepted
    val higher = sweep.find(p => p.accept == 0.95 && p.reject == 0.35).get.metrics
    assertEquals(
      (higher.tp, higher.fp),
      (2, 0)
    ) // 0.92 NON_MATCH no longer accepted; exact ID unchanged
    assertEquals(
      Evaluation.redecide(decisions(5), 0.1, 0.05),
      MatchOutcome.ACCEPTED
    ) // exact path kept
    assert(MatchPolicy.ProbabilisticRules("R-R9"))
  }

  test("Wilson 95% intervals match published values and are reported for precision and recall") {
    def near(i: Option[Interval], lo: Double, hi: Double) =
      assertEqualsDouble(i.get.low, lo, 1e-9); assertEqualsDouble(i.get.high, hi, 1e-9)
    near(Wilson.interval95(0, 10), 0.0, 0.2775327998628892)
    near(Wilson.interval95(10, 10), 0.7224672001371107, 1.0)
    near(Wilson.interval95(13, 13), 0.7719046276458016, 1.0)
    assertEquals(Wilson.interval95(0, 0), None)
    assertEquals(Wilson.interval95(3, 2), None)
    val m = report.researcher.overall // precision 2/3, recall 2/4
    near(m.precisionWilson95, 0.2076596008020477, 0.9385080552796037)
    near(m.recallWilson95, 0.15003898915214947, 0.8499610108478506)
    assertEquals(Confusion.Empty.metrics.precisionWilson95, None)
  }

  test("run outcome counts cover every decision of the run, labeled or not") {
    assertEquals(
      report.runOutcomes("RESEARCHER"),
      Map("ACCEPTED" -> 4, "REJECTED" -> 2, "UNRESOLVED" -> 2, "CONFLICT" -> 1)
    )
    assertEquals(report.runOutcomes("WORK"), Map("ACCEPTED" -> 1, "REJECTED" -> 1))
  }

  test("a gold file without the display columns still parses, with empty names") {
    val bare =
      "pair_id,kind,left_record_id,right_record_id,label,note\r\nmc:p1,researcher,a,b,MATCH,\r\n"
    assertEquals(
      GoldCsv.parse(bare).map(_.map(g => (g.leftName, g.rightName))),
      Right(Vector(("", "")))
    )
  }

  test("empty denominators are n/a, never zero; mixed runs and duplicate decisions are refused") {
    val empty = Confusion.Empty.metrics
    assertEquals(
      (empty.precision, empty.recall, empty.f1, empty.coverage),
      (None, None, None, None)
    )
    val other = decisions.head.copy(provenance =
      decisions.head.provenance.copy(runId = Ids.run("""{"x":1}"""))
    )
    assert(Evaluation.evaluate(rows, decisions :+ other, cfg, thresholds).isLeft)
    assert(Evaluation.evaluate(rows, decisions :+ decisions.head, cfg, thresholds).isLeft)
  }

  test(
    "the Markdown report holds the confusion matrix, the per-path table and the sensitivity table"
  ) {
    val md = Evaluation.markdown(report)
    assert(md.contains("| MATCH | 2 | 1 | 1 | 0 |"), md)
    assert(md.contains("| NON_MATCH | 1 | 1 | 0 | 1 |"))
    assert(md.contains("| PROBABILISTIC | 5 |"))
    assert(md.contains("| 0.90 | 0.35 | er-v1 |"))
    assert(md.contains("UNSURE rows (not counted above): 1"))
    assert(md.contains("| LLM_ADJUDICATED | 0 pairs |"), md)
    assert(md.contains("[0.208, 0.939]"), md) // researcher precision CI
    assert(
      md.contains(
        "| mc:p2 | left name 2 | right name 2 | NON_MATCH | ACCEPTED | PROBABILISTIC | R-R9 |"
      ),
      md
    )
    assert(Evaluation.CountingRules.forall(md.contains))
  }

  test(
    "EvaluateMain reads a decision run folder and a labels file, and refuses human-only output"
  ) {
    val dir = Files.createTempDirectory("sc-er-eval-")
    try
      val run = dir.resolve("run")
      decisions.groupBy(_.decision).foreach { (o, ds) =>
        val sub = run.resolve(scholarchain.er.ErOut.of(o))
        Files.createDirectories(sub)
        Files.writeString(
          sub.resolve("part-r-00000"),
          ds.map(JsonLines.encode(_)).mkString("\n") + "\n"
        )
      }
      Files.writeString(run.resolve("_SUCCESS"), "")
      val labels = Files.writeString(dir.resolve("labels.csv"), csv(goldRows))
      val out = dir.resolve("report")
      val r = EvaluateMain.run(
        Vector("--gold", labels.toString, "--decisions", run.toString, "--out", out.toString)
      )
      assertEquals(r.map(_.countedRows), Right(9))
      assert(
        Files.exists(out.resolve("er-evaluation.json")) && Files.exists(
          out.resolve("er-evaluation.md")
        )
      )
      val refused = dir.resolve("datasets/gold/report")
      val bad = EvaluateMain.run(
        Vector("--gold", labels.toString, "--decisions", run.toString, "--out", refused.toString)
      )
      assert(bad.left.exists(_.contains("datasets/gold")))
      assert(!Files.exists(dir.resolve("datasets")))
    finally
      val walk = Files.walk(dir)
      try
        walk.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
      finally walk.close()
  }

  test("EvaluateMain refuses a decision run made under another policy version than it loads") {
    val v2 = decisions.map(d =>
      d.copy(
        decisionId = Ids.decision(d.kind, d.sourceId, d.targetId, "er-v2"),
        policyVersion = "er-v2",
        provenance = d.provenance.copy(policyVersion = Some("er-v2"))
      )
    )
    assertEquals(EvaluateMain.samePolicy(decisions, "er-v1"), Right(()))
    assert(EvaluateMain.samePolicy(v2, "er-v1").left.exists(_.contains("policy er-v2")))
    // A mismatch carried only by the provenance is refused too.
    val mixed = decisions.updated(
      0,
      decisions(0).copy(provenance = decisions(0).provenance.copy(policyVersion = Some("er-v2")))
    )
    assert(EvaluateMain.samePolicy(mixed, "er-v1").isLeft)
    val dir = Files.createTempDirectory("sc-er-eval-policy-")
    try
      val run = dir.resolve("run")
      v2.groupBy(_.decision).foreach { (o, ds) =>
        val sub = run.resolve(scholarchain.er.ErOut.of(o))
        Files.createDirectories(sub)
        Files.writeString(
          sub.resolve("part-r-00000"),
          ds.map(JsonLines.encode(_)).mkString("\n") + "\n"
        )
      }
      Files.writeString(run.resolve("_SUCCESS"), "")
      val labels = Files.writeString(dir.resolve("labels.csv"), csv(goldRows))
      val out = dir.resolve("report")
      val r = EvaluateMain.run(
        Vector("--gold", labels.toString, "--decisions", run.toString, "--out", out.toString)
      )
      assert(r.left.exists(e => e.contains("er-v2") && e.contains("er-v1")), r)
      assert(!Files.exists(out), "nothing is written for a refused run")
    finally
      val walk = Files.walk(dir)
      try
        walk.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
      finally walk.close()
  }
