# Runtime collaboration workflow (Phase 09)

This page describes the ten-stage workflow in module `rag`
(`collaboration-workflow-v1`).
Every statement comes from the Phase 09 reports in `docs/agent-runs/phase-09/`
(`step-01-rag-engineer-report.md`, `agent-report.md`, `test-report-02.md`,
`output-benchmark.md`, `review-report.md`, `repair-01-rag-engineer.md`,
`repair-01-build-engineer.md`, `repair-01-test-engineer.md`) and the repository.
Review 1 was REQUEST_CHANGES; repair round 1 fixed F1 to F4 and M1 to M7 except that M5
was partial.
Review 2 (`review-report-02.md`) was REQUEST_CHANGES on one MAJOR defect, D5.
The human authorized an exception to the one-round rule for D5 alone, and repair round 2
fixed it (`repair-02-rag-engineer.md`, `repair-02-test-engineer.md`).
Repair round 3 (`repair-03-rag-engineer.md`, `repair-03-test-engineer.md`) replaced the
clause rule with a sentence rule, and review 4 (`review-report-04.md`) found the defect
class still open.
The review verdict is REQUEST_CHANGES, and D5 is not claimed fixed: it is fixed for the
named forms only.
This page describes the state after repair round 3.
The Public API is in [modules/rag/MODULE.md](../modules/rag/MODULE.md).
No cloud, Bedrock or AWS call was made in this phase.

## Stages

| Stage | Component | Version |
|---|---|---|
| 1 | QueryPlannerAgent (deterministic, no model) | planner-v1 |
| 2 | ResearcherResolverAgent | resolver-v1 |
| 3 | CollaborationMetricsAgent | metrics-agent-v1 |
| 4 | PathFinderAgent | path-agent-v1 |
| 5 | CommunityAnalystAgent | community-agent-v1 |
| 6 | SemanticContextAgent | semantic-agent-v1 |
| 7 | EvidenceBuilderAgent | evidence-builder-v1 |
| 8 | AnswerSynthesisAgent | deterministic-synthesis-v1, llm-synthesis-v1 |
| 9 | ClaimVerificationAgent | claim-verifier-v1 |
| 10 | Audit | audit-record-v1 |

Other versions: plan-operations-v1, graph-runner-v1, template-graph-v1,
evidence-pipeline-v1, local-runtime-v1, synthesis-rules-v1, context-packer-v1.
Stage 6 reuses the Phase 08 versions semantic-context-v1, untrusted-text-v1 and
graph-value-policy-v1.
The entry point is `CollaborationWorkflow.run(queryId, AnalysisRequest)`.

## Prompt and schema versions

- `prompts/runtime/answer-synthesis-v1.md`, version answer-synthesis-v1, sha256
  a72a7c8896458c624a681d84f6631b6f437ef9a15f6d61ccaa4b0fc9cb1109e6.
- `prompts/schemas/answer-synthesis-v1.json`, version answer-synthesis-v1, sha256
  67ef3cbd4cd91af4f2f79f2ca37700633c7114ff7fd028fe2f16a4d2587e04f4.
- `prompts/schemas/query-plan-v1.json`, version query-plan-v1 (hash not recorded in the
  reports).
- The planner is deterministic, so no prompt exists for stage 1.
- The synthesis prompt has exactly the placeholders BOUNDARY, REQUEST, NOTES, EVIDENCE
  and FEEDBACK; the prompt hash goes into every audit record as `promptSha256`.
- The output schema requires every key and allows no other, so there is no reasoning
  field.
- The model is `scripted-fake-v1` locally; `models.llmEnabled` stays false.
  The Bedrock model ID and the per-query cost are undecided (human).

## Allowed plan operations (plan-operations-v1)

The resolver may always use neighbors-out-v1, node-v1 and direct-coauthors-v1.

