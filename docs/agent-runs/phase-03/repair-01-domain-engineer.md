HARNESS: claude-code
MODEL: sonnet (claude-sonnet-5-5), standard tier, role default (no per-task override)

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: P03-R1-1, Phase 03 repair round 1 (domain-engineer part): MAJOR-1, MAJOR-2, MINOR-1 to MINOR-6, plus the T6/T7 decision and the AnswerClaim statement.

FILES CREATED:
- /home/ajcreator/scholarchain/tests/module-unit/core/resources/wire/author-mention.json (new frozen wire example)

FILES MODIFIED (all inside modules/core/** and tests/module-unit/core/**):
- modules/core/src/main/scala/scholarchain/core/Model.scala
- modules/core/src/main/scala/scholarchain/core/Ids.scala
- modules/core/src/main/scala/scholarchain/core/JsonLines.scala
- modules/core/src/main/scala/scholarchain/core/Validate.scala
- modules/core/src/main/scala/scholarchain/core/Config.scala
- tests/module-unit/core/Fixtures.scala, SerializationSuite.scala, ValidateSuite.scala, IdsSuite.scala, ConfigSuite.scala
- tests/module-unit/core/resources/wire/work.json (bytes changed, see below)
- Not touched: MODULE.md, build.sbt, project/**, tests/contracts/**, scholarchain-v1.yaml (no value changed).

FROZEN FILES THAT CHANGED BYTES
- wire/work.json: each nested author mention now ends with a `provenance` object (schemaVersion `author-mention-v1`, same source, snapshot and run as the work). Reason: MAJOR-2 added a field to AuthorMention.
- wire/author-mention.json: new, the first mention of the work fixture.
- The 17 other wire files are byte-identical, and their byte-equal tests pass unchanged.
- The files were produced by a Python JSON transform that keeps field order and has no trailing newline, as the others have none. SerializationSuite then confirmed the bytes equal the uPickle output.

COMMANDS RUN (all observed successful)
- `sbt fmt`
- `sbt check` (two runs: the first had 3 failing tests, all from my own test or loader errors, fixed; the second is green)
- `pgrep -af sbt` (it matched only my own shell line, so no other sbt ran)

TEST RESULTS
Final `sbt check` tail, real output with colour codes removed:
```
Test run scholarchain.core.NormalizeSuite finished: 0 failed, 0 ignored, 4 total
Test run scholarchain.core.IdsSuite finished: 0 failed, 0 ignored, 12 total
Test run scholarchain.core.ValidateSuite finished: 0 failed, 0 ignored, 24 total
Test run scholarchain.core.SerializationSuite finished: 0 failed, 0 ignored, 29 total
Test run scholarchain.core.ConfigSuite finished: 0 failed, 0 ignored, 20 total
[info] Passed: Total 89, Failed 0, Errors 0, Passed 89
[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run
[success] Total time: 3 s, completed Nov 22, 2026, 11:35:13 AM
```
That is 69 before and 89 now. The new tests are 1 in Ids, 6 net... precisely: IdsSuite +3, ValidateSuite +8, SerializationSuite +4 (author-mention freeze and 3 others), ConfigSuite +5.

FINDINGS (each with its proving test)
- MAJOR-1 FIXED. Decoding is total.
  - `JsonLines.decode` reads, scans the decoded object for JVM nulls (case-class fields, options, collections, map values) and returns `Left("... null at $.provenance")`.
  - `decodeValid` wraps `validate` in `Try`, so a throwing validator is a `Left`.
  - Tests in SerializationSuite: "a JSON null in a required field is a Left, never an exception" covers `provenance`, `authors`, `title`, `provenance.sourceRecordId`, `authors[0].provenance`, CoauthorEdge `researcherA` and AuthorMention `provenance`, through both `decode` and `decodeValid`. Also "a validator that throws is a Left from decodeValid".
- MAJOR-2 FIXED.
  - AuthorMention now has `provenance: Provenance` (last field) and `SchemaVersions.Mention = "author-mention-v1"`, added to `Supported` (now 18).
  - `Validate.mention` checks the mention ID shape, the work ID shape, position >= 0, `mentionId == Ids.mention(workId, position)`, a non-empty name and provenance with the mention schema version. `Validate.work` calls it per author.
  - Tests: `freeze("author-mention")`, the updated coverage test (carried set equals Supported, no duplicates) and ValidateSuite "a mention without provenance data, or with the wrong schema version, is rejected".
- MINOR-1 FIXED. One failing-input test per rule (ValidateSuite):
  - Mention ID recomputed: "a mention ID is recomputed from work and position".
  - Distinct mention IDs per work: "two authors of one work cannot share a mention ID".
  - Graph edge, covering the foreign tag, the endpoint types and whitespace: "graph edge: tag of another type, bad endpoint shapes and non-ID endpoints are rejected".
  - Decision ID recomputed, endpoint shapes by kind, policy version: "match decisions: recomputed ID, endpoint shapes and policy are checked".
  - representativeWorkIds and maximumTeamWorkIds: "representative and team work IDs must be work IDs".
  - Non-negative and finite counts: "metrics counts must be non-negative and finite".
  - Snapshot `YYYY-MM-DD` as a real calendar date: "provenance.snapshot must be a calendar date YYYY-MM-DD".
  - Graph edge details:
    - The ID tag must equal `Ids.edgeTag(edgeType)`, a new public helper.
    - COAUTHORED_WITH needs researcher endpoints.
    - AUTHORED runs researcher or mention to work.
    - RESOLVES_TO runs mention to researcher.
    - Other types need non-blank endpoints without whitespace.
    - The graph edge ID cannot be recomputed in full, because the policy and evidence inputs are not in the record.
- MINOR-2 FIXED.
  - Placeholders: a typed placeholder with a whole-number default (for example `NEPTUNE_PORT`) now rejects NaN, infinite, fractional and |n| > 2^53 values, with the message "config placeholders with unacceptable numbers: ...". The first attempt left the bad value as a string, but uPickle parses strings leniently, so the loader now reports them itself.
  - Overrides: an override of a whole-number field must be a finite whole number. A fractional field such as a threshold stays fractional but finite.
  - Ranges now validated: neptune.port 1..65535, queryTimeoutMs >= 1, vectors.dimension >= 1, maxBlockSize >= 1, every operations limit (retries >= 0, others >= 1), maxLlmCallsPerRun >= 0, every graph and retrieval parameter >= 1, non-blank policy and metric versions, and non-empty `vectors.indexes`.
  - In aws mode with `llmEnabled`, `models.synthesisModel` and `models.verificationModel` are required.
  - Tests in ConfigSuite: "NaN, infinite and fractional numbers are rejected for integer fields", "every numeric field has a range check" and "aws mode with the LLM enabled needs synthesis and verification model IDs".
  - The existing `not a number` substring is kept for non-numeric overrides.
- MINOR-3 FIXED. The code constant 6 is gone. `retrieval.maxPathDepth` must be in `1..graph.pathMaxDepth`, and `graph.pathMaxDepth >= 1`. Test: "retrieval.maxPathDepth is bounded by graph.pathMaxDepth from the file, not by a constant". It shows 7 is rejected against 6, 8 is accepted when the override raises pathMaxDepth to 9, and 0 is rejected for each.
- MINOR-4 FIXED.
  - `Ids.run` rejects any manifest number with |n| >= 2^53 (`Ids.MaxManifestNumber`) with an IllegalArgumentException telling the caller to use a string. I chose rejection because ujson parses numbers as doubles, so exact canonicalisation from the source text would need a new parser.
  - `Ids.work` now requires its result to satisfy `isWorkId`, so the 400-character bound and the whitespace check agree.
  - Tests in IdsSuite: "run IDs distinguish what they can and reject what they cannot represent" and "Ids.work enforces the length bound isWorkId checks".
- MINOR-5 DECIDED, NO CODE CHANGE beyond a doc comment and a test.
  - Decision: the researcher ID stays `sc:r:v1:` plus the hash of the sorted mention set. It is stable within a run and policy, not across runs.
  - Reason: spec line 1091 asks only for "a versioned ScholarChain identity ID" and nowhere requires cross-run stability. An anchor scheme would need a re-keying rule and a mapping table, which is a larger change. A validated ORCID is the cross-run key.
  - Doc comment: added to `Ids`.
  - Test: "a researcher ID names its exact mention set (stable within a run and policy)".
- MINOR-6 FIXED. ConfigSuite "a # inside a string value or array element is not a comment" covers `"x": "a # b"` and the array element `"#z"`. It also loads the bundled config with a `"q # 1"` service name and asserts it survives.
- T6 and T7 (unknown fields, fractional year) REJECTED. `JsonLines.decode` re-writes the decoded value and requires the JSON to equal the input, which rejects an unknown field, a fractional `year` such as 2021.7 and any other lossy value. Test: "unknown fields and a fractional year are rejected, not ignored or truncated". Reason: the contract is frozen, and an additive field needs a new schema version (iron law 5).

WHY AnswerClaim CARRIES NO Provenance
Its provenance is its `evidenceIds`. Hard rule 3 asks claims to carry evidence IDs, and `Validate.claim` enforces them: each ID is well formed, and a claim that is not UNSUPPORTED must have at least one. A source dataset record, a snapshot or a run ID would be wrong on a claim, which comes from a runtime answer. The enclosing AnswerResponse carries Provenance, `promptVersion` and `modelVersion`.

VALIDATION RESULTS
- Sample, wire/author-mention.json: `{"mentionId":"m:8a86a56410344f5199a5","workId":"dblp:conf/test/A21","position":0,"rawName":"Ada Testwright","normalizedName":"ada testwright","orcid":null,"externalAuthorId":null,"affiliation":null,"provenance":{"source":"DBLP","sourceRecordId":"conf/test/A21","snapshot":"2026-11-01","runId":"run-7c97c16f285cc5f8","schemaVersion":"author-mention-v1","policyVersion":null}}`
- Stable ID formulas are unchanged (the frozen ID test is green). Only constraints were added: `work` ID length is now enforced, `run` rejects |n| >= 2^53, and `Ids.edgeTag` is exposed.
- Pair canonicalization is unchanged: the smaller ID by `String.compareTo` first, equal IDs rejected.
- Config precedence is unchanged: `SCHOLARCHAIN__` override, then `${VAR}` placeholder, then file literal.
- Schema versions: 18 (the 17 plus `author-mention-v1`).
- Decode behaviour is now strict and total (see MAJOR-1 and T6/T7).

ASSUMPTIONS
- The null scan and the re-write comparison cost about three JSON passes per decoded line. I accepted that for strictness. Phase 4 may measure it.
- `provenance.snapshot` is `YYYY-MM-DD`, as the repair brief says. Datasets whose snapshot is a retrieval date use that format. A producer that writes a non-date snapshot is now rejected.
- Values at or above 2^53 in a run manifest must be strings.

RISKS
- Consumers affected: none exist yet. Phase 4 hadoop and the later phases must build AuthorMention with provenance and use `JsonLines.decode` or `decodeValid`.
- Signature change: `JsonLines.decode` and `decodeValid` now need `Reader` and `Writer` (any `ReadWriter`). Every record in core derives it.
- Not done on purpose: a graph edge ID is not recomputed in full, because its policy and evidence inputs are not in the record. A work ID shares its shape with some researcher IDs (for example `orcid:...` and `sc:r:v1:...` match the work pattern), so the shapes alone cannot tell the kinds apart. Where the kind is fixed (edge endpoints, decision endpoints) I checked by kind. A reserved work-namespace list would fix this; it is an open design point and I did not change it.
- MINOR-7 to MINOR-10 are dataset-engineer items and MINOR-11 is documentation-engineer's. I did not touch them.
- State left behind: `sbt fmt` was run and may have reformatted files in my territory. `.claude/.last-test-run` was touched by `sbt check`.
- Notes for documentation-engineer, `modules/core/MODULE.md`:
  1. Schema versions: 17 becomes 18; list `author-mention-v1`.
  2. AuthorMention: add the `provenance` field, and note it is a standalone Phase 4 output.
  3. `Validate`: add `mention`, so there is one validator per record except AnswerClaim and the nested parts. State that AnswerClaim's evidence IDs are its provenance.
  4. Validation rules to list: mention ID recomputed, distinct mention IDs per work, `provenance.snapshot` is a calendar date `YYYY-MM-DD`, non-negative and finite metrics, graph edge tag and endpoint types, decision ID recomputed with endpoint shapes by kind, work IDs checked in representativeWorkIds and maximumTeamWorkIds.
  5. `JsonLines`: strict and total (no exceptions, null is Left, unknown field is Left, lossy value is Left); the signature needs `ReadWriter`.
  6. Ids: the researcher ID scope (MINOR-5 decision above); `Ids.run` rejects |n| >= 2^53; `Ids.work` enforces 400 characters; new `Ids.edgeTag` and `Ids.MaxManifestNumber`.
  7. Config: the new ranges, `retrieval.maxPathDepth` bounded by `graph.pathMaxDepth`, model IDs required in aws mode with `llmEnabled`, strict numeric placeholders and overrides.
  8. Wire examples: 18 files, with `wire/author-mention.json` new and `wire/work.json` changed.
  9. Tests: 89 in core (4 Normalize, 12 Ids, 24 Validate, 29 Serialization, 20 Config).
  10. PROJECT_MEMORY "Researcher ID" row: say "stable within a run and policy (mention-set hash); an ORCID is the cross-run key".

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer re-review of the core diff, then documentation-engineer applies the MODULE.md and PROJECT_MEMORY.md notes above.
