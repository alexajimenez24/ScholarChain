package scholarchain.spark

import scala.collection.mutable

import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.api.java.UDF2
import org.apache.spark.sql.expressions.{UserDefinedFunction, Window}
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{ArrayType, MapType, StringType, StructField, StructType}
import scholarchain.core.SchemaVersions

/** Parsed, valid rows of the Phase 04 J07 export and the Phase 05 er-v1 decisions. */
final case class Sources(
    works: DataFrame,
    mentions: DataFrame,
    authorPapers: DataFrame,
    pairs: DataFrame,
    topics: DataFrame,
    institutions: DataFrame,
    venues: DataFrame,
    decisions: DataFrame
)

/** Job 1: canonical Researcher, Work, AUTHORED, Topic, Institution, Venue (and researcher
  * affiliation) tables.
  *
  * Identity policy (only er-v1 decisions are applied; nothing is matched here):
  *   - Researchers. ACCEPTED RESEARCHER decisions are edges of an identity graph over mention IDs
  *     and external IDs (`orcid:...`); each cluster holding at least one mention is one researcher.
  *     A mention with no accepted link stays its own researcher. UNRESOLVED, CONFLICT and REJECTED
  *     decisions never link anything, and they are never merged through other links either: the
  *     contradiction cut ([[constrainedClusters]]) keeps the two ends of every such decision, and
  *     any two ORCID iDs, in different researchers.
  *   - Contradiction cut (constrained single linkage, deterministic). Clusters are first the
  *     connected components of the accepted links. A cluster is contradicted when it holds both
  *     ends of an UNRESOLVED, CONFLICT or REJECTED decision, or two ORCID iDs. Every contradicted
  *     cluster is rebuilt from its own accepted links in descending confidence, ties by the link's
  *     ordered endpoint IDs (a, then b): a link is applied unless it would put the two ends of a
  *     contradicting decision, or two ORCID iDs, into one cluster; then it is refused. When one
  *     path of accepted links joins a contradicting pair, exactly the weakest link of that path is
  *     refused; links that do not lie between contradicting nodes are kept. Uncontradicted clusters
  *     are unchanged. Cost and limits: the components are bounded by `componentMaxIterations`
  *     (non-convergence is the invariant `invariant_<kind>ClusterNotConverged`); the rebuild is one
  *     pass per contradicted cluster, run on the executor that holds the cluster's links (one
  *     shuffle by cluster label), never on the driver, with memory linear in that cluster's links.
  *     A contradicted cluster above `graph.identityCutMaxClusterLinks` accepted links (inclusive
  *     bound) is not rebuilt: all its accepted links are refused and its members stay apart,
  *     counted as `<kind>ClustersOverCutBound` and `<kind>DecisionLinksRefusedOverCutBound`.
  *   - Flags: `unresolved` when an UNRESOLVED decision touches a member; `identityConflict` when
  *     the cut refused an accepted link touching a member (the accepted links alone would have
  *     merged this researcher with a node a contradicting decision or a second ORCID iD keeps
  *     apart).
  *   - Researcher ID: `orcid:<iD>` when exactly one ORCID is in the cluster, else `sc:r:v1:` +
  *     h(sorted mention IDs joined by '|', 20), the core `Ids.researcherFromMentions` formula.
  *   - Works. ACCEPTED WORK decisions cluster the per-source work records (DBLP, Crossref, OpenAlex
  *     copies of one paper) the same way, with the same cut for UNRESOLVED, CONFLICT and REJECTED
  *     WORK decisions. The canonical work ID is the ID of the representative member: the DBLP
  *     record if any, else Crossref, else OpenAlex, else any other source, ties by the smallest
  *     work ID. Without this merge one paper exported by three sources would count as three
  *     publications and triple every joint publication count.
  *   - A decision endpoint that names a mention or work absent from the export is not applied and
  *     is counted (`decisionLinksDroppedUnknownEndpoint`).
  *   - Provenance of researcher and work rows names the identity policy that made them (the er-v1
  *     policy version of the decisions), not the graph policy.
  *
  * AUTHORED is one row per (researcher, canonical work), built from the Hadoop author-paper rows.
  * An author-paper row whose mention has no researcher or whose work is not in the export is
  * rejected with a reason and counted. `teamSize` of a canonical work is its number of distinct
  * researchers, so unmerged mentions of one person in two sources (UNRESOLVED) count twice; the run
  * report counts works where that makes the team larger than any source's own author list.
  * `position` is the canonical position: researchers ordered by their position on the
  * representative source record (then on any other), so 0 <= position < teamSize.
  */
