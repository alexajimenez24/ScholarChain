package scholarchain.hadoop

import scholarchain.core.*
import upickle.default.ReadWriter

/** Schema versions of the wire records that only the Hadoop stage defines. `core` has no contract
  * for them yet (STATUS open item: ORCID profile and match candidate), so they are versioned here;
  * their provenance carries these versions in `provenance.schemaVersion`.
  */
object HadoopSchemas:
  val MatchCandidate = "match-candidate-v1"
  val ExternalResearcher = "external-researcher-v1"
  val AuthorPaper = "author-paper-v1"
  val Abstract = "abstract-v1"

/** The run a task belongs to; every derived record gets its provenance from here. */
final case class RunContext(runId: String, snapshot: String):
  def provenance(source: SourceType, sourceRecordId: String, schemaVersion: String): Provenance =
    Provenance(source, sourceRecordId, snapshot, runId, schemaVersion, Some(Normalize.Version))

/** An ORCID (or other external) researcher profile: identity evidence, not a graph work. */
final case class ExternalResearcher(
    schemaVersion: String,
    recordId: String,
    source: SourceType,
    orcid: Option[String],
    externalAuthorId: Option[String],
    displayName: String,
    nameVariants: Vector[String],
    affiliations: Vector[String],
    workDois: Vector[String],
    provenance: Provenance
) derives ReadWriter

/** One author mention on one work, keyed by mention ID and work ID (the Spark author-paper input).
  */
final case class AuthorPaper(
    mentionId: String,
    workId: String,
    position: Int,
    normalizedName: String,
    orcid: Option[String],
    year: Option[Int],
    teamSize: Int,
    provenance: Provenance
) derives ReadWriter

/** Abstract text kept apart from the work record, which holds only its hash reference. */
final case class AbstractRecord(
    workId: String,
    textHash: String,
    text: String,
    provenance: Provenance
) derives ReadWriter

/** A candidate link between two records with its deterministic features. The Hadoop stage emits
  * candidates only; it never decides a match. `sourceId` sorts before `targetId` for symmetric
  * pairs, so the same pair is the same record whichever side was read first.
  */
final case class MatchCandidate(
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

object MatchCandidate:
  def idOf(kind: EntityKind, a: String, b: String): String =
    val (x, y) = Ids.orderedPair(a, b)
    s"mc:${kind.toString.toLowerCase(java.util.Locale.ROOT)}:" + Hashing.short(s"$x|$y")

  /** A symmetric pair; the lexicographically smaller ID becomes the source. */
  def symmetric(
      kind: EntityKind,
      a: String,
      aIds: Map[String, String],
      b: String,
      bIds: Map[String, String],
      blockKey: String,
      features: Map[String, Double],
      run: RunContext
  ): MatchCandidate =
    val (x, xi, y, yi) = if a < b then (a, aIds, b, bIds) else (b, bIds, a, aIds)
    make(kind, x, xi, y, yi, blockKey, features, run)

  /** A directed link, for example a mention to a canonical ORCID researcher ID. */
  def make(
      kind: EntityKind,
      source: String,
      sourceIds: Map[String, String],
      target: String,
      targetIds: Map[String, String],
      blockKey: String,
      features: Map[String, Double],
      run: RunContext
  ): MatchCandidate =
    MatchCandidate(
      HadoopSchemas.MatchCandidate,
      idOf(kind, source, target),
      kind,
      source,
      target,
      blockKey,
      features,
      sourceIds,
      targetIds,
      run.provenance(SourceType.SCHOLARCHAIN, blockKey, HadoopSchemas.MatchCandidate)
    )

/** Validation for the Hadoop-local records (core validates its own). */
object HadoopValidate:
  private def check(cond: Boolean, msg: => String): Validate.Errors =
    if cond then Vector.empty else Vector(msg)

  private def provenance(p: Provenance, expected: String): Validate.Errors =
    check(p.sourceRecordId.trim.nonEmpty, "provenance.sourceRecordId is empty") ++
      check(Validate.snapshotDate(p.snapshot), s"provenance.snapshot '${p.snapshot}' is invalid") ++
      check(Ids.isRunId(p.runId), s"provenance.runId '${p.runId}' is malformed") ++
      check(
        p.schemaVersion == expected,
        s"schema version '${p.schemaVersion}' where '$expected' is required"
      )

  def candidate(c: MatchCandidate): Validate.Errors =
    check(
      c.schemaVersion == HadoopSchemas.MatchCandidate,
      s"unsupported schema version '${c.schemaVersion}'"
    ) ++
      check(c.sourceId != c.targetId, s"candidate ${c.candidateId} links a record to itself") ++
      check(
        c.candidateId == MatchCandidate.idOf(c.kind, c.sourceId, c.targetId),
        s"candidate ${c.candidateId} does not match its endpoints"
      ) ++
      check(
        c.features.values.forall(v => !v.isNaN && !v.isInfinite),
        s"candidate ${c.candidateId} has a non-finite feature"
      ) ++ provenance(c.provenance, HadoopSchemas.MatchCandidate)

  def externalResearcher(r: ExternalResearcher): Validate.Errors =
    check(
      r.schemaVersion == HadoopSchemas.ExternalResearcher,
      s"unsupported schema version '${r.schemaVersion}'"
    ) ++ check(r.recordId.nonEmpty && r.displayName.trim.nonEmpty, "profile without id or name") ++
      r.orcid.toVector.flatMap(o => check(Normalize.orcid(o).contains(o), s"invalid ORCID '$o'")) ++
      provenance(r.provenance, HadoopSchemas.ExternalResearcher)

  def authorPaper(a: AuthorPaper): Validate.Errors =
    check(Ids.isMentionId(a.mentionId), s"malformed mention '${a.mentionId}'") ++
      check(Ids.isWorkId(a.workId), s"malformed work ID '${a.workId}'") ++
      check(a.position >= 0 && a.position < a.teamSize, s"position ${a.position} outside team") ++
      provenance(a.provenance, HadoopSchemas.AuthorPaper)
