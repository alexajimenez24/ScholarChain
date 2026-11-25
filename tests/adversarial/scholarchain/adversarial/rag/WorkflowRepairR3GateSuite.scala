package scholarchain.adversarial.rag

import scholarchain.adversarial.rag.BenchmarkScoring.num
import scholarchain.core.*
import scholarchain.rag.*

/** Phase 09 repair round 3 gate tests (test-engineer), converted for `answer-synthesis-v2` and
  * `claim-verifier-v3` (Phase 08b follow-up task 2, human decision in
  * `docs/agent-runs/phase-08b/run-log.md`).
  *
  * Rule under test (`repair-06-rag-engineer.md`): only structured figures (researcherId, metric,
  * value or rank, evidenceIds) can be VERIFIED; every free-text model claim is labeled UNVERIFIED
  * interpretation. The class under test stays the one R3 was written for: a false per-researcher
  * number is never VERIFIED, whatever the prose around it says. Every phrasing of R3 (review 3), R4
  * (review 4), V5-1 (review 5) and V5b (review 5b) is sent as FREE TEXT and must come out labeled
  * and never VERIFIED; the structured forms are VERIFIED only when the tuple matches the evidence,
  * never on a wrong value, a wrong rank, a wrong researcher, or a missing or foreign evidence ID.
  *
  * Fixture: A and B are two resolved researchers of one frozen two-name question with different
  * distinct-coauthor counts (selection rule of [[WorkflowRepairD5GateSuite]]); every model claim
  * cites the metric records of A and B and, when the bundle holds one, of a third researcher. The
  * ranking tests use the frozen ranking question bq-v1-01. Public API only
  * (`modules/rag/MODULE.md`, `prompts/schemas/answer-synthesis-v2.json`,
  * `repair-06-rag-engineer.md`).
  *
  * Spec (`docs/spec/assignment.md`): stage 9 "Check every researcher identity, ... coauthor count,
  * ... and numerical value against the bundle"; gate "Unsupported claims are removed, weakened, or
  * explicitly labeled"; line 135 "without confusing deterministic graph facts with LLM-generated
  * interpretation".
  */
