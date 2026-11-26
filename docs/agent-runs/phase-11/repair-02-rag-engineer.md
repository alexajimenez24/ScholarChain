HARNESS: claude-code
MODEL: opus, effort high (high tier, .agents/models.json)
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 11 repair round 2, R-4 (rag part): tests pinning the C19 invalid-item note and the Downstream("evidence") and Downstream("answer") mapping.
FILES CREATED: /home/ajcreator/scholarchain/tests/module-unit/rag/InvariantErrorSuite.scala (5 tests)
FILES MODIFIED: none (no production change and no test seam needed)
COMMANDS RUN:
- timeout 30m sbt rag/Test/scalafmt (twice; "Formatting 1 Scala sources", the new file only)
- timeout 30m sbt rag/test, run 1: Total 163, Failed 1 (my fixture put a METRIC_RECORD in the semantic partition, so the builder refused it; fixed the test input)
- timeout 30m sbt rag/Test/scalafmt rag/test, run 2: Passed: Total 163, Failed 0, Errors 0; InvariantErrorSuite 5 of 5
TEST RESULTS: rag/test 163 of 163 passed (158 before plus 5 new).
VALIDATION RESULTS:
- (a) Evidence.scala:46-50. An item with a well-formed ID whose chunk ID is the secret "Bearer sc-fake-token-c19-9a7f3e" gives exactly one note, "dropped invalid evidence item <id>: it failed core validation". The test asserts the secret and "malformed" are absent. An item whose malformed ID carries the secret gives exactly "dropped invalid evidence item (malformed ID): it failed core validation", with the secret absent. A precondition test asserts that Validate.evidence(...).head contains the secret, so the old note (errs.head) would echo it and fail.
- (b1) Evidence.scala:82-86. EvidenceBuilderAgent.build("", ...) gives Downstream("evidence", "bundle invalid: ... bundle without bundle or query ID ..."). InvalidRequest falls to `case other => fail`.
- (b2) Workflow.scala:155-159. The public Synthesizer port returns a deterministic draft with abstain=true and abstentionReason=Some("   "). Stages 1 to 7 do not abstain (asserted). The run gives Downstream("answer", "answer invalid: ... abstains without a reason"), and the audit log stays empty.
- Revert sensitivity was checked by reading the code, not by mutation (the work order forbids mutation testing). Reverting either change fails at least one of the 5 tests.
- No prompt, schema, plan operation, template or verification rule changed.
ASSUMPTIONS: The secret goes in client-visible notes only. The Downstream message is internal by design (it carries validation text), so it is not asserted secret-free.
RISKS:
- Services, for service-engineer: Downstream("answer") can be triggered end to end. Build a real CollaborationWorkflow inside WorkflowEngine and inject a Synthesizer that wraps DeterministicSynthesizer and sets abstain=true with a blank abstentionReason (the same stub as this suite). Downstream("evidence") cannot be reached through the workflow: RuntimeContext requires valid run IDs and a valid snapshot, items are cleaned before the bundle is built, and the planner refuses an empty query ID (InvalidRequest) before stage 7. A services test must use a fake QueryEngine that returns Left(WorkflowError.Downstream("evidence", ...)).
- Not done: the 502-vs-500 mapping CARRY (service-engineer). The MODULE.md note belongs to documentation-engineer: test-only change, public API unchanged.
- State left: one new untracked test file, no commit.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: service-engineer mapping test (R-4 services part), then a fresh code-reviewer.
