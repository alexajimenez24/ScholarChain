HARNESS: claude-code
MODEL: opus, effort high (high tier per .agents/models.json)
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 13 repair of a BLOCKED-ON. In a clean checkout, BenchmarkWorkflowSuite "semantic retrieval is scored on the semantic partition only, apart from deterministic" threw IllegalStateException (LocalRuntime.load failed) when the git-ignored 25k snapshot was absent. It must skip instead.
FILES CREATED: none
FILES MODIFIED: tests/adversarial/scholarchain/adversarial/rag/BenchmarkWorkflowSuite.scala (one line added as the first statement of that test: `assume(BenchmarkRuntime.present, s"run data absent at ${BenchmarkStore.runDir}")`, the same guard and reason its siblings use. No assertion changed.)
COMMANDS RUN:
- SCHOLARCHAIN_BENCHMARK_RUN_DIR=<scratchpad>/absent-run-dir timeout 30m sbt "adversarialTests/Test/scalafmtOnly <abs path of the file>" "adversarialTests/Test/scalafmtCheck" "adversarialTests/testOnly scholarchain.adversarial.rag.BenchmarkWorkflowSuite" (success)
- timeout 30m sbt "adversarialTests/testOnly scholarchain.adversarial.rag.BenchmarkWorkflowSuite" (snapshot present, success)
- timeout 45m sbt check (success, exit 0, 1097 s; the stop hook required it)
TEST RESULTS:
- BenchmarkWorkflowSuite, run data absent: Total 68, Failed 0, Passed 0, Skipped 68 (the repaired test is skipped too).
- BenchmarkWorkflowSuite, snapshot present: Total 68, Failed 0, Passed 68. D 33/33, A 33/33, benchmark pass 33/33.
- sbt check: every project passed. Per-project totals were 123, 15, 18, 31, 166, 217 (1 ignored), 39, 48, 100, 119 and 263 (adversarialTests), all with 0 failed and 0 errors.
VALIDATION RESULTS:
- With the snapshot absent, the suite now skips like its siblings, with the reason "run data absent at <runDir>". With it present, the test runs with unchanged assertions.
- In the clean-run evidence, this was the only failure in tests/ outside module-unit.
- The other suites that load the runtime (WorkflowGateSuite and WorkflowRepair{,R3,R4,D5}GateSuite) already guard every runtime access through `rt` or `assume(BenchmarkRuntime.present)`, so they needed no change.
ASSUMPTIONS:
- The absent case was proven with the suite's own SCHOLARCHAIN_BENCHMARK_RUN_DIR override pointed at a path that does not exist. `BenchmarkRuntime.manifestPath` is derived from that directory, so it is missing too. Nothing under datasets/ was touched.
RISKS:
- The absent case was proven for BenchmarkWorkflowSuite only. The whole-tree absent case (a real clean copy) is to be confirmed by infra-engineer's next clean-copy run of scripts/check.sh.
- The clean run's 4 service timeouts (GraphNodeSuite, GraphNeighborsSuite, DownstreamMappingSuite, QueryServiceSuite) are module-unit tests owned by service-engineer. They passed in this run; their clean-copy timeouts are not in this territory and were not investigated.
- Nothing committed; the working tree also holds other agents' uncommitted changes.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews the one-line diff; infra-engineer reruns scripts/check.sh in a clean copy.
