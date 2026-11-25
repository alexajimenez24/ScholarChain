package scholarchain.services.graph

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.*
import org.http4s.dsl.io.*
import scholarchain.services.common.*

/** Thin routes: authorize, parse the query string, call [[GraphReader]], render JSON.
  *
  *   - `GET /v1/graph/nodes/{nodeId}`
  *   - `GET /v1/graph/nodes/{nodeId}/neighbors?edgeTypes=&direction=&depth=&pageSize=&cursor=`
  */
final class GraphRoutes(guard: Guard, reader: GraphReader):
  private object EdgeTypes extends OptionalQueryParamDecoderMatcher[String]("edgeTypes")
  private object Direction extends OptionalQueryParamDecoderMatcher[String]("direction")
  private object Depth extends OptionalQueryParamDecoderMatcher[String]("depth")
  private object PageSize extends OptionalQueryParamDecoderMatcher[String]("pageSize")
  private object Cursor extends OptionalQueryParamDecoderMatcher[String]("cursor")

  private def int(name: String, v: Option[String]): IO[Option[Int]] =
    v match
      case None    => IO.pure(None)
      case Some(s) =>
        s.toIntOption.fold(
          ApiException.raise[Option[Int]](ApiError.invalid(s"$name must be an integer", name))
        )(i => IO.pure(Some(i)))

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "v1" / "graph" / "nodes" / id =>
      guard.authorize(req, Scopes.GraphRead) *> reader.node(id).map(Json.ok)

    case req @ GET -> Root / "v1" / "graph" / "nodes" / id / "neighbors" :? EdgeTypes(
          types
        ) +& Direction(
          dir
        ) +& Depth(depth) +& PageSize(size) +& Cursor(cursor) =>
      for
        _ <- guard.authorize(req, Scopes.GraphRead)
        d <- int("depth", depth)
        n <- int("pageSize", size)
        page <- reader.neighbors(
          id,
          types.map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet),
          dir,
          d,
          n,
          cursor
        )
      yield Json.ok(view(page))
  }

  private def view(p: NeighborPage): ujson.Obj =
    val o = ujson.Obj(
      "nodeId" -> p.nodeId,
      "direction" -> p.direction,
      "edgeTypes" -> ujson.Arr.from(p.edgeTypes),
      "depth" -> p.depth,
      "items" -> ujson.Arr.from(
        p.items.map(n =>
          ujson.Obj(
            "edgeType" -> n.edgeType,
            "edgeId" -> n.edgeId,
            "nodeId" -> n.nodeId,
            "labels" -> ujson.Arr.from(n.labels)
          )
        )
      ),
      "truncated" -> p.truncated
    )
    p.nextCursor.foreach(c => o("nextCursor") = c)
    o

/** Wiring of the graph deployable: routes plus evidence by ID, wrapped with the shared middleware.
  */
object GraphApp:
  def routes(
      kit: ServiceKit,
      guard: Guard,
      reader: GraphReader,
      evidence: EvidenceRoutes
  ): HttpRoutes[IO] =
    kit.wrap(new GraphRoutes(guard, reader).routes <+> evidence.routes)
