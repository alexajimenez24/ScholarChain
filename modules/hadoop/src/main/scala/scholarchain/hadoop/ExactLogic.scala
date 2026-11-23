package scholarchain.hadoop

import scholarchain.core.*
import upickle.default.ReadWriter

/** Exact-identifier keys and the join of records that share one. Keys are `doi:<doi>`,
  * `arxiv:<id>`, `openalex:<W id>` for works (and mentions under a DOI), `orcid:<iD>` for mentions
  * and profiles, and `doi:<doi>` again for the works an ORCID profile lists.
  *
  * The join emits candidates with their features, never a final decision. The same pair can appear
  * under two keys (a DOI and an arXiv ID); the candidate ID is the same, so a consumer
  * deduplicates.
  */
object ExactLogic:
  /** A compact reference grouped under one exact identifier key. */
  final case class IdRef(
      kind: String, // work | mention | profile
      id: String,
      source: SourceType,
      name: String,
      title: String,
      year: Option[Int],
      workId: Option[String]
  ) derives ReadWriter

  def workKeys(work: WorkRecord): Vector[String] =
    work.doi.toVector.map("doi:" + _) ++ work.arxivId.toVector.map("arxiv:" + _) ++
      work.openAlexId.toVector.map("openalex:" + _)

  def exactKeys(work: WorkRecord): Vector[(String, IdRef)] =
    val workRef =
      IdRef(
        "work",
        work.workId,
        work.source,
        "",
        work.normalizedTitle,
        work.year,
        Some(work.workId)
      )
    val mentionRefs = work.authors.map(m =>
      IdRef("mention", m.mentionId, work.source, m.normalizedName, "", work.year, Some(work.workId))
    )
    val doiMentions = work.doi.toVector.flatMap(d => mentionRefs.map(r => s"doi:$d" -> r))
    val orcidKeys =
      work.authors.zip(mentionRefs).flatMap((m, r) => m.orcid.map(o => s"orcid:$o" -> r))
    workKeys(work).map(_ -> workRef) ++ doiMentions ++ orcidKeys

  def exactKeys(profile: ExternalResearcher): Vector[(String, IdRef)] =
    val ref = IdRef(
      "profile",
      profile.recordId,
      profile.source,
      Normalize.personName(profile.displayName),
      "",
      None,
      None
    )
    profile.workDois.map(d => s"doi:$d" -> ref) ++ profile.orcid.toVector.map(o =>
      s"orcid:$o" -> ref
    )

  final case class ExactResult(
      candidates: Vector[MatchCandidate],
      conflicts: Int,
      duplicates: Int,
      oversize: Option[String] = None
  )

  /** Two works under one identifier whose titles share fewer than half their tokens. */
  val TitleConflictBelow = 0.5

  /** A mention whose name is this far from the profile that owns its ORCID. */
  val OrcidNameConflictBelow = 0.7

  /** Minimum name similarity for a profile to be linked to the best mention under a shared DOI. */
  val ProfileNameAtLeast = 0.85

  /** Collapses references that share an ID, independent of arrival order: the kept copy is the
    * smallest by its encoded JSON. Returns the kept references sorted by ID, the number of copies
    * beyond the first per ID, and the number of IDs whose copies differ.
    */
  def consolidate(refs: Vector[IdRef]): (Vector[IdRef], Int, Int) =
    val groups = refs.groupBy(_.id).toVector.sortBy(_._1)
    val kept = groups.map((_, g) => g.minBy(r => JsonLines.encode(r)))
    val extra = groups.map((_, g) => g.size - 1).sum
    val differing = groups.count((_, g) => g.map(r => JsonLines.encode(r)).distinct.size > 1)
    (kept, extra, differing)

  def exactJoin(
      key: String,
      refs: Vector[IdRef],
      run: RunContext,
      maxGroup: Int = Int.MaxValue
  ): ExactResult =
    val (works, repeatedWorks, differingWorks) = consolidate(refs.filter(_.kind == "work"))
    val (mentions, _, _) = consolidate(refs.filter(_.kind == "mention"))
    val (profiles, _, _) = consolidate(refs.filter(_.kind == "profile"))
    val duplicates = works.groupBy(_.source).values.count(_.size > 1) + repeatedWorks
    val featureName = key.takeWhile(_ != ':') match
      case "doi"      => "exact_doi"
      case "arxiv"    => "exact_arxiv"
      case "openalex" => "exact_openalex"
      case _          => "exact_id"
    val idKind = key.takeWhile(_ != ':')
    val oversize =
      Option.when(works.size > maxGroup)(
        s"exact key $key holds ${works.size} works > $maxGroup; work pairs not expanded"
      )
    val workPairs =
      if oversize.nonEmpty then Vector.empty
      else
        for
          (a, i) <- works.zipWithIndex
          b <- works.drop(i + 1)
        yield (a, b)
    val workCandidates = workPairs.map { (a, b) =>
      val f = Map(
        featureName -> 1.0,
        "same_source_duplicate" -> (if a.source == b.source then 1.0 else 0.0),
        "title_jaccard" -> Similarity
          .jaccard(Similarity.tokens(a.title), Similarity.tokens(b.title)),
        "year_distance" -> yearDistance(a.year, b.year)
      )
      MatchCandidate.symmetric(
        EntityKind.WORK,
        a.id,
        Map(idKind -> key.stripPrefix(idKind + ":")),
        b.id,
        Map(idKind -> key.stripPrefix(idKind + ":")),
        key,
        f,
        run
      )
    }
    val titleConflicts = workPairs.count((a, b) =>
      a.title != b.title &&
        Similarity.jaccard(Similarity.tokens(a.title), Similarity.tokens(b.title)) <
        TitleConflictBelow
    )
    val (researcherCandidates, nameConflicts) =
      if key.startsWith("orcid:") then orcidLinks(key, mentions, profiles, run)
      else (profileLinks(key, mentions, profiles, run), 0)
    ExactResult(
      workCandidates ++ researcherCandidates,
      titleConflicts + nameConflicts + differingWorks,
      duplicates,
      oversize
    )

  /** Every mention carrying an ORCID links to the canonical `orcid:<iD>` researcher. */
  private def orcidLinks(
      key: String,
      mentions: Vector[IdRef],
      profiles: Vector[IdRef],
      run: RunContext
  ): (Vector[MatchCandidate], Int) =
    val orcidId = key
    val profileName = profiles.headOption.map(_.name)
    val scored = mentions.map { m =>
      m -> profileName.fold(1.0)(Similarity.jaroWinkler(_, m.name))
    }
    val candidates = scored.map { (m, jw) =>
      MatchCandidate.make(
        EntityKind.RESEARCHER,
        m.id,
        Map.empty,
        orcidId,
        Map("orcid" -> key.stripPrefix("orcid:")),
        key,
        Map("exact_orcid" -> 1.0, "name_jw" -> jw),
        run
      )
    }
    candidates -> scored.count((_, jw) => jw < OrcidNameConflictBelow)

  /** A profile that lists a DOI links to the best-named mention of that DOI's works. Mentions are
    * sorted by ID first, so a tie in name similarity always picks the same mention.
    */
  private def profileLinks(
      key: String,
      mentions: Vector[IdRef],
      profiles: Vector[IdRef],
      run: RunContext
  ): Vector[MatchCandidate] =
    if !key.startsWith("doi:") then Vector.empty
    else
      for
        p <- profiles
        best <- mentions
          .map(m => m -> Similarity.jaroWinkler(p.name, m.name))
          .foldLeft(Option.empty[(IdRef, Double)]) {
            case (None, c)                   => Some(c)
            case (Some(b), c) if c._2 > b._2 => Some(c)
            case (keep, _)                   => keep
          }
          .toVector
        if best._2 >= ProfileNameAtLeast && Similarity.initialsCompatible(p.name, best._1.name)
      yield
        val (m, jw) = best
        MatchCandidate.make(
          EntityKind.RESEARCHER,
          m.id,
          Map.empty,
          p.id,
          Map("orcid" -> p.id.stripPrefix("orcid:")),
          key,
          Map("profile_lists_work_doi" -> 1.0, "name_jw" -> jw, "initials_compatible" -> 1.0),
          run
        )

  def yearDistance(a: Option[Int], b: Option[Int]): Double = (a, b) match
    case (Some(x), Some(y)) => math.abs(x - y).toDouble
    case _                  => 99.0
end ExactLogic
