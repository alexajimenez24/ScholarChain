package scholarchain.hadoop

import scholarchain.core.*

/** Bad input and hard limits through the whole local pipeline. Every number is derived from the
  * input text written in this file.
  */
class RejectsAndLimitsSuite extends munit.FunSuite:
  private val h = new Harness("rejects")
  override def afterAll(): Unit = h.delete()

  // ------------------------------------------------------------ bad records and an oversize team

  private val bigTitle = "x" * 900
  private val dblp: String =
    s"""<?xml version="1.0" encoding="ISO-8859-1"?>
       |<dblp>
       |<article key="j/ok/1"><author>A One</author><author>B Two</author><title>Good One.</title><year>2020</year><journal>J</journal></article>
       |<article key="j/ok/1"><author>A One</author><author>B Two</author><title>Good One.</title><year>2020</year><journal>J</journal></article>
       |<article key="j/ok/2"><author>Sam Lee</author><author>Sam Lee</author><title>Repeated Names</title><year>2021</year><journal>J</journal></article>
       |<article key="j/dup/1"><author>C Three</author><title>Alpha</title><year>2019</year><journal>J</journal></article>
       |<article key="j/dup/1"><author>C Three</author><title>Beta</title><year>2019</year><journal>J</journal></article>
       |<article><author>No Key</author><title>Keyless</title></article>
       |<article key="j/ent/1"><author>D Four</author><title>Bad &bogus; entity</title></article>
       |<article key="j/title/1"><author>E Five</author><title></title></article>
       |<article key="j/year/1"><author>F Six</author><title>Old</title><year>1850</year></article>
       |<article key="j/noauth/1"><title>Nobody</title><year>2020</year></article>
       |<www key="homepages/x/y"><author>X Y</author><title>Home Page</title></www>
       |<article key="j/big/1"><author>G Seven</author><title>$bigTitle</title><year>2020</year></article>
       |<article key="j/open/1"><author>H Eight</author><title>never closed
       |<inproceedings key="j/rec/1"><author>I Nine</author><author>J Ten</author><title>Recovered One</title><year>2020</year><booktitle>B</booktitle></inproceedings>
       |<article key="j/rec/2"><author>K Eleven</author><author>L Twelve</author><title>Recovered Two</title><year>2020</year><journal>J</journal></article>
       |<article key="j/team/1"><author>M Aa</author><author>N Bb</author><author>O Cc</author><author>P Dd</author><author>Q Ee</author><title>Team Of Five</title><year>2022</year><journal>J</journal></article>
       |<article key="j/emptyauthor/1"><author></author><title>Empty Author</title></article>
       |</dblp>
       |""".stripMargin

  private lazy val openAlex: String =
    val good = FixtureIO.lines("openalex-works-fixture.jsonl").head
    Vector(
      good,
      "{",
      """{"display_name":"No Id"}""",
      """{"id":"https://openalex.org/W77","display_name":" "}""",
      """{"id":"https://openalex.org/W78","display_name":"Fractional","publication_year":2015.5}""",
      good
    ).mkString("\n") + "\n"

  private lazy val r: h.Result =
    h.run(
      Vector(
        "--dblp",
        h.write("dblp.xml", dblp).toString,
        "--openalex",
        h.write("oa.jsonl", openAlex).toString,
        "--crossref",
        h.write("cr.jsonl", """{"DOI":"nope","title":["t"]}""" + "\n").toString,
        "--orcid",
        h.write("orcid.jsonl", """{"orcid-identifier":{"path":"0000-0002-1825-0098"}}""" + "\n")
          .toString,
        "--snapshot",
        FixtureIO.snapshot,
        "--max-team",
        "4",
        "--reducers",
        "2",
        "--output",
        h.dir.resolve("out-bad").toString
      ),
      Map(DblpRecordReader.MaxRecordBytesKey -> "700")
    )
  private def p = r.paths

  test("the pipeline finishes: bad records are rejected, never crash a task") {
    assertEquals(r.code, 0)
  }

  test(
    "J01: 17 units in = 9 accepted + 1 skipped + 7 rejected; duplicates and the conflict counted"
  ) {
    // units: 12 records up to j/big/1, the unclosed j/open/1, the two records it swallowed, j/team/1
    // and j/emptyauthor/1 = 17. Accepted: ok/1 twice, ok/2, dup/1 twice, noauth, rec/1, rec/2,
    // team/1 = 9. Skipped: the www page. Rejected: keyless, bad entity, empty title, year 1850,
    // oversize, unclosed, empty author name = 7.
    assertEquals(
      r.counters(p.dblpWorks),
      Map(
        Ctr.Input -> 17L,
        Ctr.Accepted -> 9L,
        Ctr.Skipped -> 1L,
        Ctr.Rejected -> 7L,
        Ctr.MissingKey -> 1L,
        "DBLP_SKIPPED_WWW" -> 1L,
        "DBLP_TYPE_ARTICLE" -> 8L,
        "DBLP_TYPE_INPROCEEDINGS" -> 1L,
        // reducer: 7 distinct work IDs; ok/1 and dup/1 each have one extra copy; dup/1 differs
        "UNIQUE_RECORDS" -> 7L,
        Ctr.Duplicate -> 2L,
        Ctr.Conflict -> 1L
      )
    )
    assertEquals(r.decode[WorkRecord](p.dblpWorks).size, 7)
  }

  test("J01 reject output is versioned, names the stage and run, and says why") {
    val rejects = r.rejects(p.dblpWorks)
    assertEquals(rejects.size, 7)
    assert(rejects.forall(x => x.stage == "J01" && x.runId == p.runId))
    val reasons = rejects.map(_.reason)
    for expected <- Vector(
        "without key",
        "bogus",
        "empty title",
        "implausible year",
        "Oversize",
        "Truncated",
        "no name"
      )
    do assert(reasons.exists(_.contains(expected)), s"no reject mentions '$expected': $reasons")
    assert(rejects.forall(x => x.input.length <= 2000))
    // the unclosed record is rejected alone: its two neighbours were recovered, not lost
    val works = r.decode[WorkRecord](p.dblpWorks).map(_.sourceRecordId).toSet
    assert(Set("j/rec/1", "j/rec/2").subsetOf(works))
    assert(p.dblpWorks.contains(s"snapshot=${FixtureIO.snapshot}/run_id=${p.runId}"))
  }

  test("J02: OpenAlex 6 lines = 2 accepted + 4 rejected; Crossref and ORCID bad lines rejected") {
    val oa = p.normalized(SourceType.OPENALEX)
    assertEquals(
      r.counters(oa),
      Map(
        Ctr.Input -> 6L,
        Ctr.Accepted -> 2L,
        Ctr.Rejected -> 4L,
        "WORKS_WRITTEN" -> 2L,
        "TOPICS_WRITTEN" -> 2L
      )
    )
    val reasons = r.rejects(oa).map(_.reason)
    for expected <- Vector("not JSON", "work id", "empty title", "whole number") do
      assert(
        reasons.exists(_.contains(expected)),
        s"no OpenAlex reject mentions '$expected': $reasons"
      )
    assertEquals(
      r.counters(p.normalized(SourceType.CROSSREF)),
      Map(Ctr.Input -> 1L, Ctr.Rejected -> 1L)
    )
    assertEquals(
      r.counters(p.normalized(SourceType.ORCID)),
      Map(Ctr.Input -> 1L, Ctr.Rejected -> 1L)
    )
    assert(r.rejects(p.normalized(SourceType.ORCID)).head.reason.contains("invalid iD"))
  }

  test("J03: works without an exact identifier are counted as missing keys") {
    // 7 DBLP works (none has an ee link, so no DOI) + 2 OpenAlex lines = 9 inputs. The 2 lines
    // are one OpenAlex work given twice; it carries a DOI and an OpenAlex ID, so it sits twice
    // under `doi:` and twice under `openalex:`: 1 extra copy per key = DUPLICATE_RECORDS 2, no
    // conflict (the copies are identical).
    assertEquals(
      r.counters(p.exactCandidates),
      Map(
        Ctr.Input -> 9L,
        Ctr.Accepted -> 9L,
        Ctr.MissingKey -> 7L,
        "MISSING_DOI" -> 7L,
        Ctr.Duplicate -> 2L
      )
    )
  }

  test("J05 and J06: a team above the cap is counted and rejected, the others expand fully") {
    // J05 reads 7 DBLP works + 2 OpenAlex lines; j/noauth/1 has no authors (skipped); the OpenAlex
    // work is one work twice (1 duplicate), so 7 teams. Pairs before cap: ok/1 1, ok/2 1 (two
    // mentions of "Sam Lee"), dup/1 0, rec/1 1, rec/2 1, team/1 10, OpenAlex 1 = 15.
    assertEquals(
      r.counters(p.teams),
      Map(
        Ctr.Input -> 9L,
        Ctr.Accepted -> 8L,
        Ctr.Skipped -> 1L,
        Ctr.WorksWithoutAuthors -> 1L,
        Ctr.Duplicate -> 1L,
        "TEAMS_WRITTEN" -> 7L,
        "AUTHOR_PAPERS_WRITTEN" -> 16L,
        Ctr.PairsBeforeCap -> 15L,
        "TEAM_SIZE_1" -> 1L,
        "TEAM_SIZE_2_5" -> 6L
      )
    )
    // J06: 7 teams in; team/1 (5 authors) exceeds the cap of 4 and its 10 pairs are refused;
    // 1+1+0+1+1+1 = 5 pairs are emitted
    assertEquals(
      r.counters(p.pairs),
      Map(
        Ctr.Input -> 7L,
        Ctr.Accepted -> 6L,
        Ctr.Rejected -> 1L,
        Ctr.OversizeTeam -> 1L,
        Ctr.PairsRefused -> 10L,
        Ctr.EmittedPair -> 5L,
        "UNIQUE_RECORDS" -> 5L
      )
    )
    val rej = r.rejects(p.pairs)
    assertEquals(rej.map(_.input), Vector("dblp:j/team/1"))
    assert(rej.head.reason.contains("exceeds pair cap 4"))
    assertEquals(r.summary("maxTeamObserved")("teamSize").num.toInt, 5)
    assertEquals(r.summary("largestPairExpansion").num.toLong, 10L)
  }

  test("a repeated author name keeps both mentions and their pair") {
    val ok2 = r.decode[PublicationTeam](p.teams, "teams").find(_.workId == "dblp:j/ok/2").get
    assertEquals(ok2.teamSize, 2)
    assertEquals(ok2.mentionIds.distinct.size, 2)
    val pairs = r.decode[CoauthorPairCandidate](p.pairs).filter(_.workId == "dblp:j/ok/2")
    assertEquals(pairs.size, 1)
  }

  // ------------------------------------------------------------ a pathological common-name block

  private val smith: String =
    val authors = (1 to 25).map(i => f"<author>John Smith $i%04d</author>").mkString
    s"""<dblp>
       |<article key="j/smith/1">$authors<title>The Smith Consortium</title><year>2020</year><journal>J</journal></article>
       |<article key="j/lee/1"><author>Ann Lee</author><author>Bo Lee</author><title>Small Paper</title><year>2020</year><journal>J</journal></article>
       |</dblp>
       |""".stripMargin

  private def smithRun(maxBlock: Int, label: String): h.Result =
    h.run(
      Vector(
        "--dblp",
        h.write("smith.xml", smith).toString,
        "--snapshot",
        FixtureIO.snapshot,
        "--max-block",
        maxBlock.toString,
        "--reducers",
        "2",
        "--output",
        h.dir.resolve(label).toString
      )
    )

  test("a common-name block above the cluster cap is counted, rejected and never expanded") {
    // 25 distinct "John Smith 00NN" authors share block smith_j: 25 clusters of one mention, over
    // the cap of 3 and, after splitting by full name, still over it. The 25 members stay under the
    // hard cap (3 x 10 = 30), so the block is read but refused; singleton clusters have no links.
    val s = smithRun(3, "out-smith3")
    assertEquals(s.code, 0)
    assertEquals(
      s.counters(s.paths.blockedCandidates),
      Map(Ctr.Input -> 2L, Ctr.Accepted -> 2L, Ctr.OversizeBlock -> 1L, Ctr.Rejected -> 1L)
    )
    val rej = s.rejects(s.paths.blockedCandidates)
    assertEquals(rej.size, 1)
    // the refused full-name part is named in the one line and not counted a second time
    assertEquals(
      rej.head.reason,
      "block smith_j has 25 clusters > 3; not compared whole, split by full name; " +
        "1 parts refused: block smith_j/john smith has 25 clusters > 3"
    )
    assertEquals(s.decode[MatchCandidate](s.paths.blockedCandidates), Vector.empty)
    assertEquals(s.summary("largestBlockObserved")("members").num.toInt, 25)
    assertEquals(s.summary("largestBlockObserved")("key").str, "r:smith_j")
  }

  test("a block above the hard cap is not even buffered") {
    val s = smithRun(2, "out-smith2") // hard cap 2 x 10 = 20 < 25 members
    assertEquals(s.code, 0)
    assertEquals(
      s.counters(s.paths.blockedCandidates),
      Map(Ctr.Input -> 2L, Ctr.Accepted -> 2L, Ctr.OversizeBlock -> 1L, Ctr.Rejected -> 1L)
    )
    assert(s.rejects(s.paths.blockedCandidates).head.reason.contains("hard cap 20"))
  }

  test("a 25-author paper expands to 300 pairs and is the largest team and expansion reported") {
    val s = smithRun(3, "out-smith3")
    assertEquals(s.counters(s.paths.teams)(Ctr.PairsBeforeCap), 301L) // 25*24/2 + 1
    assertEquals(s.counters(s.paths.pairs)(Ctr.EmittedPair), 301L)
    assertEquals(s.counters(s.paths.teams)("TEAM_SIZE_21_100"), 1L)
    assertEquals(s.summary("maxTeamObserved")("teamSize").num.toInt, 25)
    assertEquals(s.summary("largestPairExpansion").num.toLong, 300L)
    assertEquals(s.decode[CoauthorPairCandidate](s.paths.pairs).count(_.teamSize == 25), 300)
  }

  // ------------------------------------------------------------ J03 exact-key cap and J04 fallback

  private def article(key: String, authors: Vector[String], title: String, rest: String): String =
    val names = authors.map(a => s"<author>$a</author>").mkString
    s"""<article key="$key">$names<title>$title</title>$rest</article>"""

  /** 10 works, run once with `--max-block 3`. Four works share one DOI and two share another, with
    * the same title in different years. Four more works have four different "H. Zhang" authors
    * (block zhang_h) of one year: all four share "Kim Lee" and the first two also share "Pat
    * Quill". Keys `z/...` give no venue. No other name or title block holds two clusters or works.
    */
  private val caps: String =
    val shared = Vector(
      ("Ada Abel", "Quantum Lattices"),
      ("Ben Boyd", "Marine Sediments"),
      ("Cal Cruz", "Protein Folding"),
      ("Dee Dunn", "Urban Mobility")
    ).zipWithIndex.map { case ((name, title), i) =>
      article(
        s"j/shared/$i",
        Vector(name),
        title,
        "<year>2010</year><ee>https://doi.org/10.1000/shared</ee>"
      )
    }
    val pair = Vector("Eve East", "Fay Ford").zipWithIndex.map { (name, i) =>
      article(
        s"j/pair/$i",
        Vector(name),
        "Glacier Dynamics",
        s"<year>${2001 + i}</year><ee>https://doi.org/10.1000/pair</ee>"
      )
    }
    val zhangs = Vector(
      ("Hao Zhang", "Sparse Solvers"),
      ("Hui Zhang", "Coral Genomics"),
      ("Hong Zhang", "Lunar Regolith"),
      ("Han Zhang", "Wireless Beamforming")
    ).zipWithIndex.map { case ((name, title), i) =>
      val coauthors = Vector(name, "Kim Lee") ++ Vector("Pat Quill").filter(_ => i < 2)
      article(s"z/zhang$i", coauthors, title, "<year>2018</year>")
    }
    (shared ++ pair ++ zhangs).mkString("<dblp>\n", "\n", "\n</dblp>\n")

  private lazy val capped: h.Result =
    h.run(
      Vector(
        "--dblp",
        h.write("caps.xml", caps).toString,
        "--snapshot",
        FixtureIO.snapshot,
        "--max-block",
        "3",
        "--reducers",
        "2",
        "--output",
        h.dir.resolve("out-caps").toString
      )
    )

  test("J03 job: an exact key above --max-block is counted once, rejected once and not paired") {
    assertEquals(capped.code, 0)
    // 10 works in; the 4 z/ works have no identifier. Each DOI key counts one source that holds
    // more than one work (2 duplicates). The only expanded work pair is the control DOI pair.
    assertEquals(
      capped.counters(capped.paths.exactCandidates),
      Map(
        Ctr.Input -> 10L,
        Ctr.Accepted -> 10L,
        Ctr.MissingKey -> 4L,
        "MISSING_DOI" -> 4L,
        Ctr.Duplicate -> 2L,
        Ctr.OversizeBlock -> 1L,
        Ctr.Rejected -> 1L,
        Ctr.ExactCandidates -> 1L
      )
    )
    val rej = capped.rejects(capped.paths.exactCandidates)
    assertEquals(
      rej.map(x => (x.stage, x.reason, x.input, x.runId)),
      Vector(
        (
          "J03",
          "exact key doi:10.1000/shared holds 4 works > 3; work pairs not expanded",
          "doi:10.1000/shared",
          capped.paths.runId
        )
      )
    )
    assertEquals(
      capped.decode[MatchCandidate](capped.paths.exactCandidates).map(_.blockKey),
      Vector("doi:10.1000/pair")
    )
  }

  test("J04 job: the block above the cap and each refused fallback block are counted apart") {
    // zhang_h: 4 clusters > 3, every full name its own part, so no part is refused: one
    // OVERSIZE_BLOCKS. Its fallback blocks co:lee_k and y:2018 hold 4 (refused), co:quill_p holds
    // 2 (compared, 1 pair). The other 4 candidates are same-cluster links: 3 for the 4 "Kim Lee"
    // mentions and 1 for the 2 "Pat Quill" mentions.
    assertEquals(
      capped.counters(capped.paths.blockedCandidates),
      Map(
        Ctr.Input -> 10L,
        Ctr.Accepted -> 10L,
        Ctr.OversizeBlock -> 1L,
        Ctr.FallbackRefused -> 2L,
        Ctr.FallbackCompared -> 1L,
        Ctr.Rejected -> 3L,
        Ctr.BlockedCandidates -> 5L
      )
    )
    val rej = capped.rejects(capped.paths.blockedCandidates)
    assertEquals(
      rej.map(_.reason).sorted,
      Vector(
        "block zhang_h has 4 clusters > 3; not compared whole, split by full name",
        "fallback block zhang_h|co:lee_k has 4 clusters > 3",
        "fallback block zhang_h|y:2018 has 4 clusters > 3"
      )
    )
    assert(
      rej.forall(x => x.stage == "J04" && x.runId == capped.paths.runId && x.input == x.reason),
      rej.toString
    )
    val candidates = capped.decode[MatchCandidate](capped.paths.blockedCandidates)
    assertEquals(candidates.size, 5)
    assertEquals(
      candidates.map(_.blockKey).filter(_.contains("zhang_h")),
      Vector("zhang_h|co:quill_p")
    )
  }
