#!/usr/bin/env bash
# scripts/run-bounded-pipeline.sh: one bounded cloud pipeline run, in three separate
# invocations (no polling loop on the laptop):
#
#   start:  scripts/run-bounded-pipeline.sh --env dev --phase NN
#             --snapshot-dir datasets/samples/local/<snapshot label>
#             --embed-units 0|all|N [--neptune-load true|false] [--max-input-mb N]
#             [--keep-services] [--dry-run]
#   status: scripts/run-bounded-pipeline.sh --env dev --status <run label>
#   finish: scripts/run-bounded-pipeline.sh --env dev --finish <run label>
#             [--evidence-dir DIR] [--keep-services] [--dry-run]
#
# The snapshot: a local dataset snapshot made by dataset-engineer (git-ignored), whose
# folder name is its label (YYYY-MM-DD or YYYY-MM-DD-<suffix>, for example
# 2026-11-24-25k) and which holds dblp-sample.xml, openalex-works.jsonl,
# crossref-works.jsonl and retrieval.json (dblp.dtd too, if present). Each file's SHA-256
# and size must equal its retrieval.json entry. start uploads them, create-only, to the
# raw prefixes of datasets/dataset-manifest.yaml:
#   s3://<raw>/dblp/snapshot_date=<label>/{dblp-sample.xml,dblp.dtd,retrieval.json}
#   s3://<raw>/openalex/snapshot_date=<label>/openalex-works.jsonl
#   s3://<raw>/crossref/snapshot_date=<label>/crossref-works.jsonl
# (put-object --if-none-match: an existing object is never overwritten; one with the
# same recorded SHA-256 is kept and the retry goes on, any other stops the run).
# The Hadoop job gets --snapshot <first 10 characters of the label>.
# --embed-units: how many semantic units per document type the pipeline enqueues for the
#   embedding worker (each costs Bedrock and S3 Vectors calls): 0 skips the enqueue, N
#   enqueues at most N per type, all enqueues every unit. Required, no default.
# --neptune-load true: the pipeline writes the Neptune bulk-load files and runs the bulk
#   load (Neptune must be enabled). Default false.
#
# start refuses unless: the caller is the deployment role (scholarchain-<env>-deployment,
# so the budget deny action can stop it; status and finish accept any caller, and only
# the human's administrator credentials may terminate a leftover cluster, because the
# deployment role holds no EMR write: repair round 3); the tree is clean (shared test in
# scripts/lib/common.sh: evidence exempt); phase-NN/human-gate.md approves HEAD and this
# environment with COST-ESTIMATE-USD <= BUDGET-REMAINING-USD; the deployed compute stack
# is at HEAD (its state machine matches the jars about to be uploaded); the pipeline's
# interface endpoints (EMR service and SQS) are on; and, for a Neptune load, Neptune is
# enabled and the state machine was built against the current Neptune endpoint. The
# upload is bounded by --max-input-mb (default 1024, all files together); the
# 100,000-work bound is the prepared snapshot itself (dataset-engineer).
# It then builds the jars once (scripts/package.sh --jars-only: the three job jars and the
# query-service assembly, which carries the storage loaders), uploads them as
# s3://<artifacts>/pipeline/<HEAD>/jobs/{hadoop,entity-resolution,spark,loaders}.jar with
# the EMR step helper, and starts one execution. The cluster terminates itself on success
# and on every failure path (and after 30 idle minutes otherwise).
# Record: target/pipeline-evidence/<run>/run-start.txt (snapshot label and files with
# SHA-256, raw URIs, jar SHA-256, configuration, output locations, execution ARN with
# the account ID redacted).
#
# Exit guard (Phase 13 MINOR-2 and repair-03, scripts/lib/common.sh sc_guard_*): once
# start has passed its gates (clean tree, human gate, deployment role) and once finish has
# checked its label (before its first AWS call), EVERY exit (success, failure, timeout, a
# closed output reader, INT, TERM, HUP) first ends the running child commands (TERM, then
# KILL), then runs the EMR cluster check and scales the three ECS services to zero,
# keeping the exit code. finish terminates a cluster only after it confirmed the
# execution ended; if that is unknown (an early call failed) it only reports the
# clusters, and if the execution is RUNNING it refuses with no cluster or ECS call. start
# runs as the deployment role, which may not terminate a cluster: after a failed start any
# cluster of the run is ==> CLUSTER-STILL-ACTIVE (for the human); after a successful
# start it is ==> CLUSTER-ACTIVE (the running execution terminates it). Because start
# also scales ECS to zero on success, queued embedding jobs wait in SQS until the
# services run again; --keep-services keeps them after a SUCCESSFUL start or finish only
# (==> SERVICES-KEPT), never after a failure. Dry runs, usage errors and refusals before
# the gates make no AWS call and do not arm it.
#
# finish (after the execution has ended): archives the execution description and
# history (account ID redacted) under target/pipeline-evidence/<run>/ (git-ignored), lists
# the run manifests the jobs wrote (run IDs) and prints the Spark one as
# ==> SERVING-MANIFEST (the --serving-manifest of the next app plan), lists the clusters
# of this run that are still active and, unless the caller is the deployment role
# (denied every EMR write), terminates them and checks each is TERMINATING; then it
# scales the three ECS services to zero (unless --keep-services), printing the ==> DRIFT
# note. Any cluster left active is printed as ==> CLUSTER-STILL-ACTIVE and the run FAILS
# (exit 1, no PIPELINE-FINISHED line) with the terminate command for the human.
#
# --dry-run (start or finish): the local checks (arguments, snapshot files against
# retrieval.json, size bound, clean tree, the human gate) as PASS or WOULD-REFUSE, and
# what a real run would do; no AWS call, no build, no upload. Exit 0 = local checks
# pass, 3 = a real run would refuse. Real run exit codes: 0 done, 1 refused or failed,
# 2 usage.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,12p' "$0"; exit 2; }

