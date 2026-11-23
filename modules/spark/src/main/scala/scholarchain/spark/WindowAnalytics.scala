package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions.*
import scholarchain.core.{SchemaVersions, TimeWindow}

/** Jobs 3 to 8 for one window: edges, measures, connected components, communities, bridge measures,
  * sampled betweenness and distance statistics, and k-core coreness, joined into the core
  * `ResearcherMetrics` shape. Every count and invariant failure goes into `report`.
  *
  * Parameters, all from the `graph` section of `scholarchain-v1.yaml`: `componentMaxIterations`
  * (components; BFS depth bound for betweenness and distance statistics; k-core iteration bound),
  * `communityIterations`, `betweennessSampleSources`, `betweennessSeed`, `kcoreMaxK`,
  * `representativeWorkLimit`, `policyVersion`, `metricVersion`.
  */
object WindowAnalytics:
  final case class Result(
      window: TimeWindow,
      edges: DataFrame,
      metrics: DataFrame,
      components: DataFrame,
      communities: DataFrame,
      crossCommunityEdges: DataFrame,
      distanceStats: DataFrame,
      report: ujson.Obj
  )

  /** `cm:` / `cc:` + h(`label|window|policy`, 16): window-specific community and component IDs. */
  private def groupId(prefix: String, label: Column, w: TimeWindow, policy: String): Column =
    concat(lit(prefix), Cols.shortHash(concat_ws("|", label, lit(w.label), lit(policy)), 16))

  def run(authored: DataFrame, pairWorks: DataFrame, w: TimeWindow, run: RunInfo): Result =
    val g = run.graph
    val report = ujson.Obj()
    Report.putS(report, "window", w.label)

    // Jobs 2-3: edges and measures --------------------------------------------------------------
    val edges = Lineage.cut(CoauthorGraph.edges(pairWorks, w, run))
    val graph = CoauthorGraph.graphEdges(edges)
    val base = Lineage.cut(CoauthorGraph.metrics(authored, edges, w, g.representativeWorkLimit))
    val nodes = base.select(col("researcherId").as("id"))
    val n = nodes.count()
    Report.put(report, "researchers", n)
    Report.put(report, "edges", edges.count())

    // Job 4: connected components ---------------------------------------------------------------
    val (cc, ccStats) = GraphAlgorithms.connectedComponents(nodes, graph, g.componentMaxIterations)
    val sizes = cc.groupBy("label").agg(count(lit(1)).as("componentSize"))
    val componentMembers = Lineage.cut(
      cc.join(sizes, "label")
        .select(
          col("id"),
          groupId("cc:", col("label"), w, g.policyVersion).as("componentId"),
          col("componentSize")
        )
    )
    val components = Lineage.cut(
      componentMembers
        .groupBy("componentId")
        .agg(first("componentSize").as("componentSize"))
        .select(
          col("componentId"),
          col("componentSize"),
          (col("componentSize").cast("double") / lit(math.max(n, 1L).toDouble)).as("fraction"),
          Cols.windowStruct(w).as("window"),
          lit(w.label).as("windowLabel"),
          Cols
            .provenance(run, lit("SCHOLARCHAIN"), col("componentId"), None, g.policyVersion)
            .as("provenance")
        )
    )
    // Largest component: largest size, ties by the smallest ID (a one-row distributed top-1).
    val largest =
      components.orderBy(col("componentSize").desc, col("componentId").asc).limit(1).collect()
    val largestId = largest.headOption.map(_.getString(0))
    val largestSize = largest.headOption.map(_.getLong(1)).getOrElse(0L)
    Report.put(report, "components", components.count())
    Report.put(report, "largestComponentSize", largestSize)
    Report.putD(
      report,
      "largestComponentFraction",
      if n == 0 then 0.0 else largestSize.toDouble / n
    )
    Report.put(
      report,
      "singletonResearchers",
      components.filter(col("componentSize") === 1).count()
    )
    Report.put(report, "componentIterations", ccStats.iterations.toLong)
    // A cut-off leaves components split: an invariant failure (0 or 1), not just a flag.
    Report.put(report, "invariant_componentsNotConverged", if ccStats.converged then 0L else 1L)

    // Job 6: communities (weighted label propagation) -----------------------------------------
    val (lpa, lpaStats) = GraphAlgorithms.labelPropagation(nodes, graph, g.communityIterations)
    val communityOf = Lineage.cut(
      lpa.select(col("id"), groupId("cm:", col("label"), w, g.policyVersion).as("communityId"))
    )
    val endpoints = edges
      .select("edgeId", "researcherA", "researcherB", "jointPublicationCount")
      .join(communityOf.toDF("researcherA", "communityA"), "researcherA")
      .join(communityOf.toDF("researcherB", "communityB"), "researcherB")
    val crossCommunityEdges = Lineage.cut(
      endpoints
        .filter(col("communityA") =!= col("communityB"))
        .select(
          col("edgeId"),
          col("researcherA"),
          col("researcherB"),
          col("communityA"),
          col("communityB"),
          col("jointPublicationCount"),
          Cols.windowStruct(w).as("window"),
          lit(w.label).as("windowLabel")
        )
    )
    val edgeEnds = endpoints
      .select(col("communityA").as("communityId"), col("communityB").as("other"))
      .unionByName(
        endpoints.select(col("communityB").as("communityId"), col("communityA").as("other"))
      )
      .groupBy("communityId")
      .agg(
        (sum(when(col("other") === col("communityId"), 1).otherwise(0)) / 2)
          .cast("long")
          .as("internalEdgeCount"),
        sum(when(col("other") =!= col("communityId"), 1).otherwise(0))
          .cast("long")
          .as("crossCommunityEdgeCount")
      )
    val communities = Lineage.cut(
      communityOf
        .groupBy("communityId")
        .agg(count(lit(1)).as("communitySize"))
        .join(edgeEnds, Seq("communityId"), "left")
        .select(
          col("communityId"),
          col("communitySize"),
          coalesce(col("internalEdgeCount"), lit(0L)).as("internalEdgeCount"),
          coalesce(col("crossCommunityEdgeCount"), lit(0L)).as("crossCommunityEdgeCount"),
          Cols.windowStruct(w).as("window"),
          lit(w.label).as("windowLabel"),
          Cols
            .provenance(run, lit("SCHOLARCHAIN"), col("communityId"), None, g.policyVersion)
            .as("provenance")
        )
    )
    Report.put(report, "communities", communities.count())
    Report.put(
      report,
      "communitiesOfTwoOrMore",
      communities.filter(col("communitySize") >= 2).count()
    )
    Report.put(report, "crossCommunityEdges", crossCommunityEdges.count())
    Report.put(report, "communityIterations", lpaStats.iterations.toLong)
    // Synchronous label propagation may oscillate (documented); its cut-off stays a reported flag.
    Report.putB(report, "communitiesConverged", lpaStats.converged)

    // Job 7: bridge measures ------------------------------------------------------------------
    val bridges = Bridges.measures(graph, communityOf)
    val connected =
      base.filter(col("distinctCoauthorCount") > 0).select(col("researcherId").as("id"))
    val sources = Lineage.cut(
      GraphAlgorithms.sampleSources(connected, g.betweennessSampleSources, g.betweennessSeed)
    )
    val k = sources.count()
    val population = connected.count()
    // An empty source set gives empty levels, so betweenness is 0 everywhere.
    val (levels, truncated) =
      GraphAlgorithms.multiSourceBfs(sources, graph, g.componentMaxIterations)
    val betweenness =
      Lineage.cut(GraphAlgorithms.approximateBetweenness(levels, graph, n, population, k))
    Report.put(report, "betweennessSources", k)
    Report.put(report, "betweennessSourcePopulation", population)
    Report.putS(
      report,
      "betweennessMethod",
      if k >= population then "exact (every connected researcher is a source)"
      else "sampled-source Brandes estimate scaled by population/k"
    )
    Report.putB(report, "betweennessBfsTruncated", truncated)
    // Job 5: sampled distance statistics.
    val distanceStats = Lineage.cut(
      Paths
        .distanceStats(levels, componentMembers)
        .withColumn("window", Cols.windowStruct(w))
        .withColumn("windowLabel", lit(w.label))
    )
    val overall = levels
      .filter(col("dist") > 0)
      .agg(
        count(lit(1)),
        avg("dist"),
        percentile_approx(col("dist"), lit(0.9), lit(10000)),
        max("dist")
      )
      .head()
    Report.put(report, "sampledDistancePairs", overall.getLong(0))
    Report.putD(
      report,
      "sampledAveragePathLength",
      Option(overall.get(1)).map(_.toString.toDouble).getOrElse(0.0)
    )
    Report.put(
      report,
      "sampledEffectiveDiameter",
      Option(overall.get(2)).map(_.toString.toLong).getOrElse(0L)
    )
    Report.put(
      report,
      "sampledMaxDistance",
      Option(overall.get(3)).map(_.toString.toLong).getOrElse(0L)
    )

    // Job 8: k-core ------------------------------------------------------------------------------
    val (core, coreStats) =
      GraphAlgorithms.coreness(nodes, graph, g.kcoreMaxK, g.componentMaxIterations)
    val coreness = Lineage.cut(core)
    Report.put(report, "corenessIterations", coreStats.iterations.toLong)
    // A cut-off leaves coreness estimates above the fixpoint: an invariant failure (0 or 1).
    Report.put(report, "invariant_corenessNotConverged", if coreStats.converged then 0L else 1L)
    Report.put(report, "corenessAtCap", coreness.filter(col("coreness") >= g.kcoreMaxK).count())
    Report.put(
      report,
      "maxCoreness",
      Option(coreness.agg(max("coreness")).head().get(0)).map(_.toString.toLong).getOrElse(0L)
    )

    // Researcher metrics (core ResearcherMetrics shape plus visible extras) ---------------------
    val metrics = Lineage.cut(
      base
        .join(componentMembers.withColumnRenamed("id", "researcherId"), "researcherId")
        .join(communityOf.withColumnRenamed("id", "researcherId"), "researcherId")
        .join(bridges.withColumnRenamed("id", "researcherId"), "researcherId")
        .join(betweenness.withColumnRenamed("id", "researcherId"), Seq("researcherId"), "left")
        .join(coreness.withColumnRenamed("id", "researcherId"), "researcherId")
        .select(
          lit(SchemaVersions.Metrics).as("schemaVersion"),
          col("researcherId"),
          col("publicationCount"),
          col("distinctCoauthorCount"),
          col("totalCoauthorOccurrences"),
          col("averageCoauthorsPerPaper"),
          col("maximumCoauthorsOnOnePaper"),
          col("maximumTeamWorkIds"),
          col("maximumTeamWorkCount"),
          col("weightedDegree"),
          col("componentId"),
          col("componentSize"),
          largestId.fold(lit(false))(id => col("componentId") === id).as("inLargestComponent"),
          col("communityId"),
          coalesce(col("betweenness"), lit(0.0)).as("bridgeScore"),
          col("participationCoefficient"),
          col("crossCommunityDegree"),
          col("communitiesConnected"),
          col("coreness"),
          Cols.windowStruct(w).as("window"),
          lit(w.label).as("windowLabel"),
          lit(g.metricVersion).as("metricVersion"),
          Cols
            .provenance(
              run,
              lit("SCHOLARCHAIN"),
              col("researcherId"),
              Some(SchemaVersions.Metrics),
              g.metricVersion
            )
            .as("provenance")
        )
    )

    // Invariants (every count must be 0) ---------------------------------------------------------
    def inv(name: String, bad: Long): Unit = Report.put(report, s"invariant_$name", bad)
    inv("metricsRowsNotEqualNodes", math.abs(metrics.count() - n))
    inv(
      "CrGreaterThanWr",
      metrics.filter(col("distinctCoauthorCount") > col("weightedDegree")).count()
    )
    inv(
      "WrGreaterThanTr",
      metrics.filter(col("weightedDegree") > col("totalCoauthorOccurrences")).count()
    )
    inv(
      "MrGreaterThanTr",
      metrics.filter(col("maximumCoauthorsOnOnePaper") > col("totalCoauthorOccurrences")).count()
    )
    inv(
      "MrWithoutWorks",
      metrics
        .filter(col("maximumCoauthorsOnOnePaper") > 0 && size(col("maximumTeamWorkIds")) === 0)
        .count()
    )
    inv("publicationCountBelowOne", metrics.filter(col("publicationCount") < 1).count())
    inv("edgesUnordered", edges.filter(col("researcherA") >= col("researcherB")).count())
    inv("edgesWithoutPublication", edges.filter(col("jointPublicationCount") < 1).count())
    inv(
      "edgeYearsOutsideWindow",
      edges
        .filter(
          !Cols.inWindow(col("firstCollaborationYear"), w) || !Cols.inWindow(
            col("lastCollaborationYear"),
            w
          ) || col("firstCollaborationYear") > col("lastCollaborationYear")
        )
        .count()
    )
    inv(
      "edgeEndpointMissing",
      edges.join(nodes.toDF("researcherA"), Seq("researcherA"), "left_anti").count() +
        edges.join(nodes.toDF("researcherB"), Seq("researcherB"), "left_anti").count()
    )
    inv("duplicateEdgeIds", edges.count() - edges.select("edgeId").distinct().count())
    inv(
      "componentSizesNotSummingToNodes",
      math.abs(
        Option(components.agg(sum("componentSize")).head().get(0))
          .map(_.toString.toLong)
          .getOrElse(0L) - n
      )
    )
    inv(
      "communitySizesNotSummingToNodes",
      math.abs(
        Option(communities.agg(sum("communitySize")).head().get(0))
          .map(_.toString.toLong)
          .getOrElse(0L) - n
      )
    )
    inv(
      "crossCommunityEdgeEndsMismatch",
      math.abs(
        Option(communities.agg(sum("crossCommunityEdgeCount")).head().get(0))
          .map(_.toString.toLong)
          .getOrElse(0L) -
          2 * crossCommunityEdges.count()
      )
    )

    // Distributions for the run report (approximate quantiles; distributed, 3 numbers each).
    if n > 0 then
      val qs = Seq(
        "publicationCount",
        "distinctCoauthorCount",
        "totalCoauthorOccurrences",
        "maximumCoauthorsOnOnePaper",
        "weightedDegree",
        "coreness",
        "participationCoefficient",
        "bridgeScore"
      )
      val q = metrics
        .select(qs.map(c => col(c).cast("double").as(c))*)
        .stat
        .approxQuantile(qs.toArray, Array(0.5, 0.9, 0.99), 0.001)
      qs.zip(q).foreach { (name, v) =>
        report(s"quantiles_p50_p90_p99_$name") =
          ujson.Arr(v.toSeq.map(x => ujson.Num(x): ujson.Value)*)
      }

    Result(w, edges, metrics, components, communities, crossCommunityEdges, distanceStats, report)
