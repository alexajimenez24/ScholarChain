# Module `rag`

## Purpose

Phase 08 scope: deterministic rendering templates, the chunk policy, the embedding
version rule and the compact semantic-context format for the synthesis agent.
Phase 09 scope: the runtime collaboration workflow, stages 1 to 10 (planner, resolver,
metrics, path finder, community analyst, semantic context, evidence builder, answer
synthesis, claim verification and audit), `collaboration-workflow-v1`.
The runtime prompts live under `prompts/`.
Workflow description and benchmark results:
[docs/runtime-workflow.md](../../docs/runtime-workflow.md).
Full description: [docs/embeddings.md](../../docs/embeddings.md).

## Owner and paths

- Owner: rag-engineer (documentation-engineer owns only this file).
- sbt project: `rag`; folder: `modules/rag`.
- Sources: `modules/rag/src/main/scala/scholarchain/rag/`: `Chunking`,
  `EmbeddingVersion`, `Templates` and `SemanticContext` (Phase 08); `Plan`,
  `Directory`, `TemplateGraph`, `Agents`, `Evidence`, `LocalRuntime`, `Synthesis`,
  `Llm`, `Verification` and `Workflow` (Phase 09).
- Unit-test root: `tests/module-unit/rag/` (never `src/test`): `ChunkingSuite`,
  `EmbeddingVersionSuite`, `TemplateSuite`, `IngestionConformanceSuite`,
  `SemanticContextSuite` and `RetrievalSmokeSuite` (Phase 08); `PlannerSuite`,
  `StageSuite`, `PipelineSuite`, `LocalRuntimeSuite`, `SynthesisSuite`,
  `VerificationSuite`, `WorkflowSuite` and `BedrockLlmSuite` (Phase 09), with the
  helpers `RagWorld`, `RenderFixtures`,
  `SparkShapedRun`, `SmokeFixture`, `RealRowsRender` and `LocalIngestionRun`.
- Schemas: `prompts/schemas/semantic-context-v1.json`,
  `prompts/schemas/query-plan-v1.json` and `prompts/schemas/answer-synthesis-v1.json`.
- Prompt: `prompts/runtime/answer-synthesis-v1.md`.
- Black-box tests (test-engineer): `tests/adversarial/scholarchain/adversarial/rag/`
  (including `WorkflowRepairGateSuite`) and `tests/benchmark/`.

## Public API

Package `scholarchain.rag`.
Sources: the Phase 08 reports `step-01-rag-engineer-report.md`,
`step-01b-rag-engineer-report.md` and `step-02-rag-engineer-report.md` in
`docs/agent-runs/phase-08/`.

- Rendering: `SemanticRenderer(cfg: VectorConfig, policy = ChunkPolicy.V1):
  Either[String, SemanticRenderer]`.
  It extends storage's `DocumentRenderer` port and uses storage's `RenderedChunk` and
  `RenderedDocument`.
  Methods `renderSummary(EvidenceItem)`, `renderAbstract(PaperAbstract)`,
  `templateVersions` and `SemanticRenderer.conflicts(docs)`.
- `Templates.Versions`, `Templates.summary`, `Templates.conforms`.
  Template versions: tpl-researcher-profile-v1, tpl-collaboration-community-v1,
  tpl-collaboration-path-v1, tpl-topic-institution-summary-v1 and
  tpl-paper-abstract-v2 (v1 wrote the work ID into the text and is retired).
- Chunking: `ChunkPolicy.V1` (chunk-v1: 2,000 UTF-16 units, 200 overlap, 32 chunks),
  `Chunking.spans` and `Chunking.chunkIds`.
- Embedding versions: `EmbeddingModelVersion.of`, `fromConfig`, `parse`, `decide`,
  `nextIndexName`, `EmbedDecision` and `StoredEmbedding`.
  `EmbeddingModelVersion.id` delegates to storage's `EmbeddingVersion.id`
  (`<modelId>@<dimension>`; local `hash-embed-v1@256`).
- Semantic context (`SemanticContext.scala`):
  - Types `SemanticContextItem` (with `toEvidence`), `SemanticContext` (with `toJson`)
    and `ContextProvenance`.
  - `ContextBudget` and `ContextBudget.V1` (10 items, 2,000 characters per item, 8,000
    in total).
  - `UntrustedText` (`sanitize`, `bound`), the sanitizer `untrusted-text-v1`.
  - `GraphValuePolicy` (`graph-value-policy-v1`) with `Descriptive` and
    `maySupportGraphValue`.
  - Query types `ContextFilter`, `ContextTarget`, `ContextQuery`, `DroppedHit` and
    `SemanticRetrieval`.
  - `SemanticContextRetriever` with `apply`, `retrieve`, `assemble` and `fromHit`.
    Limits `MaxQueryChars` 2,000, `MaxTargets` 10, `MaxTopK` 20 and `MaxObjectBytes`
    65,536.
- Format `semantic-context-v1`: `{format, trust: "untrusted-data", embeddingModel,
  items}`; `score` is the only number.
- Test-scope runner: `scholarchain.rag.LocalIngestionRun` (in `tests/module-unit/rag/`).

