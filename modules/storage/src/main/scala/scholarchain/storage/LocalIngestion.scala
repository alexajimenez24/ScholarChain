package scholarchain.storage

import scholarchain.core.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.Try

/** The three stores of a local ingestion. Only in-memory ones are built here; nothing reaches AWS.
  */
final case class LocalStores(objects: ObjectStore, kv: KeyValueStore, vectors: VectorStore)

object LocalStores:
  def inMemory(): LocalStores =
    LocalStores(new InMemoryObjectStore, new InMemoryKeyValueStore, new InMemoryVectorStore)

/** @param maxUnitsPerIndex
  *   when set, at most this many source rows per document type are ingested, chosen by the smallest
  *   SHA-256 of the row key (so the same subset every time, whatever the file order); a row can
  *   become several chunks. Unset ingests every row.
  * @param queueCapacity
  *   bound of the embedding work queue (documents waiting between reader and batches)
  * @param querySamplesPerIndex
  *   query vectors per index for the latency samples (1..100)
  */
final case class IngestionSettings(
    maxUnitsPerIndex: Option[Int] = None,
    batchSize: Int = VectorLoader.DefaultBatch,
    queueCapacity: Int = VectorLoader.DefaultQueue,
    retry: RetryPolicy = RetryPolicy(4, 1L, _ => ()),
    querySamplesPerIndex: Int = 25,
    queryTopK: Int = 10
)

final case class DocTypeCounts(docType: String, index: String, totalRows: Long, selectedRows: Long)

final case class PassReport(
    label: String,
    sourceRows: Long,
    chunks: Long,
    written: Long,
    unchanged: Long,
    duplicatesDropped: Long,
    rejected: Vector[RejectRecord],
    perIndex: Vector[IndexCount],
    putBatches: Long,
    retries: Map[String, Long],
    failureRecords: Long,
    queueHighWater: Int,
    embedderCalls: Long,
    elapsedMillis: Long,
    reportUri: String,
    replay: Boolean
):
  def chunksPerSecond: Double =
    if elapsedMillis <= 0 then 0.0 else (written + unchanged) * 1000.0 / elapsedMillis
  def writtenPerSecond: Double =
    if elapsedMillis <= 0 then 0.0 else written * 1000.0 / elapsedMillis

final case class LatencySample(
    index: String,
    queries: Int,
    ownKeyInTopK: Int,
    p50Micros: Long,
    p95Micros: Long,
    maxMicros: Long,
    filteredQueries: Int,
    filteredP50Micros: Long
)

final case class LedgerSummary(
    stageStatus: String,
    attempt: Int,
    failureRecords: Int,
    failureRetryCountTotal: Long
)

