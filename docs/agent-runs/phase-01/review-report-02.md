HARNESS: claude-code (version not re-checked in this run; earlier phase-01 reports record 2.1.330)
MODEL: opus (claude-opus-5-5), high tier, effort high (role default per `.agents/models.json`, no override)

# Phase 01 repair round 3: verification review (code-reviewer)

**Verdict: REQUEST_CHANGES.** Round 3 fixed the forms it tested, but it introduced a **regression that weakens the floor**:
- The secret-read check used to scan the raw command whenever its parser failed. That fallback no longer runs.
- So any secret read with a trailing `#'`, or with an ordinary apostrophe in a comment or heredoc, now passes. Examples: `cat .env #'` and `cat .env # don't print this`.
- The ratified staged hook blocked these, and so did the pre-round-3 copy.

Besides that CRITICAL, I found one MAJOR (the new glob check does not follow `sh -c`, `eval` or `find -name`) and three MINOR findings. All of them belong to factory-engineer.

What passes:
- Both guard suites are green: 45 and 4 tests.
- `render.sh --check` is OK and the validator is OK.
- MINOR-5 and the MINOR-6 line-239 note are closed.
- Every original reviewer probe for CRITICAL-1, MAJOR-1, MAJOR-6, MINOR-2 and MINOR-3 is now blocked.
- The live diff matches the diff in repair-03.md line for line.
- None of my 50 legitimate commands lost its pass, including sbt, git diff/log/show without `--output`, and ordinary reads and globs.

## Probe method
- Every probe was sent only as inert JSON to the hook (`{"tool_name":"Bash","tool_input":{"command":...},"cwd":"/home/ajcreator/scholarchain"}` on stdin), and I recorded the exit code and stderr. No probe command was executed.
- `SCHOLARCHAIN_ROLE` was removed from the probe environment and the payload carried no `agent_type`, so every probe ran as the orchestrator.
- My own live guard scans my Bash commands. So the harness scripts build forbidden strings from fragments, for example `".e" + "nv"`. My guard blocked one early attempt that had a literal `.ssh`; I fragmented it and re-ran. I used no other workaround.
- Hooks compared:
  - **live**: `.claude/hooks/guard-dangerous.sh`, sha256 `101243b2…`;
  - **pre3**: factory-engineer's pre-round copy, sha256 `11d91405…`;
  - **staged**: the ratified index copy from `git show :…`, sha256 `d0d438b2…`.

## Findings, ranked by severity

### R3-CRITICAL-1: the secret scan's raw-command fallback no longer runs, so a parse failure lets literal secret reads through (regression, weakens the floor)
- **File:** `.claude/hooks/guard-dangerous.sh`.
  - Lines 1027-1030:
    ```bash
    scan="" globs=""
    { IFS= read -r -d $'\x1d' scan; IFS= read -r -d '' globs; } <<< "$scan_out"
    [ -n "$scan" ] || scan=$cmd
    ```
  - Line 718, which the code now contradicts: "If that scrub fails, the raw command is scanned."
- **Defect:** when the scan's Python step fails, `scan_out` is empty. This happens whenever `shlex` raises, for example on an unbalanced quote in a comment or in a heredoc body. But `<<<` always appends a newline, so `scan` becomes `"\n"`, `[ -n "$scan" ]` is true, and `scan=$cmd` never runs. Nothing is scanned, and `globs` is empty too.
- **Mechanism check:** I ran `scan_out=""; … <<< "$scan_out"` in pure bash. It printed `len=1 hex= 0a` and "fallback NOT taken".
- **Failure inputs.** Each gives rc=0 on live and rc=2 on pre3:

  | Input | Staged |
  |---|---|
  | `cat .env #'` | rc=2 |
  | `cat .env # don't print this` | rc=2 |
  | `cat ~/.aws/credentials #'` | rc=2 |
  | `cat ~/.ssh/id_rsa #'` | rc=2 |
  | `cat .env` + newline + `cat <<EOF` + newline + `it's a note` + newline + `EOF` | rc=2 |
  | `grep -vf /dev/null .env #'` (the CRITICAL-1 form again) | rc=2 |
  | `cat infra/x.tfvars #'` | rc=0 (staged predates tfvars) |
  | `head -100 config/prod.env # it's fine` | rc=0 (staged predates prod.env) |

  The glob forms also pass on live, because the glob records are empty: `cat .e* #'`, `cat ~/.aws/cred* #'` and `cat .en? "`.
