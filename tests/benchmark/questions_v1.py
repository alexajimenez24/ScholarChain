"""Benchmark question set v1: fixed subjects and expected answers computed from the stores.

Owner: test-engineer (Phase 09 step 3). Read-only on the data.

The expected answers are computed here, directly from the JSON-lines export of Spark run
run-ccf619c509611f06 as its load manifests name it, with no call into the runtime workflow
and no import of any ScholarChain module. The Scala suite
tests/adversarial/scholarchain/adversarial/rag/BenchmarkQuestionSetSuite.scala recomputes
every expected answer with a second, independent implementation and must agree with the
frozen file this script writes.

Usage (from the repository root):
  python3 -I tests/benchmark/questions_v1.py <spark run dir> tests/adversarial/resources/benchmark/questions-v1.json

<spark run dir> is .../spark-out/spark/snapshot=2026-11-24/run_id=run-ccf619c509611f06.
"""

import collections
import json
import os
import sys
import unicodedata
from collections import deque

VERSION = "benchmark-questions-v1"
GRAPH_RUN = "run-ccf619c509611f06"
ER_RUN = "run-cb5a0421c406737d"
W1, W2 = "2010-2014", "2015-2026"
BOUNDS = {W1: (2010, 2014), W2: (2015, 2026)}


def die(msg):
    sys.stderr.write("questions_v1: " + msg + "\n")
    sys.exit(2)


def manifest(run_dir, name):
    with open(os.path.join(run_dir, "load-manifests", name), encoding="utf-8") as h:
        m = json.load(h)
    if m["runId"] != GRAPH_RUN:
        die(f"{name} names run {m['runId']}, expected {GRAPH_RUN}")
    return m


def read_partition(section, partition):
    """Rows of one partition, checked against the manifest's row count."""
    jl = section["jsonLines"]
    files = [f["file"] for f in jl["files"] if f["file"].startswith(partition + "/")]
    if not files:
        die(f"no file for {partition}")
    rows = []
    for f in files:
        with open(os.path.join(jl["path"], f), encoding="utf-8") as h:
            rows.extend(json.loads(l) for l in h if l.strip())
    want = jl["rowsByPartition"][partition]
    if len(rows) != want:
        die(f"{partition}: {len(rows)} rows, manifest says {want}")
    return rows


def tokens(name):
    s = unicodedata.normalize("NFKD", name)
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    out = []
    for raw in s.replace("-", " ").replace(".", " ").split():
        t = "".join(c for c in raw if c.isalnum())
        if t:
            out.append(t)
    return out


