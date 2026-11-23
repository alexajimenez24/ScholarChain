package scholarchain.hadoop

import scholarchain.core.*

/** Graph export preparation (J07): the partitioned files Spark reads.
  *
  * Every record is keyed `<partition key> TAB <kind> TAB <id>`. The partition key is the work ID
  * for everything that belongs to a work (the work, its mentions, team, author-paper records and
  * pair candidates), so one work's records always land in the same partition number; topics,
  * institutions and venues partition by their own ID. The reducer writes one file family per kind:
  * `works/`, `mentions/`, `teams/`, `pairs/`, `authorpapers/`, `topics/`, `institutions/` and
  * `venues/`, each `part-r-NNNNN`.
  */
object ExportLogic:
  val Works = "works"
  val Mentions = "mentions"
  val Teams = "teams"
  val Pairs = "pairs"
  val AuthorPapers = "authorpapers"
  val Topics = "topics"
  val Institutions = "institutions"
  val Venues = "venues"
  val Kinds: Vector[String] =
    Vector(Works, Mentions, Teams, Pairs, AuthorPapers, Topics, Institutions, Venues)

  private def clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

  def key(partition: String, kind: String, id: String): String =
    s"${clean(partition)}\t$kind\t${clean(id)}"

  /** (kind, id) of a shuffle key. */
  def kindAndId(shuffleKey: String): Option[(String, String)] =
    shuffleKey.split("\t", 3) match
      case Array(_, kind, id) if Kinds.contains(kind) => Some((kind, id))
      case _                                          => None

  def venueOf(work: WorkRecord, run: RunContext): Option[Venue] =
    work.venueId.map(id =>
      Venue(
        id,
        work.venueName.getOrElse(id),
        DblpParser.venueKind(id),
        run.provenance(work.source, id, SchemaVersions.Venue)
      )
    )

  /** The shuffle rows of one work: the work, one row per mention, and its venue. */
  def workRows(work: WorkRecord, run: RunContext): Vector[(String, String)] =
    val wid = work.workId
    val venue =
      venueOf(work, run).toVector.map(v => key(v.venueId, Venues, v.venueId) -> JsonLines.encode(v))
    val mentions = work.authors.map(m => key(wid, Mentions, m.mentionId) -> JsonLines.encode(m))
    (key(wid, Works, wid) -> JsonLines.encode(work)) +: (mentions ++ venue)
end ExportLogic
