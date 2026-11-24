HARNESS: claude-code
MODEL: Claude Opus 5.5 (claude-opus-5-5), high tier (role default, as the work order says)

The 25k match run finished on the first try, with no code change: decision run **run-cb5a0421c406737d**, over Hadoop run run-03a81f6f54f3fc3c, produced 903,286 decisions in 1:19:48 wall time. That is about 10 minutes under the 90-minute limit, so no step-down to 10,000 is needed. The margin is small, though: the run was mostly waiting rather than computing (CPU 36%, see RISKS).

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 08 step S3b. Run er-v1 `LocalMatchRunner` with the adjudicator off on Hadoop run run-03a81f6f54f3fc3c (25k subset), the same way as S3: absolute paths, a 2 GB heap, er-v1 unchanged, no gold-set re-evaluation and no code change.

RUN
- Decision run ID: run-cb5a0421c406737d. Upstream run: run-03a81f6f54f3fc3c (pipeline hadoop-v2, normalization norm-v1). Snapshot: 2026-11-24.
- Output: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/er-out (250 MB).
  - Decisions are in `match-decisions/policy=er-v1/snapshot=2026-11-24/run_id=run-cb5a0421c406737d/`, which holds accepted, rejected, unresolved, conflict, audit, `_counters.json` and `_SUCCESS`.
  - `adjudications/` and `rejects-v1/` are absent because they have zero rows (known Phase 05 behavior).
- Manifest: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/er-out/run-manifests/run-cb5a0421c406737d.json
- Summary: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/er-out/run-manifests/run-cb5a0421c406737d-summary.json
- The ER-J1 intermediate is `er-out/_er-profiles/policy=er-v1/run_id=run-cb5a0421c406737d` (59 MB).
- The runner created the scratch folder `er-out-tmp` and removed it, as designed (checked: it no longer exists).
- Git-ignored: `git check-ignore -v` gives `datasets/samples/local/.gitignore:1:*` for both er-out and the manifest. `git status --porcelain` is byte-identical to the snapshot taken before the run.
- Wall time: 1:19:48 by GNU time; sbt reported 4773 s. Start 2026-11-24T14:34:52Z, end 15:54:41Z.
- Attempts: one, rc=0. No memory failure.
- Heap: the forked JVM ran with -Xmx2g, set with `set entityResolution / Test / javaOptions := Seq("-Xmx2g")`. build.sbt is unchanged.
- Peak memory: GNU time reports a maximum resident set of 1,387,644 kB for the largest process, and 0 swaps.
- No other sbt or JVM was running before the start (checked with `ps`); `free -m` showed 3,509 MB available.

OUTCOME COUNTS (from the summary json; the decompressed line counts of each outcome folder match exactly, and audit has 903,286 lines)
- Total decisions: 903,286.

| Outcome | All | Researcher | Work |
|---|---|---|---|
| ACCEPTED | 122,405 | 108,154 | 14,251 |
| REJECTED | 771,520 | 770,827 | 693 |
| UNRESOLVED | 7,370 | 7,351 | 19 |
| CONFLICT | 1,991 | 1,970 | 21 |

- Decisions per match path: EXACT_ID 215,264; DETERMINISTIC_BIBLIOGRAPHIC 13,062; PROBABILISTIC 674,960; LLM_ADJUDICATED 0.
- Unresolved rate: 0.008159.
- Counters:
  - INPUT_RECORDS 914,413, which equals S2b J03 EXACT_CANDIDATES 65,766 plus J04 BLOCKED_CANDIDATES 848,647.
  - DUPLICATE_RECORDS 11,127, and DECISIONS + DUPLICATE_RECORDS = INPUT_RECORDS.
  - LLM_OFF 733 (731 researcher and 2 work cases in the LLM band; they stay UNRESOLVED).
- Decisions that need human review (UNRESOLVED + CONFLICT): 9,361. The gold-labeling queue (`ReviewQueueMain`) was NOT RUN.
- CONFLICT by reason code (all on the EXACT_ID path):

| Count | Kind | Reason code |
|---|---|---|
| 1,918 | RESEARCHER | ORCID_DISAGREES |
| 22 | RESEARCHER | HOMONYM_SUFFIX_VS_ORCID |
| 21 | RESEARCHER | SAME_WORK_SAME_ORCID |
| 9 | RESEARCHER | ORCID_CONTRADICTORY |
| 20 | WORK | DOI_DISAGREES |
| 1 | WORK | ARXIV_DISAGREES |

- UNRESOLVED by path and reason code:

| Count | Kind | Path | Reason codes |
|---|---|---|---|
| 5,029 | RESEARCHER | PROBABILISTIC | SCORE_BETWEEN_THRESHOLDS |
| 1,540 | RESEARCHER | EXACT_ID | ORCID_EQUAL_NAMES_DISAGREE |
| 731 | RESEARCHER | PROBABILISTIC | SCORE_IN_LLM_BAND, LLM_OFF |
| 51 | RESEARCHER | EXACT_ID | OPENALEX_EQUAL_NAMES_DISAGREE |
| 16 | WORK | EXACT_ID | DOI_EQUAL_TITLES_DISAGREE |
| 2 | WORK | PROBABILISTIC | SCORE_IN_LLM_BAND, LLM_OFF |
| 1 | WORK | EXACT_ID | SAME_SOURCE_SHARES_DOI |

