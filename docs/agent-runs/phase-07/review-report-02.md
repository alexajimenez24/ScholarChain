HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, role default (`opus`, effort high per `.agents/models.json`)

<!-- Orchestrator note: the harness flagged this report as matching a "settings-json"
instruction-shaped pattern when it was delivered (the review lists reading
.claude/settings.json hooks among its commands). The report contains no instruction
that was acted on; it is saved as delivered. -->

**Verdict: REQUEST_CHANGES.** The repair round worked: every Review 1 finding is now closed or carried forward as routed in `repair-01.md`. The one new MAJOR is an ownership problem, not a code defect. domain-engineer's `sbt scalafmtAll` reformatted two storage-engineer files, and its report says it touched nothing outside its territory. Iron law 3 makes any write outside the author's territory MAJOR. The change is formatting only and `sbt check` is green with it, so the human can close it at the gate (storage-engineer accepts the two files, or the human discloses it in `human-gate.md`) with no code change. There is no second repair round, so everything open below goes to the human.

**The saved sbt check matches the current tree.**
- `output-sbt-check-r1.txt`: exit 0, 305 s, finished 22:57:15. Totals as printed: 158 passed + 1 ignored, 119, 68, 98, 33, 119. markTestRun touched `.claude/.last-test-run` at 22:57:14.
- Using the check's start (about 22:51:43) as the cutoff, the only files under `modules/`, `tests/`, `build.sbt` and `project/` that are newer are `modules/core/MODULE.md` and `modules/storage/MODULE.md`.
- Using the output file's mtime as the cutoff, the only newer files are STATUS.md, docs/storage.md, the two MODULE.md files and two repair reports. All are docs.

## Review 1 findings, re-checked

- **MAJOR-1: CLOSED BY DOCUMENTATION, CARRIED FORWARD.**
  - The comment at `NeptuneAdapters.scala:74-83` no longer claims that `updateSingleCardinalityProperties` handles a reload under a new run, and it states the one-served-run invariant.
  - `VectorLoader.scala:409-410` notes that an unchanged vector keeps the first run's `runId` and `textUri`.
  - The invariant is documented in `docs/storage.md:337-346` and `modules/storage/MODULE.md:151-160`.
  - The full fix is listed in STATUS.md:1012ff.
- **MINOR-1: CLOSED (with a residual, see R2-NIT-2).**
  - `Loaders.scala:66-70`: a missing directory now yields one `Left("input directory … is missing")` row instead of zero rows.
  - The tests at `LoaderSuite.scala:614-631` and `VectorSuite.scala:769-771` would fail on the old code, which read 0 rows.
  - The accepted choice, that the stage still records Succeeded, is documented accurately (`docs/storage.md:330-336`, MODULE.md:167-170). It is also no regression: the old behavior was Succeeded with 0 rows.
  - Residual that goes to the human: combined with MINOR-6, a run with a mistyped path leaves the ledger naming a report with read=1, rejected=1. A corrected rerun under the same run ID stores the rows, but the ledger keeps the first report.
- **MINOR-2: CLOSED.** `docs/storage.md:399-406` and MODULE.md:161-163 state the textUri rule for deleting a prefix, and that the load report holds counts only.
- **MINOR-3: CLOSED (one stale doc line, see R2-NIT-1).**
  - `BoundedTransport` (`AwsIntegrationSuite.scala:43-49`) counts every attempt and refuses attempt 26 and later without reaching the inner transport.
  - `run` wraps the `AwsHttp` transport (`:70-84`) and returns Failed when the bound is exceeded (`:96-97`).
  - A production index name is refused before any call (`:74-78`).
  - Tests: `:210-227` and `:229-236`. The tautological assert is gone.
  - Docs: `docs/storage.md:380-386` and MODULE.md:171-174.
