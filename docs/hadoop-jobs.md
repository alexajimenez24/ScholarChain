# Hadoop jobs (Phase 04)

Owner: documentation-engineer.
Module notes: [modules/hadoop/MODULE.md](../modules/hadoop/MODULE.md).

## Evidence and status

Every number in this file comes from a saved report.
Nothing here was derived or recomputed by the author of this file.

- HR: the hadoop-engineer report,
  [agent-report.md](agent-runs/phase-04/agent-report.md) (task T2, before the repair).
- HR2: the hadoop-engineer repair report,
  [repair-01-hadoop-engineer.md](agent-runs/phase-04/repair-01-hadoop-engineer.md)
  (repair round 1, task R1a; the source of every post-repair number).
- TR: the test-engineer report,
  [test-report.md](agent-runs/phase-04/test-report.md) (task T3, before the repair).

Post-repair state (HR2): the final `sbt check` exited 0 with core 94 of 94, hadoop 98 of
98 (79 before) and adversarialTests 68 of 68.
The 5 adversarial tests that were red in T3 are green.
The test-engineer rerun (R1c) and the code-reviewer's Review 2 were pending when this was
written.
Cloud runs: none.
The EMR smoke-run script is not written (infra-engineer, Phase 13).
`sbt packageJobs` was not run with the new sources.

## Job graph

| Job | Pipeline | Output folder |
|---|---|---|
| J01 DBLP extraction | `DblpInputFormat`, `DblpExtractMapper`, `UniqueReducer` | `works/source=dblp` |
| J02 normalization (map only, per source) | `SourceNormalizeMapper` | `normalized/source={openalex,crossref,orcid}/{works,topics,institutions,abstracts,researchers}` |
| J03 exact identifiers | `WorkExactMapper`, `ProfileExactMapper`, `ExactJoinReducer` | `match-candidates/kind=exact` |
| J04 blocking and features | `BlockingMapper`, `BlockingReducer` | `match-candidates/kind=blocked`, `stats/` |
| J05 teams | `TeamMapper`, `TeamReducer` | `teams/{teams,authorpapers,stats}` |
| J06 pairs | `PairMapper`, `UniqueReducer` | `coauthor-pair-candidates` |
| J07 Spark export | `Export*Mapper`, `ExportReducer` | `export/{works,mentions,teams,pairs,authorpapers,topics,institutions,venues}` |

The work order's job numbers map as 1 to J01, 2 to J02, 3 to J03, 4 and 5 to J04,
6 to J05 and J06, and 7 to J07 (HR).

## Key-value contracts

| Job | Map input to map output | Reduce |
|---|---|---|
| J01 | (offset, one complete DBLP record) to (workId, WorkRecord JSON) | (workId, JSON list) to one line, the smallest JSON |
| J02 | (offset, source JSON line) to named outputs | none |
| J03 | (offset, WorkRecord or ExternalResearcher JSON) to (`doi:`, `arxiv:`, `openalex:` or `orcid:` key, IdRef JSON) | (key, IdRef list) to MatchCandidate lines |
| J04 | (offset, WorkRecord) to (`r:<last>_<initial>`, MentionSummary) and (`wk:<year>:<two longest title tokens>`, WorkSummary) | (block, members) to MatchCandidate lines, streamed and buffered up to 10 x `--max-block`; fallback keys in the Blocking section |
| J05 | (offset, WorkRecord) to (workId, work JSON) | (workId, JSON list) to a team line and one author-paper line per mention |
| J06 | (offset, PublicationTeam) to (`mentionA\|mentionB\|workId`, CoauthorPairCandidate) | `UniqueReducer` |
| J07 | (offset, record) to (`<partitionKey>\t<kind>\t<id>`, JSON) | (key, value stream) to `<kind>/part-r-NNNNN`; one pass, smallest JSON wins |

A DBLP unit that is not a complete record reaches J01 with key `-(offset+1)`.
Its value starts with `Oversize:`, `BadEncoding:` or `Truncated:` (not `STATUS:`).
A refused file reaches J01 once, with key `-1` and the value
`UnsupportedEncoding:<reason>`.
J01 rejects such units with counters.

