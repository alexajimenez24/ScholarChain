#!/usr/bin/env bash
# scripts/smoke-test.sh: read-only checks of a deployed environment. Changes nothing.
#
# Usage:
#   scripts/smoke-test.sh --env dev [--expect-commit <sha>] [--unauthenticated-only]
#
# Checks (each prints ==> ... OK or FAILED; the first failure stops with evidence):
#   1. revision: SSM app/deploy/image-tag and the image of every ECS service equal the
#      expected commit (default HEAD); every service has its desired tasks running.
#   2. edge: a request without a token is refused with 401 by the JWT authorizer, and
#      the health routes are not exposed through the API.
#   3. authenticated (needs SCHOLARCHAIN_SMOKE_TOKEN): POST /v1/query answers 200 or
#      202, GET /v1/graph/nodes/<id> answers 200 or 404 (never 401, 403 or 5xx).
#
# The token: an OAuth2 client-credentials access token of the service client
# (SSM /scholarchain/<env>/app/auth/client-id and auth/token-url, scopes in
# auth/scopes). The human obtains it and exports SCHOLARCHAIN_SMOKE_TOKEN for the run;
# this script never reads the client secret, never prints the token and passes it to
# curl on stdin (curl --config -), so it is not on any command line.
# Without the token the script fails, unless --unauthenticated-only is given, in which
# case step 3 is reported as SKIP.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,5p' "$0"; exit 2; }

ENV="" EXPECT="" UNAUTH_ONLY=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --expect-commit) EXPECT="${2:-}"; shift 2 ;;
    --unauthenticated-only) UNAUTH_ONLY=true; shift ;;
    -h | --help) usage ;;
    *) sc_die "unknown argument: $1" ;;
  esac
done
[[ -n "$ENV" ]] || usage

sc_step preflight "env=$ENV"
sc_need_tools aws curl git timeout sed
[[ "$ENV" =~ ^[a-z][a-z0-9]{1,7}$ ]] || sc_die "invalid environment name: $ENV"
EXPECT="${EXPECT:-$(sc_head)}"
[[ "$EXPECT" =~ ^[0-9a-f]{40}$ ]] || sc_die "--expect-commit must be a full 40-hex commit SHA"
if [[ "$UNAUTH_ONLY" == false && -z "${SCHOLARCHAIN_SMOKE_TOKEN:-}" ]]; then
  sc_die "SCHOLARCHAIN_SMOKE_TOKEN is not set (or pass --unauthenticated-only)"
fi

sc_step identity "caller identity"
sc_identity
api="$(sc_ssm "/scholarchain/$ENV/app/api/endpoint")"
cluster="$(sc_ssm "/scholarchain/$ENV/app/ecs/cluster-name")"
[[ "$api" =~ ^https://[a-z0-9]+\.execute-api\.us-east-2\.amazonaws\.com$ ]] || sc_die "unexpected API endpoint: $api"

sc_step revision "deployed images equal $EXPECT"
tag="$(sc_ssm "/scholarchain/$ENV/app/deploy/image-tag")"
[[ "$tag" == "$EXPECT" ]] || sc_die "SSM app/deploy/image-tag is $tag, expected $EXPECT"
for svc in query-service graph-service embedding-worker; do
  td="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].taskDefinition' --output text)"
  image="$(sc_aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
  counts="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].[runningCount,desiredCount]' --output text)"
  running="${counts%%$'\t'*}"
  desired="${counts##*$'\t'}"
  [[ "${image##*:}" == "$EXPECT" ]] || sc_die "$svc image tag ${image##*:}, expected $EXPECT"
  [[ "$running" == "$desired" && "$desired" -ge 1 ]] || sc_die "$svc running=$running desired=$desired"
  sc_ok "$svc :$EXPECT running=$running"
done

# status <method> <path> [body]: prints the HTTP status; with a token when TOKEN_ON=1.
TOKEN_ON=0
status() {
  local method="$1" path="$2" body="${3:-}"
  local -a args=(--silent --show-error --output /dev/null --write-out '%{http_code}'
    --max-time 30 --request "$method" "$api$path")
  if [[ -n "$body" ]]; then
    args+=(--header 'Content-Type: application/json' --data "$body")
  fi
  if [[ "$TOKEN_ON" == 1 ]]; then
    printf 'header = "Authorization: Bearer %s"\n' "$SCHOLARCHAIN_SMOKE_TOKEN" |
      timeout 40s curl --config - "${args[@]}"
  else
    timeout 40s curl "${args[@]}"
  fi
}

sc_step edge "no token is refused by the JWT authorizer"
code="$(status GET /v1/graph/nodes/smoke-test)"
[[ "$code" == "401" ]] || sc_die "GET /v1/graph/nodes/smoke-test without a token answered $code, expected 401"
sc_ok "unauthenticated request refused with 401"
code="$(status GET /healthz)"
[[ "$code" == "404" || "$code" == "401" ]] || sc_die "GET /healthz is exposed through the API (answered $code)"
sc_ok "health routes are not exposed through the API ($code)"

if [[ "$UNAUTH_ONLY" == true ]]; then
  sc_skip "authenticated checks (--unauthenticated-only)"
  printf '==> SMOKE env=%s commit=%s result=PASS-UNAUTHENTICATED-ONLY\n' "$ENV" "$EXPECT"
  exit 0
fi

sc_step authenticated "query and graph routes with the service token"
TOKEN_ON=1
code="$(status POST /v1/query '{"question": "Who are the most frequent coauthors in the sample?", "mode": "async"}')"
[[ "$code" == "200" || "$code" == "202" ]] || sc_die "POST /v1/query answered $code, expected 200 or 202"
sc_ok "POST /v1/query answered $code"
code="$(status GET /v1/graph/nodes/smoke-test)"
[[ "$code" == "200" || "$code" == "404" ]] || sc_die "GET /v1/graph/nodes/smoke-test answered $code, expected 200 or 404"
sc_ok "GET /v1/graph/nodes/smoke-test answered $code"
TOKEN_ON=0

printf '==> SMOKE env=%s commit=%s result=PASS\n' "$ENV" "$EXPECT"
