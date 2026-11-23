package scholarchain.er

import scholarchain.core.*
import upickle.default.ReadWriter

/** Accept, reject and LLM-band thresholds. A score at or above `accept` is ACCEPTED, at or below
  * `reject` is REJECTED, and anything between is UNRESOLVED; only scores in `[llmBandMin,
  * llmBandMax)` may go to LLM adjudication.
  */
final case class Thresholds(accept: Double, reject: Double, llmBandMin: Double, llmBandMax: Double)
    derives ReadWriter:
  def errors: Vector[String] =
    Vector(
      Option.when(!Vector(accept, reject, llmBandMin, llmBandMax).forall(v => v >= 0 && v <= 1))(
        "thresholds must lie in [0, 1]"
      ),
      Option.when(!(reject < llmBandMin && llmBandMin <= llmBandMax && llmBandMax <= accept))(
        "thresholds must satisfy reject < llmBandMin <= llmBandMax <= accept"
      )
    ).flatten

  def inBand(score: Double): Boolean = score >= llmBandMin && score < llmBandMax

object Thresholds:
  def from(c: EntityResolutionConfig): Thresholds =
    Thresholds(c.acceptThreshold, c.rejectThreshold, c.llmBandMin, c.llmBandMax)

/** One identifier scheme's role for one entity kind. `authoritative`: a disagreement is CONFLICT
  * evidence. `minCorroboration`: an agreement accepts only when the corroborating field (name
  * similarity for researchers, title similarity for works) reaches this value.
  */
final case class SchemeRule(scheme: IdScheme, authoritative: Boolean, minCorroboration: Double)
    derives ReadWriter

final case class ResearcherPolicy(
    schemes: Vector[SchemeRule],
    exactAcceptConfidence: Double,
    declaredOnlyConfidence: Double,
    sourceClusterConfidence: Double,
    sharedWorkConfidence: Double,
    homonymConfidence: Double,
    weights: Map[String, Double],
    nameFloor: Double,
    yearGapFree: Double,
    yearGapCap: Double
) derives ReadWriter

final case class BibliographicRule(
    minTitleTokens: Int,
    maxYearDistance: Double,
    minAuthorSequence: Double
) derives ReadWriter

final case class WorkPolicy(
    schemes: Vector[SchemeRule],
    exactAcceptConfidence: Double,
    bibliographicConfidence: Double,
    bibliographic: BibliographicRule,
    weights: Map[String, Double],
    yearDistanceFree: Double,
    yearDistanceCap: Double
) derives ReadWriter

final case class AdjudicationPolicy(
    minLlmConfidence: Double,
    maxFieldChars: Int,
    maxListItems: Int
) derives ReadWriter

/** The versioned scoring policy, read from `/scholarchain/er/er-policy-v1.json`. Its weights and
  * rule parameters were set by hand; er-v1 was fixed before the gold file existed and was not
  * changed afterwards. Its thresholds are pinned, so the loaded configuration must carry exactly
  * these values. A change to anything here is a new policy version, never an edit of er-v1.
  */
final case class PolicyConfig(
    policyVersion: String,
    featureVersion: String,
    normalizationVersion: String,
    thresholds: Thresholds,
    sourceReliability: Map[String, Double],
    preprintDoiPrefixes: Vector[String],
    conflictConfidence: Double,
    limits: ProfileLimits,
    researcher: ResearcherPolicy,
    work: WorkPolicy,
    adjudication: AdjudicationPolicy
) derives ReadWriter:
  def reliability(s: SourceType): Double = sourceReliability.getOrElse(s.toString, 0.0)
  def isPreprintDoi(doi: String): Boolean = preprintDoiPrefixes.exists(doi.startsWith)

