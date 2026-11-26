package scholarchain.services.query

import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.services.common.*
import scholarchain.storage.EvidenceBundles

/** Port: the Graph-RAG workflow (Phase 09 `CollaborationWorkflow`). A fake serves tests. */
trait QueryEngine:
  def run(queryId: String, req: AnalysisRequest): Either[WorkflowError, WorkflowResult]

final class WorkflowEngine(wf: CollaborationWorkflow) extends QueryEngine:
  def run(queryId: String, req: AnalysisRequest): Either[WorkflowError, WorkflowResult] =
    wf.run(queryId, req)

/** Port: where an asynchronous job runs. Production starts a fiber; tests queue and drain. */
trait JobScheduler:
  def submit(job: IO[Unit]): IO[Unit]

object FiberScheduler extends JobScheduler:
  def submit(job: IO[Unit]): IO[Unit] = job.handleError(_ => ()).start.void

enum JobState(val wire: String, val terminal: Boolean):
  case Queued extends JobState("queued", false)
  case Running extends JobState("running", false)
  case Succeeded extends JobState("succeeded", true)
  case Failed extends JobState("failed", true)

/** One query and its job (the job ID is the query ID's suffix, so the two never drift). */
final case class QueryRecord(
    queryId: String,
    owner: String,
    mode: QueryMode,
    state: JobState,
    stage: String,
    createdAt: Long,
    updatedAt: Long,
    answer: Option[AnswerResponse],
    error: Option[ApiError]
):
  def jobId: String = QueryIds.jobOf(queryId)

object QueryIds:
  private val Shape = "^(qry|job)-[A-Za-z0-9_-]{1,64}$".r
  def jobOf(queryId: String): String = "job-" + queryId.stripPrefix("qry-")
  def queryOf(jobId: String): String = "qry-" + jobId.stripPrefix("job-")
  def valid(id: String): Boolean = Shape.matches(id)
  def fresh: String = "qry-" + java.util.UUID.randomUUID.toString.replace("-", "")
  def derived(owner: String, key: String): String =
    "qry-" + Hashes.sha256Hex(s"$owner|$key").take(32)

/** In-memory query records and idempotency claims (the DynamoDB repository belongs to storage and
  * is not built in this phase). Bounded: a full store refuses new queries with 503.
  */
enum Claim:
  case Fresh, Replay, Mismatch

final class QueryStore(maxRecords: Int = 1000):
  private val records = TrieMap.empty[String, QueryRecord]
  private val claims = TrieMap.empty[(String, String), String]

  def claim(owner: String, key: String, fingerprint: String): Claim = synchronized {
    claims.putIfAbsent((owner, key), fingerprint) match
      case None                        => Claim.Fresh
      case Some(f) if f == fingerprint => Claim.Replay
      case Some(_)                     => Claim.Mismatch
  }
  def release(owner: String, key: String): Unit = claims.remove((owner, key)): Unit
  def size: Int = records.size

  /** Adds a record. A full store first evicts the oldest terminal (Succeeded or Failed) record and
    * its idempotency claim; it refuses (false) only when every record is still in flight.
    */
  def add(r: QueryRecord): Boolean = synchronized {
    if records.size >= maxRecords && !records.contains(r.queryId) then evictOldestTerminal()
    if records.size >= maxRecords && !records.contains(r.queryId) then false
    else
      records.put(r.queryId, r)
      true
  }

  private def evictOldestTerminal(): Unit =
    val terminal =
      records.values.filter(x => x.state == JobState.Succeeded || x.state == JobState.Failed)
    if terminal.nonEmpty then
      val old = terminal.minBy(_.updatedAt)
      records.remove(old.queryId)
      claims.keys
        .filter((o, k) => QueryIds.derived(o, k) == old.queryId)
        .foreach(claims.remove(_): Unit)
  def remove(queryId: String): Unit = synchronized(records.remove(queryId): Unit)
  def get(queryId: String): Option[QueryRecord] = records.get(queryId)
  def update(queryId: String)(f: QueryRecord => QueryRecord): Unit =
    synchronized(records.get(queryId).foreach(r => records.put(queryId, f(r)): Unit))

/** Evidence ID to bundle ID, filled as workflows finish; the adapter below reads the stored bundle.
  * Storage has no per-evidence index, so this one is rebuilt only from the queries this process
  * answered.
  */
final class EvidenceIndex:
  private val byId = new ConcurrentHashMap[String, String]()
  def put(evidenceId: String, bundleId: String): Unit = byId.put(evidenceId, bundleId): Unit
  def bundleOf(evidenceId: String): Option[String] = Option(byId.get(evidenceId))

final class BundleEvidenceLookup(bundles: EvidenceBundles, index: EvidenceIndex)
    extends EvidenceLookup:
  def get(evidenceId: String): Either[String, Option[EvidenceItem]] =
    index.bundleOf(evidenceId) match
      case None     => Right(None)
      case Some(bd) =>
        bundles.get(bd).left.map(_.toString).map(_.flatMap(_.byId.get(evidenceId)))

