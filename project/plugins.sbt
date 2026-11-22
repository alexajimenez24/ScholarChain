// Plugin versions are pinned once, in project/Versions.scala (the `plugin*` vals).
// This file cannot import that object, because it defines the classpath the object is
// compiled on, so it reads the pins from the ledger text. Never write a version here.
// The ledger is resolved against this meta-build's base directory (project/), never the
// JVM working directory, so the load works whatever `user.dir` the launcher has.
def ledgerPin(name: String): Def.Initialize[String] = Def.setting {
  val file = baseDirectory.value / "Versions.scala"
  val text = scala.io.Source.fromFile(file).mkString
  val Pattern = ("""val\s+""" + name + """\s*=\s*"([^"]+)"""").r
  Pattern
    .findFirstMatchIn(text)
    .map(_.group(1))
    .getOrElse(
      sys.error(s"$file has no pin named $name")
    )
}

// Same as addSbtPlugin, with the version taken from the ledger setting above.
def ledgerPlugin(org: String, artifact: String, pin: String) =
  libraryDependencies += Def.setting {
    Defaults.sbtPluginExtra(
      org % artifact % ledgerPin(pin).value,
      (pluginCrossBuild / sbtBinaryVersion).value,
      (pluginCrossBuild / scalaBinaryVersion).value
    )
  }.value

// Formatter: `fmt` and the format check inside `check` (Scala 3 dialect, .scalafmt.conf).
ledgerPlugin("org.scalameta", "sbt-scalafmt", "pluginSbtScalafmt")

// Packaging: sbt-assembly builds the fat jars for `packageJobs` (EMR job jars) and the
// services jar that `dockerLocal` puts in the image. One plugin for both, so Provided
// exclusion and merge rules live in one place. Chosen over sbt-native-packager because
// EMR needs a single self-contained jar, and the image is a plain Dockerfile (docker/).
ledgerPlugin("com.eed3si9n", "sbt-assembly", "pluginSbtAssembly")
