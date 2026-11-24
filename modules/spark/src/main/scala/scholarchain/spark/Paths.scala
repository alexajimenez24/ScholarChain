package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import scholarchain.core.TimeWindow

/** Job 5: connectivity and path support.
  *
  * Shortest paths for selected pairs: one multi-source BFS from the distinct sources of the pairs
  * (one join and one aggregation per level, at most `pathMaxDepth` levels), then parent pointers
  * are walked back from each target. Cost grows with (number of sources x reachable nodes within
  * the depth bound), never with all pairs, and nothing but the requested pairs reaches the driver.
  * Path evidence is bounded: at most `pathMaxDepth` hops, each with the COAUTHORED_WITH edge ID
  * that supports it, so a path claim can be checked against the edge table.
  *
  * Pair selection for the pipeline ([[sampledEvidence]], logic spark-v4): the sources are the
  * window's sampled betweenness sources, and each source's targets are chosen from its own BFS
  * reach within `pathMaxDepth` hops, so every requested pair is reachable by construction (the
  * earlier rule paired the sources with one another, and on a fragmented graph none was within the
  * depth bound). See [[reachablePairs]] for the rule and its budget.
  *
  * Distance statistics: average path length and effective diameter (the 90th percentile of
  * shortest-path lengths, Leskovec et al. 2005) are estimated per component from BFS runs started
  * at the sampled betweenness sources (`betweennessSampleSources`, hash of ID and
  * `betweennessSeed`). They cover only components that hold a sampled source and only distances up
  * to the BFS depth bound; they are estimates over sampled sources, never all-pairs values.
  */
