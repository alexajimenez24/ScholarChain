# STATUS

<!-- Owner: documentation-engineer. Updated after every task so a later session can
resume. -->

## Where things stand

- Phase 00: seed `factory-engineer` committed.
- Phase 01: agent system committed after a factory-engineer audit, three repair rounds
  and two code-reviewer passes. Evidence and the accepted known gaps are in
  [docs/agent-runs/phase-01/](docs/agent-runs/phase-01/human-gate.md).
- Phase 02: committed as 3de681d (`docs/agent-runs/phase-02/commit.txt`).
  Sbt build skeleton, toolchain ledger, module and test roots, service image
  Dockerfile. Repair round 1 is complete, review 2 APPROVE, and the Phase 02 gate is done.
- Phase 03: contracts, configuration and dataset manifest.
  All tasks and repair rounds 1 and 2 are done, and review 3 is APPROVE.
  Committed as 9a65cf2, followed by 524dc1d "phase-03: record accepted commit"
  (`git log --oneline -5` and `docs/agent-runs/phase-03/commit.txt`, which holds
  9a65cf2e4de050017e56db15505a27a8fd0be916).
- Phase 04: Hadoop MapReduce pipeline, committed.
  `git log --oneline -3` shows a43fa55 "phase-04 hadoop-engineer: DBLP parser, source
  normalizers, MapReduce jobs J01-J07" and 4388607 "phase-04: record accepted commit".
  Items N1 to N7 stay open; see "Phase 04 task list" and "Carried forward from Phase 04".
- Phase 05: entity resolution, committed.
  `git log --oneline -2` shows acb5f21 "phase-05: record accepted commit hash" and
  4dfb3bd "phase-05 entity-resolution-engineer: er-v1 matching, adjudication port,
  review queue, gold-set evaluation"; `docs/agent-runs/phase-05/commit.txt` holds
  4dfb3bd46d3c0ea2abd50d4e386ce5158ea767c7.
  v1 was labeled (280 rows), v1b was not used (decision 03), and Review 2 was APPROVE
  WITH CARRIED-FORWARD ITEMS.
  See "Phase 05 task list" and "Carried forward from Phase 05 (for the human gate)".
- Phase 06: Spark collaboration analysis, committed.
  `git log --oneline -3` shows 217b7fc "phase-06 spark-engineer: coauthorship graph and
  Spark analytics" and d02da39 "phase-06: record accepted commit hash".
  Review 1 REQUEST_CHANGES; Review 2 APPROVE WITH CARRIED-FORWARD ITEMS.
  See "Phase 06 task list", "Carried forward from Phase 06 (for the human gate)" and
  "Current state (phase 06)".
- Phase 07: storage adapters and loading, committed.
  `git log --oneline -3` shows 2c86331 "phase-07 storage-engineer: S3, DynamoDB, Neptune
  and S3 Vectors adapters and loaders" and 1a727a1
  "phase-07: record accepted commit hash".
  The human gate is `docs/agent-runs/phase-07/human-gate.md`, which approves starting
  Phase 08 and routes four items to Phase 08 step 00: the JSON-lines export,
  `institutionId`, paper-abstract rows, and every coauthor window in `neptune.json`.
  See the Phase 07 task list and "Carried forward from Phase 07".
- Phase 08: embeddings and S3 Vectors ingestion, committed.
  `git log --oneline -2` shows a093a92 "phase-08: record accepted commit hash" and
  12829d7 "phase-08 storage-engineer, rag-engineer: embeddings, S3 Vectors ingestion,
  dataset scale-up".
  Steps S1 to 04, Review 1 and repair round 1 (R1-0 to R1-4) were done before the
  commit (see Phase 08 task list).
  No cloud was used.
  The dataset was stepped down from 100,000 to 25,000 works (Spark failed at 100,000).
  Spark run `run-ccf619c509611f06` is the input of every later step.
  Local ingestion put 28,527 of 223,953 source rows (31,341 chunks) into five in-memory
  indexes with 0 rejected.
  `semantic-context-v1` and its smoke tests are in rag (66 tests after the repair).
  The full `sbt check` after the repair exited 0 in 356 s
  (`docs/agent-runs/phase-08/output-sbt-check-r1.txt`).
  Review 2 is not needed, because Review 1 had no CRITICAL or MAJOR finding.
  The bounded AWS ingestion of 10,000 vectors, AWS query latency and real cost are NOT
  RUN until the cloud phases.
  The embedding docs are `docs/embeddings.md`; lineage is `docs/data-lineage.md`.
  See "Carried forward from Phase 08".
- Phase 09: runtime collaboration workflow (Graph-RAG stages 1 to 10) in module `rag`;
  steps 1 to 4, reviews 1 to 4 and repair rounds 1 to 3 are done, and nothing is
  committed.
  Review 1 was REQUEST_CHANGES (M1 to M7, m1 to m3); round 1 fixed F1 to F4 and M1 to M7
  (M5 partly, defect D5).
  Review 2 was REQUEST_CHANGES on D5, and the human authorized a round 2 exception.
  Review 3 was REQUEST_CHANGES (R3-1 to R3-4), and the human authorized a round 3.
  Round 3 replaced the clause rule with a sentence rule: a per-researcher number is
  VERIFIED only with one exact-display-name researcher in its sentence and no pronoun.
  Review 4 (`review-report-04.md`) was REQUEST_CHANGES: the defect class is STILL OPEN
  (R4-1 variant or partial names, R4-2 noun phrases and unlisted abbreviations, R4-3 a
  cited ranking item backs any researcher's number), all MAJOR, model path only
  (`LLM_ENABLED` defaults to false).
  The Phase 09 gate is not met on "Code review returns APPROVE" and on "unsupported
  claims weakened" for the model path.
  The full `sbt check` exited 0 in 789 s.
  No cloud or Bedrock call was made.
  See "Phase 09 task list", "Carried forward from Phase 09" and
  [docs/runtime-workflow.md](docs/runtime-workflow.md).

## Phase 09 task list

Phase 09 is the runtime collaboration workflow, work order in
`docs/agent-runs/phase-09/prompt.md`; reports are saved in `docs/agent-runs/phase-09/`.
Inputs: Spark run `run-ccf619c509611f06` and ER run `run-cb5a0421c406737d`.
The run log names the deadline 2026-11-25.

| Task | Owner | Done means | Status |
|---|---|---|---|
| 1 R1: stages 1 to 7, query-plan-v1 | rag-engineer | rag/test 96 of 96 | done (BLOCKED-ON community size, carried forward) |
| 2 R2: stages 8 to 10, prompt and schema v1 | rag-engineer | rag/test 123 of 123 | done |
| 3 r1: question set v1 | test-engineer | 40 of 40, check exit 0 | done (workflow tests blocked on API docs) |
| 3a: Public API in MODULE.md | documentation-engineer | signatures from source | done |
| 3 r2: workflow benchmark, gate tests | test-engineer | exact counts, one check | done (13 red on purpose) |
| 4: workflow doc, MODULE.md, STATUS.md | documentation-engineer | links, line length | done |
| 5: review 1 | code-reviewer (fresh) | verdict per defect | done, REQUEST_CHANGES (`review-report.md`) |
| 6a: repair M1 to M6 (F1 to F4, M5, M6) | rag-engineer | rag/test green | done, 131 of 131 (`repair-01-rag-engineer.md`) |
| 6b: repair M7, adversarialTests heap | build-engineer | default `SBT_OPTS` works | done (`repair-01-build-engineer.md`) |
| 6c: rerun, new M5 and M6 gate suite, one check | test-engineer | exact counts | done, one red test D5 (`repair-01-test-engineer.md`) |
| 6d: docs for the repair | documentation-engineer | links, line length | done |
| 7: re-check | code-reviewer (fresh) | `review-report-02.md` | done, REQUEST_CHANGES on D5 |
| 7a: repair round 2, D5 only (human-authorized exception) | rag-engineer | rag/test green | done, 133 of 133 (`repair-02-rag-engineer.md`) |
| 7b: rerun, one check | test-engineer | exact counts | done, check exit 0, 734 s (`repair-02-test-engineer.md`) |
| 7c: docs for repair round 2 | documentation-engineer | links, line length | done |
| 7d: re-check, D5 and regression only | code-reviewer (fresh) | `review-report-03.md` | done, REQUEST_CHANGES (R3-1 to R3-4) |
| 7e: repair round 3, sentence rule (second human-authorized exception) | rag-engineer | rag/test green | done, 135 of 135 (`repair-03-rag-engineer.md`) |
| 7f: R3 gate suite, one check | test-engineer | exact counts | done, R3Gate 6 of 6, check exit 0, 789 s (`repair-03-test-engineer.md`) |
| 7g: review 4, R3-1 to R3-3 and regressions | code-reviewer (fresh) | `review-report-04.md` | done, REQUEST_CHANGES (R4-1 to R4-3, class still open) |
| 7h: docs for round 3, R3-4 | documentation-engineer | links, line length | done |
| 8: human gate | human | decide R4-1 to R4-3, ratify, commit | NEXT |

Evidence by task:
- Task 1: `step-01-rag-engineer-report.md`; task 2: `agent-report.md`.
- Task 3: `test-report.md`, `test-report-02.md` and `output-benchmark.md`.
- Task 3a: `step-03a-documentation-engineer-report.md`.
- Tasks 7 to 7b: `review-report-02.md`, `repair-02-rag-engineer.md`,
  `repair-02-test-engineer.md`, `run-log.md` and the last section of
  `output-benchmark.md`.
- Tasks 5 to 6c: `review-report.md`, `repair-01-rag-engineer.md`,
  `repair-01-build-engineer.md`, `repair-01-test-engineer.md`, `run-log.md` and the last
  section of `output-benchmark.md`.

State at the end of repair round 1 (review 1 was REQUEST_CHANGES; superseded by the
round 2 state below):
- Benchmark: D 33/33, A 33/33, pass 33/33 and P 33/33; semantic hit rate 6/33 and
  precision 7/80 are unchanged (details in `docs/runtime-workflow.md`).
- F1 to F4 and M1 to M7 are fixed (M5 partly, see D5); the 13 tests that were red now
  pass.
- M7 is done: `adversarialTests` is forked at `-Xmx2560m -Xss4m`, so the default
  `SBT_OPTS` (`-Xmx1536m -Xss4m`) works.
  The heap carry-forward is removed.
- Tests: `sbt rag/test` 131 of 131; BenchmarkQuestionSetSuite 40 of 40;
  BenchmarkWorkflowSuite 68 of 68; WorkflowGateSuite 29 of 29;
  WorkflowRepairGateSuite (new, M5 and M6) 5 of 6.
- Full `sbt check` at the default `SBT_OPTS`: exit 1 in 771 s, once.
  adversarialTests: Failed 1 of 211, only the D5 test.
  The test-marker task was not reached, so `.claude/.last-test-run` is not touched and
  `sbt check` is NOT green.
- Defect D5 (rag-engineer, open): M5 is only partly fixed.
  The verifier attributes a number by position, to the nearest researcher named before
  it.
  A false number is VERIFIED when another cited researcher is named between the subject
  and the number: "Hans Hagen, compared with Jan Mohring, has 5 distinct coauthors in
  window 2010-2014." citing both metric records is VERIFIED, and 5 is Jan Mohring's value.
- The repair is not yet re-checked: no second repair round is planned, so the fresh
  re-check (`review-report-02.md`) comes next, then the human gate.
- Uncommitted: `build.sbt` and `modules/rag/MODULE.md` modified; new rag sources,
  `prompts/runtime/`, `prompts/schemas/answer-synthesis-v1.json`,
  `prompts/schemas/query-plan-v1.json`, `tests/module-unit/rag/` suites,
  `tests/adversarial/` rag suites (including `WorkflowRepairGateSuite.scala`) and
  resources, `tests/benchmark/`, `docs/agent-runs/phase-09/`,
  `docs/runtime-workflow.md` and this file.
- Half done: nothing is half written; the one red test is the reported state.
- Harness: Claude Code only.

State at the end of repair round 2 (D5 only; the human authorized the exception to the
one-round rule):
- D5 is fixed in `Verification.scala` by a clause rule that replaces the positional rule
  (see [docs/runtime-workflow.md](docs/runtime-workflow.md)).
  A number gets a subject only when its clause makes it clear; otherwise no
  per-researcher record supports it, and the claim is PARTIALLY_SUPPORTED with
  `[unverified]`.
  The repair-01 statement that positional attribution "never verifies a false one" is
  superseded.
- Disclosed: correct comparative prose such as "Alice, like Bob, has 41" is weakened
  and labeled on purpose.
- Residuals, not fixed: the contrived "A: unlike B, 30", pronoun subjects, windows not
  attributed to a researcher, swapped values inside a ranking and unkeyed ranking numbers.
- Tests: `sbt rag/test` 133 of 133; BenchmarkQuestionSetSuite 40 of 40;
  BenchmarkWorkflowSuite 68 of 68; WorkflowGateSuite 29 of 29; WorkflowRepairGateSuite
  6 of 6; WorkflowRepairD5GateSuite (new) 2 of 2; adversarialTests 213 of 213.
  Benchmark unchanged: D, A, pass and P 33/33, semantic 6/33 and 7/80.
- Full `sbt check` at the default `SBT_OPTS`, once: exit 0 in 734 s, 14 project test runs
  passed, test marker touched.
- Uncommitted: as in round 1, plus `WorkflowRepairD5GateSuite.scala`; changed in this
  round: `Verification.scala`, `VerificationSuite.scala`, `WorkflowSuite.scala`.
- Half done: nothing.
  The re-check (`review-report-03.md`) later ran; see round 3 below.

State at the end of repair round 3 (second human-authorized exception):
- Round 3 replaced the clause rule with a sentence rule in `Verification.scala`.
  A per-researcher number is VERIFIED only when its sentence names exactly one
  researcher, recognised by exact display name, and has no pronoun.
  Otherwise it is weakened and labeled, never CONFLICTING for per-researcher records;
  pair values held by an edge, path or ranking may still be CONFLICTING.
  This supersedes the round 2 clause rule and the "pronoun subjects are weakened, not
  resolved" statement of review 3 R3-4, which was wrong when the clause names another
  researcher.
- Tests (rag-engineer): `sbt rag/test` 135 of 135; five assertions weakened on purpose.
- Tests (test-engineer): WorkflowRepairR3GateSuite (new) 6 of 6.
  One full `sbt check` at the default `SBT_OPTS`: exit 0 in 789 s, 14 project test runs
  passed, test marker touched; rag 135, adversarialTests 219.
- Review 4 (`review-report-04.md`): REQUEST_CHANGES.
  R3-1 to R3-3 are fixed as written and no regression was found.
  D5 and its class are NOT claimed fixed: the class is STILL OPEN with three MAJOR
  findings, rag-engineer, model path only (`LLM_ENABLED` defaults to false):
  - R4-1: a variant or partial name is not a mention (`Verification.scala:321-336`,
    `:460-471`);
  - R4-2: a noun phrase ("the leader", "the former", "X's closest coauthor") is not a
    pronoun, and an unlisted abbreviation such as "dir." splits a sentence;
  - R4-3: a cited ranking item backs any researcher's number (`Verification.scala:476`,
    `:509-514`).
- The human carries R4-1 to R4-3 forward; they must be closed before Bedrock or the LLM
  is enabled.
