---
name: run-pipeline
description: Run one bounded ScholarChain data pipeline (Hadoop, entity resolution,
  Spark, loaders) on EMR through deploy-engineer and scripts/run-bounded-pipeline.sh,
  after the human's cost sign-off.
allowed-tools: Read, Grep, Glob
---

<argument> = the environment and dataset snapshot the user named with this skill.

Responsible agent: **deploy-engineer** (executes the approved script; authored by
infra-engineer).
The orchestrator only delegates and reports.

Gates, all verified before delegating:
1. `docs/agent-runs/phase-NN/human-gate.md` approves this bounded run: environment,
   dataset snapshot, input bound, EMR capacity and the cost estimate counted against
   the remaining $25 budget.
2. `sbt check` is green on the approved commit and the tree is clean.
3. The packaged job artifacts match the commit.

If any gate is missing, stop and tell the human which one; do not delegate.

Use the **deploy-engineer** agent to run `scripts/run-bounded-pipeline.sh` with
<argument>, confirm the EMR cluster auto-terminated after evidence was archived, and
record dataset snapshot, run IDs, configuration and output locations.
The work order writes the script call in the foreground under `timeout` with the bound
the human gate names, and any local `sbt` step as `timeout 45m sbt check` or
`timeout 30m sbt ...`; no background run, polling or wait loop (`AGENTS.md`, Commands).

Report: run IDs, Hadoop and Spark counters, output paths, duration, estimated cost and
anything left running.
