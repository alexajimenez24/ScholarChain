package scholarchain.er

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.ToolRunner

import scholarchain.core.*

/** Runs ER-J1 and ER-J2 with Hadoop's LocalJobRunner over the synthetic run of [[ErFixtures.Run]].
  *
  * Hand derivation (er-v1 weights in er-policy-v1.json; z is the logistic input):
  *   - W1-W2 share DOI 10.1000/abc1, same title: ACCEPTED, EXACT_ID, DOI_EQUAL.
  *   - W1-W3 same normalized title (5 tokens), year 2020, authors smith_a, jones_b in order, DBLP
  *     and OpenAlex: ACCEPTED, DETERMINISTIC_BIBLIOGRAPHIC.
  *   - W2-W4 DOIs differ; z = -5 + 6 * 1.0 - 0.05 = 0.95, score 0.721 > 0.35: CONFLICT.
  *   - W5-W6 DOIs differ; unrelated titles, score < 0.35: REJECTED, EXACT_ID.
  *   - Alice Smith (W1, W2) to orcid:0000-0002-1825-0097: names corroborate: 2 x ACCEPTED,
  *     EXACT_ID.
  *   - Bob Jones W1-W2: z = -4.3 + 3 + 1 + 6 + 2 + 0.3 - 0.05 = 7.95: ACCEPTED, PROBABILISTIC.
  *   - Alice-Bob on W1: same work: REJECTED, DETERMINISTIC_BIBLIOGRAPHIC.
  *   - Carol White W4-W5: ORCIDs differ, z = -0.35, score 0.413 > 0.35: CONFLICT.
  *   - Dan Brown W5-W6: z = -4.3 + 3 + 1 + 6 * 0.3 + 0.3 - 0.05 = 1.75, score 0.852 in [0.7, 0.9):
  *     UNRESOLVED in off mode, LLM band.
  *   - One malformed candidate line: one reject.
  */
