package scholarchain.adversarial.rag

import scala.collection.mutable

import scholarchain.adversarial.rag.BenchmarkScoring.{kind, num, numbers}
import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.GraphQuery

/** Phase 09 repair round 1 gate tests (test-engineer) for the two review findings no earlier test
  * covered: M5 (a number misattributed across two cited records) and M6 (a question without years
  * on the loaded window subset). Public API only (`modules/rag/MODULE.md`); same loaded run as
  * [[WorkflowGateSuite]] (windows 2010-2014 and 2015-2026).
  *
  * Spec (`docs/spec/assignment.md`): stage 9 "Check every researcher identity, ... coauthor count,
  * ... time window, and numerical value against the bundle"; gate "Every numerical and path claim
  * has evidence IDs" and "Unsupported claims are removed, weakened, or explicitly labeled"; stage 8
  * "State uncertainty and missing evidence".
  */
class WorkflowRepairGateSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(40, "min")

  private def rt: LocalRuntime =
    assume(BenchmarkRuntime.present, s"run data absent at ${BenchmarkStore.runDir}")
    BenchmarkRuntime.runtime

  private val seen = mutable.ArrayBuffer.empty[WorkflowResult]

  private def run(
      id: String,
      req: AnalysisRequest,
      wf: => CollaborationWorkflow = BenchmarkRuntime.deterministic()
  ): (Either[WorkflowError, WorkflowResult], Vector[GraphQuery]) =
    rt
    val (r, qs) = BenchmarkRuntime.observe(wf.run(id, req))
    r.foreach(seen += _)
    (r, qs)

  private def ok(r: Either[WorkflowError, WorkflowResult]): WorkflowResult =
    r.fold(e => fail(s"workflow error: ${e.describe}"), identity)

  // answer-synthesis-v2 replies, claim-verifier-v3 checks ([[ModelV2]]).
  import ModelV2.{Draft, figureNotVerified, figureVerified, interpretation, noVerifiedFalse, reply}

  private def question(id: String): ujson.Value =
    BenchmarkQuestions.questions.find(_("id").str == id).getOrElse(fail(s"no question $id"))

  // M5. A number is checked against the record of the researcher it is stated for ---------------

  private val Win = "2010-2014"

  /** Two resolved researchers of one frozen two-name question, with their own metric records in the
    * bundle and different distinct-coauthor counts.
    */
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

  private lazy val pair: Pair =
    rt
    val tried = mutable.ArrayBuffer.empty[String]
    val found = Vector("bq-v1-07", "bq-v1-08", "bq-v1-10", "bq-v1-11", "bq-v1-33").iterator
      .map(question)
      .filter(_("window").str == Win)
      .flatMap { q =>
        val names = q("constraints")("researcherNames").arr.map(_.str).toVector
        val e = q("expected")
        val ids = (e.obj.get("researcherA"), e.obj.get("researcherB")) match
          case (Some(a), Some(b)) => Some((a.str, b.str))
          case _                  =>
            val p = e("path").arr.map(_.str)
            Some((p.head, p.last))
        val req = BenchmarkRuntime.request(q)
        val (ev, _) = BenchmarkRuntime.observe(
          BenchmarkRuntime.deterministic().evidence.run(s"m5-probe-${q("id").str}", req)
        )
        val out = for
          (a, b) <- ids
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
        if out.isEmpty then
          tried += s"${q("id").str}: ${ev.fold(_.describe, _ => "no usable pair")}"
        out
      }
      .nextOption()
    found.getOrElse(
      fail(
        "no two-name question has both researchers' metric records with different counts: " +
          tried.mkString("; ")
      )
    )

  private def metric(b: EvidenceBundle, rid: String): Option[EvidenceItem] =
    b.deterministic.find(x =>
      x.values.get("kind").contains("researcher-metrics") && x.researcherIds == Vector(rid) &&
        x.values.get("window").contains(Win)
    )

  /** The two metric evidence IDs stages 1 to 7 produce for the pair question under `queryId`
    * (evidence IDs are read under the same query ID the workflow then runs, as in
    * [[WorkflowGateSuite]]).
    */
  private def metricIds(queryId: String, p: Pair): Vector[String] =
    val (ev, _) =
      BenchmarkRuntime.observe(BenchmarkRuntime.deterministic().evidence.run(queryId, p.req))
    val b = ev.fold(e => fail(e.describe), identity).bundle
    Vector(p.idA, p.idB).map(rid =>
      metric(b, rid).getOrElse(fail(s"no metric item for $rid under $queryId")).evidenceId
    )

  private def fmt(x: BigDecimal): String = x.bigDecimal.stripTrailingZeros.toPlainString

  private def report(p: Pair, bad: Iterable[String]): Unit =
    assert(
      bad.isEmpty,
      s"pair ${p.qid} (A=${p.idA} ${p.cA}, B=${p.idB} ${p.cB}):" + bad.mkString("\n", "\n", "\n")
    )

  private val Dcc = "distinctCoauthorCount"

  /** The control: B's own value for B as a figure citing B's record, the claim citing both. */
  private def control(p: Pair, both: Vector[String]) =
    Draft(
      "claim-1",
      s"${p.nameB} has {figure-1} distinct coauthors in window $Win.",
      both,
      Vector((p.idB, Dcc, fmt(p.cB), Vector(both(1))))
    )

  private def controlOk(res: WorkflowResult, p: Pair): Iterable[String] =
    figureVerified(res, "claim-1", 1, p.idB, p.cB) ++ interpretation(res, "claim-1", false)

  private def modelRun(qid: String, p: Pair, replyText: String): WorkflowResult =
    val llm = ScriptedLlm.replies(replyText)
    val res = ok(run(qid, p.req, BenchmarkRuntime.withModel(llm))._1)
    assertEquals(res.synthesis.fallback, None, "the model reply was not used")
    assertEquals(res.answer.promptVersion, "answer-synthesis-v2")
    res

  test("M5: a number of one cited researcher stated for the other is never VERIFIED") {
    val p = pair
    val both = metricIds("m5-misattributed", p)
    val (mA, mB) = (both(0), both(1))
    val (a, b, cA, cB) = (p.nameA, p.nameB, fmt(p.cA), fmt(p.cB))
    val pubs = (p.pA, p.pB) match
      case (Some(x), Some(y)) if x.compare(y) != 0 => Some(fmt(y))
      case _                                       => None
    // structured forms: B's value as a figure for A, citing both records, B's or A's
    val figs = Vector(
      Draft(
        "claim-2",
        s"$a has {figure-1} distinct coauthors in window $Win.",
        both,
        Vector((p.idA, Dcc, cB, both))
      ),
      Draft(
        "claim-3",
        s"$a has {figure-1} distinct coauthors and $b has {figure-2} distinct coauthors in window $Win.",
        both,
        Vector((p.idA, Dcc, cB, both), (p.idB, Dcc, cA, both))
      ),
      Draft(
        "claim-4",
        s"With {figure-1} distinct coauthors in window $Win, $a is listed.",
        both,
        Vector((p.idA, Dcc, cB, Vector(mB)))
      ),
      Draft(
        "claim-6",
        s"$a has {figure-1} distinct coauthors in window $Win.",
        both,
        Vector((p.idA, Dcc, cB, Vector(mA)))
      )
    ) ++ pubs.map(v =>
      Draft(
        "claim-5",
        s"$a has {figure-1} publications in window $Win.",
        both,
        Vector((p.idA, "publicationCount", v, both))
      )
    )
    // the same sentences with the numbers in free text, no figure
    val free = Vector(
      Draft("claim-12", s"$a has $cB distinct coauthors in window $Win.", both),
      Draft(
        "claim-13",
        s"$a has $cB distinct coauthors and $b has $cA distinct coauthors in window $Win.",
        both
      ),
      Draft("claim-14", s"With $cB distinct coauthors in window $Win, $a is listed.", both)
    ) ++ pubs.map(v => Draft("claim-15", s"$a has $v publications in window $Win.", both))
    val summary = s"$a has $cB distinct coauthors in window $Win."
    val res = modelRun("m5-misattributed", p, reply(summary, (control(p, both) +: figs) ++ free*))
    val figureChecks =
      figs.flatMap(d => d.figs.indices.flatMap(k => figureNotVerified(res, d.id, k + 1)))
    report(
      p,
      controlOk(res, p) ++ figureChecks ++ (figs ++ free).flatMap(d => interpretation(res, d.id)) ++
        noVerifiedFalse(res, p.idA, p.cB) ++ noVerifiedFalse(res, p.idB, p.cA) ++
        ModelV2.summaryLabeledOrReplaced(res, summary) ++ ModelV2.notVerifiedOverall(res)
    )
  }

  test(
    "M5: a number after two names is checked against the grammatical subject, not the nearest name"
  ) {
    val p = pair
    val both = metricIds("m5-subject", p)
    // A is the subject; B's value stated for A, with B named last before the number
    val text = s"${p.nameA}, compared with ${p.nameB}, has %s distinct coauthors in window $Win."
    val res = modelRun(
      "m5-subject",
      p,
      reply(
        s"${p.nameB} has ${fmt(p.cB)} distinct coauthors in window $Win.",
        control(p, both),
        // structured: the figure names A, B's value, B's record (the nearest name's)
        Draft("claim-2", text.format("{figure-1}"), both, Vector((p.idA, Dcc, fmt(p.cB), both))),
        // free text
        Draft("claim-3", text.format(fmt(p.cB)), both)
      )
    )
    report(
      p,
      controlOk(res, p) ++ figureNotVerified(res, "claim-2", 1) ++ interpretation(res, "claim-2") ++
        interpretation(res, "claim-3") ++ noVerifiedFalse(res, p.idA, p.cB)
    )
  }

  // M6. A question without years on the loaded window subset -----------------------------------

  /** Configured windows the benchmark runtime does not load (review-report.md M6, from the local
    * config's graph windows 1950-2026, 2000-2009, 2010-2014 and 2015-2026).
    */
  private val Unloaded = Set("1950-2026", "2000-2009")
  private val Loaded = BenchmarkRuntime.Windows
  private def windowStrings(v: ujson.Value): Vector[String] = v match
    case ujson.Str(s) => Vector(s)
    case a: ujson.Arr => a.value.toVector.flatMap(windowStrings)
    case _            => Vector.empty

  private def assertLoadedOnly(id: String, res: WorkflowResult, qs: Vector[GraphQuery]): Unit =
    val e = res.evidence
    assert(!Unloaded(e.plan.window.label), s"$id: planned unloaded window ${e.plan.window.label}")
    assert(Loaded(e.window.label), s"$id: evidence window ${e.window.label} is not loaded")
    assert(Loaded(res.answer.window.label), s"$id: answer window ${res.answer.window.label}")
    assert(Loaded(res.audit.answeredWindow.label), s"$id: audit window ${res.audit.answeredWindow}")
    qs.foreach { g =>
      g.parameters.obj.get("window").toVector.flatMap(windowStrings).foreach { w =>
        assert(Loaded(w), s"$id: ${g.templateId} ran on window $w")
      }
      if g.cypher.contains("COAUTHORED_WITH") then
        assert(g.parameters.obj.contains("window"), s"$id: ${g.templateId} has no window")
    }
    e.bundle.all.foreach { x =>
      Vector("window", "windowLabel").flatMap(x.values.get).foreach { w =>
        assert(Loaded(w), s"$id: evidence ${x.evidenceId} (${kind(x)}) is for window $w")
      }
    }
    val stated = (res.answer.summary +: res.answer.abstentionReason.toVector) ++
      res.answer.claims.flatMap(c => c.text +: c.uncertainty.toVector)
    for t <- stated; u <- Unloaded do
      assert(!t.contains(u), s"$id: the answer states unloaded window $u: '$t'")
    val Fact = s"""(?i)\\bin (?:the )?window (${Unloaded.mkString("|")})""".r
    (e.notes ++ e.windowNote.toVector ++ res.audit.notes).foreach { n =>
      assert(Fact.findFirstIn(n).isEmpty, s"$id: a note states a fact for an unloaded window: '$n'")
    }
    // the question asked for all years; the answer covers one loaded window and must say so
    assert(
      e.windowNote.exists(_.nonEmpty) || res.audit.windowNote.exists(_.nonEmpty),
      s"$id: answered from ${res.answer.window.label} without a window note"
    )

  /** The answer to a question without years, or None for a typed refusal (which answers from no
    * window at all and is accepted, after checking that it ran no query on an unloaded window).
    */
  private def noYears(id: String, text: String): Option[(WorkflowResult, Vector[GraphQuery])] =
    val (r, qs) = run(id, AnalysisRequest(text))
    r match
      case Left(e) =>
        assert(
          e.isInstanceOf[WorkflowError.InvalidRequest] || e.isInstanceOf[WorkflowError.Unsupported],
          e.describe
        )
        qs.foreach(g =>
          g.parameters.obj
            .get("window")
            .toVector
            .flatMap(windowStrings)
            .foreach(w => assert(Loaded(w), s"$id: ${g.templateId} ran on window $w"))
        )
        None
      case Right(res) =>
        assertLoadedOnly(id, res, qs)
        Some((res, qs))

  test("M6: a path question without years (bq-v1-10) plans and answers only from loaded windows") {
    val q = question("bq-v1-10")
    val text = """What is the shortest coauthorship path from "Fan Bai" to "Matthew Brown"?"""
    noYears("m6-path", text).foreach { (res, _) =>
      assert(!res.answer.abstained, res.answer.abstentionReason)
      if res.answer.window.label == q("window").str then
        // in 2010-2014 the oracle has exactly one shortest path of 3 hops
        val path = q("expected")("path").arr.map(_.str).toVector
        assert(
          res.evidence.bundle.deterministic.exists(x =>
            kind(x) == "GRAPH_PATH" && x.graphPath.contains(path)
          ),
          s"no GRAPH_PATH $path in ${res.answer.window.label}"
        )
    }
  }

  test("M6: a metric question without years states no fact for an unloaded window") {
    noYears("m6-metric", """How many distinct coauthors does "Haizhou Li" have?""").foreach {
      (res, _) =>
        if res.answer.window.label == "2010-2014" && !res.answer.abstained then
          assert(
            res.answer.researchers
              .find(_.researcherId == "orcid:0000-0001-9158-9401")
              .forall(_.distinctCoauthorCount == 10L),
            res.answer.researchers.map(x => (x.researcherId, x.distinctCoauthorCount))
          )
    }
  }

  test("M6: a pair question without years (bq-v1-07) checks the pair only in a loaded window") {
    noYears(
      "m6-pair",
      """Has "Shaojun Wei" collaborated with "Leibo Liu", and how many joint publications do they have?"""
    ).foreach { (res, qs) =>
      assert(qs.exists(_.templateId == "coauthor-pair-v1"), qs.map(_.templateId).distinct)
      if res.answer.window.label == "2010-2014" then
        assert(
          res.answer.claims.exists(c => numbers(c.text).exists(_.compare(BigDecimal(2)) == 0)),
          res.answer.claims.map(_.text)
        )
    }
  }

  // Evidence IDs on every numerical and path claim of the answers above ---------------------------

  test(
    "repair gate answers: every numerical and path claim cites existing deterministic evidence"
  ) {
    rt
    assert(seen.nonEmpty, "no answer was produced above")
    val bad = for
      r <- seen.toVector
      c <- r.answer.claims
      if numbers(c.text).nonEmpty || c.text.contains("->") || c.text.toLowerCase.contains("path")
      b = r.evidence.bundle
      good = c.evidenceIds.nonEmpty && c.evidenceIds.forall(b.byId.contains) &&
        c.evidenceIds.exists(id =>
          b.deterministic.exists(_.evidenceId == id) || b.identity.exists(_.evidenceId == id)
        )
      if !good
    yield s"${r.answer.queryId} ${c.claimId}: ${c.evidenceIds} :: ${c.text}"
    assert(bad.isEmpty, bad.mkString("\n", "\n", "\n"))
    val rows = for
      r <- seen.toVector
      x <- r.answer.researchers
      if x.claimIds.isEmpty || !x.claimIds.forall(r.answer.claims.map(_.claimId).contains)
    yield s"${r.answer.queryId} ${x.researcherId} claimIds ${x.claimIds}"
    assert(rows.isEmpty, rows.mkString("\n", "\n", "\n"))
  }
