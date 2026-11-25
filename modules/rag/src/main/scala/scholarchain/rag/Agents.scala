package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import upickle.default.ReadWriter
import java.util.concurrent.{Callable, Executors, ThreadFactory, TimeUnit, TimeoutException}

/** The served runs and versions every stage stamps on its evidence. `graphRunId` is the Spark run
  * whose graph, metrics and summaries are loaded; `erRunId` is the er-v1 decision run that Spark
  * run consumed (its run manifest `inputs.entityResolution.runId`).
  */
final case class RuntimeContext(
    cfg: ScholarChainConfig,
    graphRunId: String,
    snapshot: String,
    erRunId: String
):
  require(Ids.isRunId(graphRunId) && Ids.isRunId(erRunId), "graph and ER run IDs must be run IDs")
  require(Validate.snapshotDate(snapshot), s"snapshot $snapshot is not YYYY-MM-DD")

  def metricVersion: String = cfg.graph.metricVersion
  def erPolicy: String = cfg.entityResolution.policyVersion
  val templates: GraphTemplates = GraphTemplates.forConfig(cfg.graph.windows)

  def evidence(
      t: EvidenceType,
      key: String,
      sourceUri: String,
      researchers: Vector[String],
      values: Map[String, String],
      policy: String,
      work: Option[String] = None,
      path: Option[Vector[String]] = None
  ): EvidenceItem =
    EvidenceItem(
      SchemaVersions.Evidence,
      Ids.evidence(t, s"$graphRunId|$key"),
      t,
      work,
      researchers,
      sourceUri,
      None,
      path,
      1.0,
      values,
      None,
      None,
      Provenance(
        SourceType.SCHOLARCHAIN,
        key,
        snapshot,
        graphRunId,
        SchemaVersions.Evidence,
        Some(policy)
      )
    )

/** The stores the stages read. Every one is a port: in-memory locally, AWS adapters in the cloud.
  */
final case class RuntimeStores(
    kv: KeyValueStore,
    graph: GraphStore,
    vectors: VectorStore,
    objects: ObjectStore,
    embedder: Embedder,
    directory: ResearcherDirectory,
    ranking: MetricRanking
)

/** One executed graph query, for the audit (template ID and window only, never query text). */
final case class GraphCall(
    stage: String,
    templateId: String,
    window: Option[String],
    rows: Int,
    millis: Long,
    status: String
) derives ReadWriter

/** Runs the graph queries of one request (`graph-runner-v1`).
  *
  * A query runs only if storage built it from an allowlisted template, the plan's operations allow
  * that template, every COAUTHORED_WITH template names exactly one configured window, fewer than
  * `maxQueries` ran before, and time is left before the request deadline. Each call gets at most
  * `perQueryTimeoutMs` (and never more than the time left); a call that does not return in time is
  * cancelled and fails the stage with `Timeout`. Rows above `maxRows` are refused, not truncated.
  */
