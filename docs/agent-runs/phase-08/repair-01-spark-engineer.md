HARNESS: claude-code
MODEL: opus, effort high (high tier, role default)

The `abstracts` `keyRule` text is fixed, and the one full `sbt check` for the repair round passed with exit code 0.

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 08 repair task R1-3. NIT-1: correct the `abstracts` `keyRule` description in the `s3vectors.json` load manifest. Then run one full `sbt check` covering R1-1, R1-2 and R1-3.

FILES CREATED: none in the repository. Outside it:
- scratch output: /tmp/claude-1000/-home-ajcreator-scholarchain/a1f0207b-ca0f-4079-a6c1-6c71c9de7b8a/scratchpad/sbt-check-r1.txt
- copy for the orchestrator (git-ignored): /home/ajcreator/scholarchain/target/phase-08-sbt-check-r1.txt (419 lines)

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/spark/src/main/scala/scholarchain/spark/Export.scala, lines 750-755. Only the `keyRule` string changed. New text: "rag's renderer (behind storage's DocumentRenderer port) makes the chunk IDs, not Spark: it renders template tpl-paper-abstract-v2 and uses that source version with the rendered text; one chunk is core Ids.chunk(workId, tpl-paper-abstract-v2, paper-abstract, 0, rendered text); a long abstract split into several chunks gives chunk i Ids.chunk(workId, tpl-paper-abstract-v2+<chunk policy version>+<sha256 of the whole rendered text>, paper-abstract, i, chunk text)". I checked it against the rag code: `Chunking.chunkIds` (Chunking.scala:97-120), and `PaperAbstractTemplate` plus `renderAbstract`/`assemble` (Templates.scala:134-139, 237-265), where entityId = workId, sourceVersion = tag `tpl-paper-abstract-v2`, docType `paper-abstract`.
- /home/ajcreator/scholarchain/tests/module-unit/spark/PipelineSuite.scala. No test pinned the old string. I added 4 assertions to the existing load-manifest test after the `ab` (abstracts) check: keyRule contains "rag's renderer", "tpl-paper-abstract-v2" and "several chunks", and no longer contains "ordinal 0, text".

COMMANDS RUN:
- `sbt "sparkAnalytics/scalafmt" "sparkAnalytics/Test/scalafmt"`: success, 1 source formatted in each.
- `cd /home/ajcreator/scholarchain && timeout 3000 sbt check`, run once in the background and waited on without polling. Output went to the scratch file, which I copied to target/phase-08-sbt-check-r1.txt.

TEST RESULTS (sbt check):
- Exit code 0. Wall time 395 s; sbt reported "[success] Total time: 356 s (0:05:56.0), completed Nov 24, 2026, 2:33:38 PM".
- The 7 "Passed" lines, verbatim:
  - [info] Passed: Total 120, Failed 0, Errors 0, Passed 120
  - [info] Passed: Total 66, Failed 0, Errors 0, Passed 66
  - [info] Passed: Total 184, Failed 0, Errors 0, Passed 184, Ignored 1
  - [info] Passed: Total 68, Failed 0, Errors 0, Passed 68
  - [info] Passed: Total 45, Failed 0, Errors 0, Passed 45
  - [info] Passed: Total 119, Failed 0, Errors 0, Passed 119
  - [info] Passed: Total 98, Failed 0, Errors 0, Passed 98
  - [info] markTestRun: 14 project test runs passed
- The output does not label these lines by project. Summing the per-suite "finished" lines by package gives the clear matches:
  - spark 45
  - rag 66
  - entity resolution 119 (79 + 14 + 26)
  - hadoop 98
  - adversarial hadoop 68
- The 184-test line (1 ignored) is storage: its suite lines sum to 168 with 1 ignored. The 120-test line is most likely core: its suite lines sum to 114 (95 + 19 aws). I did not track down the gap in either.
- `scholarchain.spark.PipelineSuite finished: 0 failed, 0 ignored, 8 total`, which includes the new assertions.

VALIDATION RESULTS:
- No computed value changed.
- The run ID hashes `SparkRunManifest.build`, which does not include the load manifests. They are written as text to `<base>/load-manifests/*.json`, and nothing else reads them into a hash. So the run ID and every computed output stay the same, and `LogicVersion` stays `spark-v4`.
- The Spark pipeline was not rerun, as instructed. No stage DAG, graph or metric numbers changed.

ASSUMPTIONS:
- "Multi-chunk formula" means rag's `Chunking.chunkIds` several-chunks branch as it is now.
- `<chunk policy version>` is described generically, not as `chunk-v1`, so the text stays true if the policy version changes.

RISKS:
- The `s3vectors.json` already written by earlier runs still holds the old text until the next Spark run regenerates it. I did not change it.
- If rag changes its template version or chunk ID formula, this string has to be updated by hand: spark cannot import rag, and the test pins only the substrings above.
- Notes for `modules/spark/MODULE.md` (documentation-engineer):
  - The `abstracts.keyRule` text in the load manifest now says rag's renderer makes the chunk IDs (tpl-paper-abstract-v2, multi-chunk formula).
  - There is no public API change and no logic version bump.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the orchestrator copies target/phase-08-sbt-check-r1.txt into docs/agent-runs/phase-08/output-sbt-check-r1.txt; then R1-4 (documentation-engineer), then the human gate.