- **Impact:** any agent can read `.env`, AWS credentials or SSH keys through the shell by adding `#'`. An ordinary prose apostrophe in the same command has the same effect, without any adversarial intent: heredoc bodies with "don't" are common. Bash `cat` is not auto-allowed, so a human prompt remains, but the guard is the floor for exactly this case (Codex has no Read deny at all).
- **Tests that pass on broken behaviour:** no test in `test_guard_dangerous.py` exercises a shlex failure, so all 45 pass.
- **Owner:** factory-engineer.
- **Repair brief:**
  - **Likely cause:** the round 3 change from `scan=$(…)` to one Python output split by `read <<<`.
  - **Fix options:**
    - branch on `[ -z "$scan_out" ]` before the `read` and set `scan=$cmd` there;
    - or feed `read` with `< <(printf '%s' "$scan_out")`, which adds no newline;
    - and make the `-n` test ignore a lone newline.
  - **Fail closed for globs too:** when Python fails, run the aimed-glob analysis on a whitespace split, or a comment-stripped retry, rather than skipping globs.
  - **Correct line 718** if the behaviour changes.
  - **What else could break:** the round 2 tfvars `-var-file` exemption does not apply on the raw fallback. So `terraform plan -var-file=x.tfvars #'` would be blocked, which is acceptable because it fails closed.
  - **Tests to add:** every input above.
  - **Must stay green:** all 45 + 4, especially `GrepPatternFileTest`, `SecretGlobTest`, `test_grep_pattern_is_not_a_read`, `test_grep_reading_a_secret_file_blocked` and the tfvars exemption tests.

### R3-MAJOR-1: the MAJOR-6 glob check skips glob words that a nested shell or `find` expands
- **File:** `.claude/hooks/guard-dangerous.sh`.
  - Lines 750-767: `protect()` turns every quoted `*`, `?` and `[` into literals, including inside a `-c` string.
  - Lines 1011-1013: only top-level words are analysed.
  - Compare lines 169-172 and 556-560: the structural pass and the gold check both recurse into `sh -c`, `bash -c` and `eval`.
- **Failure inputs.** Each gives rc=0 on live:
  - `bash -c 'cat .e*'`
  - `sh -c "cat ~/.aws/cred*"`
  - `eval 'cat .e*'`
  - `find . -maxdepth 1 -name '.e*' -exec cat {} +`
  - `find ~/.aws -name 'cred*' -exec cat {} +`
- **Control:** `bash -c 'cat .env'` is blocked (rc=2), because the literal regex sees the raw text.
- **Impact:** MAJOR-6 (the floor rule "Block reads of .env, secret directories, private keys") is closed only for top-level words. A single wrapper reopens it. These commands are not auto-allowed, so a human prompt remains.
- **Documented residue, not counted here:** `cat <<EOF | bash` + newline + `cat .e*` + newline + `EOF` also passes. That is piping text to a shell, deferred with MAJOR-4.
- **Owner:** factory-engineer.
- **Repair brief:**
  - Re-run the glob and brace analysis on the string argument of `sh`/`bash`/`zsh`/`dash`/`ksh -c` and of `eval`, unquoted once, the same way the structural pass recurses.
  - Treat the pattern values of `find -name`, `-iname`, `-path` and `-ipath` as glob words: aimed-name matching, plus expansion under find's start directories, when the same find has `-exec`, `-execdir`, `-ok` or `-okdir`, or prints names into a reader.
  - **Must stay green:** `SecretGlobTest`, especially `cat '.e*'` → 0 and `find . -name '*.scala'` → 0, plus `test_heredoc_bodies`.

