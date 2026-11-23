package scholarchain.core

import upickle.default.ReadWriter

/** Schema versions of every wire record. A reader rejects any version it does not list.
  *
  * Records with a `schemaVersion` field carry their own version; the others carry it in
  * `provenance.schemaVersion`. Either way the value must equal the constant named for the record. A
  * wire-format change needs a new constant (for example `work-v2`), a frozen JSON example for it in
  * tests/module-unit/core, and an entry in [[Supported]].
  */
object SchemaVersions:
  val Work = "work-v1"
  val Mention = "author-mention-v1"
  val Researcher = "researcher-v1"
  val Authorship = "authorship-v1"
  val Team = "team-v1"
  val Pair = "coauthor-pair-v1"
  val Edge = "coauthor-edge-v1"
  val Metrics = "researcher-metrics-v1"
  val Venue = "venue-v1"
  val Institution = "institution-v1"
  val Topic = "topic-v1"
  val Match = "match-v1"
  val GraphNode = "graph-node-v1"
  val GraphEdge = "graph-edge-v1"
  val Evidence = "evidence-v1"
  val Bundle = "evidence-bundle-v1"
  val QueryPlan = "query-plan-v1"
  val Answer = "answer-v1"
  val Supported: Set[String] =
    Set(
      Work,
      Mention,
      Researcher,
      Authorship,
      Team,
      Pair,
      Edge,
      Metrics,
      Venue,
      Institution,
      Topic,
      Match,
      GraphNode,
      GraphEdge,
      Evidence,
      Bundle,
      QueryPlan,
      Answer
    )

/** Datasets the pipeline reads, plus SCHOLARCHAIN for records derived by the pipeline itself. */
enum SourceType derives ReadWriter:
  case DBLP, OPENALEX, ORCID, CROSSREF, SCHOLARCHAIN

enum MatchOutcome derives ReadWriter:
  case ACCEPTED, REJECTED, UNRESOLVED, CONFLICT

/** Which rung of the linkage evidence hierarchy produced a decision. */
enum MatchPath derives ReadWriter:
  case EXACT_ID, DETERMINISTIC_BIBLIOGRAPHIC, PROBABILISTIC, LLM_ADJUDICATED

enum EntityKind derives ReadWriter:
  case RESEARCHER, WORK

enum EvidenceType derives ReadWriter:
  case METRIC_RECORD, COAUTHOR_EDGE, GRAPH_PATH, PUBLICATION, COMPONENT_RECORD, COMMUNITY_RECORD,
    IDENTITY_RESOLUTION, TEXT_CHUNK, SEMANTIC_PROFILE

enum CollaborationMetric derives ReadWriter:
  case PUBLICATION_COUNT, DISTINCT_COAUTHOR_COUNT, TOTAL_COAUTHOR_OCCURRENCES,
    AVERAGE_COAUTHORS_PER_PAPER, MAXIMUM_COAUTHORS_ON_ONE_PAPER, WEIGHTED_DEGREE, BRIDGE_SCORE,
    PARTICIPATION_COEFFICIENT, CROSS_COMMUNITY_DEGREE, CORENESS

enum QueryOperation derives ReadWriter:
  case RESEARCHER_RANKING, MAX_TEAM_SIZE, DIRECT_COAUTHORS, SHORTEST_PATH, COMPONENT_LOOKUP,
    COMMUNITY_COMPARISON, BRIDGE_RANKING, TEMPORAL_COMPARISON, TOPIC_FILTER, INSTITUTION_FILTER,
    SEMANTIC_EXPLANATION

enum VerificationStatus derives ReadWriter:
  case VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING

/** Where a record came from: dataset record, snapshot, pipeline run and the versions applied. */
final case class Provenance(
    source: SourceType,
    sourceRecordId: String,
    snapshot: String,
    runId: String,
    schemaVersion: String,
    policyVersion: Option[String]
) derives ReadWriter

/** Inclusive publication-year window. Every metric and edge names the window it was built for. */
final case class TimeWindow(startYear: Int, endYear: Int) derives ReadWriter:
  def contains(year: Int): Boolean = year >= startYear && year <= endYear
  def label: String = s"$startYear-$endYear"

