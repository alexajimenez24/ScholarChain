HARNESS: claude-code
MODEL: opus, effort high (the high tier in `.agents/models.json`; no override)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04, Review 2. This re-checks the whole uncommitted Phase 04 change against HEAD 524dc1d after the one repair round (R1a hadoop-engineer, R1b documentation-engineer, R1c test-engineer). It covers: the status of F1 to F11; regressions; a judgment on the two R1c observations; doc numbers checked against the saved reports; and a final verdict with carried-forward items. Under the human decision of 2026-11-23 there is no second repair round.

FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (I saw the successful output of each):
- `git status --short`, `git diff --stat`, `git diff build.sbt PROJECT_MEMORY.md`.
- `git diff HEAD --stat -- project/ modules/core/ datasets/ .claude .agents AGENTS.md CLAUDE.md docs/agents.md`: empty.
- `ls -a` on the 3 hadoop source roots; `find`/`wc`; `grep`/`sed` over sources, tests, docs and the saved reports.
- `sha256sum` on `tests/adversarial/**`: all 8 prefixes match `repair-01-test-engineer.md`.
- `grep` of the R1c log in the scratchpad (`r1c-check.log`) for the reject reasons.
- `source toolchain/env.sh && timeout 590 sbt -Dsbt.color=false check`, run once, in the foreground. Output was filtered through grep and not saved to a file. Exit 0, 116 s.
- One `jshell` probe (JDK 17.0.21 from the toolchain, -Xmx128m) on `Charset.forName(...).newEncoder()` for ISO-2022-CN, x-JISAutoDetect, x-IBM834 and UTF-7.

TEST RESULTS (my one `sbt check`, exit 0):
- core: 94 passed of 94.
- hadoop: 98 passed of 98.
- adversarialTests: 68 passed of 68.
- Format check and compile succeeded. markTestRun reported "14 project test runs passed"; the touch came from `sbt check` itself.
- The same log prints the defect behind N1, verbatim: `OBSERVE DblpXmlAttackSuite hostile J01 reject reasons: Vector(unreadable record (OversizeNone) at byte 264, unreadable record (BadEncodingSome(, UTF-8)) at byte 3649, ... unreadable record (TruncatedNone) at byte 4377)`.

VALIDATION RESULTS

Verdict: APPROVE WITH CARRIED-FORWARD ITEMS. In the role's binary terms this is APPROVE for the Phase 04 gate, with 7 MINOR findings carried forward to the human. All 11 Review 1 findings are resolved. No CRITICAL or MAJOR finding remains.

