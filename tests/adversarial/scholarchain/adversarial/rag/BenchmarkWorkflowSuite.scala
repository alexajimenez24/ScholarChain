package scholarchain.adversarial.rag

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import scala.util.Try

import scholarchain.adversarial.rag.BenchmarkRuntime.BenchmarkRun
import scholarchain.core.*
import scholarchain.rag.*

/** Benchmark-query suite, question set v1, through the stage 1 to 10 workflow (Phase 09 step 3,
  * test-engineer).
  *
  * Spec: Phase 9 "Run sbt check and the benchmark-query suite" and the gate "Deterministic graph
  * retrieval and semantic retrieval are evaluated separately" (`docs/spec/assignment.md` lines 1560
  * and 1573). Every question runs once through `CollaborationWorkflow.run` with the deterministic
  * synthesizer and an `InMemoryAuditLog`, as a spec `POST /v1/query` request (see
  * [[BenchmarkRuntime.request]]), and is compared with the frozen expected answer in
  * `questions-v1.json`, which was computed from the stores without the workflow.
  *
  * Three scores, kept apart:
  *   - D, deterministic retrieval: the deterministic and identity partitions of the evidence bundle
  *     hold the expected graph, metric and identity facts (never the semantic partition).
  *   - A, answer: the kept claims and researcher rows state the expected facts, each claim citing
  *     evidence that holds them, the gap is stated where evidence is missing, and the audit record
  *     is stored.
  *   - S, semantic retrieval: returned semantic items and how many are relevant under the rule
  *     `semantic-relevance-rule-v1` (an item is relevant when its entity is one of the question's
  *     expected entities: researchers, works, component, community, topic or institution).
  *
  * A question passes the benchmark when D and A both pass. S is a score, not a pass mark (the spec
  * sets no threshold). The planner check P (the question text alone) is reported beside them.
  */
