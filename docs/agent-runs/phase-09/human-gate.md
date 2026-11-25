# Phase 09 human gate

## How Phase 09 was produced

The owning agents did the work through the orchestrator, one owner at a time:
rag-engineer (R1: stages 1 to 7, the QueryPlan schema and the operation allowlist; R2:
stages 8 to 10, the synthesis prompt and schema, claim verification and audit),
test-engineer (benchmark question set v1, the benchmark-query suite and the gate tests),
documentation-engineer (the rag Public API section, `docs/runtime-workflow.md`, the module
notes and STATUS), build-engineer (repair M7), and fresh code-reviewers for each review.
The work order and my addendum are in `prompt.md`; every step is in `run-log.md`, and
every report is saved in this folder.

As in earlier phases, I gave the agents reference material from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-09/`.

No cloud was used. The workflow runs on in-memory stores loaded from Spark run
`run-ccf619c509611f06` and er-v1 run `run-cb5a0421c406737d` (25,000 works, Phase 08), with
the deterministic synthesizer and a scripted fake model behind the LLM port. The
model-written answer path is off by default (`LLM_ENABLED` defaults to false).

## What was checked

- Stages 1 to 10 are implemented: planner, resolver, metrics, paths, communities,
  semantic context, evidence bundle, synthesis, claim verification and audit. User text
  never becomes Cypher or an AWS operation, an ambiguous name returns candidates and
  abstains, retrieved text is treated as untrusted data, and no chain-of-thought is
  stored or returned.
- Benchmark question set v1: 33 questions, 3 for each of the 11 categories the assignment
  lists, with expected answers computed from the deterministic stores by code that does
  not call the workflow (`tests/benchmark/questions_v1.py`, checksums in
  `output-benchmark.md`). I reviewed the questions, their expected answers and
  `semantic-relevance-rule-v1`.
- Benchmark result: 33 of 33 on deterministic retrieval, answer, pass and planning from
  the question text alone. Semantic retrieval, scored separately: 6 of 33 hits, precision
  7 of 80.
- Gate tests pass for: no user text as database code, no silent resolution of an
  ambiguous name, evidence IDs on numerical and path claims, prompt injection, and
  deterministic and semantic retrieval scored separately.
- Final full `sbt check` exited 0 in 789 s at the default SBT_OPTS (rag 135 tests,
  adversarial 219). adversarialTests now runs in a forked JVM with a 2560 MB heap
  (repair M7), because the benchmark suites ran out of memory at the default heap.

## Repair history

- Review 1 (`review-report.md`): all 13 red benchmark and gate tests (F1 to F4) were
  judged fair; it added M5 and M6, and M7 (heap). Repair round 1 fixed F1 to F4, M1 to
  M4, M6 and M7; M5 was only partly fixed and reappeared as D5.
- Review 2 (`review-report-02.md`): REQUEST_CHANGES on D5, where the claim verifier
  attributed a number to a researcher by position in the sentence.
- I authorized a second repair round for D5 alone, as a disclosed exception to the
  one-round rule. Review 3 (`review-report-03.md`) found D5's named forms fixed but the
  same class reachable through other phrasings (R3-1 to R3-3).
- I authorized a third round, again as a disclosed exception, with a conservative rule: a
  per-researcher number is VERIFIED only when its sentence names exactly one researcher
  and has no pronoun subject. Review 4 (`review-report-04.md`) confirmed R3-1 to R3-3
  fixed with no regressions, and found the class still open (R4-1 to R4-3).

## Decisions

- R4-1 to R4-3 (MAJOR, model-written path only): a surname, first name or lower-case form
  is not counted as a mention; a noun phrase standing for a researcher ("the leader",
  "the former") is not treated like a pronoun; and a cited ranking item can back any
  researcher's number. I am not authorizing another phrasing-by-phrasing round. Three
  rounds showed that attributing numbers by parsing free text cannot close this class.
  The fix is a structural change, run as Phase 09 repair round 4 inside the Phase 08b work
  order (reports saved in this folder as `repair-04-*.md` and `review-report-05.md`): every numerical claim carries an
  explicit researcher ID, metric, value and evidence ID in the structured synthesis
  output; the verifier checks that tuple against the evidence item for that researcher
  ID; the claim text for a number is rendered from the verified tuple; and free model
  text containing a number is weakened and labeled. That change bumps the claim
  verifier's version. It must be in place before the model path is enabled for
  deployment; if review 5 still finds the class open, it goes to Phase 11.
- Until then, the model path stays off (`LLM_ENABLED=false`). Deterministic answers are
  not affected by R4-1 to R4-3.
- Gate items not met at this commit: "code review returns APPROVE", and "unsupported
  claims are removed, weakened or labeled" on the model-written path. The other gate
  items are met.
- Community size: no store the runtime reads holds it, because Spark exports communities
  only as Parquet and core's `ResearcherMetrics` has no community-size field. Answers
  state it as missing evidence. The fix (spark-engineer export, storage-engineer loader)
  is carried forward to Phase 11.

## Accepted, with known gaps

The Phase 09 runtime workflow is accepted as committed, with the model path off. Carried
forward:
- R4-1 to R4-3, through the structural claim-verifier change above (rag-engineer, Phase
  09 repair round 4 in the Phase 08b run; the Phase 08b gate records the review 5 result).
- Community size (spark-engineer and storage-engineer, Phase 11).
- m1: a swapped name order ("Li Wei" for "Wei Li") is not listed as a candidate; m2: the
  name check matches substrings; m4: a topic phrase such as "collaboration graphs" gives
  an empty filter and abstains (rag-engineer, Phase 11).
- The Bedrock model ID and per-query cost (domain-engineer and rag-engineer, before
  Phase 14).
- The benchmark expected answers are recomputed if Phase 08b moves the integrated run to
  100,000 works.
- The guard blocks reports that name the test-marker task or path, so the orchestrator
  had to save two reports with that text removed (factory-engineer, Phase 11, already
  tracked).

## Manual changes

- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 9 is complete. I approve starting Phase 08b.
