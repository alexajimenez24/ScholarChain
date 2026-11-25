package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.Try

/** @param windows
  *   window labels whose coauthor edges and metrics rows are loaded (default: every configured
  *   window); each must be configured. The runtime context's configuration holds only these
  *   windows, so no stage plans, chooses or queries an unloaded window
  * @param vectorRowsPerType
  *   the deterministic vector subset of Phase 08 (`LocalIngestion`'s smallest-SHA-256 rows per
  *   document type); None ingests every row, which does not fit a 2 GB heap
  */
final case class LocalRuntimeSettings(
    windows: Option[Set[String]] = None,
    vectorRowsPerType: Option[Int] = Some(8000)
)

/** Rows loaded per table and partition, checked against the run's load manifests. */
final case class LocalRuntimeReport(
    graphRunId: String,
    erRunId: String,
    snapshot: String,
    windows: Vector[String],
    rows: Vector[(String, Long)],
    vectorChunks: Long
)

/** The runtime stores of one served run, all in memory. */
final case class LocalRuntime(
    ctx: RuntimeContext,
    stores: RuntimeStores,
    retriever: SemanticContextRetriever,
    report: LocalRuntimeReport
):
  def pipeline(limits: EvidenceLimits = EvidenceLimits()): EvidencePipeline =
    new EvidencePipeline(ctx, stores, retriever, limits)

  /** Stages 1 to 10 on these stores (deterministic synthesizer unless another is given). */
  def workflow(
      synthesizer: Synthesizer = DeterministicSynthesizer,
      audit: AuditLog = new InMemoryAuditLog,
      limits: EvidenceLimits = EvidenceLimits()
  ): CollaborationWorkflow =
    new CollaborationWorkflow(ctx, stores, retriever, synthesizer, audit, limits)

/** Loads a Spark run's JSON-lines export into the in-memory stores (`local-runtime-v1`).
  *
  * The run is found through its run manifest (`<out>/run-manifests/<run>.json`); the run ID must be
  * the hash of that manifest, the manifest must name the expected er-v1 run, and the graph and ER
  * policy and metric versions must equal the configuration's. Every row is strictly decoded and
  * validated (one bad row fails the load), every partition's row count must equal the load
  * manifest's, metrics go through storage's ResearcherMetricsLoader into ResearcherMetricCache,
  * vectors through storage's LocalIngestion, and the graph is a [[TemplateGraph]] behind storage's
  * InMemoryGraphStore.
  */
