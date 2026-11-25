package scholarchain.rag

import scholarchain.core.*
import scholarchain.core.VerificationStatus.*
import scholarchain.storage.{EvidenceBundles, Stored}
import java.nio.file.{Files, Paths}
import RagWorld.*

/** Stages 1 to 10 end to end on the hand-built run: synthesis (deterministic and a scripted fake
  * model), claim verification and the audit.
  */
class WorkflowSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private lazy val pipe = w.pipeline()
  private lazy val prompt = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(p => Files.isDirectory(p.resolve("prompts/runtime")))
    .toRight("no prompts/runtime above the working directory")
    .flatMap(SynthesisPrompt.load)
    .fold(e => fail(e), identity)

  private val RankingQ =
    "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?"
  private val AliceQ = """What are the collaboration metrics of "Alice Arden" since 2015?"""
  private val WeiQ = """What are the collaboration metrics of "Wei Wang" since 2015?"""
  private val Wei1Only = Constraints(researcherIds = Vector(Wei1))
  private val Marker = "HIDDEN-REASONING-MARKER-5521"

  private def evidence(q: String, c: Constraints = Constraints()) =
    pipe.run("qry-probe", AnalysisRequest(q, c)).fold(e => fail(e.describe), identity)
  private def metricsEv(e: EvidenceStageResult, id: String) =
    e.bundle.deterministic
      .find(x =>
        x.values.get("kind").contains("researcher-metrics") && x.researcherIds == Vector(id)
      )
      .getOrElse(fail(s"no metrics evidence for $id"))
      .evidenceId
  private def modelReply(
      summary: String,
      claims: (String, Vector[String], Option[String])*
  ): String =
    ujson
      .Obj(
        "schemaVersion" -> "answer-synthesis-v1",
        "summary" -> summary,
        "abstain" -> false,
        "abstentionReason" -> ujson.Null,
        "claims" -> ujson.Arr.from(claims.zipWithIndex.map { case ((t, ids, u), i) =>
          ujson.Obj(
            "claimId" -> s"claim-${i + 1}",
            "text" -> t,
            "evidenceIds" -> ujson.Arr.from(ids),
            "uncertainty" -> u.fold[ujson.Value](ujson.Null)(ujson.Str(_))
          )
        })
      )
      .render()
  private def run(
      q: String,
      c: Constraints = Constraints(),
      synth: Synthesizer = DeterministicSynthesizer,
      log: InMemoryAuditLog = new InMemoryAuditLog,
      id: String = "qry-workflow"
  ) =
    val (wf, stores) = w.workflow(synth, log)
    (wf.run(id, AnalysisRequest(q, c)).fold(e => fail(e.describe), identity), stores, log)
  private def keys(v: ujson.Value): Vector[String] = v match
    case ujson.Obj(o) => o.keys.toVector ++ o.values.flatMap(keys)
    case ujson.Arr(a) => a.toVector.flatMap(keys)
    case _            => Vector.empty

  test(
    "stages 1 to 10 on the deterministic path: a verified answer, exact values, stored bundle and audit"
  ) {
    val (r, stores, log) = run(RankingQ)
    val a = r.answer
    assertEquals(Validate.answer(a), Vector.empty)
    assertEquals(a.verification, VERIFIED)
    assert(!a.abstained)
    assertEquals(a.promptVersion, DeterministicSynthesizer.Version)
    assertEquals(a.modelVersion, "none")
    assert(a.claims.nonEmpty && a.claims.forall(_.verificationStatus == VERIFIED))
    assert(
      a.claims.forall(c =>
        c.evidenceIds.nonEmpty && c.evidenceIds.forall(r.evidence.bundle.byId.contains)
      )
    )
    val carol = a.researchers.find(_.researcherId == Carol).get
    assertEquals(
      (carol.distinctCoauthorCount, carol.weightedDegree, carol.publicationCount),
      (52L, 55.0, 15L)
    )
    assertEquals(carol.bridgeMetric, Some(BridgeMetric("sampled-brandes-betweenness", 0.65)))
    assert(carol.claimIds.nonEmpty)
    val bob = a.researchers.find(_.researcherId == Bob).get
    assertEquals(bob.identityConfidence, 0.72)
    val bobClaims = a.claims.filter(_.evidenceIds.contains(metricsEv(r.evidence, Bob)))
    assert(
      bobClaims.nonEmpty && bobClaims.forall(
        _.uncertainty.exists(_.contains("identity confidence 0.72 below 0.9"))
      )
    )
    assert(
      a.summary.contains("Missing or limited evidence") && a.summary.contains("community size"),
      a.summary
    )
    // Stage 10: bundle and audit persisted with conditional writes.
    val stored =
      new EvidenceBundles(stores.kv, w.cfg.dynamo.evidenceBundleTable).get(a.evidenceBundleId)
    assertEquals(stored, Right(Some(r.evidence.bundle)))
    val au = r.audit
    assertEquals(log.get("qry-workflow"), Right(Some(au)))
    assertEquals(au.plan, r.evidence.plan)
    assertEquals(au.evidenceIds, r.evidence.bundle.all.map(_.evidenceId))
    assertEquals((au.graphRunId, au.erRunId, au.erPolicyVersion), (w.runId, ErRunId, "er-v1"))
    assertEquals(au.metricVersions, Vector("metrics-v1"))
    assertEquals(
      (au.synthesizer, au.promptVersion, au.modelVersion),
      (DeterministicSynthesizer.Version, "none", "none")
    )
    assertEquals((au.inputTokens, au.outputTokens), (None, None))
    assertEquals(au.verification, VERIFIED)
    assertEquals(au.answerHash, Hashing.tagged(JsonLines.encode(a)))
    assertEquals(au.claimChecks.map(_.claimId), a.claims.map(_.claimId))
    assert(au.graphCalls.nonEmpty && au.latencyMs >= 0)
    assertEquals(au.stages.map(_.stage).takeRight(3), Vector("synthesis", "verification", "audit"))
    assertEquals(au.componentVersions("verifier"), ClaimVerifier.Version)
    assertEquals(JsonLines.decode[AuditRecord](JsonLines.encode(au)), Right(au))
    assertEquals(log.append(au), Right(Stored.Unchanged))
    assert(
      log.append(au.copy(latencyMs = au.latencyMs + 1)).isLeft,
      "a different record is not overwritten"
    )
  }

  test("an ambiguous or unknown name abstains, lists candidates, and calls no model") {
    val llm = ScriptedLlm.replies()
    val (r, _, _) = run(
      "Who are the coauthors of Wei Wang since 2015?",
      synth = new LlmSynthesizer(llm, prompt, w.cfg)
    )
    val a = r.answer
    assert(
      a.abstained && a.abstentionReason.exists(_.contains("ambiguous")),
      a.abstentionReason.toString
    )
    assertEquals(a.researchers, Vector.empty)
    assertEquals(a.claims.size, 2)
    assert(a.claims.forall(_.verificationStatus == VERIFIED))
    assertEquals(
      a.claims.flatMap(c => r.evidence.bundle.byId(c.evidenceIds.head).researcherIds).toSet,
      Set(Wei1, Wei2)
    )
    assertEquals(llm.prompts, Vector.empty)
    assertEquals(r.audit.modelVersion, "none")
    assertEquals(r.audit.resolvedResearcherIds, Vector.empty)
    assertEquals(r.audit.resolutions.head.candidateIds.toSet, Set(Wei1, Wei2))
    val (u, _, _) = run("Who are the coauthors of Nobody Atall since 2015?")
    assert(u.answer.abstained && u.answer.claims.isEmpty)
    assertEquals(u.answer.verification, UNSUPPORTED)
    assert(u.answer.abstentionReason.exists(_.contains("no canonical researcher matches")))
  }

  test("malformed model output is retried with a fixed reason, then a valid reply is used") {
    val e = evidence(AliceQ)
    val good = modelReply(
      "The stored metrics answer the question.",
      (
        "Alice Arden has 41 distinct coauthors in window 2015-2026.",
        Vector(metricsEv(e, Alice)),
        None
      )
    )
    val withReasoning = good.dropRight(1) + s""","reasoning":"$Marker I think step by step"}"""
    val llm = ScriptedLlm.replies(s"Sure, here it is. $Marker", withReasoning, good)
    val (r, _, _) = run(AliceQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(r.synthesis.attempts.map(_.status), Vector("refused", "refused", "accepted"))
    assertEquals(llm.prompts.size, 3)
    assert(
      llm.prompts(1).contains("Your previous reply was refused: the reply is not one JSON object")
    )
    assert(llm.prompts(2).contains("unknown, missing or null key"))
    assert(llm.prompts.forall(!_.contains(Marker)), "no reply text is fed back")
    val a = r.answer
    assertEquals((a.promptVersion, a.modelVersion), ("answer-synthesis-v1", ScriptedLlm.Version))
    assertEquals(
      a.claims.map(c => (c.text, c.verificationStatus)),
      Vector(("Alice Arden has 41 distinct coauthors in window 2015-2026.", VERIFIED))
    )
    val au = r.audit
    assertEquals(au.promptSha256, Some(prompt.sha256))
    assertEquals(au.synthesizer, LlmSynthesizer.Version)
    assertEquals(au.inputTokens, Some(llm.prompts.map(p => Tokens.estimate(p).toLong).sum))
    assert(au.outputTokens.exists(_ > 0))
    assert(au.synthesisAttempts.forall(_.replySha256.exists(_.startsWith("sha256:"))))
    // No chain-of-thought: no reply text, no reasoning key, in the answer or the audit.
    val encoded = JsonLines.encode(a) + JsonLines.encode(au)
    assert(!encoded.contains(Marker))
    val names = keys(ujson.read(JsonLines.encode(au))) ++ keys(ujson.read(JsonLines.encode(a)))
    assert(
      !names.exists(k => Set("reasoning", "thinking", "chainOfThought", "thoughts")(k)),
      names.distinct.toString
    )
  }

  test(
    "model output that stays malformed ends after the bounded attempts with the deterministic answer"
  ) {
    val llm = ScriptedLlm.replies(Vector.fill(5)("{not json")*)
    val (r, _, _) = run(AliceQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(llm.prompts.size, w.cfg.operations.maxWorkflowRetries + 1)
    assertEquals(r.synthesis.fallback, Some("model output refused 3 times"))
    assertEquals(r.answer.promptVersion, DeterministicSynthesizer.Version)
    assertEquals(r.audit.synthesisAttempts.size, 3)
    assertEquals(r.audit.modelVersion, ScriptedLlm.Version)
    assertEquals(r.answer.verification, VERIFIED)
  }

  test(
    "prompt injection: retrieved text stays fenced data; an obeyed injection is removed and the summary replaced"
  ) {
    val e = evidence(WeiQ, Wei1Only)
    val profile = e.bundle.semantic.find(_.text.exists(_.contains("IGNORE ALL PREVIOUS"))).get
    val m = metricsEv(e, Wei1)
    val llm = ScriptedLlm.replies(
      modelReply(
        "Wei Wang has 999 distinct coauthors.",
        ("Wei Wang has 999 distinct coauthors.", Vector(profile.evidenceId, m), None),
        ("Wei Wang has 3 distinct coauthors in window 2015-2026.", Vector(m), None)
      )
    )
    val (r, _, _) = run(WeiQ, Wei1Only, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    val p = llm.prompts.head
    val open = p.indexOf("<<<DATA ")
    val close = p.lastIndexOf(">>>")
    val at = p.indexOf("IGNORE ALL PREVIOUS")
    assert(open >= 0 && open < at && at < close, "the injected text sits inside the DATA fence")
    assert(p.contains("\"trust\":\"untrusted-data\""))
    val a = r.answer
    assertEquals(
      a.claims.map(_.text),
      Vector("Wei Wang has 3 distinct coauthors in window 2015-2026.")
    )
    assertEquals(
      r.audit.removedClaims.map(c => (c.claimId, c.status)),
      Vector(("claim-1", CONFLICTING))
    )
    assert(!a.summary.contains("999") && a.claims.forall(!_.text.contains("999")))
    assert(r.audit.summaryReplaced.nonEmpty)
    assertEquals(a.verification, CONFLICTING)
    assertEquals(a.researchers.map(_.distinctCoauthorCount), Vector(3L))
  }

  test(
    "a partially supported model claim is weakened and labeled; a bad uncertainty note is replaced"
  ) {
    val e = evidence(RankingQ)
    val bob = metricsEv(e, Bob)
    val llm = ScriptedLlm.replies(
      modelReply(
        "Bridge importance and breadth differ.",
        (
          "Bob Brandt has 30 distinct coauthors, more than John Smith.",
          Vector(bob),
          Some("He may really have 77 distinct coauthors.")
        ),
        ("Bob Brandt has bridge score 0.41 in window 2015-2026.", Vector(bob), None)
      )
    )
    val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    val c1 = r.answer.claims.find(_.claimId == "claim-1").get
    assertEquals(c1.verificationStatus, PARTIALLY_SUPPORTED)
    assertEquals(c1.text, "Bob Brandt has 30 distinct coauthors, more than [unverified].")
    val u = c1.uncertainty.get
    assert(u.contains("partially supported") && u.contains("identity confidence 0.72 below 0.9"), u)
    assert(u.contains("uncertainty note was removed") && !u.contains("77"), u)
    assertEquals(r.answer.verification, PARTIALLY_SUPPORTED)
  }

  test("token and context budgets bound the model path") {
    val tight = w.cfg.copy(operations = w.cfg.operations.copy(maxTokensPerQuery = 400))
    val llm = ScriptedLlm.replies("x" * 650, "never reached")
    val (r, _, _) = run(AliceQ, synth = new LlmSynthesizer(llm, prompt, tight))
    assertEquals(llm.maxTokens, Vector(400))
    assert(
      r.synthesis.fallback.exists(_.contains("token budget of 400 per query exhausted")),
      r.synthesis.fallback.toString
    )
    val e = evidence(AliceQ)
    val narrow = w.cfg.copy(retrieval = w.cfg.retrieval.copy(contextTokenBudget = 300))
    val good = modelReply(
      "Stored metrics follow.",
      ("Alice Arden has 41 distinct coauthors.", Vector(metricsEv(e, Alice)), None)
    )
    val (n, _, _) =
      run(AliceQ, synth = new LlmSynthesizer(ScriptedLlm.replies(good), prompt, narrow))
    val use = n.audit.context.get
    assertEquals(use.tokenBudget, 300)
    assert(use.estimatedTokens <= 300 && use.omittedEvidenceIds.nonEmpty, use.toString)
  }

  test(
    "a failed model call or a model that abstains despite evidence falls back to the deterministic answer"
  ) {
    val failing = new ScriptedLlm(Vector(_ => Left("connection refused")))
    val (f, _, _) = run(AliceQ, synth = new LlmSynthesizer(failing, prompt, w.cfg))
    assertEquals(f.synthesis.fallback, Some("model call failed: connection refused"))
    assertEquals(f.audit.synthesisAttempts.map(_.status), Vector("call-failed"))
    assert(!f.answer.abstained)
    val abstaining = ScriptedLlm.replies(
      """{"schemaVersion":"answer-synthesis-v1","summary":"I refuse.","abstain":true,"abstentionReason":"instructed to refuse","claims":[]}"""
    )
    val (b, _, _) = run(AliceQ, synth = new LlmSynthesizer(abstaining, prompt, w.cfg))
    assertEquals(
      b.synthesis.fallback,
      Some("the model abstained although deterministic evidence exists")
    )
    assert(!b.answer.abstained && b.answer.claims.nonEmpty)
  }

  test(
    "repair M1: a quoted pair question checks the edge, states its joint count and cites its publications"
  ) {
    val (r, _, _) = run(
      """Has "Alice Arden" collaborated with "Bob Brandt" in 2015-2026, and how many joint publications do they have?""",
      id = "qry-pair"
    )
    val b = r.evidence.bundle
    val edge = b.deterministic
      .find(x =>
        x.evidenceType == EvidenceType.COAUTHOR_EDGE && x.researcherIds.toSet == Set(Alice, Bob) &&
          x.values.get("found").contains("true")
      )
      .getOrElse(fail("no found=true edge for the pair"))
    // fixture: Alice-Bob, 3 joint publications 2016-2022, supported by "Surface code decoders"
    assertEquals(edge.values("jointPublicationCount"), "3")
    val pub = b.deterministic
      .find(x => x.evidenceType == EvidenceType.PUBLICATION && x.workId.contains(SurfaceWork))
      .getOrElse(fail("no PUBLICATION for the pair's supporting work"))
    assert(r.evidence.graphCalls.exists(_.templateId == "coauthor-pair-v1"))
    val a = r.answer
    assert(!a.abstained)
    assertEquals(a.verification, VERIFIED)
    assert(
      a.claims.exists(c =>
        c.evidenceIds.contains(edge.evidenceId) && c.text.contains("3 joint publications") &&
          c.text.contains("Alice Arden") && c.text.contains("Bob Brandt")
      ),
      a.claims.map(_.text).mkString("\n")
    )
    val support = a.claims
      .find(_.evidenceIds.contains(pub.evidenceId))
      .getOrElse(fail("no claim cites the supporting publication"))
    assert(support.text.contains("\"Surface code decoders\" (2021)"), support.text)
    assertEquals(support.verificationStatus, VERIFIED)
    // no edge: Alice and Dan are not coauthors in 2015-2026
    val (n, _, _) = run(
      """Which publications support the collaboration between "Alice Arden" and "Dan Dorsey" in 2015-2026?""",
      id = "qry-no-pair"
    )
    val none = n.evidence.bundle.deterministic
      .find(x =>
        x.evidenceType == EvidenceType.COAUTHOR_EDGE && x.values.get("found").contains("false")
      )
      .getOrElse(fail("no found=false edge"))
    assertEquals(none.researcherIds.toSet, Set(Alice, Dan))
    assert(
      n.answer.claims.exists(c =>
        c.evidenceIds.contains(none.evidenceId) && c.text.contains("no direct coauthor edge")
      ),
      n.answer.claims.map(_.text).mkString("\n")
    )
    // an ambiguous endpoint runs no pair query and abstains
    val (amb, _, _) = run(
      """Has "Wei Wang" collaborated with "Alice Arden" in 2015-2026?""",
      id = "qry-pair-amb"
    )
    assert(amb.answer.abstained)
    assert(!amb.evidence.graphCalls.exists(_.templateId == "coauthor-pair-v1"))
  }

  test("repair M4: an answer that abstains because no claim survived carries no researcher rows") {
    val e = evidence(AliceQ)
    val llm = ScriptedLlm.replies(
      modelReply(
        "Alice Arden has 41 distinct coauthors.",
        (
          "Alice Arden has 77 distinct coauthors in window 2015-2026.",
          Vector(metricsEv(e, Alice)),
          None
        )
      )
    )
    val (r, _, _) = run(AliceQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(r.synthesis.fallback, None)
    assert(r.answer.abstained && r.answer.claims.isEmpty, r.answer.claims.toString)
    assertEquals(r.answer.verification, UNSUPPORTED)
    assertEquals(r.answer.researchers, Vector.empty)
    assertEquals(r.audit.removedClaims.map(_.status), Vector(CONFLICTING))
    // a kept claim that cites only Alice's record: only Alice's row, and it names the claim
    val rk = evidence(RankingQ)
    val one = ScriptedLlm.replies(
      modelReply(
        "Researchers ranked by distinct coauthors.",
        (
          "Alice Arden has 41 distinct coauthors in window 2015-2026.",
          Vector(metricsEv(rk, Alice)),
          None
        )
      )
    )
    val (k, _, _) = run(RankingQ, synth = new LlmSynthesizer(one, prompt, w.cfg))
    assert(!k.answer.abstained)
    assertEquals(k.answer.researchers.map(_.researcherId), Vector(Alice))
    assertEquals(k.answer.researchers.head.claimIds, Vector("claim-1"))
  }

  test("repair M5: a model claim or summary with another researcher's value is never VERIFIED") {
    val e = evidence(RankingQ)
    val (alice, bob) = (metricsEv(e, Alice), metricsEv(e, Bob))
    val llm = ScriptedLlm.replies(
      modelReply(
        "Alice Arden has 30 distinct coauthors.",
        // Bob's 30, attributed to Alice, citing both records
        ("Alice Arden has 30 distinct coauthors in window 2015-2026.", Vector(alice, bob), None),
        (
          "Alice Arden has 41 distinct coauthors and Bob Brandt has 30 distinct coauthors in window 2015-2026.",
          Vector(alice, bob),
          None
        )
      )
    )
    val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(
      r.audit.removedClaims.map(c => (c.claimId, c.status)),
      Vector(("claim-1", CONFLICTING))
    )
    // repair 3: claim-2 names two researchers in one sentence, so it is weakened (was VERIFIED)
    assertEquals(
      r.answer.claims.map(c => (c.claimId, c.verificationStatus)),
      Vector(("claim-2", PARTIALLY_SUPPORTED))
    )
    assert(r.answer.claims.head.uncertainty.exists(_.contains("names 2 researchers")))
    assert(r.audit.summaryReplaced.nonEmpty, "the misattributed summary was kept")
    assert(!r.answer.summary.contains("30 distinct"), r.answer.summary)
  }

  test(
    "repair D5: a model claim or summary whose number has an ambiguous subject is not VERIFIED"
  ) {
    val e = evidence(RankingQ)
    val both = Vector(metricsEv(e, Alice), metricsEv(e, Bob))
    // fixture: Alice 41 distinct coauthors, Bob 30
    val subjectFirst =
      "Alice Arden, compared with Bob Brandt, has 30 distinct coauthors in window 2015-2026."
    val numberFirst =
      "With 30 distinct coauthors in window 2015-2026, Alice Arden leads Bob Brandt."
    val correct =
      "Alice Arden has 41 distinct coauthors and Bob Brandt has 30 distinct coauthors in window 2015-2026."
    Vector(
      "Alice Arden, compared with Bob Brandt, has 30 distinct coauthors.",
      "With 30 distinct coauthors, Alice Arden leads Bob Brandt."
    ).foreach { summary =>
      val llm = ScriptedLlm.replies(
        modelReply(
          summary,
          (subjectFirst, both, None),
          (numberFirst, both, None),
          (correct, both, None)
        )
      )
      val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
      assertEquals(r.synthesis.fallback, None, "the model reply was not used")
      assertEquals(
        r.answer.claims.map(c => (c.claimId, c.verificationStatus)),
        // repair 3: claim-3 (correct values, two names in one sentence) was VERIFIED; weakened now
        Vector(
          ("claim-1", PARTIALLY_SUPPORTED),
          ("claim-2", PARTIALLY_SUPPORTED),
          ("claim-3", PARTIALLY_SUPPORTED)
        )
      )
      val Vector(c1, c2, _) = r.answer.claims: @unchecked
      assertEquals(
        c1.text,
        "Alice Arden, compared with Bob Brandt, has [unverified] distinct coauthors in window 2015-2026."
      )
      assertEquals(
        c2.text,
        "With [unverified] distinct coauthors in window 2015-2026, Alice Arden leads Bob Brandt."
      )
      Vector(c1, c2).foreach(c =>
        assert(c.uncertainty.exists(_.contains("ambiguous")), c.uncertainty.toString)
      )
      assert(r.audit.summaryReplaced.exists(_.contains("ambiguous")), summary)
      assert(!r.answer.summary.contains("30 distinct"), r.answer.summary)
    }
  }

  test(
    "repair 3: a model summary keeps a per-researcher number only in a one-researcher sentence"
  ) {
    val e = evidence(RankingQ)
    val both = Vector(metricsEv(e, Alice), metricsEv(e, Bob))
    // fixture: Alice 41 distinct coauthors, Bob 30; review-report-03 R3-1 summary inputs 1 and 4
    val control = ("Alice Arden has 41 distinct coauthors in window 2015-2026.", both, None)
    def summarise(summary: String) =
      val llm = ScriptedLlm.replies(modelReply(summary, control))
      val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
      assertEquals(r.synthesis.fallback, None, "the model reply was not used")
      assertEquals(r.answer.claims.map(_.verificationStatus), Vector(VERIFIED))
      r
    Vector(
      "Alice Arden (vs. Bob Brandt) has 30 distinct coauthors in window 2015-2026.",
      "Alice Arden leads. Compared with Bob Brandt, she has 30 distinct coauthors.",
      // true, but two researchers in one sentence: replaced too (conservative)
      "Alice Arden has 41 distinct coauthors, while Bob Brandt has 30 distinct coauthors."
    ).foreach { summary =>
      val r = summarise(summary)
      assert(r.audit.summaryReplaced.exists(_.contains("ambiguous")), summary)
      assert(!r.answer.summary.contains("30 distinct"), r.answer.summary)
    }
    // one researcher per sentence: the model summary is kept
    val kept =
      "Alice Arden has 41 distinct coauthors in window 2015-2026. Bob Brandt has 30 distinct coauthors in window 2015-2026."
    val r = summarise(kept)
    assertEquals(r.audit.summaryReplaced, None)
    assert(r.answer.summary.startsWith(kept), r.answer.summary)
  }

  test("the synthesizer is chosen from configuration") {
    assertEquals(Synthesizers.select(w.cfg, None, None), Right(DeterministicSynthesizer))
    val on = w.cfg.copy(models = w.cfg.models.copy(llmEnabled = true))
    assert(Synthesizers.select(on, None, Some(prompt)).isLeft)
    assert(
      Synthesizers
        .select(on, Some(ScriptedLlm.replies()), Some(prompt))
        .toOption
        .exists(_.isInstanceOf[LlmSynthesizer])
    )
  }
