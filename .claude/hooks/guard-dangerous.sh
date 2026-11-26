#!/usr/bin/env bash
# PreToolUse hook - the guard archetype. Harness-neutral: registered by
# .claude/settings.json and .codex/hooks.json for the shell tool (Bash) and for the
# aws-api MCP tool (mcp__aws-api__call_aws). Both harnesses send the command in
# tool_input.command (call_aws sends tool_input.cli_command) and treat exit 2 with stderr
# as "block, show the message to the model".
# A deterministic backstop under the permission rules: it blocks command shapes that must
# never run from an agent context, however the model was prompted, and shell reads of
# secret files (Codex has no per-file read deny rule).
# Owner: factory-engineer. Floor invariant: patterns may be added, never removed.
# Known false positive: patterns also match prose inside heredocs or messages; reword
# the prose or write it with the Write tool, never weaken the list.
# Shell parsing (Phase 11 repair round 1): every pass below parses the command twice, as
# written and with unquoted '#' comments removed (bash drops them), and blocks if either
# reading blocks.
# Fail closed (Phase 11 repair round 4, human decision): when in doubt, block.
# - Every heredoc body (and here-string word) is read as commands by every pass; there is
#   no data exception for a quoted end marker or a reader receiver.
# - A command that any pass cannot fully parse is blocked: an unbalanced quote, an end
#   marker or heredoc place the scanner cannot read with certainty, << inside ${...},
#   $[...] or $((...)), $'...' or $"..." quoting, an unmatched ), more heredocs than
#   HD_MAX, nesting deeper than the passes follow, a crashed pass or a pass with no
#   verdict. No pass falls back to a raw-text check that could allow.
# - Text that bash evaluates as code is code (see "Evaluation sites" in the structural
#   pass): test, [ and [[ with -v or -R, arithmetic operators in [[ ]], arithmetic
#   expansion and commands, subscripts, indirection, @P, eval or sh -c of an expansion, a
#   shell reading a pipe or process substitution, function export, trap, alias.
# - Size and time: a payload over GUARD_MAX_PAYLOAD characters or a command over
#   GUARD_MAX_CMD is blocked; the scan runs in a child under timeout, GUARD_DEADLINE
#   seconds, safely below the 10 s hook timeout in .claude/settings.json; a timeout, a
#   crash, a signal or any exit status other than 0 or 2 is a block. Observed on Claude
#   Code 2.1.332 (inert probe, docs/agent-runs/phase-11/repair-04-factory-engineer.md): a
#   hook that runs past its timeout is cancelled and the tool call proceeds, and exit 1 or
#   137 lets it proceed too, so the guard must never reach the harness timeout.
set -uo pipefail

GUARD_MAX_PAYLOAD=65536   # characters of hook input (JSON)
GUARD_MAX_CMD=16384       # characters of command text
GUARD_DEADLINE=6          # seconds for the whole scan; the hook timeout is 10
if [ "${1:-}" != "--inner" ]; then
  # The wrapper does no parsing of its own, so it always finishes: it runs the guard below
  # as a child under timeout and turns every outcome but a clean allow into a block.
  fail() {
    echo "Blocked by guard-dangerous hook: $1. The guard fails closed: a command it cannot" \
         "fully check is blocked. Shorten or simplify the command; write report text with" \
         "the Write tool." >&2
    exit 2
  }
  trap 'fail "the guard was interrupted by a signal"' TERM INT HUP
  payload=$(cat) || fail "the guard could not read its input"
  [ "${#payload}" -le "$GUARD_MAX_PAYLOAD" ] \
    || fail "the hook input is longer than $GUARD_MAX_PAYLOAD characters"
  # A test may lower the deadline (never raise it) to prove that a timeout blocks.
  limit=$GUARD_DEADLINE
  [[ "${SCHOLARCHAIN_GUARD_DEADLINE:-}" =~ ^0?\.[0-9]+$|^[1-5]$ ]] && limit=$SCHOLARCHAIN_GUARD_DEADLINE
  out=$(printf '%s' "$payload" | timeout -k 1 "$limit" bash "${BASH_SOURCE[0]}" --inner 2>&1 >/dev/null)
  rc=$?
  case $rc in
    0) exit 0 ;;
    2) printf '%s\n' "$out" >&2; exit 2 ;;
    124|137) fail "the scan did not finish within $limit seconds" ;;
    *) fail "the scan ended with status $rc" ;;
  esac
fi

