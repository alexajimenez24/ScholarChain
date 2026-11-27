# Phase 13 run log

Orchestrator: main session, model `claude-sonnet-5-5` (the matrix puts the orchestrator on the high tier; deviation to note at the gate). Times are local, 2026-11-26. Times marked `~` were not read from the clock.

**Phase state: work order complete, waiting for the human gate. Final `sbt check` in the repository: exit 0. Nothing committed.**

| Step | Agent | Model and effort | Start | End | Report | STATUS |
|---|---|---|---|---|---|---|
| 1 | service-engineer | sonnet, medium | 15:49 | 15:58 | step-01-service-engineer-report.md | COMPLETE |
| 2 | storage-engineer | sonnet, medium | 15:58 | ~16:08 | step-02-storage-engineer-report.md | BLOCKED-ON rag-engineer |
| 2a | rag-engineer (unblock) | opus, high | ~16:08 | ~16:10 | step-02a-rag-engineer-report.md | COMPLETE |
| 2b | storage-engineer (fresh rerun) | sonnet, medium | ~16:10 | ~16:12 | step-02b-storage-engineer-rerun-report.md | COMPLETE |
| 3 | infra-engineer (task A1) | opus, high | 16:12 | 17:10 | step-03-infra-engineer-report.md | COMPLETE |
| 4 | infra-engineer (task A2) | opus, high | 17:10 | 17:44 | agent-report-blocked.md | BLOCKED-ON test-engineer (also service-engineer or build-engineer) |
| 4a | test-engineer (unblock) | opus, high | 17:44 | ~18:13 | step-04a-test-engineer-report.md | COMPLETE |
| 4b | service-engineer (unblock) | sonnet, medium | ~18:13 | ~18:15 | step-04b-service-engineer-report.md | COMPLETE |
| 4 rerun | infra-engineer (fresh) | opus, high | ~18:15 | 18:33 | agent-report-blocked-02.md | BLOCKED-ON build-engineer (second block; stopped for the human) |
| 4c | build-engineer (routed by the human) | sonnet, medium | 19:33 | ~19:44 | step-04c-build-engineer-report.md | COMPLETE |
| 4 rerun 2 | infra-engineer (fresh) | opus, high | 19:44 | 20:00 | agent-report.md | COMPLETE, clean-copy check.sh 7 of 7 |
| 5 | documentation-engineer | haiku (low, step-down: the work order asks for the low tier) | 20:00 | ~20:02 | step-04-documentation-engineer-report.md | COMPLETE |
| 6 | code-reviewer (review) | sonnet, medium | 20:02 | ~20:07 | review-report.md | COMPLETE, REQUEST_CHANGES (1 MAJOR, 3 MINOR) |
| 7 | infra-engineer (repair round 1, the only one) | opus, high | 20:07 | ~20:10 | repair-01.md | COMPLETE |
| 8 | code-reviewer (fresh re-check) | sonnet, medium | ~20:10 | ~20:10 | review-report-02.md | COMPLETE, APPROVE |
| 9 | final `sbt check` (orchestrator command) | - | 20:10 | 20:29 | output-sbt-check-final.txt | exit 0 |

Human decision after the second block: "route to build-engineer", as one task: limit test concurrency in `sbt check`, and package the synthesis prompt and schema into the queryService image with a test; then one clean-copy `check.sh`, STATUS.md, the review with at most one repair round, ending with `sbt check`.
build-engineer may not write `tests/module-unit/services/**`, so the work order asked for the resource test as a build-level check in the `check` alias (`queryService/verifySynthesisResources`).

Final `sbt check` (repository, after the repair): exit 0 in 1129 s. The per-project totals add to 1139 tests passed, 0 failed, plus 1 ignored (the gated AWS suite). `verifySynthesisResources` matched 2 resources. The marker `.claude/.last-test-run` was updated. The harness moved the command to the background at 600 s; nothing else was started or polled.