### R3-MINOR-1: human-gate write shapes are bypassed by quote splitting and unlisted writers, and docs/agents.md list E still overstates the coverage
- **Files:**
  - `.claude/hooks/guard-dangerous.sh` lines 345-348: the name is matched on `$cmd` or `$norm`, but the write-shape regex runs on raw `$cmd` only.
  - `docs/agents.md` lines 88-93.
- **Failure inputs.** Each gives rc=0 on live:
  - quote and escape splitting of a listed shape: `"r"m <G>`, `r''m <G>`, `m\v <G> /tmp/x`;
  - `sed` in-place forms that the doc lists as "sed -i": `sed -ni d <G>`, `sed -E -i s/a/b/ <G>`, `sed --in-place s/a/b/ <G>`;
  - writers that are not listed: `gzip <G>` (removes the original), `ed -s <G> <<< w`, `rsync /dev/null <G>`, `patch <G> /tmp/p.diff`, `echo x | sponge <G>`, `git archive -o <G> HEAD`, `git bundle create <G> HEAD`, `git config --file <G> a.b c`.

  Here `<G>` is `docs/agent-runs/phase-01/human-gate.md`.
- **Doc accuracy (work-order scope item):**
  - List E correctly enumerates the shapes the regex contains.
  - But it says "sed -i", "rm" and "mv" are blocked when the file is named literally. It also names only non-literal naming (glob, variable, ancestor) as the residue.
  - Both statements are false for the inputs above, so list E is not yet accurate.
  - `docs/agent-runs/README.md:59` still says "a deny rule blocks every agent tool from writing it". factory-engineer flagged this in repair-03 RISKS, and the round 3 work order kept it out of scope.
- **Impact:** low. None of these commands is auto-allowed, so a human prompt remains, and git diff and the human gate review are the backstop.
- **Owner:** factory-engineer (both files are list A; README.md needs an orchestrator work order that allows `docs/agent-runs/README.md`).
- **Repair brief:**
  - Match the write shapes on a quote- and escape-resolved copy, for example the structural pass's canonical segments.
  - Either reuse the gold resolver's `EVERY` set, in-place detection and git write targets for `docs/agent-runs/**/human-gate.md` (the original MINOR-2 brief), or add the remaining writers to list E's residue sentence.
  - Fix README.md:59.
  - **Must stay green:** `HumanGateShellWriteTest`, including `test_reads_allowed`.

### R3-MINOR-2: MINOR-3 environment guard gaps
- **File:** `.claude/hooks/guard-dangerous.sh`.
  - Lines 144-151: a path such as `/usr/bin/env` sets `env = True`, but it is not in `WRAP`, so it stays as `argv[0]`. The "no command" branch never runs, and `name == "env"` has no check of its own.
  - Line 88: `SECRET_VAR` covers PAT, TOKEN, SECRET and KEY only.
- **Failure inputs.** Each gives rc=0 on live:
  - `/usr/bin/env`
  - `'/usr/bin/env'`
  - `busybox env`
  - `printenv DB_PASSWORD`
- **Controls:**
  - `env`, `command env`, `nice env`, `x=1 env`, `e"nv"`, `env -0` and `env | sort` are blocked;
  - `/usr/bin/printenv GITHUB_PAT` is blocked;
  - `env FOO=1 sbt check`, `env -u FOO sbt compile` and `printenv PATH` are allowed.
