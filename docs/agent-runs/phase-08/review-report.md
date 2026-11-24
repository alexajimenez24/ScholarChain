HARNESS: claude-code
MODEL: opus, effort high (high tier, `.agents/models.json` claude.high; running as Claude Opus 5.5, claude-opus-5-5)

# Phase 08 Review 1

**Verdict: APPROVE WITH CARRIED-FORWARD ITEMS.** There are no CRITICAL or MAJOR findings, so the addendum does not call for a Review 2. There are 5 MINOR findings and 6 NITs. MINOR-1, MINOR-2 and MINOR-3 are cheap to fix in the one repair round. If MINOR-1 or MINOR-2 is fixed, source changes and `sbt check` must run again once.

## Gate items checked (verified against the files)
- **Five indexes.** All five are present in the config and in `s3vectors.json`, and the local run has rows in each one.
- **Bounded queue.** It is a manifest-driven batch plus `InMemoryWorkQueue`, not SQS. The reason is stated in `WorkQueue.scala` and in the docs.
- **Write batches.** Batches are 100 by default and limited to 1..100. The tested split is 100/100/50/50.
- **Source and rendered text in ordinary S3.** Each chunk object is the `textUri`, plus a `-source` object and, for multi-chunk documents, a `-document` object.
- **Vector metadata.** The vector holds only the pointer and compact metadata, with every listed field (`VectorLoader.scala:242-279`). The 40-character span rule and the 512-byte cap apply.
- **Skip and refusal.** A chunk is skipped only when both text hash and model version match (`:716-727`). One key for two texts is refused, and there are tests in storage and rag.
- **RunLedger.** Rejections and retry counts are recorded, with a cap and an overflow record (`:642-655`).
- **rag side.** Templates are versioned and adopt Spark's text after a conformance check. Chunking `chunk-v1`, `embedding-version-v1`, the smoke tests with known neighbours and `semantic-context-v1` through the S3 pointer with hash checks are all present. `score` is the only number in the format.
- **Phase 07 gate items in step 0.** All four are done. I checked the real `neptune.json`: it lists all 4 windows with `exportedWindows`. The `paper-abstracts` JSON lines hold 3,240 + 3,052 = 6,292 lines, matching the manifest.
- **Reporting.** Throughput, rejects, duplicates, latency, index counts, metadata schema and model version are reported, and the AWS items are marked NOT RUN.

## Other checks
- **Module boundary.** rag depends on core and storage (`build.sbt:266-267`), and storage never imports rag.
- **Ownership.** I found no cross-owner write and no cross-owner reformatting (`Tables.scala` changes only `recordFailure`).
- **Secrets.** No AWS call and no secret. `OPENALEX_API_KEY` is read only from the environment and is never logged or written to a manifest.
- **Driver collects.** The Spark collects are bounded: partitions, at most 6 reason codes, and one row per window. Path selection uses distributed window functions only. `multiSourceBfs` gives one row per (src, node), so no pair is requested twice.
- **`sbt check`.** It exited 0 (`output-sbt-check-step03.txt:413-414`). No source file was changed after it.
- **Checksums.** The 25k `dblp-sample.xml` and `crossref-works.jsonl` sha256 values match the manifest.

## Findings

**MINOR-1. Chunking accepts maxChunks+1 chunks.**
- **Where:** `modules/rag/src/main/scala/scholarchain/rag/Chunking.scala:61`, `while !done && count <= p.maxChunks`.
- **Evidence:** I ran a direct Python copy of the loop on `ChunkPolicy("chunk-test",50,25,10,8)`. A text of `"z"*370` gives 9 spans and is accepted; `"z"*371` is refused. Under chunk-v1, a 33-chunk text is accepted, for example a text with no whitespace of 57,801 to 59,600 units.
- **Docs that are wrong as a result:** `Chunking.scala:18`, `docs/embeddings.md:89-91` ("More than 32 chunks is refused") and `modules/rag/MODULE.md`.
- **Impact:** the bound is broken by one. Real data is not affected (the longest document has 17 chunks).
- **Owner:** rag-engineer.
- **Repair brief:**
  - Likely cause: the loop guard counts from 0 with `<=`.
  - Fix: use `count < p.maxChunks`.
  - Add a boundary test: with `small`, `"z"*330` gives 8 spans and `"z"*370` is refused.
  - What else could break: none of the golden spans, since every golden case is far below the limit.
  - Must stay green: `ChunkingSuite`, `TemplateSuite` (golden spans and IDs), `IngestionConformanceSuite`, `RetrievalSmokeSuite`.

