package scholarchain.er.review

import scala.util.Try

import scholarchain.core.*
import upickle.default.*

/** Configuration of the human review queue. It is read from a versioned resource, never from code
  * literals. The stratification fields choose which pairs the human sees; they are not the er-v1
  * match policy and they never reach the output file.
  */
final case class ReviewQueueConfig(
    queueVersion: String,
    strataVersion: String,
    seed: String,
    targetRows: Int,
    minRows: Int,
    likelyMatchAtLeast: Double,
    likelyNonMatchBelow: Double,
    identifierConflictFeatures: Vector[String],
    identifierMatchFeatures: Vector[String],
    researcherWeights: Map[String, Double],
    workWeights: Map[String, Double],
    maxAffiliations: Int,
    maxCoauthors: Int,
    maxVenuesYears: Int,
    maxTitles: Int
) derives ReadWriter

object ReviewQueueConfig:
  val DefaultResource = "/scholarchain/er/review-queue-v1.json"

  def parse(json: String): Either[String, ReviewQueueConfig] =
    Try(read[ReviewQueueConfig](json)).toEither.left
      .map(e => s"review queue config: ${e.getMessage}".take(500))
      .flatMap(c => validate(c).toLeft(c))

  def load(resource: String = DefaultResource): Either[String, ReviewQueueConfig] =
    Option(getClass.getResourceAsStream(resource)) match
      case None     => Left(s"review queue config resource $resource not found")
      case Some(in) =>
        try parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
        finally in.close()

  def validate(c: ReviewQueueConfig): Option[String] =
    val errors = Vector(
      Option.when(c.queueVersion.isBlank || c.strataVersion.isBlank || c.seed.isBlank)(
        "versions and seed must be non-empty"
      ),
      Option.when(c.minRows < 1 || c.targetRows < c.minRows)("need 1 <= minRows <= targetRows"),
      Option.when(
        !(0.0 <= c.likelyNonMatchBelow && c.likelyNonMatchBelow <= c.likelyMatchAtLeast &&
          c.likelyMatchAtLeast <= 1.0)
      )("need 0 <= likelyNonMatchBelow <= likelyMatchAtLeast <= 1"),
      Option.when(
        c.researcherWeights.isEmpty || c.workWeights.isEmpty ||
          (c.researcherWeights.values ++ c.workWeights.values).exists(w => !(w > 0.0))
      )("weights must be non-empty and positive"),
      Option.when(
        Vector(c.maxAffiliations, c.maxCoauthors, c.maxVenuesYears, c.maxTitles).exists(_ < 1)
      )("list caps must be >= 1")
    ).flatten
    Option.when(errors.nonEmpty)(errors.mkString("; "))

/** The three review strata. Internal only: a stratum name never appears in the queue file. */
enum Stratum:
  case LikelyMatch, Borderline, LikelyNonMatch

/** A Phase 04 `match-candidate-v1` line, read without importing the hadoop module (pipeline stages
  * exchange data only through files). Only the fields the queue needs are kept.
  */
final case class CandidateView(
    candidateId: String,
    kind: EntityKind,
    sourceId: String,
    targetId: String,
    features: Map[String, Double],
    runId: String
)

object CandidateView:
  val SchemaVersion = "match-candidate-v1"

  def parse(line: String): Either[String, CandidateView] =
    Try {
      val o = ujson.read(line).obj
      def str(k: String): String = o.get(k) match
        case Some(ujson.Str(s)) if s.nonEmpty => s
        case _ => throw new IllegalArgumentException(s"missing or empty string field $k")
      require(str("schemaVersion") == SchemaVersion, s"schemaVersion is not $SchemaVersion")
      val kind = str("kind") match
        case "RESEARCHER" => EntityKind.RESEARCHER
        case "WORK"       => EntityKind.WORK
        case other        => throw new IllegalArgumentException(s"unsupported kind $other")
      val id = str("candidateId")
      require(
        id.startsWith(s"mc:${kind.toString.toLowerCase}:"),
        s"candidateId $id does not match kind $kind"
      )
      val features = o.get("features") match
        case Some(ujson.Obj(fs)) =>
          fs.toMap.map {
            case (k, ujson.Num(v)) if !v.isNaN && !v.isInfinite => k -> v
            case (k, _) => throw new IllegalArgumentException(s"feature $k is not a number")
          }
        case _ => throw new IllegalArgumentException("missing features object")
      val runId = o.get("provenance") match
        case Some(ujson.Obj(p)) =>
          p.get("runId") match
            case Some(ujson.Str(r)) if r.nonEmpty => r
            case _ => throw new IllegalArgumentException("missing provenance.runId")
        case _ => throw new IllegalArgumentException("missing provenance")
      val (src, tgt) = (str("sourceId"), str("targetId"))
      require(src != tgt, "sourceId equals targetId")
      CandidateView(id, kind, src, tgt, features, runId)
    }.toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}".take(500))

  /** One view per candidate ID. A pair can appear under several keys (exact and blocked, DOI and
    * arXiv); copies must agree on kind and endpoints, and their features are merged by maximum.
    */
  def merge(views: Vector[CandidateView]): Either[String, Vector[CandidateView]] =
    val groups = views.groupBy(_.candidateId).toVector.sortBy(_._1)
    val bad = groups.collectFirst {
      case (id, vs) if vs.map(v => (v.kind, v.sourceId, v.targetId, v.runId)).distinct.size > 1 =>
        s"candidate $id has copies with different kind, endpoints or run"
    }
    bad.toLeft(groups.map { (_, vs) =>
      val features = vs.flatMap(_.features).groupMapReduce(_._1)(_._2)(math.max)
      vs.head.copy(features = features)
    })

