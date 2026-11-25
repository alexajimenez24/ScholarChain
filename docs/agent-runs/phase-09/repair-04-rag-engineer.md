HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (default tier, no override)
(Saved by the orchestrator: rag-engineer has no territory under docs/agent-runs.)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 08b step 3, Phase 09 repair round 4 (R4-1 to R4-3), a disclosed human-authorized exception to the one-round rule. Replaced text-based number attribution with figure tuples (researcherId, metric, value, evidenceIds); removed the sentence-subject heuristics.
FILES CREATED: prompts/schemas/answer-synthesis-v2.json; prompts/runtime/answer-synthesis-v2.md (v1 files kept unchanged as history)
FILES MODIFIED: modules/rag/src/main/scala/scholarchain/rag/{Verification,Synthesis,Workflow}.scala; tests/module-unit/rag/{VerificationSuite,SynthesisSuite,WorkflowSuite,BedrockLlmSuite}.scala
COMMANDS RUN: `timeout 1500 sbt -mem 1536 rag/scalafmt rag/Test/scalafmt rag/test` once, foreground; JVM exited. scalafmt reformatted 1 of 3 main and 1 of 4 test sources, all owned.
TEST RESULTS: rag/test passed 134, failed 0, errors 0 (previous 135). Verification 11, Workflow 14, Synthesis 6, Bedrock 4. Full sbt check and gate suites not run (step 4).
VALIDATION RESULTS:
- New versions: prompt and schema `answer-synthesis-v2`, `synthesis-rules-v2`, `claim-verifier-v2`. `deterministic-synthesis-v1` and `llm-synthesis-v1` kept: rendered deterministic text unchanged (a test checks it).
- Plan operations and graph templates untouched.
- Rule: every number in a claim is a `ClaimFigure`; its placeholder `{figure-N}` (from 1) appears exactly once in the text. A figure may cite only evidence its claim cites. Bounds: at most 16 figures per claim, 1 to 4 evidence IDs per figure, value up to 64 chars, metric matches `^[A-Za-z][A-Za-z0-9_.]{0,63}$`.
- Which cited item backs a figure, by position only: a researcher's metrics row, community membership or identity record backs only that researcher; a ranking backs the researcher at that position for its metric or `rank` (closes R4-3); a found edge or cross-community link backs its two researchers; a path its two endpoints; a publication its work ID (year).
- Matching: metric keys ignore case, `_` and a `degree.`/`bridge.` prefix. A different held value is CONFLICTING; no holder is UNSUPPORTED, shown [unverified] with a label.
- Rendering: the number shown comes from the evidence. Model claims show `value (metric of Name, window W)`; deterministic claims show the bare value.
- Any other number in free text (claims, summary, uncertainty note) is unsupported, weakened and labeled. A model summary containing any number is replaced.
- Removed: Keyed table, sentence splitting, abbreviation list, pronoun and "own included" handling, query-lead subject logic, per-researcher subject inference. Name matching stays only for older checks (person in cited evidence, path hops, ranking order).
- Abstention unchanged.
- R4-1 to R4-3 phrasings (partial, lower-case, NBSP names, "the leader", "the former", "this researcher", possessive, "dir.", "is ranked with 30") now come out PARTIALLY_SUPPORTED or UNSUPPORTED, never VERIFIED or CONFLICTING. A deterministic answer is VERIFIED on 9 question types in both rendering modes.
ASSUMPTIONS: model path stays off by default. For publication years `researcherId` holds the work ID. Rendering mode is `labeled = s.byModel`; default labeled.
RISKS:
- Gate suites in tests/adversarial will fail (not run). Any test that scripts an `answer-synthesis-v1` reply is now refused and falls back to deterministic synthesis; tests asserting promptVersion v1, free-text numbers VERIFIED, summaries kept, or free-text CONFLICTING fail. Covers WorkflowGateSuite (model tests), WorkflowRepairGateSuite M5, WorkflowRepairD5GateSuite, WorkflowRepairR3GateSuite (including its control test). The human rule makes this unavoidable and it conflicts with "keep every gate suite unedited". Not touched. BenchmarkWorkflow and QuestionSet should stay green (deterministic text unchanged); not observed.
- Prose around a labeled figure is not checked against it ("Unlike Bob, Arden has 30 (distinctCoauthorCount of Bob Brandt, ...)" is VERIFIED but the number is shown as Bob's).
- Identifiers (componentId, communityId) are checked only for presence in any cited item, not per researcher.
- Deterministic packing measures the placeholder text (about 9 chars longer per number); on real data rankings and coauthor lists may split into more claims (40-claim cap still applies). A publication with no year renders `"T"` instead of `"T" ()`.
- For modules/rag/MODULE.md: new public API `ClaimFigure`, `DraftClaim.figures` (required) and `draftText`, `ClaimFigures`; `ClaimVerifier.verify(c, bundle, labeled = true)`; `Verdict.text`; `Verdict.weakened` takes no argument; `ClaimVerifier.metricKey`, `rankingPositions`; `RemovedClaim.text` is the drafted text with claimed values. docs/runtime-workflow.md needs the new rule (step 6).
MANUAL ACTION REQUIRED: the human decides how gate tests that encode the replaced v1 rule are handled (see RISKS).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 4, test-engineer. A model reply needs `"figures":[{"researcherId","metric","value","evidenceIds"}]` on every claim and `{figure-N}` in its text. A verified model claim text becomes "N (metric of Name, window W)". Free-text numbers come out [unverified] with a label containing "free text".
