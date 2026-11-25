# code-reviewer report (Phase 09 review 1)

HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

All 13 red tests are FAIR against the spec. I also found two new MAJOR defects the tests do not catch, and the heap problem is a build-engineer item that belongs in this repair round, not later. So the verdict is REQUEST_CHANGES. Every finding was re-checked against the files by reading them; per the work order I ran no sbt, so none was reproduced by running a test.

Ownership is clean: every changed or new file is inside its author's territory, and there is no control-plane change, no version change and no secret.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Fresh Phase 09 review of the whole uncommitted diff (rag, prompts, tests/module-unit/rag, tests/adversarial, tests/benchmark, docs/runtime-workflow.md, modules/rag/MODULE.md, STATUS.md) on all axes. Fairness verdict on the 13 red test-engineer tests (F1 to F4) with repair briefs. A verdict on the adversarialTests heap item. Read-only, no sbt.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `git status --short`, `git status --porcelain --untracked-files=all`, `git diff --stat`, `git diff -- STATUS.md`, `git diff -- modules/rag/MODULE.md`, `git ls-files`
- `wc -l` and `du -k` on the changed files
- `sha256sum` on the prompt, the two schemas, questions-v1.json and questions_v1.py: all match the reports; query-plan-v1.json is c4d246eae19d42bc282c8fe8bf8f8184e59088171a04582dab49a0922f01cde0
- `python3 -I` read-only print of selected questions from questions-v1.json
- grep and cat of `.sbtopts`/`.jvmopts` (none exist), build.sbt fork and heap lines, the config windows, `Normalize.personName`, the spark `communitiesConnected` definition, storage `InMemoryGraphStore.executed`, and a secret-pattern grep over the untracked files (no hits)
TEST RESULTS: none run (the work order forbids sbt). I relied on the observed counts in test-report-02.md and output-benchmark.md.

VALIDATION RESULTS:

Verdict: REQUEST_CHANGES

**MAJOR findings**

M1 (F1). Owner rag-engineer.
- Where: modules/rag/src/main/scala/scholarchain/rag/Plan.scala:349, 364-369, 221-227; Evidence.scala:297-307; Synthesis.scala:304-315.
- Cause: `NameText` must start with a letter, so `DirectPair`, `CoauthorsOf` and `CollaboratedWith` never match a quoted name. `wantsCoauthors` has no fallback like the one `wantsPath` has at line 221 (`has(PathWords) && names.size >= 2`). Nothing recognises "collaboration between X and Y".
- Failure input: `Has "Shaojun Wei" collaborated with "Leibo Liu" in 2010-2014, ...` is planned as SEMANTIC_EXPLANATION only. No coauthor-pair query runs, and the answer is VERIFIED without the joint count (2).
- Second gap: publications are fetched only for team works and the first work of each path hop. The supporting works of a pair edge are never fetched, so bq-v1-31/32 lack PUBLICATION items even once planned.
- The unit tests missed it because PlannerSuite:56 only uses unquoted names.

M2 (F3). Owner rag-engineer.
- Where: Plan.scala:374.
- Cause: the `TopicIn` lookahead lists since, between, from, after, before, until and during, but not "in <year>".
- Failure input: `...most distinct coauthors in Network Time Synchronization Technologies in 2010-2014?` gives `topics=[]` and no TOPIC_FILTER. The answer silently returns the global ranking (Paul Flicek).

M3 (F2). Owner rag-engineer.
- Where: Synthesis.scala:358 and Verification.scala:121-124.
- Cause: the claim text says "linking N other communities". Spark defines `communitiesConnected` with the researcher's own community included (spark Bridges.scala:11, docs/spark-jobs.md:335). The verifier then maps "N other communities" to that same field, so the false statement is VERIFIED.
- Failure input: Chin Keong Ho, 2010-2014: "2 cross-community coauthors, linking 3 other communities". Ruigang Yang: "0 ..., linking 1 other communities".
- The same wording appears in every bridge or community answer (bq-v1-16 to 21).

