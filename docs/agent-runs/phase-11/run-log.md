# Phase 11 run log

Harness `claude-code` 2.1.332 for every row. Model is the role's tier default unless noted.

## Orchestrator choices

- The addendum asks for `STATUS.md` once, at the end (step 5). That replaces the usual
  task list up front and per-task STATUS updates; this log is the checkpoint until then.
- Project memory `hook-edits-live`: edits under `.claude/hooks/` run from disk at once,
  so factory-engineer's hook changes are live when written, not after ratification.
  Only settings and agent files wait for a restart. The human must know this at the gate;
  a rejected hook change has to be reverted. factory-engineer stays last.

## Steps

| Step | Agent | Model, effort | Report | Status |
|---|---|---|---|---|
| 0 | orchestrator | main session | carried-items.md | 18 MUST, 15 CARRY (started 16:26) |
| 1 | test-engineer | opus, high | coverage-before.md, test-report.md | COMPLETE 16:28-17:13; 33 tests added; full sbt check (24m57s) exit 1: 5 red in adversarialTests (R6-1 x3, token_use, J04 OVERSIZE_BLOCKS); all else green; AWS suites NOT RUN |
| 2a | code-reviewer (fresh) | opus, high | review-report-a.md | REQUEST_CHANGES: A-1 (MUST), A-2, A-3 (CARRY); C12, C14, C18 MUST; J04 test FAIR. Part 1 skipped large parts of its half, so the same reviewer was continued once; areas still unreviewed are listed in the report. Saved by extracting its two hand-back messages |
| 2b | code-reviewer (fresh) | opus, high | review-report-b.md | REQUEST_CHANGES: B-1 CRITICAL (sbt allow rule), B-2 CRITICAL (= C01); 7 test/guard/storage/rag findings MAJOR or MUST; R6-1 and token_use tests FAIR; R4-1..3 closed |
| 3 | orchestrator | main session | repair-plan.md | C14 route settled for Review B after checking Export.scala |
| 4.1 | domain-engineer | sonnet, medium | repair-01-domain-engineer.md | COMPLETE: C12 field identityCutMaxClusterLinks (1..1000000, default 100000); core/test 123/123 |
| 4.2 | spark-engineer | opus, high | repair-01-spark-engineer.md | COMPLETE: C12 over-bound clusters refused and counted (LogicVersion spark-v5); C14 communities in JSON lines; sparkAnalytics/test 47/47 |
| 4.3 | storage-engineer | sonnet, medium | repair-01-storage-engineer.md | COMPLETE: C13, C14 load (CommunityReader), C17; storage/test 199 passed, 1 ignored (AWS) |
| 4.4 | rag-engineer | opus, high | repair-01-rag-engineer.md | COMPLETE: C09 filter named in every rank text (2nd site fixed), C11 llm-synthesis-v2, C14 read; rag/test 154/154; adversarial gate 138/138 (second run; first used an env redirect to an empty scratch dir, nothing on disk changed) |

## Choice for the human: the 25,000-work export

