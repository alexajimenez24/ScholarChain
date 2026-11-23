package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import scholarchain.core.TimeWindow

/** Job 9: temporal collaboration analysis.
  *
  * Every window of the graph config is computed in full by [[WindowAnalytics]] (edges, measures,
  * components, communities, bridges, coreness); this object compares the results of consecutive
  * windows. Inclusion rules: a window holds the canonical works whose year lies in it, and their
  * pairs; works without a year are in no window.
  *
  * Which windows are compared ([[plan]]): windows sorted by (startYear, endYear); each consecutive
  * pair is compared only if the two do not overlap. An overlapping pair (for example the cumulative
  * 1950-2026 window against the period 2000-2009) is never compared, because the earlier window
  * contains the later one's works; it is listed as skipped with that reason. Compared windows of
  * unequal length carry a limitation: counts are per window, not per year.
  *
  * Per researcher present in either window: before and after values of distinct coauthors (C_r),
  * average and maximum team sizes (A_r, M_r), component size and largest-component membership,
  * community and component retention, cross-community degree, communities connected, participation
  * coefficient and sampled betweenness. Community and component IDs are window-specific, so
  * membership change is measured by best-overlap matching (Greene, Doyle and Cunningham, 2010):
  * each earlier group is matched to the later group holding most of its members that are present in
  * both windows (ties by the smallest ID), and a researcher "retained" its group when its later
  * group is the match of its earlier one. Retention is null for a researcher absent from either
  * window.
  */
object Temporal:
  final case class Comparison(from: TimeWindow, to: TimeWindow, limitations: Vector[String])
  final case class Skipped(first: TimeWindow, second: TimeWindow, reason: String)

  val CommonLimitations: Vector[String] = Vector(
    "works without a publication year are in no window",
    "component and community IDs are window-specific; retention uses best-overlap matching",
    "betweenness is a sampled estimate in each window; with different node sets the sampled sources differ"
  )

  def plan(windows: Vector[TimeWindow]): (Vector[Comparison], Vector[Skipped]) =
    val sorted = windows.distinct.sortBy(w => (w.startYear, w.endYear))
    val pairs = sorted.zip(sorted.drop(1))
    val (ok, overlapping) = pairs.partition((a, b) => a.endYear < b.startYear)
    val comparisons = ok.map { (a, b) =>
      val la = a.endYear - a.startYear + 1
      val lb = b.endYear - b.startYear + 1
      val unequal =
        if la != lb then
          Vector(s"unequal window lengths ($la vs $lb years): counts are not normalized per year")
        else Vector.empty
      Comparison(a, b, unequal ++ CommonLimitations)
    }
    val skipped = overlapping.map((a, b) =>
      Skipped(a, b, "overlapping windows (one includes works of the other); not compared")
    )
    (comparisons, skipped)

  /** For each earlier group, the later group holding most of its members present in both windows.
    * `pairs(researcherId, before, after)`; result (before, matched).
    */
  private def bestMatch(pairs: DataFrame): DataFrame =
    val byOverlap = Window.partitionBy("before").orderBy(col("n").desc, col("after").asc)
    pairs
      .groupBy("before", "after")
      .agg(count(lit(1)).as("n"))
      .withColumn("r", row_number().over(byOverlap))
      .filter(col("r") === 1)
      .select(col("before"), col("after").as("matched"))

  private def retained(both: DataFrame, before: String, after: String): DataFrame =
    val pairs = both.select(col("researcherId"), col(before).as("before"), col(after).as("after"))
    pairs
      .join(bestMatch(pairs), "before")
      .select(col("researcherId"), (col("after") === col("matched")).as("retained"))

  private val Compared: Seq[String] = Seq(
    "distinctCoauthorCount",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "componentSize",
    "inLargestComponent",
    "crossCommunityDegree",
    "communitiesConnected",
    "participationCoefficient",
    "bridgeScore"
  )

  /** Per-researcher comparison of two window results (`metrics` of [[WindowAnalytics.Result]]). */
  def compare(before: DataFrame, after: DataFrame, c: Comparison): DataFrame =
    def side(m: DataFrame, suffix: String): DataFrame =
      m.select(
        (col("researcherId") +: (Compared ++ Seq("componentId", "communityId")).map(n =>
          col(n).as(n + suffix)
        ))*
      )
    val b = side(before, "Before")
    val a = side(after, "After")
    val both = b.join(a, "researcherId")
    val community = retained(both, "communityIdBefore", "communityIdAfter")
      .withColumnRenamed("retained", "communityRetained")
    val component = retained(both, "componentIdBefore", "componentIdAfter")
      .withColumnRenamed("retained", "componentRetained")
    val values: Seq[Column] = Compared.flatMap(n => Seq(col(n + "Before"), col(n + "After")))
    b.join(a, Seq("researcherId"), "full_outer")
      .join(community, Seq("researcherId"), "left")
      .join(component, Seq("researcherId"), "left")
      .select(
        (Seq(
          col("researcherId"),
          lit(c.from.label).as("fromWindow"),
          lit(c.to.label).as("toWindow"),
          col("componentIdBefore").isNotNull.as("presentBefore"),
          col("componentIdAfter").isNotNull.as("presentAfter")
        ) ++ values ++ Seq(col("componentRetained"), col("communityRetained")))*
      )
