package scholarchain.services.query

import scholarchain.services.common.{AuthConfig, Guard, JwtVerifier, StaticKeys}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey
import java.security.{KeyPair, KeyPairGenerator, Signature}
import java.util.Base64

/** Test-only token signer: a locally generated RSA key, no network. */
object TestJwt:
  val Issuer = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_TEST"
  val Audience = "client-abc"
  val Now: Long = 1_800_000_000L
  val clock: java.time.Clock =
    java.time.Clock.fixed(java.time.Instant.ofEpochSecond(Now), java.time.ZoneOffset.UTC)

  private def gen(): KeyPair =
    val g = KeyPairGenerator.getInstance("RSA")
    g.initialize(2048)
    g.generateKeyPair()
  val pair: KeyPair = gen()
  val otherPair: KeyPair = gen()
  val cfg: AuthConfig = AuthConfig(Issuer, Audience)

  def keys: StaticKeys = new StaticKeys(Map("kid-1" -> pair.getPublic.asInstanceOf[RSAPublicKey]))
  def verifier: JwtVerifier = new JwtVerifier(cfg, keys, clock)
  def guard: Guard = new Guard(verifier)

  private def b64(b: Array[Byte]) = Base64.getUrlEncoder.withoutPadding.encodeToString(b)

  def token(
      scope: String = "scholarchain/query.run scholarchain/evidence.read",
      iss: String = Issuer,
      clientId: Option[String] = Some(Audience),
      exp: Long = Now + 3600,
      alg: String = "RS256",
      kid: String = "kid-1",
      signWith: KeyPair = pair,
      extra: Map[String, ujson.Value] = Map.empty
  ): String =
    val header = ujson.Obj("alg" -> alg, "kid" -> kid, "typ" -> "JWT")
    val body = ujson.Obj(
      "sub" -> "user-1",
      "iss" -> iss,
      "exp" -> ujson.Num(exp.toDouble),
      "token_use" -> "access",
      "scope" -> scope
    )
    clientId.foreach(c => body("client_id") = c)
    extra.foreach((k, v) => body(k) = v)
    val signed =
      s"${b64(ujson.write(header).getBytes(UTF_8))}.${b64(ujson.write(body).getBytes(UTF_8))}"
    val s = Signature.getInstance("SHA256withRSA")
    s.initSign(signWith.getPrivate)
    s.update(signed.getBytes(UTF_8))
    s"$signed.${b64(s.sign())}"
