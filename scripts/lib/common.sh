# shellcheck shell=bash
# Shared helpers for the ScholarChain operational scripts (sourced, never executed).
#
# Every script: bash strict mode, no interactive prompts, every external call under
# `timeout`, greppable step markers on stdout ("==> [script] STEP|INFO|OK|SKIP|FAILED"),
# and failures that print the step, the command and its exit code. The AWS account ID is
# replaced by <account-id> in everything a script prints or saves. No secret value is
# ever read, printed or written by these helpers.

set -Eeuo pipefail
IFS=$'\n\t'
umask 077

export AWS_PAGER=""
export AWS_REGION="us-east-2"
export AWS_DEFAULT_REGION="us-east-2"
export CHECKPOINT_DISABLE=1
export TF_IN_AUTOMATION=1
export TF_INPUT=0

SC_NAME="${SC_NAME:-$(basename "${BASH_SOURCE[1]:-${0}}" .sh)}"
SC_STEP="start"
SC_STACKS=(foundation data compute app observability)
SC_REGION="us-east-2"
SC_ACCOUNT_ID=""

sc_step() {
  SC_STEP="$1"
  shift
  printf '==> [%s] STEP %s: %s\n' "$SC_NAME" "$SC_STEP" "$*"
}

sc_info() { printf '==> [%s] INFO: %s\n' "$SC_NAME" "$*"; }
sc_ok() { printf '==> [%s] OK: %s\n' "$SC_NAME" "$*"; }
sc_skip() { printf '==> [%s] SKIP: %s\n' "$SC_NAME" "$*"; }

# A failed write (a closed output reader, Phase 13 repair-03) never changes the exit code.
sc_die() {
  printf '==> [%s] FAILED step=%s: %s\n' "$SC_NAME" "$SC_STEP" "$*" >&2 || true
  exit 1
}

SC_LAST_CALL=""
sc_on_error() {
  local code="$1" line="$2" cmd="$3"
  # A failed sc_aws, sc_tf or sc_docker call surfaces at sc_errredact's return: name it.
  # shellcheck disable=SC2016 # the literal command text, not an expansion
  if [[ "$cmd" == 'return "$rc"' && -n "$SC_LAST_CALL" ]]; then cmd="$SC_LAST_CALL"; fi
  printf '==> [%s] FAILED step=%s line=%s exit=%s command: %s\n' \
    "$SC_NAME" "$SC_STEP" "$line" "$code" "$(printf '%s' "$cmd" | sc_redact)" >&2 || true
  exit "$code"
}
trap 'sc_on_error "$?" "$LINENO" "$BASH_COMMAND"' ERR

# Repository root, resolved from the script location (works from any working directory).
SC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SC_INFRA="$SC_ROOT/infra"

sc_need_tools() {
  local t
  for t in "$@"; do
    command -v "$t" >/dev/null 2>&1 || sc_die "required tool not found on PATH: $t (run: source toolchain/env.sh)"
  done
}

sc_check_env() {
  [[ "$1" =~ ^[a-z][a-z0-9]{1,7}$ ]] || sc_die "invalid environment name: $1"
  [[ -f "$SC_INFRA/backend/$1.s3.tfbackend" ]] || sc_die "no backend file infra/backend/$1.s3.tfbackend"
  # Phase 13 (Phase 12 gate): only setting lines count; a comment (# or //) may hold
  # any text, angle brackets included.
  if grep -v -E '^[[:space:]]*(#|//)' "$SC_INFRA/backend/$1.s3.tfbackend" | grep -q '[<>]'; then
    sc_die "infra/backend/$1.s3.tfbackend still holds a placeholder in a setting line; the human writes the state bucket name first"
  fi
}

sc_check_stack() {
  local s
  for s in "${SC_STACKS[@]}"; do
    if [[ "$s" == "$1" ]]; then return 0; fi
  done
  sc_die "unknown stack: $1 (expected one of: $(IFS=' ' && printf '%s' "${SC_STACKS[*]}"))"
}

sc_check_phase() {
  [[ "$1" =~ ^[0-9]{2}$ ]] || sc_die "invalid phase: $1 (two digits, for example 14)"
}

# ----- Redaction -----------------------------------------------------------------------
# Replaces the caller's account ID, and any 12-digit account field of an ARN, ECR host or
# SQS queue URL, with <account-id>. Reads stdin, writes stdout (line-buffered).
SC_REDACT_RULES=(
  -e 's/(arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:)[0-9]{12}(:|\/)/\1<account-id>\2/g'
  -e 's/[0-9]{12}(\.dkr\.ecr\.)/<account-id>\1/g'
  -e 's/(amazonaws\.com\/)[0-9]{12}(\/|$)/\1<account-id>\2/g'
)
sc_redact() {
  if [[ "$SC_ACCOUNT_ID" =~ ^[0-9]{12}$ ]]; then
    sed -u -E -e "s/${SC_ACCOUNT_ID}/<account-id>/g" "${SC_REDACT_RULES[@]}"
  else
    sed -u -E "${SC_REDACT_RULES[@]}"
  fi
}

