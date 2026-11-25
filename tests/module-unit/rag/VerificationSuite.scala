package scholarchain.rag

import scholarchain.core.*
import scholarchain.core.VerificationStatus.*
import RagWorld.*

/** Stage 9: the claim verifier, on bundles of the hand-built run. Every expected value is read from
  * the RagWorld scaladoc and data by hand, not from the code under test.
  */
class VerificationSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private lazy val pipe = w.pipeline()
  private def ev(q: String, c: Constraints = Constraints()) =
    pipe
      .run(s"qry-${Hashing.short(q + c.toString, 12)}", AnalysisRequest(q, c))
      .fold(e => fail(e.describe), identity)
  private def metricsOf(r: EvidenceStageResult, id: String) =
    r.bundle.deterministic
      .find(e =>
        e.values.get("kind").contains("researcher-metrics") && e.researcherIds == Vector(id) &&
          e.values("window") == Recent.label
      )
      .getOrElse(fail(s"no metrics evidence for $id"))
  private def check(r: EvidenceStageResult, text: String, ids: String*) =
    ClaimVerifier.verifyText("claim-1", text, ids.toVector, r.bundle)

  private lazy val ranking = ev(
    "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?"
  )
  private lazy val path = ev(
    "What is the shortest path between Alice Arden and Dan Dorsey since 2015?"
  )

  test(
    "every deterministic claim verifies on ranking, team, bridge, path, edge, component and temporal questions"
  ) {
    val questions = Vector(
      "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?",
      "Who participated in the largest author teams since 2015?",
      "What is the shortest path between Alice Arden and Dan Dorsey since 2015?",
      "What is the shortest path between Alice Arden and Erin Ellis since 2015?",
      "Has Alice Arden collaborated with Bob Brandt since 2015?",
      "Has Alice Arden collaborated with Dan Dorsey since 2015?",
      """How has the collaboration of "Alice Arden" changed over time?""",
      """Is "Alice Arden" in the same component as "Dan Dorsey" since 2015?"""
    )
    questions.foreach { q =>
      val r = ev(q)
      val out = DeterministicSynthesizer.draft(r)
      assert(out.claims.nonEmpty, q)
      assertEquals(SynthesisRules.check(out), Right(out), q)
      out.claims.foreach { c =>
        val v = ClaimVerifier.verify(c, r.bundle)
        assertEquals(v.check.status, VERIFIED, s"$q\n${c.text}\n${v.check.reasons}")
        assert(v.check.supported >= 1 && v.unsupportedSpans.isEmpty, c.text)
      }
    }
  }

  test("an exact metric claim verifies; a wrong count, window or bridge score conflicts") {
    val a = metricsOf(ranking, Alice).evidenceId
    assertEquals(
      check(ranking, "Alice Arden has 41 distinct coauthors in window 2015-2026.", a).check.status,
      VERIFIED
    )
    val wrongCount = check(ranking, "Alice Arden has 42 distinct coauthors in window 2015-2026.", a)
    assertEquals(wrongCount.check.status, CONFLICTING)
    assert(
      wrongCount.check.reasons.exists(_.contains("claimed 42")),
      wrongCount.check.reasons.toString
    )
    assertEquals(
      check(ranking, "Alice Arden has 41 distinct coauthors in window 2010-2014.", a).check.status,
      CONFLICTING
    )
    assertEquals(
      check(
        ranking,
        "Bob Brandt has bridge score 0.12.",
        metricsOf(ranking, Bob).evidenceId
      ).check.status,
      CONFLICTING
    )
    assertEquals(
      check(
        ranking,
        "Bob Brandt has bridge score 0.41.",
        metricsOf(ranking, Bob).evidenceId
      ).check.status,
      VERIFIED
    )
  }

  test("a claim that cites nothing in the bundle, or a value no cited item holds, is unsupported") {
    val ghost = "ev:metric_record:0123456789abcdef"
    val none = check(ranking, "Alice Arden has 41 distinct coauthors.", ghost)
    assertEquals(none.check.status, UNSUPPORTED)
    assertEquals(none.check.missingEvidenceIds, Vector(ghost))
    // Community size is in no store the runtime reads, so no cited item can hold it.
    val community =
      ranking.bundle.deterministic.find(_.values.get("kind").contains("community-membership")).get
    val size = check(ranking, "Community cm:1111111111111111 has 10 members.", community.evidenceId)
    assert(
      size.check.reasons.exists(_.contains("no cited evidence holds communitySize")),
      size.check.reasons.toString
    )
    assertNotEquals(size.check.status, VERIFIED)
    assertEquals(
      size.weakened("Community cm:1111111111111111 has 10 members."),
      "Community cm:1111111111111111 has [unverified] members."
    )
    // One cited item exists and supports the value, the other does not exist: partially supported.
    val partial = check(
      ranking,
      "Alice Arden has 41 distinct coauthors.",
      metricsOf(ranking, Alice).evidenceId,
      ghost
    )
    assertEquals(partial.check.status, PARTIALLY_SUPPORTED)
    assertEquals(partial.existingEvidenceIds, Vector(metricsOf(ranking, Alice).evidenceId))
  }

  test(
    "semantic context never supports a graph value; the injected profile number conflicts with the metric"
  ) {
    val r = ev(
      """What are the collaboration metrics of "Wei Wang" since 2015?""",
      Constraints(researcherIds = Vector(Wei1))
    )
    val profile = r.bundle.semantic.find(_.text.exists(_.contains("IGNORE ALL PREVIOUS"))).get
    val semOnly = check(r, "Wei Wang has 999 distinct coauthors.", profile.evidenceId)
    assertEquals(semOnly.check.status, UNSUPPORTED)
    assert(semOnly.check.reasons.exists(_.contains("semantic context never supports")))
    val withMetric = check(
      r,
      "Wei Wang has 999 distinct coauthors.",
      profile.evidenceId,
      metricsOf(r, Wei1).evidenceId
    )
    assertEquals(withMetric.check.status, CONFLICTING)
    val descriptive =
      check(r, s"A profile describes $Wei1 and Qubit routing work.", profile.evidenceId)
    assertEquals(descriptive.check.status, PARTIALLY_SUPPORTED)
    assert(descriptive.check.reasons.exists(_.contains("descriptive context only")))
  }

  test("path hops, path and edge statements are checked against the cited found flags") {
    val p = path.bundle.deterministic.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
    assertEquals(
      check(
        path,
        "A shortest coauthorship path has 3 hops: Alice Arden -> Bob Brandt -> Carol Chen -> Dan Dorsey.",
        p.evidenceId
      ).check.status,
      VERIFIED
    )
    val skipped = check(
      path,
      "A shortest coauthorship path has 3 hops: Alice Arden -> Carol Chen -> Dan Dorsey.",
      p.evidenceId
    )
    assertEquals(skipped.check.status, CONFLICTING)
    assert(
      skipped.check.reasons.exists(_.contains("Alice Arden -> Carol Chen")),
      skipped.check.reasons.toString
    )
    assertEquals(check(path, "The path has 2 hops.", p.evidenceId).check.status, CONFLICTING)
    assertEquals(
      check(
        path,
        "No coauthorship path connects Alice Arden and Dan Dorsey.",
        p.evidenceId
      ).check.status,
      CONFLICTING
    )
    val apart = ev("Has Alice Arden collaborated with Dan Dorsey since 2015?")
    val noEdge = apart.bundle.deterministic
      .find(e => e.evidenceType == EvidenceType.COAUTHOR_EDGE && e.values("found") == "false")
      .get
    assertEquals(
      check(
        apart,
        "Alice Arden and Dan Dorsey are direct coauthors.",
        noEdge.evidenceId
      ).check.status,
      CONFLICTING
    )
    assertEquals(
      check(
        apart,
        "Alice Arden and Dan Dorsey have no direct coauthor edge in window 2015-2026.",
        noEdge.evidenceId
      ).check.status,
      VERIFIED
    )
  }

  test("ranking order, quoted titles, identifiers and unknown names are checked") {
    val rk = ranking.bundle.deterministic
      .find(e => e.values.get("metric").contains("DISTINCT_COAUTHOR_COUNT"))
      .get
    assertEquals(
      check(
        ranking,
        "By distinct coauthors: Carol Chen (52), Alice Arden (41), Bob Brandt (30).",
        rk.evidenceId
      ).check.status,
      VERIFIED
    )
    val swapped =
      check(ranking, "By distinct coauthors: Alice Arden (41), Carol Chen (52).", rk.evidenceId)
    assertEquals(swapped.check.status, CONFLICTING)
    val team = ev("Who participated in the largest author teams since 2015?")
    val pub = team.bundle.deterministic.find(_.workId.contains(SurfaceWork)).get
    assertEquals(
      check(
        team,
        "The paper \"Surface code decoders\" appeared in 2021.",
        pub.evidenceId
      ).check.status,
      VERIFIED
    )
    val fake =
      check(team, "The paper \"Quantum gravity for everyone\" appeared in 2021.", pub.evidenceId)
    assertEquals(fake.check.status, PARTIALLY_SUPPORTED)
    assertEquals(
      fake.weakened("The paper \"Quantum gravity for everyone\" appeared in 2021."),
      "The paper [unverified] appeared in 2021."
    )
    val badId = check(
      ranking,
      s"Alice Arden ($Bob) has 41 distinct coauthors.",
      metricsOf(ranking, Alice).evidenceId
    )
    assertEquals(badId.check.status, PARTIALLY_SUPPORTED)
    // repair 3: the sentence names no known researcher, so Alice's record no longer backs the 41
    // (was PARTIALLY_SUPPORTED with the 41 kept); nothing is supported, so the claim is removed
    val invented = "John Smith has 41 distinct coauthors."
    val v = check(ranking, invented, metricsOf(ranking, Alice).evidenceId)
    assertEquals(v.check.status, UNSUPPORTED, v.check.reasons.toString)
    assertEquals(v.weakened(invented), "[unverified] has [unverified] distinct coauthors.")
  }

  test("ambiguous-name candidate claims verify against identity evidence only") {
    val r = ev("Who are the coauthors of Wei Wang since 2015?")
    val out = DeterministicSynthesizer.draft(r)
    assert(out.abstain)
    assertEquals(out.claims.size, 2)
    assert(out.claims.exists(_.text.contains(Wei1)) && out.claims.exists(_.text.contains(Wei2)))
    out.claims.foreach(c =>
      assertEquals(ClaimVerifier.verify(c, r.bundle).check.status, VERIFIED, c.text)
    )
    val i = r.bundle.identity.head
    assertEquals(
      check(
        r,
        s"'Wei Wang' is ambiguous: candidate Wei Wang has identity confidence 0.5.",
        i.evidenceId
      ).check.status,
      CONFLICTING
    )
  }

  test("repair M5: a number is checked against the named researcher's own records only") {
    val (a, b) = (metricsOf(ranking, Alice).evidenceId, metricsOf(ranking, Bob).evidenceId)
    // fixture: Alice 41 distinct coauthors, 12 publications; Bob 30 and 9
    val swapped = check(ranking, "Alice Arden has 30 distinct coauthors in window 2015-2026.", a, b)
    assertEquals(swapped.check.status, CONFLICTING, swapped.check.reasons.toString)
    assert(
      swapped.check.reasons.exists(_.contains("of Alice Arden")),
      swapped.check.reasons.toString
    )
    // repair 3: a sentence that names two researchers gets no per-researcher support, so both
    // the swapped (was CONFLICTING) and the correct (was VERIFIED) two-name sentence are weakened
    Vector(
      "Bob Brandt has 41 distinct coauthors, and Alice Arden has 30 distinct coauthors.",
      "Alice Arden has 41 distinct coauthors, and Bob Brandt has 30 distinct coauthors."
    ).foreach { t =>
      val v = check(ranking, t, a, b)
      assertEquals(v.check.status, PARTIALLY_SUPPORTED, s"$t\n${v.check.reasons}")
      assertEquals(v.unsupportedSpans.size, 2, t)
    }
    // an unkeyed number: Bob's 9 publications attributed to Alice is weakened, not verified
    val papers = check(ranking, "Alice Arden wrote 9 papers in window 2015-2026.", a, b)
    assertEquals(papers.check.status, PARTIALLY_SUPPORTED)
    assertEquals(
      papers.weakened("Alice Arden wrote 9 papers in window 2015-2026."),
      "Alice Arden wrote [unverified] papers in window 2015-2026."
    )
    // citing only another researcher's record never verifies the value for the named one
    val other = check(ranking, "Alice Arden has 30 distinct coauthors.", b)
    assertNotEquals(other.check.status, VERIFIED)
    assert(other.unsupportedSpans.nonEmpty)
    // edge, path and ranking values keep supporting every named researcher
    val p = path.bundle.deterministic.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
    assertEquals(
      check(path, "Alice Arden reaches Dan Dorsey in 3 hops.", p.evidenceId).check.status,
      VERIFIED
    )
  }

  test("repair D5: a number whose subject is ambiguous in its clause is attributed to nobody") {
    val (a, b) = (metricsOf(ranking, Alice).evidenceId, metricsOf(ranking, Bob).evidenceId)
    val c = metricsOf(ranking, Carol).evidenceId
    // fixture: Alice 41 distinct coauthors, 12 publications; Bob 30 and 9; Carol 52
    def weakenedBy(text: String, expected: String, ids: String*) =
      val v = check(ranking, text, ids*)
      assertEquals(v.check.status, PARTIALLY_SUPPORTED, s"$text\n${v.check.reasons}")
      assert(v.check.reasons.exists(_.contains("ambiguous")), v.check.reasons.toString)
      assertEquals(v.weakened(text), expected)
    // the subject first, another researcher named between it and the number: Bob's 30 for Alice
    weakenedBy(
      "Alice Arden, compared with Bob Brandt, has 30 distinct coauthors in window 2015-2026.",
      "Alice Arden, compared with Bob Brandt, has [unverified] distinct coauthors in window 2015-2026.",
      a,
      b
    )
    // the number before two names: no researcher's record is credited with it
    weakenedBy(
      "With 30 distinct coauthors in window 2015-2026, Alice Arden leads Bob Brandt.",
      "With [unverified] distinct coauthors in window 2015-2026, Alice Arden leads Bob Brandt.",
      a,
      b
    )
    // conservative: the same forms with the subject's own value are weakened too, never verified
    Vector(
      "Alice Arden, compared with Bob Brandt, has 41 distinct coauthors in window 2015-2026.",
      "With 41 distinct coauthors in window 2015-2026, Alice Arden leads Bob Brandt.",
      // a conjunction inside the parenthetical does not start a clause (no number before it)
      "Alice Arden, compared with Bob Brandt and Carol Chen, has 52 distinct coauthors.",
      "Alice Arden, and not Bob Brandt, has 30 distinct coauthors.",
      // "and" followed by a researcher whose number comes after a comma: no new clause
      "Alice Arden has 12 publications and Bob Brandt as a coauthor, with 30 distinct coauthors.",
      // a later clause that names nobody, in a claim that names two researchers
      "Alice Arden and Bob Brandt are listed. Their count is 30 distinct coauthors.",
      "Alice Arden, compared with Bob Brandt: 30 distinct coauthors."
    ).foreach { t =>
      val v = check(ranking, t, a, b, c)
      assertNotEquals(v.check.status, VERIFIED, s"$t\n${v.check.reasons}")
      assert(v.unsupportedSpans.nonEmpty, t)
    }
    // one researcher per sentence still verifies, whatever the position of the number
    Vector(
      "Alice Arden has 41 distinct coauthors. With 30 distinct coauthors, Bob Brandt follows.",
      "With 41 distinct coauthors in window 2015-2026, Alice Arden is listed."
    ).foreach { t =>
      val v = check(ranking, t, a, b)
      assertEquals(v.check.status, VERIFIED, s"$t\n${v.check.reasons}")
    }
    // repair 3: these were VERIFIED under the clause rule (and the swapped ", while" form was
    // CONFLICTING); one sentence names two researchers, so each is weakened and labeled now
    Vector(
      "Alice Arden has 41 distinct coauthors, while Bob Brandt has 30 distinct coauthors.",
      "Alice Arden has 41 distinct coauthors and Bob Brandt has 30 distinct coauthors.",
      "Alice Arden has 41 distinct coauthors; Bob Brandt has 30 distinct coauthors.",
      "Alice Arden, a coauthor of Bob Brandt, is Alice Arden with 41 distinct coauthors.",
      "Alice Arden has 41 distinct coauthors, more than Bob Brandt.",
      "Alice Arden has 30 distinct coauthors, while Bob Brandt has 41 distinct coauthors."
    ).foreach { t =>
      val v = check(ranking, t, a, b)
      assertEquals(v.check.status, PARTIALLY_SUPPORTED, s"$t\n${v.check.reasons}")
      assert(v.check.reasons.exists(_.contains("names 2 researchers")), v.check.reasons.toString)
    }
    // edge, path and ranking items keep supporting an ambiguous number
    val p = path.bundle.deterministic.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
    assertEquals(
      check(path, "With 3 hops, Alice Arden reaches Dan Dorsey.", p.evidenceId).check.status,
      VERIFIED
    )
    val rk = ranking.bundle.deterministic
      .find(e => e.values.get("metric").contains("DISTINCT_COAUTHOR_COUNT"))
      .get
    assertEquals(
      check(
        ranking,
        "Carol Chen, ahead of Alice Arden and Bob Brandt, is ranked with 52.",
        rk.evidenceId,
        a,
        b,
        c
      ).check.status,
      VERIFIED
    )
  }

  test(
    "repair 3: a per-researcher number verifies only in a sentence naming one researcher, no pronoun"
  ) {
    val (a, b) = (metricsOf(ranking, Alice).evidenceId, metricsOf(ranking, Bob).evidenceId)
    val c = metricsOf(ranking, Carol).evidenceId
    // fixture: Alice 41 distinct coauthors, 12 publications; Bob 30 and 9; Carol 52
    def weakened(text: String, reason: String, ids: String*) =
      val v = check(ranking, text, ids*)
      assertEquals(v.check.status, PARTIALLY_SUPPORTED, s"$text\n${v.check.reasons}")
      assert(v.check.reasons.exists(_.contains("ambiguous")), v.check.reasons.toString)
      assert(v.check.reasons.exists(_.contains(reason)), s"$text\n${v.check.reasons}")
      assert(v.weakened(text).contains(s"${ClaimVerifier.Unverified} distinct coauthors"), text)
      assert(!v.weakened(text).matches(".*\\b(30|41) distinct.*"), v.weakened(text))
      v
    // review-report-03 R3-1, inputs 1 to 7 (Bob's 30 stated for Alice), citing all three records
    Vector(
      "Alice Arden (vs. Bob Brandt) has 30 distinct coauthors in window 2015-2026.",
      "Alice Arden, compared with Prof. Bob Brandt, has 30 distinct coauthors in window 2015-2026.",
      "Alice Arden, like Dr. Bob Brandt, has 30 distinct coauthors.",
      "Alice Arden leads; compared with Bob Brandt, she has 30 distinct coauthors.",
      "Alice Arden stands out: compared with Bob Brandt, she has 30 distinct coauthors.",
      "Alice Arden has 12 publications, while compared with Bob Brandt she has 30 distinct coauthors."
    ).foreach(t => weakened(t, "researchers", a, b, c))
    val four = weakened(
      "Alice Arden leads. Unlike Bob Brandt, she has 30 distinct coauthors in window 2015-2026.",
      "pronoun 'she'",
      a,
      b,
      c
    )
    assertEquals(
      four.weakened(
        "Alice Arden leads. Unlike Bob Brandt, she has 30 distinct coauthors in window 2015-2026."
      ),
      "Alice Arden leads. Unlike Bob Brandt, she has [unverified] distinct coauthors in window 2015-2026."
    )
    // input 7 also loses Alice's own 12: the sentence names two researchers
    val seven =
      "Alice Arden has 12 publications, while compared with Bob Brandt she has 30 distinct coauthors."
    assertEquals(
      check(ranking, seven, a, b, c).weakened(seven),
      "Alice Arden has [unverified] publications, while compared with Bob Brandt she has [unverified] distinct coauthors."
    )
    // more abbreviation periods: none of them ends the sentence, so both names stay in it
    Vector(
      "Alice Arden, compared with prof. Bob Brandt, has 30 distinct coauthors.",
      "Alice Arden, like e.g. Bob Brandt, has 30 distinct coauthors.",
      "Alice Arden, unlike J. Doe and Bob Brandt, has 30 distinct coauthors.",
      "Alice Arden, ranked 1. Bob Brandt, has 30 distinct coauthors."
    ).foreach(t => weakened(t, "researchers", a, b, c))
    // R3-2: Alice's 41 credited to Bob, who is the subject
    weakened(
      "Alice Arden collaborates with Bob Brandt, who, like Alice Arden, has 41 distinct coauthors.",
      "names 2 researchers (Alice Arden / Bob Brandt)",
      a,
      b,
      c
    )
    // R3-3: a true pronoun claim is weakened and labeled, never CONFLICTING
    weakened(
      "Alice Arden leads. Unlike Bob Brandt, she has 41 distinct coauthors in window 2015-2026.",
      "pronoun 'she'",
      a,
      b,
      c
    )
    // a pronoun weakens even a one-name sentence; a pronoun-only claim keeps nothing
    weakened("Alice Arden says she has 41 distinct coauthors.", "pronoun 'she'", a)
    val she = check(ranking, "She has 41 distinct coauthors.", a)
    assertEquals(she.check.status, UNSUPPORTED, she.check.reasons.toString)
    // real sentence ends split: one researcher per sentence verifies, and a wrong value conflicts
    Vector(
      "Alice Arden has 41 distinct coauthors in window 2015-2026. Bob Brandt has 30 distinct coauthors in window 2015-2026.",
      "Bob Brandt is listed second! Alice Arden has 41 distinct coauthors.",
      "Bob Brandt is listed (see the ranking). Alice Arden has 41 distinct coauthors."
    ).foreach { t =>
      val v = check(ranking, t, a, b, c)
      assertEquals(v.check.status, VERIFIED, s"$t\n${v.check.reasons}")
    }
    val wrong =
      check(ranking, "Bob Brandt is listed second. Alice Arden has 30 distinct coauthors.", a, b)
    assertEquals(wrong.check.status, CONFLICTING, wrong.check.reasons.toString)
    // a period inside brackets or after a name does not end the sentence
    weakened(
      "Bob Brandt is listed (second. Alice Arden) with 30 distinct coauthors.",
      "researchers",
      a,
      b
    )
    weakened(
      "Alice Arden leads Bob Brandt. Bob Brandt has 30 distinct coauthors.",
      "researchers",
      a,
      b
    )
    // a quoted query is not a mention only in the fixed identity lead; elsewhere it counts
    val wei = ev("Who are the coauthors of Wei Wang since 2015?")
    val ids = wei.bundle.identity.map(_.evidenceId)
    val quoted = check(wei, "'Wei Wang' has 2 source mentions, like Wei Wang 0002.", ids*)
    assertNotEquals(quoted.check.status, VERIFIED, quoted.check.reasons.toString)
    assert(
      quoted.check.reasons.exists(_.contains("names 2 researchers")),
      quoted.check.reasons.toString
    )
  }

  test("repair M3: 'communities connected' includes the researcher's own; 'other' is unverified") {
    val bob = metricsOf(ranking, Bob).evidenceId
    // fixture: Bob crossCommunityDegree 2, communitiesConnected 2 (own community included)
    assertEquals(
      check(ranking, "Bob Brandt is touching 2 communities, its own included.", bob).check.status,
      VERIFIED
    )
    assertEquals(
      check(ranking, "Bob Brandt connects 3 communities.", bob).check.status,
      CONFLICTING
    )
    val other = check(ranking, "Bob Brandt links 2 other communities.", bob)
    assertEquals(other.check.status, PARTIALLY_SUPPORTED, other.check.reasons.toString)
    assert(
      other.check.reasons.exists(_.contains("otherCommunityCount")),
      other.check.reasons.toString
    )
    assertEquals(
      other.weakened("Bob Brandt links 2 other communities."),
      "Bob Brandt links [unverified] other communities."
    )
  }

  test("the answer status follows the claim statuses") {
    def c(s: VerificationStatus) = ClaimCheck("claim-1", s, Vector.empty, 1, 1, Vector.empty)
    assertEquals(ClaimVerifier.overall(Vector(c(VERIFIED), c(VERIFIED)), 2), VERIFIED)
    assertEquals(
      ClaimVerifier.overall(Vector(c(VERIFIED), c(PARTIALLY_SUPPORTED)), 2),
      PARTIALLY_SUPPORTED
    )
    assertEquals(ClaimVerifier.overall(Vector(c(VERIFIED), c(UNSUPPORTED)), 1), PARTIALLY_SUPPORTED)
    assertEquals(ClaimVerifier.overall(Vector(c(VERIFIED), c(CONFLICTING)), 1), CONFLICTING)
    assertEquals(ClaimVerifier.overall(Vector(c(UNSUPPORTED)), 0), UNSUPPORTED)
    assertEquals(ClaimVerifier.overall(Vector.empty, 0), UNSUPPORTED)
  }