/** Behavior behind the routes: validation results in, answers and typed errors out. */
final class QueryService(
    engine: QueryEngine,
    audit: AuditLog,
    bundles: EvidenceBundles,
    workflow: Downstream,
    stores: Downstream,
    scheduler: JobScheduler,
    store: QueryStore,
    index: EvidenceIndex,
    clock: IO[Long] = IO.realTime.map(_.toMillis),
    maxPendingJobs: Int = 8
):
  private val pending = new AtomicInteger(0)

  /** Starts (async) or runs (sync) a query. `replayed` is true when the Idempotency-Key matched an
    * earlier identical request.
    */
  def submit(p: Principal, q: ParsedQuery, key: Option[String]): IO[(QueryRecord, Boolean)] =
    val queryId = key.fold(QueryIds.fresh)(QueryIds.derived(p.subject, _))
    key.map(store.claim(p.subject, _, q.fingerprint)) match
      case Some(Claim.Mismatch) =>
        ApiException.raise(
          ApiError(
            ErrorCode.Conflict,
            "the Idempotency-Key was already used with a different request"
          )
        )
      case Some(Claim.Replay) =>
        store.get(queryId).fold(inProgress)(r => IO.pure((r, true)))
      case _ => start(p, q, key, queryId).map(_ -> false)

  private def inProgress[A]: IO[A] =
    ApiException.raise(ApiError(ErrorCode.Conflict, "the original request is still being accepted"))

  private def unavailable[A](why: String): IO[A] =
    ApiException.raise(ApiError(ErrorCode.Unavailable, why))

  private def start(
      p: Principal,
      q: ParsedQuery,
      key: Option[String],
      queryId: String
  ): IO[QueryRecord] =
    def undo = IO(key.foreach(store.release(p.subject, _)))
    clock.flatMap { now =>
      val rec =
        QueryRecord(queryId, p.subject, q.mode, JobState.Queued, "queued", now, now, None, None)
      def run: IO[QueryRecord] = q.mode match
        case QueryMode.Sync =>
          execute(rec, q.request, key) *> IO(store.get(queryId).getOrElse(rec))
        case QueryMode.Async =>
          scheduler
            .submit(execute(rec, q.request, key).guarantee(IO {
              pending.decrementAndGet(); ()
            }))
            .as(rec)
      // The pending bound is checked before the record exists, so a refusal leaves nothing behind.
      val reserved = q.mode == QueryMode.Async
      if reserved && pending.incrementAndGet() > maxPendingJobs then
        IO(pending.decrementAndGet()) *> undo *> unavailable("too many jobs are pending")
      else if !store.add(rec) then
        IO(if reserved then pending.decrementAndGet()) *> undo *>
          unavailable("the query store is full")
      else run
    }

  private def touch(queryId: String)(f: QueryRecord => QueryRecord): IO[Unit] =
    clock.flatMap(n => IO(store.update(queryId)(r => f(r).copy(updatedAt = n))))

  private def execute(rec: QueryRecord, req: AnalysisRequest, key: Option[String]): IO[Unit] =
    for
      _ <- touch(rec.queryId)(_.copy(state = JobState.Running, stage = "workflow"))
      out <- runEngine(rec.queryId, req).attempt
      _ <- out match
        case Right(r) =>
          IO(r.audit.evidenceIds.foreach(index.put(_, r.audit.evidenceBundleId))) *>
            touch(rec.queryId)(
              _.copy(state = JobState.Succeeded, stage = "done", answer = Some(r.answer))
            )
        case Left(e) =>
          val err = e match
            case a: ApiException => a.error
            case _               => ApiError.internal
          touch(rec.queryId)(
            _.copy(state = JobState.Failed, stage = "workflow", error = Some(err))
          ) *>
            IO(
              if err.code.retryable && rec.mode == QueryMode.Sync then
                key.foreach(store.release(rec.owner, _))
            )
    yield ()

  /** Workflow errors map to typed errors; only a downstream failure counts toward the breaker. */
  private def runEngine(queryId: String, req: AnalysisRequest): IO[WorkflowResult] =
    workflow
      .call(engine.run(queryId, req) match
        case Left(WorkflowError.Downstream(stage, _)) => Left(s"$stage failed")
        case other                                    => Right(other))
      .flatMap {
        case Right(r) => IO.pure(r)
        case Left(e)  => ApiException.raise(QueryService.workflowError(e))
      }

  private def owned(p: Principal, queryId: String): IO[QueryRecord] =
    if !QueryIds.valid(queryId) then
      ApiException.raise(ApiError.invalid("malformed identifier", "id"))
    else
      store.get(queryId).filter(_.owner == p.subject) match
        case Some(r) => IO.pure(r)
        case None    => ApiException.raise(ApiError.notFound("query"))

  def job(p: Principal, jobId: String): IO[QueryRecord] = owned(p, QueryIds.queryOf(jobId))

  /** `GET /v1/explain/{queryId}`: the audit record of the answer as an observable derivation. It
    * holds no prompt text, no reply text and no model reasoning (the audit record keeps none).
    */
  def explain(p: Principal, queryId: String): IO[ujson.Obj] =
    owned(p, queryId).flatMap { rec =>
      if !rec.state.terminal then
        ApiException.raise(ApiError(ErrorCode.Conflict, "the query has not finished"))
      else
        for
          a <- stores
            .call(audit.get(queryId))
            .flatMap(
              _.fold(ApiException.raise[AuditRecord](ApiError.notFound("explanation")))(IO.pure)
            )
          b <- stores.call(bundles.get(a.evidenceBundleId).left.map(_.toString))
        yield QueryService.explainView(a, b)
    }

