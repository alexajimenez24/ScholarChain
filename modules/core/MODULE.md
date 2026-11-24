# Module `core`

## Purpose

Shared domain records, stable identifiers, serialization and configuration contracts.
Pipeline stages exchange data only through these versioned contracts on S3.

## Owner and paths

- Owner: domain-engineer (documentation-engineer owns only this file).
- sbt project: `core`; folder: `modules/core`.
- Sources: `modules/core/src/main/scala/scholarchain/core/`.
- Resource: `modules/core/src/main/resources/scholarchain/config/scholarchain-v1.yaml`.
- Unit-test root: `tests/module-unit/core/` (never `src/test`).
- Frozen wire examples: `tests/module-unit/core/resources/wire/*.json`.

## Public API

Everything is in package `scholarchain.core`.
Sources of this section: the T02 report (`docs/agent-runs/phase-03/agent-report.md`),
the repair round 1 report (`docs/agent-runs/phase-03/repair-01-domain-engineer.md`) and
the code.

Types and constants (`Model.scala`):

- `SchemaVersions` with 18 constants (`Work`, `Mention`, `Researcher`, `Authorship`,
  `Team`, `Pair`,
  `Edge`, `Metrics`, `Venue`, `Institution`, `Topic`, `Match`, `GraphNode`, `GraphEdge`,
  `Evidence`, `Bundle`, `QueryPlan`, `Answer`) and the set `Supported`.
- Enums: `SourceType` (DBLP, OPENALEX, ORCID, CROSSREF, SCHOLARCHAIN), `MatchOutcome`,
  `MatchPath`, `EntityKind`, `EvidenceType`, `CollaborationMetric`, `QueryOperation`,
  `VerificationStatus`.
- `Provenance` (source, sourceRecordId, snapshot, runId, schemaVersion, policyVersion) and
  `TimeWindow` (startYear, endYear; `contains`, `label`).
- Records (`AuthorMention` carries its own `provenance` and is a standalone Phase 4
  output; its `provenance.schemaVersion` is `author-mention-v1`): `AuthorMention`,
  `WorkRecord`, `CanonicalResearcher`, `AuthorshipRecord`,
  `PublicationTeam`, `CoauthorPairCandidate`, `CoauthorEdge`, `ResearcherMetrics`,
  `Venue`,
  `Institution`, `Topic`, `MatchDecision`, `GraphNode`, `GraphEdge`, `EvidenceItem`,
  `EvidenceBundle`, `QueryPlan`, `AnswerClaim`, `AnswerResponse`.
- Answer parts: `ResearcherAnswer`, `BridgeMetric`.
- `GraphLabels`: node labels, edge labels, `NodeLabels`, `EdgeLabels`,
  `UndirectedEdgeLabels`.

Functions:

- `Ids`: `edgeTag`, `MaxManifestNumber`, `work`, `mention`,
  `researcherFromOrcid`, `researcherFromMentions`,
  `orderedPair`, `coauthorEdge`, `graphEdge`, `chunk`, `evidence`, `decision`, `run`,
  and the shape checks `isWorkId`, `isMentionId`, `isResearcherId`, `isEdgeId`,
  `isChunkId`,
  `isEvidenceId`, `isDecisionId`, `isRunId`.
- `Validate`: one validator per record except `AnswerClaim` and the nested parts
  (`mention`, `work`, `researcher`, `authorship`, `team`,
  `pair`, `edge`, `metrics`, `venue`, `institution`, `topic`, `decision`, `graphNode`,
  `graphEdge`,
  `evidence`, `bundle`, `queryPlan`, `answer`), plus `claim`, `confidence`, `window`,
  `snapshotDate`, `schemaVersion` and `provenance`.
  `AnswerClaim` has no validator of its own inside a record and no provenance: its
  provenance is its evidence IDs, which `Validate.claim` checks (each ID is well formed,
  and a claim that is not UNSUPPORTED needs at least one).
  Each validator returns `Validate.Errors`; an empty result means valid.
  Year bounds `MinYear` 1900 and `MaxYear` 2100 are a plausibility constant.
- `Hashing`: `sha256Hex`, `short`, `tagged`.
- `JsonLines`: `encode`, `decode`, `decodeValid` (the type needs a
  `Reader` and a `Writer`, which every core record derives); decoding is strict and
  total, see below; `RejectRecord` (stage, reason, input, runId) and `RejectRecord.of`.
