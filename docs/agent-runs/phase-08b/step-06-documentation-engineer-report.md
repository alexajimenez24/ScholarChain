HARNESS: claude-code
MODEL: sonnet (Sonnet 5.5), standard tier, per work order (routine doc work)
(Saved by the orchestrator.)
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 08b step 6: update docs/runtime-workflow.md for the v2 verifier, prompt and schema; update STATUS.md once for the whole work order.
FILES CREATED: none
FILES MODIFIED: docs/runtime-workflow.md, STATUS.md
COMMANDS RUN: read of the 10 named reports, STATUS.md, runtime-workflow.md; sha256sum of the two v2 prompt files; grep of prompt placeholders; python edit scripts; line-length and em-dash checks.
TEST RESULTS: none run (docs only). Quoted test counts are copied from the agent reports.
VALIDATION RESULTS:
- 0 lines over 90 characters and 0 em dashes in runtime-workflow.md and the new STATUS.md sections; linked paths exist.
- runtime-workflow.md: "Verification rules" replaced with claim-verifier-v2 (figure tuples, {figure-N} placeholders, backing by position and holder, rendering from evidence, free-text numbers weakened and labeled, V5-1 fold and word rules with the `model` flag, deterministic path unchanged); stage table and version list show claim-verifier-v2 and synthesis-rules-v2; prompt section gives the v2 prompt and schema with sha256 computed on the working tree (v1 hashes kept as history); new section on repair round 4, the V5-1 repair and reviews 5 and 5b with residuals V5-3, ids presence-only, V5b-1, V5b-2, V5b-3; failure-modes entries updated.
- STATUS.md: Phase 08b bullet added, Phase 09 bullet rewritten, new section "Phase 08b and Phase 09 repair rounds 4 and 5" (Spark 100,000-work failure and EMR move, BFS coalesce revert, round 4 and V5-1 results, the 16 red tests with the NOT FAIR verdict and recommendation, human decisions, uncommitted state, table of where every report is); "Continue in a fresh session" names the human decisions first, then the cloud phases.
ASSUMPTIONS: the v2 prompt and schema hashes are the agent's own computation on the working tree. The Phase 09 task-list table was not edited (historical).
RISKS: modules/*/MODULE.md notes pending for Phase 17 (rag new public API ClaimFigure, DraftClaim.figures, draftText, ClaimFigures, ClaimVerifier.verify(c, bundle, labeled, model) and verifyText, Verdict.text, Verdict.weakened no argument, metricKey, rankingPositions, RemovedClaim.text; spark known issue: 100,000-work local run out of memory 2026-11-25, BFS coalesce reverted, deferred to EMR). Other numbers in runtime-workflow.md stale until Phase 17. The "Communities" bullet (otherCommunityCount weakening) unchanged and unverified under v2. Phase 09 "Carried forward" items 6 and 9 not rewritten. STATUS file-state list comes from the start-of-conversation git status. One STATUS edit was first blocked by a hook because the text named the test marker path; reworded and rerun, written once.
MANUAL ACTION REQUIRED: the human's decisions in STATUS.md: the 16 v1-scripted gate tests, the R4Gate "leads the ranking" pin, V5b-1 and V5b-2, the Phase 08 leftovers (2.1G).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human's decisions, then Phase 09 ratification and commit, then the cloud phases.
