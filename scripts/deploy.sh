#!/usr/bin/env bash
# scripts/deploy.sh: apply ONE saved, human-approved Terraform plan of one stack.
#
# Usage:
#   scripts/deploy.sh --env dev --stack <stack> --phase NN [--plan FILE] [--dry-run]
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
#     for a foundation plan with admin-changes=0 (interface endpoint, rule and SSM
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
# app stack only: before the apply it builds the three images with scripts/package.sh
# --images-only (the assemblies, then one bounded sbt dockerLocalBuild per image; local
# tags scholarchain-<image>:<HEAD>), tags each
# with the full Git SHA (HEAD) for its ECR repository and pushes it (immutable tags; an
# existing tag is kept, never overwritten), and reads each pushed image's digest.
#
# After the apply it verifies the landed revision:
#   - every stack: SSM /scholarchain/<env>/<stack>/deploy/revision == HEAD,
#   - app: the three ECS services are stable, each task definition names an image tagged
#     HEAD, and every running task reports the image digest that ECR holds for that tag.
# The final ==> RELEASE lines (revision, plan SHA-256 and, for app, each image digest as
# <repository name>@sha256:..., no account ID) are what deploy-engineer copies into
# releases/.
#
# --dry-run: the same local checks (tree, gate lines, HEAD, plan SHA-256 and metadata),
# printed as PASS or WOULD-REFUSE, plus what a real run would do; no AWS call, no build,
# no terraform command. Exit 0 = local checks pass, 3 = a real run would refuse.
# Exit codes: 0 deployed and verified, 1 refused or failed, 2 usage.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,5p' "$0"; exit 2; }

ENV="" STACK="" PHASE="" PLAN="" DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --stack) STACK="${2:-}"; shift 2 ;;
    --phase) PHASE="${2:-}"; shift 2 ;;
    --plan) PLAN="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage ;;
    *) printf '==> [deploy] FAILED: unknown argument: %s\n' "$1" >&2; usage ;;
  esac
done
[[ -n "$ENV" && -n "$STACK" && -n "$PHASE" ]] || usage

SERVICES=(query-service graph-service embedding-worker)

# Status 0 when $1 is one of the signed APPROVED-PLAN-SHA256 values.
plan_is_signed() {
  local s
  for s in "${GATE_SHAS[@]}"; do
    if [[ "$s" == "$1" ]]; then return 0; fi
  done
  return 1
}

# check_plan PLANFILE METAFILE: the plan's SHA-256 is signed and its metadata matches
# this env, stack, HEAD and a clean tree. Needs sc_read_gate first. Sets SHA.
check_plan() {
  local plan="$1" meta_file="$2"
  SHA="$(sha256sum -- "$plan" | awk '{print $1}')"
  [[ "$SHA" =~ ^[0-9a-f]{64}$ ]] || sc_die "could not hash the plan copy"
  plan_is_signed "$SHA" || sc_die "plan SHA-256 $SHA is not an APPROVED-PLAN-SHA256 in phase-$PHASE/human-gate.md"
  meta() { sed -n "s/^$1=//p" "$meta_file"; }
  [[ "$(meta sha256)" == "$SHA" ]] || sc_die "plan file changed after plan.sh wrote its metadata"
  [[ "$(meta env)" == "$ENV" && "$(meta stack)" == "$STACK" ]] || sc_die "plan metadata names env=$(meta env) stack=$(meta stack)"
  [[ "$(meta revision)" == "$HEAD_SHA" ]] || sc_die "plan was made at $(meta revision), HEAD is $HEAD_SHA"
  [[ "$(meta dirty)" == "false" ]] || sc_die "plan was made on a dirty tree"
}

PLAN_ARG="${PLAN:-${SCHOLARCHAIN_PLAN_DIR:-$HOME/.scholarchain/plans}/$ENV/$STACK.tfplan}"

