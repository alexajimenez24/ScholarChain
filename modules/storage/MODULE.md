# Module `storage`

## Purpose

Ports, adapters and loaders for ordinary S3, DynamoDB, Neptune (bulk-load files, read
templates, openCypher and loader HTTP adapters) and S3 Vectors, plus the Bedrock
embedder and the in-memory fakes that prove them without AWS.
Full description of layouts, schemas, keys and procedures:
[docs/storage.md](../../docs/storage.md).
Limits with documentation URLs and check dates:
[docs/service-limits.md](../../docs/service-limits.md).
Optional OpenSearch was not implemented (the human addendum excluded it).

## Owner and paths

- Owner: storage-engineer (documentation-engineer owns only this file).
- sbt project: `storage`; folder: `modules/storage`.
- Sources: `modules/storage/src/main/scala/scholarchain/storage/` (13 files:
  `Ports`, `Fakes`, `S3Layout`, `S3ObjectStore`, `DynamoDbStore`, `Tables`,
  `GraphTemplates`, `NeptuneExport`, `NeptuneAdapters`, `VectorStore`, `Embedder`,
  `VectorLoader`, `Loaders`).
- Unit-test root: `tests/module-unit/storage/` (never `src/test`): `StorageSuite`,
  `NeptuneSuite`, `VectorSuite`, `LoaderSuite`, `AwsIntegrationSuite`.

## Public API

Package `scholarchain.storage`.
Sources: the Phase 07 step 01, 02 and 03 reports in `docs/agent-runs/phase-07/`, checked
against file names under `src/main`.
Every operation that can fail returns `Either`; none throws on bad input.

Ports and fakes (`Ports.scala`, `Fakes.scala`):

- `ObjectStore` (`putIfAbsent`, `get`, `getText`, `list`), `PutOutcome` (`Created`,
  `AlreadyExists`).
- `KeyValueStore` (`putIfAbsent`, `replaceIfVersion`, `get`, `query`), `KvItem(pk, sk,
  payload, version, ttl)` with `isExpired` and `KvItem.validate`, `KvError`
  (`ConditionFailed`, `Failure`).
  There is no unconditional overwrite in any port.
- `GraphStore.run(GraphQuery)`, read-only.
- `InMemoryObjectStore`, `InMemoryKeyValueStore`, `InMemoryGraphStore(responder)`; the
  fakes enforce the same conditions and limits as AWS.

S3 layout and artifacts (`S3Layout.scala`):

- `Zone`, `S3Layout(StorageConfig)` (`bucket`, `runKey`, `manifestUri`, `contentKey`),
  `S3Uri`, `SourceRef`, `StoredObject`, `StorageManifest` (`storage-manifest-v1`),
  `WriteOutcome`, `ArtifactError`, `ArtifactWriter` (`write`, `writeAt`,
  `writeManifest`, `readManifest`, `verify`).

Adapters over core `AwsHttp`:

- `S3ObjectStore(http, region, endpointOverride)`.
- `DynamoDbStore(http, region, endpointOverride)`.
- `NeptuneGraphStore`, `NeptuneBulkLoader` (`start`, `status`), `LoaderStatus`,
  `NeptuneLoadWriter`, `NeptuneLoadResult` (`NeptuneAdapters.scala`).
- `S3VectorsStore(http, region, bucket, endpointOverride)`, `RetryPolicy`, `RemoteError`,
  `MetaValue`, `VectorRecord`, `StoredVector`, `VectorHit`, `FilterTerm`, `IndexSpec`,
  `VectorLimits`, `VectorIndexes` (`NonFilterable`, `spec`), `InMemoryVectorStore`
  (`VectorStore.scala`).
- `Embedder`, `HashingEmbedder`, `BedrockEmbedder` (and `fromConfig`) (`Embedder.scala`).

DynamoDB tables (`Tables.scala`):

- `DynamoTables(kv, cfg, clock, sessionTtlSeconds)`: `RunLedger`, `EntityLinkCache`,
  `QuerySessions`, `EvidenceBundles`, `ArtifactRegistry`, `ResearcherMetricCache`, with
  `Versioned`, `Stored`, `LedgerStatus`, `LedgerEntry`, `FailureRecord`, `EntityLink`,
  `SessionTurn`, `ArtifactRecord`, `CurrentArtifact`.

Neptune files and templates (`NeptuneExport.scala`, `GraphTemplates.scala`):

- `NeptuneExport.build(GraphExportInput): NeptuneLoadPlan`, `NeptuneSchema`, `NeptuneCsv`,
  `Rejection`, `CsvFile`, `NeptuneLoadManifest`, `SparkNeptuneManifest` (`parse`,
  `reconcile`).
