#!/usr/bin/env bash
# scripts/archive-evidence.sh: gather the evidence of a phase into one versioned folder.
#
# Usage:
#   scripts/archive-evidence.sh --env dev --phase NN [--run <pipeline run label>]
#                               [--metrics-hours N] [--dry-run]
#
# Collects, into target/evidence/phase-NN-<HEAD 12>-<UTC time>/ (git-ignored; the human
# or documentation-engineer copies what a phase record needs):
#   test-reports/  the sbt JUnit XML reports (*/target/test-reports/*.xml)
#   plans/         plan metadata and redacted plan text from the plan directory
#                  (<stack>.tfplan.meta and <stack>.plan.txt, which hold the plan
#                  SHA-256s; never the binary .tfplan, which holds plan-time values)
#   package/       the package records of target/package/ (jar SHA-256s, image tags and
#                  IDs) for HEAD
#   pipeline/      target/pipeline-evidence/<run>/ (run start record, execution
#                  history, run manifests), for --run or every run
#   experiments/   target/experiments/ (experiment records and console logs)
#   releases/      the release records under releases/ (image digests and revisions)
#   metrics/       only with --metrics-hours N (1 to 72): CloudWatch statistics for the
#                  last N hours, 5-minute periods, read only: API Gateway Count, 4xx, 5xx
#                  and Latency for the HTTP API; ECS CPUUtilization and
#                  MemoryUtilization of the three services; SQS
#                  ApproximateNumberOfMessagesVisible of the embedding queue and its DLQ
#   INDEX.txt      commit, dirty flag, env, phase, time, file counts per section
#   SHA256SUMS     SHA-256 of every collected file
# Every collected text file passes the account-ID redaction, and the folder is scanned
# for access key IDs and private key blocks: a match fails the run (exit 1) and names
# the file, so nothing secret is handed on. Without --metrics-hours there is no AWS call.
# --dry-run: names every source and how many files it holds; no AWS call, no copy.
# Exit codes: 0 archived, 1 failed (including a secret-scan match), 2 usage.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,6p' "$0"; exit 2; }

ENV="" PHASE="" RUN="" HOURS=0 DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --phase) PHASE="${2:-}"; shift 2 ;;
    --run) RUN="${2:-}"; shift 2 ;;
    --metrics-hours) HOURS="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage ;;
    *) printf '==> [archive-evidence] FAILED: unknown argument: %s\n' "$1" >&2; usage ;;
  esac
done
[[ -n "$ENV" && -n "$PHASE" ]] || usage
[[ "$ENV" =~ ^[a-z][a-z0-9]{1,7}$ ]] || sc_die "invalid environment name: $ENV"
sc_check_phase "$PHASE"
[[ -z "$RUN" || "$RUN" =~ ^run-[0-9]{8}t[0-9]{6}z-[0-9a-f]{7}$ ]] || sc_die "--run takes a run label (run-YYYYMMDDtHHMMSSz-<sha7>)"
[[ "$HOURS" =~ ^[0-9]{1,2}$ && "$HOURS" -le 72 ]] || sc_die "--metrics-hours must be 0 to 72"
sc_need_tools git find sha256sum sed grep cp mkdir date

HEAD_SHA="$(sc_head)"
DIRTY=false
if sc_is_dirty; then DIRTY=true; fi
PLAN_DIR="${SCHOLARCHAIN_PLAN_DIR:-$HOME/.scholarchain/plans}/$ENV"
STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
OUT="$SC_ROOT/target/evidence/phase-$PHASE-${HEAD_SHA:0:12}-$STAMP"

# Sources: section => find arguments, listed NUL-separated by list_section.
list_section() {
  case "$1" in
    test-reports)
      find "$SC_ROOT" \( -path "$SC_ROOT/.git" -o -path "$SC_ROOT/datasets" -o -path "$SC_ROOT/target/evidence" -o -name node_modules \) -prune \
        -o -path '*/target/test-reports/*.xml' -type f -print0 2>/dev/null
      ;;
    plans) if [[ -d "$PLAN_DIR" ]]; then find "$PLAN_DIR" -maxdepth 1 -type f \( -name '*.tfplan.meta' -o -name '*.plan.txt' \) -print0; fi ;;
    package) if [[ -d "$SC_ROOT/target/package" ]]; then find "$SC_ROOT/target/package" -mindepth 2 -maxdepth 2 -type f -path "*/$HEAD_SHA*/*" -print0; fi ;;
    pipeline) if [[ -d "$SC_ROOT/target/pipeline-evidence/${RUN}" ]]; then find "$SC_ROOT/target/pipeline-evidence/${RUN}" -type f -print0; fi ;;
    experiments) if [[ -d "$SC_ROOT/target/experiments" ]]; then find "$SC_ROOT/target/experiments" -type f -print0; fi ;;
    releases) if [[ -d "$SC_ROOT/releases" ]]; then find "$SC_ROOT/releases" -type f ! -name .gitkeep -print0; fi ;;
    *) return 1 ;;
  esac
}
SECTIONS=(test-reports plans package pipeline experiments releases)
count() { list_section "$1" | tr -cd '\0' | wc -c; }

