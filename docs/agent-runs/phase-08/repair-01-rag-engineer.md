HARNESS: claude-code
MODEL: claude-opus-5-5 (Claude Code `opus`, effort high; rag-engineer is high tier, run at its default)

The chunk limit now accepts exactly `maxChunks` chunks and refuses one more. `decide` now uses the loader's order, and a new test runs the real loader to keep the two from drifting. `sbt rag/test` passed 66 of 66 (it was 63). Golden spans and chunk IDs did not change, and `chunk-v1` is kept.

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 08 repair R1-1. MINOR-1 (the chunk limit was off by one) and NIT-4, rag side (`decide` order versus the loader). Then `sbt rag/test`.

FILES CREATED: none

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/rag/src/main/scala/scholarchain/rag/Chunking.scala
  - Line 61: the loop guard is now `count < p.maxChunks` (was `<=`).
  - Line 18 now reads: "Exactly `maxChunks` spans is accepted; a text that needs more is refused, never truncated."
  - The `ChunkPolicy.V1` comment no longer has the unclear "64,000 units, below what the longest export text needs". It now says that a text needing a 33rd chunk is refused.
- /home/ajcreator/scholarchain/modules/rag/src/main/scala/scholarchain/rag/EmbeddingVersion.scala
  - `decide` order is now: absent key gives Embed; other text gives RefuseOtherText, whatever the version; same text under another version gives NewIndexVersion; otherwise Skip.
  - Added a doc comment on `decide` naming the loader's order and the pinning test.
  - The rule comment now says the text check comes before the version check.
- /home/ajcreator/scholarchain/tests/module-unit/rag/ChunkingSuite.scala
  - New test "the maxChunks bound is exact" with the `small` policy:
    - "z"*330 gives exactly 8 spans, the last one Span(280,330).
    - "z"*331 and "z"*370 are refused with "more than 8 chunks".
    - With `maxChunks=1`: 50 characters is accepted, 51 is refused.
    - Under chunk-v1: "z"*57800 gives 32 spans, and "z"*57801 is refused.
- /home/ajcreator/scholarchain/tests/module-unit/rag/EmbeddingVersionSuite.scala
  - New test "precedence": a stored entry with another text and another version gives RefuseOtherText.
- /home/ajcreator/scholarchain/tests/module-unit/rag/IngestionConformanceSuite.scala
  - New test "the loader's skip and refusal order is EmbeddingModelVersion.decide's order":
    - It ingests the fixtures, then changes the stored vectors of 3 keys: text hash only, model only, and both.
    - It reloads over the same vector store, using a fresh object store and key-value store.
    - For each changed key it checks that the real `VectorLoader` rejection reason, mapped to an `EmbedDecision`, equals `decide` on the same stored values. The expected results are RefuseOtherText, NewIndexVersion and RefuseOtherText.
    - The other 10 chunks are skipped. Totals: rejected 3, unchanged 10, written 0.
    - Storage was not edited.

COMMANDS RUN:
- `timeout 900 sbt -batch "rag/scalafmt" "rag/Test/scalafmt" "rag/test"`: exit 0, first attempt. Output in /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/rag-test-1.txt
- Read-only commands: `cat`, `sed -n`, `grep` and `ls` over the review, the repair routing, rag, storage and docs.

TEST RESULTS: `sbt rag/test` passed 66 of 66 (Failed 0, Errors 0).

| Suite | Tests | Change |
|---|---|---|
| ChunkingSuite | 10 | +1 |
| EmbeddingVersionSuite | 8 | +1 |
| IngestionConformanceSuite | 8 | +1 |
| TemplateSuite | 18 | unchanged |
| RetrievalSmokeSuite | 10 | unchanged |
| SemanticContextSuite | 12 | unchanged |

VALIDATION RESULTS:
- **Chunk policy:** `chunk-v1` is kept (2000/1000/200/32).
  - The loop body did not change; only the iteration cap did. So any text accepted before and after gives byte-identical spans and chunk IDs.
  - TemplateSuite's golden spans and IDs and IngestionConformanceSuite's 13 chunks still pass unchanged.
  - The only change is that a text needing exactly 33 chunks under chunk-v1 (for example 57,801 to 59,600 characters with no whitespace) is now refused, as the documented rule always said.
  - No stored or exported output changes: the review reports that the longest real document has 17 chunks. I did not re-measure that.
- **Version rule:** `embedding-version-v1` is kept. The rule text already said a key holding another text is refused, and the enforced order now matches it.
- **Not touched:** prompts and schemas (`semantic-context-v1`), plan operations, graph templates and abstention. No LLM and no AWS were involved.

ASSUMPTIONS:
- I kept `chunk-v1` because no output on an accepted input changes. The newly refused 33-chunk case is a bug fix that brings the code back to the published limit, not a policy change.
- Matching the loader's rejection reason by substring ("already bound to different text", "another model") is an acceptable way to pin the order. If storage rewords either message, the test fails loudly rather than drifting silently.
- I did not revert the fix to watch the new tests fail on the old code. The review's Python check (370 characters accepted as 9 spans) and the old `decide` order mean the boundary and precedence tests would have failed before the fix.

RISKS:
- **Not done:** the full `sbt check` (it runs at R1-3); no edits to storage or MODULE.md.
- **State left behind:** rag source and tests changed, formatted by `rag/scalafmt` and `rag/Test/scalafmt` only.
- **Known failure mode:** the new conformance test depends on the wording of the loader's rejection messages in `VectorLoader.scala:716-727`.
- **Doc lines for documentation-engineer (R1-4):**
  - `docs/embeddings.md:89-91` ("at most 32 chunks"; "More than 32 chunks is refused") is now true, so no change is needed. Its own statement that it was wrong is resolved by the code fix.
  - `docs/embeddings.md:113-114` could add: "The text check comes first: a key holding other text is refused even under another embedding version; the same text under another version means a new index version." This is optional and not wrong as it stands.
  - `modules/rag/MODULE.md:72` ("`sbt rag/test` passed 63 of 63") needs a new line for R1-1: 66 of 66, with the 3 new tests above.
  - `modules/rag/MODULE.md`: `decide` now follows the loader's order (no public API change; only the order for the both-mismatch case changes from NewIndexVersion to RefuseOtherText). `Chunking.spans` refuses at exactly `maxChunks + 1`.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R1-2 (storage-engineer); R1-3's full `sbt check` must include these rag changes; R1-4 documentation-engineer updates the doc lines above.
