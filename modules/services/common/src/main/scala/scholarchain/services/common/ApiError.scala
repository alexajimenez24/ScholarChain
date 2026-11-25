package scholarchain.services.common

import org.http4s.Status

/** The one typed error model. Every error response of every service is built from an [[ApiError]]
  * and carries the request ID and the trace ID, so a failure can be found in the logs.
  *
  * Status mapping (stated once, here): malformed input 400; missing or invalid token 401; missing
  * scope 403; unknown resource 404; a conflicting replay 409; body over the limit 413; wrong
  * content type 415; a well-formed request outside a bound or an unsupported constraint 422; an
  * unexpected failure 500; a failed downstream call 502; an open circuit breaker or a service that
  * is not ready 503; a downstream call that timed out 504.
  */
enum ErrorCode(val wire: String, val status: Status, val retryable: Boolean):
  case InvalidRequest extends ErrorCode("invalid_request", Status.BadRequest, false)
  case InvalidJson extends ErrorCode("invalid_json", Status.BadRequest, false)
  case Unauthorized extends ErrorCode("unauthorized", Status.Unauthorized, false)
  case Forbidden extends ErrorCode("forbidden", Status.Forbidden, false)
  case NotFound extends ErrorCode("not_found", Status.NotFound, false)
  case Conflict extends ErrorCode("conflict", Status.Conflict, false)
  case PayloadTooLarge extends ErrorCode("payload_too_large", Status.PayloadTooLarge, false)
  case UnsupportedMediaType
      extends ErrorCode("unsupported_media_type", Status.UnsupportedMediaType, false)
  case UnsupportedConstraint
      extends ErrorCode("unsupported_constraint", Status.UnprocessableContent, false)
  case Internal extends ErrorCode("internal_error", Status.InternalServerError, false)
  case DependencyFailed extends ErrorCode("dependency_failed", Status.BadGateway, true)
  case Unavailable extends ErrorCode("unavailable", Status.ServiceUnavailable, true)
  case Timeout extends ErrorCode("timeout", Status.GatewayTimeout, true)

/** A typed error. `message` is safe for a client: never a token, a credential, a stack trace or a
  * downstream message. `details` holds short machine-readable context (a field name, a bound).
  */
final case class ApiError(
    code: ErrorCode,
    message: String,
    details: Map[String, String] = Map.empty
):
  def toJson(ids: RequestIds): ujson.Obj =
    val err = ujson.Obj(
      "code" -> code.wire,
      "message" -> message,
      "retryable" -> code.retryable,
      "requestId" -> ids.requestId,
      "traceId" -> ids.traceId
    )
    if details.nonEmpty then
      err("details") = ujson.Obj.from(details.toSeq.sorted.map((k, v) => k -> ujson.Str(v)))
    ujson.Obj("error" -> err)

object ApiError:
  def invalid(message: String, field: String = ""): ApiError =
    ApiError(
      ErrorCode.InvalidRequest,
      message,
      if field.isEmpty then Map.empty else Map("field" -> field)
    )
  def unsupported(message: String, field: String): ApiError =
    ApiError(ErrorCode.UnsupportedConstraint, message, Map("field" -> field))
  def notFound(what: String): ApiError = ApiError(ErrorCode.NotFound, s"$what not found")
  val internal: ApiError = ApiError(ErrorCode.Internal, "internal error")

/** Raised inside an `IO` and rendered by [[ServiceKit]]; a route never builds an error response by
  * hand.
  */
final class ApiException(val error: ApiError)
    extends RuntimeException(error.message, null, false, false)

object ApiException:
  def raise[A](e: ApiError): cats.effect.IO[A] = cats.effect.IO.raiseError(new ApiException(e))

/** The IDs one request carries on every response and in every error body. */
final case class RequestIds(requestId: String, traceId: String)

object RequestIds:
  val RequestHeader = "X-Request-Id"
  val TraceHeader = "X-Trace-Id"
  private val Safe = "^[A-Za-z0-9_.=-]{1,128}$".r
  private val Root = "Root=([A-Za-z0-9-]{1,128})".r.unanchored
  private val Traceparent = "^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$".r

  private def fresh(prefix: String) = prefix + java.util.UUID.randomUUID().toString
  private def safe(v: Option[String]) = v.map(_.trim).filter(Safe.matches)

  /** Reuses a well-formed caller ID, otherwise generates one: a caller cannot inject odd characters
    * into logs or headers.
    */
  def from(
      requestId: Option[String],
      xrayHeader: Option[String],
      traceparent: Option[String],
      traceId: Option[String]
  ): RequestIds =
    val trace = xrayHeader
      .flatMap(h => Root.findFirstMatchIn(h).map(_.group(1)))
      .orElse(
        traceparent.flatMap(h => Traceparent.findFirstMatchIn(h.trim.toLowerCase).map(_.group(1)))
      )
      .orElse(safe(traceId))
      .getOrElse(java.util.UUID.randomUUID().toString.replace("-", ""))
    RequestIds(safe(requestId).getOrElse(fresh("req-")), trace)
