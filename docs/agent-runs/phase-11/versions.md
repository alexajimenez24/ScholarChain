# Phase 11 versions

- Harness: claude-code 2.1.332 (observed with `claude --version`).
- Models (`.agents/models.json`): high `opus`, effort high; standard `sonnet`, effort
  medium; low `haiku`, effort high. Orchestrator: main session, model id
  `claude-sonnet-5-5` (observed; the matrix puts the orchestrator on the high tier, so
  this is a deviation to note at the gate).
- JVM: OpenJDK 21.0.13.1. sbt, Scala and library versions: unchanged from the repository
  (`project/Versions.scala`, `project/build.properties`).
- No cloud tool was used.
