package scholarchain.rag

import scholarchain.core.*
import upickle.default.ReadWriter
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

/** A check plus what the answer stage needs to weaken the claim: the character spans of the
  * unsupported statements and the cited evidence IDs that exist.
  */
final case class Verdict(
    check: ClaimCheck,
    unsupportedSpans: Vector[(Int, Int)],
    existingEvidenceIds: Vector[String]
):
  /** The text with every unsupported span replaced by `[unverified]`. */
  def weakened(text: String): String =
    val merged = unsupportedSpans.sortBy(_._1).foldLeft(Vector.empty[(Int, Int)]) {
      case (acc :+ last, (s, e)) if s <= last._2 => acc :+ (last._1, math.max(last._2, e))
      case (acc, span)                           => acc :+ span
    }
    merged.reverse.foldLeft(text)((t, span) =>
      t.substring(0, span._1) + ClaimVerifier.Unverified + t.substring(span._2)
    )

/** ClaimVerificationAgent (`claim-verifier-v1`): deterministic and independent of the synthesizer.
  *
  * Only cited evidence that exists in the bundle supports a claim. Graph and metric evidence,
  * PUBLICATION and IDENTITY_RESOLUTION records support values; semantic context supports only
  * descriptive names and identifiers, never a number, window, hop, path or edge statement or
  * ranking order (`graph-value-policy-v1`). The text is read in this order, each span once:
  *   1. quoted publication titles: equal (after title normalization) to a cited PUBLICATION title;
  *   2. researcher names known to the bundle: the cited evidence names that researcher;
  *   3. identifiers (`scheme:...`, run IDs): present in the cited evidence;
  *   4. path hops `A -> B`: adjacent on a cited found path or a cited found coauthor edge;
  *   5. time windows `YYYY-YYYY`: a window of the cited evidence;
  *   6. path and edge statements ("no coauthorship path", "are direct coauthors"): agree with the
  *      cited `found` flag;
  *   7. keyed numbers ("41 distinct coauthors", "bridge score 0.41", "3 hops", "10 members"): equal
  *      to that field of the cited evidence; a field that no cited item holds is unsupported;
  *   8. other numbers: present among the cited values. In steps 7 and 8 a number has a subject only
  *      when its sentence names exactly one researcher (one researcher ID) and has no pronoun (he,
  *      she, they, his, her, their, its, him, them and their forms; "its own included" is wording,
  *      not a subject). Only `.`, `!` or `?` after a lower-case non-abbreviation word or a numeric
  *      token of four or more characters, outside brackets and before a capital, digit, quote or
  *      bracket, ends a sentence; a quoted query leading an identity statement is not a mention. A
  *      per-researcher record (metrics row, community membership, identity) supports a number only
  *      when it is its subject's; any other number belongs to nobody, so only edge, path, ranking,
  *      link and publication items can support it and a per-researcher value never makes it
  *      CONFLICTING (it is weakened and labeled). "N communities, its own included" is the stored
  *      `communitiesConnected`, while "N other communities" is a value no item holds;
  *   9. capitalized multi-word names not found in the bundle;
  *   10. ranking order: with one cited ranking, the named researchers follow its order.
  *
  * Status: no cited evidence exists, UNSUPPORTED; a fact contradicts the evidence, CONFLICTING;
  * semantic-only citations with a graph value, UNSUPPORTED; facts but none supported, UNSUPPORTED;
  * some facts unsupported, a cited ID missing, only descriptive context or no checkable value,
  * PARTIALLY_SUPPORTED; otherwise VERIFIED.
  */
