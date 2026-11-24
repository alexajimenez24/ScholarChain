package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.{
  AwsHttp,
  EnvironmentCredentials,
  HttpRequest,
  HttpResponse,
  HttpTransport,
  JdkHttpTransport
}
import scholarchain.storage.Fixtures.{cfg, layout}
import scholarchain.storage.VecFx.{dim, vcfg}

/** The bounded AWS integration run. It is OFF by default: it runs only when the environment
  * variable `SCHOLARCHAIN_AWS_IT` is exactly `true`. `sbt check` never sets it, so the suite is
  * ignored (reported NOT RUN) and no AWS call is made.
  *
  * Opt in, in a shell that holds real credentials and the cloud configuration:
  * {{{
  *   SCHOLARCHAIN_AWS_IT=true SCHOLARCHAIN_IT_VECTOR_INDEX=<existing index> \
  *     RAW_BUCKET=... CURATED_BUCKET=... GRAPH_BUCKET=... RAG_BUCKET=... \
  *     DDB_RUN_LEDGER=... DDB_ENTITY_LINK_CACHE=... DDB_QUERY_SESSION=... DDB_EVIDENCE_BUNDLE=... \
  *     DDB_ARTIFACT_REGISTRY=... DDB_RESEARCHER_METRIC=... VECTOR_BUCKET=... \
  *     EMBEDDING_MODEL_ID=... EMBEDDING_DIMENSION=... sbt "storage/testOnly *AwsIntegrationSuite"
  * }}}
  * The run creates no bucket, table or index (Terraform owns them): it uses the configured ones.
  * The vector index must be a dedicated TEST index named by `SCHOLARCHAIN_IT_TEST_VECTOR_INDEX`;
  * the run refuses a production index name (the `VectorConfig.indexes` values), so test vectors
  * never reach RAG queries. The bound is enforced, not assumed: every HTTP attempt (a retry counts)
  * passes through [[BoundedTransport]], which refuses every request after `MaxCalls` (25) and makes
  * the run fail. Logical calls are about 14 (S3 create-only puts, DynamoDB conditional writes, one
  * Bedrock embedding, S3 Vectors put, get and query). It leaves 1 log object, 1 QuerySession item
  * (expires by TTL) and 2 vectors named `it-<tag>-a|b` in the test index; nothing is deleted by the
  * test (see the cleanup note in the report).
  */
