HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, effort high (code-reviewer default in `.agents/models.json`)

# Phase 05 Review 1

**Verdict: APPROVE WITH CARRIED-FORWARD ITEMS.** In role terms that is APPROVE. There are no CRITICAL or MAJOR findings and `sbt check` is green. I found 6 MINOR and 4 NIT issues. I recommend fixing the documentation items (MINOR-3 to MINOR-6) in repair round 1. I recommend fixing MINOR-2 in repair round 1 or carrying it forward. MINOR-1 has to wait for a future policy version: fixing it changes er-v1's behaviour.

## What I verified

- **`sbt check` passed.** It exited 0 when I ran it once at 11:14 to 11:16.
  - Lines from the log: `Passed: Total 94`, `Passed: Total 98`, `Passed: Total 106` (entityResolution), `Passed: Total 68`.
  - `markTestRun: 14 project test runs passed`, `[success] Total time: 159 s`.
  - No sbt or java was running before I started (checked with `ps`). The harness moved the run to the background at 180 s. I started nothing else and read the output once, after the completion notice.
- **Ownership is clean.**
  - `git status` shows changes only in: `build.sbt` (build-engineer), `modules/entity-resolution/**` and `tests/module-unit/entity-resolution/**` (entity-resolution-engineer), `docs/entity-resolution.md`, `modules/entity-resolution/MODULE.md` and `STATUS.md` (documentation-engineer), `docs/agent-runs/phase-05/**` (orchestrator) and `datasets/gold/` (human).
  - Nothing changed in `modules/core`, `modules/hadoop`, `project/**` or the control plane.
  - The `build.sbt` diff is the entityResolution stanza only (12 lines). Versions come from `V.hadoop` and `V.slf4j`; `project/Versions.scala` is unchanged.
- **No leakage.**
  - The gold file `datasets/gold/er-gold-v1.csv` was last modified at 10:33:39, and the `datasets/` folder at 10:28.
  - Every matching source and resource is older than the gold file: policy JSON 09:39, `MatchPolicy.scala` 09:53, `er-eval-v1.json` (the sensitivity grids) 09:45.
  - The decision run `run-02cecabcf3e68312` started at 16:01:05Z (10:01 local, before the gold file existed).
  - Only `Evaluation.scala` (10:41) and `EvaluationSuite.scala` (10:40) changed after the gold file. That matches the T3 report's claim.
  - Hashes match the reports: policy `82ce9ae5…`, prompt `fbcf4b13…`, eval config `11031f93…`, review queue `f7520475…`, gold file `7fdd7923…`.
  - No matching code refers to gold paths. `LeakageSuite` scans the `scholarchain.er` package and the resources. The three entry points refuse any `datasets/gold` path.
  - v1b selection (`ReviewSupplement`) reads source records, J04 block keys and the upstream `same_source_cluster` feature. It reads no er-v1 score, outcome or decision.
  - Sensitivity is reported only, and no er-v1 file changed after the labels existed.
- **Labels.** I read the gold file (read-only):
  - The header and all 280 pair_ids, in order, equal the review queue.
  - 0 cells differ outside `label` and `note`; 0 notes.
  - Counts: researcher 153 MATCH, 13 NON_MATCH, 39 UNSURE; work 75 MATCH.
  - The 13 NON_MATCH rows cover 4 names (Dickinson 6, Sapiro 3, Shokoufandeh 3, Favaro 1).
  - Nothing shows an agent writing to it. EvaluateMain only reads the gold file and refuses an output path under `datasets/gold`.
- **Provenance and run outputs.** I read all 347 decisions of the run:
  - Every decision has `provenance.runId = run-02cecabcf3e68312`, `policyVersion = er-v1`, and `sourceRecordId` set to the candidate ID.
  - There are 347 audit lines, all with one `decidedAt`, `upstreamRunId run-6d1175dbfdb874a9` and adjudicator mode OFF.
  - The run manifest holds the policy sha, thresholds, feature, normalization, prompt and model versions, the budget and the cache sha, and no timestamp.
  - Reason counts match the docs exactly: 192/44/20/11/5 for researchers, 71/4 for works.
  - The summary gives 347 decisions, by path 130/20/197/0, and unresolved rate 0.01440922.
