package scholarchain.spark

import java.nio.file.Files

import org.apache.spark.sql.{DataFrame, Encoders, Row, SparkSession}
import org.apache.spark.sql.types.*
import scala.jdk.CollectionConverters.*
import scholarchain.core.{GraphConfig, TimeWindow}

/** One local SparkSession for every Spark suite: local[2], 4 shuffle partitions, UI off, and a
  * checkpoint directory so the iterative algorithms run the reliable-checkpoint path used on a
  * cluster.
  */
object LocalSpark:
  lazy val spark: SparkSession =
    val s = SparkSession
      .builder()
      .master("local[2]")
      .appName("scholarchain-spark-tests")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.cleaner.referenceTracking.cleanCheckpoints", "true")
      .getOrCreate()
    s.sparkContext.setCheckpointDir(Files.createTempDirectory("scholarchain-spark-ckpt").toString)
    s

  /** The graph section of scholarchain-v1.yaml as loaded by the core loader (no defaults in code).
    */
  lazy val graphConfig: GraphConfig =
    scholarchain.core.ConfigLoader.load(Map.empty).fold(e => throw new AssertionError(e), _.graph)

  def runInfo(g: GraphConfig = graphConfig): RunInfo =
    RunInfo("run-0123456789abcdef", "2026-11-22", g)

  val All: TimeWindow = TimeWindow(1950, 2026)

  def frame(schema: StructType, rows: Seq[Row]): DataFrame =
    spark.createDataFrame(rows.asJava, schema)

  def nodes(ids: String*): DataFrame =
    frame(StructType(Seq(StructField("id", StringType))), ids.map(Row(_)))

  /** Undirected weighted edges (a < b enforced here). */
  def edges(es: (String, String, Long)*): DataFrame =
    frame(
      StructType(
        Seq(StructField("a", StringType), StructField("b", StringType), StructField("w", LongType))
      ),
      es.map((x, y, w) => Row(if x < y then x else y, if x < y then y else x, w))
    )

  def lines(ls: Seq[String]): org.apache.spark.sql.Dataset[String] =
    spark.createDataset(ls.asJava)(Encoders.STRING)

  def asMap(df: DataFrame, k: String, v: String): Map[String, Any] =
    df.select(k, v).collect().map(r => r.getString(0) -> r.get(1)).toMap
