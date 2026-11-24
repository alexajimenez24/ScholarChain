HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, role default; effort per .agents/models.json)

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: phase 07 step 01, task T1. Storage ports and fakes, ordinary S3 layout and adapter, DynamoDB tables and adapter. Neptune is T2. S3 Vectors, embedder and loaders are T3.

FILES CREATED (all absolute, under /home/ajcreator/scholarchain):
- modules/storage/src/main/scala/scholarchain/storage/Ports.scala
- modules/storage/src/main/scala/scholarchain/storage/Fakes.scala
- modules/storage/src/main/scala/scholarchain/storage/S3Layout.scala
- modules/storage/src/main/scala/scholarchain/storage/S3ObjectStore.scala
- modules/storage/src/main/scala/scholarchain/storage/DynamoDbStore.scala
- modules/storage/src/main/scala/scholarchain/storage/Tables.scala
- tests/module-unit/storage/StorageSuite.scala (7 suites)
FILES MODIFIED: none. I did not touch MODULE.md, core or build files. The empty `.gitkeep` under `modules/storage/src/main/scala` is untouched.

COMMANDS RUN:
- `sbt -Dsbt.server.forcestart=true storage/compile`. It failed once with a name clash between my `write` method and uPickle's `write`.
- `sbt storage/compile`. It succeeded after the fix.
- `sbt storage/test`, 4 runs in total, one sbt at a time, foreground, with a timeout.
  - Run 1: a test compile error (`kv.size` on the wrong type).
  - Run 2: a test compile error (`withZone` needs `override`).
  - Run 3: 4 failures, 2 of them real bugs in my code and 2 caused by those same bugs (see below).
  - Run 4: all passed.
- Not run: the full `sbt check`, `sbt fmt`, any AWS call, Docker.

TEST RESULTS: Run 4 of `sbt storage/test`: "Passed: Total 33, Failed 0, Errors 0, Passed 33". Counts per suite:
- KeyValueFakeSuite 4
- S3LayoutSuite 3
- ArtifactWriterSuite 4
- SerializationSuite 2
- TablesSuite 10
- S3StubSuite 5
- DynamoStubSuite 5

Run 3 found two things:
- (a) The S3 key segment regex rejected a leading `_`, so `_manifest.json` was refused. I fixed the regex in S3Layout.scala.
- (b) A test used the bucket name `b1`, which is invalid because bucket names need at least 3 characters. I changed the test to `bkt1`.

VALIDATION RESULTS

**Service limits** (checked in the AWS docs on 2026-11-23):
- DynamoDB partition key is 1 to 2048 bytes. Sort key is 1 to 1024 bytes. Item size is 400 KB including attribute names. Source: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html. Enforced in `KvItem.validate`, shared by the fake and the adapter.
- S3 `If-None-Match: *` returns 200 when the key is absent and 412 when it exists. A 409 can occur if a concurrent delete wins. The adapter maps 412 to AlreadyExists and 409 to a retryable Left. Source: https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html.
- I did not check DynamoDB TTL deletion lag (my comment says "up to 48 hours" from memory). Mark it unverified for service-limits.md.

**S3 zones and prefixes.** The zone is a key prefix inside a bucket taken from `StorageConfig`. No literal bucket names are in adapter code.

| Zone | Prefix | Bucket |
|---|---|---|
| Raw | `raw` | `rawBucket` |
| Curated | `curated` | `curatedBucket` |
| Reports | `reports` | `curatedBucket` |
| Logs | `logs` | `curatedBucket` |
| Graph | `graph` | `graphBucket` |
| SemanticContext | `semantic-context` | `ragBucket` |

- `StorageConfig` has no reports or logs bucket, so I chose `curatedBucket` for both. This is a decision for infra-engineer to confirm, for example lifecycle rules by prefix. It is a one-place change in `S3Layout.bucket`.
- Run-scoped (versioned) key: `s3://{bucket}/{zone}/{dataset}/run_id={runId}/{name}`.
- Run manifest: `.../run_id={runId}/_manifest.json`.
- Content-addressed key: `s3://{bucket}/{zone}/{dataset}/sha256={64 hex}/{name}`.
- Each segment must match `^[A-Za-z0-9_][A-Za-z0-9._=:-]{0,127}$`. A `..` segment, an empty segment, a run ID that does not match `Ids.isRunId`, or an unconfigured bucket is a Left.

