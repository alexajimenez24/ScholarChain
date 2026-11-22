#!/usr/bin/env bash
# .agents/routing-suite.sh - run the routing corpus (.agents/routing-tests.md) against a
# headless router and report every misroute.
#   ROUTER=claude .agents/routing-suite.sh                 (default) Claude Code, claude -p
#   ROUTER=codex  .agents/routing-suite.sh                 Codex, codex exec
#   ROUTER=<h>    .agents/routing-suite.sh --resume <file> only the rows a checkpoint left
# Model: the low tier for that harness from .agents/models.json. A description that the
# cheapest model routes correctly is clear, so the test is both stricter and cheaper.
# Shape: one batched call per harness, all rows in one prompt in a fresh shuffled order
# (SEED=<n> reproduces an order), each request judged independently; the reply is JSON
# lines {"id","agent"}, parsed strictly: a missing, duplicated or unknown row, or any
# line that is not one such object, is a failure. Each row is answered once, never
# retried: a flickering row is a thin margin to flag, not to hide. BATCH=<n> splits the
# rows into calls of n only if a harness ever rejects the prompt size (default: one call).
# Lean calls: the router gets the role names and descriptions and the requests, nothing
# else (no tools, MCP servers, skills, plugins, user settings or project files), from an
# empty temporary directory, so the test measures the descriptions alone.
# Usage limit: when a reply is a usage or rate limit message, the suite stops at once,
# writes a checkpoint (rows done with results, rows remaining; CHECKPOINT=<file>, default
# ${XDG_STATE_HOME:-~/.local/state}/scholarchain/routing-checkpoint.json), prints the
# harness that is out, its reset time if given, and the commands to resume, and exits 75.
# A batch cut by a limit counts as unanswered. A resume refuses a checkpoint whose
# corpus or descriptions changed, and deletes the checkpoint when it completes.
# RESULTS=<file> writes one line per row. TIMEOUT=<s> bounds each call (default 900).
# Exit: 0 every row routes to its expected agent; 1 misroute, missing, duplicated or
# unparseable row, or router error; 2 setup error; 75 usage limit (checkpoint written).
# Owner: factory-engineer (constitutional).
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root"
export ROUTER="${ROUTER:-claude}" ROOT="$root"
case "$ROUTER" in claude|codex) ;; *) echo "unknown ROUTER '$ROUTER'" >&2; exit 2 ;; esac
command -v "$ROUTER" >/dev/null || { echo "routing-suite: '$ROUTER' CLI not installed" >&2; exit 2; }

python3 - "$@" <<'PY'
import atexit, hashlib, json, os, pathlib, random, re, shlex, shutil, subprocess, sys, tempfile, time

EXIT_OK, EXIT_FAIL, EXIT_SETUP, EXIT_LIMIT = 0, 1, 2, 75
router, root = os.environ["ROUTER"], os.environ["ROOT"]
args, resume, corpus = sys.argv[1:], None, ".agents/routing-tests.md"
while args:
    a = args.pop(0)
    if a == "--resume" and args: resume = args.pop(0)
    elif not a.startswith("-"): corpus = a
    else: print(f"usage: routing-suite.sh [--resume <checkpoint>] [corpus]", file=sys.stderr); sys.exit(EXIT_SETUP)
state = pathlib.Path(os.environ.get("XDG_STATE_HOME") or pathlib.Path.home() / ".local/state")
ckpt_path = pathlib.Path(os.environ.get("CHECKPOINT") or resume
                         or state / "scholarchain/routing-checkpoint.json").absolute()

# Catalog and corpus.
roles = {}
for p in sorted(pathlib.Path(".agents/roles").glob("*.md")):
    m = re.match(r"^---\n(.*?)\n---\n", p.read_text(), re.S)
    f, key = {}, None
    for line in m.group(1).splitlines():
        if re.match(r"^[A-Za-z_-]+:", line):
            key, _, v = line.partition(":"); f[key.strip()] = v.strip()
        elif key: f[key] += " " + line.strip()
    roles[f["name"]] = f["description"]
catalog = "\n".join(f"- {n}: {d}" for n, d in roles.items())
rows, unknown = {}, []
for line in pathlib.Path(corpus).read_text().split("## Polysemy registry")[0].splitlines():
    if not line.strip().startswith("|"): continue
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) < 2 or cells[0] == "Request" or set(cells[0]) <= set("-: "): continue
    if cells[1].strip("`") not in roles: unknown.append(line.strip()); continue
    rows[f"R{len(rows) + 1:03d}"] = (cells[0], cells[1].strip("`"))
if unknown:
    for u in unknown: print(f"routing-suite: corpus row names no known agent: {u}", file=sys.stderr)
    sys.exit(EXIT_SETUP)
if not rows:
    print(f"routing-suite: no rows in {corpus}", file=sys.stderr); sys.exit(EXIT_SETUP)
