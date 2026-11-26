package scholarchain.adversarial.hadoop

import java.nio.file.Path

import scholarchain.adversarial.hadoop.HadoopBlackBox.*

/** Duplicate-ID gate cases (spec Phase 4 Gate "duplicate-ID" and "Repeated runs with the same input
  * produce equivalent logical output"; job 3 "Emit exact-identifier keys and duplicate or conflict
  * counters"; operational "Deterministic outputs", "Idempotent output paths", "Explicit partition
  * strategy"; MODULE.md: J01 keeps one line per work ID, the smallest JSON; a rerun with the same
  * inputs is idempotent; the run ID excludes the output root; candidate IDs are `mc:<kind>:...`
  * with sourceId < targetId).
  *
  * DBLP (order of run D1):
  * {{{
  *   d/same  copy 1      2 authors  "Same Twice"              2020
  *   d/conf  version X   2 authors  "Conflict Version Alpha"  2021
  *   d/solo              2 authors  "Solo Record"             2019
  *   d/same  copy 2      byte-identical to copy 1
  *   d/conf  version Y   3 authors  "Conflict Version Beta"   2022
  *   d/doi               2 authors  ee https://doi.org/10.5555/ADV.DUP.1   2018
  * }}}
  * Run D2 has the same records in another order (Y before X). OpenAlex: work W9200000001 with DOI
  * https://doi.org/10.5555/adv.dup.1, the line given twice. Crossref: DOI 10.5555/Adv.Dup.1. All
  * three DOI spellings normalize to one key.
  *
  * Expected: DBLP works 4; CONFLICT_RECORDS 1 (only d/conf differs); DUPLICATE_RECORDS 1 or 2
  * (MODULE.md does not say whether a conflicting extra copy also counts as a duplicate). Distinct
  * works 4 + 1 + 1 = 6, so 6 exported works and 6 teams. DOI holders: 3 distinct works, so exact
  * candidates n(n-1)/2 = 3. Pairs: d/same 1, d/conf 1 or 3 (as the kept version), d/solo 1, d/doi
  * 1, W9200000001 1, Crossref 1: 6 or 8.
  */
