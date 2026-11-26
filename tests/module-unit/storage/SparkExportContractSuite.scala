package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.Fixtures.{cfg, layout}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Comparator

/** A small hand-written run folder in the Spark JSON-lines layout (`<run>/jsonl/<table>/` with the
  * Parquet partition folders, `part-*.json`, every column with nulls written, plus `_SUCCESS` and
  * checksum files). The lines are literal text, not encoded from core records; only the run ID and
  * the core-derived edge IDs are filled in. The layout and the extra fields are copied from Spark's
  * `Export.scala`; storage never imports spark.
  */
object SparkLayout:
  val run: String = Ids.run("""{"fixture":"spark-jsonl"}""")
  val policy = "graph-v1"
  val snapshot = "2026-11-22"
  val window: TimeWindow = TimeWindow(2015, 2026)
  val a = "sc:r:v1:0123456789abcdef0123"
  val b = "sc:r:v1:fedcba9876543210fedc"
  val work = "dblp:conf/x/A14"
  val community = "cm:00000000000000aa"
  val communityB = "cm:00000000000000bb"

  /** `extraFields` of each table, as Export.scala declares them. */
  val MetricsExtra: Set[String] =
    Set("maximumTeamWorkCount", "inLargestComponent", "windowLabel")
  val CoauthorExtra: Set[String] = Set("supportingWorkIds", "windowLabel")
  val CommunitiesExtra: Set[String] = Set("windowLabel", "recordKind")

  val coauthorId: String = Ids.coauthorEdge(a, b, window, policy)
  val mentions: Vector[String] = Vector(Ids.mention(work, 0), Ids.mention(work, 1))
  val authoredIds: Vector[String] =
    Vector(a, b).zip(mentions).map((r, m) => Ids.graphEdge("AUTHORED", r, work, policy, m))
  val coauthorGraphId: String =
    Ids.graphEdge("COAUTHORED_WITH", a, b, policy, coauthorId)

  private def prov(record: String, schema: String, pol: String = policy): String =
    s"""{"source":"SCHOLARCHAIN","sourceRecordId":"$record","snapshot":"$snapshot",""" +
      s""""runId":"$run","schemaVersion":"$schema","policyVersion":"$pol"}"""

  private def node(id: String, label: String, props: String): String =
    s"""{"nodeId":"$id","label":"$label","properties":$props,""" +
      s""""provenance":${prov(id, "graph-node-v1", "er-v1")}}"""

  private def researcher(id: String, name: String): String =
    node(id, "Researcher", s"""{"displayName":"$name","mentionCount":"1","unresolved":"false"}""")

  private def authored(i: Int, r: String): String =
    s"""{"edgeId":"${authoredIds(i)}","fromId":"$r","edgeType":"AUTHORED","toId":"$work",""" +
      s""""properties":{"position":"$i","teamSize":"2","year":"2016","mentionIds":"${mentions(
          i
        )}"},""" +
      s""""provenance":${prov(mentions(i), "graph-edge-v1")}}"""

  private val coauthorGraphEdge: String =
    s"""{"edgeId":"$coauthorGraphId","fromId":"$a","edgeType":"COAUTHORED_WITH","toId":"$b",""" +
      s""""properties":{"coauthorEdgeId":"$coauthorId","window":"2015-2026",""" +
      s""""jointPublicationCount":"1","firstCollaborationYear":"2016",""" +
      s""""lastCollaborationYear":"2016","representativeWorkIds":"$work"},""" +
      s""""provenance":${prov(coauthorId, "graph-edge-v1")}}"""

  private val coauthorRecord: String =
    s"""{"schemaVersion":"coauthor-edge-v1","edgeId":"$coauthorId","researcherA":"$a",""" +
      s""""researcherB":"$b","jointPublicationCount":1,"firstCollaborationYear":2016,""" +
      s""""lastCollaborationYear":2016,"representativeWorkIds":["$work"],""" +
      s""""supportingWorkIds":["$work"],"window":{"startYear":2015,"endYear":2026},""" +
      s""""policyVersion":"$policy","provenance":${prov(coauthorId, "coauthor-edge-v1")},""" +
      """"windowLabel":"2015-2026"}"""

  private def metrics(r: String): String =
    s"""{"schemaVersion":"researcher-metrics-v1","researcherId":"$r","publicationCount":1,""" +
      """"distinctCoauthorCount":1,"totalCoauthorOccurrences":1,""" +
      """"averageCoauthorsPerPaper":1.0,"maximumCoauthorsOnOnePaper":1,""" +
      s""""maximumTeamWorkIds":["$work"],"weightedDegree":1.0,"componentId":"cc:1",""" +
      s""""componentSize":2,"communityId":"$community","bridgeScore":0.0,""" +
      """"participationCoefficient":null,"crossCommunityDegree":0,""" +
      """"communitiesConnected":null,"coreness":1,""" +
      """"window":{"startYear":2015,"endYear":2026},"metricVersion":"metrics-v1",""" +
      s""""provenance":${prov(r, "researcher-metrics-v1", "metrics-v1")},""" +
      """"maximumTeamWorkCount":1,"inLargestComponent":true,"windowLabel":"2015-2026"}"""

  def communityRow(evidenceId: String, id: String, size: String, of: String = run): String =
    s"""{"schemaVersion":"evidence-v1","evidenceId":"$evidenceId",""" +
      """"evidenceType":"COMMUNITY_RECORD","workId":null,"researcherIds":[],""" +
      """"sourceUri":"s3://sc-graph/spark/communities/windowLabel=2015-2026","chunkId":null,""" +
      """"graphPath":null,"score":0.0,"values":{"recordKind":"community",""" +
      s""""communityId":"$id","communitySize":"$size","internalEdgeCount":"1",""" +
      """"crossCommunityEdgeCount":"1","window":"2015-2026",""" +
      """"method":"weighted synchronous label propagation","maxIterations":"20"},""" +
      s""""text":null,"textHash":null,"provenance":${prov(id, "evidence-v1").replace(run, of)},""" +
      """"windowLabel":"2015-2026","recordKind":"community"}"""

  val evA: String = "ev:community_record:00000000000000aa"
  val evB: String = "ev:community_record:00000000000000bb"

  val crossRow: String =
    """{"schemaVersion":"evidence-v1","evidenceId":"ev:community_record:00000000000000cc",""" +
      """"evidenceType":"COMMUNITY_RECORD","workId":null,""" +
      s""""researcherIds":["$a","$b"],""" +
      """"sourceUri":"s3://sc-graph/spark/communities/windowLabel=2015-2026","chunkId":null,""" +
      """"graphPath":null,"score":0.0,"values":{"recordKind":"cross_community_edge",""" +
      s""""edgeId":"$coauthorId","communityA":"$community","communityB":"$communityB",""" +
      """"jointPublicationCount":"1","window":"2015-2026"},"text":null,"textHash":null,""" +
      s""""provenance":${prov(coauthorId, "evidence-v1")},""" +
      """"windowLabel":"2015-2026","recordKind":"cross_community_edge"}"""

  /** Partition folder (under `jsonl/`) and its lines. */
  val Partitions: Vector[(String, Vector[String])] = Vector(
    "graph-nodes/label=Researcher" ->
      Vector(researcher(a, "Ada Lovelace"), researcher(b, "Grace Hopper")),
    "graph-nodes/label=Work" ->
      Vector(node(work, "Work", """{"title":"Graphs","year":"2016"}""")),
    "graph-edges/edgeType=AUTHORED" -> Vector(authored(0, a), authored(1, b)),
    "graph-edges/edgeType=COAUTHORED_WITH" -> Vector(coauthorGraphEdge),
    "coauthor-edges/windowLabel=2015-2026" -> Vector(coauthorRecord),
    "researcher-metrics/windowLabel=2015-2026" -> Vector(metrics(a), metrics(b)),
    "communities/windowLabel=2015-2026/recordKind=community" ->
      Vector(communityRow(evA, community, "3"), communityRow(evB, communityB, "1")),
    "communities/windowLabel=2015-2026/recordKind=cross_community_edge" -> Vector(crossRow)
  )

  /** Writes the run folder, runs `f` on its `jsonl` directory and removes it. */
  def withRun[A](f: Path => A): A =
    val root = Files.createTempDirectory("spark-run")
    try
      val jsonl = root.resolve("jsonl")
      Partitions.foreach { (partition, lines) =>
        val dir = Files.createDirectories(jsonl.resolve(partition))
        Files.writeString(dir.resolve("part-00000-aaaa-c000.json"), lines.mkString("", "\n", "\n"))
        Files.writeString(dir.resolve("_SUCCESS"), "")
        Files.write(dir.resolve(".part-00000-aaaa-c000.json.crc"), "crc".getBytes(UTF_8))
      }
      f(jsonl)
    finally Files.walk(root).sorted(Comparator.reverseOrder[Path]()).forEach(Files.delete(_))

  def rows[A](src: RowSource[A]): Vector[A] =
    src.rows.map(_.fold(e => throw new AssertionError(e), identity)).toVector

