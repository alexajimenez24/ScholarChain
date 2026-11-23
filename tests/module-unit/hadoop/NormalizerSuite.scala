package scholarchain.hadoop

import scholarchain.core.*

class NormalizerSuite extends munit.FunSuite:
  private val run = FixtureIO.run

  private lazy val oa =
    FixtureIO.lines("openalex-works-fixture.jsonl").map(SourceNormalizers.openAlex(_, run))
  private lazy val cr =
    FixtureIO.lines("crossref-works-fixture.jsonl").map(SourceNormalizers.crossref(_, run))
  private lazy val orcids =
    FixtureIO.lines("orcid-records-fixture.jsonl").map(SourceNormalizers.orcid(_, run))

  test("OpenAlex fixture: 12 works with 29 authorships, every one valid under core") {
    // openalex-works-fixture.jsonl has 12 lines; authors per work 2,3,2,3,2,3,2,3,2,3,2,2 = 29
    assert(oa.forall(_.isRight))
    val works = oa.flatMap(_.toOption).flatMap(_.works)
    assertEquals(works.size, 12)
    assertEquals(works.map(_.authors.size).sum, 29)
    assert(works.forall(w => Validate.work(w).isEmpty))
    assert(works.forall(_.source == SourceType.OPENALEX))
    assert(works.forall(w => w.openAlexId.exists(i => w.workId == s"openalex:$i")))
  }

  test("OpenAlex keeps author IDs, ORCIDs, institutions, topics and venue IDs") {
    val n = oa.flatMap(_.toOption)
    val mentions = n.flatMap(_.works).flatMap(_.authors)
    assert(mentions.forall(_.externalAuthorId.exists(_.startsWith("openalex:A"))))
    // ORCID on W..001, 002, 005, 010 (Carol Chen) and 006, 008, 011 (Eve Evans) = 7
    assertEquals(mentions.count(_.orcid.nonEmpty), 7)
    assertEquals(mentions.flatMap(_.orcid).toSet, Set("0000-0009-9000-0018", "0000-0009-9000-0026"))
    // every work has one topic row (12); two distinct topics T10001 and T10002
    assertEquals(n.flatMap(_.topics).size, 12)
    assertEquals(
      n.flatMap(_.topics).map(_.topicId).toSet,
      Set("openalex:T10001", "openalex:T10002")
    )
    // Carol or Ivy has institution I900000001 on W..001, 002, 005, 010, 011 = 5 rows, 1 distinct
    assertEquals(n.flatMap(_.institutions).size, 5)
    assertEquals(n.flatMap(_.institutions).map(_.institutionId).toSet, Set("openalex:I900000001"))
    assert(n.flatMap(_.topics).forall(t => Validate.topic(t).isEmpty))
    assert(n.flatMap(_.institutions).forall(i => Validate.institution(i).isEmpty))
    val first = n.head.works.head
    assertEquals(first.doi, Some("10.5555/sc.fix.001"))
    assertEquals(first.venueId, Some("venue:openalex:S800000065"))
    assertEquals(first.venueName, Some("FIXA"))
    assertEquals(first.topicIds, Vector("openalex:T10001"))
  }

  test("Crossref fixture: 8 works with 20 authors, DOIs lower-cased, entities decoded") {
    // crossref-works-fixture.jsonl has 8 lines; authors per work 2,3,2,3,2,3,2,3 = 20
    assert(cr.forall(_.isRight))
    val works = cr.flatMap(_.toOption).flatMap(_.works)
    assertEquals(works.size, 8)
    assertEquals(works.map(_.authors.size).sum, 20)
    assert(works.forall(w => Validate.work(w).isEmpty))
    assertEquals(works.head.doi, Some("10.5555/sc.fix.001"))
    assertEquals(works.head.workId, "crossref:10.5555/sc.fix.001")
    assertEquals(works.head.authors.map(_.rawName), Vector("Alice Archer", "Bob Baker"))
    assertEquals(works.head.year, Some(2015))
    assertEquals(works.head.venueName, Some("FIXA"))
    // "M&uuml;ller" in the Crossref title becomes the same normalized title as DBLP and OpenAlex
    val t3 = works.find(_.doi.contains("10.5555/sc.fix.003")).get
    assertEquals(t3.title, "Fault Tolerance in Müller Networks")
    assertEquals(t3.normalizedTitle, "fault tolerance in muller networks")
    // ORCID on 002 (Carol), 003 (Carol), 006 (Carol), 101 (Eve) = 4
    assertEquals(works.flatMap(_.authors).count(_.orcid.nonEmpty), 4)
  }

  test("ORCID records become external researchers with normalized work DOIs") {
    val rs = orcids.flatMap(_.toOption).flatMap(_.researchers)
    assertEquals(
      rs.map(_.recordId),
      Vector("orcid:0000-0009-9000-0018", "orcid:0000-0009-9000-0026")
    )
    assertEquals(rs.head.displayName, "Carol Chen")
    assertEquals(
      rs.head.workDois,
      Vector("10.5555/sc.fix.002", "10.5555/sc.fix.003", "10.5555/sc.fix.201")
    )
    assertEquals(rs(1).workDois, Vector("10.5555/sc.fix.101", "10.5555/sc.fix.103"))
    assertEquals(rs.head.affiliations, Vector("Fixture University"))
    assert(rs.forall(r => HadoopValidate.externalResearcher(r).isEmpty))
  }

  test("abstracts are rebuilt from OpenAlex inverted indexes and stored apart from the work") {
    val line =
      """{"id":"https://openalex.org/W55","display_name":"T","publication_year":2020,"abstract_inverted_index":{"Graphs":[0],"scale":[2],"do":[1]},"authorships":[]}"""
    val n = SourceNormalizers.openAlex(line, run).toOption.get
    assertEquals(n.abstracts.map(_.text), Vector("Graphs do scale"))
    assertEquals(n.works.head.abstractTextRef, Some(Hashing.tagged("Graphs do scale")))
    assertEquals(n.abstracts.head.textHash, Hashing.tagged("Graphs do scale"))
    val cross =
      """{"DOI":"10.1000/AB","title":["T"],"abstract":"<jats:p>Fast &amp; <jats:italic>safe</jats:italic></jats:p>","author":[]}"""
    val c = SourceNormalizers.crossref(cross, run).toOption.get
    assertEquals(c.abstracts.map(_.text), Vector("Fast & safe"))
  }

  test("malformed and unsupported source records are rejected with a reason") {
    def left(r: Either[String, SourceNormalizers.Normalized]): String =
      r.left.getOrElse(fail("accepted"))
    assert(left(SourceNormalizers.openAlex("{", run)).contains("not JSON"))
    assert(
      left(SourceNormalizers.openAlex("""{"id":"x","display_name":"T"}""", run)).contains("work id")
    )
    assert(
      left(
        SourceNormalizers.openAlex("""{"id":"https://openalex.org/W11","display_name":" "}""", run)
      )
        .contains("empty title")
    )
    assert(
      left(
        SourceNormalizers.openAlex(
          """{"id":"https://openalex.org/W11","display_name":"T","publication_year":2015.5}""",
          run
        )
      ).contains("whole number")
    )
    assert(
      left(
        SourceNormalizers.openAlex(
          """{"id":"https://openalex.org/W11","display_name":"T","authorships":[{"author":{}}]}""",
          run
        )
      ).contains("no author name")
    )
    assert(
      left(SourceNormalizers.crossref("""{"DOI":"nope","title":["t"]}""", run)).contains("DOI")
    )
    assert(
      left(SourceNormalizers.crossref("""{"DOI":"10.1000/x","title":[]}""", run))
        .contains("empty title")
    )
    assert(
      left(SourceNormalizers.orcid("""{"orcid-identifier":{"path":"0000-0002-1825-0098"}}""", run))
        .contains("invalid iD")
    )
    assert(SourceNormalizers.forSource(SourceType.DBLP).isLeft)
  }

  test("an identifier that does not validate is dropped and counted, not guessed") {
    val line =
      """{"id":"https://openalex.org/W99","display_name":"T","doi":"not-a-doi","authorships":[{"raw_author_name":"A B","author":{"id":"https://openalex.org/A1234","orcid":"0000-0002-1825-0098"}}]}"""
    val n = SourceNormalizers.openAlex(line, run).toOption.get
    assertEquals(n.works.head.doi, None)
    assertEquals(n.works.head.authors.head.orcid, None)
    assertEquals(n.invalidIdentifiers, 2)
  }

  test("a null JSON value in an optional field is absent, not an error") {
    val line =
      """{"id":"https://openalex.org/W77","display_name":"T","doi":null,"publication_year":null,"primary_location":null,"authorships":[{"raw_author_name":"A B","institutions":[null,{"id":null}]}],"topics":null}"""
    val w = SourceNormalizers.openAlex(line, run).toOption.get.works.head
    assertEquals((w.doi, w.year, w.venueId), (None, None, None))
    assertEquals(w.authors.head.affiliation, None)
  }

  test("DOI case, arXiv, OpenAlex and Semantic Scholar identifiers normalize") {
    assertEquals(Identifiers.doi("https://doi.org/10.1000/ABC"), Some("10.1000/abc"))
    assertEquals(Identifiers.doi("doi:10.1000/x y"), None)
    assertEquals(Identifiers.arxiv("arXiv:2101.00001v3"), Some("2101.00001"))
    assertEquals(
      Identifiers.arxiv("https://arxiv.org/pdf/hep-th/9901001v1.pdf"),
      Some("hep-th/9901001")
    )
    assertEquals(Identifiers.arxiv("https://arxiv.org/abs/21.1"), None)
    assertEquals(Identifiers.openAlex("https://openalex.org/w123456"), Some("W123456"))
    assertEquals(Identifiers.openAlex("W1"), None)
    assertEquals(Identifiers.openAlex("https://example.org/W123"), None)
    val sha = "0123456789abcdef0123456789ABCDEF01234567"
    assertEquals(Identifiers.semanticScholar(sha), Some(sha.toLowerCase))
    assertEquals(Identifiers.semanticScholar("CorpusId: 12345"), Some("corpus:12345"))
    assertEquals(Identifiers.semanticScholar("nope"), None)
    assertEquals(
      Identifiers.orcid("https://orcid.org/0000-0002-1825-0097"),
      Some("0000-0002-1825-0097")
    )
    assertEquals(Identifiers.orcid("0000-0002-1825-0098"), None)
  }

  test("name block keys: last token and first initial of the folded name") {
    assertEquals(Identifiers.nameBlockKey("Wei Wang 0001"), "wang_w")
    assertEquals(Identifiers.nameBlockKey("Wang, Wei"), "wang_w")
    assertEquals(Identifiers.nameBlockKey("Jörg Müller"), "muller_j")
    assertEquals(Identifiers.nameBlockKey("J. K. Aggarwal"), "aggarwal_j")
    assertEquals(Identifiers.nameBlockKey("Madonna"), "madonna_m")
    assertEquals(Identifiers.nameBlockKey("  ...  "), "")
  }
