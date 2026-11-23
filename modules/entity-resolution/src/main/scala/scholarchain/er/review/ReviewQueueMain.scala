package scholarchain.er.review

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scholarchain.core.*
import upickle.default.*

/** Builds the human review queue from one local Phase 04 pipeline run.
  *
  * `sbt "entityResolution/runMain scholarchain.er.review.ReviewQueueMain --run-root <dir> --out
  * <file.csv> [--run-id <id>] [--overwrite true]"`
  *
  * Reads `match-candidates/kind={exact,blocked}` and `export/{works,venues,institutions}` of the
  * run, writes the CSV and a provenance sidecar `<file>.provenance.json`, and prints the row counts
  * by kind and stratum to stdout (the strata never reach either file). It never reads or writes
  * `datasets/gold/`.
  */
object ReviewQueueMain:
  final case class Args(runRoot: Path, out: Path, runId: Option[String], overwrite: Boolean)

  private val Names = Set("--run-root", "--out", "--run-id", "--overwrite")

  def parseArgs(args: Vector[String]): Either[String, Args] =
    if args.size % 2 != 0 then Left("arguments come as --name value pairs")
    else
      val pairs = args.grouped(2).map(p => p(0) -> p(1)).toVector
      pairs
        .collectFirst {
          case (n, _) if !Names(n)                  => s"unknown argument $n"
          case (n, v) if v.startsWith("--")         => s"$n has no value"
          case (n, _) if pairs.count(_._1 == n) > 1 => s"repeated argument $n"
        }
        .toLeft(pairs.toMap)
        .flatMap { m =>
          for
            root <- m.get("--run-root").toRight("--run-root is required")
            out <- m.get("--out").toRight("--out is required")
            overwrite <- m.getOrElse("--overwrite", "false") match
              case "true"  => Right(true)
              case "false" => Right(false)
              case other   => Left(s"--overwrite must be true or false, got $other")
          yield Args(
            Paths.get(root).toAbsolutePath.normalize,
            Paths.get(out).toAbsolutePath.normalize,
            m.get("--run-id"),
            overwrite
          )
        }

  /** True when the path lies inside a `datasets/gold` folder (human-only territory). */
  def isGoldPath(p: Path): Boolean =
    val parts = p.toAbsolutePath.normalize.iterator.asScala.map(_.toString).toVector
    parts.sliding(2).exists(_ == Vector("datasets", "gold"))

  private[review] def children(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else Using.resource(Files.list(dir))(_.iterator.asScala.toVector.sortBy(_.toString))

  /** The `run_id=<runId>` folders under every `snapshot=` folder of `base`; each must hold
    * `_SUCCESS`.
    */
  private def runDirs(base: Path, runId: String): Either[String, Vector[Path]] =
    val dirs = children(base)
      .filter(_.getFileName.toString.startsWith("snapshot="))
      .map(_.resolve(s"run_id=$runId"))
      .filter(Files.isDirectory(_))
    if dirs.isEmpty then Left(s"no run_id=$runId folder under $base")
    else
      dirs
        .find(d => !Files.exists(d.resolve("_SUCCESS")))
        .map(d => s"$d has no _SUCCESS")
        .toLeft(dirs)

  private def parts(dir: Path): Vector[Path] =
    children(dir).filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part-"))

  private[review] def lines(p: Path): Vector[String] =
    val raw = Files.newInputStream(p)
    val in = if p.getFileName.toString.endsWith(".gz") then new GZIPInputStream(raw) else raw
    Using.resource(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))(
      _.lines.iterator.asScala.filter(_.nonEmpty).toVector
    )

  private def decodeAll[T: Reader: Writer](files: Vector[Path]): Either[String, Vector[T]] =
    val decoded =
      files.flatMap(f => lines(f).zipWithIndex.map((l, i) => (f, i, JsonLines.decode[T](l))))
    decoded
      .collectFirst { case (f, i, Left(e)) => s"$f line ${i + 1}: $e" }
      .toLeft(decoded.collect { case (_, _, Right(v)) => v })

  private def uniqueBy[T](xs: Vector[T], what: String)(
      id: T => String
  ): Either[String, Map[String, T]] =
    val g = xs.distinct.groupBy(id)
    g.collectFirst { case (k, vs) if vs.size > 1 => s"$what $k has differing copies" }
      .toLeft(g.view.mapValues(_.head).toMap)

  private def resolveRunId(a: Args): Either[String, String] =
    a.runId.map(Right(_)).getOrElse {
      val ids = children(a.runRoot.resolve("run-manifests"))
        .map(_.getFileName.toString)
        .filter(n => n.endsWith(".json") && !n.endsWith("-summary.json"))
        .map(_.stripSuffix(".json"))
      ids match
        case Vector(one) => Right(one)
        case other       =>
          Left(s"expected one run manifest under ${a.runRoot}/run-manifests, found ${other.size}")
    }

  final case class Inputs(
      runId: String,
      candidateFiles: Vector[Path],
      exportFiles: Vector[Path],
      candidates: Vector[CandidateView],
      index: RecordIndex
  )

  def readRun(a: Args): Either[String, Inputs] =
    for
      runId <- resolveRunId(a)
      _ <- Either.cond(Ids.isRunId(runId), (), s"$runId is not a run ID")
      exactDirs <- runDirs(a.runRoot.resolve("match-candidates").resolve("kind=exact"), runId)
      blockedDirs <- runDirs(a.runRoot.resolve("match-candidates").resolve("kind=blocked"), runId)
      exportDirs <- runDirs(a.runRoot.resolve("export"), runId)
      candidateFiles = (exactDirs ++ blockedDirs).flatMap(parts)
      _ <- Either.cond(candidateFiles.nonEmpty, (), "no candidate part files")
      candLines = candidateFiles.flatMap(f => lines(f).zipWithIndex.map((l, i) => (f, i, l)))
      candidates <- {
        val parsed =
          candLines.map((f, i, l) => CandidateView.parse(l).left.map(e => s"$f line ${i + 1}: $e"))
        parsed.collectFirst { case Left(e) => e }.toLeft(parsed.collect { case Right(c) => c })
      }
      _ <- candidates
        .find(_.runId != runId)
        .map(c => s"candidate ${c.candidateId} has run ${c.runId}, expected $runId")
        .toLeft(())
      workFiles = exportDirs.flatMap(d => parts(d.resolve("works")))
      venueFiles = exportDirs.flatMap(d => parts(d.resolve("venues")))
      instFiles = exportDirs.flatMap(d => parts(d.resolve("institutions")))
      works <- decodeAll[WorkRecord](workFiles).flatMap(uniqueBy(_, "work")(_.workId))
      venues <- decodeAll[Venue](venueFiles).flatMap(uniqueBy(_, "venue")(_.venueId))
      insts <- decodeAll[Institution](instFiles).flatMap(
        uniqueBy(_, "institution")(_.institutionId)
      )
    yield Inputs(
      runId,
      candidateFiles,
      workFiles ++ venueFiles ++ instFiles,
      candidates,
      RecordIndex(works, venues, insts)
    )

  final case class QueueProvenance(
      queueVersion: String,
      strataVersion: String,
      seed: String,
      configResource: String,
      runId: String,
      runRoot: String,
      candidateFiles: Vector[String],
      exportFiles: Vector[String],
      candidateLines: Int,
      distinctCandidates: Int,
      rows: Int,
      rowsByKind: Map[String, Int],
      csvSha256: String
  ) derives ReadWriter

  private[review] def writeAtomically(target: Path, text: String): Unit =
    Files.createDirectories(target.getParent)
    val tmp = Files.createTempFile(target.getParent, ".tmp-", ".part")
    Files.writeString(tmp, text, StandardCharsets.UTF_8)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

  def sidecarOf(out: Path): Path = out.resolveSibling(out.getFileName.toString + ".provenance.json")

  /** Count table printed to stdout: kind x stratum, available and selected. */
  def countTable(sel: ReviewQueue.Selection): Vector[String] =
    val header = "kind        stratum           available  selected"
    val body = ReviewQueue.Cells.map { cell =>
      val (k, s) = cell
      f"${k.toString.toLowerCase}%-11s ${s.toString}%-17s ${sel.available(cell)}%9d ${sel.selected(cell)}%9d"
    }
    val total =
      f"${"total"}%-11s ${""}%-17s ${sel.available.values.sum}%9d ${sel.selected.values.sum}%9d"
    header +: body :+ total

  def run(args: Vector[String]): Either[String, Vector[String]] =
    for
      a <- parseArgs(args)
      _ <- Either.cond(
        !isGoldPath(a.out),
        (),
        "refusing to write under datasets/gold/ (human-only)"
      )
      _ <- Either.cond(!isGoldPath(a.runRoot), (), "refusing to read under datasets/gold/")
      _ <- Either.cond(
        a.overwrite || !Files.exists(a.out),
        (),
        s"${a.out} exists; pass --overwrite true to replace it"
      )
      cfg <- ReviewQueueConfig.load()
      in <- readRun(a)
      built <- ReviewQueue.build(in.candidates, in.index, cfg)
    yield
      val merged = in.candidates.map(_.candidateId).distinct.size
      val prov = QueueProvenance(
        cfg.queueVersion,
        cfg.strataVersion,
        cfg.seed,
        ReviewQueueConfig.DefaultResource,
        built.runId,
        a.runRoot.toString,
        in.candidateFiles.map(f => a.runRoot.relativize(f).toString),
        in.exportFiles.map(f => a.runRoot.relativize(f).toString),
        in.candidates.size,
        merged,
        built.rows.size,
        built.rows.groupBy(_.kind.toString.toLowerCase).view.mapValues(_.size).toMap,
        Hashing.sha256Hex(built.csv)
      )
      writeAtomically(a.out, built.csv)
      writeAtomically(sidecarOf(a.out), write(prov, indent = 2) + "\n")
      Vector(
        s"run ${built.runId}: ${in.candidates.size} candidate lines, $merged distinct pairs",
        s"queue ${cfg.queueVersion}, strata ${cfg.strataVersion}, seed ${cfg.seed}"
      ) ++ countTable(built.selection) ++ Vector(
        s"wrote ${built.rows.size} rows to ${a.out}",
        s"wrote provenance to ${sidecarOf(a.out)} (csv sha256 ${prov.csvSha256})"
      )

  def main(args: Array[String]): Unit =
    run(args.toVector) match
      case Right(out) => out.foreach(println)
      case Left(err)  =>
        System.err.println(s"review queue error: $err")
        throw new IllegalStateException(err)
