#!/usr/bin/env bash
# scripts/cleanup.sh: the course-environment cleanup. HUMAN ONLY.
#
# Usage:
#   bash scripts/cleanup.sh --env dev            # list mode (default): deletes nothing
#   SCHOLARCHAIN_CLEANUP_CONFIRM=destroy-dev bash scripts/cleanup.sh --env dev --execute
# (The file is deliberately not executable; agents cannot run it.)
#
# List mode prints every resource each stack's Terraform state holds, marks what is kept,
# and lists live project resources outside Terraform (active EMR clusters, anything
# tagged project=scholarchain environment=<env>). Run it first and read it.
#
# --execute destroys every resource that bills by the hour or by the month and keeps only
# the S3 evidence (the data-lake buckets) and the state bucket (never in any state):
#   0. terminates active EMR clusters of the environment (listed first) with the human's
#      administrator credentials (repair round 3: the deployment role and every workload
#      role except the step-functions role are denied every EMR write); a failed
#      terminate, or a cluster not TERMINATING or TERMINATED afterwards, stops the run
#      before any destroy, and an active cluster after the destroys fails the run,
#   (foundation also destroys AWSServiceRoleForEMRCleanup when it created it, that is
#    with create_emr_service_linked_role=true; EMR recreates it at the next cluster),
#   1. observability: full destroy (dashboard, alarms, SSM parameters),
#   2. app: full destroy (ECS services and cluster, Cloud Map and its hosted zone, VPC
#      Link, API Gateway, Cognito, log groups, the execution role's inline policy),
#   3. compute: full destroy (EMR endpoint, state machine, SQS queues, ECR repositories
#      with their images, security configuration, log group),
#   4. data: targeted destroy of everything EXCEPT module.data_lake (Neptune, DynamoDB
#      tables, S3 Vectors bucket and indexes, the workload roles' inline policies, SSM
#      parameters). The
#      buckets are not destroyed, so the "bucket not empty" stop of a full destroy never
#      happens; their evidence survives,
#   5. foundation: full destroy (NAT gateway and its address, interface endpoints, VPC,
#      security groups, deployment role, boundary, every workload role with its trust and
#      instance profile, budget and its action). Needs the human's administrator
#      credentials (the deployment role cannot change these). Run the whole cleanup with
#      those credentials: the order (inline policies in step 4 before the roles in step 5)
#      means no step waits on another identity.
# Each destroy is a saved destroy plan whose full output is printed before it is applied.
# Registered ECS task definition revisions stay (free); see the final ==> KEPT and
# ==> RESIDUAL lists. Every init, state list and AWS list call is checked: a failure
# stops the run, and the run fails if any tagged resource other than a data-lake bucket
# or a task definition revision remains (infra/README.md, "Cleanup verification").
#
# Explicit human invocation is required, and checked without any prompt:
#   --execute, SCHOLARCHAIN_CLEANUP_CONFIRM=destroy-<env>, an interactive terminal on stdin
#   and stdout, and no agent session marker (CLAUDECODE, SCHOLARCHAIN_ROLE, CODEX_*).
# Plan-time inputs: TF_VAR_owner for every stack; foundation also needs
# TF_VAR_budget_alert_email and TF_VAR_state_bucket_name (as for scripts/plan.sh).

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,6p' "$0"; exit 2; }

ENV="" EXECUTE=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    -h | --help) usage ;;
    *) sc_die "unknown argument: $1" ;;
  esac
done
[[ -n "$ENV" ]] || usage

sc_step preflight "env=$ENV execute=$EXECUTE"
sc_need_tools terraform aws git timeout sed awk grep
sc_check_env "$ENV"
if [[ "$EXECUTE" == true ]]; then
  [[ "${SCHOLARCHAIN_CLEANUP_CONFIRM:-}" == "destroy-$ENV" ]] || sc_die "set SCHOLARCHAIN_CLEANUP_CONFIRM=destroy-$ENV to confirm"
  [[ -t 0 && -t 1 ]] || sc_die "--execute needs an interactive terminal (a human runs it, not an agent)"
  if [[ -n "${CLAUDECODE:-}" || -n "${SCHOLARCHAIN_ROLE:-}" ]] || env | grep -q '^CODEX_'; then
    sc_die "an agent session marker is set; cleanup is run only by the human"
  fi
  for s in "${SC_STACKS[@]}"; do sc_require_tf_vars "$s"; done
fi

sc_step identity "caller identity"
sc_identity
REV="$(sc_head)"
if [[ "$EXECUTE" == true ]] && sc_is_deployment_role "$ENV"; then
  sc_die "--execute runs with the human's administrator credentials, not scholarchain-$ENV-deployment (it may not terminate clusters or destroy foundation)"
