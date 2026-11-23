# STATUS

<!-- Owner: documentation-engineer. Updated after every task so a later session can
resume. -->

## Where things stand

- Phase 00: seed `factory-engineer` committed.
- Phase 01: agent system committed after a factory-engineer audit, three repair rounds
  and two code-reviewer passes. Evidence and the accepted known gaps are in
  [docs/agent-runs/phase-01/](docs/agent-runs/phase-01/human-gate.md).
- Phase 02: committed as 3de681d (`docs/agent-runs/phase-02/commit.txt`).
  Sbt build skeleton, toolchain ledger, module and test roots, service image
  Dockerfile. Repair round 1 is complete, review 2 APPROVE, and the Phase 02 gate is done.
- Phase 03: contracts, configuration and dataset manifest.
  All tasks and repair rounds 1 and 2 are done, and review 3 is APPROVE.
  The phase is uncommitted and waits for the human gate (ratification and commit).

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
| Human gate | human | the phase 02 gate file | done, committed 3de681d |

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

The Phase 02 gate is done: the human committed the phase as 3de681d.
The open choices on review 2 MINOR-A, MINOR-B and MINOR-D are listed under
"Carried forward from Phase 02" above.
MINOR-C (orchestrator evidence notes) is already corrected and has not been reviewed.

Decisions and deferrals recorded this round:

- Spark runtime target is `emr-spark-8.0.0` (human, 2026-11-22); see PROJECT_MEMORY.
- MAJOR-3 (prepared package provenance) is disclosed by the human in the gate file.
- MINOR-4 is deferred to Phase 11 with the other guard findings.

After the build-engineer fixes, `sbt check` and `tests/build/check-contract.sh` must stay
green.
Committed: 3de681d (`git log`: "phase-02: record accepted commit").

## Phase 03 task list