# Error streams (Phase 13 MINOR-1): sc_redact plus ANY bare 12-digit number, because an
# error can name the account outside an ARN ("registry with id '...'") before the caller
# identity is known. Error text is never parsed, so over-redaction there is harmless.
sc_redact_err() {
  sc_redact | sed -u -E \
    -e 's/(^|[^0-9A-Za-z])[0-9]{12}($|[^0-9A-Za-z])/\1<account-id>\2/g' \
    -e 's/(^|[^0-9A-Za-z])[0-9]{12}($|[^0-9A-Za-z])/\1<account-id>\2/g'
}

# sc_errredact CMD...: runs CMD with its stdout untouched (callers parse it) and its
# stderr streamed through sc_redact_err on a pipe (no temporary file). Returns CMD's own
# exit code. Repair-03: if the redactor dies (its reader closed, a signal), cat drains the
# rest of CMD's stderr, so CMD never gets a broken pipe and the filter never sets the code.
sc_errredact() {
  local rc=0
  { "$@" 2>&1 1>&3 3>&- | { sc_redact_err >&2 3>&- || cat >/dev/null; }; } 3>&1 || rc=$?
  return "$rc"
}

# ----- AWS (read calls unless the caller says otherwise) -------------------------------
# Every AWS CLI, Terraform and docker call of the scripts goes through sc_errredact.
sc_aws() {
  SC_LAST_CALL="aws ${1:-} ${2:-}"
  sc_errredact timeout "${SC_AWS_TIMEOUT:-120s}" aws --region "$SC_REGION" --no-cli-pager "$@"
}
sc_docker() {
  local limit="$1"
  shift
  SC_LAST_CALL="docker ${1:-}"
  sc_errredact timeout "$limit" docker "$@"
}

sc_identity() {
  SC_ACCOUNT_ID="$(sc_aws sts get-caller-identity --query Account --output text)"
  [[ "$SC_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || sc_die "could not read the caller identity (AWS profile not configured or expired)"
  local arn
  arn="$(sc_aws sts get-caller-identity --query Arn --output text)"
  SC_CALLER_ARN="$arn"
  # The session name of an assumed role can be a person's login or email: drop it.
  if [[ "$arn" == *":assumed-role/"* ]]; then arn="${arn%/*}/<session>"; fi
  SC_CALLER_SHOWN="$(printf '%s' "$arn" | sc_redact)"
  sc_info "caller: $SC_CALLER_SHOWN"
}

# Repair M3: the budget action attaches its deny policy to the deployment role only, so a
# step that creates billable resources must run as that role (infra/README.md,
# "Deployment identity"). Call after sc_identity.
SC_CALLER_ARN=""
SC_CALLER_SHOWN=""
sc_is_deployment_role() {
  local re="^arn:aws:sts::[0-9]{12}:assumed-role/scholarchain-$1-deployment/[^/]+$"
  [[ "$SC_CALLER_ARN" =~ $re ]]
}

sc_require_deployment_role() {
  if ! sc_is_deployment_role "$1"; then
    sc_die "caller $SC_CALLER_SHOWN is not the role scholarchain-$1-deployment; use its profile (AWS_PROFILE=scholarchain-$1-deploy, infra/README.md \"Deployment identity\") so the budget deny action applies"
  fi
  sc_ok "caller is the deployment role scholarchain-$1-deployment (covered by the budget deny action)"
}

sc_ssm() {
  sc_aws ssm get-parameter --name "$1" --query Parameter.Value --output text
}

# ----- Git -------------------------------------------------------------------------------
sc_head() { git -C "$SC_ROOT" rev-parse HEAD; }

# The ONE dirty-tree test of every script (repair M2): plan.sh marks a plan dirty with
# it; deploy.sh, rollback.sh and run-bounded-pipeline.sh start refuse with it. It reads
# every change git reports (staged and unstaged, tracked and each untracked file, both
# paths of a rename or copy; ignored files excluded) and exempts only evidence written
# after the approved commit:
#   - any change under docs/agent-runs/phase-NN/ (phase records and the human's
#     human-gate.md), when every path of the entry is under such a folder;
#   - a NEW file under releases/ (untracked, or staged as added): release records are
#     append only, so a modified, deleted or renamed release record stays dirty.
# Everything else is dirty. HEAD must still equal APPROVED-COMMIT (sc_require_gate_matches).
SC_PHASE_EVIDENCE_RE='^docs/agent-runs/phase-[0-9]{2}/[^/]'
SC_RELEASE_RE='^releases/[^/]'

# sc_evidence_entry XY PATH [ORIG]: status 0 when the entry is exempt evidence.
sc_evidence_entry() {
  local xy="$1" path="$2" orig="${3:-}"
  if [[ "$path" =~ $SC_PHASE_EVIDENCE_RE ]]; then
    if [[ -z "$orig" || "$orig" =~ $SC_PHASE_EVIDENCE_RE ]]; then return 0; fi
  fi
  if [[ "$path" =~ $SC_RELEASE_RE && -z "$orig" ]]; then
    case "$xy" in
      '??' | 'A ' | 'AM') return 0 ;;
      *) ;;
    esac
  fi
  return 1
}

