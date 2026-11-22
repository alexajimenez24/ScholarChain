#!/usr/bin/env bash
# Agent-file validator. Harness-neutral.
#   .claude/hooks/validate-agent-files.sh          validate everything; exit 1 on failure
#   .claude/hooks/validate-agent-files.sh --hook   PostToolUse hook: validate only when the
#                                                  payload touched a control-plane file;
#                                                  exit 2 sends the failures to the model
# Checks: JSON and TOML parse; role and generated agent frontmatter (fenced, name equals
# file name, unique names, description, explicit tools, tier); reviewer tool fences; MCP
# fences and version pins; hook syntax, executable bits and registration in both
# harnesses; floor deny rules; one writer per tracked file; no unresolved placeholders.
# Called by the pre-commit gate. Owner: factory-engineer (constitutional).
set -uo pipefail
mode="${1:-all}"
payload=""
[ "$mode" = "--hook" ] && payload=$(cat)
root=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "not in a git repo" >&2; exit 1; }
cd "$root"

if [ "$mode" = "--hook" ]; then
  touched=$(printf '%s' "$payload" | python3 -c '
import json, re, sys
d = json.load(sys.stdin); ti = d.get("tool_input", {}) or {}
p = [ti.get("file_path") or ""]
p += re.findall(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", str(ti.get("command") or ""), re.M)
print("\n".join(x for x in p if x))' 2>/dev/null || true)
  printf '%s\n' "$touched" | grep -Eq '(^|/)(AGENTS\.md|CLAUDE\.md|docs/agents\.md|\.mcp\.json)$|(^|/)\.(agents|claude|codex)/' \
    || exit 0
fi

# Shell checks: syntax and executable bit of every hook and script the factory owns.
fail=0
for h in .claude/hooks/*.sh .agents/*.sh .agents/githooks/*; do
  [ -f "$h" ] || continue
  bash -n "$h" 2>/dev/null || { echo "FAIL bash -n: $h" >&2; fail=1; }
  [ -x "$h" ] || { echo "FAIL not executable (chmod +x): $h" >&2; fail=1; }
done

MODE="$mode" python3 - <<'PY' || fail=1
import json, os, re, subprocess, sys, tomllib, pathlib
strict = os.environ["MODE"] != "--hook"
errs = []
def err(m): errs.append(m)
P = pathlib.Path

def load_json(p):
    try: return json.loads(P(p).read_text())
    except Exception as e: err(f"JSON parse {p}: {e}"); return {}
def load_toml(p):
    try: return tomllib.loads(P(p).read_text())
    except Exception as e: err(f"TOML parse {p}: {e}"); return {}

settings = load_json(".claude/settings.json")
chooks = load_json(".codex/hooks.json")
mcp = load_json(".mcp.json").get("mcpServers", {})
models = load_json(".agents/models.json")
cfg = load_toml(".codex/config.toml")

def front(p):
    t = P(p).read_text()
    m = re.match(r"^---\n(.*?)\n---\n", t, re.S)
    if not m: err(f"{p}: frontmatter is not fenced by two --- lines"); return {}
    try:
        import yaml
        f = yaml.safe_load(m.group(1))
        if not isinstance(f, dict): raise ValueError("not a mapping")
        return {k: (", ".join(v) if isinstance(v, list) else str(v)) for k, v in f.items()}
    except ImportError:
        f, key = {}, None
        for line in m.group(1).splitlines():
            if re.match(r"^[A-Za-z_-]+:", line):
                key, _, v = line.partition(":"); f[key.strip()] = v.strip()
            elif key: f[key] += " " + line.strip()
        return f
    except Exception as e:
        err(f"{p}: frontmatter is not valid YAML: {e}"); return {}

WRITE = {"Write", "Edit", "MultiEdit", "NotebookEdit"}
def tools(f): return [t.strip() for t in f.get("tools", "").split(",") if t.strip()]

# Roles (sources).
roles = {}
for p in sorted(P(".agents/roles").glob("*.md")):
    f = front(p)
    n = f.get("name", "")
    if n != p.stem: err(f"{p}: name '{n}' must equal the file name")
    if not re.fullmatch(r"[a-z][a-z0-9-]*", n): err(f"{p}: name is not kebab-case")
    if n in roles: err(f"{p}: duplicate agent name {n}")
    for k in ("description", "tools", "tier"):
        if not f.get(k): err(f"{p}: frontmatter field '{k}' is missing or empty")
    if f.get("tier") not in ("high", "standard"): err(f"{p}: tier must be high or standard")
    if "model" in f or "effort" in f: err(f"{p}: a role names a tier, never a model")
    roles[n] = f
if not roles: err("no role files in .agents/roles/")

# Generated adapters.
gen = {}
for p in sorted(P(".claude/agents").glob("*.md")):
    f = front(p); n = f.get("name", "")
    if n != p.stem: err(f"{p}: name '{n}' must equal the file name")
    if n in gen: err(f"{p}: duplicate agent name {n}")
    if not f.get("tools"): err(f"{p}: tools field missing (omission inherits every tool)")
    if not f.get("model") or not f.get("effort"): err(f"{p}: model or effort missing")
    gen[n] = f
cdx = {}
for p in sorted(P(".codex/agents").glob("*.toml")):
    t = load_toml(p)
    for k in ("name", "description", "developer_instructions", "model"):
        if not t.get(k): err(f"{p}: '{k}' missing")
    if t.get("name") != p.stem: err(f"{p}: name must equal the file name")
    cdx[t.get("name")] = t
if strict:
    if set(gen) != set(roles): err(f".claude/agents does not match .agents/roles: {sorted(set(gen) ^ set(roles))}")
    if set(cdx) != set(roles): err(f".codex/agents does not match .agents/roles: {sorted(set(cdx) ^ set(roles))}")

# Reviewer fences: review-only roles never hold a write tool.
for n, f in roles.items():
    review_only = n == "code-reviewer" or "Read-only" in f.get("description", "")
    if review_only and WRITE & set(tools(f)): err(f"{n}: review-only role holds a write tool")
for n, f in gen.items():
    if n == "code-reviewer" and WRITE & set(tools(f)): err(".claude/agents/code-reviewer.md holds a write tool")

# MCP: tool names reference configured servers; every server fenced or orchestrator-only;
# Codex mirror has the same servers and pins; no unpinned launches.
agents_md = P("docs/agents.md").read_text() if P("docs/agents.md").exists() else ""
granted = {}
for n, f in roles.items():
    for t in tools(f):
        if t.startswith("mcp__"):
            key = t.split("__")[1]
            if key not in mcp: err(f"{n}: tool {t} names server '{key}' absent from .mcp.json")
            granted.setdefault(key, set()).add(n)
for key, s in mcp.items():
    orch_only = re.search(rf"^\| `{re.escape(key)}` \|.*\| orchestrator only \|$", agents_md, re.M)
    if key not in granted and not orch_only:
        err(f"MCP server '{key}' is neither granted to an agent nor recorded orchestrator-only")
    if key in granted and orch_only: err(f"MCP server '{key}' is orchestrator-only but granted")
    if any("@latest" in a for a in s.get("args", [])): err(f"MCP server '{key}' is unpinned")
cx = cfg.get("mcp_servers", {})
if set(cx) != set(mcp): err(f".codex/config.toml mcp_servers {sorted(cx)} != .mcp.json {sorted(mcp)}")
for key in set(cx) & set(mcp):
    if cx[key].get("args", []) != mcp[key].get("args", []): err(f"MCP '{key}': args differ between harnesses")
    if cx[key].get("url") != mcp[key].get("url"): err(f"MCP '{key}': url differs between harnesses")

# Models: every tier mapped for both harnesses (low: routing suite and step-downs only).
for h in ("claude", "codex"):
    for tier in ("high", "standard", "low"):
        if not models.get(h, {}).get(tier, {}).get("model"): err(f"models.json: {h}.{tier} unmapped")

# Hook registration in both harnesses, and floor content.
hooks = ["guard-dangerous.sh", "guard-ownership.sh", "format-scala.sh", "verify-tests-ran.sh",
         "validate-agent-files.sh"]
def cmds(h): return " ".join(x.get("command", "") for ev in h.get("hooks", {}).values()
                             for grp in ev for x in grp.get("hooks", []))
for name, reg in (("claude", settings), ("codex", chooks)):
    c = cmds(reg)
    for h in hooks:
        if h not in c: err(f"{name}: hook {h} is not registered")
    for ref in re.findall(r"\.claude/hooks/([A-Za-z0-9_.-]+\.sh)", c):
        if not P(".claude/hooks", ref).exists(): err(f"{name}: registered hook {ref} does not exist")
    for ev in ("PreToolUse", "PostToolUse", "Stop", "SubagentStop"):
        if ev not in reg.get("hooks", {}): err(f"{name}: no {ev} hooks registered")
deny_rules = set(settings.get("permissions", {}).get("deny", []))
for must in ("Read(./.env)", "Read(./.env.*)", "Read(./secrets/**)", "Bash(terraform apply*)",
             "Bash(terraform destroy*)", "Bash(terraform * apply*)", "Bash(terraform * destroy*)",
             "Bash(git push --force*)", "Bash(git push -f*)", "Bash(git push * --force*)",
             "Bash(git push --force-with-lease*)", "Bash(aws s3 rb*)",
             "Bash(aws s3 rm * --recursive*)", "Bash(aws dynamodb delete-table*)",
             "Bash(aws neptune delete-db-cluster*)", "Bash(aws s3vectors delete-vector-bucket*)",
             "Bash(aws s3vectors delete-index*)", "Bash(aws ecr delete-repository*)",
             "Bash(aws kms schedule-key-deletion*)", "Bash(aws emr terminate-clusters*)",
             "Edit(./docs/agent-runs/**/human-gate.md)", "Write(./docs/agent-runs/**/human-gate.md)",
             "Bash(./scripts/cleanup.sh*)", "Read(**/*.env)", "Read(**/.envrc)",
             "Read(**/*.tfvars)", "Read(~/.ssh/**)", "Read(~/.aws/sso/**)",
             "Read(~/.aws/cli/cache/**)", "Read(~/.claude/.credentials.json)",
             "Read(~/.config/gh/hosts.yml)", "Read(~/.codex/auth.json)",
             "Edit(./datasets/gold/**)", "Write(./datasets/gold/**)"):
    if must not in deny_rules: err(f"settings.json deny list lacks the floor rule {must}")
