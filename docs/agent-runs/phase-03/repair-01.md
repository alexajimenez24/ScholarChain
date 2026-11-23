# Phase 03 repair round 1

Started by: `review-report.md` (T03, fresh code-reviewer, opus high), verdict
REQUEST_CHANGES with 2 MAJOR and 11 MINOR findings, 0 CRITICAL.
The repair briefs are in that report, one per finding.

| Task | Owner | Findings | Report | Result |
|---|---|---|---|---|
| R1-1 | domain-engineer | MAJOR-1, MAJOR-2, MINOR-1 to MINOR-6 (plus T6/T7) | `repair-01-domain-engineer.md` | all FIXED; MINOR-5 decided (run-scoped researcher ID, doc comment and test); sbt check green, 89 passed |
| R1-1s | documentation-engineer | STATUS | `repair-01-status-r1-1-documentation-engineer.md` | done |
| R1-2 | dataset-engineer | MINOR-7 to MINOR-10 (leak only) | `repair-01-dataset-engineer.md` | all FIXED; validator ok; 15 Python tests OK |
| R1-3 | documentation-engineer | MINOR-5 notes, MINOR-11, MODULE.md, PROJECT_MEMORY, STATUS | `repair-01-documentation-engineer.md` | done |

Not routed in this round:
- MINOR-10, the part about wiring the Python tests into `sbt check`. It belongs to build-engineer
  and is recorded as an open item in STATUS.md.
- The guard false positives the reviewer saw. They are for factory-engineer in Phase 11 and are
  recorded in STATUS.md.

Orchestrator check: on-disk wire file count is 19, against note 8 of the R1-1 report, which says
18. R1-3 wrote 19 into MODULE.md.

Rerun evidence: review 2 (`review-report-02.md`), a fresh code-reviewer.
