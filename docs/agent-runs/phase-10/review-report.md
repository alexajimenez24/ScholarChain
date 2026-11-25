HARNESS: claude-code
MODEL: opus, effort high (code-reviewer is high tier in .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 10 fresh adversarial review (review-report.md): modules/services/**, tests/module-unit/services/**, docs/api.md, modules/services/MODULE.md, STATUS.md; also whether docs/api.md matches the tests.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git status --short; git diff --stat; git diff -- STATUS.md modules/services/MODULE.md; git diff --stat HEAD -- build.sbt project/; find/wc over modules/services and tests/module-unit/services; grep for imports, logging and the storage/rag types used; diff of the three TestJwt copies. I did not run sbt, as the work order said.
TEST RESULTS: none run. I counted the tests by reading the suites: common 28, graph 16, worker 10, query 19, which matches the reports. All 10 tests the work order requires exist and check real behavior, and so does the end-to-end test from a signed POST to the evidence route.
VALIDATION RESULTS: REQUEST_CHANGES (0 CRITICAL, 2 MAJOR, 8 MINOR)
- MAJOR-1, service-engineer. modules/services/query/src/main/scala/scholarchain/services/query/QueryService.scala:63-79 and :147-156.
  - Defect: QueryStore never removes a record, and `add` refuses new IDs once 1000 records exist.
  - Failure input: 1000 distinct POST /v1/query calls, after which every new query gets 503 "the query store is full" until the process restarts. /readyz still reports ready because no check covers the store, so the service never recovers on its own; a Phase 15 load test will hit this.
  - Related leak: on the async path the record is added at :149 before the pending check at :155. A 503 "too many pending jobs" releases the key but leaves an orphan Queued record that is never cleaned up. Without a key, each such 503 permanently uses up one slot.
  - Repair: evict terminal records (TTL or LRU over Succeeded and Failed), check the pending bound before `store.add` or remove the record on refusal, and optionally expose store capacity in readiness.
  - Add tests: 1001 queries in sequence still succeed; a keyless async 503 leaves no record.
  - Must stay green: the idempotency, async-replay, pending-bound and ownership tests in QueryServiceSuite.
- MAJOR-2, service-engineer (cost risk). modules/services/common/src/main/scala/scholarchain/services/common/ServiceKit.scala:90-92, :104, :121.
  - Defect: the EMF `Operation` dimension is the method plus the first two raw path segments, and it is emitted for unmatched routes too, before any auth.
  - Failure input: `GET /x<n>/y<n>` for n = 1..10000 creates 10000 distinct dimension values, so 40000 CloudWatch custom metrics, billed per metric against a $25 hard cap. Whether this is reachable depends on API Gateway forwarding a proxy route, which is not built yet.
  - The claim that job IDs do not inflate cardinality (docs/api.md:228) is true, but it does not cover unmatched paths.
  - Repair: derive Operation from a fixed allowlist of route templates, and use a constant such as "unmatched" for 404s from no route.
  - Add a test: two random unmatched paths produce the same Operation value.
  - Must stay green: ServiceKitSuite's EMF test ("GET /v1/ok") and GraphFailureSuite's metrics test ("GET /v1/graph").
- MINOR-1, service-engineer. Auth.scala:157-180 never checks `token_use`, which Cognito's guidance says to verify.
  - The JWT tests also lack cases for a missing `exp`, `nbf`, a missing `sub` and the 30 s leeway (CommonSuite.scala:62-86).
  - Otherwise the JWT checks are correct: alg is pinned to RS256 (none and HS256 are refused), `kid` is required, and iss, aud or client_id, exp and scope are all checked.
- MINOR-2, service-engineer. QueryService.scala:282-283 explain returns `synthesisAttempts[].reason` and `synthesisFallback`.
  - On the model path these carry `err.take(200)` from the LLM client (rag Synthesis.scala:785, 791), which would put a downstream message in a client response.
  - Latent while LLM_ENABLED=false. No chain-of-thought is exposed: removed-claim text, notes and summaryReplaced are left out.
- MINOR-3, service-engineer. Resilience.scala:40-45 makes the timeout depend on the port being interruptible.
  - `IO.interruptible(...).timeout` waits on cancellation, so a blocking call that ignores Thread.interrupt defeats the 504.
  - The default ResiliencePolicy (maxAttempts 2) would re-run a non-idempotent workflow if production wiring uses it. The tests avoid both cases (an interruptible latch, 1 attempt).
- MINOR-4, service-engineer. QueryService.scala:168-171: a sync request cancelled mid-workflow (client disconnect) leaves the record Running and its key claimed for good, so every replay gets 409.
- MINOR-5, service-engineer. EmbeddingWorker.scala:138-139: an exception from `receive` or `acknowledge` ends `loop` (foreverM fails) while /healthz stays 200.
  - Separately, a transient PutVectors `Left` is counted as `rejected` rather than `failedBatches`. Neither is acknowledged, so dead-letter behavior is still correct.
- MINOR-6, documentation-engineer. docs/api.md:80-81 says "a failed job returns its stored typed error on both routes". The test (QueryServiceSuite:325-339) shows GET /v1/jobs/{id} returns 200 with state failed and an `error` object; only /result returns 502.
- MINOR-7, documentation-engineer. docs/api.md:195-196 names the wrong test. The hunter2 secret case is ServiceKitSuite's "a typed error maps to its status; an unexpected exception is a generic 500 that leaks nothing"; the "a thrown exception ..." test uses "x".
- MINOR-8, documentation-engineer. docs/api.md:7 says every example comes from a test, but these come from code, not tests:
  - Retry-After (line 79; set at QueryRoutes.scala:105);
  - BudgetExceeded -> 422 (line 91);
  - pageSize default 20 (line 140);
  - the 200-result bound (line 148; the test uses maxResults=5).
  - Fix: mark them as code facts or add assertions.
  - Every other example and status in docs/api.md matches the tests.
- Clean: module boundaries (common->core; graph and worker->core, storage, common; query->core, rag, storage, common); no build or other territory edits; no logging besides EMF; graph queries only through GraphTemplates with an allowlist; neighbours bounded (page 50, total 200, depth 1, fetch at most offset+page+1); cursor bound to node, direction and types and range-checked; idempotency scoped per subject with body and mode fingerprint; the worker never acknowledges malformed or rejected messages; status mapping matches the error table; routes are thin.
ASSUMPTIONS: API Gateway routing (proxy or explicit routes) is not built yet, so how reachable MAJOR-2 is remains open, which is why I rated it MAJOR rather than CRITICAL. A Cognito ID token carries no `scope` claim, which keeps MINOR-1 at low impact.
RISKS: Not reviewed: Jwks.httpFetch at runtime; storage and rag internals beyond the types the services call; MODULE.md and STATUS.md line by line beyond their diffs; docs/agent-runs/phase-10/run-log.md; the build and test output of the earlier sbt check. No tests were run.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: repair-01: service-engineer fixes MAJOR-1 and MAJOR-2 and runs sbt check; documentation-engineer fixes MINOR-6 to 8 (they touch the gate item that docs/api.md matches the tests); then a fresh re-check (review-report-02.md).