- `Normalize` (version `norm-v1`): `foldUnicode`, `title`, `stripDblpHomonym`,
  `personName`, `doi`, `orcid`, `orcidCheckDigit`.
  It is deliberately minimal; matching logic belongs to entity-resolution.
- `ConfigLoader`: `load`, `parse`, `stripComments`, `validate`, `processEnvironment`,
  `DefaultResource`, `SupportedVersions`.
- Config case classes: `ScholarChainConfig` and its sections `ApplicationConfig`,
  `StorageConfig`, `NeptuneConfig`, `DynamoConfig`, `VectorConfig`,
  `EntityResolutionConfig`, `GraphConfig`, `RetrievalConfig`, `ModelConfig`,
  `OperationsConfig`, `ServicesConfig`, `HttpServiceConfig`, `WorkerServiceConfig`,
  `JwtConfig`.

### Validation rules added in repair round 1

- A mention ID is recomputed from work ID and position, and the mention IDs of one
  work are distinct.
- `provenance.snapshot` is a real calendar date `YYYY-MM-DD`.
- Metrics counts are non-negative and finite.
- A graph edge ID tag equals `Ids.edgeTag(edgeType)`, and endpoint types are checked per
  edge type (COAUTHORED_WITH between researchers, AUTHORED from researcher or mention to
  work, RESOLVES_TO from mention to researcher, other types non-blank without
  whitespace).
  The graph edge ID is not recomputed in full: policy and evidence are not in the record.
- A match decision ID is recomputed, with endpoint shapes checked by kind and a policy
  version required.
- `representativeWorkIds` and `maximumTeamWorkIds` hold work IDs.

### Decoding (`JsonLines`)

- `decode` and `decodeValid` never throw: a JSON null in a required field, an unknown
  field, a lossy value (such as a fractional `year`) and a validator that throws are each
  a `Left`.
- The strictness is a re-write comparison and a null scan, about three JSON passes per
  line.

### Package `scholarchain.core.aws` (Phase 07 step 00)

Source: `docs/agent-runs/phase-07/step-00-domain-engineer-report.md` and the files
`Credentials.scala`, `Http.scala` and `SigV4.scala` under `scholarchain/core/aws/`.
It has no wire format and no schema version, and it adds no dependency (uPickle `ujson`
only, the JDK `java.net.http.HttpClient`, no AWS SDK).

- `AwsCredentials(accessKeyId, secretAccessKey, sessionToken, expiration)`; `toString`
  redacts the secret.
- `CredentialsProvider` with `StaticCredentials` (tests) and `EnvironmentCredentials`
  (environment variables, then the ECS container endpoint, cached until 5 minutes before
  expiry; the environment is an injected `Map`).
- `HttpRequest`, `HttpResponse(status, body: String, headers)` (alias `AwsResponse`),
  `HttpTransport`, `JdkHttpTransport` (no redirects, no retries).
- `AwsHttp(region, credentials, maxAttempts = 1, ...)` with `send(method, url, service,
  body, headers)` and `json(url, service, body, headers)`.
  A non-2xx reply is `Right` from `send` and `Left` from `json`.
  Retry only when `maxAttempts` is above 1, on a transport failure or HTTP 429, 500, 502,
  503 or 504, with exponential backoff.
  Service `s3` also signs and sends `x-amz-content-sha256`.
- `SigV4.sign`, `signingKey`, `uriEncode`, `canonicalUri`, `canonicalQuery`.

## Stable ID formulas

`h(x,n)` is the first n hex characters of SHA-256 over the UTF-8 text x.
No formula uses a timestamp.
Frozen literal values are asserted in `IdsSuite`.

- Work: `<namespace>:<authoritative source id>`.
  The namespace matches `[a-z][a-z0-9]{1,15}` and the source ID has no whitespace.
  `Ids.work` throws unless its result satisfies `isWorkId` (400 characters at most).
  Example: `dblp:conf/test/A21`.
