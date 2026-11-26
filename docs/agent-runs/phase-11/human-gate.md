# Phase 11 human gate

## How Phase 11 was produced

The owning agents did the work through the orchestrator, one owner at a time. The work
order and my addendum are in `prompt.md`. Every step is in `run-log.md`, and every report is
saved in this folder (the orchestrator copied in reports from agents that cannot write here).
`carried-items.md` lists every item the earlier gate notes sent to this phase, classed MUST
(repaired here) or CARRY (open for Phase 17's limitations section). As in earlier phases, I
gave the agents reference material from the collaborative ScholarChain repository, unpacked
outside this repository in `~/ref-phase-11/`, for test ideas only. No cloud was used, and the
AWS integration suites are NOT RUN because no AWS profile is approved.

## Testing, reviews and product repairs

- test-engineer mapped the existing tests to the work order's categories
  (`coverage-before.md`) and added adversarial tests where a category or behavior had none.
  Five new tests were red; the reviewers judged all five fair (J04, R6-1 three times, and
  the JWT `token_use` check).
- Review A covered core, hadoop, entity-resolution, spark and datasets. It went back once to
  finish its half and still lists areas it did not read (`review-report-a.md`). Review B
  covered storage, rag, services, tests, the control plane and docs, and found B-1
  (CRITICAL): `.claude/settings.json` auto-approved any sbt command, and `sbt eval` could run
  arbitrary programs past the guard.
- Repair round 1, by owner (`repair-plan.md`, `repair-01-*.md`): domain-engineer added the
  config bound on contradicted identity groups (C12); spark-engineer enforces it, exports
  community size in the JSON-lines export (C14) and accepts both `hadoop-v2` and `hadoop-v3`
  runs (C20, found when hadoop-engineer's repair bumped the Hadoop logic version);
  storage-engineer added the Neptune and DynamoDB loader contract test on the JSON-lines
  export (C13), loads community size and fixed C17; rag-engineer fixed R6-1 (filtered rank
  figures), R6-2, R6-3, reads community size, and fixed internal error text reaching answers
  (C19, found by service-engineer); service-engineer added the `token_use` check (C15) and
  removed downstream error text from explain (C16); hadoop-engineer fixed A-1 and C18.
- After round 1 the full `sbt check` passed (1092 tests). Review 2 found R-1 to R-4 in the
  guard and two C19 behaviors without tests. Round 2 added those tests and closed R-1 to R-3.
  The final full `sbt check` passed: 1103 passed, 0 failed, 1 ignored (the gated AWS suite).
  No Scala or test file changed after that.

## The command guard (factory-engineer)

- Round 1 fixed B-1 and the guard and hook items C01 to C08: the raw-command fallback scan
  (R3-CRITICAL-1), shell writes to and deletions of `datasets/gold/`, the false block on text
  naming the test marker, self-matching polling loops, and the stop hook asking the
  orchestrator for sbt check while a subagent's sbt run is in progress.
- Reviews 3, 4 and 5 each found new ways around the guard's shell parsing (S-1, then T-1 to
  T-3, then R5-1 to R5-3). I authorized rounds 3 and 4 as disclosed exceptions to the
  two-round limit. Round 3 also blocked shell writes to `~/.sbt/`, `~/.ivy2/` and `project/`,
  closing the planted-code route around B-1. Round 4 made the guard fail closed: every
  heredoc body is scanned, unparseable constructs are blocked, and the guard blocks on its own
  6-second deadline.
- After round 4: guard tests 95 of 95, ownership 4 of 4, stop hook 17 of 17, routing 153 of
  153, and the validator and render check pass. Review 5 (`review-report-05.md`) found
  nothing that round 3 blocked now passes, and 66 of 67 ordinary read-only and routine
  commands are allowed (HEAD also blocks the other one).
- Still open: R5-1 (commands split with a backslash), R5-2 (command names built with braces,
  wildcards or variables) and R5-3 (`compgen -W` running code), all CRITICAL; R5-4 (a zero
  deadline disables the guard's timer) and T-4 (two ways to write to protected build
  folders), both MINOR. Each needs a deliberately disguised command.
- Review 5 also observed that Claude Code runs a command when a hook times out (seen on
  2.1.332 at a 2-second timeout); the guard's own 6-second deadline blocks first.

## Decisions and ratification

- I accept the guard as installed and record R5-1 to R5-4 and T-4 as open. Reverting to HEAD
  would not close R5-1 to R5-3 and would bring back B-1, S-1 and the other repaired holes.
  The guard is one layer: permission prompts and the deny rules in `settings.json` are the
  others. Review 5 found that R5-1 to R5-3 commands still reach a permission prompt.
  Auto mode stays off for the rest of the project, and I check unusual-looking commands
  before approving them.
- Gate item not met: "no critical or major verified finding remains" (R5-1 to R5-3). The
  other gate items are met: every production behavior has a relevant test, all tests pass
  under their declared profiles, no reviewer wrote production code, and the repair records
  name owners and rerun evidence.
- I reviewed the control-plane diffs (`output-factory-diff*.txt`) and the hook test files,
  and I ratify them, including the change that puts code-reviewer on the standard tier for
  Phases 12 to 16. factory-engineer objected to that change; I keep it because those phases
  rely heavily on `terraform validate`, plans, scans and script checks. Before Phase 17,
  factory-engineer restores code-reviewer to the high tier, model and effort both, and
  re-renders its files.
- After restarting Claude Code, I ran the three safe probes in a fresh session:
  - `echo 'terraform destroy'`: blocked
  - reading `.env`: blocked
  - a recursive S3 delete: blocked

## Process departures

- The orchestrator reused agents in a few places: the final test rerun (the same
  test-engineer), review A finishing its half, and rag-engineer and spark-engineer for late
  fixes.
- The reviews did not read all the code; each report lists what it skipped.
- The orchestrator ran on Sonnet 5.5, not the high tier the matrix sets, to save usage.
- Review A's report is 146 lines and review 5's is 96, over the caps I set.
- Older benchmark suites read the 25,000-work export whenever it exists; my rule against
  using that export applied to new tests only.
- The guard still blocks some harmless commands (review 5's minor items); agents write
  reports with the Write tool.

## Accepted, with known gaps

Phase 11 is accepted as committed. Carried forward:
- R5-1 to R5-4 and T-4 (factory-engineer, after the deadline; listed in the final report's
  limitations).
- Every CARRY item in `carried-items.md`, and the minor findings in the review reports, for
  Phase 17's limitations section.
- The aws-api MCP server is reaching end of development, and the github MCP server's
  authorization header is badly formatted (factory-engineer, Phase 12).

## Manual changes

- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 11 is complete. I approve starting Phase 12.
