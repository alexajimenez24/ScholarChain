# Phase 04 human gate

## How Phase 04 was produced

The owning agents did the work through the orchestrator, one owner at a time:
build-engineer (Hadoop test runtime in build.sbt), hadoop-engineer (`modules/hadoop/**`
and its unit tests), test-engineer (the Phase 4 adversarial cases in
`tests/adversarial/`), and documentation-engineer (`modules/hadoop/MODULE.md`,
`docs/hadoop-jobs.md` and the notes). The work order and my addendum are in
`prompt.md`; every step is in `run-log.md` and the STATUS updates.

As in Phase 03, I gave the agents reference material from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-04/` with a
`REFERENCE.md` listing what had to change: the hardened Phase 03 core, this
repository's own samples, the other repository's measured numbers, and the Hadoop-only
scope of the adversarial cases. Each owner adapted the files in its own territory.

## Interruption and recovery

WSL ran out of memory during test-engineer's first run and I had to restart the
computer. The orchestrator then confirmed that build-engineer's and hadoop-engineer's
edits on disk matched their reports and did not re-run them. I changed the order so
that documentation-engineer wrote the Public API section of `modules/hadoop/MODULE.md`
first (`step-03a-documentation-engineer-report.md`), because test-engineer writes
black-box tests from that section. test-engineer deleted its seven partial files and
re-ran from the beginning (`test-report.md`).

## What was checked

- test-engineer's 68 adversarial cases cover adversarial XML (DOCTYPE and entities,
  malformed, truncated, oversize, split boundaries), Unicode, missing fields, duplicate
  IDs, common-name blocks and giant teams, written from the spec and the public API
  only. Five failed on the first run.
- Review 1 (`review-report.md`, fresh code-reviewer) returned REQUEST_CHANGES with
  findings F1 to F11 and judged all five failing tests FAIR.
- Repair round 1: hadoop-engineer fixed all ten briefs, including my encoding rule, with
  19 new unit tests (`repair-01-hadoop-engineer.md`); documentation-engineer corrected
  the module notes and job docs with post-repair numbers
  (`repair-01-documentation-engineer.md`); test-engineer re-ran the adversarial suite
  with no test changes, 68 of 68 passing (`repair-01-test-engineer.md`).
- Review 2 (`review-report-02.md`, fresh code-reviewer) returned APPROVE WITH
  CARRIED-FORWARD ITEMS: F1 to F11 resolved, no ownership breach. Its sbt check passed
  with core 94/94, hadoop 98/98 and adversarial 68/68.
- Local bounded pipeline (LocalJobRunner) after the repair: on the fixtures
  (run-8b3a162615a54f8f) the largest team was 12, the largest pair expansion 66 and the
  largest blocking group 13; on the real samples (run-6d1175dbfdb874a9) the largest team
  was 5, the largest pair expansion 10 and the largest blocking group 18. No records were
  rejected in either run.
- `sbt packageJobs`, run by me after Review 2: see `output-package-jobs.txt`.

## Decisions

- DBLP encoding: the reader decodes with the encoding declared in the XML prolog
  (ISO-8859-1 for DBLP, UTF-8 when none is declared) and rejects bytes that are invalid
  in the declared encoding. A raw 0xE9 in an ISO-8859-1 file reads correctly.
- Repair budget: one repair round per phase; findings left after the re-check are
  carried forward, not repaired in a second round.
- N4: match candidates keep the normalization version `norm-v1`. The pipeline version
  `hadoop-v2` is found through the candidate's run ID and the run manifest; no second
  version field is added.
- test-engineer ran `javap -public` on compiled classes to confirm exact signatures. It
  shows declarations only, the same information as the Public API section, and it was
  disclosed in its report. I accept it for black-box testing.

## Accepted, with known gaps

The Phase 04 Hadoop pipeline is accepted as committed. Carried forward from Review 2:
- N1: reject reasons print raw Scala text (`BadEncodingSome(, UTF-8)`) instead of the
  documented format. Counts and data are correct. Phase 11, hadoop-engineer.
- N2: a declared decode-only charset such as ISO-2022-CN makes J01 fail instead of a
  counted refusal. None of the selected datasets uses one. Phase 11, hadoop-engineer.
- N3: work per fallback block is limited only by the hard pair cap. Phase 11.
- N5: the J03 reducer holds a whole exact-key group in memory. Revisit before the bounded
  EMR run in Phase 15.
- N6: some "pending" wording in `modules/hadoop/MODULE.md` and `docs/hadoop-jobs.md` is
  out of date. documentation-engineer, start of Phase 05.
- N7: no test runs the new reducer wiring as a full job, and the black-box ISO-8859-1
  case is not written. Phase 11, hadoop-engineer and test-engineer.

## Process notes

- The harness moved one of hadoop-engineer's `sbt hadoop/test` runs to the background
  after 120 seconds; it reports that no second sbt ran while it did.
- Some documentation-engineer edits ran alongside an agent's sbt run. There was never
  more than one sbt at a time.
- The orchestrator's final phase report summarized the agent reports instead of pasting
  them; every report is saved word for word in this folder.

## Manual changes

- I restarted the computer after WSL ran out of memory, and ran `sbt packageJobs`.
- Manual changes to agent-owned files: none.

## Commit

Accepted commit: COMMIT_HASH

Phase 4 is complete. I approve starting Phase 5.
