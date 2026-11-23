# Phase 06 versions

- Harness: claude-code 2.1.330 (`claude --version`, 2026-11-23).
- Orchestrator: opus 5.5 (claude-opus-5-5), effort high.
- Models from `.agents/models.json` (claude): high = opus/high, standard = sonnet/medium,
  low = haiku/high. Roles ran on their tier default except the STATUS-only updates, which
  ran on haiku (low, step-down, stated in each work order and in `run-log.md`).
- JVM: OpenJDK 17.0.21.1 (`java -version` after `source toolchain/env.sh`).
- sbt: 1.13.1 (`project/build.properties`).
- Scala: 3.3.9 (`project/Versions.scala`, `scala3`).
- Spark: 4.0.1, Provided (`project/Versions.scala`, `spark`); EMR target emr-spark-8.0.0
  not used in this phase (no cloud).
- scalafmt CLI: 3.11.5.
- Terraform, AWS CLI: not used in this phase.
