package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.AwsHttp
import scholarchain.storage.Fixtures.{cfg, http, layout, withStub}
import scala.collection.mutable

object VecFx:
  val dim = 16
  val mv = "metrics-v1"
  val vcfg: VectorConfig = cfg.vectors.copy(vectorBucket = "sc-vectors", dimension = dim)
  val run: String = Fixtures.runId
  val r1 = "sc:r:v1:0123456789abcdef0123"
  val r2 = "orcid:0000-0002-7347-9716"
  val w2010 = TimeWindow(2010, 2014)
  val w1950 = TimeWindow(1950, 2026)
  val instant = java.time.Instant.parse("2026-11-23T12:00:00Z")
  val noWait: RetryPolicy = RetryPolicy(3, 1L, _ => ())

  def doc(
      docType: String,
      entity: String,
      text: String,
      researchers: Vector[String] = Vector.empty,
      window: Option[TimeWindow] = Some(w2010),
      runId: String = run,
      mutate: SemanticDocument => SemanticDocument = identity
  ): SemanticDocument =
    mutate(
      SemanticDocument(
        SemanticDocument.Version,
        docType,
        entity,
        Ids.chunk(entity, mv, docType, 0, text),
        text,
        Hashing.tagged(text),
        window,
        researchers,
        None,
        None,
        None,
        vcfg.indexes.get(SemanticDocument.IndexKeys(docType)),
        SourceType.SCHOLARCHAIN,
        s"spark:$entity",
        "2026-11-22",
        runId,
        Some(Ids.evidence(EvidenceType.SEMANTIC_PROFILE, entity)),
        "s3://sc-graph/spark/semantic-summaries"
      )
    )

  def profile(n: Int, text: String = "") =
    doc(
      SemanticDocument.ResearcherProfile,
      s"sc:r:v1:${"%020x".format(n)}",
      if text.isEmpty then s"Researcher profile number $n works on graph topics and databases"
      else text,
      Vector(s"sc:r:v1:${"%020x".format(n)}")
    )

  /** One valid document for each of the five document types. */
  def five: Vector[SemanticDocument] = Vector(
    profile(1),
    doc(SemanticDocument.CommunityProfile, "cm-1", "Community of five researchers on databases"),
    doc(
      SemanticDocument.PaperAbstractType,
      "dblp:conf/x/Paper1",
      "An abstract about distributed graph processing",
      window = None
    ),
    doc(
      SemanticDocument.PathSummary,
      "ev:graph_path:0123456789abcdef",
      "Path from one researcher to another over two joint works",
      Vector(r1, r2)
    ),
    doc(SemanticDocument.TopicSummary, "openalex:T10001", "Topic summary of graph databases")
  )

  /** Counts calls and injects failures; everything else goes to the in-memory store. */
  final class CountingStore(inner: VectorStore) extends VectorStore:
    val puts = mutable.Buffer.empty[Int]
    var gets = 0
    var putCalls = 0
    val putFailures = mutable.Queue.empty[RemoteError]
    val getFailures = mutable.Queue.empty[RemoteError]
    def createBucket() = inner.createBucket()
    def createIndex(spec: IndexSpec) = inner.createIndex(spec)
    def put(index: String, vectors: Vector[VectorRecord]) =
      putCalls += 1
      if putFailures.nonEmpty then Left(putFailures.dequeue())
      else
        puts += vectors.size
        inner.put(index, vectors)
    def get(index: String, keys: Vector[String]) =
      gets += 1
      if getFailures.nonEmpty then Left(getFailures.dequeue()) else inner.get(index, keys)
    def query(index: String, vector: Vector[Float], topK: Int, filter: Vector[FilterTerm]) =
      inner.query(index, vector, topK, filter)

  final class FixedEmbedder(val modelId: String, val dimension: Int, f: String => Vector[Float])
      extends Embedder:
    var calls = 0
    val failures = mutable.Queue.empty[RemoteError]
    def embed(text: String) =
      calls += 1
      if failures.nonEmpty then Left(failures.dequeue()) else Right(f(text))

  final class Rig(
      val embedder: Embedder = new HashingEmbedder(dim),
      settings: VectorLoaderSettings = VectorLoaderSettings(
        chunkSourceVersion = Some(mv),
        retry = noWait
      )
  ):
    val objects = new InMemoryObjectStore
    val kv = new InMemoryKeyValueStore
    val fake = new InMemoryVectorStore
    val store = new CountingStore(fake)
    val tables = DynamoTables(kv, cfg.dynamo).toOption.get
    val writer = new ArtifactWriter(objects, layout)
    fake.createBucket()
    SemanticDocument.IndexKeys.values.foreach(k =>
      fake.createIndex(VectorIndexes.spec(vcfg.indexes(k), dim))
    )
    val loader =
      VectorLoader(store, embedder, writer, tables.runLedger, vcfg, settings).toOption.get
    def load(docs: Seq[SemanticDocument], expected: Option[Long] = None) =
      loader.load(run, RowSource.of(docs), expected)
    def ledger = tables.runLedger.get(run, loader.Stage).toOption.get.map(_.value)

import VecFx.*

