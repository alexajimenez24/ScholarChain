"""Dataset manifest and sample checks. Run from the repository root:
python3 -m unittest discover -s tests/module-unit/datasets -p 'test_*.py'
(Python tests are outside `sbt check`; they check datasets/** only.)"""
import importlib.util, json, os, re, subprocess, sys, tempfile, unittest
import xml.parsers.expat as expat

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SCRIPTS = os.path.join(ROOT, "datasets", "scripts")


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), os.path.join(SCRIPTS, name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.cwd = os.getcwd()
        os.chdir(ROOT)
        self.v = load("validate-manifest")

    def tearDown(self):
        os.chdir(self.cwd)

    def test_manifest_valid_and_checksums_match(self):
        ds, required, errors = self.v.check("datasets/dataset-manifest.yaml")
        self.assertEqual(errors, [])
        self.assertEqual(required, ["dblp", "openalex", "crossref", "orcid"])

    def test_every_dataset_is_owned_and_has_unverified_list(self):
        ds, _, _ = self.v.check("datasets/dataset-manifest.yaml")
        for name, d in ds.items():
            self.assertEqual(d["owner"], "dataset-engineer", name)
            self.assertIn("unverified", d, name)

    def test_no_real_orcid_records_committed(self):
        real = os.path.join(ROOT, "datasets", "samples", "real")
        self.assertFalse([f for f in os.listdir(real) if "orcid" in f.lower()])

    def test_committed_samples_are_small(self):
        # the git-ignored datasets/samples/local/ (raw downloads, up to GBs) is not committed content
        total = sum(os.path.getsize(os.path.join(dp, f)) for sub in ("real", "fixtures")
                    for dp, _, fs in os.walk(os.path.join(ROOT, "datasets", "samples", sub)) for f in fs)
        self.assertLess(total, 1_000_000)

    def test_tampered_sample_fails_validation(self):
        with tempfile.TemporaryDirectory() as t:
            m = open(os.path.join(ROOT, "datasets", "dataset-manifest.yaml")).read().replace("bytes=17713", "bytes=1")
            p = os.path.join(t, "m.yaml")
            open(p, "w").write(m)
            _, _, errors = self.v.check(p)
            self.assertTrue(any("size mismatch" in e for e in errors))


class FetchBoundsTest(unittest.TestCase):
    def run_fetch(self, *args):
        return subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "fetch_samples.py"), *args], capture_output=True, text=True)

    def test_refuses_full_scale_record_count(self):
        with tempfile.TemporaryDirectory() as t:
            r = self.run_fetch("dblp", "--records", "600000", "--out", t)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("exceeds", r.stdout + r.stderr)

    def test_snapshot_label_validated(self):
        for bad in ("2026-11-22-repair", "../real", "2026-13-40", "x/../../y"):
            with self.subTest(bad=bad):
                r = self.run_fetch("verify", "--snapshot", bad)
                self.assertNotEqual(r.returncode, 0)
                self.assertIn("invalid --snapshot", r.stdout + r.stderr)
        with tempfile.TemporaryDirectory() as t:  # valid date passes the check, then hits the record bound (no network)
            r = self.run_fetch("dblp", "--records", "600000", "--snapshot", "2026-11-22", "--out", t)
        self.assertNotIn("invalid --snapshot", r.stdout + r.stderr)
        self.assertIn("exceeds", r.stdout + r.stderr)

    def test_all_samples_script_rejects_bad_snapshot(self):
        r = subprocess.run(["bash", os.path.join(SCRIPTS, "fetch-all-samples.sh")], capture_output=True, text=True,
                           env={**os.environ, "SNAPSHOT": "../real"})
        self.assertEqual(r.returncode, 2)
        self.assertIn("invalid SNAPSHOT", r.stderr)

    def test_no_upload_subcommand(self):
        r = self.run_fetch("upload", "--bucket", "x")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("invalid choice", r.stdout + r.stderr)
        self.assertNotIn("subprocess", open(os.path.join(SCRIPTS, "fetch_samples.py")).read())
        self.assertNotIn("aws s3", open(os.path.join(SCRIPTS, "fetch-all-samples.sh")).read())

    def test_orcid_refused_under_committed_dir(self):
        r = self.run_fetch("orcid", "--out", os.path.join(ROOT, "datasets", "samples", "real"))
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("personal data", r.stdout + r.stderr)

    def test_verify_detects_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as t:
            open(os.path.join(t, "x.jsonl"), "w").write("a\n")
            json.dump({"x": {"file": "x.jsonl", "sha256": "0" * 64}}, open(os.path.join(t, "retrieval.json"), "w"))
            r = self.run_fetch("verify", "--out", t)
            self.assertNotEqual(r.returncode, 0)

    def test_fixtures_are_reproducible(self):
        with tempfile.TemporaryDirectory() as t:
            subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "make-fixtures.py"), t], check=True, capture_output=True)
            for f in os.listdir(t):
                self.assertEqual(open(os.path.join(t, f), "rb").read(),
                                 open(os.path.join(ROOT, "datasets", "samples", "fixtures", f), "rb").read(), f)


