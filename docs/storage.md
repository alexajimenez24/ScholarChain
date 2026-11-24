# Storage layer (phase 07)

Module: `modules/storage` (sbt project `storage`), with the HTTP client and SigV4 signer
in `modules/core` (package `scholarchain.core.aws`).
Public API and status: [modules/storage/MODULE.md](../modules/storage/MODULE.md).
Limits and their documentation URLs: [service-limits.md](service-limits.md).

Sources of every fact below: the Phase 07 step reports in `docs/agent-runs/phase-07/`
(`step-00-domain-engineer-report.md`, `step-01-storage-engineer-report.md`,
`step-02-storage-engineer-report.md`, `agent-report.md`) and the code under
`modules/storage/src/main/scala/scholarchain/storage/`.
No AWS call was made in this phase.
Every adapter was proven only against in-memory fakes and a local
`com.sun.net.httpserver` stub, and the gated AWS integration suite was NOT RUN.
`sbt check` exited 0 (`docs/agent-runs/phase-07/output-sbt-check-step04.txt`): storage
153 passed and 1 ignored, core 115, adversarial 68, entityResolution 119, hadoop 98 and
spark 33 (project names inferred from the suite names, see the step 04 report).
After repair round 1 the full `sbt check` exited 0 again
(`docs/agent-runs/phase-07/output-sbt-check-r1.txt`, 305 s): 158 passed and 1 ignored, 119,
68, 98, 33 and 119 as printed, without project names.

Bucket names, table names, index names, the Neptune endpoint and the embedding model ID
come from configuration (`scholarchain-v1.yaml` placeholders and `${VAR}` values).
No adapter holds a literal bucket, table, endpoint or account ID.

## 1. Ordinary S3

A zone is a key prefix inside a bucket from `StorageConfig`.

| Zone | Prefix | Bucket config field |
|---|---|---|
| Raw | `raw` | `rawBucket` |
| Curated | `curated` | `curatedBucket` |
| Reports | `reports` | `curatedBucket` |
| Logs | `logs` | `curatedBucket` |
| Graph | `graph` | `graphBucket` |
| SemanticContext | `semantic-context` | `ragBucket` |

- `StorageConfig` has no reports or logs bucket, so both use `curatedBucket`.
  This is a decision for infra-engineer to confirm (for example lifecycle rules by
  prefix).
  It is a one-place change in `S3Layout.bucket`.
- Run-scoped key: `s3://{bucket}/{zone}/{dataset}/run_id={runId}/{name}`.
- Run manifest: `s3://{bucket}/{zone}/{dataset}/run_id={runId}/_manifest.json`.
- Content-addressed key: `s3://{bucket}/{zone}/{dataset}/sha256={64 hex}/{name}`.
- Each key segment must match `^[A-Za-z0-9_][A-Za-z0-9._=:-]{0,127}$`.
  A `..` or empty segment, a run ID that fails `Ids.isRunId`, and an unconfigured bucket
  are refused.
- `StorageManifest` (`storage-manifest-v1`): `runId`, `stage`, `zone`, `dataset`,
  optional `policyVersion` and `objects`.
  It has no timestamp, so a replay serializes to the same bytes.
- Each `StoredObject` holds `uri`, `sha256` (hex of the exact bytes), `bytes`,
  `contentType` and `sources` (`SourceRef`: source type, source record ID, snapshot).
- `ArtifactWriter` writes create-only (`If-None-Match: *`).
  On an existing key it reads the object and compares hashes: equal gives `Unchanged`,
  different gives `ArtifactError.Conflict` and nothing is overwritten.
  There is no unconditional overwrite in any port.
- Binary payloads are not supported through the port: `HttpResponse.body` is a String,
  so `get` is UTF-8 text only.
  `put` stamps `x-amz-meta-sha256`; `get` recomputes the hash and returns a `Left` when it
  differs from the header, so a binary object is detected and never returned corrupted.
  Parquet and gzip are read by Spark and Neptune, not through this port.

Prefixes written by the loaders:

- `semantic-context/{docType}/run_id={run}/{chunkId}.json` in `ragBucket` (stored text).
- `logs/load-vectors/`, `logs/load-researcher-metrics/` and
  `logs/write-neptune-files/`, each `run_id={run}/load-report-*.json`, in
  `curatedBucket`.
