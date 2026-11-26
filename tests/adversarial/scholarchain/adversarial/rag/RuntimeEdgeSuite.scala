package scholarchain.adversarial.rag

import scholarchain.adversarial.rag.MiniWorld.*
import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.{GraphQuery, GraphStore}

/** Phase 11 runtime edge cases with no black-box test before (`coverage-before.md` rows 7, 10, 13,
  * 15 and 18): an empty served graph, a disconnected graph, missing researcher metadata and an
  * unknown topic filter, a failing graph store (Neptune timeout), malformed model replies through
  * the whole workflow, and a replayed query ID.
  *
  * Fixture: [[MiniWorld]] (hand-built, bounded). Public API only (`modules/rag/MODULE.md`, "Phase
  * 09 workflow API"; `modules/storage/MODULE.md`, `GraphStore.run`). Spec
  * (`docs/spec/assignment.md`): abstain on missing evidence, never invent a value, bounded retries
  * with a deterministic fallback, and an audit record per query.
  */
class RuntimeEdgeSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val Since = Constraints(yearFrom = Some(2015), yearTo = Some(2026))

  private def metricsReq(name: String) =
    AnalysisRequest(s"""What are the metrics of "$name" since 2015?""", Since)

  private def pathReq(a: String, b: String) =
    AnalysisRequest(
      s"""What is the shortest coauthorship path from "$a" to "$b" in 2015-2026?""",
      Since
    )

  private def ok(r: Either[WorkflowError, WorkflowResult]): WorkflowResult =
    r.fold(e => fail(s"workflow error: ${e.describe}"), identity)

  private def verified(res: WorkflowResult): Vector[AnswerClaim] =
    res.answer.claims.filter(ModelV2.isVerified)

  private def show(res: WorkflowResult): String =
    s"abstained=${res.answer.abstained} reason=${res.answer.abstentionReason} " +
      s"claims=${res.answer.claims.map(c => (c.claimId, c.verificationStatus, c.text))}"

  private def report(ctx: String, bad: Iterable[String]): Unit =
    assert(bad.isEmpty, ctx + bad.mkString("\n", "\n", "\n"))

  // ----- empty and disconnected graphs ------------------------------------------------------------

  test(
    "empty graph: a named researcher abstains NOT_FOUND with no claim, no row and no model call"
  ) {
    empty.stores() match
      case Left(e) =>
        // refusing to serve an empty run is acceptable; it must be a Left, never a throw
        println(s"OBSERVE RuntimeEdgeSuite empty stores refused: $e")
      case Right(_) =>
        val llm = ScriptedLlm.replies(ModelV2.reply("Alice Arden has 41 distinct coauthors."))
        val wf = empty.workflow(new LlmSynthesizer(llm, prompt, cfg))
        val res = ok(wf.run("empty-1", metricsReq("Alice Arden")))
        println(s"OBSERVE RuntimeEdgeSuite empty: ${show(res)}")
        report(
          "empty graph:",
          Option.when(!res.answer.abstained)(s"not abstained: ${show(res)}") ++
            Option.when(res.answer.claims.nonEmpty)(s"claims on an empty graph: ${show(res)}") ++
            Option.when(res.answer.researchers.nonEmpty)(s"rows: ${res.answer.researchers}") ++
            Option.when(
              !res.evidence.resolutions.forall(_.status.toString == "NOT_FOUND")
            )(s"resolutions ${res.evidence.resolutions.map(_.status)}") ++
            Option.when(llm.prompts.nonEmpty)(s"the model was called ${llm.prompts.size} times")
        )
  }

  test("empty graph: a ranking question states no researcher and no VERIFIED claim") {
    empty.stores() match
      case Left(e)  => println(s"OBSERVE RuntimeEdgeSuite empty stores refused: $e")
      case Right(_) =>
        val r = AnalysisRequest(
          "Which researcher has the most distinct coauthors in 2015-2026?",
          Since,
          AnalysisOptions(rankingMetric = Some(CollaborationMetric.DISTINCT_COAUTHOR_COUNT))
        )
        empty.workflow().run("empty-2", r) match
          case Left(e)    => println(s"OBSERVE RuntimeEdgeSuite empty ranking: ${e.describe}")
          case Right(res) =>
            report(
              "empty ranking:",
              verified(res).map(c => s"VERIFIED on an empty graph: '${c.text}'") ++
                Option.when(res.answer.researchers.nonEmpty)(s"rows: ${res.answer.researchers}")
            )
  }

  private val edgesOf: Set[Set[String]] =
    standardCoauthors.map(e => Set(e.researcherA, e.researcherB)).toSet

  private def hopsExist(p: Vector[String]): Boolean =
    p.sliding(2).forall(w => w.size < 2 || edgesOf(w.toSet))

  test("disconnected graph: no path to the isolated researcher is invented; the chain is found") {
    val wf = standard.workflow()
    val control = ok(wf.run("path-control", pathReq("Alice Arden", "Dan Dorsey")))
    val iso = ok(wf.run("path-isolated", pathReq("Alice Arden", "Ivan Isola")))
    println(s"OBSERVE RuntimeEdgeSuite isolated path: ${show(iso)}")
    val paths = iso.evidence.bundle.all.flatMap(i => i.graphPath.map(i -> _))
    report(
      "disconnected:",
      Option.when(
        !control.evidence.bundle.deterministic
          .exists(_.graphPath.contains(Vector(Alice, Bob, Carol, Dan)))
      )(s"control path Alice-Bob-Carol-Dan not in the evidence: ${show(control)}") ++
        paths.collect {
          case (i, p)
              if p.contains(Ivan) && p.size >= 2 && !i.values.get("found").contains("false") &&
                !hopsExist(p) =>
            s"invented path ${p.mkString(" -> ")} in ${i.evidenceId} (${i.values})"
        } ++
        verified(iso)
          .filter(c => c.text.contains(Ivan) || c.text.contains("Ivan Isola"))
          .filter(c => """\b[1-9]\d* hops?\b""".r.findFirstIn(c.text).isDefined)
          // a negative statement ("No coauthorship path of at most 6 hops ...") is the right answer
          .filterNot(c => c.text.toLowerCase.startsWith("no ") || c.text.contains(" no "))
          .map(c => s"VERIFIED hop count to an isolated researcher: '${c.text}'") ++
        Option.when(
          !iso.answer.abstained && !verified(iso).exists { c =>
            val t = c.text.toLowerCase
            t.contains("ivan isola") && t.contains("path") &&
            (t.startsWith("no ") || t.contains(" no ") || t.contains(" not "))
          }
        )(s"neither an abstention nor a VERIFIED statement that no path exists: ${show(iso)}")
    )
  }

  // ----- missing metadata -------------------------------------------------------------------------

  test("missing metadata: no community, bridge, coreness, topic or profile is invented for Mia") {
    val res = ok(standard.workflow().run("meta-mia", metricsReq("Mia Nometa")))
    println(s"OBSERVE RuntimeEdgeSuite Mia: ${show(res)}; rows ${res.answer.researchers}")
    val row = res.answer.researchers.find(_.researcherId == Mia)
    report(
      "Mia:",
      Option.when(row.isEmpty)(s"no row for Mia: ${show(res)}") ++
        row.toVector.flatMap { m =>
          Option.when(m.publicationCount != 1L || m.distinctCoauthorCount != 1L)(
            s"stored counts not copied: ${m.publicationCount}, ${m.distinctCoauthorCount}"
          ) ++ Option.when(m.communityId.isDefined)(s"invented community ${m.communityId}") ++
            Option.when(m.bridgeMetric.isDefined)(s"invented bridge ${m.bridgeMetric}") ++
            Option.when(m.coreness.isDefined)(s"invented coreness ${m.coreness}")
        } ++
        res.answer.claims
          .filter(c =>
            Vector(QuantumCommunity, BiologyCommunity, SystemsCommunity).exists(c.text.contains)
          )
          .map(c => s"a community ID appears for Mia: '${c.text}'") ++
        res.evidence.bundle.deterministic
          .filter(i =>
            i.evidenceType.toString == "COMMUNITY_RECORD" && i.researcherIds.contains(Mia)
          )
          .map(i => s"community evidence for Mia: ${i.evidenceId}")
    )
  }

  test("missing metadata: an unknown topic filter never falls back to the global ranking") {
    val r = AnalysisRequest(
      "Which researcher has the most distinct coauthors in Nonexistent topic zqx in 2015-2026?",
      Since.copy(topics = Vector("Nonexistent topic zqx")),
      AnalysisOptions(rankingMetric = Some(CollaborationMetric.DISTINCT_COAUTHOR_COUNT))
    )
    standard.workflow().run("meta-topic", r) match
      case Left(e)    => println(s"OBSERVE RuntimeEdgeSuite unknown topic: ${e.describe}")
      case Right(res) =>
        println(s"OBSERVE RuntimeEdgeSuite unknown topic: ${show(res)}")
        val ranked = res.evidence.bundle.deterministic
          .filter(_.values.get("kind").contains("ranking"))
          .filter(i => people.exists(p => i.values.getOrElse("positions", "").contains(p)))
        report(
          "unknown topic:",
          ranked.map(i => s"a ranking lists researchers for an unknown topic: ${i.values}") ++
            verified(res)
              .filter(c => people.exists(p => c.text.contains(p) || c.text.contains(Names(p))))
              .map(c => s"VERIFIED researcher claim for an unknown topic: '${c.text}'")
        )
  }

  // ----- graph store failure ----------------------------------------------------------------------

  private val TimeoutText = "Neptune query timed out after 30000 ms (secret-marker-7f3a)"

  private val failing: GraphStore => GraphStore = _ =>
    new GraphStore:
      def run(query: GraphQuery): Either[String, Vector[ujson.Obj]] = Left(TimeoutText)

  test("Neptune timeout: every graph query fails; no claim is VERIFIED and no value invented") {
    val wf = standard.workflow(graph = failing)
    val bad = Vector(
      "metrics" -> metricsReq("Alice Arden"),
      "path" -> pathReq("Alice Arden", "Dan Dorsey")
    ).flatMap { (name, r) =>
      wf.run(s"neptune-$name", r) match
        case Left(e) =>
          println(s"OBSERVE RuntimeEdgeSuite neptune $name: ${e.describe}")
          Vector.empty
        case Right(res) =>
          println(s"OBSERVE RuntimeEdgeSuite neptune $name: ${show(res)}")
          verified(res).map(c => s"$name: VERIFIED while the graph store fails: '${c.text}'") ++
            Option.when(
              (res.answer.summary +: res.answer.claims.map(_.text)).exists(_.contains("7f3a"))
            )(s"$name: downstream error text reached the answer: ${show(res)}") ++
            Option.when(!res.answer.abstained && res.answer.claims.nonEmpty)(
              s"$name: an answer with claims while every graph query failed: ${show(res)}"
            )
    }
    report("failing graph store:", bad)
  }

  // ----- malformed model replies ------------------------------------------------------------------

  test(
    "LLM malformed JSON: truncated, non-object and prose replies end in the deterministic answer"
  ) {
    val replies = Vector(
      """{"schemaVersion":"answer-synthesis-v2","summary":"MARKER-TRUNC Alice has 999""",
      """["MARKER-ARRAY", {"claimId":"claim-1"}]""",
      "MARKER-PROSE Alice Arden has 999 distinct coauthors.",
      ModelV2.reply("MARKER-LATE Alice Arden has 999 distinct coauthors.")
    )
    val llm = ScriptedLlm.replies(replies*)
    val res = ok(MiniWorld.withModel(llm).run("llm-malformed", metricsReq("Alice Arden")))
    val text = (res.answer.summary +: res.answer.claims.map(_.text)).mkString("\n")
    println(
      s"OBSERVE RuntimeEdgeSuite malformed: calls ${llm.prompts.size}, fallback ${res.synthesis.fallback}, " +
        s"attempts ${res.synthesis.attempts.map(a => (a.attempt, a.status, a.reason))}"
    )
    report(
      "malformed replies:",
      Option.when(res.synthesis.fallback.isEmpty)("no deterministic fallback recorded") ++
        Option.when(llm.prompts.size > SynthesisLimits().maxAttempts)(
          s"${llm.prompts.size} model calls, above the bound ${SynthesisLimits().maxAttempts}"
        ) ++
        Option.when(res.answer.promptVersion != "deterministic-synthesis-v1")(
          s"promptVersion ${res.answer.promptVersion}"
        ) ++
        Option.when(text.contains("MARKER") || BenchmarkScoring.states(text, BigDecimal(999)))(
          s"model text reached the answer: '$text'"
        ) ++
        Option.when(
          !res.answer.researchers.exists(r =>
            r.researcherId == Alice && r.distinctCoauthorCount == 41L
          )
        )(
          s"the deterministic answer lost the stored value: ${res.answer.researchers}"
        ) ++
        Option.when(res.audit.synthesisFallback != res.synthesis.fallback)(
          s"audit fallback ${res.audit.synthesisFallback} != ${res.synthesis.fallback}"
        )
    )
  }

  // ----- replay and idempotency -------------------------------------------------------------------

  test("replay: the same query ID and request give the same audit record, stored once") {
    val log = new InMemoryAuditLog
    val wf = standard.workflow(audit = log, clock = () => 1_700_000_000_000L)
    val a = ok(wf.run("replay-1", metricsReq("Alice Arden")))
    val b = ok(wf.run("replay-1", metricsReq("Alice Arden")))
    report(
      "replay:",
      Option.when(a.audit != b.audit)(
        s"replayed audit differs: ${a.audit.answerHash} vs ${b.audit.answerHash}"
      ) ++
        Option.when(a.answer != b.answer)("replayed answer differs") ++
        Option.when(log.all.size != 1)(s"${log.all.size} audit records for one query ID") ++
        Option.when(log.get("replay-1") != Right(Some(a.audit)))(s"stored ${log.get("replay-1")}")
    )
  }

  test("replay: another request under a used query ID never replaces the stored audit record") {
    val log = new InMemoryAuditLog
    val wf = standard.workflow(audit = log, clock = () => 1_700_000_000_000L)
    val first = ok(wf.run("replay-2", metricsReq("Alice Arden")))
    val second = wf.run("replay-2", metricsReq("Carol Chen"))
    println(
      s"OBSERVE RuntimeEdgeSuite replay conflict: ${second.left.map(_.describe).map(_.answer.queryId)}"
    )
    report(
      "replay conflict:",
      Option.when(log.get("replay-2") != Right(Some(first.audit)))(
        s"the stored record changed: ${log.get("replay-2").map(_.map(_.answerHash))}"
      ) ++
        // MODULE.md: "WorkflowResult.audit is the same record that was appended"; a Right whose
        // audit is not the stored record would report an answer that has no audit trail
        second.toOption
          .filter(r => log.get("replay-2") != Right(Some(r.audit)))
          .map(r =>
            s"second run returned Right with an audit that was never stored (${r.audit.answerHash})"
          ) ++
        Option.when(log.all.size != 1)(s"${log.all.size} audit records")
    )
  }
