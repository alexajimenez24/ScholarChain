# code-reviewer report (Phase 08b step 5 re-check, review 5b)
HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (high tier, no override)
(Saved by the orchestrator: code-reviewer creates no files.)
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: fresh re-check: V5-1 closed?, regressions from the V5-1 change, the BFS revert, the 16 red tests. Read-only.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git status, git diff HEAD (rag, spark, the four gate suites), ls/stat/find, grep. Read Verification.scala in full, the Workflow diff, the V5-1 tests (VerificationSuite 250-345), the R4Gate phrasings, ranking-filter code in Agents.scala and Evidence.scala. Probe: one Scala file compiled with the cached scala3-compiler 3.3.9 and run twice (one JVM at a time, nothing written to the repo, no sbt).
TEST RESULTS: no sbt run. Classes (12:06:15) are newer than Verification.scala and Workflow.scala (12:05:56), so the probe ran the V5-1 code. Adversarial XML (12:17-12:19): 227 current tests, 16 failures.
VALIDATION RESULTS: **REQUEST_CHANGES. The class "a false per-researcher number is VERIFIED" is still open. Per the work order this stops here and the human carries it forward.**
Closed: every form from review 5 now comes back PARTIALLY_SUPPORTED with a span (thirty, three dozen, 30x, _30_, fullwidth, Arabic-Indic, 3rd, ranks first), also twenty-one, a hundred, ThIrTy, zero-width inside digits or words, numbers in a quoted title, superscripts, the fraction, "number one", "No. 1", {figure-1 }, {figure-9}, {figure-1}0.
- MAJOR V5b-1 (rag-engineer), Verification.scala:137-138 with 605-609: names, windows and identifiers are blanked to spaces before the number check; ModelWords' `[\s\-]+(?:and[\s\-]+)?` then joins two number words across a blanked span; the joined match fails `free(s,e)` and is dropped whole, so neither word is flagged. "Alice Arden has thirty and Bob Brandt twenty distinct coauthors in window 2015-2026." is VERIFIED with 0 spans (true values 41 and 30), kept as a summary. "Alice Arden has thirty 2015-2026 one distinct coauthors." VERIFIED. "Alice Arden ranks first Carol Chen second by distinct coauthors." kept with 0 spans (Carol is first).
- MAJOR V5b-2 (rag-engineer), the word list is incomplete; each is VERIFIED with 0 spans citing Alice's row (41): combining mark "thírty" (fold never strips marks); soft hyphen "thirty-one" (drop glues to "thirtyone", EdgeEnd rejects; plain "thirtyone", "fortyone" pass too); Cyrillic look-alike "twо"; ASCII Roman numerals "XXX"; quantity words "a single", "no distinct coauthors", "a pair of", "a couple of", "a score of"; superlatives (false, Carol leads) "has the most", "has the highest ... count", "leads the ranking". "leads the ranking" is pinned VERIFIED by R4Gate:210 and :259, so fixing superlatives conflicts with a test-engineer test: human decision. MAJOR not CRITICAL: model path off by default.
- MINOR V5b-3 (rag-engineer): with only metric rows cited, "Bob Brandt has more distinct coauthors than Alice Arden" is VERIFIED (false), in the V5-3 class of unchecked prose; "Alice Arden has 2015-2026 distinct coauthors" is VERIFIED (a window used as a count).
- Repair brief (if the human continues): NFKD, drop \p{M}, map look-alike letters (Unicode TR39 skeleton) before matching; stop phrases joining across blanked spans (non-joining sentinel; if a phrase is not free, flag each word); match glued number words; add quantity words and ASCII Roman numerals. A word list stays incomplete: the structural alternative is to forbid quantity prose outside figures (human decision). Keep green: rag 137, R4Gate 8, BenchmarkWorkflow 68, QuestionSet 40.
- Regressions: none. The deterministic path is unchanged (model=false keeps NumberPattern; "thirty" still VERIFIED there). Workflow passes model = s.byModel for claim, note and summary; the fallback is deterministic.
- Territory: all diffs in rag-engineer, test-engineer or orchestrator territory; control plane untouched. No secrets (SECRET-MARKER-7731 is a test sentinel at SynthesisSuite:110).
- BFS revert: git diff HEAD and git status empty for modules/spark and tests/module-unit/spark. Confirmed.
- 16 red tests, still only those: R3 6, D5 2, RepairGate 2, WorkflowGate 6 (lines 429, 476, 529, 585, 632, 666); four suites unedited from HEAD. Fairness unchanged: NOT FAIR, all 16 (they script answer-synthesis-v1 replies); no repair brief.
- Gate: met: R4 8/8. Not met: full sbt check (the 16) and review APPROVE (V5b-1, V5b-2).
ASSUMPTIONS: RagWorld bundle is representative of Agents.scala evidence; probe classes match the 12:05:56 sources.
RISKS: not reviewed: SynthesisSuite and WorkflowSuite rewrites line by line; deterministic packing in Synthesis; Plan and Llm; the ranking-filter literal (built from the user's topic words, blanked anywhere without word boundaries; possible minor bypass, not probed). Not probed: other languages, end-to-end Workflow (verifyText only, same arguments).
MANUAL ACTION REQUIRED: human decides how to carry V5b-1 and V5b-2 forward (another repair round or a structural rule), whether to keep the R4Gate "leads the ranking" pin, and how to handle the 16 v1-scripted tests.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the work order stops (class still open after the one repair round); human decision.
