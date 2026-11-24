# Data lineage

This file holds the Phase 08 lineage facts and the stages documented elsewhere.
Every hop carries provenance: source dataset, source record ID, snapshot, run ID and
the policy or schema version.
Stage details are in the files linked in each row.

## Pipeline stages

| Stage | Reads | Writes | Documented in |
|---|---|---|---|
| Datasets | DBLP, OpenAlex, Crossref | local snapshot files | `datasets/dataset-manifest.yaml` |
| Hadoop J01 to J07 | snapshot files | work, mention, team, pair and export tables | [hadoop-jobs.md](hadoop-jobs.md) |
| Entity resolution er-v1 | Hadoop export | match decisions | [entity-resolution.md](entity-resolution.md) |
| Spark | Hadoop run and decision run | graph, metrics, summaries, JSON-lines | [spark-jobs.md](spark-jobs.md) |
| rag rendering | Spark JSON-lines rows | rendered documents and chunks | [embeddings.md](embeddings.md) |
| storage ingestion | rendered chunks | S3 text, source rows, vectors | [embeddings.md](embeddings.md) |

Stages exchange data only through versioned `core` contracts and run manifests.

## Lineage line for semantic context

```text
Spark row (JSON-lines: semantic-summaries, paper-abstracts; run manifest, run ID)
  -> rag rendering (template version, chunk-v1; one text per entity)
  -> storage chunk object in S3
       (semantic-context/<dataset>/run_id=<run>/<docType>/<chunkId>.json)
       with the source row in <docType>-source/ and, for two or more chunks, the whole
       document in <docType>-document/
  -> vector (key = chunkId; metadata = textUri pointer, textSha256, embeddingModel, runId)
  -> semantic-context-v1 item (fetched through textUri, hash checked)
```

- The Spark row names its source: summaries carry an evidence ID and provenance; abstract
  rows carry the source record ID from the Hadoop abstracts stage.
- Rendering uses only the Spark row; the four summary templates adopt Spark's text
  byte for byte, so one identity has one text.
- The vector stores no text.
  The chunk object holds the text, the template, the chunk policy, the ordinal and the
  chunk count.
- A reader follows `textUri` and compares the text hash with `textSha256`.
  A mismatch drops the hit.
- Evidence ID of an item: equal to Spark's for a one-chunk summary; a TEXT_CHUNK evidence
  ID per abstract chunk.
  Semantic context never supports a count, path, component or bridge value.
- Embedding version `<modelId>@<dimension>` is on every vector; locally
  `hash-embed-v1@256`.

Evidence: `docs/agent-runs/phase-08/step-01-rag-engineer-report.md`,
`step-02a-storage-engineer-report.md` and `step-02-rag-engineer-report.md`.

## Dataset scale-up and step-down (Phase 08)

The human decided on 2026-11-23 to move the integrated run from the 40-record sample to
100,000 DBLP works.
Raw data stays in git-ignored `datasets/samples/local/<snapshot>/`, and nothing was
uploaded to S3.

### Step S1, the 100,000-work snapshot (2026-11-24)

- DBLP: 100,000 works with at least 2 authors, 4,000 per year for 2000 to 2024.
  Rule: per year, the smallest sha256 of the DBLP key.
  The dump was 1,123,907,757 bytes; 8,793,669 records of the retained types were scanned.
- OpenAlex: PARTIAL.
  50,000 of 90,285 DOIs were requested, because the keyless daily budget ended in HTTP
  429.
  It returned 49,749 works, 25,078 of them with an abstract.
  DOIs were requested in sorted order, so coverage has a DOI-prefix bias.
- Crossref: 5,000 DOIs requested, 4,516 found.
- ORCID: unchanged, not fetched.
- Checksums and the year distribution are in the manifest and in
  `docs/agent-runs/phase-08/step-S1-dataset-engineer-report.md`.

### Steps S2 and S3 at 100,000 works

- Hadoop run `run-834f71c82961a194`: 570 s on the second attempt (the first used
  relative paths and failed); 154,246 works, 545,366 mentions, 980,859 pairs.
- Entity resolution `run-85fd79957d197ffc`: 4,561,097 decisions in 1:21:47.

### Step 00, Spark at 100,000 works failed

- Attempt 1 ran out of memory after 23:04 with a 2 GB heap.
- Attempt 2, with 32 shuffle partitions, was killed at the 90-minute limit.
- The run `run-c4b35c788d05a93c` is not usable and no later step reads it.
- Under the step-down rule the chain reran at 25,000 works.
- spark-engineer retried at full scale once before stepping down; that is recorded as a
  possible deviation from the rule, for the human to decide.

### The 25,000-work step-down

| Step | Run or output | Observed |
|---|---|---|
| S1b | `datasets/samples/local/2026-11-24-25k/` | 25,000 works, 1,000 per year; OpenAlex 12,433 (6,292 abstracts); Crossref 1,137 |
| S2b | Hadoop `run-03a81f6f54f3fc3c` | 187 s; 38,564 works, 136,266 mentions, 241,686 pairs |
| S3b | decision `run-cb5a0421c406737d` | 903,286 decisions in 1:19:48 |
| 00b | Spark `run-c73766e8001fce5a` | 24:56; INVARIANT_FAILURES, not used |
| 00d | Spark `run-ccf619c509611f06` | 25:14; SUCCEEDED, 114 invariants, 0 nonzero |

- S1b derives the subset from the 2026-11-24 snapshot with the `subset` command, with no
  new fetch; a rerun of the S1 rule at 25,000 is byte-identical for DBLP.
- The manifest records it in `derived_subset_25k`.
- 00b had one nonzero invariant (`componentsNotConverged`):
  components did not converge in 40 iterations for 1950-2026.
  The empty path index was not an invariant then; `pathRequestedNotFound` was added in 00d.
- Repair 00c: core `graph.componentMaxIterations` 40 to 100.
- Repair 00d: path pair rule `spark-v4`, targets chosen from each source's reach within
  6 hops, at most 112 pairs per window; 278 path documents.
- Later steps read `run-ccf619c509611f06` through its run manifest.

OpenAlex coverage in the 25,000-work subset is thin: 12,433 works for 22,602 DOIs.