class VectorLimitsSuite extends munit.FunSuite:
  private val spec = VectorIndexes.spec("researcher-profile-v1", 4)
  private def rec(md: Map[String, MetaValue], data: Vector[Float] = Vector(1f, 0f, 0f, 0f)) =
    VectorRecord("k1", data, md)

  test("metadata values round-trip through JSON; booleans and fractions are refused") {
    val values = Vector(
      MetaValue.Str("a"),
      MetaValue.Num(2010L),
      MetaValue.Strs(Vector("x", "y")),
      MetaValue.Strs(Vector.empty)
    )
    values.foreach(v => assertEquals(MetaValue.fromJson(v.toJson), Right(v)))
    assert(MetaValue.fromJson(ujson.Bool(true)).isLeft)
    assert(MetaValue.fromJson(ujson.Num(1.5)).isLeft)
    assert(MetaValue.fromJson(ujson.Arr(ujson.Num(1))).isLeft)
    assert(MetaValue.fromJson(ujson.Null).isLeft)
  }

  test("a valid record passes; key, dimension and non-finite values are refused") {
    assert(VectorLimits.validate(rec(Map("a" -> MetaValue.Str("b"))), spec).isRight)
    assert(VectorLimits.validate(rec(Map.empty).copy(key = ""), spec).isLeft)
    assert(VectorLimits.validate(rec(Map.empty).copy(key = "k" * 1025), spec).isLeft)
    assert(VectorLimits.validate(rec(Map.empty, Vector(1f, 0f)), spec).isLeft)
    assert(VectorLimits.validate(rec(Map.empty, Vector(1f, Float.NaN, 0f, 0f)), spec).isLeft)
  }

  test("metadata limits: 50 keys, 2 KB filterable, 2 KB per string; non-filterable is exempt") {
    def keys(n: Int) = (1 to n).map(i => s"k$i" -> MetaValue.Num(i.toLong)).toMap
    assert(VectorLimits.validate(rec(keys(50)), spec).isRight)
    assert(VectorLimits.validate(rec(keys(51)), spec).left.exists(_.contains("50")))
    val big = MetaValue.Str("x" * 3000)
    assert(VectorLimits.validate(rec(Map("a" -> big)), spec).left.exists(_.contains("2048")))
    val filterable = (1 to 30).map(i => s"key$i" -> MetaValue.Str("v" * 80)).toMap
    assert(
      VectorLimits.validate(rec(filterable), spec).left.exists(_.contains("filterable metadata"))
    )
    // the same bytes under a non-filterable key are allowed (up to the 40 KB total)
    assert(
      VectorLimits
        .validate(rec(Map("textUri" -> MetaValue.Str("s3://b/" + "k" * 1500))), spec)
        .isRight
    )
  }

  test("index specs: name length, dimension, at most 10 non-filterable keys") {
    assert(VectorLimits.validateSpec(IndexSpec("ab", 4, Vector.empty)).isLeft)
    assert(VectorLimits.validateSpec(IndexSpec("abc", 0, Vector.empty)).isLeft)
    assert(VectorLimits.validateSpec(IndexSpec("abc", 4097, Vector.empty)).isLeft)
    assert(
      VectorLimits.validateSpec(IndexSpec("abc", 4, (1 to 11).map(i => s"k$i").toVector)).isLeft
    )
    assert(VectorLimits.validateSpec(IndexSpec("abc", 4, Vector.empty, "dot")).isLeft)
    assert(VectorLimits.validateSpec(VectorIndexes.spec("abc", 4096)).isRight)
  }

  test("retry policy: transient only, bounded, with doubling backoff; permanent is not retried") {
    val waits = mutable.Buffer.empty[Long]
    val p = RetryPolicy(4, 10L, w => waits += w)
    var n = 0
    val r = p.run { n += 1; Left(RemoteError.Transient("busy")) }
    assertEquals(n, 4)
    assertEquals(waits.toVector, Vector(10L, 20L, 40L))
    assertEquals(r, Left(RemoteError.Transient("busy")))
    n = 0
    assert(p.run { n += 1; Left(RemoteError.Permanent("no")) }.isLeft)
    assertEquals(n, 1)
    n = 0
    assertEquals(
      p.run { n += 1; if n < 3 then Left(RemoteError.Transient("x")) else Right(n) },
      Right(3)
    )
    assertEquals(RetryPolicy(1).run { n += 1; Left(RemoteError.Transient("x")) }.isLeft, true)
    intercept[IllegalArgumentException](RetryPolicy(0))
  }