- `graph/neptune-load/run_id={run}/` (CSV only) and
  `graph/neptune-load-manifest/run_id={run}/` (`neptune-load.json` plus a
  `StorageManifest`) in `graphBucket`.
- Integration test (gated): `logs/integration-test/run_id=.../check.txt`.

## 2. DynamoDB

Item shape for every table: `{pk: S, sk: S, payload: S (JSON), version: N, ttl?: N}`.
Contract for infra-engineer (Phase 12): every table has hash key `pk` (S) and range key
`sk` (S); the TTL attribute is `ttl`, enabled only on QuerySession.
Table names come from `DynamoConfig`; `DynamoTables(...)` returns Left for a blank or
duplicate name.

Writes are conditional:

- `putIfAbsent` uses `attribute_not_exists(#pk)`.
- `replaceIfVersion` uses `attribute_exists(#pk) AND #v = :expected`, and the new item's
  version must equal expected plus 1 (checked locally before any call).
- `get` and `query` use `ConsistentRead`; `query` follows `LastEvaluatedKey` up to a
  limit of 1 to 1000 items.
- `putIdempotent` (shared): create; if the key exists, read it; same value is
  `Unchanged`, a different value is `ConditionFailed` and nothing is overwritten.
- Reads hide items whose TTL has passed, before DynamoDB deletes them.
- Limits enforced locally: partition key 2048 bytes, sort key 1024 bytes, item 400 KB
  (see [service-limits.md](service-limits.md)).

Tables (key formats in code spelling):

- RunLedger: pk `run#{runId}`; sk `stage#{stage}` (state) and
  `fail#{stage}#{subject}` (failed subject).
  Access: get a stage; query `stage#` for a run; query `fail#{stage}#` for failures.
  `start` is create-only; a replay with the same logicVersion returns the stored entry,
  a different logicVersion is `ConditionFailed`.
  `succeed`, `fail` and `retry` are `version = expected`.
  Legal moves: Started to Succeeded or Failed; Failed to Started (attempt plus 1);
  Succeeded is terminal.
  TTL: no, it is the audit trail.
- EntityLinkCache: pk `link#{mentionKey}`; sk `policy#{policyVersion}`.
  Access: get what policy P resolved key K to, before another paid LLM adjudication.
  Same link is unchanged; a different link under one policy is refused; a new policy
  version is a new sk.
  TTL: no, it stores the result of a paid call and old-policy entries are inert.
  Any reader must keep `Adjudicator.recheck` on every hit (Phase 05 carried item).
- QuerySession: pk `session#{sessionId}`; sk `turn#{6-digit turn}`.
  Access: query turns in order; get one turn.
  Same turn is unchanged; a different turn at one number is refused; an expired item is
  taken over by a version-guarded replace.
  TTL: yes, now plus `ttlSeconds`, default 7 days (a constructor parameter; the config has
  no field), because it is conversational scratch state.
  The turn number is caller-assigned, 0 to 999999.
- EvidenceBundle: pk `bundle#{bundleId}`; sk `schema#{schemaVersion}`.
  Access: get by the `evidenceBundleId` an answer cites.
  Schema must be `evidence-bundle-v1`; a bundle over 400 KB is refused, not truncated.
  TTL: no, it is the evidence behind a published claim.
- ArtifactRegistry: pk `artifact#{kind}`; sk `run#{runId}` (registered) and `current`
  (promoted pointer).
  Access: get an artifact; get `current`; query `run#` for all runs of a kind.
  `register` is create-only; `promote` requires the run to be registered and creates or
  replaces `current` with `version = read version`.
  TTL: no, it is the lineage index.
- ResearcherMetricCache (included, justified): pk `researcher#{researcherId}`; sk
  `metrics#{metricVersion}#{window label}`.
  Access: one point read per answered researcher, without an openCypher round trip.
  The config already names `researcherMetricTable` and requires it in aws mode.
  Different metrics under one `metricVersion` are refused (a changed algorithm needs a
  new version); schema must be `researcher-metrics-v1`.
  TTL: no, it is a derived read model.

TTL decision: only QuerySession is disposable.
DynamoDB deletes expired items "typically within a few days"
([service-limits.md](service-limits.md)), which is why reads filter expired items.

## 3. Neptune

Input: typed core rows `GraphNode`, `GraphEdge` and `CoauthorEdge` (the Phase 06 table
shapes), through `NeptuneExport.build`.
The real Phase 06 Parquet run was NOT loaded (see section 8).

