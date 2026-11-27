package scholarchain.services.worker

import cats.effect.{ExitCode, IO, IOApp}
import java.util.concurrent.atomic.AtomicBoolean
import scholarchain.core.aws.*
import scholarchain.services.common.*
import scholarchain.storage.*

/** The queue consumer plus whether its last receive worked: the worker's readiness. */
final class ObservedConsumer(inner: QueueConsumer) extends QueueConsumer:
  private val ok = new AtomicBoolean(false)
  def healthy: Boolean = ok.get
  def receive(max: Int): Vector[QueueMessage] =
    try
      val messages = inner.receive(max)
      ok.set(true)
      messages
    catch
      case e: Throwable =>
        ok.set(false)
        throw e
  def acknowledge(ids: Vector[String]): Unit = inner.acknowledge(ids)

/** The worker deployable's wiring; building it makes no network call. The queue is drained beside
  * the server, which serves health and readiness only.
  */
object WorkerWiring:
  def assemble(
      env: Map[String, String],
      transport: HttpTransport = new JdkHttpTransport(),
      sink: MetricsSink = StdoutMetrics
  ): Either[String, ServiceApp] =
    for
      cfg <- Bootstrap.config(env)
      settings = cfg.services.worker
      _ <- Either.cond(
        settings.embeddingQueueUrl.startsWith("https://"),
        (),
        "services.worker.embeddingQueueUrl must be https"
      )
      region = cfg.application.region
      http = new AwsHttp(region, new EnvironmentCredentials(env, transport), transport = transport)
      embedder <- BedrockEmbedder.fromConfig(http, region, cfg.models, cfg.vectors)
      loader <- VectorLoader(
        new S3VectorsStore(http, region, cfg.vectors.vectorBucket),
        embedder,
        new ArtifactWriter(new S3ObjectStore(http, region), new S3Layout(cfg.storage)),
        new RunLedger(new DynamoDbStore(http, region), cfg.dynamo.runLedgerTable),
        cfg.vectors
      )
    yield
      val consumer =
        new ObservedConsumer(new SqsQueueConsumer(http, region, settings.embeddingQueueUrl))
      val worker = new EmbeddingWorker(
        consumer,
        loader,
        sink,
        batchSize = math.min(settings.batchSize, SqsQueueConsumer.MaxBatch)
      )
      val kit = new ServiceKit(
        settings.serviceName,
        sink,
        checks = List(ReadinessCheck("queue", IO(consumer.healthy)))
      )
      ServiceApp(settings.serviceName, settings.port, WorkerApp.routes(kit), worker.loop())

object EmbeddingWorkerMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    Bootstrap.run("embedding-worker", IO.blocking(WorkerWiring.assemble(sys.env)))
