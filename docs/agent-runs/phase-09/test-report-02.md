# test-engineer report, round 2 (Phase 09 step 3)

HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

I wrote the benchmark-query suite and the gate tests, and ran them through stages 1 to 10 on run-ccf619c509611f06. 27 of 33 questions pass. 13 tests are red on purpose; they expose four defects (F1 to F4) for code-reviewer's fairness verdict. I ran the full `sbt check` once. It exited 1 only because of those 13 tests; every other project passed.

The check did not run at the default heap. These suites need a heap larger than this environment's default SBT_OPTS (-Xmx1536m), so I ran the check with `SBT_OPTS="-Xmx2560m -Xss4m"`. A build change is needed (see MANUAL ACTION REQUIRED).

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 09 step 3 round 2.
- Run the workflow benchmark on the 33 frozen questions, comparing against the expected answers. Score deterministic and semantic retrieval separately; community-size questions expect missing evidence.
- Write the gate tests: no user text as database code, ambiguous "Wei Wang", evidence IDs on numerical and path claims, unsupported claims handled by a scripted model, prompt injection, separate scoring, bounded retry.
- Failing tests stay red. Run the suites, then `sbt check` once.

FILES CREATED:
- tests/adversarial/scholarchain/adversarial/rag/BenchmarkRuntime.scala: one shared LocalRuntime per JVM, with:
  - windows 2010-2014 and 2015-2026, plus the default Phase 08 vector subset of 8,000 rows per type;
  - a serialized `observe` that returns the graph queries each run executed;
  - the request builder, in the spec's `POST /v1/query` shape: question text plus constraints (yearFrom, yearTo, topics, institutions) and `analysis.rankingMetric`.
- tests/adversarial/scholarchain/adversarial/rag/BenchmarkWorkflowSuite.scala: the benchmark suite.
  - Two tests per question: D (deterministic retrieval) and A (answer).
  - One test for the planner reading the question text alone (P).
  - One partition and semantic scoring test, which writes the results text.
- tests/adversarial/scholarchain/adversarial/rag/WorkflowGateSuite.scala: the gate tests (29).

FILES MODIFIED (mine, from round 1):
- BenchmarkStore.scala: `data` is now a releasable cache, plus a `HeavyLoad` permit so the store reader and the runtime never load at the same time.
- BenchmarkQuestionSetSuite.scala: takes and releases that permit (beforeAll/afterAll) and drops its oracle afterwards.
- The question set is unchanged: questions-v1.json sha256 8584d275357c0b64445d60a7c7edcfb4e24a12d7b1b09d5b7407d25905b58e65, generator aebce53ad77bb0b08d0311ac1d23ebb27f2b4083f37805682ec9be94bbf6561c.

COMMANDS RUN (output observed):
- An exploratory probe run of the workflow.
  - `SBT_OPTS="-Xmx2g -Xss4m" sbt -batch "adversarialTests/testOnly ...BenchmarkWorkflowSuite"`: passed, 164 s.
  - An earlier attempt at the default -Xmx1536m failed with `java.lang.OutOfMemoryError: Java heap space` inside LocalRuntime.load (storage `RowSource`, Loaders.scala:91).
- Run 1 at -Xmx2g, benchmark and gate suites: "Failed: Total 97, Failed 17, Passed 80".
  - I corrected 4 of my own assertions that were stricter than the spec (see ASSUMPTIONS 4).
- Run 2 at -Xmx2g, all three rag suites: OutOfMemoryError in LocalRuntime.load inside the gate suite. This was an infrastructure failure, so I stopped that run.
- Run 3 at -Xmx2560m, `adversarialTests/testOnly scholarchain.adversarial.rag.*`: "Failed: Total 137, Failed 13, Errors 0, Passed 124", 180 s.
- `scalafmt --test` on my 5 files: "All files are formatted with scalafmt :)". Only my files were formatted.
- `SBT_OPTS="-Xmx2560m -Xss4m" timeout 3000 sbt -batch check`, once, exit 1 after 961 s:
  - format checks: success;
  - project test runs passing: 120, 123, 184 (1 ignored), 98, 119 and 45;
  - adversarialTests: "Failed: Total 205, Failed 13, Errors 0, Passed 192".
  - The only failures are BenchmarkWorkflowSuite (12) and WorkflowGateSuite (1). BenchmarkQuestionSetSuite passed 40 of 40.
  - The test-marker task did not run, so the marker was not touched.
