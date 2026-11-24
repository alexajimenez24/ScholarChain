HARNESS: claude-code
MODEL: Claude Opus 5.5 (claude-opus-5-5), high tier (role default, as the work order says)

The repair worked. Run **run-ccf619c509611f06** finished with status **SUCCEEDED**: 114 invariants checked, 0 nonzero. All five indexes now have rows, including collaboration-path-v1 with 278. Components in 1950-2026 converged at the expected 15,830, with a largest of 8,615, and my offline union-find check agrees exactly.

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 08 step 00d. Choose path-evidence targets from each sampled source's reach within pathMaxDepth (reachable by construction, deterministic, bounded), with known-graph tests and a logic version bump. Run sparkAnalytics/test and format only my own files. Run one 25k local rerun exactly as 00b.

FILES CREATED:
- /home/ajcreator/scholarchain/tests/module-unit/spark/PathSelectionSuite.scala (4 tests)

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Paths.scala: new `targetsPerSource`, `reachablePairs` and `sampledEvidence`. The shared path and hop-edge code moved into a private `fromLevels`, and `evidence` keeps its signature and behavior.
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/SparkPipeline.scala: `pathEvidence` uses the new rule. New report keys `pathTargetsPerSource` and `invariant_pathRequestedNotFound`.
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Export.scala: the path-evidence `selection` text now describes the new rule.
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/RunManifest.scala: LogicVersion spark-v3 -> spark-v4.
- /home/ajcreator/scholarchain/tests/module-unit/spark/PipelineSuite.scala: the logic assertion is now spark-v4, and the fixture comment states the new pair rule. The expected path counts are unchanged at 3 per window.

COMMANDS RUN:
- `timeout 3000 sbt -batch "sparkAnalytics/scalafmt" "sparkAnalytics/Test/scalafmt" "sparkAnalytics/test"`
  - rc=0, 478 s, `Passed: Total 45, Failed 0, Errors 0`.
  - The harness moved it to the background. I waited, started nothing else and did not poll.
- Pre-run checks: `ps` showed no java or sbt running; `free -m` showed 3,634 MB available; `df`; `git check-ignore` confirmed spark-out is ignored by `datasets/samples/local/.gitignore:1:*`; a `git status` snapshot was saved.
- The run, with the same inputs and settings as 00b: `/usr/bin/time -v timeout 5400 sbt -batch '<Test/javaOptions -Xmx2g>' "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture <scratch>/capture-00d --shuffle-partitions 8 --hadoop-manifest .../2026-11-24-25k/hadoop-out/run-manifests/run-03a81f6f54f3fc3c.json --er-manifest .../2026-11-24-25k/er-out/run-manifests/run-cb5a0421c406737d.json --output .../2026-11-24-25k/spark-out"`
  - rc=0, `local run run-ccf619c509611f06: 0 invariant failures`, `[success] Total time: 1491 s`.
  - It also went to the background; I waited without polling.
- `python3 -I` reads of the summary, run manifest and load manifests.
- JSON-lines line counts on disk.
- `python3 -I <scratch>/ccdrift.py <base>/jsonl`, the offline union-find check.
- `git status` diffed against the pre-run snapshot: identical.

TEST RESULTS:
- `sbt sparkAnalytics/test`: 45 passed, 0 failed (41 before, plus 4 new).
- By suite: Export 4, Canonical 6, Collaboration 3, WindowAnalytics 5, RunManifest 7, Temporal 2, GraphAlgorithms 6, Pipeline 8, PathSelection 4.
- What the new tests check:
  - **Budget.** `targetsPerSource(k) = max(1, floor((k-1)/2))`, which is 7 for k=16. For k >= 3, k times that is at most k(k-1)/2.
  - **Graph A** (chain a0..a7 and chain b0..b2, sources a0 and b0). The old rule's only pair, a0-b0, is not found. The new rule requests a0-a6, a0-a5, a0-a4, b0-b2 and b0-b1, all found with exact distances, paths and hop edge IDs (`Ids.coauthorEdge`). a7, at 7 hops, is never requested, even with a budget of 100.
  - **Graph S** (star c-l1..l5, sources c and l1). The pair c-l1 is requested only once, from c. Targets are the smallest SHA-256 of `src|tgt|seed`, checked in the test with an independent MessageDigest. No unordered pair repeats, and a rerun selects the same pairs.
  - **Graph L** (47-hop chain p00..p47). Components converge with the configured componentMaxIterations in exactly 48 iterations, which is more than the old cap of 40, and give one label, p00. From the middle source p20, the 7 targets have distances {6,6,5,4,3,2,1}, all exact, with correct hop edge IDs.