The addendum says tests never use the 25,000-work export. Suites from Phase 09 (BenchmarkWorkflowSuite
and the Workflow gate suites) read it when it is present, so the step 1 full check and
rag-engineer's gate did read it (the orchestrator's rag work order held both instructions).
Reading taken: the rule binds tests written in Phase 11, which use hand-built fixtures; the
existing declared suites run as before, because without the export 121 tests skip and one
unguarded test fails. Nothing writes to the export.
| 4.5 | service-engineer | sonnet, medium | repair-01-service-engineer.md | COMPLETE: C15, C16, B-19; services tests 82/82; JwtClaims + RuntimeEdge 16/16. Found C19: store/embedder error text reaches answer.summary through rag; routed to rag-engineer as MUST (security, same class as C16) |
| 4.4b | rag-engineer (continued) | opus, high | repair-01-rag-engineer-c19.md | COMPLETE: C19 plus two more text leaks; rag/test 158/158; RuntimeEdge + FilteredRankGate 16/16. Services tests not rerun by it (test-engineer full check covers them) |
| 4.6 | hadoop-engineer | sonnet, medium | repair-01-hadoop-engineer.md | COMPLETE: A-1 (LogicVersion hadoop-v3), C18 unit cases; hadoop/test 100/100; adversarial hadoop 78/78. Raised C20 |
| 4.2b | spark-engineer (continued) | opus, high | repair-01-spark-engineer-c20.md | COMPLETE: C20 Spark reads hadoop-v2 and hadoop-v3; sparkAnalytics/test 48/48 |
| 4.7 | factory-engineer | opus, high | repair-01-factory-engineer.md, output-factory-diff.txt | COMPLETE: B-1, C01-C08; guard tests 68, stop hook 17, ownership 4 OK; render --check and validator OK; routing 152/153 then 153/153; 10/10 probes. Hook files are live before ratification |
| 4.7n | orchestrator | main session | - | Observed after install: the new guard blocked `cp <two files> "$D/"` into docs/agent-runs as a gold write ("cp into $/"). The orchestrator reran it with a literal destination. Passed to review 2 as a candidate false block |
| 5 | test-engineer (fresh) | opus, high | test-report-02.md | COMPLETE: full sbt check exit 0 in 18m53s, 1092 passed, 0 failed, 1 ignored (gated AWS suite); 5 former red tests green; datasets Python 19, guard 68, ownership 4, stop hook 17 OK; AWS NOT RUN. Open: two C19-related behaviours have no test (rag-engineer, gate item) |
| 6 | code-reviewer (fresh) | opus, high | review-report-02.md | REQUEST_CHANGES: R-1, R-2 CRITICAL (guard); R-3 (C04 partly), R-4 (untested C19 behaviours) MUST; R-5, R-6 CARRY. All other MUST items CLOSED |

## Round 2 scope (orchestrator choice)

R-1 and R-2 are critical. R-4 breaks a gate item and R-3 leaves an addendum MUST (C04)
partly open, so both are in the round: the addendum says MUST items are repaired in this
phase. R-3 only tightens the guard, so it does not wait for a human decision on the old
`datasets/*` residual. R-5 and R-6 are folded in by factory-engineer only if few lines in
the same code and without weakening a block. R-4's CARRY (502 versus 500) stays open.
Order: rag-engineer, service-engineer, factory-engineer last.
| 7.1 | rag-engineer | opus, high | repair-02-rag-engineer.md | COMPLETE: R-4 tests (InvariantErrorSuite, 5); rag/test 163/163; no production change |
| 7.2 | service-engineer | sonnet, medium | repair-02-service-engineer.md | COMPLETE: R-4 mapping pinned (6 tests); services/test 88/88; no production change |
| 7.3 | factory-engineer | opus, high | repair-02-factory-engineer.md, output-factory-diff-02.txt | COMPLETE: R-1, R-2, R-3; R-5, R-6 folded; guard 78, stop hook 17, ownership 4 OK; routing 153/153; probes 29/29; differential 1022 inputs, 19 HEAD-blocked now pass (listed as C05 cases). Hook files live before ratification |

Choice: step 4 of the addendum names no full check, but round 2 added test files after the
last one, so test-engineer runs `sbt check` once more; "all tests pass" needs it.
| 8 | test-engineer (continued) | opus, high | test-report-03.md | COMPLETE: full sbt check exit 0 in 16m12s, 1103 passed, 0 failed, 1 ignored (gated AWS suite); datasets Python 19, guard 78, ownership 4, stop hook 17 OK; AWS NOT RUN; 2 guard false blocks on read-only commands that handle the marker name |
| 9 | code-reviewer (fresh) | opus, high | review-report-03.md | REQUEST_CHANGES: R-1..R-4 CLOSED; new S-1 CRITICAL (guard); S-2..S-5 minor CARRY |

## Escalation to the human (no third repair round)

- S-1, CRITICAL, owner factory-engineer, still open after round 2, so the addendum sends it
  to the human. Reason: since round 1 the guard treats every non-shell heredoc body as
  data, but bash runs command substitution in an unquoted body, and an unusual delimiter
  hides the lines after it. Some inputs HEAD blocks now pass (forge the test marker, print
  a secret file). By the reviewer's reading no allow rule matches them, so a prompt remains.
