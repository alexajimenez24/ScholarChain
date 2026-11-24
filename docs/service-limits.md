# Service limits relied on by the storage and entity-resolution code

Region: `us-east-2`.
Checked: 2026-11-23, with the aws-docs documentation tools (read-only).
Every limit below names its documentation URL.
VERIFIED means the page was read on 2026-11-23 and says what is written here.
UNVERIFIED means the page was not read, the page does not say it, or the value is a
design choice of this repository that no page confirms.
Nothing here was measured against a live AWS account: no AWS call was made in Phase 07.
The reference file `~/ref-phase-07/docs/service-limits.md` was not used for any value.
Its figures (for example a batch of 200) were not carried over.

## DynamoDB

- Partition key value: 1 to 2048 bytes.
  Sort key value: 1 to 1024 bytes.
  Item size: at most 400 KB, counting attribute names and values.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html
  Enforced in `KvItem.validate` (`Ports.scala`), shared by the fake and the adapter.
- `ConditionalCheckFailedException` is an HTTP 400 and "OK to retry? No".
  `ProvisionedThroughputExceededException` is also listed under HTTP 400.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Programming.Errors.html
  The adapters map the first to `KvError.ConditionFailed` and do not retry the second
  (see "Known issues" in `modules/storage/MODULE.md`).
- TTL: the TTL attribute must be a Number in Unix epoch seconds.
  DynamoDB deletes expired items "typically within a few days after their expiration", at
  any time, and the page gives no tighter bound.
  Until deletion an expired item can still be read and updated, so reads should filter
  it out and writes to it should use a condition expression.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html
  The code hides expired items on reads and takes an expired key over with a
  version-guarded replace, which is the documented pattern.
  CORRECTION: the comment at `modules/storage/.../Tables.scala:304` says deletion "can lag
  by up to 48 hours".
  The page does not say that; it says "within a few days".
  The comment is storage-engineer's to fix; behavior is unaffected.
- Conditional writes with `attribute_not_exists` and a version condition, and strongly
  consistent reads (`ConsistentRead`): used by the adapter and proven only against the
  local stub.
  UNVERIFIED against the live service (the expression syntax and the `__type` error
  field were written from the API documentation and not re-read here).

## S3 (ordinary buckets)

- Conditional write with `If-None-Match: *`: 200 when the key is absent, 412 when it
  exists, and 409 when a concurrent delete wins.
  A `PutObject` may be retried after a 409.
  The request must be SigV4 signed and the caller needs `s3:PutObject`.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html
  `S3ObjectStore` maps 412 to `AlreadyExists` and 409 to a retryable error.
- Single `PutObject` up to 5 GB, bucket-name length of at least 3 characters, the
  `ListObjectsV2` response shape and the SigV4 canonical form for
  `list-type=2&prefix=...`: UNVERIFIED (not read on 2026-11-23; proven only against the
  local stub).
- Server-side storage of the `x-amz-meta-sha256` header the adapter stamps and reads
  back: UNVERIFIED against the live service.

## Neptune

- openCypher bulk-load CSV: UTF-8, RFC 4180, nodes and relationships in separate files,
  system columns `:ID`, `:LABEL`, `:START_ID`, `:END_ID`, `:TYPE`, property headers
  `name:Type`, no space, comma or line break in a header.
  A `Bool` column treats any value other than `true` as `false`.
  All files of one load share one S3 path prefix.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-format-opencypher.html
  Documented types include `String`, `Int`, `Long`, `Double`, `Bool` and `DateTime`.
- Loader request: `source`, `format` = `opencypher`, `iamRoleArn` and `region` are
  required, and the JSON body must name the region field `region` (not `s3BucketRegion`).
  `mode` defaults to `AUTO`, `failOnError` to `TRUE`, `parallelism` to `HIGH`.
  `queueRequest` allows up to 64 queued jobs.
  `userProvidedEdgeIds` makes an `:ID` column required in every relationship file.
  `updateSingleCardinalityProperties` = `TRUE` replaces an existing property value.
  `parallelism` can raise `LOAD_DATA_DEADLOCK` for openCypher; lowering it is the
  documented fix.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html
  `NeptuneBulkLoader.start` sends `region`, `iamRoleArn`, `format=opencypher`,
  `mode=AUTO`, `failOnError=TRUE`, `parallelism=MEDIUM`, `userProvidedEdgeIds=TRUE`,
  `updateSingleCardinalityProperties=TRUE` and `queueRequest=TRUE` (the first two checked
  in `NeptuneAdapters.scala`, the rest from the step 02 report).
- Duplicate handling in an openCypher load: rows with the same node ID are merged and, for
  a property present in several rows, one value is chosen non-deterministically.
  Of several rows with one relationship ID, only one is loaded, chosen
  non-deterministically.
  An existing node or relationship keeps its property values unless
  `updateSingleCardinalityProperties` is `TRUE`.
  VERIFIED 2026-11-23 (same page as above).
  This is why every identifier in the load files is the deterministic Spark ID and why
  the exporter drops identical duplicates and rejects conflicting ones before writing.
