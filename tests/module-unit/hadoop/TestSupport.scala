package scholarchain.hadoop

import scholarchain.core.*
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.ToolRunner
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Fixtures come from the repository samples (datasets/samples), copied to
  * tests/module-unit/hadoop/resources/fixtures/ so they are on the test classpath.
  */
object FixtureIO:
  def bytes(name: String): Array[Byte] =
    val in = getClass.getClassLoader.getResourceAsStream(s"fixtures/$name")
    require(in != null, s"missing fixture $name")
    try in.readAllBytes()
    finally in.close()
  def text(name: String): String = new String(bytes(name), StandardCharsets.UTF_8)
  def lines(name: String): Vector[String] =
    text(name).linesIterator.filter(_.trim.nonEmpty).toVector

  val runId: String = Ids.run("""{"fixture":"hadoop"}""")
  val snapshot = "2026-11-22"
  val run: RunContext = RunContext(runId, snapshot)

/** Small valid records for logic tests. */
object Fixtures:
  def work(
      key: String,
      names: Vector[String],
      title: String = "Graphs",
      year: Option[Int] = Some(2021),
      doi: Option[String] = None,
      source: SourceType = SourceType.DBLP,
      venueId: Option[String] = None,
      venueName: Option[String] = None
  ): WorkRecord =
    val ns = source.toString.toLowerCase
    val wid = Ids.work(ns, key)
    val mp = FixtureIO.run.provenance(source, key, SchemaVersions.Mention)
    WorkRecord(
      SchemaVersions.Work,
      wid,
      source,
      key,
      "inproceedings",
      title,
      Normalize.title(title),
      year,
      venueId,
      venueName,
      doi,
      None,
      None,
      Vector.empty,
      names.zipWithIndex.map((n, i) =>
        AuthorMention(
          Ids.mention(wid, i),
          wid,
          i,
          n,
          Normalize.personName(n),
          None,
          None,
          None,
          mp
        )
      ),
      Vector.empty,
      Vector.empty,
      None,
      FixtureIO.run.provenance(source, key, SchemaVersions.Work)
    )

/** One pipeline run in a temp directory on Hadoop's LocalJobRunner. */
final class Harness(label: String):
  val dir: Path = Files.createTempDirectory(s"sc-hadoop-$label-")

  def write(name: String, text: String): Path =
    val p = dir.resolve("in").resolve(name)
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))
    p

  def fixture(name: String): Path = write(name, FixtureIO.text(name))

  def conf(out: String, extra: Map[String, String] = Map.empty): Configuration =
    val c = LocalRunner.localConf(out)
    extra.foreach((k, v) => c.set(k, v))
    c

  final class Result(val args: Seq[String], val code: Int, val conf: Configuration):
    val parsed: PipelineArgs = PipelineArgs.parse(args).toOption.get
    val paths: PipelinePaths =
      PipelinePaths(parsed.output, parsed.snapshot, Ids.run(parsed.manifest))
    def lines(stageDir: String, sub: String = ""): Vector[String] =
      FsText.lines(conf, s"$stageDir/${if sub.isEmpty then "" else sub + "/"}part-*")
    def decode[T: upickle.default.Reader: upickle.default.Writer](
        stageDir: String,
        sub: String = ""
    ): Vector[T] =
      lines(stageDir, sub).map(l =>
        JsonLines.decode[T](l).fold(e => throw new AssertionError(e), identity)
      )
    def counters(stageDir: String): Map[String, Long] =
      FsText.lines(conf, s"$stageDir/_counters.json").headOption match
        case None    => Map.empty
        case Some(j) => ujson.read(j).obj.map((k, v) => k -> v.num.toLong).toMap
    def rejects(stageDir: String): Vector[RejectRecord] =
      lines(stageDir, "rejects-v1").map(l =>
        JsonLines.decode[RejectRecord](l).fold(e => throw new AssertionError(e), identity)
      )
    def summary: ujson.Value = ujson.read(Files.readString(Path.of(paths.summary)))

  def run(
      args: Seq[String],
      extra: Map[String, String] = Map.empty
  ): Result =
    val out = args.sliding(2).collectFirst { case Seq("--output", v) => v }.get
    val c = conf(out, extra)
    val code = ToolRunner.run(c, new Pipeline, args.toArray)
    new Result(args, code, c)

  def delete(): Unit =
    if Files.exists(dir) then
      val walk = Files.walk(dir)
      try walk.iterator.asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p))
      finally walk.close()
