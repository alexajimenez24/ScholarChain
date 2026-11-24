# Phase 08 repair round 1 (orchestrator routing)

Started by: `review-report.md` (Review 1, fresh code-reviewer), verdict APPROVE WITH
CARRIED-FORWARD ITEMS: 0 CRITICAL, 0 MAJOR, 5 MINOR, 6 NIT.
Because Review 1 has no CRITICAL or MAJOR finding, the addendum calls for no Review 2.
This is the only repair round; anything still open afterwards is carried to the human gate.

## Routing

| Task | Owner | Findings | Repair brief (from the review) | Check |
|---|---|---|---|---|
| R1-0 | documentation-engineer | (STATUS) | record the review and this routing in STATUS.md | none |
| R1-1 | rag-engineer | MINOR-1, NIT-4 (rag side) | `Chunking.scala:61` guard `count < p.maxChunks`; boundary test (8 spans accepted, 9 refused); align `EmbeddingModelVersion.decide` precedence with the loader (text mismatch before version) or pin the loader's order in a test | `sbt rag/test` |
| R1-2 | storage-engineer | MINOR-2, NIT-5 | `LocalIngestion`: pass `expectedRows` with no subset; with a subset fail when a type's scan total differs from its manifest rows; report selected rows from what was read; reword the `queueCapacity` doc | `sbt storage/test` |
| R1-3 | spark-engineer | NIT-1 | update the `abstracts` `keyRule` text in `Export.scala:750` (rag's renderer makes the chunk IDs, tpl-paper-abstract-v2, multi-chunk formula); then the one full `sbt check` for the round, because R1-1 to R1-3 change source | full `sbt check` |
| R1-4 | documentation-engineer | MINOR-3 (wording), NIT-2, NIT-3, NIT-6, plus the doc lines MINOR-1 makes wrong | reword the Parquet-gap claim; fix the invariant count, the heap estimate wording and basis, the iteration ratio, and the chunk-limit wording if R1-1 changed it; STATUS for R1-1 to R1-4 | none |

Not routed, carried to the human gate:
- MINOR-3 code part: a contract test that loads the `neptune.json` and `dynamodb.json`
  JSON-lines with `extraFields` through the Neptune and DynamoDB loaders (storage-engineer).
  It is new work beyond Phase 08's vector scope, so the orchestrator carries it forward
  under the addendum's "nothing optional".
- MINOR-4: the untracked `modules/hadoop/datasets/` folder from S2 attempt 1.
  The orchestrator leaves its removal to the human ("no agent deletes data" reading, as the
  review allows); the human must not commit it.
- MINOR-5: spark-engineer's full-scale retry before stepping down in step 00. Human decision.
- NIT-4 storage side: nothing to change in storage if rag aligns to the loader's order.

STATUS updates: a documentation-engineer STATUS update follows each owner task (R1-0,
R1-1s, R1-2s); R1-4 records the STATUS for R1-3 and for itself, because it follows R1-3
directly and is a documentation-engineer task.

## Fix reports and rerun evidence

Saved as `repair-01-<owner>.md` (and `repair-01-status-*.md` for STATUS updates), listed in
`run-log.md`. The full `sbt check` output of R1-3 is saved as `output-sbt-check-r1.txt`.