# Prints each non-evidence change as "XY path" (plus "(from orig)" for a rename or
# copy). Status 2, with nothing trusted, when git status itself fails.
sc_dirty_entries() {
  local status entry xy path orig
  status="$(mktemp)" || return 2
  if ! git -C "$SC_ROOT" status --porcelain=v1 -z --untracked-files=all >"$status"; then
    rm -f "$status"
    return 2
  fi
  while IFS= read -r -d '' entry; do
    xy="${entry:0:2}"
    path="${entry:3}"
    orig=""
    if [[ "$xy" == *[RC]* ]]; then
      IFS= read -r -d '' orig || orig="<unreadable>"
    fi
    if ! sc_evidence_entry "$xy" "$path" "$orig"; then
      printf '%s %s%s\n' "$xy" "$path" "${orig:+ (from $orig)}"
    fi
  done <"$status"
  rm -f "$status"
}

# Status 0 (dirty) also when the tree cannot be read: fail closed.
sc_is_dirty() {
  local out
  out="$(sc_dirty_entries)" || return 0
  [[ -n "$out" ]]
}

sc_require_clean_tree() {
  local out
  out="$(sc_dirty_entries)" || sc_die "could not read the git status of the repository"
  if [[ -n "$out" ]]; then
    printf '%s\n' "$out" | head -50 >&2 || true
    sc_die "the source tree is dirty (changes above); only docs/agent-runs/phase-NN/ and new files under releases/ are exempt as evidence"
  fi
  sc_ok "source tree is clean at $(sc_head) (evidence exempt: docs/agent-runs/phase-NN/, new files under releases/)"
}

# ----- Terraform -------------------------------------------------------------------------
sc_tf() {
  local stack="$1"
  shift
  SC_LAST_CALL="terraform -chdir=infra/stacks/$stack ${1:-}"
  sc_errredact timeout "${SC_TF_TIMEOUT:-15m}" terraform -chdir="$SC_INFRA/stacks/$stack" "$@"
}

sc_tf_init() {
  local env="$1" stack="$2"
  sc_tf "$stack" init -input=false -no-color -reconfigure -lockfile=readonly \
    "-backend-config=../../backend/$env.s3.tfbackend" \
    "-backend-config=key=scholarchain/$env/$stack/terraform.tfstate"
}

# Inputs a stack needs at plan time that never live in a file. Values are never printed.
sc_require_tf_vars() {
  local stack="$1" v
  local -a need=(TF_VAR_owner)
  if [[ "$stack" == "foundation" ]]; then
    need+=(TF_VAR_budget_alert_email TF_VAR_state_bucket_name)
  fi
  for v in "${need[@]}"; do
    [[ -n "${!v:-}" ]] || sc_die "$v is not set (supply it in the environment at plan time; never in a file)"
  done
}

# -var arguments every plan of a stack gets (revision, and image_tag for app). With
# window=on, the cloud-window toggles that the committed JSON files keep off:
#   data enable_neptune=true, compute enable_emr_endpoint=true (the EMR and SQS
#   endpoints). Phase 13: the NAT gateway is an app-stack resource that exists while the
#   services are not parked, so foundation has no window toggle any more.
# app only: a 5th argument, the served run manifest URI, adds
#   -var=serving_run_manifest_uri=<uri> (query-service SERVING_RUN_MANIFEST_URI).
sc_tf_plan_vars() {
  local env="$1" stack="$2" rev="$3" window="${4:-off}" serving="${5:-}"
  printf '%s\n' "-var-file=../../environments/$env/$stack.json" "-var=revision=$rev"
  if [[ "$stack" == "app" ]]; then
    printf '%s\n' "-var=image_tag=$rev"
    if [[ -n "$serving" ]]; then
      printf '%s\n' "-var=serving_run_manifest_uri=$serving"
    fi
  fi
  if [[ "$window" == "on" ]]; then
    case "$stack" in
      data) printf '%s\n' "-var=enable_neptune=true" ;;
      compute) printf '%s\n' "-var=enable_emr_endpoint=true" ;;
      *) ;;
    esac
  fi
}

# Served run manifest URI shape (app variable serving_run_manifest_uri, same pattern).
# shellcheck disable=SC2034 # used by plan.sh and run-bounded-pipeline.sh
SC_SERVING_RE='^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/runs/run-[0-9]{8}t[0-9]{6}z-[0-9a-f]{7}/spark/([A-Za-z0-9=._-]+/)*run-manifests/run-[0-9a-f]+\.json$'