- sha256sum on the question files.

TEST RESULTS:
- BenchmarkQuestionSetSuite: 40 passed, 0 failed.
- BenchmarkWorkflowSuite: 68 total, 56 passed, 12 failed:
  - bq-v1-07, 08, 09, 31 and 32, both D and A (10 tests): defect F1.
  - bq-v1-21 A: defect F2.
  - The planner test (P): F1 for questions 07, 08, 09, 31 and 32, and F3 for questions 25 to 27.
- WorkflowGateSuite: 29 total, 28 passed, 1 failed:
  - "every numerical and path claim cites existing deterministic or identity evidence": defect F4.

Defects (each test stays red; code-reviewer judges fairness):
- **F1** (spec stage 1, "Classify requested operations such as ... direct coauthors"; stage 4, "direct collaborators"):
  - What happens: questions like "Has X collaborated with Y in W..." and "Which publications support the collaboration between X and Y in W?" are planned as SEMANTIC_EXPLANATION only.
  - No coauthor-pair query runs and the bundle has no COAUTHOR_EDGE or PUBLICATION evidence.
  - The answer states only each researcher's own metrics. It never gives the joint count (expected 2) or that no edge exists (bq-v1-09).
  - Owner: rag-engineer.
- **F2** (spec stage 8 claims, stage 9 "check every ... bridge score ... numerical value"):
  - What happens: the deterministic synthesizer reports `communitiesConnected` as "N other communities". That count includes the researcher's own community.
  - Evidence: Ruigang Yang in 2015-2026 has "0 cross-community coauthors, linking 1 other communities"; Chin Keong Ho in 2010-2014 has "2 cross-community coauthors, linking 3 other communities". Both are impossible.
  - The verifier marks the claim VERIFIED because the number equals the field.
  - Only bq-v1-21 checks this; the same wording appears in bq-v1-16 to 20.
  - Owner: rag-engineer.
- **F3** (spec stage 1, "Extract ... topic"):
  - What happens: "Which researcher has the most distinct coauthors in <Topic> in <window>?" gives `topics=[]` and no TOPIC_FILTER.
  - The answer is the global ranking (Paul Flicek), with no note that the topic was dropped. That is a silent wrong answer.
  - The same questions pass when the topic is passed in `constraints.topics`.
  - Owner: rag-engineer.
- **F4** (gate "Every numerical and path claim has evidence IDs"):
  - What happens: a model claim was removed (`claim-1`, which stated a value of 77). The answer then abstains, UNSUPPORTED with no claims.
  - It still carries a researcher row with metric numbers and empty `claimIds`, so those numbers point to no evidence.
  - Owner: rag-engineer.

VALIDATION RESULTS (gate coverage):
- **No user text as database code**:
  - 7 injection requests: Cypher in a quoted name, UNION and CALL after the question, an institution phrase, injected `constraints.topics` and `institutions`, two smuggled `researcherIds`, and Gremlin plus AWS words.
  - Every executed query is an allowlisted template whose text equals its template, with no write clause.
  - Every string parameter is an ID, a window or an edge type, and contains no payload; numbers are bounded.
  - Across every query any suite ran: one query text per template, and every COAUTHORED_WITH query names exactly one configured window.
  - All passed.
- **Ambiguous names**:
  - "Wei Wang" abstains as AMBIGUOUS with at least 2 candidates, no metric or path evidence and no count claim.
  - The variants (lower case, swapped, upper case, accents, extra spaces) and a Cyrillic confusable never resolve.
  - An ambiguous path endpoint runs no path query.
  - Picking a candidate through `researcherIds` answers for that ID only.
  - All passed.
