package scholarchain.er

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Clock, Duration}

import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.core.aws.{AwsHttp, CredentialsProvider, HttpTransport, JdkHttpTransport}

/** Amazon Bedrock adjudicator behind [[AdjudicatorPort]], through the bedrock-runtime `InvokeModel`
  * operation (`POST /model/{modelId}/invoke`, SigV4 signing name `bedrock`) with the Anthropic
  * Messages body (`anthropic_version` [[BedrockAdjudicator.AnthropicVersion]], temperature 0,
  * bounded `max_tokens`).
  *
  * Like every port it only turns a rendered prompt into raw reply text; [[Adjudicator]] validates
  * that text with the strict schema and the no-new-identifier rule, keys the cache and spends the
  * budget. Build it with [[BedrockAdjudicator.fromConfig]]: the model ID is `models.synthesisModel`
  * and the region `application.region`, so neither is written in code. Its `modelVersion` is the
  * model ID, which therefore enters the cache key unchanged.
  *
  * `complete` never throws. Every failure is a `Left`, which [[Adjudicator]] turns into
  * `LLM_CALL_FAILED` (the case stays UNRESOLVED and nothing is cached):
  *   - no credentials, a transport failure or a timeout (no HTTP response);
  *   - any non-2xx status (the message names the status and the AWS error type, never the body);
  *   - a 2xx body that is not JSON, has no `content`, has other than exactly one text block, or
  *     stopped at `max_tokens` (a truncated reply). A well-formed envelope gives `Right(text)`, and
  *     a text that breaks the reply schema becomes `LLM_INVALID_OUTPUT` in [[Adjudicator]].
  *
  * Retries are the injected [[AwsHttp]]'s (none by default), so one port call, and one unit of the
  * budget, may cover several HTTP attempts.
  */
final class BedrockAdjudicator private (
    http: AwsHttp,
    val modelVersion: String,
    val promptVersion: String,
    val maxTokens: Int,
    base: String
) extends AdjudicatorPort:
  val mode: AdjudicatorMode = AdjudicatorMode.BEDROCK

  /** The InvokeModel URL; the model ID is one percent-encoded path segment. */
  val endpoint: String = s"$base/model/${URLEncoder.encode(modelVersion, UTF_8)}/invoke"

  private val headers = Map("content-type" -> "application/json", "accept" -> "application/json")

  def complete(prompt: String, request: AdjudicationRequest): Either[String, String] =
    try
      http
        .send("POST", endpoint, BedrockAdjudicator.SigningName, requestBody(prompt), headers)
        .left
        .map(e => s"InvokeModel failed: ${e.take(160)}")
        .flatMap { r =>
          if r.ok then BedrockAdjudicator.replyText(r.body)
          else
            val kind = r.headers
              .get("x-amzn-errortype")
              .map(t => s" ${t.takeWhile(_ != ':').take(80)}")
              .getOrElse("")
            Left(s"InvokeModel HTTP ${r.status}$kind")
        }
    catch case NonFatal(e) => Left(s"InvokeModel failed: ${e.getClass.getSimpleName}")

  /** The InvokeModel body: one user message holding the rendered prompt, nothing else. */
  def requestBody(prompt: String): Array[Byte] =
    ujson
      .Obj(
        "anthropic_version" -> BedrockAdjudicator.AnthropicVersion,
        "max_tokens" -> maxTokens,
        "temperature" -> 0,
        "messages" -> ujson.Arr(
          ujson.Obj(
            "role" -> "user",
            "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> prompt))
          )
        )
      )
      .render()
      .getBytes(UTF_8)

  override def toString: String = s"BedrockAdjudicator($modelVersion, $promptVersion)"

object BedrockAdjudicator:
  val SigningName = "bedrock"
  val AnthropicVersion = "bedrock-2023-05-31"

  /** Enough for the largest valid reply (8 reason codes and 16 cited fields). */
  val DefaultMaxTokens = 512

  /** Anthropic model IDs, cross-region inference profile IDs and ARNs naming either. */
  private val AnthropicFamily = "(^|[./:])anthropic\\.".r

  /** Builds the adapter from configuration. Refused (`Left`) when `models.llmEnabled` is false,
    * when `models.synthesisModel` or `application.region` is empty, when the model is not of the
    * Anthropic family (the only request body this adapter speaks), or when `maxTokens < 1`.
    * `endpointOverride` replaces the regional `https://bedrock-runtime.<region>.amazonaws.com`
    * base, for a local stub.
    */
  def fromConfig(
      cfg: ScholarChainConfig,
      template: PromptTemplate,
      credentials: CredentialsProvider,
      maxTokens: Int = DefaultMaxTokens,
      maxAttempts: Int = 1,
      requestTimeout: Duration = Duration.ofSeconds(30),
      transport: HttpTransport = new JdkHttpTransport(),
      clock: Clock = Clock.systemUTC(),
      endpointOverride: Option[String] = None
  ): Either[String, BedrockAdjudicator] =
    val model = cfg.models.synthesisModel.trim
    val region = cfg.application.region.trim
    if !cfg.models.llmEnabled then Left("models.llmEnabled is false; Bedrock adjudication is off")
    else if model.isEmpty then Left("models.synthesisModel is not configured")
    else if region.isEmpty then Left("application.region is not configured")
    else if AnthropicFamily.findFirstIn(model).isEmpty then
      Left(s"models.synthesisModel $model is not an Anthropic model; no other body is supported")
    else if maxTokens < 1 then Left("maxTokens must be at least 1")
    else if maxAttempts < 1 then Left("maxAttempts must be at least 1")
    else
      val http = new AwsHttp(
        region,
        credentials,
        maxAttempts = maxAttempts,
        requestTimeout = requestTimeout,
        transport = transport,
        clock = clock
      )
      val base = endpointOverride
        .map(_.stripSuffix("/"))
        .getOrElse(s"https://bedrock-runtime.$region.amazonaws.com")
      Right(new BedrockAdjudicator(http, model, template.version, maxTokens, base))

  /** The model's text from an InvokeModel Messages reply: exactly one block, of type text, and a
    * stop reason other than `max_tokens`.
    */
  def replyText(body: String): Either[String, String] =
    scala.util.Try(ujson.read(body)).toOption match
      case None                   => Left("InvokeModel reply is not JSON")
      case Some(ujson.Obj(reply)) =>
        val stop = reply.get("stop_reason").flatMap(_.strOpt)
        reply.get("content") match
          case Some(ujson.Arr(blocks)) =>
            blocks.toVector match
              case Vector(ujson.Obj(b))
                  if b.get("type").flatMap(_.strOpt).contains("text") &&
                    b.get("text").exists(_.strOpt.isDefined) =>
                if stop.contains("max_tokens") then Left("InvokeModel reply stopped at max_tokens")
                else Right(b("text").str)
              case other =>
                Left(s"InvokeModel reply needs exactly one text block (has ${other.size} blocks)")
          case _ => Left("InvokeModel reply has no content array")
      case Some(_) => Left("InvokeModel reply is not a JSON object")
