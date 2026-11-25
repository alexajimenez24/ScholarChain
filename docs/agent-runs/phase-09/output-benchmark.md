Phase 09 step 3, test-engineer, 2026-11-24 (round 1, partial: question set only)
Question set: benchmark-questions-v1, 33 questions (3 per category, 11 categories), windows 2010-2014 and 2015-2026,
graph run run-ccf619c509611f06, ER run run-cb5a0421c406737d.
File tests/adversarial/resources/benchmark/questions-v1.json sha256 8584d275357c0b64445d60a7c7edcfb4e24a12d7b1b09d5b7407d25905b58e65
Generator tests/benchmark/questions_v1.py sha256 aebce53ad77bb0b08d0311ac1d23ebb27f2b4083f37805682ec9be94bbf6561c
sbt -batch "adversarialTests/testOnly scholarchain.adversarial.rag.BenchmarkQuestionSetSuite":
  Passed: Total 40, Failed 0, Errors 0, Passed 40 (JUnit: skipped="0", time 8.503 s); [success] Total time: 25 s
sbt -batch check: exit 0; 14 project test runs passed; [success] Total time: 368 s;
  BenchmarkQuestionSetSuite inside check: 0 failed, 0 ignored, 40 total (12.401 s)
Workflow benchmark (33 questions through CollaborationWorkflow): NOT RUN, blocked on the undocumented rag public API.
Gate tests: NOT WRITTEN, blocked on the same.
(Round 2 results are appended below.)

----- Round 2 -----
Phase 09 step 3 round 2, test-engineer, 2026-11-24
Workflow benchmark, benchmark-questions-v1, 33 questions, graph run run-ccf619c509611f06, ER run run-cb5a0421c406737d, windows 2010-2014, 2015-2026, deterministic synthesizer, InMemoryAuditLog
LocalRuntime.load 158135 ms; heap used after load 1034 MB of 2560 MB max; vector chunks 31341
Request shape: spec POST /v1/query (question + constraints yearFrom/yearTo/topics/institutions + analysis.rankingMetric); P = planner on the question text alone.

| ID | Category | D | A | Pass | P | Verification | Semantic returned/relevant |
|---|---|---|---|---|---|---|---|
| bq-v1-01 | coauthor-breadth | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-02 | coauthor-breadth | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-03 | coauthor-breadth | pass | pass | pass | pass | VERIFIED | 4/1 |
| bq-v1-04 | maximum-team-size | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-05 | maximum-team-size | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-06 | maximum-team-size | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-07 | direct-collaboration | FAIL | FAIL | FAIL | FAIL | VERIFIED | 2/0 |
| bq-v1-08 | direct-collaboration | FAIL | FAIL | FAIL | FAIL | VERIFIED | 2/0 |
| bq-v1-09 | direct-collaboration | FAIL | FAIL | FAIL | FAIL | VERIFIED | 2/0 |
| bq-v1-10 | shortest-path | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-11 | shortest-path | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-12 | shortest-path | pass | pass | pass | pass | VERIFIED | 4/0 |
| bq-v1-13 | components | pass | pass | pass | pass | VERIFIED | 4/1 |
| bq-v1-14 | components | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-15 | components | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-16 | communities | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-17 | communities | pass | pass | pass | pass | VERIFIED | 4/2 |
| bq-v1-18 | communities | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-19 | bridge-researchers | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-20 | bridge-researchers | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-21 | bridge-researchers | pass | FAIL | FAIL | pass | VERIFIED | 2/0 |
| bq-v1-22 | temporal-change | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-23 | temporal-change | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-24 | temporal-change | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-25 | topics | pass | pass | pass | FAIL | VERIFIED | 3/1 |
| bq-v1-26 | topics | pass | pass | pass | FAIL | VERIFIED | 4/1 |
| bq-v1-27 | topics | pass | pass | pass | FAIL | VERIFIED | 4/1 |
| bq-v1-28 | institutions | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-29 | institutions | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-30 | institutions | pass | pass | pass | pass | VERIFIED | 2/0 |
| bq-v1-31 | evidence | FAIL | FAIL | FAIL | FAIL | VERIFIED | 2/0 |
| bq-v1-32 | evidence | FAIL | FAIL | FAIL | FAIL | VERIFIED | 3/0 |
| bq-v1-33 | evidence | pass | pass | pass | pass | VERIFIED | 2/0 |

| Category | Questions | D | A | Pass | S hit (>= 1 relevant) | S relevant/returned |
|---|---|---|---|---|---|---|
| coauthor-breadth | 3 | 3/3 | 3/3 | 3/3 | 1/3 | 1/8 |
| maximum-team-size | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| direct-collaboration | 3 | 0/3 | 0/3 | 0/3 | 0/3 | 0/6 |
| shortest-path | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/8 |
| components | 3 | 3/3 | 3/3 | 3/3 | 1/3 | 1/8 |
| communities | 3 | 3/3 | 3/3 | 3/3 | 1/3 | 2/8 |
| bridge-researchers | 3 | 3/3 | 2/3 | 2/3 | 0/3 | 0/6 |
| temporal-change | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| topics | 3 | 3/3 | 3/3 | 3/3 | 3/3 | 3/11 |
| institutions | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| evidence | 3 | 1/3 | 1/3 | 1/3 | 0/3 | 0/7 |

Deterministic retrieval score (D): 28/33 (84.8%)
Answer score (A): 27/33 (81.8%)
Benchmark pass (D and A): 27/33 (81.8%)
Planner from question text alone (P): 25/33 (75.8%)
Semantic retrieval (S, semantic-relevance-rule-v1): hit rate 6/33 (18.2%), precision 7/80 (8.8%) relevant of returned items
Semantic items by document type: collaboration-community 6, paper-abstract 66, researcher-profile 5, topic-institution-summary 3