- Parameterized openCypher over HTTPS: `POST /openCypher` with form fields `query` and
  `parameters` (JSON), default port 8182, SigV4 signing name `neptune-db` when IAM
  authentication is on.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-parameterized-queries.html
  The signing name `neptune-db` appears in that page's `awscurl` example.
- UNVERIFIED (not read; proven only against the local stub): `LIMIT $limit` with a
  parameter, the `*1..N` variable-length path templates and their cost on a dense graph,
  `id(n)` in `WHERE`, the exact `/loader` and `/openCypher` reply bodies, and Neptune
  accepting quoted fields that contain newlines.
- Neptune query timeout: `NeptuneConfig.queryTimeoutMs` is not applied per request
  (`AwsHttp` has one timeout for the client).
  This is a code limitation, not a documented service limit.

## S3 Vectors

All values VERIFIED 2026-11-23.
URL: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html

- Dimension per vector: 1 to 4,096.
  Vectors per index: up to 2 billion.
- Total metadata per vector: 40 KB on CLASSIC indexes, 100 KB on ENHANCED.
  Filterable metadata per vector: 2 KB on CLASSIC, 40 KB on ENHANCED.
  Filterable string value (also each element of a string list): up to 2 KB.
  Metadata keys per vector: up to 50.
  Non-filterable metadata keys per index: up to 10.
  This repository designs to the stricter CLASSIC values so a record fits both modes.
- `PutVectors`: up to 500 vectors per call.
  `DeleteVectors`: up to 500.
  `GetVectors`: up to 100 keys (key length 1 to 1,024).
  Request payload: up to 20 MiB.
  Combined Put and Delete requests: up to 1,000 per second per index.
  Combined vectors inserted and deleted: up to 2,500 per second per index.
  The loader uses batches of 100 (a choice, see below).
- `QueryVectors`: topK up to 10,000, 100 results per page.
  The adapter returns one page (topK 1 to 100) and does not page.
- Index mode: vector buckets created on or after 2026-09-30 create ENHANCED indexes,
  older buckets create CLASSIC indexes.
  Metadata is filterable unless named non-filterable when the index is created.
  Operators used here: `$eq` (also matches an element of a list), `$gte`, `$lte`, `$and`.
  URL: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-metadata-filtering.html
- Endpoint in `us-east-2`: `s3vectors.us-east-2.api.aws`, HTTPS, Signature Version 4.
  URL: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-regions-quotas.html
- `CreateIndex`: `dataType` is `float32`, `dimension` 1 to 4,096, `distanceMetric` is
  `euclidean` or `cosine`, `indexName` 3 to 63 characters, and
  `metadataConfiguration.nonFilterableMetadataKeys` lists the non-filterable keys.
  Errors: 409 `ConflictException` (name already exists), 402 quota, 408 timeout,
  429 throttling, 503 unavailable, 400 validation.
  URL: https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_CreateIndex.html
- `CreateVectorBucket`: 409 `ConflictException` when the name exists, same other errors.
  URL: https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_CreateVectorBucket.html
- `PutVectors`: 1 to 500 vectors; a batch above the "resource capacity" is rejected with
  503 `ServiceUnavailableException`; 408, 429 and 503 are the retryable statuses;
  400 `ValidationException` covers oversized metadata.
  URL: https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_PutVectors.html
- UNVERIFIED: that `GetVectors` omits absent keys instead of failing (the page read on
  2026-11-23 lists `keys`, `returnData` and `returnMetadata` and does not say).
  URL read: https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetVectors.html
  The loader's "unchanged" decision depends on this behavior.
- UNVERIFIED: that a batch of 100 never triggers the 503 capacity rejection.
  100 is this repository's choice (a retry costs little), not a measured limit.

## Bedrock embeddings (Titan Text Embeddings V2)

- Request body: `inputText` (required), `dimensions` (1024 default, 512 or 256),
  `normalize` (default true), `embeddingTypes` (default `float`).
  Response: `embedding`, `inputTextTokenCount`, `embeddingsByType`.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-titan-embed-text.html
  The project default dimension 256 is in the accepted set.
- Model card: model ID `amazon.titan-embed-text-v2:0`, context window 8K tokens,
  `bedrock-runtime` endpoint `https://bedrock-runtime.{region}.amazonaws.com`, in-Region
  inference supported in `us-east-2`, no geo or global inference ID.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-amazon-titan-text-embeddings-v2.html
  The card as read lists no dimensions.
  An earlier report said the card lists 256, 384 and 1024; that was not reproduced on
  2026-11-23, and the request page above is the dimension source.
