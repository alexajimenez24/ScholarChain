# Entity resolution (policy er-v1)

Owner of the code: entity-resolution-engineer (`modules/entity-resolution`).
This page is written by documentation-engineer from saved reports only.
Every number comes from one of these files under `docs/agent-runs/phase-05/`:

- `agent-report.md` (T2, the match job and its sample run);
- `step-02-entity-resolution-engineer-report.md` (T1, the review queue);
- `step-03-entity-resolution-engineer-report.md` and `output-er-evaluation.md` (T3);
- `human-decision-01.md` to `human-decision-04.md`.

No number here comes from another repository.

## Read this first: what the evaluation can and cannot claim

Human decision 04 (`human-decision-04.md`) states the following, and it governs every
metric below.

- The 13 researcher NON_MATCH labels in `datasets/gold/er-gold-v1.csv` were judged by
  "different chapter", not "different person".
  That was the wrong question for researcher pairs.
- The 13 labels include the equal-ORCID pair `mc:researcher:10c07413d756444b3991`.
- The 13 labels are therefore **known labeling errors**.
- The 10 researcher false positives are **likely not real errors of er-v1**.
- Researcher precision (0.938) is **understated**, and so is the combined precision
  (0.957).
- The **reject side is effectively unmeasured**: the researcher set has no valid
  NON_MATCH label, and the work set has none at all.
- The gold file and the decision run are kept as they are, and T3 was not re-run.

The human's limitation from decision 03, in the human's words:

> the sample has few non-matches (13 NON_MATCH labels) and this limits how well
> precision and the reject side are measured

Decision 04 does not say which figures, if any, are free of caveat.

## Purpose and pipeline position

Entity resolution decides whether two candidate records are the same researcher or the
same work.
It reads the match candidates that the Hadoop jobs wrote in Phase 04 and writes one
`MatchDecision` per candidate pair, with the features, the rules that fired, reason
codes, confidence, policy version and run ID.

- Input: the Phase 04 `match-candidate-v1` records (jobs J03 and J04) plus the Phase 04
  run manifest.
- The stage reads them from the run folder on disk or S3.
  It does not import the hadoop module, which the boundary rules in `AGENTS.md` forbid.
- Upstream run used for every number below: `run-6d1175dbfdb874a9`
  (pipeline `hadoop-v2`, normalization `norm-v1`), built from `datasets/samples`.
- Link to upstream (N4, see Known issues): the ER run manifest records `upstreamRunId`,
  `upstreamPipeline` and `upstreamNormalization`, read from `run-manifests/<runId>.json`.
  The human has not yet accepted this as the answer to N4.
- Output: the decision run `run-02cecabcf3e68312`, described under Outputs.

## Normalization (er-norm-v1) and exact identifiers

- Names, titles and affiliations are normalized by `ErNormalize` (`er-norm-v1`) into a
  `PersonName` (family name, given names, initials) and token lists.
- `ExactIds` extracts the identifiers of each side into an `IdSet` over the schemes
  ORCID, OpenAlex, Semantic Scholar, DOI, arXiv and repository URL, and compares them as
  `Agreement`: equal, disagree or unknown.
- A preprint-server DOI never counts as a DOI disagreement.
- An `orcid:` endpoint never counts its own linked mention as evidence for itself
  (leave-one-out).

## Features (er-features-v1)

"Upstream" means the Phase 04 feature for the same evidence.

Researcher features:

- `name_similarity`: maximum Jaro-Winkler over name forms, including the swapped order
  for two-token names; upstream `name_jw` is used when names are missing.
- `same_full_name`, `initials_compatible` (same family name and first initial, other
  initials in order), `name_order_swapped`, `names_known`.
- `author_position_agree` and `author_position_known` (both first author, or both last).
- `coauthor_overlap`: Jaccard over family-name-plus-first-initial keys, the larger of
  this value and upstream.
- `affiliation_overlap` (larger of this and upstream), `affiliation_known`,
  `affiliation_disjoint`.
- `topic_overlap`: Jaccard of title tokens without stop words, larger of this and
  upstream.
- `venue_overlap` (larger of this and upstream).
- `year_gap`: gap between activity ranges, the smaller of this and upstream, 99 when
  unknown.
