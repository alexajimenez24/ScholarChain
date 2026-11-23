package scholarchain.hadoop

import scholarchain.core.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class DblpParserSuite extends munit.FunSuite:
  private val run = FixtureIO.run

  private def scan(
      bytes: Array[Byte],
      start: Long = 0,
      end: Long = Long.MaxValue,
      cap: Int = DblpRecordScanner.DefaultMaxRecordBytes
  ): Vector[ScannedRecord] =
    val in = new ByteArrayInputStream(bytes); in.skip(start)
    val s = new DblpRecordScanner(in, start, end, cap)
    Iterator.continually(s.next()).takeWhile(_.nonEmpty).flatten.toVector

  private def scan(xml: String): Vector[ScannedRecord] = scan(xml.getBytes(StandardCharsets.UTF_8))

  private def retained(xml: String): WorkRecord =
    DblpParser.parseRecord(xml).map(DblpParser.toWork(_, run)) match
      case Right(DblpParser.Outcome.Retained(w)) => w
      case other                                 => fail(s"expected a retained work, got $other")

  // ------------------------------------------------------------ parsing

  test("DTD entities, inline markup and NFC are handled; a trailing period is dropped") {
    val xml =
      """<article key="journals/x/M1"><author>J&ouml;rg M&uuml;ller</author><title>On <i>Fast</i> &amp; Safe.</title><year>2001</year><journal>X</journal></article>"""
    val p = DblpParser.parseRecord(xml).fold(e => fail(e), identity)
    assertEquals(p.authors.map(_._1), Vector("Jörg Müller"))
    assertEquals(p.title, "On Fast & Safe")
    assertEquals(p.year, Some(2001))
    assertEquals(p.venue, Some("X"))
  }

  test("the entity table covers code points 160 to 255 and decode works in one pass") {
    assertEquals(DblpEntities.CodePoints.size, 96)
    assertEquals(DblpEntities.CodePoints("nbsp"), 160)
    assertEquals(DblpEntities.CodePoints("yuml"), 255)
    assertEquals(
      DblpEntities.decode("M&uuml;ller &amp;#65; &#x41; &#65; &bogus; &#0; &#1114112;"),
      "Müller &#65; A A &bogus; &#0; &#1114112;"
    )
  }

  test("every kind of malformed record is rejected with a reason") {
    def reason(xml: String) = DblpParser.parseRecord(xml).left.getOrElse(fail(s"accepted $xml"))
    assert(reason("""<article key="k"><title>&bogus;</title></article>""").contains("bogus"))
    assert(reason("""<article key="k"><title>unclosed</article>""").contains("malformed"))
    assert(reason("""<article><title>t</title></article>""").contains("without key"))
    assert(reason("""<article key=""><title>t</title></article>""").contains("without key"))
    assert(reason("""<article key="k"><year>MMXX</year></article>""").contains("non-numeric year"))
    assert(reason("""<widget key="k"></widget>""").contains("unknown record type"))
  }

  test("a DOCTYPE or ENTITY declaration inside a record is refused (no XXE)") {
    val xxe =
      """<article key="k/1"><!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><title>&xxe;</title></article>"""
    assert(DblpParser.parseRecord(xxe).left.exists(_.contains("DOCTYPE")))
    val billion = """<article key="k/2"><!ENTITY a "aaaa"><title>&a;</title></article>"""
    assert(DblpParser.parseRecord(billion).isLeft)
  }

  test("ORCID attributes are validated, DOI and arXiv come from ee links, venue from the key") {
    val xml =
      """<inproceedings key="conf/a/B1"><author orcid="0000-0002-1825-0097">Josiah Carberry</author><author orcid="0000-0002-1825-0098">Bad Check</author><title>T</title><year>2020</year><booktitle>A</booktitle><ee>https://doi.org/10.1145/ABC</ee><ee>https://arxiv.org/abs/2101.00001v2</ee><url>db/conf/a/B1.html</url></inproceedings>"""
    val w = retained(xml)
    assertEquals(w.authors.map(_.orcid), Vector(Some("0000-0002-1825-0097"), None))
    assertEquals(w.doi, Some("10.1145/abc"))
    assertEquals(w.arxivId, Some("2101.00001"))
    assertEquals(w.venueId, Some("venue:dblp:conf/a"))
    assertEquals(w.venueName, Some("A"))
    assertEquals(
      w.urls,
      Vector(
        "https://doi.org/10.1145/ABC",
        "https://arxiv.org/abs/2101.00001v2",
        "db/conf/a/B1.html"
      )
    )
    assertEquals(Validate.work(w), Vector.empty[String])
  }

  test("missing fields: no year and no authors are kept, no title or an empty author is rejected") {
    val noYear = retained("""<article key="j/a/1"><author>A B</author><title>T</title></article>""")
    assertEquals(noYear.year, None)
    val noAuthors =
      retained("""<book key="books/x/1"><title>T</title><publisher>P</publisher></book>""")
    assertEquals(noAuthors.authors, Vector.empty)
    assertEquals(noAuthors.venueName, Some("P"))
    def outcome(xml: String) = DblpParser.parseRecord(xml).map(DblpParser.toWork(_, run))
    assert(
      outcome("""<article key="j/a/2"><author>A B</author></article>""") match
        case Right(DblpParser.Outcome.Rejected(r)) => r.contains("empty title")
        case _                                     => false
    )
    assert(
      outcome("""<article key="j/a/3"><author> </author><title>T</title></article>""") match
        case Right(DblpParser.Outcome.Rejected(r)) => r.contains("no name")
        case _                                     => false
    )
    assert(
      outcome(
        """<article key="j/a/4"><author>A B</author><title>T</title><year>1850</year></article>"""
      ) match
        case Right(DblpParser.Outcome.Rejected(r)) => r.contains("implausible year")
        case _                                     => false
    )
  }

  test("a key that cannot form a work ID is rejected, not thrown") {
    assert(
      DblpParser
        .parseRecord("""<article key="has space"><author>A B</author><title>T</title></article>""")
        .map(
          DblpParser.toWork(_, run)
        ) match
        case Right(DblpParser.Outcome.Rejected(_)) => true
        case _                                     => false
    )
  }

  test("www, proceedings and data records are skipped, not retained") {
    for t <- Vector("www", "proceedings", "data") do
      val p = DblpParser
        .parseRecord(s"""<$t key="x/y"><author>X</author><title>Home</title></$t>""")
        .toOption
        .get
      assertEquals(DblpParser.toWork(p, run), DblpParser.Outcome.Skipped(t))
  }

  test("repeated author names keep one mention per position") {
    val w = retained(
      """<article key="j/rep/1"><author>Sam Lee</author><author>Sam Lee</author><author>Sam Lee</author><title>T</title><year>2020</year></article>"""
    )
    assertEquals(w.authors.map(_.position), Vector(0, 1, 2))
    assertEquals(w.authors.map(_.mentionId).distinct.size, 3)
    assertEquals(Validate.work(w), Vector.empty[String])
  }

  test("Unicode: composed and decomposed spellings give one raw and one normalized name") {
    val composed = "<article key=\"j/u/1\"><author>José García</author><title>T</title></article>"
    val decomposed =
      "<article key=\"j/u/2\"><author>José García</author><title>T</title></article>"
    val (a, b) = (retained(composed).authors.head, retained(decomposed).authors.head)
    assertEquals(a.rawName, b.rawName)
    assertEquals(a.normalizedName, "jose garcia")
    assertEquals(b.normalizedName, "jose garcia")
    val cjk = retained("<article key=\"j/u/3\"><author>李 雷</author><title>图论</title></article>")
    assertEquals(cjk.authors.head.rawName, "李 雷")
    assertEquals(Validate.work(cjk), Vector.empty[String])
  }

  // ------------------------------------------------------------ splitting and torn records

  test("the DBLP fixture holds 17 records: 15 retained works and 2 skipped") {
    val records = scan(FixtureIO.bytes("dblp-fixture.xml"))
    assertEquals(records.size, 17)
    assert(records.forall(_.status == RecordStatus.Complete))
    val outcomes = records.map(r => DblpParser.parseRecord(r.text).map(DblpParser.toWork(_, run)))
    assertEquals(
      outcomes.count { case Right(DblpParser.Outcome.Retained(_)) => true; case _ => false },
      15
    )
    assertEquals(
      outcomes.count { case Right(DblpParser.Outcome.Skipped(_)) => true; case _ => false },
      2
    )
  }

  test("every split boundary yields each record exactly once, whatever the split size") {
    val bytes = FixtureIO.bytes("dblp-fixture.xml")
    val expected = scan(bytes)
    for splitSize <- Vector(1, 7, 37, 101, 256, 999, 2048, 5000) do
      val got = (0L until bytes.length.toLong by splitSize.toLong).toVector.flatMap(s =>
        scan(bytes, s, math.min(bytes.length.toLong, s + splitSize))
      )
      assertEquals(got, expected, s"split size $splitSize")
  }

  test("a record whose start tag sits exactly at a split end belongs to the next split") {
    val bytes = FixtureIO.bytes("dblp-fixture.xml")
    val all = scan(bytes)
    val second = all(1).offset
    assertEquals(scan(bytes, 0, second).size, 1)
    assertEquals(scan(bytes, second, bytes.length.toLong).size, all.size - 1)
  }

  test("multi-byte UTF-8 survives byte scanning at every split position") {
    val xml =
      "<dblp><article key=\"k/a/1\"><author>Zoë Ünal</author><title>Größe</title><year>2000</year></article></dblp>"
    val bytes = xml.getBytes(StandardCharsets.UTF_8)
    for splitSize <- 1 to bytes.length do
      val got = (0L until bytes.length.toLong by splitSize.toLong).toVector.flatMap(s =>
        scan(bytes, s, s + splitSize)
      )
      assertEquals(got.size, 1, s"split size $splitSize")
    assertEquals(
      DblpParser.parseRecord(scan(bytes).head.text).map(_.authors.head._1),
      Right("Zoë Ünal")
    )
  }

  test("booktitle and similar tags are not mistaken for record starts") {
    val xml =
      """<dblp><inproceedings key="c/x/1"><author>A B</author><title>T</title><booktitle>X</booktitle></inproceedings></dblp>"""
    assertEquals(scan(xml).map(_.status), Vector(RecordStatus.Complete))
  }

  test("a record cut off by end of input is reported as truncated, not dropped") {
    val xml = """<dblp><article key="t/1"><author>A B</author><title>cut"""
    val r = scan(xml)
    assertEquals(r.map(_.status), Vector(RecordStatus.Truncated))
    assert(r.head.text.startsWith("<article key=\"t/1\">"))
  }

  test("an unclosed record is rejected alone: the records it swallowed are recovered") {
    val xml =
      """<dblp>
        |<article key="a/1"><author>A One</author><title>unclosed
        |<inproceedings key="b/2"><author>B Two</author><title>second</title><year>2020</year></inproceedings>
        |<article key="c/3"><author>C Three</author><title>third</title><year>2021</year></article>
        |</dblp>""".stripMargin
    val records = scan(xml)
    assertEquals(
      records.map(_.status),
      Vector(RecordStatus.Truncated, RecordStatus.Complete, RecordStatus.Complete)
    )
    val ok = records.drop(1).map(r => DblpParser.parseRecord(r.text).map(_.dblpKey))
    assertEquals(ok, Vector(Right("b/2"), Right("c/3")))
    // offsets are byte offsets of the start tags, strictly increasing
    assertEquals(records.map(_.offset), records.map(_.offset).sorted)
    assertEquals(records.map(_.offset).distinct.size, 3)
  }

  test("recovered records are not read twice when a split ends inside the unclosed record") {
    val xml =
      """<dblp><article key="a/1"><author>A One</author><title>unclosed</title>
        |<inproceedings key="b/2"><author>B Two</author><title>second</title></inproceedings>
        |<article key="c/3"><author>C Three</author><title>third</title></article></dblp>""".stripMargin
    val bytes = xml.getBytes(StandardCharsets.UTF_8)
    val expected =
      scan(bytes).map(r => (r.status, DblpParser.parseRecord(r.text).map(_.dblpKey).toOption))
    for splitSize <- Vector(20, 50, 80, 120) do
      val got = (0L until bytes.length.toLong by splitSize.toLong).toVector
        .flatMap(s => scan(bytes, s, math.min(bytes.length.toLong, s + splitSize)))
        .map(r => (r.status, DblpParser.parseRecord(r.text).map(_.dblpKey).toOption))
      assertEquals(got, expected, s"split size $splitSize")
  }

  test("a record above the size cap is reported oversize and scanning continues after it") {
    val big =
      "<article key=\"big/1\"><author>A B</author><title>" + ("x" * 500) + "</title></article>"
    val small = "<article key=\"s/1\"><author>A B</author><title>ok</title></article>"
    val r = scan((big + "\n" + small).getBytes(StandardCharsets.UTF_8), cap = 200)
    assertEquals(r.map(_.status), Vector(RecordStatus.Oversize, RecordStatus.Complete))
    assert(r.head.text.length <= DblpRecordScanner.RejectHeadChars)
    assertEquals(DblpParser.parseRecord(r(1).text).map(_.dblpKey), Right("s/1"))
  }

  test("invalid UTF-8 bytes inside a record are reported, not silently replaced") {
    val head = "<article key=\"e/1\"><author>A ".getBytes(StandardCharsets.UTF_8)
    val tail = " B</author><title>t</title></article>".getBytes(StandardCharsets.UTF_8)
    val r = scan(head ++ Array(0xff.toByte, 0xfe.toByte) ++ tail)
    assertEquals(r.map(_.status), Vector(RecordStatus.BadEncoding))
  }

  test("the reader encodes non-complete units with a negative key the mapper can decode") {
    val (k, v) =
      DblpRecordReader.encode(ScannedRecord(41, RecordStatus.Truncated, "<article key=\"x\">"))
    assertEquals(k, -42L)
    assertEquals(DblpRecordReader.decodeRejected(v), ("Truncated", "<article key=\"x\">"))
    assertEquals(
      DblpRecordReader.encode(ScannedRecord(7, RecordStatus.Complete, "<a/>")),
      (7L, "<a/>")
    )
  }

  // ------------------------------------------------------------ the real DBLP sample

  test("the real DBLP sample: 40 incollection records, 97 authors, 82 pairs, largest team 5") {
    // 40 records (datasets/samples/real/retrieval.json); author counts per record sum to 97 and
    // sum(n(n-1)/2) is 82, with two 5-author records (10 pairs each) the largest.
    val records = scan(FixtureIO.bytes("dblp-real-sample.xml"))
    assertEquals(records.size, 40)
    val works = records.map(r => retained(r.text))
    assert(works.forall(_.recordType == "incollection"))
    assertEquals(works.map(_.authors.size).sum, 97)
    assertEquals(works.map(w => TeamLogic.pairCount(w.authors.size.toLong)).sum, 82L)
    assertEquals(works.map(_.authors.size).max, 5)
    assertEquals(works.map(_.workId).distinct.size, 40)
    assert(works.forall(w => Validate.work(w).isEmpty))
    // &aacute; and &iacute; resolve; DBLP homonym suffixes stay in the raw name, not the normalized
    val names = works.flatMap(_.authors)
    assert(names.exists(_.rawName == "Václav Hlavác"))
    assert(names.exists(_.rawName == "Marcelo Bertalmío"))
    val homonym = names.find(_.rawName == "Gaurav Srivastava 0004").get
    assertEquals(homonym.normalizedName, "gaurav srivastava")
    // three authors carry a valid ORCID attribute
    assertEquals(names.count(_.orcid.nonEmpty), 3)
  }
