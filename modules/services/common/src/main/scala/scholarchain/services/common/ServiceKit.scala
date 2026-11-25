package scholarchain.services.common

import cats.data.OptionT
import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scala.util.control.NonFatal

object Json:
  def respond(status: Status, body: ujson.Value): Response[IO] =
    Response[IO](status)
      .withEntity(ujson.write(body))
      .withContentType(`Content-Type`(MediaType.application.json))
  def ok(body: ujson.Value): Response[IO] = respond(Status.Ok, body)

/** Request bodies: bounded, JSON only. */
object JsonBody:
  /** Reads at most `maxBytes` (413 beyond it, also for a chunked body without a length), requires
    * `application/json` (415) and parses it (400 `invalid_json`, never echoing the body).
    */
  def read(req: Request[IO], maxBytes: Int): IO[ujson.Value] =
    val typed = req.contentType.exists(_.mediaType == MediaType.application.json)
    if !typed then
      ApiException.raise(
        ApiError(ErrorCode.UnsupportedMediaType, "content type must be application/json")
      )
    else
      req.body.take(maxBytes.toLong + 1).compile.to(Array).flatMap { bytes =>
        if bytes.length > maxBytes then
          ApiException.raise(
            ApiError(
              ErrorCode.PayloadTooLarge,
              "request body is too large",
              Map("maxBytes" -> maxBytes.toString)
            )
          )
        else
          IO.fromEither(
            scala.util
              .Try(ujson.read(bytes))
              .toEither
              .left
              .map(_ =>
                new ApiException(ApiError(ErrorCode.InvalidJson, "the body is not valid JSON"))
              )
          )
      }

/** A named readiness check: ready when it returns true (it must not throw secrets; any exception
  * counts as not ready).
  */
final case class ReadinessCheck(name: String, check: IO[Boolean])

/** What every deployable gets from `servicesCommon` once: request and trace IDs on every response,
  * the typed error mapping for every failure (including unmatched routes and exceptions), the
  * request size limit, and one EMF metric line per request.
  *
  * `/healthz` is liveness (the process is up; no dependency is touched). `/readyz` is readiness
  * (its checks pass); it answers 503 with the failing check names when they do not. Neither needs a
  * token. `/metrics` is not exposed: metrics leave as EMF log lines on stdout (decision in
  * `modules/services/MODULE.md`).
  */
final class ServiceKit(
    val service: String,
    sink: MetricsSink,
    maxBodyBytes: Int = 64 * 1024,
    checks: List[ReadinessCheck] = Nil,
    checkTimeout: FiniteDuration = 2.seconds,
    nowMillis: IO[Long] = IO.realTime.map(_.toMillis)
):
  private val Namespace = "ScholarChain/Services"

  def wrap(inner: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO](req => OptionT.liftF(handle(inner, req)))

  private def ids(req: Request[IO]): RequestIds =
    def h(n: String) = req.headers.get(CIString(n)).map(_.head.value)
    RequestIds.from(
      h(RequestIds.RequestHeader),
      h("X-Amzn-Trace-Id"),
      h("traceparent"),
      h(RequestIds.TraceHeader)
    )

  /** The EMF Operation dimension must have bounded cardinality (each value is a billed metric set).
    * It is the method plus the first two path segments only when a route matched the request or the
    * prefix is in the fixed allowlist of this API's route templates; every other path is the
    * constant `unmatched`.
    */
  private def operation(req: Request[IO], matched: Boolean): String =
    val segs = req.pathInfo.renderString.split('/').filter(_.nonEmpty).take(2).toList
    val canon = List(segs.mkString("/"), segs.take(1).mkString("/"))
      .find(ServiceKit.KnownPrefixes.contains)
    canon.orElse(Option.when(matched)(segs.mkString("/"))) match
      case Some(p) => s"${req.method.name} /$p"
      case None    => "unmatched"

  private def handle(routes: HttpRoutes[IO], req: Request[IO]): IO[Response[IO]] =
    val rid = ids(req)
    for
      start <- nowMillis
      pair <- guarded(routes, req, rid)
      (res, matched) = pair
      end <- nowMillis
      out = res.putHeaders(
        Header.Raw(CIString(RequestIds.RequestHeader), rid.requestId),
        Header.Raw(CIString(RequestIds.TraceHeader), rid.traceId)
      )
      _ <- sink.emit(metric(req, matched, out.status, end - start, rid, end)).handleError(_ => ())
    yield out

  private def guarded(
      routes: HttpRoutes[IO],
      req: Request[IO],
      rid: RequestIds
  ): IO[(Response[IO], Boolean)] =
    val tooBig = req.contentLength.exists(_ > maxBodyBytes)
    val run: IO[(Response[IO], Boolean)] =
      if tooBig then
        ApiException.raise(
          ApiError(
            ErrorCode.PayloadTooLarge,
            "request body is too large",
            Map("maxBytes" -> maxBodyBytes.toString)
          )
        )
      else
        (ops(rid) <+> routes)
          .run(req)
          .value
          .map {
            case Some(r) => (r, true)
            case None    => (error(ApiError(ErrorCode.NotFound, "no such route"), rid), false)
          }
    run.handleError {
      case e: ApiException => (error(e.error, rid), false)
      case NonFatal(_)     => (error(ApiError.internal, rid), false)
    }

  private def error(e: ApiError, rid: RequestIds): Response[IO] =
    val r = Json.respond(e.code.status, e.toJson(rid))
    if e.code == ErrorCode.Unauthorized then
      r.putHeaders(Header.Raw(CIString("WWW-Authenticate"), "Bearer"))
    else r

  private def metric(
      req: Request[IO],
      matched: Boolean,
      st: Status,
      ms: Long,
      rid: RequestIds,
      now: Long
  ): String =
    Emf.line(
      Namespace,
      Map("Service" -> service, "Operation" -> operation(req, matched)),
      Seq(
        Metric("Requests", 1),
        Metric("ClientErrors", if st.code >= 400 && st.code < 500 then 1 else 0),
        Metric("ServerErrors", if st.code >= 500 then 1 else 0),
        Metric("LatencyMs", ms.toDouble, "Milliseconds")
      ),
      Map("requestId" -> rid.requestId, "traceId" -> rid.traceId, "status" -> st.code.toString),
      now
    )

  private def ops(rid: RequestIds): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      IO.pure(Json.ok(ujson.Obj("status" -> "ok", "service" -> service)))
    case GET -> Root / "readyz" =>
      checks
        .traverse(c => c.check.timeout(checkTimeout).handleError(_ => false).map(c.name -> _))
        .map { results =>
          val failing = results.filterNot(_._2).map(_._1)
          if failing.isEmpty then
            Json.ok(
              ujson.Obj(
                "status" -> "ready",
                "service" -> service,
                "checks" -> ujson.Obj.from(results.map((n, _) => n -> ujson.Str("ok")))
              )
            )
          else
            error(
              ApiError(
                ErrorCode.Unavailable,
                "the service is not ready",
                failing.map(n => n -> "failing").toMap
              ),
              rid
            )
        }
  }

object ServiceKit:
  /** Fixed allowlist of route path prefixes (first one or two segments) for the EMF Operation. */
  val KnownPrefixes: Set[String] = Set(
    "healthz",
    "readyz",
    "v1/query",
    "v1/jobs",
    "v1/explain",
    "v1/evidence",
    "v1/graph"
  )
