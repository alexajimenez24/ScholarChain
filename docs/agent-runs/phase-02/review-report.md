HARNESS: claude-code
MODEL: opus 5.5 (claude-opus-5-5), high tier, effort high (code-reviewer default per .agents/models.json)

# Phase 02 code review (fresh code-reviewer)

**Verdict: REQUEST_CHANGES.** Every gate item in the spec that can be checked mechanically passes, and I reproduced each one myself. Two documentation defects (MAJOR-1, MAJOR-2) must be fixed before the human gate. One provenance disclosure (MAJOR-3) can only be resolved by the human in `human-gate.md`. Everything else is MINOR or NIT.

## Gate items (spec, docs/spec/assignment.md lines 1042-1049)

| Gate item | Result | Evidence I observed |
|---|---|---|
| No application logic | PASS | Every file under `modules/*/src/**` is a `.gitkeep`. There are no `.scala` or `.java` sources. |
| `sbt Test/compile` succeeds | PASS | `[success] Total time: 10 s` on the pinned JDK 17.0.21.1 and sbt 1.13.1 |
| `sbt check` has a stable definition | PASS | Alias at build.sbt:333-336. The run passed and printed `markTestRun: 14 project test runs passed, touched .../.claude/.last-test-run`. |
| No cycles in the module graph | PASS | The `dependsOn` lines in build.sbt:171-243 match the AGENTS.md boundary table exactly. No deployable depends on another deployable. |
| Versions in one ledger | PASS | `project/Versions.scala`. plugins.sbt reads the plugin pins from it as text. sbt, scalafmt-core and the Docker digest are documented exceptions (Versions.scala:7-12). |
| uPickle is the only JSON library | PASS (declared) | Only `upickle` is declared. Jackson and json4s appear only transitively on the Provided Spark classpath, as build.sbt:22-23 documents. |
| markTestRun only after tests pass | PASS | `tests/build/check-contract.sh` passed 4 of 4 scenarios (pass, fail-contract, fail-unit, fail-format). In each failing scenario the marker kept its old mtime. |
| Code review APPROVE | not yet | This report |

Spec principles also confirmed:
- Scala 3.3.9 everywhere.
- Hadoop and Spark are Provided.
- MUnit is in Test scope only.
- logback is in Runtime scope, deployables only.
- The scalafmt dialect is scala3.
- The aliases fmt, check, packageJobs and dockerLocal exist.
- The Dockerfile uses a pinned tag plus digest, a non-root `USER 10001:10001`, and `InitialRAMPercentage`/`MaxRAMPercentage`.
- The environment surface is documented (build.sbt:3-17).
- A grep of the build files found no secrets, endpoints or account IDs.

Spark 4.0.1 resolves: `spark-sql_2.13-4.0.1.jar` is on the sparkAnalytics classpath. That answers the agent report's open point that resolution was "unobserved".

Ownership:
- `git diff HEAD` is empty, so no tracked file changed. No constitutional or generated file was touched.
- `validate-agent-files.sh` passed: "188 files with one writer". It includes untracked files.
- `render.sh --check` passed: "44 generated files match their sources".
- Every new path sits in some valid territory.
- No library version value was moved. The build-engineer edits to Versions.scala changed comments only.

## Findings

### MAJOR-1: modules/spark/MODULE.md contradicts the ledger and cites a report that does not exist
- **File:** `modules/spark/MODULE.md` lines 34-40 and 51.
- **Owner:** documentation-engineer.
- **Problem:**
  - Line 51 says `spark-sql` 3.5.9. The build uses 4.0.1 (Versions.scala:36). I observed `spark-sql_2.13-4.0.1.jar` resolved.
  - Lines 34-39 say EMR 7.x Spark 3.5 runs on Scala 2.12 and that "the human decides the Spark runtime before phase 6". The human already decided `emr-spark-8.0.0` (Versions.scala:31-34, step-04 report).
  - Line 40 cites `docs/agent-runs/phase-02/step-08-spark-engineer-report.md`. That file does not exist; `ls docs/agent-runs/phase-02/` lists no step-08.
- **Impact:** MODULE.md is the black-box input test-engineer gets (docs/agents.md section 7). A wrong dependency version and a dead evidence citation are unsupported documentation claims.
- **Repair brief:**
  - Rewrite Known issues and Dependencies from the current `project/Versions.scala` and `build.sbt`:
    - Spark 4.0.1 on Scala 2.13, Provided.
    - Target `emr-spark-8.0.0`, us-east-2 availability open until Phase 12.
    - The DataFrame-only constraint stays.
  - Drop the step-08 citation, or replace it with an evidence file that exists.
  - Check the other six MODULE.md files for the same kind of stale text. I found none there.

