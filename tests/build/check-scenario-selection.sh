#!/usr/bin/env bash
# Regression test for scenario selection in check-contract.sh.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
status=0
output="$(BUILD_CONTRACT_SCENARIOS='   ' "$script_dir/check-contract.sh" 2>&1)" ||
  status=$?

if [[ "$status" != 2 ||
  "$output" != *'SETUP ERROR:'*BUILD_CONTRACT_SCENARIOS*'scenario name'* ]]; then
  printf 'FAIL whitespace-only selection: expected setup error 2, got %s\n' \
    "$status" >&2
  printf '%s\n' "$output" >&2
  exit 1
fi

printf 'PASS whitespace-only scenario selection\n'