class InMemoryVectorStoreSuite extends munit.FunSuite:
  private def store =
    val s = new InMemoryVectorStore
    s.createBucket()
    s.createIndex(VectorIndexes.spec("idx-a", 3))
    s

  private def rec(k: String, v: Vector[Float], md: Map[String, MetaValue] = Map.empty) =
    VectorRecord(k, v, md)

  test("bucket and index creation are idempotent; a different spec under one name is refused") {
    val s = new InMemoryVectorStore
    assert(s.createIndex(VectorIndexes.spec("idx-a", 3)).isLeft) // no bucket yet
    assertEquals(s.createBucket(), Right(PutOutcome.Created))
    assertEquals(s.createBucket(), Right(PutOutcome.AlreadyExists))
    assertEquals(s.createIndex(VectorIndexes.spec("idx-a", 3)), Right(PutOutcome.Created))
    assertEquals(s.createIndex(VectorIndexes.spec("idx-a", 3)), Right(PutOutcome.AlreadyExists))
    assert(s.createIndex(VectorIndexes.spec("idx-a", 4)).isLeft)
  }

  test("put, get and query: cosine order, topK, metadata and equality, range and list filters") {
    val s = store
    val md = (id: String, y: Long, rs: Vector[String]) =>
      Map[String, MetaValue](
        "documentType" -> MetaValue.Str("researcher-profile"),
        "windowStartYear" -> MetaValue.Num(y),
        "researcherIds" -> MetaValue.Strs(rs)
      )
    assertEquals(
      s.put(
        "idx-a",
        Vector(
          rec("a", Vector(1f, 0f, 0f), md("a", 2010, Vector("r1"))),
          rec("b", Vector(0.9f, 0.1f, 0f), md("b", 2015, Vector("r1", "r2"))),
          rec("c", Vector(0f, 1f, 0f), md("c", 2010, Vector("r3")))
        )
      ),
      Right(())
    )
    val all = s.query("idx-a", Vector(1f, 0f, 0f), 10, Vector.empty).toOption.get
    assertEquals(all.map(_.key), Vector("a", "b", "c"))
    assertEquals(s.query("idx-a", Vector(1f, 0f, 0f), 2, Vector.empty).toOption.get.size, 2)
    val byList = Vector(FilterTerm.Eq("researcherIds", MetaValue.Str("r2")))
    assertEquals(
      s.query("idx-a", Vector(1f, 0f, 0f), 10, byList).toOption.get.map(_.key),
      Vector("b")
    )
    val range =
      Vector(FilterTerm.Gte("windowStartYear", 2012), FilterTerm.Lte("windowStartYear", 2020))
    assertEquals(
      s.query("idx-a", Vector(1f, 0f, 0f), 10, range).toOption.get.map(_.key),
      Vector("b")
    )
    val both = Vector(
      FilterTerm.Eq("documentType", MetaValue.Str("researcher-profile")),
      FilterTerm.Eq("windowStartYear", MetaValue.Num(2010L))
    )
    assertEquals(
      s.query("idx-a", Vector(1f, 0f, 0f), 10, both).toOption.get.map(_.key),
      Vector("a", "c")
    )
    assertEquals(s.get("idx-a", Vector("c", "zzz")).toOption.get.map(_.key), Vector("c"))
    assertEquals(
      s.get("idx-a", Vector("a")).toOption.get.head.metadata,
      md("a", 2010, Vector("r1"))
    )
  }

  test("put refuses a bad batch whole: size, duplicate keys, wrong dimension, unknown index") {
    val s = store
    assert(s.put("idx-a", Vector.empty).isLeft)
    assert(s.put("idx-a", (1 to 501).map(i => rec(s"k$i", Vector(1f, 0f, 0f))).toVector).isLeft)
    assert(
      s.put("idx-a", Vector(rec("k", Vector(1f, 0f, 0f)), rec("k", Vector(0f, 1f, 0f)))).isLeft
    )
    assert(s.put("idx-a", Vector(rec("ok", Vector(1f, 0f, 0f)), rec("bad", Vector(1f)))).isLeft)
    assertEquals(s.keys("idx-a"), Vector.empty[String])
    assert(s.put("nope", Vector(rec("k", Vector(1f, 0f, 0f)))).left.exists(!_.isTransient))
    assert(s.get("idx-a", (1 to 101).map(_.toString).toVector).isLeft)
    assert(s.query("idx-a", Vector(1f, 0f, 0f), 101).isLeft)
    assert(s.query("idx-a", Vector(1f), 1).isLeft)
  }

