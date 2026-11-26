package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import RagWorld.*

/** Phase 11 C19: no vector store, embedder or community store error text reaches anything a client
  * receives (answer, summary, claims, notes, the evidence notes the model prompt reads). Each
  * failing fake returns a recognizable secret-like string; the whole serialized answer must not
  * contain it, on the deterministic path.
  */
class ErrorTextSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private val Secret = "SECRET-arn:aws:iam::123456789012:role/leak-TOKEN-7731"

  private val failingEmbedder = new Embedder:
    val modelId = "fail-embed-v1"
    val dimension: Int = w.cfg.vectors.dimension
    def embed(text: String): Either[RemoteError, Vector[Float]] =
      Left(RemoteError.Permanent(s"embedding endpoint refused: $Secret"))

  private def failingQueries(v: VectorStore): VectorStore = new VectorStore:
    def createBucket(): Either[RemoteError, PutOutcome] = v.createBucket()
    def createIndex(spec: IndexSpec): Either[RemoteError, PutOutcome] = v.createIndex(spec)
    def put(index: String, vectors: Vector[VectorRecord]): Either[RemoteError, Unit] =
      v.put(index, vectors)
    def get(index: String, keys: Vector[String]): Either[RemoteError, Vector[StoredVector]] =
      v.get(index, keys)
    def query(
        index: String,
        vector: Vector[Float],
        topK: Int,
        filter: Vector[FilterTerm]
    ): Either[RemoteError, Vector[VectorHit]] =
      Left(RemoteError.Transient(s"QueryVectors throttled for $index: $Secret"))

  private val failingCommunities = new CommunityReader:
    def community(
        runId: String,
        communityId: String,
        window: TimeWindow
    ): Either[KvError, Option[CommunitySize]] = Left(KvError.Failure(s"GetItem denied: $Secret"))

  private val Questions = Vector(
    "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?",
    """What are the collaboration metrics of "Alice Arden" since 2015?"""
  )

  /** Runs every question on stores whose retriever uses `vectors` and `embedder`. */
  private def answers(
      vectors: VectorStore => VectorStore = identity,
      embedder: Option[Embedder] = None,
      communities: Option[CommunityReader] = None
  ): Vector[WorkflowResult] =
    val (s, _) = w.stores()
    val vs = vectors(s.vectors)
    val retriever = SemanticContextRetriever(
      w.cfg,
      vs,
      s.objects,
      embedder.getOrElse(s.embedder)
    ).fold(e => fail(e), identity)
    val stores = s.copy(vectors = vs, communities = communities)
    Questions.zipWithIndex.map { (q, i) =>
      new CollaborationWorkflow(
        w.ctx,
        stores,
        retriever,
        DeterministicSynthesizer,
        new InMemoryAuditLog
      )
        .run(s"qry-c19-$i", AnalysisRequest(q))
        .fold(e => fail(e.describe), identity)
    }

  private def clean(rs: Vector[WorkflowResult], ctx: String): Unit =
    rs.foreach { r =>
      val json = upickle.default.write(r.answer)
      assert(!json.contains("SECRET") && !json.contains("123456789012"), s"$ctx: $json")
      assert(!r.evidence.notes.exists(_.contains("SECRET")), s"$ctx: ${r.evidence.notes}")
      assert(!r.synthesis.output.summary.contains("SECRET"), ctx)
    }

  test("a failing embedder: fixed note in the summary, no downstream text anywhere in the answer") {
    val rs = answers(embedder = Some(failingEmbedder))
    clean(rs, "embedder")
    rs.foreach { r =>
      assert(r.evidence.notes.contains(EvidencePipeline.SemanticUnavailableNote), r.evidence.notes)
      assert(r.answer.summary.contains(EvidencePipeline.SemanticUnavailableNote), r.answer.summary)
      assertEquals(r.evidence.bundle.semantic, Vector.empty)
      assert(r.evidence.stages.exists(s => s.stage == "semantic" && s.status == "failed"))
    }
  }

  test("a failing vector store query: the same fixed note, no downstream text in the answer") {
    val rs = answers(vectors = failingQueries)
    clean(rs, "vector store")
    rs.foreach(r =>
      assert(r.answer.summary.contains(EvidencePipeline.SemanticUnavailableNote), r.answer.summary)
    )
  }

  test("a failing community store: a fixed note, no store text in the answer") {
    val rs = answers(communities = Some(failingCommunities))
    clean(rs, "community store")
    assert(
      rs.head.answer.summary.contains("community store read failed"),
      rs.head.answer.summary
    )
  }

  test("control: healthy stores leave no semantic-unavailable note") {
    answers().foreach(r =>
      assert(!r.evidence.notes.contains(EvidencePipeline.SemanticUnavailableNote), r.evidence.notes)
    )
  }
