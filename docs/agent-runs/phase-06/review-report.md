HARNESS: claude-code
MODEL: Claude Code opus, effort high (high tier, `.agents/models.json`; the code-reviewer role default)

Verdict: REQUEST_CHANGES. There is one MAJOR finding: ER UNRESOLVED and CONFLICT pairs get merged through chains of accepted links, and this happened on the real sample. There are also 5 MINOR findings and 4 NITs. The rest of the phase holds up. I re-checked every expected value in the known-graph suites listed below by hand and all were correct. No full graph is collected to the driver. Limits come from the `graph` config block. Ownership is clean. `sbt sparkAnalytics/test` passes 30 of 30 on the current files.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 06 Review 1, fresh context. Review the whole uncommitted Phase 06 diff against `docs/agent-runs/phase-06/prompt.md` and the human addendum. The report is saved as `docs/agent-runs/phase-06/review-report.md`.
FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (each with its output observed):
- `git status --short`, `git diff --stat`, `git diff STATUS.md build.sbt`, `git diff modules/spark/MODULE.md`; `wc -l` over the Spark sources and tests (5922 lines).
- `zcat` and `python3 -I` (read-only, stdin only) over the er-v1 decisions in `target/er-match-run/match-decisions/policy=er-v1/snapshot=2026-11-22/run_id=run-02cecabcf3e68312/{accepted,conflict,unresolved}`:
  - a union-find over the ACCEPTED links, plus a BFS that traces each UNRESOLVED pair.
- `python3 -I` read of `target/spark-local-run/run-manifests/run-4903a4f9eabba786-summary.json`, to get the job 1 counters and the per-window convergence flags.
- `grep` and `sed` over the spec (`docs/spec/assignment.md`), core `Ids.scala`, `Config.scala` and `scholarchain-v1.yaml`, the reference (`~/ref-phase-06`), `modules/spark/target/t2-check.log`, `.gitignore` (via `git check-ignore`) and the `target/jobs` timestamps.
- `find ... -newer modules/spark/target/t2-check.log`: no Spark source, test or `build.sbt` file changed after the step 03 `sbt check`.
- `source toolchain/env.sh; sbt -no-colors sparkAnalytics/test`, exit 0. The harness moved it to the background at 180 s; I started nothing else and waited for the completion notice.

TEST RESULTS (verbatim):
```
Test run scholarchain.spark.CanonicalSuite finished: 0 failed, 0 ignored, 5 total 39.034s
Test run scholarchain.spark.CollaborationSuite finished: 0 failed, 0 ignored, 3 total 2.454s
Test run scholarchain.spark.WindowAnalyticsSuite finished: 0 failed, 0 ignored, 4 total 25.564s
Test run scholarchain.spark.RunManifestSuite finished: 0 failed, 0 ignored, 5 total 0.066s
Test run scholarchain.spark.TemporalSuite finished: 0 failed, 0 ignored, 2 total 22.149s
Test run scholarchain.spark.GraphAlgorithmsSuite finished: 0 failed, 0 ignored, 6 total 24.473s
Test run scholarchain.spark.PipelineSuite finished: 0 failed, 0 ignored, 5 total 78.828s
[info] Passed: Total 30, Failed 0, Errors 0, Passed 30
[success] Total time: 198 s (0:03:18.0), completed Nov 23, 2026, 2:42:45 PM
```

VALIDATION RESULTS: REQUEST_CHANGES

== MAJOR ==

MAJOR-1: ER UNRESOLVED and CONFLICT pairs are merged through chains of accepted links. Most of the time nothing reports it.
- Owner: spark-engineer. Policy choice: the human, through the orchestrator.
- Where:
  - `modules/spark/src/main/scala/scholarchain/spark/Canonical.scala:24-33` (the policy text);
  - `:162-169` (every ACCEPTED link goes into one connected-components clustering);
  - `:191-194` (`flagged`: CONFLICT and REJECTED decisions count only when both ends are already in one cluster; for UNRESOLVED, any touched end flags the whole cluster);
  - `:233` (a cluster that holds 2 ORCID iDs is kept as one researcher, only flagged).
