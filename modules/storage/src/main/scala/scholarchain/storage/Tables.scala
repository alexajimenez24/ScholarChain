package scholarchain.storage

import scholarchain.core.*
import upickle.default.ReadWriter
import java.time.{Clock, Duration}
import scala.util.Try

/** A decoded item with its optimistic-lock version. */
final case class Versioned[A](value: A, version: Long)

enum Stored:
  case Created, Unchanged

/** Typed access to one table. Keys are built by the repositories, tables are named by
  * `DynamoConfig`, payloads are uPickle JSON. A malformed payload is a `Failure`, never an
  * exception.
  */
private[storage] final class TypedTable[A](kv: KeyValueStore, table: String, clock: Clock)(using
    rw: ReadWriter[A]
):
  private def now = clock.instant.getEpochSecond

  def decode(i: KvItem): Either[KvError, A] =
    Try(upickle.default.read[A](i.payload)).toEither.left
      .map(e => KvError.Failure(s"malformed item ${i.pk} / ${i.sk}: ${e.getMessage.take(120)}"))

  def create(pk: String, sk: String, a: A, ttl: Option[Long] = None): Either[KvError, Unit] =
    kv.putIfAbsent(table, KvItem(pk, sk, upickle.default.write(a), 1L, ttl))

  def replace(
      pk: String,
      sk: String,
      a: A,
      expected: Long,
      ttl: Option[Long] = None
  ): Either[KvError, Unit] =
    kv.replaceIfVersion(
      table,
      KvItem(pk, sk, upickle.default.write(a), expected + 1, ttl),
      expected
    )

  /** The live item (a TTL-expired item that DynamoDB has not yet deleted reads as absent). */
  def read(pk: String, sk: String): Either[KvError, Option[Versioned[A]]] =
    kv.get(table, pk, sk).left.map(KvError.Failure(_)).flatMap {
      case Some(i) if !i.isExpired(now) => decode(i).map(a => Some(Versioned(a, i.version)))
      case _                            => Right(None)
    }

  def list(pk: String, prefix: String, limit: Int): Either[KvError, Vector[Versioned[A]]] =
    kv.query(table, pk, prefix, limit).left.map(KvError.Failure(_)).flatMap { items =>
      items
        .filterNot(_.isExpired(now))
        .foldLeft[Either[KvError, Vector[Versioned[A]]]](Right(Vector.empty))((acc, i) =>
          acc.flatMap(xs => decode(i).map(a => xs :+ Versioned(a, i.version)))
        )
    }

  /** Idempotent create. Absent: `Created`. Present with the same value: `Unchanged`. Present with a
    * different value: `ConditionFailed` and nothing is overwritten. A present item whose TTL passed
    * is taken over with a version-guarded replace.
    */
  def putIdempotent(
      pk: String,
      sk: String,
      a: A,
      ttl: Option[Long] = None
  ): Either[KvError, Stored] =
    create(pk, sk, a, ttl) match
      case Right(_)                            => Right(Stored.Created)
      case Left(KvError.ConditionFailed(_, _)) =>
        kv.get(table, pk, sk).left.map(KvError.Failure(_)).flatMap {
          case None => Left(KvError.Failure(s"item $pk / $sk vanished during write"))
          case Some(i) if i.isExpired(now) =>
            replace(pk, sk, a, i.version, ttl).map(_ => Stored.Created)
          case Some(i) =>
            decode(i).flatMap(old =>
              if old == a then Right(Stored.Unchanged) else Left(KvError.ConditionFailed(pk, sk))
            )
        }
      case Left(other) => Left(other)

private def keyPart(label: String, v: String): Either[KvError, String] =
  if v.nonEmpty && !v.contains('#') then Right(v)
  else Left(KvError.Failure(s"$label must be non-empty and contain no '#'"))

// ---------------------------------------------------------------- RunLedger

enum LedgerStatus derives ReadWriter:
  case Started, Succeeded, Failed

/** The state of one stage of one run. */
final case class LedgerEntry(
    runId: String,
    stage: String,
    status: LedgerStatus,
    attempt: Int,
    logicVersion: String,
    outputUri: Option[String],
    outputSha256: Option[String],
    error: Option[String],
    updatedAt: String
) derives ReadWriter

