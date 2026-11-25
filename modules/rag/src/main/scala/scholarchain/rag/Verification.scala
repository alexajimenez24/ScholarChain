package scholarchain.rag

import scholarchain.core.*
import upickle.default.ReadWriter
import java.util.Locale
import scala.util.Try
import scala.util.matching.Regex

/** The verifier's verdict on one claim, for the answer and the audit. `facts` counts the checkable
  * statements found in the text, `supported` those the cited evidence supports.
  */
final case class ClaimCheck(
    claimId: String,
    status: VerificationStatus,
    reasons: Vector[String],
    facts: Int,
    supported: Int,
    missingEvidenceIds: Vector[String]
) derives ReadWriter

/** A check plus what the answer stage needs: `text`, the claim text with every figure rendered from
  * the evidence, the character spans of its unsupported statements, and the cited evidence IDs that
  * exist.
  */
final case class Verdict(
    check: ClaimCheck,
    text: String,
    unsupportedSpans: Vector[(Int, Int)],
    existingEvidenceIds: Vector[String]
):
  /** `text` with every unsupported span replaced by `[unverified]`. */
  def weakened: String =
    val merged = unsupportedSpans.sortBy(_._1).foldLeft(Vector.empty[(Int, Int)]) {
      case (acc :+ last, (s, e)) if s <= last._2 => acc :+ (last._1, math.max(last._2, e))
      case (acc, span)                           => acc :+ span
    }
    merged.reverse.foldLeft(text)((t, span) =>
      t.substring(0, span._1) + ClaimVerifier.Unverified + t.substring(span._2)
    )

/** ClaimVerificationAgent (`claim-verifier-v3`): deterministic and independent of the synthesizer.
  *
  * Only cited evidence that exists in the bundle supports a claim. Graph and metric evidence,
  * PUBLICATION and IDENTITY_RESOLUTION records support values; semantic context supports only
  * descriptive names and identifiers, never a number, window, hop, path or edge statement or
  * ranking order (`graph-value-policy-v1`).
  *
  * Construction rule (v3): a model can make a VERIFIED statement only by construction, never by
  * prose. Every number of a claim is a [[ClaimFigure]], an explicit (researcherId, metric, value,
  * evidenceIds) tuple whose placeholder `{figure-N}` stands in the text; a ranking position is the
  * tuple (researcherId, `rank`, position, evidenceIds) on a cited ranking. A figure is checked
  * against the items it cites (among those its claim cites, every one of them in the bundle), and
  * an item backs it only for the researcher and metric at that position:
  *   - a researcher-metrics row, community membership or identity record: its own researcher;
  *   - a ranking: the researcher at a position of `positions`, for the ranking's metric (or
  *     `rank`);
  *   - a found coauthor edge or a cross-community link: its two researchers;
  *   - a path: its two endpoints; a publication: its work ID (the year).
  * The metric names a numeric key of the item's values (case, `_` and a `degree.` or `bridge.`
  * prefix ignored). An equal value verifies; a value that differs from the held one is CONFLICTING;
  * no item holding that metric for that researcher, or a cited ID not in the bundle, is
  * UNSUPPORTED.
  *
  * Model output ([[structured]], and [[verify]] / [[verifyText]] with `model`, the default):
  *   - each figure is a structured claim ([[structured]]) whose whole text is written here from the
  *     evidence ("metric of name (id) in window W: value."), so it holds no model prose; it is the
  *     only model statement that can be VERIFIED;
  *   - the claim text, the uncertainty note and the summary are free text: whatever they contain,
  *     they are an "UNVERIFIED interpretation" and never VERIFIED. No number, number word or rank
  *     phrase is looked for in them; each figure in the text is rendered as "value (metric of
  *     researcher, window)" or `[unverified]`, and the checks below can still make the text
  *     CONFLICTING or UNSUPPORTED (it is then removed) or weaken a span.
  * The deterministic synthesizer's text (`model = false`) is code built from the evidence: its
  * figures render as bare values, every other ASCII number in it is unsupported (step 7), and a
  * claim whose every fact holds is VERIFIED as a whole.
  *
  * The rendered text is read in this order, each span once (figures already masked):
  *   1. quoted publication titles: equal (after title normalization) to a cited PUBLICATION title;
  *   2. researcher names known to the bundle: the cited evidence names that researcher;
  *   3. identifiers (`scheme:...`, run IDs): present in the cited evidence;
  *   4. path hops `A -> B`: adjacent on a cited found path or a cited found coauthor edge;
  *   5. time windows `YYYY-YYYY`: a window of the cited evidence;
  *   6. path and edge statements ("no coauthorship path", "are direct coauthors"): agree with the
  *      cited `found` flag;
  *   7. deterministic text only: every remaining ASCII number is unsupported (a cited identity
  *      record's quoted query `'q'` and a cited ranking's filter text are data, not numbers);
  *   8. capitalized multi-word names not found in the bundle;
  *   9. ranking order: with one cited ranking, the named researchers follow its order.
  *
  * Status: no cited evidence exists, UNSUPPORTED; a fact contradicts the evidence, CONFLICTING;
  * semantic-only citations with a graph value, UNSUPPORTED; facts but none supported, UNSUPPORTED;
  * model text, some facts unsupported, a cited ID missing, only descriptive context or no checkable
  * value, PARTIALLY_SUPPORTED; otherwise VERIFIED.
  */
