package scholarchain.services.query

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.http4s.Status
import scala.collection.mutable.ArrayBuffer
import scholarchain.core.*
import scholarchain.core.aws.*
import scholarchain.rag.*
import scholarchain.services.common.*
import scholarchain.storage.*
import QueryFixture.*
import QFx.*

/** The environment of a deployable in aws mode, with placeholder names only. */
object WiringFx:
  val env: Map[String, String] = Map(
    "SCHOLARCHAIN_MODE" -> "aws",
    "AWS_REGION" -> "us-east-2",
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
    "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" -> "/creds"
  )

  val jwks: IO[String] = IO.pure {
    val k = TestJwt.pair.getPublic.asInstanceOf[RSAPublicKey]
    def b64(n: java.math.BigInteger) =
      Base64.getUrlEncoder.withoutPadding.encodeToString(n.toByteArray)
    ujson.write(
      ujson.Obj(
        "keys" -> ujson.Arr(
          ujson.Obj(
            "kty" -> "RSA",
            "kid" -> "kid-1",
            "n" -> b64(k.getModulus),
            "e" -> b64(k.getPublicExponent)
          )
        )
      )
    )
  }
  // The wiring uses the system clock, so the token expires far in the future.
  val token: Option[String] = Some(TestJwt.token(exp = 4_000_000_000L))

  /** Records every request; answers S3 reads from `objects`, anything else with `other`. */
  final class FakeAws(
      objects: InMemoryObjectStore = new InMemoryObjectStore,
      other: HttpRequest => HttpResponse = _ => HttpResponse(500, "", Map.empty)
  ) extends HttpTransport:
    val seen = ArrayBuffer.empty[HttpRequest]
    private def ok(body: String) = Right(HttpResponse(200, body, Map.empty))
    def execute(r: HttpRequest): Either[String, HttpResponse] =
      synchronized(seen += r)
      val uri = URI.create(r.url)
      if uri.getPath == "/creds" then
        ok("""{"AccessKeyId":"fake","SecretAccessKey":"fake","Token":"fake"}""")
      else if uri.getHost.endsWith(".s3.us-east-2.amazonaws.com") then
        val bucket = uri.getHost.takeWhile(_ != '.')
        Option(uri.getRawQuery).filter(_.startsWith("list-type=2")) match
          case Some(q) =>
            val prefix = URLDecoder.decode(q.split("prefix=")(1).takeWhile(_ != '&'), UTF_8)
            val keys = objects.keys.filter(_.startsWith(s"s3://$bucket/$prefix"))
            ok(keys.map(k => s"<Key>${k.stripPrefix(s"s3://$bucket/")}</Key>").mkString)
          case None =>
            objects
              .getText(s"s3://$bucket${uri.getPath}")
              .fold(_ => Right(HttpResponse(404, "", Map.empty)), ok)
      else Right(other(r))

  /** A hand-built Spark export of the query fixture: run manifest, load manifests and rows. */
  def export_(rowCountSkew: Int = 0): (InMemoryObjectStore, String, String) =
    val manifest = ujson.write(
      ujson.Obj(
        "snapshot" -> Snapshot,
        "inputs" -> ujson.Obj(
          "entityResolution" -> ujson.Obj(
            "runId" -> ErRunId,
            "policyVersion" -> cfg.entityResolution.policyVersion
          )
        ),
        "graph" -> ujson.Obj(
          "metricVersion" -> cfg.graph.metricVersion,
          "policyVersion" -> cfg.graph.policyVersion
        )
      )
    )
    val runId = Ids.run(manifest)
    val out = "s3://sc-graph/runs/t/spark"
    val folder = s"$out/spark/snapshot=$Snapshot/run_id=$runId"
    val objects = new InMemoryObjectStore
    def put(uri: String, text: String) =
      objects.putIfAbsent(uri, text.getBytes(UTF_8), "application/json")
    val manifestUri = s"$out/run-manifests/$runId.json"
    put(manifestUri, manifest)
    val nodeCounts =
      Vector(GraphLabels.Researcher, GraphLabels.Topic, GraphLabels.Institution).map { label =>
        val rows = nodes.filter(_.label == label)
        if rows.nonEmpty then
          put(
            s"$folder/jsonl/graph-nodes/label=$label/part-0.json",
            rows.map(n => JsonLines.encode(n)).mkString("\n")
          )
        s"label=$label" -> ujson.Num((rows.size + rowCountSkew).toDouble)
      }
    val metricCounts = cfg.graph.windows.map(_.label).map { w =>
      val rows = metricRows.filter(_.window.label == w)
      if rows.nonEmpty then
        put(
          s"$folder/jsonl/researcher-metrics/windowLabel=$w/part-0.json",
          rows.map(m => JsonLines.encode(m)).mkString("\n")
        )
      s"windowLabel=$w" -> ujson.Num(rows.size.toDouble)
    }
    def entry(counts: Vector[(String, ujson.Num)]) =
      ujson.Obj("rowsByPartition" -> ujson.Obj.from(counts))
    put(
      s"$folder/load-manifests/neptune.json",
      ujson.write(ujson.Obj("runId" -> runId, "nodes" -> entry(nodeCounts)))
    )
    put(
      s"$folder/load-manifests/dynamodb.json",
      ujson.write(
        ujson.Obj(
          "runId" -> runId,
          "tables" -> ujson.Arr(ujson.Obj("source" -> entry(metricCounts)))
        )
      )
    )
    (objects, manifestUri, runId)

class QueryWiringSuite extends munit.FunSuite:
  // Hang guard only: the first test pays one-time JVM setup (RSA keys, IO runtime, class
  // loading), over 30s in a loaded `sbt check`. Product timeouts are unchanged.
  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")
  import WiringFx.{env, jwks, token, FakeAws}

  private def assemble(e: Map[String, String], aws: FakeAws, sink: MetricsSink) =
    QueryWiring.assemble(e, aws, Some(jwks), sink).fold(fail(_), identity)

  test("without a served run: live, not ready, 401 before 503, and no AWS call") {
    val aws = new FakeAws()
    val app = assemble(env, aws, new CollectingMetrics)
    assertEquals((app.name, app.port), ("query-service", 8080))
    app.background.unsafeRunSync()
    assertEquals(send(app.routes, get("/healthz", None))._1.status, Status.Ok)
    val (ready, why) = send(app.routes, get("/readyz", None))
    assertEquals(ready.status, Status.ServiceUnavailable)
    assert(err(why)("details").obj.contains("serving-run"))
    assertEquals(
      send(app.routes, post(body(AliceQ), token = None))._1.status,
      Status.Unauthorized
    )
    val (r, b) = send(app.routes, post(body(AliceQ), token = token, key = Some("k1")))
    assertEquals((r.status, err(b)("code").str), (Status.ServiceUnavailable, "unavailable"))
    assert(err(b)("traceId").str.nonEmpty)
    assertEquals(aws.seen.size, 0)
  }

  test("a served run loaded from S3 makes the service ready") {
    val (objects, uri, _) = WiringFx.export_()
    val app = assemble(
      env + (QueryWiring.ServingEnv -> uri),
      new FakeAws(objects),
      new CollectingMetrics
    )
    app.background.unsafeRunSync()
    assertEquals(send(app.routes, get("/readyz", None))._1.status, Status.Ok)
  }

  test("a load that fails its row count leaves the service live and not ready") {
    val (objects, uri, _) = WiringFx.export_(rowCountSkew = 1)
    val app = assemble(
      env + (QueryWiring.ServingEnv -> uri),
      new FakeAws(objects),
      new CollectingMetrics
    )
    app.background.unsafeRunSync()
    assertEquals(send(app.routes, get("/readyz", None))._1.status, Status.ServiceUnavailable)
    assertEquals(send(app.routes, get("/healthz", None))._1.status, Status.Ok)
  }

  test("the served run's identity, directory and ranking come from the export") {
    val (objects, uri, runId) = WiringFx.export_()
    val data = ServingData.load(cfg, objects, uri).fold(fail(_), identity)
    assertEquals(
      (data.ctx.graphRunId, data.ctx.snapshot, data.ctx.erRunId),
      (runId, Snapshot, ErRunId)
    )
    assertEquals(data.directory.byId(Alice).map(_.map(_.displayName)), Right(Some("Alice Arden")))
    val top = data.ranking.top(
      Recent,
      cfg.graph.metricVersion,
      CollaborationMetric.values.head,
      3,
      None
    )
    assert(top.isRight)
    assert(ServingData.load(cfg, objects, "s3://sc-graph/other.json").isLeft)
    val tampered = new InMemoryObjectStore
    tampered.putIfAbsent(uri, "{}".getBytes(UTF_8), "application/json")
    assert(ServingData.load(cfg, tampered, uri).left.exists(_.contains("hash")))
  }

  test("configuration: local mode, a missing bucket and a plain-http JWKS URL are refused") {
    def refused(e: Map[String, String]) = QueryWiring.assemble(e, new FakeAws()).isLeft
    assert(refused(env - "SCHOLARCHAIN_MODE"))
    assert(refused(env - "RAG_BUCKET"))
    assert(refused(env - "EMBEDDING_MODEL_ID"))
    assert(refused(env + ("COGNITO_JWKS_URL" -> "http://jwks.test.invalid")))
  }

  private val creds = new StaticCredentials(AwsCredentials("fake", "fake"))
  private val repoRoot =
    Iterator
      .iterate(java.nio.file.Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => java.nio.file.Files.isDirectory(p.resolve("prompts")))
      .getOrElse(sys.error("no prompts directory above the working directory"))
  private val llmEnv = env ++ Map(
    "LLM_ENABLED" -> "true",
    "AWS_REGION" -> "us-west-2",
    "SYNTHESIS_MODEL_ID" -> "us.anthropic.test-model-v1:0",
    QueryWiring.PromptRootEnv -> repoRoot.toString
  )
  private def config(e: Map[String, String]) = Bootstrap.config(e).fold(fail(_), identity)

  test("LLM_ENABLED is off by default and builds no model client") {
    val aws = new FakeAws()
    assertEquals(config(env).models.llmEnabled, false)
    assertEquals(
      QueryWiring.synthesizer(config(env), env, creds, aws),
      Right(DeterministicSynthesizer)
    )
  }

  test("LLM_ENABLED=true calls Bedrock in the configured Region with the configured model") {
    // The model call fails (HTTP 500), so the workflow falls back to the deterministic draft.
    val aws = new FakeAws()
    val synth =
      QueryWiring.synthesizer(config(llmEnv), llmEnv, creds, aws).fold(fail(_), identity)
    assert(synth.isInstanceOf[LlmSynthesizer])
    val rig = new Rig(synthesizer = synth)
    val (r, b) = send(rig.routes, post(body(AliceQ)))
    assertEquals(r.status, Status.Ok, b)
    val urls = aws.seen.map(_.url)
    assertEquals(urls.size, 1)
    assert(
      urls.head.startsWith(
        "https://bedrock-runtime.us-west-2.amazonaws.com/model/us.anthropic.test-model-v1%3A0/invoke"
      ),
      urls.head
    )
  }

  test("LLM_ENABLED=true without a model ID or the prompt file does not start") {
    def build(e: Map[String, String]) =
      QueryWiring.assemble(e, new FakeAws(), Some(jwks))
    assert(build(llmEnv - "SYNTHESIS_MODEL_ID").isLeft)
    assert(build(llmEnv + (QueryWiring.PromptRootEnv -> "/nonexistent")).isLeft)
    assert(build(llmEnv).isRight)
  }