Evidence:
- `output-check-clean.txt`: the passing clean-copy run (7 of 7). It predates the repair, which changed one Terraform statement and a comment; the repair has its own `fmt -check` and `validate` of the data stack.
- `output-check-clean-run1.txt` and `output-check-clean-run2.txt`: the two failed clean-copy runs. `output-check-clean-rerun-services.txt` and `output-check-clean-rerun-failed.txt`: the failing projects pass alone.
- `output-package.txt` and `output-image-mainclass.txt`: 6 jars and 3 images from the one package run. STALE for query-service since the step 4c build change.
- `output-dry-*.txt`: dry runs. `output-dry-cleanup.txt` records that the guard blocked the cleanup dry run.

IAM change for the human to see (security change, `docs/agents.md` section 6): statement `WriteLoadReports` on runtime-worker, `s3:PutObject` and `s3:GetObject` on the curated bucket's `logs/*` (repair-01.md).

Open for the human:
- MINOR-1: `sc_aws` and the `docker push` pipe leave stderr unredacted, so an AWS or push error can print the account ID (review-report.md).
- MINOR-2: `run-bounded-pipeline.sh` collects evidence before the leftover-cluster check and the scale-to-zero, so a failed evidence call leaves ECS billing.
- MINOR-3: the EMR step helper exports one credential snapshot with no refresh; its remaining lifetime is unverified.
- The cleanup dry run: no agent may run it (command in agent-report.md).
- Rerun `scripts/package.sh` before Phase 14. No image was built or started with `LLM_ENABLED=true`.

Carried forward:
- `ServingData` is a stand-in for missing owner adapters for rag's `ResearcherDirectory` and `MetricRanking`; its memory use is unmeasured.
- The cloud window estimate is $6.85 typical and $13.43 upper (step 3).
- `docs/agent-runs/README.md` does not document `APPROVED-EXPERIMENT-SHA256` (factory-engineer).
- The synthesis file list in `build.sbt` must be updated when rag adds a prompt or schema version.
- Further items are under RISKS in each step report. `modules/*/MODULE.md` were not updated in this phase.
- STATUS.md was written once (step 5), as the addendum asks, so it predates the review, the repair, the re-check and the final check. This log carries the later state.

State:
- No AWS call, plan, apply, deploy or cloud resource. Nothing committed or staged in the real repository.
- Left behind: local Docker images (`:local` and `:90370d8...-dirty`), throwaway copies in the session scratchpad, and `target/` outputs.

## After the first gate report: human-ordered fix (newest state; it overrides the lines above)

Human decisions, in chat, 2026-11-26:
- "I approve the WriteLoadReports permission on runtime-worker." This log records the statement; it is not `human-gate.md`, which only the human writes.
- One small infra-engineer task, a disclosed exception after the re-check, for review MINOR-1, MINOR-2 and MINOR-3. A fresh code-reviewer checks only those three items. documentation-engineer then updates STATUS.md (a second write, ordered by the human). Reports under 40 lines. No commits.
- The same message arrived twice. The task was run once.

