package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.{AwsCredentials, AwsHttp, StaticCredentials}
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.{Clock, Duration, Instant, ZoneId, ZoneOffset}
import scala.collection.mutable
import scala.collection.concurrent.TrieMap
import upickle.default.{ReadWriter, read, write}

object Fixtures:
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
  val layout = new S3Layout(cfg.storage)
  val runId: String = Ids.run("""{"fixture":"storage"}""")
  val runId2: String = Ids.run("""{"fixture":"storage-2"}""")
  val researcher = "sc:r:v1:0123456789abcdef0123"
  val prov: Provenance =
    Provenance(SourceType.SCHOLARCHAIN, "x", "2026-11-01", runId, SchemaVersions.Metrics, None)
  val source: SourceRef = SourceRef(SourceType.SCHOLARCHAIN, "dblp:w1", "2026-11-01")

  def metrics(id: String, publications: Long): ResearcherMetrics =
    ResearcherMetrics(
      SchemaVersions.Metrics,
      id,
      publications,
      3,
      5,
      1.5,
      2,
      Vector("dblp:w1"),
      3.0,
      "cc-1",
      10,
      Some("cm-1"),
      Some(0.25),
      None,
      None,
      None,
      Some(2),
      TimeWindow(2015, 2026),
      "metrics-v1",
      prov
    )

  def item(text: Option[String]): EvidenceItem =
    EvidenceItem(
      SchemaVersions.Evidence,
      "ev:edge:0123456789abcdef",
      EvidenceType.COAUTHOR_EDGE,
      None,
      Vector(researcher),
      "s3://sc-graph/x",
      None,
      None,
      1.0,
      Map("k" -> "v"),
      text,
      text.map(Hashing.tagged),
      prov
    )

  def bundle(id: String, text: Option[String] = None): EvidenceBundle =
    EvidenceBundle(
      SchemaVersions.Bundle,
      id,
      "q-1",
      Vector(item(text)),
      Vector.empty,
      Vector.empty,
      runId,
      "metrics-v1",
      prov
    )

  val t0: Instant = Instant.parse("2026-11-23T12:00:00Z")

  final class TestClock(var now: Instant) extends Clock:
    def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(z: ZoneId): Clock = this
    def instant(): Instant = now

  def http(maxAttempts: Int = 1): AwsHttp =
    new AwsHttp(
      "us-east-2",
      new StaticCredentials(AwsCredentials("AKIDEXAMPLE", "example-only", None)),
      maxAttempts = maxAttempts,
      sleeper = _ => ()
    )

  final case class Seen(method: String, uri: String, headers: Map[String, String], body: String)

  /** Local stub of an AWS endpoint: `handler` returns (status, body, extra response headers). */
  def withStub[A](handler: Seen => (Int, String, Map[String, String]))(
      f: (String, mutable.Buffer[Seen]) => A
  ): A =
    val seen = mutable.Buffer.empty[Seen]
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      ex =>
        import scala.jdk.CollectionConverters.*
        val hs = ex.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.get(0)).toMap
        val s = Seen(
          ex.getRequestMethod,
          ex.getRequestURI.toString,
          hs,
          new String(ex.getRequestBody.readAllBytes(), "UTF-8")
        )
        seen.synchronized(seen += s)
        val (status, body, extra) = handler(s)
        extra.foreach((k, v) => ex.getResponseHeaders.add(k, v))
        val b = body.getBytes("UTF-8")
        ex.sendResponseHeaders(status, if b.isEmpty then -1 else b.length.toLong)
        if b.nonEmpty then ex.getResponseBody.write(b)
        ex.close()
    )
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}", seen)
    finally server.stop(0)

import Fixtures.*

class S3LayoutSuite extends munit.FunSuite:
  test("zones map to configured buckets and the documented key layout") {
    assertEquals(
      layout.runKey(Zone.Raw, "dblp", runId, "part-0.jsonl"),
      Right(s"s3://sc-raw/raw/dblp/run_id=$runId/part-0.jsonl")
    )
    assertEquals(
      layout.runKey(Zone.Curated, "works", runId, "a/b.json").map(_.take(30)),
      Right(s"s3://sc-curated/curated/works/".take(30))
    )
    assertEquals(
      layout.runKey(Zone.Graph, "nodes", runId, "n.csv"),
      Right(s"s3://sc-graph/graph/nodes/run_id=$runId/n.csv")
    )
    assertEquals(
      layout.runKey(Zone.SemanticContext, "profiles", runId, "r.txt"),
      Right(s"s3://sc-rag/semantic-context/profiles/run_id=$runId/r.txt")
    )
    assertEquals(
      layout.runKey(Zone.Reports, "eval", runId, "r.md"),
      Right(s"s3://sc-curated/reports/eval/run_id=$runId/r.md")
    )
    assertEquals(
      layout.runKey(Zone.Logs, "emr", runId, "l.txt"),
      Right(s"s3://sc-curated/logs/emr/run_id=$runId/l.txt")
    )
    assertEquals(
      layout.manifestUri(Zone.Graph, "nodes", runId),
      Right(s"s3://sc-graph/graph/nodes/run_id=$runId/_manifest.json")
    )
    assertEquals(
      Zone.values.map(_.prefix).toVector.sorted,
      Vector("curated", "graph", "logs", "raw", "reports", "semantic-context")
    )
  }

  test("content-addressed keys depend only on the bytes") {
    val h = Hashing.sha256Hex("text")
    assertEquals(
      layout.contentKey(Zone.SemanticContext, "docs", h, "d.txt"),
      Right(s"s3://sc-rag/semantic-context/docs/sha256=$h/d.txt")
    )
    assert(layout.contentKey(Zone.SemanticContext, "docs", "abc", "d.txt").isLeft)
  }

  test("a bad run ID, a traversal segment or a missing bucket is refused") {
    assert(layout.runKey(Zone.Raw, "dblp", "run-1", "x").isLeft)
    assert(layout.runKey(Zone.Raw, "dblp", runId, "../x").isLeft)
    assert(layout.runKey(Zone.Raw, "d b", runId, "x").isLeft)
    assert(layout.runKey(Zone.Raw, "dblp", runId, "a//b").isLeft)
    val empty = new S3Layout(cfg.storage.copy(ragBucket = " "))
    assert(empty.runKey(Zone.SemanticContext, "p", runId, "x").isLeft)
  }