Phase 03 is contracts, configuration and dataset manifest (domain-engineer,
dataset-engineer), per
[the spec](docs/spec/assignment.md#phase-3-define-contracts-configuration-and-dataset-manifests).
Reports are in `docs/agent-runs/phase-03/`.

| Step | Owner | Scope | Report | Done means / check | State |
|---|---|---|---|---|---|
| T00 | documentation-engineer | write this task list | (this report, saved by orchestrator) | table present | done |
| T01 | dataset-engineer | datasets/dataset-manifest.yaml, datasets/scripts, datasets/samples, datasets/schemas | `step-01-dataset-engineer-report.md` | manifest validator and script dry runs pass; every license/snapshot/size/checksum/endpoint re-observed or UNVERIFIED | done |
| T01s | documentation-engineer | STATUS after T01 | - | STATUS updated | done |
| T02 | domain-engineer | modules/core records, IDs, enums, validation, YAML-JSON config loader, tests in tests/module-unit/core | `agent-report.md` | `sbt check` green | done |
| T02d | documentation-engineer | modules/core/MODULE.md, PROJECT_MEMORY decisions, STATUS after T02 | - | MODULE.md states the JSON-compatible YAML restriction | done |
| T03 | code-reviewer (fresh) | review of the whole Phase 03 diff | `review-report.md` | APPROVE, or repair rounds (max 3) | done, REQUEST_CHANGES (2 major, 11 minor) |
| T04 | documentation-engineer | final STATUS, phase ready for the human gate | - | STATUS updated | done |

Note T01 (report `docs/agent-runs/phase-03/step-01-dataset-engineer-report.md`):
dataset-engineer COMPLETE.
The manifest covers dblp, openalex, crossref and orcid, plus synthetic fixtures.
`validate-manifest.py` is ok, 9 Python unittest tests pass, and the samples total
163579 bytes.
No real ORCID record is committed.
The Python tests in tests/module-unit/datasets are not run by `sbt check` (open:
build-engineer or test-engineer).

Note T02 (report `docs/agent-runs/phase-03/agent-report.md`): domain-engineer COMPLETE.
`sbt check` exit 0, 69 core tests pass, 18 frozen wire examples.
`modules/core/MODULE.md` and the PROJECT_MEMORY decisions were updated in T02d.

Phase 03 repair round 1 (reports saved as `repair-01-<owner>.md`):

| Task | Owner | Scope | State |
|---|---|---|---|
| R1-1 | domain-engineer | MAJOR-1, MAJOR-2, MINOR-1 to MINOR-6; sbt check | done (sbt check green, 89 core tests) |
| R1-2 | dataset-engineer | MINOR-7 to MINOR-10 (leak only); validator and Python tests | done, see note R1-2 |
| R1-3 | documentation-engineer | MINOR-5 and MINOR-11 notes; MODULE.md after R1-1 | done |
| Review 2 | code-reviewer (fresh) | whole Phase 03 diff, `review-report-02.md` | done, REQUEST_CHANGES (0 major, 4 minor) |

Note R1-1 (report `docs/agent-runs/phase-03/repair-01-domain-engineer.md`):

- MAJOR-1, MAJOR-2 and MINOR-1 to MINOR-6 are fixed.
- MINOR-5 was decided without a code change: the researcher ID is stable within a run
  and policy, and an ORCID is the cross-run key.
  A doc comment and a test were added.
- Decoding is now strict: an unknown field or a lossy value is rejected.
- The orchestrator counted 19 frozen wire files on disk; the report's note 8 says 18.
  Checked by listing `tests/module-unit/core/resources/wire/`: 19 files, including the
  new `author-mention.json`.

Note R1-2 (report `docs/agent-runs/phase-03/repair-01-dataset-engineer.md`):

- MINOR-7 to MINOR-10 are fixed, the leak only for MINOR-10.
- `dblp.dtd` is committed as `datasets/samples/real/dblp.dtd` (sha256 2d864498...1ef6).
- 15 Python tests pass (9 before).
- No S3 upload path remains in `datasets/`, and the ORCID step needs `WITH_ORCID=1`.

Note R1-3 (documentation-engineer): `modules/core/MODULE.md` and PROJECT_MEMORY updated
for repair round 1.
MODULE.md now counts 19 frozen wire files on disk (the domain-engineer report says 18),
18 schema versions and 89 core tests.

Phase 03 repair round 2 (reports saved as `repair-02-<owner>.md`):

| Task | Owner | Scope | State |
|---|---|---|---|
| R2-1 | domain-engineer | MINOR-1 and MINOR-3 | done (94 tests, 13 mutation checks) |
| R2-2 | dataset-engineer | MINOR-2 | done (17 Python tests) |
| R2-3 | documentation-engineer | MINOR-4 | done |
| R2-V | orchestrator | verify after the hung R2-1 session | done, see note R2-V |
| Review 3 | code-reviewer (fresh) | whole Phase 03 diff, `review-report-03.md` | done, APPROVE (0 critical, 0 major, 0 minor) |

Note R2: review 2 is `docs/agent-runs/phase-03/review-report-02.md`.
R2-1 took about 8.5 hours of wall time, because of the agent's own background polling.
Residual items from review 2 are now in `modules/core/MODULE.md` "Known issues".

Note R2-V (orchestrator, `run-log.md` row R2-V): the orchestrator verified after the
human stopped the hung R2-1 session.
All 13 mutated rules are present in `Config.scala` and `Validate.scala`, so nothing
was restored.
A single `sbt check` exited 0 with 94 passed.
Evidence: `docs/agent-runs/phase-03/output-r2-verify-sbt-check.txt` and
`docs/agent-runs/phase-03/output-r2-1-mutation-checks.txt`.

Note R3 (report `docs/agent-runs/phase-03/review-report-03.md`): review 3 was a fresh
code-reviewer on opus, high.
Verdict APPROVE, with 0 critical, 0 major and 0 minor findings.
All review 2 findings, MINOR-1 to MINOR-4, are FIXED.

Open items for later phases:

- A reserved work-namespace list, so that work IDs and researcher IDs are distinguishable
  (open design point from the domain-engineer repair report; domain-engineer).
- Phase 4 and 5: the contract has no ORCID profile record and no match-candidate record
  (domain-engineer, additive change with a new schema version).
- Contract-side dataset questions 1 to 8 are listed in
  `docs/agent-runs/phase-03/agent-report.md` (end of the report).
- Wiring the `tests/module-unit/datasets` Python tests into `sbt check` is still open
  (build-engineer; review 1 MINOR-10, review 2).
- Guard-dangerous false positives on read-only commands were seen in the Phase 03
  review: a `python3 -c` naming a settings file, and a `sed` without `-i` naming the
  gold-set path (factory-engineer, Phase 11).

Owners run one at a time; never more than one sbt at once (addendum).

## Current state (phase 03)

- Tasks done: T00, T01, T01s, T02, T02d, T03, T03s, R1-1 to R1-3, R2-1 to R2-3, R2-V,
  Review 3 (APPROVE) and T04.
- Task running: none.
- Next step: the human gate for Phase 03 (ratification and commit by the human).
- Half done: nothing.
- Git: HEAD is 3d10892; the Phase 03 files are uncommitted (modified and untracked
  files in `git status --short`).
- Harness: claude-code 2.1.330 (`docs/agent-runs/phase-03/run-log.md`).

## Open decisions for the human

- Confirm the inferred CC0 status of `datasets/samples/real/dblp.dtd`, or drop it.
  The DTD header and `README.txt` give CC0 for the export formats but do not name the DTD
  (`docs/agent-runs/phase-03/repair-01-dataset-engineer.md`).
  Dropping it means removing the file and its `retrieval.json` entry and changing the test.
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
- Dataset questions from T01 (MANUAL ACTION REQUIRED 1 to 5), in the report:
  `docs/agent-runs/phase-03/step-01-dataset-engineer-report.md`
  1. ORCID: API terms UNVERIFIED; choose the API sample or the 57 GB Public Data File.
  2. DBLP: is the README.txt CC0 statement enough, given the FAQ is bot-protected?
  3. DBLP full-dump SHA-256 is UNVERIFIED; it needs a 1.1 GB download and your approval.
  4. The 100,000-work bounded fetch is not run; it needs an OPENALEX_API_KEY.
  5. OpenAlex snapshot date and Crossref data file details are UNVERIFIED.
