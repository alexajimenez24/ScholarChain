#!/usr/bin/env bash
# scripts/smoke-test.sh: checks of a deployed environment through its real API. It changes
# no infrastructure; the only writes are the query records the API itself keeps.
#
# Usage:
#   scripts/smoke-test.sh --env dev --researcher "<name in the served run>"
#                         [--expect-commit <sha>] [--unauthenticated-only] [--dry-run]
#
# Checks (each prints ==> ... OK, or FAILED and stops with exit 1):
#   1. revision: SSM app/deploy/image-tag and the image of every ECS service equal the
#      expected commit (default HEAD); every service has its desired tasks running.
#   2. health: every running task of the three services is RUNNING and HEALTHY (the
#      container health check probes the service's own GET /healthz, liveness).
#   3. edge: a request without a token is refused with 401 by the JWT authorizer, and
#      the health routes are not exposed through the API.
#   4. authenticated (needs SCHOLARCHAIN_SMOKE_TOKEN with the scopes scholarchain/query.run,
#      scholarchain/evidence.read and scholarchain/graph.read):
#      a. readiness and a bounded researcher query: POST /v1/query (sync, options limit 5
#         and maxPathDepth 2) about the collaborators of --researcher answers 200 (503
#         means query-service is not ready: no served run) with state succeeded, a
#         non-abstained answer and a claim that cites evidence;
#      b. evidence: GET /v1/evidence/<first evidence ID of the first claim> answers 200
#         with the same evidenceId and a non-empty sourceUri;
#      c. idempotency: the same request with the same Idempotency-Key answers 200 with
#         Idempotent-Replayed: true and the same queryId;
#      d. expected error path: an unsupported constraint answers 422
#         unsupported_constraint;
#      e. graph readiness: GET /v1/graph/nodes/smoke-test answers 200 or 404 (never 401,
#         403 or 5xx).
#
# The token: an OAuth2 client-credentials access token of the service client
# (SSM /scholarchain/<env>/app/auth/client-id and auth/token-url, scopes in
# auth/scopes). The human obtains it and exports SCHOLARCHAIN_SMOKE_TOKEN for the run;
# this script never reads the client secret, never prints the token and passes it to
# curl on stdin (curl --config -), so it is not on any command line.
# Without the token the script fails, unless --unauthenticated-only is given, in which
# case step 4 is reported as SKIP.
# --dry-run: checks the arguments and the token's presence (never its value), names every
# request it would send; no AWS call, no HTTP request. Exit 0 = local checks pass, 3 = a
# real run would refuse.
# Exit codes: 0 PASS, 1 a check failed, 2 usage.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,7p' "$0"; exit 2; }

ENV="" EXPECT="" UNAUTH_ONLY=false RESEARCHER="" DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --expect-commit) EXPECT="${2:-}"; shift 2 ;;
    --researcher) RESEARCHER="${2:-}"; shift 2 ;;
    --unauthenticated-only) UNAUTH_ONLY=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage ;;
    *) printf '==> [smoke-test] FAILED: unknown argument: %s\n' "$1" >&2; usage ;;
  esac
