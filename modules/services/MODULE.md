# Module `services`

## Purpose

The REST microservices, split into one shared library and three deployables:

| sbt project | Folder | Role | Image |
|---|---|---|---|
| `servicesCommon` | `modules/services/common` | http4s middleware: Cognito JWT verification, request and trace IDs, timeouts, typed error model, CloudWatch Embedded Metric Format output | none (library) |
| `queryService` | `modules/services/query` | `/v1/query`, `/v1/jobs`, `/v1/evidence`, `/v1/explain`, health and readiness | `scholarchain-query-service` |
| `graphService` | `modules/services/graph` | `/v1/graph/nodes/...` bounded neighbourhood reads, health and readiness | `scholarchain-graph-service` |
| `embeddingWorker` | `modules/services/worker` | SQS consumer that embeds text chunks into S3 Vectors | `scholarchain-embedding-worker` |

An aggregate project named `services` (base `modules/services`, no sources) groups the
four, so `sbt services/test` runs them all; it is the spec's required `services` project.

All four run http4s Ember on cats-effect.
Ingress is an API Gateway HTTP API with a VPC Link to Cloud Map service discovery; there
is no load balancer.

## Owner and paths

- Owner: service-engineer (documentation-engineer owns only this file).
- Source roots (the `.gitkeep` placeholders remain):
  - `modules/services/common/src/main/scala`
  - `modules/services/query/src/main/scala` and `src/main/resources`
  - `modules/services/graph/src/main/scala`
  - `modules/services/worker/src/main/scala`
- Unit-test roots: `tests/module-unit/services/<common|query|graph|worker>/` (never
  `src/test`).

## Public API

Routes, request and response examples, auth, errors and timeouts are in
[docs/api.md](../../docs/api.md).
Summary by project:

- `servicesCommon` exports `ApiError` and `ErrorCode`, `RequestIds`, `JwtVerifier`,
  `Guard`, `Jwks`, `Scopes`, `Downstream` and `ResiliencePolicy`, `ServiceKit` (request
  and trace IDs, health, readiness, size limits, error mapping), `JsonBody`, `Json`,
  `Emf` and `MetricsSink`, and `EvidenceRoutes` with the `EvidenceLookup` port.
- `queryService`: `POST /v1/query`, `GET /v1/jobs/{jobId}`,
  `GET /v1/jobs/{jobId}/result`, `GET /v1/explain/{queryId}`, `GET /v1/evidence/{id}`.
  Ports: `QueryEngine`, `JobScheduler`, `AuditLog`, evidence bundles
  (`BundleEvidenceLookup`).
- `graphService`: `GET /v1/graph/nodes/{nodeId}`, `.../neighbors` (bounded, cursor
  paginated), `GET /v1/evidence/{id}`.
- `embeddingWorker`: health and readiness only; `QueueConsumer` drains the queue into
  the idempotent `VectorLoader`.
- Every service: `GET /healthz`, `GET /readyz`; `/metrics` is not served (EMF on stdout).
- Auth: Cognito JWT verified in every service; scopes `scholarchain/query.run`,
  `scholarchain/graph.read`, `scholarchain/evidence.read`.

## Done

- The four sbt projects and the `services` aggregate (phase 2).
- Phase 10 (nothing committed yet): all routes above, the typed error model, JWT and
  scope checks, request and trace IDs, size limits, timeouts, bounded retries and a
  circuit breaker, Idempotency-Key, sync and async queries, jobs, explain without
  chain-of-thought, cursor-paginated neighbours, EMF metrics, and the worker drain.
- Unit tests under `tests/module-unit/services/`, run by `sbt services/test`:

