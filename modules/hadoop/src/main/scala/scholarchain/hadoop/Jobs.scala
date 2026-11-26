package scholarchain.hadoop

import scholarchain.core.*
import org.apache.hadoop.io.{LongWritable, NullWritable, Text, WritableComparator}
import org.apache.hadoop.mapreduce.{Mapper, Partitioner, Reducer, TaskInputOutputContext}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Configuration keys read by the tasks. */
object Keys:
  val RunId = "scholarchain.run.id"
  val Snapshot = "scholarchain.snapshot"
  val Source = "scholarchain.source"
  val MaxTeam = "scholarchain.max.team.size"
  val MaxBlock = "scholarchain.max.block.size"

/** Named outputs: one directory per kind under the stage output, `<name>/part-[mr]-NNNNN`. */
object Out:
  /** Reject records. The path segment `rejects-v1` is the reject format version. */
  val Rejects = "rejects"
  val RejectsPath = "rejects-v1/part"
  val Stats = "stats"
  val Works = "works"
  val Researchers = "researchers"
  val Topics = "topics"
  val Institutions = "institutions"
  val Abstracts = "abstracts"
  val Teams = "teams"
  val AuthorPapers = "authorpapers"

  /** Every name any job may write; each job registers all of them (empty ones create no file). */
  val All: Vector[String] =
    (Vector(
      Rejects,
      Stats,
      Works,
      Researchers,
      Topics,
      Institutions,
      Abstracts,
      Teams,
      AuthorPapers
    ) ++ ExportLogic.Kinds).distinct

/** Explicit partition strategy for every shuffle. The partition is a stable hash of the key up to
  * its first TAB (the whole key when there is none), modulo the reducer count. Text bytes hash with
  * Hadoop's own byte hash, so the result is the same on every JVM and run. Export keys put the work
  * ID before the TAB, which co-locates everything of one work (see [[ExportLogic]]).
  */
class KeyPartitioner extends Partitioner[Text, Text]:
  override def getPartition(key: Text, value: Text, numPartitions: Int): Int =
    KeyPartitioner.partition(key.getBytes, key.getLength, numPartitions)

object KeyPartitioner:
  def partition(bytes: Array[Byte], length: Int, numPartitions: Int): Int =
    var end = 0
    while end < length && bytes(end) != '\t'.toByte do end += 1
    (WritableComparator.hashBytes(bytes, 0, end) & Int.MaxValue) % numPartitions

  def partitionOf(key: String, numPartitions: Int): Int =
    val b = key.getBytes(StandardCharsets.UTF_8)
    partition(b, b.length, numPartitions)

/** Shared task helpers: counters, reject output and named outputs. Holds only per-task state: each
  * task attempt builds its own instance and its own `MultipleOutputs`.
  */
trait TaskSupport[KI, VI, KO, VO]:
  protected var mos: MultipleOutputs[NullWritable, Text] = scala.compiletime.uninitialized
  protected var run: RunContext = scala.compiletime.uninitialized
  protected val out = new Text

  protected def setupTask(ctx: TaskInputOutputContext[KI, VI, KO, VO]): Unit =
    mos = new MultipleOutputs(ctx.asInstanceOf[TaskInputOutputContext[?, ?, NullWritable, Text]])
    run = RunContext(
      ctx.getConfiguration.get(Keys.RunId),
      ctx.getConfiguration.get(Keys.Snapshot, "unknown")
    )

  protected def count(
      ctx: TaskInputOutputContext[KI, VI, KO, VO],
      name: String,
      n: Long = 1
  ): Unit =
    if n != 0 then ctx.getCounter(Ctr.Group, name).increment(n)

  protected def named(name: String, line: String): Unit =
    out.set(line); mos.write(name, NullWritable.get, out, s"$name/part")

  /** Writes one reject record and counts it; every reject path goes through here. */
  protected def reject(
      ctx: TaskInputOutputContext[KI, VI, KO, VO],
      stage: String,
      reason: String,
      input: String,
      schemaFailure: Boolean = false
  ): Unit =
    count(ctx, Ctr.Rejected)
    if schemaFailure then count(ctx, Ctr.SchemaFailure)
    out.set(JsonLines.encode(RejectRecord.of(stage, reason, input, run.runId)))
    mos.write(Out.Rejects, NullWritable.get, out, Out.RejectsPath)

  protected def closeTask(): Unit = mos.close()

