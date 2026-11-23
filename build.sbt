// ScholarChain build: executable policy. Versions live only in project/Versions.scala.
//
// Environment input surface. The build definition itself reads no environment variable.
// The tool layer around it does; a new input needs build-engineer approval and a line
// here:
//   - JAVA_HOME and PATH, which pick the JDK sbt runs on. toolchain/env.sh sets both (and
//     honours SCHOLARCHAIN_TOOLS for the tool directory). The bytecode level and Java API
//     are pinned by Versions.jvmRelease (-release), so the JDK cannot change the output;
//     a JDK that cannot enforce it fails the load.
//   - The sbt launcher and JVM inherit SBT_OPTS, JAVA_OPTS, JAVA_TOOL_OPTIONS,
//     JDK_JAVA_OPTIONS, _JAVA_OPTIONS and SBT_NATIVE_CLIENT.
//     They can override the pinned sbt version, resolvers and JVM flags; a reproducible
//     build needs them unset.
//   - dockerLocal only: the docker CLI's DOCKER_* variables (DOCKER_HOST, DOCKER_CONTEXT,
//     DOCKER_DEFAULT_PLATFORM and the rest).
// The build holds no secrets, cloud endpoints, bucket names, account IDs or deployment
// steps. Procedures belong in scripts/.
//
// Deliberate absences (one choice per concern, never a second):
//   - JSON: uPickle only. No circe, play-json, json4s, jackson-module-scala or spray-json
//     as a declared dependency, and no http4s-circe: services read and write request and
//     response bodies as text and convert them with uPickle. (Spark and Hadoop bring their own Jackson/json4s on the
//     cluster; application code must not use them.)
//   - Tests: MUnit only. No ScalaTest, specs2 or JUnit-style framework.
//   - Logging: slf4j-api everywhere, one implementation (logback) in Runtime scope.

val V = Versions

// Fail fast: compiling with --release N needs a full JDK (lib/ct.sym) of at least N. A
// JRE-only or too-old JDK would otherwise compile against its own, wrong, Java API.
val runningJdk = java.lang.Runtime.version().feature()
val hasCtSym = new File(sys.props("java.home"), "lib/ct.sym").exists
val jdkCheck: Unit =
  if (runningJdk < V.jvmRelease.toInt || !hasCtSym)
    throw new MessageOnlyException(
      s"JDK $runningJdk at ${sys.props("java.home")} cannot enforce --release ${V.jvmRelease} " +
        "(too old, or no lib/ct.sym); run `source toolchain/env.sh` to use the pinned JDK."
    )

ThisBuild / scalaVersion := V.scala3
ThisBuild / organization := "scholarchain"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror")
// Pin the target JVM: bytecode level and Java API, whatever JDK runs sbt.
ThisBuild / scalacOptions ++= Seq("-release", V.jvmRelease)
ThisBuild / javacOptions ++= Seq("--release", V.jvmRelease) // Compile and Test Java sources

// Paths ---------------------------------------------------------------------------

val unitTestRoot = Def.setting((ThisBuild / baseDirectory).value / "tests" / "module-unit")

// Test layout shared by every test root (module unit tests, contracts, adversarial).
// The folder itself is the test source root and <folder>/resources is the only resource
// root. Because resources/ sits inside the source root, it is excluded from source
// discovery, so a .scala or .java file placed there is never compiled (it is only copied
// as a resource). A leftover <folder>/target is excluded the same way. Owners put
// sources anywhere under the folder except resources/ and target/.
def testRootSettings(root: Def.Initialize[File]): Seq[Setting[_]] = Seq(
  // Never compile stray .scala files sitting in the project base folder in Compile (no
  // MUnit there); the test sources are the Test root only.
  sourcesInBase := false,
  Test / unmanagedSourceDirectories := Seq(root.value),
  Test / unmanagedResourceDirectories := Seq(root.value / "resources"),
  Test / unmanagedSources / excludeFilter := {
    val resDir = (root.value / "resources").getCanonicalFile.toPath
    val targetDir = (root.value / "target").getCanonicalFile.toPath // stale build output
    (Test / unmanagedSources / excludeFilter).value ||
    new SimpleFileFilter(f => {
      val p = f.getCanonicalFile.toPath
      p.startsWith(resDir) || p.startsWith(targetDir)
    })
  },
  // Test-only library (Test scope).
  libraryDependencies += "org.scalameta" %% "munit" % V.munit % Test
)

