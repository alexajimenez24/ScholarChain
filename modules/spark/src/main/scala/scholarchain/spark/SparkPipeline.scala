package scholarchain.spark

import java.nio.charset.StandardCharsets

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.api.java.UDF4
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import scala.util.Try
import scholarchain.core.{
  ConfigLoader,
  RejectRecord,
  ScholarChainConfig,
  SchemaVersions,
  TimeWindow
}

/** Phase 06 driver: jobs 1 to 11 in one Spark application.
  *
  * Inputs come only from the Phase 04 Hadoop J07 export and the Phase 05 er-v1 decisions, located
  * through their run manifests ([[Upstream.locate]]); raw datasets and the gold set are never read.
  * The Spark run manifest ([[SparkRunManifest]]) hashes to the run ID, so a rerun with the same
  * inputs, config and code writes the same folder.
  *
  * Layout under `--output`:
  *   - `run-manifests/<runId>.json` (written first) and `<runId>-summary.json` (counts, invariant
  *     failures, outputs, Spark settings and stage timings, written last);
  *   - `spark/snapshot=<d>/run_id=<runId>/<table>/` (see [[Export]]) and `load-manifests/`.
  *
  * Exit codes: 0 done with every invariant at 0; 2 usage, config or upstream error (nothing
  * written); 3 outputs written but an invariant failed (the summary has status INVARIANT_FAILURES,
  * and loaders must not load the run).
  */
