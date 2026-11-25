HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default effort; no override)

I wrote the runtime workflow document, updated `modules/rag/MODULE.md` and added Phase 09 to `STATUS.md`. All facts come from the Phase 09 reports and the repository. I did not run sbt or replay the benchmark, so no number was re-measured.

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 09 step 4, three parts: the runtime workflow doc, the `MODULE.md` update, and `STATUS.md` for the whole phase.

FILES CREATED:
- docs/runtime-workflow.md
  - It covers:
    - the stage and component versions;
    - the prompt and schema versions with the two SHA-256 values from the R2 report;
    - the allowed plan operations table and the graph templates used;
    - identity, evidence, verification and abstention rules, and the model path limits;
    - the audit record and the benchmark results (D 28/33, A 27/33, pass 27/33, P 25/33, semantic hit rate 6/33, precision 7/80);
    - test counts, the 13 red tests, F1 to F4 as open and not fixed, and the known failure modes.

FILES MODIFIED:
- modules/rag/MODULE.md
  - Purpose now adds the Phase 09 scope and links the new doc.
  - Owner and paths now lists the Phase 09 sources, unit suites, schemas, prompt and black-box test roots.
  - Done adds R1 (96 tests), R2 (123 tests) and step 3.
  - Not done is rewritten with: community size missing; F1 to F4 open; the reviewer verdict and repair round still to run; no DynamoDB audit repository; no production adapters for the name-lookup and ranking ports; no Bedrock or cloud run, with the model ID and cost undecided; `prompts/` not yet packaged for the service image; weak semantic retrieval; injected text not planted in the vector store.
  - Known issues adds the 2560m heap need for `adversarialTests`, the pattern-based verifier, the planner's explicit-form limits and the Phase 09 test counts.
  - The Public API section from step 3a is unchanged.
- STATUS.md
  - "Where things stand" has a Phase 09 bullet.
  - A "Phase 09 task list" has tasks 1 to 7 with owner, done means and status. Task 5 (fresh code-reviewer fairness verdict) is NEXT, task 6 is the repair round, and task 7 is the human gate.
  - The same section holds the end-of-step-4 state: test counts, the `sbt check` result, the open defects, the uncommitted files and the harness.
  - "Carried forward from Phase 09" lists seven items: community size gap; storage-engineer: name lookup, metric ranking and the audit repository; build-engineer: `adversarialTests` heap fork; `prompts/` packaging; human review of `semantic-relevance-rule-v1` and question set v1 (sha256 recorded); Bedrock model ID and per-query cost; F1 to F4.
  - It ends with the command to continue in a fresh session and a "verify first" line.

COMMANDS RUN:
- Reads of the work order's notes files and the Phase 09 reports.
- `awk 'length>90'` on the three files.
- `grep -c` for the em dash on the three files.
- `git status --short`.
- Python edit scripts, one written to the scratchpad.

TEST RESULTS: none. I did not run sbt, as instructed. All counts are quoted from the reports.

VALIDATION RESULTS:
- `docs/runtime-workflow.md` and `modules/rag/MODULE.md` have no line over 90 characters and no em dash.
- `STATUS.md` has no em dash. The only lines over 90 characters are table rows I added and lines 252, 394 and 498. I did not check whether those three were already over 90 before my edit.
- The relative links resolve: `../../docs/runtime-workflow.md` from the module folder, and `docs/runtime-workflow.md` and `../modules/rag/MODULE.md` in the docs.
- Prompt and schema hashes, benchmark scores and test counts match `agent-report.md`, `test-report-02.md` and `output-benchmark.md`.

ASSUMPTIONS:
- The 13 red tests are split as 10 D and A tests for F1, one A test for F2, one planner test (P) for F1 and F3, and one gate test for F4. The reports give 12 in `BenchmarkWorkflowSuite` plus 1 gate test, and this is my reading of them.
- The `query-plan-v1.json` hash is not recorded in the reports, so I said so instead of computing it.

RISKS:
- The `STATUS.md` task table rows exceed 90 characters.
- `STATUS.md` still says Phase 08 awaits its human gate, although `git log` shows Phase 08 committed (12829d7 and a093a92). I did not change that Phase 08 text, because it was outside this work order.
- `docs/agent-runs/phase-09/` has no human-gate file yet. Task 7 is written as "gate file written" because a guard hook blocks shell commands that name the human's gate file.
- Nothing was committed. The state left behind is these three files plus the untracked Phase 09 files listed in `STATUS.md`.

MANUAL ACTION REQUIRED: none for this step.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator spawns a fresh code-reviewer for the fairness verdict on the 13 red tests (F1 to F4), then sends repair briefs to rag-engineer, then the human gate.
