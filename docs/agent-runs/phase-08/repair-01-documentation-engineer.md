HARNESS: claude-code
MODEL: claude-sonnet-5-5 (standard tier, role default)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 08 repair R1-4. It covers MINOR-3 wording, NIT-2, NIT-3 and NIT-6, the doc lines the three repair reports flag, and STATUS.md for R1-3 and R1-4.
FILES CREATED: none
FILES MODIFIED (all under /home/ajcreator/scholarchain):
- docs/storage.md: the Parquet gap is closed for the vector loader only; the Neptune and DynamoDB loaders have not run on the JSON-lines export; a contract test is carried forward to storage-engineer.
- docs/data-lineage.md: 00b had one nonzero invariant (`componentsNotConverged`); `pathRequestedNotFound` was added in 00d.
- docs/embeddings.md:
  - Heap estimate reworded as unmeasured: 351 MB for 31,341 chunks is about 11.5 KB per chunk, so about 2.5 GB.
  - Exactly 32 chunks accepted and a 33rd refused.
  - Text check before version check.
  - queueCapacity wording: only waiting documents are bounded.
  - Local ingestion fails before any load when the rows read differ from the load manifest, and report counts come from the rows read.
- PROJECT_MEMORY.md: the componentMaxIterations ratio is now about 2.1 times the 47 iterations (00d).
- modules/storage/MODULE.md: the MINOR-3 wording, an R1-2 entry (manifest row check, report counts from rows read, queueCapacity wording, 184 passed with 1 ignored), and the full-export heap estimate (about 2.5 GB, not 3.4 GB).
- modules/rag/MODULE.md: an R1-1 entry (exact chunk bound, `decide` order, 3 new tests, 66 of 66, no API change).
- modules/spark/MODULE.md: an R1-3 entry (`abstracts` `keyRule` text, no API change, `LogicVersion` stays `spark-v4`).
- STATUS.md:
  - "Where things stand" updated: the repair is done, rag has 66 tests, and the next step is the human gate.
  - Repair 1 row set to done.
  - Note R1-3 added, with the 7 test lines and "14 project test runs passed".
  - Note R1-4 added.
  - Stale "Next is R1-2" and "Next is R1-3" lines removed.
  - The full-export heap line reworded as an estimate.
  - "Carried forward from Phase 08" now lists MINOR-3 code part, MINOR-4 and MINOR-5 as open, and the full-export heap item reworded as an unmeasured estimate.
COMMANDS RUN: `grep`, `sed -n`, `awk`, `cat`, `ls` for reading and checking; `python3` heredocs for the exact-string edits (each asserted its target text exists). All exited 0.
TEST RESULTS: none run; no sbt, as ordered. The sbt figures recorded come from `output-sbt-check-r1.txt` and the spark-engineer report.
VALIDATION RESULTS:
- No em dash in STATUS.md or docs/embeddings.md (grep count 0).
- Added prose lines are 90 characters or less. The only long added lines are table rows in PROJECT_MEMORY.md, which follow the existing table style.
- The leftover "3.4 GB" figure appears nowhere in docs, MODULE.md or PROJECT_MEMORY.md.
ASSUMPTIONS: The 120-test line is not labelled by project in the check output; I recorded the figures as printed and did not name projects for them. The phrase "closed for the vector loader only" is my reading of the work order's wording.
RISKS:
- Not done: no link check beyond the existing paths, and no replay, because no new commands were documented.
- The `s3vectors.json` already on disk still has the old keyRule text until Spark reruns (stated in the spark MODULE.md entry).
- `docs/embeddings.md` lines 70 and 278-282 and some `docs/storage.md` and `docs/data-lineage.md` lines exceed 90 characters. They are table or earlier content I did not touch.
- The git-ignored `modules/hadoop/datasets/` is still present, untouched.
MANUAL ACTION REQUIRED: human gate items: remove `modules/hadoop/datasets/` and do not commit it (MINOR-4); decide on the step 00 step-down deviation (MINOR-5); ratify and commit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate for Phase 08. Review 2 is not needed. The MINOR-3 code part (Neptune and DynamoDB JSON-lines loader contract test, storage-engineer) is carried forward.
