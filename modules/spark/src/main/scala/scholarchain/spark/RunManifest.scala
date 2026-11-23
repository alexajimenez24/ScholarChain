package scholarchain.spark

import scala.util.Try
import scholarchain.core.{Ids, ScholarChainConfig, SchemaVersions}

/** Command-line arguments of [[SparkPipeline]]. Inputs are named only through the run manifests of
  * the Phase 04 Hadoop run and the Phase 05 entity-resolution run; Spark never takes a raw-data
  * path.
  */
final case class SparkArgs(
    hadoopManifest: String,
    erManifest: String,
    output: String,
    checkpointDir: Option[String]
)

object SparkArgs:
  val Usage: String =
    "usage: SparkPipeline --hadoop-manifest <hadoop out>/run-manifests/<runId>.json " +
      "--er-manifest <er out>/run-manifests/<runId>.json --output <dir> [--checkpoint-dir <dir>]\n" +
      "  --checkpoint-dir is required unless the master is local (local or local[...]): a cluster " +
      "run needs a reliable checkpoint directory on HDFS or S3."

  /** A run on a cluster master must name a reliable checkpoint directory: without one the iterative
    * algorithms fall back to local checkpoints, which are lost when an executor is removed (for
    * example by dynamic allocation on EMR). Only `local` and `local[...]` masters may omit it.
    */
  def checkpointRequirement(master: String, a: SparkArgs): Either[String, Unit] =
    val local = master == "local" || master.startsWith("local[")
    Either.cond(
      local || a.checkpointDir.isDefined,
      (),
      s"--checkpoint-dir is required when the master is not local (master '$master')"
    )
  private val Required = Seq("hadoop-manifest", "er-manifest", "output")
  private val Known = Required :+ "checkpoint-dir"

  /** Strict parsing: every token pair is `--name value`; an unknown, repeated or valueless name is
    * an error, never a silent default.
    */
  def parse(args: Seq[String]): Either[String, SparkArgs] =
    def loop(rest: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      rest match
        case Nil                                  => Right(acc)
        case k :: _ if !k.startsWith("--")        => Left(s"expected an option name, got '$k'")
        case k :: Nil                             => Left(s"$k has no value")
        case k :: _ if !Known.contains(k.drop(2)) => Left(s"unknown option $k")
        case k :: v :: _ if v.startsWith("--") || v.trim.isEmpty => Left(s"$k has no value")
        case k :: _ if acc.contains(k.drop(2))                   => Left(s"$k is repeated")
        case k :: v :: tail => loop(tail, acc + (k.drop(2) -> v))
    loop(args.toList, Map.empty).flatMap { kv =>
      Required.find(k => !kv.contains(k)) match
        case Some(k) => Left(s"--$k is required")
        case None    =>
          Right(
            SparkArgs(
              kv("hadoop-manifest"),
              kv("er-manifest"),
              kv("output"),
              kv.get("checkpoint-dir")
            )
          )
    }

/** The upstream runs a Spark run reads, located only through their run manifests and summaries:
  * Hadoop `<out>/run-manifests/<runId>.json` and `<runId>-summary.json` (stage `J07` gives the
  * export folder) and the er-v1 match job's manifest and summary (field `decisions`).
  */
final case class Upstream(
    snapshot: String,
    hadoopRunId: String,
    hadoopPipeline: String,
    normalization: String,
    exportDir: String,
    erRunId: String,
    erPolicyVersion: String,
    erPolicySha256: String,
    decisionsDir: String
)

object Upstream:
  /** The Hadoop logic and normalization versions whose J07 records [[Schemas]] mirrors. */
  val HadoopPipelines: Set[String] = Set("hadoop-v2")
  val Normalizations: Set[String] = Set("norm-v1")
  val ErStage = "entity-resolution"

  /** Run ID named by a manifest path `.../run-manifests/<runId>.json`. */
  def runIdOf(path: String): Either[String, String] =
    val name = path.split('/').lastOption.getOrElse("")
    val id = name.stripSuffix(".json")
    if name.endsWith(".json") && Ids.isRunId(id) then Right(id)
    else Left(s"'$path' is not a run manifest (<dir>/run-manifests/run-<16 hex>.json)")

  def summaryPathOf(manifestPath: String): String =
    manifestPath.stripSuffix(".json") + "-summary.json"

  private final case class Loaded(id: String, json: ujson.Value)

  private def field[A](what: String)(f: => A): Either[String, A] =
    Try(f).toEither.left.map(_ => s"$what is missing or malformed")

  /** A manifest must hash to the run ID in its file name (core `Ids.run`), so an edited manifest or
    * a manifest copied under another name is refused.
    */
  private def manifest(
      path: String,
      read: String => Either[String, String]
  ): Either[String, Loaded] =
    for
      id <- runIdOf(path)
      text <- read(path)
      hashed <- Try(Ids.run(text)).toEither.left.map(e => s"$path: ${e.getMessage}")
      _ <- Either.cond(hashed == id, (), s"$path hashes to $hashed, not to its name $id")
      json <- Try(ujson.read(text)).toEither.left.map(_ => s"$path is not JSON")
    yield Loaded(id, json)

  /** Locates and cross-checks both upstream runs. `read` returns a file's text or a Left. */
  def locate(
      hadoopManifest: String,
      erManifest: String,
      erPolicyVersion: String,
      read: String => Either[String, String]
  ): Either[String, Upstream] =
    for
      hLoaded <- manifest(hadoopManifest, read)
      hId = hLoaded.id
      hm = hLoaded.json
      pipeline <- field("hadoop manifest pipeline")(hm("pipeline").str)
      _ <- Either.cond(
        HadoopPipelines(pipeline),
        (),
        s"hadoop pipeline '$pipeline' is not one Spark reads (${HadoopPipelines.mkString(", ")})"
      )
      norm <- field("hadoop manifest normalization")(hm("normalization").str)
      _ <- Either.cond(Normalizations(norm), (), s"normalization '$norm' is not supported")
      snapshot <- field("hadoop manifest snapshot")(hm("snapshot").str)
      hsText <- read(summaryPathOf(hadoopManifest))
      hs <- field("hadoop summary")(ujson.read(hsText))
      _ <- Either.cond(
        field("")(hs("runId").str) == Right(hId),
        (),
        s"hadoop summary does not belong to $hId"
      )
      exportDir <- field("hadoop summary stages.J07.output")(hs("stages")("J07")("output").str)
      eLoaded <- manifest(erManifest, read)
      eId = eLoaded.id
      em = eLoaded.json
      stage <- field("entity-resolution manifest stage")(em("stage").str)
      _ <- Either.cond(stage == ErStage, (), s"$erManifest is a '$stage' manifest, not $ErStage")
      upstream <- field("entity-resolution manifest upstreamRunId")(em("upstreamRunId").str)
      _ <- Either.cond(
        upstream == hId,
        (),
        s"entity-resolution run $eId was made from hadoop run $upstream, not $hId"
      )
      eSnap <- field("entity-resolution manifest snapshot")(em("snapshot").str)
      _ <- Either.cond(eSnap == snapshot, (), s"snapshots differ: hadoop $snapshot, er $eSnap")
      policy <- field("entity-resolution manifest policyVersion")(em("policyVersion").str)
      _ <- Either.cond(
        policy == erPolicyVersion,
        (),
        s"entity-resolution policy '$policy' is not the configured '$erPolicyVersion'"
      )
      policySha <- field("entity-resolution manifest policySha256")(em("policySha256").str)
      esText <- read(summaryPathOf(erManifest))
      es <- field("entity-resolution summary")(ujson.read(esText))
      _ <- Either.cond(
        field("")(es("runId").str) == Right(eId),
        (),
        s"entity-resolution summary does not belong to $eId"
      )
      decisions <- field("entity-resolution summary decisions")(es("decisions").str)
    yield Upstream(snapshot, hId, pipeline, norm, exportDir, eId, policy, policySha, decisions)

/** The Spark run manifest. Its canonical JSON hashes to the run ID (core `Ids.run`), so the same
  * inputs, configuration and code version always give the same run ID and the same output folder;
  * it holds no timestamp and no output path. Every parameter that changes a result is in it: the
  * upstream run IDs and folders, the whole `graph` section of the config (windows, policy and
  * metric versions, every limit, seed and iteration bound), the vector index names and the code
  * version [[LogicVersion]] (bumped on any logic change).
  */
object SparkRunManifest:
  val Stage = "spark-analytics"
  val LogicVersion = "spark-v2"

  /** Core record versions Spark reads and writes (the `hadoop` local `author-paper-v1` included).
    */
  val ReadSchemas: Vector[String] = Vector(
    SchemaVersions.Work,
    SchemaVersions.Mention,
    "author-paper-v1",
    SchemaVersions.Pair,
    SchemaVersions.Topic,
    SchemaVersions.Institution,
    SchemaVersions.Venue,
    SchemaVersions.Match
  )
  val WriteSchemas: Vector[String] = Vector(
    SchemaVersions.GraphNode,
    SchemaVersions.GraphEdge,
    SchemaVersions.Edge,
    SchemaVersions.Metrics,
    SchemaVersions.Evidence,
    "reject-record"
  )

  def build(up: Upstream, cfg: ScholarChainConfig): ujson.Obj =
    ujson.Obj(
      "stage" -> Stage,
      "logic" -> LogicVersion,
      "snapshot" -> up.snapshot,
      "inputs" -> ujson.Obj(
        "hadoop" -> ujson.Obj(
          "runId" -> up.hadoopRunId,
          "pipeline" -> up.hadoopPipeline,
          "normalization" -> up.normalization,
          "export" -> up.exportDir
        ),
        "entityResolution" -> ujson.Obj(
          "runId" -> up.erRunId,
          "policyVersion" -> up.erPolicyVersion,
          "policySha256" -> up.erPolicySha256,
          "decisions" -> up.decisionsDir
        )
      ),
      "graph" -> upickle.default.writeJs(cfg.graph),
      "vectorIndexes" -> upickle.default.writeJs(cfg.vectors.indexes),
      "readSchemas" -> upickle.default.writeJs(ReadSchemas),
      "writeSchemas" -> upickle.default.writeJs(WriteSchemas)
    )

  def runId(manifest: ujson.Value): String = Ids.run(manifest.render())
