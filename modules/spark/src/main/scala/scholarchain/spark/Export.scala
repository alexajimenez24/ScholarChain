package scholarchain.spark

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale

import org.apache.spark.sql.{Column, DataFrame, SaveMode}
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{ArrayType, StringType, StructField, StructType}
import scholarchain.core.{EvidenceType, GraphLabels, SchemaVersions, TimeWindow}

/** Core `EvidenceItem` (evidence-v1) columns. The Spark tables that have no record of their own
  * (components, communities, cross-community edges, path evidence, temporal comparisons, semantic
  * summaries) are written in this committed shape, each with the evidence type made for it
  * (COMPONENT_RECORD, COMMUNITY_RECORD, GRAPH_PATH, METRIC_RECORD, SEMANTIC_PROFILE), so a later
  * stage decodes them with core `JsonLines` and checks them with `Validate.evidence`.
  *
  *   - `evidenceId` = core `Ids.evidence(type, key)`: `ev:<type>:` + h(key, 16).
  *   - `values`: the record's fields as strings (null fields left out), always with its window.
  *   - `score` is a retrieval-time relevance score; it is 0.0 at export.
  *   - `provenance`: source SCHOLARCHAIN, the record key, the Spark run and snapshot, schema
  *     evidence-v1 and the graph policy or metric version that produced it.
  */
object Evidence:
  val nullStrings: Column = lit(null).cast(ArrayType(StringType))

  def id(t: EvidenceType, key: Column): Column =
    concat(lit(s"ev:${t.toString.toLowerCase(Locale.ROOT)}:"), Cols.shortHash(key, 16))

  def values(kv: Seq[(String, Column)]): Column =
    map_filter(map(kv.flatMap((k, v) => Seq(lit(k), v.cast("string")))*), (_, v) => v.isNotNull)

  def item(
      t: EvidenceType,
      evidenceId: Column,
      sourceRecordId: Column,
      researcherIds: Column,
      sourceUri: Column,
      kv: Seq[(String, Column)],
      run: RunInfo,
      policyVersion: String,
      workId: Column = Cols.nullString,
      chunkId: Column = Cols.nullString,
      graphPath: Column = nullStrings,
      text: Column = Cols.nullString
  ): Seq[Column] =
    Seq(
      lit(SchemaVersions.Evidence).as("schemaVersion"),
      evidenceId.as("evidenceId"),
      lit(t.toString).as("evidenceType"),
      workId.as("workId"),
      coalesce(researcherIds, Cols.emptyStrings).as("researcherIds"),
      sourceUri.as("sourceUri"),
      chunkId.as("chunkId"),
      graphPath.as("graphPath"),
      lit(0.0).as("score"),
      values(kv).as("values"),
      text.as("text"),
      when(text.isNotNull, concat(lit("sha256:"), sha2(text, 256))).as("textHash"),
      Cols
        .provenance(
          run,
          lit("SCHOLARCHAIN"),
          sourceRecordId,
          Some(SchemaVersions.Evidence),
          policyVersion
        )
        .as("provenance")
    )

