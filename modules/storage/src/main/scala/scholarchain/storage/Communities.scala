package scholarchain.storage

import scholarchain.core.*
import java.time.Clock

/** The size of one community in one window, as Spark counted it, with the exported evidence row it
  * came from (provenance: run, snapshot, graph policy version, source table URI).
  */
final case class CommunitySize(
    communityId: String,
    window: TimeWindow,
    size: Long,
    evidence: EvidenceItem
):
  def runId: String = evidence.provenance.runId

object CommunitySize:
  val RecordKindKey = "recordKind"
  val CommunityKind = "community"
  val CrossEdgeKind = "cross_community_edge"

  private val Label = """(\d{4})-(\d{4})""".r

  /** The checks every row of the `communities` table must pass, of either record kind. */
  def tableRow(e: EvidenceItem): Either[String, EvidenceItem] =
    val errors = Validate.evidence(e)
    if errors.nonEmpty then Left(errors.mkString("; "))
    else
      Either.cond(
        e.evidenceType == EvidenceType.COMMUNITY_RECORD,
        e,
        s"evidence type ${e.evidenceType} is not COMMUNITY_RECORD"
      )

  /** Reads a `recordKind=community` row. A missing or malformed value is a `Left`, never filled. */
  def of(e: EvidenceItem): Either[String, CommunitySize] =
    def value(k: String) = e.values.get(k).filter(_.nonEmpty).toRight(s"no value '$k'")
    for
      _ <- tableRow(e)
      kind <- value(RecordKindKey)
      _ <- Either.cond(kind == CommunityKind, (), s"record kind $kind is not $CommunityKind")
      id <- value("communityId")
      _ <- Either.cond(!id.contains('#'), (), "communityId contains '#'")
      label <- value("window")
      window <- label match
        case Label(a, b) if Validate.window(TimeWindow(a.toInt, b.toInt)).isEmpty =>
          Right(TimeWindow(a.toInt, b.toInt))
        case _ => Left(s"bad window '$label'")
      raw <- value("communitySize")
      size <- raw.toLongOption.filter(_ >= 1).toRight(s"communitySize '$raw' is not >= 1")
    yield CommunitySize(id, window, size, e)

/** Read-only port of the runtime: one community of one run and window. `None` means the community
  * is not stored (never "size 0"); a store failure or a malformed item is a `Left`.
  */
trait CommunityReader:
  def community(
      runId: String,
      communityId: String,
      window: TimeWindow
  ): Either[KvError, Option[CommunitySize]]

/** DynamoDB adapter, over the [[KeyValueStore]] port. It lives in the ResearcherMetricCache table
  * (`dynamo.researcherMetricTable`, the other Spark-derived read model), so it needs no new config
  * field or table; its partition keys cannot meet the `researcher#` ones.
  *
  * Keys: pk `community#{communityId}`, sk `run#{runId}#window#{window label}`. Access: GetItem of
  * the community a metrics row names, for the run being served. Payload: the exported core
  * `EvidenceItem` JSON. Condition: attribute_not_exists; the same row again is unchanged, a
  * different row under the same key is refused. The run is in the key, so a later run never meets
  * an earlier run's item. No TTL: a derived read model.
  */
final class CommunityCache(kv: KeyValueStore, table: String, clock: Clock = Clock.systemUTC())
    extends CommunityReader:
  private val t = new TypedTable[EvidenceItem](kv, table, clock)
  private def keys(runId: String, communityId: String, window: TimeWindow) =
    for
      r <- Either.cond(Ids.isRunId(runId), runId, KvError.Failure(s"invalid run ID $runId"))
      c <- keyPart("communityId", communityId)
    yield (s"community#$c", s"run#$r#window#${window.label}")

  /** Create-only write of one `recordKind=community` row; its keys come from the row itself. */
  def put(row: EvidenceItem): Either[KvError, Stored] =
    for
      c <- CommunitySize.of(row).left.map(KvError.Failure(_))
      k <- keys(c.runId, c.communityId, c.window)
      stored <- t.putIdempotent(k._1, k._2, row)
    yield stored

  def community(
      runId: String,
      communityId: String,
      window: TimeWindow
  ): Either[KvError, Option[CommunitySize]] =
    keys(runId, communityId, window).flatMap { (pk, sk) =>
      t.read(pk, sk).flatMap {
        case None    => Right(None)
        case Some(v) =>
          CommunitySize.of(v.value) match
            case Right(c)
                if c.runId == runId && c.communityId == communityId && c.window == window =>
              Right(Some(c))
            case Right(_) => Left(KvError.Failure(s"item $pk / $sk holds another community"))
            case Left(e)  => Left(KvError.Failure(s"malformed item $pk / $sk: $e"))
      }
    }

