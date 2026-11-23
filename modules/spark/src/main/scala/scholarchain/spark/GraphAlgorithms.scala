package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*

/** Distributed graph algorithms over DataFrames (no GraphX or GraphFrames dependency).
  *
  * Conventions: `nodes(id)`; undirected `edges(a, b, w)` with a < b and one row per pair. No
  * algorithm collects the graph to the driver: the driver reads only per-iteration change counts,
  * the maximum BFS depth and the caller's small result rows. Every iteration ends with
  * [[Lineage.cut]], so plans stay shallow (reliable checkpoints when a checkpoint directory is
  * set).
  *
  * Shuffle boundaries per iteration: one join of the adjacency with the current state (hash
  * partitioned on the joined node ID), one aggregation by the receiving node, and one join back to
  * the state. `spark.sql.shuffle.partitions` sets the partition count of each; AQE may coalesce
  * them or broadcast a small side.
  */
object GraphAlgorithms:
  final case class IterationStats(iterations: Int, converged: Boolean)

  /** Both directions of every undirected edge: (src, dst, w). */
  def adjacency(edges: DataFrame): DataFrame =
    edges
      .select(col("a").as("src"), col("b").as("dst"), col("w"))
      .unionByName(edges.select(col("b").as("src"), col("a").as("dst"), col("w")))

  /** Connected components by hash-min label propagation: every node repeatedly takes the smallest
    * label among itself and its neighbours. It converges after at most (largest component diameter
    * + 1) iterations; `maxIterations` bounds the cost and `converged = false` reports a cut-off.
    * Result: (id, label), where label is the smallest node ID of the component.
    */
  def connectedComponents(
      nodes: DataFrame,
      edges: DataFrame,
      maxIterations: Int
  ): (DataFrame, IterationStats) =
    val adj = adjacency(edges).select("src", "dst").persist()
    var labels = Lineage.cut(nodes.select(col("id"), col("id").as("label")))
    var iteration = 0
    var changed = 1L
    while changed > 0 && iteration < maxIterations do
      val msgs = adj
        .join(labels.withColumnRenamed("id", "src"), "src")
        .groupBy(col("dst").as("id"))
        .agg(min("label").as("m"))
      val next = Lineage.cut(
        labels
          .join(msgs, Seq("id"), "left")
          .select(
            col("id"),
            least(col("label"), coalesce(col("m"), col("label"))).as("label"),
            coalesce(col("m") < col("label"), lit(false)).as("chg")
          )
      )
      changed = next.filter(col("chg")).count()
      labels = next.drop("chg")
      iteration += 1
    adj.unpersist()
    labels -> IterationStats(iteration, changed == 0)

  /** Community detection by weighted synchronous label propagation (Raghavan, Albert and Kumara,
    * 2007), adapted to run deterministically on DataFrames.
    *
    * Each iteration every node adopts the label with the largest summed vote, where every neighbour
    * votes for its own label with the edge weight (joint publication count) and the node votes for
    * its current label with the weight of its strongest edge (1 for an isolated node). Ties go to
    * the smallest label. The self vote damps the two-colour oscillation of plain synchronous LPA:
    * two nodes joined by their strongest edge tie between their two labels and both take the
    * smaller one instead of swapping forever; it also keeps isolated nodes in their own community.
    * Synchronous LPA can still oscillate on other structures, so the run stops when no label
    * changes or after `iterations` rounds (`communityIterations` in the graph config), and
    * `converged = false` reports a cut-off. Result: (id, label), label being a node ID.
    *
    * Limits: LPA optimises no global objective (it is not modularity maximisation); a giant
    * community can absorb weakly attached groups; results depend on the tie rule. It is chosen
    * because it is linear per iteration, needs no extra dependency and is reproducible.
    */
  def labelPropagation(
      nodes: DataFrame,
      edges: DataFrame,
      iterations: Int
  ): (DataFrame, IterationStats) =
    val adj = adjacency(edges).persist()
    val strongest = adj.groupBy(col("src").as("id")).agg(max("w").cast("double").as("sw"))
    var labels = Lineage.cut(
      nodes
        .join(strongest, Seq("id"), "left")
        .select(col("id"), col("id").as("label"), coalesce(col("sw"), lit(1.0)).as("sw"))
    )
    val rank = Window.partitionBy("id").orderBy(col("score").desc, col("l").asc)
    var iteration = 0
    var changed = 1L
    while changed > 0 && iteration < iterations do
      val neighbourVotes = adj
        .join(labels.select(col("id").as("src"), col("label").as("l")), "src")
        .select(col("dst").as("id"), col("l"), col("w").cast("double").as("v"))
      val selfVotes = labels.select(col("id"), col("label").as("l"), col("sw").as("v"))
      val best = neighbourVotes
        .unionByName(selfVotes)
        .groupBy("id", "l")
        .agg(sum("v").as("score"))
        .withColumn("r", row_number().over(rank))
        .filter(col("r") === 1)
        .select("id", "l")
      val next = Lineage.cut(
        labels
          .join(best, Seq("id"), "left")
          .select(
            col("id"),
            coalesce(col("l"), col("label")).as("label"),
            col("sw"),
            coalesce(col("l") =!= col("label"), lit(false)).as("chg")
          )
      )
      changed = next.filter(col("chg")).count()
      labels = next.drop("chg")
      iteration += 1
    adj.unpersist()
    labels.drop("sw") -> IterationStats(iteration, changed == 0)

  /** h-index of an integer array column: the largest h such that h elements are >= h. */
  def hIndex(values: Column): Column =
    val sorted = sort_array(values, asc = false)
    when(values.isNull || size(values) === 0, lit(0))
      .otherwise(size(filter(sequence(lit(1), size(values)), i => element_at(sorted, i) >= i)))

  /** Coreness (k-core number) by the distributed h-index iteration of Montresor, De Pellegrini and
    * Miorandi (2013): every estimate starts at min(degree, maxK) and each node lowers its estimate
    * to the h-index of its neighbours' estimates. The fixpoint is min(coreness, maxK) for every
    * node (`kcoreMaxK` in the graph config caps the reported value; a node at the cap may have a
    * larger true coreness). `maxIterations` bounds the cost; `converged = false` reports a cut-off.
    * Coreness counts distinct coauthors and ignores edge weights.
    */
  def coreness(
      nodes: DataFrame,
      edges: DataFrame,
      maxK: Int,
      maxIterations: Int
  ): (DataFrame, IterationStats) =
    val adj = adjacency(edges).select("src", "dst").persist()
    val degree = adj.groupBy(col("src").as("id")).agg(count(lit(1)).cast("int").as("deg"))
    var est = Lineage.cut(
      nodes
        .join(degree, Seq("id"), "left")
        .select(col("id"), least(coalesce(col("deg"), lit(0)), lit(maxK)).as("k"))
    )
    var iteration = 0
    var changed = 1L
    while changed > 0 && iteration < maxIterations do
      val nbr = adj
        .join(est.select(col("id").as("dst"), col("k").as("nk")), "dst")
        .groupBy(col("src").as("id"))
        .agg(collect_list("nk").as("ks"))
      val next = Lineage.cut(
        est
          .join(nbr, Seq("id"), "left")
          .select(col("id"), col("k"), least(col("k"), hIndex(col("ks"))).as("k2"))
          .select(col("id"), col("k2").as("k"), (col("k2") =!= col("k")).as("chg"))
      )
      changed = next.filter(col("chg")).count()
      est = next.drop("chg")
      iteration += 1
    adj.unpersist()
    est.withColumnRenamed("k", "coreness") -> IterationStats(iteration, changed == 0)

  /** Breadth-first search from many sources at once, one join and one aggregation per level. Result
    * rows (src, node, dist, sigma, parent): sigma is the number of shortest src-node paths and
    * parent the smallest predecessor on one of them (null for the source). Stops when every
    * frontier is empty or at `maxDepth`; the second result is true when it stopped at the bound
    * with a non-empty frontier (nodes further away were not reached).
    */
  def multiSourceBfs(sources: DataFrame, edges: DataFrame, maxDepth: Int): (DataFrame, Boolean) =
    val adj = adjacency(edges).select(col("src").as("u"), col("dst").as("v")).persist()
    var visited = Lineage.cut(
      sources.select(
        col("src"),
        col("src").as("node"),
        lit(0).as("dist"),
        lit(1.0).as("sigma"),
        Cols.nullString.as("parent")
      )
    )
    var frontier = visited
    var depth = 0
    var size = visited.count()
    while depth < maxDepth && size > 0 do
      val step = frontier
        .select(col("src"), col("node").as("u"), col("sigma").as("su"))
        .join(adj, "u")
        .groupBy(col("src"), col("v").as("node"))
        .agg(sum("su").as("sigma"), min("u").as("parent"))
      frontier = Lineage.cut(
        step
          .join(visited.select("src", "node"), Seq("src", "node"), "left_anti")
          .select(col("src"), col("node"), lit(depth + 1).as("dist"), col("sigma"), col("parent"))
      )
      size = frontier.count()
      visited = Lineage.cut(visited.unionByName(frontier))
      depth += 1
    adj.unpersist()
    visited -> (size > 0)

  /** Deterministic sample of BFS sources: the `k` node IDs with the smallest SHA-256 of `id|seed`.
    * The hash order is a pseudo-random permutation fixed by the seed, so a rerun picks the same
    * sources. A distributed top-k: the driver receives only the k IDs.
    */
  def sampleSources(candidates: DataFrame, k: Int, seed: Long): DataFrame =
    candidates
      .select(col("id").as("src"), sha2(concat_ws("|", col("id"), lit(seed.toString)), 256).as("h"))
      .orderBy("h", "src")
      .limit(k)
      .select("src")

  /** Sampled-source betweenness centrality (Brandes, 2001; source sampling as in Brandes and Pich,
    * 2007), from the BFS levels of [[multiSourceBfs]].
    *
    * Per source s, dependencies are accumulated from the deepest level back: delta_s(u) = sum over
    * successors w of u of sigma_su / sigma_sw * (1 + delta_s(w)). The k sources are a uniform
    * sample of a population of `populationCount` eligible sources (nodes with at least one edge; an
    * isolated source contributes nothing). The sum over the sample is scaled by populationCount / k
    * (an unbiased estimate of the sum over every source), halved because each undirected pair is
    * counted from both ends, and normalized by (n - 1)(n - 2) / 2, the number of node pairs not
    * including the node, with n = `nodeCount`. Exact when the sample is the whole population; with
    * k smaller it is an estimate whose variance falls as k grows, and a single value can exceed 1.
    * Unweighted: path length counts hops, not joint publications.
    */
  def approximateBetweenness(
      levels: DataFrame,
      edges: DataFrame,
      nodeCount: Long,
      populationCount: Long,
      sourceCount: Long
  ): DataFrame =
    val adj = adjacency(edges).select(col("src").as("u"), col("dst").as("w")).persist()
    val maxDepth =
      Option(levels.agg(max("dist")).head().get(0)).map(_.toString.toInt).getOrElse(0)
    var deltaPrev = levels
      .filter(col("dist") === maxDepth)
      .select(col("src"), col("node"), lit(0.0).as("delta"))
    var all = deltaPrev
    for d <- maxDepth to 1 by -1 do
      val w = deltaPrev
        .join(levels.filter(col("dist") === d).select("src", "node", "sigma"), Seq("src", "node"))
        .select(col("src"), col("node").as("w"), col("sigma").as("sigmaW"), col("delta").as("dW"))
      val u = levels
        .filter(col("dist") === d - 1)
        .select(col("src"), col("node").as("u"), col("sigma").as("sigmaU"))
      val contrib = w
        .join(adj, "w")
        .join(u, Seq("src", "u"))
        .groupBy(col("src"), col("u"))
        .agg(sum(col("sigmaU") / col("sigmaW") * (lit(1.0) + col("dW"))).as("delta"))
      deltaPrev = Lineage.cut(
        u.join(contrib, Seq("src", "u"), "left")
          .select(col("src"), col("u").as("node"), coalesce(col("delta"), lit(0.0)).as("delta"))
      )
      all = all.unionByName(deltaPrev)
    adj.unpersist()
    val scale = if sourceCount == 0 then 0.0 else populationCount.toDouble / sourceCount / 2.0
    val norm = math.max(1.0, (nodeCount - 1).toDouble * (nodeCount - 2).toDouble / 2.0)
    all
      .filter(col("node") =!= col("src"))
      .groupBy(col("node").as("id"))
      .agg((sum("delta") * scale / norm).as("betweenness"))

  /** Shortest paths for selected (src, tgt) pairs from BFS levels whose sources include every
    * `src`: walks parent pointers back from each target. Result (src, tgt, dist, sigma, path); a
    * pair whose target was not reached is absent. With several shortest paths the one through the
    * smallest predecessors is returned and `sigma` gives how many exist.
    */
  def reconstructPaths(levels: DataFrame, pairs: DataFrame, maxDepth: Int): DataFrame =
    val parents = levels.select(col("src"), col("node").as("at"), col("parent"))
    var cur = pairs
      .join(
        levels.select(col("src"), col("node").as("tgt"), col("dist"), col("sigma")),
        Seq("src", "tgt")
      )
      .select(
        col("src"),
        col("tgt"),
        col("dist"),
        col("sigma"),
        col("tgt").as("at"),
        array(col("tgt")).as("path")
      )
    for _ <- 1 to maxDepth do
      cur = cur
        .join(parents, Seq("src", "at"), "left")
        .select(
          col("src"),
          col("tgt"),
          col("dist"),
          col("sigma"),
          coalesce(col("parent"), col("at")).as("at"),
          when(col("parent").isNotNull, concat(array(col("parent")), col("path")))
            .otherwise(col("path"))
            .as("path")
        )
    cur.select("src", "tgt", "dist", "sigma", "path")
