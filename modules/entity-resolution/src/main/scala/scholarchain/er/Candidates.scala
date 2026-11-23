package scholarchain.er

import scholarchain.core.*
import upickle.default.ReadWriter

/** A Phase 04 `match-candidate-v1` line. The record is defined by the Hadoop stage and is not a
  * core contract yet, so entity resolution reads it through this mirror of its wire shape instead
  * of importing the hadoop module (stages exchange data only through files). Decoding is strict: an
  * unknown or missing field is a reject.
  */
final case class ErCandidate(
    schemaVersion: String,
    candidateId: String,
    kind: EntityKind,
    sourceId: String,
    targetId: String,
    blockKey: String,
    features: Map[String, Double],
    sourceIdentifiers: Map[String, String],
    targetIdentifiers: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** One candidate pair after merging every copy of it (a pair can be emitted under several keys: DOI
  * and arXiv, exact and blocked). Upstream features are merged by maximum, which keeps every
  * conflict flag and the largest distance; identifiers are merged as sets.
  */
final case class MergedCandidate(
    candidateId: String,
    kind: EntityKind,
    sourceId: String,
    targetId: String,
    blockKeys: Vector[String],
    upstreamFeatures: Map[String, Double],
    sourceIdentifiers: Map[String, Vector[String]],
    targetIdentifiers: Map[String, Vector[String]],
    upstreamRunId: String,
    upstreamPolicyVersion: Option[String],
    snapshot: String,
    copies: Int
) derives ReadWriter

object ErCandidate:
  val SchemaVersion = "match-candidate-v1"

  /** The candidate ID formula of the Hadoop stage: `mc:<kind>:` + h(ordered pair, 20). */
  def idOf(kind: EntityKind, a: String, b: String): String =
    val (x, y) = Ids.orderedPair(a, b)
    s"mc:${kind.toString.toLowerCase(java.util.Locale.ROOT)}:" + Hashing.short(s"$x|$y")

  private def endpointOk(kind: EntityKind, id: String): Boolean = kind match
    case EntityKind.RESEARCHER => Ids.isMentionId(id) || Ids.isResearcherId(id)
    case EntityKind.WORK       => Ids.isWorkId(id)

  def validate(c: ErCandidate): Validate.Errors =
    def check(ok: Boolean, msg: => String) = if ok then Vector.empty else Vector(msg)
    check(
      c.schemaVersion == SchemaVersion,
      s"schemaVersion '${c.schemaVersion}' is not $SchemaVersion"
    ) ++
      check(c.sourceId != c.targetId, s"candidate ${c.candidateId} links a record to itself") ++
      check(
        c.sourceId == c.targetId || c.candidateId == idOf(c.kind, c.sourceId, c.targetId),
        s"candidate ${c.candidateId} does not match its endpoints"
      ) ++
      check(endpointOk(c.kind, c.sourceId), s"malformed ${c.kind} source '${c.sourceId}'") ++
      check(endpointOk(c.kind, c.targetId), s"malformed ${c.kind} target '${c.targetId}'") ++
      check(
        c.features.values.forall(v => !v.isNaN && !v.isInfinite),
        s"candidate ${c.candidateId} has a non-finite feature"
      ) ++
      check(Ids.isRunId(c.provenance.runId), s"malformed run ID '${c.provenance.runId}'") ++
      check(Validate.snapshotDate(c.provenance.snapshot), "provenance.snapshot is not a date") ++
      check(c.provenance.schemaVersion == SchemaVersion, "provenance.schemaVersion mismatch")

  def decode(line: String): Either[String, ErCandidate] =
    JsonLines.decodeValid[ErCandidate](line, validate)

  /** Merges every copy of one candidate ID. Copies must agree on kind, endpoints, run and snapshot.
    */
  def merge(copies: Vector[ErCandidate]): Either[String, MergedCandidate] =
    copies.headOption.toRight("no candidate copy").flatMap { head =>
      val shapes = copies.map(c =>
        (c.candidateId, c.kind, c.sourceId, c.targetId, c.provenance.runId, c.provenance.snapshot)
      )
      if shapes.distinct.size > 1 then
        Left(s"candidate ${head.candidateId} has copies with different endpoints, run or snapshot")
      else
        def ids(f: ErCandidate => Map[String, String]): Map[String, Vector[String]] =
          copies.flatMap(f).groupMap(_._1)(_._2).view.mapValues(_.distinct.sorted).toMap
        Right(
          MergedCandidate(
            head.candidateId,
            head.kind,
            head.sourceId,
            head.targetId,
            copies.map(_.blockKey).distinct.sorted,
            copies.flatMap(_.features).groupMapReduce(_._1)(_._2)(math.max),
            ids(_.sourceIdentifiers),
            ids(_.targetIdentifiers),
            head.provenance.runId,
            copies.flatMap(_.provenance.policyVersion).distinct.sorted.headOption,
            head.provenance.snapshot,
            copies.size
          )
        )
    }
