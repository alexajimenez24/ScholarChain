package scholarchain.services.query

import scala.util.Try
import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.{ObjectStore, RowSource}

/** What the workflow needs that has no AWS adapter: the served run's identity, the researcher
  * directory and the metric ranking.
  */
final case class ServingData(
    ctx: RuntimeContext,
    directory: ResearcherDirectory,
    ranking: MetricRanking
)

/** Composition stand-in until a read-model adapter exists: reads one Spark run's JSON-lines export
  * through storage's [[ObjectStore]] port into rag's own in-memory directory and ranking. It
  * follows the checks of rag's `LocalRuntime` (run ID is the manifest's hash, policy and metric
  * versions equal the configuration's, strict rows, counts equal to the load manifests). One bad
  * row or count fails the whole load.
  */
object ServingData:
  private val ManifestUri = "^(s3://.+/)run-manifests/(run-[0-9a-f]+)\\.json$".r
  private val DirectoryLabels =
    Vector(GraphLabels.Researcher, GraphLabels.Topic, GraphLabels.Institution)

  def load(
      cfg: ScholarChainConfig,
      objects: ObjectStore,
      runManifestUri: String
  ): Either[String, ServingData] =
    def json(uri: String) =
      objects
        .getText(uri)
        .flatMap(t => Try(ujson.read(t)).toEither.left.map(_ => s"$uri is not JSON"))
    def field(what: String)(v: => String) =
      Try(v).toEither.left.map(_ => s"the run manifest has no $what")
    def same(what: String, found: String, expected: String) =
      Either.cond(found == expected, (), s"$what is $found in the run, $expected configured")

    runManifestUri match
      case ManifestUri(out, runId) =>
        for
          text <- objects.getText(runManifestUri)
          _ <- Either.cond(
            Try(Ids.run(text)).toOption.contains(runId),
            (),
            s"$runId is not the hash of its run manifest"
          )
          m <- Try(ujson.read(text)).toEither.left.map(_ => "the run manifest is not JSON")
          snapshot <- field("snapshot")(m("snapshot").str)
          er <- field("ER run")(m("inputs")("entityResolution")("runId").str)
          erPolicy <- field("ER policy")(m("inputs")("entityResolution")("policyVersion").str)
          _ <- same("entity-resolution policy", erPolicy, cfg.entityResolution.policyVersion)
          metricVersion <- field("metric version")(m("graph")("metricVersion").str)
          _ <- same("metric version", metricVersion, cfg.graph.metricVersion)
          graphPolicy <- field("graph policy")(m("graph")("policyVersion").str)
          _ <- same("graph policy", graphPolicy, cfg.graph.policyVersion)
          ctx <- Try(RuntimeContext(cfg, runId, snapshot, er)).toEither.left.map(
            _.getMessage
          )
          folder = s"${out}spark/snapshot=$snapshot/run_id=$runId"
          neptune <- json(s"$folder/load-manifests/neptune.json")
          dynamo <- json(s"$folder/load-manifests/dynamodb.json")
          _ <- Either.cond(
            Seq(neptune, dynamo).forall(j => Try(j("runId").str).toOption.contains(runId)),
            (),
            "a load manifest names another run"
          )
          nodeEntry <- Try(neptune("nodes")).toEither.left.map(_ => "neptune.json has no nodes")
          metricEntry <- Try(dynamo("tables")(0)("source")).toEither.left.map(_ =>
            "dynamodb.json has no table"
          )
          nodes <- all(DirectoryLabels)(l =>
            rows[GraphNode](
              objects,
              folder,
              s"graph-nodes/label=$l",
              s"label=$l",
              nodeEntry,
              Validate.graphNode
            )
          )
          metrics <- all(cfg.graph.windows.map(_.label))(w =>
            rows[ResearcherMetrics](
              objects,
              folder,
              s"researcher-metrics/windowLabel=$w",
              s"windowLabel=$w",
              metricEntry,
              Validate.metrics
            )
          )
          directory <- InMemoryDirectory.fromNodes(nodes)
        yield ServingData(ctx, directory, new InMemoryMetricRanking(metrics))
      case _ => Left("the run manifest URI must be s3://.../run-manifests/run-<hex>.json")

  private def all[A](keys: Vector[String])(
      f: String => Either[String, Vector[A]]
  ): Either[String, Vector[A]] =
    keys.foldLeft[Either[String, Vector[A]]](Right(Vector.empty))((acc, k) =>
      acc.flatMap(v => f(k).map(v ++ _))
    )

  private def rows[A: upickle.default.ReadWriter](
      objects: ObjectStore,
      folder: String,
      path: String,
      partition: String,
      entry: ujson.Value,
      validate: A => Validate.Errors
  ): Either[String, Vector[A]] =
    val jsonLines = Try(entry("jsonLines")).toOption
    val extra = jsonLines
      .flatMap(j => Try(j("extraFields").arr.map(_.str).toSet).toOption)
      .getOrElse(Set.empty[String])
    val expected = jsonLines
      .flatMap(j => Try(j("rowsByPartition")(partition).num.toLong).toOption)
      .orElse(Try(entry("rowsByPartition")(partition).num.toLong).toOption)
    def file(uri: String): Either[String, Vector[A]] =
      objects.getText(uri).flatMap { body =>
        val decoded =
          RowSource.jsonLines[A](body.linesIterator, validate, extra).rows.toVector
        decoded.collectFirst { case Left(e) => e } match
          case Some(e) => Left(s"$path: a row was refused: ${e.take(200)}")
          case None    => Right(decoded.collect { case Right(a) => a })
      }
    for
      uris <- objects.list(s"$folder/jsonl/$path")
      out <- all(uris.filter(u => u.endsWith(".json") || u.endsWith(".jsonl")))(file)
      _ <- Either.cond(
        expected.contains(out.size.toLong),
        (),
        s"$path: ${out.size} rows, the load manifest says ${expected.fold("nothing")(_.toString)}"
      )
    yield out