- **Impact:** low. These commands are not auto-allowed, the `.mcp.json` secret is `GITHUB_PAT`, which is covered, and the residue is already reported in repair-03 (`echo $VAR`, `export -p`, `set`).
- **Owner:** factory-engineer.
- **Repair brief:**
  - Apply the "env with no command" rule when `basename(argv[0]) == "env"` and no command follows, and also for `busybox env`.
  - Consider adding PASSWORD, PASSWD and CREDENTIAL to `SECRET_VAR`.
  - **Must stay green:** `SecretEnvironmentTest`.

## Status of the in-scope round 3 items

| Item | Status | Evidence (live hook) |
|---|---|---|
| CRITICAL-1 | Fixed for the listed forms; reopened by R3-CRITICAL-1 | All 7 reviewer probes give rc=2. These also give rc=2: `-f /dev/null -v -- .env`, `-vf/dev/null`, `zgrep -vf`, `-v --file /dev/null`, `-vE ''`. `grep -vf /dev/null .env #'` gives rc=0. |
| MAJOR-1 | Fixed at the hook layer | All 5 reviewer probes give rc=2. These also give rc=2: `git -C docs/agent-runs/phase-01 diff --output=human-gate.md`, `git -C .claude diff --output .last-test-run`, and the gold and marker forms with `#'` (both fallbacks still work). `git diff --output=/tmp/x.patch` is allowed by the hook as designed. The `Bash(git * --output*)` deny rule (settings.json:79) is unprobed until ratification and restart. |
| MAJOR-6 | Fixed for top-level globs; reopened by R3-CRITICAL-1 and R3-MAJOR-1 | All 4 reviewer probes give rc=2, and so do `.e[n]v`, `.??v`, `~/.ss?/id_*`, `{.,x}env`, `./.e*` and `$HOME/.aws/cr*`. The deadline holds: 10+ heavy `/usr/*/*/*/*` and `/proc/*/*/*` globs before `.e*` still block, within 1.01 s. `grep -rn PASSWORD .` gives rc=0, a documented residue. |
| MINOR-2 | 3 probes give rc=2; list E only partly accurate | R3-MINOR-1. |
| MINOR-3 | 3 probes give rc=2 (`call_aws` covered by the tests) | Gaps in R3-MINOR-2. |
| MINOR-5 | Closed | See below. |
| MINOR-6, line 239 | Closed | `docs/agents.md` 245-252 matches `claude-post-ratification-probes-02.md` row (a), lines 19-20. |

MINOR-5 evidence:
- The extracted `secret_files` run through `grep -E` blocks 20 secret names and wrongly blocks none of 9 ordinary names.
- A throwaway repo under the scratchpad staged `.envrc` and `infra/p.tfvars`, and I ran the real hook script directly, with no commit. It printed "a secret, state or marker file is staged" (rc 1). With only README.md staged, that line was absent.
- Home-only names (`.aws/credentials`, `.ssh/config`, `gh/hosts.yml`, `.codex/auth.json`) are not matched, consistent with their `~/` deny rules.
- `.env.example` is blocked. This is pre-existing and conflicts with the report's placeholder advice, which only mentions `dev.tfvars.example`.

## Regression and allow-side check
- I ran 50 legitimate commands against both live and pre3, with identical results. They include:
  - sbt: `sbt check`, `compile`, `"testOnly *Spec"`, `testOnly scholarchain.core.*`;
  - git: `git diff`, `diff --cached --stat`, `log --oneline`, `show HEAD:README.md`, `status`, `diff -- <G>`, `log -p -- <G>`;
  - reads: `cat`/`head` of human-gate.md and of the marker;
  - globs: `.claude/hooks/*.sh`, `modules/*/MODULE.md`, `docs/agent-runs/phase-01/*.md`, `infra/stacks/*/*.tf`, `rm -rf target/*`, `mkdir -p modules/{core,spark}/src`, `ls -d .*`;
  - terraform: `terraform plan -var-file=dev.tfvars`;
  - tooling: render.sh, the validator, `bash -n`, the test runner, `env FOO=1 sbt check`, `printenv PATH`.
