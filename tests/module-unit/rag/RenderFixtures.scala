package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.PaperAbstract
import upickle.default.ReadWriter

/** Fixtures shaped like the Spark JSON-lines export (`jsonl/semantic-summaries`,
  * `jsonl/paper-abstracts`). The rows in `resources/render/` were written, and their hashes, chunk
  * IDs and chunk spans computed, by an independent Python script, not by the code under test.
  */
object RenderFixtures:
  /** Extra columns of the export rows, dropped to get the core record (load manifest). */
  val SummaryExtras = Seq("docType", "windowLabel")
  val AbstractExtras = Seq("sourceWorkId", "upstreamRunId")

  def decodeRow[A: ReadWriter](line: String, extras: Seq[String]): Either[String, A] =
    scala.util
      .Try(ujson.read(line))
      .toEither
      .left
      .map(_.getMessage)
      .flatMap { j =>
        extras.foreach(k => j.obj.remove(k))
        JsonLines.decode[A](ujson.write(j))
      }

  def lines(name: String): Vector[String] =
    val in = getClass.getResourceAsStream(s"/render/$name")
    try scala.io.Source.fromInputStream(in, "UTF-8").getLines().filter(_.trim.nonEmpty).toVector
    finally in.close()

  lazy val summaries: Vector[EvidenceItem] =
    lines("summaries.jsonl").map(l => decodeRow[EvidenceItem](l, SummaryExtras).toOption.get)
  lazy val abstracts: Vector[PaperAbstract] =
    lines("abstracts.jsonl").map(l => decodeRow[PaperAbstract](l, AbstractExtras).toOption.get)

  def summary(docType: String, window: String): EvidenceItem =
    summaries.find(e => e.values("docType") == docType && e.values("window") == window).get

  val vectors: VectorConfig = VectorConfig(
    "sc-vectors",
    Map(
      "researchers" -> "researcher-profile-v1",
      "communities" -> "collaboration-community-v1",
      "abstracts" -> "paper-abstract-v1",
      "paths" -> "collaboration-path-v1",
      "topicInstitutions" -> "topic-institution-summary-v1"
    ),
    256
  )

  lazy val renderer: SemanticRenderer = SemanticRenderer(vectors).toOption.get

  /** Re-hashes a changed summary text the way Spark would, so only the template check can fail. */
  def withText(e: EvidenceItem, text: String): EvidenceItem =
    val ent = e.values("entityId")
    val c = Ids.chunk(ent, e.provenance.policyVersion.get, e.values("docType"), 0, text)
    e.copy(
      text = Some(text),
      textHash = Some(Hashing.tagged(text)),
      chunkId = Some(c),
      evidenceId = Ids.evidence(EvidenceType.SEMANTIC_PROFILE, c)
    )
