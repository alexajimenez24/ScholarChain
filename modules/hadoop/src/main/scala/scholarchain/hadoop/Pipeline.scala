package scholarchain.hadoop

import scholarchain.core.*
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.io.compress.{
  CompressionCodec,
  CompressionCodecFactory,
  DefaultCodec,
  GzipCodec
}
import org.apache.hadoop.mapreduce.{Job, Mapper}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, MultipleInputs, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.jdk.CollectionConverters.*

/** Command-line arguments of the Hadoop pipeline driver. */
final case class PipelineArgs(
    dblp: String,
    openAlex: Option[String],
    crossref: Option[String],
    orcid: Option[String],
    output: String,
    snapshot: String,
    maxTeam: Int,
    maxBlock: Int,
    reducers: Int,
    compress: Boolean
):
  /** The run manifest: everything that determines the outputs, so the run ID is reproducible. It
    * holds no timestamp and not the output root, so the same inputs give the same run ID.
    */
  def manifest: String = ujson
    .Obj(
      "pipeline" -> Pipeline.LogicVersion,
      "dblp" -> dblp,
      "openAlex" -> openAlex.getOrElse(""),
      "crossref" -> crossref.getOrElse(""),
      "orcid" -> orcid.getOrElse(""),
      "snapshot" -> snapshot,
      "maxTeam" -> maxTeam,
      "maxBlock" -> maxBlock,
      "reducers" -> reducers,
      "compress" -> compress,
      "normalization" -> Normalize.Version,
      "schemas" -> ujson.Arr.from(
        (SchemaVersions.Supported ++ Set(
          HadoopSchemas.MatchCandidate,
          HadoopSchemas.ExternalResearcher,
          HadoopSchemas.AuthorPaper,
          HadoopSchemas.Abstract
        )).toVector.sorted
      )
    )
    .render()

