# PROJECT_MEMORY

<!-- Owner: documentation-engineer. Plan and decisions with their reasons; status lives
in STATUS.md. -->

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Language and build | Scala 3.3 LTS, sbt 1.13.1, Java 17 bytecode | LTS keeps the Scala 2.13 library ABI Spark needs; EMR and Corretto run Java 17 |
| JSON | uPickle only | one codec everywhere; services convert text bodies with uPickle, no http4s-circe |
| Spark line | Spark 4.0.1 (Scala 2.13 artifacts); runtime target `emr-spark-8.0.0` (human, 2026-11-22) | Spark 3.5 on EMR 7.x is Scala 2.12 and cannot load Scala 3 code; us-east-2 availability of the label is checked before the first cloud run |
| Service stack | http4s Ember 0.23.38 on cats-effect 3.7.1 | typed routes and middleware instead of a hand-rolled server; 0.23.35+ carries the Ember HTTP/2 CVE fixes |
| Service split | `servicesCommon` library plus three deployables: query, graph, embedding worker | each scales and deploys on its own signal; shared concerns live once |
| Ingress | API Gateway HTTP API, VPC Link, Cloud Map | no always-on load balancer under a $25 budget; JWT authorizer at the edge |
| Auth | Cognito user pool JWT, checked at API Gateway and again in `servicesCommon` | defense in depth; no long-lived API keys |
| Metrics | CloudWatch Embedded Metric Format | custom metrics without a scraper or sidecar |
| Autoscaling | query: step scaling on in-flight requests per task; worker: SQS backlog per task (metric math) | scales on the work signal, not CPU alone |
| Pipeline orchestration | one Step Functions workflow: Hadoop, entity resolution, Spark, Neptune bulk load | one place for retries, catches and run IDs |
| Terraform layout | layered stacks (foundation, data, compute, app, observability), outputs through SSM | each stack plans and applies alone; small blast radius |
| Harness | Claude Code only (Codex adapters kept, out of scope) | one harness to probe and evidence before the deadline (Phase 01 repair round 1) |
| Guard hardening | known guard gaps accepted in Phase 01, fixed in Phase 11 | the repair loop limit was reached; Phase 11 is the adversarial-testing phase |
| Region and budget | us-east-2, $25 hard cap | course limits |

## Plan

Phases follow `docs/spec/assignment.md`: 03 contracts and datasets, 04 Hadoop, 05 entity
resolution, 06 Spark, 07 and 08 storage and embeddings, 08 and 09 RAG, 10 services, 11
black-box tests, 12 and 13 Terraform and automation, 14 deploy, 15 and 16 experiments and
evaluation, 17 documentation and video.
