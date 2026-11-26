# Phase 11 carried-forward items

Sources: `docs/agent-runs/phase-{01..10,08b}/human-gate.md` (all 11 read), with `STATUS.md`
for item detail. Written by the orchestrator before any other step.
MUST = critical or major, breaks a gate item, needed before the cloud phases, or named
MUST by the addendum. CARRY = stays open for Phase 17's limitations section, unless an
owner is already repairing the same file and the fix is a few lines.

## MUST

| ID | From | Item | Owner | Why MUST |
|---|---|---|---|---|
| C01 | 01 | R3-CRITICAL-1: no raw-command fallback scan when the guard cannot parse | factory-engineer | critical; addendum |
| C02 | 01 | R3-MAJOR-1: glob check skipped inside `bash -c`, `sh -c`, `eval`, `find -exec` | factory-engineer | major |
| C03 | 01 | MAJOR-4 (no phase assigned): disguised terraform apply and force-push forms | factory-engineer | major, open, before cloud; deny rules are only a backstop |
| C04 | 01, 05 | MINOR-C (with Phase 01 MINOR-1): shell and interpreter writes to and deletions of `datasets/gold/` | factory-engineer | addendum |
| C05 | 03, 05, 06, 07, 08b, 09 | Guard blocks harmless text naming the test-marker task or path | factory-engineer | addendum |
| C06 | 08 | Self-matching `while pgrep ...; do sleep` loops are not blocked | factory-engineer | addendum |
| C07 | 08b | Long pipeline and sbt commands in work orders must run under `timeout` | factory-engineer (rule) | addendum |
| C08 | 06, 07, 08 | Stop hook asks for sbt check while a subagent's sbt runs; the stale-marker refusal must not weaken | factory-engineer | addendum |
| C09 | 08b | R6-1: filtered rank figure VERIFIED without its filter; gate test with filtered rank 1, global rank not 1 | rag-engineer, test-engineer | major; addendum |
| C10 | 08b | R6-2: WorkflowGate no longer requires `claim-2` absent; restore or justify in writing | test-engineer (with rag-engineer) | addendum |
| C11 | 08b | R6-3: stale `claim-verifier-v2` scaladoc and `LlmSynthesizer.Version` = `llm-synthesis-v1` | rag-engineer, test-engineer | addendum |
| C12 | 06 | No config upper bound on the contradicted identity-group size | domain-engineer, then spark-engineer | before the 100,000-work EMR run |
| C13 | 08 | No contract test running the Neptune and DynamoDB loaders on the JSON-lines export | storage-engineer | before the cloud load |
| C14 | 09 | Community size reaches no store the runtime reads | spark-engineer, storage-engineer, rag-engineer | addendum; no new local Spark run |
| C15 | 10 | MINOR-1: JWT `token_use` not checked | service-engineer | security; addendum |
| C16 | 10 | MINOR-2: downstream error text can appear in explain | service-engineer | security; addendum |
| C17 | 07 | A repairing replay keeps the first load report; a missing input folder is a rejected row while the stage records Succeeded | storage-engineer | data loss and provenance before the cloud load |
| C18 | 04 | N7: no job-level test of the new reducer wiring; black-box ISO-8859-1 case not written | hadoop-engineer, test-engineer | gate: every production behavior has a relevant test |

## CARRY

| ID | From | Item | Owner |
|---|---|---|---|
| K01 | 01 | R3-MINOR-1: quote splitting and unlisted writers pass the `human-gate.md` check; docs overclaim | factory-engineer |
| K02 | 01 | R3-MINOR-2: `/usr/bin/env`, `busybox env`, `printenv DB_PASSWORD` pass the env guard | factory-engineer |
| K03 | 01, 02 | markTestRun quote splitting (rated MINOR-4 at the Phase 02 gate) | factory-engineer |
| K04 | 03 | Guard false positives: `python3 -c` naming a settings file, `sed` without `-i` naming the gold path | factory-engineer |
| K05 | 02 | MINOR-A: two SLF4J providers in black-box test projects; MINOR-D: no test of Spark jar exclusion or black-box classpath | build-engineer, test-engineer |
| K06 | 03 | Dataset Python tests are not part of `sbt check` | build-engineer |
| K07 | 04 | N1: J01 reject reasons print Scala `Option` text | hadoop-engineer |
| K08 | 04 | N2: a decode-only charset fails J01 instead of a counted refusal | hadoop-engineer |
| K09 | 04 | N3: fallback-block work bounded only by the hard pair cap | hadoop-engineer |
| K10 | 05 | Review 2 NIT-B: two refusal checks in one test are not isolated | entity-resolution-engineer |
| K11 | 07 | R2-NIT-1, R2-NIT-2 (stale variable name; `expectedRows = 1` met by a missing folder; NIT-2 is likely closed with C17) | storage-engineer |
| K12 | 07 | Optional extra SigV4 vectors | domain-engineer |
| K13 | 09 | m1 swapped name order, m2 substring name check, m4 topic phrase filter | rag-engineer |
| K14 | 10 | MINOR-3 (timeout relies on an interruptible port), MINOR-4 (cancelled request keeps its Idempotency-Key), MINOR-5 (worker loop ends silently) | service-engineer |
| K15 | 10 | Review 2 MINOR-A to E (eviction race, untrimmed evidence index, cancelled requests refill the store, unbounded method dimension, test gaps) | service-engineer |

## Checked and not carried into this phase

- Phase 09 R4-1 to R4-3: replaced by the Phase 08b construction rule; only R6-1 stayed
  open. Review B rechecks this.
- Phase 04 N5 (Phase 15); Phase 01 MAJOR-3 (Phase 12) and MAJOR-5 (Phase 13); Phase 05
  MINOR-1 and the greedy cut (er-v2); Phase 17 documentation nits; the cloud-phase items
  (AWS integration suite, bounded AWS ingestion, Bedrock model ID, adapters and images,
  MCP server migration, run-scoped filtering).
