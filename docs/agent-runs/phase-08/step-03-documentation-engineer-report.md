HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier (role default, as the work order says)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 08 step 04 (rerun). Report name set by prompt.md: step-03-documentation-engineer-report.md.
- Write the embedding and vector-index section and the lineage line.
- Record the dataset scale-up and step-down facts.
- Update the module notes of core, storage, rag, spark, hadoop and entity-resolution.
- Update STATUS.md and PROJECT_MEMORY.md.
Facts come only from the Phase 08 reports and files.

FILES CREATED:
- /home/ajcreator/scholarchain/docs/embeddings.md (new file, chosen over a docs/storage.md section because the topic spans rag, storage and retrieval)
  - Contents: the five indexes, the templates and chunk-v1.
  - The embedding version rule and the metadata schema (filterable and non-filterable keys).
  - The S3 object layout, queue, batch and retry settings.
  - The local run counts, throughput and latency; semantic-context-v1; the gate-to-test map; the cost method.
  - A NOT RUN table: AWS ingestion of 10,000 vectors, S3 Vectors query latency and real cost.
  - The meaningful-unit count: 223,953 source rows at 25k works, 28,527 ingested, above 10,000, so no smaller corpus.
- /home/ajcreator/scholarchain/docs/data-lineage.md (new; none existed)
  - Contents: a pipeline stage table linking the other docs, and the semantic-context lineage line (Spark JSON-lines row to rag rendering to S3 chunk object with pointer to vector to semantic-context-v1 item).
  - Scale-up and step-down facts: the S1 100,000-work snapshot with partial OpenAlex, Spark failing at 100k, the 25k chain and run IDs.
  - Also componentMaxIterations 40 to 100 and path rule spark-v4.

FILES MODIFIED:
- /home/ajcreator/scholarchain/STATUS.md
  - Phase 08 bullet in "Where things stand" updated.
  - Rows 03 and 04 set to done and Review 1 to next.
  - Notes 03 and 04 added after Note 02b.
  - New section "Carried forward from Phase 08 (for the human gate)" covering every item the work order listed.
- /home/ajcreator/scholarchain/PROJECT_MEMORY.md (8 decision rows: 25k step-down, componentMaxIterations 100, path pair rule spark-v4, renderer port in storage implemented by rag, abstract template v2, manifest-driven queue instead of SQS, embedding version, semantic context)
- /home/ajcreator/scholarchain/docs/storage.md (Phase 08 update notes in section 4 and section 8, pointing to embeddings.md)
- /home/ajcreator/scholarchain/docs/service-limits.md (a "Prices (estimate inputs, Phase 08)" section and unverified-list item 8)
- /home/ajcreator/scholarchain/docs/spark-jobs.md, hadoop-jobs.md, entity-resolution.md (each a new "Phase 08 real-scale runs" section)
- /home/ajcreator/scholarchain/modules/rag/MODULE.md (rewritten: public API, done, not done, known issues)
- /home/ajcreator/scholarchain/modules/{core,storage,spark,hadoop,entity-resolution}/MODULE.md (Phase 08 subsections added in the API, done, not done and known issues positions; earlier Phase 07 text left as the record)

COMMANDS RUN (successful output observed):
- reads: cat, sed, grep and ls over the reports and docs.
- `python3` text-insert scripts for the edits.
- arithmetic `python3 -I -c` for vector bytes: 31,341 x 256 x 4 = 32,093,184.
- link and path check loop.
- `awk` line-length check, `grep -c` for em dashes (0 in the new or rewritten files and in STATUS.md, PROJECT_MEMORY.md).
- aws-docs tools: search_documentation (3 calls), read_sections of the Titan model card "Pricing" section, and read_documentation of https://aws.amazon.com/s3/pricing/, which failed with "URL must be from list of supported domains".
- Not run: sbt (forbidden), any AWS call.

TEST RESULTS: none run by me.
- The test figures I wrote are cited from the reports: sbt check exit 0 in 362 s, rag 63, storage 179 with 1 ignored, core 120, spark 45.
- The per-project lines 120, 179, 63, 68, 98, 45, 119 are cited as printed.

VALIDATION RESULTS:
- Every new relative link in embeddings.md, data-lineage.md and the edited MODULE.md files resolves (the loop printed no MISSING).
- prompts/schemas/semantic-context-v1.json and the phase-08 report and output files exist.
- Arithmetic was replayed with python3 (32,093,184; 10,240,000 for 10,000 vectors; 63 calls at 500 per PutVectors).
- No em dashes.
- Prose lines are at most 90 characters.
- Lines still over 90 are table rows, URL lines and a few other lines only, as in the existing docs.
- Notes sections updated from: S1, S1b, S2, S2b, S3, S3b, 00, 00b, 00c, 00d, 01, 01b, 02a, 02b (agent-report.md and output-local-ingestion.md) and 03 (step-02-rag-engineer-report.md), plus run-log.md.

ASSUMPTIONS (the human is asleep):
1. New docs/embeddings.md instead of a section in storage.md.
2. The prices are marked UNVERIFIED, never invented: the aws-docs tools read only docs.aws.amazon.com, and aws.amazon.com pricing pages are refused.
   - The Titan model card's Pricing section only links to https://aws.amazon.com/bedrock/pricing/ (read 2026-11-24).
   - The S3 Vectors price URL given (https://aws.amazon.com/s3/pricing/) is the general S3 pricing page; I did not confirm it lists S3 Vectors prices.
3. The 5,000,000-token upper bound for 10,000 chunks uses rag's "2,000 units is about 500 tokens" figure.
   - It is labeled an unmeasured assumption.
4. Hadoop run `run-8e1abb4c22da9c06` is the run manifest ID quoted in the existing STATUS note S2 for `modules/hadoop/datasets/`; I did not inspect the folder.
5. The STATUS notes 03 and 04 were placed after Note 02b, not before "Notes on the table".

RISKS:
- Not verified: all run figures are copied from the reports; none were recomputed.
- The only exception is the arithmetic above.
- The documented sbt and Spark commands were not replayed (sbt forbidden).
- The docs cite report output instead, as the work order allows.
- The cost estimate has no dollar value; it is a method only, pending a human fill-in of two prices.
- Phase 07 statements in storage.md and storage MODULE.md were left in place with Phase 08 notes beside them, not rewritten.
- The Phase 07 Parquet reader known issue in storage MODULE.md is noted as closed in a Phase 08 bullet only.
- The two docs-folder files .md under 90 characters rule is met except for tables and URLs.
- State left behind: scratch script in the session scratchpad (doc/mod.py).
- I did not edit docs/agent-runs/** (run-log.md row for this rerun is the orchestrator's), code, or any control-plane file.
- The git-ignored data folders were not touched.

MANUAL ACTION REQUIRED: the human may fill the two prices from https://aws.amazon.com/s3/pricing/ and https://aws.amazon.com/bedrock/pricing/ before the cloud phases.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer Review 1 of the whole phase (review-report.md).
Items for hadoop-engineer at review: the untracked modules/hadoop/datasets/ folder.
Notice from the aws-api MCP server instructions: it is entering end of development; migration guide https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md.
