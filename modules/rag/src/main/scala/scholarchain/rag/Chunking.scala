package scholarchain.rag

import scholarchain.core.{Hashing, Ids}

/** A versioned, deterministic chunking policy for rendered documents and abstracts.
  *
  * Lengths count UTF-16 code units (`String.length`), the unit of the embedder's character limit.
  *
  *   - A text of at most `maxChars` is one chunk: the whole text.
  *   - A longer text is cut into spans `[start, end)` of at most `maxChars`. The end is the last
  *     sentence or list boundary (a whitespace preceded by `.` or `;`) in `[start + minChars, start
  *     + maxChars]`; failing that the last whitespace there; failing that a hard cut at `start +
  *     maxChars`, moved back one unit so a surrogate pair is never split.
  *   - The next span starts at most `overlapChars` before the previous end: at the first word start
  *     in `[end - overlapChars, end]`, else at `end - overlapChars` (never inside a surrogate
  *     pair). Because `minChars > overlapChars`, every span advances by more than `minChars -
  *     overlapChars`.
  *   - Exactly `maxChunks` spans is accepted; a text that needs more is refused, never truncated.
  *
  * Every chunk is a contiguous span of the one text; nothing is added or removed.
  */
final case class ChunkPolicy(
    version: String,
    maxChars: Int,
    minChars: Int,
    overlapChars: Int,
    maxChunks: Int
):
  def validate: Either[String, ChunkPolicy] =
    if version.trim.isEmpty then Left("chunk policy without a version")
    else if maxChars < 2 then Left("maxChars must be at least 2")
    else if minChars < 1 || minChars > maxChars then Left("minChars must be in 1..maxChars")
    else if overlapChars < 0 || overlapChars >= minChars then
      Left("overlapChars must be in 0..minChars-1")
    else if maxChunks < 1 then Left("maxChunks must be at least 1")
    else Right(this)

object ChunkPolicy:
  /** chunk-v1: 2,000 units (about 500 tokens), cut no earlier than 1,000, 200 units of overlap, at
    * most 32 chunks; a text that needs a 33rd chunk is refused.
    */
  val V1: ChunkPolicy = ChunkPolicy("chunk-v1", 2000, 1000, 200, 32)

/** A chunk of a text: the code-unit span `[start, end)`. */
final case class Span(start: Int, end: Int)

object Chunking:
  private def ws(c: Char): Boolean = Character.isWhitespace(c)
  private val Terminators = Set('.', ';')

  /** The spans of `text` under `p`, or why the text is refused. */
  def spans(text: String, p: ChunkPolicy): Either[String, Vector[Span]] =
    p.validate.flatMap { _ =>
      if text.isEmpty then Left("empty text")
      else
        val n = text.length
        val out = Vector.newBuilder[Span]
        var start = 0
        var count = 0
        var done = false
        while !done && count < p.maxChunks do
          if n - start <= p.maxChars then
            out += Span(start, n)
            done = true
          else
            val end = cut(text, start, p)
            out += Span(start, end)
            start = nextStart(text, end, p)
          count += 1
        if !done then Left(s"text of $n chars needs more than ${p.maxChunks} chunks (${p.version})")
        else Right(out.result())
    }

  /** End of the span starting at `start`; the caller guarantees `text.length - start > maxChars`.
    */
  private def cut(text: String, start: Int, p: ChunkPolicy): Int =
    val lo = start + p.minChars
    val hi = start + p.maxChars
    def last(ok: Int => Boolean): Option[Int] = (hi to lo by -1).find(ok)
    last(e => ws(text(e)) && Terminators(text(e - 1)))
      .orElse(last(e => ws(text(e))))
      .getOrElse(
        if Character.isHighSurrogate(text(hi - 1)) && Character.isLowSurrogate(text(hi)) then hi - 1
        else hi
      )

  private def nextStart(text: String, end: Int, p: ChunkPolicy): Int =
    val s0 = end - p.overlapChars
    (s0 to end)
      .find(q => q > 0 && q < text.length && ws(text(q - 1)) && !ws(text(q)))
      .getOrElse(
        if s0 > 0 && Character.isLowSurrogate(text(s0)) && Character.isHighSurrogate(text(s0 - 1))
        then s0 + 1
        else s0
      )

  /** Chunk IDs (core `Ids.chunk`).
    *
    *   - One chunk: `Ids.chunk(entityId, sourceVersion, docType, 0, text)`, the formula Spark uses
    *     for its semantic-summary `chunkId`, so a one-chunk document keeps the export's chunk ID.
    *   - Several chunks: chunk `i` is `Ids.chunk(entityId, sourceVersion + "+" + policy version +
    *     "+" + sha256 hex of the whole text, docType, i, chunk text)`. Binding every chunk to its
    *     whole text keeps two documents of one entity (for example two windows) apart even when a
    *     span repeats word for word.
    */
  def chunkIds(
      entityId: String,
      sourceVersion: String,
      docType: String,
      text: String,
      spans: Vector[Span],
      p: ChunkPolicy
  ): Vector[String] =
    if spans == Vector(Span(0, text.length)) then
      Vector(Ids.chunk(entityId, sourceVersion, docType, 0, text))
    else
      val parent = s"$sourceVersion+${p.version}+${Hashing.sha256Hex(text)}"
      spans.zipWithIndex.map((s, i) =>
        Ids.chunk(entityId, parent, docType, i, text.substring(s.start, s.end))
      )
