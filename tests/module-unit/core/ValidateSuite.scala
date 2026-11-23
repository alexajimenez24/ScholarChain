package scholarchain.core

import Fixtures.*

class ValidateSuite extends munit.FunSuite:
  private def noErrors(errs: Validate.Errors) = assertEquals(errs, Vector.empty[String])

  test("every valid fixture passes") {
    noErrors(Validate.work(work))
    noErrors(Validate.mention(mentionRecordA))
    noErrors(Validate.decision(decision))
    noErrors(Validate.researcher(researcher))
    noErrors(Validate.edge(edge))
    noErrors(Validate.pair(pair))
    noErrors(Validate.metrics(metrics))
    noErrors(Validate.graphEdge(graphEdge))
  }
  test("time windows whose start exceeds the end are rejected, everywhere they occur") {
    val bad = TimeWindow(2020, 2010)
    assert(Validate.window(bad).exists(_.contains("exceeds")))
    assert(Validate.window(TimeWindow(1800, 2000)).nonEmpty)
    assert(Validate.queryPlan(queryPlan.copy(window = bad)).exists(_.contains("exceeds")))
    assert(Validate.answer(answer.copy(window = bad)).exists(_.contains("exceeds")))
    assert(Validate.metrics(metrics.copy(window = bad)).exists(_.contains("exceeds")))
    assert(Validate.edge(edge.copy(window = bad)).exists(_.contains("exceeds")))
  }
  test("confidence outside [0,1] or NaN is rejected") {
    assert(Validate.confidence(1.01).nonEmpty)
    assert(Validate.confidence(-0.1).nonEmpty)
    assert(Validate.confidence(Double.NaN).nonEmpty)
    assert(Validate.confidence(0.0).isEmpty && Validate.confidence(1.0).isEmpty)
    assert(Validate.decision(decision.copy(confidence = 1.5)).exists(_.contains("outside")))
    assert(
      Validate.researcher(researcher.copy(identityConfidence = -1)).exists(_.contains("outside"))
    )
  }
  test("missing provenance fields are rejected") {
    val bad = work.copy(provenance = work.provenance.copy(sourceRecordId = "", runId = "nope"))
    val errs = Validate.work(bad)
    assert(errs.exists(_.contains("sourceRecordId")))
    assert(errs.exists(_.contains("runId")))
    assert(
      Validate.venue(venue.copy(provenance = venue.provenance.copy(snapshot = " "))).nonEmpty
    )
    assert(
      Validate
        .graphNode(graphNode.copy(provenance = graphNode.provenance.copy(sourceRecordId = "")))
        .nonEmpty
    )
  }
  test("unsupported or mismatched schema versions are rejected") {
    assert(Validate.work(work.copy(schemaVersion = "work-v0")).exists(_.contains("unsupported")))
    assert(Validate.work(work.copy(schemaVersion = "work-v2")).exists(_.contains("unsupported")))
    assert(
      Validate
        .work(work.copy(schemaVersion = SchemaVersions.Edge))
        .exists(_.contains("is required"))
    )
    val wrongProv = work.copy(provenance = work.provenance.copy(schemaVersion = "work-v9"))
    assert(Validate.work(wrongProv).exists(_.contains("unsupported")))
    assert(
      Validate.pair(pair.copy(provenance = pair.provenance.copy(schemaVersion = "bogus"))).nonEmpty
    )
  }
  test("reversed undirected pair ordering is rejected") {
    val reversed = edge.copy(researcherA = edge.researcherB, researcherB = edge.researcherA)
    assert(Validate.edge(reversed).exists(_.contains("canonically ordered")))
    val reversedPair = pair.copy(mentionA = pair.mentionB, mentionB = pair.mentionA)
    assert(Validate.pair(reversedPair).exists(_.contains("canonically ordered")))
    val reversedGraph = graphEdge.copy(fromId = graphEdge.toId, toId = graphEdge.fromId)
    assert(Validate.graphEdge(reversedGraph).exists(_.contains("canonically ordered")))
    assert(Validate.pair(pair.copy(mentionB = pair.mentionA)).nonEmpty, "equal ends")
  }
  test("a directed edge type does not need ordered endpoints") {
    val authored = graphEdge.copy(
      edgeType = GraphLabels.Authored,
      fromId = graphEdge.toId,
      toId = workId,
      edgeId = Ids.graphEdge(GraphLabels.Authored, graphEdge.toId, workId, "p", "e")
    )
    noErrors(Validate.graphEdge(authored))
  }
  test("an AUTHORED edge from a work ID to a researcher ID is rejected") {
    val reversed = graphEdge.copy(
      edgeType = GraphLabels.Authored,
      fromId = workId,
      toId = researcherA,
      edgeId = Ids.graphEdge(GraphLabels.Authored, workId, researcherA, "p", "e")
    )
    assert(
      Validate.graphEdge(reversed).exists(_.contains("must run from a researcher or mention ID"))
    )
  }
  test("edge years outside its window, malformed IDs and a foreign edge ID are rejected") {
    assert(
      Validate.edge(edge.copy(firstCollaborationYear = 2001)).exists(_.contains("outside window"))
    )
    assert(Validate.edge(edge.copy(researcherA = "bob")).exists(_.contains("malformed researcher")))
    assert(Validate.edge(edge.copy(edgeId = "e1")).exists(_.contains("malformed edge")))
    val other =
      Ids.coauthorEdge(edge.researcherA, edge.researcherB, TimeWindow(2000, 2009), "graph-v1")
    assert(Validate.edge(edge.copy(edgeId = other)).exists(_.contains("does not match")))
  }
  test("malformed work, mention, evidence, chunk, run and decision IDs are rejected") {
    assert(Validate.work(work.copy(workId = "no namespace")).exists(_.contains("malformed work")))
    val badMention = work.authors.head.copy(mentionId = "m:xyz")
    assert(
      Validate
        .work(work.copy(authors = work.authors.updated(0, badMention)))
        .exists(_.contains("malformed mention"))
    )
    assert(
      Validate
        .evidence(evidence.copy(evidenceId = "ev:x:1"))
        .exists(_.contains("malformed evidence"))
    )
    assert(Validate.evidence(evidence.copy(chunkId = Some("c:zz"))).exists(_.contains("chunk")))
    assert(
      Validate.decision(decision.copy(decisionId = "d1")).exists(_.contains("malformed decision"))
    )
    assert(
      Validate
        .work(work.copy(provenance = work.provenance.copy(runId = "run-xyz")))
        .exists(_.contains("runId"))
    )
    assert(Validate.bundle(bundle.copy(graphRunId = "r")).exists(_.contains("graph run")))
  }
  test("author positions must be contiguous") {
    val shuffled = work.copy(authors = work.authors.reverse)
    assert(Validate.work(shuffled).exists(_.contains("positions")))
  }
  test("evidence text must match its hash") {
    noErrors(Validate.evidence(evidence))
    assert(
      Validate.evidence(evidence.copy(text = Some("tampered"))).exists(_.contains("hash mismatch"))
    )
  }
  test("LLM decisions need prompt and model versions") {
    val llm = decision.copy(path = MatchPath.LLM_ADJUDICATED)
    assert(Validate.decision(llm).exists(_.contains("prompt and model")))
    noErrors(Validate.decision(llm.copy(promptVersion = Some("p1"), modelVersion = Some("m1"))))
  }
  test("metrics invariants and bundles with repeated evidence are rejected") {
    assert(
      Validate.metrics(metrics.copy(distinctCoauthorCount = 99)).exists(_.contains("C_r > T_r"))
    )
    assert(Validate.bundle(bundle.copy(identity = Vector(evidence))).exists(_.contains("repeats")))
  }
  test("answers: abstention needs a reason, claims need evidence, claim links must resolve") {
    assert(Validate.answer(answer.copy(abstained = true)).exists(_.contains("abstains")))
    noErrors(Validate.answer(answer.copy(abstained = true, abstentionReason = Some("no evidence"))))
    assert(
      Validate.claim(claim.copy(evidenceIds = Vector.empty)).exists(_.contains("without evidence"))
    )
    noErrors(
      Validate.claim(
        claim.copy(evidenceIds = Vector.empty, verificationStatus = VerificationStatus.UNSUPPORTED)
      )
    )
    assert(
      Validate
        .answer(answer.copy(claims = Vector.empty))
        .exists(_.contains("claim it does not contain"))
    )
  }
  test("query plans need a question, an operation and positive limits") {
    assert(Validate.queryPlan(queryPlan.copy(question = " ")).nonEmpty)
    assert(Validate.queryPlan(queryPlan.copy(operations = Vector.empty)).nonEmpty)
    assert(Validate.queryPlan(queryPlan.copy(limit = 0)).nonEmpty)
    assert(Validate.queryPlan(queryPlan.copy(maxPathDepth = 0)).nonEmpty)
  }
  test("authorship and team sizes must agree") {
    noErrors(Validate.authorship(authorship))
    noErrors(Validate.team(team))
    assert(Validate.team(team.copy(teamSize = 5)).nonEmpty)
    assert(Validate.authorship(authorship.copy(position = 2)).nonEmpty)
  }

  test("a mention without provenance data, or with the wrong schema version, is rejected") {
    val m = mentionRecordA
    assert(Validate.mention(m.copy(provenance = m.provenance.copy(sourceRecordId = ""))).nonEmpty)
    assert(
      Validate
        .mention(m.copy(provenance = m.provenance.copy(schemaVersion = SchemaVersions.Work)))
        .exists(_.contains("is required"))
    )
    assert(Validate.mention(m.copy(provenance = m.provenance.copy(runId = "x"))).nonEmpty)
    val badMention = m.copy(provenance = m.provenance.copy(snapshot = ""))
    assert(Validate.work(work.copy(authors = Vector(badMention))).exists(_.contains("snapshot")))
  }
  test("a mention ID is recomputed from work and position") {
    val forged = mentionRecordA.copy(mentionId = Ids.mention(workId, 7))
    assert(Validate.mention(forged).exists(_.contains("does not match")))
    assert(Validate.mention(mentionRecordA.copy(position = -1)).exists(_.contains("negative")))
    // the ID is consistent with the bad work ID, so only the work-ID shape check can reject it
    val shaped = mentionRecordA.copy(workId = "bad id", mentionId = Ids.mention("bad id", 0))
    assert(Validate.mention(shaped).exists(_.contains("malformed work ID")))
  }
  test("two authors of one work cannot share a mention ID") {
    val dup = work.copy(authors = Vector(mentionRecordA, mentionRecordA.copy(position = 1)))
    assert(Validate.work(dup).exists(_.contains("share a mention ID")))
  }
  test("graph edge: tag of another type, bad endpoint shapes and non-ID endpoints are rejected") {
    val foreignTag = graphEdge.copy(
      edgeId = Ids.graphEdge(GraphLabels.Authored, lowResearcher, highResearcher, "graph-v1", "w")
    )
    assert(Validate.graphEdge(foreignTag).exists(_.contains("another edge type")))
    assert(
      Validate
        .graphEdge(
          graphEdge.copy(
            fromId = "x",
            toId = "y",
            edgeId = Ids.graphEdge(GraphLabels.CoauthoredWith, "x", "y", "p", "e")
          )
        )
        .exists(_.contains("researcher IDs"))
    )
    assert(Validate.graphEdge(graphEdge.copy(toId = "a b")).exists(_.contains("whitespace")))
    val resolves = graphEdge.copy(
      edgeType = GraphLabels.ResolvesTo,
      fromId = researcherA,
      toId = mentionA,
      edgeId = Ids.graphEdge(GraphLabels.ResolvesTo, researcherA, mentionA, "p", "e")
    )
    assert(Validate.graphEdge(resolves).exists(_.contains("mention ID to a researcher")))
  }
  test("match decisions: recomputed ID, endpoint shapes and policy are checked") {
    val other = decision.copy(decisionId = Ids.decision(EntityKind.RESEARCHER, "a", "b", "p"))
    assert(Validate.decision(other).exists(_.contains("does not match")))
    assert(
      Validate
        .decision(decision.copy(sourceId = "bob"))
        .exists(_.contains("malformed RESEARCHER source"))
    )
    assert(
      Validate
        .decision(decision.copy(kind = EntityKind.WORK, sourceId = mentionA))
        .exists(_.contains("malformed WORK source"))
    )
    assert(Validate.decision(decision.copy(policyVersion = " ")).exists(_.contains("policy")))
    assert(Validate.decision(decision.copy(targetId = decision.sourceId)).nonEmpty, "self link")
  }
  test("representative and team work IDs must be work IDs") {
    assert(
      Validate
        .edge(edge.copy(representativeWorkIds = Vector("nope")))
        .exists(_.contains("malformed work ID"))
    )
    assert(
      Validate
        .metrics(metrics.copy(maximumTeamWorkIds = Vector("nope")))
        .exists(_.contains("malformed work ID"))
    )
  }
  test("metrics counts must be non-negative and finite") {
    assert(
      Validate.metrics(metrics.copy(distinctCoauthorCount = -1)).exists(_.contains("negative"))
    )
    assert(Validate.metrics(metrics.copy(totalCoauthorOccurrences = -1)).nonEmpty)
    assert(Validate.metrics(metrics.copy(maximumCoauthorsOnOnePaper = -1)).nonEmpty)
    assert(Validate.metrics(metrics.copy(componentSize = -1)).nonEmpty)
    assert(Validate.metrics(metrics.copy(crossCommunityDegree = Some(-1))).nonEmpty)
    assert(Validate.metrics(metrics.copy(coreness = Some(-1))).nonEmpty)
    assert(Validate.metrics(metrics.copy(weightedDegree = -0.5)).nonEmpty)
    assert(Validate.metrics(metrics.copy(averageCoauthorsPerPaper = Double.NaN)).nonEmpty)
    assert(Validate.metrics(metrics.copy(bridgeScore = Some(Double.PositiveInfinity))).nonEmpty)
  }
  test("provenance.snapshot must be a calendar date YYYY-MM-DD") {
    List("garbage", "2026-13-01", "2026-02-30", "20261101", "2026-1-1", "").foreach { bad =>
      val w = work.copy(provenance = work.provenance.copy(snapshot = bad))
      assert(Validate.work(w).exists(_.contains("YYYY-MM-DD")), bad)
    }
    noErrors(Validate.work(work.copy(provenance = work.provenance.copy(snapshot = "2024-02-29"))))
  }
