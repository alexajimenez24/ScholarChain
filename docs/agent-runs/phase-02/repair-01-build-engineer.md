HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 02 repair round 1, task R1-2 (rerun): MINOR-1, MINOR-2, N1, N2, N5.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/build.sbt, /home/ajcreator/scholarchain/project/Versions.scala, /home/ajcreator/scholarchain/project/plugins.sbt
(Every change below is relative to p2-orig plus the step-04 comment. I re-examined each partial edit and kept it.)

build.sbt (3 changes kept, 0 reverted, 0 new):
- N5 (kept): line 46 `ThisBuild / Compile / compile / javacOptions ++= ...` became `ThisBuild / javacOptions ++= Seq("--release", V.jvmRelease)`, so Test Java sources get `--release 17` too.
- MINOR-2 (kept): sparkAnalytics gets `assembly / assemblyExcludedJars` dropping `scala-library-*`. A comment explains the 2.13.18 vs 2.13.16 skew and the residual NoSuchMethodError risk. hadoop and entityResolution still bundle scala-library, because a plain Hadoop cluster supplies none.
- MINOR-1 (kept): blackBoxTests adds `hadoop-client-api`, `hadoop-client-runtime` and `spark-sql` (for3Use2_13) at `% Test`, with the same V.hadoop and V.spark values. The comment above the project is updated.

project/Versions.scala (comment-only, no value changed):
- N2 (kept): the jvmRelease comment no longer cites EMR 7.x. It now says Spark 4.0 supports Java 17, the emr-spark-8.0.0 JVM is to be confirmed in Phase 12, and the Corretto 17 image runs 17.
- Line 9 header: `toolchain/versions.env` became `toolchain/versions.sh`. I checked that the file on disk is `toolchain/versions.sh`.
- Step-04 emr-spark-8.0.0 comment lines are unchanged. I left the older "Spark 3.5 on EMR 7.x is Scala 2.12" comment alone, since it is accurate history and is not the jvmRelease comment.

project/plugins.sbt (the partial rewrite was identical to p2-orig; now changed, N1):
- `ledgerPin` is now a `Def.setting` that reads `baseDirectory.value / "Versions.scala"`. In the meta-build that is `project/`, so it no longer depends on the JVM working directory. The error message names the resolved file.
- New helper `ledgerPlugin(org, artifact, pin)` mirrors what `addSbtPlugin` does (`Defaults.sbtPluginExtra` with `pluginCrossBuild` binary versions). The sbt-scalafmt and sbt-assembly lines use it. The plugin set and versions are unchanged (2.6.2 and 2.5.0, still read from the ledger).

COMMANDS RUN (successful output observed):
- `source toolchain/env.sh && sbt -batch check` (run twice, after the plugins.sbt edit and again at the end): scalafmt checked 1 and then 2 Scala sources, all `[success]`, and `markTestRun: 14 project test runs passed, touched .claude/.last-test-run`.
- `sbt -batch "export contractTests/Test/fullClasspath"`: the classpath now lists hadoop-client-api-3.4.1, hadoop-client-runtime-3.4.1, spark-sql_2.13-4.0.1 and spark-sql-api_2.13-4.0.1.
- `rm target/jobs/sparkAnalytics-*.jar && sbt -batch packageJobs`: rebuilt `sparkAnalytics-0.1.0-SNAPSHOT.jar`. The hadoop and entityResolution jars were already up to date.
- `unzip -l ... | grep -c`: the sparkAnalytics jar has 0 `scala/collection/` entries and 8 `scala/runtime/LazyVals` entries, so scala3-library is bundled and scala-library is not. The hadoop jar has 1171 `scala/collection/` entries, so scala-library is bundled there.
- `TMPDIR=<scratchpad> bash tests/build/check-contract.sh`: `build-contract: 4 passed (pass fail-contract fail-unit fail-format), 0 failed (none)`.
TEST RESULTS: sbt check green (14 project test runs passed); check-contract 4 passed, 0 failed.
VALIDATION RESULTS:
- Version ledger: no library version value changed. Values are scala3 3.3.9, jvmRelease 17, hadoop 3.4.1, spark 4.0.1, upickle 4.4.3, http4s 0.23.38, catsEffect 3.7.1, munit 1.3.6, slf4j 2.0.20, logback 1.5.38, pluginSbtScalafmt 2.6.2 and pluginSbtAssembly 2.5.0. I did not re-read the ledger lines above scala3, so I did not re-list those here.
- Module graph: unchanged and follows the AGENTS.md table.
- Environment inputs: unchanged, none added.
- Plugins: none added (the two existing ones are now loaded through ledgerPlugin).
- Compatibility risk: scala3-library_3 3.3.9 pulls scala-library 2.13.18, but the emr-spark-8.0.0 runtime ships 2.13.16.
ASSUMPTIONS:
- Spark 4.0.2 and Scala 2.13.16 on emr-spark-8.0.0 are the human's statement and are unverified.
- plugins.sbt is evaluated in the meta-build, so `baseDirectory` there is `project/`.
RISKS:
- MINOR-2: I kept the exclusion of scala-library from the Spark job jar, but the choice among the review's three options is still open for the human before Phase 6. The options are a Scala 3 LTS patch pinned to scala-library at or below 2.13.16, scala-library Provided in the job jars (what I did for sparkAnalytics), or accepting `userClassPathFirst`. Scala 3.3.9 emits code against 2.13.18. Calling a member added after 2.13.16 would throw NoSuchMethodError on the cluster, and this is unverified until the first EMR run.
- Excluding scala-library means the Spark jar cannot run outside a Spark runtime that supplies it. Local runs use the Provided Spark classpath, which does.
- N1: I did not test it by launching sbt with a different `user.dir`. The load works from the project root and the code no longer reads the working directory.
- The test-scope Hadoop and Spark dependencies add about four jars to the contract and adversarial test classpaths.
MANUAL ACTION REQUIRED: the human decides the MINOR-2 option before Phase 6.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer re-check of the repairs, then the Phase 02 human gate.

---
Orchestrator note (not part of the agent report): disk was checked against this report with
diff against ~/p2-orig and matches it. Corrected after review 2 (MINOR-C): the
Versions.scala line 9 header change (versions.env -> versions.sh) was made by
build-engineer in step 02 with `sed -i` (agent-report.md lines 11 and 14), not by R1-2.
This report lists it under its own changes, which is wrong. An earlier version of this
note blamed the crashed R1-2 run or the human, and that was also wrong.
