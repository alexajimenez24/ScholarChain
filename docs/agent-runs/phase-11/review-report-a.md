HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 step 2, Review A (core, hadoop, entity-resolution, spark, datasets, their tests, the hadoop adversarial suites, and the diff of that half). Also: a fairness verdict on ReducerWiringSuite J04 OVERSIZE_BLOCKS, repair briefs for C12, C14 and C18, and a MUST/CARRY class on every item.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git status --short; git diff --stat; grep, sed -n and ls reads of modules/{hadoop,spark,core,storage,rag} sources, MODULE.md files, tests/module-unit/{hadoop,spark,core}, tests/adversarial/.../hadoop, docs/spec/assignment.md:2236-2252, and the phase-04, 06 and 09 gate and review notes. I did not run sbt, as the work order said.
TEST RESULTS: none run. I rely on test-report.md: adversarialTests 263 total, 5 red. Every module-unit project is green.
VALIDATION RESULTS: REQUEST_CHANGES
Diff of my half: one new file, tests/adversarial/.../hadoop/ReducerWiringSuite.scala, which is test-engineer territory, so there is no ownership breach. No production file in my half changed (git status).

Findings:
- A-1 MINOR, MUST (a red test breaks the gate item "all tests pass"). Files: modules/hadoop/src/main/scala/scholarchain/hadoop/BlockLogic.scala:156-168 and Jobs.scala:338-342.
  - Defect: a researcher block above --max-block whose full-name sub-blocks all fit under the cap is neither counted OVERSIZE_BLOCKS nor rejected. The work-block path in the same job does count the same event (BlockLogic.scala:315-322 builds a refused BlockResult, and emit counts OVERSIZE_BLOCKS and writes a reject line).
  - Input: --max-block 3 and name block zhang_h with 4 distinct full names. Observed FALLBACK_BLOCKS_REFUSED=2 and OVERSIZE_BLOCKS=0.
  - Impact: the counter covers only part of what it claims, and J04 gives one counter two meanings. No data is lost.
  - Owner: hadoop-engineer. documentation-engineer then aligns MODULE.md:181-182 and 235-236.
- A-2 MINOR, CARRY. Files: ReducerWiringSuite.scala:144-145 and 215-216.
  - The comment says the coauthor, venue and year fallback blocks each hold 4. Keys `z/zhang*` give venueId None (DblpParser.scala:125-129), so only the co: and y: blocks are refused.
  - `refused >= 1` still passes if one of the two refusals is lost.
  - Fix: assertEquals(refused, 2L) and correct the comment.
  - Owner: test-engineer. It is a few lines in a file test-engineer reruns anyway, so it may go in that round.

Fairness verdict, ReducerWiringSuite "J04: the primary name block above --max-block that triggered fallback is OVERSIZE_BLOCKS": FAIR.
- Spec 1161 and 2245 ask for counters for oversize blocks and a policy for oversize groups.
- zhang_h is oversize by the module's own trigger (MODULE.md:241: "a researcher block with more than --max-block clusters").
- MODULE.md:235-236 names OVERSIZE_BLOCKS for "a researcher or work block above --max-block". The work path already behaves that way.
- Only the code scaladoc (BlockLogic.scala:13-15) supports the narrower reading.
- A docs-only fix would make the test NOT FAIR, but it would leave work and researcher blocks counted differently. I recommend the code fix plus a docs alignment.

