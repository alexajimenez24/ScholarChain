#!/usr/bin/env bash
# scripts/plan.sh: a read-only Terraform plan of ONE stack, saved for the human gate.
#
# Usage:
#   scripts/plan.sh --env dev --stack foundation [--window on|off] [--text-out FILE]
#                   [--plan-dir DIR] [--serving-manifest s3://...] [--dry-run]
#
# --window on (cloud window) adds the toggles the committed JSON files keep off:
#   data -var enable_neptune=true, compute -var enable_emr_endpoint=true (the EMR and SQS
#   interface endpoints). --window off (default) plans them off, which for an applied
#   window DESTROYS Neptune (listed first as STATEFUL-DESTROY) and the endpoints.
# The services' egress (one NAT gateway and the private default route) is part of the app
# stack (infra/stacks/app/egress.tf) and exists while the services are not parked, so the
# app plan shows it; foundation has no window toggle (Phase 13).
# --serving-manifest (app only): the Spark run manifest of the pipeline run query-service
#   answers from (-var serving_run_manifest_uri=..., env SERVING_RUN_MANIFEST_URI); it is
#   recorded in the plan metadata. Without it the service starts unready (no served run).
# --dry-run: checks the arguments, names the init and plan it would run and the files it
#   would write, and evaluates the checks that need no AWS call; it makes no AWS call and
#   runs no terraform command. Exit 0 = local checks pass, 3 = a real run would refuse.
#
# Stacks are planned one at a time in the order foundation, data, compute, app,
# observability: a later stack reads the SSM parameters a lower stack publishes, so its
# plan fails until the lower stack is applied.
#
# What it does (nothing is applied, nothing in AWS is changed):
#   1. checks tools, the environment name, the backend file (no placeholder in a setting
#      line; comments may hold anything) and the TF_VAR_* inputs that never live in a
#      file (values are never printed):
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
#   <plan-dir>/<stack>.tfplan.meta  env, stack, revision, dirty flag, window, serving
#                                   manifest, sha256, time
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
# Exit codes: 0 plan saved (changes or none), 1 a check or terraform failed, 2 usage.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,6p' "$0"; exit 2; }

ENV="" STACK="" TEXT_OUT="" PLAN_DIR="" WINDOW=off SERVING="" DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --stack) STACK="${2:-}"; shift 2 ;;
    --window) WINDOW="${2:-}"; shift 2 ;;
    --serving-manifest) SERVING="${2:-}"; shift 2 ;;
    --text-out) TEXT_OUT="${2:-}"; shift 2 ;;
    --plan-dir) PLAN_DIR="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage ;;
    *) printf '==> [plan] FAILED: unknown argument: %s\n' "$1" >&2; usage ;;
  esac
done
[[ -n "$ENV" && -n "$STACK" ]] || usage
[[ "$WINDOW" == on || "$WINDOW" == off ]] || sc_die "--window must be on or off"
sc_check_stack "$STACK"
if [[ -n "$SERVING" ]]; then
  [[ "$STACK" == "app" ]] || sc_die "--serving-manifest applies to the app stack only"
  [[ "$SERVING" =~ $SC_SERVING_RE ]] || sc_die "--serving-manifest must be s3://<graph bucket>/runs/<run label>/spark/.../run-manifests/run-<hex>.json"
fi

foundation_bucket_matches() {
  local b
  b="$(sed -n 's/^bucket *= *"\(.*\)"$/\1/p' "$SC_INFRA/backend/$ENV.s3.tfbackend")"
  [[ "$b" == "${TF_VAR_state_bucket_name:-}" ]] ||
    sc_die "TF_VAR_state_bucket_name differs from the bucket in infra/backend/$ENV.s3.tfbackend"
}

