#!/usr/bin/env bash
# scripts/plan.sh: a read-only Terraform plan of ONE stack, saved for the human gate.
#
# Usage:
#   scripts/plan.sh --env dev --stack foundation [--window on|off] [--text-out FILE]
#                   [--plan-dir DIR] [--allow-no-egress]
#
# --window on (cloud window) adds the toggles the committed JSON files keep off:
#   foundation -var enable_nat=true, data -var enable_neptune=true,
#   compute -var enable_emr_endpoint=true. --window off (default) plans them off, which
#   for an applied window DESTROYS the NAT, Neptune (listed first as STATEFUL-DESTROY)
#   and the EMR endpoint. The app plan refuses to run while foundation's NAT is off,
#   because the services would have no egress (override: --allow-no-egress).
#
# Stacks are planned one at a time in the order foundation, data, compute, app,
# observability: a later stack reads the SSM parameters a lower stack publishes, so its
# plan fails until the lower stack is applied.
#
# What it does (nothing is applied, nothing in AWS is changed):
#   1. checks tools, the environment name, the backend file (no placeholder) and the
#      TF_VAR_* inputs that never live in a file (values are never printed):
#        every stack: TF_VAR_owner
#        foundation:  TF_VAR_budget_alert_email, TF_VAR_state_bucket_name
#   2. reads the caller identity (to redact the account ID) and the git HEAD,
#   3. terraform init with the S3 backend (bucket from infra/backend/<env>.s3.tfbackend,
#      key scholarchain/<env>/<stack>/terraform.tfstate, S3-native lock),
#   4. terraform plan -out, with -var-file=infra/environments/<env>/<stack>.json,
#      -var revision=<HEAD> (and -var image_tag=<HEAD> for app). The FULL plan output is
#      printed and saved (account ID replaced by <account-id>); nothing is hidden,
#   5. a summary (stateful destroys first, IAM changes, add/change/destroy/replace counts)
#      and the plan file's SHA-256, which the human writes into human-gate.md as
#      APPROVED-PLAN-SHA256 for scripts/deploy.sh.
#
# Files (outside the repository, so the tree stays clean):
#   <plan-dir>/<stack>.tfplan       saved plan (binary; holds plan-time values, never commit)
#   <plan-dir>/<stack>.tfplan.meta  env, stack, revision, dirty flag, sha256, time
#   <plan-dir>/<stack>.plan.txt     redacted plan text (or --text-out FILE)
# <plan-dir> defaults to $SCHOLARCHAIN_PLAN_DIR or ~/.scholarchain/plans, plus /<env>.
#
# A plan made on a dirty tree is marked dirty=true; deploy.sh refuses it. "Dirty" is the
# one shared test sc_dirty_entries (scripts/lib/common.sh): every change except evidence
# under docs/agent-runs/phase-NN/ and new files under releases/.
# The PLAN-SUMMARY line also counts guard-changes (IAM and budget resources) and, for
# foundation, admin-changes (everything the deployment role may not apply there, listed
# as ==> ADMIN-CHANGE lines); deploy.sh uses admin-changes to pick the identity for a
# foundation apply. The plan directory is always absolute (sc_plan_dir).

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,12p' "$0"; exit 2; }

ENV="" STACK="" TEXT_OUT="" PLAN_DIR="" WINDOW=off ALLOW_NO_EGRESS=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --stack) STACK="${2:-}"; shift 2 ;;
    --window) WINDOW="${2:-}"; shift 2 ;;
    --allow-no-egress) ALLOW_NO_EGRESS=true; shift ;;
    --text-out) TEXT_OUT="${2:-}"; shift 2 ;;
    --plan-dir) PLAN_DIR="${2:-}"; shift 2 ;;
    -h | --help) usage ;;
    *) sc_die "unknown argument: $1" ;;
  esac
done
[[ -n "$ENV" && -n "$STACK" ]] || usage
[[ "$WINDOW" == on || "$WINDOW" == off ]] || sc_die "--window must be on or off"

