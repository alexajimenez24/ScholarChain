package scholarchain.core.aws

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scholarchain.core.Hashing

/** What to sign. `url` is absolute; `headers` are extra headers to sign (names any case). */
final case class SigningInput(
    method: String,
    url: String,
    headers: Map[String, String],
    body: Array[Byte]
)

/** Result of signing. `headers` is every header to send (lowercase names) including host,
  * x-amz-date, authorization and, if present, x-amz-security-token. `toString` prints no
  * credential, token or signature.
  */
final case class SignedRequest(
    headers: Map[String, String],
    canonicalRequest: String,
    stringToSign: String,
    signature: String,
    signedHeaders: String
):
  override def toString: String =
    s"SignedRequest(headerNames=${headers.keySet.toVector.sorted}, signedHeaders=$signedHeaders)"

/** AWS Signature Version 4 (header-based). Region and service are parameters. */
object SigV4:
  val Algorithm = "AWS4-HMAC-SHA256"
  private val AmzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
  private val DateStamp = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

  private def hmac(key: Array[Byte], data: String): Array[Byte] =
    val m = Mac.getInstance("HmacSHA256")
    m.init(new SecretKeySpec(key, "HmacSHA256"))
    m.doFinal(data.getBytes(UTF_8))

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  /** kSigning = HMAC(HMAC(HMAC(HMAC("AWS4"+secret, date), region), service), "aws4_request"). */
  def signingKey(secret: String, date: String, region: String, service: String): Array[Byte] =
    hmac(hmac(hmac(hmac(("AWS4" + secret).getBytes(UTF_8), date), region), service), "aws4_request")

  /** RFC 3986 unreserved characters stay; everything else is %XX (uppercase hex). */
  def uriEncode(s: String): String =
    val sb = new StringBuilder
    s.getBytes(UTF_8).foreach { b =>
      val c = (b & 0xff).toChar
      val plain = (c < 128 && c.isLetterOrDigit) || c == '-' || c == '_' || c == '.' || c == '~'
      if plain then sb += c else sb ++= f"%%${b & 0xff}%02X"
    }
    sb.toString

  private def decode(s: String): String =
    java.net.URLDecoder.decode(s.replace("+", "%2B"), UTF_8)

  /** Canonical URI: each segment decoded then encoded once (S3) or twice (other services). */
  def canonicalUri(rawPath: String, singleEncode: Boolean): String =
    val path = if rawPath == null || rawPath.isEmpty then "/" else rawPath
    path
      .split("/", -1)
      .map { seg =>
        val once = uriEncode(decode(seg))
        if singleEncode then once else uriEncode(once)
      }
      .mkString("/")

  /** Canonical query: decode, encode, sort by name then value. */
  def canonicalQuery(rawQuery: Option[String]): String =
    rawQuery.filter(_.nonEmpty).fold("") { q =>
      q.split("&", -1)
        .filter(_.nonEmpty)
        .map { kv =>
          val i = kv.indexOf('=')
          val (k, v) = if i < 0 then (kv, "") else (kv.take(i), kv.drop(i + 1))
          (uriEncode(decode(k)), uriEncode(decode(v)))
        }
        .sorted
        .map((k, v) => s"$k=$v")
        .mkString("&")
    }

  /** Sign a request at `now`. With `includeContentSha256`, adds and signs x-amz-content-sha256
    * (required by S3); the payload hash in the canonical request is always the body SHA-256.
    */
  def sign(
      in: SigningInput,
      creds: AwsCredentials,
      region: String,
      service: String,
      now: Instant,
      includeContentSha256: Boolean = false
  ): SignedRequest =
    val uri = URI.create(in.url)
    require(uri.getHost != null, "url must be absolute with a host")
    val host = if uri.getPort >= 0 then s"${uri.getHost}:${uri.getPort}" else uri.getHost
    val amzDate = AmzDate.format(now)
    val date = DateStamp.format(now)
    val payloadHash = Hashing.sha256Hex(in.body)

    val base = in.headers.map((k, v) => k.toLowerCase -> v.trim.replaceAll("\\s+", " ")) ++
      Map("host" -> host, "x-amz-date" -> amzDate) ++
      creds.sessionToken.map("x-amz-security-token" -> _) ++
      (if includeContentSha256 then Some("x-amz-content-sha256" -> payloadHash) else None)
    val sorted = base.toVector.sortBy(_._1)
    val canonicalHeaders = sorted.map((k, v) => s"$k:$v\n").mkString
    val signedHeaders = sorted.map(_._1).mkString(";")

    val creq = Vector(
      in.method.toUpperCase,
      canonicalUri(uri.getRawPath, singleEncode = service == "s3"),
      canonicalQuery(Option(uri.getRawQuery)),
      canonicalHeaders,
      signedHeaders,
      payloadHash
    ).mkString("\n")
    val scope = s"$date/$region/$service/aws4_request"
    val sts = Vector(Algorithm, amzDate, scope, Hashing.sha256Hex(creq)).mkString("\n")
    val sig = hex(hmac(signingKey(creds.secretAccessKey, date, region, service), sts))
    val auth =
      s"$Algorithm Credential=${creds.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$sig"
    SignedRequest(base + ("authorization" -> auth), creq, sts, sig, signedHeaders)
