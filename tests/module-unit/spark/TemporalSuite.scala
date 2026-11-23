package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*
import scholarchain.core.TimeWindow
import LocalSpark.*

/** Job 9 over the configured windows of scholarchain-v1.yaml.
  *
  * Fixture (canonical works):
  *   - 2012: {p, q, r} (one three-author work), inside window 2010-2014;
  *   - 2016: {p, q}; 2018: {r, s, t}, inside window 2015-2026.
  *
  * Hand-worked values, 2010-2014 -> 2015-2026:
  *   - before: one component and one community {p, q, r}; C = 2, A = M = 2 for each.
  *   - after: components {p, q} (size 2) and {r, s, t} (size 3, the largest); communities the same.
  *   - p: C 2 -> 1, M 2 -> 1, component 3 -> 2, in largest true -> false.
  *   - r: C 2 -> 2 (s, t), component 3 -> 3, in largest true -> true.
  *   - Retention: the earlier group {p, q, r} has members present in both windows p, q, r; 2 of
  *     them are in the later {p, q} group and 1 in {r, s, t}, so it matches {p, q}: p and q keep
  *     their component and community, r does not. s and t are absent before: retention is null.
  */
class TemporalSuite extends munit.FunSuite:
  // Several full Spark jobs per test on a 2-core local session: munit's 30 s default is too short.
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private val run = runInfo()
  private val teams = Seq(
    ("w12", 2012, Seq("p", "q", "r")),
    ("w16", 2016, Seq("p", "q")),
    ("w18", 2018, Seq("r", "s", "t"))
  )
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
    yield Row(x, z, w, y)
  )

  test("plan: configured windows; overlapping windows skipped, unequal lengths stated") {
    // Config windows 1950-2026, 2000-2009, 2010-2014, 2015-2026. Sorted consecutive pairs:
    // (1950-2026, 2000-2009) overlap -> skipped; (2000-2009, 2010-2014) 10 vs 5 years;
    // (2010-2014, 2015-2026) 5 vs 12 years.
    val (comparisons, skipped) = Temporal.plan(graphConfig.windows)
    assertEquals(
      comparisons.map(c => (c.from.label, c.to.label)),
      Vector("2000-2009" -> "2010-2014", "2010-2014" -> "2015-2026")
    )
    assertEquals(
      skipped.map(s => (s.first.label, s.second.label)),
      Vector("1950-2026" -> "2000-2009")
    )
    assert(
      comparisons(0).limitations.contains(
        "unequal window lengths (10 vs 5 years): counts are not normalized per year"
      )
    )
    assert(
      comparisons(1).limitations.contains(
        "unequal window lengths (5 vs 12 years): counts are not normalized per year"
      )
    )
    assert(
      comparisons.forall(
        _.limitations.contains("works without a publication year are in no window")
      )
    )
  }

  test("changes in distinct coauthors, team sizes, components, communities and bridge measures") {
    val (comparisons, _) = Temporal.plan(graphConfig.windows)
    val c = comparisons.find(_.from == TimeWindow(2010, 2014)).get
    val before = WindowAnalytics.run(authored, pairWorks, c.from, run)
    val after = WindowAnalytics.run(authored, pairWorks, c.to, run)
    assertEquals((before.report("components").num, after.report("components").num), (1.0, 2.0))
    val rows = Temporal
      .compare(before.metrics, after.metrics, c)
      .collect()
      .map(r => r.getAs[String]("researcherId") -> r)
      .toMap
    assertEquals(rows.keySet, Set("p", "q", "r", "s", "t"))
    def get[T](id: String, f: String): T = rows(id).getAs[T](f)
    assertEquals(
      (get[Long]("p", "distinctCoauthorCountBefore"), get[Long]("p", "distinctCoauthorCountAfter")),
      (2L, 1L)
    )
    assertEquals(
      (
        get[Long]("p", "maximumCoauthorsOnOnePaperBefore"),
        get[Long]("p", "maximumCoauthorsOnOnePaperAfter")
      ),
      (2L, 1L)
    )
    assertEquals(
      (get[Long]("p", "componentSizeBefore"), get[Long]("p", "componentSizeAfter")),
      (3L, 2L)
    )
    assertEquals(
      (get[Boolean]("p", "inLargestComponentBefore"), get[Boolean]("p", "inLargestComponentAfter")),
      (true, false)
    )
    assertEquals(
      (get[Long]("r", "distinctCoauthorCountBefore"), get[Long]("r", "distinctCoauthorCountAfter")),
      (2L, 2L)
    )
    assertEquals(
      (get[Boolean]("r", "inLargestComponentBefore"), get[Boolean]("r", "inLargestComponentAfter")),
      (true, true)
    )
    for id <- Seq("p", "q") do
      assertEquals(
        (get[Boolean](id, "componentRetained"), get[Boolean](id, "communityRetained")),
        (true, true),
        id
      )
    assertEquals(
      (get[Boolean]("r", "componentRetained"), get[Boolean]("r", "communityRetained")),
      (false, false)
    )
    val s = rows("s")
    assertEquals(
      (s.getAs[Boolean]("presentBefore"), s.getAs[Boolean]("presentAfter")),
      (false, true)
    )
    assert(
      s.isNullAt(s.fieldIndex("communityRetained")) && s.isNullAt(
        s.fieldIndex("distinctCoauthorCountBefore")
      )
    )
    // Bridge behaviour is compared too: no cross-community coauthor in either window here.
    assertEquals(
      (get[Long]("p", "crossCommunityDegreeBefore"), get[Long]("p", "crossCommunityDegreeAfter")),
      (0L, 0L)
    )
    assertEquals(rows("p").getAs[String]("fromWindow"), "2010-2014")
  }