class StratifiedSelectionTest(unittest.TestCase):
    def _recs(self):
        out = []
        for y in (2001, 2002):
            for i in range(6):
                out.append(f'<article key="journals/x/{y}-{i}"><author>A{i}</author><author>B{i}</author><title>t{i}</title><year>{y}</year></article>')
        out.append('<article key="journals/x/one"><author>A</author><title>t</title><year>2001</year></article>')
        out.append('<article key="journals/x/old"><author>A</author><author>B</author><title>t</title><year>1999</year></article>')
        return out

    def _run(self, d):
        import gzip, shutil
        xml = '<?xml version="1.0" encoding="ISO-8859-1"?>\n<dblp>\n' + "\n".join(self._recs()) + "\n</dblp>\n"
        with gzip.open(os.path.join(d, "dblp.xml.gz"), "wb") as f:
            f.write(xml.encode("iso-8859-1"))
        subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "fetch_samples.py"), "dblp", "--years", "2001-2002", "--records", "6",
                        "--min-authors", "2", "--out", d, "--force"], check=True, capture_output=True)
        return open(os.path.join(d, "dblp-sample.xml"), encoding="iso-8859-1").read()

    def test_quota_per_year_min_authors_year_range_and_determinism(self):
        with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
            a, b = self._run(d1), self._run(d2)
            self.assertEqual(a, b)
            keys = re.findall(r'key="([^"]+)"', a)
            self.assertEqual(len(keys), 6)
            self.assertEqual(sum(k.startswith("journals/x/2001-") for k in keys), 3)
            self.assertEqual(sum(k.startswith("journals/x/2002-") for k in keys), 3)
            self.assertNotIn("journals/x/one", keys)
            self.assertNotIn("journals/x/old", keys)
            r = json.load(open(os.path.join(d1, "retrieval.json")))["dblp"]
            self.assertEqual(r["selectedPerYear"], {"2001": 3, "2002": 3})


