package scholarchain.adversarial.hadoop

import scholarchain.adversarial.hadoop.HadoopBlackBox.*

/** Missing-field gate cases (spec Phase 4 Gate "missing-field"; job 2 "Write malformed and
  * unsupported records to versioned reject outputs"; spec "Detailed Hadoop requirements": missing
  * optional fields; counters for missing key and schema failure; MODULE.md: a fractional year or a
  * nameless author rejects the record, invalid DOI/ORCID values are dropped and counted as
  * INVALID_IDENTIFIER, a rejected unit is never dropped silently).
  *
  * DBLP, 17 units:
  * {{{
  *   kept      m/full (ee DOI, url), m/noee (no ee, no url), m/goodorcid, m/badorcid (invalid
  *             check digit, dropped), m/baddoi (ee https://doi.org/10.bogus, dropped)      5
  *   rejected  m/emptyauthor, m/blankauthor, m/fracyear (2020.5), no key attribute,
  *             key="", key="m/has space" (a work ID has no whitespace)                     6
  *   either    m/noyear, m/noauthor, m/badyear (20x0), m/oldyear (1850, below the core
  *             year bound 1900), m/notitle, m/novenue: kept or rejected, never invented    6
  * }}}
  * OpenAlex, 5 lines: 1 valid (kept), no id, broken JSON, a JSON array (3 rejected), a bare work
  * with only an id (either). Crossref, 3 lines: 1 valid (kept), no DOI, DOI "not-a-doi" (2
  * rejected). ORCID, 4 lines: 1 valid (kept), no orcid-identifier (rejected), invalid check digit
  * (never emitted with that iD), a bare valid iD (either).
  */
