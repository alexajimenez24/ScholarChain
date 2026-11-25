# test-engineer report, repair round 1 rerun (Phase 09)

HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

The repairs work. All 13 tests that were red before (F1 to F4) now pass, and none of the three existing suites was edited. The benchmark scores 33 of 33 on D, A, Pass and P. I added one suite for review findings M5 and M6 (6 tests); one of its tests is red and exposes a new defect, D5 below. The full `sbt check` ran once at the default SBT_OPTS and exited 1, only because of that one test.

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 09 repair round 1 rerun.
- Rerun BenchmarkQuestionSetSuite, BenchmarkWorkflowSuite and WorkflowGateSuite unchanged.
- Optionally add black-box tests for M5 (a number misattributed across two cited records) and M6 (a question with no years on the loaded window subset).
- Then run the full `sbt check` once at the default SBT_OPTS.

FILES CREATED:
- tests/adversarial/scholarchain/adversarial/rag/WorkflowRepairGateSuite.scala (6 tests: two for M5, three for M6, and an evidence-ID check over this suite's own answers)

FILES MODIFIED: none. BenchmarkQuestionSetSuite, BenchmarkWorkflowSuite, WorkflowGateSuite, BenchmarkRuntime and BenchmarkStore are untouched.

COMMANDS RUN (output observed):
- `timeout 300 scalafmt --test` on WorkflowRepairGateSuite.scala: "All files are formatted with scalafmt :)".
- `timeout 3000 sbt -batch "adversarialTests/testOnly scholarchain.adversarial.rag.*"`, SBT_OPTS printed as `-Xmx1536m -Xss4m` (the default, not overridden).
  - The harness moved it to the background after 180 s. I started nothing else and waited for the notification; the command's exit code was 1.
  - "[error] Failed: Total 143, Failed 1, Errors 0, Passed 142", "Total time: 360 s".
  - LocalRuntime.load took 329995 ms; heap used after load was 929 MB of 2560 MB max. So the forked heap works; there was no OutOfMemoryError.
- `timeout 3000 sbt -batch check`, once, same default SBT_OPTS. It was also moved to the background, and I waited the same way.
  - Exit 1, 18:13:06 to 18:26:20; "[error] Total time: 771 s".
  - Format checks: all success.
  - "Passed: Total" lines: 120; 131; 184 with 1 ignored; 98; 45; 119.
  - adversarialTests: "[error] Failed: Total 211, Failed 1, Errors 0, Passed 210", "Failed tests: scholarchain.adversarial.rag.WorkflowRepairGateSuite".
  - The test-marker task was not reached, so `.claude/.last-test-run` was not touched. The Stop hook flags this, and it stays so until the red test is resolved.
- `sha256sum` on the question files: unchanged (questions-v1.json 8584d275357c0b64445d60a7c7edcfb4e24a12d7b1b09d5b7407d25905b58e65, questions_v1.py aebce53ad77bb0b08d0311ac1d23ebb27f2b4083f37805682ec9be94bbf6561c).

TEST RESULTS (sbt check run; the testOnly run gave the same per-suite results):

| Suite | Total | Passed | Failed |
|---|---|---|---|
| BenchmarkQuestionSetSuite | 40 | 40 | 0 |
| BenchmarkWorkflowSuite | 68 | 68 | 0 |
| WorkflowGateSuite | 29 | 29 | 0 |
| WorkflowRepairGateSuite (new) | 6 | 5 | 1 |

The red test is "M5: a number after two names is checked against the grammatical subject, not the nearest name". It stays red.
- **Spec clauses:** stage 9, "Check every researcher identity, ... coauthor count, ... and numerical value against the bundle"; gate, "Unsupported claims are removed, weakened, or explicitly labeled".
- **Defect D5 (owner rag-engineer):** M5 is only partly fixed. Which researcher a number belongs to is decided by position, from the nearest name before it. So a false number is VERIFIED when the subject comes first and another cited researcher is named between the subject and the number.
- **Evidence (verbatim):** `claim-2 kept as VERIFIED: 'Hans Hagen, compared with Jan Mohring, has 5 distinct coauthors in window 2010-2014.' (uncertainty None); checks Vector((VERIFIED,Vector()))`.
  - The model claim cites both researchers' 2010-2014 metric records (pair bq-v1-08).
  - 5 is Jan Mohring's value; Hans Hagen's differs, because the test only uses a pair with different counts.
  - In the same run, a control claim stating Jan Mohring's own value comes out VERIFIED.
- rag-engineer's repair report (ASSUMPTIONS 1 and RISKS) names the opposite case, where a correct claim gets flagged. This case, where a false claim gets verified, is not disclosed there.
- Fairness is for code-reviewer to judge. A conservative fix would not verify a number when more than one cited researcher is named before it.

VALIDATION RESULTS:
- **F1 to F4 / M1 to M4 fixed** (observed):
  - bq-v1-07, 08, 09, 31 and 32 pass D and A.
  - bq-v1-21 passes A.
  - The planner check P passes 33 of 33, including topics 25 to 27.
  - The F4 gate test passes: no abstained answer carries a researcher row, and every row has a claim citing it.
- **M5 basic case fixed.** All cite both metric records, and the run used the model reply (no fallback):
  - B's value stated for A: not VERIFIED.
  - Swapped values in one two-name claim: not VERIFIED.
  - A number before the only name: not VERIFIED.
  - A misattributed publication count (when the two counts differ): not VERIFIED.
  - The misattributed summary is replaced.
  - The correct control claim is VERIFIED.
  - Still open: D5 above.
- **M6 fixed** (3 tests passed): bq-v1-10 with no years, a metric question for "Haizhou Li" with no years, and bq-v1-07 with no years. For each, these were checked and held:
  - the planned window is not 1950-2026 or 2000-2009;
  - the evidence, answer and audit windows are loaded windows;
  - every windowed graph query uses a loaded window;
  - every evidence item that names a window names a loaded one;
  - the summary, claims and abstention reason name no unloaded window, and no note states a fact "in window <unloaded>";
  - a window note is present;
  - the pair question runs coauthor-pair-v1.
  - The checks that apply only when the answer comes from 2010-2014 (the 3-hop path, the count of 10, the 2 joint publications) are conditional. Which window was answered was not printed, so whether they actually ran is not known.
- **Evidence IDs** on every numerical and path claim and on every researcher row of the new suite's answers: passed.
- **Protected now:** every gate item from test-report-02.md, plus the M5 and M6 regressions.

ASSUMPTIONS:
1. The unloaded configured windows (1950-2026, 2000-2009) are taken from review-report.md M6; the config field is not in the public API docs.
2. For M6, a typed refusal (InvalidRequest or Unsupported) is accepted as "not answering from an unloaded window"; it is checked only for which windows its queries used. Nothing was refused in this run.
3. For M5, an unsupported claim that is kept, but marked PARTIALLY_SUPPORTED and labeled, is accepted, as the spec's "weakened, or explicitly labeled" allows.
4. I read evidence IDs under the same query ID the workflow then runs, as WorkflowGateSuite does.

RISKS:
- At the default settings, the forked runtime load took 244 to 330 s, against 158 s before. The check still finished in 771 s.
- The M5 pair is picked at run time: the first of bq-v1-07, 08, 10, 11 and 33 whose two counts differ. It was bq-v1-08.
- Not done:
  - the reviewer's ranking-plus-name M5 variant (Paul Flicek's 65 stated for Haizhou Li), because no documented request is sure to put both records in one bundle;
  - window misattribution and swapped values inside a ranking (rag-engineer RISKS);
  - MINOR m1 and m2.
- State left behind: one new test file; build output, including target/tests/adversarial/benchmark-results-v1.md; scratch logs in the session scratchpad (rag-suites.txt and sbt-check.txt). Nothing under docs/agent-runs, no STATUS.md change, no commit.

MANUAL ACTION REQUIRED: none from me. The human still reviews benchmark question set v1 and semantic-relevance-rule-v1 at the gate.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer gives a fairness verdict on the one red test (D5) in the fresh re-check (review-report-02.md). No second repair round is planned, so if it is judged fair it goes to the human.

The benchmark results text is appended to `output-benchmark.md`.