- **The 4 work CONFLICTs are genuine.** In each pair, one side carries a Springer DOI under `978-0-387-31439-6_*` and the other under `978-3-030-63416-2_*` (two editions). The conflict rule works as designed.
- **Absent empty outcome folders** (`rejected/`, `adjudications/`, `rejects-v1/`) are handled:
  - `EvaluateMain.readDecisions` (EvaluateMain.scala:55-58, 74) treats a missing outcome folder as empty, and only if `_SUCCESS` exists.
  - `MatchJob.lines` and `readCache` treat a glob with no match as empty.
  - There is no other reader yet; see NIT-4.
- **Evaluation numbers.** I recomputed them from `target/er-eval/er-evaluation.json` with a Wilson implementation of my own. All match `output-er-evaluation.md`, `docs/entity-resolution.md` and MODULE.md:
  - researcher P 0.938 [0.889, 0.966], R 0.987, F1 0.962
  - work P 1.000 [0.949, 1.000], R 0.947
  - combined [0.922, 0.976] and [0.944, 0.988]; 0/13 gives [0, 0.228]; 10/13 gives [0.497, 0.918]; R-R5 [0.354, 0.848]
  - the sweep has 42 points, with the er-v1 row at TP 151, FP 10, unresolved 5.
- **Correctness against the work order.** These are implemented as specified:
  - exact-ID rules for DOI (prefixes, percent-encoding, preprint and arXiv DOIs), arXiv, ORCID (check digit), OpenAlex, S2 sha and corpus forms, and repo URLs;
  - the normalization list and every listed feature (citation features are always 0, a known contract gap);
  - the versioned, pinned policy with separate thresholds that refuses retuning;
  - conflict rules R-R0/R-R2/R-W0/R-W1;
  - an LLM band limited to R-R9/R-W9 scores in [0.7, 0.9);
  - a strict reply schema: exact keys, the three decisions, IDs equal to the candidate's, reason codes without digits, cited fields only from the request;
  - the four-part cache key, the four outcome outputs, the review queue, and the metrics per kind and per path with error analysis and sensitivity.
- **Tests.** I read MatchPolicySuite, AdjudicationSuite with ResolverSuite, MatchJobSuite and LeakageSuite whole. They are fair and meaningful:
  - expected outcomes are derived by hand from the weights;
  - refusal paths, idempotence, run-ID reproducibility and cache replay (0 calls, 1 hit) are covered;
  - I saw no bent assertion.

## Findings, ranked

**MINOR-1. DBLP homonym detection rejects pairs that differ only in case, accents or spacing.**
- Where: `modules/entity-resolution/src/main/scala/scholarchain/er/Features.scala:192-201`. `ReviewSupplement.scala:217-220` has the same logic, but v1b is unused.
- The rule flags a pair whenever the two trimmed raw DBLP strings differ (`x != y`) but are equal after removing the homonym number and folding. It never checks that a homonym number was actually there.
- Failure input: two DBLP mentions with no ORCID, "José Silva" and "Jose Silva". The same happens for "Wei Wang" against "Wei Wang 0001", or a double space inside a name.
  - Effect: `dblp_homonym_distinct = 1`, so R-R3 decides REJECTED on the DETERMINISTIC_BIBLIOGRAPHIC path with confidence 0.02 and reason `DBLP_HOMONYM_DISTINCT`.
  - DBLP XML prints names as they appear in the paper, including alias spellings, so these pairs can be the same person.
