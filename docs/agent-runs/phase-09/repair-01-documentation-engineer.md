HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, `.agents/models.json`), no override

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 09 repair round 1 documentation refresh. Bring docs/runtime-workflow.md, modules/rag/MODULE.md and STATUS.md in line with the repair reports. No sbt, no code, no docs/agent-runs edits, no commit.

FILES CREATED: none (scratch files only, under the session scratchpad)
FILES MODIFIED:
- STATUS.md
  - Phase 08 summary rewritten as committed (12829d7, a093a92). This fixes review MINOR m3.
  - Phase 09 summary now says review 1 was REQUEST_CHANGES, the repair is done, `sbt check` is NOT green because of D5, and the re-check and then the human gate are next.
  - Task list replaced: tasks 5 (review 1, done), 6a to 6d (repair, done), 7 (re-check, NEXT) and 8 (human gate).
  - State section rewritten with the counts below.
  - Carried-forward list rewritten: the heap item is removed, and D5, m1, m2, m3 (fixed), window attribution, swapped ranking values, the untested ranking-plus-name M5 variant and the unbumped version strings are added.
  - The fresh-session command now points at the re-check.
- docs/runtime-workflow.md
  - Planner changes, the pair-publication evidence rule, the communities wording, number attribution (M5) and the abstention and row rule are documented.
  - The loaded-windows behavior (M6) is documented.
  - Benchmark section now has before and after columns: D, A, pass and P 33/33; S 6/33 and 7/80 unchanged.
  - Test count table added, with the 771 s check and exit 1.
  - "Open defects" replaced by D5 only.
  - Failure modes updated: M7 heap, positional attribution, window misattribution, swapped ranking values, unbumped versions, m1, m2.
- modules/rag/MODULE.md
  - Done section: repair round 1 and its behavior changes. No public API signature changed.
  - Not done and Known issues: D5, m1, m2, the heap note replaced, and new test counts.
  - `WorkflowRepairGateSuite` added to the black-box test listing.

COMMANDS RUN (observed): `git status --short`, a `python3 -I` splice and replace script, and `awk` line-length and `grep` em-dash checks on the three files.
TEST RESULTS: none run; sbt was not run, as ordered. The counts come from the saved reports.
- `sbt rag/test` 131 of 131.
- BenchmarkQuestionSetSuite 40 of 40.
- BenchmarkWorkflowSuite 68 of 68.
- WorkflowGateSuite 29 of 29.
- WorkflowRepairGateSuite 5 of 6.
- Full `sbt check` at the default SBT_OPTS: exit 1 in 771 s, adversarialTests Failed 1 of 211.

VALIDATION RESULTS:
- Line lengths: no line over 90 characters in docs/runtime-workflow.md or modules/rag/MODULE.md, and none in my STATUS.md blocks. STATUS.md has five older over-90 lines outside my edits (lines 295, 437, 541, 1255 and 1766). I did not touch them.
- No em dash in the three files.
- Referenced report files exist in docs/agent-runs/phase-09/; no new links were added except existing relative ones.
- The documented sbt commands were not replayed, because the order forbade sbt.

ASSUMPTIONS:
- The "mapped to a loaded window with a note" wording for unloaded windows comes from rag-engineer's M6 test description.
- I kept the older "smallest configured window that contains it" sentence and added that only loaded windows count under LocalRuntime.

RISKS:
- Not done: the Phase 08 "Carried forward" section and the older STATUS sections were not re-checked.
- STATUS.md is long (about 1,770 lines), and I edited only the Phase 08 and Phase 09 parts.
- The query-plan-v1.json hash is not recorded in runtime-workflow.md. review-report.md gives it as c4d246ea...1cde0, but I left the line as it was.
- The test marker `.claude/.last-test-run` is not touched, and `sbt check` is red on D5.
- Nothing is committed.

MANUAL ACTION REQUIRED: none from me. The human still reviews the benchmark question set v1 and `semantic-relevance-rule-v1`, decides the Bedrock model and cost, and decides whether to bump the component version strings.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer re-check (`docs/agent-runs/phase-09/review-report-02.md`, fairness of D5), then the human gate.
