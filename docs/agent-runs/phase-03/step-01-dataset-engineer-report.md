HARNESS: claude-code
MODEL: Sonnet 5.5 (claude-sonnet-5-5), standard tier role default

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: P03-T01 (Phase 03, dataset-engineer block): manifest, bounded scripts, legal samples, schema notes.

FILES CREATED (all absolute, under /home/ajcreator/scholarchain):
- datasets/dataset-manifest.yaml
- datasets/scripts/fetch_samples.py, datasets/scripts/fetch-all-samples.sh, datasets/scripts/make-fixtures.py, datasets/scripts/validate-manifest.py
- datasets/schemas/README.md
- datasets/samples/real/{dblp-sample.xml, openalex-works.jsonl, crossref-works.jsonl, retrieval.json}
- datasets/samples/fixtures/{dblp-fixture.xml, openalex-works-fixture.jsonl, crossref-works-fixture.jsonl, orcid-records-fixture.jsonl}
- datasets/samples/local/.gitignore (git-ignores bounded local fetches; the directory is otherwise empty)
- tests/module-unit/datasets/test_dataset_manifest.py (Python unittest, 9 tests; outside sbt, see RISKS)
FILES MODIFIED: none. I touched nothing in datasets/gold, modules/** or any other territory. I read only ~/ref-phase-03/datasets/** and REFERENCE.md.

DATASETS SELECTED AND WHY
- DBLP: mandatory seed.
- OpenAlex: canonical author IDs, institutions, topics, ORCIDs.
- Crossref: second DOI-keyed source. I chose Crossref over Semantic Scholar because it has an open REST API with documented terms.
- ORCID: strong identity evidence.
- These are the spec's recommended minimum (DBLP + OpenAlex + ORCID + Crossref). That makes 3 enrichment datasets plus DBLP.
- fixtures (not required): a synthetic, hand-checkable corpus for tests.
- Not selected: OpenCitations, arXiv, Semantic Scholar, S2ORC, Papers with Code. I did not inspect their sources; they are optional per the spec.

OBSERVED PER SOURCE (all observed 2026-11-22 UTC)
DBLP
- License: CC0 1.0, ODC-BY 1.0 as a secondary license. Source: https://dblp.org/xml/README.txt (Legal Information, version of 2019-11-22).
- The dblp.org FAQ and copyright HTML pages are behind an Anubis bot challenge and could not be fetched.
- Snapshot: dblp.xml.gz Last-Modified Sat, 21 Nov 2026 22:20:07 GMT; ETag "42eabfb9-660d0c35bcf23".
- Size: Content-Length 1122680761 bytes (compressed). Uncompressed size UNVERIFIED.
- Checksum: upstream dblp.xml.gz.md5 = dcb2e8cd2d1c05e18a1e8f18165b2c5e. The upstream publishes no SHA-256, so the full-dump SHA-256 is UNVERIFIED.
- Schema: dblp.dtd is 13134 bytes, Last-Modified 2023-06-28. The dump declares encoding ISO-8859-1 (observed in the stream header).
- Release archive: https://dblp.org/xml/release/ redirects (301) to https://drops.dagstuhl.de/entities/collection/10.4230/dblp.xml.

OpenAlex
- License: CC0. Source: https://help.openalex.org/access/overview.md, and https://openalex.s3.amazonaws.com/LICENSE.txt, which is the CC0 1.0 legal code.
- docs.openalex.org now redirects (301) to help.openalex.org.
- Snapshot size: about 745 GB compressed JSONL, works about 615 GB, "as of the September 2026 release" (help.openalex.org/access/snapshot.md).
- Exact snapshot release date: UNVERIFIED.
- Snapshot per-file checksums: UNVERIFIED.
- API behavior: without a key, a response carried a notice that it shares a $0.10/day budget per IP; a free key gives $1/day. The script reads OPENALEX_API_KEY from the environment and never prints it.
- Sample is API-based, retrieval date 2026-11-22. The API has no published schema version.

Crossref
- Terms: https://www.crossref.org/documentation/retrieve-metadata/rest-api/ says almost none of the metadata is subject to copyright and it may be used for any purpose; some abstracts may carry publisher or author copyright.
- Abstracts and references are dropped from the sample.
- A single response was 10389 bytes; message-version 1.0.0.
- Public data file size, date and checksum: UNVERIFIED (not inspected).
- Rate limits: UNVERIFIED.

ORCID
- The Public Data File is CC0 1.0 (https://info.orcid.org/public-data-file-use-policy/; figshare API https://api.figshare.com/v2/articles/34697766 reports license CC0).
- Note the policy's norms: attribute ORCID, respect individuals' privacy and publicity rights.
- 2026 file: figshare published 2026-10-22. summaries tar.gz is 57290954546 bytes, md5 a55a16fe0b292bda03cc6016546c8984. The activities parts are about 20.1 to 22.9 GB each (11 parts).
- The terms for the public API (pub.orcid.org) are UNVERIFIED, as are its rate limits.
- Observed API record size: 22690 bytes for one record; 1363048 bytes for 3 other records.

SAMPLE FILES, BYTE SIZES, LEGAL BASIS (total samples dir 163579 bytes)
| File | Bytes | sha256 | Legal basis |
|---|---|---|---|
| samples/real/dblp-sample.xml (40 records, at least 2 authors each, first in file order) | 17713 | 7ea9e5152121a7a8c97d3a5b08ce2439d5ce6c0611f5a30081c85519dfb4934e | CC0 |
| samples/real/openalex-works.jsonl (25 works, projected fields) | 110720 | 1223f4ec6229ed9567e536534a227dbb3147aad7049e09f43a4b67bce12c2fd7 | CC0 |
| samples/real/crossref-works.jsonl (25 works; DOI, title, author, issued, container-title, type, publisher only) | 13976 | ba32fc243fbbab73446c9a8594a6d4427715d4ac941ca17cab0765afdfe5eb50 | Crossref terms above, abstracts removed |
| samples/real/retrieval.json | 1144 | not self-listed | records ETag, Last-Modified, counts and checksums |
| samples/fixtures/dblp-fixture.xml | 5475 | 0fce36f0529a3b58ca9f56a493b06bdc1437455070c32470376c7800090064a7 | generated here, synthetic |
| samples/fixtures/openalex-works-fixture.jsonl | 10421 | a9c4c994f1c8814169c54ba20b403ccc9df29465c8ab3915a081941e1d34f1f4 | synthetic |
| samples/fixtures/crossref-works-fixture.jsonl | 2928 | 6d15b9ead37da4d3bc6521380e041f7c247202c035a953e531b55d4df0794181 | synthetic |
| samples/fixtures/orcid-records-fixture.jsonl | 1188 | 9a6054e8097450f49c3ac4ed77be29031f459229331fc53671424ea5d9340f10 | synthetic |

- No real ORCID record is committed; fetch_samples.py refuses to write ORCID output under samples/real.
- The 40-record DBLP sample is the first records in file order, all Springer reference-work chapters. It is not representative of the whole dump; this is recorded under limitations in the manifest.
- The DBLP sample contains 3 author elements that carry an orcid attribute. This is public CC0 DBLP data.
- The fixture ORCID iDs (0000-0009-9000-0018, 0000-0009-9000-0026) returned HTTP 404 from pub.orcid.org on 2026-11-22. That is an observation, not an ORCID reservation.

VALIDATION COMMANDS AND OBSERVED OUTPUT
- `python3 datasets/scripts/validate-manifest.py` gave `[manifest][ok] 5 datasets, required: dblp, openalex, crossref, orcid`. It checks required fields, the ${VAR} bucket placeholder, no account ID, no forbidden-suffix file names, and sha256 and size of every sample_files entry.
- A PyYAML 6.0.3 safe_load of the manifest loaded 5 datasets, so it is also standard YAML.
- `python3 datasets/scripts/fetch_samples.py verify --out datasets/samples/real` gave `[verify] 3 files in .../datasets/samples/real match retrieval.json`.
- Bounded fetches:
  - dblp (40 records, min 2 authors) gave `[fetch] dblp: 40 records, 1048576 decompressed bytes scanned`.
  - openalex (25 DOIs) gave `25 works for 25 DOIs`.
  - crossref gave `25 of 25 DOIs`.
  - A second dblp run with an existing output gave `exists (use --force)` (idempotent).
- Local ORCID path: dblp --records 300 then `orcid --max 3` gave `3 of 3 iDs`; `verify` passed (2 files). I deleted that local output afterwards.
- `orcid --out datasets/samples/real` gave `refusing: ORCID records are personal data...` (rc=1).
- `fetch_samples.py dblp --records 600000` gave `exceeds 500000; pass --allow-large to override` (rc=1).
- `bash -n datasets/scripts/fetch-all-samples.sh` gave ok; `python3 -m py_compile datasets/scripts/*.py` gave ok.
- `make-fixtures.py <scratch dir>` then `diff -r` against datasets/samples/fixtures printed `fixtures-identical`.
- `python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'` gave `Ran 9 tests ... OK`.
- Did not run: sbt (per work order); `fetch-all-samples.sh` end to end; `upload`; any full-corpus download.

TEST RESULTS: 9 Python unittest tests pass. They cover manifest validity, owner and unverified list on every entry, no ORCID file under samples/real, total samples under 1 MB, a tampered-size manifest failing, the record cap, the ORCID guard, verify detecting a mismatch, and fixture reproducibility.

ASSUMPTIONS
- snapshot_date for the committed samples is the retrieval date 2026-11-22; the DBLP dump's own Last-Modified (2026-11-21) is recorded beside it.
- S3 prefixes are templates `s3://${RAW_BUCKET}/<dataset>/snapshot_date=YYYY-MM-DD/`. infra-engineer must supply RAW_BUCKET.
- The manifest uses the strict YAML subset parsed by validate-manifest.py. The sample_files lists use the form "path sha256=... bytes=N".
- Beyond the spec fields, the manifest adds name, license_source, observed_on, sample_files and unverified; each dataset's unverified list names what was not observed.

RISKS
- Not done: no full or large download; no S3 upload; no check of OpenCitations, arXiv, Semantic Scholar, S2ORC or Papers with Code.
- The Python tests live in tests/module-unit/datasets but are not run by `sbt check`. Whether to wire them in is a build-engineer or test-engineer decision.
- ResourceWarning on unclosed files in validate-manifest.py's parse() under `python3 -W error`. It is cosmetic and the validator passes.
- State left behind: datasets/samples/local holds only a .gitignore. Nothing else is outside git-trackable territory.
- Hadoop parsers must read the DBLP header (ISO-8859-1, DTD entities). The reference fixture generator assumed UTF-8; I changed the fixture header to ISO-8859-1 (content is ASCII).
- The sample uses real researcher names. They are public CC0 bibliographic data.

MANUAL ACTION REQUIRED (human) - unresolved dataset questions
1. ORCID terms. The CC0 grant I observed covers the Public Data File only. I did not verify the public-API terms for retrieving records and keeping them in S3. ORCID records are personal data (names, employers, works). Please decide whether to use the API sample, or the annual Public Data File, which is CC0 but about 57 GB for summaries alone and needs approval for cost. No real ORCID record is committed meanwhile.
2. The DBLP FAQ license page is bot-protected. I took the CC0 statement from https://dblp.org/xml/README.txt. Please confirm that is sufficient.
3. The full-dump SHA-256 for DBLP is UNVERIFIED. Only MD5 is published; a SHA-256 requires a 1.1 GB download that needs your approval.
4. Scale: the spec's recommended 100,000 DBLP works require a larger bounded fetch (about 1.1 GB streamed at most per run, capped by --records and --max-bytes). I did not run it. Also note the OpenAlex keyless budget is small; a free key via OPENALEX_API_KEY is advisable for 100,000 works. The key comes from the environment only.
5. The OpenAlex snapshot release date and the Crossref public data file details are UNVERIFIED.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: run domain-engineer (P03-T02), then documentation-engineer for STATUS.md. The reviewer should check that no license, size or checksum is invented; the manifest's `unverified` lists and `license_source` URLs are the audit trail.