object ClaimVerifier:
  val Version = "claim-verifier-v1"
  val Unverified = "[unverified]"

  private enum Kind:
    case Title, Name, Id, Hop, Window, Statement, Keyed, Number, Order
  private enum Outcome:
    case Supported, Unsupported, Conflicting
  private final case class Fact(kind: Kind, span: (Int, Int), outcome: Outcome, reason: String)

  private val GraphMaterial =
    Set(Kind.Hop, Kind.Window, Kind.Statement, Kind.Keyed, Kind.Number, Kind.Order)
  private val Quoted = "\"([^\"]{2,300})\"|\u201c([^\u201d]{2,300})\u201d".r
  val IdPattern: Regex =
    "(?<![\\w\\-])(?:(?:[a-z][a-z0-9]{0,15}:)+[^\\s,;()\\[\\]\"'=<>]+|run-[0-9a-f]{16})".r
  private val Arrow = "->|\u2192".r
  private val WindowPattern = "(?<!\\d)(\\d{4})\\s*[-\u2013]\\s*(\\d{4})(?!\\d)".r
  private val NumberPattern = "(?<![\\w.])(\\d+(?:\\.\\d+)?)(?!\\w|\\.\\d)".r
  private val Capitalized = "\\p{Lu}[\\p{L}'\u2019\\-]*(?:\\s+\\p{Lu}[\\p{L}'\u2019\\-]*)+".r
  private val NoPath = "(?i)\\bno (?:coauthorship |collaboration )?path\\b|\\bnot connected\\b".r
  private val HasPath =
    "(?i)\\bshortest (?:coauthorship )?path\\b|\\bpath (?:of|with) \\d+ hops?\\b".r
  private val NoEdge =
    "(?i)\\bno direct coauthor\\b|\\bnot (?:direct )?coauthors\\b|\\bnever coauthored\\b|\\bhave not (?:collaborated|coauthored)\\b|\\bno coauthor edge\\b".r
  private val HasEdge =
    "(?i)\\bare direct coauthors\\b|\\bhave coauthored\\b|\\bcoauthored \\d+ joint publications?\\b".r
  // Sentence attribution (R3-1): a sentence ends only at `.`, `!` or `?` followed by whitespace and
  // an upper-case letter, digit, quote or bracket, outside brackets, after a lower-case word of at
  // least three letters that is not an abbreviation, or after a numeric token of at least four
  // characters (a year, window or decimal). Anything else (an initial, a title, "vs.", "Dr.", "1.",
  // a name, an identifier) never ends a sentence, so a doubtful period only makes the scope wider.
  private val NextSentence = "\\s+[\\p{Lu}\\d\"'(\\[\u201c\u2018]".r
  private val Word = "\\p{Ll}[\\p{Ll}\\-]+\\p{Ll}".r
  private val Numeric = "\\d[\\d.\\-\u2013]{2,}\\d|\\d{4,}".r
  private val Abbreviations =
    ("abbr approx assoc avg cca cit compl cont corp dept eds eqs esp est etc excl fig figs gen govt " +
      "hon ibid inc incl inst intl loc ltd max min misc mrs natl nos orig prev prof publ ref refs " +
      "repr resp rev sec seq transl univ viz vol vols").split(' ').toSet
  // A pronoun anywhere in the sentence makes its subject unknown; "its own included" is the fixed
  // wording of the stored communitiesConnected value, not a subject.
  private val Pronoun =
    "(?i)\\b(he|she|they|his|her|hers|their|theirs|its|him|them|himself|herself|itself|themselves)\\b".r
  private val OwnIncluded = "(?i)\\b(?:its|their|his|her) own included\\b".r
  // The fixed lead of an identity statement quotes the user's query; that quotation is the query
  // string, not a mention of a researcher (Synthesis candidate claims).
  private val IdentityVerbs = Vector(" is ambiguous: candidate ", " resolved to ", " matched ")
  private val Num = "(\\d+(?:\\.\\d+)?)"
  private val Keyed: Vector[(Regex, Vector[String])] = Vector(
    s"(?i)\\b(?:at most|within) (\\d+) hops?\\b" -> Vector("maxDepth"),
    s"(?i)\\b(\\d+) hops?\\b" -> Vector("hops"),
    s"(?i)\\b(\\d+) joint publications?\\b" -> Vector("jointPublicationCount"),
    s"(?i)\\b(\\d+) distinct coauthors?\\b" -> Vector(
      "distinctCoauthorCount",
      "degree.distinctCoauthorCount"
    ),
    s"(?i)\\b(\\d+) publications?\\b" -> Vector("publicationCount"),
    s"(?i)\\bweighted degree (?:of |is |= )?$Num" -> Vector(
      "weightedDegree",
      "degree.weightedDegree"
    ),
    s"(?i)\\b(\\d+) coauthor occurrences\\b" -> Vector("totalCoauthorOccurrences"),
    s"(?i)\\b(\\d+) coauthors on one paper\\b" -> Vector("maximumCoauthorsOnOnePaper"),
    s"(?i)\\baverage (?:is |of )?$Num coauthors\\b" -> Vector("averageCoauthorsPerPaper"),
    s"(?i)\\b(?:bridge score|betweenness) (?:of |is |= )?$Num" -> Vector(
      "bridgeScore",
      "bridge.bridgeScore"
    ),
    s"(?i)\\bparticipation coefficient (?:of |is |= )?$Num" -> Vector(
      "participationCoefficient",
      "bridge.participationCoefficient"
    ),
    s"(?i)\\b(\\d+) cross-community\\b" -> Vector(
      "crossCommunityDegree",
      "bridge.crossCommunityDegree"
    ),
    s"(?i)\\bcross-community degree (?:of |is |= )?(\\d+)" -> Vector(
      "crossCommunityDegree",
      "bridge.crossCommunityDegree"
    ),
    // communitiesConnected counts the researcher's own community (Spark Bridges), so only a
    // wording that includes it maps to the field; "N other communities" is a value no item holds.
    s"(?i)\\b(?:touching|touches|linking|links|connecting|connects|spanning|spans) (\\d+) communities\\b" -> Vector(
      "communitiesConnected",
      "bridge.communitiesConnected"
    ),
    s"(?i)\\b(\\d+) communities,? (?:its|their|his|her) own included\\b" -> Vector(
      "communitiesConnected",
      "bridge.communitiesConnected"
    ),
    s"(?i)\\b(\\d+) other communit(?:y|ies)\\b" -> Vector("otherCommunityCount"),
    s"(?i)\\bcoreness (?:of |is |= )?(\\d+)" -> Vector("coreness"),
    s"(?i)\\bof (\\d+) researchers\\b" -> Vector("componentSize"),
    s"(?i)\\b(\\d+) members\\b" -> Vector("communitySize"),
    s"(?i)\\bidentity confidence (?:of |is |= )?$Num" -> Vector("identityConfidence"),
    s"(?i)\\b(\\d+) (?:source )?mentions\\b" -> Vector("mentionCount")
  ).map((p, f) => p.r -> f)
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

  /** Records that hold one researcher's own values: a value they hold is that researcher's only. */
  private def perResearcher(e: EvidenceItem) =
    e.evidenceType == EvidenceType.IDENTITY_RESOLUTION ||
      (e.evidenceType == EvidenceType.METRIC_RECORD &&
        e.values.get("kind").contains("researcher-metrics")) ||
      (e.evidenceType == EvidenceType.COMMUNITY_RECORD &&
        e.values.get("kind").contains("community-membership"))
  private def found(e: EvidenceItem) = e.values.get("found")
  private def dec(s: String) = Try(BigDecimal(s)).toOption

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

  def verify(c: DraftClaim, bundle: EvidenceBundle): Verdict =
    verifyText(c.claimId, c.text, c.evidenceIds, bundle)

  def verifyText(
      claimId: String,
      text: String,
      evidenceIds: Vector[String],
      bundle: EvidenceBundle
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
        Vector.empty,
        Vector.empty
      )
    val graph = cited.filterNot(semantic)
    val sem = cited.filter(semantic)
    val facts = Vector.newBuilder[Fact]
    val mask = text.toCharArray
    def masked = new String(mask)
    def blank(s: Int, e: Int): Unit = (s until e).foreach(i => mask(i) = ' ')
    def free(s: Int, e: Int) = (s until e).forall(i => mask(i) != ' ' || text.charAt(i) == ' ')
    def fact(k: Kind, s: Int, e: Int, ok: Boolean, reason: => String, conflict: Boolean = false) =
      val o =
        if ok then Outcome.Supported
        else if conflict then Outcome.Conflicting
        else Outcome.Unsupported
      facts += Fact(k, (s, e), o, if ok then "" else reason)

    // 1. Quoted titles.
    Quoted.findAllMatchIn(text).toVector.foreach { m =>
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
    // 2. Known researcher names.
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
    val noPath = NoPath.findFirstIn(text).isDefined
    if noPath then statement(NoPath, paths, "false", "path")
    else statement(HasPath, paths, "true", "path")
    if NoEdge.findFirstIn(text).isDefined then statement(NoEdge, edges, "false", "coauthor edge")
    else statement(HasEdge, edges, "true", "coauthor edge")
    // Attribution (R3-1, sentence rule): a per-researcher record (metrics row, community
    // membership, identity) supports a number only when the number's sentence names exactly one
    // researcher and has no pronoun; every other number belongs to nobody, so only edge, path,
    // ranking, link and publication items can support it, and a per-researcher value never makes
    // it CONFLICTING. Clauses, positions and abbreviation periods do not change the decision.
    val snapshot = masked
    val queries = bundle.identity.flatMap(_.values.get("query")).filter(_.nonEmpty).distinct
    // sentence ends, decided left to right so a bracket opened in the sentence keeps it open
    val ends = (0 until text.length)
      .filter(p => ".!?".indexOf(snapshot.charAt(p)) >= 0)
      .foldLeft(Vector.empty[Int]) { (acc, p) =>
        val start = acc.lastOption.getOrElse(0)
        val span = snapshot.substring(start, p)
        val open = span.count(c => c == '(' || c == '[') > span.count(c => c == ')' || c == ']')
        val raw = text.substring(text.lastIndexWhere(_.isWhitespace, p - 1) + 1, p)
        val token = raw
          .dropWhile(c => "([\"'\u201c\u2018".indexOf(c) >= 0)
          .reverse
          .dropWhile(c => ")]\"'\u201d\u2019,".indexOf(c) >= 0)
          .reverse
        val wordEnd = Word.matches(token) && !Abbreviations(token.toLowerCase)
        val follows = NextSentence.findPrefixOf(text.substring(p + 1)).isDefined
        if !open && follows && (wordEnd || Numeric.matches(token)) then acc :+ (p + 1) else acc
      }
    def sentenceOf(pos: Int): (Int, Int) =
      (ends.filter(_ <= pos).lastOption.getOrElse(0), ends.find(_ > pos).getOrElse(text.length))
    // Right: the subject's IDs and name; Left: why the subject is not one named researcher.
    def subjectAt(pos: Int): Either[String, (Set[String], String)] =
      val (ss, se) = sentenceOf(pos)
      val from = (ss until se).find(i => !text.charAt(i).isWhitespace).getOrElse(se)
      val quote = queries
        .filter(q => IdentityVerbs.exists(v => text.startsWith(s"'$q'$v", from)))
        .map(q => (from, from + q.length + 2))
        .maxByOption(_._2)
      def quoted(h: (Int, Int, Set[String], String)) =
        quote.exists((a, b) => h._1 >= a && h._2 <= b)
      val people = nameHits.filter(h => h._1 >= ss && h._1 < se && !quoted(h)).distinctBy(_._3)
      val scan = snapshot.substring(ss, se).toCharArray
      quote.foreach((a, b) => (a - ss until math.min(b, se) - ss).foreach(i => scan(i) = ' '))
      val pronoun = Pronoun.findFirstIn(OwnIncluded.replaceAllIn(new String(scan), " "))
      val who = people match
        case Vector(h) if h._3.size == 1 => None
        case Vector(h) => Some(s"the name ${h._4} belongs to ${h._3.size} researchers")
        case Vector()  => Some("its sentence names no researcher")
        case hs        =>
          Some(s"its sentence names ${hs.size} researchers (${hs.map(_._4).mkString(" / ")})")
      (who.toVector ++ pronoun.map(p => s"its sentence has the pronoun '$p'")) match
        case Vector() => Right((people.head._3, people.head._4))
        case issues   => Left(issues.mkString(" and "))
    def backing(pos: Int): (Vector[EvidenceItem], Option[String], Option[String]) =
      subjectAt(pos) match
        case Right((who, label)) =>
          (graph.filter(x => !perResearcher(x) || x.researcherIds.exists(who)), Some(label), None)
        case Left(why) => (graph.filterNot(perResearcher), None, Some(why))
    def ambiguity(why: String) =
      s"the subject is ambiguous: $why, so no researcher's own record supports it"
    // 7. Keyed numbers.
    Keyed.foreach { (r, fields) =>
      r.findAllMatchIn(masked).toVector.foreach { m =>
        val (s, e) = (m.start(1), m.end(1))
        if free(s, e) then
          val n = BigDecimal(m.group(1))
          val (items, subject, ambiguous) = backing(s)
          val have = items.flatMap(x => fields.flatMap(x.values.get)).flatMap(dec)
          val elsewhere = graph.flatMap(x => fields.flatMap(x.values.get)).flatMap(dec)
          val ok = have.exists(_.compare(n) == 0)
          val of = subject.fold("")(w => s" of $w")
          fact(
            Kind.Keyed,
            s,
            e,
            ok,
            if have.nonEmpty then
              s"${fields.head}$of: claimed ${m.group(1)}, evidence ${have.distinct.mkString("/")}"
            else if elsewhere.nonEmpty then
              ambiguous.fold(
                s"no cited evidence$of holds ${fields.head} (claimed ${m.group(1)}; it is another researcher's value)"
              )(among => s"${fields.head} (claimed ${m.group(1)}): ${ambiguity(among)}")
            else s"no cited evidence holds ${fields.head} (claimed ${m.group(1)})",
            conflict = have.nonEmpty
          )
          blank(s, e)
      }
    }
    // 8. Other numbers.
    def numbersOf(x: EvidenceItem): Set[BigDecimal] =
      x.values.values
        .map(v => IdPattern.replaceAllIn(v, " "))
        .flatMap(v => NumberPattern.findAllMatchIn(v).flatMap(m => dec(m.group(1))))
        .toSet
    val itemNumbers = graph.map(x => x.evidenceId -> numbersOf(x)).toMap
    NumberPattern.findAllMatchIn(masked).toVector.foreach { m =>
      val (items, subject, ambiguous) = backing(m.start)
      val ok = dec(m.group(1)).exists(n =>
        items.exists(x => itemNumbers.getOrElse(x.evidenceId, Set.empty).exists(_.compare(n) == 0))
      )
      fact(
        Kind.Number,
        m.start,
        m.end,
        ok,
        ambiguous.fold(
          s"number ${m.group(1)} is not in the cited evidence${subject.fold("")(w => s" of $w")}"
        )(among => s"number ${m.group(1)}: ${ambiguity(among)}")
      )
      blank(m.start, m.end)
    }
    // 9. Capitalized names not in the bundle.
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
    // 10. Ranking order.
    graph.filter(_.values.get("kind").contains("ranking")) match
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
