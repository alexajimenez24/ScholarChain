package scholarchain.er

import scholarchain.core.*
import upickle.default.ReadWriter

/** What entity resolution knows about one author mention, built from its work record. Read-only: a
  * profile is derived from source records and never written back to them.
  */
final case class MentionProfile(
    mentionId: String,
    workId: String,
    source: SourceType,
    position: Int,
    teamSize: Int,
    rawName: String,
    orcid: Option[String],
    externalAuthorId: Option[String],
    affiliation: Option[String],
    coauthorNames: Vector[String],
    title: String,
    year: Option[Int],
    venueKey: Option[String],
    references: Vector[String]
) derives ReadWriter

/** What entity resolution knows about one work. */
final case class WorkProfile(
    workId: String,
    source: SourceType,
    title: String,
    year: Option[Int],
    venueId: Option[String],
    venueName: Option[String],
    doi: Option[String],
    arxivId: Option[String],
    openAlexId: Option[String],
    urls: Vector[String],
    authorNames: Vector[String],
    institutionIds: Vector[String],
    references: Vector[String]
) derives ReadWriter

/** One candidate endpoint: a work, or one or more mentions (a mention ID has one; an `orcid:`
  * researcher has every mention that carries the ORCID, up to a cap). `truncated` says the cap was
  * hit; `found` is false when the run's export holds no record for the endpoint.
  */
final case class EndpointProfile(
    endpointId: String,
    kind: EntityKind,
    work: Option[WorkProfile],
    mentions: Vector[MentionProfile],
    truncated: Boolean
) derives ReadWriter:
  def found: Boolean = work.nonEmpty || mentions.nonEmpty

object EndpointProfile:
  def missing(id: String, kind: EntityKind): EndpointProfile =
    EndpointProfile(id, kind, None, Vector.empty, truncated = false)

/** Profile construction caps. They come from the policy configuration. */
final case class ProfileLimits(
    maxAuthorsPerWork: Int,
    maxCoauthorsPerMention: Int,
    maxUrlsPerWork: Int,
    maxMentionsPerEndpoint: Int
) derives ReadWriter

object Profiles:

  /** The venue key of a work: its venue ID, else its folded venue name. */
  def venueKey(w: WorkRecord): Option[String] =
    w.venueId
      .filter(_.trim.nonEmpty)
      .orElse(w.venueName.map(ErNormalize.text).filter(_.nonEmpty).map("name:" + _))

  def work(w: WorkRecord, l: ProfileLimits): WorkProfile =
    WorkProfile(
      w.workId,
      w.source,
      w.title,
      w.year,
      w.venueId,
      w.venueName,
      w.doi,
      w.arxivId,
      w.openAlexId,
      w.urls.take(l.maxUrlsPerWork),
      w.authors.sortBy(_.position).map(_.rawName).take(l.maxAuthorsPerWork),
      w.institutionIds,
      Vector.empty // WorkRecord (work-v1) carries no references: citation evidence is unavailable
    )

  def mentions(w: WorkRecord, l: ProfileLimits): Vector[MentionProfile] =
    val ordered = w.authors.sortBy(_.position)
    ordered.map { m =>
      MentionProfile(
        m.mentionId,
        w.workId,
        m.provenance.source,
        m.position,
        ordered.size,
        m.rawName,
        m.orcid,
        m.externalAuthorId,
        m.affiliation,
        ordered.filterNot(_.mentionId == m.mentionId).map(_.rawName).take(l.maxCoauthorsPerMention),
        w.title,
        w.year,
        venueKey(w),
        Vector.empty
      )
    }

  /** The researcher endpoint IDs a mention also stands for: `orcid:<iD>` when its ORCID is valid,
    * and its OpenAlex author ID when that is a researcher ID.
    */
  def researcherKeys(m: MentionProfile): Vector[String] =
    m.orcid.flatMap(Ids.researcherFromOrcid).toVector ++
      m.externalAuthorId
        .flatMap(ErNormalize.openAlex(_, 'A'))
        .map("openalex:" + _)
        .filter(Ids.isResearcherId)
        .toVector

  /** Adds a mention to a researcher endpoint, keeping the `max` smallest mention IDs so the result
    * does not depend on input order.
    */
  def addMention(p: EndpointProfile, m: MentionProfile, max: Int): EndpointProfile =
    if p.mentions.exists(_.mentionId == m.mentionId) then p
    else
      val all = (p.mentions :+ m).sortBy(_.mentionId)
      if all.size <= max then p.copy(mentions = all)
      else p.copy(mentions = all.take(max), truncated = true)

  /** Leave-one-out view: the counterpart's own mentions removed, so a mention is never evidence for
    * itself (an `orcid:` endpoint holds the very mention that is linked to it).
    */
  def without(p: EndpointProfile, mentionIds: Set[String]): EndpointProfile =
    p.copy(mentions = p.mentions.filterNot(m => mentionIds(m.mentionId)))