- `shared_work`, `dblp_homonym_distinct`, `same_source_cluster` (upstream).
- ORCID, OpenAlex and Semantic Scholar equal and disagree flags.

Work features:

- `title_similarity` (trigram Dice), `same_normalized_title`, `title_tokens`.
- `author_overlap` and `author_sequence_similarity` (longest common subsequence over the
  longer list), `authors_known`.
- `year_distance`, `same_source`.
- `venue_compatible` (same venue ID, otherwise trigram Dice of names), `venue_known`.
- Equal and disagree flags for DOI, arXiv, OpenAlex, Semantic Scholar and repository.

Shared features:

- `citation_overlap` and `citation_known`: both are always 0, because `work-v1` carries
  no references (a contract gap, see Known issues).
- `source_reliability`: the minimum over the two sides (ORCID 1.0, DBLP 0.95,
  Crossref 0.95, OpenAlex 0.85, ScholarChain 0.9).
- `cross_source`, `profiles_found`.
- Every decision also records `er_score`.

## Policy er-v1

The policy is the resource
`modules/entity-resolution/src/main/resources/scholarchain/er/er-policy-v1.json`,
sha256 `82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff`.
er-v1 was fixed before the gold file existed (the file appeared about 10:28 to 10:33)
and was not changed afterwards.
No agent could read a label while authoring it.
Nothing is git-committed yet.
The T2 report states that matching code never reads `datasets/gold/`;
`LeakageSuite` (2 tests) exists for this.

Thresholds (pinned in the policy; they come from the core config, which must equal the
pin):

| Threshold | Value | Meaning |
|---|---|---|
| accept | 0.9 | score at or above gives ACCEPTED |
| reject | 0.35 | score at or below gives REJECTED |
| LLM band | [0.7, 0.9) | UNRESOLVED scores here may go to the adjudicator |

If the config sets a different threshold or policy name, the job refuses to run and says
a new policy version is needed.
`MatchPolicySuite` and `MatchJobSuite` test the refusal.

Rules, applied in this order:

| Rule | Condition | Outcome |
|---|---|---|
| R-R0 | contradictory IDs | CONFLICT |
| R-R1 | two mentions on the same work | REJECTED, or CONFLICT when an ID agrees |
| R-R2 | ORCID disagreement | CONFLICT, or REJECTED when score is also at or below reject |
| R-R3 | different DBLP homonym suffixes | REJECTED, or CONFLICT when an ID agrees |
| R-R4 | ORCID, OpenAlex or S2 match with corroborating names | ACCEPTED (EXACT_ID) |
| R-R5 | a DBLP source cluster | ACCEPTED (DETERMINISTIC_BIBLIOGRAPHIC) |
| R-R9 | the logistic score | ACCEPTED, REJECTED or UNRESOLVED by threshold |
| R-W0 | contradictory IDs | CONFLICT |
| R-W1 | DOI or arXiv disagreement | CONFLICT, or REJECTED when score is also at or below reject |
| R-W2 | DOI, arXiv, OpenAlex, S2 or repository match | ACCEPTED when title similarity allows |
| R-W3 | same normalized title, year within 1, author sequence at least 0.8 | ACCEPTED |
| R-W9 | the logistic score | ACCEPTED, REJECTED or UNRESOLVED by threshold |

Rule details, from `agent-report.md`:

- R-R4 corroboration: name similarity at least 0.75 for ORCID and 0.85 for the other
  schemes, with compatible initials; otherwise UNRESOLVED.
  A declared-only ORCID is ACCEPTED at 0.95.
- R-W2 needs title similarity of at least 0.3 (0.6 for a repository).
  Otherwise it is UNRESOLVED, and it is also UNRESOLVED when both records come from the
  same source.
- R-W3 needs at least 4 title tokens.
- The weights are in `er-policy-v1.json`.
  Researcher: bias -4.3, nameAboveFloor 3.0 (floor 0.85), sameFullName 1, coauthorOverlap
  6, topicOverlap 2, affiliationOverlap 2.5 and further terms.
  Work: bias -5, titleSimilarity 6, authorOverlap 2, authorSequence 1 and further terms.
  An unknown year adds nothing to the score.
- Changing a weight or a threshold needs a new policy version; er-v1 is never edited in
  place.

