# Embeddings and vector indexes (phase 08)

This is a new file, not a section of `docs/storage.md`.
It is separate because it spans three modules: rag renders the text, storage ingests
it, and rag reads it back.
`docs/storage.md` section 4 keeps the S3 Vectors adapter facts and points here.

Sources of every fact below, all in `docs/agent-runs/phase-08/`:
`step-01-rag-engineer-report.md`, `step-01b-rag-engineer-report.md`,
`step-02-rag-engineer-report.md`, `step-02a-storage-engineer-report.md`,
`agent-report.md`, `output-local-ingestion.md` and `output-sbt-check-step03.txt`.
No AWS call was made in this phase.
Embeddings come from a deterministic local fake and vectors go to an in-memory store.

## What was and was not run

| Item | State |
|---|---|
| Local ingestion of run-ccf619c509611f06 into five indexes | RUN, 28,527 rows |
| Local contract tests (`sbt check`) | RUN, exit 0 in 362 s |
| Bounded AWS ingestion of 10,000 vectors | NOT RUN until the cloud phases |
| Query latency on S3 Vectors | NOT RUN until the cloud phases |
| Real embedding cost (Bedrock, S3 Vectors) | NOT RUN until the cloud phases |
| Bedrock embedder against the real service | NOT RUN |
| SQS queue adapter | not implemented, see the queue section |

The latency figures below come from the in-memory exact scan.
They say nothing about S3 Vectors latency.

## How many meaningful units the subset produces

- Run run-ccf619c509611f06 holds 223,953 source rows (semantic summaries plus paper
  abstracts), from 25,000 DBLP works.
- That is above 10,000, so no smaller corpus and no instructor question are needed.
- The 25,000-work step-down is described in
  [data-lineage.md](data-lineage.md).
- The local ingestion took a bounded deterministic subset of 28,527 rows, which became
  31,341 chunks.
  That is also above 10,000.
  The subset is the 8,000 rows with the smallest SHA-256 of the row key per document
  type; types with fewer rows were ingested in full.
- The corpus was never padded.
- The full export of 223,953 rows is likely too large for a 2 GB heap.
  This is an unmeasured estimate, because the full run was not attempted.
  Step 02b measured 351 MB of heap for 31,341 chunks, about 11.5 KB per chunk.
  That gives about 2.5 GB for 226,807 chunks.

| Document type | Total rows | Ingested rows | Chunks present |
|---|---|---|---|
| researcher-profile | 167,600 | 8,000 | 8,000 |
| collaboration-community | 43,826 | 8,000 | 8,002 |
| collaboration-path | 278 | 278 | 278 |
| topic-institution-summary | 5,957 | 5,957 | 8,674 |
| paper-abstract | 6,292 | 6,292 | 6,387 |
| total | 223,953 | 28,527 | 31,341 |

Evidence: `docs/agent-runs/phase-08/output-local-ingestion.md`
(sha256 starts e5e4e419, as noted in `run-log.md`).

## The five indexes

Names come from core `VectorConfig`.
Each has cosine distance, float32 and dimension 256 locally.

| Index | Document type | Template |
|---|---|---|
| researcher-profile-v1 | researcher-profile | tpl-researcher-profile-v1 |
| collaboration-community-v1 | collaboration-community | tpl-collaboration-community-v1 |
| collaboration-path-v1 | collaboration-path | tpl-collaboration-path-v1 |
| topic-institution-summary-v1 | topic-institution-summary | tpl-topic-institution-summary-v1 |
| paper-abstract-v1 | paper-abstract | tpl-paper-abstract-v2 |

## Templates

- The four summary templates adopt Spark's text byte for byte after a conformance check.
  The check needs the layout markers in order and a final full stop.
  A row that fails is refused with its reason and never repaired.
  A change to the Spark layout therefore fails visibly and needs a template version bump.
- The abstract template is rendered in rag:
  `Paper abstract. Source record: {source} {sourceRecordId}. Abstract: {abstract}`.
- tpl-paper-abstract-v1 wrote the work ID into the text.
  Storage refuses text that repeats a metadata value of 40 or more characters.
  71 of 6,292 work IDs are that long, so v1 would have been rejected.
  v2 removes the work ID from the text; it still travels as `entityId` and `workId`.
  v1 was never stored or committed.
