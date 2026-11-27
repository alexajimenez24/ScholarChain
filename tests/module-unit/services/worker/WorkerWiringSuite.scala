package scholarchain.services.worker

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scholarchain.core.aws.*
import scholarchain.services.common.*
import scholarchain.storage.*

class WorkerWiringSuite extends munit.FunSuite:
  // Hang guard only: the first test pays one-time JVM setup (RSA keys, IO runtime, class
  // loading), over 30s in a loaded `sbt check`. Product timeouts are unchanged.
  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")
  private val env: Map[String, String] = WFx.env ++ Map(
    "SCHOLARCHAIN_MODE" -> "aws",
    "VECTOR_BUCKET" -> "sc-vectors",
    "NEPTUNE_ENDPOINT" -> "neptune.test.invalid",
    "EMBEDDING_MODEL_ID" -> "test.embed-model",
    "EMBEDDING_QUEUE_URL" -> "https://sqs.test.invalid/q",
    "COGNITO_ISSUER" -> "https://issuer.test.invalid",
    "COGNITO_AUDIENCE" -> "client-abc",
    "COGNITO_JWKS_URL" -> "https://jwks.test.invalid/jwks.json"
  )

  private final class Sqs(reply: String => HttpResponse) extends HttpTransport:
    val seen = ArrayBuffer.empty[(String, ujson.Value)]
    def execute(r: HttpRequest): Either[String, HttpResponse] =
      val op = r.headers.getOrElse("x-amz-target", "").stripPrefix("AmazonSQS.")
      synchronized(seen += op -> ujson.read(r.body))
      Right(reply(op))

  private val creds = new StaticCredentials(AwsCredentials("fake", "fake"))
  private def consumer(sqs: Sqs) =
    new SqsQueueConsumer(
      new AwsHttp("us-east-2", creds, transport = sqs),
      "us-east-2",
      "https://sqs.test.invalid/q"
    )
  private def ok(body: String) = HttpResponse(200, body, Map.empty)
  private def status(routes: HttpRoutes[IO], path: String): Status =
    routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .unsafeRunSync()
      .status

  test("the wired worker is live and not ready before its first receive, with no AWS call") {
    val sqs = new Sqs(_ => ok("{}"))
    val app = WorkerWiring.assemble(env, sqs, new CollectingMetrics).fold(fail(_), identity)
    assertEquals((app.name, app.port), ("embedding-worker", 8080))
    assertEquals(status(app.routes, "/healthz"), Status.Ok)
    assertEquals(status(app.routes, "/readyz"), Status.ServiceUnavailable)
    assertEquals(sqs.seen.size, 0)
  }

  test("a missing queue URL and local mode are refused") {
    assert(WorkerWiring.assemble(env - "EMBEDDING_QUEUE_URL").isLeft)
    assert(WorkerWiring.assemble(env - "SCHOLARCHAIN_MODE").isLeft)
  }

  test("receive asks for at most 10 messages and returns receipt handles and bodies") {
    val sqs =
      new Sqs(_ => ok("""{"Messages":[{"MessageId":"1","ReceiptHandle":"h1","Body":"b1"}]}"""))
    assertEquals(consumer(sqs).receive(50), Vector(QueueMessage("h1", "b1")))
    val (op, sent) = sqs.seen.head
    assertEquals(op, "ReceiveMessage")
    assertEquals(sent("MaxNumberOfMessages").num.toInt, 10)
    assertEquals(sent("QueueUrl").str, "https://sqs.test.invalid/q")
  }

  test("acknowledge deletes in batches of 10; a failed entry or HTTP error throws") {
    val sqs = new Sqs(_ => ok("""{"Successful":[]}"""))
    consumer(sqs).acknowledge(Vector.tabulate(12)(i => s"h$i"))
    assertEquals(
      sqs.seen.map((op, b) => op -> b("Entries").arr.size).toList,
      List("DeleteMessageBatch" -> 10, "DeleteMessageBatch" -> 2)
    )
    intercept[IllegalStateException](
      consumer(new Sqs(_ => ok("""{"Failed":[{"Id":"d0"}]}"""))).acknowledge(Vector("h"))
    )
    val down = intercept[IllegalStateException](
      consumer(new Sqs(_ => HttpResponse(403, "denied: detail", Map.empty))).receive(1)
    )
    assertEquals(down.getMessage, "SQS ReceiveMessage: HTTP 403")
  }

  test("a failing receive does not end the loop step, and readiness follows the last receive") {
    var up = false
    val sqs = new Sqs(_ => if up then ok("{}") else HttpResponse(500, "", Map.empty))
    val observed = new ObservedConsumer(consumer(sqs))
    val rig = new WFx.Rig()
    val worker = new EmbeddingWorker(observed, rig.loader, new CollectingMetrics)
    worker.step(Duration.Zero).unsafeRunSync()
    assertEquals(observed.healthy, false)
    up = true
    worker.step(Duration.Zero).unsafeRunSync()
    assertEquals(observed.healthy, true)
  }
