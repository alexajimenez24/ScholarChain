# ScholarChain service API

Source of truth: the services code and the tests in `tests/module-unit/services/`.
Facts here come from the Phase 10 reports (`docs/agent-runs/phase-10/`) and those tests.
Nothing here was run against a server: no Main class, Ember wiring or image exists, and
no cloud was used.
Every example below is a request or assertion taken from a unit test, driven through
`HttpRoutes` in memory with fakes, except the items marked "code fact".
A code fact is read from the source and has no asserting test.
Test names are given so the reviewer can find each one.

## Deployables and routes

| Service | Method and path | Scope | Success |
|---|---|---|---|
| queryService | `POST /v1/query` | `scholarchain/query.run` | 200 sync, 202 async |
| queryService | `GET /v1/jobs/{jobId}` | `scholarchain/query.run` | 200 |
| queryService | `GET /v1/jobs/{jobId}/result` | `scholarchain/query.run` | 200, or 202 |
| queryService | `GET /v1/explain/{queryId}` | `scholarchain/query.run` | 200 |
| queryService | `GET /v1/evidence/{evidenceId}` | `scholarchain/evidence.read` | 200 |
| graphService | `GET /v1/graph/nodes/{nodeId}` | `scholarchain/graph.read` | 200 |
| graphService | `GET /v1/graph/nodes/{nodeId}/neighbors` | `scholarchain/graph.read` | 200 |
| graphService | `GET /v1/evidence/{evidenceId}` | `scholarchain/evidence.read` | 200 |
| embeddingWorker | none besides health | none | none |
| all three | `GET /healthz`, `GET /readyz` | none | 200 |

The evidence route lives once in `servicesCommon` (`EvidenceRoutes`) and is mounted by
queryService and graphService.
Any other path is a typed 404 with request and trace IDs.

## Authentication

- Cognito JWT, verified inside every HTTP service, not only at the API Gateway.
- Checks: RS256 only, `kid` found in the JWKS, issuer, audience (`client_id` or `aud`)
  and expiry (30 s leeway), then one scope per route.
- Jobs, results and explanations are also limited to the subject that created the query.
  Another caller gets 404, not 403 (`a job, its result and its explanation belong to the
  caller that made the query`).
- Evidence read is not scoped to the caller: any holder of `evidence.read` can read any
  indexed item.
- Tests sign with a locally generated RSA key (`TestJwt`); no network or Cognito call.
- Health and readiness need no token (`health and readiness need no token`).
- Scope names and the `scholarchain` resource server are the service-engineer's choice.
  infra must create `scholarchain/query.run`, `scholarchain/graph.read` and
  `scholarchain/evidence.read`.

Request header form used by the tests (`QFx.get` and `QFx.post`):

```text
Authorization: Bearer <access token>
Content-Type: application/json
Idempotency-Key: key-0001
```

## POST /v1/query

Request body, built by `QFx.body` in `QueryServiceSuite`:

```json
{"question": "What are the collaboration metrics of \"Alice Arden\" since 2015?"}
```

Async form (`asyncBody`): add `"mode": "async"`.
Optional fields, as validated by the suite: `mode` (`sync` or `async`),
`constraints` (`yearFrom`, `yearTo`, `topics`, `institutions`, `venues`, `researchers`,
`researcherIds`) and `options` (`limit` 1 to 20, `maxPathDepth` 1 to 6).
`question` is 1 to 1000 characters.
Unknown top-level fields are 400; unknown constraint or option keys are 422.

Observed in `a synchronous query returns a verified, evidence-backed answer with IDs
and a metric`:
- status 200; `state` is `succeeded` and `mode` is `sync`;
- `queryId` starts with `qry-` and `jobId` with `job-`;
- `answer.abstained` is false, every claim has a non-empty `evidenceIds`,
  `answer.modelVersion` is `none` (deterministic synthesizer).

Observed in `an asynchronous query returns 202 and a job reference`:
- status 202, `state` is `queued`, header `Location: /v1/jobs/{jobId}`.
- Until the job finishes, `GET /v1/jobs/{jobId}` is 200 `queued` and
  `GET /v1/jobs/{jobId}/result` is 202 `queued`.
  The `Retry-After` header on that 202 is a code fact (`QueryRoutes.scala`), not asserted.
- Afterwards the result is 200 with the answer.
- A failed job (`a failed asynchronous job reports the typed error on the job and its
  result`): `GET /v1/jobs/{jobId}` is 200 with `state` `failed` and an `error` object,
  and only `GET /v1/jobs/{jobId}/result` returns the typed error status (502
  `dependency_failed` in the test).

Unknown researcher (`missing evidence: ...`): status 200 with `answer.abstained` true,
a non-empty `abstentionReason` and an empty `researchers` list.

