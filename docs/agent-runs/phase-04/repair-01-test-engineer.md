HARNESS: claude-code
MODEL: opus, effort high (high tier per `.agents/models.json`, the role's default; no override)

Summary: all 5 adversarial tests that were red are now green, and the other 63 still pass. The one `sbt check` run exited 0, and no test was changed. In the log I found a new mismatch: reject reasons now print raw Scala `Option` text. Details are under RISKS 1.

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 04, repair round 1, task R1c. Rerun the Phase 4 adversarial Hadoop tests after the hadoop-engineer repair. Change no test unless the repair broke it in a way the review did not cover. Leave anything red as red. Model: the high-tier default.

FILES CREATED: none
FILES MODIFIED: none. Every file under `tests/adversarial/` is byte-identical before and after the run. The first 16 hex digits of each SHA-256, taken before and after:
- CommonNameSuite 5c7d015c0cf2e833
- DblpXmlAttackSuite 34dba89754c592d4
- DuplicateIdSuite 86d2a68e2a0f92fb
- GiantTeamSuite 2be65c78d71524cf
- HadoopBlackBox 3c8212226941a158
- MissingFieldSuite 30400c720709d376
- UnicodeSuite 75d82d7908bb923d
- resources/logback-test.xml d187c62d87e64e3a

The `.scala` files still have their T3 timestamps (2026-11-22 23:41 to 2026-11-23 00:00).

COMMANDS RUN (each in the foreground; I saw the output of each):
- `sha256sum` on the adversarial test files, before and after the run (results above).
- `source toolchain/env.sh && timeout 590 sbt -Dsbt.color=false check`, run once, with output captured to `/tmp/claude-1000/-home-ajcreator-scholarchain/aa3a3dc1-c39c-43bf-9bb9-1fff257eea96/scratchpad/r1c-check.log`. Exit 0, total time 117 s. The format check, compile and Test/compile all reported `[success]`. The last line of the check reports "14 project test runs passed" and that it touched the marker file.
- `grep` and `sed` on that log to pull out the totals.
- `ls -d /tmp/sc-adv-*`: 0 left. `git status --short tests/`: no change to tracked files under `tests/`.

TEST RESULTS (from the one `sbt check`, exit 0):

| Project | Total | Passed | Failed |
|---|---|---|---|
| core | 94 | 94 | 0 |
| hadoop | 98 | 98 | 0 |
| adversarialTests | 68 | 68 | 0 |

- core suites: NormalizeSuite 4, IdsSuite 12, ValidateSuite 25, SerializationSuite 29, ConfigSuite 24.
- hadoop suites: RejectsAndLimitsSuite 10, DblpParserSuite 22, NormalizerSuite 10, JobLogicSuite 20, PipelineIntegrationSuite 18, RepairDblpSuite 10, RepairLogicSuite 6, RepairArgsSuite 2.

adversarialTests per suite (no failures, nothing ignored):

| Suite | Total | Passed | At T3 |
|---|---|---|---|
| DblpXmlAttackSuite | 13 | 13 | 13 of 13 |
| UnicodeSuite | 8 | 8 | 8 of 8 |
| MissingFieldSuite | 12 | 12 | 11 of 12 |
| DuplicateIdSuite | 11 | 11 | 10 of 11 |
| CommonNameSuite | 10 | 10 | 8 of 10 |
| GiantTeamSuite | 14 | 14 | 13 of 14 |

The 5 tests that were red are now green. Each was checked against what the run logged:
1. CommonNameSuite, oversize name block (Wang) fallback: green. There is 1 candidate, with `blockKey` "wang_w|co:zorander_k" and coauthor_overlap 1.
2. CommonNameSuite, oversize title block fallback: green. There is 1 candidate, dblp:c/title6 with dblp:c/title7, `blockKey` "wk:2019:common+graph|a:fallback_l".
   - J04 counters: OVERSIZE_BLOCKS 3, FALLBACK_BLOCKS_COMPARED 4, REJECTED_RECORDS 3, BLOCKED_CANDIDATES 15.
   - The 15 candidates by block key: ray 10, plus 1 each for fallback_l, qiu_l, zorander_k, the Wang fallback and the title fallback.
   - Zhang-Zhang candidates: 0. Ray candidates: 10.
3. MissingFieldSuite, an invalid ORCID is counted as INVALID_IDENTIFIER: green.
   - J01 INVALID_IDENTIFIER is 2 (badorcid and baddoi), and 0 in every other stage. The summary and J01 `_counters.json` agree.
   - The other J01 counters are unchanged from T3: INPUT 17, ACCEPTED 8, REJECTED 9, MISSING_KEY 2.
4. DuplicateIdSuite, J03 counts the DOI shared by 3 works, one given twice: green.
   - J03 has DUPLICATE_RECORDS 2 and CONFLICT_RECORDS 0.
   - This fits the repair's stated rule: the repeated OpenAlex copy counts once under `doi:` and once under `openalex:`, and the copies are identical, so there is no conflict.
   - Per stage (stage, DUPLICATE_RECORDS, CONFLICT_RECORDS): (J01,2,1), (J03,2,0), (J05,1,0), (J07,3,0).
5. GiantTeamSuite, a cap flag with no value is a usage error: green.

The other 63 tests still pass. Logged results that match T3:
- The hostile document gives 3 accepted and 5 rejected at every split size, with the same status heads.
- The DTD run keeps safe1 and safe2 and rejects xxe, lol6 and the in-record ENTITY; output is 10495 bytes.
- `largestBlockObserved` is {r:zhang_h, 60}.
- `maxTeamObserved` is {dblp:g/n13, 13}, and `largestPairExpansion` is 78.
- The kept conflict version is (Alpha, 2021, 2).
- The Unicode results are the same (NFC/NFD candidate "nunez_j"; Cyrillic and Latin names kept apart).

VALIDATION RESULTS:
- These spec requirements are now protected by green black-box tests:
  - fallback keys and an oversize-block policy (spec line 2245, job 4);
  - exact-identifier duplicate and conflict counters in J03 (job 3);
  - INVALID_IDENTIFIER counting for dropped DBLP ORCIDs (MODULE.md "Behavior to rely on");
  - strict `--name value` argument parsing (MODULE.md usage errors);
  - every T3 category still passes: adversarial XML, Unicode, missing fields, duplicate IDs, common names and giant teams.
- The repair did not break any T3 expectation. The hostile-document cases (C3 28, C0 AF and ED A0 80 in documents declared UTF-8) still give exactly 5 status units at every split size, as brief B5 required.

ASSUMPTIONS:
- I took the repaired behavior only from the TEST RESULTS and "ITEMS FOR documentation-engineer" sections of `repair-01-hadoop-engineer.md`, as the work order says. I did not read MODULE.md or any `src/main/**` file.
- The marker-file touch reported by the check comes from `sbt check` itself. No command of mine named the marker task.

RISKS:
1. New mismatch seen in this run's log (no test covers it). The J01 reject reasons print raw Scala `Option` text. Verbatim from the DblpXmlAttackSuite OBSERVE line:
   ```text
   unreadable record (OversizeNone) at byte 264, unreadable record (BadEncodingSome(, UTF-8)) at byte 3649, unreadable record (BadEncodingSome(, UTF-8)) at byte 3831, unreadable record (BadEncodingSome(, UTF-8)) at byte 4017, unreadable record (TruncatedNone) at byte 4377
   ```
   The repair report gives the format as `unreadable record (BadEncoding, <charset>) at byte N`. The text above does not match it, and it is not clean operator-facing reject text. The byte offsets match T3. My T3 tests check the reject count, the stage, the run ID, the length bound and UTF-8 validity, but not the reason text, so none of them catches this. I am raising it for the orchestrator to route; I added no test, because this task forbids new cases.
2. Provenance note, not a test failure. Fallback-block candidates still carry `policyVersion` "norm-v1" in their provenance, even though the blocking policy changed in this repair. The run manifest's `pipeline` field is "hadoop-v2". Whether "norm-v1" should cover blocking policy belongs to the reviewer or the human.
3. New cases I would add in a later task (none added here):
   - the planned black-box ISO-8859-1 case: a raw 0xE9 decodes to "é" when the file declares ISO-8859-1, and gives a BadEncoding reject when the file declares UTF-8 or nothing;
   - the exact reject-reason format, including the charset name (risk 1);
   - a UTF-16 or UTF-32 BOM or declaration, and an unknown or multi-byte legacy declaration such as Shift_JIS: one UNSUPPORTED_ENCODING unit at every split size;
   - a UTF-8 BOM next to a non-UTF-8 declaration, which the repair says is checked only at charset-detection level;
   - an unclosed record followed by many records: exactly 1 Truncated unit, with every neighbour kept at every split size and through `.gz`;
   - J03 with an exact key above `--max-block`: no work pairs, OVERSIZE_BLOCKS counted and a reject line, while mention links remain;
   - a fallback block above `maxBlock`: FALLBACK_BLOCKS_REFUSED counted with a reject line, and OVERSIZE_BLOCKS not raised;
   - fallback blocks never pairing two clusters that share a work, and never emitting a pair twice;
   - args: an unknown flag, a repeated flag, a value starting with `--`, and `--compress` set to something other than `true` or `false`;
   - the same J03 duplicate and conflict result under both arrival orders, with the smallest-JSON copy kept.
4. Not done: no new tests (forbidden here); no `LocalRunner.main` or `--compress false` run; no exactly-300-author expansion. Each was already disclosed at T3.
5. Carried over from T3: adversarialTests is not forked, so its pipeline runs share the sbt JVM. This run finished in 117 s without memory trouble. The fork setting belongs to build-engineer.
6. State left behind: no tracked file changed. The run log is in the scratchpad: `/tmp/claude-1000/-home-ajcreator-scholarchain/aa3a3dc1-c39c-43bf-9bb9-1fff257eea96/scratchpad/r1c-check.log`. No `/tmp/sc-adv-*` folders remain. No cloud action, no commit.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review 2 by a fresh code-reviewer on the whole Phase 04 diff, including risk 1 above (reject-reason format, which would be hadoop-engineer's if judged a defect). After MODULE.md documents the declared-encoding rule, the separate black-box ISO-8859-1 task follows.
