#!/usr/bin/env python3
"""Bounded, idempotent retrieval of REAL dataset samples (standard library only).

  fetch_samples.py dblp     [--records N] [--min-authors K] [--snapshot YYYY-MM-DD] [--out DIR]
  fetch_samples.py openalex [--max-dois N] [--out DIR]
  fetch_samples.py crossref [--max-dois N] [--out DIR]
  fetch_samples.py orcid    [--max N]      (local output only, see PRIVACY below)
  fetch_samples.py verify   [--out DIR]    re-check every sha256 in DIR/retrieval.json
  fetch_samples.py dtd      [--out DIR]    dblp.dtd (13134 bytes), needed to resolve the entities in the DBLP sample

There is no upload command: S3 writes go only through the infra-engineer and deploy-engineer gated path.

Bounds (nothing downloads a full corpus):
  * DBLP is streamed from the gzip and the connection is closed after --records retained records
    (default 1000, refused above 500000 without --allow-large) or after --max-bytes of decompressed
    input (default 128 MiB), whichever comes first. The full dump is about 1.1 GB compressed.
  * OpenAlex, Crossref and ORCID are queried only for identifiers found in the DBLP sample, at most
    --max-dois / --max of them (defaults 50, 50 and 20).
An existing output file is reused unless --force. Output goes to --out (default
datasets/samples/local/<snapshot>/, git-ignored). The committed samples are produced with
--out datasets/samples/real. ORCID records are personal data and are never written under
datasets/samples/real.

Environment (names only; values are never printed or stored):
  CONTACT_EMAIL        optional, added to the Crossref/OpenAlex polite-pool requests
  OPENALEX_API_KEY     optional, raises the OpenAlex daily budget; sent as api_key
"""
import argparse, datetime, gzip, hashlib, json, os, re, sys, time
import urllib.error, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.normpath(os.path.join(HERE, "..", "samples"))
COMMITTED = os.path.join(SAMPLES, "real")
RETAINED = ("article", "inproceedings", "incollection", "book", "phdthesis", "mastersthesis")
DBLP_URL = "https://dblp.org/xml/dblp.xml.gz"
DTD_URL = "https://dblp.org/xml/dblp.dtd"
DTD_MAX_BYTES = 1 << 20  # the observed file is 13134 bytes; refuse anything over 1 MiB
DBLP_HEADER = b'<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n'
HARD_MAX_RECORDS = 500000
OPENALEX_SELECT = "id,doi,display_name,publication_year,type,authorships,topics,primary_location"
CROSSREF_KEEP = ("DOI", "title", "author", "issued", "container-title", "type", "publisher")  # no abstract, no references


def ua():
    mail = os.environ.get("CONTACT_EMAIL", "")
    return "ScholarChain-course-project" + (f" (mailto:{mail})" if mail else "")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_snapshot(label):
    """Snapshot label must be a real YYYY-MM-DD date (core provenance.snapshot rule); exits otherwise."""
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", label):
        sys.exit(f"[fetch] invalid --snapshot {label!r}: must match YYYY-MM-DD")
    try:
        datetime.date.fromisoformat(label)
    except ValueError:
        sys.exit(f"[fetch] invalid --snapshot {label!r}: not a real calendar date")


def out_dir(a):
    d = a.out or os.path.join(SAMPLES, "local", a.snapshot)
    if not a.out:
        local = os.path.realpath(os.path.join(SAMPLES, "local"))
        if os.path.commonpath([local, os.path.realpath(d)]) != local or os.path.realpath(d) == local:
            sys.exit(f"[fetch] output directory {d} escapes datasets/samples/local")
    os.makedirs(d, exist_ok=True)
    return os.path.abspath(d)


def record(d, name, info):
    path = os.path.join(d, "retrieval.json")
    data = json.load(open(path)) if os.path.exists(path) else {}
    data[name] = dict(info, retrievedAt=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    with open(path, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")


def get(url, accept="application/json", retries=4):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua(), "Accept": accept})
            return urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            raise


