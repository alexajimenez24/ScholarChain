package scholarchain.core.aws

import java.time.{Clock, Duration, Instant}
import scala.util.Try

/** AWS credentials. `toString` never prints the secret key or session token. */
final case class AwsCredentials(
    accessKeyId: String,
    secretAccessKey: String,
    sessionToken: Option[String] = None,
    expiration: Option[Instant] = None
):
  override def toString: String =
    val token = if sessionToken.isDefined then "***" else "none"
    val exp = expiration.fold("none")(_.toString)
    s"AwsCredentials(accessKeyId=$accessKeyId, secretAccessKey=***, sessionToken=$token, expiration=$exp)"

/** Port: where signed clients get credentials. Implementations must not log secrets. */
trait CredentialsProvider:
  def credentials(): Either[String, AwsCredentials]

/** Fixed credentials, for tests and for callers that already hold a resolved value. */
final class StaticCredentials(value: AwsCredentials) extends CredentialsProvider:
  def credentials(): Either[String, AwsCredentials] = Right(value)
  override def toString: String = s"StaticCredentials($value)"

/** Resolves credentials from an injected environment map (never `sys.env` implicitly).
  *
  * Order: (1) the standard access key id and secret key variables, plus AWS_SESSION_TOKEN; (2) the
  * container credentials endpoint named by AWS_CONTAINER_CREDENTIALS_RELATIVE_URI (appended to
  * `containerBase`) or AWS_CONTAINER_CREDENTIALS_FULL_URI, with the optional
  * AWS_CONTAINER_AUTHORIZATION_TOKEN sent as the Authorization header. Container credentials are
  * cached until `refreshBefore` ahead of their expiry. `containerBase` is the ECS-defined
  * link-local host and is a parameter so tests point it at a localhost stub.
  */
final class EnvironmentCredentials(
    env: Map[String, String],
    transport: HttpTransport = new JdkHttpTransport(),
    containerBase: String = EnvironmentCredentials.EcsContainerBase,
    clock: Clock = Clock.systemUTC(),
    refreshBefore: Duration = Duration.ofMinutes(5),
    timeout: Duration = Duration.ofSeconds(5)
) extends CredentialsProvider:
  private var cached: Option[AwsCredentials] = None

  def credentials(): Either[String, AwsCredentials] = synchronized {
    cached.filter(fresh) match
      case Some(c) => Right(c)
      case None    =>
        resolve().map { c =>
          cached = Some(c)
          c
        }
  }

  override def toString: String = "EnvironmentCredentials(***)"

  private def fresh(c: AwsCredentials): Boolean =
    c.expiration.forall(_.minus(refreshBefore).isAfter(clock.instant()))

  private def nonEmpty(k: String): Option[String] = env.get(k).map(_.trim).filter(_.nonEmpty)

  private val KeyId = "AWS_ACCESS_KEY_ID"
  private val Secret = "AWS_" + "SECRET_ACCESS_KEY"

  private def resolve(): Either[String, AwsCredentials] =
    (nonEmpty(KeyId), nonEmpty(Secret)) match
      case (Some(id), Some(secret)) =>
        Right(AwsCredentials(id, secret, nonEmpty("AWS_SESSION_TOKEN")))
      case (Some(_), None) | (None, Some(_)) =>
        Left("incomplete AWS credentials in environment: need both key id and secret key")
      case _ =>
        nonEmpty("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
          .map(rel => containerBase.stripSuffix("/") + "/" + rel.stripPrefix("/"))
          .map(Right(_))
          .orElse(nonEmpty("AWS_CONTAINER_CREDENTIALS_FULL_URI").map(checkFullUri)) match
          case Some(Right(url)) => fetchContainer(url)
          case Some(Left(e))    => Left(e)
          case None             => Left("no AWS credentials in environment or container endpoint")

  /** As the AWS SDKs do: https is allowed; http only to loopback or the ECS/EKS container hosts.
    * The refusal never echoes the URL, and nothing is sent.
    */
  private def checkFullUri(url: String): Either[String, String] =
    val refused = Left(
      "AWS_CONTAINER_CREDENTIALS_FULL_URI refused: must be https, or http to a loopback or ECS/EKS container host"
    )
    Try(new java.net.URI(url)).toOption match
      case Some(u) if u.getScheme != null && u.getHost != null =>
        val host = u.getHost.stripPrefix("[").stripSuffix("]").toLowerCase
        u.getScheme.toLowerCase match
          case "https"                                                  => Right(url)
          case "http" if EnvironmentCredentials.isAllowedHttpHost(host) => Right(url)
          case _                                                        => refused
      case _ => refused

  private def fetchContainer(url: String): Either[String, AwsCredentials] =
    val auth = nonEmpty("AWS_CONTAINER_AUTHORIZATION_TOKEN").map("authorization" -> _)
    transport
      .execute(HttpRequest("GET", url, auth.toMap, Array.emptyByteArray, timeout))
      .left
      .map(e => s"container credentials request failed: $e")
      .flatMap { r =>
        if !r.ok then Left(s"container credentials endpoint returned HTTP ${r.status}")
        else
          Try {
            val j = ujson.read(r.body)
            AwsCredentials(
              j("AccessKeyId").str,
              j("SecretAccessKey").str,
              j.obj.get("Token").map(_.str),
              j.obj.get("Expiration").map(e => Instant.parse(e.str))
            )
          }.toEither.left.map(_ => "container credentials reply is not valid")
      }

object EnvironmentCredentials:
  /** Fixed link-local address of the ECS task metadata endpoint, as documented by AWS. */
  val EcsContainerBase: String = "http://169.254.170.2"

  private val ContainerHosts = Set("169.254.170.2", "169.254.170.23", "fd00:ec2::23")
  private val Ipv4Loopback = """127(\.\d{1,3}){3}""".r

  /** Hosts allowed for a plain-http full URI (literal match; no DNS lookup). */
  private[aws] def isAllowedHttpHost(host: String): Boolean =
    host == "localhost" || host == "::1" || Ipv4Loopback.matches(host) || ContainerHosts(host)
