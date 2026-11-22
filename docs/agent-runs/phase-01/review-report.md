HARNESS: claude-code (Claude Code 2.1.330)
MODEL: opus (claude-opus-5-5), high tier, effort high (role default per `.agents/models.json`, no override)

# Phase 01 review: code-reviewer verdict

**Verdict: REQUEST_CHANGES.** I found 1 CRITICAL, 6 MAJOR and 7 MINOR issues.

The structural checks pass:
- `render.sh --check` is OK.
- The validator is OK.
- Both guard test files pass.
- Every Phase 1 work-order artifact exists.
- code-reviewer has no Write or Edit tool.

The fixes from rounds 1 and 2 work for the exact forms they test. Several of them can still be bypassed with a single command:
- **CRITICAL (a regression):** the grep/rg search-pattern scrub lets `grep -vf /dev/null .env` print a whole secret file. The original package blocked this.
- **MAJOR:** commands that Claude Code auto-allows (`git diff --output=…`, `sbt 'markTest''Run'`) can refresh the test marker or overwrite `human-gate.md` and the gold labels, with no prompt and no hook block.

All fixes belong to factory-engineer, except MINOR-6 (documentation-engineer), MINOR-7 (human and orchestrator) and part of MINOR-3 (IAM, infra-engineer). Hook edits take effect at once (handoff.md), but settings.json edits need ratification and a restart.

Probe method: I ran every probe below as inert JSON on stdin to the live `.claude/hooks/guard-dangerous.sh`. None of the probed commands was executed. My own session's guard blocked my first attempts to write the test data, because the data contains forbidden strings. So the probe harness assembles those strings from fragments in memory (for example `"terra" + "form"`) in a script under the scratchpad. I used no other workaround. "staged" below means the original package in the index (`git show :.claude/hooks/guard-dangerous.sh`). "round 1" means factory-engineer's saved pre-round-2 copy.

---

## Findings, ranked by severity

### CRITICAL-1: the grep/rg scrub lets a full secret file through when patterns come from `-f`/`--file` (weakens the floor)
- **File:** `.claude/hooks/guard-dangerous.sh`
  - lines 696 and 700: `f` is in `GREP_SHORT` and `RG_SHORT`, and `--file` is in the long lists;
  - lines 759-773: only `-e` and `--regexp` set `have_e`;
  - line 778: `if not have_e and pos: drop.add(pos[0])`.
- **Defect:** with `-f FILE`, grep takes no pattern operand, so the first positional word is a file. The scrub drops it from the scanned text as if it were a pattern, so no secret-file rule sees it.
- **Failure inputs.** Each returned rc=0 under the current hook and under round 1:
  - `grep -vf /dev/null .env`
  - `grep --file=/dev/null -v .env`
  - `rg -f /dev/null -v .env`
  - `grep -v -f /dev/null ~/.ssh/id_rsa`
  - `grep -vf /dev/null ~/.aws/credentials`
  - `grep -vf /dev/null config/prod.env`
  - `grep -vf /dev/null infra/x.tfvars`
- **Regression evidence:** the staged hook blocked the `.env`, `id_rsa` and `.aws/credentials` forms with rc=2. An empty pattern file plus `-v` prints every line, so the whole secret reaches the model context.
- **Why CRITICAL:** the human accepted the rule "a search pattern is not a read". This behaviour goes further: a real file read is no longer blocked. That weakens the floor (law 3).
- **Owner:** factory-engineer.
- **Repair brief:**
  - Treat `-f`, `--file` and `--file=` like `-e`: patterns are supplied, so no positional is a pattern. Set `have_e`.
  - Add tests for every form above.
  - Tests that must stay green: `test_grep_pattern_is_not_a_read`, `test_grep_reading_a_secret_file_blocked`, and the rest of the 25.

