package scholarchain.core.aws

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.{Clock, Duration, Instant, ZoneOffset}
import scala.collection.mutable

/** The AWS-published SigV4 example credentials; they are public documentation values, not secrets.
  */
private object Vectors:
  val AccessKey = "AKIDEXAMPLE"
  val SecretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
  val Creds: AwsCredentials = AwsCredentials(AccessKey, SecretKey)
  val Time: Instant = Instant.parse("2015-08-30T12:36:00Z")

class SigV4Suite extends munit.FunSuite:
  import Vectors.*

  // Source: AWS SigV4 test suite https://github.com/awslabs/aws-sig-v4-test-suite
  // (aws-sig-v4-test-suite/get-vanilla and post-vanilla; also reproduced in the AWS docs
  // https://docs.aws.amazon.com/general/latest/gr/sigv4-calculate-signature.html).
  // Scope: 20150830/us-east-1/service/aws4_request, host example.amazonaws.com.
  test("test suite get-vanilla: canonical request, string to sign, signature") {
    val s = SigV4.sign(
      SigningInput("GET", "https://example.amazonaws.com/", Map.empty, Array.emptyByteArray),
      Creds,
      "us-east-1",
      "service",
      Time
    )
    assertEquals(
      s.canonicalRequest,
      "GET\n/\n\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n" +
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
    assertEquals(
      s.stringToSign,
      "AWS4-HMAC-SHA256\n20150830T123600Z\n20150830/us-east-1/service/aws4_request\n" +
        "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63"
    )
    assertEquals(s.signature, "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31")
    assertEquals(
      s.headers("authorization"),
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
        "SignedHeaders=host;x-amz-date, " +
        "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
    )
    assertEquals(s.headers("x-amz-date"), "20150830T123600Z")
  }

  test("test suite post-vanilla signature") {
    val s = SigV4.sign(
      SigningInput("POST", "https://example.amazonaws.com/", Map.empty, Array.emptyByteArray),
      Creds,
      "us-east-1",
      "service",
      Time
    )
    assertEquals(s.signature, "5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b")
  }

  // Source: https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv4-create-string-to-sign.html
  // and the derivation example in the AWS General Reference (signing key for 20150830/us-east-1/iam).
  test("signing key derivation matches the AWS published value") {
    val k = SigV4.signingKey(SecretKey, "20150830", "us-east-1", "iam")
    assertEquals(
      k.map(b => f"${b & 0xff}%02x").mkString,
      "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9"
    )
  }

  test("session token is added, signed and sent; content sha256 is optional") {
    val withTok = Creds.copy(sessionToken = Some("TOKEN-VALUE"))
    val s = SigV4.sign(
      SigningInput(
        "PUT",
        "https://b.s3.example.test/k",
        Map("Content-Type" -> "text/plain"),
        "hi".getBytes
      ),
      withTok,
      "r1",
      "s3",
      Time,
      includeContentSha256 = true
    )
    assertEquals(s.headers("x-amz-security-token"), "TOKEN-VALUE")
    assertEquals(
      s.signedHeaders,
      "content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token"
    )
    assertEquals(
      s.headers("x-amz-content-sha256"),
      "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4"
    )
  }

  test("canonical URI and query encoding, sorting and port in host") {
    assertEquals(SigV4.canonicalQuery(Some("b=2&a=1&a=0&c=x y&d")), "a=0&a=1&b=2&c=x%20y&d=")
    assertEquals(SigV4.canonicalUri("/a b/c", singleEncode = true), "/a%20b/c")
    assertEquals(SigV4.canonicalUri("/a%20b/c", singleEncode = false), "/a%2520b/c")
    val s = SigV4.sign(
      SigningInput("GET", "http://127.0.0.1:8123/p?z=1&y=2", Map.empty, Array.emptyByteArray),
      Creds,
      "r",
      "svc",
      Time
    )
    assertEquals(s.headers("host"), "127.0.0.1:8123")
    assert(s.canonicalRequest.startsWith("GET\n/p\ny=2&z=1\n"))
  }

  test("region and service are parameters of the scope") {
    val a = SigV4.sign(
      SigningInput("GET", "https://h.test/", Map.empty, Array.empty),
      Creds,
      "r1",
      "s1",
      Time
    )
    val b = SigV4.sign(
      SigningInput("GET", "https://h.test/", Map.empty, Array.empty),
      Creds,
      "r2",
      "s1",
      Time
    )
    assert(a.stringToSign.contains("/r1/s1/aws4_request"))
    assertNotEquals(a.signature, b.signature)
  }

class CredentialsSuite extends munit.FunSuite:
  import Vectors.*
  private val keyVar = "AWS_ACCESS_KEY_ID"
  private val secretVar = "AWS_" + "SECRET_ACCESS_KEY"

  private def withContainerStub(reply: String)(
      f: (String, mutable.Buffer[Map[String, String]]) => Unit
  ) =
    val seen = mutable.Buffer.empty[Map[String, String]]
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      ex =>
        import scala.jdk.CollectionConverters.*
        seen += ex.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.get(0)).toMap +
          ("path" -> ex.getRequestURI.getPath)
        val b = reply.getBytes("UTF-8")
        ex.sendResponseHeaders(200, b.length)
        ex.getResponseBody.write(b)
        ex.close()
    )
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}", seen)
    finally server.stop(0)

  test("environment variables, with and without a session token") {
    val p = new EnvironmentCredentials(
      Map(keyVar -> "AK", secretVar -> "SK", "AWS_SESSION_TOKEN" -> "TK")
    )
    assertEquals(p.credentials(), Right(AwsCredentials("AK", "SK", Some("TK"))))
    val q = new EnvironmentCredentials(Map(keyVar -> "AK", secretVar -> "SK"))
    assertEquals(q.credentials().map(_.sessionToken), Right(None))
  }

  test("missing or incomplete environment is a Left, never a real lookup") {
    assert(new EnvironmentCredentials(Map.empty).credentials().isLeft)
    assert(new EnvironmentCredentials(Map(keyVar -> "AK")).credentials().isLeft)
  }

  test("static provider returns its value") {
    assertEquals(new StaticCredentials(Creds).credentials(), Right(Creds))
  }

  test("container endpoint: relative URI, auth token header, caching until near expiry") {
    val reply =
      """{"AccessKeyId":"CAK","SecretAccessKey":"CSK","Token":"CTK","Expiration":"2030-01-01T00:00:00Z"}"""
    withContainerStub(reply) { (base, seen) =>
      val clock = Clock.fixed(Instant.parse("2029-12-31T00:00:00Z"), ZoneOffset.UTC)
      val p = new EnvironmentCredentials(
        Map(
          "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" -> "/v2/creds/abc",
          "AWS_CONTAINER_AUTHORIZATION_TOKEN" -> "auth-1"
        ),
        containerBase = base,
        clock = clock
      )
      val c = p.credentials().toOption.get
      assertEquals((c.accessKeyId, c.secretAccessKey, c.sessionToken), ("CAK", "CSK", Some("CTK")))
      p.credentials()
      assertEquals(seen.size, 1, "second call served from cache")
      assertEquals(seen.head("path"), "/v2/creds/abc")
      assertEquals(seen.head("authorization"), "auth-1")
    }
  }

  test("container credentials near expiry are refreshed") {
    val reply =
      """{"AccessKeyId":"A","SecretAccessKey":"S","Token":"T","Expiration":"2030-01-01T00:00:00Z"}"""
    withContainerStub(reply) { (base, seen) =>
      val clock = Clock.fixed(Instant.parse("2029-12-31T23:58:00Z"), ZoneOffset.UTC)
      val p = new EnvironmentCredentials(
        Map("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" -> "/x"),
        containerBase = base,
        clock = clock
      )
      p.credentials(); p.credentials()
      assertEquals(seen.size, 2)
    }
  }

  test("full URI: http to loopback is allowed and the authorization token is sent") {
    val reply = """{"AccessKeyId":"FA","SecretAccessKey":"FS"}"""
    withContainerStub(reply) { (base, seen) =>
      val p = new EnvironmentCredentials(
        Map(
          "AWS_CONTAINER_CREDENTIALS_FULL_URI" -> (base + "/creds"),
          "AWS_CONTAINER_AUTHORIZATION_TOKEN" -> "auth-full"
        )
      )
      assertEquals(p.credentials().map(_.accessKeyId), Right("FA"))
      assertEquals(seen.head("authorization"), "auth-full")
    }
  }

  test("full URI: http to an external host is refused and nothing is sent") {
    val sent = mutable.Buffer.empty[HttpRequest]
    val spy = new HttpTransport:
      def execute(r: HttpRequest) =
        sent += r
        Left("must not be called")
    for url <- Seq(
        "http://evil.example.com/creds?x=SECRETQ",
        "http://169.254.169.254/latest",
        "http://127.0.0.1.evil.example.com/creds",
        "ftp://127.0.0.1/creds",
        "not a uri"
      )
    do
      val r = new EnvironmentCredentials(
        Map(
          "AWS_CONTAINER_CREDENTIALS_FULL_URI" -> url,
          "AWS_CONTAINER_AUTHORIZATION_TOKEN" -> "TOKEN-SECRET"
        ),
        transport = spy
      ).credentials()
      assert(r.isLeft, url)
      assert(r.left.exists(m => m.contains("refused") && !m.contains("SECRETQ")), r.toString)
    assertEquals(sent.size, 0)
  }

  test("full URI: container hosts and https pass the host check") {
    for h <- Seq("169.254.170.2", "169.254.170.23", "[fd00:ec2::23]", "[::1]", "localhost")
    do
      val sent = mutable.Buffer.empty[HttpRequest]
      val spy = new HttpTransport:
        def execute(r: HttpRequest) =
          sent += r
          Left("stub")
      new EnvironmentCredentials(
        Map("AWS_CONTAINER_CREDENTIALS_FULL_URI" -> s"http://$h/creds"),
        transport = spy
      ).credentials()
      assertEquals(sent.size, 1, h)
    val sent = mutable.Buffer.empty[HttpRequest]
    new EnvironmentCredentials(
      Map("AWS_CONTAINER_CREDENTIALS_FULL_URI" -> "https://creds.example.com/x"),
      transport = new HttpTransport:
        def execute(r: HttpRequest) =
          sent += r
          Left("stub")
    ).credentials()
    assertEquals(sent.size, 1)
  }

  test("no secret in toString of credentials, providers or signed values") {
    val c = AwsCredentials("AKID1", "SECRET-VALUE-XYZ", Some("TOKEN-VALUE-XYZ"))
    val signed =
      SigV4.sign(SigningInput("GET", "https://h.test/", Map.empty, Array.empty), c, "r", "s", Time)
    val req =
      HttpRequest("GET", "https://h.test/", signed.headers, Array.empty, Duration.ofSeconds(1))
    val env = new EnvironmentCredentials(Map(keyVar -> "AKID1", secretVar -> "SECRET-VALUE-XYZ"))
    val shown = Seq(
      c.toString,
      new StaticCredentials(c).toString,
      env.toString,
      env.credentials().toString,
      signed.toString,
      req.toString
    ).mkString("\n")
    assert(!shown.contains("SECRET-VALUE-XYZ"), shown)
    assert(!shown.contains("TOKEN-VALUE-XYZ"), shown)
    assert(!shown.contains(signed.signature), shown)
    assert(shown.contains("AKID1"))
  }

