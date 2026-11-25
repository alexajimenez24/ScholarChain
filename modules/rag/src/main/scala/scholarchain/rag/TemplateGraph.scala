package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.{GraphQuery, GraphTemplates, InMemoryGraphStore}

/** An in-memory graph that answers storage's allowlisted read templates the way the Neptune load of
  * the same export would (`template-graph-v1`): the rows carry the columns of each template's
  * RETURN clause, COAUTHORED_WITH relationships exist once per window with their graph edge ID
  * (`Ids.graphEdge` of pair, policy and coauthor edge ID), `representativeWorkIds` is the
  * `|`-joined string the bulk load writes, and the evidence pointer is
  * `<evidenceBase>/coauthor-edges/windowLabel=<window>#<coauthorEdgeId>`.
  *
  * It is the local stand-in behind storage's [[InMemoryGraphStore]] (which still refuses anything
  * not allowlisted). Shortest paths are breadth-first within one window up to the template depth,
  * visiting neighbours in researcher-ID order, so ties resolve deterministically (Neptune may pick
  * another path of the same length). It runs no other query.
  */
final class TemplateGraph private (
    nodes: Map[String, GraphNode],
    out: Map[String, Vector[GraphEdge]],
    in: Map[String, Vector[GraphEdge]],
    co: Map[String, Map[String, Vector[TemplateGraph.CoEdge]]]
):
  import TemplateGraph.*

  def store: InMemoryGraphStore = new InMemoryGraphStore(respond)

  def respond(q: GraphQuery): Either[String, Vector[ujson.Obj]] =
    val p = q.parameters.value
    def str(k: String) = p.get(k).flatMap(_.strOpt).toRight(s"${q.templateId}: no $k")
    def int(k: String) = p.get(k).flatMap(_.numOpt).map(_.toInt).toRight(s"${q.templateId}: no $k")
    q.templateId match
      case "node-v1" =>
        str("id").map(id => nodes.get(id).toVector.map(nodeRow))
      case "direct-coauthors-v1" =>
        for id <- str("id"); w <- str("window"); l <- int("limit")
        yield adjacency(w, id)
          .sortBy(e => (-e.weight, e.other(id)))
          .take(l)
          .map { e =>
            val o = e.other(id)
            ujson.Obj(
              "researcherId" -> o,
              "displayName" -> displayName(o),
              "edgeId" -> e.graphEdgeId,
              "jointPublicationCount" -> e.weight.toDouble,
              "firstYear" -> e.first,
              "lastYear" -> e.last,
              "workIds" -> e.works.mkString("|"),
              "evidencePointer" -> e.pointer
            )
          }
      case "coauthor-pair-v1" =>
        for a <- str("a"); b <- str("b"); w <- str("window")
        yield adjacency(w, a)
          .find(_.other(a) == b)
          .toVector
          .map(e =>
            ujson.Obj(
              "edgeId" -> e.graphEdgeId,
              "jointPublicationCount" -> e.weight.toDouble,
              "firstYear" -> e.first,
              "lastYear" -> e.last,
              "workIds" -> e.works.mkString("|"),
              "evidencePointer" -> e.pointer
            )
          )
      case ShortestPath(d) =>
        for a <- str("from"); b <- str("to"); w <- str("window")
        yield bfs(w, a, b, d.toInt).toVector.map { hops =>
          ujson.Obj(
            "researchers" -> ujson.Arr.from(a +: hops.map(_._1)),
            "edges" -> ujson.Arr.from(hops.map(_._2.graphEdgeId)),
            "workIds" -> ujson.Arr.from(hops.map(_._2.works.mkString("|")))
          )
        }
      case t @ ("neighbors-out-v1" | "neighbors-in-v1" | "neighbors-both-v1") =>
        for
          id <- str("id")
          l <- int("limit")
          types <- p
            .get("edgeTypes")
            .flatMap(_.arrOpt)
            .map(_.flatMap(_.strOpt).toSet)
            .toRight(s"$t: no edgeTypes")
        yield
          val outs = if t != "neighbors-in-v1" then
            out.getOrElse(id, Vector.empty).map(e => e -> e.toId)
          else Vector.empty
          val ins = if t != "neighbors-out-v1" then
            in.getOrElse(id, Vector.empty).map(e => e -> e.fromId)
          else Vector.empty
          (outs ++ ins)
            .filter((e, _) => types(e.edgeType))
            .sortBy((e, m) => (m, e.edgeId))
            .take(l)
            .map((e, m) =>
              ujson.Obj(
                "edgeType" -> e.edgeType,
                "edgeId" -> e.edgeId,
                "nodeId" -> m,
                "labels" -> ujson.Arr.from(nodes.get(m).map(_.label).toVector)
              )
            )
      case "work-authors-v1" =>
        for id <- str("id"); l <- int("limit")
        yield in
          .getOrElse(id, Vector.empty)
          .filter(e => e.edgeType == GraphLabels.Authored && Ids.isResearcherId(e.fromId))
          .sortBy(_.fromId)
          .take(l)
          .map(e =>
            ujson.Obj(
              "researcherId" -> e.fromId,
              "displayName" -> displayName(e.fromId),
              "edgeId" -> e.edgeId
            )
          )
      case "researcher-works-v1" =>
        for id <- str("id"); l <- int("limit")
        yield out
          .getOrElse(id, Vector.empty)
          .filter(_.edgeType == GraphLabels.Authored)
          .map(e => (e, nodes.get(e.toId)))
          .sortBy((e, n) =>
            (-n.flatMap(_.properties.get("year")).flatMap(_.toIntOption).getOrElse(0), e.toId)
          )
          .take(l)
          .map((e, n) =>
            ujson.Obj(
              "workId" -> e.toId,
              "title" -> n
                .flatMap(_.properties.get("title"))
                .fold[ujson.Value](ujson.Null)(ujson.Str(_)),
              "year" -> n
                .flatMap(_.properties.get("year"))
                .flatMap(_.toIntOption)
                .fold[ujson.Value](ujson.Null)(ujson.Num(_)),
              "edgeId" -> e.edgeId
            )
          )
      case other => Left(s"template-graph-v1 does not answer $other")

  private def adjacency(window: String, id: String): Vector[CoEdge] =
    co.get(window).flatMap(_.get(id)).getOrElse(Vector.empty)

  private def displayName(id: String): ujson.Value =
    nodes
      .get(id)
      .flatMap(_.properties.get("displayName"))
      .fold[ujson.Value](ujson.Null)(ujson.Str(_))

  /** Hops (next researcher, edge) of one shortest path from a to b within `depth` hops. */
  private def bfs(
      window: String,
      a: String,
      b: String,
      depth: Int
  ): Option[Vector[(String, CoEdge)]] =
    val parent = scala.collection.mutable.HashMap[String, (String, CoEdge)]()
    val seen = scala.collection.mutable.HashSet(a)
    var frontier = Vector(a)
    var level = 0
    while frontier.nonEmpty && level < depth && !seen(b) do
      val next = Vector.newBuilder[String]
      frontier.foreach { x =>
        adjacency(window, x).sortBy(_.other(x)).foreach { e =>
          val y = e.other(x)
          if !seen(y) then
            seen += y
            parent(y) = (x, e)
            next += y
        }
      }
      frontier = next.result()
      level += 1
    Option.when(seen(b) && a != b) {
      Iterator
        .iterate(b)(y => parent(y)._1)
        .takeWhile(_ != a)
        .map(y => (y, parent(y)._2))
        .toVector
        .reverse
    }

