# Module `spark`

## Purpose

Temporal weighted coauthorship graph construction and distributed graph analytics.
The analytics are degrees, components, distances, communities, bridge scores, k-core,
trends and evidence paths.

## Owner and paths

- Owner: spark-engineer (documentation-engineer owns only this file).
- sbt project: `sparkAnalytics`; folder: `modules/spark`.
- Source roots, empty `.gitkeep` placeholders only:
  - `modules/spark/src/main/scala`
- Unit-test root: `tests/module-unit/spark/` (never `src/test`).

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

Spark 4.0.1 is built for Scala 2.13, the line Scala 3 links against
(`project/Versions.scala`).
The runtime target is `emr-spark-8.0.0` (human decision, 2026-11-22).
Its availability in us-east-2 is still to be checked before the first cloud run.
Application code must stay DataFrame-shaped, because Dataset encoders need Scala 2
TypeTags (`build.sbt`, comment above `sparkAnalytics`).
Compiling here proves only that it compiles; no Spark code or test exists yet.

Local Spark tests on JDK 17 will need `Test / fork := true` and `--add-opens` JVM options.
Ask build-engineer when the first Spark test is written.

A graph library such as GraphFrames would be a new dependency.
It raises the same 2.12 vs 2.13 question and goes through build-engineer.

## Dependencies

- Project: `core`.
- Library: `spark-sql` 4.0.1 (Scala 2.13 artifacts) via `CrossVersion.for3Use2_13`,
  Provided.
- Packaged by `packageJobs` into `target/jobs/`.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
