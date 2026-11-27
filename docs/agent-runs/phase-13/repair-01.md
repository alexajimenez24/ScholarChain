HARNESS: claude-code
MODEL: opus, effort high (high tier, from .agents/models.json)
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 13 repair round 1. Fix MAJOR-1 only: the embedding-worker role could not store its load report.
FILES CREATED: none
FILES MODIFIED: infra/modules/workload-roles/main.tf (this change is the new worker statement WriteLoadReports and two lines of the runtime-worker header comment. The file's larger `git diff --stat` count also includes the uncommitted step-03 changes.)
COMMANDS RUN:
- `timeout 2m terraform fmt -check -recursive modules/workload-roles stacks/data` (in infra/): exit 0
- `timeout 5m terraform validate -no-color` (in infra/stacks/data, already initialized; no init, no backend, no plan, no AWS call): "Success! The configuration is valid."
TEST RESULTS: none run. The Scala is unchanged, and sbt and check.sh were left out as the work order said.
VALIDATION RESULTS:
- Stateful replacements: none. No plan was run, but the change is one statement inside an existing aws_iam_role_policy.
- IAM change, the exact lines added to data.aws_iam_policy_document.worker (runtime-worker inline policy "least-privilege"):
    statement {
      sid       = "WriteLoadReports"
      effect    = "Allow"
      actions   = ["s3:PutObject", "s3:GetObject"]
      resources = ["${var.bucket_arns["curated"]}/logs/*"]
    }
- Why these two actions:
  - Loaders.scala:189 writes the report to Zone.Logs, which S3Layout.scala:32 maps to the curated bucket under `logs/`. The Terraform "logs" bucket is a different bucket and is not used.
  - S3ObjectStore.putIfAbsent does a PUT with If-None-Match. On a 412, ArtifactWriter.writeAt (S3Layout.scala:140-147) does a GET to compare hashes. So PutObject and GetObject are needed. No ListBucket, DeleteObject or multipart action is needed.
- Also-check items:
  - rag puts and gets: VectorLoader writes Zone.SemanticContext to the rag bucket. That is covered by the existing WriteSemanticContext and ReadSourceRows statements.
  - Run ledger: RunLedger and its failure records use only the run-ledger table. DynamoDbStore uses PutItem (conditional), GetItem and Query, all covered by the existing RunLedger statement.
  - S3 Vectors: GetVectors and PutVectors are covered by WriteVectors.
  - SQS: ReceiveMessage and DeleteMessageBatch need sqs:DeleteMessage, which is covered by DrainEmbeddingQueue.
  - KMS: not needed. The data-lake buckets use SSE-S3 (AES256, data-lake/main.tf:88).
  - No further gap belongs to this finding.
- Could anything still deny it?
  - Bucket policy: its only Deny is on aws:SecureTransport=false, and the worker uses https.
  - S3 gateway endpoint (network/main.tf:135): it has no policy, so the default full-access policy applies.
  - Permissions boundary: workload-boundary statement "Objects" already allows Get and Put on `scholarchain-*/*`, and the buckets are named with bucket_prefix "${var.name}-${zone}-". No boundary or trust change is needed.
- Stack affected: data (the only stack that calls modules/workload-roles). Plan add/change/destroy: not run (no-cloud rule). The expected result is 1 in-place change to aws_iam_role_policy.this["runtime-worker"].
- Cost: $0 (IAM only).
- Script gates: unchanged.
ASSUMPTIONS:
- Scope kept at `logs/*` as the brief says. The worker's only stage, VectorLoader.Stage = "load-vectors", writes `logs/load-vectors/run_id=...`, so `logs/load-vectors/*` would be narrower. It was not used because it would tie IAM to a code constant. The human may prefer it.
- The worker never GETs a missing key: it reads only after a 412. Because it has no ListBucket, a missing key would return 403, not 404, but that path is not used.
RISKS:
- Not exercised in AWS; no data-stack plan was saved.
- MINOR-1, MINOR-2 and MINOR-3 are untouched (they go to the human).
- No other file was changed. Nothing is committed.
MANUAL ACTION REQUIRED:
- Review the IAM lines above (docs/agents.md section 6, security change).
- The data-stack plan SHA-256 and the $0 cost go into human-gate.md once plan.sh has produced the plan in Phase 14.
- The aws-api MCP server has a deprecation notice; the migration guide is https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: a fresh code-reviewer re-check of MAJOR-1 (review-report-02.md), then the final sbt check.
