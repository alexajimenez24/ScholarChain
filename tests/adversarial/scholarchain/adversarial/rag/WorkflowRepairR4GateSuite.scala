package scholarchain.adversarial.rag

import scholarchain.adversarial.rag.BenchmarkScoring.{num, states}
import scholarchain.core.*
import scholarchain.rag.*

/** Phase 09 repair round 4 gate tests (test-engineer, Phase 08b step 4) for R4-1, R4-2 and R4-3
  * (`docs/agent-runs/phase-09/review-report-04.md`). Rule under test: `answer-synthesis-v2` replies
  * checked by `claim-verifier-v3` (first written for the verifier version of the human work order
  * `docs/agent-runs/phase-08b/prompt.md` step 3, converted in the Phase 08b follow-up, where free
  * text is only ever an UNVERIFIED interpretation and each figure is its own structured claim;
  * scaladoc version string corrected in Phase 11, carried item C11): every number of a model claim
  * is a figure tuple (researcherId, metric, value, evidenceIds); the verifier checks the tuple
  * against the cited item that holds that metric for that researcher (a ranking: only for the
  * researcher at that position); the number shown is rendered from the evidence; any other number
  * in free model text is weakened and labeled. The class under test: a false per-researcher number
  * is never VERIFIED, whatever the prose around it says.
  *
  * Fixture: the same loaded run and pair selection as [[WorkflowRepairR3GateSuite]] (A and B are
  * two resolved researchers of one frozen two-name question with different distinct-coauthor
  * counts), plus the frozen ranking question bq-v1-01 for R4-3. Public API only
  * (`modules/rag/MODULE.md`, `repair-04-rag-engineer.md`,
  * `prompts/schemas/answer-synthesis-v2.json`).
  *
  * Spec (`docs/spec/assignment.md`): stage 9 "Check every researcher identity, ... coauthor count,
  * ... and numerical value against the bundle"; gate "Unsupported claims are removed, weakened, or
  * explicitly labeled".
  */
