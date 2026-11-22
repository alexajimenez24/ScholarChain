HARNESS: claude-code (Claude Code 2.1.330) MODEL: opus 5.5 (claude-opus-5-5), high tier, effort high (role default, no override)

Phase 01 audit of the installed agent system (read-only)

Bottom line: the mechanical checks are all green. render --check, the validator, the pre-commit gate, every JSON and TOML parse, YAML frontmatter, bash -n, and executable bits on disk and in the index all pass. Every one of the 95 tracked files has exactly one writer. The MCP grants match section 5. code-reviewer has no Write or Edit tool.

The gaps are in enforcement depth and evidence, not structure. There is 1 blocking item and 15 should-fix items; the rest are nits.

I broke the no-write constraint once. python3 -m py_compile .claude/hooks/test_guard_dangerous.py created the untracked file /home/ajcreator/scholarchain/.claude/hooks/pycache/test_guard_dangerous.cpython-312.pyc. I did not delete it, because deleting was also forbidden. The human should run rm -r /home/ajcreator/scholarchain/.claude/hooks/pycache. Nothing else in the repo changed: the index is unchanged and there is no unstaged diff. Every executing check after that ran in a scratchpad copy.

A. Phase 1 work order items (spec docs/spec/assignment.md:900-963)

CLAUDE.md. All items are PRESENT. The content lives in AGENTS.md, which CLAUDE.md:2 imports.
identity: AGENTS.md:8
module boundaries: AGENTS.md:23-46
sbt check as definition of done: AGENTS.md:50
one owner per artifact: AGENTS.md:60
feature tests: AGENTS.md:64
provenance: AGENTS.md:66
secrets: AGENTS.md:68
Terraform only: AGENTS.md:71
no console mutation: AGENTS.md:74
imports docs/agents.md: CLAUDE.md:3
docs/agents.md. All items are PRESENT.
ownership table for all 18 required agents plus toolchain-engineer: :16-48, territory block :89-183
lifecycle table: :208-234
all 7 escalation classes: :583-602
no subagent-to-subagent calls, hub routing: :12-13
Agent files.
PRESENT: 19 roles with project-vocabulary descriptions.
PRESENT: explicit tools fields.
PRESENT: code-reviewer tools are Read, Grep, Glob, Bash (.agents/roles/code-reviewer.md:8).
PRESENT: infra-engineer has authoring tools plus read-only aws-api, terraform registry and aws-docs, and no apply (deny at settings.json:49-54, plus the guard).
PARTIAL: deploy-engineer is limited to approved scripts and environments by instructions, preconditions and the human gate only. It holds plain Bash, and the scripts do not exist yet.
PRESENT: test-engineer is limited to test territory (docs/agents.md:160-161, guard-ownership probes rc 2/0).
PRESENT: decline-and-route, because every role has a Boundaries section.
PRESENT: report contract with all 14 fields in all 19 roles. The factory-engineer header is missing (defect 17).
settings.json and hooks.
PRESENT: formatter, settings.json:122-129
PRESENT: dangerous-command guard, :108-114
PRESENT: Stop and SubagentStop, :131-146
PRESENT: validator, :127
PRESENT: allow and deny lists, :4-105
PARTIAL: secret and .env deny, :25-42 (defect 8)
PRESENT: terraform deny, :49-54
PRESENT: force push deny, :55-61
PRESENT: destructive S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS and EMR deny, :66-96. EMR scale-out is blocked only by the guard.
.mcp.json.
PRESENT: github, :3-11
PRESENT: aws-api with READ_OPERATIONS_ONLY, :12-24
PARTIAL: terraform, :25-32. terraform-mcp-server is not installed on this machine (defect 12).
PRESENT: aws-docs, :33-41
PRESENT: trust boundaries and environment variables, docs/agents.md:530-578 (nit 25)
Commands. PRESENT. All five plus handoff exist. Each names its agent and gates: .agents/skills//SKILL.md lines 6-17, rendered to .claude/commands/.md.
docs/agent-runs/README.md. PRESENT. Evidence format at :1-78; hidden reasoning is never stored at :81.
Closing items.
PRESENT: no build or application files.
PRESENT: validations (re-run by me, section C).
PARTIAL: no factory-engineer report, complete diff or per-file justification exists (defect 6).
PARTIAL: probes P1 to P6 are recorded, but P4 is partial (defect 5) and the identity probe is missing (defect 1).

B. Defects, numbered

