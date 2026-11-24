# Phase 07 repair round 1: orchestrator routing

Source: `review-report.md` (Review 1, REQUEST_CHANGES: 1 major, 7 minor, 6 nits).
The addendum allows one repair round, and a fresh Review 2 because Review 1 has a MAJOR.

## Decision on MAJOR-1 (served-run scoping)

The orchestrator chose the reviewer's minimum repair. The addendum says to build exactly
what the work order and the gate require and nothing optional, and the work order does
not demand run scoping (reviewer ASSUMPTIONS). The minimum repair is:
- correct the misleading reload comment;
- document the invariant: one served run per Neptune cluster and per vector index,
  rebuilt or cleaned by the human before a new run is loaded;
- carry the full fix (run-scoped templates and vector reads) forward to the human gate.
The human may overrule this at the gate.

## Routing

| Task | Owner | Findings | Check |
|---|---|---|---|
| R1-0 | documentation-engineer (haiku) | STATUS: review 1 result, repair task list | none |
| R1-1 | storage-engineer | MAJOR-1 comment, MINOR-1, MINOR-3 code, MINOR-5, MINOR-7, NIT-5 | `sbt storage/test` |
| R1-2 | domain-engineer | NIT-3, NIT-4; then the one full `sbt check` (source changed) | `sbt core/test`, `sbt check` |
| R1-3 | documentation-engineer | MAJOR-1 docs, MINOR-2, MINOR-3 docs, MINOR-4, MINOR-6 (documented), NIT-2, STATUS | evidence |
| Review 2 | code-reviewer (fresh) | re-check | `review-report-02.md` |

Carried forward to the human, not repaired in this round:
- MAJOR-1 full fix (run scoping in templates and vector reads).
- MINOR-6 code fix (the ledger keeps the first report after a repairing replay); documented.
- NIT-1: the step 00 report says 29 new tests, the suite counts give 21; the report is
  evidence and stays as written (MODULE.md and STATUS already say 21).
- NIT-6: more published SigV4 vectors (optional).
- Reviewer observation outside the diff: `load-manifests/neptune.json` lists
  `coauthoredWith.windows = ["1950-2026"]` while the export has 1950-2026 and 2010-2014
  partitions (spark-engineer).
- Already open: Parquet reader gap; Spark export gaps (institutionId, paper-abstract rows);
  adjudication sharing `models.synthesisModel`.
