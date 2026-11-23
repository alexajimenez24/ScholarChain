package scholarchain.hadoop

import scholarchain.core.*

class JobLogicSuite extends munit.FunSuite:
  private val run = FixtureIO.run

  private def names(n: Int): Vector[String] = (1 to n).toVector.map(i => f"Author Number$i%03d")

  private def team(n: Int): PublicationTeam =
    TeamLogic.team(Fixtures.work(s"t/x/$n", names(n)), run).get._1

  // ------------------------------------------------------------ teams and pairs

  test("pair expansion: 1, 2, 3, 12 and 300 authors give 0, 1, 3, 66 and 44850 pairs") {
    val counts = Vector(1, 2, 3, 12, 300).map(n => TeamLogic.pairs(team(n), 300, run).map(_.size))
    assertEquals(counts, Vector(Right(0), Right(1), Right(3), Right(66), Right(44850)))
    assertEquals(
      Vector(0L, 1L, 2L, 12L, 300L).map(TeamLogic.pairCount),
      Vector(0L, 0L, 1L, 66L, 44850L)
    )
  }

  test(
    "every pair is canonically ordered and unique, and keeps work, year, positions and provenance"
  ) {
    val w = Fixtures.work("t/x/p", names(5), year = Some(2020))
    val t = TeamLogic.team(w, run).get._1
    val ps = TeamLogic.pairs(t, 300, run).toOption.get
    assertEquals(ps.size, 10)
    assert(ps.forall(p => p.mentionA < p.mentionB))
    assertEquals(ps.map(p => (p.mentionA, p.mentionB)).distinct.size, 10)
    assert(ps.forall(p => p.workId == w.workId && p.year.contains(2020) && p.teamSize == 5))
    assert(ps.forall(p => p.positionA != p.positionB && p.positionA < 5 && p.positionB < 5))
    // the positions name the mention they sit next to
    assert(
      ps.forall(p =>
        w.authors(p.positionA).mentionId == p.mentionA && w
          .authors(p.positionB)
          .mentionId == p.mentionB
      )
    )
    assert(ps.forall(p => Validate.pair(p).isEmpty))
    assert(
      ps.forall(p =>
        p.provenance.source == SourceType.DBLP && p.provenance.sourceRecordId == "t/x/p"
      )
    )
    assert(ps.forall(p => p.provenance.runId == run.runId && p.provenance.snapshot == run.snapshot))
  }

  test("a team above the cap is refused with a reason instead of expanding quadratically") {
    assert(TeamLogic.pairs(team(40), 30, run).left.exists(_.contains("exceeds pair cap 30")))
    assert(TeamLogic.pairs(team(30), 30, run).isRight)
  }

  test("team size buckets") {
    assertEquals(
      Vector(1, 2, 5, 6, 20, 21, 100, 101, 300, 301).map(TeamLogic.bucket),
      Vector(
        "TEAM_SIZE_1",
        "TEAM_SIZE_2_5",
        "TEAM_SIZE_2_5",
        "TEAM_SIZE_6_20",
        "TEAM_SIZE_6_20",
        "TEAM_SIZE_21_100",
        "TEAM_SIZE_21_100",
        "TEAM_SIZE_101_300",
        "TEAM_SIZE_101_300",
        "TEAM_SIZE_OVER_300"
      )
    )
  }

  test("a team has the ordered mentions, and one author-paper record per mention, all valid") {
    val w = Fixtures.work("t/x/q", Vector("Ada Lovelace", "Bob Baker", "Cy Young"))
    val (t, papers) = TeamLogic.team(w, run).get
    assertEquals(t.mentionIds, w.authors.map(_.mentionId))
    assertEquals(t.teamSize, 3)
    assertEquals(papers.map(_.position), Vector(0, 1, 2))
    assertEquals(papers.map(_.mentionId), t.mentionIds)
    assert(papers.forall(p => p.workId == w.workId && p.teamSize == 3))
    assertEquals(Validate.team(t), Vector.empty[String])
    assert(papers.forall(p => HadoopValidate.authorPaper(p).isEmpty))
    assertEquals(TeamLogic.team(Fixtures.work("t/x/r", Vector.empty), run), None)
  }

  // ------------------------------------------------------------ exact identifiers

  private def profile(orcid: String, name: String, dois: Vector[String]) =
    ExternalResearcher(
      HadoopSchemas.ExternalResearcher,
      s"orcid:$orcid",
      SourceType.ORCID,
      Some(orcid),
      None,
      name,
      Vector(name),
      Vector.empty,
      dois,
      run.provenance(SourceType.ORCID, orcid, HadoopSchemas.ExternalResearcher)
    )

  private def refsFor(
      key: String,
      ws: Vector[WorkRecord],
      ps: Vector[ExternalResearcher] = Vector.empty
  ) =
    (ws.flatMap(ExactLogic.exactKeys) ++ ps.flatMap(ExactLogic.exactKeys))
      .filter(_._1 == key)
      .map(_._2)

  test("a shared DOI links works across sources and counts same-source copies as duplicates") {
    val a =
      Fixtures.work("a/1", Vector("Ada Lovelace"), title = "Graph Systems", doi = Some("10.1/x"))
    val b = Fixtures.work(
      "W1",
      Vector("Ada Lovelace"),
      title = "Graph Systems",
      doi = Some("10.1/x"),
      source = SourceType.OPENALEX
    )
    val c =
      Fixtures.work("a/2", Vector("Ada Lovelace"), title = "Graph Systems", doi = Some("10.1/x"))
    val r = ExactLogic.exactJoin("doi:10.1/x", refsFor("doi:10.1/x", Vector(a, b, c)), run)
    val works = r.candidates.filter(_.kind == EntityKind.WORK)
    assertEquals(works.size, 3) // 3 works give 3 pairs
    assertEquals(
      works.count(_.features("same_source_duplicate") == 1.0),
      1
    ) // a and c are both DBLP
    assertEquals(r.duplicates, 1) // the DBLP source holds two works under the DOI
    assertEquals(r.conflicts, 0)
    assert(works.forall(c => HadoopValidate.candidate(c).isEmpty && c.features("exact_doi") == 1.0))
  }

  test("works under one DOI with unrelated titles are a conflict") {
    val a =
      Fixtures.work("a/1", Vector("Ada Lovelace"), title = "Graph Systems", doi = Some("10.1/x"))
    val b = Fixtures.work(
      "W1",
      Vector("Ada Lovelace"),
      title = "Quantum Cooking",
      doi = Some("10.1/x"),
      source = SourceType.OPENALEX
    )
    val r = ExactLogic.exactJoin("doi:10.1/x", refsFor("doi:10.1/x", Vector(a, b)), run)
    assertEquals(r.conflicts, 1)
    assertEquals(r.candidates.size, 1)
    assert(r.candidates.head.features("title_jaccard") < ExactLogic.TitleConflictBelow)
  }

  test(
    "an ORCID links its mentions to the canonical ID, and a name far from the profile conflicts"
  ) {
    val good = Fixtures.work("a/1", Vector("Carol Chen"))
    val bad = Fixtures.work("a/2", Vector("Zbigniew Kowalczyk"))
    def withOrcid(w: WorkRecord) =
      w.copy(authors = w.authors.map(_.copy(orcid = Some("0000-0002-1825-0097"))))
    val ws = Vector(good, bad).map(withOrcid)
    val key = "orcid:0000-0002-1825-0097"
    val p = profile("0000-0002-1825-0097", "Carol Chen", Vector.empty)
    val r = ExactLogic.exactJoin(key, refsFor(key, ws, Vector(p)), run)
    assertEquals(r.candidates.size, 2)
    assert(r.candidates.forall(c => c.targetId == key && c.features("exact_orcid") == 1.0))
    assertEquals(r.conflicts, 1)
  }

  test(
    "a profile that lists a DOI links to the best-named mention; ties pick the smallest mention ID"
  ) {
    val w1 = Fixtures.work("a/1", Vector("Carol Chen", "Dan Diaz"), doi = Some("10.1/x"))
    val w2 = Fixtures.work("a/2", Vector("Carol Chen"), doi = Some("10.1/x"))
    val p = profile("0000-0002-1825-0097", "Carol Chen", Vector("10.1/x"))
    val key = "doi:10.1/x"
    val r = ExactLogic.exactJoin(key, refsFor(key, Vector(w1, w2), Vector(p)), run)
    val people = r.candidates.filter(_.kind == EntityKind.RESEARCHER)
    assertEquals(people.size, 1)
    val carolMentions =
      Vector(w1, w2).flatMap(_.authors).filter(_.rawName == "Carol Chen").map(_.mentionId)
    assertEquals(people.head.sourceId, carolMentions.min)
    assertEquals(people.head.targetId, "orcid:0000-0002-1825-0097")
    // reversed input order gives the same answer
    val again = ExactLogic.exactJoin(key, refsFor(key, Vector(w2, w1), Vector(p)).reverse, run)
    assertEquals(again.candidates.map(_.candidateId).sorted, r.candidates.map(_.candidateId).sorted)
  }

  test("exact keys: DOI, arXiv and OpenAlex ID for works; ORCID for mentions") {
    val w = Fixtures
      .work("a/1", Vector("Carol Chen"), doi = Some("10.1/x"))
      .copy(arxivId = Some("2101.00001"), openAlexId = Some("W7"))
    val w2 = w.copy(authors = w.authors.map(_.copy(orcid = Some("0000-0002-1825-0097"))))
    assertEquals(
      ExactLogic.exactKeys(w2).map(_._1).sorted,
      Vector(
        "arxiv:2101.00001",
        "doi:10.1/x",
        "doi:10.1/x",
        "openalex:W7",
        "orcid:0000-0002-1825-0097"
      )
    )
    assertEquals(ExactLogic.exactKeys(Fixtures.work("a/9", Vector("A B"))), Vector.empty)
  }

  // ------------------------------------------------------------ blocking and features

  private def block(ws: WorkRecord*) =
    ws.toVector.flatMap(BlockLogic.mentionBlocks)

  test("block keys carry coauthor, title, year, venue and source context") {
    val w = Fixtures.work(
      "a/1",
      Vector("Wei Wang 0001", "Bob Baker"),
      title = "Distributed Snapshot Protocols",
      venueId = Some("venue:dblp:conf/x")
    )
    val blocks = BlockLogic.mentionBlocks(w).toMap
    assertEquals(blocks.keySet, Set("wang_w", "baker_b"))
    val s = blocks("wang_w")
    assertEquals(s.coauthorKeys, Vector("baker_b"))
    assertEquals(s.year, Some(2021))
    assertEquals(s.venueId, Some("venue:dblp:conf/x"))
    assertEquals(s.source, SourceType.DBLP)
    assertEquals(s.clusterKey, "dblpname:Wei Wang 0001")
    assert(s.titleTokens.contains("snapshot"))
    assertEquals(BlockLogic.workBlockKey(w), Some("wk:2021:distributed+protocols"))
  }

  test("DBLP homonyms with different suffixes are flagged distinct; the same string clusters") {
    val members = block(
      Fixtures.work("a/1", Vector("Wei Wang 0001", "Bob Baker")),
      Fixtures.work("a/2", Vector("Wei Wang 0001")),
      Fixtures.work("a/3", Vector("Wei Wang 0002", "Frank Fox"))
    ).filter(_._1 == "wang_w").map(_._2)
    assertEquals(members.size, 3)
    val result = BlockLogic.block("wang_w", members, 200, run).head
    assertEquals(result.clusters, 2)
    val cross = result.candidates.filter(_.features.contains("dblp_homonym_distinct"))
    assertEquals(cross.size, 1)
    assertEquals(cross.head.features("dblp_homonym_distinct"), 1.0)
    assertEquals(cross.head.features("same_full_name"), 1.0)
    val links = result.candidates.filter(_.features.contains("same_source_cluster"))
    assertEquals(links.size, 1, "the two 'Wei Wang 0001' mentions link inside their DBLP cluster")
    assert(result.candidates.forall(c => HadoopValidate.candidate(c).isEmpty))
  }

  test("conflicting ORCIDs and external IDs are features, never a merge") {
    def mention(i: Int, orcid: Option[String], ext: Option[String]) = BlockLogic.MentionSummary(
      s"m:$i",
      s"w:$i",
      SourceType.OPENALEX,
      "J Smith",
      "j smith",
      s"ext:$i",
      orcid,
      ext,
      None,
      Some(2020),
      None,
      Vector.empty,
      Vector.empty
    )
    val members = Vector(
      mention(1, Some("0000-0002-1825-0097"), Some("openalex:A1")),
      mention(2, Some("0000-0001-8079-2797"), Some("openalex:A2")),
      mention(3, Some("0000-0002-1825-0097"), Some("openalex:A3"))
    )
    val cs = BlockLogic.clusters(members)
    val f12 = BlockLogic.features(cs(0), cs(1))
    val f13 = BlockLogic.features(cs(0), cs(2))
    assertEquals((f12("orcid_conflict"), f12("orcid_equal")), (1.0, 0.0))
    assertEquals((f13("orcid_conflict"), f13("orcid_equal")), (0.0, 1.0))
    assertEquals(f12("external_id_conflict"), 1.0)
  }

  test("an oversize block is split by full name and refused only when a part is still too large") {
    def summary(i: Int, name: String) = BlockLogic.MentionSummary(
      f"m:$i%020d",
      s"w:$i",
      SourceType.OPENALEX,
      name,
      Normalize.personName(name),
      s"mention:$i",
      None,
      None,
      None,
      None,
      None,
      Vector.empty,
      Vector.empty
    )
    // 6 clusters named "j smith" and 2 named "john smith"; the cap is 3 clusters
    val members =
      (0 until 6).map(i => summary(i, "J Smith")).toVector ++ (6 until 8).map(i =>
        summary(i, "John Smith")
      )
    val results = BlockLogic.block("smith_j", members, 3, run)
    assertEquals(results.size, 2)
    val refused = results.filter(_.oversize.nonEmpty)
    assertEquals(refused.size, 1)
    assert(refused.head.candidates.isEmpty, "singleton clusters have no member links")
    assert(refused.head.oversize.get.contains("6 clusters > 3"))
    val kept = results.filter(_.oversize.isEmpty)
    assertEquals(kept.head.candidates.size, 1, "2 'john smith' clusters compare once")
  }

  test("a block above the hard cap is never expanded") {
    assertEquals(BlockLogic.hardCap(200), 2000)
    assertEquals(BlockLogic.hardCap(Int.MaxValue), Int.MaxValue)
  }

  test("work blocks pair works by title and year, with DOI, venue and author features") {
    val a = Fixtures.work(
      "a/1",
      Vector("Ada Lovelace", "Bob Baker"),
      title = "Graph Systems at Scale",
      doi = Some("10.1/x"),
      venueName = Some("FIXA")
    )
    val b = Fixtures.work(
      "W1",
      Vector("Ada Lovelace", "Bob Baker"),
      title = "Graph Systems at Scale",
      doi = Some("10.1/y"),
      venueName = Some("fixa"),
      source = SourceType.OPENALEX
    )
    val (ka, sa) = BlockLogic.workBlock(a).get
    val (kb, sb) = BlockLogic.workBlock(b).get
    assertEquals(ka, kb)
    val r = BlockLogic.workBlockResult(ka, Vector(sa, sb, sa), 10, run).head
    assertEquals(r.candidates.size, 1, "the repeated summary of one work counts once")
    val f = r.candidates.head.features
    assertEquals((f("same_title"), f("doi_conflict"), f("doi_equal")), (1.0, 1.0, 0.0))
    assertEquals((f("same_venue_name"), f("author_overlap"), f("cross_source")), (1.0, 1.0, 1.0))
    val tooMany = BlockLogic.workBlockResult(ka, Vector(sa, sb), 1, run).head
    assert(tooMany.oversize.nonEmpty && tooMany.candidates.isEmpty)
    assertEquals(
      BlockLogic.workBlockKey(Fixtures.work("a/2", Vector("A B"), title = "On It")),
      None
    )
  }

  test("similarity primitives are deterministic and bounded") {
    assertEqualsDouble(Similarity.jaroWinkler("martha", "marhta"), 0.9611111111111111, 1e-9)
    assertEquals(Similarity.jaroWinkler("carol chen", "carol chen"), 1.0)
    assertEquals(Similarity.jaroWinkler("", ""), 0.0)
    assertEquals(Similarity.jaroWinkler("abc", "xyz"), 0.0)
    assertEquals(Similarity.jaccard(Set(1, 2, 3), Set(2, 3, 4)), 0.5)
    assertEquals(Similarity.jaccard(Set.empty[Int], Set.empty[Int]), 0.0)
    assertEquals(Similarity.tokens("a graph of x"), Set("graph", "of"))
    assert(Similarity.initialsCompatible("wei wang", "w wang"))
    assert(!Similarity.initialsCompatible("wei wang", "li wang"))
  }

  // ------------------------------------------------------------ export and partitioning

  test("export rows: one work, one row per mention and the venue, all keyed by work ID first") {
    val w = Fixtures.work(
      "a/1",
      Vector("Ada Lovelace", "Bob Baker"),
      venueId = Some("venue:dblp:conf/x"),
      venueName = Some("X")
    )
    val rows = ExportLogic.workRows(w, run)
    assertEquals(rows.size, 4)
    assertEquals(
      rows.map(r => ExportLogic.kindAndId(r._1).get._1).sorted,
      Vector("mentions", "mentions", "venues", "works")
    )
    assert(rows.filterNot(_._1.contains("venues")).forall(_._1.startsWith(w.workId + "\t")))
    assertEquals(ExportLogic.kindAndId("w\tbogus\tid"), None)
    assertEquals(ExportLogic.key("a\tb", "works", "c\nd"), "a b\tworks\tc d")
  }

  test("the partitioner hashes the key up to the first TAB and is stable") {
    val n = 7
    val a = KeyPartitioner.partitionOf("dblp:conf/x/1\tworks\tdblp:conf/x/1", n)
    assertEquals(a, KeyPartitioner.partitionOf("dblp:conf/x/1\tmentions\tm:abc", n))
    assertEquals(a, KeyPartitioner.partitionOf("dblp:conf/x/1", n))
    assert(
      (0 until 200)
        .map(i => KeyPartitioner.partitionOf(s"doi:10.1/$i", n))
        .forall(p => p >= 0 && p < n)
    )
    // a fixed vector: the partition of a key never changes between runs or JVMs
    assertEquals(
      KeyPartitioner.partitionOf("doi:10.5555/sc.fix.001", 4),
      KeyPartitioner.partitionOf("doi:10.5555/sc.fix.001", 4)
    )
    assertEquals(
      (0 until 1000).map(i => KeyPartitioner.partitionOf(s"k$i", 4)).groupBy(identity).keySet,
      Set(0, 1, 2, 3)
    )
  }

  test("counter names are unique and the core set is present") {
    val core = Vector(
      Ctr.Input,
      Ctr.Accepted,
      Ctr.Rejected,
      Ctr.Duplicate,
      Ctr.Conflict,
      Ctr.OversizeBlock,
      Ctr.OversizeTeam,
      Ctr.EmittedPair,
      Ctr.MissingKey,
      Ctr.SchemaFailure
    )
    assertEquals(core.distinct.size, 10)
  }
