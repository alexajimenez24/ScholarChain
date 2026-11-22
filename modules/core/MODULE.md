# Module `core`

## Purpose

Shared domain records, stable identifiers, serialization and configuration contracts.
Pipeline stages exchange data only through these versioned contracts on S3.

## Owner and paths

- Owner: domain-engineer (documentation-engineer owns only this file).
- sbt project: `core`; folder: `modules/core`.
- Source roots, empty `.gitkeep` placeholders only:
  - `modules/core/src/main/scala`
  - `modules/core/src/main/java`
  - `modules/core/src/main/resources`
- Unit-test root: `tests/module-unit/core/` (never `src/test`).

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

- External libraries only: uPickle 4.4.3 (the only JSON library) and slf4j-api 2.0.20.
- No project dependency.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
