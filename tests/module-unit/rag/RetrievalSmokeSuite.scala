package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import scholarchain.rag.SmokeFixture.*

/** Retrieval smoke tests on the hand-built [[SmokeFixture]] (in-memory vector store, deterministic
  * fake embedder, rendered by SemanticRenderer and loaded by storage's VectorLoader), and one test
  * per Phase 08 gate item that the rag side can show.
  */
class RetrievalSmokeSuite extends munit.FunSuite:
  private val vectorStore = new InMemoryVectorStore
  private val objectStore = new InMemoryObjectStore
  private lazy val loaded =
    load(stores = LocalStores(objectStore, new InMemoryKeyValueStore, vectorStore))
  private lazy val rag = loaded.retriever()

  private def textOf(id: String, w: TimeWindow = Recent): String =
    summaries
      .find(e => e.values("entityId") == id && e.values("window") == w.label)
      .flatMap(_.text)
      .getOrElse(fail(s"no fixture row $id ${w.label}"))

  private def top(
      docType: String,
      query: String,
      k: Int = 3,
      filter: ContextFilter = ContextFilter()
  ): Vector[SemanticContextItem] =
    val r = rag
      .retrieve(ContextQuery(query, Vector(ContextTarget(docType, k, filter))))
      .fold(fail(_), identity)
    assertEquals(r.dropped, Vector.empty)
    r.context.items

  private def entities(items: Vector[SemanticContextItem]) = items.map(_.entityId)

  test("the fixture loads through storage's loader into the indexes with 0 rejections") {
    val r = loaded.result
    assertEquals(r.rejected, Vector.empty)
    assertEquals((r.read, r.chunks, r.written, r.duplicatesDropped), (13L, 13L, 13L, 0L))
    assertEquals(
      r.perIndex.map(i => i.index -> i.present).toMap,
      Map(
        "researcher-profile-v1" -> 5L,
        "collaboration-community-v1" -> 3L,
        "topic-institution-summary-v1" -> 3L,
        "paper-abstract-v1" -> 2L
      )
    )
  }

  test("smoke: each researcher's nearest researcher is the one with the same field") {
    val rp = SemanticDocument.ResearcherProfile
    val w = ContextFilter(window = Some(Recent))
    assertEquals(entities(top(rp, textOf(Alice), 2, w)), Vector(Alice, Bob))
    assertEquals(entities(top(rp, textOf(Bob), 2, w)), Vector(Bob, Alice))
    assertEquals(entities(top(rp, "protein folding molecular dynamics", 1)), Vector(Carol))
    assertEquals(entities(top(rp, "Byzantine consensus distributed ledgers", 1)), Vector(Dan))
  }

  test("smoke: a researcher's profile retrieves the community of its field") {
    val cp = SemanticDocument.CommunityProfile
    assertEquals(entities(top(cp, textOf(Alice), 1)), Vector(QuantumCommunity))
    assertEquals(entities(top(cp, textOf(Bob), 1)), Vector(QuantumCommunity))
    assertEquals(entities(top(cp, textOf(Carol), 1)), Vector(BiologyCommunity))
    assertEquals(entities(top(cp, textOf(Dan), 1)), Vector(SystemsCommunity))
  }

  test("smoke: a researcher's profile retrieves the topic of its field") {
    val ts = SemanticDocument.TopicSummary
    assertEquals(entities(top(ts, textOf(Alice), 1)), Vector(QuantumTopic))
    assertEquals(entities(top(ts, textOf(Carol), 1)), Vector(FoldingTopic))
    assertEquals(entities(top(ts, textOf(Dan), 1)), Vector(ConsensusTopic))
  }

  test("smoke: a topical question retrieves the representative paper") {
    val pa = SemanticDocument.PaperAbstractType
    assertEquals(
      entities(top(pa, "decoders for surface codes on qubits", 1)),
      Vector(SurfaceCodeWork)
    )
    assertEquals(entities(top(pa, "simulated protein folding pathways", 1)), Vector(FoldingWork))
  }

  test("smoke: window and researcher filters restrict the neighbours") {
    val rp = SemanticDocument.ResearcherProfile
    val q = "compiler optimization register allocation"
    val any = top(rp, q, 1)
    assertEquals(any.map(i => (i.entityId, i.window)), Vector((Alice, Some("2000-2009"))))
    val early = top(rp, q, 5, ContextFilter(window = Some(Early)))
    assertEquals(early.map(i => (i.entityId, i.window)), Vector((Alice, Some("2000-2009"))))
    val recent = top(rp, q, 5, ContextFilter(window = Some(Recent)))
    assertEquals(recent.size, 4)
    assert(recent.forall(_.window.contains("2015-2026")))
    val bob = top(rp, "quantum", 5, ContextFilter(researcherId = Some(Bob)))
    assertEquals(entities(bob), Vector(Bob))
    val one = top(rp, "quantum", 5, ContextFilter(entityId = Some(Carol)))
    assertEquals(entities(one), Vector(Carol))
  }

  test("smoke: one question over several indexes gives one bounded, score-ordered context") {
    val r = rag
      .retrieve(
        ContextQuery(
          "quantum error correction with surface codes",
          Vector(
            ContextTarget(SemanticDocument.ResearcherProfile, 2),
            ContextTarget(SemanticDocument.CommunityProfile, 1),
            ContextTarget(SemanticDocument.TopicSummary, 1),
            ContextTarget(SemanticDocument.PaperAbstractType, 1)
          )
        )
      )
      .fold(fail(_), identity)
    val items = r.context.items
    assertEquals(
      items.map(_.entityId).toSet,
      Set(Alice, Bob, QuantumCommunity, QuantumTopic, SurfaceCodeWork)
    )
    assertEquals(items.map(_.score), items.map(_.score).sorted.reverse)
    assert(items.map(_.text.length).sum <= ContextBudget.V1.maxTotalChars)
  }

  test("gate: one vector key never refers to two texts") {
    val key = summaries.head.chunkId.get
    val ix = "researcher-profile-v1"
    val stored = vectorStore.get(ix, Vector(key)).fold(e => fail(e.text), identity).head
    val uri = stored.metadata("textUri") match
      case MetaValue.Str(u) => u
      case other            => fail(other.toString)
    val doc = objectStore
      .getText(uri)
      .flatMap(JsonLines.decode[SemanticDocument](_))
      .fold(fail(_), identity)
    val other = doc.text + " Distinct coauthors: 999."
    val second = doc.copy(text = other, textHash = Hashing.tagged(other))
    val r = loaded.loader.load(RunId, RowSource.of(Vector(second))).fold(fail(_), identity)
    assertEquals(r.written, 0L)
    assertEquals(r.rejected.map(_.reason), Vector("vector key already bound to different text"))
    // the vector, its pointer and the stored text are unchanged, and retrieval returns the first
    val after = vectorStore.get(ix, Vector(key)).fold(e => fail(e.text), identity).head
    assertEquals(after.metadata, stored.metadata)
    assertEquals(objectStore.getText(uri), Right(JsonLines.encode(doc)))
    val item = top(SemanticDocument.ResearcherProfile, textOf(Alice), 1).head
    assertEquals((item.chunkId, item.text), (key, doc.text))
    // and every key in every index holds exactly one text hash, matching its stored object
    for spec <- loaded.loader.specs; k <- vectorStore.keys(spec.name) do
      val v = vectorStore.get(spec.name, Vector(k)).fold(e => fail(e.text), identity).head
      val md = v.metadata.collect { case (n, MetaValue.Str(x)) => n -> x }
      val d = objectStore.getText(md("textUri")).flatMap(JsonLines.decode[SemanticDocument](_))
      assertEquals(d.map(x => (x.chunkId, x.textHash)), Right((k, md("textSha256"))))
  }

  test("gate: the embedding model version is stored with every vector and in the context") {
    val version = EmbeddingModelVersion.of("hash-embed-v1", 256).fold(fail(_), _.id)
    assertEquals(version, "hash-embed-v1@256")
    val keys = loaded.loader.specs.flatMap(s => vectorStore.keys(s.name).map(s.name -> _))
    assertEquals(keys.size, 13)
    for (ix, k) <- keys do
      val v = vectorStore.get(ix, Vector(k)).fold(e => fail(e.text), identity).head
      assertEquals(v.metadata.get("embeddingModel"), Some(MetaValue.Str(version)))
    val items = top(SemanticDocument.ResearcherProfile, "quantum", 5)
    assert(items.nonEmpty && items.forall(_.embeddingModel == version))
    // vectors of one model are never read as context for a query embedded by another
    val other = new Embedder {
      val modelId = "amazon.titan-embed-text-v2:0"
      val dimension = 256
      def embed(text: String) = new HashingEmbedder(256).embed(text)
    }
    val r = loaded
      .retriever(embedder = other)
      .retrieve(
        ContextQuery("quantum", Vector(ContextTarget(SemanticDocument.ResearcherProfile, 5)))
      )
      .fold(fail(_), identity)
    assertEquals(r.context.items, Vector.empty)
    assertEquals(r.dropped.size, 5)
    assert(
      r.dropped.forall(_.reason.contains("embedded with hash-embed-v1@256")),
      r.dropped.toString
    )
  }

  test("gate: text is fetched through the vector's ordinary-S3 pointer, never from metadata") {
    val spy = new TamperedObjects(objectStore, Map.empty)
    val r = loaded
      .retriever(objects = spy)
      .retrieve(
        ContextQuery(
          "quantum error correction",
          Vector(
            ContextTarget(SemanticDocument.ResearcherProfile, 5),
            ContextTarget(SemanticDocument.PaperAbstractType, 2)
          )
        )
      )
      .fold(fail(_), identity)
    val items = r.context.items
    assertEquals(items.size, 7)
    // one S3 read per item, each at the item's textUri, which is the hit's textUri
    assertEquals(spy.reads.toVector.sorted, items.map(_.textUri).sorted)
    for i <- items do
      val v = vectorStore.get(i.index, Vector(i.chunkId)).fold(e => fail(e.text), identity).head
      assertEquals(v.metadata.get("textUri"), Some(MetaValue.Str(i.textUri)))
      val stored = objectStore
        .getText(i.textUri)
        .flatMap(JsonLines.decode[SemanticDocument](_))
        .fold(fail(_), identity)
      assertEquals(i.text, stored.text)
      assertEquals(Hashing.tagged(i.text), i.textSha256)
      // the vector metadata holds no part of the text
      val strings = v.metadata.values.toVector.flatMap {
        case MetaValue.Str(s)   => Vector(s)
        case MetaValue.Strs(xs) => xs
        case _                  => Vector.empty
      }
      // storage's compactness rule: no value of 40+ characters repeats a span of the text
      assert(!strings.exists(s => s.length >= 40 && i.text.contains(s)), i.chunkId)
      assert(!strings.exists(_.contains(i.text.take(40))), i.chunkId)
    // without the S3 objects there is no context: the text cannot come from anywhere else
    val empty = loaded.retriever(objects = new InMemoryObjectStore)
    val none = empty
      .retrieve(
        ContextQuery("quantum", Vector(ContextTarget(SemanticDocument.ResearcherProfile, 5)))
      )
      .fold(fail(_), identity)
    assertEquals(none.context.items, Vector.empty)
    assertEquals(none.dropped.size, 5)
    assert(
      none.dropped.forall(_.reason.startsWith("text object unreadable")),
      none.dropped.toString
    )
  }
