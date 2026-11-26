package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.{col, when}
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}
import scholarchain.core.*
import LocalSpark.*

/** Job 1 (canonical tables) and the job 2 pair mapping, on records serialized exactly as Hadoop J07
  * and the er-v1 match job write them (core records through `JsonLines.encode`, read back with the
  * Spark `Schemas`).
  *
  * Fixture. Four source works:
  *   - dblp:w1 (2020) Ann Lee m:a1, Bob Ray 0001 m:b1; crossref:w1 (2020) Ann Lee m:a2, Bob Ray
  *     m:b2 (the same paper, linked by an ACCEPTED WORK decision);
  *   - dblp:w2 (2021) Ann Lee m:a3, Cat Diaz m:c3, A. Lee m:x3;
  *   - openalex:w3 (2022) Ann Lee m:a4 (affiliation openalex:I1), Cat Diaz m:c4 (affiliation
  *     openalex:I2, not in the institution table); a WORK CONFLICT with dblp:w2 keeps it apart.
  *
  * Researcher decisions: ACCEPTED a1-a2 (0.99), a1-a3 (0.95), a3-x3 (0.92), a2-orcid (0.99), b1-b2
  * (0.97), c3-c4 (0.90), b1-m:zz (m:zz is not in the export: dropped); UNRESOLVED a1-a4 (never
  * merged; a1 and a4 are in different clusters anyway); CONFLICT a2-x3.
  *
  * Contradiction cut, worked by hand. The accepted links alone make one cluster {a1, a2, a3, x3,
  * orcid} that holds both ends of CONFLICT a2-x3, so it is rebuilt from its 4 links in descending
  * confidence, ties by (a, b): a1-a2 0.99 and m:a2-orcid 0.99 ("m:a1" < "m:a2"), a1-a3 0.95, a3-x3
  * 0.92. The first three join sets without x3: applied. a3-x3 would put x3 into the set holding a2:
  * refused. That is the weakest link (0.92) of the path a2-a1-a3-x3 (0.99, 0.95, 0.92).
  *
  * Expected researchers (5): Ann {a1,a2,a3} with ID orcid:0000-0002-1825-0097, confidence min(0.99,
  * 0.99, 0.95) = 0.95, unresolved (a1-a4 touches a1), identity conflict (the refused a3-x3 touches
  * a3); A. Lee {x3}, identity conflict, not unresolved, confidence 1.0 (no applied link); Bob
  * {b1,b2}; Cat {c3,c4}; and the unmerged OpenAlex Ann {a4}, unresolved. Expected canonical works
  * (3): dblp:w1 (members crossref:w1, dblp:w1), dblp:w2, openalex:w3.
  */
