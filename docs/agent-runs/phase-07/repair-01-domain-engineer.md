HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier role default

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 07 repair round 1, task R1-2 (NIT-3 and NIT-4)

FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/core/src/main/scala/scholarchain/core/aws/Http.scala
- /home/ajcreator/scholarchain/modules/core/src/main/scala/scholarchain/core/aws/Credentials.scala
- /home/ajcreator/scholarchain/tests/module-unit/core/AwsSuite.scala

FINDINGS
- NIT-3, Http.scala:126-128. The IllegalArgumentException handler no longer uses e.getMessage. The error is now `invalid request: <METHOD> <scheme://host/path> (malformed URL)`. The text is cut at the first '?' (it reuses `shown`) and at '#'. Proving test: AwsHttpSuite "a malformed URL error carries no query string". It sends a URL with a space in the path and `?token=QUERY-SECRET-123`. It asserts the secret and `token=` are absent and the host is present.
- NIT-4, Credentials.scala:73-100 and the companion object at the end of the file. A new `checkFullUri` applies only to AWS_CONTAINER_CREDENTIALS_FULL_URI. The RELATIVE_URI path through `containerBase` is unchanged, so tests still point it at localhost. https is allowed. Plain http is allowed only for `localhost`, 127.x.x.x, `::1`, 169.254.170.2, 169.254.170.23 and `fd00:ec2::23`. The match is on the literal host, with no DNS lookup. Anything else, including an unparsable URI, a non-http(s) scheme, or `127.0.0.1.evil.example.com`, returns `Left("AWS_CONTAINER_CREDENTIALS_FULL_URI refused: must be https, or http to a loopback or ECS/EKS container host")`. The Left does not echo the URL, and no request is made. Proving tests, all in CredentialsSuite:
  - "full URI: http to loopback is allowed and the authorization token is sent" (localhost stub).
  - "full URI: http to an external host is refused and nothing is sent". A spy transport sees 0 requests for 5 refused URLs, and the Left does not echo a query secret.
  - "full URI: container hosts and https pass the host check". It covers the hosts above and an https URL, using a spy transport.

COMMANDS RUN (all succeeded)
- `sbt scalafmtAll`. It touched nothing outside my territory: git status shows no new modified files beyond the pre-existing ones.
- `sbt core/test`
- `sbt check`, run once, with output saved to /tmp/claude-1000/-home-ajcreator-scholarchain/159da75f-8493-4571-ac6a-39a4acaed45d/scratchpad/sbt-check-r1-2.txt

TEST RESULTS
- `sbt core/test`: "Passed: Total 119, Failed 0, Errors 0, Passed 119". The core count is 119. Per suite: AwsHttpSuite 10, CredentialsSuite 9, SigV4Suite 6, ConfigSuite 24, IdsSuite 12, NormalizeSuite 4, SerializationSuite 29, ValidateSuite 25.
- `sbt check` (rc=0, final "[success] Total time: 305 s", completed 10:57:15 PM). Every "Passed: Total" line as printed:
  - `Passed: Total 158, Failed 0, Errors 0, Passed 158, Ignored 1`
  - `Passed: Total 119, Failed 0, Errors 0, Passed 119`
  - `Passed: Total 68, Failed 0, Errors 0, Passed 68`
  - `Passed: Total 98, Failed 0, Errors 0, Passed 98`
  - `Passed: Total 33, Failed 0, Errors 0, Passed 33`
  - `Passed: Total 119, Failed 0, Errors 0, Passed 119`
  I did not map the lines to modules. The saved file has the context.

VALIDATION RESULTS
- No wire-format or schema change.
- Error text for a malformed URL: `invalid request: GET http://127.0.0.1:1/a b (malformed URL)`.
- Allowed http full-URI hosts are listed under NIT-4 above.

ASSUMPTIONS
- Literal host matching is enough, as in the AWS SDKs. A hostname that resolves to loopback, other than `localhost`, is refused.
- Hosts are lowercased, and IPv6 brackets are stripped before matching.

RISKS
- NIT-1 and NIT-6 were not touched, as instructed.
- For `modules/core/MODULE.md` (documentation-engineer): EnvironmentCredentials now refuses FULL_URI values that are not https or loopback/container-host http. Also, AwsHttp's `invalid request` error no longer includes the underlying exception message.
- Nothing else was left behind. The scratchpad file above is the only extra artifact.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh code-reviewer re-check of NIT-3 and NIT-4, then the phase 07 human gate.
