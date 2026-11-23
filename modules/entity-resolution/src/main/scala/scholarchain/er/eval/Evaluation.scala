package scholarchain.er.eval

import scholarchain.core.*
import scholarchain.er.{MatchPolicy, Resolver, Thresholds}
import upickle.default.ReadWriter

/** A human label. Only the human writes labels; this code reads them and never infers one. */
enum GoldLabel derives ReadWriter:
  case MATCH, NON_MATCH, UNSURE

/** One row of the human-labeled review file (the review-queue columns plus `label` and `note`). */
final case class GoldRow(
    pairId: String,
    kind: EntityKind,
    leftRecordId: String,
    rightRecordId: String,
    leftName: String,
    rightName: String,
    label: Option[GoldLabel],
    note: String,
    line: Int
)

/** Reads the gold CSV (RFC 4180, UTF-8, optional BOM, CRLF or LF). Labels are read exactly: MATCH,
  * NON_MATCH or UNSURE (case and surrounding spaces ignored), blank for unlabeled; any other value
  * is an error naming its line, never a guess.
  */
object GoldCsv:
  val RequiredColumns: Vector[String] =
    Vector("pair_id", "kind", "left_record_id", "right_record_id", "label", "note")

  /** Display-only columns, read when present (the review queue has them) and never used to join. */
  val NameColumns: (String, String) = ("left_name_or_title", "right_name_or_title")

  /** RFC 4180 records. A quote inside a quoted field is doubled; quoted fields may hold CR and LF.
    */
  def records(text: String): Either[String, Vector[Vector[String]]] =
    val s = text.stripPrefix("﻿")
    val rows = Vector.newBuilder[Vector[String]]
    val row = Vector.newBuilder[String]
    val field = new StringBuilder
    var i = 0
    var quoted = false
    var fieldStarted = false
    var inRow = false
    var error: Option[String] = None
    def endField(): Unit = { row += field.toString; field.clear(); fieldStarted = false }
    def endRow(): Unit = { endField(); rows += row.result(); row.clear(); inRow = false }
    while i < s.length && error.isEmpty do
      val ch = s.charAt(i)
      if ch != '\r' && ch != '\n' then inRow = true
      if quoted then
        if ch == '"' then
          if i + 1 < s.length && s.charAt(i + 1) == '"' then { field.append('"'); i += 1 }
          else
            quoted = false
            if i + 1 < s.length && !",\r\n".contains(s.charAt(i + 1)) then
              error = Some(s"character after a closing quote at offset ${i + 1}")
        else field.append(ch)
      else
        ch match
          case '"' if !fieldStarted && field.isEmpty => quoted = true; fieldStarted = true
          case '"'                                   => error = Some(s"stray quote at offset $i")
          case ','                                   => endField()
          case '\r' if i + 1 < s.length && s.charAt(i + 1) == '\n' => endRow(); i += 1
          case '\n' | '\r'                                         => endRow()
          case other => field.append(other); fieldStarted = true
      i += 1
    if error.isEmpty && quoted then error = Some("unterminated quoted field")
    if error.isEmpty && (inRow || field.nonEmpty || fieldStarted) then endRow()
    error.toLeft(rows.result().filterNot(r => r.forall(_.isEmpty)))

  def label(raw: String, line: Int): Either[String, Option[GoldLabel]] =
    raw.trim.toUpperCase(java.util.Locale.ROOT) match
      case ""          => Right(None)
      case "MATCH"     => Right(Some(GoldLabel.MATCH))
      case "NON_MATCH" => Right(Some(GoldLabel.NON_MATCH))
      case "UNSURE"    => Right(Some(GoldLabel.UNSURE))
      case _           =>
        Left(s"line $line: label '${raw.take(40)}' is not MATCH, NON_MATCH, UNSURE or blank")

  def parse(text: String): Either[String, Vector[GoldRow]] =
    records(text).flatMap {
      case header +: body =>
        val idx = header.map(_.trim).zipWithIndex.toMap
        RequiredColumns
          .find(c => !idx.contains(c))
          .map(c => s"gold file has no column $c")
          .toLeft(())
          .flatMap { _ =>
            val parsed = body.zipWithIndex.map { (r, i) =>
              val line = i + 2
              def col(c: String) = idx.get(c).flatMap(r.lift).getOrElse("")
              for
                _ <- Either.cond(
                  r.size == header.size,
                  (),
                  s"line $line has ${r.size} fields, header ${header.size}"
                )
                kind <- col("kind").trim.toLowerCase(java.util.Locale.ROOT) match
                  case "researcher" => Right(EntityKind.RESEARCHER)
                  case "work"       => Right(EntityKind.WORK)
                  case other        => Left(s"line $line: kind '$other' is not researcher or work")
                l <- label(col("label"), line)
                _ <- Either.cond(col("pair_id").trim.nonEmpty, (), s"line $line has no pair_id")
              yield GoldRow(
                col("pair_id").trim,
                kind,
                col("left_record_id").trim,
                col("right_record_id").trim,
                col(NameColumns._1).trim,
                col(NameColumns._2).trim,
                l,
                col("note"),
                line
              )
            }
            parsed
              .collectFirst { case Left(e) => e }
              .toLeft(parsed.collect { case Right(g) => g })
              .flatMap { rows =>
                val dup = rows.groupBy(_.pairId).collectFirst { case (id, rs) if rs.size > 1 => id }
                dup.map(id => s"pair_id $id appears more than once").toLeft(rows)
              }
          }
      case _ => Left("gold file is empty")
    }

