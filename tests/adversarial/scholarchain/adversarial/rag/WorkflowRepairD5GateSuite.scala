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

  // answer-synthesis-v2 replies, claim-verifier-v3 checks ([[ModelV2]]).
  import ModelV2.{Draft, figureNotVerified, figureVerified, interpretation, noVerifiedFalse, reply}

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

  private val Dcc = "distinctCoauthorCount"

  private def runModel(queryId: String, p: Pair, replyText: String): WorkflowResult =
    val llm = ScriptedLlm.replies(replyText)
    val (r, _) = BenchmarkRuntime.observe(BenchmarkRuntime.withModel(llm).run(queryId, p.req))
    val res = r.fold(e => fail(s"workflow error: ${e.describe}"), identity)
    assertEquals(res.synthesis.fallback, None, "the model reply was not used")
    assertEquals(res.answer.promptVersion, "answer-synthesis-v2")
    res

  /** The control: B's own value as a figure citing B's record; the claim cites both records. */
  private def control(p: Pair, both: Vector[String]) =
    Draft(
      "claim-1",
      s"${p.nameB} has {figure-1} distinct coauthors in window $Win.",
      both,
      Vector((p.idB, Dcc, fmt(p.cB), Vector(both(1))))
    )

  private def controlOk(res: WorkflowResult, p: Pair): Vector[String] =
    (figureVerified(res, "claim-1", 1, p.idB, p.cB) ++ interpretation(
      res,
      "claim-1",
      false
    )).toVector

  private def report(p: Pair, bad: Vector[String]): Unit =
    assert(bad.isEmpty, s"pair ${p.qid} (A=${p.cA}, B=${p.cB}):" + bad.mkString("\n", "\n", "\n"))

  /** The D5 sentences (review-report-02.md) with `n(v)` in place of each stated value `v`: a number
    * before two names and comparative prose. Each entry: claim number, text, (subject ID, metric,
    * value) of the stated number.
    */
  private def sentences(
      p: Pair,
      n: String => String
  ): Vector[(Int, String, (String, String, String))] =
    val (a, b, cA, cB) = (p.nameA, p.nameB, fmt(p.cA), fmt(p.cB))
    val pubs = (p.pA, p.pB) match
      case (Some(x), Some(y)) if x.compare(y) != 0 =>
        Vector(
          (
            7,
            s"With ${n(fmt(y))} publications in window $Win, $a leads $b.",
            (p.idA, "publicationCount", fmt(y))
          )
        )
      case _ => Vector.empty
    Vector(
      // the reviewer's variant: B's value, number first, A then B
      (2, s"With ${n(cB)} distinct coauthors in window $Win, $a leads $b.", (p.idA, Dcc, cB)),
      // mirrored: A's value, number first, B then A
      (3, s"With ${n(cA)} distinct coauthors in window $Win, $b leads $a.", (p.idB, Dcc, cA)),
      // number first, comparative parenthetical
      (
        4,
        s"With ${n(cB)} distinct coauthors in window $Win, $a, compared with $b, leads.",
        (p.idA, Dcc, cB)
      ),
      // comparative prose with the number after both names
      (5, s"$a, like $b, has ${n(cB)} distinct coauthors in window $Win.", (p.idA, Dcc, cB)),
      (6, s"$a (unlike $b) has ${n(cB)} distinct coauthors in window $Win.", (p.idA, Dcc, cB))
    ) ++ pubs

  test(
    "D5: a number before two names, or in comparative prose, is never VERIFIED for the wrong one"
  ) {
    val p = pair
    val both = metricIds("d5-claims", p)
    val structured = sentences(p, _ => "{figure-1}").map { (i, t, f) =>
      Draft(s"claim-$i", t, both, Vector((f._1, f._2, f._3, both)))
    }
    // the same sentences with the numbers in free text, no figure
    val free = sentences(p, identity).map((i, t, _) => Draft(s"claim-${10 + i}", t, both))
    val res = runModel(
      "d5-claims",
      p,
      reply(s"${p.nameB} is compared with ${p.nameA}.", (control(p, both) +: structured) ++ free*)
    )
    report(
      p,
      controlOk(res, p) ++ structured.flatMap(d => figureNotVerified(res, d.id, 1)) ++
        (structured ++ free).flatMap(d => interpretation(res, d.id)) ++
        noVerifiedFalse(res, p.idA, p.cB) ++ noVerifiedFalse(res, p.idB, p.cA) ++
        ModelV2.notVerifiedOverall(res)
    )
  }

  test("D5: a model summary stating B's number before two names is replaced or labeled") {
    val p = pair
    val both = metricIds("d5-summary", p)
    val summary =
      s"With ${fmt(p.cB)} distinct coauthors in window $Win, ${p.nameA} leads ${p.nameB}."
    val res = runModel("d5-summary", p, reply(summary, control(p, both)))
    report(p, controlOk(res, p) ++ ModelV2.summaryLabeledOrReplaced(res, summary))
  }