### MAJOR-2: STATUS.md is not maintained as the phase checkpoint
- **File:** `STATUS.md` lines 12-13, 21-22 and 36-41. Also `PROJECT_MEMORY.md` line 12.
- **Owner:** documentation-engineer (routed by the orchestrator).
- **Problem:**
  - AGENTS.md ("Phases run as small tasks") requires a Phase 02 task list in STATUS.md before the first task, then tasks done, the running task and the next task after every task. STATUS.md has none of this; it only says "In progress".
  - Line 38-39 still lists "Record the observed tool versions ... in `toolchain/versions.sh` and `toolchain/LEDGER.md`" as an open human decision. Step 01b already did that: versions.sh:19-26 holds the pins and LEDGER.md:86-91 holds the observed values.
  - PROJECT_MEMORY "Spark line" does not record the human's `emr-spark-8.0.0` decision.
- **Impact:** Phase 02 already survived a WSL disconnect. A fresh session resuming from STATUS.md cannot tell which steps ran, and it would re-raise a closed decision.
- **Repair brief:**
  - Add the Phase 02 task list with each step's status, owner and report file. Steps 1 and 1b are toolchain, step 2 is the build-engineer verify (`agent-report.md`), step 3 is the docs swap, step 4 is the Spark comment, and review is next.
  - Remove the closed decision.
  - Record the Spark decision in PROJECT_MEMORY.
  - Keep MAJOR-2 / MINOR-4 below as a carried item.

### MAJOR-3: the Phase 2 deliverables came from a prepared package, and Phase 02 evidence does not say so
- **Files:** `build.sbt`, `project/plugins.sbt`, `project/build.properties`, `.gitignore`, `docker/services/Dockerfile`, `tests/build/**`, `README.md`, `PROJECT_MEMORY.md`, every `modules/*/MODULE.md` and all 60 or so `.gitkeep` files.
- **Owner:** the human (disclosure in `human-gate.md`) and the orchestrator (`run-log.md`).
- **Problem:**
  - The spec work order (line 999) says "Use the build-engineer agent to create ... from scratch".
  - No Phase 02 report claims to have created any of these files. Every report says `FILES CREATED: none`.
  - `agent-report.md` line 6 is "Verify the installed build", and line 98 says "The prepared build package is the intended Phase 2 skeleton".
  - The file mtimes are 2026-11-20 12:16:46 (one identical stamp for about 45 files) and 2026-11-21 12:05-12:06. Both are earlier than the Phase 1 commit (2026-11-21 23:11).
  - The package carries references to evidence from a run that is not in this repository: MAJOR-1's step-08, and MINOR-5's "phase 2 review (finding 9)".
  - The human also renamed `toolchain/versions.env` to `versions.sh` (agent-report ASSUMPTIONS). That is a toolchain-engineer file.
  - Under the authority matrix the human "must never hand-edit an agent-owned artifact without disclosing it in human-gate.md". Phase 01 disclosed its prepared package (phase-01 human-gate.md:3, run-log.md:4).
- **Impact:** provenance of the Phase 2 build is not recorded. A grader cannot tell who authored build.sbt or the black-box build test.
- **Repair brief:**
  - The orchestrator writes `docs/agent-runs/phase-02/run-log.md` with a human row "install from prepared package" and the steps that followed.
  - The human discloses in `human-gate.md`:
    - the package install;
    - the versions.env rename;
    - the terraform-mcp-server install and its checksum check.
  - No code change is needed.

### MINOR-1: the contract and adversarial test projects cannot see Hadoop or Spark types
- **File:** `build.sbt` lines 257-288. Line 258 claims "Each sees every module's public API."
- **Owner:** build-engineer.
- **Problem:**
  - `export contractTests/Test/fullClasspath` lists 45 jars, and 0 of them match `spark-sql|hadoop-client`.
  - `sparkAnalytics/Test/fullClasspath` does contain `spark-sql_2.13-4.0.1.jar` and `hadoop-client-api-3.4.1.jar`.
  - Provided dependencies do not flow through `dependsOn`. So the first black-box test that touches a Hadoop or Spark job's API will fail to compile or throw NoClassDefFoundError.
- **Why it is latent:** there are no tests yet, so `check` is green.
- **Repair brief:**
  - Add `hadoop-client-api` (and the runtime the tests need) plus `spark-sql` (`for3Use2_13`) at `% Test` to `blackBoxTests`, using the same ledger values.
  - Or correct the comment and record the limit.
  - `check` and `tests/build/check-contract.sh` must stay green.