- `claim-verifier-v1` was not bumped (the human's call).
- Phase 09 gate: "Code review returns APPROVE" NOT met; "unsupported claims removed,
  weakened or labeled" NOT met on the model path; `sbt check` green; other items as in
  review 3.
- Uncommitted: as in round 2, plus `WorkflowRepairR3GateSuite.scala`; changed in round 3:
  `Verification.scala`, `VerificationSuite.scala`, `WorkflowSuite.scala`.
- Half done: nothing.

## Carried forward from Phase 09 (for the human gate)

1. Community size is missing evidence: Spark exports communities only as Parquet, and
   core `ResearcherMetrics` has no `communitySize`.
   Fixing it needs spark-engineer (JSON-lines export) and storage-engineer (loader and
   read access), and would change the pinned Phase 08 run.
   The orchestrator carried it to the human (`run-log.md`, step 1).
2. storage-engineer: a name-lookup access pattern (for `ResearcherDirectory`), a
   per-window metric ranking access pattern (for `MetricRanking`) and a DynamoDB
   `AuditRecord` repository (conditional put) behind `AuditLog`.
3. build-engineer or service-engineer (Phase 10): ship `prompts/` with the query service
   image, or add it as a rag resource directory, so `SynthesisPrompt.load` finds it.
4. Human review: `semantic-relevance-rule-v1` (labels are not human-reviewed) and
   benchmark question set v1 (`tests/adversarial/resources/benchmark/questions-v1.json`,
   sha256 8584d275357c0b64445d60a7c7edcfb4e24a12d7b1b09d5b7407d25905b58e65).
5. Human decision before Bedrock is enabled: the synthesis model ID
   (`SYNTHESIS_MODEL_ID`) and the per-query cost.
   A query can send up to about 36k input tokens (3 attempts) plus the template.
6. D5 is fixed only for the named forms; review 4 found the class STILL OPEN:
   R4-1 (variant or partial names), R4-2 (noun phrases, "X's closest coauthor", an
   unlisted abbreviation such as "dir.") and R4-3 (a cited ranking item backs any
   researcher's number), all MAJOR, rag-engineer, model path only.
   The human decides; they must be closed before Bedrock or the LLM is enabled.
7. MINOR findings of reviews 1 and 2, not fixed except m3:
   - m1 (rag-engineer): an exact-name lookup that finds one node never consults the
     token-order key, so swapped-order homonyms are not listed as candidates
     (`Agents.scala:313-317`).
   - m2 (rag-engineer): the name check accepts a name as a substring of any cited value,
     so "Wei Li" passes on "Wei Lin" (`Verification.scala:285`).
   - m3 (documentation-engineer): the stale Phase 08 text in this file is fixed (Phase 08
     is committed at 12829d7 and a093a92).
   - m4 (rag-engineer): a topic phrase such as "collaboration graphs" gives a filter that
     matches no Topic node, so the answer abstains (`Plan.scala:507-512`).
   - m5 (documentation-engineer): the "M1 to M6" wording is fixed; this file and
     `modules/rag/MODULE.md` now say M1 to M7 fixed and D5 fixed only for the named forms.
8. Not fixed, named by rag-engineer and test-engineer:
   - a window is not attributed to a researcher: a claim pairing one researcher's value
     with the window of another cited record still passes the window check;
   - swapped values inside one cited ranking ("Carol (41), Alice (52)") are accepted,
     because only the order is checked;
   - the reviewer's ranking-plus-name M5 variant has no test;
   - the round 2 residuals "A: unlike B, 30" and pronoun subjects are replaced by
     R4-1 to R4-3 (item 6).
9. Component version strings were not bumped although behavior changed (planner-v1,
   claim-verifier-v1, deterministic-synthesis-v1, evidence-pipeline-v1,
   local-runtime-v1); rag-engineer kept v1 because the phase is uncommitted.
   The human decides whether to bump them.

### Continue in a fresh session

```text
cd ~/scholarchain && claude "Read STATUS.md and continue with its next step."
```

The next step is the human's decision on R4-1 to R4-3 (carry forward, closing them
before Bedrock or the LLM is enabled, or authorize another round), then Phase 09
ratification and commit.
The other carried-forward items stay open: m1, m2, m4, community size, the Bedrock model
ID and per-query cost, the review of question set v1 and `semantic-relevance-rule-v1`,
and the `claim-verifier-v1` version string.

Verify first: `git status --short` and `ls docs/agent-runs/phase-09`.

## Carried forward from Phase 01

- Claude Code is the only harness used; the Codex adapters are rendered and validated
  but out of scope.
- Hook script edits take effect immediately; `settings.json` changes need a restart.
- Keep Claude Code's auto mode off in this repository until the Phase 11 guard fixes.
- Phase 11: MINOR-4 of review 1 (markTestRun quote splitting in guard-dangerous),
  deferred by the human on 2026-11-22; the bypass now skips only the format check.
- Phase 05: gold-set bypasses (`rm -rf datasets` and similar) and MINOR-1 (gold-set
  interpreter writes).
- Phase 11: R3-CRITICAL-1, R3-MAJOR-1, R3-MINOR-1 and R3-MINOR-2 from
  `docs/agent-runs/phase-01/review-report-02.md`.
- Phase 12: terraform MCP server, AWS-side scale caps (MAJOR-3), aws-api MCP migration.
- Phase 13: MAJOR-5 (cleanup.sh piping).

## Carried forward from Phase 02 (pending the human's choice)

- MINOR-A: the black-box test classpath has two SLF4J providers, and no fork or
  `--add-opens` for a local SparkSession (build-engineer, before Phase 11 tests).
- MINOR-B: closed in Phase 06 step 04; `modules/spark/MODULE.md` Known issues now
  records the scala-library exclusion and its residual risk.
- MINOR-D: no automated test for the Spark jar exclusion or the black-box classpath
  (test-engineer, after the MINOR-2 decision).
- N-a: stale EMR 7.x text in `docker/services/Dockerfile:6` and
  `toolchain/LEDGER.md:64-65` (build-engineer, toolchain-engineer).
- N-c: slf4j-api is bundled in the job jars (build-engineer, Phase 6).
- N-d: `vmatch` accepts pre-release suffixes (toolchain-engineer).

## Phase 02 task list

Reports are in `docs/agent-runs/phase-02/`.

| Step | Owner | Report | State |
|---|---|---|---|
| 01 toolchain | toolchain-engineer | `step-01-toolchain-engineer-report.md` | done |
| 01b observed versions | toolchain-engineer | `step-01b-toolchain-engineer-report.md` | done |
| 02 verify installed build | build-engineer | `agent-report.md` | done |
| 03 docs | documentation-engineer | `step-03-documentation-engineer-report.md` | done |
| 04 Spark comment | build-engineer | `step-04-build-engineer-report.md` | done |
| Review 1 | code-reviewer | `review-report.md` | done, REQUEST_CHANGES |

Repair round 1 (reports saved as `repair-01-<owner>.md`):

| Task | Owner | Scope | State |
|---|---|---|---|
| R1-1 | documentation-engineer | MAJOR-1, MAJOR-2, MINOR-3 | done (edits made, see its report) |
| R1-2 | build-engineer | MINOR-1, MINOR-2, nits N1, N2, N5 | done, see note 1 |
| R1-3 | toolchain-engineer | nits N3, N4 | done, see note 3 |
| R1-4 | test-engineer | MINOR-5 | done, see note 4 |
| R1-5 | infra-engineer | MINOR-7 | done, see note 5 |
| R1-6 | orchestrator | MINOR-6: `run-log.md` and `versions.md` | done, see note 6 |
| R1-7 | documentation-engineer | STATUS update | done |
| Review 2 | code-reviewer (fresh) | `review-report-02.md` | done, APPROVE |
| Human gate | human | the phase 02 gate file | done, committed 3de681d |

Note 1 (R1-2, report `repair-01-build-engineer.md`): sbt check and
check-contract are green.
The MINOR-2 option is open for the human before Phase 6.
The task was re-run from the start after a WSL crash interrupted the first attempt.

Note 2: the WSL crash interrupted repair round 1 during R1-2.
Partial edits are recorded in `docs/agent-runs/phase-02/output-r1-2-partial-edits.txt`.
The clean pre-phase package copy is at `~/p2-orig` for `diff -r` checks.
All Phase 02 files are untracked.

Note 3 (R1-3, report `repair-01-toolchain-engineer.md`): exact version-token matching
in `toolchain/install.sh` for nits N3 and N4.
`bash -n` and a no-op `install.sh` run passed.
The scratch matcher test passed 14 of 14.

Note 4 (R1-4, report `repair-01-test-engineer.md`): dead review citations in
`tests/build/` were replaced with spec clauses (lines 1021, 1023, 1038 and 1046).
Only comments changed.
check-contract 4 passed, scenario-selection 1 passed, and sbt check is green.

Note 5 (R1-5, report `repair-01-infra-engineer.md`): `.github/workflows/.gitkeep` was
created as an empty file (MINOR-7).
`.claude/hooks/validate-agent-files.sh` exited 0 with 198 files having one writer.

Note 6 (R1-6, orchestrator): the orchestrator wrote `run-log.md`, `versions.md` and
`repair-01.md` (MINOR-6).
Its `sbt check` exited 0 (`output-sbt-check.txt`).
Its build contract run passed 4 and failed 0 (`output-build-contract.txt`).

The Phase 02 gate is done: the human committed the phase as 3de681d.
The open choices on review 2 MINOR-A, MINOR-B and MINOR-D are listed under
"Carried forward from Phase 02" above.
MINOR-C (orchestrator evidence notes) is already corrected and has not been reviewed.

Decisions and deferrals recorded this round:

- Spark runtime target is `emr-spark-8.0.0` (human, 2026-11-22); see PROJECT_MEMORY.
- MAJOR-3 (prepared package provenance) is disclosed by the human in the gate file.
- MINOR-4 is deferred to Phase 11 with the other guard findings.

After the build-engineer fixes, `sbt check` and `tests/build/check-contract.sh` must stay
green.
Committed: 3de681d (`git log`: "phase-02: record accepted commit").

## Phase 03 task list

Phase 03 is contracts, configuration and dataset manifest (domain-engineer,
dataset-engineer), per
[the spec](docs/spec/assignment.md#phase-3-define-contracts-configuration-and-dataset-manifests).
Reports are in `docs/agent-runs/phase-03/`.

| Step | Owner | Scope | Report | Done means / check | State |
|---|---|---|---|---|---|
| T00 | documentation-engineer | write this task list | (this report, saved by orchestrator) | table present | done |
| T01 | dataset-engineer | datasets/dataset-manifest.yaml, datasets/scripts, datasets/samples, datasets/schemas | `step-01-dataset-engineer-report.md` | manifest validator and script dry runs pass; every license/snapshot/size/checksum/endpoint re-observed or UNVERIFIED | done |
| T01s | documentation-engineer | STATUS after T01 | - | STATUS updated | done |
| T02 | domain-engineer | modules/core records, IDs, enums, validation, YAML-JSON config loader, tests in tests/module-unit/core | `agent-report.md` | `sbt check` green | done |
| T02d | documentation-engineer | modules/core/MODULE.md, PROJECT_MEMORY decisions, STATUS after T02 | - | MODULE.md states the JSON-compatible YAML restriction | done |
| T03 | code-reviewer (fresh) | review of the whole Phase 03 diff | `review-report.md` | APPROVE, or repair rounds (max 3) | done, REQUEST_CHANGES (2 major, 11 minor) |
| T04 | documentation-engineer | final STATUS, phase ready for the human gate | - | STATUS updated | done |

Note T01 (report `docs/agent-runs/phase-03/step-01-dataset-engineer-report.md`):
dataset-engineer COMPLETE.
The manifest covers dblp, openalex, crossref and orcid, plus synthetic fixtures.
`validate-manifest.py` is ok, 9 Python unittest tests pass, and the samples total
163579 bytes.
No real ORCID record is committed.
The Python tests in tests/module-unit/datasets are not run by `sbt check` (open:
build-engineer or test-engineer).

Note T02 (report `docs/agent-runs/phase-03/agent-report.md`): domain-engineer COMPLETE.
`sbt check` exit 0, 69 core tests pass, 18 frozen wire examples.
`modules/core/MODULE.md` and the PROJECT_MEMORY decisions were updated in T02d.

Phase 03 repair round 1 (reports saved as `repair-01-<owner>.md`):

| Task | Owner | Scope | State |
|---|---|---|---|
| R1-1 | domain-engineer | MAJOR-1, MAJOR-2, MINOR-1 to MINOR-6; sbt check | done (sbt check green, 89 core tests) |
| R1-2 | dataset-engineer | MINOR-7 to MINOR-10 (leak only); validator and Python tests | done, see note R1-2 |
| R1-3 | documentation-engineer | MINOR-5 and MINOR-11 notes; MODULE.md after R1-1 | done |
| Review 2 | code-reviewer (fresh) | whole Phase 03 diff, `review-report-02.md` | done, REQUEST_CHANGES (0 major, 4 minor) |

Note R1-1 (report `docs/agent-runs/phase-03/repair-01-domain-engineer.md`):

- MAJOR-1, MAJOR-2 and MINOR-1 to MINOR-6 are fixed.
- MINOR-5 was decided without a code change: the researcher ID is stable within a run
  and policy, and an ORCID is the cross-run key.
  A doc comment and a test were added.
- Decoding is now strict: an unknown field or a lossy value is rejected.
- The orchestrator counted 19 frozen wire files on disk; the report's note 8 says 18.
  Checked by listing `tests/module-unit/core/resources/wire/`: 19 files, including the
  new `author-mention.json`.

Note R1-2 (report `docs/agent-runs/phase-03/repair-01-dataset-engineer.md`):

- MINOR-7 to MINOR-10 are fixed, the leak only for MINOR-10.
- `dblp.dtd` is committed as `datasets/samples/real/dblp.dtd` (sha256 2d864498...1ef6).
- 15 Python tests pass (9 before).
- No S3 upload path remains in `datasets/`, and the ORCID step needs `WITH_ORCID=1`.

Note R1-3 (documentation-engineer): `modules/core/MODULE.md` and PROJECT_MEMORY updated
for repair round 1.
MODULE.md now counts 19 frozen wire files on disk (the domain-engineer report says 18),
18 schema versions and 89 core tests.

Phase 03 repair round 2 (reports saved as `repair-02-<owner>.md`):

| Task | Owner | Scope | State |
|---|---|---|---|
| R2-1 | domain-engineer | MINOR-1 and MINOR-3 | done (94 tests, 13 mutation checks) |
| R2-2 | dataset-engineer | MINOR-2 | done (17 Python tests) |
| R2-3 | documentation-engineer | MINOR-4 | done |
| R2-V | orchestrator | verify after the hung R2-1 session | done, see note R2-V |
| Review 3 | code-reviewer (fresh) | whole Phase 03 diff, `review-report-03.md` | done, APPROVE (0 critical, 0 major, 0 minor) |

Note R2: review 2 is `docs/agent-runs/phase-03/review-report-02.md`.
R2-1 took about 8.5 hours of wall time, because of the agent's own background polling.
Residual items from review 2 are now in `modules/core/MODULE.md` "Known issues".

Note R2-V (orchestrator, `run-log.md` row R2-V): the orchestrator verified after the
human stopped the hung R2-1 session.
All 13 mutated rules are present in `Config.scala` and `Validate.scala`, so nothing
was restored.
A single `sbt check` exited 0 with 94 passed.
Evidence: `docs/agent-runs/phase-03/output-r2-verify-sbt-check.txt` and
`docs/agent-runs/phase-03/output-r2-1-mutation-checks.txt`.

Note R3 (report `docs/agent-runs/phase-03/review-report-03.md`): review 3 was a fresh
code-reviewer on opus, high.
Verdict APPROVE, with 0 critical, 0 major and 0 minor findings.
All review 2 findings, MINOR-1 to MINOR-4, are FIXED.

Open items for later phases:

- A reserved work-namespace list, so that work IDs and researcher IDs are distinguishable
  (open design point from the domain-engineer repair report; domain-engineer).
- Phase 4 and 5: the contract has no ORCID profile record and no match-candidate record
  (domain-engineer, additive change with a new schema version).
- Contract-side dataset questions 1 to 8 are listed in
  `docs/agent-runs/phase-03/agent-report.md` (end of the report).
- Wiring the `tests/module-unit/datasets` Python tests into `sbt check` is still open
  (build-engineer; review 1 MINOR-10, review 2).
- Guard-dangerous false positives on read-only commands were seen in the Phase 03
  review: a `python3 -c` naming a settings file, and a `sed` without `-i` naming the
  gold-set path (factory-engineer, Phase 11).

Owners run one at a time; never more than one sbt at once (addendum).

## Phase 04 task list

Phase 04 is the Hadoop MapReduce pipeline (work order and human addendum in
`docs/agent-runs/phase-04/prompt.md`).
Reports are saved in `docs/agent-runs/phase-04/`.
The reference material in `~/ref-phase-04/` is a starting point only; each owner
adapts it and owns the result.

| Step | Owner | Report | Done means / check | State |
|---|---|---|---|---|
| T0 task list | documentation-engineer | (this report, saved by orchestrator as `step-00-documentation-engineer-report.md`) | STATUS.md lists tasks | done |
| T1 Hadoop test runtime | build-engineer | `step-01-build-engineer-report.md` | hadoop-client-runtime and jcl-over-slf4j at Test scope, forked hadoop test JVM -Xmx1g, versions only from project/Versions.scala; sbt check green | done, see note T1 |
| T2 Hadoop MapReduce pipeline | hadoop-engineer | `agent-report.md` | jobs 1-7 of the work order, unit + LocalJobRunner integration tests on datasets/samples fixtures, counts derived by hand; sbt check green; local bounded pipeline run with counters reported | done, see note T2 |
| T2b MODULE.md public API | documentation-engineer | `step-03a-documentation-engineer-report.md` | Public API and Done sections written from agent-report.md | done |
| T3 Phase 4 adversarial gate cases (Hadoop only) | test-engineer | `test-report.md` | tests/adversarial Hadoop cases: adversarial XML, Unicode, missing-field, duplicate-ID, common-name, giant-team; sbt check result reported | done, see note T3 |
| T4 docs | documentation-engineer | `step-04-documentation-engineer-report.md` | docs/hadoop-jobs.md and modules/hadoop/MODULE.md with only observed numbers | done, see note T4 |
| Review 1 | code-reviewer (fresh) | `review-report.md` | verdict, plus the fairness verdict on the 5 red adversarial tests | done, REQUEST_CHANGES, see note Review 1 |

Repair round 1 (human budget of 2026-11-23: one round only; reports saved as
`repair-01-<owner>.md`):

| Task | Owner | Scope | Report | State |
|---|---|---|---|---|
| R1a | hadoop-engineer | B1-B10 of the review (B1-B7 required) | `repair-01-hadoop-engineer.md` | done, see note R1a |
| R1b | documentation-engineer | D1: MODULE.md, docs/hadoop-jobs.md, post-repair numbers | `repair-01-documentation-engineer.md` | done, see note R1b |
| R1c | test-engineer | rerun of adversarialTests (no test change needed; all verdicts FAIR) | `repair-01-test-engineer.md` | done, see note R1c |
| Review 2 | code-reviewer (fresh) | whole Phase 04 diff | `review-report-02.md` | done, APPROVE WITH CARRIED-FORWARD ITEMS, see note Review 2 |

Note R1a (report `docs/agent-runs/phase-04/repair-01-hadoop-engineer.md`):
hadoop-engineer COMPLETE, B1 to B10 all done.

- Final `sbt check` exited 0: core 94 of 94, hadoop 98 of 98 (79 before), adversarialTests
  68 of 68 (the 5 red tests are green).
- Post-repair local runs: fixtures `run-8b3a162615a54f8f` and real samples
  `run-6d1175dbfdb874a9`; headline numbers are unchanged from the pre-repair runs, and the
  run IDs differ because `LogicVersion` is now `hadoop-v2`.
- Process observation: one `sbt hadoop/test` was moved to the background by the harness
  at 120 s.
  hadoop-engineer reports it read the output file once and started no second sbt while it
  ran.
- Risks it reports: the J03 reducer still buffers a key's values, the `y:` fallback key is
  weak, `sbt packageJobs` is unobserved, and the streaming J07 reducer has no separate unit
  test.

Note R1b (documentation-engineer): `modules/hadoop/MODULE.md` and `docs/hadoop-jobs.md`
now carry the F11 corrections, the repair behavior, the post-repair numbers and the
per-job spec items; the 4 open T3 items are removed.
`PROJECT_MEMORY.md` records the encoding decision and the one-round budget.
Line lengths over 90 remain only in markdown tables.

Note R1c (report `docs/agent-runs/phase-04/repair-01-test-engineer.md`): test-engineer
COMPLETE.

- One `sbt check` exited 0 (117 s): core 94 of 94, hadoop 98 of 98, adversarialTests
  68 of 68.
- The 5 formerly red tests are green; no test file changed (SHA-256 prefixes identical
  before and after).
- Two new observations, passed to Review 2 (no test covers either):
  1. J01 reject reasons print raw Scala `Option` text, for example
     `unreadable record (BadEncodingSome(, UTF-8)) at byte 3649` and
     `unreadable record (OversizeNone) at byte 264`.
     hadoop-engineer reported the format as
     `unreadable record (BadEncoding, <charset>) at byte N`; that was reported but does
     not match the observed log.
  2. Fallback-block candidates carry provenance `policyVersion` "norm-v1", while the
     manifest `pipeline` is "hadoop-v2".
- Review 2 is a fresh code-reviewer (`review-report-02.md`).
  Any finding still open after it goes to the human as carried forward; there is no
  second repair round.

Findings still open after Review 2 go to the human as carried-forward items.
There is no second repair round.

STATUS.md is updated by documentation-engineer after each owner.

Note Review 2 (report `docs/agent-runs/phase-04/review-report-02.md`): code-reviewer on
opus, high, fresh context.

- Verdict APPROVE WITH CARRIED-FORWARD ITEMS: APPROVE for the Phase 04 gate, with 7 MINOR
  findings (N1 to N7) carried forward.
  No CRITICAL or MAJOR finding remains.
- Its one `sbt check` exited 0 (116 s): core 94 of 94, hadoop 98 of 98, adversarialTests
  68 of 68.
- All Review 1 findings F1 to F11 are RESOLVED.
- No ownership breach; `tests/adversarial/**` is byte-identical to T3.
- Doc numbers match the saved reports; no invented number found.
- Per the human's instruction of 2026-11-23, no second repair round was run.
  N1 to N7 go to the human (next section).
- Review 2 did not run `sbt packageJobs`, LocalRunner, `--compress false` or any EMR
  classpath.

## Carried forward from Phase 04 (pending the human's decision)

All MINOR, as stated in `docs/agent-runs/phase-04/review-report-02.md`.
Paths below are under `modules/hadoop/src/main/scala/scholarchain/hadoop/` unless noted.

- N1: J01 reject reasons print Scala `Option` text, a regression from the repair.
  Reads `unreadable record (BadEncodingSome(, UTF-8)) at byte 3649`, not
  `(BadEncoding, <charset>)`.
  Owner hadoop-engineer; `Jobs.scala:125`, `:129`, `DblpInputFormat.scala:202`.
  Brief: use `cs.getOrElse("")`, strip the `\n[charset=...]` suffix from the reject
  `input`, and add a test pinning the reason for each of the 3 statuses.
- N2: a decode-only declared charset (for example ISO-2022-CN) crashes the reader
  instead of giving one counted refusal.
  Owner hadoop-engineer; `DblpInputFormat.scala:100` and `:103`.
  Brief: test `cs.canEncode` before `newEncoder()`, or catch
  `UnsupportedOperationException`, and add the case to RepairDblpSuite.
- N3: fallback comparison cost and volume are bounded only by the hard cap
  (about C(2000, 2) per block at the default).
  Owner hadoop-engineer; `BlockLogic.scala:201-217` and `:332-347`.
  Brief: check `pairId` before computing features, optionally cap fallback pairs per
  block with a counter, and document the bound.
- N4: candidate provenance names only `norm-v1`, while the manifest `pipeline` is
  `hadoop-v2`.
  Owner hadoop-engineer, then domain-engineer when MatchCandidate moves to core;
  `Records.scala:19` and `:113`.
  Decide before Phase 05 consumes candidates: put a policy version that includes
  `Pipeline.LogicVersion` in provenance, or accept runId to manifest as the carrier and
  record that in PROJECT_MEMORY.md.
- N5: the J03 reducer still buffers a whole exact-key group (residue of F4 and F9).
  Owner hadoop-engineer; `Jobs.scala:257`.
  Brief: stream the values, or refuse a key past a hard cap as `BlockingReducer` does
  (`Jobs.scala:313-324`).
- N6: stale "pending" text and small imprecisions in the docs.
  Owner documentation-engineer; `MODULE.md:348`, `docs/hadoop-jobs.md:22-23`, `:274-280`
  and `:291-296`, and `MODULE.md:57`.
  Not fixed in this task, per the one-round rule.
- N7: job-level test gaps for the new reducer wiring: J04 `FALLBACK_BLOCKS_REFUSED`
  with a reject line, J03 `OVERSIZE_BLOCKS` with a reject line, and the J01 reject
  reason text.
  Owners hadoop-engineer (module-unit) and test-engineer, which also plans the
  black-box ISO-8859-1 case (`repair-01-test-engineer.md`, RISKS 3).
  Brief: one LocalJobRunner case each with a small `--max-block`.

The reviewer notes N1 and N2 are small, contained fixes if the human waives the
one-round budget.

Note Review 1 (report `docs/agent-runs/phase-04/review-report.md`): code-reviewer on
opus, high.

- Verdict REQUEST_CHANGES.
- Its `sbt check` exited 1: core 94 of 94, hadoop 79 of 79, adversarialTests 63 of 68
  (the same 5 red tests as T3).
- All 5 red adversarial tests are judged FAIR.
- Findings: F1 to F5 MAJOR and F6 to F11 MINOR.
  The report text counts its MAJOR findings as 4 plus the encoding item; the list has F1 to
  F5.
- No ownership breach.
- Repair briefs are B1 to B10 for hadoop-engineer (B1 to B7 required for APPROVE) and D1
  for documentation-engineer.
- Review 1 recorded its own risks: F2, F3 and F4 are confirmed by reading the code only,
  and `sbt packageJobs` and the cluster run are unobserved.

Decision (human, 2026-11-23, given in the main session), verbatim: "the reader must
decode with the encoding declared in the XML prolog (ISO-8859-1 for DBLP, UTF-8 when none
is declared) and reject bytes that are invalid in the declared encoding. Route it to
hadoop-engineer in the repair round, with a test using a raw ISO-8859-1 high byte."
This is finding F5 and brief B5.

Note T1 (report `docs/agent-runs/phase-04/step-01-build-engineer-report.md`):
build-engineer COMPLETE, `build.sbt` changed in the hadoop project only.

- `sbt check` is green as reported (94 passed at the tail, 14 project test runs passed).
- hadoop `Test / fork` is true and `Test / javaOptions` is `-Xmx1g`.
- `hadoop-client-runtime` 3.4.1 and `jcl-over-slf4j` 2.0.20 are at Test scope;
  `hadoop-client-api` stays Provided.
- No change to `project/Versions.scala`; the existing `V.hadoop` and `V.slf4j` were used.
- Risks noted: `entityResolution` has no Hadoop deps or fork yet, and the fork is
  exercised only once T2 adds tests, because the hadoop project has no sources or tests.
  Each forked test JVM can use up to 1 GB heap on the 8 GB machine.

Note T2 (report `docs/agent-runs/phase-04/agent-report.md`): hadoop-engineer COMPLETE.

- `sbt check` is green as reported: hadoop 79 of 79 tests, core 94 of 94.
- Jobs J01 to J07 are implemented.
- Local bounded pipeline runs: fixtures (`run-8019aba484da34ad`) and real samples
  (`run-2ed430a7951bb05a`).
- Fixtures headline numbers: maximum team size 12, largest pair expansion 66, largest
  blocking group 13.
- 0 rejects on the samples.
- Risks as reported:
  - `sbt packageJobs` was not run, so the job-jar build with the new sources is
    unobserved.
  - LocalRunner needs the Test-scope `hadoop-client-runtime`, so it runs through
    `hadoop/Test/runMain`.
  - The hadoop-local records (MatchCandidate, ExternalResearcher, AuthorPaper,
    AbstractRecord) are not core contracts yet; domain-engineer promotes them in a
    later phase.
  - Cross-source teams and pairs double count the same paper until entity resolution.

Post-restart verification (orchestrator, as observed and given to documentation-engineer):

- Phase 04 was interrupted by a WSL out-of-memory crash and a restart.
- After the restart the orchestrator verified that build-engineer's `build.sbt` edit and
  hadoop-engineer's 15 source files, 5 suites (22+10+20+17+10 = 79 tests) and fixtures
  are on disk and match their reports.
- One `sbt check` run after the restart (2026-11-22 23:30) exited 1.
  Core passed 94 of 94 and hadoop passed 79 of 79.
  `adversarialTests` ran 54 tests and 3 failed (CommonNameSuite 2, MissingFieldSuite 1).
- The failing files are partial, unreported files that test-engineer left in
  `tests/adversarial/` when the crash interrupted it.
  test-engineer is re-run from the beginning in T3.

Note T2b (report `docs/agent-runs/phase-04/step-03a-documentation-engineer-report.md`):
`modules/hadoop/MODULE.md` has the Public API and Done / Not done sections, taken from
the T2 report and checked against names in `src/main`.
Run numbers and headline counts are left for T4.

Note T3 (report `docs/agent-runs/phase-04/test-report.md`): test-engineer COMPLETE.

- Final `sbt check` exited 1.
  core 94 of 94, hadoop 79 of 79, adversarialTests 68 total, 63 passed, 5 failed.
- The 5 failures are left red for the fairness verdict (Review 1):
  two missing fallback keys for oversize name and title blocks, `INVALID_IDENTIFIER`
  stays 0, J03 `DUPLICATE_RECORDS` and `CONFLICT_RECORDS` stay 0, and a trailing cap flag
  without a value takes the default.
- test-engineer also listed imprecisions in `modules/hadoop/MODULE.md`; they are under
  its Known issues.
- Open human question: how DBLP's ISO-8859-1 declaration interacts with strict UTF-8
  (untested).

Note T4 (report `docs/agent-runs/phase-04/step-04-documentation-engineer-report.md`):
`docs/hadoop-jobs.md` was written and `modules/hadoop/MODULE.md` finished, with only the
numbers in the T2 and T3 reports.
The numbers may change after the repair round.

Machine limits (human addendum, 8 GB laptop):

- Never more than one sbt at once.
- No background shells, and no polling or wait loops.
- No mutation testing.
- Run every command in the foreground with a timeout.

## Phase 05 task list

Phase 05 is entity resolution (entity-resolution-engineer), work order and human addendum
in `docs/agent-runs/phase-05/prompt.md`.
Reports are saved in `docs/agent-runs/phase-05/`.
The reference material in `~/ref-phase-05/` is a starting point only; each owner adapts
it and owns the result.

| Step | Owner | Scope | Report | State |
|---|---|---|---|---|
| 01 | build-engineer | entityResolution gets hadoop-client-api (Provided) and the Hadoop test runtime, forked test JVM -Xmx1g, versions only from project/Versions.scala | `step-01-build-engineer-report.md` | done, see note step 01 |
| 02 (T1) | entity-resolution-engineer | review queue generated from the Phase 04 local pipeline on datasets/samples into `target/er-review/er-review-v1.csv` (git-ignored), at least 250 rows, stratified across researcher and work pairs and across likely matches, borderline and likely non-matches, no scores, paths or predictions; BLOCKED-ON dataset-engineer if fewer than 250 pairs | `step-02-entity-resolution-engineer-report.md` | done about 09:21, see note step 02 |
| Human labeling | human | writes `datasets/gold/er-gold-v1.csv` from the review queue (starts 09:00); v1b labeling was planned and then cancelled | - | done: v1 only, 280 rows (228 MATCH, 13 NON_MATCH, 39 UNSURE); v1b not labeled and unused (decision 03) |
| 03 (T2) | entity-resolution-engineer | the rest of the work order except the gold-set evaluation; er-v1 fixed; LLM adjudication behind a port with a deterministic fake and an off mode | `agent-report.md` | done, see note step 03 |
| 03b (T1b) | entity-resolution-engineer | supplementary queue `target/er-review/er-review-v1b.csv`: up to 60 additional likely non-match pairs from the same samples (same block but different clusters; different DBLP homonym numbers; different ORCIDs; compatible initials with no shared context); same columns and rules as v1 (no scores, paths or predicted labels, traceable to source records); no overlap with v1 | `step-03b-entity-resolution-engineer-report.md` | done, see note step 03b |
| 04 (T3) | entity-resolution-engineer | gold-set evaluation on v1 only (`datasets/gold/er-gold-v1.csv`, 280 rows) against decision run `run-02cecabcf3e68312` in `target/er-match-run`; MATCH and NON_MATCH counted, UNSURE reported separately; the report must record the few-non-matches limitation (13 NON_MATCH labels limit how well precision and the reject side are measured) and the class imbalance; the v1b queue, its candidate run and `target/er-match-run-v1b` are not used (human decision 03) | `output-er-evaluation.md`, `step-03-entity-resolution-engineer-report.md` | done, see note step 04 |
| 05 | documentation-engineer | `docs/entity-resolution.md` and `modules/entity-resolution/MODULE.md`, observed numbers only | `step-04-documentation-engineer-report.md` | done, see note step 05 |
| Review 1 | code-reviewer (fresh) | whole Phase 05 diff | `review-report.md` | done, APPROVE WITH CARRIED-FORWARD ITEMS (6 MINOR, 4 NIT) |
| R1-1 | entity-resolution-engineer | MINOR-2, NIT-1, NIT-2, PolicyConfig comment | `repair-01-entity-resolution-engineer.md` | done, see note R1-1 |
| R1-2 | documentation-engineer | MINOR-3, MINOR-4 (docs part), MINOR-5, MINOR-6 | `repair-01-documentation-engineer.md` | done, see note R1-2 |
| R1-3 | documentation-engineer | RISKS 6 wording from R1-1 into MODULE.md and the docs page | `repair-01b-documentation-engineer.md` | done, see note R1-3 |
| Review 2 | code-reviewer (fresh) | re-check; open findings carried forward to the human, no second repair round | `review-report-02.md` | done, APPROVE WITH CARRIED-FORWARD ITEMS, see note Review 2 |
| Human gate | human | ratify and commit | `human-gate.md` | pending |

Phase rules:

- No leakage: the scoring policy er-v1 and its accept, reject and unresolved thresholds
  were fixed in the module before the gold file existed.
  Matching code never reads `datasets/gold/`.
  Threshold sensitivity is reported, not used to retune er-v1.
- Every label is written by the human; agents never write, infer or pre-fill one.
- Machine limits (8 GB laptop): never more than one sbt at once, no background shells,
  no polling or wait loops, no mutation testing, every command in the foreground with a
  timeout.
- Owners run one at a time.
  STATUS.md is updated by documentation-engineer after each owner.

Note step 01 (build-engineer, as reported by the orchestrator):

- `sbt check` is green: Total 68 passed, 14 project test runs.
- The `build.sbt` change is 12 added lines (`git diff --stat -- build.sbt`).
- The sbt run exceeded the 180 s foreground window and the harness backgrounded it.
  Machine limits forbid background shells.
  The run completed and no sbt was left running.
- Later sbt work orders set an explicit 600 s timeout.
- The saved report `docs/agent-runs/phase-05/step-01-build-engineer-report.md` now exists
  and matches these numbers (Total 68 passed, 14 project test runs, 175 s).

Note step 02 (T1, report
`docs/agent-runs/phase-05/step-02-entity-resolution-engineer-report.md`): started 09:07,
past the human's 09:00 labeling start; done about 09:21.

- Output: `target/er-review/er-review-v1.csv` (git-ignored), 280 rows (205 researcher,
  75 work), sha256 f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e,
  source run `run-6d1175dbfdb874a9`; `sbt check` green.
- The orchestrator check at the end of the report confirms the header, the row counts,
  the distinct pair IDs, empty label and note columns, the checksum and the ignore rule.
- Queue delivered about 09:22; `datasets/gold` did not exist yet.
- CLOSED by human decision 01 (report RISKS 1, class imbalance): the human labels
  `er-review-v1.csv` as is
  (`docs/agent-runs/phase-05/human-decision-01.md`).
  The samples give only 8 likely non-matches (4 researcher, 4 work) and no borderline
  work pairs.
  The supplement is task 03b (`er-review-v1b.csv`); the imbalance and the supplement go
  in the evaluation report.
- The generator refuses to overwrite an existing file unless `--overwrite true` is given,
  which protects a copy the human may already be labeling.
- Process notes: the harness backgrounds sbt after 180 s even with a 600000 ms timeout
  (seen in steps 01 and 02); agents start no second sbt and do not poll.
  guard-dangerous gives false positives on the literal test-marker task name in greps and
  report text (Phase 11 item).

Note step 03 (T2, report `docs/agent-runs/phase-05/agent-report.md`):
entity-resolution-engineer COMPLETE.

- `sbt check` is green: entity-resolution 92 tests, core 94, hadoop 98, adversarial 68;
  14 project test runs; 570 s.
- Policy er-v1 thresholds: accept 0.9, reject 0.35, LLM band [0.7, 0.9).
  er-v1 was fixed before the gold file existed (it appeared about 10:28 to 10:33) and was
  not changed afterwards; no agent could read a label while authoring it; nothing is
  git-committed yet (Review 1 MINOR-4).
  The engineer did not read or write `datasets/gold/**`, and the queue
  `er-review-v1.csv` was not regenerated (sha256 still starts f75204756578ffc9).
- LLM adjudication sits behind a port with an off mode and a deterministic fake.
  The sample run used off mode.
- Sample run `run-02cecabcf3e68312` over upstream run `run-6d1175dbfdb874a9`, in
  git-ignored `target/er-match-run/`: 347 decisions, 338 ACCEPTED, 0 REJECTED,
  5 UNRESOLVED, 4 CONFLICT; unresolved rate 1.44%.
- The orchestrator check at the end of the report found no sbt or java process running
  and `datasets/gold` absent.
- Open items from its RISKS:
  - N4 needs a human decision: the engineer took the run ID plus the run manifest
    (`upstreamRunId`, `upstreamPipeline` "hadoop-v2") as the link to upstream, as
    implemented; the human has not accepted or rejected it.
  - No citation evidence is ever available, because work-v1 has no references
    (contract gap, domain-engineer).
  - The run output folders `rejected/`, `adjudications/` and `rejects-v1/` are absent
    because they hold zero rows; T3 and the reviewer must confirm that readers handle an
    absent empty output.
  - The sample has no REJECTED decisions; its 4 work CONFLICTs are DOI disagreements.
  - ER-J1 sends a whole `orcid:` profile to every linked candidate; measure the data
    growth before a full-DBLP run.
  - The adjudication cache is read whole into each reducer; a DynamoDB cache comes later.
  - Not done: the T3 evaluation, any Bedrock adjudicator, `sbt packageJobs`, an EMR run.

Note step 04 (T3, reports
`docs/agent-runs/phase-05/step-03-entity-resolution-engineer-report.md` and
`docs/agent-runs/phase-05/output-er-evaluation.md`): entity-resolution-engineer
COMPLETE, evaluation on v1 only against `run-02cecabcf3e68312`.

- `sbt check` is green: entityResolution 106 tests, 14 project test runs.
- Headline, from `output-er-evaluation.md`:
  - researcher: precision 0.938 [0.889, 0.966], recall 0.987, F1 0.962;
  - work: precision 1.000 [0.949, 1.000], recall 0.947, F1 0.973; no work pair is
    labeled NON_MATCH, so only the lower bound carries information;
  - unresolved rate 0.0144 over the whole run (5 of 347).
- er-v1 made 0 REJECTED decisions, so the reject side is unmeasured by this gold set.
- The v1b queue and its run were not used (decision 03).
- Not written: the evaluation copy under `target/` (see open item 3).

Note step 03b (T1b, report
`docs/agent-runs/phase-05/step-03b-entity-resolution-engineer-report.md`):
entity-resolution-engineer COMPLETE.

- Output: `target/er-review/er-review-v1b.csv` (git-ignored), 60 researcher rows, 0
  overlap with v1, sha256
  08c02f228229f907ce7de1b967dd22ce5de5af6263c06411fb23c7a68c86c845.
  The header is identical to v1; label and note are empty (orchestrator check).
- Selection by criterion: different ORCID 0, DBLP homonym 0, compatible initials with no
  shared context 10, same block different clusters 50.
- er-v1 decisions for the v1b pairs were produced in a separate run
  (`target/er-match-run-v1b`), for T3 only.
- `sbt check` green: entity-resolution 103 tests, 14 project test runs.
- New files: `review-supplement-v1b.json`, `ReviewSupplement.scala`,
  `ReviewSupplementMain.scala` and `ReviewSupplementSuite.scala`.
  `ReviewQueueMain.scala` changed visibility only.
- Blindness: that report holds er-v1's outcomes for the v1b pairs.
  Labeling v1b will not happen (decision 03), so the warning about showing it to a
  labeler no longer applies, but the report still holds those outcomes.
  This note records structure only.
- v1b is superseded: the queue stays unlabeled and unused.

Note human message 02 (`docs/agent-runs/phase-05/human-decision-02.md`):
`datasets/gold/er-gold-v1.csv` has 280 labeled rows (228 MATCH, 13 NON_MATCH, 39
UNSURE), as stated by the human; agents have not opened the file.
This message planned v1b labeling and a T3 run after it; decision 03 supersedes that.

Note human decision 03 (`docs/agent-runs/phase-05/human-decision-03.md`): the human
will not label v1b and asks for T3 now on `datasets/gold/er-gold-v1.csv` only (280
labeled rows).
It supersedes decision 01's v1b plan.
T3 evaluates the v1 decision run `run-02cecabcf3e68312` (`target/er-match-run`).
`target/er-review/er-review-v1b.csv`, its candidate run and `target/er-match-run-v1b`
are not used.
The evaluation report must record that the sample has few non-matches (13 NON_MATCH
labels), which limits how well precision and the reject side are measured.

Note human decision 04 (`docs/agent-runs/phase-05/human-decision-04.md`): answers open
items 1 and 2 of T3.
The 13 researcher NON_MATCH labels were judged by "different chapter", not "different
person", which was the wrong question for researcher pairs; this includes the
equal-ORCID pair `mc:researcher:10c07413d756444b3991`.
The human is not relabeling and not re-running T3; `datasets/gold/er-gold-v1.csv` and
the evaluation stay as they are.
Consequences, as the human directs: the 13 labels are known labeling errors, the 10
researcher false positives are likely not real errors of er-v1, researcher precision
(0.938) is understated, and the reject side is effectively unmeasured.
`output-er-evaluation.md` section 11 records this.

Note step 05 (documentation-engineer, report
`docs/agent-runs/phase-05/step-04-documentation-engineer-report.md`): written
`docs/entity-resolution.md` and `modules/entity-resolution/MODULE.md`, with only numbers
from the saved Phase 05 reports and the decision 04 statement above.
No sbt was run; the documented sbt commands were not replayed.

Note Review 1 (report `docs/agent-runs/phase-05/review-report.md`): verdict APPROVE WITH
CARRIED-FORWARD ITEMS, 6 MINOR and 4 NIT, no CRITICAL or MAJOR.
Its one `sbt check` exited 0 (core 94, hadoop 98, entityResolution 106, adversarial 68;
14 project test runs).

Note R1-2 (documentation-engineer): docs edits only, no sbt run.

- MINOR-3: `docs/entity-resolution.md` now says R-R2 is CONFLICT, or REJECTED when the
  score is at or below 0.35; the "an identifier agrees" condition stays with R-R3.
  Checked by reading `MatchPolicy.scala` lines 191-210.
- MINOR-4 (docs part): the page and Note step 03 now say er-v1 was fixed before the gold
  file existed and was not changed afterwards.
  The `PolicyConfig.scala` comment is entity-resolution-engineer's (R1-1).
  `output-er-evaluation.md:27` keeps the old wording; it is saved evidence.
- MINOR-5: the "recall without caveat" sentence is removed from the page.
- MINOR-6: MODULE.md says `LocalMatchRunner` is in `src/main`, with only the Hadoop
  runtime Test-scoped.

Note R1-1 (report `docs/agent-runs/phase-05/repair-01-entity-resolution-engineer.md`):
entity-resolution-engineer COMPLETE.

- `sbt fmt check` exited 0: core 94, entityResolution 112 (106 before), hadoop 98,
  adversarial 68; 14 project test runs.
- MINOR-2: every adjudication cache hit is re-validated (`Adjudicator.recheck`); a
  refused hit gives `LLM_INVALID_OUTPUT` with no call.
- NIT-1: `EvaluateMain.samePolicy` refuses a run made under another policy version.
- NIT-2: `LocalMatchRunner` scratch is `<output>-tmp`, removed after the run (ER only).
- PolicyConfig comment fixed.
  Policy resource sha256 starts 82ce9ae5 (unchanged); no evaluation was rerun.
- MINOR-1 is not fixed (carried to er-v2).

Note R1-3 (documentation-engineer): the R1-1 RISKS 6 wording is applied to
`modules/entity-resolution/MODULE.md` and `docs/entity-resolution.md`.
Each claim was checked by reading `Adjudication.scala`, `MatchJob.scala`,
`eval/EvaluateMain.scala` and the hadoop `LocalRunner.scala`; no sbt was run.

Carried forward from Review 1:

- MINOR-1: a future policy version er-v2 (fixing it changes er-v1 behavior).
- NIT-3: no action.
- NIT-4: the Phase 07 loader must treat an absent outcome folder as empty only when
  `_SUCCESS` exists (storage-engineer).
- Human: the `target/**` matrix gap; `chmod 644` on the gold file before the commit;
  the MINOR-1 decision; N4.
- Process: the harness backgrounds sbt at 180 s; guard-dangerous false positives on the
  test-marker task name.

Note Review 2 (report `docs/agent-runs/phase-05/review-report-02.md`): code-reviewer on
opus, high, fresh context.

- Verdict APPROVE WITH CARRIED-FORWARD ITEMS; no CRITICAL or MAJOR finding.
- Its one `sbt check` exited 0: core 94, entityResolution 112, hadoop 98, adversarial
  68; 14 project test runs; 11:36 (its output shows a total time of 148 s).
- All Review 1 repair items are fixed: MINOR-2, MINOR-3, MINOR-4, MINOR-5, MINOR-6,
  NIT-1 and NIT-2 (NIT-2 for new runs; the old `_local-tmp` is left for the human).
- Carried from Review 1: MINOR-1, NIT-3 and NIT-4.
- New findings: MINOR-A (the stale snapshot, replaced below), MINOR-B, MINOR-C,
  NIT-A, NIT-B and NIT-C.
  Per the one-round rule there is no second repair round.
- The er-v1 policy resource hash is unchanged (starts 82ce9ae5) and no leakage was
  found.

## Current state (phase 06)

- Tasks done: step 00 (documentation-engineer, the task list), step 01 (build-engineer),
  step 01s (documentation-engineer), step 02 (spark-engineer, T1), step 02s
  (documentation-engineer), step 03 (spark-engineer, T2), step 03s
  (documentation-engineer), step 04 (documentation-engineer, `docs/spark-jobs.md` and
  `modules/spark/MODULE.md`), Review 1 (code-reviewer, fresh: REQUEST_CHANGES,
  1 major, 5 minor, 4 nits; `docs/agent-runs/phase-06/review-report.md`), and
  repair R1-1 (spark-engineer, all seven findings fixed; see note R1-1), R1-2
  (build-engineer, jar rebuilt; see note R1-2) and R1-3 (documentation-engineer),
  and Review 2 (code-reviewer, fresh: APPROVE WITH CARRIED-FORWARD ITEMS, 3 nits;
  `docs/agent-runs/phase-06/review-report-02.md`).
- Task running: none.
- Next task: the Phase 06 human gate (ratify, commit, decide the carried items).
- Half done: nothing.
- Git: HEAD is acb5f21 "phase-05: record accepted commit hash", with 4dfb3bd below it.
  `git status --short` (run for this update) shows nothing committed for Phase 06:

  ```text
   M STATUS.md
   M build.sbt
   M modules/spark/MODULE.md
  ?? docs/agent-runs/phase-06/
  ?? docs/spark-jobs.md
  ?? modules/spark/src/main/scala/scholarchain/
  ?? tests/module-unit/spark/CanonicalSuite.scala
  ?? tests/module-unit/spark/CollaborationSuite.scala
  ?? tests/module-unit/spark/GraphAlgorithmsSuite.scala
  ?? tests/module-unit/spark/LocalPipelineRun.scala
  ?? tests/module-unit/spark/LocalSpark.scala
  ?? tests/module-unit/spark/PipelineSuite.scala
  ?? tests/module-unit/spark/RunManifestSuite.scala
  ?? tests/module-unit/spark/TemporalSuite.scala
  ?? tests/module-unit/spark/WindowAnalyticsSuite.scala
  ?? tests/module-unit/spark/resources/
  ```
- Harness: claude-code.
- Resume in a fresh session with:
  `cd ~/scholarchain && claude "Read STATUS.md and continue with its next step."`

## Phase 06 task list

Phase 06 is the Spark collaboration-analysis pipeline (spark-engineer), work order and
human addendum in `docs/agent-runs/phase-06/prompt.md`.
Reports are saved in `docs/agent-runs/phase-06/`.
The reference material in `~/ref-phase-06/` is a starting point only; each owner adapts
it and owns the result.

| Step | Owner | Scope | Report | Done means / check | State |
|---|---|---|---|---|---|
| 00 | documentation-engineer | this task list | `step-00-documentation-engineer-report.md` | table present | done |
| 01 | build-engineer | fork sparkAnalytics tests with the JDK module opens, -Xmx1g, versions only from project/Versions.scala; keep scala-library excluded from the Spark job jar; confirm the Spark job jar builds | `step-01-build-engineer-report.md` | `sbt sparkAnalytics/test` runs forked; Spark job jar builds | done, see note step 01 |
| 01s | documentation-engineer | STATUS after step 01 | - | STATUS updated | done |
| 02 (T1) | spark-engineer | jobs 1 to 9 (canonical tables, temporal weighted coauthor graph, breadth and team-size stats, components, paths, communities, bridges, k-core, temporal windows) with hand-checkable known-graph suites | `step-02-spark-engineer-report.md` | `sbt sparkAnalytics/test` green | done, see note step 02 |
| 02s | documentation-engineer | STATUS after step 02 | - | STATUS updated | done |
| 03 (T2) | spark-engineer | jobs 10 and 11 (semantic summaries, Parquet export, load manifests), run manifest, local integration run on the Phase 04 and 05 sample outputs (local[2], 4 shuffle partitions, UI off) captured in `output-spark-local-run.md`, then one full `sbt check`; bounded EMR run reported NOT RUN (cloud work starts in Phase 12) | `agent-report.md` | local run captured; full `sbt check` green | done, see note step 03 |
| 03s | documentation-engineer | STATUS after step 03 | - | STATUS updated | done |
| 04 | documentation-engineer | `docs/spark-jobs.md` and `modules/spark/MODULE.md` with only observed numbers; STATUS | `step-03-documentation-engineer-report.md` (name set by the prompt) | every number traces to a saved report | done, see note step 04 |
| Review 1 | code-reviewer (fresh) | whole Phase 06 diff | `review-report.md` | verdict | done, REQUEST_CHANGES (1 major, 5 minor, 4 nits) |
| R1 | owners named by the review | repair round 1, one round only | `repair-01*.md` | findings fixed | done (R1-1, R1-2, R1-3) |
| Review 2 | code-reviewer (fresh) | re-check, only if review 1 has a CRITICAL or MAJOR finding | `review-report-02.md` | verdict | done, APPROVE WITH CARRIED-FORWARD ITEMS (3 nits) |
| Gate | human | ratify and commit | the gate file | - | next |

Phase 06 repair round 1 (one round only; reports saved as `repair-01-<owner>.md`):

| Task | Owner | Scope | Report | State |
|---|---|---|---|---|
| R1-1 | spark-engineer | MAJOR-1 under policy (a), MINOR-1 to MINOR-4, NIT-3, NIT-4; sparkAnalytics tests, local run rerun, one sbt check | `repair-01-spark-engineer.md` | done, see note R1-1 |
| R1-2 | build-engineer | MINOR-5, job jar built on the final tree, scala-library absence re-checked | `repair-01-build-engineer.md` | done, see note R1-2 |
| R1-3 | documentation-engineer | NIT-1, NIT-2, MAJOR-1 and MINOR-3 doc text, numbers from the rerun, STATUS | `repair-01-documentation-engineer.md` | done, see note R1-3 |
| Review 2 | code-reviewer (fresh) | re-check of the whole Phase 06 diff, required because review 1 had a MAJOR finding | `review-report-02.md` | done, APPROVE WITH CARRIED-FORWARD ITEMS (3 nits) |

Note R1-1 (report `docs/agent-runs/phase-06/repair-01-spark-engineer.md`):
all seven findings are fixed.

- MAJOR-1 uses policy (a): a deterministic contradiction cut refuses the weakest
  accepted link on a path that joins a contradicting pair.
- MINOR-1 to MINOR-4, NIT-3 and NIT-4 are fixed as the report describes.
- The LogicVersion is now spark-v2.
- `sbt sparkAnalytics/test` passed 33 of 33.
- The local rerun `run-769e0d672ecd3c19` SUCCEEDED, with 94 invariants, all 0.
  It refused 3 accepted researcher links and gives 81 researchers (was 79).
  Numbers are in `docs/agent-runs/phase-06/output-spark-local-run-r1.md`.
- The full `sbt check` exited 0 (94, 68, 112, 33 and 98 passed).
- Risk: no config bound on the size of a contradicted cluster before a full-DBLP
  run (domain-engineer field).

Note R1-2 (build-engineer, report `docs/agent-runs/phase-06/repair-01-build-engineer.md`):
COMPLETE; MINOR-5 is closed.

- The jar was rebuilt on the final tree at 15:12:16 with `sbt sparkAnalytics/assembly`:
  4,071,345 bytes, `Main-Class: scholarchain.spark.SparkPipeline`.
- Entry counts: `scala/collection/` 0, `ScalaRunTime` 0, `upickle/` 377.
- The orchestrator counted 6 `scholarchain/spark/SparkPipeline*` entries in the jar.
- No build file changed and `sbt check` was not run, as ordered.

Note R1-3 (documentation-engineer, report
`docs/agent-runs/phase-06/repair-01-documentation-engineer.md`): `docs/spark-jobs.md`,
`modules/spark/MODULE.md` and this file updated; no sbt was run.

- MAJOR-1 and MINOR-1 to MINOR-4 text is written from the R1-1 report, with the rerun
  numbers (`run-769e0d672ecd3c19`) from `output-spark-local-run-r1.md`.
- NIT-2: the driver does set `spark.cleaner.referenceTracking.cleanCheckpoints`
  (`SparkPipeline.scala:58`, checked by grep; the review cited line 50).
- NIT-1: the Git line below comes from a `git status --short` run for this update.

Note review 1 (`docs/agent-runs/phase-06/review-report.md`):

MAJOR-1 = UNRESOLVED and CONFLICT ER pairs merged transitively through accepted links.
On the sample, 3 of the 5 unresolved researcher pairs end up in one canonical researcher.
The orchestrator routes policy (a): split a cluster on a contradicting decision.
The human addendum says "Unresolved and conflict decisions are preserved, never merged",
so policy (b), keep and disclose, would contradict it.
The human can overrule this at the gate.
One repair round only: findings still open after Review 2 carry forward to the human.

Note step 01 (build-engineer, report
`docs/agent-runs/phase-06/step-01-build-engineer-report.md`):

- `build.sbt` sparkAnalytics tests now fork, with the reference JDK module opens and
  -Xmx1g. No version values changed.
- scala-library is still excluded from the Spark job jar. Jar check: 0 entries under
  `scala/collection/`, 0 `ScalaRunTime` entries, scala3-library and uPickle bundled
  (377 `upickle/` entries).
- `sbt sparkAnalytics/assembly` reported the jar up to date. The change is Test-only,
  so the jar was not rebuilt from scratch.
- `sbt sparkAnalytics/test` succeeded with no tests yet, so the fork options are
  unexercised until step 02.

Note step 02 (spark-engineer, T1, report
`docs/agent-runs/phase-06/step-02-spark-engineer-report.md`): done, COMPLETE.

- Code: jobs 1 to 9 in `modules/spark` (10 source files under
  `src/main/scala/scholarchain/spark/`), with 5 suites and `LocalSpark` in
  `tests/module-unit/spark/`.
- `sbt sparkAnalytics/test` passed 20 of 20 on the final files (run 4, after
  scalafmt).
  Earlier runs failed 7 and then 5 tests; all were fixed (an algorithm flaw, an
  ambiguous column reference, the 30 s munit timeout and a test assumption on ID
  order).
- The step 01 fork options (module opens, -Xmx1g) worked on first use.
- Algorithm change against the reference: label propagation with a fixed self vote
  oscillated forever on weighted edges.
  The self vote now equals the node's strongest edge weight.
  Synchronous label propagation can still oscillate on other structures; it is
  bounded by `communityIterations`.
- Not run: the full `sbt check`, as the work order defers it to step 03.
  The stop hook fired for it, and `.claude/.last-test-run` is stale.
- Open for T2 (step 03):
  - the driver, run-manifest inputs, jobs 10 and 11, and the local integration run;
  - a contract gap: components, communities, path evidence and temporal comparisons
    have no core record.
    T2 maps them onto existing core records or reports BLOCKED-ON domain-engineer.
- No EMR run, no cloud work, no commits.

Note step 03 (T2, report `docs/agent-runs/phase-06/agent-report.md`): done, COMPLETE.

- Code: jobs 10 and 11, the driver `SparkPipeline`, and the Spark run manifest
  (`RunManifest.scala`, `Summaries.scala`, `Export.scala`, `SparkPipeline.scala`).
  Added suites: `RunManifestSuite` (5 tests) and `PipelineSuite` (5 tests).
- Local integration run (local[2], 4 shuffle partitions, UI off), run ID
  `run-4903a4f9eabba786`: SUCCEEDED, 77 invariants checked, all 0.
  The observed numbers are in `output-spark-local-run.md`.
- The graph has 203 nodes and 403 edges, 29 components (largest 5), 29 communities
  and 0 rejects.
  Of 120 path pairs per non-empty window, 4 are found.
- Full `sbt check` ran once and exited 0.
  Passed totals were 94, 68, 112, 98 and 30; the Spark project ran 30 tests.
- Contract gap: the tables with no core record are written in the committed
  EvidenceItem shape (evidence-v1); nodes and edges use core GraphNode and GraphEdge.
  Nothing is BLOCKED-ON domain-engineer.
- Sample limits: every sample work is from 2014, so the windows 2000-2009 and
  2015-2026 are empty, and there are no cross-community edges.
  The temporal comparisons therefore compare an empty window with a full one.
- Not done: no paper summaries; the config has no summary limits, so a domain-engineer
  config field is needed before a full-DBLP run; the Neptune bulk-load CSV conversion
  belongs to storage-engineer.
- Bounded EMR Spark run: NOT RUN. Cloud work starts in Phase 12.

Note step 04 (documentation-engineer, report
`docs/agent-runs/phase-06/step-03-documentation-engineer-report.md`):
`docs/spark-jobs.md` written and `modules/spark/MODULE.md` rewritten.

- Every number is cited to step 01, step 02, step 03 or the local-run output; none comes
  from the reference repository.
- The scala-library exclusion from the Spark job jar (MINOR-2 option (b)) is now in the
  MODULE.md Known issues, which closes carried-forward MINOR-B.
  MINOR-A, MINOR-D, N-a, N-c and N-d stay open.
- No sbt was run and no documented command was replayed, as the work order directed.
  Public API names were checked by grep against `src/main`.
- Not stated in the reports at the time, so not claimed then: whether the driver sets
  `spark.cleaner.referenceTracking.cleanCheckpoints` (it does, see note R1-3), and whether
  `sbt packageJobs` ran with the Phase 06 sources (R1-2 ran
  `sbt sparkAnalytics/assembly`, not `packageJobs`).

Phase rules:

- Deadline is 2026-11-25; build only what the work order and the gate need.
- Spark reads only the Phase 04 J07 export and the Phase 05 er-v1 decisions, located
  through their run manifests, never raw data or `datasets/gold/`.
- A missing core contract or config field is BLOCKED-ON domain-engineer.
- Machine limits (8 GB laptop): one sbt at a time, every command in the foreground with a
  timeout, no background shells or polling, only `sbt sparkAnalytics/test` while working,
  and the full `sbt check` once, at the end of step 03.
- No commits, no cleanup script run and no cloud resources.

## Carried forward from Phase 06 (for the human gate)

Items 1 to 4 and 6 come from `docs/agent-runs/phase-06/review-report-02.md` (Review 2).
Each item names its source when it is not that report.

1. Identity policy (human decision).
   Policy (a), the contradiction cut, is implemented.
   The orchestrator routed it from the human addendum ("Unresolved and conflict
   decisions are preserved, never merged").
   The human ratifies (a) or overrules it for policy (b), keep and disclose.
   Consequence under (a): one probable person can appear as two nodes, and two pieces
   can both author one canonical work.
   `worksWithTeamAboveEverySourceList` went from 1 to 3.
2. No config bound on contradicted-cluster size before a full-DBLP run.
   One row per cluster sits on one executor.
   Owner: domain-engineer (config field), then spark-engineer.
3. The greedy cut can refuse more links than a minimum cut would (9bcc lost 2 links).
4. The bounded EMR Spark run is NOT RUN until Phase 12.
   The infra-engineer run script must pass `--checkpoint-dir`; a non-local master
   without it exits 2 (`SparkPipeline.scala:62`, from
   `docs/agent-runs/phase-06/repair-01-spark-engineer.md`).
5. Not done by scope (source: Phase 06 step 03 report, `agent-report.md`):
   - no paper summaries;
   - no summary limits in the config, so a domain-engineer config field is needed
     before a full-DBLP run;
   - the Neptune bulk-load CSV conversion belongs to storage-engineer (Phase 07);
   - the COAUTHORED_WITH window filter (MINOR-3, option 2) is the graph query contract
     for storage-engineer and service-engineer (orchestrator work order).
6. Nits from Review 2, at the human's discretion:
   - NIT-A (spark-engineer): `CanonicalSuite.scala:358` says 9 links; the fixture has 10.
   - NIT-B (documentation-engineer): `docs/spark-jobs.md:323-324` and `:381-382` cite LR;
     the 2532 plan size and "10 vs 5, 5 vs 12" come from LR0 and SR3, not LR.
   - NIT-C (documentation-engineer): `docs/spark-jobs.md:46-47` says all code is
     DataFrame and SQL, without the two Java UDFs; the driver-traffic list at `:281-283`
     omits the cut's at most 3 outcome rows and the policy-version rows.
7. Process (orchestrator work order; `STATUS.md` note step 02):
   - the stop hook asked for `sbt check` before the scheduled run several times; the
     orchestrator held to the one-sbt rule;
   - guard-dangerous false positives on the test-marker path and gate-file names belong
     to factory-engineer, Phase 11 (see "Carried forward from Phase 05", item 7).

## Phase 08 task list

Phase 08 is embeddings and S3 Vectors ingestion (storage-engineer and rag-engineer, with
the upstream steps owned by dataset-engineer, hadoop-engineer,
entity-resolution-engineer and spark-engineer), work order and human addendum in
`docs/agent-runs/phase-08/prompt.md`.
Reports are saved in `docs/agent-runs/phase-08/`.
The reference material in `~/ref-phase-08/` is a starting point only; each owner adapts
it and owns the result.

| Step | Owner | Scope | Report | Done means / check | State |
|---|---|---|---|---|---|
| pre | documentation-engineer | this task list | step-pre-documentation-engineer-report.md | table present | done |
| S1 | dataset-engineer | 100,000 DBLP works (>=2 authors, years 2000-2024, deterministic hash rule), OpenAlex by DOI with abstracts, Crossref capped at 5,000, ORCID unchanged; raw data only in git-ignored datasets/samples/local/<snapshot>/ | step-S1-dataset-engineer-report.md | fetch, verify and manifest validation pass; counts, year distribution, checksums | done, OpenAlex partial |
| S2 | hadoop-engineer | Phase 04 LocalRunner J01-J07 on the S1 snapshot, no code change unless a run fails | step-S2-hadoop-engineer-report.md | run ID, counters, max team size, largest pair expansion, largest blocking group | done, see note S2 |
| S3 | entity-resolution-engineer | er-v1 LocalMatchRunner, adjudicator off, on the S2 run | step-S3-entity-resolution-engineer-report.md | run ID and outcome counts | done, see note S3 |
| 00 | spark-engineer | JSON-lines export beside Parquet, institutionId in summaries, paper-abstract rows, every coauthor window in neptune.json; local pipeline on S2 and S3 runs | step-00-spark-engineer-report.md | sbt sparkAnalytics/test green; new Spark run ID | BLOCKED-ON at 100k; step-down to 25,000, see note 00 |
| S1b | dataset-engineer | 25,000-work subset of the S1 snapshot by the same rule (1,000 per year 2000-2024, smallest sha256 of the dblp key), OpenAlex and Crossref filtered to its DOIs, no new fetch | step-S1b-dataset-engineer-report.md | verify and manifest validation pass; counts | done, see note S1b |
| S2b | hadoop-engineer | LocalRunner J01-J07 on the 25k subset | step-S2b-hadoop-engineer-report.md | run ID, counters | done, see note S2b |
| S3b | entity-resolution-engineer | er-v1 LocalMatchRunner, adjudicator off, on S2b | step-S3b-entity-resolution-engineer-report.md | run ID, outcome counts | done, see note S3b |
| 00b | spark-engineer | local Spark pipeline on S2b and S3b (the step-down rerun of step 00; last allowed step-down is 10,000) | step-00b-spark-engineer-report.md | usable Spark run ID; table counts | done, INVARIANT_FAILURES, see note 00b |
| 00c | domain-engineer | raise graph componentMaxIterations in core config (components did not converge at 40 in 1950-2026) | step-00c-domain-engineer-report.md | core tests green | done, see note 00c |
| 00d | spark-engineer | path evidence pairs chosen from reachable targets so collaboration-path-v1 has documents; one 25k rerun | step-00d-spark-engineer-report.md | sparkAnalytics/test green; run SUCCEEDED; path documents > 0 | done, see note 00d |
| 01 | rag-engineer (R1) | rendering templates (researcher, community, path, paper, topic-institution) with versions, chunking policy, embedding model versioning, behind a storage port | step-01-rag-engineer-report.md | sbt rag/test green | done through 01b |
| 02a | storage-engineer | DocumentRenderer port (signature in the step 01 report), bounded embedding work queue, vector write batches, S3 text with pointer and compact metadata, hash+model skip, one key one text, RunLedger failures and retries, loader taking text only from the renderer | step-02a-storage-engineer-report.md | sbt storage/test green | done, see note 02a |
| 01b | rag-engineer | SemanticRenderer extends the storage port; composition runner for the local ingestion in rag test scope | step-01b-rag-engineer-report.md | sbt rag/test green | done, see note 01b |
| 02b | storage-engineer | local ingestion of run-ccf619c509611f06 into all five indexes through the 01b runner | agent-report.md, output-local-ingestion.md | counts, throughput, rejects, duplicates | done, see note 02b |
| 03 | rag-engineer (R2) | compact semantic-context format via S3 pointer, retrieval smoke tests on a hand-built fixture, graph values never from vectors; full sbt check | step-02-rag-engineer-report.md, output-sbt-check-step03.txt | full sbt check exit 0 | done, see note 03 |
| 04 | documentation-engineer | embedding and vector-index docs section, lineage line, module notes | step-03-documentation-engineer-report.md | facts only from this phase's reports | done, see note 04 |
| Review 1 | code-reviewer (fresh) | whole phase | review-report.md | verdict | done, APPROVE WITH CARRIED-FORWARD ITEMS, see note R1 |
| Repair 1 | owners named by review | one round | repair-01*.md | sbt check if source changed | done, see notes R1-1 to R1-4 |
| Review 2 | code-reviewer (fresh) | only if Review 1 has CRITICAL or MAJOR | review-report-02.md | verdict | not needed (no CRITICAL or MAJOR) |
| Gate | human | phase 08 gate | human-gate.md, commit.txt | - | pending |

Note S1 (report `docs/agent-runs/phase-08/step-S1-dataset-engineer-report.md`).
dataset-engineer is COMPLETE for DBLP, Crossref and ORCID; OpenAlex is PARTIAL.
The snapshot 2026-11-24 is in the git-ignored `datasets/samples/local/2026-11-24/`.
DBLP has 100,000 works, 4,000 per year for 2000 to 2024.
Each year's works are selected by the smallest sha256 of the DBLP key.
OpenAlex has 50,000 of 90,285 DOIs requested.
The keyless free daily budget ran out (HTTP 429).
OpenAlex gives 49,749 works, and 25,078 of them have an abstract.
Crossref has 5,000 DOIs requested and 4,516 found.
ORCID is unchanged.
The manifest validated, `verify` passed, and the dataset unittest ran 18 OK.
Orchestrator decision (2026-11-24): the phase continues with the partial OpenAlex set.
The human is asleep and the addendum says not to stop.
Completing OpenAlex is an optional human action.
It is listed in the S1 report under MANUAL ACTION REQUIRED and carried to the gate.

Note S2 (report `docs/agent-runs/phase-08/step-S2-hadoop-engineer-report.md`).
hadoop-engineer is COMPLETE, with no code change.
The run ID is run-834f71c82961a194 on the snapshot 2026-11-24.
Its output is in the git-ignored `datasets/samples/local/2026-11-24/hadoop-out`.
Attempt 1 failed because of relative paths; the forked JVM's cwd is modules/hadoop.
Attempt 2 used absolute paths and succeeded in 570 s with a 2 GB heap, so no step-down
was needed.
Key counters:
J01 accepted 100,000;
J02 OpenAlex accepted 49,730, rejected 19, with 25,078 abstracts;
J02 Crossref 4,516;
exported works 154,246, mentions 545,366, pairs 980,859.
Maximum team size is 133, and the largest pair expansion is 8,778.
The largest blocking group is r:wang_y with 1,578 members, above maxBlock 200,
so the fallback path handled it.
Open item: attempt 1 left the untracked folder `modules/hadoop/datasets/` (a run
manifest of run-8e1abb4c22da9c06) inside hadoop-engineer's territory.
The S2 report does not name it.
It is not removed yet and is routed to hadoop-engineer at the review.

Notes on the table:

- Report names for S1, S2, S3, 00 and 01 to 04 are set by `prompt.md`.
- So step numbers and report numbers differ for steps 03 and 04.
- A STATUS update by documentation-engineer follows every owner step and is saved as
  `step-<NN>s-documentation-engineer-report.md`.
- No cloud in this phase.
- The bounded AWS ingestion of 10,000 vectors is NOT RUN until the cloud phases.
- Step-down rule: a pipeline stage that fails for memory or exceeds 90 minutes is rerun
  once at 25,000 works, then once at 10,000, with every attempt recorded.

Note S3 (report `docs/agent-runs/phase-08/step-S3-entity-resolution-engineer-report.md`).
entity-resolution-engineer is COMPLETE, with no code change.
The decision run is run-85fd79957d197ffc over Hadoop run run-834f71c82961a194.
Its output is in the git-ignored `datasets/samples/local/2026-11-24/er-out`.
The policy is er-v1 and is unchanged; the adjudicator is off.
The gold set is not re-evaluated.
There was one attempt, with 1:21:47 wall time and a 2 GB heap.
That is about 8 minutes under the 90-minute limit, so no step-down was needed.
Outcomes over 4,561,097 decisions:
ACCEPTED 543,851;
REJECTED 3,928,560;
UNRESOLVED 65,759;
CONFLICT 22,927.
LLM_ADJUDICATED is 0, and the unresolved rate is 0.0144.

Note 00 (report `docs/agent-runs/phase-08/step-00-spark-engineer-report.md`).
spark-engineer is BLOCKED-ON hadoop-engineer after the 100,000-work local run failed.
The Phase 07 gate code changes are in place and tested in modules/spark.
They are JSON-lines copies of six tables under `jsonl/`, `institutionId` in the
summaries, a paper-abstracts table, every coauthor window in `neptune.json`, and logic
spark-v3.
`sparkAnalytics/test` passed 41 of 41.
The 100,000-work run did not finish.
Attempt 1 ran out of memory after 23:04 with a 2 GB heap.
Attempt 2, with 32 shuffle partitions, was killed at the 90-minute limit.
Run `run-c4b35c788d05a93c` is not usable, and no later step reads it.
spark-engineer's retry at full scale before stepping down is recorded as a possible
deviation from the step-down rule, for the human to decide.
About 2.3 GB of leftover git-ignored output stays in
`datasets/samples/local/2026-11-24/spark-out`.
Orchestrator routing: the step-down to 25,000 works runs through S1b, S2b, S3b and 00b.

Note S1b (report `docs/agent-runs/phase-08/step-S1b-dataset-engineer-report.md`).
dataset-engineer is COMPLETE, with no network fetch.
The 25,000-work subset is in `datasets/samples/local/2026-11-24-25k/`.
That folder is git-ignored.
It is derived from the 2026-11-24 snapshot by the new `subset` command in
`datasets/scripts/fetch_samples.py`.
DBLP has 25,000 works, 1,000 per year for each year from 2000 to 2024.
A rerun of the S1 rule at 25,000 is byte-identical to the derived DBLP file (`cmp`).
The DBLP works have 22,602 distinct DOIs.
OpenAlex has 12,433 works, 6,292 of them with an abstract.
Crossref has 1,137 works.
`verify` reports 4 files matching `retrieval.json`, and manifest validation passed.
The dataset unittest run reports 19 tests OK.
The manifest records the subset in the field `derived_subset_25k`.
OpenAlex coverage is thin: 12,433 works for 22,602 DOIs, with a DOI-prefix bias
inherited from the parent fetch (report RISKS).

Note S2b (report `docs/agent-runs/phase-08/step-S2b-hadoop-engineer-report.md`).
hadoop-engineer is COMPLETE, with no code change.
Run ID `run-03a81f6f54f3fc3c` ran on the 25,000-work subset, snapshot label 2026-11-24.
Its output is `datasets/samples/local/2026-11-24-25k/hadoop-out`, which is git-ignored.
The first attempt succeeded in 187 s with a 2 GB heap.
J01 accepted 25,000 DBLP works with no rejects.
J02 accepted 12,427 OpenAlex works and rejected 6, with 6,292 abstracts.
J02 accepted 1,137 Crossref works.
The export holds 38,564 works, 136,266 mentions and 241,686 pairs.
The maximum team size is 75, and the largest pair expansion is 2,775.
The largest blocking group is `r:wang_y` with 361 members, which took the fallback path.

Note S3b (report `docs/agent-runs/phase-08/step-S3b-entity-resolution-engineer-report.md`).
entity-resolution-engineer is COMPLETE, with no code change.
The decision run is run-cb5a0421c406737d over Hadoop run run-03a81f6f54f3fc3c.
Its output is in the git-ignored `datasets/samples/local/2026-11-24-25k/er-out`.
The policy is er-v1 and is unchanged; the adjudicator is off.
The gold set is not re-evaluated.
There was one attempt, with 1:19:48 wall time and a 2 GB heap.
That is about 10 minutes under the 90-minute limit, so no step-down was needed.
Peak RSS was about 1.4 GB and CPU use was 36%.
The report suspects I/O wait or host contention; it did not verify this.
Outcomes over 903,286 decisions:
ACCEPTED 122,405;
REJECTED 771,520;
UNRESOLVED 7,370;
CONFLICT 1,991.
LLM_ADJUDICATED is 0.

Note 00b (report `docs/agent-runs/phase-08/step-00b-spark-engineer-report.md`).
spark-engineer ran the local Spark pipeline on the 25k subset (the step-down rerun).
The Spark run ID is run-c73766e8001fce5a, with -Xmx2g and 8 shuffle partitions.
It finished on the first attempt in 24:56 wall time, with peak RSS about 2.9 GB.
JSON-lines and Parquet counts agree for all six tables:
graph-nodes 118,960; graph-edges 543,699; coauthor-edges 337,677;
researcher-metrics 167,600; semantic-summaries 217,383; paper-abstracts 6,292.
Summary documents: researcher-profile 167,600; collaboration-community 43,826;
topic-institution-summary 5,957; collaboration-path 0.
neptune.json lists all 4 windows, and 106,614 summary rows carry an institutionId.
The status is INVARIANT_FAILURES, for two reasons.
(1) Components did not converge within 40 iterations in 1950-2026: one component
of 8,615 split as 8,540 and 75.
(2) 0 of 120 sampled path pairs per window were found within 6 hops, so there are
no path documents.
Orchestrator routing: repair before step 01, because the gate needs all five indexes.
Run 00c, then 00d; later steps read the 00d run.

Note 00c (report `docs/agent-runs/phase-08/step-00c-domain-engineer-report.md`).
graph.componentMaxIterations is raised from 40 to 100 in scholarchain-v1.yaml.
It also bounds the BFS and coreness loops.
A new ConfigSuite test pins the value; sbt core/test passed 120, failed 0.
No consumer hardcodes 40; they read the config value.
The value is in the run manifest, so the 25k Spark rerun in 00d gets a new run ID.

Note 00d (report `docs/agent-runs/phase-08/step-00d-spark-engineer-report.md`).
Path-evidence targets now come from each sampled source's reach within 6 hops.
The logic version is spark-v4.
sparkAnalytics/test passed 45 and failed 0.
Spark run run-ccf619c509611f06 used the 25k inputs and took 25:14 wall time.
Its status is SUCCEEDED, with 114 invariants checked and 0 nonzero.
Components in 1950-2026 converged at 15,830, with a largest of 8,615.
An offline union-find check confirmed both numbers.
Index researcher-profile-v1 has 167,600 rows.
Index collaboration-community-v1 has 43,826 rows.
Index collaboration-path-v1 has 278 rows.
Index topic-institution-summary-v1 has 5,957 rows.
Index paper-abstract-v1 has 6,292 rows.
Every later step reads run-ccf619c509611f06 through its run manifest.

Note 01 (report `docs/agent-runs/phase-08/step-01-rag-engineer-report.md`).
rag-engineer is BLOCKED-ON storage-engineer, because storage has no renderer port.
Five rendering templates are versioned.
- tpl-researcher-profile-v1
- tpl-collaboration-community-v1
- tpl-collaboration-path-v1
- tpl-topic-institution-summary-v1
- tpl-paper-abstract-v1
The four summary templates adopt Spark's text byte for byte after a conformance check.
That keeps one identity with one text.
Chunk policy chunk-v1 allows at most 2,000 UTF-16 units, 200 overlap and 32 chunks.
Embedding version rule embedding-version-v1 is `<modelId>@<dimension>`.
The local fake is hash-embed-v1@256.
sbt rag/test passed 33 of 33.
The real run run-ccf619c509611f06 rendered 223,953 documents into 226,807 chunks.
It had 0 rejects and 0 conflicts.
Orchestrator routing: 02a (storage port), then 01b (rag wires the port), then 02b.
rag depends on storage, so the composition runner lives in rag test scope.

Note 02a (report `docs/agent-runs/phase-08/step-02a-storage-engineer-report.md`).
storage-engineer COMPLETE; `sbt storage/test` passed 179, failed 0, ignored 1.
The real-export ingestion is not run yet (step 02b).
- DocumentRenderer port defined with rag's requested signature.
- The loader takes text only from the renderer, one vector per chunk.
- Work queue: a manifest-driven batch with a bounded in-process queue (capacity 1000),
  not SQS, because no cloud is used this phase and every step is idempotent.
- Batches of 100; retry policy of 4 attempts.
- Skip by text hash and model version `<modelId>@<dimension>` (local fake
  hash-embed-v1@256).
- One key for two texts is refused.
- Rejections and retries are recorded in RunLedger; failure records are capped at 500
  per load.
- The vector holds only the S3 pointer and compact metadata.
- `LocalIngestion.run` is the entry point, with a bounded deterministic subset option.

Note 01b (report `docs/agent-runs/phase-08/step-01b-rag-engineer-report.md`).
rag-engineer COMPLETE; `sbt rag/test` passed 41, failed 0 (33 before this step).
- SemanticRenderer now extends storage's DocumentRenderer and uses storage's types.
- The embedding version formula is one function, storage's EmbeddingVersion.id.
- The abstract template moved to tpl-paper-abstract-v2, because v1 wrote the work ID
  into the text.
- Storage refuses text that repeats a metadata value of 40 or more characters.
  71 of 6,292 abstracts have a work ID that long, so v1 would have been rejected.
- Smoke run with 200 rows per type: 1,071 chunks ingested, 0 rejections in both passes.
- The runner is scholarchain.rag.LocalIngestionRun, in rag test scope.
- One sbt process took 47 minutes to exit after success; the cause was not found.
  Step 02b keeps a timeout on its command.

Note 02b (reports `docs/agent-runs/phase-08/output-local-ingestion.md` and the step 02b
section of `docs/agent-runs/phase-08/agent-report.md`).
storage-engineer COMPLETE for step 02b; no repository file was changed.
The local ingestion of run-ccf619c509611f06 ran into all five indexes through
`scholarchain.rag.LocalIngestionRun`, once, at N=8000.
The subset is bounded and deterministic: the smallest SHA-256 of the row key, 8,000
source rows per type.
28,527 of 223,953 meaningful source rows were ingested, giving 31,341 chunks.
Chunks per index: researcher-profile-v1 8,000; collaboration-community-v1 8,002;
collaboration-path-v1 278; topic-institution-summary-v1 8,674; paper-abstract-v1 6,387.
Rejected 0 and duplicates 0 in both passes.
The first pass wrote 584 vectors per second in 315 batches of 100.
The second identical pass wrote 0 and skipped all 31,341 chunks.
Retries 0 and ledger failure records 0; retry and rejection paths are covered by unit
tests only.
Heap after the first pass was 351 MB of 2,048 MB.
Embedding version: `hash-embed-v1@256` (local deterministic fake, no AWS call).
Query latency comes from the in-memory exact scan only, not from S3 Vectors.
NOT RUN until the cloud phases: bounded AWS ingestion of 10,000 vectors, AWS query
latency and real cost.
The full export of 223,953 rows is likely too large for a 2 GB heap.
That is an unmeasured estimate: 351 MB for 31,341 chunks is about 11.5 KB per chunk,
so about 2.5 GB for the full export.

Note 03 (report `docs/agent-runs/phase-08/step-02-rag-engineer-report.md` and
`output-sbt-check-step03.txt`).
rag-engineer COMPLETE for R2; `sbt rag/test` passed 63, failed 0 (41 before, 22 new).
- Format `semantic-context-v1`, schema `prompts/schemas/semantic-context-v1.json`.
- A hit is kept only through its S3 pointer, after a hash check and size bounds.
- `score` is the only number in the format; `GraphValuePolicy` keeps graph values out.
- Budget 10 items, 2,000 characters per item, 8,000 in total.
- `SemanticContextSuite` has 12 tests and `RetrievalSmokeSuite` 10.
- All five gate items are covered by tests.
- The one full `sbt check` exited 0 in 362 s (380 s wall time).
- It printed `14 project test runs passed`.
- Per-project test lines in printed order: 120; 179 with 1 ignored (storage); 63 (rag);
  68; 98; 45; 119.
  The names of the other lines are not printed by sbt and are not certain from the log.
- The Phase 9 workflow, prompts and verifier are not done.

Note 04 (report `docs/agent-runs/phase-08/step-03-documentation-engineer-report.md`).
documentation-engineer COMPLETE.
- New `docs/embeddings.md`: the five indexes, metadata schema, template and model
  versions, chunk policy, batch and retry settings, local object counts and the cost
  method.
  The two prices are UNVERIFIED, because the documentation tools cannot read
  `aws.amazon.com` pricing pages.
- New `docs/data-lineage.md`: the semantic-context lineage line and the scale-up and
  step-down facts.
- Updated `docs/storage.md`, `docs/service-limits.md`, `docs/spark-jobs.md`,
  `docs/hadoop-jobs.md`, `docs/entity-resolution.md`, `PROJECT_MEMORY.md` and the
  `MODULE.md` of core, storage, rag, spark, hadoop and entity-resolution.
- The step 04 run was interrupted once by a usage limit (HTTP 429) before it changed any
  file, and was rerun from the start.

Note R1 (report `docs/agent-runs/phase-08/review-report.md`, routing in `repair-01.md`).
Review 1 found 0 CRITICAL, 0 MAJOR, 5 MINOR and 6 NIT.
The repair round routes R1-1 to rag-engineer (MINOR-1 chunk limit off by one, NIT-4).
R1-2 goes to storage-engineer (MINOR-2 manifest row check, NIT-5).
R1-3 goes to spark-engineer (NIT-1, then the one full `sbt check`).
R1-4 goes to documentation-engineer (MINOR-3 wording, NIT-2, NIT-3, NIT-6).
Carried to the gate: the Neptune and DynamoDB JSON-lines loader test (MINOR-3 code part).
Also carried: the untracked `modules/hadoop/datasets/` folder for the human to remove
(MINOR-4, not to be committed), and the step 00 step-down deviation (MINOR-5).

Note R1-1 (rag-engineer, report `docs/agent-runs/phase-08/repair-01-rag-engineer.md`):
The chunk limit now accepts exactly `maxChunks` chunks and refuses one more.
`chunk-v1` is kept, and the golden spans are unchanged.
`decide` in `EmbeddingVersion.scala` now follows the loader's order: other text is
refused before a version mismatch.
A test that runs the real loader pins that order.
`sbt rag/test` passed 66 of 66.

Note R1-2 (storage-engineer, report
`docs/agent-runs/phase-08/repair-01-storage-engineer.md`):
R1-2 is done.
`LocalIngestion` scans the export and fails before any load when the rows read differ
from the load manifest, in both full and subset modes.
Row counts in the report now come from the rows read.
The `queueCapacity` comment now says the queue bounds only the waiting documents.
`sbt storage/test` passed 184, with 1 ignored; `sbt rag/test` passed 66.

Note R1-3 (spark-engineer, report `docs/agent-runs/phase-08/repair-01-spark-engineer.md`,
output `output-sbt-check-r1.txt`):
R1-3 is done.
NIT-1 is fixed: the `abstracts` `keyRule` text says rag's renderer makes the chunk IDs.
No computed value changed, and `LogicVersion` stays `spark-v4`.
The one full `sbt check` for the round exited 0 in 356 s.
The test lines were 120, 66, 184 (1 ignored), 68, 45, 119 and 98.
It printed `14 project test runs passed`.

Note R1-4 (documentation-engineer, this task):
R1-4 is done; docs edits only, no sbt run.
- MINOR-3 wording: `docs/storage.md` and `modules/storage/MODULE.md` now say the gap is
  closed for the vector loader only.
  The Neptune and DynamoDB loaders have not yet been run on the JSON-lines export.
- NIT-2: `docs/data-lineage.md` says 00b had one nonzero invariant.
- NIT-3: `docs/embeddings.md` and this file state the full-export heap as an unmeasured
  estimate of about 2.5 GB.
- NIT-6: `PROJECT_MEMORY.md` says 100 is about 2.1 times the 47 iterations observed.
- Doc lines from the repair reports: the chunk limit (32 accepted, a 33rd refused) and
  the decide order in `docs/embeddings.md`; the queue capacity wording; the manifest row
  check; the rag, storage and spark `MODULE.md` repair entries.
- Review 2 is not needed.
  Next is the human gate.

## Carried forward from Phase 08 (for the human gate)

Sources: the Phase 08 reports in `docs/agent-runs/phase-08/` and `run-log.md`.

- OpenAlex is partial: 50,000 of 90,285 DOIs, because the keyless daily budget ended in
  HTTP 429.
  Completing it needs a free API key or a wait past midnight UTC, and `--force` restarts
  all DOIs (S1 report).
  61 DBLP DOIs with XML entities cannot match.
- The 100,000-work Spark local run does not fit 2 GB and 90 minutes.
  The chain ran at 25,000 works instead.
- S3 (1:21:47) and S3b (1:19:48) wall times are close to the 90-minute limit; S3b used 36
  percent CPU, and the cause (I/O wait or host contention) is unverified.
- Several GB of git-ignored leftover outputs remain under `datasets/samples/local/`:
  the 100,000 Spark output (about 2.3 GB), the 00b run and checkpoints (about 2.6 GB),
  the 00d run (about 2.6 GB), er-out 1.3 GB and 250 MB, and the raw dump.
  Agents do not delete data; the human decides.
- MINOR-4 (open): the untracked folder `modules/hadoop/datasets/` (from S2 attempt 1)
  is not git-ignored.
  The human removes it and must not commit it.
- MINOR-3 code part (open): a contract test that loads the `neptune.json` and
  `dynamodb.json` JSON-lines with `extraFields` through the Neptune and DynamoDB loaders
  (storage-engineer).
  Until then no doc claims those loaders are proven on the real export.
- spark-engineer retried the 100,000 run at full scale (32 shuffle partitions) before
  stepping down; this is a possible deviation from the step-down rule, for the human
  (MINOR-5, open, human decision).
- The full-export local ingestion (223,953 rows, about 2.5 GB estimated, unmeasured) is
  likely too large for 2 GB; only 12.7 percent of the rows were ingested.
- Review 1 (APPROVE WITH CARRIED-FORWARD ITEMS) and repair round 1 are done.
  The review and repair reports are in `docs/agent-runs/phase-08/`.
- Duplicate-key semantics across queue windows: a key written in an earlier window is
  not retracted if a later conflicting copy arrives.
- NOT RUN until the cloud phases: the bounded AWS ingestion of 10,000 vectors, query
  latency on S3 Vectors and real cost.
  Both price values are UNVERIFIED (`docs/service-limits.md`).
- The Stop hook asked for `sbt check` many times while agents were running; the
  orchestrator declined, because only one sbt may run at a time and the full check was
  scheduled for the end of step 03.
- Step 04 was interrupted once by a usage limit and rerun from the start (`run-log.md`).
- Step 02b left a background shell that kept waiting after the report; the human stopped
  it (`run-log.md`).
- One sbt process took 47 minutes to exit after success (step 01b); the cause was not
  found.
- The Phase 9 workflow, synthesis prompt and verifier are not done.

## Phase 07 task list

Phase 07 is storage adapters and loading (storage-engineer, with domain-engineer and
entity-resolution-engineer for the named steps), work order and human addendum in
`docs/agent-runs/phase-07/prompt.md`.
Reports are saved in `docs/agent-runs/phase-07/`.
The reference material in `~/ref-phase-07/` is a starting point only; each owner adapts
it and owns the result.

| Step | Owner | Report | State |
|---|---|---|---|
| 00 core.aws package (HTTP client, SigV4, credentials) | domain-engineer | step-00-domain-engineer-report.md | done (sbt core/test: 115 passed, 0 failed; 21 new tests by suite counts, the report says 29) |
| 01 T1 ports, fakes, S3 layout, DynamoDB tables | storage-engineer | step-01-storage-engineer-report.md | done (sbt storage/test: 33 passed, 0 failed) |
| 02 T2 Neptune bulk-load manifests, CSV, templates, edge validation | storage-engineer | step-02-storage-engineer-report.md | done (sbt storage/test: 82 passed, 0 failed; 49 new) |
| 03 T3 S3 Vectors adapter/loader, five indexes, embedder, reload, gated AWS suite | storage-engineer | agent-report.md | done (sbt storage/test: 153 passed, 0 failed, 1 ignored = gated AWS suite NOT RUN; 71 new) |
| 04 Bedrock adjudicator adapter, full sbt check | entity-resolution-engineer | step-03-entity-resolution-engineer-report.md | done (sbt entityResolution/test 119 passed; full sbt check exit 0, 312 s: totals 153+1 ignored, 115, 68, 119, 98, 33) |
| 05 service-limits, storage docs, modules/storage/MODULE.md | documentation-engineer | step-04-documentation-engineer-report.md | done (docs/service-limits.md, docs/storage.md, three MODULE.md files; no sbt run) |
| Review 1 | code-reviewer (fresh) | review-report.md | done, REQUEST_CHANGES (1 major, 7 minor, 6 nits) |
| Repair round 1 | owners named by the review | repair-01*.md | done |
| Review 2 (only if review 1 has CRITICAL or MAJOR) | code-reviewer (fresh) | review-report-02.md | done, REQUEST_CHANGES (1 new major: R2-MAJOR-1, format-only write outside territory; 4 new nits); no second repair round, all open items carried to the gate |
| Gate | human | human-gate.md | next |

Repair round 1 tasks (routing from `docs/agent-runs/phase-07/repair-01.md`):

| Task | Owner | Findings | State |
|---|---|---|---|
| R1-0 | documentation-engineer | STATUS: review 1 result and repair task list | done |
| R1-1 | storage-engineer | MAJOR-1 comment, MINOR-1, MINOR-3 code, MINOR-5, MINOR-7, NIT-5 | done (MAJOR-1 comment and invariant, MINOR-1, MINOR-3, MINOR-5, MINOR-7, NIT-5; sbt storage/test 158 passed, 0 failed, 1 ignored) |
| R1-2 | domain-engineer | NIT-3, NIT-4; then the one full `sbt check` (source changed) | done (NIT-3, NIT-4; sbt core/test 119 passed; full sbt check exit 0, 305 s: 158+1 ignored, 119, 68, 98, 33, 119) |
| R1-3 | documentation-engineer | MAJOR-1 docs, MINOR-2, MINOR-3 docs, MINOR-4, MINOR-6 (documented), NIT-2, STATUS | done (docs only, no sbt run; see note R1-3) |
| Review 2 | code-reviewer (fresh) | re-check, `review-report-02.md` | done, see the Review 2 row above |

Note R1-3 (documentation-engineer, docs only, no sbt run; sources
`repair-01.md`, `repair-01-storage-engineer.md`, `repair-01-domain-engineer.md`,
`output-sbt-check-r1.txt`):

- MAJOR-1 docs: `docs/storage.md` section 7 and `modules/storage/MODULE.md` Known
  issues state the served-run invariant.
- MINOR-2: the cleanup section says a run's semantic-context prefix may be deleted only
  with the vectors whose `textUri` points into it; the load report holds counts and
  rejections only.
- MINOR-3 docs: bound is 25 HTTP attempts including retries (counting transport); the
  variable is `SCHOLARCHAIN_IT_TEST_VECTOR_INDEX`, a dedicated test index.
- MINOR-4: restated as a wiring requirement (callers use `maxAttempts` 1 under a
  `RetryPolicy`).
- MINOR-6 documented; MINOR-1 behavior documented (a missing input directory is one
  visible malformed-row rejection, the stage still records Succeeded).
- NIT-2 and the R1-2 core changes are in `modules/core/MODULE.md`.
- Not replayed: no command was run; the gated suite is still NOT RUN.

## Carried forward from Phase 07 (for the human gate)

Source: `docs/agent-runs/phase-07/review-report-02.md`, section "MANUAL ACTION REQUIRED"
(items 1 to 9).
Review 1 and repair round 1 are in `review-report.md` and `repair-01.md`.
The human may overrule the MAJOR-1 decision at the gate.
There is no second repair round; every item below goes to the gate.

1. R2-MAJOR-1: a format-only write outside territory.
   domain-engineer's `sbt scalafmtAll` reformatted `NeptuneAdapters.scala` and
   `Tables.scala` (storage-engineer's files), and its report says it did not.
   Decide: storage-engineer accepts the two files, or the human discloses the write in
   `human-gate.md`.
   No behavior change; the R1-2 ownership claim is wrong.
2. MAJOR-1 full fix: run scoping in the Neptune templates and vector reads.
   Until then, the one-served-run invariant applies: one served run per Neptune cluster
   and per vector index, cleaned or rebuilt by the human before a new run is loaded.
   The human may overrule the minimum-repair decision.
3. MINOR-6 code fix: the ledger keeps the first report after a repairing replay.
   With the MINOR-1 residual (documented, not fixed), a run with a mistyped path stays
   Succeeded and keeps the report of the bad run.
4. R2-NIT-1 to R2-NIT-4 (`review-report-02.md`):
   - R2-NIT-1 (storage-engineer): `AwsIntegrationSuite.scala:21` shows the old variable
     name `SCHOLARCHAIN_IT_VECTOR_INDEX`; `:30` says every attempt is counted, but the
     container-credentials fetch is not.
   - R2-NIT-2 (storage-engineer code, documentation-engineer wording): `expectedRows = 1`
     is met by a missing directory (`Loaders.scala:69` and `:277`,
     `VectorLoader.scala:320`).
   - R2-NIT-3 (documentation-engineer): `docs/service-limits.md:36-39` and `:232-233`
     still describe the old "48 hours" comment.
   - R2-NIT-4 (documentation-engineer): the stale "conditional" row and the duplicate
     carry-forward list in this file were corrected in the STATUS update that wrote
     this list.
5. NIT-1: the step 00 report says 29 new tests; the suite counts give 21 (the report
   stays as written).
6. NIT-6: more published SigV4 vectors (optional).
7. Spark-engineer observation: `load-manifests/neptune.json` lists
   `coauthoredWith.windows = ["1950-2026"]`, while the export has 1950-2026 and 2010-2014
   partitions.
8. Already open:
   - the Parquet reader gap (the real run `run-769e0d672ecd3c19` has not been loaded
     through the storage code);
   - Spark export gaps (`institutionId`, paper-abstract rows);
   - adjudication sharing `models.synthesisModel` (the Bedrock adapter reuses it);
   - the Phase 05 NIT-4 `_SUCCESS` rule is not implemented.
9. Not run: the gated AWS integration suite (`SCHOLARCHAIN_AWS_IT=true`); no cloud
   resource exists.

Step 00 notes for MODULE.md: new package scholarchain.core.aws (AwsHttp, SigV4,
credentials providers); signer vectors get-vanilla and post-vanilla taken from recall of
the published suite and recomputed with python hmac, not fetched; HttpResponse.body is a
String (binary S3 payloads unsupported).

Step 01 notes: DynamoDB tables use pk/sk strings and a ttl attribute (TTL on QuerySession
only, 7 days default); ResearcherMetricCache included (config requires it in aws mode);
reports and logs zones are in curatedBucket pending infra-engineer; S3 get is UTF-8 text
only; DynamoDB TTL lag unverified.

Step 02 notes: Neptune CSV and load manifest built from typed core rows (GraphNode,
GraphEdge, CoauthorEdge) and proven on a fixture shaped like the Phase 06 export;
RESOLVES_TO, DERIVED_FROM and SUPPORTED_BY are not exported by Phase 06 and are listed
as not exported in the load manifest.
Open gap for the human gate: the Phase 06 export is Parquet and the storage module has
no Parquet reader, so the real run run-769e0d672ecd3c19 has not been loaded through the
storage code (options: a Parquet dependency via build-engineer, JSON-lines exports via
spark-engineer, or a reader in a later phase).
Researcher metrics stay in DynamoDB ResearcherMetricCache, not on Neptune nodes.

Step 03 notes: five vector indexes wired with chunkId keys, compact filterable metadata
and long text in S3 semantic-context; batch 100, bounded retry of 408/429/5xx; every
loader replays without change.
The gated AWS integration suite (opt-in SCHOLARCHAIN_AWS_IT=true) was NOT RUN.
Open for the human gate: the real Parquet run was not loaded (no Parquet reader); the
Spark export has no institutionId in semantic summaries and no paper-abstract rows
(paper-abstract-v1 would come from Hadoop abstracts, absent locally).

Step 04 notes: the Bedrock adjudicator adapter (`BedrockAdjudicator`) was tested against a
local stub only and is not wired into `MatchJob` (`--adjudicator bedrock` and
`models.llmEnabled=true` are still refused).
The model ID is reused from `models.synthesisModel` pending a human decision (keep sharing
it, or add a core config field).
er-v1, the prompt `er-adjudication-v1` and the cache key are unchanged; the gold-set
evaluation was not rerun.
The full `sbt check` exited 0 and is the phase's single full check (output in
`docs/agent-runs/phase-07/output-sbt-check-step04.txt`).

Step 05 notes (documentation-engineer, report `step-04-documentation-engineer-report.md`):
`docs/service-limits.md` re-checked each limit on 2026-11-23 with the aws-docs tools, and
marks the rest unverified.
Findings to route: the comment at `modules/storage/.../Tables.scala:304` says DynamoDB TTL
deletion lags "up to 48 hours" but the documentation says "typically within a few days"
(storage-engineer, comment only); the step 00 report says 29 new core tests, but its suite
counts and the 94 to 115 change give 21 (noted in `modules/core/MODULE.md`).
New files: `docs/service-limits.md`, `docs/storage.md`.
Rewritten or updated: `modules/storage/MODULE.md`, `modules/core/MODULE.md`,
`modules/entity-resolution/MODULE.md`.
No sbt was run; no AWS call was made other than the read-only documentation tools.

STATUS is updated by documentation-engineer after each owner.
No cloud, no AWS call, no OpenSearch in this phase; the bounded AWS integration suite is
written but NOT RUN.
One sbt at a time; full sbt check once at the end of step 04.

## Previous state (phase 05, closed)

- Tasks done: step 01 (build-engineer), step 02 (T1), step 03 (T2), step 03b (T1b),
  step 04 (T3, evaluation on v1), step 05 (documentation), Review 1, R1-1, R1-2,
  R1-3 and Review 2.
- Task running: none.
- Next task: none; the gate is done and Phase 05 was committed as 4dfb3bd (recorded by
  acb5f21).
  The carried-forward items below stay open.
- Half done: nothing.
  v1 labeling is done (280 rows) and v1b is abandoned (decision 03).
- Git at the time of the update (before the commit): HEAD was 4388607 (Phase 04
  accepted), with the Phase 05 files uncommitted; they were committed as 4dfb3bd.
  `git status --short` at the time of that update showed:
  - modified, tracked: `STATUS.md`, `build.sbt`,
    `modules/entity-resolution/MODULE.md`;
  - untracked: `datasets/gold/`, `docs/agent-runs/phase-05/`,
    `docs/entity-resolution.md`,
    `modules/entity-resolution/src/main/resources/scholarchain/`,
    `modules/entity-resolution/src/main/scala/scholarchain/`, and 10 files in
    `tests/module-unit/entity-resolution/` (AdjudicationSuite, ErFixtures,
    ErNormalizeSuite, EvaluationSuite, FeaturesSuite, LeakageSuite, MatchJobSuite,
    MatchPolicySuite, ReviewQueueSuite, ReviewSupplementSuite).
- Harness: claude-code.
- Resume in a fresh session with:
  `cd ~/scholarchain && claude "Read STATUS.md and continue with its next step."`

## Carried forward from Phase 05 (for the human gate)

From `docs/agent-runs/phase-05/review-report-02.md` section 5, plus the items named
in this file.

1. MINOR-1 (Review 1): DBLP homonym false REJECT.
   Needs `er-features-v2` and policy `er-v2`, decided without looking at the labels.
2. NIT-3 (Review 1): LeakageSuite is a string tripwire only; no action.
3. NIT-4 (Review 1): the Phase 07 loader (storage-engineer) must treat an absent
   outcome folder as empty only when `_SUCCESS` exists.
   The Phase 07 DynamoDB cache must keep re-validation on every hit.
4. Human decisions:
   - whether to bump `MatchJob.LogicVersion` (NIT-A; `er-job-v1` is unchanged and a
     bump changes the run ID of any rerun);
   - whether to delete the stale `target/er-match-run/_local-tmp`;
   - the `target/**` gap in the ownership matrix (the evaluation copy under `target/`
     was not written; either factory-engineer changes the matrix or the human accepts
     the `docs/agent-runs/phase-05/` copy only);
   - `chmod 644 datasets/gold/er-gold-v1.csv` before committing (it is mode 755);
   - N4 (upstream link by run ID plus run manifest; implemented, not yet accepted).
5. Review 2 findings carried forward unfixed (no second repair round):
   - MINOR-B (documentation-engineer): MINOR-1 is missing from the Known issues of
     `modules/entity-resolution/MODULE.md`, and `docs/entity-resolution.md` lines 141
     and 180 describe the R-R3 intent, not the behaviour.
     `Features.scala:192-201` also fires on case, accent or spacing variants and when
     only one side has a suffix.
   - MINOR-C (documentation-engineer to track; factory-engineer to fix in Phase 11
     guard work): the Phase 01 item "Phase 05: gold-set bypasses (`rm -rf datasets`
     and similar) and MINOR-1 (gold-set interpreter writes)" (under "Carried forward
     from Phase 01") is still open, because Phase 05 made no control-plane change.
     `datasets/gold/er-gold-v1.csv` now holds real labels, so the open bypass protects
     real data.
   - NIT-B (entity-resolution-engineer): `AdjudicationSuite.scala:193-212`, the
     inputHash and candidateId refusals are not isolated, because those records also
     carry a mismatching model version `"m"`.
   - NIT-C (documentation-engineer): `docs/entity-resolution.md:274-275` omits that the
     scratch folder is removed only when this run created it.
6. Gold-label caveat (decision 04): the 13 NON_MATCH labels are known labeling errors
   ("different chapter" was judged, not "different person"), researcher precision
   (0.938) is understated, and the reject side is effectively unmeasured.
   Neither the labels nor the evaluation were changed.
7. Process items: the harness moves `sbt` to the background at 180 s (the sixth time
   this phase); the guard-dangerous false positive on the test-marker task name
   belongs to factory-engineer in Phase 11.

## Previous state (phase 04, closed)

- Tasks done: T0, T1, T2, T2b, T3, T4, Review 1, R1a, R1b, R1c, Review 2.
  All Phase 04 tasks are done.
- Task running: none.
- Next task: the human gate (ratify, commit, and decide N1 to N7).
- Half done: nothing.
  `sbt check` exited 0 in hadoop-engineer's final run, in the R1c run and in Review 2
  (core 94, hadoop 98, adversarial 68 of 68).
  `sbt packageJobs` has not been run this phase; the job-jar build is unobserved.
- Git: HEAD is 524dc1d (Phase 03 accepted).
  Uncommitted at the start of T4, per the session's git status: `STATUS.md`, `build.sbt`,
  `modules/hadoop/MODULE.md`, the deleted `tests/module-unit/hadoop/.gitkeep`, and
  untracked `docs/agent-runs/phase-04/`, `modules/hadoop/src/main/scala/scholarchain/`,
  `tests/adversarial/` files and `tests/module-unit/hadoop/` suites.
  T4 added the untracked `docs/hadoop-jobs.md`.
- Harness: claude-code (Phase 03 recorded 2.1.330; not re-probed for Phase 04).

## Open decisions for the human

- Decided (human, 2026-11-23): the DBLP reader decodes with the encoding declared in the
  XML prolog (ISO-8859-1 for DBLP, UTF-8 when none is declared) and rejects bytes that
  are invalid in it; implemented in R1a (brief B5), documented in R1b.
  This closes the T3 question in `docs/agent-runs/phase-04/test-report.md`.
- Decided (human, 2026-11-23): Phase 04 has one repair round only; findings still open
  after Review 2 are carried forward to the human.
- Confirm the inferred CC0 status of `datasets/samples/real/dblp.dtd`, or drop it.
  The DTD header and `README.txt` give CC0 for the export formats but do not name the DTD
  (`docs/agent-runs/phase-03/repair-01-dataset-engineer.md`).
  Dropping it means removing the file and its `retrieval.json` entry and changing the test.
- Confirm `emr-spark-8.0.0` is available in us-east-2 before the first cloud run
  (`project/Versions.scala`); infra-engineer checks in Phase 12.
- MINOR-2 scala-library option, before Phase 6. Choose one: (a) a Scala 3 LTS patch
  pinned to scala-library at or below 2.13.16; (b) scala-library excluded from the
  Spark job jar, as now built; (c) `userClassPathFirst`.
  Scala 3.3.9 pulls scala-library 2.13.18, and emr-spark-8.0.0 ships 2.13.16.
  Phase 06 update: the Phase 06 work order keeps option (b), scala-library excluded from
  the Spark job jar, as committed.
- Gate disclosures for `human-gate.md` (review 1 MAJOR-3, provenance in `run-log.md`
  step 0):
  - the Phase 2 deliverables were placed from a prepared package, not written by agents;
  - `toolchain/versions.env` was renamed to `toolchain/versions.sh` by the human;
  - terraform-mcp-server was installed by the human, and its checksum check is to be
    recorded in the gate.
- Dataset questions from T01 (MANUAL ACTION REQUIRED 1 to 5), in the report:
  `docs/agent-runs/phase-03/step-01-dataset-engineer-report.md`
  1. ORCID: API terms UNVERIFIED; choose the API sample or the 57 GB Public Data File.
  2. DBLP: is the README.txt CC0 statement enough, given the FAQ is bot-protected?
  3. DBLP full-dump SHA-256 is UNVERIFIED; it needs a 1.1 GB download and your approval.
  4. The 100,000-work bounded fetch is not run; it needs an OPENALEX_API_KEY.
  5. OpenAlex snapshot date and Crossref data file details are UNVERIFIED.