/** One side of a review row: what the human needs to judge identity, and the source record keys. */
final case class ReviewSide(
    source: String,
    recordId: String,
    nameOrTitle: String,
    orcidOrDoi: String,
    affiliations: Vector[String],
    coauthors: Vector[String],
    venuesYears: Vector[String],
    titles: Vector[String]
)

/** A row of the queue. It carries no score, stratum, path or label by construction. */
final case class ReviewRow(pairId: String, kind: EntityKind, left: ReviewSide, right: ReviewSide)

/** Records of the same pipeline run, indexed by ID, used to describe each candidate endpoint. */
final case class RecordIndex(
    works: Map[String, WorkRecord],
    venues: Map[String, Venue],
    institutions: Map[String, Institution]
):
  val mentions: Map[String, AuthorMention] =
    works.values.flatMap(_.authors).map(m => m.mentionId -> m).toMap
  val mentionsByOrcid: Map[String, Vector[AuthorMention]] =
    mentions.values.toVector
      .flatMap(m => m.orcid.map(_ -> m))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.sortBy(_.mentionId))
      .toMap

object ReviewQueue:
  val Columns: Vector[String] =
    val side = Vector(
      "source",
      "record_id",
      "name_or_title",
      "orcid_or_doi",
      "affiliations",
      "coauthors",
      "venues_years",
      "titles"
    )
    Vector("pair_id", "kind") ++ side.map("left_" + _) ++ side.map("right_" + _) ++
      Vector("label", "note")

  /** Separator inside a multi-valued cell. */
  val ListSeparator = " | "

  // ------------------------------------------------------------------ stratification

  /** Internal similarity used only to choose strata. An identifier conflict puts the pair in the
    * non-match stratum, shared identifier evidence in the match stratum, otherwise the weighted
    * mean of the configured features (a missing feature counts as 0).
    */
  def similarity(c: CandidateView, cfg: ReviewQueueConfig): Double =
    def on(names: Vector[String]) = names.exists(n => c.features.getOrElse(n, 0.0) > 0.0)
    if on(cfg.identifierConflictFeatures) then 0.0
    else if on(cfg.identifierMatchFeatures) then 1.0
    else
      val weights = if c.kind == EntityKind.WORK then cfg.workWeights else cfg.researcherWeights
      val total = weights.values.sum
      val s = weights.toVector.map((f, w) => w * c.features.getOrElse(f, 0.0)).sum / total
      math.max(0.0, math.min(1.0, s))

  def stratum(c: CandidateView, cfg: ReviewQueueConfig): Stratum =
    val s = similarity(c, cfg)
    if s >= cfg.likelyMatchAtLeast then Stratum.LikelyMatch
    else if s < cfg.likelyNonMatchBelow then Stratum.LikelyNonMatch
    else Stratum.Borderline

  type Cell = (EntityKind, Stratum)

  val Cells: Vector[Cell] =
    for
      k <- Vector(EntityKind.RESEARCHER, EntityKind.WORK)
      s <- Stratum.values.toVector
    yield (k, s)

  /** Balanced allocation of `target` rows over the cells: every non-empty cell gets an equal share,
    * and what a small cell cannot use goes to the others. Deterministic (cell order breaks ties).
    */
  def allocate(capacity: Map[Cell, Int], target: Int): Map[Cell, Int] =
    val cells = Cells.filter(c => capacity.getOrElse(c, 0) > 0)
    @annotation.tailrec
    def go(quota: Map[Cell, Int]): Map[Cell, Int] =
      val remaining = target - quota.values.sum
      val open = cells.filter(c => quota(c) < capacity(c))
      if remaining <= 0 || open.isEmpty then quota
      else
        val share = remaining / open.size
        val next =
          if share == 0 then open.take(remaining).foldLeft(quota)((q, c) => q.updated(c, q(c) + 1))
          else open.foldLeft(quota)((q, c) => q.updated(c, math.min(capacity(c), q(c) + share)))
        go(next)
    go(cells.map(_ -> 0).toMap)

  private def rank(seed: String, salt: String, id: String): String =
    Hashing.sha256Hex(s"$seed|$salt|$id")

  final case class Selection(
      chosen: Vector[CandidateView],
      available: Map[Cell, Int],
      selected: Map[Cell, Int]
  )

  /** Stratified, seeded and deterministic: within a cell, candidates are ranked by a hash of the
    * seed and the candidate ID; the result is ordered by a second hash, so the row position says
    * nothing about the stratum.
    */
  def select(candidates: Vector[CandidateView], cfg: ReviewQueueConfig): Selection =
    val byCell = candidates.groupBy(c => (c.kind, stratum(c, cfg)))
    val available = Cells.map(c => c -> byCell.get(c).fold(0)(_.size)).toMap
    val quota = allocate(available, cfg.targetRows)
    val chosen = Cells
      .flatMap(cell =>
        byCell
          .getOrElse(cell, Vector.empty)
          .sortBy(c => rank(cfg.seed, "pick", c.candidateId))
          .take(quota.getOrElse(cell, 0))
      )
      .sortBy(c => rank(cfg.seed, "order", c.candidateId))
    Selection(chosen, available, Cells.map(c => c -> quota.getOrElse(c, 0)).toMap)

  // ------------------------------------------------------------------ sides

  private def distinctNonEmpty(xs: Iterable[String]): Vector[String] =
    xs.iterator.map(_.trim).filter(_.nonEmpty).toVector.distinct

  private def venueYear(w: WorkRecord, idx: RecordIndex): Option[String] =
    val venue =
      w.venueName.filter(_.trim.nonEmpty).orElse(w.venueId.flatMap(idx.venues.get).map(_.name))
    (venue, w.year) match
      case (Some(v), Some(y)) => Some(s"$v ($y)")
      case (Some(v), None)    => Some(v)
      case (None, Some(y))    => Some(y.toString)
      case (None, None)       => None

  private def institutionName(id: String, idx: RecordIndex): String =
    idx.institutions.get(id).map(_.name).getOrElse(id)

  private def workOf(m: AuthorMention, idx: RecordIndex): Either[String, WorkRecord] =
    idx.works.get(m.workId).toRight(s"work ${m.workId} of mention ${m.mentionId} not found")

  private def mentionSide(
      recordId: String,
      source: String,
      ms: Vector[AuthorMention],
      idx: RecordIndex,
      cfg: ReviewQueueConfig
  ): Either[String, ReviewSide] =
    val worksE = ms.map(workOf(_, idx))
    worksE.collectFirst { case Left(e) => e }.toLeft {
      val works = worksE.collect { case Right(w) => w }
      val own = ms.map(_.mentionId).toSet
      val ownNames = ms.map(_.rawName).toSet
      val coauthors = works
        .flatMap(w => w.authors.sortBy(_.position).filterNot(a => own(a.mentionId)).map(_.rawName))
      ReviewSide(
        source,
        recordId,
        distinctNonEmpty(ms.map(_.rawName)).mkString(ListSeparator),
        distinctNonEmpty(ms.flatMap(_.orcid)).mkString(ListSeparator),
        distinctNonEmpty(ms.flatMap(_.affiliation).map(institutionName(_, idx)))
          .take(cfg.maxAffiliations),
        distinctNonEmpty(coauthors).filterNot(ownNames).take(cfg.maxCoauthors),
        distinctNonEmpty(works.flatMap(venueYear(_, idx))).take(cfg.maxVenuesYears),
        distinctNonEmpty(works.map(_.title)).take(cfg.maxTitles)
      )
    }

  /** Describes one candidate endpoint from the run's records. An endpoint that cannot be traced to
    * a record is an error, never an empty side.
    */
  def side(
      kind: EntityKind,
      id: String,
      idx: RecordIndex,
      cfg: ReviewQueueConfig
  ): Either[String, ReviewSide] =
    kind match
      case EntityKind.WORK =>
        idx.works.get(id).toRight(s"work $id not found in the run's export").map { w =>
          ReviewSide(
            w.source.toString,
            w.workId,
            w.title,
            w.doi.getOrElse(""),
            distinctNonEmpty(w.institutionIds.map(institutionName(_, idx)))
              .take(cfg.maxAffiliations),
            distinctNonEmpty(w.authors.sortBy(_.position).map(_.rawName)).take(cfg.maxCoauthors),
            venueYear(w, idx).toVector,
            Vector(w.title).take(cfg.maxTitles)
          )
        }
      case EntityKind.RESEARCHER if id.startsWith("m:") =>
        idx.mentions
          .get(id)
          .toRight(s"mention $id not found in the run's export")
          .flatMap(m => mentionSide(id, m.provenance.source.toString, Vector(m), idx, cfg))
      case EntityKind.RESEARCHER if id.startsWith("orcid:") =>
        // The canonical ORCID researcher of J03: the mentions that carry this ORCID in the run.
        val ms = idx.mentionsByOrcid.getOrElse(id.stripPrefix("orcid:"), Vector.empty)
        if ms.isEmpty then Left(s"no mention carries $id in the run's export")
        else mentionSide(id, SourceType.SCHOLARCHAIN.toString, ms, idx, cfg)
      case other => Left(s"unsupported endpoint $id of kind $other")

  def rows(
      chosen: Vector[CandidateView],
      idx: RecordIndex,
      cfg: ReviewQueueConfig
  ): Either[String, Vector[ReviewRow]] =
    val built = chosen.map(c =>
      for
        l <- side(c.kind, c.sourceId, idx, cfg)
        r <- side(c.kind, c.targetId, idx, cfg)
      yield ReviewRow(c.candidateId, c.kind, l, r)
    )
    built.collectFirst { case Left(e) => e }.toLeft(built.collect { case Right(r) => r })

  // ------------------------------------------------------------------ CSV

  /** RFC 4180 field: quoted when it holds a comma, quote, CR or LF, or edge whitespace. */
  def csvField(s: String): String =
    val needs = s.exists(ch => ch == ',' || ch == '"' || ch == '\n' || ch == '\r') ||
      (s.nonEmpty && (s.head.isWhitespace || s.last.isWhitespace))
    if needs then "\"" + s.replace("\"", "\"\"") + "\"" else s

  private def sideCells(s: ReviewSide): Vector[String] =
    Vector(
      s.source,
      s.recordId,
      s.nameOrTitle,
      s.orcidOrDoi,
      s.affiliations.mkString(ListSeparator),
      s.coauthors.mkString(ListSeparator),
      s.venuesYears.mkString(ListSeparator),
      s.titles.mkString(ListSeparator)
    )

  /** The label and note cells are always empty: the human writes them. */
  def cells(r: ReviewRow): Vector[String] =
    Vector(r.pairId, r.kind.toString.toLowerCase) ++ sideCells(r.left) ++ sideCells(r.right) ++
      Vector("", "")

  /** The whole file, UTF-8 text with CRLF record ends (RFC 4180). */
  def csv(rows: Vector[ReviewRow]): String =
    (Columns +: rows.map(cells)).map(_.map(csvField).mkString(",")).mkString("", "\r\n", "\r\n")

  final case class Built(selection: Selection, rows: Vector[ReviewRow], csv: String, runId: String)

  private def singleRun(cs: Vector[CandidateView]): Either[String, String] =
    cs.map(_.runId).distinct match
      case Vector(one) => Right(one)
      case runs        => Left(s"candidates must come from exactly one run, got ${runs.size}")

  /** The pure queue builder: merged candidates of one run in, CSV text out. */
  def build(
      candidates: Vector[CandidateView],
      idx: RecordIndex,
      cfg: ReviewQueueConfig
  ): Either[String, Built] =
    for
      merged <- CandidateView.merge(candidates)
      runId <- singleRun(merged)
      _ <- Either.cond(
        merged.size >= cfg.minRows,
        (),
        s"only ${merged.size} distinct candidate pairs, fewer than minRows ${cfg.minRows}"
      )
      selection = select(merged, cfg)
      _ <- Either.cond(
        selection.chosen.size >= cfg.minRows,
        (),
        s"selected ${selection.chosen.size} rows, fewer than minRows ${cfg.minRows}"
      )
      built <- rows(selection.chosen, idx, cfg)
    yield Built(selection, built, csv(built), runId)
