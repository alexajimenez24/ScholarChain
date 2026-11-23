HARNESS: claude-code
MODEL: Claude Code opus, effort high (high tier, `.agents/models.json`; the role default, unchanged by the work order)

Summary: Jobs 10 and 11, the driver and the Spark run manifest are built. The local run on this repository's Phase 04 and Phase 05 sample outputs finished with 0 invariant failures. The full `sbt check` passed once, with exit 0.

Decisions and findings to know first:
- **Contract gap closed without new core records.** The tables that had no core record (components, communities, cross-community edges, path evidence, temporal comparisons, semantic summaries) are written in the committed `EvidenceItem` shape (evidence-v1). Each uses the evidence type made for it, so nothing is BLOCKED-ON domain-engineer.
- **The config has no summary limits.** The `graph` block of scholarchain-v1.yaml names none, so no document count or list length is cut, and nothing is hard-coded in their place.
- **The sample shows little graph structure.**
  - Every sample work is from 2014, so windows 2000-2009 and 2015-2026 are empty, and the temporal comparisons compare an empty window with a full one.
  - The graph is 29 small components, and label propagation put each component in its own community, so there are 0 cross-community edges and the participation coefficient is 0 everywhere.
  - Only 4 of 120 path pairs per window are connected.
- **The bounded EMR Spark run was NOT RUN.** Cloud work starts in Phase 12; there is no infrastructure and no approval.

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 06 step 03, task T2.
- Job 10 (semantic-context preparation) and job 11 (partitioned Parquet export and load manifests for Neptune, DynamoDB, S3 Vectors and ordinary S3).
- The driver, with inputs only through the Phase 04 and Phase 05 run manifests, and a Spark run manifest.
- Mapping of the T1 contract gap onto committed core records.
- Tests, the local integration run (local[2], 4 shuffle partitions, UI off, output under target/), one full sbt check.
- EMR run reported NOT RUN.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/RunManifest.scala
  - `SparkArgs`: strict argument parsing.
  - `Upstream.locate`: finds the Hadoop J07 export and the er-v1 decisions only through their run manifests and summaries. Its checks:
    - each manifest must hash (core `Ids.run`) to the run ID in its file name;
    - the Hadoop pipeline must be hadoop-v2 and the normalization norm-v1;
    - the ER manifest's `upstreamRunId` must be that Hadoop run;
    - the snapshots must be equal;
    - the ER policy must equal the configured `entityResolution.policyVersion`.
  - `SparkRunManifest`: stage, `LogicVersion` "spark-v1", snapshot, both upstream run IDs and folders, the whole `graph` config, the vector index names, and the schemas read and written. It holds no timestamp and no output path; its hash is the run ID.
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Summaries.scala: job 10.
  - Fixed templates, filled only from computed tables and source records. Per window:
    - a researcher profile for every researcher;
    - a community profile for every community of 2 or more members;
    - a path summary for every found path;
    - a topic-institution summary for every topic with a work in the window.
  - `chunkId` follows core `Ids.chunk(entity, metricVersion, docType, 0, text)`; `textHash` is `sha256:` plus the hex digest; the vector index name comes from `vectors.indexes`.
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Export.scala: job 11.
  - `Evidence.item` builds the core EvidenceItem columns.
  - `graphNodes` and `graphEdges` build core GraphNode and GraphEdge rows. Edge IDs follow core `Ids.graphEdge`.
  - Builders for components, communities, path evidence and temporal comparisons.
  - `write`: repartition by the partition columns, write partitioned Parquet, read back to count rows and files.
  - `loadManifests`.
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/SparkPipeline.scala: driver `main` and `run`.
  - Exit codes: 2 for a usage, config or upstream error; 3 when an invariant failed.
  - Reads `part-*` only and requires `_SUCCESS` on both inputs.
  - Per-input reject counts, plus integrity checks:
    - every record's `provenance.runId` must equal its upstream run;
    - every decision must carry the configured policy.
  - Path pairs are every pair of the window's sampled betweenness sources.
  - Writes the run manifest first and `<runId>-summary.json` last. The summary holds the status, all invariants, the Spark settings and stage timings.
  - Sets local property `scholarchain.stage` for each pipeline stage.
