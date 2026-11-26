HARNESS: claude-code
MODEL: opus, effort high (tier high, `.agents/models.json`)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 11 round 2 rerun. One full `timeout 45m sbt check`, the three .claude/hooks test files and the dataset Python tests. Check that the 2 missing C19 sweep tests from test-report-02 now exist.
FILES CREATED: none in the repository (this report; scratchpad/sbt-check-03.log)
FILES MODIFIED: none
COMMANDS RUN (observed, exit 0):
- `find modules tests build.sbt project -newermt '2026-11-25 19:53:39' -type f -not -path '*/target/*'`. Only 2 files changed since the last full check: tests/module-unit/rag/InvariantErrorSuite.scala and tests/module-unit/services/query/DownstreamMappingSuite.scala. No production file changed.
- grep of those two suites for test names and assertions.
- `timeout 45m sbt check`: success in 16m12s; "14 project test runs passed", and the test marker was touched. The harness moved it to the background; I waited without polling and started nothing else.
- `timeout 10m python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'`; `timeout 9m python3 .claude/hooks/<file>.py` for test_guard_ownership, test_verify_tests_ran and test_guard_dangerous.
- False blocks by guard-dangerous (verbatim; both commands were read-only; I rewrote each without the trigger and did not bypass the guard):
  1. `find ... -newer .claude/.last-test-run ...; ls -l ... | awk ...` gave "Blocked by guard-dangerous hook: awk in a command that hands on the test marker name (.claude/.last-test-run is written only by sbt check). Agents never run this. If it is really needed, a human runs it manually; the approved paths are in docs/agents.md section 6." Rerun with `find -newermt '<literal time>'`.
  2. `grep -aE '...|markTestRun|...' <log> | cut -c1-200` gave "Blocked by guard-dangerous hook: cut in a command that hands on the test marker name (.claude/.last-test-run is written only by sbt check). Agents never run this. ..." (same text as above). Rerun grepping "project test runs passed" without cut.
TEST RESULTS (full check, per project, 0 failed and 0 errors everywhere):
- core 123; hadoop 100; entityResolution 119; sparkAnalytics 48; storage 199 passed, 1 ignored (AwsIntegrationSuite, gated).
- rag 163 (was 158; +5 InvariantErrorSuite); servicesCommon 31; graphService 16; queryService 31 (was 25; +6 DownstreamMappingSuite); embeddingWorker 10.
- adversarialTests 263, all 17 suites ran, 0 ignored.
- Total 1103 passed, 0 failed, 1 ignored.
- Python: datasets 19 OK; test_guard_dangerous 78 OK (148 s; was 68); test_guard_ownership 4 OK; test_verify_tests_ran 17 OK.
- AWS integration suites: NOT RUN (no approved profile).
VALIDATION RESULTS: The 2 test gaps from test-report-02 (C19 wider sweep, owner rag-engineer) are now covered.
- (a) Note text: InvariantErrorSuite pins "dropped invalid evidence item <id>: it failed core validation" for a well-formed ID, and "(malformed ID)" for a malformed one. Its precondition test proves the fixtures' validation text would echo the secret.
- (b) Remap: InvariantErrorSuite checks that an invalid bundle is Downstream(evidence), not InvalidRequest, and that an invalid answer is Downstream(answer) with nothing stored. DownstreamMappingSuite checks sync 502 dependency_failed and async job and result without the internal message, both for stub errors and for the real workflow's invalid answer.
- Every round-1 item (C09, C12 to C20, A-1) now has a relevant test (map in test-report-02.md). No production defect is open.
ASSUMPTIONS: Per-project counts are read from the "Passed: Total" lines and the suite totals (both 31s are servicesCommon and queryService: 25 + 6). The new suites' coverage is judged from their test names and assertions; I did not read src/main.
RISKS: Carried from test-report-02: there is no cross-stage black-box test from the Spark communities export through the storage loader to the rag reader (each hop has module-unit tests only). Services do not wire RuntimeStores.communities (service-engineer). LocalIngestion does not load communities (storage-engineer). The guard's marker-name rule blocks read-only `awk`/`cut` pipelines that name the marker or "markTestRun": factory-engineer, false positive. No cloud, commit or cleanup.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Fresh code-reviewer on the whole repair diff (review-report-02.md), then documentation-engineer.
