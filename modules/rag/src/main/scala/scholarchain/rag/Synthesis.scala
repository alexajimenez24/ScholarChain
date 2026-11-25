package scholarchain.rag

import scholarchain.core.*
import upickle.default.ReadWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.Try

/** One number of a claim as an explicit tuple (`answer-synthesis-v2`): the value `value` of
  * `metric` for the researcher `researcherId` (for a publication year, the work ID), held by the
  * cited items `evidenceIds`. The claim text holds its placeholder `{figure-N}` (N counts the
  * claim's figures from 1); the verifier checks the tuple and renders the number from the evidence.
  * A ranking position is the figure (researcherId, `rank`, position, the ranking's evidence ID). A
  * model's figure is a structured claim (`claim-verifier-v3`): the only model statement that can be
  * VERIFIED.
  */
final case class ClaimFigure(
    researcherId: String,
    metric: String,
    value: String,
    evidenceIds: Vector[String]
) derives ReadWriter

/** One claim as a synthesizer drafts it, before verification. Every number is a [[ClaimFigure]]
  * whose placeholder stands in `text`.
  */
final case class DraftClaim(
    claimId: String,
    text: String,
    evidenceIds: Vector[String],
    uncertainty: Option[String],
    figures: Vector[ClaimFigure]
) derives ReadWriter:
  /** The text with each placeholder replaced by the drafted (unverified) value, for the audit. */
  def draftText: String = ClaimFigures.fill(text, i => figures.lift(i).map(_.value))

/** Placeholders of [[ClaimFigure]]s in claim text: `{figure-N}`, N from 1. */
object ClaimFigures:
  val Placeholder: scala.util.matching.Regex = "\\{figure-([0-9]{1,2})\\}".r
  def placeholder(index: Int): String = s"{figure-${index + 1}}"

  /** The zero-based figure index of every placeholder in `text`, in order. */
  def refs(text: String): Vector[Int] =
    Placeholder.findAllMatchIn(text).map(_.group(1).toInt - 1).toVector

  /** `text` with each placeholder replaced by `value(index)`, or left as is when it is None. */
  def fill(text: String, value: Int => Option[String]): String =
    Placeholder.replaceAllIn(
      text,
      m =>
        scala.util.matching.Regex
          .quoteReplacement(value(m.group(1).toInt - 1).getOrElse(m.matched))
    )

/** The `answer-synthesis-v2` reply (`prompts/schemas/answer-synthesis-v2.json`). There is no field
  * for reasoning: a reply with any other key is refused.
  */
final case class SynthesisOutput(
    schemaVersion: String,
    summary: String,
    abstain: Boolean,
    abstentionReason: Option[String],
    claims: Vector[DraftClaim]
) derives ReadWriter

/** One model call: outcome, the validator's reason when refused, and the SHA-256 of the raw reply.
  * The reply text itself is never kept.
  */
final case class SynthesisAttempt(
    attempt: Int,
    status: String,
    reason: Option[String],
    replySha256: Option[String],
    maxOutputTokens: Int,
    inputTokens: Option[Long],
    outputTokens: Option[Long]
) derives ReadWriter

/** How much of the bundle reached the prompt under the context budget. */
final case class ContextUse(
    tokenBudget: Int,
    estimatedTokens: Int,
    includedEvidence: Int,
    omittedEvidenceIds: Vector[String]
) derives ReadWriter

/** What the synthesis stage produced. `synthesizer` names what wrote `output`; `promptVersion` and
  * `modelVersion` name the prompt and model that were called ("none" when no model was called).
  */
final case class SynthesisResult(
    output: SynthesisOutput,
    synthesizer: String,
    promptVersion: String,
    promptSha256: Option[String],
    modelVersion: String,
    inputTokens: Option[Long],
    outputTokens: Option[Long],
    attempts: Vector[SynthesisAttempt],
    fallback: Option[String],
    context: Option[ContextUse]
):
  def byModel: Boolean = synthesizer == LlmSynthesizer.Version

/** AnswerSynthesisAgent port: drafts the summary and claims from stages 1 to 7. */
trait Synthesizer:
  def synthesize(e: EvidenceStageResult): SynthesisResult

// ---------------------------------------------------------------- output rules

/** Validation of a model reply against `answer-synthesis-v2` (`synthesis-rules-v2`).
  *
  * The reply must be exactly one JSON object (one surrounding ```json fence is tolerated), decode
  * strictly (no unknown, missing or null key), name the schema version and keep every bound of the
  * schema file. Every figure's placeholder appears exactly once in its claim's text, every
  * placeholder names an existing figure, and a figure cites only evidence its claim cites.
  * Reasoning markup is refused. Refusal reasons are fixed texts written here, never fragments of
  * the reply, so a retry prompt and the audit never repeat model text. Accepted text is passed
  * through the untrusted-text sanitizer.
  */
