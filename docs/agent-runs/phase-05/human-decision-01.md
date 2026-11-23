# Phase 05, human decision 01 (received in the main session after step 02, 2026-11-23)

Verbatim:

> Decision: I am labeling the queue as is, now. After T2 finishes, have
> entity-resolution-engineer generate a supplementary queue,
> target/er-review/er-review-v1b.csv, with up to 60 additional pairs from the same
> samples that are likely non-matches: pairs inside the same block from different
> clusters, different DBLP homonym numbers, different ORCIDs, or compatible initials
> with no shared context. Same columns and rules as v1 (no scores, paths or predicted
> labels; traceable to source records). I will label it after the main file and append
> it to datasets/gold/er-gold-v1.csv. Record the class imbalance and the supplement in
> the evaluation report.

Resolves: the class-imbalance question in step-02-entity-resolution-engineer-report.md,
RISKS 1.

Orchestrator routing:
- New task 03b (T1b), entity-resolution-engineer, after T2 (step 03) completes, so that
  only one sbt runs at a time. Report: step-03b-entity-resolution-engineer-report.md.
- T3 (evaluation) reports the class imbalance and the supplement, and reports v1 and v1b
  rows separately as well as combined, because v1b rows were selected by rules that
  overlap er-v1's conflict and rejection evidence.
