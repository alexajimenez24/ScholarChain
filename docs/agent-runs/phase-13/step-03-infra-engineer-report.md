HARNESS: claude-code
MODEL: opus, effort high (high tier, infra-engineer floor)
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 13 step 3, task A1: finalize the six Phase 12 scripts; add check.sh, package.sh, run-experiment.sh, archive-evidence.sh; ECS egress in the app stack; pipeline runs the jobs plus the step-2 loaders; snapshot upload (dry-run only)
FILES CREATED (/home/ajcreator/scholarchain/): scripts/{check,package,run-experiment,archive-evidence}.sh; infra/stacks/app/egress.tf
FILES MODIFIED: scripts/{plan,deploy,smoke-test,rollback,run-bounded-pipeline,cleanup}.sh, scripts/lib/common.sh, scripts/emr/scholarchain-step.sh; infra/stacks/app/{main,variables}.tf; infra/stacks/compute/{main,pipeline,variables}.tf; infra/stacks/foundation/{main,variables,outputs}.tf; infra/modules/{network,ecs-service,workload-roles,security-groups,interface-endpoints}/main.tf; infra/environments/dev/foundation.json; infra/README.md
COMMANDS RUN:
- terraform fmt -recursive and fmt -check (pass); terraform validate on all 5 stacks (5 of 5 valid); bash -n and shellcheck -x on every script, cleanup.sh included (inspection only); one guard block on `bash -n "$f"` in a loop, rerun with the paths written out
- dry runs: plan (exit 0), deploy (3), smoke-test (3), rollback x2 (3), pipeline start on 2026-11-24-25k (3), finish (0), missing --embed-units (2), archive (0), experiment with a missing or bad path (1); experiment dry run in a scratch git repo with a test manifest (3), plus an invalid-limit manifest (1)
- timeout 45m scripts/check.sh, once, in the foreground (the harness moved it to the background; I started nothing else and waited)
- NOT RUN: package.sh, cleanup.sh, trivy, any plan or apply, any AWS or aws-api call
TEST RESULTS: check.sh exit 0, RESULT PASS, 7 of 7 gates. sbt check: 14 project test runs, 1139 passed, 0 failed, 1 ignored (the gated AWS suite); markTestRun touched the marker. validate-agent-files OK (839 files with one writer); render --check OK (44); 9 JSON files parse; hooks: 6 shell and 3 Python files parse; 18 shell files pass bash -n, 16 pass shellcheck; terraform 5 of 5 valid.
VALIDATION RESULTS:
- Stateful replacements: none (no plan was run). Foundation drops one SSM parameter (network/nat-enabled) and its NAT resources (count 0, never applied).
- Egress: one zonal NAT, its EIP and the 0.0.0.0/0 route of the private route table moved to the app stack (egress.tf). They exist while park_services=false, and the services depend on the route. Why it is cheapest: $0.050/h + $0.045/GB, against 7 interface endpoints (ecr.api, ecr.dkr, logs, bedrock-runtime, s3vectors, sqs, cognito-idp) at $0.070/h in one AZ, and whether the Cognito JWKS URL works through an endpoint is UNVERIFIED. No new security group rule and no widening: the services keep their existing 443 egress.
- Pipeline: the state machine now runs Hadoop (with --openalex and --crossref), ER and Spark, then on the primary node: neptune-load (only with neptuneLoad), load-dynamo, then enqueue-embeddings (skipped when embedUnits="0"). EMR reaches SQS through a new SQS interface endpoint in compute, on the same window toggle as the EMR endpoint ($0.01/h), so EMR still has no 0.0.0.0/0 egress. The endpoint policy admits only the two project queues.
- Credentials on EMR: the step helper reads the instance profile through IMDSv2 and exports it to the CLI process only; it is never printed. I chose this over the aws CLI credential export, which the guard blocks.
- IAM: runtime-query gains s3:ListBucket on the graph bucket, limited to the prefix runs/*. emr-instance needs nothing new. No foundation, boundary or deployment-role IAM change.
- Container health check on GET /healthz (bash /dev/tcp). SERVING_RUN_MANIFEST_URI is set through plan.sh --serving-manifest; run-bounded-pipeline --finish prints it as ==> SERVING-MANIFEST.
- Scripts: every cloud-dependent script has --dry-run (no AWS call; exit 0 = the local checks pass, 3 = a real run would refuse, 2 = usage, 1 = a bad argument value). The plan.sh placeholder check now skips comment lines. The Phase 12 approval design is unchanged (deploy.sh check_plan has the same checks, plus digests in the ==> RELEASE lines). package.sh checks that the query-service jar holds exactly one provider entry, scholarchain.rag.SemanticRendererProvider; without Docker it reports NOT-RUN and exits 3.
- Cost: hourly cost rises by $0.01/h (SQS endpoint) and the loader steps add EMR time. Window typical $6.50 -> $6.85 (+$0.06 endpoint 6 h, +$0.29 loaders 0.5 h x 3 nodes); upper bound $12.56 -> $13.43 (+$0.10, +$0.77 at 1 h x 4 nodes). Both are under $15. Prices are reused from the Phase 12 price files, not re-read.
ASSUMPTIONS:
1. I read "in the app stack" literally, so the NAT moved there (foundation is unapplied, so no state moves). The deployment role already holds the tag-scoped NAT, EIP and route rights.
2. Loaders run on EMR, not ECS: ECS would need step-functions boundary changes, which are foundation IAM and must go to the human.
3. A snapshot's label is its folder name, used as snapshot_date=<label> (matching the manifest's raw_prefix). The Hadoop --snapshot value is the first 10 characters of the label.
4. --embed-units is required, with no default, because every unit costs money.
5. Experiments need a new gate line, APPROVED-EXPERIMENT-SHA256, and the manifest pins its entrypoint by entrypointSha256. Run output goes to target/experiments/ so the tree stays clean.
6. archive-evidence writes to target/evidence/ (git-ignored), not to S3.
RISKS:
- UNVERIFIED until a cloud run: IMDSv2 and curl/python3/java 17 on the EMR primary node; that command-runner steps run on the host (hop limit 1); bash in the corretto al2023 image for the health check; SQS private DNS; S3 put-object --if-none-match under the deployment role; state machine JSON with SSM values (validate cannot render it).
- A core IMDS credential provider (domain-engineer) would remove the helper's credential handling.
- Carried forward: LLM_ENABLED stays false because the prompt is not in the image (build-engineer resourceGenerator, step 1). Merged-jar provider discovery is proven only once package.sh runs (A2).
- check.sh in a copy without .git fails the git-based gates; A2 needs a real checkout or git init.
- With the services up but idle, NAT plus tasks cost about $0.13/h. Scaling to zero outside Terraform (pipeline --finish) leaves the NAT billing until the app plan runs with park_services=true; the ==> DRIFT line says so.
- docs/agent-runs/README.md does not yet document APPROVED-EXPERIMENT-SHA256 (factory-engineer). The deployment-iam comments still mention the foundation NAT toggle (comments only).
- State left: uncommitted changes only. Nothing applied, no plan file, no AWS call. target/ holds build and test output only.
MANUAL ACTION REQUIRED: none in this task (no plan, so there is no plan SHA-256 to sign). For Phase 14, sign the new window cost: typical $6.85, upper $13.43. For information: the aws-api MCP server is at end of development; migration guide https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: task A2 (infra-engineer): clean-copy check.sh, package.sh once, and the dry runs of deploy, smoke-test, pipeline, experiment, archive and cleanup.
