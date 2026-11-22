#!/usr/bin/env bash
# Stop and SubagentStop hook - the completion-gate archetype. Harness-neutral: registered
# by .claude/settings.json and .codex/hooks.json; both send stop_hook_active and both
# treat exit 2 with stderr as "keep working". Writes nothing to stdout, because Codex
# rejects plain-text stdout on Stop.
# If source files changed (staged, unstaged or untracked) and the test marker written by
# 'sbt check' (markTestRun) is older than the newest change, block the stop once and tell
# the agent to run the check. The stop_hook_active check prevents an endless loop.
# Owner: factory-engineer. Floor invariant: never remove the stop_hook_active check.
set -euo pipefail

payload=$(cat)
already=$(printf '%s' "$payload" | python3 -c '
import json, sys
print(json.load(sys.stdin).get("stop_hook_active", False))
' 2>/dev/null || echo False)
[ "$already" = "True" ] && exit 0

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root"
specs=('*.scala' '*.sbt' '*.java' 'project/*' 'modules/*/src/main/resources/*' 'prompts/*')
changed=$( { git diff --name-only -- "${specs[@]}";
             git diff --cached --name-only -- "${specs[@]}";
             git ls-files --others --exclude-standard -- "${specs[@]}"; } 2>/dev/null \
           | sort -u)
[ -z "$changed" ] && exit 0   # no source changes, nothing to verify

marker=".claude/.last-test-run"
# newer PATH: PATH, or for a deleted file its nearest existing ancestor directory (whose
# mtime records the deletion), is newer than the marker.
newer() { local p=$1; while [ ! -e "$p" ]; do p=$(dirname "$p"); done; [ "$p" -nt "$marker" ]; }
if [ -f "$marker" ]; then
  stale=0
  while IFS= read -r f; do
    newer "$f" && { stale=1; break; }
  done <<< "$changed"
  [ "$stale" -eq 0 ] && exit 0   # tests passed after the newest change
fi

echo "Source files changed but the tests have not passed since the newest change" \
     "(.claude/.last-test-run is missing or older). Run 'sbt check' and fix every" \
     "failure before you finish; report BLOCKED-ON after 3 failed attempts." >&2
exit 2