- /home/ajcreator/scholarchain/tests/module-unit/spark/RunManifestSuite.scala: 5 tests (arguments; upstream located; refusals for a wrong upstream, a wrong policy and an edited manifest; run-ID determinism and sensitivity; invariant collection). No Spark session.
- /home/ajcreator/scholarchain/tests/module-unit/spark/PipelineSuite.scala: 5 tests running `SparkPipeline.run` end to end on a hand-checkable fixture (4 researchers, 3 works) laid out exactly like the J07 export and the er-v1 decisions with their manifests. Expected values are worked out in the class comment.
  - Run manifest and status.
  - The exact text of one researcher profile; chunk and evidence IDs match the core formulas.
  - Community, path and topic texts.
  - Every table decodes with core `JsonLines.decodeValid` and passes core `Validate.*` (GraphNode, GraphEdge, ResearcherMetrics, CoauthorEdge, EvidenceItem).
  - Load manifest counts.
- /home/ajcreator/scholarchain/tests/module-unit/spark/LocalPipelineRun.scala: the local-run entry point, run through `Test/runMain`, so no build change was needed. Its `CaptureListener` (a SparkListener, not the UI) records per stage the tasks, shuffle read and write and the pipeline stage, and per SQL execution the call site and the formatted physical plan.

FILES MODIFIED: none outside the new files. T1 sources and suites are unchanged; build.sbt, core, hadoop, entity-resolution and MODULE.md are untouched.

COMMANDS RUN (all observed):
- `sbt sparkAnalytics/test`, attempt 1 (log `modules/spark/target/t2-test1.log`): compile error. `value withFilter is not a member of Either[...]` at RunManifest.scala:109, because of a tuple pattern in a for over Either. Fixed by binding a case class.
- `sbt sparkAnalytics/test`, attempt 2 (log `t2-test2.log`): exit 0, `Passed: Total 30, Failed 0`. The harness moved it to the background at 180 s; I started nothing else.
- `sbt "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture .../modules/spark/target/local-run --hadoop-manifest .../target/er-source-run/run-manifests/run-6d1175dbfdb874a9.json --er-manifest .../target/er-match-run/run-manifests/run-02cecabcf3e68312.json --output .../target/spark-local-run"`: exit 0, `local run run-4903a4f9eabba786: 0 invariant failures`, `[success] Total time: 175 s`.
- `scalafmt --test` on all Spark files: one comment in PipelineSuite failed the check. Formatted it; the re-check printed "All files are formatted with scalafmt :)".
- `sbt check`, once (log `modules/spark/target/t2-check.log`): exit 0, `[success] Total time: 429 s (0:07:09.0)`.

TEST RESULTS:
- `sbt check` summary lines, verbatim:
```
[info] Passed: Total 94, Failed 0, Errors 0, Passed 94
[info] Passed: Total 68, Failed 0, Errors 0, Passed 68
[info] Passed: Total 112, Failed 0, Errors 0, Passed 112
[info] Passed: Total 98, Failed 0, Errors 0, Passed 98
[info] Passed: Total 30, Failed 0, Errors 0, Passed 30
[success] Total time: 429 s (0:07:09.0), completed Nov 23, 2026, 1:26:28 PM
```
- The Spark project ran 30 tests: CanonicalSuite 5, CollaborationSuite 3, WindowAnalyticsSuite 4, RunManifestSuite 5, TemporalSuite 2, GraphAlgorithmsSuite 6, PipelineSuite 5 (121.3 s).