object SparkPipeline:
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  /** Local property naming the pipeline stage of every Spark job (read by listeners). */
  val StageProperty = "scholarchain.stage"

  final case class Outcome(
      runId: String,
      base: String,
      manifestPath: String,
      summaryPath: String,
      summary: ujson.Obj,
      invariantFailures: Long
  )

  def main(args: Array[String]): Unit =
    val a =
      SparkArgs.parse(args.toSeq).fold(e => fail(s"usage error: $e\n${SparkArgs.Usage}"), identity)
    val cfg = ConfigLoader.load().fold(e => fail(s"config error: $e"), identity)
    val spark = SparkSession
      .builder()
      .appName("scholarchain-spark")
      .config("spark.cleaner.referenceTracking.cleanCheckpoints", "true")
      .getOrCreate()
    val code =
      try
        SparkArgs.checkpointRequirement(spark.sparkContext.master, a) match
          case Left(e) =>
            System.err.println(s"usage error: $e\n${SparkArgs.Usage}")
            2
          case Right(()) =>
            a.checkpointDir.foreach(spark.sparkContext.setCheckpointDir)
            run(spark, a, cfg) match
              case Left(e) =>
                System.err.println(s"upstream error: $e")
                2
              case Right(o) =>
                log.info(
                  s"[scholarchain] spark run ${o.runId}: ${o.invariantFailures} invariant failures"
                )
                if o.invariantFailures == 0 then 0 else 3
      finally spark.stop()
    if code != 0 then sys.exit(code)

  private def fail(msg: String): Nothing =
    System.err.println(msg)
    sys.exit(2)

  // Files through the Hadoop FileSystem API (local paths, HDFS or S3 alike) ----------------------

  def readText(spark: SparkSession, path: String): Either[String, String] =
    Try {
      val p = new Path(path)
      val in = p.getFileSystem(spark.sparkContext.hadoopConfiguration).open(p)
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    }.toEither.left.map(_ => s"cannot read $path")

  def writeText(spark: SparkSession, path: String, text: String): Unit =
    val p = new Path(path)
    val out = p.getFileSystem(spark.sparkContext.hadoopConfiguration).create(p, true)
    try out.write(text.getBytes(StandardCharsets.UTF_8))
    finally out.close()

  private def pathExists(spark: SparkSession, path: String): Boolean =
    val p = new Path(path)
    p.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(p)

  /** The `part-*` files of an output folder (named outputs such as `rejects-v1/` are never read).
    * An absent folder has no files: a lazy MapReduce output creates no folder for zero rows.
    */
  private def partFiles(spark: SparkSession, dir: String): Seq[String] =
    val pattern = new Path(s"${dir.stripSuffix("/")}/part-*")
    Option(pattern.getFileSystem(spark.sparkContext.hadoopConfiguration).globStatus(pattern)).toSeq
      .flatMap(_.toSeq)
      .filter(_.isFile)
      .map(_.getPath.toString)
      .sorted

  private val RejectSchema =
    StructType(Seq(StructField("input", StringType), StructField("reason", StringType)))

  private def read(
      spark: SparkSession,
      dirs: Seq[String],
      schema: StructType,
      required: Seq[String]
  ): Parsed =
    val files = dirs.flatMap(partFiles(spark, _))
    if files.nonEmpty then Inputs.readFiles(spark, files, schema, required)
    else
      def empty(s: StructType) = spark.createDataFrame(java.util.List.of[Row](), s)
      Parsed(empty(schema), empty(RejectSchema))

  // The run ---------------------------------------------------------------------------------------

  def run(spark: SparkSession, a: SparkArgs, cfg: ScholarChainConfig): Either[String, Outcome] =
    for
      up <- Upstream.locate(
        a.hadoopManifest,
        a.erManifest,
        cfg.entityResolution.policyVersion,
        readText(spark, _)
      )
      _ <- Either.cond(
        pathExists(spark, s"${up.exportDir}/_SUCCESS"),
        (),
        s"hadoop J07 export ${up.exportDir} has no _SUCCESS"
      )
      _ <- Either.cond(
        pathExists(spark, s"${up.decisionsDir}/_SUCCESS"),
        (),
        s"entity-resolution decisions ${up.decisionsDir} have no _SUCCESS"
      )
      indexes <- Summaries.indexes(cfg.vectors.indexes)
    yield execute(spark, a, cfg, up, indexes)

  private def execute(
      spark: SparkSession,
      a: SparkArgs,
      cfg: ScholarChainConfig,
      up: Upstream,
      indexes: Map[String, String]
  ): Outcome =
    val g = cfg.graph
    val manifest = SparkRunManifest.build(up, cfg)
    val runId = SparkRunManifest.runId(manifest)
    val run = RunInfo(runId, up.snapshot, g)
    val out = a.output.stripSuffix("/")
    val base = s"$out/spark/snapshot=${up.snapshot}/run_id=$runId"
    val manifestPath = s"$out/run-manifests/$runId.json"
    val summaryPath = s"$out/run-manifests/$runId-summary.json"
    writeText(spark, manifestPath, manifest.render(indent = 2))

    val started = System.nanoTime()
    val timings = ujson.Obj()
    var current = ""
    var currentAt = started
    def stage(name: String): Unit =
      val now = System.nanoTime()
      if current.nonEmpty then
        timings(current) = ujson.Num(math.round((now - currentAt) / 1e7) / 100.0)
      current = name
      currentAt = now
      spark.sparkContext.setLocalProperty(StageProperty, name)
      log.info(s"[scholarchain] stage $name")

    val report = ujson.Obj("runId" -> runId, "snapshot" -> up.snapshot)

    // Inputs ------------------------------------------------------------------------------------
    stage("read")
    val inputs = ujson.Obj()
    def load(kind: String, dirs: Seq[String], schema: StructType, req: Seq[String], upRun: String) =
      val p = read(spark, dirs, schema, req)
      val valid = p.valid.persist()
      val r = ujson.Obj()
      Report.put(r, "valid", valid.count())
      Report.put(r, "rejected", p.rejected.count())
      Report.put(r, "partitions", valid.rdd.getNumPartitions.toLong)
      // Integrity: every record must belong to the upstream run named by its manifest.
      Report.put(
        r,
        "invariant_provenanceRunIdMismatch",
        valid.filter(!(col("provenance.runId") <=> lit(upRun))).count()
      )
      inputs(kind) = r
      (valid, p.rejected.withColumn("stage", lit(s"spark-read-$kind")))
    def j07(kind: String) = Seq(s"${up.exportDir}/$kind")
    val h = up.hadoopRunId
    val (works, wRej) = load("works", j07("works"), Schemas.work, Schemas.Required.work, h)
    val (mentions, mRej) =
      load("mentions", j07("mentions"), Schemas.mention, Schemas.Required.mention, h)
    val (authorPapers, apRej) =
      load(
        "authorpapers",
        j07("authorpapers"),
        Schemas.authorPaper,
        Schemas.Required.authorPaper,
        h
      )
    val (pairs, pRej) = load("pairs", j07("pairs"), Schemas.pair, Schemas.Required.pair, h)
    val (topics, tRej) = load("topics", j07("topics"), Schemas.topic, Schemas.Required.topic, h)
    val (institutions, iRej) =
      load(
        "institutions",
        j07("institutions"),
        Schemas.institution,
        Schemas.Required.institution,
        h
      )
    val (venues, vRej) = load("venues", j07("venues"), Schemas.venue, Schemas.Required.venue, h)
    val outcomes = Seq("accepted", "rejected", "unresolved", "conflict")
    val (decisions, dRej) = load(
      "decisions",
      outcomes.map(o => s"${up.decisionsDir}/$o"),
      Schemas.decision,
      Schemas.Required.decision,
      up.erRunId
    )
    Report.put(
      inputs,
      "invariant_decisionsOfAnotherPolicy",
      decisions.filter(!(col("policyVersion") <=> lit(up.erPolicyVersion))).count()
    )
    report("inputs") = inputs
    val sources =
      Sources(works, mentions, authorPapers, pairs, topics, institutions, venues, decisions)

    // Job 1 --------------------------------------------------------------------------------------
    stage("job1-canonical")
    val canonical = Canonical.build(sources, run, up.erPolicyVersion)
    report("job1") = canonical.report

    // Job 2 --------------------------------------------------------------------------------------
    stage("job2-pairs")
    val pairResult = CoauthorGraph.researcherPairs(
      pairs,
      canonical.mentionToResearcher,
      canonical.workMap,
      canonical.authored,
      g.maxTeamSizeForPairs
    )
    report("job2") = pairResult.report

    // Jobs 3 to 8 and path evidence, per window -------------------------------------------------
    def tableUri(name: String) = s"$base/$name"
    val windowReports = ujson.Arr()
    val results = g.windows.map { w =>
      stage(s"window-${w.label}")
      val r = WindowAnalytics.run(canonical.authored, pairResult.pairWorks, w, run)
      stage(s"paths-${w.label}")
      val paths = pathEvidence(r, w, run)
      Report.put(r.report, "pathPairsRequested", paths.count())
      Report.put(r.report, "pathPairsFound", paths.filter(col("found")).count())
      // Integrity: a found path has one coauthor edge ID per hop.
      Report.put(
        r.report,
        "invariant_pathHopEdgeMissing",
        paths
          .filter(
            col("found") && (size(col("hopEdgeIds")) =!= col("distance") ||
              exists(col("hopEdgeIds"), _.isNull))
          )
          .count()
      )
      val hist = paths
        .filter(col("found"))
        .groupBy("distance")
        .count()
        .orderBy("distance")
        .collect() // at most pathMaxDepth rows
      r.report("pathDistanceHistogram") =
        ujson.Obj.from(hist.map(x => x.get(0).toString -> ujson.Num(x.getLong(1).toDouble)))
      windowReports.arr += r.report
      w -> (r, paths)
    }
    report("windows") = windowReports

    // Job 9 --------------------------------------------------------------------------------------
    stage("job9-temporal")
    val byWindow = results.toMap
    val (comparisons, skipped) = Temporal.plan(g.windows)
    val temporalReport = ujson.Obj()
    temporalReport("skipped") = ujson.Arr(
      skipped.map(s =>
        ujson.Obj("first" -> s.first.label, "second" -> s.second.label, "reason" -> s.reason)
      )*
    )
    val comparisonTables = comparisons.map { c =>
      val df =
        Lineage.cut(Temporal.compare(byWindow(c.from)._1.metrics, byWindow(c.to)._1.metrics, c))
      val cr = ujson.Obj("from" -> c.from.label, "to" -> c.to.label)
      Report.put(cr, "researchers", df.count())
      Report.put(cr, "presentBoth", df.filter(col("presentBefore") && col("presentAfter")).count())
      Report.put(cr, "componentRetained", df.filter(col("componentRetained")).count())
      Report.put(cr, "communityRetained", df.filter(col("communityRetained")).count())
      cr("limitations") = ujson.Arr(c.limitations.map(ujson.Str(_))*)
      temporalReport(s"${c.from.label}_to_${c.to.label}") = cr
      Export.comparisons(df, c, run, tableUri("researcher-metrics"))
    }
    report("job9") = temporalReport

    // Job 10 -------------------------------------------------------------------------------------
    stage("job10-summaries")
    val ctx = Summaries.Context(
      canonical.researchers,
      canonical.works,
      canonical.authored,
      mentions,
      canonical.topics,
      canonical.institutions,
      run,
      indexes,
      tableUri
    )
    val docs = Lineage.cut(
      results
        .flatMap { (w, rp) =>
          val (r, paths) = rp
          Seq(
            Summaries.researcherProfiles(r, ctx),
            Summaries.communityProfiles(r, ctx),
            Summaries.pathSummaries(paths, r, ctx),
            Summaries.topicSummaries(w, ctx)
          )
        }
        .reduce(_.unionByName(_))
    )
    val summaryReport = ujson.Obj()
    docs
      .groupBy("docType")
      .count()
      .collect() // four doc types
      .foreach(r => Report.put(summaryReport, r.getString(0), r.getLong(1)))
    Report.put(
      summaryReport,
      "invariant_duplicateChunkIds",
      docs.count() - docs.select("chunkId").distinct().count()
    )
    Report.put(
      summaryReport,
      "invariant_emptyText",
      docs.filter(col("text").isNull || length(trim(col("text"))) === 0).count()
    )
    report("job10") = summaryReport

    // Job 11 -------------------------------------------------------------------------------------
    stage("job11-export")
    def union(dfs: Iterable[DataFrame]) = dfs.reduce(_.unionByName(_))
    val windowed = results.map(_._2._1)
    val coauthorEdges = union(windowed.map(_.edges))
    val nodes = Lineage.cut(Export.graphNodes(canonical, run))
    val edges = Lineage.cut(Export.graphEdges(canonical, coauthorEdges, run))
    val exportReport = ujson.Obj()
    Report.put(
      exportReport,
      "invariant_graphNodeDuplicateIds",
      nodes.count() - nodes.select("nodeId").distinct().count()
    )
    Report.put(
      exportReport,
      "invariant_graphEdgeDuplicateIds",
      edges.count() - edges.select("edgeId").distinct().count()
    )
    val nodeIds = nodes.select(col("nodeId"))
    Report.put(
      exportReport,
      "invariant_graphEdgeEndpointMissing",
      edges.join(nodeIds.toDF("fromId"), Seq("fromId"), "left_anti").count() +
        edges.join(nodeIds.toDF("toId"), Seq("toId"), "left_anti").count()
    )
    val rejects = union(
      Seq(wRej, mRej, apRej, pRej, tRej, iRej, vRej, dRej) ++ Seq(
        canonical.authoredRejects.withColumn("stage", lit("spark-job1-authored")),
        pairResult.rejects.withColumn("stage", lit("spark-job2-pairs"))
      )
    ).select(coreReject(col("stage"), col("reason"), col("input"), lit(runId)).as("r"))
      .select("r.*")
    def ev(name: String, df: DataFrame, parts: String*) =
      Export.write(df, base, name, SchemaVersions.Evidence, parts)
    val tables = Seq(
      Export.write(nodes, base, "graph-nodes", SchemaVersions.GraphNode, Seq("label")),
      Export.write(edges, base, "graph-edges", SchemaVersions.GraphEdge, Seq("edgeType")),
      Export.write(coauthorEdges, base, "coauthor-edges", SchemaVersions.Edge, Seq("windowLabel")),
      Export.write(
        union(windowed.map(_.metrics)),
        base,
        "researcher-metrics",
        SchemaVersions.Metrics,
        Seq("windowLabel")
      ),
      ev(
        "components",
        union(
          windowed.map(r =>
            Export.components(r, run, tableUri(s"components/windowLabel=${r.window.label}"))
          )
        ),
        "windowLabel"
      ),
      ev(
        "communities",
        union(
          windowed.map(r =>
            Export.communities(r, run, tableUri(s"communities/windowLabel=${r.window.label}"))
          )
        ),
        "windowLabel",
        "recordKind"
      ),
      ev(
        "path-evidence",
        union(results.map { (w, rp) =>
          Export.pathEvidence(rp._2, w, run, tableUri(s"path-evidence/windowLabel=${w.label}"))
        }),
        "windowLabel"
      ),
      ev("semantic-summaries", docs, "docType", "windowLabel"),
      Export.write(rejects, base, "rejects", "reject-record", Seq("stage"))
    ) ++ (if comparisonTables.isEmpty then Nil
          else Seq(ev("metric-comparisons", union(comparisonTables), "comparison")))
    val byName = tables.map(t => t.name -> t).toMap
    exportReport("tables") = ujson.Obj.from(tables.map(t => t.name -> t.json))
    report("job11") = exportReport
    val manifests = Export.loadManifests(
      byName,
      runId,
      up.snapshot,
      indexes,
      cfg.vectors.dimension,
      g.windows.map(_.label),
      manifestPath,
      summaryPath
    )
    manifests.foreach((k, m) =>
      writeText(spark, s"$base/load-manifests/$k.json", m.render(indent = 2))
    )

    // Summary --------------------------------------------------------------------------------------
    stage("")
    val failures = invariants(report)
    val total = failures.map(_._2).sum
    val conf = spark.conf
    val summary = ujson.Obj(
      "runId" -> runId,
      "snapshot" -> up.snapshot,
      "status" -> (if total == 0 then "SUCCEEDED" else "INVARIANT_FAILURES"),
      "manifest" -> manifestPath,
      "output" -> base,
      "loadManifests" -> ujson.Obj.from(
        manifests.keys.toSeq.sorted.map(k => k -> ujson.Str(s"$base/load-manifests/$k.json"))
      ),
      "invariantFailures" -> total.toDouble,
      "invariantsNonZero" -> ujson.Obj.from(
        failures.filter(_._2 > 0).map((k, v) => k -> ujson.Num(v.toDouble))
      ),
      "invariantsChecked" -> failures.size.toDouble,
      "spark" -> ujson.Obj(
        "master" -> spark.sparkContext.master,
        "version" -> spark.version,
        "shufflePartitions" -> conf.get("spark.sql.shuffle.partitions"),
        "adaptiveExecution" -> conf.get("spark.sql.adaptive.enabled"),
        "checkpoints" -> (if spark.sparkContext.getCheckpointDir.isDefined then "reliable"
                          else "local")
      ),
      "stageSeconds" -> timings,
      "report" -> report
    )
    writeText(spark, summaryPath, summary.render(indent = 2))
    Outcome(runId, base, manifestPath, summaryPath, summary, total)

  /** Path evidence of one window: every pair of the window's sampled betweenness sources (the same
    * deterministic sample WindowAnalytics uses), searched up to `pathMaxDepth` hops.
    */
  private def pathEvidence(r: WindowAnalytics.Result, w: TimeWindow, run: RunInfo): DataFrame =
    val g = run.graph
    val connected =
      r.metrics.filter(col("distinctCoauthorCount") > 0).select(col("researcherId").as("id"))
    val sources =
      GraphAlgorithms.sampleSources(connected, g.betweennessSampleSources, g.betweennessSeed)
    val pairs = sources
      .crossJoin(sources.select(col("src").as("tgt")))
      .filter(col("src") < col("tgt"))
    Lineage.cut(
      Export.withPathIds(Paths.evidence(r.edges, pairs, g.pathMaxDepth, w), w, g.policyVersion)
    )

  private val RejectType = StructType(
    Seq("stage", "reason", "input", "runId").map(StructField(_, StringType))
  )

  /** core `RejectRecord.of` applied per row, so the truncation lengths live only in core. A null
    * reason or input stays null.
    */
  private val coreReject: UserDefinedFunction =
    val f = new UDF4[String, String, String, String, Row] {
      def call(stage: String, reason: String, input: String, runId: String): Row =
        val r = RejectRecord.of(
          stage,
          Option(reason).getOrElse(""),
          Option(input).getOrElse(""),
          runId
        )
        Row(
          r.stage,
          Option(reason).map(_ => r.reason).orNull,
          Option(input).map(_ => r.input).orNull,
          r.runId
        )
    }
    udf(f, RejectType)

  /** Every `invariant_*` count in the report, with its JSON path. */
  def invariants(v: ujson.Value, path: String = ""): Seq[(String, Long)] = v match
    case o: ujson.Obj =>
      o.value.toSeq.flatMap { (k, x) =>
        val p = if path.isEmpty then k else s"$path.$k"
        x match
          case ujson.Num(n) if k.startsWith("invariant_") => Seq(p -> n.toLong)
          case _                                          => invariants(x, p)
      }
    case a: ujson.Arr =>
      a.value.toSeq.zipWithIndex.flatMap((x, i) =>
        val label = x.objOpt.flatMap(_.get("window")).flatMap(_.strOpt).getOrElse(i.toString)
        invariants(x, s"$path[$label]")
      )
    case _ => Nil
