package scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

/** Synthetic records for entity-resolution tests. No gold label, and no file of datasets/, is read.
  */
object ErFixtures:
  val Snapshot = "2026-11-22"
  val UpstreamRunId: String = Ids.run("""{"fixture":"er-upstream"}""")

  // Valid ORCID iDs (the ORCID documentation examples; check digits verified in ErNormalizeSuite).
  val OrcidA = "0000-0002-1825-0097"
  val OrcidC4 = "0000-0001-5109-3700"
  val OrcidC5 = "0000-0002-1694-233X"

  /** SHA-256 of prompts/er-adjudication-v1.md; a changed prompt needs a new prompt version. */
  val PromptSha256 = "fbcf4b136e72b9d1edede0060be0d4543f2459a83c0e119252284cd2ca291ac5"

  lazy val policyConfig: PolicyConfig =
    PolicyConfig.load().fold(e => throw new AssertionError(e), identity)
  lazy val policy: MatchPolicy = new MatchPolicy(policyConfig, policyConfig.thresholds)
  def limits: ProfileLimits = policyConfig.limits

  def prov(src: SourceType, rec: String, schema: String): Provenance =
    Provenance(src, rec, Snapshot, UpstreamRunId, schema, Some("norm-v1"))

  final case class Author(
      name: String,
      orcid: Option[String] = None,
      ext: Option[String] = None,
      aff: Option[String] = None
  )

  def work(
      id: String,
      source: SourceType,
      title: String,
      year: Option[Int],
      authors: Vector[Author],
      doi: Option[String] = None,
      openAlexId: Option[String] = None,
      arxivId: Option[String] = None,
      urls: Vector[String] = Vector.empty,
      venueId: Option[String] = None,
      venueName: Option[String] = None
  ): WorkRecord =
    WorkRecord(
      SchemaVersions.Work,
      id,
      source,
      id.dropWhile(_ != ':').drop(1),
      "inproceedings",
      title,
      Normalize.title(title),
      year,
      venueId,
      venueName,
      doi,
      arxivId,
      openAlexId,
      urls,
      authors.zipWithIndex.map((a, i) =>
        AuthorMention(
          Ids.mention(id, i),
          id,
          i,
          a.name,
          Normalize.personName(a.name),
          a.orcid,
          a.ext,
          a.aff,
          prov(source, id, SchemaVersions.Mention)
        )
      ),
      Vector.empty,
      Vector.empty,
      None,
      prov(source, id, SchemaVersions.Work)
    )

  def mentionId(w: WorkRecord, i: Int): String = w.authors(i).mentionId

  def mentionProfile(w: WorkRecord, i: Int): EndpointProfile =
    val m = Profiles.mentions(w, limits)(i)
    EndpointProfile(m.mentionId, EntityKind.RESEARCHER, None, Vector(m), false)

  def workProfile(w: WorkRecord): EndpointProfile =
    EndpointProfile(w.workId, EntityKind.WORK, Some(Profiles.work(w, limits)), Vector.empty, false)

  /** An aggregate researcher endpoint (for example `orcid:<iD>`) built the way ER-J1 builds it. */
  def aggregate(id: String, members: Vector[(WorkRecord, Int)]): EndpointProfile =
    members.foldLeft(EndpointProfile(id, EntityKind.RESEARCHER, None, Vector.empty, false)) {
      case (p, (w, i)) =>
        val m = Profiles.mentions(w, limits)(i)
        Profiles.addMention(
          p,
          m.copy(coauthorNames = Vector.empty, title = "", references = Vector.empty),
          limits.maxMentionsPerEndpoint
        )
    }

  def candidate(
      kind: EntityKind,
      source: String,
      target: String,
      features: Map[String, Double] = Map.empty,
      sourceIds: Map[String, String] = Map.empty,
      targetIds: Map[String, String] = Map.empty,
      blockKey: String = "key"
  ): ErCandidate =
    ErCandidate(
      ErCandidate.SchemaVersion,
      ErCandidate.idOf(kind, source, target),
      kind,
      source,
      target,
      blockKey,
      features,
      sourceIds,
      targetIds,
      Provenance(
        SourceType.SCHOLARCHAIN,
        blockKey,
        Snapshot,
        UpstreamRunId,
        ErCandidate.SchemaVersion,
        Some("norm-v1")
      )
    )

  def merged(c: ErCandidate): MergedCandidate =
    ErCandidate.merge(Vector(c)).fold(e => throw new AssertionError(e), identity)

  def featureSet(values: (String, Double)*): FeatureSet = FeatureSet(values.toMap, Map.empty)

  def featureSet(ids: Map[IdScheme, Agreement], values: (String, Double)*): FeatureSet =
    FeatureSet(values.toMap, ids)

  // ------------------------------------------------------------------ the synthetic run

  /** Six works and ten hand-derived candidates (see MatchJobSuite for the expected outcomes). */
  object Run:
    val W1: WorkRecord = work(
      "dblp:conf/a/X1",
      SourceType.DBLP,
      "Scalable Graph Processing on Commodity Clusters",
      Some(2020),
      Vector(Author("Alice Smith", orcid = Some(OrcidA)), Author("Bob Jones")),
      doi = Some("10.1000/abc1")
    )
    val W2: WorkRecord = work(
      "crossref:10.1000/abc1",
      SourceType.CROSSREF,
      "Scalable Graph Processing on Commodity Clusters",
      Some(2020),
      Vector(Author("Alice Smith", orcid = Some(OrcidA)), Author("Bob Jones")),
      doi = Some("10.1000/abc1")
    )
    val W3: WorkRecord = work(
      "openalex:W100",
      SourceType.OPENALEX,
      "Scalable graph processing on commodity clusters",
      Some(2020),
      Vector(Author("A. Smith"), Author("B. Jones")),
      openAlexId = Some("W100")
    )
    val W4: WorkRecord = work(
      "dblp:conf/b/Y1",
      SourceType.DBLP,
      "Scalable Graph Processing on Commodity Clusters",
      Some(2021),
      Vector(Author("Carol White", orcid = Some(OrcidC4))),
      doi = Some("10.1000/zzz9")
    )
    val W5: WorkRecord = work(
      "dblp:conf/c/Z1",
      SourceType.DBLP,
      "Deep Learning for Protein Folding",
      Some(2019),
      Vector(Author("Carol White", orcid = Some(OrcidC5)), Author("Dan Brown")),
      doi = Some("10.2000/q1")
    )
    val W6: WorkRecord = work(
      "crossref:10.2000/q2",
      SourceType.CROSSREF,
      "A Survey of Database Indexing",
      Some(2019),
      Vector(Author("Erin Gray"), Author("Dan Brown")),
      doi = Some("10.2000/q2")
    )
    val works: Vector[WorkRecord] = Vector(W1, W2, W3, W4, W5, W6)

    private def sym(kind: EntityKind, a: String, b: String, f: Map[String, Double], key: String) =
      val (x, y) = Ids.orderedPair(a, b)
      candidate(kind, x, y, f, blockKey = key)

    val orcidId: String = s"orcid:$OrcidA"
    val exact: Vector[ErCandidate] = Vector(
      sym(EntityKind.WORK, W1.workId, W2.workId, Map("exact_doi" -> 1.0), "doi:10.1000/abc1"),
      candidate(
        EntityKind.RESEARCHER,
        mentionId(W1, 0),
        orcidId,
        Map("exact_orcid" -> 1.0, "name_jw" -> 1.0),
        targetIds = Map("orcid" -> OrcidA),
        blockKey = orcidId
      ),
      candidate(
        EntityKind.RESEARCHER,
        mentionId(W2, 0),
        orcidId,
        Map("exact_orcid" -> 1.0, "name_jw" -> 1.0),
        targetIds = Map("orcid" -> OrcidA),
        blockKey = orcidId
      )
    )
    val blocked: Vector[ErCandidate] = Vector(
      sym(EntityKind.WORK, W1.workId, W3.workId, Map("title_jaccard" -> 1.0), "wk:2020"),
      sym(EntityKind.WORK, W2.workId, W4.workId, Map("doi_conflict" -> 1.0), "wk:2020|a"),
      sym(EntityKind.WORK, W5.workId, W6.workId, Map("doi_conflict" -> 1.0), "wk:2019"),
      sym(
        EntityKind.RESEARCHER,
        mentionId(W1, 1),
        mentionId(W2, 1),
        Map("coauthor_overlap" -> 1.0),
        "jones_b"
      ),
      sym(
        EntityKind.RESEARCHER,
        mentionId(W1, 0),
        mentionId(W1, 1),
        Map("shared_work" -> 1.0),
        "x"
      ),
      sym(
        EntityKind.RESEARCHER,
        mentionId(W4, 0),
        mentionId(W5, 0),
        Map("orcid_conflict" -> 1.0),
        "white_c"
      ),
      sym(
        EntityKind.RESEARCHER,
        mentionId(W5, 1),
        mentionId(W6, 1),
        Map("coauthor_overlap" -> 0.3),
        "brown_d"
      )
    )
    val bandCandidate: ErCandidate = blocked.last

    /** The endpoint profile of an ID, built the way ER-J1 builds it. */
    def endpoint(id: String, kind: EntityKind): EndpointProfile =
      val mentions = for
        w <- works
        (m, i) <- w.authors.zipWithIndex
      yield (w, i, m)
      kind match
        case EntityKind.WORK =>
          works.find(_.workId == id).map(workProfile).getOrElse(EndpointProfile.missing(id, kind))
        case EntityKind.RESEARCHER if id.startsWith("m:") =>
          mentions
            .collectFirst { case (w, i, m) if m.mentionId == id => mentionProfile(w, i) }
            .getOrElse(EndpointProfile.missing(id, kind))
        case EntityKind.RESEARCHER =>
          aggregate(
            id,
            mentions.collect { case (w, i, m) if m.orcid.exists(o => s"orcid:$o" == id) => (w, i) }
          )

    def sides(c: ErCandidate): (EndpointProfile, EndpointProfile) =
      (endpoint(c.sourceId, c.kind), endpoint(c.targetId, c.kind))
    val invalidLine = """{"schemaVersion":"match-candidate-v1","candidateId":"mc:work:bad"}"""

  // ------------------------------------------------------------------ files

  final class TempDir(label: String):
    val dir: Path = Files.createTempDirectory(s"sc-er-$label-")
    def write(rel: String, text: String): Path =
      val p = dir.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      p
    def delete(): Unit =
      if Files.exists(dir) then
        val walk = Files.walk(dir)
        try walk.iterator.asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p))
        finally walk.close()

  /** Writes a Phase 04-shaped run tree: manifest, both candidate stages and the works export. */
  def writeRun(t: TempDir, root: String): Path =
    val run = s"snapshot=$Snapshot/run_id=$UpstreamRunId"
    t.write(
      s"$root/run-manifests/$UpstreamRunId.json",
      """{"pipeline":"hadoop-v2","normalization":"norm-v1"}"""
    )
    t.write(
      s"$root/match-candidates/kind=exact/$run/part-r-00000",
      Run.exact.map(JsonLines.encode(_)).mkString("\n") + "\n"
    )
    t.write(s"$root/match-candidates/kind=exact/$run/_SUCCESS", "")
    t.write(
      s"$root/match-candidates/kind=blocked/$run/part-r-00000",
      (Run.blocked.map(JsonLines.encode(_)) :+ Run.invalidLine).mkString("\n") + "\n"
    )
    t.write(s"$root/match-candidates/kind=blocked/$run/_SUCCESS", "")
    t.write(
      s"$root/export/$run/works/part-r-00000",
      Run.works.map(JsonLines.encode(_)).mkString("\n") + "\n"
    )
    t.write(s"$root/export/$run/_SUCCESS", "")
    t.dir.resolve(root)
