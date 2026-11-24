HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, role default (opus, effort high per .agents/models.json)

**Verdict: REQUEST_CHANGES.** There is one MAJOR finding, so the Review 2 re-check applies. The repair round can close it either with the documented invariant (minimum) or with run scoping (full fix), as described under MAJOR-1. Every other finding is MINOR or NIT.

What holds up:
- **SigV4 is correct and the test vectors are genuine.** I recomputed them independently with Python `hmac`/`hashlib`. get-vanilla gives canonical-request hash `bb579772…` and signature `5fa00fa3…`. post-vanilla gives `5da7c1a2…`. The 20150830/us-east-1/iam signing key is `c4afb1cc…`. These match my own knowledge of the published AWS suite and docs. A mis-recalled HMAC output could not also match a correct implementation.
- **Secret hygiene is good.** No secret appears in any `toString` or log line, and credentials come only from an injected Map.
- **The no-cloud rule holds.** Tests contact only localhost. The gated suite is ignored by default and `build.sbt` never sets the opt-in.
- **Module boundaries hold.** `core/aws` uses JDK and ujson only. storage imports only `scholarchain.core`. The ER adapter imports only `core.aws`.
- **Ownership is clean.** Every touched file is in its owner's territory, and nothing outside it changed.
- **Every gate item is present:**
  - the required adapters and the five indexes;
  - Neptune IDs equal Spark IDs on a fixture whose property keys match `Export.scala`;
  - each COAUTHORED_WITH edge resolves to works that both researchers AUTHORED;
  - all 8 COAUTHORED_WITH templates have `e.window = $window`;
  - conditional writes, TTL only on QuerySession, long text only in S3;
  - batch 100, bounded retry, rejected-vector records;
  - Bedrock adjudicator with the strict schema, the no-new-identifier rule, the pinned cache key, and er-v1 unchanged (OFF still records none/none).
- **No scope creep.** There is no OpenSearch.
- **The known open items are reported correctly and consistently** across the reports, MODULE.md files, docs and STATUS.md: the Parquet reader gap, the Spark export gaps (`institutionId`, paper-abstract rows), and adjudication sharing `models.synthesisModel`.

**Findings, ranked**

**MAJOR-1. Loaded data is never scoped to the run being served, so loading a second run leaves the first run's Neptune nodes and edges and its S3 Vectors live and returned by queries.**
- Owner: storage-engineer (code, comments); documentation-engineer (docs/storage.md, modules/storage/MODULE.md).
- Where:
  - `modules/storage/src/main/scala/scholarchain/storage/GraphTemplates.scala:174-220`: no template filters on run or policy.
  - `NeptuneAdapters.scala:74-79`: the comment says a reload under a new run is handled by `updateSingleCardinalityProperties`.
  - `VectorLoader.scala:407-412`: an unchanged vector keeps the `runId` and `textUri` metadata of the run that first wrote it, so `runId` cannot scope reads either.
  - docs/storage.md §7 presents idempotency without this limit.
- Failure input:
  - Neptune: run R1 loads researchers X and Z. In R2, ER merges X into Z, or the policy version moves, so edge IDs change. R2 loads into the same cluster with `mode=AUTO`. The R1 COAUTHORED_WITH edges for X are never removed, so `direct-coauthors-v1` / `coauthor-pair-v1` return the old and new edges together and double-count joint publications.
  - Vectors: Z's profile text changes in R2, giving a new chunk key. The R1 vector still has `researcherIds=[Z]`, so a filtered query returns both the outdated and the current profile.
