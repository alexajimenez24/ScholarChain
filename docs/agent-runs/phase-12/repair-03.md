# Phase 12 repair round 3 (final; a disclosed exception ordered by the human)

This is the third repair round of the phase. Under `docs/agent-runs/README.md` the loop stops after it: anything still open goes to the human.

## Decisions that started it (the human's message, verbatim)

> Human decisions on review 3: one small final fix (a disclosed exception). infra-engineer: (1) give EMR create, run and terminate rights only to the Step Functions pipeline role; no other workload or deployment role can start or change an EMR cluster. (2) Add the AWSServiceRoleForEMRCleanup service-linked role to the foundation stack behind a variable (create_emr_service_linked_role, default false), and document the one-line check the human runs to decide whether to set it. I accept the remaining Resource "*" in the EMR permission boundary as a recorded exception, and the residual risk that service updates can restart capped ECS tasks after the $20 action. Rerun fmt, validate and the security scan. A fresh code-reviewer checks only these two items and reruns the secret and account-ID scan with each path written out. Reports under 50 lines. No commits.

## Accepted by the human (recorded, not changed)

- The remaining `Resource "*"` in the EMR permission boundary.
- The residual risk that service updates can restart capped ECS tasks after the $20 budget action.

## Owner

infra-engineer, fresh context, opus, high.

## Repair brief

The two decisions, addressing M1 and M2 of `review-report-03.md`. The work order added: cover every policy, with a deny or boundary that holds even if an inline policy is added; close indirect routes through Step Functions; keep `--finish` and `cleanup.sh` consistent with who may terminate, so that a failed terminate never looks like success.

## Fix report (verbatim)