| Operation | Graph templates | Vector document types |
|---|---|---|
| RESEARCHER_RANKING | node-v1 | researcher-profile |
| MAX_TEAM_SIZE | node-v1 | paper-abstract |
| DIRECT_COAUTHORS | direct-coauthors-v1, coauthor-pair-v1 | researcher-profile |
| SHORTEST_PATH | shortest-path-d1..d6, coauthor-pair-v1, node-v1 | collaboration-path |
| COMPONENT_LOOKUP | none | researcher-profile |
| COMMUNITY_COMPARISON | direct-coauthors-v1 | collaboration-community |
| BRIDGE_RANKING | direct-coauthors-v1 | collaboration-community, researcher-profile |
| TEMPORAL_COMPARISON | none | researcher-profile |
| TOPIC_FILTER | neighbors-in-v1, work-authors-v1, node-v1 | topic-institution-summary |
| INSTITUTION_FILTER | neighbors-in-v1 | topic-institution-summary |
| SEMANTIC_EXPLANATION | none | all five |

User text never becomes database code.
Only storage-built, allowlisted templates run, and only those the plan allows.
Per request: at most 250 queries, 100 rows per query, 5000 ms per query, 15000 ms in
total, and path depth up to 6.
Every template that reads COAUTHORED_WITH names exactly one configured window.

Planner behavior after repair round 1 (planner-v1, version string unchanged):

- A pair question accepts names in straight or curly double quotes.
  It also accepts "collaboration, joint publications or coauthored papers between X and
  Y".
- A direct-collaboration cue (collaborated, coauthored, joint publications or papers,
  direct coauthors or collaborators, "collaboration between") with two named researchers
  adds DIRECT_COAUTHORS to the plan, as a path cue already did for SHORTEST_PATH.
  "Coauthors of" and "collaborated with" still do not accept quoted names, so temporal
  questions do not gain DIRECT_COAUTHORS.
