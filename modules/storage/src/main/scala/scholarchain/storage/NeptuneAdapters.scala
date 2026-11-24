package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.AwsHttp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

private object NeptuneUrl:
  /** `https://{endpoint}:{port}`, or the test override (a local stub). */
  def base(cfg: NeptuneConfig, override_ : Option[String]): Either[String, String] =
    override_.map(_.stripSuffix("/")) match
      case Some(o)                           => Right(o)
      case None if cfg.endpoint.trim.isEmpty => Left("neptune.endpoint is not configured")
      case None                              => Right(s"https://${cfg.endpoint}:${cfg.port}")

/** Neptune openCypher over HTTPS with IAM SigV4 (signing name `neptune-db`), read-only.
  *
  * It accepts only a [[GraphQuery]] built by [[GraphTemplates]]; the adapter re-checks that the
  * query is exactly an allowlisted template with exactly its declared parameters and that the text
  * has no write keyword. It sends `query` and `parameters` as separate form fields, so a user value
  * is never part of the query text. The runtime IAM role must grant only
  * `neptune-db:ReadDataViaQuery` (infra-engineer, Phase 12); this class has no write path either.
  *
  * Request format:
  * https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-parameterized-queries.html
  */
final class NeptuneGraphStore(
    http: AwsHttp,
    neptune: NeptuneConfig,
    endpointOverride: Option[String] = None
) extends GraphStore:
  def run(q: GraphQuery): Either[String, Vector[ujson.Obj]] =
    for
      _ <- Either.cond(
        GraphTemplates.isAllowlisted(q),
        (),
        s"query ${q.templateId} is not an allowlisted template"
      )
      _ <- ReadOnlyCypher
        .violation(q.cypher)
        .fold(Right(()))(v => Left(s"template ${q.templateId}: $v"))
      base <- NeptuneUrl.base(neptune, endpointOverride)
      form =
        s"query=${URLEncoder.encode(q.cypher, UTF_8)}&parameters=${URLEncoder.encode(q.parameters.render(), UTF_8)}"
      r <- http.send(
        "POST",
        s"$base/openCypher",
        "neptune-db",
        form.getBytes(UTF_8),
        Map("content-type" -> "application/x-www-form-urlencoded")
      )
      rows <-
        if r.ok then
          scala.util
            .Try(ujson.read(r.body)("results").arr.toVector.map(v => ujson.Obj.from(v.obj)))
            .toEither
            .left
            .map(_ => s"Neptune ${q.templateId}: reply without a results array")
        else Left(s"Neptune ${q.templateId} HTTP ${r.status}: ${r.body.take(200)}")
    yield rows

/** Status of one bulk-load job (`overallStatus` of the Get-Status reply). */
final case class LoaderStatus(
    status: String,
    totalRecords: Long,
    totalDuplicates: Long,
    parsingErrors: Long,
    datatypeMismatchErrors: Long,
    insertErrors: Long
):
  def errors: Long = parsingErrors + datatypeMismatchErrors + insertErrors
  def completedClean: Boolean = status == "LOAD_COMPLETED" && errors == 0

/** Neptune bulk loader (openCypher CSV). Loader role only: the runtime services never receive this
  * class. The load is idempotent because IDs are the Spark IDs and `mode = AUTO` resumes or starts
  * a load of the same source, `updateSingleCardinalityProperties` makes a changed property of an
  * existing ID replace the stored one, and relationship IDs are supplied (`userProvidedEdgeIds`),
  * so a replay of the same input never duplicates a node or relationship. It does NOT clean up a
  * reload under a new run: nodes and edges of the earlier run that the new run no longer contains
  * stay in the graph. Invariant: one served run per Neptune cluster (and per vector index); the
  * human cleans or rebuilds the cluster before a new run is loaded. Reply and parameters:
  * https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html
  */