Match paths recorded on a decision: EXACT_ID, DETERMINISTIC_BIBLIOGRAPHIC,
PROBABILISTIC and LLM_ADJUDICATED.
Decision confidence means P(same entity) on every path.

## Conflict detection

A conflict is not a rejection.
It means two pieces of identity evidence disagree, and the pair is not merged.

- Contradictory identifiers (R-R0, R-W0): CONFLICT.
- An ORCID disagreement (R-R2) is a CONFLICT, or a REJECTED when the score is also at or
  below reject (0.35); it does not depend on another identifier agreeing.
- A different DBLP homonym suffix (R-R3) is a CONFLICT when an identifier on the same
  pair agrees, otherwise a REJECTED.
- A DOI or arXiv disagreement on works (R-W1) is a CONFLICT, or REJECTED when the score
  is also at or below reject.
- On the sample run, the 4 CONFLICT decisions are all work pairs with `DOI_DISAGREES`
  and identical titles.
  The human labels these 4 as MATCH, so the rule costs 4 points of work recall.
  It prevented no error on this sample.

## Adjudication port

LLM adjudication sits behind `AdjudicatorPort`.
Modes in this repository: **off** (`OffAdjudicator`) and **fake** (`FakeAdjudicator`,
model `fake-deterministic-v1`, for wiring tests).
No Bedrock adjudicator exists; Bedrock mode and `models.llmEnabled=true` are refused
until Phase 07.
The sample run used off mode, so its 5 band pairs are UNRESOLVED with reason code
`LLM_OFF`.

- Scope: only R-R9 and R-W9 probabilistic results that are UNRESOLVED with a score in
  [0.7, 0.9).
- Prompt: `er-adjudication-v1`, sha256
  `fbcf4b136e72b9d1edede0060be0d4543f2459a83c0e119252284cd2ca291ac5`, at
  `modules/entity-resolution/src/main/resources/scholarchain/er/prompts/er-adjudication-v1.md`.
  A test fails if the text changes without a new prompt version.
- Strict output schema: the reply is exactly one JSON object with the keys `decision`
  (ACCEPT, REJECT or ABSTAIN), `sourceId`, `targetId`, `confidence`, `reasonCodes` and
  `citedFields`.
  Prose, code fences, extra or missing keys and null all fail validation.
- No new identifiers: the two IDs must equal the candidate's own.
  Reason codes are 1 to 8 codes matching `^[A-Z][A-Z_]{1,47}$`, so they cannot carry
  digits or an identifier.
  Every cited field must be one that was supplied.
- A reply with confidence below 0.8 is treated as an abstention.
  The recorded confidence of an LLM REJECT is 1 minus the reply's confidence.
- Adjudication never changes the candidate, the profiles or the features
  (`ResolverSuite`).
- Budget per task: floor(`maxLlmCallsPerRun` / reducers); `maxLlmCallsPerRun` defaults
  to 0.
- Cache key: `sha256(inputHash|promptVersion|modelVersion|policyVersion)`, where
  `inputHash` is the sha256 of the canonical JSON of the request.
  Validated replies go to `adjudications/`.
  `--adjudication-cache <run dir>` reloads them, and a cache line whose key does not
  match its own fields is refused.
  `MatchJobSuite` checks that a replay makes 0 calls and gets 1 cache hit.
- Every cache hit is re-validated (`Adjudicator.recheck`) with the reply validator and
  the request's IDs and versions.
  A refused hit gives `LLM_INVALID_OUTPUT` with no call, and the case stays UNRESOLVED.
  `AdjudicationSuite`, `ResolverSuite` and `MatchJobSuite` check forged, correctly keyed
  lines.

## Outputs and layout

```text
<out>/match-decisions/policy=er-v1/snapshot=<d>/run_id=<id>/
  accepted/ rejected/ unresolved/ conflict/ audit/ adjudications/ rejects-v1/
  _counters.json  _SUCCESS
<out>/run-manifests/<id>.json
<out>/run-manifests/<id>-summary.json
```

- The core `match-v1` decision has no timestamp or upstream-run field, so each decision
  also has a `DecisionAudit` line in `audit/`: `decidedAt` (one value per run, from the
  run start), upstream run, upstream features, block keys, cache key and thresholds.
