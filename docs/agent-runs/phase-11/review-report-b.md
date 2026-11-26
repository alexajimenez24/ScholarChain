HARNESS: claude-code
MODEL: opus 5.5, effort high (code-reviewer tier high, `.agents/models.json`)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 step 2, Review B (storage, rag, services, prompts, tests, control plane, docs; full diff of that half). Read-only. I did not use review-report-a.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git status / git diff; read-only grep and sed over the files listed below; `python3 -I` probe scripts in the session scratchpad (outside the repo) that send inert JSON payloads to .claude/hooks/guard-dangerous.sh (about 90 probes, none of them executed); verify-tests-ran.sh with 4 JSON payloads; `.agents/render.sh --check` (rc 0); `.claude/hooks/validate-agent-files.sh` (OK: 19 roles, 708 files with one writer). I did not run sbt, as the work order says.
TEST RESULTS: none run by me. The red tests in test-report.md are confirmed by reading the code (below).
VALIDATION RESULTS: REQUEST_CHANGES
Findings (ID, severity, MUST/CARRY, location, failing input, impact, owner):
- B-1 CRITICAL, MUST. Location: .claude/settings.json:5 (allow rule `Bash(sbt *)`); guard-dangerous.sh:378 (INTERP has no sbt) and :683. Failing input: `sbt 'eval scala.sys.process.Process(Seq("terraform","apply")).!'` gives rc 0. So do the same form with `Seq("rm","-rf","datasets/gold")`, a `.env` read with the name split into pieces, and `setLastModified` on the marker path split into pieces. Impact: the allow rule auto-approves these commands and the guard passes them, so nothing prompts. Any sbt-holding agent, or injected text, can run terraform apply, delete the gold set, read secrets or forge the test marker. This is a floor gap and must close before any AWS credentials exist. Owner: factory-engineer. Repair: replace `Bash(sbt *)` with an explicit list (check, fmt, packageJobs, `<p>/test`, `<p>/testOnly`, compile, scalafmtCheck). In the guard, block sbt/sbtn arguments `eval`, `console*`, `set`, `run`, `runMain`, `reload plugins`, `alias`, `addCommandAlias`. Add sbt, scala, scala-cli, amm and jshell to INTERP. Keep green: every `sbt check` and `timeout 45m sbt check` form.
- B-2 = C01 CRITICAL, MUST, still open. Location: guard-dangerous.sh, the secret scan's raw-command fallback. Failing input: ``cat .env #'`` and `cat .env # don't print` give rc 0; the plain form gives rc 2. Repair brief from Phase 01 review-report-02 still applies: branch on an empty scan output before `read <<<`, fail closed on globs, add every listed input as a test. Owner: factory-engineer.
- B-3 = C02 MAJOR, MUST, open. Failing input: `bash -c 'cat .e*'`, `eval 'cat .e*'`, `find . -maxdepth 1 -name '.e*' -exec cat {} +` all give rc 0 (the top-level `cat .e*` gives rc 2). Repair: run the glob analysis on `-c`/eval strings and on find `-name`/`-path` values. Owner: factory-engineer.
- B-4 = C03 MAJOR, MUST before cloud. Phase 01 MAJOR-4 is still open. rc 0 for:
  - `ter\raform apply #'`
  - `git push --mirr origin #'`
  - `git -c remote.origin.push=+refs/heads/main:refs/heads/main push origin`
  - `t=terraform; $t apply`
  - `$(echo terraform) apply`
  Now blocked: `echo '... apply' | bash` and a heredoc fed to bash (the raw pattern catches both). No deny rule covers the open forms: the deny list has no `--mirror` rule and no `-c ...push=+` rule, and variable forms escape prefix matching. A human prompt still stands because these are not auto-allowed. MAJOR, not CRITICAL. Repair: retry the parse with an unquoted `#...` stripped, otherwise fail closed on terraform/tofu/push tokens; treat `-c remote.*.push=+` and `--mirror` prefixes as force; block a command word that is `$var` or `$(...)` followed by apply/destroy. Owner: factory-engineer.
