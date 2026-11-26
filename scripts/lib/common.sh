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

sc_die() {
  printf '==> [%s] FAILED step=%s: %s\n' "$SC_NAME" "$SC_STEP" "$*" >&2
  exit 1
}

sc_on_error() {
  local code="$1" line="$2" cmd="$3"
  printf '==> [%s] FAILED step=%s line=%s exit=%s command: %s\n' \
    "$SC_NAME" "$SC_STEP" "$line" "$code" "$(printf '%s' "$cmd" | sc_redact)" >&2
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
  if grep -q '<' "$SC_INFRA/backend/$1.s3.tfbackend"; then
    sc_die "infra/backend/$1.s3.tfbackend still holds a placeholder; the human writes the state bucket name first"
  fi
}

sc_check_stack() {
  local s
  for s in "${SC_STACKS[@]}"; do
    if [[ "$s" == "$1" ]]; then return 0; fi
  done
  sc_die "unknown stack: $1 (expected one of: ${SC_STACKS[*]})"
}

sc_check_phase() {
  [[ "$1" =~ ^[0-9]{2}$ ]] || sc_die "invalid phase: $1 (two digits, for example 14)"
}

# ----- Redaction -----------------------------------------------------------------------
# Replaces the caller's account ID, and any 12-digit account field of an ARN or ECR host,
# with <account-id>. Reads stdin, writes stdout.
sc_redact() {
  if [[ -n "$SC_ACCOUNT_ID" ]]; then
    sed -E \
      -e "s/${SC_ACCOUNT_ID}/<account-id>/g" \
      -e 's/(arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:)[0-9]{12}(:|\/)/\1<account-id>\2/g' \
      -e 's/[0-9]{12}(\.dkr\.ecr\.)/<account-id>\1/g'
  else
    sed -E \
      -e 's/(arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:)[0-9]{12}(:|\/)/\1<account-id>\2/g' \
      -e 's/[0-9]{12}(\.dkr\.ecr\.)/<account-id>\1/g'
  fi
}

# ----- AWS (read calls unless the caller says otherwise) -------------------------------
sc_aws() { timeout "${SC_AWS_TIMEOUT:-120s}" aws --region "$SC_REGION" --no-cli-pager "$@"; }

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
  timeout "${SC_TF_TIMEOUT:-15m}" terraform -chdir="$SC_INFRA/stacks/$stack" "$@"
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
#   foundation enable_nat=true, data enable_neptune=true, compute enable_emr_endpoint=true.
sc_tf_plan_vars() {
  local env="$1" stack="$2" rev="$3" window="${4:-off}"
  printf '%s\n' "-var-file=../../environments/$env/$stack.json" "-var=revision=$rev"
  if [[ "$stack" == "app" ]]; then
    printf '%s\n' "-var=image_tag=$rev"
  fi
  if [[ "$window" == "on" ]]; then
    case "$stack" in
      foundation) printf '%s\n' "-var=enable_nat=true" ;;
      data) printf '%s\n' "-var=enable_neptune=true" ;;
      compute) printf '%s\n' "-var=enable_emr_endpoint=true" ;;
      *) ;;
    esac
  fi
}

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
  mapfile -t GATE_SHAS < <(sed -n 's/^APPROVED-PLAN-SHA256: *\([0-9a-f]\{64\}\) *$/\1/p' "$f")

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