class WorkflowRepairR4GateSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(40, "min")

  private val Win = "2010-2014"
  private val Dcc = "distinctCoauthorCount"

  private def rt: LocalRuntime =
    assume(BenchmarkRuntime.present, s"run data absent at ${BenchmarkStore.runDir}")
    BenchmarkRuntime.runtime

  /** One figure tuple: researcherId, metric, value, evidenceIds. */
  private type Fig = (String, String, String, Vector[String])

  /** One draft claim: claimId, text, evidenceIds, figures. */
  private type Draft = (String, String, Vector[String], Vector[Fig])

  private def strs(xs: Vector[String]) = ujson.Arr.from(xs.map(ujson.Str(_)))

  private def reply(summary: String, claims: Draft*): String =
    ujson.write(
      ujson.Obj(
        "schemaVersion" -> "answer-synthesis-v2",
        "summary" -> summary,
        "abstain" -> false,
        "abstentionReason" -> ujson.Null,
        "claims" -> ujson.Arr.from(claims.map { (id, text, ev, figs) =>
          ujson.Obj(
            "claimId" -> id,
            "text" -> text,
            "evidenceIds" -> strs(ev),
            "uncertainty" -> ujson.Null,
            "figures" -> ujson.Arr.from(figs.map { (rid, m, v, fev) =>
              ujson.Obj(
                "researcherId" -> rid,
                "metric" -> m,
                "value" -> v,
                "evidenceIds" -> strs(fev)
              )
            })
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
      pA: Option[BigDecimal]
  )

  private def isMetric(x: EvidenceItem): Boolean =
    x.values.get("kind").contains("researcher-metrics") && x.values.get("window").contains(Win)

  private def metric(b: EvidenceBundle, rid: String): Option[EvidenceItem] =
    b.deterministic.find(x => isMetric(x) && x.researcherIds == Vector(rid))

  private def bundle(queryId: String, req: AnalysisRequest): EvidenceBundle =
    val (ev, _) =
      BenchmarkRuntime.observe(BenchmarkRuntime.deterministic().evidence.run(queryId, req))
    ev.fold(e => fail(e.describe), identity).bundle

  /** Same selection rule as [[WorkflowRepairR3GateSuite]]. */
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
          BenchmarkRuntime.deterministic().evidence.run(s"r4-probe-${q("id").str}", req)
        )
        for
          st <- ev.toOption
          mA <- metric(st.bundle, a)
          mB <- metric(st.bundle, b)
          cA <- num(mA, Dcc)
          cB <- num(mB, Dcc)
          if cA.compare(cB) != 0
        yield Pair(q("id").str, req, names(0), names(1), a, b, cA, cB, num(mA, "publicationCount"))
      }
      .nextOption()
      .getOrElse(fail("no two-name question has both metric records with different counts"))

  /** Evidence IDs of A's and B's metric records, and of a third researcher's when the bundle has
    * one.
    */
  private final case class Cited(mA: String, mB: String, all: Vector[String])

  private def cited(queryId: String, p: Pair): Cited =
    val b = bundle(queryId, p.req)
    def id(rid: String) =
      metric(b, rid).getOrElse(fail(s"no metric item for $rid under $queryId")).evidenceId
    val third = b.deterministic
      .find(x =>
        isMetric(x) && x.researcherIds.size == 1 && !Set(p.idA, p.idB)(x.researcherIds.head)
      )
      .map(_.evidenceId)
    Cited(id(p.idA), id(p.idB), Vector(id(p.idA), id(p.idB)) ++ third.toVector)

  private def fmt(x: BigDecimal): String = x.bigDecimal.stripTrailingZeros.toPlainString

  private def runModel(queryId: String, req: AnalysisRequest, replyText: String): WorkflowResult =
    val llm = ScriptedLlm.replies(replyText)
    val (r, _) = BenchmarkRuntime.observe(BenchmarkRuntime.withModel(llm).run(queryId, req))
    val res = r.fold(e => fail(s"workflow error: ${e.describe}"), identity)
    assertEquals(res.synthesis.fallback, None, "the model reply was not used")
    assertEquals(res.answer.promptVersion, "answer-synthesis-v2")
    res

  private def trace(res: WorkflowResult, id: String): String =
    s"checks ${res.audit.claimChecks.filter(_.claimId == id).map(k => (k.status, k.reasons))}; " +
      s"removed ${res.audit.removedClaims.filter(_.claimId == id).map(r => (r.status, r.text))}"

  // Converted for claim-verifier-v3 (Phase 08b follow-up task 2): a model claim `claim-N` is never
  // VERIFIED (kept, it is labeled UNVERIFIED interpretation); its figure K is checked as the
  // structured claim `claim-N-figure-K` ([[ModelV2]]).

  /** None when free text `id` is labeled or removed, and its figure (if any) is not VERIFIED. */
  private def notVerified(res: WorkflowResult, id: String, figures: Int = 1): Option[String] =
    val bad = ModelV2.interpretation(res, id).toVector ++
      (1 to figures).flatMap(k => ModelV2.figureNotVerified(res, id, k))
    Option.when(bad.nonEmpty)(bad.mkString("; "))

  /** None when figure 1 of `id` is VERIFIED for `rid`, shows `value`, and the prose is labeled. */
  private def verified(
      res: WorkflowResult,
      id: String,
      rid: String,
      value: BigDecimal
  ): Option[String] =
    val bad = ModelV2.figureVerified(res, id, 1, rid, value).toVector ++
      ModelV2.interpretation(res, id, mayRemove = false)
    Option.when(bad.nonEmpty)(s"control $id: " + bad.mkString("; "))

  /** Some when kept claim `id` shows `value` (a rejected figure value) in its prose. */
  private def shows(res: WorkflowResult, id: String, value: BigDecimal): Option[String] =
    ModelV2
      .claim(res, id)
      .filter(c => states(c.text, value))
      .map(c => s"$id shows ${fmt(value)}: '${c.text}'")

  private def report(ctx: String, bad: Vector[String]): Unit =
    assert(bad.isEmpty, ctx + bad.mkString("\n", "\n", "\n"))

  private def ctx(p: Pair) = s"pair ${p.qid} (A=${p.idA} ${p.cA}, B=${p.idB} ${p.cB}):"

  private def controls(p: Pair, c: Cited): Vector[Draft] = Vector(
    (
      "claim-1",
      s"${p.nameA} has {figure-1} distinct coauthors in window $Win.",
      c.all,
      Vector((p.idA, Dcc, fmt(p.cA), Vector(c.mA)))
    ),
    (
      "claim-2",
      s"${p.nameB} has {figure-1} distinct coauthors in window $Win.",
      c.all,
      Vector((p.idB, Dcc, fmt(p.cB), Vector(c.mB)))
    )
  )

  private def controlsOk(res: WorkflowResult, p: Pair): Vector[String] =
    verified(res, "claim-1", p.idA, p.cA).toVector ++ verified(res, "claim-2", p.idB, p.cB)

  /** The R4-1 (partial, lower-case, NBSP names) and R4-2 (noun phrases, possessive, "dir.")
    * phrasings of review-report-04.md, about A, with B named, the number at `n`.
    */
  private def phrasings(p: Pair, n: String): Vector[String] =
    val (a, b) = (p.nameA, p.nameB)
    val parts = a.split(' ')
    val (first, sur) = (parts.head, parts.last)
    Vector(
      s"Unlike $b, $sur has $n distinct coauthors in window $Win.",
      s"Unlike $b, $first has $n distinct coauthors in window $Win.",
      s"$sur, unlike $b, has $n distinct coauthors in window $Win.",
      s"Unlike $b, ${a.toLowerCase} has $n distinct coauthors in window $Win.",
      s"Unlike $b, ${a.replace(' ', ' ')} has $n distinct coauthors in window $Win.",
      s"$a leads the ranking. Unlike $b, the leader has $n distinct coauthors in window $Win.",
      s"$a stands out. Unlike $b, this researcher has $n distinct coauthors in window $Win.",
      s"$a and $b were compared. Unlike $b, the former has $n distinct coauthors in window $Win.",
      s"$a's closest coauthor has $n distinct coauthors in window $Win.",
      s"$a, like the lab of dir. $b, has $n distinct coauthors in window $Win.",
      s"$a is ranked with $n."
    )

  test(
    "control: correct figure tuples for A and B are VERIFIED and a number-free summary is kept, labeled"
  ) {
    val p = pair
    val c = cited("r4-control", p)
    val summary = s"${p.nameA} and ${p.nameB} differ in collaboration breadth."
    val res = runModel("r4-control", p.req, reply(summary, controls(p, c)*))
    report(
      ctx(p),
      controlsOk(res, p) ++
        // v3: kept means not replaced and shown with its text, labeled as interpretation
        Option.when(
          res.audit.summaryReplaced.isDefined || !res.answer.summary.contains(summary) ||
            !res.answer.summary.contains(ModelV2.Label)
        )(
          s"number-free summary not kept labeled: ${res.audit.summaryReplaced}; '${res.answer.summary}'"
        ) ++ ModelV2.notVerifiedOverall(res)
    )
  }

  test("R4-1/R4-2: B's number in a tuple for A is never VERIFIED in eleven phrasings") {
    val p = pair
    val c = cited("r4-false-a", p)
    val forms = phrasings(p, "{figure-1}")
    // own record cited, and B's record (which holds the stated value) cited
    val own = forms.zipWithIndex.map((t, i) =>
      (s"claim-${11 + i}", t, c.all, Vector((p.idA, Dcc, fmt(p.cB), Vector(c.mA))))
    )
    val other = forms.zipWithIndex.map((t, i) =>
      (s"claim-${31 + i}", t, c.all, Vector((p.idA, Dcc, fmt(p.cB), Vector(c.mB))))
    )
    val res = runModel("r4-false-a", p.req, reply("A comparison.", controls(p, c) ++ own ++ other*))
    report(
      ctx(p),
      controlsOk(res, p) ++ (own ++ other).flatMap(d => notVerified(res, d._1)) ++
        (own ++ other).flatMap(d => shows(res, d._1, p.cB)) ++
        ModelV2.noVerifiedFalse(res, p.idA, p.cB) ++ ModelV2.notVerifiedOverall(res)
    )
  }

  test("R4-1/R4-2 reversed: A's number in a tuple for B is never VERIFIED in eleven phrasings") {
    val p = pair
    val c = cited("r4-false-b", p)
    val drafts = phrasings(p, "{figure-1}").zipWithIndex.map((t, i) =>
      (s"claim-${11 + i}", t, c.all, Vector((p.idB, Dcc, fmt(p.cA), Vector(c.mA, c.mB))))
    )
    val res = runModel("r4-false-b", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(
      ctx(p),
      controlsOk(res, p) ++ drafts.flatMap(d => notVerified(res, d._1)) ++
        drafts.flatMap(d => shows(res, d._1, p.cA)) ++ ModelV2.noVerifiedFalse(res, p.idB, p.cA)
    )
  }

  // The pin of review 5b (V5b-2): phrasing 6 says "leads the ranking" in prose. Under v3 that prose
  // is an UNVERIFIED interpretation (asserted here); only the distinct-coauthor figure is VERIFIED.
  // The structured "leads the ranking" (a rank figure) is pinned in the R4-3 test below.
  test("R4-1/R4-2: a true tuple for A is VERIFIED and shown with A's value in every phrasing") {
    val p = pair
    val c = cited("r4-true-a", p)
    val drafts = phrasings(p, "{figure-1}").zipWithIndex.map((t, i) =>
      (s"claim-${11 + i}", t, c.all, Vector((p.idA, Dcc, fmt(p.cA), Vector(c.mA))))
    )
    val res = runModel("r4-true-a", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(
      ctx(p),
      controlsOk(res, p) ++ drafts.flatMap(d => verified(res, d._1, p.idA, p.cA)) ++
        drafts.flatMap(d => shows(res, d._1, p.cB) ++ shows(res, ModelV2.figureId(d._1, 1), p.cB))
    )
  }

  test(
    "R4-1/R4-2 free text: a literal number with no figure is never VERIFIED, kept only labeled"
  ) {
    val p = pair
    val c = cited("r4-free", p)
    val drafts =
      (phrasings(p, fmt(p.cB)) ++ phrasings(p, fmt(p.cA))).zipWithIndex.map((t, i) =>
        (s"claim-${11 + i}", t, c.all, Vector.empty[Fig])
      )
    val res = runModel("r4-free", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(
      ctx(p),
      controlsOk(res, p) ++ drafts.flatMap(d => notVerified(res, d._1, figures = 0)) ++
        ModelV2.noVerifiedFalse(res, p.idA, p.cB)
    )
  }

  test("R4-1/R4-2 wrong metric or unknown researcher: the tuple is never VERIFIED") {
    val p = pair
    val c = cited("r4-shape", p)
    val t = s"${p.nameA} has {figure-1} in window $Win."
    val wrongMetric = p.pA
      .filter(_.compare(p.cA) != 0)
      .map(_ =>
        ("claim-11", t, c.all, Vector((p.idA, "publicationCount", fmt(p.cA), Vector(c.mA))))
      )
    val drafts = wrongMetric.toVector ++ Vector(
      ("claim-12", t, c.all, Vector(("ra:not-a-researcher", Dcc, fmt(p.cA), Vector(c.mA)))),
      ("claim-13", t, c.all, Vector((p.idA, "coauthorRank", fmt(p.cA), Vector(c.mA))))
    )
    val res = runModel("r4-shape", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(ctx(p), controlsOk(res, p) ++ drafts.flatMap(d => notVerified(res, d._1)))
  }

  test(
    "R4-1 summary: a model summary stating B's number for A in a partial-name form is replaced or labeled"
  ) {
    val p = pair
    val forms = phrasings(p, fmt(p.cB))
    val bad = Vector(0, 5).flatMap { i =>
      val qid = s"r4-summary-${i + 1}"
      val res = runModel(qid, p.req, reply(forms(i), controls(p, cited(qid, p))*))
      // v3: a model summary is labeled UNVERIFIED interpretation, or replaced; a replacement
      // must not keep B's number, a labeled summary may (it is shown as interpretation)
      val labeled = res.answer.summary.contains(ModelV2.Label)
      controlsOk(res, p) ++
        ModelV2.summaryLabeledOrReplaced(res, forms(i)).map(x => s"form ${i + 1}: $x") ++
        Option.when(!labeled && states(res.answer.summary, p.cB))(
          s"form ${i + 1}: B's number still in the unlabeled summary: '${res.answer.summary}'"
        )
    }
    report(ctx(p), bad)
  }

  /** The ranking question bq-v1-01, its ranking item, the top position and the first position whose
    * value differs from the top's.
    */
  private final case class Ranking(
      req: AnalysisRequest,
      itemId: String,
      metricField: String,
      top: (Int, String, BigDecimal),
      other: (Int, String, BigDecimal)
  )

  private lazy val ranking: Ranking =
    rt
    val q = BenchmarkQuestions.questions
      .find(_("id").str == "bq-v1-01")
      .getOrElse(fail("bq-v1-01 missing"))
    val req = BenchmarkRuntime.request(q)
    val field = q("expected")("metric").str
    val item = bundle("r4-ranking-probe", req).deterministic
      .find(x =>
        x.values.get("kind").contains("ranking") && x.values.get("metricField").contains(field)
      )
      .getOrElse(fail(s"no ranking item on $field for bq-v1-01"))
    val pos = item.values
      .getOrElse("positions", "")
      .split(";")
      .toVector
      .filter(_.contains("="))
      .map { s =>
        val Array(rank, rest) = s.split(":", 2)
        val k = rest.lastIndexOf('=')
        (rank.trim.toInt, rest.take(k), BigDecimal(rest.drop(k + 1)))
      }
    val top = pos.headOption.getOrElse(fail(s"empty ranking positions: ${item.values}"))
    val other = pos
      .find(_._3.compare(top._3) != 0)
      .getOrElse(fail(s"every ranking position ties the top: ${item.values}"))
    Ranking(req, item.evidenceId, field, top, other)

  test("R4-3: a ranking backs a number or rank only for the researcher at that position") {
    val r = ranking
    val (tRank, tId, tVal) = r.top
    val (oRank, oId, oVal) = r.other
    val ev = Vector(r.itemId)
    val text = "The researcher is ranked with {figure-1}."
    val rankText = "The researcher holds position {figure-1} in the ranking."
    def d(id: Int, t: String, f: Fig): Draft = (s"claim-$id", t, ev, Vector(f))
    val leads = "The researcher leads the ranking at position {figure-1}."
    val good = Vector(
      d(1, text, (tId, r.metricField, fmt(tVal), ev)) -> (tId, tVal),
      d(2, text, (oId, r.metricField, fmt(oVal), ev)) -> (oId, oVal),
      d(3, rankText, (tId, "rank", tRank.toString, ev)) -> (tId, BigDecimal(tRank)),
      // "leads the ranking" in the structured form (V5b-2 pin, converted): a rank figure
      d(4, leads, (tId, "rank", tRank.toString, ev)) -> (tId, BigDecimal(tRank))
    )
    val bad = Vector(
      d(11, text, (tId, r.metricField, fmt(oVal), ev)), // the reviewer's "is ranked with 30"
      d(12, text, (oId, r.metricField, fmt(tVal), ev)),
      d(13, rankText, (oId, "rank", tRank.toString, ev)),
      d(14, rankText, (tId, "rank", oRank.toString, ev)),
      d(15, text, ("ra:not-a-researcher", r.metricField, fmt(tVal), ev)),
      // "leads the ranking" structured for a researcher who is not at the top
      d(16, leads, (oId, "rank", tRank.toString, ev))
    )
    // the free-text counterparts: "leads the ranking" in prose, true or false, is only labeled
    val free = Vector(
      (s"claim-21", s"$tId leads the ranking.", ev, Vector.empty[Fig]),
      (s"claim-22", s"$oId leads the ranking.", ev, Vector.empty[Fig])
    )
    val res =
      runModel("r4-ranking", r.req, reply("The ranking.", good.map(_._1) ++ bad ++ free*))
    report(
      s"ranking ${r.itemId} top=${r.top} other=${r.other}:",
      good.flatMap { case (x, (rid, v)) => verified(res, x._1, rid, v) } ++
        bad.flatMap(x => notVerified(res, x._1)) ++
        free.flatMap(x => notVerified(res, x._1, figures = 0)) ++ ModelV2.notVerifiedOverall(res)
    )
  }