- Impact: wrong collaboration metrics and stale grounding from Phase 12 on. Idempotency holds only for the same input. The ArtifactRegistry `current` pointer exists, but nothing reads through it.
- Repair brief:
  - Minimum for this round:
    - correct the `NeptuneAdapters.scala:76-79` comment;
    - state the invariant in MODULE.md and docs/storage.md: one served run per Neptune cluster and per vector index, rebuilt or cleaned by the human before loading a new run;
    - record run scoping as a carried-forward item for the human gate.
  - Full fix, if the human wants it now:
    - add a run parameter (`ParamKind.RunId`, resolved from `ArtifactRegistry.current`) to every template (`AND e.runId = $runId` / `n.runId = $runId`);
    - have the vector loader report the keys of the run, so stale keys can be listed.
  - What else could break: GraphTemplateSuite's exact-parameter and template-count tests, and NeptuneAdapterSuite's form test.
  - Tests that must stay green: GraphTemplateSuite, NeptuneAdapterSuite, NeptuneIdentifierSuite, VectorLoaderSuite.

**MINOR-1. A missing input directory loads zero rows and the stage is marked Succeeded (this answers the open Phase 05 NIT-4 question).**
- Owner: storage-engineer.
- Where: `Loaders.scala:44-45` (`if !Files.isDirectory(dir) then Iterator.empty`), combined with `expectedRows: Option[Long] = None` in `ResearcherMetricsLoader.load` (`Loaders.scala:266`) and `VectorLoader.load` (`VectorLoader.scala:312`).
- Failure input: `RowSource.jsonLinesDir(Path.of("typo"))` gives `Right` with read 0, and RunLedger `stage#…` becomes Succeeded.
- Repair brief: either make `jsonLinesDir` emit one `Left("input directory … is missing")` row (which fails or rejects visibly), or require `expectedRows` in both loaders. Add a test for each. RowSourceSuite and the loader suites must stay green.

**MINOR-2. The cleanup procedure can leave vector `textUri` pointers dangling, and docs claim the load report lists keys when it does not.**
- Owner: documentation-engineer (docs/storage.md §10, MODULE.md); storage-engineer (the agent-report cleanup text is evidence and needs no change).
- Where: docs/storage.md:368 and §10 ("delete the run_id={run} prefixes to remove one run"). The mechanism is `VectorLoader.scala:403-412`: an unchanged vector keeps the old run's `textUri`.
- Failure input: R2 re-loads Z's unchanged profile, so the vector still points to `semantic-context/researcher-profile/run_id=R1/…`. The human then deletes the R1 prefix as documented, and RAG can no longer fetch the evidence text.
- Also: `LoadReport` (`Loaders.scala:78-85`) holds counts and rejections only, not the keys written.
- Repair brief: state that a run's semantic-context prefix may be deleted only together with the vectors whose `textUri` points into it. Drop or correct the "keys are in the load report" claim.

**MINOR-3. The integration suite's "at most 25 AWS calls" bound is neither enforced nor true under its own retry setting, and the suite writes test vectors into a production index.**
- Owner: storage-engineer (code); documentation-engineer (docs/storage.md:357).
- Where:
  - `tests/module-unit/storage/AwsIntegrationSuite.scala:28-29` and `:36`;
  - `:63` (`maxAttempts = 3`): 14 logical calls can become up to 42 HTTP requests;
  - `:242`: `assert(AwsIntegration.MaxCalls <= 25)` checks a constant against itself and would pass on broken behavior;
  - `:21`: `SCHOLARCHAIN_IT_VECTOR_INDEX=<existing index>`, and the `it-<tag>-a|b` vectors are never deleted, so unfiltered RAG queries can return them.
- Repair brief: wrap the transport in a counting `HttpTransport` and fail when the count exceeds `MaxCalls`, then fix the doc figure. Recommend a dedicated test index. AwsGateSuite and AwsIntegrationHarnessSuite must stay green.

**MINOR-4. docs/storage.md:291 says "The loaders build AwsHttp with maxAttempts 1 so retries are not doubled".**
- Owner: documentation-engineer.
- No storage code constructs `AwsHttp` (verified by grep). MODULE.md:146 correctly says "The caller picks AwsHttp.maxAttempts".
- Failure input: a caller that passes `maxAttempts=3` together with the default `RetryPolicy(4)` gets up to 12 attempts per call.
- Repair brief: restate it as a wiring requirement for the Phase 10 and cloud callers.

