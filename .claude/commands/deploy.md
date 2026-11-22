---
description: Deploy the approved ScholarChain commit and Terraform plan to an approved environment through deploy-engineer, with plan SHA-256, smoke-test and rollback gates.
allowed-tools: Read, Grep, Glob
---
<!-- GENERATED from .agents/skills/deploy/SKILL.md by .agents/render.sh - do not edit; edit the source and re-run. -->

<env> = the environment the user named with this skill (first word); there is no default.

Responsible agent: **deploy-engineer**.
The orchestrator only delegates and reports; it never runs the scripts itself.

Gates, all verified before delegating:
1. `docs/agent-runs/phase-NN/human-gate.md` names this commit, <env>, the plan SHA-256
   and the cost estimate counted against the remaining $25 budget.
2. code-reviewer returned APPROVE on the infrastructure diff.
3. The working tree is clean.

If any gate is missing, stop and tell the human which one; do not delegate.

Use the **deploy-engineer** agent to: verify its preconditions; run `scripts/deploy.sh
<env>` (it applies only the saved plan whose SHA-256 matches `human-gate.md`); wait for
stabilization; run `scripts/smoke-test.sh <env>`; on any gate failure capture evidence,
run `scripts/rollback.sh <env>` and verify recovery; write a new record under
`releases/`.

Report: commit SHA, plan SHA-256, image digests, revisions, smoke results, rollback
actions, resources left running and their cost.

Arguments given by the user: $ARGUMENTS
