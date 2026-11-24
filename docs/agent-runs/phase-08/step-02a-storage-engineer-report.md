HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier (role default, as the work order says)

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 08 step 02a. Define the DocumentRenderer port and take loader text only from it. Add a bounded embedding work queue, vector write batches, S3 source and rendered text with a pointer and compact metadata, skip by hash plus model, refusal of one key for two texts, and failures and retries in RunLedger. Loaders read the step 0 JSON-lines export, and storage gets a local ingestion entry point.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/DocumentRenderer.scala
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/WorkQueue.scala
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/LocalIngestion.scala
- /home/ajcreator/scholarchain/tests/module-unit/storage/RenderFx.scala (stub renderer and fixture rows)
- /home/ajcreator/scholarchain/tests/module-unit/storage/RenderedLoadSuite.scala (15 tests)
- /home/ajcreator/scholarchain/tests/module-unit/storage/LocalIngestionSuite.scala (5 tests)

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/VectorLoader.scala (rewritten: new SemanticDocument fields and builders, bounded queue loop, retries and ledger records)
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/Loaders.scala (`RowSource.jsonLines` and `jsonLinesDir` take `dropFields`; new `RowSource.concat` and `RowSource.filter`)
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/Embedder.scala (`EmbeddingVersion`, `Embedder.versionId`)
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/Tables.scala (`RunLedger.recordFailure` gains `times: Int = 1`)
- /home/ajcreator/scholarchain/tests/module-unit/storage/VectorSuite.scala and LoaderSuite.scala (tests adapted to the new behavior, see TEST RESULTS)
- Not touched: MODULE.md, docs, other modules. Formatting used only `storage/scalafmt` and `storage/Test/scalafmt`.

COMMANDS RUN (observed results):
- `pgrep`/`free -m` before sbt: no other sbt or JVM was running.
- `timeout 900 sbt -batch "storage/compile"`: success.
- `timeout 1500 sbt -batch "storage/scalafmt" "storage/Test/scalafmt" "storage/test"`, first run: 178 passed, 1 failed. SemanticDocumentSuite failed because my test expected the identity-mismatch message and the self-consistency check ran first. I reordered the checks in `fromEvidence` and `fromAbstract`.
- The same command, second run: `Passed: Total 179, Failed 0, Errors 0, Passed 179, Ignored 1`, success, 29 s.
- Not run: `sbt check` (step 03), any AWS call, any run on the real Spark export (that is step 02b).

TEST RESULTS:
- `sbt storage/test`: 179 passed, 0 failed, 1 ignored (the gated AWS suite). Before this step it was 158 passed, 1 ignored.
- 20 new tests: RenderedLoadSuite 15, LocalIngestionSuite 5. SemanticDocumentSuite also grew from 5 to 6 tests, with the old two row-to-document tests replaced.
- The new tests cover every behavior in the work order:
  - Port use with a stub renderer.
  - A renderer `Left`, a throwing renderer and an inconsistent rendering are rejections.
  - The embedder sees only rendered text.
  - A document of three chunks gives three vectors.
  - Queue bounds, with high water 7 at capacity 7.
  - Batches of 100, 100, 50, 50 across interleaved indexes.
  - Pointer and metadata key set.
  - Skip by hash and model.
  - Another model is refused.
  - Key conflict refusal, with vectors untouched.
  - Duplicate keys.
  - Idempotent second pass (0 embeds, 0 puts, no new objects, report `Unchanged`).
  - Ledger failures and retry counts.
  - The failure-record cap with an overflow record.
  - A failed stage retried on replay.
  - Expected row count refused before any write.
  - JSON lines with `extraFields` dropped; unknown fields and garbage still refused.
  - The entry point on a Spark-shaped fixture: counts, second pass all skipped, rejects with reasons, bounded deterministic subset, multi-chunk, and missing or mismatched manifest errors.
- Changed existing tests (the right behavior, so I changed the tests and not the code):
  - `embeddingModel` is now `hash-embed-v1@16`.
  - The metadata key set gains `chunkOrdinal` and `chunkCount`.
  - The row-to-document tests now need a rendering.
  - The abstract load test now goes through the renderer.

VALIDATION RESULTS:

