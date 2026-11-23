HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, effort high (entity-resolution-engineer default in `.agents/models.json`)

Summary: All four items are fixed and tested. `sbt fmt check` ran once and exited 0. entityResolution now has 112 tests, up from 106, and all pass. The er-v1 policy resource is unchanged; its sha256 is still 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff. I did not save this report as `repair-01-entity-resolution-engineer.md`: `docs/agent-runs/**` is outside my territory, so the orchestrator needs to save it.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05, repair round 1, task R1-1. Fixes MINOR-2 (re-validate adjudication cache hits), NIT-1 (EvaluateMain refuses a run made under another policy version), NIT-2 (LocalMatchRunner puts Hadoop scratch outside the output root) and the PolicyConfig.scala:71-72 comment (MINOR-4). MINOR-1 is not fixed (carried to er-v2). er-v1's behaviour, policy resource, features, thresholds and prompt are unchanged.
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/Adjudication.scala (new `Adjudicator.recheck`, used on every cache hit; updated AdjudicationCache doc comment)
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/PolicyConfig.scala (doc comment only)
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/eval/EvaluateMain.scala (new `samePolicy`, called in `run` before evaluation)
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/MatchJob.scala (LocalMatchRunner only: new `scratchDir`, `localConf` uses it, new `run(args, job)` that removes the scratch folder, `main = sys.exit(run(args))`)
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/AdjudicationSuite.scala (+2 AdjudicationSuite tests, +1 ResolverSuite test)
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/MatchJobSuite.scala (+2 tests; the `fake` run became a shared lazy val, and the existing replay test is otherwise unchanged)
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/EvaluationSuite.scala (+1 test)
COMMANDS RUN:
- ps aux | grep sbt/java: no sbt or java was running before the sbt run.
- sha256sum of the policy JSON and the prompt: 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff and fbcf4b136e72b9d1edede0060be0d4543f2459a83c0e119252284cd2ca291ac5, the same before and after.
- source toolchain/env.sh && timeout 590 sbt -batch fmt check: the one sbt invocation, exit 0. The harness moved it to the background at 180 s. I started nothing else and read the log once, after the completion notice.
- git status --short, plus a find of files newer than the task start. My 7 files changed; see RISKS 5 for the others.
TEST RESULTS (sbt fmt check, exit 0, [success] Total time: 165 s, completed Nov 23, 2026, 11:28:02 AM):
- Passed: Total 94 (core), 112 (entityResolution, was 106), 98 (hadoop), 68 (adversarialTests). Failed 0, Errors 0 in each.
- The test-marker task: 14 project test runs passed.
- Per suite:

| Suite | Tests now | Before | Added |
|---|---|---|---|
| AdjudicationSuite | 12 | 10 | 2 |
| ResolverSuite | 8 | 7 | 1 |
| MatchJobSuite | 9 | 7 | 2 |
| EvaluationSuite | 14 | 13 | 1 |

- The MatchJobSuite cache replay test ("fake mode with a budget ... the cache replays it without a call") is still green: 0 LLM_CALLS, 1 LLM_CACHE_HIT, identical decision.
VALIDATION RESULTS:
- Policy er-v1 is unchanged. Policy resource sha256 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff (checked). Thresholds accept 0.9, reject 0.35, LLM band [0.7, 0.9) (pinned, unchanged). Features er-features-v1. Prompt er-adjudication-v1, sha256 fbcf4b13...ca291ac5, unchanged.
- Model: adjudication is off in the sample run, so no model was used. Tests use the fake (fake-deterministic-v1) and scripted ports.
- No gold-set evaluation was rerun, as instructed: target/er-match-run and target/er-eval were not regenerated. The confusion matrix, per-path precision, recall and F1, and the unresolved rate (0.0144) are the T3 numbers in docs/agent-runs/phase-05/output-er-evaluation.md. None of these fixes can change them: the sample run is OFF mode with no cache, and the decisions are policy er-v1, which EvaluateMain loads.
- MINOR-2: on a cache hit, `Adjudicator.recheck` first checks that the record's cacheKey, inputHash, candidateId, promptVersion, modelVersion and policyVersion all equal the current request and port. It then runs `Adjudication.validate(write(hit.output), request)`, the same strict check as a fresh reply. If either step fails, the result is `Skipped("LLM_INVALID_OUTPUT", "cached reply refused: ..." or "cached record does not match the request: ...")`. The cached reply is not used, no call is made, and the case stays UNRESOLVED on the PROBABILISTIC path with reason LLM_INVALID_OUTPUT.
- MINOR-2 tests:
  - AdjudicationSuite: 8 forged, correctly keyed lines, each accepted by `AdjudicationCache.parse` and each refused: reasonCodes ["see 10.1000/x"], a source ID that is not the candidate's, a target ID that is not the candidate's, an uncited field, an ACCEPT that cites nothing, confidence 1.5, decision MERGE, and empty reason codes. All make 0 calls. The same line with a valid output still replays as a cache hit.
  - AdjudicationSuite: 5 in-memory records filed under the request's key that do not match its fields, all refused.
  - ResolverSuite: a forged line gives UNRESOLVED / PROBABILISTIC / LLM_INVALID_OUTPUT. No "10.1000" reason code, no R-LLM rule, no adjudication record, 0 calls, and a valid core decision.
  - MatchJobSuite end to end: a forged `adjudications/part-r-00000` passed through `--adjudication-cache` gives the band case UNRESOLVED with LLM_INVALID_OUTPUT. No LLM_ADJUDICATED decision in the run; counter LLM_INVALID_OUTPUT=1, LLM_CALLS=0, LLM_CACHE_HITS=0; no adjudications output.