fingerprint = hashlib.sha256((catalog + "\n" + json.dumps(sorted(rows.items()))).encode()).hexdigest()
tier = json.loads(pathlib.Path(".agents/models.json").read_text())[router].get("low")
if not tier or not tier.get("model"):
    print(f"routing-suite: models.json has no low tier for {router}", file=sys.stderr); sys.exit(EXIT_SETUP)
model = tier["model"]

done, prior_out = {}, None
if resume:
    try: ck = json.loads(pathlib.Path(resume).read_text())
    except (OSError, ValueError) as e:
        print(f"routing-suite: cannot read checkpoint {resume}: {e}", file=sys.stderr); sys.exit(EXIT_SETUP)
    if ck.get("fingerprint") != fingerprint:
        print("routing-suite: the corpus or a description changed since the checkpoint; "
              "its results no longer apply. Run the full suite.", file=sys.stderr); sys.exit(EXIT_SETUP)
    done, prior_out = ck["done"], (ck.get("harness_out"), ck.get("reset"))
todo = [i for i in rows if i not in done]
seed = int(os.environ.get("SEED") or random.SystemRandom().randrange(1, 10**9))
random.Random(seed).shuffle(todo)
size = int(os.environ.get("BATCH") or 0) or len(todo)
batches = [todo[k:k + size] for k in range(0, len(todo), size)]
timeout = int(os.environ.get("TIMEOUT") or 900)

SYSTEM = ("You route work requests for the ScholarChain project to exactly one agent.\n"
          "The agents and their routing descriptions are:\n" + catalog + "\n\n"
          "You will receive a list of requests, one per line, each prefixed by its id.\n"
          "Judge each request independently, as if it were the only request you were given: "
          "do not compare requests and do not balance agents across them; one agent may win "
          "many requests or none.\n"
          "For every request choose the ONE agent that should handle it.\n"
          "Output exactly one line per request, in any order, and nothing else: no prose, "
          "no code fences, no blank lines.\n"
          'Each line is a JSON object with exactly two keys: {"id": "<request id>", '
          '"agent": "<agent name>"}. Use only agent names from the list above.')
LIMIT = re.compile(r"hit your\b[^\n]{0,40}\blimit|usage limit|(?:session|weekly|daily|5-hour|"
                   r"five-hour|rate) limit|limit (?:reached|exceeded)|quota exceeded|"
                   r"too many requests|\b429\b", re.I)
RESET = re.compile(r"(?:resets?|try again)\s+(?:at\s+|in\s+|on\s+)?([^\n\"}\\]+?)\s*(?:\.\s|\.$|[\"}]|\\n|$)", re.I)
tmp = pathlib.Path(tempfile.mkdtemp(prefix="routing-"))
atexit.register(shutil.rmtree, tmp, True)
(tmp / "router-instructions.md").write_text(SYSTEM + "\n")
CODEX_OFF = ["shell_tool", "unified_exec", "apps", "plugins", "browser_use", "computer_use",
             "image_generation", "view_image", "skill_search", "tool_suggest", "sleep_tool",
             "goals", "in_app_browser", "incremental_tools"]

