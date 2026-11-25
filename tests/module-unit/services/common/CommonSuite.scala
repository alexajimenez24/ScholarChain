package scholarchain.services.common

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scholarchain.core.*

object Fx:
  def run[A](io: IO[A]): A = io.unsafeRunSync()
  def get(
      path: String,
      token: Option[String] = None,
      headers: Seq[(String, String)] = Nil
  ): Request[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    val hs = headers.map((k, v) => Header.Raw(CIString(k), v)) ++ token.map(t =>
      Header.Raw(CIString("Authorization"), s"Bearer $t")
    )
    base.withHeaders(Headers(hs.toList))
  def send(routes: HttpRoutes[IO], req: Request[IO]): (Response[IO], String) =
    run(routes.orNotFound.run(req).flatMap(r => r.bodyText.compile.string.map(r -> _)))
  def hdr(r: Response[IO], n: String): Option[String] = r.headers.get(CIString(n)).map(_.head.value)
  def err(body: String): ujson.Obj = ujson.read(body)("error").obj

  val prov: Provenance =
    Provenance(SourceType.SCHOLARCHAIN, "x", "2026-11-01", "run-1", "evidence-v1", None)
  def evidence(id: String, text: String = "short text"): EvidenceItem =
    EvidenceItem(
      "evidence-v1",
      id,
      EvidenceType.TEXT_CHUNK,
      Some("dblp:w1"),
      Vector("sc:r:v1:0123456789abcdef0123"),
      "s3://sc-graph/spark/x",
      Some("chunk-1"),
      None,
      0.5,
      Map("k" -> "v"),
      Some(text),
      None,
      prov
    )

class JwtSuite extends munit.FunSuite:
  import Fx.*
  private def verify(t: String) = run(TestJwt.verifier.verify(t))

  test("a valid access token yields the principal and scopes") {
    val p = verify(TestJwt.token()).toOption.get
    assertEquals(p.subject, "user-1")
    assertEquals(p.scopes, Set("scholarchain/graph.read", "scholarchain/evidence.read"))
  }
  test("an ID token with aud instead of client_id is accepted") {
    val t = TestJwt.token(clientId = None, extra = Map("aud" -> ujson.Str(TestJwt.Audience)))
    assert(verify(t).isRight)
  }
  test(
    "expired, wrong issuer, wrong audience, bad signature, unknown key, bad algorithm are refused"
  ) {
    assertEquals(verify(TestJwt.token(exp = TestJwt.Now - 3600)), Left(AuthFailure.Expired))
    assertEquals(
      verify(TestJwt.token(iss = "https://evil.example/pool")),
      Left(AuthFailure.WrongIssuer)
    )
    assertEquals(verify(TestJwt.token(clientId = Some("other"))), Left(AuthFailure.WrongAudience))
    assertEquals(
      verify(TestJwt.token(signWith = TestJwt.otherPair)),
      Left(AuthFailure.BadSignature)
    )
    assertEquals(verify(TestJwt.token(kid = "kid-9")), Left(AuthFailure.UnknownKey))
    assertEquals(verify(TestJwt.token(alg = "HS256")), Left(AuthFailure.UnsupportedAlgorithm))
    assertEquals(verify(TestJwt.token(alg = "none")), Left(AuthFailure.UnsupportedAlgorithm))
  }
  test("a tampered payload and garbage are refused") {
    val parts = TestJwt.token().split('.')
    val forged = java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString("""{"sub":"x","iss":"i","exp":9999999999}""".getBytes)
    assertEquals(verify(s"${parts(0)}.$forged.${parts(2)}"), Left(AuthFailure.BadSignature))
    assertEquals(verify("not-a-token"), Left(AuthFailure.Malformed))
    assertEquals(verify("a.b.c"), Left(AuthFailure.Malformed))
  }
  test("a JWKS document round trips to a key that verifies") {
    val pub = TestJwt.pair.getPublic.asInstanceOf[java.security.interfaces.RSAPublicKey]
    def enc(b: java.math.BigInteger) =
      java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(b.toByteArray.dropWhile(_ == 0))
    val doc = ujson.write(
      ujson.Obj(
        "keys" -> ujson.Arr(
          ujson.Obj(
            "kid" -> "kid-1",
            "kty" -> "RSA",
            "alg" -> "RS256",
            "use" -> "sig",
            "n" -> enc(pub.getModulus),
            "e" -> enc(pub.getPublicExponent)
          ),
          ujson.Obj("kid" -> "ec", "kty" -> "EC")
        )
      )
    )
    val keys = Jwks.parse(doc).toOption.get
    assertEquals(keys.keySet, Set("kid-1"))
    val v = new JwtVerifier(
      TestJwt.cfg,
      new JwksKeyProvider(IO.pure(doc), nowMillis = IO.pure(1000L)),
      TestJwt.clock
    )
    assert(run(v.verify(TestJwt.token())).isRight)
    assert(Jwks.parse("{").isLeft)
  }
  test("the JWKS provider refreshes at most once per interval for an unknown kid") {
    var fetches = 0
    val p =
      new JwksKeyProvider(IO { fetches += 1; """{"keys":[]}""" }, nowMillis = IO.pure(1_000_000L))
    assertEquals(run(p.key("a")), None)
    assertEquals(run(p.key("b")), None)
    assertEquals(fetches, 1)
  }
  test("guard: missing token 401, bad token 401, missing scope 403") {
    def code(t: Option[String], scope: String) =
      Fx.run(TestJwt.guard.authorize(get("/x", t), scope).attempt).left.toOption.collect {
        case e: ApiException => e.error.code
      }
    assertEquals(code(None, "a"), Some(ErrorCode.Unauthorized))
    assertEquals(code(Some("junk"), "a"), Some(ErrorCode.Unauthorized))
    assertEquals(code(Some(TestJwt.token(scope = "other")), "a"), Some(ErrorCode.Forbidden))
    assertEquals(code(Some(TestJwt.token(scope = "a")), "a"), None)
  }

class ErrorModelSuite extends munit.FunSuite:
  import Fx.*
  test("status mapping of every error code") {
    val m = ErrorCode.values.map(c => c.wire -> c.status.code).toMap
    assertEquals(
      m,
      Map(
        "invalid_request" -> 400,
        "invalid_json" -> 400,
        "unauthorized" -> 401,
        "forbidden" -> 403,
        "not_found" -> 404,
        "conflict" -> 409,
        "payload_too_large" -> 413,
        "unsupported_media_type" -> 415,
        "unsupported_constraint" -> 422,
        "internal_error" -> 500,
        "dependency_failed" -> 502,
        "unavailable" -> 503,
        "timeout" -> 504
      )
    )
  }
  test("a caller request ID is kept when well formed and replaced when not") {
    assertEquals(RequestIds.from(Some("abc-123"), None, None, None).requestId, "abc-123")
    val bad = RequestIds.from(Some("x\r\ny z"), None, None, None).requestId
    assert(bad.startsWith("req-"))
  }
  test("the trace ID comes from X-Amzn-Trace-Id, then traceparent, else is generated") {
    assertEquals(
      RequestIds
        .from(
          None,
          Some("Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8"),
          None,
          None
        )
        .traceId,
      "1-5759e988-bd862e3fe1be46a994272793"
    )
    assertEquals(
      RequestIds
        .from(None, None, Some("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"), None)
        .traceId,
      "0af7651916cd43dd8448eb211c80319c"
    )
    assertEquals(RequestIds.from(None, None, None, None).traceId.length, 32)
  }
  test("the error body has the code, message and both IDs") {
    val j = ApiError(ErrorCode.NotFound, "x", Map("field" -> "f")).toJson(RequestIds("r1", "t1"))
    assertEquals(j("error")("requestId").str, "r1")
    assertEquals(j("error")("traceId").str, "t1")
    assertEquals(j("error")("details")("field").str, "f")
  }

class ServiceKitSuite extends munit.FunSuite:
  import Fx.*
  private val inner = HttpRoutes.of[IO] {
    case GET -> Root / "v1" / "boom" =>
      IO.raiseError(new RuntimeException("secret-password=hunter2"))
    case GET -> Root / "v1" / "typed" => ApiException.raise(ApiError(ErrorCode.Conflict, "dup"))
    case GET -> Root / "v1" / "ok"    => IO.pure(Json.ok(ujson.Obj("a" -> 1)))
    case req @ POST -> Root / "v1" / "echo" => JsonBody.read(req, 32).map(Json.ok)
  }
  private def kit(sink: MetricsSink = new CollectingMetrics, checks: List[ReadinessCheck] = Nil) =
    new ServiceKit(
      "test-service",
      sink,
      maxBodyBytes = 64,
      checks = checks,
      checkTimeout = 200.millis
    )
  private def post(body: String, ct: Option[MediaType] = Some(MediaType.application.json)) =
    val r = Request[IO](Method.POST, uri"/v1/echo").withEntity(body)
    ct.fold(r.removeHeader[`Content-Type`])(m => r.withContentType(`Content-Type`(m)))

  test("every response carries the request and trace IDs; a caller ID is echoed") {
    val (r, _) = send(kit().wrap(inner), get("/v1/ok", headers = Seq("X-Request-Id" -> "my-req-1")))
    assertEquals(r.status, Status.Ok)
    assertEquals(hdr(r, "X-Request-Id"), Some("my-req-1"))
    assert(hdr(r, "X-Trace-Id").exists(_.nonEmpty))
  }
  test("an unmatched route is a typed 404 with both IDs") {
    val (r, b) = send(kit().wrap(inner), get("/v1/nope"))
    assertEquals(r.status, Status.NotFound)
    assertEquals(err(b)("code").str, "not_found")
    assertEquals(err(b)("requestId").str, hdr(r, "X-Request-Id").get)
    assertEquals(err(b)("traceId").str, hdr(r, "X-Trace-Id").get)
  }
  test(
    "a typed error maps to its status; an unexpected exception is a generic 500 that leaks nothing"
  ) {
    val (r1, b1) = send(kit().wrap(inner), get("/v1/typed"))
    assertEquals((r1.status, err(b1)("code").str), (Status.Conflict, "conflict"))
    val (r2, b2) = send(kit().wrap(inner), get("/v1/boom"))
    assertEquals(r2.status, Status.InternalServerError)
    assert(!b2.contains("hunter2") && !b2.contains("secret"))
    assertEquals(err(b2)("code").str, "internal_error")
  }
  test("401 carries WWW-Authenticate") {
    val routes = kit().wrap(HttpRoutes.of[IO] { case req @ GET -> Root / "v1" / "p" =>
      TestJwt.guard.authorize(req, "s").as(Json.ok(ujson.Obj()))
    })
    val (r, b) = send(routes, get("/v1/p"))
    assertEquals(r.status, Status.Unauthorized)
    assertEquals(hdr(r, "WWW-Authenticate"), Some("Bearer"))
    assertEquals(err(b)("code").str, "unauthorized")
  }
  test("health is up without touching dependencies; readiness reports failing checks with 503") {
    val ready = kit(checks =
      List(
        ReadinessCheck("store", IO.pure(true)),
        ReadinessCheck("queue", IO.pure(false)),
        ReadinessCheck("slow", IO.never),
        ReadinessCheck("throws", IO.raiseError(new RuntimeException("x")))
      )
    )
    val (h, hb) = send(ready.wrap(inner), get("/healthz"))
    assertEquals(h.status, Status.Ok)
    assertEquals(ujson.read(hb)("status").str, "ok")
    val (r, rb) = send(ready.wrap(inner), get("/readyz"))
    assertEquals(r.status, Status.ServiceUnavailable)
    assertEquals(err(rb)("code").str, "unavailable")
    assertEquals(err(rb)("details").obj.keySet.toSet, Set("queue", "slow", "throws"))
    val ok = kit(checks = List(ReadinessCheck("store", IO.pure(true))))
    val (r2, rb2) = send(ok.wrap(inner), get("/readyz"))
    assertEquals(r2.status, Status.Ok)
    assertEquals(ujson.read(rb2)("checks")("store").str, "ok")
  }
  test(
    "a declared Content-Length over the limit is 413; chunked and small bodies go through JsonBody"
  ) {
    val (r, b) = send(kit().wrap(inner), post("x" * 100))
    assertEquals(r.status, Status.PayloadTooLarge)
    assertEquals(err(b)("code").str, "payload_too_large")
    val big = Request[IO](Method.POST, uri"/v1/echo")
      .withContentType(`Content-Type`(MediaType.application.json))
      .withBodyStream(
        fs2.Stream.emits("""{"a":"""".getBytes.toSeq) ++ fs2.Stream
          .emits(("y" * 80).getBytes.toSeq) ++ fs2.Stream.emits("\"}".getBytes.toSeq)
      )
    val inner2 = HttpRoutes.of[IO] { case req @ POST -> Root / "v1" / "echo" =>
      JsonBody.read(req, 32).map(Json.ok)
    }
    assertEquals(
      send(
        new ServiceKit("s", new CollectingMetrics, maxBodyBytes = 10_000).wrap(inner2),
        big
      )._1.status,
      Status.PayloadTooLarge
    )
    val (okr, okb) = send(kit().wrap(inner), post("""{"a":1}"""))
    assertEquals((okr.status, ujson.read(okb)("a").num), (Status.Ok, 1.0))
  }
  test("invalid JSON is 400 invalid_json without echoing the body; wrong content type is 415") {
    val (r, b) = send(kit().wrap(inner), post("{not json hunter2"))
    assertEquals((r.status, err(b)("code").str), (Status.BadRequest, "invalid_json"))
    assert(!b.contains("hunter2"))
    val (r2, b2) = send(kit().wrap(inner), post("{}", Some(MediaType.text.plain)))
    assertEquals(
      (r2.status, err(b2)("code").str),
      (Status.UnsupportedMediaType, "unsupported_media_type")
    )
  }
  test("unmatched paths share one constant EMF Operation (bounded cardinality)") {
    val sink = new CollectingMetrics
    val k = kit(sink).wrap(inner)
    send(k, get("/x" + java.util.UUID.randomUUID + "/y" + java.util.UUID.randomUUID))
    send(k, get("/q" + java.util.UUID.randomUUID + "/z" + java.util.UUID.randomUUID))
    val ops = sink.all.map(l => ujson.read(l)("Operation").str)
    assertEquals(ops.toSet, Set("unmatched"))
    assertEquals(ops.size, 2)
  }
  test("one valid EMF line per request with service and operation dimensions and no token") {
    val sink = new CollectingMetrics
    val (_, _) = send(kit(sink).wrap(inner), get("/v1/ok", token = Some("TOKEN-SECRET")))
    val line = ujson.read(sink.all.head)
    val m = line("_aws")("CloudWatchMetrics")(0)
    assertEquals(m("Namespace").str, "ScholarChain/Services")
    assertEquals(m("Dimensions")(0).arr.map(_.str).toSet, Set("Operation", "Service"))
    assertEquals(line("Service").str, "test-service")
    assertEquals(line("Operation").str, "GET /v1/ok")
    assertEquals(line("Requests").num, 1.0)
    assertEquals(line("status").str, "200")
    assert(!sink.all.head.contains("TOKEN-SECRET"))
  }
  test("a failing metrics sink never fails the request") {
    val bad = new MetricsSink { def emit(l: String) = IO.raiseError(new RuntimeException("sink")) }
    assertEquals(send(kit(bad).wrap(inner), get("/v1/ok"))._1.status, Status.Ok)
  }

class DownstreamSuite extends munit.FunSuite:
  import Fx.*
  private def policy(attempts: Int = 2, threshold: Int = 2, timeout: FiniteDuration = 1.second) =
    ResiliencePolicy(timeout, attempts, Duration.Zero, threshold, 10.seconds)
  private def code(io: IO[?]): Option[ErrorCode] =
    run(io.attempt).left.toOption.collect { case e: ApiException => e.error.code }

  test("success passes through; a transient failure is retried then succeeds") {
    var n = 0
    val d = new Downstream("store", policy())
    val v = run(d.call { n += 1; if n == 1 then Left("transient") else Right(42) })
    assertEquals((v, n), (42, 2))
  }
  test("attempts are bounded; a failed port is 502 without leaking its message") {
    var n = 0
    val d = new Downstream("store", policy(attempts = 3))
    val r = run(d.call[Int] { n += 1; Left("arn:aws:secret:XYZ") }.attempt)
    assertEquals(n, 3)
    val e = r.left.toOption.collect { case e: ApiException => e.error }.get
    assertEquals(e.code, ErrorCode.DependencyFailed)
    assert(!e.toJson(RequestIds("r", "t")).toString.contains("secret"))
  }
  test("a thrown exception is a failure; a hung call times out as 504") {
    val d = new Downstream("store", policy(attempts = 1))
    assertEquals(
      code(d.call[Int](throw new IllegalStateException("x"))),
      Some(ErrorCode.DependencyFailed)
    )
    val latch = new java.util.concurrent.CountDownLatch(1)
    val slow = new Downstream("slow", policy(attempts = 1, timeout = 20.millis))
    assertEquals(code(slow.call[Int] { latch.await(); Right(1) }), Some(ErrorCode.Timeout))
  }
  test(
    "the breaker opens after the threshold, answers 503 without calling, and half-opens after the open period"
  ) {
    val clock = new java.util.concurrent.atomic.AtomicLong(0)
    val d = new Downstream("store", policy(attempts = 1, threshold = 2), IO(clock.get))
    var calls = 0
    def fail = d.call[Int] { calls += 1; Left("x") }
    assertEquals(code(fail), Some(ErrorCode.DependencyFailed))
    assertEquals(code(fail), Some(ErrorCode.DependencyFailed))
    assertEquals(calls, 2)
    assertEquals(code(fail), Some(ErrorCode.Unavailable))
    assertEquals(calls, 2)
    clock.set(11_000)
    assertEquals(run(d.call { calls += 1; Right(7) }), 7)
    assertEquals(calls, 3)
    assertEquals(run(d.breakerOpen), false)
  }
  test("a not-found (Right(None)) never trips the breaker") {
    val d = new Downstream("store", policy(threshold = 1))
    (1 to 5).foreach(_ => assertEquals(run(d.call(Right(Option.empty[Int]))), None))
    assertEquals(run(d.breakerOpen), false)
  }

class EmfSuite extends munit.FunSuite:
  test("an EMF line has the _aws envelope, dimension values and metric values") {
    val j = ujson.read(
      Emf.line(
        "NS",
        Map("Service" -> "s"),
        Seq(Metric("A", 2), Metric("L", 5, "Milliseconds")),
        Map("requestId" -> "r"),
        123L
      )
    )
    assertEquals(j("_aws")("Timestamp").num, 123.0)
    assertEquals(j("_aws")("CloudWatchMetrics")(0)("Metrics")(1)("Unit").str, "Milliseconds")
    assertEquals(
      (j("Service").str, j("A").num, j("L").num, j("requestId").str),
      ("s", 2.0, 5.0, "r")
    )
  }

class EvidenceRoutesSuite extends munit.FunSuite:
  import Fx.*
  private val kit = new ServiceKit("svc", new CollectingMetrics)
  private def routes(
      lookup: EvidenceLookup = new InMemoryEvidenceLookup(Seq(evidence("ev:1", "t" * 1000)))
  ) =
    kit.wrap(
      new EvidenceRoutes(
        TestJwt.guard,
        lookup,
        new Downstream("ev", ResiliencePolicy(100.millis, 1, Duration.Zero)),
        50
      ).routes
    )
  private val tok = Some(TestJwt.token())

  test("returns the evidence with provenance and compact text") {
    val (r, b) = send(routes(), get("/v1/evidence/ev:1", tok))
    assertEquals(r.status, Status.Ok)
    val j = ujson.read(b)
    assertEquals(j("evidenceId").str, "ev:1")
    assertEquals(j("text").str.length, 50)
    assertEquals(j("textTruncated").bool, true)
    assertEquals(j("provenance")("runId").str, "run-1")
    assertEquals(j("sourceUri").str, "s3://sc-graph/spark/x")
  }
  test("missing evidence is 404; unauthorized 401; wrong scope 403; store failure 502") {
    assertEquals(send(routes(), get("/v1/evidence/ev:zz", tok))._1.status, Status.NotFound)
    assertEquals(send(routes(), get("/v1/evidence/ev:1"))._1.status, Status.Unauthorized)
    assertEquals(
      send(routes(), get("/v1/evidence/ev:1", Some(TestJwt.token(scope = "x"))))._1.status,
      Status.Forbidden
    )
    val failing = new EvidenceLookup { def get(id: String) = Left("table down") }
    val (r, b) = send(routes(failing), get("/v1/evidence/ev:1", tok))
    assertEquals((r.status, err(b)("code").str), (Status.BadGateway, "dependency_failed"))
    assert(!b.contains("table down"))
  }