class CanonicalSuite extends munit.FunSuite:
  // Several full Spark jobs per test on a 2-core local session: munit's 30 s default is too short.
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")
  private val Orcid = "0000-0002-1825-0097"
  private val run = runInfo(graphConfig)

  private def prov(src: SourceType, rec: String, schema: String) =
    Provenance(src, rec, "2026-11-22", "run-6d1175dbfdb874a9", schema, Some("norm-v1"))

  private case class M(id: String, work: String, pos: Int, name: String, aff: Option[String] = None)
  private val ms = Vector(
    M("m:a1", "dblp:w1", 0, "Ann Lee"),
    M("m:b1", "dblp:w1", 1, "Bob Ray 0001"),
    M("m:a2", "crossref:w1", 0, "Ann Lee"),
    M("m:b2", "crossref:w1", 1, "Bob Ray"),
    M("m:a3", "dblp:w2", 0, "Ann Lee"),
    M("m:c3", "dblp:w2", 1, "Cat Diaz"),
    M("m:x3", "dblp:w2", 2, "A. Lee"),
    M("m:a4", "openalex:w3", 0, "Ann Lee", Some("openalex:I1")),
    M("m:c4", "openalex:w3", 1, "Cat Diaz", Some("openalex:I2"))
  )
  private def source(work: String) = work.takeWhile(_ != ':') match
    case "dblp"     => SourceType.DBLP
    case "crossref" => SourceType.CROSSREF
    case _          => SourceType.OPENALEX
  private def mention(m: M) = AuthorMention(
    m.id,
    m.work,
    m.pos,
    m.name,
    m.name.toLowerCase,
    None,
    None,
    m.aff,
    prov(source(m.work), m.work, SchemaVersions.Mention)
  )
  private val years =
    Map("dblp:w1" -> 2020, "crossref:w1" -> 2020, "dblp:w2" -> 2021, "openalex:w3" -> 2022)
  private def work(
      id: String,
      venue: Option[String],
      topics: Vector[String],
      insts: Vector[String]
  ) =
    WorkRecord(
      SchemaVersions.Work,
      id,
      source(id),
      id.dropWhile(_ != ':').drop(1),
      "inproceedings",
      s"Title of $id",
      s"title of $id",
      Some(years(id)),
      venue,
      venue.map(_ => "Venue One"),
      None,
      None,
      None,
      Vector.empty,
      ms.filter(_.work == id).map(mention),
      topics,
      insts,
      None,
      prov(source(id), id, SchemaVersions.Work)
    )
  private val worksJson = Vector(
    work("dblp:w1", Some("venue:v1"), Vector.empty, Vector.empty),
    work("crossref:w1", None, Vector("openalex:T1", "openalex:T9"), Vector("openalex:I1")),
    work("dblp:w2", None, Vector.empty, Vector.empty),
    work("openalex:w3", None, Vector("openalex:T1"), Vector.empty)
  ).map(JsonLines.encode(_))
  private val teamSize = Map("dblp:w1" -> 2, "crossref:w1" -> 2, "dblp:w2" -> 3, "openalex:w3" -> 2)
  private def authorPaper(mentionId: String, workId: String, pos: Int) = ujson
    .Obj(
      "mentionId" -> mentionId,
      "workId" -> workId,
      "position" -> pos,
      "normalizedName" -> "x",
      "orcid" -> ujson.Null,
      "year" -> years.getOrElse(workId, 2020),
      "teamSize" -> teamSize.getOrElse(workId, 2),
      "provenance" -> upickle.default.writeJs(prov(SourceType.DBLP, workId, "author-paper-v1"))
    )
    .render()
  // 9 good rows, one whose mention is not in the export, one whose work is not in the export.
  private val authorPapersJson = ms.map(m => authorPaper(m.id, m.work, m.pos)) ++
    Vector(authorPaper("m:ghost", "dblp:w1", 2), authorPaper("m:a1", "dblp:gone", 0))
  private def pair(a: String, b: String, w: String) = JsonLines.encode(
    CoauthorPairCandidate(
      a,
      b,
      w,
      Some(years(w)),
      0,
      1,
      teamSize(w),
      prov(source(w), w, SchemaVersions.Pair)
    )
  )
  private val pairsJson = Vector(
    pair("m:a1", "m:b1", "dblp:w1"),
    pair("m:a2", "m:b2", "crossref:w1"),
    pair("m:a3", "m:c3", "dblp:w2"),
    pair("m:a3", "m:x3", "dblp:w2"),
    pair("m:c3", "m:x3", "dblp:w2"),
    pair("m:a4", "m:c4", "openalex:w3"),
    pair("m:a1", "m:ghost", "dblp:w1")
  )
  private def decision(
      kind: EntityKind,
      s: String,
      t: String,
      outcome: MatchOutcome,
      conf: Double
  ) =
    JsonLines.encode(
      MatchDecision(
        SchemaVersions.Match,
        Ids.decision(kind, s, t, "er-v1"),
        kind,
        s,
        t,
        Map("er_score" -> conf),
        Vector("R-R9"),
        outcome,
        MatchPath.PROBABILISTIC,
        conf,
        Vector("X"),
        "er-v1",
        None,
        None,
        Provenance(
          SourceType.SCHOLARCHAIN,
          "mc:x",
          "2026-11-22",
          "run-02cecabcf3e68312",
          SchemaVersions.Match,
          Some("er-v1")
        )
      )
    )
  import EntityKind.*
  import MatchOutcome.*
  private val decisionsJson = Vector(
    decision(RESEARCHER, "m:a1", "m:a2", ACCEPTED, 0.99),
    decision(RESEARCHER, "m:a1", "m:a3", ACCEPTED, 0.95),
    decision(RESEARCHER, "m:a3", "m:x3", ACCEPTED, 0.92),
    decision(RESEARCHER, "m:a2", s"orcid:$Orcid", ACCEPTED, 0.99),
    decision(RESEARCHER, "m:b1", "m:b2", ACCEPTED, 0.97),
    decision(RESEARCHER, "m:c3", "m:c4", ACCEPTED, 0.90),
    decision(RESEARCHER, "m:b1", "m:zz", ACCEPTED, 0.99),
    decision(RESEARCHER, "m:a1", "m:a4", UNRESOLVED, 0.80),
    decision(RESEARCHER, "m:a2", "m:x3", CONFLICT, 0.50),
    decision(WORK, "crossref:w1", "dblp:w1", ACCEPTED, 1.0),
    decision(WORK, "dblp:w2", "openalex:w3", CONFLICT, 0.50)
  )
  private val topicsJson = Vector(
    JsonLines.encode(
      Topic("openalex:T1", "Topic One", prov(SourceType.OPENALEX, "T1", SchemaVersions.Topic))
    )
  )
  private val institutionsJson = Vector(
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
  private val venuesJson = Vector(
    JsonLines.encode(
      Venue(
        "venue:v1",
        "Venue One",
        "conference",
        prov(SourceType.DBLP, "venue:v1", SchemaVersions.Venue)
      )
    )
  )

  private def parsed(
      ls: Seq[String],
      schema: org.apache.spark.sql.types.StructType,
      req: Seq[String]
  ) =
    Inputs.parse(lines(ls), schema, req).valid

  private lazy val sources = Sources(
    parsed(worksJson, Schemas.work, Schemas.Required.work),
    parsed(ms.map(m => JsonLines.encode(mention(m))), Schemas.mention, Schemas.Required.mention),
    parsed(authorPapersJson, Schemas.authorPaper, Schemas.Required.authorPaper),
    parsed(pairsJson, Schemas.pair, Schemas.Required.pair),
    parsed(topicsJson, Schemas.topic, Schemas.Required.topic),
    parsed(institutionsJson, Schemas.institution, Schemas.Required.institution),
    parsed(venuesJson, Schemas.venue, Schemas.Required.venue),
    parsed(decisionsJson, Schemas.decision, Schemas.Required.decision)
  )
  private lazy val result = Canonical.build(sources, run, "er-v1")
  private def n(key: String): Long = result.report(key).num.toLong

  private val annId = s"orcid:$Orcid"
  private val bobId = Ids.researcherFromMentions(Vector("m:b1", "m:b2"))
  private val catId = Ids.researcherFromMentions(Vector("m:c3", "m:c4"))
  private val annOaId = Ids.researcherFromMentions(Vector("m:a4"))
  private val x3Id = Ids.researcherFromMentions(Vector("m:x3"))

  test("schemas: a line that does not fit or lacks a required field is a counted reject") {
    val p = Inputs.parse(
      lines(Seq(JsonLines.encode(mention(ms.head)), "{not json", """{"workId":"dblp:w1"}""")),
      Schemas.mention,
      Schemas.Required.mention
    )
    assertEquals(p.valid.count(), 1L)
    val reasons = p.rejected.collect().map(_.getString(1)).sorted.toVector
    assertEquals(reasons, Vector("REQUIRED_FIELD_NULL", "SCHEMA_MISMATCH"))
    // every fixture line of every kind fits its schema
    assertEquals(sources.works.count(), 4L)
    assertEquals(sources.decisions.count(), 11L)
    assertEquals(sources.authorPapers.count(), 11L)
  }

  test(
    "researchers: accepted links merge, ORCID becomes the ID, contradictions are never merged"
  ) {
    val m2r = asMap(result.mentionToResearcher, "mentionId", "researcherId")
    assertEquals(Set("m:a1", "m:a2", "m:a3").map(m2r), Set[Any](annId))
    assertEquals(m2r("m:x3"), x3Id, "CONFLICT a2-x3: the cut refused a3-x3")
    assertEquals(Set("m:b1", "m:b2").map(m2r), Set[Any](bobId), "core Ids formula")
    assertEquals(Set("m:c3", "m:c4").map(m2r), Set[Any](catId))
    assertEquals(m2r("m:a4"), annOaId, "UNRESOLVED a1-a4 never merges")
    assertEquals(m2r.size, 9)
    val rows = result.researchers.collect().map(r => r.getAs[String]("researcherId") -> r).toMap
    assertEquals(rows.keySet, Set(annId, bobId, catId, annOaId, x3Id))
    val ann = rows(annId)
    assertEquals(ann.getAs[String]("displayName"), "Ann Lee") // 3 of 3 mentions
    assertEquals(ann.getSeq[String](ann.fieldIndex("nameVariants")).toVector, Vector("Ann Lee"))
    assertEquals(
      ann.getMap[String, String](ann.fieldIndex("externalIds")).toMap,
      Map("orcid" -> Orcid)
    )
    assertEqualsDouble(ann.getAs[Double]("identityConfidence"), 0.95, 1e-12)
    assertEquals(ann.getAs[Boolean]("unresolved"), true)
    assertEquals(ann.getAs[Boolean]("identityConflict"), true, "the refused a3-x3 touches a3")
    assertEquals(
      ann.getStruct(ann.fieldIndex("provenance")).getAs[String]("policyVersion"),
      "er-v1",
      "identity provenance names the identity policy"
    )
    val x3 = rows(x3Id)
    assertEquals(x3.getAs[String]("displayName"), "A. Lee")
    assertEquals(
      (x3.getAs[Boolean]("identityConflict"), x3.getAs[Boolean]("unresolved")),
      (true, false)
    )
    assertEqualsDouble(x3.getAs[Double]("identityConfidence"), 1.0, 1e-12, "no applied link")
    val bob = rows(bobId)
    assertEquals(bob.getAs[String]("displayName"), "Bob Ray", "DBLP homonym suffix removed")
    assertEquals(bob.getAs[Boolean]("unresolved"), false)
    assertEquals(bob.getAs[Boolean]("identityConflict"), false)
    assertEquals(rows(annOaId).getAs[Boolean]("unresolved"), true)
    assertEqualsDouble(
      rows(annOaId).getAs[Double]("identityConfidence"),
      1.0,
      1e-12,
      "no merge was made"
    )
    // 7 accepted links: 1 with an unknown endpoint, 1 refused by the cut, 5 applied.
    assertEquals(
      (
        n("researcherDecisionLinksAccepted"),
        n("researcherDecisionLinksDroppedUnknownEndpoint"),
        n("researcherDecisionLinksRefusedByCut"),
        n("researcherDecisionLinksApplied"),
        n("researcherClustersCut")
      ),
      (7L, 1L, 1L, 5L, 1L)
    )
    // C12 through Canonical.build: the configured bound is read; the one contradicted cluster
    // (4 links) is far below it.
    assertEquals(
      (
        n("researcherCutMaxClusterLinks"),
        n("researcherLargestContradictedClusterLinks"),
        n("researcherClustersOverCutBound"),
        n("researcherDecisionLinksRefusedOverCutBound"),
        n("workClustersOverCutBound")
      ),
      (graphConfig.identityCutMaxClusterLinks.toLong, 4L, 0L, 0L, 0L)
    )
    // Per outcome: (decisions, in one cluster before the cut, kept apart, merged after the cut).
    def outcome(o: String) = (
      n(s"researcherContradictions_$o"),
      n(s"researcherContradictionsInOneClusterBeforeCut_$o"),
      n(s"researcherContradictionsKeptApart_$o"),
      n(s"invariant_researcherContradictionsMerged_$o")
    )
    assertEquals(outcome("CONFLICT"), (1L, 1L, 1L, 0L))
    assertEquals(outcome("UNRESOLVED"), (1L, 0L, 1L, 0L))
    assertEquals(outcome("REJECTED"), (0L, 0L, 0L, 0L))
    assertEquals(
      (
        n("researcherClustersWithSeveralOrcidsBeforeCut"),
        n("invariant_researcherClustersWithSeveralOrcids"),
        n("invariant_researcherClusterNotConverged")
      ),
      (0L, 0L, 0L)
    )
    assertEquals(
      (
        n("researchers"),
        n("researchersMerged"),
        n("researchersUnresolved"),
        n("researchersIdentityConflict")
      ),
      (5L, 3L, 2L, 2L)
    )
  }

  test("contradiction cut on a known identity graph: UNRESOLVED, CONFLICT and two ORCID iDs") {
    // Four clusters of accepted links (confidence on each link), and the contradictions:
    //   K1  p -0.925- h -1.0- q,  k -0.5- h          UNRESOLVED p-q (the sample's shape)
    //   K2  c1 -0.9- c2 -0.8- c3 -0.7- c1 (a cycle)   CONFLICT c1-c3
    //   K3  orcid:A -0.99- o1 -0.95- o2 -0.9- orcid:B (two ORCID iDs)
    //   K4  n1 -0.6- n2                                no contradiction
    // Rebuild by descending confidence, ties by (a, b):
    //   K1: h-q 1.0 applied {h,q}; h-p 0.925 would join p to q: refused; h-k 0.5 applied
    //       -> {h,k,q}, {p}. The weak link h-k is kept: it does not lie between p and q.
    //   K2: c1-c2 0.9 applied; c2-c3 0.8 refused (c3 to c1); c1-c3 0.7 refused -> {c1,c2}, {c3}.
    //   K3: o1-orcid:A 0.99 applied; o1-o2 0.95 applied; o2-orcid:B 0.9 would hold A and B:
    //       refused -> {o1,o2,orcid:A}, {orcid:B}.
    //   K4 is not contradicted and stays {n1,n2}.
    // Labels are the smallest ID of each final set. Refused: 1 + 2 + 1 = 4 of 9 links.
    val ids = Seq("m:p", "m:h", "m:q", "m:k", "m:c1", "m:c2", "m:c3", "m:o1", "m:o2") ++
      Seq("orcid:A", "orcid:B", "m:n1", "m:n2")
    val linkSchema = StructType(
      Seq("a", "b").map(StructField(_, StringType)) :+ StructField("confidence", DoubleType)
    )
    def ordered(x: String, y: String) = if x < y then (x, y) else (y, x)
    val links = frame(
      linkSchema,
      Seq(
        ("m:p", "m:h", 0.925),
        ("m:h", "m:q", 1.0),
        ("m:k", "m:h", 0.5),
        ("m:c1", "m:c2", 0.9),
        ("m:c2", "m:c3", 0.8),
        ("m:c3", "m:c1", 0.7),
        ("orcid:A", "m:o1", 0.99),
        ("m:o1", "m:o2", 0.95),
        ("m:o2", "orcid:B", 0.9),
        ("m:n1", "m:n2", 0.6)
      ).map { (x, y, c) =>
        val (a, b) = ordered(x, y)
        Row(a, b, c)
      }
    )
    val contraSchema = StructType(Seq("x", "y", "outcome").map(StructField(_, StringType)))
    val contra = frame(
      contraSchema,
      Seq(
        Row("m:p", "m:q", "UNRESOLVED"),
        Row("m:c1", "m:c3", "CONFLICT")
      )
    )
    // K1, K2 and K3 hold exactly 3 accepted links each: a bound of 3 is exactly at the bound for
    // all three, so they are cut as without a bound (C12, inclusive bound).
    def clustersWith(maxIterations: Int) =
      val report = ujson.Obj()
      val c = Canonical.constrainedClusters(
        nodes(ids*),
        links,
        contra,
        Some("orcid:" -> "Orcids"),
        maxIterations,
        3,
        report,
        "t"
      )
      (c, report)
    val (c, report) = clustersWith(graphConfig.componentMaxIterations)
    def r(k: String) = report(k).num.toLong
    assertEquals(
      (
        r("tLargestContradictedClusterLinks"),
        r("tClustersOverCutBound"),
        r("tDecisionLinksRefusedOverCutBound")
      ),
      (3L, 0L, 0L)
    )
    val label = asMap(c.labels, "id", "label")
    def members(l: String) = label.collect { case (id, x) if x == l => id }.toSet
    assertEquals(members("m:h"), Set("m:h", "m:k", "m:q"))
    assertEquals(members("m:p"), Set("m:p"))
    assertEquals(members("m:c1"), Set("m:c1", "m:c2"))
    assertEquals(members("m:c3"), Set("m:c3"))
    assertEquals(members("m:o1"), Set("m:o1", "m:o2", "orcid:A"))
    assertEquals(members("orcid:B"), Set("orcid:B"))
    assertEquals(members("m:n1"), Set("m:n1", "m:n2"))
    assertEquals(label.size, ids.size)
    val applied = c.applied.collect().map(x => x.getString(0) -> x.getString(1)).toSet
    assertEquals(
      applied,
      Set("m:h" -> "m:q", "m:h" -> "m:k", "m:c1" -> "m:c2", "m:o1" -> "orcid:A") ++
        Set("m:o1" -> "m:o2", "m:n1" -> "m:n2")
    )
    assertEquals(
      c.cut.collect().map(_.getString(0)).toSet,
      Set("m:h", "m:p", "m:c1", "m:c2", "m:c3", "m:o2", "orcid:B")
    )
    assertEquals(
      (r("tDecisionLinksAccepted"), r("tDecisionLinksRefusedByCut"), r("tDecisionLinksApplied")),
      (10L, 4L, 6L)
    )
    assertEquals(r("tClustersCut"), 3L, "K1 (UNRESOLVED), K2 (CONFLICT), K3 (two ORCID iDs)")
    assertEquals(
      (
        r("tContradictionsInOneClusterBeforeCut_UNRESOLVED"),
        r("tContradictionsKeptApart_UNRESOLVED"),
        r("invariant_tContradictionsMerged_UNRESOLVED")
      ),
      (1L, 1L, 0L)
    )
    assertEquals(
      (
        r("tContradictionsInOneClusterBeforeCut_CONFLICT"),
        r("tContradictionsKeptApart_CONFLICT"),
        r("invariant_tContradictionsMerged_CONFLICT")
      ),
      (1L, 1L, 0L)
    )
    assertEquals(
      (r("tClustersWithSeveralOrcidsBeforeCut"), r("invariant_tClustersWithSeveralOrcids")),
      (1L, 0L)
    )
    // The pure step gives the same answer for K1 alone (no Spark).
    assertEquals(
      IdentityCut.cut(
        Seq(
          IdentityCut.Link("m:h", "m:p", 0.925),
          IdentityCut.Link("m:h", "m:q", 1.0),
          IdentityCut.Link("m:h", "m:k", 0.5)
        ),
        Seq("m:p" -> "m:q"),
        None
      ),
      (Map("m:h" -> "m:h", "m:k" -> "m:h", "m:q" -> "m:h", "m:p" -> "m:p"), Vector("m:h" -> "m:p"))
    )
    // MINOR-1: the identity clustering cut off below its diameter is an invariant failure.
    val (_, cutOff) = clustersWith(1)
    assertEquals(cutOff("invariant_tClusterNotConverged").num.toLong, 1L)
  }

  test("C12 cluster bound: at the bound the cut runs as now; bound+1 refuses every link, counted") {
    // Known graph (confidence on each link):
    //   K1  p -0.925- h -1.0- q,  k -0.5- h                    3 links, UNRESOLVED p-q
    //   K5  e1 -0.9- e2 -0.8- e3 -0.7- e4 -0.6- e5             4 links, CONFLICT e1-e5
    //   K3  orcid:A -0.99- o1 -0.95- o2 -0.9- o3 -0.85- orcid:B 4 links, two ORCID iDs
    //   K4  u0 -0.6- u1, u2, u3, u4 (a star)                    4 links, no contradiction
    // Bound 3: K1 holds exactly 3 links and is cut as now ({h,k,q}, {p}); K5 and K3 hold 4 = bound+1,
    // so all 8 of their links are refused and their 10 members stay singletons; K4 is not
    // contradicted and is not limited. Bound 4: every contradicted cluster is at the bound and is
    // cut: K5 refuses e4-e5 (the weakest on the path e1..e5), K3 refuses o3-orcid:B.
    val ids = Seq("m:p", "m:h", "m:q", "m:k") ++ (1 to 5).map(i => s"m:e$i") ++
      Seq("m:o1", "m:o2", "m:o3", "orcid:A", "orcid:B") ++ (0 to 4).map(i => s"m:u$i")
    val linkSchema = StructType(
      Seq("a", "b").map(StructField(_, StringType)) :+ StructField("confidence", DoubleType)
    )
    def ordered(x: String, y: String) = if x < y then (x, y) else (y, x)
    val links = frame(
      linkSchema,
      (Seq(
        ("m:p", "m:h", 0.925),
        ("m:h", "m:q", 1.0),
        ("m:k", "m:h", 0.5),
        ("m:e1", "m:e2", 0.9),
        ("m:e2", "m:e3", 0.8),
        ("m:e3", "m:e4", 0.7),
        ("m:e4", "m:e5", 0.6),
        ("orcid:A", "m:o1", 0.99),
        ("m:o1", "m:o2", 0.95),
        ("m:o2", "m:o3", 0.9),
        ("m:o3", "orcid:B", 0.85)
      ) ++ (1 to 4).map(i => ("m:u0", s"m:u$i", 0.6))).map { (x, y, c) =>
        val (a, b) = ordered(x, y)
        Row(a, b, c)
      }
    )
    val contra = frame(
      StructType(Seq("x", "y", "outcome").map(StructField(_, StringType))),
      Seq(Row("m:p", "m:q", "UNRESOLVED"), Row("m:e1", "m:e5", "CONFLICT"))
    )
    def clustersWith(bound: Int) =
      val report = ujson.Obj()
      val c = Canonical.constrainedClusters(
        nodes(ids*),
        links,
        contra,
        Some("orcid:" -> "Orcids"),
        graphConfig.componentMaxIterations,
        bound,
        report,
        "t"
      )
      (c, report)
    def groups(c: Canonical.Clusters): Set[Set[String]] =
      asMap(c.labels, "id", "label").toSeq.groupBy(_._2).values.map(_.map(_._1).toSet).toSet
    def applied(c: Canonical.Clusters) =
      c.applied.collect().map(x => x.getString(0) -> x.getString(1)).toSet
    val star = (1 to 4).map(i => "m:u0" -> s"m:u$i").toSet
    val uStar = (0 to 4).map(i => s"m:u$i").toSet
    val e = (1 to 5).map(i => s"m:e$i")
    val o = Seq("m:o1", "m:o2", "m:o3", "orcid:A", "orcid:B")
    def invariantsZero(r: ujson.Obj) =
      for k <- Seq(
          "invariant_tContradictionsMerged_UNRESOLVED",
          "invariant_tContradictionsMerged_CONFLICT",
          "invariant_tContradictionsMerged_REJECTED",
          "invariant_tClustersWithSeveralOrcids",
          "invariant_tClusterNotConverged"
        )
      do assertEquals(r(k).num.toLong, 0L, k)

    // Bound 3: K1 at the bound, K5 and K3 at bound+1.
    val (c3, r3) = clustersWith(3)
    def n3(k: String) = r3(k).num.toLong
    assertEquals(
      groups(c3),
      Set(Set("m:h", "m:k", "m:q"), Set("m:p"), uStar) ++ (e ++ o).map(Set(_)),
      "over-bound members stay apart; K4 (uncontradicted) is not limited"
    )
    assertEquals(applied(c3), Set("m:h" -> "m:q", "m:h" -> "m:k") ++ star)
    assertEquals(
      c3.cut.collect().map(_.getString(0)).toSet,
      Set("m:h", "m:p") ++ e ++ o,
      "every member of an over-bound cluster is touched by a refused link"
    )
    assertEquals(
      (
        n3("tCutMaxClusterLinks"),
        n3("tLargestContradictedClusterLinks"),
        n3("tClustersCut"),
        n3("tClustersOverCutBound"),
        n3("tDecisionLinksRefusedOverCutBound")
      ),
      (3L, 4L, 3L, 2L, 8L)
    )
    assertEquals(
      (
        n3("tDecisionLinksAccepted"),
        n3("tDecisionLinksRefusedByCut"),
        n3("tDecisionLinksApplied")
      ),
      (15L, 9L, 6L),
      "refused by cut = 1 (K1 cut) + 8 (over the bound)"
    )
    assertEquals(
      (
        n3("tContradictionsKeptApart_UNRESOLVED"),
        n3("tContradictionsKeptApart_CONFLICT"),
        n3("tClustersWithSeveralOrcidsBeforeCut")
      ),
      (1L, 1L, 1L)
    )
    invariantsZero(r3)

    // Bound 4: every contradicted cluster is exactly at the bound and is cut as without a bound.
    val (c4, r4) = clustersWith(4)
    def n4(k: String) = r4(k).num.toLong
    assertEquals(
      groups(c4),
      Set(
        Set("m:h", "m:k", "m:q"),
        Set("m:p"),
        uStar,
        Set("m:e1", "m:e2", "m:e3", "m:e4"),
        Set("m:e5"),
        Set("m:o1", "m:o2", "m:o3", "orcid:A"),
        Set("orcid:B")
      )
    )
    assertEquals(
      (
        n4("tClustersOverCutBound"),
        n4("tDecisionLinksRefusedOverCutBound"),
        n4("tDecisionLinksRefusedByCut"),
        n4("tDecisionLinksApplied")
      ),
      (0L, 0L, 3L, 12L)
    )
    invariantsZero(r4)
  }

  test("works: accepted work links merge source copies; a CONFLICT keeps works apart") {
    val w = result.works.collect().map(r => r.getAs[String]("workId") -> r).toMap
    assertEquals(w.keySet, Set("dblp:w1", "dblp:w2", "openalex:w3"), "DBLP is the representative")
    val w1 = w("dblp:w1")
    assertEquals(
      w1.getSeq[String](w1.fieldIndex("memberWorkIds")).toVector,
      Vector("crossref:w1", "dblp:w1")
    )
    assertEquals(
      w1.getSeq[String](w1.fieldIndex("topicIds")).toVector,
      Vector("openalex:T1", "openalex:T9")
    )
    assertEquals(w1.getSeq[String](w1.fieldIndex("venueIds")).toVector, Vector("venue:v1"))
    assertEquals(w1.getAs[Int]("year"), 2020)
    assertEquals(
      w("dblp:w2").getAs[Boolean]("identityConflict"),
      false,
      "the conflict is across clusters"
    )
    assertEquals((n("canonicalWorks"), n("canonicalWorksMerged")), (3L, 1L))
    assertEquals(
      (
        n("workDecisionLinksRefusedByCut"),
        n("workContradictions_CONFLICT"),
        n("workContradictionsInOneClusterBeforeCut_CONFLICT"),
        n("workContradictionsKeptApart_CONFLICT"),
        n("invariant_workContradictionsMerged_CONFLICT")
      ),
      (0L, 1L, 0L, 1L, 0L)
    )
    assertEquals(
      w1.getStruct(w1.fieldIndex("provenance")).getAs[String]("policyVersion"),
      "er-v1"
    )
    // T9 is not in the topic table; I2 is not in the institution table.
    assertEquals((n("workTopicRefsMissing"), n("workVenueRefsMissing")), (1L, 0L))
    assertEquals((n("affiliations"), n("affiliationRefsMissing")), (1L, 1L))
  }

  test("AUTHORED: one edge per researcher and canonical work; dangling rows rejected and counted") {
    val a = result.authored
      .collect()
      .map(r => (r.getAs[String]("researcherId"), r.getAs[String]("workId")) -> r)
      .toMap
    assertEquals(
      a.keySet,
      Set(
        annId -> "dblp:w1",
        bobId -> "dblp:w1",
        annId -> "dblp:w2",
        catId -> "dblp:w2",
        x3Id -> "dblp:w2", // m:x3 is its own researcher after the cut
        annOaId -> "openalex:w3",
        catId -> "openalex:w3"
      )
    )
    val annW1 = a(annId -> "dblp:w1")
    assertEquals(
      annW1.getSeq[String](annW1.fieldIndex("mentionIds")).toVector,
      Vector("m:a1", "m:a2")
    )
    assertEquals(
      annW1.getAs[String]("mentionId"),
      "m:a1",
      "the mention on the representative record"
    )
    // dblp:w2 lists 3 mentions of 3 distinct researchers: team size 3, positions 0, 1, 2 (a3, c3,
    // x3 by their source positions).
    assertEquals(a(annId -> "dblp:w2").getAs[Int]("teamSize"), 3)
    assertEquals(
      (
        a(annId -> "dblp:w2").getAs[Int]("position"),
        a(catId -> "dblp:w2").getAs[Int]("position"),
        a(x3Id -> "dblp:w2").getAs[Int]("position")
      ),
      (0, 1, 2)
    )
    assertEquals(
      (
        n("authorPaperRows"),
        n("authored"),
        n("authoredRejected_MENTION_WITHOUT_RESEARCHER"),
        n("authoredRejected_WORK_NOT_FOUND")
      ),
      (11L, 7L, 1L, 1L)
    )
    assertEquals(
      (n("invariant_authoredMissingResearcher"), n("invariant_authoredMissingWork")),
      (0L, 0L)
    )
  }

  test("pairs: mapped to researchers and canonical works, self pairs dropped, duplicates merged") {
    val pr = CoauthorGraph.researcherPairs(
      sources.pairs,
      result.mentionToResearcher,
      result.workMap,
      result.authored,
      graphConfig.maxTeamSizeForPairs
    )
    val rows = pr.pairWorks
      .collect()
      .map(r =>
        (r.getAs[String]("a"), r.getAs[String]("b"), r.getAs[String]("workId")) -> r
          .getAs[Long]("sourcePairRows")
      )
      .toMap
    val (oaA, oaB) = Ids.orderedPair(annOaId, catId) // both sc: IDs, ordered by their hashes
    val (axA, axB) = Ids.orderedPair(annId, x3Id)
    val (cxA, cxB) = Ids.orderedPair(catId, x3Id)
    // (a1,b1) on dblp:w1 and (a2,b2) on crossref:w1 are one pair on one canonical work; on dblp:w2
    // (a3,c3), (a3,x3) and (c3,x3) are three pairs (x3 is not Ann); (a1,ghost) is rejected.
    assertEquals(
      rows,
      Map(
        (annId, bobId, "dblp:w1") -> 2L,
        (annId, catId, "dblp:w2") -> 1L,
        (axA, axB, "dblp:w2") -> 1L,
        (cxA, cxB, "dblp:w2") -> 1L,
        (oaA, oaB, "openalex:w3") -> 1L
      )
    )
    def p(k: String) = pr.report(k).num.toLong
    assertEquals(
      (p("pairCandidateRows"), p("selfPairsDropped"), p("pairRejects_PAIR_MENTION_UNKNOWN")),
      (7L, 0L, 1L)
    )
    // Self pairs: had a3-x3 been applied (x3 mapped to Ann), (a3,x3) would be a self pair, dropped
    // and counted, and (c3,x3) would join (a3,c3) as one pair with 2 source rows.
    val asAnn = result.mentionToResearcher.select(
      col("mentionId"),
      when(col("mentionId") === "m:x3", annId)
        .otherwise(col("researcherId"))
        .as("researcherId")
    )
    val merged =
      CoauthorGraph.researcherPairs(sources.pairs, asAnn, result.workMap, result.authored, 300)
    assertEquals(merged.report("selfPairsDropped").num.toLong, 1L)
    assertEquals(
      merged.pairWorks
        .filter(col("workId") === "dblp:w2")
        .collect()
        .map(r => (r.getAs[String]("a"), r.getAs[String]("b")) -> r.getAs[Long]("sourcePairRows"))
        .toMap,
      Map((annId, catId) -> 2L)
    )
    assertEquals((p("pairRejects_PAIR_WITHOUT_AUTHORSHIP"), p("pairsRefusedByTeamCap")), (0L, 0L))
    // Ann-Bob is reported by two sources but has one joint publication.
    val e = CoauthorGraph.edges(pr.pairWorks, All, run).filter(col("researcherB") === bobId).head()
    assertEquals(e.getAs[Long]("jointPublicationCount"), 1L)
    assertEquals(
      e.getAs[String]("edgeId"),
      Ids.coauthorEdge(annId, bobId, All, graphConfig.policyVersion)
    )
    // A team cap of 1 refuses every pair (every canonical team has 2 or 3 researchers): the 6
    // mapped pair rows (a1-b1, a2-b2, a3-c3, a3-x3, c3-x3, a4-c4) are refused.
    val capped = CoauthorGraph.researcherPairs(
      sources.pairs,
      result.mentionToResearcher,
      result.workMap,
      result.authored,
      1
    )
    assertEquals(
      (capped.pairWorks.count(), capped.report("pairsRefusedByTeamCap").num.toLong),
      (0L, 6L)
    )
  }
