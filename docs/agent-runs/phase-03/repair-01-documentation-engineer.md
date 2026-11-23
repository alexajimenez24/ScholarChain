HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier, role default

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: P03-R1-3. Apply the domain-engineer repair notes 1 to 9 and MINOR-11 to `modules/core/MODULE.md`. Apply note 10 and two decision rows to PROJECT_MEMORY.md. Update STATUS.md.

FILES CREATED: none
FILES MODIFIED:
- /home/ajcreator/scholarchain/modules/core/MODULE.md
- /home/ajcreator/scholarchain/PROJECT_MEMORY.md
- /home/ajcreator/scholarchain/STATUS.md

COMMANDS RUN (all observed to succeed):
- `ls tests/module-unit/core/resources/wire | wc -l` printed 19.
- grep of `test(` lines per suite: Config 20, Ids 12, Normalize 4, Validate 24, Serialization 11.
- grep and sed over `Model.scala`, `Ids.scala`, `Validate.scala` and `JsonLines.scala` to confirm every name written.
- awk line-length check (more than 90 characters) and an em-dash grep over the three files.

TEST RESULTS: none run. I did not run sbt; test counts are cited, not re-run.

VALIDATION RESULTS:
- Wire files: I counted 19 on disk and wrote 19. The domain-engineer report says 18 (also note 8).
- Test counts: MODULE.md now says 89 in total. By suite: Ids 12, Validate 24, Config 20, Normalize 4, Serialization 29. The source is `repair-01-domain-engineer.md`.
  - Ids, Validate, Config and Normalize match my own count of `test(` lines in the files.
  - SerializationSuite has 11 `test(` lines, but one of them registers a frozen-example test per wire file. That gives 10 named tests plus 19 frozen tests, which is 29, so the report's figure holds.
- Schema versions: 18, including `Mention = "author-mention-v1"`, which is in `Supported`.
- Names checked in the code: `Validate.mention`, `Validate.snapshotDate`, `Ids.edgeTag`, `Ids.MaxManifestNumber`, and `JsonLines.decode` and `decodeValid` with `Reader` and `Writer` bounds. `AuthorMention.provenance` exists.
- MINOR-11: MODULE.md now says there is one validator per record except `AnswerClaim` and the nested parts. `Validate.claim` is listed separately, and `AnswerClaim`'s provenance is its evidence IDs.
- Added to MODULE.md:
  - the validation rules from repair round 1;
  - the strict and total decoding rule;
  - the researcher ID scope;
  - the 2^53 rule for `Ids.run` and the 400-character limit for `Ids.work`;
  - the new config ranges and the `maxPathDepth` bound;
  - the models required in aws mode with `llmEnabled`;
  - a known issue about the work and researcher ID shape overlap.
- PROJECT_MEMORY.md: the "Researcher ID" row is updated, and two rows are added: "Decoding" and "Dataset uploads and ORCID".
- STATUS.md:
  - R1-2 and R1-3 are done, and Review 2 is next.
  - Notes R1-2 and R1-3 are added.
  - The open decision on the dblp.dtd CC0 status is added.
  - The open item for a reserved work-namespace list is added.
- Line-length and em-dash checks:
  - MODULE.md has no line over 90 characters and no em dash.
  - STATUS.md and PROJECT_MEMORY.md have no em dash.
  - Their only long lines are table rows and a few STATUS.md lines. Table rows were already long before this change, so I left them.
  - I did not check whether the long STATUS.md lines other than table rows were already there.

ASSUMPTIONS:
- The domain-engineer report is the source for the Serialization total of 29. I did not run sbt to confirm the 89.
- Note 2 asks for AuthorMention to be a "standalone Phase 4 output". I wrote that as stated in the code doc comment.

RISKS:
- STATUS.md "Note R1-1" still says the report's note 8 reads 18. I left it because it is a correct record of the discrepancy.
- Nothing else was touched. The Phase 03 STATUS.md task T04 is still pending.
- MODULE.md "Not done" and "Known issues" were otherwise unchanged. The only addition is the work/researcher ID shape issue.

MANUAL ACTION REQUIRED: Human, as listed in STATUS.md: confirm the inferred CC0 status of `dblp.dtd`, or drop it.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Review 2 by a fresh code-reviewer on the whole Phase 03 diff, saved as `review-report-02.md`.
