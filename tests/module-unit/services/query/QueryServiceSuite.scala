package scholarchain.services.query

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.util.concurrent.atomic.AtomicInteger
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scholarchain.rag.*
import scholarchain.services.common.*
import scholarchain.storage.EvidenceBundles
import QueryFixture.*

/** Runs queued async jobs on demand, so tests are deterministic (no threads, no sleeps). */
final class QueueScheduler extends JobScheduler:
  private val queue = scala.collection.mutable.Queue.empty[IO[Unit]]
  def submit(job: IO[Unit]): IO[Unit] = IO(queue.enqueue(job))
  def size: Int = queue.size
  def drain(): Unit = while queue.nonEmpty do queue.dequeue().unsafeRunSync()

final class CountingEngine(inner: QueryEngine) extends QueryEngine:
  val calls = new AtomicInteger(0)
  def run(queryId: String, req: AnalysisRequest): Either[WorkflowError, WorkflowResult] =
    calls.incrementAndGet()
    inner.run(queryId, req)

final class FnEngine(f: (String, AnalysisRequest) => Either[WorkflowError, WorkflowResult])
    extends QueryEngine:
  def run(queryId: String, req: AnalysisRequest) = f(queryId, req)

object QFx:
  val fast = ResiliencePolicy(5.seconds, 1, Duration.Zero, 3, 30.seconds)
  val tok = Some(TestJwt.token())
  def tokFor(sub: String) = Some(TestJwt.token(extra = Map("sub" -> ujson.Str(sub))))

  private def auth(r: Request[IO], t: Option[String]) =
    t.fold(r)(x => r.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $x")))

  def post(
      body: String,
      token: Option[String] = tok,
      key: Option[String] = None,
      contentType: MediaType = MediaType.application.json
  ): Request[IO] =
    val r = Request[IO](Method.POST, Uri.unsafeFromString("/v1/query"))
      .withEntity(body)
      .withContentType(`Content-Type`(contentType))
    auth(key.fold(r)(k => r.putHeaders(Header.Raw(CIString("Idempotency-Key"), k))), token)

  def get(path: String, token: Option[String] = tok): Request[IO] =
    auth(Request[IO](Method.GET, Uri.unsafeFromString(path)), token)

  def send(routes: HttpRoutes[IO], req: Request[IO]): (Response[IO], String) =
    routes.orNotFound.run(req).flatMap(r => r.bodyText.compile.string.map(r -> _)).unsafeRunSync()
  def err(b: String): ujson.Obj = ujson.read(b)("error").obj
  def hdr(r: Response[IO], n: String): Option[String] = r.headers.get(CIString(n)).map(_.head.value)
  def body(q: String, extra: (String, ujson.Value)*): String =
    ujson.write(ujson.Obj.from(Seq("question" -> ujson.Str(q)) ++ extra))
  def asyncBody(q: String = AliceQ): String = body(q, "mode" -> "async")

  final class Rig(
      engine: Option[QueryEngine] = None,
      policy: ResiliencePolicy = fast,
      audit: Option[AuditLog] = None,
      maxPending: Int = 8,
      extraChecks: List[ReadinessCheck] = Nil,
      maxRecords: Int = 1000,
      synthesizer: Synthesizer = DeterministicSynthesizer
  ):
    private val (rs, retriever) = QueryFixture.stores()
    val wfAudit = new InMemoryAuditLog
    val auditLog: AuditLog = audit.getOrElse(wfAudit)
    val real = new WorkflowEngine(
      new CollaborationWorkflow(ctx, rs, retriever, synthesizer, wfAudit)
    )
    val counting = new CountingEngine(engine.getOrElse(real))
    val bundles = new EvidenceBundles(rs.kv, cfg.dynamo.evidenceBundleTable)
    val scheduler = new QueueScheduler
    val sink = new CollectingMetrics
    val wfDown = new Downstream("workflow", policy)
    val stDown = new Downstream("stores", fast)
    val index = new EvidenceIndex
    val store = new QueryStore(maxRecords)
    val service = new QueryService(
      counting,
      auditLog,
      bundles,
      wfDown,
      stDown,
      scheduler,
      store,
      index,
      IO.pure(1_800_000_000_000L),
      maxPending
    )
    val guard = TestJwt.guard
    val kit = new ServiceKit(
      "query-service",
      sink,
      checks = ReadinessCheck("workflow", wfDown.breakerOpen.map(!_)) :: extraChecks
    )
    val routes = QueryApp.routes(
      kit,
      guard,
      service,
      new EvidenceRoutes(guard, new BundleEvidenceLookup(bundles, index), stDown)
    )

import QFx.*

class QueryServiceSuite extends munit.FunSuite:
  private def answerOf(b: String) = ujson.read(b)("answer")

  test("a synchronous query returns a verified, evidence-backed answer with IDs and a metric") {
    val rig = new Rig
    val (r, b) = send(rig.routes, post(body(AliceQ)))
    assertEquals(r.status, Status.Ok)
    val j = ujson.read(b)
    assertEquals((j("state").str, j("mode").str), ("succeeded", "sync"))
    assert(j("queryId").str.startsWith("qry-") && j("jobId").str.startsWith("job-"))
    val a = answerOf(b)
    assertEquals(a("abstained").bool, false)
    assert(a("claims").arr.nonEmpty && a("claims").arr.forall(_("evidenceIds").arr.nonEmpty))
    assertEquals(a("queryId").str, j("queryId").str)
    assertEquals(a("modelVersion").str, "none")
    assert(hdr(r, "X-Request-Id").exists(_.nonEmpty) && hdr(r, "X-Trace-Id").exists(_.nonEmpty))
    assert(rig.sink.all.exists(l => l.contains("POST /v1/query") && l.contains("LatencyMs")))
  }

  test("an asynchronous query returns 202 and a job reference; the job finishes and has a result") {
    val rig = new Rig
    val (r, b) = send(rig.routes, post(asyncBody()))
    assertEquals(r.status, Status.Accepted)
    val j = ujson.read(b)
    val jobId = j("jobId").str
    assertEquals((j("state").str, hdr(r, "Location")), ("queued", Some(s"/v1/jobs/$jobId")))
    assertEquals(rig.scheduler.size, 1)
    assertEquals(rig.counting.calls.get, 0)
    val (r1, b1) = send(rig.routes, get(s"/v1/jobs/$jobId"))
    assertEquals((r1.status, ujson.read(b1)("state").str), (Status.Ok, "queued"))
    val (r2, b2) = send(rig.routes, get(s"/v1/jobs/$jobId/result"))
    assertEquals((r2.status, ujson.read(b2)("state").str), (Status.Accepted, "queued"))
    rig.scheduler.drain()
    val (r3, b3) = send(rig.routes, get(s"/v1/jobs/$jobId"))
    val done = ujson.read(b3)
    assertEquals(
      (r3.status, done("state").str, done("stage").str),
      (Status.Ok, "succeeded", "done")
    )
    assert(done.obj.contains("completedAt") && done("links")("result").str.endsWith("/result"))
    val (r4, b4) = send(rig.routes, get(s"/v1/jobs/$jobId/result"))
    assertEquals(r4.status, Status.Ok)
    assertEquals(answerOf(b4)("abstained").bool, false)
  }

  test(
    "invalid JSON, a non-object body, a wrong content type and a missing question are typed 4xx"
  ) {
    val rig = new Rig
    val (r, b) = send(rig.routes, post("{not json"))
    assertEquals((r.status, err(b)("code").str), (Status.BadRequest, "invalid_json"))
    assert(!b.contains("not json"))
    assertEquals(err(b)("requestId").str, hdr(r, "X-Request-Id").get)
    assertEquals(err(b)("traceId").str, hdr(r, "X-Trace-Id").get)
    val (r2, b2) = send(rig.routes, post("[1]"))
    assertEquals((r2.status, err(b2)("code").str), (Status.BadRequest, "invalid_request"))
    val (r3, b3) = send(rig.routes, post(body(AliceQ), contentType = MediaType.text.plain))
    assertEquals(
      (r3.status, err(b3)("code").str),
      (Status.UnsupportedMediaType, "unsupported_media_type")
    )
    val (r4, b4) = send(rig.routes, post("{}"))
    assertEquals(
      (r4.status, err(b4)("details")("field").str),
      (Status.BadRequest, "question")
    )
    val (r5, b5) = send(rig.routes, post(body(AliceQ, "mode" -> "later")))
    assertEquals((r5.status, err(b5)("details")("field").str), (Status.BadRequest, "mode"))
    val (r6, b6) = send(rig.routes, post(body(AliceQ, "surprise" -> 1)))
    assertEquals((r6.status, err(b6)("details")("field").str), (Status.BadRequest, "body.surprise"))
    assertEquals(send(rig.routes, post("x" * 20000))._1.status, Status.PayloadTooLarge)
    assertEquals(rig.counting.calls.get, 0)
  }

  test("an unsupported query constraint is 422 with the field named") {
    val rig = new Rig
    def c(k: String, v: ujson.Value) = body(AliceQ, "constraints" -> ujson.Obj(k -> v))
    val (r, b) = send(rig.routes, post(c("nationality", "x")))
    assertEquals(
      (r.status, err(b)("code").str, err(b)("details")("field").str),
      (Status.UnprocessableContent, "unsupported_constraint", "constraints.nationality")
    )
    val (r2, b2) = send(
      rig.routes,
      post(body(AliceQ, "constraints" -> ujson.Obj("yearFrom" -> 2020, "yearTo" -> 2016)))
    )
    assertEquals(
      (r2.status, err(b2)("code").str),
      (Status.UnprocessableContent, "unsupported_constraint")
    )
    assertEquals(send(rig.routes, post(c("yearFrom", 1200)))._1.status, Status.UnprocessableContent)
    assertEquals(
      send(
        rig.routes,
        post(c("topics", ujson.Arr.from((1 to 11).map(i => ujson.Str(s"t$i")))))
      )._1.status,
      Status.UnprocessableContent
    )
    assertEquals(
      send(rig.routes, post(body(AliceQ, "options" -> ujson.Obj("limit" -> 500))))._1.status,
      Status.UnprocessableContent
    )
    assertEquals(send(rig.routes, post(c("yearFrom", "2015")))._1.status, Status.BadRequest)
    // a workflow that refuses the question is also 422
    val refusing =
      new Rig(engine = Some(new FnEngine((_, _) => Left(WorkflowError.Unsupported("no")))))
    val (r3, b3) = send(refusing.routes, post(body(AliceQ)))
    assertEquals(
      (r3.status, err(b3)("code").str),
      (Status.UnprocessableContent, "unsupported_constraint")
    )
    assertEquals(rig.counting.calls.get, 0)
  }

  test("missing evidence: an unknown researcher abstains; an unknown evidence ID is 404") {
    val rig = new Rig
    val (r, b) = send(rig.routes, post(body(UnknownQ)))
    assertEquals(r.status, Status.Ok)
    val a = answerOf(b)
    assertEquals(a("abstained").bool, true)
    assert(a("abstentionReason").strOpt.exists(_.nonEmpty) && a("researchers").arr.isEmpty)
    val (r2, b2) = send(rig.routes, get("/v1/evidence/ev-does-not-exist"))
    assertEquals((r2.status, err(b2)("code").str), (Status.NotFound, "not_found"))
    assertEquals(err(b2)("traceId").str, hdr(r2, "X-Trace-Id").get)
    val (r3, _) = send(rig.routes, get("/v1/evidence/" + "x" * 300))
    assertEquals(r3.status, Status.BadRequest)
  }

  test("a repeated request with the same Idempotency-Key replays; a different body conflicts") {
    val rig = new Rig
    val (r1, b1) = send(rig.routes, post(body(AliceQ), key = Some("key-0001")))
    val (r2, b2) = send(rig.routes, post(body(AliceQ), key = Some("key-0001")))
    assertEquals((r1.status, r2.status), (Status.Ok, Status.Ok))
    assertEquals(rig.counting.calls.get, 1)
    assertEquals(ujson.read(b1)("queryId").str, ujson.read(b2)("queryId").str)
    assertEquals(ujson.read(b1)("answer"), ujson.read(b2)("answer"))
    assertEquals(
      (hdr(r1, "Idempotent-Replayed"), hdr(r2, "Idempotent-Replayed")),
      (None, Some("true"))
    )
    val (r3, b3) = send(rig.routes, post(body(RankingQ), key = Some("key-0001")))
    assertEquals((r3.status, err(b3)("code").str), (Status.Conflict, "conflict"))
    assertEquals(rig.counting.calls.get, 1)
    // another caller with the same key is another request
    val (r4, b4) =
      send(rig.routes, post(body(AliceQ), token = tokFor("user-2"), key = Some("key-0001")))
    assertEquals(r4.status, Status.Ok)
    assertNotEquals(ujson.read(b4)("queryId").str, ujson.read(b1)("queryId").str)
    // a malformed key is 400
    val (r5, _) = send(rig.routes, post(body(AliceQ), key = Some("bad key!")))
    assertEquals(r5.status, Status.BadRequest)
  }

  test("an asynchronous replay returns the same job and schedules once") {
    val rig = new Rig
    val (_, b1) = send(rig.routes, post(asyncBody(), key = Some("key-async-1")))
    val (r2, b2) = send(rig.routes, post(asyncBody(), key = Some("key-async-1")))
    assertEquals(r2.status, Status.Accepted)
    assertEquals(ujson.read(b1)("jobId").str, ujson.read(b2)("jobId").str)
    assertEquals(hdr(r2, "Idempotent-Replayed"), Some("true"))
    assertEquals(rig.scheduler.size, 1)
    rig.scheduler.drain()
    assertEquals(rig.counting.calls.get, 1)
  }

  test("pending asynchronous jobs are bounded: 503 and the key is released") {
    val rig = new Rig(maxPending = 1)
    assertEquals(send(rig.routes, post(asyncBody()))._1.status, Status.Accepted)
    val (r, b) = send(rig.routes, post(asyncBody(), key = Some("key-bound-1")))
    assertEquals((r.status, err(b)("code").str), (Status.ServiceUnavailable, "unavailable"))
    rig.scheduler.drain()
    assertEquals(
      send(rig.routes, post(asyncBody(), key = Some("key-bound-1")))._1.status,
      Status.Accepted
    )
  }

  test("a keyless asynchronous 503 leaves no record behind") {
    val rig = new Rig(maxPending = 1)
    assertEquals(send(rig.routes, post(asyncBody()))._1.status, Status.Accepted)
    assertEquals(rig.store.size, 1)
    val (r, _) = send(rig.routes, post(asyncBody()))
    assertEquals(r.status, Status.ServiceUnavailable)
    assertEquals(rig.store.size, 1)
  }

  test("terminal records are evicted, so 1001 sequential queries all succeed") {
    val rig = new Rig
    val statuses = (1 to 1001).map(_ => send(rig.routes, post(body(AliceQ)))._1.status).toSet
    assertEquals(statuses, Set(Status.Ok))
    assertEquals(rig.store.size, 1000)
  }

  test("a store full of in-flight records still refuses with 503") {
    val rig = new Rig(maxRecords = 2)
    assertEquals(send(rig.routes, post(asyncBody()))._1.status, Status.Accepted)
    assertEquals(send(rig.routes, post(asyncBody()))._1.status, Status.Accepted)
    assertEquals(send(rig.routes, post(asyncBody()))._1.status, Status.ServiceUnavailable)
    rig.scheduler.drain()
    assertEquals(send(rig.routes, post(asyncBody()))._1.status, Status.Accepted)
  }

  test("a workflow that hangs is 504; the failed key is released and can be retried") {
    val latch = new java.util.concurrent.CountDownLatch(1)
    val rig = new Rig(
      engine = Some(new FnEngine((_, _) => { latch.await(); Left(WorkflowError.Timeout("x", 1)) })),
      policy = ResiliencePolicy(20.millis, 1, Duration.Zero, 5, 30.seconds)
    )
    val (r, b) = send(rig.routes, post(body(AliceQ), key = Some("key-slow-1")))
    assertEquals(
      (r.status, err(b)("code").str, err(b)("retryable").bool),
      (Status.GatewayTimeout, "timeout", true)
    )
    val (r2, _) = send(rig.routes, post(body(AliceQ), key = Some("key-slow-1")))
    assertEquals(r2.status, Status.GatewayTimeout)
    assertEquals(rig.counting.calls.get, 2)
    // the workflow's own timeout is also 504
    val own =
      new Rig(engine = Some(new FnEngine((_, _) => Left(WorkflowError.Timeout("graph", 5)))))
    assertEquals(send(own.routes, post(body(AliceQ)))._1.status, Status.GatewayTimeout)
  }

  test(
    "a downstream failure is 502 without its message; repeated failures open the breaker (503)"
  ) {
    val rig = new Rig(
      engine =
        Some(new FnEngine((_, _) => Left(WorkflowError.Downstream("graph", "password=hunter2"))))
    )
    val (r, b) = send(rig.routes, post(body(AliceQ)))
    assertEquals((r.status, err(b)("code").str), (Status.BadGateway, "dependency_failed"))
    assert(!b.contains("hunter2"))
    send(rig.routes, post(body(AliceQ)))
    send(rig.routes, post(body(AliceQ)))
    val (r4, b4) = send(rig.routes, post(body(AliceQ)))
    assertEquals((r4.status, err(b4)("code").str), (Status.ServiceUnavailable, "unavailable"))
    assertEquals(rig.counting.calls.get, 3)
    val (rz, _) = send(rig.routes, get("/readyz", None))
    assertEquals(rz.status, Status.ServiceUnavailable)
  }

  test("a failed asynchronous job reports the typed error on the job and its result") {
    val rig = new Rig(
      engine = Some(new FnEngine((_, _) => Left(WorkflowError.Downstream("graph", "boom"))))
    )
    val jobId = ujson.read(send(rig.routes, post(asyncBody()))._2)("jobId").str
    rig.scheduler.drain()
    val (r, b) = send(rig.routes, get(s"/v1/jobs/$jobId"))
    val j = ujson.read(b)
    assertEquals(
      (r.status, j("state").str, j("error")("code").str),
      (Status.Ok, "failed", "dependency_failed")
    )
    val (r2, b2) = send(rig.routes, get(s"/v1/jobs/$jobId/result"))
    assertEquals((r2.status, err(b2)("code").str), (Status.BadGateway, "dependency_failed"))
  }

  test("unauthorized: no token 401, wrong scope 403, expired 401, on every route") {
    val rig = new Rig
    val paths = List("/v1/jobs/job-abc", "/v1/jobs/job-abc/result", "/v1/explain/qry-abc")
    paths.foreach { p =>
      val (r, b) = send(rig.routes, get(p, None))
      assertEquals((r.status, err(b)("code").str), (Status.Unauthorized, "unauthorized"))
      assertEquals(hdr(r, "WWW-Authenticate"), Some("Bearer"))
      assertEquals(
        send(rig.routes, get(p, Some(TestJwt.token(scope = "other"))))._1.status,
        Status.Forbidden
      )
    }
    assertEquals(send(rig.routes, post(body(AliceQ), token = None))._1.status, Status.Unauthorized)
    assertEquals(
      send(
        rig.routes,
        post(body(AliceQ), token = Some(TestJwt.token(scope = "scholarchain/evidence.read")))
      )._1.status,
      Status.Forbidden
    )
    assertEquals(
      send(
        rig.routes,
        post(body(AliceQ), token = Some(TestJwt.token(exp = TestJwt.Now - 3600)))
      )._1.status,
      Status.Unauthorized
    )
    assertEquals(
      send(
        rig.routes,
        get("/v1/evidence/ev-1", Some(TestJwt.token(scope = "scholarchain/query.run")))
      )._1.status,
      Status.Forbidden
    )
    assertEquals(rig.counting.calls.get, 0)
  }

  test("a job, its result and its explanation belong to the caller that made the query") {
    val rig = new Rig
    val j = ujson.read(send(rig.routes, post(body(AliceQ)))._2)
    val other = tokFor("user-2")
    assertEquals(
      send(rig.routes, get(s"/v1/jobs/${j("jobId").str}", other))._1.status,
      Status.NotFound
    )
    assertEquals(
      send(rig.routes, get(s"/v1/jobs/${j("jobId").str}/result", other))._1.status,
      Status.NotFound
    )
    assertEquals(
      send(rig.routes, get(s"/v1/explain/${j("queryId").str}", other))._1.status,
      Status.NotFound
    )
    assertEquals(send(rig.routes, get("/v1/jobs/job-unknown"))._1.status, Status.NotFound)
    assertEquals(send(rig.routes, get("/v1/jobs/bad%20id"))._1.status, Status.BadRequest)
  }

  test("health is liveness, readiness reports failing checks, and /metrics is not served") {
    val down = new Rig(extraChecks = List(ReadinessCheck("audit-store", IO.pure(false))))
    val (h, hb) = send(down.routes, get("/healthz", None))
    assertEquals((h.status, ujson.read(hb)("status").str), (Status.Ok, "ok"))
    val (r, b) = send(down.routes, get("/readyz", None))
    assertEquals(
      (r.status, err(b)("code").str, err(b)("details")("audit-store").str),
      (Status.ServiceUnavailable, "unavailable", "failing")
    )
    assertEquals(err(b)("requestId").str, hdr(r, "X-Request-Id").get)
    val up = new Rig
    val (r2, b2) = send(up.routes, get("/readyz", None))
    assertEquals((r2.status, ujson.read(b2)("status").str), (Status.Ok, "ready"))
    assertEquals(send(up.routes, get("/metrics", None))._1.status, Status.NotFound)
  }

  test(
    "explain returns the plan, stages, versions, evidence IDs, scores, counters and no reasoning"
  ) {
    val rig = new Rig
    val q = ujson.read(send(rig.routes, post(body(AliceQ)))._2)
    val (r, b) = send(rig.routes, get(s"/v1/explain/${q("queryId").str}"))
    assertEquals(r.status, Status.Ok)
    val e = ujson.read(b)
    assertEquals(e("queryId").str, q("queryId").str)
    assertEquals(e("plan")("question").str, AliceQ)
    val stages = e("stages").arr.map(_("stage").str)
    assert(Seq("synthesis", "verification", "audit").forall(stages.contains), stages.toString)
    assertEquals(e("versions")("synthesizer").str, DeterministicSynthesizer.Version)
    assertEquals(e("versions")("modelVersion").str, "none")
    assertEquals(e("versions")("graphRunId").str, RunId)
    val ids = e("evidence")("ids").arr.map(_.str)
    assert(ids.nonEmpty)
    assertEquals(e("evidence")("scoreComponents").arr.map(_("evidenceId").str).toSet, ids.toSet)
    assert(e("counters")("claimsChecked").num > 0 && e("counters")("graphCalls").num >= 0)
    assertEquals(
      e("verification").str,
      answerOf(send(rig.routes, get(s"/v1/jobs/${q("jobId").str}/result"))._2)("verification").str
    )
    val lower = b.toLowerCase
    assert(
      !lower.contains("chain-of-thought") && !lower.contains("reasoning") && !e.obj.contains(
        "prompt"
      )
    )
    assertEquals(send(rig.routes, get("/v1/explain/qry-nope"))._1.status, Status.NotFound)
  }

  /** A model path over a scripted port: the real synthesizer writes the audit texts. */
  private def modelPath(step: String => Either[String, LlmReply]): Synthesizer =
    val template = "prompt-version: answer-synthesis-v2\n" +
      SynthesisPrompt.Placeholders.toVector.sorted.map(p => s"{{$p}}").mkString("\n")
    val prompt = SynthesisPrompt.parse(template).fold(sys.error, identity)
    new LlmSynthesizer(new ScriptedLlm(Vector.fill(5)(step)), prompt, cfg)

  test("explain shows attempt status and a fallback category, never downstream error text") {
    val secret = "AKIAFAKESECRET-hunter2"
    val rig = new Rig(synthesizer =
      modelPath(_ => Left(s"InvokeModel failed: connect https://host/?token=$secret"))
    )
    val (_, posted) = send(rig.routes, post(body(AliceQ)))
    val queryId = answerOf(posted)("queryId").str
    val audited = rig.wfAudit.get(queryId).toOption.flatten.get
    assert(audited.synthesisAttempts.exists(_.reason.exists(_.contains(secret))))
    assert(audited.synthesisFallback.exists(_.contains(secret)))
    val (r, b) = send(rig.routes, get(s"/v1/explain/$queryId"))
    assertEquals(r.status, Status.Ok)
    val jobId = ujson.read(posted)("jobId").str
    val job = send(rig.routes, get(s"/v1/jobs/$jobId"))._2
    val result = send(rig.routes, get(s"/v1/jobs/$jobId/result"))._2
    Seq(posted, b, job, result).foreach(x =>
      assert(!x.contains(secret) && !x.contains("InvokeModel"), x)
    )
    val e = ujson.read(b)
    assertEquals(e("synthesisFallback"), ujson.Str("model-call-failed"): ujson.Value)
    assertEquals(
      e("synthesisAttempts"),
      ujson.Arr(ujson.Obj("attempt" -> 1, "status" -> "call-failed")): ujson.Value
    )
  }

  test("explain of refused model replies drops each reason and names the fallback category") {
    val rig = new Rig(synthesizer = modelPath(_ => Right(LlmReply("not json", None, None))))
    val queryId = answerOf(send(rig.routes, post(body(AliceQ)))._2)("queryId").str
    val e = ujson.read(send(rig.routes, get(s"/v1/explain/$queryId"))._2)
    assertEquals(e("synthesisFallback"), ujson.Str("model-output-refused"): ujson.Value)
    val attempts = e("synthesisAttempts").arr
    assert(attempts.nonEmpty)
    assert(attempts.forall(_.obj.keySet == Set("attempt", "status")), attempts.toString)
    assert(attempts.forall(_("status").str == "refused"))
  }

  test("every synthesis fallback text maps to a fixed category; unknown text is other") {
    val got = Vector(
      "the evidence stage abstained; no model call",
      "model call failed: boom",
      "model output refused 3 times",
      "output token budget of 10 per query exhausted",
      "the model abstained although deterministic evidence exists",
      "secret-password=hunter2"
    ).map(QueryService.fallbackCategory)
    assertEquals(
      got,
      Vector(
        "evidence-abstained",
        "model-call-failed",
        "model-output-refused",
        "token-budget-exhausted",
        "model-abstained",
        "other"
      )
    )
  }

  test("explain of a job that has not finished is 409; a store failure on explain is 502") {
    val rig = new Rig
    val q = ujson.read(send(rig.routes, post(asyncBody()))._2)
    val (r, b) = send(rig.routes, get(s"/v1/explain/${q("queryId").str}"))
    assertEquals((r.status, err(b)("code").str), (Status.Conflict, "conflict"))
    val broken = new Rig(audit = Some(new AuditLog {
      def append(r: AuditRecord) = Left("down")
      def get(queryId: String) = Left("dynamodb password=hunter2")
    }))
    val bq = ujson.read(send(broken.routes, post(body(AliceQ)))._2)
    val (r2, b2) = send(broken.routes, get(s"/v1/explain/${bq("queryId").str}"))
    assertEquals(r2.status, Status.BadGateway)
    assert(!b2.contains("hunter2"))
  }

  test("lifecycle with fakes: query, claims, evidence by ID, job, result and explanation agree") {
    val rig = new Rig
    val (_, b) = send(rig.routes, post(asyncBody(RankingQ)))
    val jobId = ujson.read(b)("jobId").str
    rig.scheduler.drain()
    val result = answerOf(send(rig.routes, get(s"/v1/jobs/$jobId/result"))._2)
    val queryId = result("queryId").str
    val claimIds = result("claims").arr.flatMap(_("evidenceIds").arr.map(_.str)).distinct
    assert(claimIds.nonEmpty)
    val explained = ujson.read(send(rig.routes, get(s"/v1/explain/$queryId"))._2)
    val known = explained("evidence")("ids").arr.map(_.str).toSet
    assert(claimIds.forall(known.contains))
    claimIds.foreach { id =>
      val (r, eb) = send(rig.routes, get(s"/v1/evidence/$id"))
      assertEquals(r.status, Status.Ok)
      assertEquals(ujson.read(eb)("evidenceId").str, id)
    }
    assertEquals(rig.counting.calls.get, 1)
  }

  test("end to end: a signed request, an evidence-backed answer, and each cited evidence item") {
    val rig = new Rig
    val signed = Some(TestJwt.token(scope = "scholarchain/query.run scholarchain/evidence.read"))
    val (r, b) = send(rig.routes, post(body(AliceQ), token = signed, key = Some("e2e-key-1")))
    assertEquals(r.status, Status.Ok)
    val a = answerOf(b)
    assertEquals(a("abstained").bool, false)
    assertEquals(a("provenance")("runId").str, RunId)
    val claim = a("claims")(0)
    assert(claim("text").str.nonEmpty && claim("verificationStatus").str != "UNSUPPORTED")
    val id = claim("evidenceIds")(0).str
    val (er, eb) = send(rig.routes, get(s"/v1/evidence/$id", signed))
    assertEquals(er.status, Status.Ok)
    val ev = ujson.read(eb)
    assertEquals(ev("evidenceId").str, id)
    assert(ev("sourceUri").str.nonEmpty)
    assertEquals(ev("provenance")("runId").str, RunId)
    assertEquals(hdr(er, "X-Request-Id").isDefined, true)
    assert(!eb.contains("Bearer") && !eb.contains(signed.get))
  }

  test("LLM_ENABLED defaults to false and selects the deterministic synthesizer") {
    assertEquals(QueryConfig.fromEnv(Map.empty), Right(QueryConfig(false)))
    assertEquals(QueryApp.synthesizer(QueryConfig(), None), Right(DeterministicSynthesizer))
    assertEquals(QueryConfig.fromEnv(Map("LLM_ENABLED" -> "true")), Right(QueryConfig(true)))
    assert(QueryApp.synthesizer(QueryConfig(true), None).isLeft)
    assert(QueryConfig.fromEnv(Map("LLM_ENABLED" -> "maybe")).isLeft)
  }
