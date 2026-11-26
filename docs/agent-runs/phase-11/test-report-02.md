HARNESS: claude-code
MODEL: opus, effort high (tier high, `.agents/models.json`)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 11 step 3 rerun after repair round 1: full check, confirm the 5 formerly red tests, Python suites, optional A-2/A-3 tightening, coverage of round-1 repairs.
FILES CREATED: none in the repository (this report; logs scratchpad/sbt-hadoop-adv.log and scratchpad/sbt-check-02.log)
FILES MODIFIED (A-2, A-3; tests/adversarial/scholarchain/adversarial/hadoop/):
- ReducerWiringSuite.scala: `refused >= 1` is now `assertEquals(refused, 2L)`; the comment now says co: and y: blocks only (no venue ID, so no v: block).
- CommonNameSuite.scala: OVERSIZE_BLOCKS `>= 2` is now `== 3` (wang_w, the 2019 title work block, zhang_h). Also FALLBACK_BLOCKS_REFUSED == 0 and J04 reject lines == 3. The test title changed "at least the 2" to "exactly the 3".
- DuplicateIdSuite.scala: added J03 DUPLICATE_RECORDS == 2 (MODULE.md: a repeated OpenAlex work counts once under doi: and once under openalex:). The sum check is kept.
- MissingFieldSuite.scala: MISSING_KEY `>= 1` is now in {2, 3}. INVALID_IDENTIFIER: J01 counter and J01 counters file == 2; totals `>= 2` (were `>= 1`).
COMMANDS RUN (all observed, exit 0):
- `timeout 30m sbt adversarialTests/Test/scalafmt "adversarialTests/testOnly scholarchain.adversarial.hadoop.*"`: 78/78. This formats only tests/adversarial (my territory). The harness moved it to the background; I waited without polling.
- `timeout 45m sbt check`: success in 18m53s. markTestRun: "14 project test runs passed", and `.claude/.last-test-run` was touched at 19:53:39. Also moved to the background; I waited.
- `timeout 10m python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'`; `timeout 10m python3 .claude/hooks/test_guard_ownership.py`; `timeout 10m python3 .claude/hooks/test_verify_tests_ran.py`; `timeout 9m python3 .claude/hooks/test_guard_dangerous.py`; git status/diff of tests/; grep of tests/module-unit for test names.
- Command guard: no block, false or true, in this run.
TEST RESULTS (full check, per project; 0 failed everywhere):
- core 123/123; hadoop 100/100; entityResolution 119/119; sparkAnalytics 48/48; storage 199 passed, 1 ignored (AwsIntegrationSuite, gated).
- rag 158/158; servicesCommon 31/31; graphService 16/16; queryService 25/25; embeddingWorker 10/10.
- adversarialTests 263/263: all 17 suites ran, 0 failed, 0 ignored.
- Total 1092 passed, 0 failed, 1 ignored.
- Python: datasets 19 OK; test_guard_dangerous 68 OK (114 s); test_guard_ownership 4 OK; test_verify_tests_ran 17 OK.
- AWS integration suites: NOT RUN (no approved profile).
Formerly red, now green (names unchanged; each suite finished with 0 failed):
- FilteredRankGateSuite 7/7: "R6-1 topic", "R6-1 institution", "R6-1 bridge".
- JwtClaimsSuite 7/7: "token_use: a refresh-typed, a bogus or a missing token_use is refused (C15)".
- ReducerWiringSuite 10/10: "J04: the primary name block above --max-block that triggered fallback is OVERSIZE_BLOCKS".
No weakening: every file under tests/adversarial was last changed before test-report.md (17:13), except the 4 hadoop files I tightened at 19:30. Nobody else edited my suites. My diff only adds or narrows expected values. The new exact values all passed on the first run (observed: OVERSIZE 3, refused 2, J03 DUPLICATE 2, MISSING_KEY 2, J01 INVALID_IDENTIFIER 2).
VALIDATION RESULTS (coverage added in round 1, by requirement; MU = tests/module-unit):
| Item | Protection |
|---|---|
| C09 R6-1 | MU rag/FilteredRankSuite 6 (model and deterministic paths, summary lead, rankingFilter bounds); adversarial FilteredRankGate 7 |
| C12 | MU core/ConfigSuite (missing key, range); MU spark/CanonicalSuite "C12 cluster bound: at the bound ... bound+1 refuses every link, counted" plus report keys |
| C13 | MU storage/SparkExportContractSuite 3 (metrics, Neptune, strict extra field) |
| C14 | MU spark/ExportSuite (communities JSON lines), MU spark/PipelineSuite (dynamodb.json communities block); MU storage/CommunityLoaderSuite 7; MU rag/CommunitySizeSuite 7 and LocalRuntimeSuite +3 |
| C15 | MU services/common/CommonSuite (token_use refusals, audience binding, ID token); adversarial JwtClaims 7 |
| C16 | MU services/query/QueryServiceSuite 481, 507, 518 (explain status only, fallback categories, fake secret absent) |
| C17 | MU storage/LoaderSuite 634, 646, 668, 684, 419 and the old-JSON priorOutputs check (700-721) |
| C18, A-1 | MU hadoop/RejectsAndLimitsSuite 350 (J03), 385 (J04); adversarial ReducerWiring and CommonName, now with exact counts |
| C19 | MU rag/ErrorTextSuite 4 (embedder, vector store and community store failures give a fixed note; healthy control) |
| C20 | MU spark/RunManifestSuite 98 (v2 and v3 read, v9 refused with the exact message, distinct run IDs); PipelineSuite 825 spark-v5 |
FAILURE (gate rule: a changed production behavior must have a relevant test). Owner rag-engineer (C19 wider sweep, repair-01-rag-engineer-c19.md lines 20-21):
- (a) The builder note "dropped invalid evidence item <id>: it failed core validation" replaced the echoed validation text. No test in tests/ pins it (grep "dropped invalid", "failed core validation": no hit in rag tests).
- (b) "bundle invalid" and "answer invalid" moved from InvalidRequest (400 with the message) to Downstream("evidence") and Downstream("answer"). No rag or services test covers the new mapping; the C19 report itself says "No test pinned the old mapping".
- The code may be correct; the test is missing. I did not write one: both paths are internal invariants that the public API offers no documented way to trigger.
ASSUMPTIONS: The exact values come from MODULE.md semantics plus the fixtures. One fact is observed, not documented: the ReducerWiring z/ records carry no venue ID. MISSING_KEY is kept as a range because MODULE.md does not say whether a key containing whitespace counts as missing. Per-project counts are mapped from the per-suite "finished" lines in the log.
RISKS: Not done: no new tests for C19 (a) and (b); no cross-stage black-box test from the Spark communities export to the storage loader to the rag reader (each hop is covered by MU only). Open, from the repair reports: services build no RuntimeStores.communities, so community size reads as no-store until the cloud wiring is done (service-engineer); LocalIngestion does not load communities (storage-engineer). No cloud, commit or cleanup. The sbt check passed, so the test marker is fresh.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator routes the C19 test gap to rag-engineer (tests for the invalid-item note and the Downstream remap). Then a fresh code-reviewer on the whole repair diff (review-report-02.md), then documentation-engineer.
