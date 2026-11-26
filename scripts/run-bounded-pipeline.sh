#!/usr/bin/env bash
# scripts/run-bounded-pipeline.sh: one bounded cloud pipeline run, in three separate
# invocations (no polling loop on the laptop):
#
#   start:  scripts/run-bounded-pipeline.sh --env dev --phase NN --snapshot YYYY-MM-DD
#             (--dblp-key <key in the raw bucket> | --dblp-file <local file>)
#             [--neptune-load true --neptune-subpath <path under the run's spark output>]
#   status: scripts/run-bounded-pipeline.sh --env dev --status <run label>
#   finish: scripts/run-bounded-pipeline.sh --env dev --finish <run label>
#             [--evidence-dir DIR] [--keep-services]
#
# start refuses unless: the caller is the deployment role (scholarchain-<env>-deployment,
# so the budget deny action can stop it; status and finish accept any caller, and only
# the human's administrator credentials may terminate a leftover cluster, because the
# deployment role holds no EMR write: repair round 3); the tree is clean (shared test in
# scripts/lib/common.sh: evidence exempt); phase-NN/human-gate.md approves HEAD and this
# environment with COST-ESTIMATE-USD <= BUDGET-REMAINING-USD; the deployed compute stack
# is at HEAD (its state machine matches the jars about to be uploaded); the NAT gateway
# and the EMR service endpoint are on; and, for a Neptune load, Neptune is enabled and
# the state machine was built against the current Neptune endpoint. The input file is
# bounded by --max-input-mb (default 1024); the 100,000-work bound is the prepared
# DBLP sample itself (dataset-engineer), not something this script can cut.
# It then builds the job jars (sbt packageJobs), uploads them with the EMR step helper to
# s3://<artifacts>/pipeline/<HEAD>/, and starts one execution. The cluster terminates
# itself on success and on every failure path (and after 30 idle minutes otherwise).
#
# finish (after the execution has ended): archives the execution description and
# history (account ID redacted) under target/pipeline-evidence/<run>/ (git-ignored), lists
# the clusters of this run that are still active and, unless the caller is the deployment
# role (denied every EMR write), terminates them and checks each is TERMINATING; then it
# scales the three ECS services to zero (unless --keep-services), printing the ==> DRIFT
# note. Any cluster left active is printed as ==> CLUSTER-STILL-ACTIVE and the run FAILS
# (exit 1, no PIPELINE-FINISHED line) with the terminate command for the human.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,12p' "$0"; exit 2; }

ENV="" PHASE="" SNAPSHOT="" DBLP_KEY="" DBLP_FILE="" NEPTUNE_LOAD=false NEPTUNE_SUBPATH=""
STATUS_ARN="" FINISH_ARN="" EVIDENCE_DIR="" KEEP_SERVICES=false MAX_MB=1024
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --phase) PHASE="${2:-}"; shift 2 ;;
    --snapshot) SNAPSHOT="${2:-}"; shift 2 ;;
    --dblp-key) DBLP_KEY="${2:-}"; shift 2 ;;
    --dblp-file) DBLP_FILE="${2:-}"; shift 2 ;;
    --neptune-load) NEPTUNE_LOAD="${2:-}"; shift 2 ;;
    --neptune-subpath) NEPTUNE_SUBPATH="${2:-}"; shift 2 ;;
    --max-input-mb) MAX_MB="${2:-}"; shift 2 ;;
    --status) STATUS_ARN="${2:-}"; shift 2 ;;
    --finish) FINISH_ARN="${2:-}"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="${2:-}"; shift 2 ;;
    --keep-services) KEEP_SERVICES=true; shift ;;
    -h | --help) usage ;;
    *) sc_die "unknown argument: $1" ;;
  esac
done
[[ -n "$ENV" ]] || usage
[[ "$ENV" =~ ^[a-z][a-z0-9]{1,7}$ ]] || sc_die "invalid environment name: $ENV"
NAME="scholarchain-$ENV"
label_ok() { [[ "$1" =~ ^run-[0-9]{8}t[0-9]{6}z-[0-9a-f]{7}$ ]]; }
# Execution ARN from the run label and the published state machine ARN (no account ID
# is ever typed or printed).
execution_arn() {
  local sm
  sm="$(sc_ssm "/scholarchain/$ENV/compute/pipeline/state-machine-arn")"
  printf '%s:%s' "${sm/:stateMachine:/:execution:}" "$1"
}

