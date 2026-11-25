HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high (tier high in `.agents/models.json`, no override)

I've fixed all six MAJOR findings (M1 to M6) in rag main code and added 8 unit tests. `sbt rag/test` passed 131 of 131 on its first run (it was 123 before). I did not run the adversarial suites, as the work order said. So whether the 13 red test-engineer tests now pass is my reasoning from reading them, not something I observed. test-engineer's rerun is the next check. I did not touch tests/adversarial, build.sbt, MODULE.md or STATUS.md, and fixed none of MINOR m1 to m3.

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 09 repair round 1 (the only one), from review-report.md. Fix M1 (F1), M2 (F3), M3 (F2), M4 (F4), M5 (verifier misattribution) and M6 (unloaded windows), with unit tests in tests/module-unit/rag. MINOR m1 to m3 are carried forward. Run only `sbt rag/test`, with no cloud and no commit.

FILES CREATED: none

FILES MODIFIED (all untracked Phase 09 files of mine):
- modules/rag/src/main/scala/scholarchain/rag/Plan.scala
- modules/rag/src/main/scala/scholarchain/rag/Evidence.scala
- modules/rag/src/main/scala/scholarchain/rag/Synthesis.scala
- modules/rag/src/main/scala/scholarchain/rag/Verification.scala
- modules/rag/src/main/scala/scholarchain/rag/Workflow.scala
- modules/rag/src/main/scala/scholarchain/rag/LocalRuntime.scala
- tests/module-unit/rag/PlannerSuite.scala (+2 tests)
- tests/module-unit/rag/WorkflowSuite.scala (+3 tests)
- tests/module-unit/rag/VerificationSuite.scala (+2 tests)
- tests/module-unit/rag/LocalRuntimeSuite.scala (+1 test)
- tests/module-unit/rag/SynthesisSuite.scala (2 assertions added to the existing copy test)
- No prompt or schema file changed.

Changes per finding:

**M1 (F1): pair questions with quoted names, and their supporting publications.**
- Plan.scala:
  - `DirectPair` now accepts names in straight or curly double quotes.
  - New pattern `CollaborationBetween`: "collaboration(s) / joint publications / coauthored papers between X and Y".
  - New cue regex `DirectWords`: collaborated, coauthored, joint publications or papers, direct coauthors or collaborators, "collaboration between". When it appears and the question names two researchers, the plan gets DIRECT_COAUTHORS (the same kind of fallback `wantsPath` has).
  - `CoauthorsOf` and `CollaboratedWith` are unchanged on purpose. Accepting quoted names there would have added DIRECT_COAUTHORS to the temporal questions ("distinct coauthors of "X"").
- Evidence.scala:
  - The PUBLICATION items for every `supportingWorkIds` of a found pair edge are now fetched, before team and path works, still within MaxPublications 12.
  - Deterministic order is now `... pathEv, pair edge, pubs, coauthor lists, community views`, so the bundle cap cuts the long lists before the publications.
- Synthesis.scala: after the pair claim, new claim(s) "Publications supporting the coauthor edge of A and B in window W: "Title" (year); ...". Each cites the edge and those PUBLICATION items and stays within 500 characters and 12 evidence IDs.
- Tests:
  - PlannerSuite "repair M1": quoted pair, quoted and unquoted "support the collaboration between". No pair check for "most joint publications" without names, or for a two-name path question.
  - WorkflowSuite "repair M1", end to end:
    - Alice-Bob: a found edge with 3 joint publications, a VERIFIED claim citing the edge with both names, and a claim citing the PUBLICATION of "Surface code decoders" (2021).
    - Alice-Dan: a found=false edge and a "no direct coauthor edge" claim.
    - An ambiguous "Wei Wang" endpoint abstains and runs no coauthor-pair-v1 query.

**M2 (F3): topic followed by "in <year>".**
- `TopicIn` lookahead now also stops at `in|during [the] [year(s)|window|period] YYYY`.
- `topicLike` also had to change. The old rule dropped any phrase containing a NotTopics word, and "network" is one, so "Network Time Synchronization Technologies" was dropped even with the lookahead fixed.
  - New rule: refuse a phrase when its last word is a graph term, when every word is one, or when it starts with a determiner (their, own, same, the, ...).