def dblp(a):
    if a.records > HARD_MAX_RECORDS and not a.allow_large:
        sys.exit(f"[fetch] --records {a.records} exceeds {HARD_MAX_RECORDS}; pass --allow-large to override")
    d = out_dir(a)
    out = os.path.join(d, "dblp-sample.xml")
    if os.path.exists(out) and not a.force:
        print(f"[fetch] {out} exists (use --force)")
        return
    start = re.compile(rb"<(" + b"|".join(t.encode() for t in RETAINED) + rb")[ >]")
    kept, scanned, buf = 0, 0, b""
    resp = get(a.url, "application/gzip")
    meta = {"url": a.url, "etag": resp.headers.get("ETag"), "lastModified": resp.headers.get("Last-Modified")}
    tmp = out + ".part"
    with gzip.GzipFile(fileobj=resp) as z, open(tmp, "wb") as f:
        f.write(DBLP_HEADER)
        while kept < a.records and scanned < a.max_bytes:
            chunk = z.read(1 << 20)
            if not chunk:
                break
            scanned += len(chunk)
            buf += chunk
            while kept < a.records:
                m = start.search(buf)
                if not m:
                    buf = buf[-64:]
                    break
                end_tag = b"</" + m.group(1) + b">"
                e = buf.find(end_tag, m.start())
                if e < 0:
                    buf = buf[m.start():]
                    break
                rec = buf[m.start():e + len(end_tag)]
                buf = buf[e + len(end_tag):]
                if rec.count(b"<author") >= a.min_authors:
                    f.write(rec + b"\n")
                    kept += 1
        f.write(b"</dblp>\n")
    resp.close()
    os.replace(tmp, out)
    record(d, "dblp", dict(meta, file="dblp-sample.xml", records=kept, minAuthors=a.min_authors, decompressedBytesScanned=scanned,
                           sha256=sha256(out), bytes=os.path.getsize(out)))
    print(f"[fetch] dblp: {kept} records, {scanned} decompressed bytes scanned -> {out}")


def sample_dois(d, limit):
    text = open(os.path.join(d, "dblp-sample.xml"), encoding="iso-8859-1").read()
    found = {m.lower() for m in re.findall(r"<ee[^>]*>https?://(?:dx\.)?doi\.org/(10\.\d{4,9}/[^<\s]+)</ee>", text)}
    return sorted(found)[:limit]


