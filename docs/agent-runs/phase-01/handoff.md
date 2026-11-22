# Phase 01 handoff (orchestrator, written before the human's restart)

Written by the orchestrator because the restart came before documentation-engineer could
be delegated (docs/agents.md section 3, phase execution item 7). The next session's first
task folds this note into `STATUS.md` and `PROJECT_MEMORY.md` through
documentation-engineer.

## State

- Repair rounds 1 and 2 (factory-engineer) are ratified by the human. Reports:
  `repair-01.md`, `repair-02.md` (saved word for word from the agent hand-back).
- Nothing is staged or committed by an agent; the human commits.
- Checks observed by the orchestrator after round 2: `render.sh --check` OK (44 files),
  `validate-agent-files.sh` OK, `test_guard_dangerous.py` 25 tests OK,
  `test_guard_ownership.py` 4 tests OK.

## Human decisions recorded (round 2)

- Accepted: the grep/rg search-pattern scrub; `datasets/g*` stays blocked.
- Accepted risk, revisit in Phase 05 when the gold set exists: the narrowed gold-set
  shell-write check no longer blocks writes through an ancestor of `datasets/gold/`
  (`rm -rf datasets`, `rm -rf datasets/*`, `cd datasets && rm -rf *`,
  `find datasets -delete`, `mv datasets x`, `git checkout -- datasets`, `rm -rf *` at the
  repository root). Still covered by the file-tool deny, the territory, git diff, the
  pre-commit gate and the reviewer. Owner when revisited: factory-engineer.
- Routing result accepted by the human.

## Open

- Routing evidence: the orchestrator found no routing run newer than the round 1 edits.
  `output-routing.txt` and `output-routing-summary.txt` in this folder are dated before
  round 1 (13:13) and were left unchanged. The human supplies the new run's output, or
  the suite is re-run, before these files are replaced.
- Note for future reviews: edited hook scripts take effect immediately (Claude Code runs
  them from disk on every call); only `settings.json` and agent definitions need a
  restart. Observed with an inert probe after round 2.
- Post-ratification probes listed in `repair-01.md` (a) to (d) and `repair-02.md` (e), (f).

## Next step

Fresh session: `cd ~/scholarchain && claude "Read docs/agent-runs/phase-01/handoff.md and continue."`
