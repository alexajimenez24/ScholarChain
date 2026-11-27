#!/usr/bin/env bash
# scripts/run-experiment.sh: run ONE approved experiment manifest and record it.
#
# Usage:
#   scripts/run-experiment.sh --env dev --phase NN --manifest experiments/<id>/manifest.json
#                             [--dry-run]
#
# The manifest (JSON, written by experiment-engineer, committed):
#   {
#     "id": "<id>",                     the folder name: lowercase letters, digits and -
#     "description": "<text>",
#     "entrypoint": "<file>",           a bash script in experiments/<id>/, committed
#     "entrypointSha256": "<64 hex>",   its SHA-256, so the signed manifest pins the code
#     "args": ["<word>", ...],          each [A-Za-z0-9=._:/,@+-], at most 200 characters
#     "limits": {
#       "maxMinutes": 1..240,           hard timeout of the whole run
#       "maxEcsTasks": 0..3,            per service (the app stack's max_tasks)
#       "maxEmrInstances": 0..4,        SCALE_MAX, docs/agents.md section 6
#       "costEstimateUsd": <number>     must not exceed the gate's COST-ESTIMATE-USD
#     },
#     "metrics": ["<where the metrics are read, for example AWS/ApiGateway:Latency>", ...]
#   }
#
# It refuses unless ALL hold (the same approval design as deploy.sh):
#   - the source tree is clean and the manifest and its entrypoint are tracked by git,
#   - docs/agent-runs/phase-NN/human-gate.md approves HEAD (APPROVED-COMMIT), this
#     environment and COST-ESTIMATE-USD <= BUDGET-REMAINING-USD <= 25, AND holds an
#     APPROVED-EXPERIMENT-SHA256 line equal to the manifest file's SHA-256,
#   - the entrypoint's SHA-256 equals entrypointSha256 and the limits are within bounds,
#   - the caller is the deployment role (the budget deny action applies).
# Then it runs `timeout <maxMinutes>m bash experiments/<id>/<entrypoint> <args...>` with
# SCHOLARCHAIN_ENV, SCHOLARCHAIN_EXPERIMENT_ID, SCHOLARCHAIN_EXPERIMENT_RUN,
# SCHOLARCHAIN_EXPERIMENT_OUT (the output folder), SCHOLARCHAIN_EXPERIMENT_MAX_ECS_TASKS,
# SCHOLARCHAIN_EXPERIMENT_MAX_EMR_INSTANCES and AWS_REGION set. The entrypoint writes its
# raw results under SCHOLARCHAIN_EXPERIMENT_OUT; experiment-engineer copies them, as new
# files only, into experiments/<id>/raw/.
# Record (git-ignored, kept for failed runs too): target/experiments/<id>/<run>/
#   experiment-record.txt  id, manifest and entrypoint SHA-256, commit, Region, limits,
#                          start and end time, exit code, metric locations, output folder
#   console.log            the entrypoint's output, account ID redacted
# --dry-run: every local check above (as PASS or WOULD-REFUSE) and what would run; no
# AWS call, nothing is run. Exit 0 = local checks pass, 3 = a real run would refuse.
# Exit codes: 0 the experiment succeeded, 1 refused or the experiment failed (its record
# is kept), 2 usage. After the gates pass, every exit runs the exit guard (EMR cluster
# check, ECS scale to zero; see the identity step), and a failed guard turns 0 into 1.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,6p' "$0"; exit 2; }

ENV="" PHASE="" MANIFEST="" DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --phase) PHASE="${2:-}"; shift 2 ;;
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage ;;
    *) printf '==> [run-experiment] FAILED: unknown argument: %s\n' "$1" >&2; usage ;;
  esac
done
[[ -n "$ENV" && -n "$PHASE" && -n "$MANIFEST" ]] || usage
[[ "$ENV" =~ ^[a-z][a-z0-9]{1,7}$ ]] || sc_die "invalid environment name: $ENV"
sc_check_phase "$PHASE"
[[ "$MANIFEST" =~ ^experiments/([a-z0-9][a-z0-9-]{2,40})/manifest\.json$ ]] ||
  sc_die "--manifest must be experiments/<id>/manifest.json, relative to the repository root"
ID="${BASH_REMATCH[1]}"
[[ -f "$SC_ROOT/$MANIFEST" && ! -L "$SC_ROOT/$MANIFEST" ]] || sc_die "no manifest file $MANIFEST"
sc_need_tools git python3 sha256sum timeout sed awk

