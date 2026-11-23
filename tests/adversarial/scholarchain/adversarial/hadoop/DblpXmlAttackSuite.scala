package scholarchain.adversarial.hadoop

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.{Path => HPath}
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.{Job, TaskAttemptID}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

import scholarchain.adversarial.hadoop.HadoopBlackBox.*
import scholarchain.hadoop.DblpInputFormat

/** Adversarial DBLP XML (spec Phase 4 job 1 and Gate "DBLP parser handles record boundaries
  * correctly"; spec "Detailed Hadoop requirements": not assume byte splits align with records, test
  * entity declarations, multiline titles, records near split boundaries; MODULE.md "Reject output":
  * oversize, truncated and badly encoded records come back with a status and are rejected with
  * counters, strict UTF-8, the cap `scholarchain.dblp.max.record.bytes`, `<!DOCTYPE`/`<!ENTITY`
  * refused, entities decoded in a single pass, the result does not depend on the split size).
  *
  * Hostile document (cap 1000 bytes), in order:
  * {{{
  *   journals/adv/ok1        2 authors                      accepted, 1 pair
  *   journals/adv/big        title of 3004 characters       rejected (oversize)
  *   journals/adv/ok2        3 authors, title "Multi\n  Line\tTitle"  accepted, 3 pairs
  *   journals/adv/badutf     bytes C3 28 in the title       rejected (invalid UTF-8)
  *   journals/adv/overlong   bytes C0 AF (overlong '/')     rejected (invalid UTF-8)
  *   journals/adv/surrogate  bytes ED A0 80 (UTF-16 surrogate) rejected (invalid UTF-8)
  *   journals/adv/ok3        2 authors, title "A &amp;lt; B"   accepted, 1 pair
  *   journals/adv/cut        cut off at end of input        rejected (truncated)
  * }}}
  * units 8 = accepted 3 + rejected 5; teams 3; pairs 1+3+1 = 5.
  *
  * Clean document for split boundaries: 24 records, record i has (i mod 4)+1 authors, so each block
  * of 4 records has 1+2+3+4 = 10 mentions and 0+1+3+6 = 10 pairs; 6 blocks give 60 mentions and 60
  * pairs. Plus one `www` and one `proceedings` record (skipped, not rejected): 26 units.
  */
