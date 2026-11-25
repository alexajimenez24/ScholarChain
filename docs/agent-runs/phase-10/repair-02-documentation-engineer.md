HARNESS: claude-code
MODEL: sonnet (standard tier, default, no override)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 10 human-approved fix of MINOR-F and MINOR-G (review-report-02.md).
FILES CREATED: none
FILES MODIFIED:
- STATUS.md
- modules/services/MODULE.md
- docs/api.md
COMMANDS RUN: grep and sed reads of the report and the three files; a python replacement script with exact-match asserts (all passed); git diff --stat; an awk line-length check.
TEST RESULTS: none run (docs only, no sbt, as ordered).
VALIDATION RESULTS:
- STATUS.md: the "Where things stand" Phase 10 bullet now says re-check APPROVE (0 CRITICAL, 0 MAJOR, 7 MINOR carried forward), services tests 77 (query 22, common 29, graph 16, worker 10), and that the 77 is the reviewer's count from reading the suites.
- STATUS.md: table row 4 is COMPLETE and row 5 is COMPLETE; the repair bullet says the re-check approved it and that MINOR-F and MINOR-G are fixed.
- STATUS.md: the test-count line now reads 73 before repair and 77 after.
- STATUS.md: the next step is the human gate, and the stale "Verify ... 73 ... before any repair" line is replaced by "expect 77, not yet observed" and "sbt check last exit 0 after the repair".
- MODULE.md: counts table updated (common 29, query 22, total 77 with the reviewer-count caveat) and "still to come" replaced by "Review done ... APPROVE".
- api.md: the 503 row now reads "store full (1000 in-flight records)", and a new note after the 1000-record line covers eviction of the oldest Succeeded or Failed record and its claim, 404 for an evicted job, result or explain, and a replay of an evicted key re-running the query.
- Line lengths: the lines I added are within 90 characters, except the api.md 503 table row (109 characters; table rows in this file are already long). STATUS.md has other lines over 90 characters, in tables or from earlier work; I left them as they were.
ASSUMPTIONS: the 4 added tests are split as query +3 and common +1, from the reviewer's report. Per-project totals for graph (16) and worker (10) are unchanged.
RISKS: the 77 is read from the suites, not observed in a run. The MODULE.md repair bullet still says per-project totals were not observed; that remains true. Not done: MINOR-A to E (service-engineer code). Nothing committed.
MANUAL ACTION REQUIRED: human gate for Phase 10; the human commits.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate, Phase 10.