- **Evidence IDs**: checked over all 33 benchmark answers and every gate answer. Claims pass; researcher rows fail (F4).
- **Unsupported claims** (scripted model):
  - A fabricated value is removed as CONFLICTING.
  - A claim citing a missing evidence ID, and a semantic-only number, are removed as UNSUPPORTED.
  - A fabricated hop is removed or weakened.
  - An unknown name is weakened to PARTIALLY_SUPPORTED and labeled.
  - A fabricated summary is replaced, and a model abstention despite evidence is overridden.
  - All passed.
- **Prompt injection**:
  - Injected text in the question does not change the stored value, either deterministically or when a model obeys it.
  - The question, a fake fence closer and `{{EVIDENCE}}` and `{{BOUNDARY}}` placeholders stay inside the DATA block, whose boundary cannot be guessed from the question.
  - An injected model reply is removed.
  - All passed.
- **Bounded retry**:
  - At most 3 model calls, then the deterministic fallback.
  - One malformed reply, then a valid one: the retry prompt never repeats the reply.
  - Throttled calls end in the fallback.
  - A reasoning key or `<thinking>` markup is refused and never stored.
  - The output-token cap is at most 1500 per call.
  - All passed.
- **Deterministic and semantic retrieval scored separately**: D is scored on the deterministic and identity partitions, S on the semantic partition only, and the partitions are checked to be disjoint (passed).
- **Community size**: bq-v1-16 to 18 pass. The answer states "community size of cm:... is not available", and no claim gives a member count.

ASSUMPTIONS:
1. Requests use the spec's REST shape. Names stay quoted in the text. A temporal question gets no year bounds, because its text names both windows. Text-only planning is scored separately as P.
2. The semantic relevance labels follow a rule I named `semantic-relevance-rule-v1`: an item is relevant when its entity is one of the question's expected entities. These labels are not human-reviewed; the human should review them at the gate.
3. The heap was raised for my command runs only, through SBT_OPTS. No build file was changed.
4. My own tests corrected after run 1, to match the spec wording:
   - a typed Unsupported refusal also counts as "not silently resolved";
   - a resolved second endpoint may appear in `resolvedResearcherIds`;
   - the disambiguation test uses the documented form (name in text plus `researcherIds`);
   - the smuggled-ID tests use a question with a name;
   - a fabricated hop may be removed or weakened.
5. No question in the frozen set was changed; none is malformed.

RISKS:
- **Heap**: LocalRuntime.load needs more than the default SBT_OPTS -Xmx1536m.
  - It failed at 1536m once and at 2048m once, and passed at 2048m once (241 s) and at 2560m twice.
  - About 1.0 to 1.1 GB stays in use after loading.
  - Under the default heap, `sbt check` will run out of memory in these suites until adversarialTests forks with its own heap.
- At 2560m the check took 961 s; the suite took 830 to 945 s, slowed by the memory pressure.
- Not done: planting injected text into the vector store, because there is no documented API to add a record. Retrieved-text containment is checked only for the semantic texts the run retrieved.
- Observations not turned into tests:
  - `researcherIds` without a name in the question gives Unsupported.
  - Lowercase "wei wang" is not recognized as a name.
- Semantic retrieval is weak with the hash embedder: hit rate 6/33, precision 7/80.
- State left behind: 3 new and 2 modified files under tests/adversarial, and target/tests/adversarial/benchmark-results-v1.md. Nothing was committed and STATUS.md was not touched.

MANUAL ACTION REQUIRED: build-engineer, through the orchestrator: fork the adversarialTests tests with a heap of at least 2560m, or the default `sbt check` will run out of memory. The human reviews semantic-relevance-rule-v1.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer gives a fairness verdict on the 13 red tests (F1 to F4); repair briefs go to rag-engineer; test-engineer reruns.

The benchmark results text is appended to `output-benchmark.md`.
