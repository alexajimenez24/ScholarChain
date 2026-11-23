HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (high tier, role default per .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: P03-T03, a fresh review of the whole Phase 03 working-tree diff against HEAD 3d10892. That covers datasets/**, tests/module-unit/datasets/**, modules/core/**, tests/module-unit/core/**, STATUS.md, PROJECT_MEMORY.md and docs/agent-runs/phase-03/**.
FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (all observed successful)
- `git status --short`, `git status --short --untracked-files=all` (grouped by folder), `git diff --stat`, `git diff -- STATUS.md PROJECT_MEMORY.md`, `git check-ignore -v`.
- `pgrep -af sbt`: no sbt was running. Then one `sbt check` (exit 0).
- `python3 datasets/scripts/validate-manifest.py` (rc 0).
- `python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'` (OK).
- `python3 datasets/scripts/fetch_samples.py verify --out datasets/samples/real` (rc 0).
- `sha256sum` of every sample and `du -cb datasets/samples`.
- A PyYAML `safe_load` of the config and the manifest, compared with a JSON parse of the config with comments stripped.
- A Python `xml.etree` parse of both DBLP files, without the DTD.
- `diff` of each file against `~/ref-phase-03`.
- `.claude/hooks/validate-agent-files.sh`: "OK: 19 roles, ... 266 files with one writer".
- A standalone Scala 3.3.9 REPL (`java -cp ... dotty.tools.repl.Main`) with core's compiled classes on the classpath, used for behaviour probes T1 to T28 below. Nothing was written to the repository.
- Read-only HTTPS spot checks at the official sources (HEAD requests and small text or JSON files only):
  - dblp.xml.gz headers, dblp.xml.gz.md5, dblp.dtd headers and dblp README.txt;
  - the figshare API record for article 34697766;
  - the OpenAlex LICENSE.txt and help.openalex.org/access/snapshot.md.

TEST RESULTS
`sbt check` output tail (my run):
```
Test run scholarchain.core.NormalizeSuite finished: 0 failed, 0 ignored, 4 total
Test run scholarchain.core.IdsSuite finished: 0 failed, 0 ignored, 9 total
Test run scholarchain.core.ValidateSuite finished: 0 failed, 0 ignored, 16 total
Test run scholarchain.core.ConfigSuite finished: 0 failed, 0 ignored, 15 total
Test run scholarchain.core.SerializationSuite finished: 0 failed, 0 ignored, 25 total
[info] Passed: Total 69, Failed 0, Errors 0, Passed 69
[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run
[success] Total time: 3 s, completed Nov 22, 2026, 11:17:12 AM
exit=0
```
Dataset checks:
```
[manifest][ok] 5 datasets, required: dblp, openalex, crossref, orcid   (rc=0)
Ran 9 tests in 0.229s
OK
[verify] 3 files in /home/ajcreator/scholarchain/datasets/samples/real match retrieval.json   (rc=0)
```
Behaviour probes (REPL, against the compiled classes):
```
T1 "title":null              -> Failure(NullPointerException ... WorkRecord.title() is null)
T2 "provenance":null         -> Failure(NullPointerException ... "p" is null)
T3 provenance.sourceRecordId:null -> Failure(NullPointerException)
T8 CoauthorEdge researcherA:null  -> Failure(NullPointerException ... String.compareTo)
T9 "authors":null            -> Failure(NullPointerException ... Vector.flatMap)
T5 missing Option field      -> decode Left (absent keys are rejected; None must be written as null)
T6 "year":2021.7             -> Right(Some(2021)) (silently truncated)
T7 unknown extra field       -> accepted
T10 NEPTUNE_PORT=NaN         -> Right(0)
T11 NEPTUNE_PORT=8182.9      -> Right(8182)
T14 Ids.run {"a":9007199254740993} vs {"a":9007199254740992} -> same run-65c07d758377f303
T17 stripComments keeps "a # b" and "#z" inside strings and drops the "  # c" line (correct)
T18 Ids.work("dblp", 500 chars) -> builds an ID that isWorkId rejects (false)
T21 metrics with negative counts         -> Vector() (valid)
T22 GraphEdge COAUTHORED_WITH, AUTHORED-tagged ID, endpoints "x","y" -> Vector() (valid)
T24 work whose two authors share mentionId m:000... -> Vector() (valid)
T25 provenance.snapshot "garbage"        -> Vector() (valid)
T28 kcoreMaxK=-5, betweennessSampleSources=0 -> Right((-5,0))
```

VALIDATION RESULTS
Verdict: REQUEST_CHANGES (2 MAJOR, 11 MINOR, 0 CRITICAL).

MAJOR-1: JSON null in a required field crashes the decoder instead of producing a reject record.
- Where:
  - modules/core/src/main/scala/scholarchain/core/JsonLines.scala:33-37 (`decodeValid`);
  - modules/core/src/main/scala/scholarchain/core/Validate.scala:41-49 (`provenance`) and every validator that dereferences a field;
  - tests/module-unit/core/SerializationSuite.scala:99-103.
- Failure input: a WorkRecord line with `"provenance":null`, or `"sourceRecordId":null`, `"title":null` or `"authors":null`. uPickle 4.4.3 decodes JSON null into a JVM null for String, Vector and case-class fields. `JsonLines.decodeValid` then throws a NullPointerException instead of returning `Left` (probes T1, T2, T3, T8, T9).
- Impact:
  - The work order's "rejects missing provenance" holds only when the key is absent. The `null` form, the commonest "missing" encoding from Python, Spark or other producers, crashes.
  - One bad line throws out of a Hadoop task or Spark stage instead of going to the versioned reject output. `decodeValid`'s documented contract ("failures carry the reason so the caller can emit a reject record") breaks.
  - The only "missing provenance" decode test deletes the key, so it passes on this broken behaviour.
- Owner: domain-engineer.
- Repair brief:
  - Likely cause: uPickle's null-to-reference default, plus validators that assume non-null fields.
  - Fix: make `decodeValid` total by catching `NonFatal` from `validate` and returning `Left`. Also reject null explicitly: either a `Validate.provenance` null guard plus null checks on required fields, or a decode-time null scan of the `ujson.Value` against non-Option fields.
  - Add tests: `"provenance":null`, `"sourceRecordId":null`, `"authors":null` and CoauthorEdge `"researcherA":null` each yield `Left`, with no exception.
  - What else could break: callers that rely on exceptions (none exist yet).
  - Must stay green: all 18 frozen wire tests (byte-equal write), the ValidateSuite and `sbt check`.

MAJOR-2: AuthorMention has no provenance and no validator, which fails the gate "Every record has provenance".
- Where:
  - modules/core/src/main/scala/scholarchain/core/Model.scala:97-106;
  - Validate.scala has no `mention` validator;
  - docs/agent-runs/phase-03/agent-report.md (the ASSUMPTIONS and Schema versions sections).
- Failure input: a standalone author-mention record, which Phase 4 must emit (spec docs/spec/assignment.md:1128 "Emit valid normalized WorkRecord and AuthorMention records" and :1157 "Emit partitioned work, author-mention, ... files"). It carries no source record, snapshot, run ID or schema version, and nothing can validate it.
- Impact:
  - The spec gate at line 1100 fails, and so do hard rule 3 and spec line 638 ("provenance ... mandatory").
  - The T02 report cites the gate as its reason for adding provenance to five records, but discloses only AnswerClaim as lacking provenance, not AuthorMention.
  - AnswerClaim is defensible because a claim's provenance is its evidence IDs (hard rule 3) and `Validate.claim` enforces them; the report should say so explicitly.
- Owner: domain-engineer. documentation-engineer updates MODULE.md afterwards.
- Repair brief:
  - Fix:
    - Add `provenance: Provenance` to AuthorMention, with a new constant (for example `author-mention-v1`) in `SchemaVersions.Supported`.
    - Add `Validate.mention`: mention ID shape, `mentionId == Ids.mention(workId, position)`, position >= 0 and provenance. `Validate.work` calls it per author.
    - Freeze a new `wire/author-mention.json` and regenerate `wire/work.json`, which nests mentions.
    - Update the schema-version coverage test.
  - No consumer exists yet, so now is the cheapest time.
  - What else could break: work.json bytes, the 17-to-18 count in the coverage test, and MODULE.md "17 constants".
  - Must stay green: every SerializationSuite and ValidateSuite test, and `sbt check`.

MINOR-1: Validation recomputes only CoauthorEdge IDs. Other ID consistency and over-merge guards are missing.
- Where: Validate.scala:54-66, 101-125, 127-145 and 185-193.
- Failure input:
  - Two authors of one work with the same mentionId validate clean (T24). Mention IDs are never checked against `Ids.mention(workId, position)`, so a producer bug merges two people's mentions, an over-merge risk.
  - A GraphEdge whose ID tag belongs to another edge type, or whose endpoints are not IDs, passes (T22).
  - Unchecked fields: representativeWorkIds (T26), MatchDecision source and target shapes and decisionId recomputation, and the provenance.snapshot format (T25).
  - Negative metric counts pass (T21).
  - Decoding is lenient: unknown fields are ignored (T7) and `year` 2021.7 becomes 2021 (T6).
- Owner: domain-engineer.
- Repair brief:
  - Fix: recompute deterministic IDs where the inputs are in the record (mention, graph edge, decision), require distinct mention IDs per work, check the shapes of referenced IDs, require non-negative counts, and pin the snapshot as `YYYY-MM-DD`.
  - Add one failing-input test per rule. This can share the change with MAJOR-2.
  - Must stay green: the frozen fixtures, which already satisfy these rules.

MINOR-2: Config coercion and validation let impossible values through.
- Where: Config.scala:283-287 (`coerce`), 311-312 and 178-234.
- Failure input:
  - `NEPTUNE_PORT=NaN` loads as port 0 (T10), and `8182.9` as 8182 (T11).
  - `SCHOLARCHAIN__GRAPH__KCOREMAXK=-5` and `BETWEENNESSSAMPLESOURCES=0` load (T28).
  - neptune.port, queryTimeoutMs, operations limits and graph parameters are not validated.
  - In aws mode, `llmEnabled=true` with an empty `synthesisModel` passes.
- Owner: domain-engineer.
- Repair brief:
  - Fix: reject non-finite and non-integral numbers when the replaced value is an integer, validate every numeric field's range, and require synthesis and verification model IDs when `llmEnabled`.
  - Add tests for NaN and fractional ports.

MINOR-3: A graph or retrieval parameter is hardcoded in code.
- Where: Config.scala:219 (`retrieval.maxPathDepth > 6`). This duplicates the file's `graph.pathMaxDepth: 6`.
- Impact: raising the depth needs a code change, against "do not hardcode graph windows or ... parameters".
- Owner: domain-engineer.
- Repair brief: bound retrieval.maxPathDepth by `graph.pathMaxDepth`, validate that one >= 1, and add a test.
- Judgement on the file literals: thresholds, windows, the k-core limit and the betweenness seed are literals in the versioned `scholarchain-v1.yaml`, each overridable by `SCHOLARCHAIN__...`. That meets "do not hardcode". The work order explicitly puts values in versioned files, and only code-side constants violate it. The year bounds 1900 to 2100 are a contract plausibility constant, which is acceptable.

MINOR-4: Run IDs collide for large integers, and work IDs can be built that fail their own check.
- Where: Ids.scala:102-112 and 35-41.
- Failure input:
  - `Ids.run` canonicalises through `ujson.Num` (a Double), so manifests that differ only in an integer above 2^53 get the same run ID (T14).
  - `Ids.work` accepts source IDs that `isWorkId` rejects, for example 500 characters (T18).
- Owner: domain-engineer.
- Repair brief:
  - Fix: canonicalise numbers from their source text, or reject numbers above 2^53 in manifests. Have `Ids.work` enforce the same 400-character bound.
  - Add tests.

MINOR-5: The researcher ID is stable only for an identical mention set, and the notes do not say so.
- Where:
  - Ids.scala:50-52;
  - PROJECT_MEMORY.md, the "Researcher ID" row ("stable and clock-free");
  - modules/core/MODULE.md, Stable ID formulas.
- Impact: `sc:r:v1:` hashes the full sorted mention set. A new snapshot that adds one paper, or an ER merge, changes the ID, which churns edges, metrics, vectors and DynamoDB keys between runs. This is not necessarily wrong (the spec asks for a "versioned ScholarChain identity ID"), but it is unstated.
- Owner: domain-engineer decides; documentation-engineer records the scope.
- Repair brief: state that the ID is scoped to the run and policy, or choose an anchor (for example the smallest mention ID) with a documented re-keying rule.

MINOR-6: No test pins `#` inside a config string.
- Where: tests/module-unit/core/ConfigSuite.scala:98-108.
- Impact: the behaviour is correct today (T17). But a naive "strip from # to end of line" implementation would pass the whole suite, because the bundled file has no `#` in any value.
- Owner: domain-engineer.
- Repair brief: add a parse test with `"x": "a # b"` and an array element `"#z"` that asserts the values survive.

MINOR-7: Real ORCID records are fetched by default.
- Where: datasets/scripts/fetch-all-samples.sh:19.
- Impact: the script fetches up to 20 ORCID records (personal data) on every run, although the ORCID public-API terms are UNVERIFIED and escalated to the human (open question 1).
- Owner: dataset-engineer.
- Repair brief: make the ORCID step opt-in (for example `ORCID_MAX=0` by default, or a flag) until the human decides, and say so in the manifest's orcid retrieval line.

MINOR-8: A cloud write path sits in datasets/scripts.
- Where: datasets/scripts/fetch_samples.py:231-240 (`upload`, which runs `aws s3 cp` through a subprocess) and fetch-all-samples.sh:21.
- Impact:
  - The work order does not ask for an upload. Billable S3 writes belong to deploy-engineer's gated bounded-run path (scripts/** is infra-engineer's).
  - The Bash command that triggers it does not contain `aws`, so the "any shell aws call prompts" visibility does not apply. The script itself is not auto-allowed, so it still prompts.
- Owner: dataset-engineer.
- Repair brief: remove `upload`, or mark it human-only and route uploads through the infra-engineer and deploy-engineer path.

MINOR-9: The DBLP samples cannot be parsed offline.
- Where: datasets/samples/real/dblp-sample.xml (4 `&aacute;` and 1 `&iacute;`, first at line 113) and datasets/samples/fixtures/dblp-fixture.xml (`&uuml;`, line 22).
- Impact: both reference dblp.dtd entities, and `xml.etree` fails without the DTD. dblp.dtd is neither committed nor fetched by the scripts, so Phase 4 local sample runs will need it.
- Owner: dataset-engineer.
- Repair brief: have fetch_samples.py fetch dblp.dtd (13134 bytes, observed) with a sha256 in retrieval.json, after checking its redistribution basis. Or document how parsers obtain it.

MINOR-10: The Python dataset tests run outside `sbt check` and leak a temp directory.
- Where: tests/module-unit/datasets/test_dataset_manifest.py:59.
- Impact: the tests are not part of the definition of done (already an open item for build-engineer or test-engineer). `test_refuses_full_scale_record_count` also leaves a `tempfile.mkdtemp()` directory in /tmp on every run.
- Owner: dataset-engineer for the leak; build-engineer for wiring.
- Repair brief: use `TemporaryDirectory`.

MINOR-11: Small doc inaccuracies.
- Where:
  - modules/core/MODULE.md says "`Validate`: one validator per record", but AuthorMention has none (see MAJOR-2);
  - the T02 report says Normalize was "reduced to title, personName, doi and orcid", but the code also exposes `foldUnicode`, `stripDblpHomonym` and `orcidCheckDigit`. MODULE.md lists them correctly.
- Owner: documentation-engineer, after the MAJOR-2 fix.

Checklist results
1. Work order coverage:
   - Records: all 20 named records are present. The 19 listed under "Records" in MODULE.md plus `Provenance` make 20, which resolves the T02d "19 plus parts" doubt.
   - Enums: the six required enums are present (plus MatchPath and EntityKind).
   - IDs and loader: the work, researcher, edge, evidence and run ID functions are present (plus mention, chunk, graph edge and decision), and so is the config loader.
   - Wire examples: there are 18 frozen ones. Each test checks byte-equal write, equal read and valid decode.
   - Validation rejections: each of the six has a test that would fail if its rule were removed (I checked the assertions' message substrings against each rule). The "missing provenance" coverage is incomplete (MAJOR-1).
2. Addendum:
   - The config is `scholarchain-v1.yaml`. PyYAML and the JSON parse give identical trees. The restriction is stated in the file header (lines 3-7), in MODULE.md ("Restriction: JSON-compatible YAML") and in the report.
   - `services.query`, `graph`, `worker` and `auth` are present. Issuer, audience and JWKS URL come from `${COGNITO_*}` and the queue from `${EMBEDDING_QUEUE_URL}`.
   - There is no bucket, endpoint, account ID or model ID literal in the file. Thresholds and windows are judged above (MINOR-3).
   - The wire tests are in tests/module-unit/core, and tests/contracts is untouched.
   - Every changed path is inside its owner's territory: dataset-engineer, domain-engineer, documentation-engineer and the orchestrator. Nothing was written under datasets/gold, build.sbt or project/. validate-agent-files is OK.
   - Reference use was adapted, not copied: Model, Ids, Validate, Config and the suites changed substantially. Hashing.scala and JsonLines.scala are verbatim, a choice the report records. make-fixtures.py has only a header and encoding change, and the openalex fixture is identical, which is expected from a deterministic generator.
3. Datasets:
   - I re-observed the following at the source, and every one matches the report:
     - DBLP: Content-Length 1122680761, ETag "42eabfb9-660d0c35bcf23", Last-Modified Sat, 21 Nov 2026 22:20:07 GMT and md5 dcb2e8cd2d1c05e18a1e8f18165b2c5e;
     - the DTD: 13134 bytes, Last-Modified 28 Jun 2023;
     - the README: "Version of November 22nd, 2019", CC0 with ODC-BY 1.0 secondary;
     - figshare: CC0, published 2026-10-22, summaries 57290954546 bytes, md5 a55a16fe0b292bda03cc6016546c8984;
     - OpenAlex: LICENSE.txt is CC0 1.0; about 745 GB, works about 615 GB, "September 2026 release".
   - Unverified items are listed per dataset. I found nothing invented.
   - The samples total 163579 bytes and all sha256 values match the manifest. There are no abstracts and no e-mail addresses, and no real ORCID record is committed.
   - The scripts skip existing outputs (idempotent) and are bounded: 1000 records, 128 MiB, a 500000 hard cap, and DOI-limited enrichment.
   - The API key and the contact e-mail are read from the environment and never printed or stored. No file name ends in `.env` or `.tfvars`.
4. Correctness:
   - Pair ordering, edge ID order independence and the edge ID recomputation in `Validate.edge` are correct.
   - Config precedence (override, then placeholder, then literal) is tested and correct.
   - Comment stripping is correct, including `#` inside strings (T17).
   - Option encoding is stable: None is written as `null`, an absent key is rejected, and the format is frozen by byte-equal tests. Null into required fields is the defect (MAJOR-1).
   - Remaining ID and config gaps: MINOR-1 to MINOR-4.
5. Provenance:
   - Every record except AuthorMention and AnswerClaim carries Provenance (MAJOR-2).
   - LLM match decisions must carry prompt and model versions, and the answer carries both.
   - The real samples carry retrieval provenance (URL, ETag, retrievedAt, sha256).
6. Report honesty:
   - Every reproducible count and command matched: sbt check (69 tests, per-suite counts), the validator line, 9 Python tests, verify, sample sizes and sha256 values, 3 DBLP orcid attributes, 40 records with 40 DOIs, and the fixtures at 20012 bytes.
   - The exception is the AuthorMention provenance omission in the T02 report (MAJOR-2).
7. Agreement: MODULE.md, STATUS.md and PROJECT_MEMORY.md match the code, apart from MINOR-5 and MINOR-11. MODULE.md has no line over 90 characters, and none of the three files contains an em dash.

ASSUMPTIONS
- Spec lines 1128 and 1157 make AuthorMention a standalone emitted record, so the gate "Every record has provenance" applies to it. AnswerClaim's evidence IDs satisfy hard rule 3 for claims.
- Values in the versioned config file, overridable from the environment, count as "not hardcoded". Only code constants are judged hardcoded.
- No test dispute was raised, so no FAIR, NOT FAIR or SPEC UNCLEAR verdict is needed.

RISKS
- Not reviewed:
  - the full content of the real openalex and crossref sample lines (I spot-checked the heads for abstracts and e-mail addresses only);
  - the body of make-fixtures.py beyond its diff from the reference and its output handling;
  - the Crossref terms page and the ORCID policy page (not re-fetched);
  - the openalex and crossref fetch paths, which I did not run live (no network fetch of samples);
  - the run-log and versions files, beyond reading them.
- Side effects of my evidence runs (none tracked by git):
  - `sbt check` touched `.claude/.last-test-run` and target/.
  - The Python tests rewrote the git-ignored `__pycache__/` directories in datasets/scripts and tests/module-unit/datasets, and left one `tempfile.mkdtemp()` directory in /tmp (MINOR-10).
  - The sbt output was captured at /tmp/claude-1000/-home-ajcreator-scholarchain/f7f3f42a-716d-4e27-850c-da90450d758c/scratchpad/sbt-check.txt, outside the repository.
- Guard false positives seen (information for factory-engineer, not a finding on this diff): guard-dangerous blocked a read-only `python3 -c` that named `.claude/settings.json`, reporting that it "names the test marker". It also blocked a read-only `sed -E 's#...#'` (no -i) that named the gold-set path. Both are presumably within the Phase 01 accepted guard gaps.
- Two `sbt core/Test/console` attempts did not evaluate piped input, so I used a standalone REPL instead. sbt was never run concurrently.

MANUAL ACTION REQUIRED: none for this review. The five dataset questions in STATUS.md remain open for the human.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator sends repair round 1 to three owners, then a fresh code-reviewer re-reviews:
- domain-engineer: MAJOR-1, MAJOR-2, MINOR-1 to MINOR-6, then `sbt check`;
- dataset-engineer: MINOR-7 to MINOR-10, then the validator and the Python tests;
- documentation-engineer: MINOR-5 and MINOR-11 notes, after the code fixes.