def write_jsonl(d, name, rows):
    out = os.path.join(d, name)
    with open(out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    return out


def need_dblp(d):
    if not os.path.exists(os.path.join(d, "dblp-sample.xml")):
        sys.exit(f"[fetch] run the dblp command first (no dblp-sample.xml in {d})")


def openalex(a):
    d = out_dir(a)
    need_dblp(d)
    name = "openalex-works.jsonl"
    if os.path.exists(os.path.join(d, name)) and not a.force:
        print(f"[fetch] {name} exists (use --force)")
        return
    dois = sample_dois(d, a.max_dois)
    works = []
    for i in range(0, len(dois), 50):
        q = {"filter": "doi:" + "|".join("https://doi.org/" + x for x in dois[i:i + 50]), "per-page": 50, "select": OPENALEX_SELECT}
        if os.environ.get("CONTACT_EMAIL"):
            q["mailto"] = os.environ["CONTACT_EMAIL"]
        if os.environ.get("OPENALEX_API_KEY"):
            q["api_key"] = os.environ["OPENALEX_API_KEY"]
        with get("https://api.openalex.org/works?" + urllib.parse.urlencode(q)) as r:
            works += json.load(r).get("results", [])
        time.sleep(0.2)
    out = write_jsonl(d, name, works)
    record(d, "openalex", {"file": name, "requestedDois": len(dois), "works": len(works), "select": OPENALEX_SELECT,
                           "sha256": sha256(out), "bytes": os.path.getsize(out)})
    print(f"[fetch] openalex: {len(works)} works for {len(dois)} DOIs")


def crossref(a):
    d = out_dir(a)
    need_dblp(d)
    name = "crossref-works.jsonl"
    if os.path.exists(os.path.join(d, name)) and not a.force:
        print(f"[fetch] {name} exists (use --force)")
        return
    dois = sample_dois(d, a.max_dois)
    items = []
    for doi in dois:
        url = "https://api.crossref.org/works/" + urllib.parse.quote(doi, safe="")
        if os.environ.get("CONTACT_EMAIL"):
            url += "?mailto=" + urllib.parse.quote(os.environ["CONTACT_EMAIL"])
        try:
            with get(url) as r:
                msg = json.load(r)["message"]
            items.append({k: msg[k] for k in CROSSREF_KEEP if k in msg})
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
        time.sleep(0.1)
    out = write_jsonl(d, name, items)
    record(d, "crossref", {"file": name, "requestedDois": len(dois), "found": len(items), "keptFields": list(CROSSREF_KEEP),
                           "sha256": sha256(out), "bytes": os.path.getsize(out)})
    print(f"[fetch] crossref: {len(items)} of {len(dois)} DOIs")


def orcid(a):
    d = out_dir(a)
    if os.path.commonpath([d, COMMITTED]) == COMMITTED:
        sys.exit("[fetch] refusing: ORCID records are personal data and are never written under datasets/samples/real")
    need_dblp(d)
    name = "orcid-records.jsonl"
    if os.path.exists(os.path.join(d, name)) and not a.force:
        print(f"[fetch] {name} exists (use --force)")
        return
    ids = set(re.findall(r'orcid="(\d{4}-\d{4}-\d{4}-\d{3}[\dX])"', open(os.path.join(d, "dblp-sample.xml"), encoding="iso-8859-1").read()))
    ids = sorted(ids)[: a.max]
    recs = []
    for i in ids:
        try:
            with get(f"https://pub.orcid.org/v3.0/{i}/record") as r:
                recs.append(json.load(r))
        except urllib.error.HTTPError as e:
            if e.code not in (404, 409):
                raise
        time.sleep(0.1)
    out = write_jsonl(d, name, recs)
    record(d, "orcid", {"file": name, "requested": len(ids), "found": len(recs), "sha256": sha256(out), "bytes": os.path.getsize(out)})
    print(f"[fetch] orcid: {len(recs)} of {len(ids)} iDs")


def verify(a):
    d = out_dir(a)
    path = os.path.join(d, "retrieval.json")
    if not os.path.exists(path):
        sys.exit(f"[verify] no retrieval.json in {d}")
    data = json.load(open(path))
    bad = [k for k, v in data.items() if sha256(os.path.join(d, v["file"])) != v["sha256"]]
    if bad:
        sys.exit(f"[verify] checksum mismatch: {', '.join(bad)}")
    print(f"[verify] {len(data)} files in {d} match retrieval.json")


def dtd(a):
    d = out_dir(a)
    out = os.path.join(d, "dblp.dtd")
    if os.path.exists(out) and not a.force:
        print(f"[fetch] {out} exists (use --force)")
        return
    with get(DTD_URL, "application/xml-dtd") as r:
        body = r.read(DTD_MAX_BYTES + 1)
        meta = {"url": DTD_URL, "etag": r.headers.get("ETag"), "lastModified": r.headers.get("Last-Modified")}
    if len(body) > DTD_MAX_BYTES:
        sys.exit("[fetch] dblp.dtd larger than 1 MiB, refusing")
    with open(out + ".part", "wb") as f:
        f.write(body)
    os.replace(out + ".part", out)
    record(d, "dblp-dtd", dict(meta, file="dblp.dtd", sha256=sha256(out), bytes=os.path.getsize(out)))
    print(f"[fetch] dtd: {len(body)} bytes -> {out}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["dblp", "openalex", "crossref", "orcid", "verify", "dtd"])
    p.add_argument("--snapshot", default=datetime.date.today().isoformat(), help="retrieval date label, YYYY-MM-DD")
    p.add_argument("--out", help="output directory (default datasets/samples/local/<snapshot>)")
    p.add_argument("--records", type=int, default=1000)
    p.add_argument("--min-authors", type=int, default=0)
    p.add_argument("--max-bytes", type=int, default=128 * 1024 * 1024, help="decompressed DBLP bytes scanned, at most")
    p.add_argument("--max-dois", type=int, default=50)
    p.add_argument("--max", type=int, default=20, help="ORCID iDs, at most")
    p.add_argument("--url", default=DBLP_URL)
    p.add_argument("--allow-large", action="store_true")
    p.add_argument("--force", action="store_true")
    a = p.parse_args()
    check_snapshot(a.snapshot)
    {"dblp": dblp, "openalex": openalex, "crossref": crossref, "orcid": orcid, "verify": verify, "dtd": dtd}[a.command](a)


if __name__ == "__main__":
    main()
