package scholarchain.adversarial.rag

import scholarchain.adversarial.rag.BenchmarkScoring.num
import scholarchain.core.*
import scholarchain.rag.*

/** Phase 09 repair round 3 gate tests (test-engineer) for R3-1, R3-2 and R3-3
  * (`docs/agent-runs/phase-09/review-report-03.md`). Rule under test (human decision, run-log.md):
  * a per-researcher numerical claim may be VERIFIED only when its sentence names exactly one
  * researcher and has no pronoun subject; every other numerical claim about researchers is weakened
  * and labeled, never CONFLICTING.
  *
  * Fixture: the same loaded run and pair selection as [[WorkflowRepairD5GateSuite]]. A and B are
  * two resolved researchers of one frozen two-name question with different distinct-coauthor counts
  * (the reviewer's Alice 41 and Bob 30); every model claim cites the metric records of A and B and,
  * when the bundle holds one, of a third researcher (the reviewer's Carol). Public API only
  * (`modules/rag/MODULE.md`).
  *
  * Spec (`docs/spec/assignment.md`): stage 9 "Check every researcher identity, ... coauthor count,
  * ... and numerical value against the bundle"; gate "Unsupported claims are removed, weakened, or
  * explicitly labeled".
  */
class WorkflowRepairR3GateSuite extends munit.FunSuite:

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
      pA: Option[BigDecimal]
  )

  private def isMetric(x: EvidenceItem): Boolean =
    x.values.get("kind").contains("researcher-metrics") && x.values.get("window").contains(Win)

  private def metric(b: EvidenceBundle, rid: String): Option[EvidenceItem] =
    b.deterministic.find(x => isMetric(x) && x.researcherIds == Vector(rid))

  /** Same selection rule as [[WorkflowRepairD5GateSuite]]. */
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
          BenchmarkRuntime.deterministic().evidence.run(s"r3-probe-${q("id").str}", req)
        )
        for
          st <- ev.toOption
          mA <- metric(st.bundle, a)
          mB <- metric(st.bundle, b)
          cA <- num(mA, "distinctCoauthorCount")
          cB <- num(mB, "distinctCoauthorCount")
          if cA.compare(cB) != 0
        yield Pair(q("id").str, req, names(0), names(1), a, b, cA, cB, num(mA, "publicationCount"))
      }
      .nextOption()
      .getOrElse(fail("no two-name question has both metric records with different counts"))

  /** The metric records of A and B, plus a third researcher's metric record when the bundle has
    * one.
    */
  private def cited(queryId: String, p: Pair): Vector[String] =
    val (ev, _) =
      BenchmarkRuntime.observe(BenchmarkRuntime.deterministic().evidence.run(queryId, p.req))
    val b = ev.fold(e => fail(e.describe), identity).bundle
    val ab = Vector(p.idA, p.idB).map(rid =>
      metric(b, rid).getOrElse(fail(s"no metric item for $rid under $queryId")).evidenceId
    )
    val third = b.deterministic
      .find(x =>
        isMetric(x) && x.researcherIds.size == 1 && !Set(p.idA, p.idB)(x.researcherIds.head)
      )
      .map(_.evidenceId)
    ab ++ third.toVector

  private def fmt(x: BigDecimal): String = x.bigDecimal.stripTrailingZeros.toPlainString

  private def runModel(queryId: String, p: Pair, replyText: String): WorkflowResult =
    val llm = ScriptedLlm.replies(replyText)
    val (r, _) = BenchmarkRuntime.observe(BenchmarkRuntime.withModel(llm).run(queryId, p.req))
    val res = r.fold(e => fail(s"workflow error: ${e.describe}"), identity)
    assertEquals(res.synthesis.fallback, None, "the model reply was not used")
    assertEquals(res.answer.promptVersion, "answer-synthesis-v1")
    res

  /** The seven R3-1 phrasings (review-report-03.md, inputs 1 to 7), with `n` stated for A while B
    * is named in the same sentence.
    */
  private def phrasings(p: Pair, n: String): Vector[String] =
    val (a, b) = (p.nameA, p.nameB)
    val pubs = p.pA.fold(s"$a publishes widely")(x => s"$a has ${fmt(x)} publications")
    Vector(
      s"$a (vs. $b) has $n distinct coauthors in window $Win.",
      s"$a, compared with Prof. $b, has $n distinct coauthors in window $Win.",
      s"$a, like Dr. $b, has $n distinct coauthors.",
      s"$a leads. Unlike $b, she has $n distinct coauthors in window $Win.",
      s"$a leads; compared with $b, she has $n distinct coauthors.",
      s"$a stands out: compared with $b, she has $n distinct coauthors.",
      s"$pubs, while compared with $b she has $n distinct coauthors."
    )

  /** None when the claim is kept PARTIALLY_SUPPORTED with a label and was never judged CONFLICTING;
    * else the reason.
    */
  private def weakened(res: WorkflowResult, claimId: String): Option[String] =
    val checks = res.audit.claimChecks.filter(_.claimId == claimId)
    val trace = s"checks ${checks.map(k => (k.status, k.reasons))}"
    res.answer.claims.find(_.claimId == claimId) match
      case None =>
        Some(s"$claimId removed: ${res.audit.removedClaims.find(_.claimId == claimId)}; $trace")
      case Some(c) =>
        val labeled = c.text.contains("[unverified]") || c.uncertainty.exists(_.nonEmpty)
        Option.when(
          c.verificationStatus.toString != "PARTIALLY_SUPPORTED" || !labeled ||
            checks.exists(_.status.toString == "CONFLICTING")
        )(
          s"$claimId kept as ${c.verificationStatus}: '${c.text}' (uncertainty ${c.uncertainty}); " +
            trace
        )

  private def verified(res: WorkflowResult, claimId: String): Option[String] =
    Option.when(
      !res.answer.claims
        .find(_.claimId == claimId)
        .exists(_.verificationStatus.toString == "VERIFIED")
    )(s"control $claimId not VERIFIED: ${res.audit.claimChecks.filter(_.claimId == claimId)}")

  private def noConflict(res: WorkflowResult): Option[String] =
    val removed = res.audit.removedClaims.filter(_.status.toString == "CONFLICTING")
    Option.when(removed.nonEmpty || res.answer.verification.toString == "CONFLICTING")(
      s"answer ${res.answer.verification}; CONFLICTING removals ${removed
          .map(r => (r.claimId, r.text, r.reasons))}"
    )

  private def controls(p: Pair, ev: Vector[String]) = Vector(
    ("claim-1", s"${p.nameA} has ${fmt(p.cA)} distinct coauthors in window $Win.", ev),
    ("claim-2", s"${p.nameB} has ${fmt(p.cB)} distinct coauthors in window $Win.", ev)
  )

  private def report(p: Pair, bad: Vector[String]): Unit =
    assert(bad.isEmpty, s"pair ${p.qid} (A=${p.cA}, B=${p.cB}):" + bad.mkString("\n", "\n", "\n"))

  test(
    "R3-1: B's number stated for A next to B in seven phrasings is weakened, labeled, never CONFLICTING"
  ) {
    val p = pair
    val ev = cited("r3-false", p)
    val claims = controls(p, ev) ++
      phrasings(p, fmt(p.cB)).zipWithIndex.map((t, i) => (s"claim-${11 + i}", t, ev))
    val res =
      runModel("r3-false", p, reply(s"${p.nameB} has ${fmt(p.cB)} distinct coauthors.", claims*))
    report(
      p,
      Vector("claim-1", "claim-2").flatMap(verified(res, _)) ++
        (11 to 17).map(i => s"claim-$i").flatMap(weakened(res, _)) ++ noConflict(res)
    )
  }

  test("R3-1: the same seven phrasings with A's true number are weakened, never CONFLICTING") {
    val p = pair
    val ev = cited("r3-true", p)
    val claims = controls(p, ev) ++
      phrasings(p, fmt(p.cA)).zipWithIndex.map((t, i) => (s"claim-${21 + i}", t, ev))
    val res =
      runModel("r3-true", p, reply(s"${p.nameB} has ${fmt(p.cB)} distinct coauthors.", claims*))
    report(
      p,
      Vector("claim-1", "claim-2").flatMap(verified(res, _)) ++
        (21 to 27).map(i => s"claim-$i").flatMap(weakened(res, _)) ++ noConflict(res)
    )
  }

  test("R3-1 summary path: a model summary in form 1 or form 4 with B's number for A is replaced") {
    val p = pair
    val forms = phrasings(p, fmt(p.cB))
    val bad = Vector(0, 3).flatMap { i =>
      val qid = s"r3-summary-${i + 1}"
      val ev = cited(qid, p)
      val res = runModel(qid, p, reply(forms(i), controls(p, ev)*))
      Vector("claim-1", "claim-2").flatMap(verified(res, _)) ++
        Option.when(res.audit.summaryReplaced.isEmpty || res.answer.summary == forms(i))(
          s"input ${i + 1}: misattributed summary kept: '${res.answer.summary}'"
        )
    }
    report(p, bad)
  }

  test("R3-2: A's number stated for B after 'like A' is weakened, labeled, never CONFLICTING") {
    val p = pair
    val ev = cited("r3-2", p)
    val (a, b) = (p.nameA, p.nameB)
    val claims = controls(p, ev) ++ Vector(
      // the reviewer's form: the subject is B, the number is A's
      (
        "claim-31",
        s"$a collaborates with $b, who, like $a, has ${fmt(p.cA)} distinct coauthors.",
        ev
      ),
      // the same form with B's true number
      (
        "claim-32",
        s"$a collaborates with $b, who, like $a, has ${fmt(p.cB)} distinct coauthors.",
        ev
      )
    )
    val res = runModel("r3-2", p, reply(s"$b has ${fmt(p.cB)} distinct coauthors.", claims*))
    report(
      p,
      Vector("claim-1", "claim-2").flatMap(verified(res, _)) ++
        Vector("claim-31", "claim-32").flatMap(weakened(res, _)) ++ noConflict(res)
    )
  }

  test("R3-3: a true pronoun claim next to another name is weakened, not CONFLICTING or removed") {
    val p = pair
    val ev = cited("r3-3", p)
    val text =
      s"${p.nameA} leads. Unlike ${p.nameB}, she has ${fmt(p.cA)} distinct coauthors in window $Win."
    val claims = controls(p, ev) :+ ("claim-41", text, ev)
    val res =
      runModel("r3-3", p, reply(s"${p.nameA} has ${fmt(p.cA)} distinct coauthors.", claims*))
    report(
      p,
      Vector("claim-1", "claim-2").flatMap(verified(res, _)) ++ weakened(res, "claim-41") ++
        noConflict(res)
    )
  }

  test(
    "control: one researcher, no pronoun, correct value stays VERIFIED and its summary is kept"
  ) {
    val p = pair
    val ev = cited("r3-control", p)
    val summary = s"${p.nameA} has ${fmt(p.cA)} distinct coauthors in window $Win."
    val res = runModel("r3-control", p, reply(summary, controls(p, ev)*))
    report(
      p,
      Vector("claim-1", "claim-2").flatMap(verified(res, _)) ++
        Option.when(res.audit.summaryReplaced.isDefined || res.answer.summary != summary)(
          s"correct one-researcher summary replaced: ${res.audit.summaryReplaced}; '${res.answer.summary}'"
        ) ++ noConflict(res)
    )
  }
