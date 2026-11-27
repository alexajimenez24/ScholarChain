#!/usr/bin/env bash
# scripts/check.sh: every non-cloud validation gate of the repository, in one command.
#
# Usage:
#   scripts/check.sh
#
# Gates, in order (each prints ==> [check] STEP <gate> and then PASS or FAIL; every gate
# runs even after a failure, and the run exits nonzero if any gate failed):
#   constitution   .claude/hooks/validate-agent-files.sh (roles, adapters, MCP config,
#                  one writer per tracked file) and .agents/render.sh --check
#   json           every JSON configuration file parses: .agents/models.json, .mcp.json,
#                  .claude/settings.json, .codex/hooks.json, infra/environments/*/*.json
#   hooks          bash -n on .claude/hooks/*.sh and .agents/githooks/pre-commit; the
#                  hook Python files compile (ast parse, no bytecode written)
#   shell          bash -n and shellcheck -x on every tracked or new shell script outside
#                  the constitution (scripts/, toolchain/, datasets/scripts/, tests/build/)
#   schema         datasets/scripts/validate-manifest.py (dataset manifest schema, sample
#                  checksums and sizes); scripts/lib/plan_summary.py compiles
#   terraform      terraform fmt -check -recursive infra, then for each of the five stacks
#                  terraform init -backend=false -lockfile=readonly (providers from the
#                  registry into a shared plugin cache, no backend, no AWS call) and
#                  terraform validate
#   sbt            ONE sbt run: `sbt check` = scalafmt check (sources and build), compile,
#                  Test/compile, every project's tests including tests/contracts and
#                  tests/adversarial (no AWS), and markTestRun
# No AWS call, no plan, no apply, no image build. Works from a clean checkout once the
# toolchain prerequisites are installed (toolchain/install.sh, then source
# toolchain/env.sh): git, java, sbt, terraform, shellcheck, python3.
# Exit codes: 0 every gate passed, 1 at least one gate failed, 2 usage or missing tool.

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,5p' "$0"; exit 2; }
[[ $# -eq 0 ]] || usage

for t in git java sbt terraform shellcheck python3 timeout find; do
  if ! command -v "$t" >/dev/null 2>&1; then
    printf '==> [check] FAILED: required tool not found on PATH: %s (run: source toolchain/env.sh)\n' "$t" >&2
    exit 2
  fi
done

cd "$SC_ROOT"
export TF_PLUGIN_CACHE_DIR="${TF_PLUGIN_CACHE_DIR:-$HOME/.terraform.d/plugin-cache}"
mkdir -p "$TF_PLUGIN_CACHE_DIR"

FAILED=()
# gate NAME CMD...: runs one gate with errexit off, records PASS or FAIL.
gate() {
  local name="$1" rc=0
  shift
  sc_step "$name" "$*"
  "$@" || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    printf '==> [check] PASS %s\n' "$name"
  else
    printf '==> [check] FAIL %s (exit %s)\n' "$name" "$rc"
    FAILED+=("$name")
  fi
}

# Files git knows of (tracked, plus new files that are not ignored), NUL-safe.
repo_files() { git ls-files -z --cached --others --exclude-standard -- "$@"; }

check_constitution() {
  timeout 600s .claude/hooks/validate-agent-files.sh && timeout 600s .agents/render.sh --check
}

check_json() {
  local -a files=() f
  mapfile -d '' -t files < <(repo_files .agents/models.json .mcp.json .claude/settings.json .codex/hooks.json 'infra/environments/*/*.json')
  [[ "${#files[@]}" -gt 0 ]] || return 1
  for f in "${files[@]}"; do
    timeout 60s python3 -I -c 'import json, sys
json.load(open(sys.argv[1], encoding="utf-8"))' "$f" || { printf 'invalid JSON: %s\n' "$f"; return 1; }
  done
  printf '%s JSON files parse\n' "${#files[@]}"
}

check_hooks() {
  local -a sh=() py=() f
  mapfile -d '' -t sh < <(repo_files '.claude/hooks/*.sh' .agents/githooks/pre-commit)
  mapfile -d '' -t py < <(repo_files '.claude/hooks/*.py')
  for f in "${sh[@]}"; do bash -n "$f" || { printf 'syntax error: %s\n' "$f"; return 1; }; done
  for f in "${py[@]}"; do
    timeout 60s python3 -I -c 'import ast, sys
ast.parse(open(sys.argv[1], encoding="utf-8").read(), sys.argv[1])' "$f" || { printf 'syntax error: %s\n' "$f"; return 1; }
  done
  printf '%s hook shell files and %s hook Python files parse\n' "${#sh[@]}" "${#py[@]}"
}

# bash -n on every shell file; shellcheck on every one that declares its shell (a
# shebang, or a "# shellcheck shell=" directive for a sourced library). The two sourced
# toolchain settings files (toolchain/env.sh, toolchain/versions.sh) declare none on
# purpose (they are also read by zsh), so they get bash -n only.
check_shell() {
  local -a files=() lint=() f
  mapfile -d '' -t files < <(repo_files 'scripts/*.sh' 'toolchain/*.sh' 'datasets/scripts/*.sh' 'tests/build/*.sh')
  [[ "${#files[@]}" -gt 0 ]] || return 1
  for f in "${files[@]}"; do
    bash -n "$f" || { printf 'syntax error: %s\n' "$f"; return 1; }
    if head -1 "$f" | grep -q '^#!' || grep -q -m1 '^# shellcheck shell=' "$f"; then lint+=("$f"); fi
  done
  timeout 300s shellcheck -x -P scripts "${lint[@]}" || return 1
  printf '%s shell files pass bash -n; %s of them pass shellcheck\n' "${#files[@]}" "${#lint[@]}"
}

check_schema() {
  timeout 300s python3 -I datasets/scripts/validate-manifest.py datasets/dataset-manifest.yaml &&
    timeout 60s python3 -I -c 'import ast, sys
ast.parse(open(sys.argv[1], encoding="utf-8").read(), sys.argv[1])' scripts/lib/plan_summary.py
}

check_terraform() {
  local s
  timeout 300s terraform fmt -check -recursive -diff infra || return 1
  for s in "${SC_STACKS[@]}"; do
    printf -- '--- %s\n' "$s"
    timeout 900s terraform -chdir="infra/stacks/$s" init -backend=false -input=false -lockfile=readonly -no-color >/dev/null || return 1
    timeout 900s terraform -chdir="infra/stacks/$s" validate -no-color || return 1
  done
}

check_sbt() { timeout 45m sbt -batch check; }

gate constitution check_constitution
gate json check_json
gate hooks check_hooks
gate shell check_shell
gate schema check_schema
gate terraform check_terraform
gate sbt check_sbt

if [[ "${#FAILED[@]}" -gt 0 ]]; then
  printf '==> [check] RESULT FAIL: %s of 7 gates failed: %s\n' "${#FAILED[@]}" "$(IFS=' ' && printf '%s' "${FAILED[*]}")"
  exit 1
fi
printf '==> [check] RESULT PASS: 7 of 7 gates passed at %s\n' "$(sc_head)"