- **MINOR-4: CLOSED.** `docs/storage.md:294-298` and MODULE.md:175-177 now state it as a wiring requirement. The false "loaders build AwsHttp" sentence is gone (grep finds it nowhere).
- **MINOR-5: CLOSED in the code.** `Tables.scala:304-305` now reads "typically within a few days". docs/service-limits.md was not updated to match (R2-NIT-3).
- **MINOR-6: CLOSED BY DOCUMENTATION, CARRIED FORWARD.** Documented at `docs/storage.md:347-349` and MODULE.md:164-166; listed in STATUS.md.
- **MINOR-7: CLOSED.**
  - `VectorLoader.scala:424-429` skips `prepare` once `err` is set.
  - The test at `LoaderSuite.scala:380-409` expects 1 S3 put where the old code made 20.
  - Its embedder assertion is weak: the old code also made 0 embedder calls when S3 fails on every document.
- **NIT-1: CARRIED FORWARD.** The report stays as written; MODULE.md says 21.
- **NIT-2: CLOSED.** `modules/core/MODULE.md` Known issues now say "2 of the AWS suite's roughly 30 cases … plus the published signing-key value".
- **NIT-3: CLOSED.** `Http.scala:126-128` no longer uses `e.getMessage`. Its error text is the URL cut at `?` and `#`. The test at `AwsSuite.scala:328-336` would fail on the old message, which carried the query secret.
- **NIT-4: CLOSED.**
  - `Credentials.scala:84-95` and `:121-126`: https is allowed; plain http only to literal loopback or container hosts.
  - It parses with `java.net.URI` (the same parser the JDK client uses), so a userinfo trick such as `http://127.0.0.1@evil/` is refused.
  - `127.0.0.1.evil.example.com` fails the full regex match, and IMDS (`169.254.169.254`) is refused.
  - The refusal does not echo the URL, and no request is sent: a spy transport counts 0 (`AwsSuite.scala:226-250`).
  - RELATIVE_URI keeps precedence and is not checked, as in the SDKs.
  - No regression found. It is stricter than the SDKs on non-literal loopback forms (for example `[0:0:0:0:0:0:0:1]` or `127.1`), which is acceptable.
- **NIT-5: CLOSED.** `AwsIntegrationSuite.scala:204-208` checks the real suite's `munitIgnore` against the gate. The test fails if the ignore wiring is removed.
- **NIT-6: CARRIED FORWARD** (optional).
- **Reviewer observation about `neptune.json` windows (spark-engineer): CARRIED FORWARD.**

## New findings from this re-check

**R2-MAJOR-1. A write outside the author's territory: domain-engineer's `sbt scalafmtAll` rewrote two storage-engineer files, and its report says it did not.**
- Owners: domain-engineer (process and the report claim); storage-engineer (accept the two files); human (disclosure).
- Files: `modules/storage/src/main/scala/scholarchain/storage/NeptuneAdapters.scala` and `modules/storage/src/main/scala/scholarchain/storage/Tables.scala`.
- Evidence:
  - Both files have the identical mtime 22:51:04.747513438. That is after the storage-engineer report was saved (22:49:52) and 1.3 s after domain-engineer's last write to `Credentials.scala` (22:51:03).
  - `.claude/hooks/format-scala.sh` formats only the file named in the payload, so the hook did not write them.
  - The r1 check then ran `scalafmt: Checking 2 Scala sources` and `compiling 2 Scala sources to …/modules/storage/…` (output lines 9 and 11). Those 2 storage sources had changed after storage-engineer's last compile.
  - In the domain-engineer write window (22:49:53 to 22:51:40) no other file changed. No tracked file changed.
  - `repair-01-domain-engineer.md` says scalafmtAll "touched nothing outside my territory: git status shows no new modified files". git status cannot show content changes to untracked files, so that claim rests on unusable evidence and is false.
- Content: formatting only. `.scalafmt.conf` has no rewrite rules, and the formatted files passed the full check.
- Root cause: storage-engineer's R1-1 edits (partly via sed and python, which bypass the format hook) left two files unformatted. `sbt storage/test` does not run scalafmtCheck, so R1-1 reported COMPLETE on files that would have failed `sbt check`.
- Impact: no behavior change. The one-writer invariant is broken, and a report makes an unsupported ownership claim.
- Repair brief (no code change):
  1. storage-engineer re-reads both files, especially `NeptuneAdapters.scala:74-83` and `Tables.scala:298-306`, and accepts them as its own; or the human discloses the write in `human-gate.md`.
  2. In future, domain-engineer formats only its own projects (`sbt core/scalafmt core/Test/scalafmt`).
  3. Owners run `<module>/scalafmtCheck` and `<module>/Test/scalafmtCheck` before reporting COMPLETE.