/** Confusion of human labels (MATCH, NON_MATCH) against the four decision outcomes. UNSURE rows are
  * never counted here; they are reported separately.
  */
final case class Confusion(
    matchAccepted: Int,
    matchRejected: Int,
    matchUnresolved: Int,
    matchConflict: Int,
    nonMatchAccepted: Int,
    nonMatchRejected: Int,
    nonMatchUnresolved: Int,
    nonMatchConflict: Int
) derives ReadWriter:
  def add(label: GoldLabel, o: MatchOutcome): Confusion = (label, o) match
    case (GoldLabel.MATCH, MatchOutcome.ACCEPTED)     => copy(matchAccepted = matchAccepted + 1)
    case (GoldLabel.MATCH, MatchOutcome.REJECTED)     => copy(matchRejected = matchRejected + 1)
    case (GoldLabel.MATCH, MatchOutcome.UNRESOLVED)   => copy(matchUnresolved = matchUnresolved + 1)
    case (GoldLabel.MATCH, MatchOutcome.CONFLICT)     => copy(matchConflict = matchConflict + 1)
    case (GoldLabel.NON_MATCH, MatchOutcome.ACCEPTED) =>
      copy(nonMatchAccepted = nonMatchAccepted + 1)
    case (GoldLabel.NON_MATCH, MatchOutcome.REJECTED) =>
      copy(nonMatchRejected = nonMatchRejected + 1)
    case (GoldLabel.NON_MATCH, MatchOutcome.UNRESOLVED) =>
      copy(nonMatchUnresolved = nonMatchUnresolved + 1)
    case (GoldLabel.NON_MATCH, MatchOutcome.CONFLICT) =>
      copy(nonMatchConflict = nonMatchConflict + 1)
    case (GoldLabel.UNSURE, _) => this

  def matches: Int = matchAccepted + matchRejected + matchUnresolved + matchConflict
  def nonMatches: Int = nonMatchAccepted + nonMatchRejected + nonMatchUnresolved + nonMatchConflict
  def labeled: Int = matches + nonMatches

  /** Metrics. Precision is over ACCEPTED decisions. Recall is over every MATCH label, so a MATCH
    * left UNRESOLVED or CONFLICT counts against it; `recallDecided` is over MATCH labels that got a
    * definite decision. Coverage is the share of labeled pairs with ACCEPTED or REJECTED. A metric
    * with an empty denominator is None, never 0.
    */
  def metrics: Metrics =
    def ratio(n: Int, d: Int): Option[Double] = Option.when(d > 0)(n.toDouble / d)
    val tp = matchAccepted
    val fp = nonMatchAccepted
    val fn = matchRejected
    val tn = nonMatchRejected
    val precision = ratio(tp, tp + fp)
    val recall = ratio(tp, matches)
    val f1 = for p <- precision; r <- recall if p + r > 0 yield 2 * p * r / (p + r)
    Metrics(
      labeled,
      matches,
      nonMatches,
      tp,
      fp,
      fn,
      tn,
      matchUnresolved + nonMatchUnresolved,
      matchConflict + nonMatchConflict,
      precision,
      recall,
      ratio(tp, tp + fn),
      f1.orElse(Option.when(precision.nonEmpty && recall.nonEmpty)(0.0)),
      ratio(tp + fp + fn + tn, labeled),
      ratio(matchUnresolved + nonMatchUnresolved, labeled),
      Wilson.interval95(tp, tp + fp),
      Wilson.interval95(tp, matches)
    )

