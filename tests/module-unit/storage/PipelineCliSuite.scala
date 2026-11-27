package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.aws.*
import scholarchain.storage.VecFx.{run as vecRun}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/** A Spark run in the in-memory object store: the C13 JSON-lines fixture plus semantic rows, with
  * the run manifest and load manifests the pipeline entry points read.
  */
object CliFx:
  val Out = "s3://sc-graph/runs/label-1/spark"
  val manifestText: String =
    """{"snapshot":"2026-11-22","graph":{"policyVersion":"graph-v1","metricVersion":"metrics-v1"}}"""
  val runId: String = Ids.run(manifestText)
  val snapshot = "2026-11-22"
  val folder = s"$Out/spark/snapshot=$snapshot/run_id=$runId"

  val env: Map[String, String] = Fixtures.env ++ Map(
    "VECTOR_BUCKET" -> "sc-vectors",
    "EMBEDDING_MODEL_ID" -> "test-embed-v1",
    "EMBEDDING_QUEUE_URL" -> "https://sqs.us-east-2.amazonaws.com/000000000000/sc-embedding-jobs",
    "NEPTUNE_ENDPOINT" -> "neptune.invalid",
    PipelineCli.LoaderRoleEnv -> s"arn:aws:iam::${"0" * 12}:role/sc-neptune-loader"
  )
  val cfg: ScholarChainConfig = ConfigLoader.load(env).toOption.get

  val rows: Vector[EvidenceItem] =
    (1 to 23).toVector.map(n => RenderFx.profileRow(n).copy()).map(reRun)

  private def reRun(e: EvidenceItem): EvidenceItem =
    e.copy(provenance = e.provenance.copy(runId = runId))

  private def table(n: Int, extra: Set[String], by: Map[String, Int] = Map.empty) =
    ujson.Obj(
      "rows" -> n,
      "rowsByPartition" -> ujson.Obj.from(by.map((k, v) => k -> ujson.Num(v))),
      "jsonLines" -> ujson.Obj("extraFields" -> ujson.Arr.from(extra.map(ujson.Str(_))))
    )

  def put(store: ObjectStore, uri: String, text: String): Unit =
    assert(store.putIfAbsent(uri, text.getBytes(UTF_8), "application/json").isRight)

  def store(semantic: Vector[EvidenceItem] = rows): InMemoryObjectStore =
    val s = new InMemoryObjectStore
    put(s, s"$Out/run-manifests/$runId.json", manifestText)
    put(s, s"$Out/run-manifests/$runId-summary.json", "{}")
    SparkLayout.Partitions.foreach { (partition, lines) =>
      put(
        s,
        s"$folder/jsonl/$partition/part-00000-a.json",
        lines.mkString("\n").replace(SparkLayout.run, runId)
      )
      put(s, s"$folder/jsonl/$partition/_SUCCESS", "")
    }
    semantic.grouped(10).zipWithIndex.foreach { (g, i) =>
      put(
        s,
        s"$folder/jsonl/semantic-summaries/part-$i.json",
        g.map(upickle.default.write(_)).mkString("\n")
      )
    }
    val head = Seq("runId" -> ujson.Str(runId), "snapshot" -> ujson.Str(snapshot))
    def manifest(name: String, fields: (String, ujson.Value)*): Unit =
      put(s, s"$folder/load-manifests/$name.json", ujson.Obj.from(head ++ fields).render())
    manifest(
      "neptune",
      "nodes" -> table(3, Set.empty, Map("label=Researcher" -> 2, "label=Work" -> 1)),
      "edges" -> table(
        3,
        Set.empty,
        Map("edgeType=AUTHORED" -> 2, "edgeType=COAUTHORED_WITH" -> 1)
      ),
      "coauthorEdges" -> table(1, SparkLayout.CoauthorExtra),
      "coauthoredWith" -> ujson.Obj("windows" -> ujson.Arr("2015-2026"))
    )
    manifest(
      "dynamodb",
      "target" -> ujson.Str("dynamodb"),
      "tables" -> ujson.Arr(
        ujson.Obj(
          "tableConfigKey" -> "dynamo.researcherMetricTable",
          "source" -> {
            val t = table(2, SparkLayout.MetricsExtra)
            t("table") = "researcher-metrics"
            t("recordSchema") = "researcher-metrics-v1"
            t
          }
        )
      ),
      "communities" -> ujson.Obj("source" -> table(3, SparkLayout.CommunitiesExtra))
    )
    manifest(
      "s3vectors",
      "target" -> ujson.Str("s3vectors"),
      "dimension" -> ujson.Num(cfg.vectors.dimension),
      "source" -> table(semantic.size, Set.empty),
      "indexes" -> ujson.Arr(
        ujson.Obj(
          "docType" -> SemanticDocument.ResearcherProfile,
          "index" -> cfg.vectors.indexes("researchers"),
          "rows" -> semantic.size
        )
      )
    )
    s

  /** A loader endpoint that answers a start and then the given statuses in turn. */
  final class LoaderStub(statuses: String*) extends HttpTransport:
    val requests = mutable.ArrayBuffer.empty[HttpRequest]
    private var polls = 0
    def execute(r: HttpRequest): Either[String, HttpResponse] =
      requests += r
      val body =
        if r.method == "POST" then """{"payload":{"loadId":"abcdef12-3456"}}"""
        else
          val s = statuses(math.min(polls, statuses.size - 1))
          polls += 1
          s"""{"payload":{"overallStatus":{"status":"$s","totalRecords":9}}}"""
      Right(HttpResponse(200, body, Map.empty))

  final class Deps(
      val objects: InMemoryObjectStore = store(),
      loader: LoaderStub = new LoaderStub("LOAD_IN_PROGRESS", "LOAD_COMPLETED"),
      render: Either[String, DocumentRenderer] = Right(RenderFx.stub())
  ) extends PipelineDeps:
    val kv: InMemoryKeyValueStore = new InMemoryKeyValueStore
    val vectors: InMemoryVectorStore = new InMemoryVectorStore
    assert(vectors.createBucket().isRight)
    cfg.vectors.indexes.values.foreach(n =>
      assert(vectors.createIndex(VectorIndexes.spec(n, cfg.vectors.dimension)).isRight)
    )
    val queue: InMemoryQueueSender = new InMemoryQueueSender
    var slept = 0L
    def embedder: Either[String, Embedder] = Right(new HashingEmbedder(cfg.vectors.dimension))
    def neptuneLoader(role: String): NeptuneBulkLoader =
      new NeptuneBulkLoader(
        new AwsHttp(
          "us-east-2",
          new StaticCredentials(AwsCredentials("test-id", "test-value")),
          transport = loader
        ),
        cfg.neptune,
        "us-east-2",
        role
      )
    def renderer: Either[String, DocumentRenderer] = render
    override def sleep(millis: Long): Unit = slept += millis
    def ledger = new RunLedger(kv, cfg.dynamo.runLedgerTable)

