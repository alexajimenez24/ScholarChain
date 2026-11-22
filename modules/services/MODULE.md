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
- Source roots, empty `.gitkeep` placeholders only:
  - `modules/services/common/src/main/scala`
  - `modules/services/query/src/main/scala` and `src/main/resources`
  - `modules/services/graph/src/main/scala`
  - `modules/services/worker/src/main/scala`
- Unit-test roots: `tests/module-unit/services/<common|query|graph|worker>/` (never
  `src/test`).

## Public API

None yet.
No source exists, so there is nothing to document or to test black-box.

## Done

- The four sbt projects are defined with no application logic (phase 2).
- Source and unit-test roots exist as `.gitkeep` placeholders.

## Not done

- All application code and all unit tests.
- No test case exists; `sbt check` reports 0 test cases for these projects.

## Known issues

- The deployables have no main class yet, and the image ENTRYPOINT uses `-jar`, so no
  image can start until a main class exists.

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
