package scholarchain.spark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue

import org.apache.spark.scheduler.*
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.ui.{
  SparkListenerSQLAdaptiveExecutionUpdate,
  SparkListenerSQLExecutionStart
}
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*
import scholarchain.core.ConfigLoader

/** Records what a local pipeline run did, from Spark listener events (not the UI): per stage the
  * pipeline stage that submitted it (the [[SparkPipeline.StageProperty]] local property), task
  * count and shuffle read and write; per SQL execution its call site and formatted physical plan
  * (`spark.sql.ui.explainMode` is `formatted` by default; the final adaptive plan when AQE
  * re-planned).
  */
final class CaptureListener extends SparkListener:
  final case class StageRow(
      stageId: Int,
      phase: String,
      name: String,
      tasks: Int,
      shuffleReadBytes: Long,
      shuffleReadRecords: Long,
      shuffleWriteBytes: Long,
      shuffleWriteRecords: Long,
      inputRecords: Long,
      outputRecords: Long,
      runMs: Long
  )
  final case class Sql(id: Long, description: String, details: String, plan: String)

  val stagePhase: TrieMap[Int, String] = TrieMap.empty
  val executionPhase: TrieMap[Long, String] = TrieMap.empty
  val stages: ConcurrentLinkedQueue[StageRow] = ConcurrentLinkedQueue()
  val sql: TrieMap[Long, Sql] = TrieMap.empty
  val finalPlan: TrieMap[Long, String] = TrieMap.empty

  override def onJobStart(e: SparkListenerJobStart): Unit =
    val props = Option(e.properties)
    val phase =
      props.flatMap(p => Option(p.getProperty(SparkPipeline.StageProperty))).getOrElse("(none)")
    e.stageIds.foreach(id => stagePhase.update(id, phase))
    props
      .flatMap(p => Option(p.getProperty("spark.sql.execution.id")))
      .flatMap(_.toLongOption)
      .foreach(x => executionPhase.putIfAbsent(x, phase))

  override def onStageCompleted(e: SparkListenerStageCompleted): Unit =
    val i = e.stageInfo
    val m = Option(i.taskMetrics)
    def g(f: org.apache.spark.executor.TaskMetrics => Long) = m.map(f).getOrElse(0L)
    stages.add(
      StageRow(
        i.stageId,
        stagePhase.getOrElse(i.stageId, "(none)"),
        i.name,
        i.numTasks,
        g(_.shuffleReadMetrics.totalBytesRead),
        g(_.shuffleReadMetrics.recordsRead),
        g(_.shuffleWriteMetrics.bytesWritten),
        g(_.shuffleWriteMetrics.recordsWritten),
        g(_.inputMetrics.recordsRead),
        g(_.outputMetrics.recordsWritten),
        g(_.executorRunTime)
      )
    )

  override def onOtherEvent(e: SparkListenerEvent): Unit = e match
    case s: SparkListenerSQLExecutionStart =>
      sql.update(
        s.executionId,
        Sql(s.executionId, s.description, s.details, s.physicalPlanDescription)
      )
    case u: SparkListenerSQLAdaptiveExecutionUpdate =>
      finalPlan.update(u.executionId, u.physicalPlanDescription)
    case _ => ()

/** Local integration run of the whole pipeline (jobs 1 to 11) on Phase 04 and Phase 05 outputs:
  * local[2], 4 shuffle partitions, Spark UI off, reliable checkpoints under the output folder.
  *
  * Usage (sbt): `sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture <dir>
  * --hadoop-manifest <...> --er-manifest <...> --output <dir> [--checkpoint-dir <dir>]`.
  *
  * Writes into `--capture`: `stages.tsv` (one row per completed stage), `phases.tsv` (totals per
  * pipeline stage), `plans.txt` (formatted physical plan of the first SQL execution of every
  * distinct pipeline stage and call site) and `plans-index.tsv`.
  */