BLOCKING. docs/agents.md:191-203. The agent-identity probe was never run or recorded, but the doc says to record observed versions and payloads "before ratification". It still reads "Expected:". guard-ownership enforcement for subagents depends on agent_type being in the payload. The phase-01 ownership probe (output-validation.txt) piped a made-up payload into the hook; it does not prove the harness sends agent_type. human-gate.md:40 ratified anyway.
should-fix. .claude/hooks/guard-dangerous.sh:297-317 covers shell writes to human-gate.md and secret reads, but not shell writes to .claude/.last-test-run.
Probe: touch .claude/.last-test-run gives rc=0, and so does echo x > .claude/.last-test-run.
One command therefore defeats both verify-tests-ran.sh and the pre-commit test check (.agents/githooks/pre-commit:29-43).
The territory none row (docs/agents.md:115) covers only file tools.
should-fix. Both guards fail open without python3.
guard-dangerous.sh:16-23: probe terraform destroy with python3 absent from PATH gave rc=0.
guard-ownership.sh:16: the same condition gave rc=127, a non-blocking error.
Codex has no deny rules, so there the guard is the only floor.
python3 is not in the toolchain list (toolchain-engineer.md:2-6), and the validator needs Python 3.11 or later for tomllib.
should-fix. The scale cap does not match the matrix.
The incident-responder must-never "exceed the scale bound" (1 to 2 tasks) claims enforcement by "guard-dangerous (scale cap)" (docs/agents.md:518).
The cap is SCALE_MAX=4 (guard-dangerous.sh:276). Probe: aws ecs update-service ... --desired-count 3 gives rc=0.
should-fix. Probe P4 (claude-post-ratification-probes.md:10) was refused at the instruction level only. "The settings.json deny rule itself was not exercised." The spec (assignment.md:971) requires the permission policy to block it.
should-fix. The phase-01 evidence lacks files that docs/agent-runs/README.md:13-24 requires in every phase folder: run-log.md, agent-report.md, versions.md (also spec :816) and commit.txt. There is no record of the complete diff or the per-file justification the work order asks for (:963).
should-fix. human-gate.md:36 says "I keep .claude/settings.local.json committed". It is not in the index: the user's global ignore ~/.config/git/ignore:1 matches it. On another clone it would not be ignored either, because the repo has no .gitignore. docs/agents.md:80-81 calls it "unowned" and "git-ignored", but territory line 118 gives it to the human.
should-fix. There are gaps in secret-read coverage, in settings.json deny (:25-42) and in the guard (:302-311). All of these probes gave rc=0:
cat config/prod.env
cat .envrc
cat ~/.codex/auth.json
cat ~/.config/gh/hosts.yml
cat ~/.aws/sso/cache/token.json
cat infra/stacks/app/prod.tfvars
The Read deny list has no /*.env, .envrc, *.tfvars, ~/.ssh/, ~/.aws/sso/, ~/.aws/cli/cache/, ~/.codex/auth.json, ~/.claude/.credentials.json or gh hosts.yml.
should-fix. Codex was never exercised: human-gate.md:30 says "I did not run anything under Codex", and the codex CLI is not installed.
Law 7 (routing green under every harness) is unmet for Codex.
Not done: Codex hook trust, codex exec --strict-config (.codex/config.toml:6-8) and the hooks.json matcher names (Bash, apply_patch).
A reviewer spawned under Codex inherits sandbox_mode = "workspace-write" (config.toml:13), not the --sandbox read-only that docs/agents.md:462 relies on.
Either record a Claude-only scope in the matrix (a human-ratified change) or run the Codex checks.
should-fix. guard-ownership.sh:41 reads the Codex patch only from tool_input.command or tool_input.patch. If the patch arrives under any other key (probe: tool_input.input), the hook gives rc=0 and fails open. This is unverified until the Codex payload probe in defect 1 runs.
should-fix. Open [PROPOSED - verify against harness docs] items:
docs/agents.md:294-301: model override mechanics for the Agent tool and spawn_agent
.codex/config.toml:37-40: env_vars, enabled_tools
The effort frontmatter key in .claude/agents/*.md and [agents] max_concurrent_threads_per_session (config.toml:20-21) are also unprobed.
should-fix. The terraform MCP server is not usable yet.
terraform-mcp-server is MISSING from PATH, so the server cannot start.
The 1.4.1 pin is only a claim in docs/agents.md:561-563, and no toolchain/ ledger exists.
The terraform MCP tool names in infra-engineer.md:13-15 (for example search_provider_docs) are not checked against the server's real tool list.
should-fix. All six commands set allowed-tools: Read, Grep, Glob (.agents/skills/*/SKILL.md:4). If Claude Code treats this as a restriction rather than pre-approval, the commands cannot delegate (no Agent tool), and the handoff fallback cannot write docs/agent-runs/phase-NN/handoff.md (handoff/SKILL.md:28). This needs a probe.
should-fix. The gold-set labels have no owner. The human "labels the gold set" (docs/agents.md:305) and the spec says labels are human-reviewed (assignment.md:1245). The ownership map and territory have no human-owned path for them, so entity-resolution-engineer could overwrite them.
should-fix. Two routing collisions are not covered by the corpus (.agents/routing-tests.md):
"Run the EMR burst-scaling experiment": deploy-engineer's description says "to run jobs on EMR" (deploy-engineer.md:5), and experiment-engineer's says "evaluation runs for EMR baseline and burst scaling" (experiment-engineer.md:4). The corpus only has "Write the manifest..." (:100).
"Write the uPickle wire-format contract test for WorkRecord": domain-engineer owns "serialization contracts" with "contract tests" as its gate (docs/agents.md:27), and test-engineer owns tests/contracts. The corpus only has a cross-module row (:82).
should-fix. settings.json:14-19 auto-allows terraform plan and fmt and validate for every agent and the main session. The matrix grants plan only to infra-engineer (docs/agents.md:466). A plan can run external data-source programs and needs credentials.
should-fix / nit. .agents/roles/factory-engineer.md has four problems:
no HARNESS: / MODEL: header in its report (:116-139), although AGENTS.md:142-146 requires one;
"Owned artifacts" lists .claude/agents/* and .claude/commands/* (:21), which are generated-only;
the "seed file is the single exception" text in law 5 is stale (:62);
the description claims "the generated .claude/** and .codex/** adapters" (:5-6).
nit. Corpus header (:11-13) says "three hard negatives per agent". Actual counts: code-reviewer 0, domain 0, factory 1, service 1, and 2 each for dataset, documentation, experiment and spark.
nit. These have no owner (orphans): Makefile, root .sbt other than build.sbt (for example version.sbt), .github/ outside workflows, .tool-versions. The validator's sample list (validate-agent-files.sh:215-231) does not cover them.
nit. The ownership map and the territory block disagree:
:44 says docs/agent-runs/** is "human evidence", but the territory gives the orchestrator write (:122);
.sbtopts and .jvmopts (territory) are missing from the map row at :24;
the spec has hadoop-engineer own "Hadoop integration tests" (assignment.md:334), but here they fall in test-engineer's tests/integration.
nit. project/Versions.scala has two writers by design (build-engineer allow, dependency-updater values). writers() at validate-agent-files.sh:205-212 ignores values, so the one-writer audit never sees the split.
nit. verify-tests-ran blocks only once: stop_hook_active lets any agent stop on its second attempt, so pre-commit is the hard gate. It also asks for sbt check, which does not exist until Phase 2, and without a .gitignore the untracked project/target files will keep re-triggering it.
nit. Guard false positives: a read-only git config --get core.hooksPath is blocked, and so is grep with a protected phrase or .env in its pattern. Both happened to me during this audit.
nit. Single-item data deletes (aws dynamodb delete-item, aws s3api delete-object) are not in the guard or the deny list. The shell aws prompt is the only gate.
nit. docs/agents.md:566 says the terraform MCP server has "Environment: none", but both configs set LOG_LEVEL=error. Also, the Claude side forwards ${AWS_API_MCP_PROFILE_NAME:-...} while Codex pins it (documented, but asymmetric).
nit. code-reviewer is meant to write nothing, but it holds Bash plus the auto-allowed sbt *, and sbt check touches the marker and target/. docs/agents.md:521-528 accepts this residue.
nit. Deny-rule prefix variants are inconsistent (aws * ... exists for some verbs only). The guard's aws canonicalization covers the gap (probe aws --profile x dynamodb delete-table gave rc=2).
nit. rag-engineer lacks mcp__aws-docs__search_table, which every other aws-docs holder has. toolchain-engineer's WebSearch and WebFetch are not named in its matrix row.
nit. human-gate.md:13 records "92 files with one writer". It is 95 now.

STATUS: COMPLETE

OWNER: factory-engineer

WORK ORDER: Phase 01 audit of the installed agent system (read-only), items 1 to 9.

FILES CREATED: none intended. Stray file, created by mistake and disclosed above: /home/ajcreator/scholarchain/.claude/hooks/pycache/test_guard_dangerous.cpython-312.pyc. Scratch only, outside the repo: /tmp/claude-1000/-home-ajcreator-scholarchain/f6ecd053-dac2-489d-8664-6599c456cc83/scratchpad/{repo/, owners.py, probes.py, pats.txt, nopy/, tmp/}

FILES MODIFIED: none

COMMANDS RUN (all output observed):

Live repo, read-only: git status, git ls-files -s, git check-ignore, cat .git/config, stat, cat, sed, grep, awk, wc on control files; python3 -I parsing JSON (json), TOML (tomllib) and YAML frontmatter (PyYAML 6.0.1); bash -n on 8 scripts; owners.py (git ls-files plus territory glob matching); command -v and claude --version.
Live repo, wrote a file: python3 -I -m py_compile .claude/hooks/test_guard_dangerous.py (created the stray .pyc).
Scratchpad copy (cp -a of the repo, TMPDIR and PYTHONDONTWRITEBYTECODE set): .agents/render.sh --check, .claude/hooks/validate-agent-files.sh, .agents/githooks/pre-commit, python3 -I .claude/hooks/test_guard_dangerous.py, and probes.py feeding made-up payloads to guard-dangerous, guard-ownership and verify-tests-ran.
Not run: .agents/routing-suite.sh. It spawns claude -p / codex exec model calls and writes a checkpoint and results files.
Blocked by my own session's guard, so not run: git config --get core.hooksPath (I read .git/config instead), and two greps whose patterns contained protected strings.

TEST RESULTS: test_guard_dangerous.py: "Ran 1 test ... OK".

VALIDATION RESULTS:

JSON OK: .agents/models.json, .claude/settings.json, .codex/hooks.json, .mcp.json, .claude/settings.local.json.
TOML OK: .codex/config.toml and all 19 .codex/agents/*.toml.
YAML frontmatter OK with unique names: roles 19, skills 6, .claude/agents 19, commands 6. All tiers are high or standard and mapped for claude and codex.
bash -n ok on all 5 .claude/hooks/*.sh, render.sh, routing-suite.sh and pre-commit. Each is executable on disk and 100755 in the index; test_guard_dangerous.py is 100644 and run by python. core.hooksPath = .agents/githooks.
render --check, verbatim: "render --check OK: 44 generated files match their sources".
validate-agent-files, verbatim: "validate-agent-files OK: 19 roles, 19 Claude agents, 19 Codex agents, 4 MCP servers, 95 files with one writer".
pre-commit: rc=0.
Ownership audit: all 95 tracked files plus my 1 stray file have exactly one writer (45 factory-engineer, 44 generated, 5 orchestrator, 2 human). The only multi-writer path is project/Versions.scala, by design. The orphans are in nit 19.
Fences: they match the section 4 matrix. code-reviewer has no Write, Edit or NotebookEdit; dependency-updater has Edit without Write; deploy-engineer has Write without Edit. MCP grants match section 5: github orchestrator-only; aws-api for infra, deploy, experiment and incident; terraform for infra; aws-docs for infra, storage, rag and documentation.
Guard probes: 31 Bash command probes plus 1 call_aws probe. Every expected block returned rc=2, and the gaps are listed in defects 2, 3, 4, 8 and 24.
Ownership probes: 20 of 20 matched expectations; the Codex key and python3 cases are in defects 3 and 10.
Routing: not re-run by me. The packaged evidence shows claude/haiku 153/153 (output-routing-summary.txt), and codex was never run.

ASSUMPTIONS: The packaged phase-01 files in docs/agent-runs/phase-01/ are the human's evidence as written. Claude Code semantics I could not probe (allowed-tools, the effort frontmatter key, deny-rule coverage of Grep and Glob) are reported as unverified, not as defects. In scope was the Phase 1 work order only (spec :900-963), plus the hook and MCP policy sections it cites.

RISKS:

Nothing was fixed, by design.
The stray .pyc remains in the live repo. It is untracked and harmless to the validator, but it shows up in git status.
The scratch copy of the repo remains under the scratchpad.
The identity probe, P4 with the deny rule, all Codex checks and the routing suite were not run.

MANUAL ACTION REQUIRED:

Human: rm -r /home/ajcreator/scholarchain/.claude/hooks/pycache.
Human: decide whether defect 1 (identity probe before ratification) reopens the Phase 1 gate.
Human: decide on Codex scope (defect 9) and the settings.local.json commit statement (defect 7).
Re-run P4 so the Read deny rule itself fires.
Any repair goes through a factory-engineer work order with a ratified matrix change where needed (defects 4, 14, 16, 20). After that: ratification, a restart of every harness, and Codex hook trust if Codex stays in scope.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: The human reviews this audit and decides on defect 1 and the should-fix items. The orchestrator then routes fixes to factory-engineer (control plane), toolchain-engineer (python3, terraform-mcp-server, scalafmt pins) and the orchestrator itself (missing phase-01 evidence files) before Phase 2 starts.

No constitutional change is part of this report.