- No LLM prompt is involved in rendering, so `prompts/` holds no render prompt.

## Chunk policy chunk-v1

- At most 2,000 UTF-16 units per chunk, no cut before 1,000, at most 200 units of
  overlap and at most 32 chunks.
  Exactly 32 chunks is accepted and a 33rd is refused, never truncated.
- A chunk ends after the last `.` or `;` followed by whitespace, else at the last
  whitespace, else at a hard cut that never splits a surrogate pair.
- The next chunk starts at the first word start inside the overlap.
- Chunks are pure spans of the one text.
- A one-chunk document keeps the key `Ids.chunk(entity, sourceVersion, docType, 0, text)`,
  which equals Spark's `chunkId` for summaries.
- A multi-chunk document uses the source version plus `+chunk-v1+` plus the sha256 of
  the full text, so two windows with a repeated span get different keys.
- Observed multi-chunk documents in the local subset: 83 abstracts, 1,207 topic
  summaries and 2 community summaries (`-document` object counts below).

## Embedding version rule embedding-version-v1

- The version is `<modelId>@<dimension>`, both from config.
  One function, storage's `EmbeddingVersion.id`, computes it; rag's
  `EmbeddingModelVersion.id` calls it.
- The local fake is `hash-embed-v1@256`.
- One index version (`-vN`) holds exactly one embedding version.
  A change of model or dimension means a new index (`nextIndexName`, for example
  researcher-profile-v1 to researcher-profile-v2), never an overwrite.
- Changed text gets a new key and only that key is embedded.
- Identical text under a new template, chunk policy or run keeps its key and is skipped.
- A key that already holds other text is refused.
  The text check comes first: other text is refused even under another embedding
  version, and the same text under another version means a new index version.
- The version is stored in the filterable metadata `embeddingModel`.
  The retriever drops every hit whose `embeddingModel` differs from its embedder.

## Metadata schema

The vector key is the chunk ID, `c:` plus 20 hex characters.
No key is text-like and no string is over 512 bytes.

| Kind | Keys |
|---|---|
| Filterable | `documentType`, `entityId`, `source`, `runId`, `snapshot` |
| Filterable | `embeddingModel`, `window`, `windowStartYear`, `windowEndYear` |
| Filterable | `researcherIds` (at most 16), `communityId`, `topicId` |
| Filterable | `institutionId`, `workId`, `pathId`, `template` |
| Filterable | `chunkOrdinal`, `chunkCount` |
| Non-filterable | `textUri`, `textSha256`, `evidenceId`, `sourceUri` |

- `windowStartYear` and `windowEndYear` are absent on paper-abstract.
- `workId` is set on abstracts and `pathId` on paths.
- The non-filterable set is unchanged from Phase 07, so no Terraform change.
- S3 Vectors limits are as in [service-limits.md](service-limits.md): 50 keys, 2 KB
  filterable and 40 KB total on CLASSIC indexes.
- `template`, `chunkOrdinal` and `chunkCount` are Phase 08 additions to the filterable
  set, inside the existing index definitions (storage report assumption 5).

## Text and the S3 pointer

All objects are create-only, with a content-hash comparison on replay, under
`semantic-context/<dataset>/run_id=<run>/`.

| Object | Content |
|---|---|
| `<docType>/<chunkId>.json` | the chunk's `semantic-doc-v2`; this is `textUri` |
| `<docType>-source/<sha40>.json` | the authoritative source row |
| `<docType>-document/<sha40>.json` | the whole rendered document, 2 or more chunks only |

- The vector holds only the pointer and compact metadata, never the text.
- Unchanged vectors write no object.
- Object counts from the local run: collaboration-community 8,002 chunks, 8,000 sources
  and 2 documents; collaboration-path 278 and 278; paper-abstract 6,387, 6,292 and 83;
  researcher-profile 8,000 and 8,000; topic-institution-summary 8,674, 5,957 and 1,207.

## Work queue, batches and retries

