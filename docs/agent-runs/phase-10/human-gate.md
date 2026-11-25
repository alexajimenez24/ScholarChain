# Phase 10 human gate

## How Phase 10 was produced

The owning agents did the work through the orchestrator, one owner at a time:
service-engineer (S1: servicesCommon, graphService and embeddingWorker; S2: queryService,
its tests and the end-to-end test), documentation-engineer (`docs/api.md`, the module notes
and STATUS), and two fresh code-reviewers. The work order and my addendum are in
`prompt.md`. Every step is in `run-log.md`, and every report is saved in this folder.

As in earlier phases, I gave the agents reference material from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-10/`. Its
services use a different HTTP layer, authentication and structure, so only behaviour and
test ideas were taken from it.

No cloud was used: no AWS call, no credentials, no network, no server on a port and no
Docker image. JWT tests sign tokens with a locally generated RSA key, stores are the
in-memory fakes with small bounded fixtures, and the rag workflow runs with its
deterministic synthesizer. The model-written path stays off (`LLM_ENABLED=false`) while
Phase 08b finding R6-1 is open.

## What was checked

- Four projects, three deployable services: `servicesCommon` (library), `queryService`
  (`/v1/query`, `/v1/jobs/*`, `/v1/explain/*`), `graphService` (`/v1/graph/*`,
  `/v1/evidence/*`) and `embeddingWorker` (no public routes). The deployables do not
  depend on one another.
- servicesCommon: Cognito JWT verification (issuer, audience, expiry, RS256 signature) and
  scope checks in every HTTP service, request and trace IDs on every response, the typed
  error model and status-code mapping, request size limits, timeouts, bounded retries, a
  circuit breaker for downstream calls, and CloudWatch EMF metric output. `/metrics` is
  not served; the reason is recorded in `docs/api.md`.
- graphService: nodes, bounded and cursor-paginated neighbours, and evidence by ID.
  embeddingWorker: drains the Phase 08 queue through the idempotent loader and leaves a
  malformed message for the dead-letter queue. Every service has health and readiness.
- queryService: POST /v1/query with validation, query ID, Idempotency-Key, synchronous and
  asynchronous paths, jobs and job results, and explain without chain-of-thought.
- One end-to-end test on bounded fixtures goes from a signed request to POST /v1/query,
  through an evidence-backed answer, to GET /v1/evidence/{id}.
- Tests: services 77 (servicesCommon 28, graphService 16, embeddingWorker 10, queryService
  19 at S2, plus 4 added in the repair). The full `sbt check` exited 0 after S2 and again
  after the repair round.
- Review 1 (`review-report.md`): 0 CRITICAL, 2 MAJOR, 8 MINOR. The repair round fixed
  both MAJOR findings (the query store filling with finished queries, and unbounded EMF
  metric dimensions) and the three documentation mismatches between `docs/api.md` and the
  tests, which broke a gate item. Review 2 (`review-report-02.md`) returned APPROVE, with
  0 CRITICAL, 0 MAJOR and 7 new MINOR findings.
- After review 2, I approved a one-step documentation fix for MINOR-F and MINOR-G
  (`repair-02-documentation-engineer.md`): STATUS.md and `modules/services/MODULE.md` now
  record the approval and the 77 services tests, and `docs/api.md` describes query-store
  eviction (at 1,000 records the oldest finished query and its idempotency claim are
  dropped, so its job, result or explain returns 404). Documentation only; no source
  changed after the last full `sbt check`.

## Accepted, with known gaps

Phase 10 is accepted as committed. Carried forward to Phase 11 (details in the two review
reports):
- From review 1: the JWT `token_use` claim is not checked (MINOR-1), downstream error text
  can appear in explain (MINOR-2), a timeout that relies on the port being interruptible
  (MINOR-3), a cancelled synchronous request leaving its Idempotency-Key claimed
  (MINOR-4), and the worker loop ending without a log line (MINOR-5).
- From review 2: an eviction race (MINOR-A), an untrimmed evidence index (MINOR-B),
  cancelled requests refilling the store (MINOR-C), the HTTP method as an unbounded
  metric dimension (MINOR-D), and test gaps (MINOR-E).
- Still open from earlier phases: R6-1 to R6-3 (claim verifier) and the guard and stop-hook
  items, all in Phase 11.

## Manual changes

- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 10 is complete. I approve starting Phase 11.
