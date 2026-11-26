package scholarchain.adversarial.rag

import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.*

/** A small hand-built served run for the Phase 11 runtime gate suites (test-engineer). It never
  * reads the 25,000-work export: every node, edge and metric row is written below by hand.
  *
  * Public API only: the `RuntimeStores`, `TemplateGraph.build`, `CollaborationWorkflow` and
  * `SemanticContextRetriever` signatures of `modules/rag/MODULE.md` ("Phase 09 workflow API",
  * "Observing graph queries") and the storage ports and fakes of `modules/storage/MODULE.md`. The
  * construction follows the documented usage on in-memory stores (the module's own test helper is
  * not on this project's classpath, so the same public calls are made here).
  *
  * Window 2015-2026. Alice - Bob - Carol - Dan is a chain (3 hops). Alice also coauthored with Wei.
  * Ivan has metrics but no coauthor edge (an isolated vertex, a second component). Mia has metrics
  * but no community, bridge score, coreness, topic, institution or profile. Topic "Quantum error
  * correction" holds the works of Alice, Bob and Wei; institution "Example Quantum Institute" holds
  * Alice, Bob and Wei. Distinct coauthor counts: Carol 52, Alice 41, Bob 30, Dan 19, Wei 3, Mia 1,
  * Ivan 0, so Carol leads the global ranking and Alice leads the topic and institution rankings.
  * Bridge scores: Carol 0.65, Bob 0.41, Alice 0.12, so Carol leads the global bridge ranking and
  * Bob leads the topic-filtered one.
  */
final class MiniWorld(
    val nodes: Vector[GraphNode],
    val edges: Vector[GraphEdge],
    val coauthorEdges: Vector[CoauthorEdge],
    val metricRows: Vector[ResearcherMetrics]
):
  import MiniWorld.*

  val ctx: RuntimeContext = RuntimeContext(cfg, RunId, Snapshot, ErRunId)

  /** Fresh in-memory stores; `graph` may wrap the template graph store (failure injection). */
  def stores(
      graph: GraphStore => GraphStore = identity
  ): Either[String, (RuntimeStores, SemanticContextRetriever)] =
    val local = LocalStores.inMemory()
    for
      tables <- DynamoTables(local.kv, cfg.dynamo)
      _ <- metricRows.foldLeft[Either[String, Unit]](Right(()))((acc, m) =>
        acc.flatMap(_ => tables.metrics.put(m).left.map(_.toString).map(_ => ()))
      )
      writer = new ArtifactWriter(local.objects, new S3Layout(cfg.storage))
      loader <- VectorLoader(
        local.vectors,
        new HashingEmbedder(cfg.vectors.dimension),
        writer,
        tables.runLedger,
        cfg.vectors
      )
      _ <- local.vectors.createBucket().left.map(_.text)
      _ <- loader.specs.foldLeft[Either[String, Unit]](Right(()))((acc, s) =>
        acc.flatMap(_ => local.vectors.createIndex(s).left.map(_.text).map(_ => ()))
      )
      tg <- TemplateGraph.build(nodes, edges, coauthorEdges, "graph-v1", "file:///fixture/mini")
      dir <- InMemoryDirectory.fromNodes(nodes)
      retriever <- SemanticContextRetriever(
        cfg,
        local.vectors,
        local.objects,
        new HashingEmbedder(cfg.vectors.dimension),
        ContextBudget.V1
      )
    yield (
      RuntimeStores(
        local.kv,
        graph(tg.store),
        local.vectors,
        local.objects,
        new HashingEmbedder(cfg.vectors.dimension),
        dir,
        new InMemoryMetricRanking(metricRows)
      ),
      retriever
    )

  def workflow(
      synthesizer: Synthesizer = DeterministicSynthesizer,
      audit: AuditLog = new InMemoryAuditLog,
      graph: GraphStore => GraphStore = identity,
      clock: () => Long = () => System.currentTimeMillis()
  ): CollaborationWorkflow =
    val (s, r) = stores(graph).fold(e => throw new IllegalStateException(e), identity)
    new CollaborationWorkflow(ctx, s, r, synthesizer, audit, EvidenceLimits(), clock)

