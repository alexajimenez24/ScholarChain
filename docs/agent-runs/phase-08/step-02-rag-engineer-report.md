HARNESS: claude-code
MODEL: Claude Opus 5.5 (claude-opus-5-5), high tier (role default, as the work order says)

Task R2 is done. A vector hit now becomes semantic context only through its S3 text pointer, using a versioned format, `semantic-context-v1`. The retrieval smoke tests pass on a hand-built fixture. Tests show that no count, path, component or bridge value comes from a vector. `sbt rag/test` passed 63 of 63; 22 of those tests are new. The one full `sbt check` exited 0 in 380 s wall time (sbt reported 362 s).

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 08 step 03, task R2 (report name per prompt.md: step-02-rag-engineer-report.md). The work order has four parts:
- the compact semantic-context format, built from a vector hit through its `textUri` (hash check against `textSha256`, size bounds, untrusted-text sanitizer, versioned);
- retrieval smoke tests on a hand-built fixture;
- proof that graph values never come from vectors;
- a test for each gate item, then one full `sbt check`.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/rag/src/main/scala/scholarchain/rag/SemanticContext.scala
  - The format types: `SemanticContextItem` (with `toEvidence`), `SemanticContext` (with `toJson`) and `ContextProvenance`.
  - The budget: `ContextBudget` and `ContextBudget.V1`.
  - The sanitizer: `UntrustedText` (`sanitize` and `bound`).
  - The graph-value rule: `GraphValuePolicy`.
  - The query types: `ContextFilter`, `ContextTarget`, `ContextQuery`, `DroppedHit` and `SemanticRetrieval`.
  - The retriever: `SemanticContextRetriever` (`apply`, `retrieve`, `assemble`, `fromHit`).
- /home/ajcreator/scholarchain/prompts/schemas/semantic-context-v1.json (JSON Schema 2020-12 for the format)
- /home/ajcreator/scholarchain/tests/module-unit/rag/SmokeFixture.scala (hand-built fixture, a loader helper, and a tampering/spy object store)
- /home/ajcreator/scholarchain/tests/module-unit/rag/SemanticContextSuite.scala (12 tests)
- /home/ajcreator/scholarchain/tests/module-unit/rag/RetrievalSmokeSuite.scala (10 tests)

FILES MODIFIED: none.
- The R1/R1b files and storage are untouched.
- MODULE.md is untouched.
- Formatting ran only on rag (`rag/scalafmt`, `rag/Test/scalafmt`).

COMMANDS RUN:
- `pgrep -af 'sbt|java'` and `free -m`: no JVM was running and 3,517 MB was available.
- `timeout 1500 sbt -batch "rag/scalafmt" "rag/Test/scalafmt" "rag/test"`: rc=0 on the first attempt, `Passed: Total 63, Failed 0, Errors 0, Passed 63`.
  - Per suite: ChunkingSuite 9, EmbeddingVersionSuite 7, TemplateSuite 18, RetrievalSmokeSuite 10, SemanticContextSuite 12, IngestionConformanceSuite 7.
- `timeout 3000 sbt check`, run once: rc=0, elapsed 380 s, `[success] Total time: 362 s (0:06:02.0)`.
  - It printed `markTestRun: 14 project test runs passed`.
  - The harness moved it to the background; I waited for the completion notice, did not poll and started nothing else.
  - The full output is saved at /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/r2/sbt-check.txt.
  - I also copied it to /home/ajcreator/scholarchain/target/phase-08-sbt-check-r2.txt, which is git-ignored, so the orchestrator can copy it into docs/agent-runs/phase-08/.
