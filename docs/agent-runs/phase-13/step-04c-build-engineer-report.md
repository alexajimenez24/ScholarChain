HARNESS: claude-code
MODEL: sonnet, effort medium (standard tier)
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 13 repair of the second step-4 BLOCKED-ON: serialize tests in sbt check; package the synthesis prompt and schema into queryService, with a build-level check.
FILES CREATED: none in the repository.
FILES MODIFIED: build.sbt only. project/, docker/, scripts/ and prompts/ were not changed by this agent.
COMMANDS RUN (foreground under timeout, one sbt at a time, except where noted):
- Repo: `sbt "scalafmtSbtCheck; show Global/concurrentRestrictions; show .../Test/test/tags; queryService/verifySynthesisResources"` passed. `sbt queryService/assembly` passed; `unzip -p | cmp` found both files identical in the jar, the only two files under prompts/.
- Repo, final: `sbt "scalafmtSbtCheck; Test/compile; queryService/verifySynthesisResources; queryService/test"`, exit 0.
- Throwaway copy (git ls-files + rsync, in the scratchpad neg-t3lRv8): two failure proofs, the six-project overlap run and a control run.
- The harness backgrounded the six-project run after 180s. Nothing else was started and it was not polled.
- guard-dangerous blocked the first failure proof, which used sbt `set`. Nothing ran. `set` was not used and the guard was not worked around; files were edited only in the throwaway copy.
- The full `sbt check` and check.sh were NOT run, as the work order requires, although the stop hook asked. No docker build or AWS call.
TEST RESULTS:
- Repo queryService/test: 39 passed, 0 failed.
- Copy, core, servicesCommon, queryService, graphService, hadoop and rag in one command: 477 passed, 0 failed (includes graphService 18, rag 166).
- Failure proof A, schema removed in the copy: exit 1, "synthesis resource prompts/schemas/answer-synthesis-v2.json is missing from the repository".
- Failure proof B, packaging line removed in the copy: exit 1, both files "not on the packaged classpath".
- The "differs" and "2 copies" branches were not exercised.
VALIDATION RESULTS:
- Limit: `Global / concurrentRestrictions` adds `Tags.limit(Tags.Test, 1)` and `Tags.exclusive(Tags.Test)`. Both the forked hadoop test task and the in-process queryService one carry the test tag.
- Overlap, measured with a temporary suite start/end listener in the copy: 49 suites in 6 projects, at most 1 running at once. Control without the limit (4 projects, 27 suites): up to 16 at once.
- Limited: in-process suites and forked test groups across all projects, under check, test and testOnly. `exclusive` also stops compile or any other task running beside a test. check already compiles everything before markTestRun.
- Not limited: threads within a suite or forked JVM (Spark, cats-effect), threads left by finished in-process suites, processes outside this sbt run, and parallelism among compile and packaging tasks. Forked heaps are unchanged.
- Packaging: runtime synthesis loads only prompts/runtime/answer-synthesis-v2.md (SynthesisPrompt.File). The schema prompts/schemas/answer-synthesis-v2.json (SynthesisRules.SchemaFile) is read only by tests; it is packaged as requested. The v1 files, query-plan-v1 and semantic-context-v1 are not packaged.
- Step 1's `resourceGenerators` suggestion was not used. It would put the prompt on the test classpath, where it takes precedence over SYNTHESIS_PROMPT_ROOT and would break the `/nonexistent` assertion at QueryWiringSuite.scala:279. The files are added to `assembly / fullClasspath` only, which dockerLocalBuild uses.
- New check: `queryService/verifySynthesisResources` is in the `check` alias before markTestRun. It requires exactly one byte-identical copy of each listed file on the assembly classpath. It does not build or inspect the fat jar; the real jar was compared once by hand.
- LLM_ENABLED still defaults to false.
- Version ledger, module graph, environment inputs and plugins: all unchanged. No new Scala 3, Hadoop or Spark compatibility risk.
ASSUMPTIONS: "synthesis prompt and schema files" means the two v2 files the synthesis stage names.
RISKS:
- The clean-checkout gate is not yet proven green. The two earlier flakes may not recur with tests serialized, but only the full clean-copy check.sh can show that. It will also take longer; its sbt limit is 45m.
- The file list in build.sbt does not follow rag automatically. A new prompt or schema version must update it, or the check still passes on the old files (a missing new prompt would then fail only at LLM startup, unless SYNTHESIS_PROMPT_ROOT supplies it).
- The service was not started with LLM_ENABLED=true from the jar or an image. Image build: NOT RUN.
- The local images and the queryService jar checksum from the first package.sh run (output-package.txt) predate this change and are stale. The old query-service image lacks the prompt. Locally, target/services holds the new jar.
- No test-engineer contract script (tests/build/check-contract.sh) run, although its README asks for one after build.sbt changes; it runs full `sbt check`.
- Left behind: scratchpad neg-t3lRv8 (with temporary build edits), scratchpad overlap.py, and target/ outputs. .claude/.last-test-run was not touched.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: a fresh infra-engineer runs the clean-copy scripts/check.sh in a new directory. No script change is needed, since check.sh's `sbt check` includes the new verification. infra-engineer should also decide on rerunning scripts/package.sh so the recorded jar checksums and images match this build; it must be rebuilt before any deploy.
