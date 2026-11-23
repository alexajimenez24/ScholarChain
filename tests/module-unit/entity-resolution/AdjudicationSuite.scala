package scholarchain.er

import scholarchain.core.*

class AdjudicationSuite extends munit.FunSuite:
  import ErFixtures.*

  private val template = PromptTemplate.load().fold(e => throw new AssertionError(e), identity)
  private val req = AdjudicationRequest(
    "mc:researcher:00000000000000000001",
    EntityKind.RESEARCHER,
    "m:aaaaaaaaaaaaaaaaaaaa",
    "m:bbbbbbbbbbbbbbbbbbbb",
    "er-v1",
    0.8,
    Map(Features.CoauthorOverlap -> 0.3, Features.InitialsCompatible -> 1.0),
    Map("left.names" -> "Dan Brown", "right.names" -> "Dan Brown")
  )
  private def reply(
      decision: String = "ACCEPT",
      src: String = req.sourceId,
      tgt: String = req.targetId,
      conf: String = "0.9",
      codes: String = "\"SHARED_COAUTHORS\"",
      cited: String = "\"coauthor_overlap\"",
      extra: String = ""
  ) =
    s"""{"decision":"$decision","sourceId":"$src","targetId":"$tgt","confidence":$conf,"reasonCodes":[$codes],"citedFields":[$cited]$extra}"""

  /** Returns canned replies in order and counts calls. */
  final class Scripted(replies: String*) extends AdjudicatorPort:
    private val it = replies.iterator
    var calls = 0
    var lastPrompt = ""
    val mode: AdjudicatorMode = AdjudicatorMode.FAKE
    val promptVersion: String = template.version
    val modelVersion = "scripted-model-1"
    def complete(prompt: String, r: AdjudicationRequest): Either[String, String] =
      calls += 1; lastPrompt = prompt
      if it.hasNext then Right(it.next()) else Left("no more replies")

  test("a valid reply passes; the three decisions are the only ones allowed") {
    Vector("ACCEPT", "REJECT", "ABSTAIN").foreach(d =>
      assert(Adjudication.validate(reply(d), req).isRight, d)
    )
    assert(Adjudication.validate(reply("MERGE"), req).isLeft)
    assert(Adjudication.validate(reply("accept"), req).isLeft)
  }

  test("strict JSON: prose, code fences, extra keys, missing keys and wrong types are refused") {
    assert(Adjudication.validate("They are the same person. " + reply(), req).isLeft)
    assert(Adjudication.validate("```json\n" + reply() + "\n```", req).isLeft)
    assert(Adjudication.validate(reply(extra = ""","note":"x""""), req).isLeft)
    assert(Adjudication.validate("""{"decision":"ACCEPT"}""", req).isLeft)
    assert(Adjudication.validate(reply(conf = "\"high\""), req).isLeft)
    assert(Adjudication.validate(reply(conf = "null"), req).isLeft)
    assert(Adjudication.validate(reply(conf = "1.5"), req).isLeft)
    assert(Adjudication.validate("[" + reply() + "]", req).isLeft)
  }

  test("no identifier can be created: other IDs, and digits in reason codes, are refused") {
    assert(
      Adjudication
        .validate(reply(src = s"orcid:$OrcidA"), req)
        .left
        .exists(_.contains("not the candidate"))
    )
    assert(Adjudication.validate(reply(tgt = "m:cccccccccccccccccccc"), req).isLeft)
    assert(Adjudication.validate(reply(codes = "\"SAME_AS_A5054810403\""), req).isLeft)
    assert(Adjudication.validate(reply(codes = "\"see 10.1000/x\""), req).isLeft)
    assert(Adjudication.validate(reply(codes = ""), req).isLeft)
  }

  test("cited fields must be given; an ACCEPT or REJECT must cite something") {
    assert(Adjudication.validate(reply(cited = "\"left.names\",\"score\""), req).isRight)
    assert(
      Adjudication
        .validate(reply(cited = "\"secret_field\""), req)
        .left
        .exists(_.contains("not given"))
    )
    assert(Adjudication.validate(reply(cited = ""), req).isLeft)
    assert(Adjudication.validate(reply("ABSTAIN", cited = ""), req).isRight)
  }

  test("the cache key changes with each of input, prompt, model and policy version") {
    val base = Adjudication.cacheKey(req.inputHash, "p1", "m1", "er-v1")
    val keys = Vector(
      Adjudication.cacheKey(req.copy(score = 0.81).inputHash, "p1", "m1", "er-v1"),
      Adjudication.cacheKey(req.inputHash, "p2", "m1", "er-v1"),
      Adjudication.cacheKey(req.inputHash, "p1", "m2", "er-v1"),
      Adjudication.cacheKey(req.inputHash, "p1", "m1", "er-v2")
    )
    assert(keys.forall(_ != base))
    assertEquals(Adjudication.cacheKey(req.inputHash, "p1", "m1", "er-v1"), base)
    assertEquals(req.inputHash, req.copy().inputHash) // deterministic input JSON
  }

  test(
    "cached answers cost no call; the budget bounds fresh calls; refused replies are not cached"
  ) {
    val port = new Scripted(reply(), "not json")
    val a = new Adjudicator(port, template, new AdjudicationCache(), budget = 2, "er-v1")
    val rec = a.adjudicate(req) match
      case AdjudicationResult.Answered(r, false) => r
      case other                                 => fail(s"unexpected $other")
    assertEquals(a.adjudicate(req), AdjudicationResult.Answered(rec, fromCache = true))
    assertEquals(port.calls, 1)
    val other = req.copy(candidateId = "mc:researcher:00000000000000000002")
    a.adjudicate(other) match
      case AdjudicationResult.Skipped(reason, _) => assertEquals(reason, "LLM_INVALID_OUTPUT")
      case unexpected                            => fail(s"unexpected $unexpected")
    assertEquals(port.calls, 2)
    a.adjudicate(other) match
      case AdjudicationResult.Skipped(reason, _) => assertEquals(reason, "LLM_BUDGET_EXHAUSTED")
      case unexpected                            => fail(s"unexpected $unexpected")
    assertEquals(port.calls, 2) // refused by the budget, not called
  }

  test("off mode never calls the port and a preloaded cache is used across runs") {
    val off = new Adjudicator(OffAdjudicator, template, new AdjudicationCache(), 10, "er-v1")
    assertEquals(off.adjudicate(req), AdjudicationResult.Skipped("LLM_OFF", ""))
    val port = new Scripted(reply())
    val first = new Adjudicator(port, template, new AdjudicationCache(), 1, "er-v1")
    val rec = first.adjudicate(req) match
      case AdjudicationResult.Answered(r, false) => r
      case other                                 => fail(s"unexpected $other")
    val lines =
      Vector(upickle.default.write(rec), upickle.default.write(rec.copy(cacheKey = "0" * 64)))
    val (records, errors) = AdjudicationCache.parse(lines)
    assertEquals((records.size, errors.size), (1, 1)) // a line with a forged key is refused
    val later = new Scripted()
    val second = new Adjudicator(later, template, new AdjudicationCache(records), 0, "er-v1")
    assertEquals(second.adjudicate(req), AdjudicationResult.Answered(rec, fromCache = true))
    assertEquals(later.calls, 0)
  }

  test("a forged but correctly keyed cache line is re-validated, refused and costs no call") {
    val port = new Scripted(reply())
    val key = Adjudication.cacheKey(req.inputHash, template.version, port.modelVersion, "er-v1")
    val good = AdjudicationOutput(
      "ACCEPT",
      req.sourceId,
      req.targetId,
      0.99,
      Vector("SHARED_COAUTHORS"),
      Vector("coauthor_overlap")
    )
    def line(o: AdjudicationOutput) = upickle.default.write(
      AdjudicationRecord(
        key,
        req.inputHash,
        req.candidateId,
        template.version,
        port.modelVersion,
        "er-v1",
        o
      )
    )
    val forged = Vector(
      good.copy(reasonCodes = Vector("see 10.1000/x")), // an identifier hidden in a reason code
      good.copy(sourceId = s"orcid:$OrcidA"), // an ID that is not the candidate's
      good.copy(targetId = "m:cccccccccccccccccccc"),
      good.copy(citedFields = Vector("secret_field")), // a field that was not given
      good.copy(citedFields = Vector.empty), // an ACCEPT that cites nothing
      good.copy(confidence = 1.5),
      good.copy(decision = "MERGE"),
      good.copy(reasonCodes = Vector.empty)
    )
    forged.foreach { o =>
      val (records, errors) = AdjudicationCache.parse(Vector(line(o)))
      assertEquals((records.size, errors.size), (1, 0), o) // the key alone does not catch it
      val a = new Adjudicator(port, template, new AdjudicationCache(records), budget = 5, "er-v1")
      a.adjudicate(req) match
        case AdjudicationResult.Skipped(reason, detail) =>
          assertEquals(reason, "LLM_INVALID_OUTPUT", o)
          assert(detail.startsWith("cached reply refused"), detail)
        case other => fail(s"forged cache line $o was used: $other")
      assertEquals(a.callsMade, 0)
    }
    assertEquals(port.calls, 0)
    // The same line with a valid output is still replayed as a cache hit.
    val (ok, _) = AdjudicationCache.parse(Vector(line(good)))
    val replay = new Adjudicator(port, template, new AdjudicationCache(ok), budget = 0, "er-v1")
    assertEquals(replay.adjudicate(req), AdjudicationResult.Answered(ok.head, fromCache = true))
  }

  test("a cache record filed under a request's key but made for another request is refused") {
    val port = new Scripted()
    val key = Adjudication.cacheKey(req.inputHash, template.version, port.modelVersion, "er-v1")
    val other = req.copy(candidateId = "mc:researcher:00000000000000000002")
    val out = AdjudicationOutput(
      "ACCEPT",
      req.sourceId,
      req.targetId,
      0.99,
      Vector("SHARED_COAUTHORS"),
      Vector("coauthor_overlap")
    )
    val misfiled = Vector(
      AdjudicationRecord(
        key,
        other.inputHash,
        req.candidateId,
        template.version,
        "m",
        "er-v1",
        out
      ),
      AdjudicationRecord(
        key,
        req.inputHash,
        other.candidateId,
        template.version,
        "m",
        "er-v1",
        out
      ),
      AdjudicationRecord(
        key,
        req.inputHash,
        req.candidateId,
        "p0",
        port.modelVersion,
        "er-v1",
        out
      ),
      AdjudicationRecord(key, req.inputHash, req.candidateId, template.version, "m0", "er-v1", out),
      AdjudicationRecord(
        key,
        req.inputHash,
        req.candidateId,
        template.version,
        port.modelVersion,
        "er-v0",
        out
      )
    )
    misfiled.foreach { rec =>
      // Built in memory, so no key check ran; the adjudicator must still refuse it.
      val a = new Adjudicator(port, template, new AdjudicationCache(Vector(rec)), 5, "er-v1")
      a.adjudicate(req) match
        case AdjudicationResult.Skipped(reason, detail) =>
          assertEquals(reason, "LLM_INVALID_OUTPUT")
          assert(detail.contains("does not match the request"), detail)
        case unexpected => fail(s"misfiled record $rec was used: $unexpected")
    }
    assertEquals(port.calls, 0)
  }

  test("the prompt embeds the request JSON once, as data, and its text is pinned to its version") {
    assertEquals(template.version, "er-adjudication-v1")
    val prompt = template.render(req)
    assert(prompt.contains(req.inputJson))
    assert(!prompt.contains(PromptTemplate.Slot))
    assert(prompt.contains("ABSTAIN") && prompt.contains("Never invent"))
    // Editing the prompt text without a new prompt version breaks this pin.
    assertEquals(template.sha256, ErFixtures.PromptSha256)
  }

  test("the deterministic fake answers through the same schema and validator") {
    val fake = new FakeAdjudicator(template.version)
    val out = fake.complete("", req).flatMap(Adjudication.validate(_, req))
    assertEquals(out.map(_.decision), Right("ACCEPT"))
    val weak = req.copy(features = req.features.updated(Features.CoauthorOverlap, 0.1))
    assertEquals(
      fake.complete("", weak).flatMap(Adjudication.validate(_, weak)).map(_.decision),
      Right("ABSTAIN")
    )
    assertEquals(fake.modelVersion, "fake-deterministic-v1")
    assertEquals(fake.complete("", req), fake.complete("", req))
  }

  test("bedrock mode is not available before Phase 07") {
    assert(AdjudicatorMode.parse("bedrock").left.exists(_.contains("Phase 07")))
    assertEquals(AdjudicatorMode.parse("off"), Right(AdjudicatorMode.OFF))
  }

class ResolverSuite extends munit.FunSuite:
  import ErFixtures.*
  import ErFixtures.Run.*

  private val template = PromptTemplate.load().fold(e => throw new AssertionError(e), identity)
  private val runId = Ids.run("""{"fixture":"er-resolver"}""")
  private val band = merged(bandCandidate)
  private val (left, right) = sides(bandCandidate)

  private final class Port(answer: AdjudicationRequest => String) extends AdjudicatorPort:
    var calls = 0
    val mode: AdjudicatorMode = AdjudicatorMode.FAKE
    val promptVersion: String = template.version
    val modelVersion = "test-model-1"
    def complete(prompt: String, r: AdjudicationRequest) = { calls += 1; Right(answer(r)) }

  private def json(r: AdjudicationRequest, decision: String, conf: Double) =
    s"""{"decision":"$decision","sourceId":"${r.sourceId}","targetId":"${r.targetId}","confidence":$conf,"reasonCodes":["X_Y"],"citedFields":["coauthor_overlap"]}"""

  private def resolver(port: AdjudicatorPort, budget: Int = 5) =
    new Resolver(
      policy,
      new Adjudicator(port, template, new AdjudicationCache(), budget, "er-v1"),
      runId,
      "2026-11-23T00:00:00Z"
    )

  test("off mode: a band case stays UNRESOLVED with its reason and a valid core decision") {
    val r = resolver(OffAdjudicator).decide(band, left, right)
    assertEquals(r.decision.decision, MatchOutcome.UNRESOLVED)
    assertEquals(r.decision.reasonCodes, Vector("SCORE_IN_LLM_BAND", "LLM_OFF"))
    assertEquals(r.decision.promptVersion, None)
    assertEquals(Validate.decision(r.decision), Vector.empty[String])
    assertEquals(
      r.decision.decisionId,
      Ids.decision(EntityKind.RESEARCHER, band.sourceId, band.targetId, "er-v1")
    )
    assertEquals(r.decision.provenance.sourceRecordId, band.candidateId)
    assertEquals(r.audit.upstreamRunId, UpstreamRunId)
    assertEquals(r.audit.decidedAt, "2026-11-23T00:00:00Z")
  }

  test("an ACCEPT moves a band case to LLM_ADJUDICATED with prompt and model versions") {
    val port = new Port(json(_, "ACCEPT", 0.9))
    val r = resolver(port).decide(band, left, right)
    val d = r.decision
    assertEquals((d.decision, d.path), (MatchOutcome.ACCEPTED, MatchPath.LLM_ADJUDICATED))
    assertEquals(
      (d.promptVersion, d.modelVersion),
      (Some("er-adjudication-v1"), Some("test-model-1"))
    )
    assertEquals(d.confidence, 0.9)
    assert(d.rulesFired.contains("R-LLM") && d.reasonCodes.contains("LLM_X_Y"))
    assertEquals(Validate.decision(d), Vector.empty[String])
    assertEquals(r.adjudication.map(_.cacheKey), r.audit.adjudicationCacheKey)
  }

  test("a REJECT records P(match) as one minus the reply confidence") {
    val d = resolver(new Port(json(_, "REJECT", 0.9))).decide(band, left, right).decision
    assertEquals(d.decision, MatchOutcome.REJECTED)
    assertEqualsDouble(d.confidence, 0.1, 1e-12)
  }

  test("abstention and low confidence leave the case UNRESOLVED") {
    val abstain = resolver(new Port(json(_, "ABSTAIN", 0.9))).decide(band, left, right).decision
    assertEquals(
      (abstain.decision, abstain.reasonCodes.last),
      (MatchOutcome.UNRESOLVED, "LLM_ABSTAINED")
    )
    val low = resolver(new Port(json(_, "ACCEPT", 0.5))).decide(band, left, right).decision
    assertEquals(
      (low.decision, low.reasonCodes.last),
      (MatchOutcome.UNRESOLVED, "LLM_LOW_CONFIDENCE")
    )
    assertEquals(low.path, MatchPath.PROBABILISTIC)
  }

  test("adjudication changes neither the candidate, the profiles nor the features") {
    val before = (band, left, right)
    val off = resolver(OffAdjudicator).decide(band, left, right).decision
    val on = resolver(new Port(json(_, "ACCEPT", 0.9))).decide(band, left, right).decision
    assertEquals((band, left, right), before)
    assertEquals(on.features, off.features)
    assertEquals(
      (on.sourceId, on.targetId, on.decisionId),
      (off.sourceId, off.targetId, off.decisionId)
    )
  }

  test("only band cases reach the adjudicator") {
    val port = new Port(json(_, "REJECT", 0.99))
    val res = resolver(port)
    val bob = blocked.find(_.blockKey == "jones_b").get
    val (bl, br) = sides(bob)
    val d = res.decide(merged(bob), bl, br).decision
    assertEquals((d.decision, d.path), (MatchOutcome.ACCEPTED, MatchPath.PROBABILISTIC))
    val carol = blocked.find(_.blockKey == "white_c").get
    assertEquals(
      res.decide(merged(carol), sides(carol)._1, sides(carol)._2).decision.decision,
      MatchOutcome.CONFLICT
    )
    assertEquals(port.calls, 0)
  }

  test("every decision records the probabilistic score as a feature") {
    val d = resolver(OffAdjudicator).decide(band, left, right).decision
    assert(d.features.contains(Resolver.ScoreFeature))
    assertEquals(d.features(Resolver.ScoreFeature), d.confidence)
  }

  test("a forged cache line replayed through the resolver never yields LLM_ADJUDICATED") {
    val fresh = resolver(new Port(json(_, "ACCEPT", 0.9))).decide(band, left, right)
    val rec = fresh.adjudication.getOrElse(fail("the band case was not adjudicated"))
    val forgedLine = upickle.default.write(
      rec.copy(output = rec.output.copy(confidence = 0.99, reasonCodes = Vector("see 10.1000/x")))
    )
    val (records, errors) = AdjudicationCache.parse(Vector(forgedLine))
    assertEquals((records.size, errors.size), (1, 0)) // correctly keyed
    val port = new Port(json(_, "ACCEPT", 0.9))
    val replay = new Resolver(
      policy,
      new Adjudicator(port, template, new AdjudicationCache(records), 5, "er-v1"),
      runId,
      "2026-11-23T00:00:00Z"
    ).decide(band, left, right)
    val d = replay.decision
    assertEquals((d.decision, d.path), (MatchOutcome.UNRESOLVED, MatchPath.PROBABILISTIC))
    assertEquals(d.reasonCodes.last, "LLM_INVALID_OUTPUT")
    assert(!d.reasonCodes.exists(_.contains("10.1000")), d.reasonCodes)
    assert(!d.rulesFired.contains(Resolver.LlmRule))
    assertEquals((replay.adjudication, replay.audit.adjudicationFromCache), (None, None))
    assertEquals(port.calls, 0)
    assertEquals(Validate.decision(d), Vector.empty[String])
  }
