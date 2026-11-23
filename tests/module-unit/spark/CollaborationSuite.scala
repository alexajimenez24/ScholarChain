package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*
import scholarchain.core.{Ids, TimeWindow}
import LocalSpark.*

/** Jobs 2 and 3 on canonical AUTHORED rows and researcher pair-work rows.
  *
  * Fixture (team size = authors on the work):
  *   - w1 2015 {r1, r2}; w2 2016 {r1, r2, r3}; w3 2022 {r1, t1..t11} (12 authors);
  *   - x1..x7, years 2010..2016, each {r9, s1};
  *   - w0 2018 {r0} (solo).
  *
  * Hand-worked values, window 1950-2026 (every work):
  *   - r1: P = 3; coauthors r2, r3, t1..t11, so C = 13; T = (2-1) + (3-1) + (12-1) = 14; A = 14/3;
  *     M = 11 on w3 only; W = 2 (r2: w1, w2) + 1 (r3) + 11 x 1 (t's) = 14.
  *   - r2: P = 2, C = 2 (r1, r3), T = 1 + 2 = 3, A = 1.5, M = 2 (w2), W = 2 + 1 = 3.
  *   - edge r1-r2: joint publications 2 (w1, w2), first 2015, last 2016.
  *   - r9: P = 7, C = 1, T = 7, M = 1 on all 7 works: maximumTeamWorkIds = the first 5 by ID
  *     (representativeWorkLimit 5 in scholarchain-v1.yaml), maximumTeamWorkCount = 7; W = 7.
  *   - edge r9-s1: joint 7, years 2010..2016; representative works = the 5 most recent: x7, x6, x5,
  *     x4, x3; supporting works = x1..x7.
  *   - r0: P = 1, C = 0, T = 0, A = 0, M = 0 (w0 is responsible), W = 0.
  */
