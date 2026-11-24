package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*

/** A hand-built retrieval fixture with known neighbours, loaded through storage's loader.
  *
  * Four researchers in window 2015-2026 work in three fields, each with its own vocabulary:
  *   - Alice Arden and Bob Brandt: quantum error correction and surface codes (same venue and
  *     institute), so each is the other's nearest researcher;
  *   - Carol Chen: protein folding and molecular dynamics;
  *   - Dan Dorsey: Byzantine consensus and distributed ledgers.
  * Alice also has a 2000-2009 profile in compiler optimization. Each field has one community (with
  * its members' names and topics) and one topic summary, and two abstracts cover quantum surface
  * codes and protein folding. The expected neighbours follow from shared vocabulary under the
  * token-hashing fake embedder (`hash-embed-v1`), not from running the code.
  *
  * The summary texts follow the Spark layouts (the anchors of each rag template, filled with
  * values) and carry Spark-style numbers, so numbers appear in text exactly as in the real export.
  */
object SmokeFixture:
  val RunId = "run-5a5a5a5a5a5a5a5a"
  val Snapshot = "2026-11-24"
  val Recent: TimeWindow = TimeWindow(2015, 2026)
  val Early: TimeWindow = TimeWindow(2000, 2009)

  val cfg: ScholarChainConfig = LocalIngestion.localConfig().fold(e => sys.error(e), identity)
  val renderer: SemanticRenderer =
    SemanticRenderer(cfg.vectors).fold(e => sys.error(e), identity)

  val Alice = "sc:r:v1:" + "a" * 20
  val Bob = "sc:r:v1:" + "b" * 20
  val Carol = "sc:r:v1:" + "c" * 20
  val Dan = "sc:r:v1:" + "d" * 20
  val QuantumCommunity = "cm:1111111111111111"
  val BiologyCommunity = "cm:2222222222222222"
  val SystemsCommunity = "cm:3333333333333333"
  val QuantumTopic = "openalex:T1001"
  val FoldingTopic = "openalex:T1002"
  val ConsensusTopic = "openalex:T1003"
  val SurfaceCodeWork = "dblp:conf/qec/Arden21"
  val FoldingWork = "dblp:journals/cbj/Chen22"

  /** A Spark-shaped summary row whose text fills the template's anchors with `fillers`. */
  def summary(
      tpl: SummaryTemplate,
      w: TimeWindow,
      entity: String,
      researcherIds: Vector[String],
      fillers: Vector[String]
  ): EvidenceItem =
    val prov = Provenance(
      SourceType.SCHOLARCHAIN,
      entity,
      Snapshot,
      RunId,
      SchemaVersions.Evidence,
      Some("metrics-v1")
    )
    val shell = EvidenceItem(
      SchemaVersions.Evidence,
      "ev:semantic_profile:0000000000000000",
      EvidenceType.SEMANTIC_PROFILE,
      None,
      researcherIds,
      s"file:///smoke/${tpl.docType}/windowLabel=${w.label}",
      None,
      None,
      0.0,
      Map(
        "docType" -> tpl.docType,
        "vectorIndex" -> cfg.vectors.indexes(SemanticDocument.IndexKeys(tpl.docType)),
        "entityId" -> entity,
        "window" -> w.label
      ),
      None,
      None,
      prov
    )
    val anchors = tpl.anchors(w.label, entity, shell).fold(e => sys.error(e), identity)
    require(anchors.size == fillers.size, s"${tpl.tag} needs ${anchors.size} fillers")
    val text = anchors.zip(fillers).map(_ + _).mkString + "."
    val chunk = Ids.chunk(entity, "metrics-v1", tpl.docType, 0, text)
    shell.copy(
      evidenceId = Ids.evidence(EvidenceType.SEMANTIC_PROFILE, chunk),
      chunkId = Some(chunk),
      text = Some(text),
      textHash = Some(Hashing.tagged(text))
    )

  def profile(
      w: TimeWindow,
      id: String,
      name: String,
      counts: (Int, Int),
      work: String,
      topics: String,
      venues: String,
      institutions: String
  ): EvidenceItem =
    val (pubs, coauthors) = counts
    summary(
      ResearcherProfileTemplate,
      w,
      id,
      Vector(id),
      Vector(
        name,
        pubs.toString,
        coauthors.toString,
        (coauthors + 3).toString,
        "3.10",
        "6",
        "1",
        work,
        (coauthors + 3).toString,
        "cc:00000000000000aa with 120 researchers",
        "cm:0000000000000000",
        "3",
        "2",
        "2",
        "0.320",
        "0.0150",
        topics,
        venues,
        institutions,
        "1.00"
      )
    )

  def community(id: String, members: String, topics: String, venues: String, inst: String) =
    summary(
      CommunityProfileTemplate,
      Recent,
      id,
      Vector.empty,
      Vector("2", "1", "1", members, topics, venues, inst)
    )

  def topic(id: String, name: String, inst: String, venues: String) =
    summary(
      TopicSummaryTemplate,
      Recent,
      id,
      Vector.empty,
      Vector(name, "14", "2016-2024", "2", inst, venues)
    )

  private val Qec =
    "Quantum error correction (7); Surface codes (5); Superconducting qubits (4)"
  private val QecVenue = "Quantum Information Letters (6)"
  private val QecInst = "Example Quantum Institute (8)"
  private val Bio = "Protein folding (6); Molecular dynamics simulation (5); Structural biology (3)"
  private val BioVenue = "Computational Biology Journal (5)"
  private val BioInst = "Example Biology Institute (6)"
  private val Sys =
    "Byzantine consensus (6); Distributed ledgers (4); Replicated state machines (3)"
  private val SysVenue = "Distributed Systems Symposium (5)"
  private val SysInst = "Example Systems Lab (5)"

  val summaries: Vector[EvidenceItem] = Vector(
    profile(
      Recent,
      Alice,
      "Alice Arden",
      (12, 41),
      "Surface code decoders (dblp:conf/qec/Arden21, 2021)",
      Qec,
      QecVenue,
      QecInst
    ),
    profile(
      Recent,
      Bob,
      "Bob Brandt",
      (9, 30),
      "Lattice surgery with surface codes (dblp:conf/qec/Brandt19, 2019)",
      "Quantum error correction (5); Surface codes (4); Superconducting qubits (2)",
      QecVenue,
      QecInst
    ),
    profile(
      Recent,
      Carol,
      "Carol Chen",
      (15, 52),
      "Folding pathways by molecular dynamics (dblp:journals/cbj/Chen22, 2022)",
      Bio,
      BioVenue,
      BioInst
    ),
    profile(
      Recent,
      Dan,
      "Dan Dorsey",
      (7, 19),
      "Fast Byzantine agreement (dblp:conf/dss/Dorsey20, 2020)",
      Sys,
      SysVenue,
      SysInst
    ),
    profile(
      Early,
      Alice,
      "Alice Arden",
      (4, 6),
      "Register allocation by graph colouring (dblp:conf/plc/Arden05, 2005)",
      "Compiler optimization (4); Register allocation (3)",
      "Programming Languages Conference (3)",
      "Example Compiler Group (2)"
    ),
    community(QuantumCommunity, "Alice Arden (1); Bob Brandt (1)", Qec, QecVenue, QecInst),
    community(BiologyCommunity, "Carol Chen (1); Erin Ellis (1)", Bio, BioVenue, BioInst),
    community(SystemsCommunity, "Dan Dorsey (1); Frank Foster (1)", Sys, SysVenue, SysInst),
    topic(QuantumTopic, "Quantum error correction", QecInst, QecVenue),
    topic(FoldingTopic, "Protein folding", BioInst, BioVenue),
    topic(ConsensusTopic, "Byzantine consensus", SysInst, SysVenue)
  )

  def paper(workId: String, record: String, text: String): PaperAbstract =
    PaperAbstract(
      workId,
      Hashing.tagged(text),
      text,
      Provenance(SourceType.OPENALEX, record, Snapshot, RunId, "abstract-v1", Some("er-v1"))
    )

  val abstracts: Vector[PaperAbstract] = Vector(
    paper(
      SurfaceCodeWork,
      "W2000000001",
      "We present fast decoders for surface codes on superconducting qubits. Quantum error " +
        "correction thresholds improve under the new decoder."
    ),
    paper(
      FoldingWork,
      "W2000000002",
      "We simulate protein folding pathways with molecular dynamics and compare them with " +
        "structural biology measurements."
    )
  )

  final case class Loaded(stores: LocalStores, loader: VectorLoader, result: VectorLoadResult):
    def retriever(
        embedder: Embedder = new HashingEmbedder(cfg.vectors.dimension),
        objects: ObjectStore = stores.objects,
        budget: ContextBudget = ContextBudget.V1
    ): SemanticContextRetriever =
      SemanticContextRetriever(cfg, stores.vectors, objects, embedder, budget)
        .fold(e => sys.error(e), identity)

  /** Creates the five indexes and loads the rows through `VectorLoader.loadRendered` with rag's
    * renderer and the deterministic fake embedder.
    */
  def load(
      rows: Vector[EvidenceItem] = summaries,
      papers: Vector[PaperAbstract] = abstracts,
      stores: LocalStores = LocalStores.inMemory()
  ): Loaded =
    val run = for
      tables <- DynamoTables(stores.kv, cfg.dynamo)
      writer = new ArtifactWriter(stores.objects, new S3Layout(cfg.storage))
      loader <- VectorLoader(
        stores.vectors,
        new HashingEmbedder(cfg.vectors.dimension),
        writer,
        tables.runLedger,
        cfg.vectors
      )
      _ <- stores.vectors.createBucket().left.map(_.text)
      _ <- loader.specs.foldLeft[Either[String, Unit]](Right(()))((acc, s) =>
        acc.flatMap(_ => stores.vectors.createIndex(s).left.map(_.text).map(_ => ()))
      )
      src = RowSource.of[SourceRecord](
        rows.map(SourceRecord.Summary(_)) ++ papers.map(SourceRecord.Abstract(_))
      )
      result <- loader.loadRendered(RunId, src, renderer)
    yield Loaded(stores, loader, result)
    run.fold(e => sys.error(e), identity)

  /** The object store as seen through `inner`, with some URIs answering other bytes (tampering). */
  final class TamperedObjects(inner: ObjectStore, swap: Map[String, Array[Byte]])
      extends ObjectStore:
    val reads = scala.collection.mutable.ArrayBuffer.empty[String]
    def putIfAbsent(uri: String, bytes: Array[Byte], contentType: String) =
      inner.putIfAbsent(uri, bytes, contentType)
    def get(uri: String) =
      reads.synchronized(reads += uri)
      swap.get(uri).map(b => Right(b.clone())).getOrElse(inner.get(uri))
    def list(prefixUri: String) = inner.list(prefixUri)
