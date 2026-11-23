package scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

/** No-leakage checks: the matching code and the er-v1 policy cannot see gold labels. Evaluation
  * lives in `scholarchain.er.eval`, which depends on matching, never the other way round.
  */
class LeakageSuite extends munit.FunSuite:
  private val relative = Paths.get("modules", "entity-resolution", "src", "main")

  /** The module's `src/main`, found from the working directory (the forked test JVM runs in the
    * project folder or the repository root). Not finding it fails the test: it never passes
    * vacuously.
    */
  private lazy val main: Path =
    val start = Paths.get("").toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .flatMap(p => Vector(p.resolve(relative), p.resolve("src").resolve("main")))
      .find(p =>
        Files.isDirectory(p.resolve("scala/scholarchain/er")) && Files.exists(
          p.resolve("scala/scholarchain/er/MatchPolicy.scala")
        )
      )
      .getOrElse(fail(s"entity-resolution sources not found from $start"))

  private def files(dir: Path, ext: String): Vector[Path] =
    val walk = Files.walk(dir)
    try
      walk.iterator.asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(ext)).toVector
    finally walk.close()

  private val labelTerms =
    Vector("scholarchain.er.eval", "GoldCsv", "GoldLabel", "GoldRow", "datasets/gold", "er-gold")

  test("matching sources never reference the evaluation package, labels or the gold folder") {
    val er = main.resolve("scala/scholarchain/er")
    val matching =
      files(er, ".scala").filter(_.getParent == er) // the scholarchain.er package itself
    assert(matching.size >= 10, s"found ${matching.size} matching sources")
    val hits = for
      f <- matching
      text = Files.readString(f, StandardCharsets.UTF_8)
      term <- labelTerms
      if text.contains(term)
    yield s"${main.relativize(f)} mentions $term"
    assertEquals(hits, Vector.empty[String])
  }

  test("the policy, prompt and review resources hold no label and no gold path") {
    val resources =
      files(main.resolve("resources"), "").filterNot(p => p.toString.endsWith("er-eval-v1.json"))
    assert(resources.nonEmpty)
    val hits = resources.filter(p =>
      Files.readString(p, StandardCharsets.UTF_8).toLowerCase.contains("gold")
    )
    assertEquals(hits, Vector.empty[Path])
  }