Labels exported by Phase 06: Researcher, Work, Venue, Institution, Topic (run
`run-769e0d672ecd3c19`: 81, 42, 1, 37, 44, total 205 nodes, from `neptune.json`).
Relationships: AUTHORED (106), COAUTHORED_WITH (156 across 2 non-empty windows),
PUBLISHED_IN (25), AFFILIATED_WITH (47), HAS_TOPIC (75), total 409.
Not exported: RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY and the nodes they need
(AuthorMention, TextChunk, DatasetSnapshot).
They are listed in the load manifest as not exported, and a row of that kind is rejected
with "not exported by Phase 06".

Id scheme: node `:ID` is the Spark `nodeId`; relationship `:ID` is the Spark `edgeId`
(core `Ids.graphEdge`).
A COAUTHORED_WITH edge keeps its graph-edge ID, and the window-scoped coauthor-edge ID
is in the property `coauthorEdgeId`.
The loader creates no identifier.
Output rows are sorted by ID, so the same input gives the same bytes.

CSV (openCypher format, UTF-8, LF, RFC 4180; one file per label or type; an empty file is
not written):

- Node header: `:ID,:LABEL,<props>`.
  Relationship header: `:ID,:START_ID,:END_ID,:TYPE,<props>`.
- Provenance columns on every row:
  `source:String,sourceRecordId:String,snapshot:String,runId:String,policyVersion:String`.
  An empty cell means the property is absent.
- `nodes-Researcher.csv`: `displayName:String,orcid:String,mentionCount:Long,`
  `mentionIds:String,identityConfidence:Double,unresolved:Bool,identityConflict:Bool`.
- `nodes-Work.csv`: `title:String,year:Int,doi:String,recordType:String,`
  `memberWorkIds:String,unresolved:Bool,identityConflict:Bool`.
- `nodes-Venue.csv`: `name:String,kind:String`.
  `nodes-Institution.csv`: `name:String,countryCode:String,ror:String`.
  `nodes-Topic.csv`: `name:String`.
- `edges-AUTHORED.csv`: `position:Int,teamSize:Int,year:Int,mentionIds:String`.
- `edges-COAUTHORED_WITH.csv`: `coauthorEdgeId:String,window:String,windowStartYear:Int,`
  `windowEndYear:Int,jointPublicationCount:Long,firstCollaborationYear:Int,`
  `lastCollaborationYear:Int,representativeWorkIds:String,evidencePointer:String`.
  `representativeWorkIds` is `|`-joined; `evidencePointer` is
  `<evidenceBaseUri>/coauthor-edges/windowLabel=<window>#<coauthorEdgeId>`.
- `edges-PUBLISHED_IN.csv` and `edges-HAS_TOPIC.csv`: provenance only.
  `edges-AFFILIATED_WITH.csv`: `supportingMentionCount:Int`.
- An input property outside these lists, or a derived column supplied by the input, is
  rejected.
- Researcher collaboration metrics are not on nodes (they are window-scoped); they go to
  ResearcherMetricCache.

Validation (each failure is a `Rejection(kind, id, reason)` in the manifest; nothing is
repaired; dependants of a rejected row are rejected):

- Nodes: exported label; core `Validate.graphNode`; run ID equals the input run; ID shape;
  known, non-derived property keys with parseable values.
  An identical duplicate is dropped and counted; a different record under one ID
  rejects every copy.
- Relationships: exported type; core `Validate.graphEdge`; endpoints are loaded nodes
  with the labels the type fixes.
- COAUTHORED_WITH in addition: a coauthor-edge record that passes core `Validate.edge`;
  both endpoints are Researcher nodes equal to the record's pair in order; a configured
  window equal to the record window; the graph edge ID equals
  `Ids.graphEdge(COAUTHORED_WITH, a, b, policy, coauthorEdgeId)`; count, years and works
  equal the record; every representative work is a loaded Work AUTHORED by both
  researchers; a coauthor record with no referencing edge is rejected.
- `SparkNeptuneManifest.reconcile` compares the Spark `neptune.json` counts with the rows
  read and names every difference.

Read templates (class `GraphTemplates`, 14 constant templates, no string literals):
`node-v1`, `direct-coauthors-v1`, `coauthor-pair-v1`, `shortest-path-d1-v1` to
`shortest-path-d6-v1`, `neighbors-out-v1`, `neighbors-in-v1`, `neighbors-both-v1`,
`work-authors-v1`, `researcher-works-v1`.