payload=$(cat)
# The command text, behind an OK line: no OK line means the input was not the JSON the
# harness sends, and that is a block, not an allow (round 4).
cmd=$(printf '%s' "$payload" | python3 -c '
import json, sys
d = json.load(sys.stdin)
ti = d.get("tool_input", {}) or {}
c = ti.get("command") or ti.get("cmd") or ti.get("cli_command") or ""
sys.stdout.write("OK\n" + (" ".join(map(str, c)) if isinstance(c, list) else str(c)))
' 2>/dev/null)
if [[ "$cmd" != OK && "$cmd" != OK$'\n'* ]]; then
  echo "Blocked by guard-dangerous hook: the hook input could not be read as JSON." >&2
  exit 2
fi
cmd=${cmd#OK}
cmd=${cmd#$'\n'}
[ -z "$cmd" ] && exit 0
if [ "${#cmd}" -gt "$GUARD_MAX_CMD" ]; then
  echo "Blocked by guard-dangerous hook: a command longer than $GUARD_MAX_CMD characters" \
       "(the guard scans at most that much; split the command, or write long text with" \
       "the Write tool)." >&2
  exit 2
fi
# Caller identity, the same rule as guard-ownership.sh: the payload field agent_type, else
# SCHOLARCHAIN_ROLE, else the orchestrator (docs/agents.md section 2). Used only to apply
# the tighter incident-responder scale bound; every other check applies to every caller.
caller=$(printf '%s' "$payload" | python3 -c '
import json, sys
print(json.load(sys.stdin).get("agent_type") or "")
' 2>/dev/null || true)
caller=${caller:-${SCHOLARCHAIN_ROLE:-orchestrator}}
# A normalized copy closes spelling bypasses: backslash-newline joined, quotes removed,
# runs of spaces and tabs collapsed. Every check below matches the raw command OR this
# copy, so normalization only ever adds matches.
norm=$(printf '%s' "$cmd" | python3 -c '
import re, sys
c = re.sub(r"\\\r?\n", " ", sys.stdin.read())
c = c.replace(chr(34), "").replace(chr(39), "")
sys.stdout.write(re.sub(r"[ \t]+", " ", c))
' 2>/dev/null || printf '%s' "$cmd")

block() {
  echo "Blocked by guard-dangerous hook: $1." \
       "Agents never run this. If it is really needed, a human runs it manually;" \
       "the approved paths are in docs/agents.md section 6." >&2
  exit 2
}

# Shared shell helpers, prepended to the Python passes that parse the command.
# uncomment: drop unquoted '#' comments the way bash does (a '#' that starts a word).
# subst: replace $(...), backticks, ${...} and $NAME by SUB and return the inner commands.
# wrappers: strip command wrappers, VAR=value prefixes and wrapper options (with their
# values: timeout -s KILL, env -u NAME, nice -n 5, stdbuf -o L, xargs -I {}, sudo -u x).
IFS= read -r -d '' PYLIB <<'PYLIB'
import os, re, shlex
SUB = "@@SUB@@"
def uncomment(s):
    out, i, q, n = [], 0, "", len(s)
    while i < n:
        ch = s[i]
        if q == "\x27":
            out.append(ch); i += 1
            if ch == "\x27": q = ""
            continue
        if ch == "\\" and i + 1 < n:
            out.append(s[i:i + 2]); i += 2; continue
        if q == "\"":
            out.append(ch); i += 1
            if ch == "\"": q = ""
            continue
        if ch in "\x27\"":
            q = ch; out.append(ch); i += 1; continue
        if ch == "#" and (i == 0 or s[i - 1] in " \t\n;&|()<>"):
            j = s.find("\n", i)
            i = n if j < 0 else j
            continue
        out.append(ch); i += 1
    return "".join(out)
def _brace_end(s, j):
    # The index of the } that closes a ${ whose content starts at j (nested ${ } counted;
    # quotes and escapes skipped), or len(s). Round 3, S-1: ${Y:-$(cmd)} runs cmd.
    depth, q = 1, ""
    while j < len(s):
        ch = s[j]
        if q:
            if ch == "\\" and q == "\"": j += 2; continue
            if ch == q: q = ""
        elif ch == "\\": j += 2; continue
        elif ch in "\x27\"": q = ch
        elif s.startswith("${", j): depth += 1; j += 2; continue
        elif ch == "}":
            depth -= 1
            if not depth: return j
        j += 1
    return len(s)
def subst(s):
    out, inner, i, q = [], [], 0, ""
    while i < len(s):
        ch = s[i]
        if q == "\x27":
            out.append(ch); i += 1
            if ch == "\x27": q = ""
            continue
        if ch == "\\" and i + 1 < len(s):
            out.append(s[i:i + 2]); i += 2; continue
        if ch == "\x27" and not q:
            q = "\x27"; out.append(ch); i += 1; continue
        if ch == "\"":
            q = "" if q else "\""; out.append(ch); i += 1; continue
        if ch == "$" and s[i + 1:i + 2] == "(":
            depth, j = 1, i + 2
            while j < len(s) and depth:
                depth += {"(": 1, ")": -1}.get(s[j], 0); j += 1
            inner.append(s[i + 2:j - 1]); out.append(SUB); i = j; continue
        if ch == "`":
            j = s.find("`", i + 1); j = len(s) if j < 0 else j
            inner.append(s[i + 1:j]); out.append(SUB); i = j + 1; continue
        if ch == "$" and s[i + 1:i + 2] == "{":
            j = _brace_end(s, i + 2); inner += subst(s[i + 2:j])[1]
            out.append(SUB); i = j + 1; continue
        m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*|[0-9@*#?!$-])", s[i:])
        if m:
            out.append(SUB); i += len(m.group()); continue
        out.append(ch); i += 1
    return "".join(out), inner
# Shells (round 4 adds the rarer ones and script -c, review S-2); every pass recurses into
# their -c strings.
SHELLS = {"sh", "bash", "zsh", "dash", "ksh", "rbash", "ash", "mksh", "yash", "fish", "csh",
          "tcsh", "posh", "script"}
KEYWORDS = {"then", "do", "else", "elif", "if", "while", "until", "!", "{", "}", "fi",
            "done", "esac"}
WRAPPERS = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout",
            "stdbuf", "xargs", "sudo", "doas", "busybox", "ionice", "chrt", "setsid",
            "unbuffer", "flock"}
WRAPVAL = {"timeout": {"-s", "--signal", "-k", "--kill-after"},
           "env": {"-u", "--unset", "-C", "--chdir"},
           "nice": {"-n", "--adjustment"}, "ionice": {"-c", "-n", "-p"},
           "stdbuf": {"-i", "-o", "-e", "--input", "--output", "--error"},
           "xargs": {"-I", "-L", "-n", "-P", "-s", "-d", "-E", "-a", "--arg-file",
                     "--delimiter", "--max-args", "--max-procs", "--max-lines",
                     "--max-chars", "--replace", "--eof"},
           "sudo": {"-u", "-g", "-C", "-D", "-h", "-p", "-U", "-r", "-t"},
           "doas": {"-u", "-C"}, "exec": {"-a"}, "time": {"-f", "-o", "--format", "--output"},
           "flock": {"-w", "-E", "--timeout", "--conflict-exit-code"}}
def wrappers(words, keywords=KEYWORDS):
    # Returns (command words, saw xargs, VAR=value words, env -S string or None, keywords).
    xa, cur, assigns, split, seen = False, None, [], None, []
    while words:
        w = words[0]; b = os.path.basename(w)
        if w in keywords:
            seen.append(w); cur = None; words = words[1:]; continue
        if w == "function" and cur is None:
            # function NAME { body; } (round 4): the body words are commands.
            seen.append(w); words = words[2:]; continue
        if b in WRAPPERS:
            xa = xa or b == "xargs"; cur = b; words = words[1:]; continue
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", w):
            assigns.append(w); words = words[1:]; continue
        if cur == "env" and re.fullmatch(r"-S.*|--split-string(=.*)?", w):
            v = w[2:] if w.startswith("-S") else w.partition("=")[2]
            if not v and len(words) > 1: v, words = words[1], words[1:]
            split = " ".join([v] + words[1:]); words = []; break
        if re.fullmatch(r"-\S*", w):
            words = words[2:] if (cur and w in WRAPVAL.get(cur, ()) and len(words) > 1) else words[1:]
            continue
        if re.fullmatch(r"\d+(\.\d+)?[smhd]?", w):
            words = words[1:]; continue
        break
    return words, xa, assigns, split, seen
def variants(s):
    # The command as written and with unquoted comments removed (bash drops them). The
    # last reading is the one bash sees: only its parse failure fails closed.
    u = uncomment(s)
    return [s] if u == s else [s, u]
# Heredocs and here-strings (round 2, R-1; round 4, human: fail closed). Every heredoc body
# and every here-string word is commands for every pass: feeds_shell is always true and
# there is no data exception (the round-3 quoted-marker rule is gone). A here-string route
# keeps its receiver (route_names) for the marker pass; a heredoc body has no route (None).
FEED_SHELLS = SHELLS | {"source", ".", "eval"}
def route_names(route):
    # (command name, xargs flag, args) of the receiver and each later pipeline stage.
    out = []
    for w in ([route[0]] + route[1]) if route else []:
        cmd, xa, _, split, _ = wrappers(list(w))
        if split is not None: cmd = ["sh", "-c", split]
        out.append((os.path.basename(cmd[0]) if cmd else "", xa, cmd[1:] if cmd else []))
    return out
def herestrings(segs):
    # Here-strings (cmd <<< word): segs is [(words, redirections, operator after the
    # segment, here-string words)]; the route is the receiver and later pipeline stages.
    out = []
    for k, (w, reds, sep, hs) in enumerate(segs):
        later, j = [], k
        while j + 1 < len(segs) and segs[j][2] in ("|", "|&"):
            later.append(segs[j + 1][0]); j += 1
        out += [((w, later, False, list(reds)), h) for h in hs]
    return out
def feeds_shell(route):
    # Round 4 (human): every heredoc body and here-string word is read as commands.
    return True
# Heredoc scanner (round 3, S-1; round 4: doubt blocks). _hd_scan follows bash's reading of
# quotes, escapes, comments, $( ` <( >( and ( subshells, ${...}, $[...], here-strings and
# heredoc end markers. Whatever it cannot read with certainty is doubt, and heredoc_split
# then raises ValueError, which every pass turns into a block: an odd, partly quoted or
# backslash end marker; << inside ${...} or $[...] (bash reads it as word text there, T-1);
# a heredoc inside $( ` <( >( ( or an arithmetic ((...)) (so also $((x<<2))); a quote or
# substitution open at the line end; an unmatched ) (a case pattern too); a missing end
# marker; a backslash line ending in an unquoted body; $'...' or $"..." outside double
# quotes (the passes do not decode them); a quote inside $[...] or inside a double-quoted
# ${...}; a newline inside ${...} or $[...]; more than HD_MAX heredocs (T-2).
HD_MAX = 16
_HD_PLAIN = re.compile(r"\x27([A-Za-z0-9_.-]+)\x27|\"([A-Za-z0-9_.-]+)\"")
_HD_BARE = re.compile(r"[A-Za-z0-9_]+")
def _hd_word(text, j):
    n = len(text)
    while j < n and text[j] in " \t": j += 1
    k, q = j, ""
    while k < n:
        ch = text[k]
        if q:
            if ch == "\n": return k, None
            if ch == q: q = ""
            k += 1; continue
        if ch in "\x27\"": q = ch; k += 1; continue
        if ch == "\\": k += 2; continue
        if ch in " \t\n;&|()<>": break
        k += 1
    k = min(k, n)
    return k, (None if q else text[j:k])
def _hd_scan(text):
    # Returns (ops, doubt): ops are dicts for heredocs read with certainty, in order; doubt
    # is None when the whole text was read with certainty (linear in the text length).
    n, i, stack, pend, ops, ls = len(text), 0, [], [], [], 0
    while i < n:
        ch = text[i]; top = stack[-1] if stack else ""
        if top == "\x27":
            if ch == "\x27": stack.pop()
            i += 1; continue
        if ch == "\\": i += 2; continue
        if top == "\"":
            if ch == "\"": stack.pop(); i += 1
            elif ch == "`": stack.append("`"); i += 1
            elif text.startswith("$(", i): stack.append("("); i += 2
            elif text.startswith("${", i): stack.append("{"); i += 2
            elif text.startswith("$[", i): stack.append("["); i += 2
            else: i += 1
            continue
        if top in ("{", "["):
            if ch == "\n" or text.startswith("<<", i): return ops, ls
            if text.startswith(("$\x27", "$\""), i): return ops, ls
            if ch == ("}" if top == "{" else "]"): stack.pop(); i += 1; continue
            if ch in "\x27\"":
                if top == "[" or "\"" in stack: return ops, ls
                stack.append(ch); i += 1; continue
            if text.startswith("$(", i): stack.append("("); i += 2; continue
            if text.startswith("${", i): stack.append("{"); i += 2; continue
            if text.startswith("$[", i): stack.append("["); i += 2; continue
            if ch == "`": stack.append("`"); i += 1; continue
            if top == "[" and ch == "[": stack.append("["); i += 1; continue
            i += 1; continue
        if ch == "\n":
            if pend:
                if stack: return ops, ls
                pos = i + 1
                for op in pend:
                    body, found = [], False
                    while pos < n:
                        e = text.find("\n", pos); e = n if e < 0 else e
                        line = text[pos:e]; pos = e + 1
                        if (line.lstrip("\t") if op["dash"] else line) == op["delim"]:
                            found = True; break
                        if not op["quoted"] and re.search(r"(?<!\\)(?:\\\\)*\\$", line): return ops, ls
                        body.append(line)
                    if not found: return ops, ls
                    op.update(line_end=i, body="\n".join(body), next=min(pos, n))
                ops += pend; pend = []
                i = ls = min(pos, n); continue
            i += 1; ls = i; continue
        if ch == "#" and (i == 0 or text[i - 1] in " \t\n;&|()<>"):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith(("$\x27", "$\""), i): return ops, ls
        if ch in "\x27\"": stack.append(ch); i += 1; continue
        if text.startswith("${", i): stack.append("{"); i += 2; continue
        if text.startswith("$[", i): stack.append("["); i += 2; continue
        if text.startswith("<<<", i): i += 3; continue
        if text.startswith("<<", i):
            if stack or len(ops) + len(pend) >= HD_MAX: return ops, ls
            j = i + 2; dash = text[j:j + 1] == "-"
            if dash: j += 1
            k, w = _hd_word(text, j)
            m = _HD_PLAIN.fullmatch(w or "")
            if m: delim, quoted = m.group(1) or m.group(2), True
            elif w and _HD_BARE.fullmatch(w): delim, quoted = w, False
            else: return ops, ls
            pend.append({"start": i, "end": k, "delim": delim, "quoted": quoted, "dash": dash})
            i = k; continue
        if text.startswith(("$(", "<(", ">("), i): stack.append("("); i += 2; continue
        if ch == "(": stack.append("("); i += 1; continue
        if ch == ")":
            if top != "(": return ops, ls
            stack.pop(); i += 1; continue
        if ch == "`":
            if top == "`": stack.pop()
            else: stack.append("`")
            i += 1; continue
        i += 1
    if pend or stack: return ops, ls
    return ops, None
def heredoc_split(text, keep=None):
    # The text with every heredoc body removed (keep is None) or inline after its line as
    # commands (keep given), and [(None, body)] for every heredoc in order. Raises
    # ValueError when the scanner has a doubt (round 4: the command is then blocked).
    ops, doubt = _hd_scan(text)
    if doubt is not None:
        raise ValueError("a heredoc, quote or substitution the guard cannot parse with certainty")
    out, cur, bodies, g = [], 0, [], 0
    while g < len(ops):
        le = ops[g]["line_end"]; grp = [o for o in ops[g:] if o["line_end"] == le]; g += len(grp)
        out.append(text[cur:le]); out.append("\n")
        for o in grp:
            if keep is not None and o["body"]: out.append(o["body"] + "\n")
            bodies.append((None, o["body"]))
        cur = grp[-1]["next"]
    out.append(text[cur:])
    return "".join(out), bodies
# Evaluation sites (round 4, human: test, [ and [[ with -v or other evaluating options are
# code). Bash evaluates the text in these places as arithmetic or as a variable name, and
# a subscript there (a[$(cmd)]) runs commands, also when the text comes from a variable,
# a file or a heredoc (T-3), so they are allowed only with literal text:
# - $((...)), $[...] and an arithmetic command ((...)) (also for ((...))): digits and
#   operators only;
# - ${...}: a subscript a literal integer, @ or *; a substring offset or length a literal
#   integer; ${!name} indirection only as ${!prefix*}, ${!prefix@} or ${!name[@]}; never
#   the @P operator (prompt expansion runs $(...));
# - [[ ... ]]: -v and -R take a plain name; -eq, -ne, -lt, -le, -gt and -ge take literal
#   integers or $?, $#, $$, $!, ${#name};
# - the command-word rules (test and [ with -v or -R, let, declare and friends, read,
#   unset, printf -v, mapfile, getopts, wait -p, array element assignments, eval and sh -c
#   of an expansion, a shell reading a pipe, trap, alias, exported functions, BASH_ENV and
#   the prompt variables) are in the structural pass (evaluating_words).
ARITH_LIT = re.compile(r"[0-9\s+\-*/%<>=!&|^~?:,()]*")
INT_OPS = {"-eq", "-ne", "-lt", "-le", "-gt", "-ge"}
INT_OK = re.compile(r"-?[0-9]+|\$[?#$!]|\$\{[?#$!]\}|\$\{#[A-Za-z_][A-Za-z0-9_]*(?:\[[@*]\])?\}")
NAME_OK = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
def _closing(s, j, o, c):
    # The index of the c that closes an o opened just before j (quotes and escapes
    # skipped, nested o counted), or -1.
    depth, q = 1, ""
    while j < len(s):
        ch = s[j]
        if q:
            if ch == "\\" and q == "\"": j += 2; continue
            if ch == q: q = ""
        elif ch == "\\": j += 2; continue
        elif ch in "\x27\"": q = ch
        elif s.startswith(o, j): depth += 1; j += len(o); continue
        elif s.startswith(c, j):
            depth -= 1
            if not depth: return j
        j += 1
    return -1
def _param_ok(body):
    # body is the text inside ${...}.
    if body.startswith("!"):
        return re.fullmatch(r"![A-Za-z_][A-Za-z0-9_]*(?:[*@]|\[[@*]\])", body) is not None
    m = re.match(r"#?(?:[A-Za-z_][A-Za-z0-9_]*|[0-9]+)|#?[@*#?$!-]", body)
    if not m: return False
    rest = body[m.end():]
    if rest.startswith("["):
        k = rest.find("]")
        if k < 0 or not re.fullmatch(r"\s*(?:-?[0-9]+|[@*])\s*", rest[1:k]): return False
        rest = rest[k + 1:]
    if rest.startswith("@P"): return False
    if rest.startswith(":") and rest[1:2] not in ("-", "=", "?", "+"):
        return re.match(r":\s*-?[0-9]+\s*(?::\s*-?[0-9]+\s*)?(?:}|$)", rest + "}") is not None
    return True
def _dbrackets(inner):
    try: toks = shlex.split(inner)
    except ValueError: return "a [[ ]] test the guard cannot parse"
    for k, t in enumerate(toks):
        nxt = toks[k + 1] if k + 1 < len(toks) else ""
        if t in ("-v", "-R") and not NAME_OK.fullmatch(nxt):
            return "[[ " + t + " " + nxt + " ]] (bash evaluates the operand as a variable name and its subscript as code)"
        if t in INT_OPS:
            prv = toks[k - 1] if k else ""
            if not (INT_OK.fullmatch(prv) and INT_OK.fullmatch(nxt)):
                return "[[ " + prv + " " + t + " " + nxt + " ]] (bash evaluates both operands as arithmetic, and a subscript in them runs code; use literal numbers or $?)"
    return None
def evaluating(s):
    # The first evaluation site in s that holds non-literal text, as a reason, or None.
    i, q, n = 0, "", len(s)
    while i < n:
        ch = s[i]
        if q == "\x27":
            if ch == "\x27": q = ""
            i += 1; continue
        if ch == "\\": i += 2; continue
        if ch == "\"": q = "" if q else "\""; i += 1; continue
        if ch == "\x27" and not q: q = "\x27"; i += 1; continue
        if s.startswith("$((", i):
            k = _closing(s, i + 3, "(", ")")
            if k < 0 or not ARITH_LIT.fullmatch(s[i + 3:k]):
                return "arithmetic expansion $((...)) with text the guard cannot verify (bash evaluates names and subscripts in it as code)"
            i += 3; continue
        if s.startswith("$[", i):
            k = _closing(s, i + 2, "[", "]")
            if k < 0 or not ARITH_LIT.fullmatch(s[i + 2:k]):
                return "arithmetic expansion $[...] with text the guard cannot verify"
            i += 2; continue
        if s.startswith("${", i):
            k = _brace_end(s, i + 2)
            if not _param_ok(s[i + 2:k]):
                return "the parameter expansion ${" + s[i + 2:k][:40] + "} (a subscript, offset, indirection or @P that bash evaluates as code)"
            i += 2; continue
        if not q and s.startswith("((", i) and (i == 0 or s[i - 1] in " \t\n;&|({"):
            k = _closing(s, i + 2, "(", ")")
            if k < 0 or not ARITH_LIT.fullmatch(s[i + 2:k]):
                return "an arithmetic command ((...)) with text the guard cannot verify (bash evaluates names and subscripts in it as code)"
            i += 2; continue
        if not q and s.startswith("[[", i) and (i == 0 or s[i - 1] in " \t\n;&|(!{") and s[i + 2:i + 3] in (" ", "\t", "\n"):
            j, qq = i + 2, ""
            while j < n:
                c2 = s[j]
                if qq:
                    if c2 == "\\" and qq == "\"": j += 2; continue
                    if c2 == qq: qq = ""
                elif c2 == "\\": j += 2; continue
                elif c2 in "\x27\"": qq = c2
                elif s.startswith("]]", j) and s[j - 1] in " \t": break
                j += 1
            r = _dbrackets(s[i + 2:j])
            if r: return r
            i += 2; continue
        i += 1
    return None
PYLIB

# Structural pass: identify shell operators before shlex decodes words. The guard splits
# the command into segments on unquoted ; & | ( ) ` and newlines, drops redirections,
# strips wrappers (with their option values) and VAR=value prefixes, replaces $(...),
# backticks and $VAR by a placeholder and checks the inner commands too, recurses into
# sh -c / bash -c strings, eval, env -S and heredoc bodies fed to a shell, and checks argv
# tokens:
# - terraform or tofu with an apply or destroy token, fed by xargs, or with a variable
#   subcommand; a variable or $(...) command word followed by apply, destroy or push;
# - git push with a force flag (-f in any short group, --force*, --mirror and their
#   abbreviations, +refspec), a remote.*.push or remote.*.mirror value given with -c or
#   --config-env, GIT_CONFIG* variables, xargs, or a variable argument; git config that
#   sets remote.*.push or remote.*.mirror;
# - git commit with --no-verify or n in a short group (values of -m, -F and similar are
#   skipped);
# - env with no command, printenv with no name or a secret-looking name (PAT, TOKEN,
#   SECRET, KEY, PASSWORD, PASSWD, PASSPHRASE, CREDENTIAL), export, declare, typeset or
#   readonly with no name, and a bare set, because they print credential values;
# - sbt, sbtn and java -jar sbt-launch.jar running arbitrary code (Phase 11 B-1): the sbt
#   commands eval, console*, set, session, alias, addCommandAlias, apply, '<', load*,
#   onFailure, iflast, append, early, shell, boot, reboot, client, bgRun*, reStart,
#   scripted, '!' history, '~' continuous execution, reload or projects with arguments,
#   runMain (and fgRunMain) of a class outside the scholarchain package, markTestRun;
#   after an input task (testOnly, run, runMain) its later words are test patterns or
#   program arguments, but a word shaped like a scoped key (core/console) is still
#   checked (round 2, R-6); sbt with no command (its shell reads commands from stdin);
#   sbt fed by xargs; JVM agent options in JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS,
#   _JAVA_OPTIONS, SBT_OPTS, JAVA_OPTS or JVM_OPTS.
#   Launcher options are an allow-list (round 2, R-2): -batch, -no-colors, -v, -d,
#   -timings, -traces, -no-server, --warn, --error, --info, -mem N, -J-Xmx/-Xms/-Xss and
#   -Dsbt.server.forcestart, .server.autostart, .color, .supershell, .log.noformat or .ci
#   set to true, false, always, never or auto; the help and version options. Every other
#   argument that starts with - or @ is refused, as a separate argument or inside a
#   command string, with spaces trimmed (--addPluginSbtFile, -Duser.home, -J-D...). The
#   environment for sbt: SBT_OPTS, JAVA_OPTS, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS,
#   _JAVA_OPTIONS and JVM_OPTS may hold only -Xmx, -Xms, -Xss (also as -J-X...) and the
#   allowed -Dsbt properties (the repository uses SBT_OPTS="-Xmx2560m -Xss4m"); HOME,
#   PATH, CLASSPATH, JAVACMD, BASH_ENV, ENV, IFS, PS4 and every XDG_, SBT_, COURSIER_,
#   IVY_, JAVA_, JDK_, JVM_, SCALA_, LD_ and DYLD_ variable are refused, whether set as a
#   prefix (also through env), as NAME=value or with export anywhere in a command that
#   runs sbt. java -jar sbt-launch.jar takes only heap options and those properties.
#   run, Test/run and runMain of a scholarchain.* class stay allowed: local pipeline runs
#   use them, and they run only the build's own code, which is reviewed in the diff like
#   every test. Residual: tests and the build's own mains are arbitrary code by design.
# - polling and wait loops (Phase 11 B-7): sleep, pgrep, pidof or ps inside a while,
#   until, for or select loop, and watch. A bare pgrep stays allowed.
# Heredocs and here-strings (round 4): every body and here-string word is checked as
# commands, inline after its line.
# Evaluation sites (round 4, human): evaluating() in PYLIB on the text of every level, and
# evaluating_words below on every command: test and [ with -v or -R take a plain name;
# let, declare/typeset/local/readonly/export with -i (and declare, typeset, local with -n),
# export -f and declare -fx, mapfile -C, trap with an action, alias NAME=, bind -x,
# complete or compgen -C or -F, enable -f, fc and coproc are blocked; read, unset, printf
# -v, mapfile, wait -p, getopts and the declare family take plain names only; an array
# element assignment takes a literal integer subscript; BASH_ENV, ENV, PS0 to PS4,
# PROMPT_COMMAND, SHELLOPTS, BASHOPTS and BASH_FUNC_* are never set; eval of an expansion,
# sh -c of an expansion, a shell or source reading a pipe, process substitution,
# /dev/stdin or a variable script are blocked (closes review S-2 and S-3); find -exec
# commands are analyzed like any command.
# It also prints each segment in canonical form (quotes and escapes resolved; for aws,
# global options removed so service and operation are adjacent), and the pattern list
# below is matched against those too. A command that a reading cannot parse is blocked
# (round 4; see the verdict check below).
structural=$(printf '%s' "$cmd" | python3 -c "$PYLIB"'
import sys
raw = sys.stdin.read()
SOURCE = {"source", ".", "eval"}
AWS_NOVAL = {"--debug", "--no-verify-ssl", "--no-paginate", "--no-sign-request",
             "--no-cli-pager", "--cli-auto-prompt", "--no-cli-auto-prompt"}
PUSH_VAL = {"--push-option", "--repo", "--receive-pack", "--exec"}
COMMIT_VAL = {"--message", "--file", "--reuse-message", "--reedit-message", "--template",
              "--author", "--date", "--fixup", "--squash", "--cleanup", "--trailer",
              "--pathspec-from-file"}
GIT_GLOBAL_VAL = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--config-env",
                  "--super-prefix"}
# A variable name whose value is a credential: an underscore-separated part that is PAT,
# KEY (or ends in KEY), PASSWORD, PASSWD, PASSPHRASE, CREDENTIAL(S), or contains TOKEN or
# SECRET. PATH, HOME, PWD and JAVA_HOME do not match.
SECRET_VAR = re.compile(r"(?:^|_)(?:[A-Z0-9]*(?:TOKEN|SECRET)[A-Z0-9]*|PATS?|[A-Z0-9]*KEYS?|PASSWORDS?|PASSWD|PASSPHRASE|CREDENTIALS?)(?:_|$)")
JVM_ENV = re.compile(r"(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|SBT_OPTS|JAVA_OPTS|JVM_OPTS)=(.*)", re.S)
JVM_BAD = re.compile(r"javaagent|agentpath|agentlib|OnOutOfMemoryError|OnError|-jar\b|sbt\.global\.base|sbt\.boot\.(directory|properties)|sbt\.ivy\.home|sbt\.override\.build\.repos|sbt\.repository\.config|sbt\.global\.(plugins|settings)", re.I)
SBT_DENY = {"eval", "console", "consolequick", "consoleproject", "set", "session", "alias",
            "addcommandalias", "apply", "<", "load", "loadp", "loadfailed", "onfailure",
            "iflast", "append", "early", "shell", "oldshell", "boot", "reboot", "client",
            "bgrun", "bgrunmain", "restart", "ammonite", "amm", "scripted",
            "addpluginsbtfile", "marktestrun"}
# Input tasks whose later words are test patterns or program arguments (R-6: test is a
# plain task, so the words after it are still checked).
SBT_ARGS = {"testonly", "testquick", "run", "fgrun", "runmain", "fgrunmain"}
SBT_INFO = {"-h", "-help", "--help", "-version", "--version", "-V", "--numeric-version",
            "--script-version", "-script-version"}
# Launcher options (R-2): an allow-list. Every other argument that starts with - or @ is
# refused, in any position and with surrounding spaces removed. -mem takes a number.
SBT_PROPS = r"-Dsbt\.(?:server\.forcestart|server\.autostart|color|supershell|log\.noformat|ci)=(?:true|false|always|never|auto)"
HEAP = r"-X(?:mx|ms|ss)[0-9]+[kKmMgG]?"
SBT_OPT_OK = re.compile(r"-(?:batch|no-colors|v|verbose|d|debug|timings|traces|no-server)|--(?:batch|no-colors|verbose|debug|warn|error|info|timings|traces|no-server)|" + SBT_PROPS + r"|-J" + HEAP)
# Environment for sbt (R-2): JVM option variables may hold only heap sizes and the allowed
# sbt properties; variables that move where sbt, bash or the JVM load code from are refused.
SBT_ENV_JVM = {"SBT_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
               "JVM_OPTS"}
JVM_OPT_OK = re.compile(HEAP + r"|-J" + HEAP + r"|" + SBT_PROPS)
SBT_ENV_BAD = re.compile(r"HOME|PATH|CLASSPATH|JAVACMD|BASH_ENV|ENV|SHELLOPTS|BASHOPTS|PS4|PROMPT_COMMAND|IFS|(?:LD|DYLD|XDG|SBT|SBTN|COURSIER|IVY|JAVA|JDK|_JAVA|JVM|SCALA|SCALA_CLI|MAVEN|GRADLE)_\w*|JAVA\w*|COURSIER\w*")
blocks, canon = [], []

# Consume quotes and escapes before recognizing a run of unquoted operators.
syntax = re.compile(
    r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 | (?P<op>[;&|()<>`\n]+)""",
    re.S | re.X,
)

DECL = {"declare", "typeset", "local", "readonly", "export"}
# Variables whose value bash runs or expands as code (round 4): BASH_ENV is expanded and
# sourced by every non-interactive bash, the prompts are expanded (PS4 under set -x),
# SHELLOPTS and BASHOPTS switch such options on, BASH_FUNC_* are exported functions.
DANGER_ENV = re.compile(r"BASH_ENV|PS[0-4]|PROMPT_COMMAND|SHELLOPTS|BASHOPTS|BASH_FUNC_.*")

def tokens(s):
    start = 0
    for match in syntax.finditer(s):
        if match.lastgroup == "op":
            for word in shlex.split(s[start:match.start()]):
                yield False, word
            yield True, match.group()
            start = match.end()
    for word in shlex.split(s[start:]):
        yield False, word

def segments(s):
    # [(argv, stdin marks, operator after it, here-string words)]. Stdin marks (round 4):
    # "|in" when the segment reads a pipe (also inside a ( subshell after the pipe), "<<"
    # for a heredoc, "<" for a literal input file, "<?" for an input redirection the guard
    # cannot see (a variable or a process substitution).
    segs, cur, hs, skip, pin, marks = [], [], [], None, False, []
    for operator, tok in tokens(s):
        if skip is not None:
            if not operator:
                if skip == "<<<": hs.append(tok)
                elif skip in ("<<", "<<-"): marks.append("<<")
                elif skip in ("<", "<>"): marks.append("<?" if SUB in tok else "<")
            elif skip in ("<", "<>"): marks.append("<?")
            skip = None
            if not operator: continue
        if operator and set(tok) <= set("<>&") and ("<" in tok or ">" in tok):
            skip = tok; continue
        if operator and set(tok) <= set(";&|()`\n"):
            segs.append((cur, (["|in"] if pin else []) + marks, tok, hs)); cur, hs, marks = [], [], []
            if re.search(r"(?<!\|)\|(?!\|)", tok) and not re.search(r"[;\n`]|&&|\|\|", tok): pin = True
            elif not set(tok) <= set("("): pin = False
            continue
        cur.append(tok)
    segs.append((cur, (["|in"] if pin else []) + marks, "", hs))
    return [x for x in segs if x[0] or x[3]]

def shell_args(args):
    # (has -c, the -c string or None, reads stdin, the script operand or None).
    c, s, cstr, op, i = False, False, None, None, 0
    while i < len(args):
        a = args[i]
        if a == "--":
            op = args[i + 1] if i + 1 < len(args) else None; break
        if a in ("-o", "+o", "-O", "+O", "--rcfile", "--init-file"): i += 2; continue
        if a[:1] in "-+" and len(a) > 1:
            if not a.startswith("--"):
                if "c" in a[1:] and not c:
                    c = True; cstr = args[i + 1] if i + 1 < len(args) else ""
                    i += 2; continue
                s = s or "s" in a[1:]
            i += 1; continue
        op = a; break
    return c, cstr, (not c and (op is None or s or op == "-")), op

def evaluating_words(name, args, words, assigns, marks):
    # Round 4 evaluation sites and code sources on one command (see the comment above).
    for a in list(assigns) + [w for w in words if "=" in w]:
        n = a.split("=", 1)[0].rstrip("+")
        if DANGER_ENV.fullmatch(n):
            return "setting " + n + " (bash runs or expands its value as code)"
    if any(w.startswith("BASH_FUNC_") for w in list(words) + list(assigns)):
        return "a BASH_FUNC_ word (an exported shell function, code the guard does not see)"
    if not words: return None
    m = re.match(r"(?:[A-Za-z_][A-Za-z0-9_]*)?\[(.*)\]\+?=", words[0], re.S)
    if m and not re.fullmatch(r"\s*-?[0-9]+\s*", m.group(1)):
        return "an array element assignment with the subscript [" + m.group(1)[:30] + "] (bash evaluates it as arithmetic, and a subscript runs code)"
    if name == "let": return "let (bash evaluates its arguments as arithmetic, and a subscript runs code)"
    if name in ("coproc", "fc"): return name + " (the guard does not follow the command it runs)"
    if name in ("test", "["):
        for j, a in enumerate(args):
            if a in ("-v", "-R"):
                nxt = args[j + 1] if j + 1 < len(args) else ""
                if not NAME_OK.fullmatch(nxt):
                    return name + " " + a + " " + nxt[:30] + " (bash evaluates the operand as a variable name and its subscript as code)"
    if name in DECL:
        opts = "".join(a[1:] for a in args if a[:1] in "-+" and not a.startswith("--"))
        if "i" in opts: return name + " -i (bash evaluates every value as arithmetic)"
        if name != "export" and "n" in opts: return name + " -n (a nameref target is evaluated as a name, with its subscript)"
        if (name == "export" and "f" in opts) or (name in ("declare", "typeset") and "f" in opts and "x" in opts):
            return "exporting a shell function (" + name + " -f), code the guard does not see"
        for a in args:
            if a[:1] in "-+": continue
            n = a.split("=", 1)[0].rstrip("+")
            if not NAME_OK.fullmatch(n):
                return name + " of the name " + n[:30] + " (a subscript or expansion that bash evaluates as code)"
    if name == "printf" and args[:1] == ["-v"] and not NAME_OK.fullmatch(args[1] if len(args) > 1 else ""):
        return "printf -v into a name that is not plain (bash evaluates its subscript as code)"
    if name in ("read", "mapfile", "readarray", "unset", "wait"):
        val = {"read": "dinNptu", "mapfile": "cdnOsu", "readarray": "cdnOsu", "wait": "",
               "unset": ""}[name]
        named = {"read": "a", "wait": "p"}.get(name, "")
        if name in ("mapfile", "readarray") and any(a[:1] == "-" and "C" in a for a in args):
            return name + " -C (a callback runs as code)"
        j, ops = 0, []
        while j < len(args):
            a = args[j]
            if a[:1] == "-" and len(a) > 1 and a != "--":
                if a[-1] in named:
                    nxt = args[j + 1] if j + 1 < len(args) else ""
                    if not NAME_OK.fullmatch(nxt):
                        return name + " " + a + " " + nxt[:30] + " (bash evaluates the name and its subscript as code)"
                    j += 2; continue
                j += 2 if a[-1] in val else 1; continue
            ops.append(a); j += 1
        if name in ("read", "mapfile", "readarray", "unset"):
            for a in ops:
                if a != "--" and not NAME_OK.fullmatch(a):
                    return name + " of the name " + a[:30] + " (bash evaluates the name and its subscript as code)"
    if name == "getopts" and len(args) > 1 and not NAME_OK.fullmatch(args[1]):
        return "getopts into the name " + args[1][:30]
    if name == "trap":
        t = [a for a in args if a != "--"]
        if len(t) >= 2 and t[0] not in ("-", "", "-l", "-p", "-P"):
            return "trap with an action (code the guard does not check)"
    if name == "alias" and any("=" in a for a in args): return "alias with a definition (code the guard does not check)"
    if name == "bind" and any(a.startswith("-") and "x" in a for a in args): return "bind -x (code the guard does not check)"
    if name in ("complete", "compgen") and any(a in ("-C", "-F") for a in args):
        return name + " -C or -F (code the guard does not check)"
    if name == "enable" and any(a.startswith("-") and "f" in a for a in args): return "enable -f (loads code)"
    if name == "eval" and any(SUB in a for a in args):
        return "eval of an expansion (code the guard cannot see)"
    if name in ("source", "."):
        op = next((a for a in args if not a.startswith("-")), None)
        if op is not None and (SUB in op or op.startswith(("<(", "/dev/fd/", "/proc/")) or op in ("/dev/stdin", "-")):
            return name + " of " + op.replace(SUB, "$")[:30] + " (code the guard cannot see)"
        if "<?" in marks: return name + " reading a redirection the guard cannot see"
    if name in SHELLS:
        c, cstr, stdin, op = shell_args(args)
        if name == "script": stdin = not c      # script runs a shell on stdin unless -c
        if c and cstr is not None and SUB in cstr:
            return name + " -c with a string built from an expansion (code the guard cannot see)"
        if op is not None and not stdin and (SUB in op or op.startswith(("<(", "/dev/fd/", "/proc/")) or op == "/dev/stdin"):
            return name + " running " + op.replace(SUB, "$")[:30] + " (code the guard cannot see)"
        if any(a.startswith(("<(", ">(")) for a in args) or "<?" in marks:
            return name + " reading a process substitution or a redirection the guard cannot see"
        if stdin and "|in" in marks and "<<" not in marks:
            return name + " reading commands from a pipe (code the guard cannot see; review S-2)"
    return None

def flags(args, hit, vals, longval, longhit, optional=""):
    i = 0
    while i < len(args):
        a = args[i]
        if a.startswith("--"):
            if longhit(a.split("=")[0]): return True
            if "=" not in a and a in longval: i += 1
        elif a.startswith("-") and len(a) > 1:
            for k, ch in enumerate(a[1:]):
                if ch == hit: return True
                if ch in optional: break
                if ch in vals:
                    if k == len(a) - 2: i += 1
                    break
        i += 1
    return False

def opt_bad(o):
    return o not in SBT_INFO and not SBT_OPT_OK.fullmatch(o)

def sbt_env(assigns):
    for a in assigns:
        n, _, v = a.partition("=")
        if n in SBT_ENV_JVM:
            bad = [t for t in v.split() if not JVM_OPT_OK.fullmatch(t)]
            if bad: return "the JVM option " + bad[0] + " in " + n + " for sbt (allowed: -Xmx, -Xms, -Xss and a few sbt display properties)"
        elif SBT_ENV_BAD.fullmatch(n):
            return "the environment variable " + n + " set for sbt (it changes where sbt, bash or the JVM load code from)"
    return None

def sbt_check(args):
    opts, cmds, i = [], [], 0
    while i < len(args):
        a = args[i]; s = a.strip()
        if s.startswith(("-", "@")):
            if s in ("-mem", "--mem") and i + 1 < len(args) and re.fullmatch(r"[0-9]+", args[i + 1].strip()):
                opts.append(s); i += 2; continue
            if opt_bad(s):
                return "an sbt launcher option outside the allow-list (" + s + "); allowed: -batch, -no-colors, -v, -mem N, --warn, --error, --info, -J-Xmx and a few -Dsbt display properties"
            opts.append(s)
        else:
            cmds += [p.strip() for p in a.split(";") if p.strip()]
        i += 1
    if not cmds:
        if any(o in SBT_INFO for o in opts): return None
        return "sbt with no command (its interactive shell reads commands from stdin)"
    for c in cmds:
        toks = c.split()
        after = None
        for k, t in enumerate(toks):
            if after is not None:
                # R-6: after an input task, a word shaped like a scoped key (core/console,
                # core/Test/console) is still checked against the deny list.
                if re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*(?:/[A-Za-z][A-Za-z0-9_-]*)+", t):
                    key = t.rsplit("/", 1)[-1].lower().replace("-", "")
                    if key in SBT_DENY or key == "marktestrun":
                        return "the sbt command " + t + ", which runs arbitrary code"
                continue
            if t.startswith(("-", "@")) and opt_bad(t):
                return "an sbt option inside a command string (" + t + ")"
            if t.startswith("!"): return "an sbt history command (" + t + ")"
            if t.startswith("~"): return "sbt continuous execution (~), a wait loop"
            base = t.rsplit("/", 1)[-1].lstrip("+\"\x27(")
            m = re.match(r"<|[A-Za-z][A-Za-z0-9_+-]*", base)
            key = (m.group() if m else base).lower().replace("-", "")
            if key == "marktestrun": return "running the markTestRun task by name; run sbt check"
            if key in ("reload", "projects") and k + 1 < len(toks):
                return "sbt " + t + " with arguments (it loads another build)"
            if key in SBT_DENY: return "the sbt command " + t + ", which runs arbitrary code"
            if key in ("runmain", "fgrunmain"):
                cls = toks[k + 1] if k + 1 < len(toks) else ""
                if not cls.startswith("scholarchain."):
                    return "sbt runMain of a class outside the scholarchain package (" + (cls or "none") + ")"
            if key in SBT_ARGS and toks[k + 1:k + 2] != ["/"]:
                after = k                  # the rest are test patterns or program arguments
    return None

def analyze(argv, depth, st, marks=()):
    if argv and argv[0] == "done":
        st["loop"] = max(0, st["loop"] - 1)
    words, xa, assigns, split, seen = wrappers(argv)
    st["loop"] += sum(1 for k in seen if k in ("while", "until"))
    env = any(os.path.basename(a) == "env" for a in argv[:len(argv) - len(words)])
    for a in assigns:
        m = JVM_ENV.fullmatch(a)
        if m and JVM_BAD.search(m.group(2)):
            blocks.append("a JVM agent or hook option in " + m.group(1))
    r = evaluating_words(os.path.basename(words[0]) if words else "", words[1:], words, assigns, marks)
    if r: blocks.append(r)
    if split is not None:
        if SUB in split: blocks.append("env -S with a string built from an expansion (code the guard cannot see)")
        run(split, depth + 1, st); return
    if words and words[0] == "eval":
        st["exec"] = True
        run(" ".join(words[1:]), depth + 1, st); return
    if words and os.path.basename(words[0]) == "find":
        # Round 4: the command that find -exec, -execdir, -ok or -okdir runs is a command.
        j = 1
        while j < len(words):
            if words[j] in ("-exec", "-execdir", "-ok", "-okdir"):
                k = j + 1
                while k < len(words) and words[k] not in (";", "+"): k += 1
                if words[j + 1:k]: analyze(words[j + 1:k], depth + 1, st)
                j = k
            j += 1
    if not words:
        # env with no command prints every variable, the secret ones included.
        if env: blocks.append("printing the whole environment (env with no command)")
        st["envset"] += assigns          # NAME=value; an exported NAME changes for sbt too
        return
    name, args = os.path.basename(words[0]), words[1:]
    # A shell, source, a script path or a variable command word can run a file or string
    # that a data heredoc wrote earlier in the same command (R-1).
    if name in SHELLS | SOURCE or "/" in words[0] or SUB in words[0]:
        st["exec"] = True
    if name in ("export", "declare", "typeset", "readonly", "local"):
        st["envset"] += [a for a in args if "=" in a and not a.startswith(("-", "+"))]
    if name in ("for", "select"): st["loop"] += 1
    if st["loop"] and name in ("sleep", "pgrep", "pidof", "ps"):
        blocks.append("a polling or wait loop (" + name + " inside a loop); run the command once in the foreground under timeout")
    if name == "watch":
        blocks.append("a polling loop (watch); run the command once in the foreground under timeout")
    if name == "printenv":
        names = [a for a in args if not a.startswith("-")]
        if not names:
            blocks.append("printing the whole environment (printenv with no name)")
        elif any(SECRET_VAR.search(n.upper()) or SUB in n for n in names):
            blocks.append("printing a secret environment variable (a name with PAT, TOKEN, SECRET, KEY or PASSWORD)")
    if name in ("export", "declare", "typeset", "readonly"):
        names = [a for a in args if not a.startswith(("-", "+"))]
        if not names and (not args or not all(re.fullmatch(r"[-+][fF]+", a) for a in args)):
            blocks.append("printing the whole environment (" + name + " with no name)")
        for a in names:
            m = JVM_ENV.fullmatch(a)
            if m and JVM_BAD.search(m.group(2)):
                blocks.append("a JVM agent or hook option in " + m.group(1))
            if "-p" in args and SECRET_VAR.search(a.split("=")[0].upper()):
                blocks.append("printing a secret environment variable")
    if name == "set" and not args:
        blocks.append("printing the whole environment (set with no arguments)")
    if SUB in words[0]:
        if any(a in ("apply", "destroy") for a in args):
            blocks.append("apply or destroy run through a variable or $(...) command word")
        if "push" in args:
            blocks.append("a git push run through a variable or $(...) command word")
    if name == "aws":
        pos, rest, i = [], [], 0
        while i < len(args):
            a = args[i]
            if len(pos) < 2 and a.startswith("--"):
                i += 1 if ("=" in a or a in AWS_NOVAL) else 2; continue
            (pos if len(pos) < 2 else rest).append(a); i += 1
        canon.append(" ".join(["aws"] + pos + rest))
    else:
        canon.append(" ".join([name] + args))
    if name in SHELLS:
        for j, a in enumerate(args):
            if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a) and j + 1 < len(args):
                run(args[j + 1], depth + 1, st); break
    if name in ("terraform", "tofu"):
        if any(a in ("apply", "destroy") for a in args):
            blocks.append("raw terraform apply or destroy (the only apply path is scripts/deploy.sh)")
        sub = next((a for a in args if not a.startswith("-")), "")
        if xa or SUB in sub:
            blocks.append("terraform with a subcommand the guard cannot see (xargs or a variable)")
    if name in ("sbt", "sbtn"):
        st["sbt"] = True
        if xa: blocks.append("sbt with commands from xargs (the guard cannot see them)")
        r = sbt_check(args) or sbt_env(assigns)
        if r: blocks.append(r)
    if name == "java":
        for k, a in enumerate(args):
            launch = a == "-jar" and k + 1 < len(args) and "sbt-launch" in os.path.basename(args[k + 1])
            if launch or a in ("xsbt.boot.Boot", "sbt.xMain"):
                st["sbt"] = True
                jvm = [x for x in args[:k] if x.startswith("-")]
                bad = [x for x in jvm if not JVM_OPT_OK.fullmatch(x)]
                r = (("the JVM option " + bad[0] + " for the sbt launcher") if bad else None) \
                    or (("sbt with commands from xargs") if xa else None) \
                    or sbt_check(args[k + (2 if launch else 1):]) or sbt_env(assigns)
                r and blocks.append(r); break
    if name == "git":
        i, gconf = 0, []
        while i < len(args) and args[i].startswith("-"):
            if args[i] in ("-c", "--config-env") and i + 1 < len(args): gconf.append(args[i + 1])
            if args[i].startswith("--config-env="): gconf.append(args[i].split("=", 1)[1])
            i += 2 if args[i] in GIT_GLOBAL_VAL else 1
        sub, rest = (args[i], args[i + 1:]) if i < len(args) else ("", [])
        pushkey = lambda k: re.fullmatch(r"remote\..*\.(push|mirror)", k.lower()) is not None
        if sub == "push":
            force = lambda t: t.startswith("--force") or (len(t) >= 4 and ("--force".startswith(t) or "--mirror".startswith(t)))
            if flags(rest, "f", "o", PUSH_VAL, force) or any(r.startswith("+") and len(r) > 1 for r in rest):
                blocks.append("a force push")
            if any(pushkey(c.split("=", 1)[0]) for c in gconf):
                blocks.append("a force push (a push refspec or mirror given with -c)")
            if any(a.split("=", 1)[0].startswith("GIT_CONFIG") for a in assigns):
                blocks.append("a git push with configuration from GIT_CONFIG variables")
            if xa or any(SUB in a for a in args):
                blocks.append("a git push with arguments the guard cannot see (xargs or a variable)")
        if sub == "config" and any(pushkey(a) for a in rest) and not any(
                a in ("--get", "--get-all", "--get-regexp", "--list", "-l", "--show-origin") for a in rest):
            blocks.append("git config that sets a push refspec or mirror (a force push)")
        if sub == "commit":
            nov = lambda t: t == "--no-verify" or (len(t) >= 9 and "--no-verify".startswith(t))
            if flags(rest, "n", "mFCct", COMMIT_VAL, nov, optional="S"):
                blocks.append("a commit that skips the gate")

def run(s, depth, st):
    if depth > 4: raise ValueError("nesting deeper than the guard follows")   # round 4
    r = evaluating(s)                     # round 4: evaluation sites at every level
    if r: blocks.append(r)
    s, inner = subst(s)
    for x in inner:
        run(x, depth + 1, st)
    segs = segments(s)
    for seg in segs:
        if seg[0]: analyze(seg[0], depth, st, seg[1])
    for route, word in herestrings(segs):
        run(word, depth + 1, st)          # every here-string word is commands (round 4)

def check(text):
    # Returns (failed, exec seen). sbt plus an environment assignment anywhere in the
    # command (NAME=value, export NAME=value) is checked like a prefix assignment (R-2).
    failed, ex = False, False
    vs = variants(re.sub(r"\\\r?\n", " ", text))
    for k, v in enumerate(vs):
        st = {"loop": 0, "exec": False, "envset": [], "sbt": False}
        try:
            run(v, 0, st)
        except ValueError:
            failed = failed or k == len(vs) - 1
        ex = ex or st["exec"]
        if st["sbt"]:
            r = sbt_env(st["envset"])
            if r: blocks.append(r)
    return failed, ex

try:
    # Round 4: every heredoc body inline as commands; a scanner doubt is a block.
    text = heredoc_split(raw, True)[0]
except ValueError as e:
    print("UNPARSED " + str(e)); sys.exit()
failed, ex = check(text)
if blocks: print("BLOCK " + blocks[0].replace(SUB, "$..."))
elif failed: print("UNPARSED an unbalanced quote, an unclosed substitution or nesting deeper than the guard follows")
else: print("OK")
print("\n".join(canon))
' 2>/dev/null || true)
verdict=${structural%%$'\n'*}
canon=""
[[ "$structural" == *$'\n'* ]] && canon=${structural#*$'\n'}
[[ "$verdict" == "BLOCK "* ]] && block "${verdict#BLOCK }"
# Fail closed (Phase 11 C01, C03; round 4): a command that a reading cannot parse, or a
# crashed pass with no verdict, is blocked. The named checks below only give the clearer
# message for terraform, git push, sbt and loops; every other unparsed command is blocked
# after them.
if [[ "$verdict" != "OK" ]]; then
  bare=${norm//\\/}
  c0='(^|[[:space:];&|(`])'                 # a command or argument word starts here
  c1='([[:space:];&|)`]|$)'
  [[ "$bare" =~ $c0(terraform|tofu)$c1 && "$bare" =~ $c0(apply|destroy)$c1 ]] \
    && block "a terraform or tofu apply or destroy the guard could not parse"
  [[ "$bare" =~ ${c0}git$c1 && "$bare" =~ ${c0}push$c1 ]] \
    && block "a git push the guard could not parse"
  [[ "$bare" =~ ${c0}sbtn?$c1 ]] && block "an sbt command the guard could not parse"
  [[ "$bare" =~ $c0(while|until|for|watch)$c1 && "$bare" =~ $c0(sleep|pgrep|pidof|watch)$c1 ]] \
    && block "a loop the guard could not parse"
  why=${verdict#UNPARSED }
  block "a command the guard cannot fully parse (${why:-the structural pass gave no verdict}); it fails closed. Simplify the command, or write text with the Write tool"
fi
# end of structural pass

# Substring patterns, case-insensitive.
blocked_patterns=(
  # template floor: SQL, filesystem, git
  "DROP TABLE" "DROP DATABASE" "TRUNCATE " "--force-delete" "rm -rf /"
  "push --force" "push -f " "push --force-with-lease" "push --mirror"
  "--no-verify" "core.hooksPath" "filter-branch" "filter-repo" "reflog expire"
  "reset --hard" "update-ref -d"
  # Terraform state surgery
  "terraform destroy" "terraform apply" "tofu apply" "tofu destroy"
  "terraform state rm" "terraform state push" "terraform force-unlock"
  "terraform workspace delete"
  # S3 and S3 Vectors
  "s3 rb" "s3api delete-bucket" "s3api delete-objects" "delete-vector-bucket"
  "s3vectors delete-index" "s3vectors delete-vectors"
  # DynamoDB and Neptune (also through the rds alias)
  "dynamodb delete-table" "dynamodb delete-backup" "delete-db-cluster"
  "delete-db-instance" "delete-graph" "initiateDatabaseReset" "performDatabaseReset"
  "DETACH DELETE" "g.V().drop()" "g.E().drop()" "no-deletion-protection" "reset-graph"
  # ECR, KMS, Secrets Manager, credentials
  "ecr delete-repository" "ecr batch-delete-image" "kms schedule-key-deletion"
  "kms disable-key" "kms delete-alias" "delete-imported-key-material"
  "secretsmanager delete-secret" "secretsmanager get-secret-value"
  "batch-get-secret-value" "--with-decryption" "get-login-password"
  "export-credentials" "aws_secret_access_key"
  # credential-minting reads (repair round 3): they print a session token or a registry
  # password; get-authorization-token also covers ecr-public and codeartifact
  "sts get-session-token" "get-authorization-token"
  # EMR and ECS
  "emr terminate-clusters" "no-termination-protected" "ecs delete-service"
  "ecs delete-cluster" "deregister-task-definition" "delete-task-definitions"
  # other destructive AWS verbs
  "cloudformation delete-stack" "sqs delete-queue" "sqs purge-queue"
  "logs delete-log-group" "cloudtrail delete-trail" "cloudtrail stop-logging"
  "opensearch delete-domain" "aoss delete-collection" "ec2 terminate-instances"
  "elbv2 delete-load-balancer" "stepfunctions delete-state-machine"
  "budgets delete-budget" "iam delete-"
)
shopt -s nocasematch
for pat in "${blocked_patterns[@]}"; do
  [[ "$cmd" == *"$pat"* || "$norm" == *"$pat"* || "$canon" == *"$pat"* ]] \
    && block "the command matches forbidden pattern '$pat'"
done
shopt -u nocasematch

# Shape patterns (extended regex) for forms a substring cannot express.
s='[[:space:]]'
[[ "$cmd" =~ (terraform|tofu)($s+-[^[:space:]]+)*$s+(apply|destroy) ]] \
  && block "raw terraform apply or destroy (the only apply path is scripts/deploy.sh)"
[[ "$cmd" =~ s3$s+rm$s.*--recursive|--recursive.*s3$s+rm$s ]] \
  && block "recursive S3 deletion"
[[ "$cmd" =~ s3$s+sync$s.*--delete ]] && block "S3 sync with --delete"
[[ "$cmd" =~ git$s(.*$s)?push$s(.*$s)?\+[^[:space:]] ]] && block "a force push (+refspec)"
[[ "$cmd" =~ git$s+clean$s+-[a-zA-Z]*f ]] && block "git clean -f"
[[ "$cmd" =~ git$s+commit$s(.*$s)?-n($s|$) ]] && block "a commit that skips the gate"
# The same shapes on the normalized copy, with flags anywhere after the subcommand.
w='[^;&|]'                               # stays inside one command segment
g="git($s-[^[:space:]]+($s[^-[:space:]][^[:space:]]*)?)*$s"   # git plus global options
[[ "$norm" =~ (^|[^[:alnum:]_-])(terraform|tofu)$s($w*$s)?(apply|destroy)($s|$) ]] \
  && block "raw terraform apply or destroy (the only apply path is scripts/deploy.sh)"
[[ "$norm" =~ ${g}push($s$w*)?$s(-[a-zA-Z]*f[a-zA-Z]*|--force[^[:space:]]*|--mirror)($s|$) ]] \
  && block "a force push"
[[ "$norm" =~ ${g}push$s($w*$s)?\+[^[:space:]] ]] && block "a force push (+refspec)"
[[ "$norm" =~ ${g}commit($s$w*)?$s(-[a-zA-Z]*n[a-zA-Z]*|--no-verify)($s|$) ]] \
  && block "a commit that skips the gate"
[[ "$norm" =~ s3$s+rm$s.*--recursive|--recursive.*s3$s+rm$s ]] && block "recursive S3 deletion"
[[ "$norm" =~ s3$s+sync$s.*--delete ]] && block "S3 sync with --delete"
[[ "$canon" =~ s3$s+rm$s.*--recursive|--recursive.*s3$s+rm$s ]] && block "recursive S3 deletion"
[[ "$canon" =~ s3$s+sync$s.*--delete ]] && block "S3 sync with --delete"

# Privilege escalation (sudo, su, doas, pkexec), matched only where a command runs: at
# the start, after ; & | ( $( or a backtick or newline, inside a shell's -c string, or
# after wrappers such as env, exec, nohup, time, nice, timeout and xargs. Heredoc bodies
# and other quoted text are data, so a file that mentions the word is not blocked.
priv=$(printf '%s' "$cmd" | python3 -c "$PYLIB"'
import sys
# Every heredoc body stays inline as commands (round 4). The pass prints OK or PRIV word;
# no output (a crash or a scanner doubt) is a block.
kept, _ = heredoc_split(sys.stdin.read(), True)
c = re.sub(r"(?:^|\s)-c\s+[\x27\"]", ";", kept)
wrap = {"env", "exec", "nohup", "time", "command", "builtin", "nice", "xargs", "timeout",
        "stdbuf"}
for seg in re.split(r"[;&|()`\n]|\$\(", c):
    w = seg.split()
    while w and (w[0] in wrap or "=" in w[0] or re.fullmatch(r"-\S*|\d+[smhd]?", w[0])):
        w.pop(0)
    if w and w[0].rsplit("/", 1)[-1] in ("sudo", "su", "doas", "pkexec"):
        print("PRIV " + w[0]); sys.exit()
print("OK")
' 2>/dev/null || true)
[[ "$priv" == "PRIV "* ]] && block "privilege escalation ('${priv#PRIV }'); tools install in user space without root"
[ "$priv" = OK ] || block "a command whose privilege check could not finish (fail closed)"

# Scale caps, stated in docs/agents.md section 6 (expensive scale change):
# - SCALE_MAX, the general cap for every caller: no instance, task or capacity count
#   above it in an EMR, ECS or autoscaling command;
# - INCIDENT_SCALE_MIN..INCIDENT_SCALE_MAX, the separate and tighter incident-responder
#   bound: when the caller is incident-responder, every such count must lie inside it.
SCALE_MAX=4
INCIDENT_SCALE_MIN=1
INCIDENT_SCALE_MAX=2
if [[ "$cmd" =~ (emr|ecs|application-autoscaling|autoscaling) ]]; then
  counts=$(printf '%s' "$cmd" | grep -oiE \
    '(desired-count|instance-count|max-capacity|min-capacity|InstanceCount|DesiredCount|MaximumCapacityUnits|MaxCapacity|TargetOnDemandCapacity|TargetSpotCapacity)"?[[:space:]]*[=:]?[[:space:]]*"?[0-9]+' \
    | grep -oE '[0-9]+$' || true)
  for n in $counts; do
    # A count too long for shell arithmetic is over every cap (never fail open on it).
    [ "${#n}" -gt 9 ] && block "an EMR or ECS scale-out to $n (cap $SCALE_MAX)"
    n=$((10#$n))
    [ "$n" -gt "$SCALE_MAX" ] && block "an EMR or ECS scale-out to $n (cap $SCALE_MAX)"
    if [ "$caller" = "incident-responder" ] \
       && { [ "$n" -lt "$INCIDENT_SCALE_MIN" ] || [ "$n" -gt "$INCIDENT_SCALE_MAX" ]; }; then
      block "incident-responder scaling to $n (its bound is $INCIDENT_SCALE_MIN to $INCIDENT_SCALE_MAX tasks; anything else goes to the human)"
    fi
  done
fi

# scripts/cleanup.sh is human-only; agents may only inspect it.
if [[ "$cmd" == *cleanup.sh* ]]; then
  ok=1
  while IFS= read -r seg; do
    [[ "$seg" == *cleanup.sh* ]] || continue
    seg="${seg#"${seg%%[![:space:]]*}"}"
    [[ "$seg" =~ ^(bash$s+-n|sh$s+-n|shellcheck|cat|head|tail|grep|wc|ls|stat|git$s+(diff|log|show|status))$s ]] || ok=0
  done < <(printf '%s\n' "$cmd" | tr ';&|' '\n\n\n')
  [ "$ok" -eq 1 ] || block "running scripts/cleanup.sh (human only)"
fi

# Shell writes to human-gate.md (human only). A command that names human-gate.md
# literally is blocked when it also holds a write shape: a redirection, tee, sed -i, mv,
# cp, rm, truncate, dd, install, ln, touch, chmod, shred, unlink, find -delete, -exec or
# -ok, git --output, checkout or restore, or an interpreter (python, perl, ruby, node,
# php, awk; sbt, sbtn, scala, scala-cli, amm and jshell since Phase 11 B-1). Round 3 added touch, chmod, shred, unlink, the find actions, the git forms,
# the interpreters, and perl without -i. A write that does not name the file
# literally (a glob, a variable, an ancestor directory) is not matched; the file-tool deny
# rules, git diff, the pre-commit gate and the human gate review are the backstop.
if [[ "$cmd" == *human-gate.md* || "$norm" == *human-gate.md* ]] \
   && [[ "$cmd" =~ ([>]|tee|sed$s+-i|perl$s|mv$s|cp$s|rm$s|truncate|dd$s|install$s|ln$s|touch$s|chmod$s|shred|unlink|-delete|-exec|$s-ok|--output|checkout$s|restore$s|python|ruby$s|node$s|php$s|awk$s|sbtn?$s|scala|amm$s|jshell) ]]; then
  block "a shell write to human-gate.md (written only by the human)"
fi

# Shell writes to the gold-set labels datasets/gold/** (human only, docs/agents.md
# section 1). Only a write whose target resolves under datasets/gold/ is blocked; a read
# (cat, head, wc, a redirection elsewhere) and any other datasets/ path stay allowed.
# Each write target (a redirection, tee, the operands of rm, cp, mv, touch, sed -i and
# similar, find with -delete or -exec, xargs feeding a writer) is resolved against the
# repository root: the payload cwd, then every cd or pushd in the command, ./ and ..
# segments, {a,b} braces, globs (datasets/*/x, */gold/x, datasets/g*), ~, absolute paths,
# and a leading $VAR/ or $(...)/ read as the repository root. Inline interpreter code
# (python -c, awk, sed scripts, interpreter heredocs) that names datasets/gold is
# blocked; sh -c, eval, $(...) and shell heredocs are checked recursively. The value of a
# git --output option (and -o of archive and format-patch) is a write target (round 3).
# Ancestors (Phase 11 C04): rm, rmdir, unlink, shred, chmod, chown and similar on an
# ancestor of datasets/gold (datasets, the repository root, anything above it, or a glob
# that can match one), mv of an ancestor, cp, rsync, scp, install, ln or mv into an
# ancestor from a source that can land on datasets/gold (a name datasets or gold, a glob,
# a trailing / or /.), find with -delete or a writing -exec, -execdir, -ok or -okdir under
# an ancestor whose -name filters can match a file in datasets/gold (any -path, -regex,
# -o or negation fails closed), git checkout or restore of an ancestor, and the output
# option (--out, --output, --capture) of an sbt run. Interpreter code (python, perl,
# ruby, node, php, lua, awk, Rscript, scala, scala-cli, amm, jshell; inline or a heredoc)
# that names both datasets and gold, or names datasets with rmtree, rename, move or a
# shell call, is blocked. Not matched: a bare variable ("$X") or a plain $NAME/ or
# $NAME/. (round 2, R-5), and code that builds the path some other way.
# Round 2 (R-3, supersedes the repair-round-2 datasets/* residual, reopened as Phase 05
# MINOR-C): a delete, move or chmod whose glob can match datasets/gold itself is blocked
# (datasets/*, datasets/*/, ./datasets/*, datasets/[a-h]*, cd datasets && rm -rf *), while
# a glob that cannot match it (datasets/*.tmp) stays allowed. $HOME and ${HOME} resolve
# like ~; $PWD and $OLDPWD read as the repository root. xargs running rm, mv, cp, chmod,
# find and similar is blocked when the command names datasets, runs in datasets, or lists
# names that can include datasets/gold (ls or git ls-files of an ancestor, a find that
# can reach it). Heredocs and here-strings (R-1; round 4): every body is checked as
# commands, with an unknown working directory, and as the code of its receiver and of
# every interpreter the command runs; a body that names datasets allows beside it only
# readers and file commands (cat, tee, grep, ls, cp, mv, rm, mkdir, git reads and
# similar), so cat > x.sh <<EOF ... EOF; bash x.sh is blocked.
# Fails closed (round 4): a command that a reading cannot parse, more brace alternatives
# or gold names than the pass follows, or a crashed pass is blocked.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null && pwd)
GOLDPY="$PYLIB"'
import fnmatch, json, os, posixpath, re, shlex, sys
top = posixpath.normpath(sys.argv[1])
BUILD = len(sys.argv) > 2 and sys.argv[2] == "build"
roots = {top, posixpath.normpath(os.path.realpath(top))}
d = json.load(sys.stdin)
ti = d.get("tool_input", {}) or {}
c = ti.get("command") or ti.get("cmd") or ti.get("cli_command") or ""
c = " ".join(map(str, c)) if isinstance(c, list) else c
home = os.environ.get("HOME") or "/nonexistent"
SUB = "@@SUB@@"
GOLDTXT = re.compile(r"datasets/+(?:\./+)*gold(?![A-Za-z0-9_.-])")
INTERP = {"python", "python2", "python3", "perl", "ruby", "node", "php", "lua", "awk",
          "gawk", "mawk", "nawk", "sed", "Rscript", "jq", "scala", "scala-cli", "amm",
          "jshell"}
# General-purpose interpreters: code that names datasets and gold separately is blocked.
INTERP_CODE = INTERP - {"sed", "jq"}
DIRCODE = re.compile(r"rmtree|removedirs|rename|move|copytree|system|subprocess|Popen|spawn|exec|rm\s+-")
ANC_ANY = {"rm", "rmdir", "unlink", "shred", "chmod", "chown", "chgrp", "setfacl", "chattr",
           "truncate"}
COPYERS = {"cp", "mv", "rsync", "scp", "install", "ln"}
XDEL = ANC_ANY | COPYERS | {"find"}      # xargs runs these with names read from stdin
FIND_READ = {"cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "ls", "stat", "file",
             "md5sum", "sha1sum", "sha256sum", "du", "echo", "printf", "test", "[", "diff",
             "cmp", "basename", "dirname", "readlink", "realpath", "jq"}
SBT_OUT = re.compile(r"--?(out|output|capture|out-dir|output-dir|output-root)")
WRAP = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout", "stdbuf",
        "then", "do", "else", "elif", "if", "while", "until", "!", "{", "}", "sudo", "doas"}
EVERY = {"rm", "rmdir", "unlink", "touch", "truncate", "mkdir", "chmod", "chown", "chgrp",
         "shred", "mv", "cp", "install", "ln", "rsync", "scp", "tee", "patch", "split",
         "csplit", "mkfifo", "mknod", "setfacl", "chattr", "sponge", "gzip", "gunzip",
         "bzip2", "bunzip2", "xz", "unxz", "zstd", "unzstd", "vi", "vim", "nvim", "nano",
         "emacs", "ed", "ex", "dd", "curl", "wget", "tar", "unzip"}
CWD_WRITERS = {"curl", "wget", "tar", "unzip"}   # may write into the working directory
FIND_WRITE = {"-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0",
              "-fprintf", "-fls"}
GIT_WRITE = {"rm", "mv", "checkout", "restore", "apply", "clean", "reset", "stash", "am",
             "cherry-pick", "revert", "merge", "pull", "switch"}
XARGS_VAL = {"-I", "-L", "-n", "-P", "-s", "-d", "-E", "-a", "--arg-file", "--delimiter",
             "--max-args", "--max-procs", "--max-lines", "--max-chars"}
SEPS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "`", "\n"}
WOPS = {">", ">>", ">|", "&>", "&>>", "<>", ">&"}
syntax = re.compile(r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 |
    (?P<op>&>>|&>|>>|>\||>&|<<<|<<-|<<|<>|<&|&&|\|\||\|&|;;|[;&|()<>`\n])""", re.S | re.X)
mentions = [bool(GOLDTXT.search(c))]

VM = "\x00"           # marks SUB as a plain $NAME or ${NAME} (R-5)
DSWORD = re.compile(r"(?<![A-Za-z0-9_.-])datasets(?![A-Za-z0-9_-])")
GOLD_SAFE = {"cat", "tee", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "sort", "uniq",
             "diff", "cmp", "md5sum", "sha1sum", "sha256sum", "cut", "tr", "nl", "fold", "fmt",
             "column", "base64", "jq", "true", "false", "echo", "printf", "mkdir", "cd",
             "pushd", "popd", "ls", "stat", "test", "[", "[[", "date", "basename", "dirname",
             "readlink", "realpath", "du", "file", "cp", "mv", "rm", "touch", "ln", "less",
             "more"}
GIT_READS = {"status", "diff", "log", "show", "ls-files", "check-ignore", "rev-parse", "blame",
             "grep"}
LISTERS = {"ls", "tree", "du"}

def subst(s):
    # Replace $(...), backticks, ${...} and $NAME by SUB; return the inner commands.
    # $HOME and ${HOME} become the home directory (R-3), like ~; $PWD and $OLDPWD become
    # SUB/. so that even "$PWD" alone reads as the repository root; another plain $NAME
    # or ${NAME} becomes SUB plus VM.
    out, inner, i, q = [], [], 0, ""
    while i < len(s):
        ch = s[i]
        if q == "\x27":
            out.append(ch); i += 1
            if ch == "\x27": q = ""
            continue
        if ch == "\\" and i + 1 < len(s):
            out.append(s[i:i + 2]); i += 2; continue
        if ch == "\x27" and not q:
            q = "\x27"; out.append(ch); i += 1; continue
        if ch == "\"":
            q = "" if q else "\""; out.append(ch); i += 1; continue
        if ch == "$" and s[i + 1:i + 2] == "(":
            depth, j = 1, i + 2
            while j < len(s) and depth:
                depth += {"(": 1, ")": -1}.get(s[j], 0); j += 1
            inner.append(s[i + 2:j - 1]); out.append(SUB); i = j; continue
        if ch == "`":
            j = s.find("`", i + 1); j = len(s) if j < 0 else j
            inner.append(s[i + 1:j]); out.append(SUB); i = j + 1; continue
        if ch == "$" and s[i + 1:i + 2] == "{":
            j = _brace_end(s, i + 2); inner += subst(s[i + 2:j])[1]
            out.append(var(s[i + 2:j])); i = j + 1; continue
        m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*|[0-9@*#?!$-])", s[i:])
        if m:
            out.append(var(m.group(1))); i += len(m.group()); continue
        out.append(ch); i += 1
    return "".join(out), inner

def var(n):
    if n == "HOME": return home
    if n in ("PWD", "OLDPWD"): return SUB + "/."
    return SUB + VM if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", n) else SUB

def tokens(s):
    pos = 0
    for m in syntax.finditer(s):
        if m.lastgroup == "op":
            for w in shlex.split(s[pos:m.start()]): yield False, w
            yield True, m.group(); pos = m.end()
    for w in shlex.split(s[pos:]): yield False, w

def segments(s):
    segs, words, reds, hs, it, i = [], [], [], [], list(tokens(s)), 0
    while i < len(it):
        op, t = it[i]
        if op and (t in SEPS or (t not in WOPS and not t.startswith("<"))):
            segs.append((words, reds, t, hs)); words, reds, hs = [], [], []
        elif op:
            if i + 1 < len(it) and not it[i + 1][0]:
                i += 1
                nxt = it[i][1]
                if t in WOPS and not (t == ">&" and re.fullmatch(r"\d+-?|-", nxt)):
                    reds.append(nxt)
                elif t == "<<<": hs.append(nxt)
        else:
            words.append(t)
        i += 1
    segs.append((words, reds, "", hs))
    return [x for x in segs if x[0] or x[1] or x[3]]

def braces(w, n=0):
    m = re.search(r"\{([^{}]*,[^{}]*)\}", w)
    if not m or n > 6: return [w]
    out = []
    for alt in m.group(1).split(","):
        out += braces(w[:m.start()] + alt + w[m.end():], n + 1)
        if len(out) > 256: raise ValueError("more brace alternatives than the guard follows")
    return out

def can(comp, name):
    return comp == name or (re.search(r"[*?\[]", comp) is not None and fnmatch.fnmatchcase(name, comp))

def specific(comp):
    return re.sub(r"\[[^\]]*\]|[*?]", "", comp) != ""

def gold_parts(p):
    if len(p) < 2 or not (can(p[0], "datasets") and can(p[1], "gold")): return False
    if len(p) == 2: return specific(p[1])        # datasets/gold itself; never datasets/*
    return specific(p[0]) or specific(p[1])

def under_gold(word, cwd):
    word = word.replace(VM, "")
    for w in braces(word):
        if w == "~" or w.startswith("~/"): w = home + w[1:]
        cands = []
        if w.startswith(SUB):
            rest = w[len(SUB):].lstrip("/").replace(SUB, "*")
            cands += [posixpath.join(r, rest) for r in roots]
            if cwd: cands.append(posixpath.join(cwd, rest))
        else:
            w = w.replace(SUB, "*")
            if w.startswith("/"): cands.append(w)
            elif cwd is None:
                cands += [posixpath.join(r, x, w) for r in roots for x in ("", "datasets")]
            else: cands.append(posixpath.join(cwd, w))
        for p in cands:
            p = posixpath.normpath(p)
            for r in roots:
                if p.startswith(r + "/") and gold_parts(p[len(r) + 1:].split("/")): return True
    return False

def ancestor(word, cwd):
    # True when the word resolves to datasets, the repository root or anything above it,
    # or to datasets/gold itself, or is a glob that can match one of them (R-3: datasets/*,
    # datasets/*/ and * in datasets can match gold; datasets/*.tmp cannot). A bare
    # variable is not resolved, and neither is a plain $NAME/ or $NAME/. (R-5; $HOME and
    # $PWD are resolved, see subst).
    if word.replace(VM, "") in (SUB, "\"" + SUB + "\""): return False
    if word.startswith(SUB + VM):
        rest = word[len(SUB + VM):]
        if rest.startswith("/") and all(p in ("", ".") for p in rest.split("/")): return False
    word = word.replace(VM, "")
    for w in braces(word):
        if w == "~" or w.startswith("~/"): w = home + w[1:]
        cands = []
        if w.startswith(SUB):
            rest = w[len(SUB):].lstrip("/").replace(SUB, "*")
            cands += [posixpath.join(r, rest) for r in roots]
        else:
            w = w.replace(SUB, "*")
            if w.startswith("/"): cands.append(w)
            elif cwd is None:
                cands += [posixpath.join(r, x, w) for r in roots for x in ("", "datasets")]
            else: cands.append(posixpath.join(cwd, w))
        for p in cands:
            pc = [x for x in posixpath.normpath(p).split("/") if x]
            for r in roots:
                gc = [x for x in (r + "/datasets/gold").split("/") if x]
                if len(pc) <= len(gc) and all(can(a, b) for a, b in zip(pc, gc)): return True
    return False

def gold_names():
    if "names" not in GOLD:
        names = {"gold"}
        for r in roots:
            for dp, dn, fn in os.walk(posixpath.join(r, "datasets", "gold")):
                names.update(dn + fn)
                if len(names) > 5000: raise ValueError("too many names to check")
        GOLD["names"] = names
    return GOLD["names"]
GOLD = {}

# Build mode (repair round 3, human): the same write analysis for the sbt build inputs
# project/ (at the repository root), ~/.sbt and ~/.ivy2. Writes there go only through the
# Write tool, whose ownership check applies; a copy FROM them, reads and sbt itself stay.
HOMES = sorted({posixpath.normpath(home), posixpath.normpath(os.path.realpath(home))})
PROT = [[x for x in (b + "/" + n).split("/") if x] for b, n in
        [(r, "project") for r in sorted(roots)] + [(h, n) for h in HOMES for n in (".sbt", ".ivy2")]]
BTXT = re.compile(r"(?<![A-Za-z0-9_.-])project(?![A-Za-z0-9_.-])|(?<![A-Za-z0-9_-])\.sbt(?![A-Za-z0-9_-])|\.ivy2(?![A-Za-z0-9_-])")
BWRITE = re.compile(r"open\s*\(|write|rename|replace|unlink|remove|rmtree|rmdir|mkdir|makedirs|copy|move|touch|symlink|link|chmod|truncate|system|subprocess|Popen|spawn|exec|>|-i|\bw\b")
def bcands(word, cwd):
    word = word.replace(VM, "")
    out = []
    for w in braces(word):
        if w == "~" or w.startswith("~/"): w = home + w[1:]
        if w.startswith(SUB):
            rest = w[len(SUB):].lstrip("/").replace(SUB, "*")
            out += [posixpath.join(b, rest) for b in sorted(roots) + HOMES]
            if cwd: out.append(posixpath.join(cwd, rest))
        else:
            w = w.replace(SUB, "*")
            if w.startswith("/"): out.append(w)
            elif cwd is None: out += [posixpath.join(b, w) for b in sorted(roots) + HOMES]
            else: out.append(posixpath.join(cwd, w))
    return [[x for x in posixpath.normpath(p).split("/") if x] for p in out]
def under_build(word, cwd):
    # Inside a protected directory (a glob that can reach one counts, conservatively).
    return any(len(pc) >= len(g) and all(can(a, b) for a, b in zip(pc, g))
               for pc in bcands(word, cwd) for g in PROT)
def ancestor_build(word, cwd):
    if word.replace(VM, "") in (SUB, "\"" + SUB + "\""): return False
    if word.startswith(SUB + VM):
        rest = word[len(SUB + VM):]
        if rest.startswith("/") and all(p in ("", ".") for p in rest.split("/")): return False
    return any(len(pc) <= len(g) and all(can(a, b) for a, b in zip(pc, g))
               for pc in bcands(word, cwd) for g in PROT)
def build_names():
    if "names" not in GOLD:
        names = {"project", ".sbt", ".ivy2"}
        for g in PROT:
            for dp, dn, fn in os.walk("/" + "/".join(g)):
                names.update(dn + fn)
                if len(names) > 5000: raise ValueError("too many names to check")
        GOLD["names"] = names
    return GOLD["names"]
def in_build(cwd):
    pc = [x for x in (cwd or "").split("/") if x]
    return cwd is not None and any(pc[:len(g)] == g for g in PROT)
def code_hit(name, text):
    # Interpreter code (inline or a heredoc program): gold mode as before; build mode: code
    # naming project, .sbt or .ivy2 with a write-like word, and any sed script naming them
    # (sed w, W and e write or run).
    if not BUILD:
        if name in INTERP and GOLDTXT.search(text): return "code that names datasets/gold"
        if name in INTERP_CODE and "datasets" in text and ("gold" in text or DIRCODE.search(text)):
            return "code that names datasets with gold or a directory operation"
        return None
    if name in INTERP and BTXT.search(text) and (name == "sed" or BWRITE.search(text)):
        return "code that names project/, ~/.sbt or ~/.ivy2 with a write"
    return None
if BUILD:
    GOLDTXT = DSWORD = BTXT
    under_gold, ancestor, gold_names = under_build, ancestor_build, build_names

def copy_operands(args):
    dest, ops, i = None, [], 0
    while i < len(args):
        a = args[i]
        if a in ("-t", "--target-directory") and i + 1 < len(args): dest = args[i + 1]; i += 2; continue
        if a.startswith("--target-directory="): dest = a.split("=", 1)[1]
        elif re.fullmatch(r"-t.+", a): dest = a[2:]
        elif not a.startswith("-"): ops.append(a)
        i += 1
    if dest is None:
        return (ops[-1] if len(ops) > 1 else None), ops[:-1]
    return dest, ops

def find_reaches_gold(expr):
    if any(a in ("-path", "-ipath", "-wholename", "-iwholename", "-regex", "-iregex", "-o",
                 "-or", "!", "-not") for a in expr): return True
    pats = [(expr[i + 1], expr[i] == "-iname") for i, a in enumerate(expr)
            if a in ("-name", "-iname") and i + 1 < len(expr)]
    if not pats: return True
    return any(all(fnmatch.fnmatchcase(n.lower() if ci else n, p.lower() if ci else p)
                   for p, ci in pats) for n in gold_names())

def chdir(args, cwd):
    a = [x.replace(VM, "") for x in args if not re.fullmatch(r"-[LPe@]+|--", x)]
    if not a: return home
    t = a[0]
    if t == "-": return None
    if t == "~" or t.startswith("~/"): t = home + t[1:]
    if t.startswith(SUB):
        rest = t[len(SUB):]
        return posixpath.normpath(posixpath.join(top, rest.lstrip("/"))) if rest.startswith("/") and SUB not in rest else None
    if SUB in t: return None
    if t.startswith("/"): return posixpath.normpath(t)
    return None if cwd is None else posixpath.normpath(posixpath.join(cwd, t))

def every(args):
    out = []
    for a in args:
        if a.startswith("-"):
            if "=" in a: out.append(a.split("=", 1)[1])
            elif len(a) > 2 and not a.startswith("--"): out.append(a[2:])
        else:
            out.append(a)
            if "=" in a: out.append(a.split("=", 1)[1])
    return out

def strip(words):
    # Wrappers with their option values (timeout -s KILL, env -u NAME; Phase 11), so the
    # command word is never a wrapper option value.
    words, xa, _, split, _ = wrappers(words)
    if split is not None: words = ["eval", split]
    return words, xa

def analyze(words, reds, st, depth):
    cwd = st["cwd"]
    for r in reds:
        if under_gold(r, cwd): return "a redirection into " + r
    words, xa = strip(words)
    if not words: return None
    name, args = posixpath.basename(words[0]), words[1:]
    st["names"].append((words[0], args))
    if xa and name in XDEL and (DSWORD.search(c) or GOLDTXT.search(c) or st["listing"]
                                or (cwd and any(cwd == r + "/datasets" or cwd.startswith(r + "/datasets/") for r in roots))
                                or (BUILD and in_build(cwd))):
        return "xargs " + name + " fed with names that can include datasets/gold"
    if name == "eval": return run(" ".join(args), st, depth + 1)
    if name in SHELLS:
        for j, a in enumerate(args):
            if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a) and j + 1 < len(args):
                return run(args[j + 1], st, depth + 1)
        return None
    if name in ("cd", "pushd"):
        st["cwd"] = chdir(args, cwd); return None
    if name == "popd":
        st["cwd"] = None; return None
    # A listing of an ancestor of datasets/gold, or a find that can reach it, may feed an
    # xargs deleter later in the command (R-3).
    if name in LISTERS:
        ops = [a for a in args if not a.startswith("-")]
        if (not ops and (cwd is None or ancestor(".", cwd))) or any(ancestor(a, cwd) or under_gold(a, cwd) for a in ops):
            st["listing"] = True
    if name == "git":
        sub = next((a for a in args if not a.startswith("-")), "")
        if sub in ("ls-files", "ls-tree", "grep", "diff", "status"):
            ops = [a for a in args[args.index(sub) + 1:] if not a.startswith("-")]
            if not ops or any(ancestor(a, cwd) or under_gold(a, cwd) or re.search(r"[*?\[]", a) for a in ops):
                st["listing"] = True
    if name == "find":
        k = 0
        while k < len(args) and not args[k].startswith(("-", "(", "!", ")")): k += 1
        if any(ancestor(x, cwd) for x in (args[:k] or ["."])) and find_reaches_gold(args[k:]):
            st["listing"] = True
    plain = [a.replace(VM, "") for a in args]
    if name in INTERP:
        for a in plain:
            if re.fullmatch(r"[A-Za-z0-9_./*?\[\]{},@=+:-]+", a): continue
            hit = code_hit(name, a)
            if hit: return name + " inline " + hit
    if name in ("sbt", "sbtn"):
        toks = [t for a in args for t in re.split(r"[\s;]+", a) if t]
        for j, t in enumerate(toks):
            k, _, v = t.partition("=")
            if SBT_OUT.fullmatch(k):
                v = v or (toks[j + 1] if j + 1 < len(toks) else "")
                if under_gold(v, cwd) or ancestor(v, cwd): return "an sbt run writing to " + v
    if name in ANC_ANY:
        for t in every(args):
            if ancestor(t, cwd): return name + " on " + t + ", datasets/gold or an ancestor of it (or a glob that can match one)"
    if name in COPYERS:
        dest, srcs = copy_operands(args)
        if name == "mv":
            for x in srcs:
                if ancestor(x, cwd): return "mv of " + x + ", datasets/gold or an ancestor of it (or a glob that can match one)"
        if dest and ancestor(dest, cwd):
            for x in srcs:
                b = posixpath.basename(x.rstrip("/"))
                if (x.endswith(("/", "/.")) or b in ("", ".", "..", "datasets", "gold", "project", ".sbt", ".ivy2")
                        or SUB in x or re.search(r"[*?\[]", b)):
                    return name + " into " + dest + ", which can overwrite datasets/gold"
    if name == "find":
        i = 0
        while i < len(args) and not args[i].startswith(("-", "(", "!", ")")): i += 1
        starts, expr = args[:i] or ["."], args[i:]
        writes = "-delete" in expr or any(
            a in ("-exec", "-execdir", "-ok", "-okdir") and j + 1 < len(expr)
            and posixpath.basename(expr[j + 1]) not in FIND_READ for j, a in enumerate(expr))
        if writes and any(ancestor(x, cwd) for x in starts) and find_reaches_gold(expr):
            return "find under " + " ".join(starts) + " that can delete or change datasets/gold"
    targets, tcwd = [], cwd
    inplace = any(re.fullmatch(r"-[A-Za-z]*i\S*|--in-place\S*", a) for a in args)
    if name in EVERY or (name in ("sed", "perl") and inplace):
        targets = every(args)
        # A copy FROM project/ is a read; a hard or symbolic link copy (cp -l, -s) or a
        # move-like rsync keeps the source writable through the copy, so it is not.
        if BUILD and name in ("cp", "scp", "rsync") and not any(
                a.startswith(("--remove-source", "--link", "--symbolic-link"))
                or re.fullmatch(r"-[A-Za-z]*[ls][A-Za-z]*", a) for a in args):
            targets = [copy_operands(args)[0] or ""]
        if name in CWD_WRITERS: targets = [t for t in targets if "://" not in t] + ["x"]
    elif name == "find" and any(a in FIND_WRITE for a in args):
        targets = every(args)
    elif name == "sort":
        for j, a in enumerate(args):
            if a in ("-o", "--output") and j + 1 < len(args): targets.append(args[j + 1])
            elif a.startswith("--output="): targets.append(a.split("=", 1)[1])
            elif a.startswith("-o") and len(a) > 2: targets.append(a[2:])
    elif name == "uniq":
        pos = [a for a in args if not a.startswith("-")]
        targets = pos[1:2]
    elif name == "git":
        i = 0
        while i < len(args) and args[i].startswith("-"):
            if args[i] == "-C" and i + 1 < len(args):
                tcwd = chdir([args[i + 1]], tcwd); i += 2
            elif args[i] in ("-c", "--git-dir", "--work-tree", "--namespace"): i += 2
            else: i += 1
        if i < len(args) and args[i] in GIT_WRITE: targets = every(args[i + 1:])
        # --output (diff, log, show and every other subcommand; repair round 3) and the
        # -o and --output-directory of archive and format-patch name a file git writes.
        sub, rest = (args[i], args[i + 1:]) if i < len(args) else ("", [])
        if sub in ("checkout", "restore", "rm", "mv"):
            for t in every(rest):
                if ancestor(t, tcwd): return "git " + sub + " of " + t + ", datasets/gold or an ancestor of it (or a glob that can match one)"
        for j, a in enumerate(rest):
            n = a.split("=", 1)[0]
            out = n.startswith("--output") or (len(n) >= 6 and "--output".startswith(n))
            if sub in ("archive", "format-patch") and re.fullmatch(r"-o.*", a):
                targets.append(a[2:] if len(a) > 2 else (rest[j + 1] if j + 1 < len(rest) else ""))
            elif out:
                targets.append(a.split("=", 1)[1] if "=" in a else (rest[j + 1] if j + 1 < len(rest) else ""))
    if xa and targets is not None and (name in EVERY or name in ("sed", "perl", "find")) and mentions[0]:
        return "xargs " + name + " with a datasets/gold path"
    for t in targets:
        if under_gold(t, tcwd): return name + " on " + t
    return None

def run(s, st, depth):
    if depth > 4: raise ValueError("nesting deeper than the guard follows")   # round 4
    s, bodies = heredoc_split(s)          # before joining (S-1): a quoted body keeps its backslash lines
    s = re.sub(r"\\\r?\n", " ", s)
    s, inner = subst(s)
    for x in inner:
        r = run(x, dict(st), depth + 1)
        if r: return r
    segs = segments(s)
    for words, reds, _, _ in segs:
        if any(under_gold(w, st["cwd"]) for w in words + reds): mentions[0] = True
        r = analyze(words, reds, st, depth)
        if r: return r
    bodies = bodies + herestrings(segs)
    interp = {posixpath.basename(w) for w, _ in st["names"]} & INTERP
    for route, body in bodies:
        # Round 4: every heredoc body and here-string word is commands. A heredoc body can
        # be run later from wherever the command is then, so it is read with an unknown
        # working directory (relative names match the repository root and datasets/). It
        # is also read as the code of its receiver and of every interpreter the command
        # runs, and a body that names datasets allows beside it only readers and file
        # commands (R-1).
        r = run(body, dict(st, cwd=None) if route is None else st, depth + 1)
        if r: return r
        for name in sorted(interp | {n for n, _, _ in route_names(route)}):
            hit = code_hit(name, body)
            if hit: return name + " heredoc " + hit
        if GOLDTXT.search(body) or DSWORD.search(body):
            st["hdata"] = True
    return None

def gold_safe(w, args):
    # Commands that cannot run a file or string a data heredoc wrote (R-1).
    n = posixpath.basename(w)
    if n == "git":
        sub = next((a for a in args if not a.startswith("-")), "")
        return sub in GIT_READS and not any(a in ("-c", "--config-env") or a.startswith("--config-env=") for a in args)
    return n in GOLD_SAFE and w == n

start = posixpath.normpath(d.get("cwd") or top)
r, failed = None, False
vs = variants(c)
for k, v in enumerate(vs):
    st = {"cwd": start, "names": [], "listing": False, "hdata": False}
    try:
        r = run(v, st, 0)
        if not r and st["hdata"]:
            # A data heredoc that names datasets, beside a command that can run what it
            # wrote (bash /tmp/x.sh, python3, a script path, $X): read as HEAD did, blocked.
            bad = next((w for w, a in st["names"] if not gold_safe(w, a)), None)
            if bad: r = bad + " in a command whose heredoc names datasets (only readers and file commands may run beside it)"
    except Exception:
        failed = failed or k == len(vs) - 1
    if r: break
if r and BUILD: r = r.replace("datasets/gold", "project/, ~/.sbt or ~/.ivy2")
print(("BLOCK " + r.replace(SUB, "$").replace(VM, "")) if r else ("UNPARSED" if failed else "OK"))
'
goldv=$(printf '%s' "$payload" | python3 -c "$GOLDPY" "$root" gold 2>/dev/null || true)
buildv=$(printf '%s' "$payload" | python3 -c "$GOLDPY" "$root" build 2>/dev/null || true)
case "$goldv" in
  OK) ;;
  "BLOCK "*) block "a shell write to the gold-set labels under datasets/gold/: ${goldv#BLOCK } (written only by the human)" ;;
  *) if { [[ "$cmd" =~ datasets/+(\./+)*gold ]] \
          && [[ "$cmd" =~ ([>]|tee|sed$s+-i|perl$s+-i|mv$s|cp$s|rm$s|truncate|dd$s|install$s|ln$s|touch$s|chmod$s|--output) ]]; } \
        || { [[ "$cmd" =~ (^|[^[:alnum:]_.-])datasets([^[:alnum:]_.-]|$) ]] \
          && [[ "$cmd" =~ (rm|mv|rmdir|shred|rsync|unlink)$s|-delete|rmtree ]]; }; then
       block "a shell write to the gold-set labels under datasets/gold/ that the guard could not parse (written only by the human)"
     fi
     block "a command whose gold-set write check could not finish (${goldv:-no verdict}); it fails closed" ;;
esac
# Shell writes to the sbt build inputs (repair round 3, human): project/ at the repository
# root, ~/.sbt and ~/.ivy2 (~, $HOME, ${HOME}, absolute, relative and through cd). The same
# write analysis as the gold set (GOLDPY in build mode); a copy from them, reads, git reads
# and sbt itself stay allowed. Fails closed when unparsed: a project/, .sbt or .ivy2 path
# with a write shape is blocked.
case "$buildv" in
  OK) ;;
  "BLOCK "*) block "a shell write to the sbt build inputs: ${buildv#BLOCK } (project/ changes go only through the Write tool and its ownership check; agents never write ~/.sbt or ~/.ivy2)" ;;
  *) if [[ "$cmd" =~ (^|[^[:alnum:]_.-])project(/|[[:space:]]|$)|(^|[^[:alnum:]_-])\.sbt([^[:alnum:]_-]|$)|\.ivy2 ]] \
        && [[ "$cmd" =~ ([>]|tee|sed$s+-i|perl$s+-i|mv$s|cp$s|rm$s|rmdir|mkdir|truncate|dd$s|install$s|ln$s|touch$s|chmod$s|rsync|unlink|shred|--output|-delete|rmtree) ]]; then
       block "a shell write to the sbt build inputs (project/, ~/.sbt, ~/.ivy2) that the guard could not parse"
     fi
     block "a command whose build-input write check could not finish (${buildv:-no verdict}); it fails closed" ;;
esac

# Shell writes to the test marker .claude/.last-test-run. Only the sbt markTestRun task
# writes it, inside the sbt JVM at the end of 'sbt check', so no agent command may.
# Rewritten in Phase 11 (C05) to stop blocking harmless text; every write shape below
# stays blocked:
# - a redirection (> >> >| &>) into the marker;
# - markTestRun as an argument of sbt or sbtn (quotes resolved, so "mark""TestRun" too),
#   also inside sh -c, eval, $(...) and a heredoc fed to a shell ('sbt check' stays
#   allowed); the structural pass blocks it as an sbt command as well;
# - a command segment that names the marker (or a glob directly under .claude/) unless it
#   is a read: cat, head, tail, stat, ls, test, wc, grep, diff and similar, find without a
#   write action, a git read subcommand without --output, sed without -i (the marker as a
#   file operand, never in the script), and cp with the marker only as a source (no -l or
#   -s, and a destination that is neither the marker nor the .claude directory);
# - when the marker name is handed on as data (a VAR=value word, export, read, for or
#   case, a naming segment piped onward, $(...) or <(...) naming it, or a file written by
#   a naming segment that another segment reads: echo m > f; xargs touch < f), the old
#   rule: every segment of the command must be a read;
# - find whose -name or -path pattern matches the marker, with a write action or piped
#   onward (a listing of it written to a file counts as a hand-off);
# - every heredoc body and here-string word is checked as commands (round 4); a heredoc
#   that names the marker or the task allows beside it only readers, the safe consumers
#   and mkdir; a here-string that names them fed to anything but a reader or a shell;
# Allowed: grep, sed -n, echo, git log, and other commands beside a segment that only
# reads the marker. Report text goes through the Write tool, not a heredoc.
# Fails closed: a command that a reading cannot parse is blocked (round 4).
marker=$(printf '%s' "$cmd" | python3 -c "$PYLIB"'
import fnmatch, posixpath, sys
c = sys.stdin.read()      # continuation lines are joined after the heredoc split (S-1)
MARK = re.compile(r"\.last-test-run|\.claude/\.?[^\s/]*[*?\[]|(?i:marktestrun)")
READ = {"cat", "head", "tail", "less", "more", "stat", "ls", "test", "[", "[[", "wc",
        "file", "grep", "egrep", "fgrep", "rg", "diff", "cmp", "md5sum", "sha1sum",
        "sha256sum", "readlink", "realpath", "du", "date", "true", "false", "basename",
        "dirname", "cd", "pushd", "popd"}
OLD_READ = READ | {"echo", "printf", "for", "case", "find", "git"}
DATA = {"echo", "printf", "for", "case", "select", "export", "declare", "local",
        "readonly", "typeset", "read", "set", "let"}
SAFE_CONSUMERS = {"cat", "tee", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "sort",
                  "uniq", "diff", "cmp", "md5sum", "sha1sum", "sha256sum", "less", "more",
                  "cut", "tr", "nl", "fold", "fmt", "column", "base64", "jq", "true", "false"}
GIT_READ = {"status", "diff", "log", "show", "ls-files", "check-ignore", "rev-parse",
            "blame", "grep"}
FIND_WRITE = {"-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0",
              "-fprintf", "-fls"}
WOPS = {">", ">>", ">|", "&>", "&>>", "<>", ">&"}
SEPS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "`", "\n"}
syntax = re.compile(r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 |
    (?P<op>&>>|&>|>>|>\||>&|<<<|<<-|<<|<>|<&|&&|\|\||\|&|;;|[;&|()<>`\n])""", re.S | re.X)

class Block(Exception): pass

def tokens(s):
    pos = 0
    for m in syntax.finditer(s):
        if m.lastgroup == "op":
            for w in shlex.split(s[pos:m.start()]): yield False, w
            yield True, m.group(); pos = m.end()
    for w in shlex.split(s[pos:]): yield False, w

def segments(s):
    segs, words, reds, hs, it, i = [], [], [], [], list(tokens(s)), 0
    while i < len(it):
        op, t = it[i]
        if op and (t in SEPS or (t not in WOPS and not t.startswith("<"))):
            segs.append((words, reds, t, hs)); words, reds, hs = [], [], []
        elif op:
            if i + 1 < len(it) and not it[i + 1][0]:
                i += 1
                if t in WOPS: reds.append(it[i][1])
                elif t == "<<<": hs.append(it[i][1])
                elif t in ("<", "<&"): ins.append(it[i][1])
        else:
            words.append(t)
        i += 1
    segs.append((words, reds, "", hs))
    return [x for x in segs if x[0] or x[1] or x[3]]

ins = []                 # input redirection targets (< file), for the file hand-off rule

def procsubs(s):
    # The text of every unquoted <(...) and >(...) (process substitution).
    out, i, q = [], 0, ""
    while i < len(s):
        ch = s[i]
        if q:
            if ch == q: q = ""
            elif ch == "\\" and q == "\"": i += 1
            i += 1; continue
        if ch == "\\": i += 2; continue
        if ch in "\x27\"": q = ch; i += 1; continue
        if ch in "<>" and s[i + 1:i + 2] == "(":
            depth, j = 1, i + 2
            while j < len(s) and depth:
                depth += {"(": 1, ")": -1}.get(s[j], 0); j += 1
            out.append(s[i + 2:j - 1])
        i += 1
    return out

def named(w): return MARK.search(w) is not None

def git_read(args):
    i = 0
    while i < len(args) and args[i].startswith("-"):
        i += 2 if args[i] in ("-C", "-c") else 1
    if i >= len(args) or args[i] not in GIT_READ: return False
    # git diff, log and show write a file with --output (repair round 3).
    return not any(a.split("=", 1)[0].startswith("--output") or
                   (len(a.split("=", 1)[0]) >= 6 and "--output".startswith(a.split("=", 1)[0]))
                   for a in args[i:])

def sed_read(args):
    if any(re.fullmatch(r"-[A-Za-z]*i\S*|--in-place\S*|-s?i", a) for a in args): return False
    script, pos, i = False, [], 0
    while i < len(args):
        a = args[i]
        if a in ("-e", "--expression", "-f", "--file"):
            if i + 1 < len(args) and named(args[i + 1]): return False
            script = True; i += 2; continue
        if a.startswith(("--expression=", "--file=")) or re.fullmatch(r"-[nrsuzE]*[ef].*", a):
            if named(a): return False
            script = True
        elif a.startswith("-"):
            if named(a): return False
        else: pos.append(a)
        i += 1
    if not script and pos and named(pos[0]): return False
    return True

def cp_read(args):
    if any(re.fullmatch(r"-[A-Za-z]*[ls][A-Za-z]*", a) or a.split("=")[0] in
           ("--link", "--symbolic-link", "--target-directory") or re.fullmatch(r"-t.*", a)
           for a in args): return False
    ops = [a for a in args if not a.startswith("-")]
    if len(ops) < 2: return False
    dest = ops[-1]
    base = posixpath.basename(posixpath.normpath(dest))
    return not (named(dest) or base in (".claude", ".", "..") or dest.endswith("/"))

def reader(name, args):
    if name in READ: return True
    if name == "find": return not any(a in FIND_WRITE for a in args)
    if name == "git": return git_read(args)
    if name == "sed": return sed_read(args)
    if name == "cp": return cp_read(args)
    return False

def old_reader(name, args):
    if name == "git": return git_read(args)
    if name == "find": return not any(a in FIND_WRITE for a in args)
    return name in OLD_READ

def heredoc_ok(name, args):
    # Beside a data heredoc that names the marker, only commands that cannot run what it
    # wrote may run (R-1): the old readers, the safe heredoc consumers and mkdir.
    return old_reader(name, args) or name in SAFE_CONSUMERS or name == "mkdir"

def run(s, depth, st):
    if depth > 4: raise ValueError("nesting deeper than the guard follows")   # round 4
    s, bodies = heredoc_split(s)
    s = re.sub(r"\\\r?\n", " ", s)
    if any(named(x) for x in procsubs(s)): st["strict"] = True   # <(echo marker)
    s, inner = subst(s)
    for x in inner:
        if named(x): st["strict"] = True
        run(x, depth + 1, st)
    segs = segments(s)
    for words, reds, _, hs in segs: st["tokens"] += words + reds + hs
    st["tokens"] += ins; ins.clear()
    bodies = bodies + herestrings(segs)        # here-strings route like heredocs (round 2)
    for words, reds, sep, _ in segs:
        for r in reds:
            if named(r): raise Block("a redirection into the test marker")
        cmd, xa, assigns, split, _ = wrappers(words)
        if any(named(a) for a in assigns): st["strict"] = True
        if split is not None:
            st["names"].append(("env", []))
            run(split, depth + 1, st); continue
        if not cmd:
            if any(named(w) for w in words) and not any(named(a) for a in assigns):
                raise Block("a command naming the test marker that the guard cannot classify")
            continue
        name, args = os.path.basename(cmd[0]), cmd[1:]
        st["names"].append((name, args))
        if name == "eval":
            run(" ".join(args), depth + 1, st); continue
        if name in SHELLS:
            for j, a in enumerate(args):
                if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a) and j + 1 < len(args):
                    run(args[j + 1], depth + 1, st); break
            else:
                if any(named(w) for w in cmd): raise Block(name + " in a command that names the test marker")
            continue
        if name == "find":
            # find -name .last-* with -exec touch, piped to xargs, or listed into a file
            # that another segment reads (round 2).
            pats = [(args[j + 1], a) for j, a in enumerate(args[:-1]) if a in ("-name", "-iname", "-path", "-ipath", "-wholename")]
            for pat, a in pats:
                tgt = ".last-test-run" if a in ("-name", "-iname") else "./.claude/.last-test-run"
                if a in ("-iname", "-ipath") and fnmatch.fnmatch(tgt.lower(), pat.lower()) or fnmatch.fnmatchcase(tgt, pat) \
                        or fnmatch.fnmatchcase(tgt[2:], pat):
                    if sep in ("|", "|&") or any(x in FIND_WRITE for x in args):
                        raise Block("find whose pattern " + pat + " matches the test marker, with a write action or piped onward")
                    st["written"] += reds
        if not any(named(w) for w in cmd): continue
        # The marker name handed on (round 2): a naming segment piped onward, or writing a
        # file that another segment of the command reads (see the end of the pass).
        if sep in ("|", "|&"): st["strict"] = True
        st["written"] += reds
        if name in DATA:
            if sep in ("|", "|&") or name not in ("echo", "printf"): st["strict"] = True
            continue
        if xa and name not in READ: raise Block("xargs " + name + " in a command that names the test marker")
        if not reader(name, args): raise Block(name + " in a command that names the test marker")
    for route, body in bodies:
        # Round 4: every heredoc body and here-string word is checked as commands. A
        # heredoc that names the marker or the task allows beside it only commands that
        # cannot run what it wrote (heredoc_ok); a here-string keeps its receiver check.
        run(body, depth + 1, st)
        if not named(body): continue
        if route is None:
            st["hstrict"] = True; continue
        name, xa, _ = route_names(route)[0]
        if name in FEED_SHELLS: continue
        if name in SAFE_CONSUMERS and not xa:
            st["hstrict"] = True; continue
        raise Block((name or "a command") + " reading a heredoc that names the test marker")

failed = False
try:
    vs = variants(c)
    for k, v in enumerate(vs):
        st = {"strict": False, "hstrict": False, "names": [], "tokens": [], "written": []}
        try:
            run(v, 0, st)
        except ValueError:
            failed = failed or k == len(vs) - 1; continue
        if any(st["tokens"].count(f) > 1 for f in st["written"]):
            st["strict"] = True          # echo marker > f; xargs touch < f
        if st["strict"]:
            for name, args in st["names"]:
                if not old_reader(name, args):
                    raise Block(name + " in a command that hands on the test marker name")
        if st["hstrict"]:
            for name, args in st["names"]:
                if not heredoc_ok(name, args):
                    raise Block(name + " beside a heredoc that names the test marker (a heredoc piped or written onward can run as commands)")
except Block as b:
    print("BLOCK " + str(b)); sys.exit()
print("UNPARSED" if failed else "OK")
' 2>/dev/null || true)
case "$marker" in
  OK) ;;
  "BLOCK "*) block "${marker#BLOCK } (.claude/.last-test-run is written only by sbt check)" ;;
  *) [[ "$cmd" == *last-test-run* || "$cmd" == *markTestRun* || "$norm" == *markTestRun* ]] \
       && block "a command naming the test marker that the guard could not parse"
     block "a command whose test-marker check could not finish (${marker:-no verdict}); it fails closed" ;;
esac
# end of marker section

# Secret-file reads through the shell: .env and .envrc files, *.tfvars, secrets/, keys,
# SSH, AWS SSO and CLI caches, credentials, harness and gh tokens, state.
# The text scanned is the command with grep and rg search patterns removed, because a
# pattern such as grep '.env' docs/x.md names no file; every file operand, -f pattern
# file, redirection and other word stays.
# Parse failures fail closed (Phase 11 C01): the command is read as written and with its
# unquoted comments removed; if either reading cannot be parsed, a loose whitespace split
# of the raw text is scanned as well, with every glob character taken as real; and if the
# scan itself fails, the raw command is scanned and any glob or brace character blocks.
# A file operand that is a secret is a read, whatever the flags (human, repair round 3):
# -f, --file and -e, --regexp (and the GNU abbreviations --fil, --reg, --rege, --regex)
# supply the patterns, so then no file operand is taken for a pattern.
# Glob and brace words (repair round 3): an unquoted word with * ? [ ] or {a,b} is
# matched against the secret names. A glob component aimed at a secret name (it can match
# the name with at least two of its literal characters on the name's distinctive part,
# and its * and ? stay inside one dot-separated piece: .e* .en? cred* .a* prod.tfv*) is
# blocked whether or not the file exists. Any other glob (*, *.json, x.*) is expanded
# against the file system from the payload cwd (bounded listing; HOME for ~), and a real
# secret match is blocked. {a,b} words are expanded and scanned. Quoted glob characters
# are literal. Every heredoc body is lexed and scanned as commands (round 4). The glob
# analysis has 3 seconds and bounded listings; a scan that runs out of time or budget,
# cannot lex the command or would truncate a candidate list is a block (round 4), never a
# partial allow.
# Nested globs (Phase 11 C02): the same analysis runs on the string of sh -c, bash -c and
# eval, recursively, and on the -name, -iname, -path and -ipath patterns of a find that
# has -exec, -execdir, -ok or -okdir or pipes its names onward: aimed matching, plus a
# bounded walk of the start directories (.git, target and node_modules skipped).
# /proc/<pid>/environ is a secret file (B-18): it holds a process's whole environment.
# Exemption (human decision, repair round 2): the value of -var-file=<name>.tfvars or
# -var-file <name>.tfvars given to terraform plan, validate or console, or to
# scripts/plan.sh, is terraform input, not a shell read. Only that value is rewritten so
# the tfvars rule skips it (every other secret rule still sees it); any other tfvars word
# in the command, a tfvars read in another segment or inside $(...), and -var-file on any
# other subcommand (apply, destroy, import) are still blocked.
pcwd=$(printf '%s' "$payload" | python3 -c '
import json, sys
print(json.load(sys.stdin).get("cwd") or "")
' 2>/dev/null || true)
scan_out=$(printf '%s' "$cmd" | python3 -c "$PYLIB"'
import fnmatch, functools, itertools, os, posixpath, re, shlex, sys, time
raw = sys.stdin.read()
raw0 = re.sub(r"\\\r?\n", " ", raw)
cwd = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else "."
home = os.environ.get("HOME") or "/nonexistent"
# Quoted or escaped glob and brace characters become sentinels, so a word still holding
# a real * ? [ { , } after shlex had it unquoted; the sentinels are restored for output.
Q = {"*": "\x01", "?": "\x02", "[": "\x03", "{": "\x04", "}": "\x05", ",": "\x06"}
UNQ = {v: k for k, v in Q.items()}
def protect(s):
    out, i, q = [], 0, ""
    while i < len(s):
        ch = s[i]
        if q == "\x27":
            if ch == "\x27": q = ""
            out.append(Q.get(ch, ch)); i += 1; continue
        if ch == "\\" and i + 1 < len(s):
            out.append(ch + Q.get(s[i + 1], s[i + 1])); i += 2; continue
        if q == "\"":
            if ch == "\"": q = ""
            out.append(Q.get(ch, ch)); i += 1; continue
        if ch in "\x27\"": q = ch
        out.append(ch); i += 1
    return "".join(out)
def restore(s):
    return "".join(UNQ.get(c, c) for c in s)
syntax = re.compile(r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 | (?P<op>[;&|()<>`\n]+)""",
                    re.S | re.X)
def lex(text):
    p, toks, start = protect(text), [], 0
    for m in syntax.finditer(p):
        if m.lastgroup == "op":
            toks += [(False, w) for w in shlex.split(p[start:m.start()])]
            toks.append((True, m.group())); start = m.end()
    toks += [(False, w) for w in shlex.split(p[start:])]
    return p, toks
def stop(why):
    # Round 4: the scan cannot finish with certainty, so the command is blocked.
    sys.stdout.write("UNPARSED " + why); sys.exit(0)
# Literal text that every secret_hit pattern (bash, below) needs: a candidate without any
# of these cannot match, so it is not handed on (this keeps long find candidate lists
# short without truncating them).
SECRET_BITS = (".env", ".pem", ".key", ".p12", ".pfx", ".tfvars", "id_", "secrets/", ".ssh",
               ".aws/", "credentials.json", "gh/hosts", ".codex/auth.json", ".netrc",
               ".tfstate", "environ")
def record(cands, word):
    cands = [x for x in cands if any(b in x for b in SECRET_BITS)]
    if len(cands) > 2000: stop("more secret-like candidates than the scan hands on")
    if cands: records.append(" ".join(cands) + "\x1f" + word)
def split_bodies(text):
    # Round 4: every heredoc body is commands, inline after its line; a scanner doubt is a
    # block.
    try: kept, _ = heredoc_split(text, True)
    except ValueError as e: stop(str(e))
    return kept, []
kept0, BODYW = split_bodies(raw)      # split before joining continuation lines (S-1)
kept0 = re.sub(r"\\\r?\n", " ", kept0)
VARIANTS, failed = [], False
vs = variants(kept0)
for k, v in enumerate(vs):
    try: VARIANTS.append(lex(v))
    except ValueError: failed = failed or k == len(vs) - 1
if failed or not VARIANTS: stop("the secret-file scan cannot lex the command")
GREP = {"grep", "egrep", "fgrep", "zgrep"}
RG = {"rg"}
# Options whose value is a separate word (a wrong entry here could hide a file operand,
# so only options known to take a value are listed). e and --regexp carry a pattern;
# f and --file name a pattern file, which stays scanned.
GREP_SHORT, GREP_LONG = set("efmABCdD"), {"--regexp", "--file", "--max-count",
    "--after-context", "--before-context", "--context", "--directories", "--devices",
    "--label", "--include", "--exclude", "--exclude-from", "--exclude-dir",
    "--group-separator", "--binary-files"}
RG_SHORT, RG_LONG = set("efgtTmABCjMrEd"), {"--regexp", "--file", "--glob", "--iglob",
    "--type", "--type-not", "--max-count", "--after-context", "--before-context",
    "--context", "--threads", "--max-columns", "--replace", "--encoding", "--max-depth",
    "--max-filesize", "--path-separator", "--sort", "--sortr", "--type-add",
    "--type-clear", "--pre", "--pre-glob", "--colors", "--color"}
WRAP = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout",
        "stdbuf", "xargs", "sudo", "doas", "!", "then", "do", "else", "if", "while",
        "until", "{"}
TFVARS = r"([A-Za-z0-9_./-]*[A-Za-z0-9_-])\.tfvars"
def varfile(seg, i):
    # Rewrite each plain -var-file tfvars value from index i on; nothing else changes.
    repl = {}
    while i < len(seg):
        op, w = seg[i]
        if op:                                   # skip a redirection and its target
            i += 2 if ("<" in w or ">" in w) else 1; continue
        m = re.fullmatch(r"(--?var-file=)" + TFVARS, w)
        if m:
            repl[i] = m.group(1) + m.group(2) + ".var-file-arg"
        elif w in ("-var-file", "--var-file") and i + 1 < len(seg) and not seg[i + 1][0]:
            m = re.fullmatch(TFVARS, seg[i + 1][1])
            if m:
                repl[i + 1] = m.group(1) + ".var-file-arg"; i += 1
        i += 1
    return repl
def plan_sh(w):
    return w == "scripts/plan.sh" or w.endswith("/scripts/plan.sh")
def scrub(seg):
    words = [i for i, (op, _) in enumerate(seg) if not op]
    k = 0
    while k < len(words) and (seg[words[k]][1] in WRAP or
            re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*|-\S*|\d+[smhd]?", seg[words[k]][1])):
        k += 1
    if k >= len(words): return set(), {}
    name = os.path.basename(seg[words[k]][1])
    if name == "terraform":
        j = k + 1
        while j < len(words) and seg[words[j]][1].startswith("-"): j += 1
        if j < len(words) and seg[words[j]][1] in ("plan", "validate", "console"):
            return set(), varfile(seg, words[j] + 1)
        return set(), {}
    if plan_sh(seg[words[k]][1]):
        return set(), varfile(seg, words[k] + 1)
    if name in SHELLS:
        j = k + 1
        while j < len(words) and seg[words[j]][1].startswith("-"): j += 1
        if j < len(words) and plan_sh(seg[words[j]][1]):
            return set(), varfile(seg, words[j] + 1)
        return set(), {}
    if name in GREP: short, long_ = GREP_SHORT, GREP_LONG
    elif name in RG: short, long_ = RG_SHORT, RG_LONG
    else: return set(), {}
    drop, have_e, pos, i, ddash = set(), False, [], words[k] + 1, False
    while i < len(seg):
        op, w = seg[i]
        if op:                                   # a redirection and its target stay
            i += 2 if ("<" in w or ">" in w) else 1; continue
        if not ddash and w == "--": ddash = True
        elif not ddash and w.startswith("--"):
            name_ = w.split("=")[0]
            if len(name_) >= 5 and "--regexp".startswith(name_):
                have_e = True
                if "=" in w: drop.add(i)
                elif i + 1 < len(seg): drop.add(i + 1); i += 1
            elif len(name_) >= 5 and "--file".startswith(name_):
                have_e = True                    # the pattern file itself stays scanned
                if "=" not in w: i += 1
            elif "=" not in w and name_ in long_: i += 1
        elif not ddash and w.startswith("-") and len(w) > 1:
            for j, ch in enumerate(w[1:]):
                if ch in short:
                    attached = j < len(w) - 2
                    if ch in "ef": have_e = True
                    if ch == "e" and not attached and i + 1 < len(seg): drop.add(i + 1)
                    if not attached: i += 1
                    break
        else:
            pos.append(i)
        i += 1
    if not have_e and pos: drop.add(pos[0])
    return drop, {}
out, GLOBW = [], []
def process(toks, depth, top, kw):
    # Split into segments; keep the scrubbed words (top level only) for the secret scan,
    # collect glob and brace words, and look inside sh -c, eval and find patterns.
    segs, seg = [], []
    for op, w in toks:
        if op and set(w) & set(";&|()`\n") and not set(w) & set("<>"):
            segs.append((seg, w)); seg = []
        else:
            seg.append((op, w))
    segs.append((seg, ""))
    for sg, sep in segs:
        d, repl = scrub(sg)
        kept = [repl.get(j, w) for j, (_, w) in enumerate(sg) if j not in d]
        if top: out.extend(kept + [";"])
        for w in kept:
            if (GLOB.search(w) or re.search(r"\{[^{}]*,[^{}]*\}", w)) and (kw is None or w in kw):
                GLOBW.append(w)
        nested(sg, sep, depth)

# Glob and brace words. SHAPES: a secret name pattern, the representative name that the
# scan regexes below recognize, and the distinctive text that an aimed glob shares.
SHAPES = [("*.env", ".env", ".env"), ("*.env.*", ".env.x", ".env"),
    ("*.envrc", ".envrc", ".envrc"), ("*.pem", "x.pem", ".pem"), ("*.key", "x.key", ".key"),
    ("*.p12", "x.p12", ".p12"), ("*.pfx", "x.pfx", ".pfx"), ("*.tfvars", "x.tfvars", ".tfvars"),
    ("*.tfvars.json", "x.tfvars.json", ".tfvars"), ("*.tfstate", "x.tfstate", ".tfstate"),
    ("*.tfstate.*", "x.tfstate.x", ".tfstate"), ("id_rsa", "id_rsa", "id_rsa"),
    ("id_ed25519", "id_ed25519", "id_ed25519"), ("id_ecdsa", "id_ecdsa", "id_ecdsa"),
    ("credentials.json", "credentials.json", "credentials"),
    (".credentials.json", ".credentials.json", "credentials"), (".netrc", ".netrc", ".netrc"),
    ("secrets", "secrets", "secrets"), (".ssh", ".ssh", ".ssh"), (".aws", ".aws", ".aws"),
    ("credentials", "credentials", "credentials"), ("sso", "sso", "sso"), ("cli", "cli", "cli"),
    ("cache", "cache", "cache"), ("gh", "gh", "gh"), ("hosts.yml", "hosts.yml", "hosts"),
    ("hosts.yaml", "hosts.yaml", "hosts"), (".codex", ".codex", ".codex"),
    ("auth.json", "auth.json", "auth")]
ALPHA = frozenset(chr(x) for x in range(32, 127))
GLOB = re.compile(r"[*?\[]")
def gparse(p, nodot=False):
    # Elements: ("*", chars) for a star, ("s", chars) for one character. With nodot, * and
    # ? never produce a dot, so an aimed glob reaches a name only inside one dot piece.
    el, i, any_ = [], 0, (ALPHA - {"."}) if nodot else ALPHA
    while i < len(p):
        ch = p[i]
        if ch == "*":
            if not el or el[-1] != ("*", any_): el.append(("*", any_))
        elif ch == "?": el.append(("s", any_))
        elif ch == "[" and p.find("]", i + 2) > 0:
            j = p.find("]", i + 2)
            body = p[i + 1:j]; neg = body[:1] in ("!", "^")
            body = body[1:] if neg else body
            chars, k = set(), 0
            while k < len(body):
                if k + 2 < len(body) and body[k + 1] == "-":
                    chars |= {chr(x) for x in range(ord(body[k]), ord(body[k + 2]) + 1)}; k += 3
                else:
                    chars.add(UNQ.get(body[k], body[k])); k += 1
            el.append(("s", frozenset(ALPHA - chars) if neg else frozenset(chars))); i = j
        else: el.append(("s", frozenset([UNQ.get(ch, ch)])))
        i += 1
    return tuple(el)
def meet(a, b, marks):
    # True when some name matches both glob element lists a (the command word) and b (a
    # secret shape) with at least two literal characters of a placed on the distinctive
    # part of b (marks), so the glob is aimed at that secret name.
    lit = [x[0] == "s" and len(x[1]) < 10 for x in a]
    @functools.lru_cache(None)
    def go(i, j, c):
        if i == len(a) and j == len(b): return c >= 2
        # A star matches nothing, or takes one character the other side produces; a
        # character both stars would produce can always be left out.
        if i < len(a) and a[i][0] == "*":
            if go(i + 1, j, c): return True
            return j < len(b) and (b[j][0] == "*" or bool(a[i][1] & b[j][1])) and go(i, j + 1, c)
        if j < len(b) and b[j][0] == "*":
            if go(i, j + 1, c): return True
            return i < len(a) and bool(a[i][1] & b[j][1]) and go(i + 1, j, c)
        if i == len(a) or j == len(b): return False
        return bool(a[i][1] & b[j][1]) and go(i + 1, j + 1, min(2, c + (lit[i] and marks[j])))
    return go(0, 0, 0)
def shape(pat, dist, dot):
    # The shell never lets * ? or [ match a leading dot, so for a glob that does not start
    # with a literal dot a dot name is out of reach and a leading * becomes [!.]*.
    el = gparse(pat)
    k = pat.find(dist)
    marks = tuple(k <= x < k + len(dist) for x in range(len(el)))
    if dot: return el, marks
    if el[0] == ("s", frozenset(["."])): return None
    if el[0][0] == "*": return (("s", ALPHA - {"."}),) + el, (False,) + marks
    return el, marks
@functools.lru_cache(None)
def names(comp):
    if not GLOB.search(comp) or len(comp) > 200: return [restore(comp)]
    g = gparse(comp, nodot=True)
    dot = g[0] == ("s", frozenset(["."]))
    hit = []
    for pat, rep, dist in SHAPES:
        sh = shape(pat, dist, dot)
        if sh and meet(g, sh[0], sh[1]) and rep not in hit: hit.append(rep)
    return hit or [restore(comp)]
def braces(w, n=0):
    m = re.search(r"\{([^{}]*,[^{}]*)\}", w)
    if not m or n > 6: return [w]
    res = []
    for alt in m.group(1).split(","):
        res += braces(w[:m.start()] + alt + w[m.end():], n + 1)
    if len(res) > 64: stop("more brace alternatives than the scan follows")
    return res
def fsglob(w):
    # Expand a glob against the file system like the shell, with a bounded listing.
    if w == "~" or w.startswith("~/"): w = home + w[1:]
    w = re.sub(r"^\$\{?HOME\}?(?=/|$)", home, w)
    if not GLOB.search(w): return []
    absl, cur, budget = w.startswith("/"), [""], 5000
    for comp in [c for c in w.split("/") if c]:
        nxt = []
        for d in cur:
            if not GLOB.search(comp):
                nxt.append(posixpath.join(d, restore(comp))); continue
            base = ("/" + d if absl else posixpath.join(cwd, d)) if d else ("/" if absl else cwd)
            if base not in LISTED:
                try: LISTED[base] = sorted(os.listdir(base))
                except OSError: LISTED[base] = []
            entries = LISTED[base]
            pat = "".join("[" + UNQ[c] + "]" if c in "\x01\x02\x03" else UNQ.get(c, c) for c in comp)
            for n in entries:
                budget -= 1
                if budget < 0: stop("a glob over more names than the scan lists")
                if n.startswith(".") and not comp.startswith("."): continue
                if fnmatch.fnmatchcase(n, pat): nxt.append(posixpath.join(d, n))
        if len(nxt) > 2000: stop("a glob with more matches than the scan follows")
        cur = nxt
    return [("/" + x if absl else x) for x in cur]
def shell_words(s):
    # The words the shell sees. s is already split_bodies output: data heredoc bodies are
    # removed and bodies fed to a shell are inline, so it is not split again (a second
    # split dropped every line after a data heredoc; Phase 11 round 2).
    words, st = set(), 0
    for m in syntax.finditer(s):
        if m.lastgroup == "op":
            words.update(shlex.split(s[st:m.start()])); st = m.end()
    words.update(shlex.split(s[st:]))
    return words
LISTED, records, deadline = {}, [], time.time() + 3
PRUNE = {".git", "target", "node_modules", ".bloop", ".metals", ".bsp"}
FIND_PAT = {"-name": "n", "-iname": "ni", "-path": "p", "-ipath": "pi", "-wholename": "p",
            "-iwholename": "pi"}
def walk(start):
    base = re.sub(r"^\$\{?HOME\}?(?=/|$)", home, home + start[1:] if start == "~" or start.startswith("~/") else start)
    top = base if base.startswith("/") else posixpath.join(cwd, base)
    budget = 50000
    for dp, dn, fn in os.walk(top):
        dn[:] = [x for x in dn if x not in PRUNE]
        for n in dn + fn:
            budget -= 1
            if budget < 0 or time.time() > deadline:
                stop("a find walk larger or slower than the scan allows")
            yield posixpath.join(base + dp[len(top):], n)
def find_globs(args, sep):
    i = 0
    while i < len(args) and not args[i].startswith(("-", "(", "!", ")")): i += 1
    starts, expr = args[:i] or ["."], args[i:]
    if not (sep in ("|", "|&") or any(a in ("-exec", "-execdir", "-ok", "-okdir") for a in expr)):
        return
    for j, a in enumerate(expr):
        kind = FIND_PAT.get(a)
        if not kind or j + 1 >= len(expr): continue
        pat, ci = expr[j + 1], "i" in kind
        cands = []
        for s0 in starts:
            if kind[0] == "n":
                cands += [posixpath.join(s0, h) for h in names(pat.lower() if ci else pat)]
            else:
                combos = list(itertools.islice(itertools.product(*[names(x) for x in pat.split("/")]), 257))
                if len(combos) > 256: stop("a find pattern with more readings than the scan follows")
                cands += ["/".join(x) for x in combos]
            for path in walk(s0):
                target = posixpath.basename(path) if kind[0] == "n" else path
                if fnmatch.fnmatchcase(target.lower() if ci else target, pat.lower() if ci else pat):
                    cands.append(path)
        record(cands, pat)
def inner(text, depth):
    if depth > 4: stop("nesting deeper than the scan follows")
    try: p, tk = lex(text)
    except ValueError: stop("a nested command string the scan cannot lex")
    process(tk, depth, False, None)
def nested(sg, sep, depth):
    cmd = wrappers([w for op, w in sg if not op])[0]
    if not cmd: return
    name, args = os.path.basename(restore(cmd[0])), cmd[1:]
    if name in SHELLS:
        for j, a in enumerate(args):
            if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a) and j + 1 < len(args):
                inner(restore(args[j + 1]), depth + 1); break
    elif name == "eval":
        inner(" ".join(restore(a) for a in args), depth + 1)
    elif name == "find":
        find_globs([restore(a) for a in args], sep)
out.extend(BODYW + [";"])
for p, tk in VARIANTS:
    kw = None
    if p is not None:
        try: kw = shell_words(p)
        except ValueError: kw = None
    process(tk, 0, True, kw)
seen = set()
for w in GLOBW:
    if w in seen: continue
    seen.add(w)
    if time.time() > deadline: stop("the glob analysis ran out of time")
    cands = []
    for e in braces(w):
        if not GLOB.search(e):
            if e != w: cands.append(restore(e))
            continue
        combos = list(itertools.islice(itertools.product(*[names(c) for c in e.split("/")]), 257))
        if len(combos) > 256: stop("a glob with more readings than the scan follows")
        cands += ["/".join(x) for x in combos]
        cands += fsglob(e)
    record(cands, restore(w))
sys.stdout.write("OK\x1c" + restore(" ".join(out)) + "\x1d" + "".join(r + "\x1e" for r in records))
' "${pcwd:-$root}" 2>/dev/null || true)
# Split on the \x1d separator with read (bash pattern operators are slow on long text).
scan="" globs=""
# Round 4: no OK prefix (a crash, a doubt, a lex failure, a budget or the deadline) is a
# block; the raw-text fallback that could allow is gone.
if [[ "$scan_out" != OK$'\x1c'* ]]; then
  why=${scan_out#UNPARSED }
  block "a command whose secret-file scan could not finish (${why:0:120}); it fails closed"
fi
scan_out=${scan_out#OK$'\x1c'}
{ IFS= read -r -d $'\x1d' scan; IFS= read -r -d '' globs; } <<< "$scan_out"
[ -n "${scan//[[:space:];]/}" ] || scan=$cmd
b='[[:space:]/"'"'"'=]'          # characters that may precede a path
e='([[:space:]"'"'"';|&)]|$)'      # characters that may follow a file name
secret_hit() {
  local t=$1
  hit=""
  if [[ "$t" =~ (^|$b)([A-Za-z0-9_.*?-]*\.env(rc)?(\.[A-Za-z0-9_-]+)?)$e ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)([A-Za-z0-9_.*?-]*\.(pem|key|p12|pfx|tfvars|tfvars\.json)|id_rsa|id_ed25519|id_ecdsa)$e ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)(\.env[^[:space:]]*[*?]) ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)(secrets/) ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)(\.ssh)(/|$e) ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (\.aws/credentials|\.aws/sso|\.aws/cli/cache|credentials\.json|gh/hosts\.ya?ml|\.codex/auth\.json|\.netrc|\.tfstate|/proc/[^[:space:]]*/environ) ]]; then hit=${BASH_REMATCH[1]}
  fi
}
secret_hit "$scan"
if [ -n "$hit" ]; then
  echo "Blocked by guard-dangerous hook: the command touches a secret file ('$hit')." \
       "Agents never read secrets; use the documented environment variable names" \
       "instead, or ask a human." >&2
  exit 2
fi
while IFS=$'\x1f' read -r -d $'\x1e' cands orig; do
  secret_hit "$cands"
  if [ -n "$hit" ]; then
    echo "Blocked by guard-dangerous hook: the glob or brace word '$orig' can match a" \
         "secret file ('$hit'). Agents never read secrets; name the file you need," \
         "or ask a human." >&2
    exit 2
  fi
done <<< "$globs"
exit 0
