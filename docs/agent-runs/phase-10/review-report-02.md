HARNESS: claude-code
MODEL: opus, effort high (code-reviewer, high tier)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 10 fresh re-check after repair round 1 (review-report-02.md). Checked that MAJOR-1 and MAJOR-2 are fixed, that the new tests are fair, that nothing regressed, that the docs/api.md MINOR-6 to 8 fixes match the tests, and that MODULE.md and STATUS.md agree with the code.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git status --short; git diff -- STATUS.md; find -newer review-report.md (only the 10 expected files changed, all inside the authors' territories); grep over the services sources, the suites, build.sbt and STATUS.md. I did not run sbt and started no server or background shell.
TEST RESULTS: none run. Counting the tests by reading the suites gives query 22 (+3) and common 29 (+1), so the services total is 77. That matches the claim that 4 tests were added.
VALIDATION RESULTS: APPROVE (0 CRITICAL, 0 MAJOR, 7 MINOR). The 7 MINOR findings are carried to the human; there is no second repair round.
- MAJOR-1 FIXED. QueryService.scala:79-95: when the store is full, `add` evicts the oldest Succeeded or Failed record and its claim, and never evicts a Queued or Running record. :176-182: the async pending slot is reserved before `store.add`, and on a store-full refusal both the slot and the key are released.
  Tests at QueryServiceSuite:288, :297 and :304 are FAIR against the review: each would have failed on the old code.
- MAJOR-2 FIXED. ServiceKit.scala:95-101 with the KnownPrefixes list at :202-210. Every production route (QueryRoutes:79-111, GraphRoutes:29-51, evidence and ops) falls under an allowlisted prefix. Errors raised before routing or by auth return matched=false and resolve to the allowlisted prefix or "unmatched". The test at CommonSuite:299 is FAIR, and the "GET /v1/ok" test still holds.
- MINOR-A, service-engineer, QueryService.scala:87-95 and :67-73. Eviction can delete a claim that has just been taken again.
  - Sequence: a sync retryable failure releases key k and leaves record Q as Failed. Request B claims k again (Fresh), but before B calls `store.add`, another request evicts Q as the oldest terminal record and removes B's new claim. A concurrent replay of k then also gets Fresh and overwrites B's in-flight record, so the workflow runs twice.
  - The window is narrow: it needs a full store and a concurrent replay.
  - Fix: evict only claims whose record is still terminal at the moment of removal, or keep the queryId in the claim and check it.
- MINOR-B, service-engineer, QueryService.scala:105-108 and :194. EvidenceIndex is never trimmed.
  - Before the repair, the store's 1000-record limit indirectly capped the index. Eviction removed that cap, so memory now grows with every answered query for the life of the process.
  - Fix: cap the index, or drop an evicted query's entries when its record is evicted.
- MINOR-C, service-engineer, QueryService.scala:168 (amplifies the carried MINOR-4). A cancelled sync request leaves its record Running, and the store never evicts a Running record. Each such request takes one slot for good, so about 1000 cancelled requests bring back the permanent 503 that MAJOR-1 was about.
- MINOR-D, service-engineer, ServiceKit.scala:100. The Operation value includes `req.method.name` without a bound. Ember accepts any method token, so a direct caller using custom methods can still inflate metric cardinality. API Gateway in front limits the methods. Fix: map any method outside the standard set to "OTHER".
- MINOR-E, service-engineer. Test gaps:
  - QueryServiceSuite:93 fixes the clock, so "oldest first" is never tested: evicting the newest record would still pass.
  - No test checks that a store-full refusal releases the pending slot: with maxPending at 8, the :304 test passes either way.
  - No test checks that a replay of an evicted key runs again.
- MINOR-F, documentation-engineer, docs/api.md:197 and :224 do not mention eviction. "Store full" now means 1000 in-flight records, and an evicted job or explain returns 404.
  - MINOR-6, MINOR-7 and MINOR-8 are fixed and match the tests: api.md:83-86 matches Suite:352-365; api.md:204-206 matches CommonSuite:224-232; and :81, :97 and :147 are correctly marked as code facts (GraphReader.scala:16-17).
- MINOR-G, documentation-engineer. Stale state that a fresh session would act on:
  - STATUS.md:79-80: "review pending" and "73 of 73";
  - STATUS.md:96-97: table row 4 is still "NEXT: review";
  - STATUS.md:125-127: "Next step: code-reviewer review ..." and "Verify with sbt services/test (73 passed) ... before any repair". Both the repair and its sbt check (exit 0) have happened since, and the count to verify is 77.
  - MODULE.md:69 has the total 73 with no post-repair figure, and MODULE.md:80 says the review "is still to come".
ASSUMPTIONS: API Gateway forwards only the real routes and the standard methods. The 77 count comes from reading the suites, not from a test run.
RISKS: Not reviewed: the files that did not change since review-report.md (I relied on the first review for them); the full sbt output (the service-engineer kept only 40 lines); run-log.md; Ember's behavior when a client disconnects. No tests were run.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate for Phase 10. MINOR-A to E go to service-engineer and MINOR-F and G to documentation-engineer when the human chooses. MINOR-G is a one-step STATUS.md fix worth doing before the handoff.
