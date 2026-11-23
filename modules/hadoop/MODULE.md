# Module `hadoop`

## Purpose

MapReduce ingestion over DBLP and enrichment data.
It covers parsing, normalization, author-mention preparation, blocking, joining and
coauthor-pair generation.

## Owner and paths

- Owner: hadoop-engineer (documentation-engineer owns only this file).
- sbt project: `hadoop`; folder: `modules/hadoop`.
- Source roots (an empty `.gitkeep` placeholder remains in each of the three roots,
  `scala` included):
  - `modules/hadoop/src/main/scala`
  - `modules/hadoop/src/main/java`
  - `modules/hadoop/src/main/resources`
- Unit-test root: `tests/module-unit/hadoop/` (never `src/test`).

## Public API

Source of this section: the hadoop-engineer report
`docs/agent-runs/phase-04/agent-report.md`, with names and signatures confirmed in
`modules/hadoop/src/main/scala/scholarchain/hadoop/`.
Package: `scholarchain.hadoop`.
The wire format is JSON (uPickle), one record per line.

### Entry points

- `Pipeline`: a Hadoop `Tool`; `Pipeline.main(args)` runs J01 to J07 in order and exits
  with 0 on success, 1 when a stage failed and 2 on a usage error.
  On EMR the cluster's Hadoop runs it.
- `LocalRunner.main(args)`: the same pipeline on one JVM with Hadoop's LocalJobRunner and
  the local file system.
  It needs `hadoop-client-runtime`, which is Test scope in the build, so run it as:
  `sbt "hadoop/Test/runMain scholarchain.hadoop.LocalRunner --dblp <xml> --output <dir>
  --snapshot 2026-11-22"` (the command is one line; other `Pipeline` arguments are added
  the same way).
  Hadoop scratch goes to `<output>/_local-tmp`.
  Local output leaves `.crc` files beside the data; Spark ignores them.
- Other public types: `PipelineArgs`, `PipelinePaths`, `RunSummary`, `DblpInputFormat`,
  `SourceNormalizers`, `KeyPartitioner` and the hadoop-local records below.

### Arguments (`PipelineArgs.parse`)

Arguments come as `--name value` pairs.

| Argument | Required | Default | Rule |
|---|---|---|---|
| `--dblp` | yes | - | path to the DBLP XML input |
| `--output` | yes | - | output root |
| `--snapshot` | yes | - | calendar date `YYYY-MM-DD`, validated |
| `--openalex` | no | none | path to OpenAlex works JSON lines |
| `--crossref` | no | none | path to Crossref works JSON lines |
| `--orcid` | no | none | path to ORCID record JSON lines |
| `--max-team` | no | 300 | integer >= 2; a larger team is not expanded to pairs |
| `--max-block` | no | 200 | integer >= 2; a larger block is not compared; also the cap on work pairs per exact key in J03 |
| `--reducers` | no | 4 | integer >= 1 |
| `--compress` | no | true | exactly `true` or `false`; `false` turns off gzip on final outputs |

- Parsing is strict (repair round 1, B4).
  A usage error prints `usage error: <message>` and the usage line to stderr, and exits
  with 2, for any of these:
  - a missing required argument or an invalid value;
  - a token in a name position that does not start with `--`;
  - a name outside the 10 above;
  - a name with no value, or a value that starts with `--` (so a trailing `--max-team`
    no longer takes the default);
  - a repeated name;
  - `--compress` with a value other than exactly `true` or `false`.
- A stage with no input is skipped.
- A stage whose output already has `_SUCCESS` is skipped, so a rerun with the same
  inputs is idempotent; a partial output of the same run is removed and rebuilt.
- Map-output compression is always on (DefaultCodec); final outputs are gzip by default.
- The run ID is `Ids.run(manifest)`.
  The manifest holds the input paths, snapshot, caps, reducers, compress flag,
  normalization version, schema versions and the logic version
  `Pipeline.LogicVersion` (`"hadoop-v2"`, field `pipeline`); it holds no timestamp and not
  the output root, so the same inputs give the same run ID.
  `LogicVersion` is bumped on any logic change, so every run ID differs from a run made
  before the repair (it was `"hadoop-v1"`).
  Inputs at a path are treated as immutable per snapshot.