object SynthesisRules:
  val Version = "synthesis-rules-v2"
  val SchemaVersion = "answer-synthesis-v2"
  val SchemaFile = "prompts/schemas/answer-synthesis-v2.json"
  val MaxSummary = 1500
  val MaxReason = 600
  val MaxClaims = 40
  val MaxClaimText = 500
  val MaxEvidencePerClaim = 12
  val MaxUncertainty = 300
  val MaxFiguresPerClaim = 16
  val MaxEvidencePerFigure = 4
  val MaxFigureSubject = 200
  val MaxFigureValue = 64
  val ClaimIdPattern = "^claim-[0-9]{1,3}$".r
  val MetricPattern = "^[A-Za-z][A-Za-z0-9_.]{0,63}$".r
  private val Reasoning =
    "(?i)<\\s*/?\\s*(thinking|think|reasoning|scratchpad|analysis|reflection)\\b".r

  def validate(raw: String): Either[String, SynthesisOutput] =
    val t0 = raw.trim
    val t =
      if t0.startsWith("```") && t0.endsWith("```") && t0.length > 6 then
        t0.stripPrefix("```json").stripPrefix("```").stripSuffix("```").trim
      else t0
    if !(t.startsWith("{") && t.endsWith("}")) then Left("the reply is not one JSON object")
    else if Reasoning.findFirstIn(t).isDefined then Left("the reply contains reasoning markup")
    else
      JsonLines
        .decode[SynthesisOutput](t)
        .left
        .map(_ =>
          "the reply is not valid answer-synthesis-v2 JSON (unknown, missing or null key, or a wrong type)"
        )
        .flatMap(check)
        .map(sanitized)

  /** The schema bounds, also applied to deterministic output (a test keeps them in step). */
  def check(o: SynthesisOutput): Either[String, SynthesisOutput] =
    val ids = o.claims.map(_.claimId)
    def blank(s: String) = s.trim.isEmpty
    if o.schemaVersion != SchemaVersion then Left(s"schemaVersion must be $SchemaVersion")
    else if blank(o.summary) || o.summary.length > MaxSummary then
      Left(s"summary must have 1 to $MaxSummary characters")
    else if o.abstain && o.abstentionReason.forall(blank) then
      Left("an abstaining reply needs an abstentionReason")
    else if o.abstentionReason.exists(_.length > MaxReason) then
      Left(s"abstentionReason exceeds $MaxReason characters")
    else if o.claims.size > MaxClaims then Left(s"more than $MaxClaims claims")
    else if ids.exists(i => !ClaimIdPattern.matches(i)) || ids.distinct.size != ids.size then
      Left("claim IDs must be distinct and look like claim-N")
    else if o.claims.exists(c => blank(c.text) || c.text.length > MaxClaimText) then
      Left(s"every claim text needs 1 to $MaxClaimText characters")
    else if o.claims.exists(c =>
        c.evidenceIds.isEmpty || c.evidenceIds.size > MaxEvidencePerClaim || c.evidenceIds
          .exists(id => !Ids.isEvidenceId(id))
      )
    then Left(s"every claim needs 1 to $MaxEvidencePerClaim well-formed evidence IDs")
    else if o.claims.exists(_.uncertainty.exists(_.length > MaxUncertainty)) then
      Left(s"uncertainty exceeds $MaxUncertainty characters")
    else if o.claims.exists(_.figures.size > MaxFiguresPerClaim) then
      Left(s"a claim has more than $MaxFiguresPerClaim figures")
    else if o.claims.exists(c =>
        c.figures.exists(f =>
          blank(f.researcherId) || f.researcherId.length > MaxFigureSubject ||
            !MetricPattern.matches(f.metric) || blank(f.value) || f.value.length > MaxFigureValue
        )
      )
    then
      Left(
        s"every figure needs a researcherId of 1 to $MaxFigureSubject characters, a metric key and a value of 1 to $MaxFigureValue characters"
      )
    else if o.claims.exists(c =>
        c.figures.exists(f =>
          f.evidenceIds.isEmpty || f.evidenceIds.size > MaxEvidencePerFigure ||
            f.evidenceIds.exists(id => !c.evidenceIds.contains(id))
        )
      )
    then Left(s"every figure needs 1 to $MaxEvidencePerFigure evidence IDs that its claim cites")
    else if o.claims.exists(c => ClaimFigures.refs(c.text).sorted != c.figures.indices.toVector)
    then Left("every {figure-N} placeholder must name one figure of its claim, each exactly once")
    else Right(o)

  private def sanitized(o: SynthesisOutput): SynthesisOutput =
    val s = UntrustedText.sanitize
    o.copy(
      summary = s(o.summary),
      abstentionReason = o.abstentionReason.map(s),
      claims = o.claims.map(c =>
        c.copy(
          text = s(c.text),
          evidenceIds = c.evidenceIds.distinct,
          uncertainty = c.uncertainty.map(s).filter(_.nonEmpty),
          figures = c.figures.map(f =>
            f.copy(
              researcherId = s(f.researcherId).trim,
              metric = f.metric.trim,
              value = s(f.value).trim,
              evidenceIds = f.evidenceIds.distinct
            )
          )
        )
      )
    )

