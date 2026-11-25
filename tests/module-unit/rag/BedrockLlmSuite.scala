package scholarchain.rag

import scholarchain.core.*
import scholarchain.core.aws.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import RagWorld.*

/** A 127.0.0.1 stand-in for bedrock-runtime InvokeModel: records requests, answers the scripted
  * reply. No AWS endpoint, no real credential and no network beyond localhost is used.
  */
final class InvokeStub:
  @volatile var reply: (Int, String, Map[String, String]) = (200, "{}", Map.empty)
  private val received = new ConcurrentLinkedQueue[(String, String, Map[String, String], String)]()
  private val pool = Executors.newFixedThreadPool(2)
  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.setExecutor(pool)
  server.createContext(
    "/",
    (ex: HttpExchange) =>
      try
        val body = new String(ex.getRequestBody.readAllBytes(), UTF_8)
        val hs =
          ex.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(","))
        received.add((ex.getRequestMethod, ex.getRequestURI.getRawPath, hs.toMap, body))
        val (status, text, headers) = reply
        headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
        val out = text.getBytes(UTF_8)
        ex.sendResponseHeaders(status, if out.isEmpty then -1 else out.length.toLong)
        if out.nonEmpty then ex.getResponseBody.write(out)
      catch case NonFatal(_) => ()
      finally ex.close()
  )
  server.start()
  val url: String = s"http://127.0.0.1:${server.getAddress.getPort}"
  def seen: Vector[(String, String, Map[String, String], String)] = received.asScala.toVector
  def clear(): Unit = received.clear()
  def stop(): Unit = { server.stop(0); pool.shutdownNow(); () }

class BedrockLlmSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private val Model = "anthropic.claude-test-v1:0"
  private val creds = new StaticCredentials(
    AwsCredentials("AKIDTEST", "test-secret-not-a-real-key", None)
  )
  private val clock = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC)
  private val stub = new InvokeStub
  override def afterAll(): Unit = stub.stop()

  private val on = w.cfg.copy(models = w.cfg.models.copy(llmEnabled = true, synthesisModel = Model))
  private def llm(cfg: ScholarChainConfig = on) =
    BedrockLlm
      .fromConfig(
        cfg,
        creds,
        requestTimeout = Duration.ofSeconds(5),
        clock = clock,
        endpointOverride = Some(stub.url)
      )
  private def messages(text: String, stop: String = "end_turn") =
    s"""{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":${ujson
        .Str(text)
        .render()}}],"stop_reason":"$stop","usage":{"input_tokens":1234,"output_tokens":56}}"""

  test(
    "InvokeModel request: signed POST to the model path with one user message, temperature 0, bounded tokens"
  ) {
    stub.clear()
    stub.reply = (200, messages("hello"), Map.empty)
    val l = llm().fold(e => fail(e), identity)
    assertEquals(l.complete("PROMPT-TEXT", 700), Right(LlmReply("hello", Some(1234L), Some(56L))))
    val (method, path, headers, body) = stub.seen.head
    assertEquals(method, "POST")
    assertEquals(path, "/model/anthropic.claude-test-v1%3A0/invoke")
    assert(headers.get("authorization").exists(_.startsWith("AWS4-HMAC-SHA256")))
    assert(headers.get("authorization").exists(_.contains("/bedrock/aws4_request")))
    val b = ujson.read(body)
    assertEquals(b("max_tokens").num.toInt, 700)
    assertEquals(b("temperature").num, 0.0)
    assertEquals(b("anthropic_version").str, BedrockLlm.AnthropicVersion)
    assertEquals(
      b("messages").arr.map(m => m("content")(0)("text").str).toVector,
      Vector("PROMPT-TEXT")
    )
    assert(!b.obj.contains("thinking") && !b.obj.contains("tools"))
  }

  test(
    "replies with a reasoning block, a truncated reply, an error status or a bad body are refused"
  ) {
    val l = llm().fold(e => fail(e), identity)
    stub.reply = (
      200,
      """{"content":[{"type":"thinking","thinking":"HIDDEN"},{"type":"text","text":"x"}],"stop_reason":"end_turn"}""",
      Map.empty
    )
    val thinking = l.complete("p", 10)
    assert(
      thinking.left.toOption.exists(_.contains("reasoning block")) && !thinking.toString.contains(
        "HIDDEN"
      )
    )
    stub.reply = (200, messages("{\"partial", "max_tokens"), Map.empty)
    assertEquals(l.complete("p", 10), Left("InvokeModel reply stopped at max_tokens"))
    stub.reply = (
      429,
      """{"message":"slow down"}""",
      Map("x-amzn-ErrorType" -> "ThrottlingException:http://x")
    )
    assertEquals(l.complete("p", 10), Left("InvokeModel HTTP 429 ThrottlingException"))
    stub.reply = (200, "not json", Map.empty)
    assertEquals(l.complete("p", 10), Left("InvokeModel reply is not JSON"))
    assert(l.complete("p", 0).isLeft)
  }

  test(
    "the adapter is refused unless the model path is enabled with an Anthropic model and a region"
  ) {
    assert(llm(w.cfg).isLeft, "llmEnabled is false in the local config")
    assert(llm(on.copy(models = on.models.copy(synthesisModel = ""))).isLeft)
    assert(llm(on.copy(models = on.models.copy(synthesisModel = "amazon.titan-text-v1"))).isLeft)
    val regional = BedrockLlm.fromConfig(on, creds).fold(e => fail(e), identity)
    assertEquals(
      regional.endpoint,
      "https://bedrock-runtime.us-east-2.amazonaws.com/model/anthropic.claude-test-v1%3A0/invoke"
    )
  }

  test(
    "stages 1 to 10 through the Bedrock adapter on the stub: validated reply, reported token usage"
  ) {
    val prompt = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("prompts/runtime")))
      .toRight("no prompts/runtime")
      .flatMap(SynthesisPrompt.load)
      .fold(e => fail(e), identity)
    val q = """What are the collaboration metrics of "Alice Arden" since 2015?"""
    val e = w.pipeline().run("qry-probe", AnalysisRequest(q)).fold(x => fail(x.describe), identity)
    val m = e.bundle.deterministic.find(_.researcherIds == Vector(Alice)).get.evidenceId
    val text =
      s"""{"schemaVersion":"answer-synthesis-v1","summary":"Stored metrics follow.","abstain":false,"abstentionReason":null,"claims":[{"claimId":"claim-1","text":"Alice Arden has 12 publications in window 2015-2026.","evidenceIds":["$m"],"uncertainty":null}]}"""
    stub.clear()
    stub.reply = (200, messages(text), Map.empty)
    val synth = new LlmSynthesizer(llm().fold(x => fail(x), identity), prompt, on)
    val (wf, _) = w.workflow(synth)
    val r = wf.run("qry-bedrock", AnalysisRequest(q)).fold(x => fail(x.describe), identity)
    assertEquals(r.answer.modelVersion, Model)
    assertEquals(r.answer.claims.map(_.verificationStatus), Vector(VerificationStatus.VERIFIED))
    assertEquals((r.audit.inputTokens, r.audit.outputTokens), (Some(1234L), Some(56L)))
    assertEquals(stub.seen.size, 1)
    assert(ujson.read(stub.seen.head._4)("max_tokens").num.toInt <= on.operations.maxTokensPerQuery)
  }