### Job graph

| Job | Pipeline | Output folder |
|---|---|---|
| J01 DBLP extraction | `DblpInputFormat`, `DblpExtractMapper`, `UniqueReducer` | `works/source=dblp` |
| J02 normalization (map only, one run per source) | `SourceNormalizeMapper` | `normalized/source={openalex,crossref,orcid}/{works,topics,institutions,abstracts,researchers}` |
| J03 exact identifiers | `WorkExactMapper` and `ProfileExactMapper` (MultipleInputs), `ExactJoinReducer` | `match-candidates/kind=exact` |
| J04 blocking and features | `BlockingMapper`, `BlockingReducer` | `match-candidates/kind=blocked`, plus `stats/` |
| J05 teams | `TeamMapper`, `TeamReducer` | `teams/{teams,authorpapers,stats}` |
| J06 pairs | `PairMapper`, `UniqueReducer` | `coauthor-pair-candidates` |
| J07 Spark export | `Export{Work,Team,Pair,AuthorPaper,Topic,Institution}Mapper`, `ExportReducer` | `export/{works,mentions,teams,pairs,authorpapers,topics,institutions,venues}` |

The J-numbers map to the work order's job numbers: 1 is J01, 2 is J02, 3 is J03,
4 and 5 are J04, 6 is J05 and J06, and 7 is J07.

### Output path layout

Every stage folder is
`<output>/<stage>/snapshot=<YYYY-MM-DD>/run_id=<run-xxxxxxxxxxxxxxxx>/`, for example
`works/source=dblp/snapshot=2026-11-22/run_id=<id>/part-r-0000N.gz`.
Inside a stage folder:

- data files: `part-r-NNNNN` (reduce output) or `part-m-NNNNN` (map-only J02), with
  `.gz` when compressed; named outputs sit in a subfolder named after the output
  (`works/`, `teams/`, `export/<kind>/` and so on);
- `_SUCCESS`, and `_counters.json` (one JSON object that maps counter name to value,
  for the group `scholarchain`);
- `rejects-v1/part-*`: the reject output of that stage (format below);
- `stats/part-*`: stage statistics lines (J04 `largestBlock`, J05 `maxTeamSize`).

Run records are at `<output>/run-manifests/<runId>.json` (the manifest) and
`<output>/run-manifests/<runId>-summary.json`.
The summary holds `runId`, `snapshot`, `reducers`, `maxTeam`, `maxBlock`,
`maxTeamObserved` (`workId`, `teamSize`), `largestPairExpansion`,
`largestBlockObserved` (`key`, `members`) and `stages`, a map from stage name to its
`output` path and `counters`.
Stage names are `J01`, `J02-<SOURCE>` for each source given, and `J03` to `J07`.
Readers must use `part-*` globs, because stage folders also hold `rejects-v1/` and
`stats/`.

### Reject output

- Every reject path increments `REJECTED_RECORDS` and writes a line to
  `rejects-v1/part-*` in the stage folder; `rejects-v1` is the reject format version.
- Each line is a core `RejectRecord` JSON with `stage`, `reason`, `input` (truncated to
  2000 characters) and `runId`.
- A rejected unit is never dropped silently.
  The DBLP scanner returns oversize records, records truncated at end of input and
  badly encoded records with a status, and J01 rejects them with counters.