if [[ "$DRY_RUN" == true ]]; then
  sc_step dry-run "env=$ENV phase=$PHASE run=${RUN:-all} metrics-hours=$HOURS (no AWS call, no copy)"
  sc_info "revision=$HEAD_SHA dirty=$DIRTY"
  for s in "${SECTIONS[@]}"; do
    sc_would "collect $(count "$s") file(s) into ${OUT#"$SC_ROOT"/}/$s/"
  done
  if [[ "$HOURS" -gt 0 ]]; then
    sc_dry_aws "CloudWatch statistics of the last $HOURS hour(s) (API Gateway, ECS, SQS; read only) into metrics/"
  else
    sc_would "skip CloudWatch metrics (no --metrics-hours): no AWS call"
  fi
  sc_would "redact account IDs, scan for access key IDs and private keys, write INDEX.txt and SHA256SUMS"
  sc_dry_end
fi

sc_step collect "into ${OUT#"$SC_ROOT"/}"
mkdir -p "$OUT"
declare -A N=()
for s in "${SECTIONS[@]}"; do
  N[$s]=0
  mkdir -p "$OUT/$s"
  while IFS= read -r -d '' f; do
    case "$s" in
      test-reports) rel="${f#"$SC_ROOT"/}" ;;
      plans) rel="${f##*/}" ;;
      *) rel="${f#"$SC_ROOT"/}"; rel="${rel#target/}" ;;
    esac
    mkdir -p "$OUT/$s/$(dirname "$rel")"
    if grep -I -q . "$f" 2>/dev/null || [[ ! -s "$f" ]]; then
      sc_redact <"$f" >"$OUT/$s/$rel"
    else
      cp -- "$f" "$OUT/$s/$rel"
    fi
    N[$s]=$((N[$s] + 1))
  done < <(list_section "$s")
  sc_ok "$s: ${N[$s]} file(s)"
done

if [[ "$HOURS" -gt 0 ]]; then
  sc_step metrics "CloudWatch statistics, last $HOURS hour(s), read only"
  sc_need_tools aws
  sc_identity
  mkdir -p "$OUT/metrics"
  start="$(date -u -d "-$HOURS hours" +%Y-%m-%dT%H:%M:%SZ)"
  end="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  api_id="$(sc_ssm "/scholarchain/$ENV/app/api/id")"
  cluster="$(sc_ssm "/scholarchain/$ENV/app/ecs/cluster-name")"
  queue="$(sc_ssm "/scholarchain/$ENV/compute/sqs/embedding-queue-name")"
  dlq="$(sc_ssm "/scholarchain/$ENV/compute/sqs/embedding-dlq-name")"
  # cw_stat FILE NAMESPACE METRIC STATISTICS DIMENSIONS...
  cw_stat() {
    local file="$1" ns="$2" metric="$3" stats="$4"
    shift 4
    sc_aws cloudwatch get-metric-statistics --namespace "$ns" --metric-name "$metric" \
      --start-time "$start" --end-time "$end" --period 300 --statistics "$stats" \
      --dimensions "$@" --output json | sc_redact >"$OUT/metrics/$file.json"
    sc_ok "metrics/$file.json"
  }
  for m in Count 4xx 5xx; do cw_stat "api-$m" AWS/ApiGateway "$m" Sum "Name=ApiId,Value=$api_id"; done
  cw_stat api-Latency AWS/ApiGateway Latency Average "Name=ApiId,Value=$api_id"
  for svc in query-service graph-service embedding-worker; do
    for m in CPUUtilization MemoryUtilization; do
      cw_stat "ecs-$svc-$m" AWS/ECS "$m" Average "Name=ClusterName,Value=$cluster" "Name=ServiceName,Value=$svc"
    done
  done
  cw_stat sqs-embedding-visible AWS/SQS ApproximateNumberOfMessagesVisible Maximum "Name=QueueName,Value=$queue"
  cw_stat sqs-embedding-dlq-visible AWS/SQS ApproximateNumberOfMessagesVisible Maximum "Name=QueueName,Value=$dlq"
fi

sc_step secret-scan "no access key ID or private key in the archive"
hits="$(grep -r -l -I -E '(AKIA|ASIA)[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|aws_secret_access_key' "$OUT" || true)"
if [[ -n "$hits" ]]; then
  printf '%s\n' "$hits" | sed "s|^$SC_ROOT/||" >&2
  sc_die "the archive holds what looks like a credential (files above); it is NOT ready to hand on: remove the source and archive again"
fi
sc_ok "no match"

sc_step index "INDEX.txt and SHA256SUMS"
{
  printf 'commit=%s\ndirty=%s\nenv=%s\nphase=%s\ncreated=%s\nrun=%s\nmetrics-hours=%s\n' \
    "$HEAD_SHA" "$DIRTY" "$ENV" "$PHASE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${RUN:-all}" "$HOURS"
  for s in "${SECTIONS[@]}"; do printf 'section %s files=%s\n' "$s" "${N[$s]}"; done
} >"$OUT/INDEX.txt"
sums="$(cd "$OUT" && find . -type f -print0 | sort -z | xargs -0 -r sha256sum)"
printf '%s\n' "$sums" >"$OUT/SHA256SUMS"
printf '==> EVIDENCE %s files=%s\n' "${OUT#"$SC_ROOT"/}" "$(find "$OUT" -type f | wc -l)"
sc_ok "evidence archived"
