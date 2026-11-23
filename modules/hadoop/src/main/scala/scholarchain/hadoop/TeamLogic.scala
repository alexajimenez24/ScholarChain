package scholarchain.hadoop

import scholarchain.core.*

/** Publication teams, author-paper records and coauthor pair expansion. Pure functions; the mappers
  * and reducers in [[Jobs]] only adapt I/O and counters.
  */
object TeamLogic:
  private def derived(work: WorkRecord, run: RunContext, schema: String): Provenance =
    run.provenance(work.source, work.sourceRecordId, schema)

  /** One team record with the ordered mentions, and one author-paper record per mention. A work
    * without authors has no team.
    */
  def team(work: WorkRecord, run: RunContext): Option[(PublicationTeam, Vector[AuthorPaper])] =
    Option.when(work.authors.nonEmpty) {
      val size = work.authors.size
      val team = PublicationTeam(
        work.workId,
        work.year,
        work.authors.sortBy(_.position).map(_.mentionId),
        size,
        derived(work, run, SchemaVersions.Team)
      )
      val papers = work.authors.map(m =>
        AuthorPaper(
          m.mentionId,
          work.workId,
          m.position,
          m.normalizedName,
          m.orcid,
          work.year,
          size,
          derived(work, run, HadoopSchemas.AuthorPaper)
        )
      )
      team -> papers
    }

  /** Team-size histogram bucket, reported as a Hadoop counter. */
  def bucket(size: Int): String = size match
    case s if s <= 1   => "TEAM_SIZE_1"
    case s if s <= 5   => "TEAM_SIZE_2_5"
    case s if s <= 20  => "TEAM_SIZE_6_20"
    case s if s <= 100 => "TEAM_SIZE_21_100"
    case s if s <= 300 => "TEAM_SIZE_101_300"
    case _             => "TEAM_SIZE_OVER_300"

  /** n authors give n(n-1)/2 unordered pairs, each with work ID, year, both positions, team size
    * and the source provenance. A team above `maxTeam` is refused (Left) and counted by the caller,
    * so a 5000-author paper cannot expand to 12 million pairs unseen.
    */
  def pairs(
      team: PublicationTeam,
      maxTeam: Int,
      run: RunContext
  ): Either[String, Vector[CoauthorPairCandidate]] =
    if team.teamSize > maxTeam then
      Left(s"${team.workId}: team of ${team.teamSize} exceeds pair cap $maxTeam")
    else
      val prov = team.provenance.copy(
        schemaVersion = SchemaVersions.Pair,
        snapshot = run.snapshot,
        runId = run.runId
      )
      val ms = team.mentionIds.zipWithIndex
      Right(
        for
          (a, i) <- ms
          (b, j) <- ms.drop(i + 1)
        yield
          val (x, px, y, py) = if a < b then (a, i, b, j) else (b, j, a, i)
          CoauthorPairCandidate(x, y, team.workId, team.year, px, py, team.teamSize, prov)
      )

  def pairCount(n: Long): Long = n * (n - 1) / 2

  /** Sort key of a pair record in the J06 shuffle: the pair identity, unique per work. */
  def pairKey(p: CoauthorPairCandidate): String = s"${p.mentionA}|${p.mentionB}|${p.workId}"
end TeamLogic
