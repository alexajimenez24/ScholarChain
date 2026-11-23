package scholarchain.er

import scholarchain.core.EntityKind

class ErNormalizeSuite extends munit.FunSuite:
  import ErNormalize.*

  test("Unicode: accents, compatibility forms and letters NFKD keeps are folded") {
    assertEquals(text("Müller"), "muller")
    assertEquals(text("Łukasz Ørsted"), "lukasz orsted")
    assertEquals(text("Straße"), "strasse")
    assertEquals(text("ﬁnite ＡＢＣ"), "finite abc") // ligature and full-width letters
    assertEquals(text("Æsir Œuvre Þór"), "aesir oeuvre thor")
  }

  test("punctuation and every kind of whitespace collapse to single spaces") {
    assertEquals(text("  Graph-based \tlearning:\n a   survey!  "), "graph based learning a survey")
    assertEquals(text("..."), "")
  }

  test("title markup: tags, entities and TeX are removed before folding") {
    assertEquals(title("The <i>E. coli</i> Genome &amp; <sub>2</sub>O"), "the e coli genome 2 o")
    assertEquals(
      title("Learning with $\\mathcal{O}(n)$ \\emph{memory}"),
      "learning with o n memory"
    )
    assertEquals(title("A &lt;b&gt; tag &#233;t&#xE9;"), "a b tag ete")
    assertEquals(
      titleTokens("The Art of Computer Programming"),
      Set("art", "computer", "programming")
    )
  }

  test("name order: 'Last, First', homonym and generational suffixes, particles") {
    assertEquals(personName("Smith, John"), PersonName(Vector("john"), "smith"))
    assertEquals(personName("Wei Wang 0003"), PersonName(Vector("wei"), "wang"))
    assertEquals(
      personName("Martin Luther King, Jr."),
      PersonName(Vector("martin", "luther"), "king")
    )
    assertEquals(
      personName("King, Martin Luther, Jr."),
      PersonName(Vector("martin", "luther"), "king")
    )
    assertEquals(personName("Ludwig van Beethoven").family, "beethoven")
    assertEquals(personName("Seán O'Brien"), PersonName(Vector("sean"), "obrien"))
    assertEquals(personName("Plato"), PersonName(Vector.empty, "plato"))
  }

  test("initials: dotted, hyphenated and spaced given names give the same initials") {
    assertEquals(personName("J.R.R. Tolkien").initials, Vector('j', 'r', 'r'))
    assertEquals(personName("Jean-Pierre Serre").initials, Vector('j', 'p'))
    assertEquals(personName("J. P. Serre").key, "serre_j")
    assertEquals(personName("Wang Wei").swapped, Some(PersonName(Vector("wei"), "wang")))
  }

  test("DOI: resolver, doi: and urn prefixes, case, percent-encoding and trailing punctuation") {
    val expected = Some("10.1145/3292500.3330701")
    Vector(
      "10.1145/3292500.3330701",
      "https://doi.org/10.1145/3292500.3330701",
      "http://dx.doi.org/10.1145/3292500.3330701",
      "doi.org/10.1145/3292500.3330701",
      "DOI: 10.1145/3292500.3330701",
      "doi:10.1145/3292500.3330701.",
      "urn:doi:10.1145/3292500.3330701",
      "https://doi.org/10.1145%2F3292500.3330701"
    ).foreach(raw => assertEquals(doi(raw), expected, raw))
    assertEquals(
      doi("10.1002/(SICI)1097-4571(199806)49:8<693::AID-ASI4>3.0.CO;2-O").map(_.take(8)),
      Some("10.1002/")
    )
    assertEquals(doi("not a doi"), None)
    assertEquals(doi("11.1145/x"), None)
  }

  test("arXiv: new and old style, versions, URLs and the arXiv DOI") {
    assertEquals(arxiv("arXiv:2101.00001v3"), Some("2101.00001"))
    assertEquals(arxiv("https://arxiv.org/abs/2101.00001"), Some("2101.00001"))
    assertEquals(arxiv("https://arxiv.org/pdf/2101.00001v2.pdf"), Some("2101.00001"))
    assertEquals(arxiv("hep-th/9901001v1"), Some("hep-th/9901001"))
    assertEquals(arxiv("math.GT/0309136"), Some("math.gt/0309136"))
    assertEquals(arxiv("10.48550/arXiv.2101.00001"), Some("2101.00001"))
    assertEquals(arxiv("2101.0001x"), None)
  }

  test("ORCID: check digit enforced; URL, www and orcid: forms accepted") {
    assertEquals(orcid(ErFixtures.OrcidA), Some(ErFixtures.OrcidA))
    assertEquals(orcid(ErFixtures.OrcidC4), Some(ErFixtures.OrcidC4))
    assertEquals(orcid(ErFixtures.OrcidC5), Some(ErFixtures.OrcidC5))
    assertEquals(orcid("https://www.orcid.org/0000-0002-1825-0097"), Some(ErFixtures.OrcidA))
    assertEquals(orcid("orcid:0000000218250097"), Some(ErFixtures.OrcidA))
    assertEquals(orcid("0000-0002-1825-0098"), None) // wrong check digit
  }

  test("OpenAlex: entity letter checked, URL and prefix forms accepted") {
    assertEquals(openAlex("https://openalex.org/A5054810403", 'A'), Some("A5054810403"))
    assertEquals(openAlex("openalex:A5054810403", 'A'), Some("A5054810403"))
    assertEquals(openAlex("w2741809807", 'W'), Some("W2741809807"))
    assertEquals(openAlex("W2741809807", 'A'), None)
  }

  test("Semantic Scholar: sha, CorpusID and author forms stay apart") {
    val sha = "649def34f8be52c8b66281af98ae884c09aef38b"
    assertEquals(
      semanticScholarPaper(s"https://www.semanticscholar.org/paper/Some-Title/$sha"),
      Some(s"sha:$sha")
    )
    assertEquals(semanticScholarPaper(sha.toUpperCase), Some(s"sha:$sha"))
    assertEquals(semanticScholarPaper("CorpusID:0215416146"), Some("corpus:215416146"))
    assertEquals(
      semanticScholarAuthor("https://www.semanticscholar.org/author/J-Smith/1741101"),
      Some("author:1741101")
    )
    assertEquals(
      semanticScholarPaper("https://www.semanticscholar.org/author/J-Smith/1741101"),
      None
    )
  }

  test("repository URL: host, owner and repo only; known hosts only") {
    assertEquals(repositoryUrl("https://github.com/Apache/Spark"), Some("github.com/apache/spark"))
    assertEquals(
      repositoryUrl("http://www.github.com/apache/spark.git"),
      Some("github.com/apache/spark")
    )
    assertEquals(
      repositoryUrl("https://github.com/apache/spark/tree/master/core?x=1#y"),
      Some("github.com/apache/spark")
    )
    assertEquals(repositoryUrl("gitlab.com/group/proj"), Some("gitlab.com/group/proj"))
    assertEquals(repositoryUrl("https://github.com/orgs/apache"), None)
    assertEquals(repositoryUrl("https://example.org/a/b"), None)
  }