sc_step preflight "env=$ENV stack=$STACK"
sc_need_tools terraform aws git sha256sum python3 timeout sed awk
sc_check_env "$ENV"
sc_check_stack "$STACK"
sc_require_tf_vars "$STACK"
[[ -f "$SC_INFRA/environments/$ENV/$STACK.json" ]] || sc_die "no variable file infra/environments/$ENV/$STACK.json"
if [[ "$STACK" == "foundation" ]]; then
  backend_bucket="$(sed -n 's/^bucket *= *"\(.*\)"$/\1/p' "$SC_INFRA/backend/$ENV.s3.tfbackend")"
  [[ "$backend_bucket" == "${TF_VAR_state_bucket_name:-}" ]] ||
    sc_die "TF_VAR_state_bucket_name differs from the bucket in infra/backend/$ENV.s3.tfbackend"
fi
if [[ -n "$PLAN_DIR" ]]; then
  mkdir -p "$PLAN_DIR"
  PLAN_DIR="$(cd "$PLAN_DIR" && pwd -P)"
else
  PLAN_DIR="$(sc_plan_dir "$ENV")"
fi
PLAN="$PLAN_DIR/$STACK.tfplan"
TEXT_OUT="${TEXT_OUT:-$PLAN_DIR/$STACK.plan.txt}"
mkdir -p "$(dirname "$TEXT_OUT")"

sc_step identity "read-only caller identity"
sc_identity

sc_step revision "git HEAD and tree state"
REV="$(sc_head)"
DIRTY=false
if sc_is_dirty; then
  DIRTY=true
  sc_info "the tree is dirty: this plan is for review only; deploy.sh will refuse it"
fi
sc_info "revision=$REV dirty=$DIRTY"

if [[ "$STACK" == "app" ]]; then
  sc_step egress "the services need egress (Bedrock, ECR, Logs, SQS, S3 Vectors, JWKS)"
  nat="$(sc_ssm "/scholarchain/$ENV/foundation/network/nat-enabled")"
  if [[ "$nat" != "true" && "$ALLOW_NO_EGRESS" != true ]]; then
    sc_die "foundation has enable_nat=false: plan and deploy foundation with --window on first (or pass --allow-no-egress to plan anyway)"
  fi
  if [[ "$(sc_ssm "/scholarchain/$ENV/data/neptune/enabled")" != "true" ]]; then
    sc_info "WARNING: Neptune is disabled; query-service and graph-service will not become ready"
  fi
fi

sc_step init "terraform init (S3 backend, key scholarchain/$ENV/$STACK/terraform.tfstate)"
sc_tf_init "$ENV" "$STACK" | sc_redact

sc_step plan "terraform plan -out ${PLAN/#$HOME/~} window=$WINDOW (full output follows)"
mapfile -t VARS < <(sc_tf_plan_vars "$ENV" "$STACK" "$REV" "$WINDOW")
rm -f "$PLAN" "$PLAN.meta"
# -detailed-exitcode returns 2 for "changes present": suspend errexit and the ERR trap.
trap - ERR
set +e
sc_tf "$STACK" plan -input=false -no-color -lock-timeout=60s -detailed-exitcode \
  "${VARS[@]}" "-out=$PLAN" 2>&1 | sc_redact | tee "$TEXT_OUT"
rc="${PIPESTATUS[0]}"
set -e
trap 'sc_on_error "$?" "$LINENO" "$BASH_COMMAND"' ERR
case "$rc" in
  0) sc_info "plan: no changes" ;;
  2) sc_info "plan: changes present" ;;
  *) sc_die "terraform plan exited $rc (output above and in $TEXT_OUT)" ;;
esac

sc_step summary "stateful destroys first, then IAM changes and counts"
sc_tf "$STACK" show -json "$PLAN" | python3 -I "$SC_ROOT/scripts/lib/plan_summary.py" --stack "$STACK" | tee -a "$TEXT_OUT"

SHA="$(sha256sum "$PLAN" | awk '{print $1}')"
{
  printf 'env=%s\nstack=%s\nrevision=%s\ndirty=%s\nwindow=%s\nsha256=%s\ncreated=%s\nterraform=%s\n' \
    "$ENV" "$STACK" "$REV" "$DIRTY" "$WINDOW" "$SHA" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(terraform version | head -1)"
} >"$PLAN.meta"
printf '==> PLAN-FILE %s\n==> PLAN-SHA256 %s\n==> PLAN-REVISION %s dirty=%s window=%s\n' \
  "${PLAN/#$HOME/~}" "$SHA" "$REV" "$DIRTY" "$WINDOW" | tee -a "$TEXT_OUT"
sc_ok "saved plan, metadata and redacted text (${TEXT_OUT/#$HOME/~}); nothing was applied"
