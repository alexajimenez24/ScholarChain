package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.GraphTemplates
import upickle.default.ReadWriter

/** Typed constraints of one request (the `constraints` block of POST /v1/query).
  *
  * `researcherIds` is the disambiguation path: after an ambiguous answer listed candidates, the
  * caller names the canonical ID it means. IDs are validated as researcher IDs, never parsed as
  * text.
  */
final case class Constraints(
    yearFrom: Option[Int] = None,
    yearTo: Option[Int] = None,
    topics: Vector[String] = Vector.empty,
    institutions: Vector[String] = Vector.empty,
    venues: Vector[String] = Vector.empty,
    researchers: Vector[String] = Vector.empty,
    researcherIds: Vector[String] = Vector.empty
) derives ReadWriter

final case class AnalysisOptions(
    rankingMetric: Option[CollaborationMetric] = None,
    includeTeamSize: Boolean = true,
    includeBridgeMetrics: Boolean = true,
    includeCommunities: Boolean = true,
    includePaths: Boolean = true
) derives ReadWriter

final case class RetrievalOptions(
    graphLimit: Int = 100,
    vectorLimit: Int = 30,
    maxPathDepth: Int = 6,
    limit: Int = 10
) derives ReadWriter

/** The runtime workflow input; the query service maps the POST /v1/query body onto it. */
final case class AnalysisRequest(
    question: String,
    constraints: Constraints = Constraints(),
    analysis: AnalysisOptions = AnalysisOptions(),
    retrieval: RetrievalOptions = RetrievalOptions()
) derives ReadWriter

enum WorkflowError:
  case InvalidRequest(message: String)
  case Unsupported(message: String)
  case Downstream(stage: String, message: String)
  case Timeout(stage: String, millis: Long)
  case BudgetExceeded(stage: String, message: String)

  def describe: String = this match
    case InvalidRequest(m)    => s"invalid request: $m"
    case Unsupported(m)       => s"unsupported question: $m"
    case Downstream(s, m)     => s"$s failed: $m"
    case Timeout(s, ms)       => s"$s timed out after $ms ms"
    case BudgetExceeded(s, m) => s"$s exceeded its budget: $m"

/** The allowlist of plan operations (`plan-operations-v1`).
  *
  * A plan may hold only these operations, and each operation names the only graph templates and
  * vector document types its stages may use. The graph runner of one query is built from the union
  * for the plan's operations plus the resolver's own templates, so a stage cannot run a template
  * its operation does not allow. Templates come from storage's [[GraphTemplates]] allowlist;
  * nothing here is query text.
  */
