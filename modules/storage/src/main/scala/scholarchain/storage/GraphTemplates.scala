package scholarchain.storage

import scholarchain.core.{GraphLabels, Ids}

/** A validated, allowlisted graph query: a template ID, the constant template text and the bound
  * parameters. User text never becomes query text: it travels only in `parameters`. The constructor
  * is private to the module, so a query exists only if [[GraphTemplates]] built it.
  */
final case class GraphQuery private[storage] (
    templateId: String,
    cypher: String,
    parameters: ujson.Obj
)

/** The kind a template parameter must have; each kind has one validator. */
enum ParamKind:
  case ResearcherId, WorkId, NodeId, Window, Limit, EdgeTypes

/** One allowlisted read-only template: constant text and its named, typed parameters. */
final case class GraphTemplate(id: String, cypher: String, params: Vector[(String, ParamKind)])

/** Text check shared by the allowlist test and the Neptune adapter: a template may not write. */
object ReadOnlyCypher:
  private val Writes =
    "\\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE|DROP|FOREACH|CALL|LOAD|START)\\b".r

  def violation(cypher: String): Option[String] =
    Writes.findFirstIn(cypher.toUpperCase(java.util.Locale.ROOT)).map(w => s"write keyword $w")

/** Allowlisted, parameterized openCypher templates. Runtime services get nothing else.
  *
  * COAUTHORED_WITH is stored once per time window (one edge per researcher pair per configured
  * window, so overlapping windows give parallel edges). Every template that reads COAUTHORED_WITH
  * therefore filters on exactly one `e.window = $window`, and the window must be one of the
  * configured labels. Depth and edge types cannot be parameters in openCypher, so depth is part of
  * the template ID (one constant template per depth) and edge types are a parameter list tested
  * with `type(e) IN $edgeTypes` after Scala-side allowlisting.
  */
final class GraphTemplates(windows: Set[String]):
  import GraphTemplates.*

  private def checkNodeId(id: String) =
    Either.cond(
      id.nonEmpty && id.length <= MaxIdLength && !id.exists(c => c.isWhitespace || c.isControl),
      id,
      "unsupported node ID"
    )
  private def checkResearcher(id: String) =
    Either.cond(Ids.isResearcherId(id), id, "not a researcher ID")
  private def checkWork(id: String) = Either.cond(Ids.isWorkId(id), id, "not a work ID")
  private def checkLimit(n: Int) =
    Either.cond(n >= 1 && n <= MaxLimit, n, s"limit must be 1..$MaxLimit")
  private def checkWindow(w: String) =
    Either.cond(windows.contains(w), w, s"window '${w.take(20)}' is not a configured window")

  private def query(id: String, params: (String, ujson.Value)*): GraphQuery =
    GraphQuery(id, ById(id).cypher, ujson.Obj.from(params))

  def node(id: String): Either[String, GraphQuery] =
    checkNodeId(id).map(i => query("node-v1", "id" -> ujson.Str(i)))

  def directCoauthors(researcherId: String, window: String, limit: Int) =
    for r <- checkResearcher(researcherId); w <- checkWindow(window); l <- checkLimit(limit)
    yield query(
      "direct-coauthors-v1",
      "id" -> ujson.Str(r),
      "window" -> ujson.Str(w),
      "limit" -> ujson.Num(l)
    )

  def coauthorPair(a: String, b: String, window: String) =
    for
      x <- checkResearcher(a); y <- checkResearcher(b); w <- checkWindow(window)
      _ <- Either.cond(x != y, (), "pair endpoints must differ")
    yield query(
      "coauthor-pair-v1",
      "a" -> ujson.Str(x),
      "b" -> ujson.Str(y),
      "window" -> ujson.Str(w)
    )

  def shortestPath(from: String, to: String, window: String, maxDepth: Int) =
    for
      a <- checkResearcher(from); b <- checkResearcher(to); w <- checkWindow(window)
      d <- Either.cond(
        maxDepth >= 1 && maxDepth <= MaxDepth,
        maxDepth,
        s"depth must be 1..$MaxDepth"
      )
      _ <- Either.cond(a != b, (), "path endpoints must differ")
    yield query(
      s"shortest-path-d$d-v1",
      "from" -> ujson.Str(a),
      "to" -> ujson.Str(b),
      "window" -> ujson.Str(w)
    )

  /** Neighbors over the window-free edge types only; coauthors need a window (directCoauthors). */
  def neighbors(id: String, edgeTypes: Set[String], direction: String, limit: Int) =
    for
      i <- checkNodeId(id); l <- checkLimit(limit)
      types <- Either.cond(
        edgeTypes.nonEmpty && edgeTypes.subsetOf(NeighborEdgeTypes),
        edgeTypes.toVector.sorted,
        s"edge types must be a non-empty subset of ${NeighborEdgeTypes.toVector.sorted.mkString(",")}"
      )
      template <- direction match
        case "out"  => Right("neighbors-out-v1")
        case "in"   => Right("neighbors-in-v1")
        case "both" => Right("neighbors-both-v1")
        case other  => Left(s"direction must be in|out|both, not '${other.take(10)}'")
    yield query(
      template,
      "id" -> ujson.Str(i),
      "edgeTypes" -> ujson.Arr.from(types),
      "limit" -> ujson.Num(l)
    )

  def workAuthors(workId: String, limit: Int) =
    for w <- checkWork(workId); l <- checkLimit(limit)
    yield query("work-authors-v1", "id" -> ujson.Str(w), "limit" -> ujson.Num(l))

  def researcherWorks(researcherId: String, limit: Int) =
    for r <- checkResearcher(researcherId); l <- checkLimit(limit)
    yield query("researcher-works-v1", "id" -> ujson.Str(r), "limit" -> ujson.Num(l))

