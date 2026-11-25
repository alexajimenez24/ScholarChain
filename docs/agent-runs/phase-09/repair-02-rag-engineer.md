HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

I fixed D5 in the verifier. A number in a claim that names researchers now gets a subject only when its clause makes the subject clear. In every other case it belongs to nobody, so per-researcher records cannot support it, and the claim is weakened and labeled instead of VERIFIED. `sbt rag/test` passed 133 of 133 (131 before). I added two unit tests and changed none of the existing assertions. I did not run the adversarial suites, as the work order said, so I have not seen the red WorkflowRepairGateSuite test pass; I only traced it in the code (details under RISKS).

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 09 repair round 2, a disclosed exception authorized by the human, D5 only (review-report-02.md, D5 and its repair brief). The subject of a number must be unambiguous before per-researcher records may support it, in the claim path and in the summary path. Cover "X, compared with Y, has N ..." and "With N ..., X leads Y." Unit tests go in VerificationSuite and WorkflowSuite. Run only `sbt rag/test`. No cloud, no commit, no change to STATUS.md, tests/adversarial, build.sbt or docs.

FILES CREATED: none

FILES MODIFIED:
- modules/rag/src/main/scala/scholarchain/rag/Verification.scala
  - New patterns `HardBreak` and `SoftBreak`.
  - The attribution block before steps 7 and 8 is rewritten: clause splitting, a new `subjectAt` that returns Either (one subject or ambiguous), and `backing` with a third field for ambiguity.
  - New reason text for ambiguous numbers in steps 7 and 8.
  - The scaladoc for step 8 is updated.
- tests/module-unit/rag/VerificationSuite.scala: +1 test, "repair D5".
- tests/module-unit/rag/WorkflowSuite.scala: +1 test, "repair D5".
- Workflow.scala is unchanged. The summary check already calls `ClaimVerifier.verifyText` over the whole bundle, so the fix covers it; the new WorkflowSuite test proves this.

The change (Verification.scala, steps 7 and 8):
1. **Clause splitting.**
   - Hard breaks: `.`, `;` or `:` followed by whitespace or the end of the text. They are found on the masked text, so periods inside names or titles, decimals and identifiers never split.
   - Soft breaks: "and", "while" or "whereas", with or without a comma before them. They split only when the current clause already states a number.
   - "and" also needs a known researcher named right after it, with no comma between that name and its number.
   - So "A, compared with B and C, has 5" stays one clause and "A, and not B, has 30" does not split, while "A has 41 ... and B has 30 ..." does split.
2. **Subject of a number, within its clause:**
   - If names come before the number, the subject is that researcher when the first name in the clause and the nearest name before the number are the same researcher. Otherwise the number is ambiguous.
   - If no name comes before the number, the subject is the clause's only researcher. If the clause names nobody, it is the claim's only researcher. Otherwise the number is ambiguous.
3. **Ambiguous numbers.**
   - Only items that are not per-researcher (edge, path, ranking, link and publication items) can support the number.
   - Per-researcher records never do: researcher-metrics METRIC_RECORD, community-membership COMMUNITY_RECORD and IDENTITY_RESOLUTION.
   - The fact becomes Unsupported, with the reason "... the subject is ambiguous (A / B in its clause), so no researcher's own record supports it". The claim becomes PARTIALLY_SUPPORTED with `[unverified]` and a label, and a model summary like this is replaced.
   - A value that a cited edge, path or ranking holds but that contradicts the claim is still CONFLICTING.
4. Claims that name no researcher, and the edge, path, ranking, link and publication items, behave as before.

The colon break was needed. My first run had 2 failures among the existing tests, VerificationSuite "ambiguous-name candidate claims ..." and WorkflowSuite "an ambiguous or unknown name abstains ...":
- Both fail on the deterministic candidate claim "'Wei Wang' is ambiguous: candidate Wei Wang 0002 (sc:r:v1:...) with identity confidence 0.97 and 2 source mentions."
- The quoted query name (Wei 1) and the candidate (Wei 2) sit before the number in one clause, so the claim came out PARTIALLY_SUPPORTED.
- Those tests check the right behavior: a deterministic identity claim must verify. So I fixed the clause rule by making a colon a hard break, and I did not change the tests.

