package scholarchain.services.worker

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.typelevel.ci.CIString
import scholarchain.core.*
import scholarchain.services.common.*
import scholarchain.storage.*
import upickle.default.write

object WFx:
  val env: Map[String, String] = Map(
    "RAW_BUCKET" -> "sc-raw",
    "CURATED_BUCKET" -> "sc-curated",
    "GRAPH_BUCKET" -> "sc-graph",
    "RAG_BUCKET" -> "sc-rag",
    "DDB_RUN_LEDGER" -> "t-ledger",
    "DDB_ENTITY_LINK_CACHE" -> "t-links",
    "DDB_QUERY_SESSION" -> "t-sessions",
    "DDB_EVIDENCE_BUNDLE" -> "t-bundles",
    "DDB_ARTIFACT_REGISTRY" -> "t-artifacts",
    "DDB_RESEARCHER_METRIC" -> "t-metrics"
  )
  val cfg: ScholarChainConfig = ConfigLoader.load(env).toOption.get
  val dim = 16
  val mv = "metrics-v1"
  val vcfg: VectorConfig = cfg.vectors.copy(vectorBucket = "sc-vectors", dimension = dim)
  val run: String = Ids.run("""{"fixture":"worker"}""")
  val run2: String = Ids.run("""{"fixture":"worker-2"}""")
  val w = TimeWindow(2010, 2014)

  def doc(n: Int, runId: String = run, text: String = ""): SemanticDocument =
    val entity = s"sc:r:v1:${"%020x".format(n)}"
    val t = if text.isEmpty then s"Researcher profile number $n works on graph topics and databases"
    else text
    SemanticDocument(
      SemanticDocument.Version,
      SemanticDocument.ResearcherProfile,
      entity,
      Ids.chunk(entity, mv, SemanticDocument.ResearcherProfile, 0, t),
      t,
      Hashing.tagged(t),
      Some(w),
      Vector(entity),
      None,
      None,
      None,
      vcfg.indexes.get("researchers"),
      SourceType.SCHOLARCHAIN,
      s"spark:$entity",
      "2026-11-22",
      runId,
      Some(Ids.evidence(EvidenceType.SEMANTIC_PROFILE, entity)),
      "s3://sc-graph/spark/semantic-summaries"
    )
  def msg(d: SemanticDocument): String = write(EmbedMessage(d.runId, d))

  /** Delegates to the in-memory store and can be told to throw on put. */
  final class FlakyStore(inner: InMemoryVectorStore) extends VectorStore:
    var explode = false
    def createBucket() = inner.createBucket()
    def createIndex(spec: IndexSpec) = inner.createIndex(spec)
    def put(index: String, vectors: Vector[VectorRecord]) =
      if explode then throw new IllegalStateException("store exploded")
      else inner.put(index, vectors)
    def get(index: String, keys: Vector[String]) = inner.get(index, keys)
    def query(index: String, vector: Vector[Float], topK: Int, filter: Vector[FilterTerm]) =
      inner.query(index, vector, topK, filter)

  final class Rig(batch: Int = 50, maxBytes: Int = 256 * 1024):
    val queue = new InMemoryWorkQueue[String](1000)
    val consumer = new WorkQueueConsumer(queue)
    val kv = new InMemoryKeyValueStore
    val fake = new InMemoryVectorStore
    val store = new FlakyStore(fake)
    val tables = DynamoTables(kv, cfg.dynamo).toOption.get
    val writer = new ArtifactWriter(new InMemoryObjectStore, new S3Layout(cfg.storage))
    fake.createBucket()
    SemanticDocument.IndexKeys.values.foreach(k =>
      fake.createIndex(VectorIndexes.spec(vcfg.indexes(k), dim))
    )
    val loader = VectorLoader(
      store,
      new HashingEmbedder(dim),
      writer,
      tables.runLedger,
      vcfg,
      VectorLoaderSettings(chunkSourceVersion = Some(mv), retry = RetryPolicy(3, 1L, _ => ()))
    ).toOption.get
    val sink = new CollectingMetrics
    val worker = new EmbeddingWorker(consumer, loader, sink, batch, maxBytes)
    def offer(bodies: String*): Unit = bodies.foreach(b => assert(queue.offer(b)))
    def drain: DrainResult = worker.drainOnce.unsafeRunSync()
    def stored(d: SemanticDocument): Boolean =
      fake.get(vcfg.indexes("researchers"), Vector(d.chunkId)).toOption.exists(_.nonEmpty)

import WFx.*

