# Module `spark`

## Purpose

Temporal weighted coauthorship graph construction and distributed graph analytics.
The analytics are degrees, components, distances, communities, bridge scores, k-core,
trends and evidence paths.
The module reads only the Phase 04 J07 export and the Phase 05 er-v1 decisions, through
their run manifests, and writes partitioned Parquet tables and load manifests.
Job detail and every observed number: [spark-jobs](../../docs/spark-jobs.md).

## Owner and paths

- Owner: spark-engineer (documentation-engineer owns only this file).
- sbt project: `sparkAnalytics`; folder: `modules/spark`.
- Source: `modules/spark/src/main/scala/scholarchain/spark/` (14 files).
- Unit-test root: `tests/module-unit/spark/` (never `src/test`).

## Public API

Package `scholarchain.spark`.
Names are taken from the Phase 06 reports and checked against the files under `src/main`.
All code is DataFrame and SQL functions; there are no Dataset encoders.
Two Java UDFs exist since repair R1-1: `IdentityCut` (the identity contradiction cut) and
the reject mapping `SparkPipeline.coreReject`.

- Driver: `SparkPipeline.main`,
  `SparkPipeline.run(spark, SparkArgs, ScholarChainConfig): Either[String, Outcome]`,
  `SparkPipeline.StageProperty` (local property `scholarchain.stage`) and
  `SparkPipeline.invariants`.
- Arguments and inputs: `SparkArgs.parse`, `SparkArgs.Usage`, `Upstream.locate`,
  `Inputs` (JSON-lines reader with counted rejects) and `Schemas` (explicit read schemas).
- Run manifest: `SparkRunManifest.build`, `SparkRunManifest.runId` and
  `SparkRunManifest.LogicVersion` ("spark-v5" since Phase 11; earlier versions are in the
  sections below).
- Upstream pins (Phase 11, C20): `SparkRunManifest`'s `Upstream.HadoopPipelines` accepts
  `hadoop-v2` and `hadoop-v3`.
- Phase 11 (C12, C14): `Canonical.constrainedClusters` takes `maxClusterLinks: Int`
  after the maximum-iterations argument; new `Export.CommunitiesJson`; `dynamodb.json`
  has a new top-level `communities` block (details under "Phase 11 additions").
- Identity (job 1, repair R1-1): `Canonical.build(src, run, identityPolicy)` takes the ER
  policy version, `Canonical.constrainedClusters`, `Canonical.Clusters`,
  `Canonical.ContradictingOutcomes`, `IdentityCut.cut` (pure step) and
  `IdentityCut.udfFor`.
- Jobs 1 to 9: `Canonical.build`, `CoauthorGraph.researcherPairs`, `CoauthorGraph.edges`,
  `CoauthorGraph.metrics`, `WindowAnalytics.run`, `Paths.evidence`, `Paths.distanceStats`,
  `Bridges.measures`, `Temporal.plan`, `Temporal.compare` and the `GraphAlgorithms.*`
  functions (components, label propagation, k-core, multi-source BFS, source sampling,
  betweenness, path reconstruction).
- Jobs 10 and 11: `Summaries.*`, `Export.*` (graph node and edge builders, table writer,
  `loadManifests`, which now takes the window labels) and `Evidence.*` (core EvidenceItem
  columns).
- Support: `RunInfo`, `Lineage.cut` (checkpointing), `Cols` and `Report`.
- Arguments: `SparkArgs.checkpointRequirement(master, args)`.
  `--checkpoint-dir` is required for any master other than `local` or `local[...]`;
  `SparkPipeline.main` exits 2 with a usage error otherwise.
  The Phase 12 run script (infra-engineer) must pass it, because `localCheckpoint` is
  unsafe under EMR dynamic allocation.
- Researcher and Work rows and graph nodes carry `provenance.policyVersion` er-v1 (the ER
  policy of the run); other nodes keep graph-v1.
  Researcher nodes have a `mentionIds` property, a sorted list joined with `|`.

### Graph query contract: COAUTHORED_WITH is window-scoped

For storage-engineer and service-engineer.
There is one COAUTHORED_WITH edge per researcher pair per window (spec lines 1092 and
1375), so overlapping windows give parallel edges for one pair.
Every graph query over COAUTHORED_WITH, in Neptune, in the Parquet tables and in services,
must filter on exactly one `window` value; an unfiltered query double counts.
The Neptune load manifest has a `coauthoredWith` block with the scope, `windowProperty`
"window", the configured window labels and this rule.

### Identity policy (job 1)

