HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, effort high (the code-reviewer default in `.agents/models.json`)

# Phase 05 Review 2 (fresh re-check after repair round 1)

**Verdict: APPROVE WITH CARRIED-FORWARD ITEMS.** In role terms this is APPROVE.

- Every Review 1 finding meant for repair round 1 is fixed. MINOR-1, NIT-3 and NIT-4 are correctly carried forward.
- The repairs caused no regression. The er-v1 policy resource is unchanged, matching behaviour is unchanged, the new tests are fair, there is no leakage, and ownership is clean.
- `sbt check` exited 0, with entityResolution at 112 of 112 tests.
- New findings: 3 MINOR, all documentation or STATUS accuracy, and 3 NIT. None of them blocks the gate. Since there is no second repair round, all of them go to the human.

## 1. Status of each Review 1 finding

| Finding | Status | Evidence |
|---|---|---|
| MINOR-1: DBLP homonym false REJECT | CARRIED (future er-v2) | The code is unchanged: `Features.scala:192-201` still flags any `x != y` raw pair whose stripped, folded forms are equal. `Features.scala` and `MatchPolicy.scala` were last modified at 09:37 and 09:53, before the gold file (10:33:39). STATUS.md:684 carries it. See new finding MINOR-B: MODULE.md and the docs page do not mention it. |
| MINOR-2: cache hits not re-validated | FIXED | `Adjudication.scala:298-314` adds `recheck`. It checks the key, inputHash, candidateId and the prompt, model and policy versions, then runs `Adjudication.validate(write(hit.output), r)`. `Adjudication.scala:321-325` calls it on every hit; a failure gives `Skipped("LLM_INVALID_OUTPUT")` and makes no call. Tests: `AdjudicationSuite.scala:138-186` (8 forged lines, each with a correct key), `:188-249` (misfiled records), the ResolverSuite test at `:389-413`, and the end-to-end `MatchJobSuite.scala:189-217`. |
| MINOR-3: R-R2 misstated in the docs | FIXED | `docs/entity-resolution.md:178-181` matches `MatchPolicy.scala:191-230`. R-R2 is CONFLICT, or REJECTED when the score is at or below reject. R-R3 is CONFLICT only when an ID agrees. |
| MINOR-4: "committed before any label" | FIXED | `docs/entity-resolution.md:114-117`, STATUS.md:497-498 and STATUS.md:549-551, and `PolicyConfig.scala:70-74` (comment) now carry the true statement. `output-er-evaluation.md:27` keeps the old text by design; its section 12 (lines 268-280) corrects it. A grep finds no "committed ... before any label" wording left in the docs, MODULE.md, STATUS.md or the sources. |
| MINOR-5: "recall without caveat" | FIXED | The sentence is removed. `docs/entity-resolution.md:36` now reads "Decision 04 does not say which figures, if any, are free of caveat." That adds no claim. |
| MINOR-6: LocalMatchRunner described as test scope | FIXED | `MODULE.md:49-50` now says it is in `src/main`, ships in the job jar, and only its Hadoop runtime is Test-scoped. `LocalMatchRunner` is at `MatchJob.scala:818`. |
| NIT-1: no policy-version check in EvaluateMain | FIXED | `EvaluateMain.scala:86-96` adds `samePolicy`, called at `:105` before evaluation and before any write. Tested by `EvaluationSuite.scala:366-404`, including the check that nothing is written to `--out`. |
| NIT-2: `_local-tmp` inside the output root | FIXED for new runs | `MatchJob.scala:822-830` puts scratch in the sibling `<output>-tmp`. `:836-850` deletes it only when this run created it. Tested by `MatchJobSuite.scala:219-237`. After my `sbt check`, `target/` holds no `*-tmp` folder. The old `target/er-match-run/_local-tmp` (from the 10:01 run) is still there; removing it is a human decision. |
| NIT-3: LeakageSuite is a string tripwire only | CARRIED, no action | Unchanged, as agreed. |
| NIT-4: absent outcome folders in a future loader | CARRIED to Phase 07 | `MODULE.md:137-139`, `MODULE.md:144` (the DynamoDB cache must keep re-validation on every hit), STATUS.md:686-687 and STATUS.md:702. |

## 2. Regression checks

