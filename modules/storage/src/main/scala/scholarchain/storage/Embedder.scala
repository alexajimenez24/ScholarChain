package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.AwsHttp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** Turns text into a fixed-size float32 vector. `modelId` is the embedding model version that the
  * loaders record next to the text hash: a vector is skipped only when both are unchanged.
  */
trait Embedder:
  def modelId: String
  def dimension: Int
  def embed(text: String): Either[RemoteError, Vector[Float]]

/** Deterministic feature-hashing embedder for local mode and tests (model `hash-embed-v1`). It
  * captures token overlap only and is never reported as a semantic model.
  */
final class HashingEmbedder(val dimension: Int) extends Embedder:
  val modelId = "hash-embed-v1"
  def embed(text: String): Either[RemoteError, Vector[Float]] =
    val v = Array.fill(dimension)(0f)
    Normalize.title(text).split(' ').filter(_.length > 2).foreach { t =>
      val h = scala.util.hashing.MurmurHash3.stringHash(t)
      v(math.floorMod(h, dimension)) += (if ((h >> 16) & 1) == 0 then 1f else -1f)
    }
    val n = math.sqrt(v.map(x => x.toDouble * x).sum).toFloat
    Right(if n == 0 then v.toVector else v.map(_ / n).toVector)

/** Amazon Titan Text Embeddings V2 through Bedrock `InvokeModel` (signing name `bedrock`, endpoint
  * `bedrock-runtime.{region}.amazonaws.com`, `POST /model/{modelId}/invoke`).
  *
  * The model ID comes from `models.embeddingModel` and the dimension from `vectors.dimension`; use
  * [[BedrockEmbedder.fromConfig]]. Request: `inputText`, `dimensions`, `normalize = true`. The
  * reply must hold `embedding` with exactly `dimension` finite numbers. Text over `maxChars` is
  * refused, never truncated, so a vector always stands for the whole text it is filed under.
  *
  * Status mapping (InvokeModel errors): 408, 429, 500, 502, 503 and 504 and a missing response are
  * `Transient`; 400, 403, 404 and 424 are `Permanent`.
  */
final class BedrockEmbedder(
    http: AwsHttp,
    region: String,
    val modelId: String,
    val dimension: Int,
    maxChars: Int = BedrockEmbedder.DefaultMaxChars,
    endpointOverride: Option[String] = None
) extends Embedder:
  private val TransientStatus = Set(408, 429, 500, 502, 503, 504)
  private val base =
    endpointOverride
      .map(_.stripSuffix("/"))
      .getOrElse(s"https://bedrock-runtime.$region.amazonaws.com")
  private val url = s"$base/model/${URLEncoder.encode(modelId, UTF_8)}/invoke"

  def embed(text: String): Either[RemoteError, Vector[Float]] =
    if text.isEmpty then Left(RemoteError.Permanent("empty text"))
    else if text.length > maxChars then
      Left(RemoteError.Permanent(s"text has ${text.length} chars, the limit is $maxChars"))
    else
      http
        .send(
          "POST",
          url,
          "bedrock",
          ujson
            .Obj("inputText" -> text, "dimensions" -> dimension, "normalize" -> true)
            .render()
            .getBytes(UTF_8),
          Map("content-type" -> "application/json", "accept" -> "application/json")
        )
        .left
        .map(e => RemoteError.Transient(s"InvokeModel: $e"))
        .flatMap { r =>
          if r.ok then parse(r.body)
          else if TransientStatus(r.status) then
            Left(RemoteError.Transient(s"InvokeModel: HTTP ${r.status} ${r.body.take(200)}"))
          else Left(RemoteError.Permanent(s"InvokeModel: HTTP ${r.status} ${r.body.take(200)}"))
        }

  private def parse(body: String): Either[RemoteError, Vector[Float]] =
    Try(ujson.read(body)("embedding").arr.toVector.map(_.num.toFloat)).toEither.left
      .map(_ => RemoteError.Permanent("embedding reply without a vector"))
      .flatMap { v =>
        if v.size != dimension then
          Left(RemoteError.Permanent(s"embedding has ${v.size} values, expected $dimension"))
        else if v.exists(f => f.isNaN || f.isInfinite) then
          Left(RemoteError.Permanent("embedding has a non-finite value"))
        else Right(v)
      }

object BedrockEmbedder:
  /** Titan V2 takes up to 8,192 tokens; 20,000 characters is a conservative bound (the documented
    * character limit was not found, see the service limits note).
    */
  val DefaultMaxChars = 20000

  def fromConfig(
      http: AwsHttp,
      region: String,
      models: ModelConfig,
      vectors: VectorConfig,
      endpointOverride: Option[String] = None
  ): Either[String, BedrockEmbedder] =
    if models.embeddingModel.trim.isEmpty then Left("models.embeddingModel is not configured")
    else if vectors.dimension < 1 then Left("vectors.dimension must be positive")
    else
      Right(
        new BedrockEmbedder(
          http,
          region,
          models.embeddingModel,
          vectors.dimension,
          endpointOverride = endpointOverride
        )
      )
