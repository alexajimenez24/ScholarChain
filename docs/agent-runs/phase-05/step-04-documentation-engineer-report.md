HARNESS: claude-code
MODEL: sonnet (standard tier, default; effort per .agents/models.json)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 05 step 05. Write docs/entity-resolution.md, update modules/entity-resolution/MODULE.md, update STATUS.md. Record human decision 04, mark step 05 done, and set the next task to Review 1.

FILES CREATED:
- /home/ajcreator/scholarchain/docs/entity-resolution.md

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/MODULE.md
- /home/ajcreator/scholarchain/STATUS.md

What each file now says:
- docs/entity-resolution.md puts the decision 03 and 04 caveats in a section at the top:
  - The 13 researcher NON_MATCH labels are known labeling errors ("different chapter", not "different person"). The equal-ORCID pair mc:researcher:10c07413d756444b3991 is among them.
  - The 10 researcher false positives are likely not real errors of er-v1.
  - Researcher precision 0.938 is understated.
  - The reject side is effectively unmeasured.
  - The decision 03 limitation is quoted in the human's words.
  - It then covers purpose and pipeline position, normalization and exact IDs, features, policy er-v1 with its rules, thresholds and conflicts, the adjudication port, outputs and layout, the review queue (v1, and v1b left unlabeled and unused), and the evaluation.
  - Closing section: limits and not done.
- MODULE.md has the purpose, public API for the three packages, run commands, output layout, done, not done, known issues and dependencies. The known issues cover the items you listed, and I added N4 as open, the sbt-backgrounding note and the `*/` doc-comment pitfall.
- STATUS.md has the following changes:
  - A decision 04 note, a step 05 note and the table row marked done.
  - Review 1 marked next.
  - Open items 1 and 2 closed by decision 04.
  - Item 3 (target/** gap) and N4 left open.
  - The Phase 05 summary line near the top.

COMMANDS RUN: ls and sed/grep/awk reads for checks (all exit 0), plus `git diff --stat -- build.sbt`. No sbt.

TEST RESULTS: none run; sbt was not run, as instructed.

VALIDATION RESULTS:
- Every number in the two docs comes from output-er-evaluation.md, agent-report.md (T2), step-02 or step-03 reports, or human-decision-01 to -04. No number from ~/ref-phase-05 was used.
- Resource file names under modules/entity-resolution/src/main/resources/scholarchain/er/ and the source file list were checked on disk. I corrected the supplement resource name to `review-supplement-v1b.json`.
- No em dashes in either doc.
- Lines over 90 characters remain only for markdown tables, long sbt commands, a resource path and one link.
- The documented sbt commands were not replayed, and both docs say so.

ASSUMPTIONS:
- The ER-J1 and ER-J2 names, `LocalMatchRunner` in test scope and `MatchJob.main` as the EMR entry come from the T2 report only. I did not open the sources.
- The two-test LeakageSuite is cited as existing. I did not verify what it asserts.

RISKS:
- The v1b API is described by file name only. I read the step 03b report only for structure (it was not required reading), and no v1b decision counts appear anywhere.
- The "recall and unresolved-rate figures are the only ones this gold set supports without caveat" wording in docs/entity-resolution.md is my reading of decision 04. The reviewer should check it, since the 4 work DOI conflicts also depend on labels.
- The evaluation section reproduces numbers by hand copy from output-er-evaluation.md, so the reviewer should re-check them.
- The rules table is condensed. R-R4 and the other rules are summarized from the T2 report, not from the source.
- STATUS.md still carries an older "Half done" line that mentions N4 and the Phase 04 items. I left it unchanged.

MANUAL ACTION REQUIRED: none for this task. Open item 3 (target/** ownership gap) and N4 are still the human's.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: Review 1, a fresh code-reviewer on the whole Phase 05 diff (review-report.md). Orchestrator saves this report as docs/agent-runs/phase-05/step-04-documentation-engineer-report.md.
