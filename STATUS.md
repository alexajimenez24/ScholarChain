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
  Committed as 9a65cf2, followed by 524dc1d "phase-03: record accepted commit"
  (`git log --oneline -5` and `docs/agent-runs/phase-03/commit.txt`, which holds
  9a65cf2e4de050017e56db15505a27a8fd0be916).
- Phase 04: Hadoop MapReduce pipeline.
  All tasks, repair round 1 and Review 2 are done (APPROVE WITH CARRIED-FORWARD ITEMS).
  Next is the human gate; see "Phase 04 task list" and "Carried forward from Phase 04".

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

## Phase 04 task list

Phase 04 is the Hadoop MapReduce pipeline (work order and human addendum in
`docs/agent-runs/phase-04/prompt.md`).
Reports are saved in `docs/agent-runs/phase-04/`.
The reference material in `~/ref-phase-04/` is a starting point only; each owner
adapts it and owns the result.

| Step | Owner | Report | Done means / check | State |
|---|---|---|---|---|
| T0 task list | documentation-engineer | (this report, saved by orchestrator as `step-00-documentation-engineer-report.md`) | STATUS.md lists tasks | done |
| T1 Hadoop test runtime | build-engineer | `step-01-build-engineer-report.md` | hadoop-client-runtime and jcl-over-slf4j at Test scope, forked hadoop test JVM -Xmx1g, versions only from project/Versions.scala; sbt check green | done, see note T1 |
| T2 Hadoop MapReduce pipeline | hadoop-engineer | `agent-report.md` | jobs 1-7 of the work order, unit + LocalJobRunner integration tests on datasets/samples fixtures, counts derived by hand; sbt check green; local bounded pipeline run with counters reported | done, see note T2 |
| T2b MODULE.md public API | documentation-engineer | `step-03a-documentation-engineer-report.md` | Public API and Done sections written from agent-report.md | done |
| T3 Phase 4 adversarial gate cases (Hadoop only) | test-engineer | `test-report.md` | tests/adversarial Hadoop cases: adversarial XML, Unicode, missing-field, duplicate-ID, common-name, giant-team; sbt check result reported | done, see note T3 |
| T4 docs | documentation-engineer | `step-04-documentation-engineer-report.md` | docs/hadoop-jobs.md and modules/hadoop/MODULE.md with only observed numbers | done, see note T4 |
| Review 1 | code-reviewer (fresh) | `review-report.md` | verdict, plus the fairness verdict on the 5 red adversarial tests | done, REQUEST_CHANGES, see note Review 1 |

Repair round 1 (human budget of 2026-11-23: one round only; reports saved as
`repair-01-<owner>.md`):

| Task | Owner | Scope | Report | State |
|---|---|---|---|---|
| R1a | hadoop-engineer | B1-B10 of the review (B1-B7 required) | `repair-01-hadoop-engineer.md` | done, see note R1a |
| R1b | documentation-engineer | D1: MODULE.md, docs/hadoop-jobs.md, post-repair numbers | `repair-01-documentation-engineer.md` | done, see note R1b |
| R1c | test-engineer | rerun of adversarialTests (no test change needed; all verdicts FAIR) | `repair-01-test-engineer.md` | done, see note R1c |
| Review 2 | code-reviewer (fresh) | whole Phase 04 diff | `review-report-02.md` | done, APPROVE WITH CARRIED-FORWARD ITEMS, see note Review 2 |

Note R1a (report `docs/agent-runs/phase-04/repair-01-hadoop-engineer.md`):
hadoop-engineer COMPLETE, B1 to B10 all done.

- Final `sbt check` exited 0: core 94 of 94, hadoop 98 of 98 (79 before), adversarialTests
  68 of 68 (the 5 red tests are green).
- Post-repair local runs: fixtures `run-8b3a162615a54f8f` and real samples
  `run-6d1175dbfdb874a9`; headline numbers are unchanged from the pre-repair runs, and the
  run IDs differ because `LogicVersion` is now `hadoop-v2`.
- Process observation: one `sbt hadoop/test` was moved to the background by the harness
  at 120 s.
  hadoop-engineer reports it read the output file once and started no second sbt while it
  ran.
