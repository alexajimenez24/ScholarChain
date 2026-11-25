package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import RagWorld.*

/** Stages 2 to 5 against the hand-built run: expected values are the fixture's own numbers. */
class StageSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private lazy val built = w.stores()
  private def stores = built._1
  private def runner(ops: QueryOperation*) =
    val p = w
      .pipeline()
      .planner
      .plan("qry-s", AnalysisRequest("Who has the most coauthors?"))
      .toOption
      .get
    GraphRunner.forPlan(
      stores.graph,
      p.copy(operations = ops.toVector :+ QueryOperation.SEMANTIC_EXPLANATION),
      w.cfg
    )
  private def resolver = new ResearcherResolverAgent(w.ctx, stores.directory)
  private def metrics =
    new CollaborationMetricsAgent(w.ctx, stores.kv, stores.directory, stores.ranking)
  private def paths = new PathFinderAgent(w.ctx, stores.directory)
  private def r[A](e: Either[WorkflowError, A]): A = e.fold(x => fail(x.describe), identity)

  test(
    "resolver: one exact name resolves with its identifiers, affiliations, coauthors and confidence"
  ) {
    val Vector(res) = r(resolver.resolve(Vector("alice ARDEN"), Vector.empty, Recent, runner()))
    assertEquals(res.status, ResolutionStatus.RESOLVED)
    assertEquals(res.researcherId, Some(Alice))
    val c = res.candidates.head
    assertEquals(c.affiliations, Vector(s"Example Quantum Institute ($QuantumInst)"))
    assertEquals(c.coauthors.head, s"Bob Brandt ($Bob, 3 joint publications)")
    assertEquals(c.identityConfidence, 0.97)
    assertEquals(c.sourceMentionIds.size, 2)
    val ev = resolver.identityEvidence(res).head
    assertEquals(ev.evidenceType, EvidenceType.IDENTITY_RESOLUTION)
    assertEquals(ev.values("erRunId"), ErRunId)
    assertEquals(ev.values("status"), "RESOLVED")
    assertEquals(Validate.evidence(ev), Vector.empty)
  }

  test(
    "resolver: two researchers with one name are AMBIGUOUS with both candidates; no silent choice"
  ) {
    val Vector(res) = r(resolver.resolve(Vector("Wei Wang"), Vector.empty, Recent, runner()))
    assertEquals(res.status, ResolutionStatus.AMBIGUOUS)
    assertEquals(res.researcherId, None)
    assertEquals(res.candidates.map(_.researcherId).toSet, Set(Wei1, Wei2))
    assertEquals(res.totalCandidates, 2)
    val wei1 = res.candidates.find(_.researcherId == Wei1).get
    assertEquals(wei1.affiliations, Vector(s"Example Quantum Institute ($QuantumInst)"))
    assert(wei1.coauthors.exists(_.startsWith("Alice Arden")), wei1.coauthors.toString)
    assertEquals(
      resolver.identityEvidence(res).map(_.values("status")).distinct,
      Vector("AMBIGUOUS")
    )
  }

  test("resolver: an explicit researcher ID disambiguates; unknown names and conflicts abstain") {
    val Vector(res) = r(resolver.resolve(Vector("Wei Wang"), Vector(Wei2), Recent, runner()))
    assertEquals((res.status, res.researcherId), (ResolutionStatus.RESOLVED, Some(Wei2)))
    assertEquals(res.candidates.head.matchedBy, "explicit-id")
    val Vector(none) = r(resolver.resolve(Vector("Nobody Atall"), Vector.empty, Recent, runner()))
    assertEquals((none.status, none.candidates), (ResolutionStatus.NOT_FOUND, Vector.empty))
    val Vector(zoe) = r(resolver.resolve(Vector("Zoe Zeller"), Vector.empty, Recent, runner()))
    assertEquals(
      zoe.status,
      ResolutionStatus.AMBIGUOUS,
      "one candidate with an er-v1 identity conflict"
    )
    val Vector(tokens) = r(resolver.resolve(Vector("Arden Alice"), Vector.empty, Recent, runner()))
    assertEquals(
      (tokens.researcherId, tokens.candidates.head.matchedBy),
      (Some(Alice), "name-tokens")
    )
    assert(resolver.resolve(Vector("Alice"), Vector("not-an-id"), Recent, runner()).isLeft)
  }

  test(
    "metrics: exact stored values with metric version and run; missing measures are listed, not filled"
  ) {
    val rec = r(metrics.forResearcher(Alice, Recent)).get
    val v = rec.evidence.values
    assertEquals(v("distinctCoauthorCount"), "41")
    assertEquals(v("publicationCount"), "12")
    assertEquals(v("totalCoauthorOccurrences"), "44")
    assertEquals(v("weightedDegree"), "60")
    assertEquals(v("averageCoauthorsPerPaper"), MetricValues.plain(44.0 / 12))
    assertEquals(v("maximumCoauthorsOnOnePaper"), "6")
    assertEquals(v("maximumTeamWorkIds"), SurfaceWork)
    assertEquals(v("bridgeScore"), "0.12")
    assertEquals(v("metricVersion"), "metrics-v1")
    assertEquals(v("metricRunId"), w.runId)
    assertEquals(rec.evidence.provenance.runId, w.runId)
    val wei2 = r(metrics.forResearcher(Wei2, Recent)).get.evidence.values
    assert(
      !wei2.contains("bridgeScore") && wei2("missingFields").contains("bridgeScore"),
      wei2.toString
    )
    assertEquals(r(metrics.forResearcher(Carol, Early)), None)
    assertEquals(r(metrics.temporal(Alice)).map(_.metrics.window), Vector(Early, Recent))
  }

  test("rankings use the selected stored metric, ties by ID, and refuse a disagreeing index") {
    val (byBreadth, ev) =
      r(metrics.ranked(Recent, CollaborationMetric.DISTINCT_COAUTHOR_COUNT, 3, None))
    assertEquals(byBreadth.map(_.metrics.researcherId), Vector(Carol, Alice, Bob))
    assertEquals(ev.values("positions"), s"1:$Carol=52;2:$Alice=41;3:$Bob=30")
    assertEquals(ev.values("metricField"), "distinctCoauthorCount")
    val (byBridge, _) = r(metrics.ranked(Recent, CollaborationMetric.BRIDGE_SCORE, 3, None))
    assertEquals(byBridge.map(_.metrics.researcherId), Vector(Carol, Bob, Alice))
    val (byWeight, _) = r(metrics.ranked(Recent, CollaborationMetric.WEIGHTED_DEGREE, 2, None))
    assertEquals(byWeight.map(_.metrics.researcherId), Vector(Alice, Carol))
    val (among, ev2) = r(
      metrics.ranked(
        Recent,
        CollaborationMetric.DISTINCT_COAUTHOR_COUNT,
        5,
        Some(("fixture set", Set(Bob, Dan)))
      )
    )
    assertEquals(among.map(_.metrics.researcherId), Vector(Bob, Dan))
    assertEquals(ev2.values("filter"), "fixture set")
    val lying = new MetricRanking:
      def top(w: TimeWindow, v: String, m: CollaborationMetric, l: Int, a: Option[Set[String]]) =
        Right(Vector(RankedId(Dan, 999)))
    val bad = new CollaborationMetricsAgent(w.ctx, stores.kv, stores.directory, lying)
    assert(bad.ranked(Recent, CollaborationMetric.DISTINCT_COAUTHOR_COUNT, 1, None).isLeft)
  }

  test("paths: one window, bounded depth, and per-hop weights, years, works and pointers") {
    val g = runner(QueryOperation.SHORTEST_PATH)
    val ev = r(paths.shortestPath(Alice, Dan, Recent, 6, g))
    val path = ev.head
    assertEquals(path.evidenceType, EvidenceType.GRAPH_PATH)
    assertEquals(path.graphPath, Some(Vector(Alice, Bob, Carol, Dan)))
    assertEquals(path.values("hops"), "3")
    assertEquals(
      path.values("researcherNames"),
      "Alice Arden -> Bob Brandt -> Carol Chen -> Dan Dorsey"
    )
    val hops = ev.tail
    assertEquals(hops.map(_.values("jointPublicationCount")), Vector("3", "1", "2"))
    assertEquals(hops.map(_.values("firstCollaborationYear")), Vector("2016", "2020", "2019"))
    assertEquals(hops.map(_.values("supportingWorkIds")), Vector(SurfaceWork, ChainWork, ChainWork))
    assert(
      hops.forall(
        _.sourceUri.startsWith("file:///fixture/run/coauthor-edges/windowLabel=2015-2026#e:co:")
      )
    )
    assertEquals(path.values("hopEvidenceIds"), hops.map(_.evidenceId).mkString(";"))
    val short = r(paths.shortestPath(Alice, Dan, Recent, 2, g)).head
    assertEquals((short.values("found"), short.values("maxDepth")), ("false", "2"))
    val early = r(paths.shortestPath(Alice, Dan, Early, 6, g)).head
    assertEquals(early.values("found"), "false", "edges of another window are never used")
    assert(
      g.executed
        .filter(c => PlanOperations.WindowedTemplates(c.templateId))
        .forall(_.window.nonEmpty)
    )
    assert(paths.shortestPath(Alice, Dan, Recent, 7, g).isLeft, "depth above the template maximum")
  }

  test("direct collaborators and pair checks come from the window's coauthor edges") {
    val g = runner(QueryOperation.DIRECT_COAUTHORS)
    val co = r(paths.directCoauthors(Alice, Recent, 10, g))
    assertEquals(
      co.map(e => Set(e.values("researcherA"), e.values("researcherB")) - Alice),
      Vector(Set(Bob), Set(Wei1))
    )
    assertEquals(co.head.values("jointPublicationCount"), "3")
    assertEquals(r(paths.pair(Alice, Carol, Recent, g)).values("found"), "false")
    assertEquals(r(paths.pair(Alice, Erin, Early, g)).values("jointPublicationCount"), "1")
  }

  test(
    "communities: membership, cross-community links, and degree kept apart from bridge measures"
  ) {
    val g = runner(QueryOperation.COMMUNITY_COMPARISON)
    val bob = r(metrics.forResearcher(Bob, Recent)).get
    val view = r(new CommunityAnalystAgent(w.ctx, metrics, paths).analyse(bob, 10, g))
    val rec = view.evidence.head.values
    assertEquals(rec("communityId"), SmokeFixture.QuantumCommunity)
    assertEquals(rec("degree.distinctCoauthorCount"), "30")
    assertEquals(rec("bridge.bridgeScore"), "0.41")
    assertEquals(rec("neighbouringCommunitiesOnRetrievedEdges"), SmokeFixture.BiologyCommunity)
    assert(rec("missingFields").contains("communitySize"))
    val links = view.evidence.filter(_.values.get("kind").contains("cross-community-link"))
    assertEquals(links.map(_.values("coauthorId")), Vector(Carol))
    assertEquals(links.head.values("otherCommunityId"), SmokeFixture.BiologyCommunity)
    assert(view.notes.exists(_.contains("community size")))
  }

  test(
    "graph runner: plan allowlist, one window, query budget and per-query timeout are enforced"
  ) {
    val t = w.ctx.templates
    val ranking = runner(QueryOperation.RESEARCHER_RANKING)
    val denied = ranking.run("x", t.shortestPath(Alice, Dan, Recent.label, 3))
    assert(denied.left.exists(_.describe.contains("do not allow")), denied.toString)
    assert(
      ranking.run("x", t.directCoauthors(Alice, "2016-2020", 5)).isLeft,
      "an unconfigured window"
    )
    val tiny = new GraphRunner(stores.graph, Set("node-v1"), Set(Recent.label), 1, 10, 1000, 10000)
    assert(tiny.run("x", t.node(Alice)).isRight)
    assert(tiny.run("x", t.node(Bob)).left.exists(_.isInstanceOf[WorkflowError.BudgetExceeded]))
    val slow = new InMemoryGraphStore(_ => { Thread.sleep(2000); Right(Vector.empty) })
    val timed = new GraphRunner(slow, Set("node-v1"), Set(Recent.label), 5, 10, 50, 10000)
    assert(timed.run("x", t.node(Alice)).left.exists(_.isInstanceOf[WorkflowError.Timeout]))
    assertEquals(timed.executed.map(_.status), Vector("timeout"))
    val many = new InMemoryGraphStore(_ => Right(Vector.fill(11)(ujson.Obj())))
    val rows = new GraphRunner(many, Set("node-v1"), Set(Recent.label), 5, 10, 1000, 10000)
    assert(rows.run("x", t.node(Alice)).left.exists(_.isInstanceOf[WorkflowError.BudgetExceeded]))
  }
