Work order 1 (testing), work order 2 (review) and the repair loop, as given in the
assignment, separated by ----:

Use the test-engineer agent on the complete ScholarChain repository produced through Phase 10.

Add adversarial tests without modifying production code.

Required categories:
- Malformed and truncated DBLP XML.
- Unicode normalization and confusable characters.
- Duplicate and conflicting DOI records.
- Common author names and reordered author lists.
- Very large blocking groups.
- Coauthor edges with unresolved or conflicting researcher endpoints.
- Empty and disconnected graphs.
- Distinct-degree, component, shortest-path, bridge, and k-core known-graph cases.
- Large-team pair expansion and common-name researcher-resolution cases.
- Missing topic, institution, abstract, or researcher-profile metadata.
- Duplicate vector keys and stale model versions.
- Prompt injection in title, abstract, and full-text chunks.
- LLM malformed JSON and fabricated identifiers.
- Unsupported answer claims.
- Neptune timeout.
- DynamoDB conditional-write conflict.
- S3 Vectors throttling or transient failure.
- Replayed workflow and idempotency.
- API authentication, authorization, malformed bodies, and timeouts.
- Logging redaction.
- Configuration omissions.

Run all tests that do not require live AWS. Run bounded AWS integration tests only under the approved profile.
Leave any production defect exposed by a failing test. Report it to the owning agent instead of changing production code.
Report exact test counts, failures, coverage by requirement, and residual risks.
----
Use the code-reviewer agent to perform an adversarial read-only review of the complete repository and the full current diff.

Review in this order:
1. Correctness and data loss.
2. Contract and schema drift.
3. Artifact ownership violations.
4. Security, secrets, IAM assumptions, and injection risks.
5. Hadoop and Spark distribution mistakes.
6. Entity-resolution over-merging and unreported uncertainty.
7. Storage idempotency and provenance.
8. RAG grounding and unsupported claims.
9. API behavior and failure handling.
10. Tests that would pass even if the behavior were broken.
11. Documentation claims not supported by implementation.
12. Cost and cleanup risks.

Verify every candidate finding against the actual files before reporting it.
For each finding provide severity, file and line, concrete failure input, expected impact, and owning agent.
Return APPROVE or REQUEST_CHANGES. Do not edit any file.
----
1. Orchestrator groups findings by owner.
2. Each owning agent receives only its findings plus necessary context.
3. Agent fixes its owned artifacts and runs its gate.
4. Test engineer reruns relevant adversarial tests.
5. Code reviewer rereads the complete diff.
6. Repeat until APPROVE or human escalation.
----

Addendum from the human architect for this repository:

- Scope: the Phase 11 gate. No critical or major verified finding remains, every
  production behavior has a relevant test, all tests pass under their declared profiles,
  no reviewer writes production code, and the repair records name owners and rerun
  evidence. Nothing beyond that.
- Work without stopping to ask: take the most reasonable reading, record each choice and
  why in the step report, and carry on. Stop only for BLOCKED-ON, and route it as the
  rules say. The human is likely asleep during this phase.
- Keep usage low: read only the files your step needs, and keep each report under 80
  lines with the required fields (review reports may run to 120). The orchestrator
  answers stop-hook messages in one line and does not restate earlier reports.
- Reference material: ~/ref-phase-11/ holds the collaborative repository's adversarial
  suite, for test ideas only. Read ~/ref-phase-11/REFERENCE.md first.
