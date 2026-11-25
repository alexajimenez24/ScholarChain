HARNESS: claude-code
MODEL: opus, effort high (tier high in .agents/models.json)

R1 is implemented and `sbt rag/test` passes 96 of 96. One required value is missing: stage 5 cannot return community size, because no store the runtime reads holds it. You told me to raise missing fields as BLOCKED-ON, so that is the status. Everything else in stages 1 to 7 works on the in-memory stores and is tested. I have not loaded the real Phase 08 run into the new loader yet (see RISKS).

STATUS: BLOCKED-ON
OWNER: rag-engineer
WORK ORDER: Phase 09 task R1, stages 1 to 7: QueryPlannerAgent, ResearcherResolverAgent, CollaborationMetricsAgent, PathFinderAgent, CommunityAnalystAgent, SemanticContextAgent, EvidenceBuilderAgent. Also the versioned QueryPlan schema and the allowlist of plan operations. No cloud. Inputs: Spark run run-ccf619c509611f06 and er-v1 run run-cb5a0421c406737d. Every graph query filters on one window.

FILES CREATED:
- modules/rag/src/main/scala/scholarchain/rag/Plan.scala: AnalysisRequest, Constraints, AnalysisOptions, RetrievalOptions, WorkflowError, PlanOperations (allowlist), QueryPlanRules (schema validation), QueryPlanner
- modules/rag/src/main/scala/scholarchain/rag/Directory.scala: ResearcherEntry, NamedEntity, NameKeys, the ResearcherDirectory port and InMemoryDirectory, MetricValues, RankedId, the MetricRanking port and InMemoryMetricRanking
- modules/rag/src/main/scala/scholarchain/rag/TemplateGraph.scala: an in-memory stand-in for Neptune that answers storage's allowlisted templates; it sits behind storage's InMemoryGraphStore
- modules/rag/src/main/scala/scholarchain/rag/Agents.scala: RuntimeContext, RuntimeStores, GraphRunner, GraphCall, WindowChoice, the resolver (with Resolution, Candidate, ResolutionStatus), CollaborationMetricsAgent (with MetricRecord), PathFinderAgent, CommunityAnalystAgent (with CommunityView), SemanticContextAgent
- modules/rag/src/main/scala/scholarchain/rag/Evidence.scala: EvidenceLimits, EvidenceBuilderAgent, StageTiming, EvidenceStageResult, and EvidencePipeline, which runs stages 1 to 7
- modules/rag/src/main/scala/scholarchain/rag/LocalRuntime.scala: LocalRuntime.load, LocalRuntimeSettings, LocalRuntimeReport
- prompts/schemas/query-plan-v1.json
- tests/module-unit/rag/RagWorld.scala (fixture), PlannerSuite.scala (8 tests), StageSuite.scala (9), PipelineSuite.scala (11), LocalRuntimeSuite.scala (2)

FILES MODIFIED: none. No existing file changed; nothing outside modules/rag, prompts and tests/module-unit/rag was touched.

COMMANDS RUN (I saw the output of each):
- `timeout 900 sbt -batch rag/test`, run 5 times, each to completion:
  - Run 1: compile error. A tuple pattern on an Either inside a for-comprehension needs withFilter. Fixed.
  - Run 2: compile error. The enum fields `message` clashed with a method `message`; I renamed the method to `describe`. Fixed.
  - Run 3: passed 66 of 66 (only the existing suites; the new main code compiled).
  - Run 4: passed 95 of 95.
  - Run 5: passed 96 of 96.
- `scalafmt --test` on every file in modules/rag/src/main/scala/scholarchain/rag and tests/module-unit/rag: "All files are formatted with scalafmt :)". I formatted only my own files and never ran scalafmtAll.
- Read-only python3 -I checks of the real load manifests:
  - dynamodb.json `tables[0].source.jsonLines.rowsByPartition` exists.
  - neptune.json `coauthorEdges` and `edges` have `jsonLines.rowsByPartition`.
  - The canonical hash of run-manifests/run-ccf619c509611f06.json is run-ccf619c509611f06.

TEST RESULTS:
- `sbt rag/test`: "Passed: Total 96, Failed 0, Errors 0, Passed 96". That is 66 existing tests plus 30 new ones: PlannerSuite 8, StageSuite 9, PipelineSuite 11, LocalRuntimeSuite 2.
- Not run: the full `sbt check` and the benchmark-query suite. The work order puts both at the end of step 3 (test-engineer), and the benchmark set does not exist yet.

VALIDATION RESULTS:

Versions:
- No LLM prompt exists in R1. The planner is deterministic, so no model is called in stages 1 to 7.
- Schema: query-plan-v1 (`prompts/schemas/query-plan-v1.json`). A test checks it field for field against the encoded core QueryPlan: required keys, provenance keys, the operation and metric enums, the limit (50) and depth (6) maximums, the name pattern and the filter bounds.
- Component versions: planner-v1, plan-operations-v1, resolver-v1, metrics-agent-v1, path-agent-v1, community-agent-v1, semantic-agent-v1, evidence-builder-v1, evidence-pipeline-v1, graph-runner-v1, template-graph-v1, local-runtime-v1.
- The semantic stage reuses the Phase 08 versions semantic-context-v1, untrusted-text-v1 and graph-value-policy-v1.