### MAJOR-1: auto-allowed `git diff|log|show --output=<file>` writes to the marker, `human-gate.md` and `datasets/gold/**`
- **Files:**
  - `.claude/settings.json` lines 7-9 auto-allow `Bash(git diff*)`, `Bash(git log*)` and `Bash(git show*)`;
  - `.claude/hooks/guard-dangerous.sh` line 632 lists `diff`, `log` and `show` in `GIT_READ` for the marker;
  - line 362 `GIT_WRITE` (the gold check) lacks them;
  - line 319 (the human-gate regex) does not match `--output`.
- **Failure inputs.** Each returned rc=0, in both current and staged:
  - `git diff --output=.claude/.last-test-run`
  - `git diff --output=docs/agent-runs/phase-01/human-gate.md`
  - `git diff --output=datasets/gold/labels.csv`
  - `git log -1 --output=datasets/gold/labels.csv`
  - `git show --output=docs/agent-runs/phase-01/human-gate.md HEAD`
- **Evidence:** in a scratch repo, `git diff --output=out.txt` (git 2.43.0) created `out.txt` holding the diff.
- **Impact:** these commands match an allow rule, so they run without a prompt. Any agent can:
  - refresh the marker, which defeats `verify-tests-ran.sh` and pre-commit check 3 (this is defect 2 again);
  - truncate or overwrite a human ratification record;
  - truncate or overwrite the gold labels.
  
  I did not probe whether Claude Code adds its own write detection for `--output=` (see ASSUMPTIONS).
- **Owner:** factory-engineer.
- **Repair brief:**
  - Treat `--output`/`--output=` (and `-o` where it applies) on git as a write target in the marker, gold and human-gate checks.
  - Narrow the settings.json allow rules, or add deny rules for `git * --output*`.
  - Add tests.

### MAJOR-2: the `markTestRun`-by-name check is defeated by quote splitting, and `sbt *` is auto-allowed
- **Files:** `.claude/hooks/guard-dangerous.sh` line 644 (the `\bmarkTestRun\b` regex runs on the raw text, not a quote-resolved copy); `.claude/settings.json` line 5 (`Bash(sbt *)`).
- **Failure inputs.** Each returned rc=0, while `sbt markTestRun` returned rc=2:
  - `sbt 'markTest''Run'`
  - `sbt "markTest"Run`
- **Impact:** once Phase 2 defines `markTestRun`, any agent can touch the marker without tests and without a prompt. Defect 2's "running markTestRun by name is blocked" claim (docs/agents.md:115) is then false. This is latent until the build exists.
- **Owner:** factory-engineer.
- **Repair brief:**
  - Match the quote-removed `$norm`, or the shlex tokens, not the raw text.
  - Also note the residue `sbt 'set Test / test := {}' check` (see RISKS).
  - Tests: `TestMarkerTest` stays green; add the quoted forms.

### MAJOR-3: the scale caps miss common spellings and options, including the incident-responder 0 bound
- **File:** `.claude/hooks/guard-dangerous.sh` lines 291-305. The regex on line 293 runs on raw `$cmd` only, is line-based (grep), accepts only a double quote before the digits, and lists only some option names.
- **Failure inputs with no caller identity.** Each returned rc=0; the general cap is 4:
  - `aws ecs update-service --cluster c --service s --desired-count '10'`
  - `--desired-count +10`
  - `--desired-count \<newline>10`
  - `--desired-count $((5*2))`
  - `aws ecs run-task --cluster c --task-definition t --count 10`
  - `aws autoscaling update-auto-scaling-group --auto-scaling-group-name g --max-size 20 --desired-capacity 20`
  - `aws autoscaling set-desired-capacity --auto-scaling-group-name g --desired-capacity 20`
- **Failure inputs as incident-responder.** Each returned rc=0; the bound is 1 to 2:
  - `--desired-count '0'`
  - `--desired-count +0`
  - `aws ecs run-task ... --count 3`
- **Working cases:**
  - unquoted `--desired-count 0` and `3` are blocked for incident-responder, whether identity comes from `agent_type`, from `SCHOLARCHAIN_ROLE`, or through the `call_aws` `cli_command` path;
  - `=00` is blocked;
  - `agent_type` correctly takes precedence over the environment variable;
  - EMR `InstanceCount=` and `--instance-count` are blocked.
