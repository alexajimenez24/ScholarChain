HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default effort)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 02 step 2. Verify the installed build, fix the versions.env comments, run sbt Test/compile and sbt check, report.

FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/.scalafmt.conf (comment only: versions.env -> versions.sh)
- /home/ajcreator/scholarchain/project/Versions.scala (comment only: versions.env -> versions.sh; no version values changed)

COMMANDS RUN (all succeeded):
- sed -i 's/versions\.env/versions.sh/' on the two files above, then grep to confirm
- `source toolchain/env.sh; sbt Test/compile` -> [success], 51 s (first run, downloaded sbt 1.13.1 and Scala 2.12.21)
- `source toolchain/env.sh; sbt check` -> [success]
- A first attempt at `sbt check` was blocked by the guard-dangerous hook. My command line also named the marker file in a `ls`, and the hook refuses `source` in a command that names it. I re-ran without naming the marker. This was my command shape, not a build defect.

TEST RESULTS:
- No test sources exist yet, so there are 0 tests. `markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`.
- The 14 are the aggregated Test/test runs: 12 project test runs plus the root and the `services` aggregate. I did not itemize them.
- scalafmt checked 2 Scala sources (build.sbt/project files). Compile and Test/compile passed.
- The marker was touched by sbt check itself. I did not confirm that it exists with a separate command.

VALIDATION RESULTS:

1. Verification against the work order
- Required projects core, hadoop, entityResolution, sparkAnalytics, storage, rag and services are present.
- The extra projects are servicesCommon, queryService, graphService, embeddingWorker, contractTests, adversarialTests and root.
- Scala 3 is used everywhere. Hadoop and Spark are Provided, MUnit is Test, logback is Runtime (deployables only), and uPickle is the only JSON library.
- Aliases fmt, check, packageJobs and dockerLocal exist. `check` = scalafmtCheckAll; scalafmtSbtCheck; compile; Test/compile; markTestRun.
- markTestRun depends on every project's Test/test, so a failing test leaves the marker untouched.
- .scalafmt.conf uses the scala3 dialect.
- The Dockerfile has a pinned Corretto 17.0.21-al2023-headless base (tag plus sha256 digest), non-root USER 10001:10001, and InitialRAMPercentage=25 / MaxRAMPercentage=75.
- The build holds no secrets, endpoints or deployment steps.
- .sbtopts and .jvmopts are not present. They are optional, and the environment surface is documented without them. I did not create them.
- There is no compose file, which fits "only when justified".
- .gitignore covers secrets, Terraform state, sbt output, the test marker and settings.local.json.

2. Version ledger (project/Versions.scala, each value pinned exactly)
- scala3 3.3.9
- jvmRelease 17
- hadoop 3.4.1 (hadoop-client-api, Provided)
- spark 4.0.1 (spark-sql, Provided, CrossVersion.for3Use2_13)
- upickle 4.4.3
- http4s 0.23.38 (ember-server, dsl)
- catsEffect 3.7.1
- munit 1.3.6 (Test)
- slf4j 2.0.20 (slf4j-api, compile scope in core)
- logback 1.5.38 (Runtime, deployables only)
- pluginSbtScalafmt 2.6.2
- pluginSbtAssembly 2.5.0
- sbt 1.13.1 (project/build.properties)
- scalafmt-core 3.11.5 (.scalafmt.conf, matches toolchain/versions.sh)
- JDK Corretto 17.0.21.1.2 (toolchain)
- Docker base amazoncorretto:17.0.21-al2023-headless@sha256:dae4ff04... (Dockerfile)
- project/plugins.sbt reads the plugin pins as text from the ledger, so no version is written twice.

