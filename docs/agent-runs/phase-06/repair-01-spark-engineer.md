HARNESS: claude-code
MODEL: Claude Code opus, effort high (spark-engineer high tier, `.agents/models.json`)

Summary: I fixed all seven findings in this round. MAJOR-1 used policy (a): a contradicting decision is never merged through a chain of accepted links.
- `sbt sparkAnalytics/test` passed 33 of 33 on the first attempt.
- The local rerun (`run-769e0d672ecd3c19`) succeeded. All 94 invariants it checks are 0.
- On the sample, the cut refused 3 accepted researcher links. That kept apart the 3 UNRESOLVED pairs the review found merged, so the run now has 81 researchers instead of 79.
- The full `sbt check` exited 0.

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 06 repair round 1, task R1-1. Fix MAJOR-1 (policy (a)), MINOR-1 to MINOR-4, NIT-3 and NIT-4. Then run `sbt sparkAnalytics/test`, rerun the local integration run and run one full `sbt check`. Report saved as `docs/agent-runs/phase-06/repair-01-spark-engineer.md`.

PER FINDING:
- **MAJOR-1: fixed.**
  - Code: `/home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Canonical.scala`
    - the policy text is in the object doc (lines 21-66);
    - `constrainedClusters` (line 114) does the clustering and the cut;
    - `IdentityCut` (line 710, pure step `cut` at line 727, UDF `udfFor` at line 787) runs on the executors.
  - The rule is a deterministic, constrained single linkage:
    1. Build the connected components of the ACCEPTED links, bounded by `componentMaxIterations`.
    2. A cluster is contradicted if it holds both ends of an UNRESOLVED, CONFLICT or REJECTED decision, or two ORCID iDs.
    3. Rebuild each contradicted cluster from its own accepted links, in descending confidence. Ties are broken by the ordered endpoint IDs (a, then b).
    4. A link is applied unless it would put a contradicting pair, or two ORCID iDs, into one cluster. Otherwise it is refused.
  - Effect: when one path joins a contradicting pair, exactly the weakest link on that path is refused. Weak links that do not lie between contradicting nodes are kept. Uncontradicted clusters do not change. The same cut applies to WORK decisions.
  - How it runs: each contradicted cluster is grouped into one row (one shuffle by label), and a Java UDF2 rebuilds it on the executor that holds the row. There is no iteration loop and nothing is collected to the driver; the driver reads only counts and at most 3 outcome rows.
  - Counters, for both researchers and works:
    - `<kind>DecisionLinksAccepted`, `...DroppedUnknownEndpoint`, `...RefusedByCut`, `...Applied` (now the links actually applied), `<kind>ClustersCut`;
    - per outcome O: `<kind>Contradictions_O`, `...InOneClusterBeforeCut_O`, `...KeptApart_O`, and the invariant `invariant_<kind>ContradictionsMerged_O`;
    - for researchers only: `researcherClustersWithSeveralOrcidsBeforeCut` and `invariant_researcherClustersWithSeveralOrcids`;
    - a new `canonicalWorksUnresolved` counter.
  - Flags:
    - `unresolved` is unchanged: an UNRESOLVED decision touches a member.
    - `identityConflict` now means that the cut refused an accepted link touching a member. The old meaning (a contradiction inside a cluster) can no longer happen; it is an invariant.
    - The summary text now reads "identity conflict flagged (an accepted identity link was refused)" (`Summaries.scala`).
  - Tests: `/home/ajcreator/scholarchain/tests/module-unit/spark/CanonicalSuite.scala`
    - The a2-x3 expectation is worked out in the header comment: the cut refuses a3-x3 (0.92, the weakest link of the path a2-a1-a3-x3), giving 5 researchers.
    - New known-graph test (line 345) on four clusters:
      - K1: an UNRESOLVED pair inside one cluster, the sample's shape. The cut refuses h-p and keeps the weak h-k link.
      - K2: a CONFLICT on a cycle; 2 links are refused.
      - K3: two ORCID iDs.
      - K4: no contradiction; unchanged.
    - The AUTHORED and pair expectations were updated: dblp:w2 now has team size 3, 7 AUTHORED rows and 0 self pairs.
    - Self-pair dropping is still covered, using a mapping where x3 is mapped to Ann.
