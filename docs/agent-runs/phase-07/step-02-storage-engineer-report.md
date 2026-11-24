HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, role default; effort per .agents/models.json)

STATUS: COMPLETE (one gap to route, see RISKS item 1: the loader cannot read the Phase 06 Parquet files yet)
OWNER: storage-engineer
WORK ORDER: phase 07 step 02, task T2. Neptune bulk-load manifests and openCypher CSV from the Phase 06 export, allowlisted parameterized read templates, Neptune HTTP and bulk-loader adapters, and coauthor-edge endpoint and evidence validation.

FILES CREATED (all under /home/ajcreator/scholarchain):
- modules/storage/src/main/scala/scholarchain/storage/GraphTemplates.scala
- modules/storage/src/main/scala/scholarchain/storage/NeptuneExport.scala
- modules/storage/src/main/scala/scholarchain/storage/NeptuneAdapters.scala
- tests/module-unit/storage/NeptuneSuite.scala (5 suites, 49 tests)
FILES MODIFIED:
- modules/storage/src/main/scala/scholarchain/storage/Ports.scala (appended the `GraphStore` trait)
- modules/storage/src/main/scala/scholarchain/storage/Fakes.scala (appended `InMemoryGraphStore`)
I did not touch MODULE.md, modules/spark, modules/core, build files or any T1 logic.

COMMANDS RUN (each in the foreground with a timeout, one sbt at a time):
- `sbt -Dsbt.server.forcestart=true storage/compile`: failed once (non-exhaustive match in `NeptuneCsv.parse`). I fixed it.
- `sbt storage/compile`: success.
- `sbt storage/test`: 3 runs.
  - Run 1: 3 of 81 tests failed. One was a fixture bug (empty provenance record ID on edges that have no evidence). One was a test mistake (a hostile ID with whitespace is rightly refused). One was a wrong expectation, because I had misread the work-ID pattern.
  - Run 2: 3 failed.
  - Run 3: green after I fixed the tests.
- `sbt storage/scalafmt storage/Test/scalafmt storage/test`: reformatted 4 main files and 1 test file (the files I had written through the shell), and tests passed.
- Plus read-only `ls`, `find`, `strings` and `grep` on the Phase 06 output folder.
- Not run: the full `sbt check`, Docker, any AWS call.

TEST RESULTS: the last `sbt storage/test` printed `Passed: Total 82, Failed 0, Errors 0, Passed 82`. That is 33 T1 tests plus 49 new.
- NeptuneIdentifierSuite 7
- NeptuneValidationSuite 19
- NeptuneCsvSuite 8
- GraphTemplateSuite 8
- NeptuneAdapterSuite 7
- T1 suites unchanged: KeyValueFake 4, S3Layout 3, ArtifactWriter 4, Serialization 2, Tables 10, S3Stub 5, DynamoStub 5.

The fixture is shaped like the Phase 06 export. It uses core `GraphNode`, `GraphEdge` and `CoauthorEdge` with the string properties `Export.scala` writes, and has 3 researchers, 3 works, 1 venue, 1 topic, 1 institution and 4 coauthor records over 2 windows. I could not read the real Parquet (see RISKS 1), so no test reads `run-769e0d672ecd3c19` data. I checked the real run only through its `neptune.json` manifest and by `strings` on the Parquet files. This confirmed the ID forms (`venue:openalex:S…`, `openalex:T…`, `openalex:I…`, `orcid:…`, `sc:r:v1:…`) and the coauthor edge ID tag `e:coauthored_with:`.

VALIDATION RESULTS

1. Labels present and absent in the Phase 06 export (checked in `Export.scala` and `neptune.json`)
- Nodes present: Researcher, Work, Venue, Institution, Topic. Real run counts: 81, 42, 1, 37 and 44, 205 in total.
- Relationships present: AUTHORED (106), COAUTHORED_WITH (156, across 2 non-empty windows), PUBLISHED_IN (25), AFFILIATED_WITH (47), HAS_TOPIC (75), 409 in total.
- NOT exported: RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY, and the nodes they need (AuthorMention, TextChunk, DatasetSnapshot). There are no "selected context nodes" in the export.
  - They are listed in the load manifest (`notExportedNodes`, `notExportedEdges`).
  - If such rows ever appear, the loader rejects them with "not exported by Phase 06".
  - I judged this a report item, not a spark-engineer block: the work order says "as far as the implemented graph model (the export) has them". If the human wants these edges, spark-engineer must export them.