object QueryService:
  def workflowError(e: WorkflowError): ApiError = e match
    case WorkflowError.InvalidRequest(m) => ApiError.invalid(m.take(200), "question")
    case WorkflowError.Unsupported(m)    => ApiError.unsupported(m.take(200), "question")
    case WorkflowError.Timeout(stage, _) =>
      ApiError(ErrorCode.Timeout, "the workflow timed out", Map("stage" -> stage))
    case WorkflowError.BudgetExceeded(stage, _) =>
      ApiError.unsupported(s"the request exceeds the $stage budget", "options")
    case WorkflowError.Downstream(stage, _) =>
      ApiError(ErrorCode.DependencyFailed, "a workflow stage failed", Map("stage" -> stage))

  private def js[A: upickle.default.Writer](a: A): ujson.Value = upickle.default.writeJs(a)

  /** The fixed category of a synthesis fallback. The audit's text is only matched, never returned;
    * a text this list does not know is `other`.
    */
  def fallbackCategory(reason: String): String =
    FallbackCategories.collectFirst { case (p, c) if reason.startsWith(p) => c }.getOrElse("other")

  private val FallbackCategories = Vector(
    "the evidence stage abstained" -> "evidence-abstained",
    "model call failed" -> "model-call-failed",
    "model output refused" -> "model-output-refused",
    "output token budget" -> "token-budget-exhausted",
    "the model abstained" -> "model-abstained"
  )

  def explainView(a: AuditRecord, bundle: Option[EvidenceBundle]): ujson.Obj =
    val items = bundle.fold(Vector.empty[EvidenceItem])(_.all)
    ujson.Obj(
      "schemaVersion" -> "explain-v1",
      "queryId" -> a.queryId,
      "plan" -> js(a.plan),
      "answeredWindow" -> js(a.answeredWindow),
      "windowNote" -> js(a.windowNote),
      "resolutions" -> js(a.resolutions),
      "stages" -> js(a.stages),
      "graphCalls" -> js(a.graphCalls),
      "versions" -> ujson.Obj(
        "components" -> js(a.componentVersions),
        "synthesizer" -> a.synthesizer,
        "promptVersion" -> a.promptVersion,
        "promptSha256" -> js(a.promptSha256),
        "outputSchemaVersion" -> a.outputSchemaVersion,
        "modelVersion" -> a.modelVersion,
        "metricVersions" -> js(a.metricVersions),
        "erPolicyVersion" -> a.erPolicyVersion,
        "graphRunId" -> a.graphRunId,
        "erRunId" -> a.erRunId
      ),
      "evidence" -> ujson.Obj(
        "bundleId" -> a.evidenceBundleId,
        "ids" -> js(a.evidenceIds),
        "scoreComponents" -> ujson.Arr.from(
          items.map(e =>
            ujson.Obj(
              "evidenceId" -> e.evidenceId,
              "evidenceType" -> js(e.evidenceType),
              "score" -> e.score,
              "values" -> ujson.Obj.from(
                e.values.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v.take(200)))
              )
            )
          )
        )
      ),
      // An attempt's reason and the fallback text can quote a downstream error, so neither leaves.
      "synthesisAttempts" -> ujson.Arr.from(
        a.synthesisAttempts.map(t => ujson.Obj("attempt" -> t.attempt, "status" -> t.status))
      ),
      "synthesisFallback" -> js(a.synthesisFallback.map(fallbackCategory)),
      "claims" -> ujson.Obj(
        "checks" -> ujson.Arr.from(
          a.claimChecks.map(c =>
            ujson.Obj(
              "claimId" -> c.claimId,
              "status" -> js(c.status),
              "reasons" -> js(c.reasons),
              "facts" -> c.facts,
              "supported" -> c.supported
            )
          )
        ),
        "removed" -> ujson.Arr.from(
          a.removedClaims.map(c =>
            ujson.Obj("claimId" -> c.claimId, "status" -> js(c.status), "reasons" -> js(c.reasons))
          )
        )
      ),
      "counters" -> ujson.Obj(
        "claimsChecked" -> a.claimChecks.size,
        "claimsRemoved" -> a.removedClaims.size,
        "evidenceItems" -> a.evidenceIds.size,
        "graphCalls" -> a.graphCalls.size,
        "semanticDropped" -> a.semanticDropped,
        "synthesisAttempts" -> a.synthesisAttempts.size,
        "inputTokens" -> js(a.inputTokens),
        "outputTokens" -> js(a.outputTokens),
        "latencyMs" -> a.latencyMs
      ),
      "verification" -> js(a.verification),
      "abstained" -> a.abstained,
      "abstentionReason" -> js(a.abstentionReason),
      "answerHash" -> a.answerHash
    )
