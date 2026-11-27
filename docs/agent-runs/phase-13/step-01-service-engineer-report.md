HARNESS: claude-code
MODEL: sonnet, effort medium (standard tier; runtime reports claude-sonnet-5-5)
STATUS: COMPLETE (follow-ups under RISKS; the LLM path in the image and answering queries in ECS depend on them)
OWNER: service-engineer
WORK ORDER: Phase 13 step 1: three runnable mains, LLM wiring, wiring tests with no port bound
FILES CREATED (under /home/ajcreator/scholarchain/):
- modules/services/common/src/main/scala/scholarchain/services/common/Bootstrap.scala
- modules/services/query/src/main/scala/scholarchain/services/query/{QueryServiceMain,ServingData}.scala
- modules/services/graph/src/main/scala/scholarchain/services/graph/GraphServiceMain.scala
- modules/services/worker/src/main/scala/scholarchain/services/worker/{EmbeddingWorkerMain,SqsQueueConsumer}.scala
- tests/module-unit/services/{query/QueryWiringSuite,graph/GraphWiringSuite,worker/WorkerWiringSuite}.scala
FILES MODIFIED: QueryRoutes.scala (comment only); EmbeddingWorker.scala (a failed drain logs the error class and the loop continues)
COMMANDS RUN (foreground, timeout 30m): sbt per-project scalafmt on the four service projects, services/Test/compile, services/test, show <3 projects>/Compile/mainClass. Run 1 failed to compile (one wrong type qualifier). Run 2, after the fix, succeeded. Not run: sbt check, assembly, docker, any AWS call.
TEST RESULTS: 103 passed, 0 failed: servicesCommon 31, graphService 18, embeddingWorker 15, queryService 39.
VALIDATION RESULTS:
- mainClass: QueryServiceMain, GraphServiceMain, EmbeddingWorkerMain, one each. No build main-class change is needed.
- Port 8080 each (QUERY_/GRAPH_/WORKER_SERVICE_PORT), bind 0.0.0.0. GET /healthz = liveness; GET /readyz = 200 or 503. Ready when: query has loaded the served run; graph's Neptune probe read succeeds; the worker's last SQS receive succeeded.
- REQUIRED by all three (aws-mode config validation; infra common_env already sets them): SCHOLARCHAIN_MODE=aws (default local, refused); RAW_BUCKET, CURATED_BUCKET, GRAPH_BUCKET, RAG_BUCKET, VECTOR_BUCKET, NEPTUNE_ENDPOINT, DDB_RUN_LEDGER, DDB_ENTITY_LINK_CACHE, DDB_QUERY_SESSION, DDB_EVIDENCE_BUNDLE, DDB_ARTIFACT_REGISTRY, DDB_RESEARCHER_METRIC, EMBEDDING_MODEL_ID, EMBEDDING_QUEUE_URL (https), COGNITO_ISSUER, COGNITO_AUDIENCE, COGNITO_JWKS_URL (https; the worker does no token checks but config still requires these).
- Optional (default): AWS_REGION (us-east-2), SCHOLARCHAIN_ENV (dev), NEPTUNE_PORT (8182), EMBEDDING_DIMENSION (256), the three ports (8080), QUERY_/GRAPH_/WORKER_REQUEST_TIMEOUT_MS (15000/10000/30000; worker value unused), COGNITO_JWKS_TIMEOUT_MS (3000), WORKER_BATCH_SIZE (10, capped at 10), WORKER_MAX_IN_FLIGHT (4, unused), SCHOLARCHAIN_LOCAL_ROOT, ER_MAX_LLM_CALLS, VERIFICATION_MODEL_ID, SCHOLARCHAIN_CONFIG_FILE, SCHOLARCHAIN__*. SCHOLARCHAIN_REVISION is set by infra but not read.
- queryService only: LLM_ENABLED (false); SYNTHESIS_MODEL_ID (required when true, Anthropic only; Region = AWS_REGION); new SYNTHESIS_PROMPT_ROOT (".", used if the prompt is not on the classpath); new SERVING_RUN_MANIFEST_URI (unset), form s3://<graph>/.../run-manifests/run-<hex>.json.
- AWS credentials: the standard ECS container credential variables.
- Routes: unchanged. New behaviour: POST /v1/query gives 401 without a token, and 503 unavailable (before any idempotency claim) until the served run loads. Status mapping otherwise unchanged.
- Timeouts: query workflow = QUERY_REQUEST_TIMEOUT_MS, 1 attempt; query stores = neptune.queryTimeoutMs 5 s, 2 attempts; graph = min(5 s, request timeout), 2 attempts; BedrockLlm 10 s; SQS long poll 10 s; server shutdown 20 s.
- Auth: Cognito JWT through the shared Guard with cached HTTPS JWKS. Dependency graph unchanged. rag and storage not edited.
- Tests use fake HTTP transports: no port, network or sleep. The LLM test proves the Bedrock URL carries the configured Region and model.
ASSUMPTIONS / CHOICES (why):
1. Mains accept aws mode only: no local adapters exist, and aws mode rejects empty values.
2. The query service starts without a served run: the app is deployed before the pipeline writes one. It loads once at startup, so set the variable and roll a new task. A failed load is not retried.
3. No AWS adapter exists for rag's ResearcherDirectory or MetricRanking (rag calls it storage's, but storage cannot import rag). So ServingData reads the Spark JSON-lines export through storage's ObjectStore into rag's in-memory classes, with LocalRuntime-style checks. Without this no query could be answered. It is a stand-in; the reviewer or human may prefer an owner adapter.
4. SqsQueueConsumer sits in the worker, which owns the QueueConsumer port; it uses core AwsHttp.
5. Graph /v1/evidence always returns 404 (no per-evidence index; API Gateway routes evidence to the query service).
RISKS (not done, gaps, MODULE.md notes):
- Never run: server bind, assembly, image start, any real AWS call. AWS wire shapes, S3A export layout against ServingData, and SQS are checked only on fakes or by reading code.
- infra-engineer: (a) set SERVING_RUN_MANIFEST_URI on query-service after the pipeline run; (b) grant runtime-query s3:ListBucket on the graph bucket (prefix-limited); it has GetObject only on graph and rag, so the load fails without it; the run must be written to the graph bucket; (c) egress must also reach cognito-idp (JWKS), not just ECR, Logs and Bedrock. The services also need DynamoDB, S3, S3 Vectors, SQS and Neptune; (d) NEPTUNE_ENDPOINT="disabled" starts, but graph is never ready and queries fail.
- build-engineer (not blocking while LLM_ENABLED=false): the image has no prompt, so LLM_ENABLED=true exits at startup. On queryService add: .settings(Compile / resourceGenerators += Def.task { val rel = "prompts/runtime/answer-synthesis-v2.md"; val dst = (Compile / resourceManaged).value / rel; IO.copyFile((ThisBuild / baseDirectory).value / rel, dst); Seq(dst) }.taskValue)
- storage-engineer, step 2: each queue message body must be upickle JSON {"runId":..., "document": SemanticDocument}, matching EmbedMessage (storage cannot import it).
- storage-engineer, likely blocker for about 31,000 messages (from reading code, not reproduced): VectorLoader records each drain under one RunLedger entry (run, "load-vectors"), and each new report appends to priorOutputs, bounded by the 400 KB item limit. At 10 messages per drain (about 3,100 drains), writes may start failing, leaving messages unacknowledged and bound for the DLQ although vectors were written. Concurrent workers, or a direct loader run on the same run ID, can also conflict on that entry.
- ServingData memory for the 25k-work sample in a 1024 MiB task is unmeasured.
- Still in memory: audit, jobs, idempotency and evidence index (per task, lost on restart; with more than 1 query task, job, explain and evidence reads can miss).
- MODULE.md: new public API Bootstrap, ServiceApp, QueryWiring, ServingData, GraphWiring, WorkerWiring, SqsQueueConsumer, ObservedConsumer, EmbeddingWorker.step. "No Main", "no SQS adapter" and "RuntimeStores.communities not wired" are now done. MINOR-5's loop death is fixed; its "rejected" classification is unchanged.
MANUAL ACTION REQUIRED: none now. The orchestrator should route the infra, build and storage items above. For awareness: the aws-api MCP server announces end of development; migration guide https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 2, storage-engineer (message contract and ledger risk above)