done
[[ -n "$ENV" ]] || usage
[[ "$ENV" =~ ^[a-z][a-z0-9]{1,7}$ ]] || sc_die "invalid environment name: $ENV"
EXPECT="${EXPECT:-$(sc_head)}"
[[ "$EXPECT" =~ ^[0-9a-f]{40}$ ]] || sc_die "--expect-commit must be a full 40-hex commit SHA"
if [[ "$UNAUTH_ONLY" == false ]]; then
  [[ "$RESEARCHER" =~ ^[[:alpha:]][[:alpha:]\ .\'-]{1,80}$ ]] ||
    sc_die "--researcher \"<name>\" is required for the authenticated checks (letters, spaces and .'- only; a researcher of the served run)"
fi
SERVICES=(query-service graph-service embedding-worker)
KEY="smoke-${EXPECT:0:12}-$(date -u +%Y%m%dT%H%M%SZ)"

token_set() { [[ -n "${SCHOLARCHAIN_SMOKE_TOKEN:-}" ]] || sc_die "SCHOLARCHAIN_SMOKE_TOKEN is not set (or pass --unauthenticated-only)"; }

if [[ "$DRY_RUN" == true ]]; then
  sc_step dry-run "env=$ENV expect=$EXPECT (no AWS call, no HTTP request)"
  sc_dry_check "tools (aws curl git python3)" sc_need_tools aws curl git timeout sed python3
  if [[ "$UNAUTH_ONLY" == false ]]; then
    sc_dry_check "SCHOLARCHAIN_SMOKE_TOKEN is set (value never read here)" token_set
  fi
  sc_dry_aws "API endpoint and cluster name from SSM /scholarchain/$ENV/app/{api/endpoint,ecs/cluster-name}"
  sc_would "check SSM app/deploy/image-tag == $EXPECT and that query-service, graph-service and embedding-worker run :$EXPECT with running == desired >= 1"
  sc_would "check every running task of the three services is RUNNING and HEALTHY (container check on GET /healthz)"
  sc_would "GET /v1/graph/nodes/smoke-test without a token: expect 401; GET /healthz through the API: expect 404 or 401"
  if [[ "$UNAUTH_ONLY" == true ]]; then
    sc_would "skip the authenticated checks (--unauthenticated-only)"
  else
    sc_would "POST /v1/query (sync, limit 5, maxPathDepth 2, Idempotency-Key $KEY) about the collaborators of \"$RESEARCHER\": expect 200 succeeded, not abstained, a claim citing evidence"
    sc_would "GET /v1/evidence/<claims[0].evidenceIds[0]>: expect 200, same evidenceId, non-empty sourceUri"
    sc_would "repeat the POST with the same key: expect 200, Idempotent-Replayed: true, same queryId"
    sc_would "POST /v1/query with constraints.nationality: expect 422 unsupported_constraint"
    sc_would "GET /v1/graph/nodes/smoke-test with the token: expect 200 or 404"
  fi
  sc_dry_end
fi

sc_step preflight "env=$ENV"
sc_need_tools aws curl git timeout sed python3 mktemp
if [[ "$UNAUTH_ONLY" == false ]]; then token_set; fi

sc_step identity "caller identity"
sc_identity
api="$(sc_ssm "/scholarchain/$ENV/app/api/endpoint")"
cluster="$(sc_ssm "/scholarchain/$ENV/app/ecs/cluster-name")"
[[ "$api" =~ ^https://[a-z0-9]+\.execute-api\.us-east-2\.amazonaws\.com$ ]] || sc_die "unexpected API endpoint: $api"

sc_step revision "deployed images equal $EXPECT"
tag="$(sc_ssm "/scholarchain/$ENV/app/deploy/image-tag")"
[[ "$tag" == "$EXPECT" ]] || sc_die "SSM app/deploy/image-tag is $tag, expected $EXPECT"
for svc in "${SERVICES[@]}"; do
  td="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].taskDefinition' --output text)"
  image="$(sc_aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
  counts="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].[runningCount,desiredCount]' --output text)"
  running="${counts%%$'\t'*}"
  desired="${counts##*$'\t'}"
  [[ "${image##*:}" == "$EXPECT" ]] || sc_die "$svc image tag ${image##*:}, expected $EXPECT"
  [[ "$running" == "$desired" && "$desired" -ge 1 ]] || sc_die "$svc running=$running desired=$desired"
  sc_ok "$svc :$EXPECT running=$running"
done

sc_step health "every running task is RUNNING and HEALTHY"
for svc in "${SERVICES[@]}"; do
  mapfile -t tasks < <(sc_aws ecs list-tasks --cluster "$cluster" --service-name "$svc" --desired-status RUNNING \
    --query 'taskArns[]' --output text | tr '\t' '\n' | grep -v -E '^(None)?$' || true)
  [[ "${#tasks[@]}" -ge 1 ]] || sc_die "$svc has no running task"
  mapfile -t states < <(sc_aws ecs describe-tasks --cluster "$cluster" --tasks "${tasks[@]}" \
    --query 'tasks[].[lastStatus,healthStatus]' --output text | tr '\t' ' ' | sort -u)
  [[ "${#states[@]}" -eq 1 && "${states[0]}" == "RUNNING HEALTHY" ]] ||
    sc_die "$svc tasks are not all RUNNING HEALTHY: $(IFS=',' && printf '%s' "${states[*]}")"
  sc_ok "$svc: ${#tasks[@]} task(s) RUNNING HEALTHY"
done

WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT

# call METHOD PATH [BODY]: sets CODE; the response body is in $WORK/body, headers in
# $WORK/headers. With the token only when TOKEN_ON=1 (passed on stdin, never argv).
TOKEN_ON=0
CODE=""
call() {
  local method="$1" path="$2" body="${3:-}"
  local -a args=(--silent --show-error --output "$WORK/body" --dump-header "$WORK/headers"
    --write-out '%{http_code}' --max-time 30 --request "$method" "$api$path")
  if [[ -n "$body" ]]; then
    printf '%s' "$body" >"$WORK/request.json"
    args+=(--header 'Content-Type: application/json' --header "Idempotency-Key: $KEY" --data-binary "@$WORK/request.json")
  fi
  if [[ "$TOKEN_ON" == 1 ]]; then
    CODE="$(printf 'header = "Authorization: Bearer %s"\n' "$SCHOLARCHAIN_SMOKE_TOKEN" |
      timeout 40s curl --config - "${args[@]}")"
  else
    CODE="$(timeout 40s curl "${args[@]}")"
  fi
}

# jget PATH: one field of the last response body (dotted path, numeric parts index a
# list); prints true/false for booleans, nothing when absent.
jget() {
  python3 -I -c 'import json, sys
try:
    v = json.load(open(sys.argv[1], encoding="utf-8"))
    for p in sys.argv[2].split("."):
        v = v[int(p)] if p.isdigit() else v[p]
except (ValueError, KeyError, IndexError, TypeError):
    sys.exit(0)
print(str(v).lower() if isinstance(v, bool) else v)' "$WORK/body" "$1"
}

sc_step edge "no token is refused by the JWT authorizer"
call GET /v1/graph/nodes/smoke-test
[[ "$CODE" == "401" ]] || sc_die "GET /v1/graph/nodes/smoke-test without a token answered $CODE, expected 401"
sc_ok "unauthenticated request refused with 401"
call GET /healthz
[[ "$CODE" == "404" || "$CODE" == "401" ]] || sc_die "GET /healthz is exposed through the API (answered $CODE)"
sc_ok "health routes are not exposed through the API ($CODE)"

if [[ "$UNAUTH_ONLY" == true ]]; then
  sc_skip "authenticated checks (--unauthenticated-only)"
  printf '==> SMOKE env=%s commit=%s result=PASS-UNAUTHENTICATED-ONLY\n' "$ENV" "$EXPECT"
  exit 0
fi

TOKEN_ON=1
QUERY="$(python3 -I -c 'import json, sys
print(json.dumps({"question": "Who are the most frequent collaborators of \"%s\"?" % sys.argv[1],
                  "mode": "sync", "options": {"limit": 5, "maxPathDepth": 2}}))' "$RESEARCHER")"

sc_step query "bounded researcher query (readiness of query-service)"
call POST /v1/query "$QUERY"
[[ "$CODE" != "503" ]] || sc_die "POST /v1/query answered 503: query-service is not ready (no served run loaded, or a dependency is down)"
[[ "$CODE" == "200" ]] || sc_die "POST /v1/query answered $CODE ($(jget code)), expected 200"
[[ "$(jget state)" == "succeeded" ]] || sc_die "query state is $(jget state), expected succeeded"
[[ "$(jget answer.abstained)" == "false" ]] || sc_die "the answer abstained ($(jget answer.abstentionReason)); is \"$RESEARCHER\" in the served run?"
QUERY_ID="$(jget queryId)"
EVIDENCE_ID="$(jget answer.claims.0.evidenceIds.0)"
[[ "$QUERY_ID" =~ ^qry-[A-Za-z0-9_-]+$ ]] || sc_die "no queryId in the answer"
[[ "$EVIDENCE_ID" =~ ^[A-Za-z0-9_.:=-]{1,256}$ ]] || sc_die "the first claim cites no evidence ID"
sc_ok "query $QUERY_ID succeeded; first claim cites $EVIDENCE_ID"

sc_step evidence "evidence for the first claim"
call GET "/v1/evidence/$EVIDENCE_ID"
[[ "$CODE" == "200" ]] || sc_die "GET /v1/evidence/$EVIDENCE_ID answered $CODE, expected 200"
[[ "$(jget evidenceId)" == "$EVIDENCE_ID" ]] || sc_die "the evidence item names another ID"
[[ -n "$(jget sourceUri)" ]] || sc_die "the evidence item has no sourceUri"
sc_ok "evidence $EVIDENCE_ID: sourceUri present"

sc_step idempotency "the same request with the same Idempotency-Key replays"
call POST /v1/query "$QUERY"
[[ "$CODE" == "200" ]] || sc_die "the replay answered $CODE, expected 200"
grep -q -i '^Idempotent-Replayed: *true' "$WORK/headers" || sc_die "the replay has no Idempotent-Replayed: true header"
[[ "$(jget queryId)" == "$QUERY_ID" ]] || sc_die "the replay returned another queryId"
sc_ok "replayed $QUERY_ID"

sc_step error-path "an unsupported constraint is 422"
KEY="$KEY-error"
call POST /v1/query '{"question": "Who collaborates most?", "constraints": {"nationality": "x"}}'
[[ "$CODE" == "422" && "$(jget code)" == "unsupported_constraint" ]] ||
  sc_die "the unsupported constraint answered $CODE $(jget code), expected 422 unsupported_constraint"
sc_ok "422 unsupported_constraint"

sc_step graph "graph-service readiness"
call GET /v1/graph/nodes/smoke-test
[[ "$CODE" == "200" || "$CODE" == "404" ]] || sc_die "GET /v1/graph/nodes/smoke-test answered $CODE, expected 200 or 404"
sc_ok "GET /v1/graph/nodes/smoke-test answered $CODE"
TOKEN_ON=0

printf '==> SMOKE env=%s commit=%s query=%s evidence=%s result=PASS\n' "$ENV" "$EXPECT" "$QUERY_ID" "$EVIDENCE_ID"