**MINOR-5. The comment at `Tables.scala:304` says TTL deletion lags "up to 48 hours".**
- Owner: storage-engineer.
- The docs say "typically within a few days" (docs/service-limits.md:28-39). The step 01 report admits the figure came from memory. Comment only, with no behavior impact.
- Repair brief: reword the comment.

**MINOR-6. A replay that repairs earlier rejections stores a new report but leaves the ledger pointing at the first one.**
- Owner: storage-engineer.
- Where: `Loaders.scala:143-150`. A Succeeded stage is terminal, so `outputUri` / `outputSha256` keep naming the first report.
- Failure input: the first load rejects a batch after PutVectors exhausts its retries, and the stage is Succeeded. A replay then writes the batch, but the ledger still cites a report that lists those rejections.
- Repair brief: record the replay's report URI somewhere (for example an `ArtifactRegistry` entry per report), or document it. Carry-forward is acceptable.

**MINOR-7. After a text-object failure, the rest of the batch still writes to S3 and calls the paid embedder.**
- Owner: storage-engineer.
- Where: `VectorLoader.scala:406-426`. Once `err` is set, later documents in the same batch still run `prepare`, then nothing is put, and a replay embeds them again.
- Impact: up to 99 wasted Bedrock calls per failure.
- Repair brief: skip `prepare` when `err.isDefined`, and add a test that counts embedder calls after an injected S3 failure.

**NIT-1.** The step 00 report says "86 existing plus 29 new". The suite counts give 6+6+9 = 21 new on top of 94 existing, which is 115. This is already corrected in `modules/core/MODULE.md` and STATUS.md. The report is evidence, so no change is needed. Owner: domain-engineer.

**NIT-2.** `modules/core/MODULE.md` says "Only 2 of the signer's roughly 30 tests are published vectors". The step 00 report meant 2 of the AWS suite's roughly 30 cases, and the signing-key value is a third published value. Owner: documentation-engineer.

**NIT-3.** `Http.scala:126` puts `e.getMessage` into the error text, and for `URI.create` that message contains the full URL with its query string. This contradicts the class doc. Today's callers put no secrets in URLs. Owner: domain-engineer.

**NIT-4.** `Credentials.scala:73-82` sends `AWS_CONTAINER_AUTHORIZATION_TOKEN` to whatever host `AWS_CONTAINER_CREDENTIALS_FULL_URI` names. AWS SDKs restrict an http full URI to loopback and container hosts. The variable is operator-controlled, so the risk is low. Owner: domain-engineer.

**NIT-5.** The AwsGateSuite test "this test run is not opted in" (`AwsIntegrationSuite.scala:183-187`) is tautological. The real guarantee is the build.sbt check plus the human never exporting the variable. Owner: storage-engineer.

**NIT-6.** Signer coverage is limited to the vanilla vectors. There are no published vectors for path encoding, query order or header trimming (get-space, get-utf8, get-vanilla-query-order-*, get-header-value-trim). Today's callers pre-encode the path (the model ID's `%3A`), so the double-encoding branch is self-consistent, but it is unproven against AWS. Owner: domain-engineer. Optional.