// ---------------------------------------------------------------- deterministic synthesizer

/** Template synthesizer (`deterministic-synthesis-v1`): every claim is rendered from evidence
  * values only and cites the items it copies them from, so the verifier can check each value; every
  * number is a [[ClaimFigure]] of the item and researcher (or work) it belongs to, so the verified
  * claim text is the same text as before figures existed (the version is unchanged). It answers
  * when no model is configured, when the evidence stage abstains, and as the fallback when model
  * output stays invalid. Retrieved text never enters a claim.
  */
object DeterministicSynthesizer extends Synthesizer:
  val Version = "deterministic-synthesis-v1"
  import QueryOperation.*

  def synthesize(e: EvidenceStageResult): SynthesisResult =
    SynthesisResult(draft(e), Version, "none", None, "none", None, None, Vector.empty, None, None)

  def label(m: CollaborationMetric): String = m match
    case CollaborationMetric.DISTINCT_COAUTHOR_COUNT =>
      "distinct coauthors (collaboration breadth, C_r)"
    case CollaborationMetric.WEIGHTED_DEGREE =>
      "weighted degree (repeated-collaboration intensity, W_r)"
    case CollaborationMetric.PUBLICATION_COUNT           => "publications (P_r)"
    case CollaborationMetric.TOTAL_COAUTHOR_OCCURRENCES  => "coauthor occurrences (T_r)"
    case CollaborationMetric.AVERAGE_COAUTHORS_PER_PAPER =>
      "average coauthors per paper (team size, A_r)"
    case CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER =>
      "most coauthors on one paper (largest team, M_r)"
    case CollaborationMetric.BRIDGE_SCORE =>
      "bridge score (sampled betweenness, structural bridge importance)"
    case CollaborationMetric.PARTICIPATION_COEFFICIENT =>
      "participation coefficient (spread across communities)"
    case CollaborationMetric.CROSS_COMMUNITY_DEGREE => "cross-community coauthors"
    case CollaborationMetric.CORENESS               => "coreness (k-core number)"

  /** Which measures the answer uses, explained once, for the operations of the plan. */
  def explanation(ops: Set[QueryOperation], hasMetrics: Boolean): String =
    Vector(
      Option.when(hasMetrics)(
        "Collaboration breadth is the number of distinct coauthors (C_r), and collaboration intensity is the weighted degree, the joint publications summed over coauthors (W_r)."
      ),
      Option.when(ops(MAX_TEAM_SIZE))(
        "Team size is the number of coauthors on one paper (largest M_r, average A_r)."
      ),
      Option.when(ops(SHORTEST_PATH) || ops(COMPONENT_LOOKUP) || ops(DIRECT_COAUTHORS))(
        "Connectivity is whether and how closely researchers are linked by coauthor edges, paths and components."
      ),
      Option.when(ops(BRIDGE_RANKING) || ops(COMMUNITY_COMPARISON))(
        "Bridge importance is structural (sampled betweenness, participation coefficient, cross-community links), so a researcher with many direct coauthors need not be a bridge."
      )
    ).flatten.mkString(" ")

  /** The figures of one claim under construction: each call records a [[ClaimFigure]] for one
    * evidence item and returns its placeholder.
    */
  private final class Figures:
    private val xs = Vector.newBuilder[ClaimFigure]
    private var n = 0
    def apply(x: EvidenceItem, subject: String, metric: String, value: String): String =
      xs += ClaimFigure(subject, metric, value, Vector(x.evidenceId))
      n += 1
      ClaimFigures.placeholder(n - 1)
    def of(x: EvidenceItem, subject: String, key: String): String =
      apply(x, subject, key, x.values.getOrElse(key, ""))
    def result: Vector[ClaimFigure] = xs.result()

  private final case class Drafted(text: String, ids: Vector[String], figures: Vector[ClaimFigure])

  def draft(e: EvidenceStageResult): SynthesisOutput =
    val b = e.bundle
    val ops = e.plan.operations.toSet
    val det = b.deterministic
    val claims = Vector.newBuilder[Drafted]
    def template(build: Figures => String): (String, Vector[ClaimFigure]) =
      val f = new Figures
      val t = build(f)
      (t, f.result)
    // The template (with placeholders) is what the output rules bound.
    def size(build: Figures => String): Int = template(build)._1.length
    def add(ids: Vector[String])(build: Figures => String): Unit =
      val (text, figures) = template(build)
      if text.length <= SynthesisRules.MaxClaimText then
        val cited = (ids ++ figures.flatMap(_.evidenceIds)).distinct
        claims += Drafted(text, cited.take(SynthesisRules.MaxEvidencePerClaim), figures)
    def v(x: EvidenceItem, k: String) = x.values.getOrElse(k, "")
    def kind(k: String) = det.filter(_.values.get("kind").contains(k))
    def of(t: EvidenceType) = det.filter(_.evidenceType == t)
    val metricsById = kind("researcher-metrics")
      .groupBy(_.researcherIds.head)
      .view
      .mapValues(_.find(x => v(x, "window") == e.window.label))
      .toMap
    def nameOf(id: String) =
      metricsById.get(id).flatten.map(v(_, "displayName")).filter(_.nonEmpty).getOrElse(id)
    def found(x: EvidenceItem) = x.values.get("found").contains("true")
    val pubs = of(EvidenceType.PUBLICATION).flatMap(p => p.workId.map(_ -> p)).toMap
    // A publication's title and, when stored, its year (a figure of the work).
    def publication(f: Figures, p: EvidenceItem): String =
      val year = v(p, "year")
      val title = "\"" + v(p, "title") + "\""
      p.workId.filter(_ => year.trim.nonEmpty).fold(title)(w => s"$title (${f.of(p, w, "year")})")

    e.abstention match
      case Some(reason) =>
        b.identity.foreach { i =>
          val what = v(i, "status") match
            case "AMBIGUOUS" => "is ambiguous: candidate"
            case "RESOLVED"  => "resolved to"
            case _           => "matched"
          val id = i.researcherIds.head
          add(Vector(i.evidenceId))(f =>
            s"'${v(i, "query")}' $what ${v(i, "displayName")} ($id) with identity confidence ${f
                .of(i, id, "identityConfidence")} and ${f.of(i, id, "mentionCount")} source mentions."
          )
        }
        // The candidate claims say why the answer abstains; they cite only identity evidence.
        SynthesisOutput(
          SynthesisRules.SchemaVersion,
          "The answer abstains because researcher identity or evidence is insufficient.",
          true,
          Some(UntrustedText.bound(reason, SynthesisRules.MaxReason)),
          number(claims.result())
        )
      case None =>
        // Rankings, greedily packed into claims of at most MaxClaimText characters.
        kind("ranking").foreach { r =>
          val metric = Try(CollaborationMetric.valueOf(v(r, "metric"))).toOption
          val filter = v(r, "filter")
          val filterText =
            if filter.isEmpty || filter.startsWith("none") then ""
            else s" among ${UntrustedText.bound(filter, 120)}"
          val positions = v(r, "positions").split(';').toVector.filter(_.nonEmpty).flatMap { p =>
            p.split(':').toList match
              case rank :: rest =>
                val idValue = rest.mkString(":")
                val cut = idValue.lastIndexOf('=')
                Option.when(cut > 0)((rank, idValue.take(cut), idValue.drop(cut + 1)))
              case _ => None
          }
          val head =
            s"Ranked by ${metric.fold(v(r, "metric"))(label)} in window ${v(r, "window")}$filterText: "
          def text(f: Figures, ps: Vector[(String, String, String)]) =
            head + ps
              .map((k, id, value) =>
                s"${f(r, id, "rank", k)}. ${nameOf(id)} (${f(r, id, v(r, "metric"), value)})"
              )
              .mkString(", ")
          var chunk = Vector.empty[(String, String, String)]
          def flush(): Unit =
            if chunk.nonEmpty then
              val ps = chunk
              add(
                r.evidenceId +: ps.flatMap((_, id, _) =>
                  metricsById.get(id).flatten.map(_.evidenceId)
                )
              )(f => text(f, ps) + ".")
              chunk = Vector.empty
          positions.foreach { p =>
            val next = chunk :+ p
            if size(text(_, next)) > SynthesisRules.MaxClaimText - 5 || next.size > 8 then
              flush()
              chunk = Vector(p)
            else chunk = next
          }
          flush()
        }
        // Paths and their hops.
        of(EvidenceType.GRAPH_PATH).foreach { p =>
          val from = v(p, "from")
          if found(p) then
            val hops = v(p, "hopEvidenceIds").split(';').toVector.filter(_.nonEmpty)
            add(p.evidenceId +: hops)(f =>
              s"A shortest coauthorship path in window ${v(p, "window")} has ${f.of(p, from, "hops")} hops: ${v(p, "researcherNames")}."
            )
            hops.flatMap(b.byId.get).foreach { h =>
              val pub = v(h, "supportingWorkIds").split(';').headOption.flatMap(pubs.get)
              add(h.evidenceId +: pub.map(_.evidenceId).toVector)(f =>
                s"${coauthored(f, h)}${pub.fold("")(x => s", including ${publication(f, x)}")}."
              )
            }
          else
            add(Vector(p.evidenceId))(f =>
              s"No coauthorship path of at most ${f.of(p, from, "maxDepth")} hops connects ${v(p, "fromName")} and ${v(p, "toName")} in window ${v(p, "window")}."
            )
        }
        // Direct collaboration of the named researchers.
        val named = e.resolutions.flatMap(_.researcherId)
        if ops(DIRECT_COAUTHORS) then
          val edges = of(EvidenceType.COAUTHOR_EDGE)
          val pair = Option
            .when(named.size >= 2)(Ids.orderedPair(named(0), named(1)))
            .flatMap((x, y) => edges.find(_.researcherIds == Vector(x, y)))
          pair.foreach { x =>
            val a = v(x, "researcherA")
            if found(x) then
              add(Vector(x.evidenceId))(f =>
                s"${v(x, "nameA")} and ${v(x, "nameB")} are direct coauthors in window ${v(x, "window")}, with ${f
                    .of(x, a, "jointPublicationCount")} joint publications, first in ${f
                    .of(x, a, "firstCollaborationYear")} and last in ${f.of(x, a, "lastCollaborationYear")}."
              )
              // The edge's supporting publications, packed into claims that cite edge and works.
              val support = v(x, "supportingWorkIds").split(';').toVector.flatMap(pubs.get)
              val head =
                s"Publications supporting the coauthor edge of ${v(x, "nameA")} and ${v(x, "nameB")} in window ${v(x, "window")}: "
              def text(f: Figures, ps: Vector[EvidenceItem]) =
                head + ps.map(publication(f, _)).mkString("; ") + "."
              def emit(ps: Vector[EvidenceItem]) =
                if ps.nonEmpty then add(x.evidenceId +: ps.map(_.evidenceId))(text(_, ps))
              var chunk = Vector.empty[EvidenceItem]
              support.foreach { p =>
                val next = chunk :+ p
                if size(text(_, next)) > SynthesisRules.MaxClaimText ||
                  next.size >= SynthesisRules.MaxEvidencePerClaim
                then
                  emit(chunk)
                  chunk = Vector(p)
                else chunk = next
              }
              emit(chunk)
            else
              add(Vector(x.evidenceId))(_ =>
                s"${v(x, "nameA")} and ${v(x, "nameB")} have no direct coauthor edge in window ${v(x, "window")}."
              )
          }
          named.take(2).foreach { id =>
            val list = edges
              .filter(x => found(x) && x.researcherIds.contains(id) && !pair.contains(x))
              .sortBy(x => (-BigDecimal(v(x, "jointPublicationCount")), other(x, id)))
            val head =
              s"Direct coauthors of ${nameOf(id)} in window ${e.window.label} by joint publications: "
            def text(f: Figures, xs: Vector[EvidenceItem]) =
              head + xs
                .map { x =>
                  val o = other(x, id)
                  val n = if v(x, "researcherA") == o then v(x, "nameA") else v(x, "nameB")
                  s"$n (${f.of(x, id, "jointPublicationCount")})"
                }
                .mkString(", ")
            var taken = Vector.empty[EvidenceItem]
            list.foreach { x =>
              if taken.size < 8 && size(text(_, taken :+ x)) < 490 then taken :+= x
            }
            if taken.nonEmpty then
              val xs = taken
              add(xs.map(_.evidenceId))(f => text(f, xs) + ".")
          }
        // Per-researcher measures, in bundle order (named, ranked, temporal).
        kind("researcher-metrics").foreach { m =>
          val name = v(m, "displayName")
          val w = v(m, "window")
          val id = m.researcherIds.head
          add(Vector(m.evidenceId))(f =>
            s"$name has ${f.of(m, id, "distinctCoauthorCount")} distinct coauthors (collaboration breadth) across ${f
                .of(m, id, "publicationCount")} publications in window $w, with weighted degree ${f
                .of(m, id, "weightedDegree")} (repeated-collaboration intensity) and ${f
                .of(m, id, "totalCoauthorOccurrences")} coauthor occurrences."
          )
          val teamMetric = Set(
            CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER,
            CollaborationMetric.AVERAGE_COAUTHORS_PER_PAPER
          )(e.plan.rankingMetric)
          if ops(MAX_TEAM_SIZE) || teamMetric then
            val team = v(m, "maximumTeamWorkIds").split(';').toVector.flatMap(pubs.get)
            val titles =
              if team.isEmpty then ""
              else " (" + team.take(2).map(p => "\"" + v(p, "title") + "\"").mkString(", ") + ")"
            add(m.evidenceId +: team.take(2).map(_.evidenceId))(f =>
              s"$name's largest author team in window $w had ${f
                  .of(m, id, "maximumCoauthorsOnOnePaper")} coauthors on one paper$titles; the average is ${f
                  .of(m, id, "averageCoauthorsPerPaper")} coauthors per paper."
            )
          if ops(BRIDGE_RANKING) || ops(COMMUNITY_COMPARISON) then
            if m.values.contains("bridgeScore") then
              add(Vector(m.evidenceId))(f =>
                s"$name's bridge importance in window $w: bridge score ${f
                    .of(m, id, "bridgeScore")} (sampled betweenness), participation coefficient ${f
                    .of(m, id, "participationCoefficient")}, ${f.of(m, id, "crossCommunityDegree")} cross-community coauthors, touching ${f
                    .of(m, id, "communitiesConnected")} communities, its own included."
              )
            else add(Vector(m.evidenceId))(_ => s"$name has no stored bridge score in window $w.")
          if ops(COMPONENT_LOOKUP) || e.plan.rankingMetric == CollaborationMetric.CORENESS then
            add(Vector(m.evidenceId))(f =>
              val core =
                if m.values.contains("coreness") then s", with coreness ${f.of(m, id, "coreness")}"
                else ""
              s"$name is in connected component ${v(m, "componentId")} of ${f
                  .of(m, id, "componentSize")} researchers in window $w$core."
            )
        }
        // Communities: membership and cross-community links.
        kind("community-membership").foreach { c =>
          val near = v(c, "neighbouringCommunitiesOnRetrievedEdges").split(';').filter(_.nonEmpty)
          val community = v(c, "communityId")
          if community.nonEmpty then
            add(Vector(c.evidenceId))(_ =>
              s"${v(c, "displayName")} is in community $community (weighted label propagation) in window ${v(c, "window")}; neighbouring communities on the retrieved coauthor edges: ${
                  if near.isEmpty then "none" else near.mkString(", ")
                }."
            )
        }
        kind("cross-community-link").foreach { x =>
          val id = v(x, "researcherId")
          add(Vector(x.evidenceId) ++ x.values.get("edgeEvidenceId").filter(b.byId.contains))(f =>
            s"${nameOf(id)}'s coauthor ${v(x, "coauthorName")} is in another community (${v(x, "otherCommunityId")}, not ${v(x, "ownCommunityId")}); joint publications in window ${v(x, "window")}: ${f
                .of(x, id, "jointPublicationCount")}."
          )
        }
        val structural = claims.result()
        // Only descriptive context: say where it came from, never what it claims.
        val descriptive =
          if structural.nonEmpty then Vector.empty
          else
            b.semantic.take(3).map { s =>
              Drafted(
                s"Descriptive context (untrusted retrieved text) from the ${v(s, "documentType")} index about ${v(s, "entityId")}; it supports no graph value.",
                Vector(s.evidenceId),
                Vector.empty
              )
            }
        val all = number(structural ++ descriptive)
        val ranking = kind("ranking").headOption
        val summary =
          val lead = ranking match
            case Some(r) =>
              val names = r.researcherIds.take(10).map(nameOf)
              s"Researchers ranked by ${Try(CollaborationMetric.valueOf(v(r, "metric"))).toOption.fold(v(r, "metric"))(label)} in window ${v(r, "window")}: ${names.mkString(", ")}."
            case None if all.nonEmpty =>
              "Collaboration evidence follows; every figure cites the stored record or graph query it comes from."
            case None => "No stored evidence answers the question."
          val text = (lead + " " + explanation(ops, metricsById.nonEmpty)).trim
          if text.length <= SynthesisRules.MaxSummary then text
          else explanation(ops, metricsById.nonEmpty).take(SynthesisRules.MaxSummary)
        SynthesisOutput(
          SynthesisRules.SchemaVersion,
          summary,
          all.isEmpty,
          Option.when(all.isEmpty)(
            "no deterministic evidence or descriptive context answers the question"
          ),
          all
        )

  private def number(cs: Vector[Drafted]): Vector[DraftClaim] =
    cs.take(SynthesisRules.MaxClaims).zipWithIndex.map { (d, i) =>
      DraftClaim(s"claim-${i + 1}", d.text, d.ids, None, d.figures)
    }

  private def other(x: EvidenceItem, id: String): String =
    if x.values.get("researcherA").contains(id) then x.values.getOrElse("researcherB", "")
    else x.values.getOrElse("researcherA", "")

  /** A hop's joint publications and years, as figures of the edge for its first endpoint. */
  private def coauthored(f: Figures, h: EvidenceItem): String =
    def v(k: String) = h.values.getOrElse(k, "")
    val a = v("researcherA")
    s"${v("nameA")} and ${v("nameB")} coauthored ${f.of(h, a, "jointPublicationCount")} joint publications in window ${v("window")}, first in ${f
        .of(h, a, "firstCollaborationYear")} and last in ${f.of(h, a, "lastCollaborationYear")}"