- Mention: `m:` + h(`<workId>#<position>`, 20).
- Researcher: `orcid:<iD>` when the ORCID passes the MOD 11-2 check digit.
  Otherwise `sc:r:v1:` + h(sorted mention IDs joined by `|`, 20).
  The ID pattern also accepts `openalex:A<digits>`, but no function builds that form.
  The ID is stable within a run and policy (a hash of the mention set); an ORCID is the
  cross-run key.
- Coauthor edge: `e:co:` + h(`<a>|<b>|<startYear-endYear>|<policyVersion>`, 24), with
  (a,b) ordered.
- Graph edge: `e:<lowercase type>:` + h(`<type>|<from>|<to>|<policy>|<evidence>`, 24).
  For COAUTHORED_WITH the endpoints are ordered first.
- Chunk or profile: `c:` +
  h(`<entity>|<sourceVersion>|<docType>|<ordinal>|<sha256(text)>`,
  20).
- Evidence: `ev:<type lowercase>:` + h(key, 16).
- Match decision: `md:<kind lowercase>:` + h(`<a>|<b>|<policyVersion>`, 20), with (a,b)
  ordered.
- Run: `run-` + h(canonical manifest JSON, 16).
  Object keys are sorted recursively and the text is re-serialized without whitespace.
  The manifest must hold no timestamp; the function does not enforce that.
  `Ids.run` rejects any number of magnitude 2^53 or more (`Ids.MaxManifestNumber`) with an
  `IllegalArgumentException`; such values must be strings.

## Pair canonicalization rule

- The lexicographically smaller ID goes first (`String.compareTo`, UTF-16 code unit
  order).
  A pair of equal IDs is rejected.
- `Ids.orderedPair` applies it.
  Validation enforces it on `CoauthorPairCandidate` (mentionA < mentionB), `CoauthorEdge`
  (researcherA < researcherB) and `GraphEdge` of an undirected type (only COAUTHORED_WITH,
  in `GraphLabels.UndirectedEdgeLabels`: fromId < toId).
- Edge and decision IDs hash the ordered pair, so A,B and B,A give one ID.
- `Validate.edge` also checks the edge ID against the ID recomputed from endpoints, window
  and policy.

## Schema versions

- Own `schemaVersion` field: work-v1, researcher-v1, coauthor-edge-v1,
  researcher-metrics-v1, match-v1, evidence-v1, evidence-bundle-v1, query-plan-v1,
  answer-v1.
- Version only in `provenance.schemaVersion`: author-mention-v1, authorship-v1, team-v1,
  coauthor-pair-v1,
  venue-v1, institution-v1, topic-v1, graph-node-v1, graph-edge-v1.
- `AnswerClaim` has no provenance and no schema version; its evidence IDs are its
  provenance (hard rule 3).
  It is validated inside `AnswerResponse` or alone through `Validate.claim`.
  The enclosing `AnswerResponse` carries the provenance, `promptVersion` and
  `modelVersion`.
- Validation requires the version to be in `SchemaVersions.Supported` and to equal the one
  defined for the record type; a mismatch is reported as "is required".
- The config file has its own version, `config-v1`.
- The wire shape of every record is frozen.
  A change needs a new version constant, a new frozen file and a `Supported` entry in the
  same change.

## Configuration

Precedence, highest first:

1. Environment overrides `SCHOLARCHAIN__<SECTION>__<KEY>`.
   Segments match case-insensitively and nest with `__`.
   An unknown key fails the load.
   An override takes the type of the value it replaces; arrays and objects are parsed as
   JSON.
2. `${VAR}` or `${VAR:-default}` placeholders in the file, resolved from the environment.
   `${VAR}` with no default and no value fails the load and lists every missing name.
3. Literal values in the versioned file.

There are no defaults in code.
`SCHOLARCHAIN_CONFIG_FILE` selects another file path; otherwise the classpath resource is
used.
A whole-string placeholder whose file default is a number or boolean takes that type;
otherwise it stays a string.

