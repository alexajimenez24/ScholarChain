# rag-engineer report (Phase 08b follow-up task 1, repair 06)
HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (high tier default, no override)
(Saved by the orchestrator: rag-engineer has no write territory under docs/agent-runs.)
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: replace free-text number detection with the construction rule, bump to claim-verifier-v3, no commit.
FILES CREATED: none
FILES MODIFIED: modules/rag/src/main/scala/scholarchain/rag/{Verification,Workflow,Synthesis}.scala (Synthesis: scaladoc only); prompts/runtime/answer-synthesis-v2.md; prompts/schemas/answer-synthesis-v2.json (description only); tests/module-unit/rag/{VerificationSuite,WorkflowSuite,BedrockLlmSuite}.scala
COMMANDS RUN: `timeout 1500 sbt -mem 1536 rag/scalafmt rag/Test/scalafmt rag/test` once, one JVM, exit 0; git status; grep.
TEST RESULTS: rag/test 138/138 on first attempt (VerificationSuite 14, WorkflowSuite 15, BedrockLlmSuite 4). Not run: full sbt check, adversarial and gate suites, benchmark.
VALIDATION RESULTS:
- Versions: claim-verifier-v3. answer-synthesis-v2 and synthesis-rules-v2 keep their names; schema fields unchanged. v2 prompt text changed (new sha256): rule 6 now says only figures are verified, free text is shown as UNVERIFIED interpretation, a ranking position is a figure (metric rank). Schema description updated. Plan operations, graph templates, abstention unchanged.
- Removed, no dead code: ModelDigits, Units, Tens, Scales, Ordinals, Multiples, NumberWord, Edge/EdgeEnd, ModelWords, ModelRank, fold() with NFKC, Normalizer import. Literal blanking plus ASCII NumberPattern now runs only when model = false.
- API changes:
  1. ClaimVerifier.verify(c, bundle, model = true): `labeled` parameter gone; with model (default) model text is never VERIFIED: at best PARTIALLY_SUPPORTED with reason starting "UNVERIFIED interpretation"; can still be CONFLICTING or UNSUPPORTED; unknown names, ids, titles still weakened.
  2. verifyText keeps its signature; model text is no longer searched for numbers, number words or rank phrases.
  3. New ClaimVerifier.structured(c, bundle): Vector[Verdict], one per figure; id ClaimVerifier.structuredId(id, i) = <claimId>-figure-N; text written from evidence only (e.g. "distinctCoauthorCount of Alice Arden (<id>) in window 2015-2026: 41."; rank: "rank of <name> (<id>) by <metricField> in window W: 1."). VERIFIED when tuple matches, CONFLICTING when a cited item holds another value, else UNSUPPORTED.
  4. New ClaimVerifier.Interpretation = "UNVERIFIED interpretation".
  5. A figure citing an ID not in the bundle is now UNSUPPORTED even if another cited item agrees.
- Workflow model path: each draft gives claim-N (PARTIALLY_SUPPORTED, text "UNVERIFIED interpretation: ...", label also in uncertainty), followed by claim-N-figure-K for each VERIFIED figure. Non-VERIFIED figure = separate RemovedClaim "figure K of claim-N: <metric> of <rid> = <value>". Summary becomes "UNVERIFIED interpretation: <summary>", replaced only when CONFLICTING or has unsupported spans. Note becomes "UNVERIFIED interpretation (uncertainty note): <note>" or removed on same conditions. audit.claimChecks includes structured checks. A model answer's overall verification is never VERIFIED.
- Deterministic path (model = false): unchanged, tests green.
- "leads the ranking" VERIFIED only as structured claim (researcherId, rank, position, ranking ID); as prose it is an interpretation.
- Tests changed (intent kept): VerificationSuite: model-text VERIFIED assertions now check `structured` plus interpretation status; V5-1 detection test replaced by one free-text test covering R4, V5-1, V5b-1/2/3 forms (never VERIFIED); deterministic ASCII rule still pinned via model = false; "partial" and "invented" moved to model = false; new tests: structured ranking claim, claim built from tuple only. WorkflowSuite: retry, injection, partial/note, M4, M5, R4-1/2 converted to claim-N + claim-N-figure-K shape; "R4 summary replaced" and "V5-1" replaced by "v3 summaries and notes are labeled whatever they contain" plus replacement cases; new ranking test. BedrockLlmSuite line 161 same way.
- Intent weakened on purpose by the rule: injection test's summary "Wei Wang has 999..." is now kept labeled, not replaced; its claim and figure are still removed and no VERIFIED claim or row shows 999.
ASSUMPTIONS: claim-N-figure-K ids pass core validation (no ID pattern enforced). PARTIALLY_SUPPORTED is right for a kept interpretation.
RISKS: false numbers in model prose/notes/summaries stay visible under the label only (the human's rule). Up to 40x16 structured claims per answer. Non-numeric facts (no path, no edge) cannot be structured. Gate suites (WorkflowGate, RepairGate M5, D5, R3, R4Gate) fail wherever they expect claim-N VERIFIED or a summary replaced. docs/runtime-workflow.md and modules/rag/MODULE.md not updated.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer converts gate suites (task 2), then fresh code-reviewer (review 6).