object PipelineArgs:
  val Usage: String =
    "usage: --dblp <path> --output <path> --snapshot <YYYY-MM-DD> [--openalex <path>] " +
      "[--crossref <path>] [--orcid <path>] [--max-team 300] [--max-block 200] " +
      "[--reducers 4] [--compress true]"

  def parse(args: Seq[String]): Either[String, PipelineArgs] =
    val Known = Set(
      "dblp",
      "openalex",
      "crossref",
      "orcid",
      "output",
      "snapshot",
      "max-team",
      "max-block",
      "reducers",
      "compress"
    )
    def walk(rest: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      rest match
        case Nil                                  => Right(acc)
        case k :: _ if !k.startsWith("--")        => Left(s"expected a --name, got '$k'")
        case k :: _ if !Known.contains(k.drop(2)) => Left(s"unknown argument '$k'")
        case k :: _ if acc.contains(k.drop(2))    => Left(s"argument '$k' is repeated")
        case k :: Nil                             => Left(s"$k has no value")
        case k :: v :: _ if v.startsWith("--")    => Left(s"$k has no value (next token is '$v')")
        case k :: v :: tail                       => walk(tail, acc + (k.drop(2) -> v))
    def int(kv: Map[String, String], name: String, default: Int, min: Int): Either[String, Int] =
      kv.get(name) match
        case None    => Right(default)
        case Some(s) =>
          s.toIntOption.filter(_ >= min).toRight(s"--$name must be an integer >= $min, got '$s'")
    for
      kv <- walk(args.toList, Map.empty)
      dblp <- kv.get("dblp").toRight("--dblp <path> is required")
      out <- kv.get("output").toRight("--output <path> is required")
      snapshot <- kv.get("snapshot").toRight("--snapshot <YYYY-MM-DD> is required")
      _ <- Either.cond(
        Validate.snapshotDate(snapshot),
        (),
        s"--snapshot '$snapshot' is not a calendar date YYYY-MM-DD"
      )
      maxTeam <- int(kv, "max-team", 300, 2)
      maxBlock <- int(kv, "max-block", 200, 2)
      reducers <- int(kv, "reducers", 4, 1)
      compress <- kv.get("compress") match
        case None          => Right(true)
        case Some("true")  => Right(true)
        case Some("false") => Right(false)
        case Some(o)       => Left(s"--compress must be exactly true or false, got '$o'")
    yield PipelineArgs(
      dblp,
      kv.get("openalex"),
      kv.get("crossref"),
      kv.get("orcid"),
      out,
      snapshot,
      maxTeam,
      maxBlock,
      reducers,
      compress
    )

/** Output layout; Spark and entity resolution read these paths. Every stage folder holds the
  * dataset snapshot and the run ID, so a rerun of the same run lands in the same place and a new
  * run never overwrites an old one.
  */
final case class PipelinePaths(root: String, snapshot: String, runId: String):
  val base: String = root.stripSuffix("/")
  private def at(stage: String) = s"$base/$stage/snapshot=$snapshot/run_id=$runId"
  val manifest: String = s"$base/run-manifests/$runId.json"
  val summary: String = s"$base/run-manifests/$runId-summary.json"
  val dblpWorks: String = at("works/source=dblp")
  def normalized(source: SourceType): String =
    at(s"normalized/source=${source.toString.toLowerCase(java.util.Locale.ROOT)}")
  val exactCandidates: String = at("match-candidates/kind=exact")
  val blockedCandidates: String = at("match-candidates/kind=blocked")
  val teams: String = at("teams")
  val pairs: String = at("coauthor-pair-candidates")
  val graphExport: String = at("export")

  /** The stages in order, with their job ID, for summaries. */
  def stages(a: PipelineArgs): Vector[(String, String)] =
    Vector("J01" -> dblpWorks) ++
      Vector(
        SourceType.OPENALEX -> a.openAlex,
        SourceType.CROSSREF -> a.crossref,
        SourceType.ORCID -> a.orcid
      ).collect { case (s, Some(_)) => s"J02-$s" -> normalized(s) } ++
      Vector(
        "J03" -> exactCandidates,
        "J04" -> blockedCandidates,
        "J05" -> teams,
        "J06" -> pairs,
        "J07" -> graphExport
      )

/** Chains J01 to J07. A stage whose output already has `_SUCCESS` is skipped, so a rerun with the
  * same inputs is idempotent; a partial output of this same run is removed and rebuilt.
  *
  * Job graph (see docs/hadoop-jobs.md):
  * {{{
  * J01 dblp xml  -> DblpInputFormat -> DblpExtractMapper -> UniqueReducer      -> works/source=dblp
  * J02 openalex/crossref/orcid jsonl -> SourceNormalizeMapper (map only)      -> normalized/source=*
  * J03 works + profiles -> WorkExactMapper|ProfileExactMapper -> ExactJoinReducer -> kind=exact
  * J04 works -> BlockingMapper -> BlockingReducer                             -> kind=blocked
  * J05 works -> TeamMapper -> TeamReducer                                     -> teams
  * J06 teams -> PairMapper -> UniqueReducer                                   -> coauthor-pair-candidates
  * J07 works,teams,pairs,authorpapers,topics,institutions -> Export*Mapper -> ExportReducer -> export
  * }}}
  */
class Pipeline extends Configured with Tool:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[Pipeline])

  override def run(args: Array[String]): Int =
    PipelineArgs.parse(args.toSeq) match
      case Left(err) =>
        System.err.println(s"usage error: $err\n${PipelineArgs.Usage}"); 2
      case Right(a) =>
        val runId = Ids.run(a.manifest)
        val conf = new Configuration(getConf)
        Pipeline.configure(conf, runId, a)
        val p = PipelinePaths(a.output, a.snapshot, runId)
        writeManifest(conf, p, a.manifest)
        log.info(s"[scholarchain] run $runId snapshot ${a.snapshot} output ${p.base}")
        val steps: Vector[(String, () => Boolean)] = Vector(
          "J01" -> (() => dblpExtract(conf, a, p)),
          "J02" -> (() => normalizeAll(conf, a, p)),
          "J03" -> (() => exactJoin(conf, a, p)),
          "J04" -> (() => blocking(conf, a, p)),
          "J05" -> (() => teams(conf, a, p)),
          "J06" -> (() => pairExpansion(conf, a, p)),
          "J07" -> (() => graphExport(conf, a, p))
        )
        val failed = steps.find { (name, step) =>
          log.info(s"[scholarchain] step $name start")
          val ok = step()
          log.info(s"[scholarchain] step $name ${if ok then "ok" else "FAILED"}")
          !ok
        }
        writeText(conf, p.summary, RunSummary.build(conf, a, p).render(indent = 2))
        if failed.isEmpty then 0 else 1

  private def writeText(conf: Configuration, path: String, text: String): Unit =
    val target = new Path(path)
    val out = target.getFileSystem(conf).create(target, true)
    try out.write(text.getBytes("UTF-8"))
    finally out.close()

  private def writeManifest(conf: Configuration, p: PipelinePaths, manifest: String): Unit =
    val path = new Path(p.manifest)
    if !path.getFileSystem(conf).exists(path) then writeText(conf, p.manifest, manifest)

  /** Skips a stage with `_SUCCESS`; removes a partial output of this same run before rerunning. */
  private def prepare(conf: Configuration, out: String): Boolean =
    val path = new Path(out)
    val fs: FileSystem = path.getFileSystem(conf)
    if fs.exists(new Path(path, "_SUCCESS")) then
      log.info(s"[scholarchain] $out already complete, skipping"); false
    else
      if fs.exists(path) then fs.delete(path, true) // only this run's own partial output
      true

  private def hasFiles(conf: Configuration, glob: String): Boolean =
    val path = new Path(glob)
    Option(path.getFileSystem(conf).globStatus(path)).exists(_.nonEmpty)

  private def job(
      conf: Configuration,
      name: String,
      out: String,
      a: PipelineArgs,
      reducers: Int
  ): Job =
    val j = Job.getInstance(conf, s"scholarchain-$name")
    j.setJarByClass(classOf[Pipeline])
    j.setOutputKeyClass(classOf[NullWritable]); j.setOutputValueClass(classOf[Text])
    j.setMapOutputKeyClass(classOf[Text]); j.setMapOutputValueClass(classOf[Text])
    j.setPartitionerClass(classOf[KeyPartitioner])
    j.setNumReduceTasks(reducers)
    LazyOutputFormat.setOutputFormatClass(j, classOf[TextOutputFormat[NullWritable, Text]])
    FileOutputFormat.setOutputPath(j, new Path(out))
    if a.compress then
      FileOutputFormat.setCompressOutput(j, true)
      FileOutputFormat.setOutputCompressorClass(j, classOf[GzipCodec])
    Out.All.foreach { n =>
      MultipleOutputs.addNamedOutput(
        j,
        n,
        classOf[TextOutputFormat[NullWritable, Text]],
        classOf[NullWritable],
        classOf[Text]
      )
    }
    j

  /** Runs a job, logs and persists its counters next to its output (`_counters.json`). */
  private def runJob(j: Job, out: String): Boolean =
    val ok = j.waitForCompletion(true)
    val counters = j.getCounters
      .getGroup(Ctr.Group)
      .iterator
      .asScala
      .map(c => c.getName -> c.getValue)
      .toVector
      .sortBy(_._1)
    counters.foreach((n, v) => log.info(s"[scholarchain] counter ${j.getJobName} $n=$v"))
    if ok then
      writeText(
        j.getConfiguration,
        s"$out/_counters.json",
        ujson.Obj.from(counters.map((n, v) => n -> ujson.Num(v.toDouble))).render()
      )
    ok

  private def dblpExtract(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Boolean =
    !prepare(conf, p.dblpWorks) || {
      val j = job(conf, "J01-dblp-extract", p.dblpWorks, a, a.reducers)
      j.setInputFormatClass(classOf[DblpInputFormat])
      FileInputFormat.addInputPaths(j, a.dblp)
      j.setMapperClass(classOf[DblpExtractMapper]); j.setReducerClass(classOf[UniqueReducer])
      runJob(j, p.dblpWorks)
    }

  private def normalizeAll(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Boolean =
    Vector(
      SourceType.OPENALEX -> a.openAlex,
      SourceType.CROSSREF -> a.crossref,
      SourceType.ORCID -> a.orcid
    ).forall {
      case (_, None)             => true
      case (source, Some(input)) =>
        val out = p.normalized(source)
        !prepare(conf, out) || {
          val c = new Configuration(conf); c.set(Keys.Source, source.toString)
          val j = job(c, s"J02-normalize-${source.toString.toLowerCase}", out, a, 0)
          j.setInputFormatClass(classOf[TextInputFormat])
          FileInputFormat.addInputPaths(j, input)
          j.setMapperClass(classOf[SourceNormalizeMapper])
          j.setMapOutputKeyClass(classOf[NullWritable])
          runJob(j, out)
        }
    }

  /** Work files of every source, through `part-*` globs: stage folders also hold named outputs
    * (rejects-v1/, stats/) that must not be read as data. Sources that wrote no works are left out.
    */
  private def workInputs(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Vector[String] =
    (s"${p.dblpWorks}/part-*" +: Vector(
      SourceType.OPENALEX -> a.openAlex,
      SourceType.CROSSREF -> a.crossref
    ).collect { case (s, Some(_)) => s"${p.normalized(s)}/works/part-*" }).filter(hasFiles(conf, _))

  private def exactJoin(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Boolean =
    val works = workInputs(conf, a, p)
    val profiles = Vector(s"${p.normalized(SourceType.ORCID)}/researchers/part-*")
      .filter(_ => a.orcid.nonEmpty)
      .filter(hasFiles(conf, _))
    if works.isEmpty && profiles.isEmpty then skipEmpty("J03")
    else
      !prepare(conf, p.exactCandidates) || {
        val j = job(conf, "J03-exact-identifier-join", p.exactCandidates, a, a.reducers)
        works.foreach(in => addInput(j, in, classOf[WorkExactMapper]))
        profiles.foreach(in => addInput(j, in, classOf[ProfileExactMapper]))
        j.setReducerClass(classOf[ExactJoinReducer])
        runJob(j, p.exactCandidates)
      }

  private def addInput(j: Job, glob: String, mapper: Class[? <: Mapper[?, ?, ?, ?]]): Unit =
    MultipleInputs.addInputPath(j, new Path(glob), classOf[TextInputFormat], mapper)

  private def skipEmpty(stage: String): Boolean =
    log.info(s"[scholarchain] $stage has no input, skipping"); true

  private def blocking(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Boolean =
    val works = workInputs(conf, a, p)
    if works.isEmpty then skipEmpty("J04")
    else
      !prepare(conf, p.blockedCandidates) || {
        val j = job(conf, "J04-blocking-features", p.blockedCandidates, a, a.reducers)
        j.setInputFormatClass(classOf[TextInputFormat])
        works.foreach(in => FileInputFormat.addInputPath(j, new Path(in)))
        j.setMapperClass(classOf[BlockingMapper]); j.setReducerClass(classOf[BlockingReducer])
        runJob(j, p.blockedCandidates)
      }

  private def teams(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Boolean =
    val works = workInputs(conf, a, p)
    if works.isEmpty then skipEmpty("J05")
    else
      !prepare(conf, p.teams) || {
        val j = job(conf, "J05-publication-teams", p.teams, a, a.reducers)
        j.setInputFormatClass(classOf[TextInputFormat])
        works.foreach(in => FileInputFormat.addInputPath(j, new Path(in)))
        j.setMapperClass(classOf[TeamMapper]); j.setReducerClass(classOf[TeamReducer])
        runJob(j, p.teams)
      }

  private def pairExpansion(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Boolean =
    val teamFiles = s"${p.teams}/teams/part-*"
    if !hasFiles(conf, teamFiles) then skipEmpty("J06")
    else
      !prepare(conf, p.pairs) || {
        val j = job(conf, "J06-coauthor-pair-expansion", p.pairs, a, a.reducers)
        j.setInputFormatClass(classOf[TextInputFormat])
        FileInputFormat.addInputPath(j, new Path(teamFiles))
        j.setMapperClass(classOf[PairMapper]); j.setReducerClass(classOf[UniqueReducer])
        runJob(j, p.pairs)
      }

  private def graphExport(conf: Configuration, a: PipelineArgs, p: PipelinePaths): Boolean =
    val works = workInputs(conf, a, p)
    val sources = Vector(SourceType.OPENALEX, SourceType.CROSSREF)
    def normalizedKind(kind: String) =
      sources.map(s => s"${p.normalized(s)}/$kind/part-*").filter(hasFiles(conf, _))
    val families: Vector[(Vector[String], Class[? <: Mapper[?, ?, ?, ?]])] = Vector(
      works -> classOf[ExportWorkMapper],
      Vector(s"${p.teams}/teams/part-*").filter(hasFiles(conf, _)) -> classOf[ExportTeamMapper],
      Vector(s"${p.pairs}/part-*").filter(hasFiles(conf, _)) -> classOf[ExportPairMapper],
      Vector(s"${p.teams}/authorpapers/part-*")
        .filter(hasFiles(conf, _)) -> classOf[ExportAuthorPaperMapper],
      normalizedKind("topics") -> classOf[ExportTopicMapper],
      normalizedKind("institutions") -> classOf[ExportInstitutionMapper]
    )
    if families.forall(_._1.isEmpty) then skipEmpty("J07")
    else
      !prepare(conf, p.graphExport) || {
        val j = job(conf, "J07-graph-export", p.graphExport, a, a.reducers)
        families.foreach((inputs, mapper) => inputs.foreach(addInput(j, _, mapper)))
        j.setReducerClass(classOf[ExportReducer])
        runJob(j, p.graphExport)
      }

object Pipeline:
  /** The pipeline logic version, part of the run manifest and so of the run ID. Bump it on any
    * change of job logic, keys or output format: a rerun then never reuses stage outputs written by
    * older logic. Input paths are treated as immutable per snapshot.
    */
  val LogicVersion: String = "hadoop-v3"

  /** The settings every job shares: run, snapshot, caps and intermediate (map output) compression,
    * which is always on.
    */
  def configure(conf: Configuration, runId: String, a: PipelineArgs): Unit =
    conf.set(Keys.RunId, runId); conf.set(Keys.Snapshot, a.snapshot)
    conf.setInt(Keys.MaxTeam, a.maxTeam); conf.setInt(Keys.MaxBlock, a.maxBlock)
    conf.setBoolean("mapreduce.map.output.compress", true)
    conf.setClass(
      "mapreduce.map.output.compress.codec",
      classOf[DefaultCodec],
      classOf[CompressionCodec]
    )

  def main(args: Array[String]): Unit =
    sys.exit(ToolRunner.run(new Configuration(), new Pipeline, args))

/** Reads text part files (plain or compressed) under a glob. */
object FsText:
  def lines(conf: Configuration, glob: String): Vector[String] =
    val path = new Path(glob)
    val fs = path.getFileSystem(conf)
    val codecs = new CompressionCodecFactory(conf)
    Option(fs.globStatus(path))
      .map(_.toVector)
      .getOrElse(Vector.empty)
      .filter(_.isFile)
      .sortBy(_.getPath.toString)
      .flatMap { st =>
        val codec = codecs.getCodec(st.getPath)
        val raw = fs.open(st.getPath)
        val in = if codec == null then raw else codec.createInputStream(raw)
        try new String(in.readAllBytes(), "UTF-8").linesIterator.filter(_.nonEmpty).toVector
        finally in.close()
      }

/** The run summary written next to the manifest: counters per stage and the maxima Hadoop counters
  * cannot hold (largest team, largest pair expansion, largest blocking group).
  */
object RunSummary:
  def build(conf: Configuration, a: PipelineArgs, p: PipelinePaths): ujson.Obj =
    def counters(stageOut: String): ujson.Value =
      FsText
        .lines(conf, s"$stageOut/_counters.json")
        .headOption
        .map(ujson.read(_))
        .getOrElse(ujson.Null)
    def stats(stageOut: String): Vector[ujson.Value] =
      FsText.lines(conf, s"$stageOut/stats/part-*").map(ujson.read(_))
    val teamStats = stats(p.teams).filter(_("stat").str == "maxTeamSize")
    val bestTeam = teamStats
      .sortBy(s => (-s("teamSize").num, s("workId").str))
      .headOption
      .getOrElse(ujson.Obj("workId" -> "", "teamSize" -> 0))
    val blockStats = stats(p.blockedCandidates).filter(_("stat").str == "largestBlock")
    val bestBlock = blockStats
      .sortBy(s => (-s("members").num, s("key").str))
      .headOption
      .getOrElse(ujson.Obj("key" -> "", "members" -> 0))
    val size = bestTeam("teamSize").num.toLong
    ujson.Obj(
      "runId" -> p.runId,
      "snapshot" -> p.snapshot,
      "reducers" -> a.reducers,
      "maxTeam" -> a.maxTeam,
      "maxBlock" -> a.maxBlock,
      "maxTeamObserved" -> ujson.Obj(
        "workId" -> bestTeam("workId").str,
        "teamSize" -> size.toInt
      ),
      "largestPairExpansion" -> TeamLogic.pairCount(size).toDouble,
      "largestBlockObserved" -> ujson.Obj(
        "key" -> bestBlock("key").str,
        "members" -> bestBlock("members").num.toInt
      ),
      "stages" -> ujson.Obj.from(
        p.stages(a)
          .map((name, out) => name -> ujson.Obj("output" -> out, "counters" -> counters(out)))
      )
    )
