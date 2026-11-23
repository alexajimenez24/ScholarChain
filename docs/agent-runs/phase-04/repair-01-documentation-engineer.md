HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier default, no override)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 04, repair round 1, task R1b (brief D1 / finding F11): correct modules/hadoop/MODULE.md and docs/hadoop-jobs.md, record the 2026-11-23 decisions in PROJECT_MEMORY.md, update STATUS.md.

FILES CREATED: none.

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/hadoop/MODULE.md
- /home/ajcreator/scholarchain/docs/hadoop-jobs.md
- /home/ajcreator/scholarchain/PROJECT_MEMORY.md
- /home/ajcreator/scholarchain/STATUS.md

What changed:
- MODULE.md, F11 items:
  - :13 now says a `.gitkeep` remains in all three roots, `scala` included. I checked with `ls -a`.
  - :120 strict UTF-8 is replaced by the declared-encoding rule: charset read once from byte 0, BOM, no prolog means UTF-8, reporting decoder, whole-file refusals, the `UnsupportedEncoding:` unit, and the `BadEncoding:<head>\n[charset=<name>]` value and reason formats.
  - :123 DOCTYPE/ENTITY now says a header internal subset is tolerated and a DOCTYPE or ENTITY inside a record is rejected.
  - :160 oversize-block wording: split by full name first, hard cap 10 x `--max-block`, fallback-key policy.
  - :209-211 split independence is now explained and backed by the split tests.
- MODULE.md, repair content:
  - strict args
  - J03 DUPLICATE/CONFLICT semantics
  - new counters FALLBACK_BLOCKS_COMPARED, FALLBACK_BLOCKS_REFUSED, UNSUPPORTED_ENCODING, J01 INVALID_IDENTIFIER, J03 OVERSIZE_BLOCKS cap
  - unclosed-record recovery at the next start tag
  - streaming J07 reducer
  - LogicVersion "hadoop-v2"
  - Done and Known issues rewritten from the repair report; the 4 open T3 items and the imprecision and encoding-question lists are removed
  - post-repair runs run-8b3a162615a54f8f and run-6d1175dbfdb874a9
  - tests: core 94, hadoop 98, adversarial 68 of 68
- docs/hadoop-jobs.md:
  - evidence section now cites HR, HR2 and TR
  - new "Per-job items (spec lines 2196-2210)" section: combiner none, partitioner, reducers, codec, S3 prefixes (EMR prefixes set in Phase 13 by infra-engineer), idempotency, fixtures, and a per-job table of input format, key and value, output, reducers and reject conditions
  - blocking and fallback section
  - counters and the J03 semantics
  - post-repair run tables
  - adversarial section with the 5 formerly red tests now green
  - the open encoding question replaced by the human's decision
- PROJECT_MEMORY.md: two decision rows, the verbatim encoding rule and the one-repair-round budget with carry-forward to the human.
- STATUS.md: R1a done (note R1a), R1b done (note R1b), R1c running, Review 2 next, current-state block updated, the encoding decision marked implemented and documented. The note records the process observation: one `sbt hadoop/test` was moved to the background by the harness at 120 s, and hadoop-engineer reports it started no second sbt meanwhile.

COMMANDS RUN:
- Read of the named files; grep and sed on review-report.md, the spec lines 2190-2212, Jobs.scala and Pipeline.scala (no combiner and no S3 code found; reducers are `--reducers`, J02 has 0).
- `ls -a` on the three source roots.
- Python edit scripts on the four files.
- `awk 'length>90'` and a grep for the em dash on the four files.
All succeeded. I ran no sbt.

TEST RESULTS: none run. Numbers quoted (core 94, hadoop 98, adversarial 68 of 68, sbt check exit 0) are from repair-01-hadoop-engineer.md.

VALIDATION RESULTS:
- The em-dash count is 0 in all four files.
- Lines over 90 characters remain only in markdown tables, in code-command lines and in a few wrapped lines. Most of them were there before this task (STATUS.md:125, 267, 289, 405 and the pre-existing tables). A few of my own table rows are long as well.
- Every path and link I added was checked against the files read. The evidence links are `agent-runs/phase-04/agent-report.md`, `repair-01-hadoop-engineer.md` and `test-report.md`.
- Documented commands: the LocalRunner command is unchanged and was not replayed, because the work order forbids sbt.

ASSUMPTIONS:
- The per-job reject conditions come from the `reject(...)` calls in Jobs.scala and from the repair report. I did not run each one.
- "No combiner" rests on a grep of Pipeline.scala and Jobs.scala finding no combiner class.
- "line text" is the input format for J02 to J07, taken from the key-value contracts (offset, line). I did not read the format setup.
- J05 and J02 reject conditions are stated generally ("unparsable, invalid").
- Adversarial suite totals are TR's; per-suite pass counts after the repair were not reported, and the docs say so.

RISKS:
- Pre-repair values I could not re-observe are kept and labelled "before the repair": giant-team numbers, the part-file counts and the RejectsAndLimits counts.
- I dropped the T3 per-stage duplicate counts and the "60 identical Zhang give 0 candidates" claim, because the repair changes both.
- hadoop-jobs.md lists 4 bullets for the 5 formerly red tests, because the two CommonNameSuite tests share one bullet.
- The test-engineer rerun R1c and Review 2 were still pending, and the docs say so.
- `sbt packageJobs` is still unobserved.
- The test-engineer ISO-8859-1 black-box case is not yet written.
- The repair report says the run output folders live in the hadoop-engineer's scratchpad, so they are not repository evidence.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R1c (test-engineer rerun of adversarialTests), then Review 2 by a fresh code-reviewer.
