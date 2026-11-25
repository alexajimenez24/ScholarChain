package scholarchain.rag

import scholarchain.core.*
import scholarchain.storage.*
import upickle.default.ReadWriter

/** A drafted claim the verifier rejected: kept in the audit, never in the answer. */
final case class RemovedClaim(
    claimId: String,
    text: String,
    status: VerificationStatus,
    reasons: Vector[String]
) derives ReadWriter

/** One name of the plan and what the resolver made of it. */
final case class ResolvedIdentity(
    query: String,
    status: ResolutionStatus,
    researcherId: Option[String],
    candidateIds: Vector[String],
    totalCandidates: Int
) derives ReadWriter

/** The observable derivation of one answer (`audit-record-v1`).
  *
  * It holds the plan, resolutions, evidence IDs, versions of every stage, the prompt version and
  * SHA-256, the model, token usage when reported, every model attempt (status, the validator's
  * reason and the SHA-256 of the reply, never the reply text), graph calls (template IDs and
  * windows, never query text), stage timings, latency, every claim check, the removed claims, the
  * verification result and the answer hash. No prompt text, reply text or model reasoning is kept.
  */
final case class AuditRecord(
    schemaVersion: String,
    queryId: String,
    plan: QueryPlan,
    answeredWindow: TimeWindow,
    windowNote: Option[String],
    resolutions: Vector[ResolvedIdentity],
    resolvedResearcherIds: Vector[String],
    evidenceBundleId: String,
    evidenceIds: Vector[String],
    graphRunId: String,
    erRunId: String,
    erPolicyVersion: String,
    metricVersions: Vector[String],
    componentVersions: Map[String, String],
    synthesizer: String,
    promptVersion: String,
    promptSha256: Option[String],
    outputSchemaVersion: String,
    modelVersion: String,
    inputTokens: Option[Long],
    outputTokens: Option[Long],
    synthesisAttempts: Vector[SynthesisAttempt],
    synthesisFallback: Option[String],
    context: Option[ContextUse],
    graphCalls: Vector[GraphCall],
    semanticDropped: Int,
    stages: Vector[StageTiming],
    latencyMs: Long,
    claimChecks: Vector[ClaimCheck],
    removedClaims: Vector[RemovedClaim],
    summaryReplaced: Option[String],
    verification: VerificationStatus,
    abstained: Boolean,
    abstentionReason: Option[String],
    answerHash: String,
    notes: Vector[String]
) derives ReadWriter

object AuditRecord:
  val SchemaVersion = "audit-record-v1"

/** Where audit records are persisted. One record per query ID, written with a condition: the same
  * record again is `Unchanged`, a different record under the same ID is refused. The DynamoDB
  * repository for it belongs to storage (not built in this phase); [[InMemoryAuditLog]] is the
  * local store.
  */
trait AuditLog:
  def append(r: AuditRecord): Either[String, Stored]
  def get(queryId: String): Either[String, Option[AuditRecord]]

final class InMemoryAuditLog extends AuditLog:
  private val records = scala.collection.concurrent.TrieMap.empty[String, AuditRecord]

  def append(r: AuditRecord): Either[String, Stored] =
    records.putIfAbsent(r.queryId, r) match
      case None                  => Right(Stored.Created)
      case Some(old) if old == r => Right(Stored.Unchanged)
      case Some(_) => Left(s"an audit record for ${r.queryId} exists and differs; not overwritten")

  def get(queryId: String): Either[String, Option[AuditRecord]] = Right(records.get(queryId))
  def all: Vector[AuditRecord] = records.values.toVector.sortBy(_.queryId)

/** Everything one run of the workflow produced. */
final case class WorkflowResult(
    answer: AnswerResponse,
    evidence: EvidenceStageResult,
    synthesis: SynthesisResult,
    audit: AuditRecord
)

