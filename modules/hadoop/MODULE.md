# Module `hadoop`

## Purpose

MapReduce ingestion over DBLP and enrichment data.
It covers parsing, normalization, author-mention preparation, blocking, joining and
coauthor-pair generation.

## Owner and paths

- Owner: hadoop-engineer (documentation-engineer owns only this file).
- sbt project: `hadoop`; folder: `modules/hadoop`.
- Source roots, empty `.gitkeep` placeholders only:
  - `modules/hadoop/src/main/scala`
  - `modules/hadoop/src/main/java`
  - `modules/hadoop/src/main/resources`
- Unit-test root: `tests/module-unit/hadoop/` (never `src/test`).

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
- Library: `hadoop-client-api` 3.4.1, Provided (EMR supplies it).
- Packaged by `packageJobs` into `target/jobs/`.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
