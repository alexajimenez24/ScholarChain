package scholarchain.hadoop

/** Strict argument parsing (repair B4): every malformed argument list is a usage error. */
class RepairArgsSuite extends munit.FunSuite:
  private val base = Vector("--dblp", "d", "--output", "x", "--snapshot", "2026-11-22")

  test("a valid list parses, and compress accepts exactly true or false") {
    assert(PipelineArgs.parse(base).isRight)
    assertEquals(
      PipelineArgs.parse(base ++ Vector("--compress", "false")).toOption.get.compress,
      false
    )
    assertEquals(
      PipelineArgs.parse(base ++ Vector("--compress", "true")).toOption.get.compress,
      true
    )
  }

  test("an unknown, repeated, value-less or misspelled argument is rejected") {
    val bad = Vector(
      base ++ Vector("--max-team"),
      base ++ Vector("--max-teams", "5"),
      base ++ Vector("--max-team", "5", "--max-team", "6"),
      base ++ Vector("--max-team", "--max-block", "5"),
      base ++ Vector("stray"),
      base ++ Vector("--compress", "yes"),
      base ++ Vector("--compress", "FALSE")
    )
    bad.foreach(a => assert(PipelineArgs.parse(a).isLeft, a.toString))
  }
