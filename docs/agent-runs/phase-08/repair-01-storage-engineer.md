HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, role default)

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 08 repair R1-2: MINOR-2 (LocalIngestion compares rows read with the manifest) and NIT-5 (queueCapacity doc wording).
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/LocalIngestion.scala
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/VectorLoader.scala (doc comment only)
- /home/ajcreator/scholarchain/tests/module-unit/storage/LocalIngestionSuite.scala
COMMANDS RUN (all succeeded):
- sbt storage/scalafmt storage/Test/scalafmt storage/test
- sbt rag/test
TEST RESULTS: storage/test: Passed 184, Failed 0, Ignored 1 (LocalIngestionSuite: 10 tests, 0 failed). rag/test: Passed 66, Failed 0 (IngestionConformanceSuite 8/8). Full sbt check not run, as ordered.
VALIDATION RESULTS:
- LocalIngestion.scan replaces select. It is one pass that counts every decoded row per docType. With a subset it also keeps the n smallest-SHA-256 keys. It counts undecodable lines per source table.
- New checkAgainstManifest runs before any load, in both modes. Failure text starts "export does not match its load manifest: ...".
  - It compares the rows read per type with the manifest rows (index rows, plus abstracts rows).
  - It flags rows of document types the manifest does not list.
  - If a table has undecodable lines (they have no type), that table is compared as a whole (decoded plus undecodable lines) instead of per type.
- No subset: `expectedRows = Some(sum of index rows + abstract rows)` is passed to `loadRendered` on both passes.
- The report's `totalRows` and `selectedRows` now come from the scan, not the manifest. Without a subset both equal the rows read. With a subset, selected is the number chosen.
- A missing jsonl directory shows up as an undecodable row, so it fails the check.
- New tests: for each of semantic-summaries and paper-abstracts, with and without a subset, a run folder with one part file removed fails (4 tests). A complete folder passes with correct counts: full 21/21 with first.sourceRows 21, subset of 2 gives 21 total and 6 selected.
- The existing test with one extra non-JSON line still passes: the fixture manifest counts that line.
- NIT-5: the `queueCapacity` doc now says it bounds only the documents waiting, and that the `seen` fingerprints and the `objects` memo grow with the keys of the load.
- `LocalIngestion.run` signature unchanged. No `VectorLoader` rejection message changed ("already bound to different text" and "another model" untouched).
ASSUMPTIONS:
- The manifest's index rows plus abstracts rows are the expected total for a full load.
- Counting undecodable lines toward their table's total is the right way to keep the rejected-rows path working. Its per-type check is skipped for that table, so a truncation hidden among malformed lines is still caught only by the table-level sum.
RISKS:
- The export is now scanned once more (a counting scan) in full mode, plus the existing two load passes. This costs time on the real run, no extra memory without a subset.
- Without a subset, the `expectedRows` check runs inside the loader and counts the source rows by iterating the source again. That is an extra read per pass.
- Nothing was run against real data or AWS. The loader's own `expectedRows` message ("expected N rows, the source gave M") now appears only if the files change between the scan and the load.
- Note for MODULE.md: LocalIngestion now fails a run whose export does not match its load manifest, and report row counts are from rows read.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R1-3 full `sbt check`. R1-4 documentation-engineer. Doc lines now wrong or to extend (not edited by me): any line in docs/storage.md, docs/embeddings.md, docs/data-lineage.md and modules/storage/MODULE.md that says the local ingestion "ingested rows" or total rows come from the manifest, or that it does not compare the export with the manifest. Any line that says the loader "never holds more than the queue plus one partial batch per index" is also wrong (the NIT-5 wording applies).