object MiniWorld:
  val RunId = "run-6b6b6b6b6b6b6b6b"
  val ErRunId = "run-7c7c7c7c7c7c7c7c"
  val Snapshot = "2026-11-24"
  val Recent: TimeWindow = TimeWindow(2015, 2026)

  lazy val cfg: ScholarChainConfig =
    LocalIngestion.localConfig().fold(e => throw new IllegalStateException(e), identity)

  lazy val prompt: SynthesisPrompt =
    SynthesisPrompt
      .load(BenchmarkStore.repoRoot)
      .fold(e => throw new IllegalStateException(s"SynthesisPrompt.load: $e"), identity)

  private def rid(c: Char) = "sc:r:v1:" + c.toString * 20
  val Alice: String = rid('a')
  val Bob: String = rid('b')
  val Carol: String = rid('c')
  val Dan: String = rid('d')
  val Wei: String = rid('1')
  val Ivan: String = rid('9')
  val Mia: String = rid('8')

  val Names: Map[String, String] = Map(
    Alice -> "Alice Arden",
    Bob -> "Bob Brandt",
    Carol -> "Carol Chen",
    Dan -> "Dan Dorsey",
    Wei -> "Wei Wang",
    Ivan -> "Ivan Isola",
    Mia -> "Mia Nometa"
  )

  val QuantumTopic = "openalex:T1001"
  val FoldingTopic = "openalex:T1002"
  val QuantumTopicName = "Quantum error correction"
  val QuantumInst = "openalex:I1000001"
  val BioInst = "openalex:I1000002"
  val QuantumInstName = "Example Quantum Institute"
  val QuantumCommunity = "cm:1111111111111111"
  val BiologyCommunity = "cm:2222222222222222"
  val SystemsCommunity = "cm:3333333333333333"

  val SurfaceWork = "dblp:conf/qec/Arden21"
  val FoldWork = "dblp:journals/cbj/Chen22"
  val ChainWork = "dblp:journals/cfl/Brandt20"
  val WeiWork = "dblp:conf/qrt/Wang18"
  val IvanWork = "dblp:conf/iso/Isola19"
  val MiaWork = "dblp:conf/nom/Nometa23"

  private def prov(id: String, schema: String, policy: String) =
    Provenance(SourceType.SCHOLARCHAIN, id, Snapshot, RunId, schema, Some(policy))

  def researcher(id: String) =
    val name = Names(id)
    GraphNode(
      id,
      GraphLabels.Researcher,
      Map(
        "displayName" -> name,
        "mentionCount" -> "2",
        "mentionIds" -> s"${Ids.mention(s"dblp:x/$name", 0)}|${Ids.mention(s"dblp:x/$name", 1)}",
        "identityConfidence" -> "0.97",
        "unresolved" -> "false",
        "identityConflict" -> "false"
      ),
      prov(id, SchemaVersions.GraphNode, "er-v1")
    )

  private def entity(id: String, label: String, props: (String, String)*) =
    GraphNode(id, label, props.toMap, prov(id, SchemaVersions.GraphNode, "graph-v1"))

  private def work(id: String, title: String, year: Int) =
    entity(
      id,
      GraphLabels.Work,
      "title" -> title,
      "year" -> year.toString,
      "recordType" -> "article"
    )

  private def gedge(t: String, from: String, to: String, props: (String, String)*) =
    GraphEdge(
      Ids.graphEdge(t, from, to, "graph-v1", s"$from>$to"),
      from,
      t,
      to,
      props.toMap,
      prov(from, SchemaVersions.GraphEdge, "graph-v1")
    )

  def coauthor(a: String, b: String, n: Long, first: Int, last: Int, works: String*) =
    val (x, y) = Ids.orderedPair(a, b)
    val id = Ids.coauthorEdge(x, y, Recent, "graph-v1")
    CoauthorEdge(
      SchemaVersions.Edge,
      id,
      x,
      y,
      n,
      first,
      last,
      works.toVector,
      Recent,
      "graph-v1",
      prov(id, SchemaVersions.Edge, "graph-v1")
    )

  def metrics(
      id: String,
      p: Long,
      c: Long,
      weighted: Double,
      maxTeam: Long,
      team: String,
      community: Option[String],
      bridge: Option[Double],
      core: Option[Int]
  ) =
    ResearcherMetrics(
      SchemaVersions.Metrics,
      id,
      p,
      c,
      c + 3,
      (c + 3).toDouble / p,
      maxTeam,
      Vector(team),
      weighted,
      "cc:00000000000000aa",
      120,
      community,
      bridge,
      bridge.map(_ => 0.32),
      bridge.map(_ => 2L),
      bridge.map(_ => 2L),
      core,
      Recent,
      "metrics-v1",
      prov(id, SchemaVersions.Metrics, "metrics-v1")
    )

  val people: Vector[String] = Vector(Alice, Bob, Carol, Dan, Wei, Ivan, Mia)

  val standardNodes: Vector[GraphNode] = people.map(researcher) ++ Vector(
    work(SurfaceWork, "Surface code decoders", 2021),
    work(FoldWork, "Folding pathways by molecular dynamics", 2022),
    work(ChainWork, "Consensus for folding labs", 2020),
    work(WeiWork, "Qubit routing", 2018),
    work(IvanWork, "Isolated register allocation", 2019),
    work(MiaWork, "Untitled note without metadata", 2023),
    entity(QuantumTopic, GraphLabels.Topic, "name" -> QuantumTopicName),
    entity(FoldingTopic, GraphLabels.Topic, "name" -> "Protein folding"),
    entity(QuantumInst, GraphLabels.Institution, "name" -> QuantumInstName),
    entity(BioInst, GraphLabels.Institution, "name" -> "Example Biology Institute")
  )

  val standardEdges: Vector[GraphEdge] = Vector(
    gedge(GraphLabels.Authored, Alice, SurfaceWork),
    gedge(GraphLabels.Authored, Bob, SurfaceWork),
    gedge(GraphLabels.Authored, Carol, FoldWork),
    gedge(GraphLabels.Authored, Bob, ChainWork),
    gedge(GraphLabels.Authored, Carol, ChainWork),
    gedge(GraphLabels.Authored, Dan, ChainWork),
    gedge(GraphLabels.Authored, Alice, WeiWork),
    gedge(GraphLabels.Authored, Wei, WeiWork),
    gedge(GraphLabels.Authored, Ivan, IvanWork),
    gedge(GraphLabels.Authored, Mia, MiaWork),
    gedge(GraphLabels.HasTopic, SurfaceWork, QuantumTopic),
    gedge(GraphLabels.HasTopic, WeiWork, QuantumTopic),
    gedge(GraphLabels.HasTopic, FoldWork, FoldingTopic),
    gedge(GraphLabels.AffiliatedWith, Alice, QuantumInst, "supportingMentionCount" -> "1"),
    gedge(GraphLabels.AffiliatedWith, Bob, QuantumInst, "supportingMentionCount" -> "1"),
    gedge(GraphLabels.AffiliatedWith, Wei, QuantumInst, "supportingMentionCount" -> "1"),
    gedge(GraphLabels.AffiliatedWith, Carol, BioInst, "supportingMentionCount" -> "1")
  )

  val standardCoauthors: Vector[CoauthorEdge] = Vector(
    coauthor(Alice, Bob, 3, 2016, 2022, SurfaceWork),
    coauthor(Bob, Carol, 1, 2020, 2020, ChainWork),
    coauthor(Carol, Dan, 2, 2019, 2021, ChainWork),
    coauthor(Alice, Wei, 1, 2018, 2018, WeiWork)
  )

  val standardMetrics: Vector[ResearcherMetrics] = Vector(
    metrics(Alice, 12, 41, 60, 6, SurfaceWork, Some(QuantumCommunity), Some(0.12), Some(4)),
    metrics(Bob, 9, 30, 35, 4, SurfaceWork, Some(QuantumCommunity), Some(0.41), Some(2)),
    metrics(Carol, 15, 52, 55, 12, FoldWork, Some(BiologyCommunity), Some(0.65), Some(2)),
    metrics(Dan, 7, 19, 40, 3, ChainWork, Some(SystemsCommunity), Some(0.0), Some(2)),
    metrics(Wei, 1, 3, 3, 3, WeiWork, Some(QuantumCommunity), Some(0.0), Some(1)),
    metrics(Ivan, 2, 0, 0, 1, IvanWork, Some(SystemsCommunity), Some(0.0), Some(0)),
    metrics(Mia, 1, 1, 1, 1, MiaWork, None, None, None)
  )

  lazy val standard: MiniWorld =
    new MiniWorld(standardNodes, standardEdges, standardCoauthors, standardMetrics)

  /** No node, no edge, no metric row: an empty served graph. */
  lazy val empty: MiniWorld = new MiniWorld(Vector.empty, Vector.empty, Vector.empty, Vector.empty)

  def withModel(llm: LlmPort, audit: AuditLog = new InMemoryAuditLog): CollaborationWorkflow =
    standard.workflow(new LlmSynthesizer(llm, prompt, cfg), audit)
