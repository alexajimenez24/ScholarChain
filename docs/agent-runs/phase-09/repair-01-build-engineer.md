HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 09 repair round 1, finding M7. Run adversarialTests in a forked JVM with a fixed heap of at least 2560m so `sbt check` can pass at the default SBT_OPTS.
FILES CREATED: none
FILES MODIFIED: build.sbt
- The `adversarialTests` project now has `Test / fork := true` and `Test / javaOptions ++= Seq("-Xmx2560m", "-Xss4m")`.
- A comment explains why. The settings sit on `adversarialTests` only, so `contractTests` and the shared `blackBoxTests` helper are unchanged.
COMMANDS RUN: `timeout 1500 sbt -batch "adversarialTests/testOnly scholarchain.adversarial.rag.BenchmarkQuestionSetSuite"` at the default SBT_OPTS, with no override.
TEST RESULTS: BenchmarkQuestionSetSuite passed: 40 total, 0 failed, 0 errors, 0 ignored, 7.4s. The sbt run took 13s.
VALIDATION RESULTS:
- Working directory: a forked test JVM starts in the project base directory, which is `tests/adversarial`. `BenchmarkStore.repoRoot` (tests/adversarial/scholarchain/adversarial/rag/BenchmarkStore.scala:46) starts from `user.dir` and walks up to the first directory that has both `build.sbt` and `modules/`. So it resolves to the repository root from there. `BenchmarkStore.runDir` and the `SynthesisPrompt.load(BenchmarkStore.repoRoot)` call in BenchmarkRuntime.scala:63 therefore keep working.
- I did not set `Test / baseDirectory`, because the upward walk makes it unnecessary.
- How I checked it: I read the source. The 40-test suite passing in the fork shows the suite runs, but it may not load the graph or the prompt. I did not run BenchmarkWorkflowSuite, which does.
- Versions, module graph and environment inputs are unchanged. No plugins were added. No new environment input.
ASSUMPTIONS: -Xmx2560m plus sbt's own 1536m heap fits in the 5 GB WSL limit, because only one sbt runs at a time. The forked JVM starts after sbt's own heap use is already in place.
RISKS:
- I did not run `scalafmtSbtCheck` or `sbt check`. The edit follows the existing formatting, but scalafmt on build.sbt is unverified.
- I did not run the heavy BenchmarkWorkflowSuite, so whether 2560m is enough for it is untested. That is test-engineer's run.
- The adversarial tests now run in a fork, so a JVM crash from OOM shows up as a forked-VM failure rather than an sbt OOM.
- Nothing is committed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer reruns BenchmarkWorkflowSuite, then one full `sbt check`, then a fresh code-reviewer re-check.