/** One failed subject (a rejected vector key, a bad file) with its retry count. */
final case class FailureRecord(
    runId: String,
    stage: String,
    subject: String,
    reason: String,
    retryCount: Int,
    updatedAt: String
) derives ReadWriter

/** RunLedger.
  *
  * Keys: pk `run#{runId}`; sk `stage#{stage}` for the stage state, `fail#{stage}#{subject}` for a
  * failed subject. Access: (1) the state of a stage of a run: GetItem; (2) all stages of a run:
  * Query `stage#`; (3) the failures of a stage: Query `fail#{stage}#`. Conditions: `start` is
  * attribute_not_exists, a replay returns the stored entry; every transition and every retry count
  * is a version-guarded replace, so two workers cannot both advance one stage. No TTL: the ledger
  * is the audit trail of what ran.
  */
final class RunLedger(kv: KeyValueStore, table: String, clock: Clock = Clock.systemUTC()):
  private val t = new TypedTable[LedgerEntry](kv, table, clock)
  private val f = new TypedTable[FailureRecord](kv, table, clock)
  private def stamp = clock.instant.toString
  private def pk(runId: String) =
    Either.cond(Ids.isRunId(runId), s"run#$runId", KvError.Failure(s"invalid run ID $runId"))
  private def stageSk(stage: String) = keyPart("stage", stage).map(s => s"stage#$s")

  /** Starts a stage. A replay with the same logic version returns the stored entry unchanged. */
  def start(
      runId: String,
      stage: String,
      logicVersion: String
  ): Either[KvError, (Versioned[LedgerEntry], Stored)] =
    for
      p <- pk(runId)
      s <- stageSk(stage)
      entry = LedgerEntry(
        runId,
        stage,
        LedgerStatus.Started,
        1,
        logicVersion,
        None,
        None,
        None,
        stamp
      )
      r <- t.create(p, s, entry) match
        case Right(_)                            => Right(Versioned(entry, 1L) -> Stored.Created)
        case Left(KvError.ConditionFailed(_, _)) =>
          t.read(p, s).flatMap {
            case Some(v) if v.value.logicVersion == logicVersion => Right(v -> Stored.Unchanged)
            case _ => Left(KvError.ConditionFailed(p, s))
          }
        case Left(e) => Left(e)
    yield r

  private def move(
      cur: Versioned[LedgerEntry],
      from: Set[LedgerStatus],
      next: LedgerEntry
  ): Either[KvError, Versioned[LedgerEntry]] =
    if !from.contains(cur.value.status) then
      Left(KvError.Failure(s"illegal transition from ${cur.value.status} to ${next.status}"))
    else
      for
        p <- pk(cur.value.runId)
        s <- stageSk(cur.value.stage)
        _ <- t.replace(p, s, next, cur.version)
      yield Versioned(next, cur.version + 1)

  def succeed(
      cur: Versioned[LedgerEntry],
      outputUri: String,
      outputSha256: String
  ): Either[KvError, Versioned[LedgerEntry]] =
    move(
      cur,
      Set(LedgerStatus.Started),
      cur.value.copy(
        status = LedgerStatus.Succeeded,
        outputUri = Some(outputUri),
        outputSha256 = Some(outputSha256),
        error = None,
        updatedAt = stamp
      )
    )

  def fail(cur: Versioned[LedgerEntry], error: String): Either[KvError, Versioned[LedgerEntry]] =
    move(
      cur,
      Set(LedgerStatus.Started),
      cur.value.copy(status = LedgerStatus.Failed, error = Some(error.take(500)), updatedAt = stamp)
    )

  /** Failed to Started again, one attempt higher. */
  def retry(cur: Versioned[LedgerEntry]): Either[KvError, Versioned[LedgerEntry]] =
    move(
      cur,
      Set(LedgerStatus.Failed),
      cur.value.copy(
        status = LedgerStatus.Started,
        attempt = cur.value.attempt + 1,
        error = None,
        updatedAt = stamp
      )
    )

  def get(runId: String, stage: String): Either[KvError, Option[Versioned[LedgerEntry]]] =
    for p <- pk(runId); s <- stageSk(stage); r <- t.read(p, s) yield r

  def stages(runId: String): Either[KvError, Vector[Versioned[LedgerEntry]]] =
    pk(runId).flatMap(t.list(_, "stage#", 1000))

  /** Records a failed subject: first time count 1, then +1 per call (version-guarded). */
  def recordFailure(
      runId: String,
      stage: String,
      subject: String,
      reason: String
  ): Either[KvError, Versioned[FailureRecord]] =
    for
      p <- pk(runId)
      st <- keyPart("stage", stage)
      sub <- keyPart("subject", subject)
      s = s"fail#$st#$sub"
      rec = FailureRecord(runId, stage, subject, reason.take(500), 1, stamp)
      out <- f.create(p, s, rec) match
        case Right(_)                            => Right(Versioned(rec, 1L))
        case Left(KvError.ConditionFailed(_, _)) =>
          f.read(p, s).flatMap {
            case Some(cur) =>
              val next = cur.value.copy(
                reason = rec.reason,
                retryCount = cur.value.retryCount + 1,
                updatedAt = stamp
              )
              f.replace(p, s, next, cur.version).map(_ => Versioned(next, cur.version + 1))
            case None => Left(KvError.Failure(s"failure record $s vanished"))
          }
        case Left(e) => Left(e)
    yield out

  def failures(runId: String, stage: String): Either[KvError, Vector[Versioned[FailureRecord]]] =
    for p <- pk(runId); st <- keyPart("stage", stage); r <- f.list(p, s"fail#$st#", 1000) yield r

