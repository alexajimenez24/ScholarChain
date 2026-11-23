Use the dataset-engineer and domain-engineer, in separate delegated work orders, to create the ScholarChain data contracts and dataset manifest.

Dataset-engineer work:
- Create datasets/dataset-manifest.yaml for DBLP and every selected enrichment dataset.
- Include source, license, snapshot, format, size estimate, S3 prefixes, checksums, subset rule, schema, linkage keys, update method, limitations, privacy notes, and validation commands.
- Create bounded download and sample-generation scripts under datasets/scripts.
- Create representative legal sample files under datasets/samples, small enough for the repository.
- Create schema notes under datasets/schemas.
- Scripts must be idempotent and must not download a full corpus by default.

Domain-engineer work:
- Create immutable Scala domain records for WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord, PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics, Venue, Institution, Topic, Provenance, MatchDecision, GraphNode, GraphEdge, EvidenceItem, EvidenceBundle, QueryPlan, AnswerClaim, and AnswerResponse.
- Define stable researcher, work, edge, evidence, and run ID functions.
- Define enums for source type, match outcome, evidence type, collaboration metric, query operation, and verification status.
- Define a configuration loader using environment variables plus versioned HOCON or YAML files. Do not hardcode bucket names, endpoints, thresholds, model IDs, AWS account IDs, graph windows, or bridge-algorithm parameters.
- Create serialization and schema-version tests.
- Freeze representative JSON wire examples in contract tests.
- Add validation that rejects missing provenance, invalid confidence ranges, unsupported schema versions, malformed stable IDs, invalid undirected pair ordering, and time windows where start year exceeds end year.

Run sbt check.
Report sample serialized records, stable ID formulas, pair canonicalization rule, configuration precedence, schema versions, and known unresolved dataset questions.

Addendum from the human architect for this repository:

- Reference material: ~/ref-phase-03/ holds a reference implementation of this phase
  from the collaborative ScholarChain repository, with the same layout as this
  repository. Read ~/ref-phase-03/REFERENCE.md first. Each owner may start from the
  reference files in its own territory, but adapts them to this repository, reviews
  every line, and owns the result; nothing is copied unread. Neither owner reads or
  writes outside its territory.
- Configuration format: the work order asks for versioned HOCON or YAML files, and
  uPickle is the only JSON library allowed. Store the configuration as
  modules/core/src/main/resources/scholarchain/config/scholarchain-v1.yaml, written in
  the JSON-compatible subset of YAML 1.2 so uPickle parses it, and state that
  restriction in the file header, in modules/core/MODULE.md and in the report.
- Service configuration: replace the reference's single api section with settings for
  this repository's three deployables (query service, graph service, embedding worker)
  and their shared Cognito JWT settings (issuer, audience, JWKS URL, request timeout),
  as described in AGENTS.md and PROJECT_MEMORY.md. No value may be hardcoded; endpoints
  and IDs come from environment placeholders.
- Datasets: every license, snapshot, size, checksum and endpoint in the manifest is
  re-observed at the official source or marked UNVERIFIED. Committed samples are small
  and legal to redistribute.
- Contract tests: the frozen JSON wire examples go in domain-engineer's unit tests in
  tests/module-unit/core/. tests/contracts/ stays test-engineer's for Phase 11.
- Run owners one at a time, never in parallel, and never more than one sbt at once.
- Save each final report word for word in docs/agent-runs/phase-03/:
  dataset-engineer as step-01-dataset-engineer-report.md, domain-engineer as
  agent-report.md. Update STATUS.md through documentation-engineer after each owner.
