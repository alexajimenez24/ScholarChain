HARNESS: claude-code
MODEL: opus, effort high (high tier, .agents/models.json; session model claude-opus-5-5)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 12, step 3, task I2: the compute, app and observability stacks, the per-environment JSON files, the six scripts, the checks and the cost estimate. No plan was run, because the precondition is not met (the backend file still holds the placeholder bucket name).
FILES CREATED (/home/ajcreator/scholarchain/...): infra/stacks/compute/{versions,variables,main,pipeline,outputs}.tf; infra/stacks/app/{versions,variables,main,edge,outputs}.tf; infra/stacks/observability/{versions,variables,main,outputs}.tf; a .terraform.lock.hcl in each of the three, copied from foundation (aws 6.74.0); infra/modules/ecs-service/main.tf; infra/environments/dev/{compute,app,observability}.json; scripts/{plan,deploy,rollback,smoke-test,run-bounded-pipeline,cleanup}.sh; scripts/lib/{common.sh,plan_summary.py}; scripts/emr/scholarchain-step.sh (a helper that runs on the cluster).
FILES MODIFIED (I1 fixes): infra/modules/deployment-iam/main.tf, which had three defects: the runtime policy was at about 6,277 characters, over the 6,144 IAM limit, so I split it into runtime plus a new edge policy (~5,148 and ~1,260); the Cognito user-pool-domain actions were missing; and the log-delivery and Route 53 VPC-association actions were missing. infra/stacks/{foundation,data}/{main,variables}.tf gain a `revision` variable, published as SSM deploy/revision. infra/README.md is updated. I deleted the .gitkeep files in stacks/{compute,app,observability}.
COMMANDS RUN (all foreground under timeout, after `source toolchain/env.sh`):
- terraform fmt -recursive (reformatted 4 files), then `terraform fmt -check -recursive infra/`: rc 0.
- On all 5 stacks: `init -backend=false -lockfile=readonly`: "Terraform has been successfully initialized!" x5. `validate`: "Success! The configuration is valid." x5.
- bash -n on 7 of the 8 shell files: ok. shellcheck 0.11.0 `-x` on the same 7: rc 0 (the first run found one SC2154, which I fixed).
- `trivy config --skip-check-update --disable-telemetry --skip-version-check --quiet infra/`: rc 0.
- I rendered the state machine definition with stub values in a scratchpad probe and checked the structure and intrinsics. I ran plan_summary.py on a synthetic plan: "==> PLAN-SUMMARY add=2 change=1 destroy=2 replace=1 stateful-destroy=2 iam-changes=1".
- curl of public price list CSVs (no account), us-east-2, Publication Date 2026-11-25. 17 small files were saved to the scratchpad; the 280 MB EC2 file was streamed through grep and never stored.
- Blocked by guard-dangerous and not worked around: `bash -n scripts/cleanup.sh` and `shellcheck scripts/cleanup.sh` ("running scripts/cleanup.sh (human only)"). Two other blocked forms were rewritten the approved way: a `bash -n "$f"` loop, and a cp whose sed named tfstate (that file went through the Write tool instead).
TEST RESULTS: no sbt source change, so no sbt run. The checks above are the evidence.
VALIDATION RESULTS:
- Stateful replacements: none known, because no plan exists. Possible later ones: `plan.sh --window off` destroys Neptune (dev, no final snapshot; plan_summary lists it first as STATEFUL-DESTROY); changing the embedding dimension replaces the S3 Vectors indexes; renaming an ECR repository or the user pool replaces it.
- Plan add/change/destroy: NOT AVAILABLE for every stack. No plan file and no SHA-256 exist.
- Trivy, 23 findings. The 13 from I1 are unchanged. The 10 new ones are deliberate and none is suppressed:
  - AWS-0017 LOW x5: log groups use the default encryption, no CMK.
  - AWS-0033 LOW x3: ECR uses AES256.
  - AWS-0034 LOW: Container Insights is off, because it bills custom metrics.
  - AWS-0138 HIGH: EMR has no in-transit TLS. It would need a certificate provider; the cluster is transient, private and IMDSv2-only.
- IAM changes:
  - foundation: a new managed policy scholarchain-dev-deploy-edge on the deployment role, with apigateway and cognito-idp moved out of runtime, plus the user-pool-domain, vended-log-delivery and route53 Associate/DisassociateVPCWithHostedZone actions.
  - app: a new role scholarchain-dev-ecs-execution. It is trusted by ecs-tasks with SourceAccount and SourceArn conditions and carries the boundary. It allows ecr:GetAuthorizationToken on *, pulls on the 3 repositories only, and CreateLogStream/PutLogEvents on the 3 log groups only.
  - app, optional and off: an inline synthesis-model policy on runtime-query, only when llm_enabled is true and a model ID is set.
  - compute: deny-non-TLS policies on both queues. No new roles.