VALIDATION RESULTS: see OUTPUT-SPARK-LOCAL-RUN below. Key points:
- Run run-4903a4f9eabba786 has status SUCCEEDED, with 77 invariants checked and all 0.
- Graph: 203 nodes and 403 edges; 79 researchers and 76 coauthor edges in each non-empty window.
- Components: 29, the largest 5 (fraction 0.0633). Communities: 29.
- Paths: 4 of 120 pairs found per non-empty window.
- Rejects: 0.
- Shuffle totals per pipeline stage come from the SparkListener.

ASSUMPTIONS:
1. Contract mapping, all onto committed core records:
   - components: EvidenceItem COMPONENT_RECORD, with the sampled distance statistics in `values`;
   - communities and cross-community edges: COMMUNITY_RECORD, partition `recordKind`;
   - path evidence: GRAPH_PATH, with `graphPath` = researcher IDs and the hop edge IDs in `values`;
   - temporal comparisons: METRIC_RECORD;
   - semantic summaries: SEMANTIC_PROFILE;
   - graph tables: GraphNode and GraphEdge;
   - metrics and coauthor edges: the core shape plus visible extra columns (`maximumTeamWorkCount`, `inLargestComponent`, `supportingWorkIds`, `windowLabel`).
   EvidenceItem `score` is 0.0 at export; it is a retrieval-time field.
2. Neptune: the manifest points at Parquet in the GraphNode and GraphEdge shape. Converting it to the bulk-load CSV belongs to the storage loader (storage-engineer). DynamoDB, S3 Vectors and S3 targets are named by config keys (`dynamo.researcherMetricTable`, `vectors.vectorBucket`, `vectors.indexes.*`, `storage.graphBucket`), not by values.
3. Path evidence pairs: every pair of the window's sampled betweenness sources (`betweennessSampleSources`, `betweennessSeed`), up to `pathMaxDepth` hops. No new parameter was introduced, because the config has no benchmark-pair setting.
4. Summaries are unbounded because the config has no summary limits. Every document is chunk 0; splitting long texts is left to the embedding step, since the chunk ID has an ordinal.
5. "Selected paper or topic summaries": topic summaries were built, one per topic per window. Paper summaries were not built; the `abstracts` vector index stays empty in this run.
6. The integrity checks on input provenance run IDs and on the decision policy version are run as invariants.

RISKS:
- Not done:
  - The bounded EMR Spark run is NOT RUN: cloud work starts in Phase 12, with no infrastructure and no approval.
  - No paper summaries.
  - No summary limits; this needs a config field from domain-engineer before a full-DBLP run.
  - Neptune bulk-load CSV conversion (storage-engineer).
- Scale notes:
  - The job 10 union is one checkpointed plan of 2532 nodes (execution 409), which costs planning time at scale.
  - Every window recomputes the sampled sources for path evidence; it is a cheap top-k.
  - Community member lists in summaries grow with community size.
  - `supportingWorkIds` is unbounded per edge (carried over from T1).
- The sample cannot exercise cross-community bridges or meaningful temporal change, because every work is from 2014. Those behaviors are covered only by the T1 known-graph suites.
- State left behind:
  - Run outputs under /home/ajcreator/scholarchain/target/spark-local-run/ (tables, `_checkpoints`, run-manifests).
  - Raw capture under /home/ajcreator/scholarchain/modules/spark/target/local-run/: `stages.tsv` (1197 stages), `phases.tsv`, `plans.txt`, `plans-index.tsv` (331 executions), `selected-plans.txt`, `outcome.txt`.
  - Logs `modules/spark/target/{t2-test1,t2-test2,local-run,t2-check}.log`.
  - Test temp dirs in the system temp folder.
  - No commits, no cloud.
