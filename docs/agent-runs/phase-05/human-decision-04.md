# Phase 05, human decision 04 (received in the main session after step 04 / T3, 2026-11-23)

Verbatim:

> Answer to the label questions: the 13 researcher NON_MATCH labels were judged by
> "different chapter", not "different person", which was the wrong question for researcher
> pairs; this includes the equal-ORCID pair mc:researcher:10c07413d756444b3991. I am not
> relabeling or re-running T3. Keep datasets/gold/er-gold-v1.csv and the evaluation as they
> are, and state this in output-er-evaluation.md, docs/entity-resolution.md and the module
> notes: those 13 labels are known labeling errors, so the 10 researcher false positives
> are likely not real errors of er-v1, researcher precision (0.938) is understated, and the
> reject side is effectively unmeasured. Continue with step 5.

Answers: output-er-evaluation.md section 9, items 1 and 2.

Orchestrator routing:
- output-er-evaluation.md: section 11 added by the orchestrator, quoting this decision; the
  measured numbers in sections 1 to 10 are unchanged.
- Step 05 (documentation-engineer) resumes: docs/entity-resolution.md and
  modules/entity-resolution/MODULE.md state the same, and STATUS.md is updated.
- The gold file and the decision run stay as they are; T3 is not re-run.
