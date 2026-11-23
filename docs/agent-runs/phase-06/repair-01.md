# Phase 06 repair round 1

The only repair round of the phase (human addendum: one round; Review 2 because Review 1
had a MAJOR finding; findings still open after Review 2 carry forward to the human).

## Trigger

`review-report.md` (Review 1, fresh code-reviewer): REQUEST_CHANGES with MAJOR-1,
MINOR-1 to MINOR-5 and NIT-1 to NIT-4. The findings and repair briefs are in that file,
verbatim.

## Orchestrator decision on MAJOR-1

Review 1 asked the human to choose between policy (a), split a cluster on a contradicting
decision, and policy (b), keep transitive merges and disclose them. The orchestrator did
not stop the phase: the human addendum in `prompt.md` says "Unresolved and conflict
decisions are preserved, never merged", which (b) would contradict. Policy (a) was routed.
The human may overrule it at the gate. This is the orchestrator's reading, not a
ratification.

## Routing

| Task | Owner | Findings | Report |
|---|---|---|---|
| R1-1 | spark-engineer | MAJOR-1 (a), MINOR-1 to MINOR-4, NIT-3, NIT-4 | `repair-01-spark-engineer.md`, `output-spark-local-run-r1.md` |
| R1-1s | documentation-engineer | STATUS | `repair-01-status-r1-1-documentation-engineer.md` |
| R1-2 | build-engineer | MINOR-5 | `repair-01-build-engineer.md` |
| R1-3 | documentation-engineer | NIT-1, NIT-2, doc text for MAJOR-1 and MINOR-1 to MINOR-4, STATUS | `repair-01-documentation-engineer.md` |

## Rerun evidence

- R1-1: `sbt sparkAnalytics/test` 33 of 33; local rerun `run-769e0d672ecd3c19` SUCCEEDED,
  94 invariants all 0, 3 accepted researcher links refused, 81 researchers (was 79); full
  `sbt check` exit 0 (94, 68, 112, 33, 98 passed).
- R1-2: Spark job jar rebuilt at 15:12:16 on the final tree; scala/collection/ 0,
  ScalaRunTime 0. Orchestrator check: `unzip -Z1 ... | grep -c '^scholarchain/spark/SparkPipeline'`
  printed 6.
- Review 2 (`review-report-02.md`, fresh code-reviewer): all ten findings FIXED; its own
  `sbt sparkAnalytics/test` 33 of 33; independent recomputation of the cut on the real
  decisions matches the run counters. Verdict APPROVE WITH CARRIED-FORWARD ITEMS, 3 new
  NITs (NIT-A, NIT-B, NIT-C), carried forward with no second round.
