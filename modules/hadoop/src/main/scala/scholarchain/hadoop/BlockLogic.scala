package scholarchain.hadoop

import scholarchain.core.*
import upickle.default.ReadWriter

/** Researcher and work blocking, and the deterministic match features computed inside a block.
  *
  * Researcher block key: `<last name token>_<first initial>` of the folded name (`wang_w`). Work
  * block key: `wk:<year>:<two longest title tokens>`. A mention is summarized with the coauthor,
  * title, year, venue, affiliation and source context the features need, so the reducer never reads
  * another file.
  *
  * Bounds: a block with more than `maxBlock` clusters is split by full normalized name; a part that
  * is still too large is refused (counted and rejected) and keeps only its linear within-cluster
  * links. A block with more than `hardCap(maxBlock)` members is not expanded at all.
  *
  * Fallback keys (policy for oversize blocks): when a block is oversize, its clusters (works) are
  * re-blocked, inside the buffered block, by `<key>|co:<coauthor key>`, `<key>|v:<venue>` and
  * `<key>|y:<year>` (works: `<key>|v:<venue>` and `<key>|a:<author key>`). A fallback block is
  * compared only when it has at most `maxBlock` members, never from an empty attribute, never
  * between two mentions of the same work, and a pair already emitted is not emitted again. A
  * fallback block above `maxBlock` is refused (counted FALLBACK_BLOCKS_REFUSED and rejected).
  */
