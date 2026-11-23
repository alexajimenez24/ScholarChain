HARNESS: claude-code
MODEL: sonnet, standard tier (no override; matches `.agents/models.json` standard tier)

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 04, repair round 1: B4, B3, B2, B1, B5 with B6, B7 (required), then B8, B9, B10. All ten were done.

FILES CREATED (all under tests/module-unit/hadoop/):
- RepairArgsSuite.scala (2 tests)
- RepairLogicSuite.scala (6 tests)
- RepairDblpSuite.scala (10 tests)

FILES MODIFIED:
- modules/hadoop/src/main/scala/scholarchain/hadoop/Pipeline.scala (strict args, `Pipeline.LogicVersion`, `Pipeline.configure`)
- modules/hadoop/src/main/scala/scholarchain/hadoop/BlockLogic.scala (fallback keys, deterministic same-ID choice)
- modules/hadoop/src/main/scala/scholarchain/hadoop/ExactLogic.scala (J03 consolidation, exact-key cap)
- modules/hadoop/src/main/scala/scholarchain/hadoop/DblpParser.scala (`invalidIdentifiers`)
- modules/hadoop/src/main/scala/scholarchain/hadoop/DblpInputFormat.scala (declared encoding, next-start-tag recovery)
- modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala (reducers and mapper wiring, streaming export reducer)
- modules/hadoop/src/main/scala/scholarchain/hadoop/Counters.scala (two new counters)
- tests/module-unit/hadoop/JobLogicSuite.scala (`workBlockResult` now returns a Vector, so two `.head` calls)
- tests/module-unit/hadoop/RejectsAndLimitsSuite.scala (one expected counter map, derivation below)
- tests/module-unit/hadoop/PipelineIntegrationSuite.scala (manifest assertion uses `Pipeline.LogicVersion`; compression test split in two)
- `sbt fmt` was run once and reformatted my sources.
I did not touch tests/adversarial/**, any MODULE.md or other doc, or core.

COMMANDS RUN (I saw the output of each; sbt always sourced from toolchain/env.sh, one sbt at a time):
- `sbt "hadoop/testOnly <Suite>"` for each suite I touched, and `sbt "adversarialTests/testOnly scholarchain.adversarial.hadoop.<Suite>"` for CommonNameSuite, DuplicateIdSuite, GiantTeamSuite, MissingFieldSuite, DblpXmlAttackSuite and UnicodeSuite.
- `sbt fmt`.
- One `sbt hadoop/test`. It was moved to the background by the harness at 120 s. I read its output file once. It showed 1 red test, my own new compression test, which I fixed and re-ran. I started no second sbt while it was running.
- `sbt check`, once at the end, in the foreground. Exit 0.
- One sbt invocation with two `hadoop/Test/runMain scholarchain.hadoop.LocalRunner` runs. My first attempt used relative dataset paths. It failed on a missing path, because the forked JVM runs from `modules/hadoop`. I re-ran with absolute paths.

TEST RESULTS (final `sbt check`, exit 0):
- core: 94 of 94 passed.
- hadoop: 98 of 98 passed. Before the repair it was 79. The 19 new tests are RepairArgsSuite 2, RepairLogicSuite 6, RepairDblpSuite 10, and 1 net new in PipelineIntegrationSuite (the compression test became two).
- adversarialTests: 68 of 68 passed. The 5 red tests are now green:
  - CommonNameSuite fallback Wang and fallback title (B1).
  - DuplicateIdSuite J03 duplicate (B3).
  - MissingFieldSuite invalid ORCID (B2).
  - GiantTeamSuite cap flag with no value (B4).
- scalafmt check and compile passed.

Per-brief changes:

B4, strict arguments (`PipelineArgs.parse`). A usage error (exit 2) now follows from any of these:
- a token in a name position that does not start with `--`;
- a name outside the 10 documented ones;
- a name with no value, or a value that starts with `--`;
- a repeated name;
- `--compress` other than exactly `true` or `false`.
Tests: RepairArgsSuite.

B3, J03 consolidation (`ExactLogic.consolidate`):
- References are grouped by id. The kept copy is the smallest JSON (not the first to arrive), and the result is sorted by id.
- DUPLICATE_RECORDS now counts the copies beyond the first of a work reference (plus the existing count of sources holding more than one distinct work). CONFLICT_RECORDS counts the work ids whose copies differ (plus the existing title and ORCID-name conflicts).
- Mention and profile references are also consolidated deterministically, but only work references are counted.
- Counts are per key. A repeated OpenAlex work with a DOI and an OpenAlex ID counts once under `doi:` and once under `openalex:`.
- The same deterministic choice now applies to `workBlockResult` (smallest JSON per workId) and to `clusters` (identical copies collapse, sorted by mentionId then JSON).
- Test: both arrival orders give an equal result.

B2, `DblpParser.invalidIdentifiers(parsed)`. I used a separate function instead of changing the `Outcome.Retained` shape, because existing tests pattern-match it. It counts:
- an `orcid` attribute that is present but invalid;
- an `ee` whose host is doi.org or dx.doi.org and that fails `Normalize.doi`;
- an `ee` whose host is arxiv.org or www.arxiv.org and that is not an arXiv ID.
Ordinary URLs are never counted. DblpExtractMapper adds the count to INVALID_IDENTIFIER next to ACCEPTED_RECORDS.

B1, fallback keys. Policy for documentation-engineer:
- Trigger: a researcher block with more than `maxBlock` clusters. The existing split by full name is kept, and fallback blocks are added.
- Researcher fallback keys: `<key>|co:<coauthorKey>`, `<key>|v:<venueId>` and `<key>|y:<year>`.
- Work fallback keys: `<wk key>|v:<venueId or lower-cased venueName>` and `<wk key>|a:<author key>` for every author key. The brief said first author, but `WorkSummary` has no author order, so I used every author key. This is a superset.
- A fallback block is compared only when it has at most `maxBlock` members and at least 2.
- It is never built from an empty attribute.
- Two clusters that share a work are never paired.
- A researcher fallback group where every cluster shares the same single work is skipped silently.
- A pair already emitted, in the primary sub-blocks or in an earlier fallback block, is not emitted again. The check is by the unordered pair of endpoint IDs.
- A fallback block above `maxBlock` is refused. It is counted and gets a reject line.
- Blocks above the hard cap of 10 times `maxBlock` stay refused whole, as before.
- Block keys always contain `|`, so they never equal the bare primary key.
- `BlockResult` gained `fallback: Boolean = false`. `BlockLogic.workBlockResult` now returns `Vector[BlockResult]`.

B5, declared encoding. The rule is the human's, as decided on 2026-11-23:
- The charset is read once per file by `DblpRecordReader.charsetOf` from the first 1024 bytes at offset 0. It does this whatever the split start, and decompresses `.gz` first.
- A leading UTF-8 BOM is skipped.
- `encoding="..."` or `'...'` is read from the `<?xml ... ?>` prolog and resolved through `Charset.forName`.
- No prolog or no declaration means UTF-8.
- Each record is decoded with a REPORT decoder, so bytes invalid in the declared encoding give a BadEncoding reject. With ISO-8859-1 every byte is valid, so only the XML character rules reject, such as a raw 0x01.
- The file is refused as a whole, as one unit with a counter, in these cases:
  - a UTF-16 or UTF-32 BOM or byte pattern;
  - a declared encoding that is unknown;
  - a declared encoding that is not UTF-8 and not single-byte ASCII-compatible (so Shift_JIS, GBK and the like);
  - a UTF-8 BOM next to a non-UTF-8 declaration.
- The refusal unit is emitted once, by the split that holds byte 0, with key -1 and value `UnsupportedEncoding:<reason>`. Other splits emit nothing. The mapper counts UNSUPPORTED_ENCODING and writes a reject `unsupported encoding: <reason>`.
- `DblpRecordScanner` has a new last constructor parameter `charset`, default UTF-8.
- A BadEncoding unit's value is `BadEncoding:<head>\n[charset=<name>]`. The value still starts with `<` after the colon, which the adversarial reader test requires. The reject reason reads `unreadable record (BadEncoding, <charset>) at byte N`. The head is decoded in the declared charset and the reject file is UTF-8 JSON.
- Byte offsets no longer use `getBytes(UTF_8)`, because `expand` was removed (B6).
- Pinned by tests:
  - raw 0xE9 in ISO-8859-1 gives "René Dupont";
  - the same bytes declared UTF-8, or undeclared, give a BadEncoding reject naming the charset;
  - UTF-8 bytes C3 A9 in an ISO-8859-1 document give "RenÃ© Dupont";
  - raw 0x01 is rejected by the parser;
  - the real `DblpRecordReader` on `FileSplit`s at sizes 30, 64, 100, 257 and 1000, plus `.gz`, gives identical output;
  - a UTF-16 declaration gives exactly one unit, at 2 split sizes;
  - a full LocalJobRunner run on a Latin-1 file gives the accented author in the J01 output, and a UTF-16 file gives INPUT 1, REJECTED 1, UNSUPPORTED_ENCODING 1.

B6, unclosed records. `readRecord` now ends the record as Truncated or Oversize at a byte-level `<` followed by a record-type name and a space or `>`. It does this with mark/reset and a one-'<' pushback (`carry`). `expand` was removed, offsets are exact, and split N and N+1 agree. Tests:
- an unclosed phdthesis followed by 1000 articles gives 1001 units (1 Truncated, 1000 Complete);
- the same result at 3 split sizes, through the reader at split size 4096, and for `.gz`;
- an Oversize unclosed record is bounded by the next start tag.

B7, `ExportReducer` iterates once and keeps the first value, the minimum, a count and a differs flag. DUPLICATE is count minus 1, and CONFLICT is raised if any value differs from the first. PipelineIntegrationSuite J07 (DUPLICATE 30 and the exported counts) is unchanged and green. I added no separate unit test, because the reducer needs a Hadoop context and that suite covers it. `UniqueReducer` (J01 and J06) still buffers per key. Its groups are small.

B8, the manifest field is now `Pipeline.LogicVersion = "hadoop-v2"`, bumped from the constant "hadoop-v1" (this includes the run-ID change below). It is bumped on any logic change. Inputs at a path are treated as immutable per snapshot, and that is stated in the scaladoc. The only test touching it compared the manifest to the literal, and it now uses the constant.

B9, J03 caps work pairs per exact key at `--max-block` (200 by default):
- A key with more works than the cap gets no work pairs.
- It counts OVERSIZE_BLOCKS and writes a reject line `exact key <key> holds N works > M; work pairs not expanded`.
- Mention and profile links are unaffected.
- `ExactLogic.exactJoin` gained a `maxGroup` parameter (default unlimited) and `ExactResult.oversize`.
- Test: 6 works at cap 5 give none, and 5 works give 10 pairs.

B10, `Pipeline.configure(conf, runId, a)` now holds the shared job settings. A new test asserts `mapreduce.map.output.compress` is true, the codec is DefaultCodec and the run ID is set. The old test is renamed "final outputs are compressed".

ITEMS FOR documentation-engineer

New or renamed counters:
- J04: FALLBACK_BLOCKS_COMPARED and FALLBACK_BLOCKS_REFUSED. Refused fallback blocks also increment REJECTED_RECORDS with a reject line, but they do not increment OVERSIZE_BLOCKS.
- J01: UNSUPPORTED_ENCODING. INVALID_IDENTIFIER now also counts in J01.
- J03: OVERSIZE_BLOCKS and a reject line when an exact key is above the cap. DUPLICATE_RECORDS and CONFLICT_RECORDS have the new meaning above.
- No counter was renamed.

J03 DUPLICATE and CONFLICT semantics:
- DUPLICATE = (copies of a work reference beyond the first, per key and per work id) + (sources holding more than one distinct work under the key).
- CONFLICT = (work ids with differing copies) + (pairs of works with unrelated titles) + (ORCID name conflicts).
- The kept copy is the smallest JSON.

Fallback-key policy: see B1 above.

Encoding rule and refusals: see B5 above. The whole file's charset is read once from byte 0, so splits never disagree.

Run-ID manifest change: the `pipeline` field is `Pipeline.LogicVersion` ("hadoop-v2"), so all run IDs differ from before the repair.

Other changes documentation-engineer should record:
- Args are strict (B4).
- An unclosed record ends at the next start tag, so a rejected unit no longer swallows its neighbours and results do not depend on split size.
- The J07 reducer is streaming.
- `BlockLogic.workBlockResult` now returns a Vector.

Hand-derived expectation changed (one):
- RejectsAndLimitsSuite "J03: works without an exact identifier are counted as missing keys". The J03 counter map gains `DUPLICATE_RECORDS -> 2`; every other entry is unchanged (INPUT 9, ACCEPTED 9, MISSING_KEY 7, MISSING_DOI 7).
- Derivation: the 2 OpenAlex lines are the first fixture line given twice, which is the same work twice (the J05 comment already says so). It carries a DOI and an OpenAlex ID, so it reaches J03 twice under `doi:<x>` and twice under `openalex:W…`. That is 1 extra copy per key, 2 in total. The copies are identical, so there is no conflict.
- The count was observed first and then derived, because the test had failed.
- Every other hand-derived number was unchanged.

VALIDATION RESULTS (post-repair local bounded runs, `--snapshot 2026-11-22`, one sbt invocation, both exit 0):

1. Fixtures: dblp-fixture.xml, openalex, crossref and orcid fixtures. Run ID `run-8b3a162615a54f8f`.
- Max team 12 (`dblp:conf/fixa/ArcherTeam22`). Largest pair expansion 66.
- Largest blocking group `r:archer_a`, 13 members.
- J01: INPUT 17, ACCEPTED 15, SKIPPED 2 (www 1, proceedings 1), UNIQUE 15, no rejects.
- J02: OPENALEX 12 accepted (12 works, 12 topic rows, 5 institution rows). CROSSREF 8 accepted. ORCID 2 accepted.
- J03: INPUT 37, ACCEPTED 37, EXACT_CANDIDATES 51, no duplicate, conflict or oversize.
- J04: INPUT 35, BLOCKED_CANDIDATES 142, no fallback or oversize counters.
- J05: 35 teams, 93 author papers, 127 pairs before the cap. J06: EMITTED_PAIRS 127.
- J07: INPUT 307, DUPLICATE 30. Exported: works 35, teams 35, pairs 127, authorpapers 93, mentions 93, venues 11, topics 2, institutions 1.

2. Real sample: dblp-sample.xml, openalex-works.jsonl, crossref-works.jsonl. Run ID `run-6d1175dbfdb874a9`.
- Max team 5 (`crossref:10.1007/978-0-387-31439-6_184`). Largest pair expansion 10.
- Largest blocking group `r:drew_m`, 18 members.
- J01: INPUT 40, ACCEPTED 40 (all incollection). J02: OPENALEX 25 accepted (25 works, 75 topic rows, 51 institution rows). CROSSREF 25 accepted.
- J03: INPUT 90, EXACT_CANDIDATES 109. J04: BLOCKED_CANDIDATES 309.
- J05: 90 teams, 219 author papers, 186 pairs before the cap. J06: EMITTED_PAIRS 186.
- J07: INPUT 711, DUPLICATE 69. Exported: works 90, teams 90, pairs 186, authorpapers 219, mentions 219, venues 1, topics 44, institutions 37.

- No rejects, OVERSIZE_BLOCKS, oversize teams or FALLBACK counters appeared in either run. Neither input triggers the new paths.
- Both samples declare ISO-8859-1 and are pure ASCII, so they decode the same as before.
- The output directories are in the scratchpad (`.../scratchpad/out-fix` and `out-real`).

ASSUMPTIONS:
- ISO-8859-1 decoding of C1 bytes (0x80 to 0x9F) is accepted, because XML 1.0 allows C1 characters. Only C0 controls other than tab, LF and CR are rejected, and the StAX parser does that.
- Only single-byte ASCII-compatible encodings (and UTF-8) are accepted. This is stricter than the brief's "not ASCII-compatible" list. I added multi-byte legacy encodings (Shift_JIS and the like) because their trail bytes can equal `<`, which would make byte scanning unsafe.
- A prolog is found only at the very start of the file, after an optional BOM, within the first 1024 bytes. Anything else counts as undeclared UTF-8.
- B9 reuses `--max-block` as the exact-key cap. I added no new flag.

RISKS:
- The J03 reducer still buffers all values of a key in memory (`valueStrings`). B9 bounds the quadratic pairs, not the memory of a giant group. Only the J04 reducer has a hard buffer cap.
- The fallback year key (`y:`) is weak on big inputs: popular years give refused fallback blocks, each a reject line. The volume is bounded by the number of distinct fallback keys. Coauthor and venue keys carry the real gain.
- Mention fallback blocks are compared inside one buffered primary block only. A block above the hard cap is still refused whole.
- A record whose own content holds a literal `<article ` or similar inside text or CDATA would now be cut as Truncated. Valid DBLP never does this, because the `<` is escaped.
- The `.gz` input is still read by one mapper.
- Not unit-tested separately: the streaming `ExportReducer` (covered by the pipeline J07 test) and the refusal of a UTF-8 BOM next to a non-UTF-8 declaration (tested at charset-detection level only).
- Not done: nothing carried forward. B8, B9 and B10 are done. F11 and the documentation corrections (D1) remain for documentation-engineer. Documentation that is now stale includes the MODULE.md lines on UTF-8 strictness, split independence and `.gitkeep`, and the open encoding item in STATUS.md.
- State left behind: sbt `target/` refreshed, and the two local run output folders in the scratchpad. No cloud action, no commit.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer (D1, using the items above), then test-engineer reruns adversarialTests (68 of 68 green now), then Review 2 by a fresh code-reviewer.
