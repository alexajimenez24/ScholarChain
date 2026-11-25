# code-reviewer report (Phase 09 review 3, D5 and regressions only)

HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

**Verdict: REQUEST_CHANGES.** The two D5 forms from review 2 are fixed, and so is the model summary in those forms. But the defect class is still open. I ran the compiled verifier against the RagWorld fixture, and seven ordinary phrasings still give a false per-researcher number VERIFIED, with no label. In the summary they keep the false summary. Examples are "Alice Arden (vs. Bob Brandt) has 30 ..." and "Alice Arden leads. Unlike Bob Brandt, she has 30 ...". Everything else in repair round 2 is clean: no regression in deterministic claims, ownership is in territory, no secrets, no stored reasoning, no scope creep. The docs match the reports, apart from one statement that the probes refute.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 09 fresh re-check 3, D5 and regressions only, read-only, no sbt. (1) Verify the D5 fix in Verification.scala and the summary path, and hunt for new false-VERIFY and wrong-weakening paths. (2) Regression check of the repair-2 changes: tests, territory, secrets, scope, no chain-of-thought. (3) Docs honesty for runtime-workflow.md, MODULE.md and STATUS.md. (4) Verdict, gate status, what remains for the human.
FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (output observed):
- `git status --short`, `git diff --stat`, `git diff -- build.sbt`. Only M7's fork settings; no version moved.
- `find . -newer docs/agent-runs/phase-09/review-report-02.md` (target/ excluded). Only these changed after review 2:
  - Verification.scala, VerificationSuite.scala, WorkflowSuite.scala
  - WorkflowRepairD5GateSuite.scala
  - docs/runtime-workflow.md, modules/rag/MODULE.md, STATUS.md
  - the phase-09 run files and the test marker
- `grep` for secret, password, AKIA, api key, reasoning, chain-of-thought and thinking in Verification.scala, VerificationSuite.scala and WorkflowRepairD5GateSuite.scala: no hits (exit 1).
- `grep llmEnabled`: scholarchain-v1.yaml:94 has `"${LLM_ENABLED:-false}"`, so the model path is off by default (confirmed this time).
- `javap` on `RagWorld$`, and an `ls` showing the compiled `ClaimVerifier$.class` (22:04) is newer than its source (22:03:40).
- `jshell` (JDK 17) with the exported test classpath of `modules/rag/target`. This is not sbt and writes nothing to the repo. It called `ClaimVerifier.verifyText` and `DeterministicSynthesizer.draft` on the RagWorld fixture (Alice 41 and Bob 30 distinct coauthors, citing the metric records of Alice, Bob and Carol), in 3 runs. The results are in the findings below.
- My first two jshell attempts failed: one on a Java keyword, and in the other my grep filter hid the output. A `stat` that named the marker path was blocked by guard-dangerous, as designed. None of those are counted as evidence.

TEST RESULTS: no sbt run, per the work order. From `repair-02-test-engineer.md` (observed by test-engineer):
- `sbt check` at the default SBT_OPTS: exit 0 in 734 s, 14 project test runs, marker touched.
- rag 133/133, adversarialTests 213/213.
- WorkflowRepairGateSuite 6/6, WorkflowRepairD5GateSuite 2/2, WorkflowGateSuite 29/29, BenchmarkWorkflowSuite 68/68, BenchmarkQuestionSetSuite 40/40.
- My `find -newer` shows the marker was updated after review 2, which fits the report.

My jshell probes, observed:
- Weakened as intended: "Alice Arden, compared with Bob Brandt, has 30 ..." gives PARTIALLY_SUPPORTED with the reason "ambiguous". In the summary path it leaves 1 unsupported span, so the summary is replaced.
- Every deterministic claim and summary is VERIFIED, with no unsupported span, for four questions:
  - ambiguous Wei Wang, giving both candidate claims;
  - the Alice/Bob pair;
  - the ranking plus bridges question, including the "1. Carol Chen (52), 2. ..." ranking claims, the bridge-colon claims and the cross-community-link claims;
  - the team-size question, including the "; the average is ..." claims.

VALIDATION RESULTS:

**Verdict: REQUEST_CHANGES**

## Findings

### MAJOR

**R3-1. The D5 class is still reachable: a false per-researcher number is VERIFIED through abbreviation periods and pronoun subjects.**
- Owner: rag-engineer.
- Where, in modules/rag/src/main/scala/scholarchain/rag/Verification.scala:
  - line 101: `HardBreak = "[.;:](?=\\s|$)"` treats any period before a space as a clause end;
  - lines 436-449: `subjectAt` gives the only name before the number in a clause as the subject;
  - the summary uses the same code (Workflow.scala:249-251).
