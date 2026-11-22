HARNESS: claude-code
MODEL: opus, effort high (high tier per .agents/models.json)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 02 repair round 1, task R1-4 (MINOR-5). Replace the dead review citations in tests/build/** with the spec clause each scenario checks, or with docs/agent-runs/phase-02/review-report.md where that is the honest source. Comments and README text only, no test logic changes. Default high tier, fresh context.

FILES CREATED: none in the repository. Scratch files only: pre-edit copies, the sbt log and the contract-test temp dir under /tmp/claude-1000/-home-ajcreator-scholarchain/d5e0fa5a-7b55-44f5-9599-2d444a068812/scratchpad/ (check-contract.sh, README.md, sbt-check.log, r1-4-tmp/, which the script emptied on exit).

FILES MODIFIED:
- /home/ajcreator/scholarchain/tests/build/check-contract.sh: only the header comment block, lines 11-27. The old lines 11-22 were the scenario list. I removed "(review finding 1)" and "(finding 3)" and gave each scenario its spec clause from docs/spec/assignment.md:
  - line 1021: markTestRun updates the marker only after tests pass
  - line 1023: check runs format checking, compilation, tests and contract tests
  - line 1046: the gate's "`sbt check` has a stable definition"
  pass cites 1021, 1023 and 1046 (the suite-discovery, run-once and never-compile assertions). fail-contract and fail-unit cite 1021 and 1023. fail-format cites 1023 and 1021.
- /home/ajcreator/scholarchain/tests/build/README.md, line 6: I replaced "the manual scratch evidence from the phase 2 review (finding 9)". The new text says this script is the marker test the spec's Phase 2 work order names: line 1038, "sbt check once the marker test exists". It cites review-report.md MINOR-6, which records that, and lists clauses 1021, 1023 and 1046.

COMMANDS RUN (I saw the successful output of each):
- `grep -rniE "finding *[0-9]|phase ?2 review|review finding|\(finding" tests/ PROJECT_MEMORY.md`: before the edit, exactly the 2 citations the review named (README.md:6, check-contract.sh:16-17). A broader `grep -rniE "review|finding|step-0" tests/` found nothing else.
- `diff` of the old and new check-contract.sh, plus a diff of the non-comment lines only: the non-comment lines are identical, so test logic is unchanged.
- `bash -n tests/build/check-contract.sh`: SYNTAX-OK.
- `bash tests/build/check-scenario-selection.sh`: "PASS whitespace-only scenario selection", exit 0.
- The citation grep again after the edit: no matches (grep exit 1).
- `TMPDIR=<scratchpad>/r1-4-tmp bash tests/build/check-contract.sh`, run alone with no other sbt: "build-contract: 4 passed (pass fail-contract fail-unit fail-format), 0 failed (none)", exit 0. The temp copies were deleted on exit.
- `sbt -batch check` on the real tree, after the contract run finished: exit 0, scalafmt check passed, "markTestRun: 14 project test runs passed, touched .claude/.last-test-run".
- `git status --porcelain | wc -l`: 22 untracked entries, the same count the review recorded.

TEST RESULTS:
- tests/build/check-contract.sh: 4 passed, 0 failed (pass, fail-contract, fail-unit, fail-format). Every assertion line showed "ok".
- tests/build/check-scenario-selection.sh: 1 passed, 0 failed.
- sbt check: green. It ran 14 project test runs, which contain no Scala test cases yet; sbt printed no per-suite counts.
- No failing tests.

VALIDATION RESULTS: MINOR-5 is resolved. No citation to a review that does not exist is left anywhere under tests/. Each build-contract scenario now names the spec line it protects:
- 1021: marker only after tests pass
- 1023: what check runs
- 1046: stable definition of check
- 1038: the marker test itself

ASSUMPTIONS:
- Spec line 1046 ("sbt check has a stable definition") is the honest anchor for the layout properties: the never-compiled stray .scala files and each suite running exactly once. No spec line names those properties directly; they come from build.sbt's test layout, which the script header already gives as the second source of the contract.
- I did not read src/main/**. I read only the named notes, the review report, the spec's Phase 2 section and tests/build/**.

RISKS:
- Line numbers in the spec citations go stale if the human edits docs/spec/assignment.md. The quoted clause text next to each number limits the damage.
- My comment rewrite moved the script header from 22 to 27 lines, so the script's later line numbers shifted by 5. Any document that cites check-contract.sh by line number beyond line 22 is now off by 5. I found no such citation under tests/ but did not search docs/.
- A helper wait command I started (`timeout 580 tail -f <output file> | grep -m1`) was moved to the background after it matched. It may stay idle until its 580 s timeout. It only reads a scratch task file and writes nothing.
- I did not do anything outside tests/build/**.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: R1-5 (infra-engineer, MINOR-7), then R1-6 (orchestrator, MINOR-6; it may save the contract-test output above as output-build-contract.txt), R1-7 STATUS update, then Review 2 by a fresh code-reviewer.

---
Orchestrator note (not part of the agent report): the non-comment lines of check-contract.sh
are identical to ~/p2-orig, and the README diff matches the report. A grep of docs/,
README.md, STATUS.md and modules/ (agent-runs excluded) found no line-number citation of
check-contract.sh.