# Always an absolute, symlink-free path (repair round 2, n1): terraform runs with -chdir
# into the stack directory, so a relative plan path would name a different file there
# than the one a script hashes from its own working directory.
sc_plan_dir() {
  local d="${SCHOLARCHAIN_PLAN_DIR:-$HOME/.scholarchain/plans}/$1"
  mkdir -p -- "$d"
  (cd -- "$d" && pwd -P)
}

# sc_abs_file PATH: the absolute path of an existing regular file, with its directory
# resolved (symlinks included); refuses a symlink as the file itself. Prints the path.
sc_abs_file() {
  local p="$1" dir base
  [[ -n "$p" ]] || return 1
  dir="$(dirname -- "$p")"
  base="$(basename -- "$p")"
  dir="$(cd -- "$dir" 2>/dev/null && pwd -P)" || return 1
  [[ -f "$dir/$base" && ! -L "$dir/$base" ]] || return 1
  printf '%s/%s' "$dir" "$base"
}

# ----- Human gate (docs/agent-runs/README.md, human-gate.md lines) -----------------------
# Sets GATE_COMMIT, GATE_ENV, GATE_COST, GATE_REMAINING and the array GATE_SHAS.
sc_read_gate() {
  local phase="$1" f
  f="$SC_ROOT/docs/agent-runs/phase-$phase/human-gate.md"
  [[ -f "$f" ]] || sc_die "no human gate: docs/agent-runs/phase-$phase/human-gate.md"

  local n
  n="$(grep -c '^APPROVED-COMMIT: ' "$f" || true)"
  [[ "$n" == "1" ]] || sc_die "human-gate.md must hold exactly one APPROVED-COMMIT line (found $n)"
  n="$(grep -c '^APPROVED-ENVIRONMENT: ' "$f" || true)"
  [[ "$n" == "1" ]] || sc_die "human-gate.md must hold exactly one APPROVED-ENVIRONMENT line (found $n)"

  GATE_COMMIT="$(sed -n 's/^APPROVED-COMMIT: *\([0-9a-f]\{40\}\) *$/\1/p' "$f")"
  GATE_ENV="$(sed -n 's/^APPROVED-ENVIRONMENT: *\([a-z][a-z0-9]*\) *$/\1/p' "$f")"
  GATE_COST="$(sed -n 's/^COST-ESTIMATE-USD: *\([0-9][0-9]*\(\.[0-9]*\)\{0,1\}\) *$/\1/p' "$f" | head -1)"
  GATE_REMAINING="$(sed -n 's/^BUDGET-REMAINING-USD: *\([0-9][0-9]*\(\.[0-9]*\)\{0,1\}\) *$/\1/p' "$f" | head -1)"
  GATE_SHAS=() GATE_EXPERIMENT_SHAS=()
  mapfile -t GATE_SHAS < <(sed -n 's/^APPROVED-PLAN-SHA256: *\([0-9a-f]\{64\}\) *$/\1/p' "$f")
  # Phase 13: scripts/run-experiment.sh runs only a manifest whose SHA-256 is signed here.
  # shellcheck disable=SC2034 # read by run-experiment.sh
  mapfile -t GATE_EXPERIMENT_SHAS < <(sed -n 's/^APPROVED-EXPERIMENT-SHA256: *\([0-9a-f]\{64\}\) *$/\1/p' "$f")

  [[ -n "$GATE_COMMIT" ]] || sc_die "APPROVED-COMMIT is not a 40-hex commit SHA"
  [[ -n "$GATE_ENV" ]] || sc_die "APPROVED-ENVIRONMENT is not a valid environment name"
  [[ -n "$GATE_COST" ]] || sc_die "COST-ESTIMATE-USD is missing or not a number"
  [[ -n "$GATE_REMAINING" ]] || sc_die "BUDGET-REMAINING-USD is missing or not a number"
  awk -v c="$GATE_COST" -v r="$GATE_REMAINING" 'BEGIN { exit !(c <= r && r <= 25) }' ||
    sc_die "COST-ESTIMATE-USD ($GATE_COST) exceeds BUDGET-REMAINING-USD ($GATE_REMAINING), or the remaining budget is above the 25 USD cap"
  sc_ok "human gate phase-$phase: commit=$GATE_COMMIT env=$GATE_ENV cost=$GATE_COST remaining=$GATE_REMAINING approved-plans=${#GATE_SHAS[@]}"
}

sc_require_gate_matches() {
  local env="$1" head
  head="$(sc_head)"
  [[ "$GATE_COMMIT" == "$head" ]] || sc_die "HEAD $head is not the APPROVED-COMMIT $GATE_COMMIT"
  [[ "$GATE_ENV" == "$env" ]] || sc_die "environment $env is not the APPROVED-ENVIRONMENT $GATE_ENV"
}

