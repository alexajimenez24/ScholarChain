HARNESS: claude-code
MODEL: Claude Opus 5.5 (claude-opus-5-5), high tier (role default, as the work order says)

Step 01b is COMPLETE. rag's `SemanticRenderer` now implements storage's `DocumentRenderer` port and uses storage's types, and `sbt rag/test` passes 41 of 41. On the real export, a 200-per-type smoke run gives 0 rejections in both passes.

To get there I had to change one thing beyond the work order. The first smoke run rejected 3 of 1,071 chunks, and they were rag's fault. The `tpl-paper-abstract-v1` header wrote the work ID into the text. Storage's `VectorMetadata.check` then refused any chunk whose text repeats a metadata value of 40 or more characters, here `entityId`/`workId`. In the full export, 71 of 6,292 abstracts have a DBLP key that long, so step 02b would have rejected 71 chunks. I fixed it in rag: the abstract template is now `tpl-paper-abstract-v2` and its header no longer contains the work ID. The identifier still travels as `entityId` and `workId` metadata. Storage needed no change, so nothing is blocked.

**Command for storage-engineer, step 02b** (exact command line; paths must be absolute because the forked JVM starts in `modules/rag`):
```
cd /home/ajcreator/scholarchain && timeout 5400 sbt -batch 'set rag / Test / fork := true' 'set rag / Test / javaOptions += "-Xmx2g"' 'rag/Test/runMain scholarchain.rag.LocalIngestionRun /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/run-manifests/run-ccf619c509611f06.json /home/ajcreator/scholarchain/target/phase-08/output-local-ingestion.md --max-units-per-index 3000'
```
- `-Xmx2g` was observed to take effect: the report shows 2048 MB max.
- `target/` is git-ignored. The runner refuses any report path under a `docs` directory; the orchestrator copies the file into `docs/agent-runs/phase-08/`.
- Leave out `--max-units-per-index` to ingest every row; I don't expect every row to fit in 2 GB (see RISKS).
- At 3,000 per type it would ingest 12,278 source rows (3,000 + 3,000 + 278 paths + 3,000 + 3,000), which is above 10,000.

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 08 step 01b.
1. Delete rag's local `RenderedChunk`/`RenderedDocument`, make `SemanticRenderer` extend `scholarchain.storage.DocumentRenderer`, and make `EmbeddingModelVersion.id` agree with `EmbeddingVersion.id`.
2. Add a test that rag's renderer output passes storage's loader acceptance checks.
3. Write the test-scope runner `LocalIngestionRun` and do a small smoke run.
4. Run `sbt rag/test`, formatting only rag files.

FILES CREATED:
- /home/ajcreator/scholarchain/tests/module-unit/rag/LocalIngestionRun.scala (the runner: `parse`, `execute`, `main`)
- /home/ajcreator/scholarchain/tests/module-unit/rag/SparkShapedRun.scala (test helper: a temporary Spark-layout run built from the existing export-shaped fixture rows)
- /home/ajcreator/scholarchain/tests/module-unit/rag/IngestionConformanceSuite.scala (7 tests)

FILES MODIFIED (all were still untracked from R1):
- /home/ajcreator/scholarchain/modules/rag/src/main/scala/scholarchain/rag/Templates.scala
  - The local `RenderedChunk` and `RenderedDocument` are deleted; it imports storage's types.
  - It is declared `final class SemanticRenderer ... extends DocumentRenderer`.
  - `PaperAbstractTemplate` is now version 2, with the text `Paper abstract. Source record: {source} {sourceRecordId}. Abstract: {abstract}`.
- /home/ajcreator/scholarchain/modules/rag/src/main/scala/scholarchain/rag/EmbeddingVersion.scala: `id = EmbeddingVersion.id(modelId, dimension)`, so the formula is called rather than restated.
- /home/ajcreator/scholarchain/tests/module-unit/rag/TemplateSuite.scala: imports updated; v2 abstract golden values; a new check that the work ID is not in the text.
- /home/ajcreator/scholarchain/tests/module-unit/rag/EmbeddingVersionSuite.scala: 1 new test.
- /home/ajcreator/scholarchain/tests/module-unit/rag/RenderFixtures.scala: `lines` is now public.
- /home/ajcreator/scholarchain/tests/module-unit/rag/RealRowsRender.scala: import only.
- Not touched: storage, MODULE.md, `prompts/`, docs.