// ---------------------------------------------------------------- prompt and context

/** The versioned synthesis prompt (`prompts/runtime/answer-synthesis-v2.md`). Its first line names
  * the version; its placeholders are exactly [[SynthesisPrompt.Placeholders]]; `sha256` is of the
  * file text and is recorded in the audit with the version.
  */
final case class SynthesisPrompt(version: String, template: String, sha256: String):
  /** Fills the placeholders in one pass, so data that contains a placeholder name stays data. */
  def render(values: Map[String, String]): String =
    SynthesisPrompt.Placeholder.replaceAllIn(
      template,
      m => scala.util.matching.Regex.quoteReplacement(values.getOrElse(m.group(1), ""))
    )

object SynthesisPrompt:
  val Version = "answer-synthesis-v2"
  val File = "prompts/runtime/answer-synthesis-v2.md"
  val Placeholders: Set[String] = Set("BOUNDARY", "REQUEST", "NOTES", "EVIDENCE", "FEEDBACK")
  private val Placeholder = "\\{\\{([A-Z]+)\\}\\}".r

  def parse(text: String): Either[String, SynthesisPrompt] =
    val found = Placeholder.findAllMatchIn(text).map(_.group(1)).toSet
    if !text.linesIterator.nextOption().contains(s"prompt-version: $Version") then
      Left(s"the prompt's first line must be 'prompt-version: $Version'")
    else if found != Placeholders then
      Left(
        s"the prompt's placeholders are ${found.toVector.sorted.mkString(",")}, not ${Placeholders.toVector.sorted.mkString(",")}"
      )
    else Right(SynthesisPrompt(Version, text, Hashing.sha256Hex(text)))

  /** Reads the prompt from a checkout or image that holds `prompts/`. */
  def load(root: Path): Either[String, SynthesisPrompt] =
    Try(new String(Files.readAllBytes(root.resolve(File)), UTF_8)).toEither.left
      .map(e => s"cannot read $File: ${e.getClass.getSimpleName}")
      .flatMap(parse)