object AwsIntegration:
  val OptInVar = "SCHOLARCHAIN_AWS_IT"
  val IndexVar = "SCHOLARCHAIN_IT_TEST_VECTOR_INDEX"
  val MaxCalls = 25

  /** Counts every HTTP attempt (retries included) and refuses all attempts after `max`. */
  final class BoundedTransport(inner: HttpTransport, val max: Int = MaxCalls) extends HttpTransport:
    private var n = 0
    def attempts: Int = synchronized(n)
    def exceeded: Boolean = synchronized(n > max)
    def execute(r: HttpRequest): Either[String, HttpResponse] =
      val ok = synchronized { n += 1; n <= max }
      if ok then inner.execute(r) else Left(s"integration call bound of $max exceeded")

  def enabled(env: Map[String, String]): Boolean = env.get(OptInVar).contains("true")

  enum Result:
    case NotRun(reason: String)
    case Passed(checks: Vector[String])
    case Failed(reason: String)

  final case class Backends(
      objects: ObjectStore,
      tables: DynamoTables,
      vectors: VectorStore,
      embedder: Embedder,
      layout: S3Layout,
      index: String
  )

  def run(env: Map[String, String], transport: => HttpTransport): Result =
    if !enabled(env) then Result.NotRun(s"$OptInVar is not 'true'; no AWS call was made")
    else
      lazy val bounded = new BoundedTransport(transport)
      val built = for
        c <- ConfigLoader.load(env)
        index <- env.get(IndexVar).filter(_.trim.nonEmpty).toRight(s"$IndexVar is not set")
        _ <- Either.cond(
          !c.vectors.indexes.values.toSet.contains(index),
          (),
          s"$IndexVar names a production index ($index); use a dedicated test index"
        )
        http = new AwsHttp(
          c.application.region,
          new EnvironmentCredentials(env),
          maxAttempts = 3,
          transport = bounded
        )
        tables <- DynamoTables(new DynamoDbStore(http, c.application.region), c.dynamo)
        embedder <- BedrockEmbedder.fromConfig(http, c.application.region, c.models, c.vectors)
      yield Backends(
        new S3ObjectStore(http, c.application.region),
        tables,
        new S3VectorsStore(http, c.application.region, c.vectors.vectorBucket),
        embedder,
        new S3Layout(c.storage),
        index
      )
      built.flatMap(checks(_, "it-" + java.util.UUID.randomUUID().toString.take(8))) match
        case _ if bounded.exceeded =>
          Result.Failed(s"more than $MaxCalls HTTP attempts (${bounded.attempts})")
        case Right(done) => Result.Passed(done)
        case Left(why)   => Result.Failed(why)

  /** The checks themselves, over any backends (the real adapters, or fakes in the unit tests). */
  def checks(b: Backends, tag: String): Either[String, Vector[String]] =
    val runId = Ids.run(s"""{"integration":"$tag"}""")
    val writer = new ArtifactWriter(b.objects, b.layout)
    def need[A, E](what: String, r: Either[E, A]): Either[String, A] = r.left.map(e => s"$what: $e")
    for
      one <- need(
        "s3 create",
        writer.write(
          Zone.Logs,
          "integration-test",
          runId,
          "check.txt",
          tag.getBytes("UTF-8"),
          "text/plain"
        )
      )
      _ <- Either.cond(one._2 == WriteOutcome.Created, (), "s3 first write was not Created")
      two <- need(
        "s3 replay",
        writer.write(
          Zone.Logs,
          "integration-test",
          runId,
          "check.txt",
          tag.getBytes("UTF-8"),
          "text/plain"
        )
      )
      _ <- Either.cond(two._2 == WriteOutcome.Unchanged, (), "s3 replay was not Unchanged")
      _ <- Either.cond(
        writer
          .write(
            Zone.Logs,
            "integration-test",
            runId,
            "check.txt",
            "other".getBytes("UTF-8"),
            "text/plain"
          )
          .isLeft,
        (),
        "s3 overwrote an existing object"
      )
      turn = SessionTurn(tag, 1, "q-1", "integration question", None, "ok")
      c1 <- need("dynamodb create", b.tables.sessions.append(turn))
      _ <- Either.cond(c1 == Stored.Created, (), "dynamodb first write was not Created")
      c2 <- need("dynamodb replay", b.tables.sessions.append(turn))
      _ <- Either.cond(c2 == Stored.Unchanged, (), "dynamodb replay was not Unchanged")
      _ <- Either.cond(
        b.tables.sessions.append(turn.copy(question = "different")).isLeft,
        (),
        "dynamodb overwrote an existing turn"
      )
      vec <- need("bedrock embed", b.embedder.embed("ScholarChain integration check"))
      _ <- Either.cond(vec.size == b.embedder.dimension, (), "embedding has the wrong dimension")
      md = Map[String, MetaValue](
        "documentType" -> MetaValue.Str("integration-test"),
        "runTag" -> MetaValue.Str(tag)
      )
      recs = Vector(VectorRecord(s"$tag-a", vec, md), VectorRecord(s"$tag-b", vec.reverse, md))
      _ <- need("s3vectors put", b.vectors.put(b.index, recs).left.map(_.text))
      got <- need("s3vectors get", b.vectors.get(b.index, recs.map(_.key)).left.map(_.text))
      _ <- Either.cond(
        got.map(_.key).toSet == recs.map(_.key).toSet,
        (),
        "get did not return both vectors"
      )
      hits <- need(
        "s3vectors query",
        b.vectors
          .query(b.index, vec, 2, Vector(FilterTerm.Eq("runTag", MetaValue.Str(tag))))
          .left
          .map(_.text)
      )
      _ <- Either.cond(
        hits.map(_.key).toSet == recs.map(_.key).toSet,
        (),
        "filtered query missed a vector"
      )
    yield Vector(
      "s3 create-only write",
      "dynamodb conditional write",
      "bedrock embedding",
      "s3vectors put/get/query"
    )