- Window must be a configured window label; limit 1 to 200; depth 1 to 6; an ID is
  non-empty, at most 450 characters, with no whitespace or control character.
- All 8 templates that read COAUTHORED_WITH contain `e.window = $window` (single-window
  rule, tested).
- Neighbors edge types come from AUTHORED, HAS_TOPIC, AFFILIATED_WITH, PUBLISHED_IN
  only.
- `NeptuneGraphStore.run` refuses any query that is not allowlisted, and
  `ReadOnlyCypher` rejects write keywords.
  The runtime port exposes read templates only; write access exists only in the loader.

Adapters: `NeptuneGraphStore` posts `/openCypher` (service `neptune-db`);
`NeptuneBulkLoader` posts `/loader` and reads status.
`NeptuneLoadWriter` writes the files.
`NeptuneLoadJob` writes the files and never starts the bulk load; `start` stays an
explicit step.
The loader role ARN is a constructor argument (no core config field).

## 4. S3 Vectors

Five indexes, dimension from `vectors.dimension` (default 256; tests use 16), cosine,
float32.
Names come from core `VectorConfig` and match the Spark run manifest `vectorIndexes`.

| Index | docType | Entity filter field | Source |
|---|---|---|---|
| researcher-profile-v1 | researcher-profile | `researcherIds` | Spark, 162 rows |
| collaboration-community-v1 | collaboration-community | `communityId` | Spark, 58 rows |
| collaboration-path-v1 | collaboration-path | `pathId`, `researcherIds` | Spark, 8 rows |
| topic-institution-summary-v1 | topic-institution-summary | `topicId` | Spark, 88 rows |
| paper-abstract-v1 | paper-abstract | `workId` | Hadoop J02 abstracts |

Phase 08 update: the counts in the table above are the Phase 07 fixture counts.
The real 25,000-work run `run-ccf619c509611f06` has 167,600, 43,826, 278, 5,957 and
6,292 rows, and the loader now reads the JSON-lines export and takes text only from a
rag renderer.
See [embeddings.md](embeddings.md) for the indexes, metadata, versions, queue, batch
and retry settings, and the local run.

Row counts are from the Spark load manifest `s3vectors.json` (316 rows in 4 indexes),
not from data read by the loader.
paper-abstract-v1 is wired and tested on fixtures only: the Spark export has no
paper-abstract rows and the local Hadoop run has no `abstracts` folder.
`institutionId` is a supported filter on topic-institution-summary but the Phase 06
export does not carry it.

- Vector key is the document `chunkId` (`c:` plus 20 hex, core
  `Ids.chunk(entity, sourceVersion, docType, ordinal, sha256(text))`), so a changed text
  is a new key.
- Filterable metadata: `documentType`, `entityId`, `source`, `runId`, `snapshot`,
  `embeddingModel`; for windowed documents `window`, `windowStartYear`, `windowEndYear`
  (numbers); `researcherIds` (string list, at most 16); `communityId`, `topicId`,
  `institutionId`, `workId`, `pathId` when the row has them.
- Non-filterable metadata, declared at index creation (4 of the 10 allowed):
  `textUri`, `textSha256` (`sha256:` form), `evidenceId`, `sourceUri`
  (`VectorIndexes.NonFilterable`).
- No long text in the vector: the whole `SemanticDocument` JSON is stored create-only in
  `semantic-context/...` and the vector holds only `textUri` and `textSha256`.
  `VectorMetadata.check` refuses, before any put: a key named text, body, content,
  abstract, profile, summary or description; a string over 512 bytes; a value that
  repeats a 40-character span of the text; more than 16 researcher IDs.
- Contract for infra-engineer: create each index with `dataType` `float32`, the
  dimension, `distanceMetric` `cosine` and
  `metadataConfiguration.nonFilterableMetadataKeys` equal to
  `VectorIndexes.NonFilterable`.
  IAM: `s3vectors:PutVectors`, `GetVectors`, `QueryVectors` (a filter or metadata needs
  `GetVectors` too) and `bedrock:InvokeModel` for the embedding model.
  The loader never creates a bucket or index.
- Embedder: `BedrockEmbedder` (Titan Text Embeddings V2, one call per text, no batching,
  refuses text over 20,000 characters); `HashingEmbedder` for local and test use.
  The model ID is the embedding model version.