- Area mapping:
  - 1: every stack, budget in foundation. 2: foundation; the EMR service endpoint is in compute. 3: foundation, data and app (execution role). 4, 6, 7, 8: data. 9: not deployed.
  - 5 and 11: compute. Each run creates one transient emr-spark-8.0.0 cluster from Step Functions. SQS has a DLQ. There is no EventBridge rule of our own.
  - 10: ECR is in compute, because deploy.sh must push the Git SHA images before the app apply. ECS, Cloud Map, VPC Link, the HTTP API and Cognito are in app.
  - 12: log groups sit with their producers, because the producers start before observability is applied. One dashboard and 12 alarms are in observability. 13: every stack.
- Role to image: query, vector retrieval, ranking, answer synthesis and verification go to query-service. Graph retrieval goes to graph-service (and runs in-process in query-service for RAG context). Workers go to embedding-worker. Why: those five run in one synchronous rag request path with no queue between them, so splitting them adds network hops, JWT checks and always-on tasks with no independent scaling need. Graph reads (Neptune-bound) and the worker (queue-bound) keep their own image and scaling (min 1, max 3, CPU target 60%). The full justification is in stacks/app/main.tf.
- EMR release: emr-spark-8.0.0. The documentation confirms 7.x needs no extra endpoint, but 7.x ships Spark 3.5 on Scala 2.12, which cannot run the Scala 3 Spark job (project/Versions.scala). 8.0.0 ships hadoop-mapred 3.4.2 and Spark 4.0.2 on Scala 2.13, and in private subnets it needs the endpoint aws.api.us-east-2.emr-service-cell01. I create that endpoint in Terraform, one AZ, behind the toggle `enable_emr_endpoint`, so it is visible to plans and cleanup. Source: https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark800-release.html (read 2026-11-26).
- EMR capacity: 1 primary plus 2 core m6g.xlarge, all on-demand. Managed scaling allows at most 3 core and task units, 4 instances in all. Idle auto-termination is 30 minutes. Spot is not used, because a lost node forces a step retry the bounded run has no budget for.
- Script gates:
  - deploy.sh applies only a saved plan, never with -auto-approve. It refuses a dirty tree. It requires exactly one APPROVED-COMMIT equal to HEAD and exactly one APPROVED-ENVIRONMENT. The plan SHA-256 must be in the APPROVED-PLAN-SHA256 lines, and COST-ESTIMATE-USD <= BUDGET-REMAINING-USD <= 25. The plan metadata must name this env and stack, revision=HEAD and dirty=false. It tags images by full Git SHA (immutable). It verifies the SSM deploy/revision, the ECS services' stability and image tag, and prints `==>` markers.
  - cleanup.sh lists by default. Executing needs --execute, SCHOLARCHAIN_CLEANUP_CONFIRM=destroy-<env>, a TTY, and no agent session markers; the file is not executable.