- Encoding rule (human decision of 2026-11-23): the reader decodes with the encoding
  declared in the XML prolog (ISO-8859-1 for DBLP, UTF-8 when none is declared) and
  rejects bytes that are invalid in the declared encoding.
  - The charset is read once per file from the first 1024 bytes at offset 0
    (`DblpRecordReader.charsetOf`), whatever the split start; `.gz` is decompressed
    first.
    A leading UTF-8 BOM is skipped.
    `encoding="..."` or `'...'` in the `<?xml ... ?>` prolog is resolved with
    `Charset.forName`.
    No prolog or no declaration means UTF-8.
  - Each record is decoded with a reporting decoder, so a byte invalid in the declared
    encoding gives a `BadEncoding` reject.
    With ISO-8859-1 every byte is valid, so only the XML character rules reject, such as
    a raw 0x01.
  - The file is refused whole, as one unit, in these cases: a UTF-16 or UTF-32 BOM or
    byte pattern, a declared encoding that is unknown, a declared encoding that is
    neither UTF-8 nor single-byte and ASCII-compatible (Shift_JIS, GBK and the like), and
    a UTF-8 BOM next to a non-UTF-8 declaration.
    The split that holds byte 0 emits it once, with key `-1` and value
    `UnsupportedEncoding:<reason>`; J01 counts `UNSUPPORTED_ENCODING` and writes the
    reject `unsupported encoding: <reason>`.
  - A `BadEncoding` unit's value is `BadEncoding:<head>\n[charset=<name>]`; the reject
    reason reads `unreadable record (BadEncoding, <charset>) at byte N`.
    The head is decoded in the declared charset, and the reject file is UTF-8 JSON.
  - `DblpRecordScanner` has a last constructor parameter `charset`, default UTF-8.
- The DBLP record size cap is the Hadoop setting `scholarchain.dblp.max.record.bytes`
  (default 4 MB).
- A `<!DOCTYPE` header with an internal subset is tolerated, and its entities are not
  expanded.
  A record that uses an undeclared entity, and a `<!DOCTYPE` or `<!ENTITY` inside a
  record, are rejected.
- An unclosed record is ended as `Truncated` or `Oversize` at the next start tag (a
  byte-level `<` followed by a record-type name and a space or `>`), so it no longer
  swallows its neighbors.
  Byte offsets are exact, and split N and N+1 agree.

### Counters

Group `scholarchain`, with the same names in every job:
`INPUT_RECORDS`, `ACCEPTED_RECORDS`, `REJECTED_RECORDS`, `SKIPPED_RECORDS`,
`SCHEMA_FAILURE`, `DUPLICATE_RECORDS`, `CONFLICT_RECORDS`, `OVERSIZE_BLOCKS`,
`OVERSIZE_TEAMS`, `EMITTED_PAIRS` and `MISSING_KEY`.
Extra counters: `INVALID_IDENTIFIER`, `PAIRS_REFUSED_BY_CAP`,
`CANDIDATE_PAIRS_BEFORE_CAP`, `WORKS_WITHOUT_AUTHORS`, `EXACT_CANDIDATES`,
`BLOCKED_CANDIDATES`, `TEAM_SIZE_*` buckets and `EXPORTED_*`.
Added in repair round 1:

- J04 `FALLBACK_BLOCKS_COMPARED` and `FALLBACK_BLOCKS_REFUSED`.
  A refused fallback block also increments `REJECTED_RECORDS` and writes a reject line,
  but not `OVERSIZE_BLOCKS`.
- J01 `UNSUPPORTED_ENCODING` (see the encoding rule).
- J01 `INVALID_IDENTIFIER`: it now also counts invalid identifiers found by the DBLP
  parser, namely an `orcid` attribute that is present but invalid, an `ee` on
  `doi.org` or `dx.doi.org` that fails the DOI check, and an `ee` on `arxiv.org` or
  `www.arxiv.org` that is not an arXiv ID.
  Ordinary URLs are never counted.
- J03 `OVERSIZE_BLOCKS`: an exact key that holds more works than `--max-block` gets no
  work pairs, counts here and writes the reject
  `exact key <key> holds N works > M; work pairs not expanded`.
  Mention and profile links are unaffected.
- Other counters seen in the adversarial run: `DBLP_TYPE_ARTICLE`, `UNIQUE_RECORDS`,
  `WORKS_WRITTEN`, `TOPICS_WRITTEN` and `MISSING_DOI`.

J03 `DUPLICATE_RECORDS` and `CONFLICT_RECORDS` (`ExactLogic.consolidate`):

- References are grouped by ID; the kept copy is the smallest JSON, not the first to
  arrive, and the result is sorted by ID.
- DUPLICATE is the copies of a work reference beyond the first, per key and per work ID,
  plus the sources that hold more than one distinct work under the key.