- Tests that must stay green: the whole r1 check (unchanged).

**R2-NIT-1. Stale opt-in example and an overstated bound claim in the integration suite's doc comment.** Owner: storage-engineer.
- `tests/module-unit/storage/AwsIntegrationSuite.scala:21` still shows `SCHOLARCHAIN_IT_VECTOR_INDEX=<existing index>`. The R1-1 report says the old name is gone.
  - Failure input: a human copies line 21.
  - Effect: the run fails safely with "SCHOLARCHAIN_IT_TEST_VECTOR_INDEX is not set" before any call.
- `:30` says every HTTP attempt passes through `BoundedTransport`. But `new EnvironmentCredentials(env)` (`:81`) uses its own default `JdkHttpTransport`, so a container-credentials fetch is not counted. That endpoint is free, so the cost risk is nil.

**R2-NIT-2. A missing directory satisfies an expected row count of exactly 1.** Owners: storage-engineer (code); documentation-engineer (wording).
- Where: `Loaders.scala:69` with `:277`, and the same check in `VectorLoader.scala:320`.
- Failure input: `expectedRows = Some(1)` with a mistyped path.
- Effect: read=1 equals expected, so the stage is Succeeded with 0 stored.
- The docs (`docs/storage.md:333-334`, MODULE.md:169) say an `expectedRows` check makes it fail, with no exception.
- The real manifests have 162 and 316 rows, so the impact is low. A possible fix: do not count the missing-directory row as read, or fail the stage on it.

**R2-NIT-3. docs/service-limits.md still says the Tables.scala comment is wrong.** Owner: documentation-engineer.
- `docs/service-limits.md:36-39` ("CORRECTION: the comment … says … 48 hours") and `:232-233` ("the '48 hours' comment … is wrong") describe the comment before MINOR-5. The comment is now fixed (`Tables.scala:304-305`).

**R2-NIT-4. STATUS.md has a stale row and a duplicate list.** Owner: documentation-engineer.
- `STATUS.md:981` still marks Review 2 "conditional", although Review 1 had a MAJOR.
- `STATUS.md:1012-1030` and `:1032ff` hold the same carry-forward list twice.

## Ownership and no-cloud

- Every changed or untracked file is in its owner's territory, except the two files in R2-MAJOR-1.
  - documentation-engineer: STATUS.md, the three MODULE.md files and the two docs files.
  - entity-resolution-engineer: its files, unchanged since 22:18, before step 04.
  - domain-engineer: `core/aws` and `AwsSuite`.
  - storage-engineer: the storage sources and tests.
  - orchestrator: `docs/agent-runs/phase-07/`.