class ExactIdsSuite extends munit.FunSuite:
  import ErFixtures.*

  test("a work's identifiers come from fields, the work ID namespace and URLs") {
    val w = Profiles.work(
      work(
        "crossref:10.1000/ABC",
        scholarchain.core.SourceType.CROSSREF,
        "T",
        None,
        Vector(Author("A B")),
        openAlexId = Some("https://openalex.org/W42"),
        urls = Vector(
          "https://arxiv.org/abs/2101.00001v2",
          "https://github.com/a/b",
          "https://doi.org/10.48550/arXiv.2202.00002"
        )
      ),
      limits
    )
    val ids = ExactIds.ofWork(w)
    assertEquals(ids(IdScheme.DOI), Set("10.1000/abc"))
    assertEquals(
      ids(IdScheme.ARXIV),
      Set("2101.00001", "2202.00002")
    ) // the arXiv DOI is read as arXiv
    assertEquals(ids(IdScheme.OPENALEX), Set("W42"))
    assertEquals(ids(IdScheme.REPO), Set("github.com/a/b"))
  }

  test("candidate identifiers are normalized by kind, and unknown names are ignored") {
    val r = ExactIds.ofCandidateSide(
      EntityKind.RESEARCHER,
      Map(
        "orcid" -> Vector(OrcidA),
        "openalex" -> Vector("openalex:A123"),
        "doi" -> Vector("10.1/x")
      )
    )
    assertEquals(r(IdScheme.ORCID), Set(OrcidA))
    assertEquals(r(IdScheme.OPENALEX), Set("A123"))
    assertEquals(r(IdScheme.DOI), Set.empty[String])
    assertEquals(ExactIds.ofResearcherId(s"orcid:$OrcidA")(IdScheme.ORCID), Set(OrcidA))
  }

  test("compare: equal, disagree, absent and the multi-valued contradiction") {
    assertEquals(ExactIds.compare(Set("a"), Set("a")), Agreement(true, false))
    assertEquals(ExactIds.compare(Set("a"), Set("b")), Agreement(false, true))
    assertEquals(ExactIds.compare(Set("a"), Set.empty), Agreement.Absent)
    assertEquals(ExactIds.compare(Set("a"), Set("a", "b")), Agreement(true, false))
    assertEquals(ExactIds.compare(Set("a", "b"), Set("a", "c")), Agreement(true, true))
  }

  test("preprint DOIs never disagree, but still agree; S2 families are compared apart") {
    val preprint = ErFixtures.policyConfig.isPreprintDoi
    assertEquals(
      ExactIds.compare(Set("10.1101/2020.01.01.1"), Set("10.1000/pub"), preprint),
      Agreement.Absent
    )
    assertEquals(
      ExactIds.compare(Set("10.1101/x"), Set("10.1101/x"), preprint),
      Agreement(true, false)
    )
    val fam = ExactIds.familyOf(IdScheme.S2)
    assertEquals(ExactIds.compare(Set("sha:aa"), Set("corpus:1"), family = fam), Agreement.Absent)
    assertEquals(
      ExactIds.compare(Set("corpus:2"), Set("corpus:1"), family = fam),
      Agreement(false, true)
    )
    // A DOI with a colon is still compared (one family for every non-S2 scheme).
    val doiFam = ExactIds.familyOf(IdScheme.DOI)
    assertEquals(
      ExactIds.compare(Set("10.1/a:b"), Set("10.1/c:d"), family = doiFam),
      Agreement(false, true)
    )
  }