- CONFLICT is the work IDs whose copies differ, plus pairs of works with unrelated titles,
  plus ORCID name conflicts.
- Mention and profile references are consolidated the same way but not counted.
- Counts are per key: a repeated OpenAlex work with a DOI and an OpenAlex ID counts once
  under `doi:` and once under `openalex:`.
- The same smallest-JSON choice applies to `workBlockResult` (per work ID) and to
  `clusters` (identical copies collapse).
In a mapper, `INPUT_RECORDS = ACCEPTED_RECORDS + REJECTED_RECORDS + SKIPPED_RECORDS`.
`SCHEMA_FAILURE` is a subset of `REJECTED_RECORDS`.
Valid units outside a stage's scope (a DBLP `www` or `proceedings` record) are skipped,
not rejected.

### Key-value contracts

| Job | Map input to map output | Reduce |
|---|---|---|
| J01 | (LongWritable offset, Text one complete DBLP record) to (workId, WorkRecord JSON); a unit that is not a complete record arrives with key `-(offset+1)` and a status value and is rejected; the value prefix is `Oversize:`, `BadEncoding:`, `Truncated:` or `UnsupportedEncoding:` (not `STATUS:`) | (workId, JSON list) to one line, the smallest JSON |
| J02 | (offset, source JSON line) to named outputs; the default output stays empty | none |
| J03 | (offset, WorkRecord or ExternalResearcher JSON) to (key `doi:`, `arxiv:`, `openalex:` or `orcid:`, IdRef JSON) | (key, IdRef list) to MatchCandidate lines |
| J04 | (offset, WorkRecord) to (`r:<last>_<initial>`, MentionSummary) per author and (`wk:<year>:<two longest title tokens>`, WorkSummary) per work | (block, members) to MatchCandidate lines; streamed, buffered only up to a hard cap of 10 x `--max-block`; fallback keys below |
| J05 | (offset, WorkRecord) to (workId, work JSON) | (workId, JSON list) to a team line and one author-paper line per mention, in named outputs |
| J06 | (offset, PublicationTeam) to (`mentionA\|mentionB\|workId`, CoauthorPairCandidate) | `UniqueReducer` |
| J07 | (offset, record) to (`<partitionKey>\t<kind>\t<id>`, JSON) | (key, value stream) to `<kind>/part-r-NNNNN`; the reducer iterates once (first value, minimum, count, differs flag), the smallest JSON wins, DUPLICATE is count minus 1 and CONFLICT is raised when a value differs |

- The J07 partition key is the work ID for work, mention, team, pair and author-paper
  records, and the entity's own ID for topics, institutions and venues.
- Exact keys also cover arXiv and OpenAlex IDs.
  A pair can appear under two exact keys (DOI and arXiv); both rows carry the same
  candidate ID, so consumers must merge by candidate ID.
- Candidate IDs are `mc:<kind>:<sha256 prefix of the ordered pair>`; `sourceId` sorts
  before `targetId`.
- A team above `--max-team` is not expanded: it is counted (`OVERSIZE_TEAMS`), the
  refused pairs are counted (`PAIRS_REFUSED_BY_CAP`) and a reject line is written.
- A researcher or work block above `--max-block` is not compared as a whole
  (`OVERSIZE_BLOCKS`).
  A researcher block is first split by full name; the within-cluster
  `same_source_cluster` links of refused parts remain.
  A block above the hard cap of 10 x `--max-block` is refused whole.
- Fallback keys (repair round 1, B1; spec line 2245).
  The trigger is a researcher block with more than `--max-block` clusters; the split by
  full name stays and fallback blocks are added.
  - Researcher fallback keys: `<key>|co:<coauthorKey>`, `<key>|v:<venueId>` and
    `<key>|y:<year>`.
  - Work fallback keys: `<wk key>|v:<venueId or lower-cased venueName>` and
    `<wk key>|a:<author key>` for every author key (`WorkSummary` has no author order, so
    this is a superset of "first author").
  - A fallback block is compared only when it has 2 to `--max-block` members; a larger one
    is refused, counted and rejected (`FALLBACK_BLOCKS_REFUSED`).
  - A key is never built from an empty attribute.
  - Two clusters that share a work are never paired.
  - A fallback group in which every cluster shares the same single work is skipped
    silently.
  - A pair already emitted, in a primary sub-block or an earlier fallback block, is not
    emitted again (checked by the unordered pair of endpoint IDs).
  - Block keys always contain `|`, so they never equal the bare primary key.
  - The block result has a `fallback` flag, and `BlockLogic.workBlockResult` returns a
    `Vector[BlockResult]`.
