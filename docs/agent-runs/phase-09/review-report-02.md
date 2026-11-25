# code-reviewer report (Phase 09 review 2, fresh re-check)

HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

**Verdict: REQUEST_CHANGES.** One MAJOR defect is still open: D5. The red test is FAIR, and D5 breaks the gate item "Unsupported claims are removed, weakened, or explicitly labeled". The work order allows no second repair round, so the human has to decide how to proceed. Apart from D5, M1, M2, M3, M4, M6 and M7 are fixed as their reports claim, and M5 is fixed for the cases it names. Ownership is clean, there is no control-plane, version, secret or Cypher regression, and the documentation is honest, with two MINOR wording issues.

Per the work order I ran no sbt. Every finding comes from reading the code. All test counts are taken from the reports.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Fresh Phase 09 re-check, the second and last review, read-only, no sbt. (1) Check that M1 to M7 are fixed and that nothing broke or went out of scope. (2) Give a fairness verdict and a severity for D5, plus a repair brief for the human. (3) Check the honesty of runtime-workflow.md, MODULE.md and the STATUS.md Phase 08 and 09 sections. (4) Say which gate items are met, give the overall verdict, and list what the human must decide.
FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (output observed):
- `git status --short`, `git diff --stat`, `git diff -- build.sbt`
- `git diff --stat HEAD` over the control plane, `project/`, core, storage, spark, ER, hadoop, datasets, infra and scripts. It was empty: no change.
- `git log --oneline -3`
- `ls -l` mtimes of every Phase 09 file. Only the files the repair reports name changed after review 1 (17:47). The earlier test-engineer suites, the prompt and the schemas are unchanged.
- `wc -l` on the changed files.
- grep for secrets, reasoning storage and raw Cypher in the repaired files: no hit.
- grep of `.windows` uses in rag; grep of the Phase 08 review verdict and sbt-check output; `cat .agents/models.json`.

TEST RESULTS: none run (no sbt, per the work order). I relied on the observed results in `repair-01-rag-engineer.md` (rag/test 131/131) and `repair-01-test-engineer.md`. Those show: BenchmarkQuestionSetSuite 40/40, BenchmarkWorkflowSuite 68/68, WorkflowGateSuite 29/29 and WorkflowRepairGateSuite 5/6. The full `sbt check` at the default SBT_OPTS exited 1 in 771 s, with adversarialTests at 1 failed of 211, and the test marker was not reached.

VALIDATION RESULTS:

**Verdict: REQUEST_CHANGES**

## 1. Repair verification (M1 to M7)

| Finding | State | Evidence (re-read) |
|---|---|---|
| **M1**, owner rag-engineer | FIXED | Plan.scala: `QName` at 353, `DirectPair` at 368, `CollaborationBetween` at 370-371, and `DirectWords` with the two-name fallback at 223-225 and 378-379. Evidence.scala:297-312: PUBLICATION items are fetched for the pair edge's `supportingWorkIds`, through the allowlisted `node` template with stored IDs. Synthesis.scala:310-333: the pair-publication claims cite the edge and those works, within MaxClaimText and MaxEvidencePerClaim. Tests: PlannerSuite:82 and WorkflowSuite:323 (including an ambiguous endpoint with no pair query). bq-07/08/09/31/32 now pass D and A (observed by test-engineer). |
| **M2**, owner rag-engineer | FIXED | Plan.scala:384: the `TopicIn` lookahead now stops before `in\|during [the] [years\|window\|period] YYYY`. Plan.scala:507-512: `topicLike` was rewritten. Test: PlannerSuite:112. P is 33/33. The rewrite causes a small regression, MINOR m4 below. |
| **M3**, owner rag-engineer | FIXED | Synthesis.scala:382 says "touching N communities, its own included", with no N-1 computed. Verification.scala:126-136 maps that phrase to `communitiesConnected`. "N other communities" maps to `otherCommunityCount`, which no item holds, so the claim is weakened. Tests: VerificationSuite:308 and SynthesisSuite:211. bq-21 A passes. |
| **M4**, owner rag-engineer | FIXED | Workflow.scala:178 sets `abstained` for any cause, and 192-193 gives no rows when abstained and otherwise only rows with `claimIds`. Test: WorkflowSuite:383. The F4 gate test passes. |
| **M5**, owner rag-engineer | PARTIAL | Verification.scala:196-202 (`perResearcher`) and 399-412 (`subjectAt` and `backing`) work for "B's value stated for A", swapped values, and a number before a single name. Tests: VerificationSuite:262, WorkflowSuite:419, and WorkflowRepairGateSuite test 1, which is green. The attribution is positional, so D5 remains open (finding D5 below). |
| **M6**, owner rag-engineer | FIXED | LocalRuntime.scala:175-179 builds the RuntimeContext from a config limited to the loaded windows. All window consumers read `cfg.graph.windows` from that context: Plan.scala:180, Agents.scala:23, 181 and 551, and Evidence.scala:179. Tests: LocalRuntimeSuite:210 and three WorkflowRepairGateSuite M6 tests, all green. |
| **M7**, owner build-engineer | FIXED | build.sbt:355-364 adds `Test / fork := true` and `-Xmx2560m -Xss4m` on `adversarialTests` only, with a comment. No version changed. `sbt check` at the default SBT_OPTS ran with no OOM: heap 934 MB of 2560 MB, format checks passed. |

