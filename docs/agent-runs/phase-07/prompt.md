```text
Use the storage-engineer agent to implement ports, adapters, loaders, and contract tests for the ScholarChain storage layer.

Required adapters:

1. Ordinary S3
- Raw, curated, graph, semantic-context, reports, and logs prefixes.
- Versioned object keys and run manifests.
- Content hashes and source references.

2. Neptune
- Bulk loader manifests for Researcher, Work, Venue, Institution, Topic, and selected context nodes.
- Load AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, and SUPPORTED_BY relationships as required by the implemented graph model.
- Store coauthor-edge properties including jointPublicationCount, firstCollaborationYear, lastCollaborationYear, selected time window, and evidence pointer.
- Store researcher collaboration metrics needed for bounded runtime lookup when appropriate.
- Use idempotent identifiers and parameterized openCypher queries.
- Give runtime services read-only graph credentials.

3. DynamoDB
- RunLedger table.
- EntityLinkCache table.
- QuerySession table.
- EvidenceBundle table.
- ArtifactRegistry table.
- Optional ResearcherMetricCache table when justified by access patterns.
- Explicit partition and sort keys.
- Conditional writes for idempotency.
- TTL only where data is genuinely disposable.

4. S3 Vectors
- Create and query vector-bucket and index abstractions.
- Store embeddings with stable vector keys and compact filterable metadata.
- Store long text or generated profiles in ordinary S3 and keep only references in vector metadata.
- Support metadata filters for researcher, year window, source, topic, institution, community, and document type when permitted by service limits.
- Batch writes, retry transient failures, and record rejected vectors.

5. Optional OpenSearch
- Only when the team implements hybrid lexical and vector retrieval or a measured hot tier.
- Define synchronization from durable S3 and S3 Vectors data.
- Do not make OpenSearch an undocumented second source of truth.

Required vector indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Required tests:
- Serialization and deserialization.
- Idempotent reload.
- Missing and malformed metadata.
- Duplicate vector keys.
- Neptune coauthor-edge endpoint and evidence validation.
- DynamoDB conditional-write behavior.
- Local fakes or test containers where possible.
- Bounded AWS integration tests behind an explicit profile.

Run sbt check and the bounded storage integration suite.
Report schemas, keys, indexes, metadata fields, batch sizes, retry policy, idempotency strategy, service limits captured from current documentation, and cleanup procedures.
```

Addendum from the human architect for this repository:

- Scope: the deadline is 2026-11-25. Build exactly what the work order and the Phase 07
  gate require, and nothing optional. No OpenSearch. No extra tables, indexes or tests
  beyond what the gate names. Carried-forward items from earlier phases stay where they
  were assigned unless this addendum names them.
- Reference material: ~/ref-phase-07/ holds a reference implementation of this phase
  from the collaborative ScholarChain repository. Read ~/ref-phase-07/REFERENCE.md
  first. Each owner adapts the files in its own territory, reviews every line and owns
  the result. Nothing is copied unread, and no owner writes outside its territory.
  Correction to REFERENCE.md difference 1: this repository has NO `scholarchain.core.aws`
  package. Phase 03 created only Hashing, Config, Model, Normalize, JsonLines, Ids and
  Validate. Step 0 below creates it.
- No cloud in this phase: no AWS call is made, no real credentials are read, no resource
  is created. Adapters are proven against in-memory fakes and a local HTTP stub
  (`com.sun.net.httpserver`); no Docker. The bounded AWS integration suite is written
  now, runs only behind an explicit opt-in that sbt check never sets, and is reported as
  NOT RUN until the cloud phases.
- Inputs: loaders read this repository's Phase 06 Spark exports through the Spark run
  manifest (local run `run-769e0d672ecd3c19` under `target/spark-local-run`). Neptune
  identifiers must equal the Spark output identifiers. Coauthor edges are stored once
  per time window (Phase 06 carried-forward item), so every graph template filters on
  a single window. If a needed field is not exported, report BLOCKED-ON spark-engineer.
  Table, bucket, index names and endpoints come from configuration; if a config field
  is missing from core, report BLOCKED-ON domain-engineer.
- Order, one owner at a time:
  0. domain-engineer: create `scholarchain.core.aws` in modules/core: an HTTP client on
     the JDK `java.net.http.HttpClient`, AWS Signature Version 4 request signing, and a
     credentials port with an environment/container provider plus a static provider for
     tests. uPickle only; no AWS SDK, no Jackson, no new dependency. Test the signer
     against AWS's published SigV4 test vectors, with the source URL in the test, and
     test that no secret appears in toString or logs. Run `sbt core/test`. Report as
     step-00-domain-engineer-report.md.
  1. storage-engineer, task T1: ports, in-memory fakes, the S3 object layout (prefixes,
     versioned keys, run manifests, content hashes, source references) and the
     DynamoDB tables, each with written access patterns, explicit keys, conditional
     writes and a justified TTL decision. Report as step-01-storage-engineer-report.md.
  2. storage-engineer, task T2: the Neptune bulk-load manifests and openCypher CSV from
     this repository's Phase 06 export, allowlisted parameterized query templates, and
     the coauthor-edge endpoint and evidence validation. Show on a small fixture that
     every Neptune identifier equals a Spark output identifier and that each
     COAUTHORED_WITH edge resolves to its supporting works. Report as
     step-02-storage-engineer-report.md.
  3. storage-engineer, task T3: the S3 Vectors adapter and loader for the five required
     indexes (long text in ordinary S3, only references and compact filterable metadata
     in the vector), batching, bounded retry of transient failures, rejected-vector
     records, the Bedrock embedder adapter, the idempotent reload of every loader, and
     the gated AWS integration suite. Cover each required test in the work order:
     serialization round trips, idempotent reload, missing and malformed metadata,
     duplicate vector keys, edge endpoint and evidence validation, conditional writes,
     no long text in vector metadata, the integration suite off by default. Report as
     agent-report.md.
  4. entity-resolution-engineer: add the Bedrock adjudicator adapter behind the
     Phase 05 port, using core's new signing client, with the strict JSON schema, the
     no-new-identifier rule and the cache key unchanged, and with er-v1 unchanged. Test
     it against the local HTTP stub only. Then run the full sbt check once. Report as
     step-03-entity-resolution-engineer-report.md.
  5. documentation-engineer: docs/service-limits.md (each limit with its documentation
     URL and the date checked, or marked unverified), a storage section with schemas,
     keys, indexes, metadata fields, batch sizes, retry policy, idempotency strategy and
     cleanup procedures, and modules/storage/MODULE.md, with only facts observed here.
     Report as step-04-documentation-engineer-report.md.
  Update STATUS.md through documentation-engineer after each owner.
- Then one fresh code-reviewer review (review-report.md) and one repair round
  (repair-01*.md). Run a fresh re-check (review-report-02.md) only if the review had a
  CRITICAL or MAJOR finding. Findings still open after that are carried forward to the
  human; there is no second repair round.
- Machine limits (8 GB laptop, 5 GB for WSL): never more than one sbt at once, no
  background shells, no polling or wait loops, no mutation testing; every command runs
  in the foreground with a timeout. While working, run only the tests of the module
  being changed (`sbt core/test`, `sbt storage/test` or `sbt entityResolution/test`);
  the full sbt check runs once, at the end of step 4, and again only if a repair
  changes source. If the harness moves sbt to the background, do not start anything
  else and do not poll; wait for it.
- No agent commits, runs cleanup.sh or touches cloud resources in this phase.
