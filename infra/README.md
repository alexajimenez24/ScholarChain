# ScholarChain infrastructure (working notes)

Short notes for the Phase 12 tasks. The full infrastructure documentation is written in
Phase 17 from the final state.

## Layout and order

Five root stacks under `infra/stacks/`, each with its own state, planned and applied in
this order: `foundation`, `data`, `compute`, `app`, `observability`.
Reusable modules live in `infra/modules/`.
A stack never reads another stack's state file: each stack publishes what later stacks
need to SSM Parameter Store under `/scholarchain/<env>/<stack>/`, and later stacks read
those parameters with `data "aws_ssm_parameter"`.

## State

- S3 backend, S3-native locking (`use_lockfile = true`), no DynamoDB lock table.
- One key per stack and environment: `scholarchain/<env>/<stack>/terraform.tfstate`,
  passed as `-backend-config=key=...` (scripts/lib/common.sh `sc_tf_init`).
- The bucket name comes from `infra/backend/<env>.s3.tfbackend` (no secrets). The human
  creates the bucket once, outside Terraform, and replaces the placeholder.

## Inputs

- Non-secret values: `infra/environments/<env>/<stack>.json`, passed with
  `-var-file` (plain `.json` files; `*.tfvars` is git-ignored and guarded).
- Supplied at plan time, never written into a file: `TF_VAR_owner` (every stack),
  `TF_VAR_budget_alert_email` and `TF_VAR_state_bucket_name` (foundation).
- Passed by `scripts/plan.sh`: `revision` (git HEAD, every stack; published as
  `/scholarchain/<env>/<stack>/deploy/revision`, which deploy.sh verifies) and
  `image_tag` (app, the same SHA).
- Cloud-window toggles, off in the committed JSON files and turned on only by
  `scripts/plan.sh --window on`: `enable_neptune` (data), `enable_emr_endpoint`
  (compute: the EMR service and SQS interface endpoints). `--window off` plans them off
  again (Neptune then shows as STATEFUL-DESTROY).
- Egress of the ECS tasks (Phase 13): one zonal NAT gateway, its address and the private
  default route are app-stack resources (`infra/stacks/app/egress.tf`, with the cost
  comparison), created while `park_services = false`, so the app plan shows the path and
  parking the services removes it. Foundation no longer has a NAT toggle. The pipeline
  does not use the NAT: EMR reaches SQS through the compute stack's endpoint.
- `serving_run_manifest_uri` (app, query-service `SERVING_RUN_MANIFEST_URI`): passed by
  `scripts/plan.sh --serving-manifest`, printed by `run-bounded-pipeline.sh --finish`
  as `==> SERVING-MANIFEST`.
- `persistent = true` turns on deletion protection, PITR, Neptune final snapshots, longer
  backups, keeps ECR images and Cognito; dev keeps it false so cleanup works.

## Work-order areas to stacks