- Rule: the addendum says "Unresolved and conflict decisions are preserved, never merged". The work order says "Preserve unresolved author mentions without forcing a merge" (spec line 1269). Review focus 6 of the spec is "over-merging and unreported uncertainty".
- Failure on the real Phase 05 sample: all 267 ACCEPTED researcher links were applied (`researcherDecisionLinksApplied 267`, 0 dropped). As a result, 3 of the 5 UNRESOLVED researcher pairs end up in one canonical researcher:
  - m:8502cfd5507ea6416409 and m:ce4cb74feada864171f8, through m:3ea806cba0c3a681381f (accepted 0.925 and 1.0);
  - m:9bcc28e85a65529563ad and m:c748eaad7afe38950ba7, through m:90924ce4ee440437b679 (0.955 and 1.0);
  - m:1865a6b56ed9809d1979 and m:8502cfd5507ea6416409, through m:3ea806cba0c3a681381f.
- What the run reported: `researchersUnresolved 4` and `researchersIdentityConflict 0`. No counter names the merged pairs. `docs/spark-jobs.md` and `MODULE.md` never describe how unresolved decisions are handled.
- Tests codify the behaviour:
  - `tests/module-unit/spark/CanonicalSuite.scala:20,273` asserts that the CONFLICT pair a2-x3 is merged and only flagged.
  - The only UNRESOLVED case in the suites has its two ends in different clusters (a1-a4). The case that actually happens in the sample (both ends in one cluster) is never tested.
- Impact: every metric downstream (P, C, T, W, components, paths, summaries) treats mentions that ER would not merge as one person. This is the defect class that "passes its tests and corrupts every metric downstream". The same inherited behaviour comes from the reference `Identity.scala`.
- Repair brief:
  - Likely cause: transitive closure over pairwise ER decisions, with no cut constraints.
  - The human must choose the policy:
    - (a) a contradicting decision (UNRESOLVED, CONFLICT, REJECTED, or a second ORCID) inside a cluster splits it, for example by refusing the weakest accepted link on the path, or by not merging the two sub-clusters;
    - (b) transitive merges are kept, but every in-cluster contradiction is counted, disclosed and flagged.
  - Minimum under either option:
    - a counter per outcome, for example `researcherUnresolvedPairsMergedTransitively` and the CONFLICT and REJECTED equivalents for works too;
    - in-cluster UNRESOLVED should set `identityConflict` (or a distinct flag);
    - a known-graph test with an in-cluster UNRESOLVED pair;
    - the MODULE.md and `docs/spark-jobs.md` text updated by documentation-engineer.
  - What else could break: researcher IDs (`sc:r:v1:` hashes of the mention set) change if clusters split, so the PipelineSuite expected IDs and the local-run counts (79 researchers) change.
  - Tests that must stay green: CanonicalSuite (adjust the a2-x3 expectation to the chosen policy), PipelineSuite, then a rerun of the local integration run.

== MINOR ==

MINOR-1: an iterative algorithm that does not converge is not treated as an invariant failure.
- Owner: spark-engineer.
- Where: `WindowAnalytics.scala:89-90, 153-154, 216-217` and `Canonical.scala:99-100` record `*Converged` as plain booleans. `SparkPipeline.invariants` (`SparkPipeline.scala:494-507`) counts only `invariant_*` keys.
- Failure: if hash-min components or identity clustering stop at `componentMaxIterations` (40) before converging, components are split and researcher clusters are partial, yet the run reports status SUCCEEDED and exits 0, and loaders take it. On full DBLP, identity chains or a component with a long diameter can reach that bound.
- Repair: emit `invariant_componentsNotConverged`, `invariant_researcherClusterNotConverged`, `invariant_workClusterNotConverged` and `invariant_corenessNotConverged` as 0/1. Label propagation can stay a reported flag, because its oscillation is documented. Add one test using the existing cut-off case.
- Must stay green: RunManifestSuite (invariant collection), PipelineSuite (status SUCCEEDED).

