# test-engineer report, round 1 (Phase 09 step 3)

HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

STATUS: BLOCKED-ON
OWNER: test-engineer
WORK ORDER: Phase 09 step 3. Build benchmark question set v1, with expected answers computed from the stores without calling the workflow. Write the benchmark-query suite and the gate tests. Run the benchmark suite, then the full `sbt check` once. No commit, no STATUS.md.

The question set (33 questions) is built and checked, and the full `sbt check` passed. The workflow benchmark run and the gate tests need parts of the rag workflow's public API that are not written down anywhere test-engineer may read.

FILES CREATED:
- tests/benchmark/questions_v1.py: the generator; computes every expected answer from the run's JSON lines as the load manifests name them. Python standard library only, read-only on data. sha256 aebce53ad77bb0b08d0311ac1d23ebb27f2b4083f37805682ec9be94bbf6561c.
- tests/adversarial/resources/benchmark/questions-v1.json: the frozen question set v1. sha256 8584d275357c0b64445d60a7c7edcfb4e24a12d7b1b09d5b7407d25905b58e65.
- tests/adversarial/scholarchain/adversarial/rag/BenchmarkStore.scala: question-file loader and a second, independent store reader (ujson only, no ScholarChain module, never calls the workflow, checks each partition's row count against the manifest).
- tests/adversarial/scholarchain/adversarial/rag/BenchmarkQuestionSetSuite.scala: 7 structural and fixture tests, plus one test per question; each recomputes the answer with its own BenchmarkOracle and requires exact agreement with the frozen file.

FILES MODIFIED: none.

COMMANDS RUN (successful output observed):
- python3 -I exploration scripts in scratchpad, read-only on data.
- `timeout 900 python3 -I tests/benchmark/questions_v1.py <run dir> tests/adversarial/resources/benchmark/questions-v1.json`, twice (second after a wording fix): "benchmark-questions-v1: 33 questions, categories {'coauthor-breadth': 3, 'maximum-team-size': 3, 'direct-collaboration': 3, 'shortest-path': 3, 'components': 3, 'communities': 3, 'bridge-researchers': 3, 'temporal-change': 3, 'topics': 3, 'institutions': 3, 'evidence': 3}".
- sha256sum on the two files above.
- `timeout 300 scalafmt --test` on the two Scala files: "All files are formatted with scalafmt :)".
- `timeout 1500 sbt -batch "adversarialTests/testOnly scholarchain.adversarial.rag.BenchmarkQuestionSetSuite"`: "Passed: Total 40, Failed 0, Errors 0, Passed 40", "[success] Total time: 25 s". JUnit XML: tests="40" errors="0" failures="0" skipped="0".
- `timeout 3000 sbt -batch check`, once, foreground (harness moved it to background after 180 s; nothing else started). Exit 0:
```
[info] Passed: Total 120, Failed 0, Errors 0, Passed 120
[info] Passed: Total 184, Failed 0, Errors 0, Passed 184, Ignored 1
[info] Passed: Total 123, Failed 0, Errors 0, Passed 123
[info] Passed: Total 108, Failed 0, Errors 0, Passed 108
[info] Passed: Total 98, Failed 0, Errors 0, Passed 98
[info] Passed: Total 119, Failed 0, Errors 0, Passed 119
[info] Passed: Total 45, Failed 0, Errors 0, Passed 45
[info] 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run
[success] Total time: 368 s (0:06:08.0), completed Nov 24, 2026, 4:41:15 PM
```
Inside it: "Test run scholarchain.adversarial.rag.BenchmarkQuestionSetSuite finished: 0 failed, 0 ignored, 40 total 12.401s".

TEST RESULTS:
- BenchmarkQuestionSetSuite: 40 passed, 0 failed, 0 skipped, standalone and inside `sbt check`.
  - 7 structural and fixture tests: set versioned and pinned to run-ccf619c509611f06 and run-cb5a0421c406737d; at least 30 questions, all 11 categories, sequential IDs; every question names window, operation, method and expected answer; every researcher name quoted; community questions expect size as MISSING_EVIDENCE; store row counts match what the generator read; the ambiguous-name fixture "Wei Wang" has several nodes.
  - 33 per-question tests: Python and Scala agree exactly on every expected answer.
- Full `sbt check`: exit 0, 14 project test runs passed.
- Benchmark-query suite against the workflow: NOT WRITTEN, NOT RUN (blocked).
- Gate tests (no user text as database code; no silent resolution of an ambiguous name; evidence IDs on every numerical and path claim; unsupported claims removed, weakened or labeled; prompt injection; deterministic and semantic retrieval scored separately): NOT WRITTEN, NOT RUN (blocked).
- No test is failing, because none of the blocked tests could be written.

VALIDATION RESULTS:
- Covered: spec line 465 (at least 30 questions over the 11 categories): 33 questions, 3 per category; spec line 2137 (versioned questions): `benchmark-questions-v1`; the addendum rule that expected answers come from the stores without the workflow: two independent implementations that must agree; the carried-forward community-size gap: those questions expect missing evidence.
- Not covered: every Phase 09 gate item (spec lines 1566-1572).

How each expected answer was computed (both implementations follow the same steps):
- Read `load-manifests/neptune.json` and `dynamodb.json`, check runId == run-ccf619c509611f06, stream only the JSON-lines partitions needed; each partition's row count must equal the manifest's `rowsByPartition`.
- Rows read: Researcher 81,351, Work 25,020, Topic 1,945, Institution 5,318; metrics 17,347 rows in 2010-2014 and 38,767 in 2015-2026; coauthor edges 31,668 in 2010-2014 and 90,146 in 2015-2026; AUTHORED, HAS_TOPIC and AFFILIATED_WITH edges.
- Named subjects resolve by exact display name; each name must also be unique under a sorted-token key and a first-plus-last-token key across all 81,351 Researcher nodes, with no identity conflict, not unresolved, identity confidence at least 0.9.
- Rankings use the named field descending, ties by ID; the top value must be strictly above the runner-up.
- Paths: breadth-first search over the window's coauthor edges; accepted only when exactly one shortest path exists; each hop copies its edge row.
- Component sizes cross-checked by breadth-first reach.
- Topics: name tokens appear in no other topic name; at most 30 works in total (documented cap); works kept when year is in the window; authors kept when they have a metrics row in that window.
- Institutions: at most 100 affiliated researchers (documented row cap), name tokens unique among institution names. Limits chosen so truncation in the runtime cannot make a fair answer differ.

Question set v1 (W1 = 2010-2014, W2 = 2015-2026):

| ID | Category | Question (short) | Expected answer |
|---|---|---|---|
| 01 | breadth | most distinct coauthors, W1 | Paul Flicek (orcid:0000-0002-3897-7955), C=65; runner-up 55 |
| 02 | breadth | most distinct coauthors, W2 | Francesco Benedetti (orcid:0000-0003-4949-856X), C=76; runner-up 74 |
| 03 | breadth | "Haizhou Li", W1 | C=10, W=10.0, P=2 |
| 04 | team | "Eli De Poorter", W1 | M=7, dblp:conf/scvt/HoebekeCIKRPMD12 |
| 05 | team | "Liang Ge", W2 | M=3, two works: dblp:conf/uic/GeLLZ19, dblp:journals/jscic/ShenGYL15 |
| 06 | team | "Zongpeng Li", W2 | M=5, dblp:conf/icccn/SongCLBZZ16 |
| 07 | direct | "Shaojun Wei" / "Leibo Liu", W1 | edge e:co:923aac4e0a54467f3dff3932, joint 2, 2014-2014 |
| 08 | direct | "Hans Hagen" / "Jan Mohring", W1 | edge e:co:f221cde896355068d6055196, joint 2, 2010-2012 |
| 09 | direct | "Fan Bai" / "Manish Jain", W1 | no direct edge (2 hops apart) |
| 10 | path | "Fan Bai" to "Matthew Brown", W1 | 3 hops, unique path |
| 11 | path | "Liang Ge" to "Yunpu Ma", W2 | 4 hops, unique path |
| 12 | path | "Fan Bai" to "Haizhou Li", W1 | no path (different components; 40 reachable from the source) |
| 13 | component | "Haizhou Li", W1 | cc:5dc2a554307a102e, size 53 |
| 14 | component | "Zongpeng Li", W2 | cc:bd1ebe609b716e3d, size 28 |
| 15 | component | "Ruigang Yang", W2 | cc:8f9cdb04b9b55802, size 909 |
| 16 | community | "Fan Bai", W1 | cm:25b771e0a4ac4435; size MISSING_EVIDENCE (oracle count 10, for the human only) |
| 17 | community | "Haizhou Li", W1 | cm:e763cfc6ab495224; size MISSING_EVIDENCE (oracle count 7) |
| 18 | community | "Liang Ge", W2 | cm:c93c9d3997b76840; size MISSING_EVIDENCE (oracle count 6) |
| 19 | bridge | top bridge, W1 | Yong Liang Guan (orcid:0000-0002-9757-630X), 4.324012683770539e-05 |
| 20 | bridge | top bridge, W2 | Ruigang Yang (orcid:0000-0001-5296-6307), 0.0014285161471030454 |
| 21 | bridge | "Chin Keong Ho", W1 | bridge 2.522340732199481e-05, cross-community degree 2, connects 3 communities |
| 22 | temporal | "Shaojun Wei", W1 to W2 | C 11 to 17 (+6) |
| 23 | temporal | "Dacheng Tao", W1 to W2 | C 7 to 20 (+13) |
| 24 | temporal | "Leibo Liu", W1 to W2 | C 11 to 8 (-3) |
| 25 | topic | Network Time Synchronization Technologies, W1 | Carlos Eduardo Pereira, C=9 (8 of 17 works in window, 38 researchers) |
| 26 | topic | Educational Games and Gamification, W1 | Ee-Peng Lim, C=6 |
| 27 | topic | Multimodal Machine Learning Applications, W2 | Chenggang Yan, C=47 |
| 28 | institution | Nankai University, W1 | Di Wu (orcid:0000-0002-9433-7725), C=9 |
| 29 | institution | Arizona State University, W2 | Vijay Vittal, C=17 |
| 30 | institution | Huanggang Normal University, W2 | Yurong Guan, C=10 |
| 31 | evidence | supporting works, "Shaojun Wei" / "Shouyi Yin", W1 | e:co:a68b0c476c1258bdfdf6951c, 2 works with titles |
| 32 | evidence | supporting works, "Vignesh Ravichandran" / "Balamurali Murugesan", W2 | e:co:71de29b0551b1ef0913434e5, 2 works |
| 33 | evidence | per-hop works, "Fan Bai" to "Manish Jain", W1 | 2 hops with each hop's edge and works |

Gate fixture: "Wei Wang" has many Researcher nodes with exactly that display name (listed in the frozen file), and 22 nodes share the sorted-token key "wang wei". Full paths, hop evidence and titles are in the JSON file.

ASSUMPTIONS:
1. 33 questions, not 11: the work order says one per category, but spec line 465 and REFERENCE.md require at least 30; three per category meets both.
2. Windows: 2010-2014 is the smallest; 2015-2026 adds the temporal comparison and larger components.
3. Each question has a `constraints` block (names, window, topic, institution, metric, limit) because the planner only reads explicit forms; mapping it onto `AnalysisRequest` is not possible yet.
4. Two independent implementations are the answer key (Python generator, Scala verifier must agree).
5. Missing data skips, it does not fail: run data is git-ignored, so per-question tests are skipped (munit assume) on a checkout without it.
6. The one full `sbt check` ran now because the stop hook required a passing check after a source change; the unblocked rerun that adds suites needs one more full check.

RISKS:
- Not done: workflow benchmark run and the deterministic and semantic retrieval scores; all six gate test groups; output-benchmark.md (not test-engineer's territory).
- State left behind: four new untracked files under tests/, and the test marker touched by `sbt check`. No commit, no STATUS.md.
- Subject choice is not verified against the resolver: the uniqueness keys are not core's `Normalize.personName`. If the resolver treats a chosen name as ambiguous the workflow abstains and that question fails; code-reviewer would judge fairness.
- Phrasing needs review: questions 25 to 30 rely on the "in <phrase>" and "at <... University>" forms; question 25 has two "in" phrases. The human should check at the gate.
- Community size can be derived by counting metrics rows by `communityId` (oracle counts 10, 7, 6); this may matter to the human's decision on the gap.
- One call was denied: a `javap` look at compiled rag classes to read public signatures was blocked by the permission classifier. Not worked around; `src/main/**` and rag module unit tests not read.

MANUAL ACTION REQUIRED: the human reviews question set v1 (tests/adversarial/resources/benchmark/questions-v1.json) at the gate.

BLOCKED-ON OWNER: documentation-engineer, for the public API section of `modules/rag/MODULE.md`, with facts from rag-engineer's reports. If it cannot get them from the R1 and R2 reports, rag-engineer documents them.

BLOCKED-ON EVIDENCE:
- `modules/rag/MODULE.md` "Public API" describes only Phase 08 (rendering, chunking, semantic context). Its "Not done" still lists "The Phase 9 workflow".
- The R1 and R2 reports give names only, e.g. "`CollaborationWorkflow(ctx, stores, retriever, synthesizer, audit, limits, clock).run(queryId, AnalysisRequest): Either[WorkflowError, WorkflowResult]`" and "`LocalRuntime.load(cfg, runManifest, expectedErRunId, settings)`".
- The reference `~/ref-phase-09/modules/rag/MODULE.md` lists class names only; `modules/core/MODULE.md` names `AnswerResponse`, `AnswerClaim` and `EvidenceBundle` without fields.
- Needed written down:
  1. `AnalysisRequest`, `Constraints`, `AnalysisOptions`, `RetrievalOptions`: constructors and fields.
  2. `LocalRuntime.load`: parameter types (config, run manifest as path or record, `LocalRuntimeSettings` fields for window and vector subsets) and return type; `LocalRuntime.workflow(synthesizer, audit, limits)`: type of `limits` and its result.
  3. `WorkflowResult`: answer (claims with evidence IDs and status, summary, abstention reason, researcher rows), bundle partitions, audit record, and `WorkflowError`.
  4. `ScriptedLlm` (how replies are scripted), `LlmSynthesizer` constructor or `Synthesizers.select` signature, `SynthesisPrompt.load(root)`.
  5. `InMemoryAuditLog`: constructor and read access, `AuditRecord` field names.
  6. Where to observe executed graph query text and parameters (GraphRunner and GraphCall, or storage `InMemoryGraphStore` responder).

NEXT GATE: the orchestrator routes the API request to documentation-engineer (rag-engineer reports as source); then test-engineer reruns fresh to write the workflow benchmark suite and gate tests against these 33 questions, run them, and run one more full `sbt check`.