object TemplateGraph:
  val Version = "template-graph-v1"
  private val ShortestPath = "^shortest-path-d(\\d)-v1$".r

  final case class CoEdge(
      graphEdgeId: String,
      a: String,
      b: String,
      window: String,
      weight: Long,
      first: Int,
      last: Int,
      works: Vector[String],
      pointer: String
  ):
    def other(x: String): String = if x == a then b else a

  private def nodeRow(n: GraphNode): ujson.Obj =
    ujson.Obj(
      "id" -> n.nodeId,
      "labels" -> ujson.Arr(n.label),
      "properties" -> ujson.Obj.from(
        n.properties.toVector.sortBy(_._1).map((k, v) => k -> ujson.Str(v))
      )
    )

  /** @param edges
    *   non-coauthor relationships (AUTHORED, AFFILIATED_WITH, HAS_TOPIC, PUBLISHED_IN); a
    *   COAUTHORED_WITH graph edge is refused here, the relationship comes from `coauthorEdges`
    * @param coauthorEdges
    *   the window-scoped coauthor-edge records (one per pair per window)
    * @param evidenceBase
    *   the base of the evidence pointer (the run folder, as the Neptune export uses it)
    */
  def build(
      nodes: Iterable[GraphNode],
      edges: Iterable[GraphEdge],
      coauthorEdges: Iterable[CoauthorEdge],
      policyVersion: String,
      evidenceBase: String
  ): Either[String, TemplateGraph] =
    val es = edges.toVector
    if es.exists(_.edgeType == GraphLabels.CoauthoredWith) then
      Left("COAUTHORED_WITH comes from the coauthor-edge records, not graph edges")
    else
      val base = evidenceBase.stripSuffix("/")
      val coEdges = coauthorEdges.toVector.map { r =>
        CoEdge(
          Ids.graphEdge(
            GraphLabels.CoauthoredWith,
            r.researcherA,
            r.researcherB,
            policyVersion,
            r.edgeId
          ),
          r.researcherA,
          r.researcherB,
          r.window.label,
          r.jointPublicationCount,
          r.firstCollaborationYear,
          r.lastCollaborationYear,
          r.representativeWorkIds,
          s"$base/coauthor-edges/windowLabel=${r.window.label}#${r.edgeId}"
        )
      }
      val byWindow = coEdges
        .groupBy(_.window)
        .view
        .mapValues { ws =>
          ws.flatMap(e => Vector(e.a -> e, e.b -> e)).groupMap(_._1)(_._2)
        }
        .toMap
      Right(
        new TemplateGraph(
          nodes.map(n => n.nodeId -> n).toMap,
          es.groupBy(_.fromId),
          es.groupBy(_.toId),
          byWindow
        )
      )
