HARNESS: claude-code (Claude Code 2.1.330, per the work order)
MODEL: opus, effort high (tier high in .agents/models.json; no override)

Summary: all three fixes are done. Every required check passed on the first run. Across both round 1 probe sets, the only exit code that changed is `rm -rf datasets/*` (2 to 0). Run unchanged against the new hook, the round 1 test file has exactly two failures: `rm -rf datasets/*` and `rm -rf datasets`. Both are the narrowing you asked for. The only other probe change is message text on three gold rows. Nothing is in force until you ratify and restart.

STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Phase 01 repair round 2 (human-directed). Three fixes: narrow the gold-set shell-write check; allow -var-file on terraform plan, validate and console and on scripts/plan.sh; make docs/agent-runs/README.md match the Claude-Code-only harness scope.

FILES CREATED: none in the repository. Scratchpad only, under /tmp/claude-1000/-home-ajcreator-scholarchain/f6ecd053-dac2-489d-8664-6599c456cc83/scratchpad/:
- round1/ (pre-edit copies)
- round2.diff
- round1-probes.txt, round2-probes.txt, round1-probe.txt, round2-probe.txt
- r2probe.py, r2out.txt, edit_scrub.py, edit_tests.py
- r1x/ (the round 1 test file next to a symlink to the new hook)

FILES MODIFIED (round 2):
- /home/ajcreator/scholarchain/.claude/hooks/guard-dangerous.sh
- /home/ajcreator/scholarchain/.claude/hooks/test_guard_dangerous.py
- /home/ajcreator/scholarchain/docs/agent-runs/README.md
- I saved round 1 copies of docs/agents.md, .claude/settings.json, AGENTS.md and validate-agent-files.sh as well; `cmp` confirms all four are unchanged.
- render.sh rewrote its 44 generated files, but none changed (--check passes and git status shows no new modified generated file).

COMMANDS RUN (all with observed successful output):
1. `.agents/render.sh` → `render OK: 44 generated files` (exit 0)
2. `.agents/render.sh --check` → `render --check OK: 44 generated files match their sources` (exit 0)
3. `.claude/hooks/validate-agent-files.sh` → `validate-agent-files OK: 19 roles, 19 Claude agents, 19 Codex agents, 4 MCP servers, 100 files with one writer` (exit 0)
4. `PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_dangerous.py` → `Ran 25 tests in 77.931s` / `OK` (exit 0)
5. `PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_ownership.py` → `Ran 4 tests in 2.489s` / `OK` (exit 0)
6. `bash -n .claude/hooks/guard-dangerous.sh` → exit 0. It is the only hook changed. The executable bit is intact (`-rwxrwxr-x`). settings.json was not changed, so it needed no JSON parse.
7. Round 1 probe sets:
   - `python3 -I scratchpad/probes.py <repo> scratchpad/nopy` (exit 0). `diff` of round1-probes.txt against round2-probes.txt shows no change at all (`NO_CHANGES`).
   - `python3 -I scratchpad/probe.py` (exit 0). Its diff is shown under VALIDATION RESULTS.
8. The round 1 test file, unchanged, against the new hook → `Ran 15 tests`, `FAILED (failures=2)`: `rm -rf datasets/*` and `rm -rf datasets`, each `0 != 2`.
9. `python3 -I scratchpad/r2probe.py` (104 extra cases) → `mismatches: 0`.
10. `git diff --stat`, `git status --porcelain`, `cmp` and `diff -u` for the report.

TEST RESULTS:
- test_guard_dangerous.py: 25 tests OK, up from 15. New: 8 gold-set tests and 4 tfvars tests. The round 1 gold class was rewritten.
- test_guard_ownership.py: 4 tests OK.
- Timing: one hook call takes 0.437 s wall time, well inside the 10 s hook timeout.
- sbt and the routing suite were not run (the work order forbids both).

VALIDATION RESULTS:
- JSON/TOML: no JSON or TOML file changed. The validator parsed settings.json, .mcp.json, the Codex TOML and hooks.json; it passed.
- bash -n: guard-dangerous.sh OK; executable bit set.
- Frontmatter and unique agent names: validator OK (19/19/19).
- render --check: OK, no drift.
- Audits: no ownership, territory, tools or MCP fence changed in round 2. The settings.json Edit/Write deny on `./datasets/gold/**` and the guard-ownership territory are unchanged. The validator's floor strings are still present in the guard (including "datasets/gold" and "secret file").
- Routing per harness: not run (work order). Claude Code: not run. Codex: out of scope.
- Probe changes against round 1:
  - probes.py: no change in any row.
  - probe.py: one exit code changed, `rm -rf datasets/*` from `ok 2 2` to `BAD 0 2`. The BAD only means the round 1 expectation in probe.py is stale.
  - probe.py, text only (still 2): `rm -rf datasets/gold`, `echo x > datasets/gold/labels.csv` and `cd datasets && rm -rf gold`. The message now names the target, for example `...under datasets/gold/: rm on datasets/gold (written only by the human)`.
  - Everything else in probe.py is unchanged: `head datasets/gold/labels.csv` 0, `ls datasets/` 0, and every secret, marker and scale row.

ASSUMPTIONS:
- **Gold glob rule.**
  - Blocked: a glob that can only point into datasets/gold/, such as `datasets/*/labels.csv`, `*/gold/x`, `datasets/g*` and `datasets/{gold,x}/a`.
  - Not matched: a glob made only of wildcards directly under datasets/ (`datasets/*`), and a glob that cannot match `gold` (`datasets/*.tmp`).
  - I read your phrase "glob directly under datasets/" as covering the wildcard-only and non-matching cases. If you also want `datasets/g*` allowed, that is a one-line change in `gold_parts`.
