package scholarchain.core

/** Deterministic sample records shared by the unit suites. No timestamps, no randomness. */
object Fixtures:
  val runId: String = Ids.run("""{"fixture":"core"}""")

  def prov(
      id: String = "conf/test/A21",
      version: String = SchemaVersions.Work,
      source: SourceType = SourceType.DBLP
  ): Provenance = Provenance(source, id, "2026-11-01", runId, version, None)

  val workId: String = Ids.work("dblp", "conf/test/A21")
  val mentionA: String = Ids.mention(workId, 0)
  val mentionB: String = Ids.mention(workId, 1)
  val researcherA: String = Ids.researcherFromMentions(Vector(mentionA))
  val researcherB: String = Ids.researcherFromMentions(Vector(mentionB))
  val (lowResearcher, highResearcher) = Ids.orderedPair(researcherA, researcherB)
  val (lowMention, highMention) = Ids.orderedPair(mentionA, mentionB)
  val window: TimeWindow = TimeWindow(2015, 2026)

  def mentionRecord(id: String, position: Int, raw: String, normalized: String): AuthorMention =
    AuthorMention(
      id,
      workId,
      position,
      raw,
      normalized,
      None,
      None,
      None,
      prov("conf/test/A21", SchemaVersions.Mention)
    )

  val mentionRecordA: AuthorMention = mentionRecord(mentionA, 0, "Ada Testwright", "ada testwright")
  val mentionRecordB: AuthorMention = mentionRecord(mentionB, 1, "Bo Fixture", "bo fixture")

  val work: WorkRecord =
    WorkRecord(
      SchemaVersions.Work,
      workId,
      SourceType.DBLP,
      "conf/test/A21",
      "inproceedings",
      "Graphs, at Scale",
      Normalize.title("Graphs, at Scale"),
      Some(2021),
      Some("venue:dblp:conf/test"),
      Some("TEST"),
      Some("10.1000/xyz123"),
      None,
      None,
      Vector("https://doi.org/10.1000/xyz123"),
      Vector(
        mentionRecordA,
        mentionRecordB
      ),
      Vector.empty,
      Vector.empty,
      None,
      prov()
    )

  val researcher: CanonicalResearcher =
    CanonicalResearcher(
      SchemaVersions.Researcher,
      researcherA,
      "Ada Testwright",
      Vector("A. Testwright"),
      Map("orcid" -> "0000-0002-1825-0097"),
      Vector(mentionA),
      0.97,
      false,
      prov("r1", SchemaVersions.Researcher, SourceType.SCHOLARCHAIN)
    )

  val authorship: AuthorshipRecord =
    AuthorshipRecord(
      researcherA,
      workId,
      mentionA,
      0,
      Some(2021),
      2,
      prov(workId, SchemaVersions.Authorship, SourceType.SCHOLARCHAIN)
    )

  val team: PublicationTeam =
    PublicationTeam(
      workId,
      Some(2021),
      Vector(mentionA, mentionB),
      2,
      prov(workId, SchemaVersions.Team)
    )

  val pair: CoauthorPairCandidate =
    CoauthorPairCandidate(
      lowMention,
      highMention,
      workId,
      Some(2021),
      0,
      1,
      2,
      prov(workId, SchemaVersions.Pair)
    )

  val edge: CoauthorEdge =
    CoauthorEdge(
      SchemaVersions.Edge,
      Ids.coauthorEdge(lowResearcher, highResearcher, window, "graph-v1"),
      lowResearcher,
      highResearcher,
      2,
      2016,
      2020,
      Vector(workId),
      window,
      "graph-v1",
      prov("edge", SchemaVersions.Edge, SourceType.SCHOLARCHAIN)
    )

  val metrics: ResearcherMetrics =
    ResearcherMetrics(
      SchemaVersions.Metrics,
      researcherA,
      4,
      3,
      5,
      1.25,
      2,
      Vector(workId),
      3.0,
      "cc-1",
      7,
      Some("com-1"),
      Some(0.5),
      Some(0.25),
      Some(1),
      Some(2),
      Some(2),
      window,
      "metrics-v1",
      prov("metrics", SchemaVersions.Metrics, SourceType.SCHOLARCHAIN)
    )

  val venue: Venue =
    Venue("venue:dblp:conf/test", "TEST", "conference", prov("venue", SchemaVersions.Venue))

  val institution: Institution =
    Institution(
      "openalex:I1",
      "Test University",
      Some("US"),
      Some("https://ror.org/0abcdefg1"),
      prov("I1", SchemaVersions.Institution, SourceType.OPENALEX)
    )

  val topic: Topic =
    Topic(
      "openalex:T1",
      "Distributed computing",
      prov("T1", SchemaVersions.Topic, SourceType.OPENALEX)
    )

  val decision: MatchDecision =
    MatchDecision(
      SchemaVersions.Match,
      Ids.decision(EntityKind.RESEARCHER, researcherA, researcherB, "er-v1"),
      EntityKind.RESEARCHER,
      researcherA,
      researcherB,
      Map("nameSimilarity" -> 0.91),
      Vector("name-exact"),
      MatchOutcome.ACCEPTED,
      MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
      0.93,
      Vector("SHARED_DOI"),
      "er-v1",
      None,
      None,
      prov("decision", SchemaVersions.Match, SourceType.SCHOLARCHAIN)
    )

  val graphNode: GraphNode =
    GraphNode(
      researcherA,
      GraphLabels.Researcher,
      Map("displayName" -> "Ada Testwright"),
      prov("node", SchemaVersions.GraphNode, SourceType.SCHOLARCHAIN)
    )

  val graphEdge: GraphEdge =
    GraphEdge(
      Ids.graphEdge(GraphLabels.CoauthoredWith, lowResearcher, highResearcher, "graph-v1", workId),
      lowResearcher,
      GraphLabels.CoauthoredWith,
      highResearcher,
      Map("jointPublicationCount" -> "2"),
      prov("gedge", SchemaVersions.GraphEdge, SourceType.SCHOLARCHAIN)
    )

  val evidence: EvidenceItem =
    EvidenceItem(
      SchemaVersions.Evidence,
      Ids.evidence(EvidenceType.METRIC_RECORD, s"$researcherA|metrics-v1"),
      EvidenceType.METRIC_RECORD,
      None,
      Vector(researcherA),
      "s3://bucket-from-config/metrics/part-0.jsonl",
      None,
      None,
      1.0,
      Map("distinctCoauthorCount" -> "3"),
      Some("hello"),
      Some(Hashing.tagged("hello")),
      prov("evidence", SchemaVersions.Evidence, SourceType.SCHOLARCHAIN)
    )

  val bundle: EvidenceBundle =
    EvidenceBundle(
      SchemaVersions.Bundle,
      "bundle-1",
      "q-1",
      Vector(evidence),
      Vector.empty,
      Vector.empty,
      runId,
      "metrics-v1",
      prov("bundle", SchemaVersions.Bundle, SourceType.SCHOLARCHAIN)
    )

  val queryPlan: QueryPlan =
    QueryPlan(
      SchemaVersions.QueryPlan,
      "q-1",
      "Who has the most coauthors since 2015?",
      Vector(QueryOperation.RESEARCHER_RANKING),
      Vector.empty,
      window,
      Vector("distributed systems"),
      Vector.empty,
      Vector.empty,
      CollaborationMetric.DISTINCT_COAUTHOR_COUNT,
      10,
      4,
      false,
      "planner-v1",
      prov("q-1", SchemaVersions.QueryPlan, SourceType.SCHOLARCHAIN)
    )

  val claim: AnswerClaim =
    AnswerClaim(
      "claim-1",
      "Ada Testwright has 3 distinct coauthors",
      Vector(evidence.evidenceId),
      VerificationStatus.VERIFIED,
      None
    )

  val answer: AnswerResponse =
    AnswerResponse(
      SchemaVersions.Answer,
      "q-1",
      "Ada Testwright leads.",
      Vector(
        ResearcherAnswer(
          researcherA,
          "Ada Testwright",
          4,
          3,
          3.0,
          1.25,
          2,
          Vector(workId),
          "cc-1",
          Some("com-1"),
          Some(BridgeMetric("participationCoefficient", 0.25)),
          Some(2),
          0.97,
          Vector("claim-1")
        )
      ),
      Vector(claim),
      window,
      false,
      None,
      "bundle-1",
      VerificationStatus.VERIFIED,
      "prompt-v1",
      "verifier-v1",
      prov("q-1", SchemaVersions.Answer, SourceType.SCHOLARCHAIN)
    )