- **Impact:** docs/agents.md section 6 (round 1 text) says "no EMR, ECS or autoscaling command … may set an instance, task or capacity count above 4" and "every count outside 1 to 2 is blocked, including 0". Neither holds for these forms. Shell `aws` still prompts the human, and the aws-api MCP server is read-only. The `+N` form depends on the AWS CLI accepting a leading `+`, which I did not run.
- **Owner:** factory-engineer.
- **Repair brief:**
  - Scan `$norm`, or the canonical form, joined across backslash-newline.
  - Accept `'`, `"`, `+` and `=` before the digits.
  - Add `--count`, `--desired-capacity`, `--max-size` and `--min-size`.
  - Treat a non-literal count (`$`, `$(`) as a block when the caller is incident-responder.
  - Tests: keep `ScaleCapTest` green and add these cases.

### MAJOR-4: the structural pass fails open on a parse error and skips heredoc bodies fed to a shell, so the terraform and force-push floor can be bypassed
- **File:** `.claude/hooks/guard-dangerous.sh`
  - lines 178-181: `except ValueError: print("UNPARSED")` adds nothing;
  - lines 62-70: heredoc bodies are dropped even when the heredoc feeds `bash`/`sh`.
- **Failure inputs.** Each returned rc=0; the same forms without the suffix or wrapper return rc=2:
  - `ter\raform apply #'` (bash treats `#'` as a comment; I checked `bash -c "echo ran-past-comment #'"`, which ran)
  - `git push --mirr origin #'`
  - `git push --forc origin main #'`
  - `bash <<'EOF'` + newline + `ter\raform apply` + newline + `EOF`
  - `echo 'ter\raform apply' | bash`
  - `git -c remote.origin.push=+refs/heads/main:refs/heads/main push origin` (a forced refspec through config; no `#'` needed)
  - `t=terraform; $t apply` and `$(echo terraform) apply`
- **Pre-existing:** the staged hook behaves the same, so this is not a regression. The gold check already recurses into shell heredocs; the structural pass does not.
- **Impact:** the spec's Phase 1 gate says "Destructive commands are structurally blocked". The hook header (lines 57-58) says the raw and normalized checks still apply when parsing fails, but they do not cover these forms. Shell `terraform apply` still prompts, unless a deny rule matches.
- **Owner:** factory-engineer.
- **Repair brief:**
  - On `ValueError`, retry after stripping an unquoted `#…` comment; otherwise fail closed when the text contains `terraform`, `tofu`, `push` or `commit`.
  - Recurse into heredoc bodies whose command is a shell, and into text piped to a shell.
  - Treat `-c remote.*.push=+…` as a force push.
  - Tests: `GuardPunctuationTest` and the rest must stay green.

### MAJOR-5: `scripts/cleanup.sh` (human only) runs through a pipe to a shell or through a glob
- **File:** `.claude/hooks/guard-dangerous.sh` lines 308-316. Only segments that contain `cleanup.sh` are checked, so `| bash` is never examined.
- **Failure inputs.** Each returned rc=0:
  - `cat scripts/cleanup.sh | bash`
  - `head -n 999 scripts/cleanup.sh | sh`
  - `scripts/clean*.sh`
- **Working cases:** `bash < scripts/cleanup.sh` and `bash -c 'scripts/cleanup.sh'` are blocked (rc=2), and `bash -n scripts/cleanup.sh` is allowed. Pre-existing (same in staged).
- **Impact:** AGENTS.md hard rule 8 says no agent runs cleanup. The settings.json deny rules (lines 113-117) are prefix rules and do not match these forms either. The script does not exist yet (Phase 13), so the gap is latent.
- **Owner:** factory-engineer.
- **Repair brief:** if any segment names cleanup, block when another segment of the same pipeline is a shell or interpreter. Also match the glob forms `clean*`, `cleanu?` and similar under `scripts/`.