COMMANDS RUN (output observed):
- `timeout 1500 sbt -batch rag/test`, run 1: "Failed: Total 133, Failed 2, Errors 0, Passed 131". The two failures are the ones above; both new D5 tests passed.
- `timeout 1500 sbt -batch rag/test`, run 2 after the colon break: "Passed: Total 133, Failed 0, Errors 0, Passed 133", "[success] Total time: 25 s".
- `timeout 300 scalafmt --test` on my 3 files: "All files are formatted with scalafmt :)".
- Read-only: `git status --short`, `grep` and `sed` reads of the rag sources, the reports and WorkflowRepairGateSuite.

TEST RESULTS:
- `sbt rag/test`: 133 passed, 0 failed.
  - VerificationSuite 11 (was 10), WorkflowSuite 13 (was 12).
  - The rest are unchanged: PlannerSuite 10, SynthesisSuite 6, PipelineSuite 11, StageSuite 9, LocalRuntimeSuite 3, BedrockLlmSuite 4, TemplateSuite 18, RetrievalSmokeSuite 10, SemanticContextSuite 12, ChunkingSuite 10, EmbeddingVersionSuite 8, IngestionConformanceSuite 8.
- New test VerificationSuite "repair D5: a number whose subject is ambiguous in its clause is attributed to nobody":
  - "Alice Arden, compared with Bob Brandt, has 30 ..." and "With 30 ..., Alice Arden leads Bob Brandt." citing both records: PARTIALLY_SUPPORTED, the reason says "ambiguous", and the exact weakened text has `[unverified]`.
  - These are never VERIFIED and always have an unsupported span:
    - the same two forms with Alice's own 41, which is conservative on purpose;
    - a conjunction inside a parenthetical;
    - ", and not Bob Brandt,";
    - "and B as a coauthor, with 30";
    - a later sentence that names nobody;
    - "A, compared with B: 30".
  - These stay VERIFIED:
    - ", while" and bare "and" with one name per clause;
    - `;` and `.` splits;
    - a number before the only name of its clause;
    - the first name repeated as the nearest name;
    - "41 ..., more than Bob Brandt".
  - Swapped values across ", while" are CONFLICTING.
  - A path "With 3 hops, Alice Arden reaches Dan Dorsey." and a ranking value with three names are VERIFIED, so non-per-researcher items still support ambiguous numbers.
- New test WorkflowSuite "repair D5: a model claim or summary whose number has an ambiguous subject is not VERIFIED", run end to end with a scripted model, for both summary variants:
  - The model reply is used, with no fallback.
  - claim-1 (subject first) and claim-2 (number first) are PARTIALLY_SUPPORTED, with the exact weakened text and an uncertainty label containing "ambiguous".
  - claim-3 is VERIFIED: "Alice Arden has 41 ... and Bob Brandt has 30 ... in window 2015-2026."
  - `summaryReplaced` holds the ambiguity reason, and the final summary has no "30 distinct".
- Earlier assertions: none changed. The three that bear on this are all still green as written:
  - VerificationSuite "Alice Arden has 41 ..., and Bob Brandt has 30 ..." is VERIFIED, because ", and" plus a name splits the clause.
  - The swapped ", and" claim is CONFLICTING.
  - WorkflowSuite M5 claim-2 "... 41 distinct coauthors and Bob Brandt has 30 ..." (no comma) is VERIFIED, because of the bare "and" plus name rule. I added that rule because the brief's split list alone would have weakened this correct claim.