def call(n, ids):
    """One router call. Returns (reply text, raw text for limit checks, flags, usage)."""
    prompt = "Requests:\n" + "\n".join(f"{i}: {rows[i][0]}" for i in ids) + "\n"
    if router == "claude":
        flags = ["--model", model, "--max-turns", "1", "--output-format", "json",
                 "--tools", "", "--strict-mcp-config", "--disable-slash-commands",
                 "--setting-sources", "project", "--no-session-persistence"]
        if tier.get("effort"): flags += ["--effort", tier["effort"]]
        cmd = ["claude", "-p", prompt, "--system-prompt", SYSTEM] + flags
        try:
            p = subprocess.run(cmd, cwd=tmp, stdin=subprocess.DEVNULL, capture_output=True,
                               text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return "", f"timeout after {timeout}s", flags, {}
        reply, usage = p.stdout, {}
        try:
            d = json.loads(p.stdout); reply = str(d.get("result") or ""); usage = d.get("usage") or {}
        except ValueError: pass
        return reply, p.stdout + "\n" + p.stderr + f"\nexit {p.returncode}", flags, usage
    last = tmp / f"last-{n}.txt"
    flags = ["--ephemeral", "--skip-git-repo-check", "--ignore-user-config", "--ignore-rules",
             "-s", "read-only", "-C", str(tmp), "-m", model, "--json",
             "-c", f"model_instructions_file={json.dumps(str(tmp / 'router-instructions.md'))}",
             "-c", 'web_search="disabled"']
    if tier.get("model_reasoning_effort"):
        flags += ["-c", f'model_reasoning_effort="{tier["model_reasoning_effort"]}"']
    for feat in CODEX_OFF: flags += ["--disable", feat]
    cmd = ["codex", "exec"] + flags + ["-o", str(last), "-"]
    try:
        p = subprocess.run(cmd, cwd=tmp, input=prompt, capture_output=True, text=True,
                           timeout=timeout)
    except subprocess.TimeoutExpired:
        return "", f"timeout after {timeout}s", flags, {}
    usage = {}
    for line in p.stdout.splitlines():
        try:
            e = json.loads(line)
            if e.get("type") == "turn.completed": usage = e.get("usage") or {}
        except ValueError: pass
    reply = last.read_text() if last.exists() else ""
    return reply, p.stdout + "\n" + p.stderr + f"\nexit {p.returncode}", flags, usage

def parse(reply, ids):
    """Strict JSON lines. Returns ({id: [agents]}, [bad lines])."""
    answers, bad = {}, []
    for line in reply.splitlines():
        s = line.strip()
        if not s: continue
        try: o = json.loads(s)
        except ValueError: bad.append(s); continue
        if (not isinstance(o, dict) or set(o) != {"id", "agent"}
                or not isinstance(o["id"], str) or not isinstance(o["agent"], str)
                or o["id"] not in ids):
            bad.append(s); continue
        answers.setdefault(o["id"], []).append(o["agent"].strip())
    return answers, bad

def stop_for_limit(raw, remaining):
    reset = RESET.search(raw)
    reset = reset.group(1).strip().rstrip(".") if reset else "not given in the message"
    ck = {"version": 1, "fingerprint": fingerprint, "corpus": corpus,
          "written": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "harness_out": router,
          "model": model, "reset": reset, "limit_message": LIMIT.search(raw).group(0),
          "done": done, "remaining": sorted(remaining)}
    ckpt_path.parent.mkdir(parents=True, exist_ok=True)
    ckpt_path.write_text(json.dumps(ck, indent=2) + "\n")
    other = "codex" if router == "claude" else "claude"
    q = shlex.quote
    print(f"\nrouting-suite: USAGE LIMIT. {router} ({model}) is out; reset: {reset}.")
    print(f"  Rows done: {len(done)} of {len(rows)}; remaining: {len(remaining)}.")
    print(f"  Checkpoint: {ckpt_path}")
    if prior_out and prior_out[0] == other:
        print(f"  Note: {other} also reported a limit when this checkpoint was written "
              f"(reset: {prior_out[1]}).")
    print(f"  Resume the remaining rows in the other harness now:")
    print(f"    cd {q(root)} && ROUTER={other} .agents/routing-suite.sh --resume {q(str(ckpt_path))}")
    print(f"  Or in the same harness after the reset:")
    print(f"    cd {q(root)} && ROUTER={router} .agents/routing-suite.sh --resume {q(str(ckpt_path))}")
    print("  A resumed run mixes harnesses per row; green under every harness still needs one")
    print("  complete run per harness.")
    sys.exit(EXIT_LIMIT)

start, failed, notes = time.time(), False, []
print(f"routing-suite: {router}, model {model}, {len(todo)} rows to ask"
      f"{f' ({len(done)} already done in the checkpoint)' if done else ''}, "
      f"{len(batches)} call(s), seed {seed}")
for n, ids in enumerate(batches, 1):
    t0 = time.time()
    reply, raw, flags, usage = call(n, ids)
    answers, bad = parse(reply, set(ids))
    complete = not bad and all(len(answers.get(i, [])) == 1 for i in ids)
    if not complete and LIMIT.search(raw + "\n" + reply):
        stop_for_limit(raw + "\n" + reply, [i for b in batches[n - 1:] for i in b])
    print(f"  call {n}/{len(batches)}: {len(ids)} rows, {time.time() - t0:.0f}s, "
          f"usage {json.dumps(usage, separators=(',', ':'))}")
    print(f"    flags: {' '.join(shlex.quote(x) for x in flags)}")
    if not reply.strip():
        failed = True
        notes.append(f"ROUTER ERROR (call {n}): no reply; output tail: {raw.strip()[-400:]!r}")
    for s in bad:
        failed = True
        notes.append(f"UNPARSEABLE (call {n}): {s[:120]!r}")
    for i in ids:
        got = answers.get(i, [])
        done[i] = {"got": got[0] if len(got) == 1 else
                   ("<missing>" if not got else "<duplicate: " + ", ".join(got) + ">"),
                   "router": router, "model": model}

ok, lines = 0, []
for i, (req, want) in rows.items():
    r = done[i]
    passed = r["got"] == want
    ok += passed
    lines.append(f"{'PASS' if passed else 'MISROUTE'} | {i} | {req} | expected {want} | "
                 f"got {r['got']} | {r['router']} {r['model']}")
    if not passed: print(f"MISROUTE: {i} '{req}' went to '{r['got']}', expected '{want}'")
for s in notes: print(s)
if os.environ.get("RESULTS"):
    pathlib.Path(os.environ["RESULTS"]).write_text("\n".join(lines) + "\n")
if resume and ckpt_path.exists():
    ckpt_path.unlink(); print(f"routing-suite: checkpoint consumed and removed: {ckpt_path}")
routers = sorted({r["router"] for r in done.values()})
print(f"==> routing ({'+'.join(routers)}, {model if len(routers) == 1 else 'mixed'}) "
      f"{ok}/{len(rows)} in {time.time() - start:.0f}s")
sys.exit(EXIT_OK if ok == len(rows) and not failed else EXIT_FAIL)
PY
