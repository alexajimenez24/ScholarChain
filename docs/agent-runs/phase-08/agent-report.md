HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier (role default, as the work order says)

Step 02b is COMPLETE. The local ingestion of run-ccf619c509611f06 into all five indexes ran once, at N=8000 per type, with 0 rejections in both passes. The heap peaked at 351 MB of 2,048 MB, so I did not need the second attempt at 3,000. N=8000 ingested 28,527 of the 223,953 meaningful source rows (12.7%), which became 31,341 chunks. The 3,000-per-type floor (12,278 rows) and the 10,000-unit mark are both cleared.

Why N=8000: rag's smoke run measured about 15 KB per chunk (16 MB heap for 1,071 chunks). With 8000 per type I expected about 31,000 chunks, so about 0.5 GB. The measured 351 MB confirms it. All 226,807 chunks would be about 3.4 GB at that rate, so a full run still does not fit in 2 GB. I did not pad or enlarge the corpus.

The report file is /home/ajcreator/scholarchain/target/phase-08/output-local-ingestion.md (4,057 bytes, git-ignored). The orchestrator copies it to docs/agent-runs/phase-08/output-local-ingestion.md. I wrote nothing under docs/.

## OUTPUT-LOCAL-INGESTION
(verbatim `IngestionReport.toMarkdown` output)

```
# Local ingestion of run-ccf619c509611f06

- snapshot: 2026-11-24
- embedding model version: `hash-embed-v1@256` (local deterministic fake, no AWS call)
- templates: collaboration-community=tpl-collaboration-community-v1, collaboration-path=tpl-collaboration-path-v1, paper-abstract=tpl-paper-abstract-v2, researcher-profile=tpl-researcher-profile-v1, topic-institution-summary=tpl-topic-institution-summary-v1
- subset: smallest-SHA-256 8000 source rows per document type; selection took 32905 ms
- batch size 100, queue capacity 1000 (high water 1000), retry 4 attempts
- heap after the first pass: 351 MB used of 2048 MB max

## Meaningful units (source rows) and ingested

| document type | index | total rows | ingested rows | chunks present |
|---|---|---|---|---|
| collaboration-community | collaboration-community-v1 | 43826 | 8000 | 8002 |
| collaboration-path | collaboration-path-v1 | 278 | 278 | 278 |
| paper-abstract | paper-abstract-v1 | 6292 | 6292 | 6387 |
| researcher-profile | researcher-profile-v1 | 167600 | 8000 | 8000 |
| topic-institution-summary | topic-institution-summary-v1 | 5957 | 5957 | 8674 |
| total | | 223953 | 28527 | 31341 |

## Pass: first load

- source rows 28527, chunks 31341, written 31341, unchanged 0, duplicates dropped 0, rejected 0
- elapsed 53669 ms, 584.0 vectors written per second, 584.0 chunks handled per second
- PutVectors batches 315, embedder calls 31341, retries none, ledger failure records 0
- stored report s3://local-curated/logs/load-vectors/run_id=run-ccf619c509611f06/load-report-d5e318abf9bb277f.json (replay of an earlier stage: false)
  - collaboration-community-v1: present 8002, written 8002, unchanged 0
  - collaboration-path-v1: present 278, written 278, unchanged 0
  - paper-abstract-v1: present 6387, written 6387, unchanged 0
  - researcher-profile-v1: present 8000, written 8000, unchanged 0
  - topic-institution-summary-v1: present 8674, written 8674, unchanged 0

## Pass: second identical load (must skip everything)

- source rows 28527, chunks 31341, written 0, unchanged 31341, duplicates dropped 0, rejected 0
- elapsed 65160 ms, 0.0 vectors written per second, 481.0 chunks handled per second
- PutVectors batches 0, embedder calls 0, retries none, ledger failure records 0
- stored report s3://local-curated/logs/load-vectors/run_id=run-ccf619c509611f06/load-report-d5e318abf9bb277f.json (replay of an earlier stage: true)
  - collaboration-community-v1: present 8002, written 0, unchanged 8002
  - collaboration-path-v1: present 278, written 0, unchanged 278
  - paper-abstract-v1: present 6387, written 0, unchanged 6387
  - researcher-profile-v1: present 8000, written 0, unchanged 8000
  - topic-institution-summary-v1: present 8674, written 0, unchanged 8674

## Query latency (in-memory vector store, exact scan)

| index | queries | own key in top-K | p50 us | p95 us | max us | filtered p50 us |
|---|---|---|---|---|---|---|
| collaboration-community-v1 | 25 | 25 | 136585 | 522098 | 869420 | 4694 |
| collaboration-path-v1 | 25 | 25 | 4292 | 4603 | 4610 | 200 |
| paper-abstract-v1 | 25 | 25 | 103221 | 141158 | 141740 | 3177 |
| researcher-profile-v1 | 25 | 25 | 134536 | 174583 | 196957 | 4208 |
| topic-institution-summary-v1 | 25 | 25 | 162724 | 225976 | 244378 | 5102 |

## RunLedger and stored objects

- stage load-vectors: Succeeded, attempt 1; failure records 0 (retry count total 0; at most 1000 are listed)
- stored objects, collaboration-community: 8002
- stored objects, collaboration-community-document: 2
- stored objects, collaboration-community-source: 8000
- stored objects, collaboration-path: 278
- stored objects, collaboration-path-source: 278
- stored objects, paper-abstract: 6387
- stored objects, paper-abstract-document: 83
- stored objects, paper-abstract-source: 6292
- stored objects, researcher-profile: 8000
- stored objects, researcher-profile-source: 8000
- stored objects, topic-institution-summary: 8674
- stored objects, topic-institution-summary-document: 1207
- stored objects, topic-institution-summary-source: 5957
```