object GraphTemplates:
  val MaxDepth = 6
  val MaxLimit = 200
  val MaxIdLength = 450

  /** Window-free relationship types a neighbors query may follow. */
  val NeighborEdgeTypes: Set[String] =
    Set(
      GraphLabels.Authored,
      GraphLabels.HasTopic,
      GraphLabels.AffiliatedWith,
      GraphLabels.PublishedIn
    )

  def forConfig(windows: Iterable[scholarchain.core.TimeWindow]): GraphTemplates =
    new GraphTemplates(windows.map(_.label).toSet)

  private def t(id: String, params: (String, ParamKind)*)(cypher: String) =
    GraphTemplate(id, cypher.stripMargin.replace("\n", " ").trim, params.toVector)

  private def neighbors(id: String, pattern: String) =
    t(
      id,
      "id" -> ParamKind.NodeId,
      "edgeTypes" -> ParamKind.EdgeTypes,
      "limit" -> ParamKind.Limit
    )(
      s"""MATCH $pattern
         |WHERE id(n) = $$id AND type(e) IN $$edgeTypes
         |RETURN type(e) AS edgeType, id(e) AS edgeId, id(m) AS nodeId, labels(m) AS labels
         |ORDER BY nodeId ASC LIMIT $$limit""".stripMargin
    )

  private def shortestPath(depth: Int) =
    t(
      s"shortest-path-d$depth-v1",
      "from" -> ParamKind.ResearcherId,
      "to" -> ParamKind.ResearcherId,
      "window" -> ParamKind.Window
    )(
      s"""MATCH p = (a:Researcher)-[:COAUTHORED_WITH*1..$depth]-(b:Researcher)
         |WHERE id(a) = $$from AND id(b) = $$to AND all(e IN relationships(p) WHERE e.window = $$window)
         |RETURN [n IN nodes(p) | id(n)] AS researchers, [e IN relationships(p) | id(e)] AS edges,
         |[e IN relationships(p) | e.representativeWorkIds] AS workIds
         |ORDER BY length(p) ASC LIMIT 1""".stripMargin
    )

  val All: Vector[GraphTemplate] = Vector(
    t("node-v1", "id" -> ParamKind.NodeId)(
      "MATCH (n) WHERE id(n) = $id RETURN id(n) AS id, labels(n) AS labels, properties(n) AS properties LIMIT 1"
    ),
    t(
      "direct-coauthors-v1",
      "id" -> ParamKind.ResearcherId,
      "window" -> ParamKind.Window,
      "limit" -> ParamKind.Limit
    )(
      """MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(c:Researcher)
        |WHERE id(r) = $id AND e.window = $window
        |RETURN id(c) AS researcherId, c.displayName AS displayName, id(e) AS edgeId,
        |e.jointPublicationCount AS jointPublicationCount, e.firstCollaborationYear AS firstYear,
        |e.lastCollaborationYear AS lastYear, e.representativeWorkIds AS workIds,
        |e.evidencePointer AS evidencePointer
        |ORDER BY jointPublicationCount DESC, researcherId ASC LIMIT $limit""".stripMargin
    ),
    t(
      "coauthor-pair-v1",
      "a" -> ParamKind.ResearcherId,
      "b" -> ParamKind.ResearcherId,
      "window" -> ParamKind.Window
    )(
      """MATCH (a:Researcher)-[e:COAUTHORED_WITH]-(b:Researcher)
        |WHERE id(a) = $a AND id(b) = $b AND e.window = $window
        |RETURN id(e) AS edgeId, e.jointPublicationCount AS jointPublicationCount,
        |e.firstCollaborationYear AS firstYear, e.lastCollaborationYear AS lastYear,
        |e.representativeWorkIds AS workIds, e.evidencePointer AS evidencePointer LIMIT 1""".stripMargin
    )
  ) ++ (1 to MaxDepth).map(shortestPath) ++ Vector(
    neighbors("neighbors-out-v1", "(n)-[e]->(m)"),
    neighbors("neighbors-in-v1", "(n)<-[e]-(m)"),
    neighbors("neighbors-both-v1", "(n)-[e]-(m)"),
    t("work-authors-v1", "id" -> ParamKind.WorkId, "limit" -> ParamKind.Limit)(
      """MATCH (r:Researcher)-[a:AUTHORED]->(w:Work)
        |WHERE id(w) = $id
        |RETURN id(r) AS researcherId, r.displayName AS displayName, id(a) AS edgeId
        |ORDER BY researcherId ASC LIMIT $limit""".stripMargin
    ),
    t("researcher-works-v1", "id" -> ParamKind.ResearcherId, "limit" -> ParamKind.Limit)(
      """MATCH (r:Researcher)-[a:AUTHORED]->(w:Work)
        |WHERE id(r) = $id
        |RETURN id(w) AS workId, w.title AS title, w.year AS year, id(a) AS edgeId
        |ORDER BY year DESC, workId ASC LIMIT $limit""".stripMargin
    )
  )

  val ById: Map[String, GraphTemplate] = All.map(x => x.id -> x).toMap

  /** True when the query is exactly an allowlisted template with exactly its declared parameters.
    */
  def isAllowlisted(q: GraphQuery): Boolean =
    ById.get(q.templateId).exists { tpl =>
      tpl.cypher == q.cypher && q.parameters.value.keySet == tpl.params.map(_._1).toSet
    }