class SerializationSuite extends munit.FunSuite:
  private def roundTrip[A: ReadWriter](a: A): Unit =
    assertEquals(read[A](write(a)), a)

  test("storage records survive a JSON round trip, with and without optional fields") {
    val obj = StoredObject("s3://b/k", Hashing.sha256Hex("x"), 1L, "text/plain", Vector(source))
    roundTrip(obj)
    roundTrip(
      StorageManifest(StorageManifest.Version, runId, "load", "graph", "nodes", None, Vector(obj))
    )
    roundTrip(
      StorageManifest(
        StorageManifest.Version,
        runId,
        "load",
        "graph",
        "nodes",
        Some("p-1"),
        Vector.empty
      )
    )
    roundTrip(
      LedgerEntry(runId, "load", LedgerStatus.Failed, 2, "l-1", Some("u"), None, Some("e"), "t")
    )
    roundTrip(LedgerEntry(runId, "load", LedgerStatus.Started, 1, "l-1", None, None, None, "t"))
    roundTrip(FailureRecord(runId, "load", "k", "bad", 3, "t"))
    roundTrip(
      EntityLink("orcid:0000-0002-1825-0097", "er-v1", researcher, "md:accept:" + "0" * 20, 0.97)
    )
    roundTrip(SessionTurn("s1", 0, "q1", "who?", Some("b1"), "answered"))
    roundTrip(SessionTurn("s1", 1, "q2", "who?", None, "abstained"))
    roundTrip(ArtifactRecord("graph", runId, "u", "h", 4L, "v1", Some("m"), Vector(source)))
    roundTrip(CurrentArtifact("graph", runId, "u", "h"))
  }

  test("a malformed or wrong-version manifest is refused, not thrown") {
    assert(StorageManifest.parse("not json").isLeft)
    assert(StorageManifest.parse("""{"schemaVersion":"storage-manifest-v1"}""").isLeft)
    val m = StorageManifest("storage-manifest-v9", runId, "s", "raw", "d", None, Vector.empty)
    assert(StorageManifest.parse(write(m)).left.exists(_.contains("unsupported")))
    val bad = m.copy(schemaVersion = StorageManifest.Version, runId = "run-x")
    assert(StorageManifest.parse(write(bad)).isLeft)
  }