Repair brief for A-1 (hadoop-engineer):
- Likely cause: BlockLogic.block sets `oversize` only on a refused full-name sub-block (line 164). The primary block that triggered the split has no BlockResult.
- Fix shape, constrained by the existing tests: count OVERSIZE_BLOCKS exactly once per primary researcher block above the cap, with exactly one reject line. Fold any refused sub-block reason into that line rather than counting the sub-block again.
- Keep the primary reason free of `zhang_h|`: ReducerWiringSuite:222 counts `|` reasons as fallback refusals.
- Doing this in Jobs.reduceMentions leaves BlockLogic.block's result shape unchanged.
- What else could break:
  - RejectsAndLimitsSuite:255 pins the exact J04 map {Input 2, Accepted 2, OVERSIZE 1, Rejected 1} and reason "has 25 clusters > 3". The smith_j case has a refused sub-block too, so double counting turns it red.
  - JobLogicSuite:298-305 pins results.size 2 and one refused. Changing BlockLogic.block's output turns it red.
  - RepairLogicSuite:57-81.
  - ReducerWiringSuite:208-225 needs REJECTED_RECORDS = oversize + refused, and one reject line per refusal.
  - CommonNameSuite:126-142 needs OVERSIZE_BLOCKS >= 2.
- Must stay green: the hadoop module-unit suite (98), all 8 adversarial hadoop suites, and the 10 tests of ReducerWiringSuite.

Carried items in my half, with briefs:
- C12 MUST (needed before the 100,000-work EMR run).
  - Verified: Canonical.scala:166-170 does an unbounded collect_list of each contradicted cluster's links into one row, which grows executor memory.
  - domain-engineer: add a GraphConfig field such as `identityCutMaxClusterLinks: Int`, with a range check in Config.scala's integer bounds (near :231-248), a value in every shipped config, and ConfigSuite cases (missing, 0, above the range).
  - spark-engineer: count links per contradicted label before the collect. For a cluster above the bound, never build the row. Refuse all its accepted links (prefer under-merge to over-merge) or fail the job; record the choice.
  - spark-engineer: report `<prefix>ClustersOverCutBound` and the links refused. `invariant_*ContradictionsMerged_*` must stay 0.
  - Tests: a known graph exactly at the bound is cut as now; bound+1 is refused and counted.
  - Must stay green: CanonicalSuite, PipelineSuite, RunManifestSuite and ConfigSuite.
- C14 MUST (named in the addendum). A core contract change is required, so domain-engineer goes first. The addendum names only spark, storage and rag.
  - Why the extraFields route does not work: storage decodes the core record after dropping extraFields, and ResearcherMetricCache stores core ResearcherMetrics (Tables.scala:425-457). An extra field never reaches the runtime.
  - Why the field cannot have a default: decoding re-encodes and compares (JsonLines.scala:20), and Spark writes nulls (Export.scala:263). With a default None, uPickle drops the key on re-encode, so every `"communitySize": null` line would be rejected.
  - core (domain-engineer): add `communitySize: Option[Long]` with no default. Bump SchemaVersions.Metrics to researcher-metrics-v2 and update Supported. Validate: Some(n >= 1) exactly when communityId is Some. Add a v2 wire fixture beside tests/module-unit/core/resources/wire/researcher-metrics.json.
  - spark (spark-engineer): join r.communities' communitySize onto the metrics by (communityId, window) in WindowAnalytics (no collect); the JsonSpec is unchanged. Add an ExportSuite or WindowAnalyticsSuite assertion on a known graph. No new local run.
  - storage (storage-engineer): Tables.scala:449 accepts v2. Add a loader contract test on a JSON-lines fixture with the field, together with C13.
  - storage, replay risk: ResearcherMetricCache refuses different metrics under the same metricVersion, so v2 rows need a new metricVersion or a fresh table.
  - rag (rag-engineer): Agents.scala:916 lists "communitySize" as missing only when it is None, and otherwise puts its value in the COMMUNITY_RECORD. Tests: a "N members" claim is VERIFIED when present and stays missing when None.
  - Compile ripple: every constructor of ResearcherMetrics, in core Fixtures, RagWorld, StorageSuite, VectorSuite, services QueryFixture (service-engineer) and adversarial MiniWorld (test-engineer).