### Phase 09 workflow API (stages 1 to 10, `collaboration-workflow-v1`)

Package `scholarchain.rag` unless a package is named.
Every signature below was read from `modules/rag/src/main/scala/scholarchain/rag/`
(`Plan`, `Agents`, `Evidence`, `Synthesis`, `Llm`, `Workflow`, `LocalRuntime`).
Types from `scholarchain.core` (`Model.scala`) are marked core.
The case classes derive uPickle `ReadWriter`, so `JsonLines.encode` and `decode` work.

#### 1. Request types (`Plan.scala`)

- `Constraints(yearFrom: Option[Int] = None, yearTo: Option[Int] = None,
  topics: Vector[String] = Vector.empty, institutions: Vector[String] = Vector.empty,
  venues: Vector[String] = Vector.empty, researchers: Vector[String] = Vector.empty,
  researcherIds: Vector[String] = Vector.empty)`.
  `researcherIds` is the disambiguation path: canonical IDs, validated as IDs, never
  parsed as text.
- `AnalysisOptions(rankingMetric: Option[CollaborationMetric] = None,
  includeTeamSize = true, includeBridgeMetrics = true, includeCommunities = true,
  includePaths = true)`.
- `RetrievalOptions(graphLimit: Int = 100, vectorLimit: Int = 30, maxPathDepth: Int = 6,
  limit: Int = 10)`.
- `AnalysisRequest(question: String, constraints: Constraints = Constraints(),
  analysis: AnalysisOptions = AnalysisOptions(),
  retrieval: RetrievalOptions = RetrievalOptions())`.
- `CollaborationMetric` (core) values: `PUBLICATION_COUNT`, `DISTINCT_COAUTHOR_COUNT`,
  `TOTAL_COAUTHOR_OCCURRENCES`, `AVERAGE_COAUTHORS_PER_PAPER`,
  `MAXIMUM_COAUTHORS_ON_ONE_PAPER`, `WEIGHTED_DEGREE`, `BRIDGE_SCORE`,
  `PARTICIPATION_COEFFICIENT`, `CROSS_COMMUNITY_DEGREE`, `CORENESS`.
- `WorkflowError` is an enum with the cases `InvalidRequest(message)`,
  `Unsupported(message)`, `Downstream(stage, message)`, `Timeout(stage, millis: Long)`
  and `BudgetExceeded(stage, message)`; `describe: String` renders it.

#### 2. Running the workflow

- `ScholarChainConfig` (core) comes from `ConfigLoader.load(env: Map[String, String] =
  processEnvironment): Either[String, ScholarChainConfig]`.
  The local config used by the unit tests is `LocalIngestion.localConfig():
  Either[String, ScholarChainConfig]` (`scholarchain.storage`).
- `LocalRuntime.load(cfg: ScholarChainConfig, runManifest: java.nio.file.Path,
  expectedErRunId: String, settings: LocalRuntimeSettings = LocalRuntimeSettings()):
  Either[String, LocalRuntime]`.
  `runManifest` is the path `<out>/run-manifests/<run-id>.json`; the file name without
  `.json` must be the hash of its text.
  The manifest must name `expectedErRunId` and carry the config's ER policy, graph policy
  and metric versions.
  Every partition's row count must equal its load manifest, or the result is a `Left`.
- `LocalRuntimeSettings(windows: Option[Set[String]] = None,
  vectorRowsPerType: Option[Int] = Some(8000))`.
  `windows` holds window labels such as `2015-2026`, each configured; `None` loads every
  configured window.
  `vectorRowsPerType` keeps the smallest-SHA-256 rows per document type; `None` ingests
  all rows.
- `LocalRuntime(ctx: RuntimeContext, stores: RuntimeStores,
  retriever: SemanticContextRetriever, report: LocalRuntimeReport)`.
  - `LocalRuntimeReport(graphRunId, erRunId, snapshot, windows: Vector[String],
    rows: Vector[(String, Long)], vectorChunks: Long)`; `rows` keys look like
    `graph-nodes/label=Researcher` and `coauthor-edges/windowLabel=2015-2026`.
  - `pipeline(limits: EvidenceLimits = EvidenceLimits()): EvidencePipeline` runs stages
    1 to 7.
  - `workflow(synthesizer: Synthesizer = DeterministicSynthesizer,
    audit: AuditLog = new InMemoryAuditLog, limits: EvidenceLimits = EvidenceLimits()):
    CollaborationWorkflow`.
- `EvidenceLimits(maxDeterministic: Int = 120, maxIdentity: Int = 25,
  maxSemantic: Int = 10, maxBytes: Int = 300000)`.
- `RuntimeContext(cfg, graphRunId: String, snapshot: String, erRunId: String)`; run IDs
  must be `run-<16 hex>` and the snapshot `YYYY-MM-DD`, or construction throws.
- `RuntimeStores(kv: KeyValueStore, graph: GraphStore, vectors: VectorStore,
  objects: ObjectStore, embedder: Embedder, directory: ResearcherDirectory,
  ranking: MetricRanking, communities: Option[CommunityReader] = None)`.
  `CommunityReader` is storage's port; with `None` every community size is reported as
  missing.
