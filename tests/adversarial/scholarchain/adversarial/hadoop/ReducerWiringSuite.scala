package scholarchain.adversarial.hadoop

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.nio.file.{Files, Path}
import java.text.Normalizer

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.mapreduce.lib.input.FileInputFormat

import scholarchain.adversarial.hadoop.HadoopBlackBox.*

/** Phase 11 carried item C18 (Phase 04 N7, `docs/agent-runs/phase-04/review-report-02.md`): the
  * black-box ISO-8859-1 DBLP case, and job-level runs of the reducer wiring added in Phase 04
  * repair round 1 (J03 exact-key cap, J04 refused fallback blocks).
  *
  * Public contract only (`modules/hadoop/MODULE.md`, "Reject output" encoding rule, "Counters"):
  *   - the reader decodes with the encoding declared in the prolog (ISO-8859-1 for DBLP); with
  *     ISO-8859-1 every byte is valid, so only the XML character rules reject (a raw 0x01); the
  *     charset is read once from byte 0, whatever the split; outputs are UTF-8;
  *   - J03 `OVERSIZE_BLOCKS`: an exact key with more works than `--max-block` gets no work pairs,
  *     counts, and writes the reject `exact key <key> holds N works > M; work pairs not expanded`;
  *   - J04 `FALLBACK_BLOCKS_REFUSED`: a fallback block above `--max-block` is refused, counted, and
  *     writes a reject line; it increments `REJECTED_RECORDS` but not `OVERSIZE_BLOCKS`.
  */
