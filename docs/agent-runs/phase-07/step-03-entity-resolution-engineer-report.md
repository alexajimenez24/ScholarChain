HARNESS: claude-code
MODEL: Opus 5.5 (claude-opus-5-5), high tier, role default (effort high per .agents/models.json)

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: phase 07 step 04. Add the Bedrock adjudicator adapter behind the Phase 05 port, using core's signing client (AwsHttp, service "bedrock", the bedrock-runtime InvokeModel endpoint), with the model ID and region taken from core configuration. The strict JSON schema, the no-new-identifier rule, the cache key and er-v1 stay unchanged. Test only against a local com.sun.net.httpserver stub, then run the full sbt check once. Report to be saved as step-03-entity-resolution-engineer-report.md.

FILES CREATED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/BedrockAdjudicator.scala
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/BedrockAdjudicatorSuite.scala (the suite plus the test-only helpers InvokeModelStub, StubSeen and StubReply)

FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/Adjudication.scala
  - Added `AdjudicatorMode.BEDROCK`.
  - Added `AdjudicatorMode.BedrockNotInJob` (the refusal message).
  - `parse("bedrock")` still returns Left, now with that message instead of "arrives with storage in Phase 07".
  - Updated one doc comment on FakeAdjudicator.
- /home/ajcreator/scholarchain/modules/entity-resolution/src/main/scala/scholarchain/er/MatchJob.scala
  - Added `MatchJob.portFor(mode, template): Either[String, AdjudicatorPort]`. OFF gives OffAdjudicator, FAKE gives FakeAdjudicator, BEDROCK gives Left(BedrockNotInJob).
  - The reducer setup and `run` now use portFor, and the manifest's prompt and model versions come from the port. The values are the same as before: OFF gives none/none, FAKE gives er-adjudication-v1/fake-deterministic-v1.
  - The llmEnabled refusal message was reworded.
  - Job behavior is unchanged, so LogicVersion stays er-job-v1.
- /home/ajcreator/scholarchain/tests/module-unit/entity-resolution/AdjudicationSuite.scala: the test "bedrock mode is not available before Phase 07" is now "the match job refuses bedrock mode: the adapter exists but is not wired into the job". It also checks portFor for all three modes.

ADAPTER PUBLIC API (package scholarchain.er):
```scala
final class BedrockAdjudicator private (...) extends AdjudicatorPort:
  val mode: AdjudicatorMode            // BEDROCK
  val modelVersion: String             // = the configured model ID (enters the cache key as before)
  val promptVersion: String            // = template.version (er-adjudication-v1)
  val maxTokens: Int
  val endpoint: String                 // <base>/model/<URL-encoded model ID>/invoke
  def complete(prompt: String, request: AdjudicationRequest): Either[String, String]  // never throws
  def requestBody(prompt: String): Array[Byte]
object BedrockAdjudicator:
  val SigningName = "bedrock"; val AnthropicVersion = "bedrock-2023-05-31"; val DefaultMaxTokens = 512
  def fromConfig(cfg: ScholarChainConfig, template: PromptTemplate, credentials: CredentialsProvider,
      maxTokens: Int = 512, maxAttempts: Int = 1, requestTimeout: Duration = 30 s,
      transport: HttpTransport = new JdkHttpTransport(), clock: Clock = Clock.systemUTC(),
      endpointOverride: Option[String] = None): Either[String, BedrockAdjudicator]
  def replyText(body: String): Either[String, String]
```
- Model ID: `cfg.models.synthesisModel`. Region: `cfg.application.region`.
- `fromConfig` builds the AwsHttp itself, so the signing region and the URL region always match.
- Default base URL: `https://bedrock-runtime.<region>.amazonaws.com`. `endpointOverride` replaces it, for the stub.
- The request is `POST /model/{modelId}/invoke` with an Anthropic Messages body: `anthropic_version`, `max_tokens`, `temperature` 0, and one user message holding the rendered prompt. Headers are content-type and accept `application/json`, SigV4-signed with service `bedrock`.
- `fromConfig` returns Left when:
  - `llmEnabled` is false;
  - the model ID or the region is empty;
  - the model is not Anthropic-family (pattern `(^|[./:])anthropic\.`, which covers model IDs, cross-region profile IDs and ARNs that contain one);
  - `maxTokens` < 1 or `maxAttempts` < 1.