g = P(".claude/hooks/guard-dangerous.sh").read_text()
for must in ("terraform destroy", "push --force", "s3 rb", "secret file", "pkexec",
             "privilege escalation", "last-test-run", "markTestRun", "INCIDENT_SCALE_MAX",
             "datasets/gold", ".codex/auth", ".ssh", ".aws/sso"):
    if must not in g: err(f"guard-dangerous.sh lost floor pattern '{must}'")
if "stop_hook_active" not in P(".claude/hooks/verify-tests-ran.sh").read_text():
    err("verify-tests-ran.sh lost its stop_hook_active check")
if not P(".claude/hooks/format-scala.sh").read_text().rstrip().endswith("exit 0"):
    err("format-scala.sh must always exit 0")

# Territories: every actor known, every non-review role has one, one writer per file.
m = re.search(r"```territory\n(.*?)```", agents_md, re.S)
rules = [l.split() for l in (m.group(1).splitlines() if m else []) if l.strip()]
if not rules: err("docs/agents.md has no territory block")
for r in rules:
    if len(r) != 3 or r[1] not in ("allow", "except", "append", "values"):
        err(f"territory rule malformed: {' '.join(r)}")
pseudo = {"orchestrator", "human", "generated", "none"}
actors = {r[0] for r in rules if len(r) == 3}
for a in actors - pseudo - set(roles): err(f"territory actor '{a}' is not a role")
for n in set(roles) - actors:
    if n != "code-reviewer": err(f"role {n} has no write territory")
