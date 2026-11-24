# Module `rag`

## Purpose

Phase 08 scope: deterministic rendering templates, the chunk policy, the embedding
version rule and the compact semantic-context format for the synthesis agent.
Later phases add query plans, retrieval fusion, evidence bundles and claim verification.
The runtime prompts live under `prompts/`.
Full description: [docs/embeddings.md](../../docs/embeddings.md).

## Owner and paths

- Owner: rag-engineer (documentation-engineer owns only this file).
- sbt project: `rag`; folder: `modules/rag`.
- Sources: `modules/rag/src/main/scala/scholarchain/rag/`: `Chunking`,
  `EmbeddingVersion`, `Templates` and `SemanticContext`.
- Unit-test root: `tests/module-unit/rag/` (never `src/test`): `ChunkingSuite`,
  `EmbeddingVersionSuite`, `TemplateSuite`, `IngestionConformanceSuite`,
  `SemanticContextSuite` and `RetrievalSmokeSuite`, with the helpers `RenderFixtures`,
  `SparkShapedRun`, `SmokeFixture`, `RealRowsRender` and `LocalIngestionRun`.
- Schema: `prompts/schemas/semantic-context-v1.json`.

## Public API

Package `scholarchain.rag`.
Sources: the Phase 08 reports `step-01-rag-engineer-report.md`,
`step-01b-rag-engineer-report.md` and `step-02-rag-engineer-report.md` in
`docs/agent-runs/phase-08/`.

- Rendering: `SemanticRenderer(cfg: VectorConfig, policy = ChunkPolicy.V1):
  Either[String, SemanticRenderer]`.
  It extends storage's `DocumentRenderer` port and uses storage's `RenderedChunk` and
  `RenderedDocument`.
  Methods `renderSummary(EvidenceItem)`, `renderAbstract(PaperAbstract)`,
  `templateVersions` and `SemanticRenderer.conflicts(docs)`.
- `Templates.Versions`, `Templates.summary`, `Templates.conforms`.
  Template versions: tpl-researcher-profile-v1, tpl-collaboration-community-v1,
  tpl-collaboration-path-v1, tpl-topic-institution-summary-v1 and
  tpl-paper-abstract-v2 (v1 wrote the work ID into the text and is retired).
- Chunking: `ChunkPolicy.V1` (chunk-v1: 2,000 UTF-16 units, 200 overlap, 32 chunks),
  `Chunking.spans` and `Chunking.chunkIds`.
- Embedding versions: `EmbeddingModelVersion.of`, `fromConfig`, `parse`, `decide`,
  `nextIndexName`, `EmbedDecision` and `StoredEmbedding`.
  `EmbeddingModelVersion.id` delegates to storage's `EmbeddingVersion.id`
  (`<modelId>@<dimension>`; local `hash-embed-v1@256`).
- Semantic context (`SemanticContext.scala`):
  - Types `SemanticContextItem` (with `toEvidence`), `SemanticContext` (with `toJson`)
    and `ContextProvenance`.
  - `ContextBudget` and `ContextBudget.V1` (10 items, 2,000 characters per item, 8,000
    in total).
  - `UntrustedText` (`sanitize`, `bound`), the sanitizer `untrusted-text-v1`.
  - `GraphValuePolicy` (`graph-value-policy-v1`) with `Descriptive` and
    `maySupportGraphValue`.
  - Query types `ContextFilter`, `ContextTarget`, `ContextQuery`, `DroppedHit` and
    `SemanticRetrieval`.
  - `SemanticContextRetriever` with `apply`, `retrieve`, `assemble` and `fromHit`.
    Limits `MaxQueryChars` 2,000, `MaxTargets` 10, `MaxTopK` 20 and `MaxObjectBytes`
    65,536.
- Format `semantic-context-v1`: `{format, trust: "untrusted-data", embeddingModel,
  items}`; `score` is the only number.
- Test-scope runner: `scholarchain.rag.LocalIngestionRun` (in `tests/module-unit/rag/`).

## Done

- R1 (step 01): templates, chunk policy and embedding version rule; 33 tests.
  Every row of `run-ccf619c509611f06` rendered with 0 rejections and 0 conflicts:
  223,953 documents became 226,807 chunks (under the v1 abstract template).
- 01b: the port wiring, `tpl-paper-abstract-v2`, `IngestionConformanceSuite` and the
  runner; 41 tests.
  A smoke run with 200 rows per type gave 1,071 chunks and 0 rejected.
- R2 (step 03): `semantic-context-v1`, its schema, `SemanticContextSuite` 12 tests and
  `RetrievalSmokeSuite` 10 tests; `sbt rag/test` passed 63 of 63.
- Phase 08 repair R1-1: `Chunking.spans` accepts exactly `maxChunks` chunks (32 under
  chunk-v1) and refuses a 33rd; the loop guard was off by one.
  `chunk-v1` is kept, and golden spans and chunk IDs are unchanged.
  `EmbeddingModelVersion.decide` now follows the loader's order: other text is refused
  before a version mismatch, so the both-mismatch case is RefuseOtherText, not
  NewIndexVersion.
  There is no public API change.
  Three new tests: the exact chunk bound, `decide` precedence, and a conformance test
  that runs the real loader and compares its rejection reasons with `decide`.
  `sbt rag/test` passed 66 of 66, and the full `sbt check` exited 0
  (`docs/agent-runs/phase-08/output-sbt-check-r1.txt`).
- The gate items are covered by tests: one key never refers to two texts, the model
  version is stored, text is fetched through the S3 pointer, the smoke tests pass and
  graph values never come from vectors.
- The one full `sbt check` exited 0 in 362 s, with `markTestRun: 14 project test runs
  passed` (`docs/agent-runs/phase-08/output-sbt-check-step03.txt`).

## Not done

- The Phase 9 workflow: planner, SemanticContextAgent, synthesis prompt, verifier,
  graph templates, plan operations, abstention and the benchmark suite.
  No LLM prompt exists and `prompts/runtime/` is untouched.
- Retrieval quality on real data: the smoke tests use the token-hashing fake, which
  measures token overlap, not meaning.
- Any AWS call.

## Known issues

- Any change to Spark's summary layout makes every row of that type fail the
  conformance check until the template version is bumped.
  This is by design and visible.
- C1 and format characters (for example 0x8E and U+200B in 6 real texts) are kept
  verbatim in the stored text, because changing them would change its hash.
  The read-time sanitizer removes them.
- Sanitizing does not stop the words of a prompt injection.
  The Phase 9 synthesis prompt must say that `semantic-context-v1` is untrusted data.
- Summary texts carry Spark's numbers (for example "Distinct coauthors: 41").
  They reach the model as untrusted words; the verifier must accept numbers only from
  authoritative evidence.
- The pointer check is a prefix check on `s3://<ragBucket>/semantic-context/`, not an
  exact key recomputation; a poisoned pointer to another object fails on the chunk ID and
  hash checks.
- The budget values are versioned constants, not config fields.
- A failed index query or embedding call gives a `Left` for the whole retrieval.
- The schema file is not validated by a JSON Schema engine; a test pins the field sets,
  constants and bounds.
- The 223,953-document total is the v1 render; under v2 the abstract chunk count is
  6,387 (`output-local-ingestion.md`), so the total chunk count of the full export under
  v2 is not measured.
- One sbt process took 47 minutes to exit after success (step 01b); the cause was not
  found.

## Dependencies

- Projects: `core`, `storage`.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
