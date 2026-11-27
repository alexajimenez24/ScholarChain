# Phase 13 human gate

## How Phase 13 was produced

The owning agents did the work through the orchestrator, one owner at a time:
service-engineer (a runnable main for each of the three services, and the wiring that lets
queryService use the committed rag Bedrock synthesis adapter when `LLM_ENABLED` is true),
storage-engineer (command-line entry points for the cloud pipeline), rag-engineer (one
missing piece those entry points needed), infra-engineer (A1: the ten scripts; A2: the
clean-checkout check, packaging and the dry runs), build-engineer (test concurrency and
packaging), documentation-engineer (STATUS.md) and fresh code-reviewers. The work order and
my addendum are in `prompt.md`; every step is in `run-log.md`, and the reports and outputs
are in this folder. There was no reference material in this phase. No cloud was used: no
AWS call, plan, apply or deploy.

## What was checked

- Services: each of the three services has a runnable main (configuration from the
  environment, server start, health and readiness). queryService can use Bedrock synthesis
  when `LLM_ENABLED` is true; it stays false by default. The synthesis prompt and schema
  files are packaged with the queryService image, with a build check in sbt check that
  they are included.
- Storage entry points the pipeline runs: writing the Neptune bulk-load files from the
  Spark export to S3 and starting the Neptune bulk loader, the DynamoDB and S3 Vectors
  loaders, and enqueuing the embedding jobs. Each has a dry-run mode, reads names from
  configuration and is tested on fakes. storage-engineer also fixed a load ledger that
  could outgrow DynamoDB's item size limit.
- Scripts: the six Phase 12 scripts are finalized, and check.sh, package.sh,
  run-experiment.sh and archive-evidence.sh are added, with strict mode, timeouts on cloud
  calls, step markers, explicit exit codes, required environment variables listed by name,
  and the account ID redacted from AWS CLI and docker error output. plan.sh's placeholder
  check now ignores comment lines. The ECS tasks have a justified egress path to ECR,
  CloudWatch Logs and Bedrock. The Step Functions pipeline runs the packaged Hadoop,
  entity-resolution and Spark jobs and the storage loaders. run-bounded-pipeline.sh uploads
  the selected dataset snapshot to the raw prefix. The EMR helper refreshes its temporary
  credentials (checked in the code, not yet run on EMR).
- Clean checkout: a copy of the tracked and new unignored files, outside the repository,
  ran `scripts/check.sh` and passed all seven gates (`output-check-clean.txt`). 157 tests
  were skipped there, because the copy has no git-ignored local data snapshot, so the
  benchmark suites skip by design.
- The first clean-copy run failed two time-limited tests under load on this 5 GB machine
  (GraphWiringSuite readiness and a rag pipeline stage timeout); both passed when run
  alone. build-engineer limited sbt check to one test suite at a time, which fixed it.
- Packaging: `scripts/package.sh` built 6 jars and the 3 images (`output-package.txt`),
  tagged `-dirty` because nothing was committed. They are rebuilt at the clean Phase 14
  commit so the image tags are the Git SHA.
- Dry runs: deploy, smoke test, bounded pipeline, experiment and archive each named what
  they would do, and the ones needing approval refused. I ran the cleanup dry run myself,
  because the guard blocks agents from it: it listed what it would delete and exited with
  code 3 without calling AWS. I also ran bash -n and shellcheck on `scripts/cleanup.sh`,
  which agents could not check.
- Cost estimate for the cloud window: $6.85 typical, $13.43 at most, under the $15 window
  limit (Phase 12 prices reused).
- The final full sbt check in the repository exited 0: 1139 tests passed, none failed, and
  the gated AWS suite was ignored.

## Reviews and repairs

- Review 1 (`review-report.md`): one MAJOR finding. In AWS, the embedding worker could not
  save its load report, so it would never acknowledge a queue message. It was fixed with a
  scoped permission on the `runtime-worker` role (`s3:PutObject` and `s3:GetObject` on
  `curated/logs/*` only), and review 2 approved. I approve that permission change.
- After review 2, I authorized two further small rounds, disclosed exceptions to the
  one-round rule, because each remaining gap could leave resources billing:
  - Round A: redact the account ID from error output (met); refresh the EMR helper's
    credentials (met in code); make the pipeline and experiment scripts always run the
    cluster check and scale ECS to zero on every exit path (not met at first: a closed
    output reader could stop cleanup partway).
  - Round B, declared the final round for these scripts: cleanup now survives a closed
    output reader (met); `finish` cleans up when its early AWS calls fail (met for single
    failures); each service scales down independently (met); a stop signal ends the
    running command and then cleans up (not met).
- I accepted two behaviour choices: the scripts scale ECS to zero after a successful start
  unless `--keep-services` is given, and `finish` refuses to scale down while the pipeline
  is still running.

## Decisions and accepted residuals

- Open (MAJOR): a second stop signal that arrives while the first is being handled can end
  the script before the cluster check and the ECS scale-down, without a warning. The
  reviewer reproduced it in 4 of 15 runs with stand-in AWS commands. I keep the final-round
  limit and accept it with this workaround: I run `run-bounded-pipeline.sh` and
  `run-experiment.sh` without an outer `timeout` (an exception to the timeout rule; they
  have their own internal limits), I never send a second interrupt, and after any
  interrupted run, and at the end of every cloud window, I check EMR and ECS by hand
  (`aws emr list-clusters --active`, and the ECS services' desired counts).
- Open (MINOR): if the one lookup of the cluster name fails, all three scale-downs are
  skipped, with a failure warning printed. My manual check covers it.
- Gate items: a clean checkout runs all non-cloud validation through `scripts/check.sh`
  (met); deployment cannot proceed without my approval (met: deploy.sh requires the signed
  plan lines, a clean tree and a matching commit); planning reproducibility and the real
  bounded smoke query are shown in Phase 14, when the first plans are made and the
  services run. Code review: approved for everything except the accepted signal-handling
  residual.

## Accepted, with known gaps

Phase 13 is accepted as committed. Carried forward:
- The second-signal race and the cluster-lookup minor above (infra-engineer, after the
  deadline; Phase 17 limitations).
- Query data loading uses a temporary stand-in whose memory use is not yet measured; the
  Phase 14 smoke test checks task memory.
- No service or image has been started with `LLM_ENABLED=true`, and the credential refresh
  has not run on EMR; both are checked in Phase 14 and 15.
- The other minor findings in the review reports (Phase 17 limitations).

## Process events

- test-engineer ran a full sbt check where only the affected suite was asked for, saying
  the stop hook required it.
- The orchestrator lightly edited some agent reports when saving them (formatting, paths,
  wording, one added line of script commands and a note about copying output); it states
  that no result or finding was changed.
- The clean-copy check ran before the worker-permission Terraform fix; that fix separately
  passed terraform fmt and validate on the data stack.
- The orchestrator ran on Sonnet 5.5 rather than the high tier.

## Manual changes

- I ran the cleanup dry run, and bash -n and shellcheck on `scripts/cleanup.sh`.
- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 13 is complete. I approve starting Phase 14.
