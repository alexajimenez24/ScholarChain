HARNESS: claude-code
MODEL: opus (Agent-tool override, Phase 11 high tier per the work order). The effort comes from the agent file, which on disk is now sonnet/medium, so I can't see which effort this run actually got.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 review 4 (fresh, read-only) of guard repair round 3 (S-1 heredoc rule, build-input write block, code-reviewer tier change).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git diff/show/stat; a HEAD copy and the round-2 copy of the guard in the new scratchpad rev4; inert-JSON probe and diff scripts there (probe.py, diff.py, c1-c8.py); bash -c '...; declare -f f' (only defines a function to show how bash parses it, the body is never run); one harmless bash test -v check that runs only echo; .agents/render.sh --check; validate-agent-files.sh; four guard test classes; test_verify_tests_ran.py; test_guard_ownership.py.
TEST RESULTS: HeredocQuotedEndMarkerTest, BuildInputWriteTest, HeredocFedToShellTest, MarkerFalsePositiveTest: 15 OK. test_verify_tests_ran OK. test_guard_ownership OK. render --check OK (44). Validator OK (19/19/19, 4 MCP, 739 files). sbt not run, as the work order said.
VALIDATION RESULTS: REQUEST_CHANGES. Two CRITICAL findings are open (T-1, T-3).
Q1, S-1: the 7 listed S-1 inputs are closed. Each now gives rc 2 (round 2 gave 0, HEAD gives 2). But the rule is NOT closed:
- T-1 CRITICAL (iron law 3; impact MAJOR), regression against HEAD, present in round 2 as well, still prompting (no allow rule matches cat), not a stated residual. Owner: factory-engineer.
  - Location: guard-dangerous.sh:289-355. _hd_scan has no state for `${...}` or `$[...]`, so a `<<` inside them reads as a heredoc. Bash treats it as plain word text there; declare -f shows the next line parsed as a command. _safe_text at :356-369 then drops `x=` as an assignment, so the whole command counts as safe.
  - Failing input, rc 0 now, rc 2 at HEAD: line 1 `cat ${x=|cat <<'EOF' }`, line 2 `touch .claude/.last-test-run`, line 3 `EOF`.
  - Also rc 0: `sbt markTestRun` as line 2 (HEAD 2); `cat $[x<<'EOF' ]` as line 1 (HEAD 2); and, rc 0 at HEAD too, the gold `rm -rf datasets/gold`, polling loops, printenv, cp into project/ and echo into ~/.sbt.
  - Impact: a forged marker silences the stop hook, and the new build block is bypassed.
  - Fix brief: track `${`/`$[` (with nested quotes) in _hd_scan. Treat `<<` inside them as doubt, or not as a heredoc. Add these inputs as tests.
- T-3 CRITICAL (iron law 3; impact MAJOR), regression against HEAD, prompting, and it contradicts the repair-03 residual "cannot run in the same command". Owner: factory-engineer.
  - Location: guard-dangerous.sh:266. SAFE_NEAR includes `test` and `[`. Bash `test -v` expands $(...) in an array subscript; I verified this with a harmless echo (bash 5.2.21).
  - Failing input, rc 0 now, rc 2 at HEAD: `cat > /tmp/f.txt <<'EOF'`, then `a[$(touch .claude/.last-test-run)]`, then `EOF`, then `test -v "$(cat /tmp/f.txt)"`.
  - printf -v is already blocked.
  - Fix brief: drop test and `[` from SAFE_NEAR, or treat -v and arithmetic operands as code. Look for other builtins that evaluate subscripts (read, declare, let) if they are ever added.
Q2, weakening:
- Literal-row differential (578 unique rows from the current and round-2 test files):
  - Against HEAD, only one HEAD-blocked row now passes: `timeout 45m sbt check; grep -c markTestRun build.sbt`, which is a listed C05 case.
  - Against round 2, no round-2-blocked row passes.
  - My extra passes against HEAD (tab or space before EOF without a matching <<-, backticks in a quoted body, a quoted report naming the marker) are harmless body text.