/** Rough token count of prompt text: one token per 3 UTF-16 units, rounded up. Deliberately
  * pessimistic (English text averages about 4), so a budget is not overrun by the estimate.
  */
object Tokens:
  def estimate(s: String): Int = (s.length + 2) / 3

/** The prompt's data sections under the context budget (`context-packer-v1`).
  *
  * Items go in by priority: deterministic graph and metric evidence in bundle order, then identity
  * evidence, then semantic context. Each item is one JSON object, so all retrieved text is a JSON
  * string inside the DATA fence. Semantic text is sanitized again and bounded to 2,000 units. The
  * packed items are a prefix of that order: from the first item that does not fit the remaining
  * budget on, every item is left out and listed, so lower-priority text never displaces graph
  * evidence.
  */
object ContextPacker:
  val Version = "context-packer-v1"
  val MaxSemanticChars = 2000

  final case class Packed(
      request: String,
      notes: String,
      evidence: String,
      use: ContextUse
  ):
    def boundary: String = Hashing.short(s"$request|$notes|$evidence", 16)

  def item(e: EvidenceItem, partition: String): ujson.Obj =
    val o = ujson.Obj(
      "id" -> e.evidenceId,
      "type" -> e.evidenceType.toString,
      "partition" -> partition
    )
    if e.researcherIds.nonEmpty then o("researcherIds") = ujson.Arr.from(e.researcherIds)
    e.workId.foreach(w => o("workId") = w)
    if partition == "semantic" then
      o("trust") = SemanticContext.Trust
      o("score") = e.score
      Vector("documentType", "entityId", "window").foreach(k =>
        e.values.get(k).foreach(x => o(k) = x)
      )
      o("text") =
        UntrustedText.bound(UntrustedText.sanitize(e.text.getOrElse("")), MaxSemanticChars)
    else
      o("values") = ujson.Obj.from(e.values.toVector.sortBy(_._1).map((k, x) => k -> ujson.Str(x)))
    o

  def pack(e: EvidenceStageResult, budgetTokens: Int): Packed =
    val p = e.plan
    val request = ujson
      .Obj(
        "question" -> p.question,
        "operations" -> ujson.Arr.from(p.operations.map(_.toString)),
        "requestedWindow" -> p.window.label,
        "answeredWindow" -> e.window.label,
        "rankingMetric" -> p.rankingMetric.toString,
        "limit" -> p.limit
      )
      .render()
    val notes = ujson.Arr
      .from(e.notes.distinct.take(20).map(n => UntrustedText.sanitize(n).take(400)))
      .render()
    val candidates =
      e.bundle.deterministic.map(item(_, "deterministic")) ++
        e.bundle.identity.map(item(_, "identity")) ++
        e.bundle.semantic.map(item(_, "semantic"))
    var used = Tokens.estimate(request) + Tokens.estimate(notes)
    val kept = Vector.newBuilder[ujson.Obj]
    val omitted = Vector.newBuilder[String]
    var full = false
    candidates.foreach { o =>
      val t = Tokens.estimate(o.render()) + 1
      if !full && used + t <= budgetTokens then
        used += t
        kept += o
      else
        full = true
        omitted += o("id").str
    }
    val k = kept.result()
    Packed(
      request,
      notes,
      ujson.Arr.from(k).render(),
      ContextUse(budgetTokens, used, k.size, omitted.result())
    )