- ACCEPTED ER links are clustered, then a deterministic contradiction cut refuses the
  weakest accepted link on a path that joins a contradicting pair: an UNRESOLVED,
  CONFLICT or REJECTED decision, or two ORCID iDs.
  Works use the same cut.
- The orchestrator routed this as policy (a) from the human addendum ("Unresolved and
  conflict decisions are preserved, never merged"); the human may overrule it at the gate
  in favor of policy (b), keep and disclose transitive merges.
- Counters per kind and per outcome, and the invariants
  `invariant_<kind>ContradictionsMerged_O` and
  `invariant_researcherClustersWithSeveralOrcids`, are listed in
  [docs/spark-jobs.md](../../docs/spark-jobs.md).
- Non-convergence is an invariant (0 or 1): `invariant_researcherClusterNotConverged`,
  `invariant_workClusterNotConverged`, `invariant_componentsNotConverged` and
  `invariant_corenessNotConverged`; a nonzero value gives exit 3.
  Label propagation stays the flag `communitiesConverged`.
- `identityConflict` now means that the cut refused an accepted link touching a member.
  A contradiction inside a cluster is the invariant and must be 0.
  `unresolved` is unchanged.

Command line:

```text
SparkPipeline --hadoop-manifest <hadoop out>/run-manifests/<runId>.json \
  --er-manifest <er out>/run-manifests/<runId>.json \
  --output <dir> [--checkpoint-dir <dir>]
```

Exit codes: 0 done with every invariant at 0; 2 usage, config or upstream error with
nothing written; 3 outputs written but an invariant failed (a loader must not load it).

Local run (test scope, `tests/module-unit/spark/LocalPipelineRun.scala`):
`sbt "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture <dir>
--hadoop-manifest <...> --er-manifest <...> --output <dir>"`.

Outputs: `<output>/spark/snapshot=<date>/run_id=<runId>/<table>/` (10 tables), four load
manifests (`neptune`, `dynamodb`, `s3vectors`, `s3`), the run manifest and a summary.
The core records used, and how components, communities, path evidence and temporal
comparisons map onto `EvidenceItem`, are in
[docs/spark-jobs.md](../../docs/spark-jobs.md).

## Done

Source: `docs/agent-runs/phase-06/step-01-build-engineer-report.md`,
`step-02-spark-engineer-report.md`, `agent-report.md`, `repair-01-spark-engineer.md`,
`repair-01-build-engineer.md` and `output-spark-local-run-r1.md`.

- Jobs 1 to 11, the driver, the run manifest and the load manifests are implemented.
- `Test / fork` is on for this project, with the JDK module opens and `-Xmx1g`.
- 33 Spark tests pass (CanonicalSuite 6, CollaborationSuite 3, WindowAnalyticsSuite 5,
  RunManifestSuite 6, TemporalSuite 2, GraphAlgorithmsSuite 6, PipelineSuite 5).
- After repair R1-1 one full `sbt check` exited 0 with core 94, adversarial 68,
  entityResolution 112, Spark 33 and hadoop 98 passed.
- A local rerun on the Phase 04 and Phase 05 sample outputs finished: run
  `run-769e0d672ecd3c19` (logic spark-v2), SUCCEEDED, 94 invariants checked, all 0.
  The graph has 205 nodes and 409 edges, 29 components (largest 6), 29 communities and
  0 rejects, with 81 researchers.
  The contradiction cut refused 3 accepted researcher links.
  The step 03 run `run-4903a4f9eabba786` (spark-v1, 77 invariants, 79 researchers, 203
  nodes, 403 edges) is history only.
- The Spark job jar was rebuilt on the final tree (R1-2, `sbt sparkAnalytics/assembly`,
  15:12:16, 4,071,345 bytes, `Main-Class` `scholarchain.spark.SparkPipeline`), with 0
  `scala/collection/` and 0 `ScalaRunTime` entries and 377 `upickle/` entries.
  The scala-library exclusion is in place (see Known issues).

### Phase 08 additions

Sources, in `docs/agent-runs/phase-08/`: `step-00-spark-engineer-report.md`,
`step-00b-spark-engineer-report.md` and `step-00d-spark-engineer-report.md`.
Run details: [docs/spark-jobs.md](../../docs/spark-jobs.md).

- Logic version `spark-v4` (`SparkRunManifest.LogicVersion`; spark-v3 in step 00).
- New `Abstracts` (`build`, `ExtraFields`, `Stage`, `SchemaVersion`) and the table
  `paper-abstracts`.
- `Export.write` takes an optional `JsonSpec`; new `Export.JsonOut`,
  `Export.JsonLinesExport`, `Export.CoauthorWindows` and `Export.coauthorWindows`.
  `loadManifests` takes `CoauthorWindows`.
  JSON-lines copies of six tables are under `jsonl/` and listed in the load manifests.
- `Upstream.abstractsDir`, `Upstream.OpenAlexStage`, and the `Summaries.PaperAbstract`
  and `Summaries.AbstractIndexKey` constants.
- `Paths.targetsPerSource`, `Paths.reachablePairs` and `Paths.sampledEvidence`.
- New report keys `pathTargetsPerSource` and `invariant_pathRequestedNotFound`.
- `LocalPipelineRun --shuffle-partitions`.
- `institutionId` is in `values.institutionId` of each summary row.
- `neptune.json` lists every coauthor window.
- The path-evidence table holds requested pairs even when none is found.

### Phase 11 additions

Sources, in `docs/agent-runs/phase-11/`: `repair-01-spark-engineer.md`,
`repair-01-spark-engineer-c20.md` and `test-report-03.md`.

- Logic version `spark-v5`, so new run IDs change; the config's new bound field is also
  in the run manifest.
- C12: the bound is inclusive.
  All accepted links of an over-bound contradicted cluster are refused (under-merge is
  preferred to over-merge, and the job does not fail), counted before any
  `collect_list`; members keep their own ID and are flagged `identityConflict`.
  Uncontradicted clusters are not limited.
  New report keys per prefix (researcher, work): `<p>CutMaxClusterLinks`,
  `<p>LargestContradictedClusterLinks`, `<p>ClustersOverCutBound` and
  `<p>DecisionLinksRefusedOverCutBound` (also counted in
  `<p>DecisionLinksRefusedByCut`).
  They are counters, not invariants, so they never cause exit 3.
- C14: `communities` is also written as JSON lines under
  `<run>/jsonl/communities/windowLabel=<w>/recordKind=<kind>/`.
  Each line is a core `EvidenceItem` plus the extra fields `windowLabel` and
  `recordKind`; the kinds are `community` and `cross_community_edge`.
  It is listed in `s3.json` and in the `communities` block of `dynamodb.json`, which has
  no `tableConfigKey`.
- C20: Hadoop `hadoop-v2` and `hadoop-v3` manifests are accepted.
- Spark module-unit tests in the full `sbt check`: 48 passed.
- No local pipeline run, cloud run or Hadoop v3 to Spark run was made.

## Not done

- The bounded EMR Spark run: NOT RUN.
  Cloud work starts in Phase 12, and there is no infrastructure and no approval.
- Paper summaries are not built, only topic summaries; the `abstracts` index is empty.
- No summary limits: the `graph` config block names none, so a config field from
  domain-engineer is needed before a full-DBLP run.
- Conversion of the graph tables to the Neptune bulk-load CSV belongs to storage-engineer.
- `sbt packageJobs` itself was not run; `sbt sparkAnalytics/assembly` was run on the final
  tree in R1-2.
- No automated test covers the scala-library exclusion.

### Phase 08 done

- `sbt sparkAnalytics/test`: 45 passed, 0 failed (33 in Phase 06; 41 after step 00; 4 new
  in `PathSelectionSuite`).
- Run `run-ccf619c509611f06` on the 25,000-work inputs: SUCCEEDED, 114 invariants, 0
  nonzero, 25:14, peak RSS 2,923,636 kB.
  Five indexes have rows: researcher-profile 167,600, community 43,826, path 278, topic
  5,957, abstract 6,292.
- Paper-abstract rows are emitted from the Phase 04 OpenAlex abstracts (6,292); none were
  invented.
- Phase 08 repair R1-3: the `abstracts` `keyRule` text in the `s3vectors.json` load
  manifest now says rag's renderer makes the chunk IDs (`tpl-paper-abstract-v2`, with the
  multi-chunk formula).
  No API change, no computed value changed, and `LogicVersion` stays `spark-v4`.
  A `s3vectors.json` from an earlier run keeps the old text until Spark runs again.

