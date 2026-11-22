#!/usr/bin/env bash
# .agents/render.sh - render the harness-neutral sources into harness adapters.
#   source of truth                      generated (committed, never hand-edited)
#   .agents/roles/<name>.md         ->   .claude/agents/<name>.md, .codex/agents/<name>.toml
#   .agents/skills/<name>/SKILL.md  ->   .claude/commands/<name>.md (Codex reads the skill
#                                        natively from .agents/skills/)
#   .agents/models.json                  tier -> model and reasoning effort per harness;
#                                        each role names a tier (high | standard); the
#                                        low tier must be mapped too, but no role may
#                                        name it (routing suite and step-downs only)
# Usage: .agents/render.sh            write the adapters
#        .agents/render.sh --check    exit 1 if any adapter is missing, stale or orphaned,
#                                     or any tier is missing or unmapped
# Harnesses rendered: claude codex (set HARNESSES to override).
# Owner: factory-engineer (constitutional). Runs in the factory procedure and in the
# pre-commit gate.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root"
mode="${1:-write}"
case "$mode" in write|--check) ;; *) echo "usage: $0 [--check]" >&2; exit 2 ;; esac
export HARNESSES="${HARNESSES:-claude codex}"

python3 - "$mode" <<'PY'
import os, re, sys, json, pathlib, tempfile, filecmp, shutil

mode = sys.argv[1]
harnesses = os.environ["HARNESSES"].split()
TIERS = ("high", "standard")          # tiers a role may name
MODEL_TIERS = TIERS + ("low",)        # tiers models.json must map for every harness
MARK = "GENERATED from {src} by .agents/render.sh - do not edit; edit the source and re-run."

def split_front(text, path):
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.S)
    if not m:
        sys.exit(f"render: {path}: missing frontmatter between --- lines")
    front, body = m.group(1), m.group(2)
    fields, key = {}, None
    for line in front.splitlines():
        if re.match(r"^[A-Za-z_-]+:", line):
            key, _, val = line.partition(":")
            key = key.strip(); fields[key] = val.strip()
        elif key and line.startswith((" ", "\t")):
            fields[key] = (fields[key] + " " + line.strip()).strip()
    return front, fields, body

def toml_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

def toml_ml(s):
    if "'''" not in s:
        return "'''\n" + s + "'''"
    esc = s.replace("\\", "\\\\").replace('"""', '\\"\\"\\"')
    return '"""\n' + esc + '"""'

def tool_list(tools):
    return [x.strip() for x in tools.split(",") if x.strip()]

def fence_text(tools):
    t = tool_list(tools)
    builtin = [x for x in t if not x.startswith("mcp__")]
    mcp = [x for x in t if x.startswith("mcp__")]
    lines = ["", "## Tool fence (Codex: instruction-only, binding)",
             "Your allowed tools are exactly: " + ", ".join(t) + ".",
             "Claude Code enforces this list per agent; Codex cannot, so under Codex it"
             " binds you by instruction, and the reviewer and the pre-commit gate check it.",
             "Codex tool mapping: Read, Grep and Glob are read-only shell commands;"
             " Write and Edit are apply_patch; Bash is the shell."]
    if not ({"Write", "Edit"} & set(builtin)):
        lines.append("You must not create, edit, move or delete any file: never call"
                     " apply_patch and never write files from the shell; you report only.")
    elif "Write" not in builtin:
        lines.append("You must not create new files; you only edit existing ones.")
    if "Bash" not in builtin:
        lines.append("You must not run shell commands.")
    if mcp:
        lines.append("The only MCP tools you may call are: " + ", ".join(mcp) + ".")
    else:
        lines.append("You must not call any MCP tool.")
    lines.append(".claude/hooks/guard-ownership.sh enforces your write territory from"
                 " docs/agents.md under both harnesses.")
    return "\n".join(lines) + "\n"

def load_models():
    p = pathlib.Path(".agents/models.json")
    if not p.exists():
        sys.exit("render: .agents/models.json is missing (tier -> model mapping)")
    m = json.loads(p.read_text())
    for h in harnesses:
        if h not in m:
            sys.exit(f"render: models.json has no section for harness '{h}'")
        for tier in MODEL_TIERS:
            if not m[h].get(tier, {}).get("model"):
                sys.exit(f"render: models.json: tier '{tier}' has no model for {h}")
    return m

def strip_tier(front):
    return "\n".join(l for l in front.splitlines() if not l.startswith("tier:"))

