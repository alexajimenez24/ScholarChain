# Phase 03 human gate

## How Phase 03 was produced

The owning agents did the work through the orchestrator, one owner at a time:
dataset-engineer for `datasets/**` and `tests/module-unit/datasets/**`, domain-engineer
for `modules/core/**` and `tests/module-unit/core/**`, and documentation-engineer for the
notes. The work order and my addendum are in `prompt.md`; every step is in `run-log.md`.

I gave the agents reference material: a reference implementation of this phase from the
collaborative ScholarChain repository, unpacked outside the repository in
`~/ref-phase-03/` with a `REFERENCE.md` that lists what had to change. The addendum told
each owner to adapt it to this repository, review every line and own the result. The
main adaptations were the configuration format (JSON-compatible YAML 1.2 instead of a
JSON file, so the work order's "HOCON or YAML" holds with uPickle as the only JSON
library) and the service settings (three deployables with Cognito JWT settings instead
of one API-key service). The reference folder is not part of the repository.

## What was checked

- Every dataset license, snapshot, size, checksum and endpoint in
  `datasets/dataset-manifest.yaml` was observed at its official source or marked
  UNVERIFIED. The fresh reviewers checked that nothing was invented, which is the Phase 3
  gate item.
- `python3 datasets/scripts/validate-manifest.py` passes (5 datasets; DBLP, OpenAlex,
  Crossref and ORCID required), the committed samples verify against their recorded
  checksums, and the 17 dataset Python tests pass.
- `sbt check` passes with 94 core tests: wire-format contract tests with frozen JSON
  examples, schema versions, stable IDs, pair ordering, time windows, provenance and the
  configuration loader (`output-r2-verify-sbt-check.txt`, `review-report-03.md`).
- Review 1 and review 2 returned REQUEST_CHANGES. Repair rounds 1 and 2 are in
  `repair-01*.md` and `repair-02*.md`. Review 3, by a fresh code-reviewer, returned
  APPROVE with 0 critical, 0 major and 0 minor findings (`review-report-03.md`).
- No test-engineer test gate was part of the Phase 3 work order, so there is no
  `test-report.md`. The frozen wire examples are domain-engineer's unit tests in
  `tests/module-unit/core/`, as my addendum directed; `tests/contracts/` stays
  test-engineer's for Phase 11.

## The hung repair task

In repair round 2, domain-engineer's mutation checks (removing one rule at a time to
show its test fails) finished at 18:26, but the agent then waited on its own background
polling shells until 20:21, about 8.5 hours of wall time. I stopped the session. The
orchestrator then confirmed that all 13 rules touched by the mutation script are present
exactly once in `Config.scala` and `Validate.scala`, that no mutated text or backup file
was left, and that no sbt process was running. It ran `sbt check` once (exit 0, 94
passed) and saved the raw mutation results in `output-r2-1-mutation-checks.txt`; all 13
mutations were caught by their named test. The fresh reviewer verified the restored
rules separately. Nothing was restored by hand.

## Decisions

- Configuration is stored as `scholarchain-v1.yaml` in the JSON-compatible subset of
  YAML 1.2, parsed by uPickle; the restriction is stated in the file, in
  `modules/core/MODULE.md` and in the agent reports.
- Model step-downs to haiku for small STATUS updates, recorded in `run-log.md`, are
  accepted.
- Auto mode was on during parts of this phase, including repair round 2, contrary to my
  Phase 01 note. The guard hooks and deny rules still applied to every tool call, and no
  blocked action ran. I have turned auto mode off and keep it off from Phase 04 on.

## Open decisions, carried forward

These are listed in `STATUS.md` and stay open; nothing in this phase asserts more than
the manifest records:
- the license status of `dblp.dtd` (recorded as CC0 to be confirmed);
- the ORCID API terms for sample retrieval;
- dataset questions 1 to 5 from the dataset-engineer report (T01);
- the scala-library option from Phase 02 (MINOR-2), due in Phase 06.

## Accepted, with known gaps

The Phase 03 contracts, configuration loader and dataset manifest are accepted as
committed. Known issues left open on purpose, documented in `modules/core/MODULE.md`
and `STATUS.md`:
- the review 2 residuals: the T14c fractional run-ID collision, the T5 reject reason for
  a missing required key, duplicate JSON keys, decode cost, unchecked nested provenance
  and componentSize 0;
- the low-impact number-parsing observations in review 3's RISKS section, all of which
  fail closed or are cosmetic;
- the dataset Python tests are not yet part of `sbt check`; wiring them in is
  build-engineer's, planned for Phase 04 or Phase 11.

## Manual changes

- I stopped the hung repair session; I changed no file myself.
- Manual changes to agent-owned files: none.

## Commit

Accepted commit: 9a65cf2e4de050017e56db15505a27a8fd0be916

Phase 3 is complete. I approve starting Phase 4.
