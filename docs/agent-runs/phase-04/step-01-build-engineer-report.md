HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier default)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 04 T1, hadoop Test runtime and forked tests
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/build.sbt (hadoop project only)
COMMANDS RUN:
- sbt "show hadoop/Test/fork" "show hadoop/Test/javaOptions" "show hadoop/Test/dependencyClasspath" (one invocation)
- sbt check
TEST RESULTS: sbt check green. Tail: "Passed: Total 94, Failed 0, Errors 0, Passed 94" (the core suites shown at the tail; this is the last project's summary line). "markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run", "[success] Total time: 2 s".
VALIDATION RESULTS:
- hadoop / Test / fork = true; hadoop / Test / javaOptions = -Xmx1g.
- hadoop Test dependencyClasspath contains hadoop-client-api-3.4.1, hadoop-client-runtime-3.4.1 and jcl-over-slf4j-2.0.20.
- hadoop-client-api remains Provided (verified, now in a Seq with the two Test entries).
- Version ledger: no change to project/Versions.scala; used V.hadoop (3.4.1) and V.slf4j (2.0.20), both already present. No library version bumped.
- Module graph unchanged (hadoop -> core). Environment input surface unchanged. No plugins added.
- Scala 3 / Hadoop / Spark risk: hadoop-client-runtime is the shaded jar, Java-only, so there is no Scala 3 cross-version concern; it is Test scope and never packaged in the job jar. Forking changes the test class loader to a flat classpath.
ASSUMPTIONS: The reference's entityResolution part was not applied (out of scope). I inlined the reference's helper vals (hadoopTestRuntime, forkedHadoopTests) directly into the hadoop project rather than defining shared vals, since only one project uses them.
RISKS: entityResolution has no Hadoop deps or forking yet. Forked tests on the 8 GB machine use up to 1 GB heap per test JVM. The hadoop project has no sources or tests yet, so the fork is only exercised once T2 adds tests. Note that contractTests and adversarialTests already add hadoop-client-runtime at Test scope, which was already in the build.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer / next phase 04 task (hadoop-engineer)