- Carried-forward items: before anything else, the orchestrator reads every
  docs/agent-runs/phase-*/human-gate.md (phase-01 to phase-10, and phase-08b) and lists
  each item marked for Phase 11, with its owner, in
  docs/agent-runs/phase-11/carried-items.md. Each item is classed MUST or CARRY.
  MUST items are repaired in this phase: anything critical or major, anything that breaks
  a gate item, and anything needed before the cloud phases. CARRY items (minor findings
  and nits) stay open and are listed for Phase 17's limitations section, unless an owner
  is already repairing the same file and the fix is a few lines. These are MUST at least:
  - factory-engineer: the guard's missing scan of raw commands as a fallback
    (R3-CRITICAL-1); shell and interpreter writes to and deletions of datasets/gold/ not
    blocked (MINOR-C); the guard blocking harmless text that names the test-marker sbt
    task or path; blocking self-matching polling loops (`while pgrep ...; do sleep`);
    requiring long pipeline and sbt commands in agent work orders to run under `timeout`;
    and stopping the stop hook from asking the orchestrator for sbt check while a
    subagent's sbt run is in progress, without weakening the hook's refusal of a stale
    marker.
  - rag-engineer with test-engineer: R6-1 (a filtered rank figure VERIFIED without its
    filter) with a gate test where the filtered rank is 1 and the global rank is not;
    R6-2 (restore the stricter WorkflowGate assertion, or justify it in writing); R6-3
    (stale version strings).
  - domain-engineer: a config upper bound on the contradicted identity-group size (Phase
    06 gate), needed before the 100,000-work EMR run.
  - storage-engineer: a contract test running the Neptune and DynamoDB loaders on the
    JSON-lines export (Phase 08 gate), needed before the cloud load.
  - Community size reaching the runtime: spark-engineer adds it to the JSON-lines export
    with a test (no new local Spark run; the cloud run produces it), storage-engineer
    loads it, and rag-engineer reads it, each tested on fixtures.
  - service-engineer: the JWT `token_use` check (Phase 10 MINOR-1) and downstream error
    text in explain (MINOR-2), because both are security items.
  Items the gate notes assign to Phase 17, er-v2 or the cloud phases stay there.
- Constitutional changes: factory-engineer's changes to the guard, hooks, settings and
  agent files are constitutional. factory-engineer prepares them with the full diff,
  re-runs `.agents/render.sh --check`, `validate-agent-files.sh` and the routing suite,
  and runs at least three safe guard probes (a recursive S3 delete, a polling loop, a
  harmless report naming the test marker) and records the results. They take effect only
  after the human ratifies them at the gate and the next session starts. Until then, every
  agent writes around the old guard. Schedule factory-engineer last in the repair round,
  so the rest of the phase runs under the guard that was in force when it began.
- Formatting: every agent formats only the files it owns, never `scalafmtAll`.
- No cloud: no AWS call, credentials or resources. No AWS profile is approved, so the AWS
  integration suites are reported as NOT RUN. Tests use fakes and small bounded fixtures,
  never the 25,000-work export.
- Order, one owner at a time:
  1. test-engineer: map the existing tests to the work order's categories and save the
     map as docs/agent-runs/phase-11/coverage-before.md. Then add adversarial tests only
     where a category or a production behavior has none, in tests/adversarial and its own
     territory, without changing production code. A test that exposes a production defect
     stays failing and is reported with the owner. Run sbt check once at the end. Report
     as test-report.md.
  2. code-reviewer: two fresh reviewers, one after the other, each read-only, each over
     half of the repository and the full diff of that half, in the work order's review
     order. Review A covers core, hadoop, entity-resolution, spark and datasets
     (review-report-a.md). Review B covers storage, rag, services, tests, the agent
     control plane (.agents, .claude, .codex, hooks) and docs (review-report-b.md). Every
     finding is verified against the files, with severity, file and line, failing input,
     impact and owner. Reviewers report every finding but mark each one MUST or CARRY by
     the rule above.
  3. Repair: the orchestrator groups the MUST findings and MUST carried items by owner
     (repair-plan.md); each owner gets only its own items, fixes its own files and runs
     its own project tests (repair-01-<owner>.md); factory-engineer goes last.
     test-engineer then reruns the relevant adversarial tests and the full sbt check once
     (test-report-02.md), and one fresh code-reviewer rereads the complete repair diff
     (review-report-02.md).
  4. If a critical or major finding is still open after that, one more repair round for
     those findings only (repair-02-<owner>.md, review-report-03.md). Anything still
     critical or major after round 2 goes to the human with the owner and the reason.
  5. documentation-engineer: STATUS.md once, and the module notes only where behavior
     changed, with only facts observed here. Report as
     step-docs-documentation-engineer-report.md.
  The orchestrator records every step in run-log.md.
- The reviewers never edit a file, and no owner writes outside its territory.
- Machine limits (8 GB laptop, 5 GB for WSL): never more than one sbt at once, no
  background shells, no polling or wait loops of any kind, no mutation testing, and no
  two agents that run sbt at the same time. Every long command runs in the foreground
  under `timeout` (project tests `timeout 30m`, the full sbt check `timeout 45m`). While
  working, run only the tests of the project being changed; the full sbt check runs only
  where stated. If the harness moves sbt to the background, do not start anything else
  and do not poll; wait for it.
- No agent commits, runs cleanup.sh or touches cloud resources in this phase.
