package scholarchain.adversarial.services

import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey
import java.security.{KeyPair, KeyPairGenerator, Signature}
import java.util.Base64

import cats.effect.unsafe.implicits.global

import scholarchain.services.common.*

/** Phase 11, API authentication claims with no test before (`coverage-before.md` row 19; Phase 10
  * review MINOR-1, carried item C15).
  *
  * Contract (`docs/api.md` "Authentication", `modules/services/MODULE.md`): Cognito JWT verified in
  * every service; RS256 only, `kid` found, issuer, audience (`client_id` or `aud`), expiry with a
  * 30 s leeway, then a scope; requests carry an access token; jobs are limited to the subject that
  * created them. Plus RFC 7519 4.1.5 (a token MUST NOT be accepted before `nbf`) and Cognito's
  * guidance to check `token_use`. Public API only: `JwtVerifier(AuthConfig, StaticKeys, clock)` and
  * `verify(token)`; tokens are signed here with a local RSA key, no network.
  */
class JwtClaimsSuite extends munit.FunSuite:

  private val Issuer = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_ADVTEST"
  private val Client = "client-adv"
  private val Now = 1_800_000_000L
  private val clock =
    java.time.Clock.fixed(java.time.Instant.ofEpochSecond(Now), java.time.ZoneOffset.UTC)

  private val pair: KeyPair =
    val g = KeyPairGenerator.getInstance("RSA")
    g.initialize(2048)
    g.generateKeyPair()

  private val verifier = new JwtVerifier(
    AuthConfig(Issuer, Client),
    new StaticKeys(Map("kid-a" -> pair.getPublic.asInstanceOf[RSAPublicKey])),
    clock
  )

  private def b64(b: Array[Byte]) = Base64.getUrlEncoder.withoutPadding.encodeToString(b)

  /** A signed token: the standard access-token claims, with `drop` removed and `set` overriding. */
  private def token(
      set: Map[String, ujson.Value] = Map.empty,
      drop: Set[String] = Set.empty,
      header: Map[String, ujson.Value] = Map.empty
  ): String =
    val h = ujson.Obj("alg" -> "RS256", "kid" -> "kid-a", "typ" -> "JWT")
    header.foreach((k, v) => if v == ujson.Null then h.value.remove(k) else h(k) = v)
    val body = ujson.Obj(
      "sub" -> "user-adv",
      "iss" -> Issuer,
      "client_id" -> Client,
      "exp" -> ujson.Num((Now + 3600).toDouble),
      "iat" -> ujson.Num((Now - 60).toDouble),
      "token_use" -> "access",
      "scope" -> "scholarchain/query.run"
    )
    set.foreach((k, v) => body(k) = v)
    drop.foreach(k => body.value.remove(k))
    val signed = s"${b64(ujson.write(h).getBytes(UTF_8))}.${b64(ujson.write(body).getBytes(UTF_8))}"
    val s = Signature.getInstance("SHA256withRSA")
    s.initSign(pair.getPrivate)
    s.update(signed.getBytes(UTF_8))
    s"$signed.${b64(s.sign())}"

  private def verify(t: String) = verifier.verify(t).unsafeRunSync()

  private def num(x: Long) = ujson.Num(x.toDouble)

  test("control: a standard access token is accepted with its subject") {
    val p = verify(token()).fold(e => fail(s"refused: $e"), identity)
    assertEquals(p.subject, "user-adv")
  }

  test("leeway: expired 20 s ago is accepted, 31 s and 1 h ago are refused") {
    assert(verify(token(set = Map("exp" -> num(Now - 20)))).isRight, "within the 30 s leeway")
    assert(verify(token(set = Map("exp" -> num(Now - 31)))).isLeft, "31 s past expiry")
    assert(verify(token(set = Map("exp" -> num(Now - 3600)))).isLeft, "1 h past expiry")
  }

  test("a token without exp is refused: it would never expire") {
    val r = verify(token(drop = Set("exp")))
    assert(r.isLeft, s"accepted without exp: $r")
  }

  test("a token used before its nbf (beyond the leeway) is refused") {
    val r = verify(token(set = Map("nbf" -> num(Now + 3600))))
    assert(r.isLeft, s"accepted 1 h before nbf: $r")
  }

  test("a token without sub is refused: jobs are scoped to the subject") {
    val r = verify(token(drop = Set("sub")))
    assert(r.isLeft, s"accepted without sub: $r")
  }

  test("token_use: a refresh-typed, a bogus or a missing token_use is refused (C15)") {
    val bad = Vector(
      "refresh" -> verify(token(set = Map("token_use" -> ujson.Str("refresh")))),
      "bogus" -> verify(token(set = Map("token_use" -> ujson.Str("bogus")))),
      "missing" -> verify(token(drop = Set("token_use")))
    ).collect { case (k, r) if r.isRight => s"token_use $k accepted" }
    assertEquals(bad, Vector.empty[String])
  }

  test("header: a missing kid and other RSA algorithms than RS256 are refused") {
    val bad = Vector(
      "no kid" -> verify(token(header = Map("kid" -> ujson.Null))),
      "RS512" -> verify(token(header = Map("alg" -> ujson.Str("RS512")))),
      "PS256" -> verify(token(header = Map("alg" -> ujson.Str("PS256"))))
    ).collect { case (k, r) if r.isRight => s"$k accepted" }
    assertEquals(bad, Vector.empty[String])
  }