- Default quotas for Titan Text Embeddings V2: 6,000 `InvokeModel` requests per minute and
  300,000 tokens per minute, "Each supported Region", not adjustable.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/general/latest/gr/bedrock.html
  The loader sends one request per text, sequentially, so throughput against these quotas
  was not tested.
- UNVERIFIED: the maximum input in characters.
  `BedrockEmbedder` refuses text over 20,000 characters (never truncates) as a
  conservative stand-in for the 8K-token context window; the 20,000 is this repository's
  choice.
- UNVERIFIED: that SigV4 double-encodes the `:` of the model ID in the request path
  (`%3A`) the way the signer does.
  The path and the signature were proven only against the local stub and a recomputed
  signature.

## Bedrock InvokeModel (entity-resolution adjudicator)

- `POST /model/{modelId}/invoke`, `Content-Type` must be `application/json`, body up to
  25,000,000 bytes, SigV4.
  Errors: 403 access denied, 500 internal, 424 model error, 429 throttling or model not
  ready, 408 model timeout, 404 resource not found, 400 validation or service quota,
  503 unavailable.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_InvokeModel.html
  `BedrockAdjudicator` treats every non-2xx as `LLM_CALL_FAILED` (the case stays
  UNRESOLVED) and does not retry (default `maxAttempts` 1).
- Anthropic Claude Messages body: `anthropic_version` must be `bedrock-2023-05-31`,
  `max_tokens` is required, `messages` hold `role` and a `content` array of typed blocks,
  `temperature` is optional.
  The response has `content` blocks and `stop_reason`.
  The page lists `stop_reason` values `end_turn`, `max_tokens`, `stop_sequence`,
  `refusal`, `tool_use` and `model_context_window_exceeded`.
  A warning says Claude Sonnet 4.5 and Haiku 4.5 accept `temperature` or `top_p` but not
  both; the adapter sends `temperature` only.
  VERIFIED 2026-11-23.
  URL: https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-anthropic-claude-messages-request-response.html
  The step 04 report names `max_tokens` as the one `stop_reason` that fails the call.
  A reply stopped for another reason that still carries exactly one text block goes to
  the strict validator, which refuses non-conforming text with `LLM_INVALID_OUTPUT`.
  Whether that is the desired handling for `refusal` is not decided here.
- UNVERIFIED: that the model ID in `models.synthesisModel` (a `${SYNTHESIS_MODEL_ID}`
  placeholder in the config) names a model enabled for this account in `us-east-2`.
  No model ID is committed and no live call was made.

## Prices (estimate inputs, Phase 08)

Checked: 2026-11-24, with the aws-docs documentation tools.
The tools read only `docs.aws.amazon.com`, so no price page could be read.
A request for `https://aws.amazon.com/s3/pricing/` returned
"URL must be from list of supported domains".

- S3 Vectors PUT, storage and query prices: UNVERIFIED.
  Published on https://aws.amazon.com/s3/pricing/ (not read).
- Titan Text Embeddings V2 price per 1,000 input tokens: UNVERIFIED.
  The model card section Pricing sends readers to https://aws.amazon.com/bedrock/pricing/
  (not read).
  URL read: https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-amazon-titan-text-embeddings-v2.html
- No price value is written anywhere in this repository.
  The cost method and the unit quantities are in [embeddings.md](embeddings.md).
- The S3 Vectors and Bedrock limits in the sections above were not re-read in Phase 08;
  the Phase 07 check date stands.

## Consolidated unverified list

1. DynamoDB: live behavior of the conditional-write expressions and error mapping.
2. S3: 5 GB single put, bucket-name length, `ListObjectsV2` and its SigV4 query form,
   the `x-amz-meta-sha256` round trip.
3. Neptune: `LIMIT $limit`, `*1..N` cost, `id(n)` in `WHERE`, exact `/loader` and
   `/openCypher` replies, quoted newlines in CSV.
4. S3 Vectors: `GetVectors` omitting absent keys; batch 100 never hitting the
   capacity 503.
5. Bedrock embeddings: maximum characters; `%3A` double encoding in the SigV4 path.
6. Bedrock InvokeModel: model access in the account and region.
7. Everything above that says "proven only against the local stub".
8. S3 Vectors and Titan prices (Phase 08, see Prices above).

Resolved on 2026-11-23 against the Phase 07 step reports:

- DynamoDB TTL lag: now documented as "typically within a few days" (the "48 hours"
  comment in `Tables.scala` is wrong).
- HTTP 409 on `CreateVectorBucket` and `CreateIndex`: documented (`ConflictException`).
- Bedrock Titan quotas: read (6,000 requests and 300,000 tokens per minute).
- Titan V2 dimensions: the request page lists 1024, 512 and 256.

Re-verify these pages again on the day of the first cloud run (Phase 12 onward).
