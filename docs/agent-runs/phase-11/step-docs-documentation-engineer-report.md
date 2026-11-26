HARNESS: claude-code
MODEL: standard tier (sonnet, effort medium in .agents/models.json); runtime claude-sonnet-5-5

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 11 step 5: STATUS.md once, and MODULE.md notes where behaviour changed.
FILES CREATED: none in the repository (this report)
FILES MODIFIED (under /home/ajcreator/scholarchain):
- STATUS.md; PROJECT_MEMORY.md (two contradicted rows: guard hardening, R6-1 open)
- modules/{core,hadoop,spark,storage,rag,services}/MODULE.md (entity-resolution: no source diff)
COMMANDS RUN (all observed): git status, diff, log, ls-files; grep, sed, ls reads;
- .agents/render.sh --check: rc 0 (44 files)
- .claude/hooks/validate-agent-files.sh: rc 0 (19 roles, 736 files with one writer)
- timeout 9m python3 .claude/hooks/test_guard_dangerous.py: rc 0, 78 OK
- same form, test_verify_tests_ran.py rc 0, 17 OK; test_guard_ownership.py rc 0, 4 OK
- timeout 10m python3 -B -m unittest discover (tests/module-unit/datasets): rc 0, 19 OK
TEST RESULTS: no sbt run. 1103 passed, 0 failed, 1 ignored is quoted from test-report-03.md.
VALIDATION RESULTS:
- STATUS: "Resume here: Phase 11" at the top (state, gate, S-1 choices, ratification and
  probes from repair-02/01-factory, human decisions, next step, continue and verify
  commands, MUST table, CARRY, closed carries, residuals). Sources: run-log, carried-items,
  review-report-03, review-report-b, test-report-02/03, repair-01/02 reports.
- STATUS: claim-verifier-v2 line and old "Continue in a fresh session" marked superseded;
  "nothing committed" for phases 08b, 09, 10 corrected from git log.
- MODULE notes: core (domain report), hadoop (hadoop report), spark (spark and C20),
  storage (storage report), rag (rag, C19, rag-02), services (service 01/02).
  Cited names spot-checked by grep in the sources.
- A-2 and A-3 listed closed (test-report-02). K02, K03, K11 NIT-2 closed; K11 NIT-1 open.
- Added lines are at most 90 chars with no em dash, except the two edited single-line
  PROJECT_MEMORY table rows. Phase 11 record paths and new files named were checked to exist.
STATEMENTS IN OTHER DOCS NOW FALSE (not edited):
- docs/api.md:34: audience "(client_id or aud)"; now client_id for token_use access, aud
  for id, and other or missing token_use is refused.
- docs/api.md:257: "R6-1 keeps the model path off until Phase 11"; R6-1 is closed.
- docs/storage.md:101: "Succeeded is terminal"; recordReport can version-update it.
- docs/storage.md:315-316: a replay "does not move the ledger"; it does if the report differs.
- docs/storage.md:338-341: missing directory "still records Succeeded"; the stage now fails.
- docs/storage.md:354-356: ledger still names the first report; fixed (C17).
- docs/storage.md:370-371: loaders not run on JSON lines, test carried; fixture test exists.
- docs/hadoop-jobs.md:251: "LogicVersion is now hadoop-v2"; it is hadoop-v3.
- docs/spark-jobs.md:77: Hadoop pipeline is hadoop-v2; v2 or v3 is accepted.
- docs/spark-jobs.md:109: LogicVersion "spark-v2"; spark-v5 (already stale before Phase 11).
- docs/spark-jobs.md:249-250 and 520-521: no config bound on a contradicted cluster.
- Incomplete, not false: spark-jobs.md:204 and 472 (4 new report keys, dynamodb.json
  communities block); storage.md DynamoDB keys (community items); hadoop-jobs.md:149-153
  (primary researcher block now counted in OVERSIZE_BLOCKS).
- Also found, docs/runtime-workflow.md: :38, :41, :46 old component versions; :235-243 and
  :535 R6-1 open; :508-509 community size missing; :550 llm-synthesis-v1.
ASSUMPTIONS:
- "Recorded by the reports" behaviour is stated from them; not every claim was re-read in code.
- "Steps through review 3 done, nothing running" follows run-log and the work order.
RISKS:
- Not done: no sbt, cloud or commit; no edits to the other docs above.
- output-factory-diff-02.txt has no entry for the untracked test_verify_tests_ran.py
  (checked by its diff headers); STATUS says to ratify it too.
- Probe and continue commands in STATUS were not replayed (sbt, restart, new session).
- Older STATUS sections and older MODULE.md history (counts, versions) remain as history.
  modules/spark/MODULE.md "14 files" (15 on disk) predates this phase and was left.
- The guard blocked two of my read-only checks; I did not bypass it, and dropped the human
  gate path from both. Verbatim, both times: "Blocked by guard-dangerous hook: a shell write
  to human-gate.md (written only by the human). Agents never run this. If it is really
  needed, a human runs it manually; the approved paths are in docs/agents.md section 6."
  The first command had 2>/dev/null, the second ran inline python beside the path (K04 class
  false positive, factory-engineer). That Phase 01 link target is tracked (git ls-files).
- The aws-api MCP server reports it is entering end of development (migration guide:
  https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md); unused here.
MANUAL ACTION REQUIRED: human decides S-1, then gate, ratification, restart, probes, commit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human decision on S-1.
