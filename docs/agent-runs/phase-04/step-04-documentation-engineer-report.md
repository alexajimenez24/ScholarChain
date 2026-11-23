HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier default, as the work order said)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 04, task T4. I wrote docs/hadoop-jobs.md, finished modules/hadoop/MODULE.md, and updated STATUS.md for T3 and T4. I ran no sbt.

FILES CREATED:
- /home/ajcreator/scholarchain/docs/hadoop-jobs.md
  - It covers the J01-J07 job graph, key-value contracts, partition strategy, counters, output layout, reject outputs and the local run command.
  - It has the observed results of both local runs and the T3 adversarial results.
  - It lists the 5 red tests as open items and carries the ISO-8859-1 question for the human.
  - Every number is tagged HR (agent-report.md) or TR (test-report.md).

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/hadoop/MODULE.md
  - The T4 placeholder in Done is replaced with the observed numbers of both runs and the test counts.
  - Known issues now holds hadoop-engineer's risks, the 4 open T3 items (5 red tests) and the T3 imprecisions. These are marked as awaiting the fairness verdict, not as defects.
  - Two Public API statements are corrected and marked as observed in T3:
    - The J01 status prefix is `Oversize:`, `BadEncoding:` or `Truncated:`, not `STATUS:`.
    - The INVALID_IDENTIFIER behavior line now notes that the counter was 0 when T3 observed it.
- /home/ajcreator/scholarchain/STATUS.md
  - T3 is done, with this result: final sbt check exit 1; core 94/94, hadoop 79/79, adversarialTests 68 total, 63 passed, 5 failed, left red.
  - T4 is done. Review 1 is the next task and also gives the fairness verdict on the 5 red tests.
  - Notes T3 and T4 are added. The Current state section now notes that the docs' numbers may change after the repair round.
  - The ISO-8859-1 versus strict UTF-8 question is added under "Open decisions for the human".

COMMANDS RUN:
- `ls docs; ls docs/agent-runs/phase-04`.
- An `awk` line-length check on the three files and a `grep -c` for the em dash.
- `wc -l`, `grep -n` and `sed -n` on STATUS.md to check its structure.
- The edits were made with python3 heredocs and one `sed`/`cp` repair of STATUS.md.

TEST RESULTS: None run, as the work order forbids sbt. The test numbers quoted come only from the saved T2 and T3 reports.

VALIDATION RESULTS:
- Line length and dash: no prose line over 90 characters in docs/hadoop-jobs.md or modules/hadoop/MODULE.md. The only over-length lines are table rows and one code-block command line. STATUS.md has 3 lines over 90 in all, which I did not isolate. At least one is a table row; the rest may be pre-existing. No em dash in any of the three files.
- Mid-task error, repaired: my first STATUS.md edit duplicated the Phase 02, 03 and 04 sections (it matched the Phase 02 "| Review 1 |" row first). I rebuilt the file from lines 1-241 and 423-end of the bad copy.
  - The result is 366 lines (342 before, plus the 24 net lines I added).
  - Each section heading and each Phase 04 table row appears once, and the Phase 02 Review 1 row is intact.
- Links and paths: docs/hadoop-jobs.md links to ../modules/hadoop/MODULE.md and agent-runs/phase-04/agent-report.md and test-report.md. Those files were present in my initial reads. I did not run a separate link check.
- Replay: the LocalRunner command is not replayed, because it needs sbt. It is documented as reported by hadoop-engineer.

ASSUMPTIONS:
- HR means the hadoop-engineer report and TR the test-engineer report, as defined at the top of docs/hadoop-jobs.md.
- The 5 counters that TR calls undocumented (DBLP_TYPE_ARTICLE, UNIQUE_RECORDS, WORKS_WRITTEN, TOPICS_WRITTEN, MISSING_DOI) are listed as TR reported them.
- I said in docs/hadoop-jobs.md that the module notes also list WORKS_WITHOUT_AUTHORS, EXACT_CANDIDATES and BLOCKED_CANDIDATES, which neither report exercises by name in the sections I used.

RISKS:
- All numbers can change after the repair round (fallback keys, INVALID_IDENTIFIER, J03 counters, argument parsing). The docs say so.
- The Public API section is corrected only where TR observed a contradiction.
  - The other imprecisions (DOCTYPE internal-subset tolerance, name-block counting in source clusters and the `r:` prefix, DUPLICATE_RECORDS semantics, largestPairExpansion counting refused teams) are recorded under Known issues, not rewritten in the API text.
  - The STATUS.md Current state git list is taken from the session's git status snapshot at the start, not a fresh `git status`.
- I did not verify the HR and TR numbers against run output files. I used only the reports, as instructed.

MANUAL ACTION REQUIRED: The human decides how DBLP's ISO-8859-1 declaration should interact with strict UTF-8 (raised by test-engineer; untested).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review 1, a fresh code-reviewer on the whole Phase 04 diff, including the fairness verdict on the 5 red adversarial tests.
