#!/usr/bin/env bash
# scripts/deploy.sh: apply ONE saved, human-approved Terraform plan of one stack.
#
# Usage:
#   scripts/deploy.sh --env dev --stack <stack> --phase NN [--plan FILE]
#
# Run by deploy-engineer only after the human gate. It refuses to run unless ALL hold:
#   - the source tree is clean (no change outside the evidence paths below),
#   - docs/agent-runs/phase-NN/human-gate.md has exactly one APPROVED-COMMIT equal to
#     HEAD, exactly one APPROVED-ENVIRONMENT equal to --env, and COST-ESTIMATE-USD <=
#     BUDGET-REMAINING-USD <= 25,
#   - the SHA-256 of the plan it applies equals one APPROVED-PLAN-SHA256 line of that
#     file, checked when the plan is read AND again immediately before the apply,
#   - the plan's metadata (written by plan.sh) names this env and stack, revision ==
#     HEAD and dirty=false,
#   - the caller is the deployment role (assumed-role/scholarchain-<env>-deployment, so
#     the $20 budget deny action applies) for data, compute, app and observability, and
#     for a foundation plan with admin-changes=0 (NAT, interface endpoint, rule and SSM
#     toggles only); a foundation plan with admin-changes > 0 (bootstrap, any role, trust,
#     IAM, budget or network-structure change) must NOT run as that role: the human
#     applies it with administrator credentials (infra/README.md, "Deployment identity").
# Plan file (repair round 2, n1): --plan, or the default <plan-dir>/<stack>.tfplan, is
# resolved to an absolute path before any use (a relative path would be read from the
# stack directory by terraform -chdir), must be a regular file and not a symlink, and is
# copied once with its .meta into a private temporary directory (mode 0700, removed on
# exit). The copy is the ONLY file that is hashed, inspected and applied, so a file
# swapped at the original path after the check is never applied.
# "Clean" is the shared test of scripts/lib/common.sh: evidence under
# docs/agent-runs/phase-NN/ (human-gate.md included) and new files under releases/ are
# exempt; every other change, staged or not, refuses.
# It never plans, never edits a plan, never runs destroy and never takes -auto-approve:
# `terraform apply <saved plan>` applies exactly what the human signed.
#
# app stack only: before the apply it builds the three images (sbt dockerLocal), tags
# each with the full Git SHA (HEAD) and pushes it to its ECR repository (immutable tags;
# an existing tag is kept, never overwritten).
#
# After the apply it verifies the landed revision:
#   - every stack: SSM /scholarchain/<env>/<stack>/deploy/revision == HEAD,
#   - app: the three ECS services are stable and each runs an image tagged HEAD.
# The final ==> RELEASE lines are what deploy-engineer copies into releases/.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,8p' "$0"; exit 2; }

ENV="" STACK="" PHASE="" PLAN=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --stack) STACK="${2:-}"; shift 2 ;;
    --phase) PHASE="${2:-}"; shift 2 ;;
    --plan) PLAN="${2:-}"; shift 2 ;;
    -h | --help) usage ;;
    *) sc_die "unknown argument: $1" ;;
  esac
done
[[ -n "$ENV" && -n "$STACK" && -n "$PHASE" ]] || usage

sc_step preflight "env=$ENV stack=$STACK phase=$PHASE"
sc_need_tools terraform aws git sha256sum timeout sed awk mktemp cp chmod
sc_check_env "$ENV"
sc_check_stack "$STACK"
sc_check_phase "$PHASE"
PLAN_ARG="${PLAN:-$(sc_plan_dir "$ENV")/$STACK.tfplan}"
PLAN="$(sc_abs_file "$PLAN_ARG")" ||
  sc_die "saved plan not found, not a regular file, or a symlink: ${PLAN_ARG/#$HOME/~}; run scripts/plan.sh first"
META="$(sc_abs_file "$PLAN.meta")" ||
  sc_die "plan metadata not found, not a regular file, or a symlink: ${PLAN/#$HOME/~}.meta; run scripts/plan.sh first"

