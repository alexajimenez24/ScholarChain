package scholarchain.storage

import scholarchain.core.*
import upickle.default.ReadWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Try

// ------------------------------------------------------------------ input rows

/** Where a loader's typed rows come from. The Phase 06 export is Parquet and `storage` has no
  * Parquet reader (decision of the T2 gap), so every loader reads typed core rows through this
  * interface. A `Left` is a row that could not be decoded; the loader records it as rejected. Local
  * implementations: in-memory rows and JSON lines through core [[JsonLines]].
  */
trait RowSource[A]:
  def rows: Iterator[Either[String, A]]

  /** Why the input as a whole cannot be read (a missing directory), as opposed to a bad row. Every
    * loader fails its stage on it before anything is written or marked Succeeded, whatever the
    * expected row count says. `None` with no rows is a legitimately empty input.
    */
  def problem: Option[String] = None

  def mapE[B](f: A => Either[String, B]): RowSource[B] =
    val self = this
    new RowSource[B]:
      def rows: Iterator[Either[String, B]] = self.rows.map(_.flatMap(f))
      override def problem: Option[String] = self.problem

object RowSource:
  def of[A](xs: Iterable[A]): RowSource[A] =
    new RowSource[A]:
      def rows: Iterator[Either[String, A]] = xs.iterator.map(Right(_))

  /** The rows of `a` followed by the rows of `b`, mapped to one type. */
  def concat[A, B, C](a: RowSource[A], b: RowSource[B])(fa: A => C, fb: B => C): RowSource[C] =
    new RowSource[C]:
      def rows: Iterator[Either[String, C]] =
        a.rows.map(_.map(fa)) ++ b.rows.map(_.map(fb))
      override def problem: Option[String] = a.problem.orElse(b.problem)

  /** Keeps the rows `keep` accepts; a row that failed to decode is always kept (it is a rejection
    * to report, not data to select).
    */
  def filter[A](src: RowSource[A])(keep: A => Boolean): RowSource[A] =
    new RowSource[A]:
      def rows: Iterator[Either[String, A]] = src.rows.filter(_.fold(_ => true, keep))
      override def problem: Option[String] = src.problem

  /** Strict JSON lines (blank lines skipped); `validate` is the core validator of the record.
    * `dropFields` names top-level fields of the line that are not part of the core record (the
    * Spark load manifest lists them as `extraFields`: partition columns and provenance helpers);
    * they are removed before the strict decode, which still refuses every other unknown field.
    */
  def jsonLines[A: ReadWriter](
      lines: => Iterator[String],
      validate: A => Validate.Errors = (_: A) => Vector.empty,
      dropFields: Set[String] = Set.empty
  ): RowSource[A] =
    def strip(l: String): Either[String, String] =
      if dropFields.isEmpty then Right(l)
      else
        Try {
          val v = ujson.read(l)
          v.obj.keysIterator.filter(dropFields).toVector.foreach(v.obj.remove(_))
          v.render()
        }.toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}".take(300))
    new RowSource[A]:
      def rows: Iterator[Either[String, A]] =
        lines
          .filter(_.trim.nonEmpty)
          .map(l => strip(l).flatMap(JsonLines.decodeValid[A](_, validate)))

  /** All `*.jsonl`, `*.json` and `*.jsonl.gz` files under a local directory, in path order. */
  def jsonLinesDir[A: ReadWriter](
      dir: Path,
      validate: A => Validate.Errors = (_: A) => Vector.empty,
      dropFields: Set[String] = Set.empty
  ): RowSource[A] =
    def lines: Iterator[String] =
      Files
        .walk(dir)
        .iterator
        .asScala
        .filter { p =>
          val n = p.getFileName.toString
          Files.isRegularFile(p) && (n.endsWith(".jsonl") || n.endsWith(".json") || n.endsWith(
            ".jsonl.gz"
          ))
        }
        .toVector
        .sorted
        .iterator
        .flatMap { p =>
          val in =
            if p.toString.endsWith(".gz") then new GZIPInputStream(Files.newInputStream(p))
            else Files.newInputStream(p)
          try new String(in.readAllBytes(), UTF_8).linesIterator.toVector
          finally in.close()
        }
    val jl = jsonLines[A](lines, validate, dropFields)
    new RowSource[A]:
      // A missing directory is an input problem, which fails a load stage. It also stays a visible
      // error row for a consumer that only reads rows: a typo must never read as empty.
      override def problem: Option[String] =
        Option.when(!Files.isDirectory(dir))(s"input directory $dir is missing")
      def rows: Iterator[Either[String, A]] =
        problem.fold(jl.rows)(p => Iterator.single(Left(p)))