- Impact: the pair is wrongly split and given a definite negative. It does not over-merge. It did not occur on this sample (0 REJECTED).
- Owner: entity-resolution-engineer.
- Repair brief:
  - Require both sides to carry a 4-digit suffix and the suffixes to differ. Compare the unsuffixed strings with `fold`.
  - This changes er-v1 behaviour, so it needs `er-features-v2` and policy `er-v2`, decided without looking at the labels. Do not edit er-v1 in place: carry this forward.
  - Tests that must stay green: MatchPolicySuite's homonym test and MatchJobSuite.

**MINOR-2. Answers replayed from the adjudication cache are never re-validated.**
- Where: `Adjudication.scala:263-272` and `297-298`, used by `Resolver.scala:154-176`.
- `AdjudicationCache.parse` only checks that a line's key matches its own fields. On a cache hit, `rec.output` is used as is.
- Failure input: a line in an `--adjudication-cache` file with a correct key, output `{"decision":"ACCEPT","confidence":0.99,"reasonCodes":["see 10.1000/x"],"citedFields":[]}`, and sourceId/targetId of any value.
  - Effect: an LLM_ADJUDICATED ACCEPTED decision is written with the reason code `LLM_see 10.1000/x`.
  - That bypasses the strict-schema and no-new-identifier guarantees that `validate` enforces on fresh replies.
- Impact: a tampered or legacy cache, and later the Phase 07 DynamoDB cache, can inject unvalidated decisions.
- Owner: entity-resolution-engineer.
- Repair brief:
  - On a hit, re-run `Adjudication.validate(write(rec.output), request)`. Treat a failure as `LLM_INVALID_OUTPUT` and do not use it.
  - Add a test with a forged-but-keyed cache line.
  - er-v1 itself does not change. Tests that must stay green: AdjudicationSuite and the MatchJobSuite cache replay.

**MINOR-3. The docs misstate when R-R2 gives CONFLICT and when it gives REJECTED.**
- Where: `docs/entity-resolution.md:176-177`.
- The text says an ORCID disagreement (R-R2) "is a CONFLICT when an identifier on the same pair agrees, otherwise a REJECTED".
- The code (`MatchPolicy.scala:191-210`) and the page's own rules table (line 138) say R-R2 is CONFLICT unless the score is at or below reject.
- Example: ORCID disagrees, no other ID agrees, score 0.9. The doc predicts REJECTED; the code gives CONFLICT, which MatchPolicySuite lines 94-97 assert.
- Owner: documentation-engineer.
- Repair brief: say R-R2 is CONFLICT, or REJECTED when the score is also at or below 0.35. Keep the "ID agrees" condition for R-R3 only.

**MINOR-4. "Fixed and committed before any gold label existed" is not supported.**
- Where:
  - `docs/entity-resolution.md:115`;
  - STATUS.md, Note step 03 (T2), second bullet ("fixed before any label exists");
  - code comment `PolicyConfig.scala:71-72` (entity-resolution-engineer);
  - the same wording appears in the saved evidence `output-er-evaluation.md:27`, which the orchestrator should not edit.
- Evidence:
  - Nothing is git-committed; all module files are untracked.
  - The addendum scheduled the human's labeling for 09:00. Human decision 01 ("I am labeling the queue as is, now") was recorded at 09:27.
  - The policy resource was written at 09:39 and `MatchPolicy.scala` at 09:53.
- What is true:
  - er-v1 was fixed before the gold file existed (about 10:28 to 10:33).
  - No agent could read a label while authoring it: the orchestrator found `datasets/gold` absent after T2.
  - Nothing in er-v1 changed after that.
- Impact: none on leakage; this is an accuracy issue in the docs.
- Owner: documentation-engineer for the two docs. Code comment: entity-resolution-engineer, optional.
- Repair brief: replace the wording with the true statement above.