class S3VectorsStubSuite extends munit.FunSuite:
  private def md = Map[String, MetaValue](
    "documentType" -> MetaValue.Str("researcher-profile"),
    "windowStartYear" -> MetaValue.Num(2010L),
    "researcherIds" -> MetaValue.Strs(Vector("r1", "r2")),
    "textUri" -> MetaValue.Str("s3://sc-rag/semantic-context/x.json")
  )

  test("PutVectors is a signed POST with bucket, index, float32 data and typed metadata") {
    withStub(_ => (200, "", Map.empty)) { (base, seen) =>
      val s = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some(base))
      assertEquals(
        s.put("researcher-profile-v1", Vector(VectorRecord("c:abc", Vector(0.1f, -0.25f), md))),
        Right(())
      )
      val r = seen.head
      assertEquals((r.method, r.uri), ("POST", "/PutVectors"))
      assert(r.headers("authorization").contains("/us-east-2/s3vectors/aws4_request"))
      val b = ujson.read(r.body)
      assertEquals(b("vectorBucketName").str, "sc-vectors")
      assertEquals(b("indexName").str, "researcher-profile-v1")
      val v = b("vectors")(0)
      assertEquals(v("key").str, "c:abc")
      assertEquals(v("data")("float32").arr.map(_.num.toFloat).toVector, Vector(0.1f, -0.25f))
      assertEquals(v("metadata")("windowStartYear").num, 2010.0)
      assertEquals(v("metadata")("researcherIds").arr.map(_.str).toVector, Vector("r1", "r2"))
      assert(!r.body.contains("secret"))
    }
  }

  test("put refuses an empty or oversized batch before any call") {
    withStub(_ => (200, "", Map.empty)) { (base, seen) =>
      val s = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some(base))
      assert(s.put("idx", Vector.empty).isLeft)
      assert(
        s.put("idx", (1 to 501).map(i => VectorRecord(s"k$i", Vector(1f), Map.empty)).toVector)
          .isLeft
      )
      assertEquals(seen.size, 0)
    }
  }

  test("GetVectors asks for data and metadata and parses the vectors; absent keys are omitted") {
    val reply =
      """{"vectors":[{"key":"c:abc","data":{"float32":[0.5,1.0]},"metadata":{"documentType":"researcher-profile","windowStartYear":2010,"researcherIds":["r1"]}}]}"""
    withStub(_ => (200, reply, Map.empty)) { (base, seen) =>
      val s = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some(base))
      val got = s.get("idx", Vector("c:abc", "c:def")).toOption.get
      assertEquals(got.map(_.key), Vector("c:abc"))
      assertEquals(got.head.data, Vector(0.5f, 1.0f))
      assertEquals(got.head.metadata("windowStartYear"), MetaValue.Num(2010L))
      val b = ujson.read(seen.head.body)
      assertEquals((b("returnData").bool, b("returnMetadata").bool), (true, true))
      assertEquals(b("keys").arr.size, 2)
      assert(s.get("idx", Vector.empty).isLeft)
    }
  }

  test("QueryVectors sends topK, the query vector and the filter shapes, and parses hits") {
    val reply =
      """{"vectors":[{"key":"k1","distance":0.25,"metadata":{"documentType":"collaboration-path"}}]}"""
    withStub(_ => (200, reply, Map.empty)) { (base, seen) =>
      val s = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some(base))
      val hits = s.query("idx", Vector(1f, 0f), 5).toOption.get
      assertEquals(
        hits,
        Vector(VectorHit("k1", 0.25, Map("documentType" -> MetaValue.Str("collaboration-path"))))
      )
      assert(!ujson.read(seen.head.body).obj.contains("filter"))
      s.query(
        "idx",
        Vector(1f, 0f),
        5,
        Vector(
          FilterTerm.Eq("topicId", MetaValue.Str("t1")),
          FilterTerm.Gte("windowStartYear", 2010)
        )
      )
      val f = ujson.read(seen(1).body)("filter")
      assertEquals(f("$and")(0)("topicId")("$eq").str, "t1")
      assertEquals(f("$and")(1)("windowStartYear")("$gte").num, 2010.0)
      s.query("idx", Vector(1f, 0f), 5, Vector(FilterTerm.Eq("topicId", MetaValue.Str("t1"))))
      assertEquals(ujson.read(seen(2).body)("filter")("topicId")("$eq").str, "t1")
      assert(s.query("idx", Vector(1f), 0).isLeft)
    }
  }

  test(
    "CreateVectorBucket and CreateIndex: 409 is AlreadyExists, the index names its non-filterable keys"
  ) {
    val statuses = mutable.Queue(200, 409, 200, 409)
    withStub(_ => (statuses.dequeue(), "{}", Map.empty)) { (base, seen) =>
      val s = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some(base))
      assertEquals(s.createBucket(), Right(PutOutcome.Created))
      assertEquals(s.createBucket(), Right(PutOutcome.AlreadyExists))
      val spec = VectorIndexes.spec("researcher-profile-v1", 256)
      assertEquals(s.createIndex(spec), Right(PutOutcome.Created))
      assertEquals(s.createIndex(spec), Right(PutOutcome.AlreadyExists))
      assertEquals(seen(0).uri, "/CreateVectorBucket")
      val b = ujson.read(seen(2).body)
      assertEquals(seen(2).uri, "/CreateIndex")
      assertEquals(b("dataType").str, "float32")
      assertEquals(b("dimension").num, 256.0)
      assertEquals(b("distanceMetric").str, "cosine")
      assertEquals(
        b("metadataConfiguration")("nonFilterableMetadataKeys").arr.map(_.str).toVector,
        VectorIndexes.NonFilterable
      )
      assert(s.createIndex(IndexSpec("x", 4, Vector.empty)).isLeft)
    }
  }

  test(
    "status mapping: 408, 429, 5xx and a missing response are transient; 400, 402, 403, 404 are not"
  ) {
    val statuses = mutable.Queue(408, 429, 500, 503, 400, 402, 403, 404)
    withStub(_ => (statuses.dequeue(), "denied", Map.empty)) { (base, _) =>
      val s = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some(base))
      val one = Vector(VectorRecord("k", Vector(1f), Map.empty))
      val results = (1 to 8).map(_ => s.put("idx", one).left.toOption.get.isTransient)
      assertEquals(results.toVector, Vector(true, true, true, true, false, false, false, false))
    }
    val down = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some("http://127.0.0.1:1"))
    assert(
      down.put("idx", Vector(VectorRecord("k", Vector(1f), Map.empty))).left.exists(_.isTransient)
    )
  }

  test("a malformed or unsupported reply is a permanent failure, never an exception") {
    val replies = mutable.Queue(
      "not json",
      """{"vectors":[{"key":"k"}]}""",
      """{"vectors":[{"key":"k","data":{"float32":[1]},"metadata":{"a":true}}]}"""
    )
    withStub(_ => (200, replies.dequeue(), Map.empty)) { (base, _) =>
      val s = new S3VectorsStore(http(), "us-east-2", "sc-vectors", Some(base))
      (1 to 3).foreach(_ => assert(s.get("idx", Vector("k")).left.exists(!_.isTransient)))
    }
  }

  test("the adapter's default endpoint is the regional s3vectors host") {
    var url = ""
    val transport = new scholarchain.core.aws.HttpTransport:
      def execute(r: scholarchain.core.aws.HttpRequest) =
        url = r.url
        Right(scholarchain.core.aws.HttpResponse(200, "", Map.empty))
    val creds = new scholarchain.core.aws.StaticCredentials(
      scholarchain.core.aws.AwsCredentials("AKIDEXAMPLE", "example-only", None)
    )
    val aws = new AwsHttp("us-east-2", creds, transport = transport)
    new S3VectorsStore(aws, "us-east-2", "sc-vectors")
      .put("idx", Vector(VectorRecord("k", Vector(1f), Map.empty)))
    assertEquals(url, "https://s3vectors.us-east-2.api.aws/PutVectors")
  }

