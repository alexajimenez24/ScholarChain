package scholarchain.adversarial.hadoop

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.ToolRunner

import scholarchain.core.*
import scholarchain.hadoop.Pipeline

/** Black-box driver for the Hadoop pipeline (Phase 4 gate cases, test-engineer).
  *
  * Written only from `modules/hadoop/MODULE.md` "Public API" and the spec. It uses:
  *   - the `Pipeline` Hadoop `Tool`, run with `ToolRunner` on a plain local Hadoop `Configuration`
  *     (local file system, LocalJobRunner), with the documented `--name value` arguments;
  *   - the documented output layout: `<output>/run-manifests/<runId>-summary.json`, whose `stages`
  *     map gives each stage's `output` folder and `counters`; data in `part-*` files (gzip or
  *     plain), named outputs in sub-folders, rejects in `rejects-v1/part-*`.
  *
  * Nothing here reads or depends on any module's main sources.
  */
object HadoopBlackBox:

  val Snapshot = "2026-11-22"

  /** One pipeline at a time in this JVM: sbt runs suites of one project in parallel, and the
    * machine limit is one LocalJobRunner pipeline at a time.
    */
  private object Lock

  /** Inputs for one run; side sources are JSON-lines text. */
  final case class Inputs(
      dblp: Array[Byte],
      openAlex: Option[String] = None,
      crossref: Option[String] = None,
      orcid: Option[String] = None
  )

  final case class Run(exit: Int, failure: Option[Throwable], output: Path, logText: String):

    def ok: Boolean = exit == 0 && failure.isEmpty

    lazy val summaryFile: Option[Path] =
      val dir = output.resolve("run-manifests")
      if !Files.isDirectory(dir) then None
      else
        Files
          .list(dir)
          .iterator()
          .asScala
          .filter(_.getFileName.toString.endsWith("-summary.json"))
          .toVector
          .sortBy(_.toString)
          .headOption

    lazy val summary: ujson.Value =
      summaryFile match
        case Some(f) => ujson.read(Files.readString(f, UTF_8))
        case None    => throw new AssertionError(s"no run summary under $output/run-manifests")

    def runId: String = summary("runId").str

    def stageNames: Vector[String] = summary("stages").obj.keys.toVector.sorted

    def hasStage(name: String): Boolean = summary("stages").obj.contains(name)

    def stageDir(name: String): Path =
      if !hasStage(name) then
        throw new AssertionError(s"stage $name not in summary; stages: ${stageNames.mkString(",")}")
      Path.of(stripScheme(summary("stages")(name)("output").str))

    /** Counters of one stage from the run summary (group `scholarchain`). */
    def counters(name: String): Map[String, Long] =
      if !hasStage(name) then Map.empty
      else
        summary("stages")(name).obj.get("counters") match
          case Some(o: ujson.Obj) =>
            o.value.collect { case (k, n: ujson.Num) => k -> n.value.toLong }.toMap
          case _ => Map.empty

    def counter(stage: String, name: String): Long = counters(stage).getOrElse(name, 0L)

    /** The sum of one counter over every stage of the run. */
    def total(name: String): Long = stageNames.map(counter(_, name)).sum

    /** `_counters.json` of a stage folder, parsed as counter name to value. */
    def countersFile(name: String): Map[String, Long] =
      val f = stageDir(name).resolve("_counters.json")
      if !Files.isRegularFile(f) then Map.empty
      else
        ujson
          .read(Files.readString(f, UTF_8))
          .obj
          .collect { case (k, n: ujson.Num) =>
            k -> n.value.toLong
          }
          .toMap

    /** Data lines of a stage: `part-*` files directly in the stage folder, or, with `sub`, every
      * `part-*` file under that named sub-folder.
      */
    def lines(stage: String, sub: Option[String] = None): Vector[String] =
      val base = stageDir(stage)
      val dir = sub.fold(base)(base.resolve)
      partLines(dir, recursive = sub.isDefined)

    def json(stage: String, sub: Option[String] = None): Vector[ujson.Value] =
      lines(stage, sub).map(l => ujson.read(l))

    def rejects(stage: String): Vector[ujson.Value] =
      if !hasStage(stage) then Vector.empty else json(stage, Some("rejects-v1"))

    def allRejects: Vector[(String, ujson.Value)] =
      stageNames.flatMap(s => rejects(s).map(s -> _))

    def works: Vector[ujson.Value] = json("J01")
    def normalized(source: String, kind: String): Vector[ujson.Value] =
      json(s"J02-$source", Some(kind))
    def teams: Vector[ujson.Value] = json("J05", Some("teams"))
    def authorPapers: Vector[ujson.Value] = json("J05", Some("authorpapers"))
    def pairs: Vector[ujson.Value] = json("J06")
    def exactCandidates: Vector[ujson.Value] = json("J03")
    def blockedCandidates: Vector[ujson.Value] = json("J04")
    def exported(kind: String): Vector[ujson.Value] = json("J07", Some(kind))

    /** Every text file the run wrote (data, rejects, counters, manifests), decompressed. */
    def everyOutputText: String =
      Files
        .walk(output)
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filter(p => !p.getFileName.toString.endsWith(".crc"))
        .filter(p => !p.toString.contains("_local-tmp"))
        .map(p => new String(readBytes(p), UTF_8))
        .mkString("\n")

    /** Total bytes of every file the run wrote. */
    def outputBytes: Long =
      Files.walk(output).iterator().asScala.filter(Files.isRegularFile(_)).map(Files.size).sum

  def stripScheme(p: String): String =
    if p.startsWith("file://") then p.stripPrefix("file://")
    else if p.startsWith("file:") then p.stripPrefix("file:")
    else p

  def readBytes(p: Path): Array[Byte] =
    val raw = Files.readAllBytes(p)
    if raw.length >= 2 && (raw(0) & 0xff) == 0x1f && (raw(1) & 0xff) == 0x8b then
      val out = new ByteArrayOutputStream()
      val in = new GZIPInputStream(new ByteArrayInputStream(raw))
      try in.transferTo(out)
      finally in.close()
      out.toByteArray
    else raw

  def partLines(dir: Path, recursive: Boolean): Vector[String] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val files =
        (if recursive then Files.walk(dir) else Files.list(dir))
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .filter(_.getFileName.toString.startsWith("part-"))
          .toVector
          .sortBy(_.toString)
      files.flatMap(p => new String(readBytes(p), UTF_8).split("\n").iterator.filter(_.nonEmpty))

  /** A plain local Hadoop configuration (standard Hadoop keys only). */
  def localConf(scratch: Path): Configuration =
    val c = new Configuration()
    c.set("fs.defaultFS", "file:///")
    c.set("mapreduce.framework.name", "local")
    c.set("hadoop.tmp.dir", scratch.resolve("tmp").toString)
    c.set("mapreduce.cluster.local.dir", scratch.resolve("local").toString)
    c.set("mapreduce.jobtracker.staging.root.dir", scratch.resolve("staging").toString)
    // Smaller sort buffer than the 100 MB default: memory limit of the test machine only.
    c.setInt("mapreduce.task.io.sort.mb", 16)
    c

  /** Writes the inputs under `dir/in` and runs the whole pipeline into `dir/<outName>`. The input
    * paths depend only on `dir`, so two runs with the same `dir` share a run ID (the output root is
    * not in the manifest).
    */
  def run(
      inputs: Inputs,
      dir: Path,
      outName: String = "out",
      extra: Seq[String] = Nil,
      conf: Map[String, String] = Map.empty
  ): Run = Lock.synchronized {
    val in = Files.createDirectories(dir.resolve("in"))
    val dblpPath = in.resolve("dblp.xml")
    Files.write(dblpPath, inputs.dblp)
    def side(name: String, body: Option[String]): Seq[String] =
      body.toSeq.flatMap { b =>
        val p = in.resolve(s"$name.jsonl")
        Files.writeString(p, b, UTF_8)
        Seq(s"--$name", p.toString)
      }
    val out = dir.resolve(outName)
    val args =
      Seq("--dblp", dblpPath.toString, "--output", out.toString, "--snapshot", Snapshot) ++
        side("openalex", inputs.openAlex) ++ side("crossref", inputs.crossref) ++
        side("orcid", inputs.orcid) ++ extra
    val hconf = localConf(dir.resolve(s"scratch-$outName"))
    conf.foreach((k, v) => hconf.set(k, v))
    val (code, failure) =
      try (ToolRunner.run(hconf, new Pipeline(), args.toArray), None)
      catch case t: Throwable => (-1, Some(t))
    Run(code, failure, out, args.mkString(" "))
  }

  // ----- input builders -----------------------------------------------------------------------

  def utf8(s: String): Array[Byte] = s.getBytes(UTF_8)

  val Header: String =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE dblp SYSTEM \"dblp.dtd\">\n<dblp>\n"
  val Footer: String = "</dblp>\n"

  def doc(records: String*): String = Header + records.mkString("\n") + "\n" + Footer

  /** One DBLP record. `year = None` leaves the `<year>` element out. */
  def rec(
      key: String,
      authors: Seq[String],
      title: String,
      year: Option[String] = Some("2020"),
      kind: String = "article",
      venue: Option[String] = Some("ADVJ"),
      extra: String = ""
  ): String =
    val venueTag = if kind == "inproceedings" then "booktitle" else "journal"
    val as = authors.map(a => s"<author>$a</author>").mkString
    val y = year.fold("")(v => s"<year>$v</year>")
    val v = venue.fold("")(x => s"<$venueTag>$x</$venueTag>")
    s"""<$kind mdate="2026-11-01" key="$key">$as<title>$title</title>$y$v$extra</$kind>"""

  /** A letters-only surname unique for index i (Qaaa, Qaab, ...), so generated authors never share
    * a name block by accident.
    */
  def surname(i: Int): String =
    require(i >= 0 && i < 26 * 26 * 26)
    val a = ('a' + i / 676).toChar
    val b = ('a' + (i / 26) % 26).toChar
    val c = ('a' + i % 26).toChar
    s"Q$a$b$c"

  /** A letters-only word unique for index i, used to make titles distinct. */
  def word(prefix: String, i: Int): String = prefix + surname(i).drop(1).toLowerCase

  def n2(n: Int): Int = n * (n - 1) / 2

  def deleteRecursively(p: Path): Unit =
    if Files.exists(p) then
      Files.walk(p).iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))

  /** Core contract errors of every work, team and pair line (empty means all valid). */
  def workErrors(lines: Vector[ujson.Value]): Vector[String] =
    lines.flatMap { j =>
      JsonLines.decode[WorkRecord](ujson.write(j)) match
        case Left(e)  => Vector(s"decode: $e")
        case Right(w) => Validate.work(w).map(e => s"${w.workId}: $e")
    }

  def teamErrors(lines: Vector[ujson.Value]): Vector[String] =
    lines.flatMap { j =>
      JsonLines.decode[PublicationTeam](ujson.write(j)) match
        case Left(e)  => Vector(s"decode: $e")
        case Right(t) => Validate.team(t).map(e => s"team ${t.workId}: $e")
    }

  def pairErrors(lines: Vector[ujson.Value]): Vector[String] =
    lines.flatMap { j =>
      JsonLines.decode[CoauthorPairCandidate](ujson.write(j)) match
        case Left(e)  => Vector(s"decode: $e")
        case Right(p) => Validate.pair(p).map(e => s"pair ${p.workId}: $e")
    }

  def mentionErrors(lines: Vector[ujson.Value]): Vector[String] =
    lines.flatMap { j =>
      JsonLines.decode[AuthorMention](ujson.write(j)) match
        case Left(e)  => Vector(s"decode: $e")
        case Right(m) => Validate.mention(m).map(e => s"mention ${m.mentionId}: $e")
    }

  /** `null`-aware string field. */
  def str(j: ujson.Value, k: String): Option[String] =
    j.obj.get(k) match
      case Some(ujson.Str(s)) => Some(s)
      case _                  => None

/** Base class: one temp folder per suite, removed afterwards; long timeout because a suite may wait
  * for the JVM-wide pipeline lock while another suite's pipeline runs.
  */
abstract class PipelineSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 30.minutes
  protected lazy val suiteDir: Path = Files.createTempDirectory("sc-adv-")
  protected def fresh(name: String): Path = Files.createDirectories(suiteDir.resolve(name))
  override def afterAll(): Unit = HadoopBlackBox.deleteRecursively(suiteDir)

  /** Prints an observed value, used for the test report. */
  protected def observe(label: String, value: => Any): Unit =
    println(s"OBSERVE ${getClass.getSimpleName} $label: $value")

  protected def assertOk(r: HadoopBlackBox.Run): Unit =
    r.failure.foreach(t => fail(s"pipeline threw: $t", t))
    assertEquals(r.exit, 0, s"pipeline exit code for: ${r.logText}")