class DuplicateIdSuite extends PipelineSuite:

  private def au(i: Int) = s"Ann ${surname(4000 + i)}"

  private val same = rec("d/same", Seq(au(0), au(1)), "Same Twice", Some("2020"))
  private val confX = rec("d/conf", Seq(au(2), au(3)), "Conflict Version Alpha", Some("2021"))
  private val confY =
    rec("d/conf", Seq(au(2), au(3), au(4)), "Conflict Version Beta", Some("2022"))
  private val solo = rec("d/solo", Seq(au(5), au(6)), "Solo Record", Some("2019"))
  private val doiRec = rec(
    "d/doi",
    Seq(au(7), au(8)),
    "Doi Holder",
    Some("2018"),
    extra = "<ee>https://doi.org/10.5555/ADV.DUP.1</ee>"
  )

  private val oaLine =
    """{"id": "https://openalex.org/W9200000001", "doi": "https://doi.org/10.5555/adv.dup.1", "display_name": "Doi Holder Oa", "publication_year": 2018, "type": "article", "authorships": [{"author_position": "first", "author": {"display_name": "Oa Qdaj", "id": "https://openalex.org/A9200000001"}, "raw_author_name": "Oa Qdaj", "institutions": []}, {"author_position": "last", "author": {"display_name": "Oa Qdak", "id": "https://openalex.org/A9200000002"}, "raw_author_name": "Oa Qdak", "institutions": []}]}"""
  private val crLine =
    """{"DOI": "10.5555/Adv.Dup.1", "title": ["Doi Holder Cr"], "author": [{"given": "Cr", "family": "Qdal", "sequence": "first"}, {"given": "Cr", "family": "Qdam", "sequence": "additional"}], "issued": {"date-parts": [[2018]]}, "container-title": ["ADVC"], "type": "journal-article"}"""

  private def inputs(records: Seq[String]) =
    Inputs(utf8(doc(records*)), Some(oaLine + "\n" + oaLine + "\n"), Some(crLine + "\n"))

  private val d1 = Seq(same, confX, solo, same, confY, doiRec)
  private val d2 = Seq(confY, doiRec, same, solo, confX, same)

  private lazy val dupDir: Path = fresh("dup")
  private lazy val r1: Run = run(inputs(d1), dupDir, outName = "out1")
  private lazy val r1Replay: Run = run(inputs(d1), dupDir, outName = "out2")
  private lazy val r1OneReducer: Run =
    run(inputs(d1), dupDir, outName = "out3", extra = Seq("--reducers", "1"))
  private lazy val r2: Run = run(inputs(d2), fresh("dup-reordered"))

  private def work(r: Run, k: String) =
    r.works.find(_("sourceRecordId").str == k).getOrElse(fail(s"no work $k"))
  private def oaWorkId(r: Run) = r.normalized("OPENALEX", "works").map(_("workId").str).distinct
  private def crWorkId(r: Run) = r.normalized("CROSSREF", "works").map(_("workId").str).distinct

  /** Lines with the run ID replaced, for comparing runs whose manifests differ. */
  private def logical(r: Run, ls: Vector[String]): Vector[String] =
    ls.map(_.replace(r.runId, "RUN")).sorted

  test("DBLP duplicate keys: 4 works, one per key; 1 conflict; duplicates counted") {
    assertOk(r1)
    observe("D1 J01 counters", r1.counters("J01"))
    assertEquals(
      r1.works.map(_("sourceRecordId").str).sorted,
      Vector("d/conf", "d/doi", "d/same", "d/solo")
    )
    assertEquals(r1.counter("J01", "CONFLICT_RECORDS"), 1L)
    val dups = r1.counter("J01", "DUPLICATE_RECORDS")
    assert(Set(1L, 2L).contains(dups), s"DUPLICATE_RECORDS = $dups")
  }

  test("a conflicting duplicate keeps one whole input version, never a blend") {
    val w = work(r1, "d/conf")
    val got = (w("title").str, w("year").num.toInt, w("authors").arr.size)
    observe("kept d/conf version", got)
    assert(
      got == ("Conflict Version Alpha", 2021, 2) || got == ("Conflict Version Beta", 2022, 3),
      s"blended record: $got"
    )
  }

  test("the kept conflict version does not depend on input order") {
    assertOk(r2)
    val a = logical(r1, Vector(ujson.write(work(r1, "d/conf"))))
    val b = logical(r2, Vector(ujson.write(work(r2, "d/conf"))))
    assertEquals(b, a)
  }

  test("duplicates are consolidated before teams and pairs: 6 teams, pairs match kept versions") {
    val teams = r1.teams.groupBy(_("workId").str)
    observe("D1 team work IDs", teams.keys.toVector.sorted)
    assertEquals(teams.size, 6)
    teams.foreach((w, ts) => assertEquals(ts.size, 1, s"teams for $w"))
    val confN = work(r1, "d/conf")("authors").arr.size
    val per = r1.pairs.groupBy(_("workId").str).view.mapValues(_.size).toMap
    assertEquals(per.getOrElse("dblp:d/same", 0), 1)
    assertEquals(per.getOrElse("dblp:d/conf", 0), n2(confN))
    oaWorkId(r1).foreach(id => assertEquals(per.getOrElse(id, 0), 1, s"pairs of $id"))
    assertEquals(r1.pairs.size, 5 + n2(confN))
  }

  test("DOI case and resolver prefix normalize to one value across DBLP, OpenAlex and Crossref") {
    val dois =
      (Vector(work(r1, "d/doi")) ++ r1.normalized("OPENALEX", "works") ++
        r1.normalized("CROSSREF", "works")).map(w => str(w, "doi"))
    observe("DOIs", dois)
    assertEquals(dois.distinct, Vector(Some("10.5555/adv.dup.1")))
  }

  test("exact DOI key: 3 distinct candidates over the 3 holders, no self pair, no repeated row") {
    val holders = (Vector("dblp:d/doi") ++ oaWorkId(r1) ++ crWorkId(r1)).toSet
    assertEquals(holders.size, 3, holders)
    val cs = r1.exactCandidates
    observe(
      "exact candidates",
      cs.map(c => (c("candidateId").str, c("sourceId").str, c("targetId").str))
    )
    val pairs = cs.map(c => (c("sourceId").str, c("targetId").str))
    cs.foreach { c =>
      assert(c("candidateId").str.startsWith("mc:"), c("candidateId"))
      assert(c("sourceId").str < c("targetId").str, s"unordered or self pair $c")
    }
    assertEquals(cs.map(_("candidateId").str).distinct.size, cs.size, "repeated candidate rows")
    val want = for a <- holders.toVector.sorted; b <- holders.toVector.sorted if a < b yield (a, b)
    assertEquals(pairs.sorted, want)
  }

  test("exact identifiers: a DOI shared by 3 works, one of them given twice, is counted in J03") {
    // Spec job 3: "Emit exact-identifier keys and duplicate or conflict counters". The doi key
    // here has 3 distinct holders (a cross-source duplicate) and one holder repeated (an input
    // duplicate); under either reading of "duplicate" J03 has something to count.
    observe("J03 counters", r1.counters("J03"))
    observe(
      "DUPLICATE/CONFLICT per stage",
      r1.stageNames.map(s =>
        (s, r1.counter(s, "DUPLICATE_RECORDS"), r1.counter(s, "CONFLICT_RECORDS"))
      )
    )
    // MODULE.md "J03 DUPLICATE_RECORDS and CONFLICT_RECORDS": counts are per key, and "a repeated
    // OpenAlex work with a DOI and an OpenAlex ID counts once under doi: and once under openalex:".
    // That is this fixture's OpenAlex line given twice; no source holds two distinct works under
    // the DOI key (DBLP d/doi, W9200000001, one Crossref work), so DUPLICATE_RECORDS is exactly 2.
    val n = r1.counter("J03", "DUPLICATE_RECORDS") + r1.counter("J03", "CONFLICT_RECORDS")
    assert(n >= 1L, s"J03 DUPLICATE_RECORDS + CONFLICT_RECORDS = $n")
    assertEquals(r1.counter("J03", "DUPLICATE_RECORDS"), 2L, r1.counters("J03"))
  }

  test("graph export holds one work and one team per work ID: 6 and 6") {
    val ws = r1.exported("works").map(_("workId").str)
    val ts = r1.exported("teams").map(_("workId").str)
    assertEquals(ws.size, 6)
    assertEquals(ws.distinct.size, 6)
    assertEquals(ts.size, 6)
    assertEquals(ts.distinct.size, 6)
  }

  private val compared: Seq[(String, Option[String])] = Seq(
    "J01" -> None,
    "J03" -> None,
    "J05" -> Some("teams"),
    "J05" -> Some("authorpapers"),
    "J06" -> None,
    "J07" -> Some("works"),
    "J07" -> Some("pairs")
  )

  test("replay: the same input into a new output root gives the same run ID and the same lines") {
    assertOk(r1Replay)
    assertEquals(r1Replay.runId, r1.runId)
    compared.foreach { (st, sub) =>
      assertEquals(r1Replay.lines(st, sub).sorted, r1.lines(st, sub).sorted, s"$st $sub")
    }
  }

  test("rerun into the same output root succeeds and leaves the logical output unchanged") {
    val before = compared.map((st, sub) => r1.lines(st, sub).sorted)
    val again = run(inputs(d1), dupDir, outName = "out1")
    assertOk(again)
    assertEquals(again.runId, r1.runId)
    val after = compared.map((st, sub) => again.lines(st, sub).sorted)
    assertEquals(after, before)
  }

  test("partitioning: 1 reducer and the default 4 give the same logical output (run ID aside)") {
    assertOk(r1OneReducer)
    assertNotEquals(r1OneReducer.runId, r1.runId)
    compared.foreach { (st, sub) =>
      assertEquals(
        logical(r1OneReducer, r1OneReducer.lines(st, sub)),
        logical(r1, r1.lines(st, sub)),
        s"$st $sub"
      )
    }
  }
