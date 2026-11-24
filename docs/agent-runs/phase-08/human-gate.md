# Phase 08 human gate

## How Phase 08 was produced

The owning agents did the work through the orchestrator, one owner at a time. The work
order and my addendum are in `prompt.md`. Every step, model and time is in `run-log.md`,
and every agent report (39) is saved word for word in this folder (also gathered in
`phase-report-agent-reports.md`). Tool versions are in `versions.md`.

As in earlier phases, I gave the agents reference material from the collaborative
ScholarChain repository, unpacked outside this repository in `~/ref-phase-08/`.

My addendum added a dataset scale-up before the embedding work (steps S1 to S3), the
spark-engineer step 0 decided at my Phase 07 gate (JSON-lines export, `institutionId`,
paper-abstract rows, every coauthor window in `neptune.json`), and a step-down rule for
memory or time failures. The phase ran unattended overnight.

I first started this phase with an earlier version of the work order that had no
scale-up steps. I stopped it a few minutes in, during spark-engineer's step 0, set its
changes aside with `git stash`, and restarted with the current `prompt.md`. Nothing from
the aborted start is in this commit.

## Dataset scale and where it hit the wall

- S1 (dataset-engineer): a 100,000-work DBLP snapshot, 4,000 works per year from 2000 to
  2024, chosen by a deterministic hash rule rather than the first records in file order;
  Crossref 4,516 found (capped at 5,000 DOIs); OpenAlex PARTIAL, 50,000 of 90,285 DOIs,
  because the free API's daily request limit was reached (HTTP 429).
- At 100,000 works, Hadoop (run `run-834f71c82961a194`, 570 s) and er-v1 matching (run
  `run-85fd79957d197ffc`, 4,561,097 decisions in 1:21:47) both completed.
- Spark analytics at 100,000 works did not: attempt 1 ran out of memory after 23 minutes,
  and attempt 2, at 32 shuffle partitions, was stopped at the 90-minute limit.
- Under the step-down rule, the whole chain reran at 25,000 works, cut from the same
  snapshot with no new download: OpenAlex 12,433 records with 6,292 abstracts, Crossref
  1,137. Hadoop run `run-03a81f6f54f3fc3c` (187 s), er-v1 run `run-cb5a0421c406737d`
  (903,286 decisions in 1:19:48), Spark run `run-ccf619c509611f06`.
- The limiting factors are the 5 GB of memory available to WSL on this machine, the $25
  AWS budget and the 2026-11-25 deadline. The integrated run is therefore at 25,000
  works, below the assignment's recommended 100,000 works. I have not obtained
  instructor approval for the reduced scale; this section is the written explanation of
  the limiting factor, with every failed attempt recorded in `run-log.md`.
- OpenAlex coverage is partial, so topic, institution and abstract context exists for
  only part of the corpus.
- Phases 04 to 07 were gated on the committed small sample. Their code is unchanged by the
  scale-up; the larger runs are recorded here.

## What was checked

- Spark run `run-ccf619c509611f06` SUCCEEDED: 114 invariants checked, all 0. It needed two
  repairs before ingestion. First, `componentMaxIterations` went from 40 to 100
  (domain-engineer), because components had not converged in the 1950-2026 window; it now
  gives 15,830 components with a largest of 8,615, which spark-engineer's offline
  union-find check matches exactly. Second, path targets are now chosen from each
  source's reachable set, so `collaboration-path-v1` has 278 documents instead of 0.
- rag: five versioned rendering templates (the abstract template is
  `tpl-paper-abstract-v2`, because v1 repeated the work ID in the text), the chunking
  policy `chunk-v1`, the embedding version rule `<modelId>@<dimension>`, and the
  semantic-context format `semantic-context-v1`, whose text is fetched only through the
  S3 pointer. Retrieval smoke tests pass on a hand-built fixture, and tests show that no
  count, path, component or bridge value comes from vector similarity.
- storage: text is rendered only by rag, behind the `DocumentRenderer` port that storage
  defines. The embedding queue is a manifest-driven batch with a bounded in-memory queue,
  not SQS. Batches hold 100 vectors. A chunk is skipped only when its text hash and model
  version both match, one vector key for two different texts is refused, and failures and
  retries go to RunLedger. Local ingestion now fails before loading when the export does
  not match its load manifest.