# ----- status: one read, no loop ---------------------------------------------------------
if [[ -n "$STATUS_ARN" ]]; then
  sc_need_tools aws timeout sed
  label_ok "$STATUS_ARN" || sc_die "--status takes the run label (run-YYYYMMDDtHHMMSSz-<sha7>)"
  sc_step status "one describe-execution call"
  sc_identity
  sc_aws stepfunctions describe-execution --execution-arn "$(execution_arn "$STATUS_ARN")" \
    --query '{status:status,startDate:startDate,stopDate:stopDate,error:error,cause:cause}' --output json | sc_redact
  exit 0
fi

# ----- finish: archive evidence, terminate leftovers, scale services to zero -------------
if [[ -n "$FINISH_ARN" ]]; then
  sc_need_tools aws timeout sed
  label_ok "$FINISH_ARN" || sc_die "--finish takes the run label (run-YYYYMMDDtHHMMSSz-<sha7>)"
  label="$FINISH_ARN"
  sc_step identity "caller identity"
  sc_identity
  FINISH_ARN="$(execution_arn "$label")"
  sc_step ended "the execution must have ended"
  st="$(sc_aws stepfunctions describe-execution --execution-arn "$FINISH_ARN" --query status --output text)"
  [[ "$st" != "RUNNING" ]] || sc_die "execution $label is still RUNNING; run --status later, then --finish"
  sc_ok "execution $label ended with $st"

  sc_step evidence "archive the execution description and history"
  EVIDENCE_DIR="${EVIDENCE_DIR:-$SC_ROOT/target/pipeline-evidence/$label}"
  mkdir -p "$EVIDENCE_DIR"
  sc_aws stepfunctions describe-execution --execution-arn "$FINISH_ARN" --output json | sc_redact >"$EVIDENCE_DIR/describe-execution.json"
  SC_AWS_TIMEOUT=300s sc_aws stepfunctions get-execution-history --execution-arn "$FINISH_ARN" --output json | sc_redact >"$EVIDENCE_DIR/execution-history.json"
  sc_ok "evidence in ${EVIDENCE_DIR/#$HOME/~}"

  # Repair round 3 (human, 2026-11-26): only the step-functions role (the state machine's
  # terminate states, plus the cluster's idle auto-termination) and the human's
  # administrator credentials may terminate a cluster; the deployment role is denied. A
  # failed list, a denied or failed terminate, or a cluster not TERMINATING afterwards is
  # reported as ==> CLUSTER-STILL-ACTIVE and fails the run after the scale-down below.
  sc_step leftovers "active clusters of this run"
  # Not --active, which also lists TERMINATING clusters (the state machine's own terminate).
  emr_out="$(sc_aws emr list-clusters --cluster-states STARTING BOOTSTRAPPING RUNNING WAITING \
    --query "Clusters[?Name=='$NAME-$label'].Id" --output text)" ||
    sc_die "could not list active EMR clusters: a cluster of $label may still be billing; the human checks and terminates it with administrator credentials"
  mapfile -t emr_lines < <(printf '%s\n' "$emr_out" | tr '\t' '\n')
  left=()
  for id in "${emr_lines[@]}"; do
    if [[ "$id" =~ ^j-[A-Z0-9]+$ ]]; then
      left+=("$id")
    elif [[ -n "$id" && "$id" != "None" ]]; then
      sc_die "unexpected EMR list output: $id"
    fi
  done
  still_active=()
  for id in "${left[@]}"; do
    if sc_is_deployment_role "$ENV"; then
      still_active+=("$id")
      continue
    fi
    if ! sc_aws emr terminate-clusters --cluster-ids "$id"; then
      still_active+=("$id")
      continue
    fi
    state="$(sc_aws emr describe-cluster --cluster-id "$id" --query Cluster.Status.State --output text)" || state="UNKNOWN"
    if [[ "$state" == TERMINATING || "$state" == TERMINATED ]]; then
      sc_ok "cluster $id is $state"
    else
      still_active+=("$id")
    fi
  done
  [[ "${#left[@]}" -gt 0 ]] || sc_ok "no active cluster for $label"
  # Printed now as well as at the end, so a failing scale-down cannot hide them.
  for id in "${still_active[@]}"; do
    printf '==> CLUSTER-STILL-ACTIVE %s run=%s (not terminated by this caller)\n' "$id" "$label" >&2
  done

  if [[ "$KEEP_SERVICES" == true ]]; then
    sc_skip "ECS scale to zero (--keep-services)"
  else
    sc_step scale-to-zero "the three ECS services to 0 tasks"
    cluster="$(sc_ssm "/scholarchain/$ENV/app/ecs/cluster-name")"
    for svc in query-service graph-service embedding-worker; do
      sc_aws application-autoscaling register-scalable-target --service-namespace ecs \
        --scalable-dimension ecs:service:DesiredCount --resource-id "service/$cluster/$svc" \
        --min-capacity 0 --max-capacity 3 >/dev/null
      sc_aws ecs update-service --cluster "$cluster" --service "$svc" --desired-count 0 \
        --query 'service.serviceName' --output text >/dev/null
      sc_ok "$svc scaled to 0"
    done
    printf '==> DRIFT app stack: services scaled to 0 outside Terraform; plan app with park_services=true to record it, or deploy to restore the minimum\n'
  fi
  if [[ "${#still_active[@]}" -gt 0 ]]; then
    for id in "${still_active[@]}"; do
      printf '==> CLUSTER-STILL-ACTIVE %s run=%s (billing until terminated)\n' "$id" "$label" >&2
    done
    sc_die "${#still_active[@]} cluster(s) of $label were NOT terminated by this caller; the human runs, with administrator credentials: aws emr terminate-clusters --cluster-ids ${still_active[*]}"
  fi
  printf '==> PIPELINE-FINISHED env=%s run=%s status=%s\n' "$ENV" "$label" "$st"
  exit 0