object Canonical:
  final case class Result(
      researchers: DataFrame,
      mentionToResearcher: DataFrame,
      works: DataFrame,
      workMap: DataFrame,
      authored: DataFrame,
      authoredRejects: DataFrame,
      topics: DataFrame,
      institutions: DataFrame,
      venues: DataFrame,
      affiliations: DataFrame,
      report: ujson.Obj
  )

  /** Source rank of the representative work of a cluster (smaller wins). */
  def sourceRank(source: Column): Column =
    when(source === "DBLP", 0)
      .when(source === "CROSSREF", 1)
      .when(source === "OPENALEX", 2)
      .otherwise(3)

  /** Decision outcomes whose two ends must never share a cluster. */
  val ContradictingOutcomes: Seq[String] = Seq("UNRESOLVED", "CONFLICT", "REJECTED")

  /** Result of [[constrainedClusters]]: `labels(id, label)` (label = the smallest node ID of the
    * cluster), `applied(a, b, confidence)` the accepted links actually applied, and `cut(id)` the
    * nodes touched by a link the contradiction cut refused.
    */
  final case class Clusters(labels: DataFrame, applied: DataFrame, cut: DataFrame)

  /** Identity clustering with the contradiction cut, shared by researchers and works.
    *
    * `nodes(id)`; `accepted(a, b, confidence)` with a < b, one row per pair; `contradictions(x, y,
    * outcome)` with x < y; `exclusive` = (ID prefix, report name) of IDs no cluster may hold two of
    * (ORCID iDs for researchers). See the policy text of [[Canonical]] for the rule. Report keys
    * (`p` = `prefix`): `pDecisionLinksAccepted`, `...DroppedUnknownEndpoint`, `...RefusedByCut`,
    * `...Applied`; `pClustersCut`; per outcome O `pContradictions_O`,
    * `pContradictionsInOneClusterBeforeCut_O`, `pContradictionsKeptApart_O` and the invariant
    * `invariant_pContradictionsMerged_O`; for `exclusive` `pClustersWithSeveral<name>BeforeCut` and
    * `invariant_pClustersWithSeveral<name>`; `pClusterIterations` and
    * `invariant_pClusterNotConverged` (0 or 1).
    *
    * Cluster-size bound (`maxClusterLinks` = core `graph.identityCutMaxClusterLinks`, inclusive):
    * the accepted links of every contradicted cluster are counted first (an aggregate, nothing
    * collected). A contradicted cluster with at most `maxClusterLinks` links is cut as above. One
    * with more is never collected into a row: all its accepted links are refused and each member
    * stays its own cluster (under-merge preferred to over-merge; the job does not fail). Its
    * members are therefore flagged by `cut`. Uncontradicted clusters are not limited. Report keys
    * `pCutMaxClusterLinks` (the bound), `pLargestContradictedClusterLinks`, `pClustersOverCutBound`
    * and `pDecisionLinksRefusedOverCutBound` (included in `pDecisionLinksRefusedByCut`).
    */
  def constrainedClusters(
      nodes: DataFrame,
      accepted: DataFrame,
      contradictions: DataFrame,
      exclusive: Option[(String, String)],
      maxIterations: Int,
      maxClusterLinks: Int,
      report: ujson.Obj,
      prefix: String
  ): Clusters =
    val known = nodes.select(col("id"))
    val linked = accepted
      .join(known.withColumnRenamed("id", "a"), Seq("a"), "left_semi")
      .join(known.withColumnRenamed("id", "b"), Seq("b"), "left_semi")
      .persist()
    val nAccepted = accepted.count()
    val nLinked = linked.count()
    Report.put(report, s"${prefix}DecisionLinksAccepted", nAccepted)
    Report.put(report, s"${prefix}DecisionLinksDroppedUnknownEndpoint", nAccepted - nLinked)
    val (l0, stats) = GraphAlgorithms.connectedComponents(
      known,
      linked.select(col("a"), col("b"), lit(1L).as("w")),
      maxIterations
    )
    Report.put(report, s"${prefix}ClusterIterations", stats.iterations.toLong)
    Report.put(
      report,
      s"invariant_${prefix}ClusterNotConverged",
      if stats.converged then 0L else 1L
    )

    def ends(df: DataFrame, labels: DataFrame, lx: String, ly: String): DataFrame =
      df.join(labels.select(col("id").as("x"), col("label").as(lx)), Seq("x"), "left")
        .join(labels.select(col("id").as("y"), col("label").as(ly)), Seq("y"), "left")
    val contra = contradictions.select("x", "y", "outcome")
    val inOne = ends(contra, l0, "lx", "ly").filter(col("lx") === col("ly"))
    def severalExclusive(labels: DataFrame): DataFrame = exclusive match
      case Some((p, _)) =>
        labels
          .filter(col("id").startsWith(p))
          .groupBy("label")
          .agg(count(lit(1)).as("k"))
          .filter(col("k") > 1)
          .select("label")
      case None => labels.select("label").limit(0)
    val contradicted = Lineage.cut(
      inOne.select(col("lx").as("label")).unionByName(severalExclusive(l0)).distinct()
    )
    Report.put(report, s"${prefix}ClustersCut", contradicted.count())

    // Cluster-size bound (C12): links are counted per contradicted cluster before anything is
    // collected. Both ends of a link share its component, so the label of `a` is the cluster's.
    val clusterLinks = linked
      .join(l0.select(col("id").as("a"), col("label")), "a")
      .join(contradicted, Seq("label"), "left_semi")
    val linkCounts = Lineage.cut(clusterLinks.groupBy("label").agg(count(lit(1)).as("nLinks")))
    val overBound = Lineage.cut(
      linkCounts.filter(col("nLinks") > lit(maxClusterLinks.toLong)).select("label", "nLinks")
    )
    val boundStats = overBound
      .agg(count(lit(1)), coalesce(sum("nLinks"), lit(0L)))
      .head() // one row
    val largest = linkCounts.agg(coalesce(max("nLinks"), lit(0L))).head().getLong(0) // one row
    Report.put(report, s"${prefix}CutMaxClusterLinks", maxClusterLinks.toLong)
    Report.put(report, s"${prefix}LargestContradictedClusterLinks", largest)
    Report.put(report, s"${prefix}ClustersOverCutBound", boundStats.getLong(0))
    Report.put(report, s"${prefix}DecisionLinksRefusedOverCutBound", boundStats.getLong(1))
    val overLabels = overBound.select("label")

    // One row per contradicted cluster within the bound: its accepted links and its in-cluster
    // contradictions. The groupBy is the one shuffle of the cut; the rebuild runs on the executor
    // holding the row. Over-bound clusters never reach the collect.
    val groupLinks = clusterLinks
      .join(overLabels, Seq("label"), "left_anti")
      .groupBy("label")
      .agg(collect_list(struct(col("a"), col("b"), col("confidence"))).as("links"))
    val groupContra = inOne
      .join(overLabels.withColumnRenamed("label", "lx"), Seq("lx"), "left_anti")
      .groupBy(col("lx").as("label"))
      .agg(collect_list(struct(col("x"), col("y"))).as("contra"))
    val rebuilt = Lineage.cut(
      groupLinks
        .join(groupContra, Seq("label"), "left")
        .select(IdentityCut.udfFor(exclusive.map(_._1))(col("links"), col("contra")).as("r"))
    )
    // Over-bound clusters: every accepted link is refused and every member stays its own cluster
    // (under-merge, never over-merge), so no contradiction or second ORCID iD can share a cluster.
    val assigned = rebuilt
      .select(explode(col("r.assign")).as("x"))
      .select(col("x.id").as("id"), col("x.label").as("sub"))
      .unionByName(
        l0.join(overLabels, Seq("label"), "left_semi").select(col("id"), col("id").as("sub"))
      )
    val refused = rebuilt
      .select(explode(col("r.refused")).as("x"))
      .select(col("x.a").as("a"), col("x.b").as("b"))
      .unionByName(clusterLinks.join(overLabels, Seq("label"), "left_semi").select("a", "b"))
    val labels = Lineage.cut(
      l0.join(assigned, Seq("id"), "left")
        .select(col("id"), coalesce(col("sub"), col("label")).as("label"))
    )
    val applied = Lineage.cut(linked.join(refused, Seq("a", "b"), "left_anti"))
    linked.unpersist()
    val nApplied = applied.count()
    Report.put(report, s"${prefix}DecisionLinksRefusedByCut", nLinked - nApplied)
    Report.put(report, s"${prefix}DecisionLinksApplied", nApplied)

    // Outcome counters: at most 3 rows reach the driver.
    val judged = ends(ends(contra, l0, "bx", "by"), labels, "ax", "ay")
      .groupBy("outcome")
      .agg(
        count(lit(1)).as("n"),
        sum(when(col("bx") === col("by"), 1L).otherwise(0L)).as("before"),
        sum(when(col("ax") === col("ay"), 1L).otherwise(0L)).as("after")
      )
      .collect()
      .map(r => r.getString(0) -> (r.getLong(1), r.getLong(2), r.getLong(3)))
      .toMap
    ContradictingOutcomes.foreach { o =>
      val (n, before, after) = judged.getOrElse(o, (0L, 0L, 0L))
      Report.put(report, s"${prefix}Contradictions_$o", n)
      Report.put(report, s"${prefix}ContradictionsInOneClusterBeforeCut_$o", before)
      Report.put(report, s"${prefix}ContradictionsKeptApart_$o", n - after)
      Report.put(report, s"invariant_${prefix}ContradictionsMerged_$o", after)
    }
    exclusive.foreach { (_, name) =>
      Report.put(
        report,
        s"${prefix}ClustersWithSeveral${name}BeforeCut",
        severalExclusive(l0).count()
      )
      Report.put(
        report,
        s"invariant_${prefix}ClustersWithSeveral$name",
        severalExclusive(labels).count()
      )
    }
    val cut =
      refused.select(col("a").as("id")).unionByName(refused.select(col("b").as("id"))).distinct()
    Clusters(labels, applied, cut)

  /** Accepted decisions of one kind as canonically ordered links (a < b), duplicates merged. */
  private def acceptedLinks(decisions: DataFrame, kind: String): DataFrame =
    decisions
      .filter(col("kind") === kind && col("decision") === "ACCEPTED")
      .select(
        least(col("sourceId"), col("targetId")).as("a"),
        greatest(col("sourceId"), col("targetId")).as("b"),
        col("confidence")
      )
      .filter(col("a") =!= col("b"))
      .groupBy("a", "b")
      .agg(min("confidence").as("confidence"))

  /** Contradicting decisions of one kind as ordered pairs (x < y) with their outcome. */
  private def contradictionPairs(decisions: DataFrame, kind: String): DataFrame =
    decisions
      .filter(col("kind") === kind && col("decision").isin(ContradictingOutcomes*))
      .select(
        least(col("sourceId"), col("targetId")).as("x"),
        greatest(col("sourceId"), col("targetId")).as("y"),
        col("decision").as("outcome")
      )
      .filter(col("x") =!= col("y"))
      .distinct()

  /** Labels with a member touched by a decision of one of `outcomes`. */
  private def touched(
      decisions: DataFrame,
      kind: String,
      outcomes: Seq[String],
      labels: DataFrame
  ): DataFrame =
    val ids = decisions
      .filter(col("kind") === kind && col("decision").isin(outcomes*))
      .select(col("sourceId").as("id"))
      .unionByName(
        decisions
          .filter(col("kind") === kind && col("decision").isin(outcomes*))
          .select(col("targetId").as("id"))
      )
    ids.join(labels, "id").select("label").distinct()

  /** Labels of clusters with a member touched by a link the contradiction cut refused. */
  private def cutLabels(c: Clusters): DataFrame =
    c.cut.join(c.labels, "id").select("label").distinct()

  /** `identityPolicy` is the policy version of the applied identity decisions (er-v1), written into
    * the provenance of researcher and work rows.
    */
  def build(src: Sources, run: RunInfo, identityPolicy: String): Result =
    val g = run.graph
    val report = ujson.Obj()
    val decisionCounts = src.decisions
      .groupBy("kind", "decision")
      .count()
      .orderBy("kind", "decision")
      .collect() // at most 2 kinds x 4 outcomes
    decisionCounts.foreach(r =>
      Report.put(report, s"decisions_${r.getString(0)}_${r.getString(1)}", r.getLong(2))
    )
    // The identity policy versions actually applied (er-v1 expected); at most a few values.
    val policies = src.decisions
      .select("policyVersion")
      .distinct()
      .orderBy("policyVersion")
      .collect()
      .map(r => ujson.Str(Option(r.getString(0)).getOrElse("null")): ujson.Value)
    report("decisionPolicyVersions") = ujson.Arr(policies.toSeq*)

    // Researchers ------------------------------------------------------------------------------
    val mentions = src.mentions
      .groupBy("mentionId")
      .agg(min(struct("workId", "rawName", "affiliation")).as("m"))
      .select(col("mentionId"), col("m.workId"), col("m.rawName"), col("m.affiliation"))
      .persist()
    val rLinks = acceptedLinks(src.decisions, "RESEARCHER")
    val externalNodes = rLinks
      .select(col("a").as("id"))
      .unionByName(rLinks.select(col("b").as("id")))
      .filter(!col("id").startsWith("m:"))
    val rNodes = mentions.select(col("mentionId").as("id")).unionByName(externalNodes).distinct()
    val rClusters = constrainedClusters(
      rNodes,
      rLinks,
      contradictionPairs(src.decisions, "RESEARCHER"),
      Some("orcid:" -> "Orcids"),
      g.componentMaxIterations,
      g.identityCutMaxClusterLinks,
      report,
      "researcher"
    )
    val rLabels = rClusters.labels

    val isMention = col("id").startsWith("m:")
    val isOrcid = col("id").startsWith("orcid:")
    val clusters = rLabels
      .groupBy("label")
      .agg(
        array_sort(collect_list(when(isMention, col("id")))).as("mentionIds"),
        array_sort(collect_set(when(isOrcid, col("id")))).as("orcids")
      )
      .filter(size(col("mentionIds")) > 0)
      .withColumn(
        "researcherId",
        when(size(col("orcids")) === 1, element_at(col("orcids"), 1))
          .otherwise(
            concat(lit("sc:r:v1:"), Cols.shortHash(concat_ws("|", col("mentionIds")), 20))
          )
      )
    val linkConfidence = rClusters.applied
      .join(rLabels.select(col("id").as("a"), col("label")), "a")
      .groupBy("label")
      .agg(min("confidence").as("minConfidence"))
    val conflicted = cutLabels(rClusters).withColumn("contradicted", lit(true))
    val unresolved = touched(src.decisions, "RESEARCHER", Seq("UNRESOLVED"), rLabels)
      .withColumn("hasUnresolved", lit(true))
    // Display name: the most frequent raw name with the DBLP homonym suffix (" 0001") removed.
    val names = mentions
      .select(
        col("mentionId").as("id"),
        trim(regexp_replace(col("rawName"), "\\s+\\d{4}$", "")).as("name")
      )
      .filter(col("name").isNotNull && col("name") =!= "")
      .join(rLabels, "id")
      .groupBy("label", "name")
      .agg(count(lit(1)).as("n"))
    val byFrequency = Window.partitionBy("label").orderBy(col("n").desc, col("name").asc)
    val display = names
      .withColumn("r", row_number().over(byFrequency))
      .groupBy("label")
      .agg(
        max(when(col("r") === 1, col("name"))).as("displayName"),
        array_sort(collect_set(col("name"))).as("nameVariants")
      )
    val researchersL = Lineage.cut(
      clusters
        .join(linkConfidence, Seq("label"), "left")
        .join(conflicted, Seq("label"), "left")
        .join(unresolved, Seq("label"), "left")
        .join(display, Seq("label"), "left")
        .select(
          col("label"),
          lit(SchemaVersions.Researcher).as("schemaVersion"),
          col("researcherId"),
          coalesce(col("displayName"), lit("(unnamed)")).as("displayName"),
          coalesce(col("nameVariants"), Cols.emptyStrings).as("nameVariants"),
          when(
            size(col("orcids")) === 1,
            map(lit("orcid"), regexp_replace(element_at(col("orcids"), 1), "^orcid:", ""))
          ).otherwise(map().cast(MapType(StringType, StringType))).as("externalIds"),
          col("mentionIds"),
          size(col("mentionIds")).cast("long").as("mentionCount"),
          coalesce(col("minConfidence"), lit(1.0)).as("identityConfidence"),
          coalesce(col("hasUnresolved"), lit(false)).as("unresolved"),
          (size(col("orcids")) > 1 || coalesce(col("contradicted"), lit(false)))
            .as("identityConflict"),
          Cols
            .provenance(
              run,
              lit("SCHOLARCHAIN"),
              col("researcherId"),
              Some(SchemaVersions.Researcher),
              identityPolicy
            )
            .as("provenance")
        )
    )
    val m2r = Lineage.cut(
      rLabels
        .filter(isMention)
        .join(researchersL.select("label", "researcherId"), "label")
        .select(col("id").as("mentionId"), col("researcherId"))
    )
    val researchers = researchersL.drop("label")
    Report.put(report, "mentions", mentions.count())
    Report.put(report, "researchers", researchers.count())
    Report.put(report, "researchersMerged", researchers.filter(col("mentionCount") > 1).count())
    Report.put(report, "researchersWithOrcid", researchers.filter(isOrcidId).count())
    Report.put(report, "researchersUnresolved", researchers.filter(col("unresolved")).count())
    Report.put(
      report,
      "researchersIdentityConflict",
      researchers.filter(col("identityConflict")).count()
    )

    // Works ------------------------------------------------------------------------------------
    val works = src.works
      .groupBy("workId")
      .agg(
        min(
          struct(
            "source",
            "sourceRecordId",
            "recordType",
            "title",
            "normalizedTitle",
            "year",
            "venueId",
            "venueName",
            "doi",
            "topicIds",
            "institutionIds"
          )
        ).as("w")
      )
      .select(col("workId"), col("w.*"))
      .withColumn("rank", sourceRank(col("source")))
      .persist()
    val wClusters = constrainedClusters(
      works.select(col("workId").as("id")),
      acceptedLinks(src.decisions, "WORK"),
      contradictionPairs(src.decisions, "WORK"),
      None,
      g.componentMaxIterations,
      g.identityCutMaxClusterLinks,
      report,
      "work"
    )
    val wLabels = wClusters.labels
    val repOrder = Window.partitionBy("label").orderBy(col("rank").asc, col("workId").asc)
    val members = works
      .join(wLabels.withColumnRenamed("id", "workId"), "workId")
      .withColumn("r", row_number().over(repOrder))
      .persist()
    val rep = members
      .filter(col("r") === 1)
      .select(
        col("label"),
        col("workId").as("canonicalWorkId"),
        col("source").as("repSource"),
        col("sourceRecordId").as("repSourceRecordId"),
        col("recordType"),
        col("title"),
        col("normalizedTitle"),
        col("year").as("repYear"),
        col("doi").as("repDoi")
      )
    val wConflicted = cutLabels(wClusters).withColumn("contradicted", lit(true))
    val wUnresolved = touched(src.decisions, "WORK", Seq("UNRESOLVED"), wLabels)
      .withColumn("hasUnresolved", lit(true))
    val agg = members
      .groupBy("label")
      .agg(
        array_sort(collect_list("workId")).as("memberWorkIds"),
        array_sort(collect_set("source")).as("sources"),
        min("year").as("minYear"),
        min("doi").as("anyDoi"),
        array_sort(collect_set("venueId")).as("venueIds"),
        array_sort(collect_set("venueName")).as("venueNames"),
        array_sort(
          array_distinct(flatten(collect_list(coalesce(col("topicIds"), Cols.emptyStrings))))
        )
          .as("topicIds"),
        array_sort(
          array_distinct(flatten(collect_list(coalesce(col("institutionIds"), Cols.emptyStrings))))
        ).as("institutionIds")
      )
    val canonicalWorks = Lineage.cut(
      rep
        .join(agg, "label")
        .join(wConflicted, Seq("label"), "left")
        .join(wUnresolved, Seq("label"), "left")
        .select(
          col("label"),
          lit(SchemaVersions.Work).as("schemaVersion"),
          col("canonicalWorkId").as("workId"),
          col("memberWorkIds"),
          col("sources"),
          col("recordType"),
          col("title"),
          col("normalizedTitle"),
          coalesce(col("repYear"), col("minYear")).as("year"),
          coalesce(col("repDoi"), col("anyDoi")).as("doi"),
          col("venueIds"),
          col("venueNames"),
          col("topicIds"),
          col("institutionIds"),
          coalesce(col("contradicted"), lit(false)).as("identityConflict"),
          coalesce(col("hasUnresolved"), lit(false)).as("unresolved"),
          Cols
            .provenance(
              run,
              col("repSource"),
              col("repSourceRecordId"),
              Some(SchemaVersions.Work),
              identityPolicy
            )
            .as("provenance")
        )
    )
    val workMap = Lineage.cut(
      members
        .join(
          canonicalWorks.select(col("label"), col("workId").as("cw"), col("year").as("cy")),
          "label"
        )
        .select(
          col("workId").as("memberWorkId"),
          col("cw").as("workId"),
          col("cy").as("year"),
          (col("r") === 1).as("isRepresentative")
        )
    )
    members.unpersist()
    val worksOut = canonicalWorks.drop("label")
    Report.put(report, "sourceWorks", works.count())
    Report.put(report, "canonicalWorks", worksOut.count())
    Report.put(
      report,
      "canonicalWorksMerged",
      worksOut.filter(size(col("memberWorkIds")) > 1).count()
    )
    Report.put(report, "canonicalWorksWithoutYear", worksOut.filter(col("year").isNull).count())
    Report.put(
      report,
      "canonicalWorksIdentityConflict",
      worksOut.filter(col("identityConflict")).count()
    )
    Report.put(report, "canonicalWorksUnresolved", worksOut.filter(col("unresolved")).count())

    // AUTHORED ---------------------------------------------------------------------------------
    val ap = src.authorPapers
      .select(col("mentionId"), col("workId").as("memberWorkId"), col("position"), col("teamSize"))
      .join(m2r, Seq("mentionId"), "left")
      .join(workMap, Seq("memberWorkId"), "left")
    val apRejects = ap
      .filter(col("researcherId").isNull || col("workId").isNull)
      .select(
        to_json(struct(col("mentionId"), col("memberWorkId"))).as("input"),
        when(col("researcherId").isNull, lit("MENTION_WITHOUT_RESEARCHER"))
          .otherwise(lit("WORK_NOT_FOUND"))
          .as("reason")
      )
    val apOk = ap.filter(col("researcherId").isNotNull && col("workId").isNotNull)
    val perResearcher = apOk
      .groupBy("researcherId", "workId")
      .agg(
        first("year").as("year"),
        array_sort(collect_set("mentionId")).as("mentionIds"),
        min(
          struct(
            when(col("isRepresentative"), 0).otherwise(1).as("k"),
            col("position"),
            col("mentionId")
          )
        )
          .as("best"),
        max("teamSize").as("sourceTeamSize")
      )
    val byWork = Window.partitionBy("workId")
    val canonicalOrder =
      Window.partitionBy("workId").orderBy(col("best").asc, col("researcherId").asc)
    val authored = Lineage.cut(
      perResearcher
        .withColumn("teamSize", count(lit(1)).over(byWork).cast("int"))
        .withColumn("maxSourceTeamSize", max("sourceTeamSize").over(byWork))
        .withColumn("position", (row_number().over(canonicalOrder) - 1).cast("int"))
        .select(
          col("researcherId"),
          col("workId"),
          col("best.mentionId").as("mentionId"),
          col("mentionIds"),
          col("position"),
          col("year"),
          col("teamSize"),
          col("maxSourceTeamSize"),
          Cols
            .provenance(
              run,
              lit("SCHOLARCHAIN"),
              col("best.mentionId"),
              Some(SchemaVersions.Authorship),
              g.policyVersion
            )
            .as("provenance")
        )
    )
    Report.put(report, "authorPaperRows", src.authorPapers.count())
    Report.put(report, "authored", authored.count())
    Report.put(report, "authoredRejected", apRejects.count())
    Report.put(
      report,
      "authoredRejected_MENTION_WITHOUT_RESEARCHER",
      apRejects.filter(col("reason") === "MENTION_WITHOUT_RESEARCHER").count()
    )
    Report.put(
      report,
      "authoredRejected_WORK_NOT_FOUND",
      apRejects.filter(col("reason") === "WORK_NOT_FOUND").count()
    )
    Report.put(
      report,
      "worksWithTeamAboveEverySourceList",
      authored
        .filter(col("teamSize") > col("maxSourceTeamSize"))
        .select("workId")
        .distinct()
        .count()
    )
    // Integrity: every AUTHORED edge must point to an existing researcher and an existing work.
    Report.put(
      report,
      "invariant_authoredMissingResearcher",
      authored.join(researchers, Seq("researcherId"), "left_anti").count()
    )
    Report.put(
      report,
      "invariant_authoredMissingWork",
      authored.join(worksOut.select("workId"), Seq("workId"), "left_anti").count()
    )
    Report.put(
      report,
      "researchersWithoutAuthored",
      researchers.join(authored, Seq("researcherId"), "left_anti").count()
    )

    // Topic, Institution, Venue ----------------------------------------------------------------
    def dedupe(df: DataFrame, id: String, fields: Seq[String]): DataFrame =
      df.groupBy(id)
        .agg(min(struct((fields :+ "provenance").map(col)*)).as("r"))
        .select(col(id), col("r.*"))
    val topics = Lineage.cut(dedupe(src.topics, "topicId", Seq("name")))
    val institutions =
      Lineage.cut(dedupe(src.institutions, "institutionId", Seq("name", "countryCode", "ror")))
    val venues = Lineage.cut(dedupe(src.venues, "venueId", Seq("name", "kind")))
    Report.put(report, "topics", topics.count())
    Report.put(report, "institutions", institutions.count())
    Report.put(report, "venues", venues.count())
    def danglingRefs(arrayCol: String, table: DataFrame, id: String): Long =
      worksOut
        .select(explode(col(arrayCol)).as(id))
        .join(table.select(id), Seq(id), "left_anti")
        .count()
    Report.put(report, "workTopicRefsMissing", danglingRefs("topicIds", topics, "topicId"))
    Report.put(
      report,
      "workInstitutionRefsMissing",
      danglingRefs("institutionIds", institutions, "institutionId")
    )
    Report.put(report, "workVenueRefsMissing", danglingRefs("venueIds", venues, "venueId"))

    // Researcher affiliations (AFFILIATED_WITH) from the mention affiliation institution IDs.
    val affiliationRows = mentions
      .filter(col("affiliation").isNotNull)
      .join(m2r, "mentionId")
      .select(col("researcherId"), col("affiliation").as("institutionId"), col("mentionId"))
    val affiliations = Lineage.cut(
      affiliationRows
        .join(institutions.select("institutionId"), Seq("institutionId"), "left_semi")
        .groupBy("researcherId", "institutionId")
        .agg(count(lit(1)).as("supportingMentionCount"))
    )
    Report.put(report, "affiliations", affiliations.count())
    Report.put(
      report,
      "affiliationRefsMissing",
      affiliationRows
        .join(institutions.select("institutionId"), Seq("institutionId"), "left_anti")
        .count()
    )
    mentions.unpersist()
    works.unpersist()

    Result(
      researchers,
      m2r,
      worksOut,
      workMap,
      authored,
      apRejects,
      topics,
      institutions,
      venues,
      affiliations,
      report
    )

  private def isOrcidId: Column = col("researcherId").startsWith("orcid:")

