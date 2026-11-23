package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions.*
import scholarchain.core.{EvidenceType, TimeWindow}

/** Job 10: semantic-context documents for later embedding into S3 Vectors.
  *
  * Every text is a fixed template filled only from computed tables of this run (window metrics,
  * components, communities, path evidence) and from source records (canonical researcher names,
  * work titles, topic, venue and institution names). No model writes text, and no number appears
  * that is not a column of a computed table; ratios are rounded for reading only (the exact value
  * stays in the metric table).
  *
  * One document per window for:
  *   - every researcher with a work in the window (`researcher-profile`);
  *   - every community of two or more members (`collaboration-community`; a one-member community is
  *     an isolated researcher, already described by its profile);
  *   - every found path of the path evidence (`collaboration-path`);
  *   - every topic with a canonical work in the window (`topic-institution-summary`).
  *
  * Bounds: the config has no summary limit (the `graph` section of scholarchain-v1.yaml names
  * none), so no document count or list length is cut here; lists of names grow with the entity (for
  * example a community's member list). Long texts are split into chunks by the embedding step (the
  * chunk ID formula has an ordinal); every document here is chunk 0 of its text.
  *
  * Records: core `EvidenceItem` (evidence-v1), type SEMANTIC_PROFILE, with `chunkId` = core
  * `Ids.chunk(entityId, metricVersion, docType, 0, text)`, `textHash` = `sha256:` + hex digest of
  * the text, `evidenceId` = core `Ids.evidence(SEMANTIC_PROFILE, chunkId)`, the vector index name
  * from `vectors.indexes` and the window in `values`.
  */