- Risks it reports: the J03 reducer still buffers a key's values, the `y:` fallback key is
  weak, `sbt packageJobs` is unobserved, and the streaming J07 reducer has no separate unit
  test.

Note R1b (documentation-engineer): `modules/hadoop/MODULE.md` and `docs/hadoop-jobs.md`
now carry the F11 corrections, the repair behavior, the post-repair numbers and the
per-job spec items; the 4 open T3 items are removed.
`PROJECT_MEMORY.md` records the encoding decision and the one-round budget.
Line lengths over 90 remain only in markdown tables.

Note R1c (report `docs/agent-runs/phase-04/repair-01-test-engineer.md`): test-engineer
COMPLETE.

- One `sbt check` exited 0 (117 s): core 94 of 94, hadoop 98 of 98, adversarialTests
  68 of 68.
- The 5 formerly red tests are green; no test file changed (SHA-256 prefixes identical
  before and after).
- Two new observations, passed to Review 2 (no test covers either):
  1. J01 reject reasons print raw Scala `Option` text, for example
     `unreadable record (BadEncodingSome(, UTF-8)) at byte 3649` and
     `unreadable record (OversizeNone) at byte 264`.
     hadoop-engineer reported the format as
     `unreadable record (BadEncoding, <charset>) at byte N`; that was reported but does
     not match the observed log.
  2. Fallback-block candidates carry provenance `policyVersion` "norm-v1", while the
     manifest `pipeline` is "hadoop-v2".
- Review 2 is a fresh code-reviewer (`review-report-02.md`).
  Any finding still open after it goes to the human as carried forward; there is no
  second repair round.

Findings still open after Review 2 go to the human as carried-forward items.
There is no second repair round.

STATUS.md is updated by documentation-engineer after each owner.

Note Review 2 (report `docs/agent-runs/phase-04/review-report-02.md`): code-reviewer on
opus, high, fresh context.

- Verdict APPROVE WITH CARRIED-FORWARD ITEMS: APPROVE for the Phase 04 gate, with 7 MINOR
  findings (N1 to N7) carried forward.
  No CRITICAL or MAJOR finding remains.
- Its one `sbt check` exited 0 (116 s): core 94 of 94, hadoop 98 of 98, adversarialTests
  68 of 68.
- All Review 1 findings F1 to F11 are RESOLVED.
- No ownership breach; `tests/adversarial/**` is byte-identical to T3.
- Doc numbers match the saved reports; no invented number found.
- Per the human's instruction of 2026-11-23, no second repair round was run.
  N1 to N7 go to the human (next section).
- Review 2 did not run `sbt packageJobs`, LocalRunner, `--compress false` or any EMR
  classpath.

## Carried forward from Phase 04 (pending the human's decision)

All MINOR, as stated in `docs/agent-runs/phase-04/review-report-02.md`.
Paths below are under `modules/hadoop/src/main/scala/scholarchain/hadoop/` unless noted.

- N1: J01 reject reasons print Scala `Option` text, a regression from the repair.
  Reads `unreadable record (BadEncodingSome(, UTF-8)) at byte 3649`, not
  `(BadEncoding, <charset>)`.
  Owner hadoop-engineer; `Jobs.scala:125`, `:129`, `DblpInputFormat.scala:202`.
  Brief: use `cs.getOrElse("")`, strip the `\n[charset=...]` suffix from the reject
  `input`, and add a test pinning the reason for each of the 3 statuses.
- N2: a decode-only declared charset (for example ISO-2022-CN) crashes the reader
  instead of giving one counted refusal.
  Owner hadoop-engineer; `DblpInputFormat.scala:100` and `:103`.
  Brief: test `cs.canEncode` before `newEncoder()`, or catch
  `UnsupportedOperationException`, and add the case to RepairDblpSuite.
- N3: fallback comparison cost and volume are bounded only by the hard cap
  (about C(2000, 2) per block at the default).
  Owner hadoop-engineer; `BlockLogic.scala:201-217` and `:332-347`.
  Brief: check `pairId` before computing features, optionally cap fallback pairs per
  block with a counter, and document the bound.
- N4: candidate provenance names only `norm-v1`, while the manifest `pipeline` is
  `hadoop-v2`.
  Owner hadoop-engineer, then domain-engineer when MatchCandidate moves to core;
  `Records.scala:19` and `:113`.
  Decide before Phase 05 consumes candidates: put a policy version that includes
  `Pipeline.LogicVersion` in provenance, or accept runId to manifest as the carrier and
  record that in PROJECT_MEMORY.md.
