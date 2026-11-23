package scholarchain.hadoop

import scholarchain.core.*
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.hadoop.mapreduce.TaskAttemptID
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import java.io.ByteArrayInputStream
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

/** Repair round 1: declared encoding (B5) and record ends at the next start tag (B6). */
class RepairDblpSuite extends munit.FunSuite:
  private val h = new Harness("repair-dblp")
  override def afterAll(): Unit = h.delete()

  private val Latin1 = StandardCharsets.ISO_8859_1

  private def doc(prolog: String, body: String, cs: Charset = Latin1): Array[Byte] =
    (prolog + "\n<dblp>\n" + body + "\n</dblp>\n").getBytes(cs)

  private val latin1Prolog = """<?xml version="1.0" encoding="ISO-8859-1"?>"""
  private val rene =
    """<article key="j/x/1"><author>René Dupont</author><title>Graphs.</title><year>2020</year></article>"""

  private def scan(bytes: Array[Byte], start: Long, end: Long, cs: Charset) =
    val in = new ByteArrayInputStream(bytes); in.skip(start)
    val s = new DblpRecordScanner(in, start, end, 1 << 20, cs)
    Iterator.continually(s.next()).takeWhile(_.nonEmpty).flatten.toVector

  /** Runs the real record reader over one file at the given split size. */
  private def readSplits(file: Path, splitSize: Long): Vector[(Long, String)] =
    val len = Files.size(file)
    val conf = h.conf(h.dir.toString)
    (0L until len by splitSize).toVector.flatMap { s =>
      val split = new FileSplit(new HPath(file.toUri), s, math.min(splitSize, len - s), null)
      val ctx = new TaskAttemptContextImpl(conf, new TaskAttemptID())
      val reader = new DblpRecordReader
      reader.initialize(split, ctx)
      try
        Iterator
          .continually(reader.nextKeyValue())
          .takeWhile(identity)
          .map(_ => (reader.getCurrentKey.get, reader.getCurrentValue.toString))
          .toVector
      finally reader.close()
    }

  test("the prolog decides the charset: ISO-8859-1, UTF-8 when undeclared or absent") {
    def cs(prolog: String) = DblpRecordScanner.detectCharset(prolog.getBytes(Latin1))
    assertEquals(cs(latin1Prolog), Right(Latin1))
    assertEquals(cs("""<?xml version='1.0' encoding='iso-8859-1' ?>"""), Right(Latin1))
    assertEquals(cs("""<?xml version="1.0"?>"""), Right(StandardCharsets.UTF_8))
    assertEquals(cs("<dblp>"), Right(StandardCharsets.UTF_8))
    val bom = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)
    assertEquals(
      DblpRecordScanner.detectCharset(bom ++ """<?xml version="1.0" encoding="UTF-8"?>""".getBytes),
      Right(StandardCharsets.UTF_8)
    )
    assert(
      DblpRecordScanner
        .detectCharset(bom ++ latin1Prolog.getBytes(Latin1))
        .left
        .exists(_.contains("byte order mark"))
    )
    for bad <- Vector(
        """<?xml version="1.0" encoding="UTF-16"?>""",
        """<?xml version="1.0" encoding="no-such-charset"?>""",
        """<?xml version="1.0" encoding="Shift_JIS"?>"""
      )
    do assert(cs(bad).isLeft, bad)
    assert(DblpRecordScanner.detectCharset(Array(0xff.toByte, 0xfe.toByte, '<'.toByte, 0)).isLeft)
  }

  test("a raw ISO-8859-1 byte 0xE9 in an author name decodes to Rene with an accent") {
    val bytes = doc(latin1Prolog, rene)
    assert(
      bytes.contains(0xe9.toByte) && !bytes
        .sliding(2)
        .exists(_.sameElements(Array(0xc3.toByte, 0xa9.toByte)))
    )
    val recs = scan(bytes, 0, Long.MaxValue, Latin1)
    assertEquals(recs.map(_.status), Vector(RecordStatus.Complete))
    val p = DblpParser.parseRecord(recs.head.text).toOption.get
    assertEquals(p.authors.map(_._1), Vector("René Dupont"))
    assert(!recs.head.text.contains('�'))
  }

  test(
    "the same bytes declared UTF-8, or undeclared, are a BadEncoding reject naming the charset"
  ) {
    for prolog <- Vector("""<?xml version="1.0" encoding="UTF-8"?>""", "") do
      val r = scan(doc(prolog, rene), 0, Long.MaxValue, StandardCharsets.UTF_8)
      assertEquals(r.map(_.status), Vector(RecordStatus.BadEncoding))
      assert(r.head.text.endsWith("\n[charset=UTF-8]"), r.head.text)
  }

  test("UTF-8 bytes C3 A9 in an ISO-8859-1 document decode as two Latin-1 characters") {
    val bytes = doc(latin1Prolog, rene.replace("René", "RenÃ©"), Latin1)
    val r = scan(bytes, 0, Long.MaxValue, Latin1)
    assertEquals(DblpParser.parseRecord(r.head.text).toOption.get.authors.head._1, "RenÃ© Dupont")
  }

  test("a raw control byte 0x01 in an ISO-8859-1 document is rejected by the parser") {
    val r = scan(doc(latin1Prolog, rene.replace("René", "Ren\u0001")), 0, Long.MaxValue, Latin1)
    assertEquals(r.map(_.status), Vector(RecordStatus.Complete))
    assert(DblpParser.parseRecord(r.head.text).isLeft)
  }

  test("the record reader gives identical records at every split size, plain and gzip") {
    val body = (1 to 12)
      .map(i => rene.replace("j/x/1", s"j/x/$i").replace("René", s"René Nr$i"))
      .mkString("\n")
    val bytes = doc(latin1Prolog, body)
    val file = h.dir.resolve("in").resolve("latin1.xml")
    Files.createDirectories(file.getParent); Files.write(file, bytes)
    val whole = readSplits(file, bytes.length.toLong)
    assertEquals(whole.size, 12)
    assert(whole.forall((k, v) => k >= 0 && v.contains("René Nr")))
    for size <- Vector(30L, 64L, 100L, 257L, 1000L) do
      assertEquals(readSplits(file, size), whole, s"split size $size")
    val gz = h.dir.resolve("in").resolve("latin1.xml.gz")
    val out = new GZIPOutputStream(Files.newOutputStream(gz))
    try out.write(bytes)
    finally out.close()
    assertEquals(readSplits(gz, Files.size(gz)), whole)
  }

  test("an unsupported encoding refuses the file as one counted unit, once, at any split size") {
    val bytes = doc("""<?xml version="1.0" encoding="UTF-16"?>""", rene, StandardCharsets.UTF_8)
    val file = h.dir.resolve("in").resolve("utf16.xml")
    Files.createDirectories(file.getParent); Files.write(file, bytes)
    for size <- Vector(40L, 1000L) do
      val got = readSplits(file, size)
      assertEquals(got.size, 1, s"split size $size")
      assertEquals(got.head._1, -1L)
      assert(got.head._2.startsWith("UnsupportedEncoding:"), got.head._2)
  }

  test("pipeline: a Latin-1 DBLP file gives the accented author and an unsupported one a counter") {
    val ok = h.dir.resolve("in").resolve("ok.xml")
    Files.createDirectories(ok.getParent); Files.write(ok, doc(latin1Prolog, rene))
    val r = h.run(
      Vector(
        "--dblp",
        ok.toString,
        "--snapshot",
        "2026-11-22",
        "--reducers",
        "1",
        "--output",
        h.dir.resolve("out-latin1").toString
      )
    )
    assertEquals(r.code, 0)
    assert(r.lines(r.paths.dblpWorks).exists(_.contains("René Dupont")))
    assertEquals(r.counters(r.paths.dblpWorks).get(Ctr.Rejected), None)
    val bad = h.dir.resolve("in").resolve("bad16.xml")
    Files.write(
      bad,
      doc("""<?xml version="1.0" encoding="UTF-16"?>""", rene, StandardCharsets.UTF_8)
    )
    val r2 = h.run(
      Vector(
        "--dblp",
        bad.toString,
        "--snapshot",
        "2026-11-22",
        "--reducers",
        "1",
        "--output",
        h.dir.resolve("out-utf16").toString
      )
    )
    assertEquals(r2.code, 0)
    val c = r2.counters(r2.paths.dblpWorks)
    assertEquals((c(Ctr.Input), c(Ctr.Rejected), c("UNSUPPORTED_ENCODING")), (1L, 1L, 1L))
    assert(r2.rejects(r2.paths.dblpWorks).head.reason.contains("unsupported encoding"))
  }

  // ------------------------------------------------------------ B6

  private val unclosed =
    """<phdthesis key="x/1"><author>A One</author><title>t"""
  private def article(i: Int) =
    s"""<article key="a/$i"><author>A $i</author><title>T$i</title><year>2020</year></article>"""

  test("an unclosed record is Truncated alone; the 1000 articles after it are all read") {
    val xml = doc(latin1Prolog, unclosed + "\n" + (1 to 1000).map(article).mkString("\n"))
    val whole = scan(xml, 0, Long.MaxValue, Latin1)
    assertEquals(whole.size, 1001)
    assertEquals(whole.head.status, RecordStatus.Truncated)
    assert(whole.tail.forall(_.status == RecordStatus.Complete))
    assertEquals(whole.map(_.offset), whole.map(_.offset).sorted.distinct)
    for size <- Vector(37L, 500L, 4096L) do
      val got = (0L until xml.length.toLong by size).toVector
        .flatMap(s => scan(xml, s, math.min(xml.length.toLong, s + size), Latin1))
      assertEquals(
        got.map(r => (r.offset, r.status)),
        whole.map(r => (r.offset, r.status)),
        s"$size"
      )
    val file = h.dir.resolve("in").resolve("unclosed.xml")
    Files.createDirectories(file.getParent); Files.write(file, xml)
    val viaReader = readSplits(file, 4096)
    assertEquals(viaReader.size, 1001)
    assertEquals(viaReader.count(_._1 < 0), 1)
    val gz = h.dir.resolve("in").resolve("unclosed.xml.gz")
    val out = new GZIPOutputStream(Files.newOutputStream(gz))
    try out.write(xml)
    finally out.close()
    assertEquals(readSplits(gz, Files.size(gz)).size, 1001)
  }

  test("an oversize unclosed record is bounded by the next start tag too") {
    val xml = doc(latin1Prolog, unclosed + ("x" * 5000) + "\n" + article(1) + "\n" + article(2))
    val in = new ByteArrayInputStream(xml)
    val s = new DblpRecordScanner(in, 0, Long.MaxValue, 300, Latin1)
    val r = Iterator.continually(s.next()).takeWhile(_.nonEmpty).flatten.toVector
    assertEquals(
      r.map(_.status),
      Vector(RecordStatus.Oversize, RecordStatus.Complete, RecordStatus.Complete)
    )
  }
end RepairDblpSuite