- Aimed-glob false-positive spot checks all give rc=0: `ls ca*`, `ls se*`, `cat docs/au*`, `ls cl*`, `ls ~/.c*`, `ls gh*`, `rm -f target/cache*`.
- Pre-existing false positives, also blocked in pre3, are not counted: `grep -n guarantee <G>` (matches `tee`) and `grep -rn 'term ' <G>` (matches `rm `).
- The only regression I found is R3-CRITICAL-1.
- No drift between docs/agents.md and the hooks beyond R3-MINOR-1.
- The diff touches 5 list A files, all factory-engineer's. It widens no fence, territory row or tools field, and moves no version.
- `docs/agent-runs/README.md` content equals the earlier rounds; `git diff` on it shows only round 1 and 2 changes.

---

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 01, verification of repair round 3 only. I re-ran the reviewer probes for CRITICAL-1, MAJOR-1, MAJOR-6, MINOR-2, MINOR-3 and MINOR-5 against the live hooks as inert JSON payloads, and checked list E accuracy. I confirmed both guard test suites, checked the round 3 diff for allow-side breakage, new bypasses, over-broad blocks and doc/hook drift, and returned a verdict. Read-only.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- Read AGENTS.md, docs/agents.md, `docs/agent-runs/phase-01/review-report.md` and `repair-03.md` (whole), `.claude/hooks/guard-dangerous.sh` (whole), `.claude/settings.json`, `.agents/githooks/pre-commit`, README.md lines 55-62, and `claude-post-ratification-probes-02.md` probe (a).
- Repository state:
  - `git status --short`;
  - `cmp` of every file in factory-engineer's `pre3/` snapshot against the working tree (5 differ);
  - `diff -u` of those 5 against `pre3/`, compared with the diff block in repair-03.md: SAME-CHANGED-LINES, 762 lines each;
  - `git diff -- docs/agent-runs/README.md`;
  - `git show :.claude/hooks/guard-dangerous.sh` copied to the scratchpad;
  - `sha256sum` of the three hook versions.
- Probe harnesses (`python3 -I`) in `/tmp/claude-1000/-home-ajcreator-scholarchain/42f1fce8-753a-448f-ad81-f5a61c4149cd/scratchpad/`:
  - `probe.py`: 126 cases, run on live (`cur.txt`) and on pre3 (`pre3.txt`);
  - `probe_fb.py`: 9 fallback cases, run on live, pre3 and staged;
  - `probe_t.py`: 5 deadline cases;
  - `probe_fp.py`: 10 false-positive cases;
  - `pc.py`: pre-commit filter, synthetic list plus the throwaway repo `pcrepo/`, with the hook script run directly and no commit.
- A pure-bash demonstration of the empty here-string `read`, which printed `len=1 hex= 0a` and "fallback NOT taken".
- `PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_dangerous.py` → "Ran 45 tests in 133.904s", "OK", rc 0.
- `PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_ownership.py` → "Ran 4 tests in 2.471s", "OK", rc 0.
- `.agents/render.sh --check` → "render --check OK: 44 generated files match their sources", rc 0.
- `.claude/hooks/validate-agent-files.sh` → "validate-agent-files OK: 19 roles, 19 Claude agents, 19 Codex agents, 4 MCP servers, 104 files with one writer", rc 0. The count was 103 in repair-03; `repair-03.md` itself is the new tracked entry.
TEST RESULTS:
- test_guard_dangerous.py: 45 tests OK.
- test_guard_ownership.py: 4 tests OK.
- No test covers a shlex-failure command, so the suite passes despite R3-CRITICAL-1.
VALIDATION RESULTS: REQUEST_CHANGES.
1. R3-CRITICAL-1 (`.claude/hooks/guard-dangerous.sh:1027-1030`, line 718; factory-engineer): the raw-command fallback no longer runs, so `cat .env #'` gives rc=0 (regression; staged and pre3 give rc=2).
2. R3-MAJOR-1 (`guard-dangerous.sh:750-767`, `1011-1013`; factory-engineer): `bash -c 'cat .e*'`, `eval 'cat .e*'` and `find … -name '.e*' -exec cat {} +` give rc=0.
3. R3-MINOR-1 (`guard-dangerous.sh:345-348`, `docs/agents.md:88-93`, `docs/agent-runs/README.md:59`; factory-engineer): `"r"m <G>`, `sed -E -i … <G>`, `gzip <G>` and `git archive -o <G>` give rc=0, and list E overstates the coverage.
4. R3-MINOR-2 (`guard-dangerous.sh:88`, `144-151`; factory-engineer): `/usr/bin/env` and `printenv DB_PASSWORD` give rc=0.