Allowed plan operations (plan-operations-v1). The resolver may always use neighbors-out-v1, node-v1 and direct-coauthors-v1.

| Operation | Graph templates | Vector document types | Metric store |
|---|---|---|---|
| RESEARCHER_RANKING | node-v1 | researcher-profile | yes |
| MAX_TEAM_SIZE | node-v1 | paper-abstract | yes |
| DIRECT_COAUTHORS | direct-coauthors-v1, coauthor-pair-v1 | researcher-profile | yes |
| SHORTEST_PATH | shortest-path-d1-v1 to d6-v1, coauthor-pair-v1, node-v1 | collaboration-path | no |
| COMPONENT_LOOKUP | none | researcher-profile | yes |
| COMMUNITY_COMPARISON | direct-coauthors-v1 | collaboration-community | yes |
| BRIDGE_RANKING | direct-coauthors-v1 | collaboration-community, researcher-profile | yes |
| TEMPORAL_COMPARISON | none | researcher-profile | yes |
| TOPIC_FILTER | neighbors-in-v1, work-authors-v1, node-v1 | topic-institution-summary | yes |
| INSTITUTION_FILTER | neighbors-in-v1 | topic-institution-summary | yes |
| SEMANTIC_EXPLANATION | none | all five | no |

Graph query rules (GraphRunner):
- Only storage-built, allowlisted templates run, and only those the plan's operations allow.
- Every template that reads COAUTHORED_WITH must name exactly one configured window.
- Limits per request:
  - 250 queries;
  - 100 rows per query (min of retrieval.graphLimit and 200);
  - 5000 ms per query (neptune.queryTimeoutMs), enforced by cancelling the call;
  - 15000 ms in total (operations.synchronousDeadlineMs);
  - path depth up to min(retrieval.maxPathDepth, 6).
- Tests cover a template the plan does not allow, a window that is not configured, the query budget, a timeout, too many rows, and depth 7.
- A test records every query from three injected questions and checks four things:
  - the query text equals storage's template text;
  - every string parameter is an ID, a configured window or an allowlisted edge type;
  - every number is within limits;
  - every windowed query uses the one chosen window.

Templates used: node-v1, direct-coauthors-v1, coauthor-pair-v1, shortest-path-d1-v1 to d6-v1, neighbors-out-v1 (AFFILIATED_WITH), neighbors-in-v1 (HAS_TOPIC, AFFILIATED_WITH) and work-authors-v1. TemplateGraph also answers researcher-works-v1 and neighbors-both-v1, but no stage uses them.

Identity resolution (resolver-v1):
- Names are matched only by core's Normalize.personName key, then by name tokens in any order; never by vector similarity. A DBLP suffix such as "Wei Wang 0002" therefore matches "Wei Wang".
- No candidate: NOT_FOUND.
- Two or more candidates: always AMBIGUOUS, up to 5 listed with the total count.
- One candidate that er-v1 marked as an identity conflict: AMBIGUOUS.
- Otherwise RESOLVED, keeping identityConfidence and the unresolved-links flag so the answer can state them.
- Each candidate carries: ORCID, mention count, 3 sample mention IDs, confidence, how it matched, its affiliations and its 3 strongest coauthors in the window.
- The caller disambiguates by passing a candidate's ID in `constraints.researcherIds`.
- Each candidate also becomes one IDENTITY_RESOLUTION evidence item, carrying erPolicyVersion and erRunId.

Abstention (reported in EvidenceStageResult.abstention):
- Any named researcher that is ambiguous or not found makes the whole answer abstain. The bundle then holds only the candidates' identity evidence, and no graph query runs except the resolver's (tested).
- Structural operations that find no deterministic evidence abstain.
- A ranking that matches no researcher abstains, for example when a topic phrase matches no topic node.
- The planner refuses empty, oversized, control-character, unsupported and out-of-range questions with typed errors.

Evidence rules (EvidenceBuilderAgent; claim verification itself is R2):
- The bundle keeps three partitions: graph and metric evidence plus PUBLICATION records; identity evidence; and descriptive semantic items. An item in the wrong partition fails the build.
- Metric values are the exact stored values written as plain decimals.
- Measures Spark left empty are listed in `missingFields`, never filled in.
- A missing edge or path becomes `found=false` evidence carrying its depth and window.
- Every item and the bundle pass core Validate.
- The bundle is capped at 120 graph, 25 identity and 10 semantic items, and 300,000 bytes; every item dropped is noted.
- Semantic items keep their score and S3 pointer and are marked untrusted-data. An injected profile text stays in the semantic partition (tested).

Benchmark results: none yet. The benchmark set is test-engineer's task in step 3.