/** The runtime collaboration-analysis workflow, stages 1 to 10 (`collaboration-workflow-v1`), the
  * single end-to-end entry point: [[EvidencePipeline]] (plan, resolve, metrics, paths, communities,
  * semantic context, evidence bundle), then
  *   8. synthesis: the [[Synthesizer]] drafts the summary and claims; when stages 1 to 7 abstain
  *      the deterministic synthesizer lists the candidates and no model is called;
  *   9. verification: [[ClaimVerifier]] checks every claim, its uncertainty note and the summary.
  *      VERIFIED claims are kept; PARTIALLY_SUPPORTED claims are kept weakened (unsupported spans
  *      replaced by `[unverified]`) and labeled; UNSUPPORTED and CONFLICTING claims are removed and
  *      kept in the audit. A summary with an unsupported or conflicting value is replaced. Identity
  *      caveats (confidence below 0.9, unresolved or conflicting identity) and the window note are
  *      added to the claims they concern; the evidence stage's notes are stated as missing or
  *      limited evidence;
  *   10. audit: the bundle is stored in EvidenceBundle (conditional write) and the [[AuditRecord]]
  *       in the [[AuditLog]] (conditional write).
  *
  * The answer abstains when stages 1 to 7 abstain, when no claim survives verification, or when the
  * synthesis output abstains; an abstaining answer has no researcher rows, and a non-abstaining one
  * keeps only rows that a kept claim cites. It fails with a `WorkflowError` when a stage fails, the
  * answer breaks core validation or a store refuses a write.
  */