- Test, PlannerSuite "repair M2":
  - Topics extracted from "...in Quantum Error Correction in 2015-2026" and "...in Network Time Synchronization Technologies in 2010-2014".
  - No topic for "in the year 2012", "in 2010-2014", "the largest author teams", "their own community" or "collaboration networks".

**M3 (F2): communitiesConnected wording.**
- The bridge claim now says "touching N communities, its own included". N is the stored value; no N-1 is computed.
- The verifier maps "touching/linking/connects/spans N communities" and "N communities, its own included" to `communitiesConnected`.
- "N other communities" now maps to `otherCommunityCount`, which no item holds, so such a claim is weakened to PARTIALLY_SUPPORTED with "[unverified]".
- Tests:
  - VerificationSuite "repair M3": "touching 2 communities, its own included" is VERIFIED; "connects 3 communities" is CONFLICTING; "links 2 other communities" is PARTIALLY_SUPPORTED and weakened.
  - SynthesisSuite: Bob's bridge claim has the new phrase, and no claim says "other communities".

**M4 (F4): researcher rows.**
- `Verified.answer` gives no rows when the answer abstains for any reason (evidence stage, model abstention, or no surviving claim).
- In a non-abstaining answer, it keeps only rows that a kept claim cites.
- Test, WorkflowSuite "repair M4":
  - A model whose only claim (77) is removed: the answer abstains, UNSUPPORTED, with no rows.
  - A model claim citing only Alice's record: only Alice's row, with claimIds [claim-1].

