# Phase 05: every agent report in full, in run order

Assembled by the orchestrator from the saved reports in this folder; each section is the file verbatim.

---

## step-00-documentation-engineer-report.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 step 00. Add a "Phase 05 task list" to STATUS.md and update "Where things stand" and the current state, with step 01 as next.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN: `git log --oneline -3` (exit 0), `git status --short` (exit 0), `ls docs/agent-runs/phase-05/`.
TEST RESULTS: none run; this is a docs-only change.
VALIDATION RESULTS:
- `git log --oneline -3` printed 4388607 "phase-04: record accepted commit", a43fa55 "phase-04 hadoop-engineer: DBLP parser, source normalizers, MapReduce jobs J01-J07" and 524dc1d. This is recorded in STATUS as observed.
- `git status --short` showed only `?? docs/agent-runs/phase-05/`. That directory held only prompt.md.
- Where things stand: the Phase 04 bullet now says committed, and a Phase 05 bullet says it is starting.
- New "Phase 05 task list" section: 10 rows, all pending, with the phase rules (no leakage, human-only labels, machine limits).
- The Phase 04 "Current state" block is now "Current state (phase 05)", with Next set to step 01 build-engineer. The old block is kept as "Previous state (phase 04, closed)".
- I did not re-run the line-length check after the edit. Table rows in the new task list exceed 90 characters, as the existing tables already do. Prose lines were written at 90 or fewer.
ASSUMPTIONS:
- I kept the Phase 04 carry-forward items N1 to N7 as open, because no report in my inputs says they were closed.
- I added a note that N4 (candidate provenance version) should be decided before step 03 consumes candidates. That follows the Phase 04 review text and is not a new finding.
- I did not copy the Phase 04 gate disclosures or open decisions. They are unchanged in the file.
RISKS:
- The "Git" line reflects the state at the start of this task only. It is not a live snapshot.
- I did not check whether `~/ref-phase-05/` exists.
- The Phase 04 "Current state" content is retained under a "Previous state" heading, so STATUS.md is longer.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 01, build-engineer (entityResolution Hadoop dependencies and forked test JVM at -Xmx1g).

---

## step-01-build-engineer-report.md