| Area | Stack | Where |
|---|---|---|
| 1 Foundation (providers, state, tags, budget) | every stack; budget in foundation | `versions.tf`, `modules/budget-guard` |
| 2 Network | foundation (EMR service endpoint: compute) | `modules/network`, `security-groups`, `interface-endpoints` |
| 3 Identity and encryption | foundation (deployment role, both boundaries, every workload role and its trust); data and app (the roles' inline policies only) | `modules/deployment-iam`, `workload-identities`, `workload-roles`, `stacks/app/main.tf` |
| 4 Data lake | data | `modules/data-lake` |
| 5 EMR | compute (roles in data) | `stacks/compute/pipeline.tf` (transient cluster per run) |
| 6 DynamoDB | data | `modules/dynamodb-tables` |
| 7 Neptune | data | `modules/neptune-serverless` |
| 8 S3 Vectors | data | `modules/s3-vectors` |
| 9 Optional OpenSearch | not deployed | no evaluated hot or hybrid tier uses it |
| 10 Runtime compute | compute (ECR, pushed before the app apply); app (ECS, Cloud Map, VPC Link, API, Cognito) | `stacks/app`, `modules/ecs-service` |
| 11 Workflow and messaging | compute | `stacks/compute` (Step Functions, SQS and DLQ; no EventBridge rule of our own) |
| 12 Observability | log groups with their producers (compute, app); dashboard and alarms in observability | `stacks/observability` |
| 13 Cost and safety | every stack | toggles, `persistent`, `infra/environments/`, `scripts/cleanup.sh` |

## Seven service roles, three images

query, vector retrieval, ranking, answer synthesis and verification run in one request
path inside `query-service` (rag); graph retrieval is `graph-service` (and in-process in
query-service for RAG context); workers are `embedding-worker`. The justification is in
`stacks/app/main.tf`.

## SSM inputs each stack waits on

- data: foundation `subnets/private`, `sg/neptune`,
  `iam/roles/<runtime-query|runtime-graph|runtime-worker|emr-service|emr-instance|step-functions|neptune-loader>-arn`,
  `iam/emr-instance-profile-{name,arn}`.
- compute: foundation `vpc/id`, `subnets/private`, `sg/emr-primary`, `sg/emr-core`,
  `sg/emr-service-access`; data `buckets/{artifacts,curated,graph,logs}`,
  `roles/{emr-service-arn,step-functions-arn,emr-instance-profile-name}`,
  `contract/embedding-queue-name`, `neptune/{enabled,endpoint,port,loader-role-arn}`.
- app: foundation `vpc/id`, `subnets/private`, `sg/{vpc-link,query-service,graph-service,embedding-worker}`,
  `iam/roles/ecs-execution-arn`; data `buckets/{raw,curated,graph,rag}`,
  `vectors/{bucket-name,dimension}`, `models/embedding-model-id`, `neptune/{endpoint,port}`,
  `roles/runtime-{query,graph,worker}-arn`, `dynamodb/<6 tables>`; compute
  `sqs/embedding-queue-url`, `ecr/<image>/{repository-url,name}`.
- observability: data `neptune/enabled`, `models/embedding-model-id`; compute
  `sqs/embedding-{queue,dlq}-name`, `pipeline/state-machine-arn`; app `api/id`,
  `ecs/cluster-name`, `ecs/service/<image>`.
- Scripts also read: compute `deploy/revision`, `emr/endpoint-enabled`,
  `sqs/endpoint-enabled`, `pipeline/*`; app `api/endpoint`, `api/id`,
  `deploy/image-tag`, `ecs/task-family/*`, `auth/*`. The app stack publishes
  `network/nat-enabled` and `query/serving-run`.

## Contracts for later stacks

- The embedding queue is named by `/scholarchain/<env>/data/contract/embedding-queue-name`;
  the worker, EMR and Step Functions grants use that name.
- Log groups live under `/scholarchain/<env>/`, alarms and dashboards are named
  `scholarchain-<env>-*`, ECR repositories `scholarchain-<env>-*`: the deployment role is
  scoped to these names.
- No later stack creates a role (repair round 2): every role, its trust policy, its
  boundary, the EMR managed policy attachment and the instance profile are foundation
  resources (`modules/workload-identities`); the data and app stacks only put inline
  policies on those exact role names.
- EMR clusters must carry the tags `project = scholarchain` and
  `for-use-with-amazon-emr-managed-policies = true` and use the EMR-managed security
  groups published by foundation.
- EMR node egress (repair M1): EMR adds only missing inbound rules to custom managed
  groups, never egress (Amazon EMR Management Guide, "Working with Amazon EMR-managed
  security groups", read 2026-11-26). Terraform therefore owns the EMR egress:
  all traffic between the primary and core groups, all TCP to the S3 prefix list, 443 to
  the DynamoDB prefix list and to the interface endpoints group (foundation,
  `modules/security-groups`), and 443 from both node groups to the EMR service endpoint
  group, which accepts 443 from both (compute). No EMR egress to `0.0.0.0/0`: the steps
  call only S3, Neptune and the EMR service.

## Deployment identity (repair M3)

The $20 budget action attaches its deny policy to the role `scholarchain-<env>-deployment`
only, so the paths that create billable resources must run as that role:
- `scripts/deploy.sh` for `data`, `compute`, `app` and `observability` refuses any other
  caller (`sc_require_deployment_role`, checked against `sts get-caller-identity`).
- `scripts/deploy.sh` for `foundation` reads `admin-changes` from the signed plan
  (`plan_summary.py --stack foundation`: every change to a type other than `aws_eip`,
  `aws_nat_gateway`, `aws_route`, `aws_vpc_endpoint`, the two security group rule types
  and `aws_ssm_parameter`, each printed as `==> ADMIN-CHANGE`): 0 (an interface endpoint
  toggle; the NAT is an app-stack resource since Phase 13) requires the role; more than
  0 (the bootstrap apply, any role, trust,
  boundary, IAM, budget or network-structure change) refuses the role and runs with the
  human's administrator credentials, which the budget action does not cover.
- Apply order is unchanged (foundation, data, compute, app, observability) and has no
  identity deadlock: the admin-applied foundation creates every role first; data and app
  then put only inline policies, which the role may do on the named roles. cleanup.sh
  runs entirely with the human's administrator credentials and destroys the inline
  policies (data, app) before the roles (foundation).
- `scripts/run-bounded-pipeline.sh` start requires the role; `--status` and `--finish`
  accept any caller. `--finish` terminates a leftover cluster only for a caller other
  than the deployment role (which holds no EMR write, repair round 3), so a leftover
  under the deployment role, a denied or failed terminate, or a cluster not TERMINATING
  afterwards prints `==> CLUSTER-STILL-ACTIVE <id>` and fails the run (exit 1, no
  `PIPELINE-FINISHED`) with the terminate command the human runs with administrator
  credentials; the ECS scale-down still runs first.
- `rollback.sh`, `smoke-test.sh` and `plan.sh` create nothing billable and accept any
  caller.

The human adds one AWS CLI profile (in `~/.aws/config`, never in the repository) after the
foundation bootstrap; the AWS CLI and the Terraform AWS provider both refresh its
credentials from the source profile:

```ini
[profile scholarchain-dev-deploy]
role_arn          = arn:aws:iam::<account-id>:role/scholarchain-dev-deployment
source_profile    = <your administrator profile>
role_session_name = deploy
duration_seconds  = 7200
region            = us-east-2
```

Then `AWS_PROFILE=scholarchain-dev-deploy scripts/deploy.sh ...`. The role may never
change its own policies (`scholarchain-<env>-deploy-*`), either boundary, the budget, the
budget deny policy or the budget action role, and never assumes another role (repair C1).

Plan file (repair round 2, n1): deploy.sh resolves `--plan` (or the default
`<plan-dir>/<stack>.tfplan`) to an absolute path before any use, refuses a missing file,
a directory or a symlink, and copies the plan and its `.meta` once into a private
`mktemp -d` directory (removed on exit). Only the copy is hashed, compared with the
signed `APPROVED-PLAN-SHA256`, inspected (`show -json`) and applied, and its SHA-256 is
checked against the signature again immediately before `terraform apply`. `sc_plan_dir`
makes any `SCHOLARCHAIN_PLAN_DIR` absolute, so plan.sh and cleanup.sh never hand
terraform `-chdir` a relative plan path.

## IAM scope (repair round 2, human decision 1)

Roles and trust: every workload role (`runtime-query`, `runtime-graph`,
`runtime-worker`, `ecs-execution`, `emr-service`, `emr-instance`, `step-functions`,
`neptune-loader`) is created by the admin-applied foundation. The deployment role
cannot set a trust policy by any route: no `iam:CreateRole`, `DeleteRole`,
`UpdateAssumeRolePolicy`, `PutRolePermissionsBoundary`, `AttachRolePolicy`,
`CreatePolicy`/`CreatePolicyVersion`/`SetDefaultPolicyVersion`, instance-profile or user
writes are allowed, and all are explicitly denied on `*` (`deploy-guards`). Its only IAM
writes are `iam:PutRolePolicy`/`DeleteRolePolicy` on seven named role ARNs (not
`emr-service`), with a Deny on every other role. `iam:CreateServiceLinkedRole` stays for
four named services (ECS, ECS Application Auto Scaling, RDS, API Gateway ops); AWS fixes
that role's trust to the service, so the caller sets none. `elasticmapreduce.amazonaws.com`
was removed in repair round 3: the deployment role starts no cluster, and the EMR cleanup
role is an admin-applied foundation resource (below).

PassRole (explicit ARNs, always with `iam:PassedToService`):
- deployment role: `runtime-query`, `runtime-graph`, `runtime-worker`, `ecs-execution` to
  `ecs-tasks.amazonaws.com`; `step-functions` to `states.amazonaws.com`; `neptune-loader`
  to `rds.amazonaws.com`; a Deny on passing any other role.
- `step-functions` (inline and the pipeline boundary): `emr-service` to
  `elasticmapreduce.amazonaws.com`, `emr-instance` to `ec2.amazonaws.com`; the boundary
  denies passing any other role.
- `emr-service` (inline and its boundary): `emr-instance` to `ec2.amazonaws.com`.
- every role under the workload boundary: none (explicit Deny of `iam:PassRole`).

EC2 launch only through the EMR service role: `emr-service` carries its own boundary
(`<name>-emr-service-boundary`: the AmazonEMRServicePolicy_v2 actions in this Region,
pass the instance role, and a Deny on new VPCs, gateways, NAT, addresses, endpoints and
security groups). `step-functions` carries `<name>-pipeline-boundary` (section "EMR
rights" below). Every other workload role carries `<name>-workload-boundary`, which
allows no EC2 action and denies RunInstances, CreateFleet, spot requests, scheduled
instances, StartInstances, launch template writes, ImportInstance and the Auto Scaling
group and launch configuration writes. The deployment role denies the same list plus
every EMR cluster write (below), `batch:CreateComputeEnvironment`,
`ecs:CreateCapacityProvider`, `ecs:RunTask` and `ecs:StartTask`. AmazonEMRServicePolicy_v2 tag requirements (managed policy page below):
subnets and security groups must carry `for-use-with-amazon-emr-managed-policies=true`
(the private subnets and the three EMR groups do), instances, volumes and launch
templates are created with it (the cluster `Tags` carry it). The VPC is not tagged, so
EMR cannot create its own security groups or endpoint there, which the boundary also
denies; whether emr-spark-8.0.0 needs `ModifyVpcEndpoint` on the VPC is UNVERIFIED.

Resource `"*"` statements that remain, each because the Service Authorization Reference
lists no resource type for the action (pages read 2026-11-26), or because it is a Deny:
| Policy, statement | Actions | Source |
|---|---|---|
| deploy-network DescribeNoResourceType | `ec2:Describe*` (Region condition) | https://docs.aws.amazon.com/service-authorization/latest/reference/list_ec2.html (DescribeVpcs: no resource type) |
| deploy-data ListNoResourceType | `s3vectors:ListVectorBuckets`, `dynamodb:ListTables`, `ssm:DescribeParameters`, `rds:DescribeDBEngineVersions`, `rds:DescribeOrderableDBInstanceOptions` | list_s3vectors.html, list_dynamodb.html, list_ssm.html, list_rds.html |
| deploy-runtime NoResourceType | `elasticmapreduce:{Create,Delete,Describe}SecurityConfiguration`, `ListClusters`, `GetBlockPublicAccessConfiguration`; `ecs:{Describe,Deregister}TaskDefinition`, `ListTaskDefinitions`; `application-autoscaling:Describe{ScalableTargets,ScalingPolicies,ScalingActivities}`; `ecr:GetAuthorizationToken`; `states:ValidateStateMachineDefinition`; `sqs:ListQueues` | list_emr.html, list_ecs.html, list_application-autoscaling.html, list_ecr.html, list_stepfunctions.html, list_sqs.html |
| deploy-edge CreateTaggedPoolAndNamespace | `cognito-idp:CreateUserPool`, `servicediscovery:CreatePrivateDnsNamespace`, `servicediscovery:TagResource` (request must carry the project and environment tags) | list_cognito-idp.html, list_servicediscovery.html |
| deploy-edge CreatePrivateZoneInProjectVpc | `route53:CreateHostedZone` (only with `route53:VPCs` = the project VPC) | list_route53.html; key format: https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/specifying-conditions-route53.html |
| deploy-edge NoResourceType, pipeline boundary and step-functions ExecutionLogDelivery | `logs:{Create,Get,Update,Delete}LogDelivery`, `ListLogDeliveries`, `PutResourcePolicy`, `DescribeResourcePolicies`, `DescribeLogGroups`; edge also `route53:ListHostedZonesByName`, `servicediscovery:List{Namespaces,Services,Operations,TagsForResource}`, `UntagResource` | list_logs.html (the `*LogDelivery` actions are not listed there; https://docs.aws.amazon.com/step-functions/latest/dg/cw-logs.html documents them on `*`), list_route53.html, list_servicediscovery.html |
| deploy-control Identity | `sts:GetCallerIdentity` | list_sts.html |
| workload boundary NoResourceType, app execution EcrToken | `ecr:GetAuthorizationToken` | list_ecr.html |
| pipeline boundary StartClusters, step-functions StartClusters | `elasticmapreduce:RunJobFlow` (request tag `project=scholarchain`) | list_emr.html |
| emr-service-boundary AmazonEMRServicePolicyV2Actions | the EC2, Application Auto Scaling, Resource Groups and CloudWatch alarm actions of the AWS managed policy (Region condition); RECORDED EXCEPTION accepted by the human, 2026-11-26 (section "Recorded exceptions") | https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AmazonEMRServicePolicy_v2.html (v6) |
| every Deny on `*` (guards, boundaries, budget deny policy) | denies; a narrower resource would weaken them | - |
Narrower than `*` but not a single named resource: hosted zones and Route 53 changes
(`hostedzone/*`, `change/*`: no account, Region or tag key; record changes limited to the
namespace names, VPC association to the project VPC), the Neptune data plane (account and
Region: the cluster resource ID is assigned at creation), scalable targets, Cognito pools,
Cloud Map namespaces and services, EC2 network resources and EMR clusters (generated IDs:
account and Region plus the project and environment tags where a tag key exists),
API Gateway (`/apis*`, `/vpclinks*`, `/tags/*` in this Region), Bedrock in-Region models
and profiles (foundation-model in other Regions only through an inference profile of
this account and Region).
AWS managed policies attached: `AmazonEMRServicePolicy_v2` on `emr-service` only (the
ECS execution role uses a scoped inline policy, not AmazonECSTaskExecutionRolePolicy).

Adjacent routes that remain (review-report-02, corrected in repair round 3 after
review-report-03 M1): re-trust is closed (no trust write by any route, roles
admin-applied). Running ECS as a project role remains by design: the deployment role
registers task definitions and creates or updates services that pass the three task roles
and the execution role, and puts their inline policies, so it can run code with at most
the workload boundary: project buckets, tables, vectors, queues, log streams, images,
Neptune in this account, Bedrock models and the project-path secret value. Through that
route the code gets no EC2 action, no IAM write, no `iam:PassRole`, no EMR action (no
RunJobFlow, add-step, terminate or any other cluster call) and no Step Functions action:
the workload boundary denies all of them explicitly, whatever inline policy a role is
given (until repair round 3 this boundary allowed RunJobFlow, the EMR role pass and
cluster control, so ECS code could start a cluster; that route is closed). The
deployment role can also define (`states:CreateStateMachine`, `UpdateStateMachine`) and
start the state machine, whose role is the only one that starts EMR (EC2 only through the
EMR service role); after $20 actual spend the budget deny blocks `states:StartExecution`,
`StartSyncExecution`, `ecs:CreateService`, `RunTask` and `StartTask`, but not
`ecs:UpdateService`, `RegisterTaskDefinition` or `UpdateStateMachine` (section "Recorded
exceptions").

## EMR rights (repair round 3, human decision 2026-11-26)

Only the step-functions role may create, run, change or terminate an EMR cluster or pass
the two EMR roles. Action list checked against the Service Authorization Reference,
https://docs.aws.amazon.com/service-authorization/latest/reference/list_emr.html (read
2026-11-26).
- `step-functions`: inline policy (data stack, `modules/workload-roles` "sfn") and its own
  boundary `<name>-pipeline-boundary` (foundation): RunJobFlow with the request tag;
  DescribeCluster, TerminateJobFlows, AddJobFlowSteps, DescribeStep, CancelSteps,
  ListSteps, PutManagedScalingPolicy, PutAutoTerminationPolicy, AddTags on clusters tagged
  `project=scholarchain`; the two EMR role passes; the three `.sync` EventBridge rules;
  log delivery; SendMessage to project queues. The boundary denies every launch action,
  every `states:*` action and every other PassRole, so the role cannot redefine or start
  a state machine, and an inline policy the deployment role puts on it cannot widen it.
- Workload boundary (`runtime-*`, `ecs-execution`, `emr-instance`, `neptune-loader`):
  explicit Deny of `elasticmapreduce:*`, `states:*` and `iam:PassRole`.
- Deployment role: read only (ListClusters, DescribeCluster, ListSteps, DescribeStep,
  ListInstanceGroups, GetBlockPublicAccessConfiguration) plus the security configuration
  actions the compute stack needs (`aws_emr_security_configuration`; a configuration is
  read only at RunJobFlow and changes no running cluster). `deploy-guards`
  NeverLaunchOrChangeCompute denies `RunJobFlow`, `Terminate*`, `Add*`, `Cancel*`,
  `Modify*`, `Put*`, `Remove*`, `Set*`, `Start*`, `Stop*`, `Update*`,
  `CreatePersistentAppUI` and `CreateStudio*` (every Write-level cluster action in the
  reference, including instance groups and fleets, scaling and auto-termination policies,
  termination protection, keep-alive, visibility, unhealthy-node replacement and tags).
  It may not edit the pipeline boundary (NeverTouchTheGuards) or any role's boundary.
- `emr-service` (assumed by EMR itself): no elasticmapreduce or states action. Its
  boundary now denies both explicitly; AmazonEMRServicePolicy_v2 (v6) holds none, so
  nothing AWS requires is removed. It acts on EC2 only for clusters EMR runs for it and
  cannot call the EMR API to start or change one.
- `emr-instance` (EC2 instance profile): no elasticmapreduce action. It had none in
  effect before (its inline policy grants none); the on-cluster helper
  `scripts/emr/scholarchain-step.sh` and the storage loaders it runs (Phase 13) call
  only S3, DynamoDB, SQS and Neptune, plus the node's own IMDSv2 for the instance
  profile credentials the storage CLI needs in its environment. UNVERIFIED until a run:
  whether an EMR 7 application on the nodes calls a describe action (AWS's legacy
  AmazonElasticMapReduceforEC2Role grants `elasticmapreduce:Describe*`/`List*`).
- Who may still define or start the pipeline: the deployment role (CreateStateMachine,
  UpdateStateMachine, StartExecution on `stateMachine:<name>-*`, needed for the compute
  apply and `run-bounded-pipeline.sh` start) and the human's administrator credentials.
  Only the deployment role and the step-functions trust (states, this account, source ARN
  `<name>-*`) can pass or use the pipeline role. Residual: IAM cannot bound EMR instance
  type or count (RunJobFlow has only tag condition keys), so the size of a cluster is
  bounded by the state machine definition, which only a signed compute plan changes.
- Who terminates a leftover cluster: the state machine's own terminate states (success
  and every failure path), the cluster's idle auto-termination
  (`emr_idle_timeout_seconds`), and the human with administrator credentials
  (`scripts/cleanup.sh` step 0, or `run-bounded-pipeline.sh --finish` run with them).
  Both scripts check the terminate call and the resulting state and fail loudly with
  `==> CLUSTER-STILL-ACTIVE`; cleanup.sh refuses `--execute` under the deployment role
  and fails if any STARTING, BOOTSTRAPPING, RUNNING or WAITING cluster remains at the end.

## EMR cleanup service-linked role (repair round 3, human decision 2026-11-26)

EMR creates `AWSServiceRoleForEMRCleanup` (service `elasticmapreduce.amazonaws.com`) at
cluster creation when it is missing, and the caller then needs
`iam:CreateServiceLinkedRole` (Amazon EMR Management Guide, "Using service-linked roles
for Amazon EMR for cleanup",
https://docs.aws.amazon.com/emr/latest/ManagementGuide/using-service-linked-roles-cleanup.html,
read 2026-11-26). The step-functions role does not have it, so the foundation stack can
create it: `aws_iam_service_linked_role.emr_cleanup` (`aws_service_name =
"elasticmapreduce.amazonaws.com"`; Terraform Registry, aws 6.74.0, read 2026-11-26),
behind `create_emr_service_linked_role` (default false).
- The one-line check (the human, administrator credentials, before the cloud window):
  `aws iam get-role --role-name AWSServiceRoleForEMRCleanup --query Role.Arn --output text`
  An ARN means the role exists: keep false (creating it again fails). `NoSuchEntity` means
  it is missing: set true.
- How to set it: change `"create_emr_service_linked_role": false` to `true` in
  `infra/environments/dev/foundation.json` and commit, then `scripts/plan.sh --env dev
  --stack foundation`. Use the committed file, not a one-off
  `TF_VAR_create_emr_service_linked_role=true`: any later foundation plan without it would
  plan the role's deletion.
- Apply identity: `aws_iam_*` types are not in plan_summary.py ROLE_APPLICABLE, so the plan
  shows it as `==> ADMIN-CHANGE` and `==> IAM-CHANGE`, and deploy.sh refuses the deployment
  role and runs it with the human's administrator credentials.
- The deployment role no longer needs `elasticmapreduce.amazonaws.com` in
  `iam:CreateServiceLinkedRole`, and it was removed.
- Cleanup: the foundation destroy deletes the role only when Terraform created it (true);
  step 0 has terminated the clusters first, which AWS requires before the deletion. EMR
  recreates it at the next cluster creation by a caller with the permission. UNVERIFIED
  until an apply: that IAM accepts the provider default tags on this service-linked role.

## Recorded exceptions (accepted by the human, 2026-11-26)

1. Resource `"*"` in `<name>-emr-service-boundary` statement AmazonEMRServicePolicyV2Actions
   (review-report-03 m1): kept unchanged. Effective rights are the managed policy AND
   this boundary, plus the named instance-role pass.
2. ECS service updates after the $20 action (review-report-03 M1, second case): the budget
   deny does not block `ecs:UpdateService` or `ecs:RegisterTaskDefinition` (denying
   UpdateService would also block scale-down), so an app apply can restart capped Fargate
   tasks after $20. Kept unchanged.

Sizes (how measured): no plan may run, so each document was hand-rendered as compact JSON
in a scratch Python script with worst-case values (8-character environment, 12-digit
account; IAM does not count whitespace). Repair round 3 re-rendered the workload boundary
(4,417) and the pipeline boundary (2,517) in full (single-element lists rendered as
strings, as aws_iam_policy_document does) and measured the changes to the others as
deltas: emr-service boundary about 1,690, deploy-runtime about 5,010, -control about
3,315, -guards 3,871 (3,605 + 266). Unchanged from round 2: deploy-network 2,329, -data
3,634, -edge 4,094. Limit 6,144 per managed policy. Synthesis inline 770 (role inline
total 10,240); largest trust (step-functions) 309 of 2,048. The deployment role holds 6
managed policies plus the budget deny when attached (default quota 10).

UNVERIFIED until a real apply (no plan was run): full action coverage of the tag-scoped
EC2, Cognito, Cloud Map and Route 53 statements (for example whether Cloud Map's hosted
zone calls satisfy the `route53:VPCs` and record-name conditions, and whether
`DisassociateAddress` on the NAT's AWS-managed interface is needed when the NAT toggles
off).

## Budget (repair round 2, human decision 2)

`modules/budget-guard`: `cost_types` with `include_credit = false` and
`include_refund = false`, so actual spend is measured before credits and refunds; every
other flag is set to its aws 6.74.0 default (tax, support, subscriptions, upfront,
recurring, other subscriptions and discounts included; unblended, not amortized)
(Terraform Registry, `aws_budgets_budget`, provider 6.74.0, read 2026-11-26). The $20
action (`aws_budgets_budget_action.deny_at_threshold`) names this budget, so it fires on
the same uncredited actual spend.

## Synthesis model (repair round 2, human decision 4)

`infra/environments/dev/app.json`: `synthesis_model_id =
us.anthropic.claude-haiku-4-5-20251001-v1:0`, the US geographic cross-Region inference
profile of Claude Haiku 4.5 (Bedrock User Guide, model card "Claude Haiku 4.5",
https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-haiku-4-5.html,
read 2026-11-26). It reaches the tasks as `SYNTHESIS_MODEL_ID` (core config
`models.synthesisModel`); nothing under `modules/` changed.
- us-east-2 has no In-Region bedrock-runtime access for this model; the bare model ID is
  not supported on demand. Only geo (`us.`) and global profiles work. The US profile
  from us-east-2 routes to us-east-1, us-east-2 or us-west-2, so prompts and replies may
  be processed in those three US Regions, never outside the US; the global profile
  (routes worldwide, 10% cheaper) was not chosen for that reason.
- Grant (`stacks/app/main.tf`): `bedrock:InvokeModel` on the profile ARN in us-east-2,
  and on the foundation model in the three destination Regions only with
  `bedrock:InferenceProfileArn` = that profile (Bedrock User Guide, "Geographic
  cross-Region inference", IAM policy requirements, read 2026-11-26).
- `llm_enabled` stays false: "set the synthesis model" chooses the model; turning the
  model path on is a separate decision, and the services have no model synthesizer wired
  yet (`modules/services/MODULE.md`), so `LLM_ENABLED=true` would be refused at startup.
  The grant is created only when `llm_enabled` is true.
- The rag adapter accepts the ID: `BedrockLlm` and `BedrockAdjudicator` accept any ID
  matching `(^|[./:])anthropic\.` and percent-encode it into
  `/model/{modelId}/invoke` on `bedrock-runtime.<region>` (read, not changed).
- Human prerequisites (Bedrock User Guide, "Access Amazon Bedrock foundation models",
  read 2026-11-26): submit the Anthropic first-time-use form once per account (required
  for Claude Haiku 4.5); the first invocation auto-enables the Marketplace product
  (`prod-xdkflymybwmvi`) only for a caller with `aws-marketplace:Subscribe`, which no
  project role has, so the human enables the model once with an administrator identity.
- Price (AWS price list, AmazonBedrockFoundationModels, us-east-2, publication
  2026-11-23, read 2026-11-26): $1.10 per million input and $5.50 per million output
  tokens for the US (regional) profile, against $1.00 and $5.00 global.

## Dirty tree and evidence (repair M2)

One shared test, `sc_dirty_entries` in `scripts/lib/common.sh`, is used by every script:
plan.sh marks a plan `dirty=true` with it; deploy.sh, rollback.sh and
run-bounded-pipeline.sh start refuse with it. It reads `git status --porcelain=v1 -z
--untracked-files=all` (staged and unstaged, every untracked file, both paths of a
rename) and exempts only evidence written after the approved commit:
- any change under `docs/agent-runs/phase-NN/` (the human's `human-gate.md` included),
  when every path of the entry is under such a folder;
- a new file under `releases/` (untracked or staged as added); a modified, deleted or
  renamed release record is dirty.
Everything else is dirty, and a failing `git status` counts as dirty. HEAD must still
equal `APPROVED-COMMIT`.

## Cleanup verification (repair M4)

`scripts/cleanup.sh` checks the exit status of every terraform init, `state list`, EMR
list and tagged-resource list and stops on any failure. An empty stack is accepted only
when `state list` exits 0 with no output or exits 1 with "No state file was found!"
(observed with Terraform 1.17.2, 2026-11-26). The run fails unless every tagged
resource left is an S3 bucket `scholarchain-<env>-*` (the data lake, kept) or an ECS
task definition revision; the data-lake buckets and the state bucket are still kept.

## Scripts

`scripts/check.sh` (every non-cloud gate, one sbt run), `package.sh` (jars, images,
checksums, the renderer-provider check), `plan.sh`, `deploy.sh`, `rollback.sh`,
`smoke-test.sh`, `run-bounded-pipeline.sh`, `run-experiment.sh`, `archive-evidence.sh`
and `cleanup.sh` (human only, not executable), with shared helpers in `scripts/lib/` and
the on-cluster step helper `scripts/emr/scholarchain-step.sh`.
Each script's header is its usage, its gate list and its exit codes. Every
cloud-dependent script takes `--dry-run` (no AWS call; exit 0 = the local checks pass,
3 = a real run would refuse). `run-experiment.sh` also needs an
`APPROVED-EXPERIMENT-SHA256: <manifest SHA-256>` line in the phase's `human-gate.md`.