// ------------------------------------------------------------------ ledger and reports

/** One counter of a load report; a sorted vector keeps the stored bytes deterministic. */
final case class ReportCount(name: String, value: Long) derives ReadWriter

/** The stored account of a load: what is present afterwards and what was rejected. It holds no
  * timestamp and no "written versus unchanged" split, so replaying the same input produces the same
  * bytes and the same object name (`Unchanged`); the effect split is in the in-memory result.
  */
final case class LoadReport(
    schemaVersion: String,
    loader: String,
    runId: String,
    logicVersion: String,
    counts: Vector[ReportCount],
    rejected: Vector[RejectRecord]
) derives ReadWriter

object LoadReport:
  val Version = "load-report-v1"

  def of(
      loader: String,
      runId: String,
      logicVersion: String,
      counts: Seq[(String, Long)],
      rejected: Seq[RejectRecord]
  ): LoadReport =
    LoadReport(
      Version,
      loader,
      runId,
      logicVersion,
      counts.sortBy(_._1).map((n, v) => ReportCount(n, v)).toVector,
      rejected.sortBy(r => (r.input, r.reason)).toVector
    )

/** Runs a loader as a [[RunLedger]] stage and stores its report.
  *
  * `start` is idempotent per (run, stage, logic version). A stage in `Failed` is retried (attempt +
  * 1). A stage already `Succeeded` stays `Succeeded`, but the loader body still runs: every loader
  * is idempotent, so a replay reads, finds nothing to change and writes nothing. The report is
  * stored under a content-hashed name, so an identical replay is `Unchanged` and leaves the ledger
  * as it is. A replay with a different report (it completed rows rejected before, say) records the
  * new report on the ledger ([[RunLedger.recordReport]]); the earlier report stays in
  * `priorOutputs`. A replay whose body fails changes nothing on a `Succeeded` stage.
  *
  * Several writers may share one stage (embedding workers draining one run): a version conflict on
  * the ledger entry is resolved by reading the entry again and applying the same step to it, at
  * most [[LoadStage.MaxLedgerAttempts]] times.
  */
final class LoadStage(ledger: RunLedger, writer: ArtifactWriter):
  final case class Outcome[A](
      value: A,
      report: StoredObject,
      reportOutcome: WriteOutcome,
      ledger: LedgerEntry,
      replay: Boolean
  )

  def run[A](runId: String, stage: String, logicVersion: String)(
      body: => Either[String, (A, LoadReport)]
  ): Either[String, Outcome[A]] =
    for
      started <- ledger.start(runId, stage, logicVersion).left.map(e => s"ledger: $e")
      (entry0, _) = started
      cur <- onLatest(runId, stage, entry0) { e =>
        if e.value.status == LedgerStatus.Failed then ledger.retry(e) else Right(e)
      }
      result <- body match
        case Left(err) =>
          if cur.value.status == LedgerStatus.Started then ledger.fail(cur, err)
          Left(err)
        case Right((value, report)) =>
          val bytes = upickle.default.write(report).getBytes(UTF_8)
          val name = s"load-report-${Hashing.sha256Hex(bytes).take(16)}.json"
          writer.write(Zone.Logs, stage, runId, name, bytes, "application/json") match
            case Left(e) =>
              val msg = s"report not stored: $e"
              if cur.value.status == LedgerStatus.Started then ledger.fail(cur, msg)
              Left(msg)
            case Right((obj, wrote)) =>
              val replay = cur.value.status != LedgerStatus.Started
              onLatest(runId, stage, cur)(record(_, obj.uri, obj.sha256))
                .map(v => Outcome(value, obj, wrote, v.value, replay))
    yield result

  private def record(
      e: Versioned[LedgerEntry],
      uri: String,
      sha: String
  ): Either[KvError, Versioned[LedgerEntry]] =
    e.value.status match
      case LedgerStatus.Started   => ledger.succeed(e, uri, sha)
      case LedgerStatus.Succeeded =>
        if e.value.outputSha256.contains(sha) then Right(e)
        else ledger.recordReport(e, uri, sha)
      // another writer's attempt failed meanwhile; this one completed
      case LedgerStatus.Failed => ledger.retry(e).flatMap(ledger.succeed(_, uri, sha))

  private def onLatest(runId: String, stage: String, from: Versioned[LedgerEntry])(
      step: Versioned[LedgerEntry] => Either[KvError, Versioned[LedgerEntry]]
  ): Either[String, Versioned[LedgerEntry]] =
    def go(e: Versioned[LedgerEntry], left: Int): Either[KvError, Versioned[LedgerEntry]] =
      step(e) match
        case Left(c: KvError.ConditionFailed) if left > 1 =>
          ledger.get(runId, stage).flatMap(_.toRight(c)).flatMap(go(_, left - 1))
        case other => other
    go(from, LoadStage.MaxLedgerAttempts).left.map(e => s"ledger: $e")