- The queue is a manifest-driven batch with a bounded in-process queue (capacity 1,000),
  not SQS.
  The capacity bounds only the documents waiting.
  The `seen` fingerprints and the `objects` memo grow with the keys of the load.
  Reasons from the storage report: no cloud is used this phase, every step is
  idempotent so a replay needs no at-least-once broker, and an SQS queue with its dead
  letter queue and IAM belongs to infra-engineer.
- The SQS adapter is not implemented.
  `WorkQueue` is a trait, so the Phase 10 worker can implement it over SQS.
- Batch size 100 (settable 1 to 100).
  PutVectors allows 500 per call and GetVectors 100 keys.
- Retry policy: 4 attempts, 200 ms doubling, 5 s cap, transient errors only.
  The local entry point uses 4 attempts with no sleep.
- Skip: a chunk whose text hash and embedding version match the stored vector is
  skipped.
- Refusal: one key for two different texts is refused with
  "vector key already bound to different text".
- RunLedger: each rejection is `recordFailure(runId, "load-vectors", "reject:<input>",
  reason)`.
  Failure records are capped at 500 per load, with one overflow record carrying the
  count.
  Each retried call is `retry:<PutVectors|GetVectors|Embed>:<index>` with its retry
  count.

- The local ingestion fails before any load when the rows read differ from the load
  manifest, with a message that starts "export does not match its load manifest".
  Report row counts come from the rows read, not from the manifest.

## Observed local run

Settings: batch 100, queue capacity 1,000 (high water 1,000), retry 4 attempts, heap
351 MB used of 2,048 MB after the first pass.

| Pass | Written | Unchanged | Duplicates | Rejected | Batches | Rate |
|---|---|---|---|---|---|---|
| 1 | 31,341 | 0 | 0 | 0 | 315 | 584 vectors/s, 53,669 ms |
| 2 | 0 | 31,341 | 0 | 0 | 0 | 481 chunks/s, 65,160 ms |

- Pass 2 is longer because it re-reads and re-renders the 1.1 GB JSON-lines to compare
  hashes; it made 0 embedder calls.
- Retries 0 and ledger failure records 0.
  The in-memory stores never fail, so retry and rejection paths are covered by unit
  tests only (`RenderedLoadSuite`).
- Duplicate-key and conflict handling is also covered by unit tests only.
- Chunks per index: community 8,002, path 278, abstract 6,387, researcher 8,000,
  topic 8,674.
- Query latency (in-memory exact scan, 25 queries per index, own key in the top K for
  all 25):

| Index | p50 us | p95 us | max us | filtered p50 us |
|---|---|---|---|---|
| collaboration-community-v1 | 136,585 | 522,098 | 869,420 | 4,694 |
| collaboration-path-v1 | 4,292 | 4,603 | 4,610 | 200 |
| paper-abstract-v1 | 103,221 | 141,158 | 141,740 | 3,177 |
| researcher-profile-v1 | 134,536 | 174,583 | 196,957 | 4,208 |
| topic-institution-summary-v1 | 162,724 | 225,976 | 244,378 | 5,102 |

## Semantic context for the synthesis agent (semantic-context-v1)

Source: `step-02-rag-engineer-report.md`.
Schema: `prompts/schemas/semantic-context-v1.json`.

- A vector hit becomes context only through its S3 pointer.
  A hit is kept only if the index and document type agree, the embedding model matches
  the query's, the pointer is under `s3://<ragBucket>/semantic-context/`, the object is
  at most 65,536 bytes and decodes as `semantic-doc-v2`, the chunk ID equals the hit
  key, the text hashes to its own hash and to the vector's `textSha256`, and the
  entity, run and window match the metadata.
  A failed hit is dropped with a reason, never repaired.
- Top level: `format` `semantic-context-v1`, `trust` `untrusted-data`,
  `embeddingModel` and at most 10 items.
- `score` is the only number in the format.
  There is no count, path, hop, component, community-size or bridge field.
- Budget `ContextBudget.V1`: 10 items, 2,000 characters per item, 8,000 in total.
- Sanitizer `untrusted-text-v1` replaces control, format, private-use, surrogate,
  unassigned, separator and whitespace characters with a space.
  Instruction-like words stay as data; the text appears only inside a JSON string.
