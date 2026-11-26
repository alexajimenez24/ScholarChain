# Phase 12 versions

- Harness: claude-code 2.1.332 (observed with `claude --version`).
- Models (`.agents/models.json`): high `opus`, effort high; standard `sonnet`, effort medium; low `haiku`, effort high.
- Orchestrator: main session, model id `claude-sonnet-5-5` (the matrix puts the orchestrator on the high tier; deviation to note at the gate).
- Tools, from the toolchain ledger and `step-01-toolchain-engineer-report.md`: Terraform 1.17.2, AWS CLI 2.35.21, trivy 0.76.2, shellcheck 0.11.0, terraform-mcp-server 1.4.1, uv 0.13.9, Python 3.12.3.
- Terraform provider: hashicorp/aws 6.74.0, exact pin, recorded in each stack's lock file.
- JDK, sbt and scalafmt match their pins (step 1 report). Scala and library versions are unchanged. No sbt run: no Scala changed.
- AWS: no account call, no plan, no apply. Public documentation and public price list files only.
