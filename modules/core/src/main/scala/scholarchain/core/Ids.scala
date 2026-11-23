package scholarchain.core

/** Stable identifier functions. Every formula is deterministic and timestamp independent. `h(x, n)`
  * is the first n hex characters of SHA-256 over the UTF-8 text x (see [[Hashing]]).
  *
  *   - work: `<namespace>:<authoritative source id>`, e.g. `dblp:conf/sosp/Smith21` (at most 400
  *     characters of source id)
  *   - mention: `m:` + h(`<workId>#<position>`, 20)
  *   - researcher: `orcid:<iD>` when validated, else `sc:r:v1:` + h(sorted mention IDs joined by
  *     `|`, 20). The ID names the exact mention set: a new snapshot or an entity-resolution merge
  *     that changes the set gives a new ID, so a ScholarChain researcher ID is stable within one
  *     run and policy, not across runs. A validated ORCID is the cross-run key.
  *   - coauthor edge: `e:co:` + h(`<a>|<b>|<window label>|<policy version>`, 24) with (a, b)
  *     canonically ordered
  *   - graph edge: `e:<type>:` + h(`<type>|<from>|<to>|<policy>|<evidence>`, 24); an undirected
  *     type orders its endpoints first
  *   - chunk/profile: `c:` + h(`<entity>|<source version>|<doc type>|<ordinal>|<sha256 of text>`,
  *     20)
  *   - evidence: `ev:<type>:` + h(key, 16)
  *   - match decision: `md:<kind>:` + h(`<a>|<b>|<policy version>`, 20), (a, b) canonically ordered
  *   - run: `run-` + h(canonical JSON of the run manifest, 16); keys sorted, so formatting and key
  *     order never change the ID; the manifest must hold no timestamp
  */