## Facts added from the observed run
- Meaningful units: total 223,953 source rows; ingested 28,527; chunks 31,341. The chunk total for the whole export is not known from this run (about 226,807 per rag's earlier estimate, not measured).
- Under the v2 abstract template, all 6,292 abstracts gave 6,387 chunks (83 multi-chunk documents). The v1 figure was 6,400, so the chunk count changed.
- Rejected records: 0 in both passes, so no reasons to list. rag's v2 abstract fix removed the 71 long-work-ID rejections predicted in 01b.
- Duplicates: 0 dropped in either pass. Duplicate-key and conflict handling is covered by unit tests only, not by this data.
- Second-pass skip: 31,341 of 31,341 unchanged, 0 written, 0 embedder calls, 0 PutVectors batches. Only the per-pass chunk lines show this, and the ledger shows no new failures.
- Retries and ledger: 0 retries and 0 failure records. The in-memory stores never fail, so retry and failure-record behavior is shown only by unit tests (RenderedLoadSuite).
- Throughput: pass 1 wrote 584 vectors/s, 315 PutVectors batches (batch 100). Pass 2 handled 481 chunks/s.
- Query latency in the report is from the in-memory exact scan, not AWS, so it says nothing about S3 Vectors latency.
- Index counts: community 8,002, path 278, abstract 6,387, researcher 8,000, topic 8,674. Total 31,341.
- Metadata schema:
  - Filterable: documentType, entityId, source, runId, snapshot, embeddingModel (`hash-embed-v1@256`), window, windowStartYear, windowEndYear (not on abstracts), researcherIds (at most 16), communityId, topicId, institutionId, workId, pathId, template, chunkOrdinal, chunkCount.
  - Non-filterable: textUri, textSha256, evidenceId, sourceUri.
  - Key is the chunk ID (`c:` plus 20 hex). No text-like key and no string over 512 bytes.
- Embedding model version: `hash-embed-v1@256`, a local deterministic fake with no AWS call.
- NOT RUN (no cloud in this phase): bounded AWS ingestion of 10,000 vectors, AWS query latency and real cost.

## Whole Phase 08 storage work
Step 02a (details in step-02a-storage-engineer-report.md):
- Created in modules/storage/src/main/scala/scholarchain/storage/: DocumentRenderer.scala, WorkQueue.scala, LocalIngestion.scala.
- Created in tests/module-unit/storage/: RenderFx.scala, RenderedLoadSuite.scala (15 tests), LocalIngestionSuite.scala (5 tests).
- Modified: VectorLoader.scala, Loaders.scala, Embedder.scala, Tables.scala, and the tests VectorSuite.scala and LoaderSuite.scala.
- `sbt storage/test` gave 179 passed, 0 failed, 1 ignored (the gated AWS suite).

Step 02b: no files created or modified in the repository. The only new file is the git-ignored report at target/phase-08/output-local-ingestion.md.

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 08 step 02b. Run the local ingestion of run-ccf619c509611f06 into the five indexes through rag's `scholarchain.rag.LocalIngestionRun` at a bounded N. Report meaningful units against units ingested, plus the report content. This report also covers 02a.
FILES CREATED: none in 02b. Git-ignored /home/ajcreator/scholarchain/target/phase-08/output-local-ingestion.md (the report). For 02a see above.
FILES MODIFIED: none in 02b. For 02a see above.
COMMANDS RUN (observed results):
- `pgrep -af 'sbt|java'; free -m`: no sbt or JVM running, 3,523 MB available.
- The work-order sbt command with N=8000, output redirected to the scratchpad log run8000.log. Result: rc=0, `[success] Total time: 169 s`, report written, 0 rejected in both passes, heap after the first pass 351 MB of 2048 MB. The harness moved it to the background and I waited without starting anything else. The process exited normally this time, so the hang rag saw did not happen.
- `cat` of the report file.
TEST RESULTS: No new tests this step and no storage bug found. `sbt storage/test` was last run in 02a (179 passed, 0 failed, 1 ignored). `sbt check` was not run (step 03). The ingestion run is evidence, not a test.
VALIDATION RESULTS: As in the report and the facts above. Limits are the same as in 02a: batch 100, queue 1000, retry 4 attempts, S3 Vectors limits per docs/service-limits.md (unchanged and not re-fetched).
ASSUMPTIONS:
1. N=8000 was chosen from rag's measured 15 KB per chunk, so the run fits well inside the heap. 351 MB confirmed it.
2. One attempt was enough, so I did not rerun at 3,000.
3. The subset is deterministic: the smallest SHA-256 of the row key per type. Researcher-profile (167,600 rows) and community (43,826 rows) are therefore only partly ingested, while path, abstract and topic are ingested in full.
RISKS:
- Only 12.7% of rows are ingested: 28,527 of 223,953 rows, 8,000 each of researcher and community. The full run was not attempted. About 15 KB per chunk puts it near 3.4 GB, over the 2 GB heap.
- Memory and query latency are in-memory-store figures. They do not predict S3 Vectors or Neptune behavior.
- The retry and rejection paths were not exercised by this real data (0 of each). They are covered by unit tests only.
- Pass 2 takes longer than pass 1 (65 s vs 54 s) because it re-reads and re-renders the 1.1 GB JSON-lines to compare hashes.
- Known issue, unchanged from 02a: a key already written in an earlier queue window is not retracted if a later conflicting duplicate arrives.
- State left behind: the git-ignored report and the scratchpad log run8000.log. No data was changed or deleted.
- Notes for modules/storage/MODULE.md (documentation-engineer):
  - Public API additions are listed in the 02a report: loadRendered, the new SemanticDocument builders, the new VectorLoadResult fields, RowSource.concat and RowSource.filter, and LocalIngestion.run.
  - 16 source files and 179 tests passing.
  - Done: the real-export local run at N=8000 (28,527 of 223,953 rows, 31,341 chunks, 0 rejected, second pass all skipped).
  - Not done: the full-export run, AWS ingestion, AWS latency and cost, and an SQS adapter.
MANUAL ACTION REQUIRED: none. The orchestrator copies the report into docs/agent-runs/phase-08/output-local-ingestion.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md and storage's MODULE.md with the notes above, then step 03 (`sbt check`) and fresh review.