object PlanOperations:
  val Version = "plan-operations-v1"

  /** What one operation may touch. `metricStore` means point reads and rankings of Spark metrics.
    */
  final case class Allowance(templates: Set[String], docTypes: Set[String], metricStore: Boolean)

  val ShortestPathTemplates: Set[String] =
    (1 to GraphTemplates.MaxDepth).map(d => s"shortest-path-d$d-v1").toSet

  /** Templates that read COAUTHORED_WITH: each must carry exactly one configured window. */
  val WindowedTemplates: Set[String] =
    ShortestPathTemplates ++ Set("direct-coauthors-v1", "coauthor-pair-v1")

  /** Used by the resolver for every named researcher (affiliations, names, coauthor context). */
  val ResolverTemplates: Set[String] = Set("neighbors-out-v1", "node-v1", "direct-coauthors-v1")

  private val Profile = ResearcherProfileTemplate.docType
  private val Community = CommunityProfileTemplate.docType
  private val PathDoc = PathSummaryTemplate.docType
  private val TopicDoc = TopicSummaryTemplate.docType
  private val Abstract = PaperAbstractTemplate.docType

  import QueryOperation.*
  val Allowlist: Map[QueryOperation, Allowance] = Map(
    RESEARCHER_RANKING -> Allowance(Set("node-v1"), Set(Profile), metricStore = true),
    MAX_TEAM_SIZE -> Allowance(Set("node-v1"), Set(Abstract), metricStore = true),
    DIRECT_COAUTHORS -> Allowance(
      Set("direct-coauthors-v1", "coauthor-pair-v1"),
      Set(Profile),
      metricStore = true
    ),
    SHORTEST_PATH -> Allowance(
      ShortestPathTemplates ++ Set("coauthor-pair-v1", "node-v1"),
      Set(PathDoc),
      metricStore = false
    ),
    COMPONENT_LOOKUP -> Allowance(Set.empty, Set(Profile), metricStore = true),
    COMMUNITY_COMPARISON -> Allowance(Set("direct-coauthors-v1"), Set(Community), true),
    BRIDGE_RANKING -> Allowance(Set("direct-coauthors-v1"), Set(Community, Profile), true),
    TEMPORAL_COMPARISON -> Allowance(Set.empty, Set(Profile), metricStore = true),
    TOPIC_FILTER -> Allowance(
      Set("neighbors-in-v1", "work-authors-v1", "node-v1"),
      Set(TopicDoc),
      true
    ),
    INSTITUTION_FILTER -> Allowance(Set("neighbors-in-v1"), Set(TopicDoc), metricStore = true),
    SEMANTIC_EXPLANATION -> Allowance(
      Set.empty,
      Set(Profile, Community, PathDoc, TopicDoc, Abstract),
      metricStore = false
    )
  )

  def templatesFor(ops: Iterable[QueryOperation]): Set[String] =
    ResolverTemplates ++ ops.flatMap(o => Allowlist.get(o).toVector.flatMap(_.templates))

  def docTypesFor(ops: Iterable[QueryOperation]): Set[String] =
    ops.flatMap(o => Allowlist.get(o).toVector.flatMap(_.docTypes)).toSet

/** Validation of a [[QueryPlan]] against `query-plan-v1` (`prompts/schemas/query-plan-v1.json`):
  * the core contract checks plus the bounds of this planner. Every plan is validated before any
  * stage uses it, whoever produced it.
  */
object QueryPlanRules:
  val SchemaFile = "prompts/schemas/query-plan-v1.json"
  val MaxNames = 4
  val MaxNameChars = 80
  val MaxFilters = 3
  val MaxFilterChars = 80
  val MaxLimit = 50
  val NamePattern = "^\\p{L}[\\p{L} .'\\-]{0,79}$".r
  private val FilterPattern = "^[\\p{L}\\p{N}][\\p{L}\\p{N} .,'&()\\-]{0,79}$".r

  def validate(p: QueryPlan, cfg: ScholarChainConfig): Either[String, QueryPlan] =
    val maxDepth = math.min(cfg.retrieval.maxPathDepth, GraphTemplates.MaxDepth)
    def filters(what: String, xs: Vector[String]) =
      Option.when(xs.size > MaxFilters)(s"more than $MaxFilters $what").toVector ++
        xs.filterNot(FilterPattern.matches).map(x => s"$what '${x.take(20)}' is not plain text")
    val errors = Validate.queryPlan(p) ++
      Option.when(p.plannerVersion != QueryPlanner.Version)(
        s"plannerVersion ${p.plannerVersion} is not ${QueryPlanner.Version}"
      ) ++
      Option.when(p.question.length > cfg.operations.maxQuestionChars)("question is too long") ++
      Option
        .when(!p.operations.forall(PlanOperations.Allowlist.contains))("operation not allowed") ++
      Option.when(p.operations.distinct.size != p.operations.size)("operations repeat") ++
      Option.when(p.researcherNames.size > MaxNames)(s"more than $MaxNames researcher names") ++
      p.researcherNames
        .filterNot(NamePattern.matches)
        .map(n => s"name '${n.take(20)}' is not a name") ++
      filters("topic", p.topics) ++ filters("institution", p.institutions) ++
      filters("venue", p.venues) ++
      Option.when(p.limit < 1 || p.limit > MaxLimit)(s"limit must be 1..$MaxLimit") ++
      Option.when(p.maxPathDepth < 1 || p.maxPathDepth > maxDepth)(
        s"maxPathDepth must be 1..$maxDepth"
      )
    Either.cond(errors.isEmpty, p, errors.mkString("; "))

