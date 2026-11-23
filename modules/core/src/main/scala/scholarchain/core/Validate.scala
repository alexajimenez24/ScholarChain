package scholarchain.core

/** Contract validation. Every reader of a wire record runs the matching check and routes a failing
  * record to a reject output instead of guessing. Each function returns the list of violations; an
  * empty vector means the record is valid.
  */
object Validate:
  type Errors = Vector[String]

  /** Plausibility range for publication years, a contract constant (not a graph window). */
  val MinYear = 1900
  val MaxYear = 2100

  private def check(cond: Boolean, msg: => String): Errors =
    if cond then Vector.empty else Vector(msg)

  def confidence(value: Double, field: String = "confidence"): Errors =
    check(!value.isNaN && value >= 0.0 && value <= 1.0, s"$field $value outside [0, 1]")

  def window(w: TimeWindow): Errors =
    check(w.startYear <= w.endYear, s"window start ${w.startYear} exceeds end ${w.endYear}") ++
      check(
        w.startYear >= MinYear && w.endYear <= MaxYear,
        s"window ${w.label} outside $MinYear-$MaxYear"
      )

  private val SnapshotPattern = "^\\d{4}-\\d{2}-\\d{2}$".r

  /** A snapshot date `YYYY-MM-DD` that exists on the calendar. */
  def snapshotDate(s: String): Boolean =
    SnapshotPattern.matches(s) && scala.util.Try(java.time.LocalDate.parse(s)).isSuccess

  private def nonNegative(n: Long, what: String): Errors = check(n >= 0, s"$what $n is negative")
  private def finiteNonNegative(d: Double, what: String): Errors =
    check(!d.isNaN && !d.isInfinite && d >= 0.0, s"$what $d is not a finite non-negative number")

  def schemaVersion(version: String): Errors =
    check(SchemaVersions.Supported.contains(version), s"unsupported schema version '$version'")

  /** The version must be supported and be the one the record type is defined for. */
  def schemaVersion(version: String, expected: String): Errors =
    schemaVersion(version) ++
      check(
        !SchemaVersions.Supported.contains(version) || version == expected,
        s"schema version '$version' where '$expected' is required"
      )

  /** Provenance must name a source record, a snapshot, a well-formed run ID and the record's own
    * schema version.
    */
  def provenance(p: Provenance, expected: String): Errors =
    check(p.sourceRecordId.trim.nonEmpty, "provenance.sourceRecordId is empty") ++
      check(snapshotDate(p.snapshot), s"provenance.snapshot '${p.snapshot}' is not YYYY-MM-DD") ++
      check(Ids.isRunId(p.runId), s"provenance.runId '${p.runId}' is malformed") ++
      check(
        p.policyVersion.forall(_.trim.nonEmpty),
        "provenance.policyVersion is present but empty"
      ) ++
      schemaVersion(p.schemaVersion, expected)

  private def year(y: Option[Int], who: String): Errors =
    check(y.forall(v => v >= MinYear && v <= MaxYear), s"$who: implausible year $y")

  /** A mention ID is the hash of its work ID and position, so it is recomputed, not trusted. */
  def mention(m: AuthorMention): Errors =
    check(Ids.isMentionId(m.mentionId), s"malformed mention '${m.mentionId}'") ++
      check(Ids.isWorkId(m.workId), s"malformed work ID '${m.workId}'") ++
      check(m.position >= 0, s"mention ${m.mentionId}: negative position ${m.position}") ++
      check(
        m.position < 0 || m.mentionId == Ids.mention(m.workId, m.position),
        s"mention ${m.mentionId} does not match its work and position"
      ) ++
      check(m.rawName.trim.nonEmpty, s"mention ${m.mentionId}: empty name") ++
      provenance(m.provenance, SchemaVersions.Mention)

  def work(w: WorkRecord): Errors =
    schemaVersion(w.schemaVersion, SchemaVersions.Work) ++
      check(Ids.isWorkId(w.workId), s"malformed work ID '${w.workId}'") ++
      check(w.title.trim.nonEmpty, s"${w.workId}: empty title") ++
      year(w.year, w.workId) ++
      w.authors.flatMap(m =>
        mention(m) ++ check(m.workId == w.workId, s"mention ${m.mentionId} names another work")
      ) ++
      check(
        w.authors.map(_.mentionId).distinct.size == w.authors.size,
        s"${w.workId}: two authors share a mention ID"
      ) ++
      check(
        w.authors.map(_.position) == w.authors.indices.toVector,
        s"${w.workId}: author positions are not 0..n-1"
      ) ++ provenance(w.provenance, SchemaVersions.Work)

  def researcher(r: CanonicalResearcher): Errors =
    schemaVersion(r.schemaVersion, SchemaVersions.Researcher) ++
      check(Ids.isResearcherId(r.researcherId), s"malformed researcher '${r.researcherId}'") ++
      confidence(r.identityConfidence, "identityConfidence") ++
      check(r.mentionIds.nonEmpty, s"${r.researcherId}: no mentions") ++
      r.mentionIds.flatMap(m => check(Ids.isMentionId(m), s"malformed mention '$m'")) ++
      provenance(r.provenance, SchemaVersions.Researcher)

  def authorship(a: AuthorshipRecord): Errors =
    check(Ids.isResearcherId(a.researcherId), s"malformed researcher '${a.researcherId}'") ++
      check(Ids.isWorkId(a.workId), s"malformed work ID '${a.workId}'") ++
      check(Ids.isMentionId(a.mentionId), s"malformed mention '${a.mentionId}'") ++
      check(a.position >= 0 && a.position < a.teamSize, s"position ${a.position} outside team") ++
      year(a.year, a.workId) ++ provenance(a.provenance, SchemaVersions.Authorship)

  def team(t: PublicationTeam): Errors =
    check(Ids.isWorkId(t.workId), s"malformed work ID '${t.workId}'") ++
      check(t.teamSize == t.mentionIds.size, s"${t.workId}: teamSize differs from mention count") ++
      t.mentionIds.flatMap(m => check(Ids.isMentionId(m), s"malformed mention '$m'")) ++
      year(t.year, t.workId) ++ provenance(t.provenance, SchemaVersions.Team)

  def pair(p: CoauthorPairCandidate): Errors =
    check(
      p.mentionA < p.mentionB,
      s"pair (${p.mentionA}, ${p.mentionB}) is not canonically ordered"
    ) ++
      check(Ids.isMentionId(p.mentionA), s"malformed mention '${p.mentionA}'") ++
      check(Ids.isMentionId(p.mentionB), s"malformed mention '${p.mentionB}'") ++
      check(Ids.isWorkId(p.workId), s"malformed work ID '${p.workId}'") ++
      check(p.positionA >= 0 && p.positionB >= 0, "negative author position") ++
      check(p.teamSize >= 2, s"pair from team of size ${p.teamSize}") ++
      year(p.year, p.workId) ++ provenance(p.provenance, SchemaVersions.Pair)

  def edge(e: CoauthorEdge): Errors =
    schemaVersion(e.schemaVersion, SchemaVersions.Edge) ++
      check(Ids.isEdgeId(e.edgeId), s"malformed edge ID '${e.edgeId}'") ++
      check(e.researcherA < e.researcherB, s"edge ${e.edgeId} endpoints not canonically ordered") ++
      check(Ids.isResearcherId(e.researcherA), s"malformed researcher '${e.researcherA}'") ++
      check(Ids.isResearcherId(e.researcherB), s"malformed researcher '${e.researcherB}'") ++
      check(
        e.edgeId == expectedEdgeId(e),
        s"edge ${e.edgeId} does not match its endpoints, window and policy"
      ) ++
      check(e.jointPublicationCount >= 1, s"edge ${e.edgeId} has no supporting publication") ++
      check(
        e.firstCollaborationYear <= e.lastCollaborationYear,
        s"edge ${e.edgeId} years reversed"
      ) ++
      check(
        e.window.contains(e.firstCollaborationYear) && e.window.contains(e.lastCollaborationYear),
        s"edge ${e.edgeId} years outside window ${e.window.label}"
      ) ++
      check(e.representativeWorkIds.nonEmpty, s"edge ${e.edgeId} has no representative work") ++
      e.representativeWorkIds.flatMap(id => check(Ids.isWorkId(id), s"malformed work ID '$id'")) ++
      window(e.window) ++ provenance(e.provenance, SchemaVersions.Edge)

  private def expectedEdgeId(e: CoauthorEdge): String =
    if e.researcherA == e.researcherB then ""
    else Ids.coauthorEdge(e.researcherA, e.researcherB, e.window, e.policyVersion)

  def metrics(m: ResearcherMetrics): Errors =
    schemaVersion(m.schemaVersion, SchemaVersions.Metrics) ++
      check(Ids.isResearcherId(m.researcherId), s"malformed researcher '${m.researcherId}'") ++
      check(m.publicationCount >= 1, s"${m.researcherId}: no publications") ++
      nonNegative(m.distinctCoauthorCount, "distinctCoauthorCount") ++
      nonNegative(m.totalCoauthorOccurrences, "totalCoauthorOccurrences") ++
      nonNegative(m.maximumCoauthorsOnOnePaper, "maximumCoauthorsOnOnePaper") ++
      nonNegative(m.componentSize, "componentSize") ++
      m.crossCommunityDegree.toVector.flatMap(nonNegative(_, "crossCommunityDegree")) ++
      m.communitiesConnected.toVector.flatMap(nonNegative(_, "communitiesConnected")) ++
      m.coreness.toVector.flatMap(c => nonNegative(c.toLong, "coreness")) ++
      finiteNonNegative(m.averageCoauthorsPerPaper, "averageCoauthorsPerPaper") ++
      finiteNonNegative(m.weightedDegree, "weightedDegree") ++
      m.bridgeScore.toVector.flatMap(finiteNonNegative(_, "bridgeScore")) ++
      m.maximumTeamWorkIds.flatMap(id => check(Ids.isWorkId(id), s"malformed work ID '$id'")) ++
      check(
        m.distinctCoauthorCount <= m.totalCoauthorOccurrences,
        s"${m.researcherId}: C_r > T_r"
      ) ++
      check(
        m.maximumCoauthorsOnOnePaper <= m.totalCoauthorOccurrences,
        s"${m.researcherId}: M_r > T_r"
      ) ++
      check(
        m.maximumCoauthorsOnOnePaper == 0 || m.maximumTeamWorkIds.nonEmpty,
        s"${m.researcherId}: M_r without responsible works"
      ) ++
      window(m.window) ++
      m.participationCoefficient.toVector.flatMap(confidence(_, "participationCoefficient")) ++
      provenance(m.provenance, SchemaVersions.Metrics)

  def venue(v: Venue): Errors =
    check(v.venueId.nonEmpty && v.name.trim.nonEmpty, s"venue '${v.venueId}' without id or name") ++
      provenance(v.provenance, SchemaVersions.Venue)

  def institution(i: Institution): Errors =
    check(
      i.institutionId.nonEmpty && i.name.trim.nonEmpty,
      s"institution '${i.institutionId}' without id or name"
    ) ++
      check(
        i.countryCode.forall(c => c.length == 2 && c.forall(ch => ch >= 'A' && ch <= 'Z')),
        s"institution ${i.institutionId}: country code ${i.countryCode} is not ISO 3166 alpha-2"
      ) ++ provenance(i.provenance, SchemaVersions.Institution)

  def topic(t: Topic): Errors =
    check(t.topicId.nonEmpty && t.name.trim.nonEmpty, s"topic '${t.topicId}' without id or name") ++
      provenance(t.provenance, SchemaVersions.Topic)

  def decision(d: MatchDecision): Errors =
    schemaVersion(d.schemaVersion, SchemaVersions.Match) ++ confidence(d.confidence) ++
      check(Ids.isDecisionId(d.decisionId), s"malformed decision ID '${d.decisionId}'") ++
      check(d.sourceId.nonEmpty && d.targetId.nonEmpty, "decision without source or target") ++
      check(d.sourceId != d.targetId, s"decision ${d.decisionId} links a record to itself") ++
      decisionEndpoints(d) ++
      check(d.policyVersion.trim.nonEmpty, s"decision ${d.decisionId} has no policy version") ++
      check(
        d.sourceId == d.targetId || d.decisionId == expectedDecisionId(d),
        s"decision ${d.decisionId} does not match its endpoints and policy"
      ) ++
      check(d.reasonCodes.nonEmpty, s"decision ${d.decisionId} has no reason code") ++
      check(
        d.features.values.forall(v => !v.isNaN && !v.isInfinite),
        s"decision ${d.decisionId} has a non-finite feature"
      ) ++
      check(
        d.path != MatchPath.LLM_ADJUDICATED || (d.promptVersion.nonEmpty && d.modelVersion.nonEmpty),
        s"LLM decision ${d.decisionId} without prompt and model versions"
      ) ++ provenance(d.provenance, SchemaVersions.Match)

  private def expectedDecisionId(d: MatchDecision): String =
    Ids.decision(d.kind, d.sourceId, d.targetId, d.policyVersion)

  /** Researcher decisions link mentions or researchers; work decisions link works. */
  private def decisionEndpoints(d: MatchDecision): Errors =
    def ok(id: String): Boolean = d.kind match
      case EntityKind.RESEARCHER => Ids.isMentionId(id) || Ids.isResearcherId(id)
      case EntityKind.WORK       => Ids.isWorkId(id)
    check(
      ok(d.sourceId),
      s"decision ${d.decisionId}: malformed ${d.kind} source '${d.sourceId}'"
    ) ++
      check(ok(d.targetId), s"decision ${d.decisionId}: malformed ${d.kind} target '${d.targetId}'")

  def graphNode(n: GraphNode): Errors =
    check(n.nodeId.nonEmpty, "graph node without ID") ++
      check(GraphLabels.NodeLabels(n.label), s"unknown node label '${n.label}'") ++
      provenance(n.provenance, SchemaVersions.GraphNode)

  def graphEdge(e: GraphEdge): Errors =
    check(Ids.isEdgeId(e.edgeId), s"malformed edge ID '${e.edgeId}'") ++
      check(GraphLabels.EdgeLabels(e.edgeType), s"unknown edge type '${e.edgeType}'") ++
      check(e.fromId.nonEmpty && e.toId.nonEmpty, s"edge ${e.edgeId} without endpoints") ++
      check(e.fromId != e.toId, s"edge ${e.edgeId} is a self loop") ++
      check(
        e.edgeId.startsWith("e:" + Ids.edgeTag(e.edgeType) + ":"),
        s"edge ${e.edgeId} carries the tag of another edge type than ${e.edgeType}"
      ) ++
      check(
        !e.fromId.exists(_.isWhitespace) && !e.toId.exists(_.isWhitespace),
        s"edge ${e.edgeId} has an endpoint with whitespace"
      ) ++
      graphEdgeEndpoints(e) ++
      check(
        !GraphLabels.UndirectedEdgeLabels(e.edgeType) || e.fromId < e.toId,
        s"undirected edge ${e.edgeId} endpoints not canonically ordered"
      ) ++ provenance(e.provenance, SchemaVersions.GraphEdge)

  /** Typed endpoints for the edge types whose node kinds are fixed by the graph model. */
  private def graphEdgeEndpoints(e: GraphEdge): Errors =
    def bad(what: String) = Vector(s"edge ${e.edgeId} (${e.edgeType}): $what")
    e.edgeType match
      case GraphLabels.CoauthoredWith =>
        if Ids.isResearcherId(e.fromId) && Ids.isResearcherId(e.toId) then Vector.empty
        else bad("endpoints must be researcher IDs")
      case GraphLabels.Authored =>
        if (Ids.isResearcherId(e.fromId) || Ids.isMentionId(e.fromId)) && Ids.isWorkId(e.toId) then
          Vector.empty
        else bad("must run from a researcher or mention ID to a work ID")
      case GraphLabels.ResolvesTo =>
        if Ids.isMentionId(e.fromId) && Ids.isResearcherId(e.toId) then Vector.empty
        else bad("must run from a mention ID to a researcher ID")
      case _ => Vector.empty

  def evidence(e: EvidenceItem): Errors =
    schemaVersion(e.schemaVersion, SchemaVersions.Evidence) ++
      check(Ids.isEvidenceId(e.evidenceId), s"malformed evidence ID '${e.evidenceId}'") ++
      check(e.sourceUri.nonEmpty, s"${e.evidenceId}: empty source URI") ++
      e.chunkId.toVector.flatMap(c => check(Ids.isChunkId(c), s"malformed chunk ID '$c'")) ++
      check(!e.score.isNaN && !e.score.isInfinite, s"${e.evidenceId}: non-finite score") ++
      check(
        e.text.isEmpty || e.textHash.contains(Hashing.tagged(e.text.get)),
        s"${e.evidenceId}: text hash mismatch"
      ) ++
      provenance(e.provenance, SchemaVersions.Evidence)

  def bundle(b: EvidenceBundle): Errors =
    schemaVersion(b.schemaVersion, SchemaVersions.Bundle) ++
      check(b.bundleId.nonEmpty && b.queryId.nonEmpty, "bundle without bundle or query ID") ++
      check(Ids.isRunId(b.graphRunId), s"malformed graph run ID '${b.graphRunId}'") ++
      check(
        b.all.map(_.evidenceId).distinct.size == b.all.size,
        s"bundle ${b.bundleId} repeats an evidence ID"
      ) ++
      b.all.flatMap(evidence) ++ provenance(b.provenance, SchemaVersions.Bundle)

  def queryPlan(q: QueryPlan): Errors =
    schemaVersion(q.schemaVersion, SchemaVersions.QueryPlan) ++
      check(q.queryId.nonEmpty, "query plan without ID") ++
      check(q.question.trim.nonEmpty, s"query ${q.queryId} has an empty question") ++
      check(q.operations.nonEmpty, s"query ${q.queryId} has no operation") ++
      check(q.limit >= 1, s"query ${q.queryId}: limit ${q.limit} < 1") ++
      check(q.maxPathDepth >= 1, s"query ${q.queryId}: maxPathDepth ${q.maxPathDepth} < 1") ++
      window(q.window) ++ provenance(q.provenance, SchemaVersions.QueryPlan)

  def claim(c: AnswerClaim): Errors =
    check(c.claimId.nonEmpty && c.text.trim.nonEmpty, s"claim '${c.claimId}' without id or text") ++
      c.evidenceIds.flatMap(id => check(Ids.isEvidenceId(id), s"malformed evidence ID '$id'")) ++
      check(
        c.verificationStatus == VerificationStatus.UNSUPPORTED || c.evidenceIds.nonEmpty,
        s"claim ${c.claimId} is ${c.verificationStatus} without evidence"
      )

  def answer(a: AnswerResponse): Errors =
    schemaVersion(a.schemaVersion, SchemaVersions.Answer) ++
      check(a.queryId.nonEmpty, "answer without query ID") ++
      check(
        !a.abstained || a.abstentionReason.exists(_.trim.nonEmpty),
        s"answer ${a.queryId} abstains without a reason"
      ) ++
      a.claims.flatMap(claim) ++
      check(
        a.researchers.flatMap(_.claimIds).forall(a.claims.map(_.claimId).toSet),
        s"answer ${a.queryId} names a claim it does not contain"
      ) ++
      a.researchers.flatMap(r =>
        check(Ids.isResearcherId(r.researcherId), s"malformed researcher '${r.researcherId}'") ++
          confidence(r.identityConfidence, "identityConfidence")
      ) ++
      window(a.window) ++ provenance(a.provenance, SchemaVersions.Answer)