/** The executor-side step of the contradiction cut of [[Canonical.constrainedClusters]]:
  * constrained single linkage (Kruskal with cannot-link constraints) over the accepted links of one
  * contradicted cluster. Pure and deterministic; it runs inside a UDF on the row that holds the
  * cluster.
  */
object IdentityCut:
  final case class Link(a: String, b: String, confidence: Double)

  private val idLabel =
    StructType(Seq(StructField("id", StringType), StructField("label", StringType)))
  private val pair = StructType(Seq(StructField("a", StringType), StructField("b", StringType)))

  /** UDF result: `assign` (id, label) for every node of the cluster, `refused` (a, b) links. */
  val ResultType: StructType = StructType(
    Seq(StructField("assign", ArrayType(idLabel)), StructField("refused", ArrayType(pair)))
  )

  /** Links in descending confidence, ties by (a, b) ascending; a link is applied unless the union
    * would put both ends of a contradiction, or two IDs with `exclusivePrefix`, into one set.
    * Returns every link endpoint with its label (the smallest ID of its final set) and the refused
    * links in the order they were refused.
    */
  def cut(
      links: Seq[Link],
      contradictions: Seq[(String, String)],
      exclusivePrefix: Option[String]
  ): (Map[String, String], Vector[(String, String)]) =
    val ordered = links.sortBy(l => (-l.confidence, l.a, l.b))
    val parent = mutable.HashMap.empty[String, String]
    val members = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
    val forbidden = mutable.HashMap.empty[String, mutable.HashSet[String]]
    val exclusive = mutable.HashSet.empty[String] // roots of sets holding an exclusive ID
    def add(id: String): Unit =
      if !parent.contains(id) then
        parent(id) = id
        members(id) = mutable.ArrayBuffer(id)
        forbidden(id) = mutable.HashSet.empty
        if exclusivePrefix.exists(id.startsWith) then exclusive += id
    ordered.foreach(l => { add(l.a); add(l.b) })
    // Every node starts as its own root, so a node's own forbidden set is its root's set.
    contradictions.foreach { (x, y) =>
      forbidden.get(x).foreach(_ += y)
      forbidden.get(y).foreach(_ += x)
    }
    def find(x: String): String =
      var r = x
      while parent(r) != r do r = parent(r)
      var c = x
      while parent(c) != r do
        val next = parent(c)
        parent(c) = r
        c = next
      r
    val refused = Vector.newBuilder[(String, String)]
    ordered.foreach { l =>
      val ra = find(l.a)
      val rb = find(l.b)
      if ra != rb then
        val (big, small) = if members(ra).size >= members(rb).size then (ra, rb) else (rb, ra)
        // forbidden(root) holds every node some member of the set must stay apart from.
        val clash = members(small).exists(forbidden(big).contains) ||
          (exclusive.contains(big) && exclusive.contains(small))
        if clash then refused += (l.a -> l.b)
        else
          parent(small) = big
          members.remove(small).foreach(members(big) ++= _)
          forbidden.remove(small).foreach(forbidden(big) ++= _)
          if exclusive.remove(small) then exclusive += big
    }
    val labels = parent.keys.toVector
      .groupBy(find)
      .values
      .flatMap { ids =>
        val label = ids.min
        ids.map(_ -> label)
      }
      .toMap
    labels -> refused.result()

  /** UDF over (links: array<struct<a, b, confidence>>, contra: array<struct<x, y>> or null). A null
    * confidence counts as 0 (weakest).
    */
  def udfFor(exclusivePrefix: Option[String]): UserDefinedFunction =
    type Rows = scala.collection.Seq[Row]
    val f = new UDF2[Rows, Rows, Row] {
      def call(links: Rows, contra: Rows): Row =
        val ls = Option(links).toSeq.flatten.map(r =>
          Link(r.getString(0), r.getString(1), if r.isNullAt(2) then 0.0 else r.getDouble(2))
        )
        val cs = Option(contra).toSeq.flatten.map(r => r.getString(0) -> r.getString(1))
        val (labels, refused) = cut(ls, cs, exclusivePrefix)
        Row(
          labels.toVector.sorted.map((id, label) => Row(id, label)),
          refused.map((a, b) => Row(a, b))
        )
    }
    udf(f, ResultType)
