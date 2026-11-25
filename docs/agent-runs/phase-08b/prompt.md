Phase 08b work order (human-authored): Spark analytics at 100,000 works

Purpose: Phase 08 integrated the pipeline at 25,000 works, because Spark analytics failed
locally at 100,000 works (attempt 1 out of memory, attempt 2 stopped at the 90-minute
limit). Hadoop (run `run-834f71c82961a194`) and er-v1 (run `run-85fd79957d197ffc`)
already completed at 100,000 works. The deadline moved to 2026-11-30, so this work order
retries only the Spark stage at 100,000 works on those existing runs, with a longer time
limit, and reruns the downstream steps only if it succeeds. No code is redesigned.
It also carries Phase 09 repair round 4: the structural claim-verifier fix that the
Phase 09 gate recorded for R4-1 to R4-3.
OpenAlex coverage stays partial (50,000 of 90,285 DOIs, recorded in Phase 08); it is not
refetched in this work order.

Rules for every owner:
- Scope: only what this work order requires. Nothing optional.
- Unattended run: the human may be asleep. Do not stop to ask questions. Take the most
  reasonable reading, record each choice and why in the step report, and carry on.
  Stop only for BLOCKED-ON, and route it as the rules say.
- Keep usage low: read only the files your step needs, do not explore the repository,
  and keep each report under 60 lines with the required fields. The orchestrator answers
  stop-hook messages in one line and does not restate earlier reports.
- No cloud: no AWS or Bedrock call, no credentials, no resources. Outputs go only to
  git-ignored paths (`datasets/samples/local/`, `target/`). Nothing large is committed.
- Inputs and outputs are located through run manifests. er-v1, the gold set and the
  Phase 05 evaluation are unchanged.
- Formatting: every agent formats only the files it owns, never `scalafmtAll`.
- Machine limits: never more than one sbt or JVM pipeline at once, no background shells,
  no polling or wait loops of any kind (including `while pgrep ...; do sleep ...; done`),
  no mutation testing; every command runs in the foreground with a timeout. Close every
  JVM before the next step starts. If the harness moves a command to the background,
  start nothing else and do not poll; wait for it.
- No agent commits, runs cleanup.sh or touches cloud resources.

Order, one owner at a time:

1. spark-engineer: run the local Spark pipeline at 100,000 works on Hadoop run
   `run-834f71c82961a194` and er-v1 run `run-85fd79957d197ffc`, with no code change unless
   it is needed to fit the machine (state any change and why). Settings: the time limit
   is 4 hours; check memory with `free -g` first, and use up to a 3 GB driver if the
   machine has at least 5.5 GB in total, otherwise up to 2 GB; at least 32 shuffle
   partitions; checkpoints on disk. Every invariant must be 0. If the run fails on memory
   or time, retry once with more partitions or a smaller heap. Report the run ID or the
   failure evidence for each attempt, and the graph, component, community and path
   counts, as step-01-spark-engineer-report.md.
   If both attempts fail: Spark run `run-ccf619c509611f06` (25,000 works) stays the
   integrated run, Spark at 100,000 works moves to the EMR run in the cloud phases, and
   step 2 is skipped, because the 25,000-work run is already ingested. Steps 3 to 6 still
   run.

2. storage-engineer (only if step 1 succeeded): run the local ingestion of the step 1
   export into all five indexes, with the same bounded deterministic subset rule as
   Phase 08 (8,000 source rows per document type, all rows for smaller types), and the
   second identical pass. Save the counts, throughput, rejected records and duplicate
   handling as docs/agent-runs/phase-08b/output-local-ingestion.md. Report as
   step-02-storage-engineer-report.md.

3. rag-engineer (always, whatever step 1's outcome): Phase 09 repair round 4, a disclosed
   human-authorized exception to the one-round rule. Replace text-based number
   attribution with a structural rule:
   - every numerical claim in the synthesis output carries explicit `researcherId`,
     `metric`, `value` and `evidenceIds` fields; bump the synthesis schema and
     claim-verifier versions;
   - the verifier checks that tuple against the evidence item for that researcher ID and
     metric;
   - the claim's number text is rendered from the verified tuple;
   - any number in free model text that no verified tuple backs is weakened and labeled.
   Remove the sentence-subject heuristics this replaces. Run `sbt rag/test`. Save the
   report as docs/agent-runs/phase-09/repair-04-rag-engineer.md.

4. test-engineer (always), in its own territory: keep every existing gate suite
   unedited, and add the R4-1 to R4-3 phrasings from
   docs/agent-runs/phase-09/review-report-04.md as tests. Keep benchmark question set v1
   (33 questions) unchanged. If step 1 succeeded, recompute every expected answer
   directly from the deterministic stores of the step 1 run, with the same code that does
   not call the workflow; otherwise keep the 25,000-work expected answers. Run the gate
   suites and the benchmark suite, then the full sbt check once. Save the results as
   docs/agent-runs/phase-08b/output-benchmark.md. Report as test-report.md, with the
   repair-round-4 part also saved as docs/agent-runs/phase-09/repair-04-test-engineer.md.
   If the larger data exposes another defect in the runtime workflow, leave the failing
   test and report BLOCKED-ON rag-engineer; do not change production code.

5. code-reviewer, fresh and read-only: check the class "a false per-researcher number is
   VERIFIED" against the step 3 change and the step 4 tests, plus regressions in the
   files changed by this work order, and this work order's run evidence. Save as
   docs/agent-runs/phase-09/review-report-05.md. Return APPROVE or REQUEST_CHANGES. Then
   one repair round for CRITICAL and MAJOR findings only. If the class is still open
   after it, stop: the human carries it forward.

6. documentation-engineer, on the standard tier: update docs/runtime-workflow.md for the
   new verifier rule and versions, and STATUS.md once for the whole work order (which run
   is the integrated run, the Phase 09 repair round 4 result, and where every report is).
   Other numbers in docs/ and the module notes are refreshed in Phase 17, not here.
   Report as step-06-documentation-engineer-report.md.

The orchestrator records every step in run-log.md.

Gate: the integrated run is at 100,000 works with every invariant 0, or both failed
attempts are recorded and the 25,000-work run is kept; if the 100,000-work run is used,
the local ingestion fills all five indexes with 0 rejections, the 33-question benchmark
is rerun with expected answers recomputed independently. In both cases the R4-1 to R4-3
tests pass, the full sbt check passes, and review 5 returns APPROVE.
