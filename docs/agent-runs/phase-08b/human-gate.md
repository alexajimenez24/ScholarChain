# Phase 08b human gate

## How Phase 08b was produced

Phase 08b is a human-authored work order (`prompt.md`). After the deadline moved to
2026-11-30, it retried Spark analytics at 100,000 works on the existing Hadoop run
`run-834f71c82961a194` and er-v1 run `run-85fd79957d197ffc`, and it carried Phase 09's
repair round 4: replacing text-based number attribution in the claim verifier with
structured number claims. The owning agents did the work through the orchestrator, one
owner at a time. Every step is in `run-log.md`. The Phase 09 repair reports are in
`docs/agent-runs/phase-09/` (`repair-04-*` to `repair-06-*`, `review-report-05.md`,
`review-report-05b.md`, `review-report-06.md`), and the Phase 08b reports are in this
folder.

## Spark at 100,000 works: FAILED

- Step 1 ran the local Spark pipeline at 100,000 works. It exhausted memory and made the
  machine unresponsive. It ran for about 10 hours, far past the 4-hour limit in the work
  order, which the agent did not enforce. `wsl --shutdown` did not respond, so I stopped
  WSL at about 10:28 by ending `wslservice.exe` from an administrator PowerShell.
- spark-engineer then wrote `step-01-spark-engineer-report.md` from what was on disk and
  recorded the outcome as FAILED. It deleted the incomplete outputs
  (`_checkpoints-08b-a1`, 1.9 GB, and `run-74832c9d4a53a2b0.json`). They were under the
  git-ignored `datasets/samples/local/2026-11-24/spark-out/`, not under `target/` as my
  instruction said; I accept that, since they belonged only to the failed run.
- During the failed run, spark-engineer had changed `GraphAlgorithms.scala` (a coalesce in
  the shortest-path search) and added a 12-hop test. Review 5 found the change could alter
  output through the `percentile_approx` merge and the grouping of floating-point sums, so
  both files were reverted to the committed version. The committed Spark code is the code
  that produced the integrated run.
- Spark run `run-ccf619c509611f06` (25,000 works) stays the integrated run. Step 2 (vector
  ingestion) was skipped, because that run is already ingested. Spark at 100,000 works
  moves to the EMR run in the cloud phases.
- Machine limits: after the restart I set WSL to `memory=5GB` and `swap=4GB` in
  `.wslconfig`, so a runaway job cannot freeze Windows again.

## Phase 09 claim verifier: repair rounds 4 to 6

- Round 4 (step 3): every numerical claim now carries `researcherId`, `metric`, `value` and
  `evidenceIds` (`answer-synthesis-v2`), checked against the evidence for that researcher
  and metric. The R4-1 to R4-3 tests pass (8 of 8).
- Review 5 found numbers written as words or ordinals in free model text still VERIFIED
  (V5-1). Round 5 added detection for them; the re-check (review 5b) showed that word-list
  detection could still be beaten (V5b-1, V5b-2).
- I then replaced detection with a construction rule (round 6, a further disclosed
  exception to the one-round rule): only structured claims can be VERIFIED; every
  free-text claim from the model is labeled as unverified interpretation, whatever it
  contains; a ranking statement can be VERIFIED only as a structured ranking claim
  (`claim-verifier-v3`).
- The 16 gate tests that scripted the replaced v1 reply were rewritten for v2, with the
  same intent, and the R3 suite was converted so its phrasings must come out labeled and
  not VERIFIED. The reviewer had judged the 16 v1 tests not fair after the format change.
- After round 6: rag 138 tests, adversarial 230 of 230, benchmark 33 of 33 on the
  25,000-work answers, and the full `sbt check` exited 0.
- Review 6 (`review-report-06.md`) confirmed closed: wrong value, rank, researcher, metric
  or window; missing, foreign or out-of-claim evidence IDs; colliding claim IDs; injected
  text; a model answer being VERIFIED overall; and the deterministic path. It returned
  REQUEST_CHANGES on one item, R6-1, below.

## Decisions

- R6-1 (MAJOR, model path only): a rank figure on a filtered ranking (topic, institution,
  or bridge ranking) is VERIFIED with text that leaves out the filter, so "rank 1" can
  read as a global rank. The deterministic path already says "among <filter>". I am not
  authorizing another round in this work order. R6-1 goes to Phase 11's repair loop,
  where the gate requires no open MAJOR finding. The fix is to put the filter and limit
  in the rank label, or refuse rank figures on filtered rankings, with a gate test where
  the filtered rank is 1 and the global rank is not.
- The model-written path stays off (`LLM_ENABLED=false`) until R6-1 is fixed and reviewed.
- Gate items not met at this commit: the 100,000-work Spark run (moved to EMR), and "code
  review returns APPROVE" for the verifier. The other gate items are met: the failed
  attempts are recorded, every invariant of the integrated run is 0, the 33-question
  benchmark passes, and the full `sbt check` passes.

## Accepted, with known gaps

Phase 08b is accepted as committed. Carried forward to Phase 11:
- R6-1, above (rag-engineer, with a test-engineer gate test).
- R6-2 (MINOR): in the converted WorkflowGate suite, the test no longer requires the false
  claim `claim-2` to be absent, only its figure removed and the false value absent from
  the kept text. That loosening was not disclosed in the test report. test-engineer
  restores the stricter assertion or justifies it in writing.
- R6-3 (MINOR): stale `claim-verifier-v2` strings in the R4Gate scaladoc, and
  `LlmSynthesizer.Version` still reading `llm-synthesis-v1` (rag-engineer and
  test-engineer).
- A time limit enforced by the command itself (for example `timeout`) for every
  long-running pipeline step, so a runaway run cannot outlast its limit
  (factory-engineer and the owning engineers).
- The guard blocks report writes whose text mentions the test-marker task or path
  (factory-engineer, already tracked).
- Spark at 100,000 works, on EMR (cloud phases).

## Manual changes

- I stopped WSL by ending `wslservice.exe`, and changed `.wslconfig` (`memory=5GB`,
  `swap=4GB`).
- I deleted the Phase 08 leftover Spark checkpoints (`spark-out/_checkpoints`, 2.1 GB) and
  `run-c4b35c788d05a93c.json`, both git-ignored data from an earlier attempt.
- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 08b is complete. I approve starting Phase 10.
