package scholarchain.core.aws

import java.net.URI
import java.net.http as jnet
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[aws] object Redact:
  val SensitiveHeaders: Set[String] = Set("authorization", "x-amz-security-token")
  def headers(h: Map[String, String]): Map[String, String] =
    h.map((k, v) => k -> (if SensitiveHeaders(k.toLowerCase) then "***" else v))

/** An HTTP request value. `toString` redacts authorization and session-token headers. */
final case class HttpRequest(
    method: String,
    url: String,
    headers: Map[String, String],
    body: Array[Byte],
    timeout: Duration
):
  override def toString: String =
    s"HttpRequest($method $url, headers=${Redact.headers(headers)}, bodyBytes=${body.length}, timeout=$timeout)"

/** An HTTP response value; header names are lowercased. */
final case class HttpResponse(status: Int, body: String, headers: Map[String, String]):
  def ok: Boolean = status >= 200 && status < 300

/** Port over an HTTP client; `Left` carries a transport failure (no response at all). */
trait HttpTransport:
  def execute(request: HttpRequest): Either[String, HttpResponse]

/** The JDK `java.net.http.HttpClient`. No redirects, no retries; timeouts are parameters. The
  * `host`, `content-length`, `connection`, `expect` and `upgrade` headers are restricted by the
  * JDK, which derives them from the URI and body, so they are skipped here (SigV4 signs the same
  * host).
  */
final class JdkHttpTransport(connectTimeout: Duration = Duration.ofSeconds(5))
    extends HttpTransport:
  private val client = jnet.HttpClient
    .newBuilder()
    .connectTimeout(connectTimeout)
    .followRedirects(jnet.HttpClient.Redirect.NEVER)
    .build()
  private val Restricted = Set("host", "content-length", "connection", "expect", "upgrade")

  def execute(request: HttpRequest): Either[String, HttpResponse] =
    try
      val publisher =
        if request.body.isEmpty then jnet.HttpRequest.BodyPublishers.noBody()
        else jnet.HttpRequest.BodyPublishers.ofByteArray(request.body)
      val b = jnet.HttpRequest
        .newBuilder(URI.create(request.url))
        .timeout(request.timeout)
        .method(request.method.toUpperCase, publisher)
      request.headers.foreach((k, v) => if !Restricted(k.toLowerCase) then b.header(k, v))
      val r = client.send(b.build(), jnet.HttpResponse.BodyHandlers.ofString())
      val hs = r.headers().map().asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(","))
      Right(HttpResponse(r.statusCode(), r.body(), hs.toMap))
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("request interrupted")
      case NonFatal(e) =>
        Left(s"${request.method} ${hostOf(request.url)}: ${e.getClass.getSimpleName}")

  private def hostOf(url: String): String =
    scala.util.Try(URI.create(url).getAuthority).toOption.flatMap(Option(_)).getOrElse("?")

/** A response as the AWS adapters see it. */
type AwsResponse = HttpResponse

/** Signed AWS HTTP client.
  *
  * Retries are explicit: up to `maxAttempts` total attempts (default 1, so none) on a transport
  * failure or HTTP 429, 500, 502, 503 or 504, waiting `backoffMillis * 2^(attempt-1)` through
  * `sleeper` between attempts. A non-2xx response is returned as `Right` (callers read AWS error
  * bodies); only transport failure, unusable credentials or bad input is `Left`. Messages and `log`
  * lines never contain credentials, signatures or query strings.
  */
final class AwsHttp(
    region: String,
    credentials: CredentialsProvider,
    maxAttempts: Int = 1,
    sleeper: Long => Unit = ms => Thread.sleep(ms),
    backoffMillis: Long = 200L,
    requestTimeout: Duration = Duration.ofSeconds(30),
    transport: HttpTransport = new JdkHttpTransport(),
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    log: String => Unit = _ => ()
):
  require(maxAttempts >= 1, "maxAttempts must be at least 1")
  private val Retryable = Set(429, 500, 502, 503, 504)

  def send(
      method: String,
      url: String,
      service: String,
      body: Array[Byte],
      headers: Map[String, String] = Map.empty
  ): Either[String, AwsResponse] =
    val shown = url.takeWhile(_ != '?')
    def attempt(n: Int): Either[String, AwsResponse] =
      val credsResult = credentials.credentials()
      val result = credsResult.flatMap { creds =>
        val signed = SigV4.sign(
          SigningInput(method, url, headers, body),
          creds,
          region,
          service,
          clock.instant(),
          includeContentSha256 = service == "s3"
        )
        transport.execute(HttpRequest(method, url, signed.headers, body, requestTimeout))
      }
      result match
        case Right(r) =>
          log(s"$method $shown -> HTTP ${r.status} (attempt $n)")
          if Retryable(r.status) && n < maxAttempts then backoff(n, attempt(n + 1))
          else Right(r)
        case Left(e) =>
          log(s"$method $shown failed (attempt $n)")
          if n < maxAttempts && credsResult.isRight then backoff(n, attempt(n + 1))
          else Left(e)
    try attempt(1)
    catch
      case _: IllegalArgumentException =>
        Left(s"invalid request: $method ${shown.takeWhile(_ != '#')} (malformed URL)")

  private def backoff[A](n: Int, next: => A): A =
    sleeper(backoffMillis * (1L << (n - 1)))
    next

  /** POST a JSON body (content-type defaults to application/json) and parse a 2xx JSON reply. */
  def json(
      url: String,
      service: String,
      body: ujson.Value,
      headers: Map[String, String] = Map.empty
  ): Either[String, ujson.Value] =
    val hs = Map("content-type" -> "application/json") ++ headers.map((k, v) => k.toLowerCase -> v)
    send("POST", url, service, body.render().getBytes("UTF-8"), hs).flatMap { r =>
      if !r.ok then Left(s"$service HTTP ${r.status}: ${r.body.take(200)}")
      else if r.body.isEmpty then Right(ujson.Obj())
      else scala.util.Try(ujson.read(r.body)).toEither.left.map(_ => s"$service reply is not JSON")
    }
