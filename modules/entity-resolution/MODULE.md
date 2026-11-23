# Module `entity-resolution`

## Purpose

Researcher and work identity resolution.
It reads the Phase 04 `match-candidate-v1` records, scores each pair with policy er-v1
(exact identifiers, a deterministic bibliographic rule and a logistic score), detects
conflicts, optionally adjudicates the borderline band behind a port, and writes one
`MatchDecision` per pair.
It also builds the human review queue and evaluates decisions against the gold set.
The adjudication prompt lives in `src/main/resources/` of this module.
Full description and numbers: [docs/entity-resolution.md](../../docs/entity-resolution.md).

## Owner and paths

- Owner: entity-resolution-engineer (documentation-engineer owns only this file).
- sbt project: `entityResolution`; folder: `modules/entity-resolution`.
- Main sources: `src/main/scala/scholarchain/er/` (with `review/` and `eval/`).
- Resources: `src/main/resources/scholarchain/er/` holds `er-policy-v1.json`,
  `er-eval-v1.json`, `review-queue-v1.json`, `review-supplement-v1b.json` and
  `prompts/er-adjudication-v1.md`.
- Unit-test root: `tests/module-unit/entity-resolution/` (never `src/test`).

## Public API

Names are taken from the engineer reports in `docs/agent-runs/phase-05/` and the file
list under `src/main/scala`.

Package `scholarchain.er` (T2, `agent-report.md`):

- `ErNormalize` (normalization `er-norm-v1`) and `PersonName`.
- `ExactIds`: `IdScheme`, `IdSet`, `Agreement`, identifier extraction and comparison.
- `TextSimilarity`: Jaro-Winkler, Jaccard, trigram Dice, longest common subsequence.
- `Profiles`: `MentionProfile`, `WorkProfile`, `EndpointProfile`, `ProfileLimits`.
- `Candidates`: `ErCandidate` (a strict mirror of match-candidate-v1) and
  `MergedCandidate`.
- `PolicyConfig`: `Thresholds`, `SchemeRule`, the policy config and the threshold pin.
- `Features`: the feature functions of `er-features-v1`.
- `MatchPolicy`: the er-v1 rules and score.
- `Adjudication`: the request, the strict output schema and validator,
  `AdjudicatorPort`, `OffAdjudicator`, `FakeAdjudicator`, `PromptTemplate`,
  `AdjudicationCache` and `Adjudicator`.
  `Adjudicator.recheck` re-validates every cache hit (preloaded or in-run) against the
  current request, with the same strict validator as a fresh reply.
  A hit that fails is not used and gives `LLM_INVALID_OUTPUT` with no call.
- `Resolver`, `DecisionAudit` and `Resolved`.
- `MatchJob`: jobs ER-J1 and ER-J2, the Tool driver, `MatchArgs`, `MatchPaths`, the
  counters and `LocalMatchRunner`.
  `LocalMatchRunner` is in `src/main` and ships in the job jar; only the Hadoop runtime
  it needs is Test-scoped, which is why it runs through `Test/runMain`.
  `LocalMatchRunner` also exposes `scratchDir(output)`, the sibling `<output>-tmp`, and
  `run(args, job)`, which returns the exit code and removes the scratch folder when the
  run created it; `main` calls `run`.
  This is ER only: the hadoop module's `LocalRunner` still uses `<output>/_local-tmp`.

Package `scholarchain.er.review` (T1, `step-02-...-report.md`; T1b, `step-03b-...-report.md`):

- `ReviewQueue` (Columns, similarity, stratum, allocate, select, side, rows, csvField,
  cells, csv, build), `ReviewQueueConfig` (load, parse, validate), `CandidateView`
  (parse, merge), `ReviewSide`, `ReviewRow`, `RecordIndex`, `Stratum` (internal) and
  `ReviewQueueMain` (run, parseArgs, isGoldPath, sidecarOf, main).
- `ReviewSupplement` and `ReviewSupplementMain` (the v1b queue; its API is described only
  by file name here, because the v1b report was not used for details).

Package `scholarchain.er.eval` (T2 and T3, `step-03-...-report.md`):

- `GoldCsv`, `GoldLabel`, `GoldRow` (with optional display columns `left_name_or_title`
  and `right_name_or_title`), `Confusion`, `Metrics` (with `precisionWilson95` and
  `recallWilson95`), `Interval`, `Wilson`, `Evaluation.CountingRules`, `ErrorExample`,
  `KindReport`, `EvaluationReport`, `EvalConfig`, `Evaluation` and `EvaluateMain`.
- `EvaluateMain.samePolicy` refuses a decision run whose decisions name a policy version
  other than the loaded policy (er-v1); nothing is written to `--out`.
- `byPath` always lists all four match paths; an empty path has `labeled = 0`.

Run commands (all need sbt after `source toolchain/env.sh`, none replayed by
documentation-engineer):

- Match job over a Hadoop run:
  `sbt "entityResolution/Test/runMain scholarchain.er.LocalMatchRunner --run-root <hadoop out> --output <dir> [--adjudicator off|fake] [--adjudication-cache <run dir>] [--reducers 4] [--compress true]"`.
  On EMR the entry point is `MatchJob.main`.
