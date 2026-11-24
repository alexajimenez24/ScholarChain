package scholarchain.er

import scholarchain.core.*
import upickle.default.ReadWriter

/** What an adjudicator sees: the two candidate IDs, the deterministic features and a closed set of
  * profile fields (names, titles, venues, years), all bounded. It is built from the records and is
  * never written back to them.
  */
final case class AdjudicationRequest(
    candidateId: String,
    kind: EntityKind,
    sourceId: String,
    targetId: String,
    policyVersion: String,
    score: Double,
    features: Map[String, Double],
    fields: Map[String, String]
):
  /** Canonical JSON of the whole input (keys sorted); the prompt embeds exactly this text. */
  lazy val inputJson: String =
    ujson
      .Obj(
        "candidateId" -> candidateId,
        "features" -> ujson.Obj
          .from(features.toVector.sortBy(_._1).map((k, v) => k -> ujson.Num(v))),
        "fields" -> ujson.Obj.from(fields.toVector.sortBy(_._1).map((k, v) => k -> ujson.Str(v))),
        "kind" -> kind.toString,
        "policyVersion" -> policyVersion,
        "score" -> score,
        "sourceId" -> sourceId,
        "targetId" -> targetId
      )
      .render()

  lazy val inputHash: String = Hashing.sha256Hex(inputJson)

  /** Names an answer may cite: the features and fields given, nothing else. */
  def citable: Set[String] = features.keySet ++ fields.keySet + "score"

/** The strict reply schema. The reply must be exactly one JSON object with exactly these keys. */
final case class AdjudicationOutput(
    decision: String,
    sourceId: String,
    targetId: String,
    confidence: Double,
    reasonCodes: Vector[String],
    citedFields: Vector[String]
) derives ReadWriter

/** A validated adjudication, as cached and as written to the run's `adjudications/` output. */
final case class AdjudicationRecord(
    cacheKey: String,
    inputHash: String,
    candidateId: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String,
    output: AdjudicationOutput
) derives ReadWriter

enum AdjudicatorMode:
  case OFF, FAKE, BEDROCK

object AdjudicatorMode:
  /** Why the match job refuses bedrock mode although [[BedrockAdjudicator]] exists. */
  val BedrockNotInJob =
    "the match job does not wire the bedrock adjudicator yet (task credentials and the cost " +
      "sign-off come with the cloud phases); use off or fake"

  /** The match job's `--adjudicator` values; bedrock is refused there, see [[BedrockNotInJob]]. */
  def parse(s: String): Either[String, AdjudicatorMode] = s match
    case "off"     => Right(OFF)
    case "fake"    => Right(FAKE)
    case "bedrock" => Left(BedrockNotInJob)
    case other     => Left(s"unknown adjudicator mode '$other' (off or fake)")

/** The adjudicator port: a prompt in, raw reply text out. Implementations never see or return
  * records; the reply is validated by [[Adjudication.validate]] before anything uses it.
  */
trait AdjudicatorPort:
  def mode: AdjudicatorMode
  def promptVersion: String
  def modelVersion: String
  def complete(prompt: String, request: AdjudicationRequest): Either[String, String]

/** Off: no model is consulted, so every band case stays UNRESOLVED. */
object OffAdjudicator extends AdjudicatorPort:
  val mode: AdjudicatorMode = AdjudicatorMode.OFF
  val promptVersion = "none"
  val modelVersion = "none"
  def complete(prompt: String, request: AdjudicationRequest): Either[String, String] =
    Left("adjudication is off")

/** Deterministic local stand-in for an LLM, for wiring tests and local runs without Bedrock. It
  * answers from the request features only, through the same JSON schema and validator a model would
  * face. Its decisions name model version `fake-deterministic-v1` so they can never pass for a
  * model's. The cutoffs are the fake's own behavior, not part of any policy.
  */
final class FakeAdjudicator(
    val promptVersion: String,
    acceptCoauthorOverlap: Double = 0.3,
    acceptTitleSimilarity: Double = 0.9,
    confidence: Double = 0.85
) extends AdjudicatorPort:
  val mode: AdjudicatorMode = AdjudicatorMode.FAKE
  val modelVersion = FakeAdjudicator.ModelVersion

  def complete(prompt: String, r: AdjudicationRequest): Either[String, String] =
    val f = (n: String) => r.features.getOrElse(n, 0.0)
    val (decision, code, cited) = r.kind match
      case EntityKind.RESEARCHER =>
        if f(Features.InitialsCompatible) < 1.0 then
          ("REJECT", "INITIALS_INCOMPATIBLE", Features.InitialsCompatible)
        else if f(Features.CoauthorOverlap) >= acceptCoauthorOverlap then
          ("ACCEPT", "SHARED_COAUTHORS", Features.CoauthorOverlap)
        else ("ABSTAIN", "INSUFFICIENT_EVIDENCE", Features.CoauthorOverlap)
      case EntityKind.WORK =>
        if f(Features.TitleSimilarity) >= acceptTitleSimilarity && f(Features.AuthorOverlap) >= 0.5
        then ("ACCEPT", "TITLE_AND_AUTHORS_AGREE", Features.TitleSimilarity)
        else ("ABSTAIN", "INSUFFICIENT_EVIDENCE", Features.TitleSimilarity)
    Right(
      upickle.default.write(
        AdjudicationOutput(
          decision,
          r.sourceId,
          r.targetId,
          confidence,
          Vector(code),
          Vector(cited)
        )
      )
    )

