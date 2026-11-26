package scholarchain.rag

import scholarchain.core.*
import RagWorld.*

/** Phase 11 C19 sweep, pinned (R-4):
  *   - (a) the evidence builder's note for an item that fails core validation names the item (or
  *     "(malformed ID)"), never the validation text, which can echo stored values;
  *   - (b) a bundle or answer that breaks core validation is an internal invariant, so it is
  *     `WorkflowError.Downstream("evidence")` or `Downstream("answer")`, never `InvalidRequest`.
  */
class InvariantErrorSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private val Secret = "Bearer sc-fake-token-c19-9a7f3e"
  private val builder = new EvidenceBuilderAgent(w.ctx)

  private val valid = w.ctx.evidence(
    EvidenceType.METRIC_RECORD,
    "r4-valid",
    "dynamodb://metrics/r4-valid",
    Vector(Alice),
    Map("kind" -> "researcher-metrics"),
    "metric-v1"
  )
  // A well-formed ID whose first validation error ("malformed chunk ID '<value>'") echoes the secret.
  private val badChunk = w.ctx
    .evidence(
      EvidenceType.METRIC_RECORD,
      "r4-bad-chunk",
      "dynamodb://metrics/r4-bad-chunk",
      Vector(Alice),
      Map.empty,
      "metric-v1"
    )
    .copy(chunkId = Some(Secret))
  // A malformed ID that carries the secret itself.
  private val badId = valid.copy(evidenceId = s"ev-$Secret")

  test("preconditions: the fixtures fail core validation with text that echoes the secret") {
    assert(Validate.evidence(valid).isEmpty, Validate.evidence(valid))
    assert(Validate.evidence(badChunk).head.contains(Secret))
    assert(Validate.evidence(badId).head.contains(Secret))
    assert(Ids.isEvidenceId(badChunk.evidenceId) && !Ids.isEvidenceId(badId.evidenceId))
  }

  test(
    "an invalid item is dropped with a fixed note that names its ID, never the validation text"
  ) {
    val (b, notes) =
      builder
        .build("qry-r4", Vector(valid, badChunk), Vector.empty, Vector.empty)
        .fold(
          e => fail(e.describe),
          identity
        )
    assertEquals(b.deterministic.map(_.evidenceId), Vector(valid.evidenceId))
    assertEquals(
      notes,
      Vector(s"dropped invalid evidence item ${badChunk.evidenceId}: it failed core validation")
    )
    assert(!notes.exists(_.contains(Secret)), notes)
    assert(!notes.exists(_.contains("malformed")), notes)
  }

  test("an item with a malformed ID is noted as (malformed ID), so the ID text is not echoed") {
    val (b, notes) =
      builder
        .build("qry-r4", Vector(badId, valid), Vector.empty, Vector.empty)
        .fold(
          e => fail(e.describe),
          identity
        )
    assertEquals(b.all.map(_.evidenceId), Vector(valid.evidenceId))
    assertEquals(
      notes,
      Vector("dropped invalid evidence item (malformed ID): it failed core validation")
    )
    assert(!notes.exists(_.contains(Secret)), notes)
  }

  test("a bundle that breaks core validation is Downstream(evidence), not InvalidRequest") {
    // An empty query ID is refused by the planner before stage 7, so only the public builder reaches
    // this invariant; Validate.bundle then reports "bundle without bundle or query ID".
    builder.build("", Vector(valid), Vector.empty, Vector.empty) match
      case Left(WorkflowError.Downstream(stage, message)) =>
        assertEquals(stage, "evidence")
        assert(message.startsWith("bundle invalid: "), message)
        assert(message.contains("bundle without bundle or query ID"), message)
      case other => fail(s"expected Downstream(evidence), got $other")
  }

  test("an answer that breaks core validation is Downstream(answer); nothing is stored") {
    // A synthesizer port that abstains with a blank reason: the answer then abstains without a
    // reason, which Validate.answer refuses after verification.
    val blankReason = new Synthesizer:
      def synthesize(e: EvidenceStageResult): SynthesisResult =
        val r = DeterministicSynthesizer.synthesize(e)
        r.copy(output = r.output.copy(abstain = true, abstentionReason = Some("   ")))
    val log = new InMemoryAuditLog
    val (wf, _) = w.workflow(blankReason, log)
    val q = """What are the collaboration metrics of "Alice Arden" since 2015?"""
    val e =
      wf.evidence.run("qry-r4-answer", AnalysisRequest(q)).fold(x => fail(x.describe), identity)
    assertEquals(e.abstention, None, "the precondition needs stages 1 to 7 not to abstain")
    wf.run("qry-r4-answer", AnalysisRequest(q)) match
      case Left(WorkflowError.Downstream(stage, message)) =>
        assertEquals(stage, "answer")
        assert(message.startsWith("answer invalid: "), message)
        assert(message.contains("abstains without a reason"), message)
      case other => fail(s"expected Downstream(answer), got $other")
    assertEquals(log.all, Vector.empty[AuditRecord])
  }
