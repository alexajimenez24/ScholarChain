# Phase 08 versions

Observed by the orchestrator on 2026-11-24 (commands: `claude --version`, `java -version`,
`cat project/build.properties`, `grep scala3 project/Versions.scala`, `python3 --version`).

| Item | Version | Source |
|---|---|---|
| Harness | Claude Code 2.1.331 | `claude --version` |
| Orchestrator model | opus 5.5 (claude-opus-5-5), effort high | session |
| High-tier roles | opus, effort high | `.agents/models.json`; report headers |
| Standard-tier roles | sonnet 5.5 (claude-sonnet-5-5) | report headers |
| Low-tier step-downs (STATUS updates) | haiku 5.5 (claude-haiku-5-5) | report headers |
| JVM | OpenJDK 17.0.21.1 LTS | `java -version` |
| sbt | 1.13.1 | `project/build.properties` |
| Scala | 3.3.9 | `project/Versions.scala` |
| Spark (local runs) | 4.0.1 | step 00b and 00d reports |
| Python (dataset scripts) | 3.12.3 | `python3 --version` |
| Terraform, AWS CLI | not used in this phase | no cloud |
