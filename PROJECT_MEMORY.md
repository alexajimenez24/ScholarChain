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
| Config format | versioned `scholarchain-v1.yaml` in JSON-compatible YAML 1.2, parsed by uPickle | the work order asks for HOCON or YAML, and uPickle is the only JSON library (addendum) |
| Service config | three deployables plus shared Cognito JWT settings, all from `${VAR}` placeholders | no identity or endpoint value is written in the repository |
| Enrichment datasets | OpenAlex, Crossref and ORCID with DBLP; Semantic Scholar is not selected | the dataset manifest lists these four sources |
| Researcher ID | `orcid:` when the ORCID check digit is valid, else `sc:r:v1:` plus a hash of the sorted mention IDs; stable within a run and policy (mention-set hash), and an ORCID is the cross-run key | clock-free; an invalid ORCID never becomes an ID; the spec asks only for a versioned ScholarChain identity ID and needs no cross-run stability (review MINOR-5, repair round 1) |
| Decoding | strict and total: an unknown field, a JSON null or a lossy value gives a `Left`, never an exception or a silent truncation | a frozen contract changes only with a new schema version (iron law 5); consumers must not crash on bad lines (review MAJOR-1) |
| Dataset uploads and ORCID | no S3 upload path in `datasets/`; the ORCID fetch is opt-in (`WITH_ORCID=1`), off by default | uploads belong to infra-engineer and deploy-engineer; the ORCID API terms are UNVERIFIED (review MINOR-7, MINOR-8) |
| Config loader strictness | plain decimal numbers only, a round-trip check against the typed schema, and integers below 2^53 | a typed read would otherwise clamp or drop values silently (review 2 MINOR-1, repair round 2) |
| DBLP encoding | decode with the encoding declared in the XML prolog (ISO-8859-1 for DBLP, UTF-8 when none is declared) and reject bytes invalid in it; unsupported encodings refuse the whole file | human decision of 2026-11-23, verbatim: "the reader must decode with the encoding declared in the XML prolog (ISO-8859-1 for DBLP, UTF-8 when none is declared) and reject bytes that are invalid in the declared encoding"; implemented in Phase 04 repair round 1 |
| Phase 04 repair budget | one repair round only; findings still open after Review 2 carry forward to the human | human decision of 2026-11-23 |
| Dataset scale for the integrated run | 25,000 works, stepped down from 100,000 | the Spark stage failed at 100,000 (OOM after 23:04, then the 90-minute limit), so the human's step-down rule reran the chain at 25,000; 25,000 yields 223,953 source rows, above 10,000 (Phase 08) |
| Graph component bound | `graph.componentMaxIterations` 100 | components did not converge at 40 in 1950-2026 (observed maximum distance 39); 100 is about 2.1 times the 47 iterations observed in 1950-2026 (00d) (Phase 08 step 00c) |
| Path pair rule | `spark-v4`: targets from each source's reach within 6 hops, at most 112 pairs per window | the old rule paired the sources with one another and found no path, leaving collaboration-path-v1 empty (Phase 08 step 00d) |
| Renderer port | `DocumentRenderer` defined in storage, implemented by `SemanticRenderer` in rag | rag depends on storage, so storage cannot import rag; storage takes text only from the renderer, so one key has one text (Phase 08) |
| Abstract template | `tpl-paper-abstract-v2`, no work ID in the text | storage refuses text that repeats a metadata value of 40 or more characters; 71 of 6,292 work IDs are that long (Phase 08 step 01b) |
| Embedding work queue | manifest-driven batch with a bounded in-process queue, not SQS | no cloud this phase, every step is idempotent, and an SQS stack belongs to infra-engineer; `WorkQueue` is the seam for a Phase 10 SQS adapter (Phase 08 step 02a) |
| Embedding version | `<modelId>@<dimension>`, locally `hash-embed-v1@256`; a change means a new index version | one index holds one embedding version, and a changed model never overwrites vectors (Phase 08) |
| Semantic context | `semantic-context-v1`, untrusted data, `score` the only number; graph values only from graph evidence | vector similarity must never supply a count, path, component or bridge value (Phase 08 step 03) |
| Region and budget | us-east-2, $25 hard cap | course limits |

## Plan

Phases follow `docs/spec/assignment.md`: 03 contracts and datasets, 04 Hadoop, 05 entity
resolution, 06 Spark, 07 and 08 storage and embeddings, 08 and 09 RAG, 10 services, 11
black-box tests, 12 and 13 Terraform and automation, 14 deploy, 15 and 16 experiments and
evaluation, 17 documentation and video.