**MINOR-2. LocalIngestion never compares rows read with the manifest, and in full mode reports manifest rows as "ingested".**
- **Where:** `modules/storage/src/main/scala/scholarchain/storage/LocalIngestion.scala:403-418` (`selectedRows = rows` from the manifest when there is no selection) and `:430` (`loadRendered` is called without `expectedRows`).
- **Failure input:** remove one `part-*.json` from `jsonl/semantic-summaries` and run without `--max-units-per-index`. The table still says "ingested rows" = 217,661, there is no error, and only the pass line "source rows" shows the shortfall. `m.problems` (`Loaders.scala:198-210`) only checks the manifest against itself.
- **Impact:** a truncated export can load and look complete in the evidence file that the human gate reads.
- **Owner:** storage-engineer.
- **Repair brief:**
  - With no subset, pass `expectedRows = Some(summary rows + abstract rows)`.
  - With a subset, fail when a type's scan total differs from its manifest rows.
  - Report selected rows from what was actually read.
  - Must stay green: `LocalIngestionSuite`, `RenderedLoadSuite`, `IngestionConformanceSuite`.

**MINOR-3. Unsupported claim that the Parquet reader gap is "closed for the six loaded tables".**
- **Where:** `docs/storage.md:366-369` and `modules/storage/MODULE.md:274-275`.
- **Evidence:** only the vector path reads the JSON-lines export with the manifest's `extraFields` (`LocalIngestion.scala:272-280, 346`). No storage code or test reads the `jsonLines` blocks of `neptune.json` or `dynamodb.json` into the Neptune or DynamoDB loaders. A grep for `extraFields` and `dropFields` finds only LocalIngestion and tests.
- **What does exist:** format compatibility for graph-nodes, graph-edges, coauthor-edges and researcher-metrics is shown only by Spark's PipelineSuite, through core decode.
- **Impact:** the human may think the Neptune and DynamoDB loads are proven on the real export before the first cloud load (Phase 07 gate).
- **Owners:** documentation-engineer (wording) and storage-engineer (carried forward: a contract test that loads `neptune.json` and `dynamodb.json` JSON-lines with `extraFields` through the Neptune and DynamoDB loaders).
- **Repair brief:**
  - Reword to "closed for the vector loader; Neptune and DynamoDB loaders not yet run on the export".
  - Must stay green: no code change is needed for the wording.

**MINOR-4. A stray file from the failed S2 attempt 1 is untracked and not git-ignored.**
- **Where:** `modules/hadoop/datasets/samples/local/2026-11-24/hadoop-out/run-manifests/run-8e1abb4c22da9c06.json` and its `.crc`.
- **Impact:** a `git add -A` would commit a run manifest with relative paths inside the hadoop module.
- **Owner:** hadoop-engineer (the file is in its territory). It is already listed in STATUS as an open item.
- **Repair brief:**
  - Remove the folder (hadoop-engineer, or the human if the "agents do not delete data" reading applies).
  - Likely cause: the forked runner resolved relative paths against `modules/hadoop` and wrote the run manifest before checking its inputs.
  - Optionally make `LocalRunner` refuse relative paths.
  - Must stay green: hadoop 98 and adversarial 68 tests.

**MINOR-5 (process, disclosed). Step 00 deviated from the step-down rule.**
- **Where:** `step-00-spark-engineer-report.md`, assumption 6.
- **Evidence:** after the out-of-memory failure at 100k, spark-engineer retried at full scale with 32 shuffle partitions instead of stepping down at once. The retry cost another 90 minutes.
- **Status:** disclosed in the report, `run-log.md`, `docs/data-lineage.md` and STATUS.
- **Owner:** the human decides. No repair is needed.

**NIT-1.** The `keyRule` text for `abstracts` in `s3vectors.json` is out of date. It says "the loader makes the chunk ID (core Ids.chunk of workId, ordinal 0, text)" (`modules/spark/src/main/scala/scholarchain/spark/Export.scala:750`, confirmed in the real manifest). In fact rag's renderer makes the IDs, with source version `tpl-paper-abstract-v2`, the rendered text, and the multi-chunk formula for 83 documents. Owner: spark-engineer.

**NIT-2.** `docs/data-lineage.md:102` says "00b failed two invariants". 00b had 1 nonzero invariant (`componentsNotConverged`, `step-00b` report lines 4 and 141). The empty path index was not an invariant then; `pathRequestedNotFound` was added in 00d. Owner: documentation-engineer.

**NIT-3.** `docs/embeddings.md:43` and `STATUS.md:1248` state "does not fit in a 2 GB heap" as a fact, though it was not measured. The basis is also out of date:
- The 15 KB per chunk figure came from 16 MB / 1,071 chunks.
- 02b measured 351 MB / 31,341 chunks, about 11.5 KB per chunk.
- That gives about 2.5 GB, which is still likely over 2 GB, but it is an estimate.

Owner: documentation-engineer.

**NIT-4.** rag's `EmbeddingModelVersion.decide` (`EmbeddingVersion.scala:73-83`) checks the version mismatch first. The loader checks the text mismatch first (`VectorLoader.scala:716-727`). `decide` is not used by the loader, so the stated rule and the enforced rule can drift apart. Owners: rag-engineer and storage-engineer. Either align the order or have the loader use one shared decision.

**NIT-5.** The `VectorLoaderSettings.queueCapacity` doc (`VectorLoader.scala:334-336`) says "the loader never holds more than this plus one partial batch per index". In fact the `seen` fingerprints and the `objects` memo grow with every key of the load. The queue bounds only documents waiting. Owner: storage-engineer (wording).