**MINOR-5. The docs claim recall is supported "without caveat", which no source says.**
- Where: `docs/entity-resolution.md:36-37`: "the recall and unresolved-rate figures are the only ones this gold set supports without caveat".
- Decision 04 says nothing about recall. Its premise (the labeler asked the wrong question for researcher pairs) also bears on the MATCH labels.
- Evidence that the MATCH labels were not judged consistently: 41 researcher MATCH rows share no title. The 4 work MATCH rows that er-v1 left as CONFLICT are different-edition DOIs.
- The documentation-engineer flagged this sentence itself.
- Owner: documentation-engineer.
- Repair brief: delete the sentence, or attribute it as an inference and add the caveat.

**MINOR-6. MODULE.md says `LocalMatchRunner` is in test scope.**
- Where: `modules/entity-resolution/MODULE.md`, Public API, the `MatchJob` bullet.
- `LocalMatchRunner` is in `src/main` (`MatchJob.scala:818`). Only the Hadoop runtime it needs is Test-scoped, which is why it runs via `Test/runMain`. As a result it ships in the job jar.
- Owner: documentation-engineer.
- Repair brief: correct the wording.

**NIT-1.** `EvaluateMain.scala:87-90` takes thresholds and the `isPolicy` mark from the module policy. It never checks that the decisions' `policyVersion` equals the loaded policy. An er-v2 run evaluated with er-v1 code would be labeled wrongly. Owner: entity-resolution-engineer. Fix: refuse the run on a mismatch.

**NIT-2.** `LocalMatchRunner.localConf` (`MatchJob.scala:823`) puts `hadoop.tmp.dir` inside the output root, so `target/er-match-run/_local-tmp/` stays in the output tree. Owner: entity-resolution-engineer.

**NIT-3.** `LeakageSuite` is a string tripwire over the top-level package only. It would not catch a path built from pieces, and it does not scan `review/`. It is acceptable as a guard but is not proof; the docs cite it as "exists for this", which is accurate. No action is required.

**NIT-4 (carried forward to Phase 07).** Empty outcome folders are absent rather than empty. The evaluation reader handles this, but a future loader must also treat an absent folder as empty only when `_SUCCESS` exists. Owners: storage-engineer when it writes the loader, or entity-resolution-engineer if it chooses to always create empty part files. MODULE.md already records this.

**For the human (not an agent finding).** `datasets/gold/er-gold-v1.csv` has mode `-rwxr-xr-x`. git would record it as 100755. Consider `chmod 644` before committing.

## Process items (report only)

