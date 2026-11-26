HARNESS: claude-code
MODEL: opus (high tier, per .agents/models.json; session model claude-opus-5-5)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 12, step 2, task I1: the foundation and data stacks and their modules (docs/agent-runs/phase-12/prompt.md plus the addendum).
FILES CREATED (all under /home/ajcreator/scholarchain/infra/):
- stacks/foundation/{versions,variables,main,outputs}.tf and .terraform.lock.hcl; stacks/data/{versions,variables,main,outputs}.tf and .terraform.lock.hcl
- modules/{network,security-groups,interface-endpoints,deployment-iam,budget-guard,ssm-parameters,data-lake,dynamodb-tables,neptune-serverless,s3-vectors,workload-roles}/main.tf
- backend/dev.s3.tfbackend (bucket = "<state-bucket-name>" placeholder), environments/dev/{foundation,data}.json, README.md (area map, state design, contracts)
FILES MODIFIED: deleted the placeholders infra/stacks/{foundation,data}/.gitkeep and infra/modules/.gitkeep. scripts/ is untouched.
COMMANDS RUN (all in the foreground under timeout, after `source toolchain/env.sh`):
- terraform init and plan in a scratchpad probe with no provider and no backend: Terraform 1.17.2 accepted `-var-file=probe.json` and printed `probe = "dev/false/0"`
- `terraform fmt -recursive`, then `terraform fmt -check -recursive infra/`: rc 0
- foundation and data: `terraform init -backend=false` ok (hashicorp/aws v6.74.0, signed); `terraform validate`: "Success! The configuration is valid." twice, the second time after the final edits
- `trivy config --skip-check-update --disable-telemetry --skip-version-check infra/` (two runs: 14 findings, then 13)
- A slip I reverted: `git rm --cached` staged the three .gitkeep deletions. `git reset -q --` on those paths unstaged them, and `git diff --cached` is empty.
TEST RESULTS: no sbt source change, so no sbt run. Checks were fmt, validate and trivy.
VALIDATION RESULTS:
- Stateful replacements: none (nothing is planned yet). Every aws_s3vectors_index argument forces replacement, so a later change to the dimension or key list is a stateful REPLACEMENT.
- Plan add/change/destroy counts, plan file and SHA-256: none, because this task runs no plan.
- Lock files: aws 6.74.0 (exact pin `= 6.74.0`), 17 hash lines each.
- Availability in us-east-2, read 2026-11-26:
  - S3 Vectors: row us-east-2, endpoint s3vectors.us-east-2.api.aws (https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-regions-quotas.html).
  - Neptune Serverless: us-east-2 is listed; engine 1.2.0.1 or later; min NCU can be 1 (https://docs.aws.amazon.com/neptune/latest/userguide/neptune-serverless.html). Engine 1.4.8.1 is active with end of life TBD (https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases.html).
  - Embedding model amazon.titan-embed-text-v2:0: In-Region supported in us-east-2 (https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-amazon-titan-text-embeddings-v2.html). The repository config takes it from EMBEDDING_MODEL_ID; docs/service-limits.md names it.
  - Synthesis model: none is configured. SYNTHESIS_MODEL_ID is empty and is an open human decision (STATUS.md item 5), so it cannot be BLOCKED-ON. One candidate, Claude Haiku 4.5, is Geo/Global only in us-east-2 (global.anthropic.claude-haiku-4-5-20251001-v1:0; https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-haiku-4-5.html).
- IAM changes (for human review):
  - Deployment role: trusted by the account root. It has 4 managed policies (network, data, runtime, control), scoped to the region and the `scholarchain-<env>-*` name prefix. It can create roles only with the workload boundary attached. Explicit denies stop it from changing itself, the boundary, the deny policy or the budget-action role, from changing the budget or its action, and from reading secret values.
  - Workload boundary: data-plane actions only, plus ec2:* and elasticmapreduce:* for the EMR managed policy. Network-creation actions are denied, secrets are allowed only under scholarchain/<env>/, and PassRole is allowed only for project roles passed to EMR or EC2.
  - Budget-action role: may attach or detach only the deny policy, only on the deployment role.
  - Roles in data: neptune-loader (graph bucket, read only), runtime-query, runtime-graph (the read-only Neptune runtime role), runtime-worker, emr-service (AmazonEMRServicePolicy_v2 plus PassRole on the instance role only), emr-instance plus its instance profile, and step-functions. Every one carries the boundary.
- High-cost resources (prices UNVERIFIED, because the doc tools cannot read aws.amazon.com price pages; I2 does the costed estimate):
  - foundation: $0/h with enable_nat=false. With NAT on: one NAT gateway (hourly plus per GB) and its public IPv4 address.
  - data: Neptune at 1 NCU minimum per hour, only while enable_neptune=true. Everything else bills per request or per GB-month.
- Trivy, 13 findings, all deliberate:
  - AWS-0104 CRITICAL x3: HTTPS egress to 0.0.0.0/0 on the three service security groups. Bedrock, S3 Vectors, SQS, ECR, Logs and the Cognito JWKS URL are public endpoints reached through the NAT.
  - AWS-0132 HIGH (S3) and AWS-0025 LOW (DynamoDB): no customer-managed KMS key. The addendum prefers AWS-managed keys, and no cross-service grant needs a CMK.
  - AWS-0024 MEDIUM x6: PITR is off in dev; persistent=true turns it on.
  - AWS-0090 MEDIUM: the logs bucket is unversioned (its objects expire).
  - AWS-0178 MEDIUM: no VPC flow logs (CloudWatch ingestion cost).
  - Fixed after the first scan: AWS-0345 (s3:* in the boundary). I also turned on versioning for the curated bucket, which holds the run reports. No finding was suppressed.
- Script gates: not applicable; scripts are task I2.
ASSUMPTIONS:
- Area mapping: 1 every stack, budget in foundation; 2 foundation; 3 foundation plus data (task execution role goes to app in I2); 4, 6, 7, 8 data; 9 not deployed (no evaluated hot or hybrid tier); 5 and 11 compute; 10 app; 12 observability; 13 every stack (toggles, `persistent`, environments/).
- Egress: one NAT in AZ 1 (toggle, default off) and zero interface endpoints. Covering the services without a NAT needs six or more endpoints (ecr.api, ecr.dkr, logs, bedrock-runtime, s3vectors, sqs, cognito-idp), and Cognito does not document the JWKS path through PrivateLink (domain endpoints are public only). The S3 and DynamoDB gateway endpoints are free. The interface-endpoints module, one AZ each, is there if wanted.
- Budget: COST, account-wide, ANNUALLY (calendar year), because $25 is a whole-course cap and a monthly budget would reset on Dec 1. Thresholds are as the addendum says.
- The deny policy blocks only create, run and start actions, so cleanup and scale-down still work.
- Owner, the alert email and the state bucket name are plan-time TF_VAR_* inputs, never in files. The email is `sensitive`.
- Backend key: `-backend-config=key=scholarchain/<env>/<stack>/terraform.tfstate`; scripts must pass it (I2).
- Buckets use bucket_prefix, so no account ID appears in any name. reports/ is a prefix of the curated bucket, matching the storage code.
- In dev, deletion protection, PITR and the Neptune final snapshot are off, and S3 Vectors force_destroy is on (addendum). S3 buckets always keep force_destroy=false.
- Neptune: engine 1.4.8.1, family neptune1.4, both parameter groups, IAM auth.
- SSM published: foundation 22 parameters, data 37. data waits on foundation/subnets/private, foundation/sg/neptune and foundation/iam/permissions-boundary-arn.
RISKS:
- Not done: compute, app, observability, scripts (I2). No plan, no AWS call, no commit.
- Not checked against AWS (only a plan or apply will show these): the 6,144-character managed-policy limit, the parameter-group family name, `aws:RequestTag` on RunJobFlow, and whether the deployment-role action list is complete.
- The deployment role cannot apply changes to its own guard resources. The bootstrap apply of foundation needs the human's admin credentials.
- A full destroy of the data stack stops at non-empty buckets, by design. cleanup.sh (I2) must target the other resources.
- EMR 8.x creates its own billable VPC endpoint; prefer 7.x in I2.
- The budget resets on Jan 1. The budget action can still be bypassed through admin credentials or ecs:UpdateService scale-ups.
- Left behind: .terraform/ directories (git-ignored), ~/.terraform.d/plugin-cache, and the scratchpad probe.
MANUAL ACTION REQUIRED:
- Create the state bucket and replace `<state-bucket-name>` in infra/backend/dev.s3.tfbackend.
- Supply TF_VAR_owner, TF_VAR_budget_alert_email and TF_VAR_state_bucket_name at plan time.
- Decide the synthesis model ID.
- Activate the cost-allocation tags in Billing once resources exist.
- No plan SHA-256 or cost to sign yet.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Phase 12 task I2 (infra-engineer), then the fresh code-reviewer review. The human signs the plans before any apply.

Note for the human: the aws-api MCP server is deprecated; migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