- **MINOR-1: fixed.**
  - `invariant_componentsNotConverged` (`WindowAnalytics.scala:91`), `invariant_corenessNotConverged` (`:220`), and `invariant_researcherClusterNotConverged` / `invariant_workClusterNotConverged` (in `Canonical.constrainedClusters`) are 0 or 1. Label propagation stays a flag (`communitiesConverged`).
  - Test: `WindowAnalyticsSuite.scala:155` uses the existing cut-off case (`componentMaxIterations` = 1). Components and coreness both give 1.
  - The CanonicalSuite cut test also checks `invariant_tClusterNotConverged` = 1 at a bound of 1.
- **MINOR-2: fixed.**
  - `SparkArgs.checkpointRequirement` (`RunManifest.scala:28`) refuses any master other than `local` or `local[...]` when `--checkpoint-dir` is not given. `SparkPipeline.main` (line 62) exits 2 with a usage error.
  - The usage text states the rule (`RunManifest.scala:21`).
  - Test: `RunManifestSuite.scala:69`.
- **MINOR-3: fixed. Choice: option 2, keep per-window COAUTHORED_WITH edges and make the window filter public API.**
  - Why:
    - Spec line 1092 defines the coauthor edge ID as a hash that includes the graph window.
    - Spec line 1375 requires the edge to store the selected time window.
    - Window-scoped graph and path queries (spec 2590) need the per-window graph in Neptune. Loading only one window would make those impossible in Neptune.
    - "Duplicate researcher pairs consolidated" (spec 664) holds within each window.
  - Code:
    - `Export.graphEdges` doc (`Export.scala` around line 250).
    - The Neptune load manifest has a new `coauthoredWith` block (`Export.scala:505`): scope "one edge per researcher pair per window", `windowProperty` "window", the configured window labels, and the rule "every query over COAUTHORED_WITH filters on exactly one window value".
  - Test: a PipelineSuite load-manifest assertion.
  - documentation-engineer should write this into MODULE.md Public API for storage-engineer and service-engineer.
- **MINOR-4: fixed.**
  - `Canonical.build` now takes `identityPolicy`; the driver passes `up.erPolicyVersion` (`SparkPipeline.scala:246`). Researcher and work rows carry `provenance.policyVersion` = er-v1 (`Canonical.scala:389, 511`).
  - Researcher and Work graph nodes keep that policy (`Export.identityProvenance`, `Export.scala:151`). Other nodes keep graph-v1.
  - Researcher nodes carry `mentionIds` as a '|'-joined, sorted list (`Export.scala:173`), the same pattern as Work `memberWorkIds`.
  - Tests: CanonicalSuite (provenance) and PipelineSuite (node `mentionIds`; er-v1 on Researcher and Work nodes, graph-v1 on Topic).
- **NIT-3: fixed.**
  - The `Schemas.scala` comment (lines 12-20) now says what is caught: SCHEMA_MISMATCH and REQUIRED_FIELD_NULL rejects.
  - It also says what is not: unknown or added fields, and renamed optional fields, pass silently. The guard against that is the upstream version check in `Upstream.locate`.
- **NIT-4: fixed.**
  - The rejects table now applies core `RejectRecord.of` per row through a UDF4 (`SparkPipeline.coreReject`, line 510; used at line 392). The truncation lengths now live only in core.
  - A null reason or input stays null.
- **Related change: `SparkRunManifest.LogicVersion` "spark-v1" -> "spark-v2" (`RunManifest.scala:184`).** The code's documented rule bumps it on any logic change. Without the bump, the rerun would have written different results under the old run ID `run-4903a4f9eabba786`.

FILES CREATED: none

FILES MODIFIED (all under `/home/ajcreator/scholarchain/`; untracked files, so `git status` shows no new entries):
- `modules/spark/src/main/scala/scholarchain/spark/Canonical.scala`
- `modules/spark/src/main/scala/scholarchain/spark/Support.scala`
- `modules/spark/src/main/scala/scholarchain/spark/WindowAnalytics.scala`
- `modules/spark/src/main/scala/scholarchain/spark/Export.scala`
- `modules/spark/src/main/scala/scholarchain/spark/SparkPipeline.scala`
- `modules/spark/src/main/scala/scholarchain/spark/RunManifest.scala`
- `modules/spark/src/main/scala/scholarchain/spark/Schemas.scala`
- `modules/spark/src/main/scala/scholarchain/spark/Summaries.scala`
- `tests/module-unit/spark/CanonicalSuite.scala`
- `tests/module-unit/spark/WindowAnalyticsSuite.scala`
- `tests/module-unit/spark/RunManifestSuite.scala`
- `tests/module-unit/spark/PipelineSuite.scala`