- **Policy hash.** I ran `sha256sum` on `er-policy-v1.json` before and after `sbt check`. Both gave 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff. The prompt hash is fbcf4b13…ca291ac5, the eval config 11031f93…, and the gold file 7fdd7923… All are unchanged.
- **No change to matching behaviour.**
  - Only 4 source files changed in R1-1, all at 11:22: `Adjudication.scala`, `PolicyConfig.scala`, `EvaluateMain.scala` and `MatchJob.scala`.
  - `Features.scala`, `MatchPolicy.scala`, `Resolver.scala`, `ErNormalize.scala`, `ExactIds.scala` and the policy JSON were all last modified before the gold file existed.
  - `recheck` adds no new outcome for a legitimate cache. A hit whose key matches `keyOf(r)` and whose key matches its own fields (`AdjudicationCache.parse`) already has equal inputHash and versions. `inputJson` contains candidateId. Outputs were validated before they were cached, and validation depends only on the output and the request. So only tampered or misfiled records behave differently.
  - The sample run used OFF mode, so its decisions cannot change.
- **New tests are FAIR.**
  - Each new test asserts what the spec and the Review 1 brief require: a refused cache hit, no call, UNRESOLVED/PROBABILISTIC/LLM_INVALID_OUTPUT, no identifier leaks into reason codes, a policy-version mismatch is refused with nothing written, and scratch stays outside the output root.
  - The valid-cache replay test (`MatchJobSuite.scala:171-187`) is unchanged and green.
  - No assertion was bent to fit the code.
- **No leakage.** Searching `src/main` for "gold" outside the `eval` package finds only refusal guards (`ReviewQueueMain.isGoldPath` and its callers) and the PolicyConfig comment. No matching file imports `scholarchain.er.eval`.
- **Ownership is clean.** `git status --porcelain --ignored` shows changes only in `build.sbt` (build-engineer, unchanged since Review 1), the entity-resolution module and its tests (entity-resolution-engineer), `docs/entity-resolution.md`, `MODULE.md` and `STATUS.md` (documentation-engineer), `docs/agent-runs/phase-05/` (orchestrator) and `datasets/gold/` (human). Nothing changed in the control plane, `project/**`, core or hadoop.
- **`datasets/gold/**` was not touched by any agent.** It holds one file, `er-gold-v1.csv`, with mtime 10:33:39 (unchanged) and the same sha256.

## 3. New findings, ranked

No CRITICAL or MAJOR findings.

**MINOR-A. STATUS.md: the old "Current state (phase 05)" snapshot is stale and contradicts the update block above it.** Owner: documentation-engineer.
- STATUS.md:710-711 still says "Task running: R1-1 … its fixes are not recorded as done here".
- STATUS.md:706-708 ("Tasks done") lacks R1-1 and R1-3.
- STATUS.md:726-728 (the Git line) lists the untracked tests as only `ReviewQueueSuite.scala`. It omits `datasets/gold/`, `docs/entity-resolution.md`, the modified `MODULE.md` and 9 other test files.
- The note at STATUS.md:704 ("where it says R1-1 is running, it is done") limits the damage. Still, a fresh session that reads the bullets first gets a wrong picture.
- Brief: replace the snapshot with a single current list. Tasks done: through Review 2. Running: none. Next: the human gate. Half done: nothing. Git: the current `git status`.

**MINOR-B. MINOR-1 is missing from the module's Known issues, and the docs describe R-R3 by its intent rather than its behaviour.** Owner: documentation-engineer.
- `docs/entity-resolution.md:141` ("different DBLP homonym suffixes") and `:180` state the intent. The code (`Features.scala:192-201`) also fires on case, accent or spacing variants, and when only one side has a suffix.
- Example: two DBLP mentions "José Silva" and "Jose Silva" with no ORCID give `dblp_homonym_distinct = 1`, so R-R3 decides REJECTED.
- `modules/entity-resolution/MODULE.md` Known issues (lines 119-158) does not list it. It appears only in STATUS.md:684.
- The module notes are the hand-off for a future er-v2 author, so the gap will outlive STATUS.md.
- Brief: add a Known issue to MODULE.md and a short caveat under the rules table, both pointing to Review 1 MINOR-1.

