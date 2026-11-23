package scholarchain.er

import scholarchain.core.*

/** The deterministic outcome of er-v1, before any adjudication.
  *
  * `confidence` is the policy's estimate of P(same entity) for every outcome (high for an
  * acceptance, low for a rejection). `score` is the probabilistic score, computed for every pair so
  * that threshold sensitivity can be reported; it decides only on the probabilistic path.
  */
final case class PolicyResult(
    outcome: MatchOutcome,
    path: MatchPath,
    confidence: Double,
    score: Double,
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    needsAdjudication: Boolean
)

/** Versioned scoring policy er-v1. Rules run in the order of the linkage evidence hierarchy, and
  * the first rule that applies decides:
  *
  * Researchers:
  *   - R-R0 an identifier scheme both agrees and disagrees (multi-valued sides): CONFLICT
  *   - R-R1 the two mentions are on the same work: REJECTED, or CONFLICT when an authoritative ID
  *     agrees
  *   - R-R2 an authoritative ID (ORCID) disagrees: CONFLICT, or REJECTED when the non-identifier
  *     score is at or below the reject threshold as well
  *   - R-R3 DBLP homonym suffixes differ: REJECTED, or CONFLICT when an authoritative ID agrees
  *   - R-R4 an exact ID agrees (ORCID, OpenAlex, Semantic Scholar, in that order): ACCEPTED when
  *     the names corroborate it, else UNRESOLVED
  *   - R-R5 same source cluster (identical DBLP person string or source author ID): ACCEPTED
  *   - R-R9 probabilistic score
  *
  * Works:
  *   - R-W0 contradictory identifiers: CONFLICT
  *   - R-W1 an authoritative ID (DOI, arXiv) disagrees: CONFLICT, or REJECTED when the score is at
  *     or below the reject threshold as well
  *   - R-W2 an exact ID agrees (DOI, arXiv, OpenAlex, Semantic Scholar, repository URL): ACCEPTED
  *     when the titles corroborate it, UNRESOLVED when they do not or when both records come from
  *     one source
  *   - R-W3 exact normalized bibliographic fields (title, year, author sequence): ACCEPTED
  *   - R-W9 probabilistic score
  *
  * Probabilistic score: a logistic function of hand-set weights (er-policy-v1.json). At or above
  * `accept` it accepts, at or below `reject` it rejects; in between it is UNRESOLVED, and only the
  * band `[llmBandMin, llmBandMax)` may be adjudicated.
  */