class PipelineCliSuite extends munit.FunSuite:
  import CliFx.*

  private def cli(
      d: PipelineDeps,
      args: String,
      environment: Map[String, String] = env
  ): (Int, String) =
    val out = new StringBuilder
    val code =
      PipelineCli.run(
        args.split(' ').toList,
        environment,
        _ => d,
        s => out.append(s).append('\n'): Unit
      )
    (code, out.toString)

  private val Selecting = Map("load-vectors" -> " --all", "enqueue-embeddings" -> " --all")
  private def line(c: String) = s"$c --spark-output $Out${Selecting.getOrElse(c, "")}"

  test("dry-run builds no dependency, makes no call and shows no account ID") {
    PipelineCli.Commands.foreach { c =>
      val out = new StringBuilder
      val code = PipelineCli.run(
        s"${line(c)} --dry-run".split(' ').toList,
        env,
        _ => fail("dependencies built in a dry run"),
        s => out.append(s).append('\n'): Unit
      )
      assertEquals(code, PipelineCli.Ok, out.toString)
      assert(out.toString.contains("dry-run: would "), out.toString)
      assert(!out.toString.contains("0" * 12), out.toString)
    }
  }

  test("bad arguments and missing configuration exit 2 and name the variables") {
    val d = new Deps
    Seq(
      "",
      "drop-everything",
      "load-dynamo",
      s"load-dynamo --spark-output /tmp/x",
      s"load-dynamo --spark-output $Out --all",
      s"load-dynamo --spark-output $Out --dry-run --preview",
      s"load-vectors --spark-output $Out",
      s"load-vectors --spark-output $Out --all --max-units-per-type 3",
      s"enqueue-embeddings --spark-output $Out --max-units-per-type 0",
      s"neptune-load --spark-output $Out --wait-seconds"
    ).foreach(a => assertEquals(cli(d, a)._1, PipelineCli.Usage, a))
    val (code, out) =
      cli(d, line("neptune-load"), env - "GRAPH_BUCKET" - PipelineCli.LoaderRoleEnv)
    assertEquals(code, PipelineCli.Usage)
    assert(out.contains("GRAPH_BUCKET") && out.contains(PipelineCli.LoaderRoleEnv), out)
    assert(cli(d, line("enqueue-embeddings"), env - "EMBEDDING_QUEUE_URL")._2.contains("set "))
    assertEquals(d.kv.size(cfg.dynamo.runLedgerTable), 0)
  }

  test("a missing renderer stops the semantic commands before anything is written") {
    val d = new Deps(render = Left("need exactly one RendererProvider on the classpath, found 0"))
    val before = d.objects.keys
    Seq("load-vectors", "enqueue-embeddings").foreach { c =>
      assertEquals(cli(d, line(c))._1, PipelineCli.Usage)
    }
    assertEquals((d.objects.keys, d.queue.calls), (before, 0))
    assert(new AwsPipelineDeps(cfg, Map.empty).renderer.isLeft) // rag is not on this classpath
  }

  test("neptune-load writes the CSV files once, starts the loader on their prefix and waits") {
    val stub = new LoaderStub("LOAD_IN_QUEUE", "LOAD_COMPLETED")
    val d = new Deps(loader = stub)
    val (code, out) = cli(d, line("neptune-load"))
    assertEquals(code, PipelineCli.Ok, out)
    val prefix = s"s3://sc-graph/graph/neptune-load/run_id=$runId/"
    val csv = d.objects.keys.filter(_.startsWith(prefix))
    assertEquals(csv.size, 4)
    val start = stub.requests.head
    assertEquals(ujson.read(new String(start.body, UTF_8))("source").str, prefix)
    assertEquals((stub.requests.size, d.slept), (3, PipelineCli.PollMillis))
    assert(!out.contains("0" * 12), out)
    val keys = d.objects.keys
    assertEquals(cli(d, line("neptune-load") + " --no-start")._1, PipelineCli.Ok)
    assertEquals(d.objects.keys, keys)
  }

  test("neptune-load: s3a output, preview, failed load and unfinished load") {
    val quiet = new LoaderStub("LOAD_FAILED")
    val d = new Deps(loader = quiet)
    val before = d.objects.keys
    val s3a = line("neptune-load").replace("s3://", "s3a://")
    assertEquals(cli(d, s3a + " --preview")._1, PipelineCli.Ok)
    assertEquals(
      (d.objects.keys, quiet.requests.size, d.kv.size(cfg.dynamo.runLedgerTable)),
      (before, 0, 0)
    )
    assertEquals(cli(d, line("neptune-load"))._1, PipelineCli.Failed)
    val slow = new Deps(loader = new LoaderStub("LOAD_IN_PROGRESS"))
    assertEquals(
      cli(slow, line("neptune-load") + " --wait-seconds 60")._1,
      PipelineCli.LoaderPending
    )
    assertEquals(slow.slept, 60000L)
  }

  test("neptune-load fails before any write when the export differs from its manifest") {
    val d = new Deps()
    put(
      d.objects,
      s"$folder/jsonl/graph-nodes/label=Work/part-00001-b.json",
      "{\"broken\":true}"
    )
    val before = d.objects.keys
    val (code, out) = cli(d, line("neptune-load"))
    assertEquals(code, PipelineCli.Failed, out)
    assertEquals(d.objects.keys, before)
  }

  test("a run manifest that is not the hash of its name is refused") {
    val s = new InMemoryObjectStore
    put(s, s"$Out/run-manifests/${Ids.run("""{"other":1}""")}.json", manifestText)
    assert(SparkRun.open(cfg, s, Out).left.exists(_.contains("hash")))
    assert(SparkRun.open(cfg, new InMemoryObjectStore, Out).isLeft)
  }

  test("load-dynamo loads metrics and communities, and a replay creates nothing") {
    val d = new Deps
    val (code, out) = cli(d, line("load-dynamo") + " --preview")
    assertEquals(code, PipelineCli.Ok, out)
    assertEquals(d.kv.size(cfg.dynamo.researcherMetricTable), 0)
    assertEquals(cli(d, line("load-dynamo"))._1, PipelineCli.Ok)
    assertEquals(d.kv.size(cfg.dynamo.researcherMetricTable), 4)
    val again = cli(d, line("load-dynamo"))
    assertEquals(again._1, PipelineCli.Ok)
    assert(again._2.contains("metrics read=2 created=0 unchanged=2"), again._2)
    assertEquals(d.kv.size(cfg.dynamo.researcherMetricTable), 4)
  }

  test("load-vectors embeds the selection once; the second run finds it unchanged") {
    val d = new Deps
    val first = cli(d, s"load-vectors --spark-output $Out --max-units-per-type 5")
    assertEquals(first._1, PipelineCli.Ok, first._2)
    assert(first._2.contains("chunks=5 written=5 unchanged=0"), first._2)
    val again = cli(d, s"load-vectors --spark-output $Out --max-units-per-type 5")
    assert(again._2.contains("written=0 unchanged=5"), again._2)
  }

  test("enqueue sends one message per chunk in batches of 10, in the worker's JSON shape") {
    val d = new Deps
    val (code, out) = cli(d, line("enqueue-embeddings"))
    assertEquals(code, PipelineCli.Ok, out)
    assertEquals((d.queue.messages.size, d.queue.calls), (23, 3))
    d.queue.messages.foreach { m =>
      val j = ujson.read(m)
      assertEquals(j.obj.keySet.toSet, Set("runId", "document"))
      assertEquals(j("runId").str, runId)
      val doc = upickle.default.read[SemanticDocument](j("document"))
      assertEquals((doc.runId, doc.textHash), (runId, Hashing.tagged(doc.text)))
    }
    // what the worker does with the messages: every one loads, with nothing rejected
    val loader = VectorLoader(
      d.vectors,
      new HashingEmbedder(cfg.vectors.dimension),
      new ArtifactWriter(d.objects, new S3Layout(cfg.storage)),
      d.ledger,
      cfg.vectors
    ).toOption.get
    val docs = d.queue.messages.map(m => upickle.default.read[EmbedJobMessage](m).document)
    val loaded = loader.load(runId, RowSource.of(docs)).toOption.get
    assertEquals((loaded.written, loaded.rejected), (23L, Vector.empty))
    // a replay sends byte-identical messages
    val sent = d.queue.messages
    assertEquals(cli(d, line("enqueue-embeddings"))._1, PipelineCli.Ok)
    assertEquals(d.queue.messages, sent ++ sent)
    assertEquals(
      d.ledger.get(runId, "enqueue-embeddings").toOption.get.map(_.value.status),
      Some(LedgerStatus.Succeeded)
    )
  }

  test("enqueue preview and bounded selection; a refused row is recorded and exits 3") {
    val d = new Deps(render = Right(RenderFx.stub(failOn = Set(rows(0).chunkId.get))))
    val preview = cli(d, line("enqueue-embeddings") + " --preview")
    assertEquals(preview._1, PipelineCli.Rejected, preview._2)
    assert(preview._2.contains("messages=22"), preview._2)
    assertEquals((d.queue.calls, d.kv.size(cfg.dynamo.runLedgerTable)), (0, 0))
    assertEquals(cli(d, line("enqueue-embeddings"))._1, PipelineCli.Rejected)
    assertEquals(d.queue.messages.size, 22)
    assertEquals(d.ledger.failures(runId, "enqueue-embeddings").toOption.get.size, 1)
    val some = new Deps
    cli(some, s"enqueue-embeddings --spark-output $Out --max-units-per-type 4")
    assertEquals(some.queue.messages.size, 4)
  }

  test("a truncated export enqueues nothing") {
    val d = new Deps
    put(d.objects, s"$folder/jsonl/semantic-summaries/part-9.json", "not json")
    val (code, out) = cli(d, line("enqueue-embeddings"))
    assertEquals(code, PipelineCli.Failed, out)
    assertEquals(d.queue.calls, 0)
  }

class EmbeddingEnqueuerSuite extends munit.FunSuite:
  private val cfg = CliFx.cfg

  private final class Rig(queue: QueueSender):
    val kv = new InMemoryKeyValueStore
    val ledger = new RunLedger(kv, cfg.dynamo.runLedgerTable)
    val enqueuer = new EmbeddingEnqueuer(
      queue,
      ledger,
      new ArtifactWriter(new InMemoryObjectStore, new S3Layout(cfg.storage)),
      RetryPolicy(3, 1L, _ => ())
    )
    def run(rows: Seq[EvidenceItem]) =
      enqueuer.enqueue(vecRun, RenderFx.sources(rows), RenderFx.stub(maxChunk = 1000000))
    def status = ledger.get(vecRun, enqueuer.Stage).toOption.get.map(_.value.status)

  private val three = (1 to 3).map(RenderFx.profileRow(_))

  test("an entry the queue did not take is sent again, a sender fault is rejected") {
    var calls = 0
    val seen = mutable.ArrayBuffer.empty[Int]
    val q: QueueSender = bodies =>
      calls += 1
      seen += bodies.size
      Right(
        if calls == 1 then
          Vector(SendFailure(0, "InternalError", false), SendFailure(2, "Bad", true))
        else Vector.empty
      )
    val r = new Rig(q)
    val res = r.run(three).toOption.get
    assertEquals(
      (res.messages, res.retriedMessages, res.rejected.size, seen.toVector),
      (2L, 1L, 1, Vector(3, 1))
    )
    assertEquals(r.status, Some(LedgerStatus.Succeeded))
  }

  test("a send that keeps failing fails the stage, records it, and a rerun resends") {
    var down = true
    val inner = new InMemoryQueueSender
    val q: QueueSender =
      b => if down then Left(RemoteError.Transient("HTTP 503")) else inner.send(b)
    val r = new Rig(q)
    assert(r.run(three).isLeft)
    assertEquals(r.status, Some(LedgerStatus.Failed))
    assert(r.ledger.failures(vecRun, r.enqueuer.Stage).toOption.get.nonEmpty)
    down = false
    assertEquals(r.run(three).toOption.map(_.messages), Some(3L))
    assertEquals(r.status, Some(LedgerStatus.Succeeded))
  }

  test("an identical copy is dropped and an oversized message is rejected") {
    val inner = new InMemoryQueueSender
    val r = new Rig(inner)
    val huge = RenderFx.profileRow(9, "long words " * 30000)
    val res = r.run(Seq(three.head, three.head, huge)).toOption.get
    assertEquals((res.messages, res.duplicatesDropped, res.rejected.size), (1L, 1L, 1))
  }

  test("SqsQueueSender: wire shape, per-entry failures and error classes") {
    var reply: Either[String, HttpResponse] = Right(
      HttpResponse(
        200,
        """{"Successful":[{"Id":"m0"}],"Failed":[{"Id":"m1","Code":"X","SenderFault":true}]}""",
        Map.empty
      )
    )
    var last: Option[HttpRequest] = None
    val transport: HttpTransport = req =>
      last = Some(req)
      reply
    val sender = new SqsQueueSender(
      new AwsHttp(
        "us-east-2",
        new StaticCredentials(AwsCredentials("test-id", "test-value")),
        transport = transport
      ),
      "us-east-2",
      "https://sqs.us-east-2.amazonaws.com/q/jobs"
    )
    assertEquals(
      sender.send(Vector("a", "b", "c")),
      Right(
        Vector(SendFailure(1, "X", true), SendFailure(2, "missing from the reply", false))
      )
    )
    val sent = ujson.read(new String(last.get.body, UTF_8))
    assertEquals(sent("Entries").arr.map(_("MessageBody").str).toVector, Vector("a", "b", "c"))
    assert(
      last.get.headers.exists((k, v) =>
        k.equalsIgnoreCase("x-amz-target") && v == "AmazonSQS.SendMessageBatch"
      )
    )
    reply = Right(HttpResponse(503, "", Map.empty))
    assert(sender.send(Vector("a")).left.exists(_.isTransient))
    reply = Right(HttpResponse(403, "denied", Map.empty))
    assert(sender.send(Vector("a")).left.exists(!_.isTransient))
  }

class SharedStageLedgerSuite extends munit.FunSuite:
  private val cfg = CliFx.cfg

  private def report(n: Long) =
    LoadReport.of("vectors", vecRun, "vector-load-v2", Seq("read" -> n), Nil)

  test("one stage recorded once per drain stays within the item limit (31,000 messages)") {
    val kv = new InMemoryKeyValueStore // enforces the 400 KB item limit
    val ledger = new RunLedger(kv, cfg.dynamo.runLedgerTable)
    val stage = new LoadStage(
      ledger,
      new ArtifactWriter(new InMemoryObjectStore, new S3Layout(cfg.storage))
    )
    val drains = 3100
    (1 to drains).foreach { i =>
      val r = stage.run(vecRun, "load-vectors", "vector-load-v2")(
        Right(((), report(1L + i % 10)))
      )
      assert(r.isRight, s"drain $i: ${r.left.toOption}")
    }
    val entry = ledger.get(vecRun, "load-vectors").toOption.get.get.value
    assertEquals(entry.attempt, drains)
    assertEquals(entry.priorOutputs.size, RunLedger.MaxPriorOutputs)
    assertEquals(entry.priorDropped, (drains - 1 - RunLedger.MaxPriorOutputs).toLong)
    assertEquals(entry.priorOutputs.head.attempt, 1) // the first report stays
    // without the cap the same history does not fit one item
    val all = Vector.fill(drains - 1)(entry.priorOutputs.head)
    val uncapped = upickle.default.write(entry.copy(priorOutputs = all))
    assert(KvItem.validate(KvItem("p", "s", uncapped)).isLeft)
  }

  test("a version conflict from another writer is resolved on the fresh entry") {
    val inner = new InMemoryKeyValueStore
    var interfere: () => Unit = () => ()
    val kv = new KeyValueStore:
      def putIfAbsent(t: String, i: KvItem) = inner.putIfAbsent(t, i)
      def replaceIfVersion(t: String, i: KvItem, v: Long) =
        val once = interfere
        interfere = () => ()
        once()
        inner.replaceIfVersion(t, i, v)
      def get(t: String, p: String, s: String) = inner.get(t, p, s)
      def query(t: String, p: String, s: String, l: Int) = inner.query(t, p, s, l)
    val writer = new ArtifactWriter(new InMemoryObjectStore, new S3Layout(cfg.storage))
    val mine = new LoadStage(new RunLedger(kv, cfg.dynamo.runLedgerTable), writer)
    val other = new LoadStage(new RunLedger(inner, cfg.dynamo.runLedgerTable), writer)
    def go(s: LoadStage, n: Long) =
      s.run(vecRun, "load-vectors", "vector-load-v2")(Right(((), report(n))))
    // the other writer finishes between my start and my success, then between two replays
    Seq(1L -> 2L, 3L -> 4L).foreach { (a, b) =>
      interfere = () => assert(go(other, b).isRight)
      val r = go(mine, a)
      assert(r.isRight, r.toString)
      val e = r.toOption.get.ledger
      assertEquals(e.status, LedgerStatus.Succeeded)
      assertEquals(e.outputUri, Some(r.toOption.get.report.uri))
    }
    val last = new RunLedger(inner, cfg.dynamo.runLedgerTable)
      .get(vecRun, "load-vectors")
      .toOption
      .get
      .get
      .value
    assertEquals((last.attempt, last.priorOutputs.size), (4, 3)) // no report pointer lost
  }
