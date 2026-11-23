# Spark jobs (Phase 06)

Owner: documentation-engineer.
Module notes: [modules/spark/MODULE.md](../modules/spark/MODULE.md).

## Evidence and status

Every number in this file comes from a saved Phase 06 report.
Nothing here was derived or recomputed by the author of this file.
The reference repository's numbers are not used.

- SR1: the build-engineer report,
  [report](agent-runs/phase-06/step-01-build-engineer-report.md).
- SR2: the spark-engineer report for jobs 1 to 9,
  [report](agent-runs/phase-06/step-02-spark-engineer-report.md).
- SR3: the spark-engineer report for jobs 10 and 11, the driver and the local run,
  [agent-report.md](agent-runs/phase-06/agent-report.md).
- LR0: the step 03 local run, kept as history,
  [output-spark-local-run.md](agent-runs/phase-06/output-spark-local-run.md).
- R1: the repair report of spark-engineer (R1-1),
  [repair-01-spark-engineer.md](agent-runs/phase-06/repair-01-spark-engineer.md).
- LR: the local rerun after the repair, the source of every run number below unless
  marked LR0,
  [output-spark-local-run-r1.md](agent-runs/phase-06/output-spark-local-run-r1.md).
- RB: the build-engineer report of R1-2,
  [repair-01-build-engineer.md](agent-runs/phase-06/repair-01-build-engineer.md).
- RV: the Phase 06 review 1,
  [review-report.md](agent-runs/phase-06/review-report.md).

State (R1): the full `sbt check` exited 0 after the repair, with Spark 33 tests passing
(core 94, adversarial 68, entityResolution 112, hadoop 98).
The local rerun on the Phase 04 and Phase 05 sample outputs finished with run
`run-769e0d672ecd3c19`, status SUCCEEDED, 94 invariants checked, all 0 (LR).
The step 03 run `run-4903a4f9eabba786` (logic spark-v1, 77 invariants) is history only:
the repair changed the logic, so its numbers no longer describe the code (LR0).
The bounded EMR Spark run was NOT RUN: cloud work starts in Phase 12, and there is no
infrastructure and no approval (SR3, LR0).
The documented commands were not replayed by the documentation-engineer, because the work
order forbids running sbt here; they are copied from SR3, LR0 and R1.

## Pipeline

Spark reads only the Phase 04 J07 export and the Phase 05 er-v1 decisions, never raw
data or `datasets/gold/`.
It locates both only through their run manifests.
All code is DataFrame and SQL functions, because Dataset encoders need Scala 2 TypeTags
(`build.sbt`, comment above `sparkAnalytics`).

| Job | What it builds | Source file |
|---|---|---|
| 1 canonical | researchers, canonical works, AUTHORED, topics, institutions, venues | `Canonical.scala` |
| 2 pairs and edges | researcher pairs, temporal weighted COAUTHORED_WITH edges | `CoauthorGraph.scala` |
| 3 breadth and team size | per-researcher P, C, T, A, M, W measures | `CoauthorGraph.scala` |
| 4 components | connected components per window | `GraphAlgorithms.scala` |
| 5 paths | path evidence and sampled distance statistics | `Paths.scala` |
| 6 communities | weighted label propagation per window | `GraphAlgorithms.scala` |
| 7 bridges | cross-community measures and sampled betweenness | `Bridges.scala` |
| 8 k-core | coreness per researcher | `GraphAlgorithms.scala` |
| 9 temporal | comparison of window pairs | `Temporal.scala` |
| 10 summaries | semantic documents from fixed templates | `Summaries.scala` |
| 11 export | partitioned Parquet and load manifests | `Export.scala` |

Jobs 3 to 8 run once per configured window in `WindowAnalytics.scala` (SR2).
The job numbers follow `docs/agent-runs/phase-06/prompt.md`; SR2 and SR3 label the
pairs and edges builder "jobs 2 and 3" in places.

Tests: 33 Spark tests (CanonicalSuite 6, CollaborationSuite 3,
WindowAnalyticsSuite 5, RunManifestSuite 6, TemporalSuite 2, GraphAlgorithmsSuite 6,
PipelineSuite 5) (R1).
Expected values of each fixture are worked out in the suite comments (SR2).

