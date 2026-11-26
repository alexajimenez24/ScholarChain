package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.Fixtures.{cfg, layout}
import scholarchain.storage.VecFx.*
import scala.collection.mutable

class VectorLoaderSuite extends munit.FunSuite:
  private def loaded(r: Rig, docs: Seq[SemanticDocument]) = r.load(docs).toOption.get
  private def reasons(res: VectorLoadResult) = res.rejected.map(_.reason)

  test("all five required indexes are loaded: stable keys, compact metadata, text in ordinary S3") {
    val r = new Rig
    val docs = five
    val res = loaded(r, docs)
    assertEquals((res.read, res.written, res.unchanged, res.rejected), (5L, 5L, 0L, Vector.empty))
    assertEquals(
      res.perIndex.map(_.index).toSet,
      Set(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
    docs.foreach { d =>
      val index = vcfg.indexes(SemanticDocument.IndexKeys(d.docType))
      assertEquals(r.fake.keys(index), Vector(d.chunkId)) // the vector key is the chunk ID
      val v = r.fake.get(index, Vector(d.chunkId)).toOption.get.head
      val MetaValue.Str(uri) = v.metadata("textUri"): @unchecked
      assert(
        uri.startsWith("s3://sc-rag/semantic-context/" + d.docType + "/run_id=" + run + "/"),
        uri
      )
      assertEquals(v.metadata("textSha256"), MetaValue.Str(d.textHash))
      assertEquals(v.metadata("embeddingModel"), MetaValue.Str("hash-embed-v1@16"))
      val stored = upickle.default.read[SemanticDocument](r.objects.getText(uri).toOption.get)
      assertEquals(stored, d) // the long text is in S3, whole
      assertEquals(v.data.size, dim)
    }
    // ledger: one stage, Succeeded, pointing at the stored report
    val e = r.ledger.get
    assertEquals(e.status, LedgerStatus.Succeeded)
    assertEquals(e.outputUri, Some(res.report.uri))
    assert(res.report.uri.startsWith("s3://sc-curated/logs/load-vectors/run_id=" + run))
  }

  test(
    "filters work end to end: researcher, year window, source, topic, community, document type"
  ) {
    val r = new Rig
    val docs = five :+ profile(2)
    loaded(r, docs)
    val q = new HashingEmbedder(dim).embed("researcher profile graph topics").toOption.get
    def keys(index: String, f: FilterTerm*) =
      r.fake.query(index, q, 10, f.toVector).toOption.get.map(_.key).toSet
    val pi = "researcher-profile-v1"
    assertEquals(keys(pi).size, 2)
    assertEquals(
      keys(pi, FilterTerm.Eq("researcherIds", MetaValue.Str(profile(2).entityId))),
      Set(profile(2).chunkId)
    )
    assertEquals(
      keys(pi, FilterTerm.Gte("windowStartYear", 2010), FilterTerm.Lte("windowEndYear", 2014)).size,
      2
    )
    assertEquals(keys(pi, FilterTerm.Eq("window", MetaValue.Str("1950-2026"))), Set.empty[String])
    assertEquals(keys(pi, FilterTerm.Eq("source", MetaValue.Str("SCHOLARCHAIN"))).size, 2)
    assertEquals(
      keys(pi, FilterTerm.Eq("documentType", MetaValue.Str("researcher-profile"))).size,
      2
    )
    assertEquals(
      keys(
        "topic-institution-summary-v1",
        FilterTerm.Eq("topicId", MetaValue.Str("openalex:T10001"))
      ).size,
      1
    )
    assertEquals(
      keys("collaboration-community-v1", FilterTerm.Eq("communityId", MetaValue.Str("cm-1"))).size,
      1
    )
    assertEquals(
      keys("collaboration-path-v1", FilterTerm.Eq("researcherIds", MetaValue.Str(r2))).size,
      1
    )
    assertEquals(
      keys("paper-abstract-v1", FilterTerm.Eq("workId", MetaValue.Str("dblp:conf/x/Paper1"))).size,
      1
    )
  }

  test("idempotent reload: the same input twice changes nothing and calls nothing") {
    val r = new Rig
    val docs = five ++ (10 to 14).map(profile(_))
    val first = loaded(r, docs)
    assertEquals(first.written, 10L)
    val objects = r.objects.keys
    val vectorsBefore = r.fake.keys("researcher-profile-v1")
    val ledgerBefore = r.ledger
    val putsBefore = r.store.putCalls
    val second = loaded(r, docs)
    assertEquals((second.written, second.unchanged, second.rejected), (0L, 10L, Vector.empty))
    assertEquals(r.store.putCalls, putsBefore) // no PutVectors
    assertEquals(r.objects.keys, objects) // no new S3 object, not even the report
    assertEquals(r.fake.keys("researcher-profile-v1"), vectorsBefore)
    assertEquals(second.report.uri, first.report.uri)
    assertEquals(second.reportOutcome, WriteOutcome.Unchanged)
    assertEquals(second.replay, true)
    assertEquals(r.ledger, ledgerBefore)
  }

  test("unchanged text is not re-embedded: the embedder is not called on a replay") {
    val emb = new FixedEmbedder("model-A", dim, t => new HashingEmbedder(dim).embed(t).toOption.get)
    val r = new Rig(emb)
    loaded(r, five)
    assertEquals(emb.calls, 5)
    loaded(r, five)
    assertEquals(emb.calls, 5)
  }

  test("a key bound to different text is rejected and the stored vector is untouched") {
    val r = new Rig
    val d = profile(1)
    loaded(r, Seq(d))
    val before = r.fake.get("researcher-profile-v1", Vector(d.chunkId)).toOption.get.head
    // same chunk ID, different text and hash (a forged or colliding key); chunk check off for this case
    val forged =
      d.copy(text = "Entirely different text", textHash = Hashing.tagged("Entirely different text"))
    val loose = new Rig(settings = VectorLoaderSettings(retry = noWait))
    loaded(loose, Seq(d))
    val res = loose.load(Seq(forged)).toOption.get
    assertEquals(res.written, 0L)
    assert(reasons(res).exists(_.contains("bound to different text")), reasons(res).toString)
    assertEquals(
      loose.fake.get("researcher-profile-v1", Vector(d.chunkId)).toOption.get.head,
      before
    )
    // with the chunk check on (default rig), the forged key is refused up front
    val strict = r.load(Seq(forged)).toOption.get
    assert(reasons(strict).exists(_.contains("chunk ID does not match")))
    assertEquals(r.fake.get("researcher-profile-v1", Vector(d.chunkId)).toOption.get.head, before)
  }

  test("the same text under another embedding model is rejected, not overwritten") {
    val r = new Rig(new FixedEmbedder("model-A", dim, _ => Vector.fill(dim)(0.25f)))
    loaded(r, Seq(profile(1)))
    val r2 = new Rig(new FixedEmbedder("model-B", dim, _ => Vector.fill(dim)(0.5f)))
    r2.fake.put(
      "researcher-profile-v1",
      r.fake
        .get("researcher-profile-v1", Vector(profile(1).chunkId))
        .toOption
        .get
        .map(s => VectorRecord(s.key, s.data, s.metadata))
    )
    val res = r2.load(Seq(profile(1))).toOption.get
    assertEquals((res.written, res.unchanged), (0L, 0L))
    assert(reasons(res).exists(_.contains("another model")), reasons(res).toString)
    assertEquals(
      r2.fake.get("researcher-profile-v1", Vector(profile(1).chunkId)).toOption.get.head.data,
      Vector.fill(dim)(0.25f)
    )
  }

  test("an existing vector without a text hash is not overwritten") {
    val r = new Rig
    val d = profile(1)
    r.fake.put(
      "researcher-profile-v1",
      Vector(VectorRecord(d.chunkId, Vector.fill(dim)(1f), Map.empty))
    )
    val res = loaded(r, Seq(d))
    assertEquals(res.written, 0L)
    assert(reasons(res).exists(_.contains("no textSha256")))
  }

  test(
    "malformed rows, wrong run, unknown type, hash and chunk mismatches are rejected and recorded"
  ) {
    val r = new Rig
    val good = profile(1)
    val input: Seq[Either[String, SemanticDocument]] = Seq(
      Right(good),
      Left("bad json line"),
      Right(profile(2).copy(textHash = "sha256:00")),
      Right(profile(3).copy(docType = "unknown-type")),
      Right(profile(4).copy(runId = Ids.run("""{"other":1}"""))),
      Right(profile(5).copy(chunkId = "c:zzz")),
      Right(profile(6).copy(text = "")),
      Right(profile(7).copy(schemaVersion = "semantic-doc-v0")),
      Right(profile(8).copy(declaredIndex = Some("other-index-v1"))),
      Right(profile(9).copy(chunkId = Ids.chunk("sc:r:v1:other", mv, "researcher-profile", 0, "x")))
    )
    val src = new RowSource[SemanticDocument]:
      def rows = input.iterator
    val res = r.loader.load(run, src).toOption.get
    assertEquals((res.read, res.written), (10L, 1L))
    assertEquals(res.rejected.size, 9)
    val rs = reasons(res)
    Seq(
      "malformed row",
      "textHash does not match",
      "unknown document type",
      "belongs to run",
      "malformed chunk ID",
      "empty text",
      "schema",
      "names index",
      "chunk ID does not match"
    ).foreach(p => assert(rs.exists(_.contains(p)), s"$p in $rs"))
    assert(res.rejected.forall(_.stage == "load-vectors"))
    assertEquals(r.fake.keys("researcher-profile-v1"), Vector(good.chunkId))
    // the stored report carries every rejection
    val rep = upickle.default.read[LoadReport](r.objects.getText(res.report.uri).toOption.get)
    assertEquals(rep.rejected.size, 9)
    assertEquals(rep.counts.find(_.name == "rejected").map(_.value), Some(9L))
  }

  test("missing metadata: no entity, no window for a windowed type, a window on an abstract") {
    val r = new Rig
    val res = loaded(
      r,
      Seq(
        profile(1).copy(entityId = " "),
        profile(2).copy(window = None),
        five(2).copy(window = Some(w2010)),
        profile(3).copy(window = Some(TimeWindow(2020, 2010)))
      )
    )
    assertEquals(res.written, 0L)
    val rs = reasons(res)
    assert(rs.exists(_.contains("empty entityId")), rs.toString)
    assert(rs.count(_.contains("window is required except for paper-abstract")) == 2, rs.toString)
    assert(rs.exists(_.contains("bad window")))
  }

  test(
    "malformed metadata: too many researcher IDs and an oversized value are rejected, not truncated"
  ) {
    val r = new Rig
    val many = doc(
      SemanticDocument.PathSummary,
      "ev:graph_path:aaaaaaaaaaaaaaaa",
      "A path with a very long list",
      (1 to 17).map(i => s"sc:r:v1:${"%020x".format(i)}").toVector
    )
    val huge = profile(1).copy(entityId = "e" * 600)
    val hugeFixed = huge.copy(chunkId = Ids.chunk(huge.entityId, mv, huge.docType, 0, huge.text))
    val res = loaded(r, Seq(many, hugeFixed))
    assertEquals(res.written, 0L)
    val rs = reasons(res)
    assert(rs.exists(_.contains("more than")), rs.toString)
    assert(rs.exists(_.contains("long text")), rs.toString)
    assertEquals(r.fake.keys("collaboration-path-v1"), Vector.empty[String])
  }

  test("a wrong-dimension embedding is rejected before any put") {
    val r = new Rig(new FixedEmbedder("model-A", dim, _ => Vector(1f, 2f, 3f)))
    val res = loaded(r, Seq(profile(1)))
    assertEquals((res.written, r.store.putCalls), (0L, 0))
    assert(reasons(res).exists(_.contains("vector has 3 values")), reasons(res).toString)
  }

  test(
    "duplicate keys: an identical copy is dropped and counted, conflicting copies are all rejected"
  ) {
    val r = new Rig
    val a = profile(1)
    val b = profile(2)
    val conflicting = b.copy(sourceUri = "s3://sc-graph/elsewhere")
    val res = loaded(r, Seq(a, a, a, b, conflicting))
    assertEquals(res.duplicatesDropped, 2L)
    assertEquals(res.written, 1L)
    assertEquals(r.fake.keys("researcher-profile-v1"), Vector(a.chunkId))
    assertEquals(res.rejected.size, 2) // every copy of the conflicting key
    assert(reasons(res).forall(_.contains("duplicate vector key with different content")))
    // a reload still finds one stored copy and the same rejections
    val again = loaded(r, Seq(a, a, a, b, conflicting))
    assertEquals((again.written, again.unchanged, again.rejected.size), (0L, 1L, 2))
  }

  test("no long text in vector metadata, over the whole load: every stored value is compact") {
    val longText = (1 to 400).map(i => s"sentence $i about collaboration and graphs.").mkString(" ")
    val r = new Rig
    val d = profile(7, longText)
    val res = loaded(r, Seq(d))
    assertEquals(res.written, 1L)
    val v = r.fake.get("researcher-profile-v1", Vector(d.chunkId)).toOption.get.head
    val strings = v.metadata.values.flatMap {
      case MetaValue.Str(s) => Vector(s); case MetaValue.Strs(xs) => xs; case _ => Vector.empty
    }
    assert(strings.forall(_.length <= 200), strings.toString)
    assert(!v.metadata.keys.exists(k => Set("text", "body", "content", "summary").contains(k)))
    assert(!strings.exists(s => s.length > 30 && longText.contains(s)))
    val MetaValue.Str(uri) = v.metadata("textUri"): @unchecked
    assert(r.objects.getText(uri).toOption.get.contains(longText)) // the text is in S3
  }

  test(
    "batching: groups of 100, so 250 documents are three GetVectors and puts of 100, 100 and 50"
  ) {
    val r = new Rig
    val res = loaded(r, (1 to 250).map(profile(_)))
    assertEquals(res.written, 250L)
    assertEquals(r.store.puts.toVector, Vector(100, 100, 50))
    assertEquals(r.store.gets, 3)
    val small = new Rig(settings =
      VectorLoaderSettings(batchSize = 40, chunkSourceVersion = Some(mv), retry = noWait)
    )
    loaded(small, (1 to 100).map(profile(_)))
    assertEquals(small.store.puts.toVector, Vector(40, 40, 20))
  }

  test(
    "a transient PutVectors failure is retried; success on a later attempt writes the batch once"
  ) {
    val r = new Rig
    r.store.putFailures.enqueue(RemoteError.Transient("503"), RemoteError.Transient("429"))
    val res = loaded(r, (1 to 3).map(profile(_)))
    assertEquals((res.written, res.rejected), (3L, Vector.empty))
    assertEquals(r.store.putCalls, 3)
    assertEquals(r.store.puts.toVector, Vector(3))
  }

  test(
    "a persistent transient failure rejects the batch with the reason; a replay then completes it"
  ) {
    val r = new Rig
    r.store.putFailures.enqueue(
      RemoteError.Transient("503"),
      RemoteError.Transient("503"),
      RemoteError.Transient("503")
    )
    val res = loaded(r, (1 to 3).map(profile(_)))
    assertEquals((res.written, res.rejected.size), (0L, 3))
    assert(reasons(res).forall(_.contains("PutVectors researcher-profile-v1 failed: 503")))
    assertEquals(r.store.putCalls, 3) // bounded: 3 attempts
    val replay = loaded(r, (1 to 3).map(profile(_)))
    assertEquals((replay.written, replay.rejected), (3L, Vector.empty))
  }

  test("a permanent PutVectors failure is not retried") {
    val r = new Rig
    r.store.putFailures.enqueue(RemoteError.Permanent("403 denied"))
    val res = loaded(r, Seq(profile(1)))
    assertEquals((r.store.putCalls, res.rejected.size), (1, 1))
    assert(reasons(res).head.contains("403 denied"))
  }

  test("embedding: a transient failure is retried, a permanent one rejects only its document") {
    val emb = new FixedEmbedder("model-A", dim, t => new HashingEmbedder(dim).embed(t).toOption.get)
    val r = new Rig(emb)
    emb.failures.enqueue(RemoteError.Transient("throttled"))
    val ok = loaded(r, Seq(profile(1)))
    assertEquals((ok.written, emb.calls), (1L, 2))
    emb.failures.enqueue(RemoteError.Permanent("validation"))
    val res = loaded(r, Seq(profile(2), profile(3)))
    assertEquals((res.written, res.rejected.size), (1L, 1))
    assert(reasons(res).head.contains("embedding failed: validation"))
  }

  test("GetVectors failing for good fails the stage; the ledger shows it and a replay retries it") {
    val r = new Rig
    r.store.getFailures.enqueue(
      RemoteError.Transient("x"),
      RemoteError.Transient("x"),
      RemoteError.Transient("x")
    )
    assert(r.load(Seq(profile(1))).left.exists(_.contains("GetVectors")))
    assertEquals(r.ledger.map(e => (e.status, e.attempt)), Some((LedgerStatus.Failed, 1)))
    assertEquals(r.fake.keys("researcher-profile-v1"), Vector.empty[String])
    val ok = loaded(r, Seq(profile(1)))
    assertEquals(ok.written, 1L)
    assertEquals(r.ledger.map(e => (e.status, e.attempt)), Some((LedgerStatus.Succeeded, 2)))
  }

  test("after a text-object failure the rest of the batch is neither written nor embedded") {
    val emb = new FixedEmbedder("model-A", dim, t => new HashingEmbedder(dim).embed(t).toOption.get)
    var puts = 0
    val failing = new ObjectStore:
      val inner = new InMemoryObjectStore
      def putIfAbsent(uri: String, bytes: Array[Byte], contentType: String) =
        puts += 1
        Left("S3 503 injected")
      def get(uri: String) = inner.get(uri)
      def list(prefixUri: String) = inner.list(prefixUri)
    val fake = new InMemoryVectorStore
    fake.createBucket()
    SemanticDocument.IndexKeys.values.foreach(k =>
      fake.createIndex(VectorIndexes.spec(vcfg.indexes(k), dim))
    )
    val tables = DynamoTables(new InMemoryKeyValueStore, cfg.dynamo).toOption.get
    val loader = VectorLoader(
      fake,
      emb,
      new ArtifactWriter(failing, layout),
      tables.runLedger,
      vcfg,
      VectorLoaderSettings(chunkSourceVersion = Some(mv), retry = noWait)
    ).toOption.get
    val res = loader.load(run, RowSource.of((1 to 20).map(profile(_))), None)
    assert(res.left.exists(_.contains("503")), res.toString)
    assertEquals(puts, 1) // the first document only
    assert(emb.calls <= 1, s"embedder was called ${emb.calls} times after the S3 failure")
    assertEquals(fake.keys("researcher-profile-v1"), Vector.empty[String])
  }

  test("an expected row count that the source does not meet fails before any write") {
    val r = new Rig
    assert(r.load(five, Some(316L)).left.exists(_.contains("expected 316 rows")))
    assertEquals((r.store.gets, r.store.putCalls, r.objects.keys), (0, 0, Vector.empty[String]))
    assertEquals(r.ledger.map(_.status), Some(LedgerStatus.Failed))
    assert(r.load(five, Some(5L)).isRight) // the stage is retried and succeeds
  }

  test("the vector loader fails its stage on a missing input directory, even with one row due") {
    val r = new Rig
    val absent = java.nio.file.Path.of("target", "no-such-vector-dir-" + run.take(8))
    val res = r.loader.load(run, RowSource.jsonLinesDir[SemanticDocument](absent), Some(1L))
    assert(res.left.exists(_.contains("is missing")), res.toString)
    val rendered = r.loader.loadRendered(
      run,
      RowSource.jsonLinesDir[EvidenceItem](absent).mapE(e => Right(SourceRecord.Summary(e))),
      RenderFx.stub()
    )
    assert(rendered.left.exists(_.contains("is missing")), rendered.toString)
    assertEquals(r.ledger.map(_.status), Some(LedgerStatus.Failed))
    assertEquals((r.store.gets, r.store.putCalls, r.objects.keys), (0, 0, Vector.empty[String]))
  }

  test("a different logic version for the same run and stage is a conflict, not a silent rerun") {
    val r = new Rig
    loaded(r, five)
    assert(r.tables.runLedger.start(run, r.loader.Stage, "vector-load-v9").isLeft)
  }

  test("a stored text that differs from the new document is a rejection, never an overwrite") {
    val r = new Rig
    val d = profile(1)
    // pre-existing object at the document's key, with other bytes
    val uri = layout.runKey(Zone.SemanticContext, d.docType, run, s"${d.chunkId}.json").toOption.get
    r.objects.putIfAbsent(uri, "other".getBytes("UTF-8"), "application/json")
    val res = loaded(r, Seq(d))
    assertEquals(res.written, 0L)
    assert(reasons(res).head.contains("not overwritten"))
    assertEquals(r.objects.getText(uri), Right("other"))
  }

  test("the loader refuses a bad setup: dimension, missing or repeated index names, batch size") {
    val r = new Rig
    def make(
        c: VectorConfig = vcfg,
        e: Embedder = r.embedder,
        s: VectorLoaderSettings = VectorLoaderSettings()
    ) =
      VectorLoader(r.store, e, r.writer, r.tables.runLedger, c, s)
    assert(make().isRight)
    assert(make(e = new HashingEmbedder(8)).left.exists(_.contains("dimension")))
    assert(make(c = vcfg.copy(indexes = vcfg.indexes - "paths")).left.exists(_.contains("paths")))
    assert(
      make(c =
        vcfg.copy(indexes = vcfg.indexes.updated("paths", vcfg.indexes("researchers")))
      ).isLeft
    )
    assert(make(s = VectorLoaderSettings(batchSize = 0)).isLeft)
    assert(make(s = VectorLoaderSettings(batchSize = 101)).isLeft)
    assertEquals(r.loader.specs.size, 5)
    assert(r.loader.specs.forall(_.nonFilterableKeys == VectorIndexes.NonFilterable))
  }

  test("JSON-lines rows load like in-memory rows; a bad line is a rejection") {
    val r = new Rig
    val lines = five.map(JsonLines.encode(_)) :+ "{\"docType\":1}"
    val res = r.loader.load(run, RowSource.jsonLines[SemanticDocument](lines.iterator)).toOption.get
    assertEquals((res.read, res.written, res.rejected.size), (6L, 5L, 1))
  }

  test("an abstract from the Hadoop export loads into paper-abstract-v1 with no window") {
    val r = new Rig
    val text = "We study coauthorship graphs at scale."
    val pa = PaperAbstract(
      "dblp:conf/x/P2",
      Hashing.tagged(text),
      text,
      Provenance(
        SourceType.DBLP,
        "dblp:conf/x/P2",
        "2026-11-22",
        run,
        "abstract-v1",
        Some("norm-v1")
      )
    )
    val res = r.loader
      .loadRendered(run, RowSource.of(Seq(SourceRecord.Abstract(pa))), RenderFx.stub())
      .toOption
      .get
    assertEquals(res.written, 1L)
    val v = r.fake.get("paper-abstract-v1", r.fake.keys("paper-abstract-v1")).toOption.get.head
    assertEquals(v.metadata("source"), MetaValue.Str("DBLP"))
    assert(!v.metadata.contains("window"))
  }

class ResearcherMetricsLoaderSuite extends munit.FunSuite:
  private val ids = (1 to 4).map(i => s"sc:r:v1:${"%020x".format(i)}")
  private def m(i: Int, pubs: Long = 3, rid: String = run): ResearcherMetrics =
    val base = Fixtures.metrics(ids(i), pubs)
    base.copy(provenance = base.provenance.copy(runId = rid))

  private final class Rig:
    val kv = new InMemoryKeyValueStore
    val objects = new InMemoryObjectStore
    val tables = DynamoTables(kv, cfg.dynamo).toOption.get
    val loader =
      new ResearcherMetricsLoader(
        tables.metrics,
        tables.runLedger,
        new ArtifactWriter(objects, layout)
      )
    def load(rows: Seq[ResearcherMetrics], expected: Option[Long] = None) =
      loader.load(run, RowSource.of(rows), expected)

  test("rows are stored in ResearcherMetricCache under (researcher, metric version, window)") {
    val r = new Rig
    val res = r.load(Seq(m(0), m(1))).toOption.get
    assertEquals((res.read, res.created, res.unchanged, res.rejected), (2L, 2L, 0L, Vector.empty))
    assertEquals(
      r.tables.metrics.get(ids(0), "metrics-v1", TimeWindow(2015, 2026)).toOption.get,
      Some(m(0))
    )
    assertEquals(
      r.tables.runLedger.get(run, r.loader.Stage).toOption.get.map(_.value.status),
      Some(LedgerStatus.Succeeded)
    )
  }

  test("idempotent reload: the same input twice changes no item, no object and no ledger state") {
    val r = new Rig
    val first = r.load(Seq(m(0), m(1), m(2))).toOption.get
    val size = r.kv.size(cfg.dynamo.researcherMetricTable)
    val keys = r.objects.keys
    val ledger = r.tables.runLedger.get(run, r.loader.Stage).toOption.get
    val second = r.load(Seq(m(0), m(1), m(2))).toOption.get
    assertEquals((second.created, second.unchanged), (0L, 3L))
    assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), size)
    assertEquals(r.objects.keys, keys)
    assertEquals(r.tables.runLedger.get(run, r.loader.Stage).toOption.get, ledger)
    assertEquals(
      (second.report.uri, second.reportOutcome, second.replay),
      (first.report.uri, WriteOutcome.Unchanged, true)
    )
  }

  test("a changed value under the same metric version is rejected and the stored one stays") {
    val r = new Rig
    r.load(Seq(m(0, 3)))
    val res = r.load(Seq(m(0, 9))).toOption.get
    assertEquals((res.created, res.unchanged, res.rejected.size), (0L, 0L, 1))
    assert(res.rejected.head.reason.contains("already stored"))
    assertEquals(
      r.tables.metrics
        .get(ids(0), "metrics-v1", TimeWindow(2015, 2026))
        .toOption
        .get
        .map(_.publicationCount),
      Some(3L)
    )
  }

  test(
    "duplicate rows: identical copies are dropped and counted, conflicting copies are all rejected"
  ) {
    val r = new Rig
    val res = r.load(Seq(m(0), m(0), m(1, 3), m(1, 4))).toOption.get
    assertEquals((res.read, res.created, res.duplicatesDropped, res.rejected.size), (4L, 1L, 1L, 2))
    assertEquals(
      r.tables.metrics.get(ids(1), "metrics-v1", TimeWindow(2015, 2026)).toOption.get,
      None
    )
  }

  test("invalid rows, rows of another run and malformed lines are rejected with their reasons") {
    val r = new Rig
    val other = Ids.run("""{"other":2}""")
    val src = new RowSource[ResearcherMetrics]:
      def rows = Iterator(Right(m(0)), Right(m(1, 0)), Right(m(2, 3, other)), Left("bad line"))
    val res = r.loader.load(run, src).toOption.get
    assertEquals((res.created, res.rejected.size), (1L, 3))
    val rs = res.rejected.map(_.reason)
    assert(
      rs.exists(_.contains("no publications")) && rs.exists(_.contains("belongs to run")) && rs
        .exists(_.contains("malformed row"))
    )
  }

  test("the manifest row count is checked before any write") {
    val r = new Rig
    assert(r.load(Seq(m(0), m(1)), Some(162L)).left.exists(_.contains("expected 162 rows")))
    assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 0)
    assert(r.load(Seq(m(0), m(1)), Some(2L)).isRight)
  }

  test("a store failure fails the stage; a replay resumes and completes it") {
    val kv = new InMemoryKeyValueStore
    var fail = true
    val flaky = new KeyValueStore:
      def putIfAbsent(t: String, i: KvItem) =
        if fail && t == cfg.dynamo.researcherMetricTable then Left(KvError.Failure("throttled"))
        else kv.putIfAbsent(t, i)
      def replaceIfVersion(t: String, i: KvItem, v: Long) = kv.replaceIfVersion(t, i, v)
      def get(t: String, pk: String, sk: String) = kv.get(t, pk, sk)
      def query(t: String, pk: String, p: String, l: Int) = kv.query(t, pk, p, l)
    val tables = DynamoTables(flaky, cfg.dynamo).toOption.get
    val loader = new ResearcherMetricsLoader(
      tables.metrics,
      tables.runLedger,
      new ArtifactWriter(new InMemoryObjectStore, layout)
    )
    assert(loader.load(run, RowSource.of(Seq(m(0)))).left.exists(_.contains("throttled")))
    assertEquals(
      tables.runLedger.get(run, loader.Stage).toOption.get.map(_.value.status),
      Some(LedgerStatus.Failed)
    )
    fail = false
    assertEquals(loader.load(run, RowSource.of(Seq(m(0)))).toOption.get.created, 1L)
  }

  private def absent =
    java.nio.file.Path.of("target", "no-such-metrics-dir-" + run.take(8))

  test("a missing input directory fails the stage; it is never a Succeeded load of one reject") {
    val r = new Rig
    val res =
      r.loader.load(run, RowSource.jsonLinesDir[ResearcherMetrics](absent, Validate.metrics))
    assert(res.left.exists(e => e.contains("input directory") && e.contains("is missing")), res)
    val entry = r.tables.runLedger.get(run, r.loader.Stage).toOption.get.map(_.value)
    assertEquals(entry.map(e => (e.status, e.outputUri)), Some((LedgerStatus.Failed, None)))
    assert(entry.flatMap(_.error).exists(_.contains("is missing")), entry)
    assertEquals(r.objects.keys, Vector.empty[String])
    assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 0)
  }

  test("a missing input directory fails whatever the expected row count says, also 0 and 1") {
    Seq(0L, 1L, 2L).foreach { n =>
      val r = new Rig
      val res = r.loader.load(run, RowSource.jsonLinesDir[ResearcherMetrics](absent), Some(n))
      assert(res.left.exists(_.contains("is missing")), s"$n: $res")
      assertEquals(
        r.tables.runLedger.get(run, r.loader.Stage).toOption.get.map(_.value.status),
        Some(LedgerStatus.Failed)
      )
    }
  }

  test("the input problem survives mapE, filter and concat") {
    val missing = RowSource.jsonLinesDir[ResearcherMetrics](absent)
    val ok = RowSource.of(Seq(m(0)))
    assert(missing.mapE(Right(_)).problem.isDefined)
    assert(RowSource.filter(missing)(_ => true).problem.isDefined)
    assert(RowSource.concat(ok, missing)(identity, identity).problem.isDefined)
    assert(RowSource.concat(missing, ok)(identity, identity).problem.isDefined)
    assertEquals(RowSource.concat(ok, ok)(identity, identity).problem, None)
  }

  test("an existing directory without rows is a legitimate empty load: Succeeded, read 0") {
    val r = new Rig
    val dir = java.nio.file.Files.createTempDirectory("empty-metrics")
    try
      val res = r.loader
        .load(run, RowSource.jsonLinesDir[ResearcherMetrics](dir), Some(0L))
        .toOption
        .get
      assertEquals((res.read, res.created, res.rejected), (0L, 0L, Vector.empty))
      assertEquals(
        r.tables.runLedger.get(run, r.loader.Stage).toOption.get.map(_.value.status),
        Some(LedgerStatus.Succeeded)
      )
    finally java.nio.file.Files.delete(dir)
  }

  test("a repairing replay records its new report on the ledger and keeps the earlier one") {
    val r = new Rig
    val broken = new RowSource[ResearcherMetrics]:
      def rows = Iterator(Right(m(0)), Left("truncated line"))
    val first = r.loader.load(run, broken).toOption.get
    assertEquals(first.rejected.size, 1)
    val before = r.tables.runLedger.get(run, r.loader.Stage).toOption.get.get
    val repaired = r.load(Seq(m(0), m(1))).toOption.get
    assertEquals((repaired.created, repaired.rejected, repaired.replay), (1L, Vector.empty, true))
    assertNotEquals(repaired.report.sha256, first.report.sha256)
    val after = r.tables.runLedger.get(run, r.loader.Stage).toOption.get.get
    assertEquals(
      (after.value.status, after.value.attempt, after.value.outputUri, after.value.outputSha256),
      (LedgerStatus.Succeeded, 2, Some(repaired.report.uri), Some(repaired.report.sha256))
    )
    assertEquals(
      after.value.priorOutputs,
      Vector(PriorOutput(1, first.report.uri, first.report.sha256, before.value.updatedAt))
    )
    assert(r.objects.keys.contains(first.report.uri)) // the earlier report object stays
    // the same replay again changes nothing
    r.load(Seq(m(0), m(1)))
    assertEquals(r.tables.runLedger.get(run, r.loader.Stage).toOption.get.get, after)
    // a failing replay of a Succeeded stage changes nothing either
    assert(r.load(Seq(m(0)), Some(9L)).isLeft)
    assertEquals(r.tables.runLedger.get(run, r.loader.Stage).toOption.get.get, after)
    // the history is version-guarded: a stale entry cannot overwrite it
    assert(r.tables.runLedger.recordReport(before, "s3://x/y", "00").isLeft)
    assertEquals(r.tables.runLedger.get(run, r.loader.Stage).toOption.get.get, after)
  }

  test("a ledger entry without report history keeps the old stored form and decodes") {
    val old =
      """{"runId":"r","stage":"s","status":"Started","attempt":1,"logicVersion":"v",""" +
        """"outputUri":null,"outputSha256":null,"error":null,"updatedAt":"t"}"""
    val e = upickle.default.read[LedgerEntry](old)
    assertEquals(e.priorOutputs, Vector.empty)
    assert(!upickle.default.write(e).contains("priorOutputs"))
  }

