package scholarchain.er.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scholarchain.core.*

class ReviewQueueSuite extends munit.FunSuite:
  private val RunId = "run-0123456789abcdef"
  private val cfg = ReviewQueueConfig.load().fold(e => throw new AssertionError(e), identity)
  private val small = cfg.copy(targetRows = 12, minRows = 6)

  // ------------------------------------------------------------------ fixtures

  private def prov(src: SourceType, rec: String, schema: String) =
    Provenance(src, rec, "2026-11-22", RunId, schema, Some("norm-v1"))

  private def mention(
      work: String,
      pos: Int,
      name: String,
      src: SourceType,
      orcid: Option[String] = None,
      aff: Option[String] = None
  ) =
    AuthorMention(
      s"m:${Hashing.short(s"$work#$pos", 20)}",
      work,
      pos,
      name,
      name.toLowerCase,
      orcid,
      None,
      aff,
      prov(src, work, "author-mention-v1")
    )

  private def work(
      id: String,
      title: String,
      src: SourceType,
      names: Vector[String],
      doi: Option[String] = None,
      year: Option[Int] = Some(2014),
      venue: Option[String] = Some("Computer Vision"),
      insts: Vector[String] = Vector.empty,
      orcids: Map[Int, String] = Map.empty
  ) =
    WorkRecord(
      "work-v1",
      id,
      src,
      id,
      "article",
      title,
      title.toLowerCase,
      year,
      None,
      venue,
      doi,
      None,
      None,
      Vector.empty,
      names.zipWithIndex.map((n, i) => mention(id, i, n, src, orcids.get(i))),
      Vector.empty,
      insts,
      None,
      prov(src, id, "work-v1")
    )

  private def cand(
      kind: EntityKind,
      s: String,
      t: String,
      f: Map[String, Double],
      run: String = RunId
  ) =
    val k = kind.toString.toLowerCase
    CandidateView(s"mc:$k:${Hashing.short(s"$s|$t", 20)}", kind, s, t, f, run)

  /** N work pairs per stratum, all traceable to the index below. */
  private def fixture(n: Int): (Vector[CandidateView], RecordIndex) =
    val works = (0 until 3 * n).flatMap { i =>
      Vector(
        work(
          s"dblp:w/$i",
          s"Title, \"quoted\" number $i",
          SourceType.DBLP,
          Vector("Ana Ñúñez", s"Bo Li$i")
        ),
        work(
          s"openalex:W$i",
          s"Title number $i",
          SourceType.OPENALEX,
          Vector("Ana Nunez"),
          doi = Some(s"10.1/$i"),
          insts = Vector("openalex:I1")
        )
      )
    }
    val strata = Vector(
      Map("doi_equal" -> 1.0, "title_jaccard" -> 1.0),
      Map("title_jaccard" -> 0.6, "author_overlap" -> 0.6),
      Map("doi_conflict" -> 1.0, "title_jaccard" -> 1.0)
    )
    val cs = (0 until 3 * n).toVector.map(i =>
      cand(EntityKind.WORK, s"dblp:w/$i", s"openalex:W$i", strata(i % 3))
    )
    val inst = Institution(
      "openalex:I1",
      "Universität, Zürich",
      Some("CH"),
      None,
      prov(SourceType.OPENALEX, "I1", "institution-v1")
    )
    (
      cs,
      RecordIndex(works.map(w => w.workId -> w).toMap, Map.empty, Map(inst.institutionId -> inst))
    )

  /** Minimal RFC 4180 reader for the round-trip checks. */
  private def parseCsv(text: String): Vector[Vector[String]] =
    val rows = Vector.newBuilder[Vector[String]]
    var row = Vector.newBuilder[String]
    val cell = new StringBuilder
    var i = 0
    var quoted = false
    while i < text.length do
      val ch = text(i)
      if quoted then
        if ch == '"' && i + 1 < text.length && text(i + 1) == '"' then { cell += '"'; i += 1 }
        else if ch == '"' then quoted = false
        else cell += ch
      else if ch == '"' then quoted = true
      else if ch == ',' then { row += cell.toString; cell.clear() }
      else if ch == '\r' && i + 1 < text.length && text(i + 1) == '\n' then
        row += cell.toString; cell.clear(); rows += row.result(); row = Vector.newBuilder; i += 1
      else cell += ch
      i += 1
    rows.result()

  // ------------------------------------------------------------------ tests

  test("the default config loads, validates and carries the queue version") {
    assertEquals(cfg.queueVersion, "er-review-v1")
    assert(cfg.minRows >= 250 && cfg.targetRows >= cfg.minRows)
    assert(ReviewQueueConfig.validate(cfg).isEmpty)
    assert(
      ReviewQueueConfig
        .validate(cfg.copy(likelyNonMatchBelow = 0.9, likelyMatchAtLeast = 0.5))
        .nonEmpty
    )
    assert(ReviewQueueConfig.validate(cfg.copy(targetRows = 10, minRows = 20)).nonEmpty)
    assert(ReviewQueueConfig.validate(cfg.copy(workWeights = Map("x" -> 0.0))).nonEmpty)
  }

  test("columns are exactly the agreed order, ending with label and note") {
    val side = Vector(
      "source",
      "record_id",
      "name_or_title",
      "orcid_or_doi",
      "affiliations",
      "coauthors",
      "venues_years",
      "titles"
    )
    val expected = Vector("pair_id", "kind") ++ side.map("left_" + _) ++ side.map(
      "right_" + _
    ) ++ Vector("label", "note")
    assertEquals(ReviewQueue.Columns, expected)
    assertEquals(ReviewQueue.Columns.size, 20)
    val (cs, idx) = fixture(4)
    val built = ReviewQueue.build(cs, idx, small).fold(e => fail(e), identity)
    assertEquals(parseCsv(built.csv).head, expected)
  }

  test("no score, path, stratum or predicted label reaches the file; label and note are empty") {
    val (cs, idx) = fixture(4)
    val built = ReviewQueue.build(cs, idx, small).fold(e => fail(e), identity)
    val table = parseCsv(built.csv)
    val forbidden = Vector(
      "score",
      "confidence",
      "similarity",
      "stratum",
      "path",
      "predict",
      "decision",
      "outcome"
    )
    assert(table.head.forall(h => !forbidden.exists(h.toLowerCase.contains)), table.head)
    val strata = Stratum.values.map(_.toString).toVector ++ Vector(
      "MATCH",
      "NON_MATCH",
      "UNSURE",
      "ACCEPT",
      "REJECT"
    )
    assert(strata.forall(s => !built.csv.contains(s)))
    table.tail.foreach { r =>
      assertEquals(r.size, 20)
      assertEquals(r(18), "")
      assertEquals(r(19), "")
    }
    // no numeric similarity value leaks into any cell
    assert(!built.csv.contains("0.6") && !built.csv.contains("1.0"))
  }

  test("CSV quoting handles commas, quotes, newlines, edge spaces and Unicode") {
    assertEquals(ReviewQueue.csvField("plain"), "plain")
    assertEquals(ReviewQueue.csvField("a,b"), "\"a,b\"")
    assertEquals(ReviewQueue.csvField("say \"hi\""), "\"say \"\"hi\"\"\"")
    assertEquals(ReviewQueue.csvField("line1\nline2"), "\"line1\nline2\"")
    assertEquals(ReviewQueue.csvField("cr\rx"), "\"cr\rx\"")
    assertEquals(ReviewQueue.csvField(" lead"), "\" lead\"")
    assertEquals(ReviewQueue.csvField("Ñúñez Zürich 王伟"), "Ñúñez Zürich 王伟")
    val side = ReviewSide(
      "DBLP",
      "m:1",
      "Wang, \"Wei\"\nJr",
      "",
      Vector("Zürich, CH"),
      Vector("a,b", "c"),
      Vector(),
      Vector("T \"x\"")
    )
    val row = ReviewRow("mc:researcher:1", EntityKind.RESEARCHER, side, side.copy(recordId = "m:2"))
    val parsed = parseCsv(ReviewQueue.csv(Vector(row)))
    assertEquals(parsed.size, 2)
    assertEquals(parsed(1), ReviewQueue.cells(row))
    assertEquals(parsed(1)(4), "Wang, \"Wei\"\nJr")
    assertEquals(parsed(1)(7), "a,b | c")
  }

  test(
    "similarity: an identifier conflict beats a shared identifier; otherwise weighted features"
  ) {
    val both = cand(EntityKind.WORK, "a", "b", Map("doi_conflict" -> 1.0, "exact_doi" -> 1.0))
    assertEquals(ReviewQueue.stratum(both, cfg), Stratum.LikelyNonMatch)
    val id =
      cand(EntityKind.RESEARCHER, "m:a", "orcid:x", Map("exact_orcid" -> 1.0, "name_jw" -> 0.2))
    assertEquals(ReviewQueue.stratum(id, cfg), Stratum.LikelyMatch)
    val zeroFlags = cand(
      EntityKind.RESEARCHER,
      "m:a",
      "m:b",
      Map(
        "orcid_conflict" -> 0.0,
        "name_jw" -> 1.0,
        "coauthor_overlap" -> 1.0,
        "title_token_overlap" -> 1.0
      )
    )
    assertEquals(ReviewQueue.stratum(zeroFlags, cfg), Stratum.LikelyMatch)
    val mid =
      cand(EntityKind.RESEARCHER, "m:a", "m:b", Map("name_jw" -> 1.0, "coauthor_overlap" -> 1.0))
    assertEquals(ReviewQueue.stratum(mid, cfg), Stratum.Borderline)
    val low = cand(EntityKind.RESEARCHER, "m:a", "m:b", Map("name_jw" -> 1.0))
    assertEquals(ReviewQueue.stratum(low, cfg), Stratum.LikelyNonMatch)
  }

  test(
    "allocation is balanced, redistributes what small cells cannot use, and never exceeds capacity"
  ) {
    import EntityKind.*, Stratum.*
    val cap = Map(
      (RESEARCHER, LikelyMatch) -> 193,
      (RESEARCHER, Borderline) -> 75,
      (RESEARCHER, LikelyNonMatch) -> 4,
      (WORK, LikelyMatch) -> 71,
      (WORK, LikelyNonMatch) -> 4
    )
    val q = ReviewQueue.allocate(cap, 280)
    assertEquals(q.values.sum, 280)
    assertEquals(q((RESEARCHER, LikelyNonMatch)), 4)
    assertEquals(q((WORK, LikelyNonMatch)), 4)
    assertEquals(q((RESEARCHER, Borderline)), 75)
    assertEquals(q((WORK, LikelyMatch)), 71)
    assertEquals(q((RESEARCHER, LikelyMatch)), 126)
    assert(!q.contains((WORK, Borderline)))
    val even = ReviewQueue.allocate(ReviewQueue.Cells.map(_ -> 100).toMap, 31)
    assertEquals(even.values.sum, 31)
    assert(even.values.forall(v => v == 5 || v == 6))
    assertEquals(ReviewQueue.allocate(cap, 10000).values.sum, cap.values.sum)
  }

  test("selection counts per kind and stratum follow the allocation") {
    val (cs, idx) = fixture(10) // 10 per work stratum
    val built = ReviewQueue
      .build(cs, idx, cfg.copy(targetRows = 12, minRows = 6))
      .fold(e => fail(e), identity)
    val sel = built.selection
    assertEquals(sel.selected((EntityKind.WORK, Stratum.LikelyMatch)), 4)
    assertEquals(sel.selected((EntityKind.WORK, Stratum.Borderline)), 4)
    assertEquals(sel.selected((EntityKind.WORK, Stratum.LikelyNonMatch)), 4)
    assertEquals(sel.available((EntityKind.WORK, Stratum.Borderline)), 10)
    assertEquals(built.rows.size, 12)
    assertEquals(built.rows.map(_.pairId).distinct.size, 12)
    assert(built.rows.map(_.pairId).toSet.subsetOf(cs.map(_.candidateId).toSet))
  }

  test("the queue is deterministic: same bytes on rebuild and on shuffled, duplicated input") {
    val (cs, idx) = fixture(10)
    val a = ReviewQueue.build(cs, idx, small).fold(e => fail(e), identity)
    val shuffled = new scala.util.Random(3).shuffle(cs ++ cs.take(5))
    val b = ReviewQueue.build(shuffled, idx, small).fold(e => fail(e), identity)
    assertEquals(b.csv, a.csv)
    assertEquals(Hashing.sha256Hex(b.csv), Hashing.sha256Hex(a.csv))
    val other =
      ReviewQueue.build(cs, idx, small.copy(seed = "another")).fold(e => fail(e), identity)
    assertNotEquals(other.csv, a.csv)
  }

  test("rows are traceable: pair_id is the candidate ID, sides carry source and record IDs") {
    val (cs, idx) = fixture(2)
    val built = ReviewQueue
      .build(cs, idx, small.copy(minRows = 6, targetRows = 6))
      .fold(e => fail(e), identity)
    built.rows.foreach { r =>
      val c = cs.find(_.candidateId == r.pairId).get
      assertEquals(r.left.recordId, c.sourceId)
      assertEquals(r.right.recordId, c.targetId)
      assertEquals(r.left.source, "DBLP")
      assertEquals(r.right.source, "OPENALEX")
      assertEquals(r.right.affiliations, Vector("Universität, Zürich"))
      assertEquals(r.left.venuesYears, Vector("Computer Vision (2014)"))
    }
    val missing = cand(EntityKind.WORK, "dblp:w/0", "openalex:nope", Map("doi_equal" -> 1.0))
    val err = ReviewQueue.build(cs :+ missing, idx, small.copy(targetRows = 100, minRows = 6))
    assert(err.left.exists(_.contains("openalex:nope")), err)
  }

  test(
    "researcher sides: a mention lists its coauthors, an ORCID endpoint aggregates its mentions"
  ) {
    val w1 = work(
      "dblp:a",
      "Paper A",
      SourceType.DBLP,
      Vector("Ann Lee", "Bob Ray", "Cy Dee", "D E", "F G", "H I", "J K"),
      orcids = Map(0 -> "0000-0002-1825-0097")
    )
    val w2 = work(
      "openalex:W9",
      "Paper B",
      SourceType.OPENALEX,
      Vector("Ann Lee", "Zed Q"),
      orcids = Map(0 -> "0000-0002-1825-0097")
    )
    val idx = RecordIndex(Map(w1.workId -> w1, w2.workId -> w2), Map.empty, Map.empty)
    val m = w1.authors.head
    val s =
      ReviewQueue.side(EntityKind.RESEARCHER, m.mentionId, idx, cfg).fold(e => fail(e), identity)
    assertEquals(s.source, "DBLP")
    assertEquals(s.nameOrTitle, "Ann Lee")
    assertEquals(s.orcidOrDoi, "0000-0002-1825-0097")
    assertEquals(s.coauthors, Vector("Bob Ray", "Cy Dee", "D E", "F G", "H I"))
    assertEquals(s.titles, Vector("Paper A"))
    val o = ReviewQueue
      .side(EntityKind.RESEARCHER, "orcid:0000-0002-1825-0097", idx, cfg)
      .fold(e => fail(e), identity)
    assertEquals(o.source, "SCHOLARCHAIN")
    assertEquals(o.recordId, "orcid:0000-0002-1825-0097")
    assertEquals(o.titles.toSet, Set("Paper A", "Paper B"))
    assert(o.titles.size <= cfg.maxTitles && o.coauthors.size <= cfg.maxCoauthors)
    assert(ReviewQueue.side(EntityKind.RESEARCHER, "orcid:0000-0000-0000-0000", idx, cfg).isLeft)
    assert(ReviewQueue.side(EntityKind.RESEARCHER, "m:unknown", idx, cfg).isLeft)
  }

  test("too few candidate pairs is an error, never padding") {
    val (cs, idx) = fixture(1)
    val r = ReviewQueue.build(cs, idx, cfg)
    assert(r.left.exists(_.contains("fewer than minRows")), r)
  }

  test("candidates from two runs, or conflicting copies of one candidate, are refused") {
    val (cs, idx) = fixture(4)
    val twoRuns = cs.updated(0, cs(0).copy(runId = "run-fedcba9876543210"))
    assert(ReviewQueue.build(twoRuns, idx, small).isLeft)
    val clash = cs :+ cs(0).copy(targetId = "openalex:W99")
    assert(CandidateView.merge(clash).isLeft)
    val merged = CandidateView
      .merge(Vector(cs(1), cs(1).copy(features = Map("title_jaccard" -> 0.9, "x" -> 1.0))))
      .fold(e => fail(e), identity)
    assertEquals(merged.head.features("title_jaccard"), 0.9)
    assertEquals(merged.head.features("x"), 1.0)
  }

  test("candidate lines are parsed strictly") {
    val good =
      """{"schemaVersion":"match-candidate-v1","candidateId":"mc:work:ab","kind":"WORK","sourceId":"a","targetId":"b","blockKey":"k","features":{"exact_doi":1},"sourceIdentifiers":{},"targetIdentifiers":{},"provenance":{"runId":"run-0123456789abcdef"}}"""
    assertEquals(CandidateView.parse(good).map(_.features), Right(Map("exact_doi" -> 1.0)))
    assert(CandidateView.parse(good.replace("match-candidate-v1", "match-candidate-v2")).isLeft)
    assert(CandidateView.parse(good.replace("mc:work:ab", "mc:researcher:ab")).isLeft)
    assert(CandidateView.parse(good.replace("\"exact_doi\":1", "\"exact_doi\":\"1\"")).isLeft)
    assert(CandidateView.parse(good.replace("\"targetId\":\"b\"", "\"targetId\":\"a\"")).isLeft)
    assert(CandidateView.parse("not json").isLeft)
  }

  test("main refuses datasets/gold paths and an existing output without --overwrite") {
    assert(ReviewQueueMain.isGoldPath(Path.of("/x/datasets/gold/er-gold-v1.csv")))
    assert(!ReviewQueueMain.isGoldPath(Path.of("/x/target/er-review/er-review-v1.csv")))
    val gold =
      ReviewQueueMain.run(Vector("--run-root", "/nowhere", "--out", "/x/datasets/gold/q.csv"))
    assert(gold.left.exists(_.contains("datasets/gold")), gold)
    val tmp = Files.createTempFile("er-review", ".csv")
    try
      val exists = ReviewQueueMain.run(Vector("--run-root", "/nowhere", "--out", tmp.toString))
      assert(exists.left.exists(_.contains("--overwrite")), exists)
    finally Files.deleteIfExists(tmp)
    assert(ReviewQueueMain.parseArgs(Vector("--run-root", "a", "--bogus", "b")).isLeft)
    assert(ReviewQueueMain.parseArgs(Vector("--run-root", "a", "--out")).isLeft)
  }

  test("main end to end: reads a run tree, writes the CSV and a sidecar without strata") {
    val root = Files.createTempDirectory("er-review-run")
    try
      val n = 100 // 300 candidate pairs, 100 per stratum
      val (cs, idx) = fixture(n)
      def dir(p: String) = Files.createDirectories(root.resolve(p))
      val exact = dir(s"match-candidates/kind=exact/snapshot=2026-11-22/run_id=$RunId")
      val blocked = dir(s"match-candidates/kind=blocked/snapshot=2026-11-22/run_id=$RunId")
      val exp = dir(s"export/snapshot=2026-11-22/run_id=$RunId")
      dir("run-manifests")
      Files.writeString(root.resolve(s"run-manifests/$RunId.json"), "{}")
      Files.writeString(root.resolve(s"run-manifests/$RunId-summary.json"), "{}")
      def line(c: CandidateView) =
        ujson
          .Obj(
            "schemaVersion" -> "match-candidate-v1",
            "candidateId" -> c.candidateId,
            "kind" -> c.kind.toString,
            "sourceId" -> c.sourceId,
            "targetId" -> c.targetId,
            "blockKey" -> "k",
            "features" -> ujson.Obj.from(c.features.map((k, v) => k -> ujson.Num(v))),
            "sourceIdentifiers" -> ujson.Obj(),
            "targetIdentifiers" -> ujson.Obj(),
            "provenance" -> ujson.Obj("runId" -> RunId)
          )
          .render()
      Files.writeString(
        exact.resolve("part-r-00000"),
        cs.take(100).map(line).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
      Files.writeString(
        blocked.resolve("part-r-00000"),
        cs.drop(50).map(line).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
      Files.createDirectories(blocked.resolve("stats"))
      Files.writeString(blocked.resolve("stats/part-r-00000"), "not a candidate\n")
      Vector(exact, blocked, exp).foreach(d => Files.writeString(d.resolve("_SUCCESS"), ""))
      Files.createDirectories(exp.resolve("works"))
      Files.writeString(
        exp.resolve("works/part-r-00000"),
        idx.works.values.toVector.sortBy(_.workId).map(JsonLines.encode(_)).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
      Files.createDirectories(exp.resolve("institutions"))
      Files.writeString(
        exp.resolve("institutions/part-r-00000"),
        idx.institutions.values.map(JsonLines.encode(_)).mkString("\n") + "\n",
        StandardCharsets.UTF_8
      )
      val out = root.resolve("q/er-review-v1.csv")
      val stdout = ReviewQueueMain
        .run(Vector("--run-root", root.toString, "--out", out.toString))
        .fold(e => fail(e), identity)
      assert(stdout.exists(_.contains(s"wrote ${cfg.targetRows} rows")), stdout)
      assert(stdout.exists(_.startsWith("work        Borderline")), stdout)
      val text = Files.readString(out, StandardCharsets.UTF_8)
      val table = parseCsv(text)
      assertEquals(table.size, cfg.targetRows + 1)
      assert(text.contains("Ñúñez"))
      val sidecar = Files.readString(ReviewQueueMain.sidecarOf(out))
      assert(sidecar.contains(RunId) && sidecar.contains(Hashing.sha256Hex(text)))
      assert(Stratum.values.forall(s => !sidecar.contains(s.toString)))
      val again = ReviewQueueMain.run(
        Vector("--run-root", root.toString, "--out", out.toString, "--overwrite", "true")
      )
      assert(again.isRight)
      assertEquals(Files.readString(out, StandardCharsets.UTF_8), text)
    finally
      val all = Files.walk(root).toArray.map(_.asInstanceOf[Path]).sortBy(-_.toString.length)
      all.foreach(Files.deleteIfExists)
  }
