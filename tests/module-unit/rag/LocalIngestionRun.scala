package scholarchain.rag

import scholarchain.storage.{IngestionReport, IngestionSettings, LocalIngestion}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Test-scope composition runner, not a test: the local ingestion of one Spark run through rag's
  * renderer. rag depends on storage, so this composition lives in rag's test scope; storage never
  * imports rag. In-memory stores and the deterministic fake embedder only; no AWS call.
  *
  * {{{
  * rag/Test/runMain scholarchain.rag.LocalIngestionRun <runManifest.json> <report.md> [--max-units-per-index N]
  * }}}
  * Both paths must be absolute (a forked JVM starts in `modules/rag`). The report is
  * `IngestionReport.toMarkdown`, printed to stdout and written to `<report.md>`; a path with a
  * `docs` directory element is refused, because `docs/` is not this runner's to write.
  */
object LocalIngestionRun:
  val Usage =
    "usage: LocalIngestionRun <absolute run manifest .json> <absolute report .md> " +
      "[--max-units-per-index N]"

  final case class Args(runManifest: Path, reportOut: Path, maxUnitsPerIndex: Option[Int])

  def parse(args: Seq[String]): Either[String, Args] =
    def path(s: String, what: String): Either[String, Path] =
      val p = Path.of(s)
      if !p.isAbsolute then Left(s"$what '$s' is not an absolute path")
      else Right(p.normalize)
    args match
      case Seq(m, r, rest*) =>
        for
          manifest <- path(m, "run manifest")
          report <- path(r, "report path")
          _ <- Either.cond(
            !report.iterator.asScala.exists(_.toString == "docs"),
            (),
            s"report path $report lies under a docs directory; write a scratch or target/ path"
          )
          _ <- Either.cond(!Files.isDirectory(report), (), s"report path $report is a directory")
          max <- rest match
            case Seq()                           => Right(None)
            case Seq("--max-units-per-index", n) =>
              n.toIntOption.filter(_ >= 1).map(Some(_)).toRight(s"'$n' is not a count >= 1")
            case other => Left(s"unexpected arguments: ${other.mkString(" ")}")
        yield Args(manifest, report, max)
      case _ => Left("expected a run manifest path and a report path")

  /** Runs the ingestion and writes the markdown report; returns the report and its markdown. */
  def execute(a: Args): Either[String, (IngestionReport, String)] =
    for
      cfg <- LocalIngestion.localConfig()
      renderer <- SemanticRenderer(cfg.vectors)
      report <- LocalIngestion.run(
        cfg,
        a.runManifest,
        renderer,
        IngestionSettings(maxUnitsPerIndex = a.maxUnitsPerIndex)
      )
      md = report.toMarkdown
      _ <- scala.util
        .Try {
          Option(a.reportOut.getParent).foreach(Files.createDirectories(_))
          Files.writeString(a.reportOut, md, UTF_8)
        }
        .toEither
        .left
        .map(e => s"cannot write ${a.reportOut}: ${e.getMessage}")
    yield (report, md)

  def main(args: Array[String]): Unit =
    parse(args.toSeq).flatMap(execute) match
      case Left(why)      => sys.error(s"LocalIngestionRun failed: $why\n$Usage")
      case Right((r, md)) =>
        println(md)
        println(
          s"LocalIngestionRun: report written to ${args(1)}; rejected first pass " +
            s"${r.first.rejected.size}, second pass ${r.second.rejected.size}; heap after the " +
            s"first pass ${r.heapUsedMbAfterFirstPass} MB of ${r.heapMaxMb} MB"
        )
