package scholarchain.core

class NormalizeSuite extends munit.FunSuite:
  test("titles lose case, accents, markup and punctuation") {
    assertEquals(Normalize.title("Graphs, at <i>Scale</i>: Müller's"), "graphs at scale muller s")
  }
  test("person names drop DBLP homonym suffixes and fix display order") {
    assertEquals(Normalize.personName("Wei Wang 0003"), "wei wang")
    assertEquals(Normalize.personName("Wang, Wei"), "wei wang")
  }
  test("DOIs are bare and lower case") {
    assertEquals(Normalize.doi("https://doi.org/10.1000/ABC"), Some("10.1000/abc"))
    assertEquals(Normalize.doi("not a doi"), None)
  }
  test("ORCID check digits are validated") {
    assertEquals(
      Normalize.orcid("https://orcid.org/0000-0002-1825-0097"),
      Some("0000-0002-1825-0097")
    )
    assertEquals(Normalize.orcid("0000000218250097"), Some("0000-0002-1825-0097"))
    assertEquals(Normalize.orcid("0000-0002-1825-0098"), None)
    assertEquals(Normalize.orcid("0000-0001-5109-3700"), Some("0000-0001-5109-3700"))
  }
