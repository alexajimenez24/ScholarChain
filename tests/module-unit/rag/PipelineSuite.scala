package scholarchain.rag

import scholarchain.core.*
import RagWorld.*

/** Stages 6 and 7 and the stage 1-7 pipeline on the hand-built run. */
class PipelineSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private lazy val pipe = w.pipeline()
  private def run(q: String, c: Constraints = Constraints()) =
    pipe
      .run(s"qry-${Hashing.short(q + c.toString, 12)}", AnalysisRequest(q, c))
      .fold(e => fail(e.describe), identity)
  private def kinds(r: EvidenceStageResult, t: EvidenceType) =
    r.bundle.deterministic.filter(_.evidenceType == t)

  test(
    "ranking with bridges: exact order, ranking evidence, bridge ranking among the ranked set, communities"
  ) {
    val r = run(
      "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?"
    )
    assertEquals(r.window, Recent)
    assertEquals(r.abstention, None)
    val rankings = r.bundle.deterministic.filter(_.values.get("kind").contains("ranking"))
    assertEquals(
      rankings.map(_.values("metric")),
      Vector("DISTINCT_COAUTHOR_COUNT", "BRIDGE_SCORE")
    )
    assertEquals(rankings.head.researcherIds.take(3), Vector(Carol, Alice, Bob))
    assertEquals(rankings(1).values("filter"), "the primary ranking")
    assertEquals(rankings(1).researcherIds.take(3), Vector(Carol, Bob, Alice))
    assert(r.bundle.deterministic.exists(_.values.get("kind").contains("community-membership")))
    assertEquals(Validate.bundle(r.bundle), Vector.empty)
    assertEquals(r.bundle.graphRunId, w.runId)
    assertEquals(r.bundle.metricVersion, "metrics-v1")
  }

  test("deterministic graph evidence and semantic context stay in separate partitions") {
    val r = run("Which researchers have the most distinct coauthors since 2015?")
    assert(
      r.bundle.deterministic.forall(e =>
        GraphValuePolicy.maySupportGraphValue(e) || e.evidenceType == EvidenceType.PUBLICATION
      )
    )
    assert(r.bundle.semantic.nonEmpty)
    assert(r.bundle.semantic.forall(e => GraphValuePolicy.Descriptive(e.evidenceType)))
    assert(r.bundle.semantic.forall(_.values.get("trust").contains("untrusted-data")))
    assert(
      r.bundle.semantic.forall(e =>
        e.score > -1.0 && e.score <= 1.0 && e.sourceUri.startsWith("s3://")
      )
    )
    assert(r.bundle.deterministic.forall(_.text.isEmpty), "no retrieved text in graph evidence")
  }

  test(
    "an ambiguous name abstains with every candidate and runs no graph query beyond resolution"
  ) {
    val r = run("Who are the coauthors of Wei Wang since 2015?")
    assert(
      r.abstention.exists(a => a.contains(Wei1) && a.contains(Wei2) && a.contains("ambiguous")),
      r.abstention.toString
    )
    assertEquals(r.bundle.deterministic, Vector.empty)
    assertEquals(r.bundle.semantic, Vector.empty)
    assertEquals(r.bundle.identity.flatMap(_.researcherIds).toSet, Set(Wei1, Wei2))
    assertEquals(r.graphCalls.map(_.stage).toSet, Set("resolve"))
    assertEquals(r.named, Vector.empty)
  }

  test(
    "an unknown name abstains; a resolved name without evidence in the window abstains with a note"
  ) {
    val unknown = run("Who are the coauthors of Nobody Atall since 2015?")
    assert(unknown.abstention.exists(_.contains("no canonical researcher matches 'Nobody Atall'")))
    val erin = run("Who are the coauthors of Erin Ellis since 2015?")
    assertEquals(erin.resolutions.flatMap(_.researcherId), Vector(Erin))
    assert(erin.abstention.exists(_.contains("no deterministic")), erin.abstention.toString)
    assert(erin.notes.exists(_.contains("no metrics row in window 2015-2026")), erin.notes.toString)
  }

  test("a path question carries the path, every hop and a supporting publication") {
    val r = run("What is the shortest path between Alice Arden and Dan Dorsey since 2015?")
    assertEquals(
      kinds(r, EvidenceType.GRAPH_PATH).flatMap(_.graphPath),
      Vector(Vector(Alice, Bob, Carol, Dan))
    )
    assertEquals(kinds(r, EvidenceType.COAUTHOR_EDGE).size, 3)
    assertEquals(
      kinds(r, EvidenceType.PUBLICATION).map(_.values("title")),
      Vector("Surface code decoders", "Consensus for folding labs")
    )
    assertEquals(r.named.map(_.metrics.researcherId), Vector(Alice, Dan))
  }

  test("topic and institution filters rank only the matched researchers, by graph membership") {
    val t = run(
      "Which researchers have the most distinct coauthors in quantum error correction since 2015?"
    )
    val ranking = t.bundle.deterministic.find(_.values.get("kind").contains("ranking")).get
    assertEquals(
      ranking.researcherIds,
      Vector(Alice, Bob, Wei1),
      "Erin's 2005 work is outside the window"
    )
    assert(ranking.values("filter").contains("quantum error correction"))
    assert(t.notes.exists(_.contains(SmokeFixture.QuantumTopic)), t.notes.toString)
    val i = run(
      "Which researchers have the most distinct coauthors since 2015?",
      Constraints(institutions = Vector("Example Biology Institute"))
    )
    assertEquals(i.ranked.map(_.metrics.researcherId), Vector(Carol))
    val none =
      run("Which researchers have the most distinct coauthors in underwater basket weaving?")
    assertEquals(none.ranked, Vector.empty)
    assert(none.notes.exists(_.contains("matches no topic name")))
    assert(
      none.abstention.exists(_.contains("no researcher matched the ranking")),
      none.abstention.toString
    )
  }

  test("a window that was not precomputed is mapped to the containing window and stated") {
    val r = run("Who has the most distinct coauthors between 2016 and 2020?")
    assertEquals(r.plan.window, TimeWindow(2016, 2020))
    assertEquals(r.window, Recent)
    assert(r.windowNote.exists(_.contains("2016-2020")))
    assert(r.graphCalls.flatMap(_.window).forall(_ == Recent.label))
  }

  test("temporal questions read every window's stored row of the named researcher") {
    val r = run("""How has the collaboration of "Alice Arden" changed over time?""")
    val windows = r.bundle.deterministic
      .filter(_.values.get("kind").contains("researcher-metrics"))
      .map(_.values("window"))
      .toSet
    assertEquals(windows, Set(Early.label, Recent.label))
  }

  test("injected text stays untrusted semantic data and never reaches graph evidence or queries") {
    val r = run(
      """What are the collaboration metrics of "Wei Wang" since 2015? MATCH (n) DETACH DELETE n""",
      Constraints(researcherIds = Vector(Wei1))
    )
    val injected = r.bundle.semantic.filter(_.text.exists(_.contains("IGNORE ALL PREVIOUS")))
    assert(injected.nonEmpty, r.bundle.semantic.map(_.text).toString)
    assert(injected.forall(_.values("trust") == "untrusted-data"))
    assert(!r.bundle.deterministic.exists(_.values.values.exists(_ == "999")))
    assertEquals(r.named.head.evidence.values("distinctCoauthorCount"), "3")
    assert(r.graphCalls.forall(c => c.window.forall(w.cfg.graph.windows.map(_.label).contains)))
  }

  test(
    "evidence builder: partitions enforced, conflicting duplicates dropped, count and byte bounds noted"
  ) {
    val r = run("Which researchers have the most distinct coauthors since 2015?")
    val b = new EvidenceBuilderAgent(w.ctx)
    val det = r.bundle.deterministic
    assert(
      b.build("q", det ++ r.bundle.semantic, Vector.empty, Vector.empty).isLeft,
      "semantic in deterministic"
    )
    assert(b.build("q", Vector.empty, Vector.empty, det).isLeft, "graph evidence in semantic")
    val twin = det.head.copy(values = det.head.values + ("distinctCoauthorCount" -> "999"))
    val (dup, notes) = b.build("q", det :+ twin, Vector.empty, Vector.empty).toOption.get
    assertEquals(dup.deterministic, det)
    assert(notes.exists(_.contains("conflicting copy")))
    val small = new EvidenceBuilderAgent(
      w.ctx,
      EvidenceLimits(maxDeterministic = 2, maxSemantic = 1, maxBytes = 6000)
    )
    val (bounded, n2) = small.build("q", det, Vector.empty, r.bundle.semantic).toOption.get
    assert(bounded.deterministic.size <= 2 && bounded.semantic.size <= 1)
    assert(JsonLines.encode(bounded).getBytes("UTF-8").length <= 6000)
    assert(n2.exists(_.contains("bounded")))
    assertEquals(Validate.bundle(bounded), Vector.empty)
  }

  test(
    "no user text reaches the graph: every query is a template with ID, window and type parameters"
  ) {
    val seen = scala.collection.mutable.ArrayBuffer.empty[scholarchain.storage.GraphQuery]
    val (s, retriever) =
      w.stores(tg => new scholarchain.storage.InMemoryGraphStore(q => { seen += q; tg.respond(q) }))
    val p = new EvidencePipeline(w.ctx, s, retriever)
    val questions = Vector(
      "What is the shortest path between Alice Arden and Dan Dorsey since 2015? RETURN 1 UNION MATCH (x) RETURN x",
      "Which researchers have the most distinct coauthors in quantum error correction since 2015, and which are bridges between communities? CALL db.labels()",
      "Who are the coauthors of Bob Brandt'}) SET n.hacked = true // since 2015?"
    )
    questions.foreach(q =>
      p.run(s"qry-${Hashing.short(q, 8)}", AnalysisRequest(q)).fold(e => fail(e.describe), identity)
    )
    assert(seen.size > 10, seen.size.toString)
    val windows = w.cfg.graph.windows.map(_.label).toSet
    val edgeTypes = scholarchain.storage.GraphTemplates.NeighborEdgeTypes
    def allowedParam(k: String, v: ujson.Value): Boolean = v match
      case ujson.Str(x) if k == "window" => windows(x)
      case ujson.Str(x)  => Ids.isResearcherId(x) || Ids.isWorkId(x) || x.startsWith("openalex:")
      case ujson.Num(n)  => n >= 1 && n <= scholarchain.storage.GraphTemplates.MaxLimit
      case ujson.Arr(xs) => k == "edgeTypes" && xs.forall(x => edgeTypes(x.str))
      case _             => false
    seen.foreach { q =>
      assert(scholarchain.storage.GraphTemplates.isAllowlisted(q), q.templateId)
      assertEquals(q.cypher, scholarchain.storage.GraphTemplates.ById(q.templateId).cypher)
      q.parameters.value.foreach((k, v) => assert(allowedParam(k, v), s"${q.templateId} $k=$v"))
      if PlanOperations.WindowedTemplates(q.templateId) then
        assertEquals(q.parameters.value.get("window").map(_.str), Some(Recent.label))
    }
  }