M4 (F4). Owner rag-engineer.
- Where: Workflow.scala:189 and 318.
- Cause: researcher rows are emptied only when `e.abstention.nonEmpty`. They are not emptied when the answer abstains because no claim survived, or because the synthesis abstained.
- Failure input: a scripted reply whose only claim ("77 distinct coauthors") is removed gives an abstained, UNSUPPORTED answer that still carries Haizhou Li's row with numbers and `claimIds=[]`.
- agent-report.md:142 says abstaining answers have "no researcher rows"; that is wrong for this path.

M5 (new). Verifier soundness. Owner rag-engineer.
- Where: Verification.scala:381-399 (keyed numbers) and 401-409 (other numbers); the summary check at Workflow.scala:244-247 uses the same logic.
- Cause: the accepted values (`have`) are drawn from every cited graph item. A number is never tied to the researcher the claim names.
- Failure input: for a question whose bundle holds a C_r ranking plus a named researcher, a model claim "Haizhou Li has 65 distinct coauthors in window 2010-2014." citing both Haizhou Li's and Paul Flicek's (65) metric records is VERIFIED. The name, window and the 65 all find support somewhere in the cited set.
- Impact: a misattributed number is shown as VERIFIED, which breaks the gate items "every numerical claim has evidence" and "unsupported claims removed". It hits the model path and the summary. The deterministic synthesizer is not affected, because it cites one record per claim. No test covers it.

M6 (new). Data correctness in the local runtime. Owner rag-engineer.
- Where: LocalRuntime.scala:104 and 173; Agents.scala:181 (`GraphRunner.forPlan` windows) and 197-208 (`WindowChoice`); Plan.scala:311-314.
- Cause: with `LocalRuntimeSettings(windows = Some(subset))`, the `RuntimeContext` still carries the full config windows (1950-2026, 2000-2009, 2010-2014, 2015-2026; scholarchain-v1.yaml:68-73). An unloaded window is therefore chosen and allowed.
- Failure input: on the runtime BenchmarkRuntime loads (2010-2014 and 2015-2026), `What is the shortest coauthorship path from "Fan Bai" to "Matthew Brown"?` with no years plans 1950-2026. The path query runs on a window with no loaded edges, and the answer states "No coauthorship path of at most 6 hops connects Fan Bai and Matthew Brown in window 1950-2026." as VERIFIED. bq-v1-10 shows a 3-hop path in 2010-2014, which lies inside 1950-2026.
- It also writes the note "has no metrics row in window 1950-2026 (no publication in that window)", which is false. Pair checks after the M1 fix would give the same false `found=false`.
- Every benchmark question passes explicit years, so no test catches it.

M7. Owner build-engineer.
- Where: build.sbt:355 (`adversarialTests = blackBoxTests(...)`, not forked).
- Problem: the suite inherits the machine's `SBT_OPTS=-Xmx1536m` and runs out of memory in `LocalRuntime.load`. `sbt check`, the definition of done, can pass only with an environment override that is in no file.
- Concrete evidence: test-report-02.md recorded an OOM at 1536m and one at 2048m.

**MINOR findings** (carry forward to the human; none breaks a gate item)

- m1. Agents.scala:313-317, owner rag-engineer. When an exact-name lookup finds exactly one node, the resolver returns RESOLVED and never consults the token-order key. Homonyms with swapped order (for example a "Li Wei" query while "Wei Li" nodes exist) are not listed as candidates. DBLP suffixes are already stripped by `Normalize.personName`.
- m2. Verification.scala:285, owner rag-engineer. The name check accepts the name as a substring of any cited value. "Wei Li" passes on "Wei Lin", and a name passes when it appears only in an identity item's `coauthorContext`.
- m3. STATUS.md:46-48, owner documentation-engineer. It still says Phase 08's next step is the human gate, but Phase 08 is committed (12829d7, a093a92). This text predates this diff, and documentation-engineer flagged it.