fi

# ----- start -----------------------------------------------------------------------------
[[ -n "$PHASE" && -n "$SNAPSHOT" ]] || usage
sc_step preflight "env=$ENV phase=$PHASE snapshot=$SNAPSHOT neptune-load=$NEPTUNE_LOAD"
sc_need_tools aws git sbt timeout sed awk stat
sc_check_phase "$PHASE"
[[ "$SNAPSHOT" =~ ^20[0-9]{2}-[01][0-9]-[0-3][0-9]$ ]] || sc_die "--snapshot must be YYYY-MM-DD"
[[ "$NEPTUNE_LOAD" == true || "$NEPTUNE_LOAD" == false ]] || sc_die "--neptune-load must be true or false"
[[ "$MAX_MB" =~ ^[0-9]{1,5}$ ]] || sc_die "--max-input-mb must be a number"
if [[ -n "$DBLP_KEY" && -n "$DBLP_FILE" ]] || [[ -z "$DBLP_KEY" && -z "$DBLP_FILE" ]]; then
  sc_die "give exactly one of --dblp-key or --dblp-file"
fi
if [[ "$NEPTUNE_LOAD" == true ]]; then
  [[ "$NEPTUNE_SUBPATH" =~ ^[A-Za-z0-9=._-][A-Za-z0-9=._/-]*$ ]] || sc_die "--neptune-load true needs --neptune-subpath (letters, digits, =._/-)"
fi

sc_step tree "refuse a dirty source tree"
sc_require_clean_tree
HEAD_SHA="$(sc_head)"

sc_step gate "the human gate approves this commit, environment and cost"
sc_read_gate "$PHASE"
sc_require_gate_matches "$ENV"

sc_step identity "caller identity (the deployment role, so the budget deny action applies)"
sc_identity
sc_require_deployment_role "$ENV"

sc_step preconditions "deployed revision, egress, EMR endpoint, Neptune"
c_rev="$(sc_ssm "/scholarchain/$ENV/compute/deploy/revision")"
[[ "$c_rev" == "$HEAD_SHA" ]] || sc_die "compute stack is at $c_rev, HEAD is $HEAD_SHA: deploy compute first"
[[ "$(sc_ssm "/scholarchain/$ENV/foundation/network/nat-enabled")" == "true" ]] || sc_die "NAT is off: plan and deploy foundation with enable_nat=true for the window"
[[ "$(sc_ssm "/scholarchain/$ENV/compute/emr/endpoint-enabled")" == "true" ]] || sc_die "the EMR service endpoint is off: plan and deploy compute with enable_emr_endpoint=true"
if [[ "$NEPTUNE_LOAD" == true ]]; then
  [[ "$(sc_ssm "/scholarchain/$ENV/data/neptune/enabled")" == "true" ]] || sc_die "Neptune is off: plan and deploy data with enable_neptune=true"
  [[ "$(sc_ssm "/scholarchain/$ENV/compute/pipeline/neptune-endpoint")" == "$(sc_ssm "/scholarchain/$ENV/data/neptune/endpoint")" ]] ||
    sc_die "the state machine was built against another Neptune endpoint: re-plan and deploy compute"
fi
artifacts="$(sc_ssm "/scholarchain/$ENV/data/buckets/artifacts")"
raw="$(sc_ssm "/scholarchain/$ENV/data/buckets/raw")"
graph="$(sc_ssm "/scholarchain/$ENV/data/buckets/graph")"
sm="$(sc_ssm "/scholarchain/$ENV/compute/pipeline/state-machine-arn")"
sc_ok "preconditions hold"