### MINOR-2: unreported Scala standard library skew against the Spark runtime
- **Files:** `project/Versions.scala` lines 21 and 31-36; `build.sbt` lines 192-204.
- **Owner:** build-engineer.
- **Problem:**
  - The cached `scala3-library_3-3.3.9.pom` depends on `scala-library` 2.13.18. The resolved classpath shows `scala-library-2.13.18.jar`.
  - `spark-parent_2.13-4.0.1.pom` declares `<scala.version>2.13.16</scala.version>`, and Versions.scala:32 says the EMR target ships Scala 2.13.16.
  - The Scala library is not Provided, so the job assemblies will bundle 2.13.18 next to the cluster's 2.13.16.
- **Not verified:** I did not run `packageJobs` to inspect the jar.
- **Impact:** a possible NoSuchMethodError if compiled code calls 2.13.17+ library members, and two standard libraries on the classpath. The Phase 2 work order asked for "any compatibility risk involving Scala 3 ... Spark", and the agent report omits this one.
- **Repair brief:** record the risk in Versions.scala and the report. Before Phase 6, decide between a Scala 3 LTS patch that pins `scala-library` at or below the cluster version, marking scala-library Provided in the job jars, and accepting `userClassPathFirst`. Verify with `packageJobs` and a jar listing.

### MINOR-3: README states the wrong Python minimum
- **File:** `README.md` line 39 says "Python 3.10+".
- **Owner:** documentation-engineer.
- **Problem:** `toolchain/LEDGER.md` lines 91 and 104-106 and `install.sh` lines 83-85 require 3.11 or later, because of `tomllib`. Without it the guards fail open (Phase 01 audit defect 3).
- **Repair brief:** change the README line to 3.11+.

### MINOR-4 (carried, re-rated from Phase 01 MAJOR-2): markTestRun quote splitting still gets past guard-dangerous
- **File:** `.claude/hooks/guard-dangerous.sh` line 683. The regex `\bmarkTestRun\b` runs on the raw text `c`.
- **Owner:** factory-engineer.
- **Observed by piping payloads to the hook:**

  | Command | Exit code |
  |---|---|
  | `sbt markTestRun` | 2 (blocked) |
  | `sbt 'markTest''Run'` | 0 (allowed) |
  | `sbt "markTest"Run` | 0 (allowed) |
  | `sbt check` | 0 (allowed) |

- **Was Phase 02 required to fix it?**
  - Not by the spec's Phase 2 work order.
  - Yes by the human's schedule: phase-01 human-gate.md:64 deferred "MAJOR-2 (markTestRun quote splitting, Phase 02)", and STATUS.md:21-22 says to revisit it now that markTestRun exists.
  - It was not addressed. No Phase 02 step touched the hook, and `git diff HEAD` is empty.
- **Re-rating:** the build now makes markTestRun depend on every project's `Test/test` (build.sbt:321). The contract test confirms a failing test aborts it. So the bypass skips only the scalafmt check, not the tests. MINOR instead of MAJOR.
- **Repair brief:** the factory-engineer fix from the Phase 01 report still applies:
  - match on the quote-removed `$norm` or the shlex tokens;
  - add the quoted forms to `test_guard_dangerous.py`;
  - keep `TestMarkerTest` green.

  The alternative is for the human to defer it again in `human-gate.md`. This is a constitutional change, so it needs ratification.

### MINOR-5: the build contract test cites review findings that do not exist here
- **Files:** `tests/build/README.md` line 6 ("the phase 2 review (finding 9)"); `tests/build/check-contract.sh` lines 16-17 ("review finding 1", "finding 3").
- **Owner:** test-engineer.
- **Problem:** this repository has no Phase 2 review; this report is the first. The citations point to the prepared package's origin (MAJOR-3).
- **Repair brief:** replace the citations with the spec clause each scenario checks (spec line 1021 for markTestRun, line 1023 for check), or with this report.

### MINOR-6: Phase 02 evidence folder is incomplete, and one report claim is wrong
- **Files:** `docs/agent-runs/phase-02/`; `agent-report.md` lines 101 and 23.
- **Owner:** orchestrator.
- **Problem:**
  - `run-log.md` and `versions.md` are missing. docs/agent-runs/README.md:13-23 requires both in every phase folder.
  - `agent-report.md` is step 2's report, not the final owning report. Step naming jumps from step-01b to agent-report to step-03.
  - Line 101 says "The 'marker test' the work order mentions does not exist yet". But `tests/build/check-contract.sh` is that test (it checks the marker on pass and fail), and no Phase 02 agent ran it.
  - Line 23 says the marker was not confirmed.
