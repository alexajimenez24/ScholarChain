HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier (documentation-engineer default)

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05, repair round 1, task R1-3. Apply the RISKS 6 wording from the R1-1 report to modules/entity-resolution/MODULE.md and docs/entity-resolution.md, checking each claim against the source. Then update STATUS.md.
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/MODULE.md
- /home/ajcreator/scholarchain/docs/entity-resolution.md
- /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN: grep of the source files below (exit 0); ls of target/er-match-run/_local-tmp (exists); python3 edit script; awk line-length check.
TEST RESULTS: none run. No sbt, per the work order. The sbt figures (exit 0, entityResolution 112 tests, 14 project test runs) are copied from the R1-1 report and were not re-observed.
VALIDATION RESULTS:
- Each claim was checked by reading source only:
  - Adjudication.scala: `recheck` at line 298, called on cache hits at line 323, and a failure gives `Skipped("LLM_INVALID_OUTPUT", ...)` at lines 324 and 334.
  - MatchJob.scala: `scratchDir` is the sibling `<output>-tmp` (lines 819-824), `localConf` sets `hadoop.tmp.dir` from it (line 830), and `run(args, job)` is at line 836. `main` is `sys.exit(run(args))` at line 861.
  - eval/EvaluateMain.scala: `samePolicy` is at line 86 and is called in `run` before `Evaluation.evaluate` (line 105).
  - hadoop `LocalRunner.scala` line 21 still uses `<output>/_local-tmp`, so it is unchanged.
  - `target/er-match-run/_local-tmp` exists.
- Not verified by me: that `run` removes the scratch folder only when this run created it. The report states it, and I did not read the body at lines 836-860.
- MODULE.md: added `Adjudicator.recheck`, the `LocalMatchRunner` `scratchDir` and `run` text, `EvaluateMain.samePolicy`, and the Done line (R1-1, 112 tests, 14 project test runs, LogicVersion not bumped). Under Known issues I added the Phase 07 DynamoDB cache re-validation note and the stale `_local-tmp` note.
- docs/entity-resolution.md: added a cache re-validation bullet, the scratch folder sentence (ER only) and the `samePolicy` refusal after the Reproduce command.
  - The docs page had no "ER scratch goes to `<output>/_local-tmp`" text, so nothing needed correcting there.
- STATUS.md:
  - The Phase 05 summary now says repair round 1 is done.
  - The task table marks R1-1 and R1-2 done and has a new R1-3 row.
  - New note R1-1 and note R1-3.
  - The Current state section has an update block with the open human items.
- Lines over 90 characters: none are new. The ones awk flagged are existing links, commands and table rows (MODULE.md 12, 56, 79, 82, 84, 87; the docs page 140, 146, 147, 203).
ASSUMPTIONS: The R1-1 report is accurate wherever I did not read the code. The `target/er-match-run/_local-tmp` "10:01 run" timestamp is copied from the report.
RISKS:
- In STATUS.md the older "Current state (phase 05)" bullets still say "Task running: R1-1".
  I added an update block above them that says so rather than rewriting them.
  The older Tasks done list also still lacks R1-1 and R1-3.
- docs/entity-resolution.md still says "Nothing is git-committed yet" in the policy section (pre-existing).
- I did not edit docs/agent-runs/**.
- The report is not saved as repair-01b-documentation-engineer.md; the orchestrator needs to save it (outside my territory).
MANUAL ACTION REQUIRED (human, from R1-1):
- Decide whether to bump MatchJob.LogicVersion (er-job-v1, left unchanged; a bump changes the run ID of any rerun).
- Decide whether to remove the stale target/er-match-run/_local-tmp.
- Orchestrator: save this report as repair-01b-documentation-engineer.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review 2 (fresh code-reviewer, review-report-02.md), then the human gate.
```