No test-fairness dispute was in scope.
ASSUMPTIONS:
- Hook edits are live, as handoff.md and repair-03 say, so the live file is the deployed guard.
- The `pre3/` snapshot is factory-engineer's true pre-round state. Its diff to the working tree equals the reported diff.
- The staged index copy is the ratified floor used to rate R3-CRITICAL-1 as a regression.
- Probes in scratch copies of the hook resolve `root` to the scratchpad, which affects only gold-check results on those copies. I drew no gold conclusion from them.
- For impact, I assumed a non-auto-allowed Bash command prompts the human. I did not observe this in this run.
RISKS:
- What I did not review:
  - `Bash(git * --output*)` matching under Claude Code (it needs a restart);
  - the `call_aws` path beyond the existing tests;
  - Codex files;
  - `.claude/hooks/guard-ownership.sh` internals beyond its test run;
  - the out-of-scope findings MAJOR-2 to MAJOR-5, MINOR-1, MINOR-4 and MINOR-7, other than noting that the MAJOR-4 class (fail-open on parse) now also covers the glob path through R3-CRITICAL-1;
  - the routing suite (it makes model calls, and no description changed).
- `STATUS.md` and `PROJECT_MEMORY.md` still do not exist (MINOR-6, documentation-engineer); I read handoff.md's context through review-report.md only.
- The guard-dangerous hook is live with R3-CRITICAL-1 in every running session now. Until it is fixed, a secret read with a trailing `#'` or an apostrophe comment passes the guard; the human prompt remains.
- State left behind: scratch files only, under `/tmp/claude-1000/-home-ajcreator-scholarchain/42f1fce8-753a-448f-ad81-f5a61c4149cd/scratchpad/`:
  - probe scripts and outputs (`probe.py`, `probe_fb.py`, `probe_t.py`, `probe_fp.py`, `pc.py`, `cur.txt`, `pre3.txt`, `live.diff`, `reported.diff`);
  - hook copies (`staged-guard.sh`, `pre3-guard.sh`);
  - the throwaway repo `pcrepo/`.

  The repository is unchanged.
MANUAL ACTION REQUIRED:
- Human: do not ratify round 3 as is. Either keep the live hook while factory-engineer fixes R3-CRITICAL-1 urgently, or restore pre3 (repair-03 MANUAL ACTION 1), which reverts the round 3 fixes as well.
- Orchestrator: route R3-CRITICAL-1, R3-MAJOR-1, R3-MINOR-1 (including the README.md:59 line, which needs a work order that allows `docs/agent-runs/README.md`) and R3-MINOR-2 to factory-engineer as repair round 4. Each failure input above becomes a test. This is loop round 4 on phase 01, so per docs/agents.md section 7 ("both loops stop after 3 rounds and go to the human"), the human must authorize another round.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE:
1. The human decides on a further repair round (the loop cap has been reached).
2. factory-engineer fixes the findings above, keeps the 45 + 4 tests green, and adds the new cases.
3. A fresh code-reviewer reruns this probe set (`probe.py`, `probe_fb.py`) against the new live hook.
4. Then human ratification and a restart, then the orchestrator's post-ratification probes (g) to (i).
5. Then the human commits Phase 1.