class BedrockEmbedderSuite extends munit.FunSuite:
  private val model = "amazon.titan-embed-text-v2:0"
  private def reply(n: Int) = ujson
    .Obj("embedding" -> ujson.Arr.from(Seq.fill(n)(ujson.Num(0.5))), "inputTextTokenCount" -> 3)
    .render()

  test("the request is a signed InvokeModel with the configured model, dimensions and normalize") {
    withStub(_ => (200, reply(8), Map.empty)) { (base, seen) =>
      val e = new BedrockEmbedder(http(), "us-east-2", model, 8, endpointOverride = Some(base))
      assertEquals(e.embed("hello graph").toOption.get, Vector.fill(8)(0.5f))
      val r = seen.head
      assertEquals(r.method, "POST")
      assertEquals(r.uri, "/model/amazon.titan-embed-text-v2%3A0/invoke")
      assert(r.headers("authorization").contains("/us-east-2/bedrock/aws4_request"))
      assertEquals(r.headers("accept"), "application/json")
      val b = ujson.read(r.body)
      assertEquals(b("inputText").str, "hello graph")
      assertEquals(b("dimensions").num, 8.0)
      assertEquals(b("normalize").bool, true)
    }
  }

  test("the model ID and dimension come from configuration; a blank model is refused") {
    val models = cfg.models.copy(embeddingModel = model)
    val e = BedrockEmbedder.fromConfig(http(), "us-east-2", models, vcfg).toOption.get
    assertEquals((e.modelId, e.dimension), (model, dim))
    assert(
      BedrockEmbedder
        .fromConfig(http(), "us-east-2", cfg.models.copy(embeddingModel = " "), vcfg)
        .isLeft
    )
    assert(BedrockEmbedder.fromConfig(http(), "us-east-2", models, vcfg.copy(dimension = 0)).isLeft)
  }

  test("a wrong dimension, a missing vector and a bad body are permanent; no text is truncated") {
    val replies = mutable.Queue(reply(7), """{"nothing":1}""", "oops")
    withStub(_ => (200, replies.dequeue(), Map.empty)) { (base, seen) =>
      val e = new BedrockEmbedder(
        http(),
        "us-east-2",
        model,
        8,
        maxChars = 10,
        endpointOverride = Some(base)
      )
      (1 to 3).foreach(_ => assert(e.embed("short").left.exists(!_.isTransient)))
      val calls = seen.size
      assert(e.embed("x" * 11).left.exists(_.text.contains("limit")))
      assert(e.embed("").isLeft)
      assertEquals(seen.size, calls) // refused locally, no call
    }
  }

  test("status mapping: throttling, timeout and 5xx are transient; validation and access are not") {
    val statuses = mutable.Queue(408, 429, 500, 503, 400, 403, 404, 424)
    withStub(_ => (statuses.dequeue(), "err", Map.empty)) { (base, _) =>
      val e = new BedrockEmbedder(http(), "us-east-2", model, 8, endpointOverride = Some(base))
      val flags = (1 to 8).map(_ => e.embed("text").left.toOption.get.isTransient).toVector
      assertEquals(flags, Vector(true, true, true, true, false, false, false, false))
    }
    val down = new BedrockEmbedder(
      http(),
      "us-east-2",
      model,
      8,
      endpointOverride = Some("http://127.0.0.1:1")
    )
    assert(down.embed("text").left.exists(_.isTransient))
  }

  test("the hashing embedder is deterministic, unit length and dimension-sized") {
    val h = new HashingEmbedder(16)
    val a = h.embed("graph databases and coauthorship").toOption.get
    assertEquals(a, h.embed("graph databases and coauthorship").toOption.get)
    assertEquals(a.size, 16)
    assertEqualsDouble(math.sqrt(a.map(x => x.toDouble * x).sum), 1.0, 1e-5)
    assertEquals(h.embed("a b").toOption.get, Vector.fill(16)(0f)) // no token over 2 chars
  }

