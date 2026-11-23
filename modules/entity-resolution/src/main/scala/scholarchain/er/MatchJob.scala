package scholarchain.er

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.{LongWritable, NullWritable, RawComparator, Text, WritableComparator}
import org.apache.hadoop.io.WritableUtils
import org.apache.hadoop.io.compress.{CompressionCodecFactory, GzipCodec}
import org.apache.hadoop.mapreduce.{Job, Mapper, Partitioner, Reducer, TaskInputOutputContext}
import org.apache.hadoop.mapreduce.lib.input.{MultipleInputs, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.apache.hadoop.util.{Tool, ToolRunner}

import scholarchain.core.*
import scholarchain.er.review.ReviewQueueMain
import upickle.default.{read, write}

/** Configuration keys the driver passes to the tasks. */
object ErKeys:
  val RunId = "scholarchain.er.run.id"
  val DecidedAt = "scholarchain.er.decided.at"
  val PolicyVersion = "scholarchain.er.policy.version"
  val Accept = "scholarchain.er.threshold.accept"
  val Reject = "scholarchain.er.threshold.reject"
  val BandMin = "scholarchain.er.threshold.llm.min"
  val BandMax = "scholarchain.er.threshold.llm.max"
  val Adjudicator = "scholarchain.er.adjudicator"
  val BudgetPerTask = "scholarchain.er.llm.budget.per.task"
  val CacheGlob = "scholarchain.er.adjudication.cache.glob"

/** Named outputs of the decision job: one folder per outcome, the audit trail, the adjudications (a
  * reusable cache) and the versioned reject output.
  */
object ErOut:
  val Accepted = "accepted"
  val Rejected = "rejected"
  val Unresolved = "unresolved"
  val Conflict = "conflict"
  val Audit = "audit"
  val Adjudications = "adjudications"
  val Rejects = "rejects"
  val RejectsPath = "rejects-v1/part"
  val Outcomes: Vector[String] = Vector(Accepted, Rejected, Unresolved, Conflict)
  val All: Vector[String] = Outcomes ++ Vector(Audit, Adjudications, Rejects)

  def of(o: MatchOutcome): String = o match
    case MatchOutcome.ACCEPTED   => Accepted
    case MatchOutcome.REJECTED   => Rejected
    case MatchOutcome.UNRESOLVED => Unresolved
    case MatchOutcome.CONFLICT   => Conflict

object ErCounters:
  val Group = "scholarchain"
  val Input = "INPUT_RECORDS"
  val Rejected = "REJECTED_RECORDS"
  val Skipped = "SKIPPED_RECORDS"
  val SchemaFailure = "SCHEMA_FAILURE"
  val Duplicate = "DUPLICATE_RECORDS"
  val Conflict = "CONFLICT_RECORDS"
  val ProfileMissing = "PROFILE_MISSING"
  val ProfileTruncated = "PROFILE_TRUNCATED"
  val Decisions = "DECISIONS"
  val InvalidDecision = "INVALID_DECISION"

/** Shared task helpers: counters, the reject output and named outputs. Per-task state only. */
trait ErTask[KI, VI, KO, VO]:
  protected var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  protected var runId: String = ""
  protected val out = new Text

  protected def setupTask(ctx: TaskInputOutputContext[KI, VI, KO, VO]): Unit =
    mos = new MultipleOutputs(ctx.asInstanceOf[TaskInputOutputContext[?, ?, NullWritable, Text]])
    runId = ctx.getConfiguration.get(ErKeys.RunId, "")

  protected def count(
      ctx: TaskInputOutputContext[KI, VI, KO, VO],
      name: String,
      n: Long = 1
  ): Unit =
    if n != 0 then ctx.getCounter(ErCounters.Group, name).increment(n)

  protected def named(name: String, line: String): Unit =
    out.set(line); mos.write(name, NullWritable.get, out, s"$name/part")

  protected def reject(
      ctx: TaskInputOutputContext[KI, VI, KO, VO],
      stage: String,
      reason: String,
      input: String,
      schemaFailure: Boolean = false
  ): Unit =
    count(ctx, ErCounters.Rejected)
    if schemaFailure then count(ctx, ErCounters.SchemaFailure)
    out.set(JsonLines.encode(RejectRecord.of(stage, reason, input, runId)))
    mos.write(ErOut.Rejects, NullWritable.get, out, ErOut.RejectsPath)

  protected def closeTask(): Unit = mos.close()

// ------------------------------------------------------------------ ER-J1 endpoint profiles

/** J1 keys are `<endpointId>\t<tag>`: tag `P` for a profile part, `R` for a candidate reference.
  * The partitioner and the grouping comparator use only the endpoint ID, and `P` sorts before `R`,
  * so a reducer sees an endpoint's profile parts before its references and streams the references
  * without buffering them (secondary sort).
  */
object EndpointKey:
  val Profile = "P"
  val Reference = "R"
  def of(endpointId: String, tag: String): String = s"$endpointId\t$tag"

  /** Bytes of a Text key up to its first TAB. */
  def prefixLength(bytes: Array[Byte], start: Int, length: Int): Int =
    var i = 0
    while i < length && bytes(start + i) != '\t'.toByte do i += 1
    i

class EndpointPartitioner extends Partitioner[Text, Text]:
  override def getPartition(key: Text, value: Text, n: Int): Int =
    val len = EndpointKey.prefixLength(key.getBytes, 0, key.getLength)
    (WritableComparator.hashBytes(key.getBytes, 0, len) & Int.MaxValue) % n

class EndpointGroupingComparator extends RawComparator[Text]:
  override def compare(b1: Array[Byte], s1: Int, l1: Int, b2: Array[Byte], s2: Int, l2: Int): Int =
    val n1 = WritableUtils.decodeVIntSize(b1(s1))
    val n2 = WritableUtils.decodeVIntSize(b2(s2))
    val p1 = EndpointKey.prefixLength(b1, s1 + n1, l1 - n1)
    val p2 = EndpointKey.prefixLength(b2, s2 + n2, l2 - n2)
    WritableComparator.compareBytes(b1, s1 + n1, p1, b2, s2 + n2, p2)
  override def compare(a: Text, b: Text): Int =
    val pa = EndpointKey.prefixLength(a.getBytes, 0, a.getLength)
    val pb = EndpointKey.prefixLength(b.getBytes, 0, b.getLength)
    WritableComparator.compareBytes(a.getBytes, 0, pa, b.getBytes, 0, pb)

/** J1 map over candidate lines: one reference per endpoint. An invalid line is only counted here;
  * the decision job reads the same line and writes its reject.
  */
class CandidateRefMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with ErTask[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private val k = new Text
  private val v = new Text
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, ErCounters.Input)
    ErCandidate.decode(line.toString) match
      case Left(_)  => count(ctx, ErCounters.Skipped)
      case Right(c) =>
        Vector(c.sourceId -> "L", c.targetId -> "R").foreach { (id, side) =>
          k.set(EndpointKey.of(id, EndpointKey.Reference)); v.set(s"${c.candidateId}\t$side")
          ctx.write(k, v)
        }
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J1 map over the run's exported works: the work profile under the work ID, each mention profile
  * under its mention ID and under the researcher IDs it stands for (`orcid:`, `openalex:`).
  */
class WorkProfileMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with ErTask[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private val k = new Text
  private val v = new Text
  private var limits: ProfileLimits = scala.compiletime.uninitialized
  override def setup(ctx: Ctx): Unit =
    setupTask(ctx)
    limits = PolicyConfig.load().fold(e => throw new IllegalStateException(e), _.limits)
  private def emit(ctx: Ctx, id: String, value: String): Unit =
    k.set(EndpointKey.of(id, EndpointKey.Profile)); v.set(value); ctx.write(k, v)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, ErCounters.Input)
    JsonLines.decodeValid[WorkRecord](line.toString, Validate.work) match
      case Left(reason) => reject(ctx, "ER-J1", reason, line.toString, schemaFailure = true)
      case Right(w)     =>
        emit(ctx, w.workId, "W\t" + write(Profiles.work(w, limits)))
        Profiles.mentions(w, limits).foreach { m =>
          emit(ctx, m.mentionId, "M\t" + write(m))
          // An aggregate endpoint keeps only identity fields of each member: it is evidence of
          // names, sources and works, and stays small however many references it has.
          val slim = m.copy(coauthorNames = Vector.empty, title = "", references = Vector.empty)
          Profiles.researcherKeys(m).foreach(id => emit(ctx, id, "A\t" + write(slim)))
        }
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J1 reduce: one endpoint, its profile parts first, then its references. Each reference becomes
  * one line `<candidateId>\t<side>\t<EndpointProfile JSON>`. Profiles of a work ID that differ are
  * resolved to the smallest JSON (deterministic) and counted as conflicts.
  */
class ProfileJoinReducer
    extends Reducer[Text, Text, NullWritable, Text]
    with ErTask[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var limits: ProfileLimits = scala.compiletime.uninitialized
  override def setup(ctx: Ctx): Unit =
    setupTask(ctx)
    limits = PolicyConfig.load().fold(e => throw new IllegalStateException(e), _.limits)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val keyText = key.toString
    val endpoint = keyText.takeWhile(_ != '\t')
    var workJson: Option[String] = None
    var mentionView = EndpointProfile(endpoint, EntityKind.RESEARCHER, None, Vector.empty, false)
    var aggregate = mentionView
    values.asScala.foreach { t =>
      val s = t.toString
      s.split("\t", 2) match
        case Array("W", json) =>
          workJson match
            case Some(prev) if prev != json =>
              count(ctx, ErCounters.Conflict)
              workJson = Some(
                if json.length < prev.length || (json.length == prev.length && json < prev) then
                  json
                else prev
              )
            case Some(_) => count(ctx, ErCounters.Duplicate)
            case None    => workJson = Some(json)
        case Array(tag @ ("M" | "A"), json) =>
          JsonLines.decode[MentionProfile](json) match
            case Left(e)  => reject(ctx, "ER-J1", e, s)
            case Right(m) =>
              if tag == "M" then
                mentionView = Profiles.addMention(mentionView, m, limits.maxMentionsPerEndpoint)
              else aggregate = Profiles.addMention(aggregate, m, limits.maxMentionsPerEndpoint)
        case Array(candidateId, side) if side == "L" || side == "R" =>
          val profile =
            if candidateId.startsWith("mc:work:") then
              val w = workJson.flatMap(j => JsonLines.decode[WorkProfile](j).toOption)
              EndpointProfile(endpoint, EntityKind.WORK, w, Vector.empty, false)
            else if mentionView.mentions.nonEmpty then mentionView
            else aggregate
          if profile.truncated then count(ctx, ErCounters.ProfileTruncated)
          out.set(s"$candidateId\t$side\t${write(profile)}")
          ctx.write(NullWritable.get, out)
        case _ => reject(ctx, "ER-J1", "unrecognized J1 value", s)
    }
  override def cleanup(ctx: Ctx): Unit = closeTask()

// ------------------------------------------------------------------ ER-J2 decisions

class CandidateLineMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with ErTask[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private val k = new Text
  private val v = new Text
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, ErCounters.Input)
    ErCandidate.decode(line.toString) match
      case Left(reason) => reject(ctx, "ER-J2", reason, line.toString, schemaFailure = true)
      case Right(c)     => k.set(c.candidateId); v.set("C\t" + line.toString); ctx.write(k, v)
  override def cleanup(ctx: Ctx): Unit = closeTask()

class SideLineMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with ErTask[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private val k = new Text
  private val v = new Text
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    line.toString.split("\t", 3) match
      case Array(candidateId, side, json) =>
        k.set(candidateId); v.set(s"S\t$side\t$json"); ctx.write(k, v)
      case _ => reject(ctx, "ER-J2", "malformed profile line", line.toString)
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J2 reduce: every copy of one candidate plus its two endpoint profiles in, one decision out,
  * written to the folder of its outcome, with its audit line and any adjudication used.
  */
class DecideReducer
    extends Reducer[Text, Text, NullWritable, Text]
    with ErTask[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var resolver: Resolver = scala.compiletime.uninitialized
  private var adjudicator: Adjudicator = scala.compiletime.uninitialized

  override def setup(ctx: Ctx): Unit =
    setupTask(ctx)
    val c = ctx.getConfiguration
    val built = for
      p <- PolicyConfig.load()
      t = Thresholds(
        c.get(ErKeys.Accept).toDouble,
        c.get(ErKeys.Reject).toDouble,
        c.get(ErKeys.BandMin).toDouble,
        c.get(ErKeys.BandMax).toDouble
      )
      _ <- Either.cond(t == p.thresholds, (), s"thresholds $t are not the pinned ${p.thresholds}")
      _ <- Either.cond(
        c.get(ErKeys.PolicyVersion) == p.policyVersion,
        (),
        "policy version mismatch"
      )
      template <- PromptTemplate.load()
      mode <- AdjudicatorMode.parse(c.get(ErKeys.Adjudicator, "off"))
      cache <- MatchJob.readCache(c)
    yield
      val port = mode match
        case AdjudicatorMode.OFF  => OffAdjudicator
        case AdjudicatorMode.FAKE => new FakeAdjudicator(template.version)
      adjudicator =
        new Adjudicator(port, template, cache, c.getInt(ErKeys.BudgetPerTask, 0), p.policyVersion)
      new Resolver(new MatchPolicy(p, t), adjudicator, runId, c.get(ErKeys.DecidedAt))
    resolver = built.fold(e => throw new IllegalStateException(e), identity)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val copies = Vector.newBuilder[ErCandidate]
    val sides = scala.collection.mutable.Map.empty[String, String]
    values.asScala.foreach { t =>
      val s = t.toString
      s.split("\t", 3) match
        case Array("C", rest @ _*) =>
          ErCandidate.decode(s.drop(2)) match
            case Right(c) => copies += c
            case Left(e)  => reject(ctx, "ER-J2", e, s.drop(2), schemaFailure = true)
        case Array("S", side, json) => sides(side) = json
        case _                      => reject(ctx, "ER-J2", "unrecognized J2 value", s)
    }
    val cs = copies.result()
    if cs.isEmpty then reject(ctx, "ER-J2", s"profile without a candidate for ${key.toString}", "")
    else
      count(ctx, ErCounters.Duplicate, (cs.size - 1).toLong)
      ErCandidate.merge(cs) match
        case Left(e) =>
          count(ctx, ErCounters.Conflict); reject(ctx, "ER-J2", e, JsonLines.encode(cs.head))
        case Right(m) =>
          def side(tag: String, id: String): EndpointProfile =
            sides.get(tag).flatMap(j => JsonLines.decode[EndpointProfile](j).toOption) match
              case Some(p) if p.found => p
              case _ => count(ctx, ErCounters.ProfileMissing); EndpointProfile.missing(id, m.kind)
          val left = side("L", m.sourceId)
          val right = side("R", m.targetId)
          val calls = adjudicator.callsMade
          val r = resolver.decide(m, left, right)
          count(ctx, "LLM_CALLS", (adjudicator.callsMade - calls).toLong)
          val errors = Validate.decision(r.decision)
          if errors.nonEmpty then
            count(ctx, ErCounters.InvalidDecision)
            reject(ctx, "ER-J2", errors.mkString("; "), JsonLines.encode(r.decision))
          else
            val d = r.decision
            count(ctx, ErCounters.Decisions)
            count(ctx, s"${d.kind}_${d.decision}")
            count(ctx, s"PATH_${d.path}")
            r.audit.adjudicationFromCache.foreach(hit =>
              count(ctx, if hit then "LLM_CACHE_HITS" else "LLM_ANSWERS")
            )
            d.reasonCodes
              .filter(_.startsWith("LLM_"))
              .filter(c => MatchJob.LlmCounterCodes(c))
              .foreach(count(ctx, _))
            named(ErOut.of(d.decision), JsonLines.encode(d))
            named(ErOut.Audit, write(r.audit))
            r.adjudication.foreach(a => named(ErOut.Adjudications, write(a)))
  override def cleanup(ctx: Ctx): Unit = closeTask()

// ------------------------------------------------------------------ driver

final case class MatchArgs(
    runRoot: String,
    runId: Option[String],
    output: String,
    adjudicator: AdjudicatorMode,
    cache: Option[String],
    reducers: Int,
    compress: Boolean
)

object MatchArgs:
  val Usage: String =
    "usage: --run-root <hadoop output root> --output <dir> [--run-id <run-...>] " +
      "[--adjudicator off|fake] [--adjudication-cache <decision run dir or file>] [--reducers 4] " +
      "[--compress true]"

  def parse(args: Seq[String]): Either[String, MatchArgs] =
    val known = Set(
      "run-root",
      "run-id",
      "output",
      "adjudicator",
      "adjudication-cache",
      "reducers",
      "compress"
    )
    def walk(rest: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      rest match
        case Nil                                  => Right(acc)
        case k :: _ if !k.startsWith("--")        => Left(s"expected a --name, got '$k'")
        case k :: _ if !known.contains(k.drop(2)) => Left(s"unknown argument '$k'")
        case k :: _ if acc.contains(k.drop(2))    => Left(s"argument '$k' is repeated")
        case k :: Nil                             => Left(s"$k has no value")
        case k :: v :: _ if v.startsWith("--")    => Left(s"$k has no value (next token is '$v')")
        case k :: v :: tail                       => walk(tail, acc + (k.drop(2) -> v))
    for
      kv <- walk(args.toList, Map.empty)
      root <- kv.get("run-root").toRight("--run-root is required")
      out <- kv.get("output").toRight("--output is required")
      mode <- AdjudicatorMode.parse(kv.getOrElse("adjudicator", "off"))
      reducers <- kv
        .get("reducers")
        .fold[Either[String, Int]](Right(4))(s =>
          s.toIntOption.filter(_ >= 1).toRight(s"--reducers must be an integer >= 1, got '$s'")
        )
      compress <- kv.getOrElse("compress", "true") match
        case "true"  => Right(true)
        case "false" => Right(false)
        case o       => Left(s"--compress must be exactly true or false, got '$o'")
      _ <- kv
        .get("run-id")
        .fold[Either[String, Unit]](Right(()))(r =>
          Either.cond(Ids.isRunId(r), (), s"--run-id '$r' is not a run ID")
        )
    yield MatchArgs(
      root,
      kv.get("run-id"),
      out,
      mode,
      kv.get("adjudication-cache"),
      reducers,
      compress
    )

/** Output layout of one entity-resolution run. */
final case class MatchPaths(root: String, policyVersion: String, snapshot: String, runId: String):
  val base: String = root.stripSuffix("/")
  val profiles: String = s"$base/_er-profiles/policy=$policyVersion/run_id=$runId"
  val decisions: String =
    s"$base/match-decisions/policy=$policyVersion/snapshot=$snapshot/run_id=$runId"
  val manifest: String = s"$base/run-manifests/$runId.json"
  val summary: String = s"$base/run-manifests/$runId-summary.json"

/** Upstream inputs of one Hadoop run. */
final case class UpstreamRun(
    runId: String,
    snapshot: String,
    candidateGlobs: Vector[String],
    worksGlob: String,
    pipeline: String,
    normalization: String
)

/** Entity-resolution driver (map-reduce, two jobs):
  * {{{
  * ER-J1 candidates + export works -> CandidateRefMapper | WorkProfileMapper -> ProfileJoinReducer
  *        -> _er-profiles (one line per candidate side: the endpoint profile)
  * ER-J2 candidates + J1 lines     -> CandidateLineMapper | SideLineMapper  -> DecideReducer
  *        -> match-decisions/{accepted,rejected,unresolved,conflict,audit,adjudications,rejects-v1}
  * }}}
  * The run ID hashes a manifest of the upstream run, the policy, its pinned thresholds, the feature
  * and normalization versions, the prompt and model versions, the adjudicator mode and budget, and
  * the cache file contents; it holds no timestamp and no output root. A stage whose output has
  * `_SUCCESS` is skipped; a partial output of the same run is rebuilt. Inputs or outputs under a
  * human-only folder are refused.
  */
class MatchJob(
    env: Map[String, String] = ConfigLoader.processEnvironment,
    clock: () => Instant = () => Instant.now()
) extends Configured
    with Tool:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[MatchJob])

  override def run(args: Array[String]): Int =
    MatchArgs.parse(args.toSeq) match
      case Left(err) => System.err.println(s"usage error: $err\n${MatchArgs.Usage}"); 2
      case Right(a)  =>
        prepareRun(a) match
          case Left(err) => System.err.println(s"entity resolution error: $err"); 3
          case Right((conf, up, paths, manifest)) =>
            writeText(conf, paths.manifest, manifest)
            log.info(
              s"[scholarchain] er run ${paths.runId} upstream ${up.runId} output ${paths.decisions}"
            )
            val ok = profileJob(conf, a, up, paths) && decideJob(conf, a, up, paths)
            if ok then
              writeText(conf, paths.summary, MatchJob.summary(conf, paths).render(indent = 2))
            if ok then 0 else 1

  private def prepareRun(a: MatchArgs) =
    val paths = Vector(a.runRoot, a.output) ++ a.cache.toVector
    for
      _ <- paths
        .find(p =>
          ReviewQueueMain.isGoldPath(
            java.nio.file.Paths.get(p.replaceFirst("^[a-z0-9]+://[^/]*", ""))
          )
        )
        .map(p => s"refusing a path in human-only territory: $p")
        .toLeft(())
      cfg <- ConfigLoader.load(env)
      _ <- Either.cond(
        !cfg.models.llmEnabled,
        (),
        "models.llmEnabled is true, but LLM adjudication through Bedrock arrives in Phase 07"
      )
      policy <- MatchPolicy.load(cfg.entityResolution)
      template <- PromptTemplate.load()
      conf = new Configuration(getConf)
      up <- MatchJob.upstream(conf, a)
      cacheGlob <- a.cache.fold[Either[String, String]](Right(""))(c => MatchJob.cacheGlob(conf, c))
      cacheSha <- Right(MatchJob.cacheDigest(conf, cacheGlob))
    yield
      val t = policy.thresholds
      val (promptVersion, modelVersion) = a.adjudicator match
        case AdjudicatorMode.OFF  => ("none", "none")
        case AdjudicatorMode.FAKE => (template.version, FakeAdjudicator.ModelVersion)
      val budget = cfg.entityResolution.maxLlmCallsPerRun
      val manifest = ujson
        .Obj(
          "stage" -> "entity-resolution",
          "logic" -> MatchJob.LogicVersion,
          "upstreamRunId" -> up.runId,
          "upstreamPipeline" -> up.pipeline,
          "upstreamNormalization" -> up.normalization,
          "snapshot" -> up.snapshot,
          "policyVersion" -> policy.version,
          "policySha256" -> PolicyConfig.resourceText().map(Hashing.sha256Hex).getOrElse(""),
          "thresholds" -> ujson.read(write(t)),
          "featureVersion" -> policy.config.featureVersion,
          "normalizationVersion" -> policy.config.normalizationVersion,
          "adjudicator" -> a.adjudicator.toString,
          "promptVersion" -> promptVersion,
          "promptSha256" -> (if a.adjudicator == AdjudicatorMode.OFF then "" else template.sha256),
          "modelVersion" -> modelVersion,
          "llmBudgetPerRun" -> budget,
          "cacheSha256" -> cacheSha,
          "reducers" -> a.reducers,
          "compress" -> a.compress
        )
        .render()
      val runId = Ids.run(manifest)
      conf.set(ErKeys.RunId, runId)
      if conf.get(ErKeys.DecidedAt) == null then conf.set(ErKeys.DecidedAt, clock().toString)
      conf.set(ErKeys.PolicyVersion, policy.version)
      conf.set(ErKeys.Accept, t.accept.toString)
      conf.set(ErKeys.Reject, t.reject.toString)
      conf.set(ErKeys.BandMin, t.llmBandMin.toString)
      conf.set(ErKeys.BandMax, t.llmBandMax.toString)
      conf.set(ErKeys.Adjudicator, a.adjudicator.toString.toLowerCase)
      conf.setInt(ErKeys.BudgetPerTask, budget / a.reducers)
      conf.set(ErKeys.CacheGlob, cacheGlob)
      (conf, up, MatchPaths(a.output, policy.version, up.snapshot, runId), manifest)

  private def writeText(conf: Configuration, path: String, text: String): Unit =
    val target = new Path(path)
    val o = target.getFileSystem(conf).create(target, true)
    try o.write(text.getBytes(StandardCharsets.UTF_8))
    finally o.close()

  private def prepare(conf: Configuration, out: String): Boolean =
    val path = new Path(out)
    val fs: FileSystem = path.getFileSystem(conf)
    if fs.exists(new Path(path, "_SUCCESS")) then
      log.info(s"[scholarchain] $out already complete, skipping"); false
    else
      if fs.exists(path) then fs.delete(path, true) // only this run's own partial output
      true

  private def job(conf: Configuration, name: String, out: String, a: MatchArgs): Job =
    val j = Job.getInstance(conf, s"scholarchain-$name")
    j.setJarByClass(classOf[MatchJob])
    j.setMapOutputKeyClass(classOf[Text]); j.setMapOutputValueClass(classOf[Text])
    j.setOutputKeyClass(classOf[NullWritable]); j.setOutputValueClass(classOf[Text])
    j.setNumReduceTasks(a.reducers)
    LazyOutputFormat.setOutputFormatClass(j, classOf[TextOutputFormat[NullWritable, Text]])
    FileOutputFormat.setOutputPath(j, new Path(out))
    if a.compress then
      FileOutputFormat.setCompressOutput(j, true)
      FileOutputFormat.setOutputCompressorClass(j, classOf[GzipCodec])
    ErOut.All.foreach(n =>
      MultipleOutputs.addNamedOutput(
        j,
        n,
        classOf[TextOutputFormat[NullWritable, Text]],
        classOf[NullWritable],
        classOf[Text]
      )
    )
    j

  private def runJob(j: Job, out: String): Boolean =
    val ok = j.waitForCompletion(true)
    val counters = MatchJob.counters(j)
    counters.foreach((n, v) => log.info(s"[scholarchain] counter ${j.getJobName} $n=$v"))
    if ok then
      writeText(
        j.getConfiguration,
        s"$out/_counters.json",
        ujson.Obj.from(counters.map((n, v) => n -> ujson.Num(v.toDouble))).render()
      )
    ok

  private def profileJob(
      conf: Configuration,
      a: MatchArgs,
      up: UpstreamRun,
      p: MatchPaths
  ): Boolean =
    !prepare(conf, p.profiles) || {
      val j = job(conf, "ER-J1-endpoint-profiles", p.profiles, a)
      up.candidateGlobs.foreach(g =>
        MultipleInputs.addInputPath(
          j,
          new Path(g),
          classOf[TextInputFormat],
          classOf[CandidateRefMapper]
        )
      )
      MultipleInputs.addInputPath(
        j,
        new Path(up.worksGlob),
        classOf[TextInputFormat],
        classOf[WorkProfileMapper]
      )
      j.setPartitionerClass(classOf[EndpointPartitioner])
      j.setGroupingComparatorClass(classOf[EndpointGroupingComparator])
      j.setReducerClass(classOf[ProfileJoinReducer])
      runJob(j, p.profiles)
    }

  private def decideJob(
      conf: Configuration,
      a: MatchArgs,
      up: UpstreamRun,
      p: MatchPaths
  ): Boolean =
    !prepare(conf, p.decisions) || {
      val j = job(conf, "ER-J2-match-decisions", p.decisions, a)
      up.candidateGlobs.foreach(g =>
        MultipleInputs.addInputPath(
          j,
          new Path(g),
          classOf[TextInputFormat],
          classOf[CandidateLineMapper]
        )
      )
      if MatchJob.hasFiles(conf, s"${p.profiles}/part-*") then
        MultipleInputs.addInputPath(
          j,
          new Path(s"${p.profiles}/part-*"),
          classOf[TextInputFormat],
          classOf[SideLineMapper]
        )
      j.setReducerClass(classOf[DecideReducer])
      runJob(j, p.decisions)
    }

object MatchJob:
  /** Bumped on any change to the job logic, so a run before the change has another run ID. */
  val LogicVersion = "er-job-v1"

  /** Adjudication reason codes that also get a counter of their own. */
  val LlmCounterCodes: Set[String] =
    Set(
      "LLM_OFF",
      "LLM_BUDGET_EXHAUSTED",
      "LLM_CALL_FAILED",
      "LLM_INVALID_OUTPUT",
      "LLM_ABSTAINED",
      "LLM_LOW_CONFIDENCE"
    )

  def main(args: Array[String]): Unit =
    sys.exit(ToolRunner.run(new Configuration(), new MatchJob(), args))

  def hasFiles(conf: Configuration, glob: String): Boolean =
    val path = new Path(glob)
    Option(path.getFileSystem(conf).globStatus(path)).exists(_.nonEmpty)

  private def statuses(conf: Configuration, glob: String) =
    val path = new Path(glob)
    Option(path.getFileSystem(conf).globStatus(path))
      .map(_.toVector)
      .getOrElse(Vector.empty)
      .sortBy(_.getPath.toString)

  /** Lines of every file matching a glob (gzip read by extension). */
  def lines(conf: Configuration, glob: String): Vector[String] =
    val factory = new CompressionCodecFactory(conf)
    statuses(conf, glob).filter(_.isFile).flatMap { st =>
      val fs = st.getPath.getFileSystem(conf)
      val raw = fs.open(st.getPath)
      val in = Option(factory.getCodec(st.getPath))
        .fold[java.io.InputStream](raw)(_.createInputStream(raw))
      Using.resource(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))(
        _.lines.iterator.asScala.filter(_.nonEmpty).toVector
      )
    }

  /** Resolves the upstream run: its ID (the only manifest, unless given), snapshot, inputs and the
    * pipeline and normalization versions recorded in its manifest (the candidate provenance names
    * only the normalization version, so the manifest is the carrier of the pipeline version).
    */
  def upstream(conf: Configuration, a: MatchArgs): Either[String, UpstreamRun] =
    val root = a.runRoot.stripSuffix("/")
    val manifests = statuses(conf, s"$root/run-manifests/run-*.json")
      .map(_.getPath.getName)
      .filterNot(_.endsWith("-summary.json"))
      .map(_.stripSuffix(".json"))
    for
      runId <- a.runId
        .map(Right(_))
        .getOrElse(manifests match
          case Vector(one) => Right(one)
          case other       =>
            Left(
              s"expected one run manifest under $root/run-manifests, found ${other.size}; pass --run-id"
            ))
      manifestText = lines(conf, s"$root/run-manifests/$runId.json").mkString("\n")
      manifest <- scala.util
        .Try(ujson.read(manifestText).obj)
        .toEither
        .left
        .map(_ => s"run manifest of $runId is missing or not JSON")
      stageDirs = Vector("match-candidates/kind=exact", "match-candidates/kind=blocked", "export")
        .map(s =>
          statuses(conf, s"$root/$s/snapshot=*/run_id=$runId").filter(_.isDirectory).map(_.getPath)
        )
      _ <- Either.cond(
        stageDirs.forall(_.size == 1),
        (),
        s"run $runId needs exactly one candidate and export folder per stage"
      )
      dirs = stageDirs.map(_.head)
      _ <- dirs
        .find(d => !d.getFileSystem(conf).exists(new Path(d, "_SUCCESS")))
        .map(d => s"$d has no _SUCCESS")
        .toLeft(())
      snapshots = dirs.map(_.getParent.getName.stripPrefix("snapshot=")).distinct
      snapshot <- snapshots match
        case Vector(s) if Validate.snapshotDate(s) => Right(s)
        case other => Left(s"run $runId has snapshots ${other.mkString(",")}")
    yield UpstreamRun(
      runId,
      snapshot,
      Vector(s"${dirs(0)}/part-*", s"${dirs(1)}/part-*"),
      s"${dirs(2)}/works/part-*",
      manifest.get("pipeline").collect { case ujson.Str(s) => s }.getOrElse(""),
      manifest.get("normalization").collect { case ujson.Str(s) => s }.getOrElse("")
    )

  /** A cache path is an earlier decision run folder (its `adjudications/` lines) or one file. */
  def cacheGlob(conf: Configuration, path: String): Either[String, String] =
    val p = new Path(path)
    val fs = p.getFileSystem(conf)
    if !fs.exists(p) then Left(s"adjudication cache $path does not exist")
    else if fs.getFileStatus(p).isDirectory then
      Right(s"${path.stripSuffix("/")}/${ErOut.Adjudications}/part-*")
    else Right(path)

  def cacheDigest(conf: Configuration, glob: String): String =
    if glob.isEmpty then "" else Hashing.sha256Hex(lines(conf, glob).sorted.mkString("\n"))

  def readCache(conf: Configuration): Either[String, AdjudicationCache] =
    val glob = conf.get(ErKeys.CacheGlob, "")
    if glob.isEmpty then Right(new AdjudicationCache())
    else
      val (records, errors) = AdjudicationCache.parse(lines(conf, glob))
      if errors.nonEmpty then Left(s"adjudication cache: ${errors.head}")
      else Right(new AdjudicationCache(records))

  def counters(j: Job): Vector[(String, Long)] =
    j.getCounters
      .getGroup(ErCounters.Group)
      .iterator
      .asScala
      .map(c => c.getName -> c.getValue)
      .toVector
      .sortBy(_._1)

  /** Run summary from the decision job's counters: counts per outcome, kind and path, and the
    * unresolved rate (UNRESOLVED decisions over all decisions).
    */
  def summary(conf: Configuration, p: MatchPaths): ujson.Value =
    val c = lines(conf, s"${p.decisions}/_counters.json").headOption
      .map(j => ujson.read(j).obj.map((k, v) => k -> v.num.toLong).toMap)
      .getOrElse(Map.empty)
    val total = c.getOrElse(ErCounters.Decisions, 0L)
    def sum(o: MatchOutcome) = EntityKind.values.map(k => c.getOrElse(s"${k}_$o", 0L)).sum
    val byOutcome = MatchOutcome.values.toVector.map(o => o.toString -> ujson.Num(sum(o).toDouble))
    val byKind = for k <- EntityKind.values.toVector; o <- MatchOutcome.values.toVector
    yield s"${k}_$o" -> ujson.Num(c.getOrElse(s"${k}_$o", 0L).toDouble)
    val byPath = MatchPath.values.toVector.map(pt =>
      pt.toString -> ujson.Num(c.getOrElse(s"PATH_$pt", 0L).toDouble)
    )
    val unresolved = sum(MatchOutcome.UNRESOLVED)
    ujson.Obj(
      "runId" -> p.runId,
      "decisions" -> p.decisions,
      "total" -> total.toDouble,
      "byOutcome" -> ujson.Obj.from(byOutcome),
      "byKindOutcome" -> ujson.Obj.from(byKind),
      "byPath" -> ujson.Obj.from(byPath),
      "unresolvedRate" -> (if total == 0 then 0.0 else unresolved.toDouble / total),
      "counters" -> ujson.Obj.from(
        c.toVector.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))
      )
    )

/** Runs the entity-resolution jobs on this JVM with Hadoop's LocalJobRunner and the local file
  * system. It needs the Hadoop client runtime (Test scope in the build):
  *
  * `sbt "entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root <hadoop output>
  * --output <dir>"`
  */
object LocalMatchRunner:
  /** Hadoop scratch for a local run: the sibling `<output>-tmp`, outside the output root, so the
    * output tree holds only the run's results.
    */
  def scratchDir(output: String): java.nio.file.Path =
    val out = java.nio.file.Paths.get(output).toAbsolutePath.normalize
    out.resolveSibling(s"${out.getFileName}-tmp")

  def localConf(output: String): Configuration =
    val c = new Configuration()
    c.set("mapreduce.framework.name", "local")
    c.set("fs.defaultFS", "file:///")
    c.set("hadoop.tmp.dir", scratchDir(output).toString)
    c

  /** Runs the jobs and returns the exit code. The scratch folder is removed afterwards when this
    * run created it; a folder that existed before the run is left alone.
    */
  def run(args: Array[String], job: MatchJob = new MatchJob()): Int =
    val output = args.sliding(2).collectFirst { case Array("--output", v) => v }.getOrElse(".")
    val conf = localConf(output)
    val scratch = scratchDir(output)
    val created = !java.nio.file.Files.exists(scratch)
    try
      val code = ToolRunner.run(conf, job, args)
      if code == 0 then
        MatchArgs.parse(args.toSeq).toOption.foreach { _ =>
          val summaries =
            MatchJob.lines(conf, s"${output.stripSuffix("/")}/run-manifests/run-*-summary.json")
          summaries.foreach(println)
        }
      code
    finally if created then deleteTree(scratch)

  private def deleteTree(root: java.nio.file.Path): Unit =
    if java.nio.file.Files.exists(root) then
      val walk = java.nio.file.Files.walk(root)
      try
        walk
          .sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]())
          .forEach(p => java.nio.file.Files.deleteIfExists(p))
      finally walk.close()

  def main(args: Array[String]): Unit = sys.exit(run(args))
