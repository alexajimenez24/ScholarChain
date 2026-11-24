HARNESS: claude-code
MODEL: standard tier (Sonnet 5.5)

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 08 step 00c, raise graph.componentMaxIterations above 40
FILES CREATED: none
FILES MODIFIED: modules/core/src/main/resources/scholarchain/config/scholarchain-v1.yaml (componentMaxIterations 40 -> 100); tests/module-unit/core/ConfigSuite.scala (new test pinning 100 and the margin)
COMMANDS RUN: grep -rn componentMaxIterations; sbt "core/Test/scalafmt" "core/scalafmt" "core/test" (all succeeded)
TEST RESULTS: sbt core/test: Passed 120, Failed 0, Errors 0 (ConfigSuite 25, ValidateSuite 25, SerializationSuite 29, CredentialsSuite 9, AwsHttpSuite 10). The new test is in ConfigSuite. I did not run sbt check.
VALIDATION RESULTS: Existing validation only requires componentMaxIterations to be at least 1, and its test (override "0" is rejected) is unchanged. No schema version changed; this is a config value only. Config precedence is unchanged.
ASSUMPTIONS: I chose 100. Observed max distance is 39, and hash-min label propagation needs about the diameter plus 1 plus a quiet round, so about 41 rounds. 100 is about 2.4 times that, and above the 64 that step 00b suggested. The loop stays bounded. A larger diameter at full DBLP scale would still be reported as non-convergence, because the existing invariant is kept.
RISKS:
- Consumers outside core: none hardcode 40. They read graphConfig.componentMaxIterations: WindowAnalytics.scala (components, multi-source BFS, coreness) and Canonical.scala (identity clustering, lines 314 and 440). Tests in tests/module-unit/spark use the config value or override it (WindowAnalyticsSuite sets it to 1).
- The value is part of the run manifest, so it changes the run ID. The 25k Spark run must be rerun for the 1950-2026 window to converge.
- Longer loops only cost more rounds when the graph has not converged yet, since each loop stops on a quiet round.
- Note for modules/core/MODULE.md (documentation-engineer): graph.componentMaxIterations is now 100 and also bounds the BFS and coreness loops.
- I did not run sbt check, only core/test.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: spark-engineer reruns the 25k pipeline with the new config and confirms the 1950-2026 window gives one component of 8,615 nodes. documentation-engineer updates the MODULE.md note.
