# Phase 09 run log

## Step 1, R1, rag-engineer (opus, high)
- Work order: stages 1 to 7, QueryPlan schema, operation allowlist. Report: step-01-rag-engineer-report.md.
- Result: STATUS BLOCKED-ON. `sbt rag/test` 96/96. Gap: community size is in no store the runtime reads (Spark exports communities only as Parquet; core ResearcherMetrics has no communitySize).
- Orchestrator decision: the fix would need a spark-engineer export and a storage-engineer loader, and would change the Phase 08 run (run-ccf619c509611f06) that this phase pins as input, which the addendum forbids editing. Routing it would also break the 2026-11-25 deadline scope. Recorded as carried forward to the human; R2 proceeds because stages 8 to 10 do not depend on community size. The human decides at the gate.

## Step 2, R2, rag-engineer (opus, high)
- Stages 8 to 10, synthesis prompt/schema v1, entry point CollaborationWorkflow. Report: agent-report.md. STATUS COMPLETE, sbt rag/test 123/123. Community size remains missing evidence (carried forward to the human).

## Step 3, test-engineer round 1 (opus, high)
- Report: test-report.md (round 1); results: output-benchmark.md. STATUS BLOCKED-ON: 33-question set v1 built, 40 tests pass, full sbt check exit 0 (14 runs). Workflow benchmark and gate tests not written: the field-level rag public API is not in MODULE.md and test-engineer may not read src/main.
- Routing (BLOCKED-ON rule): documentation-engineer writes the Public API section of modules/rag/MODULE.md from the rag code and R1/R2 reports (step 3a), then test-engineer reruns fresh (round 2, report test-report-02.md). The rest of step 4 (workflow section, STATUS.md) stays for step 4.

## Step 3, test-engineer round 2 (opus, high)
- Report: test-report-02.md; results appended to output-benchmark.md. 27/33 benchmark questions pass; 13 red tests = defects F1 (direct-collaboration/evidence questions planned as SEMANTIC_EXPLANATION), F2 (communitiesConnected worded as "other communities" but includes own), F3 (topic not extracted from text), F4 (abstained answer keeps researcher row with no claimIds). Full sbt check exit 1, only on those 13 tests, run at -Xmx2560m (default heap OOMs; build-engineer item carried forward). Step 3a report: step-03a-documentation-engineer-report.md.
- Next: step 4 documentation-engineer, then fresh code-reviewer, then one repair round.

## Repair round 1 (one round only)
- rag-engineer (repair-01-rag-engineer.md): M1 to M6 fixed, rag/test 131/131.
- build-engineer (repair-01-build-engineer.md): M7, adversarialTests forked at -Xmx2560m.
- test-engineer (repair-01-test-engineer.md): benchmark 33/33 D,A,P; F1 to F4 green; full sbt check at default SBT_OPTS exit 1 on ONE test: new D5 (M5 only partly fixed: false number VERIFIED when another cited researcher is named between subject and number). No second repair round per the prompt; goes to the review re-check and the human.
- Next: documentation-engineer refreshes docs for the repair (repair-01-documentation-engineer.md), then fresh re-check review-report-02.md (required: review 1 had MAJOR findings).
- documentation-engineer (repair-01-documentation-engineer.md): docs and STATUS.md refreshed; stale Phase 08 text fixed.

## Re-check review (review-report-02.md, opus high)
- Verdict REQUEST_CHANGES. M1 to M4, M6, M7 verified fixed; M5 partial. D5 (MAJOR, test judged FAIR): positional number attribution can VERIFY a false number on the model path and in the summary. New MINOR m4 (a topic phrase like "collaboration graphs" gives an empty filter and abstains), m5 (docs wording). No second repair round per the prompt: goes to the human (options a, b, c in the report).
- Phase 09 stopped here for the human gate. No agent committed or touched cloud resources, and nobody ran the human-only cleanup script.

