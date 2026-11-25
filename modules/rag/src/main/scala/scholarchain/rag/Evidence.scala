package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import upickle.default.ReadWriter

/** Bounds of one evidence bundle. `maxBytes` keeps the encoded bundle well under the 400 KB
  * DynamoDB item limit of the EvidenceBundle table (storage refuses a larger bundle).
  */
final case class EvidenceLimits(
    maxDeterministic: Int = 120,
    maxIdentity: Int = 25,
    maxSemantic: Int = 10,
    maxBytes: Int = 300000
)

/** EvidenceBuilderAgent (`evidence-builder-v1`): one bounded [[EvidenceBundle]] per query.
  *
  * The three partitions stay apart: `deterministic` holds only graph and metric evidence
  * (METRIC_RECORD, COAUTHOR_EDGE, GRAPH_PATH, COMPONENT_RECORD, COMMUNITY_RECORD) and PUBLICATION
  * records, `identity` only IDENTITY_RESOLUTION, `semantic` only descriptive context
  * (SEMANTIC_PROFILE, TEXT_CHUNK). An item in the wrong partition fails the build. Items repeat by
  * ID only when equal (an unequal repeat is dropped and noted); every item and the bundle pass core
  * validation; the count and byte bounds drop semantic items first, then the tail of the
  * deterministic items, and every drop is noted. The bundle ID is a hash of the query ID and the
  * evidence IDs.
  */
final class EvidenceBuilderAgent(ctx: RuntimeContext, limits: EvidenceLimits = EvidenceLimits()):
  import EvidenceBuilderAgent.*

  def build(
      queryId: String,
      deterministic: Vector[EvidenceItem],
      identity: Vector[EvidenceItem],
      semantic: Vector[EvidenceItem]
  ): Either[WorkflowError, (EvidenceBundle, Vector[String])] =
    val notes = Vector.newBuilder[String]
    def part(xs: Vector[EvidenceItem], ok: EvidenceType => Boolean, what: String) =
      xs.find(e => !ok(e.evidenceType)) match
        case Some(e) =>
          Left(WorkflowError.InvalidRequest(s"${e.evidenceType} is not $what evidence"))
        case None => Right(xs)
    def clean(xs: Vector[EvidenceItem]): Vector[EvidenceItem] =
      val seen = scala.collection.mutable.LinkedHashMap.empty[String, EvidenceItem]
      xs.foreach { e =>
        val errs = Validate.evidence(e)
        if errs.nonEmpty then notes += s"dropped invalid ${e.evidenceId}: ${errs.head.take(120)}"
        else
          seen.get(e.evidenceId) match
            case None                  => seen(e.evidenceId) = e
            case Some(old) if old == e => ()
            case Some(_)               => notes += s"dropped a conflicting copy of ${e.evidenceId}"
      }
      seen.values.toVector
    def cap(xs: Vector[EvidenceItem], n: Int, what: String) =
      if xs.size > n then notes += s"$what evidence bounded to $n of ${xs.size} items"
      xs.take(n)
    for
      d0 <- part(
        deterministic,
        t => GraphValuePolicy.Authoritative(t) || t == EvidenceType.PUBLICATION,
        "deterministic"
      )
      i0 <- part(identity, _ == EvidenceType.IDENTITY_RESOLUTION, "identity")
      s0 <- part(semantic, GraphValuePolicy.Descriptive, "semantic")
      d1 = cap(clean(d0), limits.maxDeterministic, "deterministic")
      i1 = cap(
        clean(i0).filterNot(e => d1.exists(_.evidenceId == e.evidenceId)),
        limits.maxIdentity,
        "identity"
      )
      s1 = cap(
        clean(s0).filterNot(e => (d1 ++ i1).exists(_.evidenceId == e.evidenceId)),
        limits.maxSemantic,
        "semantic"
      )
      fitted = fit(queryId, d1, i1, s1, notes)
      errs = Validate.bundle(fitted)
      _ <- Either.cond(
        errs.isEmpty,
        (),
        WorkflowError.InvalidRequest(s"bundle invalid: ${errs.mkString("; ").take(300)}")
      )
    yield (fitted, notes.result())

  private def bundle(
      queryId: String,
      d: Vector[EvidenceItem],
      i: Vector[EvidenceItem],
      s: Vector[EvidenceItem]
  ) =
    val ids = (d ++ i ++ s).map(_.evidenceId)
    EvidenceBundle(
      SchemaVersions.Bundle,
      "bundle-" + Hashing.short(s"$queryId|${ids.mkString("|")}", 24),
      queryId,
      d,
      s,
      i,
      ctx.graphRunId,
      ctx.metricVersion,
      Provenance(
        SourceType.SCHOLARCHAIN,
        queryId,
        ctx.snapshot,
        ctx.graphRunId,
        SchemaVersions.Bundle,
        Some(Version)
      )
    )

  private def fit(
      queryId: String,
      d: Vector[EvidenceItem],
      i: Vector[EvidenceItem],
      s: Vector[EvidenceItem],
      notes: scala.collection.mutable.Builder[String, Vector[String]]
  ): EvidenceBundle =
    var (dd, ss) = (d, s)
    def size = JsonLines.encode(bundle(queryId, dd, i, ss)).getBytes("UTF-8").length
    while size > limits.maxBytes && (ss.nonEmpty || dd.size > 1) do
      if ss.nonEmpty then ss = ss.dropRight(1) else dd = dd.dropRight(1)
    if ss.size < s.size || dd.size < d.size then
      notes += s"bundle bounded to ${limits.maxBytes} bytes: dropped ${s.size - ss.size} semantic and ${d.size - dd.size} deterministic items"
    bundle(queryId, dd, i, ss)