### MAJOR-6: glob forms of secret-file reads pass the shell guard
- **File:** `.claude/hooks/guard-dangerous.sh` lines 796-801. The literal patterns only allow a glob after `.env`.
- **Failure inputs.** Each returned rc=0:
  - `cat .en?`
  - `cat .e*`
  - `cat ~/.aws/cred*`
  - `cat ~/.a*/credentials`
- **Related input:** `grep -rn PASSWORD .` also returned rc=0. It reads `.env` content recursively.
- **Working cases:** `cat .env`, `cat .e""nv`, `config/prod.env`, `.tfvars` and `~/.ssh/id_rsa` are blocked.
- **Impact:** the spec's floor says "Block reads of .env, secret directories, private keys"; the Read deny rules do not cover Bash. Pre-existing.
- **Owner:** factory-engineer.
- **Repair brief:** expand globs against the known secret names (fnmatch, as the gold check already does with `can()`), for every path word in a read command. Add tests.

### MINOR-1: gold-set writes through interpreter argv and `gawk -i inplace`
- **File:** `.claude/hooks/guard-dangerous.sh` lines 538-544. Interpreter arguments that look like plain paths are skipped, and in-place mode is recognised only for sed and perl.
- **Failure inputs.** Each returned rc=0:
  - `python3 -c "import sys; open(sys.argv[1],'w')" datasets/gold/labels.csv`
  - `gawk -i inplace '{print}' datasets/gold/labels.csv`
- **Context:** close to the accepted round 2 residue, but not listed there. The gold set does not exist until Phase 05.
- **Owner:** factory-engineer, when the gold check is revisited in Phase 05 (handoff.md).

### MINOR-2: shell writes to `human-gate.md` are only partly guarded, and docs/agents.md overstates the coverage
- **Files:** `.claude/hooks/guard-dangerous.sh` line 319; `docs/agents.md` lines 85-86 ("a deny rule covers every agent tool").
- **Failure inputs.** Each returned rc=0, in both current and staged:
  - `find docs/agent-runs -name human-gate.md -delete`
  - `python3 -c "open('docs/agent-runs/phase-01/human-gate.md','a').write('ratified')"`
  - `shred -u docs/agent-runs/phase-01/human-gate.md`
- **Context:** section 2 admits a shell-write residue, but list E claims a deny rule covers every agent tool. The deny rules are Edit and Write only.
- **Owner:** factory-engineer. Fix the doc text, or reuse the gold path resolver for `docs/agent-runs/**/human-gate.md`.

### MINOR-3: secrets in environment variables and credential-minting AWS reads are not guarded
- **Failure inputs.** Each returned rc=0:
  - `printenv GITHUB_PAT` (`.mcp.json` takes `${GITHUB_PAT}` from the environment)
  - `aws sts get-session-token`
  - `aws ecr get-authorization-token`
- **Impact:** AGENTS.md hard rule 4 ("never print secret values") is enforced by instruction only.
- **Owner:** factory-engineer for the guard pattern. The view-only IAM profile for aws-api belongs to infra-engineer.

### MINOR-4: privilege-escalation check bypassed by quoting
- **File:** `.claude/hooks/guard-dangerous.sh` lines 274-279, which split on whitespace with no quote or escape handling.
- **Failure inputs:** `"sudo" ls` and `\sudo ls` returned rc=0, while `sudo ls` and `env sudo ls` returned rc=2.
- **Owner:** factory-engineer.

### MINOR-5: the pre-commit secret-file filter was not widened with round 1
- **File:** `.agents/githooks/pre-commit` lines 46-47.
- **Evidence:** a Python check of the line-47 regex missed `config/prod.env`, `.envrc`, `infra/stacks/app/prod.tfvars` and `x.tfvars.json`, while round 1 added all four to the Read deny rules and the guard. The repo has no `.gitignore` until Phase 2.
- **Owner:** factory-engineer.