// One runtime module: sources in modules/<folder>, unit tests in
// tests/module-unit/<folder>. Test sources and resources come ONLY from the tests/ tree,
// never from src/test.
def module(id: String, folder: String): Project =
  Project(id, file(s"modules/$folder"))
    .settings(testRootSettings(Def.setting(unitTestRoot.value / folder)))

// Packaging ----------------------------------------------------------------------
// No deployment, upload, registry or S3 path here: these tasks only write under target/.
//
// Job jars (packageJobs): hadoop, entityResolution and sparkAnalytics are the EMR jobs.
// storage is NOT a job: it is a library bundled into the service jars (through the
// deployables). If storage loaders later run as their own EMR step or task, give that
// project the assembly settings below and add it to packageJobs. assembly packages the
// Runtime classpath, so Provided Hadoop and Spark stay out; the Scala 3 library and
// uPickle go in. Job jars land in target/jobs/ with deterministic names.

val jarsDir = Def.setting((ThisBuild / baseDirectory).value / "target" / "jobs")
// Service jars are not jobs: each deployable gets its own folder under target/services/
// (the dockerLocal input), so three images never share a build context.
def serviceJarDir(name: String) =
  Def.setting((ThisBuild / baseDirectory).value / "target" / "services" / name)

def packagingSettings(outDir: Def.Initialize[File]) = Seq(
  assembly / assemblyJarName := s"${name.value}-${version.value}.jar",
  assembly / assemblyOutputPath := outDir.value / (assembly / assemblyJarName).value,
  assembly / test := {}, // `check` owns testing; packaging never reruns tests
  assembly / assemblyMergeStrategy := {
    val default = (assembly / assemblyMergeStrategy).value // plugin default as fallback
    (path: String) =>
      path match {
        case PathList("META-INF", "services", _*) => MergeStrategy.concat
        case PathList("META-INF", xs @ _*)
            if xs.lastOption.exists(_.toLowerCase.matches(".*\\.(sf|dsa|rsa)")) =>
          MergeStrategy.discard
        case PathList("META-INF", "MANIFEST.MF")                        => MergeStrategy.discard
        case "module-info.class" | PathList("META-INF", "versions", _*) => MergeStrategy.discard
        case other                                                      => default(other)
      }
  }
)

// Local images (dockerLocal): one plain Dockerfile in docker/services/, shared by the three
// deployables (each image gets its own jar as app.jar), driven by this task.
// Chosen over sbt-native-packager: the Dockerfile is the reviewable pinned-base, non-
// root, percentage memory policy, with no generated layers and no plugin to keep in
// step. Local daemon only: no push, no registry, no account IDs. Not part of `check`;
// it needs a Docker daemon.
val dockerLocalBuild = taskKey[Unit]("Build this deployable's local image with the docker CLI")

def dockerSettings(imageName: String) = Seq(
  dockerLocalBuild := Def.taskDyn {
    val log = streams.value.log
    val dockerOk =
      try
        scala.sys.process
          .Process(Seq("docker", "info"))
          .!(scala.sys.process.ProcessLogger(_ => ())) == 0
      catch { case _: java.io.IOException => false }
    if (!dockerOk)
      throw new MessageOnlyException(
        "dockerLocal needs the docker CLI on PATH and a running daemon; `docker` was not found " +
          "or failed. Install Docker locally and rerun. Nothing was built."
      )
    Def.task {
      val jar = (assembly).value
      val root = (ThisBuild / baseDirectory).value
      val ctx = root / "target" / "docker" / imageName
      IO.delete(ctx)
      IO.createDirectory(ctx)
      IO.copyFile(jar, ctx / "app.jar")
      IO.copyFile(root / "docker" / "services" / "Dockerfile", ctx / "Dockerfile")
      val code =
        scala.sys.process
          .Process(Seq("docker", "build", "-t", s"scholarchain-$imageName:local", ctx.getPath))
          .!
      if (code != 0) sys.error(s"docker build failed with exit code $code")
      log.info(s"built local image scholarchain-$imageName:local")
    }
  }.value
)

// Modules and the dependency graph (AGENTS.md boundary table) -----------------------
//   core -> (external libraries only)
//   hadoop, entityResolution, sparkAnalytics, storage -> core
//   rag -> core, storage
//   servicesCommon -> core
//   queryService -> core, rag, storage, servicesCommon
//   graphService -> core, storage, servicesCommon
//   embeddingWorker -> core, storage, servicesCommon
// Pipeline stages exchange data through versioned core contracts on S3, never by code
// imports. No module depends on a service project, and the three deployables never depend
// on one another. The graph is acyclic by construction.