class ArtifactWriterSuite extends munit.FunSuite:
  private def writer(store: ObjectStore = new InMemoryObjectStore) =
    store -> new ArtifactWriter(store, layout)

  test("a replayed write is unchanged; different bytes under the same key are a conflict") {
    val (store, w) = writer()
    val bytes = "line1\nline2\n".getBytes("UTF-8")
    val first =
      w.write(Zone.Curated, "works", runId, "p.jsonl", bytes, "text/plain", Vector(source))
    val obj = first.toOption.get._1
    assertEquals(first.toOption.get._2, WriteOutcome.Created)
    assertEquals(obj.sha256, Hashing.sha256Hex(bytes))
    assertEquals(obj.bytes, bytes.length.toLong)
    assertEquals(obj.sources, Vector(source))
    assertEquals(
      w.write(Zone.Curated, "works", runId, "p.jsonl", bytes, "text/plain").toOption.get._2,
      WriteOutcome.Unchanged
    )
    val other =
      w.write(Zone.Curated, "works", runId, "p.jsonl", "changed".getBytes("UTF-8"), "text/plain")
    other match
      case Left(ArtifactError.Conflict(uri, existing, nw)) =>
        assertEquals(uri, obj.uri)
        assertEquals(existing, obj.sha256)
        assertNotEquals(nw, existing)
      case x => fail(s"expected a conflict, got $x")
    assertEquals(store.getText(obj.uri), Right("line1\nline2\n")) // the original is untouched
  }

  test("a manifest is written once, replays unchanged, reads back and verifies content hashes") {
    val (store, w) = writer()
    val o = w
      .write(Zone.Graph, "nodes", runId, "n.csv", "a,b".getBytes("UTF-8"), "text/csv")
      .toOption
      .get
      ._1
    val m = StorageManifest(
      StorageManifest.Version,
      runId,
      "load",
      "graph",
      "nodes",
      Some("spark-v2"),
      Vector(o)
    )
    assertEquals(w.writeManifest(m).toOption.get._2, WriteOutcome.Created)
    assertEquals(w.writeManifest(m).toOption.get._2, WriteOutcome.Unchanged)
    assertEquals(w.readManifest(Zone.Graph, "nodes", runId), Right(m))
    assertEquals(w.verify(m), Vector.empty)
    val tampered = m.copy(objects = Vector(o.copy(sha256 = Hashing.sha256Hex("other"))))
    assertEquals(w.verify(tampered), Vector(o.uri))
    assertEquals(
      w.verify(m.copy(objects = Vector(o.copy(uri = "s3://sc-graph/missing")))),
      Vector("s3://sc-graph/missing")
    )
    // the same run with different objects is refused, never merged
    val changed = m.copy(stage = "load", objects = Vector.empty)
    assert(w.writeManifest(changed).left.exists(_.isInstanceOf[ArtifactError.Conflict]))
    assert(w.readManifest(Zone.Graph, "nodes", runId2).isLeft)
  }

  test("a manifest stored under another run, or garbage, is refused on read") {
    val (store, w) = writer()
    val uri = layout.manifestUri(Zone.Raw, "d", runId).toOption.get
    store.putIfAbsent(uri, "garbage".getBytes("UTF-8"), "application/json")
    assert(w.readManifest(Zone.Raw, "d", runId).isLeft)
    val other =
      StorageManifest(StorageManifest.Version, runId2, "s", "raw", "d", None, Vector.empty)
    val uri2 = layout.manifestUri(Zone.Raw, "d", runId2).toOption.get
    store.putIfAbsent(uri2, write(other).getBytes("UTF-8"), "application/json")
    assertEquals(w.readManifest(Zone.Raw, "d", runId2), Right(other))
  }

  test("the fake object store lists a prefix in key order and refuses non-s3 URIs") {
    val s = new InMemoryObjectStore
    s.putIfAbsent("s3://bkt1/p/b", Array[Byte](1), "x")
    s.putIfAbsent("s3://bkt1/p/a", Array[Byte](1), "x")
    s.putIfAbsent("s3://bkt1/pp/c", Array[Byte](1), "x")
    assertEquals(s.list("s3://bkt1/p"), Right(Vector("s3://bkt1/p/a", "s3://bkt1/p/b")))
    assert(s.putIfAbsent("http://x/y", Array[Byte](), "x").isLeft)
  }

class KeyValueFakeSuite extends munit.FunSuite:
  test("putIfAbsent refuses a duplicate key and keeps the first item") {
    val kv = new InMemoryKeyValueStore
    assertEquals(kv.putIfAbsent("t", KvItem("a", "b", "1")), Right(()))
    assertEquals(
      kv.putIfAbsent("t", KvItem("a", "b", "2")),
      Left(KvError.ConditionFailed("a", "b"))
    )
    assertEquals(kv.get("t", "a", "b").toOption.flatten.map(_.payload), Some("1"))
    assertEquals(kv.putIfAbsent("other", KvItem("a", "b", "3")), Right(())) // tables are separate
  }

  test("replaceIfVersion needs the stored version; stale, missing and mis-numbered writes fail") {
    val kv = new InMemoryKeyValueStore
    assertEquals(
      kv.replaceIfVersion("t", KvItem("a", "b", "x", 2), 1),
      Left(KvError.ConditionFailed("a", "b"))
    )
    kv.putIfAbsent("t", KvItem("a", "b", "v1"))
    assertEquals(kv.replaceIfVersion("t", KvItem("a", "b", "v2", 2), 1), Right(()))
    assertEquals(
      kv.replaceIfVersion("t", KvItem("a", "b", "v3", 2), 1),
      Left(KvError.ConditionFailed("a", "b"))
    )
    assert(
      kv.replaceIfVersion("t", KvItem("a", "b", "v9", 5), 2)
        .left
        .exists(_.isInstanceOf[KvError.Failure])
    )
    assertEquals(kv.get("t", "a", "b").toOption.flatten, Some(KvItem("a", "b", "v2", 2)))
  }

  test("key and item size limits are enforced like DynamoDB") {
    val kv = new InMemoryKeyValueStore
    assert(kv.putIfAbsent("t", KvItem("", "b", "x")).isLeft)
    assert(kv.putIfAbsent("t", KvItem("a", "s" * 1025, "x")).isLeft)
    assert(kv.putIfAbsent("t", KvItem("p" * 2049, "b", "x")).isLeft)
    assert(kv.putIfAbsent("t", KvItem("a", "b", "x" * (400 * 1024))).isLeft)
    assert(kv.putIfAbsent("t", KvItem("a", "b", "x" * (399 * 1024))).isRight)
  }

  test("query returns one partition in sort-key order, filtered by prefix and bounded") {
    val kv = new InMemoryKeyValueStore
    Vector("b#2", "a#1", "b#1", "c#1").foreach(sk => kv.putIfAbsent("t", KvItem("p", sk, sk)))
    kv.putIfAbsent("t", KvItem("q", "b#9", "x"))
    assertEquals(kv.query("t", "p", "b#", 10).toOption.get.map(_.sk), Vector("b#1", "b#2"))
    assertEquals(kv.query("t", "p", "", 2).toOption.get.map(_.sk), Vector("a#1", "b#1"))
    assert(kv.query("t", "p", "", 0).isLeft)
  }