COMMANDS RUN (each with its output observed):
1. `sbt -no-colors "sparkAnalytics/scalafmtAll" "sparkAnalytics/test"`, log `modules/spark/target/r1-test1.log`.
   - scalafmt reformatted 3 + 4 files.
   - Exit 0, Passed 33 of 33, first attempt.
   - The harness moved it to the background at 180 s; I started nothing else and waited for the notice.
2. `sbt -no-colors "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture /home/ajcreator/scholarchain/modules/spark/target/local-run-r1 --hadoop-manifest /home/ajcreator/scholarchain/target/er-source-run/run-manifests/run-6d1175dbfdb874a9.json --er-manifest /home/ajcreator/scholarchain/target/er-match-run/run-manifests/run-02cecabcf3e68312.json --output /home/ajcreator/scholarchain/target/spark-local-run"`, log `modules/spark/target/r1-local-run.log`.
   - Exit 0: `local run run-769e0d672ecd3c19: 0 invariant failures`, `[success] Total time: 115 s`.
   - The `[error]` lines in that log are Spark stderr WARN lines that sbt labels as errors.
3. `python3 -I` read-only reads of the run summary and the four load manifests.
4. `sbt -no-colors check`, log `modules/spark/target/r1-check.log`: exit 0, moved to the background at 180 s, and I waited.

TEST RESULTS (verbatim):
`sbt sparkAnalytics/test`:
```
Test run scholarchain.spark.CanonicalSuite finished: 0 failed, 0 ignored, 6 total 48.175s
Test run scholarchain.spark.CollaborationSuite finished: 0 failed, 0 ignored, 3 total 1.869s
Test run scholarchain.spark.WindowAnalyticsSuite finished: 0 failed, 0 ignored, 5 total 33.923s
Test run scholarchain.spark.RunManifestSuite finished: 0 failed, 0 ignored, 6 total 0.073s
Test run scholarchain.spark.TemporalSuite finished: 0 failed, 0 ignored, 2 total 21.517s
Test run scholarchain.spark.GraphAlgorithmsSuite finished: 0 failed, 0 ignored, 6 total 22.905s
Test run scholarchain.spark.PipelineSuite finished: 0 failed, 0 ignored, 5 total 66.898s
[info] Passed: Total 33, Failed 0, Errors 0, Passed 33
[success] Total time: 226 s (0:03:46.0), completed Nov 23, 2026, 3:00:36 PM
```
`sbt check` summary lines (verbatim):
```
[info] Passed: Total 94, Failed 0, Errors 0, Passed 94
[info] Passed: Total 68, Failed 0, Errors 0, Passed 68
[info] Passed: Total 112, Failed 0, Errors 0, Passed 112
[info] Passed: Total 33, Failed 0, Errors 0, Passed 33
[info] Passed: Total 98, Failed 0, Errors 0, Passed 98
[success] Total time: 292 s (0:04:52.0), completed Nov 23, 2026, 3:08:21 PM
```

VALIDATION RESULTS:

## OUTPUT-SPARK-LOCAL-RUN-R1

Run and setup:
- Run ID `run-769e0d672ecd3c19` (logic spark-v2; was `run-4903a4f9eabba786` under spark-v1).
- Snapshot 2026-11-22, status SUCCEEDED, 94 invariants checked, all 0 (was 77).
- Spark 4.0.1, local[2], 4 shuffle partitions, AQE on, UI off, reliable checkpoints.
- Inputs are unchanged: Hadoop run-6d1175dbfdb874a9, er-v1 run-02cecabcf3e68312. Input row counts and rejects are as in step 03.

Output folders:
- The output folder `target/spark-local-run` was reused, because the pipeline does not refuse to overwrite.
- The new run is in its own folder: `target/spark-local-run/spark/snapshot=2026-11-22/run_id=run-769e0d672ecd3c19/`, with its manifest and summary in `target/spark-local-run/run-manifests/run-769e0d672ecd3c19{,-summary}.json`.
- The old `run_id=run-4903a4f9eabba786` folder is still there.
- Listener capture: `modules/spark/target/local-run-r1/` (`stages.tsv` 1335 lines, `phases.tsv`, `plans.txt`, `plans-index.tsv`, `outcome.txt`). I gave `--capture` a fresh folder so the step 03 capture stays intact; this is the only argument that differs from step 03.

