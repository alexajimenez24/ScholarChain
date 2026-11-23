HARNESS: claude-code
MODEL: sonnet (claude-sonnet-5-5), standard tier, role default (per .agents/models.json)

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: P03-R2-1, repair round 2: MINOR-1 (config integer saturation), MINOR-3 (untested rules).

FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/core/src/main/scala/scholarchain/core/Config.scala
- /home/ajcreator/scholarchain/tests/module-unit/core/ConfigSuite.scala
- /home/ajcreator/scholarchain/tests/module-unit/core/ValidateSuite.scala
(`sbt fmt` was run; it touched only these files.)

COMMANDS RUN (all observed):
- `sbt check` several times. The first runs hit a scalafmt failure on ConfigSuite. `sbt fmt` fixed it, and then `sbt check` was green.
- A mutation script, `python3 .../scratchpad/mut.py`, ran `sbt core/test` once per mutant. It backed up the file, applied one mutation, ran the tests, and restored the file.
- A final `sbt check` after all mutants were restored.

TEST RESULTS: the final `sbt check` is green. Tail:
```
Test run scholarchain.core.IdsSuite finished: 0 failed, 0 ignored, 12 total 0.277s
Test run scholarchain.core.ValidateSuite finished: 0 failed, 0 ignored, 25 total 0.486s
Test run scholarchain.core.SerializationSuite finished: 0 failed, 0 ignored, 29 total 0.383s
Test run scholarchain.core.ConfigSuite finished: 0 failed, 0 ignored, 24 total 0.858s
[info] Passed: Total 94, Failed 0, Errors 0, Passed 94
[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run
[success] Total time: 2 s, completed Nov 22, 2026, 8:20:44 PM
```
Counts: Normalize 4, Ids 12, Validate 25 (was 24), Serialization 29, Config 24 (was 20). Total 94 (was 89).

FINDINGS
- MINOR-1: FIXED. In `ConfigLoader.parse`, after `read[ScholarChainConfig](overridden)`, the typed config is re-written. The load requires `ujson.read(write(cfg)) == overridden`. If not, it returns Left with "config value was clamped, truncated or is not in the schema (a number out of range for its field)".
  - The bound is now exclusive: `abs(d) < 2^53`.
  - `plainDouble` accepts only `-?\d+(\.\d+)?([eE][+-]?\d+)?`. It is used for the placeholder default typing, the placeholder value, and the override value. `8182d`, `0x1FF6p0`, `8182f` and `NaN` are no longer numbers.
  - The bundled config still loads (`load(Map.empty)` is asserted).
  - Proving tests, all in ConfigSuite:
    - "integer values never saturate or lose digits silently": C5 (QUERYTIMEOUTMS=1e10), C7 (QUERY_REQUEST_TIMEOUT_MS=9999999999), a 3000000000 kcoreMaxK, C8 (seed 2^53+1), and seed 2^53, all Left. Seed 2^53-1 loads exactly. The bundled config loads.
    - "only plain decimal number text is a number...": `NEPTUNE_PORT=8182d`, `0x1FF6p0` and `8182f` are Left. Overrides `8182d` and `0x1FF6` give "not a number". `8183` loads.
- MINOR-3: FIXED. One assertion per rule, each matched to that rule's own message:
  - ConfigSuite "each remaining range and name rule rejects its own failing input":
    - MAXBLOCKSIZE=0 matches "maxBlockSize".
    - VECTORLIMIT=0 matches "retrieval.vectorLimit".
    - Blank entityResolution.policyVersion, graph.policyVersion, graph.metricVersion and retrieval.fusionPolicyVersion each match "<name> is empty".
    - VECTORS__INDEXES={} matches "vectors.indexes".
  - ConfigSuite "an infinite-valued override is caught by the finiteness rule itself": ACCEPTTHRESHOLD=1e999 must give "not a number" and "(finite)". NaN cannot reach that rule any more, because the plain-decimal regex now rejects it first. So I used 1e999, which is plain decimal text and parses to Infinity.
  - ValidateSuite "an AUTHORED edge from a work ID to a researcher ID is rejected" matches "must run from a researcher or mention ID".
  - ValidateSuite "a mention ID is recomputed..." now builds a mention whose ID is consistent with the bad work ID (`Ids.mention("bad id", 0)`), so only the shape check can reject it. It matches "malformed work ID".