- No-cloud: every transport in the core, storage and ER tests is a spy or a localhost stub, and the AWS hostnames in tests are asserted values only. `build.sbt` and `project/` do not contain `SCHOLARCHAIN_AWS_IT` (grep exit 1). The gated suite is ignored, which is the 1 ignored in the storage total.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: phase 07 Review 2 (re-check) of the Review 1 findings after repair round 1; regression, ownership, no-cloud and sbt-evidence currency checks.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status --porcelain (also --untracked-files=all); git diff --stat; git diff modules/core/MODULE.md
- cat, sed, awk and grep of: STATUS.md; the phase-07 prompt, review, routing and repair reports; output-sbt-check-r1.txt; docs/storage.md; docs/service-limits.md; modules/storage/MODULE.md; Loaders.scala; VectorLoader.scala; NeptuneAdapters.scala; Tables.scala; Http.scala; Credentials.scala; AwsIntegrationSuite.scala; the relevant parts of LoaderSuite, VectorSuite, AwsSuite, StorageSuite and BedrockAdjudicatorSuite; .claude/settings.json hooks; .claude/hooks/format-scala.sh; .scalafmt.conf
- ls -l --time-style=full-iso and find -newer / -newermt over modules, tests, build.sbt, project and docs (mtime currency and the scalafmtAll write window)
- grep for the old and new index variable names, stale claims, jsonLinesDir callers, AWS hostnames in tests, and the opt-in variable in build files
TEST RESULTS: I did not run sbt. I relied on docs/agent-runs/phase-07/output-sbt-check-r1.txt: exit 0, 305 s, 158 passed + 1 ignored, 119, 68, 98, 33, 119; markTestRun touched. With the check's start as the cutoff, the only newer files under modules/, tests/, build.sbt and project/ are the two MODULE.md files, so the output reflects the current code.
VALIDATION RESULTS: REQUEST_CHANGES.
- Review 1: MAJOR-1 closed by documentation and carried forward; MINOR-1 closed (residual combined with MINOR-6); MINOR-2, MINOR-3, MINOR-4 and MINOR-7 closed; MINOR-5 closed in the code; MINOR-6 closed by documentation and carried forward; NIT-2, NIT-3, NIT-4 and NIT-5 closed; NIT-1 and NIT-6 carried forward.
- New: R2-MAJOR-1, domain-engineer's scalafmtAll wrote NeptuneAdapters.scala and Tables.scala (storage territory), format only, and the report's ownership claim is false (domain-engineer, storage-engineer, human disclosure).
- New: R2-NIT-1, AwsIntegrationSuite.scala:21 old variable name and :30 bound wording (storage-engineer).
- New: R2-NIT-2, expectedRows=1 is satisfied by a missing directory, Loaders.scala:69/:277 and VectorLoader.scala:320 (storage-engineer, documentation-engineer).
- New: R2-NIT-3, docs/service-limits.md:36-39 and :232-233 stale "48 hours" correction (documentation-engineer).
- New: R2-NIT-4, STATUS.md:981 stale "conditional" row and the duplicate list at :1012/:1032 (documentation-engineer).
ASSUMPTIONS:
- sbt-scalafmt writes only files whose formatting changed. This is corroborated by zinc recompiling exactly 2 storage sources in the r1 check.
- R2-MAJOR-1 is rated by iron law 3 (a write outside the author's territory). Its content risk is nil.
RISKS (not reviewed):
- I could not see the before-image of the two reformatted storage files (they are untracked, with no baseline). That the change is format only rests on the scalafmt config and the green check.
- I did not re-review code unchanged since Review 1: GraphTemplates, NeptuneExport, VectorStore, S3, DynamoDB, the ER Bedrock adapter and SigV4.
- I did not read the AwsSuite, StorageSuite or NeptuneSuite assertions beyond the repaired tests.
- I did not verify live AWS behavior, did not re-fetch AWS docs and did not rerun sbt.
MANUAL ACTION REQUIRED (carry forward to the human gate, complete list):
1. R2-MAJOR-1: have storage-engineer accept NeptuneAdapters.scala and Tables.scala, or disclose the formatting write in human-gate.md; note that the R1-2 report's ownership claim is wrong.
2. MAJOR-1 full fix: run scoping in the Neptune templates and vector reads. Until then, one served run per Neptune cluster and per vector index, cleaned or rebuilt by the human before a new run is loaded (the human may overrule the minimum-repair decision).
3. MINOR-6 code fix: the ledger keeps the first report after a repairing replay. MINOR-1's accepted choice adds to it: a mistyped-path run stays Succeeded and keeps the report of the bad run.
4. R2-NIT-1 to R2-NIT-4 (above).
5. NIT-1: the step 00 report says 29 new tests; the suite counts give 21 (the report stays as written).
6. NIT-6: more published SigV4 vectors (optional).
7. Spark-engineer observation: load-manifests/neptune.json lists coauthoredWith.windows = ["1950-2026"], while the export has 1950-2026 and 2010-2014 partitions.
8. Already open: the Parquet reader gap; the Spark export gaps (institutionId, paper-abstract rows); adjudication sharing models.synthesisModel; the Phase 05 NIT-4 _SUCCESS rule is not implemented.
9. Not run: the gated AWS integration suite; no cloud resource exists.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Phase 07 human gate (no second repair round, per the addendum), with the carry-forward list above.
```
