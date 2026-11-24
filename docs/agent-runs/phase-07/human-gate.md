# Phase 07 human gate

## How Phase 07 was produced

The owning agents did the work through the orchestrator, one owner at a time:
domain-engineer (step 00: the `scholarchain.core.aws` package, an HTTP client on the JDK,
AWS SigV4 signing checked against AWS's published test vectors, and credentials
providers), storage-engineer (T1: ports, in-memory fakes, the S3 layout and the
DynamoDB tables; T2: Neptune bulk-load CSV and manifests, allowlisted read templates and
coauthor-edge validation; T3: the S3 Vectors adapter and loader, the Bedrock embedder,
idempotent reloads and the gated AWS integration suite), entity-resolution-engineer (the
Bedrock adjudicator behind the Phase 05 port, plus the full sbt check),
documentation-engineer (`docs/service-limits.md`, `docs/storage.md`, the module notes and
STATUS), and two fresh code-reviewers. The work order and my addendum are in `prompt.md`.
Every step is in `run-log.md`, and every agent report is saved word for word in this
folder (also gathered in `phase-report-agent-reports.md`).

As in Phases 03 to 06, I gave the agents reference material from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-07/`. Its
`REFERENCE.md` said this repository already had a `scholarchain.core.aws` package. It
did not, so I added step 00 for domain-engineer before any storage work started.

## What was checked

- No AWS call was made, no real credentials were read and no cloud resource exists.
  Every adapter is tested against in-memory fakes or a local HTTP stub. The AWS
  integration suite is written and off by default: NOT RUN until the cloud phases.
- S3: zones, run-versioned keys, run manifests and content hashes. DynamoDB: 6 tables
  with explicit keys tied to written access patterns and conditional writes; TTL only on
  QuerySession.
- Neptune: bulk-load CSV and manifests whose identifiers equal the Spark output
  identifiers, shown on a fixture shaped like the Phase 06 export; 14 allowlisted,
  parameterized, read-only openCypher templates, each coauthor template filtered to one
  window; every COAUTHORED_WITH edge validated against its endpoints and supporting works.
- S3 Vectors: the 5 required indexes, long text in ordinary S3 and only references and
  compact metadata in the vector, batches of 100, bounded retry, rejected-vector
  records, duplicate-key handling. Every loader replays without change.
- Bedrock adjudicator: tested against the local stub only, with the strict JSON schema,
  the no-new-identifier rule, the cache key and er-v1 unchanged. It is not yet wired
  into the match job.
- `docs/service-limits.md` gives each limit with its documentation URL and the date
  checked, or marks it unverified.
- Full `sbt check` exited 0 twice: after step 04 (312 s) and after the repair round
  (305 s), with storage 158 (1 ignored, the gated AWS suite), core 119, entity
  resolution 119, hadoop 98, adversarial 68 and Spark 33 tests.
- Review 1 (`review-report.md`) returned REQUEST_CHANGES: 1 MAJOR, 7 MINOR, 6 NIT. After
  one repair round (`repair-01*.md`, routing in `repair-01.md`), Review 2
  (`review-report-02.md`) confirmed every Review 1 finding closed or carried forward as
  routed. It returned REQUEST_CHANGES on one new item (R2-MAJOR-1, below) and 4 NITs.
  There is no second repair round.
- No test-engineer step was part of this work order, so there is no `test-report.md`.

## Decisions

- R2-MAJOR-1: during repair R1-2, domain-engineer's `sbt scalafmtAll` reformatted two
  storage-engineer files, `NeptuneAdapters.scala` and `Tables.scala`, and its report
  said it touched nothing outside its own files. I checked that the change is
  formatting only, and sbt check passes with it. I accept the two files as formatted,
  and I disclose the cross-owner write here. From Phase 08 on, agents format only their
  own files (`scalafmtOnly` or the module's own `scalafmt`), not `scalafmtAll`.
- MAJOR-1, run scoping: I accept the reviewer's minimum repair. Each Neptune cluster and
  each vector index serves one run; before a different run is loaded, I clean or rebuild
  them. This matches the plan, which has one bounded cloud run. Filtering every graph
  query and vector read by run is carried forward.
- Parquet: the Phase 06 export is Parquet only, and the storage module cannot read it.
  The loaders are proven on data shaped like the export, but the real run
  `run-769e0d672ecd3c19` has not been loaded. I choose a JSON-lines export alongside
  the Parquet tables, by spark-engineer, as the first step of Phase 08, with no new
  dependency in storage. It must be in place before the first cloud load.
- Spark export gaps, also routed to spark-engineer in Phase 08 step 0: the semantic
  summaries carry no `institutionId`, there are no paper-abstract rows for
  `paper-abstract-v1`, and `neptune.json` lists only the 1950-2026 coauthor window
  while the export holds two windows.
- Bedrock model ID: the adjudicator keeps reusing `models.synthesisModel` for now. A
  separate adjudication model field is carried forward to domain-engineer.

## Accepted, with known gaps

The Phase 07 storage layer is accepted as committed. Carried forward (full list in
STATUS.md under "Carried forward from Phase 07"):
- Run-scoped filtering of graph and vector reads (storage-engineer, after the
  deadline unless a second run is loaded).
- Parquet reading and the Spark export gaps: Phase 08 step 0, spark-engineer.
- A separate adjudication model field in config (domain-engineer).
- A repairing replay leaves the ledger pointing at the first load report, and a missing
  input folder is recorded as a rejected row while the stage still records Succeeded
  (storage-engineer, Phase 11).
- Review 2 NITs: a stale variable name in the integration suite, an expected count of 1
  that a missing folder satisfies, a stale correction in `docs/service-limits.md`, and
  STATUS wording (storage-engineer and documentation-engineer, Phase 11 and 17).
- Optional extra SigV4 test vectors (domain-engineer, Phase 11).
- The aws-api MCP server is reaching end of development and recommends the AWS MCP
  Server, and the github MCP server did not connect ("Authorization header is badly
  formatted"). Nothing in this phase needed either (factory-engineer, Phase 12).
- The guard false positive on the test-marker name and the stop hook's repeated
  requests for sbt check while agents run (factory-engineer, Phase 11, already tracked).

## Manual changes

- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 7 is complete. I approve starting Phase 8.
