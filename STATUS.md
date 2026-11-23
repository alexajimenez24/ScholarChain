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
- Phase 04: Hadoop MapReduce pipeline, committed.
  `git log --oneline -3` shows a43fa55 "phase-04 hadoop-engineer: DBLP parser, source
  normalizers, MapReduce jobs J01-J07" and 4388607 "phase-04: record accepted commit".
  Items N1 to N7 stay open; see "Phase 04 task list" and "Carried forward from Phase 04".
- Phase 05: entity resolution is in progress.
  Steps 01 (build-engineer), 02 (T1), 03 (T2), 03b (T1b) and 04 (T3; the last four by
  entity-resolution-engineer) are done; v1 is labeled (280 rows).
  The human will not label v1b (decision 03), so T3 ran on v1 only.
  Human decision 04 answered open items 1 and 2, and step 05 (documentation) is done.
  Review 1 is done (APPROVE WITH CARRIED-FORWARD ITEMS) and repair round 1 is done
  (R1-1, R1-2, R1-3).
  Review 2 is done (APPROVE WITH CARRIED-FORWARD ITEMS); next is the Phase 05 human gate.
  See "Phase 05 task list", "Current state (phase 05)" and "Carried forward from
  Phase 05 (for the human gate)".

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

## Phase 05 task list

Phase 05 is entity resolution (entity-resolution-engineer), work order and human addendum
in `docs/agent-runs/phase-05/prompt.md`.
Reports are saved in `docs/agent-runs/phase-05/`.
The reference material in `~/ref-phase-05/` is a starting point only; each owner adapts
it and owns the result.

| Step | Owner | Scope | Report | State |
|---|---|---|---|---|
| 01 | build-engineer | entityResolution gets hadoop-client-api (Provided) and the Hadoop test runtime, forked test JVM -Xmx1g, versions only from project/Versions.scala | `step-01-build-engineer-report.md` | done, see note step 01 |
| 02 (T1) | entity-resolution-engineer | review queue generated from the Phase 04 local pipeline on datasets/samples into `target/er-review/er-review-v1.csv` (git-ignored), at least 250 rows, stratified across researcher and work pairs and across likely matches, borderline and likely non-matches, no scores, paths or predictions; BLOCKED-ON dataset-engineer if fewer than 250 pairs | `step-02-entity-resolution-engineer-report.md` | done about 09:21, see note step 02 |
| Human labeling | human | writes `datasets/gold/er-gold-v1.csv` from the review queue (starts 09:00); v1b labeling was planned and then cancelled | - | done: v1 only, 280 rows (228 MATCH, 13 NON_MATCH, 39 UNSURE); v1b not labeled and unused (decision 03) |
| 03 (T2) | entity-resolution-engineer | the rest of the work order except the gold-set evaluation; er-v1 fixed; LLM adjudication behind a port with a deterministic fake and an off mode | `agent-report.md` | done, see note step 03 |
| 03b (T1b) | entity-resolution-engineer | supplementary queue `target/er-review/er-review-v1b.csv`: up to 60 additional likely non-match pairs from the same samples (same block but different clusters; different DBLP homonym numbers; different ORCIDs; compatible initials with no shared context); same columns and rules as v1 (no scores, paths or predicted labels, traceable to source records); no overlap with v1 | `step-03b-entity-resolution-engineer-report.md` | done, see note step 03b |
| 04 (T3) | entity-resolution-engineer | gold-set evaluation on v1 only (`datasets/gold/er-gold-v1.csv`, 280 rows) against decision run `run-02cecabcf3e68312` in `target/er-match-run`; MATCH and NON_MATCH counted, UNSURE reported separately; the report must record the few-non-matches limitation (13 NON_MATCH labels limit how well precision and the reject side are measured) and the class imbalance; the v1b queue, its candidate run and `target/er-match-run-v1b` are not used (human decision 03) | `output-er-evaluation.md`, `step-03-entity-resolution-engineer-report.md` | done, see note step 04 |
| 05 | documentation-engineer | `docs/entity-resolution.md` and `modules/entity-resolution/MODULE.md`, observed numbers only | `step-04-documentation-engineer-report.md` | done, see note step 05 |
| Review 1 | code-reviewer (fresh) | whole Phase 05 diff | `review-report.md` | done, APPROVE WITH CARRIED-FORWARD ITEMS (6 MINOR, 4 NIT) |
| R1-1 | entity-resolution-engineer | MINOR-2, NIT-1, NIT-2, PolicyConfig comment | `repair-01-entity-resolution-engineer.md` | done, see note R1-1 |
| R1-2 | documentation-engineer | MINOR-3, MINOR-4 (docs part), MINOR-5, MINOR-6 | `repair-01-documentation-engineer.md` | done, see note R1-2 |
| R1-3 | documentation-engineer | RISKS 6 wording from R1-1 into MODULE.md and the docs page | `repair-01b-documentation-engineer.md` | done, see note R1-3 |
| Review 2 | code-reviewer (fresh) | re-check; open findings carried forward to the human, no second repair round | `review-report-02.md` | done, APPROVE WITH CARRIED-FORWARD ITEMS, see note Review 2 |
| Human gate | human | ratify and commit | `human-gate.md` | pending |