/** What a community load changed; `otherKindRows` are the cross-community edge rows of the same
  * table, which this loader does not store.
  */
final case class CommunityLoadResult(
    read: Long,
    created: Long,
    unchanged: Long,
    otherKindRows: Long,
    duplicatesDropped: Long,
    rejected: Vector[RejectRecord],
    report: StoredObject,
    reportOutcome: WriteOutcome,
    replay: Boolean
)

/** Loads the Spark `communities` JSON-lines table (drop `windowLabel` and `recordKind`) into
  * [[CommunityCache]].
  *
  * Idempotency: create-only per (community, run, window) with a same-value check. Within one input
  * an identical duplicate is dropped and counted and conflicting copies are all rejected.
  * `expectedRows` counts every row of the given source (both kinds when the whole table is read)
  * and is checked before any write, as is an input problem such as a missing directory.
  */
final class CommunityLoader(cache: CommunityCache, ledger: RunLedger, writer: ArtifactWriter):
  val Stage = "load-communities"
  val LogicVersion = "communities-load-v1"
  private val stage = new LoadStage(ledger, writer)

  def load(
      runId: String,
      source: RowSource[EvidenceItem],
      expectedRows: Option[Long] = None
  ): Either[String, CommunityLoadResult] =
    stage
      .run(runId, Stage, LogicVersion) {
        val problem = source.problem
        val all = if problem.isDefined then Vector.empty else source.rows.toVector
        if problem.isDefined then Left(problem.get)
        else if expectedRows.exists(_ != all.size.toLong) then
          Left(s"expected ${expectedRows.get} rows, the source gave ${all.size}")
        else
          val rejects = Vector.newBuilder[RejectRecord]
          def reject(input: String, reason: String): Unit =
            rejects += RejectRecord.of(Stage, reason, input, runId)
          var otherKinds = 0L
          val decoded: Vector[CommunitySize] = all.zipWithIndex.flatMap {
            case (Left(e), i)    => reject(s"row $i", s"malformed row: $e"); None
            case (Right(row), _) =>
              val checked = CommunitySize
                .tableRow(row)
                .filterOrElse(
                  _.provenance.runId == runId,
                  s"row belongs to run ${row.provenance.runId}, not $runId"
                )
              checked match
                case Left(e) => reject(row.evidenceId, e); None
                case Right(_)
                    if row.values
                      .get(CommunitySize.RecordKindKey)
                      .contains(CommunitySize.CrossEdgeKind) =>
                  otherKinds += 1
                  None
                case Right(_) =>
                  CommunitySize.of(row) match
                    case Left(e)  => reject(row.evidenceId, e); None
                    case Right(c) => Some(c)
          }
          val groups = decoded.groupBy(c => (c.communityId, c.window.label))
          val distinctRows = groups.toVector.sortBy(_._1).flatMap { (k, cs) =>
            if cs.distinct.size == 1 then Some(cs.head)
            else
              cs.foreach(c => reject(c.evidence.evidenceId, s"conflicting duplicate rows for $k"))
              None
          }
          val dropped =
            groups.values.map(cs => if cs.distinct.size == 1 then cs.size - 1 else 0).sum.toLong
          var created = 0L
          var unchanged = 0L
          var fatal: Option[String] = None
          distinctRows.foreach { c =>
            if fatal.isEmpty then
              cache.put(c.evidence) match
                case Right(Stored.Created)               => created += 1
                case Right(Stored.Unchanged)             => unchanged += 1
                case Left(KvError.ConditionFailed(_, _)) =>
                  reject(
                    c.evidence.evidenceId,
                    s"a different record is stored for ${c.communityId} ${c.window.label}"
                  )
                case Left(KvError.Failure(msg)) => fatal = Some(msg)
          }
          fatal.toLeft(()).map { _ =>
            val rejected = rejects.result()
            val report = LoadReport.of(
              "communities",
              runId,
              LogicVersion,
              Seq(
                "read" -> all.size.toLong,
                "stored" -> (created + unchanged),
                "otherKindRows" -> otherKinds,
                "duplicatesDropped" -> dropped,
                "rejected" -> rejected.size.toLong
              ),
              rejected
            )
            (
              (all.size.toLong, created, unchanged, otherKinds, dropped, report.rejected),
              report
            )
          }
      }
      .map { o =>
        val (read, created, unchanged, otherKinds, dropped, rejected) = o.value
        CommunityLoadResult(
          read,
          created,
          unchanged,
          otherKinds,
          dropped,
          rejected,
          o.report,
          o.reportOutcome,
          o.replay
        )
      }
