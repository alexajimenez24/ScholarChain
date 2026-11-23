<!-- Saved verbatim by the orchestrator from the OUTPUT-SPARK-LOCAL-RUN-R1 section of repair-01-spark-engineer.md (spark-engineer, repair R1-1). -->
# Phase 06 local Spark integration rerun after repair R1-1 (observed)

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

