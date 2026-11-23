LABELER NOTE (orchestrator): this report contains er-v1's decisions for the v1b pairs and the
engineer's expectation about their labels. The human should not read it until labeling of
target/er-review/er-review-v1b.csv is finished, so the labels stay blind to the policy.

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier (the entity-resolution-engineer default)

The supplementary queue is built: target/er-review/er-review-v1b.csv has 60 rows and none of them is in v1. `sbt check` exited 0, and the v1 sha256 is unchanged.

The problem is that it will not fix the class imbalance. These samples hold no pair with different ORCIDs and no pair with different DBLP homonym numbers. Only 10 pairs meet "compatible initials, no shared context". The other 50 qualify only as "same block, different clusters". In these samples each block is one real person seen through DBLP, Crossref and OpenAlex, so those 50 rows are almost certainly the same person on different chapters (for example Sven J. Dickinson on "Shock Graph" and on "Geons"). er-v1 agrees: it rejected none of the 60.

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05, step 03b, task T1b (human decision 01). Build the supplementary review queue target/er-review/er-review-v1b.csv: up to 60 likely non-match pairs from upstream run run-6d1175dbfdb874a9, chosen only by the human's structural criteria, with no overlap with v1. Then give every pair an er-v1 decision from the unchanged policy. The orchestrator saves this report as step-03b-entity-resolution-engineer-report.md.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/resources/scholarchain/er/review-supplement-v1b.json: the versioned config. It sets the queue version er-review-v1b, generator er-review-supplement-v1, seed er-review-v1b, maxRows 60, the criteria in priority order, the context dimensions (work, coauthor, affiliation, venue, title_token) and the cluster link feature same_source_cluster.
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewSupplement.scala: the pure part. It holds `SupplementCriterion`, `SupplementConfig` (load, parse, validate), `BlockLink`, `Block`, `MentionContext` and `SupplementPair`. `ReviewSupplement` holds links, blocks, workPairsNotEmitted, sharedContext, holds, qualifying, roundRobin, select, candidate, rows, csvRecords and pairIds.
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewSupplementMain.scala: the entry point. It hashes v1 before and after the run and fails if v1 changed. It refuses gold paths, refuses v1 or its sidecar as --out, and refuses an existing output unless `--overwrite true` is given.
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/ReviewSupplementSuite.scala: 11 tests.
- Git-ignored outputs (`git check-ignore -v` shows `.gitignore:24:target/` for each):
  - /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv
  - /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv.provenance.json
  - /home/ajcreator/scholarchain/target/er-source-run-v1b/ (the supplementary candidate run)
  - /home/ajcreator/scholarchain/target/er-match-run-v1b/ (the er-v1 decisions for v1b)

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewQueueMain.scala: `children`, `lines` and `writeAtomically` changed from `private` to `private[review]`, so the new generator can reuse them. Their behavior is unchanged. The er-v1 policy, thresholds, features, rules, MatchJob and target/er-review/er-review-v1.csv are untouched.

COMMANDS RUN (each with `source toolchain/env.sh`, one sbt process at a time):
1. `sbt -batch fmt "entityResolution/testOnly scholarchain.er.review.*"`: `[info] Passed: Total 26, Failed 0, Errors 0, Passed 26` (11 new tests and 15 from ReviewQueueSuite).
2. Generator plus the unchanged er-v1 runner in one invocation. Both ended with `[success]`.
   - `sbt -batch "entityResolution/runMain scholarchain.er.review.ReviewSupplementMain --run-root target/er-source-run --review-v1 target/er-review/er-review-v1.csv --out target/er-review/er-review-v1b.csv --candidates-out target/er-source-run-v1b"`
   - `"entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root target/er-source-run-v1b --output target/er-match-run-v1b"` (adjudicator off, the default)
   - Paths are absolute in the real command.