- `new CollaborationWorkflow(ctx, stores, retriever: SemanticContextRetriever,
  synthesizer: Synthesizer, audit: AuditLog, limits: EvidenceLimits = EvidenceLimits(),
  clock: () => Long = () => System.currentTimeMillis())`.
  `run(queryId: String, req: AnalysisRequest): Either[WorkflowError, WorkflowResult]`.
  The field `evidence: EvidencePipeline` runs stages 1 to 7 alone with
  `run(queryId, req): Either[WorkflowError, EvidenceStageResult]`.
- `CollaborationWorkflow.Version` is `collaboration-workflow-v1`; the identity caveat
  floor is `ConfidenceFloor = 0.9`.

#### 3. Results

- `WorkflowResult(answer: AnswerResponse, evidence: EvidenceStageResult,
  synthesis: SynthesisResult, audit: AuditRecord)`.
- `AnswerResponse` (core): `schemaVersion, queryId, summary: String,
  researchers: Vector[ResearcherAnswer], claims: Vector[AnswerClaim], window: TimeWindow,
  abstained: Boolean, abstentionReason: Option[String], evidenceBundleId: String,
  verification: VerificationStatus, promptVersion, modelVersion, provenance`.
  The deterministic path sets `promptVersion` to `deterministic-synthesis-v1` and
  `modelVersion` to `none`.
  A model answer sets them to the prompt version and the model version.
- `AnswerClaim` (core): `claimId, text, evidenceIds: Vector[String],
  verificationStatus: VerificationStatus, uncertainty: Option[String]`.
- `VerificationStatus` (core): `VERIFIED`, `PARTIALLY_SUPPORTED`, `UNSUPPORTED`,
  `CONFLICTING`.
  The answer keeps VERIFIED claims and weakened PARTIALLY_SUPPORTED claims.
  UNSUPPORTED and CONFLICTING claims are removed and appear only in
  `AuditRecord.removedClaims`.
- `ResearcherAnswer` (core): `researcherId, displayName, publicationCount: Long,
  distinctCoauthorCount: Long, weightedDegree: Double, averageCoauthorsPerPaper: Double,
  maximumCoauthorsOnOnePaper: Long, maximumTeamWorkIds: Vector[String],
  componentId: String, communityId: Option[String], bridgeMetric: Option[BridgeMetric],
  coreness: Option[Int], identityConfidence: Double, claimIds: Vector[String]`.
  `BridgeMetric(name, value: Double)` names `sampled-brandes-betweenness`.
  The researcher list is empty when stages 1 to 7 abstained.
- `EvidenceStageResult(plan: QueryPlan, window: TimeWindow, windowNote: Option[String],
  resolutions: Vector[Resolution], named: Vector[MetricRecord],
  ranked: Vector[MetricRecord], bundle: EvidenceBundle, notes: Vector[String],
  abstention: Option[String], graphCalls: Vector[GraphCall],
  semanticDropped: Vector[DroppedHit], stages: Vector[StageTiming])`.
- `EvidenceBundle` (core): `schemaVersion, bundleId, queryId,
  deterministic: Vector[EvidenceItem], semantic: Vector[EvidenceItem],
  identity: Vector[EvidenceItem], graphRunId, metricVersion, provenance`;
  `all` is `deterministic ++ identity ++ semantic` and `byId` maps evidence ID to item.
  - `deterministic` holds METRIC_RECORD, COAUTHOR_EDGE, GRAPH_PATH, COMPONENT_RECORD,
    COMMUNITY_RECORD and PUBLICATION items.
  - `identity` holds only IDENTITY_RESOLUTION items.
  - `semantic` holds only SEMANTIC_PROFILE and TEXT_CHUNK items.
  - An item in the wrong partition fails the build.
- `EvidenceItem` (core): `schemaVersion, evidenceId, evidenceType: EvidenceType,
  workId: Option[String], researcherIds: Vector[String], sourceUri, chunkId,
  graphPath: Option[Vector[String]], score: Double, values: Map[String, String],
  text: Option[String], textHash: Option[String], provenance`.
  Metric items carry `values("kind") == "researcher-metrics"`.
- `Resolution(query, status: ResolutionStatus, candidates: Vector[Candidate],
  totalCandidates: Int, reason, contextWindow)` with `researcherId: Option[String]`
  (set only when `RESOLVED`).
  `ResolutionStatus` is `RESOLVED`, `AMBIGUOUS` or `NOT_FOUND`.
  `Candidate(researcherId, displayName, orcid, mentionCount, sourceMentionIds,
  identityConfidence, unresolvedLinks, identityConflict, matchedBy, ..., coauthors)`.
- `SynthesisResult(output: SynthesisOutput, synthesizer, promptVersion,
  promptSha256: Option[String], modelVersion, inputTokens, outputTokens,
  attempts: Vector[SynthesisAttempt], fallback: Option[String],
  context: Option[ContextUse])`; `byModel` is true when `synthesizer` is
  `LlmSynthesizer.Version` (`llm-synthesis-v2` since Phase 11).
