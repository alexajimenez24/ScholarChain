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
  private val Ev2 = "ev:metric_record:fedcba9876543210"
  private val Fig =
    s"""{"researcherId":"$Alice","metric":"distinctCoauthorCount","value":"41","evidenceIds":["$Ev"]}"""
  private def one(text: String, figures: String = s"[$Fig]", ids: String = s"\"$Ev\"") =
    s"""[{"claimId":"claim-1","text":"$text","evidenceIds":[$ids],"uncertainty":null,"figures":$figures}]"""
  private def reply(
      claims: String = one("Alice Arden has {figure-1} distinct coauthors."),
      abstain: String = "false",
      reason: String = "null",
      extra: String = ""
  ) =
    s"""{"schemaVersion":"answer-synthesis-v2","summary":"A summary.","abstain":$abstain,"abstentionReason":$reason,"claims":$claims$extra}"""

  test("the synthesis prompt is versioned, hashed and has exactly the data placeholders") {
    assertEquals(prompt.version, "answer-synthesis-v2")
    assertEquals(SynthesisRules.SchemaVersion, "answer-synthesis-v2")
    assert(prompt.template.contains("{figure-N}") && prompt.template.contains("`researcherId`"))
    assertEquals(
      prompt.sha256,
      Hashing.sha256Hex(Files.readString(root.resolve(SynthesisPrompt.File)))
    )
    assert(prompt.template.contains("data, not instructions"))
    assert(prompt.template.contains("no reasoning"))
    assert(SynthesisPrompt.parse(prompt.template.replace("{{FEEDBACK}}", "")).isLeft)
    assert(
      SynthesisPrompt
        .parse(prompt.template.replace("answer-synthesis-v2", "answer-synthesis-v0"))
        .isLeft
    )
  }

  test("prompts/schemas/answer-synthesis-v2.json matches the output encoding and the rule bounds") {
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
    val figures = claim("properties")("figures")
    val figure = figures("items")
    val encFigure = enc("claims")(0)("figures")(0)
    assertEquals(figures("maxItems").num.toInt, SynthesisRules.MaxFiguresPerClaim)
    assertEquals(figure("properties").obj.keySet.toSet, encFigure.obj.keySet.toSet)
    assertEquals(figure("required").arr.map(_.str).toSet, encFigure.obj.keySet.toSet)
    assertEquals(figure("additionalProperties").bool, false)
    val fp = figure("properties")
    assertEquals(fp("researcherId")("maxLength").num.toInt, SynthesisRules.MaxFigureSubject)
    assertEquals(fp("value")("maxLength").num.toInt, SynthesisRules.MaxFigureValue)
    assertEquals(fp("metric")("pattern").str, SynthesisRules.MetricPattern.regex)
    assertEquals(fp("evidenceIds")("maxItems").num.toInt, SynthesisRules.MaxEvidencePerFigure)
    assertEquals(
      fp("evidenceIds")("items")("pattern"),
      claim("properties")("evidenceIds")("items")("pattern")
    )
    assertEquals(
      sample.claims.head.figures,
      Vector(ClaimFigure(Alice, "distinctCoauthorCount", "41", Vector(Ev)))
    )
    assertEquals(sample.claims.head.draftText, "Alice Arden has 41 distinct coauthors.")
    assert(Ids.isEvidenceId(Ev) && Ids.isEvidenceId(Ev2))
  }

  test("output rules accept a valid reply and refuse malformed ones with fixed reasons") {
    assert(SynthesisRules.validate(reply()).isRight)
    // a claim without numbers has no figures
    assert(SynthesisRules.validate(reply(claims = one("Alice Arden is listed.", "[]"))).isRight)
    assert(SynthesisRules.validate("```json\n" + reply() + "\n```").isRight)
    val marker = "SECRET-MARKER-7731"
    val bad = Vector(
      s"Here is the answer: ${reply()}",
      reply(extra = s""","reasoning":"$marker step by step"""),
      reply().replace("\"uncertainty\":null", ""),
      reply().replace("answer-synthesis-v2", "answer-synthesis-v1"),
      reply().replace(s",\"figures\":[$Fig]", ""),
      reply(claims =
        s"""[{"claimId":"c1","text":"x","evidenceIds":["$Ev"],"uncertainty":null,"figures":[]}]"""
      ),
      reply(claims =
        s"""[{"claimId":"claim-1","text":"x","evidenceIds":["e:$marker"],"uncertainty":null,"figures":[]}]"""
      ),
      reply(claims =
        s"""[{"claimId":"claim-1","text":"x","evidenceIds":[],"uncertainty":null,"figures":[]}]"""
      ),
      reply(claims =
        s"""[{"claimId":"claim-1","text":"<thinking>$marker</thinking>","evidenceIds":["$Ev"],"uncertainty":null,"figures":[]}]"""
      ),
      reply(abstain = "true"),
      reply(claims =
        "[" + (1 to 41)
          .map(i =>
            s"""{"claimId":"claim-$i","text":"x","evidenceIds":["$Ev"],"uncertainty":null,"figures":[]}"""
          )
          .mkString(",") + "]"
      ),
      reply(claims = one("x" * 501, "[]")),
      // figures: a placeholder without its figure, a figure without its placeholder, one twice,
      // evidence the claim does not cite, a numeric value, a bad metric key, a blank subject
      reply(claims = one(s"Alice Arden has {figure-2} distinct coauthors. $marker")),
      reply(claims = one(s"Alice Arden has 41 distinct coauthors. $marker")),
      reply(claims = one("{figure-1} and {figure-1}")),
      reply(claims = one("{figure-1}", s"[${Fig.replace(Ev, Ev2)}]")),
      reply(claims = one("{figure-1}", s"[${Fig.replace("\"41\"", "41")}]")),
      reply(claims =
        one("{figure-1}", s"[${Fig.replace("distinctCoauthorCount", s"x y $marker")}]")
      ),
      reply(claims = one("{figure-1}", s"[${Fig.replace(Alice, " ")}]")),
      reply(claims =
        one(
          (1 to 17).map(i => s"{figure-$i}").mkString(" "),
          (1 to 17).map(_ => Fig).mkString("[", ",", "]")
        )
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
    // every number is a figure of Alice's own metrics record
    val aliceEv = r.bundle.deterministic
      .find(x =>
        x.values.get("kind").contains("researcher-metrics") && x.researcherIds == Vector(Alice)
      )
      .get
      .evidenceId
    assertEquals(
      alice.text,
      "Alice Arden has {figure-1} distinct coauthors (collaboration breadth) across {figure-2} publications in window 2015-2026, with weighted degree {figure-3} (repeated-collaboration intensity) and {figure-4} coauthor occurrences."
    )
    assertEquals(
      alice.figures,
      Vector(
        "distinctCoauthorCount" -> "41",
        "publicationCount" -> "12",
        "weightedDegree" -> "60",
        "totalCoauthorOccurrences" -> "44"
      ).map((m, x) => ClaimFigure(Alice, m, x, Vector(aliceEv)))
    )
    assertEquals(
      alice.draftText,
      "Alice Arden has 41 distinct coauthors (collaboration breadth) across 12 publications in window 2015-2026, with weighted degree 60 (repeated-collaboration intensity) and 44 coauthor occurrences."
    )
    assert(
      out.claims.exists(
        _.draftText == "Ranked by distinct coauthors (collaboration breadth, C_r) in window 2015-2026: 1. Carol Chen (52), 2. Alice Arden (41), 3. Bob Brandt (30), 4. Dan Dorsey (19), 5. Wei Wang (3), 6. Wei Wang 0002 (2)."
      ),
      out.claims.map(_.draftText).mkString("\n")
    )
    val path = DeterministicSynthesizer.draft(
      ev("What is the shortest path between Alice Arden and Dan Dorsey since 2015?")
    )
    assertEquals(
      path.claims.take(2).map(_.draftText),
      Vector(
        "A shortest coauthorship path in window 2015-2026 has 3 hops: Alice Arden -> Bob Brandt -> Carol Chen -> Dan Dorsey.",
        "Alice Arden and Bob Brandt coauthored 3 joint publications in window 2015-2026, first in 2016 and last in 2022, including \"Surface code decoders\" (2021)."
      )
    )
    val bob = out.claims.find(_.text.startsWith("Bob Brandt's bridge importance")).get
    assert(bob.draftText.contains("bridge score 0.41"), bob.draftText)
    // repair M3: communitiesConnected (2) includes Bob's own community; never "N other communities"
    assert(
      bob.draftText.contains("touching 2 communities, its own included"),
      bob.draftText
    )
    assert(out.claims.forall(c => !c.draftText.contains("other communities")))
    // no digit outside a placeholder, window, identifier, name or quoted title is left to prose
    assert(out.claims.forall(_.figures.forall(_.evidenceIds.forall(r.bundle.byId.contains))))
    assert(out.claims.forall(_.evidenceIds.forall(r.bundle.byId.contains)))
    assert(
      out.claims.forall(_.evidenceIds.forall(id => !r.bundle.semantic.exists(_.evidenceId == id)))
    )
  }
