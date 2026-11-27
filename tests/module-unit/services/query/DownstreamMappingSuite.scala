package scholarchain.services.query

import cats.effect.IO
import org.http4s.*
import scholarchain.rag.*
import QueryFixture.*
import QFx.*

/** Phase 11 R-4 (services part): what a client sees for `WorkflowError.Downstream("evidence")` and
  * `Downstream("answer")`, the two internal invariants of C19. Today both are 502
  * `dependency_failed` (retryable). Whether they should be 500 is carried; these tests pin the
  * current behaviour.
  *
  * The body is the one of the `workflow` breaker, so it names the dependency and neither the stage
  * nor the internal message.
  */
class DownstreamMappingSuite extends munit.FunSuite:
  // Hang guard only: the first test pays one-time JVM setup (RSA keys, IO runtime, class
  // loading), over 30s in a loaded `sbt check`. Product timeouts are unchanged.
  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")
  private val Secret = "Bearer sc-fake-token-r4-5d1e password=hunter2-r4"
  private val Fragments = Vector(Secret, "sc-fake-token-r4-5d1e", "hunter2-r4")
  private val Message = "a downstream dependency failed"

  private def failing(stage: String) =
    new FnEngine((_, _) => Left(WorkflowError.Downstream(stage, s"$stage invalid: $Secret")))

  private def visible(r: Response[IO], b: String): String =
    (r.status.toString +: r.headers.headers.map(h => s"${h.name}: ${h.value}") :+ b).mkString("\n")

  private def assertHidden(texts: Seq[String], also: Seq[String] = Nil): Unit =
    for
      t <- texts
      f <- Fragments ++ also
    do assert(!t.contains(f), s"'$f' is visible in: $t")

  private def assertErrorResponse(r: Response[IO], b: String): Unit =
    assertEquals(r.status, Status.BadGateway)
    val expected: ujson.Value = ujson.Obj(
      "error" -> ujson.Obj(
        "code" -> "dependency_failed",
        "message" -> Message,
        "retryable" -> true,
        "requestId" -> hdr(r, "X-Request-Id").get,
        "traceId" -> hdr(r, "X-Trace-Id").get,
        "details" -> ujson.Obj("dependency" -> "workflow")
      )
    )
    assertEquals(ujson.read(b), expected)

  /** Runs one failing async job and checks every view of it; returns the visible texts. */
  private def asyncViews(rig: Rig): Seq[String] =
    val (pr, pb) = send(rig.routes, post(asyncBody()))
    assertEquals(pr.status, Status.Accepted)
    val posted = ujson.read(pb)
    val jobId = posted("jobId").str
    rig.scheduler.drain()

    val (jr, jb) = send(rig.routes, get(s"/v1/jobs/$jobId"))
    val job = ujson.read(jb)
    assertEquals(
      (jr.status, job("state").str, job("stage").str),
      (Status.Ok, "failed", "workflow")
    )
    assertEquals(
      job.obj.keySet.toSet,
      Set(
        "jobId",
        "queryId",
        "mode",
        "state",
        "stage",
        "createdAt",
        "updatedAt",
        "completedAt",
        "error",
        "links"
      )
    )
    val jobError: ujson.Value =
      ujson.Obj("code" -> "dependency_failed", "message" -> Message, "retryable" -> true)
    assertEquals(job("error"), jobError)
    assertEquals(job("links").obj.keySet.toSet, Set("self", "explain"))

    val (rr, rb) = send(rig.routes, get(s"/v1/jobs/$jobId/result"))
    assertErrorResponse(rr, rb)

    // A failed workflow stores no audit record, so there is nothing to explain.
    val (er, eb) = send(rig.routes, get(s"/v1/explain/${posted("queryId").str}"))
    assertEquals((er.status, err(eb)("code").str), (Status.NotFound, "not_found"))

    Seq(visible(pr, pb), visible(jr, jb), visible(rr, rb), visible(er, eb))

  Seq("evidence", "answer").foreach { stage =>
    test(s"sync Downstream($stage) is 502 dependency_failed without the internal message") {
      val rig = new Rig(engine = Some(failing(stage)))
      val (r, b) = send(rig.routes, post(body(AliceQ), key = Some("key-r4-sync")))
      assertErrorResponse(r, b)
      // retryable, so the sync Idempotency-Key is released and the same request runs again
      val (r2, b2) = send(rig.routes, post(body(AliceQ), key = Some("key-r4-sync")))
      assertErrorResponse(r2, b2)
      assertEquals(hdr(r2, "Idempotent-Replayed"), None)
      assertEquals(rig.counting.calls.get, 2)
      assert(rig.sink.all.nonEmpty)
      assertHidden(
        Seq(visible(r, b), visible(r2, b2)) ++ rig.sink.all,
        Seq(s"$stage invalid", s"$stage failed")
      )
    }

    test(s"async Downstream($stage): job and result carry the typed error, never the message") {
      val rig = new Rig(engine = Some(failing(stage)))
      val views = asyncViews(rig)
      assert(rig.sink.all.nonEmpty)
      assertHidden(views ++ rig.sink.all, Seq(s"$stage invalid", s"$stage failed"))
    }
  }

  /** The real workflow, with a synthesizer that abstains with a blank reason and puts the secret in
    * its summary: `Validate.answer` refuses the answer, which is `Downstream("answer")`.
    */
  private val blankReason: Synthesizer = new Synthesizer:
    def synthesize(e: EvidenceStageResult): SynthesisResult =
      val r = DeterministicSynthesizer.synthesize(e)
      r.copy(output =
        r.output.copy(summary = Secret, abstain = true, abstentionReason = Some("   "))
      )

  private val Internal = Seq("answer invalid", "abstains without a reason", "answer failed")

  test("the real workflow's invalid answer reaches a sync client as the same 502 body") {
    val rig = new Rig(synthesizer = blankReason)
    rig.real.run("qry-r4-probe", AnalysisRequest(AliceQ)) match
      case Left(WorkflowError.Downstream("answer", _)) => ()
      case other => fail(s"expected Downstream(answer), got $other")
    val (r, b) = send(rig.routes, post(body(AliceQ)))
    assertErrorResponse(r, b)
    assertHidden(visible(r, b) +: rig.sink.all, Internal)
  }

  test("the real workflow's invalid answer reaches an async job and its result the same way") {
    val rig = new Rig(synthesizer = blankReason)
    val views = asyncViews(rig)
    assertHidden(views ++ rig.sink.all, Internal)
  }