class SemanticDocumentSuite extends munit.FunSuite:
  private val ev = EvidenceItem(
    SchemaVersions.Evidence,
    Ids.evidence(EvidenceType.SEMANTIC_PROFILE, "x"),
    EvidenceType.SEMANTIC_PROFILE,
    None,
    Vector(r1),
    "s3://sc-graph/spark/semantic-summaries",
    Some(Ids.chunk(r1, mv, "researcher-profile", 0, "The text")),
    None,
    0.0,
    Map(
      "docType" -> "researcher-profile",
      "vectorIndex" -> "researcher-profile-v1",
      "entityId" -> r1,
      "window" -> "2010-2014"
    ),
    Some("The text"),
    Some(Hashing.tagged("The text")),
    Provenance(
      SourceType.SCHOLARCHAIN,
      "spark:x",
      "2026-11-22",
      run,
      SchemaVersions.Evidence,
      Some("metrics-v1")
    )
  )

  test("a Spark semantic-summary row and its rendering become one document per chunk") {
    val rd = RenderFx.stub(maxChunk = 1000).renderSummary(ev).toOption.get
    val ds = SemanticDocument.fromEvidence(ev, rd).toOption.get
    assertEquals(ds.size, 1)
    val d = ds.head
    assertEquals(
      (d.docType, d.entityId, d.window, d.declaredIndex),
      ("researcher-profile", r1, Some(w2010), Some("researcher-profile-v1"))
    )
    assertEquals((d.runId, d.snapshot, d.evidenceId.isDefined), (run, "2026-11-22", true))
    assertEquals(
      (d.template, d.chunkPolicy, d.ordinal, d.chunkCount, d.documentTextHash),
      ("tpl-stub-v1", "chunk-stub-v1", 0, 1, rd.textHash)
    )
    def bad(e: EvidenceItem, part: String, r: RenderedDocument = rd) =
      assert(SemanticDocument.fromEvidence(e, r).left.exists(_.contains(part)), part)
    bad(ev.copy(values = ev.values - "docType"), "docType")
    bad(ev.copy(values = ev.values - "entityId"), "entityId")
    bad(ev.copy(values = ev.values - "window"), "window")
    bad(ev.copy(values = ev.values.updated("window", "2010")), "window")
    bad(ev.copy(schemaVersion = "evidence-v9"), "schema")
    bad(ev.copy(evidenceType = EvidenceType.TEXT_CHUNK), "SEMANTIC_PROFILE")
    // the rendering must name the row's identity
    bad(ev, "another document type, entity or window", rd.copy(entityId = "sc:r:v1:other"))
    bad(ev, "another document type, entity or window", rd.copy(window = Some(w1950)))
    bad(ev, "another evidence ID", rd.copy(evidenceId = Some("ev:x:0123456789abcdef")))
  }

  test("a rendering that is not self-consistent is refused, never repaired") {
    val rd = RenderFx.stub(maxChunk = 3).renderSummary(ev).toOption.get
    assert(rd.chunks.size > 1)
    def bad(r: RenderedDocument, part: String) =
      assert(SemanticDocument.fromEvidence(ev, r).left.exists(_.contains(part)), part)
    bad(rd.copy(textHash = "sha256:00"), "textHash")
    bad(rd.copy(chunks = Vector.empty), "no chunks")
    bad(rd.copy(chunks = rd.chunks.reverse), "ordinals")
    bad(rd.copy(chunks = rd.chunks.updated(0, rd.chunks(0).copy(text = "zzz"))), "span")
    bad(
      rd.copy(chunks = rd.chunks.updated(0, rd.chunks(0).copy(textHash = "sha256:00"))),
      "chunk textHash"
    )
    bad(
      rd.copy(chunks = rd.chunks.updated(0, rd.chunks(0).copy(chunkId = "c:zz"))),
      "malformed chunk ID"
    )
    bad(
      rd.copy(chunks = rd.chunks.map(_.copy(chunkId = rd.chunks.head.chunkId))),
      "duplicate chunk IDs"
    )
    bad(rd.copy(template = ""), "template")
    // a one-chunk document keeps the core chunk ID formula
    val one = RenderFx.stub(maxChunk = 1000).renderSummary(ev).toOption.get
    bad(
      one.copy(chunks = Vector(one.chunks.head.copy(chunkId = Ids.chunk("x", mv, "y", 0, "z")))),
      "core Ids.chunk"
    )
  }

  test("documents round-trip through uPickle and through strict JSON lines") {
    five.foreach { d =>
      assertEquals(upickle.default.read[SemanticDocument](upickle.default.write(d)), d)
      assertEquals(JsonLines.decode[SemanticDocument](JsonLines.encode(d)), Right(d))
    }
    val extra = ujson.read(JsonLines.encode(five.head)); extra("surprise") = 1
    assert(JsonLines.decode[SemanticDocument](extra.render()).isLeft)
    val pa = PaperAbstract("dblp:x", Hashing.tagged("t"), "t", ev.provenance)
    assertEquals(JsonLines.decode[PaperAbstract](JsonLines.encode(pa)), Right(pa))
  }

  test("an abstract and its rendering become documents with no window; bad input is refused") {
    val text = "An abstract about distributed graph processing"
    val pa = PaperAbstract("dblp:conf/x/P", Hashing.tagged(text), text, ev.provenance)
    val rd = RenderFx.stub().renderAbstract(pa).toOption.get
    val d = SemanticDocument.fromAbstract(pa, rd).toOption.get.head
    assertEquals((d.docType, d.window, d.entityId), ("paper-abstract", None, "dblp:conf/x/P"))
    assertEquals(d.text, rd.text) // the rendered text, not the raw abstract
    assertNotEquals(d.text, text)
    assertEquals(d.chunkId, rd.chunks.head.chunkId)
    assert(SemanticDocument.fromAbstract(pa.copy(textHash = "sha256:00"), rd).isLeft)
    assert(
      SemanticDocument
        .fromAbstract(pa.copy(text = "", textHash = Hashing.tagged("")), rd)
        .isLeft
    )
    assert(SemanticDocument.fromAbstract(pa.copy(workId = "dblp:other"), rd).isLeft)
  }

  test("metadata fields per document type: filterable fields and references, nothing else") {
    def md(d: SemanticDocument) =
      VectorMetadata.of(d, "model-1", "s3://sc-rag/semantic-context/x.json")
    val Vector(profile, community, abstr, path, topic) = five.map(md)
    val common = Set(
      "documentType",
      "entityId",
      "source",
      "runId",
      "snapshot",
      "embeddingModel",
      "textUri",
      "textSha256",
      "sourceUri",
      "evidenceId",
      "chunkOrdinal",
      "chunkCount"
    )
    assertEquals(
      profile.keySet,
      common ++ Set("window", "windowStartYear", "windowEndYear", "researcherIds")
    )
    assertEquals(
      community.keySet,
      common ++ Set("window", "windowStartYear", "windowEndYear", "communityId")
    )
    assertEquals(abstr.keySet, common ++ Set("workId")) // no window: abstracts are not windowed
    assertEquals(
      path.keySet,
      common ++ Set("window", "windowStartYear", "windowEndYear", "pathId", "researcherIds")
    )
    assertEquals(
      topic.keySet,
      common ++ Set("window", "windowStartYear", "windowEndYear", "topicId")
    )
    assertEquals(profile("windowStartYear"), MetaValue.Num(2010L))
    assertEquals(profile("window"), MetaValue.Str("2010-2014"))
    assertEquals(profile("source"), MetaValue.Str("SCHOLARCHAIN"))
    assertEquals(path("researcherIds"), MetaValue.Strs(Vector(r1, r2)))
    // optional export fields flow through when present (institution is not in the Phase 06 export)
    val withInst = md(
      five.head
        .copy(institutionId = Some("ror:abc"), topicId = Some("t1"), communityId = Some("cm-9"))
    )
    assertEquals(
      (withInst("institutionId"), withInst("topicId"), withInst("communityId")),
      (MetaValue.Str("ror:abc"), MetaValue.Str("t1"), MetaValue.Str("cm-9"))
    )
    // every non-filterable key the loader writes is declared on the index
    val declared = VectorIndexes.NonFilterable.toSet
    assert(declared.subsetOf(common))
    assert(declared.size <= VectorLimits.MaxNonFilterableKeys)
  }

  test("no long text in vector metadata: a 10 KB text leaves no fragment, key or oversized value") {
    val longText = (1 to 400).map(i => s"sentence $i about collaboration and graphs.").mkString(" ")
    assert(longText.length > 10000)
    val d = profile(7, longText)
    val m = VectorMetadata.of(d, "model-1", "s3://sc-rag/semantic-context/x.json")
    assertEquals(VectorMetadata.check(m, longText), Right(()))
    val strings = m.values.flatMap {
      case MetaValue.Str(s) => Vector(s); case MetaValue.Strs(xs) => xs; case _ => Vector.empty
    }
    assert(strings.forall(s => !longText.contains(s) || s.length < 40), "a value repeats the text")
    assert(ujson.Obj.from(m.map((k, v) => k -> v.toJson)).render().length < 1000)
    // the policy refuses what would smuggle text in
    val tooLong = m.updated("note", MetaValue.Str("n" * 600))
    assert(VectorMetadata.check(tooLong, longText).left.exists(_.contains("long text")))
    assert(
      VectorMetadata
        .check(m.updated("text", MetaValue.Str("x")), longText)
        .left
        .exists(_.contains("text-like"))
    )
    assert(VectorMetadata.check(m.updated("summary", MetaValue.Str("x")), longText).isLeft)
    val span = longText.take(100)
    assert(
      VectorMetadata
        .check(m.updated("note", MetaValue.Str(span)), longText)
        .left
        .exists(_.contains("repeats"))
    )
    assert(
      VectorMetadata
        .check(
          m.updated("researcherIds", MetaValue.Strs((1 to 17).map(_.toString).toVector)),
          longText
        )
        .isLeft
    )
  }

