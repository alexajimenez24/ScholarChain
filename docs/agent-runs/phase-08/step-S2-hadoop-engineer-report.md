HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier default

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 08 step S2. Run the Phase 04 LocalRunner pipeline (J01 to J07) on the S1 snapshot 2026-11-24, with no code change.

RUN
- Run ID: run-834f71c82961a194. Snapshot: 2026-11-24. Reducers 4, maxTeam 300, maxBlock 200 (defaults).
- Output: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24/hadoop-out (452 MB). `git check-ignore -v` confirms it is git-ignored through datasets/samples/local/.gitignore (`*`).
- Layout: works, normalized, match-candidates, teams, coauthor-pair-candidates, export, run-manifests (manifest and summary json) and _local-tmp.
- Wall time: 570 s (9 min 30 s) for the successful attempt, and sbt reported the run itself as 557 s. This is far inside the 90-minute limit, so no step-down to 25,000 or 10,000 works was needed.
- Heap: the forked job JVM was capped at 2 GB (-Xmx2g), and the sbt launcher ran at -Xmx512m. No other sbt or JVM was running. Peak heap use was not measured; the run finished with no OOM.

ATTEMPTS (all recorded)
1. Attempt 1 failed. I passed relative paths, and the forked JVM's working directory is modules/hadoop. The error was `Input path does not exist: file:/home/ajcreator/scholarchain/modules/hadoop/datasets/samples/local/2026-11-24/dblp-sample.xml`, in 4 s. This was a path error and not a memory failure.
2. Attempt 2 used absolute paths and succeeded.
- The build's Test heap is -Xmx1g, so I raised it for this run only with `set hadoop / Test / javaOptions := Seq("-Xmx2g")`. No build file was changed.

COUNTERS PER JOB
- J01:
  - INPUT_RECORDS 100000, ACCEPTED_RECORDS 100000, UNIQUE_RECORDS 100000.
  - DBLP_TYPE_ARTICLE 44244, DBLP_TYPE_BOOK 110, DBLP_TYPE_INCOLLECTION 720, DBLP_TYPE_INPROCEEDINGS 54926.
  - No rejects.
- J02-OPENALEX:
  - INPUT 49749, ACCEPTED 49730, REJECTED 19.
  - WORKS_WRITTEN 49730, ABSTRACTS_WRITTEN 25078, INSTITUTIONS_WRITTEN 91960, TOPICS_WRITTEN 148950.
- J02-CROSSREF: INPUT 4516, ACCEPTED 4516, WORKS_WRITTEN 4516.
- J03 (exact identifiers):
  - INPUT 154246, ACCEPTED 154246.
  - EXACT_CANDIDATES 262613, CONFLICT_RECORDS 180, DUPLICATE_RECORDS 7.
  - MISSING_DOI 10175, MISSING_KEY 10175.
- J04 (blocking):
  - INPUT 154246, ACCEPTED 154246.
  - BLOCKED_CANDIDATES 4342656, FALLBACK_BLOCKS_COMPARED 51667.
  - MISSING_KEY 12, MISSING_TITLE_KEY 12.
- J05 (teams):
  - INPUT 154246, ACCEPTED 154229, SKIPPED 17 (WORKS_WITHOUT_AUTHORS 17).
  - TEAMS_WRITTEN 154229, AUTHOR_PAPERS_WRITTEN 545366, CANDIDATE_PAIRS_BEFORE_CAP 980859.
  - Team-size buckets:

    | Bucket | Teams |
    |---|---|
    | 1 | 173 |
    | 2 to 5 | 138060 |
    | 6 to 20 | 15916 |
    | 21 to 100 | 79 |
    | 101 to 300 | 1 |