object ClaimVerifier:
  val Version = "claim-verifier-v3"
  val Unverified = "[unverified]"

  /** The label of every free-text claim, note and summary a model wrote. */
  val Interpretation = "UNVERIFIED interpretation"
  private val InterpretationReason =
    s"$Interpretation: free text from the model is never verified; only its figures, checked as structured claims, are"

  /** The claim ID of the structured claim made of figure `index` (from 0) of claim `claimId`. */
  def structuredId(claimId: String, index: Int): String = s"$claimId-figure-${index + 1}"

  private enum Kind:
    case Title, Name, Id, Hop, Window, Statement, Figure, Number, Order
  private enum Outcome:
    case Supported, Unsupported, Conflicting
  private final case class Fact(kind: Kind, span: (Int, Int), outcome: Outcome, reason: String)

  /** What one cited item holds for a figure: the stored value, the label naming its owner (for the
    * value shown in model prose) and `about`, the same with researcher IDs (for the structured
    * claim's text).
    */
  private final case class Held(value: String, label: String, about: String):
    def statement: String = s"$about: $value."

  private val GraphMaterial =
    Set(Kind.Hop, Kind.Window, Kind.Statement, Kind.Figure, Kind.Number, Kind.Order)
  private val Quoted = "\"([^\"]{2,300})\"|\u201c([^\u201d]{2,300})\u201d".r
  val IdPattern: Regex =
    "(?<![\\w\\-])(?:(?:[a-z][a-z0-9]{0,15}:)+[^\\s,;()\\[\\]\"'=<>]+|run-[0-9a-f]{16})".r
  private val Arrow = "->|\u2192".r
  private val WindowPattern = "(?<!\\d)(\\d{4})\\s*[-\u2013]\\s*(\\d{4})(?!\\d)".r

  /** An ASCII number in the deterministic synthesizer's text (step 7). */
  private val NumberPattern = "(?<![\\w.])(\\d+(?:\\.\\d+)?)(?!\\w|\\.\\d)".r
  private val Capitalized = "\\p{Lu}[\\p{L}'\u2019\\-]*(?:\\s+\\p{Lu}[\\p{L}'\u2019\\-]*)+".r
  private val NoPath = "(?i)\\bno (?:coauthorship |collaboration )?path\\b|\\bnot connected\\b".r
  private val HasPath =
    "(?i)\\bshortest (?:coauthorship )?path\\b|\\bpath (?:of|with) \\d+ hops?\\b".r
  private val NoEdge =
    "(?i)\\bno direct coauthor\\b|\\bnot (?:direct )?coauthors\\b|\\bnever coauthored\\b|\\bhave not (?:collaborated|coauthored)\\b|\\bno coauthor edge\\b".r
  private val HasEdge =
    "(?i)\\bare direct coauthors\\b|\\bhave coauthored\\b|\\bcoauthored \\d+ joint publications?\\b".r
  private val Stop = Set(
    "The",
    "A",
    "An",
    "In",
    "On",
    "At",
    "By",
    "For",
    "From",
    "No",
    "Most",
    "Top",
    "Ranked",
    "Researchers",
    "Researcher",
    "Direct",
    "Each",
    "Every",
    "This",
    "These",
    "Both",
    "Among",
    "Between",
    "Shortest",
    "Candidate",
    "Collaboration",
    "Bridge",
    "Descriptive",
    "Missing",
    "Evidence",
    "Window",
    "Their",
    "Its",
    "Of",
    "And",
    "Or",
    "With",
    "However",
    "Also",
    "Overall",
    "Note",
    "Community",
    "Communities",
    "Connected",
    "Component",
    "Coauthors",
    "Team",
    "Path"
  )

  private def semantic(e: EvidenceItem) = GraphValuePolicy.Descriptive(e.evidenceType)
  private def found(e: EvidenceItem) = e.values.get("found")
  private def dec(s: String) = Try(BigDecimal(s.trim)).toOption
  private def same(a: String, b: String) = (dec(a), dec(b)) match
    case (Some(x), Some(y)) => x.compare(y) == 0
    case _                  => a.trim == b.trim
  private def kindOf(e: EvidenceItem) = e.values.getOrElse("kind", "")

  /** A metric or value key without case, punctuation or a `degree.` / `bridge.` prefix. */
  def metricKey(s: String): String =
    val t = s.trim.toLowerCase(Locale.ROOT)
    Vector("degree.", "bridge.").foldLeft(t)((a, p) => a.stripPrefix(p)).filter(_.isLetterOrDigit)

  /** A ranking's positions `rank:researcherId=value`, in order. */
  def rankingPositions(e: EvidenceItem): Vector[(String, String, String)] =
    e.values.getOrElse("positions", "").split(';').toVector.filter(_.nonEmpty).flatMap { p =>
      p.split(':').toList match
        case rank :: rest =>
          val idValue = rest.mkString(":")
          val cut = idValue.lastIndexOf('=')
          Option.when(cut > 0)((rank, idValue.take(cut), idValue.drop(cut + 1)))
        case _ => None
    }

  /** Researcher names the bundle knows, longest first, each with its researcher IDs. */
  def knownNames(b: EvidenceBundle): Vector[(String, Set[String])] =
    def of(e: EvidenceItem): Vector[(String, String)] =
      val v = e.values
      def pair(id: String, name: String) = v.get(id).zip(v.get(name)).toVector
      e.evidenceType match
        case EvidenceType.METRIC_RECORD | EvidenceType.IDENTITY_RESOLUTION =>
          e.researcherIds.headOption.zip(v.get("displayName")).toVector
        case EvidenceType.COAUTHOR_EDGE =>
          pair("researcherA", "nameA") ++ pair("researcherB", "nameB")
        case EvidenceType.GRAPH_PATH =>
          pair("from", "fromName") ++ pair("to", "toName") ++
            e.graphPath.toVector.flatMap(ids =>
              v.get("researcherNames").toVector.flatMap { n =>
                val names = n.split(" -> ").toVector
                if names.size == ids.size then ids.zip(names) else Vector.empty
              }
            )
        case EvidenceType.COMMUNITY_RECORD =>
          pair("researcherId", "displayName") ++ pair("coauthorId", "coauthorName")
        case _ => Vector.empty
    (b.deterministic ++ b.identity)
      .flatMap(of)
      .collect { case (id, n) if n.trim.length >= 3 && n != id => n.trim -> id }
      .groupBy(_._1)
      .map((n, xs) => n -> xs.map(_._2).toSet)
      .toVector
      .sortBy((n, _) => (-n.length, n))

  /** The value `e` holds for `metric` of `subject`, with labels naming the owner, or None when `e`
    * holds no such value for that subject (the positional rule of the class comment).
    */
  private def held(
      e: EvidenceItem,
      subject: String,
      metric: String,
      name: String => String
  ): Option[Held] =
    val v = e.values
    val want = metricKey(metric)
    val w = v.get("window").filter(_.nonEmpty)
    val win = w.fold("")(x => s", window $x")
    val inWin = w.fold("")(x => s" in window $x")
    def ident(id: String) = if name(id) == id then id else s"${name(id)} ($id)"
    def get(k: String) = v.get(k).filter(_.nonEmpty)
    def field: Option[(String, String)] =
      v.toVector.sortBy(_._1).find((k, x) => metricKey(k) == want && dec(x).isDefined)
    def pair(a: Option[String], b: Option[String]) =
      Option
        .when(a.contains(subject) || b.contains(subject))(())
        .flatMap(_ => field)
        .map((k, x) =>
          Held(
            x,
            s"$k of ${a.fold("?")(name)} and ${b.fold("?")(name)}$win",
            s"$k of ${a.fold("?")(ident)} and ${b.fold("?")(ident)}$inWin"
          )
        )
    val identity = e.evidenceType == EvidenceType.IDENTITY_RESOLUTION
    val metricsRow =
      e.evidenceType == EvidenceType.METRIC_RECORD && kindOf(e) == "researcher-metrics"
    val membership =
      e.evidenceType == EvidenceType.COMMUNITY_RECORD && kindOf(e) == "community-membership"
    val ownRecord =
      ((identity || metricsRow) && e.researcherIds.headOption.contains(subject)) ||
        (membership && get("researcherId").contains(subject))
    e.evidenceType match
      case _ if ownRecord =>
        field.map((k, x) =>
          if identity then
            val who = s"candidate ${name(subject)} ($subject)"
            Held(x, s"$k of $who$win", s"$k of $who$inWin")
          else Held(x, s"$k of ${name(subject)}$win", s"$k of ${ident(subject)}$inWin")
        )
      case EvidenceType.METRIC_RECORD if kindOf(e) == "ranking" =>
        val by = get("metricField").orElse(get("metric")).getOrElse("the metric")
        rankingPositions(e).find(_._2 == subject).flatMap { (rank, _, value) =>
          if want == "rank" then
            Option.when(dec(rank).isDefined)(
              Held(
                rank,
                s"rank of ${name(subject)} by $by$win",
                s"rank of ${ident(subject)} by $by$inWin"
              )
            )
          else
            Option.when(
              (get("metric").map(metricKey) ++ get("metricField").map(metricKey)).exists(
                _ == want
              ) && dec(value).isDefined
            )(
              Held(
                value,
                s"$by of ${name(subject)} in the ranking$win",
                s"$by of ${ident(subject)} in the ranking$inWin"
              )
            )
        }
      case EvidenceType.COMMUNITY_RECORD if kindOf(e) == "cross-community-link" =>
        pair(get("researcherId"), get("coauthorId"))
      case EvidenceType.COAUTHOR_EDGE if found(e).contains("true") =>
        pair(get("researcherA"), get("researcherB"))
      case EvidenceType.GRAPH_PATH
          if get("from").contains(subject) || get("to").contains(subject) =>
        def ends(f: String => String) =
          s"the path from ${get("from").fold("?")(f)} to ${get("to").fold("?")(f)}"
        field.map((k, x) => Held(x, s"$k of ${ends(name)}$win", s"$k of ${ends(ident)}$inWin"))
      case EvidenceType.PUBLICATION if e.workId.contains(subject) =>
        field.map((k, x) => Held(x, s"$k of work $subject", s"$k of work $subject"))
      case _ => None

  /** A figure checked against the items it cites among `claimIds`: Supported with the items that
    * hold the claimed value, Conflicting when a cited item holds another value, Unsupported when no
    * cited item holds that metric for that researcher or a cited ID is not in the bundle.
    */
  private final case class FigureCheck(
      outcome: Outcome,
      matches: Vector[(EvidenceItem, Held)],
      reason: String
  )

  private def checkFigure(
      i: Int,
      f: ClaimFigure,
      claimIds: Vector[String],
      bundle: EvidenceBundle,
      name: String => String
  ): FigureCheck =
    val index = bundle.byId
    val ids = f.evidenceIds.distinct.filter(claimIds.contains)
    val missing = ids.filterNot(index.contains)
    val items = ids.flatMap(index.get).filterNot(semantic)
    val hs = items.flatMap(x => held(x, f.researcherId, f.metric, name).map(x -> _))
    val claimed = f.value.trim
    val what = s"figure ${i + 1} (${f.metric.take(64)} of ${f.researcherId.take(80)})"
    val matches = hs.filter((_, h) => same(h.value, claimed))
    if matches.nonEmpty && missing.isEmpty then FigureCheck(Outcome.Supported, matches, "")
    else if matches.nonEmpty then
      FigureCheck(
        Outcome.Unsupported,
        Vector.empty,
        s"$what cites evidence not in the bundle: ${missing.mkString(", ")}"
      )
    else if hs.nonEmpty then
      FigureCheck(
        Outcome.Conflicting,
        Vector.empty,
        s"$what: claimed ${claimed.take(64)}, evidence ${hs.map(_._2.value).distinct.mkString("/")}"
      )
    else
      val why =
        if items.isEmpty then "it cites no graph or metric evidence of its claim"
        else "no cited item holds that metric for that researcher"
      FigureCheck(Outcome.Unsupported, Vector.empty, s"$what, claimed ${claimed.take(64)}: $why")

  /** Bundle researcher IDs to a display name, for labels. */
  private def nameMap(bundle: EvidenceBundle): Map[String, String] =
    knownNames(bundle)
      .flatMap((n, rids) => rids.toVector.map(_ -> n))
      .groupMapReduce(_._1)(_._2)((a, _) => a)

  /** The structured claims of a model claim, one per figure, in figure order, each with the ID
    * [[structuredId]]. A structured claim is the tuple (researcherId, metric, value or rank,
    * evidenceIds) and nothing else: its text is written here from the evidence item that holds the
    * value ("metric of name (id) in window W: value."), never from model text, and it cites only
    * the items that hold that value. VERIFIED when the tuple matches a cited item; CONFLICTING when
    * a cited item holds another value; otherwise UNSUPPORTED.
    */
  def structured(c: DraftClaim, bundle: EvidenceBundle): Vector[Verdict] =
    val names = nameMap(bundle)
    def name(id: String) = names.getOrElse(id, id)
    val existing = c.evidenceIds.distinct.filter(bundle.byId.contains)
    c.figures.zipWithIndex.map { (f, i) =>
      val fc = checkFigure(i, f, c.evidenceIds.distinct, bundle, name)
      val sid = structuredId(c.claimId, i)
      val missing = f.evidenceIds.distinct.filterNot(bundle.byId.contains)
      import VerificationStatus.*
      fc.outcome match
        case Outcome.Supported =>
          val ids = fc.matches.map(_._1.evidenceId).distinct
          Verdict(
            ClaimCheck(sid, VERIFIED, Vector.empty, 1, 1, Vector.empty),
            fc.matches.head._2.statement,
            Vector.empty,
            ids
          )
        case o =>
          val status = if o == Outcome.Conflicting then CONFLICTING else UNSUPPORTED
          Verdict(
            ClaimCheck(sid, status, Vector(fc.reason), 1, 0, missing),
            s"${f.metric} of ${f.researcherId}: $Unverified.",
            Vector.empty,
            f.evidenceIds.distinct.filter(existing.contains)
          )
    }

  /** Checks a drafted claim: its figures, then its rendered text. With `model` (the default) the
    * claim is model output: its text is an UNVERIFIED interpretation (never VERIFIED) whose
    * verified figures name the researcher and metric they belong to; its figures are verified as
    * [[structured]] claims. `model = false` is only for the deterministic synthesizer's own text.
    */
  def verify(c: DraftClaim, bundle: EvidenceBundle, model: Boolean = true): Verdict =
    run(c.claimId, c.text, c.evidenceIds, c.figures, bundle, model)

  /** Checks text that has no figures (a summary, an uncertainty note). With `model` (the default)
    * it is an UNVERIFIED interpretation, never VERIFIED; `model = false` is only for the
    * deterministic synthesizer's own text, where every ASCII number is unsupported.
    */
  def verifyText(
      claimId: String,
      text: String,
      evidenceIds: Vector[String],
      bundle: EvidenceBundle,
      model: Boolean = true
  ): Verdict =
    run(claimId, text, evidenceIds, Vector.empty, bundle, model)

  private def run(
      claimId: String,
      raw: String,
      evidenceIds: Vector[String],
      figures: Vector[ClaimFigure],
      bundle: EvidenceBundle,
      model: Boolean
  ): Verdict =
    val index = bundle.byId
    val ids = evidenceIds.distinct
    val missing = ids.filterNot(index.contains)
    val cited = ids.flatMap(index.get)
    if cited.isEmpty then
      return Verdict(
        ClaimCheck(
          claimId,
          VerificationStatus.UNSUPPORTED,
          Vector("cites no evidence in the bundle"),
          0,
          0,
          missing
        ),
        ClaimFigures.fill(raw, _ => Some(Unverified)),
        Vector.empty,
        Vector.empty
      )
    val graph = cited.filterNot(semantic)
    val sem = cited.filter(semantic)
    val facts = Vector.newBuilder[Fact]
    def fact(k: Kind, s: Int, e: Int, ok: Boolean, reason: => String, conflict: Boolean = false) =
      val o =
        if ok then Outcome.Supported
        else if conflict then Outcome.Conflicting
        else Outcome.Unsupported
      facts += Fact(k, (s, e), o, if ok then "" else reason)

    // 0. Figures: each tuple checked against its cited items, its number rendered from them (model
    // text names the owner of the number).
    val names = nameMap(bundle)
    def name(id: String) = names.getOrElse(id, id)
    def figure(i: Int, f: ClaimFigure): (String, Boolean, Boolean, String) =
      val fc = checkFigure(i, f, ids, bundle, name)
      fc.outcome match
        case Outcome.Supported =>
          val h = fc.matches.head._2
          (if model then s"${h.value} (${h.label})" else h.value, true, false, "")
        case Outcome.Conflicting => (Unverified, false, true, fc.reason)
        case Outcome.Unsupported => (Unverified, false, false, fc.reason)
    val out = new java.lang.StringBuilder
    val figureSpans = Vector.newBuilder[(Int, Int)]
    var last = 0
    ClaimFigures.Placeholder.findAllMatchIn(raw).foreach { m =>
      out.append(raw, last, m.start)
      val i = m.group(1).toInt - 1
      val (shown, ok, conflict, reason) = figures
        .lift(i)
        .fold((Unverified, false, false, s"placeholder ${m.matched} names no figure"))(
          figure(i, _)
        )
      val s = out.length
      out.append(shown)
      fact(Kind.Figure, s, out.length, ok, reason, conflict)
      figureSpans += ((s, out.length))
      last = m.end
    }
    out.append(raw, last, raw.length)
    val text = out.toString

    val mask = text.toCharArray
    def masked = new String(mask)
    def blank(s: Int, e: Int): Unit = (s until e).foreach(i => mask(i) = ' ')
    def free(s: Int, e: Int) = (s until e).forall(i => mask(i) != ' ' || text.charAt(i) == ' ')
    figureSpans.result().foreach(blank)

    // 1. Quoted titles.
    Quoted.findAllMatchIn(masked).toVector.foreach { m =>
      val inner = Option(m.group(1)).getOrElse(m.group(2))
      val key = Normalize.title(inner)
      def has(xs: Vector[EvidenceItem]) = xs.exists(e =>
        e.evidenceType == EvidenceType.PUBLICATION && Normalize
          .title(e.values.getOrElse("title", "")) == key
      )
      fact(
        Kind.Title,
        m.start,
        m.end,
        has(graph),
        if has(bundle.deterministic) then
          s"title \"$inner\" is in the bundle but not in the cited evidence"
        else s"title \"$inner\" is not a publication in the evidence"
      )
      blank(m.start, m.end)
    }
    // 2. Known researcher names (is the named researcher in the cited evidence; never a subject).
    val hits = Vector.newBuilder[(Int, Int, Set[String], String)]
    knownNames(bundle).foreach { (name, rids) =>
      var from = masked.indexOf(name)
      while from >= 0 do
        val end = from + name.length
        val before = from == 0 || !Character.isLetterOrDigit(text.charAt(from - 1))
        val after = end >= text.length || !Character.isLetterOrDigit(text.charAt(end))
        if before && after && free(from, end) then
          val ok = graph.exists(e =>
            e.researcherIds.exists(rids) || e.values.values.exists(_.contains(name))
          ) ||
            sem.exists(_.researcherIds.exists(rids))
          fact(Kind.Name, from, end, ok, s"researcher $name is not in the cited evidence")
          hits += ((from, end, rids, name))
          blank(from, end)
        from = masked.indexOf(name, from + 1)
    }
    val nameHits = hits.result().sortBy(_._1)
    // 3. Identifiers.
    IdPattern.findAllMatchIn(masked).toVector.foreach { m =>
      val id = m.matched.reverse.dropWhile(c => c == '.' || c == ':').reverse
      val ok = cited.exists(e =>
        e.evidenceId == id || e.researcherIds.contains(id) || e.workId.contains(id) ||
          e.provenance.runId == id || e.values.values
            .exists(v => v == id || v.split("[;|, ]+").contains(id))
      )
      fact(
        Kind.Id,
        m.start,
        m.start + id.length,
        ok,
        s"identifier $id is not in the cited evidence"
      )
      blank(m.start, m.start + id.length)
    }
    // 4. Path hops.
    val foundPaths =
      graph.filter(e => e.evidenceType == EvidenceType.GRAPH_PATH && found(e).contains("true"))
    def adjacent(a: Set[String], b: Set[String]) =
      foundPaths.exists(_.graphPath.exists(_.sliding(2).exists {
        case Seq(x, y) => (a(x) && b(y)) || (a(y) && b(x))
        case _         => false
      })) || graph.exists(e =>
        e.evidenceType == EvidenceType.COAUTHOR_EDGE && found(e).contains("true") &&
          e.researcherIds.exists(a) && e.researcherIds.exists(b)
      )
    Arrow.findAllMatchIn(masked).toVector.foreach { m =>
      val prev = nameHits
        .filter(h => h._2 <= m.start && text.substring(h._2, m.start).trim.isEmpty)
        .lastOption
      val next = nameHits.find(h => h._1 >= m.end && text.substring(m.end, h._1).trim.isEmpty)
      (prev, next) match
        case (Some(a), Some(b)) =>
          val ok = adjacent(a._3, b._3)
          fact(
            Kind.Hop,
            a._1,
            b._2,
            ok,
            if foundPaths.nonEmpty then s"hop ${a._4} -> ${b._4} is not on the cited path"
            else s"no cited path or coauthor edge supports hop ${a._4} -> ${b._4}",
            conflict = foundPaths.nonEmpty
          )
        case _ =>
          fact(Kind.Hop, m.start, m.end, false, "a path hop endpoint is not a known researcher")
      blank(m.start, m.end)
    }
    // 5. Windows.
    val windows = graph.flatMap(e => e.values.get("window") ++ e.values.get("contextWindow")).toSet
    WindowPattern.findAllMatchIn(masked).toVector.foreach { m =>
      val label = s"${m.group(1)}-${m.group(2)}"
      fact(
        Kind.Window,
        m.start,
        m.end,
        windows(label),
        if windows.nonEmpty then
          s"window $label differs from the cited window ${windows.toVector.sorted.mkString(", ")}"
        else s"window $label is not in the cited evidence",
        conflict = windows.nonEmpty
      )
      blank(m.start, m.end)
    }
    // 6. Path and edge statements.
    val paths = graph.filter(_.evidenceType == EvidenceType.GRAPH_PATH)
    val edges = graph.filter(_.evidenceType == EvidenceType.COAUTHOR_EDGE)
    def statement(r: Regex, xs: Vector[EvidenceItem], want: String, what: String) =
      r.findFirstMatchIn(text).foreach { m =>
        val ok = xs.exists(found(_).contains(want))
        fact(
          Kind.Statement,
          m.start,
          m.end,
          ok,
          if xs.nonEmpty then s"'${m.matched}' contradicts the cited $what evidence"
          else s"no cited $what evidence supports '${m.matched}'",
          conflict = xs.nonEmpty
        )
      }
    if NoPath.findFirstIn(text).isDefined then statement(NoPath, paths, "false", "path")
    else statement(HasPath, paths, "true", "path")
    if NoEdge.findFirstIn(text).isDefined then statement(NoEdge, edges, "false", "coauthor edge")
    else statement(HasEdge, edges, "true", "coauthor edge")
    // 7. Deterministic text only: every remaining ASCII number is backed by no figure. A cited
    // identity record's quoted query and a cited ranking's filter text are copied data, not
    // numbers. Model text is never searched for numbers: all of it is an interpretation.
    if !model then
      val literals = cited.flatMap { x =>
        if x.evidenceType == EvidenceType.IDENTITY_RESOLUTION then
          x.values.get("query").filter(_.nonEmpty).map(q => s"'$q'")
        else if kindOf(x) == "ranking" then
          x.values
            .get("filter")
            .filter(f => f.nonEmpty && !f.startsWith("none"))
            .map(f => UntrustedText.bound(f, 120))
        else None
      }.distinct
      literals.foreach { l =>
        var from = text.indexOf(l)
        while from >= 0 do
          blank(from, from + l.length)
          from = text.indexOf(l, from + 1)
      }
      NumberPattern.findAllMatchIn(masked).toVector.foreach { m =>
        fact(
          Kind.Number,
          m.start,
          m.end,
          false,
          s"number ${m.group(1)} is in free text: no (researcherId, metric, value, evidenceIds) figure backs it"
        )
        blank(m.start, m.end)
      }
    // 8. Capitalized names not in the bundle.
    val known =
      (bundle.deterministic ++ bundle.identity).flatMap(_.values.values) ++ sem.flatMap(_.text)
    Capitalized.findAllMatchIn(masked).toVector.foreach { m =>
      val tokens = m.matched.split("\\s+").toVector.map(_.stripSuffix("'s").stripSuffix("\u2019s"))
      val rest = tokens.dropWhile(Stop)
      val phrase = rest.mkString(" ")
      if rest.size >= 2 && !known.exists(_.contains(phrase)) then
        val s = m.start + m.matched.indexOf(rest.head)
        fact(Kind.Name, s, m.end, false, s"name '$phrase' is not in the evidence")
    }
    // 9. Ranking order.
    graph.filter(x => kindOf(x) == "ranking") match
      case Vector(r) =>
        val order = r.researcherIds
        val positions = nameHits.flatMap(h => order.indices.find(i => h._3(order(i)))).distinct
        if positions.size >= 2 then
          val ok = positions == positions.sorted
          fact(
            Kind.Order,
            0,
            0,
            ok,
            "the order of researchers differs from the cited ranking",
            conflict = true
          )
      case _ => ()

    val all = facts.result()
    val conflicts = all.filter(_.outcome == Outcome.Conflicting)
    val unsupported = all.filter(_.outcome == Outcome.Unsupported)
    val material = all.filter(_.kind != Kind.Name)
    val supported = all.count(_.outcome == Outcome.Supported)
    val reasons = Vector.newBuilder[String]
    reasons ++= conflicts.map(_.reason) ++= unsupported.map(_.reason)
    if missing.nonEmpty then
      reasons += s"cites evidence not in the bundle: ${missing.mkString(", ")}"
    import VerificationStatus.*
    val status =
      if conflicts.nonEmpty then CONFLICTING
      else if graph.isEmpty && all.exists(f => GraphMaterial(f.kind)) then
        reasons += "semantic context never supports a count, window, path, edge or ranking"
        UNSUPPORTED
      else if all.nonEmpty && supported == 0 then UNSUPPORTED
      else if model then
        // The construction rule: model free text is never VERIFIED, whatever it contains.
        reasons += InterpretationReason
        if graph.isEmpty then reasons += "descriptive context only (untrusted retrieved text)"
        PARTIALLY_SUPPORTED
      else if graph.isEmpty then
        reasons += "descriptive context only (untrusted retrieved text)"
        PARTIALLY_SUPPORTED
      else if material.isEmpty then
        reasons += "no checkable value: interpretation only"
        PARTIALLY_SUPPORTED
      else if unsupported.nonEmpty || missing.nonEmpty then PARTIALLY_SUPPORTED
      else VERIFIED
    Verdict(
      ClaimCheck(claimId, status, reasons.result().distinct, all.size, supported, missing),
      text,
      unsupported.map(_.span).filter((s, e) => e > s),
      ids.filter(index.contains)
    )

  /** The answer's status: UNSUPPORTED when no claim survives, CONFLICTING when any drafted claim
    * contradicted the evidence (it was removed), VERIFIED when every drafted claim was verified,
    * otherwise PARTIALLY_SUPPORTED.
    */
  def overall(checks: Vector[ClaimCheck], kept: Int): VerificationStatus =
    import VerificationStatus.*
    if kept == 0 then UNSUPPORTED
    else if checks.exists(_.status == CONFLICTING) then CONFLICTING
    else if checks.forall(_.status == VERIFIED) then VERIFIED
    else PARTIALLY_SUPPORTED
