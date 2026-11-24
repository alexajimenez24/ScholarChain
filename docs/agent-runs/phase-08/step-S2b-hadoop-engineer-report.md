HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier default

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 08 step S2b. Run the Phase 04 LocalRunner pipeline (J01 to J07) on the 25,000-work subset, with no code change.

RUN
- Run ID: run-03a81f6f54f3fc3c. Snapshot label passed: 2026-11-24, the same as the S1b retrieval.json parentSnapshot. Reducers 4, maxTeam 300, maxBlock 200 (defaults).
- Output: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/hadoop-out (105 MB). `git check-ignore -v` confirms it is git-ignored (datasets/samples/local/.gitignore, pattern `*`).
- Layout: works, normalized, match-candidates, teams, coauthor-pair-candidates, export, run-manifests (run-03a81f6f54f3fc3c.json and run-03a81f6f54f3fc3c-summary.json) and _local-tmp. Stage output paths are `<stage>/.../snapshot=2026-11-24/run_id=run-03a81f6f54f3fc3c/`.
- Wall time: 187 s (3 min 7 s) including sbt startup. sbt reported 170 s. This is far inside 90 minutes, there was no memory failure and no step-down to 10k is needed.
- It succeeded on the first attempt, with a 2 GB heap on the forked JVM, absolute paths and one sbt at a time. Peak heap was not measured. Log: /tmp/claude-1000/s2b-run.log.

COUNTERS PER JOB
- J01:
  - INPUT_RECORDS 25000, ACCEPTED_RECORDS 25000, UNIQUE_RECORDS 25000.
  - DBLP_TYPE_ARTICLE 11159, DBLP_TYPE_BOOK 19, DBLP_TYPE_INCOLLECTION 186, DBLP_TYPE_INPROCEEDINGS 13636.
  - No rejects.
- J02-OPENALEX:
  - INPUT 12433, ACCEPTED 12427, REJECTED 6.
  - WORKS_WRITTEN 12427, ABSTRACTS_WRITTEN 6292, INSTITUTIONS_WRITTEN 23028, TOPICS_WRITTEN 37239.
- J02-CROSSREF: INPUT 1137, ACCEPTED 1137, WORKS_WRITTEN 1137.
- J03 (exact identifiers):
  - INPUT 38564, ACCEPTED 38564.
  - EXACT_CANDIDATES 65766, CONFLICT_RECORDS 39, DUPLICATE_RECORDS 1.
  - MISSING_DOI 2518, MISSING_KEY 2518.
- J04 (blocking):
  - INPUT 38564, ACCEPTED 38564.
  - BLOCKED_CANDIDATES 848647, FALLBACK_BLOCKS_COMPARED 3040.
  - MISSING_KEY 1, MISSING_TITLE_KEY 1.
- J05 (teams):
  - INPUT 38564, ACCEPTED 38559, SKIPPED 5 (WORKS_WITHOUT_AUTHORS 5).
  - TEAMS_WRITTEN 38559, AUTHOR_PAPERS_WRITTEN 136266, CANDIDATE_PAIRS_BEFORE_CAP 241686.
  - Team-size buckets: 1: 43; 2 to 5: 34525; 6 to 20: 3970; 21 to 100: 21; 101 to 300: 0.
- J06 (pairs): INPUT 38559, ACCEPTED 38559, EMITTED_PAIRS 241686, UNIQUE_RECORDS 241686.
- J07 (export):
  - INPUT 515342, ACCEPTED 515342, CONFLICT_RECORDS 589, DUPLICATE_RECORDS 84845.
  - EXPORTED_WORKS 38564, EXPORTED_MENTIONS 136266, EXPORTED_TEAMS 38559, EXPORTED_PAIRS 241686.
  - EXPORTED_AUTHORPAPERS 136266, EXPORTED_TOPICS 1945, EXPORTED_INSTITUTIONS 5318, EXPORTED_VENUES 5326.

KEY METRICS
- Maximum team size: 75, for dblp:journals/fini/BoedhoeHSAAAAAB19. No team exceeded the cap of 300.
- Largest pair expansion: 2775 (75*74/2).
- Largest blocking group: r:wang_y with 361 members. This is above maxBlock 200, so it went through the fallback path.
- Work records into J03: 38564 = 25000 DBLP + 12427 OpenAlex + 1137 Crossref. All counters reconcile (INPUT = ACCEPTED + rejected or skipped).
- Reject output: J02-OpenAlex rejected 6 records. They are in the rejects output and I did not inspect their reasons.
- There was no ORCID file, so the ORCID stage was skipped.

FILES CREATED: none tracked by git. Run output is only under the git-ignored hadoop-out folder.
FILES MODIFIED: none.
COMMANDS RUN:
- `sbt -batch 'set hadoop / Test / javaOptions := Seq("-Xmx2g")' "hadoop/Test/runMain scholarchain.hadoop.LocalRunner --dblp <abs> --output <abs>/hadoop-out --snapshot 2026-11-24 --openalex <abs> --crossref <abs>"`: rc=0.
- `git check-ignore -v`, `ls` and `du`, plus a python read of run-manifests/run-03a81f6f54f3fc3c-summary.json.
TEST RESULTS: I ran no `sbt hadoop/test` or `sbt check`, because there was no code change.
VALIDATION RESULTS: as above. The summary json holds all stage counters and the maximum-team, pair-expansion and block metrics.
ASSUMPTIONS:
- The snapshot label 2026-11-24 is the parent snapshot and matches S1b.
- Default caps were kept, with no ORCID file.
- Output sits under the subset's own git-ignored folder.
RISKS:
- The 25k subset keeps only DBLP records with at least 2 authors (retrieval.json minAuthors 2), so the team-size statistics are not comparable to the 100k run. It also has 25 works per year of coverage fewer than the parent, being a per-year quota of 1000.
- OpenAlex and Crossref are the filtered subset, so enrichment coverage is partial.
- hadoop-out contains .crc files and _local-tmp (scratch).
- Notes for MODULE.md: at 25k works the run takes about 3 min with a 2 GB heap. LocalRunner needs absolute paths.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: STATUS.md update by documentation-engineer (S2b done), then the Spark rerun at 25,000 works on hadoop-out/export/snapshot=2026-11-24/run_id=run-03a81f6f54f3fc3c.
