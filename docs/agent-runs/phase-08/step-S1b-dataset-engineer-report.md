HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier, role default (no override)

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 08 step S1b. Produce a 25,000-work subset of the S1 snapshot 2026-11-24 with no network fetch, in datasets/samples/local/2026-11-24-25k/. Add a retrieval.json so verify works. Record the subset in the manifest.

FILES CREATED: none in git. The data folder /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/ is git-ignored. `git check-ignore -v` printed `datasets/samples/local/.gitignore:1:*`, and `git status` shows nothing for it.

FILES MODIFIED:
- /home/ajcreator/scholarchain/datasets/scripts/fetch_samples.py: new standard-library `subset` command with `--parent DIR`, plus a `shutil` import.
- /home/ajcreator/scholarchain/tests/module-unit/datasets/test_dataset_manifest.py: new SubsetCommandTest. It runs offline (proxy set to a dead port) and checks per-year counts, DOI filtering, abstract counts and `verify`.
- /home/ajcreator/scholarchain/datasets/dataset-manifest.yaml: new top-level `derived_subset_25k` field. It holds the command, the rule, the parent snapshot, and the observed counts and checksums.

FOLDER: /home/ajcreator/scholarchain/datasets/samples/local/2026-11-24-25k/

DBLP USED: I derived the subset from the parent dblp-sample.xml, taking the first 1,000 records per year. The parent is ordered by (year, hash), so this follows the S1 rule at a smaller quota. I then reran `fetch_samples.py dblp --years 2000-2024 --records 25000 --min-authors 2 --max-bytes 8589934592 --gz <local dblp.xml.gz>` into the scratchpad. The rerun's output was byte-identical: `cmp` reported IDENTICAL, and both files have sha256 9acfd36f... (full value below).

OpenAlex and Crossref: filtered the parent files to the subset's DOIs, lowercased and with the doi.org prefix stripped. No request was made.

COUNTS
- DBLP works: 25,000, with 1,000 per year for each year 2000 to 2024 (25 years).
- DOIs: 22,602 distinct DOIs in DBLP ee links.
- OpenAlex works: 12,433 (12,432 distinct DOIs), of which 6,292 have an abstract.
- Crossref works: 1,137.

CHECKSUMS (sha256)
- dblp-sample.xml: 9acfd36f11aa8fcd154d090174f509082d25fe692bef514f5e5fd6f429ae6a84 (13,590,096 bytes)
- openalex-works.jsonl: 74c7cdfbff8633696a1fa84c19c12b01a71a31d06c9a86684b4bde1e2228de10 (89,275,380 bytes)
- crossref-works.jsonl: 68bb5aaedd9983f622f2eb433794d5d2b91fbeedca7699c32bfea4e387b29c67 (1,054,873 bytes)
- dblp.dtd: 2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6 (13,134 bytes), copied from the parent.
- Each retrieval.json entry also records the parent snapshot and the parent file's sha256.

COMMANDS RUN (all successful):
- `git check-ignore -v`
- `python3 -I -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'`
- `fetch_samples.py subset --parent datasets/samples/local/2026-11-24 --out datasets/samples/local/2026-11-24-25k --snapshot 2026-11-24 --years 2000-2024 --records 25000`
- `fetch_samples.py dblp --years 2000-2024 --records 25000 ... --gz ... --out <scratchpad>`, then `sha256sum` and `cmp`
- `fetch_samples.py verify --out datasets/samples/local/2026-11-24-25k --snapshot 2026-11-24`
- `python3 datasets/scripts/validate-manifest.py`
- `bash -n datasets/scripts/fetch-all-samples.sh`
- `python3 -m py_compile datasets/scripts/fetch_samples.py`

TEST RESULTS: unittest run reported "Ran 19 tests ... OK" (the 18 from S1 plus SubsetCommandTest). My first version of the new test failed because its fixture used an invalid DOI prefix (`10.1/`) that the DOI regex rejects. That was a test bug, so I fixed the test and not the code. sbt check was not run; no Scala changed.

VALIDATION RESULTS:
- verify: "4 files in .../datasets/samples/local/2026-11-24-25k match retrieval.json".
- validate-manifest: "[manifest][ok] 5 datasets, required: dblp, openalex, crossref, orcid".
- No UNVERIFIED fields are new. The parent's UNVERIFIED items remain: the OpenAlex snapshot release date, the Crossref public data file, and any upstream sha256 of the DBLP dump.

ASSUMPTIONS:
- In the new retrieval.json, `requestedDois` for openalex and crossref (22,602) is the subset's DOI count, not a request count. Nothing was requested. The `filter` field and the manifest text say so.
- Licenses and limitations are inherited from the parent manifest entries.

RISKS:
- OpenAlex coverage is only 12,433 works for 22,602 DOIs. The parent fetch took the first 50,000 DOIs in sorted order and then hit the keyless daily limit, so the subset inherits a DOI-prefix bias.
- Crossref coverage is thin (1,137 works). The parent fetched only 5,000 hash-ordered DOIs.
- fetch-all-samples.sh is unchanged.
- The folder is local only. Nothing was uploaded and nothing was committed.

MANUAL ACTION REQUIRED: none. Optionally, the human could complete the parent OpenAlex fetch with a free key to improve coverage. That would be a new fetch, so it is not part of this step.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: spark-engineer reruns the Spark stage once at 25,000 works, reading datasets/samples/local/2026-11-24-25k/. The upstream Hadoop and entity-resolution outputs also need regenerating at 25k by their owners. After that, documentation-engineer updates STATUS.md and code-reviewer reviews the diff.
