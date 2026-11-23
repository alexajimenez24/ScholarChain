# Phase 06 human gate

## How Phase 06 was produced

The owning agents did the work through the orchestrator, one owner at a time:
build-engineer (forked Spark test JVM with the JDK module opens and a 1 GB heap; the
Scala standard library stays out of the job jar), spark-engineer (T1: jobs 1 to 9 with
known-graph tests; T2: jobs 10 and 11, the run manifest, the local integration run and
the full sbt check), documentation-engineer (`docs/spark-jobs.md`, the module notes and
STATUS), and two fresh code-reviewers. The work order and my addendum are in `prompt.md`.
Every step is in `run-log.md`, and every agent report is saved word for word in this
folder (also gathered in `phase-report-agent-reports.md`). Tool versions are in
`versions.md`.

As in Phases 03 to 05, I gave the agents reference material from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-06/`, with a
`REFERENCE.md` listing what had to change. spark-engineer adapted it to this
repository's Phase 04 and Phase 05 outputs and changed one algorithm against the
reference: label propagation now gives a node's own vote the weight of its strongest
edge, because the reference version oscillated on weighted edges.

## What was checked

- Spark reads only this repository's Phase 04 Hadoop run (`run-6d1175dbfdb874a9`) and
  Phase 05 er-v1 decision run (`run-02cecabcf3e68312`), through their run manifests.
  It never reads raw data or `datasets/gold/`.
- Known-graph tests: `sbt sparkAnalytics/test` passes 33 of 33 after the repair. Review 1
  re-checked every expected value in the known-graph suites by hand and found them
  correct. They cover pair weights and distinct coauthors, components, community
  assignment, paths, k-core, bridge measures and windows.
- No full graph is collected to the driver; only counts, one top-1 row and the
  requested path rows are. Graph limits come from the `graph` config block.
- Final local run `run-769e0d672ecd3c19` (`output-spark-local-run-r1.md`): 81
  researchers, 42 works, 205 graph nodes, 409 edges, 29 components (largest 6), 29
  communities, 4 of 120 requested path pairs connected, 0 rejected input rows, 94
  invariants checked, all 0.
- Full `sbt check` exited 0 twice: at the end of step 03 and after repair R1-1.
- Review 1 (`review-report.md`) returned REQUEST_CHANGES: 1 MAJOR, 5 MINOR, 4 NIT.
  After one repair round (`repair-01*.md`, summary in `repair-01.md`), Review 2
  (`review-report-02.md`) confirmed every finding fixed and returned APPROVE WITH
  CARRIED-FORWARD ITEMS, with 3 new NITs and no critical, major or minor findings.
- No test-engineer step was part of this work order, so there is no `test-report.md`.

## Limits of the sample

- Every sample paper is from 2014, so the 2000-2009 and 2015-2026 windows are empty and
  the two non-empty windows are identical. Temporal comparison is implemented and
  tested on fixtures, but the sample cannot show change over time.
- The sample graph has no edges between communities, so the bridge measures are
  exercised only by the hand-checked test graphs.

## Decisions

- MAJOR-1 identity policy: I ratify policy (a), which the orchestrator routed from my
  addendum. When a merged researcher group contains an UNRESOLVED or CONFLICT decision,
  the weakest accepted link on that chain is dropped, so contradicted pairs are never
  merged. On the sample this refused 3 accepted links and kept apart the 3 UNRESOLVED
  pairs that had been merged (79 researchers became 81). I accept the cost: one probable
  person can appear as two researchers, and works with a team above every source list
  went from 1 to 3. Policy (b), keeping the merges and only reporting them, is rejected
  because it breaks "preserved, never merged".
- Bounded EMR Spark run: NOT RUN. Cloud work starts in Phase 12. The bounded EMR run
  happens in the single cloud window together with the Hadoop EMR run.
- The orchestrator did not obey the stop hook's repeated request for `sbt check` while
  agents were running, because my addendum allows one sbt at a time and schedules the
  full check. I accept this; the full check ran and passed twice.
- This session ran with Claude Code's auto mode on, although STATUS.md says to keep it
  off until the Phase 11 guard fixes. The guard and permission rules stayed active,
  nothing was committed by an agent, and no cloud resource was touched. From Phase 07
  on, auto mode stays off.

## Accepted, with known gaps

The Phase 06 Spark collaboration-analysis pipeline is accepted as committed. Carried
forward:
- The config has no upper bound on the size of a contradicted identity group; a
  domain-engineer config field is needed before a full DBLP run (Phase 15).
- The contradiction cut is greedy and can drop more accepted links than strictly needed
  (spark-engineer, er-v2 together with Phase 05 MINOR-1).
- No paper summaries are built, and the config has no limits on summary size or count
  (domain-engineer and spark-engineer, before Phase 08).
- Coauthor edges are stored once per time window, so every graph query must filter on a
  single window (storage-engineer in Phase 07, service-engineer in Phase 10; written in
  the module notes).
- Review 2 NITs: the link count in a `CanonicalSuite.scala` comment says 9 where the
  fixture has 10 (spark-engineer), and two citation and wording fixes in
  `docs/spark-jobs.md` (documentation-engineer, Phase 17).
- The guard blocks harmless commands that name the test-marker file or gate file names
  (factory-engineer, Phase 11, already tracked).
- The github MCP server did not connect during this session; nothing in the phase
  needed it (factory-engineer, Phase 12 with the other MCP items).

## Manual changes

- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 6 is complete. I approve starting Phase 7.