| Step | Agent | Model and effort | Start | Report | STATUS |
|---|---|---|---|---|---|
| 10 | infra-engineer (repair 2) | opus, high | 21:49 | repair-02.md | COMPLETE, 74 offline stub tests passed (author's own) |
| 11 | code-reviewer (three items only) | sonnet, medium | not read from the clock | review-report-03.md | COMPLETE, REQUEST_CHANGES (1 MAJOR, 3 MINOR) |
| 12 | documentation-engineer | haiku (low, step-down: small notes update) | not read from the clock | step-05-documentation-engineer-report.md | COMPLETE, STATUS.md current |

- After step 12: no file under `build.sbt`, `project/`, `modules/`, `tests/` or `prompts/` is newer than `.claude/.last-test-run`. The phase folder and STATUS.md scan found no match. Nothing staged; HEAD is still 90370d8.
- Item 1 (redaction): MET. Item 3 (credential refresh): MET by code reading; not run with Java or on EMR. Item 2 (trap): NOT MET.
- OPEN MAJOR (infra-engineer): `scripts/lib/common.sh` does not handle SIGPIPE. If the script's output reader closes (for example Ctrl-C with `| tee`), the guard dies with exit 141: no cluster check, no scale to zero, no GUARD-FAILED line. The reviewer reproduced it with stubs. It was not repaired: no further repair has been ordered.
- OPEN MINORs: a signal is deferred while a `timeout` child runs (up to maxMinutes for an experiment); `finish` is unguarded until its first describe-execution succeeds; a failed `register-scalable-target` skips desired count 0 for that service.
- For the human: a successful `start` now scales ECS to zero unless `--keep-services` is given; queued embedding jobs then wait. `finish` refused because the run is still RUNNING does not scale down.
- The report limit of 40 lines is for this task's reports.
- No Scala or Terraform file changed in repair 2; no sbt or terraform validate was run for it. No AWS call. Nothing committed.

## Final round for the scripts (newest state; it overrides the sections above)

Human decision, in chat, 2026-11-26: one final infra-engineer round for four trap items (a disclosed exception), checked only on those four by a fresh code-reviewer; anything still open is carried forward to the human; reports under 40 lines; no commits. The human accepted both choices: scale to zero on success unless `--keep-services` is given, and `finish` refusing to scale down while the pipeline runs.

| Step | Agent | Model and effort | Start | Report | STATUS |
|---|---|---|---|---|---|
| 13 | infra-engineer (repair 3) | opus, high | 22:22 | repair-03.md | COMPLETE, author reports 103/103 offline tests |
| 14 | code-reviewer (four items only) | sonnet, medium | not read from the clock | review-report-04.md | COMPLETE, REQUEST_CHANGES (1 MAJOR, 1 MINOR) |
| 15 | documentation-engineer | haiku (low, step-down: small notes update) | not read from the clock | step-06-documentation-engineer-report.md | COMPLETE. The orchestrator ordered it under the after-task STATUS.md rule in AGENTS.md; this human message did not ask for it |

- STATUS.md says old MINOR-2 and MINOR-3 of `review-report-03.md` are not named in `review-report-04.md`. For clarity: they are the subjects of items 2 and 3 of the final round, judged MET (item 2 for single failures).
- After step 15: the scan of the phase folder, STATUS.md and `scripts/` found no match; nothing staged; HEAD is 90370d8; no build, module, test or prompt file is newer than `.claude/.last-test-run`.
- Items 1, 2 and 3: MET (item 2 for single failures; the MAJOR can still skip its cleanup). Item 4: NOT MET.
- CARRIED TO THE HUMAN, MAJOR (infra-engineer): a second stop signal during the first one's handling can make the script exit with no cluster check, no scale-down and no GUARD-FAILED line (`scripts/lib/common.sh` 450-453, 482-485, 645). The reviewer saw it in 4 of 15 runs, including under an outer `timeout`. The reviewer's run of the guard tests was 27/28, so the author's 103/103 did not reproduce.
- CARRIED TO THE HUMAN, MINOR (infra-engineer): the cluster-name SSM read is tried once; if it fails, all three scale-downs are skipped (GUARD-FAILED is printed).
- Workaround until fixed (reviewer): no outer `timeout` around `run-bounded-pipeline.sh` or `run-experiment.sh` without allowing for this, no repeated stop signals, and after any interrupted run check EMR and ECS by hand.
- No Scala or Terraform change in repair 3; no sbt run for it. No AWS call. Nothing committed.

Orchestrator notes:
- Agents handed reports back as messages and the orchestrator saved them. It removed the hand-back indentation and made small edits: shortened some absolute paths, changed some first-person wording to third person, and trimmed minor parentheticals. In the final agent-report.md it also added one line listing the script command lines, and noted that it had copied the output file. No status, result or finding was changed.
- The orchestrator copied the output files from the scratchpad, because agents may not write `docs/agent-runs/`.
- The orchestrator searched `docs/agent-runs/phase-13/` for 12-digit numbers, access key IDs and Gmail addresses, after the re-check and again on the final check output: no match.
