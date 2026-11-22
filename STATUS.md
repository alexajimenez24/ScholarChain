# STATUS

<!-- Owner: documentation-engineer. Updated after every task so a later session can
resume. -->

## Where things stand

- Phase 00: seed `factory-engineer` committed.
- Phase 01: agent system committed after a factory-engineer audit, three repair rounds
  and two code-reviewer passes. Evidence and the accepted known gaps are in
  [docs/agent-runs/phase-01/](docs/agent-runs/phase-01/human-gate.md).
- Phase 02: sbt build skeleton, toolchain ledger, module and test roots, service image
  Dockerfile. Repair round 1 is complete, review 2 APPROVE, waiting for the human gate.

## Carried forward from Phase 01

- Claude Code is the only harness used; the Codex adapters are rendered and validated
  but out of scope.
- Hook script edits take effect immediately; `settings.json` changes need a restart.
- Keep Claude Code's auto mode off in this repository until the Phase 11 guard fixes.
- Phase 11: MINOR-4 of review 1 (markTestRun quote splitting in guard-dangerous),
  deferred by the human on 2026-11-22; the bypass now skips only the format check.
- Phase 05: gold-set bypasses (`rm -rf datasets` and similar) and MINOR-1 (gold-set
  interpreter writes).
- Phase 11: R3-CRITICAL-1, R3-MAJOR-1, R3-MINOR-1 and R3-MINOR-2 from
  `docs/agent-runs/phase-01/review-report-02.md`.
- Phase 12: terraform MCP server, AWS-side scale caps (MAJOR-3), aws-api MCP migration.
- Phase 13: MAJOR-5 (cleanup.sh piping).

## Carried forward from Phase 02 (pending the human's choice)

- MINOR-A: the black-box test classpath has two SLF4J providers, and no fork or
  `--add-opens` for a local SparkSession (build-engineer, before Phase 11 tests).
- MINOR-B: spark MODULE.md lacks the scala-library exclusion and the open MINOR-2
  decision (documentation-engineer).
- MINOR-D: no automated test for the Spark jar exclusion or the black-box classpath
  (test-engineer, after the MINOR-2 decision).
- N-a: stale EMR 7.x text in `docker/services/Dockerfile:6` and
  `toolchain/LEDGER.md:64-65` (build-engineer, toolchain-engineer).
- N-c: slf4j-api is bundled in the job jars (build-engineer, Phase 6).
- N-d: `vmatch` accepts pre-release suffixes (toolchain-engineer).

## Phase 02 task list

Reports are in `docs/agent-runs/phase-02/`.

| Step | Owner | Report | State |
|---|---|---|---|
| 01 toolchain | toolchain-engineer | `step-01-toolchain-engineer-report.md` | done |
| 01b observed versions | toolchain-engineer | `step-01b-toolchain-engineer-report.md` | done |
| 02 verify installed build | build-engineer | `agent-report.md` | done |
| 03 docs | documentation-engineer | `step-03-documentation-engineer-report.md` | done |
| 04 Spark comment | build-engineer | `step-04-build-engineer-report.md` | done |
| Review 1 | code-reviewer | `review-report.md` | done, REQUEST_CHANGES |

Repair round 1 (reports saved as `repair-01-<owner>.md`):

| Task | Owner | Scope | State |
|---|---|---|---|
| R1-1 | documentation-engineer | MAJOR-1, MAJOR-2, MINOR-3 | done (edits made, see its report) |
| R1-2 | build-engineer | MINOR-1, MINOR-2, nits N1, N2, N5 | done, see note 1 |
| R1-3 | toolchain-engineer | nits N3, N4 | done, see note 3 |
| R1-4 | test-engineer | MINOR-5 | done, see note 4 |
| R1-5 | infra-engineer | MINOR-7 | done, see note 5 |
| R1-6 | orchestrator | MINOR-6: `run-log.md` and `versions.md` | done, see note 6 |
| R1-7 | documentation-engineer | STATUS update | done |
| Review 2 | code-reviewer (fresh) | `review-report-02.md` | done, APPROVE |
| Human gate | human | the phase 02 gate file | pending |

Note 1 (R1-2, report `repair-01-build-engineer.md`): sbt check and
check-contract are green.
The MINOR-2 option is open for the human before Phase 6.
The task was re-run from the start after a WSL crash interrupted the first attempt.

Note 2: the WSL crash interrupted repair round 1 during R1-2.
Partial edits are recorded in `docs/agent-runs/phase-02/output-r1-2-partial-edits.txt`.
The clean pre-phase package copy is at `~/p2-orig` for `diff -r` checks.
All Phase 02 files are untracked.

Note 3 (R1-3, report `repair-01-toolchain-engineer.md`): exact version-token matching
in `toolchain/install.sh` for nits N3 and N4.
`bash -n` and a no-op `install.sh` run passed.
The scratch matcher test passed 14 of 14.

Note 4 (R1-4, report `repair-01-test-engineer.md`): dead review citations in
`tests/build/` were replaced with spec clauses (lines 1021, 1023, 1038 and 1046).
Only comments changed.
check-contract 4 passed, scenario-selection 1 passed, and sbt check is green.

Note 5 (R1-5, report `repair-01-infra-engineer.md`): `.github/workflows/.gitkeep` was
created as an empty file (MINOR-7).
`.claude/hooks/validate-agent-files.sh` exited 0 with 198 files having one writer.

Note 6 (R1-6, orchestrator): the orchestrator wrote `run-log.md`, `versions.md` and
`repair-01.md` (MINOR-6).
Its `sbt check` exited 0 (`output-sbt-check.txt`).
Its build contract run passed 4 and failed 0 (`output-build-contract.txt`).

Next task: the human gate. The human writes `human-gate.md` with the MAJOR-3
disclosures, then ratifies and commits. The human also decides whether review 2
MINOR-A, MINOR-B and MINOR-D are fixed before the commit or carried forward.
MINOR-C (orchestrator evidence notes) is already corrected and has not been reviewed.

Decisions and deferrals recorded this round:

- Spark runtime target is `emr-spark-8.0.0` (human, 2026-11-22); see PROJECT_MEMORY.
- MAJOR-3 (prepared package provenance) is disclosed by the human in the gate file.
- MINOR-4 is deferred to Phase 11 with the other guard findings.

After the build-engineer fixes, `sbt check` and `tests/build/check-contract.sh` must stay
green.
Uncommitted: the whole Phase 02 tree is untracked; the human commits.

## Next phase

Phase 03: contracts, configuration and dataset manifest (domain-engineer,
dataset-engineer), per
[the spec](docs/spec/assignment.md#phase-3-define-contracts-configuration-and-dataset-manifests).

## Open decisions for the human

- Confirm `emr-spark-8.0.0` is available in us-east-2 before the first cloud run
  (`project/Versions.scala`); infra-engineer checks in Phase 12.
- MINOR-2 scala-library option, before Phase 6. Choose one: (a) a Scala 3 LTS patch
  pinned to scala-library at or below 2.13.16; (b) scala-library excluded from the
  Spark job jar, as now built; (c) `userClassPathFirst`.
  Scala 3.3.9 pulls scala-library 2.13.18, and emr-spark-8.0.0 ships 2.13.16.
- Gate disclosures for `human-gate.md` (review 1 MAJOR-3, provenance in `run-log.md`
  step 0):
  - the Phase 2 deliverables were placed from a prepared package, not written by agents;
  - `toolchain/versions.env` was renamed to `toolchain/versions.sh` by the human;
  - terraform-mcp-server was installed by the human, and its checksum check is to be
    recorded in the gate.