**S3 objects and manifest**
- `StoredObject(uri, sha256, bytes, contentType, sources: Vector[SourceRef])`.
- `SourceRef(source: SourceType, sourceRecordId, snapshot)`.
- `StorageManifest(schemaVersion = "storage-manifest-v1", runId, stage, zone, dataset, policyVersion: Option, objects)`. It has no timestamp, so a replay serializes to the same bytes.
- Content hash is SHA-256 hex of the exact bytes (core `Hashing.sha256Hex`).
- `ArtifactWriter` write path:
  - It does a create-only put.
  - On AlreadyExists it reads the object and compares hashes. Same hash gives `Unchanged`. A different hash gives `ArtifactError.Conflict` and nothing is overwritten.
  - The manifest uses the same path. `readManifest` checks the version and run ID. `verify(manifest)` lists the URIs whose current hash differs.

**S3 adapter**
- `S3ObjectStore(http: AwsHttp, region, endpointOverride: Option[String])`, service `s3`. The default is virtual-hosted style, `https://{bucket}.s3.{region}.amazonaws.com/{key}`. The override gives path style for the stub.
- PUT sends `if-none-match: *`, `content-type` and `x-amz-meta-sha256`. Core adds `x-amz-content-sha256`.
- List uses ListObjectsV2 with continuation tokens and XML unescaping.
- Binary payloads: `HttpResponse.body` is a String, so `get` is UTF-8 text only.
  - `put` sends the exact bytes and stamps `x-amz-meta-sha256`.
  - `get` recomputes the hash of the decoded bytes. If it differs from the stored header it returns `Left("... body is not UTF-8 text (binary payloads are unsupported)")`. A binary object is detected, never returned silently corrupted.
  - An object without the header is returned unchecked (legacy objects).
  - Parquet and gzip are read by Spark and the Neptune bulk loader, not through this port. If T3 must read binary through the port, the contract needs a byte body (domain-engineer).

**DynamoDB adapter**
- `DynamoDbStore(http, region, endpointOverride)`, service `dynamodb`, JSON 1.0, `x-amz-target: DynamoDB_20120810.{PutItem|GetItem|Query}`.
- Item shape: `{pk:S, sk:S, payload:S (JSON), version:N, ttl?:N}`.
- Terraform contract for infra-engineer: every table has hash key `pk` (S) and range key `sk` (S). The TTL attribute is `ttl`, enabled only on the QuerySession table.
- All expressions use `#pk`, `#sk` and `#v` aliases, and only the aliases that are used (DynamoDB rejects unused names).
- `putIfAbsent` uses `attribute_not_exists(#pk)`.
- `replaceIfVersion` uses `attribute_exists(#pk) AND #v = :expected`. The item version must equal expected+1, checked locally before any call.
- `get` uses ConsistentRead. `query` uses ConsistentRead and follows `LastEvaluatedKey` until `limit` items (limit 1 to 1000).
- ConditionalCheckFailedException is read from `__type` and mapped to `KvError.ConditionFailed`. Other errors become `Failure`.

**Retry policy.** The adapters add no retry. `AwsHttp` retries 429 and 5xx only when it is built with `maxAttempts > 1`. A DynamoDB 400 `ProvisionedThroughputExceededException` is returned as `Failure` and not retried. Whoever builds the production `AwsHttp` in the services or loaders chooses `maxAttempts`.

**DynamoDB tables.** All values are in code in `Tables.scala`, with table names from `DynamoConfig`. `DynamoTables(kv, cfg, clock, sessionTtlSeconds)` returns Left for a blank or duplicate table name. The default local config has blank names, so tests pass names through the environment map.