final class NeptuneBulkLoader(
    http: AwsHttp,
    neptune: NeptuneConfig,
    region: String,
    loaderRoleArn: String,
    endpointOverride: Option[String] = None
):
  private val RoleArn = "^arn:aws:iam::\\d{12}:role/[A-Za-z0-9+=,.@_/-]{1,512}$".r
  private val LoadId = "^[A-Za-z0-9-]{8,64}$".r

  def start(sourcePrefix: String): Either[String, String] =
    for
      _ <- Either.cond(
        sourcePrefix.startsWith("s3://") && sourcePrefix.endsWith("/"),
        (),
        "loader source must be an s3:// prefix ending in /"
      )
      _ <- Either.cond(RoleArn.matches(loaderRoleArn), (), "loader role is not an IAM role ARN")
      base <- NeptuneUrl.base(neptune, endpointOverride)
      body = ujson.Obj(
        "source" -> sourcePrefix,
        "format" -> "opencypher",
        "iamRoleArn" -> loaderRoleArn,
        "region" -> region,
        "mode" -> "AUTO",
        "failOnError" -> "TRUE",
        "parallelism" -> "MEDIUM",
        "userProvidedEdgeIds" -> "TRUE",
        "updateSingleCardinalityProperties" -> "TRUE",
        "queueRequest" -> "TRUE"
      )
      reply <- http.json(s"$base/loader", "neptune-db", body)
      id <- scala.util
        .Try(reply("payload")("loadId").str)
        .toEither
        .left
        .map(_ => "loader reply without payload.loadId")
    yield id

  def status(loadId: String): Either[String, LoaderStatus] =
    for
      _ <- Either.cond(LoadId.matches(loadId), (), "malformed load ID")
      base <- NeptuneUrl.base(neptune, endpointOverride)
      r <- http.send("GET", s"$base/loader/$loadId", "neptune-db", Array.emptyByteArray)
      _ <- Either.cond(r.ok, (), s"loader status HTTP ${r.status}: ${r.body.take(200)}")
      s <- scala.util
        .Try {
          val o = ujson.read(r.body)("payload")("overallStatus")
          def n(k: String) = o.obj.get(k).map(_.num.toLong).getOrElse(0L)
          LoaderStatus(
            o("status").str,
            n("totalRecords"),
            n("totalDuplicates"),
            n("parsingErrors"),
            n("datatypeMismatchErrors"),
            n("insertErrors")
          )
        }
        .toEither
        .left
        .map(_ => "loader status reply without overallStatus")
    yield s

/** What [[NeptuneLoadWriter]] wrote: the loader source prefix and the stored objects. */
final case class NeptuneLoadResult(
    sourcePrefix: String,
    csvObjects: Vector[StoredObject],
    loadManifest: StoredObject,
    storageManifestUri: String,
    outcomes: Vector[WriteOutcome]
)

/** Writes a [[NeptuneLoadPlan]] to the graph zone with create-only puts and content hashes.
  *
  * CSV files go to `graph/neptune-load/run_id={run}/` (the loader source prefix holds nothing but
  * CSV); the load manifest and the storage manifest go to `graph/neptune-load-manifest/run_id=...`.
  * A replay with the same input is `Unchanged` for every object; a changed object is a conflict and
  * nothing is overwritten.
  */
final class NeptuneLoadWriter(writer: ArtifactWriter, layout: S3Layout):
  val CsvDataset = "neptune-load"
  val ManifestDataset = "neptune-load-manifest"
  val LoadManifestName = "neptune-load.json"

  def write(plan: NeptuneLoadPlan): Either[ArtifactError, NeptuneLoadResult] =
    val m = plan.manifest
    val sources = Vector(SourceRef(SourceType.SCHOLARCHAIN, s"spark:${m.runId}", m.snapshot))
    if plan.files.isEmpty then Left(ArtifactError.Failure("nothing to load: the plan has no files"))
    else
      for
        prefix <- layout
          .runKey(Zone.Graph, CsvDataset, m.runId, "x")
          .map(_.stripSuffix("x"))
          .left
          .map(ArtifactError.Failure(_))
        csv <- plan.files.foldLeft[Either[ArtifactError, Vector[(StoredObject, WriteOutcome)]]](
          Right(Vector.empty)
        ) { (acc, f) =>
          acc.flatMap(done =>
            writer
              .write(Zone.Graph, CsvDataset, m.runId, f.name, f.bytes, "text/csv", sources)
              .map(done :+ _)
          )
        }
        lm <- writer.write(
          Zone.Graph,
          ManifestDataset,
          m.runId,
          LoadManifestName,
          plan.manifestJson.getBytes(UTF_8),
          "application/json",
          sources
        )
        sm = StorageManifest(
          StorageManifest.Version,
          m.runId,
          "neptune-load",
          Zone.Graph.prefix,
          ManifestDataset,
          Some(m.policyVersion),
          csv.map(_._1) :+ lm._1
        )
        smUri <- layout
          .manifestUri(Zone.Graph, ManifestDataset, m.runId)
          .left
          .map(ArtifactError.Failure(_))
        smOut <- writer.writeManifest(sm)
      yield NeptuneLoadResult(
        prefix,
        csv.map(_._1),
        lm._1,
        smUri,
        csv.map(_._2) :+ lm._2 :+ smOut._2
      )