### Inputs through run manifests

`Upstream.locate` (SR3) checks, before reading any data:

- each manifest hashes (core `Ids.run`) to the run ID in its file name;
- the Hadoop pipeline is hadoop-v2 and the normalization is norm-v1;
- the ER manifest's `upstreamRunId` is that Hadoop run;
- the snapshots are equal;
- the ER policy equals the configured `entityResolution.policyVersion`.

The reader takes `part-*` files only and requires `_SUCCESS` on both inputs (SR3).
A line that fails the schema or has a null required field becomes a counted reject
(`SCHEMA_MISMATCH`, `REQUIRED_FIELD_NULL`) (SR2).
Two integrity checks run as invariants: every record's `provenance.runId` equals its
upstream run, and every decision carries the configured policy (SR3).

The schema guard (R1, NIT-3 fix) catches `SCHEMA_MISMATCH` and `REQUIRED_FIELD_NULL`
rejects only.
Unknown or added fields and renamed optional fields pass silently; the guard against
upstream drift is the version check in `Upstream.locate`.

Local run input counts, all with 0 rejects and 0 run ID mismatches (LR0; the rerun used
the same inputs, LR):

| kind | valid rows | input partitions |
|---|---|---|
| works | 90 | 2 |
| mentions | 219 | 2 |
| authorpapers | 219 | 2 |
| pairs | 186 | 2 |
| topics | 44 | 2 |
| institutions | 37 | 2 |
| venues | 1 | 1 |
| decisions | 347 | 2 |

### Run manifest and run ID

`SparkRunManifest` (SR3) holds the stage, `LogicVersion` "spark-v2" (was "spark-v1"; the
repair bumped it because the logic changed, R1), the snapshot,
both upstream run IDs and folders, the whole `graph` config, the vector index names and
the schemas read and written.
It holds no timestamp and no output path, and its hash is the run ID.
The manifest is written first, and `<runId>-summary.json` last.
The summary holds the status, every invariant, the Spark settings and stage timings.
The run ID of the local rerun is `run-769e0d672ecd3c19` (LR); under spark-v1 it was
`run-4903a4f9eabba786` (LR0).

### Usage and exit codes

```text
SparkPipeline --hadoop-manifest <hadoop out>/run-manifests/<runId>.json \
  --er-manifest <er out>/run-manifests/<runId>.json \
  --output <dir> [--checkpoint-dir <dir>]
```

Parsing is strict: an unknown, repeated or valueless name is an error (SR3,
`SparkArgs.Usage`).
`--checkpoint-dir` is required for any master other than `local` or `local[...]`:
`SparkArgs.checkpointRequirement` refuses the start and `SparkPipeline.main` exits 2 with
a usage error (R1, MINOR-2).
Reason: EMR turns on dynamic allocation, and `localCheckpoint` is unsafe when
executors are removed (RV).
The Phase 12 run script (infra-engineer) must pass the flag.

| Exit code | Meaning |
|---|---|
| 0 | done, every invariant is 0 |
| 2 | usage, config or upstream error; nothing is written |
| 3 | outputs written but an invariant failed; status INVARIANT_FAILURES, so loaders must not load the run |

### Local run command

Copied from R1 (exit 0, sbt `Total time: 115 s`); the step 03 run used the same command
with the capture directory `modules/spark/target/local-run` (LR0, 175 s).
Paths are shown relative; the reports used absolute paths under the repository.

```text
sbt "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun \
  --capture <dir> \
  --hadoop-manifest target/er-source-run/run-manifests/run-6d1175dbfdb874a9.json \
  --er-manifest target/er-match-run/run-manifests/run-02cecabcf3e68312.json \
  --output target/spark-local-run"
```

The rerun used `modules/spark/target/local-run-r1` as the capture directory, so the
step 03 capture stays intact (R1).
The output folder `target/spark-local-run` was reused, so it holds both runs, each in its
own `run_id=` folder (LR).
The local run uses Spark 4.0.1, master local[2], 4 shuffle partitions, AQE on and the UI
off (LR).
`LocalPipelineRun` lives in `tests/module-unit/spark/` and is run through `Test/runMain`,
so no build change was needed (SR3).
Its listener records tasks, shuffle read and write per stage, and the formatted physical
plan per SQL execution.
The captured output folders under `target/` are git-ignored build output.

