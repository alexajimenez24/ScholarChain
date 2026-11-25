HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (default tier, no override)
(Saved by the orchestrator.)
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 08b step 5 repair round, V5-1 (one round): free-text numbers that NumberPattern misses are read as numbers in model text, weakened and labeled; claim text, summary and uncertainty note.
FILES CREATED: none
FILES MODIFIED: modules/rag/src/main/scala/scholarchain/rag/{Verification,Workflow}.scala; tests/module-unit/rag/{VerificationSuite,WorkflowSuite}.scala
COMMANDS RUN: `timeout 1500 sbt -mem 1536 rag/scalafmt rag/Test/scalafmt rag/test` twice, foreground, one JVM at a time. The second run broke the "once" rule: the first had 2 red tests (the deterministic text "on one paper" hit the new word rule; one new test text named Carol after Alice and became CONFLICTING). scalafmt touched only owned files.
TEST RESULTS: first run 135 passed, 2 failed; second run 137 of 137 (VerificationSuite 13, WorkflowSuite 15). Not run: full sbt check, R4Gate, BenchmarkWorkflow, QuestionSet.
VALIDATION RESULTS:
- Versions stay answer-synthesis-v2 and claim-verifier-v2 (v2 is uncommitted and in the same phase). Plan operations, graph templates, abstention unchanged.
- New rule applies only with `model = true` (default of `ClaimVerifier.verify(c, b, labeled = true, model = true)` and `verifyText(..., model = true)`); Workflow passes `model = s.byModel` for claims, notes and the summary. Rendering flag `labeled` is separate.
- Before matching, the masked text is folded per code point keeping offsets: format characters (zero-width) dropped; every \p{N} becomes `0`; the rest NFKC.
- Three patterns on the folded text: any token with a digit (glued to letters, marks or `_`, with decimal or thousands separators); number and ordinal words as one phrase (zero to nineteen, tens, hundred, thousand, million, billion, dozen, first to twentieth, once, twice, thrice, half, double, triple, quadruple, N-fold); rank words (top, bottom, last, penultimate, runner-up, "ranks/placed/comes the highest|lowest|best|worst", "highest/best/top-ranked").
- Each match is an unsupported Number fact labeled "... is in free text": claim weakened, summary with a match replaced, note with a match removed.
- Still kept: figure placeholders, windows such as 2015-2026 (also en dash), identifiers such as cm:..., known names, quoted titles, ranking-filter and identity-query literals. Deterministic path (`model = false`) keeps the exact v2 ASCII rule.
- New tests: VerificationSuite 26 V5-1 forms not VERIFIED, labeled, number gone, through verifyText and verify (every reviewer case: thirty, three dozen, 30x, _30_, fullwidth, Arabic-Indic, "ranks 3rd", "ranks first", obfuscated words); summaries with matches get spans; the trade-off is pinned; identifier, en-dash window and figure claim stay VERIFIED. WorkflowSuite: model summaries with "thirty ... ranking first", fullwidth and "3rd" replaced; a note with "three dozen" removed; the claim stays VERIFIED.
ASSUMPTIONS: gate suites expected green by reading (deterministic path unchanged; R4Gate model texts have no number or rank words; "leads the ranking", "the leader", "is ranked with" deliberately not flagged; R4 candidate names contain no number words). Not observed by running.
RISKS: model path only: a number word in an unknown proper name or common word is weakened ("one of", "first-order", "last year", "top venue", "half", "per second", a name like "Seven Bridges" not in the bundle). Not covered: Cyrillic and Greek homoglyphs, other languages, ASCII Roman numerals, vague quantifiers ("several", "a couple", "none"), "leads"/"leader" (pinned VERIFIED by R4Gate, V5-3/V5-4). Prompt v2 says only "digit"; a future v3 should forbid number and rank words. For modules/rag/MODULE.md: new public parameter `model: Boolean = true` on `ClaimVerifier.verify` and `verifyText`; docs/runtime-workflow.md needs the V5-1 rule.
MANUAL ACTION REQUIRED: none (the 16 v1-scripted gate tests remain the human's decision).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: gate suites and one full sbt check (test-engineer), then fresh code-reviewer on V5-1.
