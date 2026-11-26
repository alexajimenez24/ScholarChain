HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (code-reviewer tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 final re-review after repair round 2 (fresh context, read-only). I checked R-1 to R-4, compared the floor with HEAD, probed for sibling bypasses and false blocks, and checked the gate item by item.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status, diff, show; reads of the phase-11 records, InvariantErrorSuite, DownstreamMappingSuite, Evidence.scala and the guard's sbt and heredoc code (lines 110-250, 255-640, 805-835, 1415-1450, 1600-1625).
- Guard probes. Scripts in scratchpad/rv3 sent inert JSON payloads to three guards: the HEAD copy, a round-1 copy rebuilt from output-factory-diff.txt, and the installed guard. About 1,500 payloads; nothing was executed.
- I replayed the factory's 1022-input corpus (826 unique inputs) against HEAD.
- output-factory-diff-02.txt applied to HEAD gives a file byte-identical to the installed guard (cmp).
- `timeout 9m python3 -I` on the three hook test files: guard 78 OK, stop hook 17 OK, ownership 4 OK.
- `.agents/render.sh --check`: OK, 44 files. `validate-agent-files.sh`: OK, 19 roles, 735 files with one writer.
- stat and find -newer on the marker: no source file is newer than the marker (21:26:31). Only 2 test files, the guard, its tests and docs/agents.md changed after 19:53.
TEST RESULTS: I did not run sbt. I rely on test-report-03: 1103 passed, 0 failed, 1 ignored (gated AWS suite); rag 163, queryService 31. Hook tests as listed above.
VALIDATION RESULTS: REQUEST_CHANGES
Items:
- R-1 CLOSED as stated. All 7 inputs from review-report-02 give rc 2 (cat|bash, cat|python3, tee /dev/stdout|bash; marker, markTestRun, gold, sbt eval).
  - About 60 consumer shapes (env, exec, nohup, timeout, xargs, tee|bash, sed|bash, bash <(...), $(cat <<X), eval, here-strings) block marker and gold bodies.
  - S-1 is a separate cause in the same area.
- R-2 CLOSED. All 5 inputs give rc 2, plus -sbt-dir, -ivy, -java-home, -sbt-jar, --client, -J-javaagent, -Dsbt.boot.*, `<`, apply, alias, ++ eval and the console scoped keys.
  - Allowed launcher options pass, and so do every prescribed sbt form, SBT_OPTS heap values, testOnly with `--` arguments and runMain scholarchain.*.
  - On the auto-approved surface (`timeout 30m sbt "core/testOnly *"`), the injectable sbt arguments that still pass are harmless as far as I can tell (startServer, publishLocal, project file:, scalafmtOnly, newline inside an argument). See S-3.
- R-3 CLOSED. All 6 inputs give rc 2, plus ?old, [g]old, {gold,x}, data*/gold, */gold, pushd, absolute paths, ~, rsync --delete and find under datasets whose name filter matches the actual gold CSV.
  - datasets/*.tmp, datasets/raw/* and find datasets/samples are allowed. docs/agents.md matches, except for S-1 and S-2.
- R-4 CLOSED.
  - InvariantErrorSuite pins both note texts, and its precondition test proves the old note would echo the secret. It also pins Downstream("evidence") through the public builder and Downstream("answer") through a blank-reason synthesizer, with nothing audited.
  - DownstreamMappingSuite pins the sync 502 body, the key release, the async job, result and explain, and that the secret is absent from bodies, headers and metrics. Reverting either production change fails at least one test.
- R-5 relaxation: safe. Every newly allowed `$NAME/` form (rm, mv, cp, rsync, find) was also allowed by HEAD. `"$S"/*`, `$S/..`, `$PWD/` and `$HOME/` stay blocked.
- R-6 CLOSED.
- Floor compared with HEAD: the factory's claim "only the 19 C05 cases pass" is true for its corpus. On that corpus I find 15 unique HEAD-blocked inputs that now pass, all harmless C05 text. It is false outside the corpus (S-1).
Findings:
- S-1 CRITICAL (iron law 3: the floor is weaker than HEAD), MUST. Owner: factory-engineer.
  - Location: guard-dangerous.sh:114 (HEREDOC does not record whether the delimiter was quoted, and accepts only [A-Za-z_]\w* delimiters) and :231-246 (heredoc_split drops every non-shell body as data). The marker pass (:1424) and the secret pass (:1609) use it, and HEAD's raw marker scan was removed.
  - Bash still runs $(...), backticks and ${..:-$(..)} inside an unquoted (<<X, <<-X) body. These inputs give rc 0 now and rc 2 at HEAD:
    - `cat > /tmp/r.md <<X` / `$(touch .claude/.last-test-run)` / `X`; the same with cp, `sbt markTestRun` or python open() inside $(...) or backticks;
    - `cat <<X` / `` `cat .env` `` / `X`, which prints .env into the transcript.
  - A delimiter the regex misreads (`<<'END-R'`, `<<END.R`, `<<X'Y'`) hides every line after the real terminator. `cat > /tmp/r.md <<'END-R'` / `notes` / `END-R` / `touch .claude/.last-test-run` gives rc 0 (HEAD rc 2). The same trick lets sbt eval, polling loops, printenv and the gold rm through (rc 0 at HEAD as well).
  - Introduced in round 1, missed by review-report-02, unchanged in round 2. No test covers it.
  - Impact: a forged marker silences the stop hook (fake sbt check evidence), and .env can be printed (hard rule 4). Mitigant: no allow rule matches `cat ... <<`, so the human still gets a prompt. By impact alone this is MAJOR, not contrived and in scope (C05 and R-1 heredoc handling).
  - Fix (small): record whether the delimiter is quoted. For an unquoted body, run subst() on it and check the inner commands in every pass. Take the whole delimiter word after quote removal. For a delimiter it cannot parse, fail closed. Add these inputs as tests.
  - The guard is LIVE now. No third round exists, so this goes to the human (see MANUAL ACTION).
- S-2 MINOR, CARRY, factory-engineer. It is pre-existing (rc 0 at HEAD as well) and prompted.
  - Gold text fed to a shell without a heredoc gives rc 0: `echo 'rm -rf datasets/gold' | bash` (also sh, tee|bash, printf|xargs bash -c, echo > q.sh; bash q.sh).
  - The marker equivalents give rc 2, and the docs/agents.md residual does not list this case.
  - Also: FEED_SHELLS (:164) misses rbash, ash, mksh, yash, fish, csh, `script -qc bash` and ssh, so `cat <<X | rbash` with `sbt 'eval 1'` gives rc 0. Marker and gold bodies still block.
- S-3 MINOR, CARRY, factory-engineer (R-2 sibling, prompted, not auto-approved).
  - `env 'BASH_FUNC_java%%=() { ...; }' sbt check` and `java() {..}; export -f java; timeout 45m sbt check` give rc 0. sbt's launcher is a bash script, so it imports exported functions, a code source that SBT_ENV_BAD (:341) does not name.
  - Not a regression (HEAD auto-allowed `sbt *`).
- S-4 MINOR, CARRY, factory-engineer. False blocks (fail closed) that HEAD did not have:
  - `grep -rn markTestRun project | sort` and test-report-03 #2 (`grep ... markTestRun ... | cut`) were added in round 2;
  - `command -v sbt` ("sbt with no command") and a heredoc .py that names datasets followed by `python3 /tmp/n.py` were added in round 1.
  - test-report-03 #1 (`ls -l <marker> | awk`) is blocked at HEAD too. Workarounds are trivial.
- S-5 MINOR, CARRY, orchestrator.
  - run-log row 8 runs "test-engineer (continued)", although AGENTS.md requires a freshly spawned test-engineer. The rerun was mechanical, and its counts agree with the fresh marker.
  - repair-02-factory-engineer's floor differential claim is incomplete (S-1).
Phase 11 gate:
- (a) No open critical or major verified finding: NOT MET (S-1).
- (b) Every production behavior has a relevant test: MET. R-4 is closed, and round 2 changed no production file.
- (c) All tests pass under declared profiles: MET. 1103 passed, the marker is fresh, AWS suites are NOT RUN by declaration, and the hook tests are green.
- (d) No reviewer wrote production code: MET.
- (e) Repair records name owners and rerun evidence: MET (S-5 is minor).
ASSUMPTIONS:
- Claude Code runs PreToolUse hooks before allow rules, and does not auto-approve `cat ... <<X` or env-prefixed sbt (no matching allow rule).
- Bash runs command substitution in unquoted heredoc bodies; this is documented bash behavior, and I did not execute it.
- sbt treats a newline inside one argument as test-pattern whitespace, not a command separator. I did not verify this.
- I rate S-1 by iron law 3; its impact rating is stated above.
RISKS (not reviewed or not checked):
- About 700 of 1967 guard lines read. The aws, scale-cap, terraform, push and human-gate sections were checked only by probes. The new test cases were not read line by line.
- The routing suite was not rerun. The round-1 production diff was not reread beyond spot checks (review-report-02 covered it). No cross-stage communities test exists (CARRY).
- The R-4 502-versus-500 mapping stays CARRY: deterministic invariants count toward the query breaker.
- The hook files are live before ratification, so S-1 is in force in every current session.
MANUAL ACTION REQUIRED:
- Human decision on S-1, because no third round exists. Owner: factory-engineer. Reason: the floor is weaker than HEAD for marker forging and .env reads. Choose one:
  - (a) authorize a bounded factory-engineer fix with the S-1 inputs as tests, then a fresh review;
  - (b) ratify with S-1 recorded as an open critical in human-gate.md;
  - (c) reject the guard diff (git checkout to HEAD), which reopens R-1, R-2 and B-1.
  I recommend (a).
- Keep auto mode off until S-1 is fixed.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human decides on S-1. Then documentation-engineer (STATUS.md, MODULE.md notes, CARRY list S-2 to S-5 plus the R-4 502-versus-500 CARRY), then human ratification of output-factory-diff-02.txt.