/** QueryPlannerAgent (`planner-v1`): deterministic parsing of the question into a typed
  * [[QueryPlan]].
  *
  * No model is called. The question yields at most researcher names (reduced to letters, spaces,
  * dots, hyphens and apostrophes), a year window, topic and institution phrases, a ranking metric
  * and a limit. Later stages use them only as typed lookup keys and validated template parameters;
  * no part of the text becomes database code or an AWS operation. Typed `constraints` win over the
  * text. Names are recognised only in explicit forms (quoted, "between X and Y", "coauthors of X",
  * "has X collaborated with Y", ...); an unrecognised name is not guessed.
  */
final class QueryPlanner(cfg: ScholarChainConfig, graphRunId: String, snapshot: String):
  import QueryPlanner.*

  private val windows = cfg.graph.windows
  private val minYear = windows.map(_.startYear).min
  private val maxYear = windows.map(_.endYear).max

  def plan(queryId: String, req: AnalysisRequest): Either[WorkflowError, QueryPlan] =
    val q = req.question.trim
    if q.isEmpty then Left(WorkflowError.InvalidRequest("question is empty"))
    else if q.length > cfg.operations.maxQuestionChars then
      Left(
        WorkflowError.InvalidRequest(
          s"question exceeds ${cfg.operations.maxQuestionChars} characters"
        )
      )
    else if q.exists(c => c.isControl && c != '\n' && c != '\t') then
      Left(WorkflowError.InvalidRequest("question contains control characters"))
    else
      for
        w <- window(q, req.constraints)
        plan <- build(queryId, q, req, w)
        valid <- QueryPlanRules.validate(plan, cfg).left.map(WorkflowError.InvalidRequest(_))
      yield valid

  private def build(
      queryId: String,
      q: String,
      req: AnalysisRequest,
      w: TimeWindow
  ): Either[WorkflowError, QueryPlan] =
    val lower = q.toLowerCase(java.util.Locale.ROOT)
    def has(r: scala.util.matching.Regex) = r.findFirstIn(lower).isDefined
    val pathNames = firstPair(q, Vector(PathBetween, PathFromTo, ConnectedTo))
    val pairNames = firstPair(q, Vector(DirectPair, CollaborationBetween))
    val singleNames = Vector(CoauthorsOf, CollaboratedWith, AboutResearcher)
      .flatMap(_.findAllMatchIn(q).map(m => cleanName(m.group(1))))
      .filter(validName)
    val quoted = Quoted.findAllMatchIn(q).map(m => cleanName(m.group(1))).filter(validName).toVector
    val names = (req.constraints.researchers.map(cleanName).filter(validName) ++ quoted ++
      pathNames ++ pairNames ++ singleNames).distinct.take(QueryPlanRules.MaxNames)
    val a = req.analysis
    val ops = Vector.newBuilder[QueryOperation]
    val wantsRanking = has(RankingWords) && (names.isEmpty || has(TopWords))
    val wantsPath = (pathNames.nonEmpty || (has(PathWords) && names.size >= 2)) && a.includePaths
    // A direct-collaboration cue with two names (quoted or typed) is a pair question too.
    val wantsCoauthors =
      pairNames.nonEmpty || CoauthorsOf.findFirstIn(q).nonEmpty ||
        CollaboratedWith.findFirstIn(q).nonEmpty || (has(DirectWords) && names.size >= 2)
    if wantsRanking then ops += QueryOperation.RESEARCHER_RANKING
    if has(TeamWords) && a.includeTeamSize then ops += QueryOperation.MAX_TEAM_SIZE
    if wantsCoauthors && names.nonEmpty then ops += QueryOperation.DIRECT_COAUTHORS
    if wantsPath && names.size >= 2 then ops += QueryOperation.SHORTEST_PATH
    if has(ComponentWords) then ops += QueryOperation.COMPONENT_LOOKUP
    if has(CommunityWords) && a.includeCommunities then ops += QueryOperation.COMMUNITY_COMPARISON
    if has(BridgeWords) && a.includeBridgeMetrics then ops += QueryOperation.BRIDGE_RANKING
    if has(TemporalWords) then ops += QueryOperation.TEMPORAL_COMPARISON
    val topics = (req.constraints.topics.map(cleanFilter) ++
      TopicIn.findAllMatchIn(q).map(m => cleanFilter(m.group(1))).filter(topicLike(_, names)))
      .filter(_.nonEmpty)
      .distinct
      .take(QueryPlanRules.MaxFilters)
    val institutions = (req.constraints.institutions.map(cleanFilter) ++
      InstitutionAt.findAllMatchIn(q).map(m => cleanFilter(m.group(1))))
      .filter(i => i.nonEmpty && !names.exists(n => i.equalsIgnoreCase(n)))
      .distinct
      .take(QueryPlanRules.MaxFilters)
    val venues = req.constraints.venues.map(cleanFilter).filter(_.nonEmpty).distinct.take(3)
    if topics.nonEmpty then ops += QueryOperation.TOPIC_FILTER
    if institutions.nonEmpty then ops += QueryOperation.INSTITUTION_FILTER
    ops += QueryOperation.SEMANTIC_EXPLANATION
    val operations = ops.result().distinct
    val structural = operations.filterNot(Descriptive)
    if structural.isEmpty && names.isEmpty && topics.isEmpty && institutions.isEmpty then
      Left(
        WorkflowError.Unsupported(
          "the question names no researcher and no supported collaboration operation " +
            "(ranking, team size, coauthors, paths, components, communities, bridges, " +
            "temporal change, topic or institution)"
        )
      )
    else
      val metric = a.rankingMetric.getOrElse(metricFromText(lower, operations))
      val limit = TopN
        .findFirstMatchIn(lower)
        .flatMap(m => m.group(1).toIntOption.orElse(NumberWords.get(m.group(1))))
        .getOrElse(req.retrieval.limit)
      val depthCap = math.min(cfg.retrieval.maxPathDepth, GraphTemplates.MaxDepth)
      Right(
        QueryPlan(
          SchemaVersions.QueryPlan,
          queryId,
          q,
          operations,
          names,
          w,
          topics,
          institutions,
          venues,
          metric,
          math.max(1, math.min(QueryPlanRules.MaxLimit, limit)),
          math.max(1, math.min(depthCap, req.retrieval.maxPathDepth)),
          true,
          Version,
          Provenance(
            SourceType.SCHOLARCHAIN,
            queryId,
            snapshot,
            graphRunId,
            SchemaVersions.QueryPlan,
            Some(Version)
          )
        )
      )

  /** Constraints win; otherwise the first year pattern in the text; otherwise the widest window. */
  private def window(q: String, c: Constraints): Either[WorkflowError, TimeWindow] =
    def y(s: String) = s.toInt
    val fromText: (Option[Int], Option[Int]) =
      Between
        .findFirstMatchIn(q)
        .orElse(Range.findFirstMatchIn(q))
        .map(m => (Some(y(m.group(1))), Some(y(m.group(2)))))
        .orElse(Since.findFirstMatchIn(q).map { m =>
          val start =
            if m.group(1).equalsIgnoreCase("after") then y(m.group(2)) + 1 else y(m.group(2))
          (Some(start), None)
        })
        .orElse(InYear.findFirstMatchIn(q).map(m => (Some(y(m.group(1))), Some(y(m.group(1))))))
        .orElse(Before.findFirstMatchIn(q).map { m =>
          val end =
            if m.group(1).equalsIgnoreCase("before") then y(m.group(2)) - 1 else y(m.group(2))
          (None, Some(end))
        })
        .getOrElse((None, None))
    val w = TimeWindow(
      c.yearFrom.orElse(fromText._1).getOrElse(minYear),
      c.yearTo.orElse(fromText._2).getOrElse(maxYear)
    )
    val errs = Validate.window(w) ++
      Option.when(w.startYear > maxYear || w.endYear < minYear)(
        s"window ${w.label} lies outside the computed years $minYear-$maxYear"
      )
    Either.cond(errs.isEmpty, w, WorkflowError.InvalidRequest(errs.mkString("; ")))

  private def firstPair(q: String, rs: Vector[scala.util.matching.Regex]): Vector[String] =
    rs.iterator
      .flatMap(_.findAllMatchIn(q))
      .map(m => Vector(cleanName(m.group(1)), cleanName(m.group(2))))
      .find(_.forall(validName))
      .getOrElse(Vector.empty)

  /** The metric whose cue appears first in the question; C_r when there is none. */
  private def metricFromText(lower: String, ops: Vector[QueryOperation]): CollaborationMetric =
    val cues = MetricCues.flatMap((r, m) => r.findFirstMatchIn(lower).map(x => (x.start, m)))
    cues.minByOption(_._1).map(_._2).getOrElse {
      if ops == Vector(QueryOperation.BRIDGE_RANKING, QueryOperation.SEMANTIC_EXPLANATION) then
        CollaborationMetric.BRIDGE_SCORE
      else CollaborationMetric.DISTINCT_COAUTHOR_COUNT
    }

