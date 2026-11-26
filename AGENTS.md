# ScholarChain - instructions for every agent and harness

<!-- Owner: factory-engineer. Constitutional: in force only after human ratification
(commit) and a restart of every harness. Canonical for Claude Code (through the
CLAUDE.md import) and Codex (native). Keep under 32 KiB. Status and module detail live
in STATUS.md, PROJECT_MEMORY.md and modules/*/MODULE.md, never here. -->

ScholarChain is a Scala 3, Hadoop, Spark, AWS, Graph-RAG project.
It processes DBLP plus open scholarly datasets with Hadoop MapReduce and Spark on EMR,
stores the coauthorship graph in Neptune, state in DynamoDB and semantic context in S3
Vectors, and answers collaboration questions with evidence-backed claims.
The build is an sbt multi-project build; uPickle is the only JSON library.

## Read first

- Read `docs/agents.md` now: ownership map, write territories, lifecycle routing,
  authority matrix, MCP trust boundaries, escalation and loops.
  Codex cannot import files, so every Codex agent reads it directly.
- Read the notes files your work order names before acting: `STATUS.md` (where the last
  step stopped), `PROJECT_MEMORY.md` (plan and decisions) and the `MODULE.md` of every
  module you touch.

## Module dependency boundaries

| Module (folder under `modules/`) | sbt project | May depend on |
|---|---|---|
| `core` | `core` | external libraries only |
| `hadoop` | `hadoop` | `core` |
| `entity-resolution` | `entityResolution` | `core` |
| `spark` | `sparkAnalytics` | `core` |
| `storage` | `storage` | `core` |
| `rag` | `rag` | `core`, `storage` |
| `services/common` | `servicesCommon` | `core` |
| `services/query` | `queryService` | `core`, `rag`, `storage`, `servicesCommon` |
| `services/graph` | `graphService` | `core`, `storage`, `servicesCommon` |
| `services/worker` | `embeddingWorker` | `core`, `storage`, `servicesCommon` |

- Pipeline stages (Hadoop, entity resolution, Spark, loaders) exchange data only through
  versioned `core` contracts on S3, never through code imports of one another.
- No module depends on a service project; the three deployables (`queryService`,
  `graphService`, `embeddingWorker`) never depend on one another, only on
  `servicesCommon` (http4s middleware, auth, metrics); the module graph has no cycles.
- Services run http4s Ember on cats-effect, one image per deployable, behind an API
  Gateway HTTP API with a VPC Link to Cloud Map; auth is a Cognito JWT.
- `datasets/`, `infra/`, `scripts/`, `tests/`, `experiments/` and `load-tests/` are not
  sbt modules of the runtime.

## Commands

- `sbt check` is the repository definition of done: format check, compile, tests,
  contract tests that need no AWS, then `markTestRun` touches `.claude/.last-test-run`.
  Run it before you report any source change complete.
- `sbt fmt`, `sbt packageJobs`, `sbt dockerLocal`: format, build job jars, build the three service
  images.
- `.claude/hooks/validate-agent-files.sh`, `.agents/render.sh --check` and
  `.agents/routing-suite.sh`: control-plane checks (factory-engineer).
- Long commands run in the foreground under `timeout`, one sbt at a time:
  `timeout 45m sbt check` for the full check, `timeout 30m sbt <project>/test` (or
  `testOnly`) for project tests, and `timeout 30m` for a local pipeline run (`runMain`).
  Every work order writes its long sbt and pipeline commands with that `timeout`.
  Never background, poll or wait-loop on a run (the guard blocks polling loops); if the
  harness moves a command to the background, start nothing else and wait for it.

## Hard rules

1. One owner per artifact class (`docs/agents.md`).
   Never create or modify an artifact outside your territory; decline, name the owner,
   and let the orchestrator route it.
   Subagents never invoke one another.
2. Every behavior change ships with a feature test in the same change, and `sbt check`
   passes before you report it done.
3. Every data transformation and every runtime claim carries provenance: source dataset
   record, run ID and policy or prompt version for data; evidence IDs for claims.
4. Secrets come only from AWS Secrets Manager at runtime or approved local placeholders
   named `${VAR}`.
   Never read, print or commit `.env` files, keys, credentials or secret values.
5. All AWS infrastructure is Terraform under `infra/`: layered root stacks in
   `infra/stacks/` (foundation, data, compute, app, observability), shared modules in
   `infra/modules/`.
   Never mutate the cloud through the console or an ad hoc CLI write as an
   implementation path.
   Raw `terraform apply` and `destroy` are blocked for every agent; the only apply path
   is `scripts/deploy.sh`, run by deploy-engineer after the human gate.
6. Constitutional files (`AGENTS.md`, `CLAUDE.md`, `docs/agents.md`, `.agents/**`,
   `.claude/**`, `.codex/**`, `.mcp.json`, `docs/agent-runs/README.md`) change only
   through factory-engineer and take effect after human ratification and a restart.
   `.claude/agents/`, `.claude/commands/` and `.codex/agents/` are generated by
   `.agents/render.sh`; never edit them by hand.
7. Report only commands whose successful output you observed.
   Retry a failing check at most 3 times, then report BLOCKED-ON with the owner and the
   verbatim evidence, and stop.
8. No agent approves its own work, commits, or runs `scripts/cleanup.sh`.
   The human ratifies, commits and cleans up.

## AWS and cost

- Region: `us-east-2`.
- Budget: $25 for the whole course, a hard cap.
  Every apply, deploy or bounded run that adds cost needs the human's sign-off, with its
  estimate counted against what is left.
- Infrastructure is Terraform only.
- Ephemeral resources shut themselves down when a run ends, after evidence is archived:
  EMR clusters auto-terminate and ECS services scale to zero.
- Full cleanup is `scripts/cleanup.sh`, run only by the human.

## Agents, models and harnesses

- Roles live once in `.agents/roles/<name>.md` with a `tier` (high or standard), never a
  model name; `.agents/models.json` maps tiers to models per harness.
- The orchestrator (main session) runs on the high tier: Claude Code `opus` with effort
  high, Codex `gpt-6.2-sol` with reasoning effort high.
- Per-task model: a role's tier is its default, and for a high-tier role its floor.
  For a small, mechanical task the orchestrator may run a standard-tier role on `low`
  (`haiku`, `gpt-6-luna`), or raise any role to high, and says so in the work order.
  code-reviewer is standard for Phases 12 to 16 and never runs on `low`; Phase 17's
  final review runs it on high (`docs/agents.md` 4, code-reviewer).
  This authorizes the Agent-tool and `spawn_agent` model overrides (`docs/agents.md` 4).
- The orchestrator never does a role's work inline; it delegates with a work order and
  reads the report.
  If a harness cannot spawn the role as a subagent, the human starts a fresh session with
  `SCHOLARCHAIN_ROLE=<role>`, the session reads `.agents/roles/<role>.md`, acts only as
  that role, treats its tools line as a hard fence, and ends with its report.
- Claude Code is the only harness used in this project (`docs/agents.md`, harness
  scope).
  The Codex adapters stay rendered and validated, but they are out of scope and
  unprobed.
  A fresh session must be able to pick up where the last one stopped: nothing lives only
  in a session's private memory; `STATUS.md` and the saved reports carry the state.
- code-reviewer and test-engineer run in the same harness that did the work, always as a
  freshly spawned agent with fresh context, never a continued or author context.

## Phases run as small tasks

- At the start of a phase the orchestrator splits the phase work order into small
  tasks, each complete and testable on its own, with one owner, its inputs, what done
  means and the check that proves it.
  A task is small enough that a cut-off loses very little.
- documentation-engineer writes that task list into `STATUS.md` before the first task.
- The orchestrator runs the tasks one by one, routing each to its owner; the review and
  test loops run inside the phase.
- After every task, documentation-engineer updates `STATUS.md`: tasks done, the task
  running, the next task and anything left half done, so a fresh session can resume.
- The orchestrator does not ask or tell the human anything until the whole phase is
  done, except for what is the human's: cost sign-off, a Terraform plan, a spec question
  the spec cannot answer, a loop that failed 3 times, or an act only the human can do
  (root installs, the AWS login, ratification).
- At the end, the orchestrator gives the human one phase report in the main session,
  with every agent report in full, not summarized.
- Before a cut-off (usage or context limit), run `/handoff` (`.agents/skills/handoff/`).
  A usage-limited agent can write nothing more, so `STATUS.md` after every task is the
  checkpoint; the orchestrator gives the exact command to continue in a fresh session.

## Reports

Every agent ends with the report fields in its role file: a header naming the harness
and model, then STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN,
TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED,
BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE and NEXT GATE.
What was not done and the state left behind go under RISKS.
Write a report file, and any other prose, with the Write tool where your role has it; a
role without Write (code-reviewer) puts the report in its hand-back message.
Never write text with a shell heredoc: guard-dangerous reads every heredoc body as
commands and blocks what it cannot parse (`docs/agents.md`, fail-closed rule).
Hidden model reasoning is never stored; phase records hold work orders, final reports,
command output, review findings and human decisions (`docs/agent-runs/README.md`).

## Commits (written by the human or drafted for the human; agents never commit)

- Format: `type(scope): description`, for example `feat(spark): add k-core decomposition`.
- Types: feat, fix, chore, docs, test, refactor, build, ci, perf.
- Scope: the module, in as few words as possible.
- The body names the phase and the agent, for example `phase 06, spark-engineer`.
- Messages are written in the human's voice.
- Never add a Co-Authored-By line.