class TablesSuite extends munit.FunSuite:
  private def tables(
      clock: Clock = Clock.fixed(t0, ZoneOffset.UTC),
      kv: InMemoryKeyValueStore = new InMemoryKeyValueStore
  ) =
    kv -> DynamoTables(kv, cfg.dynamo, clock).toOption.get

  test("table names come from configuration; blank or duplicate names are refused") {
    val kv = new InMemoryKeyValueStore
    assert(
      DynamoTables(kv, ConfigLoader.load(Map.empty).toOption.get.dynamo).isLeft
    ) // local default: blank
    assert(DynamoTables(kv, cfg.dynamo.copy(runLedgerTable = cfg.dynamo.querySessionTable)).isLeft)
    assert(DynamoTables(kv, cfg.dynamo).isRight)
  }

  test("RunLedger: start is idempotent, a different logic version conflicts") {
    val (_, t) = tables()
    val (e, st) = t.runLedger.start(runId, "load", "load-v1").toOption.get
    assertEquals(
      (st, e.version, e.value.status, e.value.attempt),
      (Stored.Created, 1L, LedgerStatus.Started, 1)
    )
    val (e2, st2) = t.runLedger.start(runId, "load", "load-v1").toOption.get
    assertEquals((st2, e2.version), (Stored.Unchanged, 1L))
    assertEquals(
      t.runLedger.start(runId, "load", "load-v2"),
      Left(KvError.ConditionFailed(s"run#$runId", "stage#load"))
    )
    assert(t.runLedger.start("run-1", "load", "load-v1").isLeft)
    assert(t.runLedger.start(runId, "a#b", "load-v1").isLeft)
  }

  test(
    "RunLedger: transitions are version-guarded; a stale writer loses; illegal moves are refused"
  ) {
    val (_, t) = tables()
    val started = t.runLedger.start(runId, "embed", "e-1").toOption.get._1
    val failed = t.runLedger.fail(started, "boom").toOption.get
    assertEquals((failed.version, failed.value.status), (2L, LedgerStatus.Failed))
    // a second worker still holding version 1 cannot also advance the stage
    assertEquals(
      t.runLedger.succeed(started, "s3://b/k", "h"),
      Left(KvError.ConditionFailed(s"run#$runId", "stage#embed"))
    )
    val again = t.runLedger.retry(failed).toOption.get
    assertEquals(
      (again.version, again.value.status, again.value.attempt),
      (3L, LedgerStatus.Started, 2)
    )
    val done = t.runLedger.succeed(again, "s3://b/k", "h").toOption.get
    assertEquals(done.value.outputUri, Some("s3://b/k"))
    assert(
      t.runLedger.retry(done).left.exists(_.isInstanceOf[KvError.Failure])
    ) // Succeeded is terminal
    assert(t.runLedger.fail(done, "x").isLeft)
    assertEquals(t.runLedger.get(runId, "embed").toOption.flatten.map(_.value), Some(done.value))
    assertEquals(t.runLedger.get(runId, "nope"), Right(None))
    assertEquals(t.runLedger.stages(runId).toOption.get.map(_.value.stage), Vector("embed"))
  }

  test("RunLedger: failures are recorded per subject and count retries") {
    val (_, t) = tables()
    val a = t.runLedger.recordFailure(runId, "vectors", "v:1", "dimension 3 != 256").toOption.get
    assertEquals((a.version, a.value.retryCount), (1L, 1))
    val b = t.runLedger.recordFailure(runId, "vectors", "v:1", "dimension 3 != 256").toOption.get
    assertEquals((b.version, b.value.retryCount), (2L, 2))
    t.runLedger.recordFailure(runId, "vectors", "v:2", "bad")
    t.runLedger.start(runId, "vectors", "l-1")
    assertEquals(
      t.runLedger.failures(runId, "vectors").toOption.get.map(_.value.subject),
      Vector("v:1", "v:2")
    )
    assertEquals(t.runLedger.failures(runId, "other").toOption.get, Vector.empty)
  }

  test(
    "EntityLinkCache: same link is unchanged, a different link under one policy is refused, policies are separate"
  ) {
    val (_, t) = tables()
    val l =
      EntityLink("orcid:0000-0002-1825-0097", "er-v1", researcher, "md:accept:" + "1" * 20, 0.97)
    assertEquals(t.entityLinks.put(l), Right(Stored.Created))
    assertEquals(t.entityLinks.put(l), Right(Stored.Unchanged))
    val moved = l.copy(researcherId = "sc:r:v1:ffffffffffffffffffff")
    assertEquals(
      t.entityLinks.put(moved),
      Left(KvError.ConditionFailed(s"link#${l.mentionKey}", "policy#er-v1"))
    )
    assertEquals(t.entityLinks.get(l.mentionKey, "er-v1"), Right(Some(l)))
    assertEquals(t.entityLinks.put(moved.copy(policyVersion = "er-v2")), Right(Stored.Created))
    assertEquals(t.entityLinks.get(l.mentionKey, "er-v1"), Right(Some(l)))
    assertEquals(t.entityLinks.get("unknown", "er-v1"), Right(None))
  }

  test("QuerySession: turns are ordered, written once, and expire by TTL") {
    val clock = new TestClock(t0)
    val (kv, t) = tables(clock)
    val turn = SessionTurn("s1", 1, "q1", "who works with X?", Some("b1"), "answered")
    assertEquals(t.sessions.append(turn.copy(turn = 2, queryId = "q2")), Right(Stored.Created))
    assertEquals(t.sessions.append(turn), Right(Stored.Created))
    assertEquals(t.sessions.append(turn), Right(Stored.Unchanged))
    assertEquals(
      t.sessions.append(turn.copy(question = "other")),
      Left(KvError.ConditionFailed("session#s1", "turn#000001"))
    )
    assertEquals(t.sessions.turns("s1").toOption.get.map(_.turn), Vector(1, 2))
    val stored = kv.get("t-sessions", "session#s1", "turn#000001").toOption.flatten.get
    assertEquals(stored.ttl, Some(t0.getEpochSecond + 7 * 24 * 3600))
    // DynamoDB deletes lazily: the item is still in the store but the repository hides it
    clock.now = t0.plusSeconds(7 * 24 * 3600 + 1)
    assertEquals(t.sessions.turns("s1"), Right(Vector.empty))
    assertEquals(kv.size("t-sessions"), 2)
    // a replay after expiry takes over the expired item with a version-guarded replace
    assertEquals(t.sessions.append(turn), Right(Stored.Created))
    assertEquals(t.sessions.turns("s1").toOption.get.map(_.turn), Vector(1))
    assert(t.sessions.append(turn.copy(turn = -1)).isLeft)
  }

  test("EvidenceBundle: round trip, idempotent repeat, conflict, bad schema, size bound, no TTL") {
    val (kv, t) = tables()
    val b = bundle("bundle-1")
    assertEquals(t.bundles.put(b), Right(Stored.Created))
    assertEquals(t.bundles.put(b), Right(Stored.Unchanged))
    assertEquals(t.bundles.get("bundle-1"), Right(Some(b)))
    assertEquals(t.bundles.get("bundle-2"), Right(None))
    assert(
      t.bundles.put(b.copy(queryId = "q-2")).left.exists(_.isInstanceOf[KvError.ConditionFailed])
    )
    assert(t.bundles.put(b.copy(schemaVersion = "evidence-bundle-v9")).isLeft)
    assert(
      t.bundles
        .put(bundle("big", Some("x" * (450 * 1024))))
        .left
        .exists(_.isInstanceOf[KvError.Failure])
    )
    assertEquals(
      kv.get("t-bundles", "bundle#bundle-1", "schema#evidence-bundle-v1")
        .toOption
        .flatten
        .flatMap(_.ttl),
      None
    )
  }

  test("ArtifactRegistry: register once, promote with a version guard, refuse unregistered runs") {
    val (_, t) = tables()
    def rec(r: String, h: String) =
      ArtifactRecord("graph-export", r, s"s3://sc-graph/$r", h, 10L, "v1", None, Vector(source))
    assertEquals(t.artifacts.register(rec(runId, "h1")), Right(Stored.Created))
    assertEquals(t.artifacts.register(rec(runId, "h1")), Right(Stored.Unchanged))
    assert(
      t.artifacts.register(rec(runId, "h2")).left.exists(_.isInstanceOf[KvError.ConditionFailed])
    )
    assertEquals(t.artifacts.get("graph-export", runId), Right(Some(rec(runId, "h1"))))
    assertEquals(t.artifacts.current("graph-export"), Right(None))
    assert(t.artifacts.promote("graph-export", runId2).left.exists(_.isInstanceOf[KvError.Failure]))
    assertEquals(t.artifacts.promote("graph-export", runId), Right(Stored.Created))
    assertEquals(t.artifacts.promote("graph-export", runId), Right(Stored.Unchanged))
    t.artifacts.register(rec(runId2, "h3"))
    assertEquals(t.artifacts.promote("graph-export", runId2), Right(Stored.Created))
    assertEquals(t.artifacts.current("graph-export").toOption.flatten.map(_.runId), Some(runId2))
    assertEquals(
      t.artifacts.runs("graph-export").toOption.get.map(_.runId).toSet,
      Set(runId, runId2)
    )
  }

  test(
    "ResearcherMetricCache: keyed by researcher, metric version and window; changed values are refused"
  ) {
    val (_, t) = tables()
    val m = metrics(researcher, 3)
    assertEquals(t.metrics.put(m), Right(Stored.Created))
    assertEquals(t.metrics.put(m), Right(Stored.Unchanged))
    assertEquals(t.metrics.get(researcher, "metrics-v1", TimeWindow(2015, 2026)), Right(Some(m)))
    assertEquals(t.metrics.get(researcher, "metrics-v1", TimeWindow(2020, 2026)), Right(None))
    assertEquals(t.metrics.get(researcher, "metrics-v2", TimeWindow(2015, 2026)), Right(None))
    assert(
      t.metrics.put(metrics(researcher, 4)).left.exists(_.isInstanceOf[KvError.ConditionFailed])
    )
    assertEquals(t.metrics.put(m.copy(metricVersion = "metrics-v2")), Right(Stored.Created))
    assert(t.metrics.put(m.copy(schemaVersion = "x")).isLeft)
  }

  test("a malformed stored payload is a Failure, not an exception") {
    val (kv, t) = tables()
    kv.putIfAbsent("t-links", KvItem("link#m1", "policy#er-v1", "{not json"))
    kv.putIfAbsent(
      "t-bundles",
      KvItem("bundle#b1", "schema#evidence-bundle-v1", """{"bundleId":"b1"}""")
    )
    assert(t.entityLinks.get("m1", "er-v1").left.exists(_.isInstanceOf[KvError.Failure]))
    assert(t.bundles.get("b1").left.exists(_.isInstanceOf[KvError.Failure]))
    // putting over a malformed item must not overwrite it
    val l = EntityLink("m1", "er-v1", researcher, "d", 0.9)
    assert(t.entityLinks.put(l).left.exists(_.isInstanceOf[KvError.Failure]))
    assertEquals(
      kv.get("t-links", "link#m1", "policy#er-v1").toOption.flatten.map(_.payload),
      Some("{not json")
    )
  }

