```text
Use the service-engineer agent to implement ScholarChain RESTful microservices in Scala 3.

Required services:

1. Query API
- POST /v1/query
- Validate request.
- Create query ID and idempotency record.
- Start synchronous or asynchronous Graph-RAG workflow.
- Return answer or job reference.

2. Jobs API
- GET /v1/jobs/{jobId}
- GET /v1/jobs/{jobId}/result
- Report state, timestamps, stage, errors, and result link.

3. Evidence API
- GET /v1/evidence/{evidenceId}
- Return provenance, source record, source URI, graph path or text pointer, and compact supporting content.

4. Graph exploration API
- GET /v1/graph/nodes/{nodeId}
- GET /v1/graph/nodes/{nodeId}/neighbors
- Bound depth, edge types, result count, and timeout.

5. Explain and audit API
- GET /v1/explain/{queryId}
- Return QueryPlan, stages executed, prompt versions, model versions, evidence IDs, score components, counters, and verification status.
- Do not return hidden chain-of-thought.

6. Operations endpoints
- GET /healthz
- GET /readyz
- GET /metrics when the deployment model permits it

Required HTTP behavior:
- JSON request and response contracts.
- Path-based API versioning.
- Request IDs and trace IDs.
- Idempotency-Key support for POST operations.
- Pagination or cursoring for graph neighborhoods.
- Timeouts, bounded retries, and circuit breakers.
- Typed error model.
- Authentication and authorization appropriate to the course deployment.
- No secrets or raw credentials in responses or logs.
- Thin routes; application logic belongs in services and ports.

Required tests:
- Successful synchronous query.
- Asynchronous query path.
- Invalid JSON.
- Unsupported query constraint.
- Missing evidence.
- Idempotent repeated request.
- Timeout and downstream failure.
- Unauthorized request.
- Health and readiness distinction.
- Full query-to-evidence lifecycle with fakes.

Run sbt check.
Report route table, status-code mapping, timeout policy, dependency graph, authentication method, and exact test counts.
```

Addendum from the human architect for this repository:

- Scope: build only what this work order and the Phase 10 gate require. Nothing optional.
  Carried-forward items from earlier phases stay where they were assigned.
- Work without stopping to ask: take the most reasonable reading, record each choice and
  why in the step report, and carry on. Stop only for BLOCKED-ON, and route it as the
  rules say.
- Keep usage low: read only the files your step needs, do not explore the repository,
  and keep each report under 60 lines with the required fields. The orchestrator answers
  stop-hook messages in one line and does not restate earlier reports.
- Reference material: ~/ref-phase-10/ holds the collaborative repository's services.
  Read ~/ref-phase-10/REFERENCE.md first. This repository's service design is
  deliberately different (http4s Ember, four projects and three images, API Gateway,
  Cognito JWT, CloudWatch EMF); take behaviour and test ideas from the reference, not
  its HTTP layer, auth or structure. No owner writes outside its territory.
- No cloud in this phase: no AWS call, no credentials, no network. JWT tests use a
  locally generated RSA key; stores are the in-memory fakes with small bounded fixtures
  (do not load the 25,000-work export); the rag workflow runs with its deterministic
  synthesizer. The model-written path stays off by default (`LLM_ENABLED=false`),
  because Phase 08b finding R6-1 is open until Phase 11.
- Inputs: the services call this repository's rag workflow (Phase 09, claim-verifier-v3)
  and storage ports (Phases 07 and 08) as committed. Never edit them; ask through
  BLOCKED-ON.
- Formatting: every agent formats only the files it owns, never `scalafmtAll`.
- Order, one owner at a time:
  1. service-engineer, task S1: servicesCommon (Cognito JWT verification and scope
     checks, request and trace IDs on every response, the typed error model with the
     status-code mapping, request size limits, timeouts, bounded retries and a circuit
     breaker for downstream calls, EMF metric output), then graphService (nodes, bounded
     and cursor-paginated neighbours, evidence by ID) and embeddingWorker (drains the
     Phase 08 queue through the idempotent loader; a malformed message is left for the
     dead-letter queue; health only), each with health and readiness, and tests for
     each. Report as step-01-service-engineer-report.md.
  2. service-engineer, task S2: queryService (POST /v1/query with validation, query ID,
     Idempotency-Key, synchronous and asynchronous paths, jobs and job results, explain
     without chain-of-thought), every required test in the work order, and one
     end-to-end test on bounded fixtures that goes from a signed request to POST
     /v1/query, through an evidence-backed answer, to GET /v1/evidence/{id}. Then run
     the full sbt check once. Report the route table, status-code mapping, timeout
     policy, dependency graph, authentication method and exact test counts as
     agent-report.md.
  3. documentation-engineer: docs/api.md (routes per service, request and response
     examples copied from the tests, auth, errors, timeouts, the metrics decision),
     modules/services/MODULE.md, and STATUS.md once for the whole phase, with only facts
     observed here. Report as step-03-documentation-engineer-report.md.
  The orchestrator records every step in run-log.md. STATUS.md is updated only in step 3
  and when a step blocks.
- Then one fresh code-reviewer review (review-report.md), which also checks that the
  examples in docs/api.md match the tests, and one repair round (repair-01*.md). The
  repair round fixes CRITICAL and MAJOR findings, and MINOR findings only where they
  break a gate item; everything else is carried forward to the human. Run a fresh
  re-check (review-report-02.md) only if the review had a CRITICAL or MAJOR finding.
  There is no second repair round.
- Machine limits (8 GB laptop, 5 GB for WSL): never more than one sbt at once, no
  background shells, no polling or wait loops of any kind (including
  `while pgrep ...; do sleep ...; done`), no mutation testing; every long command runs in
  the foreground under `timeout` (sbt test runs `timeout 30m`, the full check
  `timeout 45m`). No server is started on a port and no Docker image is built in this
  phase. While working, run only the services projects' tests; the full sbt check runs
  once, at the end of step 2, and again only if a repair changes source. If the harness
  moves sbt to the background, do not start anything else and do not poll; wait for it.
- No agent commits, runs cleanup.sh or touches cloud resources in this phase.