class MissingFieldSuite extends PipelineSuite:

  private val GoodOrcid = "0000-0009-1000-0019" // MOD 11-2 check digit 9 (verified)
  private val GoodOrcid2 = "0000-0009-1000-0027" // check digit 7 (verified)
  private val BadOrcid = "0000-0009-1000-0010" // check digit should be 9

  private def au(i: Int) = s"Ann ${surname(3000 + i)}"

  private val dblpRecs = Seq(
    rec(
      "m/full",
      Seq(au(0), au(1)),
      "Full Record",
      extra = "<ee>https://doi.org/10.5555/adv.mf.1</ee><url>db/m/full.html</url>"
    ),
    rec("m/noee", Seq(au(2), au(3)), "No Links Record"),
    s"""<article mdate="2026-11-01" key="m/goodorcid"><author orcid="$GoodOrcid">${au(
        4
      )}</author><author>${au(
        5
      )}</author><title>Good Orcid</title><year>2020</year><journal>ADVJ</journal></article>""",
    s"""<article mdate="2026-11-01" key="m/badorcid"><author orcid="$BadOrcid">${au(
        6
      )}</author><author>${au(
        7
      )}</author><title>Bad Orcid</title><year>2020</year><journal>ADVJ</journal></article>""",
    rec("m/baddoi", Seq(au(8), au(9)), "Bad Doi", extra = "<ee>https://doi.org/10.bogus</ee>"),
    rec("m/emptyauthor", Seq("", au(10)), "Empty Author"),
    rec("m/blankauthor", Seq("   ", au(11)), "Blank Author"),
    rec("m/fracyear", Seq(au(12), au(13)), "Fractional Year", Some("2020.5")),
    s"""<article mdate="2026-11-01"><author>${au(14)}</author><author>${au(
        15
      )}</author><title>MarkerNoKeyTitle</title><year>2020</year><journal>ADVJ</journal></article>""",
    rec("", Seq(au(16), au(17)), "MarkerEmptyKeyTitle"),
    rec("m/has space", Seq(au(18), au(19)), "MarkerSpaceKeyTitle"),
    rec("m/noyear", Seq(au(20), au(21)), "No Year", None),
    rec("m/noauthor", Nil, "No Author"),
    rec("m/badyear", Seq(au(22), au(23)), "Bad Year", Some("20x0")),
    rec("m/oldyear", Seq(au(24), au(25)), "Old Year", Some("1850")),
    s"""<article mdate="2026-11-01" key="m/notitle"><author>${au(26)}</author><author>${au(
        27
      )}</author><year>2020</year><journal>ADVJ</journal></article>""",
    rec("m/novenue", Seq(au(28), au(29)), "No Venue", venue = None)
  )

  private val certainKept = Set("m/full", "m/noee", "m/goodorcid", "m/badorcid", "m/baddoi")
  private val certainRejectedKeys = Set("m/emptyauthor", "m/blankauthor", "m/fracyear")
  private val keylessMarkers = Seq("MarkerNoKeyTitle", "MarkerEmptyKeyTitle", "MarkerSpaceKeyTitle")
  private val either =
    Seq("m/noyear", "m/noauthor", "m/badyear", "m/oldyear", "m/notitle", "m/novenue")

  private val openAlex = Seq(
    """{"id": "https://openalex.org/W9100000001", "doi": "https://doi.org/10.5555/adv.mf.oa1", "display_name": "OpenAlex Full", "publication_year": 2020, "type": "article", "authorships": [{"author_position": "first", "author": {"display_name": "Oa One", "id": "https://openalex.org/A9100000001"}, "raw_author_name": "Oa One", "institutions": []}, {"author_position": "last", "author": {"display_name": "Oa Two", "id": "https://openalex.org/A9100000002"}, "raw_author_name": "Oa Two", "institutions": []}], "topics": [{"id": "https://openalex.org/T9100001", "display_name": "Adversarial Topic"}], "primary_location": {"source": {"id": "https://openalex.org/S9100000001", "display_name": "ADVO"}}}""",
    """{"doi": "https://doi.org/10.5555/adv.mf.oa2", "display_name": "MarkerOaNoId", "publication_year": 2020, "type": "article", "authorships": []}""",
    """{"id": "https://openalex.org/W9100000003", "display_name": "MarkerOaBroken", """,
    """[1, 2, 3]""",
    """{"id": "https://openalex.org/W9100000004"}"""
  ).mkString("\n") + "\n"

  private val crossref = Seq(
    """{"DOI": "10.5555/ADV.MF.CR1", "title": ["Crossref Full"], "author": [{"given": "Cr", "family": "One", "sequence": "first"}, {"given": "Cr", "family": "Two", "sequence": "additional"}], "issued": {"date-parts": [[2020]]}, "container-title": ["ADVC"], "type": "journal-article"}""",
    """{"title": ["MarkerCrNoDoi"], "author": [{"given": "Cr", "family": "Three", "sequence": "first"}], "issued": {"date-parts": [[2020]]}, "type": "journal-article"}""",
    """{"DOI": "not-a-doi", "title": ["MarkerCrBadDoi"], "author": [{"given": "Cr", "family": "Four", "sequence": "first"}], "issued": {"date-parts": [[2020]]}, "type": "journal-article"}"""
  ).mkString("\n") + "\n"

  private val orcid = Seq(
    s"""{"orcid-identifier": {"path": "$GoodOrcid"}, "person": {"name": {"given-names": {"value": "Or"}, "family-name": {"value": "Valid"}}}}""",
    """{"person": {"name": {"given-names": {"value": "MarkerOrNoId"}, "family-name": {"value": "Nobody"}}}}""",
    s"""{"orcid-identifier": {"path": "$BadOrcid"}, "person": {"name": {"given-names": {"value": "Or"}, "family-name": {"value": "Invalid"}}}}""",
    s"""{"orcid-identifier": {"path": "$GoodOrcid2"}}"""
  ).mkString("\n") + "\n"

  private lazy val r: Run =
    run(
      Inputs(utf8(doc(dblpRecs*)), Some(openAlex), Some(crossref), Some(orcid)),
      fresh("missing")
    )

  private def keys: Set[String] = r.works.map(_("sourceRecordId").str).toSet
  private def work(k: String) = r.works.find(_("sourceRecordId").str == k).getOrElse(fail(k))
  private def rejectsNaming(stage: String, marker: String): Int =
    r.rejects(stage).count(_("input").str.contains(marker))

  test("DBLP: 17 units in; the 5 well-formed records are kept with all their fields") {
    assertOk(r)
    observe("stages", r.stageNames)
    observe("J01 counters", r.counters("J01"))
    assertEquals(r.counter("J01", "INPUT_RECORDS"), 17L)
    certainKept.foreach(k => assert(keys.contains(k), s"$k not kept; kept: $keys"))
    val full = work("m/full")
    assertEquals(str(full, "doi"), Some("10.5555/adv.mf.1"))
    assertEquals(full("year").num.toInt, 2020)
  }

  test("DBLP: missing optional ee and url keep the record with no DOI and no URL") {
    val w = work("m/noee")
    assertEquals(str(w, "doi"), None)
    assertEquals(w("urls").arr.size, 0)
  }

  test("DBLP: an empty or blank author name and a fractional year reject the record") {
    certainRejectedKeys.foreach { k =>
      assert(!keys.contains(k), s"$k was kept")
      assertEquals(rejectsNaming("J01", s"\"$k\""), 1, s"reject lines naming $k")
    }
  }

  test("DBLP: a missing, empty or whitespace key rejects the record; MISSING_KEY is counted") {
    keylessMarkers.foreach { m =>
      assert(!r.works.exists(_("title").str == m), s"record $m was kept")
      assertEquals(rejectsNaming("J01", m), 1, s"reject lines naming $m")
    }
    assert(r.counter("J01", "MISSING_KEY") >= 1L, r.counters("J01"))
  }

  test("DBLP: records with missing or bad year, authors, title or venue are accounted") {
    either.foreach { k =>
      val asWork = if keys.contains(k) then 1 else 0
      val asReject = rejectsNaming("J01", s"\"$k\"")
      observe(s"$k", s"work=$asWork reject=$asReject")
      assertEquals(asWork + asReject, 1, s"$k: work=$asWork reject=$asReject")
    }
    assertEquals(r.works.size + r.rejects("J01").size, 17)
    assertEquals(r.counter("J01", "REJECTED_RECORDS"), r.rejects("J01").size.toLong)
  }

  test("DBLP: no invented year (missing, 20x0, 1850) and no pair for a work without authors") {
    for k <- Seq("m/noyear", "m/badyear", "m/oldyear") if keys.contains(k) do
      val y = work(k).obj.get("year")
      assert(y.forall(_ == ujson.Null), s"$k kept with invented year $y")
    assertEquals(r.pairs.count(_("workId").str == "dblp:m/noauthor"), 0)
  }

  test("DBLP: an invalid ORCID and an invalid DOI are dropped and the record is kept") {
    val bad = work("m/badorcid")("authors")(0)
    assertEquals(str(bad, "orcid"), None)
    assertEquals(str(work("m/baddoi"), "doi"), None)
    val good = work("m/goodorcid")("authors")(0)
    assert(str(good, "orcid").exists(_.contains(GoodOrcid)), good)
    assert(!r.everyOutputText.contains(s"\"orcid\":\"$BadOrcid\""), "invalid ORCID emitted")
  }

  test("DBLP: a dropped invalid ORCID is counted as INVALID_IDENTIFIER") {
    // MODULE.md "Behavior to rely on": invalid DOI, ORCID and arXiv values are dropped and
    // counted as INVALID_IDENTIFIER. m/badorcid carries one invalid ORCID (check digit), so the
    // run must count at least 1; with the invalid doi.org link of m/baddoi it would be 2.
    observe(
      "INVALID_IDENTIFIER per stage",
      r.stageNames.map(s => s -> r.counter(s, "INVALID_IDENTIFIER"))
    )
    observe("J01 _counters.json", r.countersFile("J01"))
    val fromFiles = r.stageNames.map(s => r.countersFile(s).getOrElse("INVALID_IDENTIFIER", 0L)).sum
    assert(r.total("INVALID_IDENTIFIER") >= 1L, "INVALID_IDENTIFIER is 0 in every stage")
    assert(fromFiles >= 1L, "INVALID_IDENTIFIER is 0 in every _counters.json")
  }

  test(
    "OpenAlex: 5 lines; no id, broken JSON and a JSON array are rejected; the full work is kept"
  ) {
    val st = "J02-OPENALEX"
    observe("J02-OPENALEX counters", r.counters(st))
    assertEquals(r.counter(st, "INPUT_RECORDS"), 5L)
    val works = r.normalized("OPENALEX", "works")
    assert(works.exists(_("workId").str.contains("W9100000001")), works.map(_("workId")))
    assertEquals(rejectsNaming(st, "MarkerOaNoId"), 1)
    assertEquals(rejectsNaming(st, "MarkerOaBroken"), 1)
    assertEquals(rejectsNaming(st, "[1, 2, 3]") + rejectsNaming(st, "[1,2,3]"), 1)
    val bareKept = works.count(_("workId").str.contains("W9100000004"))
    val bareRej = rejectsNaming(st, "W9100000004")
    assertEquals(bareKept + bareRej, 1, s"bare work: kept=$bareKept rejected=$bareRej")
    assertEquals(r.counter(st, "REJECTED_RECORDS"), r.rejects(st).size.toLong)
  }

  test("Crossref: a work without a DOI or with a malformed DOI is rejected; the valid one kept") {
    val st = "J02-CROSSREF"
    assertEquals(r.counter(st, "INPUT_RECORDS"), 3L)
    val works = r.normalized("CROSSREF", "works")
    assertEquals(works.size, 1)
    assertEquals(str(works(0), "doi"), Some("10.5555/adv.mf.cr1"))
    assertEquals(rejectsNaming(st, "MarkerCrNoDoi"), 1)
    assertEquals(rejectsNaming(st, "MarkerCrBadDoi"), 1)
  }

  test("ORCID: no iD is rejected; an invalid iD is never emitted; every record is accounted") {
    val st = "J02-ORCID"
    assertEquals(r.counter(st, "INPUT_RECORDS"), 4L)
    val rs = r.normalized("ORCID", "researchers")
    observe("ORCID researchers", rs.map(x => str(x, "orcid")))
    assert(rs.exists(x => str(x, "orcid").exists(_.contains(GoodOrcid))), "valid profile missing")
    assert(!rs.exists(x => str(x, "orcid").exists(_.contains(BadOrcid))), "invalid iD emitted")
    assertEquals(rejectsNaming(st, "MarkerOrNoId"), 1)
    assertEquals(rs.size + r.rejects(st).size, 4)
  }

  test("every retained work from every source satisfies the core work contract") {
    val all = r.works ++ r.normalized("OPENALEX", "works") ++ r.normalized("CROSSREF", "works")
    assertEquals(workErrors(all), Vector.empty[String])
  }