/** A closed interval [low, high] of a proportion. */
final case class Interval(low: Double, high: Double) derives ReadWriter

/** Wilson score interval for a binomial proportion. It stays inside [0, 1] and is informative at 0
  * or n successes, which matters for small samples such as 13 NON_MATCH labels. None when n is 0.
  */
object Wilson:
  /** The two-sided 95% standard-normal quantile; the definition of a 95% interval, not a tunable.
    */
  val Z95: Double = 1.959963984540054

  def interval(successes: Int, n: Int, z: Double): Option[Interval] =
    Option.when(n > 0 && successes >= 0 && successes <= n) {
      val p = successes.toDouble / n
      val z2 = z * z
      val denom = 1 + z2 / n
      val centre = (p + z2 / (2 * n)) / denom
      val half = z * math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom
      Interval(math.max(0.0, centre - half), math.min(1.0, centre + half))
    }

  def interval95(successes: Int, n: Int): Option[Interval] = interval(successes, n, Z95)

object Confusion:
  val Empty: Confusion = Confusion(0, 0, 0, 0, 0, 0, 0, 0)
  def of(pairs: Iterable[(GoldLabel, MatchOutcome)]): Confusion =
    pairs.foldLeft(Empty)((c, p) => c.add(p._1, p._2))

final case class Metrics(
    labeled: Int,
    matches: Int,
    nonMatches: Int,
    tp: Int,
    fp: Int,
    fn: Int,
    tn: Int,
    unresolved: Int,
    conflict: Int,
    precision: Option[Double],
    recall: Option[Double],
    recallDecided: Option[Double],
    f1: Option[Double],
    coverage: Option[Double],
    unresolvedRate: Option[Double],
    precisionWilson95: Option[Interval],
    recallWilson95: Option[Interval]
) derives ReadWriter

final case class ErrorExample(
    pairId: String,
    kind: EntityKind,
    label: GoldLabel,
    decision: MatchOutcome,
    path: MatchPath,
    confidence: Double,
    score: Double,
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    sourceId: String,
    targetId: String,
    leftName: String,
    rightName: String,
    note: String
) derives ReadWriter

final case class SweepPoint(accept: Double, reject: Double, isPolicy: Boolean, metrics: Metrics)
    derives ReadWriter

final case class UnsureSummary(count: Int, byOutcome: Map[String, Int], byPath: Map[String, Int])
    derives ReadWriter

final case class KindReport(
    kind: EntityKind,
    confusion: Confusion,
    overall: Metrics,
    byPath: Map[String, Metrics],
    unsure: UnsureSummary,
    falsePositives: Vector[ErrorExample],
    falseNegatives: Vector[ErrorExample],
    matchesNotDecided: Vector[ErrorExample],
    nonMatchesNotDecided: Vector[ErrorExample],
    unsureNotAccepted: Vector[ErrorExample],
    thresholdSensitivity: Vector[SweepPoint]
) derives ReadWriter

final case class EvaluationReport(
    evalVersion: String,
    policyVersion: String,
    decisionRunId: String,
    thresholds: Thresholds,
    goldRows: Int,
    countedRows: Int,
    unsureRows: Int,
    unlabeledRows: Int,
    rowsWithoutDecision: Vector[String],
    endpointMismatches: Vector[String],
    runOutcomes: Map[String, Map[String, Int]],
    researcher: KindReport,
    work: KindReport
) derives ReadWriter

/** Evaluation settings, from `/scholarchain/er/er-eval-v1.json`. The grids describe the threshold
  * sensitivity report only; they never change er-v1.
  */
final case class EvalConfig(
    evalVersion: String,
    acceptGrid: Vector[Double],
    rejectGrid: Vector[Double],
    maxExamples: Int
) derives ReadWriter

object EvalConfig:
  val Resource = "/scholarchain/er/er-eval-v1.json"
  def load(): Either[String, EvalConfig] =
    Option(getClass.getResourceAsStream(Resource))
      .toRight(s"$Resource not found")
      .map { in =>
        try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        finally in.close()
      }
      .flatMap(JsonLines.decode[EvalConfig](_))
      .filterOrElse(_.maxExamples >= 1, "maxExamples must be >= 1")

/** Gold-set evaluation, separately for researcher and work resolution and per match path.
  *
  * Labels are only read here, after the decisions exist; nothing in the matching code depends on
  * this package, so labels cannot leak into decisions. Threshold sensitivity re-decides only the
  * probabilistic-path decisions from their recorded `er_score` (without adjudication) and is a
  * report, never an input to er-v1.
  */
