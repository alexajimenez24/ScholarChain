---
name: experiment
description: Run one approved ScholarChain experiment manifest (scaling, backlog, query
  load, retrieval modes, failure recovery or evaluation) through experiment-engineer,
  with immutable raw results and the human's cost sign-off.
allowed-tools: Read, Grep, Glob
---

<argument> = the experiment manifest path the user named with this skill.

Responsible agent: **experiment-engineer**.
The orchestrator only delegates and reports.

Gates, all verified before delegating:
1. The manifest at <argument> is committed and records commit SHA, dataset snapshot,
   configuration, resource limits and region.
2. `docs/agent-runs/phase-NN/human-gate.md` signs the cost estimate against the
   remaining $25 budget, when the run adds cost.
3. The deployed revision matches the manifest's commit.

If any gate is missing, stop and tell the human which one; do not delegate.

Use the **experiment-engineer** agent to run the manifest through the approved script,
write raw results as new files only, derive tables and plots by script, keep failed
runs, and confirm ephemeral resources scaled to zero after evidence was archived.

Report: manifest, raw data paths, scaling events, latency and throughput, errors,
estimated cost and limitations.