- The folders `rejected/`, `adjudications/` and `rejects-v1/` are absent in the sample
  run, because they hold zero rows (orchestrator check at the end of `agent-report.md`).
  The evaluation reader treats an absent folder as empty only behind `_SUCCESS`
  (`output-er-evaluation.md` section 2).

Sample run `run-02cecabcf3e68312` (off mode; 418 candidate lines merged into 347
decisions, 71 duplicate copies, 0 rejects, 0 missing profiles):

| Kind | ACCEPTED | REJECTED | UNRESOLVED | CONFLICT |
|---|---|---|---|---|
| researcher | 267 | 0 | 5 | 0 |
| work | 71 | 0 | 0 | 4 |
| all (347) | 338 | 0 | 5 | 4 |

- By path: EXACT_ID 130, DETERMINISTIC_BIBLIOGRAPHIC 20, PROBABILISTIC 197,
  LLM_ADJUDICATED 0.
- Unresolved rate over the whole run: 5/347 = 0.0144.
- Researcher reasons: 192 SCORE_AT_OR_ABOVE_ACCEPT, 44 ORCID_EQUAL, 20
  SAME_SOURCE_CLUSTER, 11 OPENALEX_EQUAL, 5 SCORE_IN_LLM_BAND.
  Work reasons: 71 DOI_EQUAL, 4 DOI_DISAGREES.

Run it (from `agent-report.md`):

```bash
source toolchain/env.sh
sbt "entityResolution/Test/runMain scholarchain.er.LocalMatchRunner \
  --run-root <hadoop out> --output <dir> [--adjudicator off|fake] \
  [--adjudication-cache <run dir>] [--reducers 4] [--compress true]"
```

