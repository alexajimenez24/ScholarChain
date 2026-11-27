package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Supplies the document renderer, which storage cannot import. Found with
  * `java.util.ServiceLoader`: the jar that runs `load-vectors` or `enqueue-embeddings` must hold
  * one implementation and its `META-INF/services/scholarchain.storage.RendererProvider` entry.
  */
trait RendererProvider:
  def renderer(cfg: VectorConfig): Either[String, DocumentRenderer]

/** Everything a command touches outside the process, built only when a command needs it. */
trait PipelineDeps:
  def objects: ObjectStore
  def kv: KeyValueStore
  def vectors: VectorStore
  def embedder: Either[String, Embedder]
  def queue: QueueSender
  def neptuneLoader(loaderRoleArn: String): NeptuneBulkLoader
  def renderer: Either[String, DocumentRenderer]
  def sleep(millis: Long): Unit = Thread.sleep(millis)

/** The AWS adapters. Credentials come from the environment or the ECS container endpoint (core
  * `EnvironmentCredentials`; it does not read the EC2 instance profile). Building makes no call.
  */
final class AwsPipelineDeps(
    cfg: ScholarChainConfig,
    env: Map[String, String],
    transport: HttpTransport = new JdkHttpTransport()
) extends PipelineDeps:
  private val region = cfg.application.region
  private val credentials = new EnvironmentCredentials(env, transport)
  // One attempt where a RetryPolicy wraps the call, or where a repeat is not wanted.
  private lazy val single = new AwsHttp(region, credentials, transport = transport)
  // S3 and DynamoDB writes are conditional, so a repeated attempt cannot overwrite anything.
  private lazy val retrying =
    new AwsHttp(region, credentials, maxAttempts = 3, transport = transport)

  lazy val objects: ObjectStore = new S3ObjectStore(retrying, region)
  lazy val kv: KeyValueStore = new DynamoDbStore(retrying, region)
  lazy val vectors: VectorStore = new S3VectorsStore(single, region, cfg.vectors.vectorBucket)
  def embedder: Either[String, Embedder] =
    BedrockEmbedder.fromConfig(single, region, cfg.models, cfg.vectors)
  lazy val queue: QueueSender =
    new SqsQueueSender(single, region, cfg.services.worker.embeddingQueueUrl)
  def neptuneLoader(loaderRoleArn: String): NeptuneBulkLoader =
    new NeptuneBulkLoader(single, cfg.neptune, region, loaderRoleArn)
  def renderer: Either[String, DocumentRenderer] =
    java.util.ServiceLoader.load(classOf[RendererProvider]).asScala.toVector match
      case Vector(one) => one.renderer(cfg.vectors)
      case found       =>
        Left(s"need exactly one RendererProvider on the classpath, found ${found.size}")

/** Command-line entry points of the cloud pipeline.
  *
  * {{{
  * java -cp <jar> scholarchain.storage.PipelineCli <command> --spark-output s3://.../spark [flags]
  *
  *   neptune-load        [--no-start] [--allow-rejects] [--wait-seconds N (default 3600)]
  *   load-dynamo
  *   load-vectors        (--max-units-per-type N | --all)
  *   enqueue-embeddings  (--max-units-per-type N | --all)
  *
  *   --dry-run   checks arguments and configuration and names what would happen; no network call
  *   --preview   reads the run from S3 and computes what would be written or sent; writes nothing
  * }}}
  *
  * Exit codes: [[PipelineCli.Ok]] 0, [[PipelineCli.Failed]] 1, [[PipelineCli.Usage]] 2 (arguments,
  * configuration or renderer), [[PipelineCli.Rejected]] 3 (finished or stopped with rejected
  * records; nothing was repaired), [[PipelineCli.LoaderPending]] 4 (the Neptune load did not end
  * within the wait).
  */