## 5. Batch sizes

- Vector loader: batch 100 (settable 1 to 100): one `GetVectors` (service maximum 100)
  and one `PutVectors` (service maximum 500) per group.
  Tested: 250 documents give puts of 100, 100, 50 and 3 `GetVectors`.
- Query topK 1 to 100, one page.
- Embedding: one Bedrock call per text.
- DynamoDB: one item per call; `query` pages by `LastEvaluatedKey`.

## 6. Retry policy

- `RetryPolicy` default: 4 attempts in total, 200 ms times 2 to the power (n minus 1),
  capped at 5 s (loader tests use 3).
  Retried: HTTP 408, 429, 500, 502, 503, 504 and a missing response.
  Not retried: 400, 402, 403, 404, 424 and malformed replies.
- Wiring requirement: no storage code builds `AwsHttp`; the caller picks `maxAttempts`.
  Phase 10 and cloud callers must build `AwsHttp` with `maxAttempts` 1 whenever a
  `RetryPolicy` wraps the call, otherwise the attempts multiply (for example `maxAttempts`
  3 under the default `RetryPolicy` of 4 allows up to 12 attempts per call).
  `AwsHttp` itself retries only when `maxAttempts` is above 1.
- After exhaustion: a failed `PutVectors` rejects every vector of that batch with the
  reason (a replay retries them); a failed embedding rejects that document; a failed
  `GetVectors` or an S3 error on the text object fails the stage (ledger Failed, a
  replay retries with attempt plus 1).
- The DynamoDB and S3 adapters add no retry of their own, so a
  `ProvisionedThroughputExceededException` is returned as a failure.

## 7. Idempotency per loader

Every load is a RunLedger stage; a replay of a Succeeded stage still runs the body, finds
nothing to change and does not move the ledger.

- Vectors (stage `load-vectors`, logic `vector-load-v1`): `PutVectors` is an upsert, so
  the loader guards it.
  Per group it calls `GetVectors`: same `textSha256` and same `embeddingModel` is
  unchanged (no embed, no write); same key with different text, same text with another
  model, or a stored vector with no `textSha256` is rejected and nothing is overwritten
  (a new model needs a new index version).
  The text object is create-only with a content-hash compare.
  The report object name carries its own hash, so a replay's report is `Unchanged`.
- Researcher metrics (stage `load-researcher-metrics`, logic `metrics-load-v1`):
  `ResearcherMetricCache.put` is create-only with a same-value check; a changed value
  under one metric version is rejected.
  Identical duplicates are dropped and counted; conflicting copies are all rejected.
  The manifest `rows` count is checked before any write.
- Neptune files (stage `write-neptune-files`, logic `neptune-files-v1`, class
  `NeptuneLoadJob`): content-hashed create-only writer; a replay is `Unchanged` for every
  object, and a changed plan under one run is a conflict.
- Neptune bulk load: the documented loader merges rows with one ID and keeps one
  non-deterministic value, so determinism comes from the Spark IDs and the deduplicating
  exporter ([service-limits.md](service-limits.md)).
- Absent input directory (NIT-4 of Phase 05): after repair round 1,
  `RowSource.jsonLinesDir` on a missing directory yields one `Left("input directory <dir>
  is missing")` row, which the loaders count as one rejected malformed row.
  The stage still records Succeeded in the ledger, and an `expectedRows` check makes it
  fail.
  The `_SUCCESS` marker rule for absent outcome folders is not implemented by this
  change.
- Served-run invariant (Review 1 MAJOR-1): one served run per Neptune cluster and per
  vector index.
  A new run's data is loaded only after the human cleans or rebuilds the cluster and the
  index.
  A reload under a new run does not remove the old run's nodes, edges or vectors, and an
  unchanged vector keeps the first run's `runId` and `textUri`.
  The bulk loader's `updateSingleCardinalityProperties` only replaces a changed property
  of an existing ID.
  Full run scoping (run-scoped templates and vector reads) is not implemented and is
  carried forward to the human gate.
- Known issue, carried forward (Review 1 MINOR-6): a replay that repairs earlier
  rejections writes a new load report, but the Succeeded ledger stage still names the
  first report (`outputUri` and `outputSha256`), because Succeeded is terminal.

## 8. Not loaded, not done