Job 1 identity and the contradiction cut, researchers:
- Accepted links 267, dropped for an unknown endpoint 0, refused by the cut 3, applied 264. Clusters cut: 2.
- UNRESOLVED: 5 decisions, 3 inside one cluster before the cut, 5 kept apart, 0 merged (invariant).
- CONFLICT 0/0/0/0; REJECTED 0/0/0/0.
- Clusters with several ORCID iDs: 0 before the cut, 0 after.
- Identity clustering converged in 3 iterations; the not-converged invariant is 0.

Job 1 identity and the contradiction cut, works:
- Accepted 71, refused 0, applied 71. Clusters cut: 0.
- CONFLICT: 4 decisions, 0 inside one cluster before the cut, 4 kept apart, 0 merged.
- Converged in 2 iterations.

Job 1 counts:
- 219 mentions into 81 researchers (was 79): 52 merged, 33 with ORCID, 6 unresolved (was 4), 4 with identityConflict (was 0).
- 90 source works into 42 canonical works: 25 merged, 0 conflict, 0 unresolved.
- AUTHORED 106 (was 104), 0 rejects. Works with a team above every source list: 3 (was 1).
- 47 affiliations; dangling references 0.

Job 2:
- 186 candidates, 0 self pairs, 0 refused by the team cap, 0 rejects.
- 89 researcher pair-work rows (was 87).

Windows: the non-empty windows 1950-2026 and 2010-2014 are identical. 2000-2009 and 2015-2026 are empty.

| window | researchers | edges | components | largest (fraction) | singletons | communities (size >= 2) | cross-community edges | betweenness sources / population | max coreness | path pairs found / requested |
|---|---|---|---|---|---|---|---|---|---|---|
| 1950-2026 | 81 | 78 | 29 | 6 (0.0741) | 0 | 29 (29) | 0 | 16 / 81 | 4 | 4 / 120 |
| 2010-2014 | 81 | 78 | 29 | 6 (0.0741) | 0 | 29 (29) | 0 | 16 / 81 | 4 | 4 / 120 |

- Iterations: components 3, label propagation 3 (converged), coreness 2. Both new not-converged invariants are 0 in all 4 windows.
- Path tests: all 4 found pairs are at distance 1. Sampled distances: 38 pairs, average 1.0789, effective diameter 1, max 3.

Metric distributions (p50 / p90 / p99, both non-empty windows; previous values in brackets where they changed):

| measure | p50 | p90 | p99 |
|---|---|---|---|
| publicationCount | 1 | 2 | 6 |
| distinctCoauthorCount | 2 | 4 | 4 |
| totalCoauthorOccurrences | 2 | 5 | 6 |
| maximumCoauthorsOnOnePaper | 2 | 4 | 5 |
| weightedDegree | 2 | 4 | 6 |
| coreness | 1 (was 2) | 4 | 4 |
| participationCoefficient | 0 | 0 | 0 |
| bridgeScore | 0 | 0 | 0.001602 (was 0.000822) |

Job 9 (temporal):
- 2000-2009 -> 2010-2014: 81 researchers, 0 present in both windows.
- 2010-2014 -> 2015-2026: 81 researchers, 0 present in both windows.
- Retention 0. 1950-2026 vs 2000-2009 skipped (overlapping windows).

Job 10 (summaries):
- researcher-profile 162, collaboration-community 58, collaboration-path 8, topic-institution-summary 88: 316 in all (was 312).
- Duplicate chunk IDs 0, empty texts 0.

Job 11 table rows (files):

| table | rows | files | breakdown |
|---|---|---|---|
| graph-nodes | 205 | 5 | Researcher 81, Work 42, Topic 44, Institution 37, Venue 1 |
| graph-edges | 409 | 5 | AUTHORED 106, COAUTHORED_WITH 156, HAS_TOPIC 75, PUBLISHED_IN 25, AFFILIATED_WITH 47 |
| coauthor-edges | 156 | 2 | 78 per window |
| researcher-metrics | 162 | 2 | |
| components | 58 | 2 | |
| communities | 58 | 2 | |
| path-evidence | 240 | 2 | |
| semantic-summaries | 316 | 8 | |
| metric-comparisons | 162 | 2 | |
| rejects | 0 | 0 | |

Graph invariants (duplicate node IDs, duplicate edge IDs, missing edge endpoints): all 0.