/** One author occurrence on one work. A standalone Phase 4 output, so it carries its own provenance
  * and the `author-mention-v1` schema version in `provenance.schemaVersion`.
  */
final case class AuthorMention(
    mentionId: String,
    workId: String,
    position: Int,
    rawName: String,
    normalizedName: String,
    orcid: Option[String],
    externalAuthorId: Option[String],
    affiliation: Option[String],
    provenance: Provenance
) derives ReadWriter

final case class WorkRecord(
    schemaVersion: String,
    workId: String,
    source: SourceType,
    sourceRecordId: String,
    recordType: String,
    title: String,
    normalizedTitle: String,
    year: Option[Int],
    venueId: Option[String],
    venueName: Option[String],
    doi: Option[String],
    arxivId: Option[String],
    openAlexId: Option[String],
    urls: Vector[String],
    authors: Vector[AuthorMention],
    topicIds: Vector[String],
    institutionIds: Vector[String],
    abstractTextRef: Option[String],
    provenance: Provenance
) derives ReadWriter

final case class CanonicalResearcher(
    schemaVersion: String,
    researcherId: String,
    displayName: String,
    nameVariants: Vector[String],
    externalIds: Map[String, String],
    mentionIds: Vector[String],
    identityConfidence: Double,
    unresolved: Boolean,
    provenance: Provenance
) derives ReadWriter

final case class AuthorshipRecord(
    researcherId: String,
    workId: String,
    mentionId: String,
    position: Int,
    year: Option[Int],
    teamSize: Int,
    provenance: Provenance
) derives ReadWriter

/** Ordered author mentions of one retained work, emitted by the Hadoop team job. */
final case class PublicationTeam(
    workId: String,
    year: Option[Int],
    mentionIds: Vector[String],
    teamSize: Int,
    provenance: Provenance
) derives ReadWriter

/** One unordered mention pair of one work. Invariant: mentionA < mentionB (lexicographic). */
final case class CoauthorPairCandidate(
    mentionA: String,
    mentionB: String,
    workId: String,
    year: Option[Int],
    positionA: Int,
    positionB: Int,
    teamSize: Int,
    provenance: Provenance
) derives ReadWriter

/** Logical undirected coauthorship. Invariant: researcherA < researcherB (lexicographic). */
final case class CoauthorEdge(
    schemaVersion: String,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: Vector[String],
    window: TimeWindow,
    policyVersion: String,
    provenance: Provenance
) derives ReadWriter

/** Spark-computed collaboration measures of one researcher in one window. Never one score. */
final case class ResearcherMetrics(
    schemaVersion: String,
    researcherId: String,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    weightedDegree: Double,
    componentId: String,
    componentSize: Long,
    communityId: Option[String],
    bridgeScore: Option[Double],
    participationCoefficient: Option[Double],
    crossCommunityDegree: Option[Long],
    communitiesConnected: Option[Long],
    coreness: Option[Int],
    window: TimeWindow,
    metricVersion: String,
    provenance: Provenance
) derives ReadWriter

final case class Venue(venueId: String, name: String, kind: String, provenance: Provenance)
    derives ReadWriter

final case class Institution(
    institutionId: String,
    name: String,
    countryCode: Option[String],
    ror: Option[String],
    provenance: Provenance
) derives ReadWriter

final case class Topic(topicId: String, name: String, provenance: Provenance) derives ReadWriter

final case class MatchDecision(
    schemaVersion: String,
    decisionId: String,
    kind: EntityKind,
    sourceId: String,
    targetId: String,
    features: Map[String, Double],
    rulesFired: Vector[String],
    decision: MatchOutcome,
    path: MatchPath,
    confidence: Double,
    reasonCodes: Vector[String],
    policyVersion: String,
    promptVersion: Option[String],
    modelVersion: Option[String],
    provenance: Provenance
) derives ReadWriter

