package scholarchain.hadoop

import scholarchain.core.*
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Runs J01 to J07 with Hadoop's LocalJobRunner on the repository samples
  * (datasets/samples/fixtures). Every expected number is derived by hand from those files.
  *
  * Hand counts, from the fixture files:
  *   - dblp-fixture.xml: 17 top-level records = 15 works (11 inproceedings, 4 articles) + 1 www + 1
  *     proceedings. Authors per work: 2,3,2,3,2,3,2,3,2,3,2,2,2,1,12 = 44. Pairs n(n-1)/2:
  *     1,3,1,3,1,3,1,3,1,3,1,1,1,0,66 = 89.
  *   - openalex-works-fixture.jsonl: 12 works, authors 2,3,2,3,2,3,2,3,2,3,2,2 = 29, pairs
  *     1,3,1,3,1,3,1,3,1,3,1,1 = 22; 12 topic rows (2 distinct), 5 institution rows (1 distinct).
  *   - crossref-works-fixture.jsonl: 8 works, authors 2,3,2,3,2,3,2,3 = 20, pairs 16.
  *   - orcid-records-fixture.jsonl: 2 profiles.
  *   - Totals: 35 works, 93 authorships (44+29+20), 127 pairs (89+22+16), largest team 12 (66
  *     pairs).
  */