## Per-job items (spec lines 2196-2210)

Common to all jobs:

- Combiner: none.
  No job sets a combiner class (checked in `Pipeline.scala` and `Jobs.scala`).
- Partitioner: `KeyPartitioner` on every shuffle (see Partition strategy).
- Reducers: `--reducers`, default 4, for every job with a reduce phase; J02 is map only
  with 0.
- Codec: map output is always compressed with DefaultCodec (a test asserts the setting);
  final outputs are gzip (`.gz`) unless `--compress false`.
- S3 prefixes: the pipeline takes input paths and an output root as arguments and writes
  the path layout under "Output path layout" below.
  No S3 bucket or prefix is defined in the module; the EMR prefixes are set in Phase 13
  by infra-engineer.
- Idempotency: a stage whose output already has `_SUCCESS` is skipped; a partial output of
  the same run is removed and rebuilt; the run ID depends only on inputs and settings, so
  a rerun lands in the same folder.
  Reducers choose the smallest JSON among copies, so the result does not depend on
  arrival order.
- Test fixtures: `tests/module-unit/hadoop/resources/fixtures/` (copies of
  `datasets/samples/fixtures`: `dblp-fixture.xml`, `openalex-works-fixture.jsonl`,
  `crossref-works-fixture.jsonl`, `orcid-records-fixture.jsonl`) and the real samples in
  `datasets/samples/real`.
  Suites are in `tests/module-unit/hadoop/` and `tests/adversarial/`.

| Job | Input format | Input key, value | Output | Reducers | Reject conditions |
|---|---|---|---|---|---|
| J01 | `DblpInputFormat` | offset, one DBLP record or a status unit | (workId, WorkRecord JSON) | `--reducers` | oversize record (4 MB default), truncated record, bytes invalid in the declared encoding, refused encoding, parse failure, schema validation failure |
| J02 | line text | offset, source JSON line | named outputs | 0 | unparsable line, schema validation failure (per source) |
| J03 | line text, two mappers | offset, WorkRecord or ExternalResearcher JSON | MatchCandidate lines | `--reducers` | unparsable line, exact key with more than `--max-block` works (no work pairs) |
| J04 | line text | offset, WorkRecord | MatchCandidate lines, stats | `--reducers` | unparsable line, block above 10 x `--max-block`, fallback block above `--max-block` |
| J05 | line text | offset, WorkRecord | team and author-paper lines, stats | `--reducers` | unparsable or invalid work |
| J06 | line text | offset, PublicationTeam | coauthor pair candidates | `--reducers` | unparsable line, team above `--max-team` |
| J07 | line text | offset, record | `export/<kind>` | `--reducers` | unparsable line, key without a known kind |

The reject conditions are read from the `reject(...)` calls in `Jobs.scala` and the
repair report; each reject is also counted (`REJECTED_RECORDS`).
The fixture for each job is the run in "Observed results" below.

## Partition strategy

- `KeyPartitioner` is set on every shuffle (HR).
- It hashes the key up to the first TAB with Hadoop's byte hash, modulo the reducer count.
- The reducer count is `--reducers`, default 4; J02 has 0 reducers.
- J07 keys put the work ID before the TAB, so a work's records share one partition number.
  HR reports a test that proves this.
- Local runs used 4 reducers (HR, HR2).
- Part files on the fixtures run (HR, before the repair, not re-counted):
  4 for J01, J03, J04 and J06.
  Export: works, mentions, teams, pairs and authorpapers 4 each, topics 2,
  institutions 1, venues 4.
  A count below 4 means no key hashed to the other partitions.
- After the repair, HR2 reports the real reader giving identical output on splits of 30,
  64, 100, 257 and 1000 bytes and on `.gz`.
- Before the repair, HR reported a test with 700-byte splits on the DBLP fixture:
  8 map splits, and the J01
  result equals the single-split run.
