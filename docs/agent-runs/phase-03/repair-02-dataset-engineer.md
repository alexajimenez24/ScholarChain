HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, role default)

MINOR-2: FIXED. Proving tests: `FetchBoundsTest.test_snapshot_label_validated` and `FetchBoundsTest.test_all_samples_script_rejects_bad_snapshot`, both in tests/module-unit/datasets/test_dataset_manifest.py.

Changes
- `fetch_samples.py` has a new `check_snapshot()`, called in `main()` right after argument parsing. It rejects any `--snapshot` that does not match `^\d{4}-\d{2}-\d{2}$` or that fails `datetime.date.fromisoformat`. It exits non-zero with "invalid --snapshot ...".
- `out_dir()` has a defense-in-depth check. When `--out` is not given, the snapshot-derived directory must resolve strictly under datasets/samples/local. Otherwise it exits with "escapes datasets/samples/local".
- `fetch-all-samples.sh` checks SNAP against the date regex and `date -d` before any fetch. On failure it prints "invalid SNAPSHOT" to stderr and exits 2.

Tests added
- `test_snapshot_label_validated` runs `verify --snapshot X` for `2026-11-22-repair`, `../real`, `2026-13-40` and `x/../../y`. Each must exit non-zero with "invalid --snapshot". A valid `--snapshot 2026-11-22` run uses a TemporaryDirectory and `dblp --records 600000`. It passes the snapshot check and stops at the record bound ("exceeds"), so no network is used.
- `test_all_samples_script_rejects_bad_snapshot` sets `SNAPSHOT=../real` and expects exit code 2 and "invalid SNAPSHOT" on stderr.

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: P03-R2-2 (Phase 03, repair round 2 of 3), MINOR-2

FILES CREATED: none

FILES MODIFIED:
- /home/ajcreator/scholarchain/datasets/scripts/fetch_samples.py
- /home/ajcreator/scholarchain/datasets/scripts/fetch-all-samples.sh
- /home/ajcreator/scholarchain/tests/module-unit/datasets/test_dataset_manifest.py

COMMANDS RUN
- `bash -n datasets/scripts/fetch-all-samples.sh` passed.
- `python3 datasets/scripts/validate-manifest.py`
- `python3 datasets/scripts/fetch_samples.py verify --out datasets/samples/real`
- `python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'`
- I did not run sbt, as instructed.

TEST RESULTS
```
Ran 17 tests in 0.583s
OK
```
The only noise is a pre-existing ResourceWarning about an unclosed file at line 111 of the test file, which is not from my change.

VALIDATION RESULTS
```
[manifest][ok] 5 datasets, required: dblp, openalex, crossref, orcid
[verify] 4 files in /home/ajcreator/scholarchain/datasets/samples/real match retrieval.json
```
The manifest provenance fields (license, snapshot, checksum) are unchanged by this repair.

ASSUMPTIONS
- The `fetch-all-samples.sh` check runs before any fetch step, so a bad SNAPSHOT never reaches Python.
- An explicit `--out` is trusted as before, and the under-local check applies only to the snapshot-derived default path.

RISKS
- The ORCID guard from round 1 is untouched.
- datasets/samples/local is empty. No background shells are left running.
- datasets/scripts/__pycache__ is git-ignored.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of MINOR-2, then the Phase 03 human gate.