- Local ingestion (`output-local-ingestion.md`), with the deterministic local embedder
  `hash-embed-v1@256` and no AWS call: 223,953 meaningful source rows exist; a
  deterministic subset of 28,527 rows (8,000 per document type, all rows for the smaller
  types) was ingested as 31,341 chunks into all five indexes, with 0 rejections, about
  584 vectors per second and a heap peak of 351 MB. The full set does not fit in a 2 GB
  heap. A second identical pass skipped every chunk.
- Not run in this phase, because no cloud is used: the bounded AWS ingestion of 10,000
  vectors, AWS query latency and real cost. Prices in `docs/embeddings.md` are marked
  UNVERIFIED.
- Full `sbt check` exited 0 twice: at the end of step 03 (362 s) and after the repair
  round (356 s). Final counts include core 120, rag 66, storage 184 (1 ignored, the gated
  AWS suite) and Spark 45 tests.
- Review 1 (`review-report.md`) returned APPROVE WITH CARRIED-FORWARD ITEMS: 0 CRITICAL,
  0 MAJOR, 5 MINOR, 6 NIT. One repair round (`repair-01*.md`, routing in `repair-01.md`)
  fixed MINOR-1 to MINOR-3 and the routed NITs. Review 2 was not run, because Review 1 had
  no CRITICAL or MAJOR finding.

## Process events

- Step 04's first documentation-engineer run was cut off by a Claude usage limit before it
  wrote anything. The orchestrator confirmed no file had changed and reran it.
- Step 02b's storage-engineer left a background shell polling with
  `while pgrep -f 'sbt-launch|LocalIngestionRun' ...; do sleep 20; done`. The pattern
  matched the loop's own command line, so it never ended. I stopped it after about two
  hours; it did no work and changed nothing.
- Two long gaps in the overnight run (about an hour after S1b, and about two hours
  around S2b) are recorded in `run-log.md` as observed.
- Two small errors in agent reports (the R1-1s verdict assumption, and the R1-4 report
  calling the stray folder git-ignored) are corrected in `run-log.md`; STATUS.md is right
  on both.

## Decisions

- MINOR-5: after the out-of-memory failure, spark-engineer retried once at full scale with
  more shuffle partitions before stepping down, which cost another 90 minutes. I accept
  it: the step-down rule allows a retry, and the failed attempt is useful evidence of
  where the scale limit sits.
- MINOR-4: S2's failed first attempt left an untracked folder, `modules/hadoop/datasets/`,
  which is not git-ignored. I removed it before committing.
- OpenAlex: I will not complete the fetch in this phase. Filling the gap would mean
  rerunning Hadoop, entity resolution and Spark, which the deadline does not allow.

## Accepted, with known gaps

The Phase 08 embedding and S3 Vectors ingestion path is accepted as committed. Carried
forward (full list in STATUS.md under "Carried forward from Phase 08"):
- A contract test running the Neptune and DynamoDB loaders on the JSON-lines export
  (storage-engineer, Phase 11).
- A full-export local ingestion does not fit in 2 GB; the cloud ingestion runs in the
  cloud phases.
- The er-v1 match runs finished close to the 90-minute limit at both scales, with low CPU
  use at 25,000 works (entity-resolution-engineer).
- A few GB of git-ignored pipeline outputs remain on this machine under `target/` and
  `datasets/samples/local/`.
- The guard does not catch self-matching `while pgrep` polling loops, and the stop hook
  repeatedly asks for sbt check while agents run (factory-engineer, Phase 11).
- The aws-api MCP server is reaching end of development (factory-engineer, Phase 12,
  already tracked).

## Manual changes

- I removed the untracked stray folder `modules/hadoop/datasets/`, left by a failed
  hadoop-engineer attempt.
- I stopped the leftover background polling shell from step 02b.
- Manual changes to agent-owned files: none.

## Commit

Accepted commit: see `commit.txt`

Phase 8 is complete. I approve starting Phase 9.