**Gate axes otherwise checked and sound**
- User text never becomes Cypher or AWS: allowlisted templates only, ID-validated parameters, one window per COAUTHORED_WITH query. The gate tests compare query text with the template text and check parameter shapes.
- Ambiguous names abstain and list candidates.
- The deterministic and semantic partitions are kept apart and scored separately.
- Untrusted text: everything goes into a DATA block whose boundary is a hash of the data, filled in one pass.
- Chain-of-thought: decoding is strict (core `JsonLines` round-trip), reasoning markup and thinking blocks are refused, and only a hash of each reply is kept.
- Retries are bounded at 3, and the token budgets are enforced.
- Provenance: run, ER and policy versions are stamped on evidence and the audit.
- The docs (runtime-workflow.md, MODULE.md, STATUS Phase 09 section) state only reported facts.
- Scope: TemplateGraph, ScriptedLlm, InMemoryAuditLog and BedrockLlm with its stub are required by the addendum or by black-box testing. Using 33 questions instead of 11 follows spec line 465 (at least 30). I found no optional extras worth flagging.

**Fairness verdicts on the 13 red tests**

1. F1, 10 tests (bq-v1-07, 08, 09, 31, 32, each D and A): FAIR.
   - Spec 1503 ("classify ... direct coauthors") and 1519-1520 (templates for direct collaborators; "supporting publication IDs, years, and provenance").
   - Spec 1533 (publication support) and 465 (direct-collaboration and evidence categories).
   - The expected answers are oracle-computed and quoted in the text.
2. F2, bq-v1-21 A: FAIR.
   - Spec 1545 requires checking every bridge and numerical value, and the field definition is documented as own community included.
   - The test bound "N other communities <= crossCommunityDegree" follows logically, and the test also accepts a correct statement of 3.
3. P planner test (F1 and F3): FAIR.
   - Spec 1502-1504: parse the question into a typed QueryPlan and extract names, time windows, topic and institution.
   - Questions 07-09, 31-32 and 25-27 are natural phrasings.
4. F4 gate test: FAIR.
   - Spec 1539 (every material claim has evidence IDs), 1570 (gate), 1557 (abstaining answer) and the response shape at 2447 (rows carry `claimIds`).
   - Requiring rows of non-abstained answers to carry claimIds also follows that shape.

**Repair briefs** (owner rag-engineer unless noted)

- F1 / M1
  - Likely cause: in Plan.scala, let the pair and coauthor patterns accept quoted names, or add a two-name fallback on direct-collaboration cues (collaborated or coauthored with, joint publications, "support the collaboration between"). In Evidence.scala, fetch PUBLICATION items for the pair edge's `supportingWorkIds` (MaxPublications is 12). In Synthesis.scala, have the pair claim cite those PUBLICATION items.
  - What else could break: two-name path questions (bq-10 to 12, 33) gain DIRECT_COAUTHORS evidence, which uses up the query budget (250) and the bundle cap (120). Path evidence comes before coauthor evidence, but check it. Ranking questions with "joint publications" must not gain DIRECT_COAUTHORS without names. An ambiguous endpoint must still run no pair or path query.
  - Must stay green: PlannerSuite, StageSuite (direct collaborators and pair checks), PipelineSuite, WorkflowSuite, every BenchmarkWorkflowSuite D/A test that passes now, all of WorkflowGateSuite, BenchmarkQuestionSetSuite.
- F3 / M2
  - Likely cause: add `in|during <year>` (and similar) to the `TopicIn` lookahead at Plan.scala:374.
  - What else could break: a false-positive topic turns a ranking into an empty filtered set, which abstains (Evidence.scala:352-356). Re-check bq-01, 02, 19, 20, 28 to 30, the temporal questions and the injection-in-constraints tests.
  - Must stay green: the PlannerSuite topic and institution cases, PipelineSuite filters, benchmark 25-30 D/A and P, and the gate tests inj-constraints and inj-cypher-institution.
- F2 / M3
  - Likely cause: word the stored meaning without computing N-1, which spec 2386 forbids, for example "touching N communities, its own included". Key that phrase to `communitiesConnected`. Stop mapping "N other communities" to that field, so it becomes unsupported and is weakened.
  - What else could break: VerificationSuite bridge cases, SynthesisSuite copy test, benchmark 16-21 A, the cross-community link claims.
  - Must stay green: those suites plus the gate unsupported-claim tests.