if [[ "$DRY_RUN" == true ]]; then
  sc_step dry-run "env=$ENV stack=$STACK phase=$PHASE (no AWS call, no build, no terraform command)"
  sc_dry_check "tools (terraform aws git sha256sum)" sc_need_tools terraform aws git sha256sum timeout sed awk mktemp cp chmod
  sc_dry_check "environment name and backend file" sc_check_env "$ENV"
  sc_dry_check "stack name" sc_check_stack "$STACK"
  sc_dry_check "phase number" sc_check_phase "$PHASE"
  sc_dry_check "source tree is clean" sc_require_clean_tree
  HEAD_SHA="$(sc_head)"
  sc_dry_check "human gate phase-$PHASE approves HEAD $HEAD_SHA and env $ENV within budget" sc_gate_for "$PHASE" "$ENV"
  # shellcheck disable=SC2329 # invoked through sc_dry_check
  dry_plan() {
    local p m
    p="$(sc_abs_file "$PLAN_ARG")" || sc_die "saved plan not found, not a regular file, or a symlink: ${PLAN_ARG/#"$HOME"/\~}; run scripts/plan.sh first"
    m="$(sc_abs_file "$p.meta")" || sc_die "plan metadata not found: ${p/#"$HOME"/\~}.meta"
    sc_read_gate "$PHASE"
    check_plan "$p" "$m"
  }
  sc_dry_check "saved plan ${PLAN_ARG/#"$HOME"/\~} is signed and its metadata names $ENV/$STACK at HEAD, clean" dry_plan
  sc_dry_aws "caller is assumed-role/scholarchain-$ENV-deployment (foundation: by admin-changes)"
  if [[ "$STACK" == "app" ]]; then
    sc_would "scripts/package.sh --images-only (the assemblies, then one image at a time, each under timeout 20m), then tag and push query-service, graph-service and embedding-worker as <ECR repository>:$HEAD_SHA (an existing tag is kept)"
  fi
  sc_would "terraform init (S3 backend), re-hash the private plan copy, terraform apply <that copy> (no -auto-approve, no plan, no destroy)"
  sc_would "verify SSM /scholarchain/$ENV/$STACK/deploy/revision == $HEAD_SHA"
  if [[ "$STACK" == "app" ]]; then
    sc_would "verify the three services are stable, their task images are :$HEAD_SHA and every running task's digest equals the ECR digest"
  fi
  sc_would "print the ==> RELEASE lines for releases/"
  sc_dry_end
fi

sc_step preflight "env=$ENV stack=$STACK phase=$PHASE"
sc_need_tools terraform aws git sha256sum timeout sed awk mktemp cp chmod
sc_check_env "$ENV"
sc_check_stack "$STACK"
sc_check_phase "$PHASE"
PLAN="$(sc_abs_file "$PLAN_ARG")" ||
  sc_die "saved plan not found, not a regular file, or a symlink: ${PLAN_ARG/#"$HOME"/\~}; run scripts/plan.sh first"
META="$(sc_abs_file "$PLAN.meta")" ||
  sc_die "plan metadata not found, not a regular file, or a symlink: ${PLAN/#"$HOME"/\~}.meta; run scripts/plan.sh first"

# The private copy that is hashed, inspected and applied (never the original path).
WORK="$(mktemp -d)" || sc_die "mktemp failed"
trap 'rm -rf -- "$WORK"' EXIT
APPLY_PLAN="$WORK/approved.tfplan"
APPLY_META="$WORK/approved.tfplan.meta"
cp -- "$PLAN" "$APPLY_PLAN"
cp -- "$META" "$APPLY_META"
chmod 0400 -- "$APPLY_PLAN" "$APPLY_META"
sc_info "plan ${PLAN/#"$HOME"/\~} copied to a private directory; only the copy is checked and applied"

sc_step tree "refuse a dirty source tree"
sc_require_clean_tree
HEAD_SHA="$(sc_head)"

sc_step gate "read the human gate"
sc_read_gate "$PHASE"
sc_require_gate_matches "$ENV"

sc_step plan-check "plan SHA-256 against APPROVED-PLAN-SHA256"
check_plan "$APPLY_PLAN" "$APPLY_META"
sc_ok "plan $SHA approved for $ENV/$STACK at $HEAD_SHA"

sc_step identity "caller identity (the deployment role, so the budget deny action applies)"
sc_identity
if [[ "$STACK" != "foundation" ]]; then
  sc_require_deployment_role "$ENV"
fi