ENV="" PHASE="" SNAPSHOT_DIR="" EMBED_UNITS="" NEPTUNE_LOAD=false DRY_RUN=false
STATUS_ARN="" FINISH_ARN="" EVIDENCE_DIR="" KEEP_SERVICES=false MAX_MB=1024
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --phase) PHASE="${2:-}"; shift 2 ;;
    --snapshot-dir) SNAPSHOT_DIR="${2:-}"; shift 2 ;;
    --embed-units) EMBED_UNITS="${2:-}"; shift 2 ;;
    --neptune-load) NEPTUNE_LOAD="${2:-}"; shift 2 ;;
    --max-input-mb) MAX_MB="${2:-}"; shift 2 ;;
    --status) STATUS_ARN="${2:-}"; shift 2 ;;
    --finish) FINISH_ARN="${2:-}"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="${2:-}"; shift 2 ;;
    --keep-services) KEEP_SERVICES=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage ;;
    *) printf '==> [run-bounded-pipeline] FAILED: unknown argument: %s\n' "$1" >&2; usage ;;
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
  if [[ "$DRY_RUN" == true ]]; then
    sc_would "one stepfunctions describe-execution of $STATUS_ARN (read only)"
    sc_dry_end
  fi
  sc_step status "one describe-execution call"
  sc_identity
  sc_aws stepfunctions describe-execution --execution-arn "$(execution_arn "$STATUS_ARN")" \
    --query '{status:status,startDate:startDate,stopDate:stopDate,error:error,cause:cause}' --output json | sc_redact
  exit 0
fi