3. Module dependency graph (matches the AGENTS.md table exactly)
- core -> (external libraries only)
- hadoop, entityResolution, sparkAnalytics, storage -> core
- rag -> core, storage
- servicesCommon -> core
- queryService -> core, rag, storage, servicesCommon
- graphService -> core, storage, servicesCommon
- embeddingWorker -> core, storage, servicesCommon
- There are no cycles, no module depends on a service project, and no service depends on another service.
- `services` is an aggregate with no sources; the root aggregates everything.
- contractTests and adversarialTests (tests/contracts, tests/adversarial) are test-only projects that depend on all modules and are depended on by none. They are not in the boundary table, so this is a design choice to flag.
- tests/integration, tests/end-to-end and tests/build are not sbt projects.

4. Environment input surface (the build definition itself reads none)
- JAVA_HOME and PATH, set by toolchain/env.sh, which honours SCHOLARCHAIN_TOOLS.
- The sbt and JVM inherited SBT_OPTS, JAVA_OPTS, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS and SBT_NATIVE_CLIENT, which should be unset for a reproducible build.
- dockerLocal only: the docker CLI's DOCKER_* variables.
- The build reads the system property java.home (JDK check: version >= 17 and lib/ct.sym present) and the file project/Versions.scala (plugins.sbt).
- No new input was added.

5. Packaging
- packageJobs = hadoop/assembly; entityResolution/assembly; sparkAnalytics/assembly. Jars go to target/jobs/<name>-<version>.jar.
- The assembly uses sbt-assembly 2.5.0. It excludes Provided Hadoop and Spark, concatenates META-INF/services, discards signatures, MANIFEST.MF and module-info, and skips tests.
- storage is a library bundled into the service jars, not a job.
- dockerLocal = queryService, graphService, embeddingWorker each run dockerLocalBuild. Each deployable gets its own jar in target/services/<image>/. A task copies the jar and the shared docker/services/Dockerfile into target/docker/<image>/ and runs `docker build -t scholarchain-<image>:local`.
- The task fails fast with a message if no docker daemon is available. It does no push, registry or account IDs.
- I did not run packageJobs or dockerLocal. They are outside `check` and were not requested, and no Docker daemon was checked.

6. Plugins: sbt-scalafmt 2.6.2 (format check) and sbt-assembly 2.5.0 (job and service jars). Each has a justification comment in plugins.sbt.

7. Compatibility risks
- Spark: Scala 3 can only consume Scala 2.13 artifacts. EMR 7.x ships Spark 3.5 on Scala 2.12, which cannot run Scala 3 code. The build therefore targets Spark 4.0.1 (Scala 2.13). Before any cloud run, confirm that an EMR release with Spark 4.x exists, or that a custom or bundled Spark 4 runtime is possible. This is the largest risk.
- Dataset encoders need Scala 2 TypeTags, so Spark code must stay DataFrame-shaped.
- Hadoop: hadoop-client-api 3.4.1 is a Java-only shaded API. Check that it matches the EMR Hadoop minor version.
- -Werror is on globally, so future code must be warning-free under Scala 3.3 LTS.
- Scala 3.3.9 LTS and sbt 1.13.1 were taken from the prepared package without independent cross-checks. They resolved and compiled.
- http4s ember on Scala 3 and cats-effect: no source exists yet, so untested.
- http4s and Spark resolution only occurs at compile of real code. Whether Spark 4.0.1 resolved at Test/compile is unobserved in my output (it succeeded).

ASSUMPTIONS: The human's rename of toolchain/versions.env to versions.sh is final. The prepared build package is the intended Phase 2 skeleton.

RISKS:
- `check` passes with zero tests. The "marker test" the work order mentions does not exist yet, so the green result proves structure only.
- I did not run packageJobs or dockerLocal.
- .sbtopts and .jvmopts are absent by choice. No compose file exists.
- git state is unchanged and nothing was committed.
- A target/ directory and the marker were produced by sbt, and both are git-ignored.

MANUAL ACTION REQUIRED: None. The human should decide whether the Spark 4.0 / EMR assumption is acceptable.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer fresh review of the build, then the human gate for Phase 02.
