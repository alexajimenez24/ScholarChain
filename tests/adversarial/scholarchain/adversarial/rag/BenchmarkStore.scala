package scholarchain.adversarial.rag

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.text.Normalizer
import java.util.Locale

import scala.collection.mutable
import scala.util.Using

/** Benchmark question set v1 (Phase 09 step 3, test-engineer): the frozen file and an independent
  * reader of the deterministic stores.
  *
  * The reader takes Spark run `run-ccf619c509611f06` through its load manifests (`neptune.json`,
  * `dynamodb.json`), reads the JSON-lines export with ujson only, and checks every partition's row
  * count against the manifest. It imports no ScholarChain module and never calls the workflow, so
  * it is an oracle for the workflow, not a copy of it.
  *
  * The data is git-ignored. The run directory is `SCHOLARCHAIN_BENCHMARK_RUN_DIR` when set, else
  * the Phase 08 path under the repository root; without it the data tests are skipped (munit
  * assume) and only the structural tests run.
  */
object BenchmarkQuestions:

  val ResourcePath = "/benchmark/questions-v1.json"

  lazy val json: ujson.Value =
    val in = Option(getClass.getResourceAsStream(ResourcePath))
      .getOrElse(throw new IllegalStateException(s"missing test resource $ResourcePath"))
    Using.resource(in)(s => ujson.read(new String(s.readAllBytes(), UTF_8)))

  def questions: Vector[ujson.Value] = json("questions").arr.toVector

/** Recursively sorts object keys, so two values compare and print independently of key order. */
def canonical(v: ujson.Value): ujson.Value = v match
  case o: ujson.Obj => ujson.Obj.from(o.value.toSeq.sortBy(_._1).map((k, x) => k -> canonical(x)))
  case a: ujson.Arr => ujson.Arr.from(a.value.map(canonical))
  case other        => other

