# Routing test corpus

<!-- Owner: factory-engineer (constitutional). Run .agents/routing-suite.sh under every
harness (ROUTER=claude and ROUTER=codex) after any description change, router model
upgrade or new agent. It asks the low tier in one batched call per harness; exit 75
means a usage limit stopped it, and it prints the --resume command. When a real request
misroutes, add it here first as a failing row, then repair the description, then re-run
to green. Never repair by naming agents in prompts. -->

Each row is one request and the one agent that must win it.
Positive rows: each agent wins its own work, five per agent.
Hard negatives: the vocabulary belongs to one agent and the work to another, three per
agent.

## Positive rows

| Request | Expected agent |
|---|---|
| Add a new agent role for an OpenSearch engineer | factory-engineer |
| Add a guard pattern that blocks aws glue delete-database | factory-engineer |
| Change the model tier of hadoop-engineer to high | factory-engineer |
| Register a new MCP server for CloudWatch log reads | factory-engineer |
| Fix a misroute by rewording the rag-engineer description | factory-engineer |
| Create build.sbt with the seven ScholarChain sbt projects | build-engineer |
| Add the check, fmt, packageJobs and dockerLocal aliases | build-engineer |
| Make Spark a Provided dependency of sparkAnalytics | build-engineer |
| Add sbt-native-packager and a non-root Docker image | build-engineer |
| Compile tests/module-unit/spark as sparkAnalytics tests | build-engineer |
| Bump the AWS SDK v2 library to its latest patch | dependency-updater |
| A CVE was published for the logback version we use | dependency-updater |
| Upgrade ScalaTest to the newest minor release | dependency-updater |
| Check our library versions for security advisories | dependency-updater |
| Bump the uPickle version in project/Versions.scala | dependency-updater |
| Install the scalafmt CLI so the format hook works | toolchain-engineer |
| Pin the Terraform CLI version and record why | toolchain-engineer |
| Write a no-sudo install script for the JDK and sbt | toolchain-engineer |
| Install uv so the uvx MCP servers can start | toolchain-engineer |
| Install terraform-mcp-server 1.4.1 for the MCP config | toolchain-engineer |
| Add a schema version to the CoauthorEdge record | domain-engineer |
| Define the stable researcher ID function | domain-engineer |
| Freeze the JSON wire example for EvidenceBundle | domain-engineer |
| Reject time windows whose start year is after the end | domain-engineer |
| Create the configuration loader for bucket names | domain-engineer |
| Create datasets/dataset-manifest.yaml for DBLP | dataset-engineer |
| Record the OpenAlex snapshot date, license and checksum | dataset-engineer |
| Write a bounded sample script for the ORCID data file | dataset-engineer |
| Is the Crossref public data file still downloadable? | dataset-engineer |
| Add a small legal DBLP sample under datasets/samples | dataset-engineer |
| Write the DBLP XML input format that keeps records whole | hadoop-engineer |
| Emit coauthor pair candidates from publication teams | hadoop-engineer |
| Add Hadoop counters for rejected and oversize records | hadoop-engineer |
| Implement the OpenAlex normalizer as a MapReduce job | hadoop-engineer |
| Add a partitioner that splits giant blocking keys | hadoop-engineer |
| Tune the accept and reject thresholds for author matching | entity-resolution-engineer |
| Output CONFLICT when two ORCID identifiers disagree | entity-resolution-engineer |
| Write the LLM adjudication prompt with an abstain option | entity-resolution-engineer |
| Calibrate the match scoring policy on the gold set | entity-resolution-engineer |
| Normalize author initials and name order for matching | entity-resolution-engineer |
| Compute distinct coauthor counts per researcher | spark-engineer |
| Add k-core decomposition to the collaboration graph | spark-engineer |
| Find connected components without collecting the graph | spark-engineer |
| Implement label propagation for research communities | spark-engineer |
| Export Parquet edges and the Neptune load manifest | spark-engineer |
| Write the Neptune bulk loader for COAUTHORED_WITH edges | storage-engineer |
| Add conditional writes to the RunLedger DynamoDB table | storage-engineer |
| Batch vector writes into the researcher-profile-v1 index | storage-engineer |
| Skip re-embedding unchanged text by content hash | storage-engineer |
| Design the DynamoDB keys for the EvidenceBundle table | storage-engineer |
| Build the QueryPlannerAgent with allowlisted operations | rag-engineer |
| Implement the claim verifier for numerical claims | rag-engineer |
| Version the answer synthesis prompt in prompts/ | rag-engineer |
| Fuse graph and vector retrieval into one evidence bundle | rag-engineer |
| Make the resolver abstain on ambiguous researcher names | rag-engineer |
| Add GET /v1/evidence/{evidenceId} with trace IDs | service-engineer |
| Handle Idempotency-Key on POST /v1/query | service-engineer |
| Separate /healthz from /readyz in the query service | service-engineer |
| Paginate GET /v1/graph/nodes/{nodeId}/neighbors | service-engineer |
| Map downstream timeouts to the typed API error model | service-engineer |
| Write adversarial tests for truncated DBLP XML | test-engineer |
| Add an end-to-end test from query to evidence with fakes | test-engineer |
| Test prompt injection hidden in paper abstracts | test-engineer |
| Write cross-module contract tests for Spark to Neptune IDs | test-engineer |
| Rerun the adversarial suite after the hadoop repair | test-engineer |
| Review the phase 6 diff before I commit it | code-reviewer |
| Is this failing test-engineer test fair against the spec? | code-reviewer |
| Check this diff for ownership violations and secrets | code-reviewer |
| Write a repair brief for the failing pair-count test | code-reviewer |
| Do a final read-only review of the whole repository | code-reviewer |
| Write Terraform for the S3 Vectors bucket and indexes | infra-engineer |
| Create scripts/deploy.sh that checks the plan SHA-256 | infra-engineer |
| Add EMR managed scaling with bounded capacity | infra-engineer |
| Write least-privilege IAM roles for the ECS tasks | infra-engineer |
| Run terraform plan for the dev environment | infra-engineer |
| Deploy the approved commit to dev | deploy-engineer |
| Roll back the query service to the previous revision | deploy-engineer |
| Run the bounded pipeline on EMR for the sample snapshot | deploy-engineer |
| Ship the approved release and record it in releases/ | deploy-engineer |
| Apply the saved plan whose SHA-256 I signed | deploy-engineer |
| Run the query load experiment at 1, 10, 50 and 100 clients | experiment-engineer |
| Write the manifest for the EMR burst experiment | experiment-engineer |
| Measure SQS backlog drain time for embedding workers | experiment-engineer |
| Compare graph-only, vector-only and fused retrieval | experiment-engineer |
| Plot p95 latency from the raw load-test results | experiment-engineer |
| Write docs/data-lineage.md from source to answer | documentation-engineer |
| Update STATUS.md after the phase 4 hadoop step | documentation-engineer |
| Write the threat model covering prompt injection | documentation-engineer |
| Update modules/spark/MODULE.md with the new API | documentation-engineer |
| Write reports/final-report.md from the evidence | documentation-engineer |
| Write the handoff note into STATUS.md before the cut-off | documentation-engineer |
| CloudWatch says the query service 5xx rate spiked | incident-responder |
| ECS tasks keep restarting after the deploy | incident-responder |
| The embedding dead-letter queue is filling up | incident-responder |
| Neptune queries are timing out in production | incident-responder |
| Diagnose why the smoke test failed after the deploy | incident-responder |