object LoadStage:
  val MaxLedgerAttempts = 10

// ------------------------------------------------------------------ load manifests

final case class VectorManifestIndex(docType: String, index: String, rows: Long)

/** The Spark `s3vectors.json` load manifest, as far as the loader needs it. */
final case class VectorManifest(
    runId: String,
    snapshot: String,
    dimension: Int,
    sourceRows: Long,
    indexes: Vector[VectorManifestIndex]
):
  /** Differences from the configuration: dimension, index names and row totals. */
  def problems(cfg: VectorConfig): Vector[String] =
    val configured = cfg.indexes.values.toSet
    Vector(
      Option.when(dimension != cfg.dimension)(
        s"manifest dimension $dimension differs from vectors.dimension ${cfg.dimension}"
      )
    ).flatten ++
      indexes
        .filterNot(i => configured(i.index))
        .map(i => s"manifest index ${i.index} is not configured") ++
      Option.when(indexes.map(_.rows).sum != sourceRows)(
        s"index rows ${indexes.map(_.rows).sum} differ from source rows $sourceRows"
      )

final case class DynamoManifestTable(
    tableConfigKey: String,
    sourceTable: String,
    recordSchema: String,
    rows: Long
)

/** The Spark `dynamodb.json` load manifest. */
final case class DynamoManifest(
    runId: String,
    snapshot: String,
    tables: Vector[DynamoManifestTable]
)

object LoadManifests:
  private def parse[A](what: String, json: String)(f: ujson.Value => A): Either[String, A] =
    Try(f(ujson.read(json))).toEither.left.map(e =>
      s"malformed $what manifest: ${e.getMessage.take(120)}"
    )

  def vectors(json: String): Either[String, VectorManifest] =
    parse("s3vectors", json) { v =>
      require(v("target").str == "s3vectors", "target is not s3vectors")
      VectorManifest(
        v("runId").str,
        v("snapshot").str,
        v("dimension").num.toInt,
        v("source")("rows").num.toLong,
        v("indexes").arr.toVector.map(i =>
          VectorManifestIndex(i("docType").str, i("index").str, i("rows").num.toLong)
        )
      )
    }.flatMap(m => Either.cond(Ids.isRunId(m.runId), m, s"manifest run ID ${m.runId} is invalid"))

  def dynamo(json: String): Either[String, DynamoManifest] =
    parse("dynamodb", json) { v =>
      require(v("target").str == "dynamodb", "target is not dynamodb")
      DynamoManifest(
        v("runId").str,
        v("snapshot").str,
        v("tables").arr.toVector.map(t =>
          DynamoManifestTable(
            t("tableConfigKey").str,
            t("source")("table").str,
            t("source")("recordSchema").str,
            t("source")("rows").num.toLong
          )
        )
      )
    }.flatMap(m => Either.cond(Ids.isRunId(m.runId), m, s"manifest run ID ${m.runId} is invalid"))

// ------------------------------------------------------------------ researcher metrics

/** What a loader changed, for the caller; the stored report holds only the replay-stable facts. */
final case class MetricsLoadResult(
    read: Long,
    created: Long,
    unchanged: Long,
    duplicatesDropped: Long,
    rejected: Vector[RejectRecord],
    report: StoredObject,
    reportOutcome: WriteOutcome,
    replay: Boolean
)

/** Loads the Spark `researcher-metrics` table into `ResearcherMetricCache`.
  *
  * Idempotency: the cache key is (researcher, metric version, window) and `put` is create-only with
  * a same-value check, so a replay is `Unchanged` per row and a changed value under the same metric
  * version is refused and rejected (a new algorithm needs a new `metricVersion`). Within one input
  * an identical duplicate row is dropped and counted; a conflicting duplicate rejects every copy.
  * `expectedRows` (the manifest row count) is checked before anything is written: a short input
  * fails the stage instead of loading a silent subset. So does an input problem such as a missing
  * directory ([[RowSource.problem]]).
  */