object BenchmarkStore:

  val GraphRun = "run-ccf619c509611f06"
  val DefaultRunDir =
    "datasets/samples/local/2026-11-24-25k/spark-out/spark/snapshot=2026-11-24/run_id=" + GraphRun

  def repoRoot: Path =
    val start = Paths.get(sys.props("user.dir")).toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.isRegularFile(p.resolve("build.sbt")) && Files.isDirectory(p.resolve("modules"))
      )
      .getOrElse(start)

  def runDir: Path =
    sys.env
      .get("SCHOLARCHAIN_BENCHMARK_RUN_DIR")
      .map(Paths.get(_))
      .getOrElse(repoRoot.resolve(DefaultRunDir))

  def present: Boolean =
    Files.isRegularFile(runDir.resolve("load-manifests").resolve("neptune.json"))

  final case class Person(name: String, conflict: Boolean, unresolved: Boolean, confidence: Double)
  final case class Work(year: Int, title: String)
  final case class Metric(
      publicationCount: Int,
      distinctCoauthorCount: Int,
      averageCoauthorsPerPaper: Double,
      maximumCoauthorsOnOnePaper: Int,
      maximumTeamWorkIds: Vector[String],
      weightedDegree: Double,
      componentId: String,
      componentSize: Int,
      communityId: String,
      bridgeScore: Double,
      crossCommunityDegree: Int,
      communitiesConnected: Int
  )
  final case class Edge(
      edgeId: String,
      a: String,
      b: String,
      joint: Int,
      first: Int,
      last: Int,
      supporting: Vector[String]
  )

  final class Data(
      val researchers: Map[String, Person],
      val works: Map[String, Work],
      val topics: Map[String, String],
      val institutions: Map[String, String],
      val authors: Map[String, Set[String]],
      val topicWorks: Map[String, Set[String]],
      val affiliated: Map[String, Set[String]],
      val metrics: Map[String, Map[String, Metric]],
      val adjacency: Map[String, Map[String, Map[String, Edge]]],
      val rowsRead: Map[String, Int]
  )

  /** Lower-case, accent-free name tokens; '-' and '.' split tokens, other symbols are dropped. */
  def tokens(name: String): Vector[String] =
    val s = Normalizer
      .normalize(name, Normalizer.Form.NFKD)
      .replaceAll("\\p{M}", "")
      .toLowerCase(Locale.ROOT)
    s.replace('-', ' ')
      .replace('.', ' ')
      .split("\\s+")
      .toVector
      .map(_.filter(Character.isLetterOrDigit))
      .filter(_.nonEmpty)

  private def manifest(name: String): ujson.Value =
    val m = ujson.read(Files.readString(runDir.resolve("load-manifests").resolve(name), UTF_8))
    require(m("runId").str == GraphRun, s"$name names run ${m("runId").str}, expected $GraphRun")
    m

  /** Streams one partition, then checks the row count against the manifest. */
  private def partition(section: ujson.Value, part: String)(f: ujson.Value => Unit): Int =
    val jl = section("jsonLines")
    val files = jl("files").arr.map(_("file").str).filter(_.startsWith(part + "/")).toVector
    require(files.nonEmpty, s"no JSON-lines file for $part")
    var n = 0
    files.foreach { file =>
      Using.resource(Files.newBufferedReader(Paths.get(jl("path").str).resolve(file), UTF_8)) { r =>
        var line = r.readLine()
        while line != null do
          if line.trim.nonEmpty then
            f(ujson.read(line))
            n += 1
          line = r.readLine()
      }
    }
    val want = jl("rowsByPartition")(part).num.toInt
    require(n == want, s"$part: read $n rows, the manifest says $want")
    n

  private def ids(v: ujson.Value): Vector[String] = v.arr.map(_.str).toVector

  /** One permit for the large in-memory loads of this test JVM: this reader's [[data]] (held for
    * the whole of [[BenchmarkQuestionSetSuite]]) and the workflow runtime load
    * ([[BenchmarkRuntime.runtime]]). They never build at the same time, which keeps the heap peak
    * of `sbt check` lower; both are test-only.
    */
  val HeavyLoad = new java.util.concurrent.Semaphore(1, true)

  @volatile private var cache: Option[Data] = None

  /** The store reader's data, read once and kept until [[release]]. */
  def data: Data = synchronized {
    if cache.isEmpty then cache = Some(load())
    cache.get
  }

  /** Drops the cached data so the heap can reclaim it. */
  def release(): Unit = synchronized { cache = None }

  private def load(): Data =
    val neptune = manifest("neptune.json")
    val dynamo = manifest("dynamodb.json")
    val metricSection = dynamo("tables")(0)("source")
    val rows = mutable.LinkedHashMap.empty[String, Int]

    val researchers = mutable.HashMap.empty[String, Person]
    rows("Researcher") = partition(neptune("nodes"), "label=Researcher") { n =>
      val p = n("properties")
      researchers(n("nodeId").str) = Person(
        p("displayName").str,
        p("identityConflict").str != "false",
        p("unresolved").str != "false",
        p("identityConfidence").str.toDouble
      )
    }
    val works = mutable.HashMap.empty[String, Work]
    rows("Work") = partition(neptune("nodes"), "label=Work") { n =>
      val p = n("properties")
      works(n("nodeId").str) = Work(p("year").str.toInt, p("title").str)
    }
    val topics = mutable.HashMap.empty[String, String]
    rows("Topic") = partition(neptune("nodes"), "label=Topic")(n =>
      topics(n("nodeId").str) = n("properties")("name").str
    )
    val institutions = mutable.HashMap.empty[String, String]
    rows("Institution") = partition(neptune("nodes"), "label=Institution")(n =>
      institutions(n("nodeId").str) = n("properties")("name").str
    )

    def edgesInto(edgeType: String): Map[String, Set[String]] =
      val m = mutable.HashMap.empty[String, mutable.Set[String]]
      partition(neptune("edges"), s"edgeType=$edgeType")(e =>
        m.getOrElseUpdate(e("toId").str, mutable.Set.empty) += e("fromId").str
      )
      m.view.mapValues(_.toSet).toMap

    val metrics = mutable.HashMap.empty[String, Map[String, Metric]]
    val adjacency = mutable.HashMap.empty[String, Map[String, Map[String, Edge]]]
    Seq("2010-2014", "2015-2026").foreach { w =>
      val m = mutable.HashMap.empty[String, Metric]
      rows(s"metrics $w") = partition(metricSection, s"windowLabel=$w") { r =>
        m(r("researcherId").str) = Metric(
          r("publicationCount").num.toInt,
          r("distinctCoauthorCount").num.toInt,
          r("averageCoauthorsPerPaper").num,
          r("maximumCoauthorsOnOnePaper").num.toInt,
          ids(r("maximumTeamWorkIds")),
          r("weightedDegree").num,
          r("componentId").str,
          r("componentSize").num.toInt,
          r("communityId").str,
          r("bridgeScore").num,
          r("crossCommunityDegree").num.toInt,
          r("communitiesConnected").num.toInt
        )
      }
      metrics(w) = m.toMap
      val adj = mutable.HashMap.empty[String, mutable.HashMap[String, Edge]]
      rows(s"coauthor-edges $w") = partition(neptune("coauthorEdges"), s"windowLabel=$w") { e =>
        val edge = Edge(
          e("edgeId").str,
          e("researcherA").str,
          e("researcherB").str,
          e("jointPublicationCount").num.toInt,
          e("firstCollaborationYear").num.toInt,
          e("lastCollaborationYear").num.toInt,
          ids(e("supportingWorkIds"))
        )
        adj.getOrElseUpdate(edge.a, mutable.HashMap.empty)(edge.b) = edge
        adj.getOrElseUpdate(edge.b, mutable.HashMap.empty)(edge.a) = edge
      }
      adjacency(w) = adj.view.mapValues(_.toMap).toMap
    }

    Data(
      researchers.toMap,
      works.toMap,
      topics.toMap,
      institutions.toMap,
      edgesInto("AUTHORED"),
      edgesInto("HAS_TOPIC"),
      edgesInto("AFFILIATED_WITH"),
      metrics.toMap,
      adjacency.toMap,
      rows.toMap
    )