// ---------------------------------------------------------------- EntityLinkCache

/** The canonical researcher a source key resolved to under one ER policy version. */
final case class EntityLink(
    mentionKey: String,
    policyVersion: String,
    researcherId: String,
    decisionId: String,
    confidence: Double
) derives ReadWriter

/** EntityLinkCache.
  *
  * Keys: pk `link#{mentionKey}`, sk `policy#{policyVersion}`. Access: GetItem "what did policy P
  * resolve source key K to" (before spending another LLM adjudication). Condition:
  * attribute_not_exists; a repeat with the same link is accepted unchanged, a different link under
  * the same policy is refused. A new policy version is a new sort key, so entries of an old policy
  * are never read under a new one. No TTL: an entry is the stored result of a paid adjudication and
  * is inert, not stale, once the policy version moves on.
  */
final class EntityLinkCache(kv: KeyValueStore, table: String, clock: Clock = Clock.systemUTC()):
  private val t = new TypedTable[EntityLink](kv, table, clock)
  private def keys(mentionKey: String, policy: String) =
    for
      m <- keyPart("mentionKey", mentionKey)
      p <- keyPart("policyVersion", policy)
    yield (s"link#$m", s"policy#$p")

  def put(link: EntityLink): Either[KvError, Stored] =
    keys(link.mentionKey, link.policyVersion).flatMap((p, s) => t.putIdempotent(p, s, link))

  def get(mentionKey: String, policyVersion: String): Either[KvError, Option[EntityLink]] =
    keys(mentionKey, policyVersion).flatMap((p, s) => t.read(p, s).map(_.map(_.value)))

// ---------------------------------------------------------------- QuerySession

/** One question of a session and where its answer evidence is. */
final case class SessionTurn(
    sessionId: String,
    turn: Int,
    queryId: String,
    question: String,
    evidenceBundleId: Option[String],
    status: String
) derives ReadWriter

/** QuerySession.
  *
  * Keys: pk `session#{sessionId}`, sk `turn#{turn, 6 digits}`. Access: Query the turns of a session
  * in order (follow-up questions); GetItem one turn. Condition: attribute_not_exists, so a retried
  * request writes its turn once; the same turn again is accepted unchanged, a different turn at the
  * same number is refused. TTL: yes, `ttlSeconds` after the write (default 7 days): a session is
  * conversational scratch state, the answer's evidence lives in EvidenceBundle. The repository
  * hides expired items even before DynamoDB deletes them (deletion is typically within a few days
  * of expiry, docs/service-limits.md).
  */
final class QuerySessions(
    kv: KeyValueStore,
    table: String,
    clock: Clock = Clock.systemUTC(),
    ttlSeconds: Long = Duration.ofDays(7).toSeconds
):
  private val t = new TypedTable[SessionTurn](kv, table, clock)
  private def pk(sessionId: String) = keyPart("sessionId", sessionId).map(s => s"session#$s")
  private def sk(turn: Int) =
    Either.cond(
      turn >= 0 && turn <= 999999,
      f"turn#$turn%06d",
      KvError.Failure(s"turn $turn must be 0..999999")
    )

  def append(turn: SessionTurn): Either[KvError, Stored] =
    for
      p <- pk(turn.sessionId)
      s <- sk(turn.turn)
      r <- t.putIdempotent(p, s, turn, Some(clock.instant.getEpochSecond + ttlSeconds))
    yield r

  def turns(sessionId: String, limit: Int = 100): Either[KvError, Vector[SessionTurn]] =
    pk(sessionId).flatMap(t.list(_, "turn#", limit).map(_.map(_.value)))