- Review queue:
  `sbt "entityResolution/runMain scholarchain.er.review.ReviewQueueMain --run-root <dir> --out <file.csv> [--run-id <id>] [--overwrite true]"`.
- Evaluation:
  `sbt "entityResolution/runMain scholarchain.er.eval.EvaluateMain --gold <csv> --decisions <run dir> --out <dir>"`.

Output layout:
`<out>/match-decisions/policy=er-v1/snapshot=<d>/run_id=<id>/{accepted,rejected,unresolved,conflict,audit,adjudications,rejects-v1}`
plus `run-manifests/<id>.json` and `<id>-summary.json`.

## Done

- Build wiring: `hadoop-client-api` (Provided) and the Hadoop test runtime, forked test
  JVM at `-Xmx1g` (step 01, `sbt check` green).
- T1: review queue, 280 rows from run `run-6d1175dbfdb874a9`.
- T2: policy er-v1 (thresholds accept 0.9, reject 0.35, LLM band [0.7, 0.9)), features
  `er-features-v1`, adjudication port with off and fake modes, MatchJob ER-J1 and ER-J2,
  evaluation code.
  Sample run `run-02cecabcf3e68312`: 347 decisions (338 ACCEPTED, 0 REJECTED,
  5 UNRESOLVED, 4 CONFLICT), unresolved rate 0.0144.
- T3: gold-set evaluation on v1 only (280 rows).
  Researcher precision 0.938 (understated, see Known issues), recall 0.987, F1 0.962;
  work precision 1.000 (no negatives), recall 0.947, F1 0.973.
  Numbers and sources are in docs/entity-resolution.md.
- T1b: the v1b queue was generated; it is unlabeled and unused (human decision 03).
- Repair round 1 (R1-1, `repair-01-entity-resolution-engineer.md`): cache re-validation,
  the `samePolicy` refusal and the ER scratch folder move.
  `MatchJob.LogicVersion` (er-job-v1) was not bumped.
- Last `sbt check` (R1-1): exit 0, entityResolution 112 tests (106 before), 14 project
  test runs.

## Not done

- Any Bedrock adjudicator (Phase 07); `models.llmEnabled=true` and Bedrock mode are
  refused.
- `sbt packageJobs` with the new sources and any EMR run.
- A gold set with valid NON_MATCH labels: the reject side is effectively unmeasured.
- Citation evidence (the contract gap below).

## Known issues

- **Labeling errors in the gold set (human decision 04).**
  The 13 researcher NON_MATCH labels were judged by "different chapter", not "different
  person", and include the equal-ORCID pair `mc:researcher:10c07413d756444b3991`.
  They are known labeling errors, so the 10 researcher false positives are likely not
  real errors of er-v1, researcher precision (0.938) is understated, and the reject side
  is effectively unmeasured.
  The gold file and the evaluation were kept as they are.
- Human decision 03: the sample has few non-matches (13 NON_MATCH labels), which limits
  how well precision and the reject side are measured.
  All 13 involve 4 names.
- R-R5 (DBLP source cluster) shows precision 0.636 on only 11 labeled pairs; its 4 false
  positives belong to the labels above.
- 4 work pairs are CONFLICT for `DOI_DISAGREES` on identical titles, and the human
  labels them MATCH, so work recall is 0.947.
- No citation evidence is available: `work-v1` has no references, so `citation_overlap`
  is always 0 (contract gap for domain-engineer).
- The empty output folders `rejected/`, `adjudications/` and `rejects-v1/` are absent in
  a run with zero rows; readers must treat an absent folder as empty behind `_SUCCESS`.
  The evaluation reader does, and a reviewer should confirm the others.
- ER-J1 sends a whole `orcid:` profile to every linked candidate; measure the data growth
  before a full-DBLP run.
- The adjudication cache is read whole into memory in each reducer; a DynamoDB cache
  comes later.
  The Phase 07 DynamoDB cache must keep the re-validation on every hit.
  Duplicate JSON keys in a reply are accepted with the last one winning (a core decoder
  behavior).
- `target/er-match-run/_local-tmp` predates the scratch fix (made by the 10:01 run) and
  was left in place; a new local run uses `<output>-tmp` and removes it afterwards.
  Whether to delete the old folder is the human's decision.
- N4 (open for the human): upstream is linked by run ID plus the run manifest
  (`upstreamRunId`, `upstreamPipeline` "hadoop-v2"), not by a policy version in
  provenance.
- guard-dangerous gives false positives on commands and report text that name the
  test-marker task literally (Phase 11 item).
- The harness moves `sbt` to the background after 180 s, even with a longer timeout.
- Doc-comment pitfall: a `*/` inside a doc comment closes it and breaks the build.
- The evaluation copy under `target/` could not be written because `target/**` has no
  owner (open item 3 for the human).

## Dependencies

- Project: `core`.
- `hadoop-client-api` at Provided scope (Hadoop test runtime at Test scope, from
  `project/Versions.scala`).
- Packaged by `packageJobs` into `target/jobs/` (not run for the new sources).
- Reads Phase 04 output only through the versioned `match-candidate-v1` records on disk,
  never by importing the hadoop module.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
