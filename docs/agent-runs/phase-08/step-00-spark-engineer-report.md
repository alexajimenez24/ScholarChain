HARNESS: claude-code
MODEL: Claude Opus 5.5 (claude-opus-5-5), high tier (role default, as the work order says)

The four Phase 07 gate changes are in place and tested: `sbt sparkAnalytics/test` passed 41 of 41 (33 before, 8 new). The local pipeline run at 100,000 works did not finish. Attempt 1 ran out of memory after 23:04, and attempt 2 hit the 90-minute limit. So there is no usable Spark run yet and no exported table to count. Under the step-down rule the next run is at 25,000 works, which needs a smaller S2 run first: BLOCKED-ON hadoop-engineer.

STATUS: BLOCKED-ON
OWNER: spark-engineer
WORK ORDER: Phase 08 step 00. Implement the Phase 07 gate decisions in modules/spark: (1) a JSON-lines copy next to the Parquet for every table the storage loaders read, listed in the load manifests; (2) `institutionId` in the semantic summaries; (3) paper-abstract rows from the Phase 04 abstracts, with provenance; (4) every exported coauthor window in `neptune.json`. Add unit tests, run `sbt sparkAnalytics/test`, then run the local pipeline on Hadoop run run-834f71c82961a194 and ER run run-85fd79957d197ffc.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Abstracts.scala
- /home/ajcreator/scholarchain/tests/module-unit/spark/ExportSuite.scala

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Export.scala
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/RunManifest.scala
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Schemas.scala
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/SparkPipeline.scala
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Summaries.scala
- /home/ajcreator/scholarchain/tests/module-unit/spark/PipelineSuite.scala
- /home/ajcreator/scholarchain/tests/module-unit/spark/RunManifestSuite.scala
- /home/ajcreator/scholarchain/tests/module-unit/spark/LocalPipelineRun.scala (adds optional `--shuffle-partitions <n>`, default 4, so behavior is unchanged)
- Nothing outside modules/spark/** and tests/module-unit/spark/**. MODULE.md was not touched. Formatting was done only with `sparkAnalytics/scalafmt` and `sparkAnalytics/Test/scalafmt`; `git status` shows no other file changed by me.

COMMANDS RUN (each with its observed result):
- `sbt -batch "sparkAnalytics/Test/compile"`: success.
- `sbt -batch "sparkAnalytics/scalafmt" "sparkAnalytics/Test/scalafmt" "sparkAnalytics/test"`: rc 0, Passed: Total 41, Failed 0, 256 s.
- Attempt 1:
  - Command: `/usr/bin/time -v timeout 5400 sbt -batch 'set sparkAnalytics / Test / javaOptions := (sparkAnalytics / Test / javaOptions).value.filterNot(_.startsWith("-Xmx")) :+ "-Xmx2g"' "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture <scratch>/capture --hadoop-manifest <abs>/hadoop-out/run-manifests/run-834f71c82961a194.json --er-manifest <abs>/er-out/run-manifests/run-85fd79957d197ffc.json --output <abs>/spark-out"`
  - Result: FAILED, rc 1, runner exit 52, `java.lang.OutOfMemoryError: Java heap space`.
- Attempt 2: the same command plus `--shuffle-partitions 32`. Result: FAILED, killed by `timeout` at 90 minutes (rc 124).
- `sbt -batch "sparkAnalytics/scalafmtCheck" "sparkAnalytics/Test/scalafmt" "sparkAnalytics/test"` (after the runner edit): rc 0, Passed: Total 41, Failed 0, 235 s.
- `git check-ignore -v datasets/samples/local/2026-11-24/spark-out`: ignored through `datasets/samples/local/.gitignore:1:*`.
- Also `git status`, `git diff --stat`, `ps`, `free -m`, `df -h`, `du`, `ls` and `grep` over the logs and output.
- Not run: `sbt check`. The work order and addendum keep the full check for step 3; the stop hook asked for it each turn, which is the known Phase 11 false positive. No mutation testing, no AWS call.

TEST RESULTS:
- `sbt sparkAnalytics/test`, run twice, both 41 passed, 0 failed, 0 ignored:

  | Suite | Tests | New in this step |
  |---|---|---|
  | ExportSuite | 4 | all 4 |
  | CanonicalSuite | 6 | 0 |
  | CollaborationSuite | 3 | 0 |
  | WindowAnalyticsSuite | 5 | 0 |
  | RunManifestSuite | 7 | 1 |
  | TemporalSuite | 2 | 0 |
  | GraphAlgorithmsSuite | 6 | 0 |
  | PipelineSuite | 8 | 3 |

- What the new tests cover:
  1. **JSON-lines copies (PipelineSuite)**:
     - Exactly these six tables get a JSON-lines copy: graph-nodes, graph-edges, coauthor-edges, researcher-metrics, semantic-summaries and paper-abstracts.
     - Every target manifest carries the same `jsonLines` block.
     - The listed files exist, and line counts per partition equal the Parquet counts.
     - The set of JSON values equals the Parquet rows read back with every column.
     - After dropping the declared `extraFields`, each line decodes strictly with core `JsonLines.decodeValid` into its record and passes core validation: GraphNode, GraphEdge, CoauthorEdge, ResearcherMetrics, EvidenceItem and the PaperAbstract shape.
  2. **`institutionId` (PipelineSuite)**: Ann's profile, the community and both topic summaries name openalex:I1. Bob, Dan and every path document have no `institutionId` key.
  3. **Paper abstracts (PipelineSuite)**:
     - The pipeline reads 3 abstracts and writes 1 row, with the source text byte for byte.
     - The row has the canonical work ID, `sourceWorkId`, `upstreamRunId`, and provenance (OPENALEX, W1, snapshot, Spark run ID, abstract-v1, er-v1).
     - The other two are counted rejects: ABSTRACT_HASH_MISMATCH and ABSTRACT_WORK_NOT_FOUND.
     - The `s3vectors.json` `abstracts` block is checked, and the logic version is spark-v3.
  4. **Abstracts known cases (ExportSuite)**: a merged work keeps one abstract (the smallest source ID); the cases REF_MISMATCH, EMPTY, NOT_CANONICAL, NOT_FOUND and SUPERSEDED are each tested; with no input, nothing is emitted and `inputPresent` is false.
  5. **Coauthor windows (ExportSuite)**: every exported window is listed, including one the config does not name, and a per-window count mismatch is detected. PipelineSuite checks `exportedWindows` and `graphEdgesByWindow` in `neptune.json`.
  6. **Upstream location (RunManifestSuite)**: abstracts are found through the Hadoop summary's `J02-OPENALEX` stage or are absent; the manifest's `inputs.hadoop.abstracts` changes the run ID.

VALIDATION RESULTS:
- Spark run ID: run-c4b35c788d05a93c (logic spark-v3; shuffle partitions are not in the manifest, so both attempts have this ID).
  - Run manifest: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24/spark-out/run-manifests/run-c4b35c788d05a93c.json
  - THIS RUN IS NOT USABLE. No summary, no tables, no JSON-lines files and no load manifests were written. Later steps must not read it.
- Attempt 1 (start 2026-11-24T08:55:17Z):
  - Wall time 23:03.93; peak resident 2,638,748 kB; heap -Xmx2g; local[2]; 4 shuffle partitions.
  - Failure: OutOfMemoryError in task 0.0 of Spark stage 726 (job 196), in `UnsafeSorterSpillReader.<init>` inside `HashAggregateExec.finishAggregate`, a sort-based aggregate fallback while spilling.
  - The log shows 71 warnings that partitions of the cached input rdd_276 (up to 1049 MiB each) did not fit in memory.
- Attempt 2 (start 09:20:00Z):
  - Wall time 1:30:03; killed at the 90-minute limit; peak resident 2,753,316 kB; 32 shuffle partitions; 0 OutOfMemoryError.
  - It reached Spark job 3913 and checkpoint rdd-15855, inside the iterative graph algorithms. Job 11 (export) was never reached.
- Row counts per exported table (Parquet and JSON-lines), semantic-summary rows per document type, paper-abstract rows, and windows in `neptune.json`: NONE, because no output exists.
  - On the test fixture: 11 nodes, 18 edges, 6 coauthor edges, 8 metrics rows, 20 summaries (8 researcher, 2 community, 6 path, 4 topic), 1 paper-abstract.
  - The fixture's `neptune.json` lists the 4 configured windows; `exportedWindows` is {1950-2026: 3, 2015-2026: 3}.
  - The Hadoop input has 25,078 works with an `abstractTextRef` and 25,078 written abstracts, so paper-abstract rows are expected once a run finishes.
- About the "neptune.json lists only 1950-2026" observation: I could not reproduce it. The Phase 06 run's file (target/spark-local-run/.../run-769e0d672ecd3c19/load-manifests/neptune.json) already lists all 4 configured windows. The new code guarantees the listing either way and adds per-window counts.

ASSUMPTIONS (choices made with the human asleep):
1. **JSON-lines shape.** I read "identical identifiers and fields" literally: each line is one Parquet row with every column, partition columns included, and nulls written.
   - Each manifest declares `coreRecord` and `extraFields` per table:
     - coauthor-edges: supportingWorkIds, windowLabel
     - researcher-metrics: maximumTeamWorkCount, inLargestComponent, windowLabel
     - semantic-summaries: docType, windowLabel
     - paper-abstracts: sourceWorkId, upstreamRunId
     - graph-nodes and graph-edges: none
   - The storage loaders must drop `extraFields` before their strict core decode; the current `RowSource.jsonLinesDir` would reject those lines as they are.
   - Location `<run>/jsonl/<table>/`, uncompressed `part-*.json`, because storage reads `.json` and `.jsonl.gz` but not `.json.gz`.
2. **Paper abstracts.** Read from `<J02-OPENALEX output>/abstracts`, found through the Hadoop summary.
   - Written as table `paper-abstracts` in storage's `PaperAbstract` shape, with `workId` set to the canonical graph Work ID.
   - One abstract per canonical work. The rest are counted rejects, with the reason codes in the PipelineSuite and ExportSuite items under TEST RESULTS.
   - Listed in a separate `abstracts` block of `s3vectors.json`, so storage's index-row check on `semantic-summaries` still balances.
3. **`institutionId`.** It is the first entry of each document's own institution list, ties broken by institution ID; path documents have none.
4. **Windows.** `neptune.json` lists the configured windows plus any exported window not configured. It adds `exportedWindows`, `graphEdgesByWindow` and the invariant `invariant_coauthorWindowRowsDiffer`. It now also lists the coauthor-edges table, which storage's Neptune exporter needs.
5. **Logic and checks.** The logic version moved from spark-v2 to spark-v3. New invariants: `invariant_jsonLinesRowsDiffer_<table>`, `invariant_jsonLinesIdMismatch_<table>` (full outer join on the ID columns), `invariant_abstractsUnaccounted` and `invariant_abstractDuplicateWorkIds`.
6. **Retry before step-down.** After the attempt 1 OOM I retried once at full scale with 32 shuffle partitions instead of stepping down at once. My reasons: the runner's fixed 4 partitions are sized for the fixtures, and the step-down needs S2 and S3 reruns (S3 took 1:21:47). That retry is attempt 2, and it then hit the 90-minute limit. The human may count it as a deviation from the step-down rule.

RISKS:
- Not done:
  - No usable 100k Spark run, so no measured counts.
  - The new export code (JSON-lines writes, ID joins, abstracts) has not run at scale.
  - No full `sbt check`; no edits to storage or MODULE.md.
- State left behind, not deleted (git-ignored):
  - spark-out/run-manifests/run-c4b35c788d05a93c.json and spark-out/_checkpoints/ (about 2.1 GB plus 173 MB).
  - The human or the next run may remove them; a rerun with the same inputs reuses this run ID and folder.
- Scale (both attempts): the 100k run did not fit 2 GB / 90 min on local[2].
  - Likely costs: job 1 identity clustering over 4.5M decisions with 1 GB cached partitions, and the per-window iterative algorithms (components 40, label propagation 8, k-core, BFS) over about 1M pairs in 4 windows.
  - The JSON-lines export adds, per partition folder, one pruned Parquet scan, one write and one line count, plus one ID join per table. That is a full extra pass over the exported data.
  - Uncompressed JSON is larger than the Parquet.
- Notes for modules/spark/MODULE.md (documentation-engineer):
  - Public API: new `Abstracts` (`build`, `ExtraFields`, `Stage`, `SchemaVersion`).
  - `Export.write` gains an optional `JsonSpec` argument, plus new `Export.JsonOut`, `Export.JsonLinesExport`, `Export.CoauthorWindows` and `Export.coauthorWindows`.
  - `loadManifests` takes `CoauthorWindows`.
  - `Upstream.abstractsDir`, `Upstream.OpenAlexStage`, and the `Summaries.PaperAbstract` and `Summaries.AbstractIndexKey` constants.
  - New table `paper-abstracts`, JSON copies under `jsonl/`, logic spark-v3, `LocalPipelineRun --shuffle-partitions`.
  - Known issue: a local run at 100k works does not finish within 2 GB and 90 minutes.

MANUAL ACTION REQUIRED: none by the human now. Optional: delete the leftover spark-out folder (about 2.3 GB), since agents do not delete data.

BLOCKED-ON OWNER: hadoop-engineer. Under the step-down rule the Spark run must be repeated at 25,000 works, which needs a 25,000-work S2 run. Entity-resolution-engineer's matching S3 run follows it, and that order is the orchestrator's to route.

BLOCKED-ON EVIDENCE:
- Attempt 1 (/tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/s00run1.log):
  `[error] 03:18:13 ERROR Executor: Exception in task 0.0 in stage 726.0 (TID 613)`
  `[error] java.lang.OutOfMemoryError: Java heap space`
  `[error] 	at org.apache.spark.util.collection.unsafe.sort.UnsafeSorterSpillReader.<init>(UnsafeSorterSpillReader.java:54)`
  `[error] (sparkAnalytics / Test / runMain) nonzero exit code returned from runner: 52`
  GNU time: `Elapsed (wall clock) time (h:mm:ss or m:ss): 23:03.93`, `Maximum resident set size (kbytes): 2638748`, `Exit status: 1`.
- Attempt 2 (same folder, s00run2.log and s00-time2.txt): `rc=124`; GNU time `Elapsed (wall clock) time (h:mm:ss or m:ss): 1:30:03`, `Maximum resident set size (kbytes): 2753316`, `Exit status: 124`; 0 OutOfMemoryError; no table folders under spark-out.

NEXT GATE: STATUS.md update by documentation-engineer (step 00 BLOCKED-ON, with both attempts). Then hadoop-engineer runs S2 at 25,000 works and entity-resolution-engineer runs S3 on it. Then spark-engineer reruns this step's local pipeline once (suggested flag `--shuffle-partitions 32`), and steps 01 to 03 read the new Spark run through its manifest.