lazy val core = module("core", "core")
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % V.upickle, // the only JSON library
      "org.slf4j" % "slf4j-api" % V.slf4j
    )
  )

// Hadoop: the shaded client API is Java and Scala-version independent, and EMR supplies
// it, so it is Provided (compile and test classpath, not packaged). The shaded runtime and a
// commons-logging bridge are Test-only: they let LocalJobRunner run the real MapReduce jobs on
// fixtures inside `check`.
lazy val hadoop = module("hadoop", "hadoop")
  .dependsOn(core)
  .settings(packagingSettings(jarsDir))
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client-api" % V.hadoop % Provided,
      "org.apache.hadoop" % "hadoop-client-runtime" % V.hadoop % Test,
      "org.slf4j" % "jcl-over-slf4j" % V.slf4j % Test
    ),
    // Forked so Hadoop's ServiceLoader-based FileSystem lookup sees a flat classpath, not
    // sbt's layered test class loaders.
    Test / fork := true,
    Test / javaOptions += "-Xmx1g"
  )

lazy val entityResolution = module("entityResolution", "entity-resolution")
  .dependsOn(core)
  .settings(packagingSettings(jarsDir))

// Spark compatibility: Scala 3 consumes only Scala 2.13 artifacts
// (CrossVersion.for3Use2_13) and needs the 2.13 standard library at runtime. EMR 7.x runs
// Spark 3.5 on Scala 2.12, which cannot load Scala 3 code (wrong standard library, and
// Spark's varargs methods erase differently in 2.12 and 2.13). So the Spark module targets
// Spark 4.0, which is built for Scala 2.13 (project/Versions.scala). Application code stays
// DataFrame-shaped: Dataset encoders need Scala 2 TypeTags, which Scala 3 does not have.
lazy val sparkAnalytics = module("sparkAnalytics", "spark")
  .dependsOn(core)
  .settings(packagingSettings(jarsDir))
  .settings(
    libraryDependencies +=
      ("org.apache.spark" %% "spark-sql" % V.spark % Provided).cross(CrossVersion.for3Use2_13),
    // scala3-library_3 3.3.9 depends on scala-library 2.13.18, but the EMR Spark runtime ships
    // 2.13.16 (project/Versions.scala). Bundling 2.13.18 would put two standard libraries on
    // the Spark classpath, so the Spark job jar leaves scala-library out and links against the
    // cluster's copy. scala3-library_3 itself is still bundled (the cluster has no Scala 3).
    // Residual risk: Scala 3.3.9 emits code against the 2.13.18 library; a call to a member
    // added after 2.13.16 would throw NoSuchMethodError on the cluster. Unverified until the
    // first EMR run (a Scala 3 LTS patch pinned to scala-library <= 2.13.16 would remove it).
    // hadoop and entityResolution keep scala-library bundled: a plain Hadoop MapReduce
    // cluster supplies no Scala library at all.
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter(_.data.getName.startsWith("scala-library-"))
    }
  )

lazy val storage = module("storage", "storage")
  .dependsOn(core)

lazy val rag = module("rag", "rag")
  .dependsOn(core, storage)

// Services: http4s Ember on cats-effect. servicesCommon holds what every deployable shares
// (middleware for Cognito JWT verification, request and trace IDs, timeouts, the typed
// error model and CloudWatch Embedded Metric Format output) and is a library, not an image.
// Each deployable is its own assembly jar and image. Only the service images run a
// long-lived JVM that owns its logging, so the logback implementation is added to the
// deployables in Runtime scope. Hadoop and Spark jobs use the cluster's own log4j binding.
lazy val servicesCommon = module("servicesCommon", "services/common")
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % V.http4s,
      "org.http4s" %% "http4s-dsl" % V.http4s,
      "org.typelevel" %% "cats-effect" % V.catsEffect
    )
  )

def deployable(id: String, folder: String, image: String): Project =
  module(id, s"services/$folder")
    .settings(packagingSettings(serviceJarDir(image)))
    .settings(dockerSettings(image))
    .settings(
      libraryDependencies += "ch.qos.logback" % "logback-classic" % V.logback % Runtime
    )

lazy val queryService = deployable("queryService", "query", "query-service")
  .dependsOn(core, rag, storage, servicesCommon)

lazy val graphService = deployable("graphService", "graph", "graph-service")
  .dependsOn(core, storage, servicesCommon)

