package scholarchain.rag

import scholarchain.core.*
import scholarchain.core.VerificationStatus.*
import scholarchain.storage.*
import RagWorld.*

/** Phase 11 C14 (read part): CommunityAnalystAgent reads community sizes through storage's
  * CommunityReader (run, community, window). A stored size is evidence and can be VERIFIED; a
  * missing one stays missing (never 0), and a failed read is reported, never filled.
  *
  * Fixture (RagWorld scaladoc): Alice, Bob and Wei 1 are in the quantum community in 2015-2026, so
  * the stored size is 3 (hand-written); Carol's biology community has no stored record.
  */
class CommunitySizeSuite extends munit.FunSuite:
  private val w = RagWorld.default
  private val Quantum = SmokeFixture.QuantumCommunity

  /** A `recordKind=community` row of the Spark communities table, for `run`'s context. */
  private def sizeRow(
      cid: String,
      n: Long,
      window: TimeWindow = Recent,
      ctx: RuntimeContext = w.ctx
  ) =
    ctx.evidence(
      EvidenceType.COMMUNITY_RECORD,
      s"community-size|$cid|${window.label}",
      "s3://fixture/graph/communities",
      Vector.empty,
      Map(
        "recordKind" -> "community",
        "communityId" -> cid,
        "communitySize" -> n.toString,
        "window" -> window.label
      ),
      "graph-v1"
    )

  private def reader(rows: EvidenceItem*): CommunityReader =
    val r = new InMemoryCommunityReader
    rows.foreach(x => r.put(x).fold(e => fail(e.toString), identity))
    r

  private def stores(c: Option[CommunityReader]) =
    val (s, ret) = w.stores()
    (s.copy(communities = c), ret)

  private def r[A](e: Either[WorkflowError, A]): A = e.fold(x => fail(x.describe), identity)

  /** Bob's community view on stores with community reader `c`. */
  private def view(c: Option[CommunityReader]) =
    val (s, ret) = stores(c)
    val p = new EvidencePipeline(w.ctx, s, ret)
    val plan = r(p.planner.plan("qry-c14", AnalysisRequest("Who has the most coauthors?")))
    val g = GraphRunner.forPlan(
      s.graph,
      plan.copy(operations =
        Vector(QueryOperation.COMMUNITY_COMPARISON, QueryOperation.SEMANTIC_EXPLANATION)
      ),
      w.cfg
    )
    val bob = r(p.metrics.forResearcher(Bob, Recent)).get
    r(p.communities.analyse(bob, 10, g))

  private def missing(v: CommunityView, status: String, why: String) =
    val rec = v.evidence.head.values
    assert(!rec.contains("communitySize"), rec.toString)
    assert(rec("missingFields").split(';').contains("communitySize"), rec("missingFields"))
    assertEquals(rec("communitySizeStatus"), status)
    assertEquals(v.notes, Vector(s"community size of $Quantum is not available: $why"))
    assertEquals(v.evidence.count(_.values.get("recordKind").contains("community")), 0)

  test("a stored size goes into the record, leaves missingFields, and cites the exported row") {
    val row = sizeRow(Quantum, 3)
    val v = view(Some(reader(row)))
    val rec = v.evidence.head.values
    assertEquals(rec("kind"), "community-membership")
    assertEquals(rec("communitySize"), "3")
    assertEquals(rec("communitySizeStatus"), "present")
    assertEquals(rec("communitySizeEvidenceId"), row.evidenceId)
    assert(!rec("missingFields").split(';').contains("communitySize"), rec("missingFields"))
    assertEquals(v.evidence(1), row)
    assert(!v.notes.exists(_.contains("community size")), v.notes.toString)
  }

  test("no stored record: reported missing (never 0) with the run and window") {
    missing(
      view(Some(reader())),
      "not-stored",
      s"no community record is stored for run ${w.runId} in window 2015-2026"
    )
  }

  test("a store failure is reported as a failed read, with a fixed note and no value") {
    val failing = new CommunityReader:
      def community(
          runId: String,
          communityId: String,
          window: TimeWindow
      ): Either[KvError, Option[CommunitySize]] =
        Left(KvError.Failure("throttled: secret-table-name"))
    val v = view(Some(failing))
    missing(v, "read-failed", "the community store read failed")
    assert(!v.notes.exists(_.contains("secret-table-name")))
  }

  test("a record of another run or window is refused, never used") {
    val other = new RagWorld("run-0123456789abcdef")
    val wrongRun = new CommunityReader:
      def community(
          runId: String,
          communityId: String,
          window: TimeWindow
      ): Either[KvError, Option[CommunitySize]] =
        CommunitySize
          .of(sizeRow(Quantum, 3, ctx = other.ctx))
          .left
          .map(KvError.Failure(_))
          .map(Some(_))
    missing(
      view(Some(wrongRun)),
      "read-failed",
      "the community store returned a record of another run, community or window"
    )
    val wrongWindow = new CommunityReader:
      def community(
          runId: String,
          communityId: String,
          window: TimeWindow
      ): Either[KvError, Option[CommunitySize]] =
        CommunitySize.of(sizeRow(Quantum, 3, Early)).left.map(KvError.Failure(_)).map(Some(_))
    missing(
      view(Some(wrongWindow)),
      "read-failed",
      "the community store returned a record of another run, community or window"
    )
  }

  test("no community reader: the old missing note, unchanged") {
    missing(
      view(None),
      "no-store",
      "the Spark community table is not in any store the runtime reads"
    )
  }

  private val RankingQ =
    "Which researchers have the most distinct coauthors since 2015, and which of them are the strongest bridges between communities?"
  private val Members = """\b\d+\s+members\b""".r

  private def answer(c: Option[CommunityReader]) =
    val (s, ret) = stores(c)
    val wf =
      new CollaborationWorkflow(w.ctx, s, ret, DeterministicSynthesizer, new InMemoryAuditLog)
    r(wf.run("qry-c14-wf", AnalysisRequest(RankingQ)))

  test("deterministic answer: 'N members' is VERIFIED when the size is stored, absent otherwise") {
    val row = sizeRow(Quantum, 3)
    val a = answer(Some(reader(row)))
    val sized = a.answer.claims.filter(c => Members.findFirstIn(c.text).isDefined)
    assert(sized.nonEmpty, a.answer.claims.map(_.text).toString)
    assert(sized.forall(_.verificationStatus == VERIFIED), sized.toString)
    assert(sized.forall(_.text.contains(s"community $Quantum of 3 members")), sized.toString)
    assert(sized.forall(_.evidenceIds.contains(row.evidenceId)), sized.toString)
    assert(a.evidence.bundle.deterministic.contains(row))
    // Only the quantum community size is stored; the others stay missing in the summary.
    assert(!a.answer.summary.contains(s"community size of $Quantum"), a.answer.summary)
    assert(
      a.answer.summary
        .contains(s"community size of ${SmokeFixture.BiologyCommunity} is not available"),
      a.answer.summary
    )

    val none = answer(Some(reader()))
    assert(
      none.answer.claims.forall(c => Members.findFirstIn(c.text).isEmpty),
      none.answer.claims.map(_.text).toString
    )
    assert(
      none.answer.summary.contains(s"community size of $Quantum is not available"),
      none.answer.summary
    )
  }

  test("model figure: (researcher, communitySize, N) is VERIFIED only when the size is stored") {
    def check(c: Option[CommunityReader], value: String) =
      val e = answer(c).evidence
      val rec = e.bundle.deterministic
        .find(x =>
          x.values.get("kind").contains("community-membership") && x.values("researcherId") == Bob
        )
        .getOrElse(fail("no community record for Bob"))
      val f = ClaimFigure(Bob, "communitySize", value, Vector(rec.evidenceId))
      ClaimVerifier.structured(
        DraftClaim(
          "claim-1",
          "The community has {figure-1} members.",
          f.evidenceIds,
          None,
          Vector(f)
        ),
        e.bundle
      ) match
        case Vector(v) => v
        case other     => fail(other.toString)
    val ok = check(Some(reader(sizeRow(Quantum, 3))), "3")
    assertEquals(ok.check.status, VERIFIED)
    assertEquals(ok.text, s"communitySize of Bob Brandt ($Bob) in window 2015-2026: 3.")
    assertEquals(check(Some(reader(sizeRow(Quantum, 3))), "4").check.status, CONFLICTING)
    assertEquals(check(Some(reader()), "3").check.status, UNSUPPORTED)
    assertEquals(check(None, "0").check.status, UNSUPPORTED)
  }
