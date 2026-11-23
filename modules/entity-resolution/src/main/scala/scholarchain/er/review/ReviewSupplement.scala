package scholarchain.er.review

import scala.util.Try

import scholarchain.core.*
import scholarchain.er.{ErCandidate, ErNormalize, Features, PersonName, Profiles}
import upickle.default.*

/** The structural criteria of the supplementary review queue (human decision 01, phase 05). They
  * read only source records and the upstream blocking structure, never an er-v1 score, outcome or
  * decision. A criterion name never reaches the queue file or its sidecar.
  */
enum SupplementCriterion:
  /** Both mentions carry an ORCID iD and the iDs differ. */
  case DIFFERENT_ORCID

  /** Both mentions are DBLP author strings that differ only by the DBLP homonym number. */
  case DBLP_HOMONYM_DIFFERENT

  /** Initials are compatible and the two mentions share no context dimension. */
  case COMPATIBLE_INITIALS_NO_SHARED_CONTEXT

  /** The mentions sit in one upstream block but in different source clusters. */
  case SAME_BLOCK_DIFFERENT_CLUSTERS

object SupplementCriterion:
  def parse(s: String): Either[String, SupplementCriterion] =
    Try(SupplementCriterion.valueOf(s)).toEither.left.map(_ => s"unknown criterion $s")

/** Configuration of the supplementary queue, read from a versioned resource. The criteria order is
  * the selection priority; the context dimensions define "shared context".
  */
final case class SupplementConfig(
    queueVersion: String,
    generatorVersion: String,
    seed: String,
    maxRows: Int,
    criteriaOrder: Vector[String],
    contextDimensions: Vector[String],
    clusterLinkFeature: String
) derives ReadWriter:
  def criteria: Vector[SupplementCriterion] =
    criteriaOrder.flatMap(c => SupplementCriterion.parse(c).toOption)