Port (exactly rag's requested signature; nothing changed):
- Code in `scholarchain.storage`, file `DocumentRenderer.scala`:
```scala
final case class RenderedChunk(chunkId: String, ordinal: Int, start: Int, end: Int, text: String, textHash: String) derives ReadWriter
final case class RenderedDocument(docType: String, entityId: String, window: Option[TimeWindow], index: String, template: String, chunkPolicy: String, sourceVersion: String, evidenceId: Option[String], text: String, textHash: String, chunks: Vector[RenderedChunk]) derives ReadWriter
trait DocumentRenderer:
  def templateVersions: Map[String, String]
  def renderSummary(row: EvidenceItem): Either[String, RenderedDocument]
  def renderAbstract(row: PaperAbstract): Either[String, RenderedDocument]
  final def render(src: SourceRecord): Either[String, RenderedDocument]  // added: catches a throwing renderer, turns it into Left
enum SourceRecord { case Summary(row: EvidenceItem); case Abstract(row: PaperAbstract) }  // plus def id
```
- Hashes are `Hashing.tagged` (`sha256:` plus hex).
- What rag must do (01b):
  - Delete its own `RenderedChunk` and `RenderedDocument`.
  - Make `SemanticRenderer` extend `DocumentRenderer`. It already has all three methods.
  - Optionally have `EmbeddingModelVersion.id` call `scholarchain.storage.EmbeddingVersion.id(modelId, dimension)`.
- The loader refuses a rendering unless all of these hold (these are checks rag's output must pass; they match what rag's report says it produces):
  - The identity (docType, entityId, window, and evidenceId if set) matches the source row.
  - `textHash` and every chunk hash match their text.
  - Chunk ordinals are 0..n-1.
  - Every chunk is the exact span `[start, end)` of the text.
  - Chunk IDs are well formed and distinct.
  - Template and chunkPolicy are non-empty.
  - A one-chunk document has `chunkId == Ids.chunk(entityId, sourceVersion, docType, 0, text)`.
  - A multi-chunk ID formula is not checked by storage; it stays rag's.

Choices:
1. Work queue: manifest-driven batch plus a bounded in-process queue, not SQS.
   - Reasons: no AWS or cloud cost this phase; every step is idempotent, so a replay needs no at-least-once broker; an SQS queue, dead-letter queue and IAM would be infra-engineer's.
   - `WorkQueue` is a trait with `InMemoryWorkQueue(capacity)`; `offer` returns false when full and `drain(max)` empties it. The loader reads rows lazily, fills the queue, drains it into per-index pending batches, and so never holds more than `queueCapacity` plus one partial batch per index.
   - The Phase 10 worker can implement the same trait over SQS.
2. Model version: `<modelId>@<dimension>`.
   - The formula lives in `EmbeddingVersion.id` in `Embedder.scala`, and `Embedder.versionId` is final.
   - The local fake is `hash-embed-v1@256` (`@16` in unit tests). rag's `EmbeddingModelVersion.id` gives the same string.
   - Metadata `embeddingModel` and the skip comparison both use `versionId`.
3. Chunk ID check: `VectorLoaderSettings.chunkSourceVersion` now uses the document's ordinal and is skipped for multi-chunk documents. The renderer owns that formula, and `fromEvidence` and `fromAbstract` check the one-chunk formula themselves.
4. Duplicate keys: an identical copy is dropped and counted. A conflicting copy is rejected, and the first copy is also rejected if it has not been written yet. A first copy already written stays (never overwritten) and only later copies are rejected. This follows from streaming; the old "reject all copies" holds within a queue window and the tests confirm it for the default sizes.

Metadata schema (S3 Vectors; the vector key is the chunk ID, `c:` plus 20 hex):
- Filterable: `documentType`, `entityId`, `source` (source dataset), `runId`, `snapshot`, `embeddingModel` (`<modelId>@<dimension>`), `window`, `windowStartYear`, `windowEndYear` (not on paper-abstract), `researcherIds` (at most 16), `communityId`, `topicId`, `institutionId`, `workId` (abstract), `pathId` (path), `template`, `chunkOrdinal`, `chunkCount`.
- Non-filterable, unchanged from Phase 07 and so no Terraform change: `textUri` (the S3 pointer), `textSha256` (text hash), `evidenceId`, `sourceUri`.
- Compactness: no text-like key, no string over 512 bytes. The S3 Vectors limits are as in Phase 07: 50 keys, 2 KB filterable, 40 KB total.

S3 objects, all create-only with content-hash comparison on replay, in `semantic-context/<dataset>/run_id=<run>/`:
- `<docType>/<chunkId>.json`: the SemanticDocument for the chunk, with the rendered chunk text, template, chunkPolicy, ordinal, chunkCount, documentTextHash and pointers. This is `textUri`.
- `<docType>-source/<sha40>.json`: the authoritative source row (EvidenceItem or PaperAbstract JSON).
- `<docType>-document/<sha40>.json`: the whole RenderedDocument, written only when there are two or more chunks.
- Unchanged vectors write no object. The semantic-doc schema moved to `semantic-doc-v2` and the loader logic to `vector-load-v2`.

Batch and retry settings:
- `batchSize` defaults to 100 (limit 1..100 from GetVectors; PutVectors allows 500).
- `queueCapacity` defaults to 1000. `maxFailureRecords` defaults to 500.
- `RetryPolicy` defaults to 4 attempts, 200 ms doubling, 5 s cap, transient errors only. The local entry point uses 4 attempts with no sleep.
- GetVectors, PutVectors and embedding calls are all retried through `RetryPolicy`.
- Ledger: every rejection becomes `recordFailure(runId, "load-vectors", "reject:<input>", reason)`.
  - Items beyond `maxFailureRecords` are one `reject:overflow` record carrying the count.
  - Every retried call becomes `retry:<PutVectors|GetVectors|Embed>:<index>` with the retry count as `retryCount`.
  - A replayed rejection counts up (1, then 2).
  - The stage entry's `attempt` still counts stage retries.
- Service limits: unchanged from `docs/service-limits.md`, checked 2026-11-23 for Phase 07. I did not re-fetch them (see RISKS).

PUBLIC API (new or changed):
- `VectorLoader.loadRendered(runId, source: RowSource[SourceRecord], renderer: DocumentRenderer, expectedRows: Option[Long] = None): Either[String, VectorLoadResult]`.
- `VectorLoader.load(runId, RowSource[SemanticDocument], expectedRows)` is kept for ready documents.
- `SemanticDocument.fromEvidence(e, rendered)` and `fromAbstract(a, rendered)` return `Vector[SemanticDocument]`, one per chunk. The raw text paths are gone.
- `VectorLoadResult` gains `chunks`, `putBatches`, `retries`, `failureRecords` and `queueHighWater`.
- `VectorLoaderSettings` gains `queueCapacity` and `maxFailureRecords`.
- `RowSource.jsonLines` and `jsonLinesDir` take `dropFields: Set[String]`; `RowSource.concat` and `RowSource.filter` are new.
- `RunLedger.recordFailure(..., times: Int = 1)`.

Entry point, exact signature (rag calls it from a runner in rag test scope):
```scala
LocalIngestion.run(
  cfg: ScholarChainConfig,
  runManifestPath: java.nio.file.Path,   // <out>/run-manifests/run-<id>.json
  renderer: DocumentRenderer,
  settings: IngestionSettings = IngestionSettings(),
  embedder: Option[Embedder] = None,     // default HashingEmbedder(cfg.vectors.dimension)
  stores: LocalStores = LocalStores.inMemory()
): Either[String, IngestionReport]
LocalIngestion.localConfig(): Either[String, ScholarChainConfig]   // placeholder bucket and table names, dimension 256
IngestionSettings(maxUnitsPerIndex: Option[Int] = None, batchSize = 100, queueCapacity = 1000,
  retry = RetryPolicy(4, 1L, _ => ()), querySamplesPerIndex = 25 /*1..100*/, queryTopK = 10)
```
- Call for the real run: `LocalIngestion.run(LocalIngestion.localConfig().toOption.get, Path.of("/home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/run-manifests/run-ccf619c509611f06.json"), renderer, IngestionSettings(maxUnitsPerIndex = Some(n)))`.
- What it does:
  - Finds the run folder beside the manifest, reads `load-manifests/s3vectors.json` and checks it against the config.
  - Reads `jsonl/semantic-summaries` and `jsonl/paper-abstracts`, dropping the `extraFields` the manifest declares.
  - Creates the bucket and five indexes in the in-memory vector store.
  - Loads twice: pass one, then an identical pass two that must skip everything.
  - Samples query latency per index: unfiltered and filtered by `entityId`, plus whether the sampled key is in the top K.
- `IngestionReport.toMarkdown` gives the text for `output-local-ingestion.md`. Its fields:
  - `counts`: total rows and ingested rows per document type.
  - `first` and `second` passes: counts per index, rows, chunks, written, unchanged, duplicates, rejected records with reasons, PutVectors batches, retries, embedder calls, elapsed milliseconds, vectors per second and the stored report URI.
  - `latency`, `ledger`, `objectsByKind`, and heap MB after pass one.
- Bounded subset (`maxUnitsPerIndex`): the n source rows with the smallest SHA-256 of the row key per document type (chunk or evidence ID, or work ID). It is independent of file order, so the same rows are chosen every time. The report shows both the total rows and the ingested rows, and a row can become several chunks.
- A malformed line or an undecodable row is a rejection in the pass report, never a silent drop. The entry point returns `Left` only for a fatal error: missing or mismatched manifest, a stage failure, and so on.

ASSUMPTIONS:
1. The run folder is `<out>/spark/snapshot=<snapshot>/run_id=<id>/`, with `<out>` being two levels above the manifest file and the snapshot read from the run manifest.
2. Summary source rows are decoded with `Validate.evidence` and the `extraFields` from `s3vectors.json`. The abstract extraFields come from its `abstracts.source.jsonLines` block. A missing `abstracts` block means no abstracts.
3. The JSON-lines `null` for an Option decodes through core `JsonLines`, as in rag's fixtures. Not yet shown on the full real export; step 02b will show it.
4. "Meaningful units" are source rows (documents); chunks are reported separately.
5. I added `chunkOrdinal`, `chunkCount` and `template` to the filterable metadata; this stays within the existing index definitions.
6. The ledger failure records are capped at 500 per load, so a large reject set cannot flood DynamoDB.

RISKS:
- Not done: nothing run on the real export; no `sbt check`; no AWS.
- Memory on the real run is NOT measured. My unmeasured estimate for the full 226,807 chunks in a 2 GB heap is that it will not fit.
  - Each vector is 256 boxed floats, plus about three JSON text objects per document, plus metadata.
  - rag's runner should start with `maxUnitsPerIndex` of a few thousand per type (for example 3,000, about 15,000 source rows, still above the 10,000-unit mark) and read `heapUsedMbAfterFirstPass` from the report before raising it.
  - Even unset, the subset logic holds only keys, so selection costs little.
- The real-data time is unknown: pass 1 with a subset reads the 1.1 GB JSON-lines twice (selection pass, then load), and pass 2 reads and renders again.
- `System.gc()` is called once to measure the heap, so the heap figure is rough.
- The real S3 and S3 Vectors limits were not re-read in this step; I made no change that relies on a new limit (batch 100, same metadata limits as Phase 07). The cited limits are unchanged from `docs/service-limits.md`, but I did not re-verify them with the aws-docs tools this step.
- A key already written in an earlier window is not retracted if a later conflicting copy arrives; only the new copy is rejected. This affects only duplicate source rows spanning the queue.
- State left behind: nothing outside the repository; temp directories created by tests are deleted by them.
- Notes for MODULE.md (documentation-engineer):
  - Public API additions as listed above; the module now has 16 source files (`DocumentRenderer`, `WorkQueue`, `LocalIngestion` added).
  - Tests in `RenderedLoadSuite`, `LocalIngestionSuite` and `RenderFx`; `sbt storage/test` 179 passed, 1 ignored.
  - Done: port, bounded queue and batches, S3 source, document and chunk objects with pointer, hash-plus-model skip, conflict refusal, ledger failures and retries, JSON lines with `extraFields`, local ingestion.
  - Not done: real-export run, AWS ingestion, SQS adapter.
  - Known issue: duplicate-key semantics across queue windows (above).

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: Step 01b, rag-engineer, run fresh: delete rag's local `RenderedChunk` and `RenderedDocument`, make `SemanticRenderer` extend `scholarchain.storage.DocumentRenderer`, and write the test-scope runner that calls `LocalIngestion.run` with the call above and a `maxUnitsPerIndex`; then `sbt rag/test`. After that, documentation-engineer updates STATUS.md, then step 02b (storage-engineer) runs the real ingestion and writes `output-local-ingestion.md` from `IngestionReport.toMarkdown`.
