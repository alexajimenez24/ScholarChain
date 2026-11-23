package scholarchain.er.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.Random

import scholarchain.core.*
import scholarchain.er.{ErCandidate, ErFixtures, LocalMatchRunner, MatchArgs, MatchJob}
import scholarchain.er.ErFixtures.Author

/** The supplementary review queue (phase 05, human decision 01): structural criteria only, disjoint
  * from the first queue, no score or criterion in the file, deterministic.
  */
class ReviewSupplementSuite extends munit.FunSuite:
  import SupplementCriterion.*

  private val cfg = SupplementConfig.load().fold(e => throw new AssertionError(e), identity)
  private val queueCfg = ReviewQueueConfig.load().fold(e => throw new AssertionError(e), identity)
  private val Block = "smith_a"

  // ------------------------------------------------------------------ fixture: one block, 7 mentions

  private def w(
      id: String,
      src: SourceType,
      title: String,
      first: Author,
      coauthor: String,
      venue: String
  ) = ErFixtures.work(
    id,
    src,
    title,
    Some(2014),
    Vector(first, Author(coauthor)),
    venueName = Some(venue)
  )

  private val W1 = w(
    "dblp:a/1",
    SourceType.DBLP,
    "Shock Graph Matching",
    Author("Alice Smith 0001"),
    "Bob Jones",
    "Vision"
  )
  private val W6 = w(
    "dblp:a/6",
    SourceType.DBLP,
    "Shock graphs revisited",
    Author("Alice Smith 0001"),
    "Bob Jones",
    "Vision"
  )
  private val W2 = w(
    "dblp:a/2",
    SourceType.DBLP,
    "Deep Lattice Coding",
    Author("Alice Smith 0002"),
    "Carl Wu",
    "Vision"
  )
  private val W3 = w(
    "openalex:W3",
    SourceType.OPENALEX,
    "Shock Graph Matching",
    Author("Alice Smith", orcid = Some(ErFixtures.OrcidA), ext = Some("openalex:A1")),
    "Bob Jones",
    "Vision"
  )
  private val W7 = w(
    "openalex:W7",
    SourceType.OPENALEX,
    "Graph Kernels",
    Author("Alice Smith", orcid = Some(ErFixtures.OrcidC5), ext = Some("openalex:A1")),
    "Eve Moe",
    "Learning"
  )
  private val W4 = w(
    "openalex:W4",
    SourceType.OPENALEX,
    "Protein Folding Kinetics",
    Author("Alice B. Smith", orcid = Some(ErFixtures.OrcidC4), ext = Some("openalex:A2")),
    "Dan Roe",
    "Biology"
  )
  private val W5 = w(
    "crossref:10.1/5",
    SourceType.CROSSREF,
    "Shock Graph Matching",
    Author("Alice Smith"),
    "Bob Jones",
    "Vision"
  )
  private val Works = Vector(W1, W2, W3, W4, W5, W6, W7)
  private def m(x: WorkRecord): String = x.authors.head.mentionId

  private val idx = RecordIndex(Works.map(x => x.workId -> x).toMap, Map.empty, Map.empty)
  private val ctx = MentionContext.index(idx)

  /** The upstream J04 shape: member links inside the two multi-member clusters, and every pair of
    * cluster representatives.
    */
  private val reps = Vector(m(W1), m(W2), m(W3), m(W4), m(W5))
  private val upstreamCandidates: Vector[ErCandidate] =
    Vector(
      ErFixtures.candidate(
        EntityKind.RESEARCHER,
        m(W1),
        m(W6),
        Map("same_source_cluster" -> 1.0),
        blockKey = Block
      ),
      ErFixtures.candidate(
        EntityKind.RESEARCHER,
        m(W3),
        m(W7),
        Map("same_source_cluster" -> 1.0),
        blockKey = Block
      )
    ) ++ (for
      (a, i) <- reps.zipWithIndex
      b <- reps.drop(i + 1)
    yield ErFixtures.candidate(
      EntityKind.RESEARCHER,
      a,
      b,
      Map("name_jw" -> 1.0),
      blockKey = Block
    ))
  private val upstreamIds = upstreamCandidates.map(_.candidateId).toSet
  private val blocks =
    ReviewSupplement.blocks(ReviewSupplement.links(upstreamCandidates, cfg.clusterLinkFeature))
  private val all = ReviewSupplement.qualifying(blocks, ctx, cfg).fold(e => fail(e), identity)
  private def id(a: WorkRecord, b: WorkRecord) = ErCandidate.idOf(EntityKind.RESEARCHER, m(a), m(b))

  /** Hand-derived primary criterion of every pair the upstream did not emit. */
  private val expectedPrimary: Map[String, SupplementCriterion] = Map(
    id(W7, W4) -> DIFFERENT_ORCID,
    id(W6, W2) -> DBLP_HOMONYM_DIFFERENT,
    id(W6, W4) -> COMPATIBLE_INITIALS_NO_SHARED_CONTEXT,
    id(W6, W7) -> COMPATIBLE_INITIALS_NO_SHARED_CONTEXT,
    id(W7, W2) -> COMPATIBLE_INITIALS_NO_SHARED_CONTEXT,
    id(W6, W3) -> SAME_BLOCK_DIFFERENT_CLUSTERS,
    id(W6, W5) -> SAME_BLOCK_DIFFERENT_CLUSTERS,
    id(W7, W1) -> SAME_BLOCK_DIFFERENT_CLUSTERS,
    id(W7, W5) -> SAME_BLOCK_DIFFERENT_CLUSTERS
  )

  // ------------------------------------------------------------------ config

  test("the default config loads, validates and keeps the criteria order of the decision") {
    assertEquals(cfg.queueVersion, "er-review-v1b")
    assertEquals(cfg.maxRows, 60)
    assertEquals(cfg.criteria, SupplementCriterion.values.toVector)
    assertEquals(cfg.contextDimensions, SupplementConfig.ContextDimensions)
    val bad = Vector(
      cfg.copy(maxRows = 0),
      cfg.copy(criteriaOrder = Vector("NOT_A_CRITERION")),
      cfg.copy(criteriaOrder = Vector("DIFFERENT_ORCID", "DIFFERENT_ORCID")),
      cfg.copy(criteriaOrder = Vector.empty),
      cfg.copy(contextDimensions = Vector("work", "shoe_size")),
      cfg.copy(seed = " ")
    )
    bad.foreach(c => assert(SupplementConfig.validate(c).nonEmpty, c))
    assert(SupplementConfig.parse("""{"queueVersion":"x"}""").isLeft)
  }

  // ------------------------------------------------------------------ blocks and criteria

  test("clusters are the connected components of the upstream same-cluster links") {
    assertEquals(blocks.map(_.key), Vector(Block))
    val b = blocks.head
    assertEquals(b.members.size, 7)
    assertEquals(b.clusterOf(m(W1)), b.clusterOf(m(W6)))
    assertEquals(b.clusterOf(m(W3)), b.clusterOf(m(W7)))
    assertEquals(b.members.map(b.clusterOf).distinct.size, 5)
    // An exact (J03) or work candidate never defines a researcher block.
    val work = ErFixtures.candidate(EntityKind.WORK, W1.workId, W5.workId, blockKey = "wk")
    val orcid = ErFixtures.candidate(
      EntityKind.RESEARCHER,
      m(W3),
      s"orcid:${ErFixtures.OrcidA}",
      blockKey = "orcid"
    )
    assertEquals(ReviewSupplement.links(Vector(work, orcid), cfg.clusterLinkFeature), Vector.empty)
  }

  test("each criterion holds exactly where its structural rule says") {
    def holds(c: SupplementCriterion, a: WorkRecord, b: WorkRecord, sameCluster: Boolean = false) =
      ReviewSupplement.holds(c, ctx(m(a)), ctx(m(b)), sameCluster, cfg)
    assert(holds(DIFFERENT_ORCID, W7, W4))
    assert(!holds(DIFFERENT_ORCID, W6, W4), "one side without an ORCID is not a difference")
    assert(holds(DBLP_HOMONYM_DIFFERENT, W6, W2))
    assert(!holds(DBLP_HOMONYM_DIFFERENT, W6, W1), "same DBLP string")
    assert(!holds(DBLP_HOMONYM_DIFFERENT, W6, W5), "Crossref has no homonym numbers")
    assert(holds(COMPATIBLE_INITIALS_NO_SHARED_CONTEXT, W6, W4))
    assert(!holds(COMPATIBLE_INITIALS_NO_SHARED_CONTEXT, W6, W3), "shared coauthor and title token")
    assert(!holds(COMPATIBLE_INITIALS_NO_SHARED_CONTEXT, W6, W2), "shared venue")
    assertEquals(
      ReviewSupplement.sharedContext(ctx(m(W7)), ctx(m(W1)), cfg.contextDimensions),
      Vector("title_token")
    )
    val bob =
      w("dblp:b/1", SourceType.DBLP, "Protein Folding", Author("Bob Smith"), "Zed Q", "Other")
    val bobCtx = MentionContext.of(bob.authors.head, bob)
    assert(
      !ReviewSupplement.holds(COMPATIBLE_INITIALS_NO_SHARED_CONTEXT, ctx(m(W4)), bobCtx, false, cfg)
    )
    assert(holds(SAME_BLOCK_DIFFERENT_CLUSTERS, W6, W3))
    assert(!holds(SAME_BLOCK_DIFFERENT_CLUSTERS, W6, W1, sameCluster = true))
  }

  test("qualifying pairs list every criterion that holds, in priority order") {
    val byId = all.map(p => p.pairId -> p).toMap
    expectedPrimary.foreach((pid, c) => assertEquals(byId(pid).primary, c, pid))
    assertEquals(
      byId(id(W7, W4)).criteria,
      Vector(DIFFERENT_ORCID, COMPATIBLE_INITIALS_NO_SHARED_CONTEXT, SAME_BLOCK_DIFFERENT_CLUSTERS)
    )
    // The same-cluster member links qualify only through another criterion.
    assertEquals(byId.get(id(W1, W6)).map(_.criteria), None)
    assertEquals(byId(id(W3, W7)).criteria, Vector(DIFFERENT_ORCID))
    all.foreach(p =>
      assert(
        p.sourceId < p.targetId && p.pairId == ErCandidate.idOf(
          EntityKind.RESEARCHER,
          p.sourceId,
          p.targetId
        )
      )
    )
    val missing = ReviewSupplement.qualifying(blocks, ctx - m(W5), cfg)
    assert(missing.left.exists(_.contains(m(W5))), missing)
  }

  // ------------------------------------------------------------------ selection

  test("no selected pair is in the first queue or among the upstream candidates") {
    val v1 = Set(id(W6, W2), id(W6, W4), id(W1, W2))
    val sel = ReviewSupplement.select(all, v1, upstreamIds, cfg)
    assert(sel.chosen.nonEmpty)
    assert(sel.chosen.forall(p => !v1(p.pairId) && !upstreamIds(p.pairId)), sel.chosen)
    assertEquals(sel.chosen.map(_.pairId).toSet, expectedPrimary.keySet -- v1)
    assertEquals(sel.inReviewV1, 3)
    assertEquals(sel.eligible, 7)
  }

  test("tiers fill in priority order, the cap holds and nothing is padded") {
    val five = ReviewSupplement.select(all, Set.empty, upstreamIds, cfg.copy(maxRows = 5))
    assertEquals(five.chosen.size, 5)
    assertEquals(five.selectedPrimary(SAME_BLOCK_DIFFERENT_CLUSTERS), 0)
    assertEquals(five.selectedPrimary(COMPATIBLE_INITIALS_NO_SHARED_CONTEXT), 3)
    val two = ReviewSupplement.select(all, Set.empty, upstreamIds, cfg.copy(maxRows = 2))
    assertEquals(two.chosen.map(_.primary).toSet, Set(DIFFERENT_ORCID, DBLP_HOMONYM_DIFFERENT))
    val every = ReviewSupplement.select(all, Set.empty, upstreamIds, cfg)
    assertEquals(every.chosen.size, 9, "60 asked, 9 exist: no padding")
    assertEquals(every.availablePrimary.values.sum, 9)
    assertEquals(every.availableAny(COMPATIBLE_INITIALS_NO_SHARED_CONTEXT), 4)
  }

  test("selection is deterministic and spread over blocks; another seed reorders") {
    val a = ReviewSupplement.select(all, Set.empty, upstreamIds, cfg)
    val shuffled = ReviewSupplement
      .qualifying(
        ReviewSupplement.blocks(
          new Random(7).shuffle(ReviewSupplement.links(upstreamCandidates, cfg.clusterLinkFeature))
        ),
        ctx,
        cfg
      )
      .fold(e => fail(e), identity)
    val b = ReviewSupplement.select(new Random(3).shuffle(shuffled), Set.empty, upstreamIds, cfg)
    assertEquals(b.chosen, a.chosen)
    val c = ReviewSupplement.select(all, Set.empty, upstreamIds, cfg.copy(seed = "other"))
    assertEquals(c.chosen.toSet, a.chosen.toSet)
    assertNotEquals(c.chosen, a.chosen)
    def p(k: String, n: Int) =
      SupplementPair(s"mc:researcher:$k$n", k, "m:a", "m:b", Vector(DIFFERENT_ORCID))
    val tier = Vector(p("x", 1), p("x", 2), p("x", 3), p("y", 1))
    val rr = ReviewSupplement.roundRobin(tier, cfg.seed)
    assertEquals(rr.take(2).map(_.blockKey).toSet, Set("x", "y"), "the second block is not starved")
  }

  // ------------------------------------------------------------------ outputs

  test("rows have the first queue's columns, no score or criterion, empty label and note") {
    val sel = ReviewSupplement.select(all, Set.empty, upstreamIds, cfg)
    val rows = ReviewSupplement
      .rows(sel.chosen, idx, queueCfg, ErFixtures.UpstreamRunId)
      .fold(e => fail(e), identity)
    val text = ReviewQueue.csv(rows)
    val table = ReviewSupplement.csvRecords(text).fold(e => fail(e), identity)
    assertEquals(table.head, ReviewQueue.Columns)
    assertEquals(table.size, 10)
    table.tail.foreach { r =>
      assertEquals(r.size, 20)
      assertEquals(r.takeRight(2), Vector("", ""))
    }
    val banned = SupplementCriterion.values.map(_.toString).toVector ++
      Vector("score", "stratum", "Likely", "MATCH", "criterion", "confidence")
    banned.foreach(t => assert(!text.contains(t), t))
    assertEquals(table.tail.map(_.head).toSet, sel.chosen.map(_.pairId).toSet)
    assertEquals(ReviewSupplement.pairIds(text), Right(sel.chosen.map(_.pairId)))
  }

  test("supplementary candidates are valid match-candidate-v1 records naming the generator") {
    val sel = ReviewSupplement.select(all, Set.empty, upstreamIds, cfg)
    val run = Ids.run("""{"fixture":"supplement"}""")
    sel.chosen.foreach { p =>
      val c = ReviewSupplement.candidate(p, ErFixtures.Snapshot, run, cfg)
      assertEquals(ErCandidate.validate(c), Vector.empty)
      assertEquals(c.candidateId, p.pairId)
      assertEquals(c.features, Map.empty[String, Double], "no upstream feature, no criterion")
      assertEquals(c.provenance.policyVersion, Some(cfg.generatorVersion))
      assertEquals(c.provenance.runId, run)
      assertEquals(ErCandidate.decode(JsonLines.encode(c)), Right(c))
    }
  }

  test("the first queue's pair IDs are read strictly") {
    val ok = ReviewQueue.Columns.mkString(",") + "\r\nmc:researcher:1" + ",x" * 19 + "\r\n"
    assertEquals(ReviewSupplement.pairIds(ok), Right(Vector("mc:researcher:1")))
    assert(ReviewSupplement.pairIds("a,b\r\n1,2\r\n").isLeft)
    assert(ReviewSupplement.pairIds(ReviewQueue.Columns.mkString(",") + "\r\nshort,row\r\n").isLeft)
    assert(ReviewSupplement.pairIds(ReviewQueue.Columns.mkString(",") + "\r\n\"open").isLeft)
  }

  // ------------------------------------------------------------------ end to end

  test("main: first queue untouched, file and sidecar without criteria, a run er-v1 can read") {
    val root = Files.createTempDirectory("er-supplement")
    try
      val up = root.resolve("up")
      val run = ErFixtures.UpstreamRunId
      def dir(p: String) = Files.createDirectories(up.resolve(p))
      val exact = dir(s"match-candidates/kind=exact/snapshot=${ErFixtures.Snapshot}/run_id=$run")
      val blocked =
        dir(s"match-candidates/kind=blocked/snapshot=${ErFixtures.Snapshot}/run_id=$run")
      val exp = dir(s"export/snapshot=${ErFixtures.Snapshot}/run_id=$run")
      dir("run-manifests")
      Files.writeString(
        up.resolve(s"run-manifests/$run.json"),
        """{"pipeline":"hadoop-v2","normalization":"norm-v1"}"""
      )
      val orcid = ErFixtures.candidate(
        EntityKind.RESEARCHER,
        m(W3),
        s"orcid:${ErFixtures.OrcidA}",
        blockKey = "orcid"
      )
      Files.writeString(exact.resolve("part-r-00000"), JsonLines.encode(orcid) + "\n")
      Files.writeString(
        blocked.resolve("part-r-00000"),
        upstreamCandidates.map(JsonLines.encode(_)).mkString("\n") + "\n"
      )
      Files.createDirectories(exp.resolve("works"))
      Files.writeString(
        exp.resolve("works/part-r-00000"),
        Works.map(JsonLines.encode(_)).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
      Vector(exact, blocked, exp).foreach(d => Files.writeString(d.resolve("_SUCCESS"), ""))
      // The first queue holds one of the qualifying pairs.
      val v1Rows = ReviewQueue
        .rows(
          Vector(CandidateView(id(W6, W2), EntityKind.RESEARCHER, m(W2), m(W6), Map.empty, run)),
          idx,
          queueCfg
        )
        .fold(e => fail(e), identity)
      val v1 = root.resolve("q/er-review-v1.csv")
      Files.createDirectories(v1.getParent)
      Files.writeString(v1, ReviewQueue.csv(v1Rows), StandardCharsets.UTF_8)
      val v1Sha = Hashing.sha256Hex(Files.readAllBytes(v1))
      val out = root.resolve("q/er-review-v1b.csv")
      val cands = root.resolve("supp")
      val args = Vector(
        "--run-root",
        up.toString,
        "--review-v1",
        v1.toString,
        "--out",
        out.toString,
        "--candidates-out",
        cands.toString
      )
      val stdout = ReviewSupplementMain.run(args).fold(e => fail(e), identity)
      assert(stdout.exists(_.contains("wrote 8 rows")), stdout)
      assert(stdout.exists(_.startsWith("DIFFERENT_ORCID")), stdout)
      assertEquals(Hashing.sha256Hex(Files.readAllBytes(v1)), v1Sha)
      val text = Files.readString(out, StandardCharsets.UTF_8)
      val ids = ReviewSupplement.pairIds(text).fold(e => fail(e), identity)
      assertEquals(ids.toSet, expectedPrimary.keySet - id(W6, W2))
      val sidecar = Files.readString(ReviewQueueMain.sidecarOf(out))
      assert(
        sidecar.contains(Hashing.sha256Hex(text)) && sidecar.contains(v1Sha) && sidecar.contains(
          run
        )
      )
      SupplementCriterion.values.foreach(c => assert(!sidecar.contains(c.toString), c))
      // The supplementary run resolves as an upstream run of the unchanged er-v1 job.
      val conf = LocalMatchRunner.localConf(root.resolve("tmp").toString)
      val margs = MatchArgs
        .parse(Seq("--run-root", cands.toString, "--output", root.resolve("o").toString))
        .fold(e => fail(e), identity)
      val upstream = MatchJob.upstream(conf, margs).fold(e => fail(e), identity)
      assertEquals(upstream.pipeline, cfg.generatorVersion)
      assertEquals(upstream.normalization, "norm-v1")
      val lines = MatchJob.lines(conf, upstream.candidateGlobs(1))
      assertEquals(
        lines
          .map(l => ErCandidate.decode(l).map(_.candidateId))
          .collect { case Right(i) => i }
          .toSet,
        ids.toSet
      )
      assertEquals(MatchJob.lines(conf, upstream.candidateGlobs(0)), Vector.empty)
      assertEquals(MatchJob.lines(conf, upstream.worksGlob).size, Works.size)
      // Refusals: an existing output, the first queue as output, a gold path.
      assert(ReviewSupplementMain.run(args).left.exists(_.contains("--overwrite")))
      val self = args.updated(5, v1.toString)
      assert(
        ReviewSupplementMain
          .run(self :+ "--overwrite" :+ "true")
          .left
          .exists(_.contains("first review queue"))
      )
      val gold = args.updated(5, "/x/datasets/gold/er.csv")
      assert(ReviewSupplementMain.run(gold).left.exists(_.contains("datasets/gold")))
      val again = ReviewSupplementMain.run(args :+ "--overwrite" :+ "true")
      assert(again.isRight, again)
      assertEquals(Files.readString(out, StandardCharsets.UTF_8), text)
      assertEquals(Hashing.sha256Hex(Files.readAllBytes(v1)), v1Sha)
    finally
      val all = Files.walk(root).toArray.map(_.asInstanceOf[Path]).sortBy(-_.toString.length)
      all.foreach(Files.deleteIfExists)
  }
