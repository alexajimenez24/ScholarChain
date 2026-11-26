#!/usr/bin/env bash
# scripts/emr/scholarchain-step.sh: runs ON the EMR cluster, never on a laptop.
# run-bounded-pipeline.sh uploads it to s3://<artifacts>/pipeline/<sha>/emr/; the state
# machine (infra/stacks/compute/pipeline.tf) runs it as the bootstrap action "install"
# and then as command-runner.jar steps. Arguments arrive as separate words (no shell
# string is built from execution input), and each is checked against a strict pattern.
#
#   install
#       copy this script to /home/hadoop/scholarchain-step.sh (bootstrap action).
#   spark <spark jar s3://...> <hadoop out s3a://...> <er out s3a://...> <spark out s3a://...> <checkpoint dir s3a://...>
#       find the one Hadoop and the one ER run manifest, then spark-submit SparkPipeline
#       (exit 3 = outputs written but an invariant failed: the step fails, no load runs).
#   neptune-load <endpoint> <port> <loader role arn> <source s3://...> <region>
#       start a Neptune bulk load (openCypher CSV, IAM auth through the AWS CLI) and wait
#       for it, bounded by NEPTUNE_LOAD_MAX_SECONDS (default 3600), checking every 30 s.

set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "==> [emr-step] FAILED line=%s exit=%s command: %s\n" "$LINENO" "$?" "$BASH_COMMAND" >&2' ERR

say() { printf '==> [emr-step] %s\n' "$*"; }
die() {
  printf '==> [emr-step] FAILED: %s\n' "$*" >&2
  exit 1
}

uri_ok() { [[ "$1" =~ ^s3a?://[a-z0-9][a-z0-9.-]{1,62}/[A-Za-z0-9=._/-]+$ ]]; }

one_manifest() {
  local dir="$1" found=() f
  mapfile -t found < <(timeout 300s hadoop fs -ls -C "$dir/run-manifests/" | grep -E '/[^/]+\.json$' | grep -v -E -- '-summary\.json$' || true)
  [[ "${#found[@]}" -eq 1 ]] || die "expected exactly one run manifest under $dir/run-manifests/, found ${#found[@]}"
  f="${found[0]}"
  printf '%s' "$f"
}

cmd="${1:-}"
shift || true

case "$cmd" in
  install)
    install -m 0755 "$0" /home/hadoop/scholarchain-step.sh
    say "installed /home/hadoop/scholarchain-step.sh"
    ;;

  spark)
    [[ $# -eq 5 ]] || die "spark needs 5 arguments, got $#"
    for a in "$@"; do uri_ok "$a" || die "invalid S3 URI argument: $a"; done
    jar="${1/#s3:/s3a:}"
    hadoop_manifest="$(one_manifest "$2")"
    er_manifest="$(one_manifest "$3")"
    say "hadoop manifest: $hadoop_manifest"
    say "er manifest: $er_manifest"
    timeout 5400s spark-submit --deploy-mode cluster --class scholarchain.spark.SparkPipeline \
      "$jar" --hadoop-manifest "$hadoop_manifest" --er-manifest "$er_manifest" \
      --output "$4" --checkpoint-dir "$5"
    say "spark pipeline finished"
    ;;

  neptune-load)
    [[ $# -eq 5 ]] || die "neptune-load needs 5 arguments, got $#"
    endpoint="$1" port="$2" role="$3" source="$4" region="$5"
    [[ "$endpoint" =~ ^[a-z0-9.-]+\.neptune\.amazonaws\.com$ ]] || die "invalid Neptune endpoint (is Neptune enabled?): $endpoint"
    [[ "$port" =~ ^[0-9]{2,5}$ ]] || die "invalid port: $port"
    [[ "$role" =~ ^arn:aws:iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+$ ]] || die "invalid loader role ARN"
    [[ "$source" =~ ^s3://[a-z0-9][a-z0-9.-]{1,62}/[A-Za-z0-9=._/-]+$ ]] || die "invalid load source: $source"
    [[ "$region" == "us-east-2" ]] || die "region must be us-east-2"
    url="https://$endpoint:$port"
    load_id="$(timeout 120s aws neptunedata start-loader-job --region "$region" --endpoint-url "$url" \
      --source "$source" --format opencypher --s3-bucket-region "$region" --iam-role-arn "$role" \
      --fail-on-error --parallelism MEDIUM --query 'payload.loadId' --output text)"
    [[ "$load_id" =~ ^[A-Za-z0-9-]+$ ]] || die "loader did not return a load ID"
    say "started Neptune load $load_id from $source"
    max="${NEPTUNE_LOAD_MAX_SECONDS:-3600}"
    waited=0
    status=""
    while [[ "$waited" -lt "$max" ]]; do
      status="$(timeout 60s aws neptunedata get-loader-job-status --region "$region" --endpoint-url "$url" \
        --load-id "$load_id" --query 'payload.overallStatus.status' --output text)"
      case "$status" in
        LOAD_COMPLETED)
          say "Neptune load $load_id completed"
          exit 0
          ;;
        LOAD_NOT_STARTED | LOAD_IN_PROGRESS | LOAD_IN_QUEUE) ;;
        *) die "Neptune load $load_id ended with $status" ;;
      esac
      sleep 30
      waited=$((waited + 30))
    done
    die "Neptune load $load_id still $status after $max seconds"
    ;;

  *)
    die "unknown command: ${cmd:-<none>} (install | spark | neptune-load)"
    ;;
esac