Phase rules:

- No leakage: the scoring policy er-v1 and its accept, reject and unresolved thresholds
  were fixed in the module before the gold file existed.
  Matching code never reads `datasets/gold/`.
  Threshold sensitivity is reported, not used to retune er-v1.
- Every label is written by the human; agents never write, infer or pre-fill one.
- Machine limits (8 GB laptop): never more than one sbt at once, no background shells,
  no polling or wait loops, no mutation testing, every command in the foreground with a
  timeout.
- Owners run one at a time.
  STATUS.md is updated by documentation-engineer after each owner.

Note step 01 (build-engineer, as reported by the orchestrator):

- `sbt check` is green: Total 68 passed, 14 project test runs.
- The `build.sbt` change is 12 added lines (`git diff --stat -- build.sbt`).
- The sbt run exceeded the 180 s foreground window and the harness backgrounded it.
  Machine limits forbid background shells.
  The run completed and no sbt was left running.
- Later sbt work orders set an explicit 600 s timeout.
- The saved report `docs/agent-runs/phase-05/step-01-build-engineer-report.md` now exists
  and matches these numbers (Total 68 passed, 14 project test runs, 175 s).

Note step 02 (T1, report
`docs/agent-runs/phase-05/step-02-entity-resolution-engineer-report.md`): started 09:07,
past the human's 09:00 labeling start; done about 09:21.

- Output: `target/er-review/er-review-v1.csv` (git-ignored), 280 rows (205 researcher,
  75 work), sha256 f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e,
  source run `run-6d1175dbfdb874a9`; `sbt check` green.
- The orchestrator check at the end of the report confirms the header, the row counts,
  the distinct pair IDs, empty label and note columns, the checksum and the ignore rule.
- Queue delivered about 09:22; `datasets/gold` did not exist yet.
- CLOSED by human decision 01 (report RISKS 1, class imbalance): the human labels
  `er-review-v1.csv` as is
  (`docs/agent-runs/phase-05/human-decision-01.md`).
  The samples give only 8 likely non-matches (4 researcher, 4 work) and no borderline
  work pairs.
  The supplement is task 03b (`er-review-v1b.csv`); the imbalance and the supplement go
  in the evaluation report.
- The generator refuses to overwrite an existing file unless `--overwrite true` is given,
  which protects a copy the human may already be labeling.
- Process notes: the harness backgrounds sbt after 180 s even with a 600000 ms timeout
  (seen in steps 01 and 02); agents start no second sbt and do not poll.
  guard-dangerous gives false positives on the literal test-marker task name in greps and
  report text (Phase 11 item).

Note step 03 (T2, report `docs/agent-runs/phase-05/agent-report.md`):
entity-resolution-engineer COMPLETE.

- `sbt check` is green: entity-resolution 92 tests, core 94, hadoop 98, adversarial 68;
  14 project test runs; 570 s.
- Policy er-v1 thresholds: accept 0.9, reject 0.35, LLM band [0.7, 0.9).
  er-v1 was fixed before the gold file existed (it appeared about 10:28 to 10:33) and was
  not changed afterwards; no agent could read a label while authoring it; nothing is
  git-committed yet (Review 1 MINOR-4).
  The engineer did not read or write `datasets/gold/**`, and the queue
  `er-review-v1.csv` was not regenerated (sha256 still starts f75204756578ffc9).
- LLM adjudication sits behind a port with an off mode and a deterministic fake.
  The sample run used off mode.
- Sample run `run-02cecabcf3e68312` over upstream run `run-6d1175dbfdb874a9`, in
  git-ignored `target/er-match-run/`: 347 decisions, 338 ACCEPTED, 0 REJECTED,
  5 UNRESOLVED, 4 CONFLICT; unresolved rate 1.44%.
- The orchestrator check at the end of the report found no sbt or java process running
  and `datasets/gold` absent.
