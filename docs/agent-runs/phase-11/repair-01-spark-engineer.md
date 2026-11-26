HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 11 repair round 1, C12 (enforce graph.identityCutMaxClusterLinks) and C14 export part (B-15: communities JSON-lines export and load manifest).
FILES CREATED: none in the repository (this report is in the scratchpad)
FILES MODIFIED:
- modules/spark/src/main/scala/scholarchain/spark/Canonical.scala (C12)
- modules/spark/src/main/scala/scholarchain/spark/Export.scala (C14: Export.CommunitiesJson, dynamodb.json `communities` block)
- modules/spark/src/main/scala/scholarchain/spark/SparkPipeline.scala (communities written with its JsonSpec)
- modules/spark/src/main/scala/scholarchain/spark/RunManifest.scala (LogicVersion spark-v4 -> spark-v5)
- tests/module-unit/spark/CanonicalSuite.scala, ExportSuite.scala, PipelineSuite.scala
COMMANDS RUN: `timeout 30m sbt sparkAnalytics/scalafmt sparkAnalytics/Test/scalafmt sparkAnalytics/test` (one run, exit 0; the harness moved it to the background and I waited without polling); git status/diff of my paths.
TEST RESULTS: sparkAnalytics/test: Total 47, Failed 0, Errors 0, Passed 47 (45 before; +1 CanonicalSuite, +1 ExportSuite). Suites: Export 5, Canonical 7, Collaboration 3, WindowAnalytics 5, RunManifest 7, Temporal 2, GraphAlgorithms 6, Pipeline 8, PathSelection 4. The full `sbt check` was not run: the work order sets the gate and allows only one sbt at a time (the stop hook asked for it; for the orchestrator).
VALIDATION RESULTS:
- C12 choice: refuse all accepted links of an over-bound contradicted cluster, not fail the job. Under-merge is preferred to over-merge, and the run stays loadable.
  - Links are counted per contradicted label with an aggregate (checkpointed) before any collect_list. Over-bound labels are anti-joined out of both collect_lists (links and in-cluster contradictions), so their row is never built.
  - Their links go to `refused`, their members keep their own ID as label, and every member is flagged `identityConflict`. Uncontradicted clusters are not limited. The bound is inclusive.
- New report keys per prefix (researcher, work): `<p>CutMaxClusterLinks`, `<p>LargestContradictedClusterLinks`, `<p>ClustersOverCutBound`, `<p>DecisionLinksRefusedOverCutBound` (also counted in `<p>DecisionLinksRefusedByCut`). They are counters, not invariants, so they never cause exit 3.
- Known graph (new CanonicalSuite test): K1 has 3 links (UNRESOLVED), K5 and K3 have 4 links each (CONFLICT; two ORCID iDs), K4 is an uncontradicted 4-link star.
  - Bound 3: K1 is cut as now; K5 and K3 give ClustersOverCutBound 2 and RefusedOverCutBound 8; RefusedByCut 9, Applied 6; K4 stays merged.
  - Bound 4: all clusters are exactly at the bound and are cut normally (0, 0, 3 refused, 12 applied).
  - Every invariant_*ContradictionsMerged_*, the several-ORCIDs invariant and the not-converged invariant are 0 at both bounds.
  - The existing cut test now passes bound 3 (each cluster exactly at the bound): all its old expectations are unchanged and over-bound is 0.
  - Canonical.build reads the config value (100000) and reports largest 4, over-bound 0.
- C14: `communities` is now in the JSON-lines export, with invariant_jsonLinesRowsDiffer_communities and invariant_jsonLinesIdMismatch_communities added automatically.
  - ExportSuite, graph G: 5 lines; byPartition {community 4, cross_community_edge 1}; idMismatches 0; strict decode plus Validate.evidence on every line.
  - communitySize by member: n1 4, n5 3, n8 2, n10 1. The cross row has researcherIds [n4, n5], communityA and communityB equal to the n4 and n5 communities, jointPublicationCount 1, and no communitySize.
  - PipelineSuite: 4 rows, (window, size) = (1950-2026, 1), (1950-2026, 3), (2015-2026, 1), (2015-2026, 3). dynamodb.json `communities.source.jsonLines` equals the s3.json block, and dynamodb `tables` still has 1 entry.