- The guard is live now. Choices (review-report-03.md): (a) authorize a bounded fix with
  the S-1 inputs as tests plus a fresh review (reviewer's recommendation); (b) ratify with
  S-1 recorded as open; (c) revert the guard to HEAD, which reopens B-1, R-1, R-2 and C01.
- Gate item "no open critical or major finding" is NOT MET. The other four items are MET.

## Deviations to review at the gate

- test-engineer was continued, not freshly spawned, for the step 8 rerun (S-5); the
  AGENTS.md fresh-context rule asks for a new agent.
- The same agents were continued for follow-ups: code-reviewer A (to finish its own half),
  rag-engineer (C19) and spark-engineer (C20).
- The orchestrator ran on claude-sonnet-5-5, not the high tier the matrix names.
- review-report-a.md is two hand-backs, 146 lines (cap 120).
- Reviewer reports were saved by extracting the hand-back text from the agent transcripts.
| 10 | documentation-engineer | sonnet, medium | step-docs-documentation-engineer-report.md | COMPLETE: STATUS.md and MODULE.md notes (core, hadoop, spark, storage, rag, services). Phase ends with S-1 open for the human; nothing committed |

## Human decision on S-1 (relayed in the main session)

Option (a): a third and final guard repair round, as a disclosed exception to the two-round
limit. Fresh agents throughout. Order: factory-engineer (conservative heredoc rule: data only
when the end marker is quoted and the receiver is not a shell or interpreter; every other body
scanned as commands; an end marker that cannot be parsed makes the rest code; shell writes to
the user's sbt and ivy folders and to the repository build folder blocked; hooks built on
copies and installed once), a fresh code-reviewer (review-report-04.md), documentation-engineer
(STATUS.md once). Full sbt check only if a Scala or test file changes. Reports under 80 lines.
No commits. If review 4 leaves a critical finding open, stop.

## Interruption in round 3

| Step | Agent | Model, effort | Report | Status |
|---|---|---|---|---|
| 11a | factory-engineer (fresh) | opus, high | none | INTERRUPTED: API 429 "usage credits ran out" while editing its scratch copy of the guard. Harness marked its work unreviewed |

Checked afterwards, read-only: the 10 files of output-factory-diff-02.txt, rebuilt from HEAD,
equal the installed files byte for byte (hooks and settings included); test_verify_tests_ran.py
was last modified before repair-02 ended; no Scala file changed; no sbt or test process left.
Installation had not started, so nothing was restored and the suites were not required. The
unfinished copy (scratchpad fe3) is not reused. Human instruction: resume the interrupted step
with a fresh agent.

Human addition to round 3 (constitutional, for ratification): factory-engineer changes
`.agents/models.json` so code-reviewer uses the standard tier for Phases 12 to 16, with a
note that Phase 17's final review uses the high tier; re-render; include in
output-factory-diff-03.txt. Sent to the running factory-engineer. Review 4 belongs to Phase 11
and is run on the high tier (model override `opus`).

## Round 3 results and STOP

| Step | Agent | Model, effort | Report | Status |
|---|---|---|---|---|
| 11b | factory-engineer (fresh) | opus, high | repair-03-factory-engineer.md, output-factory-diff-03.txt | COMPLETE: listed S-1 inputs blocked, build-input write block, code-reviewer tier change; guard 86 (four class groups), ownership 4, stop hook 17 OK; validator and render check OK; routing 153/153; differential: 13 HEAD-blocked inputs pass, reported as C05 cases. Harness raised a Self-Modification warning |
| 11c | orchestrator | main session | - | Read-only check after the warning: the control-plane files that changed match the two human decisions; settings.json equals repair-02; no Scala file changed, so no full check |
| 12 | code-reviewer (fresh) | opus (override); effort high requested, not verified by the reviewer | review-report-04.md | REQUEST_CHANGES: 7 listed S-1 inputs closed, the rule is not closed; T-1, T-3 CRITICAL and T-2 MAJOR open; T-4, T-5 minor |

STOPPED by the human's instruction: review 4 leaves critical findings open (T-1, T-3, owner
factory-engineer). No further repair, and the documentation step (STATUS.md update) was not
run, so STATUS.md still describes the state before round 3. The installed guard is the
round-3 one and is live. Nothing was committed or reverted.

