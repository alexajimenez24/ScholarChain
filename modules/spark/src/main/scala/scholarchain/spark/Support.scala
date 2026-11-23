package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{ArrayType, StringType}
import org.apache.spark.storage.StorageLevel
import scholarchain.core.{GraphConfig, TimeWindow}

/** What every Spark output row is stamped with: the Spark run ID, the dataset snapshot and the
  * graph section of `scholarchain-v1.yaml` (policy and metric versions, windows and every limit).
  */
final case class RunInfo(runId: String, snapshot: String, graph: GraphConfig)

/** Lineage truncation for iterative algorithms.
  *
  * When the SparkContext has a checkpoint directory (the driver requires `--checkpoint-dir` on HDFS
  * or S3 for every non-local master), [[cut]] writes a reliable checkpoint: the rows survive
  * executor loss and the plan restarts from the files. Without one (local masters only) it falls
  * back to a local checkpoint (executor block storage, not fault tolerant), which is enough for a
  * single-JVM run. Either way the logical plan of every iteration starts from materialized rows, so
  * plans stay shallow and the analyzer cost does not grow with the iteration count.
  */
object Lineage:
  def reliable(df: DataFrame): Boolean = df.sparkSession.sparkContext.getCheckpointDir.isDefined

  def cut(df: DataFrame): DataFrame =
    if reliable(df) then
      // Persist first so the checkpoint write does not recompute the plan a second time.
      val cached = df.persist(StorageLevel.MEMORY_AND_DISK)
      val out = cached.checkpoint(eager = true)
      cached.unpersist(blocking = false)
      out
    else df.localCheckpoint(eager = true)

/** Column helpers shared by the jobs. */
object Cols:
  val emptyStrings: Column = array().cast(ArrayType(StringType))
  val nullString: Column = lit(null).cast(StringType)

  /** First `n` hex characters of SHA-256 over the UTF-8 text: the core `Hashing.short` formula. */
  def shortHash(text: Column, n: Int): Column = substring(sha2(text, 256), 1, n)

  /** Rows whose `year` lies in the inclusive window; a null year is in no window. */
  def inWindow(year: Column, w: TimeWindow): Column =
    year.isNotNull && year.between(w.startYear, w.endYear)

  def windowStruct(w: TimeWindow): Column =
    struct(lit(w.startYear).as("startYear"), lit(w.endYear).as("endYear"))

  /** core `Provenance` shape. `schemaVersion` is null for Spark tables that have no core record yet
    * (components, communities, path evidence, temporal comparisons).
    */
  def provenance(
      run: RunInfo,
      source: Column,
      sourceRecordId: Column,
      schemaVersion: Option[String],
      policyVersion: String
  ): Column = provenance(run, source, sourceRecordId, schemaVersion, lit(policyVersion))

  /** As above, with the policy version taken from a column (for example the identity policy carried
    * by a canonical researcher or work row).
    */
  def provenance(
      run: RunInfo,
      source: Column,
      sourceRecordId: Column,
      schemaVersion: Option[String],
      policyVersion: Column
  ): Column =
    struct(
      source.as("source"),
      sourceRecordId.as("sourceRecordId"),
      lit(run.snapshot).as("snapshot"),
      lit(run.runId).as("runId"),
      schemaVersion.fold(nullString)(v => lit(v)).as("schemaVersion"),
      policyVersion.as("policyVersion")
    )

/** Run-report helpers: counts and invariant failures go into a JSON object the driver writes. */
object Report:
  def put(obj: ujson.Obj, key: String, n: Long): Unit = obj(key) = ujson.Num(n.toDouble)
  def putD(obj: ujson.Obj, key: String, x: Double): Unit = obj(key) = ujson.Num(x)
  def putB(obj: ujson.Obj, key: String, b: Boolean): Unit = obj(key) = ujson.Bool(b)
  def putS(obj: ujson.Obj, key: String, s: String): Unit = obj(key) = ujson.Str(s)