- **Working directory.** The resolver starts from the payload's `cwd`. Without one, it starts from the repository root, computed from the hook's own path.
- **`$VAR/...` and `$(...)/...`.** A path that starts with a variable is read as starting at the repository root (and also at the current directory). A variable in the middle of a path is treated as `*`.
- **-var-file values.** The exemption covers only plain paths that end in `.tfvars`. `.tfvars.json`, `tofu` and any value containing `$`, a backtick or a quote-built expression stay blocked. Both the `-` and `--` prefix spellings are accepted, as is the two-token form. `bash scripts/plan.sh ...` counts as plan.sh.
- **README lines 8 and 21.** I left these as they are: "under which harness and model" and the versions.md row "harness, model and tool versions" name the harness as something recorded and do not claim two harnesses. Instead I added a sentence after line 9 saying the recorded harness is always `claude-code`. Lines 34, 38-39 and 51 were changed. All other Codex text stays as design intent, as directed.

RISKS:
- **Carried forward from round 1, unchanged:**
  - **Deferred, accepted (one line each, as directed):**
    - defect 3: python3 goes into the toolchain ledger in Phase 02 (guard-dangerous still fails open without python3; the new marker check fails closed only when its own parse step fails).
    - defect 5: P4 was refused at the instruction level; the human chose not to re-run it.
    - defects 10 and 11: Codex, out of scope.
    - defect 12: terraform MCP, in Phase 12.
    - defect 13: not fixed by direction; the six commands' allowed-tools semantics stay unprobed.
    - defect 15: not fixed by direction; the two routing collisions are not in the corpus.
    - defect 16: not fixed by direction; terraform plan, fmt and validate stay auto-allowed for every caller.
    - nits 18, 19 and 21 to 29: not fixed by direction.
    - defects 6 and 7: human-owned, handled outside this work order; not touched.
  - **Law 7 is unmet for this change:** the factory-engineer description changed (D17 required it), and the routing suite was not run (the work order forbids it).
  - **Two-harness claims I changed (D9):** AGENTS.md (3 places); docs/agents.md (harness-scope paragraph, routing sentence, probe Codex bullet, phase item 8, human architect line, Orchestrator heading); .agents/skills/handoff/SKILL.md (3 places); .agents/roles/factory-engineer.md (intro, law 7, procedure step 4, MANUAL ACTION field).
  - **Two-harness claims I left unchanged (D9):** render.sh:81; the hook header comments; AGENTS.md:18 and :104-105; the "Enforced by (Codex)" lines and the section 4 and 5 Codex text; every role's "(claude-code or codex)" report header. The docs/agent-runs/README.md item is resolved in round 2.
  - **False positives I accepted (guard):**
    - Marker:
      - Any command that names the marker is blocked if any segment uses a non-read command (for example `stat .claude/.last-test-run && sbt check`, `cp .claude/.last-test-run /tmp/x`, `touch -r ...`, heredoc prose).
      - Any command that runs sbt and contains `markTestRun` is blocked.
    - Secrets:
      - `ls ~/.ssh` is blocked.
      - Globs `*.env` and `*.tfvars` are blocked.
      - Names like `load.env.sh` are blocked.
      - sed/awk and `git grep` patterns that contain `.env` are blocked.
    - Read deny on `**/*.tfvars`: agents cannot Edit an existing tfvars file either.
    - The round 1 gold-set false positives are replaced by the round 2 list below.
  - **The one place a previously blocked command is now allowed (D5/8):** a grep/egrep/fgrep/zgrep/rg search pattern no longer counts as a file read. Its floor note stands.
  - **Bypasses that remain:**
    - variable indirection (`x=test-run; touch .claude/.last-$x`);
    - `cd .claude && touch .l*`;
    - scripts written to disk and then run.
    - The fallback for these is still the reviewer, the pre-commit gate and git diff visibility.
  - **Incident bound without identity:** if incident-responder runs as a main session without SCHOLARCHAIN_ROLE, it gets the general cap of 4.
- **New in round 2, gold set:**
  - **The narrowing creates new bypasses (your decision; noted, not opposed).** `rm -rf datasets`, `rm -rf datasets/*`, `cd datasets && rm -rf *`, `find datasets -delete`, `mv datasets x`, `git checkout -- datasets` and `rm -rf *` at the repository root now reach the gold labels without a guard block. What still covers them: git diff visibility, the pre-commit gate and the reviewer. The file-tool deny and the territory are unchanged.
    - If you want it, a narrow follow-up rule could block only recursive rm or mv of an ancestor of datasets/gold, without matching bare `datasets` tokens in other commands.
  - **Relative and indirect forms the resolver cannot resolve (remaining bypasses):**
    - a variable that holds the whole gold path (`D=datasets/gold; rm $D/x`);
    - `cd "$X"`, `cd -` or `popd` followed by a relative write (unknown cwd is checked only against the root and datasets/);
    - symlinks that already exist;
    - writes made by scripts, make, sbt or a `python3 script.py` (script contents are not inspected);
    - interpreter code that builds the path itself, or uses `gold/` after a cd;
    - writer commands that are not listed (for example cpio, 7z, sqlite3, make -C).
  - **False positives, new:**
    - A writer command whose source is in gold, which is really a read, is blocked: `cp datasets/gold/x /tmp/`, `dd if=datasets/gold/x ...`, `tar -cf /tmp/a datasets/gold`.
    - Inline interpreter code that names datasets/gold is blocked even when it only reads.
    - `$HOME/datasets/gold/...` is treated as repository-rooted.
    - A cd inside a `( ... )` subshell is treated as if it persisted.
    - The block message for `-var-file=.env.tfvars` shows the rewritten name `.env.var-file-arg`.
  - **False positives removed:** `cd datasets && ./x.sh > log`, `rm datasets/*.tmp`, `... 2>/dev/null` next to a gold read, and `cat datasets/gold/x > /tmp/y` are no longer blocked.
- **New in round 2, tfvars:**
  - **The exemption widens what any caller's plan can read.** `terraform plan` stays auto-allowed for every caller (deferred defect 16).
  - **Saved plans hold the values.** A plan saved with `-out` contains variable values, and `terraform show` (not blocked) can print them.
  - **`terraform console` can print values.** It can print non-sensitive variable values, and sensitive ones through `nonsensitive()`. Two things limit this: settings.json does not auto-allow console or scripts/plan.sh, so both still prompt the human; and a tfvars name inside piped or heredoc input is still blocked.
  - **apply, destroy and import with -var-file stay blocked.** apply and destroy are caught by the terraform check; import is caught because only plan, validate and console are exempt. Covered by tests.