- Open items from its RISKS:
  - N4 needs a human decision: the engineer took the run ID plus the run manifest
    (`upstreamRunId`, `upstreamPipeline` "hadoop-v2") as the link to upstream, as
    implemented; the human has not accepted or rejected it.
  - No citation evidence is ever available, because work-v1 has no references
    (contract gap, domain-engineer).
  - The run output folders `rejected/`, `adjudications/` and `rejects-v1/` are absent
    because they hold zero rows; T3 and the reviewer must confirm that readers handle an
    absent empty output.
  - The sample has no REJECTED decisions; its 4 work CONFLICTs are DOI disagreements.
  - ER-J1 sends a whole `orcid:` profile to every linked candidate; measure the data
    growth before a full-DBLP run.
  - The adjudication cache is read whole into each reducer; a DynamoDB cache comes later.
  - Not done: the T3 evaluation, any Bedrock adjudicator, `sbt packageJobs`, an EMR run.

Note step 04 (T3, reports
`docs/agent-runs/phase-05/step-03-entity-resolution-engineer-report.md` and
`docs/agent-runs/phase-05/output-er-evaluation.md`): entity-resolution-engineer
COMPLETE, evaluation on v1 only against `run-02cecabcf3e68312`.

- `sbt check` is green: entityResolution 106 tests, 14 project test runs.
- Headline, from `output-er-evaluation.md`:
  - researcher: precision 0.938 [0.889, 0.966], recall 0.987, F1 0.962;
  - work: precision 1.000 [0.949, 1.000], recall 0.947, F1 0.973; no work pair is
    labeled NON_MATCH, so only the lower bound carries information;
  - unresolved rate 0.0144 over the whole run (5 of 347).
- er-v1 made 0 REJECTED decisions, so the reject side is unmeasured by this gold set.
- The v1b queue and its run were not used (decision 03).
- Not written: the evaluation copy under `target/` (see open item 3).

Note step 03b (T1b, report
`docs/agent-runs/phase-05/step-03b-entity-resolution-engineer-report.md`):
entity-resolution-engineer COMPLETE.

- Output: `target/er-review/er-review-v1b.csv` (git-ignored), 60 researcher rows, 0
  overlap with v1, sha256
  08c02f228229f907ce7de1b967dd22ce5de5af6263c06411fb23c7a68c86c845.
  The header is identical to v1; label and note are empty (orchestrator check).
- Selection by criterion: different ORCID 0, DBLP homonym 0, compatible initials with no
  shared context 10, same block different clusters 50.
- er-v1 decisions for the v1b pairs were produced in a separate run
  (`target/er-match-run-v1b`), for T3 only.
- `sbt check` green: entity-resolution 103 tests, 14 project test runs.
- New files: `review-supplement-v1b.json`, `ReviewSupplement.scala`,
  `ReviewSupplementMain.scala` and `ReviewSupplementSuite.scala`.
  `ReviewQueueMain.scala` changed visibility only.
- Blindness: that report holds er-v1's outcomes for the v1b pairs.
  Labeling v1b will not happen (decision 03), so the warning about showing it to a
  labeler no longer applies, but the report still holds those outcomes.
  This note records structure only.
- v1b is superseded: the queue stays unlabeled and unused.

Note human message 02 (`docs/agent-runs/phase-05/human-decision-02.md`):
`datasets/gold/er-gold-v1.csv` has 280 labeled rows (228 MATCH, 13 NON_MATCH, 39
UNSURE), as stated by the human; agents have not opened the file.
This message planned v1b labeling and a T3 run after it; decision 03 supersedes that.

Note human decision 03 (`docs/agent-runs/phase-05/human-decision-03.md`): the human
will not label v1b and asks for T3 now on `datasets/gold/er-gold-v1.csv` only (280
labeled rows).
It supersedes decision 01's v1b plan.
T3 evaluates the v1 decision run `run-02cecabcf3e68312` (`target/er-match-run`).
`target/er-review/er-review-v1b.csv`, its candidate run and `target/er-match-run-v1b`
are not used.
The evaluation report must record that the sample has few non-matches (13 NON_MATCH
labels), which limits how well precision and the reject side are measured.

Note human decision 04 (`docs/agent-runs/phase-05/human-decision-04.md`): answers open
items 1 and 2 of T3.
The 13 researcher NON_MATCH labels were judged by "different chapter", not "different
person", which was the wrong question for researcher pairs; this includes the
equal-ORCID pair `mc:researcher:10c07413d756444b3991`.
The human is not relabeling and not re-running T3; `datasets/gold/er-gold-v1.csv` and
the evaluation stay as they are.
Consequences, as the human directs: the 13 labels are known labeling errors, the 10
researcher false positives are likely not real errors of er-v1, researcher precision
(0.938) is understated, and the reject side is effectively unmeasured.
`output-er-evaluation.md` section 11 records this.

