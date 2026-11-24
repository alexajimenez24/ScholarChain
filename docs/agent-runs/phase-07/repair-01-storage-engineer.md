HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier, role default

All six findings in scope are fixed. `sbt storage/test` passed: Total 158, Failed 0, Errors 0, Passed 158, Ignored 1. The 1 ignored is the gated AwsIntegrationSuite. That is 153 before plus 5 net new tests: the two MINOR-1 tests, the MINOR-7 test, and two net from the MINOR-3 and NIT-5 changes (the NIT-5 test was replaced by one test, and two were added). I ran only `sbt storage/test`, once, and it compiled on the second attempt after a typo in my own test.

Findings (file and line numbers are approximate; the formatter hook reflowed some files):
- **MAJOR-1 (minimum repair).**
  - `NeptuneAdapters.scala` ~74-82: the `NeptuneBulkLoader` doc comment no longer claims `updateSingleCardinalityProperties` handles a reload under a new run. It now says that property only replaces a changed property of an existing ID. It states the invariant: one served run per Neptune cluster and per vector index, with the human cleaning or rebuilding before a new run is loaded.
  - `VectorLoader.scala` ~407: a comment on the unchanged-vector branch notes that the vector keeps the first run's `runId` and `textUri`, with the same one-served-run-per-index invariant.
  - No run-scoped templates were added. That stays carried forward to the human.
  - Proving test: none, comment only. `GraphTemplateSuite` (8) and `NeptuneAdapterSuite` (7) stay green.
- **MINOR-1.**
  - `Loaders.scala` ~39-67: `RowSource.jsonLinesDir` now returns a `RowSource` that yields one `Left("input directory <dir> is missing")` row when the directory is absent. It no longer returns zero rows.
  - The loaders mark that row as a rejected malformed row. So a missing directory gives read=1 and rejected=1 with a visible reason, instead of an empty load. The stage is still Succeeded in the ledger in that case; I took the brief's `Left`-row option and did not make the stage fail. The `expectedRows` check also fails it.
  - Tests:
    - `LoaderSuite.scala`, ResearcherMetricsLoaderSuite: "a missing input directory is a visible malformed row, not an empty successful load" and "a missing input directory does not satisfy an expected row count".
    - `VectorSuite.scala` ~754-770: the absent-directory assertion is changed from an empty vector to one Left row containing "is missing".
- **MINOR-3.**
  - `AwsIntegrationSuite.scala` has a new `AwsIntegration.BoundedTransport`, a counting `HttpTransport`. It counts every HTTP attempt, retries included. It refuses all attempts after `MaxCalls` (25) without reaching the inner transport. `run` wraps the transport with it and returns `Failed` if the bound was exceeded.
  - The dedicated test index setting is `SCHOLARCHAIN_IT_TEST_VECTOR_INDEX`, documented by name in the object doc. The old name `SCHOLARCHAIN_IT_VECTOR_INDEX` is gone.
  - `run` refuses an index name that equals any production index in `VectorConfig.indexes`, before any call.
  - The tautological `assert(MaxCalls <= 25)` is removed from the harness suite.
  - Tests:
    - "the bound counts every HTTP attempt and refuses the ones after MaxCalls": 25 calls reach the inner transport, then 11 more attempts are refused and the inner count stays 25.
    - "an opened gate refuses a production vector index before any call": 0 transport calls.
  - Still no AWS call in `sbt check`; the gated suite stays ignored.
- **MINOR-5.** `Tables.scala` ~304: the comment now says deletion is "typically within a few days of expiry, docs/service-limits.md". No test, comment only.
- **MINOR-7.**
  - `VectorLoader.scala` ~417-423: `prepare` is skipped for the rest of the batch once an artifact error is set.
  - Test in `LoaderSuite.scala`, VectorLoaderSuite: "after a text-object failure the rest of the batch is neither written nor embedded". An injected S3 failure on 20 documents gives exactly 1 S3 put attempt, at most 1 embedder call, and 0 vectors written.
- **NIT-5.** The tautological test is replaced by "the integration suite is ignored exactly when the gate is closed". It checks `munitIgnore` against `!enabled(sys.env)`, plus an explicit "false" and "true" env.

COMMANDS RUN:
- `sbt storage/test`. The first run failed to compile because of my test typo (`failing.puts` on an `ObjectStore`). The second run passed.
- I also ran grep, sed and python3 -I edits.

ASSUMPTIONS:
- MINOR-1 uses the brief's visible-Left option.
- The existing 25 limit is kept as HTTP attempts. With `maxAttempts=3`, a normal run (about 14 logical calls) should stay under it.

RISKS:
- A transient failure could use up the 25 attempts and fail the cloud run even though the logic is right.
- The bound is enforced at the transport. It is not exercised end to end against AWS, which is gated.
- Nothing was deleted or repaired.
- For `MODULE.md` (documentation-engineer):
  - The env var rename to `SCHOLARCHAIN_IT_TEST_VECTOR_INDEX`.
  - The production-index refusal.
  - The `jsonLinesDir` missing-directory behaviour.
  - The fact that `docs/storage.md` still names the old env var and still says "at most 25 calls".
- MAJOR-1 full fix, MINOR-6, MINOR-2 and MINOR-4 are untouched, as ordered.

MANUAL ACTION REQUIRED: none.

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: phase 07 repair round 1, R1-1
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/NeptuneAdapters.scala
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/VectorLoader.scala
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/Loaders.scala
- /home/ajcreator/scholarchain/modules/storage/src/main/scala/scholarchain/storage/Tables.scala
- /home/ajcreator/scholarchain/tests/module-unit/storage/LoaderSuite.scala
- /home/ajcreator/scholarchain/tests/module-unit/storage/VectorSuite.scala
- /home/ajcreator/scholarchain/tests/module-unit/storage/AwsIntegrationSuite.scala
COMMANDS RUN: `sbt storage/test` (2 runs: 1 compile failure in my test, then pass)
TEST RESULTS: Total 158, Failed 0, Errors 0, Passed 158, Ignored 1
VALIDATION RESULTS: call bound 25 HTTP attempts, enforced by `BoundedTransport` (retries counted); retry policy unchanged (`maxAttempts` 3 in the harness); no schema, key or batch change (batch size stays 100)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R1-2 (domain-engineer: NIT-3 and NIT-4, then the one full `sbt check`)
