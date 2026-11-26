HARNESS: claude-code
MODEL: sonnet (claude-sonnet-5-5), effort medium (tier standard, .agents/models.json)

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 11 repair round 1: C13 (B-14), C14 load part (B-15), C17 (B-13, K11 NIT-2).
FILES CREATED: modules/storage/src/main/scala/scholarchain/storage/Communities.scala; tests/module-unit/storage/SparkExportContractSuite.scala (SparkExportContractSuite, CommunityLoaderSuite)
FILES MODIFIED: modules/storage/src/main/scala/scholarchain/storage/{Loaders,Tables,VectorLoader,Fakes}.scala; tests/module-unit/storage/LoaderSuite.scala
COMMANDS RUN: `timeout 30m sbt storage/scalafmt storage/Test/scalafmt storage/test` (one run, foreground, exit 0, 48 s); git status/diff.
TEST RESULTS: storage: Passed: Total 199, Failed 0, Errors 0, Passed 199, Ignored 1 (184 and 1 ignored before, per MODULE.md). +15: ResearcherMetricsLoaderSuite now 13 (2 old missing-directory tests replaced), VectorLoaderSuite +1, SparkExportContractSuite 3, CommunityLoaderSuite 7. Gated AWS suite ignored, NOT RUN. Full sbt check NOT RUN (gate is storage/test).

VALIDATION RESULTS:
- C17a: choice is a version-guarded update, not a separate item. New RunLedger.recordReport(cur, uri, sha): Succeeded stays Succeeded, attempt + 1, outputUri/Sha256 name the new report, the replaced one is appended to LedgerEntry.priorOutputs (PriorOutput(attempt, uri, sha256, recordedAt)). LoadStage calls it when a replay's report SHA differs. Same SHA: ledger untouched. A failing replay changes nothing. The field defaults to empty and is then absent from stored JSON (tested against the old form). Applies to all loaders (all use LoadStage). Test: repairing replay, same replay again, failing replay, stale-version refusal, old report object kept.
- C17b: new RowSource.problem: Option[String] (default None; kept by mapE, filter, concat; set by jsonLinesDir on a missing directory). Metrics, vector (load and loadRendered) and community loaders return Left on it before any read or write; the ledger records Failed with the message, for expectedRows None, 0, 1, 2. An existing directory with no rows stays legitimate: Succeeded, read 0. jsonLinesDir.rows still yields the one Left row, so the VectorSuite test and LocalIngestion are unchanged. K11 NIT-2 closed.
- C13: hand-written literal JSON lines in <tmp>/jsonl/<table>/<partition folders>/part-*.json plus _SUCCESS and .crc (only run ID and core edge IDs interpolated). Metrics: read 2, created 2, rejects 0; reload unchanged 2. Neptune: 3 nodes, 3 edges, 1 coauthor record, rejections 0, duplicates 0, CSV rows Researcher 2, Work 1, AUTHORED 2, COAUTHORED_WITH 1; NeptuneLoadJob 4 files Created, replay Unchanged, ledger Succeeded. Keeping any one extra field: metrics rows fail "unknown field" (all 3 fields), coauthor rows refused (both fields); the loader then rejects 2 of 2 and stores 0.
- C14 table and keys: dynamo.researcherMetricTable (no new StorageConfig/DynamoConfig field, table or Terraform resource). pk `community#{communityId}`, sk `run#{runId}#window#{startYear-endYear}`; distinct from `researcher#`. Payload = the exported core EvidenceItem (provenance kept). No TTL. Run is in the sort key so a later run never collides with or reads an earlier run's item.
- C14 writes: one conditional PutItem (attribute_not_exists) per community row, GetItem compare on conflict; same row Unchanged, different row refused and rejected; identical input duplicates dropped, conflicting ones all rejected. Read: one GetItem. No batch writes. A store failure fails the stage. No new retry policy.
- C14 loader: CommunityLoader(cache, ledger, writer).load(runId, RowSource[EvidenceItem], expectedRows): stage load-communities, communities-load-v1. Every row: Validate.evidence, COMMUNITY_RECORD, run match. community rows need communityId (no '#'), window, communitySize >= 1. cross_community_edge rows are counted as otherKindRows, not stored. Anything else is rejected into the report. Fixture: read 3, created 2, otherKindRows 1, rejects 0; reload unchanged 2; 8 bad rows give 8 rejects.
- Limits: DynamoDB pk 2048 B, sk 1024 B, item 400 KB (https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html, read now); enforced by KvItem.validate.

READ PORT (rag-engineer, package scholarchain.storage):
  trait CommunityReader:
    def community(runId: String, communityId: String, window: TimeWindow): Either[KvError, Option[CommunitySize]]
  final case class CommunitySize(communityId: String, window: TimeWindow, size: Long, evidence: EvidenceItem)  // def runId
  DynamoDB adapter: new CommunityCache(kv: KeyValueStore, table = cfg.dynamo.researcherMetricTable)  // extends CommunityReader; put(row): Either[KvError, Stored]
  Fake: new InMemoryCommunityReader()  // put(row), community(...)
- Call with ctx.graphRunId, metrics.communityId, metrics.window. None = not stored (report as missing, never 0); Left = store failure or malformed/mismatched item. runId is an added argument to the requested (communityId, window) lookup, for run scoping.
- For local wiring, load with CommunityLoader(new CommunityCache(local.kv, table), tables.runLedger, writer), RowSource.jsonLinesDir[EvidenceItem](<run>/jsonl/communities, Validate.evidence, Set("windowLabel","recordKind")). expectedRows counts all rows of the folder(s) read (communities.source.rows for the whole table; the recordKind=community partition counts otherwise).

ASSUMPTIONS: communityId plus window is unique within a run (it hashes label, window, policy; WindowAnalytics). Attempt counts each recorded report as well as each retry.
RISKS:
- Public API changes are additive (RowSource.problem and LedgerEntry.priorOutputs have defaults; positional pattern matches on LedgerEntry would change). No outside use of the new type names found by grep; rag and services were not compiled here.
- NeptuneLoadJob takes a built plan, not a RowSource: whatever reads the Neptune tables must fail on `problem` or any Left row (no production caller exists in storage).
- Not done: no loader reads the dynamodb.json `communities` block (caller supplies directory and expectedRows); DynamoTables has no community member; LocalIngestion does not load communities; K11 NIT-1 not folded (AwsIntegrationSuite not otherwise edited, stays CARRY).
- A replay that is worse than the first (new transient rejects) also becomes the latest ledger report; the earlier one stays in priorOutputs. History is limited only by the 400 KB item limit (write then refused, stage returns Left).
- Mismatch of an existing item reports "different record", including a changed sourceUri for the same run.
- No AWS call, no mutation testing, no commit. No stored data touched.
- MODULE.md notes (documentation-engineer): 17 source files with Communities.scala; API: CommunityReader, CommunitySize, CommunityCache, CommunityLoader, CommunityLoadResult, InMemoryCommunityReader, RowSource.problem, RunLedger.recordReport, PriorOutput; 199 passed, 1 ignored. Close known issues "replay report not named by ledger", "missing directory still Succeeded" and "Neptune/DynamoDB loaders not run on JSON-lines export" (now fixture-tested, not on a real run). docs/storage.md needs the community key row and the researcher-metric table's second use; Query role needs only GetItem on that table.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: rag-engineer C14 read through CommunityReader; then test-engineer rerun, full sbt check and a fresh review.
