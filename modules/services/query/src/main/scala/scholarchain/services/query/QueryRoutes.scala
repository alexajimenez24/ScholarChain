package scholarchain.services.query

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString
import scholarchain.rag.{DeterministicSynthesizer, Synthesizer}
import scholarchain.services.common.*

/** Thin routes: authorize, read and validate the body, call [[QueryService]], render JSON.
  *
  *   - `POST /v1/query` (Idempotency-Key honored; 200 sync answer, 202 async job reference)
  *   - `GET /v1/jobs/{jobId}`, `GET /v1/jobs/{jobId}/result`
  *   - `GET /v1/explain/{queryId}`
  */
final class QueryRoutes(guard: Guard, service: QueryService, maxBodyBytes: Int = 16 * 1024):
  private val KeyShape = "^[A-Za-z0-9_.:-]{1,128}$".r

  private def idempotencyKey(req: Request[IO]): IO[Option[String]] =
    req.headers.get(CIString("Idempotency-Key")).map(_.head.value.trim) match
      case None                           => IO.pure(None)
      case Some(k) if KeyShape.matches(k) => IO.pure(Some(k))
      case Some(_)                        =>
        ApiException.raise(
          ApiError.invalid(
            "Idempotency-Key must be 1..128 characters of [A-Za-z0-9_.:-]",
            "Idempotency-Key"
          )
        )

  private def iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString

  private def jobView(r: QueryRecord): ujson.Obj =
    val o = ujson.Obj(
      "jobId" -> r.jobId,
      "queryId" -> r.queryId,
      "mode" -> r.mode.wire,
      "state" -> r.state.wire,
      "stage" -> r.stage,
      "createdAt" -> iso(r.createdAt),
      "updatedAt" -> iso(r.updatedAt)
    )
    if r.state.terminal then o("completedAt") = iso(r.updatedAt)
    r.error.foreach(e =>
      o("error") =
        ujson.Obj("code" -> e.code.wire, "message" -> e.message, "retryable" -> e.code.retryable)
    )
    val links =
      ujson.Obj("self" -> s"/v1/jobs/${r.jobId}", "explain" -> s"/v1/explain/${r.queryId}")
    if r.state == JobState.Succeeded then links("result") = s"/v1/jobs/${r.jobId}/result"
    o("links") = links
    o

  private def withAnswer(r: QueryRecord): ujson.Obj =
    val o = jobView(r)
    r.answer.foreach(a => o("answer") = upickle.default.writeJs(a))
    o

  private def replayed(res: Response[IO], again: Boolean) =
    if again then res.putHeaders(Header.Raw(CIString("Idempotent-Replayed"), "true")) else res

  private def render(r: QueryRecord, again: Boolean): IO[Response[IO]] =
    r.mode match
      case QueryMode.Async =>
        val res = Json.respond(Status.Accepted, jobView(r))
        IO.pure(
          replayed(res.putHeaders(Header.Raw(CIString("Location"), s"/v1/jobs/${r.jobId}")), again)
        )
      case QueryMode.Sync =>
        r.state match
          case JobState.Succeeded => IO.pure(replayed(Json.ok(withAnswer(r)), again))
          case JobState.Failed    => ApiException.raise(r.error.getOrElse(ApiError.internal))
          case _                  =>
            ApiException.raise(
              ApiError(ErrorCode.Conflict, "the original request is still running")
            )

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "v1" / "query" =>
      for
        p <- guard.authorize(req, Scopes.QueryRun)
        key <- idempotencyKey(req)
        body <- JsonBody.read(req, maxBodyBytes)
        q <- IO.fromEither(QueryRequestParser.parse(body).left.map(new ApiException(_)))
        out <- service.submit(p, q, key)
        res <- render(out._1, out._2)
      yield res

    case req @ GET -> Root / "v1" / "jobs" / id =>
      guard
        .authorize(req, Scopes.QueryRun)
        .flatMap(service.job(_, id))
        .map(r => Json.ok(jobView(r)))

    case req @ GET -> Root / "v1" / "jobs" / id / "result" =>
      guard.authorize(req, Scopes.QueryRun).flatMap(service.job(_, id)).flatMap { r =>
        r.state match
          case JobState.Succeeded => IO.pure(Json.ok(withAnswer(r)))
          case JobState.Failed    => ApiException.raise(r.error.getOrElse(ApiError.internal))
          case _                  =>
            IO.pure(
              Json
                .respond(Status.Accepted, jobView(r))
                .putHeaders(Header.Raw(CIString("Retry-After"), "2"))
            )
      }

    case req @ GET -> Root / "v1" / "explain" / id =>
      guard.authorize(req, Scopes.QueryRun).flatMap(service.explain(_, id)).map(Json.ok)
  }

/** Settings read from the environment. `LLM_ENABLED` defaults to false: the model-written path is
  * off unless a deployment turns it on.
  */
final case class QueryConfig(llmEnabled: Boolean = false)

object QueryConfig:
  def fromEnv(env: Map[String, String]): Either[String, QueryConfig] =
    env.get("LLM_ENABLED").map(_.trim.toLowerCase) match
      case None | Some("") | Some("false") => Right(QueryConfig(false))
      case Some("true")                    => Right(QueryConfig(true))
      case Some(_)                         => Left("LLM_ENABLED must be true or false")

/** Wiring of the query deployable: its routes plus evidence by ID, with the shared middleware. */
object QueryApp:
  /** The deterministic synthesizer unless `LLM_ENABLED=true` and a model synthesizer is given. */
  def synthesizer(cfg: QueryConfig, model: Option[Synthesizer]): Either[String, Synthesizer] =
    if !cfg.llmEnabled then Right(DeterministicSynthesizer)
    else model.toRight("LLM_ENABLED=true but no model synthesizer is wired")

  def routes(
      kit: ServiceKit,
      guard: Guard,
      service: QueryService,
      evidence: EvidenceRoutes
  ): HttpRoutes[IO] =
    kit.wrap(new QueryRoutes(guard, service).routes <+> evidence.routes)