class TextSimilaritySuite extends munit.FunSuite:
  import TextSimilarity.*

  test("Jaro-Winkler matches the textbook values and is symmetric") {
    assertEqualsDouble(jaroWinkler("martha", "marhta"), 0.9611, 1e-4)
    assertEqualsDouble(jaroWinkler("dixon", "dicksonx"), 0.8133, 1e-4)
    assertEqualsDouble(jaroWinkler("abc", "xyz"), 0.0, 1e-12)
    assertEquals(jaroWinkler("a", ""), 0.0)
    assertEquals(jaroWinkler("dwayne", "duane"), jaroWinkler("duane", "dwayne"))
  }

  test("Jaccard, trigram Dice and the order-aware sequence similarity") {
    assertEqualsDouble(jaccard(Set(1, 2, 3), Set(2, 3, 4)), 0.5, 1e-12)
    assertEquals(jaccard(Set.empty[Int], Set.empty[Int]), 0.0)
    assertEquals(trigramDice("graph", "graph"), 1.0)
    assert(trigramDice("graph processing", "graph procesing") > 0.8)
    assert(trigramDice("deep learning", "database indexing") < 0.3)
    assertEquals(lcs(Vector(1, 2, 3, 4), Vector(1, 3, 4)), 3)
    assertEqualsDouble(sequenceSimilarity(Vector("a", "b"), Vector("b", "a")), 0.5, 1e-12)
    assertEquals(sequenceSimilarity(Vector.empty[String], Vector("a")), 0.0)
  }
