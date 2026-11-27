HARNESS: claude-code
MODEL: sonnet, effort medium (standard tier, .agents/models.json)
VERDICT: APPROVE
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 13 re-check of MAJOR-1 only.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: cat, grep, sed, ls, stat, git diff and git status as evidence; `timeout 2m terraform fmt -check -recursive infra/modules/workload-roles` (exit 0).
TEST RESULTS: none run (no sbt, as ordered). terraform validate not rerun; the "Success!" result recorded in repair-01.md is relied on.
VALIDATION RESULTS: APPROVE. MAJOR-1 is fixed in source; no new finding.
- Statement: WriteLoadReports in data.aws_iam_policy_document.worker (infra/modules/workload-roles/main.tf) allows s3:PutObject and s3:GetObject on `${var.bucket_arns["curated"]}/logs/*`. The runtime-worker inline policy includes that document.
- Path matches: LoadStage.run writes the report to Zone.Logs. S3Layout maps that zone to curatedBucket, with key logs/load-vectors/run_id=.../load-report-<hash>.json. app/main.tf:111 sets CURATED_BUCKET from buckets/curated. That variable was not traced through config into storage.curatedBucket.
- Enough to acknowledge: the report write is a PUT with If-None-Match; only on 412 does it GET the object. It needs no list, delete or multipart call. The other calls on the load path are covered: rag Put (WriteSemanticContext) and Get (ReadSourceRows), vector Get/Put, run-ledger DynamoDB calls, and SQS receive and batch delete (sqs:DeleteMessage).
- Not wider than needed: two actions, one bucket, logs/* only. Using logs/load-vectors/* would be narrower, but logs/* is what the repair brief asked for, so it is not a finding.
- No boundary or trust change: the boundary's "Objects" statement (deployment-iam/main.tf) already allows both actions on `${var.name}-*/*`, and the buckets use the prefix `${var.name}-<zone>-`. git diff --stat shows no change in deployment-iam, workload-identities or data-lake.
- Other denies: the bucket policy lines read have a Deny only for non-TLS requests, and data-lake uses SSE-S3 (AES256), so no KMS grant is needed.
- Header comment: the new runtime-worker lines match the statement. The rest of this file's uncommitted diff is ListServedRuns, which the first review covered, plus the query and emr-instance comment text. Nothing else attributable to the repair was found. There is no pre-repair snapshot, so that attribution rests on repair-01.md and the first review.
- Nothing is left open on MAJOR-1 in source, so no second repair is needed.
ASSUMPTIONS: IAM behaviour is inferred from Terraform and Scala source and was not exercised in AWS.
RISKS: Not reviewed: anything outside MAJOR-1 and its repair, including MINOR-1 to MINOR-3 (the human's). The S3 gateway endpoint policy was not looked at; repair-01.md says it has none (network/main.tf:135). No data-stack plan exists, so "1 in-place policy change" is expected but unverified. The final sbt check has not been run.
MANUAL ACTION REQUIRED: The human reviews the worker IAM lines (docs/agents.md section 6). The data-stack plan SHA-256 and cost go into human-gate.md in Phase 14. Please also tell the user that the aws-api MCP server is reaching end of development; migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: final sbt check, then the human gate (including the three MINORs).
