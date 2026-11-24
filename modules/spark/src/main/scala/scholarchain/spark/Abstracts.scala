package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*

/** Paper-abstract rows for the `paper-abstract-v1` vector index (table `paper-abstracts`).
  *
  * Source: only the abstracts the Phase 04 Hadoop run wrote (`<J02-OPENALEX output>/abstracts`,
  * hadoop `AbstractRecord`, abstract-v1), located through the Hadoop run summary. No text is made,
  * changed or shortened here: every emitted `text` and `textHash` is the source row's, byte for
  * byte. When the Hadoop run has no OpenAlex stage or wrote no abstracts, no row is emitted and the
  * report says so (`inputPresent` false or `read` 0).
  *
  * Row shape: the storage loader's `PaperAbstract` (`workId`, `textHash`, `text`, `provenance`),
  * with two visible extra fields:
  *   - `workId` is the canonical work of job 1 (the graph `Work` node ID), reached from the source
  *     work through the er-v1 work map;
  *   - `provenance`: the source dataset record (`source`, `sourceRecordId` of the Hadoop row), the
  *     snapshot, this Spark run ID, schema abstract-v1 and the identity policy that chose the
  *     canonical work;
  *   - extras `sourceWorkId` (the source work ID, the Hadoop row's `workId`) and `upstreamRunId`
  *     (the Hadoop run that wrote the row).
  *
  * Checks, each a counted reject (stage `spark-abstracts`), never a repair:
  *   - `ABSTRACT_EMPTY`: blank text;
  *   - `ABSTRACT_HASH_MISMATCH`: `textHash` is not `sha256:` + hex SHA-256 of the text;
  *   - `ABSTRACT_WORK_NOT_FOUND`: the source work is not in the J07 export;
  *   - `ABSTRACT_REF_MISMATCH`: the export work's `abstractTextRef` is not this `textHash`;
  *   - `ABSTRACT_WORK_NOT_CANONICAL`: the source work has no canonical work;
  *   - `ABSTRACT_SUPERSEDED_SAME_WORK`: one abstract per canonical work is kept, the one of the
  *     smallest source work ID; the other abstracts of a merged work are not emitted.
  */
object Abstracts:
  val SchemaVersion = "abstract-v1"
  val Stage = "spark-abstracts"

  /** Fields of a `paper-abstracts` row that the core-shaped `PaperAbstract` record does not have.
    */
  val ExtraFields: Seq[String] = Seq("sourceWorkId", "upstreamRunId")

  final case class Result(rows: DataFrame, rejects: DataFrame, report: ujson.Obj)

  /** `abstracts`: valid Hadoop abstract rows (Schemas.abstractRecord); `sourceWorks`: the J07
    * export works (`workId`, `abstractTextRef`); `workMap`: job 1 `memberWorkId` to canonical
    * `workId`. `inputPresent` is false when the Hadoop run has no abstracts stage.
    */
  def build(
      abstracts: DataFrame,
      sourceWorks: DataFrame,
      workMap: DataFrame,
      run: RunInfo,
      identityPolicy: String,
      inputPresent: Boolean
  ): Result =
    val refs = sourceWorks
      .groupBy(col("workId").as("sourceWorkId"))
      .agg(collect_set(col("abstractTextRef")).as("refs"))
    val canonical =
      workMap.select(col("memberWorkId").as("sourceWorkId"), col("workId").as("canonicalWorkId"))
    val reason =
      when(trim(coalesce(col("text"), lit(""))) === "", lit("ABSTRACT_EMPTY"))
        .when(
          !(col("textHash") <=> concat(lit("sha256:"), sha2(col("text"), 256))),
          lit("ABSTRACT_HASH_MISMATCH")
        )
        .when(col("refs").isNull, lit("ABSTRACT_WORK_NOT_FOUND"))
        .when(!array_contains(col("refs"), col("textHash")), lit("ABSTRACT_REF_MISMATCH"))
        .when(col("canonicalWorkId").isNull, lit("ABSTRACT_WORK_NOT_CANONICAL"))
    val checked = abstracts
      .select(
        col("workId").as("sourceWorkId"),
        col("textHash"),
        col("text"),
        col("provenance")
      )
      .join(refs, Seq("sourceWorkId"), "left")
      .join(canonical, Seq("sourceWorkId"), "left")
      .withColumn("reason", reason)
    // Valid rows first (false sorts before true), so a rejected row never outranks a valid one.
    val byWork =
      Window.partitionBy("canonicalWorkId").orderBy(col("reason").isNotNull, col("sourceWorkId"))
    val ranked = Lineage.cut(
      checked
        .withColumn(
          "rank",
          when(col("reason").isNull, row_number().over(byWork)).otherwise(lit(null).cast("int"))
        )
        .withColumn(
          "reason",
          when(col("rank") > 1, lit("ABSTRACT_SUPERSEDED_SAME_WORK")).otherwise(col("reason"))
        )
        .drop("refs")
    )
    val rows = ranked
      .filter(col("reason").isNull)
      .select(
        col("canonicalWorkId").as("workId"),
        col("textHash"),
        col("text"),
        Cols
          .provenance(
            run,
            col("provenance.source"),
            col("provenance.sourceRecordId"),
            Some(SchemaVersion),
            lit(identityPolicy)
          )
          .as("provenance"),
        col("sourceWorkId"),
        col("provenance.runId").as("upstreamRunId")
      )
    val rejects = ranked
      .filter(col("reason").isNotNull)
      .select(
        to_json(struct(col("sourceWorkId"), col("textHash"))).as("input"),
        col("reason")
      )

    val report = ujson.Obj()
    Report.putB(report, "inputPresent", inputPresent)
    val read = ranked.count()
    val emitted = rows.count()
    Report.put(report, "read", read)
    Report.put(report, "emitted", emitted)
    val byReason = ranked
      .filter(col("reason").isNotNull)
      .groupBy("reason")
      .count()
      .collect() // at most six reason codes
      .map(r => r.getString(0) -> r.getLong(1))
      .toMap
    val rejected = byReason.values.sum
    Report.put(report, "notEmitted", rejected)
    for code <- Seq(
        "ABSTRACT_EMPTY",
        "ABSTRACT_HASH_MISMATCH",
        "ABSTRACT_WORK_NOT_FOUND",
        "ABSTRACT_REF_MISMATCH",
        "ABSTRACT_WORK_NOT_CANONICAL",
        "ABSTRACT_SUPERSEDED_SAME_WORK"
      )
    do Report.put(report, s"notEmitted_$code", byReason.getOrElse(code, 0L))
    // Integrity: every abstract read is emitted or rejected, and one row per canonical work.
    Report.put(report, "invariant_abstractsUnaccounted", math.abs(read - emitted - rejected))
    Report.put(
      report,
      "invariant_abstractDuplicateWorkIds",
      emitted - rows.select("workId").distinct().count()
    )
    Result(rows, rejects, report)
