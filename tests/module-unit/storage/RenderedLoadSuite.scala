package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.RenderFx.*
import scholarchain.storage.VecFx.*
import java.nio.file.{Files, Path}
import scala.collection.mutable

/** The loader with a renderer: text only from the port, one document per chunk, the work queue, the
  * batches, the pointer and metadata, skip and refusal, and the ledger's failures and retries.
  */
class RenderedLoadSuite extends munit.FunSuite:
  private def vectorOf(r: Rig, index: String, key: String) =
    r.fake.get(index, Vector(key)).toOption.get.head
  private def uriOf(v: StoredVector) =
    val MetaValue.Str(u) = v.metadata("textUri"): @unchecked
    u
  private def recordingEmbedder(seen: mutable.Buffer[String]) =
    new FixedEmbedder(
      "model-A",
      dim,
      t =>
        seen += t
        new HashingEmbedder(dim).embed(t).toOption.get
    )

  test("the port: one document per rendered chunk; the text is the renderer's, not the row's") {
    val seen = mutable.Buffer.empty[String]
    val r = new Rig(recordingEmbedder(seen))
    val pa = abstractRow(1)
    val rows = Seq(
      profileRow(1),
      summaryRow(SemanticDocument.CommunityProfile, "cm-1", "Community of five researchers"),
      summaryRow(SemanticDocument.PathSummary, "ev:graph_path:0123456789abcdef", "Path text"),
      summaryRow(SemanticDocument.TopicSummary, "openalex:T10001", "Topic summary of graphs"),
      pa
    )
    val renderer = stub()
    val res = r.loader.loadRendered(run, sources(rows), renderer).toOption.get
    assertEquals((res.read, res.chunks, res.written, res.rejected), (5L, 5L, 5L, Vector.empty))
    assertEquals(renderer.calls, 5)
    assertEquals(res.perIndex.size, 5)
    // the raw abstract text never reached the embedder; the rendered one did
    assert(
      seen.contains(s"Paper abstract. Work: ${pa.workId}. Abstract: ${pa.text}"),
      seen.toString
    )
    assert(!seen.contains(pa.text))
    val key = renderer.renderAbstract(pa).toOption.get.chunks.head.chunkId
    val v = vectorOf(r, "paper-abstract-v1", key)
    val stored = upickle.default.read[SemanticDocument](r.objects.getText(uriOf(v)).toOption.get)
    assertEquals(stored.text, s"Paper abstract. Work: ${pa.workId}. Abstract: ${pa.text}")
    // the authoritative source row is stored too, and the stored chunk points at it
    val src = r.objects.getText(stored.sourceRecordUri.get).toOption.get
    assertEquals(upickle.default.read[PaperAbstract](src), pa)
    assert(stored.sourceRecordUri.get.contains("/paper-abstract-source/"))
    assertEquals(stored.documentUri, None) // one chunk: the chunk object holds the rendered text
  }

  test("pointer and compact metadata: the fields of the gate, model version `<id>@<dimension>`") {
    val r = new Rig
    val row = profileRow(1)
    r.loader.loadRendered(run, sources(Seq(row)), stub()).toOption.get
    val v = vectorOf(r, "researcher-profile-v1", row.chunkId.get)
    assertEquals(
      v.metadata.keySet,
      Set(
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
        "window",
        "windowStartYear",
        "windowEndYear",
        "researcherIds",
        "chunkOrdinal",
        "chunkCount",
        "template"
      )
    )
    assertEquals(v.metadata("embeddingModel"), MetaValue.Str("hash-embed-v1@16"))
    assertEquals(v.metadata("textSha256"), MetaValue.Str(Hashing.tagged(row.text.get)))
    assertEquals(v.metadata("template"), MetaValue.Str(Template))
    assertEquals(v.metadata("runId"), MetaValue.Str(run))
    assertEquals(v.metadata("source"), MetaValue.Str("SCHOLARCHAIN"))
    assertEquals(v.metadata("researcherIds"), MetaValue.Strs(row.researcherIds))
    assertEquals(v.key, row.chunkId.get) // vector key = chunk ID
    assert(uriOf(v).startsWith("s3://sc-rag/semantic-context/researcher-profile/run_id="))
    // no text in metadata: every value is short
    val strings = v.metadata.values.flatMap {
      case MetaValue.Str(s) => Vector(s); case MetaValue.Strs(xs) => xs; case _ => Vector.empty
    }
    assert(strings.forall(_.length < 200))
  }

  test("a document of several chunks: one vector per chunk, the whole text stored once") {
    val r = new Rig
    val text = "0123456789" * 12 // 120 characters
    val row = profileRow(1, text)
    val res = r.loader.loadRendered(run, sources(Seq(row)), stub(maxChunk = 50)).toOption.get
    assertEquals((res.read, res.chunks, res.written, res.rejected), (1L, 3L, 3L, Vector.empty))
    val keys = r.fake.keys("researcher-profile-v1")
    assertEquals(keys.size, 3)
    val docs = keys
      .map(k =>
        upickle.default.read[SemanticDocument](
          r.objects.getText(uriOf(vectorOf(r, "researcher-profile-v1", k))).toOption.get
        )
      )
      .sortBy(_.ordinal)
    assertEquals(docs.map(_.ordinal), Vector(0, 1, 2))
    assertEquals(docs.map(_.chunkCount).distinct, Vector(3))
    assertEquals(docs.map(_.text).mkString, text)
    val uris = docs.flatMap(_.documentUri).distinct
    assertEquals(uris.size, 1) // one whole-document object for all three chunks
    assert(uris.head.contains("/researcher-profile-document/"))
    val whole = upickle.default.read[RenderedDocument](r.objects.getText(uris.head).toOption.get)
    assertEquals(whole.text, text)
    assertEquals(whole.chunks.size, 3)
    val md = vectorOf(r, "researcher-profile-v1", docs(1).chunkId).metadata
    assertEquals((md("chunkOrdinal"), md("chunkCount")), (MetaValue.Num(1L), MetaValue.Num(3L)))
    // a multi-chunk document passes the chunkSourceVersion check (the Rig sets it)
  }

  test("a renderer Left, a throwing renderer and an inconsistent rendering are rejections") {
    val r = new Rig
    val bad = profileRow(2)
    val boom = profileRow(3)
    val good = profileRow(1)
    val wrongEntity = profileRow(4)
    val renderer = new DocumentRenderer:
      val inner = stub(failOn = Set(bad.chunkId.get), throwOn = Set(boom.chunkId.get))
      def templateVersions = inner.templateVersions
      def renderSummary(e: EvidenceItem) =
        inner
          .renderSummary(e)
          .map(d => if e == wrongEntity then d.copy(entityId = "sc:r:v1:zz") else d)
      def renderAbstract(a: PaperAbstract) = inner.renderAbstract(a)
    val res = r.loader
      .loadRendered(run, sources(Seq(good, bad, boom, wrongEntity)), renderer)
      .toOption
      .get
    assertEquals((res.read, res.written, res.rejected.size), (4L, 1L, 3))
    val why = res.rejected.map(x => x.input -> x.reason).toMap
    assert(why(bad.chunkId.get).contains("render failed: stub refuses"))
    assert(why(boom.chunkId.get).contains("renderer threw IllegalStateException"))
    assert(why(wrongEntity.chunkId.get).contains("rendering refused"))
    assertEquals(r.fake.keys("researcher-profile-v1"), Vector(good.chunkId.get))
  }

  test("idempotent: a second identical pass renders, but skips every vector and writes nothing") {
    val seen = mutable.Buffer.empty[String]
    val r = new Rig(recordingEmbedder(seen))
    val rows = (1 to 8).map(profileRow(_)) :+ abstractRow(1)
    val first = r.loader.loadRendered(run, sources(rows), stub()).toOption.get
    assertEquals((first.written, first.unchanged), (9L, 0L))
    val embedded = seen.size
    val objects = r.objects.keys
    val puts = r.store.putCalls
    val second = r.loader.loadRendered(run, sources(rows), stub()).toOption.get
    assertEquals((second.written, second.unchanged, second.rejected), (0L, 9L, Vector.empty))
    assertEquals((seen.size, r.store.putCalls, r.objects.keys), (embedded, puts, objects))
    assertEquals(second.putBatches, 0L)
    assertEquals(second.reportOutcome, WriteOutcome.Unchanged)
  }

  test("skip needs both the text hash and the model version; a new model is refused, not skipped") {
    val r = new Rig(new FixedEmbedder("model-A", dim, _ => Vector.fill(dim)(0.25f)))
    r.loader.loadRendered(run, sources(Seq(profileRow(1))), stub()).toOption.get
    val other = new Rig(new FixedEmbedder("model-B", dim, _ => Vector.fill(dim)(0.5f)))
    other.fake.put(
      "researcher-profile-v1",
      r.fake
        .get("researcher-profile-v1", r.fake.keys("researcher-profile-v1"))
        .toOption
        .get
        .map(s => VectorRecord(s.key, s.data, s.metadata))
    )
    val res = other.loader.loadRendered(run, sources(Seq(profileRow(1))), stub()).toOption.get
    assertEquals((res.written, res.unchanged), (0L, 0L))
    assert(
      res.rejected.head.reason.contains("another model than model-B@16"),
      res.rejected.toString
    )
  }

  test(
    "one vector key never refers to two texts: a renderer reusing a key for new text is refused"
  ) {
    val r = new Rig
    val text1 = "A" * 60
    val text2 = "B" * 60
    // multi-chunk ids are the renderer's formula, so a faulty renderer can reuse an id
    val fixedId = (_: String, dt: String, _: String, i: Int, _: String) =>
      Ids.chunk("sc:r:v1:fixed", mv, dt, i + 5, "fixed")
    val first = r.loader
      .loadRendered(run, sources(Seq(profileRow(1, text1))), stub(maxChunk = 30, idFor = fixedId))
      .toOption
      .get
    assertEquals(first.written, 2L)
    val before = r.fake
      .keys("researcher-profile-v1")
      .map(k => k -> vectorOf(r, "researcher-profile-v1", k).data)
    val second = r.loader
      .loadRendered(run, sources(Seq(profileRow(1, text2))), stub(maxChunk = 30, idFor = fixedId))
      .toOption
      .get
    assertEquals(second.written, 0L)
    assertEquals(second.rejected.size, 2)
    assert(second.rejected.forall(_.reason.contains("already bound to different text")))
    assertEquals(
      r.fake
        .keys("researcher-profile-v1")
        .map(k => k -> vectorOf(r, "researcher-profile-v1", k).data),
      before
    )
  }

  test("duplicate keys in one input: identical copies dropped, conflicting copies all rejected") {
    val r = new Rig
    val a = profileRow(1)
    val b = profileRow(2)
    val conflicting = b.copy(sourceUri = "s3://sc-graph/elsewhere")
    val res = r.loader.loadRendered(run, sources(Seq(a, a, b, conflicting)), stub()).toOption.get
    assertEquals((res.written, res.duplicatesDropped, res.rejected.size), (1L, 1L, 2))
    assertEquals(r.fake.keys("researcher-profile-v1"), Vector(a.chunkId.get))
  }

  test(
    "queue bounds: offer refuses at capacity, drain empties in order, the loader never exceeds it"
  ) {
    val q = new InMemoryWorkQueue[Int](3)
    assert(q.offer(1) && q.offer(2) && q.offer(3))
    assert(q.isFull && !q.offer(4))
    assertEquals(q.drain(2), Vector(1, 2))
    assert(q.offer(4))
    assertEquals((q.size, q.highWater), (2, 3))
    assertEquals(q.drain(10), Vector(3, 4))
    assert(q.isEmpty)
    intercept[IllegalArgumentException](new InMemoryWorkQueue[Int](0))
    val r = new Rig(settings =
      VectorLoaderSettings(queueCapacity = 7, retry = noWait, chunkSourceVersion = Some(mv))
    )
    val res = r.loader.loadRendered(run, sources((1 to 40).map(profileRow(_))), stub()).toOption.get
    assertEquals((res.written, res.queueHighWater), (40L, 7))
    assert(
      VectorLoader(
        r.fake,
        r.embedder,
        r.writer,
        r.tables.runLedger,
        vcfg,
        VectorLoaderSettings(queueCapacity = 0)
      ).isLeft
    )
  }

  test("batching: documents of different indexes interleaved still fill batches of 100") {
    val r = new Rig
    val rows = (1 to 150).flatMap(i =>
      Seq(
        profileRow(i),
        summaryRow(
          SemanticDocument.CommunityProfile,
          s"cm-$i",
          s"Community number $i of researchers"
        )
      )
    )
    val res = r.loader.loadRendered(run, sources(rows), stub()).toOption.get
    assertEquals(res.written, 300L)
    assertEquals(r.store.puts.toVector.sorted, Vector(50, 50, 100, 100))
    assertEquals(res.putBatches, 4L)
    assert(r.store.puts.max <= VectorLimits.MaxPutBatch)
  }

  test("failures and retries are recorded in the RunLedger, with counts") {
    val r = new Rig
    r.store.putFailures
      .enqueue(RemoteError.Transient("503 slow down"), RemoteError.Transient("503 slow down"))
    val bad = profileRow(9)
    val res = r.loader
      .loadRendered(run, sources(Seq(profileRow(1), bad)), stub(failOn = Set(bad.chunkId.get)))
      .toOption
      .get
    assertEquals((res.written, res.rejected.size), (1L, 1))
    assertEquals(res.retries, Map("retry:PutVectors:researcher-profile-v1" -> 2L))
    val fails = r.tables.runLedger.failures(run, r.loader.Stage).toOption.get.map(_.value)
    val bySubject = fails.map(f => f.subject -> f).toMap
    assertEquals(bySubject("retry:PutVectors:researcher-profile-v1").retryCount, 2)
    assert(bySubject("retry:PutVectors:researcher-profile-v1").reason.contains("503"))
    assertEquals(bySubject(s"reject:${bad.chunkId.get}").retryCount, 1)
    assert(bySubject(s"reject:${bad.chunkId.get}").reason.contains("render failed"))
    assertEquals(res.failureRecords, 2L)
    // the same rejection on a replay counts up
    r.loader
      .loadRendered(run, sources(Seq(profileRow(1), bad)), stub(failOn = Set(bad.chunkId.get)))
      .toOption
      .get
    val again = r.tables.runLedger.failures(run, r.loader.Stage).toOption.get.map(_.value)
    assertEquals(again.find(_.subject == s"reject:${bad.chunkId.get}").map(_.retryCount), Some(2))
  }

  test("itemized failure records are capped; the rest are one counted overflow record") {
    val r = new Rig(settings =
      VectorLoaderSettings(maxFailureRecords = 2, retry = noWait, chunkSourceVersion = Some(mv))
    )
    val rows = (1 to 5).map(profileRow(_))
    val res = r.loader
      .loadRendered(run, sources(rows), stub(failOn = rows.map(_.chunkId.get).toSet))
      .toOption
      .get
    assertEquals(res.rejected.size, 5)
    val fails = r.tables.runLedger.failures(run, r.loader.Stage).toOption.get.map(_.value)
    assertEquals(fails.size, 3)
    assertEquals(fails.find(_.subject == "reject:overflow").map(_.retryCount), Some(3))
  }

  test("a stage that fails is in the ledger with its attempt; a replay retries it") {
    val r = new Rig
    r.store.getFailures.enqueue(RemoteError.Permanent("403"))
    assert(r.loader.loadRendered(run, sources(Seq(profileRow(1))), stub()).isLeft)
    assertEquals(r.ledger.map(e => (e.status, e.attempt)), Some((LedgerStatus.Failed, 1)))
    assert(r.loader.loadRendered(run, sources(Seq(profileRow(1))), stub()).isRight)
    assertEquals(r.ledger.map(e => (e.status, e.attempt)), Some((LedgerStatus.Succeeded, 2)))
  }

  test("an expected row count that the source does not meet fails before any write") {
    val r = new Rig
    assert(r.loader.loadRendered(run, sources(Seq(profileRow(1))), stub(), Some(5L)).isLeft)
    assertEquals((r.store.putCalls, r.objects.keys), (0, Vector.empty[String]))
  }

  // ------------------------------------------------------------ JSON lines with extraFields

  private def withDir[A](f: Path => A): A =
    val dir = Files.createTempDirectory("storage-suite")
    try f(dir)
    finally
      java.nio.file.Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete(_))

  private def summaryLine(row: EvidenceItem): String =
    val j = ujson.read(JsonLines.encode(row))
    j("docType") = row.values("docType")
    j("windowLabel") = row.values("window")
    j.render()

  test("JSON lines with the declared extraFields decode after the fields are dropped") {
    withDir { dir =>
      val rows = (1 to 3).map(profileRow(_))
      Files.writeString(dir.resolve("part-0.json"), rows.map(summaryLine).mkString("\n") + "\n")
      val strict = RowSource.jsonLinesDir[EvidenceItem](dir).rows.toVector
      assert(strict.forall(_.isLeft)) // unknown fields are still refused without dropping
      val dropped = RowSource
        .jsonLinesDir[EvidenceItem](dir, Validate.evidence, Set("docType", "windowLabel"))
        .rows
        .toVector
      assertEquals(dropped, rows.map(Right(_)).toVector)
      // another unknown field is still refused
      val extra = ujson.read(summaryLine(rows.head)); extra("surprise") = 1
      Files.writeString(dir.resolve("part-1.json"), extra.render() + "\n")
      val mixed = RowSource
        .jsonLinesDir[EvidenceItem](dir, dropFields = Set("docType", "windowLabel"))
        .rows
        .toVector
      assertEquals(mixed.count(_.isLeft), 1)
      assertEquals(mixed.count(_.isRight), 3)
    }
    // garbage is a Left row, not an exception
    withDir { dir =>
      Files.writeString(dir.resolve("part-2.json"), "not json\n[1]\n")
      val junk =
        RowSource.jsonLinesDir[EvidenceItem](dir, dropFields = Set("docType")).rows.toVector
      assertEquals(junk.count(_.isLeft), 2)
    }
  }