class MatchJobSuite extends munit.FunSuite:
  import ErFixtures.*

  private val t = new TempDir("job")
  override def afterAll(): Unit = t.delete()
  private lazy val root: Path = writeRun(t, "hadoop")
  private val clock = () => java.time.Instant.parse("2026-11-23T08:00:00Z")

  final class Result(val code: Int, val out: Path, val conf: Configuration):
    def manifestIds: Vector[String] =
      val dir = out.resolve("run-manifests")
      if !Files.isDirectory(dir) then Vector.empty
      else
        Files
          .list(dir)
          .iterator
          .asScala
          .map(_.getFileName.toString)
          .toVector
          .filter(n => n.endsWith(".json") && !n.endsWith("-summary.json"))
          .map(_.stripSuffix(".json"))
          .sorted
    def runId: String = manifestIds match
      case Vector(one) => one
      case other       => throw new AssertionError(s"expected one run, got $other")
    def dir: String = s"$out/match-decisions/policy=er-v1/snapshot=$Snapshot/run_id=$runId"
    def lines(sub: String): Vector[String] = MatchJob.lines(conf, s"$dir/$sub/part-*")
    def decisions(sub: String): Vector[MatchDecision] =
      lines(sub).map(l =>
        JsonLines
          .decodeValid[MatchDecision](l, Validate.decision)
          .fold(e => throw new AssertionError(e), identity)
      )
    def all: Vector[MatchDecision] = ErOut.Outcomes.flatMap(decisions)
    def counters: Map[String, Long] =
      MatchJob
        .lines(conf, s"$dir/_counters.json")
        .headOption
        .map(j => ujson.read(j).obj.map((k, v) => k -> v.num.toLong).toMap)
        .getOrElse(Map.empty)
    def summary: ujson.Value =
      ujson.read(MatchJob.lines(conf, s"$out/run-manifests/$runId-summary.json").mkString)
    def reasonOf(c: ErCandidate): Vector[String] =
      all
        .find(_.provenance.sourceRecordId == c.candidateId)
        .map(_.reasonCodes)
        .getOrElse(Vector("missing"))

  private def run(
      out: String,
      extra: Seq[String] = Nil,
      env: Map[String, String] = Map.empty
  ): Result =
    val outDir = t.dir.resolve(out)
    val conf = LocalMatchRunner.localConf(outDir.toString)
    val args =
      Seq(
        "--run-root",
        root.toString,
        "--output",
        outDir.toString,
        "--reducers",
        "2",
        "--compress",
        "false"
      ) ++ extra
    new Result(ToolRunner.run(conf, new MatchJob(env, clock), args.toArray), outDir, conf)

  private lazy val off = run("off")
  import Run.*

  test("off mode: every candidate gets the hand-derived outcome, path and reason") {
    assertEquals(off.code, 0)
    val byOutcome = ErOut.Outcomes.map(o => o -> off.decisions(o).size).toMap
    assertEquals(
      byOutcome,
      Map("accepted" -> 5, "rejected" -> 2, "unresolved" -> 1, "conflict" -> 2)
    )
    assertEquals(off.reasonOf(exact(0)), Vector("DOI_EQUAL"))
    assertEquals(off.reasonOf(blocked(0)), Vector("TITLE_YEAR_AUTHORS_EQUAL"))
    assertEquals(off.reasonOf(blocked(1)), Vector("DOI_DISAGREES"))
    assertEquals(off.reasonOf(blocked(2)), Vector("DOI_DIFFERS_EVIDENCE_DIFFERS"))
    assertEquals(off.reasonOf(exact(1)), Vector("ORCID_EQUAL"))
    assertEquals(off.reasonOf(exact(2)), Vector("ORCID_EQUAL"))
    assertEquals(off.reasonOf(blocked(3)), Vector("SCORE_AT_OR_ABOVE_ACCEPT"))
    assertEquals(off.reasonOf(blocked(4)), Vector("COAUTHORS_ON_SAME_WORK"))
    assertEquals(off.reasonOf(blocked(5)), Vector("ORCID_DISAGREES"))
    assertEquals(off.reasonOf(bandCandidate), Vector("SCORE_IN_LLM_BAND", "LLM_OFF"))
  }

  test("off mode: counters, rejects, audit trail and the run summary agree") {
    val c = off.counters
    assertEquals(c.get(ErCounters.Decisions), Some(10L))
    assertEquals(c.get("RESEARCHER_ACCEPTED"), Some(3L))
    assertEquals(c.get("WORK_ACCEPTED"), Some(2L))
    assertEquals(c.get("PATH_EXACT_ID"), Some(6L))
    assertEquals(c.get("PATH_DETERMINISTIC_BIBLIOGRAPHIC"), Some(2L))
    assertEquals(c.get("PATH_PROBABILISTIC"), Some(2L))
    assertEquals(c.get("LLM_OFF"), Some(1L))
    assertEquals(c.get(ErCounters.SchemaFailure), Some(1L))
    assertEquals(off.lines("rejects-v1").size, 1)
    assertEquals(off.lines(ErOut.Audit).size, 10)
    assertEquals(off.lines(ErOut.Adjudications).size, 0)
    assertEquals(off.summary("unresolvedRate").num, 0.1)
    assertEquals(off.summary("byOutcome")("CONFLICT").num, 2.0)
    val audits = off.lines(ErOut.Audit).map(l => upickle.default.read[DecisionAudit](l))
    assert(
      audits.forall(a => a.decidedAt == "2026-11-23T08:00:00Z" && a.upstreamRunId == UpstreamRunId)
    )
    assert(
      audits.forall(a =>
        a.runId == off.runId && a.policyVersion == "er-v1" && a.leftFound && a.rightFound
      )
    )
    assert(off.all.forall(d => d.provenance.runId == off.runId && d.policyVersion == "er-v1"))
  }

  test("the run manifest records the upstream pipeline version and no timestamp") {
    val m = ujson.read(Files.readString(off.out.resolve(s"run-manifests/${off.runId}.json")))
    assertEquals(m("upstreamRunId").str, UpstreamRunId)
    assertEquals(m("upstreamPipeline").str, "hadoop-v2")
    assertEquals(m("policyVersion").str, "er-v1")
    assertEquals(m("adjudicator").str, "OFF")
    assert(!m.render().contains("2026-11-23"))
    assertEquals(Ids.run(m.render()), off.runId)
  }

  test("the run ID is reproducible, a rerun is idempotent and outputs are identical") {
    val again = run("off")
    assertEquals((again.code, again.runId), (0, off.runId))
    val elsewhere = run("off-copy")
    assertEquals(elsewhere.runId, off.runId)
    assertEquals(elsewhere.lines(ErOut.Accepted).sorted, off.lines(ErOut.Accepted).sorted)
    assertEquals(
      elsewhere.all.map(JsonLines.encode(_)).sorted,
      off.all.map(JsonLines.encode(_)).sorted
    )
  }

  private val budgetEnv = Map("ER_MAX_LLM_CALLS" -> "4")
  private lazy val fake = run("fake", Seq("--adjudicator", "fake"), budgetEnv)

  test("fake mode with a budget adjudicates the band case; the cache replays it without a call") {
    val env = budgetEnv
    assertEquals(fake.code, 0)
    val dan = fake.all.find(_.provenance.sourceRecordId == bandCandidate.candidateId).get
    assertEquals((dan.decision, dan.path), (MatchOutcome.ACCEPTED, MatchPath.LLM_ADJUDICATED))
    assertEquals(dan.modelVersion, Some(FakeAdjudicator.ModelVersion))
    assertEquals(fake.counters.get("LLM_CALLS"), Some(1L))
    assertEquals(fake.lines(ErOut.Adjudications).size, 1)
    assertNotEquals(fake.runId, off.runId)
    val replay =
      run("fake-replay", Seq("--adjudicator", "fake", "--adjudication-cache", fake.dir), env)
    assertEquals(replay.code, 0)
    assertEquals(replay.counters.getOrElse("LLM_CALLS", 0L), 0L)
    assertEquals(replay.counters.get("LLM_CACHE_HITS"), Some(1L))
    val again = replay.all.find(_.provenance.sourceRecordId == bandCandidate.candidateId).get
    assertEquals(JsonLines.encode(again.copy(provenance = dan.provenance)), JsonLines.encode(dan))
  }

  test("a forged but correctly keyed cache line is refused on replay and never adjudicates") {
    assertEquals(fake.code, 0)
    val line = fake.lines(ErOut.Adjudications) match
      case Vector(one) => one
      case other       => fail(s"expected one adjudication, got $other")
    val rec = upickle.default.read[AdjudicationRecord](line)
    val forged = upickle.default.write(
      rec.copy(output = rec.output.copy(confidence = 0.99, reasonCodes = Vector("see 10.1000/x")))
    )
    assertEquals(AdjudicationCache.parse(Vector(forged))._2, Vector.empty[String]) // keyed right
    val cacheDir = t.dir.resolve("forged-cache")
    Files.createDirectories(cacheDir.resolve(ErOut.Adjudications))
    Files.writeString(cacheDir.resolve(s"${ErOut.Adjudications}/part-r-00000"), forged + "\n")
    val r = run(
      "fake-forged",
      Seq("--adjudicator", "fake", "--adjudication-cache", cacheDir.toString),
      budgetEnv
    )
    assertEquals(r.code, 0)
    val dan = r.all.find(_.provenance.sourceRecordId == bandCandidate.candidateId).get
    assertEquals((dan.decision, dan.path), (MatchOutcome.UNRESOLVED, MatchPath.PROBABILISTIC))
    assertEquals(dan.reasonCodes.last, "LLM_INVALID_OUTPUT")
    assert(!r.all.exists(_.path == MatchPath.LLM_ADJUDICATED))
    assert(!r.all.exists(_.reasonCodes.exists(_.contains("10.1000"))))
    assertEquals(r.counters.get("LLM_INVALID_OUTPUT"), Some(1L))
    assertEquals(r.counters.getOrElse("LLM_CALLS", 0L), 0L)
    assertEquals(r.counters.getOrElse("LLM_CACHE_HITS", 0L), 0L)
    assertEquals(r.lines(ErOut.Adjudications), Vector.empty[String])
  }

  test("local Hadoop scratch lives beside the output root, never inside it") {
    val out = t.dir.resolve("scratch-out")
    val scratch = LocalMatchRunner.scratchDir(out.toString)
    assertEquals(scratch, t.dir.resolve("scratch-out-tmp").toAbsolutePath.normalize)
    assertEquals(LocalMatchRunner.scratchDir(out.toString + "/"), scratch)
    assertEquals(LocalMatchRunner.localConf(out.toString).get("hadoop.tmp.dir"), scratch.toString)
    assert(!scratch.startsWith(out.toAbsolutePath.normalize))
    val args = Seq("--run-root", root.toString, "--output", out.toString, "--reducers", "1")
    assertEquals(LocalMatchRunner.run(args.toArray, new MatchJob(Map.empty, clock)), 0)
    val top = Files.list(out).iterator.asScala.map(_.getFileName.toString).toVector.sorted
    assertEquals(top, Vector("_er-profiles", "match-decisions", "run-manifests"))
    assert(!Files.exists(out.resolve("_local-tmp")))
    assert(!Files.exists(scratch), "a scratch folder this run created is removed afterwards")
    // A scratch folder that existed before the run is left in place.
    Files.createDirectories(scratch)
    Files.writeString(scratch.resolve("keep.txt"), "x")
    assertEquals(LocalMatchRunner.run(args.toArray, new MatchJob(Map.empty, clock)), 0)
    assert(Files.exists(scratch.resolve("keep.txt")))
  }

  test("fake mode without a budget leaves the band case UNRESOLVED") {
    val r = run("fake-nobudget", Seq("--adjudicator", "fake"))
    assertEquals(r.counters.get("LLM_BUDGET_EXHAUSTED"), Some(1L))
    assertEquals(r.decisions(ErOut.Unresolved).size, 1)
  }

  test("refusals: human-only paths, LLM enabled, retuned thresholds and bad arguments") {
    val gold = t.dir.resolve("x/datasets/gold/out").toString
    val conf = LocalMatchRunner.localConf(gold)
    def code(args: Seq[String], env: Map[String, String] = Map.empty) =
      ToolRunner.run(new Configuration(conf), new MatchJob(env, clock), args.toArray)
    val base = Seq("--run-root", root.toString, "--output", t.dir.resolve("refused").toString)
    assertEquals(code(Seq("--run-root", root.toString, "--output", gold)), 3)
    assert(!Files.exists(Path.of(gold)))
    assertEquals(code(base, Map("LLM_ENABLED" -> "true")), 3)
    assertEquals(code(base, Map("SCHOLARCHAIN__ENTITYRESOLUTION__ACCEPTTHRESHOLD" -> "0.95")), 3)
    assertEquals(code(base ++ Seq("--adjudicator", "bedrock")), 2)
    assertEquals(code(base ++ Seq("--reducers", "0")), 2)
    assertEquals(code(base ++ Seq("--surprise", "x")), 2)
    assert(!Files.exists(t.dir.resolve("refused/match-decisions")))
  }