# Both gate checks at once (used by the dry runs below).
sc_gate_for() {
  sc_read_gate "$1"
  sc_require_gate_matches "$2"
}

# ----- Dry runs (Phase 13) ---------------------------------------------------------------
# Every cloud-dependent script takes --dry-run: it checks its arguments, names what a real
# run would do, evaluates the approval checks that need no AWS call (clean tree, the human
# gate lines, HEAD), and makes NO AWS call, Terraform init, plan, apply, build or upload.
# Exit codes of a dry run: 0 = the local checks pass; 3 = the arguments are valid but a
# real run would refuse (each reason printed as WOULD-REFUSE); 2 = usage (a missing or
# unknown argument); 1 = an argument value is invalid (FAILED names it).
# Checks that need AWS (caller identity, SSM preconditions) are printed as NOT-CHECKED.
SC_DRY_REFUSALS=0

sc_would() { printf '==> [%s] DRY-RUN would: %s\n' "$SC_NAME" "$*"; }

# sc_dry_check DESC CMD...: runs CMD (a local check that dies on failure) in a subshell;
# prints PASS or WOULD-REFUSE with the check's own reason. Never exits.
sc_dry_check() {
  local desc="$1" err reason
  shift
  err="$(mktemp)" || sc_die "mktemp failed"
  if ("$@") >/dev/null 2>"$err"; then
    printf '==> [%s] DRY-RUN check %s: PASS\n' "$SC_NAME" "$desc"
  else
    reason="$(grep -E '^==> \[[^]]*\] FAILED' "$err" | tail -1 | sed -E 's/^==> \[[^]]*\] FAILED step=[^:]*: //' | sc_redact || true)"
    printf '==> [%s] DRY-RUN check %s: WOULD-REFUSE (%s)\n' "$SC_NAME" "$desc" "${reason:-see the real run}"
    SC_DRY_REFUSALS=$((SC_DRY_REFUSALS + 1))
  fi
  rm -f "$err"
}

sc_dry_aws() { printf '==> [%s] DRY-RUN check %s: NOT-CHECKED (needs an AWS call; the real run checks it)\n' "$SC_NAME" "$*"; }

sc_dry_end() {
  if [[ "$SC_DRY_REFUSALS" -gt 0 ]]; then
    printf '==> [%s] DRY-RUN result: a real run would REFUSE (%s check(s) above); no AWS call was made, nothing changed\n' "$SC_NAME" "$SC_DRY_REFUSALS"
    exit 3
  fi
  printf '==> [%s] DRY-RUN result: the local checks pass; no AWS call was made, nothing changed\n' "$SC_NAME"
  exit 0
}

# ----- Exit guard: cluster check and ECS scale to zero (Phase 13 MINOR-2, repair-03) ----
# run-bounded-pipeline.sh and run-experiment.sh arm it only once a real run has passed
# its gates (finish: once its label is checked), so a dry run, a usage error or a refusal
# before the gates makes no AWS call. sc_guard_arm sets the traps and ignores SIGPIPE in
# the script (a closed output reader then fails a write with EPIPE instead of killing the
# script unguarded); sc_guard_run then runs the guarded part of the script as a BODY in a
# background subshell and waits for it, because bash runs a trap only after a foreground
# command (and a command substitution) ends, but at once during `wait`. From then on EVERY
# exit runs the guard: success, sc_die, an ERR exit, a timeout, a failed write, and the
# signals INT, TERM and HUP (exit 130, 143, 129), sent to the script or to its group. It:
#   0. ends the body's processes at once: every process whose environment holds this
#      run's random SC_GUARD_TOKEN (the body and all its descendants, whatever their
#      process group: `timeout` makes its own, and a reparented grandchild keeps it) gets
#      TERM, and any left after SC_GUARD_KILL_GRACE seconds (10) gets KILL; nothing else
#      is signalled. A cloud call already sent is not undone (the checks below report it);
#   1. checks the caller account itself when the script has not (finish: the body's
#      identity is not passed back); if it cannot, it makes NO further AWS call
#      (==> GUARD-FAILED), so it never acts on an unverified account;
#   2. lists the active EMR clusters whose Name matches SC_GUARD_CLUSTER_FILTER (a
#      JMESPath condition); with SC_GUARD_TERMINATE=true and a caller that is not the
#      deployment role (denied every EMR write) it terminates each and checks it is
#      TERMINATING; every other one is ==> CLUSTER-STILL-ACTIVE (with the reason) and
#      fails the run. With SC_GUARD_OWNED_ON_SUCCESS=true (pipeline start) a cluster after
#      a SUCCESSFUL exit belongs to the running execution and is ==> CLUSTER-ACTIVE;
#   3. scales the three ECS services to 0 tasks (minimum 0), except after a successful
#      exit with SC_GUARD_KEEP_ON_SUCCESS=true (--keep-services: ==> SERVICES-KEPT). For
#      each service the scaling-minimum call and the desired-count call are tried
#      independently, each up to SC_GUARD_ATTEMPTS (3) times (both are idempotent), so one
#      failed call never skips another call or another service;
#   4. exits with the original exit code; a successful run whose guard failed exits 1,
#      and the body's success line (sc_guard_set success) prints only when all succeeded.
# The body tells the guard what it learned with sc_guard_set: filter (narrow the cluster
# check), terminate (true: the run's end is confirmed), disarm (the guard makes no AWS
# call: finish saw the run still RUNNING) and success. Each step is checked on its own
# (errexit and the ERR trap off, INT, TERM, HUP and PIPE ignored while it runs, every AWS
# call under timeout), so a failing step never skips the next; each failure prints
# ==> GUARD-FAILED. A guard write to a closed reader fails silently and changes nothing.
SC_GUARD_ENV="" SC_GUARD_CLUSTER_FILTER="" SC_GUARD_TERMINATE=false
SC_GUARD_OWNED_ON_SUCCESS=false SC_GUARD_KEEP_ON_SUCCESS=false
SC_GUARD_NO_TERMINATE_REASON="this script does not terminate clusters"
SC_GUARD_STATE="" SC_GUARD_TOKEN="" SC_GUARD_BODY="" SC_GUARD_DISARMED="" SC_GUARD_SUCCESS_LINE=""
SC_GUARD_SERVICES=(query-service graph-service embedding-worker)