- Name-block size is counted in source clusters, with sub-blocks such as
  `wang_w/wei wang`.
  A candidate's `blockKey` drops the `r:` prefix, while the summary key keeps it.
- `largestPairExpansion` counts refused teams (78 was observed for a 13-author team above
  `--max-team 12`).

### Partitioner

`KeyPartitioner` is set on every shuffle.
It takes Hadoop's byte hash (`WritableComparator.hashBytes`) of the key up to the first
TAB, or of the whole key when there is none, masked to non-negative, modulo the
reducer count.
J07 keys put the work ID before the TAB, so everything of one work lands in one
partition number.
J02 is map only (0 reducers).
A count of part files below `--reducers` means that no key hashed to the other
partitions.

### Hadoop-local records

These are not `core` contracts yet; each carries a provenance with its own schema
version, validated by `HadoopValidate`.
The core records (works, mentions, teams, pair candidates, `RejectRecord`) keep their
`core` schema versions.

| Record | Schema version | Fields |
|---|---|---|
| `MatchCandidate` | `match-candidate-v1` | schemaVersion, candidateId, kind, sourceId, targetId, blockKey, features, sourceIdentifiers, targetIdentifiers, provenance |
| `ExternalResearcher` | `external-researcher-v1` | schemaVersion, recordId, source, orcid, externalAuthorId, displayName, nameVariants, affiliations, workDois, provenance |
| `AuthorPaper` | `author-paper-v1` | mentionId, workId, position, normalizedName, orcid, year, teamSize, provenance |
| `AbstractRecord` | `abstract-v1` | workId, textHash, text, provenance |

- `RunContext(runId, snapshot)` supplies the provenance of every derived record: source
  type, source record ID, snapshot, run ID, schema version and normalization version.
- Topic, institution and venue provenance names the entity ID, not the work, so
  duplicate copies do not look like conflicts.
- Abstracts are rebuilt from OpenAlex inverted indexes and Crossref text into a separate
  `abstracts` output; the work holds `abstractTextRef`, the `sha256:` tag of the text.
- Semantic Scholar IDs are normalized, but no selected dataset provides them, so they
  emit no keys.
- DBLP disambiguates homonyms by numeric suffix: identical raw author strings form one
  source cluster, different suffixes are distinct clusters with `dblp_homonym_distinct`.

### Behavior to rely on

- Teams and pairs are built for every retained work from every source (DBLP, OpenAlex,
  Crossref).
  The same paper from two sources yields two teams until entity resolution links the
  works, so cross-source teams and pairs double count it.
- Invalid DOI, ORCID and arXiv values are dropped and counted as `INVALID_IDENTIFIER`,
  in J01 as well as the later stages.
  A fractional year or a nameless author rejects the record.
- Raw names and titles are NFC normalized; entities are decoded in a single pass.
- J01 reads the DBLP XML as splittable units.
  The result does not depend on the split size, because the charset is read once from
  byte 0 and an unclosed record ends at the next start tag.
  Tests: the real reader on splits of 30, 64, 100, 257 and 1000 bytes, and `.gz`, give
  identical output.
  A `.gz` input is still read by one mapper.

## Done

- The sbt project compiles with source and unit-test roots (phase 2).
- Jobs J01 to J07 and the operational behavior are implemented (phase 4, hadoop-engineer
  reports `docs/agent-runs/phase-04/agent-report.md` and
  `docs/agent-runs/phase-04/repair-01-hadoop-engineer.md`), with unit suites and fixtures
  in `tests/module-unit/hadoop/`.
