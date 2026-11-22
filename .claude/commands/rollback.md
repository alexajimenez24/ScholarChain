---
description: Roll a ScholarChain environment back to the previous approved revision through deploy-engineer, then open the incident-responder follow-up.
allowed-tools: Read, Grep, Glob
---
<!-- GENERATED from .agents/skills/rollback/SKILL.md by .agents/render.sh - do not edit; edit the source and re-run. -->

<env> = the environment the user named with this skill (first word); there is no default.

Responsible agent: **deploy-engineer**; follow-up owner: **incident-responder**.
The orchestrator only delegates and reports.

Limits on autonomous action:
- Rollback re-points services at the previous approved revision only.
- It never rolls back schema or data, never runs `terraform apply` or `destroy`, and
  never deletes resources.
- Anything beyond re-pointing goes to the human as an evidence dossier.

Use the **deploy-engineer** agent to: capture the failure evidence first; run
`scripts/rollback.sh <env>`; wait until the previous revision is steady; run
`scripts/smoke-test.sh <env>`; write a new record under `releases/`.

Then use the **incident-responder** agent to find why the rolled-back deploy failed and
write the incident report; nobody deploys again until it is answered.

Report: the live revision, smoke results, the evidence captured before rollback and the
incident report path.

Arguments given by the user: $ARGUMENTS
