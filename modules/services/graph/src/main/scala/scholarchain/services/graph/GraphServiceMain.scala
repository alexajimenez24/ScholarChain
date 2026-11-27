package scholarchain.services.graph

import cats.effect.{ExitCode, IO, IOApp}
import scala.concurrent.duration.*
import scholarchain.core.aws.*
import scholarchain.services.common.*
import scholarchain.storage.{GraphTemplates, NeptuneGraphStore}

/** The graph deployable's wiring; building it makes no network call.
  *
  * Ready means Neptune answers one allowlisted node read (of an ID that does not exist). Evidence
  * by ID has no per-evidence store index, so this deployable answers 404 for it; API Gateway routes
  * `/v1/evidence` to the query service.
  */
object GraphWiring:
  val ProbeNodeId = "readiness-probe"

  def assemble(
      env: Map[String, String],
      transport: HttpTransport = new JdkHttpTransport(),
      jwks: Option[IO[String]] = None,
      sink: MetricsSink = StdoutMetrics
  ): Either[String, ServiceApp] =
    for
      cfg <- Bootstrap.config(env)
      guard <- Bootstrap.guard(cfg.services.auth, jwks)
      templates = GraphTemplates.forConfig(cfg.graph.windows)
      probe <- templates.node(ProbeNodeId)
    yield
      val settings = cfg.services.graph
      val perCall = math.min(cfg.neptune.queryTimeoutMs, settings.requestTimeoutMs).millis
      val http = new AwsHttp(
        cfg.application.region,
        new EnvironmentCredentials(env, transport),
        requestTimeout = java.time.Duration.ofMillis(perCall.toMillis),
        transport = transport
      )
      val graph = new NeptuneGraphStore(http, cfg.neptune)
      val downstream = new Downstream("neptune", ResiliencePolicy(perCall, maxAttempts = 2))
      val kit = new ServiceKit(
        settings.serviceName,
        sink,
        checks = List(ReadinessCheck("neptune", IO.interruptible(graph.run(probe).isRight)))
      )
      val evidence =
        new EvidenceRoutes(guard, new InMemoryEvidenceLookup(Nil), downstream)
      ServiceApp(
        settings.serviceName,
        settings.port,
        GraphApp.routes(kit, guard, new GraphReader(graph, templates, downstream), evidence)
      )

object GraphServiceMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Bootstrap.run("graph-service", IO.blocking(GraphWiring.assemble(sys.env)))
