package scholarchain.storage

import scholarchain.core.*
import upickle.default.ReadWriter
import scala.util.Try

/** One Spark run in ordinary S3: `<out>/run-manifests/run-<hex>.json` and the run folder
  * `<out>/spark/snapshot=<snapshot>/run_id=<run>/` with `load-manifests/` and `jsonl/`.
  */
final case class SparkRun(runId: String, snapshot: String, folder: String)

object SparkRun:
  private val ManifestName = "^(run-[0-9a-f]+)\\.json$".r

  /** Finds the one run manifest under `sparkOutput` (the Spark job's `--output`, `s3://` or
    * `s3a://`). The run ID must be the hash of that manifest, and its graph policy and metric
    * versions must equal the configuration's.
    */
  def open(
      cfg: ScholarChainConfig,
      objects: ObjectStore,
      sparkOutput: String
  ): Either[String, SparkRun] =
    val out = sparkOutput.replaceFirst("^s3a://", "s3://").stripSuffix("/")
    def same(what: String, found: String, expected: String) =
      Either.cond(found == expected, (), s"$what is $found in the run, $expected configured")
    for
      uris <- objects.list(s"$out/run-manifests")
      found = uris.flatMap(u =>
        u.substring(u.lastIndexOf('/') + 1) match
          case ManifestName(id) => Some(u -> id)
          case _                => None
      )
      one <- Either.cond(
        found.size == 1,
        found.headOption,
        s"expected exactly one run manifest under $out/run-manifests, found ${found.size}"
      )
      (uri, runId) = one.get
      text <- objects.getText(uri)
      _ <- Either.cond(Ids.run(text) == runId, (), s"$runId is not the hash of its run manifest")
      fields <- Try {
        val m = ujson.read(text)
        (m("snapshot").str, m("graph")("policyVersion").str, m("graph")("metricVersion").str)
      }.toEither.left.map(_ => "the run manifest lacks its snapshot or graph versions")
      (snapshot, policy, metricVersion) = fields
      _ <- same("graph policy", policy, cfg.graph.policyVersion)
      _ <- same("metric version", metricVersion, cfg.graph.metricVersion)
    yield SparkRun(runId, snapshot, s"$out/spark/snapshot=$snapshot/run_id=$runId")

  /** A load manifest of the run (`neptune`, `dynamodb` or `s3vectors`); it must name this run. */
  def loadManifest(
      objects: ObjectStore,
      run: SparkRun,
      name: String
  ): Either[String, (String, ujson.Value)] =
    for
      text <- objects.getText(s"${run.folder}/load-manifests/$name.json")
      json <- Try(ujson.read(text)).toEither.left.map(_ => s"$name.json is not JSON")
      _ <- Either.cond(
        Try(json("runId").str).toOption.contains(run.runId),
        (),
        s"$name.json names another run"
      )
    yield (text, json)

  /** The row count and the non-core fields of one table entry of a load manifest. */
  def table(what: String, entry: => ujson.Value): Either[String, (Long, Set[String])] =
    Try {
      val e = entry
      (e("rows").num.toLong, e("jsonLines")("extraFields").arr.map(_.str).toSet)
    }.toEither.left.map(_ => s"the load manifest has no usable $what entry")

  /** The JSON lines of one exported table: Spark's uncompressed `part-*.json` files under
    * `<folder>/jsonl/<table>/`, read one file at a time through the object store. A prefix that
    * cannot be listed is an input problem; a file that cannot be read is one error row, which the
    * row-count checks and the loaders reject.
    */
  def rows[A: ReadWriter](
      objects: ObjectStore,
      run: SparkRun,
      table: String,
      validate: A => Validate.Errors = (_: A) => Vector.empty,
      dropFields: Set[String] = Set.empty
  ): RowSource[A] =
    new RowSource[A]:
      private lazy val files: Either[String, Vector[String]] =
        objects
          .list(s"${run.folder}/jsonl/$table")
          .map(_.filter { u =>
            val n = u.substring(u.lastIndexOf('/') + 1)
            n.startsWith("part-") && n.endsWith(".json")
          })
      override def problem: Option[String] = files.left.toOption
      def rows: Iterator[Either[String, A]] =
        files match
          case Left(e)     => Iterator.single(Left(e))
          case Right(uris) =>
            uris.iterator.flatMap { uri =>
              objects.getText(uri) match
                case Left(e)     => Iterator.single(Left(s"cannot read $uri: $e"))
                case Right(body) =>
                  RowSource.jsonLines[A](body.linesIterator, validate, dropFields).rows
            }
