```text
Use the infra-engineer agent to create or finalize the source-controlled automation under scripts/ and any optional GitHub Actions workflows that call those scripts.

Required scripts or equivalent commands:

1. scripts/check.sh
- Validate agent constitution files and JSON configuration.
- Run hook syntax checks.
- Run scalafmt check, sbt Test/compile, sbt test, contract validation, schema validation, and shell syntax checks.
- Run Terraform format and validate.
- Exit nonzero on any failed gate.

2. scripts/package.sh
- Build Hadoop and Spark artifacts.
- Build required service images.
- Record checksums and image tags derived from the Git commit SHA.

3. scripts/plan.sh
- Run Terraform plan for the selected environment.
- Record plan file, plan checksum, commit SHA, target account and Region, and a summary of add, change, destroy, IAM, and stateful-resource actions.
- Never apply automatically.

4. scripts/deploy.sh
- Refuse a dirty tree.
- Require an explicitly approved plan checksum or the documented human-approval mechanism.
- Apply or invoke the approved deployment path exactly as documented.
- Record deployed image digests and revisions.

5. scripts/smoke-test.sh
- Check health and readiness.
- Run a bounded researcher query.
- Fetch evidence for at least one returned claim.
- Verify idempotency and one expected error path.

6. scripts/run-bounded-pipeline.sh
- Run or submit the approved bounded Hadoop and Spark pipeline.
- Record dataset snapshot, run IDs, configuration, and output locations.

7. scripts/run-experiment.sh or an equivalent manifest-driven command
- Execute only approved experiment manifests.
- Record commit SHA, resource limits, start and end times, Region, and metric locations.

8. scripts/archive-evidence.sh
- Gather test reports, plan checksum, image digests, run manifests, selected CloudWatch metric exports, experiment outputs, and release metadata into a versioned evidence directory or approved S3 prefix.

9. scripts/cleanup.sh
- List resources targeted for removal.
- Require the documented human confirmation for destructive actions.
- Preserve required state, logs, or final reports according to the cleanup policy.

Automation requirements:
- Bash strict mode or an equivalently strict scripting language configuration.
- No secrets in source control or command output.
- Timeouts on cloud operations.
- Retry only safe idempotent actions.
- Greppable step markers and explicit exit codes.
- Commands must work from a clean checkout after documented prerequisites are installed.
- Optional GitHub Actions may call these scripts, but the scripts remain the authoritative reproducibility interface.

Run every non-destructive script in the bounded non-production environment.
Report command lines, required environment variables by name, human approval points, generated evidence, and rollback behavior.
```

Addendum from the human architect for this repository:

- Scope: the Phase 13 gate, plus the gaps the Phase 12 gate recorded as needed before
  Phase 14. Nothing beyond that. A clean checkout runs all non-cloud validation through
  the documented script; planning is reproducible with its checksum recorded; deployment
  cannot proceed without my approval; the smoke test exercises a real bounded
  collaboration query; the same scripts are used for reproduction and the demonstration.
- Work without stopping to ask: take the most reasonable reading, record each choice and
  why in the step report, and carry on. Stop only for BLOCKED-ON, and route it as the rules
  say.
- Keep usage low: read only the files your step needs, and keep each report under 60 lines
  with the required fields. The orchestrator reads only each report's STATUS, TEST RESULTS,
  VALIDATION RESULTS and BLOCKED-ON lines unless a decision needs more, answers stop-hook
  messages in one line, and does not restate earlier reports.
- No reference material in this phase. The scripts are this repository's own, written from
  the work order alone. No GitHub Actions workflow: the scripts are the interface.
- Approval with layered stacks, as built in Phase 12: plan.sh writes the saved plan, its
  metadata (SHA-256, revision, dirty flag, env, stack) and a redacted summary; deploy.sh
  applies one stack only when docs/agent-runs/phase-NN/human-gate.md holds the matching
  APPROVED-COMMIT, APPROVED-ENVIRONMENT, COST-ESTIMATE-USD, BUDGET-REMAINING-USD and
  APPROVED-PLAN-SHA256 lines, the tree is clean and the plan's revision equals HEAD. Keep
  that design; no other path applies.
