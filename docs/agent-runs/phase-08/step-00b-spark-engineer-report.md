HARNESS: claude-code
MODEL: Claude Opus 5.5 (claude-opus-5-5), high tier (role default, as the work order says)

The 25,000-work rerun finished on the first attempt in 24:56, with no memory failure and no code change. The new Spark run ID is **run-c73766e8001fce5a**. Its summary status is `INVARIANT_FAILURES`, not `SUCCEEDED`: 1 of 110 invariants is nonzero.

- **Unconverged components.** In window 1950-2026, connected components hit the configured cap of 40 iterations (`componentMaxIterations`) before converging. I checked the exported data against an exact count: one real component of 8,615 nodes was split into two pieces of 8,540 and 75. So that window shows 15,831 components instead of 15,830, and its largest component as 8,540 instead of 8,615. The other three windows converged.
- **No path documents.** `pathPairsFound` is 0 of 120 in every window, so there are 0 path documents and the `collaboration-path-v1` index gets 0 rows. Every other table, including all JSON-lines copies, is complete and matches its Parquet.

Neither is a crash: the run behaved as designed and the bounds were reported. The work order allows a code change only for a bug, so I made none. Both need a decision before step 02 (see RISKS and NEXT GATE).

STATUS: COMPLETE (the run finished and is usable. The manifest status is INVARIANT_FAILURES, with the 1 failure and the empty path index described above.)
OWNER: spark-engineer
WORK ORDER: Phase 08 step 00b. Rerun the local Spark pipeline (`LocalPipelineRun`, -Xmx2g, `timeout 5400`) on Hadoop run run-03a81f6f54f3fc3c and ER decision run run-cb5a0421c406737d (25k step-down). Output to datasets/samples/local/2026-11-24-25k/spark-out. No code change unless the run fails for a bug.