## Known issues

- Runtime target: `emr-spark-8.0.0` (human decision, 2026-11-22).
  Spark 4.0.1 is built for Scala 2.13, the line Scala 3 links against.
  Its availability in us-east-2 is still to be checked before the first cloud run.
- Spark job jar and scala-library (closes Phase 02 carried-forward MINOR-B, with the
  decision as kept by the Phase 06 work order):
  scala3-library_3 3.3.9 depends on scala-library 2.13.18, but the EMR runtime ships
  2.13.16.
  The Spark job jar therefore excludes scala-library (`assembly / assemblyExcludedJars` in
  `build.sbt`, MINOR-2 option (b)) and links against the cluster's copy.
  scala3-library and uPickle are still bundled.
  Step 01 and, on the final tree, R1-2 checked the jar: 0 entries under
  `scala/collection/`, 0 `ScalaRunTime` entries, 377 `upickle/` entries.
  The check looks for absence by those two markers only.
  Residual risk, unverified until the first EMR run: Scala 3.3.9 emits code against
  2.13.18, so a call to a member added after 2.13.16 would throw `NoSuchMethodError`.
  No automated test covers the exclusion (MINOR-D, test-engineer).
  The hadoop and entityResolution jars keep scala-library bundled.
- Identity cut (MAJOR-1 repair): it is greedy, not optimal, so with several
  contradictions it can refuse more links than a minimum cut would.
  The missing config bound on a contradicted cluster is closed in Phase 11 (C12):
  `graph.identityCutMaxClusterLinks` bounds it, and an over-bound cluster is refused (see
  "Phase 11 additions").
  Policy (a) was routed by the orchestrator and the human may overrule it at the gate.