Validation after loading covers the config version, entity-resolution thresholds
(reject < llmBandMin <= llmBandMax <= accept, all in [0,1]), graph windows, service ports
and timeouts, worker batch and in-flight sizes, retrieval path depth and
`maxTeamSizeForPairs`, and these ranges: `neptune.port` 1..65535, `queryTimeoutMs` >= 1,
`vectors.dimension` >= 1, `maxBlockSize` >= 1, operations limits (retries >= 0, others
>= 1), `maxLlmCallsPerRun` >= 0, graph and retrieval parameters >= 1, non-blank policy and
metric versions and a non-empty `vectors.indexes`.
`retrieval.maxPathDepth` must be in `1..graph.pathMaxDepth`, read from the file, with no
code constant.
A number is accepted from a placeholder or an override only as plain decimal text
(`-?digits[.digits][e+-digits]`).
Hex, Java float suffixes such as `8182d`, `NaN` and `Infinity` are rejected.
For an integer field the value must be a whole number with an absolute value below 2^53
(exclusive, matching `Ids.run`).
After the typed config is read, it is written back and must equal the overridden JSON
tree.
Any value that the typed read would clamp (for example above 2^31-1 for an Int field),
round or drop, and any key outside the schema, makes the load a Left.
Beyond that, the validator requires:

- mode `local` or `aws`;
- in `aws` mode, non-empty bucket, endpoint, table, queue, embedding model and auth
  values, and, when `llmEnabled` is true, non-empty synthesis and verification model IDs;
- ports 1..65535 and timeouts of at least 1 ms;
- every limit, iteration and size field at least 1, including
  `entityResolution.maxBlockSize` and `retrieval.vectorLimit`;
- retry and LLM-call caps at least 0;
- non-blank policy, metric and fusion-policy versions;
- a non-empty `vectors.indexes` with no blank name;
- `retrieval.maxPathDepth` within 1..`graph.pathMaxDepth`, read from the file;
- entity-resolution thresholds in [0,1] with reject < llmBandMin <= llmBandMax <= accept;
- valid graph windows.

The `services` section holds `query`, `graph`, `worker` and `auth` (Cognito JWT settings
from `${COGNITO_*}` placeholders).

### Restriction: JSON-compatible YAML

- `scholarchain-v1.yaml` is the JSON-compatible subset of YAML 1.2: exactly one JSON
  document, parsed by uPickle (ujson), the only JSON library.
- Only full-line `#` comments are allowed.
  `ConfigLoader.stripComments` removes them before parsing.
- Not supported: anchors, aliases, tags, block style, multiple documents, trailing commas
  and trailing comments.
- A block-style YAML file is refused with "config is not JSON-compatible YAML".

## Done

- Records, enums, IDs, validators, JSON-lines helpers, `Normalize` and the config loader,
  all in `scholarchain.core` (phase 3, task T02).
- 19 frozen wire examples (counted on disk in `tests/module-unit/core/resources/wire/`;
  the domain-engineer report says 18, which is not the on-disk count); each is checked for
  byte-equal write, equal read and valid decode.
  `wire/author-mention.json` is new and `wire/work.json` changed (nested mentions carry
  provenance).
- Repair round 2 `sbt check` exit 0 with 94 core tests passing (NormalizeSuite 4,
  IdsSuite 12, ValidateSuite 25, SerializationSuite 29, ConfigSuite 24), from
  `docs/agent-runs/phase-03/repair-02-domain-engineer.md`.
  The report names 13 mutation checks, each failing exactly one test.
  SerializationSuite has 10 named tests plus one frozen-example test per wire file (19),
  which is 29.
- No bucket, table, endpoint, queue, model ID or account ID in the config file (a test
  checks it).
- Phase 07 step 00: package `scholarchain.core.aws` (HTTP client, SigV4 signer,
  credentials port).
  `sbt core/test` passed 115.
  The step 00 report says "86 existing plus 29 new"; its own suite counts are 6 + 6 + 9 =
  21 new tests, and 94 (phase 3) plus 21 is 115, so the 21 figure is the consistent one.
  The full `sbt check` of step 04 printed a 115 total, which matches core.
  Tests: `tests/module-unit/core/AwsSuite.scala`.
