/** The single version ledger: every library and sbt plugin pin lives here, exactly once.
  *
  * Rules: exact versions only, no ranges, no "latest", no "+" suffix. Library values are moved only
  * by dependency-updater; the structure of this file, the plugin pins and the Scala version belong
  * to build-engineer.
  *
  * Other pins, stated once elsewhere because their owner is not the build:
  *   - sbt itself: project/build.properties (sbt.version)
  *   - scalafmt-core: .scalafmt.conf (matches toolchain/versions.sh)
  *   - Docker base image tag and digest: docker/services/Dockerfile (FROM line, shared by the three
  *     service images)
  *   - JDK, sbt launcher, scalafmt CLI: toolchain/
  *
  * project/plugins.sbt cannot see this object (it defines the classpath this file is compiled on),
  * so it reads the `plugin*` vals below as text. They are still pinned here and nowhere else.
  */
object Versions {

  // Language: Scala 3 LTS line. LTS keeps the Scala 2.13 library ABI that Spark's
  // CrossVersion.for3Use2_13 artifacts need.
  val scala3 = "3.3.9"

  // Target JVM release (scalac -release, javac --release): Spark 4.0 supports Java 17
  // (the emr-spark-8.0.0 JVM is to be confirmed in Phase 12) and the Corretto 17 service image
  // runs it, so bytecode and Java API are pinned to 17.
  val jvmRelease = "17"

  // Runtime-supplied platform (Provided). Spark targets a Spark 4.0 EMR runtime, which is
  // built for Scala 2.13, the line Scala 3 links against (Spark 3.5 on EMR 7.x is Scala
  // 2.12 and cannot run Scala 3 code). Hadoop 3.4.x is the client API both lines use.
  // Re-check against the target EMR release notes before the first cloud run.
  // Spark runtime target (human decision): EMR release label emr-spark-8.0.0, which ships
  // Spark 4.0.2 and Scala 2.13.16 (Spark 4.0 is GA on EMR Serverless, EMR on EC2 and EMR
  // on EKS). The build stays on Spark 4.0.1, the same 4.0 line. us-east-2 availability of
  // emr-spark-8.0.0 is to be confirmed in Phase 12.
  val hadoop = "3.4.1"
  val spark = "4.0.1"

  // The only JSON library of the codebase.
  val upickle = "4.4.3"

  // HTTP services: http4s 0.23 on cats-effect 3. 0.23.35 is the first release with the
  // Ember HTTP/2 fixes (CVE-2026-69203, CVE-2026-69208); stay at or above it.
  val http4s = "0.23.38"
  val catsEffect = "3.7.1"

  // The only test framework of the codebase.
  val munit = "1.3.6"

  // Logging: API on the compile classpath, one implementation in Runtime scope.
  val slf4j = "2.0.20"
  val logback = "1.5.38"

  // sbt plugins (read as text by project/plugins.sbt, see the header).
  val pluginSbtScalafmt = "2.6.2"
  val pluginSbtAssembly = "2.5.0"
}