/** Job 11: export tables and load manifests.
  *
  * Tables (Parquet under `<output>/spark/snapshot=<d>/run_id=<id>/<table>/`, partitioned):
  *
  * | table              | record                                                                               | partitions              |
  * |:-------------------|:-------------------------------------------------------------------------------------|:------------------------|
  * | graph-nodes        | core GraphNode (Researcher, Work, Topic, Institution, Venue)                         | label                   |
  * | graph-edges        | core GraphEdge (AUTHORED, COAUTHORED_WITH, HAS_TOPIC, PUBLISHED_IN, AFFILIATED_WITH) | edgeType                |
  * | coauthor-edges     | core CoauthorEdge + supportingWorkIds                                                | windowLabel             |
  * | researcher-metrics | core ResearcherMetrics + maximumTeamWorkCount, inLargestComponent                    | windowLabel             |
  * | components         | EvidenceItem COMPONENT_RECORD (size, fraction, sampled distances)                    | windowLabel             |
  * | communities        | EvidenceItem COMMUNITY_RECORD (communities; cross-community edges)                   | windowLabel, recordKind |
  * | path-evidence      | EvidenceItem GRAPH_PATH                                                              | windowLabel             |
  * | metric-comparisons | EvidenceItem METRIC_RECORD (job 9 temporal comparisons)                              | comparison              |
  * | semantic-summaries | EvidenceItem SEMANTIC_PROFILE (job 10)                                               | docType, windowLabel    |
  * | paper-abstracts    | storage PaperAbstract (= hadoop AbstractRecord) + sourceWorkId, upstreamRunId        | (none)                  |
  * | rejects            | core RejectRecord (stage, reason, input, runId)                                      | stage                   |
  *
  * Node tables are not windowed (identity facts); every windowed row names its window. Each write
  * repartitions by the partition columns first (one shuffle per table), so each partition folder
  * gets one file. Row counts are read back from the written files.
  *
  * JSON lines (Phase 07 gate): every table a storage loader reads (graph-nodes, graph-edges,
  * coauthor-edges, researcher-metrics, semantic-summaries, paper-abstracts) is also written as JSON
  * lines under `<run>/jsonl/<table>/`, see [[JsonLinesExport]].
  */