lazy val embeddingWorker = deployable("embeddingWorker", "worker", "embedding-worker")
  .dependsOn(core, storage, servicesCommon)

// The spec's required `services` project: an aggregate with no sources of its own, so
// `sbt services/test` (and every other task) runs across the four service projects.
lazy val services = project
  .in(file("modules/services"))
  .aggregate(servicesCommon, queryService, graphService, embeddingWorker)
  .settings(
    name := "services",
    publish / skip := true,
    Compile / unmanagedSourceDirectories := Nil,
    Test / unmanagedSourceDirectories := Nil
  )

// AWS-free test projects, owned by test-engineer, test-only and never a dependency of any
// module. Each sees every module's public API. Provided Hadoop and Spark do not flow
// through dependsOn, so they are re-added below at Test scope (same ledger values), so a
// test can touch a job's Hadoop or Spark types.
//   tests/contracts   contract tests (fakes and frozen fixtures, no AWS)
//   tests/adversarial adversarial black-box tests built on fakes and fixtures, no AWS
// Both are separate projects with the same layout (testRootSettings) and both sit in the
// root aggregate, so `check` runs them. Chosen over a single combined project so each
// suite keeps its own folder, report and failure scope.
// tests/integration and tests/end-to-end are deliberately NOT sbt projects: they need
// live or emulated AWS or a running stack, so they run from scripts/, never in `check`.
// test-engineer asks for a project here if one of them becomes AWS-free.
def blackBoxTests(id: String, folder: String): Project =
  Project(id, file(s"tests/$folder"))
    .dependsOn(
      core,
      hadoop,
      entityResolution,
      sparkAnalytics,
      storage,
      rag,
      servicesCommon,
      queryService,
      graphService,
      embeddingWorker
    )
    .settings(testRootSettings(Def.setting((ThisBuild / baseDirectory).value / "tests" / folder)))
    .settings(
      // Output goes outside the test source root, so generated files under target/ are
      // never discovered as test sources.
      target := (ThisBuild / baseDirectory).value / "target" / "tests" / folder,
      Compile / unmanagedSourceDirectories := Nil,
      publish / skip := true,
      libraryDependencies ++= Seq(
        "org.apache.hadoop" % "hadoop-client-api" % V.hadoop % Test,
        "org.apache.hadoop" % "hadoop-client-runtime" % V.hadoop % Test,
        ("org.apache.spark" %% "spark-sql" % V.spark % Test).cross(CrossVersion.for3Use2_13)
      )
    )

lazy val contractTests = blackBoxTests("contractTests", "contracts")
lazy val adversarialTests = blackBoxTests("adversarialTests", "adversarial")

// Root: aggregates everything, so `test` and `Test/compile` cover all modules and the
// contract and adversarial tests. Holds no sources.
lazy val root = (project in file("."))
  .aggregate(
    core,
    hadoop,
    entityResolution,
    sparkAnalytics,
    storage,
    rag,
    services,
    contractTests,
    adversarialTests
  )
  .settings(
    name := "scholarchain",
    publish / skip := true
  )

// markTestRun and aliases ---------------------------------------------------------

val markTestRun = taskKey[Unit](
  "Touch .claude/.last-test-run, only after every test in every project has passed"
)

ThisBuild / markTestRun := {
  // Depending on the aggregated test results makes a failing test abort this task before
  // the body runs, so the marker is untouched on any failure.
  val results = (Test / test).all(ScopeFilter(inAnyProject)).value
  val marker = (ThisBuild / baseDirectory).value / ".claude" / ".last-test-run"
  IO.createDirectory(marker.getParentFile)
  IO.touch(marker, setModified = true)
  streams.value.log.info(s"markTestRun: ${results.size} project test runs passed, touched $marker")
}

addCommandAlias("fmt", "scalafmtAll; scalafmtSbt")
// check = format check, compile (main and test), all tests including tests/contracts
// and tests/adversarial (no AWS), then the marker. markTestRun runs every project's
// Test/test itself (once), so the alias has no separate `test` step. The alias stops at
// the first failure.
addCommandAlias(
  "check",
  "scalafmtCheckAll; scalafmtSbtCheck; compile; Test/compile; markTestRun"
)

addCommandAlias(
  "packageJobs",
  "hadoop/assembly; entityResolution/assembly; sparkAnalytics/assembly"
)
addCommandAlias(
  "dockerLocal",
  "queryService/dockerLocalBuild; graphService/dockerLocalBuild; embeddingWorker/dockerLocalBuild"
)