- `SynthesisOutput(schemaVersion, summary, abstain: Boolean,
  abstentionReason: Option[String], claims: Vector[DraftClaim])` and
  `DraftClaim(claimId, text, evidenceIds, uncertainty: Option[String])`.
- `AuditRecord` (`audit-record-v1`) fields, in order: `schemaVersion, queryId,
  plan: QueryPlan, answeredWindow: TimeWindow, windowNote: Option[String],
  resolutions: Vector[ResolvedIdentity], resolvedResearcherIds: Vector[String],
  evidenceBundleId, evidenceIds: Vector[String], graphRunId, erRunId, erPolicyVersion,
  metricVersions: Vector[String], componentVersions: Map[String, String], synthesizer,
  promptVersion, promptSha256: Option[String], outputSchemaVersion, modelVersion,
  inputTokens: Option[Long], outputTokens: Option[Long],
  synthesisAttempts: Vector[SynthesisAttempt], synthesisFallback: Option[String],
  context: Option[ContextUse], graphCalls: Vector[GraphCall], semanticDropped: Int,
  stages: Vector[StageTiming], latencyMs: Long, claimChecks: Vector[ClaimCheck],
  removedClaims: Vector[RemovedClaim], summaryReplaced: Option[String],
  verification: VerificationStatus, abstained: Boolean,
  abstentionReason: Option[String], answerHash: String, notes: Vector[String]`.
  - `ClaimCheck(claimId, status, reasons: Vector[String], facts: Int, supported: Int,
    missingEvidenceIds: Vector[String])`.
  - `RemovedClaim(claimId, text, status, reasons: Vector[String])`.
  - `ResolvedIdentity(query, status, researcherId: Option[String],
    candidateIds: Vector[String], totalCandidates: Int)`.
  - `StageTiming(stage, millis, status)`; stage names include `plan`, `resolve`,
    `evidence`, `synthesis`, `verification` and `audit`.
  - `SynthesisAttempt(attempt: Int, status, reason: Option[String],
    replySha256: Option[String], maxOutputTokens, inputTokens, outputTokens)`.
  - The record holds no prompt text, no reply text and no graph query text.
- The workflow abstains when stages 1 to 7 abstain (an ambiguous or unknown name, or no
  deterministic evidence), when no claim survives verification, or when the synthesis
  output abstains.
  On a stage-1-to-7 abstention the deterministic synthesizer lists candidates and no
  model is called.

#### 4. Synthesis and the model port

- `trait Synthesizer { def synthesize(e: EvidenceStageResult): SynthesisResult }`.
  `DeterministicSynthesizer` is the object implementation (`Version`
  `deterministic-synthesis-v1`).
- `trait LlmPort { def modelVersion: String;
  def complete(prompt: String, maxOutputTokens: Int): Either[String, LlmReply] }` and
  `LlmReply(text: String, inputTokens: Option[Long], outputTokens: Option[Long])`.
- `new ScriptedLlm(steps: Vector[String => Either[String, LlmReply]],
  modelVersion: String = ScriptedLlm.Version)`.
  - Step `i` answers the `i`-th call and receives the rendered prompt; a call past the
    script returns `Left("the script has no more replies")`.
  - `ScriptedLlm.replies(texts: String*)` returns a model that answers each call with
    the next text, with token usage estimated from lengths.
  - `prompts: Vector[String]` and `maxTokens: Vector[Int]` record every prompt and
    output-token cap received.
  - `ScriptedLlm.Version` is `scripted-fake-v1`.
- A reply text must be one `answer-synthesis-v1` JSON object, for example
  `{"schemaVersion":"answer-synthesis-v1","summary":"...","abstain":false,
  "abstentionReason":null,"claims":[{"claimId":"claim-1","text":"...",
  "evidenceIds":["<id>"],"uncertainty":null}]}`.
  Unknown, missing or null keys are refused; the schema is
  `prompts/schemas/answer-synthesis-v1.json`.
- `SynthesisPrompt.load(root: java.nio.file.Path): Either[String, SynthesisPrompt]`
  reads `<root>/prompts/runtime/answer-synthesis-v1.md`, so `root` is a checkout that
  holds `prompts/`.
  `SynthesisPrompt.parse(text)` takes the text instead.
  `SynthesisPrompt(version, template, sha256)`; placeholders `BOUNDARY`, `REQUEST`,
  `NOTES`, `EVIDENCE`, `FEEDBACK`.
- `new LlmSynthesizer(llm: LlmPort, prompt: SynthesisPrompt, cfg: ScholarChainConfig,
  limits: SynthesisLimits = SynthesisLimits())`.
  `SynthesisLimits(maxAttempts = 3, maxOutputTokensPerAttempt = 1500,
  minOutputTokens = 200)`.
  A refused reply is retried with a fixed reason; on call failure, exhausted attempts or
  budget, or a model abstention despite evidence, the deterministic answer is used and
  `SynthesisResult.fallback` says why.