// ---------------------------------------------------------------- EvidenceBundle

/** EvidenceBundle.
  *
  * Keys: pk `bundle#{bundleId}`, sk `schema#{schemaVersion}`. Access: GetItem by the bundle ID that
  * an `AnswerResponse` cites, to verify or replay its claims. The payload is the core
  * `EvidenceBundle` JSON, bounded by the 400 KB item limit (a larger bundle is refused, not
  * truncated). Condition: attribute_not_exists; the same bundle again is accepted unchanged, a
  * different bundle under the same ID is refused. No TTL: it is the evidence behind a published
  * claim.
  */
final class EvidenceBundles(kv: KeyValueStore, table: String, clock: Clock = Clock.systemUTC()):
  private val t = new TypedTable[EvidenceBundle](kv, table, clock)
  private def keys(bundleId: String, schema: String) =
    for
      b <- keyPart("bundleId", bundleId)
      s <- keyPart("schemaVersion", schema)
    yield (s"bundle#$b", s"schema#$s")

  def put(b: EvidenceBundle): Either[KvError, Stored] =
    if b.schemaVersion != SchemaVersions.Bundle then
      Left(KvError.Failure(s"unsupported bundle schema ${b.schemaVersion}"))
    else keys(b.bundleId, b.schemaVersion).flatMap((p, s) => t.putIdempotent(p, s, b))

  def get(bundleId: String): Either[KvError, Option[EvidenceBundle]] =
    keys(bundleId, SchemaVersions.Bundle).flatMap((p, s) => t.read(p, s).map(_.map(_.value)))

// ---------------------------------------------------------------- ArtifactRegistry

/** A stored artifact of a run: where it is and the hash it must have. */
final case class ArtifactRecord(
    kind: String,
    runId: String,
    uri: String,
    sha256: String,
    bytes: Long,
    schemaVersion: String,
    manifestUri: Option[String],
    sources: Vector[SourceRef]
) derives ReadWriter

/** Which run of a kind is the one to serve. */
final case class CurrentArtifact(kind: String, runId: String, uri: String, sha256: String)
    derives ReadWriter

/** ArtifactRegistry.
  *
  * Keys: pk `artifact#{kind}`; sk `run#{runId}` for a registered artifact, `current` for the
  * promoted one. Access: (1) resolve the artifact of kind K for run R: GetItem; (2) which run to
  * serve now: GetItem `current`; (3) all registered runs of a kind: Query `run#`. Conditions:
  * register is attribute_not_exists (same record again is unchanged, a different one is refused);
  * promote creates `current` or replaces it only when its version is the one read. Promoting an
  * unregistered run is refused. No TTL: it is the lineage index of stored data.
  */
final class ArtifactRegistry(kv: KeyValueStore, table: String, clock: Clock = Clock.systemUTC()):
  private val recs = new TypedTable[ArtifactRecord](kv, table, clock)
  private val cur = new TypedTable[CurrentArtifact](kv, table, clock)
  private def pk(kind: String) = keyPart("kind", kind).map(k => s"artifact#$k")
  private def runSk(runId: String) =
    Either.cond(Ids.isRunId(runId), s"run#$runId", KvError.Failure(s"invalid run ID $runId"))
  private val CurrentSk = "current"

  def register(r: ArtifactRecord): Either[KvError, Stored] =
    for p <- pk(r.kind); s <- runSk(r.runId); x <- recs.putIdempotent(p, s, r) yield x

  def get(kind: String, runId: String): Either[KvError, Option[ArtifactRecord]] =
    for p <- pk(kind); s <- runSk(runId); r <- recs.read(p, s) yield r.map(_.value)

  def runs(kind: String, limit: Int = 100): Either[KvError, Vector[ArtifactRecord]] =
    pk(kind).flatMap(recs.list(_, "run#", limit).map(_.map(_.value)))

  def promote(kind: String, runId: String): Either[KvError, Stored] =
    for
      p <- pk(kind)
      s <- runSk(runId)
      rec <- recs
        .read(p, s)
        .flatMap(_.toRight(KvError.Failure(s"$kind run $runId is not registered")))
      next = CurrentArtifact(kind, runId, rec.value.uri, rec.value.sha256)
      old <- cur.read(p, CurrentSk)
      r <- old match
        case None                       => cur.create(p, CurrentSk, next).map(_ => Stored.Created)
        case Some(o) if o.value == next => Right(Stored.Unchanged)
        case Some(o) => cur.replace(p, CurrentSk, next, o.version).map(_ => Stored.Created)
    yield r

  def current(kind: String): Either[KvError, Option[CurrentArtifact]] =
    pk(kind).flatMap(cur.read(_, CurrentSk).map(_.map(_.value)))

