HARNESS: claude-code
MODEL: sonnet, effort medium (tier standard, .agents/models.json); runtime reports claude-sonnet-5-5

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 11 repair round 1, item C12 (config bound on a contradicted identity cluster). C14 not touched.
FILES CREATED: none in the repository (this report is in the scratchpad)
FILES MODIFIED:
- modules/core/src/main/scala/scholarchain/core/Config.scala
- modules/core/src/main/resources/scholarchain/config/scholarchain-v1.yaml
- tests/module-unit/core/ConfigSuite.scala
COMMANDS RUN: timeout 30m sbt core/scalafmt core/Test/scalafmt core/test (run twice, one sbt at a time, foreground; the second after a test title and comment-level fix); grep/sed reads; git diff, git status
TEST RESULTS: core/test: Total 123, Failed 0, Errors 0, Passed 123 (was 120 per MODULE.md; 3 new). ConfigSuite 28/28.
VALIDATION RESULTS:
- FOR SPARK-ENGINEER: cfg.graph.identityCutMaxClusterLinks: Int (last field of GraphConfig, no code default).
  - Range 1..1000000 (ConfigLoader.MaxIdentityCutClusterLinks); shipped value 100000.
  - Meaning, INCLUSIVE: a contradicted cluster with links <= bound is cut as now; links >= bound+1 is over the bound.
    "Links" = accepted links of that cluster as collected into the one row (after unknown-endpoint drops).
    Uncontradicted clusters are not limited. Applies per cluster, to researcher and work clustering alike.
  - Override: SCHOLARCHAIN__GRAPH__IDENTITYCUTMAXCLUSTERLINKS.
- Errors: "graph.identityCutMaxClusterLinks must be >= 1 (is 0)"; "... must be <= 1000000 (is 1000001)"; a missing key gives "config does not match the schema: ...".
- Tests: shipped value; missing key (with unchanged-file control); 0, -1, 1000001, 3000000000 (clamped), 2.5 rejected; 1, 250, 1000000 accepted.
- Precedence unchanged: SCHOLARCHAIN__ override, then placeholder, then file literal.
- Why 100000:
  - The Phase 05/06 real sample has 267 accepted researcher and 71 accepted work links IN TOTAL, so no cluster of it can exceed the bound.
    PipelineSuite and the known-graph tests build small hand-written fixtures (e.g. PipelineSuite has two accepted researcher links in its er-v1 file); link counts of other suites were not enumerated one by one. Spark suites were not run.
  - For scale: all pairs of one 200-member block (entityResolution.maxBlockSize) is 19,900, about a fifth of the bound.
  - Memory, an ESTIMATE, not measured: about 500 bytes of heap per link in the UDF gives about 50 MB per row at the bound, about 500 MB at the ceiling, per concurrent task.
- Ceiling 1000000: a safety constant in the validator (like the port range), so no override can make the bound effectively unlimited.
- Versions: configVersion stays config-v1 and no schema version changes. The config is not a wire record, there is one shipped file, an older file lacking the key fails loudly, and the componentMaxIterations change set a no-bump precedent for values.
  This one adds a required key, so a bump was arguable; I kept v1 because no other config file exists in the repository.
  graph.policyVersion stays graph-v1 (it is hashed into edge IDs; a bump would change every edge ID although output under the bound is identical). metricVersion unchanged.
- Run ID: the Spark manifest writes the whole graph section (RunManifest.build), so every new Spark run ID changes.
- Compile ripple: none found by grep. No code outside the case class constructs GraphConfig positionally or pattern-matches it; others use the loader or .copy.
  tests/module-unit/spark/RunManifestSuite.scala:149 reads GraphConfig from a freshly built manifest, so it keeps working. A manifest written before this change would no longer decode AS GraphConfig; grep found no main-code reader that does so.
  Only core was compiled and tested; the other modules were not.
ASSUMPTIONS:
- An inclusive bound matches the brief ("exactly at the bound is cut as now; bound+1 is refused").
- Over-bound behavior (refuse all the cluster's accepted links, or fail the job) is spark-engineer's choice, per the brief.
RISKS:
- Not done: no enforcement yet; the field has no effect until spark-engineer reads it. No edit to spark.
- Largest contradicted cluster at 25k and 100k works was not measured (no figure found in the phase notes searched). Accepted researcher links in total are 108,154 (25k) and 487,091 (100k), so the bound does not exclude a trigger by totals alone.
  spark-engineer should report <prefix>ClustersOverCutBound and the refused links, and the EMR run should be checked for a nonzero count.
- Policy: when the bound triggers, identity output differs although graph.policyVersion is the same. The run ID records the bound value. spark-engineer should decide whether its identity policy / LogicVersion needs a bump for the new rule.
- Notes for modules/core/MODULE.md (documentation-engineer): new field GraphConfig.identityCutMaxClusterLinks (1..1000000, shipped 100000); new constant ConfigLoader.MaxIdentityCutClusterLinks; core tests 123; config-v1 unchanged.
  Known: a missing key still gives the unclear "TraceException" reason (existing known issue).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: spark-engineer enforces the bound (C12 spark part), then test-engineer rerun and fresh review.
