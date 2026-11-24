#!/usr/bin/env python3
"""Bounded, idempotent retrieval of REAL dataset samples (standard library only).

  fetch_samples.py dblp     [--records N] [--min-authors K] [--snapshot YYYY-MM-DD] [--out DIR]
  fetch_samples.py download [--max-seconds S] [--out DIR]   resumable download of dblp.xml.gz into DIR (git-ignored)
  fetch_samples.py dblp     --years 2000-2024 --records 100000 --min-authors 2 --max-bytes 8589934592 [--gz FILE]
                            stratified hash selection over the whole dump (see STRATIFIED RULE)
  fetch_samples.py openalex [--max-dois N] [--max-seconds S] [--out DIR]   resumable, 50 DOIs per request
  fetch_samples.py crossref [--max-dois N] [--doi-order sorted|hash] [--max-seconds S] [--out DIR]   resumable
  fetch_samples.py orcid    [--max N]      (local output only, see PRIVACY below)
  fetch_samples.py verify   [--out DIR]    re-check every sha256 in DIR/retrieval.json
  fetch_samples.py subset   --parent DIR --out DIR --years A-B --records N   derived subset of a local snapshot, no network
  fetch_samples.py dtd      [--out DIR]    dblp.dtd (13134 bytes), needed to resolve the entities in the DBLP sample

STRATIFIED RULE (dblp --years A-B): among records of the retained types with >= --min-authors author
elements and a <year> in A..B, take for each year Y the Q = records // (B-A+1) records with the smallest
h = int(sha256(dblp key as ISO-8859-1 bytes).hexdigest()[:16], 16); ties are broken by the key. The whole
dump is scanned (bounded by --max-bytes); memory holds only the Q best records per year. Output is
ordered by (year, h). The rule is a pure function of the dump, so it is reproducible.

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
import argparse, datetime, gzip, hashlib, heapq, json, os, re, shutil, sys, time
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
OPENALEX_SELECT = "id,doi,display_name,publication_year,type,authorships,topics,primary_location,abstract_inverted_index"
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


def download(a):
    """Resumable download of the DBLP gzip into the output directory (Range requests); bounded by --max-seconds."""
    d = out_dir(a)
    out = os.path.join(d, "dblp.xml.gz")
    part = out + ".part"
    if os.path.exists(out) and not a.force:
        print(f"[fetch] {out} exists (use --force)")
        return
    t0 = time.time()
    have = os.path.getsize(part) if os.path.exists(part) else 0
    req = urllib.request.Request(a.url, headers={"User-Agent": ua(), "Range": f"bytes={have}-"})
    resp = urllib.request.urlopen(req, timeout=60)
    status = resp.status
    if status == 200:
        have = 0
    total = have + int(resp.headers.get("Content-Length", "0"))
    meta = {"url": a.url, "etag": resp.headers.get("ETag"), "lastModified": resp.headers.get("Last-Modified")}
    with open(part, "ab" if status == 206 else "wb") as f:
        while True:
            if a.max_seconds and time.time() - t0 > a.max_seconds:
                print(f"[fetch] partial: {f.tell()} of {total} bytes; rerun to resume")
                return
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    resp.close()
    if os.path.getsize(part) != total:
        sys.exit(f"[fetch] incomplete download {os.path.getsize(part)} of {total}; rerun to resume")
    os.replace(part, out)
    record(d, "dblp-gz", dict(meta, file="dblp.xml.gz", sha256=sha256(out), md5=md5(out), bytes=os.path.getsize(out)))
    print(f"[fetch] download: {os.path.getsize(out)} bytes -> {out}")


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def dblp_stratified(a, d, out):
    y0, y1 = (int(x) for x in a.years.split("-"))
    nyears = y1 - y0 + 1
    quota = a.records // nyears
    rec_re = re.compile(rb"<(" + b"|".join(t.encode() for t in RETAINED) + rb")[ >].*?</\1>", re.S)
    start = re.compile(rb"<(" + b"|".join(t.encode() for t in RETAINED) + rb")[ >]")
    key_re = re.compile(rb'key="([^"]+)"')
    year_re = re.compile(rb"<year>(\d{4})</year>")
    heaps = {y: [] for y in range(y0, y1 + 1)}  # max-heap on (h, key) via negation
    eligible = {y: 0 for y in heaps}
    gz = a.gz or os.path.join(d, "dblp.xml.gz")
    meta = {}
    if os.path.exists(gz):
        src = open(gz, "rb")
        meta = {"source": "local file " + os.path.basename(gz)}
    else:
        src = get(a.url, "application/gzip")
        meta = {"url": a.url, "etag": src.headers.get("ETag"), "lastModified": src.headers.get("Last-Modified")}
    scanned, buf, total_recs = 0, b"", 0
    with gzip.GzipFile(fileobj=src) as z:
        while scanned < a.max_bytes:
            chunk = z.read(1 << 22)
            if not chunk:
                break
            scanned += len(chunk)
            buf += chunk
            last = 0
            for m in rec_re.finditer(buf):
                last = m.end()
                rec = m.group(0)
                total_recs += 1
                if rec.count(b"<author") < a.min_authors:
                    continue
                ym = year_re.search(rec)
                km = key_re.search(rec)
                if not ym or not km or not (y0 <= int(ym.group(1)) <= y1):
                    continue
                y = int(ym.group(1))
                key = km.group(1)
                h = int(hashlib.sha256(key).hexdigest()[:16], 16)
                eligible[y] += 1
                hp = heaps[y]
                item = (-h, key, rec)
                if len(hp) < quota:
                    heapq.heappush(hp, item)
                elif item > hp[0]:
                    heapq.heapreplace(hp, item)
            rest = buf[last:]
            i = start.search(rest)
            buf = rest[i.start():] if i else rest[-64:]
    src.close()
    kept, dist = 0, {}
    tmp = out + ".part"
    with open(tmp, "wb") as f:
        f.write(DBLP_HEADER)
        for y in sorted(heaps):
            rows = sorted(heaps[y], key=lambda t: (-t[0], t[1]))
            dist[str(y)] = len(rows)
            for t in rows:
                f.write(t[2] + b"\n")
                kept += 1
        f.write(b"</dblp>\n")
    os.replace(tmp, out)
    record(d, "dblp", dict(meta, file="dblp-sample.xml", records=kept, minAuthors=a.min_authors, years=a.years, perYearQuota=quota,
                           decompressedBytesScanned=scanned, retainedTypeRecordsScanned=total_recs,
                           eligiblePerYear={str(k): v for k, v in sorted(eligible.items())}, selectedPerYear=dist,
                           rule="per year, smallest int(sha256(key)[:16],16) among eligible records; sorted by (year, hash)",
                           sha256=sha256(out), bytes=os.path.getsize(out)))
    print(f"[fetch] dblp stratified: {kept} records ({quota}/year, {total_recs} retained-type records scanned, {scanned} bytes) -> {out}")


def dblp(a):
    if a.records > HARD_MAX_RECORDS and not a.allow_large:
        sys.exit(f"[fetch] --records {a.records} exceeds {HARD_MAX_RECORDS}; pass --allow-large to override")
    d = out_dir(a)
    out = os.path.join(d, "dblp-sample.xml")
    if os.path.exists(out) and not a.force:
        print(f"[fetch] {out} exists (use --force)")
        return
    if a.years:
        return dblp_stratified(a, d, out)
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


def sample_dois(d, limit, order="sorted"):
    text = open(os.path.join(d, "dblp-sample.xml"), encoding="iso-8859-1").read()
    found = {m.lower() for m in re.findall(r"<ee[^>]*>https?://(?:dx\.)?doi\.org/(10\.\d{4,9}/[^<\s]+)</ee>", text)}
    if order == "hash":
        return sorted(found, key=lambda x: (hashlib.sha256(x.encode()).hexdigest(), x))[:limit]
    return sorted(found)[:limit]


def resume_state(d, name):
    """Progress of a resumable jsonl: (items done, byte offset of the last complete write, found); truncates a torn tail."""
    out, prog = os.path.join(d, name + ".part"), os.path.join(d, name + ".progress")
    st = json.load(open(prog)) if os.path.exists(prog) else {"done": 0, "offset": 0, "found": 0}
    if not os.path.exists(out):
        st = {"done": 0, "offset": 0, "found": 0}
        open(out, "wb").close()
    with open(out, "r+b") as f:
        f.truncate(st["offset"])
    return out, prog, st


def save_progress(prog, st):
    with open(prog + ".tmp", "w") as f:
        json.dump(st, f)
    os.replace(prog + ".tmp", prog)


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
    if a.force:
        for ext in (".part", ".progress"):
            if os.path.exists(os.path.join(d, name + ext)):
                os.remove(os.path.join(d, name + ext))
    dois = sample_dois(d, a.max_dois, a.doi_order)
    part, prog, st = resume_state(d, name)
    t0 = time.time()
    with open(part, "ab") as f:
        for i in range(st["done"], len(dois), 50):
            if a.max_seconds and time.time() - t0 > a.max_seconds:
                print(f"[fetch] openalex partial: {st['done']} of {len(dois)} DOIs requested; rerun to resume")
                return
            batch = dois[i:i + 50]
            q = {"filter": "doi:" + "|".join("https://doi.org/" + x for x in batch), "per-page": 50, "select": OPENALEX_SELECT}
            if os.environ.get("CONTACT_EMAIL"):
                q["mailto"] = os.environ["CONTACT_EMAIL"]
            if os.environ.get("OPENALEX_API_KEY"):
                q["api_key"] = os.environ["OPENALEX_API_KEY"]
            with get("https://api.openalex.org/works?" + urllib.parse.urlencode(q)) as r:
                results = json.load(r).get("results", [])
            for w in results:
                f.write((json.dumps(w, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8"))
            f.flush()
            st = {"done": i + len(batch), "offset": f.tell(), "found": st["found"] + len(results)}
            save_progress(prog, st)
            time.sleep(0.2)
    os.replace(part, os.path.join(d, name))
    os.remove(prog)
    out = os.path.join(d, name)
    with_abs = 0
    for line in open(out, encoding="utf-8"):
        if json.loads(line).get("abstract_inverted_index"):
            with_abs += 1
    record(d, "openalex", {"file": name, "requestedDois": len(dois), "works": st["found"], "worksWithAbstract": with_abs,
                           "select": OPENALEX_SELECT, "batchSize": 50, "sha256": sha256(out), "bytes": os.path.getsize(out)})
    print(f"[fetch] openalex: {st['found']} works ({with_abs} with abstract) for {len(dois)} DOIs")


def crossref(a):
    d = out_dir(a)
    need_dblp(d)
    name = "crossref-works.jsonl"
    if os.path.exists(os.path.join(d, name)) and not a.force:
        print(f"[fetch] {name} exists (use --force)")
        return
    if a.force:
        for ext in (".part", ".progress"):
            if os.path.exists(os.path.join(d, name + ext)):
                os.remove(os.path.join(d, name + ext))
    dois = sample_dois(d, a.max_dois, a.doi_order)
    part, prog, st = resume_state(d, name)
    t0 = time.time()
    with open(part, "ab") as f:
        for i in range(st["done"], len(dois)):
            if a.max_seconds and time.time() - t0 > a.max_seconds:
                print(f"[fetch] crossref partial: {st['done']} of {len(dois)} DOIs requested; rerun to resume")
                return
            url = "https://api.crossref.org/works/" + urllib.parse.quote(dois[i], safe="")
            if os.environ.get("CONTACT_EMAIL"):
                url += "?mailto=" + urllib.parse.quote(os.environ["CONTACT_EMAIL"])
            found = st["found"]
            try:
                with get(url) as r:
                    msg = json.load(r)["message"]
                item = {k: msg[k] for k in CROSSREF_KEEP if k in msg}
                f.write((json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8"))
                f.flush()
                found += 1
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    raise
            st = {"done": i + 1, "offset": f.tell(), "found": found}
            if (i + 1) % 20 == 0 or i + 1 == len(dois):
                save_progress(prog, st)
            time.sleep(0.1)
    os.replace(part, os.path.join(d, name))
    os.remove(prog)
    out = os.path.join(d, name)
    record(d, "crossref", {"file": name, "requestedDois": len(dois), "found": st["found"], "keptFields": list(CROSSREF_KEEP),
                           "doiOrder": a.doi_order, "sha256": sha256(out), "bytes": os.path.getsize(out)})
    print(f"[fetch] crossref: {st['found']} of {len(dois)} DOIs")


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


def dblp_records(path):
    """Yield (year, raw record bytes) from a dblp-sample.xml written by this script (ordered by year, hash)."""
    rec_re = re.compile(rb"<(" + b"|".join(t.encode() for t in RETAINED) + rb")[ >].*?</\1>", re.S)
    year_re = re.compile(rb"<year>(\d{4})</year>")
    data = open(path, "rb").read()
    for m in rec_re.finditer(data):
        ym = year_re.search(m.group(0))
        yield (int(ym.group(1)) if ym else 0), m.group(0)


def norm_doi(v):
    if not v:
        return None
    v = v.strip().lower()
    for p in ("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/"):
        if v.startswith(p):
            v = v[len(p):]
    return v


def subset(a):
    """Derived subset of an existing local snapshot folder (--parent): first records//years per year of the parent
    dblp-sample.xml (the parent is ordered by (year, hash), so this equals the stratified rule at the smaller size),
    and the parent OpenAlex and Crossref rows whose DOI is a DOI of the subset. No network access."""
    if not a.parent or not a.out or not a.years:
        sys.exit("[subset] needs --parent DIR, --out DIR and --years A-B")
    parent, d = os.path.abspath(a.parent), out_dir(a)
    if d == parent:
        sys.exit("[subset] --out must differ from --parent")
    y0, y1 = (int(x) for x in a.years.split("-"))
    quota = a.records // (y1 - y0 + 1)
    counts, out = {y: 0 for y in range(y0, y1 + 1)}, os.path.join(d, "dblp-sample.xml")
    with open(out, "wb") as f:
        f.write(DBLP_HEADER)
        for y, rec in dblp_records(os.path.join(parent, "dblp-sample.xml")):
            if y in counts and counts[y] < quota:
                counts[y] += 1
                f.write(rec + b"\n")
        f.write(b"</dblp>\n")
    dois = set(sample_dois(d, 10 ** 9))
    pr = json.load(open(os.path.join(parent, "retrieval.json")))
    info = {"dblp": dict(file="dblp-sample.xml", records=sum(counts.values()), minAuthors=pr["dblp"].get("minAuthors"), years=a.years,
                         perYearQuota=quota, selectedPerYear={str(k): v for k, v in counts.items()}, sha256=sha256(out),
                         bytes=os.path.getsize(out), dblpDois=len(dois), parentSnapshot=os.path.basename(parent),
                         parentDblpSha256=pr["dblp"]["sha256"],
                         rule="first perYearQuota records per year of the parent dblp-sample.xml (ordered by year, hash); equals the stratified hash rule at the smaller quota")}
    for name, key, field in (("openalex-works.jsonl", "openalex", "doi"), ("crossref-works.jsonl", "crossref", "DOI")):
        src, dst, n, nabs, seen = os.path.join(parent, name), os.path.join(d, name), 0, 0, set()
        with open(src, encoding="utf-8") as fi, open(dst, "w", encoding="utf-8") as fo:
            for line in fi:
                row = json.loads(line)
                doi = norm_doi(row.get(field))
                if doi in dois:
                    fo.write(line if line.endswith("\n") else line + "\n")
                    n += 1
                    seen.add(doi)
                    nabs += 1 if row.get("abstract_inverted_index") else 0
        e = {"file": name, "works": n, "distinctDois": len(seen), "requestedDois": len(dois), "sha256": sha256(dst), "bytes": os.path.getsize(dst),
             "parentSnapshot": os.path.basename(parent), "parentSha256": pr[key]["sha256"],
             "filter": "parent rows whose normalized DOI is in the subset DBLP DOIs; no new request"}
        if key == "openalex":
            e["worksWithAbstract"] = nabs
        else:
            e["found"] = n
        info[key] = e
    shutil.copyfile(os.path.join(parent, "dblp.dtd"), os.path.join(d, "dblp.dtd"))
    info["dblp-dtd"] = {"file": "dblp.dtd", "sha256": sha256(os.path.join(d, "dblp.dtd")), "bytes": os.path.getsize(os.path.join(d, "dblp.dtd")),
                        "copiedFrom": os.path.basename(parent)}
    for k, v in info.items():
        record(d, k, v)
    print(f"[subset] {sum(counts.values())} DBLP records, {len(dois)} DOIs, openalex {info['openalex']['works']}, crossref {info['crossref']['works']} -> {d}")


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
    p.add_argument("command", choices=["download", "dblp", "openalex", "crossref", "orcid", "verify", "dtd", "subset"])
    p.add_argument("--snapshot", default=datetime.date.today().isoformat(), help="retrieval date label, YYYY-MM-DD")
    p.add_argument("--out", help="output directory (default datasets/samples/local/<snapshot>)")
    p.add_argument("--records", type=int, default=1000)
    p.add_argument("--min-authors", type=int, default=0)
    p.add_argument("--max-bytes", type=int, default=128 * 1024 * 1024, help="decompressed DBLP bytes scanned, at most")
    p.add_argument("--max-dois", type=int, default=50)
    p.add_argument("--max", type=int, default=20, help="ORCID iDs, at most")
    p.add_argument("--url", default=DBLP_URL)
    p.add_argument("--years", help="stratified selection over YYYY-YYYY (see STRATIFIED RULE)")
    p.add_argument("--gz", help="local dblp.xml.gz to read (default DIR/dblp.xml.gz when present, else stream the URL)")
    p.add_argument("--doi-order", choices=["sorted", "hash"], default="sorted")
    p.add_argument("--max-seconds", type=int, default=0, help="stop a resumable step cleanly after this many seconds (0 = no limit)")
    p.add_argument("--allow-large", action="store_true")
    p.add_argument("--parent", help="subset: parent snapshot folder")
    p.add_argument("--force", action="store_true")
    a = p.parse_args()
    check_snapshot(a.snapshot)
    {"download": download, "dblp": dblp, "openalex": openalex, "crossref": crossref, "orcid": orcid, "verify": verify, "dtd": dtd, "subset": subset}[a.command](a)


if __name__ == "__main__":
    main()
