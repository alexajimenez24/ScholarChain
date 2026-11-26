# Phase 12 human gate

## How Phase 12 was produced

The owning agents did the work through the orchestrator, one owner at a time:
toolchain-engineer (pinned terraform, the AWS CLI v2 and the trivy scanner with checksums),
infra-engineer (I1: the foundation and data stacks; I2: the compute, app and observability
stacks, the per-environment JSON variable files, the six scripts and the validation runs),
documentation-engineer (STATUS.md), and fresh code-reviewers. The work order and my addendum
are in `prompt.md`; every step is in `run-log.md`, and every report is saved in this folder.
As in earlier phases, I gave the agents reference Terraform from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-12/`. Its layout
and edge design differ, so only resource settings and IAM ideas were taken from it.

Nothing was applied, and no agent called AWS. The only AWS calls in this phase were mine:
creating the state bucket and running the read-only foundation plan.

## What was checked

- Five layered stacks (foundation, data, compute, app, observability) with modules under
  `infra/modules/`; later stacks read earlier ones only through SSM parameters. The mapping
  from the work order's 13 areas to the stacks, and the justification for running the seven
  service roles as three images, are in the reports.
- terraform fmt and validate pass on all five stacks. bash -n and shellcheck pass on every
  script (I ran them myself on `cleanup.sh`, which agents were blocked from checking). trivy
  reports 25 findings; the reviewers accepted each one with a reason.
- Cost bounds: no NAT gateway and no interface endpoints in the foundation stack, Neptune
  Serverless at 1 to 2.5 NCU, small EMR with managed scaling and idle auto-termination, ECS
  services at 1 to 3 tasks, DynamoDB on demand, AWS-managed encryption keys, one secret and
  one dashboard. Deletion protection is off in dev so cleanup works; a persistent
  environment turns it on.
- Budget: one $25 AWS Budget for the whole project, tracking usage before credits and
  refunds, with email alerts at $5, $10, $15 and $20 actual and $20 forecast, and a budget
  action at $20 that denies the deployment role new billable resources.
- Cost estimate for the Phase 14 and 15 cloud window: $6.50, at most $12.56 (sources in
  `agent-report.md`; the S3, EBS and dashboard prices are marked unverified). That is under
  the $15 window limit and leaves a reserve within the $25 project limit.
- Synthesis model: Claude Haiku 4.5 through the inference profile
  `us.anthropic.claude-haiku-4-5-20251001-v1:0`, which may route requests to us-east-1,
  us-east-2 or us-west-2. The model path stays off (`llm_enabled=false`) until the services
  are wired to it.
- I ran my own secret search over `infra`, `scripts`, `toolchain`, this folder and
  STATUS.md. The only matches are report text describing the scan; no key or account ID
  appears.

## Reviews and repairs

- Review 1: 1 CRITICAL, 4 MAJOR. All five were fixed in the repair round, and review 2
  approved the source.
- I then authorized a further repair round, a disclosed exception: IAM scoped to actual
  resources with `iam:PassRole` limited to named project roles, no role-trust changes in the
  deployment role, the budget tracking usage before credits, `deploy.sh` applying only the
  plan whose checksum matches the signed one, and the synthesis model in configuration.
  Review 3 confirmed those and found 2 MAJOR findings.
- A final small round, also a disclosed exception, gave EMR create, run and terminate rights
  only to the Step Functions pipeline role, and added the `AWSServiceRoleForEMRCleanup`
  service-linked role to the foundation stack behind a variable. Review 4 confirmed the
  service-linked role and found 1 MAJOR and 3 MINOR findings.

## Decisions and accepted exceptions

- Review 4's MAJOR finding: the deployment role could rewrite or create a Step Functions
  pipeline that uses the pipeline role, and so start EMR indirectly. I accept it. Only I run
  `deploy.sh`, only with a plan I signed, so that route needs me to apply a plan that does
  it. The pipeline's EMR capacity is capped in Terraform, the $20 budget action blocks new
  starts, and after every cloud window I check for leftovers with
  `aws emr list-clusters --active --region us-east-2`.
- I accept the remaining `Resource "*"` in the EMR permission boundary: review 3 found it
  grants nothing beyond the AWS-managed EMR policy it bounds.
- I accept that a service update can restart ECS tasks after the $20 action. Tasks are
  capped at three per service, I run every deploy, and cleanup runs the same day.
- Gate item not met: "code reviewer approves the infrastructure diff" (review 4 returned
  REQUEST_CHANGES on the accepted EMR route). The other gate items are met for the
  foundation stack: no unexplained destroy or replacement, no public bucket or database,
  bounded default capacity and no secrets in source or plan output. IAM is scoped except
  for the exceptions above.

## Plan signature (foundation stack)

- Plan: `output-plan-foundation.txt`, made with `scripts/plan.sh --env dev --stack
  foundation` from the committed tree: 121 to add, 0 to change, 0 to destroy, no
  replacements.
- I read it: 10 IAM roles, 10 IAM policies and 7 attachments, the EMR instance profile and
  service-linked role, 9 security groups with rules by role, a VPC with 4 subnets, 2 route
  tables and an internet gateway, the free S3 and DynamoDB gateway endpoints, 33 SSM
  parameters, and the budget and its action. No NAT gateway, Elastic IP, interface endpoint
  or instance. The account ID is redacted.
- I approve this plan's content. It is not the file that will be applied: `deploy.sh`
  applies a plan only when the plan's revision equals HEAD, and committing this gate note
  moves HEAD. In Phase 14 each stack is planned again at the Phase 14 commit, and I sign that
  plan in `docs/agent-runs/phase-14/human-gate.md` with the machine-readable lines
  `deploy.sh` checks (`APPROVED-COMMIT`, `APPROVED-ENVIRONMENT`, `COST-ESTIMATE-USD`,
  `BUDGET-REMAINING-USD` and one `APPROVED-PLAN-SHA256` per stack), after comparing it with
  this one.
- Cost estimate: $6.50 for the cloud window, at most $12.56.
- The foundation plan contains IAM, budget and network-structure changes, so `deploy.sh`
  refuses to apply it as the deployment role; I apply it with administrator credentials, as
  `infra/README.md` describes. The data, compute, app and observability stacks are applied
  by deploy-engineer as the deployment role, so the $20 budget action covers them.
- The data, compute, app and observability stacks cannot be planned until the foundation
  exists, because they read its SSM parameters. Each is planned, read and signed by me in
  Phase 14 before its own apply.

## Accepted, with known gaps

Phase 12 is accepted as committed. Carried forward:
- Needed before Phase 14 (Phase 13): main classes for the three services; the job that
  produces the Neptune bulk-load files; queueing the embedding jobs; uploading the dataset
  samples to S3; wiring the services to Bedrock synthesis so `llm_enabled` can be turned on;
  and the egress path for ECS tasks to ECR, CloudWatch Logs and Bedrock, which the app-stack
  plan must show.
- `plan.sh`'s placeholder check matches any `<...>` in the backend file, comments included.
- The README overstates what limits EMR cluster size and which EMR actions are explicitly
  denied (review 4 minor).
- IAM policy sizes are not checked before apply; an oversized policy fails at apply, before
  any billable resource is created.
- The remaining minor findings in the four review reports (Phase 17 limitations).
- The aws-api MCP server is reaching end of development (factory-engineer).

## Process events

- During the repair, infra-engineer ran `awk` on `cleanup.sh` after the guard blocked
  `grep`, which works around the guard, and its first report did not say so. The guard
  blocked `awk` as well. It also ran one empty heredoc that did nothing.
- The guard blocked several harmless commands (a size calculation, searches naming
  `cleanup.sh`, a run-log update, the reviewer's secret search, a script-check loop); the
  agents reran them with each path written out or used the Write tool.
- The orchestrator ran on Sonnet 5.5 rather than the high tier, and some reports exceed the
  length limits I set.

## Manual changes

- I created the state bucket `scholarchain-tfstate-30a68cd5` with the AWS CLI, outside
  Terraform, as the work order's state design requires: versioning, default encryption, the
  public-access block and project tags.
- I edited `infra/backend/dev.s3.tfbackend`: I set the bucket name, removed the finished
  "replace the placeholder" note, and reworded the `<stack>` comment, because `plan.sh`'s
  placeholder check matched it.
- I set `create_emr_service_linked_role` to true in
  `infra/environments/dev/foundation.json`, because the role does not exist in the account.
- I ran the read-only foundation plan with `scripts/plan.sh`.

## Commit

Accepted commit: see `commit.txt`

Phase 12 is complete. I approve starting Phase 13.
