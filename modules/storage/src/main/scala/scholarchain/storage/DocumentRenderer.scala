package scholarchain.storage

import scholarchain.core.{EvidenceItem, TimeWindow}
import upickle.default.ReadWriter

/** One embeddable chunk: the span `[start, end)` of its document's text, and its vector key. */
final case class RenderedChunk(
    chunkId: String,
    ordinal: Int,
    start: Int,
    end: Int,
    text: String,
    textHash: String
) derives ReadWriter

/** The one text of one document identity (`docType`, `entityId`, `window`), cut into chunks.
  * `textHash` and each chunk's `textHash` are `Hashing.tagged` digests (`sha256:` + hex) of the
  * text they sit beside.
  */
final case class RenderedDocument(
    docType: String,
    entityId: String,
    window: Option[TimeWindow],
    index: String,
    template: String,
    chunkPolicy: String,
    sourceVersion: String,
    evidenceId: Option[String],
    text: String,
    textHash: String,
    chunks: Vector[RenderedChunk]
) derives ReadWriter

/** A source row to render: a Spark `semantic-summaries` row or a `paper-abstracts` row. */
enum SourceRecord:
  case Summary(row: EvidenceItem)
  case Abstract(row: PaperAbstract)

  /** The identifier used for a rejection of this row (chunk or evidence ID, or work ID). */
  def id: String = this match
    case Summary(e)  => e.chunkId.getOrElse(e.evidenceId)
    case Abstract(a) => a.workId

/** Renders the text of every semantic document. Implemented in rag; storage never renders text. The
  * loader takes document text only from here: one `SemanticDocument` per `RenderedChunk`, and a
  * `Left` is a rejection of that source row (recorded with its reason, never repaired).
  */
trait DocumentRenderer:
  /** Template tag per document type, for the report (for example `tpl-paper-abstract-v1`). */
  def templateVersions: Map[String, String]
  def renderSummary(row: EvidenceItem): Either[String, RenderedDocument]
  def renderAbstract(row: PaperAbstract): Either[String, RenderedDocument]

  final def render(src: SourceRecord): Either[String, RenderedDocument] =
    scala.util
      .Try(src match
        case SourceRecord.Summary(e)  => renderSummary(e)
        case SourceRecord.Abstract(a) => renderAbstract(a))
      .toEither
      .left
      .map(e => s"renderer threw ${e.getClass.getSimpleName}: ${e.getMessage}".take(300))
      .flatMap(identity)