Correction (T-5): the earlier line saying review 4 runs on the high tier relied on a model
override plus an effort override of high; the reviewer could not confirm the effort, and the
agent file on disk now says sonnet, medium.

After the review, the new guard blocked one orchestrator command: a quoted log-text heredoc
beside a python command, read as shell (an accepted round-3 false block). The command did not
run; the extraction was rerun alone and this entry was written with the Edit tool.

## Human decision on T-1 to T-3: guard round 4 (disclosed exception)

Recorded 2026-11-26 01:06, verbatim from the human's message in the main session:

> Human decision on T-1 to T-3: one final guard round (round 4, a disclosed exception). This is the last guard round in Phase 11 whatever review 5 finds; anything still open goes to the human gate.
> 1. Fresh factory-engineer: make the guard fail closed instead of adding exceptions. Always scan every heredoc body as commands (drop the quoted-marker data rule). Block any command the parser cannot fully parse, including << inside ${...} or $[...], and treat test, [ and [[ with -v or other evaluating options as code. If the scan exceeds its size or time limit, block (never allow on timeout), and confirm what the harness does when a hook times out. Keep the build-input write block, the reviewer-tier change and every earlier repair. Agents write reports with the Write tool. Build and test on copies, install once after they pass, rerun the hook suites, validate-agent-files.sh, .agents/render.sh --check and the routing suite, and add the T-1 to T-3 inputs as tests. Save repair-04-factory-engineer.md and output-factory-diff-04.txt.
> 2. Fresh code-reviewer on the high tier (opus, effort high): check T-1 to T-3 and the fail-closed rule, nothing weaker than HEAD, and ordinary read-only commands still allowed. Save review-report-05.md.
> 3. documentation-engineer: STATUS.md once, covering rounds 3 and 4.
> Reports under 80 lines; the orchestrator reads only STATUS, TEST RESULTS and BLOCKED-ON lines. No commits. Record the decision in run-log.md.

Orchestrator note on report files: guard-ownership lets only the orchestrator write
`docs/agent-runs/**` with file tools, and code-reviewer has no Write tool. No fence is
changed. factory-engineer and documentation-engineer write their reports with the Write
tool in the session scratchpad and the orchestrator copies them in byte for byte;
review-report-05.md is the reviewer's hand-back, saved verbatim by the orchestrator.

| Step | Agent | Model, effort | Report | Status |
|---|---|---|---|---|
| 13 | factory-engineer (fresh) | opus, high | repair-04-factory-engineer.md, output-factory-diff-04.txt | COMPLETE (01:06 to about 01:54): installed files: guard 95/95, ownership 4, stop hook 17 OK; validator and render check (44) OK; routing 153/153. Hook files are live before ratification |
| 14 | code-reviewer (fresh) | opus (override), effort high set on the call; the reviewer cannot see the applied effort | review-report-05.md (96 lines, over the 80 cap; kept verbatim) | REQUEST_CHANGES (about 01:55-02:17): hook suites 95, 4, 17 OK; T-1 to T-3 listed inputs closed; R5-1, R5-2, R5-3 CRITICAL and R5-4, T-4 MINOR open |

No further guard repair (human decision: round 4 is the last guard round in Phase 11).
R5-1 to R5-4 and T-4 go to the human gate. The review calls all five old behaviour, not
regressions against HEAD or round 3. Its report says R5-4 is new in round 4, so for R5-4
read it as: absent at HEAD and round 3, because the setting it concerns did not exist.

| Step | Agent | Model, effort | Report | Status |
|---|---|---|---|---|
| 15 | documentation-engineer (fresh) | sonnet, medium | step-docs-02-documentation-engineer-report.md | COMPLETE (about 02:17-02:22): STATUS.md covers rounds 3 and 4; it reports guard 95, stop hook 17, ownership 4, datasets Python 19, validator and render check OK; sbt and routing not run by it |

Phase 11 stops here for the human gate. Nothing was committed. As in the earlier rounds,
the orchestrator ran on claude-sonnet-5-5, not the high tier. The orchestrator did not
paste the agent reports into the main session (the human limited its reading to the
STATUS, TEST RESULTS and BLOCKED-ON lines); the files in this folder hold them in full.
The review 5 hand-back reached the orchestrator in full because the reviewer cannot
write files.
