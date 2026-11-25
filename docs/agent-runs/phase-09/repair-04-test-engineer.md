HARNESS: claude-code / MODEL: opus, effort high. test-engineer, Phase 09 repair round 4 (Phase 08b step 4). Full report: docs/agent-runs/phase-08b/test-report.md
- Added WorkflowRepairR4GateSuite (8 tests): the R4-1 to R4-3 phrasings of review-report-04.md as v2 figure tuples, public API only.
- Result 8/8 pass. A false per-researcher number was never VERIFIED in any phrasing, either direction, any cited record, free text, a wrong metric, an unknown ID, or a ranking position. Controls are VERIFIED with the evidence value shown.
- Existing gate suites unedited: 16 tests fail only because they script answer-synthesis-v1, which claim-verifier-v2 refuses. No other defect.
- Kept green: BenchmarkWorkflow 68/68 (A 33/33), QuestionSet 40/40, RepairGate 4 of 6, WorkflowGate 23 of 29.
- Full check exit 1 (adversarialTests 227, 16 failed); every other project green, rag 134, spark 46.
- The class "false per-researcher number VERIFIED" is closed for the cases tested; residuals are in test-report.md RISKS.