fi

# Resource addresses in a stack's state (data sources excluded). Repair M4: it prints
# nothing ONLY for a genuinely empty state (state list exits 0 with no output) or a stack
# that was never applied (exit 1 with "No state file was found!"). Any other failure of
# init or state list (expired credentials, backend or lock error) ends the run with the
# redacted error, so cleanup never reports success it could not verify. It runs inside a
# command substitution, where errexit is off, so every status is checked explicitly.
state_addresses() {
  local stack="$1" err out rc=0
  err="$(mktemp)" || sc_die "mktemp failed"
  if ! sc_tf_init "$ENV" "$stack" >/dev/null 2>"$err"; then
    sc_redact <"$err" >&2
    rm -f "$err"
    sc_die "terraform init failed for $stack (output above); nothing was verified or deleted"
  fi
  out="$(sc_tf "$stack" state list 2>"$err")" || rc=$?
  if [[ "$rc" != "0" ]]; then
    if [[ -z "$out" ]] && grep -q 'No state file was found' "$err"; then
      rm -f "$err"
      return 0
    fi
    sc_redact <"$err" >&2
    rm -f "$err"
    sc_die "terraform state list failed for $stack (exit $rc, output above); nothing was verified or deleted"
  fi
  rm -f "$err"
  if [[ -n "$out" ]]; then
    printf '%s\n' "$out" | grep -v -E '(^|\.)data\.' || true
  fi
}

sc_step list "resources in every stack (top-down), and what is kept"
declare -A ADDRS=()
for stack in observability app compute data foundation; do
  addrs="$(state_addresses "$stack")" || sc_die "could not list the $stack state"
  a=()
  if [[ -n "$addrs" ]]; then mapfile -t a < <(printf '%s\n' "$addrs"); fi
  ADDRS[$stack]="$addrs"
  printf '==> [%s] STACK %s: %s resources\n' "$SC_NAME" "$stack" "${#a[@]}"
  for addr in "${a[@]}"; do
    [[ -n "$addr" ]] || continue
    if [[ "$stack" == "data" && "$addr" == module.data_lake.* ]]; then
      printf '    KEEP     %s\n' "$addr"
    else
      printf '    DESTROY  %s\n' "$addr"
    fi
  done
done
printf '    KEEP     the Terraform state bucket (not managed by Terraform)\n'

sc_step list-live "live project resources outside Terraform"
# Repair M4: a failed list ends the run instead of reading as "no cluster", and any
# output that is not a cluster ID (or the empty-list "None") is an error too.
emr_out="$(sc_aws emr list-clusters --active \
  --query "Clusters[?starts_with(Name, 'scholarchain-$ENV-')].Id" --output text)" ||
  sc_die "could not list active EMR clusters; nothing was verified or deleted"
emr_ids=()
mapfile -t emr_lines < <(printf '%s\n' "$emr_out" | tr '\t' '\n')
for id in "${emr_lines[@]}"; do
  if [[ "$id" =~ ^j-[A-Z0-9]+$ ]]; then
    emr_ids+=("$id")
    printf '    TERMINATE EMR cluster %s\n' "$id"
  elif [[ -n "$id" && "$id" != "None" ]]; then
    sc_die "unexpected EMR list output: $id"
  fi
done
printf '    %s active EMR cluster(s)\n' "${#emr_ids[@]}"

# Tagged resources of the environment, one ARN per line (redacted later, when printed).
# A failed call ends the run.
tagged_arns() {
  local out
  out="$(sc_aws resourcegroupstaggingapi get-resources \
    --tag-filters "Key=project,Values=scholarchain" "Key=environment,Values=$ENV" \
    --query 'ResourceTagMappingList[].ResourceARN' --output text)" ||
    sc_die "could not list tagged resources; nothing was verified"
  printf '%s' "$out" | tr '\t' '\n' | grep -v -E '^(None)?$' || true
}
live="$(tagged_arns)" || sc_die "could not list tagged resources"
if [[ -n "$live" ]]; then printf '%s\n' "$live" | sc_redact | sed 's/^/    TAGGED   /'; fi

if [[ "$EXECUTE" != true ]]; then
  sc_ok "list mode: nothing was deleted. A human runs --execute (see the header)."
  exit 0
fi

