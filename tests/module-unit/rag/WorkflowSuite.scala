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
  private def fig(rid: String, metric: String, value: String, ids: String*) =
    ClaimFigure(rid, metric, value, ids.toVector)
  private def modelReply(
      summary: String,
      claims: (String, Vector[String], Option[String], Vector[ClaimFigure])*
  ): String =
    ujson
      .Obj(
        "schemaVersion" -> "answer-synthesis-v2",
        "summary" -> summary,
        "abstain" -> false,
        "abstentionReason" -> ujson.Null,
        "claims" -> ujson.Arr.from(claims.zipWithIndex.map { case ((t, ids, u, fs), i) =>
          ujson.Obj(
            "claimId" -> s"claim-${i + 1}",
            "text" -> t,
            "evidenceIds" -> ujson.Arr.from(ids),
            "uncertainty" -> u.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
            "figures" -> ujson.read(upickle.default.write(fs))
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
    assertEquals(ClaimVerifier.Version, "claim-verifier-v3")
    assertEquals(au.componentVersions("synthesisRules"), "synthesis-rules-v2")
    assertEquals(au.outputSchemaVersion, "answer-synthesis-v2")
    // the deterministic path renders bare numbers: no owner label, no placeholder
    assert(
      a.claims.forall(c => !c.text.contains("{figure-") && !c.text.contains("Count of ")),
      a.claims.map(_.text).mkString("\n")
    )
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
    val m = metricsEv(e, Alice)
    val good = modelReply(
      "The stored metrics answer the question.",
      (
        "Alice Arden has {figure-1} distinct coauthors in window 2015-2026.",
        Vector(m),
        None,
        Vector(fig(Alice, "distinctCoauthorCount", "41", m))
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
    assertEquals((a.promptVersion, a.modelVersion), ("answer-synthesis-v2", ScriptedLlm.Version))
    // the prose is an interpretation; the figure is a structured claim written from the evidence
    assertEquals(
      a.claims.map(c => (c.claimId, c.text, c.verificationStatus)),
      Vector(
        (
          "claim-1",
          "UNVERIFIED interpretation: Alice Arden has 41 (distinctCoauthorCount of Alice Arden, window 2015-2026) distinct coauthors in window 2015-2026.",
          PARTIALLY_SUPPORTED
        ),
        (
          "claim-1-figure-1",
          s"distinctCoauthorCount of Alice Arden ($Alice) in window 2015-2026: 41.",
          VERIFIED
        )
      )
    )
    assertEquals(a.claims(1).evidenceIds, Vector(m))
    assertEquals(a.verification, PARTIALLY_SUPPORTED, "model prose is never verified")
    assert(
      a.summary.startsWith("UNVERIFIED interpretation: The stored metrics answer the question."),
      a.summary
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
        (
          "Wei Wang has {figure-1} distinct coauthors.",
          Vector(profile.evidenceId, m),
          None,
          Vector(fig(Wei1, "distinctCoauthorCount", "999", profile.evidenceId, m))
        ),
        (
          "Wei Wang has {figure-1} distinct coauthors in window 2015-2026.",
          Vector(m),
          None,
          Vector(fig(Wei1, "distinctCoauthorCount", "3", m))
        )
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
      a.claims.map(c => (c.claimId, c.text, c.verificationStatus)),
      Vector(
        (
          "claim-2",
          "UNVERIFIED interpretation: Wei Wang has 3 (distinctCoauthorCount of Wei Wang, window 2015-2026) distinct coauthors in window 2015-2026.",
          PARTIALLY_SUPPORTED
        ),
        (
          "claim-2-figure-1",
          s"distinctCoauthorCount of Wei Wang ($Wei1) in window 2015-2026: 3.",
          VERIFIED
        )
      )
    )
    // the obeyed injection's figure is removed with its prose
    assertEquals(
      r.audit.removedClaims.map(c => (c.claimId, c.status)),
      Vector(("claim-1", CONFLICTING), ("claim-1-figure-1", CONFLICTING))
    )
    assert(a.claims.forall(!_.text.contains("999")))
    // v3 construction rule: the summary is free text, so its 999 is not looked for; it is shown
    // only as a labeled interpretation, and no VERIFIED claim or row states 999
    assertEquals(r.audit.summaryReplaced, None)
    assert(
      a.summary.startsWith("UNVERIFIED interpretation: Wei Wang has 999 distinct coauthors."),
      a.summary
    )
    assertEquals(a.verification, CONFLICTING)
    assertEquals(a.researchers.map(_.distinctCoauthorCount), Vector(3L))
  }

  test(
    "a model claim is weakened and labeled UNVERIFIED interpretation; notes are labeled or removed"
  ) {
    val e = evidence(RankingQ)
    val bob = metricsEv(e, Bob)
    val llm = ScriptedLlm.replies(
      modelReply(
        "Bridge importance and breadth differ.",
        (
          "Bob Brandt has {figure-1} distinct coauthors, more than John Smith.",
          Vector(bob),
          Some("Carl Unknown may have more distinct coauthors."),
          Vector(fig(Bob, "distinctCoauthorCount", "30", bob))
        ),
        (
          "Bob Brandt has bridge score {figure-1} in window 2015-2026.",
          Vector(bob),
          Some("He may really have 77 distinct coauthors."),
          Vector(fig(Bob, "bridgeScore", "0.41", bob))
        )
      )
    )
    val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    val claims = r.answer.claims.map(c => c.claimId -> c).toMap
    assertEquals(
      r.answer.claims.map(c => (c.claimId, c.verificationStatus)),
      Vector(
        ("claim-1", PARTIALLY_SUPPORTED),
        ("claim-1-figure-1", VERIFIED),
        ("claim-2", PARTIALLY_SUPPORTED),
        ("claim-2-figure-1", VERIFIED)
      )
    )
    assertEquals(
      claims("claim-1").text,
      "UNVERIFIED interpretation: Bob Brandt has 30 (distinctCoauthorCount of Bob Brandt, window 2015-2026) distinct coauthors, more than [unverified]."
    )
    val u = claims("claim-1").uncertainty.get
    assert(u.contains("partially supported") && u.contains("identity confidence 0.72 below 0.9"), u)
    assert(u.contains("UNVERIFIED interpretation: free text from the model is never verified"), u)
    // a note naming a researcher outside the evidence is removed
    assert(u.contains("uncertainty note was removed") && !u.contains("Carl Unknown"), u)
    // a note is free text: whatever it says, it is kept only labeled as an interpretation
    val u2 = claims("claim-2").uncertainty.get
    assert(
      u2.contains(
        "UNVERIFIED interpretation (uncertainty note): He may really have 77 distinct coauthors."
      ),
      u2
    )
    // the structured claims carry the identity caveat of their researcher
    assertEquals(
      claims("claim-2-figure-1").text,
      s"bridgeScore of Bob Brandt ($Bob) in window 2015-2026: 0.41."
    )
    assert(
      claims("claim-2-figure-1").uncertainty.exists(
        _.contains("identity confidence 0.72 below 0.9")
      )
    )
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
      (
        "Alice Arden has {figure-1} distinct coauthors.",
        Vector(metricsEv(e, Alice)),
        None,
        Vector(fig(Alice, "distinctCoauthorCount", "41", metricsEv(e, Alice)))
      )
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
      """{"schemaVersion":"answer-synthesis-v2","summary":"I refuse.","abstain":true,"abstentionReason":"instructed to refuse","claims":[]}"""
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
          "Alice Arden has {figure-1} distinct coauthors in window 2015-2026.",
          Vector(metricsEv(e, Alice)),
          None,
          Vector(fig(Alice, "distinctCoauthorCount", "77", metricsEv(e, Alice)))
        )
      )
    )
    val (r, _, _) = run(AliceQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(r.synthesis.fallback, None)
    assert(r.answer.abstained && r.answer.claims.isEmpty, r.answer.claims.toString)
    assertEquals(r.answer.verification, UNSUPPORTED)
    assertEquals(r.answer.researchers, Vector.empty)
    // the prose and its structured claim are both removed
    assertEquals(
      r.audit.removedClaims.map(c => (c.claimId, c.status)),
      Vector(("claim-1", CONFLICTING), ("claim-1-figure-1", CONFLICTING))
    )
    assertEquals(
      r.audit.removedClaims(1).text,
      s"figure 1 of claim-1: distinctCoauthorCount of $Alice = 77"
    )
    // a kept claim that cites only Alice's record: only Alice's row, and it names both claims
    val rk = evidence(RankingQ)
    val one = ScriptedLlm.replies(
      modelReply(
        "Researchers ranked by distinct coauthors.",
        (
          "Alice Arden has {figure-1} distinct coauthors in window 2015-2026.",
          Vector(metricsEv(rk, Alice)),
          None,
          Vector(fig(Alice, "distinctCoauthorCount", "41", metricsEv(rk, Alice)))
        )
      )
    )
    val (k, _, _) = run(RankingQ, synth = new LlmSynthesizer(one, prompt, w.cfg))
    assert(!k.answer.abstained)
    assertEquals(k.answer.researchers.map(_.researcherId), Vector(Alice))
    assertEquals(k.answer.researchers.head.claimIds, Vector("claim-1", "claim-1-figure-1"))
  }

  test("repair M5 and R4-3: a figure is checked against its own researcher's record only") {
    val e = evidence(RankingQ)
    val (alice, bob) = (metricsEv(e, Alice), metricsEv(e, Bob))
    val llm = ScriptedLlm.replies(
      modelReply(
        "Alice Arden has 30 distinct coauthors.",
        // Bob's 30 as Alice's figure, citing both records
        (
          "Alice Arden has {figure-1} distinct coauthors in window 2015-2026.",
          Vector(alice, bob),
          None,
          Vector(fig(Alice, "distinctCoauthorCount", "30", alice, bob))
        ),
        // two researchers in one sentence: each number is its own structured claim
        (
          "Alice Arden has {figure-1} distinct coauthors and Bob Brandt has {figure-2} distinct coauthors in window 2015-2026.",
          Vector(alice, bob),
          None,
          Vector(
            fig(Alice, "distinctCoauthorCount", "41", alice),
            fig(Bob, "distinctCoauthorCount", "30", bob)
          )
        )
      )
    )
    val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(
      r.audit.removedClaims.map(c => (c.claimId, c.status)),
      Vector(("claim-1", CONFLICTING), ("claim-1-figure-1", CONFLICTING))
    )
    // the audit keeps the drafted value, not the placeholder
    assertEquals(
      r.audit.removedClaims.head.text,
      "Alice Arden has 30 distinct coauthors in window 2015-2026."
    )
    assertEquals(
      r.answer.claims.map(c => (c.claimId, c.text, c.verificationStatus)),
      Vector(
        (
          "claim-2",
          "UNVERIFIED interpretation: Alice Arden has 41 (distinctCoauthorCount of Alice Arden, window 2015-2026) distinct coauthors and Bob Brandt has 30 (distinctCoauthorCount of Bob Brandt, window 2015-2026) distinct coauthors in window 2015-2026.",
          PARTIALLY_SUPPORTED
        ),
        (
          "claim-2-figure-1",
          s"distinctCoauthorCount of Alice Arden ($Alice) in window 2015-2026: 41.",
          VERIFIED
        ),
        (
          "claim-2-figure-2",
          s"distinctCoauthorCount of Bob Brandt ($Bob) in window 2015-2026: 30.",
          VERIFIED
        )
      )
    )
    assertEquals(
      r.answer.claims.drop(1).map(_.evidenceIds),
      Vector(Vector(alice), Vector(bob)),
      "a structured claim cites only the item that holds its value"
    )
    // v3: the misattributed summary is free text, kept only as a labeled interpretation
    assertEquals(r.audit.summaryReplaced, None)
    assert(
      r.answer.summary
        .startsWith("UNVERIFIED interpretation: Alice Arden has 30 distinct coauthors."),
      r.answer.summary
    )
  }

  test(
    "R4-1 and R4-2: model prose is an UNVERIFIED interpretation; a true figure is its owner's claim"
  ) {
    val e = evidence(RankingQ)
    val (alice, bob) = (metricsEv(e, Alice), metricsEv(e, Bob))
    val both = Vector(alice, bob)
    // review-report-04: a partial name and a noun phrase standing for Alice, with Bob's 30
    val prose = Vector(
      "Unlike Bob Brandt, Arden has 30 distinct coauthors in window 2015-2026.",
      "Alice Arden leads. Unlike Bob Brandt, the leader has 30 distinct coauthors."
    )
    val llm = ScriptedLlm.replies(
      modelReply(
        "Alice Arden leads the ranking by distinct coauthors in window 2015-2026.",
        (prose(0), both, None, Vector.empty),
        (prose(1), both, None, Vector.empty),
        // the same phrasing with a tuple: Bob's true figure is Bob's structured claim
        (
          "Unlike Bob Brandt, Arden has {figure-1} distinct coauthors.",
          both,
          None,
          Vector(fig(Bob, "distinctCoauthorCount", "30", bob))
        ),
        // and as Alice's figure it conflicts with her record
        (
          "Unlike Bob Brandt, Arden has {figure-1} distinct coauthors.",
          both,
          None,
          Vector(fig(Alice, "distinctCoauthorCount", "30", alice, bob))
        )
      )
    )
    val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(r.synthesis.fallback, None, "the model reply was not used")
    val kept = r.answer.claims.map(c => c.claimId -> c).toMap
    assertEquals(
      r.answer.claims.map(c => (c.claimId, c.verificationStatus)),
      Vector(
        ("claim-1", PARTIALLY_SUPPORTED),
        ("claim-2", PARTIALLY_SUPPORTED),
        ("claim-3", PARTIALLY_SUPPORTED),
        ("claim-3-figure-1", VERIFIED)
      )
    )
    Vector("claim-1", "claim-2").zip(prose).foreach { (id, p) =>
      val c = kept(id)
      assertEquals(c.text, s"UNVERIFIED interpretation: $p", id)
      assert(
        c.uncertainty.exists(_.contains("UNVERIFIED interpretation: free text")),
        c.uncertainty.toString
      )
    }
    assertEquals(
      kept("claim-3").text,
      "UNVERIFIED interpretation: Unlike Bob Brandt, Arden has 30 (distinctCoauthorCount of Bob Brandt, window 2015-2026) distinct coauthors."
    )
    assertEquals(
      kept("claim-3-figure-1").text,
      s"distinctCoauthorCount of Bob Brandt ($Bob) in window 2015-2026: 30."
    )
    assertEquals(
      r.audit.removedClaims.map(c => (c.claimId, c.status)),
      Vector(("claim-4", CONFLICTING), ("claim-4-figure-1", CONFLICTING))
    )
    // every VERIFIED claim of a model answer is a structured claim; the answer is not VERIFIED
    assert(
      r.answer.claims
        .filter(_.verificationStatus == VERIFIED)
        .forall(_.claimId.contains("-figure-"))
    )
    assertEquals(r.answer.verification, CONFLICTING)
    assertEquals(r.audit.summaryReplaced, None)
    assert(
      r.answer.summary.startsWith(
        "UNVERIFIED interpretation: Alice Arden leads the ranking by distinct coauthors in window 2015-2026."
      ),
      r.answer.summary
    )
  }

  test("v3: 'leads the ranking' is VERIFIED only as a structured ranking claim") {
    val e = evidence(RankingQ)
    val rk = e.bundle.deterministic
      .find(_.values.get("metric").contains("DISTINCT_COAUTHOR_COUNT"))
      .getOrElse(fail("no distinct-coauthor ranking"))
      .evidenceId
    val t = "Carol Chen leads the ranking by distinct coauthors ({figure-1})."
    val llm = ScriptedLlm.replies(
      modelReply(
        "The ranking.",
        (t, Vector(rk), None, Vector(fig(Carol, "rank", "1", rk))),
        // Alice is second: the same tuple for her conflicts
        (
          "Alice Arden leads the ranking by distinct coauthors ({figure-1}).",
          Vector(rk),
          None,
          Vector(fig(Alice, "rank", "1", rk))
        ),
        // prose alone, false (Carol leads): an interpretation, never VERIFIED
        ("Alice Arden leads the ranking by distinct coauthors.", Vector(rk), None, Vector.empty)
      )
    )
    val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
    assertEquals(
      r.answer.claims.map(c => (c.claimId, c.verificationStatus)),
      Vector(
        ("claim-1", PARTIALLY_SUPPORTED),
        ("claim-1-figure-1", VERIFIED),
        ("claim-3", PARTIALLY_SUPPORTED)
      )
    )
    assertEquals(
      r.answer.claims(1).text,
      s"rank of Carol Chen ($Carol) by distinctCoauthorCount in window 2015-2026: 1."
    )
    assertEquals(r.answer.claims(1).evidenceIds, Vector(rk))
    assert(
      r.answer.claims(2).text.startsWith("UNVERIFIED interpretation: "),
      r.answer.claims(2).text
    )
    assertEquals(
      r.audit.removedClaims.map(c => (c.claimId, c.status)),
      Vector(("claim-2", CONFLICTING), ("claim-2-figure-1", CONFLICTING))
    )
  }

  test(
    "v3: model summaries and notes are labeled UNVERIFIED interpretation whatever they contain"
  ) {
    val e = evidence(RankingQ)
    val alice = metricsEv(e, Alice)
    val text = "Alice Arden has {figure-1} distinct coauthors in window 2015-2026."
    val figs = Vector(fig(Alice, "distinctCoauthorCount", "41", alice))
    // review-report-05 and 05b forms: no number, number word or rank phrase is looked for
    Vector(
      "Alice Arden (vs. Bob Brandt) has 30 distinct coauthors in window 2015-2026.",
      "Alice Arden is ranked with 30.",
      "Alice Arden has thirty distinct coauthors in window 2015-2026, ranking first.",
      "Alice Arden has ３０ distinct coauthors.",
      "Alice Arden ranks 3rd by distinct coauthors.",
      "Alice Arden has thirty and Bob Brandt twenty distinct coauthors in window 2015-2026.",
      "Alice Arden has the most distinct coauthors."
    ).foreach { summary =>
      val note = Some("Alice Arden may have three dozen coauthors.")
      val llm = ScriptedLlm.replies(modelReply(summary, (text, Vector(alice), note, figs)))
      val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
      assertEquals(r.synthesis.fallback, None, "the model reply was not used")
      assertEquals(
        r.answer.claims.map(c => (c.claimId, c.verificationStatus)),
        Vector(("claim-1", PARTIALLY_SUPPORTED), ("claim-1-figure-1", VERIFIED)),
        summary
      )
      assertEquals(r.audit.summaryReplaced, None, summary)
      assert(r.answer.summary.startsWith(s"UNVERIFIED interpretation: $summary"), r.answer.summary)
      val u = r.answer.claims.head.uncertainty.getOrElse("")
      assert(
        u.contains(
          "UNVERIFIED interpretation (uncertainty note): Alice Arden may have three dozen coauthors."
        ),
        u
      )
      assertEquals(r.answer.verification, PARTIALLY_SUPPORTED)
    }
    // a summary that contradicts the evidence or names someone outside it is replaced
    Vector(
      "Carl Unknown leads in window 2015-2026.",
      "Alice Arden has many coauthors in window 1990-1994."
    ).foreach { summary =>
      val llm = ScriptedLlm.replies(modelReply(summary, (text, Vector(alice), None, figs)))
      val (r, _, _) = run(RankingQ, synth = new LlmSynthesizer(llm, prompt, w.cfg))
      assert(r.audit.summaryReplaced.nonEmpty, summary)
      assert(!r.answer.summary.contains(summary), r.answer.summary)
      assert(!r.answer.summary.startsWith("UNVERIFIED"), r.answer.summary)
    }
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
