# Module `rag`

## Purpose

Query plans, retrieval fusion, evidence bundles and claim verification.
The runtime prompts live under `prompts/`.

## Owner and paths

- Owner: rag-engineer (documentation-engineer owns only this file).
- sbt project: `rag`; folder: `modules/rag`.
- Source roots, empty `.gitkeep` placeholders only:
  - `modules/rag/src/main/scala`
- Unit-test root: `tests/module-unit/rag/` (never `src/test`).

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

- Projects: `core`, `storage`.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
