HARNESS: claude-code
MODEL: opus, effort high (high tier, infra-engineer floor)
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 13 step 4, task A2, second rerun after build-engineer's fix (Tags.Test limited to 1 and made exclusive; v2 synthesis files in assembly; queryService/verifySynthesisResources in the check alias). The job: run the clean-copy check.sh once in a new directory, rerun neither package.sh nor any dry run, and change no files.
FILES CREATED: none in the repository. Scratchpad only: a2-outputs-3/output-check-clean.txt (854 lines; the command line is first and `exit code: 0` is last) and the copy clean-copy-3/. The orchestrator copied the output to docs/agent-runs/phase-13/output-check-clean.txt. The package and dry-run outputs from the first A2 run are already in docs/agent-runs/phase-13/.
FILES MODIFIED: none in this rerun. From the first A2 run: scripts/package.sh, scripts/deploy.sh (--images-only text) and scripts/lib/common.sh (a die message).
COMMANDS RUN:
- `git ls-files -z --cached --others --exclude-standard | rsync -a --from0 --files-from=- ./ <scratchpad>/clean-copy-3/` copied 862 of 862 files into a new, empty directory. The copy's build.sbt contains Tags.exclusive(Tags.Test) and verifySynthesisResources.
- In the copy only: `git init -b main`, `git add -A` and a plain `git commit` with a throwaway identity, giving HEAD 6e1163d and a clean tree. No --no-verify, no guard workaround, no commit in the real repository.
- `timeout 45m scripts/check.sh` in the copy, once: exit 0. The harness moved it to the background; nothing else was started or polled.
- A grep scan of the saved output.
- Not rerun, as ordered: package.sh and every dry run.
- NOT RUN: `bash scripts/cleanup.sh --env dev --dry-run`, which guard-dangerous blocks for agents (human only).
TEST RESULTS: check.sh `RESULT PASS: 7 of 7 gates passed at 6e1163d3e38aed2bbe22dd39dec03d35dcd5e017`.
- constitution: 19 roles, 862 files with one writer, 44 generated files match. json: 9 files parse. hooks: 6 shell and 3 Python files parse. shell: 18 files pass bash -n, 16 pass shellcheck. schema: 5 datasets. terraform: fmt -check clean, 5 of 5 stacks valid.
- sbt: 1139 tests, 982 passed, 0 failed, 157 skipped, 1 ignored, across 11 projects. verifySynthesisResources: "2 packaged resources match the repository". The test phase took 604 s.
- The GraphWiringSuite and PipelineSuite timeouts did not recur. The clean-checkout gate passes.
VALIDATION RESULTS:
- No stateful replacement, no plan, no IAM change, no cost, no AWS call.
- Secret scan of the new output: 0 twelve-digit numbers, 0 AKIA/ASIA key IDs, 0 private keys, 0 token, secret or password assignments, 0 JWTs, 0 emails. The only "secret" text is the test fixture `secret-marker-7f3a` (2 lines). The local login name appears in 48 absolute paths.
- package.sh, from the first run, is STALE. It produced 6 checksummed jars and 3 images, built one at a time and tagged :90370d8...-dirty. Both the images and the query-service jar checksum predate the build.sbt fix, so package.sh must run again before Phase 14 packaging evidence counts. Earlier observation: each image's main class is in its jar, and each runs `java -jar /opt/scholarchain/app.jar` as user 10001.
- Dry runs, from the first run: each names what it would do and refuses as it should (dirty tree, no phase-13 human-gate.md, no saved plan, no smoke token). Checks that need AWS print NOT-CHECKED. Exit codes: deploy 3, smoke test 3, pipeline start 3, pipeline finish 0, archive 0, rollback 3, plan 0, experiment 3.
- Command lines (each from the repository root; saved outputs show the exact arguments): scripts/{check,package,plan,deploy,smoke-test,run-bounded-pipeline,run-experiment,archive-evidence,rollback,cleanup}.sh; see output-dry-*.txt.
- Environment variables by name:
  - check.sh and package.sh: none.
  - plan.sh: TF_VAR_owner; foundation also needs TF_VAR_budget_alert_email and TF_VAR_state_bucket_name; SCHOLARCHAIN_PLAN_DIR is optional.
  - deploy.sh, pipeline start and run-experiment.sh: AWS_PROFILE=scholarchain-dev-deploy. deploy.sh also reads SCHOLARCHAIN_PLAN_DIR. run-experiment.sh sets SCHOLARCHAIN_ENV, SCHOLARCHAIN_EXPERIMENT_{ID,RUN,OUT,MAX_ECS_TASKS,MAX_EMR_INSTANCES} and AWS_REGION.
  - smoke-test.sh: SCHOLARCHAIN_SMOKE_TOKEN, unless --unauthenticated-only.
  - archive-evidence.sh: SCHOLARCHAIN_PLAN_DIR is optional.
  - cleanup.sh: SCHOLARCHAIN_CLEANUP_CONFIRM=destroy-<env>, the 3 TF_VAR_* variables, administrator credentials, a terminal, and none of CLAUDECODE, SCHOLARCHAIN_ROLE or CODEX_* set.