/** Always runs: proves the gate is closed by default and a closed gate makes no call. */
class AwsGateSuite extends munit.FunSuite:
  private final class CountingTransport extends HttpTransport:
    var calls = 0
    def execute(r: HttpRequest) =
      calls += 1
      Left("a test must not reach the network")

  test("the gate is off by default: only the exact value 'true' opens it") {
    assertEquals(AwsIntegration.enabled(Map.empty), false)
    Seq("", "1", "yes", "TRUE", "True", "false", " true").foreach(v =>
      assertEquals(AwsIntegration.enabled(Map(AwsIntegration.OptInVar -> v)), false, v)
    )
    assertEquals(AwsIntegration.enabled(Map(AwsIntegration.OptInVar -> "true")), true)
  }

  test("the integration suite is ignored exactly when the gate is closed") {
    assertEquals(new AwsIntegrationSuite().munitIgnore, !AwsIntegration.enabled(sys.env))
    assertEquals(AwsIntegration.enabled(Map(AwsIntegration.OptInVar -> "false")), false)
    assertEquals(AwsIntegration.enabled(Map(AwsIntegration.OptInVar -> "true")), true)
  }

  test("the bound counts every HTTP attempt and refuses the ones after MaxCalls") {
    val inner = new CountingTransport
    val b = new AwsIntegration.BoundedTransport(inner)
    val req = HttpRequest(
      "GET",
      "http://localhost/x",
      Map.empty,
      Array.emptyByteArray,
      java.time.Duration.ofSeconds(1)
    )
    (1 to AwsIntegration.MaxCalls).foreach(_ => b.execute(req))
    assertEquals((inner.calls, b.attempts, b.exceeded), (AwsIntegration.MaxCalls, 25, false))
    val over = b.execute(req)
    assert(over.left.exists(_.contains("bound")), over.toString)
    (1 to 10).foreach(_ => b.execute(req))
    assertEquals(inner.calls, AwsIntegration.MaxCalls) // the inner transport is never reached again
    assertEquals(b.exceeded, true)
  }

  test("an opened gate refuses a production vector index before any call") {
    val t = new CountingTransport
    val prod = Fixtures.cfg.vectors.indexes.values.head
    val open = Map(AwsIntegration.OptInVar -> "true", AwsIntegration.IndexVar -> prod)
    val r = AwsIntegration.run(Fixtures.env ++ open, t)
    assert(r.toString.contains("production index"), r.toString)
    assertEquals(t.calls, 0)
  }

  test("a closed gate returns NOT RUN and makes no AWS call, even with every other variable set") {
    val t = new CountingTransport
    val full = Fixtures.env ++ Map(
      AwsIntegration.IndexVar -> "researcher-profile-v1",
      "AWS_ACCESS_KEY_ID" -> "AKIDEXAMPLE"
    )
    Seq(Map.empty[String, String], full, full.updated(AwsIntegration.OptInVar, "1")).foreach {
      env =>
        AwsIntegration.run(env, t) match
          case AwsIntegration.Result.NotRun(why) => assert(why.contains("no AWS call"))
          case other                             => fail(s"expected NotRun, got $other")
    }
    assertEquals(t.calls, 0)
  }

  test("an opened gate with no configuration fails before any call, naming what is missing") {
    val t = new CountingTransport
    val r = AwsIntegration.run(Map(AwsIntegration.OptInVar -> "true"), t)
    assert(r.isInstanceOf[AwsIntegration.Result.Failed], r.toString)
    assertEquals(t.calls, 0)
    val noIndex = AwsIntegration.run(Fixtures.env + (AwsIntegration.OptInVar -> "true"), t)
    assertEquals(noIndex, AwsIntegration.Result.Failed(s"${AwsIntegration.IndexVar} is not set"))
    assertEquals(t.calls, 0)
  }

  test("the integration suite is not wired into the build's default test settings") {
    val build = java.nio.file.Files.readString(java.nio.file.Path.of("build.sbt"))
    assert(!build.contains(AwsIntegration.OptInVar), "build.sbt must not set the opt-in")
  }

/** Checks the harness itself over in-memory backends (the same assertions run against AWS). */
class AwsIntegrationHarnessSuite extends munit.FunSuite:
  private def backends(index: String = "researcher-profile-v1") =
    val vectors = new InMemoryVectorStore
    vectors.createBucket()
    vectors.createIndex(VectorIndexes.spec("researcher-profile-v1", dim))
    AwsIntegration.Backends(
      new InMemoryObjectStore,
      DynamoTables(new InMemoryKeyValueStore, cfg.dynamo).toOption.get,
      vectors,
      new HashingEmbedder(dim),
      layout,
      index
    )

  test("the checks pass over the fakes, are bounded, and leave only tagged data") {
    val b = backends()
    val done = AwsIntegration.checks(b, "it-test0001")
    assertEquals(done.map(_.size), Right(4))
    assertEquals(
      b.vectors.asInstanceOf[InMemoryVectorStore].keys("researcher-profile-v1"),
      Vector("it-test0001-a", "it-test0001-b")
    )
  }

  test("a missing index is reported, not thrown") {
    assert(
      AwsIntegration
        .checks(backends("no-such-index"), "it-test0002")
        .left
        .exists(_.contains("s3vectors put"))
    )
  }

/** The gated suite. Ignored unless opted in (see [[AwsIntegration]]); NOT RUN in `sbt check`. */
class AwsIntegrationSuite extends munit.FunSuite:
  override def munitIgnore: Boolean = !AwsIntegration.enabled(sys.env)

  test("bounded AWS integration: S3, DynamoDB, Bedrock and S3 Vectors") {
    AwsIntegration.run(sys.env, new JdkHttpTransport()) match
      case AwsIntegration.Result.Passed(done) => assertEquals(done.size, 4)
      case other                              => fail(other.toString)
  }
