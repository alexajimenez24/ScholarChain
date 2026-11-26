package scholarchain.services.common

import cats.effect.{IO, Ref}
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.{KeyFactory, Signature}
import java.time.Clock
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Try
import scala.util.control.NonFatal

/** Who is calling: the token subject, its scopes and the Cognito app client. */
final case class Principal(subject: String, scopes: Set[String], clientId: Option[String])

/** Cognito verification settings: the exact issuer (`https://cognito-idp.<region>.amazonaws.com/
  * <userPoolId>`) and the app client ID that must be the token's audience.
  */
final case class AuthConfig(issuer: String, audience: String, leewaySeconds: Long = 30L)

/** Why a token was refused. The client only ever sees a generic 401; the reason is for metrics and
  * tests.
  */
enum AuthFailure:
  case Missing, Malformed, UnsupportedAlgorithm, UnknownKey, BadSignature, WrongIssuer,
    WrongTokenUse, WrongAudience, Expired, NotYetValid

/** Public signing keys by `kid`. */
trait KeyProvider:
  def key(kid: String): IO[Option[RSAPublicKey]]

final class StaticKeys(keys: Map[String, RSAPublicKey]) extends KeyProvider:
  def key(kid: String): IO[Option[RSAPublicKey]] = IO.pure(keys.get(kid))

object Jwks:
  private val MinModulusBits = 2048

  /** Parses a JWKS document: RSA keys of at least 2048 bits with a `kid`; anything else is skipped.
    */
  def parse(json: String): Either[String, Map[String, RSAPublicKey]] =
    Try {
      val kf = KeyFactory.getInstance("RSA")
      val dec = Base64.getUrlDecoder
      ujson
        .read(json)("keys")
        .arr
        .toVector
        .flatMap { k =>
          val o = k.obj
          for
            kid <- o.get("kid").flatMap(_.strOpt)
            if o.get("kty").flatMap(_.strOpt).contains("RSA")
            n <- o.get("n").flatMap(_.strOpt)
            e <- o.get("e").flatMap(_.strOpt)
            modulus = new BigInteger(1, dec.decode(n))
            if modulus.bitLength >= MinModulusBits
          yield kid -> kf
            .generatePublic(new RSAPublicKeySpec(modulus, new BigInteger(1, dec.decode(e))))
            .asInstanceOf[RSAPublicKey]
        }
        .toMap
    }.toEither.left.map(_ => "malformed JWKS document")

  /** Fetches the JWKS over HTTPS with bounded timeouts (not exercised by the unit tests: no network
    * in this phase).
    */
  def httpFetch(url: String, timeout: FiniteDuration = 3.seconds): IO[String] =
    IO.interruptible {
      require(url.startsWith("https://"), "the JWKS URL must be https")
      val client = java.net.http.HttpClient.newBuilder
        .connectTimeout(java.time.Duration.ofMillis(timeout.toMillis))
        .build()
      val req = java.net.http.HttpRequest
        .newBuilder(java.net.URI.create(url))
        .timeout(java.time.Duration.ofMillis(timeout.toMillis))
        .GET()
        .build()
      val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
      if res.statusCode != 200 then throw new RuntimeException("JWKS fetch failed")
      res.body
    }

/** Keys from a JWKS source with a cache. An unknown `kid` triggers one refresh, at most once per
  * `minRefresh`, so a bad token cannot make the service hammer the JWKS endpoint.
  */
final class JwksKeyProvider(
    fetch: IO[String],
    ttl: FiniteDuration = 1.hour,
    minRefresh: FiniteDuration = 1.minute,
    nowMillis: IO[Long] = IO.realTime.map(_.toMillis)
) extends KeyProvider:
  private val state = Ref.unsafe[IO, (Map[String, RSAPublicKey], Long)](Map.empty -> (-1L << 60))

  private def refresh(now: Long): IO[Map[String, RSAPublicKey]] =
    fetch.attempt.map(_.toOption.flatMap(Jwks.parse(_).toOption)).flatMap {
      case Some(keys) => state.set(keys -> now).as(keys)
      case None       => state.update((k, _) => k -> now) *> state.get.map(_._1)
    }

  def key(kid: String): IO[Option[RSAPublicKey]] =
    for
      now <- nowMillis
      st <- state.get
      (keys, at) = st
      found <-
        if keys.contains(kid) && now - at < ttl.toMillis then IO.pure(keys.get(kid))
        else if now - at >= minRefresh.toMillis then refresh(now).map(_.get(kid))
        else IO.pure(keys.get(kid))
    yield found

/** Cognito JWT verification: RS256 signature against the JWKS key named by `kid`, issuer,
  * `token_use`, audience and expiry. `token_use` picks the one claim that may hold the app client
  * ID: `client_id` for `access`, `aud` for `id`. Any other or a missing `token_use` is refused, so
  * the claim of one token kind never satisfies the other. An ID token carries no `scope` in
  * Cognito, so it passes no scoped route. Only RS256 is accepted, so `none` and
  * HMAC-with-the-public-key tokens are refused.
  */