- A crashing pass falls back to the raw regex (fails closed).
- T-2 MAJOR, regression against HEAD and worse than round 2, prompting, not a stated residual. Owner: factory-engineer.
  - Location: _hd_assemble :370-398. It joins the context and runs heredoc_route over the whole prefix for every heredoc, so cost is quadratic.
  - Failing input: 1000 copies of `cat > /tmp/r.md <<'EOF'`/`line`/`EOF`, then a marker touch. Took 15.3 s now (round 2 6.2 s, HEAD 0.9 s). 800 copies took 10.8 s. The hook timeout is 10 s (settings.json).
  - Unverified assumption: Claude Code treats a hook timeout as a non-blocking error, so the tool call would go ahead (fail open).
  - Fix brief: make the scan linear (compute the route once per op, incremental context), or add an early size or heredoc-count cap that blocks.
Q3, ordinary reads: all 45 forms pass. They cover git status/diff/log/show on project/ and build.sbt, ls, cat, grep, rg, sed -n, find with -prune, wc, head/tail, pipes, xargs wc, quoted report heredocs naming the marker, timeout 45m sbt check, timeout 30m sbt storage/test and "core/testOnly *", SBT_OPTS heap with tail, du, stat, diff, cmp. One false block I hit myself (accepted residual): chmod with a `$S/*/` glob path.
Q4, build block: effective against 48 of 50 ordinary write forms (redirects, tee, cp, mv, rm, ln, install, dd, rsync, sed/perl -i, curl/wget -o, unzip/tar -C, patch, cd forms, ~, $HOME, ${HOME}, absolute paths, ../, quoting tricks).
- T-4 MINOR, new scope (HEAD has no build block, so not a regression), prompting. Owner: factory-engineer.
  - Missed forms: `echo x > ~ajcreator/.sbt/x` and `git apply /tmp/p.diff` (the target is in the patch file; close to the stated runtime-path residual).
Q5, tier change: consistent across models.json, the role tier, both rendered adapters (sonnet/medium; gpt-6.1-terra/medium), AGENTS.md and docs/agents.md section 4. render --check and the validator are green. No fence, tools, description or other role changed.
- T-5 MINOR. Owner: orchestrator (run-log).
  - run-log.md:113 says review 4 "is run on the high tier (model override opus)". The override keeps the agent file's effort, which is now medium on disk, so after a restart that is opus/medium, not the high tier. Phase 17 has the same limit, and docs/agents.md:549 says so.
Phase 11 gate:
- (a) No open critical or major finding: NOT MET (T-1, T-3 critical; T-2 major).
- (b) Production behavior tested: MET (no production change); T-1 and T-3 have no tests.
- (c) Tests pass: hook suites green as listed; sbt not rerun (no Scala change).
- (d) No reviewer wrote production code: MET.
- (e) Repair records name owners: MET.
ASSUMPTIONS: The hook timeout fails open (T-2). declare -f output reflects bash's parse. HEAD and round-2 copies were run with cwd set to their scratch roots, which hold a datasets symlink.
RISKS (not reviewed): the full 86-test guard file (I ran only 4 classes); rows the tests build at runtime (my diff covered literal rows only); the S-2 and S-3 residuals; the secret and structural passes outside heredocs; the routing suite; Codex.
MANUAL ACTION REQUIRED: The human decides on T-1, T-3 and T-2. Round 3 was the final authorized round, and the run log says to stop if review 4 leaves a critical open.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human decision on T-1, T-3 and T-2; no ratification of the S-1 part until they are fixed. Probe files: /tmp/claude-1000/-home-ajcreator-scholarchain/c959ee53-9b66-4f2f-ba70-fdb19efdea66/scratchpad/rev4/c2.py (T-1), /tmp/claude-1000/-home-ajcreator-scholarchain/c959ee53-9b66-4f2f-ba70-fdb19efdea66/scratchpad/rev4/c3.py (T-3), /tmp/claude-1000/-home-ajcreator-scholarchain/c959ee53-9b66-4f2f-ba70-fdb19efdea66/scratchpad/rev4/c5.py (T-2).