object LocalRuntime:
  val Version = "local-runtime-v1"
  private val NodeLabels =
    Vector(GraphLabels.Researcher, GraphLabels.Topic, GraphLabels.Institution, GraphLabels.Work)
  private val EdgeTypes =
    Vector(GraphLabels.Authored, GraphLabels.AffiliatedWith, GraphLabels.HasTopic)

  def load(
      cfg: ScholarChainConfig,
      runManifest: Path,
      expectedErRunId: String,
      settings: LocalRuntimeSettings = LocalRuntimeSettings()
  ): Either[String, LocalRuntime] =
    val runId = runManifest.getFileName.toString.stripSuffix(".json")
    for
      text <- Try(Files.readString(runManifest, UTF_8)).toEither.left.map(e =>
        s"cannot read $runManifest: ${e.getMessage}"
      )
      _ <- Either.cond(
        Try(Ids.run(text)).toOption.contains(runId),
        (),
        s"$runId is not the hash of its run manifest"
      )
      m <- Try(ujson.read(text)).toEither.left.map(_ => "run manifest is not JSON")
      snapshot <- str(m, "snapshot")
      er <- str(m("inputs")("entityResolution"), "runId")
      _ <- Either.cond(
        er == expectedErRunId,
        (),
        s"run $runId consumed ER run $er, not $expectedErRunId"
      )
      erPolicy <- str(m("inputs")("entityResolution"), "policyVersion")
      _ <- same("entity-resolution policy", erPolicy, cfg.entityResolution.policyVersion)
      _ <- str(m("graph"), "metricVersion").flatMap(
        same("metric version", _, cfg.graph.metricVersion)
      )
      _ <- str(m("graph"), "policyVersion").flatMap(
        same("graph policy", _, cfg.graph.policyVersion)
      )
      folder = runManifest.toAbsolutePath.getParent.getParent
        .resolve(s"spark/snapshot=$snapshot/run_id=$runId")
      _ <- Either.cond(Files.isDirectory(folder), (), s"no run folder $folder")
      neptune <- manifest(folder.resolve("load-manifests/neptune.json"), runId)
      dynamo <- manifest(folder.resolve("load-manifests/dynamodb.json"), runId)
      configured = cfg.graph.windows.map(_.label)
      windows = settings.windows.fold(configured)(s => configured.filter(s))
      _ <- Either.cond(
        settings.windows.forall(_.subsetOf(configured.toSet)) && windows.nonEmpty,
        (),
        s"windows must be a non-empty subset of ${configured.mkString(", ")}"
      )
      counts = Vector.newBuilder[(String, Long)]
      nodes <- seq(NodeLabels.map { l =>
        rows[GraphNode](folder, "graph-nodes", s"label=$l", neptune("nodes"), Validate.graphNode)
          .map { xs =>
            counts += s"graph-nodes/label=$l" -> xs.size.toLong; xs
          }
      }).map(_.flatten)
      edges <- seq(EdgeTypes.map { t =>
        rows[GraphEdge](folder, "graph-edges", s"edgeType=$t", neptune("edges"), Validate.graphEdge)
          .map { xs =>
            counts += s"graph-edges/edgeType=$t" -> xs.size.toLong; xs
          }
      }).map(_.flatten)
      coauthor <- seq(windows.map { w =>
        rows[CoauthorEdge](
          folder,
          "coauthor-edges",
          s"windowLabel=$w",
          neptune("coauthorEdges"),
          Validate.edge
        ).map { xs =>
          counts += s"coauthor-edges/windowLabel=$w" -> xs.size.toLong; xs
        }
      }).map(_.flatten)
      metricTable <- Try(dynamo("tables").arr.head("source")).toEither.left.map(_ =>
        "dynamodb.json has no table"
      )
      metricRows <- seq(windows.map { w =>
        rows[ResearcherMetrics](
          folder,
          "researcher-metrics",
          s"windowLabel=$w",
          metricTable,
          Validate.metrics
        ).map { xs =>
          counts += s"researcher-metrics/windowLabel=$w" -> xs.size.toLong; xs
        }
      }).map(_.flatten)
      graph <- TemplateGraph.build(
        nodes,
        edges,
        coauthor,
        cfg.graph.policyVersion,
        s"file://$folder"
      )
      directory <- InMemoryDirectory.fromNodes(nodes)
      local = LocalStores.inMemory()
      tables <- DynamoTables(local.kv, cfg.dynamo)
      writer = new ArtifactWriter(local.objects, new S3Layout(cfg.storage))
      loaded <- new ResearcherMetricsLoader(tables.metrics, tables.runLedger, writer)
        .load(runId, RowSource.of(metricRows), Some(metricRows.size.toLong))
      renderer <- SemanticRenderer(cfg.vectors)
      embedder = new HashingEmbedder(cfg.vectors.dimension)
      ingestion <- LocalIngestion.run(
        cfg,
        runManifest,
        renderer,
        IngestionSettings(maxUnitsPerIndex = settings.vectorRowsPerType),
        Some(embedder),
        local
      )
      retriever <- SemanticContextRetriever(cfg, local.vectors, local.objects, embedder)
      // The stages see only the loaded windows: the planner's default window, WindowChoice and the
      // graph runner's window allowlist never pick a window whose edges and metrics are absent.
      served = cfg.copy(graph =
        cfg.graph.copy(windows = cfg.graph.windows.filter(w => windows.contains(w.label)))
      )
    yield LocalRuntime(
      RuntimeContext(served, runId, snapshot, er),
      RuntimeStores(
        local.kv,
        graph.store,
        local.vectors,
        local.objects,
        embedder,
        directory,
        new InMemoryMetricRanking(metricRows)
      ),
      retriever,
      LocalRuntimeReport(runId, er, snapshot, windows, counts.result(), ingestion.first.chunks)
    )

  private def str(v: ujson.Value, k: String): Either[String, String] =
    Try(v(k).str).toEither.left.map(_ => s"run manifest has no $k")

  private def same(what: String, found: String, expected: String): Either[String, Unit] =
    Either.cond(
      found == expected,
      (),
      s"$what is $found in the run, $expected in the configuration"
    )

  private def manifest(p: Path, runId: String): Either[String, ujson.Value] =
    for
      j <- Try(ujson.read(Files.readString(p, UTF_8))).toEither.left.map(_ => s"cannot read $p")
      _ <- Either.cond(Try(j("runId").str).toOption.contains(runId), (), s"$p names another run")
    yield j

  /** One partition of one table: strict rows, extra fields dropped, count equal to the manifest's.
    */
  private def rows[A: upickle.default.ReadWriter](
      folder: Path,
      table: String,
      partition: String,
      entry: ujson.Value,
      validate: A => Validate.Errors
  ): Either[String, Vector[A]] =
    val jl = Try(entry("jsonLines")).toOption
    val extra =
      jl.flatMap(j => Try(j("extraFields").arr.map(_.str).toSet).toOption).getOrElse(Set.empty)
    val expected = jl
      .flatMap(j => Try(j("rowsByPartition")(partition).num.toLong).toOption)
      .orElse(Try(entry("rowsByPartition")(partition).num.toLong).toOption)
    val src = RowSource.jsonLinesDir[A](folder.resolve(s"jsonl/$table/$partition"), validate, extra)
    val out = Vector.newBuilder[A]
    var first: Option[String] = None
    var bad = 0L
    src.rows.foreach {
      case Right(a) => out += a
      case Left(e)  => bad += 1; if first.isEmpty then first = Some(e.take(200))
    }
    val xs = out.result()
    if bad > 0 then Left(s"$table/$partition: $bad rows refused, first: ${first.get}")
    else if !expected.contains(xs.size.toLong) then
      Left(
        s"$table/$partition: ${xs.size} rows, the load manifest says ${expected.fold("nothing")(_.toString)}"
      )
    else Right(xs)

  private def seq[A](xs: Vector[Either[String, A]]): Either[String, Vector[A]] =
    xs.foldLeft[Either[String, Vector[A]]](Right(Vector.empty))((acc, x) =>
      acc.flatMap(v => x.map(v :+ _))
    )
