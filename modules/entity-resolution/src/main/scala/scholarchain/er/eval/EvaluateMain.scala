package scholarchain.er.eval

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scholarchain.core.*
import scholarchain.er.{ErOut, PolicyConfig}
import scholarchain.er.review.ReviewQueueMain

/** Gold-set evaluation of one decision run (task T3; never run before the human's labels exist).
  *
  * `sbt "entityResolution/runMain scholarchain.er.eval.EvaluateMain --gold <labels.csv> --decisions
  * <match-decisions/policy=er-v1/snapshot=.../run_id=...> --out <dir>"`
  *
  * Reads the labels and the four outcome folders, writes `er-evaluation.json` and
  * `er-evaluation.md` into `--out`, and prints the Markdown. It writes nothing under a human-only
  * folder and never changes the labels file.
  */
object EvaluateMain:
  final case class Args(gold: Path, decisions: Path, out: Path)

  def parseArgs(args: Vector[String]): Either[String, Args] =
    val names = Set("--gold", "--decisions", "--out")
    if args.size != 6 then Left("usage: --gold <csv> --decisions <decision run dir> --out <dir>")
    else
      val m = args.grouped(2).map(p => p(0) -> p(1)).toMap
      for
        _ <- Either.cond(
          m.keySet == names,
          (),
          s"arguments must be exactly ${names.mkString(", ")}"
        )
        out = Paths.get(m("--out")).toAbsolutePath.normalize
        _ <- Either.cond(
          !ReviewQueueMain.isGoldPath(out),
          (),
          "refusing to write under datasets/gold/"
        )
      yield Args(Paths.get(m("--gold")), Paths.get(m("--decisions")), out)

  private def lines(p: Path): Vector[String] =
    val raw = Files.newInputStream(p)
    val in = if p.getFileName.toString.endsWith(".gz") then new GZIPInputStream(raw) else raw
    Using.resource(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))(
      _.lines.iterator.asScala.filter(_.nonEmpty).toVector
    )

  /** Decisions of every outcome folder of one run folder. */
  def readDecisions(dir: Path): Either[String, Vector[MatchDecision]] =
    val files = ErOut.Outcomes.flatMap { o =>
      val d = dir.resolve(o)
      if !Files.isDirectory(d) then Vector.empty
      else
        Using.resource(Files.list(d))(
          _.iterator.asScala
            .filter(_.getFileName.toString.startsWith("part-"))
            .toVector
            .sortBy(_.toString)
        )
    }
    val decoded = files.flatMap(f =>
      lines(f).zipWithIndex.map((l, i) =>
        JsonLines
          .decodeValid[MatchDecision](l, Validate.decision)
          .left
          .map(e => s"$f line ${i + 1}: $e")
      )
    )
    if !Files.exists(dir.resolve("_SUCCESS")) then Left(s"$dir has no _SUCCESS")
    else decoded.collectFirst { case Left(e) => e }.toLeft(decoded.collect { case Right(d) => d })

  private def writeAtomically(target: Path, text: String): Unit =
    Files.createDirectories(target.getParent)
    val tmp = Files.createTempFile(target.getParent, ".tmp-", ".part")
    Files.writeString(tmp, text, StandardCharsets.UTF_8)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

  /** The thresholds and the policy mark of the report come from the policy this module loads, so a
    * decision run made under any other policy version is refused rather than mislabeled.
    */
  def samePolicy(decisions: Vector[MatchDecision], loaded: String): Either[String, Unit] =
    val found = decisions
      .flatMap(d => d.policyVersion +: d.provenance.policyVersion.toVector)
      .distinct
      .sorted
    Either.cond(
      found.forall(_ == loaded),
      (),
      s"decision run is policy ${found.filterNot(_ == loaded).mkString(", ")}, " +
        s"but this evaluation loads policy $loaded"
    )

  def run(args: Vector[String]): Either[String, EvaluationReport] =
    for
      a <- parseArgs(args)
      cfg <- EvalConfig.load()
      policy <- PolicyConfig.load()
      rows <- GoldCsv.parse(Files.readString(a.gold, StandardCharsets.UTF_8))
      decisions <- readDecisions(a.decisions)
      _ <- samePolicy(decisions, policy.policyVersion)
      report <- Evaluation.evaluate(rows, decisions, cfg, policy.thresholds)
    yield
      writeAtomically(
        a.out.resolve("er-evaluation.json"),
        upickle.default.write(report, indent = 2) + "\n"
      )
      writeAtomically(a.out.resolve("er-evaluation.md"), Evaluation.markdown(report))
      report

  def main(args: Array[String]): Unit =
    run(args.toVector) match
      case Right(r) => print(Evaluation.markdown(r))
      case Left(e)  =>
        System.err.println(s"evaluation error: $e")
        throw new IllegalStateException(e)