**NIT-6.** `PROJECT_MEMORY.md` says "100 is about 2.4 times the rounds needed". 00d observed convergence in 47 iterations in 1950-2026, which is about 2.1 times. Owner: documentation-engineer.

## Test fairness
- I found no test that passes on broken behaviour for the gate items. The key-conflict, model-skip, pointer-read and ledger-retry tests each assert the stored state, not only a return value.
- One gap: the "too many chunks" test uses an overflow far past the limit, which is how MINOR-1 got through.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 08 Review 1, a fresh read-only review of the whole working tree against HEAD 1a727a1: gate items, correctness, tests, boundaries, ownership, secrets, addendum compliance, and docs against the reports.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `git status --porcelain`; `git diff --stat`; `git diff` of the spark, storage, core, docs, MODULE.md and test files.
- `find` and `du` of the untracked trees; `git check-ignore -v`.
- `grep`, `sed` and `wc` over source, tests, reports and STATUS.
- `find -newer output-sbt-check-step03.txt` (no source changed after the check).
- `python3 -I` direct copy of `Chunking.spans` (9 spans for "z"*370 with maxChunks 8; refused at 371).
- `sha256sum` of the 25k `dblp-sample.xml` and `crossref-works.jsonl` (both match the manifest).
- `python3 -I` read of the real `neptune.json`, `s3vectors.json` and `dynamodb.json`; `grep -c` line counts of the `paper-abstracts` JSON lines (3,240 + 3,052).
- `cat .agents/models.json`.
- No sbt run (the step 03 check was reused, and no source changed after it).
TEST RESULTS: No tests run by me. The full `sbt check` from step 03 exited 0 in 362 s, with 120 / 179 (1 ignored) / 63 / 68 / 98 / 45 / 119 passed and "14 project test runs passed".
VALIDATION RESULTS: APPROVE WITH CARRIED-FORWARD ITEMS. 0 CRITICAL, 0 MAJOR. MINOR-1 Chunking.scala:61 off-by-one (rag-engineer); MINOR-2 LocalIngestion.scala:403-418,430 no manifest row check (storage-engineer); MINOR-3 docs/storage.md:366-369 and storage MODULE.md:274 overclaim, plus a carried-forward Neptune/DynamoDB JSON-lines loader test (documentation-engineer, storage-engineer); MINOR-4 stray modules/hadoop/datasets/ (hadoop-engineer); MINOR-5 step-down deviation in step 00 (human decision). NIT-1 Export.scala:750 keyRule (spark-engineer); NIT-2 data-lineage.md:102 (documentation-engineer); NIT-3 embeddings.md:43 and STATUS.md:1248 (documentation-engineer); NIT-4 decide precedence (rag-engineer, storage-engineer); NIT-5 VectorLoader.scala:334-336 wording (storage-engineer); NIT-6 PROJECT_MEMORY ratio (documentation-engineer). Test fairness: no unfair test; the maxChunks boundary was not tested.
ASSUMPTIONS:
- The Python copy of the chunk loop is faithful to `Chunking.scala:52-95`. I compared them line by line; it was not confirmed by sbt.
- "Loaders read the step 0 JSON-lines export" was judged as met for the vector loader, with the Neptune and DynamoDB side treated as a docs overclaim (MINOR), not a gate failure, because Phase 08 is scoped to vectors.
- Summary vectors carry `source = SCHOLARCHAIN` (derived documents) as their source dataset. I accepted this as within the gate wording.
RISKS (what I did not review):
- The bodies of Spark PipelineSuite, ExportSuite and PathSelectionSuite beyond their names.
- Whether `Summaries.topInstitution` agrees with the `countedList` order.
- `datasets/scripts/fetch_samples.py` beyond secret and network handling (the diff is 342 lines), and `test_dataset_manifest.py`.
- The changed lines of VectorSuite and LoaderSuite.
- The SemanticContextSuite bodies, and `prompts/schemas/semantic-context-v1.json` against the encoder.
- `LocalIngestionRun`, `SparkShapedRun` and `RealRowsRender`.
- The hadoop, ER and core MODULE.md additions, `docs/hadoop-jobs.md` and `docs/entity-resolution.md`.
- No comparison with `~/ref-phase-08/`.
- No rerun of any pipeline or of sbt.
MANUAL ACTION REQUIRED: The human decides on MINOR-5 (the step-down deviation) and on removing `modules/hadoop/datasets/` (MINOR-4), and must not commit that folder.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The one repair round (repair-01*.md):
- rag-engineer: MINOR-1, then `sbt rag/test`.
- storage-engineer: MINOR-2 and NIT-5, then `sbt storage/test`.
- spark-engineer: NIT-1.
- documentation-engineer: MINOR-3, NIT-2, NIT-3 and NIT-6.
- hadoop-engineer or the human: MINOR-4.

Then one full `sbt check` if source changed. No Review 2 is needed (no CRITICAL or MAJOR). Open items carry forward to the human gate.
