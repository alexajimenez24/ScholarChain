HARNESS: claude-code
MODEL: sonnet, effort medium (standard tier, .agents/models.json; session model claude-sonnet-5-5)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12 fresh read-only review of the uncommitted infra/, scripts/, toolchain/ and STATUS.md changes.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git status/diff/check-ignore, cat/grep reads; trivy config (pinned flags); terraform fmt -check; shellcheck on 7 shell files (not cleanup.sh); secret-pattern grep; curl of the public AWS page https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-man-sec-groups.html
TEST RESULTS: fmt rc 0; shellcheck rc 0; trivy 23 findings (matches the author). Secret grep (key IDs, 12-digit IDs, private keys; .terraform excluded) matched no file. No sbt run (no Scala change).
VALIDATION RESULTS: REQUEST_CHANGES (1 CRITICAL, 4 MAJOR). Owner of every finding: infra-engineer.
C1 CRITICAL, infra/modules/deployment-iam/main.tf:524-536 and 592-600: the deployment role can rewrite its own permissions.
- Input: iam:CreatePolicyVersion with set-as-default on policy/scholarchain-dev-deploy-control, new document Allow *:*.
- Why: the allow covers policy/<name>-*, which includes the five <name>-deploy-* policies (line 640). NeverTouchTheGuards lists only the role, boundary, budget-deny policy and budget-action role. The role has no boundary.
- Impact: account admin. It also removes its own denies (secret reads, budget, detaching the budget deny). The header claim "it may never modify itself" is false.
- Repair: deny every policy-mutating action (CreatePolicyVersion, SetDefaultPolicyVersion, DeletePolicy, DeletePolicyVersion, Tag/Untag) on policy/<name>-deploy-* as well; keep the other guard ARNs; recheck the managed-policy size.
M1 MAJOR, infra/modules/security-groups/main.tf:13-15 and 109-141; infra/stacks/compute/main.tf:213-232: EMR cannot work.
- Terraform strips default egress. EMR primary gets egress only to Neptune 8182 and 443 to service-access; core only to Neptune. Neither can reach S3, each other, DynamoDB or the NAT. Service-access allows 443 from primary only.
- The AWS page above (read in this review): EMR creates missing INBOUND rules; it does not add the egress 443 rule to custom managed groups; core needs egress to the S3 prefix list, core and primary; the 8.0 service endpoint must accept 443 from cluster instances. "EMR adds its own rules" does not cover this egress.
- Impact: the first run cannot fetch its bootstrap script from S3. The window is lost while nodes bill (CreateCluster limit 2700 s). validate and trivy cannot detect this.
- Repair: on primary and core, add egress to both EMR groups (all traffic), 443 to S3 (prefix list), 443 for DynamoDB, SQS and other needed services (prefix list or NAT), and 443 to service-access from core too; add the matching service-access ingress from core. Keep Neptune chaining and no CIDR ingress.
M2 MAJOR, scripts/lib/common.sh:117-124 (deploy.sh:54, run-bounded-pipeline.sh:141, rollback.sh:47): the approved path deadlocks.
- Input: commit X; plan; the human writes docs/agent-runs/phase-14/human-gate.md with APPROVED-COMMIT: X; run deploy.sh.
- That file is untracked and not ignored (git check-ignore rc 1), so the tree is "dirty" and deploy refuses. Committing it moves HEAD off X. releases/ records and --text-out under docs/ (the command in agent-report.md) dirty it too.
- Impact: nothing can be deployed, run or rolled back without bypassing a gate.
- Repair: one shared dirty test (plan.sh too) that exempts only evidence paths (docs/agent-runs/phase-NN/ and new files under releases/). All source, infra, scripts and docs elsewhere must still refuse; HEAD == APPROVED-COMMIT stays.
M3 MAJOR, infra/modules/budget-guard/main.tf:193-208; deployment-iam/main.tf:8-10; scripts/deploy.sh: the $20 budget action blocks nothing on the actual path.
- The header says deploy.sh assumes the deployment role. No script or provider block assumes it, requires it or checks it, and no instruction says to use it (only an admin profile is named).
- Input: spend reaches $20; deploy.sh or a pipeline start runs under the admin profile; both succeed.
- Repair (after C1): deploy.sh for data/compute/app/observability and pipeline start must refuse unless the caller is assumed-role/<name>-deployment (foundation stays admin); document the role profile; check the role's actions cover those paths (for example multipart upload of the jars needs abort/list-part rights, not granted).
M4 MAJOR, scripts/cleanup.sh:73-80, 95-96, 134-147: cleanup can fail silently.
- Input: terraform init or `state list` fails for data (expired token, backend error). state list errors go to /dev/null with `|| true`; the process substitution at line 80 drops any exit status.
- Result: "0 resources", "SKIP empty state", then "OK: cleanup done" while Neptune or the NAT keeps billing. A failed EMR list at line 95 likewise terminates nothing. The residual tag list prints but never fails the run.
- Repair: capture init, state list and EMR list with checked exit codes and sc_die on failure; accept only a genuine empty state; keep module.data_lake and state bucket exclusions. The human then runs bash -n and shellcheck on the file.
Repair must keep green: fmt, validate on 5 stacks, shellcheck, bash -n, trivy with no new unexplained finding.
MINOR, carried to the human (file paths under infra/ unless noted):
- m1 modules/deployment-iam/main.tf:210-239, 346-381, 431-478: EC2 writes, ECS delete/update, EMR terminate, apigateway:DELETE and Cognito delete on "*" (region only); Route 53 changes on "*" with no condition. Much could be tag- or ARN-scoped. Line 197 trusts account root without MFA. At 495-505 the role can re-trust a workload role, assume it and get boundary-level ec2:*, outside the budget deny. The human must accept or reject this against "scoped to actual resources".
- m2 modules/budget-guard/main.tf:75-100: no cost_types, so credits are netted by default (provider default, not re-verified). On a credit-funded account, alerts and the action may never fire.
- m3 stacks/compute/variables.tf:108-118: the 14400 s execution limit is below the summed step limits (24300 s). An execution timeout skips TerminateCluster, and idle termination does not end a running step, so the comment is wrong. Run --finish.
- m4 stacks/compute/main.tf:177-189: at-rest encryption covers S3 only, not EMR local disk or EBS.
- m5 modules/network/main.tf:10-12 says the NAT decision is in infra/README.md; it is not (it is only in the step-02 report).
- m6 modules/workload-roles/main.tf:315-323: EMR nodes can delete curated/reports/ evidence (versions last 30 days). cleanup.sh also removes the budget guard, does not wait for EMR termination and does not stop running executions.
- m7 scripts: deploy.sh leaves the ECR login in docker's config (no logout). run-bounded-pipeline.sh --finish prints "no active cluster" even if the list call failed. common.sh takes the first of duplicate cost lines.
Trivy, 23 findings: acceptable for dev. HTTPS-only egress fits the NAT design; AWS-managed keys, PITR off, unversioned expiring logs, no flow logs and no Container Insights fit the addendum. EMR in-transit TLS off is an acceptable risk (private, transient), for the human to note. None of them reveals C1 or M1.
Cost bounds (static): $25 annual budget, ACTUAL 5/10/15/20, FORECAST 20, action at 20 present (effect: M3, m2). One NAT, no default interface endpoints; EMR endpoint one AZ and justified; the NAT-versus-six-endpoints reasoning holds on the quoted prices. Neptune one instance, 1 to 2.5 NCU, off by default. EMR 1+2 m6g.xlarge, maximum 4, idle 1800 s. ECS 1 to 3. DynamoDB on demand. No secret, one dashboard. Required tags via default_tags and EMR tags (S3 Vectors tag propagation not checked). Cleanup keeps data-lake buckets and the state bucket and targets the hourly/monthly Terraform resources, subject to M4.
Dev stateful settings match the addendum: deletion protection, PITR and final snapshot off; persistent=true turns them on; S3 force_destroy false.
Gate:
- No unexplained stateful destroy or replace: CANNOT YET BE SHOWN (no plan).
- IAM scoped to actual resources and actions: NOT MET (C1; see m1).
- No bucket or database public: MET in source (S3 public-access blocks, vector bucket has no public grant, Neptune in private subnets with group-only ingress). Live state and the hand-made state bucket CANNOT YET BE SHOWN.
- Default capacity bounded: MET.
- No secret in source: MET (grep and reads). In plan output: CANNOT YET BE SHOWN (redaction and sensitive flags read only).
- Human reads and signs the plan: CANNOT YET BE SHOWN (no plan or SHA; M2 blocks use of a signature).
- Code review approves: NOT MET.
Without a plan the gate also cannot show add/change/destroy counts, the real IAM changes, managed-policy size limits, the EMR endpoint service name or provider acceptance of arguments.
Ownership: changed paths fit the reported owners; no version moved in build files. Tool pins are toolchain-engineer's.
ASSUMPTIONS: AWS's current EMR page describes the behavior for emr-spark-8.0.0. C1, M2 and M4 rest on code reading plus IAM, git and bash semantics; none was executed.
RISKS: Not reviewed or not rerun: terraform validate; bash -n (my loop form was guard-blocked and not retried); any syntax check of cleanup.sh; cost figures and price sources; tool checksums and signatures; observability alarm and dashboard behavior; STATUS.md beyond its diff; EMR 8 runtime compatibility. No plan, apply, AWS API call or operational script was run. The guard also blocked a find with a state-file pattern and a grep that named cleanup.sh with other files; neither was worked around (I read cleanup.sh with cat earlier and the Read tool). Disclosure against my no-file rule: curl saved the AWS page as docs/sg.html in the session scratchpad, outside the repository.
MANUAL ACTION REQUIRED: after repair, the human runs bash -n and shellcheck on cleanup.sh, decides m1 and m2, supplies the state bucket and signs the plans. Also tell the human that the aws-api MCP server is deprecated: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: one infra-engineer repair round for C1 and M1-M4, then a fresh re-check (review-report-02.md).