# The guarded body of finish (a background subshell; scripts/lib/common.sh sc_guard_run).
# shellcheck disable=SC2329 # invoked through sc_guard_run
finish_body() {
  local st graph curated loc b p serving
  sc_step identity "caller identity"
  sc_identity
  FINISH_ARN="$(execution_arn "$label")"
  sc_step ended "the execution must have ended"
  st="$(sc_aws stepfunctions describe-execution --execution-arn "$FINISH_ARN" --query status --output text)"
  if [[ "$st" == "RUNNING" ]]; then
    sc_guard_set disarm "execution $label is still RUNNING"
    sc_die "execution $label is still RUNNING; run --status later, then --finish"
  fi
  sc_ok "execution $label ended with $st"
  # The end is confirmed: from here the guard terminates this run's leftover clusters
  # (not as the deployment role, which is denied every EMR write: repair round 3).
  sc_guard_set terminate true
  sc_guard_set success "==> PIPELINE-FINISHED env=$ENV run=$label status=$st"

  sc_step evidence "archive the execution description and history"
  mkdir -p "$EVIDENCE_DIR"
  sc_aws stepfunctions describe-execution --execution-arn "$FINISH_ARN" --output json | sc_redact >"$EVIDENCE_DIR/describe-execution.json"
  SC_AWS_TIMEOUT=300s sc_aws stepfunctions get-execution-history --execution-arn "$FINISH_ARN" --output json | sc_redact >"$EVIDENCE_DIR/execution-history.json"
  sc_ok "evidence in ${EVIDENCE_DIR/#"$HOME"/\~}"

  sc_step run-manifests "run IDs: the run manifests the jobs wrote"
  graph="$(sc_ssm "/scholarchain/$ENV/data/buckets/graph")"
  curated="$(sc_ssm "/scholarchain/$ENV/data/buckets/curated")"
  {
    for loc in "$curated runs/$label/hadoop/run-manifests/" "$curated runs/$label/er/run-manifests/" "$graph runs/$label/spark/run-manifests/"; do
      b="${loc%% *}" p="${loc#* }"
      sc_aws s3api list-objects-v2 --bucket "$b" --prefix "$p" --query 'Contents[].Key' --output text |
        tr '\t' '\n' | grep -v -E '^(None)?$' | sed "s|^|s3://$b/|" || true
    done
  } >"$EVIDENCE_DIR/run-manifests.txt"
  sed 's/^/    /' "$EVIDENCE_DIR/run-manifests.txt"
  serving="$(grep -E "^s3://$graph/runs/$label/spark/run-manifests/run-[0-9a-f]+\.json$" "$EVIDENCE_DIR/run-manifests.txt" || true)"
  if [[ -n "$serving" && "$(printf '%s\n' "$serving" | wc -l)" -eq 1 ]]; then
    printf '==> SERVING-MANIFEST %s\n' "$serving"
  else
    sc_info "no single Spark run manifest for $label: nothing to serve from this run"
  fi

  # Repair round 3 (human, 2026-11-26): only the step-functions role (the state machine's
  # terminate states, plus the cluster's idle auto-termination) and the human's
  # administrator credentials may terminate a cluster; the deployment role is denied.
  # The exit guard lists this run's active clusters (not --active, which also lists
  # TERMINATING ones), terminates them unless the caller is the deployment role, reports
  # any left as ==> CLUSTER-STILL-ACTIVE and fails the run; then it scales the services
  # to zero (unless --keep-services and everything succeeded) and prints
  # ==> PIPELINE-FINISHED only when all of that succeeded.
  sc_step leftovers "the exit guard checks the clusters of $label and scales ECS to zero"
}

