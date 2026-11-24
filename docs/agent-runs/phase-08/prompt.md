```text
Use the storage-engineer and rag-engineer in separate delegated work orders to build the embedding and S3 Vectors ingestion path.

Storage-engineer work:
- Implement a bounded embedding work queue using SQS or a manifest-driven batch design.
- Implement vector write batches through the S3 Vectors API.
- Store authoritative source text and generated profile documents in ordinary S3.
- Store vector key, researcher or community ID, relevant time window, topic or institution IDs, document type, source dataset, model version, text hash, and run ID as compact metadata.
- Detect and skip unchanged text by content hash and embedding model version.
- Record failures and retry counts in RunLedger.

Rag-engineer work:
- Define deterministic rendering templates for researcher profiles, community profiles, collaboration paths, and representative-paper summaries.
- Define chunking policies for permitted abstracts or longer text when used.
- Define embedding model versioning.
- Create retrieval smoke tests with known expected researcher, topic, or community neighbors.
- Create a compact semantic-context format for the synthesis agent.

Required indexes:
- researcher-profile-v1
- collaboration-community-v1
- paper-abstract-v1
- collaboration-path-v1
- topic-institution-summary-v1

Run the local contract tests and a bounded AWS ingestion of at least 10,000 vectors or an instructor-approved smaller corpus when the selected dataset subset does not produce 10,000 meaningful units.
Report throughput, rejected records, duplicate handling, query latency samples, index counts, metadata schema, and embedding model version.
```

Addendum from the human architect for this repository:

- Scope: the deadline is 2026-11-25. Build exactly what the work order and the Phase 08
  gate require, and nothing optional. No extra indexes, templates or tests beyond what
  the gate names. Carried-forward items from earlier phases stay where they were
  assigned unless this addendum names them.
- Reference material: ~/ref-phase-08/ holds reference code from the collaborative
  ScholarChain repository. Read ~/ref-phase-08/REFERENCE.md first. Each owner adapts
  only files in its own territory, reviews every line and owns the result. Nothing is
  copied unread, and no owner writes outside its territory. Exception to REFERENCE.md:
  in step 0 below, spark-engineer changes its own spark module, as my Phase 07 gate
  decided.
- No cloud in this phase: no AWS call is made, no credentials are read, no resource is
  created. Embeddings come from the deterministic local fake, vectors go to the
  in-memory vector store, and queues are in-memory. The bounded AWS ingestion of
  10,000 vectors, query latency on AWS and real cost are reported as NOT RUN until the
  cloud phases. State how many meaningful units the current dataset subset produces;
  if it is under 10,000, say so, and the human will ask the instructor about a smaller
  corpus. Never pad the corpus to reach the number.
