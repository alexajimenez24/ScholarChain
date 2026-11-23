package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*
import scholarchain.core.{SchemaVersions, TimeWindow}

/** Jobs 2 and 3: the temporal weighted coauthorship graph and the per-researcher collaboration
  * measures of one window.
  */
object CoauthorGraph:
  final case class PairResult(pairWorks: DataFrame, rejects: DataFrame, report: ujson.Obj)

  /** Maps the Hadoop mention-level pair candidates onto canonical researchers and canonical works.
    *
    *   - Both mentions are mapped through the identity table; a pair whose mention has no
    *     researcher or whose work is not in the export is rejected (`PAIR_MENTION_UNKNOWN`,
    *     `PAIR_WORK_UNKNOWN`).
    *   - A pair whose two mentions resolve to one researcher (a self pair created by identity
    *     resolution) is dropped and counted (`selfPairsDropped`).
    *   - The pair is put in canonical order: a < b (`String` order, as core `Ids.orderedPair`).
    *   - A pair whose researcher has no AUTHORED row on the canonical work is rejected
    *     (`PAIR_WITHOUT_AUTHORSHIP`), so the graph and the team-size measures use one population.
    *   - A canonical work with more than `maxTeamSizeForPairs` distinct researchers contributes no
    *     pairs (`pairsRefusedByTeamCap`), the same cap Hadoop applies per source record.
    *   - Duplicates are aggregated: one row per (a, b, canonical work), whatever number of source
    *     records (DBLP, Crossref, OpenAlex copies) reported the pair (`sourcePairRows` keeps it).
    *
    * Result `pairWorks(a, b, workId, year, sourcePairRows)` is the stable pointer from an edge to
    * all its supporting works.
    */
  def researcherPairs(
      pairs: DataFrame,
      mentionToResearcher: DataFrame,
      workMap: DataFrame,
      authored: DataFrame,
      maxTeamSizeForPairs: Int
  ): PairResult =
    val report = ujson.Obj()
    val m2r = mentionToResearcher.select("mentionId", "researcherId")
    val mapped = pairs
      .select(col("mentionA"), col("mentionB"), col("workId").as("memberWorkId"))
      .join(m2r.toDF("mentionA", "ra"), Seq("mentionA"), "left")
      .join(m2r.toDF("mentionB", "rb"), Seq("mentionB"), "left")
      .join(workMap.select("memberWorkId", "workId", "year"), Seq("memberWorkId"), "left")
      .persist()
    val unknown = col("ra").isNull || col("rb").isNull || col("workId").isNull
    val rejectedUnmapped = mapped
      .filter(unknown)
      .select(
        to_json(struct("mentionA", "mentionB", "memberWorkId")).as("input"),
        when(col("workId").isNull, lit("PAIR_WORK_UNKNOWN"))
          .otherwise(lit("PAIR_MENTION_UNKNOWN"))
          .as("reason")
      )
    val known = mapped.filter(!unknown)
    val self = known.filter(col("ra") === col("rb"))
    val ordered = known
      .filter(col("ra") =!= col("rb"))
      .select(
        col("mentionA"),
        col("mentionB"),
        col("memberWorkId"),
        least(col("ra"), col("rb")).as("a"),
        greatest(col("ra"), col("rb")).as("b"),
        col("workId"),
        col("year")
      )
    val onWork = authored.select("researcherId", "workId", "teamSize")
    val supported = ordered
      .join(onWork.toDF("a", "workId", "teamSize"), Seq("a", "workId"), "left")
      .join(onWork.toDF("b", "workId", "teamSizeB"), Seq("b", "workId"), "left")
    val unsupported = col("teamSize").isNull || col("teamSizeB").isNull
    val rejectedUnsupported = supported
      .filter(unsupported)
      .select(
        to_json(struct("mentionA", "mentionB", "memberWorkId")).as("input"),
        lit("PAIR_WITHOUT_AUTHORSHIP").as("reason")
      )
    val withinCap = supported.filter(!unsupported)
    val refused = withinCap.filter(col("teamSize") > maxTeamSizeForPairs)
    val pairWorks = Lineage.cut(
      withinCap
        .filter(col("teamSize") <= maxTeamSizeForPairs)
        .groupBy("a", "b", "workId")
        .agg(min("year").as("year"), count(lit(1)).as("sourcePairRows"))
    )
    val rejects = rejectedUnmapped.unionByName(rejectedUnsupported)
    Report.put(report, "pairCandidateRows", pairs.count())
    Report.put(report, "selfPairsDropped", self.count())
    Report.put(report, "pairsRefusedByTeamCap", refused.count())
    Report.put(
      report,
      "pairRejects_PAIR_MENTION_UNKNOWN",
      rejectedUnmapped.filter(col("reason") === "PAIR_MENTION_UNKNOWN").count()
    )
    Report.put(
      report,
      "pairRejects_PAIR_WORK_UNKNOWN",
      rejectedUnmapped.filter(col("reason") === "PAIR_WORK_UNKNOWN").count()
    )
    Report.put(report, "pairRejects_PAIR_WITHOUT_AUTHORSHIP", rejectedUnsupported.count())
    Report.put(report, "researcherPairWorks", pairWorks.count())
    Report.put(
      report,
      "invariant_pairWorksUnordered",
      pairWorks.filter(col("a") >= col("b")).count()
    )
    mapped.unpersist()
    PairResult(pairWorks, rejects, report)

  /** COAUTHORED_WITH edges of one window, in the core `CoauthorEdge` shape plus `supportingWorkIds`
    * (every supporting canonical work, sorted) and `windowLabel`.
    *
    *   - `jointPublicationCount`: distinct canonical works of the pair inside the window.
    *   - `firstCollaborationYear` / `lastCollaborationYear`: inside the window.
    *   - `representativeWorkIds`: the `representativeWorkLimit` most recent supporting works (ties
    *     by work ID); the full list is `supportingWorkIds`, and `pairWorks` filtered on (a, b) is
    *     the stable pointer to the rows.
    *   - `edgeId`: `e:co:` + h(`a|b|<startYear-endYear>|<policyVersion>`, 24), the core
    *     `Ids.coauthorEdge` formula.
    *
    * Shuffle: one aggregation hash-partitioned on (a, b).
    */
  def edges(pairWorks: DataFrame, w: TimeWindow, run: RunInfo): DataFrame =
    val g = run.graph
    pairWorks
      .filter(Cols.inWindow(col("year"), w))
      .groupBy("a", "b")
      .agg(
        count(lit(1)).as("jointPublicationCount"),
        min("year").as("firstCollaborationYear"),
        max("year").as("lastCollaborationYear"),
        array_sort(collect_list("workId")).as("supportingWorkIds"),
        array_sort(collect_list(struct((-col("year")).as("k"), col("workId")))).as("recent")
      )
      .withColumn(
        "edgeId",
        concat(
          lit("e:co:"),
          Cols.shortHash(concat_ws("|", col("a"), col("b"), lit(w.label), lit(g.policyVersion)), 24)
        )
      )
      .select(
        lit(SchemaVersions.Edge).as("schemaVersion"),
        col("edgeId"),
        col("a").as("researcherA"),
        col("b").as("researcherB"),
        col("jointPublicationCount"),
        col("firstCollaborationYear"),
        col("lastCollaborationYear"),
        transform(slice(col("recent"), 1, g.representativeWorkLimit), x => x.getField("workId"))
          .as("representativeWorkIds"),
        col("supportingWorkIds"),
        Cols.windowStruct(w).as("window"),
        lit(w.label).as("windowLabel"),
        lit(g.policyVersion).as("policyVersion"),
        Cols
          .provenance(
            run,
            lit("SCHOLARCHAIN"),
            col("edgeId"),
            Some(SchemaVersions.Edge),
            g.policyVersion
          )
          .as("provenance")
      )

  /** The (a, b, w) view of window edges used by [[GraphAlgorithms]]; w = joint publications. */
  def graphEdges(edges: DataFrame): DataFrame =
    edges.select(
      col("researcherA").as("a"),
      col("researcherB").as("b"),
      col("jointPublicationCount").as("w")
    )

  /** Per-researcher measures of one window, every component kept as its own column:
    *
    *   - P_r `publicationCount`: canonical works authored in the window.
    *   - C_r `distinctCoauthorCount`: distinct coauthors over the window's edges (the degree).
    *   - T_r `totalCoauthorOccurrences`: sum over the researcher's works of (teamSize - 1); a work
    *     above `maxTeamSizeForPairs` counts here although it adds no edge.
    *   - A_r `averageCoauthorsPerPaper`: T_r / P_r.
    *   - M_r `maximumCoauthorsOnOnePaper`: max over the works of (teamSize - 1).
    *   - `maximumTeamWorkIds`: the works responsible for M_r, the first `representativeWorkLimit`
    *     by work ID; `maximumTeamWorkCount` says how many there are in total.
    *   - W_r `weightedDegree`: sum of the joint publication counts of the researcher's edges.
    *
    * Invariants: C_r <= W_r <= T_r and M_r <= T_r (checked by [[WindowAnalytics]]). Researchers
    * with at least one work in the window are the window's nodes; a researcher with no coauthor has
    * C_r = W_r = 0. Works without a year are in no window.
    */
  def metrics(authored: DataFrame, edges: DataFrame, w: TimeWindow, limit: Int): DataFrame =
    val aw = authored
      .filter(Cols.inWindow(col("year"), w))
      .select("researcherId", "workId", "teamSize")
    val base = aw
      .groupBy("researcherId")
      .agg(
        count(lit(1)).as("publicationCount"),
        sum(col("teamSize") - 1).cast("long").as("totalCoauthorOccurrences"),
        max(col("teamSize") - 1).cast("long").as("maximumCoauthorsOnOnePaper")
      )
    val maxWorks = aw
      .join(base.select("researcherId", "maximumCoauthorsOnOnePaper"), "researcherId")
      .filter(col("teamSize") - 1 === col("maximumCoauthorsOnOnePaper"))
      .groupBy("researcherId")
      .agg(
        slice(array_sort(collect_list("workId")), 1, limit).as("maximumTeamWorkIds"),
        count(lit(1)).as("maximumTeamWorkCount")
      )
    val degree = GraphAlgorithms
      .adjacency(graphEdges(edges))
      .groupBy(col("src").as("researcherId"))
      .agg(
        count(lit(1)).as("distinctCoauthorCount"),
        sum("w").cast("double").as("weightedDegree")
      )
    base
      .join(maxWorks, "researcherId")
      .join(degree, Seq("researcherId"), "left")
      .select(
        col("researcherId"),
        col("publicationCount"),
        coalesce(col("distinctCoauthorCount"), lit(0L)).as("distinctCoauthorCount"),
        col("totalCoauthorOccurrences"),
        (col("totalCoauthorOccurrences") / col("publicationCount")).as("averageCoauthorsPerPaper"),
        col("maximumCoauthorsOnOnePaper"),
        col("maximumTeamWorkIds"),
        col("maximumTeamWorkCount"),
        coalesce(col("weightedDegree"), lit(0.0)).as("weightedDegree")
      )