Unsupported constraint (`an unsupported query constraint is 422 ...`): body
`{"question": ..., "constraints": {"nationality": "x"}}` gives 422,
`code` `unsupported_constraint` and `details.field` `constraints.nationality`.
Also 422: `yearFrom` 2020 with `yearTo` 2016, `yearFrom` 1200, 11 topics, `limit` 500.
`"yearFrom": "2015"` (a string) is 400.
A workflow that answers `Unsupported` is also 422 (`unsupported_constraint`, asserted).
`BudgetExceeded` mapping to 422 is a code fact, not asserted by a test.

### Idempotency

From `a repeated request with the same Idempotency-Key replays`:
- The key is optional, 1 to 128 characters of `[A-Za-z0-9_.:-]`; `bad key!` is 400.
- Scope is the caller subject plus the key; the query ID derives from their SHA-256.
- A replay returns the stored outcome, header `Idempotent-Replayed: true`, and the
  workflow ran once (counter 1).
- The same key with a different body is 409 `conflict`.
- Another subject with the same key is a different request.
- A retryable sync failure releases the key; so does a 503 for too many pending jobs.
- An async replay returns the same `jobId` and schedules once.

## GET /v1/explain/{queryId}

From `explain returns the plan, stages, versions, evidence IDs, scores, counters and no
reasoning`: 200 with `queryId`, `plan`, `resolutions`, `stages` (including `synthesis`,
`verification`, `audit`), graph calls (template IDs and windows), `versions`
(synthesizer, `modelVersion`, prompt version and SHA-256, run IDs including
`graphRunId`), `evidence.ids` with `evidence.scoreComponents`, synthesis attempts, claim
checks, removed claims (ID, status and reasons only), `counters` (`claimsChecked`,
`graphCalls`) and `verification`.
The body holds no prompt, reply or reasoning text; the test asserts that.
An unknown query is 404; an unfinished query is 409; a store failure is 502.

## GET /v1/evidence/{evidenceId}

From `end to end: a signed request, an evidence-backed answer, and each cited evidence
item`: a request signed with `scholarchain/query.run scholarchain/evidence.read` posts
`/v1/query` with `Idempotency-Key: e2e-key-1`, takes
`answer.claims[0].evidenceIds[0]`, then calls `GET /v1/evidence/{id}`.
Response 200 carries `evidenceId`, a non-empty `sourceUri` and
`provenance.runId` equal to the answer's run ID.
From the common and graph suites: `text` is cut to a bound and flagged with
`textTruncated`, and a graph evidence item carries `graphPath` and `provenance.source`.
The body never contains the bearer token.
Unknown ID is 404 `not_found`; an ID of 300 characters is 400.
In queryService the evidence index covers only workflows that process answered
(`BundleEvidenceLookup` over stored evidence bundles).

## Graph exploration (graphService)

`GET /v1/graph/nodes/{nodeId}` returns 200 with `id`, `labels` and `properties`, for
example `labels[0]` `Researcher` and `properties.displayName` `Ada`.
Unknown node is 404; a 500-character ID is 400.

`GET /v1/graph/nodes/{nodeId}/neighbors?edgeTypes&direction&depth&pageSize&cursor`:
- defaults: both directions, every allowed edge type, depth 1;
- `pageSize` maximum 50 (51 and 0 are 422); at most 200 results across pages;
  the default of 20 and the 200 bound are code facts, not asserted (the bound test uses
  `maxResults = 5`);
- depth maximum 1 and only the four allowlisted window-free edge types, because storage
  has one-hop templates only;
- pages are cursor-linked: with `?pageSize=3` the test sees `w001` to `w003`, then
  `?pageSize=3&cursor=<nextCursor>` gives `w004` to `w006`, then `w007` with no
  `nextCursor` and `truncated` false;
- the store is asked for one row more than the page, never more than the bound (limits 4,
  7 and 10 in that test);
- at the configured result bound (5 in the test) the last page has `truncated` true and
  no cursor;
- the cursor is opaque and bound to node, direction and edge types, so reuse with
  `direction=out` is 400;
- out-of-bound values are 422; malformed values (`cursor=garbage`) are 400; nothing
  reaches the store in either case.

## embeddingWorker

No business routes; only `/healthz` and `/readyz`.
It drains a queue (`QueueConsumer`, SQS-shaped; `WorkQueueConsumer` adapts the Phase 08
`WorkQueue`) through the idempotent `VectorLoader`.
Message body: `{"runId", "document": SemanticDocument}`.
A malformed, oversized or loader-rejected message is never acknowledged, so the real
queue redrives it to the dead-letter queue.
A store failure leaves the batch unacknowledged; a redelivery is a no-op; each drain
emits one EMF line with no document text (`WorkerSuite`).