- `GraphTemplates` (14 allowlisted templates), `GraphQuery`, `ReadOnlyCypher`.

Loaders (`VectorLoader.scala`, `Loaders.scala`):

- `SemanticDocument` (`fromEvidence`, `fromAbstract`), `PaperAbstract`, `VectorMetadata`,
  `VectorLoaderSettings`, `VectorLoader(...)` with `load(runId, RowSource, expectedRows)`.
- `RowSource` (`of`, `jsonLines`, `jsonLinesDir`), `LoadReport`, `LoadStage`,
  `VectorManifest`, `DynamoManifest`, `LoadManifests.vectors/dynamo`,
  `ResearcherMetricsLoader`, `NeptuneLoadJob`.

Test side: `AwsIntegration`, `AwsGateSuite`, `AwsIntegrationHarnessSuite` and the gated
`AwsIntegrationSuite` (opt-in `SCHOLARCHAIN_AWS_IT=true`).

### Phase 08 additions

Sources, in `docs/agent-runs/phase-08/`: `step-02a-storage-engineer-report.md`,
`agent-report.md` and `output-local-ingestion.md`.
Full description: [docs/embeddings.md](../../docs/embeddings.md).
The module now has 16 source files; `DocumentRenderer`, `WorkQueue` and
`LocalIngestion` are new.

- `DocumentRenderer` (port, implemented in rag): `templateVersions`, `renderSummary`,
  `renderAbstract` and a final `render(SourceRecord)` that turns a throwing renderer
  into a `Left`.
  Types `RenderedChunk`, `RenderedDocument` and `SourceRecord`.
- `VectorLoader.loadRendered(runId, RowSource[SourceRecord], renderer, expectedRows)`.
  `load` stays for ready documents.
  Text comes only from the renderer, one vector per chunk.
- `SemanticDocument.fromEvidence(e, rendered)` and `fromAbstract(a, rendered)` return
  one document per chunk.
  The schema is `semantic-doc-v2` and the loader logic `vector-load-v2`.
- `VectorLoadResult` gains `chunks`, `putBatches`, `retries`, `failureRecords` and
  `queueHighWater`.
  `VectorLoaderSettings` gains `queueCapacity` and `maxFailureRecords`.
- `WorkQueue` trait with `InMemoryWorkQueue(capacity)`.
- `RowSource.jsonLines` and `jsonLinesDir` take `dropFields`; `RowSource.concat` and
  `RowSource.filter` are new.
- `EmbeddingVersion.id` (`<modelId>@<dimension>`) and `Embedder.versionId`.
- `RunLedger.recordFailure(..., times: Int = 1)`.
- `LocalIngestion.run(cfg, runManifestPath, renderer, settings, embedder, stores)`
  returns an `IngestionReport` with `toMarkdown`, and `LocalIngestion.localConfig()`.
  `IngestionSettings` defaults: batch 100, queue capacity 1,000, 4 attempts, 25 query
  samples per index, topK 10, and an optional deterministic `maxUnitsPerIndex`.
- Filterable metadata gained `template`, `chunkOrdinal` and `chunkCount`.

## Done

- Step 01 (T1): ports, fakes, S3 layout and artifact writer, S3 and DynamoDB adapters,
  six DynamoDB tables with written access patterns, conditional writes and the TTL
  decision (QuerySession only).
  `sbt storage/test`: 33 passed.
- Step 02 (T2): Neptune CSV and load manifests from typed core rows, 14 allowlisted
  parameterized read templates, Neptune HTTP and loader adapters, coauthor-edge endpoint
  and evidence validation, and a fixture shaped like the Phase 06 export.
  `sbt storage/test`: 82 passed (49 new).
- Step 03 (T3): S3 Vectors adapter, five-index vector loader, Bedrock embedder,
  researcher-metrics loader, Neptune file job, idempotent reload of every loader and the
  gated AWS suite.
  `sbt storage/test`: 153 passed, 1 ignored (71 new).
- Repair round 1 (R1-1, `docs/agent-runs/phase-07/repair-01-storage-engineer.md`):
  `sbt storage/test` 158 passed, 0 failed, 1 ignored.
  The full `sbt check` after R1-2 exited 0 in 305 s (158 and 1 ignored, 119, 68, 98, 33,
  119 as printed, `docs/agent-runs/phase-07/output-sbt-check-r1.txt`).
- Full `sbt check` (step 04, exit 0, 312 s): storage 153 passed and 1 ignored.
  The totals are printed per project without names; the storage line is identified from
  the suites that precede it (`docs/agent-runs/phase-07/output-sbt-check-step04.txt`).