object Summaries:
  val ResearcherProfile = "researcher-profile"
  val CommunityProfile = "collaboration-community"
  val PathSummary = "collaboration-path"
  val TopicSummary = "topic-institution-summary"

  /** docType -> key of `vectors.indexes` in scholarchain-v1.yaml. */
  val IndexKeys: Map[String, String] = Map(
    ResearcherProfile -> "researchers",
    CommunityProfile -> "communities",
    PathSummary -> "paths",
    TopicSummary -> "topicInstitutions"
  )

  /** Canonical tables (job 1) and source mentions the templates read. `tableUri(name)` is the URI
    * of an exported table, written into `sourceUri`.
    */
  final case class Context(
      researchers: DataFrame,
      works: DataFrame,
      authored: DataFrame,
      mentions: DataFrame,
      topics: DataFrame,
      institutions: DataFrame,
      run: RunInfo,
      vectorIndexes: Map[String, String],
      tableUri: String => String
  )

  /** The vector index of every docType, or the config keys that are missing. */
  def indexes(configured: Map[String, String]): Either[String, Map[String, String]] =
    val missing = IndexKeys.values.filterNot(configured.contains).toVector.sorted
    if missing.nonEmpty then Left(s"vectors.indexes lacks ${missing.mkString(", ")}")
    else Right(IndexKeys.view.mapValues(configured).toMap)

  private val None_ = lit("none recorded")
  private def txt(parts: Column*): Column = concat_ws("", parts*)
  private def str(c: Column): Column = c.cast("string")

  /** `rows(keys..., label, n)` -> one row per key with `out` = "label (n); ..." ordered by n
    * descending, then label.
    */
  private def countedList(rows: DataFrame, keys: Seq[String], out: String): DataFrame =
    rows
      .groupBy(keys.map(col)*)
      .agg(
        sort_array(collect_list(struct((-col("n")).as("k"), col("label"), col("n")))).as("xs")
      )
      .select(
        (keys.map(col) :+ array_join(
          transform(
            col("xs"),
            x => concat(x.getField("label"), lit(" ("), str(x.getField("n")), lit(")"))
          ),
          "; "
        ).as(out))*
      )

  /** `keyed(keys..., workId)`: topics of those works, counted in distinct works. */
  private def topicRows(keyed: DataFrame, keys: Seq[String], ctx: Context): DataFrame =
    keyed
      .select((keys :+ "workId").map(col)*)
      .distinct()
      .join(ctx.works.select(col("workId"), explode(col("topicIds")).as("topicId")), "workId")
      .join(ctx.topics.select(col("topicId"), col("name").as("label")), "topicId")
      .groupBy((keys ++ Seq("topicId", "label")).map(col)*)
      .agg(countDistinct("workId").as("n"))

  /** `keyed(keys..., workId)`: venue names of those works (source records), in distinct works. */
  private def venueRows(keyed: DataFrame, keys: Seq[String], ctx: Context): DataFrame =
    keyed
      .select((keys :+ "workId").map(col)*)
      .distinct()
      .join(ctx.works.select(col("workId"), explode(col("venueNames")).as("label")), "workId")
      .groupBy((keys :+ "label").map(col)*)
      .agg(countDistinct("workId").as("n"))

  /** `keyed(keys..., workId, mentionIds)`: institutions of the researchers' own mentions on those
    * works (mention affiliation, source records), counted in distinct works.
    */
  private def institutionRows(keyed: DataFrame, keys: Seq[String], ctx: Context): DataFrame =
    keyed
      .select((keys.map(col) ++ Seq(col("workId"), explode(col("mentionIds")).as("mentionId")))*)
      .join(
        ctx.mentions.select(col("mentionId"), col("affiliation").as("institutionId")),
        "mentionId"
      )
      .join(ctx.institutions.select(col("institutionId"), col("name").as("label")), "institutionId")
      .groupBy((keys ++ Seq("institutionId", "label")).map(col)*)
      .agg(countDistinct("workId").as("n"))

  private def windowAuthored(ctx: Context, w: TimeWindow): DataFrame =
    ctx.authored
      .filter(Cols.inWindow(col("year"), w))
      .select("researcherId", "workId", "mentionIds")

  private def names(ctx: Context): DataFrame =
    ctx.researchers.select(col("researcherId"), col("displayName"))

  /** Finishes documents `(entityId, researcherIds, graphPath, text)` into evidence rows. */
  private def finish(
      docs: DataFrame,
      docType: String,
      w: TimeWindow,
      ctx: Context,
      sourceUri: String
  ): DataFrame =
    val g = ctx.run.graph
    val chunkId = concat(
      lit("c:"),
      Cols.shortHash(
        concat_ws(
          "|",
          col("entityId"),
          lit(g.metricVersion),
          lit(docType),
          lit("0"),
          sha2(col("text"), 256)
        ),
        20
      )
    )
    val t = EvidenceType.SEMANTIC_PROFILE
    docs
      .withColumn("chunkId", chunkId)
      .select(
        (Evidence.item(
          t,
          Evidence.id(t, col("chunkId")),
          col("entityId"),
          col("researcherIds"),
          lit(sourceUri),
          Seq(
            "docType" -> lit(docType),
            "vectorIndex" -> lit(ctx.vectorIndexes(docType)),
            "entityId" -> col("entityId"),
            "window" -> lit(w.label)
          ),
          ctx.run,
          g.metricVersion,
          chunkId = col("chunkId"),
          graphPath = col("graphPath"),
          text = col("text")
        ) ++ Seq(lit(docType).as("docType"), lit(w.label).as("windowLabel")))*
      )

  /** Researcher profiles of one window from its metric rows. */
  def researcherProfiles(r: WindowAnalytics.Result, ctx: Context): DataFrame =
    val w = r.window
    val keys = Seq("researcherId")
    val aw = windowAuthored(ctx, w)
    val topics = countedList(topicRows(aw, keys, ctx), keys, "topicList")
    val venues = countedList(venueRows(aw, keys, ctx), keys, "venueList")
    val insts = countedList(institutionRows(aw, keys, ctx), keys, "institutionList")
    val maxTeam = r.metrics
      .select(col("researcherId"), posexplode(col("maximumTeamWorkIds")).as(Seq("pos", "workId")))
      .join(
        ctx.works.select(col("workId"), col("title"), col("year").as("wy")),
        Seq("workId"),
        "left"
      )
      .groupBy("researcherId")
      .agg(
        sort_array(
          collect_list(
            struct(
              col("pos"),
              txt(
                coalesce(col("title"), lit("(untitled)")),
                lit(" ("),
                col("workId"),
                lit(", "),
                coalesce(str(col("wy")), lit("year unknown")),
                lit(")")
              ).as("s")
            )
          )
        ).as("xs")
      )
      .select(
        col("researcherId"),
        array_join(transform(col("xs"), x => x.getField("s")), "; ").as("maxTeamList")
      )
    val docs = r.metrics
      .join(
        ctx.researchers.select(
          "researcherId",
          "displayName",
          "identityConfidence",
          "unresolved",
          "identityConflict"
        ),
        Seq("researcherId"),
        "left"
      )
      .join(topics, keys, "left")
      .join(venues, keys, "left")
      .join(insts, keys, "left")
      .join(maxTeam, keys, "left")
      .select(
        col("researcherId").as("entityId"),
        array(col("researcherId")).as("researcherIds"),
        Evidence.nullStrings.as("graphPath"),
        concat_ws(
          " ",
          txt(lit(s"Researcher profile, window ${w.label}. Name: "), col("displayName")),
          txt(lit("("), col("researcherId"), lit(").")),
          txt(lit("Publications: "), str(col("publicationCount")), lit(".")),
          txt(lit("Distinct coauthors: "), str(col("distinctCoauthorCount")), lit(".")),
          txt(lit("Total coauthor occurrences: "), str(col("totalCoauthorOccurrences")), lit(".")),
          txt(
            lit("Average coauthors per paper: "),
            format_string("%.2f", col("averageCoauthorsPerPaper")),
            lit(".")
          ),
          txt(
            lit("Largest team: "),
            str(col("maximumCoauthorsOnOnePaper")),
            lit(" coauthors on one paper; works with that team size: "),
            str(col("maximumTeamWorkCount")),
            lit(", listed: "),
            coalesce(col("maxTeamList"), None_),
            lit(".")
          ),
          txt(
            lit("Weighted degree (joint publications summed over coauthors): "),
            str(col("weightedDegree").cast("long")),
            lit(".")
          ),
          txt(
            lit("Component "),
            col("componentId"),
            lit(" with "),
            str(col("componentSize")),
            lit(" researchers"),
            when(col("inLargestComponent"), lit(", the largest component")),
            lit(".")
          ),
          txt(lit("Community: "), col("communityId"), lit(".")),
          txt(lit("Coreness: "), str(col("coreness")), lit(".")),
          txt(lit("Coauthors outside own community: "), str(col("crossCommunityDegree")), lit(".")),
          txt(lit("Communities connected: "), str(col("communitiesConnected")), lit(".")),
          txt(
            lit("Participation coefficient: "),
            format_string("%.3f", col("participationCoefficient")),
            lit(".")
          ),
          txt(
            lit("Sampled betweenness estimate: "),
            format_string("%.4f", col("bridgeScore")),
            lit(".")
          ),
          txt(lit("Topics (works): "), coalesce(col("topicList"), None_), lit(".")),
          txt(lit("Venues (works): "), coalesce(col("venueList"), None_), lit(".")),
          txt(lit("Institutions (works): "), coalesce(col("institutionList"), None_), lit(".")),
          txt(
            lit("Identity confidence: "),
            format_string("%.2f", col("identityConfidence")),
            when(col("unresolved"), lit("; unresolved identity decisions recorded")),
            when(
              col("identityConflict"),
              lit("; identity conflict flagged (an accepted identity link was refused)")
            ),
            lit(".")
          )
        ).as("text")
      )
    finish(
      docs,
      ResearcherProfile,
      w,
      ctx,
      ctx.tableUri(s"researcher-metrics/windowLabel=${w.label}")
    )

  /** Profiles of the communities of two or more members of one window. */
  def communityProfiles(r: WindowAnalytics.Result, ctx: Context): DataFrame =
    val w = r.window
    val keys = Seq("communityId")
    val big = r.communities.filter(col("communitySize") >= 2)
    val members = r.metrics
      .select("researcherId", "communityId", "distinctCoauthorCount")
      .join(big.select("communityId"), "communityId")
    val memberList = countedList(
      members
        .join(names(ctx), Seq("researcherId"), "left")
        .select(
          col("communityId"),
          coalesce(col("displayName"), col("researcherId")).as("label"),
          col("distinctCoauthorCount").as("n")
        ),
      keys,
      "memberList"
    )
    val aw =
      windowAuthored(ctx, w).join(members.select("researcherId", "communityId"), "researcherId")
    val topics = countedList(topicRows(aw, keys, ctx), keys, "topicList")
    val venues = countedList(venueRows(aw, keys, ctx), keys, "venueList")
    val insts = countedList(institutionRows(aw, keys, ctx), keys, "institutionList")
    val docs = big
      .join(memberList, keys, "left")
      .join(topics, keys, "left")
      .join(venues, keys, "left")
      .join(insts, keys, "left")
      .select(
        col("communityId").as("entityId"),
        Cols.emptyStrings.as("researcherIds"),
        Evidence.nullStrings.as("graphPath"),
        concat_ws(
          " ",
          txt(lit("Collaboration community "), col("communityId"), lit(s", window ${w.label}.")),
          txt(lit("Members: "), str(col("communitySize")), lit(".")),
          txt(
            lit("Coauthor edges inside the community: "),
            str(col("internalEdgeCount")),
            lit(".")
          ),
          txt(
            lit("Coauthor edges to other communities: "),
            str(col("crossCommunityEdgeCount")),
            lit(".")
          ),
          txt(
            lit("Members (distinct coauthors in the window): "),
            coalesce(col("memberList"), None_),
            lit(".")
          ),
          txt(
            lit("Topics of members' works (works): "),
            coalesce(col("topicList"), None_),
            lit(".")
          ),
          txt(
            lit("Venues of members' works (works): "),
            coalesce(col("venueList"), None_),
            lit(".")
          ),
          txt(
            lit("Institutions of members (works): "),
            coalesce(col("institutionList"), None_),
            lit(".")
          )
        ).as("text")
      )
    finish(docs, CommunityProfile, w, ctx, ctx.tableUri(s"communities/windowLabel=${w.label}"))

  /** Summaries of the found paths of one window. `paths` is [[Paths.evidence]] output plus
    * `pathEvidenceId` (the GRAPH_PATH evidence ID, used as the document's entity).
    */
  def pathSummaries(paths: DataFrame, r: WindowAnalytics.Result, ctx: Context): DataFrame =
    val w = r.window
    val found = paths.filter(col("found"))
    val chain = found
      .select(col("pathEvidenceId"), posexplode(col("path")).as(Seq("pos", "researcherId")))
      .join(names(ctx), Seq("researcherId"), "left")
      .groupBy("pathEvidenceId")
      .agg(
        sort_array(
          collect_list(
            struct(col("pos"), coalesce(col("displayName"), col("researcherId")).as("n"))
          )
        ).as("xs")
      )
      .select(
        col("pathEvidenceId"),
        array_join(transform(col("xs"), x => x.getField("n")), " -> ").as("chain")
      )
    val hops = found
      .select(col("pathEvidenceId"), posexplode(col("hopEdgeIds")).as(Seq("pos", "edgeId")))
      .join(
        r.edges.select(
          "edgeId",
          "jointPublicationCount",
          "firstCollaborationYear",
          "lastCollaborationYear"
        ),
        Seq("edgeId"),
        "left"
      )
      .groupBy("pathEvidenceId")
      .agg(
        sort_array(
          collect_list(
            struct(
              col("pos"),
              txt(
                col("edgeId"),
                lit(" ("),
                str(col("jointPublicationCount")),
                lit(" joint publications, "),
                str(col("firstCollaborationYear")),
                lit("-"),
                str(col("lastCollaborationYear")),
                lit(")")
              ).as("s")
            )
          )
        ).as("xs")
      )
      .select(
        col("pathEvidenceId"),
        array_join(transform(col("xs"), x => x.getField("s")), "; ").as("hopList")
      )
    val n = names(ctx)
    val docs = found
      .join(chain, "pathEvidenceId")
      .join(hops, Seq("pathEvidenceId"), "left")
      .join(n.toDF("src", "srcName"), Seq("src"), "left")
      .join(n.toDF("tgt", "tgtName"), Seq("tgt"), "left")
      .select(
        col("pathEvidenceId").as("entityId"),
        array(col("src"), col("tgt")).as("researcherIds"),
        col("path").as("graphPath"),
        concat_ws(
          " ",
          txt(
            lit(s"Collaboration path, window ${w.label}: "),
            coalesce(col("srcName"), col("src")),
            lit(" ("),
            col("src"),
            lit(") to "),
            coalesce(col("tgtName"), col("tgt")),
            lit(" ("),
            col("tgt"),
            lit(").")
          ),
          txt(
            lit("Shortest path length: "),
            str(col("distance")),
            lit(" hops; "),
            str(col("shortestPathCount")),
            lit(" shortest paths of this length.")
          ),
          txt(lit("Chain: "), col("chain"), lit(".")),
          txt(lit("Coauthor edges: "), coalesce(col("hopList"), None_), lit("."))
        ).as("text")
      )
    finish(docs, PathSummary, w, ctx, ctx.tableUri(s"path-evidence/windowLabel=${w.label}"))

  /** Topic summaries of one window: canonical works with the topic and their source records. */
  def topicSummaries(w: TimeWindow, ctx: Context): DataFrame =
    val ww = ctx.works.filter(Cols.inWindow(col("year"), w))
    val tw = ww
      .select(col("workId"), col("year"), explode(col("topicIds")).as("topicId"))
      .join(ctx.topics.select(col("topicId"), col("name")), "topicId")
    val keys = Seq("topicId")
    val stats = tw
      .groupBy("topicId", "name")
      .agg(countDistinct("workId").as("works"), min("year").as("y0"), max("year").as("y1"))
    val researchers = tw
      .join(ctx.authored.select("workId", "researcherId"), "workId")
      .groupBy("topicId")
      .agg(countDistinct("researcherId").as("researchers"))
    val insts = countedList(
      tw.join(
        ww.select(col("workId"), explode(col("institutionIds")).as("institutionId")),
        "workId"
      ).join(
        ctx.institutions.select(col("institutionId"), col("name").as("label")),
        "institutionId"
      ).groupBy("topicId", "institutionId", "label")
        .agg(countDistinct("workId").as("n")),
      keys,
      "institutionList"
    )
    val venues = countedList(
      tw.join(ww.select(col("workId"), explode(col("venueNames")).as("label")), "workId")
        .groupBy("topicId", "label")
        .agg(countDistinct("workId").as("n")),
      keys,
      "venueList"
    )
    val docs = stats
      .join(researchers, keys, "left")
      .join(insts, keys, "left")
      .join(venues, keys, "left")
      .select(
        col("topicId").as("entityId"),
        Cols.emptyStrings.as("researcherIds"),
        Evidence.nullStrings.as("graphPath"),
        concat_ws(
          " ",
          txt(
            lit(s"Topic summary, window ${w.label}: "),
            col("name"),
            lit(" ("),
            col("topicId"),
            lit(").")
          ),
          txt(
            lit("Works: "),
            str(col("works")),
            lit(", published "),
            str(col("y0")),
            lit("-"),
            str(col("y1")),
            lit(".")
          ),
          txt(lit("Researchers: "), str(coalesce(col("researchers"), lit(0L))), lit(".")),
          txt(
            lit("Institutions on these works (works): "),
            coalesce(col("institutionList"), None_),
            lit(".")
          ),
          txt(lit("Venues (works): "), coalesce(col("venueList"), None_), lit("."))
        ).as("text")
      )
    finish(docs, TopicSummary, w, ctx, ctx.tableUri("graph-nodes/label=Topic"))
