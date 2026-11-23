package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*
import scholarchain.core.{Ids, TimeWindow}
import LocalSpark.*

/** Jobs 3 to 8 together on graph G of [[GraphAlgorithmsSuite]] (n1..n10), written as canonical
  * AUTHORED rows: every edge of G is one two-author work (year 2020), and n10 has one solo work.
  *
  * Hand-worked values for window 1950-2026:
  *   - 10 researchers, 9 edges; components {n1..n7}, {n8,n9}, {n10}: largest 7, fraction 0.7, one
  *     singleton.
  *   - communities {n1..n4}, {n5,n6,n7}, {n8,n9}, {n10} (trace in GraphAlgorithmsSuite); one
  *     cross-community edge n4-n5.
  *   - 9 researchers have a coauthor and the config samples 16 sources, so all 9 are sources and
  *     betweenness is exact: n4 = 9/36 = 0.25, n3 = n5 = 8/36.
  *   - distances inside {n1..n7} over the 42 ordered pairs: 16 at 1, 10 at 2, 8 at 3, 8 at 4, sum
  *     92: average 92/42, 90th percentile 4 (34/42 = 0.81 of the values are <= 3), max 4.
  */
class WindowAnalyticsSuite extends munit.FunSuite:
  // Several full Spark jobs per test on a 2-core local session: munit's 30 s default is too short.
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private val run = runInfo()
  private val gEdges = Seq(
    "n1" -> "n2",
    "n1" -> "n3",
    "n2" -> "n3",
    "n3" -> "n4",
    "n4" -> "n5",
    "n5" -> "n6",
    "n5" -> "n7",
    "n6" -> "n7",
    "n8" -> "n9"
  )
  private val works = gEdges.map((a, b) => (s"w-$a-$b", Seq(a, b))) :+ ("w-n10" -> Seq("n10"))
  private val authored = frame(
    StructType(
      Seq(
        StructField("researcherId", StringType),
        StructField("workId", StringType),
        StructField("year", IntegerType),
        StructField("teamSize", IntegerType)
      )
    ),
    works.flatMap((w, rs) => rs.map(r => Row(r, w, 2020, rs.size)))
  )
  private val pairWorks = frame(
    StructType(
      Seq(
        StructField("a", StringType),
        StructField("b", StringType),
        StructField("workId", StringType),
        StructField("year", IntegerType)
      )
    ),
    gEdges.map((a, b) => Row(a, b, s"w-$a-$b", 2020))
  )
  private lazy val result = WindowAnalytics.run(authored, pairWorks, All, run)
  private def n(k: String): Long = result.report(k).num.toLong
  private def d(k: String): Double = result.report(k).num
  private lazy val metrics =
    result.metrics.collect().map(r => r.getAs[String]("researcherId") -> r).toMap

  test("components: IDs, sizes, largest-component fraction and singletons") {
    assertEquals((n("researchers"), n("edges")), (10L, 9L))
    assertEquals(
      (n("components"), n("largestComponentSize"), n("singletonResearchers")),
      (3L, 7L, 1L)
    )
    assertEqualsDouble(d("largestComponentFraction"), 0.7, 1e-12)
    val comp = metrics.view.mapValues(_.getAs[String]("componentId")).toMap
    assertEquals((1 to 7).map(i => comp(s"n$i")).toSet.size, 1)
    assertEquals(comp("n8"), comp("n9"))
    assertEquals(Set(comp("n1"), comp("n8"), comp("n10")).size, 3)
    assertEquals(metrics("n4").getAs[Long]("componentSize"), 7L)
    assertEquals(metrics("n4").getAs[Boolean]("inLargestComponent"), true)
    assertEquals(metrics("n8").getAs[Boolean]("inLargestComponent"), false)
  }

  test("communities: membership, sizes, internal and cross-community edges") {
    val cm = metrics.view.mapValues(_.getAs[String]("communityId")).toMap
    assertEquals((1 to 4).map(i => cm(s"n$i")).toSet.size, 1)
    assertEquals((5 to 7).map(i => cm(s"n$i")).toSet.size, 1)
    assertNotEquals(cm("n4"), cm("n5"))
    val c = result.communities.collect().map(r => r.getAs[String]("communityId") -> r).toMap
    def sizes(id: String) =
      (
        c(id).getAs[Long]("communitySize"),
        c(id).getAs[Long]("internalEdgeCount"),
        c(id).getAs[Long]("crossCommunityEdgeCount")
      )
    assertEquals(sizes(cm("n1")), (4L, 4L, 1L)) // n1n2, n1n3, n2n3, n3n4; n4-n5 leaves
    assertEquals(sizes(cm("n5")), (3L, 3L, 1L))
    assertEquals(sizes(cm("n8")), (2L, 1L, 0L))
    assertEquals(sizes(cm("n10")), (1L, 0L, 0L))
    assertEquals((n("communities"), n("crossCommunityEdges")), (4L, 1L))
    val cross = result.crossCommunityEdges.head()
    assertEquals(
      (cross.getAs[String]("researcherA"), cross.getAs[String]("researcherB")),
      ("n4", "n5")
    )
  }

  test("bridge, k-core and collaboration measures in the ResearcherMetrics rows") {
    assertEquals(n("betweennessSources"), 9L)
    val n4 = metrics("n4")
    assertEqualsDouble(n4.getAs[Double]("bridgeScore"), 0.25, 1e-12)
    assertEqualsDouble(metrics("n3").getAs[Double]("bridgeScore"), 8.0 / 36, 1e-12)
    assertEqualsDouble(metrics("n1").getAs[Double]("bridgeScore"), 0.0, 1e-12)
    assertEqualsDouble(n4.getAs[Double]("participationCoefficient"), 0.5, 1e-12)
    assertEquals(
      (n4.getAs[Long]("crossCommunityDegree"), n4.getAs[Long]("communitiesConnected")),
      (1L, 2L)
    )
    assertEquals(
      (
        n4.getAs[Int]("coreness"),
        metrics("n8").getAs[Int]("coreness"),
        metrics("n10").getAs[Int]("coreness")
      ),
      (2, 1, 0)
    )
    // n3: three two-author works with n1, n2, n4: P = C = T = W = 3, M = 1.
    val n3 = metrics("n3")
    assertEquals(
      (
        n3.getAs[Long]("publicationCount"),
        n3.getAs[Long]("distinctCoauthorCount"),
        n3.getAs[Long]("totalCoauthorOccurrences"),
        n3.getAs[Long]("maximumCoauthorsOnOnePaper")
      ),
      (3L, 3L, 3L, 1L)
    )
    val n10 = metrics("n10")
    assertEquals(
      (n10.getAs[Long]("distinctCoauthorCount"), n10.getAs[Double]("weightedDegree")),
      (0L, 0.0)
    )
    assertEquals(n10.getAs[String]("windowLabel"), "1950-2026")
    assertEquals(n10.getStruct(n10.fieldIndex("provenance")).getAs[String]("runId"), run.runId)
    // every invariant count is zero
    val inv = result.report.obj.collect {
      case (k, v) if k.startsWith("invariant_") => k -> v.num.toLong
    }
    assert(inv.size >= 14, inv.toString)
    assertEquals(inv.filter(_._2 != 0L).toMap, Map.empty[String, Long])
    assertEquals(
      (inv("invariant_componentsNotConverged"), inv("invariant_corenessNotConverged")),
      (0L, 0L)
    )
    assert(result.report("communitiesConverged").bool)
  }

  test("an iterative cut-off is an invariant failure (components, coreness); LPA stays a flag") {
    // The cut-off case of GraphAlgorithmsSuite: one hash-min iteration is below the diameter (4)
    // of {n1..n7}, so components do not converge. One h-index round is not the fixpoint either:
    // n3 starts at degree 3 and drops to h(2, 2, 2) = 2 in round 1, so coreness has not converged.
    val cut = runInfo(graphConfig.copy(componentMaxIterations = 1))
    val r = WindowAnalytics.run(authored, pairWorks, All, cut)
    val inv = SparkPipeline.invariants(r.report).toMap
    assertEquals(inv("invariant_componentsNotConverged"), 1L)
    assertEquals(inv("invariant_corenessNotConverged"), 1L)
    assert(!inv.contains("communitiesConverged"))
    assert(r.report("communitiesConverged").bool, "LPA has its own bound (communityIterations)")
  }

  test("path queries with hop evidence, depth bound, and sampled distance statistics") {
    val pairs = frame(
      StructType(Seq(StructField("src", StringType), StructField("tgt", StringType))),
      Seq(Row("n1", "n7"), Row("n8", "n7"), Row("n2", "n3"))
    )
    val ev = Paths
      .evidence(result.edges, pairs, graphConfig.pathMaxDepth, All)
      .collect()
      .map(r => (r.getAs[String]("src"), r.getAs[String]("tgt")) -> r)
      .toMap
    val p = ev("n1" -> "n7")
    assertEquals(p.getAs[Boolean]("found"), true)
    assertEquals(p.getAs[Int]("distance"), 4)
    assertEquals(
      p.getSeq[String](p.fieldIndex("path")).toVector,
      Vector("n1", "n3", "n4", "n5", "n7")
    )
    val policy = graphConfig.policyVersion
    assertEquals(
      p.getSeq[String](p.fieldIndex("hopEdgeIds")).toVector,
      Vector("n1" -> "n3", "n3" -> "n4", "n4" -> "n5", "n5" -> "n7").map((a, b) =>
        Ids.coauthorEdge(a, b, All, policy)
      )
    )
    assertEquals(ev("n2" -> "n3").getAs[Int]("distance"), 1)
    val none = ev("n8" -> "n7")
    assertEquals(
      (none.getAs[Boolean]("found"), none.isNullAt(none.fieldIndex("distance"))),
      (false, true)
    )
    // With a depth bound of 3 the 4-hop pair is reported as not found.
    val bounded =
      Paths.evidence(result.edges, pairs, 3, All).filter("src = 'n1' and tgt = 'n7'").head()
    assertEquals(bounded.getAs[Boolean]("found"), false)
    // Distance statistics of the sampled sources (all 9 connected researchers here).
    val ds = result.distanceStats.collect().map(r => r.getAs[Long]("sampledSources") -> r).toMap
    val big = ds(7L)
    assertEquals(big.getAs[Long]("sampledPairs"), 42L)
    assertEqualsDouble(big.getAs[Double]("averagePathLength"), 92.0 / 42, 1e-12)
    assertEquals(
      (big.getAs[Int]("effectiveDiameter"), big.getAs[Int]("maxObservedDistance")),
      (4, 4)
    )
    val pair = ds(2L)
    assertEqualsDouble(pair.getAs[Double]("averagePathLength"), 1.0, 1e-12)
    assertEquals(ds.size, 2, "the singleton component holds no sampled source")
    // Window level: 44 sampled pairs, average (92 + 2) / 44.
    assertEquals(n("sampledDistancePairs"), 44L)
    assertEqualsDouble(d("sampledAveragePathLength"), 94.0 / 44, 1e-12)
    assertEquals((n("sampledEffectiveDiameter"), n("sampledMaxDistance")), (4L, 4L))
  }