class NeptuneLoadJobSuite extends munit.FunSuite:
  import NeptuneFx.{plan, run as nrun}

  test("writing the Neptune load files is a ledger stage and an idempotent reload") {
    val store = new InMemoryObjectStore
    val kv = new InMemoryKeyValueStore
    val tables = DynamoTables(kv, cfg.dynamo).toOption.get
    val aw = new ArtifactWriter(store, layout)
    val job = new NeptuneLoadJob(tables.runLedger, new NeptuneLoadWriter(aw, layout), aw)
    val first = job.run(plan).toOption.get
    assert(first.outcomes.forall(_ == WriteOutcome.Created))
    val keys = store.keys
    val entry = tables.runLedger.get(nrun, job.Stage).toOption.get
    assertEquals(entry.map(_.value.status), Some(LedgerStatus.Succeeded))
    assert(entry.flatMap(_.value.outputUri).exists(_.contains("/logs/write-neptune-files/run_id=")))
    val again = job.run(plan).toOption.get
    assert(again.outcomes.forall(_ == WriteOutcome.Unchanged), again.outcomes.toString)
    assertEquals(store.keys, keys)
    assertEquals(tables.runLedger.get(nrun, job.Stage).toOption.get, entry)
  }

  test("a changed plan under the same run is a conflict; nothing is overwritten") {
    val store = new InMemoryObjectStore
    val tables = DynamoTables(new InMemoryKeyValueStore, cfg.dynamo).toOption.get
    val aw = new ArtifactWriter(store, layout)
    val job = new NeptuneLoadJob(tables.runLedger, new NeptuneLoadWriter(aw, layout), aw)
    job.run(plan)
    val changedNodes = NeptuneFx.nodes.map(n =>
      if n.nodeId == NeptuneFx.r3 then NeptuneFx.researcher(NeptuneFx.r3, "Other Name") else n
    )
    val changed = NeptuneExport.build(NeptuneFx.input(ns = changedNodes))
    assert(job.run(changed).left.exists(_.contains("Conflict")))
    assertEquals(
      tables.runLedger.get(nrun, job.Stage).toOption.get.map(_.value.status),
      Some(
        LedgerStatus.Succeeded
      ) // the original success stands; the conflicting replay is only reported
    )
  }