Scope, territory and safety:
- Every changed file is inside its author's territory:
  - rag-engineer: modules/rag, tests/module-unit/rag, prompts;
  - build-engineer: build.sbt;
  - test-engineer: tests/adversarial and tests/benchmark;
  - documentation-engineer: STATUS.md, docs/runtime-workflow.md and MODULE.md;
  - orchestrator: docs/agent-runs/phase-09.
- No control-plane, `project/` or other module change, no `human-gate.md` written by an agent, and no secret.
- User text still never becomes Cypher or AWS. The new planner patterns only produce validated lookup names and operations.
- Untrusted-text handling and the no-chain-of-thought controls sit in files that did not change after review 1 (Llm.scala, Agents.scala, TemplateGraph.scala and the prompt and schemas). Workflow and Synthesis still store no reasoning.
- No repair added optional scope.

## 2. Findings

### MAJOR

**D5. Verifier misattribution by position (residual of M5).** Owner: rag-engineer.
- Where: Verification.scala:404-407 (`subjectAt`: the last name hit before the number, or every named researcher when no name comes before it). It is used by steps 7 and 8 (414-459) and by the summary check (Workflow.scala:249-251).
- Failure input, observed by test-engineer and confirmed by my code trace:
  - The claim is "Hans Hagen, compared with Jan Mohring, has 5 distinct coauthors in window 2010-2014.", citing both metric records (bq-v1-08). 5 is Jan Mohring's value.
  - The nearest name before "5" is Jan Mohring, so only Jan's per-researcher record backs the number, `have` = {5}, and the result is VERIFIED.
- Untested variant from the same cause: a number before two names, for example "With 5 distinct coauthors in window 2010-2014, Hans Hagen leads Jan Mohring." No name comes before the number, so both records are accepted and it is VERIFIED. That is the two-name form of the test's claim-4.
- Impact:
  - A false numerical claim reaches the user marked VERIFIED, with no label.
  - A model summary in the same form is kept, not replaced.
  - This breaks the gate item "Unsupported claims are removed, weakened, or explicitly labeled" and spec line 1545 ("Check every ... coauthor count ... and numerical value against the bundle").
  - "Every numerical claim has evidence IDs" is met only literally: the IDs are there, but they do not support the number.
- Why MAJOR and not CRITICAL:
  - Only the model synthesis path is affected. The deterministic synthesizer cites one per-researcher record per claim (benchmark A 33/33).
  - The model path is off by default (`models.llmEnabled` false, per runtime-workflow.md:48; I did not check this in the config).
  - There is no data loss, no security exposure and no fence change.
- Why not MINOR: comparative prose like "X, like Y, has N" is an ordinary LLM phrasing, and injected text can steer the model toward it. It is the same class of defect as review 1's M5.

### MINOR (carried forward; none of them breaks a gate item)

- **m1, m2 (from review 1, still open), owner rag-engineer.**
  - m1: Agents.scala:313-317, the token-order homonym gap.
  - m2: Verification.scala:304, the name check matches a name as a substring of a cited value (the code moved from line 285).
- **m4 (new, a regression from the M2 rewrite), owner rag-engineer.**
  - Where: Plan.scala:507-512, with NotTopics at 418-452, which holds "graph" but not "graphs".
  - Cause: the old rule refused any phrase that contained a NotTopics word. The new rule refuses a phrase only when its last word is one, every word is one, or it starts with a determiner.
  - Failure input: "Which researchers have the most distinct coauthors in collaboration graphs?" now gives the topic "collaboration graphs" and TOPIC_FILTER. The filter matches no Topic node, so the answer is an empty set and abstains (Evidence.scala:357-361).
  - Before the repair, the phrase was dropped because "collaboration" is a NotTopics word.
  - It fails safe (an abstention with a filter note), not as a wrong answer. Found by code trace, not run.
