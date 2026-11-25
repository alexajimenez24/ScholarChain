package scholarchain.adversarial.rag

import java.nio.file.{Files, Path}

import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.{GraphQuery, InMemoryGraphStore, LocalIngestion}

/** One loaded Phase 08 run, shared by the workflow benchmark and the gate suites (Phase 09 step 3,
  * test-engineer).
  *
  * Public API only (`modules/rag/MODULE.md`, "Phase 09 workflow API"): `LocalRuntime.load` on run
  * `run-ccf619c509611f06` with the ER run `run-cb5a0421c406737d`, a window subset (2010-2014 and
  * 2015-2026) and the Phase 08 vector subset (the default 8,000 rows per type). The runtime is
  * loaded once per JVM, because it holds every Researcher node.
  *
  * Every workflow run goes through [[observe]], which serializes runs and returns the graph queries
  * that run executed (`InMemoryGraphStore.executed`), so a test sees exactly the database code one
  * request produced.
  */
object BenchmarkRuntime:

  val ErRun = "run-cb5a0421c406737d"
  val Windows: Set[String] = Set("2010-2014", "2015-2026")

  def manifestPath: Path =
    BenchmarkStore.runDir.getParent.getParent.getParent
      .resolve("run-manifests")
      .resolve(BenchmarkStore.GraphRun + ".json")

  def present: Boolean = BenchmarkStore.present && Files.isRegularFile(manifestPath)

  lazy val cfg: ScholarChainConfig =
    LocalIngestion.localConfig().fold(e => throw new IllegalStateException(e), identity)

  /** Load time and heap after load, for the results text. */
  @volatile var loadNote: String = "not loaded"

  lazy val runtime: LocalRuntime =
    BenchmarkStore.HeavyLoad.acquire()
    val t0 = System.nanoTime()
    val rt =
      try
        LocalRuntime
          .load(cfg, manifestPath, ErRun, LocalRuntimeSettings(windows = Some(Windows)))
          .fold(e => throw new IllegalStateException(s"LocalRuntime.load failed: $e"), identity)
      finally BenchmarkStore.HeavyLoad.release()
    val rtm = java.lang.Runtime.getRuntime
    System.gc()
    val usedMb = (rtm.totalMemory() - rtm.freeMemory()) / (1024 * 1024)
    loadNote =
      s"LocalRuntime.load ${(System.nanoTime() - t0) / 1000000} ms; heap used after load " +
        s"$usedMb MB of ${rtm.maxMemory() / (1024 * 1024)} MB max; vector chunks ${rt.report.vectorChunks}"
    rt

  lazy val graph: InMemoryGraphStore = runtime.stores.graph match
    case g: InMemoryGraphStore => g
    case other                 =>
      throw new AssertionError(s"runtime graph store is ${other.getClass}, not InMemoryGraphStore")

  lazy val prompt: SynthesisPrompt =
    SynthesisPrompt
      .load(BenchmarkStore.repoRoot)
      .fold(e => throw new IllegalStateException(s"SynthesisPrompt.load: $e"), identity)

  private val lock = new Object

  /** Runs `f` alone and returns its value with the graph queries it executed, in call order. */
  def observe[A](f: => A): (A, Vector[GraphQuery]) = lock.synchronized {
    val before = graph.executed.size
    val a = f
    (a, graph.executed.drop(before))
  }

  /** Every graph query executed so far by any suite. */
  def allExecuted: Vector[GraphQuery] = lock.synchronized(graph.executed)

  def deterministic(log: AuditLog = new InMemoryAuditLog): CollaborationWorkflow =
    runtime.workflow(audit = log)

  def withModel(llm: LlmPort, log: AuditLog = new InMemoryAuditLog): CollaborationWorkflow =
    runtime.workflow(new LlmSynthesizer(llm, prompt, cfg), log)

  private def bounds(window: String): (Int, Int) =
    val Array(a, b) = window.split("-")
    (a.toInt, b.toInt)

  /** The spec's `POST /v1/query` request shape (`docs/spec/assignment.md`, REST API contract): the
    * question text plus `constraints` (yearFrom, yearTo, topics, institutions) and
    * `analysis.rankingMetric`. Researcher names stay in the text, quoted, as the spec request has
    * no names field. A temporal question names both windows in its text and gets no year bounds.
    */
  def request(q: ujson.Value): AnalysisRequest =
    val c = q("constraints").obj
    val (from, to) =
      if c.contains("windows") then (None, None)
      else
        val (a, b) = bounds(q("window").str)
        (Some(a), Some(b))
    val metric = c.get("metric").map(_.str).map {
      case "distinctCoauthorCount" => CollaborationMetric.DISTINCT_COAUTHOR_COUNT
      case "bridgeScore"           => CollaborationMetric.BRIDGE_SCORE
      case other                   => throw new IllegalArgumentException(other)
    }
    AnalysisRequest(
      q("question").str,
      Constraints(
        yearFrom = from,
        yearTo = to,
        topics = c.get("topic").map(_.str).toVector,
        institutions = c.get("institution").map(_.str).toVector
      ),
      AnalysisOptions(rankingMetric = metric)
    )

  final case class BenchmarkRun(
      question: ujson.Value,
      result: Either[WorkflowError, WorkflowResult],
      queries: Vector[GraphQuery],
      log: InMemoryAuditLog,
      textOnly: Either[WorkflowError, EvidenceStageResult]
  )

  /** All benchmark questions through stages 1 to 10, once per JVM, plus a text-only stages 1 to 7
    * run of each question for the planner check.
    */
  lazy val benchmark: Vector[BenchmarkRun] =
    BenchmarkQuestions.questions.map { q =>
      val id = q("id").str
      val log = new InMemoryAuditLog
      val wf = deterministic(log)
      val (r, qs) = observe(wf.run(id, request(q)))
      val (t, _) = observe(wf.evidence.run(id + "-text", AnalysisRequest(q("question").str)))
      BenchmarkRun(q, r, qs, log, t)
    }