object Paths:
  /** `edges` in the core `CoauthorEdge` shape (one window); `pairs(src, tgt)` researcher IDs.
    * Result: one row per requested pair (src != tgt): found, distance (hops), shortestPathCount,
    * path (researcher IDs from src to tgt) and hopEdgeIds; distance and path are null when tgt is
    * not reachable within `maxDepth` hops.
    */
  def evidence(edges: DataFrame, pairs: DataFrame, maxDepth: Int, w: TimeWindow): DataFrame =
    val graph = CoauthorGraph.graphEdges(edges)
    val requested = pairs.select("src", "tgt").filter(col("src") =!= col("tgt")).distinct()
    val sources = requested.select("src").distinct()
    val (levels, _) = GraphAlgorithms.multiSourceBfs(sources, graph, maxDepth)
    fromLevels(edges, levels, requested, maxDepth, w)

  /** Targets per source for a sample of `k` sources: max(1, floor((k - 1) / 2)). For k >= 3 the
    * window budget k x that is at most k (k - 1) / 2, the pair count of the earlier
    * sources-with-sources rule (16 sources: 7 targets each, at most 112 pairs).
    */
  def targetsPerSource(k: Int): Int = math.max(1, (k - 1) / 2)

  /** Reachable targets from BFS levels `(src, node, dist, ...)` (depth-bounded): at most
    * `perSource` pairs `(src, tgt)` per source, every one with tgt reached within the bound.
    *
    * Rule, deterministic for a given seed: candidates are the nodes at distance 1 or more. A pair
    * of two sources is kept only from the smaller ID (src < tgt), so one undirected pair is
    * requested once. Within each (source, distance) the candidates are ranked by SHA-256 of
    * `src|tgt|seed` (ties by ID); the source then takes them round-robin over distances, longest
    * distance first: one candidate at each distance (6, 5, ..., 1 with depth 6), then a second at
    * each, and so on, until `perSource` are chosen or the reach is exhausted. So multi-hop paths
    * are preferred and the chosen distances are spread over the reach. Distributed window functions
    * only; the levels hold at most (sources x nodes within the depth bound) rows.
    */
  def reachablePairs(levels: DataFrame, perSource: Int, seed: Long): DataFrame =
    val sources = levels.filter(col("dist") === 0).select(col("src").as("tgt"))
    val candidates = levels
      .filter(col("dist") >= 1)
      .select(col("src"), col("node").as("tgt"), col("dist"))
      .join(sources.withColumn("tgtIsSource", lit(true)), Seq("tgt"), "left")
      .filter(col("tgtIsSource").isNull || col("src") < col("tgt"))
      .withColumn("h", sha2(concat_ws("|", col("src"), col("tgt"), lit(seed.toString)), 256))
    val inLevel = Window.partitionBy("src", "dist").orderBy(col("h").asc, col("tgt").asc)
    val inSource = Window.partitionBy("src").orderBy(col("round").asc, col("dist").desc)
    candidates
      .withColumn("round", row_number().over(inLevel))
      .withColumn("pick", row_number().over(inSource))
      .filter(col("pick") <= perSource)
      .select("src", "tgt")

  /** Path evidence of the pipeline: BFS from `sources(src)` up to `maxDepth` hops, at most
    * `perSource` targets per source by [[reachablePairs]] (the pipeline passes [[targetsPerSource]]
    * of `betweennessSampleSources`), then the shortest paths. Every requested pair is found; one
    * that is not is the pipeline invariant `pathRequestedNotFound`.
    */
  def sampledEvidence(
      edges: DataFrame,
      sources: DataFrame,
      maxDepth: Int,
      perSource: Int,
      seed: Long,
      w: TimeWindow
  ): DataFrame =
    val graph = CoauthorGraph.graphEdges(edges)
    val src = sources.select("src").distinct()
    val (levels, _) = GraphAlgorithms.multiSourceBfs(src, graph, maxDepth)
    val requested = Lineage.cut(reachablePairs(levels, perSource, seed))
    fromLevels(edges, levels, requested, maxDepth, w)

  /** Shortest paths of the `requested(src, tgt)` pairs from BFS levels whose sources include every
    * src, with one COAUTHORED_WITH edge ID per hop.
    */
  private def fromLevels(
      edges: DataFrame,
      levels: DataFrame,
      requested: DataFrame,
      maxDepth: Int,
      w: TimeWindow
  ): DataFrame =
    val found = Lineage.cut(GraphAlgorithms.reconstructPaths(levels, requested, maxDepth))
    val hops = found
      .select(
        col("src"),
        col("tgt"),
        posexplode(
          zip_with(
            slice(col("path"), lit(1), size(col("path")) - 1),
            slice(col("path"), lit(2), size(col("path")) - 1),
            (x, y) => struct(least(x, y).as("researcherA"), greatest(x, y).as("researcherB"))
          )
        ).as(Seq("pos", "hop"))
      )
      .select(col("src"), col("tgt"), col("pos"), col("hop.researcherA"), col("hop.researcherB"))
      .join(
        edges.select("researcherA", "researcherB", "edgeId"),
        Seq("researcherA", "researcherB"),
        "left"
      )
      .groupBy("src", "tgt")
      .agg(sort_array(collect_list(struct(col("pos"), col("edgeId")))).as("hops"))
      .select(
        col("src"),
        col("tgt"),
        transform(col("hops"), h => h.getField("edgeId")).as("hopEdgeIds")
      )
    requested
      .join(found, Seq("src", "tgt"), "left")
      .join(hops, Seq("src", "tgt"), "left")
      .select(
        col("src"),
        col("tgt"),
        col("path").isNotNull.as("found"),
        col("dist").as("distance"),
        col("sigma").cast("long").as("shortestPathCount"),
        col("path"),
        col("hopEdgeIds"),
        lit(maxDepth).as("maxDepth"),
        Cols.windowStruct(w).as("window"),
        lit(w.label).as("windowLabel")
      )

  /** Per-component distance statistics from sampled-source BFS levels `(src, node, dist, ...)` and
    * `components(id, componentId)`: sampledSources, sampledPairs, averagePathLength,
    * effectiveDiameter (approximate 90th percentile) and maxObservedDistance.
    */
  def distanceStats(levels: DataFrame, components: DataFrame): DataFrame =
    levels
      .filter(col("dist") > 0)
      .join(components.select(col("id").as("src"), col("componentId")), "src")
      .groupBy("componentId")
      .agg(
        countDistinct("src").as("sampledSources"),
        count(lit(1)).as("sampledPairs"),
        avg("dist").as("averagePathLength"),
        percentile_approx(col("dist"), lit(0.9), lit(10000)).as("effectiveDiameter"),
        max("dist").as("maxObservedDistance")
      )
