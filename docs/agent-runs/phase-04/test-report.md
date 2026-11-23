## Phase 04 T3 report (test-engineer)

HARNESS: claude-code
MODEL: opus (claude-opus-5-5), high tier (the role's default). The mapping is taken from AGENTS.md; I did not re-read `.agents/models.json`.

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 04, task T3, re-run from the beginning. The task is the Phase 4 gate cases in `tests/adversarial/`, Hadoop only: adversarial XML (DOCTYPE/ENTITY, malformed, truncated, oversize, split boundaries), Unicode, missing fields, duplicate IDs, common-name blocks and giant teams. The tests use only the documented public entry points, and every expected count is derived by hand.

FILES CREATED:
All seven `.scala` drafts were untrusted. I deleted every one unread beyond its test names and helper, then rewrote each from scratch from the spec and the MODULE.md Public API. The file names are reused.
- /home/ajcreator/scholarchain/tests/adversarial/scholarchain/adversarial/hadoop/HadoopBlackBox.scala
- /home/ajcreator/scholarchain/tests/adversarial/scholarchain/adversarial/hadoop/DblpXmlAttackSuite.scala
- /home/ajcreator/scholarchain/tests/adversarial/scholarchain/adversarial/hadoop/UnicodeSuite.scala
- /home/ajcreator/scholarchain/tests/adversarial/scholarchain/adversarial/hadoop/MissingFieldSuite.scala
- /home/ajcreator/scholarchain/tests/adversarial/scholarchain/adversarial/hadoop/DuplicateIdSuite.scala
- /home/ajcreator/scholarchain/tests/adversarial/scholarchain/adversarial/hadoop/CommonNameSuite.scala
- /home/ajcreator/scholarchain/tests/adversarial/scholarchain/adversarial/hadoop/GiantTeamSuite.scala

FILES MODIFIED:
- /home/ajcreator/scholarchain/tests/adversarial/resources/logback-test.xml: kept. Its configuration is unchanged. I rewrote the comment so it states only what I observed: SLF4J reported logback as the actual provider in these runs.

Every file under `tests/adversarial/` when I finished (`find tests/adversarial -type f`), exactly:
- `tests/adversarial/.gitkeep` (pre-existing from Phase 02, untouched)
- `tests/adversarial/resources/logback-test.xml`
- `tests/adversarial/scholarchain/adversarial/hadoop/CommonNameSuite.scala`
- `tests/adversarial/scholarchain/adversarial/hadoop/DblpXmlAttackSuite.scala`
- `tests/adversarial/scholarchain/adversarial/hadoop/DuplicateIdSuite.scala`
- `tests/adversarial/scholarchain/adversarial/hadoop/GiantTeamSuite.scala`
- `tests/adversarial/scholarchain/adversarial/hadoop/HadoopBlackBox.scala`
- `tests/adversarial/scholarchain/adversarial/hadoop/MissingFieldSuite.scala`
- `tests/adversarial/scholarchain/adversarial/hadoop/UnicodeSuite.scala`

COMMANDS RUN (all in the foreground, one sbt at a time, after `source toolchain/env.sh`; I saw the output of each):
- `javap -public` on the compiled classes `Pipeline`, `PipelineArgs`, `DblpInputFormat`, `LocalRunner`, `JsonLines$` and `Validate$`. This prints signatures only, no method bodies. I used it to get the exact types of the documented entry points.
- `python3 -I` to compute and verify ORCID MOD 11-2 check digits.
- `sbt "adversarialTests/testOnly scholarchain.adversarial.hadoop.<Suite>"`, once or a few times per suite while I worked on it. Every run completed; the last exits were Giant 1, XML 0, Unicode 0, MissingField 1, DuplicateId 1, CommonName 1, and each exit 1 is a fair red test.
- `sbt "adversarialTests/Test/scalafmt" check`, once at the end. Exit 1, caused only by the 5 red tests below. scalafmt reformatted 1 file. `scalafmtCheckAll`, `scalafmtSbtCheck`, `compile` and `Test/compile` all succeeded. The test-run marker was not touched, because tests failed.
- One shell command was blocked by guard-dangerous before it ran: my output filter named the marker task. I reran without that word; nothing executed.

TEST RESULTS (the final `sbt check`):
- core: 94 total, 94 passed (NormalizeSuite 4, IdsSuite 12, ValidateSuite 25, SerializationSuite 29, ConfigSuite 24).
- hadoop: 79 total, 79 passed (RejectsAndLimitsSuite 10, DblpParserSuite 22, NormalizerSuite 10, JobLogicSuite 20, PipelineIntegrationSuite 17).
- adversarialTests: 68 total, 63 passed, 5 failed:

| Suite | Total | Passed | Failed |
|---|---|---|---|
| DblpXmlAttackSuite | 13 | 13 | 0 |
| UnicodeSuite | 8 | 8 | 0 |
| MissingFieldSuite | 12 | 11 | 1 |
| DuplicateIdSuite | 11 | 10 | 1 |
| CommonNameSuite | 10 | 8 | 2 |
| GiantTeamSuite | 14 | 13 | 1 |

The 5 failing tests. Each is left red; the verbatim output is under RISKS.

1. `CommonNameSuite` "fallback keys: the oversize name block still compares the two Wangs sharing context"
   - Spec line 2245: "The blocking design must include maximum block size, fallback keys, counters, and a policy for oversize groups"; job 4.
   - Defect: there are no fallback keys. When a name block is oversize, nobody in it is compared, including two mentions that share coauthor, venue and year.
2. `CommonNameSuite` "fallback keys: the oversize title block still compares the two works sharing context"
   - Same clause.
   - Defect: an oversize title block yields nothing, even for two works that share title, year, venue and first author.
3. `MissingFieldSuite` "DBLP: a dropped invalid ORCID is counted as INVALID_IDENTIFIER"
   - MODULE.md "Behavior to rely on": "Invalid DOI, ORCID and arXiv values are dropped and counted as INVALID_IDENTIFIER".
   - Defect: the invalid ORCID is dropped (a separate test passes), but INVALID_IDENTIFIER is 0 in every stage, in both the summary and every `_counters.json`.
4. `DuplicateIdSuite` "exact identifiers: a DOI shared by 3 works, one of them given twice, is counted in J03"
   - Spec job 3: "Emit exact-identifier keys and duplicate or conflict counters".
   - Defect: J03 has DUPLICATE_RECORDS = 0 and CONFLICT_RECORDS = 0, although the doi key has 3 distinct holders and one holder appears twice.
   - Observed per stage (stage, DUPLICATE_RECORDS, CONFLICT_RECORDS): (J01,2,1), (J03,0,0), (J05,1,0), (J07,3,0).
5. `GiantTeamSuite` "a cap flag with no value is a usage error, never silently replaced by the default"
   - MODULE.md: arguments come as `--name value` pairs, and an invalid value is a usage error (exit 2).
   - Defect: `PipelineArgs.parse` accepts a trailing `--max-team` (and the test also covers `--max-block`) and returns `maxTeam=300`, the default. The cap the operator asked for is silently ignored.

VALIDATION RESULTS: hand derivations and what each suite now protects.

DblpXmlAttackSuite (13 tests, all pass):
- Record reader, called directly (`DblpInputFormat`, new API):
  - Split sizes tested: 64, 100, 128, 257 and 1000 bytes, and one whole split.
  - Clean document: 24 publications plus 1 `www` and 1 `proceedings` record = 26 keys. At every split size each key arrives exactly once as a whole unit, and there are no status units. The 64-byte size gives more than one split.
  - Hostile document, cap 1000 bytes: 3 complete records (ok1, ok2, ok3) and 5 status units (oversize, bad UTF-8 `C3 28`, overlong `C0 AF`, CESU surrogate `ED A0 80`, truncated tail) at every split size. Each status key equals -(byte offset + 1), with the offsets computed from the bytes I wrote.
- Hostile run, 8 units = 3 accepted + 5 rejected:
  - J01 INPUT_RECORDS = 8, REJECTED_RECORDS = 5, SKIPPED_RECORDS = 0, and 5 reject lines, each naming exactly one bad key.
  - Every reject line has a stage, a reason and the run ID, and its input is at most 2000 characters (the oversize title is 3004 characters).
  - The reject file is strict UTF-8.
  - Teams = 3. Pairs = 1 + 3 + 1 = 5.
  - The title "Multi\n  Line\tTitle" normalizes to "multi line title".
  - `A &amp;lt; B` decodes once, to `A &lt; B`.
- DTD run (internal subset with a `file://` XXE, a 6-level nested entity expansion, and an `<!ENTITY` inside a record):
  - The canary file text, the expanded inner title and the expanded "LOLA" strings appear in no output.
  - Total output is under 2 MB.
  - safe1 and safe2 are kept. xxe, lol and inner are each counted exactly once; all three were observed as rejected.
- Split run with `split.maxsize` = 128:
  - Works 24. Teams 24. Author papers = 6 blocks × (1+2+3+4) = 60. Pairs = 6 blocks × (0+1+3+6) = 60. SKIPPED = 2, REJECTED = 0, INPUT = 26.
  - Compared with the whole-split run: same run ID, and identical J01, team and pair lines.

UnicodeSuite (8 tests, all pass). One document starting with a byte order mark (BOM):
- 8 units = 6 works + 2 rejects (`&#0;` and `&#xD800;`).
- An NFD name and title are stored NFC, equal to the NFC record.
- The NFC and NFD mentions are linked by a blocked candidate (`blockKey` "nunez_j").
- The Cyrillic-А name is kept exactly. Observed normalized names: "аnna kowalska" versus "anna kowalska", so the two are not merged.
- Characters outside the Basic Multilingual Plane (U+1F600, U+2000B) survive, and no U+FFFD appears in any data output.
- `&uuml;`, `&#233;` and `&#xE9;` decode; `&amp;amp;` decodes once.
- Every work and mention passes `Validate`.

MissingFieldSuite (12 tests, 11 pass). DBLP has 17 units:
- 5 records must be kept: full, noee, goodorcid, badorcid, baddoi.
- 6 must be rejected: empty author, blank author, year 2020.5, no key, `key=""`, a key containing a space.
- 6 may go either way: noyear, noauthor, badyear, oldyear, notitle, novenue. Observed: noyear, noauthor and novenue kept (noyear with a null year); badyear, oldyear and notitle rejected.
- Observed J01: INPUT 17, ACCEPTED 8, REJECTED 9, MISSING_KEY 2. Works + rejects = 17.
- No year is invented, and the work without authors has no pair.
- Invalid ORCID and DOI values are dropped and the records kept, but nothing is counted (test 3 above).
- OpenAlex: 5 lines (INPUT 5): 1 kept, 3 rejected for certain, and the bare record was observed rejected.
- Crossref: 3 lines: 1 kept, with DOI `10.5555/adv.mf.cr1`, and 2 rejected.
- ORCID: 4 lines: the valid iD is kept, the record with no iD is rejected, the invalid iD is never emitted, and kept + rejected = 4.
- Every retained work from every source passes `Validate.work`.

DuplicateIdSuite (11 tests, 10 pass):
- DBLP: 4 distinct works. CONFLICT_RECORDS = 1 (exact). DUPLICATE_RECORDS must be 1 or 2; 2 was observed.
- The kept conflict version is one whole input, never a blend. Version X (Alpha, 2021, 2 authors) was kept, and it is identical when the input order is reversed.
- 6 teams, one per work ID.
- Pairs = 1 (same) + C(2,2) (conf) + 1 (solo) + 1 (doi) + 1 (OpenAlex, given twice) + 1 (Crossref) = 6.
- Three DOI spellings normalize to `10.5555/adv.dup.1`.
- Exact candidates: C(3,2) = 3. Each is distinct, prefixed `mc:`, has sourceId < targetId, and none is a self pair.
- Export: 6 works and 6 teams.
- Replay into a new output root gives the same run ID and the same lines in J01, J03, teams, author papers, J06, export works and export pairs.
- A rerun into the same root leaves the output unchanged.
- 1 reducer and the default 4 give the same lines once the run ID is set aside.

CommonNameSuite (10 tests, 8 pass). One run with `--max-block 5`:
- Works = 8 + 2 + 7 + 5 + 2 + 60 = 84. Every work has 2 authors, so 168 author papers, 84 pairs and 84 teams.
- Observed: OVERSIZE_BLOCKS = 3 (Wang, Zhang above the hard cap of 50, and the title block), with 3 reject lines matching REJECTED_RECORDS.
- No candidate comes from the Wang or title blocks, and the 60 identical Zhang mentions give 0 candidates, which is not quadratic.
- The block exactly at the cap (5 Rays) is compared: 10 candidates.
- `largestBlockObserved` = {r:zhang_h, 60}.
- Homonym suffixes 0001 and 0002 give a candidate flagged `dblp_homonym_distinct`.
- Fallback keys are missing (tests 1 and 2 above).

GiantTeamSuite (14 tests, 13 pass):
- Run 1, `--max-team 12`, 6 works:
  - Teams of 1, 2, 3 and 12 authors give 0, 1, 3 and 66 pairs. The repeated-author paper gives 3. Total 73 pairs, and J06 EMITTED_PAIRS = 73.
  - Each expanded team yields every unordered position pair exactly once.
  - Every pair passes `Validate.pair`, and its work ID, year, team size and DBLP provenance (snapshot and run ID) match its team.
  - Team mention order matches the author positions.
  - The 13-author team is refused: OVERSIZE_TEAMS = 1 and PAIRS_REFUSED_BY_CAP = 78 summed over all stages, with a reject line in J06. Its team record and 13 author papers are kept.
  - The summary gives `maxTeamObserved` = {dblp:g/n13, 13}. `largestPairExpansion` was observed as 78, so it counts the refused team.
  - Author papers = 1 + 2 + 3 + 12 + 3 + 13 = 34.
- Run 2, default cap of 300: the 301-author paper refuses 45150 pairs, the 25-author paper gives 300, and author papers = 326.
- Usage errors: caps below 2, non-integers, an int overflow, `--reducers 0` and impossible snapshot dates are all refused by `PipelineArgs.parse`.

ASSUMPTIONS:
- I read only the spec, STATUS.md, `prompt.md`, the hadoop and core MODULE.md files, the frozen core wire examples (`tests/module-unit/core/resources/wire/`, read for field names) and `datasets/samples/**`. I never read `src/main/**`.
  - The `javap -public` signatures come from compiled main classes. They are declarations only, the same information compiler errors would give, and I disclose them here.
  - I read the old drafts only for their helper and test names, then deleted them.
- A DBLP work ID is `dblp:<key>` and its sourceRecordId is the key. Both come from the core wire example.
- The pipeline runs through `ToolRunner.run(conf, new Pipeline(), args)` on a plain local Hadoop configuration (`file:///`, `mapreduce.framework.name=local`, `io.sort.mb=16` to fit the machine). I did not use `LocalRunner.main`, because its exit behavior is not documented and could kill the sbt JVM.
- Stage folders and counters are read from the documented `run-manifests/<runId>-summary.json`, which maps each stage to its `output` and `counters`.
- The "either" cases in MissingFieldSuite deliberately accept keep-or-reject, because MODULE.md does not say which DBLP fields are required.

RISKS:
- Verbatim output of the 5 red tests, from the final `sbt check`. They are left red for code-reviewer's fairness verdict:
```text
==> X scholarchain.adversarial.hadoop.CommonNameSuite.fallback keys: the oversize name block still compares the two Wangs sharing context 0.038s munit.FailException: tests/adversarial/scholarchain/adversarial/hadoop/CommonNameSuite.scala:184 the oversize Wang block left no fallback comparison for c/wang9-c/wang10
==> X scholarchain.adversarial.hadoop.CommonNameSuite.fallback keys: the oversize title block still compares the two works sharing context 0.004s munit.FailException: tests/adversarial/scholarchain/adversarial/hadoop/CommonNameSuite.scala:191 the oversize title block left no fallback comparison for title6-title7
==> X scholarchain.adversarial.hadoop.MissingFieldSuite.DBLP: a dropped invalid ORCID is counted as INVALID_IDENTIFIER 0.009s munit.FailException: tests/adversarial/scholarchain/adversarial/hadoop/MissingFieldSuite.scala:177 INVALID_IDENTIFIER is 0 in every stage
==> X scholarchain.adversarial.hadoop.GiantTeamSuite.a cap flag with no value is a usage error, never silently replaced by the default 0.004s munit.FailException: tests/adversarial/scholarchain/adversarial/hadoop/GiantTeamSuite.scala:215 accepted --max-team as Right(PipelineArgs(d.xml,None,None,None,o,2026-11-22,300,200,4,true))
==> X scholarchain.adversarial.hadoop.DuplicateIdSuite.exact identifiers: a DOI shared by 3 works, one of them given twice, is counted in J03 0.004s munit.FailException: tests/adversarial/scholarchain/adversarial/hadoop/DuplicateIdSuite.scala:157 J03 DUPLICATE_RECORDS + CONFLICT_RECORDS = 0
[error] Failed: Total 68, Failed 5, Errors 0, Passed 63
```
- Fairness notes for code-reviewer:
  - Failures 1 and 2 rest on spec line 2245. MODULE.md documents only the `r:` and `wk:` key families, with no fallback keys.
  - Failure 4 holds under either reading of "duplicate" in J03.
  - The draft's earlier failing common-name tests built the oversize block from 10 identical DBLP strings. I judged that input unfair: MODULE.md says identical raw strings are one source cluster, and the pipeline linked them linearly (n-1 `same_source_cluster` candidates). I redesigned the case with 10 distinct people and added a separate linear-bound test for the identical-string case.
- MODULE.md is imprecise in these places; each is observed behavior, not documented behavior:
  - The status value is documented as `STATUS:<head>`, but observed as `Oversize:<head>`, `BadEncoding:…` and `Truncated:…`.
  - "`<!DOCTYPE`/`<!ENTITY` refused": a header with an internal subset is tolerated. Records that use undeclared entities are rejected ("undeclared entities: xxe"), and so is an `<!ENTITY` inside a record.
  - Name-block size is counted in source clusters, with undocumented sub-blocks such as "wang_w/wei wang" (9 clusters). A candidate's `blockKey` drops the `r:` prefix, but the summary key keeps it.
  - DUPLICATE_RECORDS counting semantics are not documented.
  - Whether `largestPairExpansion` includes refused teams is not documented (78 observed).
  - Undocumented counters: DBLP_TYPE_ARTICLE, UNIQUE_RECORDS, WORKS_WRITTEN, TOPICS_WRITTEN and MISSING_DOI.
- Not done, deliberately:
  - No test of `--compress false` or of `LocalRunner.main`.
  - No exactly-300-author expansion (44850 pairs), to stay within memory.
  - No test of an ISO-8859-1-declared DBLP file with raw high bytes against strict UTF-8. The fixture declares ISO-8859-1, so this is a real-data question for the human or hadoop-engineer.
  - No Phase 11 categories beyond Hadoop.
- adversarialTests is not forked, so all 14 pipeline runs execute in the sbt JVM, serialized by a JVM-wide lock. During `sbt check` they ran at the same time as the forked hadoop tests without trouble. The fork setting belongs to build-engineer if memory becomes a problem.
- State left behind: the files listed above are untracked. The suites deleted their temp folders (0 `/tmp/sc-adv-*` left). Scratch logs are in `/tmp/claude-1000/-home-ajcreator-scholarchain/aa3a3dc1-c39c-43bf-9bb9-1fff257eea96/scratchpad/`. No production or module-unit file was touched.

MANUAL ACTION REQUIRED: None for test-engineer. The human may want to decide how DBLP's ISO-8859-1 declaration should interact with strict UTF-8.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer, through the orchestrator: a fairness verdict on the 5 red tests, then repair briefs to hadoop-engineer if they are judged fair. After the fix, test-engineer reruns `adversarialTests`.