- Formatting: every agent formats only the files it owns (`scalafmtOnly <files>` or its
  own module's `scalafmt`), never `scalafmtAll` (Phase 07 gate, R2-MAJOR-1).
- Unattended overnight run: the human is asleep. Do not stop to ask questions. Take the
  most reasonable reading, record each choice and why in the step report, and carry on.
  Stop only for BLOCKED-ON, or when a stage still fails after the step-downs below.
- Dataset scale-up (human decision, 2026-11-23): the integrated run moves from the
  committed 40-record sample to the assignment's recommended minimum of 100,000 DBLP
  works. Raw downloads go only to git-ignored paths (`datasets/samples/local/<snapshot>/`).
  I approve downloading and streaming the full DBLP dump once for this. Nothing goes to S3
  and nothing large is committed; the manifest records the subset rule, counts, checksums
  and the year distribution.
  Step-down rule for every stage below: if a stage fails for memory or does not finish in
  90 minutes, rerun it once at 25,000 works, then once at 10,000. Record every attempt and
  its failure in the report; never hide a failed attempt. Local pipeline runs may use up
  to a 2 GB JVM heap, with no other sbt or JVM running.
- Order, one owner at a time:
  S1. dataset-engineer: extend `datasets/scripts/fetch_samples.py` and the manifest, with
     no new dependency:
     - DBLP: select 100,000 records of the existing types with at least 2 authors,
       spread across publication years 2000 to 2024 by a deterministic rule (for example
       a hash of the dblp key), not the first N in file order, so every configured time
       window has data. Raise `--max-bytes` only as far as this needs.
     - OpenAlex: query by DOI for every DOI in the new DBLP subset, in batches of 50, and
       add `abstract_inverted_index` to the projection, so paper abstracts exist.
     - Crossref: cap at 5,000 DOIs, which keeps it a real linked dataset without hours
       of single-DOI requests.
     - ORCID: unchanged. The ORCID API-terms decision is still pending, and ORCID iDs in
       the DBLP author elements remain the ORCID evidence.
     Run the fetch and `verify`, and validate the manifest. Report the counts per
     dataset, the year distribution and the checksums as
     step-S1-dataset-engineer-report.md.
  S2. hadoop-engineer: run the Phase 04 local pipeline (`LocalRunner`, J01 to J07) on the
     S1 snapshot, with no code change unless a run fails. Report the run ID, the counters,
     the maximum team size, the largest pair expansion and the largest blocking group as
     step-S2-hadoop-engineer-report.md.
  S3. entity-resolution-engineer: run the er-v1 match job (`LocalMatchRunner`,
     adjudicator off) on the S2 run, with er-v1 unchanged. The gold set and the Phase 05
     evaluation stay as they are; do not re-evaluate. Report the run ID and the outcome
     counts as step-S3-entity-resolution-engineer-report.md.
  0. spark-engineer (Phase 07 gate decisions): add a JSON-lines export next to the
     Parquet tables for every table the storage loaders read, with identical
     identifiers and fields, listed in the load manifests. Add `institutionId` to the
     semantic summaries. Emit paper-abstract rows from the abstracts present in the
     Phase 04 outputs; if the sample has none, say so and emit none, never invent text.
     List every exported coauthor window in `neptune.json`. Run `sbt sparkAnalytics/test`
     and run the local pipeline on the S2 Hadoop run and the S3 decision run. Report the
     new Spark run ID as step-00-spark-engineer-report.md. Every later step reads that
     run through its manifest.
  1. rag-engineer, task R1: the deterministic rendering templates for researcher
     profiles, community profiles, collaboration paths, representative-paper summaries
     and topic-institution summaries, each with a template version, rendered only from
     computed data and source records of the Phase 06 export; the chunking policy for
     abstracts or longer text; the embedding model versioning rule. Implement rendering
     behind the port storage defines (ask storage-engineer for it through BLOCKED-ON if
     it does not exist). Report as step-01-rag-engineer-report.md.
  2. storage-engineer: the bounded embedding work queue (SQS adapter plus the
     in-memory fake, or a manifest-driven batch design; state which and why), vector
     write batches, source and rendered text in ordinary S3 with the vector holding only
     the pointer and compact metadata (vector key, researcher or community ID, time
     window, topic or institution IDs, document type, source dataset, model version,
     text hash, run ID), skip of unchanged text by content hash and model version,
     refusal of one vector key for two different texts, and failures and retry counts in
     RunLedger. Loaders read the step 0 JSON-lines export. Run the local ingestion of
     the step 0 export into all five indexes (if the in-memory store cannot hold every
     unit, ingest a bounded, deterministic subset and report both the total meaningful
     units and the number ingested) and save counts, throughput,
     rejected records and duplicate handling as
     docs/agent-runs/phase-08/output-local-ingestion.md. Report as agent-report.md.
  3. rag-engineer, task R2: the compact semantic-context format for the synthesis
     agent, fetched from a vector hit through its S3 pointer, and retrieval smoke tests
     with known expected researcher, topic or community neighbours on a hand-built
     fixture. Show that no exact count, shortest path, component or bridge value is
     taken from vector similarity. Cover each gate item in the tests: one vector key
     never refers to two texts, the model version is stored, text is fetched through
     the S3 pointer, the smoke tests pass, and graph values never come from vectors.
     Then run the full sbt check once. Report as step-02-rag-engineer-report.md.
  4. documentation-engineer: the embedding and vector-index section (indexes, metadata
     schema, template and model versions, batch and retry settings, object counts from
     the local run, the cost method with each price's documentation URL and date marked
     as an estimate), the lineage line, and the module notes, with only facts observed
     here. Report as step-03-documentation-engineer-report.md.
  Update STATUS.md through documentation-engineer after each owner.
- Then one fresh code-reviewer review (review-report.md) and one repair round
  (repair-01*.md). Run a fresh re-check (review-report-02.md) only if the review had a
  CRITICAL or MAJOR finding. Findings still open after that are carried forward to the
  human; there is no second repair round.
- Machine limits (8 GB laptop, 5 GB for WSL): never more than one sbt at once, no
  background shells, no polling or wait loops, no mutation testing; every command runs
  in the foreground with a timeout. While working, run only the tests of the module
  being changed (`sbt sparkAnalytics/test`, `sbt rag/test` or `sbt storage/test`; S2 and S3
  run only their pipelines); the
  full sbt check runs once, at the end of step 3, and again only if a repair changes
  source. If the harness moves sbt to the background, do not start anything else and
  do not poll; wait for it.
- No agent commits, runs cleanup.sh or touches cloud resources in this phase.
