HARNESS: claude-code
MODEL: Claude Code opus, effort high (high tier, `.agents/models.json`; the role default, not changed by the work order)

Summary: Jobs 1 to 9 are built and `sbt sparkAnalytics/test` passes 20 of 20 on the final files. Nothing was collected to the driver except counts, one top-1 row and the requested path rows. Two things to know first:
- **I deliberately did not obey the stop hook.** The stop hook `verify-tests-ran.sh` fired 4 times asking for the full `sbt check`. Your work order forbids that in this step (the full check runs once at the end of step 03). So I did not run it, and `.claude/.last-test-run` is not refreshed.
- **I changed one algorithm against the reference.** Label propagation as written oscillates forever on weighted edges. The node's own vote now weighs as much as its strongest edge (details under ASSUMPTIONS).

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 06 step 02, task T1. Jobs 1 to 9 of the Spark work order, adapted from ~/ref-phase-06, with known-graph test suites whose expected values are worked out in the test comments. Only `sbt sparkAnalytics/test`; no full sbt check, no commits, no cloud.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Schemas.scala: explicit read schemas for the J07 export kinds and match-v1 decisions, plus required columns
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Inputs.scala: JSON-lines reader. A line that fails the schema or has a null required field becomes a counted reject (`SCHEMA_MISMATCH`, `REQUIRED_FIELD_NULL`).
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Support.scala: `RunInfo` (runId, snapshot, GraphConfig), `Lineage.cut` (checkpointing), `Cols` (hash, window, provenance helpers), `Report` (run-report counts)
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Canonical.scala: job 1
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/CoauthorGraph.scala: jobs 2 and 3
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/GraphAlgorithms.scala: components, label propagation, k-core, multi-source BFS, source sampling, betweenness, path reconstruction
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Bridges.scala: job 7, community-based bridge measures
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Paths.scala: job 5, path evidence and distance statistics
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/WindowAnalytics.scala: jobs 3 to 8 for one window, the core ResearcherMetrics shape, invariants and quantiles
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Temporal.scala: job 9
- /home/ajcreator/scholarchain/tests/module-unit/spark/LocalSpark.scala: test SparkSession (local[2], 4 shuffle partitions, UI off, checkpoint dir); config loaded with `ConfigLoader.load(Map.empty)`
- /home/ajcreator/scholarchain/tests/module-unit/spark/CanonicalSuite.scala, CollaborationSuite.scala, GraphAlgorithmsSuite.scala, WindowAnalyticsSuite.scala, TemporalSuite.scala
- /home/ajcreator/scholarchain/tests/module-unit/spark/resources/log4j2.properties: Spark test logging at WARN

FILES MODIFIED: none. build.sbt, core, hadoop, entity-resolution and MODULE.md are untouched.

COMMANDS RUN (all observed):
- `source toolchain/env.sh && sbt sparkAnalytics/test` four times. The harness moved each to the background at 180 s; I started nothing else and did not poll.
  - Attempt 1: Failed 7 of 20. The visible failure was the weighted label propagation case: x and b swapped labels each iteration. That was a real algorithm flaw, fixed. My `tail` cut the other suites' output.
  - Attempt 2: Failed 5 of 20.
    - 4 CanonicalSuite tests: `[AMBIGUOUS_REFERENCE] Reference 'year' is ambiguous` at Canonical.scala:376 (workMap join). Fixed by renaming the column.
    - 1 TemporalSuite test: `test timed out after 30 seconds` (munit default). Fixed with a 5-minute `munitTimeout` on the three Spark-heavy suites.
    - Before attempt 3 I also fixed one test bug: it assumed the order of two `sc:r:v1:` hash IDs; it now uses `Ids.orderedPair`.
  - Attempt 3: Passed 20 of 20.
  - Run 4, after scalafmt: Passed 20 of 20, to confirm the final files.
- `scalafmt --config .scalafmt.conf --test` on all 16 Spark files: first it failed (whitespace only). I applied scalafmt, and the re-check printed "All files are formatted with scalafmt :)".

TEST RESULTS (verbatim from modules/spark/target/t1-test4.log, the final run):
```
Test run scholarchain.spark.CanonicalSuite finished: 0 failed, 0 ignored, 5 total 55.131s
Test run scholarchain.spark.CollaborationSuite finished: 0 failed, 0 ignored, 3 total 3.273s
Test run scholarchain.spark.WindowAnalyticsSuite finished: 0 failed, 0 ignored, 4 total 40.129s
Test run scholarchain.spark.TemporalSuite finished: 0 failed, 0 ignored, 2 total 36.976s
Test run scholarchain.spark.GraphAlgorithmsSuite finished: 0 failed, 0 ignored, 6 total 34.92s
[info] Passed: Total 20, Failed 0, Errors 0, Passed 20
[success] Total time: 200 s (0:03:20.0), completed Nov 23, 2026, 12:46:14 PM
```
The forked JVM options from step 01 (module opens, -Xmx1g) worked; this is their first use.