**M5: verifier misattribution.**
- In steps 7 (keyed numbers) and 8 (other numbers), a number belongs to the nearest researcher named before it. When no name precedes it, it belongs to all named researchers.
- Per-researcher records (researcher-metrics METRIC_RECORD, community-membership COMMUNITY_RECORD, IDENTITY_RESOLUTION) support the number only when they are that researcher's.
- Edge, path, ranking, cross-community-link and publication items still support their values for anyone.
- Claims that name no researcher work as before.
- The summary check uses the same code, so it is covered too.
- Tests:
  - VerificationSuite "repair M5":
    - Alice "30" citing Alice's and Bob's records is CONFLICTING, with the reason "of Alice Arden".
    - Swapped values across two names are CONFLICTING; correct values are VERIFIED.
    - An unkeyed "9 papers" (Bob's value) is weakened.
    - Citing only Bob's record never verifies Alice.
    - A path hop count still verifies.
  - WorkflowSuite "repair M5": the misattributed model claim is removed as CONFLICTING, the correct two-name claim is kept VERIFIED, and the misattributed summary is replaced.

**M6: unloaded windows.**
- `LocalRuntime.load` now builds the `RuntimeContext` from a config whose `graph.windows` holds only the loaded windows. So the planner's default window, WindowChoice, the GraphRunner window allowlist, `GraphTemplates.forConfig` and `metrics.temporal` all see only loaded windows.
- Test, LocalRuntimeSuite "repair M6" (windows 2015-2026 and 2000-2009 loaded):
  - The context's windows equal the loaded set.
  - A no-year path question plans 2000-2026, is answered from 2015-2026 with a window note, finds Alice -> Bob -> Carol -> Dan, queries only loaded windows, and has no note naming 1950-2026.
  - A request for 2010-2014 maps to a loaded window with a note.

COMMANDS RUN (output observed):
- `timeout 1500 sbt -batch rag/test` (default SBT_OPTS, foreground): compiled 6 main and 5 test sources; "Passed: Total 131, Failed 0, Errors 0, Passed 131", "[success] Total time: 40 s". One run.
- `timeout 300 scalafmt --test` on the 11 files listed above: "All files are formatted with scalafmt :)". Only my files.
- Read-only: `python3 -I` print of the benchmark questions and expected answers, and greps of tests/adversarial, core Config and Validate.answer, and the oracle field names.

TEST RESULTS:
- `sbt rag/test`: 131 passed, 0 failed.
  - Per suite: PlannerSuite 10, WorkflowSuite 12, VerificationSuite 10, LocalRuntimeSuite 3, SynthesisSuite 6, PipelineSuite 11, StageSuite 9, BedrockLlmSuite 4, others unchanged.
  - Every pre-existing rag test stayed green with no edits to its assertions.
- Not run (work order): adversarialTests (BenchmarkWorkflowSuite, WorkflowGateSuite, BenchmarkQuestionSetSuite) and the full `sbt check`.
- What I expect when test-engineer reruns, from reading their checks (not observed):
  - F1, 10 tests (bq-07, 08, 09, 31, 32, D and A): should now pass.
  - F2 (bq-21 A): should pass.
  - Planner P (07 to 09, 25 to 27, 31, 32): should pass.
  - F4 gate test: should pass.
  - One open risk for bq-07/08/31/32 D: the oracle's `supportingWorkIds` must equal the stored `representativeWorkIds` (limit 5). For 2 joint publications they should.

VALIDATION RESULTS:
- Prompt and schema versions: unchanged (answer-synthesis-v1 prompt and schema, query-plan-v1). Component version strings are unchanged too (see RISKS).
- Allowed plan operations: plan-operations-v1, unchanged. DIRECT_COAUTHORS is now also planned for two names plus a direct-collaboration cue.
- Graph templates: unchanged. Pair publications use node-v1 through the resolver templates every plan already allows. Windowed templates now accept only loaded windows locally.
- Verification rules changed: per-researcher attribution of numbers (M5); the communitiesConnected phrase keys, and "N other communities" left unsupported (M3).
- Abstention: no researcher rows in any abstaining answer; non-abstaining answers keep only rows that a kept claim cites.
- Benchmark: not run here (test-engineer's rerun).

ASSUMPTIONS:
1. I picked "the nearest researcher named before the number" for M5 instead of the brief's looser "any named researcher". The looser rule still verifies swapped values in a claim that names two researchers. This one can wrongly flag a correct model claim like "Bob, a coauthor of Alice, has 30", but that only removes or weakens a claim; it never verifies a false one.
2. A phrase whose last word is a graph term, or that starts with a determiner, is not a topic. That is the smallest rule that keeps "Network Time Synchronization Technologies" and still refuses the phrases the old rule refused in every existing test.
3. Under M6, a no-year question on a window subset is answered from the widest loaded window, with the window note. That answer is true for the window it names, and the note says so.

RISKS:
- Not done:
  - MINOR m1 to m3 (carried forward);
  - attributing a window to a researcher. A model claim that pairs one researcher's value with the window of another cited record still passes the window check. Numbers are now attributed, windows are not.
  - Ranking claims: swapped values inside one cited ranking ("Carol (41), Alice (52)") are still accepted, because ranking values are kept "as they are" per the brief. Only the order is checked.
  - M7 (heap, build-engineer); STATUS.md; MODULE.md; any commit.
  - Component version strings were not bumped (planner-v1, claim-verifier-v1, deterministic-synthesis-v1, evidence-pipeline-v1 and local-runtime-v1 now behave differently). The phase is uncommitted, so I kept v1; say if you want bumps.
- Known failure modes:
  - Subject attribution is positional. A number placed before its subject's name in prose is checked against the previous name.
  - A title containing a double quote breaks title checking in the pair-publication and hop claims (as before).
  - Two-name questions with a direct-collaboration cue also fetch both researchers' coauthor lists (up to 50 edges each). This fits the 120-item bundle cap and the 250-query budget.
- State left behind: only the files listed above changed, plus build output under modules/rag/target.
- Notes for modules/rag/MODULE.md (documentation-engineer):
  - No public API signature changed.
  - Behavior to document: pair questions accept quoted names, "support the collaboration between X and Y" and direct-collaboration cues with two names; pair answers cite the edge's supporting PUBLICATION items; topics are now extracted before "in <years>"; bridge claims read "touching N communities, its own included"; the verifier ties numbers to the named researcher's own records; "N other communities" is unverified; abstaining answers have no researcher rows, and rows need a citing claim; LocalRuntime's context config holds only the loaded windows, so the default window is the loaded span and is answered from the widest loaded window with a note.
  - agent-report.md line 142 ("no researcher rows") is now true on every abstention path.
  - docs/runtime-workflow.md should get the verification rule and abstention changes above.

MANUAL ACTION REQUIRED: none from me. M7 (fork adversarialTests with a larger heap) stays with build-engineer through the orchestrator.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: build-engineer M7. Then test-engineer reruns the benchmark and gate suites, then one full `sbt check`, then a fresh code-reviewer re-check (review-report-02.md).
