HARNESS: claude-code
MODEL: sonnet (claude-sonnet-5-5); effort medium (tier standard, .agents/models.json)

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 11 repair round 1, A-1 and C18 (hadoop-engineer part, the N7 module-unit cases)
FILES CREATED: none in the repository (this report; two sbt logs in the scratchpad)
FILES MODIFIED (all under /home/ajcreator/scholarchain):
- modules/hadoop/src/main/scala/scholarchain/hadoop/{BlockLogic,Jobs,Counters,Pipeline}.scala
- tests/module-unit/hadoop/RejectsAndLimitsSuite.scala
COMMANDS RUN (one sbt at a time, foreground; edits were made by `python3 -I` exact-match replacements):
- `timeout 30m sbt hadoop/test` (before and after formatting)
- `timeout 30m sbt "adversarialTests/testOnly scholarchain.adversarial.hadoop.*"` (before and after formatting)
- `sbt hadoop/scalafmtCheck hadoop/Test/scalafmtCheck`: failed first on BlockLogic.scala, then on RejectsAndLimitsSuite.scala
- `sbt hadoop/scalafmt ...`, then `sbt hadoop/Test/scalafmt hadoop/scalafmtCheck hadoop/Test/scalafmtCheck`: 1 file reformatted by each, both checks then succeeded. No scalafmtAll.
TEST RESULTS (final run, after formatting, both exit 0):
- hadoop/test: 100/100, 0 failed, 0 ignored (was 98; 2 new). RejectsAndLimitsSuite 12, DblpParser 22, Normalizer 10, JobLogic 20, RepairDblp 10, RepairLogic 6, PipelineIntegration 18, RepairArgs 2.
- adversarial hadoop: 78/78, 0 failed. ReducerWiring 10 (including the formerly red A-1 test), CommonName 10, Unicode 8, GiantTeam 14, DuplicateId 11, DblpXmlAttack 13, MissingField 12.
- "8 suites": the folder has 8 files; 7 are suites. HadoopBlackBox.scala is the shared harness (object HadoopBlackBox and abstract PipelineSuite). All 7 ran. No adversarial file was edited.
VALIDATION RESULTS:
A-1 (fixed):
- New pure function BlockLogic.primaryRefusal. Jobs.reduceMentions counts OVERSIZE_BLOCKS once, with one reject line, for each researcher block with more than --max-block clusters. The size is the sum of the full-name parts' cluster counts. Refused parts are folded into that line and not counted or rejected again. BlockLogic.block's result shape is unchanged.
- Reason: `block <key> has N clusters > M; not compared whole, split by full name[; K parts refused: block <key>/<name> has n clusters > M; ...]`. The primary text has no `<key>|`.
- RejectRecord.of cuts reason at 500 characters, so a long part list can be cut there; N and K come first. `input` carries the same text up to 2000 characters.
- Work blocks, the hard-cap refusal, fallback counting, candidates and J07 records are unchanged.
- Observed, ReducerWiringSuite J04 (first run): OVERSIZE 1, FALLBACK_BLOCKS_REFUSED 2, REJECTED 3, 3 reject lines. CommonNameSuite: OVERSIZE 3, REJECTED 3 (wang_w with its refused part folded in, zhang_h hard cap, one work block).
Logic version: BUMPED, `hadoop-v2` to `hadoop-v3` (Pipeline.LogicVersion). Why: its rule says to bump on any change of job logic, keys or output format. J04 counters and reject output changed, and without a bump a rerun would reuse `_SUCCESS` J04 output from the old logic. Every new run ID differs from earlier ones. No schema version changed.
C18 (module-unit part done), one LocalJobRunner run of 10 works, --max-block 3, 2 reducers:
- J03 exact map: INPUT 10, ACCEPTED 10, MISSING_KEY 4, MISSING_DOI 4, DUPLICATE 2, OVERSIZE_BLOCKS 1, REJECTED 1, EXACT_CANDIDATES 1. One reject (stage, reason, input, run ID all asserted): `exact key doi:10.1000/shared holds 4 works > 3; work pairs not expanded`. Only the control DOI pair is linked.
- J04 exact map: INPUT 10, ACCEPTED 10, OVERSIZE_BLOCKS 1, FALLBACK_BLOCKS_REFUSED 2, FALLBACK_BLOCKS_COMPARED 1, REJECTED 3, BLOCKED_CANDIDATES 5. Three exact reasons: zhang_h primary, zhang_h|co:lee_k, zhang_h|y:2018. The one zhang_h candidate comes from co:quill_p.
- The existing smith_j test now asserts the exact folded reason (its counter map is unchanged: OVERSIZE 1, REJECTED 1).
N1 / K07 (J01 reject text): NOT fixed, stays CARRY. The code change is small and in Jobs.scala, but N1's brief also needs new coverage of three statuses, including a separate raw-bytes BadEncoding input, so it is more than a few lines. No J01 reject-text test was added.
Job graph, key-value contracts, partitioning and output paths: unchanged. No dataset run, so no new reject counts, largest team, pair expansion or block figures.
ASSUMPTIONS:
- "Exactly once per block above the cap" is measured in clusters (the documented trigger), not mentions.
- The work order limits verification to the two gates, so `sbt check` and the local pipeline on datasets/samples were not run.
RISKS:
- FOLLOW-UP REQUIRED (owner spark-engineer): modules/spark/src/main/scala/scholarchain/spark/RunManifest.scala:87 has `HadoopPipelines = Set("hadoop-v2")`. By code reading (not run), Spark will refuse every hadoop-v3 manifest with "hadoop pipeline 'hadoop-v3' is not one Spark reads". J07 records are unchanged, so adding "hadoop-v3" should be enough; keep "hadoop-v2" if existing Phase 08 outputs must stay readable. Needed before any new Hadoop-to-Spark run, including the 100,000-work EMR run. Spark tests use hadoop-v2 fixtures and will not catch this. It fits the spark-engineer C12/C14 round. I did not edit it.
- A repo grep of modules/entity-resolution found no pipeline-version allow-list (it records upstreamPipeline); its tests use hadoop-v2 fixtures. New Hadoop runs get new run IDs, so downstream runs must be pointed at the new manifests.
- No cloud, dataset pipeline run, mutation testing or commit. MODULE.md not edited.
MODULE.md wording for documentation-engineer (modules/hadoop/MODULE.md):
1. Run ID bullet (about lines 77-81) and Done line (about 328): `Pipeline.LogicVersion` is `"hadoop-v3"` since Phase 11 repair round 1 (A-1); it was `"hadoop-v2"` after Phase 04 repair round 1 and `"hadoop-v1"` before that.
2. Replace the "A researcher or work block above --max-block ..." bullet (about 235-237) with: "A researcher or work block above `--max-block` is not compared as a whole. Each counts once in `OVERSIZE_BLOCKS` with one reject line. A researcher block (more than `--max-block` clusters) is split by full name even when every part then fits; parts still above the cap are named in that same line (`K parts refused: ...`, cut at the 500-character reason limit) and are not counted again; their within-cluster `same_source_cluster` links remain. A block above the hard cap of 10 x `--max-block` members is refused whole, also counted once."
3. Fallback counters bullet (about 181-182) stays true; add: "the primary block that triggered fallback is counted separately in `OVERSIZE_BLOCKS`."
4. Public API: new `BlockLogic.primaryRefusal(key, results, maxBlock): Option[String]`.
5. K07/N1 remains open: J01 status rejects still print Scala Option text (the MODULE.md format at about line 155 is the intended one, not what the code produces). Also add the spark allow-list follow-up until it is closed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator routes the hadoop-v3 allow-list item to spark-engineer; documentation-engineer updates MODULE.md; test-engineer rerun; fresh code-reviewer pass on the repair diff.