MINOR-2: a cluster run without `--checkpoint-dir` silently falls back to `localCheckpoint`.
- Owner: spark-engineer; infra-engineer must pass the flag from the Phase 12 run script.
- Where: `Support.scala:26-33`; `SparkPipeline.scala:54`; `RunManifest.scala:14, 22` (the option is optional).
- Failure: EMR turns on dynamic allocation by default. Spark documents `localCheckpoint` as unsafe when executors are removed, so a removed idle executor loses checkpoint blocks in the middle of an iteration.
- Repair: require `--checkpoint-dir` when the master is not `local[*]`, or refuse to start; state this in Usage. MODULE.md should note it for Phase 12.

MINOR-3: Neptune receives one COAUTHORED_WITH edge per researcher pair per window, including overlapping windows, and this is not stated in MODULE.md or `docs/spark-jobs.md`.
- Owner: spark-engineer (decision); documentation-engineer (docs).
- Where: `Export.scala:234-266`.
- Observed: 152 COAUTHORED_WITH edges = 76 pairs x 2 windows (1950-2026 and 2010-2014 hold the same graph). On full data a pair gets up to 2 parallel edges: the cumulative window plus one period window.
- Spec 664: "Duplicate researcher pairs must be consolidated deterministically." Spec 1519: Neptune templates for direct collaborators.
- Impact: a graph-service collaborator or degree query that does not filter on the edge's `window` property double-counts.
- Repair: either load only one window (for example the cumulative one) into Neptune and keep the per-window edges in Parquet and DynamoDB, or keep the per-window edges and put the window filter into the MODULE.md public API for storage-engineer and service-engineer.

MINOR-4: identity provenance is thin (rule 3).
- Owner: spark-engineer.
- Where: researcher rows carry `provenance.policyVersion = graph-v1` (`Canonical.scala:235-243`, `Export.scala:163`), not the er-v1 policy that made the merge. The Researcher graph node has no `mentionIds` and no applied decision IDs (`Export.scala:152-162`).
- The lineage can be recovered only through the AUTHORED edges' `mentionIds` plus the run manifest.
- Repair: put er-v1 (`up.erPolicyVersion`) into researcher and work identity provenance. Export the mention IDs, or a pointer to the mention-to-researcher table, with the node.

MINOR-5: the Spark job jar was never built with the Phase 06 sources.
- Owner: build-engineer (assembly check). The gate step can be routed by the orchestrator.
- Evidence: `target/jobs/sparkAnalytics-0.1.0-SNAPSHOT.jar` is dated 11-23 07:28, before any Phase 06 source existed (12:09 onward). The addendum step 1 check ("confirm that the Spark job jar still builds") ran before the sources. MODULE.md discloses this under Not done.
- Repair: run `sbt packageJobs` (or `sparkAnalytics/assembly`) once on the final tree, and repeat the scala-library absence check (`scala/collection/` and `ScalaRunTime` entries 0).

== NIT ==

- NIT-1: `STATUS.md:720` says "`git status --short` (run for this update)", but the list leaves out ` M modules/spark/MODULE.md` and `?? docs/spark-jobs.md`. Step 04 created those files, and its report shows no git status was run (documentation-engineer).
- NIT-2: `docs/spark-jobs.md:194-195` says it is unknown whether the driver sets `spark.cleaner.referenceTracking.cleanCheckpoints`. `SparkPipeline.scala:50` sets it (documentation-engineer).
- NIT-3: `Schemas.scala:12-14` claims contract drift is made visible, but the PERMISSIVE JSON reader silently ignores unknown or renamed extra fields. Only missing required fields and type mismatches are caught (spark-engineer).
- NIT-4: `SparkPipeline.scala:381-382` repeats the 500 and 2000 truncation lengths of core `RejectRecord.of` instead of calling it (spark-engineer).

