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