- **sbt backgrounding.** Confirmed again in this review: the harness moved my 600 000 ms foreground `sbt check` to the background at 180 s. This is the fifth time this phase. The machine-limit rule ("no background shells") cannot be met by agents. It needs a harness or factory-engineer decision; the workable practice so far is to start nothing else and read the output once.
- **guard-dangerous false positives** on the literal test-marker task name. These are reported by the step 01, 02, 03b and T3 reports and by documentation-engineer. I did not observe one myself. It belongs to factory-engineer in Phase 11.
- **`target/**` ownership gap.** Verified: `docs/agents.md` has no territory row for `target/**`. guard-ownership therefore blocks a file-tool write there for every agent (T3's `output-er-evaluation.md` copy), while sbt-run outputs under `target/` pass through the shell unguarded. This is a matrix decision for the human: either factory-engineer adds a row such as `<module owner> allow target/er-*/**`, or the human accepts `docs/agent-runs/` as the canonical evidence location (current practice).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 05 Review 1 (fresh context): whole uncommitted Phase 05 diff (build.sbt stanza, modules/entity-resolution/**, tests/module-unit/entity-resolution/**, docs/entity-resolution.md, MODULE.md, STATUS.md, docs/agent-runs/phase-05/**), with target/ outputs as evidence. The orchestrator saves this as docs/agent-runs/phase-05/review-report.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status --short [--ignored], git diff --stat, git diff build.sbt / MODULE.md / STATUS.md
- find ... -printf mtimes over the module, tests and docs; ls -la --time-style=full-iso of datasets/gold, target/, target/er-eval, target/er-review
- sha256sum of the gold file, review queue, policy, prompt and eval config
- python3 -I read-only summaries: the decision run (347 decisions: provenance, reasons, audit), the profiles of the 4 conflict pairs, er-evaluation.json with Wilson recomputation, gold vs queue comparison (paths passed as arguments)
- ps (no sbt or java running)
- source toolchain/env.sh && sbt -batch check (exit 0; moved to the background at 180 s by the harness, output read once after completion)
TEST RESULTS: sbt check exit 0: Passed 94 / 98 / 106 (entityResolution) / 68; markTestRun: 14 project test runs passed; [success] Total time: 159 s, completed Nov 23, 2026, 11:16:54 AM.
VALIDATION RESULTS: APPROVE (APPROVE WITH CARRIED-FORWARD ITEMS). No CRITICAL or MAJOR.
- MINOR-1 Features.scala:192-201 DBLP homonym false REJECT on case, accent or spacing variants and unsuffixed vs suffixed names (entity-resolution-engineer; needs er-v2, carry forward)
- MINOR-2 Adjudication.scala:263-298 cached adjudications not re-validated (entity-resolution-engineer)
- MINOR-3 docs/entity-resolution.md:176-177 R-R2 misstated (documentation-engineer)
- MINOR-4 "fixed and committed before any label existed" unsupported: docs/entity-resolution.md:115, STATUS.md note step 03, PolicyConfig.scala:71 (documentation-engineer; code comment entity-resolution-engineer)
- MINOR-5 docs/entity-resolution.md:36-37 unsupported "recall without caveat" (documentation-engineer)
- MINOR-6 MODULE.md "LocalMatchRunner (test scope)" wrong (documentation-engineer)
- NIT-1 EvaluateMain does not check the decision policy version against the loaded policy; NIT-2 _local-tmp left in the output root; NIT-3 LeakageSuite is a string tripwire only; NIT-4 absent-folder handling must carry to the Phase 07 loader
- Test fairness: the suites I read are FAIR; no bent tests found.
ASSUMPTIONS:
- File mtimes are taken as the authoring order. They can be reset, but they agree with the reports and the orchestrator checks.
- human-decision-0N.md are orchestrator transcriptions of the human's words, which docs/agents.md allows in docs/agent-runs/**.
- The verdict label "APPROVE WITH CARRIED-FORWARD ITEMS" follows the work order; in role terms it is APPROVE.
RISKS (not reviewed, or reviewed only in part):
- Read only in part: TextSimilarity.scala; ErFixtures, ErNormalizeSuite, FeaturesSuite, EvaluationSuite, ReviewQueueSuite and ReviewSupplementSuite.
- Not compared against ~/ref-phase-05: whether files were copied unread, and the "no reference-repository numbers" claim. I only checked that every doc number traces to a saved Phase 05 report or to the target/ outputs.
- No baseline exists for the T1b visibility-only change to ReviewQueueMain.scala (the file is untracked).
- Distributed behaviour beyond LocalJobRunner was not reviewed: EMR, the grouping comparator with non-ASCII endpoint IDs, the data growth of ORCID aggregates.
- Multi-valued DOI sides from URLs (a chapter with a link to the book DOI) can produce R-W0 or R-W1 conflicts. Not tested on the sample.
- No mutation testing (forbidden); no other sbt run.
MANUAL ACTION REQUIRED:
- Human: decide the target/** matrix gap.
- Human: consider chmod 644 on datasets/gold/er-gold-v1.csv before committing.
- Human: decide whether MINOR-1 goes to a future er-v2.
- Human: N4 is still open.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Repair round 1. documentation-engineer takes MINOR-3 to MINOR-6. entity-resolution-engineer takes MINOR-2 and optionally NIT-1, NIT-2 and the PolicyConfig comment; MINOR-1 is carried forward. Then a fresh code-reviewer re-check (review-report-02.md), then the human gate.
```