- I did not run `sbt check`. The phase runs it once at the end of step 3, and only one sbt may run at a time. The Stop hook asked for it twice while sbt jobs were running, and I declined both times for those reasons.

VALIDATION RESULTS:

Run
- Spark run ID: **run-ccf619c509611f06**.
- Logic spark-v4, Spark 4.0.1, local[2], 8 shuffle partitions, AQE on, reliable checkpoints.
- Config: componentMaxIterations 100, pathMaxDepth 6, betweennessSampleSources 16, seed 441.
- Run manifest: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/run-manifests/run-ccf619c509611f06.json
- Summary: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/run-manifests/run-ccf619c509611f06-summary.json (status SUCCEEDED)
- Load manifests, under /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/spark-out/spark/snapshot=2026-11-24/run_id=run-ccf619c509611f06/load-manifests/:
  - neptune.json
  - dynamodb.json
  - s3vectors.json
  - s3.json
- Wall time and memory:
  - 25:13.98 by GNU time (sbt reported 1491 s); start 2026-11-24T16:46:19Z, end 17:11:33Z.
  - Peak RSS 2,923,636 kB, CPU 272%, 0 OutOfMemoryError.
- Attempts: 1.

Pair rule (spark-v4)
- Sources are the same hash sample as before: the 16 smallest SHA-256 of `id|seed` among connected researchers.
- Candidates are the BFS reach of each source within pathMaxDepth, at distance 1 or more.
- A pair of two sources is kept only from the smaller ID, so one undirected pair is requested once.
- Within each (source, distance), candidates are ranked by SHA-256 of `src|tgt|seed`, with ties broken by ID.
- Each source then takes them round-robin over distances, longest first, up to `targetsPerSource(16) = 7`.
- So the window budget is at most 112 pairs, below the old 120.
- Paths are BFS shortest paths over the exported coauthor edges, with one edge ID per hop. Everything is done with distributed window functions; the driver receives only counts and the histogram (at most 6 rows).

Exported tables (Parquet rows = JSON-lines rows in the manifest = lines on disk)

| Table | Rows |
|---|---|
| graph-nodes | 118,960 |
| graph-edges | 543,699 |
| coauthor-edges | 337,677 |
| researcher-metrics | 167,600 |
| semantic-summaries | 217,661 (was 217,383; +278 path documents) |
| paper-abstracts | 6,292 |

- Parquet-only tables:
  - components 35,954 (was 35,955)
  - communities 70,864
  - path-evidence 278
  - metric-comparisons 100,386
  - rejects 0

Semantic-summary rows per document type, matching the rows in s3vectors.json

| Document type | Index | Rows |
|---|---|---|
| researcher-profile | researcher-profile-v1 | 167,600 |
| collaboration-community | collaboration-community-v1 | 43,826 |
| collaboration-path | collaboration-path-v1 | **278** |
| topic-institution-summary | topic-institution-summary-v1 | 5,957 |
| paper-abstract | paper-abstract-v1 | 6,292 |

- 0 path documents carry an institutionId, as designed.

Path pairs per window (requested / found, distance histogram)