object SupplementConfig:
  val DefaultResource = "/scholarchain/er/review-supplement-v1b.json"

  /** Every context dimension the generator knows. */
  val ContextDimensions: Vector[String] =
    Vector("work", "coauthor", "affiliation", "venue", "title_token")

  def parse(json: String): Either[String, SupplementConfig] =
    Try(read[SupplementConfig](json)).toEither.left
      .map(e => s"supplement config: ${e.getMessage}".take(500))
      .flatMap(c => validate(c).toLeft(c))

  def resourceText(resource: String = DefaultResource): Either[String, String] =
    Option(getClass.getResourceAsStream(resource)) match
      case None     => Left(s"supplement config resource $resource not found")
      case Some(in) =>
        try Right(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
        finally in.close()

  def load(resource: String = DefaultResource): Either[String, SupplementConfig] =
    resourceText(resource).flatMap(parse)

  def validate(c: SupplementConfig): Option[String] =
    val errors = Vector(
      Option.when(
        Vector(c.queueVersion, c.generatorVersion, c.seed, c.clusterLinkFeature).exists(_.isBlank)
      )("versions, seed and clusterLinkFeature must be non-empty"),
      Option.when(c.maxRows < 1)("maxRows must be >= 1"),
      Option.when(c.criteriaOrder.isEmpty || c.criteriaOrder.distinct.size != c.criteriaOrder.size)(
        "criteriaOrder must be non-empty and without repeats"
      ),
      c.criteriaOrder.map(SupplementCriterion.parse).collectFirst { case Left(e) => e },
      Option.when(
        c.contextDimensions.isEmpty || c.contextDimensions.distinct.size != c.contextDimensions.size
      )("contextDimensions must be non-empty and without repeats"),
      c.contextDimensions
        .find(d => !ContextDimensions.contains(d))
        .map(d => s"unknown context dimension $d")
    ).flatten
    Option.when(errors.nonEmpty)(errors.mkString("; "))

/** One researcher link of the upstream blocked candidates: two mention IDs in one block, and
  * whether the upstream marked them as one source cluster.
  */
final case class BlockLink(blockKey: String, a: String, b: String, sameCluster: Boolean)

/** One upstream researcher block: its members and the source cluster of each member (the smallest
  * mention ID of its cluster).
  */
final case class Block(key: String, members: Vector[String], clusterOf: Map[String, String])

/** What the criteria read about one mention: its source record fields, nothing derived from a match
  * decision.
  */
final case class MentionContext(
    mentionId: String,
    workId: String,
    source: SourceType,
    rawName: String,
    name: PersonName,
    orcid: Option[String],
    affiliation: Option[String],
    venue: Option[String],
    coauthorKeys: Set[String],
    titleTokens: Set[String]
)

object MentionContext:
  def of(m: AuthorMention, w: WorkRecord): MentionContext =
    MentionContext(
      m.mentionId,
      w.workId,
      m.provenance.source,
      m.rawName.trim,
      ErNormalize.personName(m.rawName),
      m.orcid.map(o => ErNormalize.orcid(o).getOrElse(o.trim)).filter(_.nonEmpty),
      m.affiliation.map(_.trim).filter(_.nonEmpty),
      Profiles.venueKey(w),
      w.authors
        .filterNot(_.mentionId == m.mentionId)
        .map(a => ErNormalize.personName(a.rawName).key)
        .filter(_.nonEmpty)
        .toSet,
      ErNormalize.titleTokens(w.title)
    )

  def index(idx: RecordIndex): Map[String, MentionContext] =
    idx.works.values.flatMap(w => w.authors.map(m => m.mentionId -> of(m, w))).toMap

/** One qualifying pair. `criteria` lists every criterion that holds, in priority order. */
final case class SupplementPair(
    pairId: String,
    blockKey: String,
    sourceId: String,
    targetId: String,
    criteria: Vector[SupplementCriterion]
):
  def primary: SupplementCriterion = criteria.head

object ReviewSupplement:

  // ------------------------------------------------------------------ blocks

  /** Researcher links of the upstream blocked candidates (mention to mention only). */
  def links(cs: Vector[ErCandidate], clusterFeature: String): Vector[BlockLink] =
    cs.filter(c =>
      c.kind == EntityKind.RESEARCHER && Ids.isMentionId(c.sourceId) && Ids.isMentionId(c.targetId)
    ).map(c =>
      BlockLink(
        c.blockKey,
        c.sourceId,
        c.targetId,
        c.features.getOrElse(clusterFeature, 0.0) >= 1.0
      )
    )

  /** Blocks from links. Clusters are the connected components of the same-cluster links; a member
    * without such a link is its own cluster. Deterministic: the cluster label is its smallest ID.
    */
  def blocks(ls: Vector[BlockLink]): Vector[Block] =
    ls.groupBy(_.blockKey).toVector.sortBy(_._1).map { (key, bl) =>
      val members = bl.flatMap(l => Vector(l.a, l.b)).distinct.sorted
      val parent = scala.collection.mutable.Map.from(members.map(m => m -> m))
      @annotation.tailrec
      def find(x: String): String = if parent(x) == x then x else find(parent(x))
      bl.filter(_.sameCluster).sortBy(l => (l.a, l.b)).foreach { l =>
        val (x, y) = (find(l.a), find(l.b))
        if x != y then if x < y then parent(y) = x else parent(x) = y
      }
      Block(key, members, members.map(m => m -> find(m)).toMap)
    }

  /** Same-block work pairs that the upstream did not already emit; the criteria are researcher
    * criteria, so this count is reported, never selected.
    */
  def workPairsNotEmitted(cs: Vector[ErCandidate]): Int =
    val work = cs.filter(_.kind == EntityKind.WORK)
    val emitted = work.map(c => Ids.orderedPair(c.sourceId, c.targetId)).toSet
    work
      .groupBy(_.blockKey)
      .values
      .map { ws =>
        val ms = ws.flatMap(c => Vector(c.sourceId, c.targetId)).distinct.sorted
        (for (a, i) <- ms.zipWithIndex; b <- ms.drop(i + 1) if !emitted((a, b)) yield 1).size
      }
      .sum

  // ------------------------------------------------------------------ criteria

  /** The context dimensions (of the configured ones) that the two mentions share. Coauthor keys
    * exclude the two mentions' own name keys.
    */
  def sharedContext(a: MentionContext, b: MentionContext, dims: Vector[String]): Vector[String] =
    val own = Set(a.name.key, b.name.key)
    dims.filter {
      case "work"        => a.workId == b.workId
      case "coauthor"    => ((a.coauthorKeys -- own) intersect (b.coauthorKeys -- own)).nonEmpty
      case "affiliation" => a.affiliation.nonEmpty && a.affiliation == b.affiliation
      case "venue"       => a.venue.nonEmpty && a.venue == b.venue
      case "title_token" => (a.titleTokens intersect b.titleTokens).nonEmpty
      case _             => true // unknown dimensions are refused by validation; count as shared
    }

  def holds(
      c: SupplementCriterion,
      a: MentionContext,
      b: MentionContext,
      sameCluster: Boolean,
      cfg: SupplementConfig
  ): Boolean = c match
    case SupplementCriterion.DIFFERENT_ORCID =>
      a.orcid.nonEmpty && b.orcid.nonEmpty && a.orcid != b.orcid
    case SupplementCriterion.DBLP_HOMONYM_DIFFERENT =>
      a.source == SourceType.DBLP && b.source == SourceType.DBLP && a.rawName != b.rawName &&
      ErNormalize.fold(ErNormalize.stripDblpHomonym(a.rawName)) ==
        ErNormalize.fold(ErNormalize.stripDblpHomonym(b.rawName))
    case SupplementCriterion.COMPATIBLE_INITIALS_NO_SHARED_CONTEXT =>
      Features.initialsCompatible(a.name, b.name) &&
      sharedContext(a, b, cfg.contextDimensions).isEmpty
    case SupplementCriterion.SAME_BLOCK_DIFFERENT_CLUSTERS => !sameCluster

  /** Every pair of mentions inside one block for which at least one configured criterion holds, one
    * per pair ID (the first block in key order wins). A member without a source record is an error,
    * never a silent skip.
    */
  def qualifying(
      bs: Vector[Block],
      ctx: Map[String, MentionContext],
      cfg: SupplementConfig
  ): Either[String, Vector[SupplementPair]] =
    bs.flatMap(_.members).find(m => !ctx.contains(m)) match
      case Some(m) => Left(s"block member $m has no source record in the run's export")
      case None    =>
        val all = for
          b <- bs
          (x, i) <- b.members.zipWithIndex
          y <- b.members.drop(i + 1)
          sameCluster = b.clusterOf(x) == b.clusterOf(y)
          criteria = cfg.criteria.filter(holds(_, ctx(x), ctx(y), sameCluster, cfg))
          if criteria.nonEmpty
        yield
          val (s, t) = Ids.orderedPair(x, y)
          SupplementPair(ErCandidate.idOf(EntityKind.RESEARCHER, s, t), b.key, s, t, criteria)
        Right(all.distinctBy(_.pairId))

  // ------------------------------------------------------------------ selection

  private def rank(seed: String, salt: String, id: String): String =
    Hashing.sha256Hex(s"$seed|$salt|$id")

  final case class Selection(
      chosen: Vector[SupplementPair],
      qualifying: Int,
      inReviewV1: Int,
      upstreamCandidates: Int,
      eligible: Int,
      availablePrimary: Map[SupplementCriterion, Int],
      selectedPrimary: Map[SupplementCriterion, Int],
      availableAny: Map[SupplementCriterion, Int],
      selectedAny: Map[SupplementCriterion, Int]
  )

  /** Seeded block round robin inside one tier: blocks in hash order, pairs of a block in hash
    * order, then the first pair of every block, the second of every block, and so on.
    */
  def roundRobin(tier: Vector[SupplementPair], seed: String): Vector[SupplementPair] =
    val byBlock = tier
      .groupBy(_.blockKey)
      .toVector
      .sortBy((k, _) => rank(seed, "block", k))
      .map((_, ps) => ps.sortBy(p => rank(seed, "pick", p.pairId)))
    val depth = byBlock.map(_.size).maxOption.getOrElse(0)
    (0 until depth).toVector.flatMap(i => byBlock.flatMap(_.lift(i)))

  /** Drops every pair of the first review queue and every pair the upstream already emitted (so
    * each supplementary pair has exactly one er-v1 decision, from the supplementary run), then
    * fills `maxRows` tier by tier in criteria order. Never pads. The result is ordered by a second
    * hash, so a row's position says nothing about its criterion.
    */
  def select(
      all: Vector[SupplementPair],
      reviewV1: Set[String],
      upstream: Set[String],
      cfg: SupplementConfig
  ): Selection =
    val notV1 = all.filterNot(p => reviewV1(p.pairId))
    val eligible = notV1.filterNot(p => upstream(p.pairId))
    val crit = cfg.criteria
    val picked = crit
      .flatMap(c => roundRobin(eligible.filter(_.primary == c), cfg.seed))
      .take(cfg.maxRows)
    def countPrimary(ps: Vector[SupplementPair]) =
      crit.map(c => c -> ps.count(_.primary == c)).toMap
    def countAny(ps: Vector[SupplementPair]) =
      crit.map(c => c -> ps.count(_.criteria.contains(c))).toMap
    Selection(
      picked.sortBy(p => rank(cfg.seed, "order", p.pairId)),
      all.size,
      all.size - notV1.size,
      notV1.size - eligible.size,
      eligible.size,
      countPrimary(eligible),
      countPrimary(picked),
      countAny(eligible),
      countAny(picked)
    )

  // ------------------------------------------------------------------ outputs

  /** The supplementary pair as a match-candidate-v1 record for the unchanged er-v1 job. It carries
    * no upstream feature and no identifier: er-v1 decides from the endpoint profiles alone. Its
    * provenance names the generator version and the supplementary run.
    */
  def candidate(
      p: SupplementPair,
      snapshot: String,
      runId: String,
      cfg: SupplementConfig
  ): ErCandidate =
    ErCandidate(
      ErCandidate.SchemaVersion,
      p.pairId,
      EntityKind.RESEARCHER,
      p.sourceId,
      p.targetId,
      p.blockKey,
      Map.empty,
      Map.empty,
      Map.empty,
      Provenance(
        SourceType.SCHOLARCHAIN,
        p.blockKey,
        snapshot,
        runId,
        ErCandidate.SchemaVersion,
        Some(cfg.generatorVersion)
      )
    )

  /** The queue rows, built exactly as for the first queue (same columns, sides and CSV rules). */
  def rows(
      chosen: Vector[SupplementPair],
      idx: RecordIndex,
      queueCfg: ReviewQueueConfig,
      upstreamRunId: String
  ): Either[String, Vector[ReviewRow]] =
    ReviewQueue.rows(
      chosen.map(p =>
        CandidateView(
          p.pairId,
          EntityKind.RESEARCHER,
          p.sourceId,
          p.targetId,
          Map.empty,
          upstreamRunId
        )
      ),
      idx,
      queueCfg
    )

  /** Minimal RFC 4180 reader (quoted fields, doubled quotes, CR LF or LF record ends). */
  def csvRecords(text: String): Either[String, Vector[Vector[String]]] =
    val rows = Vector.newBuilder[Vector[String]]
    val row = Vector.newBuilder[String]
    val cell = new StringBuilder
    var quoted = false
    var started = false
    var i = 0
    while i < text.length do
      val ch = text(i)
      if quoted then
        if ch == '"' && i + 1 < text.length && text(i + 1) == '"' then { cell += '"'; i += 1 }
        else if ch == '"' then quoted = false
        else cell += ch
      else if ch == '"' then { quoted = true; started = true }
      else if ch == ',' then { row += cell.toString; cell.clear(); started = true }
      else if ch == '\n' || ch == '\r' then
        if ch == '\r' && i + 1 < text.length && text(i + 1) == '\n' then i += 1
        row += cell.toString; cell.clear(); rows += row.result(); row.clear(); started = false
      else { cell += ch; started = true }
      i += 1
    if quoted then Left("unterminated quoted field")
    else
      if started || cell.nonEmpty then { row += cell.toString; rows += row.result() }
      Right(rows.result())

  /** The pair IDs of a review queue file whose header is exactly [[ReviewQueue.Columns]]. */
  def pairIds(csvText: String): Either[String, Vector[String]] =
    csvRecords(csvText).flatMap {
      case header +: body if header == ReviewQueue.Columns =>
        body.find(_.size != header.size) match
          case Some(r) => Left(s"a review row has ${r.size} fields, expected ${header.size}")
          case None    =>
            val ids = body.map(_.head)
            if ids.exists(_.isBlank) then Left("a review row has an empty pair_id")
            else if ids.distinct.size != ids.size then Left("the review queue repeats a pair_id")
            else Right(ids)
      case _ => Left("the review queue header is not the agreed column list")
    }