if [[ "$DRY_RUN" == true ]]; then
  sc_step dry-run "env=$ENV stack=$STACK window=$WINDOW (no AWS call, no terraform command)"
  sc_dry_check "tools (terraform aws git sha256sum python3)" sc_need_tools terraform aws git sha256sum python3 timeout sed awk
  sc_dry_check "environment name and backend file (no placeholder)" sc_check_env "$ENV"
  sc_dry_check "plan-time inputs set (names only, values never printed)" sc_require_tf_vars "$STACK"
  sc_dry_check "variable file infra/environments/$ENV/$STACK.json" test -f "$SC_INFRA/environments/$ENV/$STACK.json"
  if [[ "$STACK" == "foundation" ]]; then
    sc_dry_check "TF_VAR_state_bucket_name equals the backend bucket" foundation_bucket_matches
  fi
  REV="$(sc_head)"
  DIRTY=false
  if sc_is_dirty; then DIRTY=true; fi
  sc_info "revision=$REV dirty=$DIRTY (a dirty plan is for review only; deploy.sh refuses it)"
  sc_dry_aws "caller identity (sts get-caller-identity, read only)"
  if [[ "$STACK" == "app" ]]; then
    sc_dry_aws "Neptune enabled (SSM /scholarchain/$ENV/data/neptune/enabled; a warning only)"
  fi
  dir="${PLAN_DIR:-${SCHOLARCHAIN_PLAN_DIR:-$HOME/.scholarchain/plans}/$ENV}"
  mapfile -t VARS < <(sc_tf_plan_vars "$ENV" "$STACK" "$REV" "$WINDOW" "$SERVING")
  sc_would "terraform -chdir=infra/stacks/$STACK init -input=false -reconfigure -lockfile=readonly -backend-config=../../backend/$ENV.s3.tfbackend -backend-config=key=scholarchain/$ENV/$STACK/terraform.tfstate"
  sc_would "terraform -chdir=infra/stacks/$STACK plan -input=false -lock-timeout=60s -detailed-exitcode $(IFS=' ' && printf '%s' "${VARS[*]}") -out=${dir/#"$HOME"/\~}/$STACK.tfplan"
  sc_would "write ${dir/#"$HOME"/\~}/$STACK.tfplan, $STACK.tfplan.meta and ${TEXT_OUT:-${dir/#"$HOME"/\~}/$STACK.plan.txt}, print the ==> PLAN-SUMMARY and ==> PLAN-SHA256 lines; never apply"
  sc_dry_end
fi

sc_step preflight "env=$ENV stack=$STACK"
sc_need_tools terraform aws git sha256sum python3 timeout sed awk
sc_check_env "$ENV"
sc_require_tf_vars "$STACK"
[[ -f "$SC_INFRA/environments/$ENV/$STACK.json" ]] || sc_die "no variable file infra/environments/$ENV/$STACK.json"
if [[ "$STACK" == "foundation" ]]; then
  foundation_bucket_matches
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
  sc_step readiness "Neptune and the served run (warnings only)"
  if [[ "$(sc_ssm "/scholarchain/$ENV/data/neptune/enabled")" != "true" ]]; then
    sc_info "WARNING: Neptune is disabled; query-service and graph-service will not become ready"
  fi
  if [[ -z "$SERVING" ]]; then
    sc_info "WARNING: no --serving-manifest; query-service starts without a served run and answers 503 until one is planned"
  else
    sc_info "served run: $SERVING"
  fi
fi

sc_step init "terraform init (S3 backend, key scholarchain/$ENV/$STACK/terraform.tfstate)"
sc_tf_init "$ENV" "$STACK" | sc_redact

sc_step plan "terraform plan -out ${PLAN/#"$HOME"/\~} window=$WINDOW (full output follows)"
mapfile -t VARS < <(sc_tf_plan_vars "$ENV" "$STACK" "$REV" "$WINDOW" "$SERVING")
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
  printf 'env=%s\nstack=%s\nrevision=%s\ndirty=%s\nwindow=%s\nserving_manifest=%s\nsha256=%s\ncreated=%s\nterraform=%s\n' \
    "$ENV" "$STACK" "$REV" "$DIRTY" "$WINDOW" "${SERVING:-none}" "$SHA" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(terraform version | head -1)"
} >"$PLAN.meta"
printf '==> PLAN-FILE %s\n==> PLAN-SHA256 %s\n==> PLAN-REVISION %s dirty=%s window=%s\n' \
  "${PLAN/#"$HOME"/\~}" "$SHA" "$REV" "$DIRTY" "$WINDOW" | tee -a "$TEXT_OUT"
sc_ok "saved plan, metadata and redacted text (${TEXT_OUT/#"$HOME"/\~}); nothing was applied"
