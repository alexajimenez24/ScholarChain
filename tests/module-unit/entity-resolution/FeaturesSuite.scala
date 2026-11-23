package scholarchain.er

import scholarchain.core.*

class FeaturesSuite extends munit.FunSuite:
  import ErFixtures.*
  import ErFixtures.Run.*

  private def researcher(c: ErCandidate, l: EndpointProfile, r: EndpointProfile) =
    Features.researcher(merged(c), l, r, policyConfig)

  private def pair(
      w1: WorkRecord,
      i1: Int,
      w2: WorkRecord,
      i2: Int,
      f: Map[String, Double] = Map.empty
  ) =
    val (a, b) = Ids.orderedPair(mentionId(w1, i1), mentionId(w2, i2))
    val (pa, pb) =
      if a == mentionId(w1, i1) then (mentionProfile(w1, i1), mentionProfile(w2, i2))
      else (mentionProfile(w2, i2), mentionProfile(w1, i1))
    researcher(candidate(EntityKind.RESEARCHER, a, b, f), pa, pb)

  test("initials: missing middle names are compatible, different ones and families are not") {
    val n = ErNormalize.personName
    assert(Features.initialsCompatible(n("John Smith"), n("J. R. Smith")))
    assert(Features.initialsCompatible(n("J. R. R. Smith"), n("John Ronald Smith")))
    assert(!Features.initialsCompatible(n("J. R. Smith"), n("J. K. Smith")))
    assert(!Features.initialsCompatible(n("John Smith"), n("Paul Smith")))
    assert(!Features.initialsCompatible(n("John Smith"), n("John Smyth")))
  }

  test("name comparison reads two-token names in both orders") {
    val n = ErNormalize.personName
    val c = Features.compareNames(Vector(n("Wang Wei")), Vector(n("Wei Wang"))).get
    assertEquals((c.similarity, c.sameFull, c.initials, c.swapped), (1.0, true, true, true))
    assertEquals(Features.compareNames(Vector.empty, Vector(n("A B"))), None)
  }

  test("researcher features from two mention profiles, with hand-derived values") {
    val f = pair(W1, 1, W2, 1) // Bob Jones on two copies of one paper
    assertEquals(f(Features.NameSimilarity), 1.0)
    assertEquals(f(Features.SameFullName), 1.0)
    assertEquals(f(Features.InitialsCompatible), 1.0)
    assertEquals(f(Features.CoauthorOverlap), 1.0) // {smith_a} on both sides
    assertEquals(f(Features.TopicOverlap), 1.0)
    assertEquals(f(Features.YearGap), 0.0)
    assertEquals(f(Features.AuthorPositionAgree), 1.0) // last author of two on both
    assertEquals(f(Features.SharedWork), 0.0)
    assertEquals(f(Features.SourceReliability), 0.95) // DBLP and Crossref
    assertEquals(f(Features.CrossSource), 1.0)
    assertEquals(f(Features.CitationKnown), 0.0) // work-v1 has no references
    assertEquals(f.agreement(IdScheme.ORCID), Agreement.Absent)
  }

  test("upstream cluster evidence is combined: overlaps by max, gaps by min, flags by OR") {
    val f =
      pair(W5, 1, W6, 1, Map("coauthor_overlap" -> 0.3, "year_gap" -> 0.0, "shared_work" -> 0.0))
    assertEquals(f(Features.CoauthorOverlap), 0.3) // profile value 0, upstream 0.3
    val g = pair(W5, 1, W6, 1, Map("dblp_homonym_distinct" -> 1.0, "orcid_conflict" -> 1.0))
    assertEquals(g(Features.DblpHomonymDistinct), 1.0)
    assertEquals(g.agreement(IdScheme.ORCID), Agreement(false, true))
  }

  test("ORCID disagreement and the leave-one-out view of an orcid: endpoint") {
    assertEquals(pair(W4, 0, W5, 0).agreement(IdScheme.ORCID), Agreement(false, true))
    val agg = aggregate(orcidId, Vector(W1 -> 0, W2 -> 0))
    val c = candidate(EntityKind.RESEARCHER, mentionId(W1, 0), orcidId, Map("exact_orcid" -> 1.0))
    val f = researcher(c, mentionProfile(W1, 0), agg)
    assertEquals(f.agreement(IdScheme.ORCID), Agreement(true, false))
    assertEquals(
      f(Features.NamesKnown),
      1.0
    ) // the other Alice Smith mention, not the mention itself
    assertEquals(f(Features.SharedWork), 0.0)
    // With only the mention itself on the aggregate, nothing is left to compare names against.
    val alone = researcher(c, mentionProfile(W1, 0), aggregate(orcidId, Vector(W1 -> 0)))
    assertEquals(alone(Features.NamesKnown), 0.0)
  }

  test("DBLP homonym suffixes that differ are found from the profiles") {
    val a =
      work("dblp:conf/h/1", SourceType.DBLP, "T one", Some(2020), Vector(Author("Wei Wang 0001")))
    val b =
      work("dblp:conf/h/2", SourceType.DBLP, "T two", Some(2020), Vector(Author("Wei Wang 0002")))
    assertEquals(pair(a, 0, b, 0)(Features.DblpHomonymDistinct), 1.0)
    val c =
      work("dblp:conf/h/3", SourceType.DBLP, "T three", Some(2020), Vector(Author("Wei Wang 0001")))
    assertEquals(pair(a, 0, c, 0)(Features.DblpHomonymDistinct), 0.0)
  }

  test("work features: title, author sequence, year, venue and identifiers") {
    val c = merged(candidate(EntityKind.WORK, W1.workId, W3.workId))
    val f = Features.work(c, workProfile(W1), workProfile(W3), policyConfig)
    assertEquals(f(Features.TitleSimilarity), 1.0)
    assertEquals(f(Features.SameNormalizedTitle), 1.0)
    assertEquals(f(Features.TitleTokens), 5.0)
    assertEquals(f(Features.AuthorSequence), 1.0) // smith_a, jones_b in order on both
    assertEquals(f(Features.YearDistance), 0.0)
    assertEquals(f(Features.SameSource), 0.0)
    assertEquals(f(Features.SourceReliability), 0.85) // OpenAlex
    val d = Features.work(
      merged(candidate(EntityKind.WORK, W2.workId, W4.workId)),
      workProfile(W2),
      workProfile(W4),
      policyConfig
    )
    assertEquals(d.agreement(IdScheme.DOI), Agreement(false, true))
    assertEquals(d(Features.YearDistance), 1.0)
  }

  test("unknown years and missing profiles are recorded as unknown, never as agreement") {
    val a =
      work("dblp:conf/u/1", SourceType.DBLP, "Some Title Here Now", None, Vector(Author("A B")))
    val b = work(
      "dblp:conf/u/2",
      SourceType.DBLP,
      "Some Title Here Now",
      Some(2020),
      Vector(Author("A B"))
    )
    val c = merged(candidate(EntityKind.WORK, a.workId, b.workId))
    assertEquals(
      Features.work(c, workProfile(a), workProfile(b), policyConfig)(Features.YearDistance),
      Features.UnknownYears
    )
    val missing = Features.work(
      c,
      EndpointProfile.missing(a.workId, EntityKind.WORK),
      workProfile(b),
      policyConfig
    )
    assertEquals(missing(Features.ProfilesFound), 0.0)
    assertEquals(missing(Features.AuthorsKnown), 0.0)
    assertEquals(missing(Features.SourceReliability), 0.9) // SCHOLARCHAIN for the missing side
  }

  test("every feature value is finite") {
    val all = Vector(pair(W1, 1, W2, 1), pair(W4, 0, W5, 0), pair(W5, 1, W6, 1))
    assert(all.forall(_.values.values.forall(v => !v.isNaN && !v.isInfinite)))
  }
