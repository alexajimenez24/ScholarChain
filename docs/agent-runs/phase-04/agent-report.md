HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier default; no per-task override in the work order)

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 04 T2: Hadoop MapReduce pipeline, required jobs 1-7 and the operational behavior in docs/agent-runs/phase-04/prompt.md. The EMR smoke-run script is infra-engineer's, so I did not write it. I ran no cloud job.

FILES CREATED (all new, nothing outside my territory)
modules/hadoop/src/main/scala/scholarchain/hadoop/ (15 files, Scala 3):
- Records.scala, Identifiers.scala, Similarity.scala, Counters.scala, DblpEntities.scala
- DblpInputFormat.scala (splittable input format, record scanner, record reader), DblpParser.scala, SourceNormalizers.scala
- TeamLogic.scala, ExactLogic.scala, BlockLogic.scala, ExportLogic.scala
- Jobs.scala (every Mapper and Reducer, plus KeyPartitioner), Pipeline.scala (driver, paths, run summary, FsText), LocalRunner.scala
tests/module-unit/hadoop/: TestSupport.scala, DblpParserSuite.scala, NormalizerSuite.scala, JobLogicSuite.scala, PipelineIntegrationSuite.scala, RejectsAndLimitsSuite.scala
tests/module-unit/hadoop/resources/fixtures/ (copies of this repository's samples):
- crossref-works-fixture.jsonl, dblp-fixture.xml, openalex-works-fixture.jsonl, orcid-records-fixture.jsonl, copied from datasets/samples/fixtures
- dblp-real-sample.xml, copied from datasets/samples/real/dblp-sample.xml

FILES MODIFIED: none. I deleted tests/module-unit/hadoop/.gitkeep because the folder now has content. I did not edit core, build.sbt or MODULE.md.

COMMANDS RUN (all in the foreground with timeouts, one sbt at a time; source toolchain/env.sh first)
- sbt hadoop/compile
- sbt "hadoop/Test/runMain scholarchain.hadoop.LocalRunner ..." (a smoke run)
- sbt hadoop/test (twice, with failures)
- sbt "hadoop/testOnly scholarchain.hadoop.NormalizerSuite"
- sbt fmt
- one sbt invocation with two LocalRunner runs: the fixtures and the real samples
- sbt check (exit 0)

TEST RESULTS
Hadoop module: 79 tests pass in 5 suites (DblpParserSuite 22, NormalizerSuite 10, JobLogicSuite 20, PipelineIntegrationSuite 17, RejectsAndLimitsSuite 10). There are 6 suites in all; TestSupport.scala is a helper file, not a suite.

sbt check tail:
```
Test run scholarchain.hadoop.PipelineIntegrationSuite finished: 0 failed, 0 ignored, 17 total 38.468s
[info] Passed: Total 79, Failed 0, Errors 0, Passed 79
[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run
[success] Total time: 70 s (0:01:10.0), completed Nov 22, 2026, 9:54:28 PM
```
Core still passes 94 of 94 in the same run.

Failures I hit and fixed (all before the final green run):
1. My unclosed-record recovery mislabeled a recovered, complete record as truncated.
2. The split filter for recovered records (`offset < end`) was missing. A bash command had been blocked by the guard, so that edit never applied.
3. Several of my own test inputs used invalid IDs (`W1`, `10.1/x`).
4. One expected value was wrong: 46 distinct candidate IDs, not 51 (see J03 below).
None of these changed a hand-derived count.

VALIDATION RESULTS

Job graph (stage folders are `<root>/<stage>/snapshot=<YYYY-MM-DD>/run_id=<run-xxxxxxxxxxxxxxxx>`). Map-output compression is always on (DefaultCodec). Final outputs are gzip by default. Each stage writes a `_counters.json` next to `_SUCCESS`.
- J01 DBLP extraction: `DblpInputFormat` -> `DblpExtractMapper` -> `UniqueReducer` -> `works/source=dblp`.
- J02 normalization, map only, one run per source: `SourceNormalizeMapper` -> `normalized/source={openalex,crossref,orcid}/{works,topics,institutions,abstracts,researchers}`.
- J03 exact IDs: `WorkExactMapper` and `ProfileExactMapper` (MultipleInputs) -> `ExactJoinReducer` -> `match-candidates/kind=exact`.
- J04 blocking and features: `BlockingMapper` -> `BlockingReducer` -> `match-candidates/kind=blocked` (plus `stats/`).
- J05 teams: `TeamMapper` -> `TeamReducer` -> `teams/{teams,authorpapers,stats}`.
- J06 pairs: `PairMapper` -> `UniqueReducer` -> `coauthor-pair-candidates`.
- J07 Spark export: `ExportWorkMapper`, `ExportTeamMapper`, `ExportPairMapper`, `ExportAuthorPaperMapper`, `ExportTopicMapper`, `ExportInstitutionMapper` -> `ExportReducer` -> `export/{works,mentions,teams,pairs,authorpapers,topics,institutions,venues}`.
- Run manifest and summary: `run-manifests/<runId>.json` and `<runId>-summary.json`.
- Mapping to the work order's job numbers: 1=J01, 2=J02, 3=J03, 4 and 5=J04 (blocking mapper plus features in the reducer), 6=J05 and J06, 7=J07.

Mapper and reducer key-value contracts
- J01 map: (LongWritable byte offset, Text one complete DBLP record) -> (Text workId, Text WorkRecord JSON). A unit that is not a complete record arrives with a negative key `-(offset+1)` and a value `STATUS:<head>`, and is rejected. Reduce: (workId, [JSON]) -> one line, the smallest JSON.
- J02 (map only): (offset, source JSON line) -> named outputs. The default output stays empty.
- J03 map: (offset, WorkRecord or ExternalResearcher JSON) -> (Text `doi:`, `arxiv:`, `openalex:` or `orcid:` key, IdRef JSON). Reduce: (key, [IdRef]) -> MatchCandidate lines.
- J04 map: (offset, WorkRecord) -> (`r:<last>_<initial>`, MentionSummary) per author and (`wk:<year>:<two longest title tokens>`, WorkSummary) per work. Reduce: (block, members) -> MatchCandidate lines, streamed and buffered only up to the hard cap (10 x max-block).
- J05 map: (offset, WorkRecord) -> (workId, work JSON). Reduce: (workId, [JSON]) -> a team line plus one author-paper line per mention, into named outputs.
- J06 map: (offset, PublicationTeam) -> (`mentionA|mentionB|workId`, CoauthorPairCandidate). Reduce: UniqueReducer.
- J07 map: (offset, record) -> (`<partitionKey>\t<kind>\t<id>`, JSON). The partition key is the work ID for work, mention, team, pair and author-paper records, and the entity's own ID for topics, institutions and venues. Reduce: (key, [JSON]) -> the `<kind>/part-r-NNNNN` file, smallest JSON wins.

Partition strategy and counts
- `KeyPartitioner` is the explicit partitioner on every shuffle. It takes Hadoop's byte hash of the key up to the first TAB, modulo the reducer count.
- The reducer count is `--reducers`, default 4. J02 is map only, 0 reducers.
- Local runs used 4 reducers (the integration tests use 2).
- Observed file counts per stage on the fixtures (4 reducers):
  - 4 part files for J01, J03, J04 and J06.
  - Export: works, mentions, teams, pairs and authorpapers each 4; topics 2; institutions 1; venues 4. Counts below 4 mean no key hashed to the other partitions.
- A test proves a work's records (work, mentions, team, pairs, author papers) share one partition number.
- A test uses 700-byte splits on the DBLP fixture: 8 map splits, and the J01 result equals the single-split run.

Counter names (group `scholarchain`, identical in every job)
- INPUT_RECORDS, ACCEPTED_RECORDS, REJECTED_RECORDS (every reject path increments it), SKIPPED_RECORDS, SCHEMA_FAILURE.
- DUPLICATE_RECORDS, CONFLICT_RECORDS, OVERSIZE_BLOCKS, OVERSIZE_TEAMS, EMITTED_PAIRS, MISSING_KEY.
- Extras: INVALID_IDENTIFIER, PAIRS_REFUSED_BY_CAP, CANDIDATE_PAIRS_BEFORE_CAP, TEAM_SIZE_* buckets, EXPORTED_*.
- In a mapper, INPUT = ACCEPTED + REJECTED + SKIPPED. The tests assert it.

Local bounded pipeline, run 1: this repository's fixtures (LocalJobRunner, 4 reducers)
- Run ID run-8019aba484da34ad, snapshot 2026-11-22. Example path: works/source=dblp/snapshot=2026-11-22/run_id=run-8019aba484da34ad/part-r-0000N.gz.
- J01: input 17, accepted 15, skipped 2 (1 www, 1 proceedings), rejected 0.
- J02: OpenAlex 12 of 12 (12 topic rows, 5 institution rows), Crossref 8 of 8, ORCID 2 of 2.
- J03: input 37, 51 exact candidates, conflicts 0, duplicates 0.
- J04: input 35, 142 blocked candidates, oversize blocks 0.
- J05: 35 teams, 93 author-paper records, 127 pairs before the cap. Buckets: size 1 = 1, 2-5 = 33, 6-20 = 1.
- J06: 127 pairs emitted, 127 unique, 0 oversize teams.
- J07: input 307, duplicates 30, conflicts 0. Exported: works 35, mentions 93, teams 35, pairs 127, authorpapers 93, topics 2, institutions 1, venues 11.
- Maximum observed team size: 12 (dblp:conf/fixa/ArcherTeam22). Largest pair expansion: 66 (12 x 11 / 2). Largest observed blocking group: 13 mentions, `r:archer_a` (`r:baker_b` also has 13).
- Rejected records: 0.

Local bounded pipeline, run 2: datasets/samples/real (DBLP 40, OpenAlex 25, Crossref 25; run ID run-2ed430a7951bb05a)
- J01: 40 in, 40 accepted, all incollection.
- J02: OpenAlex 25 (75 topic rows, 51 institution rows), Crossref 25.
- J03: 109 exact candidates. J04: 309 blocked candidates.
- J05: 90 teams, 219 author-paper records, 186 pairs before the cap; all 90 teams have 2-5 authors.
- J06: 186 pairs emitted. J07: input 711, duplicates 69.
- Maximum team size 5 (largest pair expansion 10). Largest blocking group 18 mentions, `r:drew_m`.
- Rejected records: 0.

Rejects exercised (RejectsAndLimitsSuite, input written in the test, `--max-team 4`, record cap 700 bytes)
- J01: 17 units in = 9 accepted + 1 skipped + 7 rejected, covering:
  - a keyless record (MISSING_KEY 1) and an undeclared entity;
  - an empty title, year 1850 and an empty author name;
  - an oversize record and an unclosed record;
  - 2 duplicates, 1 of them a conflict, and 7 unique works.
- The unclosed record is rejected alone. The two records it swallowed (`j/rec/1`, `j/rec/2`) are recovered and retained.
- J02: OpenAlex 6 lines = 2 accepted + 4 rejected (not JSON, no id, empty title, fractional year). One Crossref line and one ORCID line are rejected.
- J03: MISSING_KEY 7.
- J05: 1 duplicate. J06: OVERSIZE_TEAMS 1, PAIRS_REFUSED_BY_CAP 10, EMITTED_PAIRS 5.
- Reject files are `rejects-v1/part-*` inside each stage folder (a stage folder carries snapshot and run ID). Each line is a core `RejectRecord` with stage, reason, input (truncated to 2000) and runId.
- Common-name block, 25 authors "John Smith 00NN" in one paper:
  - max-block 3: 25 clusters > 3, so OVERSIZE_BLOCKS 1 with a reject and no candidates.
  - max-block 2: hard cap 20 < 25 members, so the block is not buffered, OVERSIZE_BLOCKS 1.
  - That paper's expansion is 300 pairs (25 x 24 / 2), so 301 pairs before the cap with the second paper.

Hand derivations (tests assert these; they matched on the first run apart from the items above)
- DBLP fixture, 17 records: 15 works (11 inproceedings, 4 articles) + 1 www + 1 proceedings.
  - Authors per work 2,3,2,3,2,3,2,3,2,3,2,2,2,1,12 sum to 44.
  - Pairs 1,3,1,3,1,3,1,3,1,3,1,1,1,0,66 sum to 89.
- OpenAlex fixture, 12 works: authors 2,3,2,3,2,3,2,3,2,3,2,2 sum to 29; pairs sum to 22. 12 topic rows (2 distinct), 5 institution rows (1 distinct).
- Crossref fixture, 8 works: authors 2,3,2,3,2,3,2,3 sum to 20; pairs sum to 16.
- Totals: 35 works, 93 authorships (44+29+20), 127 pairs (89+22+16), largest team 12.
- Team buckets: sizes 2-5 = 33 (14 DBLP minus the singleton and the 12-author team, plus 12 OpenAlex and 8 Crossref); 1 singleton; 1 team of 12.
- J03:
  - Work pairs: 8 DOIs held by 3 sources give 3 pairs each (24); 4 DOIs held by 2 sources give 4 more. 28 in total.
  - ORCID links: iD ...0018 has 4 DBLP + 4 OpenAlex + 3 Crossref mentions (11); iD ...0026 has 3+3+1 (7). 18 in total.
  - Profile-DOI links: Carol lists 3 DOIs, Eve lists 2. 5 in total.
  - 28 + 18 + 5 = 51. Those 5 profile links share their candidate IDs with ORCID links, so 46 distinct.
- J04, researcher blocks (pairs between clusters plus links inside a cluster):

  | Block | Candidates |
  |---|---|
  | archer_a | 15+7 = 22 |
  | baker_b | 22 |
  | chen_c | 10+6 = 16 |
  | diaz_d | 10+4 = 14 |
  | wang_w | 10+0 = 10 |
  | evans_e | 3+4 = 7 |
  | fox_f | 6+4 = 10 |
  | gupta_g | 3+2 = 5 |
  | hill_h | 3+2 = 5 |
  | ito_i | 1+2 = 3 |

  - Researcher total 114, plus 28 work-block pairs = 142.
  - archer_a and baker_b each have 13 mentions (5 DBLP + 4 OpenAlex + 4 Crossref).
- J07: inputs 35+35+127+93+12+5 = 307. Duplicates are topics 10, institutions 4 and venue rows 16, so 30. Distinct venues are 6 DBLP streams + 5 OpenAlex sources = 11.
- Real DBLP sample: 40 records, 97 authors, 82 pairs, largest team 5. Two of the 40 records have 5 authors.

ASSUMPTIONS
- Core has no match-candidate or ORCID-profile record, which STATUS already lists as open. I did not edit core. I defined hadoop-local wire records with their own schema versions: `match-candidate-v1`, `external-researcher-v1`, `author-paper-v1`, `abstract-v1`. They are validated by `HadoopValidate`, and their provenance carries these versions.
- This core's `Provenance` is mandatory and typed. Every derived record gets provenance with its own schema version, snapshot and run ID, taken from the task's run context.
- DBLP disambiguates homonyms by numeric suffix. Identical raw DBLP author strings are therefore treated as one source cluster, and different suffixes as distinct clusters with `dblp_homonym_distinct`.
- Teams and pairs are built for every retained work from every source (DBLP, OpenAlex, Crossref). The work order says "every retained work". The same paper from two sources yields two teams, each with source provenance. Entity resolution or Spark must collapse them later.
- A team above `--max-team` (default 300) is not expanded. It is counted, the refused pair count is recorded, and a reject line is written.
- Semantic Scholar is normalized in `Identifiers`, but no selected dataset provides it, so it emits no keys.
- Run ID = `Ids.run(manifest)`. The manifest holds input paths, snapshot, caps, reducers, compress, normalization version and schema versions. It does not hold the output root or any timestamp.

DEVIATIONS FROM THE REFERENCE, and why
1. Core API. This core has no `Ids.candidate`, `Normalize.arxiv`, `Normalize.openAlex`, `Normalize.nameBlockKey`, `Similarity`, `ExternalResearcher`, `MatchCandidate` or `SchemaVersions.ExternalResearcher`. I wrote `Identifiers`, `Similarity` (Jaro-Winkler, Jaccard, initials) and the local records. Candidate IDs are `mc:<kind>:<sha256 prefix of the ordered pair>`.
2. `AuthorMention` has a mandatory `provenance` here, and `Validate.work` checks it. The reference reused the work provenance for teams, topics and pairs, which fails this core's validators. I derive provenance per record type and schema version.
3. The record scanner never drops a unit.
   - The reference silently dropped oversize and end-of-input-truncated records.
   - Mine returns them with status (Oversize, Truncated, BadEncoding), and the mapper rejects them with counters.
   - A body that holds another record's start tag is split: the head is rejected alone and the swallowed records are recovered. Pieces starting at or after the split end are left to the next split's reader.
   - Strict UTF-8 is enforced, and the record size cap is configurable (`scholarchain.dblp.max.record.bytes`).
4. The parser refuses `<!DOCTYPE` and `<!ENTITY` explicitly and uses a per-thread StAX factory. Entity decoding is done in a single pass (`&amp;#65;` is not double-decoded). NFC normalization is applied to raw names and titles.
5. Added J07 graph export. The reference had no export job. It writes eight partitioned families with work-ID co-partitioning.
6. J04 also blocks works (title tokens, year, venue, DOI, authors) and has a hard cap with streamed values. The reference only expanded researcher blocks.
7. Exact keys also cover arXiv and OpenAlex IDs.
8. The ORCID-profile link picks the best mention with a sorted, strict tie-break. The reference's tie was order dependent.
9. Normalizers: Crossref titles get entity decoding and tag stripping, so `M&uuml;ller` matches DBLP. Abstracts are rebuilt from OpenAlex inverted indexes and Crossref text into a separate `abstracts` output, with `abstractTextRef` set to the text's `sha256:` tag. Invalid DOI, ORCID and arXiv values are dropped and counted as INVALID_IDENTIFIER. Fractional years and nameless authors reject the record.
10. Topic, institution and venue provenance names the entity ID, not the work. Otherwise duplicate copies would differ and show as false conflicts.
11. The driver and counters differ from the reference.
    - Counter names are standardized across jobs, and reject output is `rejects-v1/`.
    - Counters are persisted per stage (`_counters.json`) and a run summary holds the maxima.
    - `reducers` and `compress` are part of the manifest.
    - Snapshot and numeric arguments are validated. A stage with no input is skipped. The export reads only `part-*` globs of non-empty outputs.
12. Added `LocalRunner`, the bounded local pipeline entry point (main source). Hadoop scratch goes to `<output>/_local-tmp`.
13. Tests were written from scratch against this repository's samples with hand-derived counts. The reference fixtures and expected numbers were not used.
14. `Pipeline`'s run-summary code lives in `Pipeline.scala` rather than a separate file.

RISKS
- Not done:
  - the EMR smoke-run script (infra-engineer, Phase 13);
  - any cloud run;
  - mutation testing (forbidden by the addendum).
  - I did not run `sbt packageJobs`, so the job-jar build with the new sources is unobserved.
- LocalRunner needs hadoop-client-runtime, which is Test scope in the build. Run it as `sbt "hadoop/Test/runMain scholarchain.hadoop.LocalRunner --dblp ... --output ... --snapshot ..."`. On EMR the same `Pipeline.main` runs with the cluster's Hadoop.
- Known limitation: an unclosed record whose swallowed text exceeds the record cap (default 4 MB) becomes a single Oversize reject, and the neighbors inside it are not recovered. In real DBLP data, one record is a few KB, so this is not expected.
- The hadoop-local record types (MatchCandidate, ExternalResearcher, AuthorPaper, AbstractRecord) are not core contracts. Entity resolution reads them as JSON. Domain-engineer should promote them with schema versions in a later phase (the STATUS open item).
- A pair can appear under two exact keys (DOI and arXiv). Both rows carry the same candidate ID, so consumers must merge by ID.
- Cross-source teams and pairs double count the same paper until entity resolution links the works.
- The local file system leaves `.crc` files beside outputs. Spark ignores them. EMR output (S3) has none.
- State left behind: scratch run outputs under /tmp/claude-1000/-home-ajcreator-scholarchain/af7155d4-92e9-4aa5-8754-efde80d40f21/scratchpad/{fx,real} (not in the repo). Tests clean their own temp directories.
- Notes for modules/hadoop/MODULE.md (documentation-engineer):
  - Public API: `Pipeline` (Tool, main), `PipelineArgs` (--dblp --output --snapshot, optional --openalex, --crossref, --orcid, --max-team, --max-block, --reducers, --compress), `PipelinePaths`, `LocalRunner`, `DblpInputFormat`, `SourceNormalizers`, `KeyPartitioner`, `RunSummary` and the hadoop-local records.
  - Done: jobs 1-7 and the operational behavior. Not done: EMR script, S3 and Spark-side readers.
  - Known issues: those listed above.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer (Phase 4 adversarial Hadoop cases in tests/adversarial/, from the public API once documentation-engineer has updated MODULE.md). Then documentation-engineer (docs/hadoop-jobs.md and MODULE.md using only the numbers above) and code-reviewer.