VALIDATION RESULTS:
- Prompt and schema versions are unchanged: answer-synthesis-v1 prompt and schema, query-plan-v1.
- The `claim-verifier-v1` string is unchanged (see RISKS).
- Allowed plan operations (plan-operations-v1) and graph templates are unchanged.
- Verification rule changed: clause-scoped subject attribution for steps 7 and 8, as described above. An ambiguous subject gets no per-researcher support.
- Abstention behavior is unchanged.
- Benchmark: not run (adversarialTests is out of scope here).
- From reading the code, not run: for WorkflowRepairGateSuite "M5: a number after two names ...":
  - claim-2 "Hans Hagen, compared with Jan Mohring, has 5 ..." now comes out PARTIALLY_SUPPORTED with `[unverified]`, which `notVerified` accepts;
  - the control claim-1 (one name) stays VERIFIED.
  - In the other M5 gate test: claim-3 (bare "and" plus name) splits, so A's value conflicts; claim-4 (number before the only name) is checked against A's record and conflicts; the summary is still replaced.

ASSUMPTIONS:
1. The colon break and the conditional bare "and" break go beyond the brief's list. I added them to keep two kinds of correct claims VERIFIED: the deterministic candidate and identity claims (needed by existing tests), and "X has N and Y has M" (needed by an existing test).
   - Both breaks need conditions that keep "A, compared with B and C, has 5" in one clause.
2. I accept the conservative weakening of correct comparative prose, for example "Alice, like Bob, has 41". It is spec-compliant (weakened and labeled), as review-report-02 allows.

RISKS:
- Not done (per the work order):
  - m1, m2, m4 and m5;
  - window attribution;
  - swapped values inside one cited ranking;
  - unkeyed ranking numbers still support any named researcher;
  - the component version string bump (`claim-verifier-v1` is kept while the phase is uncommitted; the human decides).
  - No adversarialTests run and no full `sbt check`. The red D5 test, BenchmarkWorkflowSuite 68, WorkflowGateSuite 29 and BenchmarkQuestionSetSuite 40 need test-engineer's rerun, then one `sbt check` that reaches the test marker.
- Known residual failure modes (contrived):
  - A soft break can still hand a number to the wrong researcher. Example: "A has 12 publications, while B, unlike A, ..." splits after the number, and the second clause has its own first name and nearest name. That is a fresh clause, so it gets the same check as any clause; but a sentence like "A: unlike B, 30" credits B.
  - Pronoun subjects ("She has 12 ...") in a claim that names two researchers are weakened, not resolved.
  - Benchmark deterministic claims on real names were traced but not run. A real display name is masked before clause splitting, so a period or colon inside a name cannot split. A query string in single quotes that contains ":" could change clause scope in a candidate claim; it was not observed.
- State left behind: only the 3 files above changed, plus build output under modules/rag/target. No commit, and STATUS.md, docs, build.sbt and tests/adversarial are untouched.
- Notes for documentation-engineer (modules/rag/MODULE.md, docs/runtime-workflow.md):
  - No public API signature changed.
  - Replace the positional rule ("nearest researcher named before the number") with the clause rule:
    - split at `.`, `;` and `:` before whitespace, and at "and", "while" or "whereas" after a clause that states a number ("and" needs a researcher named right after it, with no comma before that researcher's number);
    - the subject is the single researcher before the number, or the first name when it is also the nearest; with no name before it, the clause's only researcher, else the claim's only one;
    - otherwise the number is ambiguous and no per-researcher record supports it, so the claim is PARTIALLY_SUPPORTED with `[unverified]`, and a model summary like this is replaced.
  - D5 is fixed in rag unit tests (133/133). Test-engineer's rerun is still pending.
  - Disclose the conservative weakening of correct comparative prose, and the residuals above.
  - repair-01-rag-engineer.md ASSUMPTIONS 1 ("never verifies a false one") was refuted by D5; this repair replaces that rule.

MANUAL ACTION REQUIRED: none from me. Model endpoint choice and per-query cost stay with the human before Bedrock is enabled (unchanged).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer reruns WorkflowRepairGateSuite, BenchmarkWorkflowSuite, WorkflowGateSuite and BenchmarkQuestionSetSuite, then one full `sbt check` at the default SBT_OPTS that must reach the test marker. Then a fresh code-reviewer re-check, then the Phase 09 human gate.
