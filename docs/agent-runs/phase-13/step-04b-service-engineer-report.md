HARNESS: claude-code
MODEL: sonnet (standard tier; the session reports claude-sonnet-5-5)
STATUS: COMPLETE (the fix is in and the services tests pass; it is not yet shown under full-check load)
OWNER: service-engineer
WORK ORDER: Phase 13 repair, part 2: first-test 30 s timeouts in four services suites
FILES CREATED: none
FILES MODIFIED (all in tests/module-unit/services/): common/CommonSuite.scala, graph/GraphServiceSuite.scala, graph/GraphWiringSuite.scala, query/DownstreamMappingSuite.scala, query/QueryServiceSuite.scala, query/QueryWiringSuite.scala, worker/WorkerSuite.scala, worker/WorkerWiringSuite.scala (the three Wiring suites are untracked step 1 files)
COMMANDS RUN: a Python script in the scratchpad that adds the override to each suite; one `timeout 30m sbt -batch` run (Test/scalafmt and Test/scalafmtCheck for the four services projects, then `all servicesCommon/test queryService/test graphService/test embeddingWorker/test`), exit 0; read-only log, source and git inspection. The full `sbt check` was NOT run, as ordered (a fresh infra-engineer reruns it).
TEST RESULTS: 103 passed, 0 failed: servicesCommon 31, queryService 39, graphService 18, embeddingWorker 15.
VALIDATION RESULTS:
- Evidence: in each failed suite the first test took about 42 s. The other tests took about 0.3 s (GraphNode) and 1 s (GraphNeighbors) in total; DownstreamMapping's suite total was 45 s. This run, without the full-check load, had suites at 0.3-4.3 s (QueryServiceSuite 11.7 s).
- Likely cause (inferred, not profiled): one-time JVM setup is charged to the first test (two RSA-2048 keys in TestJwt, cats-effect runtime, class loading) while concurrent suites share a contended 5 GB machine. Other suites were slow in the same log: JwtSuite and ServiceKitSuite about 28 s, rag VerificationSuite and IngestionConformanceSuite about 24 s. This points to general load; memory or GC pressure is a guess.
- Step 1: the only tracked production diffs under modules/services are a QueryRoutes comment and the EmbeddingWorker loop error handling. The graph tests that failed use neither, and no sign was found that step 1 caused the slowness (new mains and resources were not timed).
- Fix: `override val munitTimeout = scala.concurrent.duration.Duration(2, "min")` in all 17 services suite classes, including those close to the limit, with a short comment once per changed file. It is about 2.8 times the observed 42 s. It is justified because the tests use fakes with no sockets or sleeps; the latch tests block only until a 20 ms product timeout fires. Assertions and product timeouts are unchanged.
- Route table, status mapping, timeout policy, dependency graph and authentication (Cognito JWT): unchanged, no production edits.
ASSUMPTIONS: the larger timeout allowed by the work order is preferable to a build change, since the cause is not proven.
RISKS:
- This raises the limit; it does not make startup faster. A real hang in these suites now takes 2 min to fail.
- Under heavier load, product timeouts inside tests (500 ms in the graph rig, 100 ms in EvidenceRoutes, 20 ms in the latch tests) could in principle flake. None did in run 1's failures, and they are unchanged.
- Non-services suites at 24-28 s are close to the 30 s default. If the rerun flakes there or again here, build-engineer could limit test concurrency in build.sbt (for example `Global / concurrentRestrictions += Tags.limit(Tags.Test, 4)`; untested, value is a suggestion).
- MODULE.md: no public API change.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh infra-engineer reruns scripts/check.sh on a clean copy