- N5: the J03 reducer still buffers a whole exact-key group (residue of F4 and F9).
  Owner hadoop-engineer; `Jobs.scala:257`.
  Brief: stream the values, or refuse a key past a hard cap as `BlockingReducer` does
  (`Jobs.scala:313-324`).
- N6: stale "pending" text and small imprecisions in the docs.
  Owner documentation-engineer; `MODULE.md:348`, `docs/hadoop-jobs.md:22-23`, `:274-280`
  and `:291-296`, and `MODULE.md:57`.
  Not fixed in this task, per the one-round rule.
- N7: job-level test gaps for the new reducer wiring: J04 `FALLBACK_BLOCKS_REFUSED`
  with a reject line, J03 `OVERSIZE_BLOCKS` with a reject line, and the J01 reject
  reason text.
  Owners hadoop-engineer (module-unit) and test-engineer, which also plans the
  black-box ISO-8859-1 case (`repair-01-test-engineer.md`, RISKS 3).
  Brief: one LocalJobRunner case each with a small `--max-block`.

The reviewer notes N1 and N2 are small, contained fixes if the human waives the
one-round budget.

Note Review 1 (report `docs/agent-runs/phase-04/review-report.md`): code-reviewer on
opus, high.

- Verdict REQUEST_CHANGES.
- Its `sbt check` exited 1: core 94 of 94, hadoop 79 of 79, adversarialTests 63 of 68
  (the same 5 red tests as T3).
- All 5 red adversarial tests are judged FAIR.
- Findings: F1 to F5 MAJOR and F6 to F11 MINOR.
  The report text counts its MAJOR findings as 4 plus the encoding item; the list has F1 to
  F5.
- No ownership breach.
- Repair briefs are B1 to B10 for hadoop-engineer (B1 to B7 required for APPROVE) and D1
  for documentation-engineer.
- Review 1 recorded its own risks: F2, F3 and F4 are confirmed by reading the code only,
  and `sbt packageJobs` and the cluster run are unobserved.

Decision (human, 2026-11-23, given in the main session), verbatim: "the reader must
decode with the encoding declared in the XML prolog (ISO-8859-1 for DBLP, UTF-8 when none
is declared) and reject bytes that are invalid in the declared encoding. Route it to
hadoop-engineer in the repair round, with a test using a raw ISO-8859-1 high byte."
This is finding F5 and brief B5.

Note T1 (report `docs/agent-runs/phase-04/step-01-build-engineer-report.md`):
build-engineer COMPLETE, `build.sbt` changed in the hadoop project only.

- `sbt check` is green as reported (94 passed at the tail, 14 project test runs passed).
- hadoop `Test / fork` is true and `Test / javaOptions` is `-Xmx1g`.
- `hadoop-client-runtime` 3.4.1 and `jcl-over-slf4j` 2.0.20 are at Test scope;
  `hadoop-client-api` stays Provided.
- No change to `project/Versions.scala`; the existing `V.hadoop` and `V.slf4j` were used.
- Risks noted: `entityResolution` has no Hadoop deps or fork yet, and the fork is
  exercised only once T2 adds tests, because the hadoop project has no sources or tests.
  Each forked test JVM can use up to 1 GB heap on the 8 GB machine.

Note T2 (report `docs/agent-runs/phase-04/agent-report.md`): hadoop-engineer COMPLETE.

- `sbt check` is green as reported: hadoop 79 of 79 tests, core 94 of 94.
- Jobs J01 to J07 are implemented.
- Local bounded pipeline runs: fixtures (`run-8019aba484da34ad`) and real samples
  (`run-2ed430a7951bb05a`).
- Fixtures headline numbers: maximum team size 12, largest pair expansion 66, largest
  blocking group 13.
- 0 rejects on the samples.
- Risks as reported:
  - `sbt packageJobs` was not run, so the job-jar build with the new sources is
    unobserved.
  - LocalRunner needs the Test-scope `hadoop-client-runtime`, so it runs through
    `hadoop/Test/runMain`.
  - The hadoop-local records (MatchCandidate, ExternalResearcher, AuthorPaper,
    AbstractRecord) are not core contracts yet; domain-engineer promotes them in a
    later phase.
  - Cross-source teams and pairs double count the same paper until entity resolution.