Gate items and the tests that cover them (each fixture is hand-checkable and the expected values are worked out in the comments):

| Gate item | Covered by |
|---|---|
| Canonical tables and integrity (job 1) | CanonicalSuite: 4-source fixture with accepted, unresolved and conflict decisions, an ORCID link and an unknown endpoint. Expects 4 researchers and 3 canonical works, 6 AUTHORED edges, 2 rejected author-paper rows, 0 integrity violations. |
| Pair weights and distinct coauthors | CollaborationSuite: r1 has P=3, C=13, T=14, A=14/3, M=11 on w3, W=14. The r1-r2 edge has weight 2 (2015 to 2016). The r9-s1 edge has weight 7, 5 representative works and maximumTeamWorkCount 7. CanonicalSuite: a pair reported by two sources counts once, and self pairs are dropped. |
| Explicit windows | CollaborationSuite (2015-2016, 2013-2014, 2017-2026) and TemporalSuite (the configured windows) |
| Components | GraphAlgorithmsSuite labels and the cut-off case; WindowAnalyticsSuite: 3 components, largest 7, fraction 0.7, 1 singleton |
| Community assignment | GraphAlgorithmsSuite: iteration trace on G and a case where weights decide the outcome; WindowAnalyticsSuite: sizes, internal edges and the cross-community edge n4-n5 |
| Path queries | GraphAlgorithmsSuite: n1 to n7 is n1,n3,n4,n5,n7; ties (sigma 2); depth bound; unreachable pair. WindowAnalyticsSuite: hop edge IDs equal `Ids.coauthorEdge`; distance average 92/42, effective diameter 4. |
| k-core | G gives 2,2,2,2,2,2,2,1,1,0; a K4 with a pendant gives 3/1; with the cap at 2 it gives 2/1 |
| Bridge measures | Exact betweenness n4 = 9/36, n3 = n5 = 8/36; 4-cycle 0.5/3 each; the sampled estimate on a path gives 1.5 against an exact 1.0; deterministic source sampling; n4 has participation 0.5, n5 has 4/9 |

VALIDATION RESULTS:
- Not done in T1, by the task split: stage DAGs, shuffle metrics, real graph, component and community counts, and output paths. These come from the T2 local integration run.
- Every count and invariant failure is written to a `ujson.Obj` report for the T2 run report:
  - Job 1: decision counts, links accepted, applied and dropped, merges, unresolved and conflict counts, AUTHORED rejects by reason, `invariant_authoredMissingResearcher` and `invariant_authoredMissingWork`, dangling topic, venue and institution references.
  - Job 2: self pairs, team-cap refusals, pair rejects by reason, `invariant_pairWorksUnordered`.
  - Per window: 14 invariants, all 0 in WindowAnalyticsSuite. Examples are C_r<=W_r<=T_r, M_r<=T_r, ordered edges, endpoints present, years inside the window, component and community sizes summing to n, and cross-community edge ends matching.
  - Per window: convergence flags and p50, p90 and p99 quantiles of 8 measures.
- Windows come from config: 1950-2026, 2000-2009, 2010-2014, 2015-2026. Temporal compares 2000-2009 to 2010-2014 and 2010-2014 to 2015-2026, each with an unequal-length limitation. It skips 1950-2026 against 2000-2009 because they overlap.

Bridge measures (definitions and limits, also in code docs):
1. `crossCommunityDegree`: the number of coauthors outside the researcher's own community.
2. `communitiesConnected`: the number of distinct communities the researcher touches directly, its own included. It is 1 for an interior or isolated researcher.
3. `participationCoefficient`: P_i = 1 - sum_c (k_ic/k_i)^2 (Guimera and Amaral). It is 0 when the researcher has no coauthor.
   - Measures 1 to 3 are exact and unweighted (distinct coauthors).
   - They inherit the quality of the label propagation partition.
4. `bridgeScore`: sampled-source Brandes betweenness (Brandes 2001; Brandes and Pich 2007).
   - Parameters: `betweennessSampleSources` = 16 sources, chosen deterministically as the smallest SHA-256 of `id|betweennessSeed` (seed 441) among researchers with at least one coauthor.
   - Scaling: the sum is multiplied by population/k, halved, and divided by (n-1)(n-2)/2.
   - It is exact when the sample is the whole population; otherwise it is an unbiased estimate, and a single value can exceed 1.
   - It is unweighted (hops). The BFS depth is bounded by `componentMaxIterations`; a cut-off is reported as `betweennessBfsTruncated`.

Partitioning and shuffle boundaries:
- Every join and groupBy is a hash shuffle with `spark.sql.shuffle.partitions` partitions (4 in tests). AQE may coalesce partitions or broadcast small sides. There are no explicit hints.
- Job 1:
  - Identity clustering (researchers and works) runs connected components over decision links.
  - Then one aggregation by cluster label and window functions partitioned by label (representative work, display name).
  - AUTHORED: two left joins (mention, work), one groupBy (researcher, work), and windows partitioned by work (team size, canonical position).
