package scholarchain.er

import scholarchain.core.*

class MatchPolicySuite extends munit.FunSuite:
  import ErFixtures.*

  private val p = ErFixtures.policy
  private val t = p.thresholds
  private val sameName = Seq(
    Features.NameSimilarity -> 1.0,
    Features.SameFullName -> 1.0,
    Features.InitialsCompatible -> 1.0,
    Features.NamesKnown -> 1.0,
    Features.SourceReliability -> 0.95,
    Features.YearGap -> Features.UnknownYears
  )
  private def r(ids: Map[IdScheme, Agreement], extra: (String, Double)*) =
    p.researcher(featureSet(ids, (sameName ++ extra)*))
  private def r(extra: (String, Double)*) = p.researcher(featureSet((sameName ++ extra)*))
  private val orcidEqual = Map(IdScheme.ORCID -> Agreement(true, false))
  private val orcidDiffers = Map(IdScheme.ORCID -> Agreement(false, true))

  test("er-v1 loads, its thresholds are pinned and equal the shipped configuration") {
    assertEquals(p.version, "er-v1")
    assertEquals(t, Thresholds(0.9, 0.35, 0.7, 0.9))
    val cfg = ConfigLoader.load(Map.empty).fold(e => fail(e), identity)
    assertEquals(MatchPolicy.load(cfg.entityResolution).map(_.thresholds), Right(t))
  }

  test("a configuration that retunes er-v1 or names another policy is refused") {
    val er = ConfigLoader.load(Map.empty).fold(e => fail(e), identity).entityResolution
    assert(
      PolicyConfig
        .thresholdsFor(policyConfig, er.copy(acceptThreshold = 0.85))
        .left
        .exists(_.contains("new policy version"))
    )
    assert(PolicyConfig.thresholdsFor(policyConfig, er.copy(policyVersion = "er-v2")).isLeft)
    val env = Map("SCHOLARCHAIN__ENTITYRESOLUTION__REJECTTHRESHOLD" -> "0.3")
    val overridden = ConfigLoader.load(env).fold(e => fail(e), identity)
    assert(MatchPolicy.load(overridden.entityResolution).isLeft)
  }

  test("the policy resource is strict: an unknown or missing key is an error") {
    val text = PolicyConfig.resourceText().fold(e => fail(e), identity)
    assert(
      PolicyConfig
        .parse(text.replaceFirst("\"conflictConfidence\"", "\"conflictConfidenze\""))
        .isLeft
    )
    assert(PolicyConfig.parse(text.replaceFirst("\"bias\": -4.3,", "")).isLeft)
    assert(PolicyConfig.parse(text).isRight)
  }

  test("inconsistent thresholds are rejected") {
    assert(Thresholds(0.8, 0.9, 0.7, 0.75).errors.nonEmpty)
    assert(Thresholds(0.9, 0.35, 0.7, 0.95).errors.nonEmpty)
    intercept[IllegalArgumentException](
      new MatchPolicy(policyConfig, Thresholds(0.5, 0.6, 0.55, 0.58))
    )
  }

  test("a shared full name alone stays UNRESOLVED; coauthor evidence accepts it") {
    val alone = r()
    assertEquals((alone.outcome, alone.path), (MatchOutcome.UNRESOLVED, MatchPath.PROBABILISTIC))
    // z = -4.3 + 3 + 1 - 1.0 * 0.05 = -0.35
    assertEqualsDouble(alone.score, 1.0 / (1.0 + math.exp(0.35)), 1e-9)
    assertEquals(alone.needsAdjudication, false)
    assertEquals(r(Features.CoauthorOverlap -> 0.5).outcome, MatchOutcome.ACCEPTED)
  }

  test("the probabilistic bands: reject, unresolved, LLM band, accept") {
    val band = r(Features.CoauthorOverlap -> 0.3, Features.AuthorPositionAgree -> 1.0)
    assert(t.inBand(band.score), s"score ${band.score}")
    assertEquals((band.outcome, band.needsAdjudication), (MatchOutcome.UNRESOLVED, true))
    assertEquals(band.reasonCodes, Vector("SCORE_IN_LLM_BAND"))
    val incompatible =
      p.researcher(featureSet(Features.NameSimilarity -> 0.7, Features.InitialsCompatible -> 0.0))
    assertEquals(incompatible.outcome, MatchOutcome.REJECTED)
    assertEquals(incompatible.rulesFired.head, "R-R9")
  }

  test("an unknown year is no evidence; a known large gap counts against the match") {
    val unknown = r(Features.CoauthorOverlap -> 0.5)
    val far = r(Features.CoauthorOverlap -> 0.5, Features.YearGap -> 40.0)
    assert(unknown.score > far.score)
    assertEquals(unknown.score, r(Features.CoauthorOverlap -> 0.5, Features.YearGap -> 0.0).score)
  }

  test(
    "authoritative ID disagreement never merges: CONFLICT, or REJECTED when evidence disagrees too"
  ) {
    val c = r(orcidDiffers, Features.CoauthorOverlap -> 1.0)
    assertEquals(
      (c.outcome, c.path, c.reasonCodes),
      (MatchOutcome.CONFLICT, MatchPath.EXACT_ID, Vector("ORCID_DISAGREES"))
    )
    val weak = p.researcher(
      featureSet(orcidDiffers, Features.NameSimilarity -> 0.6, Features.InitialsCompatible -> 0.0)
    )
    assertEquals(weak.outcome, MatchOutcome.REJECTED)
    assertEquals(weak.reasonCodes, Vector("ORCID_DIFFERS_EVIDENCE_DIFFERS"))
    val both = r(Map(IdScheme.ORCID -> Agreement(true, true)))
    assertEquals((both.outcome, both.rulesFired), (MatchOutcome.CONFLICT, Vector("R-R0")))
  }

  test("same work rejects, and conflicts when an ID says same; DBLP homonyms likewise") {
    assertEquals(r(Features.SharedWork -> 1.0).reasonCodes, Vector("COAUTHORS_ON_SAME_WORK"))
    assertEquals(r(orcidEqual, Features.SharedWork -> 1.0).outcome, MatchOutcome.CONFLICT)
    val h = r(Features.DblpHomonymDistinct -> 1.0, Features.CoauthorOverlap -> 1.0)
    assertEquals(
      (h.outcome, h.path),
      (MatchOutcome.REJECTED, MatchPath.DETERMINISTIC_BIBLIOGRAPHIC)
    )
    assertEquals(r(orcidEqual, Features.DblpHomonymDistinct -> 1.0).outcome, MatchOutcome.CONFLICT)
  }

  test("exact IDs accept only when names corroborate; a declared-only ORCID is accepted") {
    val ok = r(orcidEqual)
    assertEquals(
      (ok.outcome, ok.path, ok.reasonCodes),
      (MatchOutcome.ACCEPTED, MatchPath.EXACT_ID, Vector("ORCID_EQUAL"))
    )
    val names = p.researcher(
      featureSet(
        orcidEqual,
        Features.NameSimilarity -> 0.5,
        Features.NamesKnown -> 1.0,
        Features.InitialsCompatible -> 0.0
      )
    )
    assertEquals(
      (names.outcome, names.reasonCodes),
      (MatchOutcome.UNRESOLVED, Vector("ORCID_EQUAL_NAMES_DISAGREE"))
    )
    val declared = p.researcher(featureSet(orcidEqual, Features.NamesKnown -> 0.0))
    assertEquals(declared.reasonCodes, Vector("ORCID_DECLARED_ONLY"))
    val openalex = r(Map(IdScheme.OPENALEX -> Agreement(true, false)))
    assertEquals(openalex.reasonCodes, Vector("OPENALEX_EQUAL"))
    // OpenAlex is not authoritative: its disagreement only lowers the score.
    val oaDiffers =
      r(Map(IdScheme.OPENALEX -> Agreement(false, true)), Features.CoauthorOverlap -> 0.5)
    assertEquals(oaDiffers.path, MatchPath.PROBABILISTIC)
    assert(oaDiffers.score < r(Features.CoauthorOverlap -> 0.5).score)
  }

  test("a source cluster is accepted deterministically") {
    val s = r(Features.SameSourceCluster -> 1.0)
    assertEquals(
      (s.outcome, s.path),
      (MatchOutcome.ACCEPTED, MatchPath.DETERMINISTIC_BIBLIOGRAPHIC)
    )
  }

  private val goodWork = Seq(
    Features.TitleSimilarity -> 1.0,
    Features.SameNormalizedTitle -> 1.0,
    Features.TitleTokens -> 5.0,
    Features.AuthorsKnown -> 1.0,
    Features.AuthorSequence -> 1.0,
    Features.AuthorOverlap -> 1.0,
    Features.YearDistance -> 0.0,
    Features.SourceReliability -> 0.95
  )
  private def w(ids: Map[IdScheme, Agreement], extra: (String, Double)*) =
    p.work(featureSet(ids, (goodWork ++ extra)*))

  test(
    "works: DOI equality accepts; with unrelated titles or from one source it stays UNRESOLVED"
  ) {
    val doi = Map(IdScheme.DOI -> Agreement(true, false))
    assertEquals(w(doi).reasonCodes, Vector("DOI_EQUAL"))
    assertEquals(
      w(doi, Features.TitleSimilarity -> 0.1).reasonCodes,
      Vector("DOI_EQUAL_TITLES_DISAGREE")
    )
    assertEquals(w(doi, Features.SameSource -> 1.0).reasonCodes, Vector("SAME_SOURCE_SHARES_DOI"))
    assertEquals(
      w(Map(IdScheme.REPO -> Agreement(true, false)), Features.TitleSimilarity -> 0.5).outcome,
      MatchOutcome.UNRESOLVED
    )
  }

  test("works: DOI disagreement is CONFLICT unless the bibliographic evidence disagrees too") {
    val differs = Map(IdScheme.DOI -> Agreement(false, true))
    assertEquals(w(differs).outcome, MatchOutcome.CONFLICT)
    val unrelated = p.work(
      featureSet(differs, Features.TitleSimilarity -> 0.1, Features.SourceReliability -> 0.95)
    )
    assertEquals((unrelated.outcome, unrelated.path), (MatchOutcome.REJECTED, MatchPath.EXACT_ID))
    assertEquals(
      w(
        Map(IdScheme.DOI -> Agreement(true, false), IdScheme.ARXIV -> Agreement(false, true))
      ).rulesFired,
      Vector("R-W1")
    )
  }

  test("works: exact normalized title, year and author sequence accept deterministically") {
    val b = w(Map.empty)
    assertEquals(
      (b.outcome, b.path),
      (MatchOutcome.ACCEPTED, MatchPath.DETERMINISTIC_BIBLIOGRAPHIC)
    )
    // Short generic titles, a two-year gap or one source fall through to the score.
    assertEquals(w(Map.empty, Features.TitleTokens -> 1.0).path, MatchPath.PROBABILISTIC)
    assertEquals(w(Map.empty, Features.YearDistance -> 2.0).path, MatchPath.PROBABILISTIC)
    assertEquals(w(Map.empty, Features.SameSource -> 1.0).path, MatchPath.PROBABILISTIC)
    // The same title without authors is only a band case.
    val titleOnly =
      p.work(featureSet(Features.TitleSimilarity -> 1.0, Features.SourceReliability -> 0.95))
    assert(t.inBand(titleOnly.score), s"score ${titleOnly.score}")
  }

  test("confidence is P(same entity) on every path") {
    assert(r(Features.SharedWork -> 1.0).confidence < 0.1)
    assert(r(orcidEqual).confidence > 0.9)
    val rej =
      p.researcher(featureSet(Features.NameSimilarity -> 0.7, Features.InitialsCompatible -> 0.0))
    assertEquals(rej.confidence, rej.score)
  }