# The private copy that is hashed, inspected and applied (never the original path).
WORK="$(mktemp -d)" || sc_die "mktemp failed"
trap 'rm -rf -- "$WORK"' EXIT
APPLY_PLAN="$WORK/approved.tfplan"
APPLY_META="$WORK/approved.tfplan.meta"
cp -- "$PLAN" "$APPLY_PLAN"
cp -- "$META" "$APPLY_META"
chmod 0400 -- "$APPLY_PLAN" "$APPLY_META"
sc_info "plan ${PLAN/#$HOME/~} copied to a private directory; only the copy is checked and applied"

sc_step tree "refuse a dirty source tree"
sc_require_clean_tree
HEAD_SHA="$(sc_head)"

sc_step gate "read the human gate"
sc_read_gate "$PHASE"
sc_require_gate_matches "$ENV"

# Status 0 when $1 is one of the signed APPROVED-PLAN-SHA256 values.
plan_is_signed() {
  local s
  for s in "${GATE_SHAS[@]}"; do
    if [[ "$s" == "$1" ]]; then return 0; fi
  done
  return 1
}

sc_step plan-check "plan SHA-256 against APPROVED-PLAN-SHA256"
SHA="$(sha256sum -- "$APPLY_PLAN" | awk '{print $1}')"
[[ "$SHA" =~ ^[0-9a-f]{64}$ ]] || sc_die "could not hash the plan copy"
plan_is_signed "$SHA" || sc_die "plan SHA-256 $SHA is not an APPROVED-PLAN-SHA256 in phase-$PHASE/human-gate.md"
meta() { sed -n "s/^$1=//p" "$APPLY_META"; }
[[ "$(meta sha256)" == "$SHA" ]] || sc_die "plan file changed after plan.sh wrote its metadata"
[[ "$(meta env)" == "$ENV" && "$(meta stack)" == "$STACK" ]] || sc_die "plan metadata names env=$(meta env) stack=$(meta stack)"
[[ "$(meta revision)" == "$HEAD_SHA" ]] || sc_die "plan was made at $(meta revision), HEAD is $HEAD_SHA"
[[ "$(meta dirty)" == "false" ]] || sc_die "plan was made on a dirty tree"
sc_ok "plan $SHA approved for $ENV/$STACK at $HEAD_SHA"

sc_step identity "caller identity (the deployment role, so the budget deny action applies)"
sc_identity
if [[ "$STACK" != "foundation" ]]; then
  sc_require_deployment_role "$ENV"
fi

if [[ "$STACK" == "app" ]]; then
  sc_step images "build, tag by Git SHA and push the three images"
  sc_need_tools docker sbt
  (cd "$SC_ROOT" && timeout 45m sbt -batch dockerLocal)
  registry=""
  for img in query-service graph-service embedding-worker; do
    url="$(sc_ssm "/scholarchain/$ENV/compute/ecr/$img/repository-url")"
    repo="$(sc_ssm "/scholarchain/$ENV/compute/ecr/$img/name")"
    if [[ -z "$registry" ]]; then
      registry="${url%%/*}"
      # The login token goes from the AWS CLI straight into docker on a pipe; never printed.
      sc_aws ecr get-login-password | timeout 120s docker login --username AWS --password-stdin "$registry" >/dev/null
    fi
    if sc_aws ecr describe-images --repository-name "$repo" --image-ids "imageTag=$HEAD_SHA" >/dev/null 2>&1; then
      sc_skip "$img:$HEAD_SHA already in ECR (immutable tag kept)"
      continue
    fi
    timeout 60s docker tag "scholarchain-$img:local" "$url:$HEAD_SHA"
    timeout 30m docker push "$url:$HEAD_SHA" | sc_redact
    sc_ok "pushed $img:$HEAD_SHA"
  done
fi

sc_step init "terraform init (S3 backend)"
sc_tf_init "$ENV" "$STACK" | sc_redact