- Human approval points, in docs/agent-runs/phase-NN/human-gate.md:
  - deploy.sh: APPROVED-COMMIT equal to HEAD, APPROVED-ENVIRONMENT, COST-ESTIMATE-USD <= BUDGET-REMAINING-USD <= 25, and APPROVED-PLAN-SHA256 (hashed again just before apply); the plan metadata must show dirty=false.
  - pipeline start: the commit, environment and cost lines.
  - run-experiment.sh: those lines plus APPROVED-EXPERIMENT-SHA256.
  - rollback.sh: APPROVED-ENVIRONMENT.
  - cleanup.sh: the confirmation variable and a human at a terminal.
- Generated evidence: package: target/package/<tag>/{package-manifest.txt,SHA256SUMS}. plan: ~/.scholarchain/plans/<env>/<stack>.{tfplan,tfplan.meta,plan.txt}. deploy: ==> RELEASE lines for releases/. pipeline: target/pipeline-evidence/<run>/. experiment: target/experiments/<id>/<run>/. archive: target/evidence/phase-NN-<sha12>-<time>/ with INDEX.txt and SHA256SUMS.
- Rollback: rollback.sh points the 3 ECS services at an existing ACTIVE task definition revision that uses an ancestor commit's immutable image tag. It creates no new revision, changes no scaling, runs no Terraform, touches no data, and prints ==> DRIFT. An infrastructure rollback means plan.sh on the earlier commit, then deploy.sh through the human gate.
ASSUMPTIONS:
- The copy of the working tree, including other owners' uncommitted changes, is the tree the human will commit. The proof holds for that content, not for commit 90370d8.
- A pass at 1-way test concurrency is the intended gate. The run took about 12 minutes in total, well inside the 45-minute timeout.
RISKS:
- package.sh outputs and images are stale for query-service and are not rerun here, as ordered.
- `sbt check` was not run in the real repository, which the stop hook asked for. No source changed, and the clean copy passed the same alias.
- Left behind: clean-copy, clean-copy-2 and clean-copy-3 in the scratchpad (throwaway .git directories), local Docker images from the first package run, and target/ outputs. No cloud resources.
MANUAL ACTION REQUIRED: run the cleanup dry run yourself, from a shell with no agent: `cd ~/scholarchain && timeout 120s bash scripts/cleanup.sh --env dev --dry-run; echo "exit code: $?"`. The expected exit is 3; it makes no AWS call and deletes nothing. There is no plan SHA-256 or cost to sign. Information: the aws-api MCP server is at end of development; migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 5 (documentation-engineer, STATUS.md), then the fresh code-reviewer review. Rerun package.sh before any Phase 14 packaging evidence is used.