class EmbeddingWorkerSuite extends munit.FunSuite:
  // Hang guard only: the first test pays one-time JVM setup (RSA keys, IO runtime, class
  // loading), over 30s in a loaded `sbt check`. Product timeouts are unchanged.
  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")
  test("valid messages are embedded and acknowledged") {
    val rig = new Rig
    val ds = (1 to 3).map(doc(_))
    rig.offer(ds.map(msg)*)
    val r = rig.drain
    assertEquals(
      (r.received, r.acknowledged, r.malformed, r.rejected, r.failedBatches, r.written),
      (3, 3, 0, 0, 0, 3L)
    )
    assert(ds.forall(rig.stored))
    assertEquals(rig.consumer.deadLetters, Vector.empty)
  }
  test("a redelivered message is idempotent: nothing is rewritten and it is acknowledged again") {
    val rig = new Rig
    val d = doc(1)
    rig.offer(msg(d))
    assertEquals(rig.drain.written, 1L)
    rig.offer(msg(d))
    val r = rig.drain
    assertEquals((r.acknowledged, r.written, r.unchanged), (1, 0L, 1L))
  }
  test("a second drain of new documents of the same run loads them too") {
    val rig = new Rig
    rig.offer(msg(doc(1)))
    assertEquals(rig.drain.acknowledged, 1)
    rig.offer(msg(doc(2)))
    val r = rig.drain
    assertEquals((r.acknowledged, r.failedBatches, r.written), (1, 0, 1L))
    assert(rig.stored(doc(2)))
  }
  test("a malformed message is left for the dead-letter queue and does not block the good ones") {
    val rig = new Rig
    rig.offer("{not json", """{"runId":"r"}""", msg(doc(1)), "x" * 10)
    val r = rig.drain
    assertEquals((r.received, r.acknowledged, r.malformed), (4, 1, 3))
    assertEquals(
      rig.consumer.deadLetters.map(_.body),
      Vector("{not json", """{"runId":"r"}""", "x" * 10)
    )
    assert(rig.stored(doc(1)))
  }
  test("an oversized message is malformed") {
    val rig = new Rig(maxBytes = 100)
    rig.offer(msg(doc(1)))
    val r = rig.drain
    assertEquals((r.malformed, r.acknowledged), (1, 0))
    assertEquals(rig.consumer.deadLetters.size, 1)
  }
  test("a document the loader rejects stays unacknowledged; the rest of the batch loads") {
    val rig = new Rig
    val bad = doc(1).copy(textHash = Hashing.tagged("other text"))
    rig.offer(msg(bad), msg(doc(2)))
    val r = rig.drain
    assertEquals((r.acknowledged, r.rejected, r.written), (1, 1, 1L))
    assertEquals(rig.consumer.deadLetters.size, 1)
    assert(!rig.stored(bad) && rig.stored(doc(2)))
  }
  test("a store failure leaves the whole batch unacknowledged for redelivery") {
    val rig = new Rig
    rig.store.explode = true
    rig.offer(msg(doc(1)), msg(doc(2)))
    val r = rig.drain
    assertEquals((r.acknowledged, r.failedBatches), (0, 1))
    assertEquals(rig.consumer.deadLetters.size, 2)
  }
  test("an empty queue is a no-op and every drain emits one EMF line without document text") {
    val rig = new Rig
    assertEquals(rig.drain, DrainResult(0, 0, 0, 0, 0, 0L, 0L))
    rig.offer(msg(doc(1)))
    rig.drain
    assertEquals(rig.sink.all.size, 2)
    val line = ujson.read(rig.sink.all.last)
    assertEquals(line("Service").str, "embedding-worker")
    assertEquals(line("Received").num, 1.0)
    assert(!rig.sink.all.exists(_.contains("graph topics")))
  }
  test("the batch size bounds one drain") {
    val rig = new Rig(batch = 2)
    rig.offer((1 to 5).map(i => msg(doc(i)))*)
    assertEquals(rig.drain.received, 2)
    assertEquals(rig.queue.size, 3)
  }

class WorkerHealthSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")
  private def send(routes: HttpRoutes[IO], path: String) =
    routes.orNotFound
      .run(Request[IO](Method.GET, Uri.unsafeFromString(path)))
      .flatMap(r => r.bodyText.compile.string.map(r -> _))
      .unsafeRunSync()

  test("health is up, readiness follows its checks, no other route exists") {
    var up = true
    val kit = new ServiceKit(
      "embedding-worker",
      new CollectingMetrics,
      checks = List(ReadinessCheck("vectors", IO(up)))
    )
    val routes = WorkerApp.routes(kit)
    assertEquals(send(routes, "/healthz")._1.status, Status.Ok)
    assertEquals(send(routes, "/readyz")._1.status, Status.Ok)
    up = false
    val (r, b) = send(routes, "/readyz")
    assertEquals(r.status, Status.ServiceUnavailable)
    assertEquals(ujson.read(b)("error")("details")("vectors").str, "failing")
    assertEquals(send(routes, "/healthz")._1.status, Status.Ok)
    val (n, nb) = send(routes, "/v1/anything")
    assertEquals((n.status, ujson.read(nb)("error")("code").str), (Status.NotFound, "not_found"))
    assert(n.headers.get(CIString("X-Request-Id")).isDefined)
  }