2. Id scheme
- Node `:ID` = Spark `nodeId` exactly.
- Relationship `:ID` = Spark graph `edgeId` exactly, which is core `Ids.graphEdge`. A COAUTHORED_WITH graph edge keeps its own graph-edge ID, and the window-scoped coauthor-edge ID (`Ids.coauthorEdge`) is in the `coauthorEdgeId` property.
- The loader creates no identifier. Output rows are sorted by ID, so the same input gives the same bytes in any input order. The load manifest has no timestamp.
- Tests prove the node and edge ID sets in the CSV equal the Spark ID sets, and that every `:START_ID` and `:END_ID` is a loaded node.

3. CSV files (openCypher format, UTF-8, LF, RFC 4180 quoting; one file per label or type; an empty file is not written)
Documentation: https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-format-opencypher.html (read 2026-11-23).
- Node header: `:ID,:LABEL,<props>`
- Relationship header: `:ID,:START_ID,:END_ID,:TYPE,<props>`
- Provenance columns on every node and relationship (derived from `provenance`): `source:String,sourceRecordId:String,snapshot:String,runId:String,policyVersion:String`. An empty cell means the property is absent.
- nodes-Researcher.csv: `displayName:String,orcid:String,mentionCount:Long,mentionIds:String,identityConfidence:Double,unresolved:Bool,identityConflict:Bool` + provenance
- nodes-Work.csv: `title:String,year:Int,doi:String,recordType:String,memberWorkIds:String,unresolved:Bool,identityConflict:Bool` + provenance
- nodes-Venue.csv: `name:String,kind:String` + provenance
- nodes-Institution.csv: `name:String,countryCode:String,ror:String` + provenance
- nodes-Topic.csv: `name:String` + provenance
- edges-AUTHORED.csv: `position:Int,teamSize:Int,year:Int,mentionIds:String` + provenance
- edges-COAUTHORED_WITH.csv: `coauthorEdgeId:String,window:String,windowStartYear:Int,windowEndYear:Int,jointPublicationCount:Long,firstCollaborationYear:Int,lastCollaborationYear:Int,representativeWorkIds:String,evidencePointer:String` + provenance
  - `windowStartYear`, `windowEndYear` and `evidencePointer` are derived from the coauthor record.
  - `representativeWorkIds` is `|`-joined, as Spark writes it.
  - `evidencePointer` = `<evidenceBaseUri>/coauthor-edges/windowLabel=<window>#<coauthorEdgeId>`. The caller passes `evidenceBaseUri`, the S3 folder of the Spark run.
- edges-PUBLISHED_IN.csv and edges-HAS_TOPIC.csv: provenance only
- edges-AFFILIATED_WITH.csv: `supportingMentionCount:Int` + provenance
- An input property that is not in these lists, or a derived column supplied by the input, is rejected.

4. Researcher collaboration metrics
- I did NOT put metrics on Neptune nodes. Metrics are window-scoped, and a node property has one value, which would break the single-window rule.
- Bounded runtime lookup stays in the DynamoDB `ResearcherMetricCache` from T1 (key `researcher#{id}`, sort key `metrics#{metricVersion}#{window}`).
- The Parquet-to-DynamoDB loader for `researcher-metrics` is not in T2. It belongs to T3 with the other loaders. The Neptune graph answers structure and evidence.

5. Allowlisted read-only templates (class `GraphTemplates`, 14 constant templates)
Window is checked against the configured window labels. Limit is 1..200. Depth is 1..6. An ID is non-empty, at most 450 characters, with no whitespace or control characters.
- `node-v1` (id)
- `direct-coauthors-v1` (id: researcher ID, window, limit)
- `coauthor-pair-v1` (a, b: researcher IDs, window)
- `shortest-path-d1-v1` to `shortest-path-d6-v1` (from, to: researcher IDs, window). Depth is part of the template ID because openCypher cannot parameterize it, and `*1..N` is constant text.
- `neighbors-out-v1`, `neighbors-in-v1`, `neighbors-both-v1` (id, edgeTypes list, limit). The edge types come from {AUTHORED, HAS_TOPIC, AFFILIATED_WITH, PUBLISHED_IN} and are passed as a parameter list (`type(e) IN $edgeTypes`). COAUTHORED_WITH cannot be requested through neighbors.
- `work-authors-v1` (id: work ID, limit)
- `researcher-works-v1` (id: researcher ID, limit)
- Single-window rule: all 8 templates that read COAUTHORED_WITH contain `e.window = $window` with a window parameter (tested).
- User text goes only into `parameters`. Template text has no string literals (tested).
- `GraphQuery` has a module-private constructor. `GraphTemplates.isAllowlisted` re-checks text and parameter names.
- `ReadOnlyCypher` rejects write keywords. `NeptuneGraphStore.run` refuses any query that is not allowlisted before sending anything.