- The real Phase 06 Parquet run was NOT loaded: storage has no Parquet reader.
  Expected counts if loaded (from manifests only): 316 vectors, 162 researcher-metrics
  rows, 205 nodes and 409 edges.
  Options for the human: a Parquet dependency through build-engineer, JSON-lines exports
  from spark-engineer, or a reader in a later phase.
- Spark export gaps: no `institutionId` in the semantic summaries and no paper-abstract
  rows.
  Phase 08 step 00 closed both gaps, and spark-engineer added a JSON-lines export, so
  the Parquet reader gap is closed for the vector loader only (see
  [embeddings.md](embeddings.md)).
  The Neptune and DynamoDB loaders have not yet been run on the JSON-lines export.
  A contract test for them is carried forward to storage-engineer.
  The Phase 07 statements above stay as the record of that phase.
- OpenSearch, the SQS queue adapter, `ListVectors`, query pagination beyond 100 hits and
  the `s3.json` manifest loader are not implemented.

## 9. Gated AWS integration suite

- File: `tests/module-unit/storage/AwsIntegrationSuite.scala`.
  Status: NOT RUN.
  It is the 1 ignored test in the storage totals.
- Opt-in: only the exact value `true` of `SCHOLARCHAIN_AWS_IT` opens the gate.
  `sbt check` and `build.sbt` never set it (checked by `AwsGateSuite`).
  A closed gate returns NotRun with zero transport calls; an opened gate without
  configuration fails before any call.
- Command (needs real credentials and cloud configuration, and the human's cost
  sign-off, which has not been given):
  `SCHOLARCHAIN_AWS_IT=true SCHOLARCHAIN_IT_TEST_VECTOR_INDEX=<dedicated test index>`
  plus `RAW_BUCKET`, `CURATED_BUCKET`, `GRAPH_BUCKET`, `RAG_BUCKET`, the six `DDB_*`
  table variables (`DDB_RUN_LEDGER`, `DDB_ENTITY_LINK_CACHE`, `DDB_QUERY_SESSION`,
  `DDB_EVIDENCE_BUNDLE`, `DDB_ARTIFACT_REGISTRY`, `DDB_RESEARCHER_METRIC`),
  `VECTOR_BUCKET`, `EMBEDDING_MODEL_ID` and `EMBEDDING_DIMENSION`, then
  `sbt "storage/testOnly *AwsIntegrationSuite"`.
- Bound: at most 25 HTTP attempts, retries included, enforced by a counting transport
  (`AwsIntegration.BoundedTransport`) that refuses every attempt after the 25th and makes
  the run `Failed`.
  The calls are S3 create-only puts, DynamoDB conditional writes, one Bedrock embedding,
  and S3 Vectors put, get and query on 2 vectors.
- The test index must be a dedicated test index: the run refuses, before any call, an
  index name equal to any production index in `VectorConfig.indexes`.
- It creates no bucket, table or index.
- Source: `docs/agent-runs/phase-07/repair-01-storage-engineer.md`.
  The suite is still NOT RUN; the bound is proven only against a stub transport.

## 10. Cleanup procedures

Nothing was run and no cloud resource exists from this phase.
No agent deletes or repairs data; this is the dossier for the human.

- Ordinary S3: delete the `run_id={run}` prefixes listed in section 1 to remove one run
  (except the semantic-context prefix, see the next rule);
  empty the buckets (`scripts/cleanup.sh`, human only) to remove all.
- S3 Vectors: keys are the `chunkId` values in the stored texts; the load report holds
  counts and rejections only, not the keys written.
  Remove with `DeleteVectors` (500 per call) per index, or delete the index or bucket.
  Integration-test vectors are named `it-<tag>-a` and `it-<tag>-b`.
- A run's `semantic-context/.../run_id={run}/` prefix may be deleted only together with
  the vectors whose `textUri` points into it.
  An unchanged vector keeps the first run's `textUri`, so deleting that prefix alone
  leaves a later run's vector with a dangling pointer.
- DynamoDB: RunLedger items `run#{run}` with `stage#load-vectors`,
  `stage#load-researcher-metrics` and `stage#write-neptune-files`; metric items
  `researcher#{id}` with `metrics#{version}#{window}`; the integration QuerySession item
  `session#it-<tag>` expires by its 7-day TTL (deletion can lag by days).
- A failed run can leave stored text objects without a vector (text is stored before the
  embedding call); they are harmless and are reused on replay.
- Neptune: nothing is loaded by this phase and no Neptune cleanup procedure was written.