FILES CREATED:
- None tracked by git.
- Git-ignored run output: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/** (about 2.6 GB: spark/ 1.3G, _checkpoints/ 1.3G, run-manifests/ 60K).
- Scratch, all under /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/:
  - s00b-run1.log, s00b-time.txt, s00b-start.txt, s00b-git-before.txt
  - capture-00b/ (stages.tsv, phases.tsv, plans.txt, plans-index.tsv, outcome.txt)
  - ccdrift.py

FILES MODIFIED: none. `git status --porcelain` is identical before and after the run.

COMMANDS RUN (each with its observed result):
- `git check-ignore -v datasets/samples/local/2026-11-24-25k/spark-out`: ignored through `datasets/samples/local/.gitignore:1:*`. The same check on the new run manifest gives the same result.
- Pre-run checks: `ps` (no java or sbt running), `free -m` (3,509 MB available, 946 MB swap already in use), `df -h`.
- The run: `/usr/bin/time -v -o <scratch>/s00b-time.txt timeout 5400 sbt -batch 'set sparkAnalytics / Test / javaOptions := (sparkAnalytics / Test / javaOptions).value.filterNot(_.startsWith("-Xmx")) :+ "-Xmx2g"' "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture <scratch>/capture-00b --shuffle-partitions 8 --hadoop-manifest /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/hadoop-out/run-manifests/run-03a81f6f54f3fc3c.json --er-manifest /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/er-out/run-manifests/run-cb5a0421c406737d.json --output /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out"`
  - Result: rc=0, `[success] Total time: 1469 s`, `local run run-c73766e8001fce5a: 1 invariant failures`.
  - The harness moved it to the background at 180 s. I started nothing else, did not poll, and read the output once after the completion notice.
- `python3 -I` reads of the summary, run manifest and four load manifests.
- Per-file JSON-lines line counts.
- `ccdrift.py`: an exact union-find component check over the exported JSON-lines, run offline as a validation script. It is not part of the pipeline.
- `values.institutionId` tallies, `awk` over the capture files, and `git status` diffed against the pre-run snapshot.
- Not run: `sbt check` and `sbt sparkAnalytics/test`, because no code changed. The phase runs its one full check at step 3, and the machine allows one sbt at a time. The Stop hook asked for `sbt check` while the run was going; I declined for those reasons.

TEST RESULTS:
- No test run in this step, because there was no code change.
- The last `sparkAnalytics/test` result is step 00's: 41 passed, 0 failed.

VALIDATION RESULTS:

Run
- Spark run ID: run-c73766e8001fce5a. Logic spark-v3, graph-v1 / metrics-v1, snapshot 2026-11-24.
- Spark 4.0.1, local[2], 8 shuffle partitions, AQE on, reliable checkpoints.
- Output base: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/spark/snapshot=2026-11-24/run_id=run-c73766e8001fce5a
- Run manifest: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/run-manifests/run-c73766e8001fce5a.json
- Summary: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/run-manifests/run-c73766e8001fce5a-summary.json (status INVARIANT_FAILURES)
- Load manifests, under `<output base>/load-manifests/`:
  - neptune.json
  - s3vectors.json
  - dynamodb.json
  - s3.json
- Wall time and memory:
  - GNU time wall clock 24:55.55 (sbt reported 1469 s); start 2026-11-24T15:59:11Z, end about 16:24:06Z.
  - Peak memory: maximum resident set 2,974,968 kB for the forked JVM (heap -Xmx2g plus off-heap). 0 OutOfMemoryError.
  - CPU 261%, 90,113 major page faults.
- Attempts: 1.

Inputs (all with 0 rejects and 0 provenance run-ID mismatches)

| Input | Rows |
|---|---|
| works | 38,564 |
| mentions | 136,266 |
| authorpapers | 136,266 |
| pairs | 241,686 |
| topics | 1,945 |
| institutions | 5,318 |
| venues | 5,326 |
| abstracts | 6,292 |
| decisions (all er-v1) | 903,286 |

Job 1 (identity) and job 2 (pairs)
- 81,351 researchers from 136,266 mentions; 25,020 canonical works from 38,564 source works.
- Researcher clustering converged in 6 iterations, work clustering in 3.
- 615 researcher clusters were cut, refusing 888 links. 0 contradictions were merged and 0 clusters hold several ORCIDs.
- Job 2: 241,686 pair candidates, 7 self-pairs dropped, 0 refused by the team cap, 170,744 researcher-pair-work rows.

Exported tables: Parquet rows, JSON-lines rows in the manifest, and JSON-lines lines on disk. All three agree for every table with a JSON-lines copy, and the ID-join invariants are 0.

| Table | Parquet | JSON-lines (manifest) | JSON-lines (on disk) |
|---|---|---|---|
| graph-nodes | 118,960 | 118,960 | 118,960 |
| graph-edges | 543,699 | 543,699 | 543,699 |
| coauthor-edges | 337,677 | 337,677 | 337,677 |
| researcher-metrics | 167,600 | 167,600 | 167,600 |
| semantic-summaries | 217,383 | 217,383 | 217,383 |
| paper-abstracts | 6,292 | 6,292 | 6,292 |

- graph-nodes by label: Researcher 81,351; Work 25,020; Institution 5,318; Venue 5,326; Topic 1,945.
- graph-edges by type: COAUTHORED_WITH 337,677; AUTHORED 92,675; AFFILIATED_WITH 38,941; HAS_TOPIC 37,239; PUBLISHED_IN 37,167.
- coauthor-edges by window: 1950-2026: 168,577; 2000-2009: 47,286; 2010-2014: 31,668; 2015-2026: 90,146.
- researcher-metrics by window: 81,351 / 30,135 / 17,347 / 38,767.
- Parquet-only tables:
  - communities 70,864
  - components 35,955
  - path-evidence 480 (120 per window, all with found=false)
  - metric-comparisons 100,386
  - rejects 0

Semantic-summary rows per document type (job10 report and s3vectors.json indexes)
- researcher-profile (researcher-profile-v1): 167,600 (81,351 / 30,135 / 17,347 / 38,767 by window).
- collaboration-community (collaboration-community-v1): 43,826 (20,918 / 8,814 / 4,778 / 9,316). Only communities of 2 or more members get a document.
- topic-institution-summary (topic-institution-summary-v1): 5,957 (1,945 / 1,344 / 1,169 / 1,499).
- collaboration-path (collaboration-path-v1): **0**, because no requested pair was found within the 6-hop limit.
- paper-abstract (paper-abstract-v1, the separate `abstracts` block in s3vectors.json, no window): 6,292.
- Job10 invariants: 0 duplicate chunk IDs and 0 empty texts.

Paper abstracts
- 6,292 read and 6,292 emitted, 0 not emitted.
- Rejects by reason, each 0: ABSTRACT_EMPTY, ABSTRACT_HASH_MISMATCH, ABSTRACT_WORK_NOT_FOUND, ABSTRACT_REF_MISMATCH, ABSTRACT_WORK_NOT_CANONICAL, ABSTRACT_SUPERSEDED_SAME_WORK.
- invariant_abstractsUnaccounted 0, invariant_abstractDuplicateWorkIds 0.

Windows in neptune.json (`coauthoredWith`)
- `windows`: [1950-2026, 2000-2009, 2010-2014, 2015-2026].
- `exportedWindows` and `graphEdgesByWindow`, identical: {1950-2026: 168,577; 2000-2009: 47,286; 2010-2014: 31,668; 2015-2026: 90,146}.
- invariant_coauthorWindowRowsDiffer 0. Job11 `coauthorWindowsListed` gives the same 4 windows.

Rows carrying `values.institutionId`: 106,614 of 217,383 summary rows. All are `openalex:I...` IDs, and path documents (none this run) carry no institutionId.

| Document type | 1950-2026 | 2000-2009 | 2010-2014 | 2015-2026 | Total |
|---|---|---|---|---|---|
| researcher-profile | 38,234 | 15,369 | 8,194 | 15,863 | 77,660 of 167,600 |
| collaboration-community | 11,297 | 5,055 | 2,413 | 4,283 | 23,048 of 43,826 |
| topic-institution-summary | 1,931 | 1,324 | 1,159 | 1,492 | 5,906 of 5,957 |

Invariants (110 checked, 1 nonzero)

| Report section | Invariants | Result |
|---|---|---|
| inputs (provenance run ID per input, plus decisions of another policy) | 10 | all 0 |
| job1 | 11 | all 0 |
| job2 | 1 | 0 |
| each of the 4 windows | 17 per window, 68 in all | all 0 except one |
| job10 | 2 | 0 |
| abstracts | 2 | 0 |
| job11 (duplicate node and edge IDs, missing edge endpoints, JSON-lines rows and IDs for 6 tables, coauthor window rows) | 16 | all 0 |

- Per-window invariants: metricsRowsNotEqualNodes, CrGreaterThanWr, WrGreaterThanTr, MrGreaterThanTr, MrWithoutWorks, publicationCountBelowOne, edgesUnordered, edgesWithoutPublication, edgeYearsOutsideWindow, edgeEndpointMissing, duplicateEdgeIds, componentSizesNotSummingToNodes, communitySizesNotSummingToNodes, crossCommunityEdgeEndsMismatch, componentsNotConverged, corenessNotConverged, pathHopEdgeMissing.
- **Nonzero: windows[1950-2026].invariant_componentsNotConverged = 1.**

Graph per window

| Window | Nodes | Edges | Components | Largest component (fraction) | Component iterations | Communities | Coreness iterations | Max coreness | Sampled avg path / effective diameter / max distance |
|---|---|---|---|---|---|---|---|---|---|
| 1950-2026 | 81,351 | 168,577 | 15,831 | 8,540 (0.105) | 40, cut off | 21,083 | 7 | 64 (75 at the cap) | 15.79 / 22 / 39 |
| 2000-2009 | 30,135 | 47,286 | 7,725 | 154 | 8 | 8,894 | 7 | 19 | 1.32 / 2 / 4 |
| 2010-2014 | 17,347 | 31,668 | 4,505 | 66 | 6 | 4,789 | 3 | 54 | 1.41 / 2 / 3 |
| 2015-2026 | 38,767 | 90,146 | 7,894 | 909 | 27 | 9,337 | 4 | not recorded in these notes | 20 (max distance) |

- Community detection converged in every window, in 8, 7, 5 and 5 iterations.
- Betweenness: 16 sampled sources per window (sampled-source Brandes estimate scaled by population/k), no BFS truncation.

Metric distributions in 1950-2026 (p50 / p90 / p99)

| Metric | p50 | p90 | p99 |
|---|---|---|---|
| publicationCount | 1 | 2 | 3 |
| distinctCoauthorCount | 3 | 7 | 18 |
| totalCoauthorOccurrences | 3 | 8 | 19 |
| maximumCoauthorsOnOnePaper | 3 | 7 | 16 |
| weightedDegree | 3 | 8 | 18 |
| coreness | 3 | 6 | 16 |
| participationCoefficient | 0 | 0.444 | 0.667 |
| bridgeScore | 0 | 0 | 1.23e-5 |

Component drift check (offline union-find over the exported 1950-2026 JSON-lines)
- 14 of 168,577 edges join two different Spark component IDs.
- True components 15,830 against 15,831 exported; true largest 8,615 against 8,540 exported.
- The true largest component holds exactly two Spark labels, of 8,540 and 75 nodes.

Paths and the temporal comparison
- Paths: 120 pairs requested per window (all pairs among the 16 hash-sampled sources), 0 found within pathMaxDepth 6. Distance histograms are empty, invariant_pathHopEdgeMissing is 0.
- Job9 (temporal):
  - 2000-2009 to 2010-2014: 1,692 researchers in both windows, 1,477 kept their component, 1,553 kept their community.
  - 2010-2014 to 2015-2026: 1,518 in both, 1,277 kept their component, 1,304 kept their community.
  - 1950-2026 against 2000-2009 is skipped because the windows overlap.

Stages and shuffle (from the capture files)
- 3,947 stages and 365 distinct SQL plans.
- Total shuffle read 2,592.9 MiB and write 2,565.5 MiB.
- Stage seconds:
  - read 182; job1 169; job2 9
  - window 1950-2026: 460; 2000-2009: 71; 2010-2014: 42; 2015-2026: 170
  - paths 7 to 21 per window
  - job9 7; job10 summaries 116; job10 abstracts 2; job11 export 170
- Windows 1950-2026 and 2015-2026 have repeated stages of 321 and 169 tasks (shown below the checkpoint stages), up to 633 tasks. The partition count grows across the iterative loops even with 8 shuffle partitions.

ASSUMPTIONS (choices made with the human asleep):
1. **Shuffle partitions = 8.**
   - At 100k, 4 partitions ran out of memory in a spilling sort-based aggregate (step 00, attempt 1), and 32 ran past 90 minutes. Most of that time is per-task overhead multiplied over hundreds of iterative jobs.
   - The 25k inputs are about 4 to 5 times smaller (903k decisions against 4.56M, 242k pairs against 981k).
   - With local[2], 8 partitions gives 4 tasks per core and partitions about an eighth of a stage's data, so aggregates spill far less than at 4. It also has a quarter of 32's per-iteration task count. AQE may coalesce further.
   - This was the only attempt, so it is not a retry with other settings.
2. **No code change.** The run did not fail. The nonconverged components are the documented, reported cut-off of a configured bound, and the empty path set is what the documented pair-selection rule gives on this graph. Neither is a crash. I read "No code change unless the run fails for a bug" strictly and left any fix to the orchestrator's routing.
3. **Peak memory** is GNU time's maximum resident set for the largest process (the forked JVM). Heap use was not measured separately.
4. **Two ways the step 00 report differs from this run's files:**
   - The institutionId lives in `values.institutionId` of each summary row, not in a top-level field.
   - The path-evidence table holds requested pairs even when none is found, which gives 480 rows here.

RISKS:
- **Open finding 1, components cut off in 1950-2026.**
  - `componentMaxIterations: 40` (core config, scholarchain-v1.yaml, owner domain-engineer) is below what hash-min label propagation needs here. The observed max distance is 39, and convergence needs the diameter plus 1, plus a round with no change.
  - Effect: 75 researchers carry the wrong componentId and componentSize (and inLargestComponent=false) in the 1950-2026 window. That window's component count is +1 and its largest component -75 (0.9%). Their researcher-profile text inherits those values.
  - The same bound also caps the coreness and BFS loops, which converged or were not truncated here.
  - Fix options:
    - (a) domain-engineer raises `componentMaxIterations` to 64 or more. This is a config change; it changes the manifest and so the run ID.
    - (b) spark-engineer adds pointer jumping (label ← label of label) to `GraphAlgorithms.connectedComponents`, so it converges in far fewer rounds. This needs a known-graph test with a long path, a logic version bump, and one about 25-minute rerun at 25k.
- **Open finding 2, no path documents.**
  - `pathEvidence` pairs the 16 hash-sampled sources with one another. Most researchers sit outside the largest component (89.5% in the all-time window, more than 97% elsewhere), and distances inside it average about 16 hops against a 6-hop limit. So no pair is found and `collaboration-path-v1` has 0 rows for step 02.
  - The fixture passed because its graph is small and dense.
  - Fix (spark-engineer, in territory): choose targets from each source's BFS reach within pathMaxDepth, for example deterministically hashed reachable nodes, so requested pairs are reachable by construction. This needs a known-graph test and the same single rerun.
  - If this is not fixed, step 02 can ingest only 4 of the 5 required indexes from this run.
- **Unchanged:** 25k is the current scale, and the last allowed step-down is 10k, which is not needed for time or memory (25 of 90 minutes).
- **Not done:** no code change, no tests run, no `sbt check`, no AWS call, no deletion. The leftover 100k output from step 00 (about 2.3 GB) is still in datasets/samples/local/2026-11-24/spark-out.
- **State left behind (git-ignored):** this run's output of about 2.6 GB, including _checkpoints/ (1.3 GB), which the human or a later run may remove.
- **Notes for modules/spark/MODULE.md (documentation-engineer):**
  - No public API change.
  - Done: the 25k local run run-c73766e8001fce5a, in about 25 min, with -Xmx2g, 8 shuffle partitions and peak RSS about 2.9 GB.
  - Known issues:
    - (1) Components cut off at 40 iterations in the all-time window on 25k data.
    - (2) Sampled path pairs find nothing on a fragmented graph, so the path index is empty.
    - (3) The checkpointed state's partition count grows across iterations (stages up to 633 tasks with 8 shuffle partitions). This is a likely cost at scale, not investigated.
    - (4) The 100k local run does not fit in 2 GB and 90 minutes (from step 00).

MANUAL ACTION REQUIRED: none now. Optional: delete the leftover spark-out folders (agents do not delete data).

BLOCKED-ON OWNER: none.
BLOCKED-ON EVIDENCE: none.

NEXT GATE:
- First, documentation-engineer updates STATUS.md (00b done, Spark run run-c73766e8001fce5a, status INVARIANT_FAILURES with the two open findings).
- Then the orchestrator decides one of:
  - (i) Proceed to step 01 and step 02 on this run, with collaboration-path-v1 empty and the component drift carried to the gate.
  - (ii) Route a repair first: domain-engineer for `componentMaxIterations` and/or spark-engineer for pointer-jumping components and reachable path targets, each with tests. Then spark-engineer does one 25k rerun (about 25 min) and later steps read the new run ID.
