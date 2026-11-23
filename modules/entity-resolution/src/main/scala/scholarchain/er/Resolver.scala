package scholarchain.er

import scholarchain.core.*
import upickle.default.ReadWriter

/** The audit trail of one decision: everything needed to reproduce and explain it. The core
  * `match-v1` decision has no timestamp or upstream run field, so they live here (`decidedAt` is
  * the run's start time, one value per run, so decisions stay reproducible).
  */
final case class DecisionAudit(
    decisionId: String,
    candidateId: String,
    kind: EntityKind,
    sourceId: String,
    targetId: String,
    decision: MatchOutcome,
    path: MatchPath,
    confidence: Double,
    score: Double,
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    features: Map[String, Double],
    upstreamFeatures: Map[String, Double],
    blockKeys: Vector[String],
    candidateCopies: Int,
    leftFound: Boolean,
    rightFound: Boolean,
    profilesTruncated: Boolean,
    policyVersion: String,
    featureVersion: String,
    normalizationVersion: String,
    thresholds: Thresholds,
    adjudicatorMode: String,
    promptVersion: Option[String],
    modelVersion: Option[String],
    adjudicationCacheKey: Option[String],
    adjudicationFromCache: Option[Boolean],
    upstreamRunId: String,
    upstreamPolicyVersion: Option[String],
    runId: String,
    decidedAt: String
) derives ReadWriter

final case class Resolved(
    decision: MatchDecision,
    audit: DecisionAudit,
    adjudication: Option[AdjudicationRecord]
)

/** Turns one merged candidate and its two endpoint profiles into a decision: er-v1 first, then,
  * only for a probabilistic score in the LLM band, the bounded adjudicator. An adjudication can
  * move a band case to ACCEPTED or REJECTED on the LLM_ADJUDICATED path; an abstention, a
  * low-confidence answer, a refused reply, an exhausted budget or off mode leaves it UNRESOLVED.
  * The candidate, the profiles and the features are never changed by adjudication.
  */
final class Resolver(
    policy: MatchPolicy,
    adjudicator: Adjudicator,
    runId: String,
    decidedAt: String
):
  def decide(c: MergedCandidate, left: EndpointProfile, right: EndpointProfile): Resolved =
    val f = Features.of(c, left, right, policy.config)
    val p = policy.evaluate(c.kind, f)
    val features = f.values + (Resolver.ScoreFeature -> p.score)
    val decisionId = Ids.decision(c.kind, c.sourceId, c.targetId, policy.version)
    val base = MatchDecision(
      SchemaVersions.Match,
      decisionId,
      c.kind,
      c.sourceId,
      c.targetId,
      features,
      p.rulesFired,
      p.outcome,
      p.path,
      p.confidence,
      p.reasonCodes,
      policy.version,
      None,
      None,
      Provenance(
        SourceType.SCHOLARCHAIN,
        c.candidateId,
        c.snapshot,
        runId,
        SchemaVersions.Match,
        Some(policy.version)
      )
    )
    val (decision, adjudication, fromCache) =
      if !p.needsAdjudication then (base, None, None)
      else adjudicate(c, left, right, features, p.score, base)
    val audit = DecisionAudit(
      decisionId,
      c.candidateId,
      c.kind,
      c.sourceId,
      c.targetId,
      decision.decision,
      decision.path,
      decision.confidence,
      p.score,
      decision.rulesFired,
      decision.reasonCodes,
      features,
      c.upstreamFeatures,
      c.blockKeys,
      c.copies,
      left.found,
      right.found,
      left.truncated || right.truncated,
      policy.version,
      policy.config.featureVersion,
      policy.config.normalizationVersion,
      policy.thresholds,
      adjudicator.mode.toString,
      decision.promptVersion,
      decision.modelVersion,
      adjudication.map(_.cacheKey),
      fromCache,
      c.upstreamRunId,
      c.upstreamPolicyVersion,
      runId,
      decidedAt
    )
    Resolved(decision, audit, adjudication)

  private def adjudicate(
      c: MergedCandidate,
      left: EndpointProfile,
      right: EndpointProfile,
      features: Map[String, Double],
      score: Double,
      base: MatchDecision
  ): (MatchDecision, Option[AdjudicationRecord], Option[Boolean]) =
    val request = AdjudicationRequest(
      c.candidateId,
      c.kind,
      c.sourceId,
      c.targetId,
      policy.version,
      score,
      features,
      Adjudication.fields(left, right, policy.config.adjudication)
    )
    val consulted = base.copy(
      promptVersion = Some(adjudicator.promptVersion),
      modelVersion = Some(adjudicator.modelVersion)
    )
    adjudicator.adjudicate(request) match
      case AdjudicationResult.Skipped(reason, _) =>
        (base.copy(reasonCodes = base.reasonCodes :+ reason), None, None)
      case AdjudicationResult.Answered(rec, hit) =>
        val o = rec.output
        val strong = o.confidence >= policy.config.adjudication.minLlmConfidence
        val decided = o.decision match
          case "ACCEPT" if strong =>
            consulted.copy(
              decision = MatchOutcome.ACCEPTED,
              path = MatchPath.LLM_ADJUDICATED,
              confidence = o.confidence,
              rulesFired = base.rulesFired :+ Resolver.LlmRule,
              reasonCodes = base.reasonCodes ++ ("LLM_ACCEPT" +: o.reasonCodes.map("LLM_" + _))
            )
          case "REJECT" if strong =>
            consulted.copy(
              decision = MatchOutcome.REJECTED,
              path = MatchPath.LLM_ADJUDICATED,
              // Confidence is P(same entity) on every path; the reply's confidence is in its decision.
              confidence = 1.0 - o.confidence,
              rulesFired = base.rulesFired :+ Resolver.LlmRule,
              reasonCodes = base.reasonCodes ++ ("LLM_REJECT" +: o.reasonCodes.map("LLM_" + _))
            )
          case "ABSTAIN" => consulted.copy(reasonCodes = base.reasonCodes :+ "LLM_ABSTAINED")
          case _         => consulted.copy(reasonCodes = base.reasonCodes :+ "LLM_LOW_CONFIDENCE")
        (decided, Some(rec), Some(hit))

object Resolver:
  /** The feature that records the probabilistic score in every decision. */
  val ScoreFeature = "er_score"
  val LlmRule = "R-LLM"