- Notes for modules/spark/MODULE.md (documentation-engineer):
  - New public API: `SparkPipeline.main`, `SparkPipeline.run(spark, SparkArgs, ScholarChainConfig): Either[String, Outcome]`, `SparkPipeline.StageProperty`, `SparkPipeline.invariants`, `SparkArgs.parse`, `Upstream.locate`, `SparkRunManifest.{build, runId, LogicVersion}`, `Summaries.*`, `Export.*`, `Evidence.*`.
  - Usage: `--hadoop-manifest <.../run-manifests/<id>.json> --er-manifest <...> --output <dir> [--checkpoint-dir <dir>]`.
  - Exit codes: 0 done; 2 usage, config or upstream error; 3 invariant failure.
  - Local run: `sbt "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture <dir> ..."`.
  - Known issues: the items above.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 03s (documentation-engineer updates STATUS.md), then step 04 (docs/spark-jobs.md and modules/spark/MODULE.md from observed numbers), then a fresh code-reviewer review. The orchestrator saves the section below verbatim as docs/agent-runs/phase-06/output-spark-local-run.md.

## OUTPUT-SPARK-LOCAL-RUN

# Phase 06 local Spark integration run (observed)

- Run ID `run-4903a4f9eabba786`, snapshot 2026-11-22, status SUCCEEDED, 77 invariants checked, all 0.
- Command: `sbt "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture /home/ajcreator/scholarchain/modules/spark/target/local-run --hadoop-manifest /home/ajcreator/scholarchain/target/er-source-run/run-manifests/run-6d1175dbfdb874a9.json --er-manifest /home/ajcreator/scholarchain/target/er-match-run/run-manifests/run-02cecabcf3e68312.json --output /home/ajcreator/scholarchain/target/spark-local-run"`. Exit 0, sbt `Total time: 175 s`.
- Spark 4.0.1, master local[2], `spark.sql.shuffle.partitions` 4, AQE on, UI off, reliable checkpoints in `target/spark-local-run/_checkpoints`.
- Inputs, through their manifests only:
  - Hadoop run-6d1175dbfdb874a9 (hadoop-v2, norm-v1), J07 `target/er-source-run/export/snapshot=2026-11-22/run_id=run-6d1175dbfdb874a9`;
  - er-v1 run-02cecabcf3e68312 (policy sha 82ce9ae5...), decisions `target/er-match-run/match-decisions/policy=er-v1/snapshot=2026-11-22/run_id=run-02cecabcf3e68312`.
  - Both manifests hash to their run IDs; the ER `upstreamRunId` is the Hadoop run.

## Inputs, partitions and integrity rejects

| kind | valid rows | rejected | input partitions | provenance runId mismatches |
|---|---|---|---|---|
| works | 90 | 0 | 2 | 0 |
| mentions | 219 | 0 | 2 | 0 |
| authorpapers | 219 | 0 | 2 | 0 |
| pairs | 186 | 0 | 2 | 0 |
| topics | 44 | 0 | 2 | 0 |
| institutions | 37 | 0 | 2 | 0 |
| venues | 1 | 0 | 1 | 0 |
| decisions | 347 | 0 | 2 | 0 |

- Decisions of another policy: 0. Decision counts: RESEARCHER ACCEPTED 267, RESEARCHER UNRESOLVED 5, WORK ACCEPTED 71, WORK CONFLICT 4 (policy er-v1).
- Job 1 (canonical tables):
  - 219 mentions into 79 researchers (52 merged, 33 with ORCID, 4 unresolved, 0 identity conflicts);
  - 90 source works into 42 canonical works (25 merged, 0 without year, 0 conflicts);
  - 104 AUTHORED, 0 AUTHORED rejects;
  - 1 work with a team above every source list;
  - 44 topics, 37 institutions, 1 venue, 47 affiliations;
  - dangling topic, venue and institution references: 0;
  - invariants: authored without researcher 0, authored without work 0;
  - identity clustering converged in 3 (researchers) and 2 (works) iterations.
- Job 2 (pairs): 186 pair candidates, 0 self pairs dropped, 0 refused by the team cap (300), 0 rejects of each kind, 87 researcher pair-work rows, unordered pairs 0.
- Rejects table: 0 rows.

## Windows, graph, component, community and path counts