ERROR-TO-OUTCOME MAPPING (the port's existing contract; every row leaves the case UNRESOLVED with the reason code appended and nothing cached):

| Situation | Port result | Adjudicator reason |
|---|---|---|
| No credentials, or the provider throws | Left "InvokeModel failed: ..." | LLM_CALL_FAILED |
| Transport failure, connection refused, timeout | Left "InvokeModel failed: ..." | LLM_CALL_FAILED |
| Any non-2xx status (400, 403, 404, 424, 429, 500, 503 tested) | Left "InvokeModel HTTP <status> <x-amzn-errortype before ':'>"; the body is never echoed | LLM_CALL_FAILED |
| 2xx envelope: not JSON, empty, not an object, no content array, not exactly one block, block not of type text, text not a string, stop_reason max_tokens | Left | LLM_CALL_FAILED |
| Well-formed envelope, text breaks the schema (prose, code fence, extra or missing key, decision outside the set, confidence outside [0, 1], new source or target ID, identifier or DOI in a reason code, uncited field) | Right(text), then the unchanged `Adjudication.validate` | LLM_INVALID_OUTPUT |
| Valid text | Right(text), validated, cached under the unchanged key | Answered (Resolver applies minLlmConfidence as before) |
| Budget spent | no call | LLM_BUDGET_EXHAUSTED (unchanged) |

COMMANDS RUN:
- `sha256sum` on the policy and prompt resources. Also on a hand-written input JSON and the cache-key preimage, to get independent pinned values.
- `scalafmt --quiet` on the 5 changed Scala files: exit 0.
- `sbt -batch entityResolution/test`, once: Passed 119, Failed 0.
- `sbt -batch check`, once. The harness moved it to the background at 180 s. I started nothing else and read the result after the completion notice: exit 0, total time 312 s.
- `git status --short` and `git diff` on my territory.
- My first summary command was blocked by guard-dangerous because it named the test-marker file (the known Phase 11 false positive). I reran it without that path. I never ran the marker task by name.

TEST RESULTS:
- `sbt entityResolution/test`: Passed: Total 119, Failed 0, Errors 0, Passed 119. That is 112 before plus 7 new. AdjudicationSuite still has 12 tests; one was replaced and extended.
- New BedrockAdjudicatorSuite, 7 tests:
  1. The request is a SigV4-signed InvokeModel POST:
     - path `/model/anthropic.claude-test-v1%3A0/invoke` and the exact body keys and values;
     - x-amz-date 20261123T120000Z from a fixed clock;
     - Authorization prefix `AWS4-HMAC-SHA256 Credential=AKIDTEST/20261123/us-east-2/bedrock/aws4_request, SignedHeaders=accept;content-type;host;x-amz-date, Signature=`;
     - the signature recomputed with core SigV4 over the URL, headers and body the stub received;
     - no secret in the headers, the body or toString.
  2. A valid reply is Answered:
     - the inputJson is pinned by hand and the inputHash `a18d3bc9...` and cache key `8558b5c7...` come from sha256sum, not from the code;
     - the second call is a cache hit with no HTTP call.
  3. Malformed replies, 21 cases: 9 envelope cases give LLM_CALL_FAILED and 12 text cases give LLM_INVALID_OUTPUT, including both new-identifier cases. The cache size is 0 after every case.
  4. HTTP errors, a 200 ms timeout against a 2 s stub delay, a refused connection to 127.0.0.1:1, and a missing or throwing credentials provider: all are Left or LLM_CALL_FAILED and nothing throws. The detail never holds the error body or the secret, and no unsigned request is sent.
  5. Model and region come from config:
     - an eu-west-1 override changes the endpoint host and the signed scope;
     - llmEnabled false, an empty model, Nova, a non-Anthropic ID and maxTokens 0 are refused;
     - a `us.anthropic.` profile ID is accepted;
     - building an adapter makes no call.
  6. er-v1 is unchanged:
     - policy version er-v1, thresholds (0.9, 0.35, 0.7, 0.9) and the policy resource sha256 82ce9ae5... are pinned;
     - over all fixture candidates, every non-band decision equals the off-mode decision;
     - band decisions differ only in their last reason code (LLM_CALL_FAILED instead of LLM_OFF) and stay UNRESOLVED;
     - HTTP calls equal the number of band cases.
  7. A Bedrock ACCEPT moves the band case to ACCEPTED / LLM_ADJUDICATED, with the model ID as its model version, audit mode BEDROCK, and a valid core decision.
- `sbt check`: exit 0, `[success] Total time: 312 s (0:05:12.0)`, 67 suite runs finished. Per-project totals as printed, in order:
  - `Passed: Total 153, Failed 0, Errors 0, Passed 153, Ignored 1`
  - `Passed: Total 115, Failed 0, Errors 0, Passed 115`
  - `Passed: Total 68, Failed 0, Errors 0, Passed 68`
  - `Passed: Total 119, Failed 0, Errors 0, Passed 119`
  - `Passed: Total 98, Failed 0, Errors 0, Passed 98`
  - `Passed: Total 33, Failed 0, Errors 0, Passed 33`
- Mapping those totals to modules (sbt interleaves projects, so this is inferred from the suite names and earlier reports):
  - storage 153 with 1 ignored (only storage suites precede that line; the ignored one is presumably the gated AWS integration suite);
  - core 115 (matches step 00);
  - adversarial 68 (matches Phase 05);
  - entityResolution 119 (matches my run);
  - hadoop 98 (matches Phase 05);
  - spark 33 (Spark suites precede it).
  - Full output: /tmp/claude-1000/-home-ajcreator-scholarchain/159da75f-8493-4571-ac6a-39a4acaed45d/scratchpad/sbt-check.txt

VALIDATION RESULTS:
- Policy er-v1 is unchanged:
  - resource sha256 82ce9ae5c81d998bb29fe367c4e8a824a7392acc39cd3764fb9d64a7316ce1ff, the same in HEAD and in the worktree;
  - thresholds accept 0.9, reject 0.35, LLM band [0.7, 0.9).
- Prompt er-adjudication-v1 is unchanged: sha256 fbcf4b136e72b9d1edede0060be0d4543f2459a83c0e119252284cd2ca291ac5.
- Model version under Bedrock is the configured model ID. Tests used `anthropic.claude-test-v1:0` only, through the config placeholder, not a code literal.
- Cache key formula is unchanged: sha256(inputHash|promptVersion|modelVersion|policyVersion), pinned independently.
- Gold-set evaluation was not rerun. No matching logic, threshold, feature or policy changed, and the tests show the non-band decisions are identical to off mode. So the Phase 05 numbers stand: researcher P 0.938 (understated, human decision 04), R 0.987, F1 0.962; work P 1.000, R 0.947, F1 0.973; unresolved rate 0.0144 on run-02cecabcf3e68312.
- The LLM_ADJUDICATED path has no gold-set measurement: no Bedrock run exists.
- LeakageSuite passes. The new main file names no gold term, and no label is read.
- No AWS call, no real credentials, and nothing beyond 127.0.0.1.

ASSUMPTIONS:
- Core config has no dedicated adjudication model field, so the adapter uses `models.synthesisModel` (`${SYNTHESIS_MODEL_ID}`). I chose this instead of reporting BLOCKED-ON domain-engineer, given the deadline.
- InvokeModel bodies are model-specific, so only the Anthropic Messages format is supported and other families are refused at construction. An application-inference-profile ARN without `anthropic.` in it is also refused.
- The regional host pattern `bedrock-runtime.<region>.amazonaws.com` is built from the configured region. This is the same approach as storage's BedrockEmbedder, and I read it as the documented service endpoint template, not a "literal endpoint". No account ID appears anywhere.
- An envelope problem is a call failure (LLM_CALL_FAILED); only the model's text goes to the validator (LLM_INVALID_OUTPUT). Both leave the case UNRESOLVED.
- maxTokens 512 and temperature 0 are adapter parameters, not policy, so er-policy-v1.json and its hash are unchanged.

RISKS:
- Not done on purpose:
  - The match job does not wire Bedrock. `--adjudicator bedrock` and `models.llmEnabled=true` are still refused with exit codes 2 and 3; both are tested in MatchJobSuite.
  - Wiring it needs credentials in the task JVMs, a per-task budget above the default `ER_MAX_LLM_CALLS=0`, and the human's cost sign-off. That belongs to a cloud phase.
  - No Converse API support. No non-Anthropic body. No DynamoDB adjudication cache: the Phase 05 known issue stands, and the DynamoDB cache must keep `Adjudicator.recheck` on every hit.
- Retries come from the injected AwsHttp (default maxAttempts 1). With retries enabled, one budget unit can cover several HTTP attempts.
- Duplicate JSON keys in a reply still resolve last-one-wins (Phase 05 known issue, core decoder).
- State left behind: 3 modified and 2 new files in my territory, all uncommitted. Nothing else was touched.
- Notes for modules/entity-resolution/MODULE.md (documentation-engineer):
  - Public API: add `BedrockAdjudicator` (above), `AdjudicatorMode.BEDROCK`, `AdjudicatorMode.BedrockNotInJob` and `MatchJob.portFor`.
  - Done: the Bedrock adapter, tested against the local stub; entityResolution tests 119.
  - Not done: replace "Any Bedrock adjudicator (Phase 07)" with "Bedrock wiring in MatchJob, and any live Bedrock run".
  - Known issues: the model ID is reused from `models.synthesisModel`; only the Anthropic body is supported; a budget unit equals one port call, not one HTTP attempt.

MANUAL ACTION REQUIRED:
- Human: decide whether adjudication keeps sharing `models.synthesisModel` or gets its own config field. A new field is a domain-engineer change. This is not blocking.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: step 05 documentation-engineer (docs/service-limits.md, the storage section, modules/storage/MODULE.md), plus the entity-resolution MODULE.md notes above. Then a fresh code-reviewer review of Phase 07. This report's sbt check is the phase's single full check.
