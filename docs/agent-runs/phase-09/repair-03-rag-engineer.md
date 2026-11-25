# rag-engineer report, repair round 3 (R3-1 to R3-3)
HARNESS: claude-code
MODEL: opus (Claude Opus 5.5), effort high
STATUS: COMPLETE
OWNER: rag-engineer
(Saved by the orchestrator: the ownership hook blocks rag-engineer from writing here.)

WORK ORDER: human-authorized second exception. Replace the clause-and-position subject rule with a sentence rule. A per-researcher number is VERIFIED only when its sentence names exactly one researcher and has no pronoun. Every other number is weakened and labeled, never CONFLICTING. No commits.

FILES CREATED: none
FILES MODIFIED:
- modules/rag/src/main/scala/scholarchain/rag/Verification.scala: HardBreak, SoftBreak and the clause code removed. Added the sentence-end rule, Pronoun, OwnIncluded, IdentityVerbs and a new subjectAt. Step 8 scaladoc updated.
- tests/module-unit/rag/VerificationSuite.scala: 1 new test, 3 updated.
- tests/module-unit/rag/WorkflowSuite.scala: 1 new test, 2 updated.
- Workflow.scala unchanged (summary check calls verifyText).

COMMANDS RUN:
- sbt -batch rag/compile: success.
- sbt -batch rag/test, 3 times: 128/133 (5 intended weakenings), then 135/135, then 135/135 after scalafmt.
- sbt -batch adversarialTests/testOnly (5 named rag suites): 145/145, 715 s (read-only evidence).
- scalafmt, then scalafmt --test: all files formatted.

TEST RESULTS:
- rag 135/135 (was 133). WorkflowRepairGateSuite 6, D5GateSuite 2, WorkflowGateSuite 29, BenchmarkWorkflowSuite 68, BenchmarkQuestionSetSuite 40: 145/145.
- Still VERIFIED: deterministic all-claims, candidate, ranking, bridges, team-size claims.
- Updated assertions (intended weakenings, marked "repair 3"):
  1. Unknown name "John Smith has 41" citing Alice: was PARTIALLY_SUPPORTED, now UNSUPPORTED.
  2. M5 "Bob 41, and Alice 30" (was CONFLICTING) and "Alice 41, and Bob 30" (was VERIFIED): both PARTIALLY_SUPPORTED, 2 unsupported spans.
  3. D5: five forms formerly VERIFIED (", while", bare "and", ";", "a coauthor of Bob ... is Alice ... 41", "more than Bob") and the swapped ", while" form (was CONFLICTING) are now PARTIALLY_SUPPORTED, "names 2 researchers". One-name forms stay VERIFIED.
  4. WorkflowSuite M5 claim-2 and D5 claim-3: two-name sentences with correct values, now PARTIALLY_SUPPORTED.
- New VerificationSuite test: R3-1 inputs 1 to 7 plus "prof.", "e.g.", "J.", "1." all PARTIALLY_SUPPORTED (exact text for 4 and 7); R3-2 and R3-3 weakened with a label, never CONFLICTING; pronoun cases weakened; real sentence ends split; brackets and name periods do not split.
- New WorkflowSuite test: summary inputs 1 and 4 and a true two-name summary are replaced; one-researcher-per-sentence summary kept.

VALIDATION RESULTS:
- Unchanged: answer-synthesis-v1, query-plan-v1, plan-operations-v1, graph templates, abstention.
- Rule: subject = the single researcher ID named in the number's sentence, and only with no pronoun (he, she, they, his, her(s), their(s), its, him, them, -self; "its own included" exempt).
- A sentence ends at . ! ? only if: unmasked and outside brackets; whitespace then capital, digit, quote or bracket follows; the preceding token is a lower-case word of 3+ letters not on the abbreviation list, or a numeric token of 4+ chars. A doubtful period only widens scope.
- Fixed identity lead "'<query>' is ambiguous: candidate | resolved to | matched" is not counted as a mention when the query is an identity query in the bundle.
- Homonyms (one name, 2+ IDs) count as ambiguous.
- Edge, path, ranking, link and publication items still support values and may still CONFLICT (pair values, not per-researcher).

ASSUMPTIONS: pair values may still be CONFLICTING. Running the 5 adversarial suites read-only was within the order.

RISKS (open):
- A one-name, pronoun-free sentence whose number belongs to an unnamed other ("Alice Arden's closest coauthor has 41", "the former/latter") credits Alice: false VERIFIED; the true variant is wrongly CONFLICTING.
- An unlisted lower-case abbreviation of 3+ letters ("dir.") before a capitalized name still splits, so "Alice Arden, like the lab of dir. Bob Brandt, has 30" can VERIFY Bob's 30.
- Identity-lead exclusion trusts one fixed form.
- Homonym display names would weaken deterministic claims on real bundles (none in benchmark).
- Conservative cost: correct comparative prose naming two researchers is weakened or replaced.
- Not done: windows not attributed; ranking swaps unchanged; claim-verifier-v1 not bumped (human); no full sbt check; R3-4 docs. Nothing committed.

MANUAL ACTION REQUIRED: none. Bedrock model ID and cost stay with the human.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer (repair-03-test-engineer.md), then code-reviewer (review-report-04.md), then documentation-engineer.