- **Repair brief:** add run-log.md and versions.md, and save this review's contract-test output as `output-build-contract.txt`. My run: 4 passed, 0 failed.

### MINOR-7: `.github/workflows/` was not created
- **Owner:** infra-engineer.
- **Problem:** the spec asks for "empty required top-level directories from the approved ownership map" (line 1036). The ownership map has `.github/workflows/**` (infra-engineer), and no directory or `.gitkeep` exists. Every other owned top-level root exists.
- **Note:** `datasets/gold/` is human-only, so its absence is correct. The human creates it.
- **Repair brief:** infra-engineer adds `.github/workflows/.gitkeep`, or the human accepts that it arrives in Phase 13.

### NITs
- **N1** `project/plugins.sbt` line 5 (build-engineer): `ledgerPin` opens `project/Versions.scala` relative to the JVM working directory, not the build base. Launching sbt with a different `user.dir`, as some IDE imports do, fails the load.
- **N2** `project/Versions.scala` lines 23-25 (build-engineer): the `jvmRelease` comment still cites EMR 7.x. step-04 already reported this.
- **N3** `toolchain/install.sh` line 26 (toolchain-engineer): the JDK "already installed" test matches any `"17.` version, not the pinned 17.0.21.1. The versioned directory name limits the risk.
- **N4** `toolchain/install.sh` line 64 (toolchain-engineer): pins match by substring, so `0.13.9` also matches `0.13.90`. step-01b already reported this.
- **N5** `build.sbt` line 46 (build-engineer): `--release` is applied only to `Compile / compile` javacOptions, not to Test Java sources.

## Repair routing summary
- documentation-engineer: MAJOR-1, MAJOR-2, MINOR-3.
- human (gate disclosure) and orchestrator (run-log): MAJOR-3, MINOR-6.
- build-engineer: MINOR-1, MINOR-2, N1, N2, N5.
- factory-engineer (or human re-deferral): MINOR-4.
- test-engineer: MINOR-5.
- infra-engineer: MINOR-7.
- toolchain-engineer: N3, N4.

After the build-engineer fixes, `sbt check` and `tests/build/check-contract.sh` must stay green.