6. Adapters (all tested only against a local `com.sun.net.httpserver` stub, using core `AwsHttp`, service `neptune-db`)
- `NeptuneGraphStore(http, NeptuneConfig, endpointOverride)` implements `GraphStore`. It POSTs `/openCypher` as a form with `query` and `parameters`. The documented request format is at https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-parameterized-queries.html.
- `NeptuneBulkLoader(http, NeptuneConfig, region, loaderRoleArn, endpointOverride)` is for the loader role only.
  - `start(prefix)` POSTs `/loader` with `format=opencypher`, `mode=AUTO`, `failOnError=TRUE`, `parallelism=MEDIUM`, `userProvidedEdgeIds=TRUE`, `updateSingleCardinalityProperties=TRUE` and `queueRequest=TRUE`. It returns the `loadId`. It checks that the prefix is `s3://…/` and that the role is an IAM role ARN.
  - `status(loadId)` returns `LoaderStatus` with `completedClean`, true only for LOAD_COMPLETED with 0 errors.
  - Parameters: https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html. Duplicate node or relationship IDs are loaded once, which is why the IDs are the Spark IDs.
- `NeptuneLoadWriter(ArtifactWriter, S3Layout)` writes with create-only puts and content hashes.
  - CSV goes to `s3://{graphBucket}/graph/neptune-load/run_id={run}/` (only CSV in the loader source prefix).
  - The load manifest `neptune-load.json` and a T1 `StorageManifest` go to `graph/neptune-load-manifest/run_id={run}/`.
  - A replay returns `Unchanged` for every object. Changed content is `ArtifactError.Conflict` and nothing is overwritten.
- Config: `neptune.endpoint` and `neptune.port` come from core `NeptuneConfig`. No field is missing from core.
  - The loader role ARN is not in core config. It is a constructor argument. infra-engineer supplies it in Phase 12, and domain-engineer may add a config field later.

7. Validation rules (`NeptuneExport.build`; each failure becomes a `Rejection(kind, id, reason)` in the manifest; nothing is repaired; dependants of a rejected node or edge are rejected too)
- Nodes:
  - label is one of the five exported labels
  - core `Validate.graphNode`
  - `provenance.runId` equals the input run
  - researcher and work IDs match core `Ids`; other IDs are non-empty with no whitespace
  - every property key is known and not loader-derived, and every value parses as its column type
  - an ID that appears twice must be the same record: an identical copy is dropped and counted (`duplicatesDropped`), and a different record under the same ID, including another label, rejects every copy
- Relationships other than COAUTHORED_WITH:
  - the type is exported
  - core `Validate.graphEdge`
  - run ID matches
  - both endpoints are loaded nodes with the labels the type fixes: AUTHORED Researcher to Work, PUBLISHED_IN Work to Venue, HAS_TOPIC Work to Topic, AFFILIATED_WITH Researcher to Institution
  - properties and duplicates as above
- COAUTHORED_WITH, in addition:
  - the edge names a coauthor-edge record, and that record passes core `Validate.edge` (pair order, years in the window, edge ID recomputed from endpoints, window and policy)
  - the record's policy and run match the input
  - both endpoints are loaded Researcher nodes and equal the record's pair in order
  - the window is a configured window, and the graph edge `window` equals the record window
  - the graph edge ID equals `Ids.graphEdge(COAUTHORED_WITH, a, b, policy, coauthorEdgeId)`
  - the graph edge's count, years and works equal the record
  - works are unique and at most the joint publication count
  - every representative work is a loaded Work node AUTHORED by both researchers (so each edge resolves to its supporting works)
  - a work's `year`, when present, lies inside first..last
  - a coauthor record that no graph edge references is rejected
- `SparkNeptuneManifest.parse` and `reconcile` compare the Spark `neptune.json` row counts (total, per label, per edge type, run, snapshot) with the rows read, and name every difference. A test uses a manifest shaped like the real one.

PUBLIC API ADDED (package `scholarchain.storage`)
- Ports: `GraphStore.run(GraphQuery): Either[String, Vector[ujson.Obj]]` (read-only).
- Templates: `GraphQuery`, `GraphTemplate`, `ParamKind`, `ReadOnlyCypher.violation`, `GraphTemplates` (class `new GraphTemplates(windows: Set[String])` or `GraphTemplates.forConfig(windows)`) with `node`, `directCoauthors`, `coauthorPair`, `shortestPath`, `neighbors`, `workAuthors`, `researcherWorks`. Companion: `All`, `ById`, `isAllowlisted`, `MaxLimit`, `MaxDepth`, `NeighborEdgeTypes`.
- Export: `PropType`, `Col`, `NeptuneSchema`, `NeptuneCsv` (`field`, `line`, `parse`), `Rejection`, `GraphExportInput`, `CsvFile`, `CountEntry`, `ManifestFile`, `NeptuneLoadManifest` (`parse`, `Version`), `NeptuneLoadPlan` (`manifest`, `files`, `rejections`, `clean`, `manifestJson`), `SparkNeptuneManifest` (`parse`, `reconcile`), `NeptuneExport.build(GraphExportInput): NeptuneLoadPlan`.
- Adapters: `NeptuneGraphStore`, `NeptuneBulkLoader`, `LoaderStatus`, `NeptuneLoadWriter`, `NeptuneLoadResult`.
- Fake: `InMemoryGraphStore(responder)` with `executed`.

