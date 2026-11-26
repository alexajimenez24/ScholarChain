HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (rag-engineer tier high, .agents/models.json)

## C19 (MUST, security): downstream error text in client-visible fields
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 11 repair round 1, C19 (found by service-engineer, repair-01-service-engineer.md RISKS 1).
FILES CREATED: tests/module-unit/rag/ErrorTextSuite.scala (4 tests)
FILES MODIFIED: modules/rag/src/main/scala/scholarchain/rag/Evidence.scala, Workflow.scala
COMMANDS RUN: `timeout 30m sbt rag/scalafmt rag/Test/scalafmt rag/test` (exit 0); `timeout 30m sbt "adversarialTests/testOnly *RuntimeEdgeSuite *FilteredRankGateSuite"` (exit 0, no environment redirect).
TEST RESULTS:
- rag: Passed: Total 158, Failed 0, Errors 0 (154 before, +4).
- adversarial: Passed: Total 16, Failed 0, Errors 0.
- Full sbt check: NOT RUN.
VALIDATION RESULTS:
- Verified by reading: the failed-semantic note was built from `e.describe`, so embedder or S3 Vectors text went into notes, then into answer.summary, into the model prompt, and into the response, on the deterministic path too.
  - Fix: `EvidencePipeline.SemanticUnavailableNote`, a fixed sentence.
  - Operators keep the audit stage timing "semantic" = "failed".
- Wider sweep (rag): where store, graph, model or validation text reached client fields, and the fix.
  - `dropped invalid <id>: <validation text>` (Evidence.scala builder notes, which reach the summary). The validation text can echo stored values. It is now "dropped invalid evidence item <id or (malformed ID)>: it failed core validation".
  - "bundle invalid: ..." and "answer invalid: ..." were `InvalidRequest`, which services show to the client (`m.take(200)`), and they carry validation text. They are now `Downstream("evidence")` and `Downstream("answer")`: internal invariants, and services drop the message.
  - Checked and left as they are:
    - graph, resolve, ranking, semantic and audit errors are already `Downstream`, and services show only "<stage> failed";
    - the template-build `InvalidRequest` text comes from rag's own parameter checks;
    - model errors (`synthesisAttempts`, `synthesisFallback`) go only to the audit, which services' explain now reduces (C16);
    - DroppedHit reasons reach only the audit as a count;
    - the C14 community note is already fixed text.
- Tests use the secret-like string "SECRET-arn:aws:iam::123456789012:role/leak-TOKEN-7731" in three failing fakes: an embedder, a VectorStore query and a CommunityReader. Two questions run on the deterministic path. The serialized AnswerResponse, the evidence notes and the synthesis summary never contain it; the fixed note is present; the stage is "failed". A healthy-store control has no note.
ASSUMPTIONS: The audit record is operator data; its client exposure is service-engineer's (explain, C16).
RISKS:
- Services behaviour change: an internally invalid bundle or answer is now `WorkflowError.Downstream`, not `InvalidRequest`, so QueryService maps it to its Downstream error ("evidence failed" / "answer failed") instead of 400 invalid_request with the message. No test pinned the old mapping. Nothing else in services changes; no services re-run was done here.
- The raw semantic error is no longer kept anywhere (rag has no operator log). Diagnosis relies on the stage status, or on logging added in services or adapters.
- evidence-pipeline-v1 was not bumped (security fix of note text; human may decide).
- MODULE.md notes: the new constant `EvidencePipeline.SemanticUnavailableNote`; rag tests: 158.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer rerun and full sbt check; service-engineer may add operator logging of downstream errors outside client fields.
