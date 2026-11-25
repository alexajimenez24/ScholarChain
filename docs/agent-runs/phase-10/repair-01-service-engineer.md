HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default, no override)

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: Phase 10 repair round 1. MAJOR-1 (QueryStore eviction, no orphan record on a pending 503) and MAJOR-2 (bounded EMF Operation). MINOR findings left untouched.
FILES CREATED: none
FILES MODIFIED:
- modules/services/query/src/main/scala/scholarchain/services/query/QueryService.scala
- modules/services/common/src/main/scala/scholarchain/services/common/ServiceKit.scala
- tests/module-unit/services/query/QueryServiceSuite.scala
- tests/module-unit/services/common/CommonSuite.scala
COMMANDS RUN: scalafmt on my 4 files only; `timeout 45m sbt -batch check` once, foreground, exit 0. I did not run it a second time.
TEST RESULTS: `sbt check` passed. Its last 4 project summaries were 119, 98, 45 and 230 tests, 0 failed. `markTestRun` reported "14 project test runs passed". I only kept the last 40 lines of output, so I did not observe per-services-project totals and won't invent them. The new tests, which all passed:
- queryService, 3 new: the keyless async 503 leaves no record, 1001 sequential sync queries all return 200 with the store at 1000, and a store full of in-flight records still returns 503 and recovers after the jobs drain.
- servicesCommon, 1 new: two random unmatched paths both give Operation "unmatched".
- The existing tests stayed green, including the EMF "GET /v1/ok" test, GraphServiceSuite "GET /v1/graph" and the idempotency, replay, pending-bound and ownership tests.
VALIDATION RESULTS:
- MAJOR-1: `QueryStore.add`, when the store is full, evicts the oldest terminal record (Succeeded or Failed, by updatedAt) and its idempotency claim. It refuses only when every record is still in flight. For async queries, `start` now reserves the pending slot before `store.add`. A pending-bound refusal therefore creates no record, and a store-full refusal releases the reservation and the key. I added `QueryStore.size` and `QueryStore.remove`.
- MAJOR-2: `ServiceKit.operation` now returns the method plus a prefix from the fixed set `ServiceKit.KnownPrefixes` (healthz, readyz, v1/query, v1/jobs, v1/explain, v1/evidence, v1/graph). A matched route outside the allowlist, such as the test route /v1/ok, keeps its first-two-segment prefix. Every unmatched path, and any error before routing, whose prefix is not in the set is the constant "unmatched". `guarded` now returns (response, matched).
- Route table, status codes, timeout policy and auth are unchanged.
ASSUMPTIONS:
- Evicting a claim means a later replay of an evicted key re-executes the query as fresh.
- API Gateway forwards only the real routes, so a matched route's first-two-segment prefix has bounded cardinality.
RISKS:
- Eviction is O(records), which is fine at the 1000 cap. Under load an in-memory store can still return 503 when 1000 records are all in flight.
- Store capacity is not exposed in /readyz. The review offered that as optional, and I did not do it.
- MINOR-1 to MINOR-5 are untouched and carried forward to the human.
- Nothing is committed.
- Notes for `modules/services/MODULE.md`: QueryStore now evicts terminal records. Operation is allowlisted, with "unmatched" for the rest. docs/api.md:228, the cardinality note, should say unmatched paths are the single value "unmatched".
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh re-check (review-report-02.md) after documentation-engineer fixes MINOR-6 to 8.