- TR reports split sizes of 64, 100, 128, 257 and 1000 bytes on a clean document of
  26 keys: each key arrives exactly once as a whole unit at every size.

## Blocking and fallback keys (J04)

A researcher block with more than `--max-block` clusters is split by full name, and
fallback blocks are added (HR2, spec line 2245).

- Researcher keys: `<key>|co:<coauthorKey>`, `<key>|v:<venueId>`, `<key>|y:<year>`.
- Work keys: `<wk key>|v:<venueId or lower-cased venueName>` and `<wk key>|a:<author key>`
  for every author key.
- A fallback block is compared only with 2 to `--max-block` members; larger ones are
  refused, counted and rejected.
- Empty attributes build no key; clusters sharing a work are never paired; a pair already
  emitted is not emitted again.

## Counters

Group `scholarchain`, the same names in every job (HR):
`INPUT_RECORDS`, `ACCEPTED_RECORDS`, `REJECTED_RECORDS`, `SKIPPED_RECORDS`,
`SCHEMA_FAILURE`, `DUPLICATE_RECORDS`, `CONFLICT_RECORDS`, `OVERSIZE_BLOCKS`,
`OVERSIZE_TEAMS`, `EMITTED_PAIRS`, `MISSING_KEY`.
Extras (HR): `INVALID_IDENTIFIER`, `PAIRS_REFUSED_BY_CAP`,
`CANDIDATE_PAIRS_BEFORE_CAP`, `TEAM_SIZE_*` buckets and `EXPORTED_*`.
Seen by TR: `DBLP_TYPE_ARTICLE`, `UNIQUE_RECORDS`, `WORKS_WRITTEN`, `TOPICS_WRITTEN` and
`MISSING_DOI`.
The module notes also list `WORKS_WITHOUT_AUTHORS`, `EXACT_CANDIDATES` and
`BLOCKED_CANDIDATES`.
Added in the repair (HR2):

- J04 `FALLBACK_BLOCKS_COMPARED` and `FALLBACK_BLOCKS_REFUSED`; a refused fallback block
  also counts in `REJECTED_RECORDS` and writes a reject line, but not in
  `OVERSIZE_BLOCKS`.
- J01 `UNSUPPORTED_ENCODING`, and `INVALID_IDENTIFIER` now counts in J01.
- J03 `OVERSIZE_BLOCKS` when an exact key holds more works than `--max-block`.
- J03 `DUPLICATE_RECORDS` is the copies of a work reference beyond the first, per key and
  work ID, plus the sources that hold more than one distinct work under the key.
  `CONFLICT_RECORDS` is the work IDs with differing copies, plus pairs of works with
  unrelated titles, plus ORCID name conflicts.
  Counts are per key (one repeated work with a DOI and an OpenAlex ID counts under both).

- In a mapper, `INPUT = ACCEPTED + REJECTED + SKIPPED`, and the tests assert it (HR).
- Every reject path increments `REJECTED_RECORDS` (HR).
- Each stage writes `_counters.json` next to `_SUCCESS` (HR).

## Output path layout

- Stage folder:
  `<output>/<stage>/snapshot=<YYYY-MM-DD>/run_id=<run-xxxxxxxxxxxxxxxx>/`.
- Data: `part-r-NNNNN` or `part-m-NNNNN`, with `.gz` when compressed.
  Final outputs are gzip by default and map output is always compressed (HR).
- Also in a stage folder: `_SUCCESS`, `_counters.json`, `rejects-v1/part-*` and
  `stats/part-*`.
- Run records: `<output>/run-manifests/<runId>.json` and
  `<output>/run-manifests/<runId>-summary.json`.
  The summary maps each stage to its `output` path and `counters`.
- The run ID is `Ids.run(manifest)`.
  The manifest has no timestamp and no output root, so the same inputs give the same
  run ID (HR).
  TR observed the same run ID on a replay into a new output root.
- Readers use `part-*` globs, because stage folders also hold `rejects-v1/` and `stats/`.
- The local file system leaves `.crc` files beside outputs (HR).

