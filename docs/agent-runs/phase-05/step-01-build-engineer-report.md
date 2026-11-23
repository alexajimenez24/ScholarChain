HARNESS: claude-code
MODEL: standard tier (sonnet), as set in the work order

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: phase 05, step 01. Give the entityResolution project the same Hadoop dependency shape as hadoop.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/build.sbt. Only the entityResolution project stanza changed, plus a 3-line comment above it.
- Added hadoop-client-api % Provided, hadoop-client-runtime % Test and jcl-over-slf4j % Test.
- Added `Test / fork := true` and `Test / javaOptions += "-Xmx1g"`.
- All versions come from the existing V.hadoop and V.slf4j. project/Versions.scala is unchanged.
- I repeated the stanza instead of factoring a shared helper. It is 5 lines, and repeating it leaves the hadoop project's effective settings untouched. The reference's `hadoopTestRuntime` and `forkedHadoopTests` helpers do not exist in this build, so I did not use them.
COMMANDS RUN: one sbt JVM, run in a single foreground invocation (it ran past 180s, so the harness moved it to the background): `sbt check "show entityResolution/Test/fork" "show entityResolution/Test/javaOptions" "show entityResolution/libraryDependencies" "show entityResolution/Test/fullClasspath"`
TEST RESULTS: `sbt check` passed on the first attempt.
- `[info] Passed: Total 68, Failed 0, Errors 0, Passed 68` (the last test run in the output).
- `[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `[success] Total time: 175 s`
VALIDATION RESULTS: verbatim lines from the same sbt invocation.
- `show entityResolution/Test/fork` printed `[info] true`.
- `show entityResolution/Test/javaOptions` printed `[info] * -Xmx1g`.
- `show entityResolution/libraryDependencies` printed:
  - `org.scala-lang:scala3-library:3.3.9`
  - `org.scalameta:munit:1.3.6:test`
  - `org.apache.hadoop:hadoop-client-api:3.4.1:provided`
  - `org.apache.hadoop:hadoop-client-runtime:3.4.1:test`
  - `org.slf4j:jcl-over-slf4j:2.0.20:test`
- `show entityResolution/Test/fullClasspath` includes hadoop-client-api-3.4.1.jar, hadoop-client-runtime-3.4.1.jar, jcl-over-slf4j-2.0.20.jar and commons-logging-1.2.jar.
- Provided scope puts hadoop-client-api on the Compile and Test classpaths. I did not print `entityResolution/Compile/fullClasspath` separately.
- Module dependency graph is unchanged: entityResolution depends only on core.
- No new environment inputs and no new plugins.
- Version ledger is unchanged. The values relevant here are V.hadoop 3.4.1 and V.slf4j 2.0.20.
- Scala 3 compatibility: the Hadoop artifacts are plain Java, so there is no cross-version risk.
ASSUMPTIONS: none beyond the work order.
RISKS:
- entityResolution has no Hadoop-using source or tests yet, so the new stanza is only checked by classpath inspection. A local MapReduce run in this project is not exercised.
- The state left behind is a modified build.sbt, uncommitted. I changed nothing else.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the build.sbt diff, then the next phase 05 task.

---
Orchestrator note (observed): `git diff build.sbt` matches the report (12 added lines in the
entityResolution stanza only). The sbt run exceeded the 180 s foreground window and the
harness moved it to the background, which the machine limits forbid; it finished, and no
sbt/java process was left running at 09:07 (`pgrep`). Later sbt work orders run long
commands with an explicit larger timeout.
Also observed: the orchestrator's first attempt to save this report through a shell heredoc
was blocked by guard-dangerous because the text quotes the sbt task name in the line
above; the report was then saved with the file tool. Recorded as a Phase 11 guard item
(false positive on a literal task name in report text).