ASSUMPTIONS
- Input is core `GraphNode`, `GraphEdge` and `CoauthorEdge` rows, the shapes the Spark tables declare (`graph-node-v1`, `graph-edge-v1`, `coauthor-edge-v1`).
- The edge `policyVersion` input is the graph policy (`graph-v1`, from the Spark manifest `graph.policyVersion`). Researcher and Work nodes carry `er-v1` in their provenance, as the Spark docs say.
- Provenance columns hold `source`, `sourceRecordId`, `snapshot`, `runId` and `policyVersion`, so a graph row traces to the Spark run.
- Neptune treats a blank cell as an absent property. An empty-string property is therefore absent.

RISKS
1. Parquet reader gap (most important).
   - The Phase 06 export is Parquet, and `storage` depends only on core. It has no Parquet reader, so no code here turns `target/spark-local-run/.../graph-nodes` into `GraphNode` rows.
   - `neptune.json` itself says "the loader converts it to the Neptune bulk-load format".
   - The generator (`NeptuneExport.build`) takes typed rows and is complete and tested on the fixture shape, but the real run has not been through it.
   - Routing options, for the orchestrator or human to choose:
     - build-engineer adds a Parquet reader dependency to `storage`, or
     - spark-engineer also writes JSON lines of the same tables (core `JsonLines` decodes them), or
     - a reader is written in T3 or a later step.
   - I made no change outside my territory and did not invent a dependency.
2. Real-run counts are not checked here. They need that reader. Expected from `neptune.json`: 205 nodes and 409 edges, with 156 COAUTHORED_WITH edges in 2 windows. Run `SparkNeptuneManifest.reconcile` on real rows once the reader exists.
3. Nothing ran against real Neptune. Unverified there:
   - `LIMIT $limit` with a parameter
   - the `*1..N` shortest-path templates and their cost on a dense graph
   - `id(n)` in `WHERE`
   - the exact `/loader` and `/openCypher` replies (written from the documentation and proven only against my stub)
   - the CSV acceptance of the quoting and of newlines inside quoted fields (RFC 4180, but unproven on Neptune)
4. `AwsHttp` has one request timeout for the whole client. `NeptuneConfig.queryTimeoutMs` is not applied per request. Whoever builds the production `AwsHttp` should set `requestTimeout` to match it.
5. The export logic holds all rows in memory (id maps, authored-pair set). That is fine at the sample scale (about 200 nodes). A full-DBLP run needs a streaming or partitioned design.
6. The loader never calls `NeptuneBulkLoader` by itself, and no RunLedger entry is written for the load. T3's idempotent-reload wiring should record the run in `RunLedger`.
7. IAM:
   - read-only graph credentials are an IAM matter for infra-engineer in Phase 12; this code only exposes read templates to runtime
   - the loader role needs S3 read on `graph/neptune-load/` and the Neptune loader role association
   - `neptune.endpoint` is blank in the default config
8. The T1 `Segment` key rule stays as before. File names are `nodes-<Label>.csv` and `edges-<TYPE>.csv`.
9. The state left behind is uncommitted new files only. Nothing was deleted or repaired.
- Notes for modules/storage/MODULE.md (documentation-engineer): public API as listed above. Done: T2 with 49 new tests (82 in total). Not done: the Parquet reader, the metrics loader, S3 Vectors, the Bedrock embedder, the RunLedger wiring of loads, the gated AWS suite. Known issues: items 1, 3, 4 and 5 above.

MANUAL ACTION REQUIRED: none from the human for this step. Routing decision needed for RISKS 1 (the Parquet reader).
BLOCKED-ON OWNER: none for T2 as delivered. (RISKS 1 may become BLOCKED-ON build-engineer or spark-engineer, depending on the orchestrator's choice, before the real Phase 06 tables can be loaded.)
BLOCKED-ON EVIDENCE: none.
NEXT GATE: T3 (storage-engineer): S3 Vectors adapter and loader, Bedrock embedder, idempotent reload wiring, gated AWS suite. Before T3 or the cloud phases, decide how the Parquet tables reach `NeptuneExport.build` and the other loaders.