- Required tests covered, per the step 03 report: serialization round trips,
  idempotent reload, missing and malformed metadata, duplicate vector keys, edge endpoint
  and evidence validation, conditional writes, no long text in vector metadata, and the
  integration suite off by default.
- All adapters were proven against in-memory fakes and a local `com.sun.net.httpserver`
  stub only.

### Phase 08 done

- `sbt storage/test`: 179 passed, 0 failed, 1 ignored (158 and 1 ignored before; 20 new
  tests in `RenderedLoadSuite` 15 and `LocalIngestionSuite` 5).
- Bounded embedding queue (manifest-driven batch with an in-process queue, not SQS),
  batches of 100, S3 source, chunk and document objects with a pointer, skip by hash and
  model version, refusal of one key for two texts, failures and retries in RunLedger.
- Local ingestion of `run-ccf619c509611f06` once, at 8,000 rows per type: 28,527 of
  223,953 rows, 31,341 chunks, 0 rejected, 0 duplicates; the second identical pass wrote
  0 and skipped all 31,341
  (`docs/agent-runs/phase-08/output-local-ingestion.md`).
- Full `sbt check` exited 0 (362 s); the storage line printed 179 passed, 1 ignored.
- Phase 08 repair R1-2: `LocalIngestion` now scans the export and fails before any load
  when the rows read differ from the load manifest ("export does not match its load
  manifest"), in both full and subset modes.
  Report counts (`totalRows`, `selectedRows`) come from the rows read, not the manifest.
  The `queueCapacity` doc now says it bounds only the documents waiting, and that the
  `seen` fingerprints and the `objects` memo grow with the keys of the load.
  `LocalIngestion.run` keeps its signature.
  `sbt storage/test` passed 184, 0 failed, 1 ignored (`LocalIngestionSuite` 10 tests);
  the full `sbt check` exited 0 (356 s).

## Not done

- The real Phase 06 Parquet run was NOT loaded through the storage code (no Parquet
  reader).
- The gated AWS integration suite was NOT RUN; no AWS call was made and no cloud resource
  exists from this module.
- OpenSearch (not wanted), the SQS queue adapter and the embedding-worker wiring
  (service-engineer, Phase 10), `ListVectors`, query pagination beyond 100 hits and the
  `s3.json` manifest loader.
- `RESOLVES_TO`, `DERIVED_FROM` and `SUPPORTED_BY` relationships and their nodes are not
  exported by Phase 06, so they are not loaded.
- Vector search quality was not measured.

### Phase 08 not done

- The full-export run of 223,953 rows: about 2.5 GB estimated for 226,807 chunks (351 MB
  for 31,341 chunks, about 11.5 KB per chunk), not measured, likely over a 2 GB heap.
- Bounded AWS ingestion of 10,000 vectors, query latency on S3 Vectors and real cost:
  NOT RUN until the cloud phases.
- The SQS adapter: not implemented; `WorkQueue` is the seam.
- The retry and rejection paths were not exercised by real data (0 retries, 0 rejects);
  unit tests only.

## Known issues

- Parquet reader gap (needs a human choice): the Phase 06 export is Parquet and `storage`
  depends only on `core`.
  Options: build-engineer adds a Parquet dependency, spark-engineer also writes JSON
  lines, or a reader comes later.
  The future reader must yield `EvidenceItem` (extra columns `docType` and `windowLabel`
  dropped) and `ResearcherMetrics` (extra columns `maximumTeamWorkCount` and
  `inLargestComponent` dropped), because strict JSON-lines decoding rejects unknown
  fields.
  Expected counts if loaded, from manifests only: 316 vectors, 162 metrics rows, 205
  nodes, 409 edges.
- Spark export gaps (spark-engineer, if the human wants them): no `institutionId` in the
  semantic summaries, and no paper-abstract rows.
  paper-abstract-v1 is tested on fixtures only; its source is Hadoop J02 `abstracts`,
  absent from the local Hadoop run.
- Binary S3 payloads are unsupported: `HttpResponse.body` is a String, so `get` is UTF-8
  text only and detects a binary object by hash mismatch.
  Reading Parquet or gzip through the port needs a byte body (domain-engineer).
- No throttling retry in the DynamoDB and S3 adapters: a
  `ProvisionedThroughputExceededException` is returned as a failure.
  The caller picks `AwsHttp.maxAttempts`.
- Served-run invariant (Review 1 MAJOR-1, minimum repair): one served run per Neptune
  cluster and per vector index.
  A new run's data is loaded only after the human cleans or rebuilds the cluster and the
  index.
  A reload under a new run does not remove the old run's nodes, edges or vectors, and an
  unchanged vector keeps the first run's `runId` and `textUri`.
  The misleading comments in `NeptuneAdapters.scala` and `VectorLoader.scala` were
  corrected in repair round 1.
  Full run scoping (run-scoped templates and vector reads) is carried forward to the
  human gate.
- Cleanup (Review 1 MINOR-2): a run's semantic-context prefix may be deleted only
  together with the vectors whose `textUri` points into it.
  The load report holds counts and rejections only, not the vector keys.
- A replay that repairs earlier rejections writes a new report, but the Succeeded ledger
  stage still names the first report (Review 1 MINOR-6, carried forward; the code fix
  is not made).
- A missing input directory now gives one visible malformed-row rejection
  (`RowSource.jsonLinesDir` yields one `Left`), not an empty load.
  The stage still records Succeeded; `expectedRows` makes it fail.
  The `_SUCCESS` rule of NIT-4 of Phase 05 is not implemented.
- Gated AWS suite: the bound is 25 HTTP attempts including retries, enforced by a
  counting transport; the opt-in variable for the test index is
  `SCHOLARCHAIN_IT_TEST_VECTOR_INDEX` (a dedicated test index; a production index name is
  refused before any call).
  Still NOT RUN.
- Wiring requirement: no storage code builds `AwsHttp`.
  Phase 10 and cloud callers must build `AwsHttp` with `maxAttempts` 1 when a
  `RetryPolicy` wraps the call, otherwise attempts multiply.
- The reports and logs zones use `curatedBucket` because `StorageConfig` has no field for
  them (infra-engineer to confirm).
- QuerySession TTL (7 days) is a constructor default; the config has no field.
  The Neptune loader role ARN is a constructor argument for the same reason (domain-
  engineer may add fields).
- Terraform contract for infra-engineer (Phase 12): tables with string keys `pk` and
  `sk` and TTL attribute `ttl` on QuerySession only; each vector index created with
  `float32`, the configured dimension, `cosine` and non-filterable keys exactly
  `VectorIndexes.NonFilterable` (`textUri`, `textSha256`, `evidenceId`, `sourceUri`);
  IAM for `s3vectors:PutVectors`, `GetVectors`, `QueryVectors` and `bedrock:InvokeModel`.
- Concurrency: `PutVectors` is an upsert guarded by a read then write, so two vector
  loaders racing on one key can interleave; run one at a time.
- Loaders and the Neptune exporter hold all rows of a load in memory.
  That is fine at the sample scale (hundreds of rows) and not for a full-DBLP run.
- Everything that talks to AWS is proven only against stubs; request shapes, the Bedrock
  model-ID path signing, `GetVectors` omitting absent keys and the 409 mapping are
  unverified against live services (list in
  [service-limits.md](../../docs/service-limits.md)).
- `AwsHttp` has one request timeout per client, so `NeptuneConfig.queryTimeoutMs` is not
  applied per request.
- Orphan text objects can remain after a failed `PutVectors` (the text is stored before
  the embedding call); there is no deletion port by design.
- An embedding model change needs a new index version; the loader rejects instead of
  overwriting.
- Bedrock embedding calls are sequential, one per document; throughput against the
  Bedrock quotas is untested.
- Process: the harness moves `sbt` to the background at 180 s, and guard-dangerous gives
  false positives on the test-marker task name (Phase 11 items).

### Phase 08 known issues

- Duplicate-key semantics across queue windows: a key already written in an earlier
  window is not retracted if a later conflicting copy arrives; only the new copy is
  rejected.
- The loader skips the chunk-ID formula check for multi-chunk documents; rag owns it.
- Storage refuses any chunk whose text repeats a metadata value of 40 or more
  characters (the reason `tpl-paper-abstract-v2` exists).
- Pass 2 of the local run takes longer than pass 1 (65 s against 54 s) because it re-reads
  and re-renders the 1.1 GB JSON-lines to compare hashes.
- The `System.gc()` heap figure is rough.
- The Phase 07 Parquet reader gap is closed by the Phase 08 JSON-lines export for the
  vector loader only.
  The Neptune and DynamoDB loaders have not yet been run on the JSON-lines export.
  A contract test for them is carried forward to storage-engineer.
- S3 Vectors limits were not re-read in Phase 08.

## Dependencies

- Project: `core` (uses `scholarchain.core.aws` for `AwsHttp`, SigV4 and credentials, and
  `Ids`, `Hashing`, `Config`, `Model`, `Validate`, `JsonLines`).
- No AWS SDK and no new library; JSON is uPickle only.
- Bundled into the services jar through `rag` and `services`; it is not a job jar.
  If loaders later run as their own EMR step or task, the project needs assembly settings
  and an entry in `packageJobs` (`build.sbt`, packaging comment).

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
