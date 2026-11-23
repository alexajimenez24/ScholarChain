package scholarchain.adversarial.hadoop

import java.text.Normalizer

import scholarchain.adversarial.hadoop.HadoopBlackBox.*

/** Unicode gate cases (spec Phase 4 Gate "Unicode"; spec "Detailed Hadoop requirements": test the
  * parser against Unicode and entity declarations; spec Phase 11: Unicode normalization and
  * confusable characters; MODULE.md: strict UTF-8, raw names and titles NFC normalized, entities
  * decoded in a single pass).
  *
  * One UTF-8 document that starts with a byte order mark (EF BB BF):
  * {{{
  *   u/nfc     "José Núñez" (NFC), title "Café Society" (NFC)       kept
  *   u/nfd     "José Núñez" (NFD), title "Café Society" (NFD)       kept
  *   u/cyr     "Аnna Kowalska" with CYRILLIC CAPITAL A (U+0410)     kept
  *   u/lat     "Anna Kowalska" all Latin                            kept
  *   u/astral  "Mo 李", title with U+1F600 and U+2000B              kept
  *   u/ent     "J&ouml;rg M&uuml;ller", title with &uuml; &#233; &#xE9; &amp;amp;  kept
  *   u/ref0    title with &#0;      (not a legal XML character)     rejected
  *   u/refsur  title with &#xD800;  (a surrogate, not a character)  rejected
  * }}}
  * units 8 = works 6 + rejects 2. Every second author is a unique "Ann Q..." name.
  */
class UnicodeSuite extends PipelineSuite:

  private def nfd(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
  private def nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

  private val Jose = nfc("José Núñez")
  private val Cafe = nfc("Café Society")
  private val CyrAnna = "Аnna Kowalska"
  private val LatAnna = "Anna Kowalska"
  private val Astral = "Emoji 😀 and 𠀋 survive"

  private val recs = Seq(
    rec("u/nfc", Seq(Jose, s"Ann ${surname(2000)}"), Cafe, Some("2020")),
    rec("u/nfd", Seq(nfd(Jose), s"Ann ${surname(2001)}"), nfd(Cafe), Some("2021")),
    rec("u/cyr", Seq(CyrAnna, s"Ann ${surname(2002)}"), "Confusable One", Some("2020")),
    rec("u/lat", Seq(LatAnna, s"Ann ${surname(2003)}"), "Confusable Two", Some("2021")),
    rec("u/astral", Seq("Mo 李", s"Ann ${surname(2004)}"), Astral, Some("2020")),
    rec(
      "u/ent",
      Seq("J&ouml;rg M&uuml;ller", s"Ann ${surname(2005)}"),
      "M&uuml;ller &#233;t&#xE9; &amp;amp; Co",
      Some("2020")
    ),
    rec("u/ref0", Seq("Nul Ref", s"Ann ${surname(2006)}"), "Nul &#0; Ref", Some("2020")),
    rec("u/refsur", Seq("Sur Ref", s"Ann ${surname(2007)}"), "Sur &#xD800; Ref", Some("2020"))
  )

  private val Bom = Array(0xef, 0xbb, 0xbf).map(_.toByte)

  private lazy val r: Run = run(Inputs(Bom ++ utf8(doc(recs*))), fresh("unicode"))

  private def work(key: String): ujson.Value =
    r.works.find(_("sourceRecordId").str == key).getOrElse(fail(s"no work $key"))
  private def firstAuthor(key: String): ujson.Value = work(key)("authors")(0)

  test("a byte order mark loses no record: 6 works, 2 illegal-reference records rejected") {
    assertOk(r)
    assertEquals(
      r.works.map(_("sourceRecordId").str).sorted,
      Vector("u/astral", "u/cyr", "u/ent", "u/lat", "u/nfc", "u/nfd")
    )
    assertEquals(r.counter("J01", "INPUT_RECORDS"), 8L)
    assertEquals(r.counter("J01", "REJECTED_RECORDS"), 2L)
    val inputs = r.rejects("J01").map(_("input").str)
    assertEquals(inputs.count(_.contains("\"u/ref0\"")), 1)
    assertEquals(inputs.count(_.contains("\"u/refsur\"")), 1)
  }

  test("all retained works and their mentions satisfy the core contract") {
    assertEquals(workErrors(r.works), Vector.empty[String])
    assertEquals(mentionErrors(r.works.flatMap(_("authors").arr)), Vector.empty[String])
  }

  test("an NFD author name is stored NFC: raw and normalized names equal the NFC record's") {
    val a = firstAuthor("u/nfc")
    val b = firstAuthor("u/nfd")
    assertEquals(b("rawName").str, Jose)
    assertEquals(a("rawName").str, Jose)
    assertEquals(b("normalizedName").str, a("normalizedName").str)
  }

  test("an NFD title is stored NFC: title and normalized title equal the NFC record's") {
    assertEquals(work("u/nfd")("title").str, Cafe)
    assertEquals(work("u/nfd")("normalizedTitle").str, work("u/nfc")("normalizedTitle").str)
  }

  test("NFC and NFD forms of one name fall into one name block: a candidate links them") {
    val ids = Set(firstAuthor("u/nfc")("mentionId").str, firstAuthor("u/nfd")("mentionId").str)
    val linking = r.blockedCandidates.filter(c => Set(c("sourceId").str, c("targetId").str) == ids)
    observe("NFC/NFD candidates", linking.map(c => c("blockKey")))
    assert(linking.nonEmpty, s"no blocked candidate links $ids")
  }

  test("confusables: the Cyrillic name is kept exactly, never rewritten into Latin letters") {
    assertEquals(firstAuthor("u/cyr")("rawName").str, CyrAnna)
    assertEquals(firstAuthor("u/lat")("rawName").str, LatAnna)
    observe(
      "normalized names cyr/lat",
      (firstAuthor("u/cyr")("normalizedName").str, firstAuthor("u/lat")("normalizedName").str)
    )
  }

  test("supplementary-plane characters survive; no replacement character in any data output") {
    assertEquals(work("u/astral")("title").str, Astral)
    assertEquals(firstAuthor("u/astral")("rawName").str, "Mo 李")
    val data = (r.lines("J01") ++ r.lines("J05", Some("teams")) ++ r.lines("J06") ++
      r.lines("J07", Some("works")) ++ r.lines("J07", Some("mentions"))).mkString("\n")
    assert(!data.contains("�"), "U+FFFD in a data output")
  }

  test("DBLP named, decimal and hex entities decode once: 'Müller été &amp; Co', 'Jörg Müller'") {
    assertEquals(work("u/ent")("title").str, nfc("Müller été &amp; Co"))
    assertEquals(firstAuthor("u/ent")("rawName").str, nfc("Jörg Müller"))
  }
