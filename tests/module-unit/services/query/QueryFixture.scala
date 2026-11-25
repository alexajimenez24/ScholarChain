package scholarchain.services.query

import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.*

/** A bounded served run for the service tests: three researchers, one work, one window, built
  * through storage's in-memory repositories (no export is loaded, no network). Expected values are
  * written by hand.
  */
object QueryFixture:
  val RunId = "run-5a5a5a5a5a5a5a5a"
  val ErRunId = "run-e0e0e0e0e0e0e0e0"
  val Snapshot = "2026-11-24"
  val Recent: TimeWindow = TimeWindow(2015, 2026)
  val Alice = "sc:r:v1:" + "a" * 20
  val Bob = "sc:r:v1:" + "b" * 20
  val Carol = "sc:r:v1:" + "c" * 20
  val Work = "dblp:conf/qec/Arden21"
  val AliceQ = """What are the collaboration metrics of "Alice Arden" since 2015?"""
  val RankingQ = "Which researchers have the most distinct coauthors since 2015?"
  val UnknownQ = """What are the collaboration metrics of "Nobody Known" since 2015?"""

  val cfg: ScholarChainConfig = LocalIngestion.localConfig().fold(e => sys.error(e), identity)
  val ctx: RuntimeContext = RuntimeContext(cfg, RunId, Snapshot, ErRunId)

  private def prov(id: String, schema: String, policy: String) =
    Provenance(SourceType.SCHOLARCHAIN, id, Snapshot, RunId, schema, Some(policy))

  private def researcher(id: String, name: String) =
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

  val nodes: Vector[GraphNode] = Vector(
    researcher(Alice, "Alice Arden"),
    researcher(Bob, "Bob Brandt"),
    researcher(Carol, "Carol Chen"),
    GraphNode(
      Work,
      GraphLabels.Work,
      Map("title" -> "Surface code decoders", "year" -> "2021", "recordType" -> "inproceedings"),
      prov(Work, SchemaVersions.GraphNode, "graph-v1")
    )
  )

  private def authored(from: String) =
    GraphEdge(
      Ids.graphEdge(GraphLabels.Authored, from, Work, "graph-v1", s"$from>$Work"),
      from,
      GraphLabels.Authored,
      Work,
      Map.empty,
      prov(from, SchemaVersions.GraphEdge, "graph-v1")
    )

  val edges: Vector[GraphEdge] = Vector(authored(Alice), authored(Bob))

  private def coauthor(a: String, b: String, n: Long) =
    val (x, y) = Ids.orderedPair(a, b)
    val id = Ids.coauthorEdge(x, y, Recent, "graph-v1")
    CoauthorEdge(
      SchemaVersions.Edge,
      id,
      x,
      y,
      n,
      2016,
      2022,
      Vector(Work),
      Recent,
      "graph-v1",
      prov(id, SchemaVersions.Edge, "graph-v1")
    )

  val coauthorEdges: Vector[CoauthorEdge] = Vector(coauthor(Alice, Bob, 3))

  private def metrics(id: String, pubs: Long, coauthors: Long, bridge: Double) =
    ResearcherMetrics(
      SchemaVersions.Metrics,
      id,
      pubs,
      coauthors,
      coauthors + 3,
      (coauthors + 3).toDouble / pubs,
      4,
      Vector(Work),
      coauthors.toDouble + 5,
      "cc:00000000000000aa",
      120,
      Some("cm:1111111111111111"),
      Some(bridge),
      Some(0.32),
      Some(2L),
      Some(2L),
      Some(2),
      Recent,
      "metrics-v1",
      prov(id, SchemaVersions.Metrics, "metrics-v1")
    )

  val metricRows: Vector[ResearcherMetrics] =
    Vector(metrics(Alice, 12, 41, 0.12), metrics(Bob, 9, 30, 0.41), metrics(Carol, 15, 52, 0.65))

  /** Fresh in-memory stores and a retriever over empty vector indexes. */
  def stores(): (RuntimeStores, SemanticContextRetriever) =
    val local = LocalStores.inMemory()
    val tables = DynamoTables(local.kv, cfg.dynamo).fold(sys.error, identity)
    metricRows.foreach(m => tables.metrics.put(m).fold(e => sys.error(e.toString), identity))
    val graph = TemplateGraph
      .build(nodes, edges, coauthorEdges, "graph-v1", "file:///fixture/run")
      .fold(sys.error, identity)
    val embedder = new HashingEmbedder(cfg.vectors.dimension)
    val vectors = for
      writer <- Right(new ArtifactWriter(local.objects, new S3Layout(cfg.storage)))
      loader <- VectorLoader(local.vectors, embedder, writer, tables.runLedger, cfg.vectors)
      _ <- local.vectors.createBucket().left.map(_.text)
      _ <- loader.specs.foldLeft[Either[String, Unit]](Right(()))((acc, s) =>
        acc.flatMap(_ => local.vectors.createIndex(s).left.map(_.text).map(_ => ()))
      )
      r <- SemanticContextRetriever(cfg, local.vectors, local.objects, embedder)
    yield r
    (
      RuntimeStores(
        local.kv,
        graph.store,
        local.vectors,
        local.objects,
        embedder,
        InMemoryDirectory.fromNodes(nodes).fold(sys.error, identity),
        new InMemoryMetricRanking(metricRows)
      ),
      vectors.fold(sys.error, identity)
    )