- `Synthesizers.select(cfg: ScholarChainConfig, llm: Option[LlmPort],
  prompt: Option[SynthesisPrompt]): Either[String, Synthesizer]` returns
  `DeterministicSynthesizer` when `cfg.models.llmEnabled` is false, an `LlmSynthesizer`
  when it is true and both are given, and a `Left` otherwise.
- `BedrockLlm.fromConfig(cfg, credentials, ...): Either[String, BedrockLlm]` is the
  AWS adapter; no test calls it against AWS.

#### 4b. Claim verifier v3 (`Verification.scala`, `claim-verifier-v3`)

Source: `docs/agent-runs/phase-09/repair-06-rag-engineer.md`.
- `ClaimVerifier.verify(c, bundle, model = true)`: the `labeled` parameter is gone.
  With `model` (the default) model text is never VERIFIED: at best PARTIALLY_SUPPORTED
  with a reason starting "UNVERIFIED interpretation"; it can still be CONFLICTING or
  UNSUPPORTED, and unknown names, IDs and titles are still weakened.
- `ClaimVerifier.verifyText` keeps its signature; model text is no longer searched for
  numbers, number words or rank phrases.
  Literal blanking plus the ASCII number rule runs only when `model = false`.
- `ClaimVerifier.structured(c, bundle): Vector[Verdict]`: one verdict per figure,
  written from evidence only.
  VERIFIED when the tuple matches, CONFLICTING when a cited item holds another value,
  else UNSUPPORTED.
- `ClaimVerifier.structuredId(id, i)`: `<claimId>-figure-N`.
- `ClaimVerifier.Interpretation = "UNVERIFIED interpretation"`.
- A figure that cites an ID not in the bundle is UNSUPPORTED even if another cited item
  agrees (missing-ID figure rule).
- Workflow model path: `claim-N` (PARTIALLY_SUPPORTED, labeled), then `claim-N-figure-K`
  for each VERIFIED figure; a non-VERIFIED figure is a separate `RemovedClaim`; summary
  and note are labeled; the overall verification of a model answer is never VERIFIED.
  The deterministic path is unchanged.

#### 5. Audit log

- `trait AuditLog { def append(r: AuditRecord): Either[String, Stored];
  def get(queryId: String): Either[String, Option[AuditRecord]] }`.
- `new InMemoryAuditLog` takes no arguments.
  `append` returns `Right(Stored.Created)` for a new query ID, `Right(Stored.Unchanged)`
  for an equal record and a `Left` for a different record under an existing ID.
  Read with `get(queryId)` or `all: Vector[AuditRecord]` (sorted by `queryId`).
- `WorkflowResult.audit` is the same record that was appended.
- The evidence bundle is stored through storage's `EvidenceBundles(stores.kv,
  cfg.dynamo.evidenceBundleTable).get(bundleId)`.

#### 6. Observing graph queries

- Template ID, window, row count, time and status of every call:
  `EvidenceStageResult.graphCalls` and `AuditRecord.graphCalls`, each
  `GraphCall(stage, templateId, window: Option[String], rows: Int, millis: Long,
  status: String)`.
  They hold no query text.
- Query text and parameters: `scholarchain.storage.InMemoryGraphStore(responder =
  _ => Right(Vector.empty)).executed: Vector[GraphQuery]`, in call order.
  `GraphQuery(templateId: String, cypher: String, parameters: ujson.Obj)`; its
  constructor is `private[storage]`, so a test reads queries and never builds one.
- `LocalRuntime.stores.graph` is typed `GraphStore` but is an `InMemoryGraphStore`
  backed by `TemplateGraph`, so `rt.stores.graph match { case g: InMemoryGraphStore =>
  g.executed }` reads the executed queries.
  This was not exercised by a test in `tests/module-unit/rag/`; the cast is read from
  `LocalRuntime.scala`.
- To intercept every query, build `RuntimeStores` yourself with a wrapping `GraphStore`
  (`trait GraphStore { def run(query: GraphQuery): Either[String, Vector[ujson.Obj]] }`)
  around `TemplateGraph.build(nodes, edges, coauthorEdges, policyVersion,
  evidenceBase).map(_.store)` (`RagWorld.stores(graphStore = ...)` does this in the unit
  tests).
- `GraphRunner.executed: Vector[GraphCall]` is the per-request log; a template outside
  the plan's allowlist fails with `WorkflowError.InvalidRequest`.

#### Usage on in-memory stores (compiles in `tests/module-unit/rag/`)

Taken from `WorkflowSuite.scala` and `LocalRuntimeSuite.scala`; `RagWorld` and
`SmokeFixture` are test-scope helpers there.

```scala
import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.{InMemoryGraphStore, LocalIngestion}

// A. A loaded run on disk (stages 1 to 10, deterministic synthesis).
val cfg = LocalIngestion.localConfig().fold(e => sys.error(e), identity)
val settings = LocalRuntimeSettings(Some(Set("2015-2026")), None)
val rt = LocalRuntime
  .load(cfg, runManifestPath, expectedErRunId, settings)
  .fold(e => sys.error(e), identity)
