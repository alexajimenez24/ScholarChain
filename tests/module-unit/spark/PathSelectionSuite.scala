package scholarchain.spark

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.*
import scholarchain.core.Ids
import LocalSpark.*

/** Path-evidence pair selection (logic spark-v4): targets come from each source's BFS reach within
  * `pathMaxDepth`, so every requested pair is reachable by construction.
  *
  * Known graphs, every edge one two-author work of 2020 (weight 1):
  *   - A: the chain a0 - a1 - ... - a7 (7 hops end to end) and the separate chain b0 - b1 - b2.
  *     Sources {a0, b0}. The earlier rule (pairs of sources) requests only a0-b0, which lie in
  *     different components: 0 found. With depth 6 and 3 targets per source the new rule requests
  *     a0-a6, a0-a5, a0-a4 (longest first, one per distance) and b0-b2, b0-b1 (b0's whole reach);
  *     a7 is 7 hops away and never requested.
  *   - S: the star c - l1..l5. Sources {c, l1}: c reaches l1..l5 at 1 hop; l1 reaches c at 1 hop
  *     and l2..l5 at 2. The pair c-l1 is requested once, from the smaller ID (c); l1's other
  *     candidates are l2..l5, ranked by SHA-256 of `src|tgt|seed`.
  *   - L: the chain p00 - p01 - ... - p47 (diameter 47). Hash-min components need 47 changing
  *     rounds and one quiet round: 48 iterations, above the earlier cap of 40 and within the
  *     configured componentMaxIterations. From the middle source p20 the reach within 6 hops is
  *     p14..p26, two nodes at each distance 1..6, so 7 targets are the distances 6, 5, 4, 3, 2, 1, 6.
  */
class PathSelectionSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private val run = runInfo()
  private val policy = graphConfig.policyVersion
  private val seed = graphConfig.betweennessSeed

  private def coauthorEdges(es: Seq[(String, String)]): DataFrame =
    val pairWorks = frame(
      StructType(
        Seq(
          StructField("a", StringType),
          StructField("b", StringType),
          StructField("workId", StringType),
          StructField("year", IntegerType)
        )
      ),
      es.map((x, y) =>
        val (a, b) = if x < y then (x, y) else (y, x)
        Row(a, b, s"w-$a-$b", 2020)
      )
    )
    CoauthorGraph.edges(pairWorks, All, run)

  private def chain(ids: Seq[String]): Seq[(String, String)] = ids.zip(ids.tail)

  private def sources(ids: String*): DataFrame = nodes(ids*).select(col("id").as("src"))

  private def pairs(ps: (String, String)*): DataFrame =
    frame(
      StructType(Seq(StructField("src", StringType), StructField("tgt", StringType))),
      ps.map((s, t) => Row(s, t))
    )

  private def byPair(df: DataFrame): Map[(String, String), Row] =
    df.collect().map(r => (r.getAs[String]("src"), r.getAs[String]("tgt")) -> r).toMap

  private def sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)).map("%02x".format(_)).mkString

  private val as = (0 to 7).map(i => s"a$i")
  private val bs = (0 to 2).map(i => s"b$i")
  private lazy val gA = coauthorEdges(chain(as) ++ chain(bs))

  test("budget: max(1, floor((k - 1) / 2)) targets per source, at most k(k-1)/2 pairs for k >= 3") {
    assertEquals((1 to 6).map(Paths.targetsPerSource), Vector(1, 1, 1, 1, 2, 2))
    assertEquals(Paths.targetsPerSource(16), 7)
    for k <- 1 to 40 do
      assert(k * Paths.targetsPerSource(k) <= math.max(k, k * (k - 1) / 2), s"k = $k")
  }

  test("graph A: pairs of sources find nothing; reachable targets give exact shortest paths") {
    val depth = graphConfig.pathMaxDepth
    assertEquals(depth, 6)
    // Earlier rule: the one pair of the two sources, in different components.
    val old = Paths.evidence(gA, pairs("a0" -> "b0"), depth, All)
    assertEquals(old.filter(col("found")).count(), 0L)
    // New rule.
    val ev = byPair(Paths.sampledEvidence(gA, sources("a0", "b0"), depth, 3, seed, All))
    assertEquals(
      ev.keySet,
      Set("a0" -> "a6", "a0" -> "a5", "a0" -> "a4", "b0" -> "b2", "b0" -> "b1")
    )
    ev.foreach { case ((s, t), r) =>
      val d = t.drop(1).toInt
      assertEquals(r.getAs[Boolean]("found"), true, s"$s-$t")
      assertEquals(r.getAs[Int]("distance"), d, s"$s-$t")
      assertEquals(r.getAs[Long]("shortestPathCount"), 1L)
      val path = (0 to d).map(i => s"${t.head}$i").toVector
      assertEquals(r.getSeq[String](r.fieldIndex("path")).toVector, path)
      assertEquals(
        r.getSeq[String](r.fieldIndex("hopEdgeIds")).toVector,
        path.zip(path.tail).map((x, y) => Ids.coauthorEdge(x, y, All, policy))
      )
    }
    // With a large budget a0 takes its whole reach a1..a6 and still never a7 (7 hops).
    val all = Paths.sampledEvidence(gA, sources("a0"), depth, 100, seed, All)
    assertEquals(
      all.select("tgt").collect().map(_.getString(0)).toSet,
      (1 to 6).map(i => s"a$i").toSet
    )
  }

  test("graph S: a pair of two sources once, hashed targets, the same pairs on a rerun") {
    val leaves = (1 to 5).map(i => s"l$i")
    val gS = coauthorEdges(leaves.map("c" -> _))
    def select() =
      Paths.sampledEvidence(gS, sources("c", "l1"), graphConfig.pathMaxDepth, 2, seed, All)
    val ev = byPair(select())
    def smallest(src: String, cands: Seq[String]) =
      cands.sortBy(t => (sha256(s"$src|$t|$seed"), t)).take(2).toSet
    assertEquals(ev.keySet.filter(_._1 == "c").map(_._2), smallest("c", leaves))
    assertEquals(ev.keySet.filter(_._1 == "l1").map(_._2), smallest("l1", leaves.tail))
    assert(!ev.contains("l1" -> "c"), "c-l1 is requested from c only")
    val unordered = ev.keys.toSeq.map((s, t) => Set(s, t))
    assertEquals(unordered.distinct.size, unordered.size)
    ev.foreach { case ((s, t), r) =>
      assertEquals(r.getAs[Int]("distance"), if s == "c" then 1 else 2, s"$s-$t")
    }
    assertEquals(byPair(select()).keySet, ev.keySet)
  }

  test("graph L: a 47-hop chain converges within componentMaxIterations; paths from its middle") {
    val ps = (0 to 47).map(i => f"p$i%02d")
    val gL = coauthorEdges(chain(ps))
    val (cc, stats) = GraphAlgorithms.connectedComponents(
      nodes(ps*),
      CoauthorGraph.graphEdges(gL),
      graphConfig.componentMaxIterations
    )
    assert(stats.converged, s"${stats.iterations} iterations")
    assertEquals(stats.iterations, 48)
    assert(stats.iterations > 40, "the earlier cap of 40 would have cut this chain")
    assertEquals(cc.select("label").distinct().collect().map(_.getString(0)).toSeq, Seq("p00"))
    val ev = byPair(
      Paths.sampledEvidence(
        gL,
        sources("p20"),
        graphConfig.pathMaxDepth,
        Paths.targetsPerSource(graphConfig.betweennessSampleSources),
        seed,
        All
      )
    )
    assertEquals(ev.size, 7)
    val dists = ev.map { case ((_, t), r) =>
      val d = math.abs(t.drop(1).toInt - 20)
      assertEquals(r.getAs[Int]("distance"), d, t)
      val path = r.getSeq[String](r.fieldIndex("path")).toVector
      assertEquals(path.size, d + 1)
      assertEquals((path.head, path.last), ("p20", t))
      assertEquals(
        r.getSeq[String](r.fieldIndex("hopEdgeIds")).toVector,
        path.zip(path.tail).map((x, y) => Ids.coauthorEdge(x, y, All, policy))
      )
      d
    }
    assertEquals(dists.toSeq.sorted, Seq(1, 2, 3, 4, 5, 6, 6))
  }
