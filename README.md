# ScholarChain

<!-- Owner: documentation-engineer. Grows phase by phase; the deliverable index fills in
as each package lands. -->

**Student:** YOUR UNIVERSITY-REGISTERED NAME (YOUR UNIVERSITY EMAIL)

ScholarChain answers collaboration questions about computer-science researchers with
evidence-backed claims. Hadoop MapReduce and Spark on EMR process DBLP plus OpenAlex,
ORCID and Crossref; entity resolution links researchers across sources; the coauthorship
graph lives in Neptune, run state in DynamoDB and semantic context in S3 Vectors; a
Graph-RAG workflow on Bedrock plans, retrieves, synthesizes and verifies each answer.
The full assignment is in [docs/spec/assignment.md](docs/spec/assignment.md).

## Architecture summary

- Build: one sbt multi-project build (Scala 3.3 LTS, Java 17 bytecode, uPickle only).
- Pipeline: Hadoop jobs, entity resolution and Spark graph analytics, exchanging
  versioned `core` contracts on S3, orchestrated by one Step Functions workflow.
- Services: three http4s Ember deployables on ECS Fargate (`query-service`,
  `graph-service`, `embedding-worker`) plus a shared `servicesCommon` library.
  Ingress is an API Gateway HTTP API with a VPC Link to Cloud Map; callers authenticate
  with a Cognito JWT. Metrics are CloudWatch Embedded Metric Format logs.
- Infrastructure: layered Terraform stacks in `infra/stacks/` (foundation, data,
  compute, app, observability), each with its own state, linked through SSM parameters.

## Agentic workflow summary

The repository is built by 19 role agents under the ownership map in
[docs/agents.md](docs/agents.md), with constitutional rules in [AGENTS.md](AGENTS.md) and
[CLAUDE.md](CLAUDE.md). Roles live once in `.agents/roles/` and are rendered to
`.claude/agents/` and `.codex/agents/` by `.agents/render.sh`. Hooks block destructive
cloud and git commands, enforce one writer per file, and refuse to finish a session with
stale tests. Phase evidence lives under [docs/agent-runs/](docs/agent-runs/README.md).

## Required local software

- JDK 17 (Corretto, installed by `toolchain/install.sh`), sbt 1.13.1, scalafmt 3.11.5
- Python 3.11+, curl, git, Docker (for `sbt dockerLocal`), Terraform, AWS CLI v2
- Claude Code; `uv`/`uvx` for the MCP servers

```bash
toolchain/install.sh
source toolchain/env.sh
git config core.hooksPath .agents/githooks
```

## Build commands

```bash
sbt fmt            # format
sbt check          # format check, compile, all tests, then the test marker
sbt packageJobs    # EMR job jars into target/jobs/
sbt dockerLocal    # the three service images, tagged scholarchain-<service>:local
```

## Control-plane checks

```bash
.claude/hooks/validate-agent-files.sh
.agents/render.sh --check
.agents/routing-suite.sh
```

## Status

Phases 00 and 01 (seed, agent system) are committed and Phase 02 (build skeleton) is in
progress; see
[STATUS.md](STATUS.md). Later sections (AWS prerequisites, deployment, API examples,
experiments, cleanup, deliverable index) are added by the phase that delivers them.
