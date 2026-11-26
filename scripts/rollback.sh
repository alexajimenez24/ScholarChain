#!/usr/bin/env bash
# scripts/rollback.sh: return the three ECS services to the images of an earlier commit.
#
# Usage:
#   scripts/rollback.sh --env dev --phase NN --to-commit <40-hex sha>
#
# Run by deploy-engineer (/rollback, or a failed deploy gate). It refuses unless:
#   - the source tree is clean,
#   - phase-NN/human-gate.md names this environment (APPROVED-ENVIRONMENT),
#   - --to-commit is a full SHA, an ancestor of HEAD, and every repository holds an image
#     tagged with it (tags are immutable, so the image is exactly the one released),
#   - an ACTIVE task definition revision of each service already runs that image.
# It only points each service at that earlier task definition revision (no new
# revision, no scaling change), waits for the services to be stable and verifies that
# each runs :<to-commit>. It never runs Terraform and never touches data or schemas.
#
# Infrastructure (Terraform) rollback is not done here: plan the earlier commit with
# scripts/plan.sh and apply it through the human gate with scripts/deploy.sh.
# After this script the app stack differs from Terraform state until the next app
# deploy; the ==> DRIFT line says so.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,5p' "$0"; exit 2; }

ENV="" PHASE="" TARGET=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV="${2:-}"; shift 2 ;;
    --phase) PHASE="${2:-}"; shift 2 ;;
    --to-commit) TARGET="${2:-}"; shift 2 ;;
    -h | --help) usage ;;
    *) sc_die "unknown argument: $1" ;;
  esac
done
[[ -n "$ENV" && -n "$PHASE" && -n "$TARGET" ]] || usage

sc_step preflight "env=$ENV phase=$PHASE to-commit=$TARGET"
sc_need_tools aws git timeout sed awk
[[ "$ENV" =~ ^[a-z][a-z0-9]{1,7}$ ]] || sc_die "invalid environment name: $ENV"
sc_check_phase "$PHASE"
[[ "$TARGET" =~ ^[0-9a-f]{40}$ ]] || sc_die "--to-commit must be a full 40-hex commit SHA"
git -C "$SC_ROOT" merge-base --is-ancestor "$TARGET" HEAD || sc_die "$TARGET is not an ancestor of HEAD"

sc_step tree "refuse a dirty source tree"
sc_require_clean_tree

sc_step gate "the environment must be the approved one"
sc_read_gate "$PHASE"
[[ "$GATE_ENV" == "$ENV" ]] || sc_die "environment $ENV is not the APPROVED-ENVIRONMENT $GATE_ENV"

sc_step identity "caller identity"
sc_identity
cluster="$(sc_ssm "/scholarchain/$ENV/app/ecs/cluster-name")"
services=(query-service graph-service embedding-worker)

sc_step find "an ECR image and an ACTIVE task definition for :$TARGET, per service"
declare -A target_td=()
for svc in "${services[@]}"; do
  repo="$(sc_ssm "/scholarchain/$ENV/compute/ecr/$svc/name")"
  sc_aws ecr describe-images --repository-name "$repo" --image-ids "imageTag=$TARGET" >/dev/null 2>&1 ||
    sc_die "$repo has no image tagged $TARGET"
  family="$(sc_ssm "/scholarchain/$ENV/app/ecs/task-family/$svc")"
  mapfile -t revisions < <(sc_aws ecs list-task-definitions --family-prefix "$family" --status ACTIVE \
    --sort DESC --max-items 20 --query 'taskDefinitionArns[]' --output text | tr '\t' '\n')
  found=""
  for td in "${revisions[@]}"; do
    [[ -n "$td" && "$td" != "None" ]] || continue
    image="$(sc_aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
    if [[ "${image##*:}" == "$TARGET" ]]; then
      found="$td"
      break
    fi
  done
  [[ -n "$found" ]] || sc_die "no ACTIVE task definition of $family (newest 20) runs :$TARGET"
  target_td[$svc]="$found"
  sc_ok "$svc -> $(printf '%s' "$found" | sc_redact)"
done

sc_step rollback "point each service at its earlier task definition"
for svc in "${services[@]}"; do
  sc_aws ecs update-service --cluster "$cluster" --service "$svc" \
    --task-definition "${target_td[$svc]}" --query 'service.serviceName' --output text >/dev/null
  sc_ok "update-service $svc requested"
done

sc_step verify "services stable and running :$TARGET"
SC_AWS_TIMEOUT=20m sc_aws ecs wait services-stable --cluster "$cluster" --services "${services[@]}" ||
  sc_die "services did not become stable within 20 minutes; capture evidence before any further action"
for svc in "${services[@]}"; do
  td="$(sc_aws ecs describe-services --cluster "$cluster" --services "$svc" --query 'services[0].taskDefinition' --output text)"
  image="$(sc_aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
  [[ "${image##*:}" == "$TARGET" ]] || sc_die "$svc runs ${image##*:}, expected $TARGET"
  sc_ok "$svc runs :$TARGET"
done

printf '==> DRIFT app stack: ECS services run %s while Terraform state records the last deploy; the next app deploy restores Terraform control\n' "$TARGET"
printf '==> ROLLBACK env=%s commit=%s at=%s\n' "$ENV" "$TARGET" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
