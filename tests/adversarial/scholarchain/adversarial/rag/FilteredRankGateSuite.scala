package scholarchain.adversarial.rag

import scholarchain.adversarial.rag.MiniWorld.*
import scholarchain.adversarial.rag.ModelV2.{Draft, figureId, figureVerified, reply}
import scholarchain.core.*
import scholarchain.rag.*

/** Phase 11 carried item C09, gate test for R6-1 (`docs/agent-runs/phase-09/review-report-06.md`,
  * MAJOR): a rank figure on a filtered ranking (topic, institution, or the bridge ranking under a
  * topic filter) must never come out VERIFIED with a text that omits the filter. On the fixture the
  * filtered rank 1 is NOT the global rank 1, so "rank ... : 1" without the filter is a false
  * statement.
  *
  * Either repair is accepted (work order C09): the VERIFIED text names the filter, or rank figures
  * on a filtered ranking are refused (not VERIFIED). The limit is not asserted: a rank-1 statement
  * is true or false independently of the list length, and the false statement is the missing
  * filter. The unfiltered control must stay VERIFIED, so a repair that refuses every rank figure
  * fails.
  *
  * Fixture: [[MiniWorld.standard]] (hand-built, no export). Public API only:
  * `modules/rag/MODULE.md` 4b (claim-verifier-v3, `claim-N-figure-K`), `answer-synthesis-v2`
  * replies through `ScriptedLlm`. Spec: stage 9 "Check every ... numerical value against the
  * bundle"; gate "Unsupported claims are removed, weakened, or explicitly labeled".
  */
class FilteredRankGateSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val Win = "2015-2026"

  private def req(q: String, metric: CollaborationMetric, c: Constraints = Constraints()) =
    AnalysisRequest(
      q,
      c.copy(yearFrom = Some(2015), yearTo = Some(2026)),
      AnalysisOptions(rankingMetric = Some(metric))
    )

  private val Dcc = CollaborationMetric.DISTINCT_COAUTHOR_COUNT
  private val Bridge = CollaborationMetric.BRIDGE_SCORE

  private val globalDcc =
    req(s"Which researcher has the most distinct coauthors in $Win?", Dcc)
  private val topicDcc = req(
    s"Which researcher has the most distinct coauthors in $QuantumTopicName in $Win?",
    Dcc,
    Constraints(topics = Vector(QuantumTopicName))
  )
  private val instDcc = req(
    s"Which researcher at $QuantumInstName has the most distinct coauthors in $Win?",
    Dcc,
    Constraints(institutions = Vector(QuantumInstName))
  )
  private val globalBridge = req(
    s"Which researcher is the most important bridge between collaboration communities in $Win?",
    Bridge
  )
  private val topicBridge = req(
    s"Which researcher is the most important bridge between collaboration communities in $QuantumTopicName in $Win?",
    Bridge,
    Constraints(topics = Vector(QuantumTopicName))
  )

  private final case class Ranking(item: EvidenceItem, positions: Vector[(Int, String)]):
    def top: String = positions.headOption.map(_._2).getOrElse("")

  private def stages(qid: String, r: AnalysisRequest): EvidenceStageResult =
    standard.workflow().evidence.run(qid, r).fold(e => fail(s"$qid: ${e.describe}"), identity)

  /** The ranking item of stages 1 to 7 and its positions (`rank:researcherId=value;...`). */
  private def ranking(qid: String, r: AnalysisRequest): Ranking =
    val b = stages(qid, r).bundle
    val item = b.deterministic
      .find(_.values.get("kind").contains("ranking"))
      .getOrElse(
        fail(s"$qid: no ranking item; kinds ${b.deterministic.map(_.values.get("kind"))}")
      )
    val pos = item.values
      .getOrElse("positions", "")
      .split(";")
      .toVector
      .filter(_.contains("="))
      .map { s =>
        val Array(rank, rest) = s.split(":", 2)
        (rank.trim.toInt, rest.take(rest.lastIndexOf('=')))
      }
    println(s"OBSERVE FilteredRankGateSuite $qid ranking values: ${item.values}")
    Ranking(item, pos)

  private def runModel(qid: String, r: AnalysisRequest, text: String): WorkflowResult =
    val res = MiniWorld
      .withModel(ScriptedLlm.replies(text))
      .run(qid, r)
      .fold(e => fail(s"$qid workflow error: ${e.describe}"), identity)
    assertEquals(res.synthesis.fallback, None, s"$qid: the model reply was not used")
    res

  private def has(text: String, phrase: String) = text.toLowerCase.contains(phrase.toLowerCase)

  /** Every VERIFIED claim that speaks of a rank or the lead for `rid` must name `filter`. */
  private def unqualified(res: WorkflowResult, rid: String, filter: String): Vector[String] =
    res.answer.claims
      .filter(ModelV2.isVerified)
      .filter(c => c.text.contains(rid) || has(c.text, Names(rid)))
      .filter(c => Vector("rank", "lead", "first", "top", "most").exists(w => has(c.text, w)))
      .filterNot(c => has(c.text, filter))
      .map(c => s"${c.claimId} VERIFIED without the filter '$filter': '${c.text}'")

  private def report(ctx: String, bad: Vector[String]): Unit =
    assert(bad.isEmpty, ctx + bad.mkString("\n", "\n", "\n"))

  /** The global ranking leader, the precondition that makes the filtered rank 1 a false global
    * claim.
    */
  private lazy val globalTop: String = ranking("c09-global-probe", globalDcc).top
  private lazy val globalBridgeTop: String = ranking("c09-global-bridge-probe", globalBridge).top

  test("fixture: Carol leads both global rankings; Alice and Bob lead the filtered ones") {
    assertEquals(globalTop, Carol)
    assertEquals(globalBridgeTop, Carol)
    assertEquals(ranking("c09-topic-probe", topicDcc).top, Alice)
    assertEquals(ranking("c09-inst-probe", instDcc).top, Alice)
    assertEquals(ranking("c09-bridge-probe", topicBridge).top, Bob)
  }

  test("control: rank 1 on the unfiltered ranking is VERIFIED for the global leader") {
    val g = ranking("c09-control-probe", globalDcc)
    val ev = Vector(g.item.evidenceId)
    val res = runModel(
      "c09-control",
      globalDcc,
      reply(
        "The ranking.",
        Draft(
          "claim-1",
          "The leader holds position {figure-1}.",
          ev,
          Vector((g.top, "rank", "1", ev))
        )
      )
    )
    report(
      s"global ranking ${g.positions}:",
      figureVerified(res, "claim-1", 1, g.top, BigDecimal(1)).toVector
    )
  }

  /** The R6-1 failure input for one filtered request: the filtered leader at rank 1. */
  private def gate(
      name: String,
      r: AnalysisRequest,
      leader: String,
      filter: String,
      global: => String
  ) =
    test(s"R6-1 $name: rank 1 of the filtered leader is never VERIFIED without the filter") {
      val f = ranking(s"c09-$name-probe", r)
      assertEquals(f.top, leader, s"filtered positions ${f.positions}")
      assertNotEquals(global, leader, "precondition: the filtered leader is not the global leader")
      val ev = Vector(f.item.evidenceId)
      val claims = Vector(
        Draft(
          "claim-1",
          "The leader holds position {figure-1}.",
          ev,
          Vector((leader, "rank", "1", ev))
        ),
        Draft(
          "claim-2",
          "{figure-1} is the position in the ranking.",
          ev,
          Vector((leader, "rank", "1", ev))
        )
      )
      val res = runModel(s"c09-$name", r, reply("The ranking.", claims*))
      println(
        s"OBSERVE FilteredRankGateSuite $name answer: " +
          res.answer.claims.map(c => (c.claimId, c.verificationStatus, c.text))
      )
      report(
        s"$name ranking ${f.positions} (global leader $global):",
        unqualified(res, leader, filter) ++
          // and a model answer's overall verification is never VERIFIED
          ModelV2.notVerifiedOverall(res).toVector
      )
    }

  gate("topic", topicDcc, Alice, QuantumTopicName, globalTop)
  gate("institution", instDcc, Alice, QuantumInstName, globalTop)
  gate("bridge", topicBridge, Bob, QuantumTopicName, globalBridgeTop)

  test("R6-1: the global leader's rank 1 cited against a filtered ranking is never VERIFIED") {
    val f = ranking("c09-outsider-probe", topicDcc)
    val ev = Vector(f.item.evidenceId)
    val res = runModel(
      "c09-outsider",
      topicDcc,
      reply(
        "The ranking.",
        Draft(
          "claim-1",
          "The leader holds position {figure-1}.",
          ev,
          Vector((Carol, "rank", "1", ev))
        )
      )
    )
    report(
      s"topic ranking ${f.positions}:",
      ModelV2.figureNotVerified(res, "claim-1", 1).toVector ++
        res.answer.claims
          .filter(c => ModelV2.isVerified(c) && c.claimId == figureId("claim-1", 1))
          .map(c => s"VERIFIED: '${c.text}'")
    )
  }

  test(
    "R6-1 deterministic path: every VERIFIED rank statement on a filtered ranking names the filter"
  ) {
    val bad = Vector(
      ("topic", topicDcc, Alice, QuantumTopicName),
      ("institution", instDcc, Alice, QuantumInstName),
      ("bridge", topicBridge, Bob, QuantumTopicName)
    ).flatMap { (name, r, leader, filter) =>
      val res = standard
        .workflow()
        .run(s"c09-det-$name", r)
        .fold(e => fail(s"$name: ${e.describe}"), identity)
      println(
        s"OBSERVE FilteredRankGateSuite deterministic $name: " +
          res.answer.claims.map(c => (c.claimId, c.verificationStatus, c.text))
      )
      unqualified(res, leader, filter).map(x => s"$name: $x")
    }
    report("deterministic answers:", bad)
  }