## Errors and status mapping

Every error body (`the error body has the code, message and both IDs`):

```json
{"error": {"code": "not_found", "message": "...", "retryable": false,
           "requestId": "r1", "traceId": "t1", "details": {"field": "f"}}}
```

`details` is optional.

| Status | Code | Used for |
|---|---|---|
| 400 | `invalid_json`, `invalid_request` | bad JSON (body not echoed), bad shape, unknown top-level field, bad ID, key or cursor |
| 401 | `unauthorized` | no, bad or expired token; carries `WWW-Authenticate: Bearer` |
| 403 | `forbidden` | missing scope |
| 404 | `not_found` | unknown job, query, evidence or node; another caller's job; unmatched route |
| 409 | `conflict` | key reused with another body; explain of an unfinished query |
| 413 | `payload_too_large` | body over the limit |
| 415 | `unsupported_media_type` | not `application/json` |
| 422 | `unsupported_constraint` | constraint or option out of bound; workflow Unsupported or BudgetExceeded |
| 500 | `internal_error` | unexpected exception; the message is not exposed |
| 502 | `dependency_failed` | downstream port failed; its message is not leaked |
| 503 | `unavailable` | breaker open, store full (1000 in-flight records), too many pending jobs, not ready |
| 504 | `timeout` | downstream call exceeded its timeout |

Every response carries `X-Request-Id` and `X-Trace-Id`.
A caller request ID is kept only if it matches `[A-Za-z0-9_.=-]{1,128}`.
The trace ID comes from `X-Amzn-Trace-Id`, then `traceparent`, then `X-Trace-Id`,
else it is generated.
Secrets, tokens and exception text are not in bodies or logs: in `ServiceKitSuite`,
`a typed error maps to its status; an unexpected exception is a generic 500 that leaks
nothing` throws `secret-password=hunter2` and asserts it is absent from the body.

## Health and readiness

- `GET /healthz` is liveness: 200 `{"status": "ok"}`, no dependency touched.
- `GET /readyz` runs the readiness checks, each bounded by 2 s: 200 `{"status": "ready",
  "checks": {"store": "ok"}}`, or 503 `unavailable` with `details` naming each failing
  check, for example `{"audit-store": "failing"}`.
  A check that throws or hangs counts as failing.

## Timeouts, retries and breaker

Defaults of `ResiliencePolicy` in `servicesCommon` (`Downstream`, one per port):
- 2 s per attempt, at most 2 attempts, linear backoff 100 ms;
- breaker opens after 5 consecutive failed calls and stays open 30 s;
- a not-found (`Right(None)`) never trips the breaker;
- readiness check timeout 2 s;
- request body at most 64 KB (kit); `POST /v1/query` at most 16 KB;
- queryService: at most 8 pending async jobs (503 beyond), record store of 1000 queries.
  When the store is full, the oldest Succeeded or Failed record and its idempotency
  claim are evicted.
  "Store full" 503 means all 1000 records are in flight.
  An evicted job, result or explain returns 404.
  A replay of an evicted idempotency key runs the query again.

The suites use shorter policies: the query workflow runs with 5 s and one attempt so a
query is never re-run, a hang test uses 20 ms, and the breaker threshold is 3.
The production policy is a wiring choice that has not been built.

## Metrics decision

- `GET /metrics` is not exposed; every service answers 404 (`metrics are EMF lines and
  /metrics is not exposed`).
- Metrics leave as CloudWatch Embedded Metric Format lines on stdout; ECS ships stdout
  to CloudWatch Logs, which extracts them.
  No agent, no scrape endpoint.
- One EMF line per request with `Service` and `Operation` dimensions and `LatencyMs`.
  `Operation` is the method plus a prefix from a fixed allowlist (`healthz`, `readyz`,
  `v1/query`, `v1/jobs`, `v1/explain`, `v1/evidence`, `v1/graph`), so job IDs do not
  inflate cardinality.
  Any unmatched path, and any error before routing, whose prefix is not allowlisted emits
  the single value `unmatched` (`unmatched paths share one constant EMF Operation`).
- A failing sink never fails a request; a line never holds the token.
- The `_aws` envelope asserted in `CommonSuite`: `Timestamp` as a number,
  `CloudWatchMetrics[0].Metrics` with `Name` and `Unit` (`Milliseconds` for latency).

## Configuration note

`LLM_ENABLED` defaults to false and selects the deterministic synthesizer.
`true` without a model synthesizer is refused; any value other than `true` or `false`
is an error.
Open finding R6-1 (Phase 08b) keeps the model path off until Phase 11.
