package scholarchain.er.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import scholarchain.core.*
import scholarchain.er.ErCandidate
import upickle.default.*

/** Builds the supplementary review queue (human decision 01, phase 05) from the same upstream run
  * as the first queue, and writes the selected pairs as a match-candidate-v1 run so the unchanged
  * er-v1 job can decide every one of them.
  *
  * `sbt "entityResolution/runMain scholarchain.er.review.ReviewSupplementMain --run-root <hadoop
  * out> --review-v1 <first queue csv> --out <csv> --candidates-out <dir> [--run-id <id>]
  * [--overwrite true]"`
  *
  * The first queue is only read (its pair IDs, to exclude them) and its SHA-256 is checked before
  * and after. Selection reads source records and the upstream blocking structure only: no er-v1
  * score, outcome or decision. Criterion names and counts go to stdout only, never to the CSV or
  * the sidecar, which the labeler may open. It never reads or writes `datasets/gold/`.
  */
object ReviewSupplementMain:
  final case class Args(
      runRoot: Path,
      reviewV1: Path,
      out: Path,
      candidatesOut: Path,
      runId: Option[String],
      overwrite: Boolean
  )

  private val Names =
    Set("--run-root", "--review-v1", "--out", "--candidates-out", "--run-id", "--overwrite")

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
          def path(n: String) = m.get(n).toRight(s"$n is required").map(abs)
          for
            root <- path("--run-root")
            v1 <- path("--review-v1")
            out <- path("--out")
            cands <- path("--candidates-out")
            overwrite <- m.getOrElse("--overwrite", "false") match
              case "true"  => Right(true)
              case "false" => Right(false)
              case other   => Left(s"--overwrite must be true or false, got $other")
          yield Args(root, v1, out, cands, m.get("--run-id"), overwrite)
        }

  private def abs(s: String): Path = Paths.get(s).toAbsolutePath.normalize

  final case class SupplementProvenance(
      queueVersion: String,
      generatorVersion: String,
      seed: String,
      configResource: String,
      configSha256: String,
      sideConfigResource: String,
      runId: String,
      runRoot: String,
      candidateFiles: Vector[String],
      exportFiles: Vector[String],
      reviewV1: String,
      reviewV1Sha256: String,
      reviewV1Rows: Int,
      decisionInputRoot: String,
      decisionInputRunId: String,
      rows: Int,
      rowsByKind: Map[String, Int],
      csvSha256: String
  ) derives ReadWriter

  private def sha(p: Path): String = Hashing.sha256Hex(Files.readAllBytes(p))

  private def checks(a: Args): Either[String, Unit] =
    val paths = Vector(a.runRoot, a.reviewV1, a.out, a.candidatesOut)
    for
      _ <- paths
        .find(ReviewQueueMain.isGoldPath)
        .map(p => s"refusing a path under datasets/gold/ (human-only): $p")
        .toLeft(())
      _ <- Either.cond(
        a.out != a.reviewV1 && ReviewQueueMain.sidecarOf(a.out) != a.reviewV1 &&
          a.out != ReviewQueueMain.sidecarOf(a.reviewV1),
        (),
        "--out must not be the first review queue or its sidecar"
      )
      _ <- Either.cond(Files.isRegularFile(a.reviewV1), (), s"${a.reviewV1} is not a file")
      _ <- Either.cond(
        !a.candidatesOut.startsWith(a.runRoot) && !a.runRoot.startsWith(a.candidatesOut) &&
          !a.reviewV1.startsWith(a.candidatesOut),
        (),
        "--candidates-out must be a separate folder from --run-root and the first queue"
      )
      _ <- Either.cond(
        a.overwrite || !Files.exists(a.out),
        (),
        s"${a.out} exists; pass --overwrite true to replace it"
      )
      _ <- Either.cond(
        a.overwrite || !Files.exists(a.candidatesOut),
        (),
        s"${a.candidatesOut} exists; pass --overwrite true to write into it"
      )
    yield ()

  private def decodeCandidates(files: Vector[Path]): Either[String, Vector[(Path, ErCandidate)]] =
    val decoded = files.flatMap(f =>
      ReviewQueueMain.lines(f).zipWithIndex.map((l, i) => (f, i, ErCandidate.decode(l)))
    )
    decoded
      .collectFirst { case (f, i, Left(e)) => s"$f line ${i + 1}: $e" }
      .toLeft(decoded.collect { case (f, _, Right(c)) => (f, c) })

  /** True for a file of the upstream `match-candidates/kind=blocked` stage (J04 blocks). */
  private def isBlocked(p: Path): Boolean =
    val parts = Vector.tabulate(p.getNameCount)(i => p.getName(i).toString)
    parts.contains("kind=blocked")

  private def manifestField(root: Path, runId: String, field: String): Either[String, String] =
    val p = root.resolve("run-manifests").resolve(s"$runId.json")
    scala.util
      .Try(ujson.read(Files.readString(p, StandardCharsets.UTF_8)).obj)
      .toEither
      .left
      .map(_ => s"run manifest $p is missing or not JSON")
      .map(_.get(field).collect { case ujson.Str(s) => s }.getOrElse(""))

  def run(args: Vector[String]): Either[String, Vector[String]] =
    for
      a <- parseArgs(args)
      _ <- checks(a)
      cfg <- SupplementConfig.load()
      cfgText <- SupplementConfig.resourceText()
      queueCfg <- ReviewQueueConfig.load()
      v1Before = sha(a.reviewV1)
      v1Ids <- ReviewSupplement
        .pairIds(Files.readString(a.reviewV1, StandardCharsets.UTF_8))
        .left
        .map(e => s"${a.reviewV1}: $e")
      in <- ReviewQueueMain.readRun(
        ReviewQueueMain.Args(a.runRoot, a.out, a.runId, a.overwrite)
      )
      upstream <- decodeCandidates(in.candidateFiles)
      snapshot <- upstream.map(_._2.provenance.snapshot).distinct match
        case Vector(s) => Right(s)
        case other     => Left(s"upstream candidates have ${other.size} snapshots, expected 1")
      upPipeline <- manifestField(a.runRoot, in.runId, "pipeline")
      upNormalization <- manifestField(a.runRoot, in.runId, "normalization")
      blocked = upstream.collect { case (f, c) if isBlocked(f) => c }
      blocks = ReviewSupplement.blocks(ReviewSupplement.links(blocked, cfg.clusterLinkFeature))
      ctx = MentionContext.index(in.index)
      all <- ReviewSupplement.qualifying(blocks, ctx, cfg)
      upstreamIds = upstream.map(_._2.candidateId).toSet
      sel = ReviewSupplement.select(all, v1Ids.toSet, upstreamIds, cfg)
      _ <- sel.chosen
        .find(p => v1Ids.contains(p.pairId))
        .map(p => s"pair ${p.pairId} is in the first review queue")
        .toLeft(())
      rows <- ReviewSupplement.rows(sel.chosen, in.index, queueCfg, in.runId)
      worksFiles = in.exportFiles.filter(_.getParent.getFileName.toString == "works")
      csvText = ReviewQueue.csv(rows)
      manifest = ujson
        .Obj(
          "stage" -> "er-review-supplement",
          "pipeline" -> cfg.generatorVersion,
          "queueVersion" -> cfg.queueVersion,
          "seed" -> cfg.seed,
          "configSha256" -> Hashing.sha256Hex(cfgText),
          "upstreamRunId" -> in.runId,
          "upstreamPipeline" -> upPipeline,
          "normalization" -> upNormalization,
          "snapshot" -> snapshot,
          "reviewV1Sha256" -> v1Before,
          "worksSha256" -> ujson.Arr.from(worksFiles.map(sha)),
          "pairIdsSha256" -> Hashing.sha256Hex(sel.chosen.map(_.pairId).sorted.mkString("\n")),
          "pairs" -> sel.chosen.size
        )
        .render()
      supplementRunId = Ids.run(manifest)
      candidates = sel.chosen.map(ReviewSupplement.candidate(_, snapshot, supplementRunId, cfg))
      _ <- candidates
        .map(c => ErCandidate.validate(c))
        .find(_.nonEmpty)
        .map(e => s"invalid supplementary candidate: ${e.mkString("; ")}")
        .toLeft(())
    yield
      val runDir = Paths.get(s"snapshot=$snapshot", s"run_id=$supplementRunId")
      val root = a.candidatesOut
      val blockedDir = root.resolve("match-candidates/kind=blocked").resolve(runDir)
      val exactDir = root.resolve("match-candidates/kind=exact").resolve(runDir)
      val exportDir = root.resolve("export").resolve(runDir)
      ReviewQueueMain.writeAtomically(
        blockedDir.resolve("part-r-00000"),
        candidates.map(c => JsonLines.encode(c) + "\n").mkString
      )
      ReviewQueueMain.writeAtomically(exactDir.resolve("part-r-00000"), "")
      Files.createDirectories(exportDir.resolve("works"))
      worksFiles.foreach(f =>
        Files.copy(
          f,
          exportDir.resolve("works").resolve(f.getFileName.toString),
          StandardCopyOption.REPLACE_EXISTING
        )
      )
      Vector(blockedDir, exactDir, exportDir).foreach(d =>
        ReviewQueueMain.writeAtomically(d.resolve("_SUCCESS"), "")
      )
      ReviewQueueMain.writeAtomically(
        root.resolve("run-manifests").resolve(s"$supplementRunId.json"),
        manifest
      )

      val prov = SupplementProvenance(
        cfg.queueVersion,
        cfg.generatorVersion,
        cfg.seed,
        SupplementConfig.DefaultResource,
        Hashing.sha256Hex(cfgText),
        ReviewQueueConfig.DefaultResource,
        in.runId,
        a.runRoot.toString,
        in.candidateFiles.map(f => a.runRoot.relativize(f).toString),
        in.exportFiles.map(f => a.runRoot.relativize(f).toString),
        a.reviewV1.toString,
        v1Before,
        v1Ids.size,
        root.toString,
        supplementRunId,
        rows.size,
        rows.groupBy(_.kind.toString.toLowerCase).view.mapValues(_.size).toMap,
        Hashing.sha256Hex(csvText)
      )
      ReviewQueueMain.writeAtomically(a.out, csvText)
      ReviewQueueMain.writeAtomically(
        ReviewQueueMain.sidecarOf(a.out),
        write(prov, indent = 2) + "\n"
      )
      val v1After = sha(a.reviewV1)
      if v1After != v1Before then
        throw new IllegalStateException(
          s"${a.reviewV1} changed during the run: $v1Before -> $v1After"
        )

      val sameBlockPairs = blocks.map(b => b.members.size * (b.members.size - 1) / 2).sum
      def table(
          title: String,
          avail: Map[SupplementCriterion, Int],
          sel: Map[SupplementCriterion, Int]
      ) =
        f"$title%-42s ${"eligible"}%9s ${"selected"}%9s" +: cfg.criteria.map(c =>
          f"${c.toString}%-42s ${avail(c)}%9d ${sel(c)}%9d"
        )
      val sourcePairs = sel.chosen
        .map(p => Vector(ctx(p.sourceId).source, ctx(p.targetId).source).map(_.toString).sorted)
        .groupBy(_.mkString("-"))
        .toVector
        .sortBy(_._1)
        .map((k, v) => s"$k ${v.size}")
      Vector(
        s"run ${in.runId}: ${in.candidates.size} candidate lines, ${upstreamIds.size} distinct pairs, " +
          s"${blocks.size} researcher blocks, ${blocks.map(_.members.size).sum} block members",
        s"review v1 ${a.reviewV1}: ${v1Ids.size} pair ids, sha256 $v1Before",
        s"supplement ${cfg.queueVersion}, generator ${cfg.generatorVersion}, seed ${cfg.seed}, " +
          s"maxRows ${cfg.maxRows}",
        s"same-block researcher pairs $sameBlockPairs; qualifying ${sel.qualifying} " +
          s"(in review v1 ${sel.inReviewV1}, other upstream candidates ${sel.upstreamCandidates}, " +
          s"eligible ${sel.eligible})",
        s"same-block work pairs not emitted upstream ${ReviewSupplement.workPairsNotEmitted(upstream.map(_._2))} " +
          "(the criteria are researcher criteria; no work pair is selected)"
      ) ++ table("criterion (first that holds)", sel.availablePrimary, sel.selectedPrimary) ++
        table("criterion (any that holds, overlapping)", sel.availableAny, sel.selectedAny) ++
        Vector(
          s"selected rows: ${rows.size} (researcher ${rows.count(_.kind == EntityKind.RESEARCHER)}, " +
            s"work ${rows.count(_.kind == EntityKind.WORK)}), blocks ${sel.chosen.map(_.blockKey).distinct.size}",
          s"selected source pairs: ${sourcePairs.mkString(", ")}",
          s"wrote ${rows.size} rows to ${a.out}",
          s"wrote provenance to ${ReviewQueueMain.sidecarOf(a.out)} (csv sha256 ${prov.csvSha256})",
          s"wrote supplementary candidates run $supplementRunId (${candidates.size} lines) to $root",
          s"review v1 sha256 unchanged: $v1After"
        )

  def main(args: Array[String]): Unit =
    run(args.toVector) match
      case Right(out) => out.foreach(println)
      case Left(err)  =>
        System.err.println(s"review supplement error: $err")
        throw new IllegalStateException(err)