## Reject outputs

- Path: `rejects-v1/part-*` in the stage folder.
- Each line is a core `RejectRecord` with stage, reason, input (at most 2000
  characters) and run ID (HR).
- Encoding (human decision of 2026-11-23): J01 decodes with the encoding declared in the
  XML prolog (ISO-8859-1 for DBLP, UTF-8 when none is declared) and rejects bytes that are
  invalid in it as `BadEncoding`.
  The file is refused whole (one unit, `UNSUPPORTED_ENCODING`) for UTF-16 or UTF-32, an
  unknown declared encoding, a multi-byte or non-ASCII-compatible one, or a UTF-8 BOM
  next to a non-UTF-8 declaration (HR2).
  HR2 pins this with tests: a raw 0xE9 byte in ISO-8859-1 gives "René Dupont", and the
  same bytes declared UTF-8 give a reject.
- An unclosed record ends at the next start tag, so its neighbors are recovered (HR2:
  an unclosed record followed by 1000 articles gives 1001 units).
- The values below on the hostile DBLP run and the Rejects suite were observed before the
  repair (TR, HR) and not re-observed after it:
  - TR: 8 units = 3 accepted + 5 rejected, 5 reject lines each naming one bad key, the
    oversize title (3004 characters) cut to at most 2000.
  - HR, `RejectsAndLimitsSuite` (`--max-team 4`, record cap 700 bytes): J01 17 units = 9
    accepted + 1 skipped + 7 rejected, J02 OpenAlex 6 lines = 2 accepted + 4 rejected,
    J03 MISSING_KEY 7, J06 OVERSIZE_TEAMS 1, PAIRS_REFUSED_BY_CAP 10, EMITTED_PAIRS 5.
    HR2 changed one expectation in this suite: the J03 counter map gains
    `DUPLICATE_RECORDS -> 2` (the same OpenAlex work given twice, under `doi:` and
    `openalex:`).
- DTD input (TR, before the repair): an internal subset with a `file://` entity and a
  nested entity expansion produced no canary text in any output.
  A header internal subset is tolerated; an undeclared entity or a `<!ENTITY` inside a
  record is rejected.

## Local bounded pipeline command

```text
source toolchain/env.sh
sbt "hadoop/Test/runMain scholarchain.hadoop.LocalRunner --dblp <xml> --output <dir> --snapshot 2026-11-22"
```

- The command is one line.
- It runs J01 to J07 with Hadoop's LocalJobRunner on one JVM.
- `LocalRunner` needs `hadoop-client-runtime`, which is Test scope, so it runs through
  `hadoop/Test/runMain`.
- Optional arguments: `--openalex`, `--crossref`, `--orcid`, `--max-team`,
  `--max-block`, `--reducers`, `--compress`.
- Not replayed in T4: the work order forbids sbt.
  The command and the two runs below are as reported by HR.

## Observed results of the two local runs

Source: HR2, VALIDATION RESULTS (post-repair, `--snapshot 2026-11-22`, one sbt invocation,
both runs exit 0).
Outputs are in the hadoop-engineer's scratchpad, not in the repository.

### Run 1: fixtures, `run-8b3a162615a54f8f`

Inputs: the four fixtures (DBLP, OpenAlex, Crossref, ORCID).

| Job | Observed (HR2) |
|---|---|
| J01 | input 17, accepted 15, skipped 2 (www 1, proceedings 1), unique 15, no rejects |
| J02 | OpenAlex 12 accepted (12 works, 12 topic rows, 5 institution rows), Crossref 8, ORCID 2 |
| J03 | input 37, accepted 37, 51 exact candidates, no duplicate, conflict or oversize |
| J04 | input 35, 142 blocked candidates, no fallback or oversize counters |
| J05 | 35 teams, 93 author papers, 127 pairs before the cap |
| J06 | 127 pairs emitted |
| J07 | input 307, duplicates 30; exported works 35, teams 35, pairs 127, authorpapers 93, mentions 93, venues 11, topics 2, institutions 1 |

