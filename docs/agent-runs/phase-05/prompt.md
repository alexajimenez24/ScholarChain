Use the entity-resolution-engineer agent to implement the ScholarChain entity-resolution subsystem against the Phase 3 contracts and Phase 4 MatchCandidate records.

Required behavior:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.
- Normalization for Unicode, punctuation, whitespace, title markup, initials, name order, and DOI prefixes.
- Feature functions for researcher-name similarity, initials, author order, coauthor overlap, affiliation compatibility, ORCID or external IDs, title similarity, year distance, venue compatibility, citation-neighborhood evidence when available, and source reliability.
- A versioned scoring policy.
- Separate thresholds for accepted, rejected, and unresolved results.
- Conflict detection when authoritative identifiers disagree.
- LLM adjudication only for a bounded unresolved band.
- Strict JSON schema for LLM adjudication with accept, reject, and abstain outcomes.
- No LLM call may alter source records or create an identifier not present in the candidates.
- Cache LLM adjudications by input hash, prompt version, model version, and policy version.
- Produce accepted, rejected, unresolved, and conflict outputs.
- Generate a review queue for human-labeled evaluation examples.

Evaluation requirements:
- At least 200 manually reviewed candidate pairs.
- Precision, recall, F1, confusion matrix, and coverage, reported separately for researcher-identity resolution and work resolution when both are implemented.
- Metrics by match path: exact ID, deterministic bibliographic, probabilistic, and LLM-adjudicated.
- Error analysis with at least ten false positives and ten false negatives when available.
- Threshold sensitivity analysis.

Run sbt check and the gold-set evaluation.
Report the policy version, feature definitions, thresholds, model and prompt versions, confusion matrix, unresolved rate, and representative errors.
```

Addendum from the human architect for this repository:

- Reference material: ~/ref-phase-05/ holds a reference implementation of this phase
  from the collaborative ScholarChain repository. Read ~/ref-phase-05/REFERENCE.md
  first. Each owner adapts the files in its own territory, reviews every line and owns
  the result; nothing is copied unread, and no owner writes outside its territory.
- Gold set: every label is written by the human into datasets/gold/er-gold-v1.csv
  (human-only territory). Agents generate the review queue and read the finished file;
  they never write, infer or pre-fill a label.
- No leakage: the scoring policy er-v1 and its accept, reject and unresolved thresholds
  are fixed and committed in the module before any label exists. Matching code never
  reads datasets/gold/. Threshold sensitivity is reported, not used to retune er-v1.
- Order, one owner at a time:
  1. build-engineer: give entityResolution hadoop-client-api (Provided) and the Hadoop
     test runtime with the forked test JVM capped at -Xmx1g, as the hadoop module has,
     using only version values from project/Versions.scala. Report as
     step-01-build-engineer-report.md.
  2. entity-resolution-engineer, task T1 (do this first, the human labels at 09:00):
     implement the review queue and generate it from this repository's Phase 04 local
     pipeline run on datasets/samples. Write it to target/er-review/er-review-v1.csv
     (git-ignored), one row per candidate pair, at least 250 rows, stratified across
     researcher and work pairs and across likely matches, borderline pairs and likely
     non-matches. Columns, in this order: pair_id, kind (researcher or work), then for
     each side (left_, right_): source, record_id, name_or_title, orcid_or_doi,
     affiliations, coauthors (up to 5), venues_years, titles (up to 3); then label and
     note, both empty. Do not show scores, match paths or predicted labels. Every row
     must be traceable to its source records. If the samples give fewer than 250
     candidate pairs, stop and report BLOCKED-ON dataset-engineer for a larger bounded
     sample within the manifest's rules. Report as step-02-entity-resolution-engineer-report.md.
  3. entity-resolution-engineer, task T2: the rest of this work order, except the
     gold-set evaluation, with er-v1 fixed. LLM adjudication sits behind a port with a
     deterministic fake and an off mode until Bedrock arrives in Phase 07. Report as
     agent-report.md.
  4. When the human reports that datasets/gold/er-gold-v1.csv exists:
     entity-resolution-engineer, task T3: the gold-set evaluation exactly as the work
     order lists it, counting MATCH and NON_MATCH labels and reporting UNSURE rows
     separately. Save the evaluation report as
     docs/agent-runs/phase-05/output-er-evaluation.md and the task report as
     step-03-entity-resolution-engineer-report.md.
  5. documentation-engineer: docs/entity-resolution.md and
     modules/entity-resolution/MODULE.md with only numbers observed here. Report as
     step-04-documentation-engineer-report.md.
  Update STATUS.md through documentation-engineer after each owner.
- Then one fresh code-reviewer review (review-report.md), one repair round (repair-01*.md)
  and one fresh re-check (review-report-02.md). Findings still open after that are
  carried forward to the human; there is no second repair round.
- Machine limits (8 GB laptop): never more than one sbt at once, no background shells,
  no polling or wait loops, no mutation testing; every command runs in the foreground
  with a timeout.