- B-5 = C04, MINOR, MUST by the addendum. Failing input: `rm -rf datasets`, `mv datasets /tmp/ds`, `find datasets -name '*.csv' -delete`, and `python3 -c "...rmtree('datasets/'+'gold')"` all give rc 0. Direct writes (redirection, cp, sed -i, perl, dd, rsync, git rm/checkout, globs, `cd datasets && rm -rf gold`) give rc 2. Impact: datasets/gold/er-gold-v1.csv is tracked, so HEAD can recover it, but uncommitted label work could be lost. docs/agents.md section 1 overclaims ("guard-dangerous blocks shell writes"). Repair: treat a write/move/delete on any ancestor of datasets/gold (datasets, ., ..) as a gold write; check find `-delete` on ancestors; fail closed for interpreter code that names both "gold" and "datasets"; correct the docs/agents.md wording. Owner: factory-engineer.
- B-6 = C05, MUST. Location: guard-dangerous.sh:666-704. False blocks I observed:
  - (a) A heredoc body line naming the marker path is read as a command: `cat > /tmp/r.md <<'EOF'` / `marker .claude/.last-test-run is not refreshed` / `EOF` is blocked as "marker in a command".
  - (b) An sbt segment anywhere plus the task name anywhere: `timeout 45m sbt check; grep -c <task> build.sbt` is blocked.
  - (c) Any non-READ command beside the marker path: `bash x.sh` with `ls` of the marker in the same command (my own probe), `sed -n 1,5p` of the marker, `cp` from it.
  grep, echo and git log naming the task without sbt now pass.
  Repair: skip heredoc bodies unless they feed a shell or interpreter (as the gold check does); require the task name as an argument of the sbt segment itself; allow `sed` without -i and `cp` with the marker as source. Must stay blocked: redirection into the marker; touch/cp/mv/ln onto it; `sbt <task>`; `sbt "check; <task>"`; sbtn; a heredoc fed to a shell that names the marker; a parse failure. Owner: factory-engineer.