## Human decision on D5 (disclosed exception to the one-round rule)
- Option (a): one extra repair round for D5 only. rag-engineer fixes D5 per the brief in review-report-02.md (ambiguous subject of a numerical claim: do not attribute the number, the claim is not VERIFIED), saved as repair-02-rag-engineer.md. Then test-engineer runs WorkflowRepairGateSuite and the full sbt check once (repair-02-test-engineer.md), then a fresh code-reviewer re-checks D5 and regressions only (review-report-03.md).
- Carried forward unchanged: m1, m2, m4, m5, community size, Bedrock model ID. No commits.

## Repair round 2 (D5 only, human-authorized exception)
- rag-engineer (repair-02-rag-engineer.md): clause-scoped subject attribution; rag/test 133/133.
- test-engineer (repair-02-test-engineer.md): WorkflowRepairGateSuite 6/6 green, new WorkflowRepairD5GateSuite 2/2, full sbt check exit 0 (734 s), test marker touched.
- Next: documentation refresh (repair-02-documentation-engineer.md), then fresh re-check review-report-03.md (D5 and regressions only).

## Re-check 3 (review-report-03.md, opus high; D5 and regressions only)
- Verdict REQUEST_CHANGES. D5 forms from review 2 fixed; sbt check green. But the defect class is still open: R3-1 (MAJOR) seven phrasings (abbreviation periods such as "vs." "Dr." "Prof.", sentence/semicolon/colon/while lead-ins, pronoun subjects) still verify a false per-researcher number on the model path and in the summary. R3-2 and R3-3 MINOR. R3-4 docs: the "pronoun subjects are weakened" statement is wrong when the clause names another researcher.
- No further repair round is authorized (the human exception covered D5 only). Orchestrator stops for the human: options (a) another round, (b) accept as disclosed residuals while the model path is off (LLM_ENABLED defaults false), close before Bedrock, (c) other. No commits.

## Human decision on R3-1 (second disclosed exception to the one-round rule)
- Authorized: one more round for R3-1, R3-2, R3-3 only. rag-engineer replaces the clause-and-position subject rule with a conservative one: a per-researcher numerical claim may be VERIFIED only when its sentence names exactly one researcher and has no pronoun subject; every other numerical claim about researchers is weakened and labeled, never CONFLICTING. Report: repair-03-rag-engineer.md.
- Then test-engineer adds the seven phrasings from review-report-03.md as tests, runs them and the full sbt check once (repair-03-test-engineer.md). Then a fresh code-reviewer checks R3-1 to R3-3 and regressions only (review-report-04.md). Then documentation-engineer fixes R3-4 and updates STATUS.md once at the end. Every report under 60 lines. No commits.
- If review 4 still finds the class open: stop; the human carries it forward.

## Repair round 3, rag-engineer
- repair-03-rag-engineer.md: sentence-level subject rule; rag/test 135/135; 145/145 on the five rag adversarial suites. Open residuals disclosed (unnamed-other subject, unlisted abbreviations). Next: test-engineer.

## Repair round 3, test-engineer
- repair-03-test-engineer.md: new WorkflowRepairR3GateSuite 6/6; full sbt check exit 0 (789 s), test marker touched; rag 135, adversarialTests 219. Next: fresh code-reviewer (review-report-04.md).

## Re-check 4 (review-report-04.md, opus high; R3-1 to R3-3 and regressions only)
- Verdict REQUEST_CHANGES. R3-1, R3-2, R3-3 fixed as written; no regressions; R3 gate suite FAIR. The defect class is STILL OPEN: R4-1 (variant or partial names, e.g. "Unlike Bob Brandt, Arden has 30", are not mentions), R4-2 (noun phrases such as "the leader", "the former", "Alice's closest coauthor", and the "dir." split), R4-3 (a cited ranking item backs any researcher's number; predates this round). All MAJOR, model path only (LLM_ENABLED defaults false).
- Per the human's order: no further repair round. The human carries R4-1 to R4-3 forward. Next: one documentation-engineer pass (R3-4 plus STATUS.md) to record this state; no commits.

## Final documentation pass, documentation-engineer
- R3-4 fixed (sentence rule replaces the clause rule in runtime-workflow.md, rag MODULE.md, STATUS.md). The class is recorded as STILL OPEN (R4-1 to R4-3). STATUS.md updated once; the next step is the human's decision and Phase 09 ratification. No commits. Phase 09 stopped here for the human.