final class MatchPolicy(val config: PolicyConfig, val thresholds: Thresholds):
  require(thresholds.errors.isEmpty, thresholds.errors.mkString("; "))

  def version: String = config.policyVersion

  private def sigmoid(z: Double): Double = 1.0 / (1.0 + math.exp(-z))

  /** Score terms read the same identifier comparison as the rules. */
  private def disagrees(f: FeatureSet, s: IdScheme): Double =
    if f.agreement(s).disagree then 1.0 else 0.0

  /** An unknown year (encoded as [[Features.UnknownYears]]) is no evidence, not a large distance.
    */
  private def knownYears(v: Double)(term: Double => Double): Double =
    if v >= Features.UnknownYears then 0.0 else term(v)

  /** Researcher score over non-identifier evidence (plus the non-authoritative ID conflicts), with
    * its named contributions.
    */
  def researcherScore(f: FeatureSet): (Double, Vector[(String, Double)]) =
    val w = config.researcher.weights
    val r = config.researcher
    val nameTerm = math.max(0.0, f(Features.NameSimilarity) - r.nameFloor) / (1.0 - r.nameFloor)
    val yearTerm =
      knownYears(f(Features.YearGap))(g => math.max(0.0, math.min(g, r.yearGapCap) - r.yearGapFree))
    val parts = Vector(
      "bias" -> w("bias"),
      "nameAboveFloor" -> w("nameAboveFloor") * nameTerm,
      "sameFullName" -> w("sameFullName") * f(Features.SameFullName),
      "initialsIncompatible" -> w("initialsIncompatible") * (1.0 - f(Features.InitialsCompatible)),
      "coauthorOverlap" -> w("coauthorOverlap") * f(Features.CoauthorOverlap),
      "affiliationOverlap" -> w("affiliationOverlap") * f(Features.AffiliationOverlap),
      "affiliationDisjoint" -> w("affiliationDisjoint") * f(Features.AffiliationDisjoint),
      "venueOverlap" -> w("venueOverlap") * f(Features.VenueOverlap),
      "topicOverlap" -> w("topicOverlap") * f(Features.TopicOverlap),
      "authorPositionAgree" -> w("authorPositionAgree") * f(Features.AuthorPositionAgree),
      "openalexConflict" -> w("openalexConflict") * disagrees(f, IdScheme.OPENALEX),
      "s2Conflict" -> w("s2Conflict") * disagrees(f, IdScheme.S2),
      "yearGapPerYear" -> w("yearGapPerYear") * yearTerm,
      "citationOverlap" -> w("citationOverlap") * f(Features.CitationOverlap),
      "unreliability" -> w("unreliability") * (1.0 - f(Features.SourceReliability))
    )
    sigmoid(parts.map(_._2).sum) -> parts

  def workScore(f: FeatureSet): (Double, Vector[(String, Double)]) =
    val w = config.work.weights
    val p = config.work
    val yearTerm = knownYears(f(Features.YearDistance))(d =>
      math.max(0.0, math.min(d, p.yearDistanceCap) - p.yearDistanceFree)
    )
    val parts = Vector(
      "bias" -> w("bias"),
      "titleSimilarity" -> w("titleSimilarity") * f(Features.TitleSimilarity),
      "authorOverlap" -> w("authorOverlap") * f(Features.AuthorOverlap),
      "authorSequence" -> w("authorSequence") * f(Features.AuthorSequence),
      "venueCompatible" -> w("venueCompatible") * f(Features.VenueCompatible),
      "yearDistancePerYear" -> w("yearDistancePerYear") * yearTerm,
      "openalexConflict" -> w("openalexConflict") * disagrees(f, IdScheme.OPENALEX),
      "s2Conflict" -> w("s2Conflict") * disagrees(f, IdScheme.S2),
      "repoConflict" -> w("repoConflict") * disagrees(f, IdScheme.REPO),
      "citationOverlap" -> w("citationOverlap") * f(Features.CitationOverlap),
      "unreliability" -> w("unreliability") * (1.0 - f(Features.SourceReliability))
    )
    sigmoid(parts.map(_._2).sum) -> parts

  private def contributions(parts: Vector[(String, Double)]): Vector[String] =
    parts.filter(_._2 != 0.0).map((k, v) => f"$k=$v%.4f")

  private def fixed(
      outcome: MatchOutcome,
      path: MatchPath,
      confidence: Double,
      score: Double,
      rule: String,
      reason: String
  ) = PolicyResult(outcome, path, confidence, score, Vector(rule), Vector(reason), false)

  private def probabilistic(rule: String, score: Double, parts: Vector[(String, Double)]) =
    val t = thresholds
    val (outcome, reason, adjudicate) =
      if score >= t.accept then (MatchOutcome.ACCEPTED, "SCORE_AT_OR_ABOVE_ACCEPT", false)
      else if score <= t.reject then (MatchOutcome.REJECTED, "SCORE_AT_OR_BELOW_REJECT", false)
      else if t.inBand(score) then (MatchOutcome.UNRESOLVED, "SCORE_IN_LLM_BAND", true)
      else (MatchOutcome.UNRESOLVED, "SCORE_BETWEEN_THRESHOLDS", false)
    PolicyResult(
      outcome,
      MatchPath.PROBABILISTIC,
      score,
      score,
      rule +: contributions(parts),
      Vector(reason),
      adjudicate
    )

  private def contradiction(f: FeatureSet, schemes: Vector[IdScheme]): Option[IdScheme] =
    schemes.find(s => f.agreement(s).equal && f.agreement(s).disagree)

  private def authoritative(rules: Vector[SchemeRule]): Vector[IdScheme] =
    rules.filter(_.authoritative).map(_.scheme)

  def evaluate(kind: EntityKind, f: FeatureSet): PolicyResult = kind match
    case EntityKind.RESEARCHER => researcher(f)
    case EntityKind.WORK       => work(f)

  def researcher(f: FeatureSet): PolicyResult =
    val r = config.researcher
    val (score, parts) = researcherScore(f)
    val auth = authoritative(r.schemes)
    val authEqual = auth.find(s => f.agreement(s).equal)
    val authDisagree = auth.find(s => f.agreement(s).disagree)
    val conflictConf = config.conflictConfidence
    contradiction(f, r.schemes.map(_.scheme)) match
      case Some(s) =>
        fixed(
          MatchOutcome.CONFLICT,
          MatchPath.EXACT_ID,
          conflictConf,
          score,
          "R-R0",
          s"${s}_CONTRADICTORY"
        )
      case None if f.flag(Features.SharedWork) =>
        authEqual match
          case Some(s) =>
            fixed(
              MatchOutcome.CONFLICT,
              MatchPath.EXACT_ID,
              conflictConf,
              score,
              "R-R1",
              s"SAME_WORK_SAME_$s"
            )
          case None =>
            fixed(
              MatchOutcome.REJECTED,
              MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
              r.sharedWorkConfidence,
              score,
              "R-R1",
              "COAUTHORS_ON_SAME_WORK"
            )
      case None if authDisagree.nonEmpty =>
        val s = authDisagree.get
        if score <= thresholds.reject then
          fixed(
            MatchOutcome.REJECTED,
            MatchPath.EXACT_ID,
            score,
            score,
            "R-R2",
            s"${s}_DIFFERS_EVIDENCE_DIFFERS"
          )
        else
          fixed(
            MatchOutcome.CONFLICT,
            MatchPath.EXACT_ID,
            conflictConf,
            score,
            "R-R2",
            s"${s}_DISAGREES"
          )
      case None if f.flag(Features.DblpHomonymDistinct) =>
        authEqual match
          case Some(s) =>
            fixed(
              MatchOutcome.CONFLICT,
              MatchPath.EXACT_ID,
              conflictConf,
              score,
              "R-R3",
              s"HOMONYM_SUFFIX_VS_$s"
            )
          case None =>
            fixed(
              MatchOutcome.REJECTED,
              MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
              r.homonymConfidence,
              score,
              "R-R3",
              "DBLP_HOMONYM_DISTINCT"
            )
      case None =>
        r.schemes.find(rule => f.agreement(rule.scheme).equal) match
          case Some(rule) =>
            val s = rule.scheme
            if !f.flag(Features.NamesKnown) then
              // Nothing to corroborate with: the mention declares the very ID of its target.
              fixed(
                MatchOutcome.ACCEPTED,
                MatchPath.EXACT_ID,
                r.declaredOnlyConfidence,
                score,
                "R-R4",
                s"${s}_DECLARED_ONLY"
              )
            else if f(Features.NameSimilarity) >= rule.minCorroboration && f.flag(
                Features.InitialsCompatible
              )
            then
              fixed(
                MatchOutcome.ACCEPTED,
                MatchPath.EXACT_ID,
                r.exactAcceptConfidence,
                score,
                "R-R4",
                s"${s}_EQUAL"
              )
            else
              fixed(
                MatchOutcome.UNRESOLVED,
                MatchPath.EXACT_ID,
                score,
                score,
                "R-R4",
                s"${s}_EQUAL_NAMES_DISAGREE"
              )
          case None if f.flag(Features.SameSourceCluster) && f.flag(Features.InitialsCompatible) =>
            fixed(
              MatchOutcome.ACCEPTED,
              MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
              r.sourceClusterConfidence,
              score,
              "R-R5",
              "SAME_SOURCE_CLUSTER"
            )
          case None => probabilistic("R-R9", score, parts)

  def work(f: FeatureSet): PolicyResult =
    val p = config.work
    val (score, parts) = workScore(f)
    val auth = authoritative(p.schemes)
    val conflictConf = config.conflictConfidence
    contradiction(f, p.schemes.map(_.scheme)) match
      case Some(s) =>
        fixed(
          MatchOutcome.CONFLICT,
          MatchPath.EXACT_ID,
          conflictConf,
          score,
          "R-W0",
          s"${s}_CONTRADICTORY"
        )
      case None =>
        auth.find(s => f.agreement(s).disagree) match
          case Some(s) =>
            if score <= thresholds.reject then
              fixed(
                MatchOutcome.REJECTED,
                MatchPath.EXACT_ID,
                score,
                score,
                "R-W1",
                s"${s}_DIFFERS_EVIDENCE_DIFFERS"
              )
            else
              fixed(
                MatchOutcome.CONFLICT,
                MatchPath.EXACT_ID,
                conflictConf,
                score,
                "R-W1",
                s"${s}_DISAGREES"
              )
          case None =>
            p.schemes.find(rule => f.agreement(rule.scheme).equal) match
              case Some(rule) =>
                val s = rule.scheme
                if f.flag(Features.SameSource) then
                  fixed(
                    MatchOutcome.UNRESOLVED,
                    MatchPath.EXACT_ID,
                    score,
                    score,
                    "R-W2",
                    s"SAME_SOURCE_SHARES_$s"
                  )
                else if f(Features.TitleSimilarity) >= rule.minCorroboration then
                  fixed(
                    MatchOutcome.ACCEPTED,
                    MatchPath.EXACT_ID,
                    p.exactAcceptConfidence,
                    score,
                    "R-W2",
                    s"${s}_EQUAL"
                  )
                else
                  fixed(
                    MatchOutcome.UNRESOLVED,
                    MatchPath.EXACT_ID,
                    score,
                    score,
                    "R-W2",
                    s"${s}_EQUAL_TITLES_DISAGREE"
                  )
              case None if bibliographicMatch(f) =>
                fixed(
                  MatchOutcome.ACCEPTED,
                  MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
                  p.bibliographicConfidence,
                  score,
                  "R-W3",
                  "TITLE_YEAR_AUTHORS_EQUAL"
                )
              case None => probabilistic("R-W9", score, parts)

  /** Exact normalized bibliographic fields: the same normalized title of enough tokens, years
    * within the tolerance and a compatible author sequence, from two different sources.
    */
  def bibliographicMatch(f: FeatureSet): Boolean =
    val b = config.work.bibliographic
    f.flag(Features.SameNormalizedTitle) && f(Features.TitleTokens) >= b.minTitleTokens &&
    f(Features.YearDistance) <= b.maxYearDistance && f.flag(Features.AuthorsKnown) &&
    f(Features.AuthorSequence) >= b.minAuthorSequence && !f.flag(Features.SameSource)

object MatchPolicy:
  /** Rule IDs of the probabilistic path; threshold sensitivity re-decides only these. */
  val ProbabilisticRules: Set[String] = Set("R-R9", "R-W9")

  /** The policy in force: the module's er-v1 resource and thresholds from the loaded configuration,
    * which must equal the pinned ones.
    */
  def load(c: EntityResolutionConfig): Either[String, MatchPolicy] =
    for
      p <- PolicyConfig.load()
      t <- PolicyConfig.thresholdsFor(p, c)
    yield new MatchPolicy(p, t)
