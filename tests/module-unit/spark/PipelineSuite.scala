package scholarchain.spark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, struct, to_json}
import scholarchain.core.*
import LocalSpark.*

/** Jobs 10 and 11 and the run manifest, through the driver ([[SparkPipeline.run]]) on a fixture
  * laid out exactly like the Phase 04 J07 export and the Phase 05 er-v1 decisions, each with its
  * run manifest and summary. Configuration: scholarchain-v1.yaml as loaded by the core loader.
  *
  * Fixture (DBLP works; mention IDs by the core formula):
  *   - dblp:p1 (2020) "Graph Mining Basics", venue Venue One (venue:v1), topic T1: Ann a1, Bob b1;
  *   - dblp:p2 (2021) "Graph Mining at Scale", venue Venue One (venue:v1), topics T1 and T2,
  *     institution I1 on the work: Ann a2 (affiliation I1), Bob b2, Cat c2;
  *   - dblp:p3 (2021) "Solo Work", venue name Venue Two (no venue ID): Dan d3;
  *   - one works line that is not JSON (a counted reject).
  *
  * er-v1 decisions: ACCEPTED a1-a2 (0.95) and b1-b2 (0.97). Researchers: Ann {a1, a2}, Bob {b1,
  * b2}, Cat {c2}, Dan {d3}.
  *
  * Configured windows 1950-2026, 2000-2009, 2010-2014, 2015-2026: the first and the last hold every
  * work; the two others are empty. Hand-worked values in each non-empty window:
  *   - edges Ann-Bob (p1, p2: weight 2, 2020-2021), Ann-Cat and Bob-Cat (p2: weight 1, 2021);
  *   - Ann: P 2, C 2, T 1 + 2 = 3, A 1.50, M 2 on dblp:p2, W 2 + 1 = 3; Bob the same;
  *   - Cat: P 1, C 2, T 2, M 2, W 2; Dan: P 1, C 0, T 0, M 0 on dblp:p3, W 0;
  *   - components {Ann, Bob, Cat} (3, the largest) and {Dan}, component label the smallest of the
  *     three IDs;
  *   - label propagation (self vote = strongest edge: Ann 2, Bob 2, Cat 1): round 1 Ann and Bob tie
  *     at 2 between their labels and take x = min(Ann, Bob); Cat ties at 1 between all three and
  *     takes the smallest. If that is x, nothing changes; if it is Cat, round 2 gives Cat 2 votes
  *     for x against 1 for itself. Either way one community labelled x = min(Ann, Bob), and Dan
  *     alone: one community of two or more members;
  *   - coreness 2 in the triangle, 0 for Dan; 3 connected researchers <= 16 sampled sources, so
  *     betweenness is exact: 0 in a triangle; participation 0, one community connected;
  *   - path evidence: each of the 3 sources takes its reach (2 others); a pair of two sources is
  *     requested from the smaller ID only, so 3 pairs, each 1 hop, 1 shortest path;
  *   - topics: T1 on p1 and p2 (2 works, 2020-2021, 3 researchers, Inst One on 1 work, Venue One on
  *     2); T2 on p2 (1 work, 2021-2021, 3 researchers, Inst One 1, Venue One 1).
  *
  * Documents: per non-empty window 4 profiles + 1 community + 3 paths + 2 topics = 10; 20 in all.
  * Graph: nodes 4 researchers + 3 works + 2 topics + 1 institution + 1 venue = 11; edges AUTHORED
  * 6, COAUTHORED_WITH 3 per non-empty window = 6, HAS_TOPIC 3, PUBLISHED_IN 2, AFFILIATED_WITH 1 = 18.
  *
  * Institutions (`values.institutionId`): Ann's own mention on p2 is affiliated with I1, so Ann's
  * profiles, the community and both topics (I1 is on p2) name openalex:I1; Bob, Cat, Dan and the
  * path documents have none.
  *
  * Abstracts (J02-OPENALEX `abstracts`, located through the Hadoop summary): dblp:p1 with its
  * `abstractTextRef` (emitted, canonical work dblp:p1), dblp:p2 whose `textHash` is not the hash of
  * its text (ABSTRACT_HASH_MISMATCH) and openalex:W404, not an exported work
  * (ABSTRACT_WORK_NOT_FOUND): 3 read, 1 row, 2 counted rejects.
  */
class PipelineSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(8, "min")
  private val cfg = ConfigLoader.load(Map.empty).fold(e => throw new AssertionError(e), identity)
  private val Snap = "2026-11-22"
  private val root = Files.createTempDirectory("scholarchain-pipeline")

  private def write(p: Path, text: String): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(UTF_8))

  // Upstream run IDs come from their manifests, as the Hadoop and er-v1 drivers name them.
  private val hManifest =
    """{"pipeline":"hadoop-v2","normalization":"norm-v1","snapshot":"2026-11-22","maxTeam":300}"""
  private val hId = Ids.run(hManifest)
  private val exportDir = root.resolve(s"hadoop/export/snapshot=$Snap/run_id=$hId")
  private val eManifest = ujson
    .Obj(
      "stage" -> "entity-resolution",
      "upstreamRunId" -> hId,
      "snapshot" -> Snap,
      "policyVersion" -> "er-v1",
      "policySha256" -> "82ce9ae5"
    )
    .render()
  private val eId = Ids.run(eManifest)
  private val decisionsDir =
    root.resolve(s"er/match-decisions/policy=er-v1/snapshot=$Snap/run_id=$eId")

  private def prov(src: SourceType, rec: String, schema: String, run: String = hId) =
    Provenance(src, rec, Snap, run, schema, Some("norm-v1"))

  private val m = (w: String, i: Int) => Ids.mention(w, i)
  private val (a1, b1, a2, b2, c2, d3) =
    (
      m("dblp:p1", 0),
      m("dblp:p1", 1),
      m("dblp:p2", 0),
      m("dblp:p2", 1),
      m("dblp:p2", 2),
      m("dblp:p3", 0)
    )
  private val ann = Ids.researcherFromMentions(Vector(a1, a2))
  private val bob = Ids.researcherFromMentions(Vector(b1, b2))
  private val cat = Ids.researcherFromMentions(Vector(c2))
  private val dan = Ids.researcherFromMentions(Vector(d3))

  private case class W(
      id: String,
      year: Int,
      title: String,
      venueId: Option[String],
      venue: String,
      topics: Vector[String],
      insts: Vector[String],
      authors: Vector[(String, Option[String])]
  )
  private val ws = Vector(
    W(
      "dblp:p1",
      2020,
      "Graph Mining Basics",
      Some("venue:v1"),
      "Venue One",
      Vector("openalex:T1"),
      Vector(),
      Vector("Ann Lee" -> None, "Bob Ray" -> None)
    ),
    W(
      "dblp:p2",
      2021,
      "Graph Mining at Scale",
      Some("venue:v1"),
      "Venue One",
      Vector("openalex:T1", "openalex:T2"),
      Vector("openalex:I1"),
      Vector("Ann Lee" -> Some("openalex:I1"), "Bob Ray" -> None, "Cat Diaz" -> None)
    ),
    W("dblp:p3", 2021, "Solo Work", None, "Venue Two", Vector(), Vector(), Vector("Dan Hu" -> None))
  )
  private def mentions(w: W) = w.authors.zipWithIndex.map { case ((name, aff), i) =>
    AuthorMention(
      m(w.id, i),
      w.id,
      i,
      name,
      name.toLowerCase,
      None,
      None,
      aff,
      prov(SourceType.DBLP, w.id, SchemaVersions.Mention)
    )
  }
  private val P1Abstract = "Graph mining \"basics\": a naïve 図 approach, with commas, and\ttabs."
  private val P2Abstract = "Scaling graph mining."
  private def abstractRef(id: String): Option[String] = id match
    case "dblp:p1" => Some(Hashing.tagged(P1Abstract))
    case "dblp:p2" => Some(Hashing.tagged(P2Abstract))
    case _         => None
  private def work(w: W) = WorkRecord(
    SchemaVersions.Work,
    w.id,
    SourceType.DBLP,
    w.id.drop(5),
    "inproceedings",
    w.title,
    w.title.toLowerCase,
    Some(w.year),
    w.venueId,
    Some(w.venue),
    None,
    None,
    None,
    Vector.empty,
    mentions(w),
    w.topics,
    w.insts,
    abstractRef(w.id),
    prov(SourceType.DBLP, w.id, SchemaVersions.Work)
  )
  private val abstractsDir =
    root.resolve(s"hadoop/normalized/source=openalex/snapshot=$Snap/run_id=$hId")
  private def abstractLine(workId: String, hash: String, text: String, rec: String) = ujson
    .Obj(
      "workId" -> workId,
      "textHash" -> hash,
      "text" -> text,
      "provenance" -> upickle.default.writeJs(prov(SourceType.OPENALEX, rec, "abstract-v1"))
    )
    .render()
  private def authorPaper(w: W, am: AuthorMention) = ujson
    .Obj(
      "mentionId" -> am.mentionId,
      "workId" -> w.id,
      "position" -> am.position,
      "normalizedName" -> am.normalizedName,
      "orcid" -> ujson.Null,
      "year" -> w.year,
      "teamSize" -> w.authors.size,
      "provenance" -> upickle.default.writeJs(prov(SourceType.DBLP, w.id, "author-paper-v1"))
    )
    .render()
  private def pairs(w: W) =
    val ms = mentions(w)
    for
      (x, i) <- ms.zipWithIndex
      y <- ms.drop(i + 1)
    yield
      val (p, q) = if x.mentionId < y.mentionId then (x, y) else (y, x)
      JsonLines.encode(
        CoauthorPairCandidate(
          p.mentionId,
          q.mentionId,
          w.id,
          Some(w.year),
          p.position,
          q.position,
          w.authors.size,
          prov(SourceType.DBLP, w.id, SchemaVersions.Pair)
        )
      )
  private def decision(s: String, t: String, conf: Double) = JsonLines.encode(
    MatchDecision(
      SchemaVersions.Match,
      Ids.decision(EntityKind.RESEARCHER, s, t, "er-v1"),
      EntityKind.RESEARCHER,
      s,
      t,
      Map("er_score" -> conf),
      Vector("R-R9"),
      MatchOutcome.ACCEPTED,
      MatchPath.PROBABILISTIC,
      conf,
      Vector("X"),
      "er-v1",
      None,
      None,
      Provenance(SourceType.SCHOLARCHAIN, "mc:x", Snap, eId, SchemaVersions.Match, Some("er-v1"))
    )
  )

  private def part(dir: Path, kind: String, lines: Seq[String]): Unit =
    write(dir.resolve(s"$kind/part-r-00000"), lines.mkString("", "\n", "\n"))

  private lazy val outcome: SparkPipeline.Outcome =
    part(exportDir, "works", ws.map(w => JsonLines.encode(work(w))) :+ "{not json")
    part(exportDir, "mentions", ws.flatMap(mentions).map(JsonLines.encode(_)))
    part(exportDir, "authorpapers", ws.flatMap(w => mentions(w).map(authorPaper(w, _))))
    part(exportDir, "pairs", ws.flatMap(pairs))
    part(
      exportDir,
      "topics",
      Seq(
        JsonLines.encode(
          Topic(
            "openalex:T1",
            "Graph Mining",
            prov(SourceType.OPENALEX, "T1", SchemaVersions.Topic)
          )
        ),
        JsonLines.encode(
          Topic("openalex:T2", "Databases", prov(SourceType.OPENALEX, "T2", SchemaVersions.Topic))
        )
      )
    )
    part(
      exportDir,
      "institutions",
      Seq(
        JsonLines.encode(
          Institution(
            "openalex:I1",
            "Inst One",
            Some("US"),
            None,
            prov(SourceType.OPENALEX, "I1", SchemaVersions.Institution)
          )
        )
      )
    )
    part(
      exportDir,
      "venues",
      Seq(
        JsonLines.encode(
          Venue(
            "venue:v1",
            "Venue One",
            "conference",
            prov(SourceType.DBLP, "venue:v1", SchemaVersions.Venue)
          )
        )
      )
    )
    write(exportDir.resolve("_SUCCESS"), "")
    write(
      abstractsDir.resolve("abstracts/part-m-00000"),
      Seq(
        abstractLine("dblp:p1", Hashing.tagged(P1Abstract), P1Abstract, "W1"),
        abstractLine("dblp:p2", "sha256:" + "0" * 64, P2Abstract, "W2"),
        abstractLine("openalex:W404", Hashing.tagged("Lost."), "Lost.", "W404")
      ).mkString("", "\n", "\n")
    )
    part(decisionsDir, "accepted", Seq(decision(a1, a2, 0.95), decision(b1, b2, 0.97)))
    write(decisionsDir.resolve("_SUCCESS"), "")
    write(root.resolve(s"hadoop/run-manifests/$hId.json"), hManifest)
    write(
      root.resolve(s"hadoop/run-manifests/$hId-summary.json"),
      ujson
        .Obj(
          "runId" -> hId,
          "stages" -> ujson.Obj(
            "J07" -> ujson.Obj("output" -> exportDir.toString),
            "J02-OPENALEX" -> ujson.Obj("output" -> abstractsDir.toString)
          )
        )
        .render()
    )
    write(root.resolve(s"er/run-manifests/$eId.json"), eManifest)
    write(
      root.resolve(s"er/run-manifests/$eId-summary.json"),
      ujson.Obj("runId" -> eId, "decisions" -> decisionsDir.toString).render()
    )
    val args = SparkArgs(
      root.resolve(s"hadoop/run-manifests/$hId.json").toString,
      root.resolve(s"er/run-manifests/$eId.json").toString,
      root.resolve("out").toString,
      None
    )
    SparkPipeline.run(spark, args, cfg).fold(e => throw new AssertionError(e), identity)

  private def table(name: String): DataFrame = spark.read.parquet(s"${outcome.base}/$name")

  /** Rows as JSON lines with every null written (core decoding needs the Option keys). */
  private def jsonLines(df: DataFrame, fields: Seq[String]): Vector[String] =
    df.select(to_json(struct(fields.map(col)*), Map("ignoreNullFields" -> "false")))
      .collect()
      .map(_.getString(0))
      .toVector

  private val EvidenceFields = Seq(
    "schemaVersion",
    "evidenceId",
    "evidenceType",
    "workId",
    "researcherIds",
    "sourceUri",
    "chunkId",
    "graphPath",
    "score",
    "values",
    "text",
    "textHash",
    "provenance"
  )

  private def evidence(name: String): Vector[EvidenceItem] =
    jsonLines(table(name), EvidenceFields).map(l =>
      JsonLines
        .decodeValid[EvidenceItem](l, Validate.evidence)
        .fold(e => fail(s"$name: $e\n$l"), identity)
    )

  test("run manifest: written first, hashes to the run ID, names both upstream runs; status") {
    val text = new String(Files.readAllBytes(Path.of(outcome.manifestPath)), UTF_8)
    assertEquals(Ids.run(text), outcome.runId)
    val mf = ujson.read(text)
    assertEquals(mf("inputs")("hadoop")("runId").str, hId)
    assertEquals(mf("inputs")("entityResolution")("runId").str, eId)
    assertEquals(
      outcome.base,
      s"${root.resolve("out")}/spark/snapshot=$Snap/run_id=${outcome.runId}"
    )
    val s = outcome.summary
    assertEquals(s("status").str, "SUCCEEDED")
    assertEquals(outcome.invariantFailures, 0L, s("invariantsNonZero").render())
    assert(s("invariantsChecked").num >= 40, "every job's invariants are collected")
    // The not-JSON works line and the two bad abstracts are counted rejects in the rejects table.
    assertEquals(s("report")("inputs")("works")("rejected").num, 1.0)
    assertEquals(
      table("rejects")
        .select("stage", "reason")
        .collect()
        .map(r => (r.getString(0), r.getString(1)))
        .toVector
        .sorted,
      Vector(
        "spark-abstracts" -> "ABSTRACT_HASH_MISMATCH",
        "spark-abstracts" -> "ABSTRACT_WORK_NOT_FOUND",
        "spark-read-works" -> "SCHEMA_MISMATCH"
      )
    )
  }

  test("job 10: researcher profile text, chunk and evidence IDs by the core formulas") {
    val docs = evidence("semantic-summaries")
    val byType = docs.groupBy(_.values("docType")).view.mapValues(_.size).toMap
    assertEquals(
      byType,
      Map(
        "researcher-profile" -> 8,
        "collaboration-community" -> 2,
        "collaboration-path" -> 6,
        "topic-institution-summary" -> 4
      )
    )
    docs.foreach { d =>
      val v = d.values
      assertEquals(
        d.chunkId,
        Some(Ids.chunk(v("entityId"), "metrics-v1", v("docType"), 0, d.text.get))
      )
      assertEquals(d.evidenceId, Ids.evidence(EvidenceType.SEMANTIC_PROFILE, d.chunkId.get))
      assertEquals(v("vectorIndex"), cfg.vectors.indexes(Summaries.IndexKeys(v("docType"))))
    }
    val w = TimeWindow(1950, 2026)
    // Component and community IDs: hashes of (label | window | policy); labels worked out above.
    val label = Seq(ann, bob, cat).min
    val cc = "cc:" + Hashing.short(s"$label|${w.label}|graph-v1", 16)
    val cm = "cm:" + Hashing.short(s"${Seq(ann, bob).min}|${w.label}|graph-v1", 16)
    val annDoc = docs.find(d => d.values("entityId") == ann && d.values("window") == w.label).get
    assertEquals(
      annDoc.text.get,
      s"Researcher profile, window 1950-2026. Name: Ann Lee ($ann). Publications: 2. " +
        "Distinct coauthors: 2. Total coauthor occurrences: 3. Average coauthors per paper: 1.50. " +
        "Largest team: 2 coauthors on one paper; works with that team size: 1, listed: " +
        "Graph Mining at Scale (dblp:p2, 2021). " +
        "Weighted degree (joint publications summed over coauthors): 3. " +
        s"Component $cc with 3 researchers, the largest component. Community: $cm. Coreness: 2. " +
        "Coauthors outside own community: 0. Communities connected: 1. " +
        "Participation coefficient: 0.000. Sampled betweenness estimate: 0.0000. " +
        "Topics (works): Graph Mining (2); Databases (1). Venues (works): Venue One (2). " +
        "Institutions (works): Inst One (1). Identity confidence: 0.95."
    )
    assertEquals(annDoc.researcherIds, Vector(ann))
    assertEquals(annDoc.provenance.policyVersion, Some("metrics-v1"))
    val danDoc = docs.find(d => d.values("entityId") == dan && d.values("window") == w.label).get
    assert(
      danDoc.text.get.contains(
        "Largest team: 0 coauthors on one paper; works with that team size: 1, listed: Solo Work (dblp:p3, 2021)."
      )
    )
    assert(
      danDoc.text.get.contains("Topics (works): none recorded. Venues (works): Venue Two (1).")
    )
  }

  test("job 10: community, path and topic documents only from computed tables") {
    val docs = evidence("semantic-summaries").filter(_.values("window") == "1950-2026")
    val community = docs.filter(_.values("docType") == "collaboration-community")
    assertEquals(community.size, 1, "Dan's one-member community has no document")
    val ct = community.head.text.get
    assert(
      ct.contains(
        "Members: 3. Coauthor edges inside the community: 3. Coauthor edges to other communities: 0."
      ),
      ct
    )
    // Ann and Bob have 2 coauthors each, Cat 2: ties by name.
    assert(
      ct.contains(
        "Members (distinct coauthors in the window): Ann Lee (2); Bob Ray (2); Cat Diaz (2)."
      ),
      ct
    )
    assert(ct.contains("Topics of members' works (works): Graph Mining (2); Databases (1)."), ct)
    val paths = docs.filter(_.values("docType") == "collaboration-path")
    assertEquals(paths.size, 3)
    val ab = paths.find(_.researcherIds.toSet == Set(ann, bob)).get
    assert(ab.text.get.contains("Shortest path length: 1 hops; 1 shortest paths of this length."))
    val edgeAB = Ids.coauthorEdge(ann, bob, TimeWindow(1950, 2026), "graph-v1")
    assert(
      ab.text.get.contains(s"Coauthor edges: $edgeAB (2 joint publications, 2020-2021)."),
      ab.text.get
    )
    val topics = docs
      .filter(_.values("docType") == "topic-institution-summary")
      .map(d => d.values("entityId") -> d.text.get)
      .toMap
    assertEquals(
      topics("openalex:T1"),
      "Topic summary, window 1950-2026: Graph Mining (openalex:T1). Works: 2, published 2020-2021. " +
        "Researchers: 3. Institutions on these works (works): Inst One (1). Venues (works): Venue One (2)."
    )
  }

  test("job 11: tables decode as core records and pass core validation") {
    val nodes = jsonLines(table("graph-nodes"), Seq("nodeId", "label", "properties", "provenance"))
      .map(l =>
        JsonLines.decodeValid[GraphNode](l, Validate.graphNode).fold(e => fail(e), identity)
      )
    assertEquals(
      nodes.groupBy(_.label).view.mapValues(_.size).toMap,
      Map("Researcher" -> 4, "Work" -> 3, "Topic" -> 2, "Institution" -> 1, "Venue" -> 1)
    )
    // Identity provenance: Researcher and Work nodes name the er-v1 policy that made them, and a
    // researcher node lists its member mentions.
    val annNode = nodes.find(_.nodeId == ann).get
    assertEquals(annNode.properties("mentionIds"), Vector(a1, a2).sorted.mkString("|"))
    assertEquals(
      nodes
        .filter(n => n.label == "Researcher" || n.label == "Work")
        .map(_.provenance.policyVersion)
        .toSet,
      Set(Some("er-v1"))
    )
    assertEquals(nodes.find(_.label == "Topic").get.provenance.policyVersion, Some("graph-v1"))
    val edges = jsonLines(
      table("graph-edges"),
      Seq("edgeId", "fromId", "edgeType", "toId", "properties", "provenance")
    )
      .map(l =>
        JsonLines.decodeValid[GraphEdge](l, Validate.graphEdge).fold(e => fail(e), identity)
      )
    assertEquals(
      edges.groupBy(_.edgeType).view.mapValues(_.size).toMap,
      Map(
        "AUTHORED" -> 6,
        "COAUTHORED_WITH" -> 6,
        "HAS_TOPIC" -> 3,
        "PUBLISHED_IN" -> 2,
        "AFFILIATED_WITH" -> 1
      )
    )
    val authored =
      edges.find(e => e.edgeType == "AUTHORED" && e.fromId == ann && e.toId == "dblp:p2").get
    assertEquals(authored.edgeId, Ids.graphEdge("AUTHORED", ann, "dblp:p2", "graph-v1", a2))
    val metrics = jsonLines(
      table("researcher-metrics"),
      Seq(
        "schemaVersion",
        "researcherId",
        "publicationCount",
        "distinctCoauthorCount",
        "totalCoauthorOccurrences",
        "averageCoauthorsPerPaper",
        "maximumCoauthorsOnOnePaper",
        "maximumTeamWorkIds",
        "weightedDegree",
        "componentId",
        "componentSize",
        "communityId",
        "bridgeScore",
        "participationCoefficient",
        "crossCommunityDegree",
        "communitiesConnected",
        "coreness",
        "window",
        "metricVersion",
        "provenance"
      )
    )
      .map(l =>
        JsonLines.decodeValid[ResearcherMetrics](l, Validate.metrics).fold(e => fail(e), identity)
      )
    assertEquals(metrics.size, 8, "4 researchers in each of the 2 non-empty windows")
    val cE = jsonLines(
      table("coauthor-edges"),
      Seq(
        "schemaVersion",
        "edgeId",
        "researcherA",
        "researcherB",
        "jointPublicationCount",
        "firstCollaborationYear",
        "lastCollaborationYear",
        "representativeWorkIds",
        "window",
        "policyVersion",
        "provenance"
      )
    )
      .map(l => JsonLines.decodeValid[CoauthorEdge](l, Validate.edge).fold(e => fail(e), identity))
    assertEquals(cE.size, 6)
    for name <- Seq("components", "communities", "path-evidence", "metric-comparisons") do
      assert(evidence(name).nonEmpty, name)
    // 2 components and 2 communities per non-empty window; no cross-community edge.
    assertEquals(evidence("components").size, 4)
    assertEquals(evidence("communities").map(_.values("recordKind")).toSet, Set("community"))
    val paths = evidence("path-evidence")
    assertEquals((paths.size, paths.count(_.values("found") == "true")), (6, 6))
    assert(paths.forall(p => p.graphPath.exists(_.size == 2)))
    // Temporal: 2010-2014 (empty) -> 2015-2026: the 4 researchers appear, none was present before.
    val cmp = evidence("metric-comparisons")
    assertEquals(cmp.map(_.values("toWindow")).toSet, Set("2015-2026"))
    assertEquals(cmp.size, 4)
    assert(
      cmp.forall(c => c.values("presentBefore") == "false" && c.values("presentAfter") == "true")
    )
  }

  test(
    "job 11: load manifests for Neptune, DynamoDB, S3 Vectors and S3 name the tables and counts"
  ) {
    def lm(k: String) =
      ujson.read(Files.readAllBytes(Path.of(s"${outcome.base}/load-manifests/$k.json")))
    val n = lm("neptune")
    assertEquals((n("nodes")("rows").num, n("edges")("rows").num), (11.0, 18.0))
    assertEquals(n("runId").str, outcome.runId)
    // COAUTHORED_WITH is window-scoped: one edge per pair per window, filtered on `window`.
    assertEquals(n("coauthoredWith")("windowProperty").str, "window")
    // Every exported coauthor window is listed (here both are configured), with its row counts.
    assertEquals(
      n("coauthoredWith")("windows").arr.map(_.str).toVector,
      cfg.graph.windows.map(_.label)
    )
    val exported = Map("1950-2026" -> 3.0, "2015-2026" -> 3.0)
    assertEquals(n("coauthoredWith")("exportedWindows").obj.view.mapValues(_.num).toMap, exported)
    assertEquals(
      n("coauthoredWith")("graphEdgesByWindow").obj.view.mapValues(_.num).toMap,
      exported
    )
    assertEquals(n("coauthorEdges")("rows").num, 6.0)
    val d = lm("dynamodb")("tables")(0)
    assertEquals(d("tableConfigKey").str, "dynamo.researcherMetricTable")
    assertEquals(d("source")("rows").num, 8.0)
    val v = lm("s3vectors")
    assertEquals(
      v("indexes").arr.map(x => x("index").str -> x("rows").num).toMap,
      Map(
        "researcher-profile-v1" -> 8.0,
        "collaboration-community-v1" -> 2.0,
        "collaboration-path-v1" -> 6.0,
        "topic-institution-summary-v1" -> 4.0
      )
    )
    assertEquals(v("dimension").num, cfg.vectors.dimension.toDouble)
    val s3 = lm("s3")("tables").arr.map(_("table").str).toSet
    assertEquals(
      s3,
      Set(
        "graph-nodes",
        "graph-edges",
        "coauthor-edges",
        "researcher-metrics",
        "components",
        "communities",
        "path-evidence",
        "metric-comparisons",
        "semantic-summaries",
        "paper-abstracts",
        "rejects"
      )
    )
    val ab = v("abstracts")
    assertEquals(
      (ab("docType").str, ab("index").str, ab("rows").num),
      ("paper-abstract", cfg.vectors.indexes("abstracts"), 1.0)
    )
    // The key rule names who makes abstract chunk IDs (rag's renderer), not a Spark formula.
    val keyRule = ab("keyRule").str
    assert(keyRule.contains("rag's renderer"), keyRule)
    assert(keyRule.contains("tpl-paper-abstract-v2"), keyRule)
    assert(keyRule.contains("several chunks"), keyRule)
    assert(!keyRule.contains("ordinal 0, text"), keyRule)
    // Partitioned Parquet: one folder per window of a windowed table.
    assert(Files.isDirectory(Path.of(s"${outcome.base}/researcher-metrics/windowLabel=1950-2026")))
  }

  // Phase 08 step 00 (Phase 07 gate decisions) ------------------------------------------------------

  private def lines(dir: Path): Vector[String] =
    import scala.jdk.CollectionConverters.*
    Files
      .walk(dir)
      .iterator
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
      .toVector
      .sorted
      .flatMap(p => new String(Files.readAllBytes(p), UTF_8).linesIterator.filter(_.nonEmpty))

  /** The table's rows as Spark reads the Parquet back, every column, nulls written. */
  private def parquetRows(name: String): Set[ujson.Value] =
    val df = table(name)
    jsonLines(df, df.columns.toSeq).map(ujson.read(_)).toSet

  private final case class AbstractRow(
      workId: String,
      textHash: String,
      text: String,
      provenance: Provenance
  ) derives upickle.default.ReadWriter

  test("JSON lines: every loader table, same rows and fields as the Parquet, listed in manifests") {
    def lm(k: String) =
      ujson.read(Files.readAllBytes(Path.of(s"${outcome.base}/load-manifests/$k.json")))
    val s3 = lm("s3")("tables").arr.map(t => t("table").str -> t).toMap
    val withJson = s3.filter(_._2.obj.contains("jsonLines")).keySet
    assertEquals(
      withJson,
      Set(
        "graph-nodes",
        "graph-edges",
        "coauthor-edges",
        "researcher-metrics",
        "communities",
        "semantic-summaries",
        "paper-abstracts"
      )
    )
    // The target manifests carry the same blocks.
    assertEquals(
      lm("dynamodb")("communities")("source")("jsonLines"),
      s3("communities")("jsonLines")
    )
    assertEquals(lm("dynamodb")("tables").arr.size, 1, "the researcher-metric entry is unchanged")
    assertEquals(lm("neptune")("nodes")("jsonLines"), s3("graph-nodes")("jsonLines"))
    assertEquals(lm("neptune")("edges")("jsonLines"), s3("graph-edges")("jsonLines"))
    assertEquals(lm("neptune")("coauthorEdges")("jsonLines"), s3("coauthor-edges")("jsonLines"))
    assertEquals(
      lm("dynamodb")("tables")(0)("source")("jsonLines"),
      s3("researcher-metrics")("jsonLines")
    )
    assertEquals(lm("s3vectors")("source")("jsonLines"), s3("semantic-summaries")("jsonLines"))
    assertEquals(
      lm("s3vectors")("abstracts")("source")("jsonLines"),
      s3("paper-abstracts")("jsonLines")
    )

    def decoded[T: upickle.default.ReadWriter](
        name: String,
        validate: T => Validate.Errors
    ): Vector[T] =
      val t = s3(name)
      val j = t("jsonLines")
      val dir = Path.of(j("path").str)
      assertEquals(dir, Path.of(s"${outcome.base}/jsonl/$name"))
      // Listed files exist, and nothing else holds data.
      val listed = j("files").arr.map(f => f("file").str).toVector.sorted
      assert(listed.nonEmpty, name)
      listed.foreach(f => assert(Files.isRegularFile(dir.resolve(f)), s"$name: $f"))
      val ls = lines(dir)
      assertEquals(ls.size.toDouble, t("rows").num, name)
      assertEquals(j("rows").num, t("rows").num, name)
      assertEquals(j("rowsByPartition"), t("rowsByPartition"), name)
      // Identical rows and fields: every column of the Parquet table, partition columns included.
      assertEquals(ls.map(ujson.read(_)).toSet, parquetRows(name), name)
      // Dropping the declared extra fields leaves the core record, decoded strictly.
      val extras = j("extraFields").arr.map(_.str).toSet
      ls.map { l =>
        val o = ujson.read(l).obj
        extras.foreach(o.remove)
        JsonLines
          .decodeValid[T](ujson.write(o), validate)
          .fold(e => fail(s"$name: $e\n$l"), identity)
      }

    val nodes = decoded[GraphNode]("graph-nodes", Validate.graphNode)
    assertEquals(nodes.size, 11)
    assertEquals(decoded[GraphEdge]("graph-edges", Validate.graphEdge).size, 18)
    assertEquals(decoded[CoauthorEdge]("coauthor-edges", Validate.edge).size, 6)
    assertEquals(decoded[ResearcherMetrics]("researcher-metrics", Validate.metrics).size, 8)
    val docs = decoded[EvidenceItem]("semantic-summaries", Validate.evidence)
    assertEquals(docs.size, 20)
    assert(docs.forall(d => d.textHash == d.text.map(Hashing.tagged)))
    assertEquals(decoded[AbstractRow]("paper-abstracts", _ => Vector.empty).size, 1)
    // C14: per non-empty window one community {Ann, Bob, Cat} of 3 and {Dan} of 1; no cross edge.
    val cms = decoded[EvidenceItem]("communities", Validate.evidence)
    assert(cms.forall(_.evidenceType == EvidenceType.COMMUNITY_RECORD))
    assertEquals(
      cms.map(c => (c.values("window"), c.values("recordKind"), c.values("communitySize"))).sorted,
      Vector(
        ("1950-2026", "community", "1"),
        ("1950-2026", "community", "3"),
        ("2015-2026", "community", "1"),
        ("2015-2026", "community", "3")
      )
    )
    assertEquals(
      s3("communities")("jsonLines")("extraFields").arr.map(_.str).toVector,
      Vector("windowLabel", "recordKind")
    )
    // Partition folders mirror the Parquet layout.
    assert(
      Files.isDirectory(
        Path.of(
          s"${outcome.base}/jsonl/semantic-summaries/docType=researcher-profile/windowLabel=1950-2026"
        )
      )
    )
    val inv = outcome.summary("report")("job11")
    assertEquals(inv("jsonLinesRows_graph-edges").num, 18.0)
    assertEquals(inv("invariant_jsonLinesIdMismatch_researcher-metrics").num, 0.0)
  }

  test("semantic summaries carry institutionId: the first institution of the document's list") {
    val docs = evidence("semantic-summaries").filter(_.values("window") == "1950-2026")
    def inst(docType: String, entity: String) =
      docs
        .find(d => d.values("docType") == docType && d.values("entityId") == entity)
        .flatMap(_.values.get("institutionId"))
    assertEquals(inst("researcher-profile", ann), Some("openalex:I1"))
    assertEquals(inst("researcher-profile", bob), None)
    assertEquals(inst("researcher-profile", dan), None)
    val community = docs.find(_.values("docType") == "collaboration-community").get
    assertEquals(community.values.get("institutionId"), Some("openalex:I1"))
    assertEquals(inst("topic-institution-summary", "openalex:T1"), Some("openalex:I1"))
    assertEquals(inst("topic-institution-summary", "openalex:T2"), Some("openalex:I1"))
    assert(
      docs
        .filter(_.values("docType") == "collaboration-path")
        .forall(!_.values.contains("institutionId"))
    )
  }

  test("paper abstracts: source text only, canonical work, provenance, counted rejects") {
    val rows = lines(Path.of(s"${outcome.base}/jsonl/paper-abstracts")).map(ujson.read(_))
    assertEquals(rows.size, 1)
    val r = rows.head
    assertEquals(r("workId").str, "dblp:p1")
    assertEquals(r("text").str, P1Abstract, "the text is the source row's, byte for byte")
    assertEquals(r("textHash").str, Hashing.tagged(P1Abstract))
    assertEquals(r("sourceWorkId").str, "dblp:p1")
    assertEquals(r("upstreamRunId").str, hId)
    val p = upickle.default.read[Provenance](r("provenance"))
    assertEquals(
      p,
      Provenance(SourceType.OPENALEX, "W1", Snap, outcome.runId, "abstract-v1", Some("er-v1"))
    )
    val a = outcome.summary("report")("abstracts")
    assertEquals(
      (a("inputPresent").bool, a("read").num, a("emitted").num, a("notEmitted").num),
      (true, 3.0, 1.0, 2.0)
    )
    assertEquals(a("notEmitted_ABSTRACT_HASH_MISMATCH").num, 1.0)
    assertEquals(a("notEmitted_ABSTRACT_WORK_NOT_FOUND").num, 1.0)
    val mf = ujson.read(Files.readAllBytes(Path.of(outcome.manifestPath)))
    assertEquals(mf("inputs")("hadoop")("abstracts").str, s"$abstractsDir/abstracts")
    assertEquals(mf("logic").str, "spark-v5")
  }