| Window | Requested / found | Distance histogram |
|---|---|---|
| 1950-2026 | 85 / 85 | 1:46, 2:19, 3:8, 4:4, 5:4, 6:4 |
| 2000-2009 | 63 / 63 | 1:47, 2:13, 3:2, 4:1 |
| 2010-2014 | 52 / 52 | 1:43, 2:6, 3:3 |
| 2015-2026 | 78 / 78 | 1:51, 2:19, 3:4, 4:1, 5:1, 6:2 |

- invariant_pathRequestedNotFound is 0 and invariant_pathHopEdgeMissing is 0 in every window.
- The paths stages took 20.4, 9.4, 8.7 and 11.7 s.

Components per window (all converged)

| Window | Components | Largest | Iterations |
|---|---|---|---|
| 1950-2026 | **15,830** | **8,615** (0.106) | 47 |
| 2000-2009 | 7,725 | 154 | 8 |
| 2010-2014 | 4,505 | 66 | 6 |
| 2015-2026 | 7,894 | 909 | 27 |

- Offline union-find over the exported 1950-2026 JSON-lines: 0 of 168,577 edges join two Spark components. True components 15,830 = Spark 15,830; true largest 8,615 = Spark 8,615, under one label, cc:dcb74e6e70cc2316.
- Communities 21,083 / 8,894 / 4,789 / 9,337, converged in 8 / 7 / 5 / 7 iterations. Coreness converged in 7 / 7 / 3 / 4.
- Sampled max distance 39 / 4 / 3 / 20.

Other checks
- neptune.json lists all 4 windows, with the same coauthor-edge counts as 00b.

Invariants: 114 checked, 0 nonzero. That is 110 as in 00b plus one new `pathRequestedNotFound` per window.

ASSUMPTIONS (choices made with the human asleep):
1. **The budget is derived from the configured `betweennessSampleSources`, not from a new config field.** A new field would belong to domain-engineer. It keeps the window budget below the old rule's 120 pairs, and the pipeline fixture still gives 3 paths per window.
2. **Longest distance first in the round-robin.** Path documents exist to show multi-hop connections, and the order still spreads distances over the reach. The histograms lean to 1 hop because most sources sit in small components whose whole reach is 1 hop away.
3. **The path BFS stays a separate depth-6 BFS** instead of reusing the betweenness levels. This keeps `WindowAnalytics.Result` unchanged, and it cost 9 to 20 s per window.
4. **Peak memory** is GNU time's maximum RSS of the largest process (the forked JVM).

RISKS:
- Not done: `sbt check` (left for step 3), any AWS call, and any deletion.
- State left behind (git-ignored):
  - This run's output under 2026-11-24-25k/spark-out, next to the 00b run run-c73766e8001fce5a and its checkpoints, about 2.6 GB each.
  - The 100k output from step 00.
  - Scratch logs in /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/ (s00d-test1.log, s00d-run1.log, s00d-time.txt, capture-00d/).
- The tracked tree differs from before the run only by my edits; the formatter touched only modules/spark files.
- Notes for modules/spark/MODULE.md (documentation-engineer):
  - Public API additions: `Paths.targetsPerSource`, `Paths.reachablePairs`, `Paths.sampledEvidence`.
  - LogicVersion is spark-v4.
  - New report keys `pathTargetsPerSource` and `invariant_pathRequestedNotFound`.
  - Test count 45.
  - Known issues 1 (components cut off) and 2 (no path documents) from 00b are closed on 25k with run run-ccf619c509611f06.
  - Still open: the partition count of checkpointed state grows across iterations, and the 100k run does not fit in 2 GB and 90 minutes.
  - Path documents are a bounded sample of at most 112 per window; they are not all-pairs.

MANUAL ACTION REQUIRED: none. Optional: delete the old spark-out runs (agents do not delete data).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md (00d done, Spark run run-ccf619c509611f06 SUCCEEDED, all 5 indexes non-empty) and the spark MODULE.md notes. Then step 01 (rag-engineer) and every later step read run-ccf619c509611f06 through its run manifest.