class AwsHttpSuite extends munit.FunSuite:
  import Vectors.*

  private def stub(statuses: List[Int], reply: String)(
      f: (String, mutable.Buffer[(String, Map[String, String], String)]) => Unit
  ): Unit =
    val seen = mutable.Buffer.empty[(String, Map[String, String], String)]
    val remaining = mutable.Queue.from(statuses)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      ex =>
        import scala.jdk.CollectionConverters.*
        val hs = ex.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.get(0)).toMap
        seen += ((
          ex.getRequestMethod + " " + ex.getRequestURI.toString,
          hs,
          new String(ex.getRequestBody.readAllBytes(), "UTF-8")
        ))
        val st = if remaining.size > 1 then remaining.dequeue() else remaining.head
        val b = reply.getBytes("UTF-8")
        ex.sendResponseHeaders(st, b.length)
        ex.getResponseBody.write(b)
        ex.close()
    )
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}", seen)
    finally server.stop(0)

  private val fixed = Clock.fixed(Time, ZoneOffset.UTC)

  test("a malformed URL error carries no query string") {
    val http = new AwsHttp("r", new StaticCredentials(Creds), clock = fixed)
    val r = http.send("GET", "http://127.0.0.1:1/a b?token=QUERY-SECRET-123", "s", Array.empty)
    assert(r.isLeft, r.toString)
    val msg = r.left.toOption.get
    assert(!msg.contains("QUERY-SECRET-123"), msg)
    assert(!msg.contains("token="), msg)
    assert(msg.contains("127.0.0.1"), msg)
  }

  test("round trip against a localhost stub: signed headers, body and parsed JSON") {
    stub(List(200), """{"ok":true}""") { (base, seen) =>
      val http = new AwsHttp("us-test-1", new StaticCredentials(Creds), clock = fixed)
      val r =
        http.json(s"$base/op?b=2&a=1", "svc", ujson.Obj("k" -> "v"), Map("X-Amz-Target" -> "T.Op"))
      assertEquals(r, Right(ujson.Obj("ok" -> true)))
      val (line, hs, body) = seen.head
      assertEquals(line, "POST /op?b=2&a=1")
      assertEquals(body, """{"k":"v"}""")
      assertEquals(hs("x-amz-target"), "T.Op")
      assertEquals(hs("x-amz-date"), "20150830T123600Z")
      assert(
        hs("authorization").startsWith(
          "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-test-1/svc/aws4_request, SignedHeaders=content-type;host;x-amz-date;x-amz-target"
        ),
        hs("authorization")
      )
    }
  }

  test("s3 requests sign and send x-amz-content-sha256") {
    stub(List(200), "data") { (base, seen) =>
      val http = new AwsHttp("us-test-1", new StaticCredentials(Creds), clock = fixed)
      val r = http.send("GET", s"$base/key", "s3", Array.emptyByteArray)
      assertEquals(r.map(_.body), Right("data"))
      assertEquals(
        seen.head._2("x-amz-content-sha256"),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
      )
    }
  }

  test("no hidden retries by default: a 503 is returned once") {
    stub(List(503), "busy") { (base, seen) =>
      val http = new AwsHttp("r", new StaticCredentials(Creds), sleeper = _ => fail("no sleep"))
      assertEquals(http.send("GET", base, "svc", Array.empty).map(_.status), Right(503))
      assertEquals(seen.size, 1)
    }
  }

  test("documented bounded retry with exponential backoff, then success") {
    stub(List(429, 503, 200), "{}") { (base, seen) =>
      val sleeps = mutable.Buffer.empty[Long]
      val http = new AwsHttp(
        "r",
        new StaticCredentials(Creds),
        maxAttempts = 3,
        sleeper = sleeps += _,
        backoffMillis = 10
      )
      assertEquals(http.send("GET", base, "svc", Array.empty).map(_.status), Right(200))
      assertEquals(seen.size, 3)
      assertEquals(sleeps.toList, List(10L, 20L))
    }
  }

  test("retries stop at maxAttempts and the last response is returned") {
    stub(List(500), "x") { (base, seen) =>
      val http = new AwsHttp("r", new StaticCredentials(Creds), maxAttempts = 2, sleeper = _ => ())
      assertEquals(http.send("GET", base, "svc", Array.empty).map(_.status), Right(500))
      assertEquals(seen.size, 2)
    }
  }

  test("non-2xx from json() is a Left; transport failure is a Left without secrets") {
    stub(List(400), """{"__type":"Bad"}""") { (base, _) =>
      val http = new AwsHttp("r", new StaticCredentials(Creds))
      assert(http.json(base, "svc", ujson.Obj()).left.exists(_.contains("HTTP 400")))
    }
    val http = new AwsHttp(
      "r",
      new StaticCredentials(Creds),
      requestTimeout = Duration.ofMillis(500)
    )
    val r = http.send("GET", "http://127.0.0.1:1/", "svc", Array.empty)
    assert(r.isLeft)
    assert(!r.left.toOption.get.contains(SecretKey))
  }

  test("credential failure is a Left and sends nothing") {
    val http =
      new AwsHttp("r", new EnvironmentCredentials(Map.empty), maxAttempts = 3, sleeper = _ => ())
    assert(http.send("GET", "http://127.0.0.1:1/", "svc", Array.empty).isLeft)
  }

  test("no secret, token, signature or query string in log output or error text") {
    stub(List(500, 200), "{}") { (base, seen) =>
      val logs = mutable.Buffer.empty[String]
      val creds = AwsCredentials("AKID9", "SECRET-VALUE-QRS", Some("TOKEN-VALUE-QRS"))
      val http = new AwsHttp(
        "r",
        new StaticCredentials(creds),
        maxAttempts = 2,
        sleeper = _ => (),
        log = logs += _
      )
      http.send("GET", s"$base/p?secretparam=1", "svc", Array.empty)
      val sig = seen.head._2("authorization").split("Signature=").last
      val all = logs.mkString("\n")
      assert(logs.nonEmpty)
      assert(!all.contains("SECRET-VALUE-QRS"), all)
      assert(!all.contains("TOKEN-VALUE-QRS"), all)
      assert(!all.contains(sig), all)
      assert(!all.contains("secretparam"), all)
      assertEquals(seen.head._2("x-amz-security-token"), "TOKEN-VALUE-QRS")
    }
  }

  test("JdkHttpTransport honors the request timeout") {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      ex => { Thread.sleep(1500); ex.sendResponseHeaders(200, -1); ex.close() }
    )
    server.start()
    try
      val t = new JdkHttpTransport()
      val r = t.execute(
        HttpRequest(
          "GET",
          s"http://127.0.0.1:${server.getAddress.getPort}/",
          Map.empty,
          Array.empty,
          Duration.ofMillis(200)
        )
      )
      assert(r.isLeft, r.toString)
    finally server.stop(0)
  }
