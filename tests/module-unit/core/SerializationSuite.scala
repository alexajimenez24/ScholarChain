package scholarchain.core

import upickle.default.{Reader, Writer, read, write}
import Fixtures.*

/** Frozen wire examples: one JSON file per record in resources/wire. A change to a record's JSON
  * shape fails here and needs a new schema version and a new frozen file in the same change.
  */
class SerializationSuite extends munit.FunSuite:
  private def frozen(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(s"wire/$name.json")
    assert(in != null, s"missing frozen example wire/$name.json")
    try new String(in.readAllBytes(), "UTF-8")
    finally in.close()

  private def freeze[T: Reader: Writer](name: String, value: T, validate: T => Validate.Errors) =
    test(s"frozen wire example: $name") {
      val json = frozen(name)
      assertEquals(write(value), json, "serialization drifted from the frozen example")
      val decoded = read[T](json)
      assertEquals(decoded, value)
      assertEquals(validate(decoded), Vector.empty[String])
      assertEquals(JsonLines.decodeValid[T](json, validate), Right(value))
    }

  freeze("work", work, Validate.work)
  freeze("author-mention", mentionRecordA, Validate.mention)
  freeze("researcher", researcher, Validate.researcher)
  freeze("authorship", authorship, Validate.authorship)
  freeze("publication-team", team, Validate.team)
  freeze("coauthor-pair", pair, Validate.pair)
  freeze("coauthor-edge", edge, Validate.edge)
  freeze("researcher-metrics", metrics, Validate.metrics)
  freeze("venue", venue, Validate.venue)
  freeze("institution", institution, Validate.institution)
  freeze("topic", topic, Validate.topic)
  freeze("match-decision", decision, Validate.decision)
  freeze("graph-node", graphNode, Validate.graphNode)
  freeze("graph-edge", graphEdge, Validate.graphEdge)
  freeze("evidence-item", evidence, Validate.evidence)
  freeze("evidence-bundle", bundle, Validate.bundle)
  freeze("query-plan", queryPlan, Validate.queryPlan)
  freeze("answer-claim", claim, Validate.claim)
  freeze("answer-response", answer, Validate.answer)

  test("the frozen examples carry every supported schema version exactly once where versioned") {
    val carried = Vector(
      work.schemaVersion,
      mentionRecordA.provenance.schemaVersion,
      researcher.schemaVersion,
      authorship.provenance.schemaVersion,
      team.provenance.schemaVersion,
      pair.provenance.schemaVersion,
      edge.schemaVersion,
      metrics.schemaVersion,
      venue.provenance.schemaVersion,
      institution.provenance.schemaVersion,
      topic.provenance.schemaVersion,
      decision.schemaVersion,
      graphNode.provenance.schemaVersion,
      graphEdge.provenance.schemaVersion,
      evidence.schemaVersion,
      bundle.schemaVersion,
      queryPlan.schemaVersion,
      answer.schemaVersion
    )
    assertEquals(carried.toSet, SchemaVersions.Supported)
    assertEquals(carried.size, carried.distinct.size)
  }
  test("work records round-trip through JSON lines") {
    assertEquals(JsonLines.decode[WorkRecord](JsonLines.encode(work)), Right(work))
  }
  test("enums serialize as their case names") {
    assertEquals(write(MatchOutcome.UNRESOLVED), "\"UNRESOLVED\"")
    assertEquals(
      read[VerificationStatus]("\"PARTIALLY_SUPPORTED\""),
      VerificationStatus.PARTIALLY_SUPPORTED
    )
    assertEquals(write(SourceType.OPENALEX), "\"OPENALEX\"")
    assertEquals(read[EvidenceType]("\"GRAPH_PATH\""), EvidenceType.GRAPH_PATH)
    assertEquals(read[CollaborationMetric]("\"CORENESS\""), CollaborationMetric.CORENESS)
    assertEquals(read[QueryOperation]("\"SHORTEST_PATH\""), QueryOperation.SHORTEST_PATH)
  }
  test("the enum case sets are frozen") {
    assertEquals(
      SourceType.values.map(_.toString).toSeq,
      Seq("DBLP", "OPENALEX", "ORCID", "CROSSREF", "SCHOLARCHAIN")
    )
    assertEquals(
      MatchOutcome.values.map(_.toString).toSeq,
      Seq("ACCEPTED", "REJECTED", "UNRESOLVED", "CONFLICT")
    )
    assertEquals(
      VerificationStatus.values.map(_.toString).toSeq,
      Seq("VERIFIED", "PARTIALLY_SUPPORTED", "UNSUPPORTED", "CONFLICTING")
    )
    assertEquals(EvidenceType.values.length, 9)
    assertEquals(CollaborationMetric.values.length, 10)
    assertEquals(QueryOperation.values.length, 11)
  }
  test("an unknown enum value or a missing provenance field does not decode") {
    assert(JsonLines.decode[SourceType]("\"MEDLINE\"").isLeft)
    val noProvenance = frozen("work").replaceFirst(",\"provenance\":\\{.*\\}\\}$", "}")
    assert(JsonLines.decode[WorkRecord](noProvenance).isLeft)
  }
  private def nullField(json: String, field: String): String =
    val v = ujson.read(json)
    v.obj(field) = ujson.Null
    ujson.write(v)

  test("a JSON null in a required field is a Left, never an exception") {
    def left[T: Reader: Writer](json: String, v: T => Validate.Errors) =
      assert(JsonLines.decode[T](json).isLeft, json)
      assert(JsonLines.decodeValid[T](json, v).isLeft, json)
    val w = frozen("work")
    left[WorkRecord](nullField(w, "provenance"), Validate.work)
    left[WorkRecord](nullField(w, "authors"), Validate.work)
    left[WorkRecord](nullField(w, "title"), Validate.work)
    val p = ujson.read(w)
    p("provenance")("sourceRecordId") = ujson.Null
    left[WorkRecord](ujson.write(p), Validate.work)
    val m = ujson.read(w)
    m("authors")(0)("provenance") = ujson.Null
    left[WorkRecord](ujson.write(m), Validate.work)
    left[CoauthorEdge](nullField(frozen("coauthor-edge"), "researcherA"), Validate.edge)
    left[AuthorMention](nullField(frozen("author-mention"), "provenance"), Validate.mention)
  }
  test("a validator that throws is a Left from decodeValid") {
    val boom: WorkRecord => Validate.Errors = _ => throw new IllegalStateException("boom")
    assert(JsonLines.decodeValid[WorkRecord](frozen("work"), boom).isLeft)
  }
  test("unknown fields and a fractional year are rejected, not ignored or truncated") {
    val w = ujson.read(frozen("work"))
    w("extra") = "x"
    assert(JsonLines.decode[WorkRecord](ujson.write(w)).left.exists(_.contains("unknown field")))
    val frac = frozen("work").replace("\"year\":2021", "\"year\":2021.7")
    assert(frac != frozen("work"))
    assert(JsonLines.decode[WorkRecord](frac).isLeft)
    assert(JsonLines.decode[WorkRecord](frozen("work")).isRight)
  }
  test("malformed JSON is reported, not thrown") {
    assert(JsonLines.decode[WorkRecord]("{not json").isLeft)
    assert(
      JsonLines
        .decodeValid[WorkRecord](JsonLines.encode(work.copy(title = " ")), Validate.work)
        .isLeft
    )
  }
  test("reject records bound their size") {
    val r = RejectRecord.of("stage", "r" * 900, "i" * 5000, runId)
    assertEquals(r.reason.length, 500)
    assertEquals(r.input.length, 2000)
  }
