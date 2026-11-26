HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 11 repair round 1, C20 (Spark accepts Hadoop pipeline hadoop-v3).
FILES CREATED: none in the repository (this report and sbt-c20.log are in the scratchpad)
FILES MODIFIED: modules/spark/src/main/scala/scholarchain/spark/RunManifest.scala (Upstream.HadoopPipelines = {hadoop-v2, hadoop-v3}, with the reason in a comment); modules/spark/src/main/scala/scholarchain/spark/Schemas.scala (comment); tests/module-unit/spark/RunManifestSuite.scala (1 new test)
COMMANDS RUN: `git diff HEAD -- modules/hadoop modules/core` (stat and content); grep of the spark and entity-resolution sources for pipeline pins; `timeout 30m sbt sparkAnalytics/scalafmt sparkAnalytics/Test/scalafmt sparkAnalytics/test` (one run, exit 0; moved to the background by the harness, and I waited without polling).
TEST RESULTS: sparkAnalytics/test: Total 48, Failed 0, Errors 0, Passed 48 (47 after C12/C14, plus 1 new). RunManifestSuite 8. Full `sbt check` was not run (work-order gate; the stop hook asked for it, so that is for the orchestrator).
VALIDATION RESULTS:
- v2 and v3 J07 records match.
  - hadoop-v3 changes only these files: BlockLogic.scala (a new primaryRefusal, doc text), Jobs.scala (BlockingReducer, J04 counters and reject lines), Counters.scala (doc) and Pipeline.scala (the version string).
  - Core Model.scala and SchemaVersions.scala are unchanged, and the only core diff is the C12 config field. Spark reads only J07 export, J02 abstracts and ER decisions, never J04 output.
  - So I accept hadoop-v3 and keep hadoop-v2.
- New test: hadoop-v3 is accepted, hadoop-v2 is still accepted, and hadoop-v9 is refused with the exact message "hadoop pipeline 'hadoop-v9' is not one Spark reads (hadoop-v2, hadoop-v3)". v2 and v3 inputs also give different Spark run IDs.
- ER manifest: Spark reads only stage, upstreamRunId, snapshot, policyVersion and policySha256 from it. ER writes `upstreamPipeline`, but Spark neither reads nor pins it, so nothing else needs changing.
- Versioning: no further bump. The rule bumps on a logic change, and for any given input the outputs are the same. The Hadoop pipeline is already part of the Spark run manifest, so run IDs separate by upstream. LogicVersion stays spark-v5, already bumped this round for C12/C14 and not yet committed.
ASSUMPTIONS: hadoop-engineer's statement that J07 records are unchanged was checked against the diff against HEAD, not against a v3 run output (no runs were allowed).
RISKS: No Hadoop-v3 to Spark run was made; the first one will be local or EMR. PipelineSuite fixtures still use hadoop-v2. MODULE.md note (documentation-engineer): Upstream.HadoopPipelines now accepts hadoop-v2 and hadoop-v3; Spark tests 48; this closes the hadoop allow-list follow-up.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer rerun and a fresh code-reviewer pass on the repair diff.
