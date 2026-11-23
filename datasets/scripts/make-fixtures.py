#!/usr/bin/env python3
"""Writes the SYNTHETIC fixture corpus under datasets/samples/fixtures/.

Every person, title and DOI here is invented for tests (DOIs use the Crossref test prefix 10.5555).
The corpus is a small coauthorship graph whose metrics can be checked by hand:

  community A: Alice Archer, Bob Baker, Carol Chen, Dan Diaz, Wei Wang 0001
  community B: Eve Evans, Frank Fox, Grace Gupta, Hank Hill, Wei Wang 0002
  bridge:      Ivy Ito (coauthors with Carol Chen in A and Eve Evans in B)
  component 2: Yan Yu, Zed Zhou            singleton: Solo Sato (single-author paper)
  large team:  one 12-author paper led by Alice Archer
It is NOT real data and must never be reported as a result. The DBLP fixture declares ISO-8859-1 like the real dump; it is pure ASCII (entities such as &uuml; are
references, as in dblp.dtd). Fixture ORCID iDs returned HTTP 404 from pub.orcid.org on 2026-11-22, i.e. no public
record; that is an observation, not a reservation by ORCID. Usage: make-fixtures.py [outdir]
"""
import json, os, sys

def orcid(first15):
    total = 0
    for ch in first15:
        total = (total + int(ch)) * 2
    r = (12 - total % 11) % 11
    d = first15 + ("X" if r == 10 else str(r))
    return f"{d[0:4]}-{d[4:8]}-{d[8:12]}-{d[12:16]}"

CAROL = orcid("000000099000001")
EVE = orcid("000000099000002")
TEAM = [f"Team Member {i:02d}" for i in range(1, 11)]
# (key, type, title, year, venue_field, venue, authors[(name, orcid)], doi)
RECORDS = [
  ("conf/fixa/ArcherB15", "inproceedings", "Scalable <i>Graph</i> Partitioning", 2015, "booktitle", "FIXA",
   [("Alice Archer", None), ("Bob Baker", None)], "10.5555/sc.fix.001"),
  ("conf/fixa/ArcherBC16", "inproceedings", "Consistent Hashing Revisited", 2016, "booktitle", "FIXA",
   [("Alice Archer", None), ("Bob Baker", None), ("Carol Chen", CAROL)], "10.5555/sc.fix.002"),
  ("journals/fixj/ChenD17", "article", "Fault Tolerance in M&uuml;ller Networks", 2017, "journal", "FIXJ",
   [("Carol Chen", CAROL), ("Dan Diaz", None)], "10.5555/sc.fix.003"),
  ("conf/fixa/BakerW18", "inproceedings", "Distributed Snapshot Protocols", 2018, "booktitle", "FIXA",
   [("Bob Baker", None), ("Wei Wang 0001", None), ("Dan Diaz", None)], "10.5555/sc.fix.004"),
  ("conf/fixa/ArcherD19", "inproceedings", "Replicated Logs at Scale", 2019, "booktitle", "FIXA",
   [("Alice Archer", None), ("Dan Diaz", None)], "10.5555/sc.fix.005"),
  ("conf/fixa/ArcherBC19", "inproceedings", "Consensus under Partial Synchrony", 2019, "booktitle", "FIXA",
   [("Alice Archer", None), ("Bob Baker", None), ("Carol Chen", CAROL)], "10.5555/sc.fix.006"),
  ("conf/fixb/EvansF16", "inproceedings", "Neural Ranking for Retrieval", 2016, "booktitle", "FIXB",
   [("Eve Evans", EVE), ("Frank Fox", None)], "10.5555/sc.fix.101"),
  ("conf/fixb/FoxGH17", "inproceedings", "Dense Passage Indexing", 2017, "booktitle", "FIXB",
   [("Frank Fox", None), ("Grace Gupta", None), ("Hank Hill", None)], "10.5555/sc.fix.102"),
  ("journals/fixk/GuptaE18", "article", "Learning to Rank with Weak Labels", 2018, "journal", "FIXK",
   [("Grace Gupta", None), ("Eve Evans", EVE)], "10.5555/sc.fix.103"),
  ("conf/fixb/FoxW20", "inproceedings", "Query Expansion with Graphs", 2020, "booktitle", "FIXB",
   [("Frank Fox", None), ("Wei Wang 0002", None), ("Hank Hill", None)], "10.5555/sc.fix.104"),
  ("conf/fixc/ItoC20", "inproceedings", "Retrieval for Distributed Logs", 2020, "booktitle", "FIXC",
   [("Ivy Ito", None), ("Carol Chen", CAROL)], "10.5555/sc.fix.201"),
  ("conf/fixc/ItoE21", "inproceedings", "Ranking Replicas", 2021, "booktitle", "FIXC",
   [("Ivy Ito", None), ("Eve Evans", EVE)], "10.5555/sc.fix.202"),
  ("journals/fixz/ZhouY12", "article", "Isolated Topics in Type Theory", 2012, "journal", "FIXZ",
   [("Zed Zhou", None), ("Yan Yu", None)], "10.5555/sc.fix.301"),
  ("journals/fixz/Sato14", "article", "A Solitary Proof", 2014, "journal", "FIXZ",
   [("Solo Sato", None)], "10.5555/sc.fix.302"),
  ("conf/fixa/ArcherTeam22", "inproceedings", "A Large Collaborative Benchmark", 2022, "booktitle", "FIXA",
   [("Alice Archer", None)] + [(t, None) for t in TEAM] + [("Bob Baker", None)], "10.5555/sc.fix.401"),
]