| Project | Tests |
|---|---|
| servicesCommon | 29 |
| graphService | 16 |
| embeddingWorker | 10 |
| queryService | 22 |
| total | 77 (reviewer's count from reading the suites) |

- Repair round 1 (`docs/agent-runs/phase-10/repair-01-service-engineer.md`):
  `QueryStore` evicts the oldest terminal record (and its idempotency claim) when full and
  refuses only when every record is in flight; the async pending slot is reserved before
  `store.add`, so a pending 503 leaves no record.
  The EMF `Operation` is the method plus an allowlisted prefix, else `unmatched`.
  Four tests were added (queryService 3, servicesCommon 1); `sbt check` exited 0.
  Per-project totals after repair were not observed.
- The full `sbt check` exited 0 once at the end of step 2 (22m23s,
  `docs/agent-runs/phase-10/agent-report.md`).
  Review done: re-check `review-report-02.md` is APPROVE (0 CRITICAL, 0 MAJOR, 7 MINOR
  carried forward).

## Not done

- No `Main` or Ember server wiring, no real AWS adapters (SQS, DynamoDB, Neptune, S3
  Vectors), no image; `Jwks.httpFetch` (HTTPS JWKS fetch) is untested for lack of network.
- The production `ResiliencePolicy` per port is a wiring choice, not built.
- No cloud, server or Docker run; nothing measured against a live API.
- The model-written answer path stays off (`LLM_ENABLED=false`) until R6-1 is closed.

## Known issues

- The deployables have no main class, and the image ENTRYPOINT uses `-jar`, so no
  image can start until a main class exists.
- Jobs, idempotency records and the evidence index are in memory only; after a restart
  earlier queries cannot be explained or fetched and in-flight async jobs are lost.
  A persistent store is a storage-engineer item.
- Storage has no per-evidence index (bundles are keyed by bundle ID); a deployable must
  wire an `EvidenceLookup` adapter.
- Evidence read is not scoped to the caller.
- Neighbour pages are ordered by (nodeId, edgeId) in Scala after fetching offset, page
  and one more row; the template orders by nodeId only, so parallel edges to one node
  could straddle a LIMIT cut.
  At the 200-result bound `truncated` means a full fetch and there may be more.
- Graph depth is capped at 1 because storage has one-hop templates only.
- `TestJwt` is copied into three test roots (common, graph, query), because test roots
  are separate per project.
- infra must create the three Cognito scopes above.
- Carried forward from the review (`docs/agent-runs/phase-10/review-report.md`):
  - MINOR-1: `token_use` is not checked; no tests for missing `exp`, `nbf`, `sub` or the
    30 s leeway.
  - MINOR-2: `explain` returns `synthesisAttempts[].reason` and `synthesisFallback`, which
    on the model path carry up to 200 characters of LLM client error text (latent while
    `LLM_ENABLED=false`).
  - MINOR-3: the timeout needs an interruptible port; the default policy (2 attempts)
    would re-run a non-idempotent workflow if wired in.
  - MINOR-4: a sync request cancelled mid-workflow leaves the record Running and its key
    claimed, so replays get 409.
  - MINOR-5: an exception from the worker's `receive` or `acknowledge` ends its loop while
    `/healthz` stays 200; a transient PutVectors `Left` counts as `rejected`.
  - Store capacity is not in `/readyz`; replaying an evicted key re-runs the query.
- S1 fixed two bugs: ujson writes a Long as a JSON string (EMF `Timestamp`, JWT `exp`),
  and a Long.MinValue overflow in the JWKS refresh timer.
  S2 fixed `/readyz` building a second request ID, so the error body `requestId` differed
  from the `X-Request-Id` header.

## Dependencies

- `servicesCommon`: `core`; http4s-ember-server and http4s-dsl 0.23.38, cats-effect 3.7.1.
- `queryService`: `core`, `rag`, `storage`, `servicesCommon`.
- `graphService`: `core`, `storage`, `servicesCommon`.
- `embeddingWorker`: `core`, `storage`, `servicesCommon`.
- The three deployables add logback-classic 1.5.38 in Runtime scope.
- Each deployable is packaged by `<project>/assembly` into `target/services/<image>/`;
  `dockerLocal` builds all three images from `docker/services/Dockerfile`.

Allowed by the boundary table in `AGENTS.md`; no module depends on a service project,
and the three deployables never depend on one another.
