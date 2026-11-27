HARNESS: claude-code
MODEL: sonnet (standard tier, per work order; session reports claude-sonnet-5-5); effort as set by the agent file
VERDICT: REQUEST_CHANGES (1 MAJOR, 1 MINOR, no CRITICAL)
(1) SIGPIPE ignored once armed, closed-reader tests: MET (K1-K5 and X-head pass).
(2) finish cleans up when early AWS calls fail: MET for single failures (K8-K11); MAJOR-1 can still skip the cleanup.
(3) Each service's scale-down retried independently: MET (K13-K15); see MINOR-1.
(4) Stop signal ends the timed child at once, then cleanup: NOT MET (one signal works; a repeated one can break it).
MAJOR-1 (infra-engineer), scripts/lib/common.sh:450-453, 482-485, 645: a second stop signal can make the script exit with NO cleanup.
- Signals are ignored only at line 645, inside sc_guard_exit. In failing runs a second TERM re-enters sc_guard_signal (two SIGNAL lines); nested exit as the cause is an inference.
- Input: `timeout --preserve-status -s TERM 3s bash run-bounded-pipeline.sh --env dev --finish <run>` during a slow history call (stubs); GNU timeout signals the script, then its group. Also two TERMs sent 1 ms apart.
- Result: exit 143, two SIGNAL lines, no STEP exit-guard, no cluster check or scale-down, no GUARD-FAILED. In the t-guard F run the slow stub call also finished afterwards (child not ended).
- Frequency: 4 of 15 (t-guard F 1/1, F repeated 2/6, direct double TERM 1/8). K6 and X passing does not rule out this race.
- Impact: ECS keeps billing and clusters go unchecked. AGENTS.md runs long commands under `timeout`. The experiment shares this code, so its entrypoint could keep running (inferred, not run). Double INT or HUP not tested.
- Repair (not run): ignore INT, TERM and HUP as the very first action of each signal trap, and prove it with a repeated double-signal and outer-timeout loop; one pass proves nothing.
MINOR-1 (infra-engineer), common.sh:617-621: the cluster-name SSM read is tried once. Input: one throttle or timeout on that get-parameter. All three scale-downs are skipped (exit nonzero, GUARD-FAILED printed). sc_guard_identity (657) is also single-attempt when it is needed. Found by reading; not run.
No other finding in what was checked: dry runs return before arming (finish dry run and usage made 0 stub calls); K8, K9 and K12 keep the expected exit codes; the entrypoint gets default SIGPIPE; an unrelated process survives. Body stdin is /dev/null and no needed prompt was seen (an MFA-prompting profile not considered further). The guard re-checks identity, and filter, terminate, disarm and success pass through the state file. Single INT, TERM and HUP pass, to the script and the group. deploy.sh does not arm the guard; cleanup.sh (read only) uses sc_aws, sc_tf and sc_die, not the guard. The two accepted choices were not re-litigated.
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 13 final check, items (1) to (4) only
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: reads of reports and scripts; git status; bash -n (11 scripts); shellcheck -x -P scripts (same 11); t-trap, t-guard, t-redact, t-creds (stubs, under timeout, foreground); F and double-TERM repro loops (output in the session scratchpad only). guard-dangerous blocked 3 commands: a grep naming the cleanup script (used Read), a `bash -n "$f"` loop (rerun with literal paths) and a /proc environ probe (dropped, not worked around). No AWS, sbt or cleanup run.
TEST RESULTS: bash -n OK; shellcheck rc 0. t-trap 29/29, redaction 16/16, credentials 30/30, guard 27/28: FAIL "F SIGNAL line and guard ran (3 services scaled)". The author's 103/103 did not reproduce.
VALIDATION RESULTS: REQUEST_CHANGES; findings above
ASSUMPTIONS: the scratchpad copy tested matches the repo (cmp: common.sh, both run scripts, package.sh, EMR helper, plan_summary.py). Stubs stand in for AWS.
RISKS: not reviewed: anything outside the four items, Terraform, real AWS/EMR, a real terminal, a real run-experiment.sh end to end; cleanup.sh had no bash -n or shellcheck. Unverified, from reading only: the token is exported after the body forks (common.sh:464), so /proc environ may not list the body shell itself. If a signal to the script alone lands when no exec'd child exists, line 524 could then wait for the whole body before cleaning up.
MANUAL ACTION REQUIRED: no repair round remains, so MAJOR-1 and MINOR-1 go to the human. Until fixed: do not wrap these two scripts in an outer `timeout` without accounting for this, do not send repeated stop signals, and after any interrupted run check EMR and ECS by hand (or rerun --finish for a pipeline run). Also tell the user: the aws-api MCP server is entering end of development; migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human decides on MAJOR-1 and MINOR-1 (owner infra-engineer).