# ----- finish: archive evidence, terminate leftovers, scale services to zero -------------
if [[ -n "$FINISH_ARN" ]]; then
  sc_need_tools aws timeout sed
  label_ok "$FINISH_ARN" || sc_die "--finish takes the run label (run-YYYYMMDDtHHMMSSz-<sha7>)"
  label="$FINISH_ARN"
  EVIDENCE_DIR="${EVIDENCE_DIR:-$SC_ROOT/target/pipeline-evidence/$label}"
  if [[ "$DRY_RUN" == true ]]; then
    sc_step dry-run "finish $label (no AWS call)"
    sc_dry_aws "the execution $label has ended (describe-execution)"
    sc_would "save describe-execution.json, execution-history.json and run-manifests.txt (account ID redacted) under ${EVIDENCE_DIR/#"$HOME"/\~}"
    sc_would "print the Spark run manifest as ==> SERVING-MANIFEST (for plan.sh --stack app --serving-manifest)"
    sc_would "terminate any cluster named $NAME-$label still STARTING/BOOTSTRAPPING/RUNNING/WAITING (not as the deployment role), then check it is TERMINATING"
    sc_would "arm the exit guard before the first AWS call: on EVERY exit (success, failure, timeout, a closed output reader, INT/TERM/HUP) end the running child commands, then run the cluster check (report only until the execution's end is confirmed) and the scale to zero below, keeping the exit code; an execution still RUNNING is refused with no cluster or ECS call"
    if [[ "$KEEP_SERVICES" == true ]]; then
      sc_would "keep the ECS services running after a SUCCESSFUL finish (--keep-services); a failed finish still scales them to zero"
    else
      sc_would "scale query-service, graph-service and embedding-worker to 0 tasks (minimum 0) and print ==> DRIFT"
    fi
    sc_dry_end
  fi
  # Repair-03 (human): the guard is armed BEFORE the first AWS call, so a failed, timed-out
  # or interrupted identity, SSM or describe-execution call still runs the cluster check
  # and the scale to zero. Until the body confirms the execution has ended, the cluster
  # check only REPORTS this run's clusters (a run not known to have ended is never
  # terminated) and the services are scaled to zero. When describe-execution says
  # RUNNING, finish refuses and the guard makes no AWS call (accepted by the human).
  SC_GUARD_TERMINATE=false
  SC_GUARD_NO_TERMINATE_REASON="the end of $label was not confirmed: check --status first"
  SC_GUARD_KEEP_ON_SUCCESS="$KEEP_SERVICES"
  sc_guard_arm "$ENV" "Name=='$NAME-$label'"
  sc_guard_run finish_body
fi

# ----- start -----------------------------------------------------------------------------
[[ -n "$PHASE" && -n "$SNAPSHOT_DIR" && -n "$EMBED_UNITS" ]] || usage
sc_check_phase "$PHASE"
[[ "$NEPTUNE_LOAD" == true || "$NEPTUNE_LOAD" == false ]] || sc_die "--neptune-load must be true or false"
[[ "$EMBED_UNITS" == 0 || "$EMBED_UNITS" == all || "$EMBED_UNITS" =~ ^[1-9][0-9]{0,5}$ ]] ||
  sc_die "--embed-units must be 0, all or 1 to 999999"
[[ "$MAX_MB" =~ ^[1-9][0-9]{0,4}$ ]] || sc_die "--max-input-mb must be a number"
[[ -d "$SNAPSHOT_DIR" ]] || sc_die "no such snapshot folder: $SNAPSHOT_DIR"
SNAPSHOT_DIR="$(cd "$SNAPSHOT_DIR" && pwd -P)"
LABEL="$(basename "$SNAPSHOT_DIR")"
[[ "$LABEL" =~ ^20[0-9]{2}-[01][0-9]-[0-3][0-9](-[a-z0-9]{1,16})?$ ]] ||
  sc_die "the snapshot folder name must be its label, YYYY-MM-DD or YYYY-MM-DD-<suffix> (got $LABEL)"
SNAPSHOT="${LABEL:0:10}"

# source => file name in the folder (retrieval.json key, file, raw prefix); dtd optional.
declare -A SRC_FILE=([dblp]=dblp-sample.xml [openalex]=openalex-works.jsonl [crossref]=crossref-works.jsonl [dblp-dtd]=dblp.dtd)
declare -A SRC_PREFIX=([dblp]=dblp [openalex]=openalex [crossref]=crossref [dblp-dtd]=dblp)
SOURCES=(dblp openalex crossref)
if [[ -f "$SNAPSHOT_DIR/dblp.dtd" ]]; then SOURCES+=(dblp-dtd); fi
declare -A SUM=()