class PipelineIntegrationSuite extends munit.FunSuite:
  private val h = new Harness("it")
  override def afterAll(): Unit = h.delete()

  private def inputs(): Vector[String] = Vector(
    "--dblp",
    h.fixture("dblp-fixture.xml").toString,
    "--openalex",
    h.fixture("openalex-works-fixture.jsonl").toString,
    "--crossref",
    h.fixture("crossref-works-fixture.jsonl").toString,
    "--orcid",
    h.fixture("orcid-records-fixture.jsonl").toString,
    "--snapshot",
    FixtureIO.snapshot,
    "--reducers",
    "2"
  )
  private lazy val in = inputs()
  private def argsFor(out: String): Vector[String] = in ++ Vector("--output", out)

  private lazy val a = h.run(argsFor(h.dir.resolve("out-a").toString))
  private def p = a.paths
  private def c(stage: String) = a.counters(stage)

  test("the full chain succeeds and every stage path holds the snapshot and the run ID") {
    assertEquals(a.code, 0)
    assert(Ids.isRunId(p.runId))
    val stages = Vector(
      p.dblpWorks,
      p.normalized(SourceType.OPENALEX),
      p.normalized(SourceType.CROSSREF),
      p.normalized(SourceType.ORCID),
      p.exactCandidates,
      p.blockedCandidates,
      p.teams,
      p.pairs,
      p.graphExport
    )
    stages.foreach { s =>
      assert(s.contains(s"snapshot=${FixtureIO.snapshot}/run_id=${p.runId}"), s)
      assert(Files.exists(Path.of(s, "_SUCCESS")), s"$s has no _SUCCESS")
    }
    assert(Files.readString(Path.of(p.manifest)).contains(Pipeline.LogicVersion))
  }

  test("J01: 17 DBLP records in, 15 works retained, 2 skipped, none rejected") {
    assertEquals(
      c(p.dblpWorks),
      Map(
        Ctr.Input -> 17L,
        Ctr.Accepted -> 15L,
        Ctr.Skipped -> 2L,
        "UNIQUE_RECORDS" -> 15L,
        "DBLP_TYPE_INPROCEEDINGS" -> 11L,
        "DBLP_TYPE_ARTICLE" -> 4L,
        "DBLP_SKIPPED_WWW" -> 1L,
        "DBLP_SKIPPED_PROCEEDINGS" -> 1L
      )
    )
    val works = a.decode[WorkRecord](p.dblpWorks)
    assertEquals(works.size, 15)
    assertEquals(works.map(_.authors.size).sum, 44)
    assert(works.forall(w => Validate.work(w).isEmpty))
    assert(
      works.forall(w =>
        w.provenance.runId == p.runId && w.provenance.snapshot == FixtureIO.snapshot
      )
    )
    assertEquals(a.rejects(p.dblpWorks), Vector.empty)
  }

  test("J02: one normalizer per source keeps works, topics, institutions and profiles") {
    val oa = c(p.normalized(SourceType.OPENALEX))
    assertEquals(
      oa,
      Map(
        Ctr.Input -> 12L,
        Ctr.Accepted -> 12L,
        "WORKS_WRITTEN" -> 12L,
        "TOPICS_WRITTEN" -> 12L,
        "INSTITUTIONS_WRITTEN" -> 5L
      )
    )
    assertEquals(
      c(p.normalized(SourceType.CROSSREF)),
      Map(Ctr.Input -> 8L, Ctr.Accepted -> 8L, "WORKS_WRITTEN" -> 8L)
    )
    assertEquals(
      c(p.normalized(SourceType.ORCID)),
      Map(Ctr.Input -> 2L, Ctr.Accepted -> 2L, "RESEARCHERS_WRITTEN" -> 2L)
    )
    assertEquals(a.decode[WorkRecord](p.normalized(SourceType.OPENALEX), "works").size, 12)
    assertEquals(a.decode[WorkRecord](p.normalized(SourceType.CROSSREF), "works").size, 8)
    assertEquals(
      a.decode[ExternalResearcher](p.normalized(SourceType.ORCID), "researchers").size,
      2
    )
  }

  test("J03: 51 exact-identifier candidates, no conflicts, no same-source duplicates") {
    // Work pairs: 8 DOIs (001-006, 101, 102) held by DBLP, OpenAlex and Crossref give 3 pairs each
    // (24); 4 DOIs (103, 104, 201, 202) held by DBLP and OpenAlex give 1 each (4): 28.
    // ORCID links: iD ...0018 appears on 4 DBLP + 4 OpenAlex + 3 Crossref mentions (11), iD ...0026
    // on 3 + 3 + 1 (7): 18. Profile DOI links: Carol Chen lists 002, 003, 201 and Eve Evans lists
    // 101, 103, each linked to one mention: 5. Total 28 + 18 + 5 = 51.
    val k = c(p.exactCandidates)
    assertEquals(k, Map(Ctr.Input -> 37L, Ctr.Accepted -> 37L, Ctr.ExactCandidates -> 51L))
    val cands = a.decode[MatchCandidate](p.exactCandidates)
    assertEquals(cands.size, 51)
    assertEquals(cands.count(_.kind == EntityKind.WORK), 28)
    assertEquals(
      cands.count(c => c.kind == EntityKind.RESEARCHER && c.blockKey.startsWith("orcid:")),
      18
    )
    assertEquals(
      cands.count(c => c.kind == EntityKind.RESEARCHER && c.blockKey.startsWith("doi:")),
      5
    )
    assertEquals(cands.count(_.features.get("exact_doi").contains(1.0)), 28)
    assert(cands.forall(c => HadoopValidate.candidate(c).isEmpty))
    assert(cands.exists(_.targetId == "orcid:0000-0009-9000-0018"))
    // the 5 profile-DOI links name the same mention and ORCID as an ORCID-key link, so they share
    // candidate IDs with them (consumers merge candidates by ID): 51 - 5 = 46 distinct
    assertEquals(cands.map(_.candidateId).distinct.size, 46)
  }

  test("J04: 142 blocked candidates, largest blocking group 13 mentions, no oversize block") {
    // Researcher blocks (clusters = DBLP names + OpenAlex author IDs + each Crossref mention):
    //   archer_a 13 mentions (5 DBLP + 4 OpenAlex + 4 Crossref): 6 clusters -> 15 pairs + 7 links = 22
    //   baker_b  13 mentions, the same shape: 22
    //   chen_c   11 mentions: 5 clusters -> 10 pairs + 6 links = 16
    //   diaz_d    9 mentions: 5 clusters -> 10 + 4 = 14
    //   wang_w    5 mentions: 5 clusters -> 10 + 0 = 10 (two DBLP homonyms, two OpenAlex IDs)
    //   evans_e   7 mentions: 3 clusters -> 3 + 4 = 7
    //   fox_f     8 mentions: 4 clusters -> 6 + 4 = 10
    //   gupta_g   5 mentions: 3 clusters -> 3 + 2 = 5
    //   hill_h    5 mentions: 3 clusters -> 3 + 2 = 5
    //   ito_i     4 mentions: 2 clusters -> 1 + 2 = 3
    //   zhou, yu, sato and the ten "Team Member NN" authors sit alone: 0
    // Researcher total 114. Work blocks: the 28 same-DOI work pairs share title and year: 28.
    // 114 + 28 = 142.
    val k = c(p.blockedCandidates)
    assertEquals(k, Map(Ctr.Input -> 35L, Ctr.Accepted -> 35L, Ctr.BlockedCandidates -> 142L))
    val cands = a.decode[MatchCandidate](p.blockedCandidates)
    assertEquals(cands.size, 142)
    assertEquals(cands.count(_.kind == EntityKind.RESEARCHER), 114)
    assertEquals(cands.count(_.kind == EntityKind.WORK), 28)
    val perBlock =
      cands.filter(_.kind == EntityKind.RESEARCHER).groupBy(_.blockKey).view.mapValues(_.size).toMap
    assertEquals(
      perBlock,
      Map(
        "archer_a" -> 22,
        "baker_b" -> 22,
        "chen_c" -> 16,
        "diaz_d" -> 14,
        "wang_w" -> 10,
        "evans_e" -> 7,
        "fox_f" -> 10,
        "gupta_g" -> 5,
        "hill_h" -> 5,
        "ito_i" -> 3
      )
    )
    assertEquals(cands.count(_.features.get("dblp_homonym_distinct").contains(1.0)), 1)
    // the two OpenAlex "Wei Wang" author IDs (A...947 and A...948) are the only conflicting pair
    assertEquals(cands.count(_.features.get("external_id_conflict").contains(1.0)), 1)
    assert(cands.forall(c => HadoopValidate.candidate(c).isEmpty))
    assertEquals(a.rejects(p.blockedCandidates), Vector.empty)
    val largest = a
      .lines(p.blockedCandidates, "stats")
      .map(ujson.read(_))
      .map(s => (s("members").num.toInt, s("key").str))
      .max
    assertEquals(largest._1, 13)
  }

  test("J05: 35 teams, 93 author-paper records, 127 pairs before any cap, largest team 12") {
    assertEquals(
      c(p.teams),
      Map(
        Ctr.Input -> 35L,
        Ctr.Accepted -> 35L,
        "TEAMS_WRITTEN" -> 35L,
        "AUTHOR_PAPERS_WRITTEN" -> 93L,
        Ctr.PairsBeforeCap -> 127L,
        "TEAM_SIZE_1" -> 1L,
        "TEAM_SIZE_2_5" -> 33L,
        "TEAM_SIZE_6_20" -> 1L
      )
    )
    val teams = a.decode[PublicationTeam](p.teams, "teams")
    assertEquals(teams.size, 35)
    assertEquals(teams.map(_.teamSize).max, 12)
    assertEquals(teams.map(_.teamSize).sum, 93)
    assert(teams.forall(t => Validate.team(t).isEmpty))
    val papers = a.decode[AuthorPaper](p.teams, "authorpapers")
    assertEquals(papers.size, 93)
    assertEquals(papers.map(x => (x.mentionId, x.workId)).distinct.size, 93)
  }

  test("J06: 127 pair candidates, each valid, 89 + 22 + 16 by source") {
    assertEquals(
      c(p.pairs),
      Map(Ctr.Input -> 35L, Ctr.Accepted -> 35L, Ctr.EmittedPair -> 127L, "UNIQUE_RECORDS" -> 127L)
    )
    val pairs = a.decode[CoauthorPairCandidate](p.pairs)
    assertEquals(pairs.size, 127)
    assert(pairs.forall(x => Validate.pair(x).isEmpty))
    assertEquals(
      pairs.groupBy(_.provenance.source).view.mapValues(_.size).toMap,
      Map(SourceType.DBLP -> 89, SourceType.OPENALEX -> 22, SourceType.CROSSREF -> 16)
    )
    assertEquals(pairs.map(_.teamSize).max, 12)
    assertEquals(pairs.count(_.teamSize == 12), 66)
  }

  test(
    "J07: partitioned export of works, mentions, teams, pairs, author papers, topics, institutions and venues"
  ) {
    // inputs: 35 works + 35 teams + 127 pairs + 93 author papers + 12 topic rows + 5 institution rows
    // = 307. Distinct outputs: 35, 93 mentions, 35, 127, 93, 2 topics, 1 institution, 11 venues
    // (6 DBLP streams + 5 OpenAlex sources). Duplicates: topics 12-2, institutions 5-1, venue rows
    // 27-11 = 10 + 4 + 16 = 30, all byte-identical (no conflict).
    val k = c(p.graphExport)
    assertEquals(
      k,
      Map(
        Ctr.Input -> 307L,
        Ctr.Accepted -> 307L,
        Ctr.Duplicate -> 30L,
        "EXPORTED_WORKS" -> 35L,
        "EXPORTED_MENTIONS" -> 93L,
        "EXPORTED_TEAMS" -> 35L,
        "EXPORTED_PAIRS" -> 127L,
        "EXPORTED_AUTHORPAPERS" -> 93L,
        "EXPORTED_TOPICS" -> 2L,
        "EXPORTED_INSTITUTIONS" -> 1L,
        "EXPORTED_VENUES" -> 11L
      )
    )
    val sizes = ExportLogic.Kinds.map(kind => kind -> a.lines(p.graphExport, kind).size).toMap
    assertEquals(
      sizes,
      Map(
        "works" -> 35,
        "mentions" -> 93,
        "teams" -> 35,
        "pairs" -> 127,
        "authorpapers" -> 93,
        "topics" -> 2,
        "institutions" -> 1,
        "venues" -> 11
      )
    )
    assert(
      a.decode[AuthorMention](p.graphExport, "mentions").forall(m => Validate.mention(m).isEmpty)
    )
    assert(a.decode[Venue](p.graphExport, "venues").forall(v => Validate.venue(v).isEmpty))
    assert(a.decode[Topic](p.graphExport, "topics").forall(t => Validate.topic(t).isEmpty))
  }

  test("partitioning: with 2 reducers everything of one work lands in the same partition number") {
    for kind <- Vector("works", "mentions", "teams", "pairs", "authorpapers") do
      for part <- 0 until 2 do
        val lines = FsText.lines(a.conf, f"${p.graphExport}/$kind/part-r-$part%05d*")
        val workIds = kind match
          case "works"    => lines.map(l => JsonLines.decode[WorkRecord](l).toOption.get.workId)
          case "mentions" => lines.map(l => JsonLines.decode[AuthorMention](l).toOption.get.workId)
          case "teams" => lines.map(l => JsonLines.decode[PublicationTeam](l).toOption.get.workId)
          case "pairs" =>
            lines.map(l => JsonLines.decode[CoauthorPairCandidate](l).toOption.get.workId)
          case _ => lines.map(l => JsonLines.decode[AuthorPaper](l).toOption.get.workId)
        assert(workIds.forall(w => KeyPartitioner.partitionOf(w, 2) == part), s"$kind part $part")
    val perPart =
      (0 until 2).map(i => FsText.lines(a.conf, f"${p.graphExport}/works/part-r-$i%05d*").size)
    assertEquals(perPart.sum, 35)
    assert(perPart.forall(_ > 0), s"both partitions hold works: $perPart")
  }

  test("intermediate (map output) compression is configured on for every job") {
    val c = new org.apache.hadoop.conf.Configuration(false)
    Pipeline.configure(c, "run-x", a.parsed)
    assert(c.getBoolean("mapreduce.map.output.compress", false))
    assertEquals(
      c.get("mapreduce.map.output.compress.codec"),
      classOf[org.apache.hadoop.io.compress.DefaultCodec].getName
    )
    assertEquals(c.get(Keys.RunId), "run-x")
  }

  test("final outputs are compressed") {
    val gz = Files
      .walk(Path.of(p.base))
      .iterator
      .asScala
      .filter(f => f.getFileName.toString.startsWith("part-") && !f.toString.contains("_local-tmp"))
      .toVector
    assert(
      gz.nonEmpty && gz.forall(_.toString.endsWith(".gz")),
      gz.filterNot(_.toString.endsWith(".gz")).toString
    )
  }

  test("the run summary reports the maxima Hadoop counters cannot hold") {
    val s = a.summary
    assertEquals(s("runId").str, p.runId)
    assertEquals(s("maxTeamObserved")("teamSize").num.toInt, 12)
    assertEquals(s("maxTeamObserved")("workId").str, "dblp:conf/fixa/ArcherTeam22")
    assertEquals(s("largestPairExpansion").num.toLong, 66L)
    assertEquals(s("largestBlockObserved")("members").num.toInt, 13)
    assertEquals(s("largestBlockObserved")("key").str, "r:archer_a")
  }

  test("a rerun of the same run skips every finished stage and leaves the outputs untouched") {
    assertEquals(a.code, 0)
    val file = Path.of(p.pairs, "part-r-00000.gz")
    val before =
      (Files.getLastModifiedTime(file), Files.readAllBytes(file).toVector, a.lines(p.pairs))
    val again = h.run(argsFor(h.dir.resolve("out-a").toString))
    assertEquals(again.code, 0)
    assertEquals(
      (Files.getLastModifiedTime(file), Files.readAllBytes(file).toVector, again.lines(p.pairs)),
      before
    )
  }

  test("a partial output of a run is removed and rebuilt, not mixed with the new output") {
    assertEquals(a.code, 0)
    val before = a.lines(p.pairs).sorted
    Files.delete(Path.of(p.pairs, "_SUCCESS"))
    Files.deleteIfExists(Path.of(p.pairs, "part-r-00001.gz"))
    Files.writeString(Path.of(p.pairs, "stray-leftover.txt"), "partial")
    val again = h.run(argsFor(h.dir.resolve("out-a").toString))
    assertEquals(again.code, 0)
    assertEquals(again.lines(p.pairs).sorted, before)
    assert(!Files.exists(Path.of(p.pairs, "stray-leftover.txt")))
  }

  test("same inputs, same run ID, byte-identical outputs in a different output folder") {
    assertEquals(a.code, 0)
    val b = h.run(argsFor(h.dir.resolve("out-b").toString))
    assertEquals(b.code, 0)
    assertEquals(b.paths.runId, p.runId)
    def snapshot(r: h.Result): Map[String, Vector[String]] =
      val base = Path.of(r.paths.base)
      Files
        .walk(base)
        .iterator
        .asScala
        .filter(Files.isRegularFile(_))
        .filter(f => !f.toString.contains("_local-tmp") && !f.getFileName.toString.endsWith(".crc"))
        .filter(f =>
          f.getFileName.toString.startsWith("part-") || f.getFileName.toString == "_counters.json"
        )
        .map(f => base.relativize(f).toString -> FsText.lines(r.conf, f.toString))
        .toMap
    val (sa, sb) = (snapshot(a), snapshot(b))
    assert(sa.size > 40, s"expected many part files, got ${sa.size}")
    assertEquals(sb.keySet, sa.keySet)
    sa.foreach((k, v) => assertEquals(sb(k), v, k))
  }

  test("a different snapshot is a different run and a different folder") {
    val args = in.updated(in.indexOf("--snapshot") + 1, "2026-11-23") ++ Vector(
      "--output",
      h.dir.resolve("out-a").toString
    )
    val other = PipelineArgs.parse(args).toOption.get
    val otherPaths = PipelinePaths(other.output, other.snapshot, Ids.run(other.manifest))
    assertNotEquals(otherPaths.runId, p.runId)
    assertNotEquals(otherPaths.pairs, p.pairs)
  }

  test("split-independence: many small input splits give the same J01 result as one split") {
    val bigRun = a
    val small = h.run(
      argsFor(h.dir.resolve("out-split").toString),
      Map("mapreduce.input.fileinputformat.split.maxsize" -> "700")
    )
    assertEquals(small.code, 0)
    val job = Job.getInstance(small.conf)
    FileInputFormat.addInputPath(job, new org.apache.hadoop.fs.Path(in(in.indexOf("--dblp") + 1)))
    FileInputFormat.setMaxInputSplitSize(job, 700)
    val splits = new DblpInputFormat().getSplits(job).size
    assertEquals(splits, 8, "5475 bytes in 700-byte splits")
    assertEquals(small.counters(small.paths.dblpWorks), bigRun.counters(bigRun.paths.dblpWorks))
    assertEquals(
      small.lines(small.paths.dblpWorks).sorted,
      bigRun.lines(bigRun.paths.dblpWorks).sorted
    )
    assertEquals(small.lines(small.paths.pairs).sorted, bigRun.lines(bigRun.paths.pairs).sorted)
  }

  test("the usage is checked: missing or invalid arguments fail with exit code 2") {
    val bad = Vector(
      Vector("--output", "x", "--snapshot", "2026-11-22"),
      Vector("--dblp", "d", "--output", "x", "--snapshot", "2026-13-40"),
      Vector("--dblp", "d", "--output", "x", "--snapshot", "2026-11-22", "--reducers", "0"),
      Vector("--dblp", "d", "--output", "x", "--snapshot", "2026-11-22", "--max-team", "1")
    )
    bad.foreach(args => assert(PipelineArgs.parse(args).isLeft, args.toString))
    assertEquals(new Pipeline().run(Array("--dblp", "d")), 2)
  }