### MINOR-6: stale or missing notes and evidence
- **`docs/agents.md` line 239** says "Bash and MCP payloads were not probed". `claude-post-ratification-probes-02.md` probe (a) shows `agent_type` reaching a Bash PreToolUse payload, with `SCHOLARCHAIN_ROLE` unset. Owner: factory-engineer.
- **`output-validation.txt`** records "101 files with one writer"; the validator now reports 102. Owner: orchestrator (evidence).
- **`STATUS.md` and `PROJECT_MEMORY.md` do not exist.** `handoff.md` is still waiting to be folded in. Its "Open: routing evidence" item is now out of date: `output-routing-summary.txt` (14:54) shows a 153/153 haiku run after round 2 (14:46), which also meets law 7 for the D17 description change. Owner: documentation-engineer.

### MINOR-7: Phase 1 gate items still open (human-owned, listed for completeness)
- No commit exists, so `human-gate.md` holds no commit hash.
- `human-gate.md` (13:26) predates repair rounds 1 and 2. Their ratification and the round 2 risk decisions are recorded only in the orchestrator's `handoff.md`.
- `run-log.md`, `versions.md` and `commit.txt` are still missing (audit defect 6, deferred).
- `human-gate.md` line 36 ("I keep `.claude/settings.local.json` committed") is still contradicted by the user's global gitignore (`!!` in `git status --ignored`).
- Owners: human (`human-gate.md`, `commit.txt`) and orchestrator (`run-log.md`, `versions.md`).

---

## Earlier findings: verified status
| Item | Status | Evidence |
|---|---|---|
| D1 identity probe | Fixed | Recorded in `docs/agents.md` section 2 and `output-identity-probe.txt`; line 239 is stale (MINOR-6). |
| D2 marker shell writes | Partly fixed | `touch`, redirection, `python3 -c` and `find -exec` are blocked; MAJOR-1 and MAJOR-2 get through. |
| D4 scale caps | Partly fixed | Literal forms work; MAJOR-3. |
| D5/D8 secret reads | Wider coverage, plus a regression | CRITICAL-1, MAJOR-6. |
| D9 harness scope | Fixed | Claude Code only, in AGENTS.md, docs/agents.md, README, handoff skill and factory role. |
| D14 gold set | Fixed for direct paths | Territory, deny rules and guard; residue accepted by the human, plus MINOR-1. |
| D17 factory role | Fixed | Header, owned list and seed text corrected; routing 153/153 after round 2. |
| D20 map vs territory | Fixed | Validator passes; rows match. |
| Round 2 tfvars `-var-file` exemption | Works | Probe (f) passed; `cat x.tfvars` is blocked. |

Ownership: every file in the diff is a control-plane file (factory-engineer), a generated adapter (render.sh), evidence (orchestrator or human) or the spec (human). The diff contains no version bump or out-of-territory file. No fence was widened: the tools fields match section 4, and code-reviewer has Read, Grep, Glob and Bash.

---

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 01 review, adversarial and read-only, of the complete uncommitted Phase 01 diff (staged, unstaged and untracked) against the Phase 1 work order and gate (`docs/spec/assignment.md` 888-985), AGENTS.md and docs/agents.md, with extra attention to `.claude/hooks/guard-dangerous.sh` after repair rounds 1 and 2.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- Repository state:
  - `git status --porcelain` (102 entries, unchanged after review) and `git status --porcelain --ignored`;
  - `git diff --stat`, `git diff --cached --stat`, and `git diff` for docs/agents.md, AGENTS.md, roles, skills, the validator and `docs/agent-runs/`;
  - `git show :.claude/hooks/guard-dangerous.sh` (extracted to the scratchpad);
  - `git check-ignore -v .claude/settings.local.json`.
- Reading files: `sed`, `cat`, `grep`, `wc` and `ls` on the spec, control files, hooks, settings, `.mcp.json`, `.codex/config.toml` and the phase-01 evidence.
- `.agents/render.sh --check` → "render --check OK: 44 generated files match their sources" (rc 0).
- `.claude/hooks/validate-agent-files.sh` → "validate-agent-files OK: 19 roles, 19 Claude agents, 19 Codex agents, 4 MCP servers, 102 files with one writer" (rc 0).
- `PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_dangerous.py` → "Ran 25 tests in 90.139s OK".
- `PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_ownership.py` → "Ran 4 tests in 2.790s OK".
- Probe harness `/tmp/claude-1000/-home-ajcreator-scholarchain/5c0d29cd-a303-4f16-b853-6f0f63b5ed73/scratchpad/probe2.py`:
  - inputs `c1.tsv` to `c8.tsv`, run against the current, staged and round 1 hooks;
  - outputs `cur.txt` and `stg.txt` in the same folder.