sc_guard_arm() {
  SC_GUARD_ENV="$1"
  SC_GUARD_CLUSTER_FILTER="$2"
  SC_GUARD_STATE="$(mktemp)"
  SC_GUARD_TOKEN="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
  [[ "$SC_GUARD_TOKEN" =~ ^[0-9a-f]{32}$ ]] || sc_die "could not make the exit guard token"
  trap 'sc_guard_exit "$?"' EXIT
  trap 'sc_guard_signal INT 130' INT
  trap 'sc_guard_signal TERM 143' TERM
  trap 'sc_guard_signal HUP 129' HUP
  trap '' PIPE
  sc_info "exit guard armed: every exit from here on runs the EMR cluster check and the ECS scale to zero"
}

# sc_guard_run FUNCTION: runs FUNCTION as the guarded body and exits with its status (the
# EXIT trap then runs the guard). Its stdin is /dev/null (no prompts); SIGPIPE stays
# ignored in it and in the commands it runs (sc_default_pipe restores it for a child).
sc_guard_run() {
  local rc=0
  (
    export SC_GUARD_TOKEN
    trap - INT TERM HUP
    "$@"
  ) </dev/null &
  SC_GUARD_BODY=$!
  wait "$SC_GUARD_BODY" || rc=$?
  exit "$rc"
}

# sc_guard_set KEY VALUE (in the body): see the keys above.
sc_guard_set() {
  if [[ -n "$SC_GUARD_STATE" ]]; then printf '%s=%s\n' "$1" "$2" >>"$SC_GUARD_STATE"; fi
}

# sc_default_pipe CMD...: runs CMD with the default SIGPIPE action (an ignored signal is
# inherited and a bash child cannot reset it), for the experiment entrypoint and sbt.
sc_default_pipe() { env --default-signal=PIPE "$@"; }

sc_guard_signal() {
  printf '==> [%s] SIGNAL %s in step=%s: running the exit guard\n' "$SC_NAME" "$1" "$SC_STEP" >&2 || true
  exit "$2"
}

sc_guard_fail() { printf '==> [%s] GUARD-FAILED: %s\n' "$SC_NAME" "$*" >&2; }

# The PIDs of every live process of this run's body (environment holds the token).
sc_guard_token_pids() {
  local f pid
  local -a files=()
  [[ -n "$SC_GUARD_TOKEN" ]] || return 0
  mapfile -t files < <(grep -l -s -z -x -F "SC_GUARD_TOKEN=$SC_GUARD_TOKEN" /proc/[0-9]*/environ)
  for f in "${files[@]}"; do
    pid="${f#/proc/}"
    pid="${pid%/environ}"
    if [[ "$pid" =~ ^[0-9]+$ && "$pid" != "$$" && "$pid" != "$BASHPID" ]]; then printf '%s\n' "$pid"; fi
  done
}

