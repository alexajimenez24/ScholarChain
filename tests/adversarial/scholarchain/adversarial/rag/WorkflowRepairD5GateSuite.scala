package scholarchain.adversarial.rag

import scholarchain.adversarial.rag.BenchmarkScoring.num
import scholarchain.core.*
import scholarchain.rag.*

/** Phase 09 repair round 2 gate tests (test-engineer) for D5 (review-report-02.md): a number whose
  * subject is not the researcher it is stated for must never come out VERIFIED when the claim names
  * two researchers and cites both of their metric records. Covers the variants no earlier test
  * checks: the number before two names ("With N ..., X leads Y."), and comparative prose ("X, like
  * Y, has N", "X (unlike Y) has N"). Public API only (`modules/rag/MODULE.md`); same loaded run as
  * [[WorkflowRepairGateSuite]].
  *
  * Spec (`docs/spec/assignment.md`): stage 9 "Check every researcher identity, ... coauthor count,
  * ... and numerical value against the bundle"; gate "Unsupported claims are removed, weakened, or
  * explicitly labeled".
  */
class WorkflowRepairD5GateSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(40, "min")

  private val Win = "2010-2014"

  private def rt: LocalRuntime =
    assume(BenchmarkRuntime.present, s"run data absent at ${BenchmarkStore.runDir}")
    BenchmarkRuntime.runtime

  private def reply(summary: String, claims: (String, String, Vector[String])*): String =
    ujson.write(
      ujson.Obj(
        "schemaVersion" -> "answer-synthesis-v1",
        "summary" -> summary,
        "abstain" -> false,
        "abstentionReason" -> ujson.Null,
        "claims" -> ujson.Arr.from(claims.map { (id, text, ev) =>
          ujson.Obj(
            "claimId" -> id,
            "text" -> text,
            "evidenceIds" -> ujson.Arr.from(ev.map(ujson.Str(_))),
            "uncertainty" -> ujson.Null
          )
        })
      )
    )

  private final case class Pair(
      qid: String,
      req: AnalysisRequest,
      nameA: String,
      nameB: String,
      idA: String,
      idB: String,
      cA: BigDecimal,
      cB: BigDecimal,
      pA: Option[BigDecimal],
      pB: Option[BigDecimal]
  )

  private def metric(b: EvidenceBundle, rid: String): Option[EvidenceItem] =
    b.deterministic.find(x =>
      x.values.get("kind").contains("researcher-metrics") && x.researcherIds == Vector(rid) &&
        x.values.get("window").contains(Win)
    )

  /** The first frozen two-name question in window 2010-2014 whose two researchers both have metric
    * records with different distinct-coauthor counts (the same selection rule as
    * [[WorkflowRepairGateSuite]]).
    */
  private lazy val pair: Pair =
    rt
    Vector("bq-v1-07", "bq-v1-08", "bq-v1-10", "bq-v1-11", "bq-v1-33").iterator
      .flatMap(id => BenchmarkQuestions.questions.find(_("id").str == id))
      .filter(_("window").str == Win)
      .flatMap { q =>
        val names = q("constraints")("researcherNames").arr.map(_.str).toVector
        val e = q("expected")
        val (a, b) = (e.obj.get("researcherA"), e.obj.get("researcherB")) match
          case (Some(x), Some(y)) => (x.str, y.str)
          case _                  =>
            val p = e("path").arr.map(_.str)
            (p.head, p.last)
        val req = BenchmarkRuntime.request(q)
        val (ev, _) = BenchmarkRuntime.observe(
          BenchmarkRuntime.deterministic().evidence.run(s"d5-probe-${q("id").str}", req)
        )
        for
          st <- ev.toOption
          mA <- metric(st.bundle, a)
          mB <- metric(st.bundle, b)
          cA <- num(mA, "distinctCoauthorCount")
          cB <- num(mB, "distinctCoauthorCount")
          if cA.compare(cB) != 0
        yield Pair(
          q("id").str,
          req,
          names(0),
          names(1),
          a,
          b,
          cA,
          cB,
          num(mA, "publicationCount"),
          num(mB, "publicationCount")
        )
      }
      .nextOption()
      .getOrElse(fail("no two-name question has both metric records with different counts"))

  private def metricIds(queryId: String, p: Pair): Vector[String] =
    val (ev, _) =
      BenchmarkRuntime.observe(BenchmarkRuntime.deterministic().evidence.run(queryId, p.req))
    val b = ev.fold(e => fail(e.describe), identity).bundle
    Vector(p.idA, p.idB).map(rid =>
      metric(b, rid).getOrElse(fail(s"no metric item for $rid under $queryId")).evidenceId
    )

  private def fmt(x: BigDecimal): String = x.bigDecimal.stripTrailingZeros.toPlainString

  private def runModel(queryId: String, p: Pair, replyText: String): WorkflowResult =
    val llm = ScriptedLlm.replies(replyText)
    val (r, _) = BenchmarkRuntime.observe(BenchmarkRuntime.withModel(llm).run(queryId, p.req))
    val res = r.fold(e => fail(s"workflow error: ${e.describe}"), identity)
    assertEquals(res.synthesis.fallback, None, "the model reply was not used")
    assertEquals(res.answer.promptVersion, "answer-synthesis-v1")
    res

  /** Removed as CONFLICTING or UNSUPPORTED, or kept only PARTIALLY_SUPPORTED with a label. */
  private def notVerified(res: WorkflowResult, claimId: String): Option[String] =
    val checks = res.audit.claimChecks.filter(_.claimId == claimId)
    res.answer.claims.find(_.claimId == claimId) match
      case None =>
        val removed = res.audit.removedClaims.find(_.claimId == claimId)
        Option.when(!removed.exists(r => Set("CONFLICTING", "UNSUPPORTED")(r.status.toString)))(
          s"$claimId neither kept nor removed as CONFLICTING/UNSUPPORTED; removed $removed"
        )
      case Some(c) =>
        Option.when(
          !(c.verificationStatus.toString == "PARTIALLY_SUPPORTED" &&
            (c.text.contains("[unverified]") || c.uncertainty.exists(_.nonEmpty)))
        )(
          s"$claimId kept as ${c.verificationStatus}: '${c.text}' (uncertainty ${c.uncertainty}); " +
            s"checks ${checks.map(k => (k.status, k.reasons))}"
        )

  private def control(p: Pair, both: Vector[String]) =
    ("claim-1", s"${p.nameB} has ${fmt(p.cB)} distinct coauthors in window $Win.", both)

  private def assertControl(res: WorkflowResult): Unit =
    assertEquals(
      res.answer.claims.find(_.claimId == "claim-1").map(_.verificationStatus.toString),
      Some("VERIFIED"),
      s"control claim-1: ${res.audit.claimChecks.filter(_.claimId == "claim-1")}"
    )

  test(
    "D5: a number before two names, or in comparative prose, is never VERIFIED for the wrong one"
  ) {
    val p = pair
    val both = metricIds("d5-claims", p)
    val (a, b, cA, cB) = (p.nameA, p.nameB, fmt(p.cA), fmt(p.cB))
    val pubs = (p.pA, p.pB) match
      case (Some(x), Some(y)) if x.compare(y) != 0 =>
        Vector(("claim-7", s"With ${fmt(y)} publications in window $Win, $a leads $b.", both))
      case _ => Vector.empty
    val claims = Vector(
      control(p, both),
      // the reviewer's variant: B's value, number first, A then B
      ("claim-2", s"With $cB distinct coauthors in window $Win, $a leads $b.", both),
      // mirrored: A's value, number first, B then A
      ("claim-3", s"With $cA distinct coauthors in window $Win, $b leads $a.", both),
      // number first, comparative parenthetical
      (
        "claim-4",
        s"With $cB distinct coauthors in window $Win, $a, compared with $b, leads.",
        both
      ),
      // comparative prose with the number after both names
      ("claim-5", s"$a, like $b, has $cB distinct coauthors in window $Win.", both),
      ("claim-6", s"$a (unlike $b) has $cB distinct coauthors in window $Win.", both)
    ) ++ pubs
    val res =
      runModel("d5-claims", p, reply(s"$b has $cB distinct coauthors in window $Win.", claims*))
    assertControl(res)
    val bad = (Vector("claim-2", "claim-3", "claim-4", "claim-5", "claim-6") ++ pubs.map(_._1))
      .flatMap(notVerified(res, _))
    assert(bad.isEmpty, s"pair ${p.qid} (A=${p.cA}, B=${p.cB}):" + bad.mkString("\n", "\n", "\n"))
  }

  test("D5: a model summary stating B's number before two names is replaced") {
    val p = pair
    val both = metricIds("d5-summary", p)
    val summary =
      s"With ${fmt(p.cB)} distinct coauthors in window $Win, ${p.nameA} leads ${p.nameB}."
    val res = runModel("d5-summary", p, reply(summary, control(p, both)))
    assertControl(res)
    assert(
      res.audit.summaryReplaced.isDefined,
      s"misattributed summary kept: '${res.answer.summary}' (pair ${p.qid})"
    )
  }