| window | researchers | edges | components | largest (fraction) | singletons | communities (size >= 2) | cross-community edges | betweenness sources / population | max coreness | path pairs found / requested |
|---|---|---|---|---|---|---|---|---|---|---|
| 1950-2026 | 79 | 76 | 29 | 5 (0.0633) | 0 | 29 (29) | 0 | 16 / 79 (sampled) | 4 | 4 / 120 |
| 2000-2009 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 / 0 | 0 | 0 / 0 |
| 2010-2014 | 79 | 76 | 29 | 5 (0.0633) | 0 | 29 (29) | 0 | 16 / 79 (sampled) | 4 | 4 / 120 |
| 2015-2026 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 / 0 | 0 | 0 / 0 |

- Every sample work is from 2014, so 1950-2026 and 2010-2014 hold the same graph, and 2000-2009 and 2015-2026 are empty.
- Convergence in the non-empty windows: components converged in 2 iterations, label propagation in 2, coreness in 2; the BFS was not truncated.
- Sampled distance statistics, non-empty windows:
  - 37 sampled pairs, average path length 1.027, effective diameter 1, max distance 2;
  - path tests: 4 found, all at distance 1; 116 pairs not connected within 6 hops (different components); hop-edge invariant 0.
- Per-window invariants, all 0 in all 4 windows:
  - metric rows vs nodes;
  - C_r <= W_r, W_r <= T_r, M_r <= T_r, M_r without works;
  - publication count < 1;
  - unordered edges, edges without publication, edge years outside the window, missing endpoints, duplicate edge IDs;
  - component sizes and community sizes summing to the node count;
  - cross-community edge ends.
- Temporal (job 9):
  - 2000-2009 -> 2010-2014: 79 researchers, 0 present in both windows. Limitation: unequal lengths, 10 vs 5 years.
  - 2010-2014 -> 2015-2026: 79 researchers, 0 present in both windows. Limitation: unequal lengths, 5 vs 12 years.
  - Component and community retention: 0 in both comparisons.
  - Skipped: 1950-2026 vs 2000-2009, because the windows overlap.

## Metric distributions (p50, p90, p99; windows 1950-2026 and 2010-2014, identical)

| measure | p50 | p90 | p99 |
|---|---|---|---|
| publicationCount | 1 | 2 | 6 |
| distinctCoauthorCount | 2 | 4 | 4 |
| totalCoauthorOccurrences | 2 | 5 | 6 |
| maximumCoauthorsOnOnePaper | 2 | 4 | 5 |
| weightedDegree | 2 | 4 | 6 |
| coreness | 2 | 4 | 4 |
| participationCoefficient | 0 | 0 | 0 |
| bridgeScore (sampled betweenness) | 0 | 0 | 0.000822 |

## Semantic summaries (job 10) and export (job 11)

- Documents: researcher-profile 158, collaboration-community 58, collaboration-path 8, topic-institution-summary 88 (312 in all). Duplicate chunk IDs 0, empty texts 0.
- Graph tables:
  - nodes 203: Researcher 79, Work 42, Topic 44, Institution 37, Venue 1;
  - edges 403: AUTHORED 104, COAUTHORED_WITH 152, HAS_TOPIC 75, PUBLISHED_IN 25, AFFILIATED_WITH 47;
  - duplicate node IDs 0, duplicate edge IDs 0, missing endpoints 0.

| table (record) | rows | files | partitions |
|---|---|---|---|
| graph-nodes (graph-node-v1) | 203 | 5 | label |
| graph-edges (graph-edge-v1) | 403 | 5 | edgeType |
| coauthor-edges (coauthor-edge-v1) | 152 | 2 | windowLabel (76 + 76) |
| researcher-metrics (researcher-metrics-v1) | 158 | 2 | windowLabel (79 + 79) |
| components (evidence-v1) | 58 | 2 | windowLabel |
| communities (evidence-v1) | 58 | 2 | windowLabel, recordKind (community only) |
| path-evidence (evidence-v1) | 240 | 2 | windowLabel (120 + 120) |
| metric-comparisons (evidence-v1) | 158 | 2 | comparison (79 + 79) |
| semantic-summaries (evidence-v1) | 312 | 8 | docType, windowLabel |
| rejects (reject-record) | 0 | 0 | stage |

