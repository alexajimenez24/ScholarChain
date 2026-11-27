HARNESS: claude-code
MODEL: opus, effort high (high tier, infra-engineer floor)
STATUS: BLOCKED-ON
OWNER: infra-engineer
WORK ORDER: Phase 13 step 4, task A2, rerun after the BLOCKED-ON fixes (test-engineer benchmark guard; service-engineer munitTimeout of 2 minutes). Steps: clean-copy check.sh once; package.sh not rerun; deploy dry run rerun only if its text changed; outputs saved outside the repository.
FILES CREATED: none in the repository. Outputs in the session scratchpad a2-outputs-2/: output-check-clean.txt and output-check-clean-rerun-failed.txt (a diagnostic run). Each starts with its command line and ends with its exit code. The package and dry-run outputs from the first A2 run are already in docs/agent-runs/phase-13/ and still hold.
FILES MODIFIED: none in this rerun. From the first A2 run: scripts/package.sh (each image in its own `timeout 20m sbt <project>/dockerLocalBuild` after one assembly run; step markers joined with spaces), scripts/deploy.sh (--images-only text) and scripts/lib/common.sh (a die message joined with spaces). No script has changed since then: the newest is deploy.sh at 17:41, before the 17:42 dry runs.
COMMANDS RUN:
- `git ls-files -z --cached --others --exclude-standard | rsync -a --from0 --files-from=- ./ <scratchpad>/clean-copy-2/` copied 855 of 855 files into a new, empty directory. In the copy only: `git init -b main`, `git add -A` and a plain `git commit` with a throwaway identity (HEAD 89742df, clean). No --no-verify, no guard workaround, no commit in the real repository.
- `timeout 45m scripts/check.sh` in the copy, once: exit 1. The harness moved it to the background; I started nothing else and did not poll.
- Diagnostic, not a gate retry: `timeout 30m sbt -batch graphService/test rag/test` in the copy, exit 0.
- `shellcheck -x -P scripts` and `bash -n` on package.sh, deploy.sh and lib/common.sh in the real repository: clean (check.sh's shell gate covers the same 3 files).
- Not rerun: package.sh (once per the work order) and every dry run. output-dry-deploy.txt (17:42:03) is newer than deploy.sh (17:41:55), and its "would" line matches deploy.sh line 119.
- NOT RUN: `bash scripts/cleanup.sh --env dev --dry-run`. guard-dangerous blocks it for agents ("running scripts/cleanup.sh (human only)").
TEST RESULTS: check.sh RESULT FAIL, 6 of 7 gates passed:
- constitution: 19 roles, 855 files with one writer, 44 generated files match. json: 9 files parse. hooks: 6 shell and 3 Python files parse. shell: 18 files pass bash -n, 16 pass shellcheck. schema: 5 datasets. terraform: fmt clean, 5 of 5 stacks valid.
- sbt failed: 1139 tests, 980 passed, 2 failed, 157 skipped, 1 ignored.
Both earlier fixes held. The newly guarded benchmark test now skips (157 skips, up from 156). No munit 30s timeout recurred: DownstreamMappingSuite took 33.7s and passed. The 2 new failures are timeouts inside the code under load, and both projects pass when run alone (graphService 18/18, rag 166/166).
VALIDATION RESULTS:
- No stateful replacement, no plan, no IAM change, no cost, no AWS call.
- package.sh, from the first run: 6 jars checksummed. 3 images built one at a time and tagged :90370d8...-dirty. The query-service jar has exactly one RendererProvider (SemanticRendererProvider). Each image's main class is in its jar, and each runs `java -jar /opt/scholarchain/app.jar` as user 10001.
- Dry runs, from the first run: each names what it would do and refuses as it should (dirty tree, no phase-13 human-gate.md, no saved plan, no smoke token). Checks that need AWS print NOT-CHECKED. Exit codes: deploy 3, smoke test 3, pipeline start 3, pipeline finish 0, archive 0, rollback 3, plan 0, experiment 3.
- Secret scan of both new outputs: 0 twelve-digit numbers, 0 AKIA/ASIA key IDs, 0 private keys, 0 token or password patterns, 0 emails. The only match is the test fixture `secret-marker-7f3a`. The local login name appears in 47 absolute paths.
- Environment variables by name (checked again in the scripts):
  - check.sh and package.sh: none.
  - plan.sh: TF_VAR_owner; foundation also needs TF_VAR_budget_alert_email and TF_VAR_state_bucket_name; SCHOLARCHAIN_PLAN_DIR is optional.
  - deploy.sh, pipeline start and run-experiment.sh: AWS_PROFILE=scholarchain-dev-deploy. deploy.sh also reads SCHOLARCHAIN_PLAN_DIR. run-experiment.sh sets SCHOLARCHAIN_ENV, SCHOLARCHAIN_EXPERIMENT_{ID,RUN,OUT,MAX_ECS_TASKS,MAX_EMR_INSTANCES} and AWS_REGION.
  - smoke-test.sh: SCHOLARCHAIN_SMOKE_TOKEN, unless --unauthenticated-only.
  - archive-evidence.sh: SCHOLARCHAIN_PLAN_DIR is optional.
  - cleanup.sh: SCHOLARCHAIN_CLEANUP_CONFIRM=destroy-<env>, the 3 TF_VAR_*, administrator credentials, a terminal, and no CLAUDECODE, SCHOLARCHAIN_ROLE or CODEX_* set.
- Human approval points, in docs/agent-runs/phase-NN/human-gate.md:
  - deploy.sh: APPROVED-COMMIT equal to HEAD, APPROVED-ENVIRONMENT, COST-ESTIMATE-USD <= BUDGET-REMAINING-USD <= 25, and APPROVED-PLAN-SHA256 (hashed again just before apply); the plan metadata must show dirty=false.
  - pipeline start: the commit, environment and cost lines.
  - run-experiment.sh: those lines plus APPROVED-EXPERIMENT-SHA256.
  - rollback.sh: APPROVED-ENVIRONMENT.
  - cleanup.sh: the confirmation variable and a human at a terminal.
- Generated evidence: package: target/package/<tag>/{package-manifest.txt,SHA256SUMS}. plan: ~/.scholarchain/plans/<env>/<stack>.{tfplan,tfplan.meta,plan.txt}. deploy: ==> RELEASE lines for releases/. pipeline: target/pipeline-evidence/<run>/. experiment: target/experiments/<id>/<run>/. archive: target/evidence/phase-NN-<sha12>-<time>/ with INDEX.txt and SHA256SUMS.
- Rollback: rollback.sh points the 3 ECS services at an existing ACTIVE task definition revision with an ancestor commit's immutable image tag. It creates no new revision, changes no scaling, runs no Terraform, touches no data, and prints ==> DRIFT. An infrastructure rollback means plan.sh on the earlier commit, then deploy.sh through the human gate.
ASSUMPTIONS:
- A single targeted run of the 2 failing projects counts as diagnosis, not a blind retry. The gate itself ran exactly once.
- Both failures are load-induced (19.6s and 7.0s under the full parallel run, about 6s when run alone).
RISKS:
- The clean-checkout gate is still not green, and timing flakes under full `sbt check` load (5 GB WSL) now hit a non-services suite.
- I did not run `sbt check` in the real repository, which the stop hook asked for. I changed no source, and the gate is blocked anyway.
- Left behind: <scratchpad>/clean-copy-2 (throwaway .git), clean-copy from the first run, local Docker images from the first package run, and target/ outputs. No cloud resources.
MANUAL ACTION REQUIRED: run the cleanup dry run yourself, from a shell with no agent: `cd ~/scholarchain && timeout 120s bash scripts/cleanup.sh --env dev --dry-run; echo "exit code: $?"` (expected exit 3; no AWS call, deletes nothing). There is no plan SHA-256 or cost to sign. Information: the aws-api MCP server is at end of development; migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: build-engineer (primary): limit test concurrency under `sbt check`, which fixes both failures and any later ones like them. Alternatives, one fix per suite: rag-engineer (PipelineSuite's 5000 ms stage deadline on the fake world) and service-engineer (GraphWiringSuite readiness deadline under load).
BLOCKED-ON EVIDENCE:
- "==> X scholarchain.services.graph.GraphWiringSuite.the wired graph service is live, and ready only while Neptune answers 19.598s munit.ComparisonFailException: tests/module-unit/services/graph/GraphWiringSuite.scala:64 ... Obtained Status(code = 503) ... expected code = 200"
- "==> X scholarchain.rag.PipelineSuite.an unknown name abstains; a resolved name without evidence in the window abstains with a note 6.958s munit.FailException: tests/module-unit/rag/PipelineSuite.scala:13 coauthors timed out after 5000 ms"
- Alone: "Passed: Total 18, Failed 0" (graphService) and "Passed: Total 166, Failed 0" (rag).
NEXT GATE: after the fix, a fresh infra-engineer reruns the clean-copy check.sh (in a new directory). Then step 5 (documentation-engineer).