- C18 MUST (gate item "every production behavior has a relevant test").
  - ReducerWiringSuite now runs both wirings at job level: J03 at :170-206, J04 at :208-245. That closes N7's black-box half and the ISO-8859-1 case.
  - hadoop-engineer still owes the module-unit cases N7 asked for, in RejectsAndLimitsSuite style with --max-block small.
  - J03: the exact counter map {OVERSIZE 1, Rejected 1} and the reason `exact key ... holds N works > M; work pairs not expanded`.
  - J04: the exact map with FALLBACK_BLOCKS_REFUSED, the reject line, and OVERSIZE_BLOCKS as defined by A-1. Write it together with A-1.
  - The J01 reject-text check follows N1 (K07, CARRY).
- CARRY confirmed for my half: K07, K08 and K09 (hadoop), K10 (entity-resolution), K12 (domain-engineer) and K06 (build-engineer; the dataset Python tests ran 19/19 by hand). None is critical or major, and none is needed before the cloud phases.

ASSUMPTIONS: The full check in test-report.md is the current test state. The "complete repository" pass over my half covers the Phase 11 diff and the carried items. Earlier phases' code was reviewed at their gates, so this pass spot-checks only: Spark driver collects (all bounded, with comments) and the J03/J04 counter wiring. For A-1 I judged the fix against the spec plus MODULE.md (test-engineer's sources), not the scaladoc.
RISKS: Not reviewed:
- entity-resolution matching and over-merge logic, beyond the carried items;
- the datasets/ scripts and manifest;
- core Validate and Config, outside the C12 and C14 touch points;
- Spark algorithms (k-core, bridges, paths, temporal);
- the other adversarial hadoop suites, line by line.
No test was run by me. Review B covers storage, rag, services, the other tests, the control plane and docs.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator writes repair-plan.md. The order is: hadoop-engineer (A-1, C18 unit cases); domain-engineer (C12 field, C14 core v2); spark-engineer (C12 bound, C14 export); storage-engineer and rag-engineer (C14); test-engineer (A-2, rerun); then a fresh review of the repair diff.

----- follow-up report of the same reviewer -----