# destroy <stack> [targets...]: saved destroy plan, printed in full, then applied.
destroy() {
  local stack="$1"
  shift
  local plan
  plan="$(sc_plan_dir "$ENV")/$stack.destroy.tfplan"
  local -a vars targets=()
  mapfile -t vars < <(sc_tf_plan_vars "$ENV" "$stack" "$REV")
  local t
  for t in "$@"; do targets+=("-target=$t"); done
  sc_step "destroy-$stack" "destroy plan (full output follows)"
  sc_tf "$stack" plan -destroy -input=false -no-color -lock-timeout=60s "${vars[@]}" "${targets[@]}" "-out=$plan" 2>&1 | sc_redact
  SC_TF_TIMEOUT=90m sc_tf "$stack" apply -input=false -no-color -lock-timeout=60s "$plan" 2>&1 | sc_redact
  rm -f "$plan"
  sc_ok "$stack destroyed${1:+ (targeted)}"
}

sc_step emr "terminate active EMR clusters of $ENV"
for id in "${emr_ids[@]}"; do
  if [[ "$id" =~ ^j-[A-Z0-9]+$ ]]; then
    if ! sc_aws emr terminate-clusters --cluster-ids "$id"; then
      printf '==> CLUSTER-STILL-ACTIVE %s (terminate failed or was denied; billing)\n' "$id" >&2
      sc_die "terminate of EMR cluster $id failed; nothing was destroyed. Check the caller (administrator credentials) and rerun"
    fi
    emr_state="$(sc_aws emr describe-cluster --cluster-id "$id" --query Cluster.Status.State --output text)" || emr_state="UNKNOWN"
    if [[ "$emr_state" != TERMINATING && "$emr_state" != TERMINATED ]]; then
      printf '==> CLUSTER-STILL-ACTIVE %s state=%s\n' "$id" "$emr_state" >&2
      sc_die "EMR cluster $id is $emr_state after the terminate request; nothing was destroyed"
    fi
    sc_ok "EMR cluster $id is $emr_state"
  fi
done

for stack in observability app compute; do
  if [[ -n "${ADDRS[$stack]//$'\n'/}" ]]; then destroy "$stack"; else sc_skip "$stack: empty state"; fi
done

mapfile -t data_targets < <(printf '%s\n' "${ADDRS[data]}" | grep -v -E '^module\.data_lake\.' | grep -v '^$' || true)
if [[ "${#data_targets[@]}" -gt 0 ]]; then destroy data "${data_targets[@]}"; else sc_skip "data: nothing to destroy"; fi

if [[ -n "${ADDRS[foundation]//$'\n'/}" ]]; then destroy foundation; else sc_skip "foundation: empty state"; fi

sc_step residual-emr "no active EMR cluster of $ENV remains"
# Not --active, which also lists TERMINATING (requested above, billing ends shortly).
emr_after="$(sc_aws emr list-clusters --cluster-states STARTING BOOTSTRAPPING RUNNING WAITING \
  --query "Clusters[?starts_with(Name, 'scholarchain-$ENV-')].Id" --output text)" ||
  sc_die "could not list active EMR clusters; cleanup is NOT verified"
mapfile -t emr_after_lines < <(printf '%s\n' "$emr_after" | tr '\t' '\n')
for id in "${emr_after_lines[@]}"; do
  if [[ -n "$id" && "$id" != "None" ]]; then
    printf '==> CLUSTER-STILL-ACTIVE %s\n' "$id" >&2
    sc_die "an EMR cluster of $ENV is still active after cleanup ($id); terminate it with administrator credentials"
  fi
done
sc_ok "no active EMR cluster of $ENV"

sc_step residual "tagged resources still present"
# Repair M4: the run succeeds only if every tagged resource left is one that is kept on
# purpose: an S3 bucket of the environment (the data lake, evidence) or a registered
# ECS task definition revision (free). Anything else is printed and fails the run.
left="$(tagged_arns)" || sc_die "could not list tagged resources; cleanup is NOT verified"
unexpected=0
if [[ -n "$left" ]]; then
  mapfile -t left_arns < <(printf '%s\n' "$left")
  for arn in "${left_arns[@]}"; do
    if [[ "$arn" == "arn:aws:s3:::scholarchain-$ENV-"* || "$arn" == *":task-definition/"* ]]; then
      printf '==> KEPT %s\n' "$(printf '%s' "$arn" | sc_redact)"
    else
      printf '==> RESIDUAL %s\n' "$(printf '%s' "$arn" | sc_redact)"
      unexpected=$((unexpected + 1))
    fi
  done
fi
if [[ "$unexpected" != "0" ]]; then
  sc_die "$unexpected tagged resource(s) remain (==> RESIDUAL above); the tagging index can lag a few minutes, so rerun list mode before acting"
fi
sc_ok "cleanup done; kept: data-lake buckets (evidence) and the state bucket"
