HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (factory-engineer tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Phase 11 repair round 2 (last round): R-1, R-2, R-3 (MUST); R-5 and R-6 folded in.
FILES CREATED: none in the repository. Scratch: fe2/ (HEAD, round-1 and new copies, tools/diff.py, new_cases.py, probe_installed.py, res3.json, res-final.json, routing.log).
FILES MODIFIED: .claude/hooks/guard-dangerous.sh, .claude/hooks/test_guard_dangerous.py (68 -> 78 tests), docs/agents.md (section 1, gold-set paragraph). Unchanged this round: settings.json, verify-tests-ran.sh, AGENTS.md, roles, skills.
COMMANDS RUN: edits on scratch copies (fe2/new; fe2/mk.sh makes root-patched copies for testing); scratch test runs; differential tools/diff.py (inert JSON payloads, 12 threads, nothing executed); atomic install (cp to .name.new, chmod 775/664, mv -f; modes kept); the three hook test files; render.sh --check; validate-agent-files.sh; bash -n and -x on every hook and script; JSON/TOML parse; probe_installed.py; ROUTER=claude timeout 570 .agents/routing-suite.sh. No sbt, no cloud, no commit, no background shell, no polling.
TEST RESULTS (installed hooks): test_guard_dangerous.py 78 OK (146 s); test_verify_tests_ran.py 17 OK; test_guard_ownership.py 4 OK.
VALIDATION RESULTS:
- JSON (settings, .mcp.json, .codex/hooks.json, models.json) and TOML (.codex/config.toml, .codex/agents/*) parse OK; bash -n and the executable bit OK on .claude/hooks/*.sh, .agents/*.sh and .agents/githooks/*.
- render.sh --check OK (44 files); validate-agent-files OK (19 roles, 732 files with one writer). Its first run FAILED on a `{{HOME}}` f-string escape in a new test (placeholder rule); I rewrote that line, and the second run was OK.
- Routing (claude, haiku): 153/153, rc 0. Codex: not run (out of scope).
- Probes (installed hook): 29/29 as expected. Blocked (rc 2): aws s3 recursive delete; the polling loop; all 7 R-1 reviewer inputs; all 5 R-2 inputs plus SBT_OPTS=-Duser.home and HOME= overrides; all 6 R-3 inputs; R-6. Allowed (rc 0): the report heredoc naming the marker and markTestRun; timeout 45m sbt check; timeout 30m sbt storage/test; SBT_OPTS="-Xmx2560m -Xss4m" sbt -batch check; rm -rf datasets/*.tmp; the R-5 cp.
- Differential (1022 inputs: every test-file input, reviewer rv2 c1-c7, 158 round-2 inputs): HEAD-blocked inputs that now pass: 19. All 19 are the listed C05 harmless text: (a) report heredoc bodies, (b) sbt check plus grep markTestRun, (c) bash x.sh, sed -n, cp, stat beside a marker read. List in res-final.json.
- Round 1 let 81 HEAD-blocked corpus inputs pass; 62 were not C05 and are blocked again. The 4 "expected" mismatches are tests that build temp dirs or HOME in their setup, so the collector cannot replay them; the real test run passes them.
Per item (matrix row for all: factory-engineer May "draft list A"; each change adds a block, except R-5, see below):
- R-1 guard: new PYLIB helpers heredoc_route, feeds_shell, heredoc_split and herestrings, used by the structural, privilege, gold, marker and secret-scan passes.
  - A body is commands when its own command or a later pipe stage is a shell, eval, source or ., including through xargs sh, env -S, exec or a $VAR consumer.
  - The same holds when it is inside an unclosed $( ` <( >( or (, after a pipe that ends the line, or when it is the word of a here-string (bash <<< "...").
  - Marker: a data heredoc naming the marker allows beside it only readers, the safe consumers and mkdir. One written to a non-document file (x.sh) is read as HEAD read it.
  - Hand-offs return to the strict rule: a naming segment piped onward, <(echo marker), a file a naming segment writes and another segment reads, and find -name .last-* with a writing -exec, a pipe or a listing.
  - Gold: interpreter-fed bodies are code; a data body that names datasets allows only readers and file commands beside it.
  - Structural: a data body plus a shell, source, script path or $VAR command word is re-read as commands (cat > x.sh <<EOF ... EOF; bash x.sh).
  - Round-1 regressions vs HEAD found and fixed: here-strings (bash <<< "touch marker" and 2 others); marker hand-offs through <(...), files and piped readers (10 forms); a secret glob after a data heredoc (cat > f <<EOF..EOF; cat .e*), caused by a second body split in shell_words.
- R-2 guard: sbt launcher options are an allow-list. -batch, -no-colors, -v, -d, -timings, -traces, -no-server, --warn/--error/--info, -mem N, -J-Xmx/-Xms/-Xss, six -Dsbt display properties, help and version.
  - Every other - or @ argument is refused anywhere, also inside command strings and with spaces trimmed, through timeout, env, bash -c and eval.
  - SBT_OPTS, JAVA_OPTS, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS and JVM_OPTS may hold only heap options and those properties (the repository uses SBT_OPTS="-Xmx2560m -Xss4m").
  - HOME, PATH, CLASSPATH, JAVACMD, BASH_ENV, ENV, IFS, PS4 and the XDG_, SBT_, COURSIER_, IVY_, JAVA_, JDK_, JVM_, SCALA_, LD_ and DYLD_ variables are refused for sbt: as a prefix, through env, or as NAME=value or export anywhere in the command.
  - Also refused: java -jar sbt-launch with other JVM options, and xargs sbt.
  - settings.json: kept as is. The testOnly and runMain scholarchain.* entries cannot be exact (open patterns and arguments), and every other entry is already exact. The guard runs before the allow rule, so those forms are now refused even when auto-allowed.
- R-3 guard: ancestor() also matches a glob that can match datasets/gold itself (datasets/*, datasets/*/, cd datasets && rm -rf *); datasets/*.tmp stays allowed.
  - $HOME and ${HOME} resolve like ~; $PWD and $OLDPWD read as the repository root.
  - xargs rm, mv, cp, chmod, find and similar is blocked when the command names datasets, runs in datasets, or lists names that can include gold (ls or git ls-files of an ancestor, a find that can reach it).
  - docs/agents.md: the datasets/* residual sentence is replaced by the new wording.
- R-5 (folded): a plain $NAME/ or $NAME/. is not resolved, the same as a bare $NAME.
  - $HOME, $PWD, $(pwd)/, "$S"/*, $S/.. and $S/datasets stay blocked.
  - This unblocks 6 round-1 false blocks, none of which HEAD blocked (differential).
- R-6 (folded): test is no longer an argument-taking task. After testOnly or run, a scoped-key word (core/console) is still checked.
- Tests: 10 new methods (HeredocFedToShellTest, SbtLauncherOptionTest, GoldGlobAndVariableTest), about 150 inputs. The $HOME cases use the repository path relative to $HOME.
ASSUMPTIONS: Claude Code runs PreToolUse hooks before allow rules (exit 2 blocks an allowed command). sbt reads --addPluginSbtFile and -D/-J options as described in its launcher documentation; I did not execute any of them. A heredoc body that bash cannot parse as shell is not run as a script.
RISKS:
- LIVE NOW: the guard and its tests run from disk, so this round is in force before ratification. To reject it, restore both files to the round-1 versions in output-factory-diff.txt, or git checkout them to HEAD.
- Residual bypasses:
  - names built at run time (touch $(< f), lists read from files written in another call, grep -rl producers);
  - sbt from another working directory (cd /tmp/x && sbt, env -C, a payload cwd outside the repository);
  - code planted by an earlier command in ~/.sbt/1.0/plugins, .sbtopts, .jvmopts or project/ (sbt loads them; the highest remaining gap);
  - s=sbt; $s 'eval 1';
  - git -c core.pager on marker reads;
  - quoted globs inside here-strings;
  - find without a name filter piped into xargs touch;
  - computed paths in interpreter code.
- New false blocks (fail closed): sbt check, bash or python3 beside a heredoc that names the marker or datasets; a data heredoc plus bash x.sh, re-read as commands, can trip on sbt, push or loop words; prose lines in a heredoc written to a non-document file; <<\EOF bodies are read as commands; ls | xargs rm at the root; HOME= or PATH= set in any command that runs sbt.
- Not done: no sbt run, no Codex probes, no commit.
MANUAL ACTION REQUIRED: ratify or reject the round-1 and round-2 control-plane diff (output-factory-diff-02.txt). Restart Claude Code (settings.json and commands from round 1). After the restart, probe that timeout 45m sbt check and timeout 30m sbt storage/test run without a prompt and that sbt "--addPluginSbtFile=/tmp/p.sbt" check is blocked. Decide whether the ~/.sbt plugin residual needs a guard rule.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer rerun, then a fresh code-reviewer review-report-03.md, then human ratification.
No constitutional change in this report is effective before human ratification and a session restart (the hook files are already live, see RISKS).