object Export:
  /** How a table's JSON-lines copy maps onto its core record: `coreRecord` names the record,
    * `extraFields` are the table's columns that record does not have, and `idColumns` identify a
    * row (checked equal between the Parquet and the JSON-lines copy).
    */
  final case class JsonSpec(coreRecord: String, idColumns: Seq[String], extraFields: Seq[String])

  /** The JSON-lines copy of a table: its files (path relative to `path`, bytes), line counts per
    * partition folder, and the number of identifiers present in only one of the two copies.
    */
  final case class JsonOut(
      path: String,
      spec: JsonSpec,
      rows: Long,
      byPartition: Map[String, Long],
      files: Vector[(String, Long)],
      idMismatches: Long
  ):
    def json: ujson.Obj = ujson.Obj(
      "path" -> path,
      "format" -> "json-lines",
      "compression" -> "none",
      "lineContent" -> JsonLinesExport.LineContent,
      "coreRecord" -> spec.coreRecord,
      "extraFields" -> ujson.Arr(spec.extraFields.map(ujson.Str(_))*),
      "identifierColumns" -> ujson.Arr(spec.idColumns.map(ujson.Str(_))*),
      "rows" -> rows.toDouble,
      "rowsByPartition" -> ujson.Obj.from(
        byPartition.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))
      ),
      "files" -> ujson.Arr(
        files.map((f, b) => ujson.Obj("file" -> f, "bytes" -> b.toDouble))*
      )
    )

  final case class Table(
      name: String,
      path: String,
      recordSchema: String,
      partitionColumns: Seq[String],
      rows: Long,
      files: Long,
      byPartition: Map[String, Long],
      jsonLines: Option[JsonOut] = None
  ):
    def json: ujson.Obj =
      val o = ujson.Obj(
        "table" -> name,
        "path" -> path,
        "format" -> "parquet",
        "recordSchema" -> recordSchema,
        "partitionColumns" -> ujson.Arr(partitionColumns.map(ujson.Str(_))*),
        "rows" -> rows.toDouble,
        "files" -> files.toDouble,
        "rowsByPartition" -> ujson.Obj.from(
          byPartition.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))
        )
      )
      jsonLines.foreach(j => o("jsonLines") = j.json)
      o

  /** Writes a table as partitioned Parquet and, when `jsonSpec` is given, its JSON-lines copy. */
  def write(
      df: DataFrame,
      base: String,
      name: String,
      recordSchema: String,
      partitionColumns: Seq[String],
      jsonSpec: Option[JsonSpec] = None
  ): Table =
    val path = s"$base/$name"
    val writer =
      if partitionColumns.isEmpty then df.write
      else df.repartition(partitionColumns.map(col)*).write.partitionBy(partitionColumns*)
    writer.mode(SaveMode.Overwrite).parquet(path)
    val back = df.sparkSession.read.schema(df.schema).parquet(path)
    val parts = back
      .groupBy(partitionColumns.map(col)*)
      .count()
      .collect() // one row per partition folder (windows x kinds): a handful
      .map(r => (partitionColumns.indices.map(r.get), r.getLong(partitionColumns.size)))
      .toVector
    val byPartition =
      if partitionColumns.isEmpty then Map.empty[String, Long]
      else parts.map((vs, n) => JsonLinesExport.folder(partitionColumns, vs, raw = true) -> n).toMap
    val rows = parts.map(_._2).sum
    val json = jsonSpec.map(
      JsonLinesExport.write(
        back,
        s"$base/${JsonLinesExport.Root}/$name",
        partitionColumns,
        parts.map(_._1),
        _
      )
    )
    Table(
      name,
      path,
      recordSchema,
      partitionColumns,
      rows,
      countFiles(back, path, ".parquet"),
      byPartition,
      json
    )

  private def countFiles(df: DataFrame, path: String, suffix: String): Long =
    val p = new org.apache.hadoop.fs.Path(path)
    val fs = p.getFileSystem(df.sparkSession.sparkContext.hadoopConfiguration)
    if !fs.exists(p) then 0L
    else
      val it = fs.listFiles(p, true)
      var files = 0L
      while it.hasNext do if it.next().getPath.getName.endsWith(suffix) then files += 1
      files

  /** JSON-lines copies of the tables the storage loaders read (Phase 07 gate decision).
    *
    *   - Location: `<run>/jsonl/<table>/`, with the same partition folders as the Parquet table
    *     (`label=Researcher/`, `windowLabel=1950-2026/`, ...); an unpartitioned table has its files
    *     directly in the folder. Files are Spark's `part-*.json`, uncompressed UTF-8, one row per
    *     line; `_SUCCESS` and checksum files are not data.
    *   - Content: each line is one row of the Parquet table as Spark reads it back, with every
    *     column, the partition columns included (so a `GraphNode` line keeps its `label`), and
    *     every null written as JSON null (core decoding needs the Option keys). Identifiers and
    *     values are the Parquet values; the copy is written from the Parquet files, not recomputed.
    *   - Core records: removing the table's `extraFields` from a line leaves exactly the core
    *     record (`coreRecord`), which core `JsonLines.decode` reads strictly.
    *   - Checks: line counts per partition folder equal the Parquet counts, and the identifier
    *     columns hold the same set in both copies (full outer join, mismatches counted); both are
    *     invariants of job 11.
    *
    * Cost: per partition folder one pruned Parquet scan and one JSON write, then one line count,
    * plus one identifier join per table; nothing is collected but the partition counts.
    */
  object JsonLinesExport:
    val Root = "jsonl"
    val LineContent =
      "one Parquet row per line, every column including partition columns, nulls written; drop extraFields to get the core record"

    /** `k1=v1/k2=v2`; with `raw` false the values are URL-encoded for use as a folder name. */
    def folder(cols: Seq[String], values: Seq[Any], raw: Boolean = false): String =
      cols
        .zip(values)
        .map { (c, v) =>
          // raw: the manifest key as before (`String.valueOf`); else the folder name Spark reads
          // back as that value.
          if raw then s"$c=${String.valueOf(v)}"
          else
            s"$c=${URLEncoder.encode(Option(v).fold("__HIVE_DEFAULT_PARTITION__")(_.toString), UTF_8)}"
        }
        .mkString("/")

    def write(
        back: DataFrame,
        path: String,
        partitionColumns: Seq[String],
        partitionValues: Seq[Seq[Any]],
        spec: JsonSpec
    ): JsonOut =
      val spark = back.sparkSession
      val root = new org.apache.hadoop.fs.Path(path)
      val fs = root.getFileSystem(spark.sparkContext.hadoopConfiguration)
      fs.delete(root, true)
      fs.mkdirs(root)
      def out(df: DataFrame, dir: String): Unit =
        df.write.mode(SaveMode.Overwrite).option("ignoreNullFields", "false").json(dir)
      val folders: Seq[(String, String)] =
        if partitionColumns.isEmpty then
          out(back, path)
          Seq("" -> path)
        else
          partitionValues.map { vs =>
            val sel = partitionColumns
              .zip(vs)
              .map((c, v) => if v == null then col(c).isNull else col(c) === lit(v))
              .reduce(_ && _)
            val dir = s"$path/${folder(partitionColumns, vs)}"
            out(back.filter(sel), dir)
            folder(partitionColumns, vs, raw = true) -> dir
          }
      val byPartition = folders.map((k, dir) => k -> spark.read.text(dir).count()).toMap
      val idSchema = StructType(spec.idColumns.map(StructField(_, StringType)))
      val jsonIds = folders
        .map((_, dir) => spark.read.schema(idSchema).json(dir))
        .reduceOption(_.unionByName(_))
        .getOrElse(spark.createDataFrame(java.util.List.of[org.apache.spark.sql.Row](), idSchema))
        .withColumn("_inJson", lit(true))
      val parquetIds = back
        .select(spec.idColumns.map(c => col(c).cast("string").as(c))*)
        .withColumn("_inParquet", lit(true))
      val idMismatches = parquetIds
        .join(jsonIds, spec.idColumns, "full_outer")
        .filter(col("_inParquet").isNull || col("_inJson").isNull)
        .count()
      val files = Vector.newBuilder[(String, Long)]
      val it = fs.listFiles(root, true)
      val prefix = fs.makeQualified(root).toString.stripSuffix("/") + "/"
      while it.hasNext do
        val st = it.next()
        if st.getPath.getName.endsWith(".json") then
          files += st.getPath.toString.stripPrefix(prefix) -> st.getLen
      JsonOut(
        path,
        spec,
        byPartition.values.sum,
        if partitionColumns.isEmpty then Map.empty else byPartition,
        files.result().sortBy(_._1),
        idMismatches
      )

  // Graph nodes and edges ----------------------------------------------------------------------

  private def props(kv: (String, Column)*): Column = Evidence.values(kv)

  private def nodeProvenance(run: RunInfo, source: Column, recordId: Column): Column =
    Cols.provenance(run, source, recordId, Some(SchemaVersions.GraphNode), run.graph.policyVersion)

  /** Researcher and Work nodes keep the identity policy (er-v1) of their canonical row. */
  private def identityProvenance(run: RunInfo, source: Column, recordId: Column): Column =
    Cols.provenance(
      run,
      source,
      recordId,
      Some(SchemaVersions.GraphNode),
      col("provenance.policyVersion")
    )

  /** GraphNode rows (nodeId, label, properties, provenance) of the canonical tables. Researcher
    * nodes carry their member mention IDs (`mentionIds`, '|'-joined, sorted) and Work nodes their
    * member source work IDs (`memberWorkIds`), so every identity is traceable to its source
    * records.
    */
  def graphNodes(c: Canonical.Result, run: RunInfo): DataFrame =
    val researchers = c.researchers.select(
      col("researcherId").as("nodeId"),
      lit(GraphLabels.Researcher).as("label"),
      props(
        "displayName" -> col("displayName"),
        "orcid" -> col("externalIds").getItem("orcid"),
        "mentionCount" -> col("mentionCount"),
        "mentionIds" -> array_join(col("mentionIds"), "|"),
        "identityConfidence" -> col("identityConfidence"),
        "unresolved" -> col("unresolved"),
        "identityConflict" -> col("identityConflict")
      ).as("properties"),
      identityProvenance(run, lit("SCHOLARCHAIN"), col("researcherId")).as("provenance")
    )
    val works = c.works.select(
      col("workId").as("nodeId"),
      lit(GraphLabels.Work).as("label"),
      props(
        "title" -> col("title"),
        "year" -> col("year"),
        "doi" -> col("doi"),
        "recordType" -> col("recordType"),
        "memberWorkIds" -> array_join(col("memberWorkIds"), "|"),
        "unresolved" -> col("unresolved"),
        "identityConflict" -> col("identityConflict")
      ).as("properties"),
      identityProvenance(run, col("provenance.source"), col("provenance.sourceRecordId"))
        .as("provenance")
    )
    def simple(df: DataFrame, id: String, label: String, kv: (String, Column)*) =
      df.select(
        col(id).as("nodeId"),
        lit(label).as("label"),
        props(kv*).as("properties"),
        nodeProvenance(run, col("provenance.source"), col("provenance.sourceRecordId"))
          .as("provenance")
      )
    researchers
      .unionByName(works)
      .unionByName(simple(c.topics, "topicId", GraphLabels.Topic, "name" -> col("name")))
      .unionByName(
        simple(
          c.institutions,
          "institutionId",
          GraphLabels.Institution,
          "name" -> col("name"),
          "countryCode" -> col("countryCode"),
          "ror" -> col("ror")
        )
      )
      .unionByName(
        simple(c.venues, "venueId", GraphLabels.Venue, "name" -> col("name"), "kind" -> col("kind"))
      )

  /** core `Ids.graphEdge`: `e:<type>:` + h(`type|from|to|policy|evidence`, 24). */
  private def graphEdgeId(edgeType: String, from: Column, to: Column, policy: String, ev: Column) =
    concat(
      lit(s"e:${edgeType.toLowerCase(Locale.ROOT).take(20)}:"),
      Cols.shortHash(concat_ws("|", lit(edgeType), from, to, lit(policy), ev), 24)
    )

  private def edgeRows(
      df: DataFrame,
      edgeType: String,
      from: Column,
      to: Column,
      evidence: Column,
      recordId: Column,
      run: RunInfo,
      kv: (String, Column)*
  ): DataFrame =
    val p = run.graph.policyVersion
    df.select(
      graphEdgeId(edgeType, from, to, p, evidence).as("edgeId"),
      from.as("fromId"),
      lit(edgeType).as("edgeType"),
      to.as("toId"),
      props(kv*).as("properties"),
      Cols
        .provenance(run, lit("SCHOLARCHAIN"), recordId, Some(SchemaVersions.GraphEdge), p)
        .as("provenance")
    )

  /** GraphEdge rows. Evidence in the ID: AUTHORED the mention, COAUTHORED_WITH the window's
    * coauthor edge ID, the others none. Topic, venue and institution edges are written only when
    * the target node exists (dangling references are counted by job 1).
    *
    * COAUTHORED_WITH is window-scoped by design: one edge per researcher pair per configured window
    * the pair collaborated in (the core coauthor edge ID hashes the window, spec "coauthor edge
    * ID"), with the window label in the `window` property. Overlapping windows therefore give one
    * pair several parallel edges, and every graph query over COAUTHORED_WITH must filter on one
    * `window` value; the Neptune load manifest states this (`coauthoredWith`).
    */
  def graphEdges(c: Canonical.Result, coauthorEdges: DataFrame, run: RunInfo): DataFrame =
    val authored = edgeRows(
      c.authored,
      GraphLabels.Authored,
      col("researcherId"),
      col("workId"),
      col("mentionId"),
      col("mentionId"),
      run,
      "position" -> col("position"),
      "teamSize" -> col("teamSize"),
      "year" -> col("year"),
      "mentionIds" -> array_join(col("mentionIds"), "|")
    )
    val coauthored = edgeRows(
      coauthorEdges,
      GraphLabels.CoauthoredWith,
      col("researcherA"),
      col("researcherB"),
      col("edgeId"),
      col("edgeId"),
      run,
      "coauthorEdgeId" -> col("edgeId"),
      "window" -> col("windowLabel"),
      "jointPublicationCount" -> col("jointPublicationCount"),
      "firstCollaborationYear" -> col("firstCollaborationYear"),
      "lastCollaborationYear" -> col("lastCollaborationYear"),
      "representativeWorkIds" -> array_join(col("representativeWorkIds"), "|")
    )
    val topics = c.works
      .select(col("workId"), explode(col("topicIds")).as("topicId"))
      .join(c.topics.select("topicId"), "topicId")
    val venues = c.works
      .select(col("workId"), explode(col("venueIds")).as("venueId"))
      .join(c.venues.select("venueId"), "venueId")
    authored
      .unionByName(coauthored)
      .unionByName(
        edgeRows(
          topics,
          GraphLabels.HasTopic,
          col("workId"),
          col("topicId"),
          lit(""),
          col("workId"),
          run
        )
      )
      .unionByName(
        edgeRows(
          venues,
          GraphLabels.PublishedIn,
          col("workId"),
          col("venueId"),
          lit(""),
          col("workId"),
          run
        )
      )
      .unionByName(
        edgeRows(
          c.affiliations,
          GraphLabels.AffiliatedWith,
          col("researcherId"),
          col("institutionId"),
          lit(""),
          col("researcherId"),
          run,
          "supportingMentionCount" -> col("supportingMentionCount")
        )
      )

  // Evidence tables ----------------------------------------------------------------------------

  /** Components of one window with their sampled distance statistics. */
  def components(r: WindowAnalytics.Result, run: RunInfo, uri: String): DataFrame =
    val w = r.window
    val t = EvidenceType.COMPONENT_RECORD
    r.components
      .join(r.distanceStats.drop("window", "windowLabel"), Seq("componentId"), "left")
      .select(
        (Evidence.item(
          t,
          Evidence.id(t, col("componentId")),
          col("componentId"),
          Cols.emptyStrings,
          lit(uri),
          Seq(
            "componentId" -> col("componentId"),
            "componentSize" -> col("componentSize"),
            "fractionOfResearchers" -> col("fraction"),
            "window" -> lit(w.label),
            "method" -> lit("hash-min label propagation"),
            "sampledSources" -> col("sampledSources"),
            "sampledPairs" -> col("sampledPairs"),
            "averagePathLength" -> col("averagePathLength"),
            "effectiveDiameter" -> col("effectiveDiameter"),
            "maxObservedDistance" -> col("maxObservedDistance"),
            "distanceMethod" -> when(
              col("sampledSources").isNotNull,
              lit("BFS from sampled sources (betweennessSampleSources, betweennessSeed)")
            )
          ),
          run,
          run.graph.policyVersion
        ) :+ lit(w.label).as("windowLabel"))*
      )

  /** Communities of one window and its cross-community edges, as two record kinds. */
  def communities(r: WindowAnalytics.Result, run: RunInfo, uri: String): DataFrame =
    val w = r.window
    val g = run.graph
    val t = EvidenceType.COMMUNITY_RECORD
    val converged = r.report.value.get("communitiesConverged").map(_.bool)
    val community = r.communities.select(
      (Evidence.item(
        t,
        Evidence.id(t, col("communityId")),
        col("communityId"),
        Cols.emptyStrings,
        lit(uri),
        Seq(
          "recordKind" -> lit("community"),
          "communityId" -> col("communityId"),
          "communitySize" -> col("communitySize"),
          "internalEdgeCount" -> col("internalEdgeCount"),
          "crossCommunityEdgeCount" -> col("crossCommunityEdgeCount"),
          "window" -> lit(w.label),
          "method" -> lit("weighted synchronous label propagation"),
          "maxIterations" -> lit(g.communityIterations),
          "converged" -> converged.fold(Cols.nullString)(b => lit(b))
        ),
        run,
        g.policyVersion
      ) ++ Seq(lit(w.label).as("windowLabel"), lit("community").as("recordKind")))*
    )
    val cross = r.crossCommunityEdges.select(
      (Evidence.item(
        t,
        Evidence.id(t, concat_ws("|", lit("cross"), col("edgeId"))),
        col("edgeId"),
        array(col("researcherA"), col("researcherB")),
        lit(uri),
        Seq(
          "recordKind" -> lit("cross_community_edge"),
          "edgeId" -> col("edgeId"),
          "communityA" -> col("communityA"),
          "communityB" -> col("communityB"),
          "jointPublicationCount" -> col("jointPublicationCount"),
          "window" -> lit(w.label)
        ),
        run,
        g.policyVersion
      ) ++ Seq(lit(w.label).as("windowLabel"), lit("cross_community_edge").as("recordKind")))*
    )
    community.unionByName(cross)

  /** Adds `pathEvidenceId` (GRAPH_PATH evidence ID of key `path|src|tgt|window|policy`). */
  def withPathIds(paths: DataFrame, w: TimeWindow, policy: String): DataFrame =
    paths.withColumn(
      "pathEvidenceId",
      Evidence.id(
        EvidenceType.GRAPH_PATH,
        concat_ws("|", lit("path"), col("src"), col("tgt"), lit(w.label), lit(policy))
      )
    )

  /** Path evidence of one window (found and not found pairs). */
  def pathEvidence(paths: DataFrame, w: TimeWindow, run: RunInfo, uri: String): DataFrame =
    paths.select(
      (Evidence.item(
        EvidenceType.GRAPH_PATH,
        col("pathEvidenceId"),
        concat_ws("|", col("src"), col("tgt")),
        array(col("src"), col("tgt")),
        lit(uri),
        Seq(
          "source" -> col("src"),
          "target" -> col("tgt"),
          "found" -> col("found"),
          "distance" -> col("distance"),
          "shortestPathCount" -> col("shortestPathCount"),
          "hopEdgeIds" -> array_join(col("hopEdgeIds"), "|"),
          "maxDepth" -> col("maxDepth"),
          "window" -> lit(w.label),
          "selection" -> lit(
            "sampled sources (betweennessSampleSources, betweennessSeed), targets hashed from " +
              "each source's reach within pathMaxDepth"
          )
        ),
        run,
        run.graph.policyVersion,
        graphPath = col("path")
      ) :+ lit(w.label).as("windowLabel"))*
    )

  /** Job 9 comparison rows of one window pair as METRIC_RECORD evidence. */
  def comparisons(df: DataFrame, c: Temporal.Comparison, run: RunInfo, uri: String): DataFrame =
    val g = run.graph
    val t = EvidenceType.METRIC_RECORD
    val label = s"${c.from.label}_to_${c.to.label}"
    val fields = df.columns.filterNot(_ == "researcherId").toSeq
    df.select(
      (Evidence.item(
        t,
        Evidence.id(
          t,
          concat_ws(
            "|",
            lit("temporal"),
            col("researcherId"),
            lit(c.from.label),
            lit(c.to.label),
            lit(g.metricVersion)
          )
        ),
        col("researcherId"),
        array(col("researcherId")),
        lit(uri),
        fields.map(f => f -> col(f)) :+ ("limitations" -> lit(c.limitations.mkString("; "))),
        run,
        g.metricVersion
      ) :+ lit(label).as("comparison"))*
    )

  // Load manifests -----------------------------------------------------------------------------

  /** Coauthor windows of the export. `listed`: the configured windows, then any exported window the
    * configuration does not name (sorted), so every exported window is listed. `exported`: the
    * windows of the `coauthor-edges` partitions with their row counts. `mismatched`: windows whose
    * `coauthor-edges` row count differs from the COAUTHORED_WITH graph-edge count of that window
    * (`graphEdgesByWindow`, by the edge's `window` property); it must be empty.
    */
  final case class CoauthorWindows(
      listed: Seq[String],
      exported: Map[String, Long],
      graphEdges: Map[String, Long],
      mismatched: Seq[String]
  )

  def coauthorWindows(
      coauthorEdges: Table,
      graphEdgesByWindow: Map[String, Long],
      configured: Seq[String]
  ): CoauthorWindows =
    val prefix = "windowLabel="
    val exported = coauthorEdges.byPartition.collect {
      case (k, n) if k.startsWith(prefix) && n > 0 => k.stripPrefix(prefix) -> n
    }
    val extra = (exported.keySet ++ graphEdgesByWindow.keySet).diff(configured.toSet).toSeq.sorted
    val mismatched = (exported.keySet ++ graphEdgesByWindow.keySet).toSeq.sorted.filter(w =>
      exported.getOrElse(w, 0L) != graphEdgesByWindow.getOrElse(w, 0L)
    )
    CoauthorWindows(configured ++ extra, exported, graphEdgesByWindow, mismatched)

  /** Load manifests for the four targets, by table name. Target names (tables, indexes, buckets)
    * are named by their config keys; values are resolved by the loaders at load time. Every table a
    * loader reads carries its JSON-lines copy (`jsonLines`: path, files, counts, core record and
    * extra fields).
    */
  def loadManifests(
      tables: Map[String, Table],
      runId: String,
      snapshot: String,
      vectorIndexes: Map[String, String],
      dimension: Int,
      windows: CoauthorWindows,
      runManifestPath: String,
      runSummaryPath: String
  ): Map[String, ujson.Obj] =
    def head = ujson.Obj("runId" -> runId, "snapshot" -> snapshot)
    def t(n: String) = tables(n).json
    def counts(m: Map[String, Long]) =
      ujson.Obj.from(m.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble)))
    val neptune = head
    neptune("target") = "neptune"
    neptune("endpointConfigKey") = "neptune.endpoint"
    neptune("nodes") = t("graph-nodes")
    neptune("edges") = t("graph-edges")
    neptune("coauthorEdges") = t("coauthor-edges")
    neptune("note") =
      "Parquet and JSON lines in core GraphNode, GraphEdge and CoauthorEdge shape; the loader converts them to the Neptune bulk-load format"
    neptune("coauthoredWith") = ujson.Obj(
      "scope" -> "one edge per researcher pair per window",
      "windowProperty" -> "window",
      "windows" -> ujson.Arr(windows.listed.map(ujson.Str(_))*),
      "exportedWindows" -> counts(windows.exported),
      "graphEdgesByWindow" -> counts(windows.graphEdges),
      "windowsNote" -> "windows: every configured window and every window the export holds; exportedWindows: coauthor-edges rows per window that holds edges",
      "rule" -> "every query over COAUTHORED_WITH filters on exactly one window value"
    )
    val dynamo = head
    dynamo("target") = "dynamodb"
    dynamo("tables") = ujson.Arr(
      ujson.Obj(
        "tableConfigKey" -> "dynamo.researcherMetricTable",
        "source" -> t("researcher-metrics"),
        "partitionKey" -> "researcherId",
        "sortKey" -> "windowLabel"
      )
    )
    val vectors = head
    vectors("target") = "s3vectors"
    vectors("vectorBucketConfigKey") = "vectors.vectorBucket"
    vectors("dimension") = dimension
    vectors("source") = t("semantic-summaries")
    vectors("keyField") = "chunkId"
    vectors("textField") = "text"
    vectors("indexes") = ujson.Arr(
      Summaries.IndexKeys.toSeq.sortBy(_._1).map { (docType, key) =>
        ujson.Obj(
          "docType" -> docType,
          "indexConfigKey" -> s"vectors.indexes.$key",
          "index" -> vectorIndexes(docType),
          "rows" -> tables("semantic-summaries").byPartition
            .collect { case (k, v) if k.startsWith(s"docType=$docType/") => v }
            .sum
            .toDouble
        )
      }*
    )
    vectors("abstracts") = ujson.Obj(
      "docType" -> Summaries.PaperAbstract,
      "indexConfigKey" -> s"vectors.indexes.${Summaries.AbstractIndexKey}",
      "index" -> vectorIndexes(Summaries.PaperAbstract),
      "source" -> t("paper-abstracts"),
      "keyRule" -> ("rag's renderer (behind storage's DocumentRenderer port) makes the chunk IDs, " +
        "not Spark: it renders template tpl-paper-abstract-v2 and uses that source version " +
        "with the rendered text; one chunk is core Ids.chunk(workId, tpl-paper-abstract-v2, " +
        "paper-abstract, 0, rendered text); a long abstract split into several chunks gives " +
        "chunk i Ids.chunk(workId, tpl-paper-abstract-v2+<chunk policy version>+<sha256 of " +
        "the whole rendered text>, paper-abstract, i, chunk text)"),
      "textField" -> "text",
      "window" -> "none: paper-abstract documents have no window",
      "rows" -> tables("paper-abstracts").rows.toDouble
    )
    val s3 = head
    s3("target") = "s3"
    s3("bucketConfigKey") = "storage.graphBucket"
    s3("tables") = ujson.Arr(tables.toSeq.sortBy(_._1).map((_, x) => x.json)*)
    s3("runManifest") = runManifestPath
    s3("runSummary") = runSummaryPath
    Map("neptune" -> neptune, "dynamodb" -> dynamo, "s3vectors" -> vectors, "s3" -> s3)