class WorkflowRepairR3GateSuite extends munit.FunSuite:

  import ModelV2.{Draft, Fig, figureNotVerified, figureVerified, fmt, interpretation}
  import ModelV2.{noVerifiedFalse, notVerifiedOverall, reply, summaryLabeledOrReplaced, words}

  override val munitTimeout = scala.concurrent.duration.Duration(40, "min")

  private val Win = "2010-2014"
  private val Dcc = "distinctCoauthorCount"
  private val Missing = "ev:metric_record:0123456789abcdef"

  private def rt: LocalRuntime =
    assume(BenchmarkRuntime.present, s"run data absent at ${BenchmarkStore.runDir}")
    BenchmarkRuntime.runtime

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
          cA <- num(mA, Dcc)
          cB <- num(mB, Dcc)
          if cA.compare(cB) != 0
        yield Pair(q("id").str, req, names(0), names(1), a, b, cA, cB, num(mA, "publicationCount"))
      }
      .nextOption()
      .getOrElse(fail("no two-name question has both metric records with different counts"))

  /** Evidence IDs under `queryId`: A's and B's metric records, a third researcher's when the bundle
    * has one, and an evidence ID of another query's bundle that this bundle does not hold.
    */
  private final case class Cited(
      mA: String,
      mB: String,
      third: Option[String],
      all: Vector[String],
      foreign: String
  )

  private def cited(queryId: String, p: Pair): Cited =
    val b = bundle(queryId, p.req)
    def id(rid: String) =
      metric(b, rid).getOrElse(fail(s"no metric item for $rid under $queryId")).evidenceId
    val third = b.deterministic
      .find(x =>
        isMetric(x) && x.researcherIds.size == 1 && !Set(p.idA, p.idB)(x.researcherIds.head)
      )
      .map(_.evidenceId)
    val foreign = bundle(s"$queryId-foreign", ranking.req).deterministic
      .map(_.evidenceId)
      .find(x => !b.byId.contains(x))
      .getOrElse(fail(s"every bq-v1-01 evidence ID is also in the bundle of $queryId"))
    Cited(id(p.idA), id(p.idB), third, Vector(id(p.idA), id(p.idB)) ++ third.toVector, foreign)

  private def runModel(queryId: String, req: AnalysisRequest, replyText: String): WorkflowResult =
    val llm = ScriptedLlm.replies(replyText)
    val (r, _) = BenchmarkRuntime.observe(BenchmarkRuntime.withModel(llm).run(queryId, req))
    val res = r.fold(e => fail(s"workflow error: ${e.describe}"), identity)
    assertEquals(res.synthesis.fallback, None, "the model reply was not used")
    assertEquals(res.answer.promptVersion, "answer-synthesis-v2")
    res

  private def report(ctx: String, bad: Iterable[String]): Unit =
    assert(bad.isEmpty, ctx + bad.mkString("\n", "\n", "\n"))

  private def ctx(p: Pair) = s"pair ${p.qid} (A=${p.idA} ${p.cA}, B=${p.idB} ${p.cB}):"

  /** The controls: A's and B's true values as structured figures citing their own records. */
  private def controls(p: Pair, c: Cited): Vector[Draft] = Vector(
    Draft(
      "claim-1",
      s"${p.nameA} has {figure-1} distinct coauthors in window $Win.",
      c.all,
      Vector((p.idA, Dcc, fmt(p.cA), Vector(c.mA)))
    ),
    Draft(
      "claim-2",
      s"${p.nameB} has {figure-1} distinct coauthors in window $Win.",
      c.all,
      Vector((p.idB, Dcc, fmt(p.cB), Vector(c.mB)))
    )
  )

  private def controlsOk(res: WorkflowResult, p: Pair): Vector[String] =
    (figureVerified(res, "claim-1", 1, p.idA, p.cA) ++
      figureVerified(res, "claim-2", 1, p.idB, p.cB) ++
      interpretation(res, "claim-1", mayRemove = false) ++
      interpretation(res, "claim-2", mayRemove = false)).toVector

  private def noConflict(res: WorkflowResult, ids: Vector[String]): Vector[String] =
    res.audit.removedClaims
      .filter(r => ids.contains(r.claimId) && r.status.toString == "CONFLICTING")
      .map(r => s"${r.claimId} removed as CONFLICTING: '${r.text}' ${r.reasons}")
      .toVector

  // The phrasings ----------------------------------------------------------------------------------

  /** R3-1 inputs 1 to 7 (review-report-03.md): `n` stated for A while B is named. */
  private def r3(p: Pair, n: String): Vector[String] =
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

  /** R3-2 (`n` stated for B after "like A") and R3-3 (pronoun subject, `n` for A). */
  private def r3more(p: Pair, nForB: String, nForA: String): Vector[String] =
    val (a, b) = (p.nameA, p.nameB)
    Vector(
      s"$a collaborates with $b, who, like $a, has $nForB distinct coauthors.",
      s"$a leads. Unlike $b, she has $nForA distinct coauthors in window $Win."
    )

  /** R4-1 and R4-2 (review-report-04.md): partial, lower-case and NBSP names, noun phrases. */
  private def r4(p: Pair, n: String): Vector[String] =
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

  private def digitsIn(base: Char, v: Int): String = v.toString.map(d => (base + (d - '0')).toChar)

  private def roman(v: Int): String =
    val table = Vector(
      1000 -> "M",
      900 -> "CM",
      500 -> "D",
      400 -> "CD",
      100 -> "C",
      90 -> "XC",
      50 -> "L",
      40 -> "XL",
      10 -> "X",
      9 -> "IX",
      5 -> "V",
      4 -> "IV",
      1 -> "I"
    )
    table
      .foldLeft((v, "")) { case ((left, out), (k, s)) => (left % k, out + s * (left / k)) }
      ._2

  /** V5-1 (review-report-05.md) and V5b-1 to V5b-3 (review-report-05b.md): numbers that are not
    * ASCII digits, numbers joined across names and windows, quantity words and superlatives, about
    * A with value `v` (B's value when false) and `w` for the other researcher.
    */
  private def v5(p: Pair, v: Int, w: Int): Vector[String] =
    val (a, b) = (p.nameA, p.nameB)
    val word = words(v)
    val vowel = word.indexWhere("aeiou".contains(_))
    val accented = word.patch(vowel + 1, "́", 0)
    val soft =
      if word.contains('-') then word.replace("-", "­")
      else word.patch(word.length / 2, "­", 0)
    val cyrillic = word.replace('o', 'о').replace('e', 'е').replace('a', 'а')
    val zw = v.toString.patch(1, "​", 0)
    val sup = v.toString.map(d => "⁰¹²³⁴⁵⁶⁷⁸⁹" (d - '0'))
    def has(n: String) = s"$a has $n distinct coauthors in window $Win."
    Vector(
      // V5-1
      has(word),
      has("three dozen"),
      s"$a has ${v}x more distinct coauthors than $b in window $Win.",
      has(s"_${v}_"),
      has(digitsIn('０', v)),
      has(digitsIn('٠', v)),
      s"$a ranks 3rd by distinct coauthors in window $Win.",
      s"$a ranks first by distinct coauthors in window $Win.",
      // V5b-1: number words joined across a name or a window
      s"$a has $word and $b ${words(w)} distinct coauthors in window $Win.",
      s"$a has $word $Win one distinct coauthors.",
      s"$a ranks first $b second by distinct coauthors.",
      // V5b-2: marks, soft hyphen, glued words, look-alike letters, Roman numerals
      has(accented),
      has(soft),
      has(word.replace("-", "")),
      has(cyrillic),
      has(roman(v)),
      has(zw),
      has(sup),
      // quantity words
      has("a single"),
      s"$a has no distinct coauthors in window $Win.",
      has("a pair of"),
      has("a couple of"),
      has("a score of"),
      // superlatives and rank words
      s"$a has the most distinct coauthors in window $Win.",
      s"$a has the highest distinct coauthor count in window $Win.",
      s"$a leads the ranking by distinct coauthors in window $Win.",
      s"$a is number one by distinct coauthors in window $Win.",
      s"$a is No. 1 by distinct coauthors in window $Win.",
      // V5b-3: comparatives and a window used as a count
      s"$b has more distinct coauthors than $a in window $Win.",
      s"$a has $Win distinct coauthors."
    )

  private def free(from: Int, texts: Vector[String], c: Cited): Vector[Draft] =
    texts.zipWithIndex.map((t, i) => Draft(s"claim-${from + i}", t, c.all))

  private def int(x: BigDecimal): Int = x.toIntExact

  // Free text --------------------------------------------------------------------------------------

  test("free text: every R3 and R4 phrasing with B's number for A is labeled, never VERIFIED") {
    val p = pair
    val c = cited("r3-free-false", p)
    val drafts =
      free(11, r3(p, fmt(p.cB)) ++ r3more(p, fmt(p.cA), fmt(p.cB)) ++ r4(p, fmt(p.cB)), c)
    val res = runModel("r3-free-false", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(
      ctx(p),
      controlsOk(res, p) ++ drafts.flatMap(d => interpretation(res, d.id)) ++
        noVerifiedFalse(res, p.idA, p.cB) ++ noVerifiedFalse(res, p.idB, p.cA) ++
        notVerifiedOverall(res)
    )
  }

  test("free text: every V5-1 and V5b phrasing with B's number for A is labeled, never VERIFIED") {
    val p = pair
    val c = cited("r3-free-v5", p)
    val drafts = free(11, v5(p, int(p.cB), int(p.cA)), c)
    val res = runModel("r3-free-v5", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(
      ctx(p),
      controlsOk(res, p) ++ drafts.flatMap(d => interpretation(res, d.id)) ++
        noVerifiedFalse(res, p.idA, p.cB) ++ notVerifiedOverall(res)
    )
  }

  test("free text: the R3, R4 and V5 phrasings with A's true number are labeled, never VERIFIED") {
    val p = pair
    val c = cited("r3-free-true", p)
    val r3True = free(11, r3(p, fmt(p.cA)) ++ r3more(p, fmt(p.cB), fmt(p.cA)), c)
    val rest = free(21, r4(p, fmt(p.cA)) ++ v5(p, int(p.cA), int(p.cB)).take(8), c)
    val res =
      runModel("r3-free-true", p.req, reply("A comparison.", controls(p, c) ++ r3True ++ rest*))
    report(
      ctx(p),
      controlsOk(res, p) ++
        // R3's own rule kept: a true number in prose is weakened and labeled, not removed
        r3True.flatMap(d => interpretation(res, d.id, mayRemove = false)) ++
        noConflict(res, r3True.map(_.id)) ++
        rest.flatMap(d => interpretation(res, d.id)) ++ notVerifiedOverall(res)
    )
  }

  test("free text: a model summary in R3 form 1 or 4, R4 or V5b form is labeled or replaced") {
    val p = pair
    val forms = Vector(r3(p, fmt(p.cB))(0), r3(p, fmt(p.cB))(3), r4(p, fmt(p.cB))(5)) ++
      Vector(v5(p, int(p.cB), int(p.cA))(0), v5(p, int(p.cB), int(p.cA))(25))
    val bad = forms.zipWithIndex.flatMap { (s, i) =>
      val qid = s"r3-summary-${i + 1}"
      val res = runModel(qid, p.req, reply(s, controls(p, cited(qid, p))*))
      (controlsOk(res, p) ++ summaryLabeledOrReplaced(res, s)).map(x => s"summary ${i + 1}: $x")
    }
    report(ctx(p), bad)
  }

  test(
    "control: one researcher, correct figures VERIFIED, and its correct summary kept (labeled, not replaced)"
  ) {
    val p = pair
    val c = cited("r3-control", p)
    val summary = s"${p.nameA} has ${fmt(p.cA)} distinct coauthors in window $Win."
    val res = runModel("r3-control", p.req, reply(summary, controls(p, c)*))
    report(
      ctx(p),
      controlsOk(res, p) ++
        Option.when(
          res.audit.summaryReplaced.isDefined || !res.answer.summary.contains(summary) ||
            !res.answer.summary.contains(ModelV2.Label)
        )(
          s"correct summary not kept labeled: ${res.audit.summaryReplaced}; '${res.answer.summary}'"
        ) ++ noConflict(res, Vector("claim-1", "claim-2")) ++ notVerifiedOverall(res)
    )
  }

  // Structured figures -----------------------------------------------------------------------------

  test("structured: A's true tuple is VERIFIED with A's value in every R3 and R4 phrasing") {
    val p = pair
    val c = cited("r3-struct-true", p)
    val texts = r3(p, "{figure-1}") ++ r4(p, "{figure-1}")
    val drafts = texts.zipWithIndex.map((t, i) =>
      Draft(s"claim-${11 + i}", t, c.all, Vector((p.idA, Dcc, fmt(p.cA), Vector(c.mA))))
    )
    val res = runModel("r3-struct-true", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(
      ctx(p),
      controlsOk(res, p) ++
        drafts.flatMap(d => figureVerified(res, d.id, 1, p.idA, p.cA)) ++
        // the prose around a true figure is still only an interpretation
        drafts.flatMap(d => interpretation(res, d.id)) ++
        drafts.flatMap(d =>
          ModelV2
            .claim(res, ModelV2.figureId(d.id, 1))
            .filter(x => BenchmarkScoring.states(x.text, p.cB))
            .map(x => s"${d.id} figure shows B's value: '${x.text}'")
        ) ++ notVerifiedOverall(res)
    )
  }

  test(
    "structured: a wrong value, wrong researcher, wrong metric, or a missing or foreign evidence ID is never VERIFIED"
  ) {
    val p = pair
    val c = cited("r3-struct-bad", p)
    val (cA, cB) = (fmt(p.cA), fmt(p.cB))
    val t = s"${p.nameA} has {figure-1} distinct coauthors in window $Win."
    // the claim cites the bundle's records plus whatever its figure cites
    def d(i: Int, f: Fig): Draft = Draft(s"claim-$i", t, (c.all ++ f._4).distinct, Vector(f))
    val drafts = Vector(
      // wrong value: B's value, then off by one, on A's own record
      d(11, (p.idA, Dcc, cB, Vector(c.mA))),
      d(12, (p.idA, Dcc, fmt(p.cA + 1), Vector(c.mA))),
      // wrong value on B's record, and on both records
      d(13, (p.idA, Dcc, cB, Vector(c.mB))),
      d(14, (p.idA, Dcc, cB, Vector(c.mA, c.mB))),
      // wrong researcher: A's value for B, for an unknown ID
      d(15, (p.idB, Dcc, cA, Vector(c.mA))),
      d(16, (p.idB, Dcc, cA, Vector(c.mA, c.mB))),
      d(17, ("ra:not-a-researcher", Dcc, cA, Vector(c.mA))),
      // wrong metric name for A's value
      d(18, (p.idA, "coauthorRank", cA, Vector(c.mA))),
      // true value, missing or foreign evidence ID, alone and next to A's own record
      d(19, (p.idA, Dcc, cA, Vector(Missing))),
      d(20, (p.idA, Dcc, cA, Vector(c.mA, Missing))),
      d(21, (p.idA, Dcc, cA, Vector(c.foreign))),
      d(22, (p.idA, Dcc, cA, Vector(c.mA, c.foreign)))
    ) ++
      // true value on the third researcher's record only
      c.third.toVector.map(x => d(24, (p.idA, Dcc, cA, Vector(x)))) ++
      // the publication count stated as distinct coauthors, when the two differ
      p.pA.filter(_.compare(p.cA) != 0).toVector.map(x => d(25, (p.idA, Dcc, fmt(x), Vector(c.mA))))
    val res = runModel("r3-struct-bad", p.req, reply("A comparison.", controls(p, c) ++ drafts*))
    report(
      ctx(p),
      controlsOk(res, p) ++ drafts.flatMap(x => figureNotVerified(res, x.id, 1)) ++
        drafts.flatMap(x => interpretation(res, x.id)) ++
        noVerifiedFalse(res, p.idA, p.cB) ++ noVerifiedFalse(res, p.idA, p.cA + 1) ++
        noVerifiedFalse(res, p.idB, p.cA) ++ notVerifiedOverall(res)
    )
  }

  test(
    "structured: a figure citing an ID outside its claim's evidence IDs is refused or never VERIFIED"
  ) {
    val p = pair
    val c = cited("r3-struct-outside", p)
    val bad = Draft(
      "claim-11",
      s"${p.nameA} has {figure-1} distinct coauthors in window $Win.",
      Vector(c.mB),
      Vector((p.idA, Dcc, fmt(p.cA), Vector(c.mA)))
    )
    val llm = ScriptedLlm.replies(reply("A comparison.", (controls(p, c) :+ bad)*))
    val (r, _) =
      BenchmarkRuntime.observe(BenchmarkRuntime.withModel(llm).run("r3-struct-outside", p.req))
    val res = r.fold(e => fail(s"workflow error: ${e.describe}"), identity)
    // a refused reply ends in the deterministic answer (no model figure); an accepted one must not
    // verify the figure
    if res.synthesis.fallback.isEmpty then
      report(ctx(p), controlsOk(res, p) ++ figureNotVerified(res, "claim-11", 1))
    else assertEquals(res.answer.promptVersion, "deterministic-synthesis-v1")
  }

  // Ranking ----------------------------------------------------------------------------------------

  /** The ranking question bq-v1-01, its ranking item, the top position and the first position whose
    * value differs from the top's (same rule as [[WorkflowRepairR4GateSuite]]).
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
    val item = bundle("r3-ranking-probe", req).deterministic
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

  test(
    "ranking: a rank is VERIFIED only as a matching structured tuple; rank prose and bad rank tuples never are"
  ) {
    val r = ranking
    val (tRank, tId, _) = r.top
    val (oRank, oId, _) = r.other
    val qid = "r3-ranking"
    val ev = Vector(r.itemId)
    val b = bundle(qid, r.req)
    val foreign = bundle(s"$qid-foreign", pair.req).deterministic
      .map(_.evidenceId)
      .find(x => !b.byId.contains(x))
      .getOrElse(fail("no foreign evidence ID"))
    val text = "The researcher holds position {figure-1} in the ranking."
    def d(i: Int, f: Fig): Draft = Draft(s"claim-$i", text, (ev ++ f._4).distinct, Vector(f))
    val good = d(1, (tId, "rank", tRank.toString, ev))
    val bad = Vector(
      d(11, (tId, "rank", oRank.toString, ev)), // wrong rank
      d(12, (oId, "rank", tRank.toString, ev)), // wrong researcher
      d(13, ("ra:not-a-researcher", "rank", tRank.toString, ev)),
      d(14, (tId, "rank", tRank.toString, Vector(Missing))), // missing evidence ID
      d(15, (tId, "rank", tRank.toString, Vector(foreign))), // foreign evidence ID
      d(16, (tId, "rank", tRank.toString, ev :+ Missing)),
      d(17, (tId, "rank", tRank.toString, ev :+ foreign))
    )
    // V5-1, V5b-1 and V5b-2 rank prose, about the top and about another position
    val prose = Vector(oId, tId).flatMap(id =>
      Vector(
        s"$id leads the ranking.",
        s"$id ranks first by ${r.metricField}.",
        s"$id ranks 1st by ${r.metricField}.",
        s"$id has the most ${r.metricField}.",
        s"$id has the highest ${r.metricField} count.",
        s"$id is number one in the ranking.",
        s"$id is No. 1 in the ranking.",
        s"$id ranks first $tId second by ${r.metricField}."
      )
    )
    val freeDrafts = prose.zipWithIndex.map((t, i) => Draft(s"claim-${21 + i}", t, ev))
    val res = runModel(qid, r.req, reply("The ranking.", (good +: bad) ++ freeDrafts*))
    report(
      s"ranking ${r.itemId} top=${r.top} other=${r.other}:",
      figureVerified(res, "claim-1", 1, tId, BigDecimal(tRank)) ++
        interpretation(res, "claim-1", mayRemove = false) ++
        bad.flatMap(x => figureNotVerified(res, x.id, 1)) ++
        (bad ++ freeDrafts).flatMap(x => interpretation(res, x.id)) ++ notVerifiedOverall(res)
    )
  }