class S3StubSuite extends munit.FunSuite:
  test(
    "putIfAbsent sends a signed create-only PUT with a content hash and maps 200, 412, 409, 500"
  ) {
    val statuses = mutable.Queue(200, 412, 409, 500)
    withStub(_ => (statuses.dequeue(), "", Map.empty)) { (base, seen) =>
      val s3 = new S3ObjectStore(http(), "us-east-2", Some(base))
      val bytes = "héllo".getBytes("UTF-8")
      assertEquals(
        s3.putIfAbsent("s3://sc-raw/raw/a b/k.txt", bytes, "text/plain"),
        Right(PutOutcome.Created)
      )
      val r = seen.head
      assertEquals(r.method, "PUT")
      assertEquals(r.uri, "/sc-raw/raw/a%20b/k.txt")
      assertEquals(r.headers("if-none-match"), "*")
      assertEquals(r.headers("x-amz-meta-sha256"), Hashing.sha256Hex(bytes))
      assertEquals(r.headers("x-amz-content-sha256"), Hashing.sha256Hex(bytes))
      assertEquals(r.headers("content-type"), "text/plain")
      assert(r.headers("authorization").contains("/us-east-2/s3/aws4_request"))
      assertEquals(
        s3.putIfAbsent("s3://sc-raw/k", bytes, "text/plain"),
        Right(PutOutcome.AlreadyExists)
      )
      assert(s3.putIfAbsent("s3://sc-raw/k", bytes, "text/plain").left.exists(_.contains("409")))
      assert(s3.putIfAbsent("s3://sc-raw/k", bytes, "text/plain").left.exists(_.contains("500")))
      assert(s3.putIfAbsent("not-a-uri", bytes, "text/plain").isLeft)
    }
  }

  test("virtual-hosted addressing is used without an endpoint override") {
    var url = ""
    val transport = new scholarchain.core.aws.HttpTransport:
      def execute(r: scholarchain.core.aws.HttpRequest) =
        url = r.url
        Right(scholarchain.core.aws.HttpResponse(200, "", Map.empty))
    val creds = new StaticCredentials(AwsCredentials("AKIDEXAMPLE", "example-only", None))
    val aws = new AwsHttp("us-east-2", creds, transport = transport)
    val s3 = new S3ObjectStore(aws, "us-east-2")
    assertEquals(
      s3.putIfAbsent("s3://sc-raw/raw/x.txt", Array[Byte](1), "text/plain"),
      Right(PutOutcome.Created)
    )
    assertEquals(url, "https://sc-raw.s3.us-east-2.amazonaws.com/raw/x.txt")
  }

  test("get returns UTF-8 text, and a binary payload is detected through the stored hash") {
    val text = "plain text ü"
    val h = Hashing.sha256Hex(text)
    withStub { r =>
      r.uri match
        case "/b-1/text" => (200, text, Map("x-amz-meta-sha256" -> h))
        case "/b-1/bin"  =>
          (
            200,
            "��",
            Map("x-amz-meta-sha256" -> Hashing.sha256Hex("PK\u0003".getBytes("ISO-8859-1")))
          )
        case "/b-1/nohash" => (200, "legacy", Map.empty)
        case _             => (404, "", Map.empty)
    } { (base, _) =>
      val s3 = new S3ObjectStore(http(), "us-east-2", Some(base))
      assertEquals(s3.getText("s3://b-1/text"), Right(text))
      assertEquals(s3.getText("s3://b-1/nohash"), Right("legacy"))
      assert(s3.get("s3://b-1/bin").left.exists(_.contains("binary payloads are unsupported")))
      assert(s3.get("s3://b-1/missing").left.exists(_.contains("404")))
    }
  }

  test("list follows continuation tokens and unescapes XML keys") {
    withStub { r =>
      if r.uri.contains("continuation-token=tok%2B1") then
        (
          200,
          "<ListBucketResult><Contents><Key>p/c.txt</Key></Contents></ListBucketResult>",
          Map.empty
        )
      else
        (
          200,
          "<ListBucketResult><Contents><Key>p/b&amp;b.txt</Key></Contents><Contents><Key>p/a.txt</Key></Contents>" +
            "<NextContinuationToken>tok+1</NextContinuationToken></ListBucketResult>",
          Map.empty
        )
    } { (base, seen) =>
      val s3 = new S3ObjectStore(http(), "us-east-2", Some(base))
      assertEquals(
        s3.list("s3://bkt-1/p"),
        Right(Vector("s3://bkt-1/p/a.txt", "s3://bkt-1/p/b&b.txt", "s3://bkt-1/p/c.txt"))
      )
      assertEquals(seen.size, 2)
      assert(seen.head.uri.startsWith("/bkt-1/?list-type=2&prefix=p%2F"))
    }
  }

  test(
    "an idempotent reload over the wire: second identical write is unchanged, a changed one conflicts"
  ) {
    val data = TrieMap.empty[String, (String, String)]
    withStub { r =>
      r.method match
        case "PUT" =>
          if r.headers.get("if-none-match").contains("*") && data.contains(r.uri) then
            (412, "", Map.empty)
          else
            data(r.uri) = (r.body, r.headers("x-amz-meta-sha256")); (200, "", Map.empty)
        case _ =>
          data
            .get(r.uri)
            .map((b, h) => (200, b, Map("x-amz-meta-sha256" -> h)))
            .getOrElse((404, "", Map.empty))
    } { (base, _) =>
      val w = new ArtifactWriter(new S3ObjectStore(http(), "us-east-2", Some(base)), layout)
      def go(text: String) = w.write(
        Zone.Curated,
        "works",
        runId,
        "p.jsonl",
        text.getBytes("UTF-8"),
        "application/x-ndjson",
        Vector(source)
      )
      assertEquals(go("a\nb\n").toOption.get._2, WriteOutcome.Created)
      assertEquals(go("a\nb\n").toOption.get._2, WriteOutcome.Unchanged)
      assert(go("a\nc\n").left.exists(_.isInstanceOf[ArtifactError.Conflict]))
      assertEquals(data.size, 1)
      assertEquals(data.values.head._1, "a\nb\n")
    }
  }

