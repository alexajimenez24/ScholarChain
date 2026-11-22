# Phase 02 repair round 1

Started by review 1 (`review-report.md`, REQUEST_CHANGES). That file has the full finding
text and the repair briefs. Round 1 of at most 3.

| Finding | Owner | Repair brief (short) | Fix report | Rerun evidence |
|---|---|---|---|---|
| MAJOR-1 | documentation-engineer | rewrite Spark MODULE.md from the ledger; drop step-08 citation | `repair-01-documentation-engineer.md` | orchestrator diff against `~/p2-orig` after the crash: matches the report |
| MAJOR-2 | documentation-engineer | Phase 02 task list in STATUS.md; drop closed decision; Spark decision in PROJECT_MEMORY | `repair-01-documentation-engineer.md`, checkpoints `repair-01-status-*.md` | same diff |
| MAJOR-3 | human, orchestrator | disclose prepared package in `human-gate.md`; run-log row | `run-log.md` step 0 | human gate |
| MINOR-1 | build-engineer | Hadoop and Spark at Test scope on black-box tests | `repair-01-build-engineer.md` | `export contractTests/Test/fullClasspath` lists spark-sql and hadoop-client |
| MINOR-2 | build-engineer, human | record scala-library 2.13.18 vs 2.13.16 risk; human picks the option before Phase 6 | `repair-01-build-engineer.md` | packageJobs plus jar listing: 0 `scala/collection/` in the Spark jar |
| MINOR-3 | documentation-engineer | README Python 3.11+ | `repair-01-documentation-engineer.md` | diff |
| MINOR-4 | factory-engineer | markTestRun quote splitting | deferred to Phase 11 by the human | - |
| MINOR-5 | test-engineer | replace dead review citations in tests/build | `repair-01-test-engineer.md` | check-contract 4 of 4; logic lines identical to `~/p2-orig` |
| MINOR-6 | orchestrator | run-log.md, versions.md, contract-test output | `run-log.md`, `versions.md`, `output-build-contract.txt` | this round |
| MINOR-7 | infra-engineer | `.github/workflows/.gitkeep` | `repair-01-infra-engineer.md` | validate-agent-files OK, 198 files |
| N1, N2, N5 | build-engineer | ledger path, jvmRelease comment, Test javac --release | `repair-01-build-engineer.md` | sbt check green |
| N3, N4 | toolchain-engineer | exact version matching in install.sh | `repair-01-toolchain-engineer.md` | no-op install.sh run; matcher test 14 of 14 |

Interruption: a WSL crash stopped the round during R1-2. The partial build-engineer edits
are in `output-r1-2-partial-edits.txt`. The orchestrator re-ran build-engineer from the
start, and that rerun re-examined each partial edit and kept it.

Re-check: a fresh code-reviewer reviews the complete Phase 02 state in
`review-report-02.md`. The verdict is APPROVE, with 4 new minors and 4 nits.

Post-review correction (review 2, MINOR-C, orchestrator): the Versions.scala line 9 edit
(versions.env -> versions.sh) was made in step 02, not in R1-2. The orchestrator note in
`repair-01-build-engineer.md` and line 2 of `output-r1-2-partial-edits.txt` now say so.
The agent report itself is unchanged, as evidence must be.
