HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default)

STATUS: COMPLETE (the forked test JVM is configured but not exercised; there are no Spark tests yet)
OWNER: build-engineer
WORK ORDER: Phase 06 step 01. Fork the sparkAnalytics tests with the JDK module opens and -Xmx1g. Keep the scala-library exclusion from the Spark job jar. Check the jar. Run sparkAnalytics/test once.

FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/build.sbt (the sparkAnalytics project only)

What changed in build.sbt:
- I added `Test / fork := true` and `Test / javaOptions ++= Seq(...)` to the existing `sparkAnalytics` `.settings(...)` block.
- The options are exactly the reference list: `-XX:+IgnoreUnrecognizedVMOptions`, 13 `--add-opens=java.base/...=ALL-UNNAMED` entries, `-Djdk.reflect.useDirectMethodHandle=false` and `-Xmx1g`.
- A comment explains why the tests fork and why the heap is 1g.
- I did not copy the reference's separate `val sparkSql`, `val forkedSparkTests` and `lazy val sparkAnalytics` definitions. This build already declares the Provided spark-sql dependency using `V.spark`, so the settings went inline, as the hadoop and entityResolution projects do.
- No library or version value was added. project/Versions.scala is untouched.
- The `assembly / assemblyExcludedJars` setting that drops scala-library (MINOR-2 option (b)) is unchanged.

COMMANDS RUN:
- `source toolchain/env.sh; sbt sparkAnalytics/assembly`
- `unzip -l` and `unzip -Z1 ... | grep` on target/jobs/sparkAnalytics-0.1.0-SNAPSHOT.jar
- `source toolchain/env.sh; sbt sparkAnalytics/test`

TEST RESULTS:
`sbt sparkAnalytics/test` printed only the loading lines and `[success] Total time: 3 s, completed Nov 23, 2026, 12:07:04 PM`. It printed no test summary, so there are no tests in tests/module-unit/spark yet, and the forked JVM was not started. The build loads cleanly with the new settings. The options themselves are unproven until the first Spark test runs.

VALIDATION RESULTS:
- `sbt sparkAnalytics/assembly` printed: `[info] Assembly jar up to date: /home/ajcreator/scholarchain/target/jobs/sparkAnalytics-0.1.0-SNAPSHOT.jar` and `[success] Total time: 3 s`.
- The jar was up to date because the new settings are Test-scope only, so I did not rebuild it from scratch.
- The jar has 1718 lines in `unzip -l`.
- Entries under `scala/`: 677. The first ones are `scala/*:`, `scala/CanEqual$.class` and similar, and `scala/runtime/LazyVals` has 8 entries. These come from scala3-library_3.
- Entries under `scala/collection/`: 0.
- Entries matching `scala/runtime/ScalaRunTime`: 0.
- Entries under `upickle/`: 377, so uPickle is bundled.
- From this I conclude scala-library 2.13 is absent and scala3-library is bundled. I checked absence by `scala/collection/` and `ScalaRunTime`, not by listing every 2.13 package.
- Version ledger: unchanged. No new versions.
- Module dependency graph: unchanged.
- Environment input surface: unchanged.
- Plugins added: none.
- Scala 3, Hadoop and Spark compatibility risk: unchanged from the committed comment in build.sbt. Scala 3.3.9 emits code against scala-library 2.13.18, and EMR ships 2.13.16. That is unverified until the first EMR run.

ASSUMPTIONS:
- Forking only the sparkAnalytics module's own tests is enough for now. MINOR-A (the black-box projects, which re-add spark-sql at Test scope, still have no fork or opens) is not part of this step and was not touched.
- I ran `sbt sparkAnalytics/test` without the Provided Spark classes being exercised, since no test uses them.

RISKS:
- The forked-JVM options are unexercised.
- The `-Xmx1g` cap may be tight for a local SparkSession. The first Spark test will show that.
- I did not run the full `sbt check`, as ordered.
- Nothing was committed. No cloud resources were touched.
- Only build.sbt is modified.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 02, a spark-engineer task that adds the first Spark tests. Step 03 runs `sbt check` once at the end, and it will also check the module opens through the first forked SparkSession.
