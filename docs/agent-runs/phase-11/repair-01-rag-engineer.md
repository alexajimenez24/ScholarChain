HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (rag-engineer tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 11 repair round 1, rag items C09 (R6-1/B-10, MAJOR), C11 rag part (B-16), C14 read part (B-15).
FILES CREATED: tests/module-unit/rag/FilteredRankSuite.scala (6 tests); tests/module-unit/rag/CommunitySizeSuite.scala (7 tests)
FILES MODIFIED: modules/rag/src/main/scala/scholarchain/rag/{Verification,Synthesis,Agents,Evidence,LocalRuntime}.scala; tests/module-unit/rag/LocalRuntimeSuite.scala (+3 tests, optional communities table in the fixture export)
COMMANDS RUN:
- `timeout 30m sbt rag/scalafmt rag/Test/scalafmt rag/test`: exit 0, 46 s.
- `SCHOLARCHAIN_BENCHMARK_RUN_DIR=<empty scratch path> timeout 30m sbt "adversarialTests/testOnly *FilteredRankGateSuite *Workflow*Suite *RuntimeEdgeSuite"`: exit 1. This was my misreading of "no use of the 25,000-work export". 16 passed (FilteredRankGate 7 + RuntimeEdge 9), 121 skipped by their own assume, and 1 failed: BenchmarkWorkflowSuite "semantic retrieval is scored..." has no assume and could not find the manifest under the redirected path. It is an artifact of the redirect, not a defect.
- `timeout 30m sbt "adversarialTests/testOnly *FilteredRankGateSuite *Workflow*Suite *RuntimeEdgeSuite"` exactly as prescribed: success, 449 s. The harness moved it to the background; I waited without polling.
TEST RESULTS:
- rag: Passed: Total 154, Failed 0, Errors 0 (138 before, +16).
- adversarial gate: Passed: Total 138, Failed 0, Errors 0, 0 skipped. 8 suites ran: FilteredRankGate, WorkflowGate, WorkflowRepairGate, R3, R4, D5, BenchmarkWorkflow and RuntimeEdge. The 3 red R6-1 tests are now green.
- Full `sbt check`: NOT RUN (not a gate in this work order; one sbt at a time). The test marker is not refreshed, so the stop hook still asks for a check.
VALIDATION RESULTS:
- C09: new `ClaimVerifier.rankingFilter(e): Option[String]` reads the ranking item's `filter`, sanitized and bounded to 120 characters. A filter starting with "none" gives None. `amongText(e)` gives " among <filter>". One helper now feeds:
  - the verifier's rank `Held` label (model prose) and its `about` text (structured claim);
  - the deterministic ranking claim, which already said "among";
  - the deterministic summary lead "Researchers ranked by M in window W among F: ...". It used to drop the filter: a second R6-1 site, now fixed;
  - the step-7 literal blanking, so the deterministic text and the blanking always match.
  Result: "rank of Alice Arden (id) by distinctCoauthorCount among authors of works with topic quantum error correction published in 2015-2026 in window 2015-2026: 1." Unfiltered rank texts are unchanged (tested). The limit is not named, because a position is the same in any prefix of the order. Values on a filtered ranking are global values and stay unqualified.
- Tests on RagWorld, where the filtered rank 1 (Alice) is global rank 2:
  - filtered rank 1 is VERIFIED with the filter text;
  - Alice's rank 2 against the filtered ranking is CONFLICTING;
  - Carol's rank 1 against it is UNSUPPORTED;
  - unfiltered Carol rank 1 is VERIFIED with no "among";
  - on the model path, both the prose rendering and claim-N-figure-1 name the filter;
  - on the deterministic path, the ranking claims (VERIFIED) and the summary name the filter;
  - helper bounds and sanitizing.
- Other rank renderers checked: the Workflow researcher cards render no rank; the RemovedClaim text shows model input; no other place found.
- C11: `LlmSynthesizer.Version` is "llm-synthesis-v2" (tested in FilteredRankSuite). No other llm-synthesis-v1 string exists in rag or prompts; the answer-synthesis-v1 files are historical. `community-agent-v1` became v2 and `local-runtime-v1` became v2 (new behaviour). Nothing pins them.
- Claim verifier kept at claim-verifier-v3. Why: the construction rule is unchanged, and the fix restores v3's documented contract (the text is written from the item that holds the value). v3 is also named in the immutable answer-synthesis-v2 schema description and in every adversarial gate suite. Adversarial tests pin v3 in scaladoc only; module-unit WorkflowSuite:132 asserts it. Bumping to v4 is the human's call.
- C14: `CommunityAnalystAgent` takes a 4th defaulted parameter `communities: Option[CommunityReader] = None` and calls `community(ctx.graphRunId, communityId, window)`. New record value `communitySizeStatus` takes one of five values:
  - `present`: `communitySize` and `communitySizeEvidenceId` are added, `communitySize` leaves `missingFields`, the "not available" note is dropped, and the exported row joins the view and the bundle as provenance;
  - `not-stored` (None from the reader): note names the run and window;
  - `read-failed`: a store Left, or a returned record of another run, community or window. The note is fixed text and the store message is not shown;
  - `no-store` (no reader): the old note, unchanged;
  - `no-community`.
  When missing, the size is never 0. The deterministic membership claim adds "of {figure} members" only when the size is present and cites the exported row.
- Tests: "N members" is VERIFIED when the size is present and absent otherwise. A model figure (Bob, communitySize, 3) is VERIFIED; 4 is CONFLICTING; with None or no reader it is UNSUPPORTED.
- LocalRuntime: reads `jsonl/communities/windowLabel=W/recordKind=community` against `dynamodb.json` `communities.source` and loads it through storage's CommunityLoader into `CommunityCache(local.kv, cfg.dynamo.researcherMetricTable)`. An export without that entry gives `communities = None` (missing evidence). Any count mismatch, refused row or rejected row fails the load (tested). Report rows key: `communities/windowLabel=W/recordKind=community`.
ASSUMPTIONS:
- The Spark community row's `values.window` and partition naming follow ExportSuite (`windowLabel=W/recordKind=community`).
- A window that is not counted and has no folder holds no communities.
- A failed community read degrades to missing evidence rather than failing the whole answer.
RISKS:
- Not done: full sbt check; no change to services.
- BenchmarkWorkflowSuite on the 25k export still expects the size to be missing, and it is: that older export has no communities table.
- MODULE.md notes for documentation-engineer:
  - new public API: `RuntimeStores.communities`, the `CommunityAnalystAgent` 4th parameter, `CommunityAnalystAgent.SizeStatus`, `ClaimVerifier.rankingFilter` and `amongText`;
  - versions: llm-synthesis-v2, community-agent-v2, local-runtime-v2;
  - close R6-1, R6-3 and the "Community size not done" item (now: reported missing only when no store or record exists);
  - rag tests: 154.
MANUAL ACTION REQUIRED: human decision on bumping the verifier to claim-verifier-v4 (optional, see above).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: service-engineer wires production `RuntimeStores(..., communities = Some(new CommunityCache(kv, cfg.dynamo.researcherMetricTable)))` in queryService. The API is additive, so services compile unchanged. Then the test-engineer rerun, the full sbt check and a fresh review.