object PipelineCli:
  val Ok = 0
  val Failed = 1
  val Usage = 2
  val Rejected = 3
  val LoaderPending = 4

  val LoaderRoleEnv = "NEPTUNE_LOADER_ROLE_ARN"
  val PollMillis = 30000L
  val Commands: Vector[String] =
    Vector("neptune-load", "load-dynamo", "load-vectors", "enqueue-embeddings")

  private val Switches = Set("--dry-run", "--preview", "--no-start", "--allow-rejects", "--all")
  private val Valued = Set("--spark-output", "--wait-seconds", "--max-units-per-type")
  private val Common = Set("--spark-output", "--dry-run", "--preview")
  private val Allowed: Map[String, Set[String]] = Map(
    "neptune-load" -> Set("--no-start", "--allow-rejects", "--wait-seconds"),
    "load-dynamo" -> Set.empty,
    "load-vectors" -> Set("--all", "--max-units-per-type"),
    "enqueue-embeddings" -> Set("--all", "--max-units-per-type")
  )
  private val SparkOutput = "^s3a?://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9=._/-]+$".r
  // An AWS account ID is 12 digits; hexadecimal IDs and hashes are left alone.
  private val AccountId = "(?<![0-9A-Za-z])\\d{12}(?![0-9A-Za-z])".r

  private[storage] final case class Args(
      command: String,
      sparkOutput: String,
      dryRun: Boolean,
      preview: Boolean,
      noStart: Boolean,
      allowRejects: Boolean,
      waitSeconds: Int,
      maxUnits: Option[Int]
  )

  private[storage] def parse(args: List[String]): Either[String, Args] =
    def flags(
        rest: List[String],
        acc: Map[String, String]
    ): Either[String, Map[String, String]] =
      rest match
        case Nil                         => Right(acc)
        case f :: _ if acc.contains(f)   => Left(s"$f is given twice")
        case f :: tail if Switches(f)    => flags(tail, acc + (f -> ""))
        case f :: v :: tail if Valued(f) => flags(tail, acc + (f -> v))
        case f :: _ if Valued(f)         => Left(s"$f needs a value")
        case _                           => Left("unknown argument")
    args match
      case command :: rest if Commands.contains(command) =>
        flags(rest, Map.empty).flatMap { m =>
          def number(f: String, min: Int) = m.get(f) match
            case None    => Right(None)
            case Some(v) =>
              v.toIntOption.filter(_ >= min).map(Some(_)).toRight(s"$f must be a number >= $min")
          val extra = m.keySet -- Common -- Allowed(command)
          val selects = command == "load-vectors" || command == "enqueue-embeddings"
          for
            _ <- Either.cond(
              extra.isEmpty,
              (),
              s"$command does not take ${extra.toVector.sorted.mkString(", ")}"
            )
            out <- m
              .get("--spark-output")
              .filter(SparkOutput.matches)
              .toRight("--spark-output s3://bucket/prefix is required")
            _ <- Either.cond(
              !(m.contains("--dry-run") && m.contains("--preview")),
              (),
              "give --dry-run or --preview, not both"
            )
            max <- number("--max-units-per-type", 1)
            _ <- Either.cond(
              !selects || (max.isDefined != m.contains("--all")),
              (),
              s"$command needs exactly one of --max-units-per-type N or --all (it costs per item)"
            )
            wait <- number("--wait-seconds", 0)
          yield Args(
            command,
            out,
            m.contains("--dry-run"),
            m.contains("--preview"),
            m.contains("--no-start"),
            m.contains("--allow-rejects"),
            wait.getOrElse(3600),
            max
          )
        }
      case _ => Left(s"usage: PipelineCli <${Commands.mkString(" | ")}> --spark-output <uri>")

  /** The environment variables a command needs, by name, with the configured value of each. */
  private def required(cfg: ScholarChainConfig, a: Args): Vector[(String, String)] =
    val ledger = Vector(
      "CURATED_BUCKET" -> cfg.storage.curatedBucket,
      "DDB_RUN_LEDGER" -> cfg.dynamo.runLedgerTable
    )
    ledger ++ (a.command match
      case "neptune-load" =>
        Vector("GRAPH_BUCKET" -> cfg.storage.graphBucket) ++
          (if a.noStart then Vector.empty
           else Vector("NEPTUNE_ENDPOINT" -> cfg.neptune.endpoint))
      case "load-dynamo"  => Vector("DDB_RESEARCHER_METRIC" -> cfg.dynamo.researcherMetricTable)
      case "load-vectors" =>
        Vector(
          "VECTOR_BUCKET" -> cfg.vectors.vectorBucket,
          "RAG_BUCKET" -> cfg.storage.ragBucket,
          "EMBEDDING_MODEL_ID" -> cfg.models.embeddingModel
        )
      case _ => Vector("EMBEDDING_QUEUE_URL" -> cfg.services.worker.embeddingQueueUrl))

  def main(args: Array[String]): Unit =
    val env = ConfigLoader.processEnvironment
    sys.exit(run(args.toList, env, new AwsPipelineDeps(_, env), println(_)))

  def run(
      args: List[String],
      env: Map[String, String],
      deps: ScholarChainConfig => PipelineDeps,
      print: String => Unit
  ): Int =
    val out: String => Unit = s => print(AccountId.replaceAllIn(s, "<account>"))
    def usage(why: String) =
      out(s"==> STEP pipeline-cli FAILED: $why")
      Usage
    parse(args) match
      case Left(why) => usage(why)
      case Right(a)  =>
        ConfigLoader.load(env) match
          case Left(why)  => usage(s"configuration: $why")
          case Right(cfg) =>
            val needsRole = a.command == "neptune-load" && !a.noStart
            val missing = required(cfg, a).collect { case (n, v) if v.trim.isEmpty => n } ++
              Option.when(needsRole && env.get(LoaderRoleEnv).forall(_.trim.isEmpty))(
                LoaderRoleEnv
              )
            if missing.nonEmpty then usage(s"set ${missing.mkString(", ")}")
            else
              try
                out(s"==> STEP ${a.command}")
                if a.dryRun then dryRun(cfg, env, a, out)
                else execute(cfg, env, a, deps(cfg), out)
              catch
                case NonFatal(e) =>
                  out(s"==> STEP ${a.command} FAILED: ${e.getClass.getSimpleName}")
                  Failed

  private def dryRun(
      cfg: ScholarChainConfig,
      env: Map[String, String],
      a: Args,
      out: String => Unit
  ): Int =
    out(s"dry-run: no network call; input ${a.sparkOutput}")
    required(cfg, a).foreach { (name, value) =>
      // A queue URL carries the account ID, so only the queue name is shown.
      val shown =
        if name == "EMBEDDING_QUEUE_URL" then value.substring(value.lastIndexOf('/') + 1)
        else value
      out(s"dry-run: $name = $shown")
    }
    val would = a.command match
      case "neptune-load" =>
        "write openCypher CSV and manifests to the graph zone of GRAPH_BUCKET" +
          (if a.noStart then "; not start a load"
           else s"; start the Neptune bulk loader and wait up to ${a.waitSeconds} s")
      case "load-dynamo"  => "load researcher metrics and community sizes into DynamoDB"
      case "load-vectors" => s"embed and put ${selection(a)} into S3 Vectors"
      case _              => s"send one message per chunk of ${selection(a)} to the queue"
    out(s"dry-run: would $would")
    Ok

  private def selection(a: Args): String =
    a.maxUnits.fold("every source row")(n => s"at most $n source rows per document type")

  private def execute(
      cfg: ScholarChainConfig,
      env: Map[String, String],
      a: Args,
      deps: PipelineDeps,
      out: String => Unit
  ): Int =
    val layout = new S3Layout(cfg.storage)
    lazy val writer = new ArtifactWriter(deps.objects, layout)
    lazy val ledger = new RunLedger(deps.kv, cfg.dynamo.runLedgerTable)
    def failed(why: String, code: Int = Failed) =
      out(s"==> STEP ${a.command} FAILED: $why")
      code

    // The renderer is a classpath matter: fail on it before anything is read.
    val renderer: Either[String, Option[DocumentRenderer]] =
      if a.command == "load-vectors" || a.command == "enqueue-embeddings" then
        deps.renderer.map(Some(_))
      else Right(None)

    renderer match
      case Left(why) => failed(why, Usage)
      case Right(r)  =>
        SparkRun.open(cfg, deps.objects, a.sparkOutput) match
          case Left(why)  => failed(why)
          case Right(run) =>
            out(s"runId=${run.runId} snapshot=${run.snapshot}")
            val result: Either[String, Int] = a.command match
              case "neptune-load" =>
                neptune(cfg, env, a, deps, run, ledger, writer, layout, out)
              case "load-dynamo" => dynamo(cfg, a, deps, run, ledger, writer, out)
              case command       =>
                semantic(cfg, a, deps, run, r.get).flatMap { (source, expected) =>
                  if command == "load-vectors" then
                    loadVectors(cfg, a, deps, run, r.get, source, expected, ledger, writer, out)
                  else enqueue(a, deps, run, r.get, source, ledger, writer, out)
                }
            result.fold(failed(_), identity)

  private def rejects(out: String => Unit, rejected: Seq[(String, String)]): Unit =
    out(s"rejected=${rejected.size}")
    rejected
      .take(10)
      .foreach((input, why) => out(s"rejected: ${input.take(120)}: ${why.take(200)}"))

  private def exit(rejected: Int): Int = if rejected == 0 then Ok else Rejected

  // ---------------------------------------------------------------- neptune-load

  private def neptune(
      cfg: ScholarChainConfig,
      env: Map[String, String],
      a: Args,
      deps: PipelineDeps,
      run: SparkRun,
      ledger: RunLedger,
      writer: ArtifactWriter,
      layout: S3Layout,
      out: String => Unit
  ): Either[String, Int] =
    def all[A](what: String, src: RowSource[A]): Either[String, Vector[A]] =
      src.problem.toLeft(src.rows.toVector).flatMap { rows =>
        rows.collectFirst { case Left(e) => e } match
          case Some(e) => Left(s"$what: a row was refused: ${e.take(300)}")
          case None    => Right(rows.collect { case Right(x) => x })
      }
    for
      lm <- SparkRun.loadManifest(deps.objects, run, "neptune")
      (text, json) = lm
      expected <- SparkNeptuneManifest.parse(text)
      nt <- SparkRun.table("nodes", json("nodes"))
      et <- SparkRun.table("edges", json("edges"))
      ct <- SparkRun.table("coauthorEdges", json("coauthorEdges"))
      nodes <- all(
        "graph-nodes",
        SparkRun.rows[GraphNode](deps.objects, run, "graph-nodes", Validate.graphNode, nt._2)
      )
      edges <- all(
        "graph-edges",
        SparkRun.rows[GraphEdge](deps.objects, run, "graph-edges", Validate.graphEdge, et._2)
      )
      records <- all(
        "coauthor-edges",
        SparkRun.rows[CoauthorEdge](deps.objects, run, "coauthor-edges", Validate.edge, ct._2)
      )
      _ <- Either.cond(
        records.size.toLong == ct._1,
        (),
        s"coauthor-edges: manifest ${ct._1} rows, read ${records.size}"
      )
      input = GraphExportInput(
        run.runId,
        run.snapshot,
        cfg.graph.policyVersion,
        cfg.graph.windows,
        nodes,
        edges,
        records,
        run.folder
      )
      differences = SparkNeptuneManifest.reconcile(expected, input)
      _ <- Either.cond(differences.isEmpty, (), differences.mkString("; "))
      plan = NeptuneExport.build(input)
      _ = plan.manifest.files.foreach(f => out(s"file=${f.name} rows=${f.rows} bytes=${f.bytes}"))
      _ = rejects(out, plan.rejections.map(x => s"${x.kind} ${x.id}" -> x.reason))
      code <-
        if a.preview then
          out("preview: nothing written, no load started")
          Right(exit(plan.rejections.size))
        else
          new NeptuneLoadJob(ledger, new NeptuneLoadWriter(writer, layout), writer)
            .run(plan)
            .flatMap { written =>
              out(s"loaderSource=${written.sourcePrefix}")
              if !plan.clean && !a.allowRejects then
                out("records were rejected: no load started (--allow-rejects starts it)")
                Right(Rejected)
              else if a.noStart then Right(exit(plan.rejections.size))
              else
                load(
                  deps,
                  deps.neptuneLoader(env(LoaderRoleEnv).trim),
                  written.sourcePrefix,
                  a.waitSeconds,
                  out
                ).map(c => if c == Ok then exit(plan.rejections.size) else c)
            }
    yield code

  private val Running = Set("LOAD_NOT_STARTED", "LOAD_IN_QUEUE", "LOAD_IN_PROGRESS")

  /** Starts one bulk load and polls it. The start is not retried; a failed status read is retried
    * on the next poll until the wait ends. `waitSeconds` 0 starts without waiting.
    */
  private def load(
      deps: PipelineDeps,
      loader: NeptuneBulkLoader,
      source: String,
      waitSeconds: Int,
      out: String => Unit
  ): Either[String, Int] =
    loader.start(source).flatMap { id =>
      out(s"loadId=$id")
      def poll(waited: Long): Either[String, Int] =
        loader.status(id) match
          case Right(s) if s.completedClean =>
            out(s"load ${s.status} records=${s.totalRecords} duplicates=${s.totalDuplicates}")
            Right(Ok)
          case Right(s) if !Running(s.status) =>
            Left(s"load $id ended with ${s.status}, errors=${s.errors}")
          case other =>
            if waited >= waitSeconds * 1000L then
              out(s"load $id not finished after $waitSeconds s: ${other.fold(identity, _.status)}")
              Right(LoaderPending)
            else
              deps.sleep(PollMillis)
              poll(waited + PollMillis)
      if waitSeconds == 0 then Right(Ok) else poll(0L)
    }

  // ---------------------------------------------------------------- load-dynamo

  private def dynamo(
      cfg: ScholarChainConfig,
      a: Args,
      deps: PipelineDeps,
      run: SparkRun,
      ledger: RunLedger,
      writer: ArtifactWriter,
      out: String => Unit
  ): Either[String, Int] =
    for
      lm <- SparkRun.loadManifest(deps.objects, run, "dynamodb")
      (text, json) = lm
      manifest <- LoadManifests.dynamo(text)
      _ <- Either.cond(
        manifest.tables.map(_.tableConfigKey) == Vector("dynamo.researcherMetricTable"),
        (),
        "dynamodb.json must list exactly dynamo.researcherMetricTable"
      )
      mt <- SparkRun.table("researcher-metrics", json("tables")(0)("source"))
      ct <- json.obj.get("communities") match
        case None    => Right(None)
        case Some(c) => SparkRun.table("communities", c("source")).map(Some(_))
      metrics = SparkRun.rows[ResearcherMetrics](
        deps.objects,
        run,
        "researcher-metrics",
        Validate.metrics,
        mt._2
      )
      communities = ct.map(t =>
        t._1 -> SparkRun
          .rows[EvidenceItem](deps.objects, run, "communities", Validate.evidence, t._2)
      )
      code <-
        if a.preview then
          def check(name: String, expected: Long, rows: Iterator[Either[String, Any]]) =
            val (good, bad) = rows.toVector.partition(_.isRight)
            out(s"preview: $name rows=${good.size} malformed=${bad.size} expected=$expected")
            Either.cond(
              good.size + bad.size == expected,
              bad.size,
              s"$name: the manifest has $expected rows, the export gave ${good.size + bad.size}"
            )
          for
            m <- check("researcher-metrics", mt._1, metrics.rows)
            c <- communities.fold[Either[String, Int]](Right(0))((n, s) =>
              check("communities", n, s.rows)
            )
          yield exit(m + c)
        else
          val cache = new ResearcherMetricCache(deps.kv, cfg.dynamo.researcherMetricTable)
          new ResearcherMetricsLoader(cache, ledger, writer)
            .load(run.runId, metrics, Some(mt._1))
            .flatMap { m =>
              out(s"metrics read=${m.read} created=${m.created} unchanged=${m.unchanged}")
              rejects(out, m.rejected.map(x => x.input -> x.reason))
              communities
                .fold[Either[String, Int]](Right(0)) { (expected, source) =>
                  new CommunityLoader(
                    new CommunityCache(deps.kv, cfg.dynamo.researcherMetricTable),
                    ledger,
                    writer
                  ).load(run.runId, source, Some(expected)).map { c =>
                    out(
                      s"communities read=${c.read} created=${c.created} unchanged=${c.unchanged} other=${c.otherKindRows}"
                    )
                    rejects(out, c.rejected.map(x => x.input -> x.reason))
                    c.rejected.size
                  }
                }
                .map(c => exit(m.rejected.size + c))
            }
    yield code

  // ---------------------------------------------------------------- semantic documents

  /** The selected semantic rows, after the whole export was checked against its load manifest, and
    * the number of rows a full load must see.
    */
  private def semantic(
      cfg: ScholarChainConfig,
      a: Args,
      deps: PipelineDeps,
      run: SparkRun,
      renderer: DocumentRenderer
  ): Either[String, (RowSource[SourceRecord], Option[Long])] =
    for
      lm <- SparkRun.loadManifest(deps.objects, run, "s3vectors")
      (text, json) = lm
      manifest <- LoadManifests.vectors(text)
      _ <- manifest.problems(cfg.vectors) match
        case Vector() => Right(())
        case ps       => Left(ps.mkString("; "))
      st <- SparkRun.table("semantic-summaries", json("source"))
      abs <- json.obj.get("abstracts") match
        case None    => Right(None)
        case Some(x) => SparkRun.table("paper-abstracts", x("source")).map(Some(_))
      summaries = SparkRun.rows[EvidenceItem](
        deps.objects,
        run,
        "semantic-summaries",
        Validate.evidence,
        st._2
      )
      abstracts = abs.fold(RowSource.of(Seq.empty[PaperAbstract]))(t =>
        SparkRun.rows[PaperAbstract](deps.objects, run, "paper-abstracts", dropFields = t._2)
      )
      _ <- summaries.problem.orElse(abstracts.problem).toLeft(())
      selected = LocalIngestion.scan(summaries, abstracts, abs.isDefined, a.maxUnits)
      _ <- LocalIngestion.checkAgainstManifest(manifest, abs.map(_._1), cfg, selected)
    yield (
      LocalIngestion
        .combined(summaries, abstracts, abs.isDefined, a.maxUnits.map(_ => selected.keys)),
      Option.when(a.maxUnits.isEmpty)(manifest.sourceRows + abs.map(_._1).getOrElse(0L))
    )

  private def loadVectors(
      cfg: ScholarChainConfig,
      a: Args,
      deps: PipelineDeps,
      run: SparkRun,
      renderer: DocumentRenderer,
      source: RowSource[SourceRecord],
      expected: Option[Long],
      ledger: RunLedger,
      writer: ArtifactWriter,
      out: String => Unit
  ): Either[String, Int] =
    if a.preview then preview(deps, run, renderer, source, ledger, writer, out)
    else
      for
        embedder <- deps.embedder
        loader <- VectorLoader(deps.vectors, embedder, writer, ledger, cfg.vectors)
        r <- loader.loadRendered(run.runId, source, renderer, expected)
      yield
        out(
          s"vectors rows=${r.read} chunks=${r.chunks} written=${r.written} unchanged=${r.unchanged} duplicatesDropped=${r.duplicatesDropped}"
        )
        rejects(out, r.rejected.map(x => x.input -> x.reason))
        out(s"report=${r.report.uri}")
        exit(r.rejected.size)

  private def enqueue(
      a: Args,
      deps: PipelineDeps,
      run: SparkRun,
      renderer: DocumentRenderer,
      source: RowSource[SourceRecord],
      ledger: RunLedger,
      writer: ArtifactWriter,
      out: String => Unit
  ): Either[String, Int] =
    if a.preview then preview(deps, run, renderer, source, ledger, writer, out)
    else
      new EmbeddingEnqueuer(deps.queue, ledger, writer)
        .enqueue(run.runId, source, renderer)
        .map(summary("enqueued", _, out))

  /** Renders and sizes the messages of the selection: no send, embedding call or write. */
  private def preview(
      deps: PipelineDeps,
      run: SparkRun,
      renderer: DocumentRenderer,
      source: RowSource[SourceRecord],
      ledger: RunLedger,
      writer: ArtifactWriter,
      out: String => Unit
  ): Either[String, Int] =
    val none: QueueSender = _ => Left(RemoteError.Permanent("a preview sends nothing"))
    new EmbeddingEnqueuer(none, ledger, writer)
      .preview(run.runId, source, renderer)
      .map(summary("preview: would handle", _, out))

  private def summary(what: String, r: EnqueueResult, out: String => Unit): Int =
    out(
      s"$what rows=${r.read} chunks=${r.chunks} messages=${r.messages} bytes=${r.bytes} duplicatesDropped=${r.duplicatesDropped}"
    )
    rejects(out, r.rejected.map(x => x.input -> x.reason))
    r.report.foreach(o => out(s"report=${o.uri}"))
    exit(r.rejected.size)