final class JwtVerifier(cfg: AuthConfig, keys: KeyProvider, clock: Clock = Clock.systemUTC()):
  private val MaxTokenChars = 8192

  private def b64(s: String): Option[Array[Byte]] = Try(Base64.getUrlDecoder.decode(s)).toOption
  private def json(s: String): Option[ujson.Obj] =
    b64(s).flatMap(b =>
      Try(ujson.read(new String(b, UTF_8))).toOption.collect { case o: ujson.Obj => o }
    )

  def verify(token: String): IO[Either[AuthFailure, Principal]] =
    val parts = token.split('.')
    if token.length > MaxTokenChars || parts.length != 3 || token.endsWith(".") then
      IO.pure(Left(AuthFailure.Malformed))
    else
      (json(parts(0)), json(parts(1)), b64(parts(2))) match
        case (Some(h), Some(p), Some(sig)) =>
          if !h.value.get("alg").flatMap(_.strOpt).contains("RS256") then
            IO.pure(Left(AuthFailure.UnsupportedAlgorithm))
          else
            h.value.get("kid").flatMap(_.strOpt) match
              case None      => IO.pure(Left(AuthFailure.Malformed))
              case Some(kid) =>
                keys.key(kid).attempt.map {
                  case Right(Some(pub)) =>
                    if !signatureOk(pub, s"${parts(0)}.${parts(1)}", sig) then
                      Left(AuthFailure.BadSignature)
                    else claims(p)
                  case Right(None) => Left(AuthFailure.UnknownKey)
                  case Left(_)     => Left(AuthFailure.UnknownKey)
                }
        case _ => IO.pure(Left(AuthFailure.Malformed))

  private def signatureOk(pub: RSAPublicKey, signed: String, sig: Array[Byte]): Boolean =
    try
      val s = Signature.getInstance("SHA256withRSA")
      s.initVerify(pub)
      s.update(signed.getBytes(UTF_8))
      s.verify(sig)
    catch case NonFatal(_) => false

  private def claims(p: ujson.Obj): Either[AuthFailure, Principal] =
    def str(k: String) = p.value.get(k).flatMap(_.strOpt)
    def num(k: String) = p.value.get(k).flatMap(_.numOpt)
    val now = clock.instant.getEpochSecond
    def aud: Set[String] = p.value.get("aud") match
      case Some(ujson.Str(a))  => Set(a)
      case Some(ujson.Arr(xs)) => xs.flatMap(_.strOpt).toSet
      case _                   => Set.empty[String]
    // None: not an access or ID token. Otherwise the audiences of that token kind only.
    val audiences: Option[Set[String]] = str("token_use").collect {
      case "access" => str("client_id").toSet
      case "id"     => aud
    }
    if !str("iss").contains(cfg.issuer) then Left(AuthFailure.WrongIssuer)
    else if audiences.isEmpty then Left(AuthFailure.WrongTokenUse)
    else if !audiences.exists(_.contains(cfg.audience)) then Left(AuthFailure.WrongAudience)
    else
      num("exp") match
        case None                                       => Left(AuthFailure.Expired)
        case Some(exp) if exp + cfg.leewaySeconds < now => Left(AuthFailure.Expired)
        case _                                          =>
          if num("nbf").exists(_ - cfg.leewaySeconds > now) then Left(AuthFailure.NotYetValid)
          else
            str("sub") match
              case None      => Left(AuthFailure.Malformed)
              case Some(sub) =>
                val scopes =
                  str("scope").map(_.split(' ').filter(_.nonEmpty).toSet).getOrElse(Set.empty)
                Right(Principal(sub, scopes, Some(cfg.audience)))

/** Scope names used by the services (Cognito resource server `scholarchain`). */
object Scopes:
  val GraphRead = "scholarchain/graph.read"
  val EvidenceRead = "scholarchain/evidence.read"
  val QueryRun = "scholarchain/query.run"

/** Authentication and authorization for a route: verify the bearer token, then require a scope.
  * Raises 401 for a missing or bad token and 403 for a missing scope.
  */
final class Guard(verifier: JwtVerifier):
  private val Bearer = "(?i)^Bearer +([^ ]+)$".r

  def token(req: org.http4s.Request[IO]): Option[String] =
    req.headers
      .get(org.typelevel.ci.CIString("Authorization"))
      .flatMap(_.head.value.trim match
        case Bearer(t) => Some(t)
        case _         => None)

  def authorize(req: org.http4s.Request[IO], scope: String): IO[Principal] =
    token(req) match
      case None    => ApiException.raise(Guard.unauthorized)
      case Some(t) =>
        verifier.verify(t).flatMap {
          case Left(_)                              => ApiException.raise(Guard.unauthorized)
          case Right(p) if p.scopes.contains(scope) =>
            IO.pure(p)
          case Right(_) =>
            ApiException.raise(
              ApiError(
                ErrorCode.Forbidden,
                "the token lacks the required scope",
                Map("scope" -> scope)
              )
            )
        }

object Guard:
  val unauthorized: ApiError = ApiError(ErrorCode.Unauthorized, "missing, invalid or expired token")
