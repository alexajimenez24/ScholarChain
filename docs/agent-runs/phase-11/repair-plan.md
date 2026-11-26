# Phase 11 repair plan (round 1)

Inputs: `carried-items.md`, `test-report.md`, `review-report-a.md`, `review-report-b.md`.
Both reviews: REQUEST_CHANGES. Fairness: all 5 red tests FAIR (A: J04; B: R6-1 x3,
token_use). C10 justification accepted by Review B, so no further change. R4-1 to R4-3
confirmed closed (B). One new critical finding: B-1.

## Order, one owner at a time

| # | Owner | MUST items | Few-line CARRY folded in | Gate |
|---|---|---|---|---|
| 1 | domain-engineer | C12 config field | none | `core/test` |
| 2 | spark-engineer | C12 bound; C14 JSON-lines export (B-15) | none | `sparkAnalytics/test` |
| 3 | storage-engineer | C13 (B-14); C14 load (B-15); C17 (B-13) | K11 where C17 touches it | `storage/test` |
| 4 | rag-engineer | C09 (B-10); C11 rag part (B-16); C14 read (B-15) | none | `rag/test` |
| 5 | service-engineer | C15 (B-11); C16 (B-12) | B-19 | services tests |
| 6 | hadoop-engineer | A-1; C18 unit cases | none | `hadoop/test` |
| 7 | factory-engineer (last) | B-1; C01 to C08 (B-2 to B-9) | B-18, K01 to K04 only where the same lines are touched | guard tests, `render.sh --check`, validator, routing suite, 3 probes |

Then test-engineer (adversarial rerun and full check, `test-report-02.md`), one fresh
code-reviewer on the whole repair diff (`review-report-02.md`), and documentation-engineer.

## Disagreement settled: C14 route

Review A: add `communitySize` to core `ResearcherMetrics` (schema v2).
Review B: export the existing `communities` table, which is already core `EvidenceItem`
`COMMUNITY_RECORD` with `communityId`, `communitySize` and `window`.
Orchestrator checked `Export.scala:532-555` and takes B: no core schema bump, no
`metricVersion` replay conflict, no constructor ripple, and exactly the three owners the
addendum names. A's warning stands: an `extraFields` column on the metrics rows would
never reach the runtime.

## Still CARRY (for Phase 17 limitations unless folded in above)

K05 to K10, K12 to K15, A-2, A-3 (both offered to test-engineer in the rerun, same
files), B-17, B-20, B-21, and the parts of K01 to K04, K11 and B-18 not folded in.

## Added during round 1

C19 (MUST, security, rag-engineer; found by service-engineer, `repair-01-service-engineer.md`):
vector store or embedder error text reaches notes and `answer.summary`, so it reaches the
query response and job result, on the deterministic path too. Same class as C16.
C20 (MUST, contract break before the EMR run, spark-engineer; raised by hadoop-engineer):
Spark `RunManifest.Upstream.HadoopPipelines` allows only `hadoop-v2`; the A-1 repair moved
Hadoop to `hadoop-v3`. Orchestrator confirmed by grep at `RunManifest.scala:87`.