Load manifests:
- Neptune: nodes 205, edges 409, plus the `coauthoredWith` block (windowProperty "window", windows 1950-2026, 2000-2009, 2010-2014, 2015-2026).
- DynamoDB: researcher-metrics 162.
- S3 Vectors: researcher-profile-v1 162, collaboration-community-v1 58, collaboration-path-v1 8, topic-institution-summary-v1 88.
- S3: 10 tables.

Per-stage shuffle totals (`phases.tsv`, from the SparkListener):

| stage | Spark stages | tasks | shuffle read bytes | shuffle write bytes |
|---|---|---|---|---|
| job1-canonical | 269 | 824 | 298816 | 298774 |
| job2-pairs | 27 | 70 | 8781 | 8781 |
| window-1950-2026 | 259 | 1160 | 243364 | 233319 |
| paths-1950-2026 | 64 | 261 | 41360 | 41360 |
| job10-summaries | 144 | 511 | 329884 | 307616 |
| job11-export | 68 | 480 | 363106 | 363106 |

ASSUMPTIONS:
- Policy (a) as routed by the orchestrator. REJECTED and second-ORCID contradictions are cut too, as the work order lists them.
- `identityConflict` is redefined as "an accepted link touching a member was refused by the cut", because after the fix a contradiction inside a cluster is an invariant (always 0) and the old meaning would always be false.
- A null link confidence counts as 0, the weakest. er-v1 always writes a confidence.
- Bumping `LogicVersion` to spark-v2 follows the code's own documented rule ("bumped on any logic change").

RISKS:
- No config bound on cut size: the contradiction cut has no iteration loop, but a contradicted cluster's links sit in one row on one executor, so memory grows with the largest contradicted cluster. No core config field caps it, and I added none (I may not edit core). Before a full-DBLP run, domain-engineer may want a field such as a maximum number of links per contradicted cluster; I did not report BLOCKED-ON, because the work order's bounds are met with the existing fields.
- Greedy, not optimal: constrained single linkage is a greedy rule. With several contradictions it can refuse more links than the minimum cut would.
- Java UDFs: `IdentityCut` and `coreReject` are opaque to Catalyst. Each runs once per contradicted cluster or per reject row, which is small.
- New key names: the convergence flags `componentsConverged`, `corenessConverged` and `<kind>ClusterConverged` are replaced by the invariant keys. Any downstream reader of the old keys must change; none exists in the repository.
- State left behind:
  - `target/spark-local-run` holds two runs (run-4903a4f9eabba786 and run-769e0d672ecd3c19);
  - new captures and logs under `modules/spark/target/` (`r1-test1.log`, `r1-local-run.log`, `r1-check.log`, `local-run-r1/`);
  - all of it is git-ignored. No commits, no cloud.
- Not done: MINOR-5 (build-engineer), NIT-1 and NIT-2 (documentation-engineer), MODULE.md and `docs/spark-jobs.md` text (R1-3). No EMR run.
- Notes for `modules/spark/MODULE.md`:
  - Public API changes:
    - `Canonical.build(src, run, identityPolicy)` (new parameter);
    - `Canonical.constrainedClusters`, `Canonical.Clusters`, `Canonical.ContradictingOutcomes`;
    - `IdentityCut.cut` / `IdentityCut.udfFor`;
    - `SparkArgs.checkpointRequirement`, and `--checkpoint-dir` required for non-local masters (Phase 12 run script, infra-engineer);
    - `Export.loadManifests` takes the window labels; the Neptune manifest has a `coauthoredWith` block;
    - the Researcher node property `mentionIds`, and er-v1 provenance on Researcher and Work rows and nodes;
    - `LogicVersion` is spark-v2.
  - Identity policy text: the contradiction cut, the new counters and invariants, and the `identityConflict` meaning.
  - COAUTHORED_WITH is window-scoped: every graph query filters on one `window`.
  - Known issue: no config bound on contradicted-cluster size (above).
- Process: guard-dangerous blocked a read-only command that combined `sed` with the test-marker path (a false positive, already tracked for factory-engineer in Phase 11). I reran it without the marker path.

MANUAL ACTION REQUIRED: none for this task. The human may overrule policy (a) for policy (b) at the gate.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE:
- R1-2 build-engineer (MINOR-5, job jar on the final tree).
- R1-3 documentation-engineer (NIT-1, NIT-2, MAJOR-1 and MINOR-3 text, rerun numbers above, the MODULE.md notes).
- Then Review 2 (`review-report-02.md`), with a fresh code-reviewer.
