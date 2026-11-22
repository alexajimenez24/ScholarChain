# Phase 02 human gate

## How Phase 02 was produced

The build skeleton (build.sbt, project/, .scalafmt.conf, .gitignore, docker/,
toolchain/, tests/build/, the module and test roots with their .gitkeep files, the
MODULE.md files, README.md, STATUS.md and PROJECT_MEMORY.md) was installed by me from a
prepared package, not created by a build-engineer run in this repository. The work order
it answers is in `prompt.md`. After that, every change in this phase was made by the
owning agents through the orchestrator, one owner at a time, as recorded in `run-log.md`.

Manual changes I made myself:
- I renamed `toolchain/versions.env` to `toolchain/versions.sh`. The Phase 01 secret
  guard blocks every `*.env` name, so no agent could read or edit the file, and I chose a
  rename over loosening the guard. toolchain-engineer then updated every reference to it
  (`step-01b-toolchain-engineer-report.md`).
- I installed terraform-mcp-server 1.4.1 into `~/.local/bin` from HashiCorp's release
  page and verified it with `sha256sum -c` against HashiCorp's `SHA256SUMS`. The
  re-verified evidence, including a byte comparison of the installed binary with the
  verified release binary, is in `output-terraform-mcp-server-checksum.txt`.
- python3 3.12.3 is provided by my Python virtual environment, which is active in every
  session in this repository.

## What was checked

- toolchain-engineer pinned the observed tool versions; `toolchain/install.sh` reports no
  mismatches (`step-01b-toolchain-engineer-report.md`, `repair-01-toolchain-engineer.md`).
- build-engineer verified the build against the Phase 2 work order: `sbt Test/compile`
  and `sbt check` passed (`agent-report.md`). The Spark runtime target emr-spark-8.0.0 is
  recorded in `project/Versions.scala` (`step-04-build-engineer-report.md`).
- A fresh code-reviewer returned REQUEST_CHANGES (`review-report.md`: 3 major, 7 minor,
  5 nits). Repair round 1 is in `repair-01.md` and the `repair-01-*.md` owner reports.
- A WSL crash interrupted repair round 1 during build-engineer's task. The orchestrator
  compared the tree with a clean copy of the package (`output-r1-2-partial-edits.txt`)
  and re-ran build-engineer from the start, so its report matches the files on disk.
- `sbt check` exited 0 (`output-sbt-check.txt`), and the build contract test passed 4 of
  4 (`output-build-contract.txt`). No test sources exist yet, so `sbt check` ran 0 test
  cases; the green result shows the build structure only.
- A second fresh code-reviewer returned APPROVE (`review-report-02.md`): no critical or
  major findings, and every Phase 2 gate item passed when it ran them itself.
- No test-engineer test gate was part of the Phase 2 work order, so there is no
  `test-report.md`. test-engineer's only run was repair R1-4
  (`repair-01-test-engineer.md`), and the build contract test results are in
  `output-build-contract.txt`.

## Decisions

- Spark target: emr-spark-8.0.0 (Spark 4.0.2, Scala 2.13.16). The build compiles against
  Spark 4.0.1 on the same 4.0 line. Availability in us-east-2 and the JVM version are
  confirmed in Phase 12.
- MINOR-2 (scala-library 2.13.18 in the build versus 2.13.16 on EMR): option (b), as
  built. The Spark job jar leaves scala-library out and uses the cluster's copy. I
  confirm or change this in Phase 06, when the first Spark job runs. The residual risk is
  a NoSuchMethodError on the cluster if compiled code calls a member added after 2.13.16.
- contractTests and adversarialTests are accepted as test-engineer's black-box test
  projects that depend on every module and that nothing depends on.
- MINOR-4 (markTestRun quote splitting in guard-dangerous) is deferred to Phase 11 with
  the other guard findings. The bypass now skips only the format check, not the tests.

## Accepted, with known gaps

The Phase 02 build is accepted as committed. Carried forward from review 2:
- MINOR-A (two SLF4J providers and no forked JVM with `--add-opens` for a local
  SparkSession in the black-box test projects): Phase 06, build-engineer.
- MINOR-B (modules/spark/MODULE.md does not yet record the scala-library exclusion):
  Phase 06, documentation-engineer, together with the MINOR-2 decision.
- MINOR-C (misattributed provenance of the Versions.scala line 9 edit): corrected by the
  orchestrator in its notes after review 2; the agent report is unchanged. This
  correction is evidence text only and was not re-reviewed.
- MINOR-D (no automated test of the Spark jar composition and the black-box classpath):
  Phase 06, test-engineer, after the MINOR-2 decision.
- Nits N-a to N-d, as listed in STATUS.md.

## Other remaining risk

- `sbt dockerLocal` was not run, and the Docker base image digest was not checked against
  the registry.
- `packageJobs` ran only during repair round 1 and review 2.
- The Claude Code pin (2.1.330) reports a mismatch when Claude Code updates itself; the
  ledger is then updated by toolchain-engineer.

Manual changes to agent-owned files after review: none.

## Commit

Accepted commit: 3de681d5c844a8ffaed9789920c863190ddafdea

Phase 2 is complete. I approve starting Phase 3.
