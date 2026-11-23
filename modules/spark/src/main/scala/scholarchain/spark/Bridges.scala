package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*

/** Job 7: community-based bridge measures, all exact (no approximation), all unweighted (they count
  * distinct coauthors, not joint publications). For researcher i with k_i distinct coauthors, of
  * which k_ic belong to community c (i's own community included):
  *
  *   - `crossCommunityDegree` = number of coauthors outside i's own community.
  *   - `communitiesConnected` = number of distinct communities i touches directly: its own
  *     community plus every community of a coauthor. 1 for an interior or isolated researcher, 2
  *     for a researcher linking its community to one other.
  *   - `participationCoefficient` P_i = 1 - sum_c (k_ic / k_i)^2 (Guimera and Amaral, 2005); 0 when
  *     all coauthors share one community (or i has none), approaching 1 when they are spread evenly
  *     over many communities.
  *
  * Limits: every measure inherits the community assignment, so it is only as meaningful as label
  * propagation's partition, and it ignores how many publications support each link. The sampled
  * betweenness in [[GraphAlgorithms.approximateBetweenness]] is the path-based complement.
  *
  * Shuffles: two joins attach the community of each endpoint (partitioned on the node ID), then two
  * aggregations by researcher.
  */
object Bridges:
  /** `edges(a, b, w)`, `communities(id, communityId)`; one row per node of `communities`. */
  def measures(edges: DataFrame, communities: DataFrame): DataFrame =
    val adj = GraphAlgorithms
      .adjacency(edges)
      .select("src", "dst")
      .join(communities.select(col("id").as("dst"), col("communityId").as("nc")), "dst")
      .join(communities.select(col("id").as("src"), col("communityId").as("oc")), "src")
    val perCommunity = adj.groupBy("src", "oc", "nc").agg(count(lit(1)).as("k"))
    val totals = perCommunity.groupBy("src").agg(sum("k").as("deg"))
    val connected = perCommunity
      .join(totals, "src")
      .groupBy(col("src").as("id"))
      .agg(
        sum(when(col("nc") =!= col("oc"), col("k")).otherwise(lit(0L))).as("crossCommunityDegree"),
        size(array_union(array(min("oc")), collect_set("nc")))
          .cast("long")
          .as("communitiesConnected"),
        (lit(1.0) - sum(pow(col("k") / col("deg"), 2))).as("participationCoefficient")
      )
    communities
      .select(col("id"))
      .join(connected, Seq("id"), "left")
      .select(
        col("id"),
        coalesce(col("crossCommunityDegree"), lit(0L)).as("crossCommunityDegree"),
        coalesce(col("communitiesConnected"), lit(1L)).as("communitiesConnected"),
        coalesce(col("participationCoefficient"), lit(0.0)).as("participationCoefficient")
      )
