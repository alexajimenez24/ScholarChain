package scholarchain.er

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import scholarchain.core.*
import scholarchain.core.aws.*

/** What the local stub received. Header names are lowercased. */
final case class StubSeen(
    method: String,
    rawPath: String,
    headers: Map[String, String],
    body: Array[Byte]
)

/** What the local stub answers. */
final case class StubReply(
    status: Int,
    body: String,
    delayMs: Long = 0,
    headers: Map[String, String] = Map.empty
)

/** A localhost stand-in for bedrock-runtime InvokeModel (`com.sun.net.httpserver`, 127.0.0.1 and an
  * ephemeral port). It records every request and answers with the current scripted reply.
  */
final class InvokeModelStub:
  @volatile private var reply = StubReply(200, "{}")
  private val received = new ConcurrentLinkedQueue[StubSeen]()
  private val pool = Executors.newFixedThreadPool(4)
  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.setExecutor(pool)
  server.createContext("/", (ex: HttpExchange) => handle(ex))
  server.start()

  val url: String = s"http://127.0.0.1:${server.getAddress.getPort}"
  def reset(r: StubReply): Unit = { received.clear(); reply = r }
  def seen: Vector[StubSeen] = received.asScala.toVector
  def stop(): Unit = { server.stop(0); pool.shutdownNow(); () }

  private def handle(ex: HttpExchange): Unit =
    try
      val body = ex.getRequestBody.readAllBytes()
      val hs = ex.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(","))
      received.add(StubSeen(ex.getRequestMethod, ex.getRequestURI.getRawPath, hs.toMap, body))
      val r = reply
      if r.delayMs > 0 then Thread.sleep(r.delayMs)
      r.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
      val out = r.body.getBytes(UTF_8)
      ex.sendResponseHeaders(r.status, if out.isEmpty then -1 else out.length.toLong)
      if out.nonEmpty then ex.getResponseBody.write(out)
    catch
      case _: InterruptedException => ()
      case NonFatal(_)             => ()
    finally ex.close()

/** The Bedrock adjudicator against a local HTTP stub only: no AWS call, no real credentials, no
  * network beyond localhost, and no labeled data.
  */