private def valueStrings(values: java.lang.Iterable[Text]): Vector[String] =
  values.asScala.map(_.toString).toVector

// ------------------------------------------------------------------ J01 DBLP extraction

/** J01 map: one complete DBLP record in, one `workId -> WorkRecord JSON` out. */
class DblpExtractMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, record: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    val text = record.toString
    if offset.get < 0 then
      val (status, head) = DblpRecordReader.decodeRejected(text)
      if status == RecordStatus.UnsupportedEncoding.toString then
        count(ctx, "UNSUPPORTED_ENCODING")
        reject(ctx, "J01", s"unsupported encoding: $head", head)
      else
        val cs = "\\[charset=([^\\]]+)\\]$".r.findFirstMatchIn(head).map(m => s", ${m.group(1)}")
        reject(
          ctx,
          "J01",
          s"unreadable record ($status$cs) at byte ${-(offset.get + 1)}",
          head
        )
    else
      DblpParser.parseRecord(text) match
        case Left(reason) =>
          if reason.endsWith("without key") then count(ctx, Ctr.MissingKey)
          reject(ctx, "J01", reason, text)
        case Right(parsed) =>
          DblpParser.toWork(parsed, run) match
            case DblpParser.Outcome.Retained(w) =>
              val errors = Validate.work(w)
              if errors.nonEmpty then reject(ctx, "J01", errors.mkString("; "), text, true)
              else
                count(ctx, Ctr.Accepted)
                count(ctx, Ctr.InvalidIdentifier, DblpParser.invalidIdentifiers(parsed).toLong)
                count(ctx, s"DBLP_TYPE_${parsed.recordType.toUpperCase}")
                ctx.write(new Text(w.workId), new Text(JsonLines.encode(w)))
            case DblpParser.Outcome.Skipped(t) =>
              count(ctx, Ctr.Skipped); count(ctx, s"DBLP_SKIPPED_${t.toUpperCase}")
            case DblpParser.Outcome.Rejected(r) => reject(ctx, "J01", r, text)
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** Keeps one value per key (the smallest JSON, so the choice never depends on arrival order) and
  * counts the extra copies and the copies that differ.
  */
