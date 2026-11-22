---
name: handoff
description: Write a clean ScholarChain handoff note into STATUS.md through
  documentation-engineer (current task, done, half done, file state, exact next step,
  verify commands) so a fresh session can continue without redoing work, then stop.
allowed-tools: Read, Grep, Glob
---

<argument> = optional free text from the user about why the session is stopping.

Responsible agent: **documentation-engineer** (owns `STATUS.md`).
The orchestrator gathers the facts and delegates; it never writes `STATUS.md` itself.
Run it when the human asks, or as soon as the orchestrator knows a cut-off is coming
(usage limit, context limit, end of session).

1. Gather from primary sources only: the phase task list in `STATUS.md`, the last saved
   reports in `docs/agent-runs/phase-NN/`, `git status --short` and `git diff --stat`.
   A running subagent that is about to be cut off ends at once with its report fields,
   half-done state under RISKS; the orchestrator saves that report first.
2. Use the **documentation-engineer** agent to write the handoff section of `STATUS.md`:
   - phase, current task with its owner, and the harness and model that ran it;
   - tasks done, the task running, the next task, anything left half done;
   - the state of every changed file (finished, partial, or must be discarded);
   - the exact next step, written as the next work order line;
   - the commands that verify the state (`git status --short`,
     `.agents/render.sh --check`, `sbt check` when sources changed);
   - when a session hit its usage limit: the exact command to continue in a fresh
     Claude Code session (the only harness in use, `docs/agents.md` harness scope), for
     example
     `cd ~/dev/ScholarChain && claude "Read STATUS.md and continue with its next step."`;
   - <argument>, if given.
   An agent cut off by a usage limit wrote nothing after the limit, so the note relies
   only on `STATUS.md`, saved reports and `git status`, never on a last-moment save.
3. Too close to the cut-off to delegate: write the same note to
   `docs/agent-runs/phase-NN/handoff.md` (orchestrator territory) instead; the next
   session's first task routes it to documentation-engineer for `STATUS.md`.
4. Stop. Never commit, never start the next task.

Report: the `STATUS.md` (or `handoff.md`) path and the next step it names; after a
usage limit, also the exact command to continue in a fresh session.
