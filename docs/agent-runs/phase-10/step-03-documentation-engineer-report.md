HARNESS: claude-code
MODEL: sonnet (standard tier, no override)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 10 step 3: docs/api.md, modules/services/MODULE.md, STATUS.md once for the phase.
FILES CREATED: docs/api.md
FILES MODIFIED: modules/services/MODULE.md (Public API, Done, Not done, Known issues rewritten; Purpose and Dependencies kept); STATUS.md (Phase 10 bullet in "Where things stand", new "Phase 10 task list" section before "Phase 08b follow-up")
COMMANDS RUN: reads of the reports and the services test suites; python edits; awk line-length checks; `git status --short`. No sbt, no server, no commit.
TEST RESULTS: none run. Counts documented are from the reports: servicesCommon 28, graphService 16, embeddingWorker 10, queryService 19 (73 total, 0 failed); the full sbt check exited 0 once in 22m23s.
VALIDATION RESULTS:
- docs/api.md covers: routes per service with scopes, auth, POST /v1/query, idempotency, explain, evidence, graph neighbours and cursors, the worker, the error table with status mapping, health and readiness, timeouts, the metrics decision (no /metrics, EMF on stdout) and LLM_ENABLED.
- The tests build their JSON in code (QFx.body, ujson.Obj) rather than holding literal files. The examples are therefore the requests and assertions the tests make, with the test names cited. Response bodies are described by the fields the tests assert, not by an invented full body.
- Line length is at most 90 except table rows, which also exceed it in existing notes (one api.md row, the MODULE.md purpose table, STATUS.md task table). No em dash was written.
- Links: docs/api.md is linked from STATUS.md; `../../docs/api.md` from MODULE.md resolves from modules/services/. docs/agent-runs/phase-10 files named in the notes exist.
ASSUMPTIONS:
- "Retry-After" on the 202 job result and the "timestamp number" EMF fact come from the S2 report and CommonSuite.
- STATUS.md was edited only in the Phase 10 areas. Older stale lines (for example the Phase 09 bullet that says "nothing is committed", while git log shows 3703a91) were left alone.
RISKS:
- I did not replay any command: the docs cite tests only, and no sbt was allowed. The code-reviewer must check docs/api.md against the tests, as the prompt says.
- Statements about the production timeout policy, scope names and in-memory state come only from the S1 and S2 reports.
- The code itself was not read except through the tests; the route and status tables rely on the reports.
- PROJECT_MEMORY.md was not changed (no decision rows were requested). Candidate rows for the human: no /metrics with EMF on stdout; JWT verified in every service; evidence route in servicesCommon.
- State left: nothing committed; the Phase 10 sources and tests remain untracked, plus the three files above.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer review (review-report.md), including the docs/api.md versus tests check.