COST (us-east-2; every price from https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/<Offer>/current/us-east-2/index.csv, read 2026-11-26; Route 53 from the global file .../AmazonRoute53/current/index.csv):
| Item | Price (Offer) | Per hour while it exists |
|---|---|---|
| foundation: NAT + public IPv4 (window only) | $0.045/h + $0.045/GB (AmazonEC2); $0.005/h (AmazonVPC) | $0 off; $0.050 on |
| data: Neptune Serverless, 1 to 2.5 NCU (window only) | $0.1608/NCU-h (AmazonNeptune) | $0 off; $0.161 to $0.402 |
| compute: EMR service endpoint (window only) | $0.01/h (AmazonVPC) | $0 off; $0.010 on |
| compute: EMR, 3 to 4 m6g.xlarge, only during a run | $0.154 (AmazonEC2) + $0.039 (ElasticMapReduce) per node-hour | $0.579 to $0.772 |
| app: 3 to 9 Fargate tasks of 0.5 vCPU / 1 GB | $0.04048/vCPU-h, $0.004445/GB-h (AmazonECS) | $0.074 to $0.222 |
| app: hosted zone and Cloud Map | $0.50/zone-month (AmazonRoute53); $0.10/resource-month (AWSCloudMap) | ~$0.001 |
| observability: 12 alarms, ~44 alarm metrics | $0.10/alarm-metric-month (AmazonCloudWatch); dashboard price UNVERIFIED | ~$0.006 |
Per-use prices:
- Titan Embed v2 $0.00002/1K input tokens (AmazonBedrock).
- Candidate synthesis model Claude Haiku 4.5 Global, $1/M input and $5/M output (AmazonBedrockFoundationModels). This is a CANDIDATE: the model choice is an open human decision.
- HTTP API $1/M requests. Cognito M2M $0.00225 per token request; the app client is $0.
- SQS $0.40/M. Step Functions $0.000025 per transition. DynamoDB $0.625/M WRU.
- S3 Vectors $0.20/GB put and $0.0000025 per query. Logs ingestion $0.50/GB.
- UNVERIFIED (not extracted): S3 Standard requests and storage, EBS gp3.
Phase 14 and 15 window, typical: $6.37.
- Hourly items: NAT 6 h + 3 GB $0.44; Neptune 6 h at 1.25 NCU $1.21; endpoint $0.06; EMR 2 h x 3 nodes $1.16; Fargate 6 h + load test $0.52.
- Embeddings: 31,000 x 500 tokens = 15.5M tokens, $0.31.
- Haiku candidate: 200 queries x (4,000 input + 500 output tokens), $1.30.
- Smaller items: DynamoDB $0.13, SQS $0.04, S3 Vectors $0.03, Logs and alarms $0.29, hosted zone $0.50, Cognito $0.11, other $0.07, UNVERIFIED S3/EBS allowance $0.20.
Upper bound: $12.30. That assumes Neptune at 2.5 NCU, EMR 4 nodes for 3 h, 9 tasks, 1,000 tokens per document, double LLM use and 10 GB through the NAT. Both are under $15, so there is no BLOCKED-ON.
If things are left running: idle at window minimums costs $0.301/h, so the $18.63 left after a typical window lasts about 62 h (2.6 days); the full $25 lasts about 83 h. Fully scaled it costs $0.69/h, so the remainder lasts about 27 h. With only the app left up it costs $0.08/h, about 9.7 days. After cleanup only S3 storage remains, a few cents a month. The window toggles default off.
ASSUMPTIONS:
- Window toggles: `scripts/plan.sh --window on` adds -var enable_nat=true (foundation), enable_neptune=true (data) and enable_emr_endpoint=true (compute). The committed JSON files stay off.
- The app plan refuses to run while the SSM nat-enabled flag is false (override with --allow-no-egress), and warns when Neptune is off. run-bounded-pipeline.sh refuses to start unless the NAT, the EMR endpoint and, for a load, Neptune are on.
- There is no polling on the laptop: the pipeline script has separate start, --status <label> and --finish <label> modes. Finish archives the execution history under target/, terminates any leftover cluster of the run, and scales ECS to 0 with a printed DRIFT note. The Neptune load waits on the cluster, bounded to 3,600 s.
- Smoke tests take the token from SCHOLARCHAIN_SMOKE_TOKEN, passed to curl on stdin. No script reads the Cognito client secret, which exists only in Cognito and, marked sensitive, in the encrypted state.
- Alarms have no actions (no email in files); alarm_action_arns is optional. Plans live outside the repository in ~/.scholarchain/plans/<env>/, so the tree stays clean.
RISKS:
- Not done: no plan, no AWS account API call, no apply, no commit.
- Phase 14 gaps, outside my territory:
  - The deployables have no main class (service-engineer).
  - There is no entry point that produces the Neptune bulk-load CSV, nor one that enqueues the 31,000 embedding messages (storage-engineer, build-engineer). The pipeline therefore takes an explicit --neptune-subpath and has no enqueue stage.
  - A 100,000-work DBLP sample is needed (dataset-engineer).
- Unverified until a plan or apply:
  - The endpoint service name and private DNS of emr-spark-8.0.0.
  - Whether EMR 8 (no EMRFS) accepts the S3 SSE-S3 security configuration, and whether the s3a:// job paths work.
  - That Applications=[Spark] still installs MapReduce.
  - That AWS CLI neptunedata is present on the cluster nodes.
  - The policy sizes (my estimates), the Lite user-pool tier, and Metrics Insights alarm behavior.
- The images build only for x86_64, because the local docker build makes that platform. There is no container health check, because the image has no curl.
- rollback.sh and the pipeline's --finish scale-down cause drift that the next app deploy or a park_services plan settles. Task definition revisions are kept (skip_destroy) so rollback can use them.
- cleanup.sh was not syntax-checked by me, because the guard blocks it.
- Left behind: .terraform/ directories (git-ignored), and the scratchpad probe, price files and schema dump.
MANUAL ACTION REQUIRED:
- Create the state bucket (versioning on, SSE, public access blocked). Replace `<state-bucket-name>` in infra/backend/dev.s3.tfbackend and disclose that edit in human-gate.md.
- Then run, from /home/ajcreator/scholarchain:
  `source toolchain/env.sh; export AWS_PROFILE=<admin profile> TF_VAR_owner=<tag> TF_VAR_budget_alert_email=<addr> TF_VAR_state_bucket_name=<bucket>; scripts/plan.sh --env dev --stack foundation --text-out docs/agent-runs/phase-12/output-plan-foundation.txt`
  plan.sh replaces the account ID with <account-id> itself.
- Plan data, then compute, app and observability, each only after the one before it is applied (they wait on its SSM parameters; the list is in infra/README.md).
- Sign in human-gate.md: APPROVED-PLAN-SHA256 (one line per stack plan) and COST-ESTIMATE-USD. Typical $6.37 for the window; foundation alone with the window off costs $0/h.
- Run `bash -n scripts/cleanup.sh` and `shellcheck -x -P scripts scripts/cleanup.sh` yourself (the guard blocks agents).
- Decide the synthesis model ID. Activate the cost-allocation tags.
- Note: the aws-api MCP server is deprecated; migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the fresh code-reviewer review (review-report.md), then the human's foundation plan and its signature in human-gate.md before any apply.