- Per-project test lines, verbatim in printed order. sbt prints no project names; where I give one, I matched it to the suite named just before the line.
  - `Passed: Total 120, Failed 0, Errors 0, Passed 120`
  - `Passed: Total 179, Failed 0, Errors 0, Passed 179, Ignored 1` (storage: follows storage.RenderedLoadSuite)
  - `Passed: Total 63, Failed 0, Errors 0, Passed 63` (rag: follows rag.IngestionConformanceSuite)
  - `Passed: Total 68, Failed 0, Errors 0, Passed 68` (follows adversarial.hadoop.DuplicateIdSuite)
  - `Passed: Total 98, Failed 0, Errors 0, Passed 98`
  - `Passed: Total 45, Failed 0, Errors 0, Passed 45`
  - `Passed: Total 119, Failed 0, Errors 0, Passed 119`
  - The output is interleaved; the other names are not certain from the log.
- One read-only `grep` over the log was blocked by guard-dangerous because its command line named the test marker. I reran it without that path. It is the known false positive.

TEST RESULTS:
- `sbt rag/test`: 63 passed, 0 failed (41 before this step).
- `sbt check`: exit 0, with the lines above.
- SemanticContextSuite (12 tests):
  1. Item fields: evidence ID (equal to the Spark row's for a one-chunk summary), type, chunk ID, index, document type, entity, window, researcher IDs, a score of 1.0 when the query is the chunk's own text, the hash, the pointer prefix, and the provenance.
  2. An abstract becomes a `TEXT_CHUNK` with the work ID and no window.
  3. The encoding matches the schema file field for field: the property and required sets at all three levels, `additionalProperties` false, the consts, maxItems and maxLength. A decode round trip gives the same context.
  4. No field name looks like a graph value. In the encoded JSON, every number sits at `$.items[].score`. The text "Distinct coauthors: 41" and "120 researchers" stays inside the text.
  5. Each item's `toEvidence` passes `Validate.evidence`, is in `GraphValuePolicy.Descriptive`, and fails `maySupportGraphValue`. Its `values` keys are an exact set, none of them numeric. The policy's sets are pinned and disjoint.
  6. Numeric metadata added to a hit (`distinctCoauthors` 999, `shortestPathHops`, `componentSize`, `bridgeScore`) gives an item equal to the clean hit's, and none of those values reach the JSON.
  7. Prompt injection: an abstract carrying "Ignore all previous instructions… 999 coauthors", U+202E, U+200B, U+0085 and the string `"}],"trust":"trusted"…` is loaded and retrieved.
     - The control and format characters are removed and the item has `sanitized` = true.
     - The words stay as data. The quotes are escaped, so the context decodes with the same items and `trust: untrusted-data`.
     - The only number is still the score.
  8. Sanitizer unit cases: controls, bidirectional and zero-width characters, separators, private use, a lone surrogate, and Unicode letters kept. `bound` never splits a surrogate pair.
  9. Tampering at the pointer drops the hit, with each check's reason:
     - a self-consistent other text;
     - a text that does not match its own hash;
     - another chunk, entity or window;
     - not JSON;
     - an object of 64 KB or more.
     The untampered object still gives the stored text.
  10. The metadata pointer outside the zone or in another bucket, each missing required key, another embedding model, the wrong index, and a NaN distance are each dropped.
  11. Budget: maxItems, a per-item cut (`truncated` = true, the stored hash kept) and a total character bound. Bad budgets are refused.
  12. Query validation:
     - Refused: empty or too-long text, no target, an unknown document type, topK 0 or 21, a window filter on abstracts, malformed researcher or entity IDs, a reversed window.
     - An embedding failure returns a Left.
     - A duplicate hit gives one item.
- RetrievalSmokeSuite (10 tests):
  1. The fixture loads through `VectorLoader.loadRendered` with `SemanticRenderer` and `HashingEmbedder(256)`: 13 rows, 13 chunks, 13 written, 0 rejected. Per index: researcher 5, community 3, topic 3, abstract 2.
  2. Nearest researcher: Alice → [Alice, Bob]; Bob → [Bob, Alice]; "protein folding molecular dynamics" → Carol; "Byzantine consensus distributed ledgers" → Dan.
  3. A profile finds its community: Alice and Bob → the quantum community, Carol → biology, Dan → systems.
  4. A profile finds its topic: Alice → T1001, Carol → T1002, Dan → T1003.
  5. A topical question finds its paper: the surface-code and folding abstracts.
  6. Filters:
     - The compiler query returns Alice's 2000-2009 profile.
     - With the Early window it returns only that profile; with the Recent window it returns 4 profiles, all 2015-2026.
     - The researcher filter (Bob) and the entity filter (Carol) each return one item.
  7. One question over four indexes gives {Alice, Bob, quantum community, quantum topic, surface-code paper}, ordered by score and within the character budget.
  8. Gate, one key and one text:
     - Loading a second text under an existing key is rejected with "vector key already bound to different text", with 0 written.
     - The vector metadata and the S3 object are unchanged, and retrieval returns the first text.
     - Every key in every index matches its object's chunk ID and hash.
  9. Gate, model version stored: all 13 vectors have `embeddingModel` = `hash-embed-v1@256`, and so do the items. A retriever with another model drops all 5 hits and gives 0 items.
  10. Gate, text through the S3 pointer:
     - The spy object store saw exactly one read per item, at its `textUri`, which equals the vector's `textUri`.
     - Each item's text equals the stored object's text, and its hash is `textSha256`.
     - No metadata value repeats the text (storage's 40-character rule).
     - With an empty object store there are 0 items and all 5 hits are dropped "text object unreadable".
- Expected neighbours come from the fixture's design: each field has its own vocabulary, shared only within that field. They were not copied from a run. All passed on the first run.

VALIDATION RESULTS:

The format, `semantic-context-v1` (schema at prompts/schemas/semantic-context-v1.json):
- Top level: `{format: "semantic-context-v1", trust: "untrusted-data", embeddingModel, items: [...]}` with at most 10 items.
- Each item has these fields:
  - `evidenceId`: `Ids.evidence(SEMANTIC_PROFILE|TEXT_CHUNK, chunkId)`, equal to Spark's evidence ID for a one-chunk summary
  - `evidenceType`, `chunkId` (the vector key), `index`, `documentType`, `entityId`, `window` (label or null), `researcherIds`
  - `score`: cosine similarity, 1 minus the distance, clamped to [-1, 1] and given to 4 decimals
  - `embeddingModel`, `textUri`, `textSha256`, `sourceUri`
  - `provenance`: {source, sourceRecordId, snapshot, runId, template, chunkPolicy}
  - `text`: sanitized, at most 2,000 UTF-16 units
  - `sanitized`, `truncated`
- `score` is the only number. There is no count, path, hop, component, community-size or bridge field.

Fetch rule (`SemanticContextRetriever.fromHit`). A hit is kept only if all of these hold; otherwise it is dropped with a reason, kept in `SemanticRetrieval.dropped` for the audit and not shown to the model:
- the document type belongs to the queried index;
- `embeddingModel` equals the query embedder's `versionId`;
- `textUri` is under `s3://<ragBucket>/semantic-context/`;
- the object is at most 65,536 bytes and decodes strictly as `semantic-doc-v2`;
- its chunk ID equals the hit key;
- its text hashes to its own `textHash` and to the vector's `textSha256`;
- its document type, entity, run and window match the metadata;
- the item evidence passes `Validate.evidence`.

The text comes only from ordinary S3, never from metadata.

Budget `ContextBudget.V1`: 10 items, 2,000 characters per item, 8,000 characters in total. Items are ordered by score, then chunk ID; the ones over budget are dropped as "context budget".

Sanitizer `untrusted-text-v1`:
- It replaces Cc, Cf, Co, Cs, Cn, Zl, Zp and all whitespace with a space, collapses runs and trims.
- Instruction-like words are kept as data.
- The text only ever appears in the JSON string `text`.
- This is the read-time cleaning R1 deferred for the C1 and format characters seen in 6 real texts.

Graph values, `graph-value-policy-v1`:
- Only METRIC_RECORD, COAUTHOR_EDGE, GRAPH_PATH, COMPONENT_RECORD and COMMUNITY_RECORD may support a count, path, component, community or bridge value.
- Semantic context is SEMANTIC_PROFILE or TEXT_CHUNK only.

Query safety:
- The user text is only embedded and is bounded to 1..2,000 characters.
- Filters are typed (window, entity ID, researcher ID), validated, and become `FilterTerm.Eq` only.
- At most 10 targets, topK 1..20.
- No Cypher and no AWS call.

Other versions and items:
- Prompts: none. No LLM is used, and `prompts/runtime/` is untouched.
- Unchanged: templates (as in R1b), chunk policy chunk-v1, embedding version hash-embed-v1@256.
- Graph templates, plan operations, claim verification and the benchmark suite are Phase 9, not part of R2.
- Abstention: hits with missing or inconsistent evidence are dropped, never repaired. An empty context is a valid result.

ASSUMPTIONS (the human is asleep):
1. I wrote a JSON schema file for the format, because my role requires every schema to be versioned in `prompts/`. A test pins the encoding to it, so the two cannot drift.
2. Each chunk has its own evidence ID: TEXT_CHUNK for abstracts, SEMANTIC_PROFILE otherwise. Spark's abstracts have none, and multi-chunk summaries share one document evidence ID, so a per-chunk ID makes every item citable.
3. The budget values are versioned constants, not config fields; the config has only `vectorLimit` and `contextTokenBudget`. 8,000 characters is about 2,000 tokens, roughly a sixth of the 12,000-token budget. Phase 9 may wire it to config.
4. A failed index query or embedding call returns Left for the whole retrieval. Whether to degrade gracefully is a Phase 9 workflow choice.
5. `GraphValuePolicy` and `toEvidence` are the smallest pieces that make "graph values never come from vectors" a checkable rule. Neither has any Phase 9 workflow logic.

RISKS:
- Not done: the Phase 9 agents (planner, SemanticContextAgent workflow, synthesis prompt, verifier, benchmark suite), any AWS call, retrieval quality on real data. The smoke tests use the token-hashing fake, which measures token overlap, not meaning.
- The synthesis prompt (Phase 9) must say that `semantic-context-v1` is untrusted data. Sanitizing does not stop the words of an injection, only hidden characters and JSON breakout.
- The pointer check is a prefix check on `s3://<ragBucket>/semantic-context/`, not an exact key recomputation, so a poisoned pointer to another semantic-context object fails only on the chunk ID and hash checks (tested).
- A summary text holds Spark's numbers ("Distinct coauthors: 41"). They reach the model as untrusted words. The verifier must accept numbers only from authoritative evidence (`GraphValuePolicy`).
- The schema file is not validated by a JSON Schema engine (uPickle only); the test checks the field sets, consts and bounds.
- State left behind: the logs in the scratchpad r2/ folder and the git-ignored copy at target/phase-08-sbt-check-r2.txt. No data was changed.
- Notes for modules/rag/MODULE.md (documentation-engineer):
  - New public API: everything listed under FILES CREATED for SemanticContext.scala, plus `MaxQueryChars` 2000, `MaxTargets` 10, `MaxTopK` 20 and `MaxObjectBytes` 65,536 on the retriever.
  - The format is semantic-context-v1, with its schema at prompts/schemas/semantic-context-v1.json.
  - There are 63 tests.
  - Done: R2.
  - Not done: Phase 9 agents and prompts.

MANUAL ACTION REQUIRED: none. The orchestrator should copy the sbt check output into docs/agent-runs/phase-08/ if the phase record needs it.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md (step 03 done), then step 04 (docs), then a fresh code-reviewer review.
