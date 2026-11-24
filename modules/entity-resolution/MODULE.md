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
- `AdjudicatorMode.BEDROCK` and `AdjudicatorMode.BedrockNotInJob` (the refusal message;
  `AdjudicatorMode.parse("bedrock")` still returns Left with it).
- `BedrockAdjudicator` (Phase 07 step 04, `BedrockAdjudicator.scala`), an
  `AdjudicatorPort` over core `AwsHttp` (service `bedrock`, InvokeModel):
  `mode`, `modelVersion` (the configured model ID, which enters the cache key as before),
  `promptVersion`, `maxTokens`, `endpoint`, `complete(prompt, request)` (never throws) and
  `requestBody(prompt)`.
  `BedrockAdjudicator.fromConfig(cfg, template, credentials, maxTokens = 512,
  maxAttempts = 1, requestTimeout, transport, clock, endpointOverride)` takes the model ID
  from `cfg.models.synthesisModel` and the region from `cfg.application.region`.
  It returns Left when `llmEnabled` is false, the model ID or region is empty, the model
  is not Anthropic family (pattern `(^|[./:])anthropic\.`), or `maxTokens` or
  `maxAttempts` is below 1.
  Body: Anthropic Messages (`anthropic_version` `bedrock-2023-05-31`, `max_tokens`,
  `temperature` 0, one user message).
  Any non-2xx status, transport failure, missing credentials or malformed envelope gives
  `LLM_CALL_FAILED`; text that breaks the schema gives `LLM_INVALID_OUTPUT`; both leave
  the case UNRESOLVED and cache nothing.
  Limits and documentation URLs: [docs/service-limits.md](../../docs/service-limits.md).
- `MatchJob.portFor(mode, template)`: OFF gives `OffAdjudicator`, FAKE gives
  `FakeAdjudicator`, BEDROCK gives `Left(BedrockNotInJob)`.
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
- Phase 07 step 04 (`docs/agent-runs/phase-07/step-03-entity-resolution-engineer-report.md`):
  the Bedrock adjudicator adapter, tested against a local `com.sun.net.httpserver` stub
  only (no AWS call, no real credentials).
  New `BedrockAdjudicatorSuite` has 7 tests; `sbt entityResolution/test` passed 119 (112
  before).
  The full `sbt check` exited 0 in 312 s with project totals 153 (1 ignored), 115, 68,
  119, 98 and 33 (`docs/agent-runs/phase-07/output-sbt-check-step04.txt`).
  er-v1 is unchanged: policy resource sha256 starts 82ce9ae5, thresholds 0.9, 0.35 and
  band [0.7, 0.9), prompt `er-adjudication-v1` sha256 starts fbcf4b13, and the cache key
  `sha256(inputHash|promptVersion|modelVersion|policyVersion)` is pinned independently in
  the tests.
  The gold-set evaluation was not rerun, so the Phase 05 numbers stand.
  `MatchJob.LogicVersion` stays er-job-v1 (job behavior is unchanged).

## Not done

- Bedrock wiring in `MatchJob` and any live Bedrock run: the adapter exists but
  `--adjudicator bedrock` and `models.llmEnabled=true` are still refused (exit codes 2 and
  3, tested in MatchJobSuite).
  Wiring needs credentials in the task JVMs, a per-task budget above the default
  `ER_MAX_LLM_CALLS=0` and the human's cost sign-off.
- Gold-set measurement of the `LLM_ADJUDICATED` path: no Bedrock run exists.
- Converse API support, non-Anthropic request bodies and a DynamoDB adjudication cache.
- `sbt packageJobs` with the new sources and any EMR run.
- A gold set with valid NON_MATCH labels: the reject side is effectively unmeasured.
- Citation evidence (the contract gap below).

### Phase 08 done

Sources, in `docs/agent-runs/phase-08/`:
`step-S3-entity-resolution-engineer-report.md` and
`step-S3b-entity-resolution-engineer-report.md`.
No public API change and no code change; er-v1 is unchanged, the adjudicator is off and
the gold set was not re-evaluated.

- `run-85fd79957d197ffc`: 100,000 works, 4,561,097 decisions in 1:21:47 with `-Xmx2g`.
- `run-cb5a0421c406737d`: 25,000 works, 903,286 decisions in 1:19:48, peak RSS about
  1.4 GB.
- Outcome counts are in [docs/entity-resolution.md](../../docs/entity-resolution.md).

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
- The Bedrock adapter reuses `models.synthesisModel` (`${SYNTHESIS_MODEL_ID}`) as its
  model ID because core config has no adjudication model field.
  Human decision pending: keep sharing it, or have domain-engineer add a field.
  Not blocking.
- Only the Anthropic Messages body is supported; other model families, and an
  application-inference-profile ARN without `anthropic.` in it, are refused at
  construction.
- A budget unit equals one port call, not one HTTP attempt: with retries enabled in the
  injected `AwsHttp` (default `maxAttempts` 1), one unit can cover several attempts.
- The Bedrock host `bedrock-runtime.<region>.amazonaws.com` is built from the configured
  region and was proven only against the stub; request signing against the live service
  is unverified (see [docs/service-limits.md](../../docs/service-limits.md)).
- Doc-comment pitfall: a `*/` inside a doc comment closes it and breaks the build.
- The evaluation copy under `target/` could not be written because `target/**` has no
  owner (open item 3 for the human).

### Phase 08 known issues

- `LocalMatchRunner` needs absolute paths, like `LocalRunner`.
- The runtime is close to the 90-minute step-down limit (about 8 and 10 minutes under).
  At 25,000 works the wall time did not scale with the input and CPU use was 36 percent;
  the report suspects I/O wait or host contention and did not verify it.
- The default Test heap of 1g was not tried at this scale.
- The empty `adjudications/` and `rejects-v1/` folders are absent in these runs.
- Researcher CONFLICT volume is mostly ORCID_DISAGREES between holders of different
  ORCIDs in large blocks; a candidate er-v2 refinement, not changed (er-v1 is frozen).

## Dependencies

- Project: `core` (since Phase 07 also `scholarchain.core.aws` for `BedrockAdjudicator`).
- `hadoop-client-api` at Provided scope (Hadoop test runtime at Test scope, from
  `project/Versions.scala`).
- Packaged by `packageJobs` into `target/jobs/` (not run for the new sources).
- Reads Phase 04 output only through the versioned `match-candidate-v1` records on disk,
  never by importing the hadoop module.

Allowed by the boundary table in `AGENTS.md`; no module depends on `services`.
