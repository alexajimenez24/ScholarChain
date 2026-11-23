package scholarchain.adversarial.hadoop

import scholarchain.adversarial.hadoop.HadoopBlackBox.*

/** Common-name and common-title blocking gate cases (spec Phase 4 Gate "common-name"; job 4
  * "Prevent pathological common-name or title blocks from overwhelming reducers"; spec "Detailed
  * Hadoop requirements": "The blocking design must include maximum block size, fallback keys,
  * counters, and a policy for oversize groups"; MODULE.md: name blocks `r:<last>_<initial>`, work
  * blocks `wk:<year>:<two longest title tokens>`, a block above `--max-block` is rejected and
  * yields no candidates (OVERSIZE_BLOCKS), reducers buffer only up to 10 x `--max-block`, the
  * summary reports `largestBlockObserved`; DBLP disambiguates homonyms by numeric suffix: identical
  * raw strings are one source cluster, different suffixes are distinct clusters with
  * `dblp_homonym_distinct`).
  *
  * One run with `--max-block 5` (hard buffer cap 50); every other author is a unique "Ann Q..."
  * name:
  * {{{
  *   c/wang1..8   "Wei Wang 0001".."Wei Wang 0008" (8 distinct DBLP people); years 2001..2008;
  *                journals JW1..JW8; unique coauthors
  *   c/wang9      "Wei Wang"  + "Kim Zorander", 2010, journal JSHARED
  *   c/wang10     "W. Wang"   + "Kim Zorander", 2010, journal JSHARED
  *                -> name block wang_w: 10 mentions in 10 distinct clusters > 5: oversize
  *   c/title1..7  title "Common Graph Title", 2019; journals JT1..JT5, and JTSHARED for 6 and 7,
  *                whose first author is "Lu Fallback" in both
  *                -> work block 2019 + same title: 7 works > 5: oversize
  *   c/ray1..5    "Bob Ray", "B. Ray", "Bobby Ray", "Bo Ray", "Bea Ray"
  *                -> name block ray_b: 5 members = cap, not oversize
  *   c/qiu1,2     "Lin Qiu 0001", "Lin Qiu 0002" -> name block qiu_l: 2 members
  *   c/zhang1..60 "Hu Zhang" (one identical DBLP string, one source cluster), years 1960..2019
  *                -> name block zhang_h: 60 mentions, above the hard buffer cap of 50
  * }}}
  * works 8+2+7+5+2+60 = 84, every work has 2 authors: 168 author papers, 84 pairs. The Wangs of
  * c/wang9 and c/wang10 share coauthor, venue and year, and no other member of the Wang block
  * shares any of these with anyone: any fallback key built from coauthor context, venue or year
  * (the spec's list) puts exactly these two together. The same holds for c/title6 and c/title7
  * (venue and first author). The largest group is zhang_h with 60 members.
  */