class LoadManifestSuite extends munit.FunSuite:
  private val vectorsJson =
    s"""{"runId":"$run","snapshot":"2026-11-22","target":"s3vectors","vectorBucketConfigKey":"vectors.vectorBucket","dimension":256,
       |"source":{"table":"semantic-summaries","format":"parquet","recordSchema":"evidence-v1","rows":316,"files":8},
       |"keyField":"chunkId","textField":"text",
       |"indexes":[{"docType":"collaboration-community","indexConfigKey":"vectors.indexes.communities","index":"collaboration-community-v1","rows":58},
       |{"docType":"collaboration-path","indexConfigKey":"vectors.indexes.paths","index":"collaboration-path-v1","rows":8},
       |{"docType":"researcher-profile","indexConfigKey":"vectors.indexes.researchers","index":"researcher-profile-v1","rows":162},
       |{"docType":"topic-institution-summary","indexConfigKey":"vectors.indexes.topicInstitutions","index":"topic-institution-summary-v1","rows":88}]}""".stripMargin

  test("the s3vectors manifest parses and agrees with the configured indexes and dimension") {
    val m = LoadManifests.vectors(vectorsJson).toOption.get
    assertEquals((m.sourceRows, m.dimension, m.indexes.size), (316L, 256, 4))
    assertEquals(m.problems(cfg.vectors), Vector.empty[String])
    assertEquals(m.problems(cfg.vectors.copy(dimension = 128)).size, 1)
    val renamed = cfg.vectors.copy(indexes = cfg.vectors.indexes.updated("paths", "other-index-v9"))
    assert(m.problems(renamed).exists(_.contains("collaboration-path-v1")))
    assert(
      m.copy(sourceRows = 300).problems(cfg.vectors).exists(_.contains("differ from source rows"))
    )
  }

  test("the dynamodb manifest parses; wrong target, bad run ID and garbage are refused") {
    val json =
      s"""{"runId":"$run","snapshot":"2026-11-22","target":"dynamodb","tables":[{"tableConfigKey":"dynamo.researcherMetricTable",
         |"source":{"table":"researcher-metrics","recordSchema":"researcher-metrics-v1","rows":162},"partitionKey":"researcherId","sortKey":"windowLabel"}]}""".stripMargin
    assertEquals(
      LoadManifests.dynamo(json).toOption.get.tables,
      Vector(
        DynamoManifestTable(
          "dynamo.researcherMetricTable",
          "researcher-metrics",
          "researcher-metrics-v1",
          162L
        )
      )
    )
    assert(LoadManifests.dynamo(json.replace("dynamodb\"", "s3\"")).isLeft)
    assert(LoadManifests.vectors(json).isLeft)
    assert(LoadManifests.dynamo(json.replace(run, "run-bad")).isLeft)
    assert(LoadManifests.vectors("{").isLeft)
    assert(LoadManifests.vectors("""{"target":"s3vectors"}""").isLeft)
  }