class DynamoStubSuite extends munit.FunSuite:
  private val CondFailed =
    """{"__type":"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException","message":"x"}"""

  test(
    "putIfAbsent sends a signed PutItem with attribute_not_exists and maps ConditionalCheckFailed"
  ) {
    val replies = mutable.Queue(
      (200, "{}"),
      (400, CondFailed),
      (
        400,
        """{"__type":"com.amazonaws.dynamodb.v20120810#ProvisionedThroughputExceededException"}"""
      )
    )
    withStub(_ => { val (s, b) = replies.dequeue(); (s, b, Map.empty) }) { (base, seen) =>
      val kv = new DynamoDbStore(http(), "us-east-2", Some(base))
      val i = KvItem("run#1", "stage#a", """{"x":1}""", 1L, Some(99L))
      assertEquals(kv.putIfAbsent("t-ledger", i), Right(()))
      val h = seen.head.headers
      assertEquals(h("x-amz-target"), "DynamoDB_20120810.PutItem")
      assertEquals(h("content-type"), "application/x-amz-json-1.0")
      assert(h("authorization").contains("/us-east-2/dynamodb/aws4_request"))
      val body = ujson.read(seen.head.body)
      assertEquals(body("TableName").str, "t-ledger")
      assertEquals(body("ConditionExpression").str, "attribute_not_exists(#pk)")
      assertEquals(body("ExpressionAttributeNames").obj.keySet.toSet, Set("#pk"))
      assertEquals(body("Item")("payload")("S").str, """{"x":1}""")
      assertEquals(body("Item")("version")("N").str, "1")
      assertEquals(body("Item")("ttl")("N").str, "99")
      assertEquals(kv.putIfAbsent("t-ledger", i), Left(KvError.ConditionFailed("run#1", "stage#a")))
      assert(kv.putIfAbsent("t-ledger", i).left.exists {
        case KvError.Failure(m) => m.contains("ProvisionedThroughputExceeded"); case _ => false
      })
      assert(kv.putIfAbsent("t-ledger", KvItem("", "x", "y")).isLeft) // refused before any call
      assertEquals(seen.size, 3)
    }
  }

  test(
    "replaceIfVersion conditions on the expected version and refuses a mis-numbered item locally"
  ) {
    withStub(_ => (200, "{}", Map.empty)) { (base, seen) =>
      val kv = new DynamoDbStore(http(), "us-east-2", Some(base))
      assertEquals(kv.replaceIfVersion("t", KvItem("p", "s", "{}", 3L), 2L), Right(()))
      val body = ujson.read(seen.head.body)
      assertEquals(body("ConditionExpression").str, "attribute_exists(#pk) AND #v = :expected")
      assertEquals(body("ExpressionAttributeValues")(":expected")("N").str, "2")
      assertEquals(body("ExpressionAttributeNames")("#v").str, "version")
      assertEquals(body("Item")("version")("N").str, "3")
      assert(kv.replaceIfVersion("t", KvItem("p", "s", "{}", 9L), 2L).isLeft)
      assertEquals(seen.size, 1)
    }
  }

  test("get reads an item with its version and ttl; absent and malformed items are handled") {
    val replies = mutable.Queue(
      """{"Item":{"pk":{"S":"p"},"sk":{"S":"s"},"payload":{"S":"{}"},"version":{"N":"4"},"ttl":{"N":"7"}}}""",
      "{}",
      """{"Item":{"pk":{"S":"p"}}}""",
      "not json"
    )
    withStub(_ => (200, replies.dequeue(), Map.empty)) { (base, seen) =>
      val kv = new DynamoDbStore(http(), "us-east-2", Some(base))
      assertEquals(kv.get("t", "p", "s"), Right(Some(KvItem("p", "s", "{}", 4L, Some(7L)))))
      val body = ujson.read(seen.head.body)
      assertEquals(body("ConsistentRead").bool, true)
      assertEquals(body("Key")("sk")("S").str, "s")
      assertEquals(seen.head.headers("x-amz-target"), "DynamoDB_20120810.GetItem")
      assertEquals(kv.get("t", "p", "s"), Right(None))
      assert(kv.get("t", "p", "s").left.exists(_.contains("malformed")))
      assert(kv.get("t", "p", "s").isLeft)
    }
  }

  test("query uses key-condition aliases, follows LastEvaluatedKey and stops at the limit") {
    def page(sk: String, more: Boolean) =
      val last = if more then s""","LastEvaluatedKey":{"pk":{"S":"p"},"sk":{"S":"$sk"}}""" else ""
      s"""{"Items":[{"pk":{"S":"p"},"sk":{"S":"$sk"},"payload":{"S":"{}"},"version":{"N":"1"}}]$last}"""
    val replies = mutable.Queue(page("a#1", true), page("a#2", true), page("a#3", false))
    withStub(_ => (200, replies.dequeue(), Map.empty)) { (base, seen) =>
      val kv = new DynamoDbStore(http(), "us-east-2", Some(base))
      assertEquals(kv.query("t", "p", "a#", 3).toOption.get.map(_.sk), Vector("a#1", "a#2", "a#3"))
      val first = ujson.read(seen.head.body)
      assertEquals(first("KeyConditionExpression").str, "#pk = :pk AND begins_with(#sk, :p)")
      assertEquals(first("Limit").num.toInt, 3)
      assert(!first.obj.contains("ExclusiveStartKey"))
      val second = ujson.read(seen(1).body)
      assertEquals(second("ExclusiveStartKey")("sk")("S").str, "a#1")
      assertEquals(second("Limit").num.toInt, 2)
      assertEquals(seen.size, 3)
    }
    val replies2 = mutable.Queue(page("a#1", true), page("a#2", true))
    withStub(_ => (200, replies2.dequeue(), Map.empty)) { (base, seen) =>
      val kv = new DynamoDbStore(http(), "us-east-2", Some(base))
      assertEquals(kv.query("t", "p", "", 2).toOption.get.map(_.sk), Vector("a#1", "a#2"))
      assertEquals(seen.size, 2)
      assert(!ujson.read(seen.head.body)("KeyConditionExpression").str.contains("begins_with"))
      assert(kv.query("t", "p", "", 1001).isLeft)
    }
  }

  test("repositories run over the DynamoDB adapter: a duplicate create is read back and judged") {
    val store = TrieMap.empty[(String, String), ujson.Value]
    withStub { r =>
      val body = ujson.read(r.body)
      r.headers("x-amz-target") match
        case "DynamoDB_20120810.PutItem" =>
          val k = (body("Item")("pk")("S").str, body("Item")("sk")("S").str)
          if store.contains(k) then (400, CondFailed, Map.empty)
          else
            store(k) = body("Item"); (200, "{}", Map.empty)
        case _ =>
          val k = (body("Key")("pk")("S").str, body("Key")("sk")("S").str)
          (200, store.get(k).fold("{}")(i => ujson.write(ujson.Obj("Item" -> i))), Map.empty)
    } { (base, _) =>
      val t = DynamoTables(
        new DynamoDbStore(http(), "us-east-2", Some(base)),
        cfg.dynamo,
        Clock.fixed(t0, ZoneOffset.UTC)
      ).toOption.get
      val l = EntityLink("orcid:0000-0002-1825-0097", "er-v1", researcher, "d", 0.9)
      assertEquals(t.entityLinks.put(l), Right(Stored.Created))
      assertEquals(t.entityLinks.put(l), Right(Stored.Unchanged))
      assert(
        t.entityLinks
          .put(l.copy(confidence = 0.5))
          .left
          .exists(_.isInstanceOf[KvError.ConditionFailed])
      )
      assertEquals(t.entityLinks.get(l.mentionKey, "er-v1"), Right(Some(l)))
    }
  }
