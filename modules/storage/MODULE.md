# Module `storage`

## Purpose

Loaders and adapters for S3, Neptune, DynamoDB, S3 Vectors and optional OpenSearch.

## Owner and paths

- Owner: storage-engineer (documentation-engineer owns only this file).
- sbt project: `storage`; folder: `modules/storage`.
- Source roots, empty `.gitkeep` placeholders only:
  - `modules/storage/src/main/scala`
- Unit-test root: `tests/module-unit/storage/` (never `src/test`).

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

If loaders later run as their own EMR step or task, the project needs assembly settings.
It would also need an entry in `packageJobs` (`build.sbt`, packaging comment).

## Dependencies

- Project: `core`.
- Bundled into the services jar through `rag` and `services`; it is not a job jar.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