## Identity policy and the contradiction cut (job 1)

Source: R1, with the policy text in the object doc of `Canonical.scala`.
The finding that led to it is MAJOR-1 of RV: under the first version, UNRESOLVED and
CONFLICT ER pairs were merged transitively through chains of accepted links.
On the sample, 3 of the 5 UNRESOLVED researcher pairs ended up in one canonical
researcher (RV).

Policy decision: the orchestrator routed policy (a), "split a cluster on a contradicting
decision", because the human addendum says "Unresolved and conflict decisions are
preserved, never merged".
The human may overrule it at the gate and choose policy (b), keep transitive merges and
disclose them.

The rule is a deterministic constrained single linkage (R1):

1. Build the connected components of the ACCEPTED links, bounded by
   `componentMaxIterations`.
2. A cluster is contradicted if it holds both ends of an UNRESOLVED, CONFLICT or REJECTED
   decision, or two ORCID iDs.
3. Rebuild each contradicted cluster from its own accepted links in descending
   confidence; ties are broken by the ordered endpoint IDs.
   A null confidence counts as 0, the weakest.
4. A link is applied unless it would put a contradicting pair, or two ORCID iDs, into one
   cluster; otherwise it is refused.

Effect: when one path joins a contradicting pair, exactly the weakest link on that path is
refused.
Weak links that do not lie between contradicting nodes are kept, and uncontradicted
clusters do not change.
The same cut applies to work decisions.
Each contradicted cluster is one row, rebuilt by a Java UDF2 (`IdentityCut`) on the
executor that holds it; there is no iteration loop and nothing is collected to the driver.

Counters, for researchers and works (`<kind>` is `researcher` or `work`; R1):

- `<kind>DecisionLinksAccepted`, `...DroppedUnknownEndpoint`, `...RefusedByCut`,
  `...Applied` (the links actually applied) and `<kind>ClustersCut`;
- per outcome O in UNRESOLVED, CONFLICT, REJECTED: `<kind>Contradictions_O`,
  `<kind>ContradictionsInOneClusterBeforeCut_O` and `<kind>ContradictionsKeptApart_O`
  (names checked in `Canonical.scala:207-210`);
- researchers only: `researcherClustersWithSeveralOrcidsBeforeCut`;
- `canonicalWorksUnresolved`.

Invariants (a nonzero value fails the run, exit 3):

- `invariant_<kind>ContradictionsMerged_O`: a contradicting pair left in one cluster;
- `invariant_researcherClustersWithSeveralOrcids`;
- non-convergence, a flag per iterative algorithm that did not reach a fixpoint within
  its bound, 0 or 1 (R1, MINOR-1): `invariant_researcherClusterNotConverged`,
  `invariant_workClusterNotConverged`, `invariant_componentsNotConverged` and
  `invariant_corenessNotConverged` (per window).
  Before the repair a cut-off run reported SUCCEEDED.
  Label propagation stays a reported flag, `communitiesConverged`, because its
  oscillation is documented.
  The old flag names `componentsConverged`, `corenessConverged` and
  `<kind>ClusterConverged` no longer exist.

The `identityConflict` flag changed meaning (R1): it now means that the cut refused an
accepted link touching a member.
The old meaning, a contradiction inside a cluster, can no longer happen and is the
invariant above.
`unresolved` is unchanged: an UNRESOLVED decision touches a member.
The summary text reads "identity conflict flagged (an accepted identity link was
refused)".

Effect on the sample (LR, researchers): 267 accepted links, 0 dropped, 3 refused, 264
applied, 2 clusters cut.
Of 5 UNRESOLVED decisions, 3 were inside one cluster before the cut, 5 are kept apart and
0 are merged.
There was no CONFLICT or REJECTED researcher decision and no cluster with several ORCID
iDs.
For works, 71 links were accepted and applied, 0 refused; the 4 CONFLICT decisions were
all kept apart.
The result is 81 researchers (79 before), 6 unresolved (4 before) and 4 with
`identityConflict` (0 before) (LR, LR0).

