# Phase 08b step 4: benchmark and gate results (test-engineer)
- Integrated run: step 1 FAILED, so Spark run run-ccf619c509611f06 (25,000 works) stays the integrated run; ER run run-cb5a0421c406737d; windows 2010-2014 and 2015-2026; 31,341 vector chunks.
- Expected answers: the 25,000-work answers, not recomputed. Question set benchmark-questions-v1 (33 questions) unchanged: git status shows no change under tests/adversarial except the new R4 suite.
- Workflow benchmark (deterministic synthesizer): D 33/33, A 33/33, Pass 33/33, planner P 33/33. Semantic hit rate 6/33 (18.2%), precision 7/80 (8.8%). Every question VERIFIED, 0 failures, 3/3 in all 11 categories.
- LocalRuntime.load took 312,171 ms; 928 MB heap used of 2,560 MB.
- Gate and benchmark run, one JVM, exit 1, 412 s, 159 tests, 143 passed, 16 failed: R4Gate 8/8 (new); R3Gate 0/6; D5Gate 0/2; RepairGate 4/6; WorkflowGate 23/29; BenchmarkWorkflow 68/68; BenchmarkQuestionSet 40/40.
- Full sbt check: exit 1, 727 s. Format and compile passed. Per project: 120, 184 (1 ignored), rag 134, 98, 119 and spark 46 passed (GraphAlgorithmsSuite 7/7, covering the uncommitted change). adversarialTests: 227 total, 16 failed (the same 16). The final test-marker task did not run, so the marker was not touched.
