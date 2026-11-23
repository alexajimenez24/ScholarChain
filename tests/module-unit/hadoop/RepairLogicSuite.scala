package scholarchain.hadoop

import scholarchain.core.*

/** Repair round 1: J03 same-ID consolidation (B3) and fallback keys of oversize blocks (B1). */
class RepairLogicSuite extends munit.FunSuite:
  private val run = FixtureIO.run

  private def ref(id: String, year: Option[Int], source: SourceType = SourceType.OPENALEX) =
    ExactLogic.IdRef("work", id, source, "", "graph systems", year, Some(id))

  test("J03: a repeated reference is a duplicate, differing copies a conflict, in any order") {
    val a1 = ref("W1", Some(2020))
    val a2 = ref("W1", Some(2021))
    val other = ref("a/1", Some(2020), SourceType.DBLP)
    val r1 = ExactLogic.exactJoin("doi:10.1/x", Vector(a1, a2, other), run)
    val r2 = ExactLogic.exactJoin("doi:10.1/x", Vector(other, a2, a1), run)
    assertEquals(r1, r2)
    assertEquals((r1.duplicates, r1.conflicts), (1, 1))
    assertEquals(r1.candidates.size, 1)
    // the kept copy is the smaller encoding (year 2020), whichever arrived first
    assertEquals(r1.candidates.head.features("year_distance"), 0.0)
    val same = ExactLogic.exactJoin("doi:10.1/x", Vector(a1, a1, other), run)
    assertEquals((same.duplicates, same.conflicts), (1, 0))
  }

  test("J03 refuses to expand the work pairs of a key above the cap, and says so") {
    val refs = (1 to 6).map(i => ref(s"W$i", Some(2020)).copy(title = s"graph systems $i"))
    val over = ExactLogic.exactJoin("doi:10.1/x", refs.toVector, run, 5)
    assert(over.candidates.isEmpty && over.oversize.exists(_.contains("6 works > 5")))
    val ok = ExactLogic.exactJoin("doi:10.1/x", refs.take(5).toVector, run, 5)
    assertEquals((ok.candidates.size, ok.oversize), (10, None))
  }

  private def summary(
      i: Int,
      name: String,
      coauthors: Vector[String] = Vector.empty,
      venue: Option[String] = None,
      year: Option[Int] = None
  ) = BlockLogic.MentionSummary(
    f"m:$i%020d",
    s"w:$i",
    SourceType.OPENALEX,
    name,
    Normalize.personName(name),
    s"mention:$i",
    None,
    None,
    None,
    year,
    venue,
    coauthors,
    Vector.empty
  )

  test("fallback keys pair two oversize-block members that share a coauthor, venue or year") {
    val others = (0 until 8).map(i => summary(i, "Wei Wang", Vector(s"c$i"), Some(s"v$i"), Some(i)))
    val a = summary(20, "Wei Wang", Vector("kim"), Some("vs"), Some(2010))
    val b = summary(21, "W. Wang", Vector("kim"), Some("vs"), Some(2010))
    val results = BlockLogic.block("wang_w", others.toVector ++ Vector(a, b), 5, run)
    val fb = results.filter(_.fallback)
    // groups: co:kim, v:vs, y:2010 (each 2 clusters); the pair is emitted once, by co:kim
    assertEquals(fb.size, 3)
    assertEquals(fb.flatMap(_.candidates).size, 1)
    assertEquals(fb.flatMap(_.candidates).head.blockKey, "wang_w|co:kim")
    assert(fb.forall(_.oversize.isEmpty))
    assert(!results.flatMap(_.candidates).exists(_.blockKey == "wang_w"))
  }

  test("a fallback block above the cap is refused; two mentions of one work never pair") {
    val same = (0 until 7).map(i => summary(i, "Wei Wang", Vector.empty, None, Some(2010)))
    val refused = BlockLogic.block("wang_w", same.toVector, 5, run).filter(_.fallback)
    assertEquals(refused.size, 1)
    assert(refused.head.oversize.get.contains("wang_w|y:2010"), refused.head.oversize.toString)
    // same work: one work id for all clusters gives no eligible fallback group at all
    val oneWork = (0 until 7)
      .map(i => summary(i, "Wei Wang", Vector.empty, None, Some(2010)))
      .map(_.copy(workId = "w:same"))
    assertEquals(BlockLogic.block("wang_w", oneWork.toVector, 5, run).count(_.fallback), 0)
  }

  test("work blocks fall back to venue and first-author keys, deterministically") {
    def w(i: Int, venue: String, author: String) = BlockLogic.WorkSummary(
      s"dblp:t$i",
      SourceType.DBLP,
      "common graph title",
      Some(2019),
      Some(venue),
      None,
      None,
      Vector(author)
    )
    val ws = (1 to 5).map(i => w(i, s"j$i", s"a$i")) ++ Seq(w(6, "js", "lu"), w(7, "js", "lu"))
    val r1 = BlockLogic.workBlockResult("wk:2019:common+graph", ws.toVector, 5, run)
    val r2 = BlockLogic.workBlockResult("wk:2019:common+graph", ws.reverse.toVector, 5, run)
    assertEquals(r1, r2)
    assert(r1.head.oversize.nonEmpty)
    val cands = r1.flatMap(_.candidates)
    assertEquals(cands.size, 1)
    assertEquals(cands.head.blockKey, "wk:2019:common+graph|a:lu")
  }
  private def parsed(xml: String) = DblpParser.parseRecord(xml).toOption.get

  test("J01 counts dropped invalid ORCID, doi.org and arxiv.org values, not ordinary URLs") {
    val bad =
      """<article key="j/x/1"><author orcid="0000-0002-1825-0098">A B</author>
        |<title>T.</title><year>2020</year><ee>https://doi.org/not-a-doi</ee>
        |<ee>https://arxiv.org/abs/nonsense</ee><ee>https://example.org/paper</ee></article>""".stripMargin
    assertEquals(DblpParser.invalidIdentifiers(parsed(bad)), 3)
    val good =
      """<article key="j/x/2"><author orcid="0000-0002-1825-0097">A B</author>
        |<title>T.</title><year>2020</year><ee>https://doi.org/10.1000/xyz</ee>
        |<ee>https://example.org/paper</ee></article>""".stripMargin
    assertEquals(DblpParser.invalidIdentifiers(parsed(good)), 0)
  }
end RepairLogicSuite