final class ResearcherMetricsLoader(
    metrics: ResearcherMetricCache,
    ledger: RunLedger,
    writer: ArtifactWriter
):
  val Stage = "load-researcher-metrics"
  val LogicVersion = "metrics-load-v1"
  private val stage = new LoadStage(ledger, writer)

  def load(
      runId: String,
      source: RowSource[ResearcherMetrics],
      expectedRows: Option[Long] = None
  ): Either[String, MetricsLoadResult] =
    stage
      .run(runId, Stage, LogicVersion) {
        val problem = source.problem
        val all = if problem.isDefined then Vector.empty else source.rows.toVector
        val rejects = Vector.newBuilder[RejectRecord]
        def reject(input: String, reason: String): Unit =
          rejects += RejectRecord.of(Stage, reason, input, runId)
        if problem.isDefined then Left(problem.get)
        else if expectedRows.exists(_ != all.size.toLong) then
          Left(s"expected ${expectedRows.get} rows, the source gave ${all.size}")
        else
          val decoded = all.zipWithIndex.flatMap {
            case (Left(e), i)  => reject(s"row $i", s"malformed row: $e"); None
            case (Right(m), i) =>
              val errors = Validate.metrics(m)
              if errors.nonEmpty then
                reject(m.researcherId, errors.mkString("; ")); None
              else if m.provenance.runId != runId then
                reject(m.researcherId, s"row belongs to run ${m.provenance.runId}, not $runId");
                None
              else Some(m)
          }
          def key(m: ResearcherMetrics) = (m.researcherId, m.metricVersion, m.window.label)
          val groups = decoded.groupBy(key)
          val distinctRows = groups.toVector.sortBy(_._1.toString).flatMap { (k, ms) =>
            if ms.distinct.size == 1 then Some(ms.head)
            else
              ms.foreach(m => reject(m.researcherId, s"conflicting duplicate rows for $k"))
              None
          }
          val dropped =
            groups.values.map(ms => if ms.distinct.size == 1 then ms.size - 1 else 0).sum
          var created = 0L
          var unchanged = 0L
          var fatal: Option[String] = None
          distinctRows.foreach { m =>
            if fatal.isEmpty then
              metrics.put(m) match
                case Right(Stored.Created)               => created += 1
                case Right(Stored.Unchanged)             => unchanged += 1
                case Left(KvError.ConditionFailed(_, _)) =>
                  reject(
                    m.researcherId,
                    s"different metrics already stored under ${m.metricVersion} ${m.window.label}"
                  )
                case Left(KvError.Failure(msg)) => fatal = Some(msg)
          }
          fatal.toLeft(()).flatMap { _ =>
            val rejected = rejects.result()
            val report = LoadReport.of(
              "researcher-metrics",
              runId,
              LogicVersion,
              Seq(
                "read" -> all.size.toLong,
                "stored" -> (created + unchanged),
                "duplicatesDropped" -> dropped.toLong,
                "rejected" -> rejected.size.toLong
              ),
              rejected
            )
            Right(((all.size.toLong, created, unchanged, dropped.toLong, report.rejected), report))
          }
      }
      .map { o =>
        val (read, created, unchanged, dropped, rejected) = o.value
        MetricsLoadResult(
          read,
          created,
          unchanged,
          dropped,
          rejected,
          o.report,
          o.reportOutcome,
          o.replay
        )
      }

// ------------------------------------------------------------------ Neptune load files

/** Writes a [[NeptuneLoadPlan]] as a [[RunLedger]] stage (`write-neptune-files`). The files and
  * manifests are content-hashed, create-only objects, so a replay of the same plan is `Unchanged`
  * for every object, and a changed plan under the same run is a conflict. Starting the Neptune bulk
  * load stays an explicit step ([[NeptuneBulkLoader.start]]); this job never calls it.
  */
final class NeptuneLoadJob(ledger: RunLedger, files: NeptuneLoadWriter, writer: ArtifactWriter):
  val Stage = "write-neptune-files"
  val LogicVersion = "neptune-files-v1"
  private val stage = new LoadStage(ledger, writer)

  def run(plan: NeptuneLoadPlan): Either[String, NeptuneLoadResult] =
    val runId = plan.manifest.runId
    stage
      .run(runId, Stage, LogicVersion) {
        files.write(plan).left.map(_.toString).map { r =>
          val report = LoadReport.of(
            "neptune-files",
            runId,
            LogicVersion,
            Seq("files" -> r.csvObjects.size.toLong, "rejected" -> plan.rejections.size.toLong),
            plan.rejections.map(x => RejectRecord.of(Stage, x.reason, s"${x.kind} ${x.id}", runId))
          )
          (r, report)
        }
      }
      .map(_.value)