# Every snapshot file matches its retrieval.json entry (SHA-256 and size), and the total
# is within --max-input-mb. Sets SUM.
verify_snapshot() {
  local s f want got total=0 bytes
  [[ -f "$SNAPSHOT_DIR/retrieval.json" ]] || sc_die "no retrieval.json in $LABEL (the provenance record of the snapshot)"
  for s in "${SOURCES[@]}"; do
    f="$SNAPSHOT_DIR/${SRC_FILE[$s]}"
    [[ -f "$f" && ! -L "$f" ]] || sc_die "$LABEL has no regular file ${SRC_FILE[$s]}"
    want="$(python3 -I -c 'import json, sys
e = json.load(open(sys.argv[1], encoding="utf-8")).get(sys.argv[2], {})
print("%s %s %s" % (e.get("file", ""), e.get("sha256", ""), e.get("bytes", "")))' "$SNAPSHOT_DIR/retrieval.json" "$s")"
    bytes="$(stat -c %s -- "$f")"
    got="$(sha256sum -- "$f" | awk '{print $1}')"
    [[ "$want" == "${SRC_FILE[$s]} $got $bytes" ]] ||
      sc_die "${SRC_FILE[$s]} does not match retrieval.json ($s): file, sha256 or bytes differ"
    SUM[$s]="$got"
    total=$((total + bytes))
  done
  [[ $((total / 1048576)) -le "$MAX_MB" ]] || sc_die "the snapshot is $((total / 1048576)) MB, above --max-input-mb $MAX_MB"
  sc_ok "snapshot $LABEL: ${#SOURCES[@]} files match retrieval.json, $((total / 1048576)) MB in total"
}

if [[ "$DRY_RUN" == true ]]; then
  sc_step dry-run "start env=$ENV phase=$PHASE snapshot=$LABEL embed-units=$EMBED_UNITS neptune-load=$NEPTUNE_LOAD (no AWS call, no build, no upload)"
  sc_dry_check "tools (aws git sbt python3)" sc_need_tools aws git sbt java timeout sed awk stat sha256sum python3
  sc_dry_check "snapshot files match retrieval.json and fit --max-input-mb $MAX_MB" verify_snapshot
  sc_dry_check "source tree is clean" sc_require_clean_tree
  HEAD_SHA="$(sc_head)"
  sc_dry_check "human gate phase-$PHASE approves HEAD $HEAD_SHA and env $ENV within budget" sc_gate_for "$PHASE" "$ENV"
  sc_dry_aws "caller is assumed-role/scholarchain-$ENV-deployment"
  sc_dry_aws "compute deploy/revision == HEAD; emr/endpoint-enabled and sqs/endpoint-enabled are true"
  if [[ "$NEPTUNE_LOAD" == true ]]; then
    sc_dry_aws "Neptune is enabled and the state machine was built against its current endpoint"
  fi
  for s in "${SOURCES[@]}"; do
    sc_would "upload ${SRC_FILE[$s]} create-only to s3://<raw bucket>/${SRC_PREFIX[$s]}/snapshot_date=$LABEL/${SRC_FILE[$s]} (metadata sha256)"
  done
  sc_would "upload retrieval.json create-only to s3://<raw bucket>/dblp/snapshot_date=$LABEL/retrieval.json"
  sc_would "scripts/package.sh --jars-only (one sbt run), then upload hadoop.jar, entity-resolution.jar, spark.jar, loaders.jar and emr/scholarchain-step.sh to s3://<artifacts bucket>/pipeline/$HEAD_SHA/"
  sc_would "start one execution run-<UTC time>-${HEAD_SHA:0:7} with snapshot=$SNAPSHOT neptuneLoad=$NEPTUNE_LOAD embedUnits=$EMBED_UNITS: Hadoop, entity resolution, Spark, $([[ "$NEPTUNE_LOAD" == true ]] && printf 'neptune-load, ')load-dynamo$([[ "$EMBED_UNITS" != 0 ]] && printf ', enqueue-embeddings'), then terminate the cluster"
  sc_would "write target/pipeline-evidence/<run>/run-start.txt and print ==> PIPELINE-STARTED and the --status/--finish commands"
  sc_would "arm the exit guard after the deployment-role check: on EVERY exit (success, failure, timeout, a closed output reader, INT/TERM/HUP) end the running child commands, then list the run's active EMR clusters (report only: the deployment role may not terminate) and scale the three ECS services to 0, keeping the exit code"
  if [[ "$KEEP_SERVICES" == true ]]; then
    sc_would "keep the ECS services running after a SUCCESSFUL start only (--keep-services); a failed start still scales them to zero"
  elif [[ "$EMBED_UNITS" != 0 ]]; then
    sc_would "scale the embedding worker to 0 when start exits: the enqueued embedding jobs wait in SQS until the services run again (pass --keep-services to keep them)"
  fi
  sc_dry_end
