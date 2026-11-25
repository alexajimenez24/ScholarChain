HARNESS: claude-code
MODEL: sonnet (standard tier, no override)

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: Phase 10, task S2 (queryService, its tests, one full sbt check). Nothing is committed.
FILES CREATED:
- modules/services/query/src/main/scala/scholarchain/services/query/: QueryRequests.scala, QueryService.scala, QueryRoutes.scala
- tests/module-unit/services/query/: QueryFixture.scala, TestJwt.scala (third copy), QueryServiceSuite.scala
FILES MODIFIED:
- modules/services/common/.../Auth.scala: added Scopes.QueryRun = "scholarchain/query.run".
- modules/services/common/.../ServiceKit.scala: bug fix from S1. /readyz built a second request ID, so its error body requestId differed from the X-Request-Id header. The ID is now computed once per request.
COMMANDS RUN (all succeeded):
- sbt queryService/compile, queryService/test, services/test.
- scalafmt --config .scalafmt.conf on my own 8 files only.
- `timeout 45m sbt -batch check`, once (exit 0, 22m23s).
- No server, network or cloud. The scratch log I wrote under ~/ref-phase-10 was deleted.
TEST RESULTS:
- Full sbt check passed with no failures anywhere.
- Services: queryService 19, servicesCommon 28, graphService 16, embeddingWorker 10; 73 in total, 0 failed.
- Other modules in the same run: 120, 184 (1 ignored), 138, 119, 98, 45 and 230 passed, 0 failed.
VALIDATION RESULTS:
- Route table (queryService):
  - POST /v1/query: 200 sync answer, or 202 plus Location for an async job reference.
  - GET /v1/jobs/{jobId}: job state.
  - GET /v1/jobs/{jobId}/result: 200 answer; 202 plus Retry-After while unfinished; the stored typed error if failed.
  - GET /v1/explain/{queryId}.
  - GET /v1/evidence/{id}: servicesCommon EvidenceRoutes, adapter BundleEvidenceLookup over stored EvidenceBundles.
  - GET /healthz and GET /readyz. /metrics is not served (404).
- Request body: question (1..1000 chars), mode sync|async, constraints{yearFrom, yearTo, topics, institutions, venues, researchers, researcherIds}, options{limit 1..20, maxPathDepth 1..6}.
- Status mapping. Every error is the typed {"error":{code, message, retryable, requestId, traceId, details?}}.
  - 400: invalid_json, or invalid_request (bad shape or type, unknown top-level field, bad mode or Idempotency-Key).
  - 401: no, bad or expired token, with WWW-Authenticate.
  - 403: missing scope.
  - 404: unknown job, query or evidence, and another caller's job, result or explanation.
  - 409: same Idempotency-Key with a different body; explain of an unfinished query.
  - 413: body over 16 KB (the kit caps at 64 KB).
  - 415: not application/json.
  - 422: unsupported_constraint (unknown constraint or option key, years outside 1900..2100 or inverted, more than 10 list items, option out of range, workflow Unsupported or BudgetExceeded).
  - 502: dependency_failed.
  - 503: breaker open, store full, too many pending jobs, not ready.
  - 504: timeout.
- Idempotency:
  - The key is optional, 1..128 chars of [A-Za-z0-9_.:-], and scoped per caller subject.
  - The query ID is derived from the SHA-256 of subject and key.
  - A replay returns the stored outcome with Idempotent-Replayed: true, and the workflow runs once.
  - A retryable sync failure releases the key.
- Timeout policy: servicesCommon Downstream per port.
  - Tests use 5 s with 1 attempt for the workflow (so a query is never re-run) and 20 ms for the hang test.
  - The production policy is a wiring choice not built here.
  - Kit defaults: 2 s per attempt, breaker opens after 5 consecutive failures for 30 s (3 in tests), readiness check 2 s.
  - POST /v1/query bodies are capped at 16 KB.
  - At most 8 async jobs may be pending (503 beyond that), and the record store holds 1000 queries.
- Dependency graph: queryService -> core, rag, storage, servicesCommon. Unchanged, no build edits.
  - Ports: QueryEngine (workflow), JobScheduler (FiberScheduler in production, a queue in tests), AuditLog, EvidenceBundles.
- Authentication: Cognito JWT verified inside the service (RS256, JWKS kid, issuer, audience, expiry).
  - Scope scholarchain/query.run covers query, jobs and explain; scholarchain/evidence.read covers evidence.
  - Jobs and explain are also limited to the creating subject.
  - Tests sign with a local RSA key.
- Explain returns: plan, resolutions, stages, graph calls (template IDs and windows), versions (components, synthesizer, prompt version and SHA-256, model, run IDs), evidence IDs with score components, synthesis attempts, claim checks, removed claims (ID, status and reasons only), counters and verification. It holds no prompt, reply or reasoning text.
- LLM_ENABLED defaults to false and selects the deterministic synthesizer. Setting it to true with no model synthesizer is refused. Any value other than true or false is an error.
- Required tests, all in QueryServiceSuite: sync success, async, invalid JSON, unsupported constraint, missing evidence, idempotent replay (sync and async), timeout, downstream failure and breaker, unauthorized on every route, health vs readiness, query-to-evidence lifecycle with fakes, and one end-to-end signed POST /v1/query -> answer -> GET /v1/evidence/{id}.
ASSUMPTIONS:
- Evidence by ID is mounted from servicesCommon, indexed from workflows this process answered.
- The job ID is the query ID's suffix.
- One scope, query.run, covers query, jobs and explain.
- Idempotency-Key is optional.
- I built my own bounded fixture (3 researchers, 1 work, 1 window) because the rag test fixture is not visible from the services test root.
RISKS:
- Not done: no Main or Ember wiring, no real AWS adapters, no image.
- Jobs, idempotency records and the evidence index are in memory only. After a restart, earlier queries cannot be explained or fetched, and in-flight async jobs are lost. A persistent store is a storage-engineer item.
- Evidence read is not scoped to the caller: any holder of evidence.read can read any indexed item.
- infra must create the scope scholarchain/query.run.
- The metrics Operation dimension is "METHOD /v1/segment", so job IDs do not inflate metric cardinality.
- Notes for MODULE.md: public API as in the route table; test counts 28/16/10/19; the "no main class" known issue stands; TestJwt is duplicated in three test roots; the ServiceKit /readyz fix.
- State left: new files only, nothing committed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer (docs/api.md, MODULE.md, STATUS.md), then code-reviewer.