Failures:
- bq-v1-07/08 D: no COAUTHOR_EDGE item for the expected edge (e:co:923aac4e..., e:co:f221cde8...); A: no kept claim citing it with 2 joint publications; P: DIRECT_COAUTHORS not planned (planned SEMANTIC_EXPLANATION)
- bq-v1-09 D: no found=false COAUTHOR_EDGE item for the pair; A: no kept claim citing it; P: DIRECT_COAUTHORS not planned
- bq-v1-21 A: claim-2 says "2 cross-community coauthors, linking 3 other communities" (communitiesConnected=3 includes the researcher's own community)
- bq-v1-25/26/27 P: topic not extracted (topics []), TOPIC_FILTER not planned; the text-only answer is the global ranking
- bq-v1-31/32 D: no COAUTHOR_EDGE item for the edge and no PUBLICATION items for the 2 supporting works; A: no kept claim citing them; P: DIRECT_COAUTHORS not planned

Test counts (run 3, -Xmx2560m): BenchmarkQuestionSetSuite 40/40 passed; BenchmarkWorkflowSuite 68 total, 56 passed, 12 failed; WorkflowGateSuite 29 total, 28 passed, 1 failed (F4: abstained answer keeps a researcher row with numbers and no claimIds).
Full sbt check (SBT_OPTS -Xmx2560m -Xss4m), exit 1, 961 s: other project test runs passed (120, 123, 184 with 1 ignored, 98, 119, 45); adversarialTests "Failed: Total 205, Failed 13, Errors 0, Passed 192", only the 13 tests above; the test-marker task not reached.

----- Repair round 1 rerun -----
Phase 09 repair round 1 rerun, test-engineer, 2026-11-24
Workflow benchmark, benchmark-questions-v1, 33 questions, graph run run-ccf619c509611f06, ER run run-cb5a0421c406737d, windows 2010-2014, 2015-2026, deterministic synthesizer, InMemoryAuditLog
LocalRuntime.load 244070 ms; heap used after load 934 MB of 2560 MB max; vector chunks 31341 (inside sbt check; default SBT_OPTS -Xmx1536m -Xss4m, adversarialTests forked at -Xmx2560m)

All 33 questions: D pass, A pass, Pass, P pass, Verification VERIFIED (per-question rows identical to round 2 except that
bq-v1-07, 08, 09, 21, 25, 26, 27, 31, 32 now pass every column).
Semantic returned/relevant per question is unchanged from round 2 (same retrieval, same vector subset).

| Category | Questions | D | A | Pass | S hit (>= 1 relevant) | S relevant/returned |
|---|---|---|---|---|---|---|
| coauthor-breadth | 3 | 3/3 | 3/3 | 3/3 | 1/3 | 1/8 |
| maximum-team-size | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| direct-collaboration | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| shortest-path | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/8 |
| components | 3 | 3/3 | 3/3 | 3/3 | 1/3 | 1/8 |
| communities | 3 | 3/3 | 3/3 | 3/3 | 1/3 | 2/8 |
| bridge-researchers | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| temporal-change | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| topics | 3 | 3/3 | 3/3 | 3/3 | 3/3 | 3/11 |
| institutions | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/6 |
| evidence | 3 | 3/3 | 3/3 | 3/3 | 0/3 | 0/7 |

Deterministic retrieval score (D): 33/33 (100.0%)
Answer score (A): 33/33 (100.0%)
Benchmark pass (D and A): 33/33 (100.0%)
Planner from question text alone (P): 33/33 (100.0%)
Semantic retrieval (S, semantic-relevance-rule-v1): hit rate 6/33 (18.2%), precision 7/80 (8.8%) relevant of returned items
Semantic items by document type: collaboration-community 6, paper-abstract 66, researcher-profile 5, topic-institution-summary 3
Failures: none (benchmark).

Test counts (sbt check, default SBT_OPTS): BenchmarkQuestionSetSuite 40/40 passed; BenchmarkWorkflowSuite 68 total, 68 passed, 0 failed; WorkflowGateSuite 29 total, 29 passed, 0 failed; WorkflowRepairGateSuite (new, M5/M6) 6 total, 5 passed, 1 failed (D5: "Hans Hagen, compared with Jan Mohring, has 5 distinct coauthors in window 2010-2014." citing both metric records is VERIFIED; 5 is Jan Mohring's value).
Full sbt check (SBT_OPTS -Xmx1536m -Xss4m, the default; adversarialTests forked at -Xmx2560m), run once, exit 1, 771 s: format checks success; other project test runs passed (120, 131, 184 with 1 ignored, 98, 45, 119); adversarialTests "Failed: Total 211, Failed 1, Errors 0, Passed 210", only the D5 test; the test-marker task not reached.

----- Repair round 2 rerun (D5 only) -----
Phase 09 repair round 2 rerun, test-engineer, 2026-11-24
Benchmark unchanged from repair round 1: D 33/33, A 33/33, pass 33/33, P 33/33; semantic hit rate 6/33, precision 7/80.
LocalRuntime.load 209635 ms; heap used after load 933 MB of 2560 MB max; vector chunks 31341.
Suite totals: BenchmarkQuestionSetSuite 40/40, BenchmarkWorkflowSuite 68/68, WorkflowGateSuite 29/29, WorkflowRepairGateSuite 6/6 (D5 test now green), WorkflowRepairD5GateSuite 2/2 (new); adversarialTests 213/213; rag 133/133.
Full sbt check (default SBT_OPTS -Xmx1536m -Xss4m; adversarialTests forked at -Xmx2560m): exit 0, 734 s, 14 project test runs passed, test marker touched. Passed totals: 120, 184 (1 ignored), 133, 213, 119, 45, 98.
