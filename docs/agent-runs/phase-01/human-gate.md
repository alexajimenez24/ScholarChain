# Phase 01 human gate

I set up the agent system by hand from a prepared package instead of a factory-engineer
run in this repository. The work order it answers is in `prompt.md`. I read the roles,
`AGENTS.md`, `CLAUDE.md`, `docs/agents.md`, `.claude/settings.json`, the hooks and
`.mcp.json` before accepting it, and I reviewed the diff of every repair round before
ratifying it. The full sequence of steps is in `run-log.md`.

## What was checked

- Mechanical checks are in `output-validation.txt`: `render --check`, the validator (one
  writer per file), `bash -n` on every hook and script, every JSON file, the guard test
  files, and the guard and ownership probes.
- The spec probes P1 to P6 are in `claude-post-ratification-probes.md`. All passed. P1
  and P4 were refused by instructions before any tool ran; the hook-level block for P1 is
  in `output-validation.txt`, and probe (c) in round 2 later showed the settings.json
  Read deny firing as a permission error. I chose not to re-run P4 itself.
- The agent-identity probe is in `output-identity-probe.txt`. On Claude Code 2.1.330,
  guard-ownership named service-engineer when it blocked a write outside its territory,
  so subagent identity reaches the hook. Probe (a) later showed the same for Bash calls.
- factory-engineer audited the installed system (`agent-report.md`, STATUS COMPLETE): 1
  blocking defect and 15 should-fix items.
- Routing was 153/153 under Claude Code with haiku after both early repair rounds
  (`output-routing-summary.txt`).
- The post-ratification probes (a) to (f) passed 10 of 10
  (`claude-post-ratification-probes-02.md`). Probes (g) to (i) after round 3 are in
  `claude-post-ratification-probes-03.md`.

## Repairs and reviews

- Repair round 1 (`repair-01.md`) fixed audit defects 1, 2, 4, 8, 9, 14, 17 and 20. I
  accepted the grep/rg search-pattern scrub, which narrows a false positive.
- Repair round 2 (`repair-02.md`) narrowed the gold-set check to `datasets/gold/`,
  allowed `-var-file` for terraform plan, validate and console, and fixed the harness
  text in `docs/agent-runs/README.md`. I ratified rounds 1 and 2 at 14:53 and then
  re-ran the routing suite (153/153).
- A fresh code-reviewer returned REQUEST_CHANGES (`review-report.md`: 1 critical, 6
  major, 7 minor).
- Repair round 3 (`repair-03.md`) fixed CRITICAL-1, MAJOR-1, MAJOR-6, MINOR-2, MINOR-3,
  MINOR-5 and the stale probe note from MINOR-6.
- A fresh scoped re-check returned REQUEST_CHANGES (`review-report-02.md`). I ratified
  round 3 knowing its findings. The repair loop limit of 3 rounds was reached, and I chose
  not to authorise a fourth round.

## Accepted, with known gaps

The agent system is accepted as committed. The following are known gaps that I accept for
Phase 1 and will address later:

- R3-CRITICAL-1 (`review-report-02.md`): a regression from round 3. When the guard cannot
  parse a command, it no longer falls back to scanning the raw command, so a secret read
  with a trailing `#'` or an unmatched apostrophe (for example `cat .env #'`) gets past
  guard-dangerous. The settings.json Read deny rules still block the Read tool on secret
  files, and no secret file exists in the repository. I am addressing this in Phase 11
  (adversarial testing), and until then I keep Claude Code's auto mode off in this
  repository so that the permission prompt stays in front of such commands.
- R3-MAJOR-1: the glob check is skipped inside `bash -c`, `sh -c`, `eval` and
  `find -exec`. Phase 11.
- R3-MINOR-1: quote splitting and unlisted writers get past the human-gate.md write
  check, and `docs/agents.md` list E and `docs/agent-runs/README.md:59` still claim more
  coverage than the hook has. Phase 11.
- R3-MINOR-2: `/usr/bin/env`, `busybox env` and `printenv DB_PASSWORD` get past the
  environment-variable guard. Phase 11.
- Deferred from the first review: MAJOR-2 (markTestRun quote splitting, Phase 02),
  MAJOR-3 (scale-count forms; the real caps are the AWS-side limits in Terraform, Phase
  12), MAJOR-4 (disguised terraform and force-push forms, also present in the original
  hook; the settings.json deny rules remain the backstop), MAJOR-5 (cleanup.sh piping,
  Phase 13), MINOR-1 (gold-set interpreter writes, Phase 05), MINOR-4 (quoted sudo,
  residual).
- Declined in round 2: the gold-set bypasses such as `rm -rf datasets`, to revisit in
  Phase 05 when the gold set exists. `datasets/g*` stays blocked.
- Deferred from the audit: defect 3 (python3 in the toolchain ledger, Phase 02), defects
  10 and 11 (Codex, out of scope), defect 12 (terraform MCP server, Phase 12), defects
  13, 15 and 16, and nits 18, 19 and 21 to 29.

## Other remaining risk

- Claude Code is the only harness used. The Codex adapters are rendered and validated
  but not probed.
- terraform-mcp-server is not installed (needed from Phase 12), and GITHUB_PAT is not
  set, so the GitHub MCP server does not connect.
- The aws-api MCP server is reaching end of development; moving to the AWS MCP Server is
  a later factory-engineer change.
- A guard false positive blocks some read-only commands that name the test marker (for
  example `which` with the marker path).

## Manual changes

- I created the dummy `.env` for P4 and the `probe/Probe.scala` fixture for P5, and
  removed both after the probes.
- I removed the stray `.claude/hooks/__pycache__/` left by the audit.
- `.claude/settings.local.json` is per-machine and git-ignored, as `docs/agents.md`
  says; it is not committed.
- Manual changes to agent-owned files: none.

## Commit

Accepted commit: COMMIT_HASH

Phase 1 is complete. I approve starting Phase 2.
