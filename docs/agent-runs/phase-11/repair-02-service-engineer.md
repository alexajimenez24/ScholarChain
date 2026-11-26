HARNESS: claude-code
MODEL: sonnet (claude-sonnet-5-5), effort medium (standard tier, .agents/models.json)
STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: Phase 11 repair round 2, R-4 services part: pin how QueryService maps Downstream("evidence") and Downstream("answer"), with no internal or secret-like text client-visible.
FILES CREATED: /home/ajcreator/scholarchain/tests/module-unit/services/query/DownstreamMappingSuite.scala (6 tests)
FILES MODIFIED: none (no production change; no leak found)
COMMANDS RUN:
- timeout 30m sbt queryService/Test/scalafmt (twice; each "Formatting 1 Scala sources", success)
- timeout 30m sbt services/test (once, passed)
TEST RESULTS: 88 passed, 0 failed, 0 errors (project totals: worker 10, common 31, graph 16, query 31). DownstreamMappingSuite 6 of 6; QueryServiceSuite 25 of 25.
VALIDATION RESULTS:
- Route and status mapping pinned, same for both stages. Sync POST /v1/query: 502; error = code dependency_failed, message "a downstream dependency failed", retryable true, requestId, traceId, details {dependency: workflow}.
- Sync with an Idempotency-Key: the key is released; the repeat runs the engine again (2 calls), not marked replayed.
- Async: POST 202; GET job 200, state failed, stage workflow, error = {code, message, retryable} only; GET result 502 with the body above; GET explain 404 not_found.
- Fake engine: a fake secret in the Downstream message is absent from status, headers and body of these responses and from the metric lines, as are "<stage> invalid" and "<stage> failed".
- Real workflow, blank-reason synthesizer with the fake secret in its summary (precondition: Downstream("answer")): same sync and async results; secret and validation text absent. Evidence is covered by the fake only (unreachable end to end, per rag-engineer).
- Timeout policy (test rig: 5 s, 1 attempt, breaker at 3 failures), dependency graph and Cognito JWT authentication: unchanged. Breaker counting is already pinned in QueryServiceSuite for stage "graph"; not repeated here.
- Observed: runEngine turns Downstream into a breaker failure, so the client gets the breaker's ApiError. The Downstream branch of QueryService.workflowError (details.stage) is not reached on this path.
ASSUMPTIONS: The work order's gate (services/test) replaces sbt check for this item. Metric lines count as logs, not client fields; they are checked too.
RISKS:
- CARRY, not changed: 502 versus 500 for these deterministic invariants. Today they are retryable, release the sync key and count toward the query breaker, so repeats can open it (503) for every caller. A fix must separate them from real dependency failures in runEngine before the breaker.
- sbt check not run here, and no mutation testing; the new test file is newer than any earlier sbt check marker, so a fresh sbt check is still needed.
- State left: one new untracked file, no commit. Other modified services files in git status predate this task.
- modules/services/MODULE.md note (documentation-engineer): test-only, public API unchanged; record the CARRY and the unreached branch as known issues.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer rerun and sbt check, then a fresh code-reviewer (review-report-03.md).
