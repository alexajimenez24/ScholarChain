package scholarchain.adversarial.rag

import scholarchain.adversarial.rag.BenchmarkScoring.states
import scholarchain.core.*
import scholarchain.rag.*

/** `answer-synthesis-v2` replies and `claim-verifier-v3` checks shared by the model-path gate
  * suites (test-engineer, Phase 08b follow-up task 2). Public contract only:
  * `prompts/schemas/answer-synthesis-v2.json`, `prompts/runtime/answer-synthesis-v2.md` and the API
  * changes of `docs/agent-runs/phase-09/repair-06-rag-engineer.md`:
  *   - a model claim `claim-N` is never VERIFIED; kept, it is PARTIALLY_SUPPORTED and labeled
  *     `UNVERIFIED interpretation`; it can still be removed as CONFLICTING or UNSUPPORTED;
  *   - each figure K of `claim-N` is checked as its own structured claim `claim-N-figure-K`, kept
  *     VERIFIED (text written from the evidence, naming the researcher) only when the tuple
  *     (researcherId, metric, value or rank, evidenceIds) matches the evidence, else removed;
  *   - the summary is labeled `UNVERIFIED interpretation` or replaced; a model answer's overall
  *     verification is never VERIFIED.
  */
object ModelV2:

  val Label = "UNVERIFIED interpretation"

  /** One figure tuple: researcherId, metric, value, evidenceIds. */
  type Fig = (String, String, String, Vector[String])

  final case class Draft(
      id: String,
      text: String,
      ev: Vector[String],
      figs: Vector[Fig] = Vector.empty,
      uncertainty: Option[String] = None
  )

  private def strs(xs: Vector[String]) = ujson.Arr.from(xs.map(ujson.Str(_)))

  def claimJson(d: Draft): ujson.Obj =
    ujson.Obj(
      "claimId" -> d.id,
      "text" -> d.text,
      "evidenceIds" -> strs(d.ev),
      "uncertainty" -> d.uncertainty.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "figures" -> ujson.Arr.from(d.figs.map { (rid, m, v, fev) =>
        ujson.Obj("researcherId" -> rid, "metric" -> m, "value" -> v, "evidenceIds" -> strs(fev))
      })
    )

  def reply(summary: String, claims: Draft*): String =
    ujson.write(
      ujson.Obj(
        "schemaVersion" -> "answer-synthesis-v2",
        "summary" -> summary,
        "abstain" -> false,
        "abstentionReason" -> ujson.Null,
        "claims" -> ujson.Arr.from(claims.map(claimJson))
      )
    )

  def abstention(summary: String, reason: String): String =
    ujson.write(
      ujson.Obj(
        "schemaVersion" -> "answer-synthesis-v2",
        "summary" -> summary,
        "abstain" -> true,
        "abstentionReason" -> reason,
        "claims" -> ujson.Arr()
      )
    )

  def figureId(claimId: String, k: Int): String = s"$claimId-figure-$k"

  def fmt(x: BigDecimal): String = x.bigDecimal.stripTrailingZeros.toPlainString

  def claim(res: WorkflowResult, id: String): Option[AnswerClaim] =
    res.answer.claims.find(_.claimId == id)

  def isVerified(c: AnswerClaim): Boolean = c.verificationStatus.toString == "VERIFIED"

  def labeled(c: AnswerClaim): Boolean =
    c.text.contains(Label) || c.text.contains("[unverified]") ||
      c.uncertainty.exists(_.contains(Label))

  /** Every check and removal recorded for `id` and its figures. */
  def trace(res: WorkflowResult, id: String): String =
    def mine(x: String) = x == id || x.startsWith(s"$id-figure-")
    s"checks ${res.audit.claimChecks.filter(k => mine(k.claimId)).map(k => (k.claimId, k.status, k.reasons))}; " +
      s"removed ${res.audit.removedClaims.filter(r => mine(r.claimId) || r.text.contains(s" of $id:")).map(r => (r.claimId, r.status, r.text))}"

  private def checked(res: WorkflowResult, id: String): Boolean =
    res.audit.claimChecks.exists(_.claimId == id) || res.audit.removedClaims.exists(_.claimId == id)

  /** None when free model text `id` is never VERIFIED: kept PARTIALLY_SUPPORTED with the label, or
    * (when `mayRemove`) removed after a check.
    */
  def interpretation(res: WorkflowResult, id: String, mayRemove: Boolean = true): Option[String] =
    claim(res, id) match
      case None =>
        if !mayRemove then Some(s"$id removed although it must be kept labeled; ${trace(res, id)}")
        else Option.when(!checked(res, id))(s"$id was never checked; ${trace(res, id)}")
      case Some(c) =>
        Option.when(c.verificationStatus.toString != "PARTIALLY_SUPPORTED" || !labeled(c))(
          s"$id kept as ${c.verificationStatus}: '${c.text}' (uncertainty ${c.uncertainty}); " +
            trace(res, id)
        )

  /** None when figure `k` of `id` is kept VERIFIED, names `rid` and states `value`. */
  def figureVerified(
      res: WorkflowResult,
      id: String,
      k: Int,
      rid: String,
      value: BigDecimal
  ): Option[String] =
    val f = figureId(id, k)
    claim(res, f) match
      case Some(c) if isVerified(c) =>
        Option.when(!states(c.text, value) || !c.text.contains(rid))(
          s"$f VERIFIED but shows '${c.text}' (expected $rid and ${fmt(value)})"
        )
      case other => Some(s"$f not VERIFIED: ${other.map(_.text)}; ${trace(res, id)}")

  /** None when figure `k` of `id` is not VERIFIED and the claim was checked. */
  def figureNotVerified(res: WorkflowResult, id: String, k: Int): Option[String] =
    val f = figureId(id, k)
    claim(res, f) match
      case Some(c) if isVerified(c) => Some(s"$f VERIFIED: '${c.text}'; ${trace(res, id)}")
      case _                        =>
        Option.when(!checked(res, id) && !checked(res, f))(s"$id was never checked")

  /** The status of the removal recorded for figure `k` of `id`, if any. */
  def figureRemoval(res: WorkflowResult, id: String, k: Int): Option[String] =
    res.audit.removedClaims
      .find(r => r.claimId == figureId(id, k) || r.text.startsWith(s"figure $k of $id:"))
      .map(_.status.toString)

  /** None when no VERIFIED claim of the answer names `rid` and states `value`. */
  def noVerifiedFalse(res: WorkflowResult, rid: String, value: BigDecimal): Option[String] =
    val bad =
      res.answer.claims.filter(c => isVerified(c) && c.text.contains(rid) && states(c.text, value))
    Option.when(bad.nonEmpty)(
      s"VERIFIED claims state ${fmt(value)} for $rid: ${bad.map(c => (c.claimId, c.text))}"
    )

  /** None when a model summary is replaced or shown labeled. */
  def summaryLabeledOrReplaced(res: WorkflowResult, original: String): Option[String] =
    val replaced = res.audit.summaryReplaced.isDefined && res.answer.summary != original
    Option.when(!replaced && !res.answer.summary.contains(Label))(
      s"model summary shown unlabeled: '${res.answer.summary}' (replaced ${res.audit.summaryReplaced})"
    )

  /** None when the answer's overall verification is not VERIFIED (a model answer never is). */
  def notVerifiedOverall(res: WorkflowResult): Option[String] =
    Option.when(res.answer.verification.toString == "VERIFIED")(
      s"model answer verification VERIFIED (${res.answer.promptVersion})"
    )

  /** English words for 0 to 9999 ("thirty-one", "one hundred and five"). */
  def words(n: Int): String =
    val ones = Vector(
      "zero",
      "one",
      "two",
      "three",
      "four",
      "five",
      "six",
      "seven",
      "eight",
      "nine",
      "ten",
      "eleven",
      "twelve",
      "thirteen",
      "fourteen",
      "fifteen",
      "sixteen",
      "seventeen",
      "eighteen",
      "nineteen"
    )
    val tens =
      Vector("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    def below100(x: Int): String =
      if x < 20 then ones(x)
      else tens(x / 10) + (if x % 10 == 0 then "" else "-" + ones(x % 10))
    def below1000(x: Int): String =
      if x < 100 then below100(x)
      else ones(x / 100) + " hundred" + (if x % 100 == 0 then "" else " and " + below100(x % 100))
    if n < 1000 then below1000(n)
    else ones(n / 1000) + " thousand" + (if n % 1000 == 0 then "" else " " + below1000(n % 1000))
