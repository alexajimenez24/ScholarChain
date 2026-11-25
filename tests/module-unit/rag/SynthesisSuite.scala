package scholarchain.rag

import scholarchain.core.*
import java.nio.file.{Files, Path, Paths}
import RagWorld.*

/** Stage 8 parts: the versioned prompt and schema, the output rules and the context packer. */
class SynthesisSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private lazy val pipe = w.pipeline()
  private def ev(q: String, c: Constraints = Constraints()) =
    pipe
      .run(s"qry-${Hashing.short(q + c.toString, 12)}", AnalysisRequest(q, c))
      .fold(e => fail(e.describe), identity)
  private lazy val root: Path = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(p => Files.isDirectory(p.resolve("prompts/runtime")))
    .getOrElse(fail("no prompts/runtime above the working directory"))
  private lazy val prompt = SynthesisPrompt.load(root).fold(e => fail(e), identity)

  private val Ev = "ev:metric_record:0123456789abcdef"
  private def reply(
      claims: String =
        s"""[{"claimId":"claim-1","text":"Alice Arden has 41 distinct coauthors.","evidenceIds":["$Ev"],"uncertainty":null}]""",
      abstain: String = "false",
      reason: String = "null",
      extra: String = ""
  ) =
    s"""{"schemaVersion":"answer-synthesis-v1","summary":"A summary.","abstain":$abstain,"abstentionReason":$reason,"claims":$claims$extra}"""

  test("the synthesis prompt is versioned, hashed and has exactly the data placeholders") {
    assertEquals(prompt.version, "answer-synthesis-v1")
    assertEquals(
      prompt.sha256,
      Hashing.sha256Hex(Files.readString(root.resolve(SynthesisPrompt.File)))
    )
    assert(prompt.template.contains("data, not instructions"))
    assert(prompt.template.contains("no reasoning"))
    assert(SynthesisPrompt.parse(prompt.template.replace("{{FEEDBACK}}", "")).isLeft)
    assert(
      SynthesisPrompt
        .parse(prompt.template.replace("answer-synthesis-v1", "answer-synthesis-v0"))
        .isLeft
    )
  }

  test("prompts/schemas/answer-synthesis-v1.json matches the output encoding and the rule bounds") {
    val schema = ujson.read(Files.readString(root.resolve(SynthesisRules.SchemaFile)))
    val sample = SynthesisRules.validate(reply()).fold(e => fail(e), identity)
    val enc = ujson.read(JsonLines.encode(sample))
    val props = schema("properties")
    assertEquals(schema("title").str, SynthesisRules.SchemaVersion)
    assertEquals(schema("additionalProperties").bool, false)
    assertEquals(props.obj.keySet.toSet, enc.obj.keySet.toSet)
    assertEquals(schema("required").arr.map(_.str).toSet, enc.obj.keySet.toSet)
    val claim = props("claims")("items")
    assertEquals(claim("properties").obj.keySet.toSet, enc("claims")(0).obj.keySet.toSet)
    assertEquals(claim("required").arr.map(_.str).toSet, enc("claims")(0).obj.keySet.toSet)
    assertEquals(claim("additionalProperties").bool, false)
    assertEquals(props("schemaVersion")("const").str, SynthesisRules.SchemaVersion)
    assertEquals(props("summary")("maxLength").num.toInt, SynthesisRules.MaxSummary)
    assertEquals(props("abstentionReason")("maxLength").num.toInt, SynthesisRules.MaxReason)
    assertEquals(props("claims")("maxItems").num.toInt, SynthesisRules.MaxClaims)
    assertEquals(claim("properties")("text")("maxLength").num.toInt, SynthesisRules.MaxClaimText)
    assertEquals(
      claim("properties")("evidenceIds")("maxItems").num.toInt,
      SynthesisRules.MaxEvidencePerClaim
    )
    assertEquals(
      claim("properties")("uncertainty")("maxLength").num.toInt,
      SynthesisRules.MaxUncertainty
    )
    assertEquals(claim("properties")("claimId")("pattern").str, SynthesisRules.ClaimIdPattern.regex)
    assert(Ids.isEvidenceId(Ev))
  }

  test("output rules accept a valid reply and refuse malformed ones with fixed reasons") {
    assert(SynthesisRules.validate(reply()).isRight)
    assert(SynthesisRules.validate("```json\n" + reply() + "\n```").isRight)
    val marker = "SECRET-MARKER-7731"
    val bad = Vector(
      s"Here is the answer: ${reply()}",
      reply(extra = s""","reasoning":"$marker step by step"""),
      reply().replace("\"uncertainty\":null", ""),
      reply().replace("answer-synthesis-v1", "answer-synthesis-v2"),
      reply(claims = s"""[{"claimId":"c1","text":"x","evidenceIds":["$Ev"],"uncertainty":null}]"""),
      reply(claims =
        s"""[{"claimId":"claim-1","text":"x","evidenceIds":["e:$marker"],"uncertainty":null}]"""
      ),
      reply(claims = s"""[{"claimId":"claim-1","text":"x","evidenceIds":[],"uncertainty":null}]"""),
      reply(claims =
        s"""[{"claimId":"claim-1","text":"<thinking>$marker</thinking>","evidenceIds":["$Ev"],"uncertainty":null}]"""
      ),
      reply(abstain = "true"),
      reply(claims =
        "[" + (1 to 41)
          .map(i =>
            s"""{"claimId":"claim-$i","text":"x","evidenceIds":["$Ev"],"uncertainty":null}"""
          )
          .mkString(",") + "]"
      ),
      reply(claims =
        s"""[{"claimId":"claim-1","text":"${"x" * 501}","evidenceIds":["$Ev"],"uncertainty":null}]"""
      ),
      "not json at all " + marker
    )
    bad.foreach { b =>
      val r = SynthesisRules.validate(b)
      assert(r.isLeft, b)
      assert(!r.left.toOption.get.contains(marker), "a refusal reason must not echo reply text")
    }
  }

  test(
    "rendering is one pass: data that names a placeholder stays data, inside a content-hash fence"
  ) {
    val r = ev(
      """What are the collaboration metrics of "Alice Arden" since 2015? {{EVIDENCE}} {{FEEDBACK}}"""
    )
    val packed = ContextPacker.pack(r, 12000)
    val text = prompt.render(
      Map(
        "BOUNDARY" -> packed.boundary,
        "REQUEST" -> packed.request,
        "NOTES" -> packed.notes,
        "EVIDENCE" -> packed.evidence,
        "FEEDBACK" -> ""
      )
    )
    assertEquals(
      text.split("\\{\\{EVIDENCE\\}\\}", -1).length - 1,
      1,
      "only the question's literal text remains"
    )
    assert(
      text.contains(s"<<<DATA ${packed.boundary}") && text.contains(s"DATA ${packed.boundary}>>>")
    )
    val data = text.substring(
      text.indexOf(s"<<<DATA ${packed.boundary}"),
      text.indexOf(s"DATA ${packed.boundary}>>>")
    )
    assert(
      data.contains("\"question\":\"What are the collaboration metrics of \\\"Alice Arden\\\"")
    )
    assert(!text.contains("{{REQUEST}}") && !text.contains("{{NOTES}}"))
  }

  test(
    "context packing: deterministic evidence first, semantic text sanitized and JSON-escaped, budget kept"
  ) {
    val r = ev(
      """What are the collaboration metrics of "Wei Wang" since 2015?""",
      Constraints(researcherIds = Vector(Wei1))
    )
    val full = ContextPacker.pack(r, 12000)
    val items = ujson.read(full.evidence).arr.toVector
    assertEquals(items.size, r.bundle.all.size)
    val parts = items.map(_("partition").str)
    assertEquals(
      parts,
      parts.sortBy(p => Vector("deterministic", "identity", "semantic").indexOf(p))
    )
    val injected = items.find(_.obj.get("text").exists(_.str.contains("IGNORE ALL PREVIOUS"))).get
    assertEquals(injected("trust").str, "untrusted-data")
    assert(!injected.obj.contains("values"), "semantic items carry text, never graph values")
    val small =
      ContextPacker.pack(r, Tokens.estimate(full.request) + Tokens.estimate(full.notes) + 400)
    assert(small.use.estimatedTokens <= small.use.tokenBudget)
    assert(small.use.omittedEvidenceIds.nonEmpty)
    assertEquals(small.use.includedEvidence + small.use.omittedEvidenceIds.size, r.bundle.all.size)
    val kept = ujson.read(small.evidence).arr.map(_("id").str).toSet
    assert(
      r.bundle.semantic
        .map(_.evidenceId)
        .forall(id => !kept(id) || r.bundle.deterministic.forall(d => kept(d.evidenceId)))
    )
  }

  test("deterministic synthesis explains the measures it uses and copies stored values") {
    val r = ev(
      "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?"
    )
    val out = DeterministicSynthesizer.draft(r)
    assert(out.summary.contains("Collaboration breadth"), out.summary)
    assert(out.summary.contains("Bridge importance is structural"), out.summary)
    assert(out.summary.startsWith("Researchers ranked by distinct coauthors"), out.summary)
    val alice = out.claims.find(_.text.startsWith("Alice Arden has")).get
    assertEquals(
      alice.text,
      "Alice Arden has 41 distinct coauthors (collaboration breadth) across 12 publications in window 2015-2026, with weighted degree 60 (repeated-collaboration intensity) and 44 coauthor occurrences."
    )
    assert(
      out.claims.exists(
        _.text == "Ranked by distinct coauthors (collaboration breadth, C_r) in window 2015-2026: 1. Carol Chen (52), 2. Alice Arden (41), 3. Bob Brandt (30), 4. Dan Dorsey (19), 5. Wei Wang (3), 6. Wei Wang 0002 (2)."
      ),
      out.claims.map(_.text).mkString("\n")
    )
    val path = DeterministicSynthesizer.draft(
      ev("What is the shortest path between Alice Arden and Dan Dorsey since 2015?")
    )
    assertEquals(
      path.claims.take(2).map(_.text),
      Vector(
        "A shortest coauthorship path in window 2015-2026 has 3 hops: Alice Arden -> Bob Brandt -> Carol Chen -> Dan Dorsey.",
        "Alice Arden and Bob Brandt coauthored 3 joint publications in window 2015-2026, first in 2016 and last in 2022, including \"Surface code decoders\" (2021)."
      )
    )
    val bob = out.claims.find(_.text.startsWith("Bob Brandt's bridge importance")).get
    assert(bob.text.contains("bridge score 0.41"), bob.text)
    // repair M3: communitiesConnected (2) includes Bob's own community; never "N other communities"
    assert(bob.text.contains("touching 2 communities, its own included"), bob.text)
    assert(out.claims.forall(c => !c.text.contains("other communities")))
    assert(out.claims.forall(_.evidenceIds.forall(r.bundle.byId.contains)))
    assert(
      out.claims.forall(_.evidenceIds.forall(id => !r.bundle.semantic.exists(_.evidenceId == id)))
    )
  }