- Repair round 1 (B1 to B10): fallback keys, J03 consolidation, invalid-identifier
  counting, strict arguments, the declared-encoding rule, next-start-tag recovery, the
  streaming J07 reducer, `LogicVersion` `hadoop-v2`, the J03 exact-key cap and a
  map-output
  compression test.
- A local bounded pipeline entry point, `LocalRunner`.

Observed numbers after the repair (full tables in
[docs/hadoop-jobs.md](../../docs/hadoop-jobs.md); source: the VALIDATION RESULTS of
`repair-01-hadoop-engineer.md`):

- Run `run-8b3a162615a54f8f` (fixtures): maximum team size 12, largest pair expansion 66,
  largest blocking group 13 mentions (`r:archer_a`), no rejects.
  J01 input 17, accepted 15, skipped 2; J03 51 exact candidates; J04 142 blocked
  candidates; J05 35 teams, 93 author papers; J06 127 pairs.
- Run `run-6d1175dbfdb874a9` (real samples): maximum team size 5, largest pair expansion
  10, largest blocking group 18 mentions (`r:drew_m`), no rejects.
  J01 40 in, 40 accepted; J03 109 exact candidates; J04 309 blocked candidates;
  J05 90 teams, 219 author papers; J06 186 pairs.
- Neither run triggers the new fallback, oversize or encoding paths.
- Tests in hadoop-engineer's final `sbt check` (exit 0): core 94 of 94, hadoop 98 of 98
  (79 before the repair), adversarialTests 68 of 68 (the 5 red tests are green).
  The test-engineer rerun (R1c) and Review 2 were still pending when this was written.

## Not done

- The EMR smoke-run script (infra-engineer, Phase 13) and any cloud run.
- S3 and Spark-side readers of the export (Spark module).
- Promotion of the hadoop-local records to `core` contracts (domain-engineer, later
  phase).
- `sbt packageJobs` has not been run with the new sources, so the job-jar build is
  unobserved.
- Mutation testing (not allowed by the machine limits).

## Known issues

Source: hadoop-engineer repair report (HR2) and the earlier T2 report, both in
`docs/agent-runs/phase-04/`.
The four open items of test-engineer's T3 report (fallback keys, `INVALID_IDENTIFIER`,
J03 duplicate and conflict counts, trailing cap flag) are fixed in repair round 1 and
removed from this list.

- The J03 reducer still buffers all values of a key in memory.
  The exact-key cap bounds the quadratic pairs, not the memory of a giant group.
  Only the J04 reducer has a hard buffer cap.
- The researcher fallback year key (`y:`) is weak on big inputs: popular years give
  refused fallback blocks, each a reject line, bounded by the number of distinct fallback
  keys.
  Coauthor and venue keys carry the real gain.
- Mention fallback blocks are compared inside one buffered primary block only; a block
  above the hard cap is refused whole.
- A record whose own content holds a literal `<article ` or similar inside text or CDATA
  would be cut as Truncated.
  Valid DBLP never does this, because the `<` is escaped.
- A `.gz` DBLP input is read by one mapper.
- The streaming `ExportReducer` has no separate unit test (the J07 pipeline test covers
  it), and the refusal of a UTF-8 BOM next to a non-UTF-8 declaration is tested at
  charset-detection level only.
- `UniqueReducer` (J01 and J06) still buffers per key; its groups are small.
- ISO-8859-1 C1 bytes (0x80 to 0x9F) are accepted, because XML 1.0 allows C1 characters.
- Only UTF-8 and single-byte ASCII-compatible encodings are accepted; multi-byte legacy
  encodings are refused because a trail byte can equal `<`.
- A pair can appear under two exact keys (DOI and arXiv); consumers merge by candidate ID.
- Cross-source teams and pairs double count the same paper until entity resolution.
- Local output leaves `.crc` files beside the data; Spark ignores them.
- The hadoop-local records are not core contracts yet.
- `sbt packageJobs` was not run with the new sources.

## Dependencies

- Project: `core`.
- Library: `hadoop-client-api` 3.4.1, Provided (EMR supplies it).
- Packaged by `packageJobs` into `target/jobs/`.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