## Output paths

- Tables: `/home/ajcreator/scholarchain/target/spark-local-run/spark/snapshot=2026-11-22/run_id=run-4903a4f9eabba786/<table>/`
- Load manifests: `.../run_id=run-4903a4f9eabba786/load-manifests/{neptune,dynamodb,s3vectors,s3}.json`
  - neptune: nodes 203, edges 403;
  - dynamodb: `dynamo.researcherMetricTable`, key researcherId plus windowLabel, 158 rows;
  - s3vectors: dimension 256; indexes researcher-profile-v1 158, collaboration-community-v1 58, collaboration-path-v1 8, topic-institution-summary-v1 88;
  - s3: all 10 tables, plus the run manifest and summary.
- Run manifest: `/home/ajcreator/scholarchain/target/spark-local-run/run-manifests/run-4903a4f9eabba786.json`; summary: `.../run-4903a4f9eabba786-summary.json`.
- Raw capture: `/home/ajcreator/scholarchain/modules/spark/target/local-run/`, holding `stages.tsv` (1197 completed stages), `phases.tsv`, `plans.txt` and `plans-index.tsv` (331 distinct stage and call-site executions), `selected-plans.txt`.

## Shuffle per pipeline stage (SparkListener, sums over completed stages)

| pipeline stage | Spark stages | tasks | min-max tasks | shuffle read bytes (records) | shuffle write bytes (records) | stage wall s |
|---|---|---|---|---|---|---|
| read | 58 | 87 | 1-2 | 2677 (47) | 2677 (47) | 19.26 |
| job1-canonical | 214 | 664 | 1-8 | 286765 (5532) | 286221 (5525) | 31.36 |
| job2-pairs | 27 | 70 | 1-4 | 8713 (144) | 8713 (144) | 2.43 |
| window-1950-2026 | 226 | 920 | 1-21 | 206140 (3740) | 196262 (3606) | 23.31 |
| paths-1950-2026 | 56 | 198 | 1-13 | 40785 (438) | 40785 (438) | 5.42 |
| window-2000-2009 | 43 | 52 | 1-4 | 0 (0) | 0 (0) | 6.18 |
| paths-2000-2009 | 7 | 7 | 1-1 | 0 (0) | 0 (0) | 1.18 |
| window-2010-2014 | 226 | 920 | 1-21 | 206047 (3740) | 196362 (3606) | 19.52 |
| paths-2010-2014 | 56 | 198 | 1-13 | 40785 (438) | 40785 (438) | 4.72 |
| window-2015-2026 | 43 | 52 | 1-4 | 0 (0) | 0 (0) | 5.50 |
| paths-2015-2026 | 7 | 7 | 1-1 | 0 (0) | 0 (0) | 0.97 |
| job9-temporal | 22 | 64 | 1-4 | 10590 (190) | 10590 (190) | 1.74 |
| job10-summaries | 144 | 511 | 1-32 | 327149 (5631) | 305079 (5249) | 17.40 |
| job11-export | 68 | 480 | 1-32 | 351299 (2472) | 351299 (2472) | 15.28 |

How to read the partition and shuffle numbers:
- Every join and aggregation that is not broadcast is a hash shuffle with 4 partitions.
- AQE coalesced many shuffle reads to 1 task (`AQEShuffleRead` in the plans) and broadcast every small side (`BroadcastExchange`), because all tables here are KiB-sized.
- Task counts above 4 per stage come from unions of several inputs (summaries, export) and from multi-file scans.

## Formatted physical plans of main stages (final adaptive plans, trimmed; full text in plans.txt)

- Execution 90, job2-pairs, CoauthorGraph.scala:85, researcher pair mapping:
  - left broadcast joins mention -> researcher (twice) and work map;
  - inner broadcast joins to AUTHORED for both ends;
  - partial HashAggregate, then `Exchange` (hash on a, b, workId), then final HashAggregate;
  - 126 shuffled rows in, 87 rows out.
