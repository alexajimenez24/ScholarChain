HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (high tier, role default per .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: P03-REVIEW-3 (phase 03, review of repair round 2; the last round before escalation to the human).
FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (I saw each one succeed)
- Reads: review-report-02.md, repair-02.md, all three repair-02-*.md reports, output-r2-1-mutation-checks.txt, the tail of output-r2-verify-sbt-check.txt, and the whole of Config.scala, Validate.scala, ConfigSuite.scala and scholarchain-v1.yaml. Relevant parts of ValidateSuite.scala, Fixtures.scala, Ids.scala, fetch_samples.py, fetch-all-samples.sh, test_dataset_manifest.py, MODULE.md, STATUS.md and the PROJECT_MEMORY.md diff.
- `git status --short`.
- `find -newer review-report-02.md` on modules/core, tests/module-unit/core and datasets/scripts, to find what round 2 changed.
- A search for leftover mutation backups (*.bak, *.orig, *.mut*, *~): none.
- `pgrep -af sbt`: none was running before my run.
- `python3 datasets/scripts/validate-manifest.py` (rc 0).
- `python3 datasets/scripts/fetch_samples.py verify --out datasets/samples/real` (rc 0).
- `python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'` (OK).
- `python3 -I -c` probe, in memory only: Arabic-Indic digits match `\d` in Python, but `date.fromisoformat` rejects them, so the snapshot check holds.
- `timeout 540 sbt -Dsbt.color=false check`, foreground, once: exit 0. Log at /tmp/claude-1000/-home-ajcreator-scholarchain/6739259f-9d26-4d31-ba18-dacbc04d5e83/scratchpad/sbt-check-r3.txt
- `.claude/hooks/validate-agent-files.sh`: OK.

TEST RESULTS
`sbt check` tail (my run):
```
Test run scholarchain.core.ValidateSuite finished: 0 failed, 0 ignored, 25 total 0.445s
Test run scholarchain.core.SerializationSuite finished: 0 failed, 0 ignored, 29 total 0.354s
Test run scholarchain.core.ConfigSuite finished: 0 failed, 0 ignored, 24 total 0.754s
[info] Passed: Total 94, Failed 0, Errors 0, Passed 94
[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run
[success] Total time: 2 s, completed Nov 22, 2026, 8:32:53 PM
exit=0
```
Dataset checks:
```
[manifest][ok] 5 datasets, required: dblp, openalex, crossref, orcid        rc=0
[verify] 4 files in /home/ajcreator/scholarchain/datasets/samples/real match retrieval.json   rc=0
Ran 17 tests in 0.632s
OK                                                                            rc=0
```
Ownership validator: `validate-agent-files OK: 19 roles, 19 Claude agents, 19 Codex agents, 4 MCP servers, 282 files with one writer`

VALIDATION RESULTS
Verdict: APPROVE. 0 CRITICAL, 0 MAJOR, 0 MINOR.

Did the mutation loop leave every rule in place? Yes. I checked each rule from review 2 MINOR-1 and MINOR-3 in the current source. None is missing, commented out or weakened.
- Config.scala:177-181: round-trip check `ujson.read(write(cfg)) == overridden`, wrapped in a Try that fails closed.
- Config.scala:231: `entityResolution.maxBlockSize` >= 1.
- Config.scala:239: `retrieval.vectorLimit` >= 1.
- Config.scala:250-255: the four non-blank version checks.
- Config.scala:277: `vectors.indexes.isEmpty ||` blank-name check.
- Config.scala:348-360: `MaxExactInteger`, the `PlainNumber` regex `-?\d+(\.\d+)?([eE][+-]?\d+)?`, `plainDouble`, and `acceptableNumber` with `!d.isNaN && !d.isInfinite` and the exclusive `math.abs(d) < MaxExactInteger`.
- `plainDouble` is used in all three places: placeholder default typing (333-336, 346), the placeholder value (`coerce`, 367) and the override (395-396).
- Validate.scala:67: work-ID shape check in `mention`.
- Validate.scala:268-271: AUTHORED endpoint rule.
- Every Validate.scala line number cited in review 2 (27-31, 53, 65-74, 268-271) still points at the same code. No backup files remain. zinc compiles from source content hashes, so my green run tested the current source.

Task 1: status of each review 2 finding
- MINOR-1 (silent integer saturation): FIXED.
  - Config.scala:177-181, 348-360.
  - Test: ConfigSuite.scala:206-227 covers C5, C7, the 3e9 kcoreMaxK, C8, 2^53 excluded, 2^53-1 exact, and the bundled config still loading.
  - Test: ConfigSuite.scala:228-235 covers 8182d, 0x1FF6p0 and 8182f as placeholder and as override.
  - Fails when the rule is removed:
    - Round-trip replaced by `true`: 1e10 becomes Int.MaxValue, passes `>= 1`, and gives Right, so the "clamped" assertion fails.
    - `<` changed to `<=`: 2^53 loads, so the "2^53 itself is excluded" assertion fails. This matches the orchestrator's mutation log.
    - Regex replaced by `toDoubleOption`: 8182d parses as 8182, so the test fails.
- MINOR-2 (snapshot label not validated): FIXED.
  - fetch_samples.py:57-64 `check_snapshot` uses `re.fullmatch` plus `date.fromisoformat`, and is called for every command at :281.
  - fetch_samples.py:67-74 adds a check that the default output directory stays under datasets/samples/local.
  - fetch-all-samples.sh:15-18 checks the regex and `date -d`, and exits 2 on failure.
  - The rule agrees with core `Validate.snapshotDate`: both are strict calendar dates, and 2026-02-29 is rejected on both sides.
  - Fails when the rule is removed:
    - Without `check_snapshot`, `../real` hits the "escapes" exit, which lacks "invalid --snapshot", so test_snapshot_label_validated fails.
    - Without the bash check, Python exits 1, not 2, so test_all_samples_script_rejects_bad_snapshot fails.
- MINOR-3 (rules no test pins): FIXED.
  - ConfigSuite.scala:241-261 has one assertion per rule, each matched on that rule's own message.
  - ConfigSuite.scala:236-240: 1e999 is plain decimal text and parses to Infinity. Without the finiteness rule the message changes to "clamped" or "threshold", so the test fails.
  - ValidateSuite.scala:83-93 adds a negative AUTHORED test.
  - ValidateSuite.scala:196-197 builds a mention ID consistent with "bad id". mentionRecordA has position 0 (Fixtures.scala:35), so only the shape check can reject it.
  - I reasoned through all 13 mutations; each fails exactly one named test. That matches output-r2-1-mutation-checks.txt ("Failed 1 ... Passed 93" for all 13).
- MINOR-4 (notes inaccuracies): FIXED.
  - MODULE.md:196-217: the false 2^53 guarantee and the sentence fragment are replaced with text that matches the code. "Exclusive, matching Ids.run" matches Ids.scala:110/129 (`>= 2^53` rejected).
  - STATUS.md: the open item about the Python tests appears once, at :198. Line 138 is the historical T01 note, not a duplicate.
  - The lists in MODULE.md Known issues (261-286) and in STATUS no longer have blank lines inside them.
  - The PROJECT_MEMORY row "Config loader strictness" matches the code.

Task 2: new defects from round 2
None found. I checked these points:
- Round-trip equality: `ujson.Obj` equality ignores key order, `-0.0 == 0.0`, and NaN cannot reach it.
- No config case class has default parameters, so leaving defaults out on write cannot cause false rejects.
- JSON-valued overrides such as windows with an extra key or a fractional year are now rejected as well, which is correct.
- `SCHOLARCHAIN_CONFIG_FILE` and the bundled file still load.
- Counts in the notes match the run: 94 core tests (4/12/25/29/24) and 17 Python tests.
- The frozen wire files, IdsSuite and Fixtures have not changed since review 2, by mtime.

Task 3: ownership
Every file round 2 changed is inside its author's territory, and the validator is OK:
- Config.scala, ConfigSuite.scala, ValidateSuite.scala: domain-engineer.
- fetch_samples.py, fetch-all-samples.sh: dataset-engineer (`datasets/**`).
- test_dataset_manifest.py: dataset-engineer (`tests/module-unit/datasets/**`).
- MODULE.md, STATUS.md, PROJECT_MEMORY.md: documentation-engineer.
- docs/agent-runs/phase-03/*: orchestrator.
- Nothing changed under build.sbt, project/, datasets/gold, tests/contracts or the control plane.

No test dispute was raised, so no FAIR, NOT FAIR or SPEC UNCLEAR verdict is needed.

ASSUMPTIONS
- Review 2 already reviewed the Phase 03 diff from before round 2. I re-reviewed only round 2's changes and the rules it touched.
- I took the 2^53 upickle Long write behaviour (a number, not a string) from the orchestrator's mutation log, which shows the `<=` mutant loading 2^53. I did not re-run that mutant myself.

RISKS
Low-impact observations, not findings. Each one fails closed or is cosmetic:
- Number typing comes from the value being replaced, not from the schema (Config.scala:334, :396). If a Double field ever had a whole-number file value (for example `"rejectThreshold": 0`), a fractional override would be rejected with "a finite whole number is required". The bundled file has no such value. This predates round 2 and is related to MODULE.md:267-268.
- Decimal text more precise than a double is rounded before the whole-number check. For example "8182.0000000000000001" loads as 8182, and "9007199254740991.4" loads as ...991. So "must be a whole number" in MODULE.md is true of the parsed double, not of the text. This is negligible.
- The stricter regex now rejects "+5", ".5", "5." and values with surrounding whitespace ("8182 "), which Java parseDouble used to accept. This fails loudly and matches the documented form.
- MODULE.md:187-193 and :205-217 describe the validator twice, with overlapping content. It is cosmetic; the two agree.
- Residual items from review 2 stay open as documented known issues: T14c fractional run-ID collision, the "TraceException: $" reason, duplicate JSON keys, decode cost, ~230 bytes per author, unchecked nested provenance, and componentSize 0. The Python tests are still outside `sbt check` (build-engineer).

Not reviewed:
- The parts of the Phase 03 diff that round 2 did not change; review 2 covered them.
- Live fetch paths (no network fetch was run).
- Bodies of make-fixtures.py and validate-manifest.py.
- Normalize.scala and Hashing.scala.
- I did not re-apply any mutation to a copy; I checked them by reasoning only.

Side effects, none tracked by git: `sbt check` touched `.claude/.last-test-run` and target/, and the Python tests regenerated the git-ignored `__pycache__/`. The sbt log is in the scratchpad path above. Nothing hung, and no background process was started or left running.

MANUAL ACTION REQUIRED: none for this review. These human decisions are still open, as listed in STATUS.md: the dblp.dtd CC0 status, the ORCID API terms, the T01 dataset questions and the scala-library option.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator sends this report to documentation-engineer for STATUS.md, then assembles the Phase 03 phase report for the human gate (ratification and commit).
