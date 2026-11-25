package scholarchain.rag

import scholarchain.core.*
import scholarchain.core.aws.{AwsHttp, CredentialsProvider, HttpTransport, JdkHttpTransport}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Clock, Duration}
import scala.util.control.NonFatal

/** A model reply: its text and the token usage the model reported, if any. */
final case class LlmReply(text: String, inputTokens: Option[Long], outputTokens: Option[Long])

/** The model port of the synthesis stage. It only turns a rendered prompt into reply text; the
  * caller validates the text, spends the budgets and decides on retries. `complete` never throws:
  * every failure is a `Left` whose message holds no reply text and no credential.
  */
trait LlmPort:
  def modelVersion: String
  def complete(prompt: String, maxOutputTokens: Int): Either[String, LlmReply]

/** A scripted fake model for local runs and tests: step `i` answers the `i`-th call (from the
  * prompt it is given); a call past the script is a failure. It records every prompt it received.
  */
final class ScriptedLlm(
    steps: Vector[String => Either[String, LlmReply]],
    val modelVersion: String = ScriptedLlm.Version
) extends LlmPort:
  private val seen = scala.collection.mutable.ArrayBuffer.empty[(String, Int)]

  def prompts: Vector[String] = synchronized(seen.map(_._1).toVector)
  def maxTokens: Vector[Int] = synchronized(seen.map(_._2).toVector)

  def complete(prompt: String, maxOutputTokens: Int): Either[String, LlmReply] =
    val i = synchronized { seen += prompt -> maxOutputTokens; seen.size - 1 }
    if i < steps.size then steps(i)(prompt) else Left("the script has no more replies")

object ScriptedLlm:
  val Version = "scripted-fake-v1"

  /** Replies with these texts in order, reporting estimated token usage. */
  def replies(texts: String*): ScriptedLlm =
    new ScriptedLlm(
      texts.toVector.map(t =>
        (p: String) =>
          Right(LlmReply(t, Some(Tokens.estimate(p).toLong), Some(Tokens.estimate(t).toLong)))
      )
    )

/** Amazon Bedrock behind [[LlmPort]], through bedrock-runtime `InvokeModel` (`POST
  * /model/{modelId}/invoke`, SigV4 signing name `bedrock`) with the Anthropic Messages body: one
  * user message, temperature 0, bounded `max_tokens`, no system tools and no extended thinking.
  *
  * The model ID is `models.synthesisModel` and the region `application.region`; neither is written
  * in code, and choosing them (and the per-query cost they imply) is the human's decision. A reply
  * is accepted only with exactly one text block and a stop reason other than `max_tokens`; a reply
  * with any reasoning block is refused, so reasoning is never read, kept or logged. Usage is read
  * from `usage.input_tokens` and `usage.output_tokens`. Retries are the injected [[AwsHttp]]'s
  * (none by default); output retries belong to [[LlmSynthesizer]].
  */
final class BedrockLlm private (http: AwsHttp, val modelVersion: String, base: String)
    extends LlmPort:
  val endpoint: String = s"$base/model/${URLEncoder.encode(modelVersion, UTF_8)}/invoke"
  private val headers = Map("content-type" -> "application/json", "accept" -> "application/json")

  def requestBody(prompt: String, maxOutputTokens: Int): Array[Byte] =
    ujson
      .Obj(
        "anthropic_version" -> BedrockLlm.AnthropicVersion,
        "max_tokens" -> maxOutputTokens,
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

  def complete(prompt: String, maxOutputTokens: Int): Either[String, LlmReply] =
    if maxOutputTokens < 1 then Left("maxOutputTokens must be at least 1")
    else
      try
        http
          .send(
            "POST",
            endpoint,
            BedrockLlm.SigningName,
            requestBody(prompt, maxOutputTokens),
            headers
          )
          .left
          .map(e => s"InvokeModel failed: ${e.take(160)}")
          .flatMap { r =>
            if r.ok then BedrockLlm.reply(r.body)
            else
              val kind = r.headers
                .get("x-amzn-errortype")
                .map(t => s" ${t.takeWhile(_ != ':').take(80)}")
                .getOrElse("")
              Left(s"InvokeModel HTTP ${r.status}$kind")
          }
      catch case NonFatal(e) => Left(s"InvokeModel failed: ${e.getClass.getSimpleName}")

  override def toString: String = s"BedrockLlm($modelVersion)"

object BedrockLlm:
  val SigningName = "bedrock"
  val AnthropicVersion = "bedrock-2023-05-31"
  private val AnthropicFamily = "(^|[./:])anthropic\\.".r

  /** Refused when `models.llmEnabled` is false, the model or region is empty, or the model is not
    * of the Anthropic family (the only body this adapter speaks). `endpointOverride` replaces
    * `https://bedrock-runtime.<region>.amazonaws.com`, for a local stub.
    */
  def fromConfig(
      cfg: ScholarChainConfig,
      credentials: CredentialsProvider,
      maxAttempts: Int = 1,
      requestTimeout: Duration = Duration.ofSeconds(10),
      transport: HttpTransport = new JdkHttpTransport(),
      clock: Clock = Clock.systemUTC(),
      endpointOverride: Option[String] = None
  ): Either[String, BedrockLlm] =
    val model = cfg.models.synthesisModel.trim
    val region = cfg.application.region.trim
    if !cfg.models.llmEnabled then Left("models.llmEnabled is false; no model is called")
    else if model.isEmpty then Left("models.synthesisModel is not configured")
    else if region.isEmpty then Left("application.region is not configured")
    else if AnthropicFamily.findFirstIn(model).isEmpty then
      Left(s"models.synthesisModel $model is not an Anthropic model; no other body is supported")
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
      Right(new BedrockLlm(http, model, base))

  /** The reply text and usage of an InvokeModel Messages reply. */
  def reply(body: String): Either[String, LlmReply] =
    scala.util.Try(ujson.read(body)).toOption match
      case Some(ujson.Obj(r)) =>
        val stop = r.get("stop_reason").flatMap(_.strOpt)
        def usage(k: String) =
          r.get("usage").flatMap(_.objOpt).flatMap(_.get(k)).flatMap(_.numOpt).map(_.toLong)
        r.get("content") match
          case Some(ujson.Arr(blocks)) =>
            val types = blocks.toVector.map(_.objOpt.flatMap(_.get("type")).flatMap(_.strOpt))
            if types.exists(t => t.exists(x => x == "thinking" || x == "redacted_thinking")) then
              Left("InvokeModel reply contains a reasoning block; refused")
            else
              blocks.toVector match
                case Vector(ujson.Obj(b))
                    if b.get("type").flatMap(_.strOpt).contains("text") &&
                      b.get("text").exists(_.strOpt.isDefined) =>
                  if stop.contains("max_tokens") then
                    Left("InvokeModel reply stopped at max_tokens")
                  else Right(LlmReply(b("text").str, usage("input_tokens"), usage("output_tokens")))
                case other =>
                  Left(s"InvokeModel reply needs exactly one text block (has ${other.size})")
          case _ => Left("InvokeModel reply has no content array")
      case Some(_) => Left("InvokeModel reply is not a JSON object")
      case None    => Left("InvokeModel reply is not JSON")