object LocalPipelineRun:
  /** The first frame in scholarchain.spark outside the lineage helper: the pipeline line that ran
    * the query.
    */
  def callSite(details: String): String =
    val frame = """^\s*(?:at\s+)?scholarchain\.spark\.[^(]*\(([A-Za-z]+\.scala:\d+)\)""".r
    details.linesIterator
      .flatMap(l => frame.findFirstMatchIn(l).map(_.group(1)))
      .find(f => !f.startsWith("Support.scala"))
      .getOrElse("(unknown)")

  def main(args: Array[String]): Unit =
    val i = args.indexOf("--capture")
    require(i >= 0 && i + 1 < args.length, "--capture <dir> is required")
    val capture = Path.of(args(i + 1))
    val a = SparkArgs
      .parse(args.patch(i, Nil, 2).toSeq)
      .fold(e => throw new IllegalArgumentException(e), identity)
    val cfg = ConfigLoader.load().fold(e => throw new IllegalArgumentException(e), identity)
    val spark = SparkSession
      .builder()
      .master("local[2]")
      .appName("scholarchain-spark-local-run")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.cleaner.referenceTracking.cleanCheckpoints", "true")
      .getOrCreate()
    spark.sparkContext.setCheckpointDir(
      a.checkpointDir.getOrElse(s"${a.output.stripSuffix("/")}/_checkpoints")
    )
    val listener = CaptureListener()
    spark.sparkContext.addSparkListener(listener)
    val outcome =
      try SparkPipeline.run(spark, a, cfg)
      finally spark.stop() // drains the listener bus, so every event below has been delivered
    Files.createDirectories(capture)
    def put(name: String, lines: Iterable[String]): Unit =
      Files.write(capture.resolve(name), lines.mkString("", "\n", "\n").getBytes(UTF_8))

    val rows = listener.stages.asScala.toVector.sortBy(_.stageId)
    put(
      "stages.tsv",
      ("stageId\tphase\ttasks\tshuffleReadBytes\tshuffleReadRecords\tshuffleWriteBytes\t" +
        "shuffleWriteRecords\tinputRecords\toutputRecords\trunMs\tname") +:
        rows.map(r =>
          Seq(
            r.stageId,
            r.phase,
            r.tasks,
            r.shuffleReadBytes,
            r.shuffleReadRecords,
            r.shuffleWriteBytes,
            r.shuffleWriteRecords,
            r.inputRecords,
            r.outputRecords,
            r.runMs,
            r.name
          ).mkString("\t")
        )
    )
    val order = rows.map(_.phase).distinct
    put(
      "phases.tsv",
      ("phase\tstages\ttasks\tminTasks\tmaxTasks\tshuffleReadBytes\tshuffleReadRecords\t" +
        "shuffleWriteBytes\tshuffleWriteRecords\trunMs") +:
        order.map { p =>
          val rs = rows.filter(_.phase == p)
          Seq(
            p,
            rs.size,
            rs.map(_.tasks).sum,
            rs.map(_.tasks).min,
            rs.map(_.tasks).max,
            rs.map(_.shuffleReadBytes).sum,
            rs.map(_.shuffleReadRecords).sum,
            rs.map(_.shuffleWriteBytes).sum,
            rs.map(_.shuffleWriteRecords).sum,
            rs.map(_.runMs).sum
          ).mkString("\t")
        }
    )
    val executions = listener.sql.values.toVector.sortBy(_.id)
    val firsts = executions
      .map(s => (listener.executionPhase.getOrElse(s.id, "(none)"), callSite(s.details), s))
      .groupBy(x => (x._1, x._2))
      .values
      .map(_.minBy(_._3.id))
      .toVector
      .sortBy(_._3.id)
    put(
      "plans-index.tsv",
      "executionId\tphase\tcallSite\texchanges\tdescription" +: firsts.map { (p, c, s) =>
        val plan = listener.finalPlan.getOrElse(s.id, s.plan)
        val tree = plan.split("\n\n\n").headOption.getOrElse(plan)
        Seq(s.id, p, c, "Exchange".r.findAllIn(tree).size, s.description).mkString("\t")
      }
    )
    put(
      "plans.txt",
      firsts.map { (p, c, s) =>
        s"===== execution ${s.id} | stage $p | $c | ${s.description}\n" +
          listener.finalPlan.getOrElse(s.id, s.plan)
      }
    )
    outcome match
      case Left(e) =>
        System.err.println(s"upstream error: $e")
        sys.exit(2)
      case Right(o) =>
        put(
          "outcome.txt",
          Seq(o.runId, o.base, o.manifestPath, o.summaryPath, o.invariantFailures.toString)
        )
        println(s"[scholarchain] local run ${o.runId}: ${o.invariantFailures} invariant failures")
        println(s"[scholarchain] summary ${o.summaryPath}")