sc_guard_stop_body() {
  local i grace="${SC_GUARD_KILL_GRACE:-10}"
  local -a pids=()
  mapfile -t pids < <(sc_guard_token_pids)
  if [[ "${#pids[@]}" -gt 0 ]]; then
    printf '==> [%s] STOP: %s process(es) of this run still running: TERM now, KILL after %ss\n' "$SC_NAME" "${#pids[@]}" "$grace" >&2
    kill -TERM -- "${pids[@]}" 2>/dev/null
    for ((i = 0; i < grace * 10; i++)); do
      mapfile -t pids < <(sc_guard_token_pids)
      [[ "${#pids[@]}" -gt 0 ]] || break
      sleep 0.1
    done
    for i in 1 2; do
      mapfile -t pids < <(sc_guard_token_pids)
      [[ "${#pids[@]}" -gt 0 ]] || break
      printf '==> [%s] STOP: KILL %s process(es) that outlived TERM\n' "$SC_NAME" "${#pids[@]}" >&2
      kill -KILL -- "${pids[@]}" 2>/dev/null
      sleep 0.2
    done
    mapfile -t pids < <(sc_guard_token_pids)
    if [[ "${#pids[@]}" -gt 0 ]]; then sc_guard_fail "${#pids[@]} process(es) of this run could not be stopped: ${pids[*]}"; fi
  fi
  if [[ -n "$SC_GUARD_BODY" ]]; then wait "$SC_GUARD_BODY" 2>/dev/null; fi
}

sc_guard_load_state() {
  local line
  [[ -n "$SC_GUARD_STATE" && -f "$SC_GUARD_STATE" ]] || return 0
  while IFS= read -r line; do
    case "${line%%=*}" in
      filter) SC_GUARD_CLUSTER_FILTER="${line#*=}" ;;
      terminate) if [[ "${line#*=}" == true ]]; then SC_GUARD_TERMINATE=true; fi ;;
      disarm) SC_GUARD_DISARMED="${line#*=}" ;;
      success) SC_GUARD_SUCCESS_LINE="${line#*=}" ;;
      *) ;;
    esac
  done <"$SC_GUARD_STATE"
  rm -f "$SC_GUARD_STATE"
}

# The guard's own identity check (one attempt, never exits): sets SC_ACCOUNT_ID,
# SC_CALLER_ARN and SC_CALLER_SHOWN only when the account is a verified 12-digit ID.
sc_guard_identity() {
  local acct arn
  acct="$(sc_aws sts get-caller-identity --query Account --output text)" || return 1
  [[ "$acct" =~ ^[0-9]{12}$ ]] || return 1
  arn="$(sc_aws sts get-caller-identity --query Arn --output text)" || return 1
  SC_ACCOUNT_ID="$acct"
  SC_CALLER_ARN="$arn"
  if [[ "$arn" == *":assumed-role/"* ]]; then arn="${arn%/*}/<session>"; fi
  SC_CALLER_SHOWN="$(printf '%s' "$arn" | sc_redact)"
  # A failed write (closed reader) must not turn a verified identity into a failure.
  sc_info "exit guard caller: $SC_CALLER_SHOWN" || true
  return 0
}

# sc_guard_try CMD...: up to SC_GUARD_ATTEMPTS attempts, SC_GUARD_RETRY_DELAY s apart.
sc_guard_try() {
  local n max="${SC_GUARD_ATTEMPTS:-3}"
  for ((n = 1; n <= max; n++)); do
    if "$@"; then return 0; fi
    if [[ "$n" -lt "$max" ]]; then sleep "${SC_GUARD_RETRY_DELAY:-2}"; fi
  done
  return 1
}

sc_guard_clusters() {
  local out id state why
  local -a lines=()
  if ! out="$(sc_aws emr list-clusters --cluster-states STARTING BOOTSTRAPPING RUNNING WAITING \
    --query "Clusters[?$SC_GUARD_CLUSTER_FILTER].Id" --output text)"; then
    sc_guard_fail "could not list active EMR clusters ($SC_GUARD_CLUSTER_FILTER): one may still be billing; the human checks with administrator credentials"
    failed=1
  else
    mapfile -t lines < <(printf '%s\n' "$out" | tr '\t' '\n')
    for id in "${lines[@]}"; do
      if [[ "$id" =~ ^j-[A-Z0-9]+$ ]]; then
        ids+=("$id")
      elif [[ -n "$id" && "$id" != "None" ]]; then
        sc_guard_fail "unexpected EMR list output: $(printf '%s' "$id" | sc_redact_err)"
        failed=1
      fi
    done
  fi
  why="$SC_GUARD_NO_TERMINATE_REASON"
  if sc_is_deployment_role "$SC_GUARD_ENV"; then why="the deployment role may not terminate a cluster"; fi
  for id in "${ids[@]}"; do
    if [[ "$rc" -eq 0 && "$SC_GUARD_OWNED_ON_SUCCESS" == true ]]; then
      printf '==> CLUSTER-ACTIVE %s (the running execution owns it and terminates it; --finish checks it)\n' "$id"
      continue
    fi
    if [[ "$SC_GUARD_TERMINATE" != true ]] || sc_is_deployment_role "$SC_GUARD_ENV"; then
      still+=("$id") still_why+=("$why")
      continue
    fi
    if ! sc_aws emr terminate-clusters --cluster-ids "$id"; then
      still+=("$id") still_why+=("the terminate call failed")
      continue
    fi
    state="$(sc_aws emr describe-cluster --cluster-id "$id" --query Cluster.Status.State --output text)" || state="UNKNOWN"
    if [[ "$state" == TERMINATING || "$state" == TERMINATED ]]; then
      sc_ok "cluster $id is $state"
    else
      still+=("$id") still_why+=("still $state after the terminate call")
    fi
  done
  [[ "${#ids[@]}" -gt 0 || "$failed" -ne 0 ]] || sc_ok "no active EMR cluster ($SC_GUARD_CLUSTER_FILTER)"
}

