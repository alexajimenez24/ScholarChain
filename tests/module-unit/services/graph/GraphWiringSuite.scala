package scholarchain.services.graph

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.typelevel.ci.CIString
import scala.collection.mutable.ArrayBuffer
import scholarchain.core.aws.*
import scholarchain.services.common.*

class GraphWiringSuite extends munit.FunSuite:
  // Hang guard only: the first test pays one-time JVM setup (RSA keys, IO runtime, class
  // loading), over 30s in a loaded `sbt check`. Product timeouts are unchanged.
  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")
  private val env: Map[String, String] = Map(
    "SCHOLARCHAIN_MODE" -> "aws",
    "RAW_BUCKET" -> "sc-raw",
    "CURATED_BUCKET" -> "sc-curated",
    "GRAPH_BUCKET" -> "sc-graph",
    "RAG_BUCKET" -> "sc-rag",
    "VECTOR_BUCKET" -> "sc-vectors",
    "NEPTUNE_ENDPOINT" -> "neptune.test.invalid",
    "DDB_RUN_LEDGER" -> "t-ledger",
    "DDB_ENTITY_LINK_CACHE" -> "t-links",
    "DDB_QUERY_SESSION" -> "t-sessions",
    "DDB_EVIDENCE_BUNDLE" -> "t-bundles",
    "DDB_ARTIFACT_REGISTRY" -> "t-artifacts",
    "DDB_RESEARCHER_METRIC" -> "t-metrics",
    "EMBEDDING_MODEL_ID" -> "test.embed-model",
    "EMBEDDING_QUEUE_URL" -> "https://sqs.test.invalid/q",
    "COGNITO_ISSUER" -> TestJwt.Issuer,
    "COGNITO_AUDIENCE" -> TestJwt.Audience,
    "COGNITO_JWKS_URL" -> "https://jwks.test.invalid/jwks.json",
    // fake container credentials, answered by the fake transport
    "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" -> "/creds",
    "GRAPH_SERVICE_PORT" -> "9091"
  )

  private final class Neptune(var status: Int) extends HttpTransport:
    val seen = ArrayBuffer.empty[HttpRequest]
    def execute(r: HttpRequest): Either[String, HttpResponse] =
      if r.url.endsWith("/creds") then
        Right(
          HttpResponse(200, """{"AccessKeyId":"fake","SecretAccessKey":"fake"}""", Map.empty)
        )
      else
        synchronized(seen += r)
        Right(HttpResponse(status, """{"results":[]}""", Map.empty))

  private def status(routes: HttpRoutes[IO], path: String): Status =
    routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .unsafeRunSync()
      .status

  test("the wired graph service is live, and ready only while Neptune answers") {
    val neptune = new Neptune(200)
    val app =
      GraphWiring.assemble(env, neptune, sink = new CollectingMetrics).fold(fail(_), identity)
    assertEquals((app.name, app.port), ("graph-service", 9091))
    assertEquals(neptune.seen.size, 0)
    assertEquals(status(app.routes, "/healthz"), Status.Ok)
    assertEquals(neptune.seen.size, 0)
    assertEquals(status(app.routes, "/readyz"), Status.Ok)
    assertEquals(
      neptune.seen.map(_.url).toList,
      List("https://neptune.test.invalid:8182/openCypher")
    )
    neptune.status = 500
    assertEquals(status(app.routes, "/readyz"), Status.ServiceUnavailable)
    assertEquals(status(app.routes, "/v1/graph/nodes/n1"), Status.Unauthorized)
  }

  test("local mode and a missing Neptune endpoint are refused") {
    assert(GraphWiring.assemble(env - "SCHOLARCHAIN_MODE").isLeft)
    assert(GraphWiring.assemble(env - "NEPTUNE_ENDPOINT").isLeft)
  }
