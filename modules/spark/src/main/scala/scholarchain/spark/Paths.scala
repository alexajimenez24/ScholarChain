package scholarchain.spark

import org.apache.spark.sql.DataFrame
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
