#!/usr/bin/env bash
# PostToolUse hook - the normalizer archetype. Harness-neutral: registered by
# .claude/settings.json (matcher Write|Edit|MultiEdit) and .codex/hooks.json (matcher
# apply_patch|Edit|Write).
# Finds the touched files in the payload: Claude Code sends tool_input.file_path; Codex
# apply_patch sends the patch text in tool_input.command ("*** Add File: <path>",
# "*** Update File: <path>", "*** Move to: <path>"). Formats each touched *.scala or
# *.sbt file with scalafmt and does nothing when no Scala file changed.
# Owner: factory-engineer. Floor invariant: this hook always exits 0, because formatting
# is a convenience and never a blocker.
set -uo pipefail

payload=$(cat)
files=$(printf '%s' "$payload" | python3 -c '
import json, os, re, sys
d = json.load(sys.stdin)
ti = d.get("tool_input", {}) or {}
cwd = d.get("cwd") or os.getcwd()
out = []
if ti.get("file_path"):
    out.append(ti["file_path"])
patch = ti.get("command") or ti.get("patch") or ""
if isinstance(patch, list):
    patch = " ".join(map(str, patch))
for m in re.finditer(r"^\*\*\* (?:Add File|Update File|Move to): (.+)$", str(patch), re.M):
    out.append(m.group(1).strip())
print("\n".join(dict.fromkeys(os.path.join(cwd, p) for p in out)))
' 2>/dev/null || true)

while IFS= read -r file; do
  [ -z "$file" ] && continue
  [ -f "$file" ] || continue
  case "$file" in
    *.scala|*.sbt)
      if command -v scalafmt >/dev/null 2>&1; then
        scalafmt --quiet "$file" >/dev/null 2>&1 \
          || echo "format-scala hook: scalafmt failed on $file; run 'sbt fmt'" >&2
      else
        echo "format-scala hook: scalafmt is not installed (toolchain/); $file left" \
             "unformatted, 'sbt check' will flag it" >&2
      fi
      ;;
  esac
done <<< "$files"
exit 0