object BenchmarkScoring:

  final case class Semantic(returned: Int, relevant: Int, byType: Map[String, Int])

  final case class Outcome(
      id: String,
      category: String,
      deterministic: Vector[String],
      answer: Vector[String],
      planner: Vector[String],
      semantic: Semantic,
      verification: String,
      abstained: Boolean
  ):
    def passed: Boolean = deterministic.isEmpty && answer.isEmpty

  private val IdToken = """\b[a-z]+:[A-Za-z0-9:_./\-]+""".r
  private val WindowToken = """\b\d{4}-\d{4}\b""".r
  private val Num = """-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?""".r

  /** The numbers a text states, with identifiers and window labels blanked out first. */
  def numbers(text: String): Vector[BigDecimal] =
    val blanked = WindowToken.replaceAllIn(IdToken.replaceAllIn(text, " "), " ")
    Num.findAllIn(blanked).toVector.flatMap(s => Try(BigDecimal(s)).toOption)

  def states(text: String, x: BigDecimal): Boolean = numbers(text).exists(_.compare(x) == 0)

  def bd(v: ujson.Value): BigDecimal = BigDecimal(v.num)

  def strs(v: ujson.Value): Vector[String] = v.arr.map(_.str).toVector

  def split(s: String): Set[String] = s.split("[;,]").map(_.trim).filter(_.nonEmpty).toSet

  def kind(e: EvidenceItem): String = e.evidenceType.toString

  def num(e: EvidenceItem, k: String): Option[BigDecimal] =
    e.values.get(k).flatMap(s => Try(BigDecimal(s)).toOption)

  /** Every entity a question is about, from its expected answer (`semantic-relevance-rule-v1`). */
  def entities(q: ujson.Value): Set[String] =
    val e = q("expected").obj
    val single = Vector(
      "researcherId",
      "researcherA",
      "researcherB",
      "top",
      "componentId",
      "communityId",
      "topicId",
      "institutionId"
    ).flatMap(k => e.get(k).map(_.str))
    val lists = Vector("path", "maximumTeamWorkIds", "supportingWorkIds").flatMap(k =>
      e.get(k).toVector.flatMap(strs)
    )
    val hops =
      e.get("hopEvidence").toVector.flatMap(_.arr.flatMap(h => strs(h("supportingWorkIds"))))
    (single ++ lists ++ hops).toSet

  def score(run: BenchmarkRun): Outcome =
    val q = run.question
    val id = q("id").str
    val cat = q("category").str
    run.result match
      case Left(e) =>
        val msg = s"workflow returned ${e.describe}"
        Outcome(
          id,
          cat,
          Vector(msg),
          Vector(msg),
          planner(run),
          Semantic(0, 0, Map.empty),
          "-",
          false
        )
      case Right(r) =>
        val c = new Checks(q, r, run)
        val sem = r.evidence.bundle.semantic
        val ents = entities(q)
        val relevant =
          sem.count(i => i.values.get("entityId").exists(ents) || i.researcherIds.exists(ents))
        Outcome(
          id,
          cat,
          c.deterministic,
          c.answer,
          planner(run),
          Semantic(
            sem.size,
            relevant,
            sem.groupMapReduce(i => i.values.getOrElse("documentType", kind(i)))(_ => 1)(_ + _)
          ),
          r.answer.verification.toString,
          r.answer.abstained
        )

  /** Spec stage 1: "Extract researcher names, time windows, topic, venue, institution, and
    * requested ranking metric" and classify the requested operation, from the question text alone.
    */
  def planner(run: BenchmarkRun): Vector[String] =
    val q = run.question
    val c = q("constraints").obj
    run.textOnly match
      case Left(e)  => Vector(s"text-only plan failed: ${e.describe}")
      case Right(t) =>
        val p = t.plan
        val out = Vector.newBuilder[String]
        val names = c.get("researcherNames").toVector.flatMap(strs)
        if p.researcherNames.toSet != names.toSet then
          out += s"names ${p.researcherNames.mkString("[", ", ", "]")}, expected ${names.mkString("[", ", ", "]")}"
        if !c.contains("windows") then
          val Array(a, b) = q("window").str.split("-")
          if p.window.startYear != a.toInt || p.window.endYear != b.toInt then
            out += s"window ${p.window.startYear}-${p.window.endYear}, expected ${q("window").str}"
        c.get("topic").foreach { tp =>
          if !p.topics.exists(_.equalsIgnoreCase(tp.str)) then
            out += s"topic '${tp.str}' not extracted (topics ${p.topics.mkString("[", ", ", "]")})"
        }
        c.get("institution").foreach { in =>
          if !p.institutions.exists(_.equalsIgnoreCase(in.str)) then
            out += s"institution '${in.str}' not extracted"
        }
        val ops = p.operations.map(_.toString)
        val named = c.contains("researcherNames") && q("category").str == "coauthor-breadth"
        if !named && !ops.contains(q("operation").str) then
          out += s"operation ${q("operation").str} not planned (planned ${ops.mkString(", ")})"
        out.result()

  final class Checks(q: ujson.Value, r: WorkflowResult, run: BenchmarkRun):
    private val w = q("window").str
    private val exp = q("expected")
    private val e = exp.obj
    private val cons = q("constraints").obj
    private val names = cons.get("researcherNames").toVector.flatMap(strs)
    private val bundle = r.evidence.bundle
    private val det = bundle.deterministic
    private val byId = bundle.byId
    private val kept = r.answer.claims

    private def cites(cl: AnswerClaim)(p: EvidenceItem => Boolean): Boolean =
      cl.evidenceIds.exists(i => byId.get(i).exists(p))

    private def row(rid: String): Option[ResearcherAnswer] =
      r.answer.researchers.find(_.researcherId == rid)

    private def metric(rid: String, window: String): Option[EvidenceItem] =
      det.find(x =>
        kind(x) == "METRIC_RECORD" && x.values.get("kind").contains("researcher-metrics") &&
          x.researcherIds == Vector(rid) && x.values.get("window").contains(window)
      )

    private def eqv(x: EvidenceItem, k: String, v: ujson.Value): Boolean =
      num(x, k).exists(_.compare(bd(v)) == 0)

    private def subjectIds: Vector[String] =
      if e.contains("researcherId") then Vector(e("researcherId").str)
      else if e.contains("researcherA") then Vector(e("researcherA").str, e("researcherB").str)
      else if e.contains("path") then Vector(strs(e("path")).head, strs(e("path")).last)
      else Vector.empty

    // D: deterministic retrieval --------------------------------------------------------------

    lazy val deterministic: Vector[String] =
      val out = Vector.newBuilder[String]
      def need(ok: Boolean, msg: => String): Unit = if !ok then out += msg
      // identity: every named researcher resolved, to the expected ID when the answer key names it
      names.zipWithIndex.foreach { (n, i) =>
        val res = r.evidence.resolutions.find(_.query == n)
        need(
          res.exists(_.status.toString == "RESOLVED"),
          s"'$n' not RESOLVED (${res.map(_.status)})"
        )
        subjectIds
          .lift(i)
          .foreach(sid =>
            need(
              res.flatMap(_.researcherId).contains(sid),
              s"'$n' resolved to ${res.flatMap(_.researcherId)}, expected $sid"
            )
          )
      }
      need(r.evidence.abstention.isEmpty, s"evidence stage abstained: ${r.evidence.abstention}")
      def metricNeeds(rid: String, window: String, fields: String*): Unit =
        metric(rid, window) match
          case None    => out += s"no researcher-metrics item for $rid in $window"
          case Some(m) =>
            fields.foreach(f =>
              need(eqv(m, f, e(f)), s"$f ${m.values.get(f)} for $rid, expected ${e(f)}")
            )
      def edgeById(edgeId: String) =
        det.find(x => kind(x) == "COAUTHOR_EDGE" && x.values.get("coauthorEdgeId").contains(edgeId))
      def publications(works: Iterable[String]): Unit =
        works.foreach(wid =>
          need(
            det.exists(x => kind(x) == "PUBLICATION" && x.workId.contains(wid)),
            s"no PUBLICATION item for $wid"
          )
        )
      q("category").str match
        case "coauthor-breadth" if e.contains("researcherId") =>
          metricNeeds(
            e("researcherId").str,
            w,
            "distinctCoauthorCount",
            "weightedDegree",
            "publicationCount"
          )
        case "coauthor-breadth" | "bridge-researchers" | "topics" | "institutions"
            if e.contains("top") =>
          val item = det.find(x =>
            x.values.get("kind").contains("ranking") && x.values
              .get("metricField")
              .contains(e("metric").str)
          )
          item match
            case None    => out += s"no ranking item on ${e("metric").str}"
            case Some(x) =>
              val pos = x.values
                .getOrElse("positions", "")
                .split(";")
                .toVector
                .filter(_.contains("="))
                .map { p =>
                  val rest = p.split(":", 2)(1)
                  val k = rest.lastIndexOf('=')
                  (rest.take(k), Try(BigDecimal(rest.drop(k + 1))).getOrElse(BigDecimal(-1)))
                }
              need(
                pos.headOption.exists(_._1 == e("top").str),
                s"ranking top ${pos.headOption.map(_._1)}, expected ${e("top").str}"
              )
              need(
                pos.headOption.exists(_._2.compare(bd(e("topValue"))) == 0),
                s"ranking top value ${pos.headOption.map(_._2)}, expected ${e("topValue")}"
              )
              need(
                pos.lift(1).exists(_._2.compare(bd(e("runnerUpValue"))) == 0),
                s"ranking runner-up value ${pos.lift(1).map(_._2)}, expected ${e("runnerUpValue")}"
              )
              if cons.contains("topic") || cons.contains("institution") then
                need(
                  !x.values.get("filter").exists(_.startsWith("none")),
                  s"ranking not filtered: ${x.values.get("filter")}"
                )
        case "maximum-team-size" =>
          val rid = e("researcherId").str
          metricNeeds(rid, w, "maximumCoauthorsOnOnePaper", "averageCoauthorsPerPaper")
          metric(rid, w).foreach(m =>
            need(
              m.values
                .get("maximumTeamWorkIds")
                .map(split)
                .contains(strs(e("maximumTeamWorkIds")).toSet),
              s"maximumTeamWorkIds ${m.values.get("maximumTeamWorkIds")}"
            )
          )
          publications(strs(e("maximumTeamWorkIds")))
        case "direct-collaboration" | "evidence" if e.contains("researcherA") =>
          if e("found").bool then
            edgeById(e("edgeId").str) match
              case None    => out += s"no COAUTHOR_EDGE item for ${e("edgeId").str}"
              case Some(x) =>
                need(x.values.get("found").contains("true"), "edge item not found=true")
                Seq("jointPublicationCount", "firstCollaborationYear", "lastCollaborationYear")
                  .foreach(f => need(eqv(x, f, e(f)), s"$f ${x.values.get(f)}, expected ${e(f)}"))
                need(
                  x.values
                    .get("supportingWorkIds")
                    .map(split)
                    .contains(strs(e("supportingWorkIds")).toSet),
                  s"supportingWorkIds ${x.values.get("supportingWorkIds")}"
                )
            if q("category").str == "evidence" then publications(strs(e("supportingWorkIds")))
          else
            val pair = Set(e("researcherA").str, e("researcherB").str)
            need(
              det.exists(x =>
                kind(x) == "COAUTHOR_EDGE" && x.researcherIds.toSet == pair && x.values
                  .get("found")
                  .contains("false")
              ),
              "no found=false COAUTHOR_EDGE item for the pair"
            )
        case "shortest-path" | "evidence" =>
          if e("found").bool then
            val path = strs(e("path"))
            det.find(x => kind(x) == "GRAPH_PATH" && x.values.get("found").contains("true")) match
              case None    => out += "no found=true GRAPH_PATH item"
              case Some(x) =>
                need(x.graphPath.contains(path), s"path ${x.graphPath}, expected $path")
                need(
                  eqv(x, "hops", e("hops")),
                  s"hops ${x.values.get("hops")}, expected ${e("hops")}"
                )
            e("hopEvidence").arr.foreach { h =>
              edgeById(h("edgeId").str) match
                case None    => out += s"no COAUTHOR_EDGE item for hop ${h("edgeId").str}"
                case Some(x) =>
                  need(
                    eqv(x, "jointPublicationCount", h("jointPublicationCount")),
                    s"hop ${h("edgeId").str} joint ${x.values.get("jointPublicationCount")}"
                  )
              if q("category").str == "evidence" then publications(strs(h("supportingWorkIds")))
            }
          else
            need(
              det.exists(x => kind(x) == "GRAPH_PATH" && x.values.get("found").contains("false")),
              "no found=false GRAPH_PATH item"
            )
        case "components" =>
          val rid = e("researcherId").str
          metricNeeds(rid, w, "componentSize")
          metric(rid, w).foreach(m =>
            need(
              m.values.get("componentId").contains(e("componentId").str),
              s"componentId ${m.values.get("componentId")}"
            )
          )
        case "communities" =>
          val rid = e("researcherId").str
          det.find(x =>
            kind(x) == "COMMUNITY_RECORD" && x.values.get("researcherId").contains(rid)
          ) match
            case None    => out += s"no COMMUNITY_RECORD for $rid"
            case Some(x) =>
              need(
                x.values.get("communityId").contains(e("communityId").str),
                s"communityId ${x.values.get("communityId")}"
              )
              need(
                eqv(x, "bridge.crossCommunityDegree", e("crossCommunityDegree")),
                s"crossCommunityDegree ${x.values.get("bridge.crossCommunityDegree")}"
              )
              need(
                eqv(x, "bridge.communitiesConnected", e("communitiesConnected")),
                s"communitiesConnected ${x.values.get("bridge.communitiesConnected")}"
              )
              need(
                x.values.get("missingFields").exists(_.contains("communitySize")),
                "community size not marked missing in the evidence"
              )
        case "bridge-researchers" =>
          metricNeeds(
            e("researcherId").str,
            w,
            "bridgeScore",
            "crossCommunityDegree",
            "communitiesConnected",
            "distinctCoauthorCount"
          )
        case "temporal-change" =>
          val rid = e("researcherId").str
          e("byWindow").obj.foreach { (win, v) =>
            metric(rid, win) match
              case None    => out += s"no researcher-metrics item for $rid in $win"
              case Some(m) =>
                need(
                  eqv(m, "distinctCoauthorCount", v("distinctCoauthorCount")),
                  s"$win distinctCoauthorCount ${m.values.get("distinctCoauthorCount")}"
                )
          }
        case other => out += s"no deterministic check for $other"
      out.result()

    // A: the answer ---------------------------------------------------------------------------

    lazy val answer: Vector[String] =
      val out = Vector.newBuilder[String]
      def need(ok: Boolean, msg: => String): Unit = if !ok then out += msg
      val status = r.answer.verification.toString
      need(!r.answer.abstained, s"answer abstained: ${r.answer.abstentionReason}")
      need(status == "VERIFIED" || status == "PARTIALLY_SUPPORTED", s"answer verification $status")
      kept.foreach(cl =>
        need(
          cl.evidenceIds.nonEmpty && cl.evidenceIds.forall(byId.contains),
          s"${cl.claimId} cites missing evidence"
        )
      )
      // audit stage
      need(
        run.log.get(r.answer.queryId) == Right(Some(r.audit)),
        "audit record not stored under the query ID"
      )
      need(
        r.audit.graphRunId == BenchmarkStore.GraphRun && r.audit.erRunId == BenchmarkRuntime.ErRun,
        "audit run IDs"
      )
      need(
        r.audit.evidenceIds.toSet == bundle.all.map(_.evidenceId).toSet,
        "audit evidence IDs differ from the bundle"
      )

      def claimWith(desc: String)(p: AnswerClaim => Boolean): Unit =
        need(kept.exists(p), s"no kept claim $desc")
      def onSubject(rid: String)(x: EvidenceItem) = x.researcherIds.contains(rid)
      def namesInOrder(text: String, ns: Vector[String]): Boolean =
        ns.foldLeft(Option(0))((from, n) =>
          from.flatMap(f => Some(text.indexOf(n, f)).filter(_ >= 0))
        ).isDefined

      q("category").str match
        case "coauthor-breadth" if e.contains("researcherId") =>
          val rid = e("researcherId").str
          row(rid) match
            case None    => out += s"no researcher row for $rid"
            case Some(x) =>
              need(
                BigDecimal(x.distinctCoauthorCount).compare(bd(e("distinctCoauthorCount"))) == 0,
                s"row C ${x.distinctCoauthorCount}"
              )
              need(
                BigDecimal(x.weightedDegree).compare(bd(e("weightedDegree"))) == 0,
                s"row W ${x.weightedDegree}"
              )
              need(
                BigDecimal(x.publicationCount).compare(bd(e("publicationCount"))) == 0,
                s"row P ${x.publicationCount}"
              )
          claimWith(s"stating C=${e("distinctCoauthorCount")} for $rid")(cl =>
            states(cl.text, bd(e("distinctCoauthorCount"))) && cites(cl)(onSubject(rid))
          )
        case "coauthor-breadth" | "bridge-researchers" | "topics" | "institutions"
            if e.contains("top") =>
          val top = e("top").str
          need(
            r.answer.researchers.headOption.map(_.researcherId).contains(top),
            s"first researcher row ${r.answer.researchers.headOption.map(_.displayName)}, expected ${e("topName").str}"
          )
          row(top).foreach { x =>
            val v = e("metric").str match
              case "bridgeScore" => x.bridgeMetric.map(m => BigDecimal(m.value))
              case _             => Some(BigDecimal(x.distinctCoauthorCount))
            need(
              v.exists(_.compare(bd(e("topValue"))) == 0),
              s"top row value $v, expected ${e("topValue")}"
            )
          }
          claimWith(s"naming ${e("topName").str} with ${e("topValue")}")(cl =>
            cl.text.contains(e("topName").str) && states(cl.text, bd(e("topValue"))) && cites(cl)(
              onSubject(top)
            )
          )
        case "maximum-team-size" =>
          val rid = e("researcherId").str
          val works = strs(e("maximumTeamWorkIds"))
          row(rid) match
            case None    => out += s"no researcher row for $rid"
            case Some(x) =>
              need(
                BigDecimal(x.maximumCoauthorsOnOnePaper)
                  .compare(bd(e("maximumCoauthorsOnOnePaper"))) == 0,
                s"row M ${x.maximumCoauthorsOnOnePaper}"
              )
              need(
                x.maximumTeamWorkIds.sorted == works.sorted,
                s"row team works ${x.maximumTeamWorkIds}"
              )
          claimWith(s"stating M=${e("maximumCoauthorsOnOnePaper")} citing the team publication")(
            cl =>
              states(cl.text, bd(e("maximumCoauthorsOnOnePaper"))) &&
                cites(cl)(x => kind(x) == "PUBLICATION" && x.workId.exists(works.contains))
          )
        case "direct-collaboration" | "evidence" if e.contains("researcherA") =>
          if e("found").bool then
            val edgeId = e("edgeId").str
            def onEdge(x: EvidenceItem) = x.values.get("coauthorEdgeId").contains(edgeId)
            claimWith(
              s"citing edge $edgeId and stating ${e("jointPublicationCount")} joint publications"
            )(cl =>
              cites(cl)(onEdge) && states(cl.text, bd(e("jointPublicationCount"))) && names.forall(
                cl.text.contains
              )
            )
            if q("category").str == "evidence" then
              strs(e("supportingWorkIds")).foreach(wid =>
                claimWith(s"citing supporting publication $wid")(cl =>
                  cites(cl)(x => kind(x) == "PUBLICATION" && x.workId.contains(wid))
                )
              )
          else
            val pair = Set(e("researcherA").str, e("researcherB").str)
            claimWith("citing the found=false edge evidence")(cl =>
              cites(cl)(x =>
                kind(x) == "COAUTHOR_EDGE" && x.researcherIds.toSet == pair && x.values
                  .get("found")
                  .contains("false")
              )
            )
        case "shortest-path" | "evidence" =>
          if e("found").bool then
            val pathNames = strs(e("pathNames"))
            claimWith(s"stating the ${e("hops")}-hop path ${pathNames.mkString(" -> ")}")(cl =>
              cites(cl)(x => kind(x) == "GRAPH_PATH") && states(
                cl.text,
                bd(e("hops"))
              ) && namesInOrder(cl.text, pathNames)
            )
            if q("category").str == "evidence" then
              e("hopEvidence").arr.foreach { h =>
                val ws = strs(h("supportingWorkIds"))
                claimWith(s"citing hop ${h("edgeId").str} with a supporting publication")(cl =>
                  cites(cl)(_.values.get("coauthorEdgeId").contains(h("edgeId").str)) &&
                    cites(cl)(x => kind(x) == "PUBLICATION" && x.workId.exists(ws.contains))
                )
              }
          else
            claimWith("citing the found=false path evidence")(cl =>
              cites(cl)(x => kind(x) == "GRAPH_PATH" && x.values.get("found").contains("false"))
            )
        case "components" =>
          val rid = e("researcherId").str
          need(
            row(rid).exists(_.componentId == e("componentId").str),
            s"row componentId ${row(rid).map(_.componentId)}"
          )
          claimWith(s"stating component ${e("componentId").str} of ${e("componentSize")}")(cl =>
            cl.text.contains(e("componentId").str) && states(
              cl.text,
              bd(e("componentSize"))
            ) && cites(cl)(onSubject(rid))
          )
        case "communities" =>
          val rid = e("researcherId").str
          val cid = e("communityId").str
          need(
            row(rid).exists(_.communityId.contains(cid)),
            s"row communityId ${row(rid).map(_.communityId)}"
          )
          claimWith(s"naming community $cid")(cl =>
            cl.text.contains(cid) && cites(cl)(onSubject(rid))
          )
          val gap = (r.answer.summary +: kept.flatMap(_.uncertainty)).exists(t =>
            t.contains(cid) && t.toLowerCase.contains("community size") &&
              (t.contains("not available") || t.toLowerCase.contains("missing"))
          )
          need(gap, s"the answer does not state that the size of $cid is missing")
          val sized = kept.filter(cl =>
            """\b\d+\s+(members|researchers in (the|this|its) community)""".r
              .findFirstIn(cl.text)
              .isDefined
          )
          need(sized.isEmpty, s"a kept claim states a community size: ${sized.map(_.text)}")
        case "bridge-researchers" =>
          val rid = e("researcherId").str
          need(
            row(rid).exists(
              _.bridgeMetric.exists(m => BigDecimal(m.value).compare(bd(e("bridgeScore"))) == 0)
            ),
            s"row bridge ${row(rid).map(_.bridgeMetric)}"
          )
          claimWith(s"stating bridge score ${e("bridgeScore")}")(cl =>
            states(cl.text, bd(e("bridgeScore"))) && cites(cl)(onSubject(rid))
          )
          val cc = e("communitiesConnected").num.toInt
          val ccd = e("crossCommunityDegree").num.toInt
          claimWith(s"stating $cc communities connected")(cl =>
            states(cl.text, BigDecimal(cc)) && cites(cl)(onSubject(rid))
          )
          // communitiesConnected counts the researcher's own community: with ccd cross-community
          // coauthors it links at most ccd other communities.
          val other = """(\d+) other communit""".r
          kept.foreach(cl =>
            other
              .findAllMatchIn(cl.text)
              .map(_.group(1).toInt)
              .foreach(n =>
                need(
                  n <= ccd,
                  s"${cl.claimId} says '$n other communities' with only $ccd cross-community coauthors: '${cl.text}'"
                )
              )
          )
        case "temporal-change" =>
          val rid = e("researcherId").str
          e("byWindow").obj.foreach { (win, v) =>
            claimWith(s"stating C=${v("distinctCoauthorCount")} in $win")(cl =>
              cl.text.contains(win) && states(cl.text, bd(v("distinctCoauthorCount"))) &&
                cites(cl)(x => onSubject(rid)(x) && x.values.get("window").contains(win))
            )
          }
        case other => out += s"no answer check for $other"
      out.result()

  def report(outcomes: Vector[Outcome]): String =
    val sb = new StringBuilder
    val n = outcomes.size
    def pct(k: Int, of: Int) = if of == 0 then "n/a" else f"${100.0 * k / of}%.1f%%"
    sb ++= s"Workflow benchmark, ${BenchmarkQuestions.json("version").str}, $n questions, " +
      s"graph run ${BenchmarkStore.GraphRun}, ER run ${BenchmarkRuntime.ErRun}, windows " +
      s"${BenchmarkRuntime.Windows.toVector.sorted.mkString(", ")}, deterministic synthesizer, InMemoryAuditLog\n"
    sb ++= s"${BenchmarkRuntime.loadNote}\n\n"
    sb ++= "| ID | Category | D | A | Pass | P | Verification | Semantic returned/relevant |\n|---|---|---|---|---|---|---|---|\n"
    outcomes.foreach { o =>
      def pf(b: Boolean) = if b then "pass" else "FAIL"
      sb ++= s"| ${o.id} | ${o.category} | ${pf(o.deterministic.isEmpty)} | ${pf(o.answer.isEmpty)} | ${pf(o.passed)} | " +
        s"${pf(o.planner.isEmpty)} | ${o.verification} | ${o.semantic.returned}/${o.semantic.relevant} |\n"
    }
    sb ++= "\nPer category (D = deterministic retrieval, A = answer, Pass = D and A; S = semantic retrieval):\n\n"
    sb ++= "| Category | Questions | D | A | Pass | S hit (>= 1 relevant) | S relevant/returned |\n|---|---|---|---|---|---|---|\n"
    outcomes.groupBy(_.category).toVector.sortBy(_._2.head.id).foreach { (cat, os) =>
      val ret = os.map(_.semantic.returned).sum
      val rel = os.map(_.semantic.relevant).sum
      sb ++= s"| $cat | ${os.size} | ${os.count(_.deterministic.isEmpty)}/${os.size} | ${os.count(_.answer.isEmpty)}/${os.size} | " +
        s"${os.count(_.passed)}/${os.size} | ${os.count(_.semantic.relevant > 0)}/${os.size} | $rel/$ret |\n"
    }
    val d = outcomes.count(_.deterministic.isEmpty)
    val a = outcomes.count(_.answer.isEmpty)
    val p = outcomes.count(_.passed)
    val pl = outcomes.count(_.planner.isEmpty)
    val ret = outcomes.map(_.semantic.returned).sum
    val rel = outcomes.map(_.semantic.relevant).sum
    val hit = outcomes.count(_.semantic.relevant > 0)
    sb ++= s"\nDeterministic retrieval score (D): $d/$n (${pct(d, n)})\n"
    sb ++= s"Answer score (A): $a/$n (${pct(a, n)})\n"
    sb ++= s"Benchmark pass (D and A): $p/$n (${pct(p, n)})\n"
    sb ++= s"Planner from question text alone (P): $pl/$n (${pct(pl, n)})\n"
    sb ++= s"Semantic retrieval (S, semantic-relevance-rule-v1): hit rate $hit/$n (${pct(hit, n)}), " +
      s"precision $rel/$ret (${pct(rel, ret)}) relevant of returned items\n"
    val types = outcomes.flatMap(_.semantic.byType).groupMapReduce(_._1)(_._2)(_ + _)
    sb ++= s"Semantic items by document type: ${types.toVector.sorted.map((k, v) => s"$k $v").mkString(", ")}\n"
    sb ++= "\nFailures:\n"
    outcomes.foreach { o =>
      o.deterministic.foreach(m => sb ++= s"- ${o.id} D: $m\n")
      o.answer.foreach(m => sb ++= s"- ${o.id} A: $m\n")
      o.planner.foreach(m => sb ++= s"- ${o.id} P: $m\n")
    }
    sb.toString

