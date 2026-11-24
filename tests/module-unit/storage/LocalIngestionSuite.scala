package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.RenderFx.*
import scholarchain.storage.VecFx.*
import java.nio.file.{Files, Path}

/** The local ingestion entry point on a small fixture shaped like the Spark export. */
class LocalIngestionSuite extends munit.FunSuite:
  private val cfg: ScholarChainConfig = LocalIngestion
    .localConfig()
    .toOption
    .get
    .copy(
      vectors = LocalIngestion.localConfig().toOption.get.vectors.copy(dimension = 16)
    )
  private val snapshot = "2026-11-22"

  private def summaryLine(row: EvidenceItem): String =
    val j = ujson.read(JsonLines.encode(row))
    j("docType") = row.values("docType")
    j("windowLabel") = row.values("window")
    j.render()

  private def abstractLine(a: PaperAbstract): String =
    val j = ujson.read(JsonLines.encode(a))
    j("sourceWorkId") = "openalex:W1"
    j("upstreamRunId") = run
    j.render()

  private def rm(dir: Path): Unit =
    Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  /** Writes `<out>/run-manifests/<run>.json` and `<out>/spark/snapshot=../run_id=../...`. */
  private def fixture(
      summaries: Seq[String],
      abstracts: Seq[String],
      withAbstracts: Boolean = true
  )(
      f: Path => Unit
  ): Unit =
    val out = Files.createTempDirectory("local-ingestion")
    try
      val folder = out.resolve(s"spark/snapshot=$snapshot/run_id=$run")
      Files.createDirectories(out.resolve("run-manifests"))
      Files.createDirectories(folder.resolve("load-manifests"))
      Files.createDirectories(folder.resolve("jsonl/semantic-summaries/docType=x"))
      Files.createDirectories(folder.resolve("jsonl/paper-abstracts"))
      Files.writeString(out.resolve(s"run-manifests/$run.json"), s"""{"snapshot":"$snapshot"}""")
      Files.writeString(
        folder.resolve("jsonl/semantic-summaries/docType=x/part-0.json"),
        summaries.mkString("\n") + "\n"
      )
      Files.writeString(
        folder.resolve("jsonl/paper-abstracts/part-0.json"),
        abstracts.mkString("\n") + "\n"
      )
      val profileRows = summaries.count(_.contains("researcher-profile"))
      val absBlock =
        if withAbstracts then
          s""","abstracts":{"docType":"paper-abstract","index":"paper-abstract-v1","source":{"table":"paper-abstracts","rows":${abstracts.size},"jsonLines":{"extraFields":["sourceWorkId","upstreamRunId"]}},"rows":${abstracts.size}}"""
        else ""
      Files.writeString(
        folder.resolve("load-manifests/s3vectors.json"),
        s"""{"runId":"$run","snapshot":"$snapshot","target":"s3vectors","dimension":16,
           |"source":{"table":"semantic-summaries","rows":${summaries.size},"jsonLines":{"extraFields":["docType","windowLabel"]}},
           |"indexes":[{"docType":"researcher-profile","index":"researcher-profile-v1","rows":$profileRows},
           |{"docType":"collaboration-community","index":"collaboration-community-v1","rows":${summaries.size - profileRows}}]$absBlock}""".stripMargin
      )
      f(out.resolve(s"run-manifests/$run.json"))
    finally rm(out)

  private def rows: (Seq[EvidenceItem], Seq[PaperAbstract]) =
    (
      (1 to 12).map(profileRow(_)) ++
        (1 to 4).map(i =>
          summaryRow(
            SemanticDocument.CommunityProfile,
            s"cm-$i",
            s"Community $i of researchers on graphs"
          )
        ),
      (1 to 5).map(abstractRow(_))
    )

  test("ingests a Spark-shaped export into the indexes; a second identical pass skips everything") {
    val (s, a) = rows
    fixture(s.map(summaryLine), a.map(abstractLine)) { manifest =>
      val report =
        LocalIngestion.run(cfg, manifest, stub(), IngestionSettings(queryTopK = 50)).toOption.get
      assertEquals(report.runId, run)
      assertEquals(report.embeddingVersion, "hash-embed-v1@16")
      assertEquals(
        (report.first.sourceRows, report.first.chunks, report.first.written),
        (21L, 21L, 21L)
      )
      assertEquals(report.first.rejected, Vector.empty)
      assertEquals(
        report.first.perIndex.map(i => i.index -> i.present).toMap,
        Map(
          "researcher-profile-v1" -> 12L,
          "collaboration-community-v1" -> 4L,
          "paper-abstract-v1" -> 5L
        )
      )
      assertEquals(
        report.counts.map(c => c.docType -> (c.totalRows, c.selectedRows)).toMap,
        Map(
          "researcher-profile" -> (12L, 12L),
          "collaboration-community" -> (4L, 4L),
          "paper-abstract" -> (5L, 5L)
        )
      )
      // the second pass skips everything: no embedding, no put, nothing written
      assertEquals(
        (
          report.second.written,
          report.second.unchanged,
          report.second.embedderCalls,
          report.second.putBatches
        ),
        (0L, 21L, 0L, 0L)
      )
      assertEquals(report.second.rejected, Vector.empty)
      assert(report.first.embedderCalls == 21L && report.first.putBatches >= 3L)
      assertEquals(report.first.queueHighWater, 21)
      assert(report.latency.nonEmpty && report.latency.forall(l => l.ownKeyInTopK == l.queries))
      assertEquals(report.ledger.stageStatus, "Succeeded")
      assertEquals(report.objectsByKind("researcher-profile"), 12)
      assertEquals(report.objectsByKind("researcher-profile-source"), 12)
      val md = report.toMarkdown
      assert(md.contains("second identical load") && md.contains("hash-embed-v1@16"))
    }
  }

  test("rejected rows are reported with reasons and recorded as ledger failures") {
    val (s, a) = rows
    val bad = profileRow(3)
    val lines = s.map(summaryLine) :+ "not json at all"
    fixture(lines, a.map(abstractLine)) { manifest =>
      val report = LocalIngestion
        .run(cfg, manifest, stub(failOn = Set(bad.chunkId.get)))
        .toOption
        .get
      assertEquals(report.first.rejected.size, 2)
      val reasons = report.first.rejected.map(_.reason)
      assert(reasons.exists(_.contains("malformed row")), reasons.toString)
      assert(reasons.exists(_.contains("render failed")), reasons.toString)
      assertEquals(report.first.written, 20L)
      assertEquals(
        report.second.rejected.size,
        2
      ) // rejected rows are rejected again, nothing hidden
      assertEquals(report.ledger.failureRecords, 2) // one record per rejected input
      assertEquals(report.ledger.failureRetryCountTotal, 4L) // seen in both passes
    }
  }

  test("a bounded subset is deterministic and reports both totals") {
    val (s, a) = rows
    val settings = IngestionSettings(maxUnitsPerIndex = Some(3))
    fixture(s.map(summaryLine), a.map(abstractLine)) { manifest =>
      val one = LocalIngestion.run(cfg, manifest, stub(), settings).toOption.get
      val two = LocalIngestion.run(cfg, manifest, stub(), settings).toOption.get
      assertEquals(
        one.counts.map(c => c.docType -> (c.totalRows, c.selectedRows)).toMap,
        Map(
          "researcher-profile" -> (12L, 3L),
          "collaboration-community" -> (4L, 3L),
          "paper-abstract" -> (5L, 3L)
        )
      )
      assertEquals((one.totalRows, one.selectedRows), (21L, 9L))
      assertEquals(one.first.written, 9L)
      assertEquals(one.first.perIndex, two.first.perIndex)
      // the same rows were chosen both times: the stored vector keys are the same
      assertEquals(one.first.reportUri, two.first.reportUri)
    }
  }

  /** Splits a table's single part file in two and removes the second: a truncated export. */
  private def dropOnePart(manifest: Path, table: String, sub: String): Unit =
    val dir = manifest.getParent.getParent
      .resolve(s"spark/snapshot=$snapshot/run_id=$run/jsonl/$table")
      .resolve(sub)
    val lines = Files.readAllLines(dir.resolve("part-0.json")).toArray.toSeq.map(_.toString)
    Files.writeString(dir.resolve("part-0.json"), lines.take(2).mkString("\n") + "\n")
    Files.writeString(dir.resolve("part-1.json"), lines.drop(2).mkString("\n") + "\n")
    Files.delete(dir.resolve("part-1.json"))

  for
    (table, sub) <- Seq("semantic-summaries" -> "docType=x", "paper-abstracts" -> "")
    (mode, settings) <- Seq(
      "no subset" -> IngestionSettings(),
      "a subset" -> IngestionSettings(maxUnitsPerIndex = Some(3))
    )
  do
    test(s"a $table export with one part file removed fails ($mode)") {
      val (s, a) = rows
      fixture(s.map(summaryLine), a.map(abstractLine)) { manifest =>
        dropOnePart(manifest, table, sub)
        val r = LocalIngestion.run(cfg, manifest, stub(), settings)
        assert(
          r.left.exists(_.contains("does not match its load manifest")),
          r.map(_.first.sourceRows).toString
        )
      }
    }

  test("a complete export reports the rows actually read and passes the manifest check") {
    val (s, a) = rows
    fixture(s.map(summaryLine), a.map(abstractLine)) { manifest =>
      val full = LocalIngestion.run(cfg, manifest, stub()).toOption.get
      assertEquals((full.totalRows, full.selectedRows), (21L, 21L))
      assertEquals(full.first.sourceRows, 21L)
      val sub =
        LocalIngestion.run(cfg, manifest, stub(), IngestionSettings(maxUnitsPerIndex = Some(2)))
      assertEquals(sub.toOption.map(r => (r.totalRows, r.selectedRows)), Some((21L, 6L)))
    }
  }

  test("a multi-chunk renderer: chunks exceed rows, totals show both") {
    val (s, a) = rows
    fixture(s.map(summaryLine), a.map(abstractLine)) { manifest =>
      val report = LocalIngestion.run(cfg, manifest, stub(maxChunk = 30)).toOption.get
      assert(report.first.chunks > report.first.sourceRows)
      assertEquals(report.first.written, report.first.chunks)
      assertEquals(report.second.unchanged, report.first.chunks)
    }
  }

  test(
    "a missing manifest, run folder or a mismatched load manifest is an error, not an empty run"
  ) {
    val (s, a) = rows
    assert(LocalIngestion.run(cfg, Path.of("/nonexistent/run-manifests/x.json"), stub()).isLeft)
    fixture(s.map(summaryLine), a.map(abstractLine)) { manifest =>
      val folder = manifest.getParent.getParent.resolve(s"spark/snapshot=$snapshot/run_id=$run")
      Files.delete(folder.resolve("load-manifests/s3vectors.json"))
      assert(LocalIngestion.run(cfg, manifest, stub()).left.exists(_.contains("no load manifest")))
    }
    fixture(s.map(summaryLine), a.map(abstractLine)) { manifest =>
      val bad = cfg.copy(vectors = cfg.vectors.copy(dimension = 32))
      assert(LocalIngestion.run(bad, manifest, stub()).isLeft)
    }
  }