sc_step input "bounded DBLP input in the raw bucket"
if [[ -n "$DBLP_FILE" ]]; then
  [[ -f "$DBLP_FILE" ]] || sc_die "no such file: $DBLP_FILE"
  size_mb=$(($(stat -c %s "$DBLP_FILE") / 1048576))
  [[ "$size_mb" -le "$MAX_MB" ]] || sc_die "input is ${size_mb} MB, above --max-input-mb $MAX_MB"
  base="$(basename "$DBLP_FILE")"
  [[ "$base" =~ ^[A-Za-z0-9._-]+$ ]] || sc_die "input file name must use letters, digits and ._- only"
  DBLP_KEY="dblp/snapshot=$SNAPSHOT/$base"
  SC_AWS_TIMEOUT=60m sc_aws s3 cp "$DBLP_FILE" "s3://$raw/$DBLP_KEY" --only-show-errors
  sc_ok "uploaded the input as s3://$raw/$DBLP_KEY"
fi
[[ "$DBLP_KEY" =~ ^[A-Za-z0-9=._/-]+$ ]] || sc_die "invalid --dblp-key"
bytes="$(sc_aws s3api head-object --bucket "$raw" --key "$DBLP_KEY" --query ContentLength --output text)"
[[ $((bytes / 1048576)) -le "$MAX_MB" ]] || sc_die "s3://$raw/$DBLP_KEY is above --max-input-mb $MAX_MB"
sc_ok "input s3://$raw/$DBLP_KEY ($((bytes / 1048576)) MB)"

sc_step package "sbt packageJobs (job jars only)"
(cd "$SC_ROOT" && timeout 45m sbt -batch packageJobs)
pick() {
  local -a m
  mapfile -t m < <(find "$SC_ROOT/target/jobs" -maxdepth 1 -name "$1" -type f)
  [[ "${#m[@]}" -eq 1 ]] || sc_die "expected one $1 in target/jobs, found ${#m[@]}"
  printf '%s' "${m[0]}"
}
jar_hadoop="$(pick 'hadoop-*.jar')"
jar_er="$(pick 'entityResolution-*.jar')"
jar_spark="$(pick 'sparkAnalytics-*.jar')"

sc_step upload "jars and the EMR step helper to s3://$artifacts/pipeline/$HEAD_SHA/"
prefix="s3://$artifacts/pipeline/$HEAD_SHA"
SC_AWS_TIMEOUT=30m sc_aws s3 cp "$jar_hadoop" "$prefix/jobs/hadoop.jar" --only-show-errors
SC_AWS_TIMEOUT=30m sc_aws s3 cp "$jar_er" "$prefix/jobs/entity-resolution.jar" --only-show-errors
SC_AWS_TIMEOUT=30m sc_aws s3 cp "$jar_spark" "$prefix/jobs/spark.jar" --only-show-errors
sc_aws s3 cp "$SC_ROOT/scripts/emr/scholarchain-step.sh" "$prefix/emr/scholarchain-step.sh" --only-show-errors
sc_ok "artifacts uploaded"

sc_step start "one execution of the pipeline"
label="run-$(date -u +%Y%m%dt%H%M%Sz)-${HEAD_SHA:0:7}"
neptune_source=""
if [[ "$NEPTUNE_LOAD" == true ]]; then
  neptune_source="s3://$graph/runs/$label/spark/$NEPTUNE_SUBPATH"
fi
input="$(printf '{"runLabel":"%s","revision":"%s","snapshot":"%s","dblpUri":"s3a://%s/%s","neptuneLoad":%s,"neptuneSource":"%s"}' \
  "$label" "$HEAD_SHA" "$SNAPSHOT" "$raw" "$DBLP_KEY" "$NEPTUNE_LOAD" "$neptune_source")"
exec_arn="$(sc_aws stepfunctions start-execution --state-machine-arn "$sm" --name "$label" \
  --input "$input" --query executionArn --output text)"
printf '==> PIPELINE-STARTED env=%s run=%s commit=%s cost-estimate-usd=%s\n' "$ENV" "$label" "$HEAD_SHA" "$GATE_COST"
printf '==> EXECUTION %s\n' "$(printf '%s' "$exec_arn" | sc_redact)"
printf '==> NEXT scripts/run-bounded-pipeline.sh --env %s --status %s; when it has ended: --finish %s\n' "$ENV" "$label" "$label"