def glob_re(gl):
    out, i = "", 0
    while i < len(gl):
        if gl.startswith("**/", i): out += "(?:.*/)?"; i += 3
        elif gl.startswith("**", i): out += ".*"; i += 2
        elif gl[i] == "*": out += "[^/]*"; i += 1
        elif gl[i] == "?": out += "[^/]"; i += 1
        else: out += re.escape(gl[i]); i += 1
    return re.compile(out + r"\Z")
rules = [r for r in rules if len(r) == 3]
def writers(rel):
    w = set()
    for a in actors:
        exc = any(x == a and md == "except" and glob_re(gl).match(rel) for x, md, gl in rules)
        for x, md, gl in rules:
            if x == a and glob_re(gl).match(rel) and (md == "append" or (md == "allow" and not exc)):
                w.add(a)
    return w
files = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard"],
                       capture_output=True, text=True).stdout.split()
samples = ["build.sbt", "project/plugins.sbt", "project/Versions.scala", ".gitignore",
  ".scalafmt.conf", "docker-compose.yml", "toolchain/ledger.md", "toolchain/install.sh",
  "datasets/dataset-manifest.yaml", "tests/module-unit/datasets/ManifestSuite.scala",
  "tests/contracts/WireSuite.scala", "tests/adversarial/XmlSuite.scala",
  "tests/integration/LoadSuite.scala", "tests/end-to-end/QuerySuite.scala",
  "prompts/runtime/synthesis-v1.md", "infra/stacks/app/main.tf", "infra/modules/ecs-service/main.tf", "scripts/deploy.sh",
  "scripts/cleanup.sh", ".github/workflows/ci.yml", "releases/2026-12-01.md",
  "experiments/a/manifest.yaml", "experiments/a/raw/m.csv", "load-tests/d/raw/r.json",
  "load-tests/d/plan.yaml", "reports/final-report.md", "STATUS.md", "PROJECT_MEMORY.md",
  "docs/architecture.md", "docs/incidents/2026-12-01-x.md",
  "docs/agent-runs/phase-01/prompt.md", "docs/agent-runs/phase-01/human-gate.md",
  "datasets/gold/labels.csv",
  ".claude/.last-test-run", ".claude/settings.local.json", ".claude/agents/x.md"]