- Phase 07 repair round 1, R1-2 (`docs/agent-runs/phase-07/repair-01-domain-engineer.md`):
  - `EnvironmentCredentials` restricts `AWS_CONTAINER_CREDENTIALS_FULL_URI`: https is
    allowed; plain http only to `localhost`, 127.x.x.x, `::1`, 169.254.170.2,
    169.254.170.23 and `fd00:ec2::23` (literal host match, no DNS lookup).
    Anything else returns a `Left` that does not echo the URL, and no request is sent.
    The RELATIVE_URI path is unchanged.
  - `AwsHttp`'s malformed-URL error is now `invalid request: <METHOD> <scheme://host/path>
    (malformed URL)`; the query string and fragment are omitted.
  - `sbt core/test` passed 119; the full `sbt check` exited 0 in 305 s
    (`docs/agent-runs/phase-07/output-sbt-check-r1.txt`).

### Phase 08 additions

Source: `docs/agent-runs/phase-08/step-00c-domain-engineer-report.md`.

- `graph.componentMaxIterations` is 100 (was 40) in `scholarchain-v1.yaml`.
  It also bounds the BFS and coreness loops.
  No consumer hardcodes 40; `WindowAnalytics` and `Canonical` read the config value.
- A new `ConfigSuite` test pins the value; `sbt core/test` passed 120, failed 0.
- The value is part of the Spark run manifest, so changing it changes the run ID.
- No schema version changed.

## Not done

- No record for ORCID researcher profiles and none for the Hadoop-to-entity-resolution
  candidate pair; Phases 4 and 5 need both, as an additive change with a new schema
  version.
- Only the round 2 rules were mutation-tested (13 mutants); the other negative tests are
  not.
  The unknown-enum test and some config tests check only a message substring.
- No consumer used the contracts at phase 3; later phases add modules that depend on
  `core` (see the boundary table).
- `scholarchain.core.aws` does not do: query-string (presigned) signing, credential
  process, profile or IMDS providers, S3 `UNSIGNED-PAYLOAD` or chunked signing,
  dot-segment normalization of the canonical URI.

## Known issues

- A work ID shares its shape with some researcher IDs (`orcid:...` and `sc:r:v1:...` match
  the work pattern), so shape alone cannot tell the kinds apart.
  Validators check by kind where the kind is fixed.
  A reserved work-namespace list would fix it; it is an open design point.
- A numeric config field needs a numeric file default, or it is read as a string and fails
  at the schema step; the loader has no schema-driven typing.
- `${VAR}` is plain text substitution with no secret handling.
- The JWT settings are config only; nothing verifies a token yet (Phase 10).
- `Normalize` is minimal.
- Run IDs can collide for fractional manifest numbers (T14c in review 2): `Ids.run`
  rejects only magnitudes of 2^53 or more.
- A missing required key gives the unclear reject reason "TraceException: $".
- Duplicate JSON keys are accepted, and the last one wins.
- Decoding costs about 3 to 5 times a plain read; measure it in Phase 4.
- Nested mention provenance adds about 230 bytes per author.
- A nested mention's provenance is not checked against the work's provenance.
- A `componentSize` of 0 is accepted.
- Contract-side dataset questions 1 to 8 are open, in
  `docs/agent-runs/phase-03/agent-report.md` (ORCID profile data, one `externalAuthorId`
  slot with no namespace, invalid self-reported ORCIDs, DBLP homonym collision in
  `normalizedName`, one WorkRecord per source, case-sensitive source IDs, no arXiv
  normalizer, no ISSN-L on `Venue`).
- `scholarchain.core.aws` known issues (step 00 report):
  - `HttpResponse.body` is a String, so binary S3 payloads cannot round trip; storage
    treats them as unsupported.
  - 2 of the AWS suite's roughly 30 cases are used as vectors (get-vanilla and
    post-vanilla), plus the published signing-key value.
    The vector values were typed from recall of the AWS test suite, recomputed with
    Python `hmac`, and not fetched from AWS; the source URL is in the test.
  - Other services' query and encoding edge cases rely on the implementation of the
    specification.
  - The request timeout is one value per client.
  - The ECS link-local base `http://169.254.170.2` is a constant (the default of
    `containerBase`).
- Provisional file values (thresholds, windows, k-core limit, betweenness seed) come from
  the reference; the experiments phase should confirm them.

### Phase 08

- A larger diameter at full DBLP scale would still be reported as non-convergence,
  because the invariant is kept.
  The observed maximum distance at 25,000 works was 39 and the loop needs about the
  diameter plus 1, plus a round with no change.

## Dependencies

- External libraries only: uPickle 4.4.3 (the only JSON library) and slf4j-api 2.0.20.
- The JDK `java.net.http` client is used by `scholarchain.core.aws`; no new library.
- No project dependency.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