- `graph-value-policy-v1`: only METRIC_RECORD, COAUTHOR_EDGE, GRAPH_PATH,
  COMPONENT_RECORD and COMMUNITY_RECORD evidence may support a count, path, component,
  community or bridge value.
  Semantic context is SEMANTIC_PROFILE or TEXT_CHUNK only.
- Query safety: user text is only embedded, 1 to 2,000 characters; filters are typed and
  become equality terms; at most 10 targets; topK 1 to 20; no Cypher and no AWS call.
- Not done: the Phase 9 workflow, synthesis prompt and verifier; retrieval quality on
  real data.
  The smoke tests use the token-hashing fake, which measures token overlap, not meaning.
- Numbers inside a summary text, for example "Distinct coauthors: 41", reach the model
  as untrusted words.
  The verifier must accept numbers only from authoritative evidence.

## Gate items and the tests that cover them

All in `tests/module-unit/rag/`; `sbt rag/test` passed 63 of 63
(`step-02-rag-engineer-report.md`).

| Gate item | Test |
|---|---|
| One key never refers to two texts | `RetrievalSmokeSuite` test 8 |
| Model version is stored | `RetrievalSmokeSuite` test 9 |
| Text fetched through the S3 pointer | `RetrievalSmokeSuite` test 10 |
| Smoke tests with known neighbours | `RetrievalSmokeSuite` tests 2 to 7 |
| Graph values never from vectors | `SemanticContextSuite` tests 4 to 6 |

The fixture's expected neighbours come from its design (each field has its own
vocabulary), not from a run.

## Cost method (an estimate)

Nothing here was measured on AWS.
The method needs two prices, and neither price page can be read with the documentation
tools.
The tools read only `docs.aws.amazon.com`; fetching `https://aws.amazon.com/s3/pricing/`
returned "URL must be from list of supported domains" on 2026-11-24.

| Price | Where it is published | Checked | Value |
|---|---|---|---|
| S3 Vectors PUT, storage and query | https://aws.amazon.com/s3/pricing/ | 2026-11-24 | UNVERIFIED |
| Titan Text Embeddings V2 per 1,000 tokens | https://aws.amazon.com/bedrock/pricing/ | 2026-11-24 | UNVERIFIED |

- The Bedrock URL is the one the model card sends readers to
  (https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-amazon-titan-text-embeddings-v2.html,
  section Pricing, read 2026-11-24).
  The S3 Vectors price page URL was not found in the documentation search and is the
  general S3 pricing page; it is not confirmed to list S3 Vectors prices.
- No price is invented here.
  The human fills the two values from the pricing pages before any cloud run.

Method (every quantity except the prices):

- Embedding cost = total input tokens divided by 1,000 times the Titan price per 1,000
  tokens.
  Input tokens are not measured: the local fake does not count tokens.
  Chunks are at most 2,000 UTF-16 units, about 500 tokens, which gives an upper bound of
  about 5,000,000 tokens for 10,000 chunks (the 4-characters-per-token ratio is rag's
  assumption 4 in the step 01 report, not measured).
- Vector storage = vectors times 256 dimensions times 4 bytes, plus metadata.
  For the 31,341 local chunks the vectors alone are 32,093,184 bytes; for 10,000
  vectors they are 10,240,000 bytes (arithmetic, not an AWS figure).
  Metadata and S3 text objects add to this and were not measured.
- PUT cost uses the bytes written.
  The local run made 315 PutVectors batches of at most 100.
  At 500 vectors per call the same 31,341 vectors would need 63 calls (arithmetic).
- Query cost uses the query count and the vectors scanned per query; none was measured
  on AWS.
- The result is an estimate, to be counted against the remaining budget of $25 only
  after the human signs off the cloud run.

## Limits of this evidence

- 12.7 percent of the rows were ingested; the rest were not tried.
- Memory and latency are in-memory figures and do not predict S3 Vectors.
- A key already written in an earlier queue window is not retracted if a later
  conflicting duplicate arrives; the new copy is rejected.
  This matters only for duplicate source rows spanning the queue.
- One `sbt` process in step 01b took 47 minutes to exit after success; the cause was not
  found.