val log = new InMemoryAuditLog
val question = """What are the metrics of "Alice Arden" since 2015?"""
val r = rt
  .workflow(audit = log)
  .run("qry-1", AnalysisRequest(question))
  .fold(e => sys.error(e.describe), identity)
r.answer.claims.map(c => (c.claimId, c.verificationStatus, c.evidenceIds))
r.audit.removedClaims
log.get("qry-1") // Right(Some(r.audit))
rt.stores.graph match
  case g: InMemoryGraphStore =>
    g.executed.map(q => (q.templateId, q.cypher, q.parameters))
  case _                     =>

// B. A scripted model on the same stores.
val prompt = SynthesisPrompt.load(repoRoot).fold(e => sys.error(e), identity)
val llm = ScriptedLlm.replies(replyJson)
val wf = rt.workflow(new LlmSynthesizer(llm, prompt, cfg), new InMemoryAuditLog)
wf.run("qry-2", AnalysisRequest(question, Constraints(researcherIds = Vector(id))))
llm.prompts // every prompt the model received
```

In `WorkflowSuite` the same calls run on `RagWorld.default.workflow(synth, log)`, which
returns `(CollaborationWorkflow, RuntimeStores)` over a hand-built run.

### Phase 11 changes

Sources, in `docs/agent-runs/phase-11/`: `repair-01-rag-engineer.md`,
`repair-01-rag-engineer-c19.md` and `repair-02-rag-engineer.md`.

- Filtered rankings (C09, closes R6-1): new `ClaimVerifier.rankingFilter(e):
  Option[String]` (sanitized, at most 120 characters; `None` for a filter starting with
  "none") and `ClaimVerifier.amongText(e)` (" among <filter>").
  The verifier's rank label and structured text and the deterministic ranking claim and
  summary lead name the filter.
  Unfiltered rank texts and values on a filtered ranking are unchanged.
- Community size (C14): `RuntimeStores.communities`; `CommunityAnalystAgent` has a 4th
  defaulted parameter `communities: Option[CommunityReader] = None`.
  The record value `communitySizeStatus` is one of `CommunityAnalystAgent.SizeStatus`:
  `present`, `not-stored`, `read-failed`, `no-store` or `no-community`.
  Only `present` adds `communitySize`, its evidence ID and the exported row as
  provenance; a missing size is never 0, and a read failure gives a fixed note.
  `LocalRuntime` loads `jsonl/communities/windowLabel=W/recordKind=community` through
  storage's `CommunityLoader` when `dynamodb.json` has a `communities` block, and fails
  the load on a count mismatch or a refused or rejected row.
- Error text (C19): a failed semantic retrieval leaves only the fixed
  `EvidencePipeline.SemanticUnavailableNote`.
  A dropped item's note is "dropped invalid evidence item <id or (malformed ID)>: it
  failed core validation".
  An invalid bundle or answer is now `WorkflowError.Downstream` with stage `evidence` or
  `answer`, not `InvalidRequest`.
  The raw semantic error is no longer kept anywhere; operators see the audit stage
  status `failed`.
- Versions: `llm-synthesis-v2` (C11, closes R6-3), `community-agent-v2`,
  `local-runtime-v2`.
  `claim-verifier-v3` and `evidence-pipeline-v1` are unchanged (a bump is the human's
  call).
- R-4: `InvariantErrorSuite` (5 tests) pins the note and the remap; test only.
- rag tests in the full `sbt check`: 163 passed.
- Not done: services construct no `RuntimeStores`, so community size reads `no-store`
  there until the cloud wiring passes `communities`.
  No cross-stage test runs real Spark communities output through storage into rag.

## Done

- R1 (step 01): templates, chunk policy and embedding version rule; 33 tests.
  Every row of `run-ccf619c509611f06` rendered with 0 rejections and 0 conflicts:
  223,953 documents became 226,807 chunks (under the v1 abstract template).
- 01b: the port wiring, `tpl-paper-abstract-v2`, `IngestionConformanceSuite` and the
  runner; 41 tests.
  A smoke run with 200 rows per type gave 1,071 chunks and 0 rejected.
- R2 (step 03): `semantic-context-v1`, its schema, `SemanticContextSuite` 12 tests and
  `RetrievalSmokeSuite` 10 tests; `sbt rag/test` passed 63 of 63.
- Phase 08 repair R1-1: `Chunking.spans` accepts exactly `maxChunks` chunks (32 under
  chunk-v1) and refuses a 33rd; the loop guard was off by one.
  `chunk-v1` is kept, and golden spans and chunk IDs are unchanged.
  `EmbeddingModelVersion.decide` now follows the loader's order: other text is refused
  before a version mismatch, so the both-mismatch case is RefuseOtherText, not
  NewIndexVersion.
  There is no public API change.
  Three new tests: the exact chunk bound, `decide` precedence, and a conformance test
  that runs the real loader and compares its rejection reasons with `decide`.
  `sbt rag/test` passed 66 of 66, and the full `sbt check` exited 0
  (`docs/agent-runs/phase-08/output-sbt-check-r1.txt`).
- The gate items are covered by tests: one key never refers to two texts, the model
  version is stored, text is fetched through the S3 pointer, the smoke tests pass and
  graph values never come from vectors.
- The one full `sbt check` exited 0 in 362 s, with `markTestRun: 14 project test runs
  passed` (`docs/agent-runs/phase-08/output-sbt-check-step03.txt`).
- Phase 09, R1 (step 1): stages 1 to 7 and the query-plan-v1 schema.
  `sbt rag/test` passed 96 of 96.
- Phase 09, R2 (step 2): stages 8 to 10, the synthesis prompt and schema v1 and the
  entry point `CollaborationWorkflow`; `sbt rag/test` passed 123 of 123
  (`docs/agent-runs/phase-09/agent-report.md`).
- Phase 09 step 3 (test-engineer): benchmark question set v1 (33 questions), the
  workflow benchmark and 29 gate tests.
  Before the repair, 27 of 33 questions passed and 13 tests were red (defects F1 to F4).
- Phase 09 repair round 1 (review 1 was REQUEST_CHANGES; reports
  `repair-01-rag-engineer.md`, `repair-01-build-engineer.md`,
  `repair-01-test-engineer.md`):
  - F1 to F4 and M1 to M7 are fixed, with 8 new rag unit tests; M5 was only partly
    fixed (defect D5, see rounds 2 and 3 below); `sbt rag/test` passed 131 of 131.
  - The benchmark is 33/33 on D, A, pass and P; semantic hit rate 6/33 and precision
    7/80 are unchanged.
  - Suites: BenchmarkQuestionSetSuite 40 of 40, BenchmarkWorkflowSuite 68 of 68,
    WorkflowGateSuite 29 of 29, WorkflowRepairGateSuite (new, M5 and M6) 5 of 6.
  - M7: `adversarialTests` is forked at `-Xmx2560m -Xss4m` (build-engineer).
  - The full `sbt check` at the default `SBT_OPTS` exited 1 in 771 s on one red test,
    D5; round 2 below supersedes this.
- Phase 09 repair round 2 (D5 only; the human authorized an exception to the one-round
  rule; reports `repair-02-rag-engineer.md`, `repair-02-test-engineer.md`):
  - D5 was fixed for the named forms in `Verification.scala` (steps 7 and 8) by a clause
    rule that round 3 replaced; no public
    API signature changed.
  - `sbt rag/test` passed 133 of 133 (2 new tests, no existing assertion changed).
  - Suites: BenchmarkQuestionSetSuite 40 of 40, BenchmarkWorkflowSuite 68 of 68,
    WorkflowGateSuite 29 of 29, WorkflowRepairGateSuite 6 of 6 (the D5 test is green) and
    WorkflowRepairD5GateSuite (new) 2 of 2; adversarialTests 213 of 213.
  - The benchmark is unchanged: D, A, pass and P 33/33, semantic hit rate 6/33 and
    precision 7/80.
  - The full `sbt check` at the default `SBT_OPTS` exited 0 in 734 s, with 14 project
    test runs passed and the test marker touched, so `sbt check` is green.
  - Results and counts: [docs/runtime-workflow.md](../../docs/runtime-workflow.md).
- Phase 09 repair round 3 (second human-authorized exception; reports
  `repair-03-rag-engineer.md`, `repair-03-test-engineer.md`, `review-report-04.md`):
  - `Verification.scala` now uses the sentence rule; no public API signature changed.
  - `sbt rag/test` passed 135 of 135; WorkflowRepairR3GateSuite (new) 6 of 6;
    adversarialTests 219 of 219.
  - The full `sbt check` at the default `SBT_OPTS` exited 0 in 789 s.
  - Review 4 was REQUEST_CHANGES: R3-1 to R3-3 are fixed as written, but the class "a
    false per-researcher number is VERIFIED" is STILL OPEN (see Known issues).
- Behavior changes of the repair (no public API signature changed):
  - Pair questions accept quoted names, "support the collaboration between X and Y" and a
    direct-collaboration cue with two names (this adds DIRECT_COAUTHORS).
  - Pair answers cite the edge's supporting PUBLICATION items.
  - A topic is extracted before "in <years>".
  - Bridge claims read "touching N communities, its own included"; the verifier maps that
    phrase to `communitiesConnected`, and "N other communities" is unverified and
    weakened.
  - Round 3 replaced the clause rule with a sentence rule (see the round 3 entry).
    A per-researcher number is VERIFIED only when its sentence names exactly one
    researcher, recognised by exact display name, and has no pronoun.
    Every other number is weakened and labeled (PARTIALLY_SUPPORTED with `[unverified]`),
    never CONFLICTING for per-researcher records, and a model summary like it is
    replaced; pair values held by an edge, path or ranking may still be CONFLICTING.
    This supersedes the round 1 positional rule and the round 2 clause rule.
  - Disclosed: correct prose naming two researchers in one sentence, such as "Alice, like
    Bob, has 41", is weakened and labeled on purpose.
  - An abstaining answer has no researcher rows, and in other answers a row needs a
    claim that cites it.
  - `LocalRuntime`'s context config holds only the loaded windows, so the default window
    is the loaded span and is answered from the widest loaded window, with a note.

## Not done

- Community size (closed in Phase 11, C14): it is now missing only when no community
  store is wired or no record can be read (see "Phase 11 changes").
- The Phase 09 human gate: review 4 returned REQUEST_CHANGES, so "Code review returns
  APPROVE" is not met.
- R4-1 to R4-3 are superseded by the v3 construction rule; R6-1 is closed (Phase 11).
- MINOR m1 (an exact-name hit never consults the token-order key, so swapped-order
  homonyms are not listed), m2 (the name check accepts a name as a substring of a cited
  value) and m4 (a topic phrase such as "collaboration graphs" gives a filter that
  matches no Topic node, so the answer abstains).
- Windows are not attributed to a researcher, and swapped values inside one cited
  ranking are accepted.
- Component version strings, including `claim-verifier-v1`, were not bumped although
  behavior changed (the human decides).
- The query-service route and a DynamoDB audit repository (`AuditLog` has only the
  in-memory implementation).
- Production adapters for the `ResearcherDirectory` (name lookup) and `MetricRanking`
  (per-window ranking) ports; only in-memory versions exist.
- Any run against Bedrock or any other cloud call: `BedrockLlm` exists, and only a
  local HTTP stub is used in tests.
  The synthesis model ID and the per-query cost are undecided (human).
- Packaging `prompts/` into the query-service image, so `SynthesisPrompt.load` finds it.
- Retrieval quality on real data: semantic hit rate 6/33 and precision 7/80 with the
  token-hashing fake embedder (unchanged by the repair).
- Planting injected text into the vector store (no documented API).

## Known issues

- Any change to Spark's summary layout makes every row of that type fail the
  conformance check until the template version is bumped.
  This is by design and visible.
- C1 and format characters (for example 0x8E and U+200B in 6 real texts) are kept
  verbatim in the stored text, because changing them would change its hash.
  The read-time sanitizer removes them.
- Sanitizing does not stop the words of a prompt injection.
  The Phase 9 synthesis prompt must say that `semantic-context-v1` is untrusted data.
- Summary texts carry Spark's numbers (for example "Distinct coauthors: 41").
  They reach the model as untrusted words; the verifier must accept numbers only from
  authoritative evidence.
- The pointer check is a prefix check on `s3://<ragBucket>/semantic-context/`, not an
  exact key recomputation; a poisoned pointer to another object fails on the chunk ID and
  hash checks.