Note step 05 (documentation-engineer, report
`docs/agent-runs/phase-05/step-04-documentation-engineer-report.md`): written
`docs/entity-resolution.md` and `modules/entity-resolution/MODULE.md`, with only numbers
from the saved Phase 05 reports and the decision 04 statement above.
No sbt was run; the documented sbt commands were not replayed.

Note Review 1 (report `docs/agent-runs/phase-05/review-report.md`): verdict APPROVE WITH
CARRIED-FORWARD ITEMS, 6 MINOR and 4 NIT, no CRITICAL or MAJOR.
Its one `sbt check` exited 0 (core 94, hadoop 98, entityResolution 106, adversarial 68;
14 project test runs).

Note R1-2 (documentation-engineer): docs edits only, no sbt run.

- MINOR-3: `docs/entity-resolution.md` now says R-R2 is CONFLICT, or REJECTED when the
  score is at or below 0.35; the "an identifier agrees" condition stays with R-R3.
  Checked by reading `MatchPolicy.scala` lines 191-210.
- MINOR-4 (docs part): the page and Note step 03 now say er-v1 was fixed before the gold
  file existed and was not changed afterwards.
  The `PolicyConfig.scala` comment is entity-resolution-engineer's (R1-1).
  `output-er-evaluation.md:27` keeps the old wording; it is saved evidence.
- MINOR-5: the "recall without caveat" sentence is removed from the page.
- MINOR-6: MODULE.md says `LocalMatchRunner` is in `src/main`, with only the Hadoop
  runtime Test-scoped.

Note R1-1 (report `docs/agent-runs/phase-05/repair-01-entity-resolution-engineer.md`):
entity-resolution-engineer COMPLETE.

- `sbt fmt check` exited 0: core 94, entityResolution 112 (106 before), hadoop 98,
  adversarial 68; 14 project test runs.
- MINOR-2: every adjudication cache hit is re-validated (`Adjudicator.recheck`); a
  refused hit gives `LLM_INVALID_OUTPUT` with no call.
- NIT-1: `EvaluateMain.samePolicy` refuses a run made under another policy version.
- NIT-2: `LocalMatchRunner` scratch is `<output>-tmp`, removed after the run (ER only).
- PolicyConfig comment fixed.
  Policy resource sha256 starts 82ce9ae5 (unchanged); no evaluation was rerun.
- MINOR-1 is not fixed (carried to er-v2).

Note R1-3 (documentation-engineer): the R1-1 RISKS 6 wording is applied to
`modules/entity-resolution/MODULE.md` and `docs/entity-resolution.md`.
Each claim was checked by reading `Adjudication.scala`, `MatchJob.scala`,
`eval/EvaluateMain.scala` and the hadoop `LocalRunner.scala`; no sbt was run.

Carried forward from Review 1:

- MINOR-1: a future policy version er-v2 (fixing it changes er-v1 behavior).
- NIT-3: no action.
- NIT-4: the Phase 07 loader must treat an absent outcome folder as empty only when
  `_SUCCESS` exists (storage-engineer).
- Human: the `target/**` matrix gap; `chmod 644` on the gold file before the commit;
  the MINOR-1 decision; N4.
- Process: the harness backgrounds sbt at 180 s; guard-dangerous false positives on the
  test-marker task name.

Note Review 2 (report `docs/agent-runs/phase-05/review-report-02.md`): code-reviewer on
opus, high, fresh context.

- Verdict APPROVE WITH CARRIED-FORWARD ITEMS; no CRITICAL or MAJOR finding.
- Its one `sbt check` exited 0: core 94, entityResolution 112, hadoop 98, adversarial
  68; 14 project test runs; 11:36 (its output shows a total time of 148 s).
- All Review 1 repair items are fixed: MINOR-2, MINOR-3, MINOR-4, MINOR-5, MINOR-6,
  NIT-1 and NIT-2 (NIT-2 for new runs; the old `_local-tmp` is left for the human).
- Carried from Review 1: MINOR-1, NIT-3 and NIT-4.
- New findings: MINOR-A (the stale snapshot, replaced below), MINOR-B, MINOR-C,
  NIT-A, NIT-B and NIT-C.
  Per the one-round rule there is no second repair round.
