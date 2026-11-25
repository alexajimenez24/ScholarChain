# documentation-engineer report (Phase 08b follow-up task 4)
HARNESS: claude-code
MODEL: sonnet (Claude Sonnet 5.5), standard tier default, no override
(Saved by the orchestrator: docs/agent-runs is outside documentation-engineer's territory.)
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: update runtime-workflow.md and rag MODULE.md for claim-verifier-v3 and the construction rule; update STATUS.md once; add the decision to PROJECT_MEMORY.md; use only the repair-06 and review-06 reports.
FILES CREATED: none
FILES MODIFIED: docs/runtime-workflow.md, modules/rag/MODULE.md, STATUS.md, PROJECT_MEMORY.md
COMMANDS RUN: Read and grep of the sources; Python in-place edits; awk line-length check; git status --short. All exit 0. No sbt run.
TEST RESULTS: none run. Counts come from the repair-06 reports: rag 138/138, full sbt check exit 0 in 953 s, adversarial 230/230. Review 6 did not re-run them.
VALIDATION RESULTS:
- docs/runtime-workflow.md: stage table, section heading and figures paragraph say claim-verifier-v3. The free-text number rule V5-1 and its trade-off paragraph are replaced by the construction rule: structured claims only for VERIFIED; claim-N and claim-N-figure-K shape; removed figures; "UNVERIFIED interpretation" labels on claims, summaries, notes; model answer never VERIFIED overall; deterministic path unchanged; rank claims VERIFIED only in structured form; number-word and digit detection removed. R6-1 stated plainly with the Carol/Alice example. Added section "Phase 08b follow-up, repair 06 and review 6"; updated header paragraph, known-failure-modes bullets and version-string bullet. One claim-verifier-v2 mention remains (historical round 4 bullet, now "v3 since repair 06").
- modules/rag/MODULE.md: new section "4b. Claim verifier v3" (verify signature without `labeled`, verifyText, structured, structuredId, Interpretation, missing-ID figure rule). Known issues list R6-1 (MAJOR) and R6-3 (MINOR); R4-1 to R4-3 and V5b marked superseded; "Not done" bullet on R4 updated.
- STATUS.md: new "Phase 08b follow-up" section with task table, R6-1 to R6-3 OPEN and carried to Phase 11, uncommitted file state, nothing half done, next step, harness. One line added to "Where things stand". Earlier decision items 1 to 3 marked settled.
- PROJECT_MEMORY.md: "Claim verification" decision row (construction rule, human decision 2026-11-25).
- Line lengths: no new line over 90 characters in edited prose. awk flagged STATUS.md lines 516, 658, 762, probably older (not verified as not mine). No em dash added; MODULE.md not re-scanned for pre-existing em dashes.
ASSUMPTIONS: prompt sha256 values in runtime-workflow.md are round 4 values and now stale (repair 06 changed the v2 prompt text); marked stale, not recomputed. STATUS.md uncommitted list is from session-start git status plus files changed.
RISKS: stale prompt hashes remain, flagged in text; recomputing with sha256sum is a Phase 17 task. Older sections quoting round 2 to 5 counts left as history. Nothing committed.
MANUAL ACTION REQUIRED: human reviews and commits; decides where R6-1 to R6-3 land in Phase 11.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review and commit of Phase 08b. Then R6-1 (rag-engineer), R6-2 (test-engineer), R6-3 in Phase 11. R6-1 must be closed before Bedrock or the LLM is enabled.