final case class GraphNode(
    nodeId: String,
    label: String,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter

final case class GraphEdge(
    edgeId: String,
    fromId: String,
    edgeType: String,
    toId: String,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter

final case class EvidenceItem(
    schemaVersion: String,
    evidenceId: String,
    evidenceType: EvidenceType,
    workId: Option[String],
    researcherIds: Vector[String],
    sourceUri: String,
    chunkId: Option[String],
    graphPath: Option[Vector[String]],
    score: Double,
    values: Map[String, String],
    text: Option[String],
    textHash: Option[String],
    provenance: Provenance
) derives ReadWriter

/** Bounded evidence for one query. Deterministic graph facts stay apart from semantic context. */
final case class EvidenceBundle(
    schemaVersion: String,
    bundleId: String,
    queryId: String,
    deterministic: Vector[EvidenceItem],
    semantic: Vector[EvidenceItem],
    identity: Vector[EvidenceItem],
    graphRunId: String,
    metricVersion: String,
    provenance: Provenance
) derives ReadWriter:
  def all: Vector[EvidenceItem] = deterministic ++ identity ++ semantic
  def byId: Map[String, EvidenceItem] = all.map(e => e.evidenceId -> e).toMap

final case class QueryPlan(
    schemaVersion: String,
    queryId: String,
    question: String,
    operations: Vector[QueryOperation],
    researcherNames: Vector[String],
    window: TimeWindow,
    topics: Vector[String],
    institutions: Vector[String],
    venues: Vector[String],
    rankingMetric: CollaborationMetric,
    limit: Int,
    maxPathDepth: Int,
    includeSemantic: Boolean,
    plannerVersion: String,
    provenance: Provenance
) derives ReadWriter

final case class AnswerClaim(
    claimId: String,
    text: String,
    evidenceIds: Vector[String],
    verificationStatus: VerificationStatus,
    uncertainty: Option[String]
) derives ReadWriter

final case class BridgeMetric(name: String, value: Double) derives ReadWriter

final case class ResearcherAnswer(
    researcherId: String,
    displayName: String,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    weightedDegree: Double,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    componentId: String,
    communityId: Option[String],
    bridgeMetric: Option[BridgeMetric],
    coreness: Option[Int],
    identityConfidence: Double,
    claimIds: Vector[String]
) derives ReadWriter

final case class AnswerResponse(
    schemaVersion: String,
    queryId: String,
    summary: String,
    researchers: Vector[ResearcherAnswer],
    claims: Vector[AnswerClaim],
    window: TimeWindow,
    abstained: Boolean,
    abstentionReason: Option[String],
    evidenceBundleId: String,
    verification: VerificationStatus,
    promptVersion: String,
    modelVersion: String,
    provenance: Provenance
) derives ReadWriter

/** Node and edge labels of the research graph model. */
object GraphLabels:
  val Researcher = "Researcher"
  val AuthorMention = "AuthorMention"
  val Work = "Work"
  val Venue = "Venue"
  val Institution = "Institution"
  val Topic = "Topic"
  val TextChunk = "TextChunk"
  val DatasetSnapshot = "DatasetSnapshot"
  val NodeLabels: Set[String] =
    Set(Researcher, AuthorMention, Work, Venue, Institution, Topic, TextChunk, DatasetSnapshot)

  val Authored = "AUTHORED"
  val ResolvesTo = "RESOLVES_TO"
  val CoauthoredWith = "COAUTHORED_WITH"
  val PublishedIn = "PUBLISHED_IN"
  val AffiliatedWith = "AFFILIATED_WITH"
  val HasTopic = "HAS_TOPIC"
  val DerivedFrom = "DERIVED_FROM"
  val SupportedBy = "SUPPORTED_BY"
  val EdgeLabels: Set[String] =
    Set(
      Authored,
      ResolvesTo,
      CoauthoredWith,
      PublishedIn,
      AffiliatedWith,
      HasTopic,
      DerivedFrom,
      SupportedBy
    )

  /** Edge labels whose endpoints are an unordered pair, stored with fromId < toId. */
  val UndirectedEdgeLabels: Set[String] = Set(CoauthoredWith)