## Hard negatives

| Request | Expected agent |
|---|---|
| Write the architecture overview in docs/architecture.md | documentation-engineer |
| Add a CI workflow that runs the agent validation script | infra-engineer |
| Pin the Codex CLI version in the toolchain ledger | toolchain-engineer |
| Bump uPickle to the next patch in Versions.scala | dependency-updater |
| Install sbt and a JDK 21 in user space | toolchain-engineer |
| Write the Terraform module for the ECR repositories | infra-engineer |
| Add a new JSON schema validation library to the build | build-engineer |
| Update the pinned Terraform CLI on this machine | toolchain-engineer |
| Upgrade the sbt version in project/build.properties | build-engineer |
| Pin the scalafmt version in .scalafmt.conf | build-engineer |
| Bump the Hadoop client library version | dependency-updater |
| Add a Terraform AWS provider version constraint | infra-engineer |
| Write the dataset schema notes for OpenAlex works | dataset-engineer |
| Design the DynamoDB key schema for QuerySession | storage-engineer |
| Write adversarial tests that attack WorkRecord decoding | test-engineer |
| Parse the DBLP XML records in a MapReduce job | hadoop-engineer |
| Decide the match threshold for ORCID-linked authors | entity-resolution-engineer |
| Create the S3 raw-zone bucket for DBLP snapshots | infra-engineer |
| Write the bounded EMR smoke-run script for Hadoop | infra-engineer |
| Aggregate pair candidates into weighted coauthor edges | spark-engineer |
| Run the Hadoop jobs on EMR with the sample snapshot | deploy-engineer |
| Emit blocking keys for common author names in MapReduce | hadoop-engineer |
| Resolve a user-typed researcher name at query time | rag-engineer |
| Run the final evaluation of entity-match quality | experiment-engineer |
| Bump the Spark library to the next patch release | dependency-updater |
| Load the Spark edge Parquet files into Neptune | storage-engineer |
| Write the shortest-path Neptune query for PathFinder | rag-engineer |
| Create the DynamoDB tables in Terraform | infra-engineer |
| Write the researcher profile text template for embedding | rag-engineer |
| Test S3 Vectors throttling with adversarial cases | test-engineer |
| Expose POST /v1/query as an HTTP route | service-engineer |
| Write the entity-resolution adjudication prompt | entity-resolution-engineer |
| Load the embeddings into the S3 Vectors indexes | storage-engineer |
| Write API attack tests for malformed bodies and auth | test-engineer |
| Create the API Gateway HTTP API, VPC link and ECS services | infra-engineer |
| Deploy the query service image to the dev environment | deploy-engineer |
| Add a unit test for the pair generator in modules/hadoop | hadoop-engineer |
| Write the smoke-test script for the deployed services | infra-engineer |
| Run the smoke test against dev after the deploy | deploy-engineer |
| Fix the defect the reviewer found in the Spark job | spark-engineer |
| Fix the reviewer finding in the match thresholds | entity-resolution-engineer |
| Check the dataset licenses at the source and record them | dataset-engineer |
| Apply the approved plan to the dev environment | deploy-engineer |
| A CloudWatch alarm fired for the query service | incident-responder |
| Install the Terraform CLI on this machine | toolchain-engineer |
| Fix the deploy script so it refuses dirty trees | infra-engineer |
| The deploy failed, find out why the tasks crash | incident-responder |
| Write the rollback script for the ECS services | infra-engineer |
| Write the evaluation section of the final report | documentation-engineer |
| Tune the fusion weights to improve the benchmark | rag-engineer |
| Write Terraform autoscaling for the embedding workers | infra-engineer |
| Change the ownership map in docs/agents.md | factory-engineer |
| Write the incident report for the Neptune timeout | incident-responder |
| Add Scaladoc comments to the storage adapters | storage-engineer |
| Add a CloudWatch alarm for SQS queue age in Terraform | infra-engineer |
| Roll back the deploy to the previous revision | deploy-engineer |
| Run the failure-recovery experiment with a poison message | experiment-engineer |

## Polysemy registry

| Word | Bare form owned by | Others must qualify it as |
|---|---|---|
| build | build-engineer | CI workflow (infra-engineer) |
| version | dependency-updater | tool pin (toolchain-engineer); sbt or plugin (build) |
| update, upgrade, bump | dependency-updater | update notes (documentation-engineer) |
| install | toolchain-engineer | - |
| deploy | deploy-engineer | deploy script as authored code (infra-engineer) |
| rollback | deploy-engineer | rollback script (infra-engineer) |
| test | test-engineer | module unit test (module owner); smoke script (infra) |
| review | code-reviewer | license check at the source (dataset-engineer) |
| schema | domain-engineer | dataset notes (dataset); table keys (storage) |
| prompt | rag-engineer | adjudication prompt (entity-resolution-engineer) |
| resolver | rag-engineer | batch matching (entity-resolution-engineer) |
| experiment, benchmark | experiment-engineer | - |
| alarm, incident | incident-responder | alarm resource in Terraform (infra-engineer) |
| pipeline run | deploy-engineer | pipeline code (hadoop, spark); CI (infra) |
