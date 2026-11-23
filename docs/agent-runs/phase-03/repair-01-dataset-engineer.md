HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default effort)

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: P03-R1-2, Phase 03 repair round 1, MINOR-7 to MINOR-10 (leak only)

Findings:
- MINOR-7 FIXED. The ORCID step in fetch-all-samples.sh now runs only with WITH_ORCID=1. Otherwise the script prints "ORCID step skipped (opt-in ...)". The manifest orcid `retrieval` line now begins "OPT-IN, OFF BY DEFAULT until the human decides the ORCID public-API terms".
  - Tests: OrcidOptInTest.test_orcid_step_is_opt_in_in_fetch_all and test_manifest_says_orcid_is_off_by_default.
  - Command: `SNAPSHOT=2026-11-22-repair MAX_DOIS=3 bash datasets/scripts/fetch-all-samples.sh 20` printed the skip line and fetched no ORCID file.
- MINOR-8 FIXED. `upload` is removed from fetch_samples.py (function, choice, `--bucket`, and the now-unused `subprocess` import) and from fetch-all-samples.sh (the bucket argument and call). The manifest and schema notes had no upload instructions to change. The only match left is the fixtures `raw_prefix` text "committed, not uploaded". The S3 prefix templates stay as documentation.
  - Test: FetchBoundsTest.test_no_upload_subcommand. It checks that `upload` is rejected as an invalid choice, and that `subprocess` and `aws s3` appear in neither script.
- MINOR-9 FIXED (dblp.dtd committed).
  - Re-observed on 2026-11-22 at https://dblp.org/xml/dblp.dtd: HTTP 200, 13134 bytes, Last-Modified Wed, 28 Jun 2023 14:19:09 GMT, ETag "334e-5ff31445d5140", sha256 2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6.
  - Redistribution basis: the file's own header says "copyright by Schloss Dagstuhl", and that the metadata and the XML, JSON, RDF, RIS, BibTeX and text export formats on dblp's site are CC0 1.0. https://dblp.org/xml/README.txt (re-fetched) says the same. Neither text names dblp.dtd itself, so treating the DTD as CC0 is an inference. I recorded this in the manifest and flag it for the human (see RISKS).
  - Added the `dtd` subcommand to fetch_samples.py. It is idempotent, bounded to 1 MiB, and writes a `dblp-dtd` entry (url, etag, lastModified, sha256, bytes) to retrieval.json. fetch-all-samples.sh calls it. Committed as datasets/samples/real/dblp.dtd. The manifest dblp `sample_files` and `schema_version` now carry the DTD, its checksum and the basis. schemas/README.md says how parsers use it.
  - Tests in DblpDtdTest:
    - test_dtd_checksum_matches_retrieval_json.
    - test_both_dblp_samples_parse_with_entities_resolved. Both dblp-sample.xml (á) and dblp-fixture.xml (ü) parse with expat's external-subset handler pointed at the committed DTD, and the decoded characters are asserted.
    - test_plain_parser_without_dtd_fails. xml.etree raises ParseError without the DTD.
- MINOR-10 (leak) FIXED. test_refuses_full_scale_record_count now uses `TemporaryDirectory`, and `tempfile.mkdtemp` is gone from the file. Wiring the tests into `sbt check` is left to build-engineer.

FILES CREATED:
- /home/ajcreator/scholarchain/datasets/samples/real/dblp.dtd

FILES MODIFIED:
- /home/ajcreator/scholarchain/datasets/scripts/fetch_samples.py
- /home/ajcreator/scholarchain/datasets/scripts/fetch-all-samples.sh
- /home/ajcreator/scholarchain/datasets/samples/real/retrieval.json (via the script: new `dblp-dtd` entry, other entries unchanged)
- /home/ajcreator/scholarchain/datasets/dataset-manifest.yaml
- /home/ajcreator/scholarchain/datasets/schemas/README.md
- /home/ajcreator/scholarchain/tests/module-unit/datasets/test_dataset_manifest.py

COMMANDS RUN (all succeeded):
- `curl` of dblp.dtd with headers, `sha256sum`, and a re-fetch of https://dblp.org/xml/README.txt.
- `python3 -I datasets/scripts/fetch_samples.py dtd --out datasets/samples/real`. A second run printed "exists (use --force)", so it is idempotent.
- `bash -n datasets/scripts/fetch-all-samples.sh` printed bash-ok.
- `python3 datasets/scripts/validate-manifest.py` printed `[manifest][ok] 5 datasets, required: dblp, openalex, crossref, orcid`.
- `python3 -I datasets/scripts/fetch_samples.py verify --out datasets/samples/real` printed `[verify] 4 files in .../datasets/samples/real match retrieval.json`.
- `python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'` printed `Ran 15 tests in 0.292s` and `OK`. ResourceWarning noise about unclosed files came from the old validator and my tests (non-fatal).
- Bounded end-to-end run: `SNAPSHOT=2026-11-22-repair MAX_DOIS=3 bash datasets/scripts/fetch-all-samples.sh 20`.
  - dblp: 20 records.
  - dtd: 13134 bytes.
  - openalex: 3 works.
  - crossref: 3 of 3 DOIs.
  - ORCID skipped.
  - verify: 4 files match.
  - I deleted that scratch snapshot directory afterwards.

TEST RESULTS: 15 Python tests, 0 failures (before this repair there were 9, so 6 are new). I did not run sbt, as instructed.

VALIDATION RESULTS:
- dblp.dtd: source https://dblp.org/xml/dblp.dtd; license CC0 1.0 per the file header (applying it to the DTD file is an inference, see above); snapshot Last-Modified 2023-06-28 (file header "Date of this DTD file: May 10, 2023"); 13134 bytes; sha256 2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6.
- The other datasets are unchanged. The ORCID API terms remain UNVERIFIED and are escalated.
- The committed samples total well under the 1 MB test cap.

ASSUMPTIONS: The DTD header and README.txt are enough basis to commit a 13 KB schema file. The https://dblp.org/faq pages are still behind the bot challenge, which I confirmed again with WebFetch, so I could not read them.

RISKS:
- The DTD's own CC0 status is inferred, not stated. The human should confirm it or tell me to drop the file. Dropping it would mean removing the committed copy and the retrieval.json entry, and changing the test.
- I did not touch datasets/gold/**.
- No S3 write path remains in datasets/. Anyone who needs an upload must go through infra-engineer and deploy-engineer.
- The Python tests are still not part of `sbt check` (build-engineer or test-engineer).
- The ORCID API terms question is still open for the human.
- datasets/samples/local/ is empty and git-ignored. The `__pycache__` directories were regenerated and are git-ignored.

MANUAL ACTION REQUIRED: The human decides (1) whether the DTD's CC0 status, as inferred, is acceptable, and (2) the ORCID API terms, before WITH_ORCID=1 is ever used.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The fresh code-reviewer re-reviews MINOR-7 to MINOR-10 after the domain-engineer and documentation-engineer repairs land.