object FakeAdjudicator:
  val ModelVersion = "fake-deterministic-v1"

/** The versioned prompt in `src/main/resources/scholarchain/er/prompts/`. */
final case class PromptTemplate(version: String, text: String):
  def sha256: String = Hashing.sha256Hex(text)
  def render(r: AdjudicationRequest): String = text.replace(PromptTemplate.Slot, r.inputJson)

object PromptTemplate:
  val Version = "er-adjudication-v1"
  val Slot = "{{INPUT_JSON}}"

  def load(version: String = Version): Either[String, PromptTemplate] =
    val path = s"/scholarchain/er/prompts/$version.md"
    Option(getClass.getResourceAsStream(path))
      .toRight(s"prompt $path not found")
      .map { in =>
        try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        finally in.close()
      }
      .flatMap(t =>
        Either.cond(
          t.split(java.util.regex.Pattern.quote(Slot), -1).length == 2,
          PromptTemplate(version, t),
          s"prompt $version must hold $Slot exactly once"
        )
      )

enum AdjudicationResult:
  /** A validated reply (ACCEPT, REJECT or ABSTAIN), fresh or from the cache. */
  case Answered(record: AdjudicationRecord, fromCache: Boolean)

  /** No usable reply: off, budget exhausted, call failed or reply refused by the validator. */
  case Skipped(reason: String, detail: String)

object Adjudication:
  val Decisions: Set[String] = Set("ACCEPT", "REJECT", "ABSTAIN")
  val MaxReasonCodes = 8
  val MaxCitedFields = 16
  private val ReasonCode = "^[A-Z][A-Z_]{1,47}$".r

  /** Cache key: input hash, prompt version, model version and policy version together. */
  def cacheKey(
      inputHash: String,
      promptVersion: String,
      modelVersion: String,
      policy: String
  ): String =
    Hashing.sha256Hex(s"$inputHash|$promptVersion|$modelVersion|$policy")

  /** Validates a raw reply against its request. The reply must be one JSON object and nothing else
    * (no prose, no code fence), with exactly the schema keys; the decision must be ACCEPT, REJECT
    * or ABSTAIN; the two IDs must be the candidate's own (so no identifier can be created); reason
    * codes are upper-case letters and underscores only (so no identifier can hide in them); every
    * cited field must be one that was given.
    */
  def validate(raw: String, r: AdjudicationRequest): Either[String, AdjudicationOutput] =
    val text = raw.trim
    if !(text.startsWith("{") && text.endsWith("}")) then Left("reply is not a single JSON object")
    else
      JsonLines
        .decode[AdjudicationOutput](text)
        .left
        .map(e => s"reply breaks the schema: $e")
        .flatMap { o =>
          val errors = Vector(
            Option
              .when(!Decisions(o.decision))(s"decision '${o.decision.take(20)}' is not allowed"),
            Option.when(o.sourceId != r.sourceId || o.targetId != r.targetId)(
              "reply names identifiers that are not the candidate's"
            ),
            Option.when(o.confidence.isNaN || o.confidence < 0.0 || o.confidence > 1.0)(
              "confidence outside [0, 1]"
            ),
            Option.when(o.reasonCodes.isEmpty || o.reasonCodes.size > MaxReasonCodes)(
              s"reply needs 1 to $MaxReasonCodes reason codes"
            ),
            Option.when(o.reasonCodes.exists(c => !ReasonCode.matches(c)))(
              "reason codes must be upper-case letters and underscores"
            ),
            Option.when(o.citedFields.size > MaxCitedFields)("too many cited fields"),
            Option.when(o.decision != "ABSTAIN" && o.citedFields.isEmpty)(
              "an ACCEPT or REJECT must cite at least one field"
            ),
            Option.when(!o.citedFields.forall(r.citable))("reply cites fields that were not given")
          ).flatten
          if errors.isEmpty then Right(o) else Left(errors.mkString("; "))
        }

  /** Closed-vocabulary profile fields shown to the adjudicator, bounded in count and length. */
  def fields(
      left: EndpointProfile,
      right: EndpointProfile,
      p: AdjudicationPolicy
  ): Map[String, String] =
    def clip(xs: Iterable[String]): String =
      xs.iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toVector
        .distinct
        .take(p.maxListItems)
        .mkString(" | ")
        .take(p.maxFieldChars)
    def side(prefix: String, e: EndpointProfile): Vector[(String, String)] =
      e.work match
        case Some(w) =>
          Vector(
            s"$prefix.title" -> clip(Vector(w.title)),
            s"$prefix.authors" -> clip(w.authorNames),
            s"$prefix.venue" -> clip(w.venueName.toVector),
            s"$prefix.year" -> clip(w.year.map(_.toString).toVector),
            s"$prefix.source" -> w.source.toString
          )
        case None =>
          Vector(
            s"$prefix.names" -> clip(e.mentions.map(_.rawName)),
            s"$prefix.titles" -> clip(e.mentions.map(_.title)),
            s"$prefix.coauthors" -> clip(e.mentions.flatMap(_.coauthorNames)),
            s"$prefix.years" -> clip(e.mentions.flatMap(_.year).map(_.toString)),
            s"$prefix.sources" -> clip(e.mentions.map(_.source.toString))
          )
    (side("left", left) ++ side("right", right)).toMap