The local runner puts Hadoop scratch in the sibling folder `<output>-tmp` and removes it
after the run (ER only; the hadoop module's `LocalRunner` is unchanged).
On EMR the entry point is `MatchJob.main`; no EMR run has been done.
These commands need sbt and were not replayed by documentation-engineer.

## Review queue (v1)

The review queue is the file the human labels to make the gold set.
It is built by `scholarchain.er.review.ReviewQueueMain` from the Phase 04 run and
written to `target/er-review/er-review-v1.csv` (git-ignored).

- 280 rows (205 researcher, 75 work), sha256
  `f75204756578ffc9341482f18335e4cf574d06d5617f79a6d8dbaf6dbf442e3e`, source run
  `run-6d1175dbfdb874a9`.
- The 20 columns are `pair_id`, `kind`, per side `source`, `record_id`, `name_or_title`,
  `orcid_or_doi`, `affiliations`, `coauthors`, `venues_years`, `titles`, then `label`
  and `note`.
- The file shows no score, match path or predicted label.
  Strata (likely match, borderline, likely non-match) are used only to choose rows and
  are never written.
- Selection is seeded and deterministic, and the generator refuses to overwrite an
  existing file without `--overwrite true`.
- It refuses any path under `datasets/gold`.
- Class imbalance (T1 report, RISKS 1): the samples gave only 8 likely non-matches
  (4 researcher, 4 work) and no borderline work pairs.
  Human decision 01 chose to label the queue as is.
- v1b: a supplementary queue `target/er-review/er-review-v1b.csv` (60 researcher rows,
  sha256 `08c02f228229f907ce7de1b967dd22ce5de5af6263c06411fb23c7a68c86c845`) was
  generated in step 03b.
  The human will not label it (decision 03), so it stays unlabeled and unused.
  Nothing in the evaluation reads it, and no v1b decision counts are reported here.

## Evaluation (T3, gold set v1)

Gold file `datasets/gold/er-gold-v1.csv`, written by the human, sha256
`7fdd792334a6f6ef690e0d73f03010ddebf82d252e84614273651f6aa8026ab7`, 280 rows:
228 MATCH, 13 NON_MATCH, 39 UNSURE.
Evaluated against decision run `run-02cecabcf3e68312`.
Source: `output-er-evaluation.md`; every number in this section is copied from it.
**Read the caveats at the top of this page before using any precision figure.**

Counting rules:

- Only MATCH and NON_MATCH are counted (241 rows); UNSURE (39) is reported separately.
- TP is MATCH accepted, FP is NON_MATCH accepted, TN is NON_MATCH rejected, FN is MATCH
  rejected.
- UNRESOLVED and CONFLICT are outside TP, FP, TN and FN and lower coverage.
  A MATCH left UNRESOLVED or CONFLICT counts against recall.
- Intervals are Wilson 95% score intervals.

Headline:

| Kind | Labeled | Precision | Recall | F1 | Coverage |
|---|---|---|---|---|---|
| researcher | 166 | 0.938 [0.889, 0.966] | 0.987 [0.954, 0.996] | 0.962 | 0.970 |
| work | 75 | 1.000 [0.949, 1.000] | 0.947 [0.871, 0.979] | 0.973 | 0.947 |
| both | 241 | 0.957 [0.922, 0.976] | 0.974 [0.944, 0.988] | 0.965 | 0.963 |

- Researcher confusion (MATCH / NON_MATCH rows; ACCEPTED, REJECTED, UNRESOLVED,
  CONFLICT): [151, 0, 2, 0] and [10, 0, 3, 0].
- Work confusion: [71, 0, 0, 4] and [0, 0, 0, 0].
- The precision figures are understated, per decision 04 above.
  Work precision has no negative example, so only its lower bound carries information.
- Researcher by path:

| Path | Labeled | Precision | Recall |
|---|---|---|---|
| EXACT_ID | 33 | 0.970 | 1.000 |
| DETERMINISTIC_BIBLIOGRAPHIC (R-R5) | 11 | 0.636 [0.354, 0.848] | 1.000 |
| PROBABILISTIC | 122 | 0.957 | 0.982 |
| LLM_ADJUDICATED | 0 | none | none |

- R-R5 rests on 11 labeled pairs, so its interval is very wide, and its 4 false
  positives are among the 13 labels that decision 04 calls errors.
- Unresolved rate: researcher 0.030 and all 0.021 on labeled pairs; 0.0144 over the
  whole run.
- Reject side: er-v1 made 0 REJECTED decisions, so TN and FN are 0 for both kinds.
  Of the 13 NON_MATCH labels, 0 were rejected (Wilson [0.000, 0.228]), 10 were accepted
  and 3 were left unresolved.
  "Recall (decided) = 1.000" only means that no MATCH was rejected.
  No labeled probabilistic score is at or below 0.5, so the reject threshold 0.35 is
  never exercised by a labeled pair.
- UNSURE: 39 rows, all researcher pairs, all ACCEPTED by er-v1 (PROBABILISTIC 35,
  DETERMINISTIC_BIBLIOGRAPHIC 3, EXACT_ID 1).
  They are reported, not counted.
- The 6 MATCH labels not accepted: 2 researcher pairs in the LLM band with the
  adjudicator off, and 4 work DOI conflicts.
- Threshold sensitivity is reported in the evaluation (section 8) and was not used to
  retune er-v1.
  No grid point exceeds researcher precision 0.944, because 5 of the 10 false positives
  come from EXACT_ID and R-R5, which thresholds do not touch.

The 10 researcher false positives, from section 7.1 of the evaluation:

- All are same-full-name pairs from one 2014 reference work, whose two sides list
  different chapters.
  Decision 04 says these were labeled by "different chapter" and are likely not real
  errors of er-v1.
- 1 is the equal-ORCID pair `mc:researcher:10c07413d756444b3991` (rule R-R4), 4 are
  R-R5 `SAME_SOURCE_CLUSTER` and 5 are R-R9.

Reproduce (needs sbt, not replayed here):

```bash
source toolchain/env.sh
sbt "entityResolution/runMain scholarchain.er.eval.EvaluateMain \
  --gold datasets/gold/er-gold-v1.csv \
  --decisions target/er-match-run/match-decisions/policy=er-v1/snapshot=2026-11-22/run_id=run-02cecabcf3e68312 \
  --out target/er-eval"
```

`EvaluateMain` refuses a decision run whose decisions name a policy version other than
the loaded policy (`samePolicy`), and writes nothing in that case.

## Limits and not done

- The evaluation does not support a precision claim, a reject-side claim or a claim
  about hard negatives (see the top of this page).
- No citation evidence exists (`work-v1` has no references).
- No Bedrock adjudicator, no `sbt packageJobs` for this module, no EMR run.
- The sample has no REJECTED decisions.
- A gold set with valid NON_MATCH labels is needed before any precision or reject-side
  claim; deciding on one is the human's.
