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
