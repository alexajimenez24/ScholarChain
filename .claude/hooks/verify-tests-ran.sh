#!/usr/bin/env bash
# Stop and SubagentStop hook - the completion-gate archetype. Harness-neutral: registered
# by .claude/settings.json and .codex/hooks.json; both send stop_hook_active and both
# treat exit 2 with stderr as "keep working". Writes nothing to stdout, because Codex
# rejects plain-text stdout on Stop.
# If source files changed (staged, unstaged or untracked) and the test marker written by
# 'sbt check' (markTestRun) is older than the newest change, block the stop once and tell
# the agent to run the check. The stop_hook_active check prevents an endless loop.
# A subagent's sbt run in progress (Phase 11 C08): the main session (hook_event_name
# Stop, never SubagentStop) is let through only while a live sbt process works in this
# repository: a java process whose command line names the sbt launcher (sbt-launch,
# xsbt.boot.Boot, sbt.xMain) or a process named sbt or sbtn, whose /proc/<pid>/cwd is the
# repository root, not a zombie, and younger than 45 minutes (the sbt check timeout).
# Process text matching (pgrep -f) is never used, because it matches itself. Without such
# a process, a stale or missing marker is refused exactly as before; a payload that is not
# JSON has no event name and is refused as well.
# SCHOLARCHAIN_STOP_SBT_MAX_AGE (seconds) can only lower the 45-minute bound (tests).
# Owner: factory-engineer. Floor invariant: never remove the stop_hook_active check.
set -euo pipefail

payload=$(cat)
already=$(printf '%s' "$payload" | python3 -c '
import json, sys
print(json.load(sys.stdin).get("stop_hook_active", False))
' 2>/dev/null || echo False)
[ "$already" = "True" ] && exit 0
event=$(printf '%s' "$payload" | python3 -c '
import json, sys
print(json.load(sys.stdin).get("hook_event_name") or "")
' 2>/dev/null || echo "")

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

# sbt_live ROOT: prints "live" when a live sbt process works in ROOT (see the header).
sbt_live() {
  python3 - "$1" <<'PY' 2>/dev/null || true
import os, re, sys
root = os.path.realpath(sys.argv[1])
limit = 2700
try: limit = max(0, min(limit, int(os.environ.get("SCHOLARCHAIN_STOP_SBT_MAX_AGE", limit))))
except ValueError: limit = 0
tick = os.sysconf("SC_CLK_TCK")
uptime = float(open("/proc/uptime").read().split()[0])
for pid in os.listdir("/proc"):
    if not pid.isdigit() or int(pid) == os.getpid(): continue
    try:
        if os.path.realpath(os.readlink("/proc/%s/cwd" % pid)) != root: continue
        stat = open("/proc/%s/stat" % pid).read()
        fields = stat[stat.rindex(")") + 2:].split()
        if fields[0] in ("Z", "X", "x"): continue
        comm = open("/proc/%s/comm" % pid).read().strip()
        line = open("/proc/%s/cmdline" % pid, "rb").read().replace(b"\0", b" ").decode("utf-8", "replace")
        if comm not in ("sbt", "sbtn") and not (
                comm == "java" and re.search(r"sbt-launch|xsbt\.boot\.Boot|sbt\.xMain", line)):
            continue
        age = uptime - int(fields[19]) / tick
        if 0 <= age < limit:
            print("live"); break
    except (OSError, ValueError, IndexError):
        continue
PY
}
if [ "$event" = "Stop" ] && [ "$(sbt_live "$root")" = "live" ]; then
  exit 0   # a subagent's sbt run is in progress; its own Stop gate still applies
fi

echo "Source files changed but the tests have not passed since the newest change" \
     "(.claude/.last-test-run is missing or older). Run 'sbt check' and fix every" \
     "failure before you finish; report BLOCKED-ON after 3 failed attempts." >&2
exit 2