class UniqueReducer
    extends Reducer[Text, Text, NullWritable, Text]
    with TaskSupport[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val all = valueStrings(values)
    count(ctx, Ctr.Duplicate, all.size - 1L)
    if all.distinct.size > 1 then count(ctx, Ctr.Conflict)
    count(ctx, "UNIQUE_RECORDS")
    out.set(all.min); ctx.write(NullWritable.get, out)
  override def cleanup(ctx: Ctx): Unit = closeTask()

// ------------------------------------------------------------------ J02 source normalization

/** J02 map-only: one source JSON line in; works, researchers, topics, institutions and abstracts
  * out as named outputs. A record that fails mapping or validation is rejected whole.
  */
class SourceNormalizeMapper
    extends Mapper[LongWritable, Text, NullWritable, Text]
    with TaskSupport[LongWritable, Text, NullWritable, Text]:
  type Ctx = Mapper[LongWritable, Text, NullWritable, Text]#Context
  private var normalize: SourceNormalizers.Normalizer = scala.compiletime.uninitialized
  override def setup(ctx: Ctx): Unit =
    setupTask(ctx)
    val source = SourceType.valueOf(ctx.getConfiguration.get(Keys.Source))
    normalize =
      SourceNormalizers.forSource(source).fold(e => throw new IllegalStateException(e), identity)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    val text = line.toString
    if text.trim.nonEmpty then
      count(ctx, Ctr.Input)
      normalize(text, run) match
        case Left(reason) => reject(ctx, "J02", reason, text)
        case Right(n)     =>
          val errors = n.works.flatMap(Validate.work) ++ n.topics.flatMap(Validate.topic) ++
            n.institutions.flatMap(Validate.institution) ++
            n.researchers.flatMap(HadoopValidate.externalResearcher)
          if errors.nonEmpty then reject(ctx, "J02", errors.mkString("; "), text, true)
          else
            count(ctx, Ctr.Accepted)
            count(ctx, Ctr.InvalidIdentifier, n.invalidIdentifiers.toLong)
            n.works.foreach(w => named(Out.Works, JsonLines.encode(w)))
            n.researchers.foreach(r => named(Out.Researchers, JsonLines.encode(r)))
            n.topics.foreach(t => named(Out.Topics, JsonLines.encode(t)))
            n.institutions.foreach(i => named(Out.Institutions, JsonLines.encode(i)))
            n.abstracts.foreach(a => named(Out.Abstracts, JsonLines.encode(a)))
            count(ctx, "WORKS_WRITTEN", n.works.size.toLong)
            count(ctx, "RESEARCHERS_WRITTEN", n.researchers.size.toLong)
            count(ctx, "TOPICS_WRITTEN", n.topics.size.toLong)
            count(ctx, "INSTITUTIONS_WRITTEN", n.institutions.size.toLong)
            count(ctx, "ABSTRACTS_WRITTEN", n.abstracts.size.toLong)
  override def cleanup(ctx: Ctx): Unit = closeTask()

// ------------------------------------------------------------------ J03 exact identifier join

/** J03 map over works: `exact key -> IdRef JSON`. */
class WorkExactMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    JsonLines.decodeValid[WorkRecord](line.toString, Validate.work) match
      case Left(reason) => reject(ctx, "J03", reason, line.toString, true)
      case Right(w)     =>
        count(ctx, Ctr.Accepted)
        if ExactLogic.workKeys(w).isEmpty then count(ctx, Ctr.MissingKey)
        if w.doi.isEmpty then count(ctx, "MISSING_DOI")
        ExactLogic
          .exactKeys(w)
          .foreach((k, ref) => ctx.write(new Text(k), new Text(JsonLines.encode(ref))))
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J03 map over ORCID profiles: `exact key -> IdRef JSON`. */
class ProfileExactMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    JsonLines
      .decodeValid[ExternalResearcher](line.toString, HadoopValidate.externalResearcher) match
      case Left(reason) => reject(ctx, "J03", reason, line.toString, true)
      case Right(p)     =>
        count(ctx, Ctr.Accepted)
        ExactLogic
          .exactKeys(p)
          .foreach((k, ref) => ctx.write(new Text(k), new Text(JsonLines.encode(ref))))
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J03 reduce: all references under one exact key in, `MatchCandidate` lines out. */
class ExactJoinReducer
    extends Reducer[Text, Text, NullWritable, Text]
    with TaskSupport[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var maxGroup = 200
  override def setup(ctx: Ctx): Unit =
    setupTask(ctx); maxGroup = ctx.getConfiguration.getInt(Keys.MaxBlock, 200)
  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val decoded = valueStrings(values).map(v => v -> JsonLines.decode[ExactLogic.IdRef](v))
    decoded.collect { case (v, Left(reason)) =>
      reject(ctx, "J03", reason, v, true)
    }
    val refs = decoded.collect { case (_, Right(r)) => r }
    val r = ExactLogic.exactJoin(key.toString, refs, run, maxGroup)
    r.oversize.foreach { reason =>
      count(ctx, Ctr.OversizeBlock); reject(ctx, "J03", reason, key.toString)
    }
    count(ctx, Ctr.Conflict, r.conflicts.toLong)
    count(ctx, Ctr.Duplicate, r.duplicates.toLong)
    count(ctx, Ctr.ExactCandidates, r.candidates.size.toLong)
    r.candidates.foreach(c => { out.set(JsonLines.encode(c)); ctx.write(NullWritable.get, out) })
  override def cleanup(ctx: Ctx): Unit = closeTask()

// ------------------------------------------------------------------ J04 blocking and features

/** J04 map: a work in; `r:<name key> -> MentionSummary` for each author and
  * `wk:<year>:<tokens> -> WorkSummary` for the work out.
  */
class BlockingMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    JsonLines.decodeValid[WorkRecord](line.toString, Validate.work) match
      case Left(reason) => reject(ctx, "J04", reason, line.toString, true)
      case Right(w)     =>
        count(ctx, Ctr.Accepted)
        val keyed = BlockLogic.mentionBlocks(w)
        val missing = (w.authors.size - keyed.size).toLong
        count(ctx, Ctr.MissingKey, missing)
        count(ctx, "MISSING_NAME_KEY", missing)
        keyed.foreach((k, s) => ctx.write(new Text("r:" + k), new Text(JsonLines.encode(s))))
        BlockLogic.workBlock(w) match
          case Some((k, s)) => ctx.write(new Text(k), new Text(JsonLines.encode(s)))
          case None         => count(ctx, Ctr.MissingKey); count(ctx, "MISSING_TITLE_KEY")
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J04 reduce: one block in, `MatchCandidate` lines out. Values are streamed and buffered only up
  * to the hard cap, so a pathological common-name block cannot exhaust reducer memory.
  */
class BlockingReducer
    extends Reducer[Text, Text, NullWritable, Text]
    with TaskSupport[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var maxBlock = 200
  private var largest = 0
  private var largestKey = ""
  override def setup(ctx: Ctx): Unit =
    setupTask(ctx); maxBlock = ctx.getConfiguration.getInt(Keys.MaxBlock, 200)

  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val k = key.toString
    val cap = BlockLogic.hardCap(maxBlock)
    val buffered = Vector.newBuilder[String]
    var total = 0
    val it = values.iterator()
    while it.hasNext do
      val v = it.next().toString
      total += 1
      if total <= cap then buffered += v
    if total > largest then { largest = total; largestKey = k }
    if total > cap then
      count(ctx, Ctr.OversizeBlock)
      reject(ctx, "J04", s"block $k has $total members > hard cap $cap; not expanded", k)
    else if k.startsWith("wk:") then reduceWorks(k, buffered.result(), ctx)
    else reduceMentions(k, buffered.result(), ctx)

  private def decodeAll[T: upickle.default.Reader: upickle.default.Writer](
      lines: Vector[String],
      ctx: Ctx
  ): Vector[T] =
    lines.flatMap { l =>
      JsonLines.decode[T](l) match
        case Right(v)     => Some(v)
        case Left(reason) => reject(ctx, "J04", reason, l, true); None
    }

  private def refuse(counter: String, reason: String, ctx: Ctx): Unit =
    count(ctx, counter)
    reject(ctx, "J04", reason, reason)

  private def emit(r: BlockLogic.BlockResult, ctx: Ctx): Unit =
    r.oversize.foreach { reason =>
      refuse(if r.fallback then Ctr.FallbackRefused else Ctr.OversizeBlock, reason, ctx)
    }
    if r.fallback && r.oversize.isEmpty then count(ctx, Ctr.FallbackCompared)
    count(ctx, Ctr.BlockedCandidates, r.candidates.size.toLong)
    r.candidates.foreach(c => { out.set(JsonLines.encode(c)); ctx.write(NullWritable.get, out) })

  private def reduceMentions(k: String, lines: Vector[String], ctx: Ctx): Unit =
    val members = decodeAll[BlockLogic.MentionSummary](lines, ctx)
    val key = k.stripPrefix("r:")
    val results = BlockLogic.block(key, members, maxBlock, run)
    // one OVERSIZE_BLOCKS and one reject line per block above the cap; its parts are folded in
    BlockLogic.primaryRefusal(key, results, maxBlock).foreach(refuse(Ctr.OversizeBlock, _, ctx))
    results.foreach(r => emit(if r.fallback then r else r.copy(oversize = None), ctx))

  private def reduceWorks(k: String, lines: Vector[String], ctx: Ctx): Unit =
    val members = decodeAll[BlockLogic.WorkSummary](lines, ctx)
    BlockLogic.workBlockResult(k, members, maxBlock, run).foreach(emit(_, ctx))

  override def cleanup(ctx: Ctx): Unit =
    named(
      Out.Stats,
      ujson.Obj("stat" -> "largestBlock", "key" -> largestKey, "members" -> largest).render()
    )
    closeTask()

// ------------------------------------------------------------------ J05 publication teams

/** J05 map: a work in; `workId -> work JSON` out (works without authors are skipped, counted). */
class TeamMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    JsonLines.decodeValid[WorkRecord](line.toString, Validate.work) match
      case Left(reason)                  => reject(ctx, "J05", reason, line.toString, true)
      case Right(w) if w.authors.isEmpty =>
        count(ctx, Ctr.Skipped); count(ctx, Ctr.WorksWithoutAuthors)
      case Right(w) => count(ctx, Ctr.Accepted); ctx.write(new Text(w.workId), line)
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J05 reduce: one team record and the author-paper records per work. */
class TeamReducer
    extends Reducer[Text, Text, NullWritable, Text]
    with TaskSupport[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var maxSize = 0
  private var maxWork = ""
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    val lines = valueStrings(values)
    count(ctx, Ctr.Duplicate, lines.size - 1L)
    if lines.distinct.size > 1 then count(ctx, Ctr.Conflict)
    JsonLines.decode[WorkRecord](lines.min) match
      case Left(reason) => reject(ctx, "J05", reason, lines.min, true)
      case Right(w)     =>
        TeamLogic.team(w, run).foreach { (team, papers) =>
          count(ctx, TeamLogic.bucket(team.teamSize))
          count(ctx, Ctr.PairsBeforeCap, TeamLogic.pairCount(team.teamSize.toLong))
          if team.teamSize > maxSize then { maxSize = team.teamSize; maxWork = team.workId }
          named(Out.Teams, JsonLines.encode(team))
          papers.foreach(p => named(Out.AuthorPapers, JsonLines.encode(p)))
          count(ctx, "TEAMS_WRITTEN"); count(ctx, "AUTHOR_PAPERS_WRITTEN", papers.size.toLong)
        }
  override def cleanup(ctx: Ctx): Unit =
    named(
      Out.Stats,
      ujson.Obj("stat" -> "maxTeamSize", "workId" -> maxWork, "teamSize" -> maxSize).render()
    )
    closeTask()

// ------------------------------------------------------------------ J06 coauthor pair expansion

/** J06 map: a team in; its n(n-1)/2 pairs out as `mentionA|mentionB|workId -> pair JSON`. A team
  * above the cap is counted (OVERSIZE_TEAMS, PAIRS_REFUSED_BY_CAP) and rejected with its reason.
  */
class PairMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var maxTeam = 300
  override def setup(ctx: Ctx): Unit =
    setupTask(ctx); maxTeam = ctx.getConfiguration.getInt(Keys.MaxTeam, 300)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    JsonLines.decodeValid[PublicationTeam](line.toString, Validate.team) match
      case Left(reason) => reject(ctx, "J06", reason, line.toString, true)
      case Right(team)  =>
        TeamLogic.pairs(team, maxTeam, run) match
          case Left(reason) =>
            count(ctx, Ctr.OversizeTeam)
            count(ctx, Ctr.PairsRefused, TeamLogic.pairCount(team.teamSize.toLong))
            reject(ctx, "J06", reason, team.workId)
          case Right(ps) =>
            count(ctx, Ctr.Accepted)
            count(ctx, Ctr.EmittedPair, ps.size.toLong)
            ps.foreach(p =>
              ctx.write(new Text(TeamLogic.pairKey(p)), new Text(JsonLines.encode(p)))
            )
  override def cleanup(ctx: Ctx): Unit = closeTask()

// ------------------------------------------------------------------ J07 graph export

/** J07 map over works: the work, one row per mention and the venue, keyed for export. */
class ExportWorkMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    JsonLines.decodeValid[WorkRecord](line.toString, Validate.work) match
      case Left(reason) => reject(ctx, "J07", reason, line.toString, true)
      case Right(w)     =>
        count(ctx, Ctr.Accepted)
        ExportLogic.workRows(w, run).foreach((k, v) => ctx.write(new Text(k), new Text(v)))
  override def cleanup(ctx: Ctx): Unit = closeTask()

/** J07 map over a record kind that exports unchanged. Subclasses name the kind and its key. */
abstract class ExportPassMapper
    extends Mapper[LongWritable, Text, Text, Text]
    with TaskSupport[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context

  /** The shuffle key of a valid record, or the reasons it is invalid. */
  protected def keyOf(line: String): Either[String, String]
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def map(offset: LongWritable, line: Text, ctx: Ctx): Unit =
    count(ctx, Ctr.Input)
    keyOf(line.toString) match
      case Left(reason) => reject(ctx, "J07", reason, line.toString, true)
      case Right(k)     => count(ctx, Ctr.Accepted); ctx.write(new Text(k), line)
  override def cleanup(ctx: Ctx): Unit = closeTask()

class ExportTeamMapper extends ExportPassMapper:
  override protected def keyOf(line: String): Either[String, String] =
    JsonLines
      .decodeValid[PublicationTeam](line, Validate.team)
      .map(t => ExportLogic.key(t.workId, ExportLogic.Teams, t.workId))

class ExportPairMapper extends ExportPassMapper:
  override protected def keyOf(line: String): Either[String, String] =
    JsonLines
      .decodeValid[CoauthorPairCandidate](line, Validate.pair)
      .map(p => ExportLogic.key(p.workId, ExportLogic.Pairs, TeamLogic.pairKey(p)))

class ExportAuthorPaperMapper extends ExportPassMapper:
  override protected def keyOf(line: String): Either[String, String] =
    JsonLines
      .decodeValid[AuthorPaper](line, HadoopValidate.authorPaper)
      .map(a => ExportLogic.key(a.workId, ExportLogic.AuthorPapers, a.mentionId))

class ExportTopicMapper extends ExportPassMapper:
  override protected def keyOf(line: String): Either[String, String] =
    JsonLines
      .decodeValid[Topic](line, Validate.topic)
      .map(t => ExportLogic.key(t.topicId, ExportLogic.Topics, t.topicId))

class ExportInstitutionMapper extends ExportPassMapper:
  override protected def keyOf(line: String): Either[String, String] =
    JsonLines
      .decodeValid[Institution](line, Validate.institution)
      .map(i => ExportLogic.key(i.institutionId, ExportLogic.Institutions, i.institutionId))

/** J07 reduce: one record id in, one line out to the `<kind>/` family; duplicates and conflicting
  * copies are counted, and the smallest JSON wins.
  */
class ExportReducer
    extends Reducer[Text, Text, NullWritable, Text]
    with TaskSupport[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  override def setup(ctx: Ctx): Unit = setupTask(ctx)
  override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
    // One pass, constant memory: a venue or topic key can hold one value per work.
    val it = values.iterator()
    var first: String = null
    var smallest: String = null
    var n = 0L
    var differs = false
    while it.hasNext do
      val v = it.next().toString
      if first == null then { first = v; smallest = v }
      else
        if v != first then differs = true
        if v < smallest then smallest = v
      n += 1
    ExportLogic.kindAndId(key.toString) match
      case None => reject(ctx, "J07", "export key without a known kind", key.toString, true)
      case Some((kind, _)) =>
        count(ctx, Ctr.Duplicate, n - 1L)
        if differs then count(ctx, Ctr.Conflict)
        count(ctx, s"EXPORTED_${kind.toUpperCase}")
        named(kind, smallest)
  override def cleanup(ctx: Ctx): Unit = closeTask()