object PolicyConfig:
  val Resource = "/scholarchain/er/er-policy-v1.json"

  val ResearcherWeights: Set[String] = Set(
    "bias",
    "nameAboveFloor",
    "sameFullName",
    "initialsIncompatible",
    "coauthorOverlap",
    "affiliationOverlap",
    "affiliationDisjoint",
    "venueOverlap",
    "topicOverlap",
    "authorPositionAgree",
    "openalexConflict",
    "s2Conflict",
    "yearGapPerYear",
    "citationOverlap",
    "unreliability"
  )

  val WorkWeights: Set[String] = Set(
    "bias",
    "titleSimilarity",
    "authorOverlap",
    "authorSequence",
    "venueCompatible",
    "yearDistancePerYear",
    "openalexConflict",
    "s2Conflict",
    "repoConflict",
    "citationOverlap",
    "unreliability"
  )

  def resourceText(resource: String = Resource): Either[String, String] =
    Option(getClass.getResourceAsStream(resource))
      .toRight(s"policy resource $resource not found")
      .map { in =>
        try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        finally in.close()
      }

  def parse(json: String): Either[String, PolicyConfig] =
    // Strict: an unknown or missing key is an error, so a typo cannot fall back silently.
    JsonLines
      .decode[PolicyConfig](json)
      .left
      .map(e => s"policy config: $e")
      .flatMap(p => Option.when(validate(p).nonEmpty)(validate(p).mkString("; ")).toLeft(p))

  def load(resource: String = Resource): Either[String, PolicyConfig] =
    resourceText(resource).flatMap(parse)

  def validate(p: PolicyConfig): Vector[String] =
    def unit(v: Double) = v >= 0 && v <= 1
    Vector(
      Option.when(p.policyVersion.isBlank || p.featureVersion.isBlank)("versions must be set"),
      Option.when(p.normalizationVersion != ErNormalize.Version)(
        s"policy names normalization ${p.normalizationVersion}, code is ${ErNormalize.Version}"
      ),
      Option.when(p.featureVersion != Features.Version)(
        s"policy names features ${p.featureVersion}, code is ${Features.Version}"
      ),
      Option.when(p.researcher.weights.keySet != ResearcherWeights)(
        s"researcher weights must be exactly ${ResearcherWeights.toVector.sorted.mkString(",")}"
      ),
      Option.when(p.work.weights.keySet != WorkWeights)(
        s"work weights must be exactly ${WorkWeights.toVector.sorted.mkString(",")}"
      ),
      Option.when(
        !SourceType.values.forall(s => p.sourceReliability.get(s.toString).exists(unit)) ||
          p.sourceReliability.keySet != SourceType.values.map(_.toString).toSet
      )("sourceReliability needs a value in [0, 1] for every source type"),
      Option.when(
        !Vector(
          p.conflictConfidence,
          p.researcher.exactAcceptConfidence,
          p.researcher.declaredOnlyConfidence,
          p.researcher.sourceClusterConfidence,
          p.researcher.sharedWorkConfidence,
          p.researcher.homonymConfidence,
          p.work.exactAcceptConfidence,
          p.work.bibliographicConfidence,
          p.adjudication.minLlmConfidence
        ).forall(unit)
      )("confidences must lie in [0, 1]"),
      Option.when(
        (p.researcher.schemes ++ p.work.schemes).exists(r => !unit(r.minCorroboration)) ||
          p.researcher.schemes.map(_.scheme).distinct.size != p.researcher.schemes.size ||
          p.work.schemes.map(_.scheme).distinct.size != p.work.schemes.size
      )("scheme rules must be distinct with minCorroboration in [0, 1]"),
      Option.when(
        Vector(
          p.limits.maxAuthorsPerWork,
          p.limits.maxCoauthorsPerMention,
          p.limits.maxUrlsPerWork,
          p.limits.maxMentionsPerEndpoint,
          p.adjudication.maxFieldChars,
          p.adjudication.maxListItems,
          p.work.bibliographic.minTitleTokens
        ).exists(_ < 1)
      )("limits must be >= 1")
    ).flatten ++ p.thresholds.errors

  /** The thresholds in force: those of the loaded configuration, which must name this policy and
    * equal its pinned thresholds. An environment override cannot retune er-v1 silently.
    */
  def thresholdsFor(p: PolicyConfig, c: EntityResolutionConfig): Either[String, Thresholds] =
    val t = Thresholds.from(c)
    if c.policyVersion != p.policyVersion then
      Left(
        s"configuration names policy ${c.policyVersion}, the module implements ${p.policyVersion}"
      )
    else if t != p.thresholds then
      Left(
        s"configured thresholds $t differ from the pinned ${p.policyVersion} thresholds " +
          s"${p.thresholds}; a threshold change needs a new policy version"
      )
    else Right(t)