sc_guard_scale_down() {
  local cluster svc reg upd
  if [[ "$rc" -eq 0 && "$SC_GUARD_KEEP_ON_SUCCESS" == true ]]; then
    printf '==> SERVICES-KEPT env=%s: the ECS services keep running (--keep-services); scale them to zero with --finish <run> (without --keep-services) when done\n' "$SC_GUARD_ENV"
    return 0
  fi
  if ! cluster="$(sc_ssm "/scholarchain/$SC_GUARD_ENV/app/ecs/cluster-name")" || [[ -z "$cluster" ]]; then
    sc_guard_fail "could not read the ECS cluster name: the services were NOT scaled to zero and may still be billing"
    failed=1
    return 0
  fi
  for svc in "${SC_GUARD_SERVICES[@]}"; do
    reg=ok upd=ok
    sc_guard_try sc_aws application-autoscaling register-scalable-target --service-namespace ecs \
      --scalable-dimension ecs:service:DesiredCount --resource-id "service/$cluster/$svc" \
      --min-capacity 0 --max-capacity 3 >/dev/null || reg=failed
    sc_guard_try sc_aws ecs update-service --cluster "$cluster" --service "$svc" --desired-count 0 \
      --query 'service.serviceName' --output text >/dev/null || upd=failed
    if [[ "$upd" == ok && "$reg" == ok ]]; then
      sc_ok "$svc scaled to 0"
    elif [[ "$upd" == ok ]]; then
      sc_guard_fail "$svc desired count is 0, but its scaling minimum could not be set to 0: autoscaling may start tasks again (billing)"
      failed=1
    else
      sc_guard_fail "$svc was NOT scaled to zero and may still be billing"
      failed=1
    fi
  done
  printf '==> DRIFT app stack: services scaled to 0 outside Terraform, the NAT gateway still bills; plan app with park_services=true to remove it and record the change, or deploy to restore the minimum\n'
}

sc_guard_exit() {
  local rc="$1" failed=0 id SC_AWS_TIMEOUT="${SC_GUARD_AWS_TIMEOUT:-60s}"
  local -a ids=() still=() still_why=()
  trap '' INT TERM HUP PIPE
  trap - ERR EXIT
  set +e
  SC_STEP="exit-guard"
  sc_guard_stop_body
  sc_guard_load_state
  if [[ -n "$SC_GUARD_DISARMED" ]]; then
    printf '==> [%s] EXIT-GUARD exit=%s guard=not-run (%s): no cluster check, no scale-down\n' "$SC_NAME" "$rc" "$SC_GUARD_DISARMED" >&2
    exit "$rc"
  fi
  printf '==> [%s] STEP exit-guard: exit=%s; EMR cluster check, then ECS scale to zero\n' "$SC_NAME" "$rc"

  if [[ ! "$SC_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] && ! sc_guard_identity; then
    sc_guard_fail "could not verify the caller account: NO EMR cluster check and NO ECS scale-down were made (never on an unverified account); services and clusters may still be billing: rerun the command, or the human checks with administrator credentials"
    failed=1
  else
    sc_guard_clusters
    sc_guard_scale_down
  fi

  for id in "${!still[@]}"; do
    printf '==> CLUSTER-STILL-ACTIVE %s (not terminated: %s; billing until terminated)\n' "${still[$id]}" "${still_why[$id]}" >&2
  done
  if [[ "${#still[@]}" -gt 0 ]]; then
    sc_guard_fail "${#still[@]} EMR cluster(s) still active; once the run has ended, the human runs, with administrator credentials: aws emr terminate-clusters --cluster-ids $(IFS=' ' && printf '%s' "${still[*]}")"
    failed=1
  fi

  if [[ "$rc" -eq 0 && "$failed" -ne 0 ]]; then rc=1; fi
  if [[ "$rc" -eq 0 && -n "$SC_GUARD_SUCCESS_LINE" ]]; then printf '%s\n' "$SC_GUARD_SUCCESS_LINE"; fi
  printf '==> [%s] EXIT-GUARD exit=%s guard=%s\n' "$SC_NAME" "$rc" "$([[ "$failed" -eq 0 ]] && printf ok || printf FAILED)" >&2
  exit "$rc"
}