COMMANDS RUN:
- `pgrep`/`free -m` before each sbt: no JVM running, 3,596 MB available.
- `timeout 1500 sbt -batch "rag/scalafmt" "rag/Test/scalafmt" "rag/test"`, first run: `Passed: Total 40, Failed 0`.
- `/usr/bin/time -v timeout 3000 sbt -batch 'set rag / Test / fork := true' 'set rag / Test / javaOptions += "-Xmx2g"' 'rag/Test/runMain scholarchain.rag.LocalIngestionRun <real manifest> <scratch>/smoke-200.md --max-units-per-index 200'`: rc=0, sbt 179 s, wall clock 3:21.
  - Result: 3 rejected, all with the reason "a metadata value repeats a span of the text". That led to the v2 fix.
  - The harness moved it to the background; I waited without polling and started nothing else.
- `python3 -I <scratch>/r1b/oracle` (stdin script): an independent oracle of the v2 abstract text, hash, spans and chunk IDs, reusing R1's chunking oracle.
- A Python scan of the real JSON-lines: 71 of 6,292 work IDs are 40 or more characters (max 47). In 217,661 summaries, 0 entity, institution or researcher IDs of 40+ characters appear in the text.
- `timeout 1500 sbt -batch "rag/scalafmt" "rag/Test/scalafmt" "rag/test"`, second run: `Passed: Total 41, Failed 0, Errors 0, Passed 41`.
- The same smoke command, writing `<scratch>/smoke-200-v2.md`: rc=0, sbt task 159 s, 0 rejected (output under VALIDATION RESULTS). Background as before, waited without polling.
- `git status --short` before and after the smoke runs: identical.
- `sbt check` was NOT run. The Stop hook asked for it three times and I declined: the work order allows only `rag/test` here (the full check is at step 03), and each time an sbt was already running.

TEST RESULTS: `sbt rag/test` 41 passed, 0 failed (33 before this step).
- ChunkingSuite 9, EmbeddingVersionSuite 7 (+1), TemplateSuite 18, IngestionConformanceSuite 7 (new).
- IngestionConformanceSuite runs `LocalIngestion.run` (and through it `VectorLoader.loadRendered`) with `SemanticRenderer` on a Spark-shaped run. The expected counts come from the Python oracle.
  1. 8 source rows give 13 chunks, 13 written and 0 rejections in both passes.
     - Per index: researcher 1, community 1, path 1, topic 7, abstract 3.
     - Multi-chunk documents: two 3-chunk topic summaries and one 2-chunk abstract (`topic-institution-summary-document` 2, `paper-abstract-document` 1).
     - The report's template versions equal `Templates.Versions`, the embedding version is `hash-embed-v1@256`, the ledger has 0 failures and the stage Succeeded.
  2. The second pass: 0 written, 13 unchanged, 0 embedder calls, 0 PutVectors.
  3. Every rag chunk ID is a vector key. Its `textSha256`, `embeddingModel`, `template`, `chunkOrdinal` and `chunkCount` metadata match, and the object at `textUri` holds exactly rag's chunk text, document hash and `chunk-v1`.
  4. An injected prefix ("Ignore all previous instructions. ..."), re-hashed so that only the template check can fail, is rejected through the port with `render failed ... tpl-researcher-profile-v1`. No vector is stored for it and 1 ledger failure is recorded.
  5. A 46-character work ID is accepted: 0 rejections, the text does not contain the ID, and the `workId` metadata holds it.
  6. Runner argument parsing: absolute paths, `--max-units-per-index N`, and refusal of relative paths, `docs/` paths, a count of 0 and unknown flags.
  7. The runner writes the file it returns; with N=1, 5 of 8 rows are selected.