- Failure inputs, each citing the metric records of Alice, Bob and Carol, all observed VERIFIED with no reasons (30 is Bob's value; Alice has 41):
  1. "Alice Arden (vs. Bob Brandt) has 30 distinct coauthors in window 2015-2026."
  2. "Alice Arden, compared with Prof. Bob Brandt, has 30 distinct coauthors in window 2015-2026." (This is the exact D5 form plus an honorific.)
  3. "Alice Arden, like Dr. Bob Brandt, has 30 distinct coauthors."
  4. "Alice Arden leads. Unlike Bob Brandt, she has 30 distinct coauthors in window 2015-2026."
  5. "Alice Arden leads; compared with Bob Brandt, she has 30 distinct coauthors."
  6. "Alice Arden stands out: compared with Bob Brandt, she has 30 distinct coauthors." The colon break was added in repair 2. The brief's rule alone would have called this ambiguous, but round 1 also VERIFIED it, so it is a residual rather than a regression.
  7. "Alice Arden has 12 publications, while compared with Bob Brandt she has 30 distinct coauthors."
- Summary path, over all bundle IDs: input 4 (with "Compared with Bob Brandt, she has 30") and input 1 are VERIFIED with 0 unsupported spans, so the false model summary is kept.
- Cause:
  - The clause split runs before the attribution check. So the clause after "vs.", "Prof.", "Dr.", a sentence end, a semicolon, a colon or "while" holds only the other researcher's name.
  - That name is then taken as the subject, even though the grammatical subject is the earlier researcher (by position, or through the pronoun).
  - This is a gap in review 2's own brief, which defined "unambiguous" as one named researcher before the number in the clause. The implementation follows that brief faithfully.
- Impact:
  - It is the same as D5. A false number reaches the user marked VERIFIED, or a false summary is kept.
  - The gate item "Unsupported claims are removed, weakened, or explicitly labeled" and spec stage 9 ("check every ... coauthor count ... and numerical value") stay unmet on the model path.
  - The prompt has no rule against pronouns or abbreviations, the summary is up to 1500 characters of prose, and injected text can steer the model toward these forms.
- Why MAJOR and not CRITICAL: the same reasoning as review 2. Only the model path is affected, and it is off by default (`LLM_ENABLED:-false`). Deterministic synthesis is unaffected (probes above, and benchmark A 33/33). There is no data loss, security exposure or fence change.
- The docs say pronouns are "weakened, not resolved". That is wrong when the pronoun's clause names another researcher (see R3-4).

### MINOR

**R3-2. The first-name-equals-nearest-name rule credits the wrong researcher.** Owner: rag-engineer.
- Where: Verification.scala:442.
- Failure input, observed VERIFIED: "Alice Arden collaborates with Bob Brandt, who, like Alice Arden, has 41 distinct coauthors." 41 is Alice's value; the subject is Bob, who has 30.
- Contrived, so MINOR. It can be fixed together with R3-1: require that no other researcher sits between the first and the nearest mention.

**R3-3. A true pronoun claim is marked CONFLICTING and removed.** Owner: rag-engineer.
- Observed: "Alice Arden leads. Unlike Bob Brandt, she has 41 distinct coauthors in window 2015-2026." comes out CONFLICTING ("distinctCoauthorCount of Bob Brandt: claimed 41, evidence 30").
- The claim is true and is removed, and the answer's overall status becomes CONFLICTING.
- It fails safe, but the status misleads the reader. A correct R3-1 fix (an ambiguous subject means unsupported) would turn this into a labeled weakening instead.

**R3-4. One residual statement in the docs is wrong, and one is missing.** Owner: documentation-engineer, after the rag fix.
- These say a pronoun subject "is weakened, not resolved":
  - docs/runtime-workflow.md:306-307;
  - modules/rag/MODULE.md:454-455;
  - STATUS.md:163 ("pronoun subjects").
  That is true only when the clause names no researcher. Inputs 4 to 7 above show such claims VERIFIED.
- The statement comes from repair-02-rag-engineer.md RISKS, so the documentation-engineer copied it faithfully. The rag-engineer report is the source of the error.
- Abbreviation periods ("vs.", "Dr.", "Prof.") are not disclosed. runtime-workflow.md:155-157 says only that periods inside names, titles, decimals and identifiers never split, which is accurate as far as it goes.
- runtime-workflow.md:13 and STATUS.md say "repair round 2 fixed D5". That holds for the named forms, not for the defect class.
- runtime-workflow.md:15 ("re-check has not run yet") goes stale once this report is filed; that is expected.

## Probed and found sound (no finding)
- Quoted query strings with ":" or ";" in candidate and identity claims:
  - the candidate's numbers always follow "is ambiguous:", so extra breaks inside the query come before them and do not matter;
  - observed: "'Wei Wang: Tsinghua; and Alice Arden' is ambiguous: candidate ..." comes out PARTIALLY_SUPPORTED, but only because of the step 9 unknown-name check on the trailing quote, not because of the clause logic;
  - the real candidate claims are VERIFIED.
- The bare "and" rule:
  - "A has 41 ... and B has 30 ..." is VERIFIED, and the swapped version is CONFLICTING;
  - "and B, her coauthor, has 30" does not split.
- "Alice Arden has 41 ... vs. Bob Brandt with 30 ..." is VERIFIED (correct), and the swapped version is CONFLICTING.
- "A has more coauthors than B, who has 30" is weakened, which is conservative and correct.
- Decimals ("0.97", "3.6666666666666665") and ID colons never split.
- Ranking list markers "1. ", "2. " split clauses, but the cited ranking item is not a per-researcher record, so it still backs every value.
- "A: unlike B, 30" credits B, as already disclosed. It is part of R3-1's family.

## Regression check (task 2)
- Territory: every file changed after review 2 is inside its owner's territory:
  - rag-engineer: Verification.scala and the two unit suites in tests/module-unit/rag;
  - test-engineer: the new WorkflowRepairD5GateSuite;
  - documentation-engineer: the three notes;
  - orchestrator: docs/agent-runs/phase-09.
  There is no change to the control plane, project/, build.sbt (unchanged since M7), prompts, schemas or another module.
- Workflow.scala is unchanged, as the report says.
- WorkflowRepairGateSuite was not modified: it is older than review 2 and absent from the `find -newer` list.
- Test assertions:
  - VerificationSuite and WorkflowSuite are untracked, so I cannot diff them against their round-1 state.
  - I re-read every assertion review 2 relied on, and each is present with the same expectation:
    - VerificationSuite: repair M5 at 262-306 (including "Alice ... 41 ..., and Bob ... 30" VERIFIED at 280-288), repair M3 at 394-415, and the candidate test at 242-260;
    - WorkflowSuite: repair M5 at 419-445 (claim-1 CONFLICTING, claim-2 VERIFIED, summary replaced).
  - The new D5 tests are additive and assert exact weakened text.
- WorkflowRepairD5GateSuite:
  - it uses only the public API;
  - its control claim also guards the name-to-ID alignment;
  - `notVerified` accepts PARTIALLY_SUPPORTED with any label, as the existing suite does, which is spec-compliant;
  - FAIR as written.
- No secrets. No reasoning is stored: the only new text is reason strings with researcher names and the claimed number. Prompt, schema and plan versions are unchanged.
- No scope creep. `claim-verifier-v1` was not bumped, which is disclosed and is the human's decision.

## Docs honesty (task 3)
- runtime-workflow.md, MODULE.md and STATUS.md match repair-02-rag-engineer.md, repair-02-test-engineer.md and run-log.md on every figure:
  - 133, 40, 68, 29, 6, 2 and 213;
  - 734 s, exit 0, marker touched;
  - per-project totals 120, 184 (1 ignored), 133, 213, 119, 45 and 98;
  - the human-authorized exception.
- m5 wording is fixed: "M1 to M7 fixed, M5 partial".
- The exceptions are listed in R3-4.

## Phase 09 gate
| Gate item | Met? | Basis |
|---|---|---|
| User text cannot become arbitrary database code | MET | unchanged in round 2; templates and plan unchanged |
| Ambiguous names do not silently resolve | MET | unchanged; candidate claims VERIFIED (probe) |
| Every numerical and path claim has evidence IDs | MET (literally) | under R3-1 the cited IDs do not support the number |
| Unsupported claims removed, weakened or labeled | NOT MET | R3-1: inputs 1 to 7 VERIFIED; a false summary is kept |
| Prompt injection tests pass | MET | WorkflowGateSuite 29/29 (per report) |
| Deterministic and semantic retrieval evaluated separately | MET | D 33/33; S 6/33, 7/80 |
| sbt check green | MET | exit 0, 734 s, marker touched (repair-02-test-engineer.md) |
| Code review returns APPROVE | NOT MET | this verdict |

## Repair brief for R3-1 (owner rag-engineer, if the human authorizes a round)
- Likely cause: the clause boundaries and single-name attribution ignore lead-ins and pronouns.
- A conservative fix, only when the claim names two or more distinct researchers:
  - (a) Do not hard-break at a period whose preceding token is a short abbreviation, for example vs, Dr, Prof, Mr, Ms, Mrs, al, cf, approx, e.g., i.e., resp, or any token of 1 to 4 letters.
  - (b) A single name before the number makes it the subject only when that name starts its clause. Allowed before it: whitespace, punctuation, or the soft-break word. The numbers-first form ("With N ..., X ...") keeps its current rule.
  - (c) A pronoun (he, she, they, his, her, their, its, him, them) in the clause before the number makes the number ambiguous.
  - (d) The first-equals-nearest rule applies only when no other researcher is named between those two mentions (R3-2).
  - In every case an ambiguous number becomes Unsupported, never Conflicting (R3-3).
- What else could break:
  - ranking claims "1. X (52), 2. Y (41)": with (a), "2." no longer splits, but the ranking item still backs the values;
  - the positives at VerificationSuite:350-361, in particular "Alice Arden has 41 ... . With 30 distinct coauthors, Bob Brandt follows." and "... more than Bob Brandt.";
  - candidate claims, which start with a quoted query, so the name is not at the start of the clause before the colon; the candidate itself follows "candidate", so (b) must allow that, or apply (b) only before the number in clauses with two or more researchers named in the claim.
- Must stay green:
  - rag 133, including the deterministic all-claims test, the candidate tests, and M5, M3 and D5;
  - WorkflowRepairGateSuite 6, WorkflowRepairD5GateSuite 2, WorkflowGateSuite 29, BenchmarkWorkflowSuite 68 (A 33/33), BenchmarkQuestionSetSuite 40;
  - then one `sbt check` that reaches the marker.
- Add unit tests for inputs 1 to 7, R3-2 and R3-3.

ASSUMPTIONS:
- The probes ran the compiled classes in modules/rag/target, built at 22:04, after the last Verification.scala edit at 22:03:40. I took them to be the code that test-engineer's check exercised.
- I judged severity against spec stage 9 and the gate item "removed, weakened, or explicitly labeled", with the same reasoning review 2 used for D5.
- I took running jshell (not sbt) on existing build output to be within "read-only; run no sbt". It writes nothing in the repository.

RISKS (not reviewed or not done):
- I did not re-run any sbt suite. All suite counts come from the reports.
- I could not diff VerificationSuite and WorkflowSuite byte for byte against their round-1 state, because they are untracked. My no-weakening statement rests on re-reading the assertions review 2 cited.
- Not reviewed, out of scope: Plan.scala, Evidence.scala, Agents.scala, Synthesis.scala beyond the claim templates at 205-434, Llm.scala, TemplateGraph.scala, the bodies of WorkflowGateSuite and BenchmarkWorkflowSuite, and the carried-forward m1, m2 and m4.
- I probed only on the RagWorld fixture, not on the 25k benchmark run.
- jshell may have written Java preferences under ~/.java, outside the repo. Nothing in the repository was written.

MANUAL ACTION REQUIRED (human decisions):
1. R3-1 (MAJOR). No repair round is left under the one-round rule plus the one exception. Choose:
   - (a) authorize another D5-class round (rag-engineer, brief above; then documentation-engineer for R3-4, test-engineer rerun and one `sbt check`, then a fresh re-check);
   - (b) accept R3-1 to R3-3 as disclosed residuals while the model path is off (`LLM_ENABLED` defaults to false), with a condition that they are closed before Bedrock is enabled. "Code review returns APPROVE" then stays unmet, and that is your call;
   - (c) another disposition.
2. The items carried forward unchanged: m1, m2, m4, community size, the Bedrock model ID and per-query cost, the review of question set v1 and semantic-relevance-rule-v1, and the `claim-verifier-v1` version string.
3. Phase 09 commit and ratification, after your decision.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Phase 09 human gate (human-written gate file in docs/agent-runs/phase-09/), deciding R3-1 under item 1. Relevant files:
- modules/rag/src/main/scala/scholarchain/rag/Verification.scala (lines 101, 413-458)
- modules/rag/src/main/scala/scholarchain/rag/Workflow.scala (lines 249-251)
- docs/runtime-workflow.md (lines 13, 155-157, 306-307)
- modules/rag/MODULE.md (lines 454-455)
- STATUS.md (line 163)
