HARNESS: claude-code
MODEL: standard tier per .agents/models.json (file not re-read this run); runtime model claude-sonnet-5-5

STATUS: COMPLETE (C15, C16 in services, B-19). One related leak that originates in rag is OPEN (see RISKS 1).
OWNER: service-engineer
WORK ORDER: Phase 11 repair round 1: C15, C16, B-19; C14 consumer note.
FILES CREATED: none in the repository
FILES MODIFIED (under /home/ajcreator/scholarchain):
- modules/services/common/src/main/scala/scholarchain/services/common/Auth.scala
- modules/services/query/src/main/scala/scholarchain/services/query/QueryService.scala
- tests/module-unit/services/common/CommonSuite.scala
- tests/module-unit/services/common/TestJwt.scala (added a drop parameter)
- tests/module-unit/services/query/QueryServiceSuite.scala
COMMANDS RUN (one sbt at a time, foreground):
- timeout 30m sbt services/scalafmt services/Test/scalafmt services/test (rc 0)
- timeout 30m sbt "adversarialTests/testOnly *JwtClaimsSuite *RuntimeEdgeSuite" (rc 0)
TEST RESULTS:
- servicesCommon 31/31, queryService 25/25, graphService 16/16, embeddingWorker 10/10: 82 passed, 0 failed.
- JwtClaimsSuite 7/7 (token_use test was red, now green; not edited), RuntimeEdgeSuite 9/9: 16 passed, 0 failed.
- Full `sbt check` NOT RUN (not in this work order's gates).
VALIDATION RESULTS:
- C15 (B-11): token_use is bound to the audience claim. access: client_id must equal the app client; id: aud must contain it. Refresh, bogus, wrong case, non-string and missing give AuthFailure.WrongTokenUse (client sees the same generic 401). An access token matched only by aud, or an ID token matched only by client_id, is WrongAudience. Principal.clientId is the verified app client.
  Why this over "access only": docs/api.md already documents "audience (client_id or aud)", which stays true, while the claim of one token kind can no longer satisfy the other. Cognito ID tokens carry no scope claim and every protected route requires a scope, so an ID token gets 403 on every route (by design of the claims; no route test mints one).
- B-19: the CommonSuite test now mints token_use "id" with aud and no client_id. Added tests: token_use refusals and the binding.
- C16 (B-12): explain synthesisAttempts is now [{attempt, status}] only (reason, reply hash and per-attempt token fields dropped; token totals stay in counters). synthesisFallback is null/absent as before, or one of evidence-abstained, model-call-failed, model-output-refused, token-budget-exhausted, model-abstained, other (unknown text maps to other). The audit record itself is unchanged.
- C16 tests (+3): the real LlmSynthesizer over a ScriptedLlm whose Left holds a fake secret; the test first checks the audit does hold it, then that the POST body, job, result and explain do not. Plus refused replies, and the category table. Only call-failed and refused are driven end to end; the other three categories are tested by their literal rag texts, so a rag wording change would turn them into "other" (safe, not detected by these tests).
- Other service paths, by reading: job and result errors carry only code, safe message and retryable; WorkflowError.Downstream, Timeout and BudgetExceeded messages are dropped (QueryService.runEngine, workflowError); InvalidRequest and Unsupported messages that reach the client come from local planner/validation code; port Left strings are discarded by Downstream; community-size read failures are fixed texts. Graph error paths were checked by grep only (Left goes to Downstream, or to a local template validation message); the worker serves health and readiness only.
- Route table, status mapping, timeout policy and dependency graph: unchanged. Authentication: Cognito JWT, now with token_use as above.
ASSUMPTIONS: rag's attempt status values (call-failed, refused, abstained, accepted) are fixed constants, as read in Synthesis.scala.
RISKS:
1. OPEN, owner rag-engineer (security, same class as C16; found by reading, not reproduced by a test): downstream text reaches the query response and job result. SemanticContext.scala ~255-266 builds Left("query embedding failed: <e.text>") or "query <index>: <e.text>"; Agents.scala ~1059 wraps it as WorkflowError.Downstream("semantic", e.take(200)); Evidence.scala ~345 adds s"semantic context unavailable: ${e.describe}" to notes; Workflow.scala ~298-301 appends notes to answer.summary ("Missing or limited evidence: ..."). The notes also enter the model prompt (Synthesis.scala ~684). It is NOT latent behind LLM_ENABLED: the deterministic path takes it too. Suggested fix: a fixed note ("semantic context unavailable") with no e.describe, plus a rag test with a secret-like vector-store error. Not patched in services: rewriting the summary there is fragile and would break the audit answerHash. Recommend fixing in this round, before any cloud run.
2. Not done: B-17 (JWKS fetch failure gives 401, not 503; CARRY). No production wiring.
3. C14 consumer: services main sources construct no RuntimeStores, so nothing was wired. The cloud phase wiring must pass communities = Some(CommunityReader) in RuntimeStores, otherwise community size is reported as no-store.
Notes for modules/services/MODULE.md and docs/api.md (documentation-engineer):
- Auth: add the token_use rule and AuthFailure.WrongTokenUse; "audience (client_id or aud)" becomes "client_id for token_use access, aud for token_use id".
- explain: synthesisAttempts is {attempt, status}; synthesisFallback is a fixed category (list above).
- Close MINOR-1 for token_use (missing exp, nbf, sub and leeway are covered by JwtClaimsSuite) and MINOR-2. servicesCommon 31, queryService 25, total 82.
- The new public function is QueryService.fallbackCategory.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator routes RISKS 1 to rag-engineer; then test-engineer rerun and a fresh code-reviewer.