Shared repository behavior:
- `putIdempotent` creates with attribute_not_exists. If the key is taken, it reads the item. Same value returns `Unchanged`. A different value returns `ConditionFailed` and nothing is overwritten. An item whose TTL has passed is taken over with a version-guarded replace.
- Reads hide TTL-expired items before DynamoDB deletes them.
- A malformed payload gives a `Failure`, never an exception.

| Table | pk | sk | Access patterns | Conditions | TTL |
|---|---|---|---|---|---|
| RunLedger | `run#{runId}` | `stage#{stage}` (state) and `fail#{stage}#{subject}` (failed subject) | GetItem state of a stage; Query `stage#` for all stages of a run; Query `fail#{stage}#` for a stage's failures | `start` is attribute_not_exists; a replay with the same logicVersion returns the stored entry (`Unchanged`), a different logicVersion is ConditionFailed. `succeed`, `fail` and `retry` are `version = expected`. Legal moves: Started to Succeeded or Failed, Failed to Started (attempt+1); Succeeded is terminal. `recordFailure` creates with retryCount 1 and then does a version-guarded +1 | NO: it is the audit trail |
| EntityLinkCache | `link#{mentionKey}` | `policy#{policyVersion}` | GetItem: what policy P resolved key K to, before another paid LLM adjudication | attribute_not_exists; same link is unchanged; a different link under the same policy is refused; a new policy version is a new sk | NO: it stores the result of a paid adjudication, and old-policy entries are inert, not stale |
| QuerySession | `session#{sessionId}` | `turn#{6-digit turn}` | Query the turns of a session in order; GetItem one turn | attribute_not_exists; the same turn is unchanged; a different turn at the same number is refused; an expired item is taken over by a version-guarded replace | YES: now + `ttlSeconds`, default 7 days (constructor parameter), because it is conversational scratch state |
| EvidenceBundle | `bundle#{bundleId}` | `schema#{schemaVersion}` | GetItem by the `evidenceBundleId` an AnswerResponse cites, to verify or replay claims | attribute_not_exists; same bundle unchanged; a different bundle under the same ID refused; schema must be `evidence-bundle-v1`; a bundle over the 400 KB item limit is refused, not truncated | NO: it is the evidence behind a published claim |
| ArtifactRegistry | `artifact#{kind}` | `run#{runId}` (registered artifact) and `current` (promoted pointer) | GetItem the artifact of kind K for run R; GetItem `current` for which run to serve; Query `run#` for all runs of a kind | `register` is attribute_not_exists (same record unchanged, different refused). `promote` requires the run to be registered, creates `current` or replaces it with `version = read version` (same run again is unchanged) | NO: lineage index |
| ResearcherMetricCache | `researcher#{researcherId}` | `metrics#{metricVersion}#{window label}` | GetItem the per-researcher metrics the query service needs for each answered researcher (publication count, coauthors, weighted degree, community, bridge score) without an openCypher round trip | attribute_not_exists; same metrics unchanged; different metrics under the same metricVersion refused (a changed algorithm needs a new metricVersion); schema must be `researcher-metrics-v1` | NO: it is a derived read model, refreshed by reload under a new metric version, not by expiry |

ResearcherMetricCache: YES. Reason: the point read per answered researcher is a real access pattern. The config already has `researcherMetricTable`, and `ConfigLoader.validate` requires it non-empty in aws mode, so Terraform must create it anyway. The cost is about 20 lines on the shared helper.

**Public API** (package `scholarchain.storage`)
- Ports:
  - `trait ObjectStore { putIfAbsent(uri, bytes, contentType): Either[String, PutOutcome]; get; getText; list(prefixUri) }`
  - `enum PutOutcome { Created, AlreadyExists }`
  - `trait KeyValueStore { putIfAbsent; replaceIfVersion(table, item, expectedVersion); get; query(table, pk, skPrefix, limit) }`, all `Either[KvError, _]`; get and query use `Either[String, _]`
  - `KvItem(pk, sk, payload, version = 1, ttl)` with `isExpired(nowEpochSeconds)`, and `KvItem.validate` with the limits
  - `enum KvError { ConditionFailed(pk, sk), Failure(msg) }`
  - There is no unconditional overwrite in any port, by design.
