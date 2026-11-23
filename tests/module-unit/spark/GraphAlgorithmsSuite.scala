package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.*
import LocalSpark.*

/** Known graph G: triangles {n1,n2,n3} and {n5,n6,n7} joined through n4 (edges n3-n4, n4-n5), a
  * separate edge n8-n9 and the isolated node n10. All weights 1 unless stated.
  *
  * {{{
  *   n1       n6
  *   | \     / |
  *   |  n3-n4-n5 |      n8 - n9      n10
  *   | /     \ |
  *   n2       n7
  * }}}
  */
class GraphAlgorithmsSuite extends munit.FunSuite:
  private val ns = nodes((1 to 10).map(i => s"n$i")*)
  private val es = edges(
    ("n1", "n2", 1),
    ("n1", "n3", 1),
    ("n2", "n3", 1),
    ("n3", "n4", 1),
    ("n4", "n5", 1),
    ("n5", "n6", 1),
    ("n5", "n7", 1),
    ("n6", "n7", 1),
    ("n8", "n9", 1)
  )
  private def doubles(m: Map[String, Any]) = m.map((k, v) => k -> v.asInstanceOf[Double])

  test("connected components: {n1..n7}, {n8, n9}, {n10}") {
    // Hash-min: the label is the smallest ID in the component ("n1" < "n10" < "n2" as strings).
    // Diameter of {n1..n7} is 4 (n1 to n6), so at most 5 iterations are needed.
    val (cc, stats) =
      GraphAlgorithms.connectedComponents(ns, es, graphConfig.componentMaxIterations)
    assert(stats.converged)
    assert(stats.iterations <= 5, s"${stats.iterations} iterations")
    val label = asMap(cc, "id", "label")
    assertEquals((1 to 7).map(i => label(s"n$i")).toSet, Set[Any]("n1"))
    assertEquals(Set(label("n8"), label("n9")), Set[Any]("n8"))
    assertEquals(label("n10"), "n10")
    // An iteration bound below the diameter is reported as not converged.
    val (_, cut) = GraphAlgorithms.connectedComponents(ns, es, 1)
    assert(!cut.converged)
  }

  test("community assignment: weighted label propagation with a self vote") {
    // Trace (votes: the current label with the node's strongest edge weight, here always 1; each
    // neighbour its label with the edge weight; ties to the smallest label):
    //   it 1: n1,n2,n3 -> n1; n4 -> n3; n5 -> n4; n6,n7 -> n5; n8,n9 -> n8
    //   it 2: n4 sees {n3 (self), n1 (from n3), n4 (from n5)} -> n1; n5 sees n5 twice -> n5
    //   it 3: n4: n1 x2 -> n1; n5: n5 x3 -> n5; nothing changes, converged.
    // Communities: {n1..n4}, {n5,n6,n7}, {n8,n9}, {n10}. Without the self vote n8 and n9 would
    // swap labels forever.
    val (lpa, stats) = GraphAlgorithms.labelPropagation(ns, es, graphConfig.communityIterations)
    assertEquals(stats, GraphAlgorithms.IterationStats(3, true))
    val lab = asMap(lpa, "id", "label")
    assertEquals((1 to 4).map(i => lab(s"n$i")).toSet, Set[Any]("n1"))
    assertEquals((5 to 7).map(i => lab(s"n$i")).toSet, Set[Any]("n5"))
    assertEquals(Set(lab("n8"), lab("n9")), Set[Any]("n8"))
    assertEquals(lab("n10"), "n10")
    // Weights decide: path a -1- x -3- b; self votes a 1, x 3, b 3 (strongest edge).
    // it 1: a {a:1, x:1} tie -> a; x {x:3, a:1, b:3} tie -> b; b {b:3, x:3} tie -> b.
    // it 2: a {a:1, b:1} tie -> a; x {b:3+3, a:1} -> b; b {b:3+3} -> b. No change: {a}, {x, b}.
    // (With a self vote of 1, x and b would swap labels every iteration and never converge.)
    // With all weights 1 the same path ends as one community {a, x, b}:
    // it 1: a -> a, x {x,a,b} -> a, b {b,x} -> b; it 2: b {b,a} -> a; it 3: no change.
    val path = nodes("a", "x", "b")
    val weighted = asMap(
      GraphAlgorithms.labelPropagation(path, edges(("a", "x", 1), ("x", "b", 3)), 8)._1,
      "id",
      "label"
    )
    assertEquals(weighted, Map[String, Any]("a" -> "a", "x" -> "b", "b" -> "b"))
    val flat = asMap(
      GraphAlgorithms.labelPropagation(path, edges(("a", "x", 1), ("x", "b", 1)), 8)._1,
      "id",
      "label"
    )
    assertEquals(flat.values.toSet, Set[Any]("a"))
  }

  test("k-core: coreness on G, a pendant vertex, K4, and the kcoreMaxK cap") {
    // {n1..n7} is a 2-core (n4 keeps neighbours n3 and n5, both of degree >= 2) and has no 3-core;
    // n8-n9 is a 1-core; the isolated n10 is 0.
    val maxK = graphConfig.kcoreMaxK
    val (core, stats) = GraphAlgorithms.coreness(ns, es, maxK, graphConfig.componentMaxIterations)
    assert(stats.converged)
    val k = asMap(core, "id", "coreness")
    assertEquals((1 to 7).toVector.map(i => k(s"n$i")), Vector.fill[Any](7)(2))
    assertEquals(Vector(k("n8"), k("n9"), k("n10")), Vector[Any](1, 1, 0))
    // K4 {k1..k4} plus pendant p on k1: K4 members 3 (k1's estimates [3,3,3,1] have h-index 3), p 1.
    val k4n = nodes("k1", "k2", "k3", "k4", "p")
    val k4e = edges(
      ("k1", "k2", 1),
      ("k1", "k3", 1),
      ("k1", "k4", 1),
      ("k2", "k3", 1),
      ("k2", "k4", 1),
      ("k3", "k4", 1),
      ("k1", "p", 1)
    )
    val full = asMap(GraphAlgorithms.coreness(k4n, k4e, maxK, 10)._1, "id", "coreness")
    assertEquals(full, Map[String, Any]("k1" -> 3, "k2" -> 3, "k3" -> 3, "k4" -> 3, "p" -> 1))
    // With the cap at 2 the K4 members report 2 = min(3, 2); p stays 1.
    val capped = asMap(GraphAlgorithms.coreness(k4n, k4e, 2, 10)._1, "id", "coreness")
    assertEquals(capped, Map[String, Any]("k1" -> 2, "k2" -> 2, "k3" -> 2, "k4" -> 2, "p" -> 1))
  }

  test("path queries: BFS levels, shortest path reconstruction, ties, depth bound, unreachable") {
    val src = nodes("n1", "n8").select(col("id").as("src"))
    val (levels, truncated) = GraphAlgorithms.multiSourceBfs(src, es, graphConfig.pathMaxDepth)
    assert(!truncated)
    val pairs = frame(
      StructType(Seq(StructField("src", StringType), StructField("tgt", StringType))),
      Seq(Row("n1", "n7"), Row("n8", "n7"))
    )
    val paths = GraphAlgorithms.reconstructPaths(levels, pairs, graphConfig.pathMaxDepth).collect()
    assertEquals(paths.length, 1, "n7 is unreachable from n8")
    val p = paths.head
    assertEquals(
      p.getSeq[String](p.fieldIndex("path")).toVector,
      Vector("n1", "n3", "n4", "n5", "n7")
    )
    assertEquals(p.getAs[Int]("dist"), 4)
    assertEqualsDouble(p.getAs[Double]("sigma"), 1.0, 1e-12)
    // Depth bound 3: n7 (4 hops) is not reached and the BFS reports the cut-off.
    val (short, cut) = GraphAlgorithms.multiSourceBfs(src, es, 3)
    assert(cut)
    assertEquals(GraphAlgorithms.reconstructPaths(short, pairs, 3).count(), 0L)
    // 4-cycle a-b-c-d-a: a to c has two shortest paths (via b, via d): sigma 2, the path through
    // the smaller predecessor b is returned.
    val sq = edges(("a", "b", 1), ("b", "c", 1), ("c", "d", 1), ("a", "d", 1))
    val (sl, _) = GraphAlgorithms.multiSourceBfs(nodes("a").select(col("id").as("src")), sq, 5)
    val ac = GraphAlgorithms
      .reconstructPaths(
        sl,
        frame(
          StructType(Seq(StructField("src", StringType), StructField("tgt", StringType))),
          Seq(Row("a", "c"))
        ),
        5
      )
      .head()
    assertEquals(
      (ac.getSeq[String](4).toVector, ac.getAs[Double]("sigma")),
      (Vector("a", "b", "c"), 2.0)
    )
  }

  test("bridge measure: exact betweenness, path multiplicity, sampled estimate and sampling") {
    // Exact (every node a source). n4 is on all 3 x 3 shortest paths between {n1,n2,n3} and
    // {n5,n6,n7}: 9 pairs. n3 is on the paths from {n1,n2} to {n4..n7}: 8; n5 symmetric: 8.
    // Normalized by (n-1)(n-2)/2 = 9 * 8 / 2 = 36 with n = 10.
    val (levels, _) = GraphAlgorithms.multiSourceBfs(ns.select(col("id").as("src")), es, 20)
    val bc = doubles(
      asMap(GraphAlgorithms.approximateBetweenness(levels, es, 10, 10, 10), "id", "betweenness")
    )
    assertEqualsDouble(bc("n4"), 9.0 / 36, 1e-12)
    assertEqualsDouble(bc("n3"), 8.0 / 36, 1e-12)
    assertEqualsDouble(bc("n5"), 8.0 / 36, 1e-12)
    for x <- Seq("n1", "n2", "n6", "n7", "n8", "n9", "n10") do
      assertEqualsDouble(bc.getOrElse(x, 0.0), 0.0, 1e-12)
    // 4-cycle: each node is on half of the two shortest paths of its opposite pair: raw 0.5,
    // normalized by 3 * 2 / 2 = 3.
    val sq = edges(("a", "b", 1), ("b", "c", 1), ("c", "d", 1), ("a", "d", 1))
    val (sl, _) =
      GraphAlgorithms.multiSourceBfs(nodes("a", "b", "c", "d").select(col("id").as("src")), sq, 5)
    val sbc =
      doubles(asMap(GraphAlgorithms.approximateBetweenness(sl, sq, 4, 4, 4), "id", "betweenness"))
    assert(
      Vector("a", "b", "c", "d").forall(x => math.abs(sbc(x) - 0.5 / 3.0) < 1e-12),
      sbc.toString
    )
    // Sampled estimate on the path a-b-c with the single source a (population 3): delta_a(b) = 1,
    // scaled by 3/1, halved: 1.5, normalized by (2 * 1) / 2 = 1, so 1.5; the exact value is 1.0.
    val line = edges(("a", "b", 1), ("b", "c", 1))
    val (ll, _) = GraphAlgorithms.multiSourceBfs(nodes("a").select(col("id").as("src")), line, 5)
    val est =
      doubles(asMap(GraphAlgorithms.approximateBetweenness(ll, line, 3, 3, 1), "id", "betweenness"))
    assertEqualsDouble(est("b"), 1.5, 1e-12)
    // Source sampling is deterministic for a seed and takes min(k, candidates).
    val connected = nodes((1 to 9).map(i => s"n$i")*)
    val seed = graphConfig.betweennessSeed
    val s1 =
      GraphAlgorithms.sampleSources(connected, 4, seed).collect().map(_.getString(0)).toVector
    val s2 =
      GraphAlgorithms.sampleSources(connected, 4, seed).collect().map(_.getString(0)).toVector
    assertEquals(s1, s2)
    assertEquals(s1.distinct.size, 4)
    assertEquals(
      GraphAlgorithms.sampleSources(connected, graphConfig.betweennessSampleSources, seed).count(),
      9L
    )
  }

  test(
    "bridge measures: cross-community degree, communities connected, participation coefficient"
  ) {
    // Communities A = {n1..n4}, B = {n5,n6,n7}, C = {n8,n9}, D = {n10}.
    // n4: neighbours n3 (A), n5 (B): cross 1, touches {A, B} = 2, P = 1 - (1/2)^2 - (1/2)^2 = 0.5.
    // n5: neighbours n4 (A), n6, n7 (B): cross 1, touches 2, P = 1 - (1/3)^2 - (2/3)^2 = 4/9.
    // n3: neighbours n1, n2, n4 all in A: cross 0, touches 1, P = 0. n10 (no neighbour): 0, 1, 0.
    val comm = frame(
      StructType(Seq(StructField("id", StringType), StructField("communityId", StringType))),
      (Seq("n1", "n2", "n3", "n4").map(_ -> "A") ++ Seq("n5", "n6", "n7").map(_ -> "B") ++
        Seq("n8" -> "C", "n9" -> "C", "n10" -> "D")).map((i, c) => Row(i, c))
    )
    val b = Bridges.measures(es, comm).collect().map(r => r.getString(0) -> r).toMap
    def v(id: String) =
      (b(id).getAs[Long]("crossCommunityDegree"), b(id).getAs[Long]("communitiesConnected"))
    assertEquals(v("n4"), (1L, 2L))
    assertEquals(v("n5"), (1L, 2L))
    assertEquals(v("n3"), (0L, 1L))
    assertEquals(v("n10"), (0L, 1L))
    assertEqualsDouble(b("n4").getAs[Double]("participationCoefficient"), 0.5, 1e-12)
    assertEqualsDouble(b("n5").getAs[Double]("participationCoefficient"), 4.0 / 9, 1e-12)
    assertEqualsDouble(b("n3").getAs[Double]("participationCoefficient"), 0.0, 1e-12)
    assertEqualsDouble(b("n10").getAs[Double]("participationCoefficient"), 0.0, 1e-12)
    assertEquals(b.size, 10)
  }
