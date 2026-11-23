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