Limits (R1):

- Greedy, not optimal: with several contradictions the rule can refuse more links than a
  minimum cut would.
- No config bound on the size of a contradicted cluster: its links sit in one row on one
  executor, so memory grows with the largest contradicted cluster.
  No core config field caps it.
  A field from domain-engineer is needed before a full-DBLP run.
- `IdentityCut` and the reject mapping are Java UDFs, opaque to Catalyst; each runs once
  per contradicted cluster or per reject row, which is small.

Identity provenance (R1, MINOR-4): `Canonical.build` takes the ER policy version
(`up.erPolicyVersion`, er-v1 here), and researcher and work rows carry
`provenance.policyVersion` er-v1.
The Researcher and Work graph nodes keep that policy; other nodes keep graph-v1.
Researcher nodes carry `mentionIds`, a sorted list joined with `|`, like the Work
`memberWorkIds`.

## Partitioning and shuffle boundaries

Every join and groupBy that is not broadcast is a hash shuffle with
`spark.sql.shuffle.partitions` partitions (4 in the local run).
There are no explicit hints (SR2).

- Job 1: identity clustering runs connected components over decision links, then one
  aggregation by cluster label and window functions partitioned by label.
  AUTHORED uses two left joins, one groupBy and windows partitioned by work.
- Job 2: three joins to map pairs, two semi-checks against AUTHORED and one groupBy on
  (a, b, work).
- Edges: one groupBy on (a, b).
  Measures: a groupBy by researcher, a join for the maximum-team works and the adjacency
  groupBy.
- Iterative algorithms, per iteration: an adjacency join on node ID, an aggregation by the
  receiving node and a join back to the state.
  Components, label propagation and k-core run up to their bounds.
  BFS and the betweenness back pass run one level per depth.
- Driver traffic: change counts, the maximum BFS depth, the top-1 largest component row,
  the k sampled source IDs, quantiles, at most 8 decision-count rows and the requested
  path rows.
  The graph is never collected (SR2).

Observed in the local rerun (LR, SparkListener `phases.tsv`, sums over completed stages;
the report lists these six stages only, so the other stages and wall times are not
restated here; the step 03 table is in LR0):

| pipeline stage | Spark stages | tasks | shuffle read bytes | shuffle write bytes |
|---|---|---|---|---|
| job1-canonical | 269 | 824 | 298816 | 298774 |
| job2-pairs | 27 | 70 | 8781 | 8781 |
| window-1950-2026 | 259 | 1160 | 243364 | 233319 |
| paths-1950-2026 | 64 | 261 | 41360 | 41360 |
| job10-summaries | 144 | 511 | 329884 | 307616 |
| job11-export | 68 | 480 | 363106 | 363106 |

The job 1 stage count was 214 in LR0 and is 269 in LR; the reports do not give a cause.
In the step 03 run the two empty windows (2000-2009 and 2015-2026) shuffled 0 bytes
(LR0).
AQE coalesced many shuffle reads to 1 task and broadcast every small side, because every
table in the sample is KiB-sized (LR0).
Task counts above 4 per stage come from unions of several inputs and multi-file scans.
These numbers are for the 8-row-kind sample on a laptop and say nothing about a full-DBLP
run on EMR.
The formatted physical plans are in the git-ignored capture folders
`modules/spark/target/local-run-r1/plans.txt` (rerun) and
`modules/spark/target/local-run/plans.txt` (step 03).

## Checkpointing

Every iteration of an iterative algorithm ends with `Lineage.cut` (SR2).
With a checkpoint directory set, it persists the rows and writes a reliable
`checkpoint(eager=true)`.
Without one, it falls back to `localCheckpoint`.
The local run used reliable checkpoints in `target/spark-local-run/_checkpoints` (LR).
Not measured: the cost of a reliable checkpoint every iteration on EMR (SR2).
The driver sets `spark.cleaner.referenceTracking.cleanCheckpoints` to "true"
(`SparkPipeline.scala:58`, checked by grep; review 1 NIT-2 cited line 50 for the same
setting).
A cluster run must pass `--checkpoint-dir` (see Usage and exit codes).
Job 10 builds the 16 document plans (4 kinds, 4 windows) as one checkpointed union plan
of 2532 nodes (execution 409), which adds planning time at scale (SR3, LR).