object BlockLogic:
  /** Members above which a reducer stops buffering a block and refuses it whole. */
  def hardCap(maxBlock: Int): Int = math.min(maxBlock.toLong * 10, Int.MaxValue.toLong).toInt

  // ------------------------------------------------------------ summaries and keys

  /** One author mention, summarized for blocking. `clusterKey` groups mentions that a source has
    * already disambiguated: identical DBLP author strings (DBLP separates homonyms by numeric
    * suffix) or identical OpenAlex author IDs.
    */
  final case class MentionSummary(
      mentionId: String,
      workId: String,
      source: SourceType,
      rawName: String,
      normalizedName: String,
      clusterKey: String,
      orcid: Option[String],
      externalAuthorId: Option[String],
      affiliation: Option[String],
      year: Option[Int],
      venueId: Option[String],
      coauthorKeys: Vector[String],
      titleTokens: Vector[String]
  ) derives ReadWriter

  final case class WorkSummary(
      workId: String,
      source: SourceType,
      normalizedTitle: String,
      year: Option[Int],
      venueId: Option[String],
      venueName: Option[String],
      doi: Option[String],
      authorKeys: Vector[String]
  ) derives ReadWriter

  def clusterKey(source: SourceType, m: AuthorMention): String = source match
    case SourceType.DBLP => s"dblpname:${m.rawName}"
    case _               => m.externalAuthorId.map("ext:" + _).getOrElse(s"mention:${m.mentionId}")

  /** (block key, summary) for every mention with a name key; the caller counts the rest. */
  def mentionBlocks(work: WorkRecord): Vector[(String, MentionSummary)] =
    val keys = work.authors.map(m => Identifiers.nameBlockKey(m.rawName))
    val title = Similarity.tokens(work.normalizedTitle).toVector.sorted.take(12)
    work.authors.zip(keys).collect {
      case (m, key) if key.nonEmpty =>
        val coauthors = keys.filter(k => k.nonEmpty && k != key).distinct.sorted
        key -> MentionSummary(
          m.mentionId,
          work.workId,
          work.source,
          m.rawName,
          m.normalizedName,
          clusterKey(work.source, m),
          m.orcid,
          m.externalAuthorId,
          m.affiliation,
          work.year,
          work.venueId,
          coauthors,
          title
        )
    }

  def workBlockKey(work: WorkRecord): Option[String] =
    val tokens = Similarity
      .tokens(work.normalizedTitle)
      .filter(_.length >= 4)
      .toVector
      .sortBy(t => (-t.length, t))
      .take(2)
      .sorted
    Option.when(tokens.nonEmpty)(s"wk:${work.year.fold("na")(_.toString)}:${tokens.mkString("+")}")

  def workBlock(work: WorkRecord): Option[(String, WorkSummary)] =
    workBlockKey(work).map(
      _ -> WorkSummary(
        work.workId,
        work.source,
        work.normalizedTitle,
        work.year,
        work.venueId,
        work.venueName,
        work.doi,
        work.authors
          .map(m => Identifiers.nameBlockKey(m.rawName))
          .filter(_.nonEmpty)
          .distinct
          .sorted
      )
    )

  // ------------------------------------------------------------ researcher blocks

  /** Aggregated evidence of one source-disambiguated cluster inside a block. */
  final case class Cluster(
      key: String,
      representative: MentionSummary,
      members: Vector[MentionSummary]
  ):
    lazy val orcids: Set[String] = members.flatMap(_.orcid).toSet
    lazy val externalIds: Set[String] = members.flatMap(_.externalAuthorId).toSet
    lazy val affiliations: Set[String] = members.flatMap(_.affiliation).toSet
    lazy val coauthors: Set[String] = members.flatMap(_.coauthorKeys).toSet
    lazy val venues: Set[String] = members.flatMap(_.venueId).toSet
    lazy val titleTokens: Set[String] = members.flatMap(_.titleTokens).toSet
    lazy val years: Vector[Int] = members.flatMap(_.year)
    lazy val workIds: Set[String] = members.map(_.workId).toSet

  /** Candidates of one (sub-)block; `oversize` carries the reason when the block was refused. */
  final case class BlockResult(
      candidates: Vector[MatchCandidate],
      oversize: Option[String],
      clusters: Int,
      fallback: Boolean = false
  )

  def clusters(members: Vector[MentionSummary]): Vector[Cluster] =
    members.groupBy(_.clusterKey).toVector.sortBy(_._1).map { (k, ms) =>
      // Identical copies collapse; the order never depends on arrival.
      val sorted = ms.distinct.sortBy(m => (m.mentionId, JsonLines.encode(m)))
      Cluster(k, sorted.head, sorted)
    }

  def block(
      key: String,
      members: Vector[MentionSummary],
      maxBlock: Int,
      run: RunContext
  ): Vector[BlockResult] =
    val cs = clusters(members)
    if cs.size <= maxBlock then Vector(compareBlock(key, cs, run))
    else
      val primary =
        cs.groupBy(_.representative.normalizedName).toVector.sortBy(_._1).map { (name, sub) =>
          if sub.size <= maxBlock then compareBlock(s"$key/$name", sub, run)
          else
            BlockResult(
              sub.flatMap(memberLinks(_, s"$key/$name", run)),
              Some(s"block $key/$name has ${sub.size} clusters > $maxBlock"),
              sub.size
            )
        }
      primary ++ fallbackBlocks(key, cs, primary, maxBlock, run)

  private def pairId(c: MatchCandidate): (String, String) =
    if c.sourceId <= c.targetId then (c.sourceId, c.targetId) else (c.targetId, c.sourceId)

  /** Fallback blocks of an oversize researcher block, in key order. */
  private def fallbackBlocks(
      key: String,
      cs: Vector[Cluster],
      primary: Vector[BlockResult],
      maxBlock: Int,
      run: RunContext
  ): Vector[BlockResult] =
    val groups =
      (cs.flatMap(c => c.coauthors.toVector.filter(_.nonEmpty).sorted.map(k => s"co:$k" -> c)) ++
        cs.flatMap(c => c.venues.toVector.filter(_.nonEmpty).sorted.map(k => s"v:$k" -> c)) ++
        cs.flatMap(c => c.years.distinct.sorted.map(y => s"y:$y" -> c)))
        .groupBy(_._1)
        .toVector
        .sortBy(_._1)
        .map((k, vs) => k -> vs.map(_._2).distinctBy(_.key))
        .filter((_, g) => g.size >= 2 && g.flatMap(c => c.workIds).distinct.size > 1)
    val seen = scala.collection.mutable.Set.from(primary.flatMap(_.candidates).map(pairId))
    groups.map { (k, g) =>
      val blockKey = s"$key|$k"
      if g.size > maxBlock then
        BlockResult(
          Vector.empty,
          Some(s"fallback block $blockKey has ${g.size} clusters > $maxBlock"),
          g.size,
          true
        )
      else
        val pairs =
          for
            (a, i) <- g.zipWithIndex
            b <- g.drop(i + 1)
            if a.workIds.intersect(b.workIds).isEmpty
            cand = MatchCandidate.symmetric(
              EntityKind.RESEARCHER,
              a.representative.mentionId,
              idsOf(a),
              b.representative.mentionId,
              idsOf(b),
              blockKey,
              features(a, b),
              run
            )
            if seen.add(pairId(cand))
          yield cand
        BlockResult(pairs, None, g.size, true)
    }

  private def idsOf(c: Cluster): Map[String, String] =
    c.orcids.toVector.sorted.take(1).map("orcid" -> _).toMap ++
      c.externalIds.toVector.sorted.take(1).map("openalex" -> _)

  private def memberLinks(c: Cluster, blockKey: String, run: RunContext): Vector[MatchCandidate] =
    c.members.filterNot(_.mentionId == c.representative.mentionId).map { m =>
      val f = Map(
        "same_source_cluster" -> 1.0,
        "name_jw" -> 1.0,
        "dblp_name_identical" -> (if c.key.startsWith("dblpname:") then 1.0 else 0.0)
      )
      MatchCandidate.symmetric(
        EntityKind.RESEARCHER,
        c.representative.mentionId,
        idsOf(c),
        m.mentionId,
        Map.empty,
        blockKey,
        f,
        run
      )
    }

  private def compareBlock(key: String, cs: Vector[Cluster], run: RunContext): BlockResult =
    val links = cs.flatMap(memberLinks(_, key, run))
    val pairs =
      for
        (a, i) <- cs.zipWithIndex
        b <- cs.drop(i + 1)
      yield MatchCandidate.symmetric(
        EntityKind.RESEARCHER,
        a.representative.mentionId,
        idsOf(a),
        b.representative.mentionId,
        idsOf(b),
        key,
        features(a, b),
        run
      )
    BlockResult(links ++ pairs, None, cs.size)

  /** Deterministic match features between two clusters. Every value is in [0, 1] except `year_gap`,
    * which is in years (99 when unknown). Evidence of absence is separate from evidence of
    * conflict: `orcid_conflict` needs an ORCID on both sides.
    */
  def features(a: Cluster, b: Cluster): Map[String, Double] =
    def flag(c: Boolean) = if c then 1.0 else 0.0
    val (ra, rb) = (a.representative, b.representative)
    val bothOrcid = a.orcids.nonEmpty && b.orcids.nonEmpty
    val bothExt = a.externalIds.nonEmpty && b.externalIds.nonEmpty
    val dblpHomonyms = a.key.startsWith("dblpname:") && b.key.startsWith("dblpname:") &&
      Normalize.stripDblpHomonym(ra.rawName) == Normalize.stripDblpHomonym(rb.rawName) &&
      ra.rawName != rb.rawName
    val yearGap =
      if a.years.isEmpty || b.years.isEmpty then 99.0
      else
        math
          .max(0, math.max(a.years.min, b.years.min) - math.min(a.years.max, b.years.max))
          .toDouble
    Map(
      "name_jw" -> Similarity.jaroWinkler(ra.normalizedName, rb.normalizedName),
      "same_full_name" -> flag(ra.normalizedName == rb.normalizedName),
      "initials_compatible" -> flag(
        Similarity.initialsCompatible(ra.normalizedName, rb.normalizedName)
      ),
      "coauthor_overlap" -> Similarity.jaccard(a.coauthors, b.coauthors),
      "affiliation_known" -> flag(a.affiliations.nonEmpty && b.affiliations.nonEmpty),
      "affiliation_overlap" -> Similarity.jaccard(a.affiliations, b.affiliations),
      "orcid_equal" -> flag(bothOrcid && a.orcids.intersect(b.orcids).nonEmpty),
      "orcid_conflict" -> flag(bothOrcid && a.orcids.intersect(b.orcids).isEmpty),
      "external_id_equal" -> flag(bothExt && a.externalIds.intersect(b.externalIds).nonEmpty),
      "external_id_conflict" -> flag(bothExt && a.externalIds.intersect(b.externalIds).isEmpty),
      "dblp_homonym_distinct" -> flag(dblpHomonyms),
      "venue_overlap" -> Similarity.jaccard(a.venues, b.venues),
      "title_token_overlap" -> Similarity.jaccard(a.titleTokens, b.titleTokens),
      "cross_source" -> flag(ra.source != rb.source),
      "shared_work" -> flag(a.workIds.intersect(b.workIds).nonEmpty),
      "year_gap" -> yearGap
    )

  // ------------------------------------------------------------ work blocks

  def workBlockResult(
      key: String,
      members: Vector[WorkSummary],
      maxBlock: Int,
      run: RunContext
  ): Vector[BlockResult] =
    val ws = members
      .groupBy(_.workId)
      .values
      .map(_.minBy(w => JsonLines.encode(w)))
      .toVector
      .sortBy(_.workId)
    if ws.size > maxBlock then
      val refused =
        BlockResult(
          Vector.empty,
          Some(s"work block $key has ${ws.size} works > $maxBlock"),
          ws.size
        )
      refused +: workFallbackBlocks(key, ws, maxBlock, run)
    else Vector(compareWorks(key, ws, run, scala.collection.mutable.Set.empty, false))

  private def compareWorks(
      key: String,
      ws: Vector[WorkSummary],
      run: RunContext,
      seen: scala.collection.mutable.Set[(String, String)],
      fallback: Boolean
  ): BlockResult =
    val cands =
      for
        (a, i) <- ws.zipWithIndex
        b <- ws.drop(i + 1)
        cand = MatchCandidate.symmetric(
          EntityKind.WORK,
          a.workId,
          a.doi.map("doi" -> _).toMap,
          b.workId,
          b.doi.map("doi" -> _).toMap,
          key,
          workFeatures(a, b),
          run
        )
        if seen.add(pairId(cand))
      yield cand
    BlockResult(cands, None, ws.size, fallback)

  private def workFallbackBlocks(
      key: String,
      ws: Vector[WorkSummary],
      maxBlock: Int,
      run: RunContext
  ): Vector[BlockResult] =
    val venue = ws.flatMap(w =>
      w.venueId.orElse(w.venueName.map(_.toLowerCase)).filter(_.nonEmpty).map(v => s"v:$v" -> w)
    )
    val author = ws.flatMap(w => w.authorKeys.filter(_.nonEmpty).map(a => s"a:$a" -> w))
    val groups = (venue ++ author)
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .map { (k, vs) =>
        k -> vs.map(_._2).distinctBy(_.workId)
      }
      .filter(_._2.size >= 2)
    val seen = scala.collection.mutable.Set.empty[(String, String)]
    groups.map { (k, g) =>
      val blockKey = s"$key|$k"
      if g.size > maxBlock then
        BlockResult(
          Vector.empty,
          Some(s"fallback work block $blockKey has ${g.size} works > $maxBlock"),
          g.size,
          true
        )
      else compareWorks(blockKey, g, run, seen, true)
    }

  def workFeatures(a: WorkSummary, b: WorkSummary): Map[String, Double] =
    def flag(c: Boolean) = if c then 1.0 else 0.0
    val bothDoi = a.doi.nonEmpty && b.doi.nonEmpty
    Map(
      "title_jaccard" -> Similarity
        .jaccard(Similarity.tokens(a.normalizedTitle), Similarity.tokens(b.normalizedTitle)),
      "same_title" -> flag(a.normalizedTitle == b.normalizedTitle),
      "year_distance" -> ExactLogic.yearDistance(a.year, b.year),
      "same_venue_name" -> flag(
        a.venueName.nonEmpty && a.venueName.map(_.toLowerCase) == b.venueName.map(_.toLowerCase)
      ),
      "doi_equal" -> flag(bothDoi && a.doi == b.doi),
      "doi_conflict" -> flag(bothDoi && a.doi != b.doi),
      "author_overlap" -> Similarity.jaccard(a.authorKeys.toSet, b.authorKeys.toSet),
      "cross_source" -> flag(a.source != b.source),
      "same_source_duplicate" -> flag(a.source == b.source)
    )
end BlockLogic
