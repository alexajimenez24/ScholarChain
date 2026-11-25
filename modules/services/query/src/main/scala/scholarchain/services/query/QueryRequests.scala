package scholarchain.services.query

import cats.syntax.all.*
import java.security.MessageDigest
import scholarchain.rag.*
import scholarchain.services.common.*

enum QueryMode(val wire: String):
  case Sync extends QueryMode("sync")
  case Async extends QueryMode("async")

/** A validated `POST /v1/query` body, mapped onto the rag workflow's input. */
final case class ParsedQuery(mode: QueryMode, request: AnalysisRequest):
  /** What an Idempotency-Key is bound to: the same key with another request is a conflict. */
  def fingerprint: String =
    Hashes.sha256Hex(s"${mode.wire}|${upickle.default.write(request)}")

object Hashes:
  def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

/** Request validation (no I/O). Malformed shape or type is 400 `invalid_request`; a well-formed
  * request that names a constraint the service does not support, or one outside a bound, is 422
  * `unsupported_constraint`.
  *
  * Body: `{"question": str, "mode": "sync"|"async", "constraints": {yearFrom, yearTo, topics,
  * institutions, venues, researchers, researcherIds}, "options": {limit, maxPathDepth}}`.
  */
object QueryRequestParser:
  val MaxQuestionChars = 1000
  val MaxListItems = 10
  val MaxItemChars = 200
  val MaxYear = 2100
  val MinYear = 1900
  private val Top = Set("question", "mode", "constraints", "options")
  private val ConstraintKeys =
    Set("yearFrom", "yearTo", "topics", "institutions", "venues", "researchers", "researcherIds")
  private val OptionKeys = Set("limit", "maxPathDepth")

  def parse(body: ujson.Value): Either[ApiError, ParsedQuery] =
    for
      o <- obj(body, "body")
      _ <- unknown(o, Top, "body", ApiError.invalid)
      q <- question(o)
      m <- mode(o)
      c <- constraints(o)
      r <- options(o)
    yield ParsedQuery(m, AnalysisRequest(q, c, AnalysisOptions(), r))

  private def obj(v: ujson.Value, field: String): Either[ApiError, ujson.Obj] = v match
    case o: ujson.Obj => Right(o)
    case _            => Left(ApiError.invalid(s"$field must be a JSON object", field))

  private def unknown(
      o: ujson.Obj,
      allowed: Set[String],
      where: String,
      mk: (String, String) => ApiError
  ): Either[ApiError, Unit] =
    o.value.keys.toVector.sorted.find(!allowed(_)) match
      case Some(k) => Left(mk(s"unsupported field in $where", s"$where.$k"))
      case None    => Right(())

  private def clean(s: String) = s.trim.nonEmpty && !s.exists(_.isControl)

  private def question(o: ujson.Obj): Either[ApiError, String] =
    o.value.get("question") match
      case Some(ujson.Str(s)) if clean(s) && s.trim.length <= MaxQuestionChars => Right(s.trim)
      case Some(ujson.Str(_))                                                  =>
        Left(ApiError.invalid(s"question must be 1..$MaxQuestionChars characters", "question"))
      case _ => Left(ApiError.invalid("question is required and must be a string", "question"))

  private def mode(o: ujson.Obj): Either[ApiError, QueryMode] =
    o.value.get("mode") match
      case None | Some(ujson.Str("sync")) => Right(QueryMode.Sync)
      case Some(ujson.Str("async"))       => Right(QueryMode.Async)
      case _ => Left(ApiError.invalid("mode must be \"sync\" or \"async\"", "mode"))

  private def int(
      o: ujson.Obj,
      k: String,
      where: String,
      lo: Int,
      hi: Int
  ): Either[ApiError, Option[Int]] =
    val field = s"$where.$k"
    o.value.get(k) match
      case None                                                  => Right(None)
      case Some(ujson.Num(d)) if d.isWhole && d >= lo && d <= hi => Right(Some(d.toInt))
      case Some(ujson.Num(_))                                    =>
        Left(ApiError.unsupported(s"$field must be between $lo and $hi", field))
      case Some(_) => Left(ApiError.invalid(s"$field must be an integer", field))

  private def strings(o: ujson.Obj, k: String): Either[ApiError, Vector[String]] =
    val field = s"constraints.$k"
    o.value.get(k) match
      case None                                          => Right(Vector.empty)
      case Some(ujson.Arr(xs)) if xs.size > MaxListItems =>
        Left(ApiError.unsupported(s"$field holds at most $MaxListItems items", field))
      case Some(ujson.Arr(xs)) =>
        xs.toVector.traverse {
          case ujson.Str(s) if clean(s) && s.trim.length <= MaxItemChars => Right(s.trim)
          case _                                                         =>
            Left(ApiError.invalid(s"$field items must be 1..$MaxItemChars characters", field))
        }
      case Some(_) => Left(ApiError.invalid(s"$field must be an array of strings", field))

  private def constraints(o: ujson.Obj): Either[ApiError, Constraints] =
    o.value.get("constraints") match
      case None    => Right(Constraints())
      case Some(v) =>
        for
          c <- obj(v, "constraints")
          _ <- unknown(c, ConstraintKeys, "constraints", ApiError.unsupported)
          from <- int(c, "yearFrom", "constraints", MinYear, MaxYear)
          to <- int(c, "yearTo", "constraints", MinYear, MaxYear)
          _ <- Either.cond(
            from.forall(f => to.forall(f <= _)),
            (),
            ApiError.unsupported("yearFrom must not be after yearTo", "constraints.yearFrom")
          )
          topics <- strings(c, "topics")
          inst <- strings(c, "institutions")
          venues <- strings(c, "venues")
          names <- strings(c, "researchers")
          ids <- strings(c, "researcherIds")
        yield Constraints(from, to, topics, inst, venues, names, ids)

  private def options(o: ujson.Obj): Either[ApiError, RetrievalOptions] =
    o.value.get("options") match
      case None    => Right(RetrievalOptions())
      case Some(v) =>
        for
          p <- obj(v, "options")
          _ <- unknown(p, OptionKeys, "options", ApiError.unsupported)
          limit <- int(p, "limit", "options", 1, 20)
          depth <- int(p, "maxPathDepth", "options", 1, 6)
        yield
          val d = RetrievalOptions()
          d.copy(limit = limit.getOrElse(d.limit), maxPathDepth = depth.getOrElse(d.maxPathDepth))