## Bridge measures

Definitions and limits (SR2).
Measures 1 to 3 are exact and unweighted (distinct coauthors), and they inherit the
quality of the label propagation partition.

| Measure | Definition | Limit |
|---|---|---|
| `crossCommunityDegree` | coauthors outside the researcher's own community | exact, unweighted |
| `communitiesConnected` | distinct communities touched directly, own included | 1 for an interior or isolated researcher |
| `participationCoefficient` | P_i = 1 - sum over c of (k_ic / k_i)^2 (Guimera and Amaral) | 0 when there is no coauthor |
| `bridgeScore` | sampled-source Brandes betweenness (Brandes 2001; Brandes and Pich 2007) | approximate, unweighted (hops) |

Approximation parameters, from the `graph` config block (SR2):

- `betweennessSampleSources`: 16 sources.
  They are the researchers with at least one coauthor having the smallest SHA-256 of
  `id|betweennessSeed`, so the choice is deterministic.
- `betweennessSeed`: 441.
- Scaling: the sum is multiplied by population divided by k, halved, and divided by
  (n-1)(n-2)/2.
- The score is exact when the sample is the whole population.
  Otherwise it is an unbiased estimate, and a single value can exceed 1.
  Known-graph check: a sampled estimate on a path gave 1.5 against an exact 1.0.
- The BFS depth is bounded by `componentMaxIterations`; a cut-off is reported as
  `betweennessBfsTruncated`.
- Distance statistics (average path length, effective diameter, maximum) cover only the
  components that hold a sampled source.

Other graph config meanings (SR2):

- `kcoreMaxK` caps the reported coreness (the fixpoint is min(core, K)).
- `componentMaxIterations` bounds components, the BFS depth for betweenness and
  distances, and the k-core iterations.
- `representativeWorkLimit` limits `representativeWorkIds` (most recent first) and
  `maximumTeamWorkIds`; `maximumTeamWorkCount` shows any truncation.
- `maxTeamSizeForPairs` is reapplied on canonical teams.
  T_r still counts works above the cap; C_r and W_r do not.
- `pathMaxDepth` bounds the path search.
- Path evidence pairs: every pair of the window's sampled betweenness sources, up to
  `pathMaxDepth` hops.
  No new parameter was added, because the config has no benchmark-pair setting (SR3).

Community detection (SR2): label propagation with a self vote equal to the node's
strongest edge weight.
A fixed self vote oscillated forever on weighted edges, which a first test run showed.
Synchronous label propagation can still oscillate on other structures; it is bounded by
`communityIterations` and reported through `communitiesConverged`.

## Windows and temporal comparison

The configured windows are 1950-2026, 2000-2009, 2010-2014 and 2015-2026
(`scholarchain-v1.yaml`, confirmed in SR2 and LR).
Job 9 compares 2000-2009 to 2010-2014 and 2010-2014 to 2015-2026.
It skips 1950-2026 against 2000-2009, because the windows overlap (LR).
Each comparison states an unequal-length limitation: 10 vs 5 years, and 5 vs 12 years
(LR).

Limits observed in the local rerun (LR):

- Every sample work is from 2014, so 1950-2026 and 2010-2014 hold the same graph and
  2000-2009 and 2015-2026 are empty.
- The two comparisons therefore compare an empty window with a full one: 81 researchers,
  0 present in both windows, and retention 0.
- There are no cross-community edges, and the participation coefficient is 0 everywhere.
  Cross-community bridges and meaningful temporal change are covered only by the
  known-graph suites of SR2.

Windows in the local rerun (LR; the empty windows 2000-2009 and 2015-2026 have 0 in every
column, as in LR0):

