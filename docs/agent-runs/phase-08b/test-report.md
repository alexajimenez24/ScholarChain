HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (default tier, no override)
(Saved by the orchestrator.)
STATUS: COMPLETE (gate item "full sbt check passes" NOT MET, see RISKS)
OWNER: test-engineer
WORK ORDER: Phase 08b step 4: add R4-1 to R4-3 tests (v2 figure tuples), run gate and benchmark suites, then one full sbt check, report every failure.
FILES CREATED: tests/adversarial/scholarchain/adversarial/rag/WorkflowRepairR4GateSuite.scala (8 tests, public API only, src/main not read)
FILES MODIFIED: none
COMMANDS RUN: `timeout 2400 sbt -mem 1536 -batch "adversarialTests/Test/scalafmtCheck" "adversarialTests/testOnly <7 rag suites>"` (format passed, tests exit 1); `timeout 2400 sbt -mem 1536 -batch check` (exit 1). Each once; the harness moved both to the background; nothing else started, no polling.
TEST RESULTS: see output-benchmark.md. Every one of the 16 failures is a test that scripts the replaced answer-synthesis-v1 reply, which v2 now refuses:
- R3Gate all 6 and D5Gate both: `assertEquals(res.synthesis.fallback, None, ...)` got `Some("model call failed: the script has no more replies")` (R3 line 122, D5 line 123).
- RepairGate M5 misattributed (206) and M5 subject (237): same fallback assertion.
- WorkflowGate: "fabricated, uncited and semantic-only claims" promptVersion expected answer-synthesis-v1 got deterministic-synthesis-v1 (429); "summary with a fabricated value is replaced" summaryReplaced not defined (476); "prompt injection in the question" (529) and "injected question reaches no claim" (585) removedClaims has no claim-1; "bounded retry" llm.prompts.size 3, expected 2 (632); "reasoning fields refused" promptVersion (666).
- No test failed for another reason, so no BLOCKED-ON.
VALIDATION RESULTS (R4 suite 8/8): non-vacuous control (correct tuples VERIFIED, shown text states the evidence value, number-free summary kept); a wrong tuple number never VERIFIED in 11 R4-1/R4-2 phrasings (surname, first name, lower case, NBSP, "the leader", "this researcher", "the former", possessive, "dir.", "is ranked with"), both directions, citing either record; true tuple VERIFIED in all 11 and never shows B's value; free-text literal numbers never VERIFIED, kept only labeled; wrong metric key or unknown researcher ID never VERIFIED; summaries stating B's number for A replaced; R4-3 on bq-v1-01 ranking: a figure VERIFIED only for the researcher at that position (value or rank).
ASSUMPTIONS: SynthesisPrompt.load loads answer-synthesis-v2 (R4 runs confirmed no fallback). Prose names are deliberately irrelevant under v2.
RISKS: "full sbt check passes" is not met until the human decides what happens to the 16 v1-scripted tests (unedited, red). Not tested: B's true tuple in prose about A (shown text names B); componentId and communityId per researcher; publication-year figures; path and edge figures. The R4 suite needs git-ignored run data and is skipped by `assume` if absent.
MANUAL ACTION REQUIRED: human decides whether the v1-scripting gate tests are retired or rewritten for v2.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer fairness verdict on the 16 failures and review 5 (step 5).
