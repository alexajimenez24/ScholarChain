package scholarchain.services.graph

import cats.effect.IO
import scholarchain.core.Hashing
import scholarchain.services.common.*
import scholarchain.storage.{GraphStore, GraphTemplates}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try

/** Bounds of a neighbourhood read. `maxResults` is the most neighbours one walk can ever return
  * across all pages (it cannot exceed the Neptune template limit); `maxDepth` is 1 because the
  * allowlisted neighbour templates are one hop.
  */
final case class GraphLimits(
    defaultPageSize: Int = 20,
    maxPageSize: Int = 50,
    maxResults: Int = GraphTemplates.MaxLimit,
    maxDepth: Int = 1
):
  require(
    maxResults >= 1 && maxResults <= GraphTemplates.MaxLimit,
    "maxResults exceeds the template limit"
  )
  require(
    maxPageSize >= 1 && defaultPageSize >= 1 && defaultPageSize <= maxPageSize,
    "bad page sizes"
  )

final case class Neighbor(edgeType: String, edgeId: String, nodeId: String, labels: Vector[String])

final case class NeighborPage(
    nodeId: String,
    direction: String,
    edgeTypes: Vector[String],
    depth: Int,
    items: Vector[Neighbor],
    nextCursor: Option[String],
    truncated: Boolean
)

/** Graph reads. Application logic lives here, not in the routes: input checks, the bounds, the
  * cursor and the call through the guarded downstream. Queries are built only by
  * [[GraphTemplates]], so user text never becomes query text.
  */
final class GraphReader(
    graph: GraphStore,
    templates: GraphTemplates,
    downstream: Downstream,
    limits: GraphLimits = GraphLimits()
):
  private val AllEdgeTypes = GraphTemplates.NeighborEdgeTypes
  private val Directions = Set("in", "out", "both")

  def node(id: String): IO[ujson.Obj] =
    for
      q <- IO.fromEither(
        templates.node(id).left.map(m => new ApiException(ApiError.invalid(m, "nodeId")))
      )
      rows <- downstream.call(graph.run(q))
      row <- rows.headOption.fold(ApiException.raise[ujson.Obj](ApiError.notFound("node")))(IO.pure)
    yield ujson.Obj(
      "id" -> row.value.getOrElse("id", ujson.Str(id)),
      "labels" -> row.value.getOrElse("labels", ujson.Arr()),
      "properties" -> row.value.getOrElse("properties", ujson.Obj())
    )

  /** One page of one-hop neighbours, ordered by (node ID, edge ID). Page `n+1` is requested with
    * the cursor of page `n`; the cursor is bound to the node, direction and edge types it was
    * issued for.
    */
  def neighbors(
      id: String,
      edgeTypes: Option[Set[String]],
      direction: Option[String],
      depth: Option[Int],
      pageSize: Option[Int],
      cursor: Option[String]
  ): IO[NeighborPage] =
    val dir = direction.getOrElse("both")
    val types = edgeTypes.getOrElse(AllEdgeTypes)
    val size = pageSize.getOrElse(limits.defaultPageSize)
    for
      _ <- check(
        Directions.contains(dir),
        ApiError.invalid("direction must be in, out or both", "direction")
      )
      _ <- check(
        types.nonEmpty && types.subsetOf(AllEdgeTypes),
        ApiError.unsupported(
          s"edgeTypes must be a non-empty subset of ${AllEdgeTypes.toVector.sorted.mkString(",")}",
          "edgeTypes"
        )
      )
      _ <- check(
        depth.forall(d => d >= 1 && d <= limits.maxDepth),
        ApiError.unsupported(s"depth must be 1..${limits.maxDepth}", "depth")
      )
      _ <- check(
        size >= 1 && size <= limits.maxPageSize,
        ApiError.unsupported(s"pageSize must be 1..${limits.maxPageSize}", "pageSize")
      )
      print = fingerprint(id, dir, types)
      offset <- IO.fromEither(decode(cursor, print))
      take = math.max(math.min(size, limits.maxResults - offset), 0)
      fetch = math.min(offset + take + 1, limits.maxResults)
      // an exhausted bound still answers: an empty last page flagged as truncated
      rows <-
        if take <= 0 then IO.pure(Vector.empty[ujson.Obj])
        else
          for
            q <- IO.fromEither(
              templates
                .neighbors(id, types, dir, fetch)
                .left
                .map(m => new ApiException(ApiError.invalid(m, "nodeId")))
            )
            r <- downstream.call(graph.run(q))
          yield r
      all = rows.flatMap(toNeighbor).sortBy(n => (n.nodeId, n.edgeId))
      page = all.slice(offset, offset + take)
      more = all.size > offset + take
      atBound = offset + take >= limits.maxResults
    yield NeighborPage(
      id,
      dir,
      types.toVector.sorted,
      depth.getOrElse(1),
      page,
      Option.when(more && !atBound)(encode(offset + page.size, print)),
      // at the bound a full fetch means there may be more: reported, never paged past
      truncated = atBound && (take <= 0 || all.size >= limits.maxResults)
    )

  private def check(ok: Boolean, e: => ApiError): IO[Unit] =
    if ok then IO.unit else ApiException.raise(e)

  private def toNeighbor(r: ujson.Obj): Option[Neighbor] =
    for
      nodeId <- r.value.get("nodeId").flatMap(_.strOpt)
      edgeType <- r.value.get("edgeType").flatMap(_.strOpt)
    yield Neighbor(
      edgeType,
      r.value.get("edgeId").flatMap(_.strOpt).getOrElse(""),
      nodeId,
      r.value
        .get("labels")
        .flatMap(_.arrOpt)
        .map(_.toVector.flatMap(_.strOpt))
        .getOrElse(Vector.empty)
    )

  private def fingerprint(id: String, dir: String, types: Set[String]): String =
    Hashing.sha256Hex(s"$id|$dir|${types.toVector.sorted.mkString(",")}").take(12)

  private def encode(offset: Int, print: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(s"v1:$offset:$print".getBytes(UTF_8))

  private def decode(cursor: Option[String], print: String): Either[ApiException, Int] =
    cursor match
      case None    => Right(0)
      case Some(c) =>
        val bad = new ApiException(ApiError.invalid("malformed or foreign cursor", "cursor"))
        Try(new String(Base64.getUrlDecoder.decode(c), UTF_8)).toOption
          .map(_.split(':'))
          .collect { case Array("v1", off, p) if p == print => off.toIntOption }
          .flatten
          .filter(o => o > 0 && o <= limits.maxResults)
          .toRight(bad)
