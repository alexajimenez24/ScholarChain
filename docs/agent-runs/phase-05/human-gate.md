# Phase 05 human gate

## How Phase 05 was produced

The owning agents did the work through the orchestrator: build-engineer (Hadoop
dependencies and the capped forked test JVM for `entityResolution`),
entity-resolution-engineer (the review queue, the er-v1 subsystem and the gold-set
evaluation), documentation-engineer (`docs/entity-resolution.md`, the module notes and
STATUS), and two fresh code-reviewers. The work order and my addendum are in
`prompt.md`; every step is in `run-log.md` and the STATUS updates, and every agent report
is saved word for word in this folder (also gathered in
`phase-report-agent-reports.md`).

As in Phases 03 and 04, I gave the agents reference material from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-05/`, with a
`REFERENCE.md` listing what had to change. The reference has no gold set; every label in
this repository is mine.

## The gold set

- entity-resolution-engineer generated the review queue
  `target/er-review/er-review-v1.csv` from this repository's Phase 04 sample run: 280
  candidate pairs (205 researcher, 75 work), with no scores, match paths or predicted
  labels, each traceable to its source records.
- I labeled all 280 rows by hand on 2026-11-23 following my labeling guide, and saved
  them as `datasets/gold/er-gold-v1.csv`: 228 MATCH, 13 NON_MATCH, 39 UNSURE. I checked
  with a script that only the label and note columns differ from the queue. I corrected
  my own misspelled label `UN_MATCH` to `NON_MATCH` before handing the file over. No
  agent wrote, inferred or pre-filled a label.
- The sample has very few non-matches. A supplementary queue of 60 pairs (`v1b`) was
  generated from structural criteria only; I decided not to label it
  (`human-decision-03.md`), and it is unused.
- After the evaluation, the engineer asked what my NON_MATCH labels meant. I had judged
  the 13 researcher NON_MATCH rows by "different chapter" instead of "different person",
  which was the wrong question for researcher pairs; this includes one pair that shares
  an ORCID. I chose not to relabel or re-run (`human-decision-04.md`). These 13 labels
  are known labeling errors, kept in the file as I wrote them and stated in
  `output-er-evaluation.md` (section 11), `docs/entity-resolution.md` and the module
  notes.

## What was checked

- er-v1 (accept at 0.9 or more, reject at 0.35 or less, LLM adjudication only from 0.7
  up to 0.9) was fixed before the gold file existed and not changed afterwards; its
  policy file hash is unchanged since. Matching code never reads `datasets/gold/`. The
  reviewers verified there is no leakage, and the threshold sensitivity analysis is
  reported, not used to retune er-v1.
- Exact identifier conflicts are flagged as CONFLICT, never silently merged. The
  adjudicator can abstain, cannot introduce identifiers, and its cache is keyed by input,
  prompt, model and policy version; it runs in off or fake mode until Bedrock arrives in
  Phase 07.
- Evaluation (`output-er-evaluation.md`), MATCH and NON_MATCH counted, UNSURE reported
  separately: researcher precision 0.938 (95% CI 0.889 to 0.966), recall 0.987, F1 0.962,
  coverage 0.970; work precision 1.000 (95% CI 0.949 to 1.000), recall 0.947, F1 0.973,
  coverage 0.947. Unresolved rate 1.44% (5 of 347). er-v1 rejected nothing. Because of the
  labeling errors above, researcher precision is understated and the reject side is
  effectively unmeasured.
- Review 1 (`review-report.md`) and, after one repair round (`repair-01*.md`), Review 2
  (`review-report-02.md`) both returned APPROVE WITH CARRIED-FORWARD ITEMS, with no
  critical or major findings. The final sbt check passed: core 94, entityResolution 112,
  hadoop 98, adversarial 68 tests.
- No test-engineer test gate was part of the Phase 5 work order, so there is no
  `test-report.md`.

## Decisions

- er-v1 stays as evaluated. MINOR-1 (DBLP name variants differing only in case, accents
  or spacing can be rejected as different people) changes matching behaviour, so it goes
  to a future er-v2, not this phase.
- The match job keeps logic version `er-job-v1`; no re-run of this job is planned in this
  phase.
- `target/` is build output and stays unowned; evidence lives in `docs/agent-runs/`. I
  deleted the stale scratch folder `target/er-match-run/_local-tmp`.
- N4: I reaffirm my Phase 04 decision. A run links to its source run through its run ID
  and the run manifest.
- The harness moved sbt to the background after 180 seconds several times despite a
  600-second timeout. The agents started nothing else and did not poll, which I accept
  as meeting the intent of the machine limits.

## Accepted, with known gaps

The Phase 05 entity-resolution subsystem is accepted as committed. Carried forward:
- MINOR-1: name-variant rejection, for er-v2.
- Review 2 MINOR-B (the module notes' known issues omit MINOR-1) and NIT-C (the docs
  omit that the scratch folder is deleted only if that run created it): Phase 17,
  documentation-engineer.
- Review 2 NIT-B (two refusal checks in one test are not isolated): Phase 11.
- MINOR-C: shell commands such as `rm -rf datasets` and interpreter writes to the gold
  file are not blocked by the guard; the gold file now holds real labels. Phase 11,
  factory-engineer.
- The guard blocks harmless commands and file saves that merely contain the sbt task
  name that writes the test marker. Phase 11, factory-engineer.
- The citation-neighbourhood feature is always 0 because the work records carry no
  references (domain-engineer).

## Manual changes

- I labeled `datasets/gold/er-gold-v1.csv` (human-only territory), fixed my own label
  spelling, set the file to mode 644, and deleted the stale scratch folder in `target/`.
- Manual changes to agent-owned files: none.

## Commit

Accepted commit: 4dfb3bd46d3c0ea2abd50d4e386ce5158ea767c7

Phase 5 is complete. I approve starting Phase 6.
