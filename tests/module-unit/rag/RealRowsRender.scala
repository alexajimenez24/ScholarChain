package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.{PaperAbstract, RenderedDocument}
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Test-scope runner, not a test: renders every row of a Spark run's JSON-lines export read-only
  * and prints counts. Argument: the run's `jsonl` directory.
  */
object RealRowsRender:
  private final class Tally:
    var rows, rendered, multi, chunks, maxChunk, maxChunks, oneChunkKeyKept = 0L
    val rejects = mutable.Map.empty[String, Long].withDefaultValue(0L)

  def main(args: Array[String]): Unit =
    val base = Paths.get(args(0))
    val renderer = SemanticRenderer(RenderFixtures.vectors).fold(e => sys.error(e), identity)
    val tallies = mutable.TreeMap.empty[String, Tally]
    val identities = mutable.HashMap.empty[String, String] // identity -> text hash
    val keys = mutable.HashMap.empty[String, String] // chunk ID -> chunk text hash
    var identityConflicts, keyConflicts, nondeterministic = 0L
    val samples = mutable.ArrayBuffer.empty[String]

    def files(dir: Path): Vector[Path] =
      Files.walk(dir).iterator.asScala.filter(_.toString.endsWith(".json")).toVector.sorted

    def record(
        t: Tally,
        r: Either[String, RenderedDocument],
        again: Either[String, RenderedDocument],
        exportChunk: Option[String]
    ): Unit =
      t.rows += 1
      if r != again then nondeterministic += 1
      r match
        case Left(why) => t.rejects(why.take(90)) += 1
        case Right(d)  =>
          t.rendered += 1
          t.chunks += d.chunks.size
          if d.chunks.size > 1 then t.multi += 1
          else if exportChunk.contains(d.chunks.head.chunkId) then t.oneChunkKeyKept += 1
          t.maxChunks = math.max(t.maxChunks, d.chunks.size.toLong)
          d.chunks.foreach(c => t.maxChunk = math.max(t.maxChunk, c.text.length.toLong))
          val id = s"${d.docType}|${d.entityId}|${d.window.map(_.label).getOrElse("")}"
          if identities.getOrElseUpdate(id, d.textHash) != d.textHash then identityConflicts += 1
          d.chunks.foreach(c =>
            if keys.getOrElseUpdate(c.chunkId, c.textHash) != c.textHash then keyConflicts += 1
          )
          if samples.size < 5 && (d.chunks.size > 1 || !samples.exists(_.startsWith(d.docType)))
          then
            samples += s"${d.docType} ${d.entityId} ${d.window.map(_.label).getOrElse("-")} " +
              s"${d.template} chunks=${d.chunks.map(c => s"${c.chunkId}[${c.start},${c.end})").mkString(",")}"

    for f <- files(base.resolve("semantic-summaries")) do
      Files.lines(f).iterator.asScala.filter(_.trim.nonEmpty).foreach { line =>
        RenderFixtures.decodeRow[EvidenceItem](line, RenderFixtures.SummaryExtras) match
          case Left(e)  => tallies.getOrElseUpdate("undecodable", Tally()).rejects(e.take(90)) += 1
          case Right(e) =>
            val t = tallies.getOrElseUpdate(e.values.getOrElse("docType", "?"), Tally())
            record(t, renderer.renderSummary(e), renderer.renderSummary(e), e.chunkId)
      }
    for f <- files(base.resolve("paper-abstracts")) do
      Files.lines(f).iterator.asScala.filter(_.trim.nonEmpty).foreach { line =>
        RenderFixtures.decodeRow[PaperAbstract](line, RenderFixtures.AbstractExtras) match
          case Left(e)  => tallies.getOrElseUpdate("undecodable", Tally()).rejects(e.take(90)) += 1
          case Right(a) =>
            val t = tallies.getOrElseUpdate("paper-abstract", Tally())
            record(t, renderer.renderAbstract(a), renderer.renderAbstract(a), None)
      }

    for (k, t) <- tallies do
      println(
        s"$k: rows=${t.rows} rendered=${t.rendered} rejected=${t.rejects.values.sum} " +
          s"multiChunkDocs=${t.multi} chunks=${t.chunks} maxChunksPerDoc=${t.maxChunks} " +
          s"maxChunkChars=${t.maxChunk} oneChunkKeptExportChunkId=${t.oneChunkKeyKept}"
      )
      t.rejects.toVector.sortBy(-_._2).take(5).foreach((r, n) => println(s"  reject $n: $r"))
    println(
      s"total documents=${tallies.values.map(_.rendered).sum} chunks=${tallies.values.map(_.chunks).sum} " +
        s"distinctChunkIds=${keys.size} identityConflicts=$identityConflicts " +
        s"chunkKeyConflicts=$keyConflicts nondeterministic=$nondeterministic"
    )
    samples.foreach(s => println(s"  sample: $s"))