- Job 2: three joins to map pairs, two semi-checks against AUTHORED, and one groupBy (a, b, work).
- Edges: one groupBy (a, b). Measures: a groupBy by researcher, a join for the maximum-team works, and the adjacency groupBy.
- Iterative algorithms, per iteration: an adjacency join on node ID, an aggregation by the receiving node, and a join back to the state.
  - Components, label propagation and k-core run up to their iteration bounds.
  - BFS runs one level per depth; the betweenness back-pass runs one level per depth.
- Checkpointing: every iteration ends with `Lineage.cut`.
  - With a checkpoint directory set (the tests set one, so this path is tested), it persists the rows and writes a reliable `checkpoint(eager=true)`.
  - Without one it falls back to `localCheckpoint`.
- Driver traffic: per-iteration change counts, the maximum BFS depth, the top-1 largest component row, the sampled source IDs (k rows), quantiles, the decision-count and policy-version rows (at most 8), and the requested path rows. The graph is never collected.

ASSUMPTIONS:
1. Canonical works:
   - The export holds one row per source copy of a paper (DBLP, Crossref, OpenAlex). ACCEPTED WORK decisions merge them; otherwise jointPublicationCount would be up to 3 times too high.
   - The representative member is chosen DBLP first, then Crossref, then OpenAlex, then any other source, ties by the smallest work ID; its ID becomes the canonical work ID.
2. Identity:
   - UNRESOLVED, CONFLICT and REJECTED decisions never link anything; they only set the `unresolved` and `identityConflict` flags.
   - `identityConfidence` is the minimum accepted link confidence in the cluster, or 1.0 when no merge was made. Conflicts are flagged, not folded into the confidence.
3. Team size:
   - teamSize is the number of distinct researchers on a canonical work, so unmerged mentions inflate it. The report counts this as `worksWithTeamAboveEverySourceList`.
   - Position is the canonical position, which always satisfies 0 <= position < teamSize.
4. Team cap: `maxTeamSizeForPairs` is reapplied on canonical teams. T_r still counts works above the cap; C_r and W_r do not.
5. Config field meanings:
   - `kcoreMaxK` caps the reported coreness (the fixpoint is min(core, K)).
   - `componentMaxIterations` bounds components, the BFS depth for betweenness and distance statistics, and the k-core iterations.
   - `representativeWorkLimit` limits both `representativeWorkIds` (most recent first) and `maximumTeamWorkIds`. `maximumTeamWorkCount` shows any truncation.
6. Label propagation self vote: it equals the node's strongest edge weight. A fixed self vote of 1 (and the reference, which has none) oscillates forever on a weighted edge; attempt 1 showed this.
7. No core contract or config field was missing for T1, so nothing is BLOCKED-ON domain-engineer.

RISKS:
- Not done (T2): the driver and argument parsing, locating inputs through the run manifests, jobs 10 and 11, the Spark run manifest and run ID, the local integration run, `output-spark-local-run.md`, and the full sbt check. The EMR run is NOT RUN (Phase 12).
- Contract gap for T2:
  - Components, communities, cross-community edges, path evidence, distance statistics and temporal comparisons have no core record. Their provenance `schemaVersion` is null.
  - T2 must map them onto existing core records (EvidenceItem COMPONENT_RECORD, COMMUNITY_RECORD or GRAPH_PATH; GraphNode or GraphEdge), or report BLOCKED-ON domain-engineer for new schema versions.
- Hook conflict: the full `sbt check` was not run, as ordered, so `.claude/.last-test-run` is stale and the stop hook fired 4 times.
- Scale:
  - Hash-min components and synchronous label propagation send messages over every edge in every iteration.
  - The reliable checkpoint writes every iteration; this has not been measured on EMR.
  - The T2 driver should set `spark.cleaner.referenceTracking.cleanCheckpoints`.
  - `supportingWorkIds` holds every joint work and is unbounded per edge.
- Synchronous label propagation can still oscillate on other structures; it is bounded by `communityIterations` and reported through `communitiesConverged`.
- Distance statistics cover only components that hold a sampled source.
- State left behind: untracked new files only (listed above); test logs `modules/spark/target/t1-test3.log` and `t1-test4.log`; test checkpoints in the system temp directory.
- Notes for `modules/spark/MODULE.md` (documentation-engineer):
  - Public API: `Canonical.build`, `CoauthorGraph.researcherPairs`, `CoauthorGraph.edges`, `CoauthorGraph.metrics`, `WindowAnalytics.run`, `Paths.evidence`, `Paths.distanceStats`, `Temporal.plan`, `Temporal.compare`, `Bridges.measures`, the `GraphAlgorithms.*` functions, `Inputs` and `Schemas`.
  - Known issues: the items above.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 02s (documentation-engineer updates STATUS.md), then step 03 (spark-engineer T2: jobs 10 and 11, run manifest, local integration run, one full sbt check).