final case class IngestionReport(
    runId: String,
    snapshot: String,
    embeddingVersion: String,
    templateVersions: Map[String, String],
    settings: IngestionSettings,
    counts: Vector[DocTypeCounts],
    selectionMillis: Long,
    first: PassReport,
    second: PassReport,
    latency: Vector[LatencySample],
    ledger: LedgerSummary,
    objectsByKind: Map[String, Int],
    heapUsedMbAfterFirstPass: Long,
    heapMaxMb: Long
):
  def totalRows: Long = counts.map(_.totalRows).sum
  def selectedRows: Long = counts.map(_.selectedRows).sum

  def toMarkdown: String =
    def rejectsByReason(p: PassReport) =
      p.rejected.groupBy(_.reason.take(100)).toVector.sortBy(x => (-x._2.size, x._1))
    val sb = new StringBuilder
    def line(s: String = "") = sb.append(s).append('\n')
    line(s"# Local ingestion of ${runId}")
    line()
    line(s"- snapshot: $snapshot")
    line(s"- embedding model version: `$embeddingVersion` (local deterministic fake, no AWS call)")
    line(s"- templates: ${templateVersions.toVector.sorted.map((k, v) => s"$k=$v").mkString(", ")}")
    line(
      s"- subset: ${settings.maxUnitsPerIndex
          .fold("none, every source row")(n => s"smallest-SHA-256 $n source rows per document type")}; selection took $selectionMillis ms"
    )
    line(
      s"- batch size ${settings.batchSize}, queue capacity ${settings.queueCapacity} (high water ${first.queueHighWater}), retry ${settings.retry.maxAttempts} attempts"
    )
    line(s"- heap after the first pass: $heapUsedMbAfterFirstPass MB used of $heapMaxMb MB max")
    line()
    line("## Meaningful units (source rows) and ingested")
    line()
    line("| document type | index | total rows | ingested rows | chunks present |")
    line("|---|---|---|---|---|")
    counts.foreach { c =>
      val present = first.perIndex.find(_.index == c.index).map(_.present).getOrElse(0L)
      line(s"| ${c.docType} | ${c.index} | ${c.totalRows} | ${c.selectedRows} | $present |")
    }
    line(
      s"| total | | $totalRows | $selectedRows | ${first.perIndex.map(_.present).sum} |"
    )
    line()
    def pass(p: PassReport) =
      line(s"## Pass: ${p.label}")
      line()
      line(
        s"- source rows ${p.sourceRows}, chunks ${p.chunks}, written ${p.written}, unchanged ${p.unchanged}, duplicates dropped ${p.duplicatesDropped}, rejected ${p.rejected.size}"
      )
      line(
        f"- elapsed ${p.elapsedMillis} ms, ${p.writtenPerSecond}%.1f vectors written per second, ${p.chunksPerSecond}%.1f chunks handled per second"
      )
      line(
        s"- PutVectors batches ${p.putBatches}, embedder calls ${p.embedderCalls}, retries ${
            if p.retries.isEmpty then "none"
            else p.retries.toVector.sorted.map((k, v) => s"$k=$v").mkString(", ")
          }, ledger failure records ${p.failureRecords}"
      )
      line(s"- stored report ${p.reportUri} (replay of an earlier stage: ${p.replay})")
      p.perIndex.foreach(i =>
        line(
          s"  - ${i.index}: present ${i.present}, written ${i.written}, unchanged ${i.unchanged}"
        )
      )
      if p.rejected.nonEmpty then
        line("- rejected by reason:")
        rejectsByReason(p).take(20).foreach((r, xs) => line(s"  - $r: ${xs.size}"))
      line()
    pass(first)
    pass(second)
    line("## Query latency (in-memory vector store, exact scan)")
    line()
    line("| index | queries | own key in top-K | p50 us | p95 us | max us | filtered p50 us |")
    line("|---|---|---|---|---|---|---|")
    latency.foreach(l =>
      line(
        s"| ${l.index} | ${l.queries} | ${l.ownKeyInTopK} | ${l.p50Micros} | ${l.p95Micros} | ${l.maxMicros} | ${l.filteredP50Micros} |"
      )
    )
    line()
    line("## RunLedger and stored objects")
    line()
    line(
      s"- stage load-vectors: ${ledger.stageStatus}, attempt ${ledger.attempt}; failure records ${ledger.failureRecords} (retry count total ${ledger.failureRetryCountTotal}; at most 1000 are listed)"
    )
    objectsByKind.toVector.sorted.foreach((k, n) => line(s"- stored objects, $k: $n"))
    sb.result()

/** Local ingestion of a Spark run into the five vector indexes, over in-memory stores and the
  * deterministic fake embedder. It reads the run manifest, the `s3vectors.json` load manifest and
  * the JSON-lines export (dropping the declared `extraFields`), renders every document through the
  * supplied [[DocumentRenderer]], loads twice (the second pass must skip everything) and samples
  * query latency.
  */