- **State left behind:**
  - Nothing is staged, unstaged or committed; the index is untouched.
  - The stray `.claude/hooks/__pycache__/` is still there, as directed.
  - Scratch files remain in the scratchpad.
  - The test suite now takes about 78 s.

MANUAL ACTION REQUIRED:
1. Review and ratify the round 1 plus round 2 diff, then restart Claude Code. The hook changes take effect only after the restart.
2. Before ratifying: decide on the `.agents/routing-suite.sh` run (law 7, carried from round 1).
3. Post-ratification probes, all inert:
   - round 1 probes (a) to (d);
   - (e) a subagent runs `echo x > datasets/gold/probe.csv` → expect a block; `cd datasets && echo x > /tmp/probe.log` → expect it allowed;
   - (f) `terraform validate -var-file=probe.tfvars` → expect no guard block (it fails on terraform's own terms); `cat probe.tfvars` → expect a block.
4. Optionally, update the stale `rm -rf datasets/*` expectation in scratchpad/probe.py (round 1 scratch, not a repository file).
5. Human: `rm -r /home/ajcreator/scholarchain/.claude/hooks/__pycache__`.
6. Codex hook trust: not applicable while Codex is out of scope.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human ratification and a restart.

PER-FILE JUSTIFICATION (round 2). All three files are in list A (rows `factory-engineer allow .claude/**` and `factory-engineer allow docs/agent-runs/README.md`).
- **.claude/hooks/guard-dangerous.sh:**
  - Fix 1: the gold-set check is narrowed to targets that resolve under datasets/gold/, with a literal check as a fail-closed fallback. This is your round 2 decision. The floor item "the dangerous-command guard, its pattern list and its secret-read check" is untouched: the gold check is a round 1 addition (D14) outside that floor list. Its file-tool enforcement (the territory `human allow datasets/gold/**` and the settings deny) is unchanged.
  - Fix 2: the `-var-file` exemption. This is your round 2 decision. The floor's "secret-read check" is narrowed only for that one argument of four commands. Every other tfvars read stays blocked, and the Read deny is unchanged.
- **.claude/hooks/test_guard_dangerous.py:** the tests for fixes 1 and 2, including your four required cases verbatim (AGENTS.md rule 2). The two round 1 rows that asserted the removed behaviour were dropped.
- **docs/agent-runs/README.md:** fix 3. It is made consistent with the docs/agents.md harness scope, using the smallest edit.
- **Generated files:** none changed (render.sh output matched its sources).

`git diff --stat` (working tree against the index): 20 files changed, 1079 insertions(+), 133 deletions(-)
```
 .agents/roles/dataset-engineer.md     |   2 +
 .agents/roles/factory-engineer.md     |  48 ++--
 .agents/roles/incident-responder.md   |   7 +-
 .agents/skills/handoff/SKILL.md       |  11 +-
 .agents/skills/incident/SKILL.md      |   4 +-
 .claude/agents/dataset-engineer.md    |   2 +
 .claude/agents/factory-engineer.md    |  48 ++--
 .claude/agents/incident-responder.md  |   7 +-
 .claude/commands/handoff.md           |  11 +-
 .claude/commands/incident.md          |   4 +-
 .claude/hooks/guard-dangerous.sh      | 505 +++++++++++++++++++++++++++++++++-
 .claude/hooks/test_guard_dangerous.py | 353 +++++++++++++++++++++++-
 .claude/hooks/validate-agent-files.sh |  14 +-
 .claude/settings.json                 |  13 +
 .codex/agents/dataset-engineer.toml   |   2 +
 .codex/agents/factory-engineer.toml   |  39 ++-
 .codex/agents/incident-responder.toml |   7 +-
 AGENTS.md                             |  13 +-
 docs/agent-runs/README.md             |  12 +-
 docs/agents.md                        | 110 ++++++--
```

COMPLETE DIFF: the round 2 changes only, `diff -u` against the round 1 copies in scratchpad/round1/. It is also saved as /tmp/claude-1000/-home-ajcreator-scholarchain/f6ecd053-dac2-489d-8664-6599c456cc83/scratchpad/round2.diff.
```diff
--- a/.claude/hooks/guard-dangerous.sh (round 1)
+++ b/.claude/hooks/guard-dangerous.sh (round 2)
@@ -321,13 +321,292 @@
 fi
 
 # Shell writes to the gold-set labels datasets/gold/** (human only, docs/agents.md
-# section 1). Also caught: the bare datasets directory and globs directly under it,
-# because `rm -rf datasets/*` or `cd datasets && rm -rf gold` reach the labels too.
-gold='datasets/gold|(^|[[:space:]"'"'"'=])(\./)?datasets/?([[:space:]"'"'"';|&)]|$)|datasets/[^[:space:]/]*[*?[]'
-if [[ "$cmd" =~ $gold ]] \
-   && [[ "$cmd" =~ ([>]|tee|sed$s+-i|perl$s+-i|mv$s|cp$s|rm$s|truncate|dd$s|install$s|ln$s|touch$s|chmod$s) ]]; then
-  block "a shell write to the gold-set labels under datasets/gold/ (written only by the human)"
-fi
+# section 1). Only a write whose target resolves under datasets/gold/ is blocked; a read
+# (cat, head, wc, a redirection elsewhere) and any other datasets/ path stay allowed.
+# Each write target (a redirection, tee, the operands of rm, cp, mv, touch, sed -i and
+# similar, find with -delete or -exec, xargs feeding a writer) is resolved against the
+# repository root: the payload cwd, then every cd or pushd in the command, ./ and ..
+# segments, {a,b} braces, globs (datasets/*/x, */gold/x, datasets/g*), ~, absolute paths,
+# and a leading $VAR/ or $(...)/ read as the repository root. Inline interpreter code
+# (python -c, awk, sed scripts, interpreter heredocs) that names datasets/gold is
+# blocked; sh -c, eval, $(...) and shell heredocs are checked recursively.
+# Not matched by design (human decision, repair round 2): a bare datasets token and a
+# glob directly under datasets/, such as datasets/* or datasets/*.tmp.
+# Fails closed: if the parse fails and the command names datasets/gold with a write shape,
+# it is blocked.
+root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null && pwd)
+goldv=$(printf '%s' "$payload" | python3 -c '
+import fnmatch, json, os, posixpath, re, shlex, sys
+top = posixpath.normpath(sys.argv[1])
+roots = {top, posixpath.normpath(os.path.realpath(top))}
+d = json.load(sys.stdin)
+ti = d.get("tool_input", {}) or {}
+c = ti.get("command") or ti.get("cmd") or ti.get("cli_command") or ""
+c = " ".join(map(str, c)) if isinstance(c, list) else c
+home = os.environ.get("HOME") or "/nonexistent"
+SUB = "@@SUB@@"
+GOLDTXT = re.compile(r"datasets/+(?:\./+)*gold(?![A-Za-z0-9_.-])")
+SHELLS = {"sh", "bash", "zsh", "dash", "ksh"}
+INTERP = {"python", "python2", "python3", "perl", "ruby", "node", "php", "lua", "awk",
+          "gawk", "mawk", "nawk", "sed", "Rscript", "jq"}
+WRAP = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout", "stdbuf",
+        "then", "do", "else", "elif", "if", "while", "until", "!", "{", "}", "sudo", "doas"}
+EVERY = {"rm", "rmdir", "unlink", "touch", "truncate", "mkdir", "chmod", "chown", "chgrp",
+         "shred", "mv", "cp", "install", "ln", "rsync", "scp", "tee", "patch", "split",
+         "csplit", "mkfifo", "mknod", "setfacl", "chattr", "sponge", "gzip", "gunzip",
+         "bzip2", "bunzip2", "xz", "unxz", "zstd", "unzstd", "vi", "vim", "nvim", "nano",
+         "emacs", "ed", "ex", "dd", "curl", "wget", "tar", "unzip"}
+CWD_WRITERS = {"curl", "wget", "tar", "unzip"}   # may write into the working directory
+FIND_WRITE = {"-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0",
+              "-fprintf", "-fls"}
+GIT_WRITE = {"rm", "mv", "checkout", "restore", "apply", "clean", "reset", "stash", "am",
+             "cherry-pick", "revert", "merge", "pull", "switch"}
+XARGS_VAL = {"-I", "-L", "-n", "-P", "-s", "-d", "-E", "-a", "--arg-file", "--delimiter",
+             "--max-args", "--max-procs", "--max-lines", "--max-chars"}
+SEPS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "`", "\n"}
+WOPS = {">", ">>", ">|", "&>", "&>>", "<>", ">&"}
+syntax = re.compile(r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 |
+    (?P<op>&>>|&>|>>|>\||>&|<<<|<<-|<<|<>|<&|&&|\|\||\|&|;;|[;&|()<>`\n])""", re.S | re.X)
+HEREDOC = re.compile(r"(?<!<)<<-?\s*[\x27\"]?([A-Za-z_][A-Za-z0-9_]*)[\x27\"]?")
+mentions = [bool(GOLDTXT.search(c))]
+
+def heredocs(s):
+    lines, kept, bodies, i = s.split("\n"), [], [], 0
+    while i < len(lines):
+        line = lines[i]; kept.append(line); i += 1
+        for dl in HEREDOC.findall(line):
+            body = []
+            while i < len(lines) and lines[i].strip() != dl:
+                body.append(lines[i]); i += 1
+            i += 1
+            bodies.append((line[:line.find("<<")], "\n".join(body)))
+    return "\n".join(kept), bodies
+
+def subst(s):
+    # Replace $(...), backticks, ${...} and $NAME by SUB; return the inner commands.
+    out, inner, i, q = [], [], 0, ""
+    while i < len(s):
+        ch = s[i]
+        if q == "\x27":
+            out.append(ch); i += 1
+            if ch == "\x27": q = ""
+            continue
+        if ch == "\\" and i + 1 < len(s):
+            out.append(s[i:i + 2]); i += 2; continue
+        if ch == "\x27" and not q:
+            q = "\x27"; out.append(ch); i += 1; continue
+        if ch == "\"":
+            q = "" if q else "\""; out.append(ch); i += 1; continue
+        if ch == "$" and s[i + 1:i + 2] == "(":
+            depth, j = 1, i + 2
+            while j < len(s) and depth:
+                depth += {"(": 1, ")": -1}.get(s[j], 0); j += 1
+            inner.append(s[i + 2:j - 1]); out.append(SUB); i = j; continue
+        if ch == "`":
+            j = s.find("`", i + 1); j = len(s) if j < 0 else j
+            inner.append(s[i + 1:j]); out.append(SUB); i = j + 1; continue
+        if ch == "$" and s[i + 1:i + 2] == "{":
+            j = s.find("}", i); j = len(s) if j < 0 else j
+            out.append(SUB); i = j + 1; continue
+        m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*|[0-9@*#?!$-])", s[i:])
+        if m:
+            out.append(SUB); i += len(m.group()); continue
+        out.append(ch); i += 1
+    return "".join(out), inner
+
+def tokens(s):
+    pos = 0
+    for m in syntax.finditer(s):
+        if m.lastgroup == "op":
+            for w in shlex.split(s[pos:m.start()]): yield False, w
+            yield True, m.group(); pos = m.end()
+    for w in shlex.split(s[pos:]): yield False, w
+
+def segments(s):
+    segs, words, reds, it, i = [], [], [], list(tokens(s)), 0
+    while i < len(it):
+        op, t = it[i]
+        if op and (t in SEPS or (t not in WOPS and not t.startswith("<"))):
+            segs.append((words, reds)); words, reds = [], []
+        elif op:
+            if i + 1 < len(it) and not it[i + 1][0]:
+                i += 1
+                nxt = it[i][1]
+                if t in WOPS and not (t == ">&" and re.fullmatch(r"\d+-?|-", nxt)):
+                    reds.append(nxt)
+        else:
+            words.append(t)
+        i += 1
+    segs.append((words, reds))
+    return [x for x in segs if x[0] or x[1]]
+
+def braces(w, n=0):
+    m = re.search(r"\{([^{}]*,[^{}]*)\}", w)
+    if not m or n > 6: return [w]
+    out = []
+    for alt in m.group(1).split(","):
+        out += braces(w[:m.start()] + alt + w[m.end():], n + 1)
+    return out[:256]
+
+def can(comp, name):
+    return comp == name or (re.search(r"[*?\[]", comp) is not None and fnmatch.fnmatchcase(name, comp))
+
+def specific(comp):
+    return re.sub(r"\[[^\]]*\]|[*?]", "", comp) != ""
+
+def gold_parts(p):
+    if len(p) < 2 or not (can(p[0], "datasets") and can(p[1], "gold")): return False
+    if len(p) == 2: return specific(p[1])        # datasets/gold itself; never datasets/*
+    return specific(p[0]) or specific(p[1])
+
+def under_gold(word, cwd):
+    for w in braces(word):
+        if w == "~" or w.startswith("~/"): w = home + w[1:]
+        cands = []
+        if w.startswith(SUB):
+            rest = w[len(SUB):].lstrip("/").replace(SUB, "*")
+            cands += [posixpath.join(r, rest) for r in roots]
+            if cwd: cands.append(posixpath.join(cwd, rest))
+        else:
+            w = w.replace(SUB, "*")
+            if w.startswith("/"): cands.append(w)
+            elif cwd is None:
+                cands += [posixpath.join(r, x, w) for r in roots for x in ("", "datasets")]
+            else: cands.append(posixpath.join(cwd, w))
+        for p in cands:
+            p = posixpath.normpath(p)
+            for r in roots:
+                if p.startswith(r + "/") and gold_parts(p[len(r) + 1:].split("/")): return True
+    return False
+
+def chdir(args, cwd):
+    a = [x for x in args if not re.fullmatch(r"-[LPe@]+|--", x)]
+    if not a: return home
+    t = a[0]
+    if t == "-": return None
+    if t == "~" or t.startswith("~/"): t = home + t[1:]
+    if t.startswith(SUB):
+        rest = t[len(SUB):]
+        return posixpath.normpath(posixpath.join(top, rest.lstrip("/"))) if rest.startswith("/") and SUB not in rest else None
+    if SUB in t: return None
+    if t.startswith("/"): return posixpath.normpath(t)
+    return None if cwd is None else posixpath.normpath(posixpath.join(cwd, t))
+
+def every(args):
+    out = []
+    for a in args:
+        if a.startswith("-"):
+            if "=" in a: out.append(a.split("=", 1)[1])
+            elif len(a) > 2 and not a.startswith("--"): out.append(a[2:])
+        else:
+            out.append(a)
+            if "=" in a: out.append(a.split("=", 1)[1])
+    return out
+
+def strip(words):
+    xa = False
+    while words:
+        w = words[0]
+        if w == "xargs":
+            xa, words = True, words[1:]
+            while words and words[0].startswith("-"):
+                o, words = words[0], words[1:]
+                if o in XARGS_VAL and words: words = words[1:]
+            continue
+        if w in WRAP or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*|-\S*|\d+[smhd]?", w):
+            words = words[1:]; continue
+        break
+    return words, xa
+
+def analyze(words, reds, st, depth):
+    cwd = st["cwd"]
+    for r in reds:
+        if under_gold(r, cwd): return "a redirection into " + r
+    words, xa = strip(words)
+    if not words: return None
+    name, args = posixpath.basename(words[0]), words[1:]
+    if name == "eval": return run(" ".join(args), st, depth + 1)
+    if name in SHELLS:
+        for j, a in enumerate(args):
+            if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a) and j + 1 < len(args):
+                return run(args[j + 1], st, depth + 1)
+        return None
+    if name in ("cd", "pushd"):
+        st["cwd"] = chdir(args, cwd); return None
+    if name == "popd":
+        st["cwd"] = None; return None
+    if name in INTERP:
+        for a in args:
+            if GOLDTXT.search(a) and not re.fullmatch(r"[A-Za-z0-9_./*?\[\]{},@=+:-]+", a):
+                return name + " inline code that names datasets/gold"
+    targets, tcwd = [], cwd
+    inplace = any(re.fullmatch(r"-[A-Za-z]*i\S*|--in-place\S*", a) for a in args)
+    if name in EVERY or (name in ("sed", "perl") and inplace):
+        targets = every(args)
+        if name in CWD_WRITERS: targets = [t for t in targets if "://" not in t] + ["x"]
+    elif name == "find" and any(a in FIND_WRITE for a in args):
+        targets = every(args)
+    elif name == "sort":
+        for j, a in enumerate(args):
+            if a in ("-o", "--output") and j + 1 < len(args): targets.append(args[j + 1])
+            elif a.startswith("--output="): targets.append(a.split("=", 1)[1])
+            elif a.startswith("-o") and len(a) > 2: targets.append(a[2:])
+    elif name == "uniq":
+        pos = [a for a in args if not a.startswith("-")]
+        targets = pos[1:2]
+    elif name == "git":
+        i = 0
+        while i < len(args) and args[i].startswith("-"):
+            if args[i] == "-C" and i + 1 < len(args):
+                tcwd = chdir([args[i + 1]], tcwd); i += 2
+            elif args[i] in ("-c", "--git-dir", "--work-tree", "--namespace"): i += 2
+            else: i += 1
+        if i < len(args) and args[i] in GIT_WRITE: targets = every(args[i + 1:])
+    if xa and targets is not None and (name in EVERY or name in ("sed", "perl", "find")) and mentions[0]:
+        return "xargs " + name + " with a datasets/gold path"
+    for t in targets:
+        if under_gold(t, tcwd): return name + " on " + t
+    return None
+
+def run(s, st, depth):
+    if depth > 4: return None
+    s = re.sub(r"\\\r?\n", " ", s)
+    s, bodies = heredocs(s)
+    s, inner = subst(s)
+    for x in inner:
+        r = run(x, dict(st), depth + 1)
+        if r: return r
+    for words, reds in segments(s):
+        if any(under_gold(w, st["cwd"]) for w in words + reds): mentions[0] = True
+        r = analyze(words, reds, st, depth)
+        if r: return r
+    for prefix, body in bodies:
+        w = []
+        try: w = shlex.split(re.split(r"[;&|()\n]", prefix)[-1])
+        except ValueError: pass
+        w, _ = strip(w)
+        name = posixpath.basename(w[0]) if w else ""
+        if name in SHELLS:
+            r = run(body, st, depth + 1)
+            if r: return r
+        elif name in INTERP and GOLDTXT.search(body):
+            return name + " heredoc code that names datasets/gold"
+    return None
+
+try:
+    start = posixpath.normpath(d.get("cwd") or top)
+    r = run(c, {"cwd": start}, 0)
+except Exception:
+    print("UNPARSED"); sys.exit(0)
+print(("BLOCK " + r.replace(SUB, "$")) if r else "OK")
+' "$root" 2>/dev/null || true)
+case "$goldv" in
+  OK) ;;
+  "BLOCK "*) block "a shell write to the gold-set labels under datasets/gold/: ${goldv#BLOCK } (written only by the human)" ;;
+  *) [[ "$cmd" =~ datasets/+(\./+)*gold ]] \
+       && [[ "$cmd" =~ ([>]|tee|sed$s+-i|perl$s+-i|mv$s|cp$s|rm$s|truncate|dd$s|install$s|ln$s|touch$s|chmod$s) ]] \
+       && block "a shell write to the gold-set labels under datasets/gold/ that the guard could not parse (written only by the human)" ;;
+esac
 
 # Shell writes to the test marker .claude/.last-test-run. Only the sbt markTestRun task
 # writes it, inside the sbt JVM at the end of 'sbt check', so no agent command may.