class BedrockAdjudicatorSuite extends munit.FunSuite:
  import ErFixtures.*
  import ErFixtures.Run.*

  private val Model = "anthropic.claude-test-v1:0"
  private val Secret = "test-secret-value-not-a-real-key"
  private val creds = AwsCredentials("AKIDTEST", Secret, None)
  private val now = Instant.parse("2026-11-23T12:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val template = PromptTemplate.load().fold(e => throw new AssertionError(e), identity)
  private val stub = new InvokeModelStub

  override def afterAll(): Unit = stub.stop()

  private val req = AdjudicationRequest(
    "mc:researcher:00000000000000000001",
    EntityKind.RESEARCHER,
    "m:aaaaaaaaaaaaaaaaaaaa",
    "m:bbbbbbbbbbbbbbbbbbbb",
    "er-v1",
    0.8,
    Map(Features.CoauthorOverlap -> 0.3, Features.InitialsCompatible -> 1.0),
    Map("left.names" -> "Dan Brown", "right.names" -> "Dan Brown")
  )

  // Pinned by hand and hashed with sha256sum outside the code under test.
  private val PinnedInputJson =
    """{"candidateId":"mc:researcher:00000000000000000001","features":{"coauthor_overlap":0.3,"initials_compatible":1},"fields":{"left.names":"Dan Brown","right.names":"Dan Brown"},"kind":"RESEARCHER","policyVersion":"er-v1","score":0.8,"sourceId":"m:aaaaaaaaaaaaaaaaaaaa","targetId":"m:bbbbbbbbbbbbbbbbbbbb"}"""
  private val PinnedInputHash = "a18d3bc9aa455bfecf3fcebba9de0c2e6d972fe3a061fd8c6a42560d8f3ee73b"
  // sha256("<input hash>|er-adjudication-v1|anthropic.claude-test-v1:0|er-v1")
  private val PinnedCacheKey = "8558b5c73afccfd49e2533857951ba724fdba4ff40ac696e36210c443ece75c6"
  private val PolicySha256 = "82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff"

  private def config(env: Map[String, String] = Map.empty): ScholarChainConfig =
    ConfigLoader
      .load(Map("LLM_ENABLED" -> "true", "SYNTHESIS_MODEL_ID" -> Model) ++ env)
      .fold(e => fail(e), identity)

  private def bedrock(
      timeout: Duration = Duration.ofSeconds(5),
      provider: CredentialsProvider = new StaticCredentials(creds),
      endpoint: String = stub.url,
      env: Map[String, String] = Map.empty
  ): BedrockAdjudicator =
    BedrockAdjudicator
      .fromConfig(
        config(env),
        template,
        provider,
        requestTimeout = timeout,
        clock = clock,
        endpointOverride = Some(endpoint)
      )
      .fold(e => fail(e), identity)

  private def answer(
      decision: String = "ACCEPT",
      src: String = req.sourceId,
      tgt: String = req.targetId,
      codes: String = "\"SHARED_COAUTHORS\"",
      cited: String = "\"coauthor_overlap\"",
      extra: String = ""
  ) =
    s"""{"decision":"$decision","sourceId":"$src","targetId":"$tgt","confidence":0.9,"reasonCodes":[$codes],"citedFields":[$cited]$extra}"""

  private def textBlock(text: String) = ujson.Obj("type" -> "text", "text" -> text)

  /** An InvokeModel Messages reply as Bedrock returns it for an Anthropic model. */
  private def envelope(text: String, stop: String = "end_turn", blocks: Option[ujson.Arr] = None) =
    ujson
      .Obj(
        "id" -> "msg_test",
        "type" -> "message",
        "role" -> "assistant",
        "model" -> Model,
        "content" -> blocks.getOrElse(ujson.Arr(textBlock(text))),
        "stop_reason" -> stop,
        "stop_sequence" -> ujson.Null,
        "usage" -> ujson.Obj("input_tokens" -> 10, "output_tokens" -> 20)
      )
      .render()

  private def skipped(a: Adjudicator, r: AdjudicationRequest = req): (String, String) =
    a.adjudicate(r) match
      case AdjudicationResult.Skipped(reason, detail) => (reason, detail)
      case other                                      => fail(s"expected a skip, got $other")

  test("the request is a SigV4-signed InvokeModel POST with the model path and the prompt body") {
    stub.reset(StubReply(200, envelope(answer())))
    val port = bedrock()
    val prompt = template.render(req)
    assertEquals(port.complete(prompt, req), Right(answer()))
    val s = stub.seen match
      case Vector(one) => one
      case other       => fail(s"expected one request, got ${other.size}")
    assertEquals(s.method, "POST")
    assertEquals(s.rawPath, "/model/anthropic.claude-test-v1%3A0/invoke")
    assertEquals(s.headers.get("content-type"), Some("application/json"))
    assertEquals(s.headers.get("accept"), Some("application/json"))
    val body = ujson.read(s.body)
    assertEquals(
      body.obj.keySet.toSet,
      Set("anthropic_version", "max_tokens", "temperature", "messages")
    )
    assertEquals(body("anthropic_version").str, "bedrock-2023-05-31")
    assertEquals(body("max_tokens").num, 512.0)
    assertEquals(body("temperature").num, 0.0)
    assertEquals(
      body("messages"),
      ujson.Arr(ujson.Obj("role" -> "user", "content" -> ujson.Arr(textBlock(prompt))))
    )
    assert(prompt.contains(req.inputJson))
    assertEquals(s.headers.get("x-amz-date"), Some("20261123T120000Z"))
    val auth = s.headers.getOrElse("authorization", fail("request is not signed"))
    assert(
      auth.startsWith(
        "AWS4-HMAC-SHA256 Credential=AKIDTEST/20261123/us-east-2/bedrock/aws4_request, " +
          "SignedHeaders=accept;content-type;host;x-amz-date, Signature="
      ),
      auth
    )
    // The signature covers exactly the URL, headers and body the stub received.
    val expected = SigV4.sign(
      SigningInput(
        "POST",
        s"${stub.url}${s.rawPath}",
        Map("content-type" -> "application/json", "accept" -> "application/json"),
        s.body
      ),
      creds,
      "us-east-2",
      "bedrock",
      now
    )
    assertEquals(Some(auth), expected.headers.get("authorization"))
    assert(!s.headers.values.exists(_.contains(Secret)))
    assert(!new String(s.body, UTF_8).contains(Secret))
    assert(!port.toString.contains(Secret))
  }

  test("a valid reply is accepted through the Adjudicator under the unchanged cache key") {
    stub.reset(StubReply(200, envelope(answer())))
    val port = bedrock()
    assertEquals(port.mode, AdjudicatorMode.BEDROCK)
    assertEquals((port.promptVersion, port.modelVersion), ("er-adjudication-v1", Model))
    val a = new Adjudicator(port, template, new AdjudicationCache(), budget = 3, "er-v1")
    val rec = a.adjudicate(req) match
      case AdjudicationResult.Answered(r, false) => r
      case other                                 => fail(s"unexpected $other")
    assertEquals(
      rec.output,
      AdjudicationOutput(
        "ACCEPT",
        req.sourceId,
        req.targetId,
        0.9,
        Vector("SHARED_COAUTHORS"),
        Vector("coauthor_overlap")
      )
    )
    assertEquals(
      (rec.promptVersion, rec.modelVersion, rec.policyVersion),
      ("er-adjudication-v1", Model, "er-v1")
    )
    assertEquals(req.inputJson, PinnedInputJson)
    assertEquals(req.inputHash, PinnedInputHash)
    assertEquals(rec.cacheKey, PinnedCacheKey)
    assertEquals(
      rec.cacheKey,
      Adjudication.cacheKey(PinnedInputHash, template.version, Model, "er-v1")
    )
    assertEquals(a.keyOf(req), PinnedCacheKey)
    assertEquals(a.adjudicate(req), AdjudicationResult.Answered(rec, fromCache = true))
    assertEquals((stub.seen.size, a.callsMade), (1, 1))
  }

  test("malformed envelopes are call failures, schema-breaking text is refused, nothing cached") {
    val failed = "LLM_CALL_FAILED"
    val invalid = "LLM_INVALID_OUTPUT"
    val cases = Vector(
      ("not JSON", "<html>oops</html>", failed),
      ("empty body", "", failed),
      ("a JSON array", "[]", failed),
      ("no content", """{"type":"message","role":"assistant"}""", failed),
      (
        "two text blocks",
        envelope("", blocks = Some(ujson.Arr(textBlock(answer()), textBlock(answer())))),
        failed
      ),
      ("no block", envelope("", blocks = Some(ujson.Arr())), failed),
      (
        "a tool_use block",
        envelope(
          "",
          blocks = Some(ujson.Arr(ujson.Obj("type" -> "tool_use", "id" -> "t", "name" -> "x")))
        ),
        failed
      ),
      (
        "a non-string text",
        envelope("", blocks = Some(ujson.Arr(ujson.Obj("type" -> "text", "text" -> 5)))),
        failed
      ),
      ("stopped at max_tokens", envelope(answer(), stop = "max_tokens"), failed),
      ("prose", envelope("They are the same person."), invalid),
      ("prose around JSON", envelope("Answer: " + answer()), invalid),
      ("a code fence", envelope("```json\n" + answer() + "\n```"), invalid),
      ("an extra field", envelope(answer(extra = ""","note":"x"""")), invalid),
      ("missing fields", envelope("""{"decision":"ACCEPT"}"""), invalid),
      ("a new source identifier", envelope(answer(src = s"orcid:$OrcidA")), invalid),
      ("a new target identifier", envelope(answer(tgt = "m:cccccccccccccccccccc")), invalid),
      (
        "an identifier in a reason code",
        envelope(answer(codes = "\"SAME_AS_A5054810403\"")),
        invalid
      ),
      ("a DOI in a reason code", envelope(answer(codes = "\"see 10.1000/x\"")), invalid),
      ("an uncited field", envelope(answer(cited = "\"left.orcid\"")), invalid),
      ("a decision outside the schema", envelope(answer(decision = "MERGE")), invalid),
      ("a confidence outside [0, 1]", envelope(answer().replace("0.9", "1.5")), invalid)
    )
    cases.foreach { (name, body, expected) =>
      stub.reset(StubReply(200, body))
      val cache = new AdjudicationCache()
      val a = new Adjudicator(bedrock(), template, cache, budget = 5, "er-v1")
      assertEquals(skipped(a)._1, expected, name)
      assertEquals(cache.size, 0, name)
      assertEquals(stub.seen.size, 1, name)
    }
  }

  test("HTTP errors, a timeout, a refused connection and bad credentials never throw") {
    val marker = "error-body-marker"
    Vector(400, 403, 404, 424, 429, 500, 503).foreach { status =>
      stub.reset(
        StubReply(
          status,
          s"""{"message":"$marker"}""",
          headers = Map(
            "x-amzn-ErrorType" -> "ThrottlingException:http://internal.amazon.com/coral/x/"
          )
        )
      )
      val cache = new AdjudicationCache()
      val (reason, detail) = skipped(new Adjudicator(bedrock(), template, cache, 5, "er-v1"))
      assertEquals(reason, "LLM_CALL_FAILED", s"HTTP $status")
      assertEquals(detail, s"InvokeModel HTTP $status ThrottlingException")
      assert(!detail.contains(marker) && !detail.contains(Secret), detail)
      assertEquals(cache.size, 0)
    }

    stub.reset(StubReply(200, envelope(answer()), delayMs = 2000))
    val slow = bedrock(timeout = Duration.ofMillis(200))
    val (timeoutReason, timeoutDetail) =
      skipped(new Adjudicator(slow, template, new AdjudicationCache(), 5, "er-v1"))
    assertEquals(timeoutReason, "LLM_CALL_FAILED")
    assert(timeoutDetail.startsWith("InvokeModel failed"), timeoutDetail)

    val refused = bedrock(endpoint = "http://127.0.0.1:1")
    assert(refused.complete("p", req).left.exists(_.startsWith("InvokeModel failed")))

    stub.reset(StubReply(200, envelope(answer())))
    val missing = new CredentialsProvider:
      def credentials(): Either[String, AwsCredentials] = Left("no credentials available")
    val throwing = new CredentialsProvider:
      def credentials(): Either[String, AwsCredentials] = throw new IllegalStateException("boom")
    assertEquals(
      bedrock(provider = missing).complete("p", req),
      Left("InvokeModel failed: no credentials available")
    )
    assertEquals(
      bedrock(provider = throwing).complete("p", req),
      Left("InvokeModel failed: IllegalStateException")
    )
    assertEquals(stub.seen.size, 0) // no unsigned request is ever sent
  }

  test("model ID and region come from configuration; unusable configurations are refused") {
    val eu = BedrockAdjudicator
      .fromConfig(config(Map("AWS_REGION" -> "eu-west-1")), template, new StaticCredentials(creds))
      .fold(e => fail(e), identity)
    assertEquals(
      eu.endpoint,
      "https://bedrock-runtime.eu-west-1.amazonaws.com/model/anthropic.claude-test-v1%3A0/invoke"
    )
    stub.reset(StubReply(200, envelope(answer())))
    bedrock(env = Map("AWS_REGION" -> "eu-west-1")).complete("p", req)
    assert(
      stub.seen.headOption
        .flatMap(_.headers.get("authorization"))
        .exists(_.contains("/20261123/eu-west-1/bedrock/aws4_request")),
      stub.seen.toString
    )
    def build(env: Map[String, String], maxTokens: Int = 512) =
      BedrockAdjudicator.fromConfig(
        ConfigLoader.load(env).fold(e => fail(e), identity),
        template,
        new StaticCredentials(creds),
        maxTokens = maxTokens
      )
    val on = Map("LLM_ENABLED" -> "true")
    assert(build(Map("SYNTHESIS_MODEL_ID" -> Model)).left.exists(_.contains("llmEnabled")))
    assert(build(on).left.exists(_.contains("synthesisModel is not configured")))
    assert(build(on + ("SYNTHESIS_MODEL_ID" -> "amazon.nova-lite-v1:0")).isLeft)
    assert(build(on + ("SYNTHESIS_MODEL_ID" -> "notanthropic.x-v1")).isLeft)
    assert(build(on + ("SYNTHESIS_MODEL_ID" -> Model), maxTokens = 0).isLeft)
    val profile = build(on + ("SYNTHESIS_MODEL_ID" -> "us.anthropic.claude-test-v1:0"))
    assertEquals(profile.map(_.modelVersion), Right("us.anthropic.claude-test-v1:0"))
    assertEquals(stub.seen.size, 1) // building an adapter makes no call
  }

  test("er-v1 is unchanged: only band cases call Bedrock, all others decide as in off mode") {
    assertEquals(policy.version, "er-v1")
    assertEquals(policyConfig.thresholds, Thresholds(0.9, 0.35, 0.7, 0.9))
    val in = getClass.getResourceAsStream("/scholarchain/er/er-policy-v1.json")
    val policyBytes =
      try in.readAllBytes()
      finally in.close()
    assertEquals(Hashing.sha256Hex(policyBytes), PolicySha256)

    stub.reset(StubReply(500, "{}"))
    val runId = Ids.run("""{"fixture":"er-bedrock"}""")
    val at = "2026-11-23T00:00:00Z"
    def resolver(port: AdjudicatorPort) =
      new Resolver(
        policy,
        new Adjudicator(port, template, new AdjudicationCache(), 50, "er-v1"),
        runId,
        at
      )
    val off = resolver(OffAdjudicator)
    val bed = resolver(bedrock())
    val pairs = blocked.distinct.map { c =>
      val (l, r) = sides(c)
      (off.decide(merged(c), l, r).decision, bed.decide(merged(c), l, r).decision)
    }
    val (band, rest) = pairs.partition(_._1.reasonCodes.lastOption.contains("LLM_OFF"))
    assert(band.nonEmpty && rest.nonEmpty, s"band ${band.size}, rest ${rest.size}")
    rest.foreach((o, b) => assertEquals(b, o))
    band.foreach { (o, b) =>
      assertEquals(b.reasonCodes.last, "LLM_CALL_FAILED")
      assertEquals(
        b.copy(reasonCodes = b.reasonCodes.init),
        o.copy(reasonCodes = o.reasonCodes.init)
      )
      assertEquals(b.decision, MatchOutcome.UNRESOLVED)
    }
    assertEquals(stub.seen.size, band.size)
  }

  test("a Bedrock ACCEPT moves a band case to LLM_ADJUDICATED with the model ID as its version") {
    val band = merged(bandCandidate)
    val (l, r) = sides(bandCandidate)
    stub.reset(StubReply(200, envelope(answer(src = band.sourceId, tgt = band.targetId))))
    val resolver = new Resolver(
      policy,
      new Adjudicator(bedrock(), template, new AdjudicationCache(), 5, "er-v1"),
      Ids.run("""{"fixture":"er-bedrock"}"""),
      "2026-11-23T00:00:00Z"
    )
    val res = resolver.decide(band, l, r)
    val d = res.decision
    assertEquals((d.decision, d.path), (MatchOutcome.ACCEPTED, MatchPath.LLM_ADJUDICATED))
    assertEquals((d.promptVersion, d.modelVersion), (Some("er-adjudication-v1"), Some(Model)))
    assertEquals((d.sourceId, d.targetId), (band.sourceId, band.targetId))
    assertEquals(res.audit.adjudicatorMode, "BEDROCK")
    assertEquals(res.adjudication.map(_.cacheKey), res.audit.adjudicationCacheKey)
    assertEquals(Validate.decision(d), Vector.empty[String])
    assertEquals(stub.seen.size, 1)
  }