3. `python3 -I` checks of the CSV and the sidecar, zcat summaries of the decisions, `sha256sum` and `git check-ignore`.
4. The generator again with `--overwrite true`, then `check`, in one sbt invocation, exit 0. The rerun wrote byte-identical output: same sha256 08c02f22..., which shows determinism on the real data.
   - The harness moved this run to the background at 180 s. I started nothing else and read the output once, after the completion notice. No sbt or java process was left running.
   - An earlier try was blocked before it ran: guard-dangerous matched the marker task name inside my grep pattern (the known Phase 11 false positive).

TEST RESULTS (`sbt check`, exit 0, verbatim lines):
- `[info] Passed: Total 94, Failed 0, Errors 0, Passed 94`
- `[info] Passed: Total 103, Failed 0, Errors 0, Passed 103`
- `[info] Passed: Total 98, Failed 0, Errors 0, Passed 98`
- `[info] Passed: Total 68, Failed 0, Errors 0, Passed 68`
- `[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `[success] Total time: 157 s (0:02:37.0), completed Nov 23, 2026, 10:33:08 AM`

Entity resolution now has 103 tests: the 92 from before plus 11 in ReviewSupplementSuite. They cover:
- config loading and refusal of bad values;
- clusters built as connected components, and exact or work candidates never defining a block;
- each of the four criteria, positive and negative;
- every qualifying pair listing every criterion that holds, in priority order, and a missing source record being an error;
- disjointness from a given v1 ID set and from the upstream candidates;
- priority tiers, the maxRows cap, and no padding (60 asked, 9 exist, 9 delivered);
- determinism under shuffled input, and a different order under a different seed;
- block round robin;
- v1 columns only, no score, criterion or label strings, and label and note empty;
- the candidate records: valid match-candidate-v1, features empty, provenance naming the generator;
- strict reading of the v1 pair IDs;
- an end-to-end run. It checks that v1's sha256 is unchanged, that the sidecar holds no criterion names, and that the written run is read by the unchanged `MatchJob.upstream` (pipeline er-review-supplement-v1, the exact folder empty, works copied). It also checks the refusals.

VALIDATION RESULTS:
- Generator stdout, verbatim:
```
run run-6d1175dbfdb874a9: 418 candidate lines, 347 distinct pairs, 52 researcher blocks, 193 block members
review v1 /home/ajcreator/scholarchain/target/er-review/er-review-v1.csv: 280 pair ids, sha256 f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e
supplement er-review-v1b, generator er-review-supplement-v1, seed er-review-v1b, maxRows 60
same-block researcher pairs 499; qualifying 423 (in review v1 158, other upstream candidates 41, eligible 224)
same-block work pairs not emitted upstream 0 (the criteria are researcher criteria; no work pair is selected)
criterion (first that holds)                eligible  selected
DIFFERENT_ORCID                                    0         0
DBLP_HOMONYM_DIFFERENT                             0         0
COMPATIBLE_INITIALS_NO_SHARED_CONTEXT             10        10
SAME_BLOCK_DIFFERENT_CLUSTERS                    214        50
criterion (any that holds, overlapping)     eligible  selected
DIFFERENT_ORCID                                    0         0
DBLP_HOMONYM_DIFFERENT                             0         0
COMPATIBLE_INITIALS_NO_SHARED_CONTEXT             10        10
SAME_BLOCK_DIFFERENT_CLUSTERS                    224        60
selected rows: 60 (researcher 60, work 0), blocks 7
selected source pairs: CROSSREF-DBLP 18, CROSSREF-OPENALEX 15, DBLP-OPENALEX 27
wrote 60 rows to /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv
wrote provenance to /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv.provenance.json (csv sha256 08c02f228229f907ce7de1b967dd22ce5de5af6263c06411fb23c7a68c86c845)
wrote supplementary candidates run run-8f780e21fab13ffd (60 lines) to /home/ajcreator/scholarchain/target/er-source-run-v1b
review v1 sha256 unchanged: f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e
```
- The file: /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv, sha256 08c02f228229f907ce7de1b967dd22ce5de5af6263c06411fb23c7a68c86c845. Python csv check:
  - The header is identical to v1 (the same 20 columns in the same order), and every row has 20 fields.
  - 60 rows (all researcher), 60 distinct pair_ids, 0 overlap with v1's 280 pair_ids.
  - label and note are empty in every row, and no name_or_title cell is empty.
  - Both record_ids in every row are `m:` mention IDs.
  - No banned string appears: Likely, Borderline, score, stratum, MATCH, confidence, criterion, the four criterion names, or er-v1.
  - No bare decimal cell, and no non-empty cell starts with = + - or @.
  - UTF-8 with no BOM and CRLF record ends (61 CRLF).
- The sidecar holds the upstream run, its candidate and export files, the v1 path and sha256 (f752...), the config resource and its sha256, the supplementary run ID and the csv sha256. Per-pair criteria and criterion counts are left out, because the sidecar sits next to the file the labeler opens. The counts are in this report only.
- v1 sha256 was f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e before both runs, after both runs, and at the end.
- How er-v1 decides every v1b pair (orchestrator only; keep this out of the labeler's file):
  - The generator writes each selected pair as a match-candidate-v1 record in /home/ajcreator/scholarchain/target/er-source-run-v1b, which is laid out like an upstream run. Supplementary run run-8f780e21fab13ffd holds:
    - `run-manifests/run-8f780e21fab13ffd.json`, which records pipeline er-review-supplement-v1, upstreamRunId run-6d1175dbfdb874a9, normalization norm-v1, the config, v1, works and pair-ID hashes, and no timestamp;
    - `match-candidates/kind=blocked/.../part-r-00000` with the 60 lines;
    - an empty `kind=exact` part file;
    - byte copies of the upstream `export/works` files;
    - a `_SUCCESS` file in each folder.
  - Each candidate record carries:
    - candidateId equal to the pair_id (`ErCandidate.idOf`, the Phase 04 formula);
    - blockKey set to the J04 block;
    - features `{}` and no identifiers, so er-v1 decides from the endpoint profiles alone;
    - provenance with policyVersion `er-review-supplement-v1` and runId run-8f780e21fab13ffd.
  - The unchanged LocalMatchRunner (policy er-v1, policy sha256 82ce9ae5..., thresholds accept 0.9, reject 0.35, LLM band [0.7, 0.9), adjudicator OFF, prompt and model "none") produced decision run **run-b1098fbaba96c360**, at target/er-match-run-v1b/match-decisions/policy=er-v1/snapshot=2026-11-22/run_id=run-b1098fbaba96c360.
  - Outcomes: 60 decisions: 48 ACCEPTED, 0 REJECTED, 12 UNRESOLVED, 0 CONFLICT. All 60 took the PROBABILISTIC path, and the unresolved rate is 0.20.
    - The 48 ACCEPTED have reason SCORE_AT_OR_ABOVE_ACCEPT, with scores from 0.997 to 1.0.
    - Of the 12 UNRESOLVED, 10 have SCORE_BETWEEN_THRESHOLDS and 2 have SCORE_IN_LLM_BAND plus LLM_OFF, with scores from 0.389 to 0.896.
- For T3: evaluate v1 rows against target/er-match-run (run-02cecabcf3e68312) and v1b rows against target/er-match-run-v1b (run-b1098fbaba96c360). Report v1, v1b and combined separately. Every v1b pair_id has exactly one decision, because Phase 04 candidates were excluded from v1b.
- Not applicable here: the confusion matrix, precision, recall and F1. No labels were used; that is T3.

ASSUMPTIONS:
- The universe is every mention pair inside one Phase 04 researcher block. Block membership comes from the J04 `blockKey`, and source clusters are the connected components of J04's `same_source_cluster` member links. Hadoop logic is not re-implemented and the hadoop module is not imported.
- Each criterion is tested separately, and a pair qualifies if any one holds; a pair inside one source cluster qualifies only through one of the other three.
  - Different ORCIDs: both mentions carry an ORCID and the iDs differ.
  - DBLP homonym: both mentions are DBLP, their strings differ, and they are equal once the homonym number is removed.
  - No shared context: the initials are compatible, and the two mentions share no work, coauthor key, affiliation, venue key or title token.
  - Same block, different clusters: the two mentions are in different source clusters.
- Tiers are filled in the configured order. Within a tier, a seeded round robin over blocks keeps the two 18-member blocks from taking every row. The output is ordered by a second hash.
- Excluded:
  - the 158 qualifying pairs already in v1;
  - the 41 qualifying pairs that are other Phase 04 candidates. Their er-v1 decision would come from a different run, with upstream features.
- Work pairs: all 0 same-block work pairs were already emitted by Phase 04 (J04 compares every pair in a work block), and the criteria are researcher criteria, so v1b has no work rows.
- I read v1 only for its pair_id column, plus its sha256.
- Between my start and my end, `datasets/gold/er-gold-v1.csv` appeared; the human created it. I only saw the file name in a directory listing. I did not open, read or write it.

RISKS:
1. v1b probably does not supply non-matches. These samples have no different-ORCID or DBLP-homonym pairs inside a block: each block has at most one ORCID and one DBLP string. Every block's mentions carry the same full name, from the same encyclopedia chapters seen through three sources. On this data, "different clusters" mostly means "different sources", not "the source says these are different people". er-v1 found no REJECTED among the 60. If the human labels v1b, expect mostly MATCH, and the imbalance will remain. A real fix needs a larger bounded sample with real homonyms (dataset-engineer, within the manifest's rules).
2. v1b decisions use no upstream features. That is faithful to these pairs, which J04 never compared, but it differs from how v1 pairs were decided. T3 must report v1 and v1b separately as well as combined. The work order states this too.
3. What I did not do:
   - change er-v1, MatchJob, thresholds or features;
   - read er-v1 outputs before selecting (I read er-match-run-v1b only after the selection, to report it);
   - read or write datasets/gold;
   - run T3.
4. State left behind:
   - Untracked new files: 2 sources, 1 resource and 1 test suite.
   - One modified untracked source: ReviewQueueMain, with the visibility change only.
   - Git-ignored outputs: target/er-review/er-review-v1b.csv and its sidecar, target/er-source-run-v1b and target/er-match-run-v1b.
   - No sbt or java process is running.
5. Notes for modules/entity-resolution/MODULE.md (documentation-engineer):
   - Public API: `scholarchain.er.review.ReviewSupplement`, `SupplementConfig` (resource `/scholarchain/er/review-supplement-v1b.json`), `SupplementCriterion`, `MentionContext`, `Block`, `BlockLink`, `SupplementPair` and `ReviewSupplementMain` (parseArgs, run, main).
   - Command: `sbt "entityResolution/runMain scholarchain.er.review.ReviewSupplementMain --run-root <hadoop out> --review-v1 <v1 csv> --out <csv> --candidates-out <dir> [--run-id <id>] [--overwrite true]"`. Then run `entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root <candidates-out> --output <dir>`.
   - Known issue: risk 1.

MANUAL ACTION REQUIRED: The human should decide whether to label er-review-v1b.csv, knowing it probably holds mostly matches (risk 1), or to ask for a larger bounded sample with real homonyms first. Any labels go only into datasets/gold/er-gold-v1.csv, which agents do not write.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md after 03b. The human decides on v1b and labels. T3 starts when the human reports the gold file is complete.

---
Orchestrator check (observed): v1b header identical to v1; 60 rows, all researcher, 20
fields each; label and note empty; 0 pair_id overlap with v1; sha256 v1b starts
08c02f228229f907ce7d, v1 still starts f75204756578ffc93414; no sbt or java running;
datasets/gold/er-gold-v1.csv exists (listing only, not opened).