**MINOR-C. A Phase 01 item scheduled for Phase 05 is not tracked in the Phase 05 list.** Owner: documentation-engineer for STATUS.md; factory-engineer for the fix (Phase 11 guard work).
- STATUS.md:42 (under "Carried forward from Phase 01", already in HEAD) says: "Phase 05: gold-set bypasses (`rm -rf datasets` and similar) and MINOR-1 (gold-set interpreter writes)."
- Phase 05 made no control-plane change, so the item is still open.
- It is missing from both "Carried forward from Review 1" (STATUS.md:682-691) and "Current state (phase 05)".
- This phase is the first in which `datasets/gold/er-gold-v1.csv` exists and holds real labels, so the open bypass now protects real data.
- Brief: put it in the Phase 05 carried-forward list for the human, with a target phase.

**NIT-A. `MatchJob.LogicVersion` was not bumped, although its own comment says to bump it.** Owner: entity-resolution-engineer; the human decides.
- `MatchJob.scala:661` says "Bumped on any change to the job logic". `recheck` changes the outcome for a tampered or invalid cache, and `LogicVersion` stays `er-job-v1`.
- The same forged cache would give LLM_ADJUDICATED under the pre-repair code and UNRESOLVED now, under one run ID.
- No such run exists, and outputs for every legitimate cache are byte-identical (see the reasoning in section 2). That is why this is only a NIT. The human decision is already recorded at STATUS.md:699-700.

**NIT-B. Two refusal checks in a new test are not isolated.** Owner: entity-resolution-engineer.
- In `AdjudicationSuite.scala:193-212`, the misfiled records meant to test the inputHash check and the candidateId check also carry model version `"m"`, which differs from `port.modelVersion`.
- Each record is therefore refused by the model-version check whether or not the inputHash or candidateId check exists. The test is fair but weaker than its name.
- Brief: use `port.modelVersion` in those two records.

**NIT-C. The docs page states the scratch clean-up without its condition.**
- `docs/entity-resolution.md:274-275` says the runner "removes it after the run". The code removes the folder only when this run created it. `MODULE.md:52-53` states this correctly.
- Owner: documentation-engineer.

## 4. Is STATUS.md accurate enough for a fresh session to resume?

Mostly. The summary at the top (lines 24-32) and the task table (lines 488-493) are correct; the table still shows Review 2 as "pending", which is right until this report is saved. Notes R1-1, R1-2 and R1-3 match the repair reports, and the sbt figures match what I observed (112 tests, 14 project test runs). The gaps are MINOR-A (the stale snapshot and Git line) and MINOR-C (the untracked Phase 01 item).

## 5. Carried-forward list for the human gate

1. MINOR-1: DBLP homonym false REJECT. Needs `er-features-v2` and policy `er-v2`, decided without looking at the labels.
2. NIT-3: LeakageSuite is a string tripwire only. No action.
3. NIT-4: the Phase 07 loader (storage-engineer) must treat an absent outcome folder as empty only behind `_SUCCESS`, and the DynamoDB cache must keep re-validation on every hit.
4. Human decisions:
   - whether to bump `MatchJob.LogicVersion` (NIT-A);
   - whether to delete `target/er-match-run/_local-tmp`;
   - the `target/**` gap in the ownership matrix;
   - `chmod 644 datasets/gold/er-gold-v1.csv` before committing (it is still mode 755);
   - N4 (upstream link by run ID plus run manifest).
5. New in this review: MINOR-A (STATUS snapshot), MINOR-B (MINOR-1 missing from MODULE.md and the docs caveat), MINOR-C (the Phase 01 gold-set guard item for Phase 05 is untracked), NIT-B (test isolation), NIT-C (docs wording).
6. Process items:
   - The harness moved `sbt` to the background at 180 s again; this is the sixth time this phase. I started nothing else and read the output once, after the completion notice.
   - The guard-dangerous false positive on the test-marker task name belongs to factory-engineer in Phase 11.