- B-7 = C06, MUST. `while pgrep -f sbt; do sleep 5; done` and `until ! pgrep java ...; do sleep 10; done` give rc 0. Repair: block while/until loops whose body has sleep, and any pgrep/pidof/`ps|grep` inside a loop; a bare `pgrep -f sbt` stays allowed. Owner: factory-engineer.
- B-8 = C07, MUST. AGENTS.md, docs/agents.md and .agents/roles/* have no rule that long sbt or pipeline commands run under `timeout`. I grepped all three; only deploy-engineer has a "bounded timeout". Repair: add the rule (project tests 30m, sbt check 45m, foreground) to AGENTS.md Commands and the work-order skills, then re-render. Owner: factory-engineer.
- B-9 = C08, MUST. Observed: Stop rc 2 and SubagentStop rc 2 (marker stale: 5 red tests), `stop_hook_active` rc 0, a non-JSON payload rc 2 (fails closed, good). Repair: exit 0 only when all three hold:
  - `hook_event_name == "Stop"` (main session);
  - a live sbt/java process has /proc/<pid>/cwd equal to the repo root (not pgrep -f text, which matches itself);
  - that process is younger than 45 min.
  Keep unchanged: SubagentStop, the stale/missing-marker refusal when no sbt runs, the `stop_hook_active` check, the non-JSON refusal. Tests: these 4 payloads plus a live-process case. Note: the hook also asks read-only reviewers to run sbt check. Owner: factory-engineer.
- B-10 = C09 (R6-1) MAJOR, MUST. Location: modules/rag/src/main/scala/scholarchain/rag/Verification.scala:290-297 (ranking rank `Held`) and :118 (`statement`); the same label goes into model prose at :475. Failing input: a topic- or institution-filtered ranking figure `(Alice, rank, 1, [rankingId])` comes out `VERIFIED "rank of Alice Arden (...) by distinctCoauthorCount in window 2015-2026: 1."`. Agents.scala:570-605 shows that values are global and the filter only restricts membership, so value figures are true and rank figures are false. Owner: rag-engineer.
- B-11 = C15 MINOR, MUST by the addendum. Location: modules/services/common/src/main/scala/scholarchain/services/common/Auth.scala:157-180, where `token_use` is never read. Failing input: refresh, bogus or missing `token_use` is accepted. Owner: service-engineer.
- B-12 = C16 MINOR, MUST by the addendum. Location: QueryService.scala:300-301 (`synthesisAttempts`, `synthesisFallback`). The values come from Synthesis.scala:782-791 (`err.take(200)`) and Llm.scala:94 (transport text `e.take(160)`). Owner: service-engineer. Repair: explain shows only the attempt status code and a fixed fallback category. Keep green: QueryServiceSuite explain tests and RuntimeEdgeSuite malformed-reply test.
- B-13 = C17 MAJOR, MUST before the cloud load. Location: modules/storage/src/main/scala/scholarchain/storage/Loaders.scala:182, where a replay returns `cur.value` and the Succeeded ledger keeps the first report's URI and SHA. Failing input: a replay that repairs rejections writes a new report the ledger never names (provenance lost). Second case at :98: a missing directory is one rejected row and the stage records Succeeded unless expectedRows is set (K11 NIT-2: `expectedRows = 1` is met). Repair: on replay with a different report SHA, record the new report on the ledger (conditional update or new attempt); make a missing input directory fail the stage (Left before `succeed`). Keep green: LoaderSuite :525, :591, :616, :628 (update the two missing-directory tests to expect failure). Owner: storage-engineer.
- B-14 = C13 MAJOR (gate: production behavior without a relevant test), MUST. Location: tests/module-unit/storage/LoaderSuite.scala. No test runs ResearcherMetricsLoader, NeptuneExport.build or NeptuneLoadJob on rows from the Spark JSON-lines layout (`<run>/jsonl/<table>/` with `maximumTeamWorkCount`, `inLargestComponent`, `windowLabel` and `docType` dropped; Export.scala:79,89-91). Repair: a small hand-written fixture in that layout, decoded with `jsonLinesDir(dropFields)`, through both loaders; assert counts, no rejects, and the strict-decode reject of an undropped extra field. Owner: storage-engineer.
- B-15 = C14, MUST by the addendum. Location: Agents.scala:916 and :939 ("community size ... not available"). Export.scala:89-91 leaves `communities` out of the JSON-lines tables. No core change is needed: the community row is already a core EvidenceItem COMMUNITY_RECORD with `communitySize` (Export.scala:548). Plan:
  - spark-engineer adds `communities` to JsonLinesExport, with a test;
  - storage-engineer stores it with a fixture test, either under a distinct key in the researcher-metric table or in a new table (a new table needs a StorageConfig field from domain-engineer plus Phase 12 Terraform);
  - rag-engineer reads it in CommunityAnalystAgent and drops the missing-field note, with a fixture test.
- B-16 = C11 (rag part) MINOR, MUST by the addendum. Location: Synthesis.scala:715 and :839, where `LlmSynthesizer.Version = "llm-synthesis-v1"` although the output schema is v2. Repair: bump to v2. The audit `synthesizer` and componentVersions values change; tests use the constant symbolically. STATUS.md:209 still says claim-verifier-v2 (documentation-engineer, step 5). Owner: rag-engineer.
- B-17 MINOR, CARRY. Location: Auth.scala:144-145. A JWKS fetch failure maps to UnknownKey, so the caller gets 401 "invalid token" instead of 503 dependency_failed. Owner: service-engineer.
- B-18 MINOR, CARRY (extends K02). `cat /proc/self/environ`, `export -p`, `declare -x` and `/usr/bin/env` print the whole environment (rc 0); none is auto-allowed. Fold into the B-2 repair (same file). Owner: factory-engineer.
- B-19 MINOR, CARRY, fix with B-11. Location: tests/module-unit/services/common/CommonSuite.scala:58. "an ID token ... is accepted" mints `token_use` "access", so it tests no ID token. Owner: service-engineer.
- B-20 NIT, CARRY. Location: RuntimeEdgeSuite.scala:197-199. The Neptune-timeout test passes on any Left; the Right branch was never seen. Fair by the spec. Owner: test-engineer.
- B-21 MINOR, CARRY (Phase 12). Location: settings.json:16-19, which auto-allows `terraform plan*`. A Terraform `data "external"` block runs programs at plan time. infra/ is empty, so the risk is latent. Owner: factory-engineer (rule) and infra-engineer.

Fairness verdicts:
- FilteredRankGateSuite R6-1 topic, institution and bridge: FAIR (spec stage 9 "check every numerical value"; gate "removed, weakened, or labeled"). The unfiltered control and the deterministic path pass, so the tests are not vacuous, and both repair options are allowed.
  Repair brief: likely cause B-10. Fix: when the filter does not start with "none", put "among <UntrustedText.bound(filter,120)>" in both the label and the `about` text of the rank `Held`, the same way Synthesis.scala:352-354 does; or refuse rank figures on filtered rankings.
  What else could break: exact structured-text expectations in rag VerificationSuite, the WorkflowGate suites (R3, R4, D5) and BenchmarkWorkflowSuite (unchanged if "none" filters are excluded); `Validate.answer` text length.
  Must stay green: FilteredRankGateSuite control, outsider and deterministic tests, the 5 Workflow*Gate suites, rag module-unit. Owner: rag-engineer.
- JwtClaimsSuite token_use: FAIR. docs/api.md:50 says "Bearer <access token>"; the addendum names C15 MUST; Cognito always issues `token_use`.
  Repair brief: add `AuthFailure.WrongTokenUse`. Either require "access", or tie the value to the audience claim (access with client_id, id with aud). Refresh, bogus and missing are refused either way.
  What else could break: CommonSuite:58 (B-19); every TestJwt already sets "access". Keep green: JwtClaimsSuite (7), CommonSuite, QueryServiceSuite, GraphServiceSuite. Owner: service-engineer.
- ReducerWiringSuite J04 belongs to Review A; I give no verdict.

C10 judgment: the justification is correct. Spec lines 1571, 2599 and 2832 allow "removed, weakened, or explicitly labeled". Under v3 the number is `claim-2-figure-1`, which must be CONFLICTING (asserted). The assertion is strong enough: the prose must be removed or kept PARTIALLY_SUPPORTED with the label, never showing 999, and `onlyLabeled(999)` plus the researcher-row check cover leaks. The one residual: a removed claim-2 is checked for "was checked" but its removal status is not pinned. That is acceptable.

Phase 09 R4-1 to R4-3: closed by construction.
- Model prose is never VERIFIED (Verification.scala:701-705; Workflow.scala:258 labels it).
- Only structured figures can be VERIFIED, with text written from the evidence item (:381-407).
- A ranking backs only the researcher at its position (:287-310).
- The summary is free text and labeled (Workflow.scala:296).
- Only R6-1 (B-10) remains.

CARRY items in my half: K01-K04 (factory-engineer) stay CARRY, but fold them in if B-2 to B-6 touch the same lines. K05 and K06 (build) CARRY. K11 closes with B-13. K13, K14 and K15 CARRY. K12 is Review A's half.

ASSUMPTIONS:
- The Claude Code allow rule `Bash(sbt *)` matches a single quoted `sbt 'eval ...'` with no shell operators. This is a reading of the rule; I executed none of it.
- `git push --forc` may be ambiguous to git, so I cite only the unambiguous forms in B-4.
- I treat MUST items assigned by the addendum as MUST whatever their severity.

RISKS (not reviewed; deliberate residuals):
- storage: VectorLoader, LocalIngestion, NeptuneExport, NeptuneAdapters, DynamoDbStore, S3ObjectStore, VectorStore, Embedder.
- rag: Plan, most of Evidence, SemanticContext, Chunking, Directory, TemplateGraph, Templates, LocalRuntime, most of Agents and Synthesis; prompts/ not read.
- services: graph, worker, QueryRoutes, Resilience and Emf not read in full.
- tests: none outside the Phase 11 diff and its helpers.
- control plane: guard-dangerous.sh read in about 200 of 1060 lines (otherwise checked by probes); test_guard_*.py, render.sh, routing suite, roles, skills and .codex not read.
- docs: only api.md auth and grep checks.
- build files: not read.
- infra/ and scripts/ are empty directories.
MANUAL ACTION REQUIRED: none. B-1 may justify keeping auto mode off until the factory-engineer repair is ratified; that is the human's choice.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the orchestrator writes repair-plan.md. Route each finding to its owner:
- rag-engineer: B-10, B-15 (rag part), B-16
- service-engineer: B-11, B-12, B-19
- storage-engineer: B-13, B-14, B-15 (storage part)
- spark-engineer: B-15 (export part)
- factory-engineer, last: B-1 to B-9, with probes and ratification
Then test-engineer writes test-report-02.md and a fresh reviewer writes review-report-02.md.