final class CollaborationWorkflow(
    ctx: RuntimeContext,
    stores: RuntimeStores,
    retriever: SemanticContextRetriever,
    synthesizer: Synthesizer,
    audit: AuditLog,
    limits: EvidenceLimits = EvidenceLimits(),
    clock: () => Long = () => System.currentTimeMillis()
):
  import CollaborationWorkflow.*
  val evidence = new EvidencePipeline(ctx, stores, retriever, limits, clock)
  private val bundles = new EvidenceBundles(stores.kv, ctx.cfg.dynamo.evidenceBundleTable)

  def run(queryId: String, req: AnalysisRequest): Either[WorkflowError, WorkflowResult] =
    val t0 = clock()
    evidence.run(queryId, req).flatMap { e =>
      val s0 = clock()
      val synthesis =
        if e.abstention.nonEmpty then DeterministicSynthesizer.synthesize(e)
        else synthesizer.synthesize(e)
      val s1 = clock()
      val verified = verify(e, synthesis)
      val s2 = clock()
      val answer = verified.answer(queryId)
      val errors = Validate.answer(answer)
      for
        _ <- Either.cond(
          errors.isEmpty,
          (),
          WorkflowError.InvalidRequest(s"answer invalid: ${errors.mkString("; ").take(300)}")
        )
        _ <- bundles
          .put(e.bundle)
          .left
          .map(err => WorkflowError.Downstream("audit", err.toString.take(200)))
        stages = e.stages ++ Vector(
          StageTiming("synthesis", s1 - s0, synthesis.fallback.fold("ok")(_ => "fallback")),
          StageTiming("verification", s2 - s1, "ok"),
          StageTiming("audit", clock() - s2, "ok")
        )
        record = auditRecord(queryId, e, synthesis, verified, answer, stages, clock() - t0)
        _ <- audit.append(record).left.map(err => WorkflowError.Downstream("audit", err.take(200)))
      yield WorkflowResult(answer, e, synthesis, record)
    }

  /** Stage 9 and the answer it yields. */
  private final case class Verified(
      e: EvidenceStageResult,
      synthesis: SynthesisResult,
      checks: Vector[ClaimCheck],
      kept: Vector[AnswerClaim],
      removed: Vector[RemovedClaim],
      summary: String,
      summaryReplaced: Option[String]
  ):
    def abstained: Boolean = e.abstention.nonEmpty || synthesis.output.abstain || kept.isEmpty
    def abstentionReason: Option[String] =
      Option.when(abstained)(
        e.abstention
          .orElse(synthesis.output.abstentionReason)
          .getOrElse("no claim could be verified against the evidence bundle")
      )
    def answer(queryId: String): AnswerResponse =
      AnswerResponse(
        SchemaVersions.Answer,
        queryId,
        summary,
        // Rows only in an answer that did not abstain for any cause, and only rows whose numbers
        // a kept claim cites (a row without claims points to no evidence).
        if abstained then Vector.empty
        else researchers(e, kept).filter(_.claimIds.nonEmpty),
        kept,
        e.window,
        abstained,
        abstentionReason,
        e.bundle.bundleId,
        ClaimVerifier.overall(checks, kept.size),
        if synthesis.byModel then synthesis.promptVersion else DeterministicSynthesizer.Version,
        if synthesis.byModel then synthesis.modelVersion else "none",
        Provenance(
          SourceType.SCHOLARCHAIN,
          queryId,
          ctx.snapshot,
          ctx.graphRunId,
          SchemaVersions.Answer,
          Some(Version)
        )
      )

  private def verify(e: EvidenceStageResult, s: SynthesisResult): Verified =
    val b = e.bundle
    val caveats = identityCaveats(e)
    val verdicts = s.output.claims.map(c => c -> ClaimVerifier.verify(c, b))
    val kept = verdicts.collect {
      case (c, v)
          if v.check.status == VerificationStatus.VERIFIED || v.check.status == VerificationStatus.PARTIALLY_SUPPORTED =>
        val cited = v.existingEvidenceIds.flatMap(b.byId.get)
        val modelNote = c.uncertainty.map { u =>
          val n = ClaimVerifier.verifyText(c.claimId, u, v.existingEvidenceIds, b)
          if n.check.status == VerificationStatus.CONFLICTING || n.unsupportedSpans.nonEmpty then
            "the synthesizer's uncertainty note was removed: it stated values not in the cited evidence"
          else u
        }
        val label = Option.when(v.check.status == VerificationStatus.PARTIALLY_SUPPORTED)(
          s"partially supported: ${v.check.reasons.mkString("; ")}"
        )
        val idNotes = cited
          .filterNot(x => GraphValuePolicy.Descriptive(x.evidenceType))
          .flatMap(_.researcherIds)
          .distinct
          .flatMap(caveats.get)
        val parts = (modelNote.toVector ++ label ++ idNotes ++ e.windowNote).distinct
        AnswerClaim(
          c.claimId,
          v.weakened(c.text),
          v.existingEvidenceIds,
          v.check.status,
          Option.when(parts.nonEmpty)(UntrustedText.bound(parts.mkString("; "), MaxUncertainty))
        )
    }
    val removed = verdicts.collect {
      case (c, v)
          if v.check.status == VerificationStatus.UNSUPPORTED || v.check.status == VerificationStatus.CONFLICTING =>
        RemovedClaim(c.claimId, c.text, v.check.status, v.check.reasons)
    }
    val all = (b.deterministic ++ b.identity).map(_.evidenceId)
    val sv = ClaimVerifier.verifyText("summary", s.output.summary, all, b)
    val summaryOk =
      sv.check.status != VerificationStatus.CONFLICTING && sv.unsupportedSpans.isEmpty
    val base =
      if summaryOk then s.output.summary
      else DeterministicSynthesizer.draft(e).summary
    val missing = e.notes.distinct.take(MaxNotes).map(n => UntrustedText.bound(n, MaxNoteChars))
    val summary =
      if missing.isEmpty then base
      else s"$base Missing or limited evidence: ${missing.mkString("; ")}."
    Verified(
      e,
      s,
      verdicts.map(_._2.check),
      kept,
      removed,
      summary,
      Option.when(!summaryOk)(sv.check.reasons.mkString("; ").take(400))
    )

  /** Researchers whose identity deserves a caveat, with the caveat text. */
  private def identityCaveats(e: EvidenceStageResult): Map[String, String] =
    (e.named ++ e.ranked)
      .map(_.metrics.researcherId)
      .distinct
      .flatMap { id =>
        stores.directory.byId(id).toOption.flatten.flatMap { r =>
          val flags = Vector(
            Option.when(r.identityConfidence < ConfidenceFloor)(
              s"identity confidence ${MetricValues.plain(r.identityConfidence)} below $ConfidenceFloor"
            ),
            Option.when(r.unresolved)("unresolved source links"),
            Option.when(r.identityConflict)("an identity conflict recorded by entity resolution")
          ).flatten
          Option.when(flags.nonEmpty)(
            id -> s"identity of ${r.displayName} ($id): ${flags.mkString(", ")}"
          )
        }
      }
      .toMap

  private def researchers(
      e: EvidenceStageResult,
      kept: Vector[AnswerClaim]
  ): Vector[ResearcherAnswer] =
    (e.named ++ e.ranked).distinctBy(_.metrics.researcherId).map { r =>
      val m = r.metrics
      val conf = stores.directory
        .byId(m.researcherId)
        .toOption
        .flatten
        .map(_.identityConfidence)
        .orElse(
          e.resolutions
            .flatMap(_.candidates)
            .find(_.researcherId == m.researcherId)
            .map(_.identityConfidence)
        )
        .getOrElse(0.0)
      ResearcherAnswer(
        m.researcherId,
        r.displayName,
        m.publicationCount,
        m.distinctCoauthorCount,
        m.weightedDegree,
        m.averageCoauthorsPerPaper,
        m.maximumCoauthorsOnOnePaper,
        m.maximumTeamWorkIds,
        m.componentId,
        m.communityId,
        m.bridgeScore.map(BridgeMetric(BridgeMetricName, _)),
        m.coreness,
        conf,
        kept.filter(_.evidenceIds.contains(r.evidence.evidenceId)).map(_.claimId)
      )
    }

  private def auditRecord(
      queryId: String,
      e: EvidenceStageResult,
      s: SynthesisResult,
      v: Verified,
      answer: AnswerResponse,
      stages: Vector[StageTiming],
      latency: Long
  ): AuditRecord =
    val metricVersions =
      (ctx.metricVersion +: e.bundle.deterministic.flatMap(_.values.get("metricVersion"))).distinct
    AuditRecord(
      AuditRecord.SchemaVersion,
      queryId,
      e.plan,
      e.window,
      e.windowNote,
      e.resolutions.map(r =>
        ResolvedIdentity(
          r.query,
          r.status,
          r.researcherId,
          r.candidates.map(_.researcherId),
          r.totalCandidates
        )
      ),
      e.resolutions.flatMap(_.researcherId),
      e.bundle.bundleId,
      e.bundle.all.map(_.evidenceId),
      ctx.graphRunId,
      ctx.erRunId,
      ctx.erPolicy,
      metricVersions,
      componentVersions(s),
      s.synthesizer,
      s.promptVersion,
      s.promptSha256,
      SynthesisRules.SchemaVersion,
      s.modelVersion,
      s.inputTokens,
      s.outputTokens,
      s.attempts,
      s.fallback,
      s.context,
      e.graphCalls,
      e.semanticDropped.size,
      stages,
      latency,
      v.checks,
      v.removed,
      v.summaryReplaced,
      answer.verification,
      answer.abstained,
      answer.abstentionReason,
      Hashing.tagged(JsonLines.encode(answer)),
      e.notes
    )

  private def componentVersions(s: SynthesisResult): Map[String, String] = Map(
    "workflow" -> Version,
    "planner" -> QueryPlanner.Version,
    "planOperations" -> PlanOperations.Version,
    "resolver" -> ResearcherResolverAgent.Version,
    "metrics" -> CollaborationMetricsAgent.Version,
    "paths" -> PathFinderAgent.Version,
    "communities" -> CommunityAnalystAgent.Version,
    "semantic" -> SemanticContextAgent.Version,
    "semanticContext" -> SemanticContext.Version,
    "untrustedText" -> UntrustedText.Version,
    "graphValuePolicy" -> GraphValuePolicy.Version,
    "evidenceBuilder" -> EvidenceBuilderAgent.Version,
    "evidencePipeline" -> EvidencePipeline.Version,
    "graphRunner" -> GraphRunner.Version,
    "synthesizer" -> s.synthesizer,
    "synthesisRules" -> SynthesisRules.Version,
    "contextPacker" -> ContextPacker.Version,
    "verifier" -> ClaimVerifier.Version,
    "audit" -> AuditRecord.SchemaVersion
  )

object CollaborationWorkflow:
  val Version = "collaboration-workflow-v1"
  val ConfidenceFloor = 0.9
  val MaxUncertainty = 600
  val MaxNotes = 10
  val MaxNoteChars = 300
  val BridgeMetricName = "sampled-brandes-betweenness"
