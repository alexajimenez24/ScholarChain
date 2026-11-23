# Phase 05, human message 02 (received in the main session after step 03b, 2026-11-23)

Verbatim:

> datasets/gold/er-gold-v1.csv exists with 280 labeled rows (228 MATCH, 13 NON_MATCH,
> 39 UNSURE). Labeled by the human from target/er-review/er-review-v1.csv; checked that
> only the label and note columns differ from the queue. When T2 is done, run the v1b
> supplement for me to label, then T3.

Orchestrator routing:
- T2 and 03b (the v1b supplement) were already complete when this arrived.
  target/er-review/er-review-v1b.csv (60 rows, sha256 08c02f228229...) goes to the human
  for labeling.
- Read as: T3 runs after the human labels v1b and appends it to
  datasets/gold/er-gold-v1.csv, per decision 01, so that one evaluation covers v1, v1b
  and combined. The orchestrator told the human this in the main session; the human may
  instead ask for T3 now on v1 only.
- step-03b-entity-resolution-engineer-report.md holds er-v1's decisions for the v1b pairs;
  the human was asked not to open it before finishing the v1b labels.