/** In-memory adjudication cache, optionally preloaded from earlier runs' `adjudications/` lines.
  * Only validated replies are cached; a refused reply or a failed call is retried in a later run.
  * Preloaded lines are not trusted: [[Adjudicator]] re-validates every hit before using it.
  */
final class AdjudicationCache(initial: Iterable[AdjudicationRecord] = Nil):
  private val entries = scala.collection.mutable.HashMap.from(initial.map(r => r.cacheKey -> r))
  def get(key: String): Option[AdjudicationRecord] = entries.get(key)
  def put(r: AdjudicationRecord): Unit = entries.update(r.cacheKey, r)
  def size: Int = entries.size

object AdjudicationCache:
  /** Reads cache lines; a line whose key does not match its own fields is refused. */
  def parse(lines: Iterable[String]): (Vector[AdjudicationRecord], Vector[String]) =
    val decoded = lines.iterator.filter(_.trim.nonEmpty).toVector.map { l =>
      JsonLines.decode[AdjudicationRecord](l).flatMap { r =>
        val k = Adjudication.cacheKey(r.inputHash, r.promptVersion, r.modelVersion, r.policyVersion)
        Either.cond(k == r.cacheKey, r, s"cache line ${r.cacheKey.take(12)} has a mismatched key")
      }
    }
    (decoded.collect { case Right(r) => r }, decoded.collect { case Left(e) => e })

/** Bounded adjudication: off mode answers nothing, a cache hit costs no call, and at most `budget`
  * port calls are made by this instance (one task).
  */
final class Adjudicator(
    port: AdjudicatorPort,
    template: PromptTemplate,
    cache: AdjudicationCache,
    budget: Int,
    policyVersion: String
):
  private var calls = 0
  def callsMade: Int = calls
  def mode: AdjudicatorMode = port.mode
  def promptVersion: String = port.promptVersion
  def modelVersion: String = port.modelVersion

  def keyOf(r: AdjudicationRequest): String =
    Adjudication.cacheKey(r.inputHash, port.promptVersion, port.modelVersion, policyVersion)

  /** A cached record is trusted no more than a fresh reply: it must belong to this request under
    * this prompt, model and policy, and its output must pass [[Adjudication.validate]] against the
    * current request, exactly as a fresh reply would.
    */
  def recheck(hit: AdjudicationRecord, key: String, r: AdjudicationRequest): Either[String, Unit] =
    val mismatches = Vector(
      Option.when(hit.cacheKey != key)("cache key"),
      Option.when(hit.inputHash != r.inputHash)("input hash"),
      Option.when(hit.candidateId != r.candidateId)("candidate ID"),
      Option.when(hit.promptVersion != port.promptVersion)("prompt version"),
      Option.when(hit.modelVersion != port.modelVersion)("model version"),
      Option.when(hit.policyVersion != policyVersion)("policy version")
    ).flatten
    if mismatches.nonEmpty then
      Left(s"cached record does not match the request: ${mismatches.mkString(", ")}")
    else
      Adjudication
        .validate(upickle.default.write(hit.output), r)
        .left
        .map(e => s"cached reply refused: $e")
        .map(_ => ())

  def adjudicate(r: AdjudicationRequest): AdjudicationResult =
    if port.mode == AdjudicatorMode.OFF then AdjudicationResult.Skipped("LLM_OFF", "")
    else
      val key = keyOf(r)
      cache.get(key) match
        case Some(hit) =>
          // A refused cache entry is not used and costs no call; it leaves the case UNRESOLVED.
          recheck(hit, key, r) match
            case Left(e)  => AdjudicationResult.Skipped("LLM_INVALID_OUTPUT", e.take(200))
            case Right(_) => AdjudicationResult.Answered(hit, fromCache = true)
        case None if calls >= budget =>
          AdjudicationResult.Skipped("LLM_BUDGET_EXHAUSTED", s"budget $budget")
        case None =>
          calls += 1
          port.complete(template.render(r), r) match
            case Left(e)    => AdjudicationResult.Skipped("LLM_CALL_FAILED", e.take(200))
            case Right(raw) =>
              Adjudication.validate(raw, r) match
                case Left(e)  => AdjudicationResult.Skipped("LLM_INVALID_OUTPUT", e.take(200))
                case Right(o) =>
                  val rec = AdjudicationRecord(
                    key,
                    r.inputHash,
                    r.candidateId,
                    port.promptVersion,
                    port.modelVersion,
                    policyVersion,
                    o
                  )
                  cache.put(rec)
                  AdjudicationResult.Answered(rec, fromCache = false)