class CommonNameSuite extends PipelineSuite:

  private val MaxBlock = 5

  private val wang = (1 to 8).map { i =>
    rec(
      s"c/wang$i",
      Seq(f"Wei Wang 00$i%02d", s"Ann ${surname(5000 + i)}"),
      s"${word("Wangtitle", i)} ${word("Wangtopic", i)}",
      Some((2000 + i).toString),
      venue = Some(s"JW$i")
    )
  } ++ Seq(9 -> "Wei Wang", 10 -> "W. Wang").map { (i, name) =>
    rec(
      s"c/wang$i",
      Seq(name, "Kim Zorander"),
      s"${word("Wangtitle", i)} ${word("Wangtopic", i)}",
      Some("2010"),
      venue = Some("JSHARED")
    )
  }

  private val titles = (1 to 7).map { i =>
    val first = if i >= 6 then "Lu Fallback" else s"Ann ${surname(5100 + i)}"
    rec(
      s"c/title$i",
      Seq(first, s"Ann ${surname(5150 + i)}"),
      "Common Graph Title",
      Some("2019"),
      venue = Some(if i >= 6 then "JTSHARED" else s"JT$i")
    )
  }

  private val rayNames = Seq("Bob Ray", "B. Ray", "Bobby Ray", "Bo Ray", "Bea Ray")
  private val rays = rayNames.zipWithIndex.map { (n, i) =>
    rec(
      s"c/ray${i + 1}",
      Seq(n, s"Ann ${surname(5200 + i)}"),
      s"${word("Raytitle", i)} ${word("Raytopic", i)}",
      Some((2001 + i).toString),
      venue = Some(s"JR$i")
    )
  }

  private val qius = Seq(
    rec("c/qiu1", Seq("Lin Qiu 0001", s"Ann ${surname(5300)}"), "Homonym Paper One", Some("2012")),
    rec("c/qiu2", Seq("Lin Qiu 0002", s"Ann ${surname(5301)}"), "Homonym Paper Two", Some("2013"))
  )

  private val ZhangN = 60
  private val zhangs = (1 to ZhangN).map { i =>
    rec(
      s"c/zhang$i",
      Seq("Hu Zhang", s"Ann ${surname(5400 + i)}"),
      s"${word("Zhangtitle", i)} ${word("Zhangtopic", i)}",
      Some((1959 + i).toString),
      venue = Some(s"JZ$i")
    )
  }

  private lazy val r: Run =
    run(
      Inputs(utf8(doc((wang ++ titles ++ rays ++ qius ++ zhangs)*))),
      fresh("common"),
      extra = Seq("--max-block", MaxBlock.toString)
    )

  private def work(k: String) =
    r.works.find(_("sourceRecordId").str == k).getOrElse(fail(s"no work $k"))
  private def mentionOf(k: String, pos: Int): String = work(k)("authors")(pos)("mentionId").str
  private def endpoints(c: ujson.Value) = Set(c("sourceId").str, c("targetId").str)
  private def linking(a: String, b: String) =
    r.blockedCandidates.filter(c => endpoints(c) == Set(a, b))
  private def within(ids: Set[String]) =
    r.blockedCandidates.filter(c => endpoints(c).subsetOf(ids))

  private lazy val wangMentions = (1 to 10).map(i => mentionOf(s"c/wang$i", 0)).toSet
  private lazy val zhangMentions = (1 to ZhangN).map(i => mentionOf(s"c/zhang$i", 0)).toSet

  test("blocking caps never drop data: 84 works, 84 teams, 168 author papers, 84 pairs") {
    assertOk(r)
    assertEquals(r.works.size, 84)
    assertEquals(r.teams.size, 84)
    assertEquals(r.authorPapers.size, 168)
    assertEquals(r.pairs.size, 84)
    assertEquals(r.counter("J01", "REJECTED_RECORDS"), 0L)
  }

  test("oversize blocks are counted and each writes a reject line: at least the 2 built here") {
    observe("J04 counters", r.counters("J04"))
    observe("J04 rejects", r.rejects("J04").map(x => x("reason").str))
    observe(
      "candidates per block key",
      r.blockedCandidates.groupBy(_("blockKey").str).view.mapValues(_.size).toVector.sorted
    )
    assert(r.counter("J04", "OVERSIZE_BLOCKS") >= 2L, r.counters("J04"))
    assertEquals(r.rejects("J04").size.toLong, r.counter("J04", "REJECTED_RECORDS"))
    assert(r.rejects("J04").size >= 2, "fewer than 2 J04 reject lines")
  }

  test("common name, 10 distinct people: the oversize block itself yields no candidates") {
    val ww = within(wangMentions)
    observe("Wang-Wang candidates", ww.map(c => (c("blockKey").str, c("features"))))
    val keys = r.blockedCandidates.map(_("blockKey").str).toSet
    assert(!keys.contains("wang_w") && !keys.contains("r:wang_w"), "oversize block compared")
    assert(ww.size <= n2(MaxBlock), s"${ww.size} Wang-Wang candidates, cap allows ${n2(MaxBlock)}")
  }

  test("common name, one DBLP string 60 times (above the 50 buffer cap): linear, never quadratic") {
    val zz = within(zhangMentions)
    observe("Zhang-Zhang candidates", zz.size)
    assert(zz.size < ZhangN, s"${zz.size} candidates for $ZhangN mentions of one source cluster")
  }

  test("common title: no block compares more than max-block of the 7 same-title works") {
    val ids = (1 to 7).map(i => s"dblp:c/title$i").toSet
    val tt = within(ids)
    observe("title-title candidates", tt.map(c => (c("blockKey").str, endpoints(c))))
    assert(tt.size <= n2(MaxBlock), s"${tt.size} title candidates, cap allows ${n2(MaxBlock)}")
  }

  test("a block exactly at the cap is not oversize: the 5 Rays are still compared") {
    val rayIds = (1 to 5).map(i => mentionOf(s"c/ray$i", 0)).toSet
    val rr = within(rayIds)
    observe("Ray candidates", rr.size)
    assert(rr.nonEmpty, "no candidate inside the at-cap block ray_b")
  }

  test("the run summary reports the largest observed blocking group: zhang_h, 60 members") {
    val l = r.summary("largestBlockObserved")
    observe("largestBlockObserved", l)
    assertEquals(l("members").num.toInt, ZhangN)
    assert(l("key").str.contains("zhang"), l)
  }

  test("DBLP homonym suffixes are compared as distinct: the candidate says dblp_homonym_distinct") {
    val c = linking(mentionOf("c/qiu1", 0), mentionOf("c/qiu2", 0))
    assert(c.nonEmpty, "no candidate links Lin Qiu 0001 and Lin Qiu 0002")
    assert(c.forall(x => ujson.write(x).contains("dblp_homonym_distinct")), c)
  }

  test("fallback keys: the oversize name block still compares the two Wangs sharing context") {
    // Spec "Detailed Hadoop requirements": the blocking design must include fallback keys and a
    // policy for oversize groups. c/wang9 and c/wang10 share coauthor, venue and year.
    val c = linking(mentionOf("c/wang9", 0), mentionOf("c/wang10", 0))
    observe("fallback Wang candidates", c)
    assert(c.nonEmpty, "the oversize Wang block left no fallback comparison for c/wang9-c/wang10")
  }

  test("fallback keys: the oversize title block still compares the two works sharing context") {
    // c/title6 and c/title7 share title, year, venue and first author.
    val c = linking("dblp:c/title6", "dblp:c/title7")
    observe("fallback title candidates", c)
    assert(c.nonEmpty, "the oversize title block left no fallback comparison for title6-title7")
  }
