# Phase evidence format

<!-- Owner of this file: factory-engineer (constitutional). Everything else under
docs/agent-runs/ is human-owned evidence: the orchestrator assembles it, and every
human-gate.md is written only by the human. -->

Every phase leaves one folder, `docs/agent-runs/phase-NN/`, with two-digit `NN`.
The folder lets a grader replay the phase and see who did what, under which harness and
model, and what the human decided.
Claude Code is the only harness in scope (harness scope, `docs/agents.md`), so the
harness recorded below is always `claude-code`.

## Files in every phase folder

| File | Written by | Content |
|---|---|---|
| `prompt.md` | orchestrator | the exact work order, verbatim |
| `run-log.md` | orchestrator | one row per step (format below) |
| `agent-report.md` | orchestrator | the owning agent's final report, verbatim |
| `review-report.md` | orchestrator | code-reviewer report, when a review ran |
| `test-report.md` | orchestrator | test-engineer report, when tests ran |
| `repair-NN.md` | orchestrator | one per repair round (format below) |
| `versions.md` | orchestrator | harness, model and tool versions used |
| `handoff.md` | orchestrator | only when a cut-off left no time for `/handoff` |
| `human-gate.md` | human only | the decision (format below) |
| `commit.txt` | human | the accepted commit hash |

A phase with several steps stores each extra report as `step-NN-<agent>-report.md` and
lists it in `run-log.md`.
Deterministic command output that a report cites (validation, test counts, plan
summaries) is pasted verbatim inside the report or stored next to it as
`output-<name>.txt`.

## run-log.md

One table row per step: step number, agent, harness (`claude-code`), model and effort,
start and end time, report file and STATUS.
When the work order ran a role off its default tier, the model cell says so with the
reason, for example `haiku (low, step-down: one-line bump)`.
There is no harness switch; a step's report is saved here and `STATUS.md` is updated
before the next step starts.

## repair-NN.md

The finding or failing test that started the round, the owner it was routed to, the
repair brief (for test-engineer failures: code-reviewer's fairness verdict, likely
cause, what else could break, which tests must stay green), the fix's report, and the
rerun evidence.
A loop stops after 3 rounds and goes to the human; the third repair file says so.

## versions.md

The Claude Code version, the models and efforts from `.agents/models.json`, JVM,
sbt, Scala, Terraform, AWS CLI and every other tool from the `toolchain/` ledger that
the phase used.

## human-gate.md

Written only by the human; a deny rule blocks every agent tool from writing it.
It states:
- what was reviewed;
- what was accepted or rejected;
- any manual change, disclosed;
- any remaining risk;
- the accepted commit hash.

When the phase applies, deploys or runs anything that adds cost, it also states these
lines, which the scripts and agents read exactly:

```text
APPROVED-COMMIT: <40-hex commit SHA>
APPROVED-ENVIRONMENT: <environment name>
APPROVED-PLAN-SHA256: <64-hex SHA-256 of the saved Terraform plan file>
COST-ESTIMATE-USD: <estimate for this action>
BUDGET-REMAINING-USD: <the $25 course budget left before this action>
```

`scripts/deploy.sh` applies a saved plan only when its SHA-256 equals
`APPROVED-PLAN-SHA256` in that phase's `human-gate.md`.

## What is never stored

Hidden model reasoning is never stored, requested or reconstructed.
The record holds the work order, each agent's final report, deterministic command
output, review findings and the human's decision.
No secret value, token or credential appears in any file here; name the environment
variable instead.