object QueryPlanner:
  val Version = "planner-v1"

  private val Descriptive: Set[QueryOperation] = Set(
    QueryOperation.SEMANTIC_EXPLANATION,
    QueryOperation.TOPIC_FILTER,
    QueryOperation.INSTITUTION_FILTER
  )

  private val Year = "((?:19|20)\\d{2})"
  private val Stop =
    "(?=\\s*[^\\p{L} .'\\-\\s]|\\s+(?:since|in|from|between|during|within|before|after|until|through|and\\s+(?:which|who|how|what))\\b|\\s*$)"
  private val NameText = "([\\p{L}][\\p{L} .'\\-]{1,79}?)"

  /** A name that may stand in straight or curly double quotes. */
  private val QName = "[\"“]?" + NameText + "[\"”]?"

  val Between = s"(?i)\\b(?:between|from)\\s+$Year\\s*(?:and|to|-|–|until|through)\\s*$Year\\b".r
  val Range = s"\\b$Year\\s*[-–]\\s*$Year\\b".r
  val Since = s"(?i)\\b(since|after|from)\\s+$Year\\b".r
  val InYear = s"(?i)\\b(?:in|during)\\s+$Year\\b".r
  val Before = s"(?i)\\b(before|until|through)\\s+$Year\\b".r

  val Quoted = "\"([^\"]{2,80})\"".r
  val PathBetween =
    s"(?i)\\b(?:path|paths|connected|connection|hops?|distance|chain)\\b.*?\\bbetween\\s+$NameText\\s+and\\s+$NameText$Stop".r
  val PathFromTo =
    s"(?i)\\b(?:path|paths|chain|hops?)\\s+from\\s+$NameText\\s+to\\s+$NameText$Stop".r
  val ConnectedTo =
    s"(?i)\\bhow\\s+(?:closely\\s+)?(?:is|are)\\s+$NameText\\s+connected\\s+(?:to|with)\\s+$NameText$Stop".r
  val DirectPair =
    s"(?i)\\b(?:has|have|did)\\s+$QName\\s+(?:ever\\s+)?(?:collaborated|coauthored|co-authored|published|worked)\\s+(?:together\\s+)?with\\s+$QName$Stop".r
  val CollaborationBetween =
    s"(?i)\\b(?:collaborations?|joint\\s+(?:publications|papers|work)|co-?authored\\s+(?:publications|papers))\\s+between\\s+$QName\\s+and\\s+$QName$Stop".r
  val CoauthorsOf =
    s"(?i)\\b(?:coauthors|co-authors|collaborators|direct\\s+collaborators)\\s+(?:of|for)\\s+$NameText$Stop".r
  val CollaboratedWith =
    s"(?i)\\bwho\\s+(?:has|did|have)\\s+$NameText\\s+(?:collaborated|coauthored|co-authored|worked|published)\\s+with\\b".r

  /** Direct-collaboration cues; with two names they plan a coauthor-pair check. */
  val DirectWords =
    "\\b(collaborated|coauthored|co-authored|worked together|joint publications?|joint papers?|direct coauthors?|direct collaborators?|collaborations? between)\\b".r
  val AboutResearcher =
    s"(?i)\\b(?:does|did|is|has)\\s+$NameText\\s+(?:have|belong|bridge|sit|rank|publish|appear|an?\\s+(?:bridge|broker))\\b".r

  val TopicIn =
    "(?i)\\b(?:in|on)\\s+(?!(?:19|20)\\d{2}\\b)(?:the\\s+)?(?:field\\s+of\\s+|area\\s+of\\s+|topic\\s+)?([a-z][a-z \\-]{2,60}?)(?:\\s+research)?(?=\\s+(?:since|between|from|after|before|until|during|and\\s+which|and\\s+who)\\b|\\s+(?:in|during)\\s+(?:the\\s+)?(?:years?\\s+|window\\s+|period\\s+)?(?:19|20)\\d{2}\\b|\\s*[?.,;]|\\s*$)".r
  val InstitutionAt =
    "\\bat\\s+(?:the\\s+)?((?:[A-Z][\\p{L}&.'\\-]*\\s+){0,5}(?:University|Institute|Laboratory|Lab|College|School|Center|Centre|Academy|Polytechnic|Corporation|Research)(?:\\s+(?:of|for|de|and)\\s+[A-Z][\\p{L}&.'\\-]*)*(?:\\s+[A-Z][\\p{L}&.'\\-]*)*)".r

  val TopN = "\\b(?:top|which|the|first)\\s+(\\d{1,2}|three|five|ten|twenty)\\b".r
  val NumberWords = Map("three" -> 3, "five" -> 5, "ten" -> 10, "twenty" -> 20)

  val RankingWords =
    "\\b(top|rank|ranks|ranking|most|highest|largest|biggest|broadest|strongest|leading|which researchers|who are the)\\b".r
  val TopWords = "\\b(top|rank|ranks|ranking)\\b".r
  val PathWords = "\\b(path|paths|hops?|distance|chain|connected to|connected with)\\b".r
  val TeamWords =
    "\\b(largest|biggest|large|maximum) (author )?teams?\\b|team sizes?|author teams|coauthors on one paper".r
  val ComponentWords = "\\b(component|components|reachable|connected at all|same component)\\b".r
  val CommunityWords = "\\bcommunit(y|ies)\\b".r
  val BridgeWords =
    "\\b(bridge|bridges|bridging|broker|brokers|betweenness|connect(?:ing|s)? (?:otherwise )?separate|between (?:collaboration )?communities|across communities)\\b".r
  val TemporalWords =
    "\\b(over time|changed?|changes|trend|trends|grown|growth|evolved?|across (?:the )?windows|compared (?:to|with) (?:the )?(?:earlier|later))\\b".r

  /** Cue patterns per metric (the documented measures; no composite score). */
  val MetricCues: Vector[(scala.util.matching.Regex, CollaborationMetric)] = Vector(
    "\\b(broadest|most distinct|distinct coauthors|most coauthors|most collaborators|widest|breadth|diverse sets? of coauthors)\\b".r -> CollaborationMetric.DISTINCT_COAUTHOR_COUNT,
    "\\b(strongest repeated|repeated collaboration|most frequent|intensity|weighted degree|most joint publications)\\b".r -> CollaborationMetric.WEIGHTED_DEGREE,
    "\\bcoauthor occurrences\\b".r -> CollaborationMetric.TOTAL_COAUTHOR_OCCURRENCES,
    "\\b(average team|average coauthors|mean team)\\b".r -> CollaborationMetric.AVERAGE_COAUTHORS_PER_PAPER,
    "\\b(largest (author )?teams?|biggest teams?|maximum team|coauthors on one paper)\\b".r -> CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER,
    "\\b(most publications|most papers|prolific|publication count)\\b".r -> CollaborationMetric.PUBLICATION_COUNT,
    "\\b(coreness|k-core|core number|most embedded|densest)\\b".r -> CollaborationMetric.CORENESS,
    "\\bparticipation coefficient\\b".r -> CollaborationMetric.PARTICIPATION_COEFFICIENT,
    "\\b(cross-community|coauthors outside|outside (?:their|own) communit)".r -> CollaborationMetric.CROSS_COMMUNITY_DEGREE,
    "\\b(bridge|bridges|bridging|broker|betweenness|connecting otherwise separate)\\b".r -> CollaborationMetric.BRIDGE_SCORE
  )

  private val NotTopics = Set(
    "window",
    "windows",
    "community",
    "communities",
    "component",
    "components",
    "team",
    "teams",
    "paper",
    "papers",
    "graph",
    "network",
    "networks",
    "total",
    "coauthor",
    "coauthors",
    "path",
    "paths",
    "hops",
    "period",
    "years",
    "year",
    "time",
    "common",
    "one",
    "their",
    "own",
    "which",
    "who",
    "all",
    "any",
    "separate",
    "collaboration"
  )

  /** Names are lookup keys only: letters, spaces, dots, hyphens and apostrophes survive. */
  def cleanName(raw: String): String =
    raw
      .replaceAll("[^\\p{L}\\s.'\\-]", " ")
      .replaceAll("\\s+", " ")
      .trim
      .replaceFirst("(?i)^(?:the\\s+researcher|researcher|dr|prof|professor)\\.?\\s+", "")
      .stripSuffix("'s")
      .stripSuffix("'")
      .trim
      .take(QueryPlanRules.MaxNameChars)

  private def validName(n: String): Boolean =
    QueryPlanRules.NamePattern.matches(n) && n.count(_.isLetter) >= 2 &&
      n.split(' ').exists(_.headOption.exists(_.isUpper))

  /** Topic, institution and venue phrases: letters, digits and a few separators only. */
  def cleanFilter(raw: String): String =
    raw
      .replaceAll("[^\\p{L}\\p{N}\\s.,'&()\\-]", " ")
      .replaceAll("\\s+", " ")
      .trim
      .dropWhile(c => !c.isLetterOrDigit)
      .take(QueryPlanRules.MaxFilterChars)
      .trim

  /** Words that never start a topic phrase ("their own community", "the same component"). */
  private val NotTopicStart = Set(
    "their",
    "own",
    "which",
    "who",
    "all",
    "any",
    "one",
    "common",
    "total",
    "same",
    "this",
    "that",
    "these",
    "those",
    "each",
    "every",
    "the",
    "a",
    "an"
  )

  /** A topic phrase is refused when its head noun (last word) or every word is a graph term, or it
    * starts with a determiner; a graph term inside a longer name ("Network Time Synchronization
    * Technologies") is kept.
    */
  private def topicLike(t: String, names: Vector[String]): Boolean =
    val words =
      t.toLowerCase(java.util.Locale.ROOT).split("[ \\-]+").toVector.filter(_.nonEmpty)
    t.nonEmpty && words.nonEmpty && !t.exists(_.isDigit) && !NotTopics(words.last) &&
    !NotTopicStart(words.head) && !words.forall(NotTopics) &&
    !names.exists(n => t.equalsIgnoreCase(n) || n.toLowerCase.contains(t.toLowerCase))
