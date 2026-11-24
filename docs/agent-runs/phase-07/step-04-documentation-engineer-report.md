HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier, role default (no override)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: phase 07 step 05. Update STATUS.md for step 04, write docs/service-limits.md, write the storage section as docs/storage.md, rewrite modules/storage/MODULE.md, update modules/core/MODULE.md and modules/entity-resolution/MODULE.md, then set step 05 done and Review 1 next.

FILES CREATED:
- /home/ajcreator/scholarchain/docs/service-limits.md
- /home/ajcreator/scholarchain/docs/storage.md

FILES MODIFIED:
- /home/ajcreator/scholarchain/STATUS.md
  - Step 04 is done (119 entityResolution tests; full sbt check exit 0, 312 s, totals 153+1 ignored, 115, 68, 119, 98, 33).
  - Step 05 is done and Review 1 is next.
  - Added "Step 04 notes" and "Step 05 notes".
  - The step 00 row now reads "21 new tests by suite counts, the report says 29".
- /home/ajcreator/scholarchain/modules/storage/MODULE.md (rewritten)
- /home/ajcreator/scholarchain/modules/core/MODULE.md (new `scholarchain.core.aws` public API subsection, Done, Not done, Known issues, Dependencies)
- /home/ajcreator/scholarchain/modules/entity-resolution/MODULE.md (BedrockAdjudicator, AdjudicatorMode.BEDROCK, BedrockNotInJob, MatchJob.portFor, Done, Not done, Known issues, Dependencies)

COMMANDS RUN (successful output observed):
- Read-only aws-docs tools (read_documentation, read_sections, search_table, search_documentation) on 2026-11-23. No other AWS call was made.
- `grep`, `awk` and `ls` on the repository: line-length check, em dash check, link and path existence, and spot checks of code facts. These confirmed `"region" -> region` and `"iamRoleArn"` in `NeptuneAdapters.scala:103-104`, `DefaultMaxChars = 20000` in `Embedder.scala`, the 7-day session TTL default in `Tables.scala`, the opt-in variable name in `AwsIntegrationSuite.scala`, and the totals in `output-sbt-check-step04.txt`.
- python3 for text edits.
- No sbt was run, as ordered.

TEST RESULTS: None run by me. The figures I cite come from the saved reports and `output-sbt-check-step04.txt`. That file shows exit 0 in 312 s, and its totals are 153 (1 ignored), 115, 68, 119, 98 and 33.

VALIDATION RESULTS:
- Limits re-checked on 2026-11-23 against these documentation pages:
  - DynamoDB: Constraints, Programming.Errors, TTL.
  - S3: conditional-writes.
  - Neptune: bulk-load-tutorial-format-opencypher, load-api-reference-load, opencypher-parameterized-queries.
  - S3 Vectors: limitations, metadata-filtering, regions-quotas, and the CreateIndex, CreateVectorBucket, PutVectors and GetVectors API pages.
  - Bedrock: Titan embed request page, Titan V2 model card, InvokeModel, the Anthropic messages page, and the general service-quota table.
  - Each is marked VERIFIED or UNVERIFIED in `docs/service-limits.md`, with a consolidated unverified list.
- Findings from the re-check:
  - DynamoDB TTL deletion is "typically within a few days". The comment at `modules/storage/.../Tables.scala:304` says "up to 48 hours", which is wrong. It is a comment only.
  - HTTP 409 `ConflictException` is documented for `CreateVectorBucket` and `CreateIndex`, which closes item (d) of the step 03 unverified list.
  - Titan V2 default quotas are 6,000 requests per minute and 300,000 tokens per minute, not adjustable. This closes item (g).
  - The Titan request page lists dimensions 1024, 512 and 256. The model card as read lists no dimensions, and the earlier "384" was not reproduced.
  - The Neptune loader body must name the region field `region`. The adapter does.
  - Anthropic `stop_reason` has values beyond `max_tokens` (refusal, tool_use, model_context_window_exceeded). The step 04 report says only `max_tokens` fails the call, and the strict validator handles the rest. Whether that is the right handling for `refusal` is not decided.
- Report inconsistency found: the step 00 report says "86 existing + 29 new" core tests, but its suite counts are 6+6+9 = 21 and 94 (Phase 03) + 21 = 115. I recorded 21 in `modules/core/MODULE.md` and the STATUS row.
- Formatting checks:
  - No em dash in any file I wrote or changed.
  - `docs/service-limits.md` and `docs/storage.md` exceed 90 characters only on documentation URLs, which cannot be wrapped.
  - Two lines in STATUS.md table rows and one MODULE.md bullet that names a long report path also exceed 90 characters.
  - Every relative link and path I added exists, and the three Markdown links between `docs/storage.md` and `modules/storage/MODULE.md` resolve.
- Documented commands: the only runnable command I documented is the gated suite command (sbt plus real AWS credentials). It was not replayed, and I state it was NOT RUN.

ASSUMPTIONS:
- All storage, Neptune, vector, retry and idempotency facts in `docs/storage.md` and `modules/storage/MODULE.md` come from the step 01, 02 and 03 reports and the spot checks above. I did not read the full code line by line.
- Mapping the sbt check totals to modules (storage 153, core 115, adversarial 68, entityResolution 119, hadoop 98, spark 33) is inferred from the suite names, as the step 04 report states. The storage and entityResolution mappings are also confirmed by those modules' own runs.

RISKS:
- Nothing in the storage or ER adapters was verified against live AWS. `service-limits.md` lists what is unverified, including the S3 5 GB single put, bucket-name length, ListObjectsV2 and its SigV4 query form, Neptune `LIMIT $limit`, the `*1..N` templates and `id(n)`, and the `GetVectors` behavior for absent keys. The list also covers the batch of 100 against the PutVectors 503 capacity rejection, the Titan maximum characters (the 20,000 is a repo choice), the `%3A` model-ID path signing, and model access in the account.
- NIT-4 of Phase 05 (an absent outcome folder is empty only behind `_SUCCESS`) was not checked for the loaders. Both storage docs say so, and it stays open for the reviewer.
- The Neptune cleanup section says no procedure was written, because none exists in the reports.
- `docs/agent-runs/phase-07/step-04-documentation-engineer-report.md` was not written by me (that folder is the orchestrator's). This message is the report for the orchestrator to save under that name.
- Not done: no sbt, no replay of sbt commands, no AWS call beyond the documentation tools. The three MODULE.md files and the two new docs were not reviewed by anyone else.
- State left behind: uncommitted edits to STATUS.md and the three MODULE.md files, plus two new uncommitted docs.

MANUAL ACTION REQUIRED:
- Human decisions already open: the Parquet reader gap, the Spark export gaps (no `institutionId`, no paper-abstract rows), and whether adjudication keeps sharing `models.synthesisModel`.
- Routing for the orchestrator: storage-engineer to correct the Tables.scala:304 comment (the "48 hours" claim).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review 1, a fresh code-reviewer on the whole Phase 07 diff, saved as review-report.md.