class SubsetCommandTest(unittest.TestCase):
    def test_subset_filters_dblp_and_rows_by_doi_without_network(self):
        def rec(y, i):
            return (f'<article key="journals/x/{y}-{i}"><author>A</author><author>B</author><title>t</title><year>{y}</year>'
                    f'<ee>https://doi.org/10.1234/{y}-{i}</ee></article>')
        with tempfile.TemporaryDirectory() as p, tempfile.TemporaryDirectory() as o:
            xml = '<?xml version="1.0" encoding="ISO-8859-1"?>\n<dblp>\n' + "\n".join(rec(y, i) for y in (2001, 2002) for i in range(4)) + "\n</dblp>\n"
            open(os.path.join(p, "dblp-sample.xml"), "w", encoding="iso-8859-1").write(xml)
            open(os.path.join(p, "dblp.dtd"), "w").write("<!-- dtd -->")
            oa = [{"doi": f"https://doi.org/10.1234/{y}-{i}", "abstract_inverted_index": {"a": [0]} if i == 0 else None} for y in (2001, 2002) for i in range(4)]
            cr = [{"DOI": f"10.1234/{y}-{i}"} for y in (2001, 2002) for i in range(4)]
            open(os.path.join(p, "openalex-works.jsonl"), "w").write("".join(json.dumps(r) + "\n" for r in oa))
            open(os.path.join(p, "crossref-works.jsonl"), "w").write("".join(json.dumps(r) + "\n" for r in cr))
            json.dump({"dblp": {"sha256": "x", "minAuthors": 2}, "openalex": {"sha256": "y"}, "crossref": {"sha256": "z"}}, open(os.path.join(p, "retrieval.json"), "w"))
            env = dict(os.environ, http_proxy="http://127.0.0.1:9", https_proxy="http://127.0.0.1:9")
            subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "fetch_samples.py"), "subset", "--parent", p, "--out", o,
                            "--years", "2001-2002", "--records", "4"], check=True, capture_output=True, env=env)
            r = json.load(open(os.path.join(o, "retrieval.json")))
            self.assertEqual(r["dblp"]["selectedPerYear"], {"2001": 2, "2002": 2})
            self.assertEqual((r["dblp"]["dblpDois"], r["openalex"]["works"], r["openalex"]["worksWithAbstract"], r["crossref"]["works"]), (4, 4, 2, 4))
            subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "fetch_samples.py"), "verify", "--out", o], check=True, capture_output=True)


class OrcidOptInTest(unittest.TestCase):
    def test_orcid_step_is_opt_in_in_fetch_all(self):
        sh = open(os.path.join(SCRIPTS, "fetch-all-samples.sh")).read()
        self.assertRegex(sh, r'if \[ "\$\{WITH_ORCID:-0\}" = "1" \]; then\n\s+python3 "\$F" orcid')
        self.assertEqual(len(re.findall(r'" orcid ', sh)), 1)  # only inside that guarded branch

    def test_manifest_says_orcid_is_off_by_default(self):
        m = open(os.path.join(ROOT, "datasets", "dataset-manifest.yaml")).read()
        self.assertIn("OPT-IN, OFF BY DEFAULT", m)


class DblpDtdTest(unittest.TestCase):
    REAL = os.path.join(ROOT, "datasets", "samples", "real")

    def parse_with_dtd(self, xml_path):
        p = expat.ParserCreate()
        p.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_ALWAYS)
        text = []
        p.CharacterDataHandler = text.append

        def ext(context, base, system_id, public_id):
            self.assertEqual(system_id, "dblp.dtd")
            q = p.ExternalEntityParserCreate(context)
            q.Parse(open(os.path.join(self.REAL, "dblp.dtd"), "rb").read(), True)
            return 1

        p.ExternalEntityRefHandler = ext
        p.Parse(open(xml_path, "rb").read(), True)
        return "".join(text)

    def test_dtd_checksum_matches_retrieval_json(self):
        info = json.load(open(os.path.join(self.REAL, "retrieval.json")))["dblp-dtd"]
        data = open(os.path.join(self.REAL, "dblp.dtd"), "rb").read()
        self.assertEqual(len(data), info["bytes"])
        import hashlib
        self.assertEqual(hashlib.sha256(data).hexdigest(), info["sha256"])

    def test_both_dblp_samples_parse_with_entities_resolved(self):
        for path, char in ((os.path.join(self.REAL, "dblp-sample.xml"), "\u00e1"),
                           (os.path.join(ROOT, "datasets", "samples", "fixtures", "dblp-fixture.xml"), "\u00fc")):
            raw = open(path, "rb").read()
            self.assertRegex(raw, rb"&[a-z]+acute;|&uuml;")  # the sample really uses DTD entities
            self.assertIn(char, self.parse_with_dtd(path), path)

    def test_plain_parser_without_dtd_fails(self):
        import xml.etree.ElementTree as ET
        with self.assertRaises(ET.ParseError):
            ET.parse(os.path.join(self.REAL, "dblp-sample.xml"))


if __name__ == "__main__":
    unittest.main()
