package scholarchain.services.common

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import scholarchain.core.EvidenceItem

/** Port: evidence by ID. The storage module keeps evidence inside bundles keyed by bundle ID, so
  * the ID-to-item index is an adapter that a deployable wires (the in-memory one serves tests).
  * `Left` is a store failure; an unknown ID is `Right(None)`.
  */
trait EvidenceLookup:
  def get(evidenceId: String): Either[String, Option[EvidenceItem]]

final class InMemoryEvidenceLookup(items: Iterable[EvidenceItem]) extends EvidenceLookup:
  private val byId = items.map(e => e.evidenceId -> e).toMap
  def get(evidenceId: String): Either[String, Option[EvidenceItem]] = Right(byId.get(evidenceId))

/** `GET /v1/evidence/{evidenceId}`: provenance, source record, source URI, graph path or text
  * pointer, and compact supporting content (text cut to `maxTextChars`). Mounted by a deployable
  * next to its own routes; the checks and the response shape live here once.
  */
final class EvidenceRoutes(
    guard: Guard,
    lookup: EvidenceLookup,
    downstream: Downstream,
    maxTextChars: Int = 600
):
  private val MaxIdLength = 200

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "v1" / "evidence" / id =>
      for
        _ <- guard.authorize(req, Scopes.EvidenceRead)
        _ <- validId(id)
        found <- downstream.call(lookup.get(id))
        item <- found.fold(ApiException.raise[EvidenceItem](ApiError.notFound("evidence")))(IO.pure)
      yield Json.ok(view(item))
  }

  private def validId(id: String): IO[Unit] =
    if id.nonEmpty && id.length <= MaxIdLength && !id.exists(c => c.isWhitespace || c.isControl)
    then IO.unit
    else ApiException.raise(ApiError.invalid("malformed evidence ID", "evidenceId"))

  def view(e: EvidenceItem): ujson.Obj =
    val full = upickle.default.writeJs(e).obj
    val text = e.text.getOrElse("")
    full("text") = if e.text.isEmpty then ujson.Null else ujson.Str(text.take(maxTextChars))
    full("textTruncated") = text.length > maxTextChars
    full