fi

sc_step preflight "env=$ENV phase=$PHASE snapshot=$LABEL embed-units=$EMBED_UNITS neptune-load=$NEPTUNE_LOAD"
sc_need_tools aws git sbt java timeout sed awk stat sha256sum python3
verify_snapshot

sc_step tree "refuse a dirty source tree"
sc_require_clean_tree
HEAD_SHA="$(sc_head)"

sc_step gate "the human gate approves this commit, environment and cost"
sc_read_gate "$PHASE"
sc_require_gate_matches "$ENV"

sc_step identity "caller identity (the deployment role, so the budget deny action applies)"
sc_identity
sc_require_deployment_role "$ENV"

# The gates passed: from here every exit runs the cluster check (report only) and the ECS
# scale to zero. Until the run label exists no cluster of this run can exist, so the
# check lists every pipeline cluster of the environment.
SC_GUARD_OWNED_ON_SUCCESS=true
SC_GUARD_KEEP_ON_SUCCESS="$KEEP_SERVICES"
sc_guard_arm "$ENV" "starts_with(Name, '$NAME-run-')"
if [[ "$KEEP_SERVICES" != true && "$EMBED_UNITS" != 0 ]]; then
  sc_info "the embedding worker is scaled to 0 when start exits: the enqueued embedding jobs wait in SQS until the services run again (--keep-services keeps them)"
fi