class SparkExportContractSuite extends munit.FunSuite:
  import SparkLayout.*

  private def metricsSource(jsonl: Path, drop: Set[String] = MetricsExtra) =
    RowSource.jsonLinesDir[ResearcherMetrics](
      jsonl.resolve("researcher-metrics"),
      Validate.metrics,
      drop
    )

  private final class Rig:
    val kv = new InMemoryKeyValueStore
    val objects = new InMemoryObjectStore
    val tables = DynamoTables(kv, cfg.dynamo).toOption.get
    val writer = new ArtifactWriter(objects, layout)
    val metricsLoader = new ResearcherMetricsLoader(tables.metrics, tables.runLedger, writer)
    val neptuneJob =
      new NeptuneLoadJob(tables.runLedger, new NeptuneLoadWriter(writer, layout), writer)

  test("researcher-metrics in the Spark layout load into DynamoDB with no rejects") {
    withRun { jsonl =>
      val r = new Rig
      val res = r.metricsLoader.load(run, metricsSource(jsonl), Some(2L)).toOption.get
      assertEquals(
        (res.read, res.created, res.unchanged, res.duplicatesDropped, res.rejected),
        (2L, 2L, 0L, 0L, Vector.empty)
      )
      assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 2)
      val stored = r.tables.metrics.get(a, "metrics-v1", window).toOption.get.get
      assertEquals(
        (stored.communityId, stored.participationCoefficient, stored.coreness),
        (Some(community), None, Some(1))
      )
      val again = r.metricsLoader.load(run, metricsSource(jsonl), Some(2L)).toOption.get
      assertEquals((again.created, again.unchanged, again.replay), (0L, 2L, true))
    }
  }

  test("the Neptune export and load job take the Spark layout: all rows, no rejects") {
    withRun { jsonl =>
      val r = new Rig
      val nodes = rows(
        RowSource.jsonLinesDir[GraphNode](jsonl.resolve("graph-nodes"), Validate.graphNode)
      )
      val edges = rows(
        RowSource.jsonLinesDir[GraphEdge](jsonl.resolve("graph-edges"), Validate.graphEdge)
      )
      val records = rows(
        RowSource.jsonLinesDir[CoauthorEdge](
          jsonl.resolve("coauthor-edges"),
          Validate.edge,
          CoauthorExtra
        )
      )
      assertEquals((nodes.size, edges.size, records.size), (3, 3, 1))
      val plan = NeptuneExport.build(
        GraphExportInput(
          run,
          snapshot,
          policy,
          Vector(window),
          nodes,
          edges,
          records,
          "s3://sc-graph/spark"
        )
      )
      assertEquals(plan.rejections, Vector.empty[Rejection])
      assertEquals(plan.manifest.duplicatesDropped, 0)
      assertEquals(
        plan.files.map(f => f.name -> f.rows),
        Vector(
          "nodes-Researcher.csv" -> 2,
          "nodes-Work.csv" -> 1,
          "edges-AUTHORED.csv" -> 2,
          "edges-COAUTHORED_WITH.csv" -> 1
        )
      )
      val first = r.neptuneJob.run(plan).toOption.get
      assertEquals(first.csvObjects.size, 4)
      assert(first.outcomes.forall(_ == WriteOutcome.Created), first.outcomes.toString)
      val keys = r.objects.keys
      val again = r.neptuneJob.run(plan).toOption.get
      assert(again.outcomes.forall(_ == WriteOutcome.Unchanged), again.outcomes.toString)
      assertEquals(r.objects.keys, keys)
      assertEquals(
        r.tables.runLedger.get(run, r.neptuneJob.Stage).toOption.get.map(_.value.status),
        Some(LedgerStatus.Succeeded)
      )
    }
  }

  test("an extra field that is not dropped is refused by strict decoding and rejected") {
    withRun { jsonl =>
      MetricsExtra.foreach { kept =>
        val strict = metricsSource(jsonl, MetricsExtra - kept).rows.toVector
        assert(strict.size == 2 && strict.forall(_.left.exists(_.contains("unknown field"))), kept)
      }
      CoauthorExtra.foreach { kept =>
        val strict = RowSource
          .jsonLinesDir[CoauthorEdge](
            jsonl.resolve("coauthor-edges"),
            Validate.edge,
            CoauthorExtra - kept
          )
          .rows
          .toVector
        assert(strict.size == 1 && strict.forall(_.isLeft), kept)
      }
      val r = new Rig
      val res = r.metricsLoader
        .load(run, metricsSource(jsonl, MetricsExtra - "inLargestComponent"), Some(2L))
        .toOption
        .get
      assertEquals((res.created, res.rejected.size), (0L, 2))
      assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 0)
    }
  }