- STORAGE LOAD CONTRACT (C14):
  - Files: `<run>/jsonl/communities/windowLabel=<w>/recordKind=<community|cross_community_edge>/part-*.json`, UTF-8, uncompressed, one row per line, nulls written; `_SUCCESS` and checksum files are not data. Parquet copy: `<run>/communities/`, same partitions.
  - Line = core EvidenceItem (evidence-v1) plus two extra top-level fields, `windowLabel` and `recordKind` (the partition columns). Drop exactly those two, then decode `JsonLines.decodeValid[EvidenceItem](_, Validate.evidence)`.
  - evidenceType is COMMUNITY_RECORD and the row identifier is `evidenceId` (it hashes the window). `score` is 0.0, `text`, `textHash`, `chunkId`, `workId` and `graphPath` are null, and `sourceUri` is the table URI.
  - Yes, cross_community_edge rows are in the SAME table (own partition folder). Filter `values.recordKind == "community"`, or read only the `recordKind=community` folders.
  - community `values` keys: recordKind, communityId, communitySize, internalEdgeCount, crossCommunityEdgeCount, window, method, maxIterations, converged (omitted when unknown). All values are strings, e.g. communitySize "3"; researcherIds is empty.
  - cross_community_edge `values` keys: recordKind, edgeId, communityA, communityB, jointPublicationCount, window; researcherIds [researcherA, researcherB].
  - Join to ResearcherMetrics on (communityId, window); window == windowLabel.
  - Manifest: s3.json `tables[communities].jsonLines`, and the new dynamodb.json top-level `communities` block. Its `source` is the same table JSON (path, files, rows, rowsByPartition, coreRecord, extraFields, identifierColumns), plus evidenceType, recordKindField, recordKinds, identifierField "evidenceId", windowField "values.window" and a note. It has no tableConfigKey: storage chooses the target table. dynamodb `tables` is unchanged.
- Versioning: LogicVersion is bumped to spark-v5 (code rule: bump on any logic change; C12 changes identity output when it triggers, C14 adds output), so every new run ID changes. The identity policy version (er-v1, owned by entity-resolution and written into provenance) and graph.policyVersion graph-v1 (core) are not mine and stay as they are. metricVersion is unchanged.
- Partition and shuffle notes: C12 adds one groupBy shuffle (link counts) and two small aggregates collected to the driver (one row each). Nothing else is collected.
ASSUMPTIONS: "Links" are the accepted links after unknown-endpoint drops (domain-engineer's definition). Refuse-all rather than job failure, for the reason above.
RISKS:
- Not done: no local pipeline run and no cloud run (per the work order); full sbt check not run (orchestrator/test-engineer).
- Within-bound clusters still collect their in-cluster contradiction list without a bound (bounded in practice by ER block pairs).
- Refuse-all splits a large contradicted cluster into singletons, so many identityConflict flags. The EMR run must check `researcherClustersOverCutBound`.
- PipelineSuite has no cross_community_edge rows; only ExportSuite covers them.
- MODULE.md notes (documentation-engineer): Canonical.constrainedClusters has a new `maxClusterLinks: Int` parameter after maxIterations; new `Export.CommunitiesJson`; new dynamodb.json `communities` block; LogicVersion spark-v5; spark tests 47. Close the "no config bound" known issue (bound enforced, refuse-all policy). The Export scaladoc JSON-lines table list now includes communities. docs/spark-jobs.md needs the 4 new report keys.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: storage-engineer loads `communities` per the contract above (C14 load); rag-engineer reads it (C14 read); then test-engineer rerun and a fresh review.
