package scholarchain.core

import Fixtures.*

class IdsSuite extends munit.FunSuite:
  test("frozen stable ID values (formula drift breaks this test)") {
    assertEquals(workId, "dblp:conf/test/A21")
    assertEquals(mentionA, "m:8a86a56410344f5199a5")
    assertEquals(researcherA, "sc:r:v1:5da9c17f5484590a56b0")
    assertEquals(runId, "run-7c97c16f285cc5f8")
    assertEquals(edge.edgeId, "e:co:f5654bfb073eb4ce95d6c5a0")
    assertEquals(graphEdge.edgeId, "e:coauthored_with:f64ba9bf0af0ece3d6fce277")
    assertEquals(evidence.evidenceId, "ev:metric_record:7f701a6ae74ea72f")
    assertEquals(decision.decisionId, "md:researcher:d430d3dc214c7b72de1d")
  }
  test("stable IDs are deterministic and well-formed") {
    val w = Ids.work("dblp", "journals/x/Y20")
    assertEquals(w, "dblp:journals/x/Y20")
    assertEquals(Ids.mention(w, 2), Ids.mention(w, 2))
    assertNotEquals(Ids.mention(w, 2), Ids.mention(w, 3))
    assert(Ids.isMentionId(Ids.mention(w, 0)))
    val r = Ids.researcherFromMentions(Vector("m:b", "m:a"))
    assertEquals(r, Ids.researcherFromMentions(Vector("m:a", "m:b")), "order independent")
    assert(Ids.isResearcherId(r))
    assertEquals(Ids.researcherFromOrcid("0000-0002-1825-0097"), Some("orcid:0000-0002-1825-0097"))
    assertEquals(Ids.researcherFromOrcid("0000-0002-1825-0098"), None, "bad check digit")
    assert(Ids.isRunId(Ids.run("{}")))
    assert(Ids.isEvidenceId(evidence.evidenceId))
    assert(Ids.isDecisionId(decision.decisionId))
    assert(Ids.isChunkId(Ids.chunk("r1", "v1", "profile", 0, "a")))
  }
  test("run IDs ignore key order and whitespace, and change with content") {
    assertEquals(
      Ids.run("""{"a":1,"b":{"y":2,"x":3}}"""),
      Ids.run("""{ "b": {"x":3,"y":2}, "a":1 }""")
    )
    assertNotEquals(Ids.run("""{"a":1}"""), Ids.run("""{"a":2}"""))
    intercept[IllegalArgumentException](Ids.run("not json"))
  }
  test("undirected pairs canonicalize and reject self pairs") {
    assertEquals(Ids.orderedPair("b", "a"), ("a", "b"))
    assertEquals(Ids.orderedPair("a", "b"), ("a", "b"))
    val win = TimeWindow(2015, 2026)
    assertEquals(Ids.coauthorEdge("x", "y", win, "p"), Ids.coauthorEdge("y", "x", win, "p"))
    assertNotEquals(
      Ids.coauthorEdge("x", "y", win, "p"),
      Ids.coauthorEdge("x", "y", TimeWindow(2000, 2009), "p")
    )
    assertNotEquals(Ids.coauthorEdge("x", "y", win, "p"), Ids.coauthorEdge("x", "y", win, "q"))
    assert(Ids.isEdgeId(Ids.coauthorEdge("x", "y", win, "p")))
    intercept[IllegalArgumentException](Ids.orderedPair("a", "a"))
  }
  test("undirected graph edges ignore endpoint order; directed ones do not") {
    val u1 = Ids.graphEdge(GraphLabels.CoauthoredWith, "a", "b", "p", "e")
    assertEquals(u1, Ids.graphEdge(GraphLabels.CoauthoredWith, "b", "a", "p", "e"))
    val d1 = Ids.graphEdge(GraphLabels.Authored, "a", "b", "p", "e")
    assertNotEquals(d1, Ids.graphEdge(GraphLabels.Authored, "b", "a", "p", "e"))
    assert(Ids.isEdgeId(u1) && Ids.isEdgeId(d1))
  }
  test("match decision IDs ignore source and target order") {
    assertEquals(
      Ids.decision(EntityKind.WORK, "a", "b", "p"),
      Ids.decision(EntityKind.WORK, "b", "a", "p")
    )
    assertNotEquals(
      Ids.decision(EntityKind.WORK, "a", "b", "p"),
      Ids.decision(EntityKind.RESEARCHER, "a", "b", "p")
    )
  }
  test("work IDs reject blank or whitespace source IDs and bad namespaces") {
    intercept[IllegalArgumentException](Ids.work("dblp", "has space"))
    intercept[IllegalArgumentException](Ids.work("DBLP", "x"))
  }
  test("chunk IDs change when the text changes") {
    assertNotEquals(
      Ids.chunk("r1", "v1", "profile", 0, "a"),
      Ids.chunk("r1", "v1", "profile", 0, "b")
    )
  }
  test("no stable ID depends on the clock") {
    val first = (Ids.run("""{"k":1}"""), Ids.mention(workId, 0), edge.edgeId)
    Thread.sleep(5)
    assertEquals((Ids.run("""{"k":1}"""), Ids.mention(workId, 0), edge.edgeId), first)
  }
  test("run IDs distinguish what they can and reject what they cannot represent") {
    assertEquals(Ids.run("""{"a":9007199254740991}"""), Ids.run("""{"a":9007199254740991}"""))
    assertNotEquals(Ids.run("""{"a":9007199254740991}"""), Ids.run("""{"a":9007199254740990}"""))
    intercept[IllegalArgumentException](Ids.run("""{"a":9007199254740993}"""))
    intercept[IllegalArgumentException](Ids.run("""{"a":[1,{"b":-9007199254740992}]}"""))
    intercept[IllegalArgumentException](Ids.run("""{"a":1e300}"""))
    assertEquals(Ids.run("""{"a":"9007199254740993"}"""), Ids.run("""{"a":"9007199254740993"}"""))
    assertNotEquals(
      Ids.run("""{"a":"9007199254740993"}"""),
      Ids.run("""{"a":"9007199254740992"}""")
    )
  }
  test("Ids.work enforces the length bound isWorkId checks") {
    assert(Ids.isWorkId(Ids.work("dblp", "x" * 400)))
    intercept[IllegalArgumentException](Ids.work("dblp", "x" * 401))
    intercept[IllegalArgumentException](Ids.work("dblp", "a\u2003b"))
  }
  test("a researcher ID names its exact mention set (stable within a run and policy)") {
    val one = Ids.researcherFromMentions(Vector(mentionA))
    assertNotEquals(one, Ids.researcherFromMentions(Vector(mentionA, mentionB)))
    assertEquals(one, Ids.researcherFromMentions(Vector(mentionA)))
  }