| window | researchers | edges | components | largest (fraction) | communities | max coreness | path pairs found / requested |
|---|---|---|---|---|---|---|---|
| 1950-2026 | 81 | 78 | 29 | 6 (0.0741) | 29 | 4 | 4 / 120 |
| 2010-2014 | 81 | 78 | 29 | 6 (0.0741) | 29 | 4 | 4 / 120 |

In the non-empty windows, components converged in 3 iterations, label propagation in 3
and coreness in 2, and both new not-converged invariants are 0 in all 4 windows (LR).
Sampled distances: 38 sampled pairs, average path length 1.0789, effective diameter 1 and
maximum distance 3.
All 4 found path pairs are at distance 1; the other 116 of 120 were not reported as
found (LR; LR0 said "not connected within 6 hops").
The job 1 clustering numbers are 219 mentions into 81 researchers (52 merged, 33 with
ORCID) and 90 source works into 42 canonical works (25 merged) (LR).
Step 03 had 79 researchers, largest component 5 and 77 invariants (LR0).

## Contract mapping onto core records

Tables with a core record use it, and the rest use the committed `EvidenceItem` shape
(evidence-v1), each with the evidence type made for it (SR3).
No new core record was needed, so nothing is BLOCKED-ON domain-engineer.

| Output | Core record |
|---|---|
| graph nodes, graph edges | `GraphNode`, `GraphEdge` (edge IDs from core `Ids.graphEdge`) |
| coauthor edges, researcher metrics | the core shape plus visible extra columns (`maximumTeamWorkCount`, `inLargestComponent`, `supportingWorkIds`, `windowLabel`) |
| components | `EvidenceItem` COMPONENT_RECORD, sampled distance statistics in `values` |
| communities and cross-community edges | `EvidenceItem` COMMUNITY_RECORD, partition `recordKind` |
| path evidence | `EvidenceItem` GRAPH_PATH, `graphPath` is the researcher IDs, hop edge IDs in `values` |
| temporal comparisons | `EvidenceItem` METRIC_RECORD |
| semantic summaries | `EvidenceItem` SEMANTIC_PROFILE |
| rejects | reject-record (core reject shape) |

`EvidenceItem.score` is 0.0 at export; it is a retrieval-time field (SR3).
Job 10 documents come from fixed templates filled only from computed tables and source
records; no model is called.
`chunkId` follows core `Ids.chunk(entity, metricVersion, docType, 0, text)`, `textHash` is
`sha256:` plus the hex digest, and the vector index name comes from `vectors.indexes`.
Every document is chunk 0; splitting long texts is left to the embedding step (SR3).

## Output tables and load manifests

Tables are written under
`<output>/spark/snapshot=<date>/run_id=<runId>/<table>/` as partitioned Parquet, and read
back to count rows and files (SR3).
Local rerun counts (LR; partition columns from LR0, unchanged by the repair as far as the
reports say):

| table (record) | rows | files | partitions |
|---|---|---|---|
| graph-nodes (graph-node-v1) | 205 | 5 | label |
| graph-edges (graph-edge-v1) | 409 | 5 | edgeType |
| coauthor-edges (coauthor-edge-v1) | 156 | 2 | windowLabel |
| researcher-metrics (researcher-metrics-v1) | 162 | 2 | windowLabel |
| components (evidence-v1) | 58 | 2 | windowLabel |
| communities (evidence-v1) | 58 | 2 | windowLabel, recordKind |
| path-evidence (evidence-v1) | 240 | 2 | windowLabel |
| metric-comparisons (evidence-v1) | 162 | 2 | comparison |
| semantic-summaries (evidence-v1) | 316 | 8 | docType, windowLabel |
| rejects (reject-record) | 0 | 0 | stage |

Graph: 205 nodes (Researcher 81, Work 42, Topic 44, Institution 37, Venue 1) and 409 edges
(AUTHORED 106, COAUTHORED_WITH 156, HAS_TOPIC 75, PUBLISHED_IN 25, AFFILIATED_WITH 47),
with 0 duplicate IDs and 0 missing endpoints (LR).
Documents: researcher-profile 162, collaboration-community 58, collaboration-path 8,
topic-institution-summary 88, 316 in all, with 0 duplicate chunk IDs and 0 empty texts
(LR).
The rejects table is written by applying core `RejectRecord.of` to each row through a
UDF, so the truncation lengths live only in core (R1, NIT-4).
A null reason or input stays null.