```text
HARNESS: claude-code
MODEL: opus, effort high (high tier, .agents/models.json)
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 12 repair round 3, the human's disclosed exception: (1) EMR create, run and terminate rights go only to step-functions; (2) an optional EMRCleanup service-linked role.
FILES CREATED: none in the repo (scratch only: /tmp/claude-1000/-home-ajcreator-scholarchain/4e119e35-2008-4755-bf02-f261880124ab/scratchpad/sizes.py, trivy.txt)
FILES MODIFIED: /home/ajcreator/scholarchain/infra/modules/deployment-iam/main.tf, infra/modules/workload-identities/main.tf, infra/stacks/foundation/main.tf, infra/stacks/foundation/variables.tf, infra/environments/dev/foundation.json, infra/README.md, scripts/run-bounded-pipeline.sh, scripts/cleanup.sh (Read and Edit only)
COMMANDS RUN: bash -n scripts/run-bounded-pipeline.sh (ok, twice); shellcheck -x -P SCRIPTDIR scripts/run-bounded-pipeline.sh (ok, twice); terraform fmt -check -recursive infra (rc 0); init -backend=false -lockfile=readonly and validate for foundation, data, compute, app and observability (all rc 0); trivy config with the pinned flags (rc 0); python3 -I sizes.py; a grep for 12-digit IDs and emails in 7 of the changed files (no match; cleanup.sh not scanned).
BLOCKED COMMAND (not retried in any form): my grep for line numbers named scripts/cleanup.sh among its files. Verbatim: "PreToolUse:Bash hook error: ["$CLAUDE_PROJECT_DIR"/.claude/hooks/guard-dangerous.sh]: Blocked by guard-dangerous hook: running scripts/cleanup.sh (human only). Agents never run this. If it is really needed, a human runs it manually; the approved paths are in docs/agents.md section 6."
TEST RESULTS: no sbt run (no Scala changed). Trivy found 25, the same as before. The 2 AWS-0342 findings only moved: deployment-iam/main.tf:425-483 (emr-service boundary) and :940-1049 (control).
VALIDATION RESULTS: no plan was run, so no stateful replacement, no add/change/destroy counts, no plan file and no SHA-256.
- Item 1, main.tf, workload boundary :278-283: NoEmrPipelineOrPassRole denies elasticmapreduce:*, states:* and iam:PassRole. It replaces PassEmrRoles, StartClusters, Clusters, SfnEmrRules and the two PassRole denies, and moves log delivery to the pipeline boundary.
- New "<name>-pipeline-boundary" (data.aws_iam_policy_document.pipeline_boundary, right after the boundary resource) for step-functions. It allows RunJobFlow with the request tag; cluster control on project-tagged clusters (also closes review m2 for this boundary); the two EMR role passes, each to its own service; the EMR sync rules; log delivery; SQS send. It denies launch actions, states:* and any other PassRole.
- emr-service boundary: adds the NoEmrApiOrPipeline deny (elasticmapreduce:* and states:*). AmazonEMRServicePolicy_v2 v6 has no such action (page fetched 2026-11-26), so nothing AWS needs is lost.
- emr-instance: had no effective EMR rights before and has none now. UNVERIFIED: whether an EMR 7 app on the nodes calls describe.
- Deployment role, deploy-runtime: ReadProjectClusters is now read only (TerminateJobFlows, AddTags and RemoveTags removed).
- Deployment role, deploy-guards: NeverLaunchOrChangeCompute denies RunJobFlow, Terminate*, Add*, Cancel*, Modify*, Put*, Remove*, Set*, Start*, Stop*, Update*, CreatePersistentAppUI and CreateStudio*. The list was checked against list_emr.html (2026-11-26). The security configuration actions are kept because they change no running cluster.
- Deployment role, NeverTouchTheGuards: now also names the pipeline boundary.
- Deployment role, CreateServiceLinkedRole: elasticmapreduce.amazonaws.com removed.
- workload-identities/main.tf permissions_boundary: a lookup gives step-functions the pipeline boundary. This is an in-place boundary update, not a replace.
- Indirect routes: PassRole of step-functions is the deployment role's alone (to states). Workload roles and step-functions are denied states:*.
- Leftover clusters, run-bounded-pipeline.sh --finish (leftovers step):
  - A failed list now dies; it used to read as "no cluster".
  - Lists STARTING/BOOTSTRAPPING/RUNNING/WAITING only, because --active also includes TERMINATING clusters.
  - Under the deployment role it never tries to terminate. Under any other caller it terminates, then checks the state is TERMINATING or TERMINATED.
  - Any cluster left prints "==> CLUSTER-STILL-ACTIVE" right away and again at the end, then exits 1 without PIPELINE-FINISHED, giving the human's terminate command. Services are still scaled down.
- Leftover clusters, cleanup.sh:
  - :81-83 refuses --execute under the deployment role.
  - Step 0 dies on a failed terminate or a state that is not TERMINATING/TERMINATED, before any destroy.
  - A new residual-emr step after the destroys fails if any cluster is still active.
  - The header now says only administrator credentials terminate.
- Item 2: foundation/main.tf aws_iam_service_linked_role.emr_cleanup with count on create_emr_service_linked_role (variables.tf, default false; foundation.json now sets it false explicitly).
- Service name elasticmapreduce.amazonaws.com and role AWSServiceRoleForEMRCleanup confirmed at https://docs.aws.amazon.com/emr/latest/ManagementGuide/using-service-linked-roles-cleanup.html (read 2026-11-26). Resource arguments confirmed in the Terraform Registry, aws 6.74.0.
- Admin-only: plan_summary.py needs no change, because aws_iam_* is not in ROLE_APPLICABLE, so the change shows as ADMIN-CHANGE and deploy.sh refuses the deployment role.
- README: new sections "EMR rights", "EMR cleanup service-linked role" (check, how to set it, cleanup behaviour) and "Recorded exceptions". The ECS-route reach text is corrected under "Adjacent routes"; the table rows and the --finish bullet are updated.
- Sizes (worst-case compact JSON from a scratch Python render, 8-character env, 12-digit account): workload boundary 4,417 and pipeline boundary 2,517 (both rendered in full). From deltas: guards 3,871, emr-service about 1,690, runtime about 5,010, control about 3,315. All under 6,144.
- High-cost resources: none added. The service-linked role is free.
ASSUMPTIONS: the step-functions .sync tasks need exactly the rights the old inline policy had (unchanged). TERMINATING counts as handled, since billing stops shortly after.
RISKS:
- No plan was run, so the real policy sizes and the effect of the boundary change are UNVERIFIED.
- Not checked: whether IAM accepts the provider default_tags on the service-linked role.
- The deployment role can still rewrite and (before $20) start the state machine. IAM cannot cap EMR instance type or count, because RunJobFlow has only tag condition keys.
- The budget deny does not cover UpdateStateMachine, UpdateService or RegisterTaskDefinition; the ECS part is the human's accepted exception.
- A leftover cluster seen under the deployment role needs the human with admin credentials; idle auto-termination is the backstop.
- shellcheck and bash -n were not run on cleanup.sh (the order forbids any shell command on it).
- The two accepted exceptions are recorded, not changed. Nothing was applied or committed.
MANUAL ACTION REQUIRED: none for this round: no plan, SHA-256 or cost to sign yet. Before the window the human runs `aws iam get-role --role-name AWSServiceRoleForEMRCleanup --query Role.Arn --output text` with admin credentials. If it returns NoSuchEntity, set true in foundation.json, commit, and plan foundation as admin. The human also runs bash -n and shellcheck on scripts/cleanup.sh. The aws-api MCP server is deprecated: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none (the one guard block is quoted above)
NEXT GATE: a fresh code-reviewer checks only items 1 and 2 and reruns the secret and account-ID scan with each path written out; then the human gate.
```

## Rerun evidence

The fresh review of the two items, with the secret and account-ID scan, is `review-report-04.md`.
