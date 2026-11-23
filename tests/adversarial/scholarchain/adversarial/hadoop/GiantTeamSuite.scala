package scholarchain.adversarial.hadoop

import scholarchain.adversarial.hadoop.HadoopBlackBox.*
import scholarchain.hadoop.PipelineArgs

/** Giant-team and pair-expansion gate cases (spec Phase 4 job 6 and Gate: "Pair generation passes
  * hand-checkable papers with 1, 2, 3, and larger author lists"; "Count and report unusually large
  * author teams"; MODULE.md: a team above `--max-team` is not expanded, it is counted in
  * OVERSIZE_TEAMS, the refused pairs in PAIRS_REFUSED_BY_CAP, and a reject line is written).
  *
  * Run 1, `--max-team 12`, every author name unique except the repeat in g/rep:
  * {{{
  *   key     authors  year  pairs n(n-1)/2  note
  *   g/n1     1       2011   0
  *   g/n2     2       2012   1
  *   g/n3     3       2013   3
  *   g/n12   12       2014  66              exactly at the cap, expanded
  *   g/rep    3       2015   3              "Rae Repeat" at positions 0 and 2
  *   g/n13   13       2016   0 (78 refused) one above the cap
  * }}}
  * works 6, teams 6, author papers 1+2+3+12+3+13 = 34, pairs 0+1+3+66+3 = 73, OVERSIZE_TEAMS 1,
  * PAIRS_REFUSED_BY_CAP 13*12/2 = 78.
  *
  * Run 2, default cap (300): g/n301 refused (301*300/2 = 45150 pairs refused), g/n25 expanded (300
  * pairs); author papers 301+25 = 326.
  */