object LocalIngestion:
  /** An environment of placeholder bucket and table names for a local configuration. */
  val LocalEnv: Map[String, String] = Map(
    "RAW_BUCKET" -> "local-raw",
    "CURATED_BUCKET" -> "local-curated",
    "GRAPH_BUCKET" -> "local-graph",
    "RAG_BUCKET" -> "local-rag",
    "VECTOR_BUCKET" -> "local-vectors",
    "DDB_RUN_LEDGER" -> "local-run-ledger",
    "DDB_ENTITY_LINK_CACHE" -> "local-entity-links",
    "DDB_QUERY_SESSION" -> "local-sessions",
    "DDB_EVIDENCE_BUNDLE" -> "local-bundles",
    "DDB_ARTIFACT_REGISTRY" -> "local-artifacts",
    "DDB_RESEARCHER_METRIC" -> "local-metrics"
  )

  def localConfig(): Either[String, ScholarChainConfig] = ConfigLoader.load(LocalEnv)

  private final class CountingEmbedder(inner: Embedder) extends Embedder:
    val calls = new AtomicLong(0)
    val modelId: String = inner.modelId
    val dimension: Int = inner.dimension
    def embed(text: String): Either[RemoteError, Vector[Float]] =
      calls.incrementAndGet()
      inner.embed(text)

  /** Remembers the first `n` keys put per index, to sample queries from. */
  private final class SamplingStore(inner: VectorStore, n: Int) extends VectorStore:
    val sample = mutable.Map.empty[String, mutable.ArrayBuffer[String]]
    def createBucket() = inner.createBucket()
    def createIndex(spec: IndexSpec) = inner.createIndex(spec)
    def put(index: String, vectors: Vector[VectorRecord]) =
      val r = inner.put(index, vectors)
      if r.isRight then
        val b = sample.getOrElseUpdate(index, mutable.ArrayBuffer.empty)
        vectors.foreach(v => if b.size < n then b += v.key)
      r
    def get(index: String, keys: Vector[String]) = inner.get(index, keys)
    def query(index: String, vector: Vector[Float], topK: Int, filter: Vector[FilterTerm]) =
      inner.query(index, vector, topK, filter)

  /** The call:
    * {{{
    * LocalIngestion.run(cfg, runManifestPath, renderer, settings, embedder, stores)
    * }}}
    * `cfg` supplies index names, dimension, buckets and table names (use [[localConfig]]);
    * `runManifestPath` is `<out>/run-manifests/run-<id>.json` (the run folder is found beside it);
    * `embedder` defaults to `HashingEmbedder(cfg.vectors.dimension)`.
    */
  def run(
      cfg: ScholarChainConfig,
      runManifestPath: Path,
      renderer: DocumentRenderer,
      settings: IngestionSettings = IngestionSettings(),
      embedder: Option[Embedder] = None,
      stores: LocalStores = LocalStores.inMemory()
  ): Either[String, IngestionReport] =
    for
      loc <- locate(runManifestPath)
      (runId, snapshot, folder) = loc
      vm <- readManifest(folder, runId, cfg)
      (manifest, sumExtra, absExtra, absRows) = vm
      _ <- Either.cond(
        settings.querySamplesPerIndex >= 1 && settings.querySamplesPerIndex <= 100,
        (),
        "querySamplesPerIndex must be 1..100"
      )
      _ <- Either.cond(
        settings.maxUnitsPerIndex.forall(_ >= 1),
        (),
        "maxUnitsPerIndex must be >= 1"
      )
      emb = new CountingEmbedder(embedder.getOrElse(new HashingEmbedder(cfg.vectors.dimension)))
      sampling = new SamplingStore(stores.vectors, settings.querySamplesPerIndex)
      tables <- DynamoTables(stores.kv, cfg.dynamo)
      writer = new ArtifactWriter(stores.objects, new S3Layout(cfg.storage))
      loader <- VectorLoader(
        sampling,
        emb,
        writer,
        tables.runLedger,
        cfg.vectors,
        VectorLoaderSettings(
          batchSize = settings.batchSize,
          retry = settings.retry,
          queueCapacity = settings.queueCapacity
        )
      )
      _ <- sampling.createBucket().left.map(_.text)
      _ <- loader.specs.foldLeft[Either[String, Unit]](Right(()))((acc, s) =>
        acc.flatMap(_ => sampling.createIndex(s).left.map(_.text).map(_ => ()))
      )
      summaries = RowSource.jsonLinesDir[EvidenceItem](
        folder.resolve("jsonl/semantic-summaries"),
        Validate.evidence,
        sumExtra
      )
      abstracts = RowSource.jsonLinesDir[PaperAbstract](
        folder.resolve("jsonl/paper-abstracts"),
        dropFields = absExtra
      )
      hasAbstracts = absRows.isDefined
      t0 = System.nanoTime()
      selection = scan(summaries, abstracts, hasAbstracts, settings.maxUnitsPerIndex)
      _ <- checkAgainstManifest(manifest, absRows, cfg, selection)
      selectionMillis = (System.nanoTime() - t0) / 1000000
      source = combined(
        summaries,
        abstracts,
        hasAbstracts,
        settings.maxUnitsPerIndex.map(_ => selection.keys)
      )
      counts = countsOf(manifest, absRows, cfg, selection, settings.maxUnitsPerIndex.isDefined)
      expected =
        if settings.maxUnitsPerIndex.isDefined then None
        else Some(manifest.indexes.map(_.rows).sum + absRows.getOrElse(0L))
      first <- pass("first load", loader, runId, source, renderer, emb, expected)
      heap = heapMb()
      second <- pass(
        "second identical load (must skip everything)",
        loader,
        runId,
        source,
        renderer,
        emb,
        expected
      )
      latency = latencies(sampling, loader.specs.map(_.name), emb, settings)
      ledger = ledgerSummary(tables, runId, loader.Stage)
      objects = objectKinds(stores.objects, cfg)
    yield IngestionReport(
      runId,
      snapshot,
      emb.versionId,
      renderer.templateVersions,
      settings,
      counts,
      selectionMillis,
      first,
      second,
      latency,
      ledger,
      objects,
      heap._1,
      heap._2
    )

  private def locate(manifestPath: Path): Either[String, (String, String, Path)] =
    val name = manifestPath.getFileName.toString.stripSuffix(".json")
    for
      _ <- Either.cond(Ids.isRunId(name), (), s"$name is not a run ID; expected run-<id>.json")
      _ <- Either.cond(Files.isRegularFile(manifestPath), (), s"no run manifest $manifestPath")
      snap <- Try(ujson.read(Files.readString(manifestPath, UTF_8))("snapshot").str).toEither.left
        .map(e => s"run manifest has no snapshot: ${e.getMessage}".take(200))
      out = manifestPath.toAbsolutePath.getParent.getParent
      folder = out.resolve(s"spark/snapshot=$snap/run_id=$name")
      _ <- Either.cond(Files.isDirectory(folder), (), s"no run folder $folder")
    yield (name, snap, folder)

  private def readManifest(
      folder: Path,
      runId: String,
      cfg: ScholarChainConfig
  ): Either[String, (VectorManifest, Set[String], Set[String], Option[Long])] =
    val file = folder.resolve("load-manifests/s3vectors.json")
    for
      _ <- Either.cond(Files.isRegularFile(file), (), s"no load manifest $file")
      json = Files.readString(file, UTF_8)
      m <- LoadManifests.vectors(json)
      _ <- Either.cond(m.runId == runId, (), s"load manifest names run ${m.runId}, not $runId")
      _ <- m.problems(cfg.vectors) match
        case Vector() => Right(())
        case ps       => Left(ps.mkString("; "))
      parsed <- Try {
        val v = ujson.read(json)
        def extra(o: ujson.Value) =
          o("jsonLines")("extraFields").arr.map(_.str).toSet
        val abs = v.obj.get("abstracts")
        (
          extra(v("source")),
          abs.map(a => extra(a("source"))).getOrElse(Set.empty[String]),
          abs.map(a => a("rows").num.toLong)
        )
      }.toEither.left.map(e =>
        s"load manifest lacks the JSON-lines block: ${e.getMessage}".take(200)
      )
    yield (m, parsed._1, parsed._2, parsed._3)

  private[storage] def combined(
      summaries: RowSource[EvidenceItem],
      abstracts: RowSource[PaperAbstract],
      hasAbstracts: Boolean,
      keys: Option[Set[String]]
  ): RowSource[SourceRecord] =
    def sumKey(e: EvidenceItem) = s"${e.values.getOrElse("docType", "")}|${rowKey(e)}"
    def absKey(a: PaperAbstract) = s"${SemanticDocument.PaperAbstractType}|${a.workId}"
    val s = keys.fold(summaries)(ks => RowSource.filter(summaries)(e => ks(sumKey(e))))
    val a =
      if !hasAbstracts then RowSource.of(Seq.empty[PaperAbstract])
      else keys.fold(abstracts)(ks => RowSource.filter(abstracts)(x => ks(absKey(x))))
    RowSource.concat(s, a)(SourceRecord.Summary(_), SourceRecord.Abstract(_))

  private def rowKey(e: EvidenceItem): String = e.chunkId.getOrElse(e.evidenceId)

  /** What one scan of the export found: `totals` are the rows actually read per document type,
    * `chosen` the rows kept for a subset (all of them without one), and the `malformed` counts the
    * lines that did not decode, per source table.
    */
  private[storage] final case class Selection(
      keys: Set[String],
      totals: Map[String, Long],
      chosen: Map[String, Long],
      malformedSummaries: Long,
      malformedAbstracts: Long
  )

  /** One pass over the export holding only keys: counts every row per document type and, with a
    * subset `n`, keeps the `n` smallest-SHA-256 keys per type.
    */
  private[storage] def scan(
      summaries: RowSource[EvidenceItem],
      abstracts: RowSource[PaperAbstract],
      hasAbstracts: Boolean,
      n: Option[Int]
  ): Selection =
    val best = mutable.Map.empty[String, mutable.TreeSet[(String, String)]]
    val totals = mutable.Map.empty[String, Long]
    def see(docType: String, key: String): Unit =
      totals(docType) = totals.getOrElse(docType, 0L) + 1
      n.foreach { limit =>
        val set = best.getOrElseUpdate(docType, mutable.TreeSet.empty)
        set += ((Hashing.sha256Hex(key), key))
        if set.size > limit then set -= set.last
      }
    var badSummaries = 0L
    var badAbstracts = 0L
    summaries.rows.foreach {
      case Right(e) => see(e.values.getOrElse("docType", ""), rowKey(e))
      case Left(_)  => badSummaries += 1
    }
    if hasAbstracts then
      abstracts.rows.foreach {
        case Right(a) => see(SemanticDocument.PaperAbstractType, a.workId)
        case Left(_)  => badAbstracts += 1
      }
    Selection(
      best.flatMap((dt, set) => set.map(x => s"$dt|${x._2}")).toSet,
      totals.toMap,
      if n.isDefined then best.map((dt, set) => dt -> set.size.toLong).toMap else totals.toMap,
      badSummaries,
      badAbstracts
    )

  private def manifestTotals(
      m: VectorManifest,
      absRows: Option[Long],
      cfg: ScholarChainConfig
  ): Vector[(String, String, Long)] =
    (m.indexes.map(i => (i.docType, i.index, i.rows)) ++
      absRows.map(r => (SemanticDocument.PaperAbstractType, cfg.vectors.indexes("abstracts"), r)))
      .sortBy(_._1)

  /** A truncated or padded export must fail, not look complete: the rows read per document type
    * must equal the manifest. Lines that did not decode have no type, so when a table has any, that
    * table is compared as a whole (readable plus undecodable lines).
    */
  private[storage] def checkAgainstManifest(
      m: VectorManifest,
      absRows: Option[Long],
      cfg: ScholarChainConfig,
      sel: Selection
  ): Either[String, Unit] =
    val abs = SemanticDocument.PaperAbstractType
    val expected = manifestTotals(m, absRows, cfg)
    def tableCheck(name: String, types: Vector[String], bad: Long) =
      val want = expected.filter(e => types.contains(e._1)).map(_._3).sum
      val read = types.map(t => sel.totals.getOrElse(t, 0L)).sum + bad
      Option.when(want != read)(s"$name: the manifest has $want rows, the export gave $read")
    val sumTypes = m.indexes.map(_.docType).filter(_ != abs)
    val perType =
      (if sel.malformedSummaries == 0 then sumTypes else Vector.empty) ++
        (if sel.malformedAbstracts == 0 && absRows.isDefined then Vector(abs) else Vector.empty)
    val typeProblems = expected
      .filter(e => perType.contains(e._1))
      .flatMap { (dt, _, rows) =>
        val read = sel.totals.getOrElse(dt, 0L)
        Option.when(read != rows)(s"$dt: the manifest has $rows rows, the export gave $read")
      }
    val unknown = sel.totals.keySet -- expected.map(_._1).toSet
    val problems = typeProblems ++
      tableCheck("semantic-summaries", sumTypes, sel.malformedSummaries) ++
      absRows.flatMap(_ => tableCheck("paper-abstracts", Vector(abs), sel.malformedAbstracts)) ++
      Option.when(unknown.nonEmpty)(s"rows of unlisted document types: ${unknown.toVector.sorted}")
    Either.cond(
      problems.isEmpty,
      (),
      s"export does not match its load manifest: ${problems.distinct.mkString("; ")}"
    )

  private def countsOf(
      m: VectorManifest,
      absRows: Option[Long],
      cfg: ScholarChainConfig,
      sel: Selection,
      subset: Boolean
  ): Vector[DocTypeCounts] =
    manifestTotals(m, absRows, cfg).map { (dt, ix, _) =>
      val read = sel.totals.getOrElse(dt, 0L)
      DocTypeCounts(dt, ix, read, if subset then sel.chosen.getOrElse(dt, 0L) else read)
    }

  private def pass(
      label: String,
      loader: VectorLoader,
      runId: String,
      source: RowSource[SourceRecord],
      renderer: DocumentRenderer,
      emb: CountingEmbedder,
      expectedRows: Option[Long]
  ): Either[String, PassReport] =
    val before = emb.calls.get()
    val t0 = System.nanoTime()
    loader.loadRendered(runId, source, renderer, expectedRows).map { r =>
      PassReport(
        label,
        r.read,
        r.chunks,
        r.written,
        r.unchanged,
        r.duplicatesDropped,
        r.rejected,
        r.perIndex,
        r.putBatches,
        r.retries,
        r.failureRecords,
        r.queueHighWater,
        emb.calls.get() - before,
        (System.nanoTime() - t0) / 1000000,
        r.report.uri,
        r.replay
      )
    }

  private def heapMb(): (Long, Long) =
    System.gc()
    val rt = Runtime.getRuntime
    ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), rt.maxMemory() / (1024 * 1024))

  private def percentile(sorted: Vector[Long], p: Double): Long =
    if sorted.isEmpty then 0L
    else sorted(math.min(sorted.size - 1, math.ceil(p * sorted.size).toInt - 1).max(0))

  private def latencies(
      sampling: SamplingStore,
      indexes: Vector[String],
      emb: CountingEmbedder,
      settings: IngestionSettings
  ): Vector[LatencySample] =
    indexes.sorted.flatMap { index =>
      val keys = sampling.sample.get(index).map(_.toVector).getOrElse(Vector.empty)
      if keys.isEmpty then None
      else
        sampling.get(index, keys).toOption.map { stored =>
          val times = Vector.newBuilder[Long]
          val ftimes = Vector.newBuilder[Long]
          var own = 0
          stored.foreach { sv =>
            val t = System.nanoTime()
            val hits = sampling.query(index, sv.data, settings.queryTopK)
            times += (System.nanoTime() - t) / 1000
            if hits.exists(_.exists(_.key == sv.key)) then own += 1
            val filter = sv.metadata.get("entityId").toVector.map(FilterTerm.Eq("entityId", _))
            val tf = System.nanoTime()
            sampling.query(index, sv.data, settings.queryTopK, filter)
            ftimes += (System.nanoTime() - tf) / 1000
          }
          val ts = times.result().sorted
          val fs = ftimes.result().sorted
          LatencySample(
            index,
            ts.size,
            own,
            percentile(ts, 0.5),
            percentile(ts, 0.95),
            ts.lastOption.getOrElse(0L),
            fs.size,
            percentile(fs, 0.5)
          )
        }
    }

  private def ledgerSummary(tables: DynamoTables, runId: String, stage: String): LedgerSummary =
    val entry = tables.runLedger.get(runId, stage).toOption.flatten.map(_.value)
    val fails = tables.runLedger.failures(runId, stage).toOption.getOrElse(Vector.empty)
    LedgerSummary(
      entry.map(_.status.toString).getOrElse("missing"),
      entry.map(_.attempt).getOrElse(0),
      fails.size,
      fails.map(_.value.retryCount.toLong).sum
    )

  private def objectKinds(objects: ObjectStore, cfg: ScholarChainConfig): Map[String, Int] =
    new S3Layout(cfg.storage)
      .bucket(Zone.SemanticContext)
      .toOption
      .toVector
      .flatMap { b =>
        val prefix = s"s3://$b/${Zone.SemanticContext.prefix}/"
        objects
          .list(s"s3://$b/${Zone.SemanticContext.prefix}")
          .toOption
          .toVector
          .flatten
          .map(
            _.stripPrefix(prefix).takeWhile(_ != '/')
          )
      }
      .groupBy(identity)
      .map((k, v) => k -> v.size)
