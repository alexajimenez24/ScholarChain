HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (code-reviewer tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 step 3 re-review (fresh context, read-only) of the whole repair-01 diff: tracked changes plus untracked modules, tests and control plane. Followed the 12-point order and points 1 to 5 of the work order.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status, diff, show and log.
- Reads of every changed production file and the new ones (Communities.scala, the 4 hook files, settings.json, AGENTS.md, docs/agents.md, both skills), the phase-11 reports, phase-01 repair-02 and human-gate, and phase-05 human-gate.
- Guard probes: python3 -I scripts in scratchpad/rv2 send inert JSON payloads to the installed guard and to a scratch copy of the HEAD guard. About 200 payloads; nothing was executed.
- `timeout 9m python3 -I .claude/hooks/test_verify_tests_ran.py`: 17 OK.
- `timeout 9m python3 -I .claude/hooks/test_guard_dangerous.py`: 68 OK.
- `.agents/render.sh --check`: OK, 44 files.
- `.claude/hooks/validate-agent-files.sh`: OK, 19 roles, 727 files with one writer.
- stat of the marker plus find -newer: no source file is newer than the marker (19:53:39). No sbt run.
TEST RESULTS: I did not run sbt; I rely on test-report-02 (1092 passed, 0 failed, 1 ignored). The hook tests I ran are listed above. No test file lost an assertion (git diff -U0: only refactors, and assertions changed to stricter or to the new behavior).
VALIDATION RESULTS: REQUEST_CHANGES
1. MUST items (CLOSED / PARTLY / OPEN). "Test bites" means the test would fail if the behavior broke.
- C01 CLOSED: `cat .env #'` and `cat .env # don't print` give rc 2. C02 CLOSED: bash -c, eval and find -exec forms give rc 2.
- C03 CLOSED: all 5 Review B forms, plus env -S and xargs, give rc 2.
- C04 PARTLY: see R-3 and R-1.
- C05 CLOSED for the false positives: Review B (a), (b) and (c) give rc 0, and the must-block list still gives rc 2. But the relaxation caused R-1.
- C06 CLOSED: 6 loop forms give rc 2; bare pgrep and `sleep 30 && git status` give rc 0.
- C07 CLOSED (text rule; render --check OK). C08 CLOSED: logic read, 17 tests; the stale-marker refusal, SubagentStop, stop_hook_active and non-JSON paths are unchanged.
- C09 CLOSED: Verification.scala:304-316 and Synthesis amongText. FilteredRankGate bites: a VERIFIED rank text without the filter fails it.
- C11 CLOSED for the rag part (llm-synthesis-v2). rag MODULE.md still says v1; that is documentation-engineer's.
- C12 CLOSED: range-checked 1 to 1,000,000; over-bound clusters are refused and counted; CanonicalSuite pins both bounds.
- C13 CLOSED at fixture level. The fixture is hand-written; its dropFields match Spark's extraFields, which I checked.
- C14 CLOSED at fixture level. Spark, storage and rag agree:
  - path `jsonl/communities/windowLabel=W/recordKind=community`, and the rowsByPartition key format;
  - drop fields windowLabel and recordKind;
  - pk `community#<cid>`, sk `run#<sparkRun>#window#<label>`;
  - `CommunitySize.of` checks run, community and window, and rag re-checks them.
  - Old exports without the block give None, so sizes show as missing, never 0.
- C15 CLOSED: token_use binds the audience claim; all routes are scoped. C16 CLOSED: explain shows only status and a fixed category.
- C17 CLOSED:
  - recordReport is version-guarded, and the history is kept.
  - The new LedgerEntry field has a default and is omitted when empty. Old JSON decodes; LoaderSuite tests it.
  - A missing directory fails the stage, also through mapE, filter and concat.
- C18 CLOSED. C19 PARTLY: the leak is fixed and tested; two sweep behaviors have no test (R-4). C20 CLOSED (v2 and v3 tested). A-1 CLOSED: RejectsAndLimitsSuite pins the exact reason.
- B-1 PARTLY: `sbt eval`, set, console*, runMain of a non-scholarchain class, early(), reload plugins and `;`-chained forms are all rc 2. Two holes remain (R-2).
2 and 3. Findings:
- R-1 CRITICAL, MUST: the floor is weaker than HEAD.
  - Location: .claude/hooks/guard-dangerous.sh:1195-1205 (marker heredoc consumers) and :236-242 (structural keep_lines).
  - Input: the 3-line command `cat <<'X' | bash` / `touch .claude/.last-test-run` / `X` gives rc 0; the HEAD guard gives rc 2. The same holds for `cat <<'X' | bash` with body `sbt markTestRun`, for `cat <<'X' | python3` with body `open('<marker>','a')`, and for `tee /dev/stdout <<'X' | bash`.
  - Pre-existing, not a regression: the same pipe shape also passes `rm -rf datasets/gold`, `sbt 'eval 1'` and polling loops (HEAD rc 0 too).
  - Impact: a forged marker silences the stop hook, so sbt check evidence can be faked. Mitigant: `cat | bash` is not auto-allowed, so a prompt still shows.
  - Fix: a heredoc whose segment pipes (`|`, `|&`) into a shell, interpreter, eval, source or xargs is "fed to a shell". Apply this in the marker, gold and structural passes, and add these inputs as tests.
  - Owner: factory-engineer.
- R-2 CRITICAL, MUST: B-1 is not closed within the stated residual.
  - Location: guard-dangerous.sh:222 (SBT_OPT_BAD) and :288-292 (options are never checked against SBT_DENY).
  - Input: `timeout 30m sbt "core/testOnly * " "--addPluginSbtFile=/tmp/p.sbt"` gives rc 0, and so do the `-addPluginSbtFile=` form, `"-Duser.home=/tmp/e"` and `-J-Duser.home=...`. With the user.home override, sbt loads `/tmp/e/.sbt/1.0/plugins` and the global settings. `-Dsbt.coursier.home` also gives rc 0.
  - Exposure: by the factory's own reading of the wildcard, `Bash(timeout 30m sbt "core/testOnly *")` auto-approves these. A .sbt file written in the scratchpad then runs arbitrary code with no prompt and never appears in git diff. That is outside the stated residual ("the build's own code, reviewed in the diff").
  - Fix: allow-list sbt launcher options (for example -v, -batch, -mem N, --error, --warn, --info) and refuse every other `-`, `--`, `-D`, `-J` or `@` argument. Add tests.
  - Owner: factory-engineer.
- R-3 MINOR, MUST by the addendum (C04), unless the human re-confirms the residual in human-gate.md.
  - Location: guard-dangerous.sh:764-767 (gold_parts) and :789-810 (ancestor does not resolve `$HOME`).
  - Input: `rm -rf datasets/*`, `cd datasets && rm -rf *`, `rm -rf ./datasets/*`, `rm -r datasets/*/`, `rm -rf "$HOME/scholarchain/datasets"` and `ls datasets/* | xargs rm -rf` all give rc 0.
  - The residual is not adequately justified:
    - The cited round-2 decision was time-limited ("Declined in round 2 ... to revisit in Phase 05 when the gold set exists", phase-01 human-gate).
    - The Phase 05 gate reopened it as MINOR-C for Phase 11.
    - The repair blocks `rm -rf datasets`, which the same decision declined, so the decision is cited for only some forms.
  - Impact: uncommitted label work can be deleted. HEAD keeps the tracked file.
  - Fix: block a delete or move whose wildcard glob under datasets (or with cwd datasets) can match `gold`, which still allows `datasets/*.tmp`; resolve `$HOME` the way `~` is resolved.
  - Owner: factory-engineer; the decision is the human's.
- R-4 MINOR, MUST (gate item "every production behavior has a relevant test").
  - Location: rag Evidence.scala:46-50 (the note "dropped invalid evidence item <id or (malformed ID)>") and :82-86, plus Workflow.scala:155-159 (InvalidRequest changed to Downstream("evidence") and Downstream("answer")).
  - Input: revert either change and every test still passes. grep finds no test naming these strings or stages.
  - Mapping: the new path gives 502 dependency_failed (ApiError.scala:27). That code is retryable, releases the sync idempotency key, and counts toward the query circuit breaker (QueryService.scala:211-216). For a deterministic internal invariant, 500 internal would be more accurate (CARRY).
  - test-engineer's gap report is upheld. A test is feasible: EvidenceBuilderAgent is public and accepts an invalid item.
  - Owner: rag-engineer for the tests; service-engineer for a mapping test and the CARRY.
- R-5 MINOR, CARRY: false blocks (fail closed, misleading message).
  - The orchestrator's case is confirmed: `cp "$S/a.md" "$S/b.md" "$D/"` gives rc 2 "cp into $/, which can overwrite datasets/gold".
  - The same happens for `cp -p "$S/a" "$D/"`, `mv "$A" "$B/"`, `rsync -a /tmp/x/ "$D/"`, `rm -rf "$S/"` and `rm -rf "$S"/*`.
  - Cause: :796-798 resolves "variable plus /" to the repository root, while a bare "$S" is exempt.
  - Also: `python3 -c` naming `.claude/settings.json` followed by `[` is blocked as a marker glob, because MARK spans quotes (K04 class).
  - `rm -rf .`, `git restore .` and `git checkout .` are blocked as documented, which is acceptable.
  - These all pass: every prescribed sbt form (check, fmt, packageJobs, dockerLocal, project test and testOnly, scalafmt triples, runMain scholarchain.*, the timeout forms, pipes and redirects), `git restore <file>`, heredoc report writes naming the marker or the gold path, and `cp /tmp/a "$D/"`.
  - Owner: factory-engineer.
- R-6 MINOR, CARRY.
  - Location: guard-dangerous.sh:312-313; the check stops at the first test or run token, so later tokens in the same `;` segment are never checked.
  - Input: `sbt "core/testOnly *; all core/test core/console"` gives rc 0.
  - Impact: console needs stdin, which the allowed forms cannot supply, so I found no code path.
  - Owner: factory-engineer.
No other new defect found:
- Spark, storage and rag contracts agree.
- DynamoDB community items are create-only with a same-value check, and the run is in the key.
- Nothing scans the metric table.
- hadoop-v3 is accepted, and v2 stays readable.
- No other VERIFIED text drops a filter, and community sizes come only from the store.
- Ownership: every changed file is in its changer's territory (validator OK). The rendered commands match render --check.
- settings.json: the deny list is unchanged and the allow list only narrows. The guard test file lost no expectation, and the stop hook was not weakened.
5. Phase 11 gate:
- (a) No open critical or major finding: NOT MET (R-1, R-2).
- (b) Production behaviors tested: NOT MET (R-4).
- (c) All tests pass under their profiles: MET per test-report-02; the marker is fresh; the AWS suites are NOT RUN by declaration.
- (d) No reviewer wrote production code: MET.
- (e) Repair records name owners and rerun evidence: MET.
ASSUMPTIONS:
- Claude Code matches `*` in a Bash allow rule across quotes and later arguments (the same reading as Review B and factory-engineer). R-2 is CRITICAL only under it; otherwise MAJOR, with a prompt shown.
- sbt honours `--addPluginSbtFile` and `-Duser.home` as I describe, from its documented launcher behavior; I did not execute it.
- MUST items the addendum names are MUST whatever their severity.
- R-1 is rated by iron law 3 (it weakens the floor), despite the prompt mitigant.
RISKS (not reviewed or not checked):
- guard-dangerous.sh: about 650 of 1690 lines read. The secret, aws, scale-cap, human-gate and terraform sections were checked only by probes. The new test cases in test_guard_dangerous.py were not read line by line.
- Routing suite not rerun.
- New suites (SparkExportContractSuite, CommunityLoaderSuite, CommunitySizeSuite, ErrorTextSuite, LocalRuntimeSuite C14, JwtClaimsSuite, RuntimeEdgeSuite, MiniWorld) were read only for their assertions.
- rag Agents and Synthesis, and Spark Canonical, beyond the diff.
- No cross-stage test runs real Spark output through the storage loader and the rag reader (CARRY).
- Services and LocalIngestion do not wire communities yet (cloud phase).
- A live sbt server in the repo, under 45 minutes old, lets the main session stop (bounded by design).
- The hook files are already live before ratification.
MANUAL ACTION REQUIRED:
- The human decides whether the datasets/* residual (R-3) stands.
- Keep auto mode off until R-2 is fixed and ratified.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: repair round 2 (repair-02-<owner>.md):
- factory-engineer: R-1, R-2 and R-3 (R-5 and R-6 if the same lines are touched), with probes using the inputs above;
- rag-engineer: R-4 tests;
- service-engineer: the R-4 mapping test.
Then test-engineer reruns, and a fresh code-reviewer writes review-report-03.md.