== Verified correct ==

Fixtures checked by hand, all correct:
- G components and the 5-iteration bound.
- The label-propagation trace on G, which converges in 3 iterations, and the weighted and flat paths a-x-b.
- k-core: G = 2,2,2,2,2,2,2,1,1,0; K4 with a pendant = 3/1; capped at 2 = 2/1.
- Betweenness: n4 = 9/36, n3 = n5 = 8/36; 4-cycle 0.5/3 each; the sampled path estimate 1.5 against exact 1.0, including the scaling population/k/2/((n-1)(n-2)/2).
- Participation: n4 0.5, n5 4/9.
- Distances in {n1..n7}: 16/10/8/8 at distances 1 to 4, sum 92, effective diameter 4.
- Collaboration measures for r1: P 3, C 13, T 14, M 11, W 14; and the r9-s1 representative and maximum-team works.
- The Temporal retention example, and the PipelineSuite label-propagation case and table counts.

Other checks that passed:
- Pairs: self pairs are dropped and counted, pair order is canonical, duplicates are aggregated per canonical work, and edge IDs match core `Ids.coauthorEdge`.
- Brandes back-pass logic is correct.
- Driver traffic is bounded: top-1 rows, k source IDs, the histogram, partition counts and decision counts.
- Inputs come only through the manifests: hash checks, upstream, snapshot and policy cross-checks, `_SUCCESS` required, `part-*` files only, and the ER `audit/` folder is not read. Nothing reads raw data or `datasets/gold/`.
- `build.sbt`: Test-only fork with the reference opens and -Xmx1g, no version change, `assemblyExcludedJars` unchanged.
- Ownership: core, hadoop and entity-resolution are untouched; every file is in its owner's territory; no `human-gate.md`.
- The documentation numbers match `output-spark-local-run.md` and `t2-check.log`; I mapped the per-project totals 94/68/112/98/30 from the suite lists in the log.
- The EMR run is reported NOT RUN everywhere.
- Scope: I found nothing optional beyond the gate.

ASSUMPTIONS:
- MAJOR-1 is judged against the addendum's literal wording. Whether transitive closure of accepted links is allowed is a policy question the spec does not settle, so the orchestrator should take the remedy choice (a or b) to the human.
- My reading of "unresolved pairs merged" comes from the decision files plus the observed `researcherDecisionLinksApplied 267` (0 dropped). The mention-to-researcher table is not exported, and pyarrow is not installed, so I could not read the Parquet output directly.

RISKS (what I did NOT review, or reviewed only lightly):
- I did not inspect the formatted plans in `plans.txt` and `stages.tsv` beyond the excerpts in the report.
- Scale behaviour on EMR is unverified, as the reports state: reliable checkpoints every iteration, the 2532-node job 10 plan, and `supportingWorkIds` with no bound.
- Spark `least`/`greatest` and `array_sort` compare strings as UTF-8 bytes, while core compares UTF-16 code units. The order is the same for the current ASCII IDs, but it is not guarded.
- Path-evidence pairs are pairs of the sampled sources, not benchmark queries. On the sample only 4 of 120 were found.
- Summary texts embed untrusted titles and names; injection defence belongs to rag-engineer in later phases.
- Not run: the full `sbt check` (it was not needed; no file changed after step 03's run), `packageJobs` (see MINOR-5), and no mutation testing.
- State left behind: `sbt` refreshed build output under `modules/spark/target/`, which is git-ignored. Nothing else changed.

MANUAL ACTION REQUIRED: the human chooses the identity policy for MAJOR-1: split clusters on a contradicting decision, or keep transitive merges and disclose them.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: repair round R1:
- spark-engineer: MAJOR-1 after the human's policy choice, and MINOR-1 to MINOR-4;
- build-engineer: MINOR-5;
- documentation-engineer: NIT-1, NIT-2 and the MAJOR-1 and MINOR-3 doc text.

Then Review 2 (`review-report-02.md`), which is required because this review has a MAJOR finding.