# Validates the manifest and prints: entrypoint, entrypointSha256, maxMinutes,
# maxEcsTasks, maxEmrInstances, costEstimateUsd, metrics (joined by ;), then one arg per
# line. Exit 1 with the reason on stderr when invalid.
read_manifest() {
  python3 -I -c 'import json, re, sys
path, ident = sys.argv[1], sys.argv[2]
def bad(msg):
    sys.stderr.write("==> [run-experiment] FAILED step=manifest: %s\n" % msg)
    sys.exit(1)
try:
    m = json.load(open(path, encoding="utf-8"))
except ValueError as e:
    bad("manifest is not JSON: %s" % e)
allowed = {"id", "description", "entrypoint", "entrypointSha256", "args", "limits", "metrics"}
if not isinstance(m, dict) or set(m) - allowed:
    bad("unknown manifest fields: %s" % sorted(set(m) - allowed if isinstance(m, dict) else []))
if m.get("id") != ident:
    bad("id must equal the folder name %s" % ident)
ep = m.get("entrypoint", "")
if not isinstance(ep, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}", ep):
    bad("entrypoint must be a file name inside experiments/%s/" % ident)
sha = m.get("entrypointSha256", "")
if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
    bad("entrypointSha256 must be 64 lowercase hex")
args = m.get("args", [])
if not isinstance(args, list) or any(not isinstance(a, str) or not re.fullmatch(r"[A-Za-z0-9=._:/,@+-]{0,200}", a) for a in args):
    bad("args must be a list of words of [A-Za-z0-9=._:/,@+-]")
lim = m.get("limits")
if not isinstance(lim, dict) or set(lim) != {"maxMinutes", "maxEcsTasks", "maxEmrInstances", "costEstimateUsd"}:
    bad("limits must hold exactly maxMinutes, maxEcsTasks, maxEmrInstances, costEstimateUsd")
def num(k, lo, hi, integer=True):
    v = lim[k]
    if isinstance(v, bool) or not isinstance(v, (int, float)) or (integer and not isinstance(v, int)) or not lo <= v <= hi:
        bad("limits.%s must be %s %s to %s" % (k, "an integer" if integer else "a number", lo, hi))
    return v
mm = num("maxMinutes", 1, 240)
ecs = num("maxEcsTasks", 0, 3)
emr = num("maxEmrInstances", 0, 4)
cost = num("costEstimateUsd", 0, 25, integer=False)
metrics = m.get("metrics", [])
if not isinstance(metrics, list) or any(not isinstance(x, str) or not re.fullmatch(r"[A-Za-z0-9/:._=-]{1,200}", x) for x in metrics):
    bad("metrics must be a list of metric locations")
print(ep); print(sha); print(mm); print(ecs); print(emr); print(cost); print(";".join(metrics) or "none")
for a in args:
    print(a)' "$SC_ROOT/$MANIFEST" "$ID"
}

MANIFEST_SHA="$(sha256sum -- "$SC_ROOT/$MANIFEST" | awk '{print $1}')"
mapfile -t M < <(read_manifest || true)
[[ "${#M[@]}" -ge 7 ]] || sc_die "the manifest $MANIFEST is invalid (reason above)"
ENTRY="experiments/$ID/${M[0]}" ENTRY_SHA="${M[1]}" MAX_MIN="${M[2]}" MAX_ECS="${M[3]}" MAX_EMR="${M[4]}" COST="${M[5]}" METRICS="${M[6]}"
ARGS=("${M[@]:7}")

tracked() {
  git -C "$SC_ROOT" ls-files --error-unmatch -- "$MANIFEST" "$ENTRY" >/dev/null 2>&1 ||
    sc_die "the manifest and its entrypoint must be committed ($MANIFEST, $ENTRY)"
}
entry_matches() {
  [[ -f "$SC_ROOT/$ENTRY" && ! -L "$SC_ROOT/$ENTRY" ]] || sc_die "no entrypoint file $ENTRY"
  [[ "$(sha256sum -- "$SC_ROOT/$ENTRY" | awk '{print $1}')" == "$ENTRY_SHA" ]] ||
    sc_die "$ENTRY does not match entrypointSha256"
}
experiment_signed() {
  local s
  sc_read_gate "$PHASE"
  sc_require_gate_matches "$ENV"
  for s in "${GATE_EXPERIMENT_SHAS[@]}"; do
    if [[ "$s" == "$MANIFEST_SHA" ]]; then
      awk -v c="$COST" -v g="$GATE_COST" 'BEGIN { exit !(c <= g) }' ||
        sc_die "the manifest's costEstimateUsd $COST exceeds the gate's COST-ESTIMATE-USD $GATE_COST"
      return 0
    fi
  done
  sc_die "manifest SHA-256 $MANIFEST_SHA is not an APPROVED-EXPERIMENT-SHA256 in phase-$PHASE/human-gate.md"
}

RUN="exp-$(date -u +%Y%m%dt%H%M%Sz)"
OUT="$SC_ROOT/target/experiments/$ID/$RUN"