- F4 / M4
  - Likely cause: build rows only when the answer did not abstain for any cause. Also drop or flag rows with empty `claimIds` in non-abstained answers, for example after model claims are removed.
  - What else could break: gate tests that read Haizhou's row after a deterministic fallback (WorkflowGateSuite 509, 618, 648) must keep it. Check `Validate.answer` and any WorkflowSuite or LocalRuntimeSuite row assertions.
  - Must stay green: WorkflowSuite, LocalRuntimeSuite, all of WorkflowGateSuite, benchmark A tests.
- M5
  - Likely cause: when a claim names researchers, accept per-researcher values (metric, community, identity fields) only from cited items whose `researcherIds` include a named researcher. Keep edge, path and ranking fields as they are.
  - What else could break: ranking claims (cite ranking plus several metric records), cross-community link claims (two names), hop claims, the summary check.
  - Must stay green: VerificationSuite, WorkflowSuite, gate unsupported and injection tests, benchmark A tests. Add a unit test with a misattributed value citing two records; it must come out CONFLICTING or UNSUPPORTED.
- M6
  - Likely cause: build the `RuntimeContext` config with `graph.windows` limited to the loaded windows, or have LocalRuntime refuse an unloaded window.
  - What else could break: the planner's default window becomes 2010-2026. WindowChoice then falls back to the widest loaded window with a note. Re-check the temporal benchmark questions and LocalRuntimeSuite.
  - Must stay green: the benchmark D/A tests, LocalRuntimeSuite, the gate test "every query is templated" (which asserts loaded windows).
- M7 (build-engineer)
  - Verdict: yes, this is a build-engineer item (build.sbt structure). It should be done in this repair round, not carried forward, because the required `sbt check` re-run after the rag repair cannot pass at the default heap.
  - Fix: `Test / fork := true` plus `Test / javaOptions += "-Xmx2560m"` on adversarialTests, the same pattern as build.sbt:192-259.
  - What else could break: a forked JVM's working directory can change how `BenchmarkStore.repoRoot` and `SynthesisPrompt.load(root)` find the repository, so set or verify the fork base directory. A 2560m fork plus a 1536m sbt JVM is about 4.1 GB inside the 5 GB WSL limit, so it is tight.
  - Must stay green: all 14 project test runs, with the test-marker task reached.

ASSUMPTIONS:
- Every finding was re-verified by reading the code; none was executed. M5 and M6 are code-path traces, not observed runs.
- The fairness verdicts judge the tests against docs/spec/assignment.md and the documented Spark field meaning, never against the rag implementation.
- Community size stays a disclosed gap that the orchestrator carried to the human (run-log step 1). It is not counted as a new finding.

RISKS (what I did NOT review):
- tests/benchmark/questions_v1.py and the BenchmarkStore / BenchmarkQuestionSetSuite oracle internals: only spot-checked; expected answers were sampled, not recomputed.
- The bodies of the rag unit tests (only names and PlannerSuite:49-80 read), BedrockLlmSuite, and the MODULE.md Public API section line by line.
- The Phase 08 code (SemanticContext.scala, Templates.scala), storage adapters, core `AwsHttp` error texts, and the ~/ref-phase-09 comparison.
- Live behaviour of M5 and M6, which needs a test run.
- State left behind: none; I wrote nothing.

MANUAL ACTION REQUIRED:
- The human reviews benchmark question set v1 and `semantic-relevance-rule-v1` at the gate.
- The human decides whether to accept the MINOR carry-forwards (m1 to m3).
- Phase 09 gate item "Code review returns APPROVE" is not met yet.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Repair round 1. rag-engineer fixes F1 to F4, M5 and M6 with unit tests; build-engineer fixes M7. Then test-engineer reruns, then one full `sbt check`, then a fresh code-reviewer re-check (review-report-02.md), required because this review has MAJOR findings.