class CollaborationSuite extends munit.FunSuite:
  private val run = runInfo()
  private val ts = (1 to 11).map(i => s"t$i")
  private val xs = (1 to 7).map(i => s"x$i")
  private val teams: Seq[(String, Int, Seq[String])] =
    Seq(
      ("w1", 2015, Seq("r1", "r2")),
      ("w2", 2016, Seq("r1", "r2", "r3")),
      ("w3", 2022, "r1" +: ts),
      ("w0", 2018, Seq("r0"))
    ) ++ xs.zipWithIndex.map((x, i) => (x, 2010 + i, Seq("r9", "s1")))

  private val authored = frame(
    StructType(
      Seq(
        StructField("researcherId", StringType),
        StructField("workId", StringType),
        StructField("year", IntegerType),
        StructField("teamSize", IntegerType)
      )
    ),
    teams.flatMap((w, y, rs) => rs.map(r => Row(r, w, y, rs.size)))
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
    for
      (w, y, rs) <- teams
      (x, i) <- rs.zipWithIndex
      z <- rs.drop(i + 1)
    yield Row(if x < z then x else z, if x < z then z else x, w, y)
  )
  private def edgesIn(w: TimeWindow) = CoauthorGraph.edges(pairWorks, w, run)
  private def metricsIn(w: TimeWindow) =
    CoauthorGraph
      .metrics(authored, edgesIn(w), w, graphConfig.representativeWorkLimit)
      .collect()
      .map(r => r.getAs[String]("researcherId") -> r)
      .toMap
  private def seq(r: Row, f: String) = r.getSeq[String](r.fieldIndex(f)).toVector

  test("pair weights: joint publication count, first and last year, supporting works, edge ID") {
    val es = edgesIn(All)
      .collect()
      .map(r => (r.getAs[String]("researcherA"), r.getAs[String]("researcherB")) -> r)
      .toMap
    val e = es("r1" -> "r2")
    assertEquals(e.getAs[Long]("jointPublicationCount"), 2L)
    assertEquals(
      (e.getAs[Int]("firstCollaborationYear"), e.getAs[Int]("lastCollaborationYear")),
      (2015, 2016)
    )
    assertEquals(seq(e, "supportingWorkIds"), Vector("w1", "w2"))
    assertEquals(seq(e, "representativeWorkIds"), Vector("w2", "w1"), "most recent first")
    assertEquals(
      e.getAs[String]("edgeId"),
      Ids.coauthorEdge("r1", "r2", All, graphConfig.policyVersion)
    )
    val x = es("r9" -> "s1")
    assertEquals(x.getAs[Long]("jointPublicationCount"), 7L)
    assertEquals(
      (x.getAs[Int]("firstCollaborationYear"), x.getAs[Int]("lastCollaborationYear")),
      (2010, 2016)
    )
    assertEquals(seq(x, "representativeWorkIds"), Vector("x7", "x6", "x5", "x4", "x3"))
    assertEquals(seq(x, "supportingWorkIds"), xs.toVector)
    // one row per logical undirected pair: 3 (w2 triangle) + 11 (r1 with t's) + 55 (t's) + 1 (r9-s1)
    assertEquals(es.size, 3 + 11 + 55 + 1)
    assert(es.keys.forall((a, b) => a < b))
  }

  test("P_r, C_r, T_r, A_r, M_r with responsible works, W_r over all years") {
    val m = metricsIn(All)
    val r1 = m("r1")
    assertEquals(
      (
        r1.getAs[Long]("publicationCount"),
        r1.getAs[Long]("distinctCoauthorCount"),
        r1.getAs[Long]("totalCoauthorOccurrences"),
        r1.getAs[Long]("maximumCoauthorsOnOnePaper")
      ),
      (3L, 13L, 14L, 11L)
    )
    assertEqualsDouble(r1.getAs[Double]("averageCoauthorsPerPaper"), 14.0 / 3, 1e-12)
    assertEquals(seq(r1, "maximumTeamWorkIds"), Vector("w3"))
    assertEqualsDouble(r1.getAs[Double]("weightedDegree"), 14.0, 1e-12)
    val r2 = m("r2")
    assertEquals(
      (r2.getAs[Long]("distinctCoauthorCount"), r2.getAs[Long]("totalCoauthorOccurrences")),
      (2L, 3L)
    )
    assertEqualsDouble(r2.getAs[Double]("weightedDegree"), 3.0, 1e-12)
    assertEqualsDouble(r2.getAs[Double]("averageCoauthorsPerPaper"), 1.5, 1e-12)
    val r9 = m("r9")
    assertEquals(seq(r9, "maximumTeamWorkIds"), Vector("x1", "x2", "x3", "x4", "x5"))
    assertEquals(r9.getAs[Long]("maximumTeamWorkCount"), 7L)
    assertEquals(
      (r9.getAs[Long]("distinctCoauthorCount"), r9.getAs[Double]("weightedDegree")),
      (1L, 7.0)
    )
    val r0 = m("r0")
    assertEquals(
      (
        r0.getAs[Long]("publicationCount"),
        r0.getAs[Long]("distinctCoauthorCount"),
        r0.getAs[Long]("maximumCoauthorsOnOnePaper")
      ),
      (1L, 0L, 0L)
    )
    assertEquals(seq(r0, "maximumTeamWorkIds"), Vector("w0"))
    // C_r <= W_r <= T_r for everyone
    assert(
      m.values.forall(r =>
        r.getAs[Long]("distinctCoauthorCount") <= r.getAs[Double]("weightedDegree") &&
          r.getAs[Double]("weightedDegree") <= r.getAs[Long]("totalCoauthorOccurrences")
      )
    )
  }

  test("explicit windows restrict edges and measures to the window's works") {
    // 2015-2016 holds w1 and w2 only: r1 P = 2, C = 2 (r2, r3), T = 1 + 2 = 3, M = 2 on w2, W = 3.
    val early = TimeWindow(2015, 2016)
    val r1 = metricsIn(early)("r1")
    assertEquals(
      (
        r1.getAs[Long]("publicationCount"),
        r1.getAs[Long]("distinctCoauthorCount"),
        r1.getAs[Long]("totalCoauthorOccurrences"),
        r1.getAs[Long]("maximumCoauthorsOnOnePaper")
      ),
      (2L, 2L, 3L, 2L)
    )
    assertEquals(seq(r1, "maximumTeamWorkIds"), Vector("w2"))
    // 2013-2014 cuts the r9-s1 edge to x4 and x5: joint 2, first 2013, last 2014.
    val mid = TimeWindow(2013, 2014)
    val x = edgesIn(mid).collect().head
    assertEquals(
      (
        x.getAs[Long]("jointPublicationCount"),
        x.getAs[Int]("firstCollaborationYear"),
        x.getAs[Int]("lastCollaborationYear")
      ),
      (2L, 2013, 2014)
    )
    assertEquals(
      x.getAs[String]("edgeId"),
      Ids.coauthorEdge("r9", "s1", mid, graphConfig.policyVersion),
      "the window is part of the edge ID"
    )
    // 2017-2026 holds w3 and w0: r1 P = 1, C = 11, M = 11; r2 has no work there, so no row.
    val late = metricsIn(TimeWindow(2017, 2026))
    assertEquals(
      (late("r1").getAs[Long]("publicationCount"), late("r1").getAs[Long]("distinctCoauthorCount")),
      (1L, 11L)
    )
    assert(!late.contains("r2"))
    assertEquals(late.size, 1 + 11 + 1) // r1, t1..t11, r0
  }