- The convergence flags `componentsConverged`, `corenessConverged` and
  `<kind>ClusterConverged` were replaced by the invariant keys above; no reader of the old
  keys exists in the repository.
- The sample shows little structure: every work is from 2014, so the windows 2000-2009
  and 2015-2026 are empty, the 29 communities each equal a component, there are 0
  cross-community edges and the participation coefficient is 0.
  Temporal comparisons compare an empty window with a full one.
  Cross-community bridges and temporal change are covered only by the known-graph suites.
- Bridge score is a sampled estimate (16 sources, seed 441), unweighted, and can exceed 1.
- Synchronous label propagation can still oscillate on some structures; it is bounded by
  `communityIterations` and reported through `communitiesConverged`.
  The self vote equals the node's strongest edge weight, because a fixed self vote
  oscillated forever on weighted edges.
- Distance statistics cover only components that hold a sampled source.
- Team size counts unmerged mentions, so it can be inflated
  (`worksWithTeamAboveEverySourceList`, 3 in the local rerun, 1 before the repair).
- Scale, unmeasured: the job 10 union is one checkpointed plan of 2532 nodes;
  `supportingWorkIds` is unbounded per edge; community member lists in summaries grow
  with community size; a reliable checkpoint each iteration is untested on EMR.
- The tables for components, communities, path evidence, temporal comparisons and
  summaries use the `EvidenceItem` shape because no dedicated core record exists;
  `score` is 0.0 at export.
- The black-box test classpath has no fork or module opens for a local SparkSession
  (Phase 02 MINOR-A, build-engineer); slf4j-api is bundled in the job jars (N-c).

### Phase 11 known issues

- The EMR run must check `researcherClustersOverCutBound` and the work counterpart for a
  nonzero value; a refused cluster splits into singletons.
- Within-bound clusters still collect their in-cluster contradiction list without a
  bound.
- `PipelineSuite` has no `cross_community_edge` rows (only `ExportSuite` covers them),
  and its fixtures still use `hadoop-v2`.
- No cross-stage test runs real Spark communities output through the storage loader and
  the rag reader.

### Phase 08 known issues

- The 100,000-work local run does not fit 2 GB and 90 minutes (OOM after 23:04, then
  killed at 90 minutes); `run-c4b35c788d05a93c` is not usable.
  About 2.3 GB of git-ignored output remains in
  `datasets/samples/local/2026-11-24/spark-out`.
- Closed on 25,000 works by `run-ccf619c509611f06`: components not converged at 40
  iterations (fixed by core config 100) and no path documents (fixed by spark-v4).
- Still open: the partition count of the checkpointed state grows across iterations
  (stages up to 633 tasks with 8 shuffle partitions), a likely cost at scale, not
  investigated.
- Path documents are a bounded sample of at most 112 per window, not all pairs.
- spark-engineer retried at full scale before the step-down; this is a possible
  deviation from the step-down rule, for the human.

## Dependencies

- Project: `core`.
- Library: `spark-sql` 4.0.1 (Scala 2.13 artifacts) via `CrossVersion.for3Use2_13`,
  Provided.
- Packaged by `packageJobs` into `target/jobs/`.
- A graph library such as GraphFrames is not used; adding one would be a new dependency
  through build-engineer.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
Spark exchanges data with Hadoop and entity resolution only through their run manifests
and S3 folders, never through code imports.