FILES CREATED:
- Tracked by git: none.
- Git-ignored run output: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/er-out/**.
- Scratch files, all in /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/:
  - s3b-run1.log
  - s3b-time.txt
  - s3b-start.txt
  - s3b-git-before.txt
- Harness output: /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/tasks/bqcz1o8qk.output.

FILES MODIFIED: none.

COMMANDS RUN:
- `source toolchain/env.sh && /usr/bin/time -v -o <scratch>/s3b-time.txt timeout 5400 sbt -batch 'set entityResolution / Test / javaOptions := Seq("-Xmx2g")' "entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/hadoop-out --run-id run-03a81f6f54f3fc3c --output /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/er-out --adjudicator off"` gave rc=0.
  - The harness moved it to the background at 180 s. I started nothing else, did not poll, and read the output once after the completion notice.
- `git check-ignore -v` on er-out and the manifest: both are ignored.
- `git status --porcelain`, diffed against the snapshot taken before the run: no difference.
- `ps`, `free -m`, `df -h`, `du -sh`, and `zcat | wc -l` per outcome folder (these match the summary).
- `zcat | python3 -I` reason-code tallies for conflict and unresolved, and a `python3 -I` read of the manifest and summary.

TEST RESULTS:
- I ran no `sbt entityResolution/test` and no `sbt check`, because no code changed. The work order forbids a code change, and the phase runs its one full `sbt check` at the end of step 3.
- The Stop hook `verify-tests-ran.sh` asked for `sbt check` while the match run was in progress. I did not run it: the only source changes in the tree belong to other owners (modules/spark, datasets/scripts) and were there before this session started, and a second sbt would have broken the one-sbt rule.
- The only stderr lines in the log are the known SLF4J "No SLF4J providers" warnings (the Phase 02 MINOR-A class). There were no errors or exceptions.

VALIDATION RESULTS:
- Policy er-v1, policySha256 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff, unchanged from S3.
- Thresholds: accept 0.9, reject 0.35, LLM band [0.7, 0.9), as recorded in the manifest.
- Versions: features er-features-v1, normalization er-norm-v1, logic er-job-v1.
- Adjudicator OFF: promptVersion and modelVersion are "none" in the manifest and null in the decision rows. llmBudgetPerRun is 0, with no cache. Reducers 4, compress true.
- Gold-set metrics: NOT RE-EVALUATED, as the work order says. The Phase 05 figures stand (researcher P 0.938 / R 0.987 / F1 0.962; work P 1.000 / R 0.947 / F1 0.973). There is no confusion matrix for this run.
- Provenance: a sampled CONFLICT row carries sourceId, targetId, the full feature map, rulesFired [R-R2], path EXACT_ID, confidence 0.5, reasonCodes [ORCID_DISAGREES], policyVersion er-v1, and provenance (sourceRecordId mc:researcher:00ad04921e1e6692e067, snapshot 2026-11-24, runId run-cb5a0421c406737d, schemaVersion match-v1).
- Representative error class: two mentions with the same full name (name_similarity 1, year_gap 8) and disagreeing ORCIDs, score 0.459. The pair is held as CONFLICT and not merged (iron law 1).

ASSUMPTIONS:
- Output location: the one the work order names, beside the S2b hadoop-out. I confirmed it is git-ignored before and after the run.
- I passed `--run-id run-03a81f6f54f3fc3c` explicitly, as in S3.
- "Peak memory" is GNU time's maximum resident set size for the largest process. I did not measure heap use separately.

RISKS:
- Time margin:
  - 1:19:48 is close to the 100k run's 1:21:47, even with about 5 times fewer decisions.
  - GNU time shows CPU 36% (S3: 106%) and 1,438 s of user time (S3: 4,610 s), so the computing work scaled down as expected but the job spent most of its wall time waiting.
  - My unverified guess is I/O wait or host contention on the WSL VM: 918 MB of swap was already in use at the start, and there were 16,673 major page faults.
  - I did not investigate further (timebox). A rerun under the same machine conditions could go past 90 minutes.
- Deliberately not done: no gold-set evaluation, no review-queue generation, no adjudication, no code change, no `sbt check`.
- State left behind:
  - er-out is 250 MB of git-ignored data.
  - S3's 100k er-out (1.3 GB) is still in datasets/samples/local/2026-11-24/er-out.
  - S2b's hadoop-out/_local-tmp and the untracked modules/hadoop/datasets/ belong to hadoop-engineer and were left alone.
- Notes for modules/entity-resolution/MODULE.md (documentation-engineer):
  - No public API change.
  - Done: Phase 08 S3b run run-cb5a0421c406737d at 25k works, 903,286 decisions in about 80 min with -Xmx2g, peak RSS about 1.4 GB.
  - Known issue: at this scale wall time does not scale with input size; it looked I/O- or host-bound (CPU 36%).
  - Known issue: LocalMatchRunner still needs absolute paths.
  - Known issue: the empty adjudications/ and rejects-v1/ folders are absent, as before.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: documentation-engineer updates STATUS.md (S3b done). Then step 00b: spark-engineer runs the local Spark pipeline on Hadoop run run-03a81f6f54f3fc3c (/home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/hadoop-out) and decision run run-cb5a0421c406737d (/home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/er-out).