- J06 (pairs): INPUT 154229, ACCEPTED 154229, EMITTED_PAIRS 980859, UNIQUE_RECORDS 980859.
- J07 (export):
  - INPUT 2075610, ACCEPTED 2075610, CONFLICT_RECORDS 1114, DUPLICATE_RECORDS 369096.
  - EXPORTED_WORKS 154246, EXPORTED_MENTIONS 545366, EXPORTED_TEAMS 154229, EXPORTED_PAIRS 980859.
  - EXPORTED_AUTHORPAPERS 545366, EXPORTED_TOPICS 2711, EXPORTED_INSTITUTIONS 10288, EXPORTED_VENUES 7503.

KEY METRICS
- Maximum team size: 133, for dblp:journals/nar/BatemanMOMAAABB21. No team exceeded the 300 cap, so none was skipped as oversize.
- Largest pair expansion: 8778 (133*132/2 = 8778).
- Largest blocking group: r:wang_y with 1578 members. This is above maxBlock 200, so the block was not compared in full. The fallback path handled it (FALLBACK_BLOCKS_COMPARED 51667 across all blocks).

OPENALEX AND CROSSREF JOINS WITH PARTIAL COVERAGE
- J03 read 154246 work records: 100000 DBLP, 49730 OpenAlex and 4516 Crossref.
- It produced 262613 exact candidates.
- 10175 records had no DOI, and they are counted as MISSING_DOI and MISSING_KEY. These are mostly DBLP works without a DOI; I did not split the count by source.
- 180 conflicts and 7 duplicates were counted and not dropped.
- Rows per source are the matched side only. The OpenAlex set covers 49749 of the 90285 requested DOIs, so works with no OpenAlex row simply get no enrichment. No stage failed, and all counters reconcile (INPUT = ACCEPTED + rejected or skipped).
- I did not compute a per-source DOI match rate. The per-source split of EXACT_CANDIDATES was not examined; that is a gap.
- 19 OpenAlex records were rejected in J02. They are in the J02 rejects-v1 output, and I did not inspect their reasons.
- No ORCID file existed, so the ORCID stage was skipped.

FILES CREATED: none in the repo that git tracks. The run output is only under the git-ignored datasets/samples/local/2026-11-24/hadoop-out. Logs are at /tmp/claude-1000/s2run1.log (failed attempt) and /tmp/claude-1000/s2run2.log (success).
FILES MODIFIED: none.
COMMANDS RUN:
- `sbt -batch "hadoop/Test/runMain scholarchain.hadoop.LocalRunner ..."` with relative paths: failed, rc=1.
- `sbt -batch 'set hadoop / Test / javaOptions := Seq("-Xmx2g")' "hadoop/Test/runMain scholarchain.hadoop.LocalRunner --dblp <abs> --output <abs>/hadoop-out --snapshot 2026-11-24 --openalex <abs> --crossref <abs>"`: success, rc=0.
- `git check-ignore -v`, plus python reads of run-manifests/run-834f71c82961a194-summary.json.
TEST RESULTS: I ran no `sbt hadoop/test` or `sbt check`, because there was no code change.
VALIDATION RESULTS: as above. The summary json holds all stage counters. The output paths are `<stage>/snapshot=2026-11-24/run_id=run-834f71c82961a194/`.
ASSUMPTIONS:
- The output goes under the snapshot's own git-ignored folder.
- The 2 GB cap applies to the forked job JVM.
- The OpenAlex and Crossref files were passed in full.
- The pipeline's default caps were kept.
RISKS:
- The run is the Phase 04 logic (hadoop-v2) against real-scale data.
- OpenAlex coverage is partial, so enrichment is incomplete.
- The large DBLP block (wang_y, 1578) is not fully compared.
- hadoop-out contains .crc files and _local-tmp (scratch).
- Notes for MODULE.md:
  - LocalRunner needs absolute paths, because the forked cwd is modules/hadoop.
  - Run time at 100k works is about 9.5 min with a 2 GB heap.
  - The build's Test heap is 1g; I did not test whether the run fits in 1g.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: STATUS.md update by documentation-engineer (S2 done), then Phase 08 step S3.