// ---------------------------------------------------------------- ResearcherMetricCache

/** ResearcherMetricCache (justified, see the report).
  *
  * Keys: pk `researcher#{researcherId}`, sk `metrics#{metricVersion}#{window label}`. Access: the
  * point read the query service makes per answered researcher (publication count, distinct
  * coauthors, weighted degree, community, bridge score) without an openCypher round trip. The
  * payload is the core `ResearcherMetrics` JSON of the Spark export. Condition:
  * attribute_not_exists; the same metrics again are unchanged, different metrics under the same
  * version are refused (a changed algorithm needs a new metricVersion). No TTL: a derived read
  * model, refreshed by a reload under a new metric version, never by expiry.
  */
final class ResearcherMetricCache(
    kv: KeyValueStore,
    table: String,
    clock: Clock = Clock.systemUTC()
):
  private val t = new TypedTable[ResearcherMetrics](kv, table, clock)
  private def keys(id: String, metricVersion: String, window: TimeWindow) =
    for
      i <- keyPart("researcherId", id)
      v <- keyPart("metricVersion", metricVersion)
    yield (s"researcher#$i", s"metrics#$v#${window.label}")

  def put(m: ResearcherMetrics): Either[KvError, Stored] =
    if m.schemaVersion != SchemaVersions.Metrics then
      Left(KvError.Failure(s"unsupported metrics schema ${m.schemaVersion}"))
    else keys(m.researcherId, m.metricVersion, m.window).flatMap((p, s) => t.putIdempotent(p, s, m))

  def get(
      researcherId: String,
      metricVersion: String,
      window: TimeWindow
  ): Either[KvError, Option[ResearcherMetrics]] =
    keys(researcherId, metricVersion, window).flatMap((p, s) => t.read(p, s).map(_.map(_.value)))

// ---------------------------------------------------------------- wiring

/** The six repositories over one store, named by `DynamoConfig`. */
final case class DynamoTables(
    runLedger: RunLedger,
    entityLinks: EntityLinkCache,
    sessions: QuerySessions,
    bundles: EvidenceBundles,
    artifacts: ArtifactRegistry,
    metrics: ResearcherMetricCache
)

object DynamoTables:
  def apply(
      kv: KeyValueStore,
      cfg: DynamoConfig,
      clock: Clock = Clock.systemUTC(),
      sessionTtlSeconds: Long = Duration.ofDays(7).toSeconds
  ): Either[String, DynamoTables] =
    val names = Vector(
      cfg.runLedgerTable,
      cfg.entityLinkCacheTable,
      cfg.querySessionTable,
      cfg.evidenceBundleTable,
      cfg.artifactRegistryTable,
      cfg.researcherMetricTable
    )
    if names.exists(_.trim.isEmpty) then Left("a DynamoDB table name is not configured")
    else if names.distinct.size != names.size then Left("DynamoDB table names must be distinct")
    else
      Right(
        DynamoTables(
          new RunLedger(kv, cfg.runLedgerTable, clock),
          new EntityLinkCache(kv, cfg.entityLinkCacheTable, clock),
          new QuerySessions(kv, cfg.querySessionTable, clock, sessionTtlSeconds),
          new EvidenceBundles(kv, cfg.evidenceBundleTable, clock),
          new ArtifactRegistry(kv, cfg.artifactRegistryTable, clock),
          new ResearcherMetricCache(kv, cfg.researcherMetricTable, clock)
        )
      )