Four load manifests are written to `.../run_id=<runId>/load-manifests/` (LR, SR3):

| Manifest | Content in the local rerun |
|---|---|
| `neptune.json` | Parquet in the GraphNode and GraphEdge shape; nodes 205, edges 409; `coauthoredWith` block |
| `dynamodb.json` | `dynamo.researcherMetricTable`, key researcherId plus windowLabel, 162 rows |
| `s3vectors.json` | dimension 256; 4 indexes (162, 58, 8 and 88 documents) |
| `s3.json` | all 10 tables, plus the run manifest and summary |

The targets are named by config keys (`dynamo.researcherMetricTable`,
`vectors.vectorBucket`, `vectors.indexes.*`, `storage.graphBucket`), not by values.
A loader must not load a run whose summary status is INVARIANT_FAILURES.

### COAUTHORED_WITH is window-scoped (public API for graph queries)

Decision (R1, MINOR-3, option 2): there is one COAUTHORED_WITH edge per researcher pair
per window, and the window filter is public API.
Reasons given in R1: the spec defines the coauthor edge ID as a hash that includes the
graph window (spec line 1092), requires the edge to store its time window (line 1375), and
window-scoped graph and path queries (line 2590) need the per-window graph in Neptune.
"Duplicate researcher pairs consolidated" (spec line 664) holds within each window.

Observed: 156 COAUTHORED_WITH edges are 78 pairs times 2 windows, because 1950-2026 and
2010-2014 hold the same graph (LR; LR0 had 152 = 76 times 2).
On full data a pair can have up to 2 parallel edges, the cumulative window plus one
period window (RV).

Rule for storage-engineer and service-engineer: every query over COAUTHORED_WITH, in
Neptune, in the Parquet tables and in services, must filter on exactly one `window`
value.
A query that does not filter double counts collaborators and degree (RV).
The Neptune load manifest has a `coauthoredWith` block that states this: scope "one edge
per researcher pair per window", `windowProperty` "window", the configured window labels
(1950-2026, 2000-2009, 2010-2014, 2015-2026) and the rule (LR, R1).
The Parquet table carries the label as `windowLabel`.

## Known limits and not done

- Bounded EMR Spark run: NOT RUN.
  Cloud work starts in Phase 12 (SR3, LR).
  Spark on `emr-spark-8.0.0` and the Scala 3 link against scala-library are unverified
  until then (SR1).
- No paper summaries: only topic summaries exist, one per topic per window.
  The `abstracts` vector index is empty in the local run (SR3).
- No summary limits: the `graph` config block has none, so no document count or list
  length is cut.
  A config field from domain-engineer is needed before a full-DBLP run (SR3).
- Neptune bulk-load CSV conversion belongs to storage-engineer; the manifest points at
  Parquet only (SR3).
- Scale notes, unmeasured (SR2, SR3): hash-min components and label propagation send
  messages over every edge in every iteration; community member lists in summaries grow
  with community size; `supportingWorkIds` is unbounded per edge; every window recomputes
  the sampled sources for path evidence (a cheap top-k).
- Identity cut (R1): greedy, not optimal, and no config bound on the size of a
  contradicted cluster (see the identity section above).
- Team size counts unmerged mentions, so it can be inflated.
  The report counts this as `worksWithTeamAboveEverySourceList`, which is 3 in the local
  rerun (1 in LR0) (R1).
- The sample is small (205 nodes, 409 edges) and shows little structure, so no number
  above predicts full-DBLP behavior.
- Jar: scala-library is excluded from the Spark job jar (see MODULE.md).
  R1-2 rebuilt the jar on the final tree at 15:12:16 with `sbt sparkAnalytics/assembly`
  (4,071,345 bytes, `Main-Class: scholarchain.spark.SparkPipeline`) and found 0 entries
  under `scala/collection/`, 0 `ScalaRunTime` entries and 377 `upickle/` entries (RB).
  Absence is still checked by those markers only, and no test covers the exclusion.
  `sbt packageJobs` itself was not run.