- Maximum team size: 12 (`dblp:conf/fixa/ArcherTeam22`); largest pair expansion 66.
- Largest blocking group: 13 members, `r:archer_a`.
- The pre-repair run was `run-8019aba484da34ad`; its numbers are the same, and the run ID
  changed because `LogicVersion` is now `hadoop-v2`.

### Run 2: real samples, `run-6d1175dbfdb874a9`

Inputs: `dblp-sample.xml`, `openalex-works.jsonl`, `crossref-works.jsonl`.

| Job | Observed (HR2) |
|---|---|
| J01 | input 40, accepted 40 (all incollection) |
| J02 | OpenAlex 25 accepted (25 works, 75 topic rows, 51 institution rows), Crossref 25 |
| J03 | input 90, 109 exact candidates |
| J04 | 309 blocked candidates |
| J05 | 90 teams, 219 author papers, 186 pairs before the cap |
| J06 | 186 pairs emitted |
| J07 | input 711, duplicates 69; exported works 90, teams 90, pairs 186, authorpapers 219, mentions 219, venues 1, topics 44, institutions 37 |

- Maximum team size: 5 (`crossref:10.1007/978-0-387-31439-6_184`); largest pair
  expansion 10.
- Largest blocking group: 18 members, `r:drew_m`.
- No rejects, oversize blocks, oversize teams or fallback counters appeared in either run,
  so neither input triggers the new paths.
- Both samples declare ISO-8859-1 and are pure ASCII.

## Adversarial results (test-engineer, T3, rerun pending)

Source: TR for the suites and values, HR2 for the post-repair total.
In T3 the totals were 68 tests with 5 red.
In HR2's final `sbt check` all 68 pass.
The suite totals below are from TR; per-suite pass counts after the repair were not
reported, and the test-engineer rerun (R1c) is pending.

| Suite | Total (TR) |
|---|---|
| DblpXmlAttackSuite | 13 |
| UnicodeSuite | 8 |
| MissingFieldSuite | 12 |
| DuplicateIdSuite | 11 |
| CommonNameSuite | 10 |
| GiantTeamSuite | 14 |

The 5 formerly red tests, now green per HR2:

1. `CommonNameSuite` fallback Wang and fallback title (B1, fallback keys).
2. `MissingFieldSuite` invalid ORCID (B2, `INVALID_IDENTIFIER`).
3. `DuplicateIdSuite` J03 duplicate (B3, J03 duplicate and conflict counts).
4. `GiantTeamSuite` cap flag with no value (B4, strict arguments).

HR2 lists four test names for the five formerly red tests; the two `CommonNameSuite` tests
are the name block and the title block.

Values observed in T3 (before the repair, not re-observed):

- Giant team, `--max-team 12`: teams of 1, 2, 3 and 12 authors give 0, 1, 3 and 66 pairs;
  the 13-author team gives OVERSIZE_TEAMS 1 and PAIRS_REFUSED_BY_CAP 78 summed over all
  stages.
  `largestPairExpansion` counts the refused team (78).
- Giant team, default cap 300: a 301-author paper refuses 45150 pairs and a 25-author
  paper gives 300.
- Common names, `--max-block 5`: `largestBlockObserved` is {`r:zhang_h`, 60}; a block of 5
  at the cap is compared and gives 10 candidates.
- Missing fields, DBLP: INPUT 17, ACCEPTED 8, REJECTED 9, MISSING_KEY 2.
- XML attacks: 8 units = 3 accepted + 5 rejected at every split size.
- Unicode: 8 units = 6 works + 2 rejects.

## Decision on encoding (human, 2026-11-23)

"The reader must decode with the encoding declared in the XML prolog (ISO-8859-1 for
DBLP, UTF-8 when none is declared) and reject bytes that are invalid in the declared
encoding."
It is implemented in repair round 1 (HR2, brief B5) and described under "Reject outputs".
A black-box ISO-8859-1 case from test-engineer is not yet written.