- A topic is extracted before "in <years>" (for example "in Quantum Error Correction in
  2015-2026").
  A phrase is not a topic when its last word is a graph term, when every word is one, or
  when it starts with a determiner (their, own, same, the, ...).
- Under `LocalRuntime`, the runtime context holds only the loaded windows, so the default
  window, the window choice, the graph runner's window allowlist and the temporal metrics
  see only loaded windows.
  A question with no years is planned on the loaded span and answered from the widest
  loaded window, with a window note.
  A request for an unloaded window maps to a loaded window, with a note.

Graph templates used: node-v1, direct-coauthors-v1, coauthor-pair-v1,
shortest-path-d1-v1 to d6-v1, neighbors-out-v1, neighbors-in-v1 and work-authors-v1.
TemplateGraph also answers researcher-works-v1 and neighbors-both-v1, which no stage
uses.

## Identity behavior (resolver-v1)

- Names match only by core's `Normalize.personName` key, then by name tokens in any
  order, never by vector similarity.
  An exact-name lookup that finds one node does not consult the token-order key (MINOR
  m1, open).
- No candidate gives NOT_FOUND.
- Two or more candidates always give AMBIGUOUS (up to 5 listed with the total).
- One candidate that er-v1 marked as an identity conflict gives AMBIGUOUS.
- Otherwise RESOLVED, keeping identity confidence and the unresolved-links flag.
- The caller disambiguates by passing a candidate ID in `constraints.researcherIds`.
- Each candidate becomes an IDENTITY_RESOLUTION evidence item with the ER policy and
  run ID.

## Evidence rules

- Three partitions: deterministic graph and metric evidence with PUBLICATION records,
  identity evidence, and descriptive semantic items.
  An item in the wrong partition fails the build.
- Metric values are the exact stored values; measures Spark left empty are listed in
  `missingFields`.
- A missing edge or path is `found=false` evidence with its depth and window.
- For a found pair edge, the PUBLICATION items of its `supportingWorkIds` are fetched
  before team and path works, within MaxPublications 12.
  The deterministic order is path evidence, pair edge, those publications, coauthor lists,
  then community views, so the bundle cap cuts the long lists first.
- The pair answer has a claim "Publications supporting the coauthor edge of A and B in
  window W: ..." citing the edge and those PUBLICATION items.
- The bridge claim says "touching N communities, its own included"; N is the stored
  `communitiesConnected`, with no N-1 computed.
- The bundle is capped at 120 graph, 25 identity and 10 semantic items and 300,000
  bytes.
- Semantic items are marked untrusted-data and never prove a number, window, hop,
  path, edge or ranking order.

## Verification rules (claim-verifier-v1)

- Only cited evidence that exists in the bundle supports a claim.
- Checks, in order: quoted titles, known researcher names, identifiers, path hops,
  windows, path statements, 19 kinds of keyed numbers, any other number, unknown
  capitalized multi-word names, and ranking order.
- Statuses: VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING.
- VERIFIED is kept.
  PARTIALLY_SUPPORTED is kept, each unsupported span replaced by `[unverified]` and the
  claim labeled.
  UNSUPPORTED and CONFLICTING are removed and kept in the audit's `removedClaims`.
- Number attribution (repair M5, then the clause rule of round 2, replaced in round 3 by
  a sentence rule): in the keyed-number and other-number checks, a per-researcher number
  is VERIFIED only when its sentence names exactly one researcher and has no pronoun.
  - A sentence ends at `.`, `!` or `?` only when the mark is unmasked and outside
    brackets, whitespace then a capital, digit, quote or bracket follows, and the
    preceding token is a lower-case word of 3 or more letters not on the abbreviation
    list, or a numeric token of 4 or more characters.
    A doubtful period only widens the sentence.
  - A researcher is counted as named only by an exact display name.
    A name shared by two IDs counts as ambiguous.
  - A pronoun (he, she, they, his, her, hers, their, theirs, its, him, them, a word with
    "-self") means no subject; "its own included" is exempt.
  - The fixed identity lead "'<query>' is ambiguous: candidate | resolved to | matched"
    is not counted as a mention when the query is an identity query in the bundle.
  - Any other number is weakened and labeled: the claim becomes PARTIALLY_SUPPORTED with
    `[unverified]` and a label, and a model summary like it is replaced.
    It is never CONFLICTING for per-researcher records.
    An unknown name ("John Smith has 41") is now UNSUPPORTED.
  - Pair values held by a cited edge, path or ranking can still be CONFLICTING.
  Per-researcher records (researcher-metrics METRIC_RECORD, community-membership
  COMMUNITY_RECORD and IDENTITY_RESOLUTION) support the number only when they are that
  researcher's.
  Edge, path, ranking, cross-community-link and publication items still support their
  values for anyone, and claims that name no researcher work as before.
  The summary check uses the same code (`ClaimVerifier.verifyText` over the bundle).
  Disclosed conservative weakening: correct prose that names two researchers in one
  sentence, such as "Alice, like Bob, has 41", is weakened and labeled, not VERIFIED.
  The two-subject form "A has 41 ... and B has 30 ..." is also weakened now.
  The round 2 statements that a clause splits at "and", "while" or ";" and that
  "A has 41 and B has 30" stays VERIFIED are superseded.
  The earlier statement that positional attribution "never verifies a false one"
  (`repair-01-rag-engineer.md`, assumption 1) is superseded: D5 refuted it.
- Communities: "touching, linking, connects or spans N communities" and "N communities,
  its own included" map to `communitiesConnected`.
  "N other communities" maps to `otherCommunityCount`, which no item holds, so such a
  claim is weakened to PARTIALLY_SUPPORTED with `[unverified]`.
- A summary with an unsupported or conflicting value is replaced by the deterministic
  summary.
- The answer status is UNSUPPORTED if no claim is kept, CONFLICTING if any drafted claim
  contradicted the evidence, VERIFIED if every drafted claim was verified, otherwise
  PARTIALLY_SUPPORTED.

## Abstention behavior

- The answer abstains when stages 1 to 7 abstain, when no claim survives
  verification, or when the synthesis output abstains.
- An ambiguous or unknown name abstains the whole answer; only the resolver's graph
  queries run, and no model is called.
- An ambiguous name keeps the candidate claims, verified against identity evidence.
- An answer that abstains for any reason (evidence stage, model abstention or no
  surviving claim) has no researcher rows.
  In an answer that does not abstain, a researcher row is kept only when a kept claim
  cites it, so every row has `claimIds`.
- A model that abstains while deterministic evidence exists is overridden by the
  deterministic answer.
- The planner refuses empty, oversized, control-character, unsupported and
  out-of-range questions with typed errors.

## Model path limits

- At most 3 attempts; a refused reply is retried with the validator's fixed reason,
  never the reply text.
- Output tokens summed over attempts stay within `operations.maxTokensPerQuery`;
  each call asks for at most 1500.
- Input is bounded by `retrieval.contextTokenBudget` (3 characters per token
  estimate).
  Up to about 36k input tokens per query (3 attempts) is possible, so the human decides
  the cost before Bedrock is enabled.
- No chain-of-thought is requested, stored or accepted.

## Audit (stage 10)

The bundle goes to storage's `EvidenceBundles` (conditional write).
The `AuditRecord` goes to the `AuditLog` port, conditional too.
The record holds the plan, resolutions, evidence IDs, run and metric versions,
prompt hash, model attempts, token counts when reported, graph calls (template IDs and
windows only), claim checks, removed claims, latency and the answer hash.
No prompt text, reply text or reasoning is stored.
Only `InMemoryAuditLog` exists; there is no DynamoDB audit repository.

## Benchmark results (benchmark-questions-v1)

Source: `docs/agent-runs/phase-09/output-benchmark.md` (last section, repair round 2
rerun, which repeats the round 1 scores) and `repair-02-test-engineer.md`.
33 questions, 3 in each of 11 categories; windows 2010-2014 and 2015-2026; graph run
`run-ccf619c509611f06`; ER run `run-cb5a0421c406737d`; deterministic synthesizer.
`questions-v1.json` sha256
`8584d275357c0b64445d60a7c7edcfb4e24a12d7b1b09d5b7407d25905b58e65`.

| Score | Before repair | After repair round 1 (unchanged in round 2) |
|---|---|---|
| Deterministic retrieval (D) | 28/33 (84.8%) | 33/33 (100.0%) |
| Answer (A) | 27/33 (81.8%) | 33/33 (100.0%) |
| Benchmark pass (D and A) | 27/33 (81.8%) | 33/33 (100.0%) |
| Planner from question text alone (P) | 25/33 (75.8%) | 33/33 (100.0%) |
| Semantic hit rate (S) | 6/33 (18.2%) | 6/33 (18.2%) |
| Semantic precision (S) | 7/80 (8.8%) | 7/80 (8.8%) |

Semantic precision counts relevant items of those returned.
Semantic relevance follows `semantic-relevance-rule-v1`, written by test-engineer.
Its labels are not human-reviewed; the human reviews them at the gate.
Semantic retrieval is weak because the local embedder is the token-hashing fake, and it
is unchanged by the repair (same retrieval, same vector subset).
Every category passes 3/3 on D, A and pass after the repair.

Test counts after repair round 2:

| Suite | Total | Passed | Failed |
|---|---|---|---|
| `sbt rag/test` | 133 | 133 | 0 |
| BenchmarkQuestionSetSuite | 40 | 40 | 0 |
| BenchmarkWorkflowSuite | 68 | 68 | 0 |
| WorkflowGateSuite | 29 | 29 | 0 |
| WorkflowRepairGateSuite (M5 and M6) | 6 | 6 | 0 |
| WorkflowRepairD5GateSuite (new, D5) | 2 | 2 | 0 |
| adversarialTests project | 213 | 213 | 0 |

The full `sbt check` at the default `SBT_OPTS` (`-Xmx1536m -Xss4m`) ran once in round 2
and exited 0 after 734 s, with 14 project test runs passed and the test marker touched.
The per-project totals were 120, 184 (1 ignored), 133, 213, 119, 45 and 98.
So `sbt check` is GREEN.
In round 1 the same check had exited 1 after 771 s on the one red D5 test.
`adversarialTests` is forked at `-Xmx2560m -Xss4m` (repair M7), so no `SBT_OPTS`
override is needed.
`LocalRuntime.load` took 209635 ms in the round 2 benchmark run, with 933 MB of 2560 MB
heap used afterwards.
The counts in this section are those of round 2; round 3 counts are below.

## Repair round 2 (D5, human-authorized exception)

- The human authorized an exception to the one-round rule for D5 alone (option (a) of
  `review-report-02.md`, recorded in `run-log.md`).
- D5 (owner rag-engineer) was fixed for the named forms by a clause rule, which round 3
  replaced (see below).
  `sbt rag/test` passed 133 of 133 (131 before); two unit tests were added, in
  VerificationSuite and WorkflowSuite, and no existing assertion changed.
- The test "M5: a number after two names is checked against the grammatical subject, not
  the nearest name" in `WorkflowRepairGateSuite` is now green, unedited.
  Before, "Hans Hagen, compared with Jan Mohring, has 5 distinct coauthors in window
  2010-2014." citing both metric records was VERIFIED, and 5 is Jan Mohring's value
  (question bq-v1-08).
- test-engineer added WorkflowRepairD5GateSuite (2 tests): the number before two names in
  both orders, "compared with", "like" and "(unlike)" forms, a publication-count form and
  a model summary in the number-first form.
  None is VERIFIED, and the control claim (one researcher's own value) is VERIFIED.
- The colon break and the conditional bare "and" break go beyond the review brief's list.
  They keep two kinds of correct claims VERIFIED: the deterministic candidate and
  identity claims, and "X has N and Y has M".
- Residuals named in round 2: windows are not attributed to a researcher, swapped values
  inside one cited ranking are accepted, and unkeyed ranking numbers still support any
  named researcher (this one is R4-3 below).
  The round 2 residuals "A: unlike B, 30" and "a pronoun subject is weakened, not
  resolved" are replaced by round 3 and review 4 below.
- Not run by the owner of the fix: the adversarial suites (test-engineer ran them).
- The pair in WorkflowRepairD5GateSuite is chosen at run time and not printed, and the
  publication-count claim runs only when the two counts differ; whether it ran is not
  known.
- Review 3 (`review-report-03.md`) was REQUEST_CHANGES: R3-1 (seven phrasings where a
  false number was VERIFIED), R3-2, R3-3 and R3-4 (this documentation).

## Repair round 3 and review 4

- The human authorized a second exception.
  rag-engineer replaced the clause rule with the sentence rule above
  (`repair-03-rag-engineer.md`); `sbt rag/test` passed 135 of 135 (133 before).
  Five existing assertions were weakened on purpose, as the rule requires.
- test-engineer added WorkflowRepairR3GateSuite (6 tests, public API only).
  It passed 6 of 6; the other gate suites stayed green (D5 2, RepairGate 6, WorkflowGate
  29, BenchmarkWorkflow 68, QuestionSet 40).
  One full `sbt check` exited 0 in 789 s, 14 project test runs passed; rag 135 and
  adversarialTests 219 (`repair-03-test-engineer.md`).
- Review 4 (`review-report-04.md`) confirmed R3-1 to R3-3 fixed as written and found no
  regression, and the verdict is REQUEST_CHANGES: the class "a false per-researcher
  number is VERIFIED" is STILL OPEN.
  The rule counts only researchers recognised by exact display name.
  Three MAJOR findings, all rag-engineer, all on the model path only:
  - R4-1: a variant or partial name is not a mention, so "Unlike Bob Brandt, Arden has 30
    ..." and a lower-case "alice arden" are VERIFIED with another researcher's value.
  - R4-2: a noun phrase for a researcher is not treated as a pronoun: "the leader",
    "this researcher", "the former", "Alice Arden's closest coauthor", and an unlisted
    abbreviation such as "dir." that splits a sentence.
  - R4-3: a cited ranking item backs any researcher's number ("Alice Arden is ranked
    with 30" is VERIFIED when Alice's value is 41).
- The model path is off by default (`LLM_ENABLED` defaults to false), and the
  deterministic claims and summary all stayed VERIFIED.
  The human carries R4-1 to R4-3 forward, and they must be closed before Bedrock or the
  LLM is enabled.
- `claim-verifier-v1` was not bumped although behavior changed; that is the human's call.
- Review 4 did not review Plan, Evidence beyond ranking values, Agents, Synthesis, Llm
  or TemplateGraph, and did not probe the 25k run, homonym bundles or windows.

Fixed in repair round 1 (all four red-test defects and the reviewer's M1 to M7):
F1 and M1 (pair questions), F3 and M2 (topic before "in <years>"), F2 and M3
(communities wording), F4 and M4 (researcher rows), M5 (number attribution, partial until
D5 was fixed in round 2), M6 (unloaded windows) and M7 (heap).

## Known failure modes

- Community size is missing evidence: no store the runtime reads holds it.
  Every community answer says "community size of <id> is not available", and a claim of
  "N members" is never VERIFIED.
- The verifier is pattern-based.
  An unsupported fact in words it does not parse, such as "forty-one" or an unquoted
  title, ends PARTIALLY_SUPPORTED, not caught as wrong.
- Single capitalized unknown names are not flagged, and the capitalized-name check can
  weaken legitimate prose.
- A name shared by two IDs passes the name check if either is cited.
- The planner finds names only in explicit forms (quoted, "between X and Y", "path from
  X to Y", "coauthors of X"), topics only as "in <phrase>" and institutions only as
  "at <... University/Institute/...>".
  Lowercase "wei wang" is not recognized as a name.
- Venue is recorded in the plan but not applied (no venue operation in core).
- Ties between shortest paths are broken deterministically in TemplateGraph; live
  Neptune may pick another of the same length.
- A requested window that was not precomputed is answered from the smallest configured
  window that contains it, with a note; under `LocalRuntime` only loaded windows count
  (repair M6).
- The same query ID twice is refused at the audit write, after the bundle was stored.
- A stage 1 to 7 failure returns a WorkflowError and writes no audit record.
- Semantic retrieval has no runner deadline.
- `LocalRuntime.load` took 158135 ms and about 1034 MB of a 2560 MB heap in the
  benchmark before the repair, and 244070 ms and 934 MB in the rerun; it runs out of
  memory at -Xmx1536m.
  `adversarialTests` is forked at `-Xmx2560m`, so the default `SBT_OPTS` works.
- Number attribution is sentence-scoped (round 3), and the class is still open:
  R4-1 to R4-3 above, model path only.
  A window is not attributed to a researcher: a claim that pairs one researcher's value
  with the window of another cited record still passes the window check.
- Swapped values inside one cited ranking ("Carol (41), Alice (52)") are accepted;
  only the ranking order is checked.
- A title containing a double quote breaks title checking in the pair-publication and
  hop claims.
- Two-name questions with a direct-collaboration cue also fetch both researchers'
  coauthor lists (up to 50 edges each).
  This fits the 120-item bundle cap and the 250-query budget.
- Component version strings (planner-v1, claim-verifier-v1, deterministic-synthesis-v1,
  evidence-pipeline-v1, local-runtime-v1) were not bumped although behavior changed.
- The name check accepts a name as a substring of a cited value (MINOR m2, open).
- MINOR m1 (an exact-name hit never consults the token-order key) and m4 (a topic phrase
  such as "collaboration graphs" gives a filter that matches no Topic node, so the answer
  abstains) are open (`review-report-02.md`).
- Not tested: planting injected text into the vector store (no documented API).