class RowSourceSuite extends munit.FunSuite:
  test("jsonLines decodes strictly, reports a malformed line and skips blanks; mapE chains") {
    val good = JsonLines.encode(Fixtures.metrics("sc:r:v1:0123456789abcdef0123", 3))
    val src =
      RowSource.jsonLines[ResearcherMetrics](Iterator(good, "", "{oops", good), Validate.metrics)
    val rows = src.rows.toVector
    assertEquals(rows.size, 3)
    assert(rows(0).isRight && rows(1).isLeft && rows(2).isRight)
    val bad = Fixtures.metrics("sc:r:v1:0123456789abcdef0123", 0) // no publications
    val v =
      RowSource.jsonLines[ResearcherMetrics](Iterator(JsonLines.encode(bad)), Validate.metrics)
    assert(v.rows.next().left.exists(_.contains("no publications")))
    assertEquals(
      src.mapE(m => Either.cond(m.publicationCount > 5, m, "small")).rows.toVector.count(_.isLeft),
      3
    )
  }

  test(
    "jsonLinesDir reads .jsonl and .jsonl.gz in path order and ignores Parquet and other files"
  ) {
    val dir = java.nio.file.Files.createTempDirectory("rowsource")
    try
      val a = JsonLines.encode(Fixtures.metrics("sc:r:v1:0123456789abcdef0123", 1))
      val b = JsonLines.encode(Fixtures.metrics("sc:r:v1:0123456789abcdef0124", 2))
      java.nio.file.Files.writeString(dir.resolve("part-0.jsonl"), a + "\n")
      val gz = new java.util.zip.GZIPOutputStream(
        java.nio.file.Files.newOutputStream(dir.resolve("part-1.jsonl.gz"))
      )
      gz.write((b + "\n").getBytes("UTF-8")); gz.close()
      java.nio.file.Files.write(dir.resolve("part-2.parquet"), Array[Byte](80, 65, 82, 49))
      java.nio.file.Files.writeString(dir.resolve("_SUCCESS"), "")
      val rows = RowSource.jsonLinesDir[ResearcherMetrics](dir, Validate.metrics).rows.toVector
      assertEquals(rows.map(_.toOption.get.publicationCount), Vector(1L, 2L))
      val absent = RowSource.jsonLinesDir[ResearcherMetrics](dir.resolve("absent")).rows.toVector
      assertEquals(absent.size, 1) // a missing directory is one visible error row, never zero rows
      assert(absent.head.left.exists(_.contains("is missing")), absent.toString)
    finally
      java.nio.file.Files.list(dir).forEach(p => java.nio.file.Files.delete(p))
      java.nio.file.Files.delete(dir)
  }
