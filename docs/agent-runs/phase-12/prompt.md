```text
Use the infra-engineer agent to create the ScholarChain AWS infrastructure under infra/terraform and the approved operational scripts under scripts.

Required Terraform modules or equivalent organization:

1. Foundation
- Provider versions pinned.
- Remote state design documented.
- Required tags.
- Budget and cost-allocation tags where course accounts allow.

2. Network
- VPC in at least two Availability Zones.
- Public subnets only for approved ingress components.
- Private application and data subnets.
- Route tables, NAT or controlled egress, and VPC endpoints where justified.
- Security groups chained by service role rather than broad CIDR access.

3. Identity and encryption
- Least-privilege roles for EMR, ECS tasks, Step Functions, deployment execution, loaders, and runtime readers.
- Separate runtime and deployment permissions.
- KMS keys or approved AWS-managed encryption choices.
- Secrets Manager records referenced by ARN, never secret values in Terraform source or plans.

4. Data lake
- Raw, curated, graph, RAG, artifact, log, and report S3 buckets or prefixes.
- Versioning where needed.
- Encryption, public-access blocking, lifecycle rules, and deletion safeguards.
- Glue catalog resources when used.

5. EMR
- Hadoop and Spark applications.
- Managed scaling or instance-fleet policy.
- Log and bootstrap configuration.
- Bounded default capacity.
- Spot task capacity only when failure behavior is handled.

6. DynamoDB
- Required tables and indexes.
- On-demand or autoscaled provisioned capacity with justification.
- Point-in-time recovery where appropriate.

7. Neptune
- Cluster or serverless configuration.
- Subnet and parameter groups.
- Encryption, backups, deletion protection for non-ephemeral environments.
- Loader role and read-only runtime role.

8. S3 Vectors
- Vector bucket resources.
- Index resources for every required index.
- Encryption, tags, and access policies.
- Use current Terraform provider resources supported at implementation time and pin the provider version.

9. Optional OpenSearch
- Deploy only when used by an implemented and evaluated hot or hybrid tier.
- Define access, encryption, logging, and capacity policy.

10. Runtime compute
- ECR repositories with immutable Git SHA tags and scan-on-push.
- ECS cluster and Fargate task definitions.
- Services for query, graph retrieval, vector retrieval, ranking, answer synthesis, verification, and workers, or a justified consolidation that preserves independent scaling where needed.
- Internal service discovery and approved ingress.

11. Workflow and messaging
- Step Functions state machines.
- SQS queues and dead-letter queues.
- EventBridge rules when used.

12. Observability
- CloudWatch log groups.
- Dashboards and alarms.
- Metrics for EMR, ECS, SQS, Neptune, DynamoDB, S3 Vectors clients, API latency, LLM usage, and errors.

13. Cost and safety
- Bounded defaults.
- Environment-specific variable files without secrets.
- Deletion protection for stateful resources in persistent environments.
- Explicit cleanup mode for the course environment.

Required scripts:
- plan.sh
- deploy.sh
- rollback.sh
- smoke-test.sh
- run-bounded-pipeline.sh
- cleanup.sh

Script requirements:
- bash strict mode.
- No interactive prompts inside agent-run scripts.
- Refuse dirty source trees before deployment.
- Tag images by Git SHA.
- Print greppable step markers.
- Verify the deployed revision.
- Fail loudly with evidence.
- Never hide terraform plan output.
- Cleanup requires explicit human invocation and lists affected resources before deletion.

Run terraform fmt -check, terraform validate, a non-destructive plan, shell syntax checks, and security scans selected by the team.
Present the complete plan summary, add/change/destroy counts, estimated high-cost resources, stateful replacements, IAM changes, and required human actions. Do not apply.
```

Addendum from the human architect for this repository:

- Scope: the Phase 12 gate. The plan contains no unexplained destroy or replacement of
  stateful resources, IAM is scoped to actual resources and actions, no bucket or database
  is public, default capacity is bounded, no secret appears in source or plan output, I
  read and sign the plan, and code review approves. Nothing beyond that.
- Work without stopping to ask: take the most reasonable reading, record each choice and
  why in the step report, and carry on. Stop only for BLOCKED-ON, and route it as the rules
  say.
- Keep usage low: read only the files your step needs, and keep each report under 80 lines
  with the required fields. The orchestrator reads only each report's STATUS, TEST RESULTS,
  VALIDATION RESULTS and BLOCKED-ON lines unless a decision needs more, answers stop-hook
  messages in one line, and does not restate earlier reports.
- Reference material: ~/ref-phase-12/ holds the collaborative repository's Terraform,
  without its scripts. Read ~/ref-phase-12/REFERENCE.md first. This repository's layout is
  the layered stacks under infra/stacks/ plus infra/modules/ (record the mapping from the
  work order's 13 areas to the stacks), with API Gateway HTTP API, VPC Link, Cloud Map,
  Cognito, three images, one Step Functions pipeline and EMF-based dashboards. Record the
  justification for consolidating the work order's seven service roles into three images,
  and how each role maps to an image. No owner writes outside its territory.
- Cost bounds: $25 is the hard limit for the whole project, all phases and all cloud
  runs together, and it must not be exceeded. Billing data lags by hours, so the limit is
  enforced by estimates and short cloud windows, not by alarms alone:
  - Region us-east-2, from configuration. Before writing the data stack, infra-engineer
    confirms from current AWS documentation (URL and date) that S3 Vectors, Neptune
    Serverless and the configured Bedrock embedding and synthesis models are available
    there; if one is not, report BLOCKED-ON the human.
  - Budget: one AWS Budget of $25 for the project, with email alerts on actual spend at
    $5, $10, $15 and $20 and on forecast spend at $20, plus a budget action at $20 actual
    that attaches a deny policy to the deployment role, blocking creation of new billable
    resources. Every resource carries the course, project, environment, owner and
    expiration tags.
  - Hourly costs: two AZs for the VPC, as the work order requires, but no NAT gateway if
    gateway endpoints (S3, DynamoDB, both free) and the fewest interface endpoints cover
    the services, each interface endpoint in one AZ only; otherwise one NAT gateway.
    Justify every interface endpoint, because each one bills by the hour. Neptune
    Serverless with minimum 1 and maximum 2.5 NCU, one instance, loaded only by the bulk
    loader. EMR: a small primary and two small core nodes by default, managed scaling with
    a low maximum, auto-termination after 30 minutes idle. ECS: each service minimum 1,
    maximum 3. DynamoDB on-demand. API Gateway, Cognito and Step Functions bill per use.
  - AWS-managed encryption keys where the work order allows them, instead of customer KMS
    keys, unless a customer key is needed for a cross-service grant; justify each. At most
    one Secrets Manager secret and one dashboard.
  - In the dev environment, deletion protection is off on stateful resources so cleanup
    works; the variable for a persistent environment turns it on. cleanup.sh destroys every
    resource that bills by the hour or by the month (Neptune, NAT, interface endpoints, EMR,
    ECS, API Gateway, secret, dashboard, alarms) and keeps only the S3 evidence and the
    state bucket.
  - Cost estimate in agent-report.md: the hourly cost of each stack while it exists, and the
    estimated cost of the planned cloud window for Phases 14 and 15 (all stacks up for about
    6 hours, one bounded EMR pipeline run including Spark at 100,000 works, the Neptune bulk
    load, about 31,000 Bedrock embeddings, a short load test, then cleanup), with the price
    source URL and date for every figure. If the window estimate exceeds $15, which leaves a
    reserve for billing lag and mistakes, propose the cuts and report BLOCKED-ON the human
    instead of continuing.
- State: the S3 backend with S3-native locking (no lock table), one key per stack. The
  human creates the state bucket once, outside Terraform, before step 3's plan. The bucket
  name comes from a backend configuration file without secrets.
- Nothing is applied in this phase. No agent runs terraform apply or destroy, deploy.sh or
  cleanup.sh, and nothing touches AWS except a read-only plan.
- Order, one owner at a time:
  1. toolchain-engineer: pin terraform, the AWS CLI v2 and one infrastructure security
     scanner (trivy config scanning, or tfsec if trivy cannot be installed), each with its
     official checksum, in toolchain/versions.sh and the toolchain ledger. Report as
     step-01-toolchain-engineer-report.md.
  2. infra-engineer, task I1: the foundation and data stacks and their modules: pinned
     provider (a version with the S3 Vectors resources, recorded in the lock file), the
     remote state design, required tags, budget, VPC in two AZs, private subnets, endpoints,
     security groups by role, encryption, least-privilege roles (separate runtime and
     deployment roles, a loader role and a read-only runtime role for Neptune), buckets or
     prefixes for raw, curated, graph, RAG, artifact, log and report data, the DynamoDB
     tables from Phase 07, Neptune, the five S3 Vectors indexes, and the SSM parameters
     that later stacks read. Report as step-02-infra-engineer-report.md.
  3. infra-engineer, task I2: the compute, app and observability stacks (EMR with bounded
     default capacity and managed scaling, the Step Functions pipeline, SQS with DLQs, ECR
     with immutable tags and scan on push, ECS Fargate for the three images with their
     scaling policies, Cloud Map, VPC Link, API Gateway with the Cognito JWT authorizer,
     log groups, one dashboard and the alarms), the per-environment JSON variable files
     without secrets, and the six required scripts, written from the work order's script
     requirements alone. Then run terraform fmt -check and terraform validate on every
     stack, bash -n and shellcheck (if pinned) on every script, and the security scan.
     Then, only if the human has configured an AWS profile and created the state bucket,
     run a read-only terraform plan for the foundation stack and save it, with the account
     ID replaced by <account-id>, as docs/agent-runs/phase-12/output-plan-foundation.txt;
     report which SSM parameters each later stack waits on. Present the plan summary, add,
     change and destroy counts, the estimated high-cost resources with the documentation
     URL and date for each price, stateful replacements, IAM changes, and the human actions
     required. Report as agent-report.md.
  4. documentation-engineer, on the low tier: STATUS.md once for the phase. The
     infrastructure documentation (stack map, state layout, SSM contract, edge and auth
     design, cost bounds, cleanup procedure) is written in Phase 17 from the final state.
     Report as step-03-documentation-engineer-report.md.
  The orchestrator records every step in run-log.md.
- Then one fresh code-reviewer review (review-report.md). The reviewer relies on terraform
  fmt and validate, the security scan, bash -n, shellcheck and the plan summary for
  everything they cover, and reads by hand only: IAM policies and trust relationships,
  security groups, encryption settings, public access, the stateful resources (Neptune,
  DynamoDB, S3, S3 Vectors), default capacities, and in the six scripts the approval
  checks, destructive and cleanup paths, secret handling and the dirty-tree refusal. One
  repair round fixes CRITICAL and MAJOR findings only; everything else is carried forward
  to the human. Run a fresh re-check (review-report-02.md) only if the review had a
  CRITICAL or MAJOR finding. There is no second repair round. The human signs the plan
  before any apply.
- The account ID, access keys, session tokens and secret values never appear in any file,
  report or log; replace the account ID with <account-id> in saved output.
- Machine limits (8 GB laptop, 5 GB for WSL): one terraform or sbt process at a time, no
  background shells, no polling or wait loops of any kind; every command runs in the
  foreground under `timeout` (terraform init, validate and plan `timeout 15m`). If the
  harness moves a command to the background, do not start anything else and do not poll;
  wait for it.
- No agent commits, runs cleanup.sh or creates cloud resources in this phase.
