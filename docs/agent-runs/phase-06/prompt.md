```text
Use the spark-engineer agent to implement the ScholarChain Spark collaboration-analysis pipeline.

Required jobs:

1. Canonical researcher and authorship builder
- Read accepted researcher and work entity links plus Hadoop author-paper outputs.
- Construct canonical Researcher, Work, AUTHORED, Topic, Institution, and Venue tables.
- Preserve unresolved author mentions without forcing a merge.
- Validate that every canonical authorship edge points to existing nodes.

2. Temporal weighted coauthorship graph builder
- Map Hadoop pair candidates onto canonical researcher IDs.
- Drop self-pairs created by identity resolution.
- Canonicalize pair order so each logical undirected pair has one identity.
- Aggregate duplicate pairs.
- Store jointPublicationCount, firstCollaborationYear, lastCollaborationYear, and supporting work IDs or a stable pointer to them.
- Support configurable year windows.

3. Researcher collaboration breadth and team-size statistics
For each researcher and time window compute:
- publicationCount P_r
- distinctCoauthorCount C_r
- totalCoauthorOccurrences T_r
- averageCoauthorsPerPaper A_r
- maximumCoauthorsOnOnePaper M_r
- weightedDegree W_r
- the work IDs responsible for M_r
Keep every component visible. Do not replace these measures with one opaque collaboration score.

4. Connected components
- Compute connected components of the researcher coauthorship graph.
- Store component ID and component size.
- Report the fraction of researchers in the largest component and the number of singleton researchers when applicable.

5. Researcher connectivity and path support
- Provide a scalable method for shortest paths between selected researcher pairs.
- Materialize or cache bounded path evidence for benchmark queries.
- Where feasible, estimate component-level average path length or effective diameter using sampling or another documented scalable method.
- Never compute all-pairs shortest paths by collecting the graph on the driver.

6. Collaboration communities
- Compute a documented community method appropriate to the selected Spark stack, such as label propagation or another justified algorithm.
- Store community membership, community size, and cross-community edges.

7. Bridge analysis
- Compute at least one documented bridge measure such as exact or approximate betweenness centrality, cross-community degree, or participation coefficient.
- Record algorithm parameters and approximation strategy when exact computation is impractical.
- Report the number of distinct communities directly connected by each selected researcher when derivable.

8. K-core or equivalent dense-subgraph measure
- Compute coreness or a justified alternative that captures whether a researcher is embedded in a dense collaboration structure.
- Test against small hand-checkable graphs.

9. Temporal collaboration analysis
- Recompute selected metrics over configured time windows or yearly snapshots.
- Measure changes in distinct coauthors, team sizes, component membership, community membership, and bridge behavior.
- Do not compare windows with incompatible inclusion rules without stating the limitation.

10. Semantic-context preparation
- Produce researcher-profile summaries, community summaries, collaboration-path summaries, and selected paper or topic summaries for later embedding.
- Summaries must be generated from computed data and source records, not invented metrics.

11. Export
- Write partitioned Parquet node, edge, metric, community, component, path-evidence, and semantic-summary tables.
- Produce load manifests for Neptune, DynamoDB, S3 Vectors, and ordinary S3.

Operational requirements:
- Explain partitioning and shuffle boundaries.
- Avoid collecting the full graph on the driver.
- Configure checkpointing where iterative algorithms require it.
- Emit counts and invariant failures.
- Make every run reproducible from a run manifest.

Run sbt check, a local or small-cluster Spark integration test, and a bounded EMR Spark run.
Report stage DAGs, partition counts, shuffle metrics, graph counts, component counts, community counts, metric distributions, path-test results, temporal windows, rejected integrity violations, and output paths.
```

Addendum from the human architect for this repository:

- Scope: the deadline is 2026-11-25. Build exactly what the work order and the Phase 06
  gate require, and nothing optional. No extra jobs, tables, metrics or tests beyond what
  the gate names. Carried-forward items from earlier phases stay where they were assigned.
- Reference material: ~/ref-phase-06/ holds a reference implementation of this phase
  from the collaborative ScholarChain repository. Read ~/ref-phase-06/REFERENCE.md
  first. Each owner adapts the files in its own territory, reviews every line and owns
  the result. Nothing is copied unread, and no owner writes outside its territory.
  Limits hard-coded in the reference (for example the maximum-team work limit of 5) come
  from the `graph` block of scholarchain-v1.yaml instead.
- Inputs: Spark reads only this repository's Phase 04 Hadoop outputs (J07
  `export/{works,mentions,teams,pairs,authorpapers,topics,institutions,venues}`) and
  Phase 05 er-v1 decision outputs, located through their run manifests. It never reads
  raw data or datasets/gold/. Unresolved and conflict decisions are preserved, never
  merged. If a contract or config field is missing from core, report BLOCKED-ON
  domain-engineer.
- Order, one owner at a time:
  1. build-engineer: fork the sparkAnalytics tests with the JDK module opens from
     ~/ref-phase-06/build-notes-spark.sbt.txt, capped at -Xmx1g, using only version
     values from project/Versions.scala. Keep the committed decision about the Scala
     standard library in the Spark job jar, and confirm that the Spark job jar still
     builds. Report as step-01-build-engineer-report.md.
  2. spark-engineer, task T1: jobs 1 to 9, adapted from the reference, with the
     reference's known-graph suites adapted so that every gate item is covered: pair
     weights and distinct coauthors, components, community assignment, path queries,
     k-core, bridge measures, explicit windows. Expected values are worked out in the
     tests' comments. Every bridge measure is defined, with its limitations and
     approximation parameters. Report as step-02-spark-engineer-report.md.
  3. spark-engineer, task T2: jobs 10 and 11 and the run manifest. Then run the local
     integration run of the whole pipeline on this repository's Phase 04 and Phase 05
     sample outputs (local[2], 4 shuffle partitions, UI off). Capture from that run the
     formatted physical plans of the main stages, partition counts, shuffle read and
     write per stage (from a SparkListener, not the UI), graph, component and community
     counts, metric distributions, path-test results, windows, integrity rejects and
     output paths. Save them as docs/agent-runs/phase-06/output-spark-local-run.md.
     Then run the full sbt check once. The bounded EMR run is not possible before Phase
     12: report it as NOT RUN with that reason. Report as agent-report.md.
  4. documentation-engineer: docs/spark-jobs.md (partitioning and shuffle boundaries,
     checkpointing, bridge definitions and limits, windows) and modules/spark/MODULE.md,
     with only numbers observed here. Report as step-03-documentation-engineer-report.md.
  Update STATUS.md through documentation-engineer after each owner.
- Then one fresh code-reviewer review (review-report.md) and one repair round
  (repair-01*.md). Run a fresh re-check (review-report-02.md) only if the review had a
  CRITICAL or MAJOR finding. Findings still open after that are carried forward to the
  human; there is no second repair round.
- Machine limits (8 GB laptop, 5 GB for WSL): never more than one sbt at once, no
  background shells, no polling or wait loops, no mutation testing; every command runs
  in the foreground with a timeout. While working, run only the sparkAnalytics tests
  (`sbt sparkAnalytics/test`); the full sbt check runs once, at the end of step 3. If the
  harness moves sbt to the background, do not start anything else and do not poll; wait
  for it.
- No agent commits, runs cleanup.sh or touches cloud resources in this phase.
