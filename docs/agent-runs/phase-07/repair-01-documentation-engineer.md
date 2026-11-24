HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier, role default

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 07 repair round 1, task R1-3 (docs for MAJOR-1, MINOR-1 to MINOR-4, MINOR-6, NIT-2, the R1-2 core changes, and STATUS).
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/STATUS.md
- /home/ajcreator/scholarchain/docs/storage.md
- /home/ajcreator/scholarchain/modules/storage/MODULE.md
- /home/ajcreator/scholarchain/modules/core/MODULE.md

Changes by item:
- Item 1, STATUS.md:
  - R1-2 is "done (NIT-3, NIT-4; sbt core/test 119 passed; full sbt check exit 0, 305 s: 158+1 ignored, 119, 68, 98, 33, 119)".
  - R1-3 is "done" and Review 2 is "next".
  - The repair round row and the "Where things stand" Phase 07 line are updated.
  - A note R1-3 is added.
- Item 2, MAJOR-1 docs:
  - The served-run invariant is in docs/storage.md section 7 (idempotency) and in modules/storage/MODULE.md Known issues.
  - It covers one served run per Neptune cluster and per vector index, load only after the human cleans or rebuilds, and the old run's nodes, edges and vectors are not removed.
  - Unchanged vectors keep the first run's runId and textUri, and full run scoping goes to the human gate.
- Item 3, MINOR-2: docs/storage.md section 10 now says a run's semantic-context prefix may be deleted only together with the vectors whose textUri points into it. It also says the load report holds counts and rejections only.
- Item 4, MINOR-3 docs:
  - The bound is 25 HTTP attempts including retries, enforced by a counting transport (`AwsIntegration.BoundedTransport`).
  - The variable is `SCHOLARCHAIN_IT_TEST_VECTOR_INDEX` (dedicated test index; a production index is refused before any call).
  - The old name `SCHOLARCHAIN_IT_VECTOR_INDEX` is gone from my territory; grep before editing showed only docs/storage.md:351 outside agent-runs.
- Item 5, MINOR-4: docs/storage.md section 6 is restated as a wiring requirement. No storage code builds AwsHttp, and Phase 10 and cloud callers use maxAttempts 1 under a RetryPolicy. MODULE.md Known issues has the same.
- Item 6, MINOR-6: documented as a carried-forward known issue in both files.
- Item 7, MINOR-1 behavior: a missing input directory gives one visible malformed-row rejection. The stage still records Succeeded and expectedRows makes it fail. This is in section 7 and in MODULE.md.
- Item 8, NIT-2, modules/core/MODULE.md:
  - The wording is corrected to "2 of the AWS suite's roughly 30 cases are used as vectors, plus the published signing-key value".
  - The R1-2 changes are added: the FULL_URI host restriction (https, or http only to the listed loopback and container hosts), the malformed-URL error without query string, core tests 119, and full sbt check exit 0 in 305 s.
- Item 9: STATUS.md has a new section "Carried forward from Phase 07 (for the human gate)".
  - It lists MAJOR-1 full fix, MINOR-6 code fix, NIT-1, NIT-6 and the neptune.json windows observation.
  - It also lists the Parquet and Spark export gaps and the synthesisModel sharing.
  - It states the gated suite is still NOT RUN.
- Extras, from the R1-1 report:
  - storage MODULE.md "Done" records the 158 passed storage count.
  - The removed items are the stale "TTL up to 48 hours" known issue (MINOR-5 was fixed) and the stale NIT-4 "not re-checked" line.
  - I added the 305 s `sbt check` line to docs/storage.md and storage MODULE.md.

COMMANDS RUN: Python scripts to edit the files; `git diff`, `grep`, `awk` and `ls` to check them (all succeeded).
TEST RESULTS: not applicable. No sbt run, per the work order. Test numbers are copied from repair-01-storage-engineer.md and repair-01-domain-engineer.md.
VALIDATION RESULTS:
- Every added line is 90 characters or less, except STATUS table rows, which are exempt.
- The em dash count is 0 in all four files.
- The linked evidence file docs/agent-runs/phase-07/output-sbt-check-r1.txt exists.
- No documented command was added, so nothing needed replaying.
ASSUMPTIONS:
- The R1-2 report's `sbt check` totals have no project names, so I wrote them "as printed, without project names". I did not map the totals to modules.
- The docs/storage.md header count (`sbt check` after R1-2) is the same totals.
RISKS:
- I did not read the code. All behavior claims come from the repair reports and the review report.
- The `_SUCCESS` rule for absent outcome folders (NIT-4 of Phase 05) is stated as "not implemented by this change". The reports do not say whether it is implemented elsewhere.
- The line "20 documents ..." style test counts are not documented, only the totals.
- Nothing was committed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review 2 (fresh code-reviewer, review-report-02.md), then the Phase 07 human gate.