def author_xml(name, oid):
    attr = f' orcid="{oid}"' if oid else ""
    return f"<author{attr}>{name}</author>"

def dblp_xml():
    out = ['<?xml version="1.0" encoding="ISO-8859-1"?>', '<!DOCTYPE dblp SYSTEM "dblp.dtd">', "<dblp>"]
    for key, typ, title, year, vf, venue, authors, doi in RECORDS:
        out.append(f'<{typ} mdate="2026-11-01" key="{key}">')
        out += [author_xml(n, o) for n, o in authors]
        out.append(f"<title>{title}.</title>")
        out.append(f"<year>{year}</year><{vf}>{venue}</{vf}>")
        out.append(f"<ee>https://doi.org/{doi}</ee><url>db/{key}.html</url>")
        out.append(f"</{typ}>")
    out.append('<www mdate="2026-11-01" key="homepages/fix/a"><author>Alice Archer</author><title>Home Page</title></www>')
    out.append('<proceedings mdate="2026-11-01" key="conf/fixa/2019"><editor>Bob Baker</editor><title>FIXA 2019</title><year>2019</year></proceedings>')
    out.append("</dblp>")
    return "\n".join(out) + "\n"

def openalex_lines():
    lines = []
    for i, (key, typ, title, year, vf, venue, authors, doi) in enumerate(RECORDS[:12]):
        ships = []
        for pos, (n, o) in enumerate(authors):
            a = {"display_name": n.replace(" 0001", "").replace(" 0002", "")}
            if o: a["orcid"] = f"https://orcid.org/{o}"
            a["id"] = f"https://openalex.org/A59{sum(map(ord, n)):08d}"  # deterministic fake author ID
            inst = [{"id": "https://openalex.org/I900000001", "display_name": "Fixture University", "country_code": "US"}] if "Chen" in n or "Ito" in n else []
            ships.append({"author_position": "first" if pos == 0 else "middle", "author": a,
                          "raw_author_name": a["display_name"], "institutions": inst})
        topic = {"id": "https://openalex.org/T10001", "display_name": "Distributed Systems"} if venue in ("FIXA", "FIXJ", "FIXC") else {"id": "https://openalex.org/T10002", "display_name": "Information Retrieval"}
        lines.append(json.dumps({"id": f"https://openalex.org/W90000{i:04d}", "doi": f"https://doi.org/{doi}",
            "display_name": title.replace("<i>", "").replace("</i>", "").replace("&uuml;", "ü"),
            "publication_year": year, "type": "article", "authorships": ships, "topics": [topic],
            "primary_location": {"source": {"id": f"https://openalex.org/S8000000{ord(venue[-1])}", "display_name": venue}}}))
    return lines

def crossref_lines():
    lines = []
    for key, typ, title, year, vf, venue, authors, doi in RECORDS[:8]:
        auth = []
        for n, o in authors:
            parts = n.replace(" 0001", "").replace(" 0002", "").split(" ")
            a = {"given": " ".join(parts[:-1]), "family": parts[-1], "sequence": "additional"}
            if o: a["ORCID"] = f"https://orcid.org/{o}"
            auth.append(a)
        lines.append(json.dumps({"DOI": doi.upper(), "title": [title.replace("<i>", "").replace("</i>", "")],
            "author": auth, "issued": {"date-parts": [[year]]}, "container-title": [venue], "type": "proceedings-article"}))
    return lines

def orcid_lines():
    def rec(oid, given, family, dois):
        return json.dumps({"orcid-identifier": {"path": oid},
            "person": {"name": {"given-names": {"value": given}, "family-name": {"value": family}}},
            "activities-summary": {
              "employments": {"affiliation-group": [{"summaries": [{"employment-summary": {"organization": {"name": "Fixture University"}}}]}]},
              "works": {"group": [{"external-ids": {"external-id": [{"external-id-type": "doi", "external-id-value": d}]}} for d in dois]}}})
    return [rec(CAROL, "Carol", "Chen", ["10.5555/sc.fix.002", "10.5555/sc.fix.003", "10.5555/sc.fix.201"]),
            rec(EVE, "Eve", "Evans", ["10.5555/sc.fix.101", "10.5555/sc.fix.103"])]

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "samples", "fixtures")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "dblp-fixture.xml"), "w", encoding="iso-8859-1") as f: f.write(dblp_xml())
    for name, lines in (("openalex-works-fixture.jsonl", openalex_lines()), ("crossref-works-fixture.jsonl", crossref_lines()), ("orcid-records-fixture.jsonl", orcid_lines())):
        with open(os.path.join(out, name), "w", encoding="utf-8") as f: f.write("\n".join(lines) + "\n")
    print(f"wrote synthetic fixtures to {out} (CAROL={CAROL}, EVE={EVE})")

if __name__ == "__main__":
    main()