class CommunityLoaderSuite extends munit.FunSuite:
  import SparkLayout.*

  private final class Rig:
    val kv = new InMemoryKeyValueStore
    val objects = new InMemoryObjectStore
    val tables = DynamoTables(kv, cfg.dynamo).toOption.get
    val cache = new CommunityCache(kv, cfg.dynamo.researcherMetricTable)
    val loader =
      new CommunityLoader(cache, tables.runLedger, new ArtifactWriter(objects, layout))
    def ledger = tables.runLedger.get(run, loader.Stage).toOption.get.map(_.value)
    def size(id: String, w: TimeWindow = window, of: String = run) =
      cache.community(of, id, w).map(_.map(_.size))

  private def source(jsonl: Path) =
    RowSource.jsonLinesDir[EvidenceItem](
      jsonl.resolve("communities"),
      Validate.evidence,
      CommunitiesExtra
    )

  private def decoded(line: String): EvidenceItem =
    rows(
      RowSource.jsonLines[EvidenceItem](Iterator(line), Validate.evidence, CommunitiesExtra)
    ).head

  test("community rows load with their provenance; cross-community rows are counted, not stored") {
    withRun { jsonl =>
      val r = new Rig
      val res = r.loader.load(run, source(jsonl), Some(3L)).toOption.get
      assertEquals(
        (res.read, res.created, res.unchanged, res.otherKindRows, res.rejected),
        (3L, 2L, 0L, 1L, Vector.empty)
      )
      assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 2)
      assertEquals(r.size(community), Right(Some(3L)))
      assertEquals(r.size(communityB), Right(Some(1L)))
      val got = r.cache.community(run, community, window).toOption.get.get
      assertEquals((got.runId, got.evidence.evidenceId), (run, evA))
      assertEquals(got.evidence.provenance.policyVersion, Some(policy))
      val item = r.kv
        .get(
          cfg.dynamo.researcherMetricTable,
          s"community#$community",
          s"run#$run#window#2015-2026"
        )
        .toOption
        .get
      assertEquals(item.map(_.version), Some(1L))
      // absent community, window or run is None, never a size
      assertEquals(r.size("cm:none"), Right(None))
      assertEquals(r.size(community, TimeWindow(2010, 2014)), Right(None))
      assertEquals(r.size(community, of = Ids.run("""{"other":3}""")), Right(None))
      assert(r.size("bad#id").isLeft)
    }
  }

  test("idempotent reload: no new item, object or ledger change") {
    withRun { jsonl =>
      val r = new Rig
      val first = r.loader.load(run, source(jsonl), Some(3L)).toOption.get
      val keys = r.objects.keys
      val ledger = r.ledger
      val second = r.loader.load(run, source(jsonl), Some(3L)).toOption.get
      assertEquals((second.created, second.unchanged, second.replay), (0L, 2L, true))
      assertEquals(
        (second.report.uri, second.reportOutcome),
        (first.report.uri, WriteOutcome.Unchanged)
      )
      assertEquals((r.objects.keys, r.ledger), (keys, ledger))
      assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 2)
    }
  }

  test("a changed size is refused and the stored one stays; conflicting duplicates are rejected") {
    val r = new Rig
    val three = decoded(communityRow(evA, community, "3"))
    val four = decoded(communityRow(evA, community, "4"))
    val one = decoded(communityRow(evB, communityB, "1"))
    r.loader.load(run, RowSource.of(Seq(three)))
    val changed = r.loader.load(run, RowSource.of(Seq(four))).toOption.get
    assertEquals((changed.created, changed.rejected.size), (0L, 1))
    assert(changed.rejected.head.reason.contains("different record"))
    assertEquals(r.size(community), Right(Some(3L)))
    val fresh = new Rig
    val res = fresh.loader.load(run, RowSource.of(Seq(three, four, one, one))).toOption.get
    assertEquals((res.created, res.duplicatesDropped, res.rejected.size), (1L, 1L, 2))
    assertEquals(fresh.size(community), Right(None))
  }

  test("malformed rows, other runs, bad sizes and unknown kinds are rejected and reported") {
    val r = new Rig
    val good = decoded(communityRow(evA, community, "3"))
    def with_(k: String, v: String) = good.copy(values = good.values.updated(k, v))
    val input: Vector[Either[String, EvidenceItem]] = Vector(
      Left("bad line"),
      Right(decoded(communityRow(evB, communityB, "1", Ids.run("""{"other":4}""")))),
      Right(with_("communitySize", "0")),
      Right(with_("communitySize", "many")),
      Right(good.copy(values = good.values - "communityId")),
      Right(with_("window", "recent")),
      Right(with_("recordKind", "something")),
      Right(good.copy(evidenceType = EvidenceType.METRIC_RECORD))
    )
    val src = new RowSource[EvidenceItem]:
      def rows = input.iterator
    val res = r.loader.load(run, src).toOption.get
    assertEquals((res.read, res.created, res.rejected.size), (8L, 0L, 8))
    val report = upickle.default.read[LoadReport](r.objects.getText(res.report.uri).toOption.get)
    assertEquals(report.rejected, res.rejected)
    assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 0)
  }

  test("a short input and a missing directory fail the stage before any write") {
    withRun { jsonl =>
      val r = new Rig
      assert(r.loader.load(run, source(jsonl), Some(9L)).left.exists(_.contains("expected 9")))
      val missing = r.loader.load(run, source(jsonl.resolve("absent")), Some(1L))
      assert(missing.left.exists(_.contains("is missing")), missing.toString)
      assertEquals(r.ledger.map(_.status), Some(LedgerStatus.Failed))
      assertEquals(r.kv.size(cfg.dynamo.researcherMetricTable), 0)
    }
  }

  test("a stored item that is malformed or names another community is a failure, not a size") {
    val r = new Rig
    val table = cfg.dynamo.researcherMetricTable
    val sk = s"run#$run#window#2015-2026"
    r.kv.putIfAbsent(table, KvItem(s"community#$community", sk, "{}"))
    assert(r.size(community).isLeft)
    val other = decoded(communityRow(evA, community, "3"))
    r.kv.putIfAbsent(table, KvItem(s"community#$communityB", sk, upickle.default.write(other)))
    assert(r.size(communityB).isLeft)
  }

  test("the in-memory fake applies the adapter's checks and conditions") {
    val fake = new InMemoryCommunityReader
    val row = decoded(communityRow(evA, community, "3"))
    assertEquals(fake.put(row), Right(Stored.Created))
    assertEquals(fake.put(row), Right(Stored.Unchanged))
    assert(fake.put(decoded(communityRow(evA, community, "4"))).isLeft)
    assert(fake.put(decoded(crossRow)).isLeft)
    assertEquals(fake.community(run, community, window).map(_.map(_.size)), Right(Some(3L)))
  }