object EvidenceBuilderAgent:
  val Version = "evidence-builder-v1"

/** Timing of one stage, for the audit. */
final case class StageTiming(stage: String, millis: Long, status: String) derives ReadWriter

/** What stages 1 to 7 hand to synthesis, verification and audit (stages 8 to 10).
  *
  * `abstention` is set when the answer must abstain: a named researcher is ambiguous or unknown, or
  * the plan's structural operations found no deterministic evidence. `named` and `ranked` are the
  * exact metrics rows behind the bundle's metric evidence.
  */
final case class EvidenceStageResult(
    plan: QueryPlan,
    window: TimeWindow,
    windowNote: Option[String],
    resolutions: Vector[Resolution],
    named: Vector[MetricRecord],
    ranked: Vector[MetricRecord],
    bundle: EvidenceBundle,
    notes: Vector[String],
    abstention: Option[String],
    graphCalls: Vector[GraphCall],
    semanticDropped: Vector[DroppedHit],
    stages: Vector[StageTiming]
)

/** Stages 1 to 7 of the runtime workflow: plan, resolve, metrics, paths, communities, semantic
  * context and the evidence bundle (`evidence-pipeline-v1`). Every graph query of a request runs
  * through one [[GraphRunner]] built from the plan, on the one window [[WindowChoice]] picked.
  */
