# Phase 02 versions

Recorded by the orchestrator on 2026-11-22 at about 10:06 CST, after repair round 1 tasks R1-1
to R1-5, on the machine that ran Phase 02. Commands ran with `source toolchain/env.sh`, so
`java` is the pinned toolchain JDK
(`~/.local/share/scholarchain-tools/jdk-17.0.21.1.2/bin/java`). Phase 01 used the
system JDK 21.

```text
$ claude --version
2.1.330 (Claude Code)

$ java -version
openjdk version "17.0.21.1" 2026-11-17 LTS
OpenJDK Runtime Environment Corretto-17.0.21.1.2 (build 17.0.21.1+2-LTS)
OpenJDK 64-Bit Server VM Corretto-17.0.21.1.2 (build 17.0.21.1+2-LTS, mixed mode, sharing)

$ sbt --script-version
1.13.1

$ scalafmt --version
scalafmt 3.11.5

$ terraform version
Terraform v1.17.2
on linux_amd64

$ aws --version
aws-cli/2.35.21 Python/3.14.6 Linux/5.15.167.4-microsoft-standard-WSL2 exe/x86_64.ubuntu.24

$ uv --version
uv 0.13.9 (x86_64-unknown-linux-gnu)

$ terraform-mcp-server --version
Terraform MCP Server
Version: 1.4.1

$ python3 --version
Python 3.12.3

$ git --version
git version 2.43.0
```

Build pins (from the files, not from a command):
- sbt 1.13.1 (`project/build.properties`), Scala 3.3.9 (`project/Versions.scala`), JVM
  release 17.
- scalafmt-core 3.11.5 (`.scalafmt.conf`).
- Service base image `amazoncorretto:17.0.21-al2023-headless` pinned by digest
  (`docker/services/Dockerfile` line 9).
- Codex: not installed and not used (`CODEX_VERSION` empty; harness out of scope).

Models (from `.agents/models.json`, claude harness):
- high: opus, effort high;
- standard: sonnet, effort medium;
- low: haiku, effort high.
In repair round 1 the orchestrator stepped documentation-engineer down to low for the
mechanical STATUS checkpoints; see `run-log.md`.
