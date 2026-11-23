package scholarchain.er

import upickle.default.ReadWriter

/** Identifier schemes of the exact-identifier rung of the evidence hierarchy. */
enum IdScheme derives ReadWriter:
  case DOI, ARXIV, ORCID, OPENALEX, S2, REPO

/** What two sides say about one scheme. Both flags can hold at once when a side carries several
  * values (for example a cluster with two ORCIDs): that is a contradiction, never a match.
  */
final case class Agreement(equal: Boolean, disagree: Boolean):
  def absent: Boolean = !equal && !disagree

object Agreement:
  val Absent: Agreement = Agreement(false, false)

/** Normalized identifiers of one side, per scheme. Values are already canonical (see
  * [[ErNormalize]]), so equality is string equality.
  */
final case class IdSet(values: Map[IdScheme, Set[String]]):
  def apply(s: IdScheme): Set[String] = values.getOrElse(s, Set.empty)
  def ++(o: IdSet): IdSet =
    IdSet((values.keySet ++ o.values.keySet).map(k => k -> (apply(k) ++ o(k))).toMap)
  def isEmpty: Boolean = values.values.forall(_.isEmpty)

object IdSet:
  val Empty: IdSet = IdSet(Map.empty)
  def of(pairs: (IdScheme, Option[String])*): IdSet =
    IdSet(
      pairs
        .collect { case (s, Some(v)) => s -> v }
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap
    )

/** Exact identifier rules: extraction from records and candidate identifiers, and comparison. */
object ExactIds:

  /** Every identifier a free-form string can be read as (a URL can be a DOI, arXiv, Semantic
    * Scholar, OpenAlex or repository link).
    */
  def fromWorkString(raw: String): IdSet =
    IdSet.of(
      IdScheme.ARXIV -> arxivOf(raw),
      IdScheme.DOI -> ErNormalize.doi(raw).filterNot(_.startsWith(ArxivDoiPrefix)),
      IdScheme.OPENALEX -> ErNormalize.openAlex(raw, 'W'),
      IdScheme.S2 -> ErNormalize.semanticScholarPaper(raw),
      IdScheme.REPO -> ErNormalize.repositoryUrl(raw)
    )

  /** arXiv-minted DOIs name arXiv records, so they are read as arXiv IDs, not as DOIs. */
  val ArxivDoiPrefix = "10.48550/"

  /** An arXiv ID written directly or as an arXiv DOI (bare or as a resolver URL). */
  def arxivOf(raw: String): Option[String] =
    ErNormalize.arxiv(raw).orElse(ErNormalize.doi(raw).flatMap(ErNormalize.arxiv))

  /** Identifiers of a work: its typed fields, its work ID namespace and its URLs. */
  def ofWork(w: WorkProfile): IdSet =
    val fields = IdSet.of(
      IdScheme.DOI -> w.doi.flatMap(ErNormalize.doi).filterNot(_.startsWith(ArxivDoiPrefix)),
      IdScheme.ARXIV -> w.doi.flatMap(arxivOf),
      IdScheme.ARXIV -> w.arxivId.flatMap(ErNormalize.arxiv),
      IdScheme.OPENALEX -> w.openAlexId.flatMap(ErNormalize.openAlex(_, 'W'))
    )
    val fromId = w.workId.split(":", 2) match
      case Array("crossref", rest) => IdSet.of(IdScheme.DOI -> ErNormalize.doi(rest))
      case Array("openalex", rest) => IdSet.of(IdScheme.OPENALEX -> ErNormalize.openAlex(rest, 'W'))
      case Array("arxiv", rest)    => IdSet.of(IdScheme.ARXIV -> ErNormalize.arxiv(rest))
      case _                       => IdSet.Empty
    w.urls.map(fromWorkString).foldLeft(fields ++ fromId)(_ ++ _)

  /** Identifiers of one author mention. */
  def ofMention(m: MentionProfile): IdSet =
    IdSet.of(
      IdScheme.ORCID -> m.orcid.flatMap(ErNormalize.orcid),
      IdScheme.OPENALEX -> m.externalAuthorId.flatMap(ErNormalize.openAlex(_, 'A')),
      IdScheme.S2 -> m.externalAuthorId.flatMap(ErNormalize.semanticScholarAuthor)
    )

  /** Identifiers implied by a researcher endpoint ID itself (`orcid:<iD>`, `openalex:A<n>`). */
  def ofResearcherId(id: String): IdSet =
    IdSet.of(
      IdScheme.ORCID -> Option.when(id.startsWith("orcid:"))(id).flatMap(ErNormalize.orcid),
      IdScheme.OPENALEX -> Option
        .when(id.startsWith("openalex:"))(id)
        .flatMap(ErNormalize.openAlex(_, 'A'))
    )

  /** Identifiers that Hadoop attached to a candidate side (`doi`, `arxiv`, `openalex`, `orcid`,
    * `s2`, `repo`), normalized with the entity-resolution rules. Unknown names are ignored.
    */
  def ofCandidateSide(kind: scholarchain.core.EntityKind, ids: Map[String, Vector[String]]): IdSet =
    val researcher = kind == scholarchain.core.EntityKind.RESEARCHER
    ids.toVector
      .flatMap { (name, vs) =>
        vs.map { v =>
          name match
            case "doi" if !researcher =>
              IdSet.of(
                IdScheme.DOI -> ErNormalize.doi(v).filterNot(_.startsWith(ArxivDoiPrefix)),
                IdScheme.ARXIV -> arxivOf(v)
              )
            case "arxiv" if !researcher => IdSet.of(IdScheme.ARXIV -> ErNormalize.arxiv(v))
            case "openalex"             =>
              IdSet.of(
                IdScheme.OPENALEX -> ErNormalize.openAlex(v, if researcher then 'A' else 'W')
              )
            case "orcid" if researcher => IdSet.of(IdScheme.ORCID -> ErNormalize.orcid(v))
            case "s2"                  =>
              IdSet.of(
                IdScheme.S2 ->
                  (if researcher then ErNormalize.semanticScholarAuthor(v)
                   else ErNormalize.semanticScholarPaper(v))
              )
            case "repo" if !researcher => IdSet.of(IdScheme.REPO -> ErNormalize.repositoryUrl(v))
            case _                     => IdSet.Empty
        }
      }
      .foldLeft(IdSet.Empty)(_ ++ _)

  /** Compares one scheme.
    *
    *   - equal: the sides share a value (every value counts);
    *   - disagree: within one comparable family, each side holds a value the other lacks.
    *
    * `ignoredForDisagreement` removes values whose disagreement is not evidence (preprint-server
    * DOIs: a preprint and its publication carry different DOIs). `family` groups values that can be
    * compared at all (Semantic Scholar `sha:` and `corpus:` IDs cannot be compared with each
    * other).
    */
  def compare(
      left: Set[String],
      right: Set[String],
      ignoredForDisagreement: String => Boolean = _ => false,
      family: String => String = _ => ""
  ): Agreement =
    val equal = left.intersect(right).nonEmpty
    val l = left.filterNot(ignoredForDisagreement).groupBy(family)
    val r = right.filterNot(ignoredForDisagreement).groupBy(family)
    val disagree = l.keySet.intersect(r.keySet).exists { f =>
      l(f).diff(right).nonEmpty && r(f).diff(left).nonEmpty
    }
    Agreement(equal, disagree)

  /** The comparable family of a value of `scheme`. */
  def familyOf(scheme: IdScheme)(value: String): String =
    if scheme == IdScheme.S2 then value.takeWhile(_ != ':') else ""
