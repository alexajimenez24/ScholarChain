package scholarchain.services.query

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import scala.concurrent.duration.*
import scholarchain.core.ScholarChainConfig
import scholarchain.core.aws.*
import scholarchain.rag.*
import scholarchain.services.common.*
import scholarchain.storage.*

/** The query deployable's wiring: configuration to AWS adapters to routes. Building it makes no
  * network call.
  *
  * The process is live at once and ready when the served run is loaded: `SERVING_RUN_MANIFEST_URI`
  * names a Spark run manifest on S3 (the pipeline writes it after the service is deployed). Without
  * it, or when the load fails, `/readyz` and `POST /v1/query` answer 503.
  */
object QueryWiring:
  val ServingEnv = "SERVING_RUN_MANIFEST_URI"
  val PromptRootEnv = "SYNTHESIS_PROMPT_ROOT"

  /** The committed synthesis prompt: from the classpath when the jar carries it, else from the
    * directory that holds `prompts/`.
    */
  def prompt(env: Map[String, String]): Either[String, SynthesisPrompt] =
    Option(getClass.getClassLoader.getResourceAsStream(SynthesisPrompt.File)) match
      case Some(in) =>
        SynthesisPrompt.parse(
          try new String(in.readAllBytes(), UTF_8)
          finally in.close()
        )
      case None => SynthesisPrompt.load(Path.of(env.getOrElse(PromptRootEnv, ".")))

  /** `models.llmEnabled` (LLM_ENABLED) off: the deterministic synthesizer, and neither a model
    * client nor a prompt is built. On: rag's Bedrock adapter with the configured model and Region.
    */
  def synthesizer(
      cfg: ScholarChainConfig,
      env: Map[String, String],
      credentials: CredentialsProvider,
      transport: HttpTransport
  ): Either[String, Synthesizer] =
    if !cfg.models.llmEnabled then QueryApp.synthesizer(QueryConfig(false), None)
    else
      for
        llm <- BedrockLlm.fromConfig(cfg, credentials, transport = transport)
        text <- prompt(env)
        model <- Synthesizers.select(cfg, Some(llm), Some(text))
        chosen <- QueryApp.synthesizer(QueryConfig(true), Some(model))
      yield chosen

  def assemble(
      env: Map[String, String],
      transport: HttpTransport = new JdkHttpTransport(),
      jwks: Option[IO[String]] = None,
      sink: MetricsSink = StdoutMetrics
  ): Either[String, ServiceApp] =
    for
      cfg <- Bootstrap.config(env)
      guard <- Bootstrap.guard(cfg.services.auth, jwks)
      region = cfg.application.region
      credentials = new EnvironmentCredentials(env, transport)
      http = new AwsHttp(region, credentials, transport = transport)
      synth <- synthesizer(cfg, env, credentials, transport)
      embedder <- BedrockEmbedder.fromConfig(http, region, cfg.models, cfg.vectors)
      kv = new DynamoDbStore(http, region)
      objects = new S3ObjectStore(http, region)
      vectors = new S3VectorsStore(http, region, cfg.vectors.vectorBucket)
      retriever <- SemanticContextRetriever(cfg, vectors, objects, embedder)
    yield
      val name = cfg.services.query.serviceName
      val bundles = new EvidenceBundles(kv, cfg.dynamo.evidenceBundleTable)
      val audit = new InMemoryAuditLog
      val index = new EvidenceIndex
      val engine = new AtomicReference[Option[QueryEngine]](None)
      // One attempt only: a second one would re-run a non-idempotent workflow.
      val workflow = new Downstream(
        "workflow",
        ResiliencePolicy(cfg.services.query.requestTimeoutMs.millis, maxAttempts = 1)
      )
      val stores = new Downstream(
        "stores",
        ResiliencePolicy(cfg.neptune.queryTimeoutMs.millis, maxAttempts = 2)
      )
      val delegate = new QueryEngine:
        def run(queryId: String, req: AnalysisRequest) =
          engine.get.fold(Left(WorkflowError.Downstream("serving-data", "not loaded")))(
            _.run(queryId, req)
          )
      val service =
        new QueryService(
          delegate,
          audit,
          bundles,
          workflow,
          stores,
          FiberScheduler,
          new QueryStore(),
          index
        )
      val kit = new ServiceKit(
        name,
        sink,
        checks = List(ReadinessCheck("serving-run", IO(engine.get.isDefined)))
      )
      val evidence = new EvidenceRoutes(guard, new BundleEvidenceLookup(bundles, index), stores)
      // Refused before an Idempotency-Key is claimed; the token is still checked first.
      val notLoaded: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case req @ POST -> Root / "v1" / "query" if engine.get.isEmpty =>
          guard.authorize(req, Scopes.QueryRun) *> ApiException.raise(
            ApiError(ErrorCode.Unavailable, "no served run is loaded yet")
          )
      }
      val routes =
        kit.wrap(notLoaded <+> new QueryRoutes(guard, service).routes <+> evidence.routes)

      val load: IO[Unit] =
        env.get(ServingEnv).map(_.trim).filter(_.nonEmpty) match
          case None      => Bootstrap.log(name, s"$ServingEnv is not set; not ready")
          case Some(uri) =>
            IO.interruptible(ServingData.load(cfg, objects, uri)).attempt.flatMap {
              case Right(Right(data)) =>
                val runtime = RuntimeStores(
                  kv,
                  new NeptuneGraphStore(http, cfg.neptune),
                  vectors,
                  objects,
                  embedder,
                  data.directory,
                  data.ranking,
                  Some(new CommunityCache(kv, cfg.dynamo.researcherMetricTable))
                )
                val wf =
                  new CollaborationWorkflow(data.ctx, runtime, retriever, synth, audit)
                IO(engine.set(Some(new WorkflowEngine(wf)))) *>
                  Bootstrap.log(name, s"serving run ${data.ctx.graphRunId}")
              case Right(Left(why)) => Bootstrap.log(name, s"served run not loaded: $why")
              case Left(e)          =>
                Bootstrap.log(name, s"served run not loaded: ${e.getClass.getSimpleName}")
            }
      ServiceApp(name, cfg.services.query.port, routes, load)

object QueryServiceMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Bootstrap.run("query-service", IO.blocking(QueryWiring.assemble(sys.env)))