def render(out):
    models = load_models()
    roles = sorted(pathlib.Path(".agents/roles").glob("*.md"))
    if not roles:
        sys.exit("render: no role files in .agents/roles/")
    names = set()
    for p in roles:
        front, f, body = split_front(p.read_text(), p)
        for k in ("name", "description", "tools", "tier"):
            if not f.get(k):
                sys.exit(f"render: {p}: frontmatter field '{k}' is required")
        if f["name"] != p.stem:
            sys.exit(f"render: {p}: name '{f['name']}' must equal the file name")
        if f["name"] in names:
            sys.exit(f"render: duplicate agent name {f['name']}")
        names.add(f["name"])
        tier = f["tier"]
        if tier not in TIERS:
            sys.exit(f"render: {p}: tier '{tier}' must be one of {', '.join(TIERS)}")
        for h in harnesses:
            if tier not in models[h]:
                sys.exit(f"render: {p}: tier '{tier}' has no mapping for {h} in models.json")
        mark = MARK.format(src=str(p))
        if "claude" in harnesses:
            d = out / ".claude/agents"; d.mkdir(parents=True, exist_ok=True)
            c = models["claude"][tier]
            head = strip_tier(front) + f"\nmodel: {c['model']}"
            if c.get("effort"):
                head += f"\neffort: {c['effort']}"
            (d / p.name).write_text(f"---\n{head}\n---\n<!-- {mark} -->\n{body}")
        if "codex" in harnesses:
            d = out / ".codex/agents"; d.mkdir(parents=True, exist_ok=True)
            x = models["codex"][tier]
            toml = [f"# {mark}",
                    f"name = {toml_str(f['name'])}",
                    f"description = {toml_str(f['description'])}",
                    f"model = {toml_str(x['model'])}"]
            if x.get("model_reasoning_effort"):
                toml.append("model_reasoning_effort = "
                            + toml_str(x["model_reasoning_effort"]))
            toml.append("developer_instructions = "
                        + toml_ml(body.lstrip("\n") + fence_text(f["tools"])))
            if "Bash" not in tool_list(f["tools"]):
                toml += ["", "[features]", "shell_tool = false"]
            (d / (p.stem + ".toml")).write_text("\n".join(toml) + "\n")
    for s in sorted(pathlib.Path(".agents/skills").glob("*/SKILL.md")):
        front, f, body = split_front(s.read_text(), s)
        if f.get("name") != s.parent.name:
            sys.exit(f"render: {s}: name must equal its folder name")
        if not f.get("description"):
            sys.exit(f"render: {s}: frontmatter field 'description' is required")
        if "claude" in harnesses:
            d = out / ".claude/commands"; d.mkdir(parents=True, exist_ok=True)
            head = [f"description: {f['description']}"]
            if f.get("allowed-tools"):
                head.append(f"allowed-tools: {f['allowed-tools']}")
            (d / (s.parent.name + ".md")).write_text(
                "---\n" + "\n".join(head) + "\n---\n"
                + f"<!-- {MARK.format(src=str(s))} -->\n" + body
                + "\nArguments given by the user: $ARGUMENTS\n")

def generated(base):
    found = {}
    for sub in (".claude/agents", ".codex/agents", ".claude/commands"):
        for p in (base / sub).glob("*"):
            if p.is_file() and "GENERATED from" in p.read_text(errors="ignore")[:2000]:
                found[str(p.relative_to(base))] = p
    return found

tmp = pathlib.Path(tempfile.mkdtemp())
try:
    render(tmp)
    want, have = generated(tmp), generated(pathlib.Path("."))
    import tomllib
    for k, p in want.items():
        if k.endswith(".toml"):
            tomllib.loads(p.read_text())
    drift = sorted(k for k in want
                   if k not in have or not filecmp.cmp(want[k], have[k], shallow=False))
    orphans = sorted(k for k in have if k not in want)
    if mode == "--check":
        for k in drift: print(f"STALE or MISSING: {k}")
        for k in orphans: print(f"ORPHAN (no source): {k}")
        if drift or orphans:
            print("render --check FAILED: run .agents/render.sh and commit the result")
            sys.exit(1)
        print(f"render --check OK: {len(want)} generated files match their sources")
    else:
        for k in drift:
            dst = pathlib.Path(k); dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(want[k], dst); print(f"wrote {k}")
        for k in orphans:
            pathlib.Path(k).unlink(); print(f"removed orphan {k}")
        print(f"render OK: {len(want)} generated files")
finally:
    shutil.rmtree(tmp)
PY