HARNESS: claude-code
MODEL: opus (claude-opus-5-5), effort high (tier high, .agents/models.json)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 step 2, Review A, part 2 (the coordinator's follow-up). I finished the parts of my half that part 1 left unreviewed. This report holds only the new findings, the updated verdict and what is still unreviewed.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: grep, sed -n, ls and wc reads of the sources named below, of scholarchain-v1.yaml and of the hadoop adversarial suites. No sbt, no cloud, no write.
TEST RESULTS: none run (work-order limit); test-report.md is still the baseline.
VALIDATION RESULTS: REQUEST_CHANGES, unchanged. The blockers are still A-1 (fair red test) and the MUST carried items C12, C14 and C18 from part 1. Part 2 found nothing that loses or corrupts data, and no new critical or major finding.

New finding:
- A-3 MINOR, CARRY. Some adversarial hadoop assertions check only a lower bound, so they would pass if a count doubled.
  - Where: DuplicateIdSuite.scala:157 (`n >= 1L`), MissingFieldSuite.scala:137 (MISSING_KEY >= 1) and :177-178 (INVALID_IDENTIFIER >= 1), CommonNameSuite.scala:133 (OVERSIZE_BLOCKS >= 2). A-2 (ReducerWiringSuite:216, `refused >= 1`) is the same kind.
  - Failing input: a regression that counts each oversize block twice in J04 Jobs.emit, or each invalid ORCID twice, passes all of them.
  - Impact: over-counting in the J04 counters goes undetected at black-box level. Exact maps exist only in module-unit for a few cases (RejectsAndLimitsSuite:255 and :270).
  - Fix: where the fixture fixes the count, assert the exact value.
  - Owner: test-engineer. It can go into the A-2 round if that file is open anyway.

Checked and verified, no finding:
- Entity resolution (items 1 and 6):
  - MatchPolicy's rule order is followed. A shared work is REJECTED before any ID rule. An ORCID disagreement is CONFLICT unless the score also rejects. A DBLP homonym suffix is REJECTED.
  - The LLM is consulted only inside the band and only for scored decisions. It cannot change identifiers: the endpoints are checked (Adjudication.scala:203) and confidence must be in [0, 1]. Below minLlmConfidence the pair stays UNRESOLVED.
  - Missing profiles are counted (MatchJob.scala:341, PROFILE_MISSING) and recorded in the audit (Resolver.scala:110-112). A failed work-profile decode is counted the same way.
  - Merged candidate copies take the maximum of each feature, which is conservative for conflict flags. Gold labels are read only by eval/EvaluateMain, which refuses to write under datasets/gold.
- Spark (items 1 and 5):
  - Connected components, label propagation, coreness, multi-source BFS, betweenness and path rebuilding (GraphAlgorithms.scala) collect nothing but counts and one-row results.
  - Coreness uses the h-index fixpoint; it is correct, and `kcoreMaxK` caps it.
  - Betweenness follows Brandes' dependency rule, and BFS truncation is reported (WindowAnalytics.scala:180).
  - A cut-off in components or coreness is an invariant failure; a cut-off in communities is a reported flag (documented).
  - CoauthorGraph rejects unknown mentions and works and pairs without authorship, drops self pairs, applies the team cap per canonical work, and keeps works without a year out of every window.
  - Temporal compares only adjacent non-overlapping windows. With the shipped windows (1950-2026, 2000-2009, 2010-2014, 2015-2026) this gives the 2 intended comparisons.
  - Every driver collect is bounded and commented.
- Core (items 2 and 4):
  - Spark's edge-ID formula matches Ids.coauthorEdge: same fields, same order, same label.
  - JsonLines decoding is strict, with canonical re-encoding (this is the basis of the C14 brief).
  - Config validation covers the thresholds, a lower bound on every iteration count, the windows, the aws-mode required fields, and number overrides that cannot be clamped.
  - The only missing upper bound that matters before EMR is C12. Others, such as betweennessSampleSources, are cost-only and are covered by the human's sign-off on run cost.
- Datasets (items 1 and 4): validate-manifest.py checks the required fields, at least 3 required enrichment datasets, the `${VAR}` bucket placeholders, the account-ID pattern, the forbidden suffixes, and every sample file's SHA-256 and byte size. I found no defect.
- Hadoop and the ER drivers (item 1): an output folder is deleted only when it is the run's own incomplete output (no `_SUCCESS`, Pipeline.scala:222-225, MatchJob.scala:562-566). A completed run is skipped. Exported JSON-lines folders are run-scoped.

MUST or CARRY across all of Review A:
- MUST: A-1, C12, C14, C18.
- CARRY: A-2 and A-3 (both may be folded into the test-engineer round), and K06 to K10 and K12 as listed in part 1.

ASSUMPTIONS:
- For an LLM-in-band design and for ER defaults that trust a source's own clustering (an identical DBLP string), I judged against the documented er-v1 policy, not against a stricter policy I might prefer.
- For lower-bound assertions I treated a black-box test that passes when a count doubles as a test gap: MINOR, not MAJOR, because exact module-unit tests pin the main paths.
RISKS, still not reviewed:
- entity-resolution: Adjudication.scala lines 1-200 (prompt rendering, cache-key derivation), ExactIds, ErNormalize, TextSimilarity, the review and eval tools, BedrockAdjudicator.
- spark: Canonical.scala beyond the cut (work canonicalisation, :240-801), Summaries.scala, Abstracts.scala, Paths.scala selection, Bridges.scala formula, RunManifest.scala.
- core: Validate.scala per-record rules, Normalize, the aws/ SigV4 code (K12).
- hadoop: DblpInputFormat, DblpParser, SourceNormalizers, TeamLogic and ExportLogic, line by line.
- datasets: fetch_samples.py and make-fixtures.py.
- tests: the module-unit suites of these modules, beyond the assertions named above.
- Nothing was run, so none of this is checked by execution.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: as in part 1. repair-plan.md takes A-1, C12, C14 and C18 to their owners (hadoop-engineer; domain-engineer then spark-engineer, storage-engineer and rag-engineer). A-2 and A-3 go to test-engineer if that round opens the files. Then test-report-02.md and a fresh review-report-02.md.