def main(run_dir, out_path):
    neptune = manifest(run_dir, "neptune.json")
    dynamo = manifest(run_dir, "dynamodb.json")
    metrics_section = dynamo["tables"][0]["source"]

    researchers = {r["nodeId"]: r["properties"] for r in read_partition(neptune["nodes"], "label=Researcher")}
    works = {r["nodeId"]: r["properties"] for r in read_partition(neptune["nodes"], "label=Work")}
    topics = {r["nodeId"]: r["properties"]["name"] for r in read_partition(neptune["nodes"], "label=Topic")}
    insts = {r["nodeId"]: r["properties"]["name"] for r in read_partition(neptune["nodes"], "label=Institution")}
    authored = collections.defaultdict(set)
    for e in read_partition(neptune["edges"], "edgeType=AUTHORED"):
        authored[e["toId"]].add(e["fromId"])
    has_topic = collections.defaultdict(set)
    for e in read_partition(neptune["edges"], "edgeType=HAS_TOPIC"):
        has_topic[e["toId"]].add(e["fromId"])
    affiliated = collections.defaultdict(set)
    for e in read_partition(neptune["edges"], "edgeType=AFFILIATED_WITH"):
        affiliated[e["toId"]].add(e["fromId"])

    metrics, adj = {}, {}
    for w in (W1, W2):
        metrics[w] = {r["researcherId"]: r for r in read_partition(metrics_section, f"windowLabel={w}")}
        a = collections.defaultdict(dict)
        for e in read_partition(neptune["coauthorEdges"], f"windowLabel={w}"):
            a[e["researcherA"]][e["researcherB"]] = e
            a[e["researcherB"]][e["researcherA"]] = e
        adj[w] = a

    key_sorted = collections.Counter()
    key_ends = collections.Counter()
    exact = collections.defaultdict(list)
    for rid, p in researchers.items():
        t = tokens(p["displayName"])
        key_sorted[" ".join(sorted(t))] += 1
        if t:
            key_ends[frozenset((t[0], t[-1]))] += 1
        exact[p["displayName"]].append(rid)

    def resolve(name):
        ids = exact.get(name, [])
        if len(ids) != 1:
            die(f"{name!r}: {len(ids)} nodes with that display name")
        t = tokens(name)
        if key_sorted[" ".join(sorted(t))] != 1 or key_ends[frozenset((t[0], t[-1]))] != 1:
            die(f"{name!r} is not unique under the token keys")
        p = researchers[ids[0]]
        if p["identityConflict"] != "false" or p["unresolved"] != "false" or float(p["identityConfidence"]) < 0.9:
            die(f"{name!r} has a weak identity")
        return ids[0]

    def row(w, rid):
        r = metrics[w].get(rid)
        if r is None:
            die(f"{rid} has no metrics row in {w}")
        return r

    def metric_fields(r):
        keep = ["publicationCount", "distinctCoauthorCount", "totalCoauthorOccurrences",
                "averageCoauthorsPerPaper", "maximumCoauthorsOnOnePaper", "maximumTeamWorkIds",
                "weightedDegree", "componentId", "componentSize", "communityId", "bridgeScore",
                "participationCoefficient", "crossCommunityDegree", "communitiesConnected", "coreness",
                "metricVersion"]
        return {k: r[k] for k in keep}

    def ranking(w, ids, field):
        ordered = sorted(ids, key=lambda i: (-metrics[w][i][field], i))
        if len(ordered) < 2 or metrics[w][ordered[0]][field] == metrics[w][ordered[1]][field]:
            die(f"ranking by {field} in {w} has a tie at the top")
        top = ordered[0]
        return {"metric": field, "candidates": len(ordered), "top": top,
                "topName": researchers[top]["displayName"], "topValue": metrics[w][top][field],
                "runnerUp": ordered[1], "runnerUpValue": metrics[w][ordered[1]][field]}

    def bfs(w, s):
        dist, count = {s: 0}, {s: 1}
        q = deque([s])
        while q:
            u = q.popleft()
            for v in sorted(adj[w][u]):
                if v not in dist:
                    dist[v] = dist[u] + 1
                    count[v] = 0
                    q.append(v)
                if dist[v] == dist[u] + 1:
                    count[v] += count[u]
        return dist, count

    def shortest(w, s, t):
        dist, count = bfs(w, s)
        if t not in dist:
            return {"found": False, "reachableFromSource": len(dist)}
        if count[t] != 1:
            die(f"{s}->{t} in {w} has {count[t]} shortest paths")
        path = [t]
        while path[-1] != s:
            v = path[-1]
            path.append(next(u for u in adj[w][v] if dist.get(u) == dist[v] - 1))
        path.reverse()
        hops = []
        for a, b in zip(path, path[1:]):
            e = adj[w][a][b]
            hops.append({"from": a, "to": b, "edgeId": e["edgeId"],
                         "jointPublicationCount": e["jointPublicationCount"],
                         "supportingWorkIds": sorted(e["supportingWorkIds"])})
        return {"found": True, "hops": len(path) - 1, "path": path,
                "pathNames": [researchers[i]["displayName"] for i in path], "hopEvidence": hops}

    def pair(w, a, b):
        e = adj[w][a].get(b)
        if e is None:
            return {"found": False}
        return {"found": True, "edgeId": e["edgeId"], "jointPublicationCount": e["jointPublicationCount"],
                "firstCollaborationYear": e["firstCollaborationYear"],
                "lastCollaborationYear": e["lastCollaborationYear"],
                "supportingWorkIds": sorted(e["supportingWorkIds"]),
                "titles": {wid: works[wid]["title"] for wid in sorted(e["supportingWorkIds"]) if wid in works}}

    def unique_name(name, names):
        t = set(tokens(name))
        hits = [n for n in names if t <= set(tokens(n))]
        if hits != [name]:
            die(f"{name!r} is token-contained in {len(hits)} names")

    def topic_rank(w, name):
        unique_name(name, list(topics.values()))
        tid = next(i for i, n in topics.items() if n == name)
        all_works = has_topic[tid]
        if len(all_works) > 30:
            die(f"topic {name!r} has {len(all_works)} works in total, above the 30-work cap")
        lo, hi = BOUNDS[w]
        in_w = sorted(x for x in all_works if lo <= int(works[x]["year"]) <= hi)
        ids = {r for x in in_w for r in authored[x] if r in metrics[w]}
        if len(ids) > 200:
            die(f"topic {name!r} has more than 200 researchers")
        out = ranking(w, ids, "distinctCoauthorCount")
        out.update({"topicId": tid, "topicWorksTotal": len(all_works), "topicWorksInWindow": len(in_w)})
        return out

    def institution_rank(w, name):
        unique_name(name, list(insts.values()))
        iid = next(i for i, n in insts.items() if n == name)
        members = affiliated[iid]
        if len(members) > 100:
            die(f"institution {name!r} has {len(members)} affiliated researchers, above 100 rows")
        ids = {r for r in members if r in metrics[w]}
        out = ranking(w, ids, "distinctCoauthorCount")
        out.update({"institutionId": iid, "affiliatedTotal": len(members)})
        return out

    Q = []

    def add(cat, op, question, window, constraints, expected, method, extra_windows=()):
        Q.append({"id": f"bq-v1-{len(Q) + 1:02d}", "category": cat, "operation": op,
                  "question": question, "window": window, "extraWindows": list(extra_windows),
                  "constraints": constraints, "expected": expected, "method": method})

    def subj(*names):
        return {n: resolve(n) for n in names}

    m_rank = ("Rank every researcher-metrics row of the window by the named field, descending, "
              "ties broken by researcher ID; the top value must be strictly above the runner-up.")
    m_row = "Read the researcher-metrics row (researcherId, windowLabel) and copy the named fields."
    m_pair = ("Look up the coauthor-edges row of the window whose unordered pair is the two IDs; "
              "titles from the Work graph nodes.")
    m_path = ("Breadth-first search over the window's coauthor-edges rows (undirected); the path "
              "is accepted only when exactly one shortest path exists; each hop's edge row is copied.")

    # 1. Coauthor breadth (C_r).
    for w in (W1, W2):
        add("coauthor-breadth", "RESEARCHER_RANKING",
            f"Which researcher has the most distinct coauthors in {w}?", w,
            {"metric": "distinctCoauthorCount", "limit": 1},
            ranking(w, metrics[w].keys(), "distinctCoauthorCount"), m_rank)
    s = subj("Haizhou Li")
    r = row(W1, s["Haizhou Li"])
    add("coauthor-breadth", "RESEARCHER_RANKING",
        f'How many distinct coauthors does "Haizhou Li" have in {W1}?', W1,
        {"researcherNames": ["Haizhou Li"]},
        {"researcherId": s["Haizhou Li"], "distinctCoauthorCount": r["distinctCoauthorCount"],
         "weightedDegree": r["weightedDegree"], "publicationCount": r["publicationCount"]}, m_row)

    # 2. Maximum team size (M_r and the works responsible).
    for name, w in (("Eli De Poorter", W1), ("Liang Ge", W2), ("Zongpeng Li", W2)):
        s = subj(name)
        r = row(w, s[name])
        add("maximum-team-size", "MAX_TEAM_SIZE",
            f'What is the maximum team size of "{name}" in {w}, and which publication produced it?', w,
            {"researcherNames": [name]},
            {"researcherId": s[name], "maximumCoauthorsOnOnePaper": r["maximumCoauthorsOnOnePaper"],
             "maximumTeamWorkIds": sorted(r["maximumTeamWorkIds"]),
             "averageCoauthorsPerPaper": r["averageCoauthorsPerPaper"]}, m_row)

    # 3. Direct collaboration.
    for a, b, w in (("Shaojun Wei", "Leibo Liu", W1), ("Hans Hagen", "Jan Mohring", W1),
                    ("Fan Bai", "Manish Jain", W1)):
        s = subj(a, b)
        e = pair(w, s[a], s[b])
        e.update({"researcherA": s[a], "researcherB": s[b]})
        add("direct-collaboration", "DIRECT_COAUTHORS",
            f'Has "{a}" collaborated with "{b}" in {w}, and how many joint publications do they have?', w,
            {"researcherNames": [a, b]}, e, m_pair)

    # 4. Shortest paths.
    for a, b, w in (("Fan Bai", "Matthew Brown", W1), ("Liang Ge", "Yunpu Ma", W2),
                    ("Fan Bai", "Haizhou Li", W1)):
        s = subj(a, b)
        add("shortest-path", "SHORTEST_PATH",
            f'What is the shortest coauthorship path from "{a}" to "{b}" in {w}?', w,
            {"researcherNames": [a, b]}, shortest(w, s[a], s[b]), m_path)

    # 5. Components.
    for name, w in (("Haizhou Li", W1), ("Zongpeng Li", W2), ("Ruigang Yang", W2)):
        s = subj(name)
        r = row(w, s[name])
        dist, _ = bfs(w, s[name])
        if len(dist) != r["componentSize"]:
            die(f"{name}: BFS reaches {len(dist)}, componentSize is {r['componentSize']}")
        add("components", "COMPONENT_LOOKUP",
            f'Which connected component does "{name}" belong to in {w}, and how many researchers does it have?',
            w, {"researcherNames": [name]},
            {"researcherId": s[name], "componentId": r["componentId"], "componentSize": r["componentSize"]},
            m_row + " componentSize is cross-checked by a breadth-first search of the window's edges.")

    # 6. Communities (community size is not in any store the runtime reads).
    for name, w in (("Fan Bai", W1), ("Haizhou Li", W1), ("Liang Ge", W2)):
        s = subj(name)
        r = row(w, s[name])
        members = sum(1 for x in metrics[w].values() if x["communityId"] == r["communityId"])
        add("communities", "COMMUNITY_COMPARISON",
            f'Which collaboration community does "{name}" belong to in {w}, and how many members does it have?',
            w, {"researcherNames": [name]},
            {"researcherId": s[name], "communityId": r["communityId"],
             "crossCommunityDegree": r["crossCommunityDegree"],
             "communitiesConnected": r["communitiesConnected"],
             "communitySize": "MISSING_EVIDENCE",
             "oracleMemberCountNotExpectedFromWorkflow": members},
            m_row + " Community size must be stated as missing evidence (no runtime store holds it); "
            "the oracle member count is the number of metrics rows of the window with that communityId, "
            "recorded for the human only.")

    # 7. Bridge researchers (sampled-brandes-betweenness in bridgeScore).
    for w in (W1, W2):
        add("bridge-researchers", "BRIDGE_RANKING",
            f"Which researcher is the most important bridge between collaboration communities in {w}?", w,
            {"metric": "bridgeScore", "limit": 1}, ranking(w, metrics[w].keys(), "bridgeScore"), m_rank)
    s = subj("Chin Keong Ho")
    r = row(W1, s["Chin Keong Ho"])
    add("bridge-researchers", "BRIDGE_RANKING",
        f'What is the bridge score of "Chin Keong Ho" in {W1}, and how many communities does this researcher connect?',
        W1, {"researcherNames": ["Chin Keong Ho"]},
        {"researcherId": s["Chin Keong Ho"], "bridgeScore": r["bridgeScore"],
         "crossCommunityDegree": r["crossCommunityDegree"], "communitiesConnected": r["communitiesConnected"],
         "distinctCoauthorCount": r["distinctCoauthorCount"]}, m_row)

    # 8. Temporal change.
    for name in ("Shaojun Wei", "Dacheng Tao", "Leibo Liu"):
        s = subj(name)
        a, b = row(W1, s[name]), row(W2, s[name])
        add("temporal-change", "TEMPORAL_COMPARISON",
            f'How did the number of distinct coauthors of "{name}" change from {W1} to {W2}?', W1,
            {"researcherNames": [name], "windows": [W1, W2]},
            {"researcherId": s[name],
             "byWindow": {W1: {k: a[k] for k in ("distinctCoauthorCount", "publicationCount", "maximumCoauthorsOnOnePaper")},
                          W2: {k: b[k] for k in ("distinctCoauthorCount", "publicationCount", "maximumCoauthorsOnOnePaper")}},
             "distinctCoauthorChange": b["distinctCoauthorCount"] - a["distinctCoauthorCount"]},
            m_row + " Done for both windows; the change is the later value minus the earlier.", (W2,))

    # 9. Topics.
    for name, w in (("Network Time Synchronization Technologies", W1),
                    ("Educational Games and Gamification", W1),
                    ("Multimodal Machine Learning Applications", W2)):
        add("topics", "TOPIC_FILTER",
            f"Which researcher has the most distinct coauthors in {name} in {w}?", w,
            {"topic": name, "metric": "distinctCoauthorCount", "limit": 1}, topic_rank(w, name),
            "Topic node by exact name (its tokens are contained in no other topic name); its works through "
            "HAS_TOPIC (at most 30 in total), kept when the Work year is in the window; their authors through "
            "AUTHORED that have a metrics row in the window; ranked as above.")

    # 10. Institutions.
    for name, w in (("Nankai University", W1), ("Arizona State University", W2),
                    ("Huanggang Normal University", W2)):
        add("institutions", "INSTITUTION_FILTER",
            f"Which researcher at {name} has the most distinct coauthors in {w}?", w,
            {"institution": name, "metric": "distinctCoauthorCount", "limit": 1}, institution_rank(w, name),
            "Institution node by exact name (tokens contained in no other institution name); researchers "
            "through AFFILIATED_WITH (at most 100) that have a metrics row in the window; ranked as above.")

    # 11. Evidence.
    for a, b, w in (("Shaojun Wei", "Shouyi Yin", W1), ("Vignesh Ravichandran", "Balamurali Murugesan", W2)):
        s = subj(a, b)
        e = pair(w, s[a], s[b])
        if not e["found"]:
            die(f"no edge {a} {b}")
        e.update({"researcherA": s[a], "researcherB": s[b]})
        add("evidence", "DIRECT_COAUTHORS",
            f'Which publications support the collaboration between "{a}" and "{b}" in {w}?', w,
            {"researcherNames": [a, b]}, e, m_pair)
    s = subj("Fan Bai", "Manish Jain")
    add("evidence", "SHORTEST_PATH",
        f'Which publications support each hop of the shortest coauthorship path from "Fan Bai" to "Manish Jain" in {W1}?',
        W1, {"researcherNames": ["Fan Bai", "Manish Jain"]}, shortest(W1, s["Fan Bai"], s["Manish Jain"]), m_path)

    ambiguous = "Wei Wang"
    t = tokens(ambiguous)
    gate = {"ambiguousName": ambiguous,
            "exactDisplayNameNodes": sorted(exact.get(ambiguous, [])),
            "sortedTokenKeyNodes": key_sorted[" ".join(sorted(t))]}
    if len(gate["exactDisplayNameNodes"]) < 2:
        die("the ambiguous fixture name must have at least two nodes")

    out = {"version": VERSION, "graphRunId": GRAPH_RUN, "erRunId": ER_RUN, "windows": [W1, W2],
           "generator": "tests/benchmark/questions_v1.py",
           "verifier": "tests/adversarial/scholarchain/adversarial/rag/BenchmarkQuestionSetSuite.scala",
           "rowsRead": {"Researcher": len(researchers), "Work": len(works), "Topic": len(topics),
                        "Institution": len(insts),
                        **{f"metrics {w}": len(metrics[w]) for w in (W1, W2)},
                        **{f"coauthor-edges {w}": sum(len(v) for v in adj[w].values()) // 2 for w in (W1, W2)}},
           "gate": gate, "questions": Q}
    with open(out_path, "w", encoding="utf-8") as h:
        json.dump(out, h, indent=2, sort_keys=True, ensure_ascii=False)
        h.write("\n")
    cats = collections.Counter(q["category"] for q in Q)
    print(f"{VERSION}: {len(Q)} questions, categories {dict(cats)}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        die("usage: questions_v1.py <spark run dir> <output json>")
    main(sys.argv[1], sys.argv[2])