class DblpXmlAttackSuite extends PipelineSuite:

  // ----- documents ----------------------------------------------------------------------------

  private val Cap = 1000

  private def a(key: String, authors: Seq[String], title: String) =
    utf8(rec(key, authors, title))

  private def withBytes(
      key: String,
      authors: Seq[String],
      before: String,
      bad: Array[Byte]
  ): Array[Byte] =
    val (head, tail) = rec(key, authors, before + "MARK").split("MARK", 2) match
      case Array(h, t) => (h, t)
      case other       => throw new IllegalStateException(other.mkString)
    utf8(head) ++ bad ++ utf8(" tail" + tail)

  private val hostileUnits: Vector[Array[Byte]] = Vector(
    a("journals/adv/ok1", Seq("Ann Alpha", "Ben Beta"), "Plain Record One"),
    a("journals/adv/big", Seq("Cid Gamma", "Dee Delta"), "Big " + "x" * 3000),
    a("journals/adv/ok2", Seq("Eve Epsilon", "Fay Zeta", "Gus Eta"), "Multi\n  Line\tTitle"),
    withBytes(
      "journals/adv/badutf",
      Seq("Hal Theta", "Ivy Iota"),
      "Bad ",
      Array(0xc3, 0x28).map(_.toByte)
    ),
    withBytes(
      "journals/adv/overlong",
      Seq("Jan Kappa", "Kim Lambda"),
      "Over",
      Array(0xc0, 0xaf).map(_.toByte)
    ),
    withBytes(
      "journals/adv/surrogate",
      Seq("Lee Mu", "Max Nu"),
      "Sur",
      Array(0xed, 0xa0, 0x80).map(_.toByte)
    ),
    a("journals/adv/ok3", Seq("Ned Xi", "Oda Omicron"), "A &amp;lt; B")
  )

  private val truncatedTail =
    utf8(
      """<article mdate="2026-11-01" key="journals/adv/cut"><author>Pia Pi</author><author>Quin Rho</author><title>Cut Off"""
    )

  private val hostileDoc: Array[Byte] =
    utf8(Header) ++ hostileUnits.flatMap(_ ++ utf8("\n")) ++ truncatedTail

  /** Byte offset of each hostile unit in `hostileDoc`, in document order (the cut tail last). */
  private val hostileOffsets: Vector[Long] =
    val starts = hostileUnits.scanLeft(utf8(Header).length.toLong)((o, u) => o + u.length + 1)
    starts // 7 complete-or-bad units, then the truncated tail
  private val hostileStatusKeys: Vector[Long] =
    Vector(1, 3, 4, 5, 7).map(i => -(hostileOffsets(i) + 1)).sorted

  private val hostileAccepted = Set("journals/adv/ok1", "journals/adv/ok2", "journals/adv/ok3")
  private val hostileRejected =
    Set(
      "journals/adv/big",
      "journals/adv/badutf",
      "journals/adv/overlong",
      "journals/adv/surrogate",
      "journals/adv/cut"
    )

  private val cleanRecords: Vector[String] =
    (0 until 24).toVector.map { i =>
      val n = i % 4 + 1
      val authors = (0 until n).map(p => s"Ann ${surname(1000 + i * 4 + p)}")
      val title =
        if i % 5 == 0 then s"${word("Split", i)}\n   ${word("Bound", i)}"
        else s"${word("Split", i)} ${word("Bound", i)} &amp; More"
      val kind = if i % 2 == 0 then "article" else "inproceedings"
      val r = rec(s"journals/split/r$i", authors, title, Some((2000 + i).toString), kind)
      if i % 6 == 1 then r.replace("><", ">\n<") else r
    }

  private val skippedRecords = Vector(
    """<www mdate="2026-11-01" key="homepages/adv/q"><author>Ann Qaaa</author><title>Home Page</title></www>""",
    """<proceedings mdate="2026-11-01" key="conf/adv/2020"><editor>Ann Qaab</editor><title>ADV 2020</title><year>2020</year></proceedings>"""
  )

  /** Records joined with a newline, except every third pair back to back. */
  private val cleanDoc: String =
    val all = cleanRecords.take(12) ++ skippedRecords ++ cleanRecords.drop(12)
    Header + all.zipWithIndex.map((r, i) => if i % 3 == 2 then r else r + "\n").mkString + Footer

  private val cleanKeys: Vector[String] =
    (cleanRecords ++ skippedRecords).map(keyOf).sorted

  private def keyOf(s: String): String =
    """\skey="([^"]*)"""".r.findFirstMatchIn(s).map(_.group(1)).getOrElse(s"<no key in $s>")

  // ----- reader driver ------------------------------------------------------------------------

  /** Reads every unit of every split of `file` with DblpInputFormat at one split size. */
  private def readAll(file: Path, maxSplit: Long, cap: Option[Int]): (Int, Vector[(Long, String)]) =
    val job = Job.getInstance(localConf(suiteDir.resolve("reader-scratch")))
    val conf = job.getConfiguration
    conf.setLong(FileInputFormat.SPLIT_MAXSIZE, maxSplit)
    conf.setLong(FileInputFormat.SPLIT_MINSIZE, 1L)
    cap.foreach(c => conf.setInt("scholarchain.dblp.max.record.bytes", c))
    FileInputFormat.setInputPaths(job, new HPath(file.toUri))
    val fmt = new DblpInputFormat()
    val splits = fmt.getSplits(job).asScala.toVector
    val units = splits.flatMap { split =>
      val ctx = new TaskAttemptContextImpl(conf, new TaskAttemptID())
      val rr = fmt.createRecordReader(split, ctx)
      rr.initialize(split, ctx)
      val out = Vector.newBuilder[(Long, String)]
      try
        while rr.nextKeyValue() do
          val k: LongWritable = rr.getCurrentKey
          val v: Text = rr.getCurrentValue
          out += ((k.get, v.toString))
      finally rr.close()
      out.result()
    }
    (splits.size, units)

  private val splitSizes = Seq(64L, 100L, 128L, 257L, 1000L, Long.MaxValue)

  private lazy val cleanFile: Path =
    Files.write(fresh("reader").resolve("clean.xml"), utf8(cleanDoc))
  private lazy val hostileFile: Path =
    Files.write(fresh("reader-hostile").resolve("hostile.xml"), hostileDoc)

  test("reader: at every split size each clean record arrives exactly once, whole") {
    splitSizes.foreach { size =>
      val (nSplits, units) = readAll(cleanFile, size, None)
      if size == 64L then assert(nSplits > 1, s"64-byte splits gave $nSplits split(s)")
      assertEquals(units.count(_._1 < 0), 0, s"status units at split size $size")
      assertEquals(units.map(u => keyOf(u._2)).sorted, cleanKeys, s"keys at split size $size")
      units.foreach { (_, v) =>
        val t = v.trim
        assert(t.startsWith("<") && t.endsWith(">"), s"partial unit at split size $size: $t")
      }
    }
  }

  test("reader: hostile units give 3 records and 5 status units at every split size") {
    splitSizes.foreach { size =>
      val (_, units) = readAll(hostileFile, size, Some(Cap))
      val (status, complete) = units.partition(_._1 < 0)
      observe(s"reader status heads at $size", status.map(_._2.take(60)))
      assertEquals(
        complete.map(u => keyOf(u._2)).sorted,
        hostileAccepted.toVector.sorted,
        s"size $size"
      )
      assertEquals(status.size, 5, s"status units at split size $size")
      // MODULE.md writes the value as `STATUS:<head>`; read as "<status name>:<record head>".
      status.foreach((_, v) =>
        assert(v.matches("(?s)[A-Za-z]+:<.*"), s"status value ${v.take(80)}")
      )
      // MODULE.md: the key of a status unit is -(offset+1), offset = byte offset of the unit.
      assertEquals(status.map(_._1).sorted, hostileStatusKeys, s"status keys at split size $size")
    }
  }

  // ----- hostile records through the pipeline -------------------------------------------------

  private lazy val hostileRun: Run =
    run(
      Inputs(hostileDoc),
      fresh("hostile"),
      conf = Map("scholarchain.dblp.max.record.bytes" -> Cap.toString)
    )

  private def workKeys(r: Run): Set[String] = r.works.map(_("sourceRecordId").str).toSet

  test("hostile input: 3 works kept, 5 units rejected at J01, none lost") {
    assertOk(hostileRun)
    assertEquals(workKeys(hostileRun), hostileAccepted)
    val rejects = hostileRun.rejects("J01")
    observe("hostile J01 reject reasons", rejects.map(_("reason").str))
    observe("hostile J01 counters", hostileRun.counters("J01"))
    assertEquals(rejects.size, 5)
    assertEquals(hostileRun.counter("J01", "REJECTED_RECORDS"), 5L)
    assertEquals(hostileRun.counter("J01", "INPUT_RECORDS"), 8L)
    assertEquals(hostileRun.counter("J01", "SKIPPED_RECORDS"), 0L)
    assert(hostileRun.counter("J01", "SCHEMA_FAILURE") <= 5L, "SCHEMA_FAILURE above REJECTED")
  }

  test("hostile input: each rejected unit is named by exactly one reject line") {
    val inputs = hostileRun.rejects("J01").map(_("input").str)
    hostileRejected.foreach { k =>
      assertEquals(inputs.count(_.contains(s"\"$k\"")), 1, s"reject lines naming $k")
    }
  }

  test("hostile input: reject lines carry stage, reason and run ID; input is at most 2000 chars") {
    hostileRun.rejects("J01").foreach { r =>
      assert(r("stage").str.nonEmpty, r)
      assert(r("reason").str.nonEmpty, r)
      assertEquals(r("runId").str, hostileRun.runId)
      assert(r("input").str.length <= 2000, s"input of ${r("input").str.length} chars")
    }
  }

  test("hostile input: the reject output is itself strict UTF-8") {
    val dir = hostileRun.stageDir("J01").resolve("rejects-v1")
    val files =
      Files.walk(dir).iterator().asScala.filter(_.getFileName.toString.startsWith("part-"))
    files.foreach { p =>
      val dec = UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      try dec.decode(ByteBuffer.wrap(readBytes(p)))
      catch case e: Exception => fail(s"$p is not valid UTF-8: $e")
    }
  }

  test("hostile input: 3 teams and 1+3+1 = 5 pairs; neighbours of bad records are intact") {
    assertEquals(hostileRun.teams.size, 3)
    assertEquals(hostileRun.pairs.size, 5)
    assertEquals(workErrors(hostileRun.works), Vector.empty[String])
    val ok2 = hostileRun.works.find(_("sourceRecordId").str == "journals/adv/ok2").get
    assertEquals(
      ok2("authors").arr.map(_("rawName").str).toVector,
      Vector("Eve Epsilon", "Fay Zeta", "Gus Eta")
    )
  }

  test("multiline title with a tab normalizes to single spaces: 'multi line title'") {
    val ok2 = hostileRun.works.find(_("sourceRecordId").str == "journals/adv/ok2").get
    assertEquals(ok2("normalizedTitle").str, "multi line title")
  }

  test("entities decode once: 'A &amp;lt; B' becomes the text 'A &lt; B', never 'A < B'") {
    val ok3 = hostileRun.works.find(_("sourceRecordId").str == "journals/adv/ok3").get
    assertEquals(ok3("title").str, "A &lt; B")
  }

  // ----- entity declarations (XXE, expansion) -------------------------------------------------

  private val CanaryText = "CANARYXXE7F3A"
  private val InnerCanary = "INNERCANARY9B1"

  private lazy val dtdRun: Run =
    val dir = fresh("dtd")
    val canary = Files.writeString(dir.resolve("canary.txt"), CanaryText, UTF_8)
    val lols = (1 to 6).map { i =>
      val prev = if i == 1 then "lol" else s"lol${i - 1}"
      s"""<!ENTITY lol$i "${s"&$prev;" * 10}">"""
    }
    val header =
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<!DOCTYPE dblp [
         |<!ENTITY xxe SYSTEM "${canary.toUri}">
         |<!ENTITY lol "LOLA">
         |${lols.mkString("\n")}
         |]>
         |<dblp>
         |""".stripMargin
    val body = Seq(
      rec("journals/adv/safe1", Seq("Ann Safe", "Bo Safe"), "Safe Before"),
      rec("journals/adv/xxe", Seq("Cy Leak", "Di Leak"), "Leak &xxe; End"),
      rec("journals/adv/lol", Seq("Ed Laugh", "Flo Laugh"), "Laugh &lol6; End"),
      s"""<article mdate="2026-11-01" key="journals/adv/inner"><!ENTITY e "$InnerCanary"><author>Gil Inner</author><author>Hu Inner</author><title>T &e;</title><year>2020</year><journal>ADVJ</journal></article>""",
      rec("journals/adv/safe2", Seq("Io Safe", "Jo Safe"), "Safe After")
    )
    run(Inputs(utf8(header + body.mkString("\n") + "\n" + Footer)), dir)

  test("entity declarations are never resolved or expanded in any output") {
    val text = dtdRun.everyOutputText
    assert(!text.contains(CanaryText), "the external entity (file canary) was resolved")
    // The raw record (with its declaration) may sit in a reject line; the expanded title may not.
    assert(!text.contains(s"T $InnerCanary"), "an entity declared inside a record was expanded")
    assert(!text.contains("LOLALOLALOLA"), "the nested entity was expanded")
    observe("dtd run output bytes", dtdRun.outputBytes)
    assert(dtdRun.outputBytes < 2L * 1024 * 1024, s"output of ${dtdRun.outputBytes} bytes")
  }

  test(
    "a hostile DTD header does not cost the safe records; entity-bearing records are accounted"
  ) {
    assertOk(dtdRun)
    val keys = workKeys(dtdRun)
    observe("dtd run works", keys.toVector.sorted)
    observe("dtd run J01 rejects", dtdRun.rejects("J01").map(r => r("reason").str))
    assert(keys.contains("journals/adv/safe1"), keys)
    assert(keys.contains("journals/adv/safe2"), keys)
    assert(!keys.contains("journals/adv/inner"), "a record holding <!ENTITY was not refused")
    val rejectInputs = dtdRun.rejects("J01").map(_("input").str)
    for k <- Seq("journals/adv/xxe", "journals/adv/lol", "journals/adv/inner") do
      val asWork = if keys.contains(k) then 1 else 0
      val asReject = rejectInputs.count(_.contains(s"\"$k\""))
      assertEquals(asWork + asReject, 1, s"$k: work=$asWork rejects=$asReject")
  }

  // ----- split boundaries through the pipeline ------------------------------------------------

  private lazy val splitDir: Path = fresh("split")
  private lazy val smallSplitRun: Run =
    run(
      Inputs(utf8(cleanDoc)),
      splitDir,
      outName = "small",
      conf = Map(
        FileInputFormat.SPLIT_MAXSIZE -> "128",
        FileInputFormat.SPLIT_MINSIZE -> "1"
      )
    )
  private lazy val wholeSplitRun: Run = run(Inputs(utf8(cleanDoc)), splitDir, outName = "whole")

  test("128-byte splits: 24 works, 24 teams, 60 author papers, 60 pairs, 2 skipped, 0 rejected") {
    assertOk(smallSplitRun)
    val r = smallSplitRun
    assertEquals(r.works.map(_("sourceRecordId").str).sorted, cleanRecords.map(keyOf).sorted)
    assertEquals(r.teams.size, 24)
    assertEquals(r.authorPapers.size, 60)
    assertEquals(r.pairs.size, 60)
    assertEquals(r.counter("J01", "SKIPPED_RECORDS"), 2L)
    assertEquals(r.counter("J01", "REJECTED_RECORDS"), 0L)
    assertEquals(r.counter("J01", "INPUT_RECORDS"), 26L)
    assertEquals(workErrors(r.works), Vector.empty[String])
  }

  test("the split size changes nothing: same run ID and identical works, teams and pairs") {
    assertOk(wholeSplitRun)
    assertEquals(smallSplitRun.runId, wholeSplitRun.runId)
    assertEquals(smallSplitRun.lines("J01").sorted, wholeSplitRun.lines("J01").sorted)
    assertEquals(
      smallSplitRun.lines("J05", Some("teams")).sorted,
      wholeSplitRun.lines("J05", Some("teams")).sorted
    )
    assertEquals(smallSplitRun.lines("J06").sorted, wholeSplitRun.lines("J06").sorted)
  }