object Evaluation:
  private def candidateIdOf(d: MatchDecision): String = d.provenance.sourceRecordId

  def score(d: MatchDecision): Double = d.features.getOrElse(Resolver.ScoreFeature, Double.NaN)

  def isProbabilistic(d: MatchDecision): Boolean =
    d.rulesFired.headOption.exists(MatchPolicy.ProbabilisticRules)

  /** The outcome under other thresholds: probabilistic-path decisions re-decided from their score
    * (no adjudication), every other decision unchanged.
    */
  def redecide(d: MatchDecision, accept: Double, reject: Double): MatchOutcome =
    if !isProbabilistic(d) then d.decision
    else
      val s = score(d)
      if s >= accept then MatchOutcome.ACCEPTED
      else if s <= reject then MatchOutcome.REJECTED
      else MatchOutcome.UNRESOLVED

  private def example(g: GoldRow, d: MatchDecision, label: GoldLabel) =
    ErrorExample(
      g.pairId,
      g.kind,
      label,
      d.decision,
      d.path,
      d.confidence,
      score(d),
      d.rulesFired,
      d.reasonCodes,
      d.sourceId,
      d.targetId,
      g.leftName,
      g.rightName,
      g.note
    )

  def kindReport(
      kind: EntityKind,
      joined: Vector[(GoldRow, MatchDecision)],
      cfg: EvalConfig,
      t: Thresholds
  ): KindReport =
    val ofKind = joined.filter(_._1.kind == kind)
    val counted = ofKind.collect {
      case (g, d) if g.label.exists(_ != GoldLabel.UNSURE) => (g, d, g.label.get)
    }
    val unsureRows = ofKind.filter(_._1.label.contains(GoldLabel.UNSURE))
    val unsure = unsureRows.map(_._2)
    val confusion = Confusion.of(counted.map((_, d, l) => l -> d.decision))
    // every match path is listed, an empty one with labeled = 0, so an absent path is visible
    val byPath = MatchPath.values.toVector.map { p =>
      p.toString -> Confusion
        .of(counted.filter(_._2.path == p).map((_, d, l) => l -> d.decision))
        .metrics
    }.toMap
    def pickFrom(xs: Vector[(GoldRow, MatchDecision, GoldLabel)])(
        f: (GoldLabel, MatchOutcome) => Boolean
    ) =
      xs
        .filter((_, d, l) => f(l, d.decision))
        .sortBy((g, _, _) => g.pairId)
        .take(cfg.maxExamples)
        .map((g, d, l) => example(g, d, l))
    def pick(f: (GoldLabel, MatchOutcome) => Boolean) = pickFrom(counted)(f)
    val sweep = for
      a <- cfg.acceptGrid
      r <- cfg.rejectGrid
      if r < a
    yield SweepPoint(
      a,
      r,
      a == t.accept && r == t.reject,
      Confusion.of(counted.map((_, d, l) => l -> redecide(d, a, r))).metrics
    )
    KindReport(
      kind,
      confusion,
      confusion.metrics,
      byPath,
      UnsureSummary(
        unsure.size,
        unsure.groupBy(_.decision.toString).view.mapValues(_.size).toMap,
        unsure.groupBy(_.path.toString).view.mapValues(_.size).toMap
      ),
      pick((l, o) => l == GoldLabel.NON_MATCH && o == MatchOutcome.ACCEPTED),
      pick((l, o) => l == GoldLabel.MATCH && o == MatchOutcome.REJECTED),
      pick((l, o) =>
        l == GoldLabel.MATCH && (o == MatchOutcome.UNRESOLVED || o == MatchOutcome.CONFLICT)
      ),
      pick((l, o) =>
        l == GoldLabel.NON_MATCH && (o == MatchOutcome.UNRESOLVED || o == MatchOutcome.CONFLICT)
      ),
      pickFrom(unsureRows.map((g, d) => (g, d, GoldLabel.UNSURE)))((_, o) =>
        o != MatchOutcome.ACCEPTED
      ),
      sweep
    )

  /** Joins labels to decisions by candidate ID (the decision's `provenance.sourceRecordId`) and
    * checks that each row names the decision's two endpoints and kind; a row that does not is
    * listed as a mismatch and left out of every metric.
    */
  def evaluate(
      rows: Vector[GoldRow],
      decisions: Vector[MatchDecision],
      cfg: EvalConfig,
      t: Thresholds
  ): Either[String, EvaluationReport] =
    val runs = decisions.map(_.provenance.runId).distinct
    val policies = decisions.map(_.policyVersion).distinct
    val dupes = decisions.groupBy(candidateIdOf).collectFirst { case (id, ds) if ds.size > 1 => id }
    for
      runId <- runs match
        case Vector(r) => Right(r)
        case other     => Left(s"decisions must come from exactly one run, got ${other.size}")
      policy <- policies match
        case Vector(p) => Right(p)
        case other     => Left(s"decisions must come from exactly one policy, got ${other.size}")
      _ <- dupes.map(id => s"candidate $id has more than one decision").toLeft(())
    yield
      val byCandidate = decisions.map(d => candidateIdOf(d) -> d).toMap
      val labeled = rows.filter(_.label.nonEmpty)
      val (found, missing) = labeled.partition(g => byCandidate.contains(g.pairId))
      val checked = found.map(g => g -> byCandidate(g.pairId))
      val (ok, bad) = checked.partition((g, d) =>
        g.kind == d.kind && Set(g.leftRecordId, g.rightRecordId) == Set(d.sourceId, d.targetId)
      )
      EvaluationReport(
        cfg.evalVersion,
        policy,
        runId,
        t,
        rows.size,
        ok.count(_._1.label.exists(_ != GoldLabel.UNSURE)),
        ok.count(_._1.label.contains(GoldLabel.UNSURE)),
        rows.count(_.label.isEmpty),
        missing.map(_.pairId).sorted,
        bad.map((g, d) =>
          s"${g.pairId} (line ${g.line}): row names ${g.leftRecordId}, ${g.rightRecordId}; decision ${d.sourceId}, ${d.targetId}"
        ),
        decisions
          .groupBy(_.kind.toString)
          .map((k, ds) => k -> ds.groupBy(_.decision.toString).view.mapValues(_.size).toMap),
        kindReport(EntityKind.RESEARCHER, ok, cfg, t),
        kindReport(EntityKind.WORK, ok, cfg, t)
      )

  private def pct(v: Option[Double]): String = v.fold("n/a")(x => f"$x%.3f")
  private def ci(v: Option[Interval]): String = v.fold("n/a")(i => f"[${i.low}%.3f, ${i.high}%.3f]")
  private def cell(s: String): String = s.replace("|", "/").replace("\n", " ").replace("\r", " ")

  /** How UNRESOLVED and CONFLICT enter the metrics; printed in every report. */
  val CountingRules: Vector[String] = Vector(
    "Counting rules: only MATCH and NON_MATCH labels are counted; UNSURE rows are reported separately.",
    "TP = MATCH accepted, FP = NON_MATCH accepted, TN = NON_MATCH rejected, FN = MATCH rejected.",
    "UNRESOLVED and CONFLICT are not definite decisions: they are outside TP, FP, TN and FN and lower coverage.",
    "Precision = TP / (TP + FP). Recall = TP / all MATCH labels, so a MATCH left UNRESOLVED or CONFLICT counts against recall; " +
      "recall (decided) = TP / (TP + FN). F1 uses precision and recall.",
    "Coverage = (TP + FP + TN + FN) / labeled; unresolved rate = UNRESOLVED / labeled. CIs are Wilson 95% score intervals."
  )

  def markdown(r: EvaluationReport): String =
    def metricsRow(name: String, m: Metrics) =
      if m.labeled == 0 then s"| $name | 0 pairs | | | | | | | | | | | | | |"
      else
        s"| $name | ${m.labeled} | ${m.tp} | ${m.fp} | ${m.tn} | ${m.fn} | ${m.unresolved} | ${m.conflict} | " +
          s"${pct(m.precision)} | ${ci(m.precisionWilson95)} | ${pct(m.recall)} | ${ci(m.recallWilson95)} | " +
          s"${pct(m.recallDecided)} | ${pct(m.f1)} | ${pct(m.coverage)} |"
    def examples(title: String, xs: Vector[ErrorExample]) =
      Vector("", s"#### $title (${xs.size} shown)", "") ++
        (if xs.isEmpty then Vector("None.")
         else
           Vector(
             "| pair | left name or title | right name or title | label | decision | path | rules | confidence | score | reasons | note |",
             "|---|---|---|---|---|---|---|---|---|---|---|"
           ) ++
             xs.map(e =>
               f"| ${e.pairId} | ${cell(e.leftName).take(90)} | ${cell(e.rightName).take(90)} | ${e.label} | ${e.decision} | ${e.path} | " +
                 f"${e.rulesFired.mkString(" ")} | ${e.confidence}%.3f | ${e.score}%.3f | ${e.reasonCodes.mkString(" ")} | ${cell(e.note).take(80)} |"
             ))
    def kind(k: KindReport) =
      val c = k.confusion
      Vector(
        "",
        s"### ${k.kind.toString.toLowerCase} resolution",
        "",
        "Confusion matrix (rows: human label; columns: decision):",
        "",
        "| label | ACCEPTED | REJECTED | UNRESOLVED | CONFLICT |",
        "|---|---|---|---|---|",
        s"| MATCH | ${c.matchAccepted} | ${c.matchRejected} | ${c.matchUnresolved} | ${c.matchConflict} |",
        s"| NON_MATCH | ${c.nonMatchAccepted} | ${c.nonMatchRejected} | ${c.nonMatchUnresolved} | ${c.nonMatchConflict} |",
        "",
        "| scope | labeled | TP | FP | TN | FN | unresolved | conflict | precision | precision 95% CI | recall | recall 95% CI | recall (decided) | F1 | coverage |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
        metricsRow("all paths", k.overall)
      ) ++ k.byPath.toVector.sortBy(_._1).map((p, m) => metricsRow(p, m)) ++ Vector(
        "",
        s"Unresolved rate (labeled): ${pct(k.overall.unresolvedRate)}.",
        "",
        s"UNSURE rows (not counted above): ${k.unsure.count}; by decision " +
          k.unsure.byOutcome.toVector.sorted.map((o, n) => s"$o $n").mkString(", ") +
          "; by path " + k.unsure.byPath.toVector.sorted.map((p, n) => s"$p $n").mkString(", ")
      ) ++ examples("False positives (NON_MATCH accepted)", k.falsePositives) ++
        examples("False negatives (MATCH rejected)", k.falseNegatives) ++
        examples("MATCH left unresolved or in conflict", k.matchesNotDecided) ++
        examples("NON_MATCH left unresolved or in conflict", k.nonMatchesNotDecided) ++
        examples("UNSURE rows not accepted", k.unsureNotAccepted) ++ Vector(
          "",
          "#### Threshold sensitivity (reported only; er-v1 is not retuned)",
          "",
          "Only PROBABILISTIC-path decisions are re-decided from their recorded er_score, without adjudication; every other path keeps its decision.",
          "",
          "| accept | reject | policy | TP | FP | TN | FN | unresolved | precision | recall | F1 | coverage |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|"
        ) ++ k.thresholdSensitivity.map(s =>
          f"| ${s.accept}%.2f | ${s.reject}%.2f | ${if s.isPolicy then "er-v1" else ""} | ${s.metrics.tp} | ${s.metrics.fp} | ${s.metrics.tn} | ${s.metrics.fn} | ${s.metrics.unresolved} | ${pct(s.metrics.precision)} | ${pct(s.metrics.recall)} | ${pct(s.metrics.f1)} | ${pct(s.metrics.coverage)} |"
        )
    val runLines = r.runOutcomes.toVector.sortBy(_._1).map { (k, os) =>
      val n = os.values.sum
      val u = os.getOrElse(MatchOutcome.UNRESOLVED.toString, 0)
      s"- $k: $n decisions; " + os.toVector.sorted.map((o, c) => s"$o $c").mkString(", ") +
        f"; unresolved rate ${u.toDouble / math.max(n, 1)}%.4f"
    }
    (Vector(
      s"## Entity-resolution evaluation (${r.evalVersion}, policy ${r.policyVersion}, run ${r.decisionRunId})",
      "",
      s"Thresholds: accept ${r.thresholds.accept}, reject ${r.thresholds.reject}, LLM band [${r.thresholds.llmBandMin}, ${r.thresholds.llmBandMax}).",
      s"Gold rows: ${r.goldRows}; counted (MATCH or NON_MATCH): ${r.countedRows}; UNSURE: ${r.unsureRows}; unlabeled: ${r.unlabeledRows}; " +
        s"labeled rows without a decision: ${r.rowsWithoutDecision.size}; endpoint mismatches: ${r.endpointMismatches.size}.",
      "",
      "Decision run outcomes (all decisions, labeled or not):",
      ""
    ) ++ runLines ++ Vector("") ++ CountingRules.map("- " + _) ++ kind(r.researcher) ++ kind(
      r.work
    )).mkString("\n") + "\n"