class GiantTeamSuite extends PipelineSuite:

  private var next = 0
  private def names(n: Int): Seq[String] =
    (0 until n).map { _ =>
      next += 1
      s"Ann ${surname(next)}"
    }

  private val sizes = Seq("g/n1" -> 1, "g/n2" -> 2, "g/n3" -> 3, "g/n12" -> 12)
  private val years = Map(
    "g/n1" -> 2011,
    "g/n2" -> 2012,
    "g/n3" -> 2013,
    "g/n12" -> 2014,
    "g/rep" -> 2015,
    "g/n13" -> 2016
  )

  private lazy val run1: Run =
    val recs =
      sizes.zipWithIndex.map { case ((k, n), i) =>
        rec(k, names(n), s"${word("Team", i)} ${word("Size", i)}", Some(years(k).toString))
      } ++ Seq(
        rec(
          "g/rep",
          Seq("Rae Repeat") ++ names(1) ++ Seq("Rae Repeat"),
          "Repeated Author Paper",
          Some("2015")
        ),
        rec("g/n13", names(13), "Thirteen Authors Paper", Some("2016"))
      )
    run(Inputs(utf8(doc(recs*))), fresh("run1"), extra = Seq("--max-team", "12"))

  private lazy val run2: Run =
    val recs = Seq(
      rec("g/n301", names(301), "Three Hundred One Authors", Some("2017")),
      rec("g/n25", names(25), "Twenty Five Authors", Some("2018"))
    )
    run(Inputs(utf8(doc(recs*))), fresh("run2"))

  private def pairsPerWork(r: Run): Map[String, Int] =
    r.pairs.groupBy(_("workId").str).view.mapValues(_.size).toMap

  test("run 1 succeeds and keeps all 6 works, 6 teams and 34 author papers") {
    assertOk(run1)
    assertEquals(
      run1.works.map(_("workId").str).sorted,
      years.keys.map("dblp:" + _).toVector.sorted
    )
    assertEquals(run1.teams.size, 6)
    assertEquals(run1.authorPapers.size, 34)
  }

  test("hand-checkable expansion: 1, 2, 3 and 12 authors give 0, 1, 3 and 66 pairs; 73 in all") {
    val per = pairsPerWork(run1)
    observe("run1 pairs per work", per.toVector.sorted)
    assertEquals(per.getOrElse("dblp:g/n1", 0), 0)
    assertEquals(per.getOrElse("dblp:g/n2", 0), 1)
    assertEquals(per.getOrElse("dblp:g/n3", 0), 3)
    assertEquals(per.getOrElse("dblp:g/n12", 0), 66)
    assertEquals(run1.pairs.size, 73)
    assertEquals(run1.counter("J06", "EMITTED_PAIRS"), 73L)
  }

  test("each expanded team yields every unordered position pair exactly once") {
    val teamSize = run1.teams.map(t => t("workId").str -> t("teamSize").num.toInt).toMap
    run1.pairs.groupBy(_("workId").str).foreach { (w, ps) =>
      val n = teamSize(w)
      val got = ps.map { p =>
        val a = p("positionA").num.toInt
        val b = p("positionB").num.toInt
        (math.min(a, b), math.max(a, b))
      }
      val want = for i <- 0 until n; j <- (i + 1) until n yield (i, j)
      assertEquals(got.sorted, want.toVector, s"position pairs of $w")
    }
    val keys = run1.pairs.map(p => (p("mentionA").str, p("mentionB").str, p("workId").str))
    assertEquals(keys.distinct.size, keys.size, "repeated pair rows")
  }

  test("every pair carries work ID, year, positions, team size and DBLP provenance; all valid") {
    assertEquals(pairErrors(run1.pairs), Vector.empty[String])
    assertEquals(teamErrors(run1.teams), Vector.empty[String])
    val teams = run1.teams.map(t => t("workId").str -> t).toMap
    run1.pairs.foreach { p =>
      val w = p("workId").str
      val t = teams(w)
      val ids = t("mentionIds").arr.map(_.str).toSet
      assert(ids.contains(p("mentionA").str) && ids.contains(p("mentionB").str), s"pair outside $w")
      assertEquals(p("year").num.toInt, years(w.stripPrefix("dblp:")), s"year of $w")
      assertEquals(p("teamSize").num.toInt, t("teamSize").num.toInt, s"teamSize of $w")
      val prov = p("provenance")
      assertEquals(prov("source").str, "DBLP")
      assertEquals(prov("snapshot").str, Snapshot)
      assertEquals(prov("runId").str, run1.runId)
    }
  }

  test(
    "a repeated author name keeps two mentions with distinct IDs at positions 0 and 2; 3 pairs"
  ) {
    val team = run1.teams.find(_("workId").str == "dblp:g/rep").getOrElse(fail("no team g/rep"))
    val ids = team("mentionIds").arr.map(_.str).toVector
    assertEquals(ids.size, 3)
    assertEquals(ids.distinct.size, 3)
    val aps = run1.authorPapers.filter(_("workId").str == "dblp:g/rep")
    assertEquals(aps.map(_("position").num.toInt).sorted, Vector(0, 1, 2))
    assertEquals(pairsPerWork(run1).getOrElse("dblp:g/rep", 0), 3)
  }

  test("team mention order follows author position (author papers agree with the team)") {
    val byWork = run1.authorPapers.groupBy(_("workId").str)
    run1.teams.foreach { t =>
      val w = t("workId").str
      val ids = t("mentionIds").arr.map(_.str).toVector
      val fromAps =
        byWork.getOrElse(w, Vector.empty).sortBy(_("position").num).map(_("mentionId").str)
      assertEquals(ids, fromAps, s"mention order of $w")
    }
  }

  test("a team one above the cap is counted once and not expanded: 78 pairs refused") {
    assertEquals(pairsPerWork(run1).getOrElse("dblp:g/n13", 0), 0)
    assertEquals(run1.total("OVERSIZE_TEAMS"), 1L)
    assertEquals(run1.total("PAIRS_REFUSED_BY_CAP"), 78L)
    val rejects = run1.allRejects.filter((_, r) => r("input").str.contains("g/n13"))
    observe("run1 oversize reject stages", rejects.map(_._1))
    assert(rejects.nonEmpty, "no reject line names the oversize team g/n13")
  }

  test("the oversize team keeps its team record and 13 author papers") {
    val team = run1.teams.find(_("workId").str == "dblp:g/n13").getOrElse(fail("no team g/n13"))
    assertEquals(team("teamSize").num.toInt, 13)
    assertEquals(team("mentionIds").arr.map(_.str).distinct.size, 13)
    assertEquals(run1.authorPapers.count(_("workId").str == "dblp:g/n13"), 13)
  }

  test("the run summary reports the largest team (g/n13, 13 authors)") {
    val s = run1.summary
    observe("run1 summary maxTeamObserved", s("maxTeamObserved"))
    observe("run1 summary largestPairExpansion", s("largestPairExpansion"))
    assertEquals(s("maxTeamObserved")("workId").str, "dblp:g/n13")
    assertEquals(s("maxTeamObserved")("teamSize").num.toInt, 13)
    // MODULE.md does not say whether a refused team counts as an expansion: 66 or 78.
    assert(Set(66, 78).contains(s("largestPairExpansion").num.toInt), s("largestPairExpansion"))
  }

  test("counters are visible: _counters.json of J06 equals the summary counters") {
    assertEquals(run1.countersFile("J06"), run1.counters("J06"))
  }

  test("default cap 300: a 301-author paper refuses 45150 pairs; a 25-author paper gives 300") {
    assertOk(run2)
    val per = pairsPerWork(run2)
    assertEquals(per.getOrElse("dblp:g/n301", 0), 0)
    assertEquals(per.getOrElse("dblp:g/n25", 0), n2(25))
    assertEquals(run2.pairs.size, 300)
    assertEquals(run2.total("OVERSIZE_TEAMS"), 1L)
    assertEquals(run2.total("PAIRS_REFUSED_BY_CAP"), n2(301).toLong)
    assertEquals(run2.authorPapers.size, 326)
    assertEquals(run2.summary("maxTeamObserved")("teamSize").num.toInt, 301)
  }

  test("cap arguments: --max-team and --max-block below 2 or not integers are usage errors") {
    val base = Seq("--dblp", "d.xml", "--output", "o", "--snapshot", Snapshot)
    assert(PipelineArgs.parse(base).isRight, "base arguments")
    assert(PipelineArgs.parse(base ++ Seq("--max-team", "2")).isRight, "--max-team 2")
    assert(PipelineArgs.parse(base ++ Seq("--max-block", "2")).isRight, "--max-block 2")
    for bad <- Seq(
        Seq("--max-team", "1"),
        Seq("--max-team", "0"),
        Seq("--max-team", "-5"),
        Seq("--max-team", "abc"),
        Seq("--max-team", "99999999999"),
        Seq("--max-block", "1"),
        Seq("--reducers", "0")
      )
    do assert(PipelineArgs.parse(base ++ bad).isLeft, s"accepted ${bad.mkString(" ")}")
  }

  test("a cap flag with no value is a usage error, never silently replaced by the default") {
    // MODULE.md: arguments come as `--name value` pairs; an invalid value is a usage error.
    // A trailing `--max-team` with no value would otherwise run with the default cap of 300.
    val base = Seq("--dblp", "d.xml", "--output", "o", "--snapshot", Snapshot)
    for bad <- Seq(Seq("--max-team"), Seq("--max-block"))
    do
      val got = PipelineArgs.parse(base ++ bad)
      assert(got.isLeft, s"accepted ${bad.mkString(" ")} as $got")
  }

  test("snapshot argument: a missing or impossible calendar date is a usage error") {
    val noSnap = Seq("--dblp", "d.xml", "--output", "o")
    assert(PipelineArgs.parse(noSnap).isLeft, "missing --snapshot")
    for d <- Seq("2026-02-30", "2026-13-01", "26-11-22", "2026-11-22T00:00", "")
    do assert(PipelineArgs.parse(noSnap ++ Seq("--snapshot", d)).isLeft, s"accepted '$d'")
  }