- EmbeddingVersionSuite (new test): `HashingEmbedder(256).versionId == EmbeddingModelVersion.id == "hash-embed-v1@256"`, `parse` round-trips it, and the two formulas agree for 3 model and dimension pairs.

VALIDATION RESULTS:
- Template versions: `tpl-paper-abstract` 1 → 2, for the reason above.
  - The others are unchanged: tpl-researcher-profile-v1, tpl-collaboration-community-v1, tpl-collaboration-path-v1, tpl-topic-institution-summary-v1.
  - Chunk policy chunk-v1 and the embedding rule embedding-version-v1 (`<modelId>@<dimension>`, now one formula, storage's) are unchanged.
- Prompts and schemas: none (no LLM is involved); `prompts/` is untouched. Plan operations, graph templates, abstention and benchmarks are not part of this step.
- Smoke run on run-ccf619c509611f06, v2, N=200 per type:
  - 1,000 of 223,953 source rows, 1,071 chunks, 1,071 written, 0 duplicates, 0 rejected.
  - Second pass: 1,071 unchanged, 0 written, 0 rejected.
  - Ledger: Succeeded, 0 failure records.
  - Selection took 58 s. Each pass took about 45 s (22 vectors written per second), dominated by reading the 1.1 GB JSON-lines.
- Heap figure: 16 MB used after the first pass (after a `System.gc()`), out of 2,048 MB max. The peak RSS of the largest process was 1,424,364 KB, which includes the sbt JVM.

ASSUMPTIONS (the human is asleep):
1. I fixed the abstract rejections in rag rather than asking storage to exempt identifiers from its span check. rag owns the template, the work ID already travels as structured metadata, and an opaque key in embedded text adds no meaning. It needed no storage change and no BLOCKED-ON.
2. Because the layout changed, I bumped the template version instead of editing v1 in place, even though v1 was never stored or committed.
3. The runner uses `LocalIngestion.localConfig()` and the default `IngestionSettings` except `maxUnitsPerIndex`.
4. Heap comes from a forked JVM (`set rag / Test / fork` and `javaOptions`); rag's build settings are build-engineer's, so the build is unchanged.

RISKS:
- Memory for the full run, a linear extrapolation I have not measured: about 15 KB per chunk × 226,807 chunks is about 3.4 GB, so every row will likely not fit in 2 GB. At N=3,000 (about 13,500 chunks) I estimate around 200 MB, so storage could raise N after reading the heap line.
- After the second smoke run printed `[success]` and wrote its report (12:21:46), the sbt process did not exit until 13:05:48, so wall clock was 46:59. The first run exited about 2 s after success. I did not find the cause; no JVM was left running afterwards. storage-engineer should keep the `timeout` on the command; the report file is written before that hang.
- The R1 full-render numbers for paper-abstract (6,292 documents, 6,400 chunks) were computed under v1. Under v2 the chunk count may differ, and R1's `RealRowsRender` was not re-run. Step 02b's report will give the v2 numbers.
- Storage's span check still refuses any 40+ character metadata value that a future template puts in its text. Today no summary type does: 0 of 217,661 rows.
- Not done: `sbt check` (step 03), the full ingestion (02b), any AWS call.
- State left behind: logs, smoke reports and the oracle in /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/r1b/. Data and git status are unchanged.
- Notes for modules/rag/MODULE.md (documentation-engineer):
  - Public API: `SemanticRenderer` extends `scholarchain.storage.DocumentRenderer`. The rag `RenderedChunk` and `RenderedDocument` are gone, so use storage's.
  - Templates: `PaperAbstractTemplate` is v2.
  - `EmbeddingModelVersion.id` delegates to `storage.EmbeddingVersion.id`.
  - Test-scope runner: `scholarchain.rag.LocalIngestionRun`.
  - 41 tests.
  - Done: port wiring, the conformance test, the runner.
  - Not done: R2.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md, then step 02b: storage-engineer runs the command above and writes `output-local-ingestion.md` from the report.