# foundation: the deployment role applies it only when the signed plan touches nothing
# but the NAT, interface endpoint, security group rule and SSM resources (admin-changes=0);
# any other change (roles and their trust, IAM, budget, network structure) is the human's,
# with administrator credentials, because the role may not make it.
if [[ "$STACK" == "foundation" ]]; then
  sc_step guard-check "changes the deployment role may not apply, in the signed foundation plan"
  sc_need_tools python3
  report="$(sc_tf foundation show -json "$APPLY_PLAN" | python3 -I "$SC_ROOT/scripts/lib/plan_summary.py" --stack foundation)"
  printf '%s\n' "$report" | grep -E '^==> (ADMIN-CHANGE|PLAN-SUMMARY) ' || true
  summary="$(printf '%s\n' "$report" | grep '^==> PLAN-SUMMARY ' || true)"
  admin="$(printf '%s' "$summary" | sed -n 's/.* admin-changes=\([0-9][0-9]*\).*/\1/p')"
  [[ "$admin" =~ ^[0-9]+$ ]] || sc_die "could not read admin-changes from the plan summary"
  if [[ "$admin" == "0" ]]; then
    sc_require_deployment_role "$ENV"
  elif sc_is_deployment_role "$ENV"; then
    sc_die "this foundation plan has $admin change(s) the deployment role may not apply (==> ADMIN-CHANGE above); the human applies it with administrator credentials"
  else
    sc_info "foundation plan has $admin admin change(s): applied with the human's administrator credentials (bootstrap, role, IAM, budget or network change); the budget deny action does not cover this caller"
  fi
fi

sc_step apply-check "re-verify the plan copy against the signed SHA-256 immediately before the apply"
NOW_SHA="$(sha256sum -- "$APPLY_PLAN" | awk '{print $1}')"
if [[ "$NOW_SHA" != "$SHA" ]] || ! plan_is_signed "$NOW_SHA"; then
  sc_die "the plan copy changed since it was checked (now $NOW_SHA); nothing was applied"
fi
sc_ok "applying exactly $NOW_SHA"

sc_step apply "terraform apply of the approved saved plan (full output follows)"
SC_TF_TIMEOUT=90m sc_tf "$STACK" apply -input=false -no-color -lock-timeout=60s "$APPLY_PLAN" 2>&1 | sc_redact

sc_step verify "landed revision"
landed="$(sc_ssm "/scholarchain/$ENV/$STACK/deploy/revision")"
[[ "$landed" == "$HEAD_SHA" ]] || sc_die "SSM deploy/revision is $landed, expected $HEAD_SHA"
sc_ok "SSM /scholarchain/$ENV/$STACK/deploy/revision == $HEAD_SHA"

if [[ "$STACK" == "app" ]]; then
  cluster="$(sc_ssm "/scholarchain/$ENV/app/ecs/cluster-name")"
  services=(query-service graph-service embedding-worker)
  SC_AWS_TIMEOUT=20m sc_aws ecs wait services-stable --cluster "$cluster" --services "${services[@]}" ||
    sc_die "ECS services did not become stable within 20 minutes (the circuit breaker may have rolled back)"
  for svc in "${services[@]}"; do
    td="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].taskDefinition' --output text)"
    image="$(sc_aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
    running="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].runningCount' --output text)"
    [[ "${image##*:}" == "$HEAD_SHA" ]] || sc_die "$svc runs image tag ${image##*:}, expected $HEAD_SHA"
    sc_ok "$svc runs :$HEAD_SHA with $running task(s), task definition $(printf '%s' "$td" | sc_redact)"
  done
fi

printf '==> RELEASE env=%s\n==> RELEASE stack=%s\n==> RELEASE commit=%s\n==> RELEASE plan-sha256=%s\n==> RELEASE phase=%s\n==> RELEASE applied-at=%s\n' \
  "$ENV" "$STACK" "$HEAD_SHA" "$SHA" "$PHASE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sc_ok "deployed $ENV/$STACK at $HEAD_SHA"