object Ids:
  val ResearcherIdVersion = "v1"

  private val Namespace = "^[a-z][a-z0-9]{1,15}$".r
  private val WorkPattern = "^[a-z][a-z0-9]{1,15}:\\S{1,400}$".r
  private val MentionPattern = "^m:[0-9a-f]{20}$".r
  private val ResearcherPattern =
    "^(orcid:\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]|openalex:A\\d{2,12}|sc:r:v1:[0-9a-f]{20})$".r
  private val EdgePattern = "^e:[a-z_]{2,20}:[0-9a-f]{24}$".r
  private val ChunkPattern = "^c:[0-9a-f]{20}$".r
  private val EvidencePattern = "^ev:[a-z_]{2,24}:[0-9a-f]{16}$".r
  private val DecisionPattern = "^md:[a-z]{2,12}:[0-9a-f]{20}$".r
  private val RunPattern = "^run-[0-9a-f]{16}$".r

  def work(namespace: String, sourceId: String): String =
    require(Namespace.matches(namespace), s"bad work namespace '$namespace'")
    require(
      sourceId.trim.nonEmpty && !sourceId.exists(_.isWhitespace),
      s"bad source id '$sourceId'"
    )
    val id = s"$namespace:$sourceId"
    require(isWorkId(id), s"work ID '${id.take(60)}' fails the work ID pattern (source id <= 400)")
    id

  def mention(workId: String, position: Int): String =
    require(position >= 0, "author position must be >= 0")
    "m:" + Hashing.short(s"$workId#$position")

  def researcherFromOrcid(orcid: String): Option[String] = Normalize.orcid(orcid).map("orcid:" + _)

  /** ScholarChain identity ID for a cluster of mentions without a validated external ID. */
  def researcherFromMentions(mentionIds: Iterable[String]): String =
    require(mentionIds.nonEmpty, "a researcher needs at least one mention")
    s"sc:r:$ResearcherIdVersion:" + Hashing.short(mentionIds.toVector.sorted.mkString("|"))

  /** Undirected pair in canonical order: the lexicographically smaller ID first
    * (`String.compareTo`, UTF-16 code unit order). A pair of equal IDs is rejected.
    */
  def orderedPair(a: String, b: String): (String, String) =
    require(a != b, s"self pair '$a'")
    if a < b then (a, b) else (b, a)

  def coauthorEdge(a: String, b: String, window: TimeWindow, policyVersion: String): String =
    val (x, y) = orderedPair(a, b)
    "e:co:" + Hashing.short(s"$x|$y|${window.label}|$policyVersion", 24)

  /** The lower-case edge type, at most 20 characters, that tags a graph edge ID. */
  def edgeTag(edgeType: String): String = edgeType.toLowerCase(java.util.Locale.ROOT).take(20)

  def graphEdge(
      edgeType: String,
      from: String,
      to: String,
      policy: String,
      evidence: String
  ): String =
    val (x, y) =
      if GraphLabels.UndirectedEdgeLabels(edgeType) then orderedPair(from, to) else (from, to)
    s"e:${edgeTag(edgeType)}:" + Hashing.short(s"$edgeType|$x|$y|$policy|$evidence", 24)

  def chunk(
      entityId: String,
      sourceVersion: String,
      docType: String,
      ordinal: Int,
      text: String
  ): String =
    "c:" + Hashing.short(s"$entityId|$sourceVersion|$docType|$ordinal|${Hashing.sha256Hex(text)}")

  def evidence(evidenceType: EvidenceType, key: String): String =
    s"ev:${evidenceType.toString.toLowerCase(java.util.Locale.ROOT)}:" + Hashing.short(key, 16)

  def decision(
      kind: EntityKind,
      sourceId: String,
      targetId: String,
      policyVersion: String
  ): String =
    val (a, b) = orderedPair(sourceId, targetId)
    s"md:${kind.toString.toLowerCase(java.util.Locale.ROOT)}:" +
      Hashing.short(s"$a|$b|$policyVersion")

  /** Largest magnitude (exclusive) a manifest number may have. ujson parses numbers as doubles, so
    * integers at or above 2^53 are not represented exactly and two different manifests could share
    * a run ID; such numbers are rejected. Put large identifiers in strings.
    */
  val MaxManifestNumber: Double = 9007199254740992.0

  /** Run ID from the run manifest JSON. The manifest is canonicalized (object keys sorted
    * recursively, no whitespace) before hashing and must not contain a timestamp. A number with
    * magnitude of 2^53 or more is rejected (see [[MaxManifestNumber]]).
    */
  def run(manifestJson: String): String =
    val parsed =
      scala.util
        .Try(ujson.read(manifestJson))
        .getOrElse(throw new IllegalArgumentException("run manifest is not JSON"))
    "run-" + Hashing.short(ujson.write(canonical(parsed)), 16)

  private def canonical(v: ujson.Value): ujson.Value = v match
    case ujson.Obj(o) => ujson.Obj.from(o.toVector.sortBy(_._1).map((k, x) => k -> canonical(x)))
    case ujson.Arr(a) => ujson.Arr.from(a.map(canonical))
    case ujson.Num(d) =>
      if d.isNaN || d.isInfinite || math.abs(d) >= MaxManifestNumber then
        throw new IllegalArgumentException(
          s"run manifest number $d is not exactly representable (|n| >= 2^53); use a string"
        )
      else v
    case other => other

  def isWorkId(id: String): Boolean = WorkPattern.matches(id)
  def isMentionId(id: String): Boolean = MentionPattern.matches(id)
  def isResearcherId(id: String): Boolean = ResearcherPattern.matches(id)
  def isEdgeId(id: String): Boolean = EdgePattern.matches(id)
  def isChunkId(id: String): Boolean = ChunkPattern.matches(id)
  def isEvidenceId(id: String): Boolean = EvidencePattern.matches(id)
  def isDecisionId(id: String): Boolean = DecisionPattern.matches(id)
  def isRunId(id: String): Boolean = RunPattern.matches(id)