class BenchmarkWorkflowSuite extends munit.FunSuite:
  import BenchmarkScoring.*

  override val munitTimeout = scala.concurrent.duration.Duration(40, "min")

  private def outcomes: Vector[Outcome] =
    assume(BenchmarkRuntime.present, s"run data absent at ${BenchmarkStore.runDir}")
    BenchmarkWorkflowSuite.outcomes

  private def outcome(id: String): Outcome = outcomes.find(_.id == id).get

  BenchmarkQuestions.questions.foreach { q =>
    val id = q("id").str
    val label = s"$id (${q("category").str})"
    test(s"$label deterministic retrieval holds the expected graph and metric facts") {
      val o = outcome(id)
      assert(
        o.deterministic.isEmpty,
        o.deterministic.mkString("\n", "\n", "\n") + q("question").str
      )
    }
    test(s"$label answer states the expected facts with evidence") {
      val o = outcome(id)
      assert(o.answer.isEmpty, o.answer.mkString("\n", "\n", "\n") + q("question").str)
    }
  }

  test("the planner extracts names, window, topic, institution and operation from the text alone") {
    val bad = outcomes.filter(_.planner.nonEmpty)
    assert(
      bad.isEmpty,
      bad.map(o => s"${o.id}: ${o.planner.mkString("; ")}").mkString("\n", "\n", "\n")
    )
  }

  test("semantic retrieval is scored on the semantic partition only, apart from deterministic") {
    val runs = BenchmarkRuntime.benchmark.flatMap(_.result.toOption)
    assume(runs.nonEmpty)
    runs.foreach { r =>
      val b = r.evidence.bundle
      assert(
        b.semantic.forall(i => Set("SEMANTIC_PROFILE", "TEXT_CHUNK")(kind(i))),
        r.answer.queryId
      )
      assert(b.semantic.forall(_.values.get("trust").contains("untrusted-data")), r.answer.queryId)
      assert(
        b.deterministic.forall(i =>
          !Set("SEMANTIC_PROFILE", "TEXT_CHUNK", "IDENTITY_RESOLUTION")(kind(i))
        ),
        r.answer.queryId
      )
      assert(b.identity.forall(i => kind(i) == "IDENTITY_RESOLUTION"), r.answer.queryId)
      val ids = b.semantic.map(_.evidenceId).toSet
      assert(
        b.deterministic.forall(i => !ids(i.evidenceId)) && b.identity.forall(i =>
          !ids(i.evidenceId)
        )
      )
    }
    val text = report(outcomes)
    val out = BenchmarkStore.repoRoot.resolve("target/tests/adversarial/benchmark-results-v1.md")
    Files.createDirectories(out.getParent)
    Files.writeString(out, text, UTF_8)
    println(text)
  }

object BenchmarkWorkflowSuite:
  lazy val outcomes: Vector[BenchmarkScoring.Outcome] =
    BenchmarkRuntime.benchmark.map(BenchmarkScoring.score)
