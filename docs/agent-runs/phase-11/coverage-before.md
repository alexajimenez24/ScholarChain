# Phase 11 coverage map before new tests (test-engineer, step 1)

Read from test names and assertions only (no `src/main`). Adv = `tests/adversarial`
(black-box, test-engineer); MU = `tests/module-unit/<module>` (owner tests). Commit f11010b.

| # | Work-order category | Existing tests | Gap |
|---|---|---|---|
| 1 | Malformed, truncated DBLP XML | Adv DblpXmlAttackSuite (13); MU hadoop DblpParser, RejectsAndLimits, RepairDblp | none for UTF-8; ISO-8859-1 black-box case missing (C18) |
| 2 | Unicode NFC/NFD, confusables | Adv UnicodeSuite (8), WorkflowGate Cyrillic "Wei Wang"; MU core Normalize, ER ErNormalize | ISO-8859-1 decoding at split boundaries not black-box tested |
| 3 | Duplicate, conflicting DOI | Adv DuplicateIdSuite (11); MU hadoop JobLogic, ER MatchPolicy (DOI CONFLICT) | J03 oversize exact key (OVERSIZE_BLOCKS + reject line) untested at job level (C18/N7) |
| 4 | Common names, reordered authors | Adv CommonNameSuite (10), DuplicateId order test; MU ER Features (both orders), MatchPolicy | none |
| 5 | Very large blocking groups | Adv CommonNameSuite (OVERSIZE_BLOCKS, buffer cap, fallback compared) | J04 FALLBACK_BLOCKS_REFUSED + reject line untested at job level (C18/N7) |
| 6 | Unresolved/conflicting coauthor endpoints | MU spark Canonical (contradiction cut, dangling AUTHORED), storage Neptune (missing endpoint, wrong order) | no black-box test (owner tests adequate) |
| 7 | Empty and disconnected graphs | MU spark GraphAlgorithms (isolated vertex, unreachable) | no empty-graph test anywhere; no runtime test on an empty or disconnected served graph |
| 8 | Degree, component, path, bridge, k-core known graphs | MU spark GraphAlgorithms (6), WindowAnalytics (5), PathSelection (4); MU rag Stage (rankings, paths) | none at algorithm level |
| 9 | Large teams, common-name resolution | Adv GiantTeamSuite (13), WorkflowGate ambiguous "Wei Wang" (6) | none |
| 10 | Missing topic/institution/abstract/profile | Adv MissingFieldSuite (DBLP fields); MU spark Export/Pipeline (no abstracts), ER Features (missing profiles) | runtime: unknown topic filter, researcher without community or profile not tested black-box |
| 11 | Duplicate vector keys, stale model versions | MU storage Vector (duplicate keys refused), RenderedLoad; MU rag EmbeddingVersion (8) | none |
| 12 | Prompt injection (title, abstract, chunks) | Adv WorkflowGate (2); MU rag SemanticContext, Synthesis, Verification, Pipeline | none |
| 13 | LLM malformed JSON, fabricated IDs | Adv WorkflowGate (validation, fabricated); MU rag Synthesis, BedrockLlm; ER Adjudication | truncated or non-object reply through the whole workflow not black-box tested |
| 14 | Unsupported answer claims | Adv WorkflowGate, Repair, R3, R4, D5 gates | R6-1: rank figure on a filtered ranking (C09); R6-2 loosened claim-2 check (C10) |
| 15 | Neptune timeout | MU rag Stage (per-query timeout), services graph (hang 504) | no workflow-level test of a failing or timing-out graph store |
| 16 | DynamoDB conditional-write conflict | MU storage Storage (putIfAbsent, replaceIfVersion, RunLedger), rag Workflow (audit conflict) | none |
| 17 | S3 Vectors throttling, transient | MU storage Vector (retry policy, status mapping), Loader; rag SemanticContext | none |
| 18 | Replayed workflow, idempotency | Adv DuplicateId (Hadoop replay); MU storage, ER MatchJob, services query (Idempotency-Key), worker redelivery | no black-box replay of one queryId through the workflow and audit log |
| 19 | API auth, authz, bodies, timeouts | MU services common/query/graph (401, 403, expired, 400, 415, 422, 504) | JWT `token_use`, missing `exp`/`nbf`/`sub`, leeway untested (C15, MINOR-1) |
| 20 | Logging redaction | MU services (EMF without token, invalid JSON not echoed, 502 without port message); core Aws | no test of log output itself (no log API documented) |
| 21 | Configuration omissions | MU core Config (25: required placeholders, aws mode, unknown key) | none |

Production behaviors with no relevant test (found while mapping):
- J03 exact-key cap reject `exact key <key> holds N works > M` and J04 `FALLBACK_BLOCKS_REFUSED`
  (hadoop MODULE.md, Counters): logic-level only (N7).
- Rank figure on a topic, institution or bridge ranking (rag MODULE.md 4b): no filtered case.
- Spark graph algorithms on an empty graph (spark-engineer): none.
- JWT `exp`/`nbf`/`sub`/leeway (services MODULE.md, Known issues): none.
- J01 reject reason text (N1, CARRY K07): asserted non-empty only.