# The guarded body of start (a background subshell; scripts/lib/common.sh sc_guard_run).
# shellcheck disable=SC2329 # invoked through sc_guard_run
start_body() {
  sc_step preconditions "deployed revision, pipeline endpoints, Neptune"
  c_rev="$(sc_ssm "/scholarchain/$ENV/compute/deploy/revision")"
  [[ "$c_rev" == "$HEAD_SHA" ]] || sc_die "compute stack is at $c_rev, HEAD is $HEAD_SHA: deploy compute first"
  [[ "$(sc_ssm "/scholarchain/$ENV/compute/emr/endpoint-enabled")" == "true" ]] || sc_die "the EMR service endpoint is off: plan and deploy compute with --window on"
  [[ "$(sc_ssm "/scholarchain/$ENV/compute/sqs/endpoint-enabled")" == "true" ]] || sc_die "the SQS endpoint is off: plan and deploy compute with --window on"
  if [[ "$NEPTUNE_LOAD" == true ]]; then
    [[ "$(sc_ssm "/scholarchain/$ENV/data/neptune/enabled")" == "true" ]] || sc_die "Neptune is off: plan and deploy data with --window on"
    [[ "$(sc_ssm "/scholarchain/$ENV/compute/pipeline/neptune-endpoint")" == "$(sc_ssm "/scholarchain/$ENV/data/neptune/endpoint")" ]] ||
      sc_die "the state machine was built against another Neptune endpoint: re-plan and deploy compute"
  fi
  artifacts="$(sc_ssm "/scholarchain/$ENV/data/buckets/artifacts")"
  raw="$(sc_ssm "/scholarchain/$ENV/data/buckets/raw")"
  graph="$(sc_ssm "/scholarchain/$ENV/data/buckets/graph")"
  curated="$(sc_ssm "/scholarchain/$ENV/data/buckets/curated")"
  sm="$(sc_ssm "/scholarchain/$ENV/compute/pipeline/state-machine-arn")"
  sc_ok "preconditions hold"

  # put_once FILE KEY SHA256: create-only upload to the raw bucket. An existing object with
  # the same recorded SHA-256 is kept (safe retry); any other existing object stops the run.
  put_once() {
    local file="$1" key="$2" sum="$3" err have
    err="$(mktemp)"
    if SC_AWS_TIMEOUT=60m sc_aws s3api put-object --bucket "$raw" --key "$key" --body "$file" \
      --if-none-match '*' --metadata "sha256=$sum" --checksum-algorithm SHA256 >/dev/null 2>"$err"; then
      rm -f "$err"
      sc_ok "uploaded s3://$raw/$key"
      return 0
    fi
    if grep -q -E 'PreconditionFailed|412' "$err"; then
      rm -f "$err"
      have="$(sc_aws s3api head-object --bucket "$raw" --key "$key" --query 'Metadata.sha256' --output text)"
      [[ "$have" == "$sum" ]] || sc_die "s3://$raw/$key already exists with another content (sha256 $have); snapshots are never overwritten: use a new snapshot label"
      sc_skip "s3://$raw/$key already holds this file (sha256 matches)"
      return 0
    fi
    sc_redact <"$err" >&2
    rm -f "$err"
    sc_die "upload of s3://$raw/$key failed (output above)"
  }

  sc_step input "upload the snapshot, create-only, to the raw prefixes"
  declare -A KEY=()
  for s in "${SOURCES[@]}"; do
    KEY[$s]="${SRC_PREFIX[$s]}/snapshot_date=$LABEL/${SRC_FILE[$s]}"
    put_once "$SNAPSHOT_DIR/${SRC_FILE[$s]}" "${KEY[$s]}" "${SUM[$s]}"
  done
  put_once "$SNAPSHOT_DIR/retrieval.json" "dblp/snapshot_date=$LABEL/retrieval.json" "$(sha256sum -- "$SNAPSHOT_DIR/retrieval.json" | awk '{print $1}')"

  sc_step package "scripts/package.sh --jars-only (one sbt run)"
  sc_default_pipe "$SC_ROOT/scripts/package.sh" --jars-only
  REC="$SC_ROOT/target/package/$HEAD_SHA/package-manifest.txt"
  [[ -f "$REC" ]] || sc_die "no package record ${REC#"$SC_ROOT"/}"
  jar_of() { sed -n "s/^jar $1 sha256=\([0-9a-f]\{64\}\) bytes=[0-9]* path=\(.*\)$/\1 \2/p" "$REC"; }
  declare -A JAR_SUM=() JAR_PATH=()
  for j in hadoop entity-resolution spark query-service; do
    line="$(jar_of "$j")"
    [[ -n "$line" ]] || sc_die "the package record has no $j jar"
    JAR_SUM[$j]="${line%% *}"
    JAR_PATH[$j]="$SC_ROOT/${line#* }"
    [[ "$(sha256sum -- "${JAR_PATH[$j]}" | awk '{print $1}')" == "${JAR_SUM[$j]}" ]] || sc_die "${JAR_PATH[$j]#"$SC_ROOT"/} changed after packaging"
  done

  sc_step upload "jars and the EMR step helper to s3://$artifacts/pipeline/$HEAD_SHA/"
  prefix="s3://$artifacts/pipeline/$HEAD_SHA"
  SC_AWS_TIMEOUT=30m sc_aws s3 cp "${JAR_PATH[hadoop]}" "$prefix/jobs/hadoop.jar" --only-show-errors
  SC_AWS_TIMEOUT=30m sc_aws s3 cp "${JAR_PATH[entity-resolution]}" "$prefix/jobs/entity-resolution.jar" --only-show-errors
  SC_AWS_TIMEOUT=30m sc_aws s3 cp "${JAR_PATH[spark]}" "$prefix/jobs/spark.jar" --only-show-errors
  SC_AWS_TIMEOUT=30m sc_aws s3 cp "${JAR_PATH[query-service]}" "$prefix/jobs/loaders.jar" --only-show-errors
  sc_aws s3 cp "$SC_ROOT/scripts/emr/scholarchain-step.sh" "$prefix/emr/scholarchain-step.sh" --only-show-errors
  sc_ok "artifacts uploaded"

  sc_step start "one execution of the pipeline"
  label="run-$(date -u +%Y%m%dt%H%M%Sz)-${HEAD_SHA:0:7}"
  # Narrowed before the call: a start-execution that times out (or is ended by a signal)
  # may still have started.
  sc_guard_set filter "Name=='$NAME-$label'"
  input="$(printf '{"runLabel":"%s","revision":"%s","snapshot":"%s","dblpUri":"s3a://%s/%s","openalexUri":"s3a://%s/%s","crossrefUri":"s3a://%s/%s","neptuneLoad":%s,"embedUnits":"%s"}' \
    "$label" "$HEAD_SHA" "$SNAPSHOT" "$raw" "${KEY[dblp]}" "$raw" "${KEY[openalex]}" "$raw" "${KEY[crossref]}" "$NEPTUNE_LOAD" "$EMBED_UNITS")"
  exec_arn="$(sc_aws stepfunctions start-execution --state-machine-arn "$sm" --name "$label" \
    --input "$input" --query executionArn --output text)"

  EVIDENCE_DIR="$SC_ROOT/target/pipeline-evidence/$label"
  mkdir -p "$EVIDENCE_DIR"
  {
    printf 'run=%s\nenv=%s\nphase=%s\ncommit=%s\nstarted=%s\nregion=%s\n' "$label" "$ENV" "$PHASE" "$HEAD_SHA" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SC_REGION"
    printf 'execution=%s\n' "$exec_arn"
    printf 'snapshot-label=%s\nsnapshot-date=%s\n' "$LABEL" "$SNAPSHOT"
    for s in "${SOURCES[@]}"; do printf 'input %s s3://%s/%s sha256=%s\n' "$s" "$raw" "${KEY[$s]}" "${SUM[$s]}"; done
    for j in hadoop entity-resolution spark query-service; do printf 'jar %s sha256=%s\n' "$j" "${JAR_SUM[$j]}"; done
    printf 'config neptune-load=%s embed-units=%s max-input-mb=%s cost-estimate-usd=%s\n' "$NEPTUNE_LOAD" "$EMBED_UNITS" "$MAX_MB" "$GATE_COST"
    printf 'output hadoop=s3://%s/runs/%s/hadoop\noutput er=s3://%s/runs/%s/er\noutput spark=s3://%s/runs/%s/spark\noutput neptune-load=s3://%s/neptune-load/\n' \
      "$curated" "$label" "$curated" "$label" "$graph" "$label" "$graph"
  } | sc_redact >"$EVIDENCE_DIR/run-start.txt"
  printf '==> PIPELINE-STARTED env=%s run=%s commit=%s snapshot=%s cost-estimate-usd=%s\n' "$ENV" "$label" "$HEAD_SHA" "$LABEL" "$GATE_COST"
  printf '==> EXECUTION %s\n' "$(printf '%s' "$exec_arn" | sc_redact)"
  printf '==> RECORD %s\n' "${EVIDENCE_DIR#"$SC_ROOT"/}/run-start.txt"
  printf '==> NEXT scripts/run-bounded-pipeline.sh --env %s --status %s; when it has ended: --finish %s\n' "$ENV" "$label" "$label"
}

sc_guard_run start_body
