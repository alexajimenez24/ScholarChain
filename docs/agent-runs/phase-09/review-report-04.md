# code-reviewer report (Phase 09 review 4, R3-1 to R3-3 and regressions only)
HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (no override)
(Saved by the orchestrator: code-reviewer creates no files.)
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: read-only, no sbt. Check R3-1, R3-2, R3-3 and regressions only; R3-4 not raised. Hunt the class "a false per-researcher number is VERIFIED", judge test fairness, give verdict and gate status.
FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (observed):
- git status, git diff --stat (build.sbt diff is M7 only), find -newer review-report-03.md. Changed since review 3: Verification.scala, VerificationSuite, WorkflowSuite, WorkflowRepairR3GateSuite, phase-09 reports, test marker. Workflow.scala, prompts, build and control plane unchanged.
- Read Verification.scala whole, Workflow.scala:212-267, the R3 gate suite whole, the repair-3 edits in VerificationSuite (225-490) and WorkflowSuite (419-530).
- Test-report XML, 0 failures and 0 skipped: rag 135; R3Gate 6, D5Gate 2, RepairGate 6, WorkflowGate 29, BenchmarkWorkflow 68, QuestionSet 40.
- grep for secrets and reasoning: only the existing no-chain-of-thought test.
- jshell (JDK 17) on compiled classes (23:14, newer than the 23:13 source), RagWorld fixture (Alice 41, Bob 30, Carol 52). Writes nothing to the repo.

TEST RESULTS: no sbt run. repair-03-test-engineer.md reports R3Gate 6/6 and one full check exit 0 in 789 s; XML timestamps and counts agree.

VALIDATION RESULTS:
**Class STILL OPEN. Verdict: REQUEST_CHANGES.**
- R3-1, R3-2, R3-3 are fixed as written. All seven R3-1 inputs, R3-2 and R3-3 probed: PARTIALLY_SUPPORTED with an "ambiguous" label, never CONFLICTING. Summary inputs 1 and 4 replaced. Deterministic claims and summary all VERIFIED (ranking/bridge question, 23 claims).
- The rule counts only researchers recognised by exact display name, so a sentence naming a second researcher another way still "names one". With Bob's 30 stated for Alice, each case below came out VERIFIED and the summary was kept.

MAJOR R4-1 (rag-engineer): a partial or variant name is not a mention. Verification.scala:321-336 matches exact display names; :460-471 takes the one recognised name as subject. Observed VERIFIED, one sentence, no pronoun (not disclosed):
- "Unlike Bob Brandt, Arden has 30 distinct coauthors in window 2015-2026."
- "Unlike Bob Brandt, Alice has 30 ..."; "Arden, unlike Bob Brandt, has 30 ..."
- "... alice arden has 30" (lower case); "Alice Arden" with a non-breaking space.
Surname-only reference is ordinary LLM prose.

MAJOR R4-2 (rag-engineer): a noun phrase standing for a researcher is not treated like a pronoun. Observed VERIFIED:
- "Alice Arden leads the ranking. Unlike Bob Brandt, the leader has 30 ..."
- "... Unlike Bob Brandt, this researcher has 30 ..."
- "Alice Arden and Bob Brandt were compared. Unlike Bob Brandt, the former has 30 ..."
- Both disclosed residuals are reachable false VERIFIEDs: "Alice Arden's closest coauthor has 41 ..." and "Alice Arden, like the lab of dir. Bob Brandt, has 30 ..." (the "dir." period splits).

MAJOR R4-3 (rag-engineer; predates this round, not disclosed): a cited ranking item backs any researcher's number. Verification.scala:476 lets non-per-researcher items back every number; :509-514 collects every score in the ranking's `positions` value. "Alice Arden is ranked with 30." citing the ranking (Alice is 41) is VERIFIED and the summary is kept.

Why MAJOR, not CRITICAL (as in reviews 2 and 3): only the model path is affected and it is off by default (LLM_ENABLED:-false). No data loss, no security exposure, no fence change.

Regressions: none found.
- The five weakened assertions are each required by the human rule and none lets a false number through: "John Smith 41" becomes UNSUPPORTED; the two M5 two-name sentences; the D5 two-name list; WorkflowSuite M5 claim-2 and D5 claim-3. One-name wrong-value CONFLICTING checks (M5 claim-1, "listed second. Alice ... 30") are kept.
- WorkflowRepairR3GateSuite: FAIR against spec stage 9, the gate item and the human rule; public API only; non-vacuous control; 6 tests, 0 skipped.
- Territory OK; no secrets; no stored reasoning; no scope creep.

Repair brief (only if the human authorizes another round):
- Count as mentions, case- and whitespace-insensitively: surname tokens, first-name tokens, initial-plus-surname forms.
- Treat as pronouns: "the former/latter/leader/first/second", "this/that/the same researcher/author", "X's <relation>".
- Split sentences only after a known end word, not after any word missing from the abbreviation list.
- A ranking item supports a number only for the researcher at that position.
- Keep green: rag 135, R3Gate 6, D5 2, RepairGate 6, WorkflowGate 29, BenchmarkWorkflow 68 (A 33/33), QuestionSet 40; then one full check.

Gate: NOT met: "Unsupported claims removed, weakened or labeled" on the model path; "Code review returns APPROVE". Full check green per test-engineer. Everything else as in review 3.

ASSUMPTIONS: compiled classes are the code the reported check exercised; probes use RagWorld only.
RISKS: not reviewed: Plan, Evidence (beyond ranking values), Agents, Synthesis, Llm, TemplateGraph, bodies of WorkflowGateSuite and BenchmarkWorkflowSuite. Not probed: the 25k run, homonym bundles, windows, the true variant of the surname form (may be wrongly CONFLICTING). claim-verifier-v1 not bumped though behavior changed (human's call). jshell may write under ~/.java.
MANUAL ACTION REQUIRED: per run-log, stop. The human decides on R4-1 to R4-3: carry forward as disclosed residuals (model path off, close before Bedrock is enabled) or authorize another round. Phase 09 ratification and commit follow.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Phase 09 human gate. Files: modules/rag/src/main/scala/scholarchain/rag/Verification.scala (321-336, 451-477, 509-518).