- The budget values are versioned constants, not config fields.
- A failed index query or embedding call gives a `Left` for the whole retrieval.
- The schema file is not validated by a JSON Schema engine; a test pins the field sets,
  constants and bounds.
- The 223,953-document total is the v1 render; under v2 the abstract chunk count is
  6,387 (`output-local-ingestion.md`), so the total chunk count of the full export under
  v2 is not measured.
- `adversarialTests` is forked at `-Xmx2560m -Xss4m` (M7), so the default `SBT_OPTS`
  works; the full `sbt check` at the default took 734 s in round 2, and
  `LocalRuntime.load` took 209635 ms in the benchmark.
- The claim verifier is pattern-based; a fact in words it does not parse ends
  PARTIALLY_SUPPORTED, not caught as wrong.
- The planner recognises names, topics and institutions only in explicit forms
  (see [docs/runtime-workflow.md](../../docs/runtime-workflow.md)).
- R6-1 (MAJOR, review 6, a filtered-ranking rank text that omitted the filter) and R6-3
  (MINOR, stale `LlmSynthesizer.Version`) are closed in Phase 11 (C09, C11).
- K13 stays open: m1 (swapped name order), m2 (substring name check) and m4 (topic
  phrase filter), listed under "Not done".
- R4-1 to R4-3 and V5b-1, V5b-2 below are superseded by the v3 construction rule: free
  text is never VERIFIED.
  False numbers in labeled prose, summaries and notes stay visible under the label by
  design.
- Open after review 4 (all MAJOR, rag-engineer, model path only; `LLM_ENABLED` defaults
  to false; the human carries them forward and they must be closed before Bedrock or
  the LLM is enabled):
  - R4-1: a variant or partial name (surname only, lower case, a non-breaking space) is
    not a mention, so a false number can be VERIFIED.
  - R4-2: a noun phrase for a researcher ("the leader", "the former", "X's closest
    coauthor") is not treated as a pronoun, and an unlisted abbreviation such as "dir."
    splits a sentence.
  - R4-3: a cited ranking item backs any researcher's number.
- Test counts, Phase 09 after repair round 3: `sbt rag/test` 135;
  `BenchmarkQuestionSetSuite` 40 of 40; `BenchmarkWorkflowSuite` 68 of 68;
  `WorkflowGateSuite` 29 of 29; `WorkflowRepairGateSuite` 6 of 6;
  `WorkflowRepairD5GateSuite` 2 of 2; `WorkflowRepairR3GateSuite` 6 of 6;
  `adversarialTests` 219 of 219.
- One sbt process took 47 minutes to exit after success (step 01b); the cause was not
  found.

## Dependencies

- Projects: `core`, `storage`.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
