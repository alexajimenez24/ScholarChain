package scholarchain.rag

import scholarchain.core.*
import scholarchain.core.VerificationStatus.*
import java.nio.file.{Files, Paths}
import RagWorld.*

/** Phase 11 C09 (R6-1): a position on a filtered ranking is a position among the filtered
  * researchers only, so no text that states it may leave the filter out.
  *
  * Fixture (RagWorld scaladoc, read by hand): the 2015-2026 distinct-coauthor ranking is Carol
  * (52), Alice (41), Bob (30); restricted to authors of quantum error correction works it is Alice,
  * Bob, Wei 1. So the filtered rank 1 (Alice) is the global rank 2, and "rank of Alice: 1" without
  * the filter is false. Expected texts are written by hand.
  */
class FilteredRankSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private lazy val pipe = w.pipeline()
  private lazy val prompt = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(p => Files.isDirectory(p.resolve("prompts/runtime")))
    .toRight("no prompts/runtime above the working directory")
    .flatMap(SynthesisPrompt.load)
    .fold(e => fail(e), identity)

  private val TopicQ =
    "Which researchers have the most distinct coauthors in quantum error correction since 2015?"
  private val GlobalQ = "Which researchers have the most distinct coauthors since 2015?"
  private val Filter =
    "authors of works with topic quantum error correction published in 2015-2026"

  private def evidence(q: String) =
    pipe.run("qry-c09", AnalysisRequest(q)).fold(e => fail(e.describe), identity)
  private def rankingOf(e: EvidenceStageResult) =
    e.bundle.deterministic
      .find(_.values.get("kind").contains("ranking"))
      .getOrElse(fail("no ranking item"))
  private def rankFig(rid: String, rank: String, id: String) =
    ClaimFigure(rid, "rank", rank, Vector(id))
  private def structured(e: EvidenceStageResult, f: ClaimFigure) =
    ClaimVerifier.structured(
      DraftClaim(
        "claim-1",
        "The leader holds position {figure-1}.",
        f.evidenceIds,
        None,
        Vector(f)
      ),
      e.bundle
    ) match
      case Vector(v) => v
      case other     => fail(s"expected one structured claim, got $other")

  test("fixture: the filtered leader (Alice) is not the global leader (Carol)") {
    val t = rankingOf(evidence(TopicQ))
    assertEquals(t.researcherIds, Vector(Alice, Bob, Wei1))
    assertEquals(t.values("filter"), Filter)
    assertEquals(rankingOf(evidence(GlobalQ)).researcherIds.take(3), Vector(Carol, Alice, Bob))
  }

  test("filtered rank 1 (global rank 2) is VERIFIED only with a text that names the filter") {
    val e = evidence(TopicQ)
    val rk = rankingOf(e).evidenceId
    val v = structured(e, rankFig(Alice, "1", rk))
    assertEquals(v.check.status, VERIFIED, v.check.reasons.toString)
    assertEquals(
      v.text,
      s"rank of Alice Arden ($Alice) by distinctCoauthorCount among $Filter in window 2015-2026: 1."
    )
    assertEquals(v.existingEvidenceIds, Vector(rk))
    // Alice's global position 2 cited against the filtered ranking contradicts it.
    assertEquals(structured(e, rankFig(Alice, "2", rk)).check.status, CONFLICTING)
    // The global leader is not on the filtered ranking at all.
    assertEquals(structured(e, rankFig(Carol, "1", rk)).check.status, UNSUPPORTED)
  }

  test("unfiltered rank 1 stays VERIFIED with the unchanged text (no 'among')") {
    val e = evidence(GlobalQ)
    val rk = rankingOf(e)
    assert(rk.values("filter").startsWith("none"), rk.values("filter"))
    assertEquals(ClaimVerifier.rankingFilter(rk), None)
    assertEquals(ClaimVerifier.amongText(rk), "")
    val v = structured(e, rankFig(Carol, "1", rk.evidenceId))
    assertEquals(v.check.status, VERIFIED)
    assertEquals(
      v.text,
      s"rank of Carol Chen ($Carol) by distinctCoauthorCount in window 2015-2026: 1."
    )
  }

  test("model path: the structured claim and the rank shown in model prose both name the filter") {
    val e = evidence(TopicQ)
    val rk = rankingOf(e).evidenceId
    val reply = ujson
      .Obj(
        "schemaVersion" -> "answer-synthesis-v2",
        "summary" -> "The ranking.",
        "abstain" -> false,
        "abstentionReason" -> ujson.Null,
        "claims" -> ujson.Arr(
          ujson.Obj(
            "claimId" -> "claim-1",
            "text" -> "Alice Arden holds position {figure-1}.",
            "evidenceIds" -> ujson.Arr(rk),
            "uncertainty" -> ujson.Null,
            "figures" -> ujson.read(upickle.default.write(Vector(rankFig(Alice, "1", rk))))
          )
        )
      )
      .render()
    val (wf, _) = w.workflow(new LlmSynthesizer(ScriptedLlm.replies(reply), prompt, w.cfg))
    val r = wf.run("qry-c09-model", AnalysisRequest(TopicQ)).fold(x => fail(x.describe), identity)
    assertEquals(r.synthesis.fallback, None)
    assertEquals(r.synthesis.synthesizer, "llm-synthesis-v2")
    assertEquals(
      r.answer.claims.map(c => (c.claimId, c.verificationStatus)),
      Vector(("claim-1", PARTIALLY_SUPPORTED), ("claim-1-figure-1", VERIFIED))
    )
    assertEquals(
      r.answer.claims(0).text,
      "UNVERIFIED interpretation: Alice Arden holds position 1 (rank of Alice Arden by " +
        s"distinctCoauthorCount among $Filter, window 2015-2026)."
    )
    assertEquals(
      r.answer.claims(1).text,
      s"rank of Alice Arden ($Alice) by distinctCoauthorCount among $Filter in window 2015-2026: 1."
    )
    // No VERIFIED rank statement anywhere leaves the filter out.
    assert(
      r.answer.claims
        .filter(c => c.verificationStatus == VERIFIED && c.text.contains("rank"))
        .forall(_.text.contains(Filter)),
      r.answer.claims.toString
    )
  }

  test("deterministic path: the ranking claim and the summary lead name the filter; all VERIFIED") {
    val (wf, _) = w.workflow()
    val r = wf.run("qry-c09-det", AnalysisRequest(TopicQ)).fold(x => fail(x.describe), identity)
    val ranked = r.answer.claims.filter(_.text.startsWith("Ranked by"))
    assert(ranked.nonEmpty, r.answer.claims.toString)
    assert(ranked.forall(c => c.text.contains(s"among $Filter:")), ranked.toString)
    assert(ranked.forall(_.verificationStatus == VERIFIED), ranked.toString)
    assert(
      r.answer.summary.startsWith(
        s"Researchers ranked by distinct coauthors (collaboration breadth, C_r) in window 2015-2026 among $Filter: " +
          "Alice Arden, Bob Brandt, Wei Wang."
      ),
      r.answer.summary
    )
    // The unfiltered summary lead is unchanged.
    val g = w.workflow()._1.run("qry-c09-glob", AnalysisRequest(GlobalQ)).toOption.get
    assert(
      g.answer.summary.startsWith(
        "Researchers ranked by distinct coauthors (collaboration breadth, C_r) in window 2015-2026: "
      ),
      g.answer.summary
    )
  }

  test("rankingFilter: only ranking items, never a 'none' filter, sanitized and bounded") {
    val e = evidence(TopicQ)
    val rk = rankingOf(e)
    assertEquals(ClaimVerifier.rankingFilter(rk), Some(Filter))
    val metrics = e.bundle.deterministic.find(_.values.get("kind").contains("researcher-metrics"))
    assertEquals(metrics.flatMap(ClaimVerifier.rankingFilter), None)
    val long = rk.copy(values = rk.values.updated("filter", "a\u0000b\nc " + "x" * 300))
    val f = ClaimVerifier.rankingFilter(long).getOrElse(fail("no filter"))
    assertEquals(f.length, 120)
    assert(f.startsWith("a b c x"), f)
    val none = rk.copy(values = rk.values.updated("filter", "none: every researcher"))
    assertEquals(ClaimVerifier.rankingFilter(none), None)
  }
