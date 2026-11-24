package scholarchain.rag

import scholarchain.core.*
import java.nio.file.{Files, Path}

/** A temporary Spark run folder in the layout `LocalIngestion.run` reads: the run manifest at
  * `<out>/run-manifests/<run>.json`, the `s3vectors.json` load manifest, and the JSON-lines export
  * partitioned `docType=../windowLabel=..` for summaries. The rows are the export-shaped lines of
  * `resources/render/` (written by the independent Python script), copied verbatim with their extra
  * columns, unless a test replaces one.
  */
object SparkShapedRun:
  val RunId = "run-0123456789abcdef"
  val Snapshot = "2026-11-24"
  val SummaryLines: Vector[String] = RenderFixtures.lines("summaries.jsonl")
  val AbstractLines: Vector[String] = RenderFixtures.lines("abstracts.jsonl")

  /** An export line for a summary row: the core record plus the partition columns. */
  def summaryLine(e: EvidenceItem): String =
    val j = ujson.read(JsonLines.encode(e))
    j("docType") = e.values("docType")
    j("windowLabel") = e.values("window")
    j.render()

  private def rm(dir: Path): Unit =
    Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  /** Writes the run, hands `f` the run manifest path, and deletes the folder afterwards. */
  def withRun[A](
      summaries: Vector[String] = SummaryLines,
      abstracts: Vector[String] = AbstractLines,
      dimension: Int = 256
  )(f: Path => A): A =
    val out = Files.createTempDirectory("rag-spark-run")
    try
      val folder = out.resolve(s"spark/snapshot=$Snapshot/run_id=$RunId")
      Files.createDirectories(out.resolve("run-manifests"))
      Files.createDirectories(folder.resolve("load-manifests"))
      val parts = summaries.groupBy { l =>
        val j = ujson.read(l)
        (j("docType").str, j("windowLabel").str)
      }
      for ((dt, w), ls) <- parts do
        val dir = folder.resolve(s"jsonl/semantic-summaries/docType=$dt/windowLabel=$w")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("part-00000.json"), ls.mkString("", "\n", "\n"))
      val abs = folder.resolve("jsonl/paper-abstracts")
      Files.createDirectories(abs)
      Files.writeString(abs.resolve("part-00000.json"), abstracts.mkString("", "\n", "\n"))
      val byDocType = parts.toVector.groupMapReduce(_._1._1)(_._2.size)(_ + _)
      val indexes = byDocType.toVector.sorted.map { (dt, n) =>
        val ix = RenderFixtures.vectors.indexes(scholarchain.storage.SemanticDocument.IndexKeys(dt))
        ujson.Obj("docType" -> dt, "index" -> ix, "rows" -> n)
      }
      val manifest = ujson.Obj(
        "runId" -> RunId,
        "snapshot" -> Snapshot,
        "target" -> "s3vectors",
        "dimension" -> dimension,
        "source" -> ujson.Obj(
          "table" -> "semantic-summaries",
          "rows" -> summaries.size,
          "jsonLines" -> ujson.Obj("extraFields" -> ujson.Arr("docType", "windowLabel"))
        ),
        "indexes" -> ujson.Arr.from(indexes),
        "abstracts" -> ujson.Obj(
          "docType" -> "paper-abstract",
          "index" -> "paper-abstract-v1",
          "source" -> ujson.Obj(
            "table" -> "paper-abstracts",
            "rows" -> abstracts.size,
            "jsonLines" -> ujson.Obj("extraFields" -> ujson.Arr("sourceWorkId", "upstreamRunId"))
          ),
          "rows" -> abstracts.size
        )
      )
      Files.writeString(folder.resolve("load-manifests/s3vectors.json"), manifest.render(2))
      val runManifest = out.resolve(s"run-manifests/$RunId.json")
      Files.writeString(runManifest, ujson.Obj("snapshot" -> Snapshot).render())
      f(runManifest)
    finally rm(out)