final class GraphRunner(
    graph: GraphStore,
    allowed: Set[String],
    windows: Set[String],
    maxQueries: Int,
    maxRows: Int,
    perQueryTimeoutMs: Long,
    deadlineMs: Long,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val started = clock()
  private val calls = scala.collection.mutable.ArrayBuffer.empty[GraphCall]

  def executed: Vector[GraphCall] = calls.synchronized(calls.toVector)

  def run(
      stage: String,
      built: Either[String, GraphQuery]
  ): Either[WorkflowError, Vector[ujson.Obj]] =
    built.left.map(e => WorkflowError.InvalidRequest(s"$stage: $e")).flatMap { q =>
      val window = q.parameters.value.get("window").flatMap(_.strOpt)
      def record(rows: Int, ms: Long, status: String) =
        calls.synchronized(calls += GraphCall(stage, q.templateId, window, rows, ms, status))
      if !GraphTemplates.isAllowlisted(q) then
        Left(
          WorkflowError.InvalidRequest(s"$stage: ${q.templateId} is not an allowlisted template")
        )
      else if !allowed(q.templateId) then
        Left(
          WorkflowError
            .InvalidRequest(s"$stage: the plan's operations do not allow ${q.templateId}")
        )
      else if PlanOperations.WindowedTemplates(q.templateId) && !window.exists(windows) then
        Left(
          WorkflowError
            .InvalidRequest(s"$stage: ${q.templateId} needs exactly one configured window")
        )
      else if executed.size >= maxQueries then
        Left(WorkflowError.BudgetExceeded(stage, s"more than $maxQueries graph queries"))
      else
        val left = deadlineMs - (clock() - started)
        if left <= 0 then Left(WorkflowError.Timeout(stage, deadlineMs))
        else
          val limit = math.min(perQueryTimeoutMs, left)
          val t0 = clock()
          GraphRunner.within(limit)(graph.run(q)) match
            case None =>
              record(0, clock() - t0, "timeout")
              Left(WorkflowError.Timeout(stage, limit))
            case Some(Left(e)) =>
              record(0, clock() - t0, "failed")
              Left(WorkflowError.Downstream(stage, s"${q.templateId}: ${e.take(200)}"))
            case Some(Right(rows)) if rows.size > maxRows =>
              record(rows.size, clock() - t0, "too-many-rows")
              Left(
                WorkflowError.BudgetExceeded(stage, s"${q.templateId} returned ${rows.size} rows")
              )
            case Some(Right(rows)) =>
              record(rows.size, clock() - t0, "ok")
              Right(rows)
    }

object GraphRunner:
  val Version = "graph-runner-v1"

  private val pool = Executors.newCachedThreadPool(new ThreadFactory:
    def newThread(r: Runnable): Thread =
      val t = new Thread(r, "rag-graph-query")
      t.setDaemon(true)
      t)

  /** The result of `f`, or None when it does not finish within `ms` (the call is cancelled). */
  def within[A](ms: Long)(f: => Either[String, A]): Option[Either[String, A]] =
    val fut = pool.submit(new Callable[Either[String, A]]:
      def call(): Either[String, A] =
        try f
        catch case e: Exception => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
    try Some(fut.get(ms, TimeUnit.MILLISECONDS))
    catch
      case _: TimeoutException =>
        fut.cancel(true)
        None

  /** A runner for one plan: its allowed templates, the configured windows and the config limits. */
  def forPlan(
      graph: GraphStore,
      plan: QueryPlan,
      cfg: ScholarChainConfig,
      clock: () => Long = () => System.currentTimeMillis()
  ): GraphRunner =
    new GraphRunner(
      graph,
      PlanOperations.templatesFor(plan.operations),
      cfg.graph.windows.map(_.label).toSet,
      maxQueries = MaxQueries,
      maxRows = math.min(cfg.retrieval.graphLimit, GraphTemplates.MaxLimit),
      perQueryTimeoutMs = cfg.neptune.queryTimeoutMs.toLong,
      deadlineMs = cfg.operations.synchronousDeadlineMs.toLong,
      clock
    )

  /** Graph queries one request may run (resolver context, paths, cross-community edges, filters).
    */
  val MaxQueries = 250

/** Chooses the precomputed window for a requested one. Spark computed metrics and edges only for
  * the configured windows, so a request for another window is answered from the smallest configured
  * window that contains it, and the note says so; nothing is recomputed for the requested years.
  */
object WindowChoice:
  def choose(requested: TimeWindow, configured: Vector[TimeWindow]): (TimeWindow, Option[String]) =
    configured.find(_ == requested) match
      case Some(w) => w -> None
      case None    =>
        val containing = configured
          .filter(w => w.startYear <= requested.startYear && w.endYear >= requested.endYear)
          .sortBy(w => (w.endYear - w.startYear, w.startYear))
        val chosen = containing.headOption.getOrElse(configured.maxBy(w => w.endYear - w.startYear))
        chosen -> Some(
          s"window ${requested.label} was not precomputed; every graph value is for window ${chosen.label}"
        )

// ---------------------------------------------------------------- ResearcherResolverAgent

enum ResolutionStatus derives ReadWriter:
  case RESOLVED, AMBIGUOUS, NOT_FOUND

/** One canonical researcher a name may mean, with what the caller needs to tell candidates apart.
  */
final case class Candidate(
    researcherId: String,
    displayName: String,
    orcid: Option[String],
    mentionCount: Long,
    sourceMentionIds: Vector[String],
    identityConfidence: Double,
    unresolvedLinks: Boolean,
    identityConflict: Boolean,
    matchedBy: String,
    affiliations: Vector[String],
    coauthors: Vector[String]
) derives ReadWriter

final case class Resolution(
    query: String,
    status: ResolutionStatus,
    candidates: Vector[Candidate],
    totalCandidates: Int,
    reason: String,
    contextWindow: String
) derives ReadWriter:
  def researcherId: Option[String] =
    Option.when(status == ResolutionStatus.RESOLVED)(candidates.head.researcherId)

/** ResearcherResolverAgent (`resolver-v1`): user-typed names to canonical researcher IDs from the
  * entity-resolution output, abstaining on ambiguity.
  *
  * Rules: a name is looked up by its normalized form, then by its name tokens in any order (never
  * by vector similarity). No candidate: NOT_FOUND. Two or more: AMBIGUOUS, always, whatever their
  * confidences; the caller disambiguates by passing one candidate's ID in `researcherIds`. One
  * candidate that er-v1 marked as an identity conflict: AMBIGUOUS. Otherwise RESOLVED, with its
  * identity confidence and unresolved-link flag kept for the answer to state. Each candidate
  * carries its source identifiers (ORCID, mention IDs), affiliations and strongest coauthors in the
  * window.
  */
final class ResearcherResolverAgent(ctx: RuntimeContext, directory: ResearcherDirectory):
  import ResearcherResolverAgent.*

  def resolve(
      names: Vector[String],
      explicitIds: Vector[String],
      window: TimeWindow,
      graph: GraphRunner
  ): Either[WorkflowError, Vector[Resolution]] =
    val badIds = explicitIds.filterNot(Ids.isResearcherId)
    if badIds.nonEmpty then
      Left(
        WorkflowError.InvalidRequest(
          s"not researcher IDs: ${badIds.map(_.take(40)).mkString(", ")}"
        )
      )
    else
      for
        explicit <- seq(
          explicitIds.distinct.take(QueryPlanRules.MaxNames).map(id => lookup(directory.byId(id)))
        )
        known = explicit.flatten
        named <- seq(names.map(n => one(n, known, window, graph)))
        used = named.flatMap(_.researcherId).toSet
        extra <- seq(
          explicitIds.distinct.take(QueryPlanRules.MaxNames).filterNot(used).map { id =>
            known.find(_.researcherId == id) match
              case Some(e) =>
                enrich(e, "explicit-id", window, graph).map(c =>
                  Resolution(
                    id,
                    ResolutionStatus.RESOLVED,
                    Vector(c),
                    1,
                    "named by researcher ID",
                    window.label
                  )
                )
              case None =>
                Right(
                  Resolution(
                    id,
                    ResolutionStatus.NOT_FOUND,
                    Vector.empty,
                    0,
                    "no researcher has this ID",
                    window.label
                  )
                )
          }
        )
      yield named ++ extra

  private def one(
      name: String,
      explicit: Vector[ResearcherEntry],
      window: TimeWindow,
      graph: GraphRunner
  ): Either[WorkflowError, Resolution] =
    for
      exact <- lookup(directory.byExactName(name, MaxCandidates))
      looked <-
        if exact._2 > 0 then Right((exact._1, exact._2, "exact-name"))
        else
          lookup(directory.byNameTokens(name, MaxCandidates)).map(t => (t._1, t._2, "name-tokens"))
      (found, total, how) = looked
      chosen = explicit.find(e =>
        NameKeys.exact(e.displayName) == NameKeys.exact(name) ||
          NameKeys.tokens(e.displayName) == NameKeys.tokens(name)
      )
      r <- chosen match
        case Some(e) =>
          enrich(e, "explicit-id", window, graph).map(c =>
            Resolution(
              name,
              ResolutionStatus.RESOLVED,
              Vector(c),
              total,
              s"the caller chose ${e.researcherId} among $total candidates",
              window.label
            )
          )
        case None if total == 0 =>
          Right(
            Resolution(
              name,
              ResolutionStatus.NOT_FOUND,
              Vector.empty,
              0,
              "no canonical researcher has this name",
              window.label
            )
          )
        case None =>
          val ordered = found.sortBy(e => (-e.identityConfidence, -e.mentionCount, e.researcherId))
          seq(ordered.map(enrich(_, how, window, graph))).map { cs =>
            if total > 1 then
              Resolution(
                name,
                ResolutionStatus.AMBIGUOUS,
                cs,
                total,
                s"$total canonical researchers share this name",
                window.label
              )
            else if cs.head.identityConflict then
              Resolution(
                name,
                ResolutionStatus.AMBIGUOUS,
                cs,
                1,
                s"${ctx.erPolicy} recorded an identity conflict for the only candidate",
                window.label
              )
            else
              Resolution(
                name,
                ResolutionStatus.RESOLVED,
                cs,
                1,
                s"one canonical researcher ($how match)",
                window.label
              )
          }
    yield r

  private def enrich(
      e: ResearcherEntry,
      how: String,
      window: TimeWindow,
      graph: GraphRunner
  ): Either[WorkflowError, Candidate] =
    for
      aff <- graph.run(
        "resolve",
        ctx.templates
          .neighbors(e.researcherId, Set(GraphLabels.AffiliatedWith), "out", ContextItems)
      )
      co <- graph.run(
        "resolve",
        ctx.templates.directCoauthors(e.researcherId, window.label, ContextItems)
      )
    yield
      val affiliations = aff.flatMap(_.value.get("nodeId").flatMap(_.strOpt)).map { id =>
        directory.entity(id).toOption.flatten.fold(id)(n => s"${n.name} ($id)")
      }
      val coauthors = co.flatMap { r =>
        for id <- r.value.get("researcherId").flatMap(_.strOpt)
        yield
          val name = r.value.get("displayName").flatMap(_.strOpt).getOrElse(id)
          val n = r.value
            .get("jointPublicationCount")
            .flatMap(_.numOpt)
            .fold("?")(d => MetricValues.plain(d))
          s"$name ($id, $n joint publications)"
      }
      Candidate(
        e.researcherId,
        e.displayName,
        e.orcid,
        e.mentionCount,
        e.mentionIds.sorted.take(3),
        e.identityConfidence,
        e.unresolved,
        e.identityConflict,
        how,
        affiliations,
        coauthors
      )

  /** One IDENTITY_RESOLUTION evidence item per candidate, so an answer can cite why it abstained.
    */
  def identityEvidence(r: Resolution): Vector[EvidenceItem] =
    r.candidates.map { c =>
      ctx.evidence(
        EvidenceType.IDENTITY_RESOLUTION,
        s"identity|${r.query}|${c.researcherId}|${r.contextWindow}",
        s"neptune://node/${c.researcherId}",
        Vector(c.researcherId),
        Map(
          "query" -> r.query,
          "status" -> r.status.toString,
          "reason" -> r.reason,
          "matchedBy" -> c.matchedBy,
          "totalCandidates" -> r.totalCandidates.toString,
          "displayName" -> c.displayName,
          "orcid" -> c.orcid.getOrElse(""),
          "mentionCount" -> c.mentionCount.toString,
          "sourceMentionIds" -> c.sourceMentionIds.mkString(";"),
          "identityConfidence" -> MetricValues.plain(c.identityConfidence),
          "unresolvedLinks" -> c.unresolvedLinks.toString,
          "identityConflict" -> c.identityConflict.toString,
          "affiliations" -> c.affiliations.mkString("; "),
          "coauthorContext" -> c.coauthors.mkString("; "),
          "contextWindow" -> r.contextWindow,
          "erPolicyVersion" -> ctx.erPolicy,
          "erRunId" -> ctx.erRunId
        ),
        Version
      )
    }

  private def lookup[A](r: Either[String, A]): Either[WorkflowError, A] =
    r.left.map(e => WorkflowError.Downstream("resolve", e.take(200)))

object ResearcherResolverAgent:
  val Version = "resolver-v1"
  val MaxCandidates = 5
  val ContextItems = 3

private[rag] def seq[A](xs: Vector[Either[WorkflowError, A]]): Either[WorkflowError, Vector[A]] =
  xs.foldLeft[Either[WorkflowError, Vector[A]]](Right(Vector.empty))((acc, x) =>
    acc.flatMap(v => x.map(v :+ _))
  )

// ---------------------------------------------------------------- CollaborationMetricsAgent

/** One stored metrics row, the researcher's display name and the evidence that cites it. */
final case class MetricRecord(
    metrics: ResearcherMetrics,
    displayName: String,
    evidence: EvidenceItem
)

/** CollaborationMetricsAgent (`metrics-agent-v1`): exact Spark values from the metric store.
  *
  * A researcher's row is a point read of storage's ResearcherMetricCache (key: researcher, metric
  * version, window). A ranking takes the order from the [[MetricRanking]] port and then reads every
  * ranked row from the cache; if the cache value differs from the ranked value, the ranking is
  * refused. Nothing is recomputed or rounded: numbers keep their stored value as canonical decimal
  * text. Optional measures that Spark left empty are listed in `missingFields`, never filled.
  */
final class CollaborationMetricsAgent(
    ctx: RuntimeContext,
    kv: KeyValueStore,
    directory: ResearcherDirectory,
    ranking: MetricRanking
):
  import CollaborationMetricsAgent.*
  private val table = ctx.cfg.dynamo.researcherMetricTable
  private val cache = new ResearcherMetricCache(kv, table)

  def name(id: String): String = directory.byId(id).toOption.flatten.fold(id)(_.displayName)

  def evidenceOf(m: ResearcherMetrics, displayName: String): EvidenceItem =
    val optional = Vector(
      "communityId" -> m.communityId,
      "bridgeScore" -> m.bridgeScore.map(MetricValues.plain),
      "participationCoefficient" -> m.participationCoefficient.map(MetricValues.plain),
      "crossCommunityDegree" -> m.crossCommunityDegree.map(_.toString),
      "communitiesConnected" -> m.communitiesConnected.map(_.toString),
      "coreness" -> m.coreness.map(_.toString)
    )
    val key = s"metrics|${m.researcherId}|${m.metricVersion}|${m.window.label}"
    ctx.evidence(
      EvidenceType.METRIC_RECORD,
      key,
      s"dynamodb://$table/researcher#${m.researcherId}/metrics#${m.metricVersion}#${m.window.label}",
      Vector(m.researcherId),
      Map(
        "kind" -> "researcher-metrics",
        "displayName" -> displayName,
        "window" -> m.window.label,
        "publicationCount" -> m.publicationCount.toString,
        "distinctCoauthorCount" -> m.distinctCoauthorCount.toString,
        "totalCoauthorOccurrences" -> m.totalCoauthorOccurrences.toString,
        "averageCoauthorsPerPaper" -> MetricValues.plain(m.averageCoauthorsPerPaper),
        "maximumCoauthorsOnOnePaper" -> m.maximumCoauthorsOnOnePaper.toString,
        "maximumTeamWorkIds" -> m.maximumTeamWorkIds.mkString(";"),
        "weightedDegree" -> MetricValues.plain(m.weightedDegree),
        "componentId" -> m.componentId,
        "componentSize" -> m.componentSize.toString,
        "bridgeMetric" -> MetricValues.meaning(CollaborationMetric.BRIDGE_SCORE),
        "metricVersion" -> m.metricVersion,
        "metricRunId" -> m.provenance.runId,
        "missingFields" -> optional.collect { case (k, None) => k }.mkString(";")
      ) ++ optional.collect { case (k, Some(v)) => k -> v },
      Version
    )

  def forResearcher(id: String, window: TimeWindow): Either[WorkflowError, Option[MetricRecord]] =
    cache
      .get(id, ctx.metricVersion, window)
      .left
      .map(e => WorkflowError.Downstream("metrics", e.toString.take(200)))
      .flatMap {
        case Some(m) if m.provenance.runId != ctx.graphRunId =>
          Left(
            WorkflowError
              .Downstream("metrics", s"metrics of $id belong to run ${m.provenance.runId}")
          )
        case other =>
          val n = name(id)
          Right(other.map(m => MetricRecord(m, n, evidenceOf(m, n))))
      }

  /** Every configured window's row of one researcher (temporal comparison), oldest window first. */
  def temporal(id: String): Either[WorkflowError, Vector[MetricRecord]] =
    seq(ctx.cfg.graph.windows.sortBy(w => (w.startYear, w.endYear)).map(forResearcher(id, _)))
      .map(_.flatten)

  /** The top `limit` researchers by one stored metric, and a ranking evidence item naming the
    * order.
    */
  def ranked(
      window: TimeWindow,
      metric: CollaborationMetric,
      limit: Int,
      among: Option[(String, Set[String])]
  ): Either[WorkflowError, (Vector[MetricRecord], EvidenceItem)] =
    for
      order <- ranking
        .top(window, ctx.metricVersion, metric, limit, among.map(_._2))
        .left
        .map(e => WorkflowError.Downstream("ranking", e.take(200)))
      recs <- seq(order.map { r =>
        forResearcher(r.researcherId, window).flatMap {
          case Some(rec) if MetricValues.of(rec.metrics, metric).contains(r.value) => Right(rec)
          case Some(_)                                                             =>
            Left(
              WorkflowError.Downstream(
                "ranking",
                s"ranking and metric store disagree on ${r.researcherId}"
              )
            )
          case None =>
            Left(
              WorkflowError.Downstream("ranking", s"no metrics row for ranked ${r.researcherId}")
            )
        }
      })
    yield
      val filter = among.fold("none: every researcher with a row in the window")(_._1)
      val ev = ctx.evidence(
        EvidenceType.METRIC_RECORD,
        s"ranking|${window.label}|${ctx.metricVersion}|$metric|$limit|${Hashing.short(filter + among.fold("")(_._2.toVector.sorted.mkString("|")), 16)}",
        s"dynamodb://$table/ranking/${window.label}/${MetricValues.field(metric)}",
        order.map(_.researcherId),
        Map(
          "kind" -> "ranking",
          "metric" -> metric.toString,
          "metricField" -> MetricValues.field(metric),
          "meaning" -> MetricValues.meaning(metric),
          "window" -> window.label,
          "metricVersion" -> ctx.metricVersion,
          "order" -> OrderRule,
          "filter" -> filter,
          "limit" -> limit.toString,
          "returned" -> order.size.toString,
          "positions" -> order.zipWithIndex
            .map((r, i) => s"${i + 1}:${r.researcherId}=${MetricValues.plain(r.value)}")
            .mkString(";")
        ),
        Version
      )
      (recs, ev)

  /** Publication support for work IDs (titles and years from the Work nodes), at most 12. */
  def publications(
      workIds: Vector[String],
      graph: GraphRunner
  ): Either[WorkflowError, Vector[EvidenceItem]] =
    seq(workIds.distinct.take(MaxPublications).map { w =>
      graph
        .run("publications", ctx.templates.node(w))
        .map(_.headOption.map { row =>
          val props =
            row.value.get("properties").flatMap(_.objOpt).map(_.toMap).getOrElse(Map.empty)
          def p(k: String) = props.get(k).flatMap(_.strOpt).getOrElse("")
          ctx.evidence(
            EvidenceType.PUBLICATION,
            s"work|$w",
            s"neptune://node/$w",
            Vector.empty,
            Map(
              "workId" -> w,
              "title" -> p("title"),
              "year" -> p("year"),
              "doi" -> p("doi"),
              "recordType" -> p("recordType")
            ),
            Version,
            work = Some(w)
          )
        })
    }).map(_.flatten)

object CollaborationMetricsAgent:
  val Version = "metrics-agent-v1"
  val MaxPublications = 12
  val OrderRule =
    "stored value descending, then researcher ID ascending; rows without the value are left out"

// ---------------------------------------------------------------- PathFinderAgent

/** PathFinderAgent (`path-agent-v1`): bounded parameterized templates over one window only.
  *
  * Direct collaborators use `direct-coauthors-v1`, a pair check `coauthor-pair-v1`, a shortest path
  * `shortest-path-d<depth>-v1` followed by `coauthor-pair-v1` for every hop, so each hop carries
  * its edge weight (joint publications), years, supporting work IDs and evidence pointer. A missing
  * edge or path is evidence too (`found=false`), bounded by the depth and window it was searched
  * in.
  */
final class PathFinderAgent(ctx: RuntimeContext, directory: ResearcherDirectory):
  import PathFinderAgent.*

  private def name(id: String) = directory.byId(id).toOption.flatten.fold(id)(_.displayName)

  private def edgeEvidence(
      a: String,
      b: String,
      w: TimeWindow,
      r: ujson.Obj
  ): Either[WorkflowError, EvidenceItem] =
    val v = r.value
    (for
      edgeId <- v.get("edgeId").flatMap(_.strOpt)
      weight <- v.get("jointPublicationCount").flatMap(_.numOpt)
      first <- v.get("firstYear").flatMap(_.numOpt)
      last <- v.get("lastYear").flatMap(_.numOpt)
    yield
      val (x, y) = Ids.orderedPair(a, b)
      val works =
        v.get("workIds").flatMap(_.strOpt).toVector.flatMap(_.split('|')).filter(_.nonEmpty)
      val pointer = v.get("evidencePointer").flatMap(_.strOpt).getOrElse("")
      ctx.evidence(
        EvidenceType.COAUTHOR_EDGE,
        s"edge|$edgeId",
        if pointer.nonEmpty then pointer else s"neptune://edge/$edgeId",
        Vector(x, y),
        Map(
          "found" -> "true",
          "researcherA" -> x,
          "researcherB" -> y,
          "nameA" -> name(x),
          "nameB" -> name(y),
          "edgeId" -> edgeId,
          "coauthorEdgeId" -> pointer.split('#').drop(1).headOption.getOrElse(""),
          "jointPublicationCount" -> MetricValues.plain(weight),
          "firstCollaborationYear" -> first.toInt.toString,
          "lastCollaborationYear" -> last.toInt.toString,
          "window" -> w.label,
          "supportingWorkIds" -> works.mkString(";"),
          "evidencePointer" -> pointer
        ),
        Version
      )
    ).toRight(WorkflowError.Downstream("paths", "a coauthor row lacks edgeId, weight or years"))

  def directCoauthors(
      id: String,
      w: TimeWindow,
      limit: Int,
      graph: GraphRunner
  ): Either[WorkflowError, Vector[EvidenceItem]] =
    graph
      .run("coauthors", ctx.templates.directCoauthors(id, w.label, limit))
      .flatMap(rows =>
        seq(rows.map { r =>
          r.value.get("researcherId").flatMap(_.strOpt) match
            case Some(o) if o != id => edgeEvidence(id, o, w, r)
            case _                  =>
              Left(WorkflowError.Downstream("coauthors", "a coauthor row lacks researcherId"))
        })
      )

  /** The window's coauthor edge of a and b, or a `found=false` item when there is none. */
  def pair(
      a: String,
      b: String,
      w: TimeWindow,
      graph: GraphRunner
  ): Either[WorkflowError, EvidenceItem] =
    graph.run("coauthors", ctx.templates.coauthorPair(a, b, w.label)).flatMap {
      case Vector(r) => edgeEvidence(a, b, w, r)
      case Vector()  =>
        val (x, y) = Ids.orderedPair(a, b)
        Right(
          ctx.evidence(
            EvidenceType.COAUTHOR_EDGE,
            s"no-edge|$x|$y|${w.label}",
            s"neptune://template/coauthor-pair-v1",
            Vector(x, y),
            Map(
              "found" -> "false",
              "researcherA" -> x,
              "researcherB" -> y,
              "nameA" -> name(x),
              "nameB" -> name(y),
              "window" -> w.label
            ),
            Version
          )
        )
      case rows =>
        Left(WorkflowError.Downstream("coauthors", s"coauthor-pair-v1 returned ${rows.size} rows"))
    }

  /** A GRAPH_PATH item and one COAUTHOR_EDGE item per hop, or one `found=false` path item. */
  def shortestPath(
      a: String,
      b: String,
      w: TimeWindow,
      depth: Int,
      graph: GraphRunner
  ): Either[WorkflowError, Vector[EvidenceItem]] =
    val tpl = s"shortest-path-d$depth-v1"
    graph.run("paths", ctx.templates.shortestPath(a, b, w.label, depth)).flatMap {
      case Vector() =>
        Right(
          Vector(
            ctx.evidence(
              EvidenceType.GRAPH_PATH,
              s"no-path|$a|$b|${w.label}|$depth",
              s"neptune://template/$tpl",
              Vector(a, b),
              Map(
                "found" -> "false",
                "from" -> a,
                "to" -> b,
                "fromName" -> name(a),
                "toName" -> name(b),
                "window" -> w.label,
                "maxDepth" -> depth.toString,
                "meaning" -> s"no coauthorship path of at most $depth hops in window ${w.label}"
              ),
              Version
            )
          )
        )
      case Vector(r) =>
        val ids = r.value.get("researchers").flatMap(_.arrOpt).toVector.flatten.flatMap(_.strOpt)
        val edges = r.value.get("edges").flatMap(_.arrOpt).toVector.flatten.flatMap(_.strOpt)
        if ids.size < 2 || edges.size != ids.size - 1 || ids.head != a || ids.last != b then
          Left(WorkflowError.Downstream("paths", s"$tpl returned a malformed path"))
        else
          seq(ids.zip(ids.drop(1)).zip(edges).map { case ((x, y), e) =>
            pair(x, y, w, graph).flatMap(h =>
              Either.cond(
                h.values.get("edgeId").contains(e),
                h,
                WorkflowError
                  .Downstream("paths", s"hop $x-$y: the path edge $e is not the window's edge")
              )
            )
          }).map { hops =>
            val path = ctx.evidence(
              EvidenceType.GRAPH_PATH,
              s"path|$a|$b|${w.label}|$depth",
              s"neptune://template/$tpl",
              ids,
              Map(
                "found" -> "true",
                "from" -> a,
                "to" -> b,
                "fromName" -> name(a),
                "toName" -> name(b),
                "hops" -> edges.size.toString,
                "window" -> w.label,
                "maxDepth" -> depth.toString,
                "edgeIds" -> edges.mkString(";"),
                "researcherNames" -> ids.map(name).mkString(" -> "),
                "hopEvidenceIds" -> hops.map(_.evidenceId).mkString(";"),
                "meaning" -> "one shortest path; other paths of the same length may exist"
              ),
              Version,
              path = Some(ids)
            )
            path +: hops
          }
      case rows => Left(WorkflowError.Downstream("paths", s"$tpl returned ${rows.size} rows"))
    }

object PathFinderAgent:
  val Version = "path-agent-v1"

// ---------------------------------------------------------------- CommunityAnalystAgent

final case class CommunityView(
    researcherId: String,
    communityId: Option[String],
    evidence: Vector[EvidenceItem],
    notes: Vector[String]
)

/** CommunityAnalystAgent (`community-agent-v1`).
  *
  * For one researcher's metrics row it returns a COMMUNITY_RECORD that keeps the two kinds of
  * measure apart: `degree.*` (distinct coauthors, weighted degree: direct collaboration) and
  * `bridge.*` (sampled betweenness, participation coefficient, cross-community degree, communities
  * connected: structural position between communities). It then reads the researcher's strongest
  * coauthor edges in the window and each coauthor's community from the metric store; every edge to
  * another community becomes a cross-community link item. Neighbouring communities are those seen
  * on the retrieved edges only. Community size is not in any store the runtime reads (the Spark
  * community table is not exported as JSON lines or loaded), so it is listed as missing.
  */
final class CommunityAnalystAgent(
    ctx: RuntimeContext,
    metrics: CollaborationMetricsAgent,
    paths: PathFinderAgent
):
  import CommunityAnalystAgent.*

  def analyse(
      rec: MetricRecord,
      edgeLimit: Int,
      graph: GraphRunner
  ): Either[WorkflowError, CommunityView] =
    val m = rec.metrics
    for
      edges <- paths.directCoauthors(m.researcherId, m.window, edgeLimit, graph)
      others <- seq(edges.map { e =>
        val o =
          Vector(e.values("researcherA"), e.values("researcherB")).find(_ != m.researcherId).get
        metrics.forResearcher(o, m.window).map(r => (e, o, r.flatMap(_.metrics.communityId)))
      })
    yield
      val cross = others.collect {
        case (e, o, Some(oc)) if m.communityId.exists(_ != oc) => (e, o, oc)
      }
      val links = cross.map { (e, o, oc) =>
        ctx.evidence(
          EvidenceType.COMMUNITY_RECORD,
          s"cross|${m.researcherId}|${e.values("edgeId")}",
          e.sourceUri,
          Vector(m.researcherId, o),
          Map(
            "kind" -> "cross-community-link",
            "researcherId" -> m.researcherId,
            "coauthorId" -> o,
            "coauthorName" -> metrics.name(o),
            "ownCommunityId" -> m.communityId.getOrElse(""),
            "otherCommunityId" -> oc,
            "edgeId" -> e.values("edgeId"),
            "jointPublicationCount" -> e.values("jointPublicationCount"),
            "window" -> m.window.label,
            "edgeEvidenceId" -> e.evidenceId
          ),
          Version
        )
      }
      val neighbours = cross.map(_._3).distinct.sorted
      val record = ctx.evidence(
        EvidenceType.COMMUNITY_RECORD,
        s"community|${m.researcherId}|${m.metricVersion}|${m.window.label}",
        rec.evidence.sourceUri,
        Vector(m.researcherId),
        Map(
          "kind" -> "community-membership",
          "researcherId" -> m.researcherId,
          "displayName" -> rec.displayName,
          "communityId" -> m.communityId.getOrElse(""),
          "communityMethod" -> "weighted label propagation (graph-v1)",
          "window" -> m.window.label,
          "componentId" -> m.componentId,
          "componentSize" -> m.componentSize.toString,
          "degree.distinctCoauthorCount" -> m.distinctCoauthorCount.toString,
          "degree.weightedDegree" -> MetricValues.plain(m.weightedDegree),
          "bridge.metric" -> MetricValues.meaning(CollaborationMetric.BRIDGE_SCORE),
          "measureKinds" -> MeasureKinds,
          "neighbouringCommunitiesOnRetrievedEdges" -> neighbours.mkString(";"),
          "retrievedCoauthorEdges" -> edges.size.toString,
          "edgeLimit" -> edgeLimit.toString,
          "missingFields" -> ("communitySize" +: Vector(
            "communityId" -> m.communityId,
            "bridge.bridgeScore" -> m.bridgeScore,
            "bridge.participationCoefficient" -> m.participationCoefficient,
            "bridge.crossCommunityDegree" -> m.crossCommunityDegree,
            "bridge.communitiesConnected" -> m.communitiesConnected
          ).collect { case (k, None) => k }).mkString(";"),
          "metricsEvidenceId" -> rec.evidence.evidenceId
        ) ++ Vector(
          m.bridgeScore.map(d => "bridge.bridgeScore" -> MetricValues.plain(d)),
          m.participationCoefficient.map(d =>
            "bridge.participationCoefficient" -> MetricValues.plain(d)
          ),
          m.crossCommunityDegree.map(d => "bridge.crossCommunityDegree" -> d.toString),
          m.communitiesConnected.map(d => "bridge.communitiesConnected" -> d.toString)
        ).flatten,
        Version
      )
      CommunityView(
        m.researcherId,
        m.communityId,
        (record +: edges.filter(e => cross.exists(_._1 == e))) ++ links,
        Vector(
          s"community size of ${m.communityId.getOrElse("(none)")} is not available: the Spark community table is not in any store the runtime reads"
        ) ++ Option.when(m.communityId.isEmpty)(
          s"${m.researcherId} has no community in ${m.window.label}"
        )
      )

object CommunityAnalystAgent:
  val Version = "community-agent-v1"
  val MeasureKinds =
    "degree.* count direct collaboration (breadth and repetition); bridge.* measure structural position between communities; a high degree does not imply a high bridge score"

// ---------------------------------------------------------------- SemanticContextAgent

/** SemanticContextAgent (`semantic-agent-v1`): descriptive context from the S3 Vectors indexes the
  * plan's operations allow, through storage's text pointers (rag's [[SemanticContextRetriever]]).
  * Every item keeps its similarity score and source pointer, stays `untrusted-data`, and goes to
  * the bundle's semantic partition; it never supports a count, edge, path, component or bridge
  * value ([[GraphValuePolicy]]).
  */
final class SemanticContextAgent(retriever: SemanticContextRetriever):
  def context(
      plan: QueryPlan,
      window: TimeWindow,
      researcherIds: Vector[String],
      communityIds: Vector[String],
      topicIds: Vector[String]
  ): Either[WorkflowError, SemanticRetrieval] =
    val allowed = PlanOperations.docTypesFor(plan.operations)
    val wf = ContextFilter(window = Some(window))
    val profile = ResearcherProfileTemplate.docType
    val targets = (
      researcherIds.take(4).map(r => ContextTarget(profile, 1, wf.copy(researcherId = Some(r)))) ++
        Option.when(researcherIds.isEmpty)(ContextTarget(profile, 3, wf)).toVector ++
        communityIds
          .take(2)
          .map(c =>
            ContextTarget(CommunityProfileTemplate.docType, 1, wf.copy(entityId = Some(c)))
          ) ++
        Option
          .when(plan.operations.contains(QueryOperation.SHORTEST_PATH))(
            ContextTarget(
              PathSummaryTemplate.docType,
              2,
              wf.copy(researcherId = researcherIds.headOption)
            )
          )
          .toVector ++
        topicIds
          .take(2)
          .map(t => ContextTarget(TopicSummaryTemplate.docType, 1, wf.copy(entityId = Some(t)))) ++
        Vector(ContextTarget(PaperAbstractTemplate.docType, 2))
    ).filter(t => allowed(t.docType)).take(SemanticContextRetriever.MaxTargets)
    if targets.isEmpty then
      Right(
        SemanticRetrieval(
          SemanticContext(SemanticContext.Version, SemanticContext.Trust, "", Vector.empty),
          Vector.empty
        )
      )
    else
      retriever
        .retrieve(ContextQuery(plan.question.take(SemanticContextRetriever.MaxQueryChars), targets))
        .left
        .map(e => WorkflowError.Downstream("semantic", e.take(200)))

object SemanticContextAgent:
  val Version = "semantic-agent-v1"