HARNESS: claude-code
MODEL: standard tier (sonnet), as set in the work order

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: phase 05, step 01. Give the entityResolution project the same Hadoop dependency shape as hadoop.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/build.sbt. Only the entityResolution project stanza changed, plus a 3-line comment above it.
- Added hadoop-client-api % Provided, hadoop-client-runtime % Test and jcl-over-slf4j % Test.
- Added `Test / fork := true` and `Test / javaOptions += "-Xmx1g"`.
- All versions come from the existing V.hadoop and V.slf4j. project/Versions.scala is unchanged.
- I repeated the stanza instead of factoring a shared helper. It is 5 lines, and repeating it leaves the hadoop project's effective settings untouched. The reference's `hadoopTestRuntime` and `forkedHadoopTests` helpers do not exist in this build, so I did not use them.
COMMANDS RUN: one sbt JVM, run in a single foreground invocation (it ran past 180s, so the harness moved it to the background): `sbt check "show entityResolution/Test/fork" "show entityResolution/Test/javaOptions" "show entityResolution/libraryDependencies" "show entityResolution/Test/fullClasspath"`
TEST RESULTS: `sbt check` passed on the first attempt.
- `[info] Passed: Total 68, Failed 0, Errors 0, Passed 68` (the last test run in the output).
- `[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `[success] Total time: 175 s`
VALIDATION RESULTS: verbatim lines from the same sbt invocation.
- `show entityResolution/Test/fork` printed `[info] true`.
- `show entityResolution/Test/javaOptions` printed `[info] * -Xmx1g`.
- `show entityResolution/libraryDependencies` printed:
  - `org.scala-lang:scala3-library:3.3.9`
  - `org.scalameta:munit:1.3.6:test`
  - `org.apache.hadoop:hadoop-client-api:3.4.1:provided`
  - `org.apache.hadoop:hadoop-client-runtime:3.4.1:test`
  - `org.slf4j:jcl-over-slf4j:2.0.20:test`
- `show entityResolution/Test/fullClasspath` includes hadoop-client-api-3.4.1.jar, hadoop-client-runtime-3.4.1.jar, jcl-over-slf4j-2.0.20.jar and commons-logging-1.2.jar.
- Provided scope puts hadoop-client-api on the Compile and Test classpaths. I did not print `entityResolution/Compile/fullClasspath` separately.
- Module dependency graph is unchanged: entityResolution depends only on core.
- No new environment inputs and no new plugins.
- Version ledger is unchanged. The values relevant here are V.hadoop 3.4.1 and V.slf4j 2.0.20.
- Scala 3 compatibility: the Hadoop artifacts are plain Java, so there is no cross-version risk.
ASSUMPTIONS: none beyond the work order.
RISKS:
- entityResolution has no Hadoop-using source or tests yet, so the new stanza is only checked by classpath inspection. A local MapReduce run in this project is not exercised.
- The state left behind is a modified build.sbt, uncommitted. I changed nothing else.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the build.sbt diff, then the next phase 05 task.

---
Orchestrator note (observed): `git diff build.sbt` matches the report (12 added lines in the
entityResolution stanza only). The sbt run exceeded the 180 s foreground window and the
harness moved it to the background, which the machine limits forbid; it finished, and no
sbt/java process was left running at 09:07 (`pgrep`). Later sbt work orders run long
commands with an explicit larger timeout.
Also observed: the orchestrator's first attempt to save this report through a shell heredoc
was blocked by guard-dangerous because the text quotes the sbt task name in the line
above; the report was then saved with the file tool. Recorded as a Phase 11 guard item
(false positive on a literal task name in report text).

---

## step-01s-documentation-engineer-report.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier default; effort per .agents/models.json not re-read)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 STATUS update after step 01. Edit only STATUS.md.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN:
- ls docs/agent-runs/phase-05/ (listed prompt.md and step-00-documentation-engineer-report.md)
- git diff --stat -- build.sbt (12 insertions)
- git status --short (M STATUS.md, M build.sbt, ?? docs/agent-runs/phase-05/)
- a python3 heredoc that applied six exact-match string replacements (each asserted unique)
- git diff -U0 STATUS.md piped to awk for lines over 90 characters
TEST RESULTS: none run. I did not run sbt, as instructed.
VALIDATION RESULTS:
- STATUS.md edits made:
  - "Where things stand": Phase 05 line now reads in progress, step 01 done, step 02 running.
  - Phase 05 task list: step 01 is "done, see note step 01"; step 02 (T1) is "running, started 09:07".
  - New "Note step 01": sbt check green with Total 68 passed and 14 project test runs.
    It also records that the run exceeded the 180 s window and the harness backgrounded it, that background shells are forbidden by the machine limits, that it completed with no sbt left running, and that later sbt work orders set an explicit 600 s timeout.
  - New "Note step 02 (T1)": started 09:07, past the human's 09:00 labeling start.
  - "Current state (phase 05)":
    - Tasks done: step 01.
    - Task running: step 02, started 09:07.
    - Next: step 02 completes, then step 03 (T2) while the human labels.
    - Half done: nothing; step 02 is running.
    - Git line refreshed from git status.
- Line-length check: the only added lines over 90 characters are table rows, which was already the case in this file.
- The em dash was not used.
ASSUMPTIONS: The step 01 facts (68 passed, 14 project test runs, the backgrounding, the 600 s rule) come from your work order text, because the report file was not on disk.
RISKS:
- /home/ajcreator/scholarchain/docs/agent-runs/phase-05/step-01-build-engineer-report.md does not exist.
  I could not read the report or the orchestrator note at its end.
  Neither the 68 passed and 14 runs figures nor the backgrounding details are verified against it.
  The STATUS note says so.
  The orchestrator should save the report; if its numbers differ, STATUS.md needs a correction.
- The task list table still names the report file as step 01's report, though it is absent.
- I did not replay sbt, per the work order.
MANUAL ACTION REQUIRED: none for the human. The orchestrator should save the step 01 report.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 02 (T1, entity-resolution-engineer) completes; then step 03 (T2) while the human labels.

---
Orchestrator note: the step 01 report was saved right after this agent read the folder (the
first shell save was blocked by guard-dangerous). Its numbers match the ones in STATUS.md
(Total 68 passed, 14 project test runs). The next STATUS update removes the "unverified"
caveat.

---

## step-02-entity-resolution-engineer-report.md

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier (the entity-resolution-engineer default)

The review queue is built and checked. It has 280 rows: 205 researcher pairs and 75 work pairs. `sbt check` exited 0. The sample has very few likely non-matches: 4 researcher and 4 work pairs, and no borderline work pairs. If the human labels this file, the gold set will be almost all matches. Before 09:00 the human should decide whether to label it as is or wait for a larger sample (see RISKS 1).

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05, step 02, task T1. Implement the review queue in modules/entity-resolution, generate it from this repository's Phase 04 local run on datasets/samples, and write it to target/er-review/er-review-v1.csv. The orchestrator saves this report as step-02-entity-resolution-engineer-report.md.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewQueue.scala: the pure queue builder. It holds:
  - `ReviewQueueConfig` and its loader and validator;
  - `CandidateView`, a strict reader for match-candidate-v1 lines that does not import the hadoop module, with a merge by candidate ID;
  - the internal `Stratum` type, the similarity used only to pick strata, the balanced `allocate`, and a seeded `select`;
  - `ReviewSide`, `ReviewRow` and `RecordIndex`, the side builders, and the RFC 4180 CSV writer.
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewQueueMain.scala: the entry point. It reads one run's folders and writes the CSV and a provenance sidecar, both atomically. It refuses any path under datasets/gold, and refuses to overwrite an existing output unless given `--overwrite true`.
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/resources/scholarchain/er/review-queue-v1.json: the versioned config. It holds the queue and strata versions, seed, target and minimum rows, stratum cutoffs, identifier match and conflict feature lists, feature weights and list caps. No cutoff is a code literal.
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/ReviewQueueSuite.scala: 15 tests.
- Git-ignored outputs (`git check-ignore -v` shows `.gitignore:24:target/` for each):
  - /home/ajcreator/scholarchain/target/er-review/er-review-v1.csv
  - /home/ajcreator/scholarchain/target/er-review/er-review-v1.csv.provenance.json
  - /home/ajcreator/scholarchain/target/er-source-run/ (the Phase 04 run output)

FILES MODIFIED: none. modules/hadoop, modules/core, build.sbt and datasets/** are untouched.

COMMANDS RUN (all with `source toolchain/env.sh`, one sbt process at a time):
1. Ran the Phase 04 pipeline on the real samples: `sbt "hadoop/Test/runMain scholarchain.hadoop.LocalRunner --dblp /home/ajcreator/scholarchain/datasets/samples/real/dblp-sample.xml --openalex /home/ajcreator/scholarchain/datasets/samples/real/openalex-works.jsonl --crossref /home/ajcreator/scholarchain/datasets/samples/real/crossref-works.jsonl --output /home/ajcreator/scholarchain/target/er-source-run --snapshot 2026-11-22"`. Ended with `[success] Total time: 23 s`.
2. A first `sbt fmt ...` run failed: a `*/` inside a doc comment closed the comment early. I fixed the comment.
3. `sbt fmt "entityResolution/test" "entityResolution/runMain scholarchain.er.review.ReviewQueueMain --run-root /home/ajcreator/scholarchain/target/er-source-run --out /home/ajcreator/scholarchain/target/er-review/er-review-v1.csv"`. All three commands reported success. This is the command that produced the file.
4. Python stdlib `csv` checks of the output file (results under VALIDATION RESULTS) and `git check-ignore -v`.
5. `sbt check`, once, exit 0. Two process notes:
   - The harness moved it to the background after 180 s even though I set a 600000 ms timeout. I started no other sbt and did not poll; I read its output once, after the completion notice.
   - The guard-dangerous hook blocked two of my commands for naming the test-marker task or file in a grep or ls. Neither ran, and neither touched the marker.

TEST RESULTS (`sbt check`, exit 0, verbatim lines):
- `[info] Passed: Total 94, Failed 0, Errors 0, Passed 94`
- `[info] Passed: Total 98, Failed 0, Errors 0, Passed 98`
- `[info] Passed: Total 15, Failed 0, Errors 0, Passed 15`
- `[info] Passed: Total 68, Failed 0, Errors 0, Passed 68`
- `[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `[success] Total time: 162 s (0:02:42.0), completed Nov 23, 2026, 9:21:03 AM`

The 15 entity-resolution tests cover:
- config loading and validation;
- exact column order;
- no score, path or stratum in the file, with label and note empty;
- CSV quoting (comma, quote, LF, CR, edge whitespace, Unicode, plus a round trip);
- stratum rules (a conflict wins over a shared identifier);
- balanced allocation, including the real capacities;
- per-cell selection counts;
- determinism (same bytes when rebuilt, shuffled or given duplicate input; a different seed gives different output);
- traceability (pair_id is the candidate ID, record IDs and sources are present, an untraceable endpoint is an error);
- mention and ORCID sides;
- too few pairs is an error, never padding;
- two runs or conflicting copies are refused;
- strict candidate parsing;
- refusal of gold paths and of overwriting;
- one end-to-end run over a temporary run tree, with a sidecar that holds no strata.

VALIDATION RESULTS:
- Source run ID: `run-6d1175dbfdb874a9`. It is the same ID as the Phase 04 real-sample run, because the run ID is deterministic from the inputs.
- Candidates read: 418 lines (J03 109, J04 309), which merge into 347 distinct pairs (272 researcher, 75 work). That is at least 250, so there is no BLOCKED-ON.
- Generator stdout, verbatim:
```
run run-6d1175dbfdb874a9: 418 candidate lines, 347 distinct pairs
queue er-review-v1, strata review-strata-v1, seed er-review-v1
kind        stratum           available  selected
researcher  LikelyMatch             193       126
researcher  Borderline               75        75
researcher  LikelyNonMatch            4         4
work        LikelyMatch              71        71
work        Borderline                0         0
work        LikelyNonMatch            4         4
total                               347       280
wrote 280 rows to /home/ajcreator/scholarchain/target/er-review/er-review-v1.csv
wrote provenance to /home/ajcreator/scholarchain/target/er-review/er-review-v1.csv.provenance.json (csv sha256 f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e)
```
- Independent check of the file with Python's csv module:
  - The header is exactly the 20 required columns, and every row has 20 fields.
  - 280 rows, 280 distinct pair_ids; 205 researcher and 75 work.
  - label and note are empty in all rows, and no row has an empty name_or_title on either side.
  - These strings appear nowhere in the file: Likely, Borderline, score, stratum, MATCH, confidence.
  - No cell is a bare decimal number; every "0." in the file is inside a DOI.
  - 42 rows hold non-ASCII text, and quoting is used where needed.
  - No cell starts with = + - or @.
- Stratum rules (config review-strata-v1; used only to choose rows, never written to the file):
  - Any identifier-conflict feature above 0 makes the similarity 0. These are orcid_conflict, external_id_conflict, dblp_homonym_distinct and doi_conflict.
  - Otherwise, any identifier-match feature above 0 makes it 1. These are exact_*, orcid_equal, external_id_equal, profile_lists_work_doi, same_source_cluster and doi_equal.
  - Otherwise it is a weighted mean of the features. Researcher weights: name_jw 0.3, coauthor_overlap 0.35, title_token_overlap 0.2, venue_overlap 0.1, affiliation_overlap 0.05. Work weights: title_jaccard 0.6, author_overlap 0.3, same_venue_name 0.1.
  - Likely match is 0.8 or above, likely non-match is below 0.5, and borderline is in between.
  - Allocation gives every non-empty kind-by-stratum cell an equal share of 280, and passes what a small cell cannot use to the others.
  - Rows are picked by sha256(seed|pick|id) and ordered by sha256(seed|order|id), so a row's position says nothing about its stratum.
- Not applicable to T1: the er-v1 policy, adjudication, confusion matrix, precision, recall, F1 and unresolved rate are tasks T2 and T3.

ASSUMPTIONS:
- pair_id is the Phase 04 candidate ID (`mc:<kind>:<hash>`). A pair found under several keys is merged by that ID, and its features are merged by maximum.
- Left is the candidate's sourceId and right is its targetId.
- record_id is the endpoint ID: a mention ID `m:...`, a work ID, or `orcid:...`. A mention's source is its provenance source.
- An `orcid:` endpoint is the J03 canonical researcher, which is built from the mentions carrying that ORCID (no `--orcid` input was given). Its source is SCHOLARCHAIN, and its cells combine those mentions, so the left mention also appears inside the right side. There are 26 such rows.
- Lists within a cell are joined with " | ". The caps are 5 coauthors, 3 titles, 5 affiliations and 5 venue-years. Affiliation IDs are shown as institution names.
- 280 rows were targeted, more than 250, so the gold set keeps at least 250 even if the human marks up to 30 pairs UNSURE.
- The CSV is UTF-8 with no BOM and CRLF line ends, per RFC 4180. Excel may misread the Unicode without a BOM; LibreOffice or an import set to UTF-8 is fine.

RISKS:
1. Class imbalance. The samples give only 8 likely non-matches (4 researcher, 4 work) and no borderline work pairs. Blocking on full names leaves name_jw at 1.0 for every researcher pair, so negatives will be thin in the gold set and their metrics weak. The work order does not make this blocking, but I recommend the human decide before labeling whether a larger bounded sample is worth getting first (dataset-engineer, within the manifest's rules) to obtain real non-matches.
2. Not done: T2 (er-v1, resolver, adjudication port) and T3 (evaluation). datasets/gold/** was not read or written, and no label was inferred.
3. State left behind:
   - Untracked new files: the module sources, the config resource and the test suite.
   - Git-ignored: target/er-source-run and target/er-review.
4. Re-running the generator refuses to overwrite an existing file unless `--overwrite true` is given. This protects a copy the human may already be labeling.
5. The ORCID-aggregate rows (assumption above) are near-trivial matches unless an ORCID was misattributed.
6. Notes for modules/entity-resolution/MODULE.md (documentation-engineer):
   - Public API: package `scholarchain.er.review`, which holds `ReviewQueue` (Columns, similarity, stratum, allocate, select, side, rows, csvField, cells, csv, build), `ReviewQueueConfig` (load, parse, validate), `CandidateView` (parse, merge), `ReviewSide`, `ReviewRow`, `RecordIndex`, `Stratum` (internal) and `ReviewQueueMain` (run, parseArgs, isGoldPath, sidecarOf, main). The config resource is `/scholarchain/er/review-queue-v1.json`.
   - Command: `sbt "entityResolution/runMain scholarchain.er.review.ReviewQueueMain --run-root <dir> --out <file.csv> [--run-id <id>] [--overwrite true]"`.
   - Known issues: risk 1, and the `*/` doc-comment pitfall.
7. Process notes:
   - The harness backgrounded `sbt check` at 180 s despite the 600000 ms timeout, as it did for build-engineer in step 01.
   - guard-dangerous blocks a grep or pgrep that names the test-marker task or file (this is the Phase 11 false-positive item).

MANUAL ACTION REQUIRED: The human decides between labeling target/er-review/er-review-v1.csv as is (280 rows, very few likely non-matches) and asking for a larger bounded sample first. The human then labels into datasets/gold/er-gold-v1.csv; agents do not write there.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human decides on the imbalance and labels the file; documentation-engineer updates STATUS.md after T1; then T2 (entity-resolution-engineer).

---
Orchestrator check (observed 09:22): Python csv reader confirms the 20-column header in the
required order, 280 rows (205 researcher, 75 work), 280 distinct pair_ids, label and note
empty in every row; `sha256sum` f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e
matches the sidecar; `git check-ignore` confirms the file is ignored; `datasets/gold` does not
exist yet; no sbt or java process was running afterwards.

---

## step-02s-documentation-engineer-report.md

HARNESS: claude-code
MODEL: sonnet, standard tier (documentation-engineer default)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 STATUS.md update after step 02 (T1).
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN: ls and tail/grep of the phase-05 reports (read only). I ran no sbt.
TEST RESULTS: none run (documentation change only).
VALIDATION RESULTS:
- Step 01 numbers were checked against step-01-build-engineer-report.md: Passed 68, 14 project test runs and 175 s all match.
- The step 02 numbers (280 rows, 205 researcher and 75 work, sha256 f7520475...2e3e, run-6d1175dbfdb874a9, 8 likely non-matches) were checked against its report and the orchestrator check at its end.
- STATUS.md changes: the "Where things stand" Phase 05 bullet, the Phase 05 task table (step 02 done, human labeling pending, step 03 running), Note step 01 (caveat removed), a new Note step 02 and "Current state (phase 05)".
- Note step 02 records the open class-imbalance decision, the generator's refusal to overwrite, and the sbt-backgrounding and guard-dangerous process notes.
- I did not re-check line lengths of the new lines by script. I wrote them under 90 characters by hand.
ASSUMPTIONS: The git-status line in "Current state" is taken from the session-start snapshot and says so. I did not re-run git status.
RISKS: I changed nothing but STATUS.md. The class-imbalance decision stays open for the human. The 09:21 and 09:22 times come from the work order and the orchestrator check, not from my own observation.
MANUAL ACTION REQUIRED: The human decides whether to label the queue as is or to request a larger bounded sample from dataset-engineer. The human then writes datasets/gold/er-gold-v1.csv.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 03 (T2) completes. After that, T3 waits for the human's message that datasets/gold/er-gold-v1.csv exists.

---

## step-02d-documentation-engineer-report.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default effort)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 STATUS.md update for human decision 01 (close the class-imbalance decision, add task 03b, update the labeling, T3 and current-state rows).
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN: none; I used only Read and Edit on STATUS.md and read docs/agent-runs/phase-05/human-decision-01.md.
TEST RESULTS: not run. The work order said not to run sbt.
VALIDATION RESULTS:
- Edit 1, "Phase 05 task list" table:
  - The human labeling row now reads "in progress on v1 (labeled as is, decision 01); v1b follows after 03b" and says v1b is appended to datasets/gold/er-gold-v1.csv.
  - New row 03b (T1b), entity-resolution-engineer. It covers target/er-review/er-review-v1b.csv, up to 60 additional likely non-match pairs, the four selection rules, the same columns and rules as v1, and no overlap with v1. Its report is step-03b-entity-resolution-engineer-report.md. It is pending until step 03 (T2) completes.
  - The T3 row (04) now requires the class imbalance and the supplement to be reported, with v1, v1b and combined metrics separate. It gives the reason that v1b rows were selected by rules that overlap er-v1's conflict and rejection evidence.
- Edit 2, note step 02: the OPEN class-imbalance decision is now CLOSED by human decision 01. It cites docs/agent-runs/phase-05/human-decision-01.md and says the human labels er-review-v1.csv as is.
- Edit 3, "Current state (phase 05)": step 03 (T2) is still running, the next task is 03b, and T3 waits for the human's message that the gold file is complete. The half-done item is human labeling of v1, then v1b, appended to the gold file.
- I did not re-check line lengths or the cited file path after editing.
  The new table rows are long, like the existing table rows.
  The decision file path was read directly.
ASSUMPTIONS: The decision file's phrase "after the main file" means the human labels v1 first and v1b second. The two-step labeling order in the human row follows that.
RISKS: I changed only the listed STATUS.md sections. The orchestrator-reported 09:00/09:22 timings and the sbt-backgrounding notes were left as they were. I did not verify that step 03 (T2) is still running. I took that from the work order.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 03 (T2) finishes. Then 03b (T1b) runs, then the human finishes labeling v1 and v1b. T3 waits for the human's message that the gold file is complete.

---

## agent-report.md

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier (the entity-resolution-engineer default; .agents/models.json maps high to opus, effort high)

Task T2 is done, and `sbt check` passed with exit 0. The rest of the work order is implemented, except the gold-set evaluation run. That includes er-v1 (fixed now, before any label exists), adjudication behind a port with off and fake modes, a two-job Hadoop MatchJob, and the evaluation code tested only on labels written inside the tests. The job ran once over the Phase 04 sample in off mode and made 347 decisions: 338 ACCEPTED, 0 REJECTED, 5 UNRESOLVED and 4 CONFLICT, an unresolved rate of 1.44%. I did not read, create or write datasets/gold/**, and I did not regenerate or tune against target/er-review/er-review-v1.csv. Its sha256 is still f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e. No contract gap blocks this task.

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05, step 03, task T2. Implement the rest of the work order except the gold-set evaluation run, with er-v1 fixed, LLM adjudication behind a port with a deterministic fake and an off mode, and evaluation code tested on synthetic labels. Then run the outputs job once over target/er-source-run. The orchestrator saves this report as agent-report.md.

FILES CREATED (each path is absolute, under /home/ajcreator/scholarchain/):

Main code, under `modules/entity-resolution/src/main/scala/scholarchain/er/`:
- `ErNormalize.scala`: normalization `er-norm-v1` and the `PersonName` type.
- `ExactIds.scala`: the `IdScheme` enum, `IdSet`, `Agreement`, identifier extraction and comparison.
- `TextSimilarity.scala`: Jaro-Winkler, Jaccard, trigram Dice and LCS.
- `Profiles.scala`: `MentionProfile`, `WorkProfile`, `EndpointProfile` and `ProfileLimits`.
- `Candidates.scala`: `ErCandidate`, a strict mirror of match-candidate-v1, and `MergedCandidate`.
- `PolicyConfig.scala`: `Thresholds`, `SchemeRule`, the policy config and the threshold pin.
- `Features.scala`: feature functions `er-features-v1`.
- `MatchPolicy.scala`: the er-v1 rules and score.
- `Adjudication.scala`: the request, the strict output schema, the validator, `AdjudicatorPort`, `OffAdjudicator`, `FakeAdjudicator`, `PromptTemplate`, `AdjudicationCache` and `Adjudicator`.
- `Resolver.scala`: `Resolver`, `DecisionAudit` and `Resolved`.
- `MatchJob.scala`: ER-J1 and ER-J2, the `MatchJob` Tool driver, `MatchArgs`, `MatchPaths`, the counters and `LocalMatchRunner`.
- `eval/Evaluation.scala`: `GoldCsv`, `GoldLabel`, `Confusion`, `Metrics`, the reports, `EvalConfig` and `Evaluation`.
- `eval/EvaluateMain.scala`: the evaluation entry point (for T3).

Resources, under `modules/entity-resolution/src/main/resources/scholarchain/er/`:
- `er-policy-v1.json`
- `er-eval-v1.json`
- `prompts/er-adjudication-v1.md`

Tests, under `tests/module-unit/entity-resolution/`:
- `ErFixtures.scala`
- `ErNormalizeSuite.scala` (holds ErNormalizeSuite, ExactIdsSuite and TextSimilaritySuite)
- `FeaturesSuite.scala`
- `MatchPolicySuite.scala`
- `AdjudicationSuite.scala` (holds AdjudicationSuite and ResolverSuite)
- `MatchJobSuite.scala`
- `EvaluationSuite.scala`
- `LeakageSuite.scala`

Git-ignored output: /home/ajcreator/scholarchain/target/er-match-run/ (the sample run).

FILES MODIFIED: none. The T1 review code, modules/core, modules/hadoop and build.sbt are untouched.

COMMANDS RUN (all with `source toolchain/env.sh`, one sbt process at a time):
1. `sbt -batch fmt entityResolution/compile`: the first try failed because scalafmt rejects `given` as a field name. I renamed it to `givenNames`, and the second try showed `[success]`.
2. `sbt -batch fmt entityResolution/test`: `[info] Passed: Total 92, Failed 0, Errors 0, Passed 92`.
3. `sbt -batch "entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root /home/ajcreator/scholarchain/target/er-source-run --output /home/ajcreator/scholarchain/target/er-match-run" check`, with exit 0.
   - The harness moved it to the background at 180 s. I started nothing else and read the output once, after the completion notice.
   - The `[error] SLF4J(W): No SLF4J providers` lines in that output are stderr logging noise from the forked run, not failures.
4. sha256sum, zcat and python3 -I to read and summarize the outputs, plus `git status --short`.

TEST RESULTS (sbt check, verbatim lines):
- `[info] Passed: Total 94, Failed 0, Errors 0, Passed 94`
- `[info] Passed: Total 98, Failed 0, Errors 0, Passed 98`
- `[info] Passed: Total 92, Failed 0, Errors 0, Passed 92`
- `[info] Passed: Total 68, Failed 0, Errors 0, Passed 68`
- `[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `[success] Total time: 570 s (0:09:30.0), completed Nov 23, 2026, 10:10:57 AM`

The 92 entity-resolution tests by suite:

| Suite | Tests |
|---|---|
| ReviewQueueSuite (T1) | 15 |
| ErNormalizeSuite | 11 |
| ExactIdsSuite | 4 |
| TextSimilaritySuite | 2 |
| FeaturesSuite | 9 |
| MatchPolicySuite | 15 |
| AdjudicationSuite | 10 |
| ResolverSuite | 7 |
| MatchJobSuite (LocalJobRunner) | 7 |
| EvaluationSuite | 10 |
| LeakageSuite | 2 |

No test failed at any point.

VALIDATION RESULTS:

Versions:
- Policy er-v1, resource sha256 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff.
- Features `er-features-v1`, normalization `er-norm-v1`, job logic `er-job-v1`.

Thresholds (pinned in er-policy-v1.json; they come from the core config, which must equal the pin):
- accept 0.9, reject 0.35, LLM band [0.7, 0.9).
- At or above accept is ACCEPTED, at or below reject is REJECTED, anything between is UNRESOLVED.
- If the config sets a different threshold or policy name, the job refuses to run ("needs a new policy version"). The MatchPolicySuite and MatchJobSuite tests check this refusal.

Model and prompt versions:
- Prompt `er-adjudication-v1`, sha256 fbcf4b136e72b9d1edede0060be0d4543f2459a83c0e119252284cd2ca291ac5. A test fails if the text changes without a new prompt version.
- Fake model `fake-deterministic-v1`.
- Off mode records prompt and model "none". Bedrock mode is refused until Phase 07, and so is `models.llmEnabled=true`.

Adjudication band and rules:
- Only R-R9/R-W9 probabilistic UNRESOLVED results with a score in [0.7, 0.9) are sent to the adjudicator.
- The reply must be exactly one JSON object with keys decision (ACCEPT, REJECT or ABSTAIN), sourceId, targetId, confidence, reasonCodes and citedFields. Prose, code fences, extra or missing keys and null all fail the check.
- The two IDs must equal the candidate's own. Reason codes are 1 to 8 codes matching `^[A-Z][A-Z_]{1,47}$`, so they cannot carry digits or an identifier. Every cited field must be one that was given.
- An answer with confidence below 0.8 is treated as an abstention.
- The confidence of an LLM REJECT is recorded as 1 minus the reply's confidence, because decision confidence is P(same entity) on every path.
- Adjudication never changes the candidate, the profiles or the features (ResolverSuite tests this).
- The budget per task is floor(maxLlmCallsPerRun / reducers). maxLlmCallsPerRun defaults to 0.

Cache key: sha256(inputHash|promptVersion|modelVersion|policyVersion).
- inputHash is the sha256 of the canonical JSON of the request: candidate ID, kind, both IDs, policy version, score, features and bounded profile fields.
- Validated replies go to `adjudications/`. Passing `--adjudication-cache <run dir>` reloads them, and a cache line whose key does not match its own fields is refused.
- MatchJobSuite checks that a replay makes 0 calls and gets 1 cache hit.

Feature definitions (`er-features-v1`; "upstream" means the Phase 04 feature for the same evidence):

Researcher features:
- name_similarity: maximum Jaro-Winkler over name forms, including the swapped order for two-token names. Upstream name_jw is used when names are missing.
- same_full_name.
- initials_compatible: same family name and first initial, with the remaining initials in order.
- name_order_swapped and names_known.
- author_position_agree and author_position_known: both mentions are the first author, or both the last author.
- coauthor_overlap: Jaccard over family-name-plus-first-initial keys, the larger of this value and upstream.
- affiliation_overlap (larger of this and upstream), affiliation_known and affiliation_disjoint.
- topic_overlap: Jaccard of title tokens without stop words, the larger of this and upstream.
- venue_overlap (larger of this and upstream).
- year_gap: the gap between activity ranges, the smaller of this and upstream, 99 when unknown.
- shared_work, dblp_homonym_distinct and same_source_cluster (upstream).
- orcid_, openalex_ and s2_ equal/disagree flags.

Work features:
- title_similarity (trigram Dice), same_normalized_title and title_tokens.
- author_overlap and author_sequence_similarity (LCS over the longer list), and authors_known.
- year_distance and same_source.
- venue_compatible (same venue ID, otherwise trigram Dice of venue names) and venue_known.
- equal/disagree flags for doi, arxiv, openalex, s2 and repo. A preprint-server DOI never counts as disagreement.

Shared features:
- citation_overlap and citation_known. Both are always 0: work-v1 has no references.
- source_reliability: the minimum over the two sides (ORCID 1.0, DBLP 0.95, Crossref 0.95, OpenAlex 0.85, ScholarChain 0.9).
- cross_source and profiles_found.
- Every decision also records er_score.
- An `orcid:` endpoint never counts its own linked mention as evidence for itself (leave-one-out).

Rules, applied in this order:
- Researchers:
  - R-R0: contradictory IDs give CONFLICT.
  - R-R1: two mentions on the same work give REJECTED, or CONFLICT when an ID agrees.
  - R-R2: an ORCID disagreement gives CONFLICT, or REJECTED when the score is at or below reject as well.
  - R-R3: different DBLP homonym suffixes give REJECTED, or CONFLICT when an ID agrees.
  - R-R4: an ORCID, OpenAlex or S2 match is ACCEPTED when the names corroborate it (≥0.75 for ORCID, ≥0.85 for the others, with compatible initials), otherwise UNRESOLVED. A declared-only ORCID is ACCEPTED at 0.95.
  - R-R5: a source cluster is ACCEPTED.
  - R-R9: the logistic score.
- Works:
  - R-W0: contradictory IDs give CONFLICT.
  - R-W1: a DOI or arXiv disagreement gives CONFLICT, or REJECTED when the score is at or below reject as well.
  - R-W2: a DOI, arXiv, OpenAlex, S2 or repository match is ACCEPTED when title similarity reaches 0.3 (0.6 for a repository). Otherwise it is UNRESOLVED, and it is also UNRESOLVED when both records come from the same source.
  - R-W3: same normalized title (at least 4 tokens), year within 1 and author sequence ≥0.8 is ACCEPTED on the deterministic bibliographic path.
  - R-W9: the logistic score.
- The weights are in er-policy-v1.json:
  - Researcher: bias -4.3, nameAboveFloor 3.0 (floor 0.85), sameFullName 1, coauthorOverlap 6, topicOverlap 2, affiliationOverlap 2.5 and so on.
  - Work: bias -5, titleSimilarity 6, authorOverlap 2, authorSequence 1 and so on.
  - An unknown year adds nothing to the score.

Output counts on the sample (er run run-02cecabcf3e68312, upstream run-6d1175dbfdb874a9, pipeline hadoop-v2, off mode):
- 418 candidate lines merged into 347 decisions (71 duplicate copies), with 0 rejects, 0 missing profiles and 347 audit lines.

| Kind | ACCEPTED | REJECTED | UNRESOLVED | CONFLICT |
|---|---|---|---|---|
| researcher | 267 | 0 | 5 | 0 |
| work | 71 | 0 | 0 | 4 |

- By path: EXACT_ID 130, DETERMINISTIC_BIBLIOGRAPHIC 20, PROBABILISTIC 197, LLM_ADJUDICATED 0.
- Unresolved rate: 5/347 = 0.0144. All 5 are researcher pairs in the LLM band, and all carry LLM_OFF.
- Reason breakdown:
  - Researcher: 192 SCORE_AT_OR_ABOVE_ACCEPT, 44 ORCID_EQUAL, 20 SAME_SOURCE_CLUSTER, 11 OPENALEX_EQUAL, 5 SCORE_IN_LLM_BAND.
  - Work: 71 DOI_EQUAL, 4 DOI_DISAGREES.

Not applicable to T2: the confusion matrix, precision, recall, F1 and representative errors. No labels exist; this is T3.

ASSUMPTIONS:
- On N4, I took the run ID as the link to the upstream manifest. The ER run manifest records upstreamRunId plus upstreamPipeline ("hadoop-v2") and upstreamNormalization, read from `run-manifests/<runId>.json`. The human has not decided N4 yet.
- The core match-v1 decision has no timestamp or upstream-run field. So each decision gets a DecisionAudit line in `audit/` that holds decidedAt (one value per run, taken from the run start), the upstream run, upstream features, block keys, the cache key and the thresholds.
- `confidence` means P(same entity) for every outcome.
- The deterministic fake exists for wiring tests. Its decisions name `fake-deterministic-v1`. The sample run used off mode.

RISKS:
- What I deliberately did not do:
  - The T3 evaluation run.
  - Any Bedrock adjudicator.
  - `sbt packageJobs`, an EMR run, or any read of datasets/gold.
- Sample data limits:
  - The sample has no REJECTED decisions. Its 4 work CONFLICTs are DOI disagreements whose titles still score above reject.
  - Citation evidence is never available, because work-v1 carries no references. That is a contract gap for domain-engineer, not a blocker.
  - Semantic Scholar IDs and repository URLs are implemented and tested, but the selected data holds none.
- ER-J1 sends a whole `orcid:` profile to every candidate that links to that ORCID. Data grows with the number of references times maxMentionsPerEndpoint (200, slim fields). Measure this before a full-DBLP run.
- Adjudication cache: an earlier run's cache is read whole into memory in each reducer; a DynamoDB cache arrives later. Duplicate JSON keys in a reply are accepted with the last one winning (a known core decoder behavior).
- State left behind:
  - Uncommitted new files: 13 main sources, 3 resources, 8 test files.
  - Git-ignored output: target/er-match-run/.
- Notes for modules/entity-resolution/MODULE.md (documentation-engineer):
  - Public API: packages scholarchain.er (the types listed above) and scholarchain.er.eval.
  - Run command: `sbt "entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root <hadoop out> --output <dir> [--adjudicator off|fake] [--adjudication-cache <run dir>] [--reducers 4] [--compress true]"`. On EMR the entry point is `MatchJob.main`.
  - Output layout: `<out>/match-decisions/policy=er-v1/snapshot=<d>/run_id=<id>/{accepted,rejected,unresolved,conflict,audit,adjudications,rejects-v1}` plus `run-manifests/<id>.json` and `<id>-summary.json`.
  - T3 command: `sbt "entityResolution/runMain scholarchain.er.eval.EvaluateMain --gold <csv> --decisions <run dir> --out <dir>"`.
  - Known issues are the risks above.

MANUAL ACTION REQUIRED: none for T2. The human decides N4 (accept the run ID plus manifest link as implemented, or not) and finishes labeling.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md. T3 starts once the human reports that datasets/gold/er-gold-v1.csv exists; it runs EvaluateMain against target/er-match-run, the decision run whose pairs match the review queue.

---
Orchestrator check (observed after the report): no sbt or java process running;
target/er-review/er-review-v1.csv sha256 still starts f75204756578ffc9; datasets/gold does
not exist; git status shows only build.sbt, STATUS.md, docs/agent-runs/phase-05/ and new
entity-resolution sources and tests. The decision run folder holds accepted/, audit/,
conflict/, unresolved/, _counters.json and _SUCCESS; rejected/, adjudications/ and
rejects-v1/ (named in the output layout above) are absent, consistent with zero rows in
each. T3 and the reviewer should confirm that readers handle an absent empty output.

---

## step-03s-documentation-engineer-report.md

HARNESS: claude-code
MODEL: sonnet, standard tier (default; no override stated)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05, STATUS.md update after step 03 (T2). Mark step 03 done and 03b running, and record the open items from T2's RISKS.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
- "Where things stand": steps 01, 02 and 03 are done, and 03b is running.
- Phase 05 task table: row 03 is "done, see note step 03". Row 03b is "running (step 03 is done)".
- New "Note step 03 (T2)" section after "Note step 02". It records:
  - the sbt check result: entity-resolution 92 tests, 14 project test runs, 570 s;
  - the er-v1 thresholds (accept 0.9, reject 0.35, LLM band [0.7, 0.9)), fixed before any label exists;
  - sample run run-02cecabcf3e68312 over upstream run run-6d1175dbfdb874a9: 347 decisions, 338 ACCEPTED, 0 REJECTED, 5 UNRESOLVED, 4 CONFLICT, unresolved rate 1.44%;
  - the orchestrator check;
  - the open items from RISKS (listed under RISKS below).
- "Current state (phase 05)": tasks done, task running (03b), next task (03b completes, then T3 waits for the human's message that the gold file, v1 plus v1b, is complete). The N4 line now says it awaits the human's decision.
COMMANDS RUN: I read STATUS.md and the T2 report in docs/agent-runs/phase-05/agent-report.md. I ran an awk line-length check on the edited region and a grep count for the em dash.
TEST RESULTS: not applicable. I ran no sbt, as instructed. The sbt numbers in STATUS.md are copied from the T2 report.
VALIDATION RESULTS: In the edited region (lines 536-585) no line is over 90 characters, and STATUS.md has 0 em dashes. Every number was checked against the agent-report.md text. I did not replay the T2 commands.
ASSUMPTIONS: The "Where things stand" line says 03b is running, as the work order stated. I did not check this against the repository.
RISKS: Items recorded in STATUS.md that I did not verify:
- N4 awaits the human's decision. The engineer took the run ID plus the manifest link as implemented.
- Citation evidence is never available because work-v1 has no references. This is a domain-engineer contract gap.
- The empty rejected/, adjudications/ and rejects-v1/ folders are absent from the run output. T3 and the reviewer must confirm that readers handle this.
- Extra items I added from the report (my addition, beyond the three you named):
  - the sample has no REJECTED decisions;
  - ER-J1 data growth;
  - the whole-in-memory adjudication cache;
  - the not-done list (T3 evaluation, Bedrock adjudicator, packageJobs, EMR run).
- I did not change the task table's other rows or the Phase 04 sections.
MANUAL ACTION REQUIRED: the human decides N4 and finishes labeling.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: 03b (T1b) completes, then documentation-engineer updates STATUS.md again. T3 starts after the human reports the gold file is complete.

---

## step-03b-entity-resolution-engineer-report.md

LABELER NOTE (orchestrator): this report contains er-v1's decisions for the v1b pairs and the
engineer's expectation about their labels. The human should not read it until labeling of
target/er-review/er-review-v1b.csv is finished, so the labels stay blind to the policy.

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier (the entity-resolution-engineer default)

The supplementary queue is built: target/er-review/er-review-v1b.csv has 60 rows and none of them is in v1. `sbt check` exited 0, and the v1 sha256 is unchanged.

The problem is that it will not fix the class imbalance. These samples hold no pair with different ORCIDs and no pair with different DBLP homonym numbers. Only 10 pairs meet "compatible initials, no shared context". The other 50 qualify only as "same block, different clusters". In these samples each block is one real person seen through DBLP, Crossref and OpenAlex, so those 50 rows are almost certainly the same person on different chapters (for example Sven J. Dickinson on "Shock Graph" and on "Geons"). er-v1 agrees: it rejected none of the 60.

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05, step 03b, task T1b (human decision 01). Build the supplementary review queue target/er-review/er-review-v1b.csv: up to 60 likely non-match pairs from upstream run run-6d1175dbfdb874a9, chosen only by the human's structural criteria, with no overlap with v1. Then give every pair an er-v1 decision from the unchanged policy. The orchestrator saves this report as step-03b-entity-resolution-engineer-report.md.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/resources/scholarchain/er/review-supplement-v1b.json: the versioned config. It sets the queue version er-review-v1b, generator er-review-supplement-v1, seed er-review-v1b, maxRows 60, the criteria in priority order, the context dimensions (work, coauthor, affiliation, venue, title_token) and the cluster link feature same_source_cluster.
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewSupplement.scala: the pure part. It holds `SupplementCriterion`, `SupplementConfig` (load, parse, validate), `BlockLink`, `Block`, `MentionContext` and `SupplementPair`. `ReviewSupplement` holds links, blocks, workPairsNotEmitted, sharedContext, holds, qualifying, roundRobin, select, candidate, rows, csvRecords and pairIds.
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewSupplementMain.scala: the entry point. It hashes v1 before and after the run and fails if v1 changed. It refuses gold paths, refuses v1 or its sidecar as --out, and refuses an existing output unless `--overwrite true` is given.
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/ReviewSupplementSuite.scala: 11 tests.
- Git-ignored outputs (`git check-ignore -v` shows `.gitignore:24:target/` for each):
  - /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv
  - /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv.provenance.json
  - /home/ajcreator/scholarchain/target/er-source-run-v1b/ (the supplementary candidate run)
  - /home/ajcreator/scholarchain/target/er-match-run-v1b/ (the er-v1 decisions for v1b)

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/review/ReviewQueueMain.scala: `children`, `lines` and `writeAtomically` changed from `private` to `private[review]`, so the new generator can reuse them. Their behavior is unchanged. The er-v1 policy, thresholds, features, rules, MatchJob and target/er-review/er-review-v1.csv are untouched.

COMMANDS RUN (each with `source toolchain/env.sh`, one sbt process at a time):
1. `sbt -batch fmt "entityResolution/testOnly scholarchain.er.review.*"`: `[info] Passed: Total 26, Failed 0, Errors 0, Passed 26` (11 new tests and 15 from ReviewQueueSuite).
2. Generator plus the unchanged er-v1 runner in one invocation. Both ended with `[success]`.
   - `sbt -batch "entityResolution/runMain scholarchain.er.review.ReviewSupplementMain --run-root target/er-source-run --review-v1 target/er-review/er-review-v1.csv --out target/er-review/er-review-v1b.csv --candidates-out target/er-source-run-v1b"`
   - `"entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root target/er-source-run-v1b --output target/er-match-run-v1b"` (adjudicator off, the default)
   - Paths are absolute in the real command.
3. `python3 -I` checks of the CSV and the sidecar, zcat summaries of the decisions, `sha256sum` and `git check-ignore`.
4. The generator again with `--overwrite true`, then `check`, in one sbt invocation, exit 0. The rerun wrote byte-identical output: same sha256 08c02f22..., which shows determinism on the real data.
   - The harness moved this run to the background at 180 s. I started nothing else and read the output once, after the completion notice. No sbt or java process was left running.
   - An earlier try was blocked before it ran: guard-dangerous matched the marker task name inside my grep pattern (the known Phase 11 false positive).

TEST RESULTS (`sbt check`, exit 0, verbatim lines):
- `[info] Passed: Total 94, Failed 0, Errors 0, Passed 94`
- `[info] Passed: Total 103, Failed 0, Errors 0, Passed 103`
- `[info] Passed: Total 98, Failed 0, Errors 0, Passed 98`
- `[info] Passed: Total 68, Failed 0, Errors 0, Passed 68`
- `[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `[success] Total time: 157 s (0:02:37.0), completed Nov 23, 2026, 10:33:08 AM`

Entity resolution now has 103 tests: the 92 from before plus 11 in ReviewSupplementSuite. They cover:
- config loading and refusal of bad values;
- clusters built as connected components, and exact or work candidates never defining a block;
- each of the four criteria, positive and negative;
- every qualifying pair listing every criterion that holds, in priority order, and a missing source record being an error;
- disjointness from a given v1 ID set and from the upstream candidates;
- priority tiers, the maxRows cap, and no padding (60 asked, 9 exist, 9 delivered);
- determinism under shuffled input, and a different order under a different seed;
- block round robin;
- v1 columns only, no score, criterion or label strings, and label and note empty;
- the candidate records: valid match-candidate-v1, features empty, provenance naming the generator;
- strict reading of the v1 pair IDs;
- an end-to-end run. It checks that v1's sha256 is unchanged, that the sidecar holds no criterion names, and that the written run is read by the unchanged `MatchJob.upstream` (pipeline er-review-supplement-v1, the exact folder empty, works copied). It also checks the refusals.

VALIDATION RESULTS:
- Generator stdout, verbatim:
```
run run-6d1175dbfdb874a9: 418 candidate lines, 347 distinct pairs, 52 researcher blocks, 193 block members
review v1 /home/ajcreator/scholarchain/target/er-review/er-review-v1.csv: 280 pair ids, sha256 f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e
supplement er-review-v1b, generator er-review-supplement-v1, seed er-review-v1b, maxRows 60
same-block researcher pairs 499; qualifying 423 (in review v1 158, other upstream candidates 41, eligible 224)
same-block work pairs not emitted upstream 0 (the criteria are researcher criteria; no work pair is selected)
criterion (first that holds)                eligible  selected
DIFFERENT_ORCID                                    0         0
DBLP_HOMONYM_DIFFERENT                             0         0
COMPATIBLE_INITIALS_NO_SHARED_CONTEXT             10        10
SAME_BLOCK_DIFFERENT_CLUSTERS                    214        50
criterion (any that holds, overlapping)     eligible  selected
DIFFERENT_ORCID                                    0         0
DBLP_HOMONYM_DIFFERENT                             0         0
COMPATIBLE_INITIALS_NO_SHARED_CONTEXT             10        10
SAME_BLOCK_DIFFERENT_CLUSTERS                    224        60
selected rows: 60 (researcher 60, work 0), blocks 7
selected source pairs: CROSSREF-DBLP 18, CROSSREF-OPENALEX 15, DBLP-OPENALEX 27
wrote 60 rows to /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv
wrote provenance to /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv.provenance.json (csv sha256 08c02f228229f907ce7de1b967dd22ce5de5af6263c06411fb23c7a68c86c845)
wrote supplementary candidates run run-8f780e21fab13ffd (60 lines) to /home/ajcreator/scholarchain/target/er-source-run-v1b
review v1 sha256 unchanged: f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e
```
- The file: /home/ajcreator/scholarchain/target/er-review/er-review-v1b.csv, sha256 08c02f228229f907ce7de1b967dd22ce5de5af6263c06411fb23c7a68c86c845. Python csv check:
  - The header is identical to v1 (the same 20 columns in the same order), and every row has 20 fields.
  - 60 rows (all researcher), 60 distinct pair_ids, 0 overlap with v1's 280 pair_ids.
  - label and note are empty in every row, and no name_or_title cell is empty.
  - Both record_ids in every row are `m:` mention IDs.
  - No banned string appears: Likely, Borderline, score, stratum, MATCH, confidence, criterion, the four criterion names, or er-v1.
  - No bare decimal cell, and no non-empty cell starts with = + - or @.
  - UTF-8 with no BOM and CRLF record ends (61 CRLF).
- The sidecar holds the upstream run, its candidate and export files, the v1 path and sha256 (f752...), the config resource and its sha256, the supplementary run ID and the csv sha256. Per-pair criteria and criterion counts are left out, because the sidecar sits next to the file the labeler opens. The counts are in this report only.
- v1 sha256 was f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e before both runs, after both runs, and at the end.
- How er-v1 decides every v1b pair (orchestrator only; keep this out of the labeler's file):
  - The generator writes each selected pair as a match-candidate-v1 record in /home/ajcreator/scholarchain/target/er-source-run-v1b, which is laid out like an upstream run. Supplementary run run-8f780e21fab13ffd holds:
    - `run-manifests/run-8f780e21fab13ffd.json`, which records pipeline er-review-supplement-v1, upstreamRunId run-6d1175dbfdb874a9, normalization norm-v1, the config, v1, works and pair-ID hashes, and no timestamp;
    - `match-candidates/kind=blocked/.../part-r-00000` with the 60 lines;
    - an empty `kind=exact` part file;
    - byte copies of the upstream `export/works` files;
    - a `_SUCCESS` file in each folder.
  - Each candidate record carries:
    - candidateId equal to the pair_id (`ErCandidate.idOf`, the Phase 04 formula);
    - blockKey set to the J04 block;
    - features `{}` and no identifiers, so er-v1 decides from the endpoint profiles alone;
    - provenance with policyVersion `er-review-supplement-v1` and runId run-8f780e21fab13ffd.
  - The unchanged LocalMatchRunner (policy er-v1, policy sha256 82ce9ae5..., thresholds accept 0.9, reject 0.35, LLM band [0.7, 0.9), adjudicator OFF, prompt and model "none") produced decision run **run-b1098fbaba96c360**, at target/er-match-run-v1b/match-decisions/policy=er-v1/snapshot=2026-11-22/run_id=run-b1098fbaba96c360.
  - Outcomes: 60 decisions: 48 ACCEPTED, 0 REJECTED, 12 UNRESOLVED, 0 CONFLICT. All 60 took the PROBABILISTIC path, and the unresolved rate is 0.20.
    - The 48 ACCEPTED have reason SCORE_AT_OR_ABOVE_ACCEPT, with scores from 0.997 to 1.0.
    - Of the 12 UNRESOLVED, 10 have SCORE_BETWEEN_THRESHOLDS and 2 have SCORE_IN_LLM_BAND plus LLM_OFF, with scores from 0.389 to 0.896.
- For T3: evaluate v1 rows against target/er-match-run (run-02cecabcf3e68312) and v1b rows against target/er-match-run-v1b (run-b1098fbaba96c360). Report v1, v1b and combined separately. Every v1b pair_id has exactly one decision, because Phase 04 candidates were excluded from v1b.
- Not applicable here: the confusion matrix, precision, recall and F1. No labels were used; that is T3.

ASSUMPTIONS:
- The universe is every mention pair inside one Phase 04 researcher block. Block membership comes from the J04 `blockKey`, and source clusters are the connected components of J04's `same_source_cluster` member links. Hadoop logic is not re-implemented and the hadoop module is not imported.
- Each criterion is tested separately, and a pair qualifies if any one holds; a pair inside one source cluster qualifies only through one of the other three.
  - Different ORCIDs: both mentions carry an ORCID and the iDs differ.
  - DBLP homonym: both mentions are DBLP, their strings differ, and they are equal once the homonym number is removed.
  - No shared context: the initials are compatible, and the two mentions share no work, coauthor key, affiliation, venue key or title token.
  - Same block, different clusters: the two mentions are in different source clusters.
- Tiers are filled in the configured order. Within a tier, a seeded round robin over blocks keeps the two 18-member blocks from taking every row. The output is ordered by a second hash.
- Excluded:
  - the 158 qualifying pairs already in v1;
  - the 41 qualifying pairs that are other Phase 04 candidates. Their er-v1 decision would come from a different run, with upstream features.
- Work pairs: all 0 same-block work pairs were already emitted by Phase 04 (J04 compares every pair in a work block), and the criteria are researcher criteria, so v1b has no work rows.
- I read v1 only for its pair_id column, plus its sha256.
- Between my start and my end, `datasets/gold/er-gold-v1.csv` appeared; the human created it. I only saw the file name in a directory listing. I did not open, read or write it.

RISKS:
1. v1b probably does not supply non-matches. These samples have no different-ORCID or DBLP-homonym pairs inside a block: each block has at most one ORCID and one DBLP string. Every block's mentions carry the same full name, from the same encyclopedia chapters seen through three sources. On this data, "different clusters" mostly means "different sources", not "the source says these are different people". er-v1 found no REJECTED among the 60. If the human labels v1b, expect mostly MATCH, and the imbalance will remain. A real fix needs a larger bounded sample with real homonyms (dataset-engineer, within the manifest's rules).
2. v1b decisions use no upstream features. That is faithful to these pairs, which J04 never compared, but it differs from how v1 pairs were decided. T3 must report v1 and v1b separately as well as combined. The work order states this too.
3. What I did not do:
   - change er-v1, MatchJob, thresholds or features;
   - read er-v1 outputs before selecting (I read er-match-run-v1b only after the selection, to report it);
   - read or write datasets/gold;
   - run T3.
4. State left behind:
   - Untracked new files: 2 sources, 1 resource and 1 test suite.
   - One modified untracked source: ReviewQueueMain, with the visibility change only.
   - Git-ignored outputs: target/er-review/er-review-v1b.csv and its sidecar, target/er-source-run-v1b and target/er-match-run-v1b.
   - No sbt or java process is running.
5. Notes for modules/entity-resolution/MODULE.md (documentation-engineer):
   - Public API: `scholarchain.er.review.ReviewSupplement`, `SupplementConfig` (resource `/scholarchain/er/review-supplement-v1b.json`), `SupplementCriterion`, `MentionContext`, `Block`, `BlockLink`, `SupplementPair` and `ReviewSupplementMain` (parseArgs, run, main).
   - Command: `sbt "entityResolution/runMain scholarchain.er.review.ReviewSupplementMain --run-root <hadoop out> --review-v1 <v1 csv> --out <csv> --candidates-out <dir> [--run-id <id>] [--overwrite true]"`. Then run `entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root <candidates-out> --output <dir>`.
   - Known issue: risk 1.

MANUAL ACTION REQUIRED: The human should decide whether to label er-review-v1b.csv, knowing it probably holds mostly matches (risk 1), or to ask for a larger bounded sample with real homonyms first. Any labels go only into datasets/gold/er-gold-v1.csv, which agents do not write.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates STATUS.md after 03b. The human decides on v1b and labels. T3 starts when the human reports the gold file is complete.

---
Orchestrator check (observed): v1b header identical to v1; 60 rows, all researcher, 20
fields each; label and note empty; 0 pair_id overlap with v1; sha256 v1b starts
08c02f228229f907ce7d, v1 still starts f75204756578ffc93414; no sbt or java running;
datasets/gold/er-gold-v1.csv exists (listing only, not opened).

---

## step-03bs-documentation-engineer-report.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05, STATUS.md update after step 03b and human message 02. The edit was limited to STATUS.md and kept to structural facts about v1b.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
- Where things stand: step 03b is now listed as done, v1 is labeled, and v1b labeling is in progress.
- Phase 05 task table, human labeling row: "v1 done (280 rows, human message 02); v1b in progress".
- Phase 05 task table, row 03b: done, pointing to the new note.
- Phase 05 task table, row 04 (T3): pending until the human says v1b is labeled and appended.
- New "Note step 03b" with these facts:
  - v1b: 60 researcher rows, 0 overlap with v1, the sha256, and a header identical to v1.
  - Selection by criterion: ORCID 0, homonym 0, initials 10, same block 50.
  - er-v1 decisions for v1b are in a separate run, `target/er-match-run-v1b`.
  - `sbt check` is green with 103 entity-resolution tests and 14 project test runs.
  - The files added.
  - A blindness warning that the report must not be shown to the labeler.
- New "Note human message 02": 280 rows (228 MATCH, 13 NON_MATCH, 39 UNSURE) as stated by the human, agents have not opened the file, and the alternative of running T3 on v1 only.
- Current state: tasks done now include 03b, no task is running, and the next task is T3 after the human's message.
COMMANDS RUN: Read of STATUS.md, human-decision-02.md and selected lines of the step 03b report. grep for section headers. Edit calls. A git diff check of added line lengths and a grep for em dashes.
TEST RESULTS: none run; sbt was not run, as instructed. The sbt figures (103 entity-resolution tests, 14 project test runs) are copied from the 03b report's verbatim `sbt check` lines.
VALIDATION RESULTS:
- The added lines were checked after the edit: two lines were over 90 characters, I re-wrapped them, and I did not re-run the check afterwards.
- The em dash count in STATUS.md is 0.
- The v1b sha256, row count, overlap and criterion counts match the report's FILES, generator stdout and orchestrator-check lines.
- The 280-row gold figures come from human-decision-02.md only.
ASSUMPTIONS: The report's "Selection by criterion" counts were read from the generator stdout table (selected column). I took no er-v1 outcomes or label expectations from the report.
RISKS:
- To read the orchestrator-check section I read the report's first 75 lines and its last lines. The first lines, including the opening paragraph, hold the engineer's expectation about the v1b labels. I did not copy any of it into STATUS.md.
- The gold file and the v1b CSV were not opened or hashed by me.
- Table rows in STATUS.md are over 90 characters, as are the existing ones.
MANUAL ACTION REQUIRED: The human tells the orchestrator when v1b is labeled and appended to `datasets/gold/er-gold-v1.csv`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: T3 (step 04, entity-resolution-engineer) after the human's message that v1b is labeled and appended.

---

## step-03ds-documentation-engineer-report.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default effort)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 STATUS update for human decision 03 (v1b not labeled and unused; T3 runs on v1 only).
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN: Read of STATUS.md and docs/agent-runs/phase-05/human-decision-03.md. Six Edit calls on STATUS.md. One Bash check, which returned exit 0 (details below).
TEST RESULTS: none; sbt not run, as ordered.
VALIDATION RESULTS: The Bash check found no added non-table line over 90 characters. It found 0 em dashes in STATUS.md. It confirmed that docs/agent-runs/phase-05/human-decision-03.md exists. Sections updated, all from decision 03 and the work order:
- "Where things stand": Phase 05 line now says v1 is labeled and T3 runs now on v1 only.
- Task list, human labeling row: done, v1 only, 280 rows (228 MATCH, 13 NON_MATCH, 39 UNSURE). v1b was not labeled and is unused (decision 03).
- Task list, row 04 (T3): the state is "running". It evaluates v1 only against run-02cecabcf3e68312 in target/er-match-run. It must record the few-non-matches limitation. The v1b queue, its candidate run and target/er-match-run-v1b are not used.
- Note step 03b: the blindness warning now says v1b labeling will not happen. It keeps the note that the step 03b report holds er-v1 outcomes for the v1b pairs.
- Human message 02 note: marked superseded. A new "Note human decision 03" paragraph was added.
- Current state: running is T3. Next is step 05 (docs/entity-resolution.md and modules/entity-resolution/MODULE.md) after T3 completes. Nothing is half done on labeling.
ASSUMPTIONS: "Running now" means T3 started after decision 03, and I wrote that without a start time. I left the Git bullet of the current state unchanged.
RISKS: I did not verify the label counts (228, 13 and 39) myself. They come from human message 02 and the work order, and agents have not opened the gold file. The "Open decisions for the human" section is untouched. Nothing is committed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: T3 completes (entity-resolution-engineer), then step 05 (documentation-engineer).

---

## step-03-entity-resolution-engineer-report.md

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, effort high (the entity-resolution-engineer default in .agents/models.json)

T3 is done: the gold-set evaluation of er-v1 ran on `datasets/gold/er-gold-v1.csv` against decision run `run-02cecabcf3e68312`, and `sbt check` exited 0.

**Headline numbers** (MATCH and NON_MATCH counted; UNSURE reported separately):
- Researcher: precision 0.938, 95% CI [0.889, 0.966]; recall 0.987, CI [0.954, 0.996]; F1 0.962; coverage 0.970.
- Work: precision 1.000, CI [0.949, 1.000]; recall 0.947, CI [0.871, 0.979]; F1 0.973; coverage 0.947.
- er-v1 rejected nothing, so the reject side is not measured by this gold set.

**Two things need you:**
1. One output was not saved. The ownership guard blocked my write of `target/er-eval/output-er-evaluation.md` because `target/**` has no owner in `docs/agents.md`. I did not get around it with a shell write. The full report is between the markers below. The orchestrator saves it as `docs/agent-runs/phase-05/output-er-evaluation.md`, and the human or a matrix change decides whether a copy belongs under `target/`. The machine outputs `/home/ajcreator/scholarchain/target/er-eval/er-evaluation.json` and `/home/ajcreator/scholarchain/target/er-eval/er-evaluation.md` exist; EvaluateMain wrote them itself.
2. Two labeling questions are escalated (section 9 of the report). I did not change or infer any label.

=== BEGIN output-er-evaluation.md ===
[Orchestrator: the block between these markers was saved verbatim as
docs/agent-runs/phase-05/output-er-evaluation.md and is not repeated here.]
=== END output-er-evaluation.md ===

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05, step 04, task T3. Run the gold-set evaluation of er-v1 run run-02cecabcf3e68312 on datasets/gold/er-gold-v1.csv (v1 only). Do the read-only pre-checks first. Report metrics per kind and per path, the UNSURE rows, error analysis, threshold sensitivity (reported only), Wilson CIs and the human's limitation. The orchestrator saves this report as step-03-entity-resolution-engineer-report.md.

FILES CREATED:
- /home/ajcreator/scholarchain/target/er-eval/er-evaluation.json (git-ignored; written by EvaluateMain)
- /home/ajcreator/scholarchain/target/er-eval/er-evaluation.md (git-ignored; written by EvaluateMain)
- Scratch scripts, not part of the repository: /tmp/claude-1000/-home-ajcreator-scholarchain/347fb939-df75-4353-9d81-0bc5f75d7c8d/scratchpad/precheck.py, inspect.py, transitive.py and titles.py
- Not created: /home/ajcreator/scholarchain/target/er-eval/output-er-evaluation.md. guard-ownership blocked the write: "entity-resolution-engineer may not write target/er-eval/output-er-evaluation.md; it belongs to no owner in docs/agents.md (a matrix gap: the orchestrator escalates it to the human)". I did not work around it.

FILES MODIFIED (evaluation code only; er-v1 matching code, policy, features, thresholds, prompt and configuration are untouched):
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/eval/Evaluation.scala
  - New `Interval` and `Wilson` types (`interval`, `interval95`, `Z95` = 1.959963984540054).
  - `Metrics` gains `precisionWilson95` and `recallWilson95`.
  - `GoldRow` gains `leftName` and `rightName`, read from the optional display columns `left_name_or_title` and `right_name_or_title`. Labels are still joined only by pair_id and record IDs.
  - `ErrorExample` gains `leftName` and `rightName`.
  - `KindReport` gains `unsureNotAccepted`.
  - `EvaluationReport` gains `runOutcomes` (kind to outcome to count).
  - `byPath` now always lists all four MatchPath values; an empty path has labeled = 0.
  - Markdown changes:
    - The new `CountingRules` text.
    - CI columns.
    - "0 pairs" rows for empty paths.
    - Names, labels and rules in the error tables.
    - UNSURE counts by path.
    - TP, FP, TN, FN and unresolved columns in the sensitivity table.
    - Run-level outcome lines.
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/EvaluationSuite.scala
  - 3 new tests:
    - Wilson values checked against published ones (0/10 → [0, 0.2775]).
    - Run outcome counts.
    - A gold file without the display columns still parses.
  - Updated the byPath assertions, because all four paths are now listed by design.
  - Added assertions on names, rules, unsureNotAccepted and the Markdown.

COMMANDS RUN (only those whose output I observed):
1. Read-only pre-checks: `python3 -I precheck.py <gold> <queue> <run dir>`. All checks passed; the counts are in report section 2.
2. `sha256sum` of the gold file, the queue, er-policy-v1.json, er-eval-v1.json and the prompt; the run manifest and summary read with `python3 -I -m json.tool`; `find -newer` on the run manifest. It shows that only T1b review files and Evaluation.scala changed since the run.
3. `sbt -batch fmt entityResolution/test "entityResolution/runMain scholarchain.er.eval.EvaluateMain --gold … --decisions … --out target/er-eval"`, attempt 1: compile failed. Verbatim: "method Iterable in object Ordering is deprecated since 2.13.0". I fixed it with `sortBy(_._1)`.
4. The same command, attempt 2: exit 0. `[info] Passed: Total 106, Failed 0, Errors 0, Passed 106`, and EvaluateMain wrote target/er-eval/.
   - The `error:` lines in the log are expected messages from negative tests.
5. `python3 -I inspect.py`, `transitive.py` and `titles.py` with the gold path passed as an argument (read-only analysis).
   - An earlier inline heredoc that named the gold path was blocked by guard-dangerous and did not run.
6. `sbt -batch check`, exit 0.
   - The first attempt was blocked by guard-dangerous before it ran, because my grep filter named the marker task literally.
   - The rerun was moved to the background at 180 s. I started nothing else and read the output once, after the completion notice.

TEST RESULTS (sbt check, verbatim):
- `[info] Passed: Total 94, Failed 0, Errors 0, Passed 94`
- `[info] Passed: Total 106, Failed 0, Errors 0, Passed 106` (entityResolution)
- `[info] Passed: Total 98, Failed 0, Errors 0, Passed 98`
- `[info] Passed: Total 68, Failed 0, Errors 0, Passed 68`
- `[info] markTestRun: 14 project test runs passed, touched /home/ajcreator/scholarchain/.claude/.last-test-run`
- `[success] Total time: 150 s (0:02:30.0), completed Nov 23, 2026, 10:47:08 AM`

VALIDATION RESULTS:

Versions and settings:
- Policy er-v1, sha256 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff (equal to the run manifest).
- Thresholds: accept 0.9, reject 0.35, LLM band [0.7, 0.9).
- Features er-features-v1, normalization er-norm-v1, job er-job-v1, eval er-eval-v1 (sha256 11031f93…).
- Adjudicator OFF: prompt "none" and model "none" in the run. The module prompt er-adjudication-v1 (sha256 fbcf4b13…) was not used.

Feature definitions (er-features-v1, unchanged; full list in agent-report.md):
- Researcher: name Jaro-Winkler (order-swap aware), same full name, initials compatibility, author position, coauthor, affiliation, topic and venue overlap, year gap, DBLP homonym and source-cluster flags, ORCID/OpenAlex/S2 equal or disagree flags.
- Work: title trigram Dice, same normalized title, author overlap and LCS sequence, year distance, venue compatibility, DOI/arXiv/OpenAlex/S2/repo flags.
- Shared: source reliability. Citation features are always 0 (the contract has no references).

Confusion matrices (rows MATCH / NON_MATCH; columns ACCEPTED, REJECTED, UNRESOLVED, CONFLICT):
- Researcher: [151, 0, 2, 0] / [10, 0, 3, 0].
- Work: [71, 0, 0, 4] / [0, 0, 0, 0].

Researcher metrics:
- All paths: precision 0.938 [0.889, 0.966], recall 0.987 [0.954, 0.996], F1 0.962, coverage 0.970.
- EXACT_ID: 33 pairs, P 0.970, R 1.000, F1 0.985.
- DETERMINISTIC_BIBLIOGRAPHIC (R-R5): 11 pairs, P 0.636 [0.354, 0.848], R 1.000, F1 0.778.
- PROBABILISTIC: 122 pairs, P 0.957, R 0.982, F1 0.970.
- LLM_ADJUDICATED: 0 pairs.

Work metrics:
- All paths and EXACT_ID (75 pairs): precision 1.000 [0.949, 1.000], recall 0.947 [0.871, 0.979], F1 0.973, coverage 0.947.
- DETERMINISTIC_BIBLIOGRAPHIC, PROBABILISTIC and LLM_ADJUDICATED: 0 pairs each.

Both kinds combined: precision 0.957 [0.922, 0.976], recall 0.974 [0.944, 0.988], F1 0.965.

Unresolved rate:
- Labeled pairs: researcher 0.030, work 0.000, all 0.021.
- Whole run: 5/347 = 0.0144.

UNSURE: 39 rows, all accepted (PROBABILISTIC 35, DETERMINISTIC_BIBLIOGRAPHIC 3, EXACT_ID 1).

Representative errors:
- 10 FPs, all same-full-name chapter-author pairs. 1 is ORCID_EQUAL (`mc:researcher:10c07413d756444b3991`), 4 are R-R5 SAME_SOURCE_CLUSTER and 5 are R-R9.
- 0 strict FNs.
- 6 MATCH labels not accepted: 2 in the LLM band with LLM_OFF, and 4 work DOI_DISAGREES conflicts.

Reject side: 0 REJECTED decisions, so TN = 0. The share of NON_MATCH rejected is 0/13, Wilson [0.000, 0.228].

ASSUMPTIONS:
- "Up to ten FNs" is met by showing all 6 MATCH-not-accepted rows, because strict FNs (MATCH rejected) are 0. Both are shown and labeled.
- Wilson z = 1.959963984540054 is in the code as the definition of a 95% interval, not as a tunable threshold.
- The run-level unresolved rate is taken from the decision files; it matches the run summary (0.01440922190201729).

RISKS:
- What I deliberately did not do:
  - Change er-v1, its thresholds, features, config or prompt.
  - Rerun the match job.
  - Read anything from v1b.
  - Write, infer or correct any label.
  - Use a shell write to get around the target/ ownership block.
- State left behind:
  - Evaluation.scala and EvaluationSuite.scala modified (both still untracked from T2).
  - target/er-eval/ holds the two machine outputs.
  - .claude/.last-test-run was touched by sbt check.
- Measurement limits:
  - The reject side and work precision are unmeasured.
  - The researcher NON_MATCH sample covers 4 names.
  - The conclusions depend on the two escalated labeling questions.
- Notes for modules/entity-resolution/MODULE.md (documentation-engineer):
  - Public API additions in `scholarchain.er.eval`: `Interval`, `Wilson`, `Evaluation.CountingRules`, the new Metrics, GoldRow, ErrorExample, KindReport and EvaluationReport fields, and byPath always listing all four paths.
  - Done: T3 evaluation, with the numbers above.
  - Not done: Bedrock adjudication (Phase 07); a gold set with more negatives.
  - Known issues: R-R5 precision 0.636 on 11 pairs; 4 work DOI conflicts the human labels MATCH; the ORCID-equal NON_MATCH under human review.

MANUAL ACTION REQUIRED (human):
1. Confirm or relabel `mc:researcher:10c07413d756444b3991` (NON_MATCH with equal ORCIDs).
2. State the labeling criterion used for the 13 NON_MATCH rows (different person, or something else).
3. Decide the ownership of `target/**` evaluation copies (matrix gap), or accept the docs/agent-runs copy only.

BLOCKED-ON OWNER: none. The target/ write is a matrix gap for the orchestrator to escalate; it does not block T3.
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator saves the Markdown above as docs/agent-runs/phase-05/output-er-evaluation.md and this report as step-03-entity-resolution-engineer-report.md. Then the human answers the escalations, documentation-engineer does step 05 (docs/entity-resolution.md, MODULE.md, STATUS.md), and a fresh code-reviewer reviews.

---

## step-04s-documentation-engineer-report.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default effort)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 STATUS update after step 04 (T3, row 04). Mark T3 done, record three open human items, put step 05 on hold.
FILES CREATED: none
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
- "Where things stand": Phase 05 entry now lists step 04 as done and step 05 as on hold.
- Phase 05 task list: row 04 is "done, see note step 04"; row 05 is "on hold until the human answers open items 1 and 2".
- New "Note step 04", placed before the step 03b note.
- "Current state (phase 05)" rewritten: task running none, next task step 05 on hold, and the three open human items.
COMMANDS RUN: Read of STATUS.md; grep and sed on docs/agent-runs/phase-05/output-er-evaluation.md and step-03-entity-resolution-engineer-report.md; awk line-length and em-dash check on STATUS.md. No sbt.
TEST RESULTS: Not run, as instructed. The note quotes the reports: sbt check green, entityResolution 106 of 106 tests, 14 project test runs.
VALIDATION RESULTS:
- I checked each headline figure against output-er-evaluation.md.
  - Researcher: P 0.938 [0.889, 0.966], R 0.987, F1 0.962 (line 77).
  - Work: P 1.000 [0.949, 1.000], R 0.947, F1 0.973 (line 94).
  - Unresolved rate 0.0144 over the run, 5 of 347 (section 4.4).
  - 0 REJECTED, with the reject side not measured (line 130 onward).
  - Section 9 holds the human items on mc:researcher:10c07413d756444b3991 and on the 13 NON_MATCH rows.
- STATUS.md has no em dashes.
- Four non-table lines are over 90 characters (lines 134, 276, 380 and 680). I did not trace them, and none of the text I added is on them.
- The matrix gap on target/** (open item 3) comes from your work order. I did not check it against the territory block.
ASSUMPTIONS: The "5 of 347" figure is the run summary in section 4.4 of the evaluation. The two items about the label criterion and the ORCID row are taken from section 9 of that report.
RISKS:
- A first attempt to edit with a python heredoc was blocked by guard-dangerous, because the heredoc text named the gold-set path. No file changed from that attempt. I redid the edit with the Edit tool and kept the gold path out of the new text.
- I did not edit docs/entity-resolution.md or MODULE.md (step 05 is on hold).
- The "Half done" text still records N4 as awaiting a human decision.
- I did not check that target/er-eval exists on disk.
MANUAL ACTION REQUIRED: The human answers three items.
1. Confirm or relabel mc:researcher:10c07413d756444b3991.
2. State the labeling criterion used for the 13 NON_MATCH rows.
3. Choose a fix for the target/** ownership gap: a factory-engineer matrix change, or accept the docs/agent-runs copy only.
BLOCKED-ON OWNER: none for this task. Step 05 waits on the human (items 1 and 2).
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human answers items 1 and 2. Then step 05 (documentation-engineer: docs/entity-resolution.md and modules/entity-resolution/MODULE.md) runs, then Review 1.

---

## step-04-documentation-engineer-report.md

HARNESS: claude-code
MODEL: sonnet (standard tier, default; effort per .agents/models.json)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 step 05. Write docs/entity-resolution.md, update modules/entity-resolution/MODULE.md, update STATUS.md. Record human decision 04, mark step 05 done, and set the next task to Review 1.

FILES CREATED:
- /home/ajcreator/scholarchain/docs/entity-resolution.md

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/MODULE.md
- /home/ajcreator/scholarchain/STATUS.md

What each file now says:
- docs/entity-resolution.md puts the decision 03 and 04 caveats in a section at the top:
  - The 13 researcher NON_MATCH labels are known labeling errors ("different chapter", not "different person"). The equal-ORCID pair mc:researcher:10c07413d756444b3991 is among them.
  - The 10 researcher false positives are likely not real errors of er-v1.
  - Researcher precision 0.938 is understated.
  - The reject side is effectively unmeasured.
  - The decision 03 limitation is quoted in the human's words.
  - It then covers purpose and pipeline position, normalization and exact IDs, features, policy er-v1 with its rules, thresholds and conflicts, the adjudication port, outputs and layout, the review queue (v1, and v1b left unlabeled and unused), and the evaluation.
  - Closing section: limits and not done.
- MODULE.md has the purpose, public API for the three packages, run commands, output layout, done, not done, known issues and dependencies. The known issues cover the items you listed, and I added N4 as open, the sbt-backgrounding note and the `*/` doc-comment pitfall.
- STATUS.md has the following changes:
  - A decision 04 note, a step 05 note and the table row marked done.
  - Review 1 marked next.
  - Open items 1 and 2 closed by decision 04.
  - Item 3 (target/** gap) and N4 left open.
  - The Phase 05 summary line near the top.

COMMANDS RUN: ls and sed/grep/awk reads for checks (all exit 0), plus `git diff --stat -- build.sbt`. No sbt.

TEST RESULTS: none run; sbt was not run, as instructed.

VALIDATION RESULTS:
- Every number in the two docs comes from output-er-evaluation.md, agent-report.md (T2), step-02 or step-03 reports, or human-decision-01 to -04. No number from ~/ref-phase-05 was used.
- Resource file names under modules/entity-resolution/src/main/resources/scholarchain/er/ and the source file list were checked on disk. I corrected the supplement resource name to `review-supplement-v1b.json`.
- No em dashes in either doc.
- Lines over 90 characters remain only for markdown tables, long sbt commands, a resource path and one link.
- The documented sbt commands were not replayed, and both docs say so.

ASSUMPTIONS:
- The ER-J1 and ER-J2 names, `LocalMatchRunner` in test scope and `MatchJob.main` as the EMR entry come from the T2 report only. I did not open the sources.
- The two-test LeakageSuite is cited as existing. I did not verify what it asserts.

RISKS:
- The v1b API is described by file name only. I read the step 03b report only for structure (it was not required reading), and no v1b decision counts appear anywhere.
- The "recall and unresolved-rate figures are the only ones this gold set supports without caveat" wording in docs/entity-resolution.md is my reading of decision 04. The reviewer should check it, since the 4 work DOI conflicts also depend on labels.
- The evaluation section reproduces numbers by hand copy from output-er-evaluation.md, so the reviewer should re-check them.
- The rules table is condensed. R-R4 and the other rules are summarized from the T2 report, not from the source.
- STATUS.md still carries an older "Half done" line that mentions N4 and the Phase 04 items. I left it unchanged.

MANUAL ACTION REQUIRED: none for this task. Open item 3 (target/** ownership gap) and N4 are still the human's.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: Review 1, a fresh code-reviewer on the whole Phase 05 diff (review-report.md). Orchestrator saves this report as docs/agent-runs/phase-05/step-04-documentation-engineer-report.md.

---

## review-report.md

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, effort high (code-reviewer default in `.agents/models.json`)

# Phase 05 Review 1

**Verdict: APPROVE WITH CARRIED-FORWARD ITEMS.** In role terms that is APPROVE. There are no CRITICAL or MAJOR findings and `sbt check` is green. I found 6 MINOR and 4 NIT issues. I recommend fixing the documentation items (MINOR-3 to MINOR-6) in repair round 1. I recommend fixing MINOR-2 in repair round 1 or carrying it forward. MINOR-1 has to wait for a future policy version: fixing it changes er-v1's behaviour.

## What I verified

- **`sbt check` passed.** It exited 0 when I ran it once at 11:14 to 11:16.
  - Lines from the log: `Passed: Total 94`, `Passed: Total 98`, `Passed: Total 106` (entityResolution), `Passed: Total 68`.
  - `markTestRun: 14 project test runs passed`, `[success] Total time: 159 s`.
  - No sbt or java was running before I started (checked with `ps`). The harness moved the run to the background at 180 s. I started nothing else and read the output once, after the completion notice.
- **Ownership is clean.**
  - `git status` shows changes only in: `build.sbt` (build-engineer), `modules/entity-resolution/**` and `tests/module-unit/entity-resolution/**` (entity-resolution-engineer), `docs/entity-resolution.md`, `modules/entity-resolution/MODULE.md` and `STATUS.md` (documentation-engineer), `docs/agent-runs/phase-05/**` (orchestrator) and `datasets/gold/` (human).
  - Nothing changed in `modules/core`, `modules/hadoop`, `project/**` or the control plane.
  - The `build.sbt` diff is the entityResolution stanza only (12 lines). Versions come from `V.hadoop` and `V.slf4j`; `project/Versions.scala` is unchanged.
- **No leakage.**
  - The gold file `datasets/gold/er-gold-v1.csv` was last modified at 10:33:39, and the `datasets/` folder at 10:28.
  - Every matching source and resource is older than the gold file: policy JSON 09:39, `MatchPolicy.scala` 09:53, `er-eval-v1.json` (the sensitivity grids) 09:45.
  - The decision run `run-02cecabcf3e68312` started at 16:01:05Z (10:01 local, before the gold file existed).
  - Only `Evaluation.scala` (10:41) and `EvaluationSuite.scala` (10:40) changed after the gold file. That matches the T3 report's claim.
  - Hashes match the reports: policy `82ce9ae5…`, prompt `fbcf4b13…`, eval config `11031f93…`, review queue `f7520475…`, gold file `7fdd7923…`.
  - No matching code refers to gold paths. `LeakageSuite` scans the `scholarchain.er` package and the resources. The three entry points refuse any `datasets/gold` path.
  - v1b selection (`ReviewSupplement`) reads source records, J04 block keys and the upstream `same_source_cluster` feature. It reads no er-v1 score, outcome or decision.
  - Sensitivity is reported only, and no er-v1 file changed after the labels existed.
- **Labels.** I read the gold file (read-only):
  - The header and all 280 pair_ids, in order, equal the review queue.
  - 0 cells differ outside `label` and `note`; 0 notes.
  - Counts: researcher 153 MATCH, 13 NON_MATCH, 39 UNSURE; work 75 MATCH.
  - The 13 NON_MATCH rows cover 4 names (Dickinson 6, Sapiro 3, Shokoufandeh 3, Favaro 1).
  - Nothing shows an agent writing to it. EvaluateMain only reads the gold file and refuses an output path under `datasets/gold`.
- **Provenance and run outputs.** I read all 347 decisions of the run:
  - Every decision has `provenance.runId = run-02cecabcf3e68312`, `policyVersion = er-v1`, and `sourceRecordId` set to the candidate ID.
  - There are 347 audit lines, all with one `decidedAt`, `upstreamRunId run-6d1175dbfdb874a9` and adjudicator mode OFF.
  - The run manifest holds the policy sha, thresholds, feature, normalization, prompt and model versions, the budget and the cache sha, and no timestamp.
  - Reason counts match the docs exactly: 192/44/20/11/5 for researchers, 71/4 for works.
  - The summary gives 347 decisions, by path 130/20/197/0, and unresolved rate 0.01440922.
- **The 4 work CONFLICTs are genuine.** In each pair, one side carries a Springer DOI under `978-0-387-31439-6_*` and the other under `978-3-030-63416-2_*` (two editions). The conflict rule works as designed.
- **Absent empty outcome folders** (`rejected/`, `adjudications/`, `rejects-v1/`) are handled:
  - `EvaluateMain.readDecisions` (EvaluateMain.scala:55-58, 74) treats a missing outcome folder as empty, and only if `_SUCCESS` exists.
  - `MatchJob.lines` and `readCache` treat a glob with no match as empty.
  - There is no other reader yet; see NIT-4.
- **Evaluation numbers.** I recomputed them from `target/er-eval/er-evaluation.json` with a Wilson implementation of my own. All match `output-er-evaluation.md`, `docs/entity-resolution.md` and MODULE.md:
  - researcher P 0.938 [0.889, 0.966], R 0.987, F1 0.962
  - work P 1.000 [0.949, 1.000], R 0.947
  - combined [0.922, 0.976] and [0.944, 0.988]; 0/13 gives [0, 0.228]; 10/13 gives [0.497, 0.918]; R-R5 [0.354, 0.848]
  - the sweep has 42 points, with the er-v1 row at TP 151, FP 10, unresolved 5.
- **Correctness against the work order.** These are implemented as specified:
  - exact-ID rules for DOI (prefixes, percent-encoding, preprint and arXiv DOIs), arXiv, ORCID (check digit), OpenAlex, S2 sha and corpus forms, and repo URLs;
  - the normalization list and every listed feature (citation features are always 0, a known contract gap);
  - the versioned, pinned policy with separate thresholds that refuses retuning;
  - conflict rules R-R0/R-R2/R-W0/R-W1;
  - an LLM band limited to R-R9/R-W9 scores in [0.7, 0.9);
  - a strict reply schema: exact keys, the three decisions, IDs equal to the candidate's, reason codes without digits, cited fields only from the request;
  - the four-part cache key, the four outcome outputs, the review queue, and the metrics per kind and per path with error analysis and sensitivity.
- **Tests.** I read MatchPolicySuite, AdjudicationSuite with ResolverSuite, MatchJobSuite and LeakageSuite whole. They are fair and meaningful:
  - expected outcomes are derived by hand from the weights;
  - refusal paths, idempotence, run-ID reproducibility and cache replay (0 calls, 1 hit) are covered;
  - I saw no bent assertion.

## Findings, ranked

**MINOR-1. DBLP homonym detection rejects pairs that differ only in case, accents or spacing.**
- Where: `modules/entity-resolution/src/main/scala/scholarchain/er/Features.scala:192-201`. `ReviewSupplement.scala:217-220` has the same logic, but v1b is unused.
- The rule flags a pair whenever the two trimmed raw DBLP strings differ (`x != y`) but are equal after removing the homonym number and folding. It never checks that a homonym number was actually there.
- Failure input: two DBLP mentions with no ORCID, "José Silva" and "Jose Silva". The same happens for "Wei Wang" against "Wei Wang 0001", or a double space inside a name.
  - Effect: `dblp_homonym_distinct = 1`, so R-R3 decides REJECTED on the DETERMINISTIC_BIBLIOGRAPHIC path with confidence 0.02 and reason `DBLP_HOMONYM_DISTINCT`.
  - DBLP XML prints names as they appear in the paper, including alias spellings, so these pairs can be the same person.
- Impact: the pair is wrongly split and given a definite negative. It does not over-merge. It did not occur on this sample (0 REJECTED).
- Owner: entity-resolution-engineer.
- Repair brief:
  - Require both sides to carry a 4-digit suffix and the suffixes to differ. Compare the unsuffixed strings with `fold`.
  - This changes er-v1 behaviour, so it needs `er-features-v2` and policy `er-v2`, decided without looking at the labels. Do not edit er-v1 in place: carry this forward.
  - Tests that must stay green: MatchPolicySuite's homonym test and MatchJobSuite.

**MINOR-2. Answers replayed from the adjudication cache are never re-validated.**
- Where: `Adjudication.scala:263-272` and `297-298`, used by `Resolver.scala:154-176`.
- `AdjudicationCache.parse` only checks that a line's key matches its own fields. On a cache hit, `rec.output` is used as is.
- Failure input: a line in an `--adjudication-cache` file with a correct key, output `{"decision":"ACCEPT","confidence":0.99,"reasonCodes":["see 10.1000/x"],"citedFields":[]}`, and sourceId/targetId of any value.
  - Effect: an LLM_ADJUDICATED ACCEPTED decision is written with the reason code `LLM_see 10.1000/x`.
  - That bypasses the strict-schema and no-new-identifier guarantees that `validate` enforces on fresh replies.
- Impact: a tampered or legacy cache, and later the Phase 07 DynamoDB cache, can inject unvalidated decisions.
- Owner: entity-resolution-engineer.
- Repair brief:
  - On a hit, re-run `Adjudication.validate(write(rec.output), request)`. Treat a failure as `LLM_INVALID_OUTPUT` and do not use it.
  - Add a test with a forged-but-keyed cache line.
  - er-v1 itself does not change. Tests that must stay green: AdjudicationSuite and the MatchJobSuite cache replay.

**MINOR-3. The docs misstate when R-R2 gives CONFLICT and when it gives REJECTED.**
- Where: `docs/entity-resolution.md:176-177`.
- The text says an ORCID disagreement (R-R2) "is a CONFLICT when an identifier on the same pair agrees, otherwise a REJECTED".
- The code (`MatchPolicy.scala:191-210`) and the page's own rules table (line 138) say R-R2 is CONFLICT unless the score is at or below reject.
- Example: ORCID disagrees, no other ID agrees, score 0.9. The doc predicts REJECTED; the code gives CONFLICT, which MatchPolicySuite lines 94-97 assert.
- Owner: documentation-engineer.
- Repair brief: say R-R2 is CONFLICT, or REJECTED when the score is also at or below 0.35. Keep the "ID agrees" condition for R-R3 only.

**MINOR-4. "Fixed and committed before any gold label existed" is not supported.**
- Where:
  - `docs/entity-resolution.md:115`;
  - STATUS.md, Note step 03 (T2), second bullet ("fixed before any label exists");
  - code comment `PolicyConfig.scala:71-72` (entity-resolution-engineer);
  - the same wording appears in the saved evidence `output-er-evaluation.md:27`, which the orchestrator should not edit.
- Evidence:
  - Nothing is git-committed; all module files are untracked.
  - The addendum scheduled the human's labeling for 09:00. Human decision 01 ("I am labeling the queue as is, now") was recorded at 09:27.
  - The policy resource was written at 09:39 and `MatchPolicy.scala` at 09:53.
- What is true:
  - er-v1 was fixed before the gold file existed (about 10:28 to 10:33).
  - No agent could read a label while authoring it: the orchestrator found `datasets/gold` absent after T2.
  - Nothing in er-v1 changed after that.
- Impact: none on leakage; this is an accuracy issue in the docs.
- Owner: documentation-engineer for the two docs. Code comment: entity-resolution-engineer, optional.
- Repair brief: replace the wording with the true statement above.

**MINOR-5. The docs claim recall is supported "without caveat", which no source says.**
- Where: `docs/entity-resolution.md:36-37`: "the recall and unresolved-rate figures are the only ones this gold set supports without caveat".
- Decision 04 says nothing about recall. Its premise (the labeler asked the wrong question for researcher pairs) also bears on the MATCH labels.
- Evidence that the MATCH labels were not judged consistently: 41 researcher MATCH rows share no title. The 4 work MATCH rows that er-v1 left as CONFLICT are different-edition DOIs.
- The documentation-engineer flagged this sentence itself.
- Owner: documentation-engineer.
- Repair brief: delete the sentence, or attribute it as an inference and add the caveat.

**MINOR-6. MODULE.md says `LocalMatchRunner` is in test scope.**
- Where: `modules/entity-resolution/MODULE.md`, Public API, the `MatchJob` bullet.
- `LocalMatchRunner` is in `src/main` (`MatchJob.scala:818`). Only the Hadoop runtime it needs is Test-scoped, which is why it runs via `Test/runMain`. As a result it ships in the job jar.
- Owner: documentation-engineer.
- Repair brief: correct the wording.

**NIT-1.** `EvaluateMain.scala:87-90` takes thresholds and the `isPolicy` mark from the module policy. It never checks that the decisions' `policyVersion` equals the loaded policy. An er-v2 run evaluated with er-v1 code would be labeled wrongly. Owner: entity-resolution-engineer. Fix: refuse the run on a mismatch.

**NIT-2.** `LocalMatchRunner.localConf` (`MatchJob.scala:823`) puts `hadoop.tmp.dir` inside the output root, so `target/er-match-run/_local-tmp/` stays in the output tree. Owner: entity-resolution-engineer.

**NIT-3.** `LeakageSuite` is a string tripwire over the top-level package only. It would not catch a path built from pieces, and it does not scan `review/`. It is acceptable as a guard but is not proof; the docs cite it as "exists for this", which is accurate. No action is required.

**NIT-4 (carried forward to Phase 07).** Empty outcome folders are absent rather than empty. The evaluation reader handles this, but a future loader must also treat an absent folder as empty only when `_SUCCESS` exists. Owners: storage-engineer when it writes the loader, or entity-resolution-engineer if it chooses to always create empty part files. MODULE.md already records this.

**For the human (not an agent finding).** `datasets/gold/er-gold-v1.csv` has mode `-rwxr-xr-x`. git would record it as 100755. Consider `chmod 644` before committing.

## Process items (report only)

- **sbt backgrounding.** Confirmed again in this review: the harness moved my 600 000 ms foreground `sbt check` to the background at 180 s. This is the fifth time this phase. The machine-limit rule ("no background shells") cannot be met by agents. It needs a harness or factory-engineer decision; the workable practice so far is to start nothing else and read the output once.
- **guard-dangerous false positives** on the literal test-marker task name. These are reported by the step 01, 02, 03b and T3 reports and by documentation-engineer. I did not observe one myself. It belongs to factory-engineer in Phase 11.
- **`target/**` ownership gap.** Verified: `docs/agents.md` has no territory row for `target/**`. guard-ownership therefore blocks a file-tool write there for every agent (T3's `output-er-evaluation.md` copy), while sbt-run outputs under `target/` pass through the shell unguarded. This is a matrix decision for the human: either factory-engineer adds a row such as `<module owner> allow target/er-*/**`, or the human accepts `docs/agent-runs/` as the canonical evidence location (current practice).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 05 Review 1 (fresh context): whole uncommitted Phase 05 diff (build.sbt stanza, modules/entity-resolution/**, tests/module-unit/entity-resolution/**, docs/entity-resolution.md, MODULE.md, STATUS.md, docs/agent-runs/phase-05/**), with target/ outputs as evidence. The orchestrator saves this as docs/agent-runs/phase-05/review-report.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status --short [--ignored], git diff --stat, git diff build.sbt / MODULE.md / STATUS.md
- find ... -printf mtimes over the module, tests and docs; ls -la --time-style=full-iso of datasets/gold, target/, target/er-eval, target/er-review
- sha256sum of the gold file, review queue, policy, prompt and eval config
- python3 -I read-only summaries: the decision run (347 decisions: provenance, reasons, audit), the profiles of the 4 conflict pairs, er-evaluation.json with Wilson recomputation, gold vs queue comparison (paths passed as arguments)
- ps (no sbt or java running)
- source toolchain/env.sh && sbt -batch check (exit 0; moved to the background at 180 s by the harness, output read once after completion)
TEST RESULTS: sbt check exit 0: Passed 94 / 98 / 106 (entityResolution) / 68; markTestRun: 14 project test runs passed; [success] Total time: 159 s, completed Nov 23, 2026, 11:16:54 AM.
VALIDATION RESULTS: APPROVE (APPROVE WITH CARRIED-FORWARD ITEMS). No CRITICAL or MAJOR.
- MINOR-1 Features.scala:192-201 DBLP homonym false REJECT on case, accent or spacing variants and unsuffixed vs suffixed names (entity-resolution-engineer; needs er-v2, carry forward)
- MINOR-2 Adjudication.scala:263-298 cached adjudications not re-validated (entity-resolution-engineer)
- MINOR-3 docs/entity-resolution.md:176-177 R-R2 misstated (documentation-engineer)
- MINOR-4 "fixed and committed before any label existed" unsupported: docs/entity-resolution.md:115, STATUS.md note step 03, PolicyConfig.scala:71 (documentation-engineer; code comment entity-resolution-engineer)
- MINOR-5 docs/entity-resolution.md:36-37 unsupported "recall without caveat" (documentation-engineer)
- MINOR-6 MODULE.md "LocalMatchRunner (test scope)" wrong (documentation-engineer)
- NIT-1 EvaluateMain does not check the decision policy version against the loaded policy; NIT-2 _local-tmp left in the output root; NIT-3 LeakageSuite is a string tripwire only; NIT-4 absent-folder handling must carry to the Phase 07 loader
- Test fairness: the suites I read are FAIR; no bent tests found.
ASSUMPTIONS:
- File mtimes are taken as the authoring order. They can be reset, but they agree with the reports and the orchestrator checks.
- human-decision-0N.md are orchestrator transcriptions of the human's words, which docs/agents.md allows in docs/agent-runs/**.
- The verdict label "APPROVE WITH CARRIED-FORWARD ITEMS" follows the work order; in role terms it is APPROVE.
RISKS (not reviewed, or reviewed only in part):
- Read only in part: TextSimilarity.scala; ErFixtures, ErNormalizeSuite, FeaturesSuite, EvaluationSuite, ReviewQueueSuite and ReviewSupplementSuite.
- Not compared against ~/ref-phase-05: whether files were copied unread, and the "no reference-repository numbers" claim. I only checked that every doc number traces to a saved Phase 05 report or to the target/ outputs.
- No baseline exists for the T1b visibility-only change to ReviewQueueMain.scala (the file is untracked).
- Distributed behaviour beyond LocalJobRunner was not reviewed: EMR, the grouping comparator with non-ASCII endpoint IDs, the data growth of ORCID aggregates.
- Multi-valued DOI sides from URLs (a chapter with a link to the book DOI) can produce R-W0 or R-W1 conflicts. Not tested on the sample.
- No mutation testing (forbidden); no other sbt run.
MANUAL ACTION REQUIRED:
- Human: decide the target/** matrix gap.
- Human: consider chmod 644 on datasets/gold/er-gold-v1.csv before committing.
- Human: decide whether MINOR-1 goes to a future er-v2.
- Human: N4 is still open.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Repair round 1. documentation-engineer takes MINOR-3 to MINOR-6. entity-resolution-engineer takes MINOR-2 and optionally NIT-1, NIT-2 and the PolicyConfig comment; MINOR-1 is carried forward. Then a fresh code-reviewer re-check (review-report-02.md), then the human gate.
```

---

## repair-01-documentation-engineer.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default effort)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 repair round 1, task R1-2. Fix MINOR-3, MINOR-4 (docs part), MINOR-5 and MINOR-6 of Review 1, and update STATUS.md. Save this report as repair-01-documentation-engineer.md.
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/docs/entity-resolution.md
- /home/ajcreator/scholarchain/modules/entity-resolution/MODULE.md
- /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN: read-only grep, sed and git diff checks. No sbt.
TEST RESULTS: none run; docs only.
VALIDATION RESULTS:
- MINOR-3: docs/entity-resolution.md "Conflict detection" now says R-R2 is CONFLICT, or REJECTED when the score is also at or below 0.35. R-R3 keeps the "an identifier agrees" condition. I read MatchPolicy.scala lines 191-210 (R-R2) and 211-230 (R-R3) and they match. The rules table already said this for R-R2 and I left it unchanged.
- MINOR-4 (docs part):
  - docs/entity-resolution.md "Policy er-v1" now says: er-v1 was fixed before the gold file existed (about 10:28 to 10:33), was not changed afterwards, and no agent could read a label while authoring it. It also says nothing is git-committed yet.
  - STATUS.md Note step 03 and the Phase rules bullet were reworded the same way.
  - The "committed ... before any label exists" wording is gone from those three places.
- MINOR-5: the "recall and unresolved-rate figures ... without caveat" sentence is deleted. I added "Decision 04 does not say which figures, if any, are free of caveat." That adds no claim beyond decision 04. I did not add the labeling-question caveat on MATCH labels to the page, because decision 04 does not state it.
- MINOR-6: MODULE.md now says LocalMatchRunner is in src/main and ships in the job jar. Only the Hadoop runtime it needs is Test-scoped, which is why it runs through Test/runMain.
- STATUS.md:
  - Review 1 is recorded as done: APPROVE WITH CARRIED-FORWARD ITEMS, 6 MINOR and 4 NIT. It includes the one sbt check, which exited 0 per the review report.
  - R1-1 is listed as running with its scope, and its fixes are not described as done. R1-2 has a note.
  - A "Carried forward from Review 1" list holds MINOR-1 (a future er-v2), NIT-3 (no action), NIT-4 (Phase 07 loader, storage-engineer), the human items (target/** matrix gap, chmod 644 on the gold file before commit, MINOR-1 decision, N4) and the process items (sbt backgrounding at 180 s, guard-dangerous false positives on the test-marker name).
  - "Where things stand" and "Current state (phase 05)" now say the next task is Review 2.
- Line length: the lines I added are within 90 characters, except those inside tables. Other lines over 90 were already in MODULE.md (command lines, a path and one header line).
ASSUMPTIONS:
- The "10:28 to 10:33" gold-file time comes from the work order and the review report, which cite file mtimes. I did not check it myself.
RISKS:
- docs/agent-runs/phase-05/output-er-evaluation.md:27 still has the old "fixed before any label exists" wording. It is saved evidence in the orchestrator's territory, and I did not edit it.
- The PolicyConfig.scala:71-72 comment belongs to entity-resolution-engineer (R1-1). I did not verify whether it is fixed.
- The documented sbt commands were not replayed, per the work order.
- The Review 1 sbt numbers in STATUS.md come from the review report and I did not re-run them.
MANUAL ACTION REQUIRED: none for this task. The human items are listed under "Carried forward from Review 1" in STATUS.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R1-1 finishes (entity-resolution-engineer). After that, documentation-engineer records its result in STATUS.md, then Review 2 (fresh code-reviewer).

---

## repair-01-entity-resolution-engineer.md

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, effort high (entity-resolution-engineer default in `.agents/models.json`)

Summary: All four items are fixed and tested. `sbt fmt check` ran once and exited 0. entityResolution now has 112 tests, up from 106, and all pass. The er-v1 policy resource is unchanged; its sha256 is still 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff. I did not save this report as `repair-01-entity-resolution-engineer.md`: `docs/agent-runs/**` is outside my territory, so the orchestrator needs to save it.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05, repair round 1, task R1-1. Fixes MINOR-2 (re-validate adjudication cache hits), NIT-1 (EvaluateMain refuses a run made under another policy version), NIT-2 (LocalMatchRunner puts Hadoop scratch outside the output root) and the PolicyConfig.scala:71-72 comment (MINOR-4). MINOR-1 is not fixed (carried to er-v2). er-v1's behaviour, policy resource, features, thresholds and prompt are unchanged.
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/Adjudication.scala (new `Adjudicator.recheck`, used on every cache hit; updated AdjudicationCache doc comment)
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/PolicyConfig.scala (doc comment only)
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/eval/EvaluateMain.scala (new `samePolicy`, called in `run` before evaluation)
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/MatchJob.scala (LocalMatchRunner only: new `scratchDir`, `localConf` uses it, new `run(args, job)` that removes the scratch folder, `main = sys.exit(run(args))`)
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/AdjudicationSuite.scala (+2 AdjudicationSuite tests, +1 ResolverSuite test)
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/MatchJobSuite.scala (+2 tests; the `fake` run became a shared lazy val, and the existing replay test is otherwise unchanged)
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/EvaluationSuite.scala (+1 test)
COMMANDS RUN:
- ps aux | grep sbt/java: no sbt or java was running before the sbt run.
- sha256sum of the policy JSON and the prompt: 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff and fbcf4b136e72b9d1edede0060be0d4543f2459a83c0e119252284cd2ca291ac5, the same before and after.
- source toolchain/env.sh && timeout 590 sbt -batch fmt check: the one sbt invocation, exit 0. The harness moved it to the background at 180 s. I started nothing else and read the log once, after the completion notice.
- git status --short, plus a find of files newer than the task start. My 7 files changed; see RISKS 5 for the others.
TEST RESULTS (sbt fmt check, exit 0, [success] Total time: 165 s, completed Nov 23, 2026, 11:28:02 AM):
- Passed: Total 94 (core), 112 (entityResolution, was 106), 98 (hadoop), 68 (adversarialTests). Failed 0, Errors 0 in each.
- The test-marker task: 14 project test runs passed.
- Per suite:

| Suite | Tests now | Before | Added |
|---|---|---|---|
| AdjudicationSuite | 12 | 10 | 2 |
| ResolverSuite | 8 | 7 | 1 |
| MatchJobSuite | 9 | 7 | 2 |
| EvaluationSuite | 14 | 13 | 1 |

- The MatchJobSuite cache replay test ("fake mode with a budget ... the cache replays it without a call") is still green: 0 LLM_CALLS, 1 LLM_CACHE_HIT, identical decision.
VALIDATION RESULTS:
- Policy er-v1 is unchanged. Policy resource sha256 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff (checked). Thresholds accept 0.9, reject 0.35, LLM band [0.7, 0.9) (pinned, unchanged). Features er-features-v1. Prompt er-adjudication-v1, sha256 fbcf4b13...ca291ac5, unchanged.
- Model: adjudication is off in the sample run, so no model was used. Tests use the fake (fake-deterministic-v1) and scripted ports.
- No gold-set evaluation was rerun, as instructed: target/er-match-run and target/er-eval were not regenerated. The confusion matrix, per-path precision, recall and F1, and the unresolved rate (0.0144) are the T3 numbers in docs/agent-runs/phase-05/output-er-evaluation.md. None of these fixes can change them: the sample run is OFF mode with no cache, and the decisions are policy er-v1, which EvaluateMain loads.
- MINOR-2: on a cache hit, `Adjudicator.recheck` first checks that the record's cacheKey, inputHash, candidateId, promptVersion, modelVersion and policyVersion all equal the current request and port. It then runs `Adjudication.validate(write(hit.output), request)`, the same strict check as a fresh reply. If either step fails, the result is `Skipped("LLM_INVALID_OUTPUT", "cached reply refused: ..." or "cached record does not match the request: ...")`. The cached reply is not used, no call is made, and the case stays UNRESOLVED on the PROBABILISTIC path with reason LLM_INVALID_OUTPUT.
- MINOR-2 tests:
  - AdjudicationSuite: 8 forged, correctly keyed lines, each accepted by `AdjudicationCache.parse` and each refused: reasonCodes ["see 10.1000/x"], a source ID that is not the candidate's, a target ID that is not the candidate's, an uncited field, an ACCEPT that cites nothing, confidence 1.5, decision MERGE, and empty reason codes. All make 0 calls. The same line with a valid output still replays as a cache hit.
  - AdjudicationSuite: 5 in-memory records filed under the request's key that do not match its fields, all refused.
  - ResolverSuite: a forged line gives UNRESOLVED / PROBABILISTIC / LLM_INVALID_OUTPUT. No "10.1000" reason code, no R-LLM rule, no adjudication record, 0 calls, and a valid core decision.
  - MatchJobSuite end to end: a forged `adjudications/part-r-00000` passed through `--adjudication-cache` gives the band case UNRESOLVED with LLM_INVALID_OUTPUT. No LLM_ADJUDICATED decision in the run; counter LLM_INVALID_OUTPUT=1, LLM_CALLS=0, LLM_CACHE_HITS=0; no adjudications output.
- NIT-1: `EvaluateMain.samePolicy` refuses a run when any decision's `policyVersion` or `provenance.policyVersion` differs from the loaded policy. The error is "decision run is policy er-v2, but this evaluation loads policy er-v1", and nothing is written to `--out`. Tested on a full er-v2 run folder (decision IDs recomputed so the run passes core validation) and on a mismatch carried only in provenance.
- NIT-2: `hadoop.tmp.dir` is now the sibling `<output>-tmp` (absolute, normalized; a trailing slash is ignored). `LocalMatchRunner.run` deletes the scratch folder afterwards only if this run created it; a folder that already existed is left alone.
- NIT-2 test (MatchJobSuite): the output root of a real run holds exactly `_er-profiles`, `match-decisions` and `run-manifests` (no `_local-tmp`). The scratch folder is gone after the run, and a scratch folder that existed before the run keeps its contents.
- MINOR-4 comment now reads: "Its weights and rule parameters were set by hand; er-v1 was fixed before the gold file existed and was not changed afterwards." The word "committed" is not used.
- Representative errors: unchanged from T3. No new evaluation was run.
ASSUMPTIONS:
- A refused cache hit is not followed by a fresh call (the work order says do not use it). The case stays UNRESOLVED until the cache is fixed or a run is made without it.
- For NIT-2 the sibling folder `<output>-tmp` was chosen over a JVM temp folder: it is deterministic, testable through `localConf`, and is deleted only when this run created it.
- I did not bump `MatchJob.LogicVersion` (er-job-v1). For every cache line the old code accepted as valid, outputs are byte-identical. Only tampered or invalid cache lines now produce a different outcome, and no run with such a cache exists. Bumping it would change the documented run ID run-02cecabcf3e68312 on any rerun.
RISKS:
1. Not done, by instruction: MINOR-1 (DBLP homonym false REJECT; needs er-features-v2 and er-v2); no regeneration of target/er-match-run or target/er-eval; no change to datasets/gold/** or the review CSVs.
2. State left behind: target/er-match-run/_local-tmp still exists from the 10:01 run made before this fix. I did not delete it, because that output must not be regenerated or touched. Any new local run puts scratch in `<output>-tmp` and removes it afterwards.
3. LogicVersion was not bumped (see ASSUMPTIONS). A reviewer may prefer er-job-v2; that would change future run IDs, so it is a decision for the orchestrator or human.
4. The Skipped path records no cache key in the audit for a refused hit. The decision carries only the reason code LLM_INVALID_OUTPUT, as for a refused fresh reply, and the cache file's sha is in the run manifest. The ResolverSuite test asserts this behaviour, so changing it means updating that test.
5. During this task, files outside my territory also changed: STATUS.md, docs/entity-resolution.md, MODULE.md and docs/agent-runs/phase-05/{output-er-evaluation.md, repair-01-documentation-engineer.md, review-report.md}. This was the parallel documentation repair, not me. `sbt fmt` reformatted only my files: only my 7 files changed in source and tests.
6. Notes for modules/entity-resolution/MODULE.md (documentation-engineer to apply):
   - Public API, `Adjudication` bullet: add `Adjudicator.recheck`. Every cache hit (preloaded or in-run) is re-validated against the current request with the same strict validator as a fresh reply. A hit that fails is not used and yields LLM_INVALID_OUTPUT with no call.
   - Public API, `MatchJob` bullet: `LocalMatchRunner` now also exposes `scratchDir(output)` (the sibling `<output>-tmp`) and `run(args, job)` (exit code; removes the scratch folder when the run created it). `main` calls `run`.
   - Public API, eval bullet: add `EvaluateMain.samePolicy`. EvaluateMain refuses a decision run whose decisions name a policy version other than the loaded policy (er-v1).
   - Done: last `sbt check` (R1-1) exit 0, entityResolution 112 tests, 14 project test runs.
   - Known issues: add that target/er-match-run/_local-tmp predates the NIT-2 fix. Keep the "cache read whole into memory" item, and note that the Phase 07 DynamoDB cache must keep the re-validation on hit.
   - docs/entity-resolution.md lines 219-224 (cache paragraph): add that every cache hit is re-validated with the reply validator and the request's IDs and versions, and that a refused hit gives LLM_INVALID_OUTPUT with no call; MatchJobSuite also checks a forged, correctly keyed line.
   - docs/entity-resolution.md near line 372 (EvaluateMain): add the policy-version refusal.
   - If the docs or hadoop notes say ER scratch goes to `<output>/_local-tmp`, change that to `<output>-tmp` for ER only. The hadoop module's LocalRunner still uses `<output>/_local-tmp`; that is hadoop-engineer's and was not changed.
MANUAL ACTION REQUIRED:
- Orchestrator: save this report as docs/agent-runs/phase-05/repair-01-entity-resolution-engineer.md (outside my territory).
- Human or orchestrator: decide whether to bump MatchJob.LogicVersion (RISKS 3), and whether to remove the stale target/er-match-run/_local-tmp (RISKS 2).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer applies the MODULE.md and docs wording in RISKS 6, then a fresh code-reviewer re-checks (review-report-02.md), then the human gate.
```

---
Orchestrator check (observed after the report): policy resource sha256 starts
82ce9ae5c81d998b (unchanged); no sbt or java process running; datasets/gold holds only
er-gold-v1.csv; tracked changes are STATUS.md, build.sbt and modules/entity-resolution/MODULE.md
(the rest of the Phase 05 work is untracked).

---

## repair-01b-documentation-engineer.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier (documentation-engineer default)

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05, repair round 1, task R1-3. Apply the RISKS 6 wording from the R1-1 report to modules/entity-resolution/MODULE.md and docs/entity-resolution.md, checking each claim against the source. Then update STATUS.md.
FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/MODULE.md
- /home/ajcreator/scholarchain/docs/entity-resolution.md
- /home/ajcreator/scholarchain/STATUS.md
COMMANDS RUN: grep of the source files below (exit 0); ls of target/er-match-run/_local-tmp (exists); python3 edit script; awk line-length check.
TEST RESULTS: none run. No sbt, per the work order. The sbt figures (exit 0, entityResolution 112 tests, 14 project test runs) are copied from the R1-1 report and were not re-observed.
VALIDATION RESULTS:
- Each claim was checked by reading source only:
  - Adjudication.scala: `recheck` at line 298, called on cache hits at line 323, and a failure gives `Skipped("LLM_INVALID_OUTPUT", ...)` at lines 324 and 334.
  - MatchJob.scala: `scratchDir` is the sibling `<output>-tmp` (lines 819-824), `localConf` sets `hadoop.tmp.dir` from it (line 830), and `run(args, job)` is at line 836. `main` is `sys.exit(run(args))` at line 861.
  - eval/EvaluateMain.scala: `samePolicy` is at line 86 and is called in `run` before `Evaluation.evaluate` (line 105).
  - hadoop `LocalRunner.scala` line 21 still uses `<output>/_local-tmp`, so it is unchanged.
  - `target/er-match-run/_local-tmp` exists.
- Not verified by me: that `run` removes the scratch folder only when this run created it. The report states it, and I did not read the body at lines 836-860.
- MODULE.md: added `Adjudicator.recheck`, the `LocalMatchRunner` `scratchDir` and `run` text, `EvaluateMain.samePolicy`, and the Done line (R1-1, 112 tests, 14 project test runs, LogicVersion not bumped). Under Known issues I added the Phase 07 DynamoDB cache re-validation note and the stale `_local-tmp` note.
- docs/entity-resolution.md: added a cache re-validation bullet, the scratch folder sentence (ER only) and the `samePolicy` refusal after the Reproduce command.
  - The docs page had no "ER scratch goes to `<output>/_local-tmp`" text, so nothing needed correcting there.
- STATUS.md:
  - The Phase 05 summary now says repair round 1 is done.
  - The task table marks R1-1 and R1-2 done and has a new R1-3 row.
  - New note R1-1 and note R1-3.
  - The Current state section has an update block with the open human items.
- Lines over 90 characters: none are new. The ones awk flagged are existing links, commands and table rows (MODULE.md 12, 56, 79, 82, 84, 87; the docs page 140, 146, 147, 203).
ASSUMPTIONS: The R1-1 report is accurate wherever I did not read the code. The `target/er-match-run/_local-tmp` "10:01 run" timestamp is copied from the report.
RISKS:
- In STATUS.md the older "Current state (phase 05)" bullets still say "Task running: R1-1".
  I added an update block above them that says so rather than rewriting them.
  The older Tasks done list also still lacks R1-1 and R1-3.
- docs/entity-resolution.md still says "Nothing is git-committed yet" in the policy section (pre-existing).
- I did not edit docs/agent-runs/**.
- The report is not saved as repair-01b-documentation-engineer.md; the orchestrator needs to save it (outside my territory).
MANUAL ACTION REQUIRED (human, from R1-1):
- Decide whether to bump MatchJob.LogicVersion (er-job-v1, left unchanged; a bump changes the run ID of any rerun).
- Decide whether to remove the stale target/er-match-run/_local-tmp.
- Orchestrator: save this report as repair-01b-documentation-engineer.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review 2 (fresh code-reviewer, review-report-02.md), then the human gate.
```

---

## review-report-02.md

HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, effort high (the code-reviewer default in `.agents/models.json`)

# Phase 05 Review 2 (fresh re-check after repair round 1)

**Verdict: APPROVE WITH CARRIED-FORWARD ITEMS.** In role terms this is APPROVE.

- Every Review 1 finding meant for repair round 1 is fixed. MINOR-1, NIT-3 and NIT-4 are correctly carried forward.
- The repairs caused no regression. The er-v1 policy resource is unchanged, matching behaviour is unchanged, the new tests are fair, there is no leakage, and ownership is clean.
- `sbt check` exited 0, with entityResolution at 112 of 112 tests.
- New findings: 3 MINOR, all documentation or STATUS accuracy, and 3 NIT. None of them blocks the gate. Since there is no second repair round, all of them go to the human.

## 1. Status of each Review 1 finding

| Finding | Status | Evidence |
|---|---|---|
| MINOR-1: DBLP homonym false REJECT | CARRIED (future er-v2) | The code is unchanged: `Features.scala:192-201` still flags any `x != y` raw pair whose stripped, folded forms are equal. `Features.scala` and `MatchPolicy.scala` were last modified at 09:37 and 09:53, before the gold file (10:33:39). STATUS.md:684 carries it. See new finding MINOR-B: MODULE.md and the docs page do not mention it. |
| MINOR-2: cache hits not re-validated | FIXED | `Adjudication.scala:298-314` adds `recheck`. It checks the key, inputHash, candidateId and the prompt, model and policy versions, then runs `Adjudication.validate(write(hit.output), r)`. `Adjudication.scala:321-325` calls it on every hit; a failure gives `Skipped("LLM_INVALID_OUTPUT")` and makes no call. Tests: `AdjudicationSuite.scala:138-186` (8 forged lines, each with a correct key), `:188-249` (misfiled records), the ResolverSuite test at `:389-413`, and the end-to-end `MatchJobSuite.scala:189-217`. |
| MINOR-3: R-R2 misstated in the docs | FIXED | `docs/entity-resolution.md:178-181` matches `MatchPolicy.scala:191-230`. R-R2 is CONFLICT, or REJECTED when the score is at or below reject. R-R3 is CONFLICT only when an ID agrees. |
| MINOR-4: "committed before any label" | FIXED | `docs/entity-resolution.md:114-117`, STATUS.md:497-498 and STATUS.md:549-551, and `PolicyConfig.scala:70-74` (comment) now carry the true statement. `output-er-evaluation.md:27` keeps the old text by design; its section 12 (lines 268-280) corrects it. A grep finds no "committed ... before any label" wording left in the docs, MODULE.md, STATUS.md or the sources. |
| MINOR-5: "recall without caveat" | FIXED | The sentence is removed. `docs/entity-resolution.md:36` now reads "Decision 04 does not say which figures, if any, are free of caveat." That adds no claim. |
| MINOR-6: LocalMatchRunner described as test scope | FIXED | `MODULE.md:49-50` now says it is in `src/main`, ships in the job jar, and only its Hadoop runtime is Test-scoped. `LocalMatchRunner` is at `MatchJob.scala:818`. |
| NIT-1: no policy-version check in EvaluateMain | FIXED | `EvaluateMain.scala:86-96` adds `samePolicy`, called at `:105` before evaluation and before any write. Tested by `EvaluationSuite.scala:366-404`, including the check that nothing is written to `--out`. |
| NIT-2: `_local-tmp` inside the output root | FIXED for new runs | `MatchJob.scala:822-830` puts scratch in the sibling `<output>-tmp`. `:836-850` deletes it only when this run created it. Tested by `MatchJobSuite.scala:219-237`. After my `sbt check`, `target/` holds no `*-tmp` folder. The old `target/er-match-run/_local-tmp` (from the 10:01 run) is still there; removing it is a human decision. |
| NIT-3: LeakageSuite is a string tripwire only | CARRIED, no action | Unchanged, as agreed. |
| NIT-4: absent outcome folders in a future loader | CARRIED to Phase 07 | `MODULE.md:137-139`, `MODULE.md:144` (the DynamoDB cache must keep re-validation on every hit), STATUS.md:686-687 and STATUS.md:702. |

## 2. Regression checks

- **Policy hash.** I ran `sha256sum` on `er-policy-v1.json` before and after `sbt check`. Both gave 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff. The prompt hash is fbcf4b13…ca291ac5, the eval config 11031f93…, and the gold file 7fdd7923… All are unchanged.
- **No change to matching behaviour.**
  - Only 4 source files changed in R1-1, all at 11:22: `Adjudication.scala`, `PolicyConfig.scala`, `EvaluateMain.scala` and `MatchJob.scala`.
  - `Features.scala`, `MatchPolicy.scala`, `Resolver.scala`, `ErNormalize.scala`, `ExactIds.scala` and the policy JSON were all last modified before the gold file existed.
  - `recheck` adds no new outcome for a legitimate cache. A hit whose key matches `keyOf(r)` and whose key matches its own fields (`AdjudicationCache.parse`) already has equal inputHash and versions. `inputJson` contains candidateId. Outputs were validated before they were cached, and validation depends only on the output and the request. So only tampered or misfiled records behave differently.
  - The sample run used OFF mode, so its decisions cannot change.
- **New tests are FAIR.**
  - Each new test asserts what the spec and the Review 1 brief require: a refused cache hit, no call, UNRESOLVED/PROBABILISTIC/LLM_INVALID_OUTPUT, no identifier leaks into reason codes, a policy-version mismatch is refused with nothing written, and scratch stays outside the output root.
  - The valid-cache replay test (`MatchJobSuite.scala:171-187`) is unchanged and green.
  - No assertion was bent to fit the code.
- **No leakage.** Searching `src/main` for "gold" outside the `eval` package finds only refusal guards (`ReviewQueueMain.isGoldPath` and its callers) and the PolicyConfig comment. No matching file imports `scholarchain.er.eval`.
- **Ownership is clean.** `git status --porcelain --ignored` shows changes only in `build.sbt` (build-engineer, unchanged since Review 1), the entity-resolution module and its tests (entity-resolution-engineer), `docs/entity-resolution.md`, `MODULE.md` and `STATUS.md` (documentation-engineer), `docs/agent-runs/phase-05/` (orchestrator) and `datasets/gold/` (human). Nothing changed in the control plane, `project/**`, core or hadoop.
- **`datasets/gold/**` was not touched by any agent.** It holds one file, `er-gold-v1.csv`, with mtime 10:33:39 (unchanged) and the same sha256.

## 3. New findings, ranked

No CRITICAL or MAJOR findings.

**MINOR-A. STATUS.md: the old "Current state (phase 05)" snapshot is stale and contradicts the update block above it.** Owner: documentation-engineer.
- STATUS.md:710-711 still says "Task running: R1-1 … its fixes are not recorded as done here".
- STATUS.md:706-708 ("Tasks done") lacks R1-1 and R1-3.
- STATUS.md:726-728 (the Git line) lists the untracked tests as only `ReviewQueueSuite.scala`. It omits `datasets/gold/`, `docs/entity-resolution.md`, the modified `MODULE.md` and 9 other test files.
- The note at STATUS.md:704 ("where it says R1-1 is running, it is done") limits the damage. Still, a fresh session that reads the bullets first gets a wrong picture.
- Brief: replace the snapshot with a single current list. Tasks done: through Review 2. Running: none. Next: the human gate. Half done: nothing. Git: the current `git status`.

**MINOR-B. MINOR-1 is missing from the module's Known issues, and the docs describe R-R3 by its intent rather than its behaviour.** Owner: documentation-engineer.
- `docs/entity-resolution.md:141` ("different DBLP homonym suffixes") and `:180` state the intent. The code (`Features.scala:192-201`) also fires on case, accent or spacing variants, and when only one side has a suffix.
- Example: two DBLP mentions "José Silva" and "Jose Silva" with no ORCID give `dblp_homonym_distinct = 1`, so R-R3 decides REJECTED.
- `modules/entity-resolution/MODULE.md` Known issues (lines 119-158) does not list it. It appears only in STATUS.md:684.
- The module notes are the hand-off for a future er-v2 author, so the gap will outlive STATUS.md.
- Brief: add a Known issue to MODULE.md and a short caveat under the rules table, both pointing to Review 1 MINOR-1.

**MINOR-C. A Phase 01 item scheduled for Phase 05 is not tracked in the Phase 05 list.** Owner: documentation-engineer for STATUS.md; factory-engineer for the fix (Phase 11 guard work).
- STATUS.md:42 (under "Carried forward from Phase 01", already in HEAD) says: "Phase 05: gold-set bypasses (`rm -rf datasets` and similar) and MINOR-1 (gold-set interpreter writes)."
- Phase 05 made no control-plane change, so the item is still open.
- It is missing from both "Carried forward from Review 1" (STATUS.md:682-691) and "Current state (phase 05)".
- This phase is the first in which `datasets/gold/er-gold-v1.csv` exists and holds real labels, so the open bypass now protects real data.
- Brief: put it in the Phase 05 carried-forward list for the human, with a target phase.

**NIT-A. `MatchJob.LogicVersion` was not bumped, although its own comment says to bump it.** Owner: entity-resolution-engineer; the human decides.
- `MatchJob.scala:661` says "Bumped on any change to the job logic". `recheck` changes the outcome for a tampered or invalid cache, and `LogicVersion` stays `er-job-v1`.
- The same forged cache would give LLM_ADJUDICATED under the pre-repair code and UNRESOLVED now, under one run ID.
- No such run exists, and outputs for every legitimate cache are byte-identical (see the reasoning in section 2). That is why this is only a NIT. The human decision is already recorded at STATUS.md:699-700.

**NIT-B. Two refusal checks in a new test are not isolated.** Owner: entity-resolution-engineer.
- In `AdjudicationSuite.scala:193-212`, the misfiled records meant to test the inputHash check and the candidateId check also carry model version `"m"`, which differs from `port.modelVersion`.
- Each record is therefore refused by the model-version check whether or not the inputHash or candidateId check exists. The test is fair but weaker than its name.
- Brief: use `port.modelVersion` in those two records.

**NIT-C. The docs page states the scratch clean-up without its condition.**
- `docs/entity-resolution.md:274-275` says the runner "removes it after the run". The code removes the folder only when this run created it. `MODULE.md:52-53` states this correctly.
- Owner: documentation-engineer.

## 4. Is STATUS.md accurate enough for a fresh session to resume?

Mostly. The summary at the top (lines 24-32) and the task table (lines 488-493) are correct; the table still shows Review 2 as "pending", which is right until this report is saved. Notes R1-1, R1-2 and R1-3 match the repair reports, and the sbt figures match what I observed (112 tests, 14 project test runs). The gaps are MINOR-A (the stale snapshot and Git line) and MINOR-C (the untracked Phase 01 item).

## 5. Carried-forward list for the human gate

1. MINOR-1: DBLP homonym false REJECT. Needs `er-features-v2` and policy `er-v2`, decided without looking at the labels.
2. NIT-3: LeakageSuite is a string tripwire only. No action.
3. NIT-4: the Phase 07 loader (storage-engineer) must treat an absent outcome folder as empty only behind `_SUCCESS`, and the DynamoDB cache must keep re-validation on every hit.
4. Human decisions:
   - whether to bump `MatchJob.LogicVersion` (NIT-A);
   - whether to delete `target/er-match-run/_local-tmp`;
   - the `target/**` gap in the ownership matrix;
   - `chmod 644 datasets/gold/er-gold-v1.csv` before committing (it is still mode 755);
   - N4 (upstream link by run ID plus run manifest).
5. New in this review: MINOR-A (STATUS snapshot), MINOR-B (MINOR-1 missing from MODULE.md and the docs caveat), MINOR-C (the Phase 01 gold-set guard item for Phase 05 is untracked), NIT-B (test isolation), NIT-C (docs wording).
6. Process items:
   - The harness moved `sbt` to the background at 180 s again; this is the sixth time this phase. I started nothing else and read the output once, after the completion notice.
   - The guard-dangerous false positive on the test-marker task name belongs to factory-engineer in Phase 11.
7. Gold-label caveat (decision 04): the 13 NON_MATCH labels are known errors, researcher precision is understated, and the reject side is effectively unmeasured.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 05 Review 2 (fresh context): re-check of the whole uncommitted Phase 05 state after repair round 1 (R1-1 entity-resolution-engineer, R1-2 and R1-3 documentation-engineer). The orchestrator saves this as docs/agent-runs/phase-05/review-report-02.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status --porcelain [--ignored], git diff --stat, git show HEAD:STATUS.md | grep
- find ... -printf for the mtimes, sizes and modes of the module, tests, docs, gold and agent-runs files; ls of target/ and target/er-match-run
- sha256sum of the policy, prompt, eval config and gold file (before and after sbt)
- grep and sed reads of Adjudication.scala, Resolver.scala, PolicyConfig.scala, EvaluateMain.scala, MatchJob.scala, MatchPolicy.scala, Features.scala and the touched suites; grep for gold references and for eval imports in matching code
- read-only checks of ~/ref-phase-05 and ~/.claude/file-history for a pre-repair baseline (none usable)
- ps (no sbt or java running before the sbt run, none after)
- source toolchain/env.sh && timeout 590 sbt -batch check (exit 0; moved to the background at 180 s by the harness; output read once after the completion notice; log in /tmp/claude-1000/-home-ajcreator-scholarchain/347fb939-df75-4353-9d81-0bc5f75d7c8d/scratchpad/sbt-check.log)
TEST RESULTS: sbt check exit 0. Passed: Total 94 (core), 112 (entityResolution), 98 (hadoop), 68 (adversarial), Failed 0, Errors 0 in each; 14 project test runs passed; [success] Total time: 148 s, completed Nov 23, 2026, 11:36:23 AM.
VALIDATION RESULTS: APPROVE (APPROVE WITH CARRIED-FORWARD ITEMS). No CRITICAL or MAJOR.
- Review 1: MINOR-2, -3, -4, -5, -6 and NIT-1 FIXED; NIT-2 FIXED for new runs (old _local-tmp left for the human); MINOR-1, NIT-3 and NIT-4 CARRIED.
- MINOR-A STATUS.md:704-728 stale "Task running: R1-1", Tasks done and Git snapshot (documentation-engineer)
- MINOR-B MINOR-1 absent from MODULE.md Known issues; docs/entity-resolution.md:141,180 describe the R-R3 intent, not the behaviour (documentation-engineer)
- MINOR-C STATUS.md:42 Phase 01 gold-set guard item scheduled for Phase 05 is untracked in the Phase 05 carry-forward (documentation-engineer; fix by factory-engineer)
- NIT-A MatchJob.scala:661 LogicVersion not bumped despite its comment (entity-resolution-engineer; human decides)
- NIT-B AdjudicationSuite.scala:193-212 inputHash and candidateId refusals not isolated (model "m" also mismatches) (entity-resolution-engineer)
- NIT-C docs/entity-resolution.md:274-275 omits "only when this run created it" (documentation-engineer)
- Test fairness: the new R1-1 tests are FAIR; no bent assertions.
ASSUMPTIONS:
- File mtimes are taken as the authoring order. They agree with every report and with the orchestrator checks.
- No pre-repair copy of the 4 R1-1 files exists (all untracked; no file-history backup), so I could not diff "comment only" (PolicyConfig) and "LocalMatchRunner only" (MatchJob). I relied on unchanged mtimes of the matching files, the unchanged policy hash and the green hand-derived MatchPolicySuite and MatchJobSuite outcome tests.
- The verdict label follows the work order; in role terms it is APPROVE.
RISKS (not reviewed, or reviewed only in part):
- MatchJob.scala outside the reducer, cache, counters and LocalMatchRunner (lines 1-289 and 381-654) was not re-read; Review 1 covered it, and no baseline exists to confirm R1-1 left it alone.
- The decision run and evaluation were not regenerated. I did not recompute the evaluation numbers (Review 1 did). I did not compare with ~/ref-phase-05 for copied-unread code.
- ErNormalize, ExactIds, TextSimilarity and the review/ package were not re-read (unchanged since before the gold file).
- No mutation testing (forbidden); no other sbt run; no packageJobs or EMR run.
- Edge case not tested: LocalMatchRunner.scratchDir with no --output (defaults to ".", so the sibling would be outside the repo). The job refuses such arguments, so no folder is expected; this was not observed.
MANUAL ACTION REQUIRED:
- Human: the carried-forward list in section 5 (MINOR-1 / er-v2, LogicVersion bump, stale _local-tmp, the target/** matrix gap, chmod 644 on the gold file, N4, the Phase 01 gold-set guard item).
- Orchestrator: save this report as docs/agent-runs/phase-05/review-report-02.md, then have documentation-engineer record Review 2 in STATUS.md (and, if the human wants it, fix MINOR-A, MINOR-B, MINOR-C and NIT-C, which are docs-only).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: STATUS.md update by documentation-engineer recording Review 2, then the Phase 05 human gate (ratify, commit, decide the carried-forward items).
```

---

## step-05s-documentation-engineer-report.md

HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier (documentation-engineer default)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 after-Review-2 STATUS update. Edit only STATUS.md. Record Review 2, replace the stale "Current state (phase 05)" snapshot, add a carried-forward list for the human gate, and give the resume command. Saved by the orchestrator as step-05s-documentation-engineer-report.md.
FILES CREATED: none (a scratch draft sat in the session scratchpad, outside the repository)
FILES MODIFIED: /home/ajcreator/scholarchain/STATUS.md
- "Where things stand", Phase 05 entry: Review 2 is done and the next step is the human gate.
- Phase 05 task table: the Review 2 row now reads "done, APPROVE WITH CARRIED-FORWARD ITEMS".
- New "Note Review 2" after the Review 1 carry-forward block.
  - It records APPROVE WITH CARRIED-FORWARD ITEMS and no CRITICAL or MAJOR finding.
  - It records sbt check exit 0: core 94, entityResolution 112, hadoop 98, adversarial 68, 14 project test runs, 11:36.
  - It records that all Review 1 repair items are fixed and that MINOR-1, NIT-3 and NIT-4 are carried.
- "Current state (phase 05)" is replaced by one current list.
  - Tasks done: through Review 2. Running: none. Next: the Phase 05 human gate. Half done: nothing.
  - Git: HEAD 4388607, with the `git status --short` output summarized (below).
  - Harness: claude-code.
  - Resume command: `cd ~/scholarchain && claude "Read STATUS.md and continue with its next step."`
- New section "Carried forward from Phase 05 (for the human gate)", 7 items.
  - Items 1 to 3: MINOR-1, NIT-3, NIT-4.
  - Item 4: human decisions (LogicVersion bump, stale `_local-tmp`, `target/**` matrix gap, `chmod 644` on the gold file, N4).
  - Item 5: MINOR-B, MINOR-C, NIT-B, NIT-C, carried unfixed. MINOR-C is the Phase 01 gold-set guard item (STATUS.md line 42), with owner factory-engineer in Phase 11 guard work.
  - Item 6: the decision 04 gold-label caveat.
  - Item 7: process items.
COMMANDS RUN:
- Read of STATUS.md and review-report-02.md.
- `git status --short`.
- A Python line-range replacement of the old snapshot, with a draft file in the scratchpad.
- grep and awk checks of section headings, line lengths and em dashes.
- `git diff --stat -- STATUS.md`.
TEST RESULTS: none run. I did not run sbt, as instructed. The sbt figures above come from review-report-02.md.
VALIDATION RESULTS:
- `git status --short` was replayed (exit 0). It shows these changes.
  - Tracked and modified: STATUS.md, build.sbt, modules/entity-resolution/MODULE.md.
  - Untracked paths: datasets/gold/, docs/agent-runs/phase-05/, docs/entity-resolution.md, the entity-resolution `src/main/resources/scholarchain/` and `src/main/scala/scholarchain/`, and 10 test files in tests/module-unit/entity-resolution/.
- The section order is now Phase 05 task list, Current state (phase 05), Carried forward from Phase 05, Previous state (phase 04, closed).
- There are 0 em dashes in STATUS.md.
- Every line I added is at most 90 characters. One line over 90 remains, at line 808 ("Dropping it means removing the file..."). It was already in STATUS.md before this edit.
- Every number and claim comes from review-report-02.md sections 1 to 5 and the existing STATUS.md notes.
ASSUMPTIONS:
- The system notice that STATUS.md changed on disk during my run was read as the harness state. I did not check what changed beyond my own edit.
- Review 2 findings are taken as stated in review-report-02.md. I did not re-verify them against the source.
RISKS:
- I did not apply MINOR-B, NIT-B or NIT-C, as instructed, and did not touch MODULE.md or docs/entity-resolution.md. They remain open for the human.
- I did not trim the older "Carried forward from Review 1" block, which partly duplicates the new list. I kept it to keep the Review 1 history.
- The Review 2 "Total time" figure differs between sources. The report says 148 s in its TEST RESULTS, and the orchestrator's work order says 11:36. I recorded both as stated.
- The file listing matches the git status at the time of the run.
MANUAL ACTION REQUIRED: none for this task. The human gate takes the carried-forward list in STATUS.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Phase 05 human gate (ratify, commit, decide the carried-forward items).