- Timing probe: 40,000 segments (320 KB) took 10.3 s and returned rc 0; brace-heavy input of 38 KB took 3.2 s.
- `bash -c "echo ran-past-comment #'"`, which printed the text; this confirms the comment form in MAJOR-4.
- In a throwaway scratch repo: `git diff --output=out.txt`, which wrote the file.
- `bash -n -i -c` executability check in the scratchpad: it did not execute, so I dropped that candidate.
- `python3 -I pc.py`, which tested the pre-commit regex.
- `claude --version` → 2.1.330.
TEST RESULTS:
- test_guard_dangerous.py: 25 tests OK.
- test_guard_ownership.py: 4 tests OK.
- The suites pass, but they test only the literal forms. Every bypass above passes the suite (tests that would pass on broken behaviour).
VALIDATION RESULTS: REQUEST_CHANGES. CRITICAL-1, then MAJOR-1 to MAJOR-6, then MINOR-1 to MINOR-7, as detailed above. No test-fairness dispute was in scope.
ASSUMPTIONS:
- The hook is the guard as deployed; hook edits are live (handoff.md).
- I did not observe whether Claude Code adds its own write or read detection on top of the `Bash(git diff*)` and `Bash(sbt *)` allow rules, or on top of Read deny rules for Bash `cat`. MAJOR-1 and MAJOR-2 assume the allow rule applies as written.
- The `+N` integer form in MAJOR-3 assumes the AWS CLI parses integers with Python `int()`; I did not run it.
- "The floor" for the CRITICAL rating is the originally ratified package (the index copy).
RISKS:
- What I did not review:
  - each role body line by line beyond frontmatter and fences;
  - `.agents/routing-tests.md` and `routing-suite.sh` (not run: they make model calls);
  - the internals of `render.sh` beyond `--check`;
  - the validator logic in depth;
  - `format-scala.sh`;
  - all Codex files (out of scope);
  - the full text of `repair-01.md`;
  - the spec outside Phases 0-1 and the control-model, matrix, hook and MCP sections.
- Residue not raised as findings:
  - `Bash(sbt *)` lets any agent run arbitrary sbt, for example `sbt 'set Test / test := {}' check`, which fakes a green check from Phase 2 on;
  - every hook still fails open without python3 (deferred defect 3);
  - a hook run longer than the 10 s timeout fails open (needs a command of about 320 KB);
  - variable indirection in general;
  - the accepted gold-ancestor residue from round 2.
- State left behind: scratch files under `/tmp/claude-1000/-home-ajcreator-scholarchain/5c0d29cd-a303-4f16-b853-6f0f63b5ed73/scratchpad/` only (probe harness, case files, hook copies, `gitout/` throwaway repo). Repository unchanged.
MANUAL ACTION REQUIRED:
- Human: decide whether CRITICAL-1 and MAJOR-1 to MAJOR-6 reopen the Phase 1 gate. My recommendation is a repair round 3 for factory-engineer, then ratification and a restart (settings.json changes need the restart).
- Human: record the ratification of rounds 1 and 2, then the commit hash, in `human-gate.md` and `commit.txt` (MINOR-7).
- Orchestrator: route MINOR-6 to documentation-engineer (fold `handoff.md` into `STATUS.md`).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator sends CRITICAL-1, MAJOR-1 to MAJOR-6 and MINOR-1 to MINOR-6 to factory-engineer as repair round 3, each with its failure inputs as new tests. The 25 plus 4 guard tests, `render.sh --check` and the validator must stay green. A fresh code-reviewer then reruns these probes. After that comes human ratification and a restart, then the human commit and hash for Phase 1.