- Execution 99, window-1950-2026, WindowAnalytics.scala:38, window edges:
```text
ResultQueryStage (11)
+- Project (10)
   +- ObjectHashAggregate (9)
      +- ShuffleQueryStage (8), Statistics(sizeInBytes=24.9 KiB, rowCount=82)
         +- Exchange (7)
            +- ObjectHashAggregate (6)
               +- * Project (5)
                  +- * Filter (4)
                     +- * Scan ExistingRDD (3)
```
- Execution 104, window-1950-2026, GraphAlgorithms.scala:54, one connected-components iteration:
```text
+- * BroadcastHashJoin LeftOuter BuildRight (31)
   :- ShuffleQueryStage (5), Statistics(sizeInBytes=6.8 KiB, rowCount=79)
   :  +- Exchange (4)
   :     +- * Scan ExistingRDD (3)
   +- BroadcastQueryStage (30), Statistics(sizeInBytes=8.0 MiB, rowCount=79)
      +- BroadcastExchange (29)
         +- SortAggregate (28)
            +- * Sort (27)
               +- AQEShuffleRead (26)
                  +- ShuffleQueryStage (25), Statistics(sizeInBytes=11.5 KiB, rowCount=134)
                     +- Exchange (24)
                        +- SortAggregate (23)
                           +- * Sort (22)
                              +- * Project (21)
                                 +- * BroadcastHashJoin Inner BuildRight (20)
```
- Execution 114, window-1950-2026, GraphAlgorithms.scala:114, one label-propagation iteration:
```text
+- * BroadcastHashJoin LeftOuter BuildRight (43)
   :- ShuffleQueryStage (5), Statistics(sizeInBytes=7.4 KiB, rowCount=79)
   :  +- Exchange (4)
   +- BroadcastQueryStage (42)
      +- BroadcastExchange (41)
         +- * Project (40)
            +- * Filter (39)
               +- Window (38)
                  +- WindowGroupLimit (37)
                     +- * Sort (36)
                        +- AQEShuffleRead (35)
                           +- ShuffleQueryStage (34), Statistics(sizeInBytes=7.4 KiB, rowCount=79)
                              +- Exchange (33)
                                 +- WindowGroupLimit (32)
                                    +- * Sort (31)
                                       +- * HashAggregate (30)
```
- Execution 172, paths-1950-2026, GraphAlgorithms.scala:197, one BFS level:
```text
ResultQueryStage (32)
+- SortAggregate (31)
   +- * Sort (30)
      +- ShuffleQueryStage (29), Statistics(sizeInBytes=4.3 KiB, rowCount=32)
         +- Exchange (28)
            +- SortAggregate (27)
               +- * Sort (26)
                  +- * Project (25)
                     +- * BroadcastHashJoin LeftAnti BuildRight (24)
                        :- * Project (18)
                        :  +- * BroadcastHashJoin Inner BuildLeft (17)
```
- Execution 409, job10-summaries, SparkPipeline.scala:329:
  - the union of the 16 document plans (4 kinds x 4 windows), each a chain of left broadcast joins over per-key aggregations;
  - 2532 plan nodes; 312 rows out.
- Execution 422, job11-export, Export.scala:125, graph-nodes write:
```text
ResultQueryStage (8)
+- Execute InsertIntoHadoopFsRelationCommand (7)
   +- WriteFiles (6)
      +- * Sort (5)
         +- AQEShuffleRead (4)
            +- ShuffleQueryStage (3), Statistics(sizeInBytes=96.2 KiB, rowCount=203)
               +- Exchange (2)
                  +- * Scan ExistingRDD (1)
```
- Each checkpointed stage's plan starts with `TableCacheQueryStage` over an `InMemoryRelation`: `Lineage.cut` persists the rows, then writes a reliable checkpoint.

## Bounded EMR Spark run

NOT RUN. Reason: cloud work starts in Phase 12; there is no infrastructure and no approval.