ASSUMPTIONS (each choice and why):
1. **Deterministic planner, no LLM.** The spec only says to parse the question into a typed plan. A deterministic parser costs nothing, cannot be steered by prompt injection, and is checked against the schema.
2. **Two new read ports in rag, with in-memory implementations.** ResearcherDirectory (names to researchers) and MetricRanking (top-k by a stored metric) exist because storage has neither a name index nor a ranking access pattern. Rankings take only the order from the port, then re-read every row from storage's ResearcherMetricCache and refuse any disagreement. The production adapters are storage-engineer's.
3. **Where identities come from.** The resolver reads the Researcher graph nodes of run-ccf619c509611f06, which Spark built from er-v1 run run-cb5a0421c406737d. The er-out folder holds match decisions, not identities. LocalRuntime refuses to load unless the run manifest names exactly that ER run, the policy and metric versions match, the run ID is the hash of its manifest, and every partition's row count equals the load manifest.
4. **Precomputed windows only.** A requested window that was not precomputed is answered from the smallest configured window containing it, with a note. Nothing is recomputed.
5. **Every path hop is re-read with coauthor-pair-v1**, so each hop carries weight, years, works and an evidence pointer, and the hop's edge ID is checked against the path.
6. **Topic and institution filters.** Topic and institution names are matched by name tokens against the graph nodes, then members are found through the graph, capped at 30 works and 200 neighbours. A topic match keeps only works published in the window. A phrase with no match gives an empty set, never "no filter".
7. **Bridge ranking with a primary ranking** is ranked among the primary set ("which of them").
8. **Named researchers always get their metrics read.** The core operation enum has no profile operation.
9. **Venue** is taken from constraints only and recorded in the plan, but there is no venue filter operation in the core enum, so it is not applied.
10. **TemplateGraph lives in rag main**, so test-engineer's black-box projects (which see only main code) can build the in-memory stores.
11. **LocalRuntime defaults:** the Phase 08 vector subset (8,000 rows per type) and a selectable set of windows.
12. **Semantic retrieval failure is soft:** it is noted and the bundle has no semantic items.

RISKS:
- Not done: stages 8 to 10 (R2), the full `sbt check`, the benchmark, any AWS call. I did not update STATUS.md and did not commit.
- **LocalRuntime has not run on the real run.** Only `rag/test` was allowed, and rag tests run inside the sbt JVM. Its memory and time on the full export are unmeasured. It holds every Researcher node, AUTHORED edge and selected window, and it runs storage's two-pass vector ingestion. Use a window subset first.
- The planner recognises names only in explicit forms: quoted, "between X and Y", "path from X to Y", "coauthors of X", "has X collaborated with Y", "does X have/belong...". It finds topics only as "in <phrase>", and institutions only as "at <... University/Institute/...>". Benchmark questions should quote names or pass them in constraints.
- When several shortest paths tie, TemplateGraph picks one deterministically; live Neptune may pick another of the same length.
- Semantic retrieval has no runner deadline.
- A timed-out store call that ignores interruption keeps running on a daemon thread.
- Neighbouring communities are taken only from the 10 strongest retrieved edges.
- There is no global component record, only each researcher's componentId and componentSize. The pipeline adds a note when a component question names no researcher.
- PlannerSuite and PipelineSuite contain the string `DETACH DELETE` as an injection payload. guard-dangerous blocks any Bash command whose text contains it, so do not grep for that literal.
- Notes for modules/rag/MODULE.md (documentation-engineer): the new public API is the types and functions listed under FILES CREATED, plus `EvidencePipeline.run(queryId, AnalysisRequest): Either[WorkflowError, EvidenceStageResult]` and `LocalRuntime.load(cfg, runManifest, expectedErRunId, settings)`. Done: stages 1 to 7. Not done: stages 8 to 10, community size, the production adapters for the two ports.

MANUAL ACTION REQUIRED: none for R1. The human may decide whether to carry the community-size gap forward or have it fixed now.

BLOCKED-ON OWNER: spark-engineer for exporting community records (community size, cross-community edge ends) as JSON lines, and storage-engineer for loading them and serving a read access. Two items are not blocking R1 but are needed for production and are carried forward to storage-engineer: a name-lookup access pattern (for ResearcherDirectory) and a per-window metric ranking access pattern (for MetricRanking).

BLOCKED-ON EVIDENCE (observed):
- The run's jsonl folders are exactly coauthor-edges, graph-edges, graph-nodes, paper-abstracts, researcher-metrics and semantic-summaries. There is no communities or components folder; those exist only as Parquet.
- load-manifests/dynamodb.json maps only `('dynamo.researcherMetricTable', 'researcher-metrics')`.
- core ResearcherMetrics has no communitySize field.
- Community size appears only in descriptive summary text, for example "Members: 10.", which may not support a number.

NEXT GATE: The orchestrator routes the community-size request (spark-engineer, then storage-engineer) or carries it forward to the human. Then task R2 (rag-engineer, stages 8 to 10), then step 3 (test-engineer), whose first real LocalRuntime.load on run-ccf619c509611f06 should start with a window subset.