- **m5 (docs wording), owner documentation-engineer.**
  - STATUS.md:68 says "Repair round 1 fixed F1 to F4 and M1 to M7". STATUS.md:111 and modules/rag/MODULE.md:398 say "M1 to M6 are fixed".
  - M5 is partial. The very next lines (STATUS.md:70 and 122; MODULE.md:417 and 427) do qualify it, so this is wording, not concealment.
- **Note, no owner action.**
  - repair-01-rag-engineer.md ASSUMPTIONS 1 says positional attribution "never verifies a false one", and D5 refutes that.
  - The report is historical evidence and the docs do not repeat the claim. runtime-workflow.md:156-159 discloses D5 right after the related sentence.
- Residuals already disclosed and carried forward (not new findings):
  - windows are not attributed to a researcher;
  - swapped values inside one cited ranking are accepted;
  - unkeyed ranking numbers support any named researcher;
  - component version strings were not bumped;
  - community size is missing.

## 3. Fairness verdict on the red test

The test is `WorkflowRepairGateSuite`, "M5: a number after two names is checked against the grammatical subject, not the nearest name" (WorkflowRepairGateSuite.scala:221-243).

**FAIR.**
- What it checks: a sentence whose subject is A, with B named in a parenthetical before the number, states B's value for A and cites both records. The claim must not come out VERIFIED.
- What counts as passing (`notVerified`, lines 157-173): removal as CONFLICTING or UNSUPPORTED, or keeping it as PARTIALLY_SUPPORTED with `[unverified]` or an uncertainty label. That is exactly what spec 1571 allows ("removed, weakened, or explicitly labeled").
- Spec 1545 requires every coauthor count and numerical value to be checked against the bundle, and the bundle contradicts this one.
- The control claim-1 (B's own value for B, citing both records) must be VERIFIED. That is correct per the spec and passes today.
- The test does not demand grammatical parsing despite its name. A conservative "ambiguous subject, do not verify" fix satisfies it.
- It is a black-box test through the public API with a scripted model reply, and the pair is chosen at run time from oracle-backed questions, so it does not depend on implementation details.

### Repair brief for the human (no repair round is authorized; owner rag-engineer if one is)

- Likely cause: `subjectAt` (Verification.scala:404-407) picks one name by position, and when no name comes before the number it accepts every named researcher. When attribution is ambiguous, the number should be refused, not credited to someone.
- A sound fix, the smallest that keeps the existing tests green:
  1. Split the claim into clauses at `.`, `;` and ", and"/", while"/", whereas".
  2. Within a clause, accept a per-researcher value only when the number's subject is unambiguous. "Unambiguous" means exactly one named researcher in the clause before the number, or several where the first name in the clause and the nearest name before the number are the same researcher.
  3. When no name comes before the number and the clause names more than one researcher, per-researcher records do not support it.
  4. In every ambiguous case the fact is Unsupported, so the claim is weakened and labeled.
  5. Edge, path, ranking, link and publication items keep their current behavior.
- Simpler alternative (test-engineer's suggestion): do not verify a number when more than one named researcher comes before it. This would weaken the correct claim "Alice Arden has 41 ..., and Bob Brandt has 30 ..." that VerificationSuite:280-288 expects as VERIFIED. That is the owner's own unit test. Weakening a correct claim is spec-compliant, but the owner would have to change that assertion and say why.
- What else could break:
  - two-name deterministic claims, though all of them cite non-per-researcher items (edges, paths, links), so they should be unaffected;
  - the summary check: a deterministic summary that states two researchers' numbers in one clause would now be replaced, so check any gate test that asserts `summaryReplaced` is None for deterministic runs;
  - the model-path tests in WorkflowGateSuite (injection and unsupported-claim cases).
- Must stay green:
  - all 131 rag unit tests, especially VerificationSuite (repair M5 and M3) and WorkflowSuite (repair M1, M4 and M5);
  - all 6 WorkflowRepairGateSuite tests, including the green M5 test 1 with its control claim-1 VERIFIED;
  - WorkflowGateSuite 29/29, BenchmarkWorkflowSuite 68/68 and BenchmarkQuestionSetSuite 40/40;
  - then one full `sbt check` that reaches the test marker.
- Add a unit test for the "number before two names" variant above.

## 4. Docs honesty

- docs/runtime-workflow.md: every figure matches the reports. That covers the before and after benchmark scores, the per-suite counts, the 771 s and exit-1 check, the M7 fork, the load times and heap use, and the D5 text. The planner, evidence, verification and abstention changes match the code I read. Line 10 ("re-check has not run yet") will be stale once this report is filed, which is expected.
- modules/rag/MODULE.md: accurate apart from the m5 wording at 398. D5, m1, m2 and the residuals are listed under Not done.
- STATUS.md Phase 08 section (46-64): matches `git log` (12829d7, a093a92), the Phase 08 review verdict (0 CRITICAL, 0 MAJOR, so no review 2) and `output-sbt-check-r1.txt` (356 s, marker touched). Review 1's m3 is fixed.
- STATUS.md Phase 09 section (65-190): accurate apart from the m5 wording at 68 and 111. The task table, state, carried-forward list and resume command are consistent with run-log.md and the reports.

## 5. Phase 09 gate

| Gate item | Met? | Basis |
|---|---|---|
| User text cannot become arbitrary database code | MET | allowlisted templates only; the repair added only validated names and operations; gate tests 29/29 |
| Ambiguous names do not silently resolve | MET | resolver unchanged; WorkflowSuite repair M1 (ambiguous endpoint runs no pair query); gate tests |
| Every numerical and path claim has evidence IDs | MET (literally) | gate test and the new suite's evidence-ID check pass; under D5 the cited IDs do not support the number |
| Unsupported claims removed, weakened or labeled | NOT MET | D5: a false number is VERIFIED and unlabeled on the model path and in the summary |
| Prompt injection tests pass | MET | WorkflowGateSuite 29/29, including the injection tests |
| Deterministic and semantic retrieval evaluated separately | MET | D 33/33 and S 6/33 hit, 7/80 precision, scored separately |
| Code review returns APPROVE | NOT MET | this verdict is REQUEST_CHANGES |
| (Work order) Run sbt check | NOT GREEN | exit 1 on the D5 test; marker not touched |

ASSUMPTIONS:
- The fairness verdict and the D5 severity are judged against docs/spec/assignment.md lines 1543-1546 and 1566-1574 only.
- `models.llmEnabled` being false by default is taken from runtime-workflow.md:48. I did not check it in the config.
- m4 and the D5 "number before two names" variant are code traces, not observed runs.
- The test counts are the reports' observed results. I reproduced none of them.

RISKS (what I did NOT review):
- I did not read these line by line: Synthesis.scala outside 285-414; Agents.scala, TemplateGraph.scala, Llm.scala, Directory.scala (all unchanged since review 1); the bodies of the rag unit tests other than VerificationSuite:262-322 and the test names; WorkflowRepairGateSuite:342-381; BenchmarkWorkflowSuite, WorkflowGateSuite, BenchmarkStore and BenchmarkRuntime, and the oracle `questions_v1.py` (all unchanged since review 1).
- Not run: any sbt; scalafmt on build.sbt (test-engineer's `sbt check` reports the format checks passed).
- test-engineer's M6 checks for the case where the answer comes from 2010-2014 are conditional, so whether they actually ran is not known (disclosed in its report).
- State left behind: none; I wrote nothing.

MANUAL ACTION REQUIRED (human decisions):
1. D5 (MAJOR, FAIR red test), with no repair round left. Choose one:
   - (a) authorize an exceptional second repair round for D5 alone (rag-engineer, brief above), then one `sbt check` and a fresh re-check;
   - (b) accept D5 as carried forward. The model path is off until Bedrock is enabled, but `sbt check` stays red, which conflicts with the definition of done (AGENTS.md hard rule 2), and the gate item "Code review returns APPROVE" stays unmet;
   - (c) another disposition you choose. Agents may not bend or skip the test on their own.
2. Accept or route MINOR m1, m2, m4 (rag-engineer) and m5 (documentation-engineer), plus the disclosed residuals: window attribution, values inside a ranking, and unbumped component version strings (bump or keep v1).
3. Review benchmark question set v1 (sha256 8584d275...) and `semantic-relevance-rule-v1`.
4. Decide on community size, which is carried forward from run-log step 1.
5. Decide the Bedrock model ID and the per-query cost before the model path is enabled. That is also when D5 becomes live.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human gate for Phase 09 (`docs/agent-runs/phase-09/human-gate.md`, written by the human), deciding D5 per item 1 above. The relevant files are:
- modules/rag/src/main/scala/scholarchain/rag/Verification.scala (lines 399-459)
- tests/adversarial/scholarchain/adversarial/rag/WorkflowRepairGateSuite.scala (lines 221-243)
- modules/rag/src/main/scala/scholarchain/rag/Plan.scala (lines 507-512)
- STATUS.md (lines 68 and 111)
- modules/rag/MODULE.md (line 398)
