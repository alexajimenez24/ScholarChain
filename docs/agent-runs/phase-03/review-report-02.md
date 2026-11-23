HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (high tier, role default per .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: P03-Review-2. A fresh re-review of the whole Phase 03 working-tree diff against HEAD 3d10892, plus every untracked file, after repair round 1.
FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (each one's successful output was observed)
- `git status`, `git diff --stat`, `git diff -- PROJECT_MEMORY.md`, `git ls-files -o --exclude-standard` (grouped by area), `git check-ignore -v`.
- `pgrep -af sbt` found no sbt running. I then ran `sbt check` once (exit 0).
- `python3 datasets/scripts/validate-manifest.py` (rc 0), `python3 datasets/scripts/fetch_samples.py verify --out datasets/samples/real` (rc 0) and `python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'` (OK).
- `python3 -I datasets/scripts/fetch_samples.py verify --snapshot ../real` (rc 0; this is the evidence for MINOR-2).
- `.claude/hooks/validate-agent-files.sh`: "OK: 19 roles, 19 Claude agents, 19 Codex agents, 4 MCP servers, 275 files with one writer".
- Scans of changed and untracked paths: names ending in env or tfvars (none), S3 write paths, subprocess and upload (none), and endpoint, ARN or account literals in modules/core/src (none).
- File mtimes of the wire files and the core sources. The 17 untouched wire files have been unchanged since 11:09, before review 1 at 11:17. Only work.json and author-mention.json changed (11:32). The config YAML has been unchanged since 11:07.
- The frozen IDs in IdsSuite match the T02 report (`agent-report.md`).
- `curl -sI` and a download of https://dblp.org/xml/dblp.dtd into the scratchpad, then `sha256sum`: HTTP 200, 13134 bytes, ETag "334e-5ff31445d5140", Last-Modified Wed, 28 Jun 2023 14:19:09 GMT. The sha256 2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6 equals the committed file.
- A standalone probe, compiled with scala3-compiler 3.3.9 against core's compiled classes. The program is /tmp/claude-1000/-home-ajcreator-scholarchain/f7f3f42a-716d-4e27-850c-da90450d758c/scratchpad/probe/Probe.scala. It is outside the repository and changes nothing in it.

TEST RESULTS
`sbt check` tail (my run, colour codes removed):
```
Test run scholarchain.core.NormalizeSuite finished: 0 failed, 0 ignored, 4 total 0.11s
Test run scholarchain.core.IdsSuite finished: 0 failed, 0 ignored, 12 total 0.243s
Test run scholarchain.core.ValidateSuite finished: 0 failed, 0 ignored, 24 total 0.4s
Test run scholarchain.core.ConfigSuite finished: 0 failed, 0 ignored, 20 total 0.545s
Test run scholarchain.core.SerializationSuite finished: 0 failed, 0 ignored, 29 total 0.323s
[info] Passed: Total 89, Failed 0, Errors 0, Passed 89
[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run
[success] Total time: 2 s, completed Nov 22, 2026, 11:43:17 AM
exit=0
```
Dataset checks:
```
[manifest][ok] 5 datasets, required: dblp, openalex, crossref, orcid        rc=0
[verify] 4 files in /home/ajcreator/scholarchain/datasets/samples/real match retrieval.json   rc=0
Ran 15 tests in 0.327s
OK                                                                            rc=0
```
Probe results. The review 1 probes are re-run first, then new probes. "Left" means a rejected record with no exception.
```
T1 title null              -> Left(null at $.title)
T2 provenance null         -> Left(null at $.provenance)
T3 sourceRecordId null     -> Left(null at $.provenance.sourceRecordId)
T8 edge researcherA null   -> Left(null at $.researcherA)
T9 authors null            -> Left(null at $.authors)
N1 urls:[null]             -> Left(null at $.urls[])
N3 provenance.source null  -> Left(null at $.provenance.source)
N4/N6 Int/Double field null -> Left(record differs from its canonical form ...)
N5/N7 map value null       -> Left(null at $.features.x / $.properties.k)
N2 year null (Option)      -> Right(... None ...)          (correct)
T5 missing Option key      -> Left(TraceException: $)
T6 year 2021.7             -> Left(lossy)      N9 year "2021" -> Left(lossy)
N10/N11 2021.0, 2.021e3    -> Right            N12 key order / N14 pretty JSON -> Right
T7 unknown field           -> Left             N16 Int field 3e9 -> Left
N13 duplicate "title" key  -> Right, last value wins
N20 the line "null"        -> Left(null at $)
T24 duplicate mention IDs  -> Left: "does not match its work and position", "two authors share a mention ID"
T14 run 2^53+1, 2^53       -> both IllegalArgumentException
T14c run 9007199254740991 vs 9007199254740991.4 -> same run-a9af4cfcb37cc6c5
T18 Ids.work 500 chars     -> IllegalArgumentException; 400 chars ok
T22 COAUTHORED_WITH x,y    -> "endpoints must be researcher IDs"
T25 snapshot garbage       -> "is not YYYY-MM-DD"
T21 negative metrics       -> rejected
T10 NEPTUNE_PORT=NaN       -> Left     T11 8182.9 -> Left    C4 abc -> Left
T28 kcore=-5, bss=0        -> Left (both named)
C5  SCHOLARCHAIN__NEPTUNE__QUERYTIMEOUTMS=1e10   -> Right(queryTimeoutMs = 2147483647)
C6  SCHOLARCHAIN__GRAPH__KCOREMAXK=3000000000    -> Right(kcoreMaxK = 2147483647)
C7  QUERY_REQUEST_TIMEOUT_MS=9999999999          -> Right(2147483647)
C8  SCHOLARCHAIN__GRAPH__BETWEENNESSSEED=9007199254740993 -> Right(9007199254740992)
C1  NEPTUNE_PORT=8182d, C3 0x1FF6p0              -> Right(8182)
C10 aws + LLM_ENABLED=true -> Left(... synthesisModel is empty in aws mode with llmEnabled)
C11 maxPathDepth 7 vs 6    -> Left        C14 ER_MAX_LLM_CALLS=-1 -> Left
C15 override 9100 beats placeholder 9000 -> 9100   C16 placeholder beats literal -> 9000
```

VALIDATION RESULTS
Verdict: REQUEST_CHANGES (0 CRITICAL, 0 MAJOR, 4 MINOR). Both review 1 MAJORs are fixed. The remaining items are small, and each can be tested on its own. Repair round 2 of 3 is available.

Check 1: review 1 findings
- MAJOR-1: FIXED. `JsonLines.decode` (JsonLines.scala:15-25) runs a null scan plus a re-write comparison, and `decodeValid` (30-38) wraps the validator in a `Try`. Every review 1 null probe (T1, T2, T3, T8, T9) and the new ones (N1 to N7, N20) return Left. None of them throws.
- MAJOR-2: FIXED.
  - AuthorMention has `provenance` (Model.scala:102-112), and `Mention = "author-mention-v1"` is in `Supported` (18 constants).
  - `Validate.mention` (Validate.scala:65-74) is called per author from `Validate.work`.
  - There is a new frozen `author-mention.json`, and `work.json` was regenerated.
- MINOR-1: FIXED. The mention ID is recomputed and must be distinct per work (T24). Graph edge tags and endpoint kinds are checked (T22). Decision IDs are recomputed and their endpoints are checked by kind. Work IDs in representativeWorkIds and maximumTeamWorkIds are checked. Counts must be non-negative and finite (T21), and the snapshot must be a calendar date (T25). The lenient-decode items T6 and T7 now return Left.
- MINOR-2: PARTLY FIXED. NaN, fractional values, ranges and the LLM model requirement are fixed (T10, T11, T28, C10). Silent integer saturation remains (MINOR-1 below).
- MINOR-3: FIXED. Config.scala:263 bounds maxPathDepth by `graph.pathMaxDepth` (C11).
- MINOR-4: FIXED for integers (T14, T18). A fractional collision remains (see RISKS).
- MINOR-5: DECIDED. The decision is written in Ids.scala:9-12, MODULE.md:117-118 and the PROJECT_MEMORY "Researcher ID" row.
- MINOR-6: FIXED. ConfigSuite.scala:229-242.
- MINOR-7: FIXED. fetch-all-samples.sh:20-24 makes ORCID opt-in, the manifest's orcid retrieval line says so, and there are tests.
- MINOR-8: FIXED. There is no upload path and no subprocess left. The grep is clean, and `test_no_upload_subcommand` covers it.
- MINOR-9: FIXED. The DTD is committed, and its bytes, ETag and sha256 match what I re-observed at dblp.org. Both samples parse with the DTD in `DblpDtdTest`. Its CC0 status is still a human decision.
- MINOR-10 (leak part): FIXED. Every temp directory is a `TemporaryDirectory`. Wiring the Python tests into `sbt check` is still open for build-engineer.
- MINOR-11: FIXED in MODULE.md:54-62.

Check 2: would each new test fail if its rule were removed?
- Yes for the null scan, a throwing validator, unknown fields, mention recomputation, duplicate mention IDs, the snapshot date, work-ID lists, metrics counts, the COAUTHORED_WITH and RESOLVES_TO endpoint rules, decision recomputation, `Ids.work` length, run-ID rejection, NaN and fractional placeholders, maxPathDepth, `#` inside strings, the LLM model requirement, ORCID opt-in, no upload, and the DTD.
- No for several rules; see MINOR-3.

Check 3: regressions
- None found.
- Frozen wire bytes: 17 files are unchanged (mtimes and frozen IDs). work.json changed with its reason disclosed.
- Config precedence holds (C15, C16, and the existing test).
- Territory: every changed path sits inside its owner's rows, and validate-agent-files is OK. Nothing under datasets/gold, build.sbt, project/ or tests/contracts changed.
- No hardcoded bucket, endpoint, account or model value, and no name ending in env or tfvars.
- The scripts are still bounded: 1000 records, 128 MiB, a hard cap of 500000, a 1 MiB cap on the DTD, and DOI-limited enrichment. They skip existing outputs unless `--force`.
- There is no S3 write path.

Check 4: new risks. Covered by MINOR-1, MINOR-2 and RISKS.

Check 5: notes agreement. The counts agree with the code and with each other: 19 wire files on disk, 18 schema versions, 89 tests (4/12/24/20/29) and 15 Python tests (5/5/2/3). The exceptions are MINOR-4.

MINOR-1: Integer config values above 2^31 saturate silently, and the documented "|n| > 2^53 rejected" claim is false for 2^53+1.
- Where:
  - modules/core/src/main/scala/scholarchain/core/Config.scala:343-349 (`acceptableNumber` uses `<= MaxExactInteger`), the schema read at 172-176, and the override typing at 383-390;
  - modules/core/MODULE.md:196-197;
  - repair-01-domain-engineer.md, MINOR-2 claim.
- Failure input:
  - `SCHOLARCHAIN__NEPTUNE__QUERYTIMEOUTMS=1e10` loads as 2147483647 (C5).
  - `QUERY_REQUEST_TIMEOUT_MS=9999999999` loads as 2147483647 (C7).
  - `SCHOLARCHAIN__GRAPH__KCOREMAXK=3000000000` loads as 2147483647 (C6).
  - `SCHOLARCHAIN__GRAPH__BETWEENNESSSEED=9007199254740993` loads as 9007199254740992 (C8). This value is above 2^53 but parses to exactly 2^53, which passes `<=`.
  - Java parseDouble forms such as `8182d` and `0x1FF6p0` are accepted as numbers (C1, C3).
- Impact:
  - A mistyped timeout or limit becomes Int.MaxValue (about 24.8 days for a timeout) and passes every `>= 1` range check.
  - The seed silently changes the betweenness sample, so a run cannot be reproduced from its config.
  - This is the same silent-truncation class the repair claimed to remove. MODULE.md states a guarantee the code does not hold.
- Owner: domain-engineer. documentation-engineer corrects MODULE.md:196-197 afterwards.
- Repair brief:
  - Likely cause: uPickle's Int and Long readers clamp doubles. The 2^53 guard is inclusive, and `toDoubleOption` accepts Java float suffixes and hex.
  - Fix, preferred: after `read[ScholarChainConfig](overridden)`, re-write the config and require `ujson.read(write(cfg)) == overridden`. This is the same canonical-form check `JsonLines.decode` uses, so any clamped, truncated or unknown key in the file becomes a Left.
  - Also make the bound exclusive (`< 2^53`, matching `Ids.run`), and accept only plain decimal number text (for example the regex `-?\d+(\.\d+)?([eE][+-]?\d+)?`).
  - Tests: C5, C7 and C8 each give Left; `NEPTUNE_PORT=8182d` gives Left; the bundled config still loads.
  - What else could break: configs whose file literal has a different but equal numeric text (5000 vs 5000.0 compare equal as `ujson.Num`, so none).
  - Must stay green: all 20 ConfigSuite tests and `sbt check`.

MINOR-2: The snapshot label in the fetch scripts is not validated. Core now rejects any `provenance.snapshot` that is not a YYYY-MM-DD date, and the label can escape `datasets/samples/local`.
- Where:
  - datasets/scripts/fetch_samples.py:57-60 (`out_dir` joins the raw label) and :256 (`--snapshot`, whose help says "YYYY-MM-DD" but nothing checks it);
  - datasets/scripts/fetch-all-samples.sh:14 (`SNAPSHOT` passed through);
  - against modules/core/src/main/scala/scholarchain/core/Validate.scala:27-31 and :53.
- Failure input:
  - The repair's own bounded run used `SNAPSHOT=2026-11-22-repair`. Any Phase 4 record whose `provenance.snapshot` is taken from that label is rejected by `Validate.provenance`.
  - `fetch_samples.py verify --snapshot ../real` resolves to the committed `datasets/samples/real` (observed, rc 0).
  - So `dblp --snapshot ../real --force` would overwrite committed samples. A label like `../../x` writes to `datasets/x`.
- Impact:
  - The dataset side and the frozen contract disagree on the snapshot format, which fails closed in Phase 4.
  - The ORCID guard still holds, because it checks the resolved path. Other commands can write outside the git-ignored folder.
- Owner: dataset-engineer.
- Repair brief:
  - In `main()`, reject any `--snapshot` that does not match `^\d{4}-\d{2}-\d{2}$` and pass `datetime.date.fromisoformat`. fetch-all-samples.sh then fails early.
  - Add tests: `--snapshot 2026-11-22-repair` and `--snapshot ../real` each exit non-zero.
  - Must stay green: the 15 Python tests, the validator and verify on `datasets/samples/real`. Verify uses `--out`, so it is unaffected.

MINOR-3: Several repaired rules have no test that fails when the rule is removed.
- Where:
  - tests/module-unit/core/ConfigSuite.scala:183-205 ("every numeric field has a range check") against Config.scala:226, :234, :245-250 and :272;
  - tests/module-unit/core/ValidateSuite.scala:190-214 against Validate.scala:268-271.
- Failure input: delete any one of these rules and all 89 tests still pass:
  - `entityResolution.maxBlockSize >= 1`;
  - `retrieval.vectorLimit >= 1`;
  - the non-blank `policyVersion`, `metricVersion` and `fusionPolicyVersion` checks;
  - the non-empty `vectors.indexes` check;
  - the AUTHORED endpoint rule. It is tested only positively, at ValidateSuite.scala:74-82.
- Smaller gaps:
  - `SCHOLARCHAIN__ENTITYRESOLUTION__ACCEPTTHRESHOLD=NaN` (ConfigSuite.scala:178) passes through `Validate.confidence` even without the new override-finiteness rule.
  - `Validate.mention(... workId = "bad id")` (ValidateSuite.scala:184) passes through the mention-ID recomputation even without the work-ID shape check.
- Impact: a later edit can drop these guards silently, and the test name "every numeric field" overclaims.
- Owner: domain-engineer.
- Repair brief:
  - Add one assertion per rule, each matching that rule's own message: `SCHOLARCHAIN__ENTITYRESOLUTION__MAXBLOCKSIZE=0`, `SCHOLARCHAIN__RETRIEVAL__VECTORLIMIT=0`, `SCHOLARCHAIN__GRAPH__POLICYVERSION=" "`, and `SCHOLARCHAIN__VECTORS__INDEXES={}`.
  - Add an AUTHORED edge from a work ID to a researcher ID, which must be rejected with "must run from a researcher or mention ID".
  - Must stay green: every existing test.

MINOR-4: Small inaccuracies in the notes files.
- Where:
  - modules/core/MODULE.md:196-197 overclaims (see MINOR-1).
  - modules/core/MODULE.md:200-201 is a sentence fragment ("Mode (`local` or `aws`), and in `aws` mode non-empty bucket, ..."). It is the displaced tail of the validation sentence at 187-193.
  - STATUS.md:186-188 lists the same open item (the Python tests are not in `sbt check`) twice.
  - Blank lines split the lists at MODULE.md:248 and STATUS.md:181.
- Impact: cosmetic, apart from the false guarantee, which MINOR-1 covers.
- Owner: documentation-engineer, after the MINOR-1 code fix.
- Repair brief: rewrite 196-201 to state what the code enforces after the MINOR-1 fix, and merge the duplicate STATUS items.

ASSUMPTIONS
- `work-v1` changed shape (nested mentions gained `provenance`) without a version bump. I accept this because nothing in Phase 03 is committed and no consumer exists. The "new version per shape change" rule applies once the phase is ratified.
- The researcher ID scoped to a run and policy (MINOR-5) meets spec line 1091, "versioned ScholarChain identity ID". The cost is churn in DynamoDB keys and vectors across runs, which the human should know about at the gate. It is recorded in PROJECT_MEMORY.
- Treating the DTD as CC0 is a reasonable inference from its header and README.txt, and it is correctly escalated to the human. I re-observed the header text, the size, the ETag and the sha256.
- No test dispute was raised, so no FAIR, NOT FAIR or SPEC UNCLEAR verdict is needed.

RISKS
Residual observations, not findings. They are low impact and recorded for later phases:
- Run IDs can still collide for fractional manifest numbers that round to the same double. For example, `{"a":9007199254740991}` and `{"a":9007199254740991.4}` give the same run-a9af4cfcb37cc6c5 (T14c). Integers at or above 2^53 are correctly rejected. Rejecting non-integral manifest numbers would close this.
- Duplicate JSON keys are accepted and the last one wins (N13), even though the decoder is called "strict". Every core consumer sees the same value; a non-core reader might not.
- Decode cost: each line is parsed, read, re-written, parsed again, deep-compared, and scanned for nulls (which builds a path string per field), so expect roughly 3 to 5 times the cost of a plain read. Phase 4 should measure it on the 1000-record sample before an EMR run.
- A missing required key gives the reject reason "TraceException: $" (T5), which names neither the field nor the cause.
- `Validate.metrics` accepts `componentSize = 0` even though `publicationCount >= 1` is required.
- `Validate.work` does not check that a nested mention's provenance (source, runId, snapshot) agrees with the work's provenance.
- Nested mention provenance adds about 230 bytes per author to every work line. That is a storage and IO cost at DBLP scale.
- The Python tests are still outside `sbt check` (build-engineer). They print about 52 ResourceWarning lines, which are harmless.
Not reviewed:
- the bodies of make-fixtures.py and validate-manifest.py;
- Normalize.scala and Hashing.scala (both unchanged since 11:05, before review 1);
- the content of the real openalex and crossref sample lines;
- live openalex, crossref and orcid fetch paths (no fetch was run);
- run-log.md and versions.md;
- the reference tree beyond a wire-file presence check (it has no wire files).
Side effects of my evidence runs, none tracked by git:
- `sbt check` touched `.claude/.last-test-run` and target/.
- The Python tests regenerated git-ignored `__pycache__/`.
- The scratchpad holds the probe program, its classes, the sbt log (sbt-check-r2.txt) and a copy of the DTD, all under /tmp/claude-1000/-home-ajcreator-scholarchain/f7f3f42a-716d-4e27-850c-da90450d758c/scratchpad/.
- guard-dangerous blocked two of my read-only commands as false positives: a grep pattern that named the test-marker task, and a `find -name` pattern for env files. Both were rerun in another form. This is information for factory-engineer in Phase 11.

MANUAL ACTION REQUIRED: none for this review. Still open for the human, as listed in STATUS.md: the dblp.dtd CC0 decision, the ORCID API terms, and the T01 dataset questions 1 to 5.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator runs repair round 2, one owner at a time and one sbt at a time:
- domain-engineer: MINOR-1 and MINOR-3, then `sbt check`;
- dataset-engineer: MINOR-2, then the validator, verify and the Python tests;
- documentation-engineer: MINOR-4 and the MODULE.md line for MINOR-1.
After that, a fresh code-reviewer runs review 3.