- The er-v1 policy resource hash is unchanged (starts 82ce9ae5) and no leakage was
  found.

## Current state (phase 05)

- Tasks done: step 01 (build-engineer), step 02 (T1), step 03 (T2), step 03b (T1b),
  step 04 (T3, evaluation on v1), step 05 (documentation), Review 1, R1-1, R1-2,
  R1-3 and Review 2.
- Task running: none.
- Next task: the Phase 05 human gate (ratify, commit, decide the carried-forward items
  below).
- Half done: nothing.
  v1 labeling is done (280 rows) and v1b is abandoned (decision 03).
- Git: HEAD is 4388607 (Phase 04 accepted).
  `git status --short` at the time of this update showed:
  - modified, tracked: `STATUS.md`, `build.sbt`,
    `modules/entity-resolution/MODULE.md`;
  - untracked: `datasets/gold/`, `docs/agent-runs/phase-05/`,
    `docs/entity-resolution.md`,
    `modules/entity-resolution/src/main/resources/scholarchain/`,
    `modules/entity-resolution/src/main/scala/scholarchain/`, and 10 files in
    `tests/module-unit/entity-resolution/` (AdjudicationSuite, ErFixtures,
    ErNormalizeSuite, EvaluationSuite, FeaturesSuite, LeakageSuite, MatchJobSuite,
    MatchPolicySuite, ReviewQueueSuite, ReviewSupplementSuite).
- Harness: claude-code.
- Resume in a fresh session with:
  `cd ~/scholarchain && claude "Read STATUS.md and continue with its next step."`

## Carried forward from Phase 05 (for the human gate)

From `docs/agent-runs/phase-05/review-report-02.md` section 5, plus the items named
in this file.

1. MINOR-1 (Review 1): DBLP homonym false REJECT.
   Needs `er-features-v2` and policy `er-v2`, decided without looking at the labels.
2. NIT-3 (Review 1): LeakageSuite is a string tripwire only; no action.
3. NIT-4 (Review 1): the Phase 07 loader (storage-engineer) must treat an absent
   outcome folder as empty only when `_SUCCESS` exists.
   The Phase 07 DynamoDB cache must keep re-validation on every hit.
4. Human decisions:
   - whether to bump `MatchJob.LogicVersion` (NIT-A; `er-job-v1` is unchanged and a
     bump changes the run ID of any rerun);
   - whether to delete the stale `target/er-match-run/_local-tmp`;
   - the `target/**` gap in the ownership matrix (the evaluation copy under `target/`
     was not written; either factory-engineer changes the matrix or the human accepts
     the `docs/agent-runs/phase-05/` copy only);
   - `chmod 644 datasets/gold/er-gold-v1.csv` before committing (it is mode 755);
   - N4 (upstream link by run ID plus run manifest; implemented, not yet accepted).
5. Review 2 findings carried forward unfixed (no second repair round):
   - MINOR-B (documentation-engineer): MINOR-1 is missing from the Known issues of
     `modules/entity-resolution/MODULE.md`, and `docs/entity-resolution.md` lines 141
     and 180 describe the R-R3 intent, not the behaviour.
     `Features.scala:192-201` also fires on case, accent or spacing variants and when
     only one side has a suffix.
   - MINOR-C (documentation-engineer to track; factory-engineer to fix in Phase 11
     guard work): the Phase 01 item "Phase 05: gold-set bypasses (`rm -rf datasets`
     and similar) and MINOR-1 (gold-set interpreter writes)" (under "Carried forward
     from Phase 01") is still open, because Phase 05 made no control-plane change.
     `datasets/gold/er-gold-v1.csv` now holds real labels, so the open bypass protects
     real data.
   - NIT-B (entity-resolution-engineer): `AdjudicationSuite.scala:193-212`, the
     inputHash and candidateId refusals are not isolated, because those records also
     carry a mismatching model version `"m"`.
   - NIT-C (documentation-engineer): `docs/entity-resolution.md:274-275` omits that the
     scratch folder is removed only when this run created it.
6. Gold-label caveat (decision 04): the 13 NON_MATCH labels are known labeling errors
   ("different chapter" was judged, not "different person"), researcher precision
   (0.938) is understated, and the reject side is effectively unmeasured.
   Neither the labels nor the evaluation were changed.
7. Process items: the harness moves `sbt` to the background at 180 s (the sixth time
   this phase); the guard-dangerous false positive on the test-marker task name
   belongs to factory-engineer in Phase 11.

## Previous state (phase 04, closed)

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