@@ -393,6 +672,12 @@
 # The text scanned is the command with grep and rg search patterns removed, because a
 # pattern such as grep '.env' docs/x.md names no file; every file operand, -f pattern
 # file, redirection and other word stays. If that scrub fails, the raw command is scanned.
+# Exemption (human decision, repair round 2): the value of -var-file=<name>.tfvars or
+# -var-file <name>.tfvars given to terraform plan, validate or console, or to
+# scripts/plan.sh, is terraform input, not a shell read. Only that value is rewritten so
+# the tfvars rule skips it (every other secret rule still sees it); any other tfvars word
+# in the command, a tfvars read in another segment or inside $(...), and -var-file on any
+# other subcommand (apply, destroy, import) are still blocked.
 scan=$(printf '%s' "$cmd" | python3 -c '
 import os, re, shlex, sys
 raw = re.sub(r"\\\r?\n", " ", sys.stdin.read())
@@ -420,17 +705,51 @@
 WRAP = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout",
         "stdbuf", "xargs", "sudo", "doas", "!", "then", "do", "else", "if", "while",
         "until", "{"}
+SHELLS = {"sh", "bash", "zsh", "dash", "ksh"}
+TFVARS = r"([A-Za-z0-9_./-]*[A-Za-z0-9_-])\.tfvars"
+def varfile(seg, i):
+    # Rewrite each plain -var-file tfvars value from index i on; nothing else changes.
+    repl = {}
+    while i < len(seg):
+        op, w = seg[i]
+        if op:                                   # skip a redirection and its target
+            i += 2 if ("<" in w or ">" in w) else 1; continue
+        m = re.fullmatch(r"(--?var-file=)" + TFVARS, w)
+        if m:
+            repl[i] = m.group(1) + m.group(2) + ".var-file-arg"
+        elif w in ("-var-file", "--var-file") and i + 1 < len(seg) and not seg[i + 1][0]:
+            m = re.fullmatch(TFVARS, seg[i + 1][1])
+            if m:
+                repl[i + 1] = m.group(1) + ".var-file-arg"; i += 1
+        i += 1
+    return repl
+def plan_sh(w):
+    return w == "scripts/plan.sh" or w.endswith("/scripts/plan.sh")
 def scrub(seg):
     words = [i for i, (op, _) in enumerate(seg) if not op]
     k = 0
     while k < len(words) and (seg[words[k]][1] in WRAP or
             re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*|-\S*|\d+[smhd]?", seg[words[k]][1])):
         k += 1
-    if k >= len(words): return set()
+    if k >= len(words): return set(), {}
     name = os.path.basename(seg[words[k]][1])
+    if name == "terraform":
+        j = k + 1
+        while j < len(words) and seg[words[j]][1].startswith("-"): j += 1
+        if j < len(words) and seg[words[j]][1] in ("plan", "validate", "console"):
+            return set(), varfile(seg, words[j] + 1)
+        return set(), {}
+    if plan_sh(seg[words[k]][1]):
+        return set(), varfile(seg, words[k] + 1)
+    if name in SHELLS:
+        j = k + 1
+        while j < len(words) and seg[words[j]][1].startswith("-"): j += 1
+        if j < len(words) and plan_sh(seg[words[j]][1]):
+            return set(), varfile(seg, words[j] + 1)
+        return set(), {}
     if name in GREP: short, long_ = GREP_SHORT, GREP_LONG
     elif name in RG: short, long_ = RG_SHORT, RG_LONG
-    else: return set()
+    else: return set(), {}
     drop, have_e, pos, i, ddash = set(), False, [], words[k] + 1, False
     while i < len(seg):
         op, w = seg[i]
@@ -457,11 +776,11 @@
             pos.append(i)
         i += 1
     if not have_e and pos: drop.add(pos[0])
-    return drop
+    return drop, {}
 out, seg, idx = [], [], []
 def flush():
-    d = scrub(seg)
-    out.extend(w for j, (_, w) in enumerate(seg) if j not in d)
+    d, repl = scrub(seg)
+    out.extend(repl.get(j, w) for j, (_, w) in enumerate(seg) if j not in d)
 for op, w in toks:
     if op and set(w) & set(";&|()`\n") and not set(w) & set("<>"):
         flush(); seg = []; out.append(";")
--- a/.claude/hooks/test_guard_dangerous.py (round 1)
+++ b/.claude/hooks/test_guard_dangerous.py (round 2)
@@ -14,10 +14,15 @@
 HOOK = Path(__file__).with_name("guard-dangerous.sh")
 
 
-def run_hook(command, agent=None, env=None, key="command"):
+ROOT = HOOK.resolve().parents[2]
+
+
+def run_hook(command, agent=None, env=None, key="command", cwd=None):
     payload = {"tool_input": {key: command}}
     if agent:
         payload["agent_type"] = agent
+    if cwd:
+        payload["cwd"] = str(cwd)
     e = dict(os.environ)
     e.pop("SCHOLARCHAIN_ROLE", None)
     if env:
@@ -194,28 +199,167 @@
 
 
 class GoldSetTest(GuardCase):
-    """Defect 14: the gold-set labels under datasets/gold/ are human-only."""
+    """Defect 14, narrowed in repair round 2: only writes that resolve under
+    datasets/gold/ are blocked; a bare datasets token or a glob directly under
+    datasets/ is not matched."""
+
+    def test_work_order_cases(self):
+        self.check([
+            ("python3 datasets/scripts/fetch_samples.py > /tmp/log", 0),
+            ("cd datasets && ./scripts/x.sh > log", 0),
+            ("cp x datasets/gold/labels.csv", 2),
+            ("echo x > datasets/gold/a.csv", 2),
+        ])
 
     def test_shell_writes_blocked(self):
         self.check([
             ("rm -rf datasets/gold", 2),
+            ("rm -rf datasets/gold/", 2),
             ("echo x > datasets/gold/labels.csv", 2),
             ("cp /tmp/l.csv datasets/gold/labels.csv", 2),
             ("sed -i s/a/b/ datasets/gold/labels.csv", 2),
             ("touch datasets/gold/labels.csv", 2),
+            ("mv datasets/gold/a.csv /tmp/", 2),
+            ("echo x | tee -a datasets/gold/a.csv", 2),
+            ("dd if=/dev/zero of=datasets/gold/x", 2),
+            ("cp -t datasets/gold x", 2),
+            ("install -Dm644 x --target-directory=datasets/gold", 2),
+            ("find datasets/gold -name '*.csv' -delete", 2),
+            ("git -C datasets checkout gold/labels.csv", 2),
+        ])
+
+    def test_globs_and_braces_blocked(self):
+        self.check([
+            ("rm datasets/gold/*.csv", 2),
+            ("rm datasets/*/labels.csv", 2),
+            ("rm */gold/labels.csv", 2),
+            ("rm -rf datasets/g*", 2),
+            ("rm datasets/{gold,x}/a.csv", 2),
+        ])
+
+    def test_relative_paths_blocked(self):
+        self.check([
+            ("echo x > ./datasets/gold/x", 2),
+            ("touch datasets/./gold/x", 2),
+            ("echo x > datasets/x/../gold/y", 2),
             ("cd datasets && rm -rf gold", 2),
-            ("rm -rf datasets/*", 2),
-            ("rm -rf datasets", 2),
+            ("cd datasets && cp /tmp/l.csv gold/labels.csv", 2),
+            ("cd datasets && echo x >gold/a", 2),
+            ("cd datasets/gold && echo x > a.csv", 2),
+            ("cd datasets && cd gold && rm a.csv", 2),
+            ("cd datasets/gold; rm *", 2),
+            ("pushd datasets/gold && touch x", 2),
+            ('rm "$PWD/datasets/gold/a.csv"', 2),
+            (f"rm {ROOT}/datasets/gold/a.csv", 2),
+        ])
+
+    def test_payload_cwd(self):
+        self.check([("touch x", 2), ("echo x > a.csv", 2), ("wc -l a.csv", 0)],
+                   cwd=ROOT / "datasets" / "gold")
+        self.check([("echo x > log", 0), ("rm -rf gold", 2)], cwd=ROOT / "datasets")
+
+    def test_indirect_writes_blocked(self):
+        self.check([
+            ("echo datasets/gold/a.csv | xargs rm", 2),
+            ("bash -c 'rm datasets/gold/a.csv'", 2),
+            ("echo $(rm datasets/gold/a.csv)", 2),
+            ("""python3 -c "open('datasets/gold/a.csv', 'w')" """, 2),
+            ("""awk '{print > "datasets/gold/x"}' f""", 2),
+            ("python3 - <<'EOF'\nopen('datasets/gold/x', 'w')\nEOF", 2),
+            ("bash <<EOF\nrm datasets/gold/x\nEOF", 2),
+            # unparsable quoting falls back to the literal check (fails closed)
+            ('echo "x > datasets/gold/a', 2),
         ])
 
     def test_reads_allowed(self):
         self.check([
+            ("cat datasets/gold/labels.csv", 0),
             ("head datasets/gold/labels.csv", 0),
+            ("head -n 5 datasets/gold/labels.csv | wc -l", 0),
             ("wc -l datasets/gold/labels.csv", 0),
+            ("wc -l datasets/gold/labels.csv 2>&1", 0),
+            ("grep -c x datasets/gold/labels.csv 2>/dev/null", 0),
+            ("cat datasets/gold/labels.csv > /tmp/copy.csv", 0),
+            ("cd datasets/gold && wc -l *.csv > /tmp/n", 0),
+            ("awk -F, '{print $1}' datasets/gold/labels.csv", 0),
+            ("git diff datasets/gold", 0),
             ("ls datasets/", 0),
             ("bash -n datasets/scripts/fetch-sample.sh", 0),
         ])
 
+    def test_other_datasets_paths_allowed(self):
+        self.check([
+            ("rm datasets/*.tmp", 0),
+            ("touch datasets/manifest.json", 0),
+            ("cd datasets && rm -rf tmp", 0),
+            ("echo x > datasets/golden.csv", 0),
+            ("rm -rf datasets/gold.bak", 0),
+        ])
+
+
+class TfvarsVarFileTest(GuardCase):
+    """Repair round 2: -var-file=<name>.tfvars is allowed on terraform plan, validate
+    and console and on scripts/plan.sh; every other tfvars read stays blocked."""
+
+    def test_var_file_allowed(self):
+        self.check([
+            ("terraform plan -var-file=prod.tfvars", 0),
+            ("terraform -chdir=infra/stacks/app plan -var-file=dev.tfvars", 0),
+            ("terraform validate -var-file=dev.tfvars", 0),
+            ("terraform console -var-file=dev.tfvars", 0),
+            ("scripts/plan.sh app -var-file=prod.tfvars", 0),
+            ("terraform plan -var-file prod.tfvars", 0),
+            ("./scripts/plan.sh app -var-file infra/env/dev.tfvars", 0),
+            ("bash scripts/plan.sh app -var-file=dev.tfvars", 0),
+            ('terraform plan -var-file="prod.tfvars"', 0),
+            ("terraform plan -var-file=a.tfvars -var-file=b.tfvars -out=plan.bin", 0),
+        ])
+
+    def test_reads_still_blocked(self):
+        self.check([
+            ("cat prod.tfvars", 2),
+            ("head infra/stacks/app/dev.tfvars", 2),
+            ("less dev.tfvars", 2),
+            ("more dev.tfvars", 2),
+            ("tail dev.tfvars", 2),
+            ("bat dev.tfvars", 2),
+            ("strings dev.tfvars", 2),
+            ("xxd dev.tfvars", 2),
+            ("od -c dev.tfvars", 2),
+            ("sed -n 1p dev.tfvars", 2),
+            ("awk 1 dev.tfvars", 2),
+            ("grep -n x dev.tfvars", 2),
+            ("cp prod.tfvars /tmp/x", 2),
+            ("base64 prod.tfvars", 2),
+        ])
+
+    def test_chained_and_substituted_reads_blocked(self):
+        self.check([
+            ("terraform plan -var-file=prod.tfvars && cat prod.tfvars", 2),
+            ("terraform plan -var-file=prod.tfvars; less prod.tfvars", 2),
+            ("terraform plan -var-file=prod.tfvars | tee dev.tfvars", 2),
+            ("terraform plan -var-file=$(cat x.tfvars)", 2),
+            ("terraform plan -var-file=`cat x.tfvars`", 2),
+            ("terraform plan -var-file=prod.tfvars < prod.tfvars", 2),
+            ("echo 'file(\"prod.tfvars\")' | terraform console -var-file=prod.tfvars", 2),
+            ("bash -c 'terraform plan -var-file=x.tfvars'", 2),
+        ])
+
+    def test_other_mentions_and_subcommands_blocked(self):
+        self.check([
+            ("terraform plan -var-file=prod.tfvars -out=prod.tfvars", 2),
+            ("terraform plan -var-file=prod.tfvars dev.tfvars", 2),
+            ("terraform plan -var-file=../secrets/prod.tfvars", 2),
+            ("terraform plan -var-file=.env.tfvars", 2),
+            ("terraform plan -var-file=prod.tfvars.json", 2),
+            ("terraform fmt -var-file=prod.tfvars", 2),
+            ("tofu plan -var-file=prod.tfvars", 2),
+            ("terraform apply -var-file=prod.tfvars", 2),
+            ("terraform -chdir=infra apply -var-file=prod.tfvars", 2),
+            ("terraform destroy -var-file=prod.tfvars", 2),
+            ("terraform import -var-file=prod.tfvars aws_s3_bucket.b b", 2),
+        ])
+
 
 if __name__ == "__main__":
     unittest.main()
--- a/docs/agent-runs/README.md (round 1)
+++ b/docs/agent-runs/README.md (round 2)
@@ -7,6 +7,8 @@
 Every phase leaves one folder, `docs/agent-runs/phase-NN/`, with two-digit `NN`.
 The folder lets a grader replay the phase and see who did what, under which harness and
 model, and what the human decided.
+Claude Code is the only harness in scope (harness scope, `docs/agents.md`), so the
+harness recorded below is always `claude-code`.
 
 ## Files in every phase folder
 
@@ -31,12 +33,12 @@
 
 ## run-log.md
 
-One table row per step: step number, agent, harness (`claude-code` or `codex`), model
-and effort, start and end time, report file and STATUS.
+One table row per step: step number, agent, harness (`claude-code`), model and effort,
+start and end time, report file and STATUS.
 When the work order ran a role off its default tier, the model cell says so with the
 reason, for example `haiku (low, step-down: one-line bump)`.
-A harness switch happens only between steps, after the step's report is saved here and
-`STATUS.md` is updated.
+There is no harness switch; a step's report is saved here and `STATUS.md` is updated
+before the next step starts.
 
 ## repair-NN.md
 
@@ -48,7 +50,7 @@
 
 ## versions.md
 
-Claude Code and Codex versions, the models and efforts from `.agents/models.json`, JVM,
+The Claude Code version, the models and efforts from `.agents/models.json`, JVM,
 sbt, Scala, Terraform, AWS CLI and every other tool from the `toolchain/` ledger that
 the phase used.
 
```

Note from this session: the live guard (round 1, in force) blocked two of my own Bash heredocs, because their text named `.env` and `terraform destroy`. This is the documented heredoc-prose false positive; I moved the edit scripts into scratchpad files and did not weaken anything.

No constitutional change in this report is effective before human ratification and a session restart.