- Fakes: `InMemoryObjectStore` (adds `keys`, `contentType`) and `InMemoryKeyValueStore` (adds `size(table)`). Both enforce the same conditions and limits as AWS.
- Layout:
  - `enum Zone`
  - `S3Layout(StorageConfig)` with `bucket`, `runKey`, `manifestUri`, `contentKey`
  - `SourceRef`, `StoredObject`, `StorageManifest` (+ `parse`)
  - `enum WriteOutcome { Created, Unchanged }`, `enum ArtifactError { Conflict(uri, existingSha, newSha), Failure }`
  - `ArtifactWriter(store, layout)` with `write`, `writeAt`, `writeManifest`, `readManifest`, `verify`
  - `S3Uri.parse` and `encodeKey`
- Adapters: `S3ObjectStore(http, region, endpointOverride)` and `DynamoDbStore(http, region, endpointOverride)`.
- Tables:
  - `Versioned[A](value, version)`, `enum Stored { Created, Unchanged }`
  - `LedgerStatus`, `LedgerEntry`, `FailureRecord`, `EntityLink`, `SessionTurn`, `ArtifactRecord`, `CurrentArtifact`
  - `RunLedger` (start, succeed, fail, retry, get, stages, recordFailure, failures), `EntityLinkCache` (put, get), `QuerySessions` (append, turns), `EvidenceBundles` (put, get), `ArtifactRegistry` (register, get, runs, promote, current), `ResearcherMetricCache` (put, get)
  - `DynamoTables(kv, cfg: DynamoConfig, clock, sessionTtlSeconds): Either[String, DynamoTables]`

ASSUMPTIONS:
- Table key attribute names are `pk` and `sk`, both strings, and the TTL attribute is `ttl`.
- The reports and logs zones live in `curatedBucket` (see above).
- Dependencies: core `aws` package from step 00, `scholarchain.core` Ids, Hashing, Config and Model. No config field is missing, so there is no BLOCKED-ON domain-engineer.
- QuerySession TTL (7 days) is a constructor default because config has no field for it. domain-engineer could add one later.
- The session turn number is caller-assigned, 0 to 999999.

RISKS:
- Not done on purpose:
  - Ports for the vector store, embedder, queue and graph store. T2 and T3 own them.
  - Neptune, S3 Vectors and loaders.
  - The bounded AWS integration suite (T3, gated, NOT RUN).
  - DynamoDB throttling retry.
  - S3 multipart or large uploads. A single PUT is used, S3 allows up to 5 GB, but the String-bodied response and the in-memory byte array make large objects impractical here.
  - Binary reads through the port.
- Nothing was run against real AWS or the SDK. The wire shape (DynamoDB JSON, S3 headers, ListObjectsV2 XML) is proven only against my local stub, which I wrote from the API documentation. The SigV4 canonical form for S3 query strings with `list-type=2&prefix=...` is untested against AWS.
- `sbt check` and scalafmt were not run, as ordered. The PostToolUse hook formatted files on write, but a format check is not proven. I did not read the formatted files back.
- Test hygiene: `StorageSuite.scala` is one large file, and the virtual-hosted test uses a custom `HttpTransport`.
- State left behind: new uncommitted files only.
- Notes for modules/storage/MODULE.md (documentation-engineer):
  - public API as above
  - done: T1, 33 tests
  - not done: T2 Neptune, T3 vectors, embedder, loaders, integration suite
  - known issues: the binary limit, the reports and logs bucket choice, no throttling retry, the TTL default of 7 days, the `pk`/`sk`/`ttl` Terraform contract, and the DynamoDB TTL lag unverified

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: T2 (storage-engineer, Neptune). Then code-reviewer after T3. infra-engineer (Phase 12) must read the `pk`/`sk`/`ttl` table contract and the zone-to-bucket map above.
