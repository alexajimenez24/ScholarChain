---
description: Investigate a ScholarChain runtime or pipeline problem end to end through incident-responder, within its bounded recovery rules.
allowed-tools: Read, Grep, Glob
---
<!-- GENERATED from .agents/skills/incident/SKILL.md by .agents/render.sh - do not edit; edit the source and re-run. -->

<argument> = the symptom the user described with this skill.

Responsible agent: **incident-responder**.
The orchestrator only delegates and reports.

Gates:
- Diagnosis is read-only first; every action cites its evidence.
- Autonomous recovery is limited to restarting an ECS task, scaling an ECS service
  within the incident-responder scale bound (`docs/agents.md` section 6), and
  requesting `/rollback` when the current deploy is the proximate cause.
- Data, schema, security and infrastructure findings go to the human as a dossier.
- 15 minutes without a credible hypothesis ends in a dossier.

Use the **incident-responder** agent to run its triage order (service state, logs,
queues and workflows, stores) for: <argument>; take only bounded recovery; write
`docs/incidents/<YYYY-MM-DD>-<slug>.md`.

Report: the incident report path, or the dossier and the specific human decision needed.

Arguments given by the user: $ARGUMENTS