- NIT-1: `EvaluateMain.samePolicy` refuses a run when any decision's `policyVersion` or `provenance.policyVersion` differs from the loaded policy. The error is "decision run is policy er-v2, but this evaluation loads policy er-v1", and nothing is written to `--out`. Tested on a full er-v2 run folder (decision IDs recomputed so the run passes core validation) and on a mismatch carried only in provenance.
- NIT-2: `hadoop.tmp.dir` is now the sibling `<output>-tmp` (absolute, normalized; a trailing slash is ignored). `LocalMatchRunner.run` deletes the scratch folder afterwards only if this run created it; a folder that already existed is left alone.
- NIT-2 test (MatchJobSuite): the output root of a real run holds exactly `_er-profiles`, `match-decisions` and `run-manifests` (no `_local-tmp`). The scratch folder is gone after the run, and a scratch folder that existed before the run keeps its contents.
- MINOR-4 comment now reads: "Its weights and rule parameters were set by hand; er-v1 was fixed before the gold file existed and was not changed afterwards." The word "committed" is not used.
- Representative errors: unchanged from T3. No new evaluation was run.
ASSUMPTIONS:
- A refused cache hit is not followed by a fresh call (the work order says do not use it). The case stays UNRESOLVED until the cache is fixed or a run is made without it.
- For NIT-2 the sibling folder `<output>-tmp` was chosen over a JVM temp folder: it is deterministic, testable through `localConf`, and is deleted only when this run created it.
- I did not bump `MatchJob.LogicVersion` (er-job-v1). For every cache line the old code accepted as valid, outputs are byte-identical. Only tampered or invalid cache lines now produce a different outcome, and no run with such a cache exists. Bumping it would change the documented run ID run-02cecabcf3e68312 on any rerun.
RISKS:
1. Not done, by instruction: MINOR-1 (DBLP homonym false REJECT; needs er-features-v2 and er-v2); no regeneration of target/er-match-run or target/er-eval; no change to datasets/gold/** or the review CSVs.
2. State left behind: target/er-match-run/_local-tmp still exists from the 10:01 run made before this fix. I did not delete it, because that output must not be regenerated or touched. Any new local run puts scratch in `<output>-tmp` and removes it afterwards.
3. LogicVersion was not bumped (see ASSUMPTIONS). A reviewer may prefer er-job-v2; that would change future run IDs, so it is a decision for the orchestrator or human.
4. The Skipped path records no cache key in the audit for a refused hit. The decision carries only the reason code LLM_INVALID_OUTPUT, as for a refused fresh reply, and the cache file's sha is in the run manifest. The ResolverSuite test asserts this behaviour, so changing it means updating that test.
5. During this task, files outside my territory also changed: STATUS.md, docs/entity-resolution.md, MODULE.md and docs/agent-runs/phase-05/{output-er-evaluation.md, repair-01-documentation-engineer.md, review-report.md}. This was the parallel documentation repair, not me. `sbt fmt` reformatted only my files: only my 7 files changed in source and tests.
6. Notes for modules/entity-resolution/MODULE.md (documentation-engineer to apply):
   - Public API, `Adjudication` bullet: add `Adjudicator.recheck`. Every cache hit (preloaded or in-run) is re-validated against the current request with the same strict validator as a fresh reply. A hit that fails is not used and yields LLM_INVALID_OUTPUT with no call.
   - Public API, `MatchJob` bullet: `LocalMatchRunner` now also exposes `scratchDir(output)` (the sibling `<output>-tmp`) and `run(args, job)` (exit code; removes the scratch folder when the run created it). `main` calls `run`.
   - Public API, eval bullet: add `EvaluateMain.samePolicy`. EvaluateMain refuses a decision run whose decisions name a policy version other than the loaded policy (er-v1).
   - Done: last `sbt check` (R1-1) exit 0, entityResolution 112 tests, 14 project test runs.
   - Known issues: add that target/er-match-run/_local-tmp predates the NIT-2 fix. Keep the "cache read whole into memory" item, and note that the Phase 07 DynamoDB cache must keep the re-validation on hit.
   - docs/entity-resolution.md lines 219-224 (cache paragraph): add that every cache hit is re-validated with the reply validator and the request's IDs and versions, and that a refused hit gives LLM_INVALID_OUTPUT with no call; MatchJobSuite also checks a forged, correctly keyed line.
   - docs/entity-resolution.md near line 372 (EvaluateMain): add the policy-version refusal.
   - If the docs or hadoop notes say ER scratch goes to `<output>/_local-tmp`, change that to `<output>-tmp` for ER only. The hadoop module's LocalRunner still uses `<output>/_local-tmp`; that is hadoop-engineer's and was not changed.
MANUAL ACTION REQUIRED:
- Orchestrator: save this report as docs/agent-runs/phase-05/repair-01-entity-resolution-engineer.md (outside my territory).
- Human or orchestrator: decide whether to bump MatchJob.LogicVersion (RISKS 3), and whether to remove the stale target/er-match-run/_local-tmp (RISKS 2).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer applies the MODULE.md and docs wording in RISKS 6, then a fresh code-reviewer re-checks (review-report-02.md), then the human gate.
```

---
Orchestrator check (observed after the report): policy resource sha256 starts
82ce9ae5c81d998b (unchanged); no sbt or java process running; datasets/gold holds only
er-gold-v1.csv; tracked changes are STATUS.md, build.sbt and modules/entity-resolution/MODULE.md
(the rest of the Phase 05 work is untracked).