declare -A DIGEST=()
if [[ "$STACK" == "app" ]]; then
  sc_step images "build (scripts/package.sh --images-only), tag by Git SHA, push and read the digests"
  sc_need_tools docker
  "$SC_ROOT/scripts/package.sh" --images-only
  registry=""
  for img in "${SERVICES[@]}"; do
    url="$(sc_ssm "/scholarchain/$ENV/compute/ecr/$img/repository-url")"
    repo="$(sc_ssm "/scholarchain/$ENV/compute/ecr/$img/name")"
    if [[ -z "$registry" ]]; then
      registry="${url%%/*}"
      # The login token goes from the AWS CLI straight into docker on a pipe; never printed.
      # docker's stdout and stderr name the registry host: both are redacted (MINOR-1).
      sc_aws ecr get-login-password | sc_docker 120s login --username AWS --password-stdin "$registry" >/dev/null
    fi
    if sc_aws ecr describe-images --repository-name "$repo" --image-ids "imageTag=$HEAD_SHA" >/dev/null 2>&1; then
      sc_skip "$img:$HEAD_SHA already in ECR (immutable tag kept)"
    else
      sc_docker 60s tag "scholarchain-$img:$HEAD_SHA" "$url:$HEAD_SHA" | sc_redact
      sc_docker 30m push "$url:$HEAD_SHA" | sc_redact
      sc_ok "pushed $img:$HEAD_SHA"
    fi
    DIGEST[$img]="$(sc_aws ecr describe-images --repository-name "$repo" --image-ids "imageTag=$HEAD_SHA" \
      --query 'imageDetails[0].imageDigest' --output text)"
    [[ "${DIGEST[$img]}" =~ ^sha256:[0-9a-f]{64}$ ]] || sc_die "could not read the ECR digest of $repo:$HEAD_SHA"
    sc_ok "$img digest ${DIGEST[$img]}"
  done
fi

sc_step init "terraform init (S3 backend)"
sc_tf_init "$ENV" "$STACK" | sc_redact

# foundation: the deployment role applies it only when the signed plan touches nothing
# but the interface endpoint, security group rule and SSM resources (admin-changes=0);
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
  SC_AWS_TIMEOUT=20m sc_aws ecs wait services-stable --cluster "$cluster" --services "${SERVICES[@]}" ||
    sc_die "ECS services did not become stable within 20 minutes (the circuit breaker may have rolled back)"
  for svc in "${SERVICES[@]}"; do
    td="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].taskDefinition' --output text)"
    image="$(sc_aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
    running="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].runningCount' --output text)"
    [[ "${image##*:}" == "$HEAD_SHA" ]] || sc_die "$svc runs image tag ${image##*:}, expected $HEAD_SHA"
    mapfile -t tasks < <(sc_aws ecs list-tasks --cluster "$cluster" --service-name "$svc" --desired-status RUNNING \
      --query 'taskArns[]' --output text | tr '\t' '\n' | grep -v -E '^(None)?$' || true)
    if [[ "${#tasks[@]}" -gt 0 ]]; then
      mapfile -t digests < <(sc_aws ecs describe-tasks --cluster "$cluster" --tasks "${tasks[@]}" \
        --query 'tasks[].containers[0].imageDigest' --output text | tr '\t' '\n' | sort -u)
      [[ "${#digests[@]}" -eq 1 && "${digests[0]}" == "${DIGEST[$svc]}" ]] ||
        sc_die "$svc running tasks report digest(s) ${digests[*]:-none}, expected ${DIGEST[$svc]}"
    fi
    sc_ok "$svc runs :$HEAD_SHA (${DIGEST[$svc]}) with $running task(s), task definition $(printf '%s' "$td" | sc_redact)"
  done
fi

printf '==> RELEASE env=%s\n==> RELEASE stack=%s\n==> RELEASE commit=%s\n==> RELEASE plan-sha256=%s\n==> RELEASE phase=%s\n==> RELEASE applied-at=%s\n' \
  "$ENV" "$STACK" "$HEAD_SHA" "$SHA" "$PHASE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ "$STACK" == "app" ]]; then
  for svc in "${SERVICES[@]}"; do
    printf '==> RELEASE image %s=scholarchain-%s-%s@%s\n' "$svc" "$ENV" "$svc" "${DIGEST[$svc]}"
  done
fi
sc_ok "deployed $ENV/$STACK at $HEAD_SHA"
