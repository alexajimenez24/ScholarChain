package scholarchain.storage

import scholarchain.core.Hashing
import scholarchain.core.aws.AwsHttp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** `s3://bucket/key` split into its parts. */
object S3Uri:
  private val Pattern = "^s3://([a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9])/(.+)$".r
  def parse(uri: String): Either[String, (String, String)] = uri match
    case Pattern(b, k) => Right(b -> k)
    case _             => Left(s"not an s3:// URI: $uri")
  def encodeKey(key: String): String =
    key.split("/", -1).map(s => URLEncoder.encode(s, UTF_8).replace("+", "%20")).mkString("/")

/** Ordinary S3 over the signed `AwsHttp` client (service `s3`).
  *
  * Create-only writes use `If-None-Match: *`: 200 is `Created`, 412 `AlreadyExists`; 409 (a
  * concurrent delete) is a retryable failure, never reported as success
  * (docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html).
  *
  * BINARY LIMIT: `HttpResponse.body` is a String, so `get` decodes the body as UTF-8 and encodes it
  * back. Only valid UTF-8 text (JSON, JSON lines, text) round-trips; a binary payload is
  * unsupported. `put` sends the exact bytes and stamps `x-amz-meta-sha256`; `get` recomputes the
  * hash of the decoded bytes and fails with an explicit message when it differs, so a binary object
  * is detected and never returned silently corrupted. Parquet and gzip are read by Spark and the
  * Neptune bulk loader, not through this port.
  *
  * `endpointOverride` (a base URL such as `http://127.0.0.1:port`) switches to path-style
  * addressing for the local stub; the default is virtual-hosted style in the configured region.
  */
final class S3ObjectStore(http: AwsHttp, region: String, endpointOverride: Option[String] = None)
    extends ObjectStore:
  private val HashHeader = "x-amz-meta-sha256"

  private def base(bucket: String) = endpointOverride match
    case Some(e) => s"${e.stripSuffix("/")}/$bucket"
    case None    => s"https://$bucket.s3.$region.amazonaws.com"
  private def objectUrl(bucket: String, key: String) = s"${base(bucket)}/${S3Uri.encodeKey(key)}"

  def putIfAbsent(
      uri: String,
      bytes: Array[Byte],
      contentType: String
  ): Either[String, PutOutcome] =
    S3Uri.parse(uri).flatMap { (b, k) =>
      http
        .send(
          "PUT",
          objectUrl(b, k),
          "s3",
          bytes,
          Map(
            "content-type" -> contentType,
            "if-none-match" -> "*",
            HashHeader -> Hashing.sha256Hex(bytes)
          )
        )
        .flatMap { r =>
          if r.ok then Right(PutOutcome.Created)
          else if r.status == 412 then Right(PutOutcome.AlreadyExists)
          else if r.status == 409 then Left(s"S3 PUT $uri: HTTP 409 conflict, retry")
          else Left(s"S3 PUT $uri: HTTP ${r.status}")
        }
    }

  def get(uri: String): Either[String, Array[Byte]] =
    S3Uri
      .parse(uri)
      .flatMap((b, k) => http.send("GET", objectUrl(b, k), "s3", Array.emptyByteArray))
      .flatMap { r =>
        if !r.ok then Left(s"S3 GET $uri: HTTP ${r.status}")
        else
          val bytes = r.body.getBytes(UTF_8)
          r.headers.get(HashHeader) match
            case Some(h) if h != Hashing.sha256Hex(bytes) =>
              Left(s"S3 GET $uri: body is not UTF-8 text (binary payloads are unsupported)")
            case _ => Right(bytes)
      }

  private val KeyTag = "<Key>([^<]*)</Key>".r
  private val NextToken = "<NextContinuationToken>([^<]+)</NextContinuationToken>".r
  private def unescape(s: String) =
    s.replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&amp;", "&")

  /** ListObjectsV2 under a prefix, all pages, as s3:// URIs. */
  def list(prefixUri: String): Either[String, Vector[String]] =
    S3Uri.parse(prefixUri.stripSuffix("/") + "/").flatMap { (bucket, prefix) =>
      def page(token: Option[String], acc: Vector[String]): Either[String, Vector[String]] =
        val q = s"list-type=2&prefix=${URLEncoder.encode(prefix, UTF_8)}" +
          token.fold("")(t => s"&continuation-token=${URLEncoder.encode(t, UTF_8)}")
        http.send("GET", s"${base(bucket)}/?$q", "s3", Array.emptyByteArray).flatMap { r =>
          if !r.ok then Left(s"S3 list $prefixUri: HTTP ${r.status}")
          else
            val keys = KeyTag
              .findAllMatchIn(r.body)
              .map(m => s"s3://$bucket/${unescape(m.group(1))}")
              .toVector
            NextToken.findFirstMatchIn(r.body).map(m => unescape(m.group(1))) match
              case Some(t) => page(Some(t), acc ++ keys)
              case None    => Right((acc ++ keys).sorted)
        }
      page(None, Vector.empty)
    }