Ownership: no breach.
- The repair touched only hadoop-engineer files (modules/hadoop/src/**, tests/module-unit/hadoop/**) and documentation-engineer files (MODULE.md, docs/hadoop-jobs.md, STATUS.md, PROJECT_MEMORY.md).
- tests/adversarial/** is byte-identical to T3.
- build.sbt has only the T1 change (hadoop project, Test scope, fork, -Xmx1g, no version moved).
- core, project/, datasets/ and the control plane are unchanged.
- No human-gate.md was written.
- Each report's model matches its role's tier.

1. Review 1 findings

- F1 (MAJOR, fallback keys): RESOLVED.
  - Code: BlockLogic.scala:168 and :174-219 (researcher keys `|co:`, `|v:`, `|y:`); :315-322 and :350-379 (work keys `|v:`, `|a:`).
  - Behaviour: never built from an empty attribute (:182-184, :357-359); same-work pairs excluded (:189, :205); deduplicated by unordered pair (:190, :216, :346); refused above maxBlock with `fallback=true` (Jobs.scala:340, FALLBACK_BLOCKS_REFUSED).
  - Tests: RepairLogicSuite:57-102; CommonNameSuite red tests 1 and 2 are green.
- F2 (MAJOR, J03 duplicates by arrival order): RESOLVED.
  - Code: ExactLogic.consolidate, ExactLogic.scala:83-88 (smallest JSON, sorted by id, counts extra copies and differing ids), used at :96-99 and :146-147. The same deterministic choice is in BlockLogic.scala:142-147 and :309-314.
  - Tests: RepairLogicSuite:12-25 (both arrival orders equal). DuplicateIdSuite is green.
  - RejectsAndLimitsSuite:156-170 now expects DUPLICATE_RECORDS 2. The derivation in the comment is correct: one OpenAlex work twice, under `doi:` and `openalex:`.
- F3 (MAJOR, unclosed record swallows neighbours): RESOLVED.
  - Code: DblpInputFormat.scala:185-209. A record ends Truncated or Oversize at the next byte-level record start tag (`startTagFollows` :172-179, `carry` :125 and :141). `expand` is removed.
  - I traced the split hand-off: a nested tag at p >= end is left to split N+1, and one at p < end is read by split N. They agree.
  - Tests: RepairDblpSuite:189-225 (1000 articles after an unclosed record, at split sizes 37, 500 and 4096, through the real reader, and `.gz`; the Oversize case is bounded too).
- F4 (MAJOR, unbounded J07 groups): RESOLVED for J07.
  - ExportReducer (Jobs.scala:510-530) now streams with constant memory.
  - It is covered by PipelineIntegrationSuite J07 (DUPLICATE 30; exported counts unchanged). It has no dedicated unit test, which is disclosed.
  - The J03 buffer remains; see carried-forward N5.
- F5 (MAJOR, declared encoding, human decision a): RESOLVED.
  - Code: `detectCharset` DblpInputFormat.scala:71-103 (BOM, prolog regex, UTF-8 default, refusals); `charsetOf` :232-240 reads once from byte 0, decompressing `.gz`; REPORT decoder :211-221; only the byte-0 split emits the refusal unit (:276-284).
  - The human's required test with a raw ISO-8859-1 high byte is there: RepairDblpSuite:76-88 (0xE9 gives "René Dupont", with no C3 A9 in the bytes) and :141-157 (full LocalJobRunner run).
  - Also tested: UTF-8 or undeclared gives BadEncoding (:90-97); C3 A9 under Latin-1 gives "RenÃ©" (:99-103); raw 0x01 is rejected (:105-109); split sizes 30, 64, 100, 257 and 1000 plus gz are identical (:111-128); UTF-16 gives one unit (:130-139).
  - One gap: see N2.
- F6 (MINOR, loose args): RESOLVED. Pipeline.scala:83-114. Tests: RepairArgsSuite (7 bad cases). GiantTeamSuite red test 5 is green.
- F7 (MINOR, J01 INVALID_IDENTIFIER): RESOLVED. DblpParser.scala:143-149 and Jobs.scala:144. Tests: RepairLogicSuite:105-116. MissingFieldSuite red test 3 is green; R1c observed J01 INVALID_IDENTIFIER 2.
- F8 (MINOR, run ID ignores the code version): RESOLVED. `Pipeline.LogicVersion = "hadoop-v2"` is in the manifest (Pipeline.scala:42, :403). Test: PipelineIntegrationSuite:66. A manual bump is the chosen mechanism, and it is documented.
- F9 (MINOR, quadratic J03 pairs): RESOLVED for pairs.
  - Code: ExactLogic.scala:106-116; Jobs.scala:253-265 (OVERSIZE_BLOCKS plus a reject line).
  - Tests: RepairLogicSuite:27-33 (logic level only; see N7).
- F10 (MINOR, compression test name): RESOLVED. `Pipeline.configure` (Pipeline.scala:408-416) is what `run` uses (:186). Test: PipelineIntegrationSuite:304-313. The old test is renamed (:315).
- F11 (MINOR, docs): RESOLVED.
  - MODULE.md: :13 (the `.gitkeep` claim is true; checked with `ls -a`), :133-157 encoding rule, :160-163 DOCTYPE, :235-258 oversize and fallback, :312-317 split independence.
  - docs/hadoop-jobs.md:61-99 now has the spec 2196-2210 items: combiner none, reducers, S3 prefixes deferred to Phase 13, codec, idempotency, fixtures, and a per-job reject table. The encoding question is replaced (:315-321).
  - Residues are under N1 (MODULE.md:155 states a format the code does not produce) and N6 (stale "pending" text).

Reports against files:
- hadoop-engineer's claims match the code and my `sbt check` (98 hadoop tests, 19 new), with one exception, under N1: repair-01-hadoop-engineer.md:95 says the reason reads `unreadable record (BadEncoding, <charset>) at byte N`, which the code does not produce.
- documentation-engineer's edits match its report.
- test-engineer's hashes and totals match what I observed.

2. Regression check
- Determinism: every reducer picks the smallest JSON or sorts its groups (UniqueReducer, TeamReducer, ExportReducer, consolidate, clusters, workBlockResult). Fallback groups are sorted by key, and the `seen` set is local to each reduce call. No regression.
- Idempotent paths: unchanged (`prepare`, `_SUCCESS`). The run ID changes with LogicVersion, as intended.
- Split independence: improved; see F3 and F5.
- Counters: the mapper identity INPUT = ACCEPTED + REJECTED + SKIPPED holds for the UnsupportedEncoding unit (INPUT 1, REJECTED 1). New counters are wired at Jobs.scala:122, :144, :264 and :340-343.
- Memory: J07 is now streaming, and J04 is still capped by its hard cap. J03 still buffers (N5). Fallback work per J04 call grows (N3).
- Provenance: unchanged in shape; see N4.
- Ownership: clean.
- New defects introduced by the repair: N1 and N2.

3. R1c observations
- (i) Reject-reason text: CONFIRMED DEFECT, MINOR (N1). It is a regression introduced by the repair.
  - At Jobs.scala:125, `cs` is an `Option[String]`, interpolated raw at :129 (`$status$cs`). So every J01 status reject now reads `BadEncodingSome(, UTF-8)`, `TruncatedNone` or `OversizeNone`.
  - MODULE.md:155 states the intended format `unreadable record (BadEncoding, <charset>) at byte N`. The doc is right and the code is wrong, so the doc needs no change once the code is fixed. docs/hadoop-jobs.md states no reason format.
  - The reject `input` field also carries the appended `\n[charset=<name>]` metadata (DblpInputFormat.scala:202, passed as `head` at Jobs.scala:130).
  - No test pins the reason text: RepairDblpSuite:96 checks only the scanner head.
  - Not MAJOR: offsets, counts and the stage are correct, and no record is lost. Only the operator-facing reason string is malformed.
- (ii) `policyVersion` "norm-v1" against manifest "hadoop-v2": NOT a breach of hard rule 3, MINOR design gap (N4).
  - Every candidate carries `runId`. `run-manifests/<runId>.json` records `pipeline: hadoop-v2` and `normalization: norm-v1`, so the producing logic can be recovered.
  - MODULE.md:291-292 documents that provenance carries the normalization version.
  - This predates the repair: it was the same under hadoop-v1.
  - A candidate copied away from its manifest cannot show which blocking policy made it.

4. Doc numbers against the saved reports: all match.
- Post-repair runs `run-8b3a162615a54f8f` and `run-6d1175dbfdb874a9` (17/15/2; 51; 142; 35/93/127; 307/30; 40; 109; 309; 90/219/186; 711/69; 12/66/archer_a 13; 5/10/drew_m 18) match repair-01-hadoop-engineer.md:157-173.
- Pre-repair `run-8019aba484da34ad` and "same numbers" match agent-report.md:88-96.
- Part-file counts and the 700-byte/8-split test match agent-report.md:74-79.
- TR values match test-report.md: split sizes 64/100/128/257/1000; 26 keys; 3004; zhang_h 60; 45150; 8 = 6 + 2.
- Pre-repair values are labelled "before the repair". No invented number found.

Carried-forward items for the human, ranked (all MINOR; there is no second repair round):

N1 MINOR. J01 reject reasons print Scala Option text. This is a regression from the repair.
- Files: modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala:125 and :129; DblpInputFormat.scala:202. Owner: hadoop-engineer.
- Failure input: any DBLP unit that is Oversize, Truncated or BadEncoding. Observed: `unreadable record (BadEncodingSome(, UTF-8)) at byte 3649`, `unreadable record (TruncatedNone) at byte 4377`.
- Impact: malformed reason text in rejects-v1. MODULE.md:155 and repair-01-hadoop-engineer.md:95 claim a format the code does not produce.
- Repair brief:
  - Interpolate `${cs.getOrElse("")}`.
  - Strip the `\n[charset=...]` suffix from the `input` passed to `reject`.
  - Add a module-unit test asserting the exact reason for each of the 3 statuses, for example through a LocalJobRunner run on a document holding one of each.
  - What else could break: DblpXmlAttackSuite reads reasons but asserts only counts and bounds. Keep it green.

N2 MINOR. A decode-only declared charset crashes the reader instead of refusing the file.
- File: DblpInputFormat.scala:100 (`cs.newEncoder()`) and :103 (catches only IllegalArgumentException). Owner: hadoop-engineer.
- Failure input: `<?xml version="1.0" encoding="ISO-2022-CN"?>` (or `x-JISAutoDetect`). My jshell probe on the toolchain JDK 17 showed `newEncoder -> java.lang.UnsupportedOperationException` for both.
- Impact: `initialize` throws in every split. Every attempt fails, so J01 fails and the pipeline exits 1. The human's rule and MODULE.md:147-150 call for one counted UnsupportedEncoding unit instead.
- Repair brief: test `cs.canEncode` before `newEncoder()`, or catch UnsupportedOperationException, and return Left(reason). Add the case to the RepairDblpSuite detection test.

N3 MINOR. Fallback comparison cost and volume are bounded only by the hard cap.
- File: BlockLogic.scala:201-217 (researcher) and :332-347 (works). Owner: hadoop-engineer.
- Detail: `features(a, b)` / `workFeatures` and the candidate are built before `seen.add`, so a pair reached through k fallback keys is computed k times. Total output per block can reach C(hardCap, 2), about 2M at the default 2000, rather than a maxBlock-based bound. MODULE.md does not state this bound.
- Failure input: a block of about 1900 clusters whose clusters share many coauthor, venue and year keys, each group at or under maxBlock.
- Repair brief:
  - Check `pairId` from the representative mention IDs before computing features.
  - Optionally cap total fallback pairs per primary block, with a counter and a reject line.
  - Document the bound.
  - What else could break: RepairLogicSuite:57-102, CommonNameSuite (FALLBACK_BLOCKS_COMPARED 4, 15 candidates) and PipelineIntegrationSuite J04 142.

N4 MINOR. Candidate provenance names only the normalization version (R1c observation ii).
- File: Records.scala:19 and :113. Owner: hadoop-engineer now; domain-engineer when MatchCandidate is promoted to core.
- Impact: the blocking or feature policy is reachable only through runId → manifest (`pipeline: hadoop-v2`), not from the record itself.
- Repair brief: either give MatchCandidate provenance a policy version that includes `Pipeline.LogicVersion` (for example `norm-v1+hadoop-v2`), or have the human accept runId → manifest as the policy carrier and record that in PROJECT_MEMORY.md. Decide before Phase 05 consumes candidates.

N5 MINOR. The J03 reducer still buffers a whole exact-key group. This is the residue of F4 and F9, disclosed.
- File: Jobs.scala:257 (`valueStrings(values)`). Owner: hadoop-engineer.
- Failure input: one DOI shared by a very large number of works or mentions (a volume DOI). The cap at ExactLogic.scala:106 bounds pairs, not memory.
- Repair brief: stream the values. Stop buffering work references beyond `maxGroup + 1` and count the rest; keep mention and profile references bounded too, or refuse the key past a hard cap, as BlockingReducer does (Jobs.scala:313-324). Already listed in MODULE.md:368-370.

N6 MINOR. Documentation is stale after R1c and contains small imprecisions.
- Owner: documentation-engineer (routine post-review update).
- Stale "pending" statements: MODULE.md:348 and docs/hadoop-jobs.md:22-23, :274-280 (R1c is done: 68 of 68, with per-suite counts in repair-01-test-engineer.md:42-49).
- docs/hadoop-jobs.md:291-296 lists 4 numbered items for "5 formerly red tests".
- MODULE.md:57 says "a larger block is not compared"; that is now only true before fallback.
- After N1 is fixed, MODULE.md:155 is correct as written.

N7 MINOR. Job-level test gaps for the new reducer wiring (hard rule 2, partly met at logic level).
- Owners: hadoop-engineer (module-unit); test-engineer for the black-box cases it listed in repair-01-test-engineer.md RISKS 3.
- No test runs a job and asserts:
  - J04 FALLBACK_BLOCKS_REFUSED plus a reject line with OVERSIZE_BLOCKS unchanged (Jobs.scala:340);
  - J03 OVERSIZE_BLOCKS plus a reject line (Jobs.scala:263-265);
  - the J01 reject reason text (N1).
- The logic is unit-tested (RepairLogicSuite:27-33, :71-81). The reducer wiring is not.
- Repair brief: one LocalJobRunner case each with `--max-block` small, asserting the counter maps and the reject reasons. test-engineer adds the planned black-box ISO-8859-1 case.

ASSUMPTIONS:
- "Resolved" means the Review 1 failure input no longer fails and a test pins the fix. Residues outside the original finding's failure input are raised as new items.
- N1 is MINOR because no data or count is wrong; only the reason string is.
- N2 is MINOR because DBLP declares ISO-8859-1. Only hostile or foreign input hits it.
- N3's worst-case figures come from reading the code (bounded by hardCap = 10 x maxBlock). I did not run them.
- The JDK on EMR may differ from the toolchain JDK 17. N2 was observed only on the latter.

RISKS (what I did not review, or reviewed only partly):
- Not read whole:
  - DblpParser.scala beyond lines 143-175;
  - SourceNormalizers, Similarity, TeamLogic, ExportLogic, DblpEntities and Identifiers (unchanged in intent by the repair);
  - DblpParserSuite, NormalizerSuite and JobLogicSuite;
  - PipelineIntegrationSuite outside :60-70 and :295-325;
  - the bodies of the adversarial suites;
  - TestSupport.scala.
- I did not recompute hand derivations other than the RejectsAndLimitsSuite DUPLICATE 2 change.
- Not run: LocalRunner, `sbt packageJobs`, `--compress false`, any EMR or cluster classpath.
- The streaming ExportReducer has no unit test; I checked it by reading the code and through J07 pipeline numbers.
- The UTF-8 BOM plus non-UTF-8 declaration case is tested only at detection level.
- State left behind:
  - my `sbt check` refreshed sbt `target/` and its own marker touch;
  - the jshell probe made the JDK print "Created user preferences directory" (a per-user Java prefs folder under the home directory, outside the repository);
  - `git status` is unchanged.
  - No cloud action, no commit.

MANUAL ACTION REQUIRED:
- The human decides on carrying forward N1 to N7.
- N4 also needs a decision: either a policy version in candidate provenance, or acceptance of runId → manifest as the policy carrier, recorded in PROJECT_MEMORY.md before Phase 05.
- N1 and N2 are small, contained code fixes with clear briefs. If the human waives the one-round budget for them, hadoop-engineer can fix them.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md with this verdict and items N1 to N7 (and the N6 doc refresh). Then comes the human gate for Phase 04: ratify, commit, and decide on the carried-forward items.