if [[ "$DRY_RUN" == true ]]; then
  sc_step dry-run "experiment $ID manifest sha256=$MANIFEST_SHA (no AWS call, nothing run)"
  sc_ok "manifest is valid: entrypoint $ENTRY, maxMinutes=$MAX_MIN maxEcsTasks=$MAX_ECS maxEmrInstances=$MAX_EMR costEstimateUsd=$COST"
  sc_dry_check "manifest and entrypoint are committed" tracked
  sc_dry_check "entrypoint matches entrypointSha256" entry_matches
  sc_dry_check "source tree is clean" sc_require_clean_tree
  sc_dry_check "human gate phase-$PHASE approves HEAD, env $ENV, the cost and this manifest" experiment_signed
  sc_dry_aws "caller is assumed-role/scholarchain-$ENV-deployment"
  sc_would "run timeout ${MAX_MIN}m bash $ENTRY $(IFS=' ' && printf '%s' "${ARGS[*]:-}") with SCHOLARCHAIN_EXPERIMENT_OUT=${OUT#"$SC_ROOT"/}"
  sc_would "write ${OUT#"$SC_ROOT"/}/experiment-record.txt and console.log (kept for a failed run too); metric locations: $METRICS"
  sc_would "arm the exit guard after the deployment-role check: on EVERY exit (success, failure, timeout, a closed output reader, INT/TERM/HUP) end the running child commands, then list the active EMR clusters of $ENV (any found fails the run) and scale the three ECS services to 0, keeping the exit code"
  sc_dry_end
fi

sc_step preflight "experiment $ID"
tracked
entry_matches
sc_ok "manifest $MANIFEST sha256=$MANIFEST_SHA, entrypoint $ENTRY"

sc_step tree "refuse a dirty source tree"
sc_require_clean_tree
HEAD_SHA="$(sc_head)"

sc_step gate "the human gate approves this commit, environment, cost and manifest"
experiment_signed
sc_ok "APPROVED-EXPERIMENT-SHA256 $MANIFEST_SHA, cost $COST <= $GATE_COST"

sc_step identity "caller identity (the deployment role, so the budget deny action applies)"
sc_need_tools aws
sc_identity
sc_require_deployment_role "$ENV"

# The gates passed: from here EVERY exit (the experiment's success or failure, its
# timeout, a closed output reader, INT, TERM, HUP) runs the exit guard
# (scripts/lib/common.sh): it first ends the running entrypoint and every process it
# started (TERM, then KILL), then lists every active EMR cluster of the environment (the
# deployment role may not terminate one, so any found is ==> CLUSTER-STILL-ACTIVE and
# fails the run) and scales the three ECS services to zero, keeping the exit code.
SC_GUARD_TERMINATE=true
sc_guard_arm "$ENV" "starts_with(Name, 'scholarchain-$ENV-')"

# The guarded body (a background subshell; sc_guard_run). The entrypoint gets the default
# SIGPIPE action back (sc_default_pipe), so its own pipelines behave as usual; tee -p keeps
# writing console.log when the script's own output reader has closed.
# shellcheck disable=SC2329 # invoked through sc_guard_run
experiment_body() {
  local rc=0 START END
  sc_step run "timeout ${MAX_MIN}m bash $ENTRY"
  mkdir -p "$OUT"
  START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  trap - ERR
  set +e
  SCHOLARCHAIN_ENV="$ENV" SCHOLARCHAIN_EXPERIMENT_ID="$ID" SCHOLARCHAIN_EXPERIMENT_RUN="$RUN" \
    SCHOLARCHAIN_EXPERIMENT_OUT="$OUT" SCHOLARCHAIN_EXPERIMENT_MAX_ECS_TASKS="$MAX_ECS" \
    SCHOLARCHAIN_EXPERIMENT_MAX_EMR_INSTANCES="$MAX_EMR" \
    sc_default_pipe timeout --kill-after=60s "${MAX_MIN}m" bash "$SC_ROOT/$ENTRY" "${ARGS[@]}" 2>&1 | sc_redact | tee -p "$OUT/console.log"
  rc="${PIPESTATUS[0]}"
  set -e
  trap 'sc_on_error "$?" "$LINENO" "$BASH_COMMAND"' ERR
  END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  {
    printf 'experiment=%s\nrun=%s\nmanifest=%s\nmanifest-sha256=%s\nentrypoint=%s\nentrypoint-sha256=%s\n' \
      "$ID" "$RUN" "$MANIFEST" "$MANIFEST_SHA" "$ENTRY" "$ENTRY_SHA"
    printf 'commit=%s\nenv=%s\nphase=%s\nregion=%s\n' "$HEAD_SHA" "$ENV" "$PHASE" "$SC_REGION"
    printf 'limits maxMinutes=%s maxEcsTasks=%s maxEmrInstances=%s costEstimateUsd=%s gate-cost-usd=%s\n' \
      "$MAX_MIN" "$MAX_ECS" "$MAX_EMR" "$COST" "$GATE_COST"
    printf 'started=%s\nended=%s\nexit=%s\nmetrics=%s\noutput=%s\n' "$START" "$END" "$rc" "$METRICS" "${OUT#"$SC_ROOT"/}"
  } >"$OUT/experiment-record.txt"
  printf '==> EXPERIMENT id=%s run=%s commit=%s exit=%s record=%s\n' "$ID" "$RUN" "$HEAD_SHA" "$rc" "${OUT#"$SC_ROOT"/}/experiment-record.txt"
  [[ "$rc" -eq 0 ]] || sc_die "experiment $ID exited $rc (124 = timeout after ${MAX_MIN} minutes); its record is kept"
  sc_ok "experiment $ID finished"
}

sc_guard_run experiment_body