final class EvidencePipeline(
    ctx: RuntimeContext,
    stores: RuntimeStores,
    retriever: SemanticContextRetriever,
    limits: EvidenceLimits = EvidenceLimits(),
    clock: () => Long = () => System.currentTimeMillis()
):
  import EvidencePipeline.*
  private val cfg = ctx.cfg
  val planner = new QueryPlanner(cfg, ctx.graphRunId, ctx.snapshot)
  val resolver = new ResearcherResolverAgent(ctx, stores.directory)
  val metrics = new CollaborationMetricsAgent(ctx, stores.kv, stores.directory, stores.ranking)
  val paths = new PathFinderAgent(ctx, stores.directory)
  val communities = new CommunityAnalystAgent(ctx, metrics, paths)
  val semantic = new SemanticContextAgent(retriever)
  val builder = new EvidenceBuilderAgent(ctx, limits)

  def run(queryId: String, req: AnalysisRequest): Either[WorkflowError, EvidenceStageResult] =
    val stages = scala.collection.mutable.ArrayBuffer.empty[StageTiming]
    for
      plan <- timed(stages, "plan")(planner.plan(queryId, req))
      (window, windowNote) = WindowChoice.choose(plan.window, cfg.graph.windows)
      graph = GraphRunner.forPlan(stores.graph, plan, cfg, clock)
      resolutions <- timed(stages, "resolve")(
        resolver.resolve(plan.researcherNames, req.constraints.researcherIds, window, graph)
      )
      identity = resolutions.flatMap(resolver.identityEvidence)
      unresolved = resolutions.filter(_.status != ResolutionStatus.RESOLVED)
      result <-
        if unresolved.nonEmpty then
          timed(stages, "evidence")(builder.build(queryId, Vector.empty, identity, Vector.empty))
            .map { (b, n) =>
              EvidenceStageResult(
                plan,
                window,
                windowNote,
                resolutions,
                Vector.empty,
                Vector.empty,
                b,
                windowNote.toVector ++ n,
                Some(identityReason(unresolved)),
                graph.executed,
                Vector.empty,
                Vector.empty
              )
            }
        else gather(plan, window, windowNote, resolutions, identity, graph, stages)
    yield result.copy(stages = stages.toVector)

  private def timed[A](stages: scala.collection.mutable.ArrayBuffer[StageTiming], name: String)(
      f: => Either[WorkflowError, A]
  ): Either[WorkflowError, A] =
    val t0 = clock()
    val r = f
    stages += StageTiming(name, clock() - t0, if r.isRight then "ok" else "failed")
    r

  private def gather(
      plan: QueryPlan,
      window: TimeWindow,
      windowNote: Option[String],
      resolutions: Vector[Resolution],
      identity: Vector[EvidenceItem],
      graph: GraphRunner,
      stages: scala.collection.mutable.ArrayBuffer[StageTiming]
  ): Either[WorkflowError, EvidenceStageResult] =
    val ops = plan.operations.toSet
    val ids = resolutions.flatMap(_.researcherId).distinct
    import QueryOperation.*
    for
      named <- timed(stages, "metrics")(
        seq(ids.map(metrics.forResearcher(_, window))).map(_.flatten)
      )
      filter <- timed(stages, "filters")(candidateFilter(plan, window, graph))
      (among, filterNotes, topicIds) = filter
      primary <- timed(stages, "ranking")(
        if ops(RESEARCHER_RANKING) || (ids.isEmpty && (ops(MAX_TEAM_SIZE) || ops(
            COMPONENT_LOOKUP
          ) || ops(TEMPORAL_COMPARISON)))
        then metrics.ranked(window, plan.rankingMetric, plan.limit, among).map(Some(_))
        else Right(None)
      )
      bridge <- timed(stages, "bridges")(
        if !ops(
            BRIDGE_RANKING
          ) || plan.rankingMetric == CollaborationMetric.BRIDGE_SCORE && primary.nonEmpty
        then Right(None)
        else
          primary match
            case Some((recs, _)) =>
              val set = recs.map(_.metrics.researcherId).toSet
              metrics
                .ranked(
                  window,
                  CollaborationMetric.BRIDGE_SCORE,
                  plan.limit,
                  Some(("the primary ranking", set))
                )
                .map(Some(_))
            case None if ids.isEmpty =>
              metrics
                .ranked(window, CollaborationMetric.BRIDGE_SCORE, plan.limit, among)
                .map(Some(_))
            case None => Right(None)
      )
      ranked = (primary.toVector ++ bridge.toVector)
        .flatMap(_._1)
        .distinctBy(_.metrics.researcherId)
      temporal <- timed(stages, "temporal")(
        if !ops(TEMPORAL_COMPARISON) then Right(Vector.empty)
        else
          val who = if ids.nonEmpty then ids.take(4) else ranked.take(3).map(_.metrics.researcherId)
          seq(who.map(metrics.temporal)).map(_.flatten)
      )
      coauthors <- timed(stages, "coauthors")(
        if !ops(DIRECT_COAUTHORS) then Right(Vector.empty)
        else
          val pairCheck =
            if ids.size >= 2 then paths.pair(ids(0), ids(1), window, graph).map(Vector(_))
            else Right(Vector.empty)
          for
            p <- pairCheck
            lists <- seq(ids.take(2).map(paths.directCoauthors(_, window, DirectLimit(cfg), graph)))
          yield p ++ lists.flatten
      )
      pathEv <- timed(stages, "paths")(
        if ops(SHORTEST_PATH) && ids.size >= 2 then
          paths.shortestPath(ids(0), ids(1), window, plan.maxPathDepth, graph)
        else Right(Vector.empty)
      )
      views <- timed(stages, "communities")(
        if !(ops(COMMUNITY_COMPARISON) || ops(BRIDGE_RANKING)) then Right(Vector.empty)
        else
          val who = (named ++ bridge.toVector.flatMap(_._1) ++ primary.toVector.flatMap(_._1))
            .distinctBy(_.metrics.researcherId)
            .take(MaxCommunityViews)
          seq(who.map(communities.analyse(_, CommunityEdgeLimit, graph)))
      )
      // The named pair's edge (always first in `coauthors`): every supporting publication.
      pairWorks = (if ids.size >= 2 then coauthors.take(1) else Vector.empty)
        .filter(_.values.get("found").contains("true"))
        .flatMap(_.values.get("supportingWorkIds").toVector.flatMap(_.split(';')))
        .filter(_.nonEmpty)
      works = pairWorks ++ (if ops(MAX_TEAM_SIZE) then
                              (named ++ ranked).flatMap(_.metrics.maximumTeamWorkIds.take(2))
                            else Vector.empty) ++
        pathEv.flatMap(
          _.values
            .get("supportingWorkIds")
            .toVector
            .flatMap(_.split(';'))
            .filter(_.nonEmpty)
            .take(1)
        )
      pubs <- timed(stages, "publications")(
        if works.isEmpty then Right(Vector.empty)
        else metrics.publications(works, graph)
      )
      sem = timed(stages, "semantic")(
        semantic.context(
          plan,
          window,
          if ids.nonEmpty then ids else ranked.take(4).map(_.metrics.researcherId),
          (named ++ ranked).flatMap(_.metrics.communityId).distinct,
          topicIds
        )
      )
      semItems = sem.toOption.toVector.flatMap(_.context.items.map(_.toEvidence))
      deterministic = primary.toVector.map(_._2) ++ bridge.toVector.map(_._2) ++ named.map(
        _.evidence
      ) ++
        ranked.map(_.evidence) ++ temporal.map(_.evidence) ++ pathEv ++ coauthors.take(1) ++
        pubs ++ coauthors.drop(1) ++ views.flatMap(_.evidence)
      built <- timed(stages, "evidence")(
        builder.build(plan.queryId, deterministic, identity, semItems)
      )
    yield
      val (b, buildNotes) = built
      val missingNamed = ids
        .filterNot(i => named.exists(_.metrics.researcherId == i))
        .map(i =>
          s"$i has no metrics row in window ${window.label} (no publication in that window)"
        )
      val notes =
        windowNote.toVector ++ filterNotes ++ missingNamed ++ views.flatMap(_.notes).distinct ++
          sem.left.toOption
            .map(e => s"semantic context unavailable: ${e.describe}")
            .toVector ++
          Option
            .when(ops(COMPONENT_LOOKUP) && ids.isEmpty)(ComponentNote)
            .toVector ++ buildNotes
      val structural =
        plan.operations.exists(o => !Set(SEMANTIC_EXPLANATION, TOPIC_FILTER, INSTITUTION_FILTER)(o))
      val abstain =
        if b.deterministic.isEmpty && (structural || ids.nonEmpty) then
          Some(
            "no deterministic graph or metric evidence was found for the question in window " + window.label
          )
        else if ids.isEmpty && primary.exists(_._1.isEmpty) then
          Some(
            s"no researcher matched the ranking in window ${window.label}" +
              among.fold("")(a => s" (filter: ${a._1})")
          )
        else None
      EvidenceStageResult(
        plan,
        window,
        windowNote,
        resolutions,
        named,
        ranked,
        b,
        notes,
        abstain,
        graph.executed,
        sem.toOption.toVector.flatMap(_.dropped),
        Vector.empty
      )

  /** The candidate set of the topic and institution filters, its notes and the matched topic IDs.
    * Topic: Topic nodes by name, their works (HAS_TOPIC) published in the window, those works'
    * authors. Institution: Institution nodes by name and the researchers AFFILIATED_WITH them. Both
    * given: the intersection. A phrase that matches no node gives an empty set, never no filter.
    */
  private def candidateFilter(
      plan: QueryPlan,
      w: TimeWindow,
      graph: GraphRunner
  ): Either[WorkflowError, (Option[(String, Set[String])], Vector[String], Vector[String])] =
    val notes = Vector.newBuilder[String]
    def entities(label: String, phrases: Vector[String]) =
      seq(phrases.map { p =>
        stores.directory
          .entities(label, p, MaxFilterEntities)
          .left
          .map(e => WorkflowError.Downstream("filters", e))
          .map { es =>
            if es.isEmpty then
              notes += s"${label.toLowerCase} '$p' matches no ${label.toLowerCase} name"
            else
              notes += s"${label.toLowerCase} '$p' matched by name tokens: ${es.map(e => s"${e.name} (${e.entityId})").mkString(", ")}"
            es
          }
      }).map(_.flatten.distinctBy(_.entityId))
    def neighbours(id: String, edge: String) =
      graph
        .run("filters", ctx.templates.neighbors(id, Set(edge), "in", NeighbourLimit))
        .map(rows =>
          if rows.size >= NeighbourLimit then
            notes += s"$id: only the first $NeighbourLimit $edge neighbours were read"
          rows.flatMap(_.value.get("nodeId").flatMap(_.strOpt))
        )
    val topicSet: Either[WorkflowError, Option[(Set[String], Vector[String])]] =
      if !plan.operations.contains(QueryOperation.TOPIC_FILTER) then Right(None)
      else
        for
          topics <- entities(GraphLabels.Topic, plan.topics)
          works <- seq(topics.map(t => neighbours(t.entityId, GraphLabels.HasTopic)))
            .map(_.flatten.distinct.sorted)
          _ = if works.size > TopicWorkCap then
            notes += s"topic filter read ${TopicWorkCap} of ${works.size} topic works"
          years <- seq(
            works
              .take(TopicWorkCap)
              .map(wk => graph.run("filters", ctx.templates.node(wk)).map(rows => wk -> rows))
          )
          inWindow = years.collect {
            case (wk, Vector(r))
                if r.value
                  .get("properties")
                  .flatMap(_.objOpt)
                  .flatMap(_.get("year"))
                  .flatMap(_.strOpt)
                  .flatMap(_.toIntOption)
                  .exists(w.contains) =>
              wk
          }
          authors <- seq(
            inWindow.map(wk => graph.run("filters", ctx.templates.workAuthors(wk, AuthorLimit)))
          ).map(
            _.flatten.flatMap(_.value.get("researcherId").flatMap(_.strOpt)).toSet
          )
        yield Some((authors, topics.map(_.entityId)))
    val institutionSet: Either[WorkflowError, Option[Set[String]]] =
      if !plan.operations.contains(QueryOperation.INSTITUTION_FILTER) then Right(None)
      else
        for
          insts <- entities(GraphLabels.Institution, plan.institutions)
          people <- seq(insts.map(i => neighbours(i.entityId, GraphLabels.AffiliatedWith)))
            .map(_.flatten.toSet)
        yield Some(people)
    for t <- topicSet; i <- institutionSet
    yield
      val desc = Vector(
        t.map(_ =>
          s"authors of works with topic ${plan.topics.mkString(" / ")} published in ${w.label}"
        ),
        i.map(_ => s"researchers affiliated with ${plan.institutions.mkString(" / ")}")
      ).flatten.mkString(" and ")
      val set = (t.map(_._1), i) match
        case (Some(a), Some(b)) => Some(a intersect b)
        case (a, b)             => a.orElse(b)
      (set.map(desc -> _), notes.result(), t.toVector.flatMap(_._2))

  private def identityReason(rs: Vector[Resolution]): String =
    rs.map { r =>
      r.status match
        case ResolutionStatus.AMBIGUOUS =>
          s"'${r.query}' is ambiguous (${r.reason}): " +
            r.candidates.map(c => s"${c.displayName} (${c.researcherId})").mkString(", ") +
            "; name one by researcher ID"
        case _ => s"no canonical researcher matches '${r.query}'"
    }.mkString("; ") + "; the answer abstains rather than choose a researcher"

object EvidencePipeline:
  val Version = "evidence-pipeline-v1"
  val ComponentNote =
    "component values are per researcher (componentId, componentSize in each metrics row); the Spark component table is not in any store the runtime reads, so no component list or largest-component record is available"
  val MaxCommunityViews = 5
  val CommunityEdgeLimit = 10
  val MaxFilterEntities = 3
  val NeighbourLimit = 200
  val TopicWorkCap = 30
  val AuthorLimit = 50
  def DirectLimit(cfg: ScholarChainConfig): Int = math.min(50, cfg.retrieval.graphLimit)