**Observation outside the Phase 07 diff (spark-engineer, for the orchestrator).** The run's `load-manifests/neptune.json` lists `coauthoredWith.windows = ["1950-2026"]`. The run manifest's `graph.windows` has four windows, and `coauthor-edges/` has partitions for 1950-2026 and 2010-2014. A loader that took windows from neptune.json would reject every 2010-2014 edge. The storage report's "156 across 2 non-empty windows" comes from the folders, not from neptune.json.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: phase 07 Review 1, the whole uncommitted Phase 07 diff (core.aws, storage, entity-resolution changes, docs/service-limits.md, docs/storage.md, three MODULE.md files, STATUS.md).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status --short; git diff --stat; git diff on the entity-resolution sources, AdjudicationSuite, STATUS.md and the core and ER MODULE.md files
- Read of every new core/aws, storage and ER source file; storage and ER test files read in the sections that bear on the findings; all Phase 07 reports and output-sbt-check-step04.txt read or grepped; ~/ref-phase-07/REFERENCE.md read
- grep, sed and cat over sources, the Spark Export.scala and SparkPipeline.scala call sites, docs and reports
- python3 -I -c: independent recomputation of the SigV4 vectors, the signing key and sha256("hi")
- python3 -I -c: inspection of the Spark local-run load manifests (neptune.json, s3vectors.json, dynamodb.json, run manifest)
- ls, strings and find on target/spark-local-run (read-only)
- find -newermt '2026-11-23 22:19': no source or test file changed after the step 04 sbt check
TEST RESULTS: I did not run sbt. I relied on docs/agent-runs/phase-07/output-sbt-check-step04.txt: exit 0 in 312 s, Passed 153 (1 ignored), 115, 68, 119, 98, 33, markTestRun touched. No source file is newer than that run (find -newermt check), so it reflects the reviewed tree.
VALIDATION RESULTS: REQUEST_CHANGES.
- MAJOR-1: no served-run scoping in Neptune templates or vector reads, and the misleading reload comment (storage-engineer, documentation-engineer).
- MINOR-1: a missing input dir loads 0 rows as Succeeded (storage-engineer).
- MINOR-2: cleanup can leave textUri dangling, and docs claim the report lists keys (documentation-engineer).
- MINOR-3: the 25-call bound is unenforced and exceeded under retries, the bound test is tautological, and test vectors go into a production index (storage-engineer, documentation-engineer).
- MINOR-4: docs/storage.md:291 claims a maxAttempts wiring that no code does (documentation-engineer).
- MINOR-5: the Tables.scala:304 "48 hours" comment (storage-engineer).
- MINOR-6: the ledger keeps the first report after a repairing replay (storage-engineer).
- MINOR-7: wasted embedding calls after an artifact failure (storage-engineer).
- NIT-1 to NIT-6 as described above.
- Gate items verified as present and correct: see "What holds up" above.
ASSUMPTIONS:
- The published SigV4 values match my own knowledge of the AWS suite and docs, and my independent Python computation agrees with the implementation.
- The S3 Vectors, Neptune and Bedrock limits and behaviors in docs/service-limits.md marked VERIFIED were read by documentation-engineer. I could not re-fetch AWS docs: I have no documentation tool, and I made no network calls.
- MAJOR-1 is judged against the spec's storage idempotency and provenance discipline. The work order does not explicitly demand run scoping, so the human may choose the documented-invariant repair and carry the rest forward.
RISKS (not reviewed or not verifiable):
- I did not read every assertion in StorageSuite, VectorSuite and LoaderSuite. I read their test names and the parts behind each finding.
- I did not read BedrockAdjudicatorSuite tests 3, 6 and 7 line by line; I relied on their names and the step 03 report.
- I did not re-verify AWS behavior: CreateIndex/PutVectors/GetVectors semantics, `$eq` on lists, `updateSingleCardinalityProperties` for openCypher, Neptune multi-line CSV, the cost of the `*1..6` path templates, and whether Bedrock and s3vectors accept the double-encoded `%3A` path.
- I could not read the Parquet contents of the Spark run (no reader). The window observation comes from manifests and folder names only.
- I did not rerun sbt (machine limit; the existing check is current).
- I did not diff the code line by line against ~/ref-phase-07.
MANUAL ACTION REQUIRED: the human decides at the gate the scope of the MAJOR-1 repair (documented invariant now versus run-scoped templates now), plus the already-known items: the Parquet reader, the Spark export gaps, the adjudication model field.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: repair round 1. Route to storage-engineer: MAJOR-1 code and comment, MINOR-1, MINOR-3, MINOR-5, MINOR-6 (or carry forward), MINOR-7, NIT-5. Route to documentation-engineer: MAJOR-1 docs, MINOR-2, MINOR-3 docs, MINOR-4, NIT-2. Route to domain-engineer: NIT-3, NIT-4, NIT-6 (optional). Then run sbt check once if source changed, and a fresh Review 2 (review-report-02.md), because Review 1 has a MAJOR.
```