for mod in ("core", "hadoop", "entity-resolution", "spark", "storage", "rag", "services",
            "services/common", "services/query", "services/graph", "services/worker"):
    samples += [f"modules/{mod}/src/main/scala/X.scala", f"modules/{mod}/MODULE.md",
                f"tests/module-unit/{mod}/XSuite.scala"]
samples.append("modules/entity-resolution/src/main/resources/adjudication-v1.md")
for rel in sorted(set(files) | set(samples)):
    w = writers(rel)
    if len(w) != 1: err(f"one-writer check: {rel} has writers {sorted(w) or 'none'}")
# Paths no agent may write keep their non-agent owner.
for rel, who in (("datasets/gold/labels.csv", "human"), (".claude/.last-test-run", "none"),
                 ("docs/agent-runs/phase-01/human-gate.md", "human")):
    if writers(rel) != {who}: err(f"territory: {rel} must be written only by {who}")

# No unresolved template placeholders in control files (list A and its generated
# adapters; never docs/agent-runs/phase-*, which is human evidence quoted verbatim).
ctl = [p for p in ["AGENTS.md", "CLAUDE.md", "docs/agents.md", ".mcp.json",
                   "docs/agent-runs/README.md"] if P(p).exists()]
for base in (".agents", ".claude", ".codex"):
    ctl += [str(p) for p in P(base).rglob("*") if p.is_file()]
for p in ctl:
    if re.search(r"\{\{[A-Z_]+\}\}", P(p).read_text(errors="ignore")): err(f"{p}: unresolved template placeholder")

# Skills name an existing agent.
for s in sorted(P(".agents/skills").glob("*/SKILL.md")):
    f = front(s)
    if f.get("name") != s.parent.name: err(f"{s}: name must equal its folder")
    named = set(re.findall(r"\*\*([a-z-]+)\*\*", s.read_text()))
    if not named or not named <= set(roles): err(f"{s}: responsible agent missing or unknown: {named}")

for e in errs: print("FAIL " + e, file=sys.stderr)
if errs: sys.exit(1)
print(f"validate-agent-files OK: {len(roles)} roles, {len(gen)} Claude agents, "
      f"{len(cdx)} Codex agents, {len(mcp)} MCP servers, {len(files)} files with one writer")
PY

if [ "$fail" -ne 0 ]; then
  echo "validate-agent-files: FAILED (see FAIL lines above)" >&2
  [ "$mode" = "--hook" ] && exit 2
  exit 1
fi
exit 0