class ReducerWiringSuite extends PipelineSuite:

  private def nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

  // ----- ISO-8859-1 ---------------------------------------------------------------------------------

  private val IsoHeader =
    "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<!DOCTYPE dblp SYSTEM \"dblp.dtd\">\n<dblp>\n"

  private val Rene = "René Dupont"
  private val Jorg = "Jörg Müller"
  private val Asa = "Åsa Ørsted"
  private val CafeTitle = "Café façade ½ size: naïve Ångström Æsir analysis"
  private val NoelTitle = "Noël and the señor of Zürich"

  private val isoRecs = Seq(
    rec("iso/a", Seq(Rene, s"Ann ${surname(3000)}"), CafeTitle),
    rec("iso/b", Seq(Jorg, Asa, s"Ann ${surname(3001)}"), NoelTitle, Some("2021")),
    rec("iso/c", Seq(s"Ann ${surname(3002)}", s"Ann ${surname(3003)}"), "Plain ASCII title"),
    // a raw 0x01 is not an XML character: rejected under every encoding
    rec("iso/ctl", Seq(s"Ann ${surname(3004)}", s"Ann ${surname(3005)}"), "Bad \u0001 control")
  )

  private val isoBytes: Array[Byte] =
    (IsoHeader + isoRecs.mkString("\n") + "\n" + Footer).getBytes(ISO_8859_1)

  private lazy val isoDir: Path = fresh("iso")
  private lazy val isoWhole: Run = run(Inputs(isoBytes), isoDir, outName = "whole")
  private lazy val isoSplit: Run =
    run(
      Inputs(isoBytes),
      isoDir,
      outName = "split64",
      conf = Map(FileInputFormat.SPLIT_MAXSIZE -> "64", FileInputFormat.SPLIT_MINSIZE -> "1")
    )

  private def work(r: Run, key: String): ujson.Value =
    r.works.find(_("sourceRecordId").str == key).getOrElse(fail(s"no work $key"))

  private def names(r: Run, key: String): Vector[String] =
    work(r, key)("authors").arr.map(_("rawName").str).toVector

  private def strictUtf8(bytes: Array[Byte]): Boolean =
    val dec = UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    scala.util.Try(dec.decode(ByteBuffer.wrap(bytes))).isSuccess

  test("ISO-8859-1 fixture: high bytes are single Latin-1 bytes, never UTF-8 sequences") {
    assert(isoBytes.exists(b => (b & 0xff) == 0xe9), "no raw 0xE9")
    assert(!isoBytes.sliding(2).exists(w => (w(0) & 0xff) == 0xc3 && (w(1) & 0xff) == 0xa9))
  }

  test("ISO-8859-1: 3 works kept with exact Latin-1 names and titles; the 0x01 record rejected") {
    assertOk(isoWhole)
    val r = isoWhole
    observe("ISO J01 counters", r.counters("J01"))
    observe("ISO J01 rejects", r.rejects("J01").map(_("reason").str))
    assertEquals(r.works.map(_("sourceRecordId").str).sorted, Vector("iso/a", "iso/b", "iso/c"))
    assertEquals(names(r, "iso/a").head, nfc(Rene))
    assertEquals(names(r, "iso/b").take(2), Vector(nfc(Jorg), nfc(Asa)))
    assertEquals(work(r, "iso/a")("title").str, nfc(CafeTitle))
    assertEquals(work(r, "iso/b")("title").str, nfc(NoelTitle))
    assertEquals(r.counter("J01", "REJECTED_RECORDS"), 1L)
    assertEquals(r.rejects("J01").count(_("input").str.contains("iso/ctl")), 1)
    assertEquals(r.counter("J01", "UNSUPPORTED_ENCODING"), 0L)
    assertEquals(workErrors(r.works), Vector.empty[String])
  }

  test("ISO-8859-1: every output file is strict UTF-8, with no mojibake or replacement character") {
    val r = isoWhole
    val files = Files
      .walk(r.output)
      .iterator()
      .asScala
      .filter(Files.isRegularFile(_))
      .filter(p => !p.getFileName.toString.endsWith(".crc") && !p.toString.contains("_local-tmp"))
      .toVector
    val bad = files.filterNot(p => strictUtf8(readBytes(p)))
    assertEquals(bad, Vector.empty[Path], "output files that are not strict UTF-8")
    val text = r.everyOutputText
    assert(!text.contains("�"), "U+FFFD in an output")
    assert(!text.contains("Ã"), "UTF-8 read as Latin-1 (mojibake) in an output")
    assert(text.contains(nfc(Rene)), "the Latin-1 name is missing from the outputs")
  }

  test("ISO-8859-1 at 64-byte splits: the charset still comes from byte 0; output unchanged") {
    assertOk(isoSplit)
    assertEquals(isoSplit.runId, isoWhole.runId)
    assertEquals(isoSplit.lines("J01").sorted, isoWhole.lines("J01").sorted)
    assertEquals(isoSplit.lines("J06").sorted, isoWhole.lines("J06").sorted)
    assertEquals(isoSplit.counter("J01", "REJECTED_RECORDS"), 1L)
  }

  // ----- J03 exact-key cap and J04 refused fallback blocks, one run with --max-block 3 ----------

  private val Cap = 3
  private val SharedDoi = "10.4242/adv.shared.2020"
  private val PairDoi = "10.4242/adv.pair.2020"

  private def doiRec(key: String, i: Int, doi: String) =
    rec(
      key,
      Seq(s"Ann ${surname(3100 + 2 * i)}", s"Ann ${surname(3101 + 2 * i)}"),
      s"${word("Dx", 3100 + i)} ${word("Dy", 3200 + i)} study",
      extra = s"<ee>https://doi.org/$doi</ee>"
    )

  // 4 works under one DOI (> cap 3) and 2 under another (the control)
  private val sharedKeys = (1 to 4).map(i => s"x/shared$i")
  private val pairKeys = Seq("x/pair1", "x/pair2")
  private val doiRecs =
    sharedKeys.zipWithIndex.map((k, i) => doiRec(k, i, SharedDoi)) ++
      pairKeys.zipWithIndex.map((k, i) => doiRec(k, 10 + i, PairDoi))

  // Name block zhang_h with 4 distinct full names (> cap 3), all with one shared coauthor and one
  // year, so the co: and y: fallback blocks each hold 4 (> cap 3). The records carry a journal
  // name but no venue ID (observed in Phase 11: no v: block), and a key is never built from an
  // empty attribute (MODULE.md), so exactly 2 fallback blocks are refused.
  private val zhangNames = Seq("Hao Zhang", "Hui Zhang", "Hong Zhang", "Hua Zhang")
  private val Shared = s"Ann ${surname(3300)}"
  private val zhangRecs = zhangNames.zipWithIndex.map((n, i) =>
    rec(s"z/zhang$i", Seq(n, Shared), s"${word("Zq", 3400 + i)} ${word("Zr", 3500 + i)} method")
  )

  private lazy val capRun: Run =
    run(
      Inputs(utf8(doc((doiRecs ++ zhangRecs)*))),
      fresh("caps"),
      extra = Seq("--max-block", Cap.toString)
    )

  private def workId(key: String): String =
    capRun.works.find(_("sourceRecordId").str == key).getOrElse(fail(s"no work $key"))("workId").str

  private def endpoints(c: ujson.Value) = Set(c("sourceId").str, c("targetId").str)

  test("caps run: all 10 works kept; nothing rejected at J01") {
    assertOk(capRun)
    assertEquals(capRun.works.size, 10)
    assertEquals(capRun.counter("J01", "REJECTED_RECORDS"), 0L)
  }

  test("J03: an exact key above --max-block is counted, rejected with its reason, never paired") {
    val r = capRun
    observe("J03 counters", r.counters("J03"))
    observe("J03 rejects", r.rejects("J03").map(_("reason").str))
    val shared = sharedKeys.map(workId).toSet
    val inShared = r.exactCandidates.filter(c => endpoints(c).subsetOf(shared))
    val pair = pairKeys.map(workId).toSet
    assertEquals(r.counter("J03", "OVERSIZE_BLOCKS"), 1L, r.counters("J03"))
    assertEquals(r.rejects("J03").size.toLong, r.counter("J03", "REJECTED_RECORDS"))
    val reasons = r.rejects("J03").map(_("reason").str)
    assert(
      reasons.exists(x => x.contains(SharedDoi) && x.contains(s"holds 4 works > $Cap")),
      s"no J03 reject naming the key, its 4 works and the cap $Cap: $reasons"
    )
    assert(
      !reasons.exists(_.contains(PairDoi)),
      s"the control key under the cap was rejected: $reasons"
    )
    assertEquals(
      inShared.map(endpoints),
      Vector.empty[Set[String]],
      "work pairs under the oversize key"
    )
    assert(
      r.exactCandidates.exists(c => endpoints(c) == pair),
      "the control DOI pair is not linked"
    )
  }

  test("J03 reject lines carry the stage, the run ID and the input bound") {
    val r = capRun
    r.rejects("J03").foreach { x =>
      assert(x("stage").str.nonEmpty, x)
      assertEquals(x("runId").str, r.runId)
      assert(x("input").str.length <= 2000, x)
    }
  }

  test("J04: refused fallback blocks are counted and rejected one line each, never as OVERSIZE") {
    val r = capRun
    observe("J04 counters", r.counters("J04"))
    observe("J04 rejects", r.rejects("J04").map(_("reason").str))
    observe("largest block", r.summary("largestBlockObserved"))
    val refused = r.counter("J04", "FALLBACK_BLOCKS_REFUSED")
    val oversize = r.counter("J04", "OVERSIZE_BLOCKS")
    // the co: and y: fallback blocks of zhang_h each hold 4 clusters (> 3); no v: block exists
    assertEquals(refused, 2L, s"refused fallback blocks: ${r.counters("J04")}")
    // at most the one primary block (zhang_h) can be OVERSIZE; a refused fallback block never is
    assert(oversize <= 1L, s"refused fallback blocks counted as OVERSIZE: ${r.counters("J04")}")
    assertEquals(r.counter("J04", "REJECTED_RECORDS"), oversize + refused, r.counters("J04"))
    assertEquals(r.rejects("J04").size.toLong, oversize + refused, "one reject line per refusal")
    assert(
      r.rejects("J04").count(_("reason").str.contains("zhang_h|")) == refused.toInt,
      "each refused fallback block names its key in the reject reason"
    )
  }

  // MODULE.md "Key-value contracts": "A researcher or work block above `--max-block` is not
  // compared as a whole (`OVERSIZE_BLOCKS`)", and the fallback trigger is "a researcher block with
  // more than `--max-block` clusters". zhang_h has 4 clusters > 3 and triggers the fallback keys
  // (the test above), so it is a block above the cap and must be counted. Spec 2245: "counters ...
  // and a policy for oversize groups".
  test("J04: the primary name block above --max-block that triggered fallback is OVERSIZE_BLOCKS") {
    val r = capRun
    assert(r.counter("J04", "FALLBACK_BLOCKS_REFUSED") >= 1L, "precondition: fallback triggered")
    assertEquals(r.counter("J04", "OVERSIZE_BLOCKS"), 1L, r.counters("J04"))
  }

  test("J04: no candidate pairs two Zhangs, since every block holding them is above the cap") {
    val r = capRun
    val zhangs = zhangNames.indices
      .map(i => work(r, s"z/zhang$i")("authors")(0)("mentionId").str)
      .toSet
    val within = r.blockedCandidates.filter(c => endpoints(c).subsetOf(zhangs))
    assertEquals(within.map(c => c("blockKey").str), Vector.empty[String])
  }