7. Gold-label caveat (decision 04): the 13 NON_MATCH labels are known errors, researcher precision is understated, and the reject side is effectively unmeasured.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 05 Review 2 (fresh context): re-check of the whole uncommitted Phase 05 state after repair round 1 (R1-1 entity-resolution-engineer, R1-2 and R1-3 documentation-engineer). The orchestrator saves this as docs/agent-runs/phase-05/review-report-02.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status --porcelain [--ignored], git diff --stat, git show HEAD:STATUS.md | grep
- find ... -printf for the mtimes, sizes and modes of the module, tests, docs, gold and agent-runs files; ls of target/ and target/er-match-run
- sha256sum of the policy, prompt, eval config and gold file (before and after sbt)
- grep and sed reads of Adjudication.scala, Resolver.scala, PolicyConfig.scala, EvaluateMain.scala, MatchJob.scala, MatchPolicy.scala, Features.scala and the touched suites; grep for gold references and for eval imports in matching code
- read-only checks of ~/ref-phase-05 and ~/.claude/file-history for a pre-repair baseline (none usable)
- ps (no sbt or java running before the sbt run, none after)
- source toolchain/env.sh && timeout 590 sbt -batch check (exit 0; moved to the background at 180 s by the harness; output read once after the completion notice; log in /tmp/claude-1000/-home-ajcreator-scholarchain/347fb939-df75-4353-9d81-0bc5f75d7c8d/scratchpad/sbt-check.log)
TEST RESULTS: sbt check exit 0. Passed: Total 94 (core), 112 (entityResolution), 98 (hadoop), 68 (adversarial), Failed 0, Errors 0 in each; 14 project test runs passed; [success] Total time: 148 s, completed Nov 23, 2026, 11:36:23 AM.
VALIDATION RESULTS: APPROVE (APPROVE WITH CARRIED-FORWARD ITEMS). No CRITICAL or MAJOR.
- Review 1: MINOR-2, -3, -4, -5, -6 and NIT-1 FIXED; NIT-2 FIXED for new runs (old _local-tmp left for the human); MINOR-1, NIT-3 and NIT-4 CARRIED.
- MINOR-A STATUS.md:704-728 stale "Task running: R1-1", Tasks done and Git snapshot (documentation-engineer)
- MINOR-B MINOR-1 absent from MODULE.md Known issues; docs/entity-resolution.md:141,180 describe the R-R3 intent, not the behaviour (documentation-engineer)
- MINOR-C STATUS.md:42 Phase 01 gold-set guard item scheduled for Phase 05 is untracked in the Phase 05 carry-forward (documentation-engineer; fix by factory-engineer)
- NIT-A MatchJob.scala:661 LogicVersion not bumped despite its comment (entity-resolution-engineer; human decides)
- NIT-B AdjudicationSuite.scala:193-212 inputHash and candidateId refusals not isolated (model "m" also mismatches) (entity-resolution-engineer)
- NIT-C docs/entity-resolution.md:274-275 omits "only when this run created it" (documentation-engineer)
- Test fairness: the new R1-1 tests are FAIR; no bent assertions.
ASSUMPTIONS:
- File mtimes are taken as the authoring order. They agree with every report and with the orchestrator checks.
- No pre-repair copy of the 4 R1-1 files exists (all untracked; no file-history backup), so I could not diff "comment only" (PolicyConfig) and "LocalMatchRunner only" (MatchJob). I relied on unchanged mtimes of the matching files, the unchanged policy hash and the green hand-derived MatchPolicySuite and MatchJobSuite outcome tests.
- The verdict label follows the work order; in role terms it is APPROVE.
RISKS (not reviewed, or reviewed only in part):
- MatchJob.scala outside the reducer, cache, counters and LocalMatchRunner (lines 1-289 and 381-654) was not re-read; Review 1 covered it, and no baseline exists to confirm R1-1 left it alone.
- The decision run and evaluation were not regenerated. I did not recompute the evaluation numbers (Review 1 did). I did not compare with ~/ref-phase-05 for copied-unread code.
- ErNormalize, ExactIds, TextSimilarity and the review/ package were not re-read (unchanged since before the gold file).
- No mutation testing (forbidden); no other sbt run; no packageJobs or EMR run.
- Edge case not tested: LocalMatchRunner.scratchDir with no --output (defaults to ".", so the sibling would be outside the repo). The job refuses such arguments, so no folder is expected; this was not observed.
MANUAL ACTION REQUIRED:
- Human: the carried-forward list in section 5 (MINOR-1 / er-v2, LogicVersion bump, stale _local-tmp, the target/** matrix gap, chmod 644 on the gold file, N4, the Phase 01 gold-set guard item).
- Orchestrator: save this report as docs/agent-runs/phase-05/review-report-02.md, then have documentation-engineer record Review 2 in STATUS.md (and, if the human wants it, fix MINOR-A, MINOR-B, MINOR-C and NIT-C, which are docs-only).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: STATUS.md update by documentation-engineer recording Review 2, then the Phase 05 human gate (ratify, commit, decide the carried-forward items).
```
