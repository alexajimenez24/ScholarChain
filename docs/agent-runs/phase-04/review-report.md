HARNESS: claude-code
MODEL: opus, effort high (the high tier in `.agents/models.json`; no override)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04, Review 1. I reviewed the whole uncommitted Phase 04 change against HEAD 524dc1d: build.sbt, modules/hadoop/**, tests/module-unit/hadoop/**, tests/adversarial/**, modules/hadoop/MODULE.md, docs/hadoop-jobs.md and STATUS.md. The work order also asked for fairness verdicts on the 5 red adversarial tests, and a repair brief for the human's declared-encoding decision of 2026-11-23.

FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (I saw the output of each):
- `git status --short`, `git diff --stat`, `git diff build.sbt`, `git diff STATUS.md`, `git ls-files` on the touched folders, and `git diff HEAD --stat -- project/ modules/core/ datasets/` (empty: no core, version-ledger or dataset change).
- `find` and `wc` over the new files. `head`, `grep -c -P '[\x80-\xff]'` and `cmp` on the 4 DBLP XML fixtures and samples: every one declares ISO-8859-1, has 0 bytes at or above 0x80, and the test copies are byte-identical to `datasets/samples/`.
- `grep` over the tests for counters, encodings and CLI flags.
- `cat .agents/models.json`.
- `source toolchain/env.sh; timeout 590 sbt check`, run once, in the foreground. Its log is at /tmp/claude-1000/-home-ajcreator-scholarchain/aa3a3dc1-c39c-43bf-9bb9-1fff257eea96/scratchpad/review-check.log.

TEST RESULTS (that single `sbt check`, exit 1, total 116 s):
- core: 94 passed of 94.
- hadoop: 79 passed of 79.
- adversarialTests: 68 total, 63 passed, 5 failed. These are exactly the 5 tests in test-report.md, with the same messages and line numbers (CommonNameSuite:184 and :191, MissingFieldSuite:177, GiantTeamSuite:215, DuplicateIdSuite:157).
- scalafmt check and compile passed. Red was confirmed only on the 5 listed tests.

VALIDATION RESULTS

Verdict: REQUEST_CHANGES

All 5 red tests are FAIR. There are also 4 MAJOR and 6 MINOR findings beyond those tests. Every finding below was checked again by reading the file at the cited lines. F5 and F6 are confirmed by reading the code only. I did not run them, because the work order allows one sbt run.

Ownership: no breach found.
- build.sbt is build-engineer's: hadoop project only, Test-scope runtime, fork, -Xmx1g, no version moved, project/ untouched.
- modules/hadoop/src/** and tests/module-unit/hadoop/** are hadoop-engineer's.
- tests/adversarial/** is test-engineer's.
- MODULE.md, docs/hadoop-jobs.md and STATUS.md are documentation-engineer's.
- docs/agent-runs/phase-04/** is the orchestrator's. There is no human-gate.md.
- Core is unchanged. No control-plane file changed.
- Each report's model matches its role's tier.

Findings, ranked:

F1 MAJOR. No fallback keys for oversize blocks. These are red tests 1 and 2.
- Files:
  - modules/hadoop/src/main/scala/scholarchain/hadoop/BlockLogic.scala:140-157 (name blocks)
  - modules/hadoop/src/main/scala/scholarchain/hadoop/BlockLogic.scala:241-249 (work blocks)
- Owner: hadoop-engineer.
- Name blocks have one fallback level, split by full normalized name. A part that is still oversize gets no further fallback. Coauthor context, venue and year are never used as block keys; they are only features.
- Failure input (CommonNameSuite, `--max-block 5`):
  - "Wei Wang" (c/wang9) and "W. Wang" (c/wang10) share coauthor, venue and year.
  - "Wei Wang" lands in sub-block `wang_w/wei wang`, which has 9 clusters, more than 5, so it is refused. "W. Wang" sits alone in `w wang`.
  - The two are never compared.
- Work blocks have no fallback at all: 7 same-title works with `--max-block 5` give 0 candidates (BlockLogic.scala:248-249).
- Impact: this breaks spec line 2245 ("maximum block size, fallback keys, counters, and a policy for oversize groups") and job 4 (blocks use coauthor context, year, venue). Likely matches inside common-name and common-title groups are lost before entity resolution.

F2 MAJOR. J03 consolidates same-ID references silently and by arrival order. This is red test 4.
- Files:
  - modules/hadoop/src/main/scala/scholarchain/hadoop/ExactLogic.scala:79-82 (`distinctBy(_.id)` keeps the first arrival; `duplicates` counts only distinct same-source holders)
  - modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala:243-253
  - The same pattern is at BlockLogic.scala:247.
- Owner: hadoop-engineer.
- Failure input 1:
  - An OpenAlex line given twice. J02 is map-only, so the work reaches J03 twice under `doi:` and `openalex:`.
  - J03 drops the copy with no counter: DUPLICATE and CONFLICT stay 0 (observed).
- Failure input 2:
  - Two OpenAlex lines with the same id but different `publication_year`, plus a DBLP work with the same DOI.
  - The IdRef kept is whichever arrives first in the reduce iterator, so `year_distance` and `title_jaccard` depend on the shuffle merge order.
  - On a cluster that order follows fetch timing.
- Impact: spec job 3 asks for duplicate and conflict counters, and it is missing here. The gate rules "Deterministic outputs" and "Repeated runs ... equivalent logical output" are not guaranteed for that input.

F3 MAJOR. An unclosed DBLP record that ends as Truncated or Oversize swallows the valid records after it. The loss is silent and depends on the split size.
- File: modules/hadoop/src/main/scala/scholarchain/hadoop/DblpInputFormat.scala:106-108 (`expand` returns a non-Complete unit unexpanded) and :143-162 (`readRecord` reads to the matching close tag or to end of input).
- Owner: hadoop-engineer.
- Failure input: `<phdthesis key="x"><author>A</author><title>t` (never closed), then 1000 complete `<article>` records, then `</dblp>`, with no later `</phdthesis>`.
  - One split: one Truncated reject (Oversize above 4 MB). INPUT_RECORDS = 1, and the 1000 articles disappear with no counter.
  - Several splits: only the articles that start in later splits are recovered.
  - `.gz` input (DBLP ships as dblp.xml.gz) cannot be split, so the rest of the file is lost.
- Impact: valid records are lost, against MODULE.md "a rejected unit is never dropped silently" and "the result does not depend on the split size" (MODULE.md:209-211). The gate item "DBLP parser handles record boundaries correctly" fails on this input.
- MODULE.md documents only the case above 4 MB, and only for neighbours in the same split.

F4 MAJOR. J07 reduce groups for venues, topics and institutions are unbounded and held whole in memory.
- Files:
  - modules/hadoop/src/main/scala/scholarchain/hadoop/ExportLogic.scala:52-55 (one venue row per work, keyed `venueId\tvenues\tvenueId`)
  - modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala:472-482 (topic and institution rows per work)
  - modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala:492-500 (`valueStrings(values)` turns the whole group into a Vector, then `distinct` and `min`)
- Owner: hadoop-engineer.
- Failure input: the full DBLP stream `journals/corr`, roughly 700k records. All 700k venue rows go to one reducer and are held as Strings plus a distinct set. Popular OpenAlex topics behave the same at scale.
- Impact: a giant reducer group, which spec line 2245 names as a correctness and availability risk. Fixtures cannot show it.

F5 MAJOR (human decision 2026-11-23). The DBLP reader ignores the encoding declared in the XML prolog.
- File: modules/hadoop/src/main/scala/scholarchain/hadoop/DblpInputFormat.scala:160-174 (strict UTF-8 always), :115 (byte offsets from UTF-8 length) and :156 (reject head decoded as UTF-8).
- Owner: hadoop-engineer.
- Failure input: `<?xml version="1.0" encoding="ISO-8859-1"?>` with raw byte 0xE9 in `Ren\xE9 Dupont`. Today the record becomes a BadEncoding reject. Under the human's rule it must decode to "René Dupont".

F6 MINOR. A missing flag value, an unknown flag or a bad `--compress` value is silently ignored. This is red test 5.
- File: modules/hadoop/src/main/scala/scholarchain/hadoop/Pipeline.scala:70-101.
- Owner: hadoop-engineer.
- `args.grouped(2).collect{case Seq(k,v) ...}` drops these cases:
  - a trailing `--max-team` with no value (the default 300 is used);
  - a misspelled `--max-teams 5` (the default is used);
  - `--compress yes` or `--compress FALSE` (both mean compress on);
  - a repeated flag (the last one wins).
- Impact: the run uses different caps than the operator asked for, against MODULE.md "invalid value ... exits 2".

F7 MINOR. A dropped invalid DBLP ORCID or doi.org link is not counted. This is red test 3.
- Files:
  - modules/hadoop/src/main/scala/scholarchain/hadoop/DblpParser.scala:154 (`orcid.flatMap(Normalize.orcid)`)
  - modules/hadoop/src/main/scala/scholarchain/hadoop/DblpParser.scala:160-162
  - modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala:128-135 (no INVALID_IDENTIFIER in J01)
- Owner: hadoop-engineer.
- J02 counts dropped identifiers (SourceNormalizers.scala:116, 154-155, 218 and 320). J01 does not.

F8 MINOR. The run ID covers neither the code version nor the input content, so a rerun after a code change reuses stale stage outputs.
- File: modules/hadoop/src/main/scala/scholarchain/hadoop/Pipeline.scala:40-62 (the manifest has the constant `"pipeline" -> "hadoop-v1"` and the input path strings only) and :202-209 (a stage with `_SUCCESS` is skipped).
- Owner: hadoop-engineer.
- Failure input: run LocalRunner on the fixtures into `out/`, apply this phase's repair, then rerun with the same arguments into `out/`.
  - Every stage is skipped.
  - The old outputs and counters are reported under the same run_id.
- Impact: the run ID no longer identifies the logic that produced the data (hard rule 3, "policy ... version").

F9 MINOR. J03 work pairs are quadratic per exact key, with no cap.
- File: modules/hadoop/src/main/scala/scholarchain/hadoop/ExactLogic.scala:89-93.
- Owner: hadoop-engineer.
- Failure input: one DOI shared by 2000 records (a volume DOI in `ee`, or a bad source DOI) gives about 2 million candidates in one reducer. There is no counter and no reject.

F10 MINOR. A test name claims more than the test checks.
- File: tests/module-unit/hadoop/PipelineIntegrationSuite.scala:304-315.
- Owner: hadoop-engineer.
- The test "intermediate and final outputs are compressed" checks only that final `part-*` files end in `.gz`. It would still pass with `mapreduce.map.output.compress` off (Pipeline.scala:164-169).

F11 MINOR. Documentation says things the code does not do.
- Owner: documentation-engineer.
- modules/hadoop/MODULE.md:
  - :123 says "`<!DOCTYPE` and `<!ENTITY` in DBLP input are refused". Only a declaration inside a record is refused; a header internal subset is tolerated.
  - :160 says "A block above `--max-block` ... yields no candidates". The block is split by full name first, and refused parts keep their within-cluster `same_source_cluster` links.
  - :13 says `.gitkeep` remains only in `java` and `resources`. `src/main/scala/.gitkeep` is also present.
  - :120 ("Strict UTF-8 is enforced") and :209-211 (split independence) must change after F5 and F3.
- docs/hadoop-jobs.md does not give the per-job items of spec lines 2196-2210:
  - combiner behaviour (there is none, and the file should say so);
  - expected number of reducers per job;
  - S3 input and output prefixes;
  - codec per job;
  - the idempotency rule per job;
  - the test fixture per job;
  - reject conditions per job.
- Lines 236-240 hold the encoding question, which the human has now decided.

Fairness verdicts (checked against the spec and the MODULE.md Public API, not the implementation):

1. CommonNameSuite "fallback keys: the oversize name block still compares the two Wangs sharing context": FAIR.
   - Spec line 2245 requires fallback keys and a policy for oversize groups.
   - Job 4 lists coauthor context, year and venue as blocking inputs.
   - The test does not force one design: a fallback key built from coauthor, venue or year would each pair c/wang9 and c/wang10, and nothing else in the block shares those values.
   - The only fallback today is full name. It cannot pair an initial variant ("W. Wang") with a full name, and it leaves the 9-cluster "wei wang" part with no further fallback.
2. CommonNameSuite "fallback keys: the oversize title block still compares the two works sharing context": FAIR. Work blocks have no fallback key at all (BlockLogic.scala:248-249), so the clause in spec line 2245 is plainly unmet. The two works share venue and first author.
3. MissingFieldSuite "DBLP: a dropped invalid ORCID is counted as INVALID_IDENTIFIER": FAIR. The MODULE.md Public API ("Behavior to rely on") says invalid DOI, ORCID and arXiv values are dropped and counted as INVALID_IDENTIFIER, with no source restriction. The test asks for at least 1 across all stages, which is the weakest form of that promise.
4. DuplicateIdSuite "exact identifiers: a DOI shared by 3 works, one of them given twice, is counted in J03": FAIR.
   - Spec job 3 requires J03 to emit duplicate or conflict counters.
   - J03 received a literal duplicate record under an exact key and consolidated it with no count (ExactLogic.scala:79).
   - The test accepts either reading of "duplicate" (`n >= 1`), so it does not impose one semantics.
   - The repair only needs reading 2, counting the repeated record. It must not be read as requiring cross-source holders to count as duplicates.
5. GiantTeamSuite "a cap flag with no value is a usage error, never silently replaced by the default": FAIR. MODULE.md says arguments come as `--name value` pairs and an invalid value is a usage error with exit 2. A flag with no value is not a valid pair, so quietly using the default goes against the documented contract.

Repair briefs, grouped by owner.

hadoop-engineer (one repair round, in this order: B4, B3, B2, B1, B5 with B6 because both touch the scanner, then B7; B8 to B10 may be carried forward if the budget runs out):

B1 (F1, red tests 1 and 2). Fallback keys for oversize blocks.
- Likely cause: BlockLogic.block has one fallback level (full name), and workBlockResult has none.
- Fix:
  - For a buffered block (members up to `hardCap`) whose primary block or full-name part is oversize, re-block the members by fallback keys:
    - for mentions: `<key>|co:<coauthorKey>` per coauthor key, `<key>|v:<venueId>` and `<key>|y:<year>`;
    - for works: `<wk key>|v:<venueId or venueName>` and `<wk key>|a:<first author key>`.
  - Compare only inside fallback blocks of at most `maxBlock` members. Count refused fallback blocks in a new counter.
  - Remove duplicate pairs reached through several fallback keys, within the reducer call and by candidate ID, so each pair is emitted once.
  - Never build a fallback block from an empty attribute, and never pair two mentions of the same work.
  - Blocks above the hard cap stay refused. Document that as the policy.
  - Use blockKeys that never equal the bare primary key.
- What else could break:
  - RejectsAndLimitsSuite:238-264 asserts the exact J04 counter map `{INPUT 2, ACCEPTED 2, OVERSIZE_BLOCKS 1, REJECTED 1}`. The 25 "John Smith 00NN" are all on one paper. With the same-work exclusion they create no fallback block. A design that counts or rejects more there must re-derive those numbers by hand.
  - JobLogicSuite:277-306 asserts `results.size == 2` and that the refused part has no candidates. Its summaries have no coauthors, venue or year, so they must produce no fallback result.
- Must stay green: all 79 hadoop tests, including PipelineIntegrationSuite J04 (142 candidates, no oversize block), and the 8 other CommonNameSuite tests:
  - no `wang_w` key;
  - at most 10 Wang-Wang candidates;
  - Zhang stays linear;
  - at most 10 title candidates;
  - Rays compared;
  - largest block 60;
  - the homonym flag.
- Report the new counters for documentation-engineer.

B2 (F7, red test 3). Count INVALID_IDENTIFIER in J01.
- Likely cause: DblpParser.toWork drops an invalid `orcid` attribute and an unparseable doi.org `ee` without any count.
- Fix:
  - Return an invalid-identifier count with `Outcome.Retained`.
  - Count an `orcid` attribute that is present but invalid.
  - Count an `ee` whose host is doi.org or dx.doi.org but fails `Normalize.doi`.
  - Do not count ordinary non-DOI `ee` URLs, and do not count arXiv links that are not arXiv-shaped unless the host is arxiv.org.
  - Increment the counter in DblpExtractMapper next to ACCEPTED.
- What else could break: PipelineIntegrationSuite J01 counter assertions, if any assert an exact counter map. The fixtures and the real sample have 3 valid ORCIDs and valid DOIs, so the expected value stays 0.
- Must stay green: DblpParserSuite:67 (ORCID and DOI handling), MissingFieldSuite (12), NormalizerSuite:143.

B3 (F2, red test 4). Count and resolve same-ID references in J03.
- Fix in ExactLogic.exactJoin, before `distinctBy`:
  - group work references by `id`;
  - DUPLICATE_RECORDS += copies beyond the first;
  - CONFLICT_RECORDS += 1 for each id whose copies differ;
  - keep the smallest encoded JSON (as UniqueReducer does), not the first arrival;
  - do the same for mention and profile references, or state that only work references are counted.
- Keep the current same-source-distinct-holder count.
- Counts are per key: one repeated OpenAlex work with a DOI and an OpenAlex ID counts under both keys. Say so in the report.
- Apply the same deterministic choice in BlockLogic.workBlockResult:247 and in `clusters` (sort by mentionId, then by JSON).
- What else could break: PipelineIntegrationSuite:123 "J03 ... no conflicts, no same-source duplicates". It stays 0 only if no fixture record repeats; verify by hand. Also JobLogicSuite:113-150, where `duplicates == 1` and `conflicts` are asserted.
- Must stay green: DuplicateIdSuite (the 10 passing tests, including replay, same-root rerun and the 1 versus 4 reducer equality), and JobLogicSuite exact-key tests.
- Add a unit test with two same-ID references in both arrival orders and check that the output is equal.

B4 (F6, red test 5). Parse arguments strictly.
- Fix in PipelineArgs.parse. Walk the tokens and fail with a usage error on:
  - a token in a name position that does not start with `--`;
  - a name outside the 10 documented ones;
  - a name with no following value, or a value that starts with `--`;
  - a repeated name;
  - `--compress` other than exactly `true` or `false`.
- What else could break: LocalRunner.main reads `--output` itself and is unaffected. Every flag the tests use is a documented one (I grepped: dblp, output, snapshot, openalex, crossref, orcid, max-team, max-block, reducers).
- Must stay green: PipelineIntegrationSuite:407 (exit 2) and the GiantTeamSuite cap and snapshot usage tests. Add unit cases for an unknown flag, a repeated flag and `--compress yes`.

B5 (F5, human decision). Decode DBLP in the encoding declared in the XML prolog.
- Rule from the human: decode with the declared encoding (ISO-8859-1 for DBLP, UTF-8 when none is declared) and reject bytes that are invalid in that encoding.
- Find the charset once per file, never per split:
  - In DblpRecordReader.initialize, read the first 1 KB or so of the file from offset 0, decompressing `.gz` first, whatever the split start.
  - Skip a leading UTF-8 BOM (EF BB BF).
  - Parse `encoding="..."` or `'...'`, case-insensitive, through `Charset.forName` aliases.
  - No declaration, or no prolog, means UTF-8.
- Refuse these, with a reason and a counter, instead of falling back to UTF-8:
  - an encoding that is not ASCII-compatible, such as UTF-16 or UTF-32 (by BOM or by declaration), because byte scanning needs ASCII-compatible markup;
  - an unknown encoding name;
  - a UTF-8 BOM next to a non-UTF-8 declaration (a fatal error in XML 1.0 Appendix F).
- Pass the charset to DblpRecordScanner as a new constructor parameter that defaults to UTF-8, so direct scanner tests keep their meaning.
- Decode each record with `charset.newDecoder()` set to REPORT, and make BadEncoding name the charset.
  - Java's ISO-8859-1 maps all 256 bytes, so under ISO-8859-1 the only rejects come from XML character rules: C0 controls other than tab, LF and CR are refused by the StAX parser.
- Compute every byte offset in the declared charset, never with `getBytes(UTF_8)` (DblpInputFormat.scala:115). Better, do recovery at byte level (B6).
  - With raw Latin-1 high bytes, UTF-8 lengths make `restOffset` too large.
  - The `offset < end` filter could then drop a recovered record that the next split never sees.
- Decode the reject head (DblpInputFormat.scala:156) in the declared charset, leniently, for display only. The reject file stays UTF-8 JSON.
- Tests to add in tests/module-unit/hadoop:
  - An ISO-8859-1-declared document with raw byte 0xE9 in an author name gives a Complete record with rawName "René Dupont", NFC and no U+FFFD. Run it through DblpRecordReader with FileSplits at every split size, including splits that start after the prolog, and through LocalJobRunner: the output must be identical.
  - The same bytes declared UTF-8, or undeclared, give a BadEncoding reject.
  - An ISO-8859-1-declared document holding UTF-8 bytes C3 A9 decodes to "Ã©". That is what the rule says; pin it so the choice is visible.
  - A UTF-16 or unknown declaration is rejected with a reason and counted.
  - A raw 0x01 in an ISO-8859-1 document is rejected by the parser.
- How existing tests read under the declared-encoding rule (all stay green as written):
  - DblpParserSuite:264 (0xFF 0xFE): no prolog means UTF-8, so it stays BadEncoding.
  - DblpParserSuite:192 (multi-byte UTF-8 at every split) has no prolog and is unchanged.
  - DblpParserSuite:159, :174, :184 and :284, RejectsAndLimitsSuite and PipelineIntegrationSuite run on files declared ISO-8859-1 that are pure ASCII (I counted 0 bytes at or above 0x80). Their hand-derived counts do not change. The direct `scan` helper decodes them as UTF-8 by default, which is the same for ASCII.
  - Adversarial DblpXmlAttackSuite hostile bytes (C3 28, C0 AF, ED A0 80) sit in documents declared UTF-8 (HadoopBlackBox.scala:226, DblpXmlAttackSuite.scala:296). They stay BadEncoding, and the 5-status-unit expectations at every split size hold.
  - UnicodeSuite (UTF-8 BOM plus a UTF-8 declaration) is unchanged, provided BOM skipping happens before prolog parsing.
- Report the rule for documentation-engineer.

B6 (F3). Stop a record at the next top-level record start tag.
- Likely cause: readRecord runs to the matching close tag or to end of input. `expand` repairs only Complete bodies, at string level.
- Fix:
  - In readRecord, end the current record as Truncated when a byte-level `<` followed by a record-type name and `' '` or `'>'` appears before the close tag (DBLP records never nest).
  - Resume scanning at that tag (keep it with mark/reset or a one-tag pushback).
  - This removes `expand`, gives exact byte offsets, bounds an unclosed record by its neighbour, and makes split N and split N+1 agree.
- What else could break: DblpParserSuite:213-252 (truncated, unclosed-recovered, split inside an unclosed record) and RejectsAndLimitsSuite J01 (17 units = 9 + 1 + 7, with j/rec/1 and j/rec/2 recovered).
- Add the F3 failure input as a test: an unclosed `phdthesis` followed by complete articles and end of input, with the same result at every split size and for `.gz`.

B7 (F4). Stream the export reducer.
- Fix: in ExportReducer, iterate once, keeping the first value, the minimum and a count, and set the conflict flag when any value differs from the first. Optionally deduplicate venue, topic and institution rows in the mapper with a per-task set; that is per-task state, which is allowed.
- What else could break: PipelineIntegrationSuite:241-285 asserts J07 DUPLICATE 30, conflicts 0 and the exported counts. The streaming reducer keeps those numbers; mapper-side deduplication would change DUPLICATE and needs a hand re-derivation.

B8 (F8). Put a pipeline logic version into the manifest.
- Use a constant in the hadoop module that is bumped on any logic change, or a build hash.
- State that inputs at a path are treated as immutable per snapshot.
- Run IDs in the tests and docs change. The tests compare run IDs between runs, not to literals; verify.

B9 (F9). Cap exact-key work pairs.
- Above a cap (reuse `--max-block` or a new `--max-exact`), refuse the group with OVERSIZE_BLOCKS (or a new counter) and a reject line.
- The fixtures stay under the cap.

B10 (F10). Make the compression test check map output.
- Assert `mapreduce.map.output.compress` and its codec through the job configuration, or rename the test to "final outputs are compressed".

documentation-engineer (after the hadoop-engineer repair, from its report):

D1 (F11). Correct the documentation.
- modules/hadoop/MODULE.md:
  - :13, :120, :123, :160 and :209-211, as listed under F11;
  - the new counters and the J03 DUPLICATE and CONFLICT semantics;
  - the declared-encoding rule;
  - remove the 4 open items once they are fixed.
- docs/hadoop-jobs.md:
  - add the per-job items of spec lines 2196-2210 (combiner: none; reducers; S3 prefixes; codec; idempotency rule; test fixture; reject conditions);
  - replace the open encoding question (lines 236-240) and the STATUS.md open decision with the human's 2026-11-23 decision;
  - use only numbers observed after the repair.

test-engineer: no test change is required, because all 5 verdicts are FAIR. After the repair, rerun `adversarialTests`. Once MODULE.md documents the declared-encoding rule, test-engineer may add a black-box ISO-8859-1 case as its own task.

Tests that must stay green across the round:
- core: 94 of 94.
- hadoop: 79 of 79. Any changed hand-derived expectation needs a written derivation in the report.
- adversarialTests: the 63 passing tests, plus the 5 now-red tests turning green.

ASSUMPTIONS:
- A red adversarial test is judged FAIR when the spec or the MODULE.md Public API supports it under at least one reasonable reading, and the test does not require a particular implementation design.
- For red test 4, I took the spec's job 3 counters to cover duplicates that J03 itself receives.
- The scale figures in F4 (journals/corr at about 700k records) come from general knowledge of DBLP, not from this repository's data.
- F2 (order dependence), F3 and F4 are confirmed by reading the code, not by running it.

RISKS (what I did not review, or reviewed only partly):
- I did not read these whole:
  - NormalizerSuite;
  - the parts of JobLogicSuite outside lines 15-56 and 277-343;
  - PipelineIntegrationSuite outside the parts cited;
  - TestSupport.scala;
  - HadoopBlackBox.scala;
  - most of DblpXmlAttackSuite, UnicodeSuite and GiantTeamSuite;
  - Similarity.scala past its first 80 lines.
- I did not recompute the hand derivations (for example 51, 142 and 30). The tests pass, so I checked only that the code agrees with them, not that they are independent of it.
- I did not read the step-00, step-01s and step-02s STATUS reports closely.
- I did not run the LocalRunner command, `sbt packageJobs`, the job jar on EMR, or the Scala 3 runtime on the cluster classpath. All are unobserved.
- test-engineer used `javap -public` on compiled main classes and disclosed it. These are signatures only, not `src/main`, so I raise no finding. The orchestrator may want to state whether this is allowed.
- RejectRecord output does not carry the snapshot (only the path does). Discarded conflicting versions in J01 and J05 are counted, but not written anywhere. Neither is raised as a finding.
- State left behind: one sbt log in the scratchpad folder. sbt `target/` contents were refreshed by `sbt check`. The working tree is unchanged (`git status` was the same before and after).

MANUAL ACTION REQUIRED: none. The encoding decision is already made. The 5 verdicts are all FAIR, so no spec question goes to the human.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Repair round 1, routed by the orchestrator: hadoop-engineer (B1 to B10, with B1 to B7 required for APPROVE), then documentation-engineer (D1), then a test-engineer rerun of adversarialTests, then Review 2 by a fresh code-reviewer.