// ---------------------------------------------------------------- model synthesizer

/** Bounds of the model path. `maxAttempts` is also capped by `operations.maxWorkflowRetries + 1`.
  */
final case class SynthesisLimits(
    maxAttempts: Int = 3,
    maxOutputTokensPerAttempt: Int = 1500,
    minOutputTokens: Int = 200
)

/** Model synthesizer (`llm-synthesis-v1`) behind [[LlmPort]].
  *
  * Budgets: the prompt's data sections stay within `retrieval.contextTokenBudget` (estimated
  * tokens, [[ContextPacker]]); the model's output tokens summed over all attempts stay within
  * `operations.maxTokensPerQuery` (reported usage, or the estimate when the port reports none). A
  * reply is used only after [[SynthesisRules.validate]] accepts it. A refused reply is retried with
  * the validator's fixed reason as feedback, at most `maxAttempts` times. The deterministic
  * synthesizer answers instead (and the audit says why) when the evidence stage abstained (no call
  * is made), the call fails, the attempts or the token budget run out, or the model abstains
  * although deterministic evidence exists.
  */
final class LlmSynthesizer(
    llm: LlmPort,
    prompt: SynthesisPrompt,
    cfg: ScholarChainConfig,
    limits: SynthesisLimits = SynthesisLimits()
) extends Synthesizer:
  private val attemptsAllowed =
    math.max(1, math.min(limits.maxAttempts, cfg.operations.maxWorkflowRetries + 1))

  def synthesize(e: EvidenceStageResult): SynthesisResult =
    if e.abstention.nonEmpty then
      DeterministicSynthesizer
        .synthesize(e)
        .copy(fallback = Some("the evidence stage abstained; no model call"))
    else
      val packed = ContextPacker.pack(e, cfg.retrieval.contextTokenBudget)
      val attempts = Vector.newBuilder[SynthesisAttempt]
      var inTok, outTok = 0L
      var reported = false

      def result(o: SynthesisOutput, by: String, fallback: Option[String]) =
        SynthesisResult(
          o,
          by,
          prompt.version,
          Some(prompt.sha256),
          llm.modelVersion,
          Option.when(reported)(inTok),
          Option.when(reported)(outTok),
          attempts.result(),
          fallback,
          Some(packed.use)
        )
      def fallBack(reason: String) =
        result(DeterministicSynthesizer.draft(e), DeterministicSynthesizer.Version, Some(reason))

      def loop(n: Int, feedback: String): SynthesisResult =
        val remaining = cfg.operations.maxTokensPerQuery.toLong - outTok
        if n > attemptsAllowed then fallBack(s"model output refused $attemptsAllowed times")
        else if remaining < limits.minOutputTokens then
          fallBack(
            s"output token budget of ${cfg.operations.maxTokensPerQuery} per query exhausted"
          )
        else
          val maxOut = math.min(limits.maxOutputTokensPerAttempt.toLong, remaining).toInt
          val text = prompt.render(
            Map(
              "BOUNDARY" -> packed.boundary,
              "REQUEST" -> packed.request,
              "NOTES" -> packed.notes,
              "EVIDENCE" -> packed.evidence,
              "FEEDBACK" -> feedback
            )
          )
          llm.complete(text, maxOut) match
            case Left(err) =>
              attempts += SynthesisAttempt(
                n,
                "call-failed",
                Some(err.take(200)),
                None,
                maxOut,
                None,
                None
              )
              fallBack(s"model call failed: ${err.take(200)}")
            case Right(reply) =>
              reported = reported || reply.inputTokens.nonEmpty || reply.outputTokens.nonEmpty
              val ti = reply.inputTokens.getOrElse(Tokens.estimate(text).toLong)
              val to = reply.outputTokens.getOrElse(Tokens.estimate(reply.text).toLong)
              inTok += ti
              outTok += to
              val hash = Some(Hashing.tagged(reply.text))
              SynthesisRules.validate(reply.text) match
                case Left(reason) =>
                  attempts += SynthesisAttempt(
                    n,
                    "refused",
                    Some(reason),
                    hash,
                    maxOut,
                    Some(ti),
                    Some(to)
                  )
                  loop(
                    n + 1,
                    s"Your previous reply was refused: $reason. Reply again with exactly one JSON object that matches answer-synthesis-v2."
                  )
                case Right(o) if o.abstain && e.bundle.deterministic.nonEmpty =>
                  attempts += SynthesisAttempt(
                    n,
                    "abstained",
                    None,
                    hash,
                    maxOut,
                    Some(ti),
                    Some(to)
                  )
                  fallBack("the model abstained although deterministic evidence exists")
                case Right(o) =>
                  attempts += SynthesisAttempt(
                    n,
                    "accepted",
                    None,
                    hash,
                    maxOut,
                    Some(ti),
                    Some(to)
                  )
                  result(o, LlmSynthesizer.Version, None)
      loop(1, "")

object LlmSynthesizer:
  val Version = "llm-synthesis-v1"

/** Chooses the synthesizer from configuration: the model path only when `models.llmEnabled` is
  * true, and then both a model port and the prompt are required.
  */
object Synthesizers:
  def select(
      cfg: ScholarChainConfig,
      llm: Option[LlmPort],
      prompt: Option[SynthesisPrompt]
  ): Either[String, Synthesizer] =
    if !cfg.models.llmEnabled then Right(DeterministicSynthesizer)
    else
      (llm, prompt) match
        case (Some(l), Some(p)) => Right(new LlmSynthesizer(l, p, cfg))
        case _ => Left("models.llmEnabled is true but no model port or synthesis prompt was given")