Post-restart verification (orchestrator, as observed and given to documentation-engineer):

- Phase 04 was interrupted by a WSL out-of-memory crash and a restart.
- After the restart the orchestrator verified that build-engineer's `build.sbt` edit and
  hadoop-engineer's 15 source files, 5 suites (22+10+20+17+10 = 79 tests) and fixtures
  are on disk and match their reports.
- One `sbt check` run after the restart (2026-11-22 23:30) exited 1.
  Core passed 94 of 94 and hadoop passed 79 of 79.
  `adversarialTests` ran 54 tests and 3 failed (CommonNameSuite 2, MissingFieldSuite 1).
- The failing files are partial, unreported files that test-engineer left in
  `tests/adversarial/` when the crash interrupted it.
  test-engineer is re-run from the beginning in T3.

Note T2b (report `docs/agent-runs/phase-04/step-03a-documentation-engineer-report.md`):
`modules/hadoop/MODULE.md` has the Public API and Done / Not done sections, taken from
the T2 report and checked against names in `src/main`.
Run numbers and headline counts are left for T4.

Note T3 (report `docs/agent-runs/phase-04/test-report.md`): test-engineer COMPLETE.

- Final `sbt check` exited 1.
  core 94 of 94, hadoop 79 of 79, adversarialTests 68 total, 63 passed, 5 failed.
- The 5 failures are left red for the fairness verdict (Review 1):
  two missing fallback keys for oversize name and title blocks, `INVALID_IDENTIFIER`
  stays 0, J03 `DUPLICATE_RECORDS` and `CONFLICT_RECORDS` stay 0, and a trailing cap flag
  without a value takes the default.
- test-engineer also listed imprecisions in `modules/hadoop/MODULE.md`; they are under
  its Known issues.
- Open human question: how DBLP's ISO-8859-1 declaration interacts with strict UTF-8
  (untested).

Note T4 (report `docs/agent-runs/phase-04/step-04-documentation-engineer-report.md`):
`docs/hadoop-jobs.md` was written and `modules/hadoop/MODULE.md` finished, with only the
numbers in the T2 and T3 reports.
The numbers may change after the repair round.

Machine limits (human addendum, 8 GB laptop):

- Never more than one sbt at once.
- No background shells, and no polling or wait loops.
- No mutation testing.
- Run every command in the foreground with a timeout.

## Current state (phase 04)

- Tasks done: T0, T1, T2, T2b, T3, T4, Review 1, R1a, R1b, R1c, Review 2.
  All Phase 04 tasks are done.
- Task running: none.
- Next task: the human gate (ratify, commit, and decide N1 to N7).
- Half done: nothing.
  `sbt check` exited 0 in hadoop-engineer's final run, in the R1c run and in Review 2
  (core 94, hadoop 98, adversarial 68 of 68).
  `sbt packageJobs` has not been run this phase; the job-jar build is unobserved.
- Git: HEAD is 524dc1d (Phase 03 accepted).
  Uncommitted at the start of T4, per the session's git status: `STATUS.md`, `build.sbt`,
  `modules/hadoop/MODULE.md`, the deleted `tests/module-unit/hadoop/.gitkeep`, and
  untracked `docs/agent-runs/phase-04/`, `modules/hadoop/src/main/scala/scholarchain/`,
  `tests/adversarial/` files and `tests/module-unit/hadoop/` suites.
  T4 added the untracked `docs/hadoop-jobs.md`.
- Harness: claude-code (Phase 03 recorded 2.1.330; not re-probed for Phase 04).

## Open decisions for the human

- Decided (human, 2026-11-23): the DBLP reader decodes with the encoding declared in the
  XML prolog (ISO-8859-1 for DBLP, UTF-8 when none is declared) and rejects bytes that
  are invalid in it; implemented in R1a (brief B5), documented in R1b.
  This closes the T3 question in `docs/agent-runs/phase-04/test-report.md`.
- Decided (human, 2026-11-23): Phase 04 has one repair round only; findings still open
  after Review 2 are carried forward to the human.
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
