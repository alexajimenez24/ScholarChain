# Module `entity-resolution`

## Purpose

Researcher identity resolution.
It covers blocking keys, match features, thresholds, calibration and decision schemas.
The adjudication prompt lives in `src/main/resources/` of this module.

## Owner and paths

- Owner: entity-resolution-engineer (documentation-engineer owns only this file).
- sbt project: `entityResolution`; folder: `modules/entity-resolution`.
- Source roots, empty `.gitkeep` placeholders only:
  - `modules/entity-resolution/src/main/scala`
  - `modules/entity-resolution/src/main/resources`
- Unit-test root: `tests/module-unit/entity-resolution/` (never `src/test`).

## Public API

None yet.
No source exists, so there is nothing to document or to test black-box.

## Done

- The sbt project compiles with no application logic (phase 2).
- Source and unit-test roots exist as `.gitkeep` placeholders.

## Not done

- All application code and all unit tests.
- No test case exists; `sbt check` reports 0 test cases for this module.

## Known issues

None known.

## Dependencies

- Project: `core`.
- Packaged by `packageJobs` into `target/jobs/`.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