---

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 02 code review (orchestrator, fresh spawn, default high tier). Scope: the whole uncommitted Phase 02 tree against HEAD 3fdfeb7 (80 untracked files, no tracked changes), checked against the spec's Phase 2 work order and gate, the AGENTS.md boundaries, ownership, the hard rules and report accuracy. Run sbt check. Report MAJOR-2 status.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN (each one's successful output was observed):
- `git status --porcelain`, `git diff HEAD --stat` (empty), `git ls-files --others --exclude-standard` (80 files), `git status --ignored --porcelain`, `git log -1`
- `stat` on the untracked files (mtimes) and on `.claude/.last-test-run` (2026-11-21 23:45:02 before my run)
- `source toolchain/env.sh && sbt -batch Test/compile` -> `[success] Total time: 10 s` (openjdk 17.0.21.1, sbt 1.13.1)
- `source toolchain/env.sh && sbt -batch check` -> scalafmt checked 1 and then 2 Scala sources; 5 `[success]` lines; `markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `bash tests/build/check-scenario-selection.sh` -> `PASS whitespace-only scenario selection`, rc=0
- `TMPDIR=<scratchpad> bash tests/build/check-contract.sh` -> `build-contract: 4 passed (pass fail-contract fail-unit fail-format), 0 failed (none)`, rc=0; no warning that the real tree changed; scratch copies removed
- `sbt export` of the classpaths for sparkAnalytics (Compile and Test), servicesCommon, queryService (Runtime) and contractTests (Test, 45 jars, 0 Spark or Hadoop)
- grep of the cached `scala3-library_3-3.3.9.pom` and `spark-parent_2.13-4.0.1.pom`
- guard-dangerous probe (4 payloads piped to the hook, read-only) -> rc 2/0/0/0, as listed under MINOR-4
- `.claude/hooks/validate-agent-files.sh` -> OK, 188 files with one writer
- `.agents/render.sh --check` -> OK, 44 files
- grep for `versions.env` outside phase-02 evidence (none); grep of the build files for endpoints and secrets (none)
TEST RESULTS:
- sbt check passed with 0 test cases: there are no test sources, and the 14 aggregated project test runs are all empty.
- Build contract test: 4 of 4 scenarios passed.
- Scenario-selection regression: 1 of 1 passed.
- `sbt check` touched the real marker during my run, as designed.
VALIDATION RESULTS: REQUEST_CHANGES.
- MAJOR-1: modules/spark/MODULE.md is stale (Spark 3.5.9, runtime "undecided", dead step-08 citation). Owner documentation-engineer.
- MAJOR-2: STATUS.md has no Phase 02 task checkpoint and lists a closed decision as open; PROJECT_MEMORY lacks the Spark decision. Owner documentation-engineer.
- MAJOR-3: the deliverables were installed from a prepared package, which the Phase 02 evidence does not disclose. Owners human (human-gate.md) and orchestrator (run-log.md).
- MINOR-1: Provided Hadoop and Spark are missing from the black-box test classpath. Owner build-engineer.
- MINOR-2: Scala library 2.13.18 against the Spark runtime's 2.13.16 is not reported. Owner build-engineer.
- MINOR-3: README says Python 3.10+, but 3.11+ is required. Owner documentation-engineer.
- MINOR-4: Phase 01 MAJOR-2 is still open, re-rated with evidence. Owner factory-engineer.
- MINOR-5: dead review citations in tests/build. Owner test-engineer.
- MINOR-6: Phase 02 run-log.md and versions.md missing; the agent-report claim that no marker test exists is wrong. Owner orchestrator.
- MINOR-7: `.github/workflows/` missing. Owner infra-engineer.
- NITs N1 to N5.
- No test-fairness dispute was in scope.
ASSUMPTIONS:
- Everything the reports call "installed" or "prepared" was placed by the human. I inferred this from agent-report.md lines 6 and 98, the identical mtimes and the Phase 01 precedent. No Phase 02 document states it outright.
- The emr-spark-8.0.0 facts in Versions.scala (Spark 4.0.2, Scala 2.13.16) are the human's statement. I did not verify them.
- Scala 2.13 forward binary compatibility between 2.13.18 and 2.13.16 is unverified, so MINOR-2 is stated as a risk.
RISKS (what I did NOT review or run):
- I did not run `sbt packageJobs` or `sbt dockerLocal`, so I did not inspect assembly contents, check that Provided jars are excluded, or build an image.
- I did not verify the Docker base digest against the registry (no network check). The checksums in toolchain/versions.sh and the terraform-mcp-server checksum the human cites were not re-verified either.
- I did not test the build.sbt JDK fail-fast check (line 33) with a JRE or an older JDK.
- I did not re-review the guard-dangerous hook beyond the markTestRun block (lines 649-712).
- I did not read LICENSE beyond its header (Apache 2.0, human-owned).
- My sbt runs wrote only to the git-ignored `target/` folders and touched the real `.claude/.last-test-run` through `sbt check`, which is that file's only sanctioned writer. git status was unchanged (22 untracked entries).
MANUAL ACTION REQUIRED:
- The human discloses in `docs/agent-runs/phase-02/human-gate.md`:
  - the prepared-package install;
  - the versions.env to versions.sh rename;
  - the terraform-mcp-server install and its checksum check.
- The human decides whether to fix MINOR-4 (Phase 01 MAJOR-2) now or defer it again.
- The human confirms the Spark 4.0 / emr-spark-8.0.0 assumption and the MINOR-2 risk.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator routes MAJOR-1 and MAJOR-2 to documentation-engineer and the MINORs to their owners, and writes run-log.md and versions.md. A fresh code-reviewer re-checks the repairs. Then the human gate for Phase 02.

Relevant paths:
- /home/ajcreator/scholarchain/build.sbt
- /home/ajcreator/scholarchain/project/Versions.scala
- /home/ajcreator/scholarchain/project/plugins.sbt
- /home/ajcreator/scholarchain/modules/spark/MODULE.md
- /home/ajcreator/scholarchain/STATUS.md
- /home/ajcreator/scholarchain/PROJECT_MEMORY.md
- /home/ajcreator/scholarchain/README.md
- /home/ajcreator/scholarchain/tests/build/README.md
- /home/ajcreator/scholarchain/tests/build/check-contract.sh
- /home/ajcreator/scholarchain/toolchain/install.sh
- /home/ajcreator/scholarchain/.claude/hooks/guard-dangerous.sh
- /home/ajcreator/scholarchain/docs/agent-runs/phase-02/agent-report.md

Note for the human, repeated from the aws-api MCP server's own notice: that server is reaching end of development, and the migration guide is at https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md. This is already tracked for Phase 12 in STATUS.md.
