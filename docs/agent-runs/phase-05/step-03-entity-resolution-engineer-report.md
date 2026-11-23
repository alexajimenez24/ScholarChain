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