- No cloud: no AWS call, no plan, no apply, no deploy. Every cloud-dependent script is run
  only with its dry-run or argument check, which must name what it would do and refuse
  without approval.
- Order, one owner at a time:
  1. service-engineer: a runnable main for each of the three services (configuration from
     the environment, server start, health and readiness), so each image starts; and the
     wiring that lets queryService use the committed rag Bedrock synthesis adapter when
     `LLM_ENABLED` is true, with model ID and region from configuration. `LLM_ENABLED`
     stays false by default. Never edit rag or storage; if the build's main-class settings
     need a change, report BLOCKED-ON build-engineer. Test the wiring without binding a
     port. Run only the services projects' tests. Report as
     step-01-service-engineer-report.md.
  2. storage-engineer: command-line entry points the cloud pipeline can run: write the
     Neptune bulk-load files from the Spark export to S3 and start the Neptune bulk loader;
     run the DynamoDB and S3 Vectors loaders; enqueue the embedding jobs for the
     embedding worker. Each has a dry-run mode, reads names from configuration and is
     tested on fakes. Run only `sbt storage/test`. Report as
     step-02-storage-engineer-report.md.
  3. infra-engineer, task A1: finalize the six Phase 12 scripts and add check.sh,
     package.sh, run-experiment.sh and archive-evidence.sh, with the work order's
     automation rules (strict mode, `timeout` on every cloud call, retries only for safe
     idempotent actions, step markers such as `==> STEP name`, explicit exit codes,
     required environment variables listed by name, no secret or account ID printed).
     check.sh runs sbt only once. Also: make plan.sh's placeholder check ignore comment
     lines; give the ECS tasks a working, justified egress path to ECR, CloudWatch Logs and
     Bedrock in the app stack, the cheapest one that meets the security rules, and update
     the cost estimate if it adds hourly cost (the cloud window must stay at or under $15);
     have the Step Functions pipeline run the packaged Hadoop, entity-resolution and Spark
     jobs and step 2's loaders; and have run-bounded-pipeline.sh upload the selected local
     dataset snapshot to the raw prefix (dry-run only in this phase). Report as
     step-03-infra-engineer-report.md.
  4. infra-engineer, task A2: copy the working tree's tracked and new unignored files to a
     fresh directory outside the repository and run scripts/check.sh there, as the
     clean-checkout proof; save the output as
     docs/agent-runs/phase-13/output-check-clean.txt. Run scripts/package.sh once (jars,
     checksums, and the three images one at a time; if no Docker daemon is available,
     report the image step as NOT RUN) and save its output as output-package.txt. Run the
     dry-run or argument check of deploy, smoke test, bounded pipeline, experiment, archive
     and cleanup, and save those outputs. Report the command lines, required environment
     variables, human approval points, generated evidence and rollback behaviour as
     agent-report.md.
  5. documentation-engineer, on the low tier: STATUS.md once for the phase. The README's
     reproduction section is written in Phase 17 from the final scripts. Report as
     step-04-documentation-engineer-report.md.
  The orchestrator records every step in run-log.md.
- Then one fresh code-reviewer review (review-report.md). The reviewer relies on bash -n,
  shellcheck, the security scan, check.sh's output and the dry-run outputs for what they
  cover, and reads by hand only: the approval checks, destructive and cleanup paths, secret
  and account-ID handling and the dirty-tree refusal in the scripts; the egress change; the
  new service mains and LLM wiring; and the new storage entry points. One repair round fixes
  CRITICAL and MAJOR findings only; everything else is carried forward to the human. Run a
  fresh re-check (review-report-02.md) only if the review had a CRITICAL or MAJOR finding.
  There is no second repair round.
- Formatting: every agent formats only the files it owns, never `scalafmtAll`.
- The account ID, access keys, session tokens and secret values never appear in any file,
  report or log.
- Machine limits (8 GB laptop, 5 GB for WSL): one sbt, terraform or docker build at a time,
  no background shells, no polling or wait loops of any kind; every command runs in the
  foreground under `timeout` (project tests `timeout 30m`, check.sh `timeout 45m`, each
  image build `timeout 20m`). If the harness moves a command to the background, do not
  start anything else and do not poll; wait for it.
- No agent commits, applies, deploys, runs cleanup.sh for real or creates cloud resources
  in this phase.