MUTATION CHECKS (the rule was temporarily removed, `sbt core/test` was run, and the rule was restored). Every mutant produced "Failed: Total 94, Failed 1, Errors 0, Passed 93". Every one compiled, so the failure came from the named test and not from a compile error.
- maxBlockSize line removed: "each remaining range and name rule" failed.
- vectorLimit line removed: the same test failed.
- entityResolution.policyVersion removed: the same test failed.
- graph.policyVersion removed: the same test failed.
- metricVersion removed: the same test failed.
- fusionPolicyVersion removed: the same test failed.
- The `vectors.indexes.isEmpty ||` term removed: the same test failed.
- AUTHORED rule changed to accept anything: the AUTHORED test failed.
- `!d.isNaN && !d.isInfinite` removed from `acceptableNumber`: "an infinite-valued override..." failed.
- The work-ID shape check removed from `Validate.mention`: "a mention ID is recomputed..." failed.
- `<` changed back to `<=`: "integer values never saturate..." failed.
- Plain-decimal regex replaced by `toDoubleOption`: "only plain decimal..." failed.
- The roundtrip check replaced by `true`: "integer values never saturate..." failed.
After the run, `Config.scala` and `Validate.scala` were back to their intended content (grep of the restored rules, then a green `sbt check`).

OPTIONAL ITEMS: NOT DONE, by choice. I did not reject non-integral numbers in run manifests (T14c), and I did not add a readable reject reason for a missing required key (T5). Both would change the `Ids.run` or `JsonLines` surface, and the order said "only if small and safe". They remain open residual items from review 2.

VALIDATION RESULTS
- Frozen wire bytes: unchanged. No file under tests/module-unit/core/resources was touched, and the Serialization and Ids frozen tests pass.
- Config precedence: unchanged. The file is the base, then `${VAR:-default}` placeholders, then `SCHOLARCHAIN__A__B` overrides. The existing precedence test passes.
- Schema versions: unchanged (18 constants).
- Stable ID formulas: unchanged.

ASSUMPTIONS
- A config file with a key outside the schema is now rejected by the roundtrip check, because it differs from its canonical form. This follows the repair brief. The bundled file loads, so no key is extra or missing.
- The roundtrip check also fails any schema field missing from the file (it would be re-written with its default). Nothing in the bundled file is affected.

RISKS
- Not done: T14c and T5 (above). Duplicate JSON keys in run manifests and in `JsonLines` are still accepted, last one wins (review 2 N13).
- The mutation script and its output are in the scratchpad only. They are not in the repository.
- Stray background waiter shells from my polling were killed. No sbt is running.
- Notes for MODULE.md (documentation-engineer). Exact wording for the config section, replacing the lines at MODULE.md:196-201 that overclaim:
  "Configuration loading enforces the following. A number is accepted from a placeholder or an override only as plain decimal text (`-?digits[.digits][e+-digits]`); hex, Java float suffixes such as `8182d`, `NaN` and `Infinity` are rejected. For an integer field the value must be a whole number with an absolute value below 2^53 (exclusive, matching `Ids.run`). After the typed config is read, it is written back and must equal the overridden JSON tree, so any value that the typed read would clamp (for example above 2^31-1 for an Int field), round or drop, and any key outside the schema, makes the load a Left. Beyond that, the validator requires: mode `local` or `aws`; in `aws` mode non-empty bucket, endpoint, table, queue, embedding model and auth values, and, when `llmEnabled` is true, non-empty synthesis and verification model IDs; ports 1..65535; timeouts at least 1 ms; every limit, iteration and size field at least 1 (including entityResolution.maxBlockSize and retrieval.vectorLimit); retry and LLM-call caps at least 0; non-blank policy, metric and fusion-policy versions; a non-empty vectors.indexes with no blank name; retrieval.maxPathDepth within 1..graph.pathMaxDepth; entity-resolution thresholds in [0,1] with reject < llmBandMin <= llmBandMax <= accept; and valid graph windows."
  Test count line: 94 tests (Normalize 4, Ids 12, Validate 25, Serialization 29, Config 24).

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates MODULE.md and STATUS.md (MINOR-4 and the wording above). dataset-engineer handles MINOR-2. Then a fresh code-reviewer runs review 3.
