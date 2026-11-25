HARNESS: claude-code
MODEL: sonnet (standard tier, effort per .agents/models.json), no override

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: Phase 10, task S1 (servicesCommon, graphService, embeddingWorker). queryService (S2) not touched.
FILES CREATED:
- modules/services/common/src/main/scala/scholarchain/services/common/: ApiError.scala (error model, RequestIds), Auth.scala (JWT, JWKS, Guard, Scopes), Resilience.scala (Downstream), Emf.scala, Evidence.scala (EvidenceLookup port, EvidenceRoutes), ServiceKit.scala (middleware, health/ready, JsonBody, Json)
- modules/services/graph/src/main/scala/scholarchain/services/graph/: GraphReader.scala, GraphRoutes.scala (also GraphApp)
- modules/services/worker/src/main/scala/scholarchain/services/worker/EmbeddingWorker.scala (also WorkerApp, queue adapter)
- tests/module-unit/services/common/: TestJwt.scala, CommonSuite.scala
- tests/module-unit/services/graph/: TestJwt.scala (copy, own package), GraphServiceSuite.scala
- tests/module-unit/services/worker/WorkerSuite.scala
FILES MODIFIED: none (the .gitkeep placeholders were left in place).
COMMANDS RUN (all observed to succeed): `sbt servicesCommon/compile`, `graphService/compile`, `embeddingWorker/compile`, `servicesCommon/test`, `graphService/test`, `embeddingWorker/test`, then `scalafmt --config .scalafmt.conf` on my own 14 files only, then `sbt services/test` (timeout 30m, foreground). No full sbt check, no server, no network.
TEST RESULTS: `sbt services/test` after formatting: common 28 passed, graph 16 passed, worker 10 passed; 54 total, 0 failed; queryService has 0 tests (S2). Two real bugs were found and fixed on the way: ujson turns a Long into a JSON string (EMF Timestamp and JWT exp), and a Long.MinValue overflow in the JWKS refresh timer.
VALIDATION RESULTS:
- Route table: graphService: GET /v1/graph/nodes/{nodeId}; GET /v1/graph/nodes/{nodeId}/neighbors?edgeTypes&direction&depth&pageSize&cursor; GET /v1/evidence/{evidenceId}; GET /healthz; GET /readyz. embeddingWorker: /healthz and /readyz only. GET /metrics is not exposed (404). Metrics leave as EMF lines on stdout (decision: ECS to CloudWatch Logs extracts them; no agent or scrape endpoint).
- Status mapping: 400 invalid_request and invalid_json, 401 unauthorized (with WWW-Authenticate: Bearer), 403 forbidden, 404 not_found, 409 conflict, 413 payload_too_large, 415 unsupported_media_type, 422 unsupported_constraint (depth, pageSize or edgeTypes out of bound), 500 internal_error, 502 dependency_failed, 503 unavailable (breaker open or not ready), 504 timeout. Every error body is {"error":{code,message,retryable,requestId,traceId,details?}}. Headers X-Request-Id and X-Trace-Id are on every response; a caller ID is kept only if it matches [A-Za-z0-9_.=-]{1,128}; the trace ID comes from X-Amzn-Trace-Id, then traceparent, then X-Trace-Id, else generated.
- Timeout policy (ResiliencePolicy defaults): 2 s per attempt, max 2 attempts, linear backoff 100 ms, breaker opens after 5 consecutive failed calls for 30 s, readiness check timeout 2 s, request body max 64 KB (Content-Length check, and bounded streaming read in JsonBody). Graph bounds: pageSize default 20, max 50; at most 200 results across pages (template limit); depth max 1 (allowlisted templates are one hop); edge types only the 4 allowlisted window-free types; cursor is opaque, bound to node, direction and edge types.
- Dependency graph: unchanged from build.sbt; servicesCommon -> core; graphService and embeddingWorker -> core, storage, servicesCommon. No build file edits were needed (no JWT library is used; verification uses the JDK Signature SHA256withRSA plus uPickle/ujson).
- Authentication: Cognito JWT verified inside every HTTP service, not only at the gateway: RS256 only, kid looked up in the JWKS, issuer, audience (client_id or aud) and expiry (30 s leeway) checked, then a scope per route (scholarchain/graph.read, scholarchain/evidence.read). Tests sign with a local RSA key.
- Worker: queue port QueueConsumer is SQS-shaped (receive, acknowledge); WorkQueueConsumer adapts the Phase 08 WorkQueue[String]. Message body is {"runId","document":SemanticDocument}. Messages go through VectorLoader.load grouped by run. A malformed, oversized or loader-rejected message is never acknowledged (kept in deadLetters, i.e. what the real queue redrives to the DLQ); a store failure leaves the batch unacknowledged; a redelivery is a no-op.
ASSUMPTIONS:
- The addendum puts "evidence by ID" in graphService, but the S2 end-to-end test needs it in queryService, which cannot depend on graphService. So the route and its EvidenceLookup port live once in servicesCommon (EvidenceRoutes); graphService mounts it, and S2 can mount it too.
- Evidence scope name and the Cognito resource server "scholarchain" are my choice; infra must create matching scopes.
- Depth is capped at 1 because storage has only one-hop neighbour templates.
RISKS:
- Not done: no Main or Ember server wiring, no real AWS adapters, no image (nothing may run in this phase); Jwks.httpFetch (HTTPS JWKS fetch) is untested for lack of network; the Idempotency-Key helper is left to S2 (graph has no POST).
- No per-evidence index exists in storage (EvidenceBundles is keyed by bundle ID); a deployable must wire an EvidenceLookup adapter. S2's query service can build one from the bundles it stores. A storage-engineer item if a persistent index is wanted.
- Neighbour pages are ordered by (nodeId, edgeId) in Scala after fetching offset+page+1 rows; the template orders by nodeId only, so parallel edges to one node could straddle a LIMIT cut. At the 200-result bound "truncated" means "a full fetch, there may be more".
- Notes for modules/services/MODULE.md: public API as in the route table above; common exports ApiError/ErrorCode, RequestIds, JwtVerifier/Guard/Jwks, Downstream/ResiliencePolicy, ServiceKit, Emf/MetricsSink, EvidenceRoutes; the "Known issues: no main class" entry still stands; test counts 28/16/10.
- Test helper TestJwt is duplicated in common and graph test roots (test roots are separate per project); S2 will need a third copy.
- State left behind: new files only, nothing committed. The formatter hook reformatted two of my Write-created files; the other files were formatted with scalafmt on my own paths only.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: S2 (service-engineer, queryService), then one full sbt check.
