package scholarchain.er

import scholarchain.core.*

/** The features of one candidate pair, plus the per-scheme identifier comparison behind the
  * `<scheme>_equal` and `<scheme>_disagree` features. Every value is finite.
  */
final case class FeatureSet(values: Map[String, Double], ids: Map[IdScheme, Agreement]):
  def apply(name: String): Double = values.getOrElse(name, 0.0)
  def flag(name: String): Boolean = apply(name) >= 1.0
  def agreement(s: IdScheme): Agreement = ids.getOrElse(s, Agreement.Absent)

/** Feature functions, version `er-features-v1`. Each feature is computed from the two endpoint
  * profiles (records of the same pipeline run) with the [[ErNormalize]] rules. Where the Hadoop
  * stage measured the same evidence over a whole source cluster (a J04 candidate links cluster
  * representatives), the upstream value is combined as documented per feature: overlaps take the
  * larger value, gaps the smaller, flags are OR-ed. Absence is never agreement: a `*_known` flag
  * says whether the evidence existed.
  */
object Features:
  val Version = "er-features-v1"

  // Researcher feature names.
  val NameSimilarity = "name_similarity"
  val SameFullName = "same_full_name"
  val InitialsCompatible = "initials_compatible"
  val NameOrderSwapped = "name_order_swapped"
  val NamesKnown = "names_known"
  val AuthorPositionAgree = "author_position_agree"
  val AuthorPositionKnown = "author_position_known"
  val CoauthorOverlap = "coauthor_overlap"
  val AffiliationOverlap = "affiliation_overlap"
  val AffiliationKnown = "affiliation_known"
  val AffiliationDisjoint = "affiliation_disjoint"
  val TopicOverlap = "topic_overlap"
  val VenueOverlap = "venue_overlap"
  val YearGap = "year_gap"
  val SharedWork = "shared_work"
  val DblpHomonymDistinct = "dblp_homonym_distinct"
  val SameSourceCluster = "same_source_cluster"
  // Work feature names.
  val TitleSimilarity = "title_similarity"
  val SameNormalizedTitle = "same_normalized_title"
  val TitleTokens = "title_tokens"
  val AuthorOverlap = "author_overlap"
  val AuthorSequence = "author_sequence_similarity"
  val AuthorsKnown = "authors_known"
  val YearDistance = "year_distance"
  val VenueCompatible = "venue_compatible"
  val VenueKnown = "venue_known"
  val SameSource = "same_source"
  // Shared.
  val CitationOverlap = "citation_overlap"
  val CitationKnown = "citation_known"
  val SourceReliability = "source_reliability"
  val CrossSource = "cross_source"
  val ProfilesFound = "profiles_found"

  /** Year distance or gap when a year is unknown on either side. */
  val UnknownYears = 99.0

  def equalName(s: IdScheme): String = s"${s.toString.toLowerCase}_equal"
  def disagreeName(s: IdScheme): String = s"${s.toString.toLowerCase}_disagree"

  val ResearcherSchemes: Vector[IdScheme] = Vector(IdScheme.ORCID, IdScheme.OPENALEX, IdScheme.S2)
  val WorkSchemes: Vector[IdScheme] =
    Vector(IdScheme.DOI, IdScheme.ARXIV, IdScheme.OPENALEX, IdScheme.S2, IdScheme.REPO)

  private def b(x: Boolean): Double = if x then 1.0 else 0.0
  private def up(c: MergedCandidate, name: String): Option[Double] = c.upstreamFeatures.get(name)
  private def upFlag(c: MergedCandidate, names: String*): Boolean =
    names.exists(n => up(c, n).exists(_ >= 1.0))

  // ------------------------------------------------------------------ names

  /** Two parsed names agree on initials: same family name, same first initial, and the remaining
    * initials of the shorter one appear in order in the longer one (a missing middle name is fine,
    * a different one is not). A side without given names is compatible on initials.
    */
  def initialsCompatible(a: PersonName, b: PersonName): Boolean =
    a.family.nonEmpty && a.family == b.family && {
      val (x, y) = (a.initials, b.initials)
      if x.isEmpty || y.isEmpty then true
      else
        val (short, long) = if x.size <= y.size then (x, y) else (y, x)
        short.head == long.head && TextSimilarity.lcs(short.tail, long.tail) == short.tail.size
    }

  final case class NameComparison(
      similarity: Double,
      sameFull: Boolean,
      initials: Boolean,
      swapped: Boolean
  )

  /** Best agreement over every pair of name forms, including the family-first reading of two-token
    * names (Wang Wei / Wei Wang).
    */
  def compareNames(left: Vector[PersonName], right: Vector[PersonName]): Option[NameComparison] =
    val ls = left.filter(_.full.nonEmpty).distinct
    val rs = right.filter(_.full.nonEmpty).distinct
    Option.when(ls.nonEmpty && rs.nonEmpty) {
      val direct = for l <- ls; r <- rs yield (l, r, false)
      val swapped = for l <- ls; r <- rs; s <- r.swapped.toVector yield (l, s, true)
      val scored = (direct ++ swapped).map((l, r, sw) =>
        (TextSimilarity.jaroWinkler(l.full, r.full), l.full == r.full, initialsCompatible(l, r), sw)
      )
      val best = scored.maxBy((s, same, ini, sw) => (s, same, ini, !sw))
      NameComparison(
        best._1,
        scored.exists(_._2),
        scored.exists(_._3),
        best._4 && !scored.exists((s, _, _, sw) => !sw && s >= best._1)
      )
    }

  // ------------------------------------------------------------------ researcher

  private def role(m: MentionProfile): Option[String] =
    if m.teamSize < 2 then None
    else if m.position == 0 then Some("first")
    else if m.position == m.teamSize - 1 then Some("last")
    else Some("middle")

  private def yearRange(ms: Vector[MentionProfile]): Option[(Int, Int)] =
    val ys = ms.flatMap(_.year)
    Option.when(ys.nonEmpty)((ys.min, ys.max))

  private def sideReliability(p: EndpointProfile, policy: PolicyConfig): Double =
    val sources = p.work.map(_.source).toVector ++ p.mentions.map(_.source)
    if sources.isEmpty then policy.reliability(SourceType.SCHOLARCHAIN)
    else sources.map(policy.reliability).max

  private def researcherIds(
      c: MergedCandidate,
      p: EndpointProfile,
      candidateIds: Map[String, Vector[String]]
  ): IdSet =
    p.mentions.map(ExactIds.ofMention).foldLeft(ExactIds.ofResearcherId(p.endpointId))(_ ++ _) ++
      ExactIds.ofCandidateSide(c.kind, candidateIds)

  def researcher(
      c: MergedCandidate,
      left0: EndpointProfile,
      right0: EndpointProfile,
      policy: PolicyConfig
  ): FeatureSet =
    // Leave one out: a side never holds the other endpoint's own mention.
    val left = Profiles.without(left0, Set(c.targetId))
    val right = Profiles.without(right0, Set(c.sourceId))
    val (lm, rm) = (left.mentions, right.mentions)
    val lNames = lm.map(m => ErNormalize.personName(m.rawName))
    val rNames = rm.map(m => ErNormalize.personName(m.rawName))
    val names = compareNames(lNames, rNames)
    val upJw = up(c, "name_jw")

    val ownKeys = (lNames ++ rNames).map(_.key).toSet
    def coauthorKeys(ms: Vector[MentionProfile]) =
      ms.flatMap(_.coauthorNames).map(ErNormalize.personName(_).key).toSet -- ownKeys
    val coauthor = math.max(
      TextSimilarity.jaccard(coauthorKeys(lm), coauthorKeys(rm)),
      up(c, "coauthor_overlap").getOrElse(0.0)
    )

    val (la, ra) = (lm.flatMap(_.affiliation).toSet, rm.flatMap(_.affiliation).toSet)
    val affKnown = (la.nonEmpty && ra.nonEmpty) || upFlag(c, "affiliation_known")
    val affOverlap =
      math.max(TextSimilarity.jaccard(la, ra), up(c, "affiliation_overlap").getOrElse(0.0))

    def tokens(ms: Vector[MentionProfile]) = ms.flatMap(m => ErNormalize.titleTokens(m.title)).toSet
    val topic = math.max(
      TextSimilarity.jaccard(tokens(lm), tokens(rm)),
      up(c, "title_token_overlap").getOrElse(0.0)
    )
    val venue = math.max(
      TextSimilarity.jaccard(lm.flatMap(_.venueKey).toSet, rm.flatMap(_.venueKey).toSet),
      up(c, "venue_overlap").getOrElse(0.0)
    )
    val profileGap = (yearRange(lm), yearRange(rm)) match
      case (Some((a0, a1)), Some((b0, b1))) =>
        math.max(0, math.max(a0, b0) - math.min(a1, b1)).toDouble
      case _ => UnknownYears
    val gap = math.min(profileGap, up(c, "year_gap").getOrElse(UnknownYears))

    val positions = (lm, rm) match
      case (Vector(x), Vector(y)) => role(x).zip(role(y))
      case _                      => None
    val positionAgree = positions.exists((x, y) => x == y && x != "middle")

    val sharedWork = lm.map(_.workId).toSet.intersect(rm.map(_.workId).toSet).nonEmpty ||
      upFlag(c, "shared_work")
    val homonym = upFlag(c, "dblp_homonym_distinct") || {
      val dl = lm.filter(_.source == SourceType.DBLP).map(_.rawName.trim)
      val dr = rm.filter(_.source == SourceType.DBLP).map(_.rawName.trim)
      dl.exists(x =>
        dr.exists(y =>
          x != y && ErNormalize.fold(ErNormalize.stripDblpHomonym(x)) ==
            ErNormalize.fold(ErNormalize.stripDblpHomonym(y))
        )
      )
    }
    val (lr, rr) = (lm.flatMap(_.references).toSet, rm.flatMap(_.references).toSet)
    val citationKnown = lr.nonEmpty && rr.nonEmpty

    val lIds = researcherIds(c, left, c.sourceIdentifiers)
    val rIds = researcherIds(c, right, c.targetIdentifiers)
    val ids = ResearcherSchemes.map { s =>
      val er = ExactIds.compare(lIds(s), rIds(s), family = ExactIds.familyOf(s))
      val upstream = s match
        case IdScheme.ORCID =>
          Agreement(
            upFlag(c, "orcid_equal", "exact_orcid", "profile_lists_work_doi"),
            upFlag(c, "orcid_conflict")
          )
        case IdScheme.OPENALEX =>
          Agreement(upFlag(c, "external_id_equal"), upFlag(c, "external_id_conflict"))
        case _ => Agreement.Absent
      s -> Agreement(er.equal || upstream.equal, er.disagree || upstream.disagree)
    }.toMap

    val lSources = (left.mentions.map(_.source)).toSet
    val rSources = (right.mentions.map(_.source)).toSet
    val values = Map(
      NameSimilarity -> names.map(_.similarity).orElse(upJw).getOrElse(0.0),
      SameFullName -> b(names.exists(_.sameFull) || upFlag(c, "same_full_name")),
      InitialsCompatible -> b(
        names.map(_.initials).getOrElse(up(c, "initials_compatible").forall(_ >= 1.0))
      ),
      NameOrderSwapped -> b(names.exists(_.swapped)),
      NamesKnown -> b(names.nonEmpty || upJw.nonEmpty),
      AuthorPositionAgree -> b(positionAgree),
      AuthorPositionKnown -> b(positions.nonEmpty),
      CoauthorOverlap -> coauthor,
      AffiliationOverlap -> affOverlap,
      AffiliationKnown -> b(affKnown),
      AffiliationDisjoint -> b(affKnown && affOverlap == 0.0),
      TopicOverlap -> topic,
      VenueOverlap -> venue,
      YearGap -> gap,
      SharedWork -> b(sharedWork),
      DblpHomonymDistinct -> b(homonym),
      SameSourceCluster -> b(upFlag(c, "same_source_cluster")),
      CitationOverlap -> (if citationKnown then TextSimilarity.jaccard(lr, rr) else 0.0),
      CitationKnown -> b(citationKnown),
      SourceReliability -> math
        .min(sideReliability(left0, policy), sideReliability(right0, policy)),
      CrossSource -> b(
        (lSources.nonEmpty && rSources.nonEmpty && lSources != rSources) || upFlag(
          c,
          "cross_source"
        )
      ),
      ProfilesFound -> b(left0.found && right0.found)
    ) ++ ids.toVector.flatMap((s, a) =>
      Vector(equalName(s) -> b(a.equal), disagreeName(s) -> b(a.disagree))
    )
    FeatureSet(values, ids)

  // ------------------------------------------------------------------ work

  private def workIds(
      c: MergedCandidate,
      w: Option[WorkProfile],
      ids: Map[String, Vector[String]]
  ) =
    w.map(ExactIds.ofWork).getOrElse(IdSet.Empty) ++ ExactIds.ofCandidateSide(c.kind, ids)

  def work(
      c: MergedCandidate,
      left: EndpointProfile,
      right: EndpointProfile,
      policy: PolicyConfig
  ): FeatureSet =
    val (lw, rw) = (left.work, right.work)
    val both = lw.zip(rw)
    val titles = both.map((a, b) => (ErNormalize.title(a.title), ErNormalize.title(b.title)))
    val titleSim = titles
      .map((a, b) => TextSimilarity.trigramDice(a, b))
      .getOrElse(up(c, "title_jaccard").getOrElse(0.0))
    val sameTitle =
      titles.exists((a, b) => a.nonEmpty && a == b) || (both.isEmpty && upFlag(c, "same_title"))
    val titleTokens = both
      .map((a, b) =>
        math.min(ErNormalize.titleTokens(a.title).size, ErNormalize.titleTokens(b.title).size)
      )
      .getOrElse(0)

    def keys(w: WorkProfile) = w.authorNames.map(ErNormalize.personName(_).key).filter(_.nonEmpty)
    val authorKeys = both.map((a, b) => (keys(a), keys(b)))
    val authorsKnown = authorKeys.exists((a, b) => a.nonEmpty && b.nonEmpty)
    val authorOverlap = math.max(
      authorKeys.map((a, b) => TextSimilarity.jaccard(a.toSet, b.toSet)).getOrElse(0.0),
      up(c, "author_overlap").getOrElse(0.0)
    )
    val authorSeq = authorKeys.map((a, b) => TextSimilarity.sequenceSimilarity(a, b)).getOrElse(0.0)

    val yearDistance = both match
      case Some((a, b)) =>
        a.year.zip(b.year).map((x, y) => math.abs(x - y).toDouble).getOrElse(UnknownYears)
      case None => up(c, "year_distance").getOrElse(UnknownYears)

    val venue = both.map { (a, b) =>
      val sameId = a.venueId.exists(v => v.nonEmpty && b.venueId.contains(v))
      val names = a.venueName.map(ErNormalize.text).zip(b.venueName.map(ErNormalize.text))
      val known = sameId || names.exists((x, y) => x.nonEmpty && y.nonEmpty) ||
        a.venueId.nonEmpty && b.venueId.nonEmpty
      val score =
        if sameId then 1.0 else names.map((x, y) => TextSimilarity.trigramDice(x, y)).getOrElse(0.0)
      (known, score)
    }
    val venueKnown = venue.exists(_._1) || upFlag(c, "same_venue_name")
    val venueScore =
      math.max(venue.map(_._2).getOrElse(0.0), up(c, "same_venue_name").getOrElse(0.0))

    val lIds = workIds(c, lw, c.sourceIdentifiers)
    val rIds = workIds(c, rw, c.targetIdentifiers)
    val ids = WorkSchemes.map { s =>
      val ignore: String => Boolean = if s == IdScheme.DOI then policy.isPreprintDoi else _ => false
      val er = ExactIds.compare(lIds(s), rIds(s), ignore, ExactIds.familyOf(s))
      // Upstream identifier flags are used only when a profile is missing on a side.
      val upstream =
        if both.nonEmpty then Agreement.Absent
        else
          s match
            case IdScheme.DOI =>
              Agreement(upFlag(c, "exact_doi", "doi_equal"), upFlag(c, "doi_conflict"))
            case IdScheme.ARXIV    => Agreement(upFlag(c, "exact_arxiv"), false)
            case IdScheme.OPENALEX => Agreement(upFlag(c, "exact_openalex"), false)
            case _                 => Agreement.Absent
      s -> Agreement(er.equal || upstream.equal, er.disagree || upstream.disagree)
    }.toMap

    val sameSource = both match
      case Some((a, b)) => a.source == b.source
      case None         => upFlag(c, "same_source_duplicate")
    val (lr, rr) =
      (
        lw.map(_.references.toSet).getOrElse(Set.empty),
        rw.map(_.references.toSet).getOrElse(Set.empty)
      )
    val citationKnown = lr.nonEmpty && rr.nonEmpty
    val values = Map(
      TitleSimilarity -> titleSim,
      SameNormalizedTitle -> b(sameTitle),
      TitleTokens -> titleTokens.toDouble,
      AuthorOverlap -> authorOverlap,
      AuthorSequence -> authorSeq,
      AuthorsKnown -> b(authorsKnown),
      YearDistance -> yearDistance,
      VenueCompatible -> venueScore,
      VenueKnown -> b(venueKnown),
      SameSource -> b(sameSource),
      CitationOverlap -> (if citationKnown then TextSimilarity.jaccard(lr, rr) else 0.0),
      CitationKnown -> b(citationKnown),
      SourceReliability -> math.min(sideReliability(left, policy), sideReliability(right, policy)),
      CrossSource -> b(both.exists((a, b) => a.source != b.source) || upFlag(c, "cross_source")),
      ProfilesFound -> b(left.found && right.found)
    ) ++ ids.toVector.flatMap((s, a) =>
      Vector(equalName(s) -> b(a.equal), disagreeName(s) -> b(a.disagree))
    )
    FeatureSet(values, ids)

  def of(
      c: MergedCandidate,
      left: EndpointProfile,
      right: EndpointProfile,
      p: PolicyConfig
  ): FeatureSet =
    c.kind match
      case EntityKind.RESEARCHER => researcher(c, left, right, p)
      case EntityKind.WORK       => work(c, left, right, p)
