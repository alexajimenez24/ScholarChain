package scholarchain.rag

import scholarchain.core.*
import upickle.default.ReadWriter

/** One canonical researcher as entity resolution left it: the Researcher graph node that Spark job
  * 1 built from the er-v1 decisions (`provenance.policyVersion` is the ER policy).
  */
final case class ResearcherEntry(
    researcherId: String,
    displayName: String,
    orcid: Option[String],
    mentionCount: Long,
    mentionIds: Vector[String],
    identityConfidence: Double,
    unresolved: Boolean,
    identityConflict: Boolean,
    provenance: Provenance
) derives ReadWriter

object ResearcherEntry:
  /** A Researcher node with the Phase 06 property set; anything else is refused, not guessed. */
  def fromNode(n: GraphNode): Either[String, ResearcherEntry] =
    def prop(k: String) = n.properties.get(k).filter(_.nonEmpty).toRight(s"${n.nodeId}: no $k")
    def bool(k: String) = prop(k).flatMap(v => v.toBooleanOption.toRight(s"${n.nodeId}: $k '$v'"))
    for
      _ <- Either.cond(n.label == GraphLabels.Researcher, (), s"${n.nodeId} is a ${n.label}")
      _ <- Either.cond(Ids.isResearcherId(n.nodeId), (), s"${n.nodeId} is not a researcher ID")
      name <- prop("displayName")
      conf <- prop("identityConfidence").flatMap(v =>
        v.toDoubleOption.filter(d => d >= 0 && d <= 1).toRight(s"${n.nodeId}: confidence '$v'")
      )
      count <- prop("mentionCount").flatMap(v =>
        v.toLongOption.filter(_ >= 1).toRight(s"${n.nodeId}: mentionCount '$v'")
      )
      unresolved <- bool("unresolved")
      conflict <- bool("identityConflict")
    yield ResearcherEntry(
      n.nodeId,
      name,
      n.properties.get("orcid").filter(_.nonEmpty),
      count,
      n.properties.get("mentionIds").toVector.flatMap(_.split('|')).filter(_.nonEmpty),
      conf,
      unresolved,
      conflict,
      n.provenance
    )

/** A named non-researcher entity (Topic or Institution node) used to resolve filter phrases. */
final case class NamedEntity(entityId: String, label: String, name: String) derives ReadWriter

/** Lookup keys. Names are compared only after core's person-name normalization; filter phrases
  * after title normalization. The key is data for a map lookup, never query text.
  */
object NameKeys:
  def exact(name: String): String = Normalize.personName(name)
  def tokens(name: String): String = exact(name).split(' ').filter(_.nonEmpty).sorted.mkString(" ")
  def phrase(text: String): String = Normalize.title(text)

/** Read port: canonical researchers by name key or ID, and topic and institution entities by name.
  * A production adapter (a name index in the read model) is storage's; [[InMemoryDirectory]] serves
  * the local runtime and the tests.
  */
trait ResearcherDirectory:
  /** Researchers whose normalized display name equals `NameKeys.exact(name)`, with the total. */
  def byExactName(name: String, limit: Int): Either[String, (Vector[ResearcherEntry], Int)]

  /** Researchers with the same name tokens in any order (`NameKeys.tokens`), with the total. */
  def byNameTokens(name: String, limit: Int): Either[String, (Vector[ResearcherEntry], Int)]

  def byId(researcherId: String): Either[String, Option[ResearcherEntry]]

  /** Entities of `label` whose normalized name equals the phrase, or else contains every token of
    * it; ordered by name length, then ID.
    */
  def entities(label: String, phrase: String, limit: Int): Either[String, Vector[NamedEntity]]

  def entity(entityId: String): Either[String, Option[NamedEntity]]

final class InMemoryDirectory(
    researchers: Iterable[ResearcherEntry],
    named: Iterable[NamedEntity]
) extends ResearcherDirectory:
  private val ids: Map[String, ResearcherEntry] = researchers.map(r => r.researcherId -> r).toMap
  private val order = Ordering.by[ResearcherEntry, String](_.researcherId)
  private val exactIx = ids.values.toVector
    .groupBy(r => NameKeys.exact(r.displayName))
    .view
    .mapValues(_.sorted(order))
    .toMap
  private val tokenIx = ids.values.toVector
    .groupBy(r => NameKeys.tokens(r.displayName))
    .view
    .mapValues(_.sorted(order))
    .toMap
  private val entityIds: Map[String, NamedEntity] = named.map(e => e.entityId -> e).toMap
  private val byLabel: Map[String, Vector[(String, NamedEntity)]] =
    entityIds.values.toVector
      .groupBy(_.label)
      .view
      .mapValues(_.map(e => NameKeys.phrase(e.name) -> e).sortBy(_._2.entityId))
      .toMap

  private def take(xs: Vector[ResearcherEntry], limit: Int) = (xs.take(limit), xs.size)

  def byExactName(name: String, limit: Int) =
    Right(take(exactIx.getOrElse(NameKeys.exact(name), Vector.empty), limit))
  def byNameTokens(name: String, limit: Int) =
    Right(take(tokenIx.getOrElse(NameKeys.tokens(name), Vector.empty), limit))
  def byId(researcherId: String) = Right(ids.get(researcherId))
  def entity(entityId: String) = Right(entityIds.get(entityId))

  def entities(label: String, phrase: String, limit: Int) =
    val key = NameKeys.phrase(phrase)
    val words = key.split(' ').filter(_.nonEmpty).toSet
    val all = byLabel.getOrElse(label, Vector.empty)
    val exact = all.filter(_._1 == key).map(_._2)
    val hits =
      if exact.nonEmpty || words.isEmpty then exact
      else
        all
          .filter((k, _) => words.subsetOf(k.split(' ').toSet))
          .map(_._2)
          .sortBy(e => (e.name.length, e.entityId))
    Right(hits.take(limit))

  def size: Int = ids.size

object InMemoryDirectory:
  /** From Researcher, Topic and Institution graph nodes; other labels are ignored. */
  def fromNodes(nodes: Iterable[GraphNode]): Either[String, InMemoryDirectory] =
    val rs = Vector.newBuilder[ResearcherEntry]
    val es = Vector.newBuilder[NamedEntity]
    val errors = Vector.newBuilder[String]
    nodes.foreach { n =>
      n.label match
        case GraphLabels.Researcher =>
          ResearcherEntry.fromNode(n).fold(errors += _, rs += _)
        case GraphLabels.Topic | GraphLabels.Institution =>
          n.properties.get("name").filter(_.nonEmpty) match
            case Some(name) => es += NamedEntity(n.nodeId, n.label, name)
            case None       => errors += s"${n.nodeId}: no name"
        case _ => ()
    }
    val errs = errors.result()
    Either.cond(
      errs.isEmpty,
      new InMemoryDirectory(rs.result(), es.result()),
      s"${errs.size} directory nodes refused, first: ${errs.head}"
    )

/** The stored value of one ranking metric, as Spark wrote it. Nothing is recomputed. */
object MetricValues:
  import CollaborationMetric.*

  def of(m: ResearcherMetrics, metric: CollaborationMetric): Option[Double] = metric match
    case PUBLICATION_COUNT              => Some(m.publicationCount.toDouble)
    case DISTINCT_COAUTHOR_COUNT        => Some(m.distinctCoauthorCount.toDouble)
    case TOTAL_COAUTHOR_OCCURRENCES     => Some(m.totalCoauthorOccurrences.toDouble)
    case AVERAGE_COAUTHORS_PER_PAPER    => Some(m.averageCoauthorsPerPaper)
    case MAXIMUM_COAUTHORS_ON_ONE_PAPER => Some(m.maximumCoauthorsOnOnePaper.toDouble)
    case WEIGHTED_DEGREE                => Some(m.weightedDegree)
    case BRIDGE_SCORE                   => m.bridgeScore
    case PARTICIPATION_COEFFICIENT      => m.participationCoefficient
    case CROSS_COMMUNITY_DEGREE         => m.crossCommunityDegree.map(_.toDouble)
    case CORENESS                       => m.coreness.map(_.toDouble)

  /** The `ResearcherMetrics` field that holds the metric. */
  def field(metric: CollaborationMetric): String = metric match
    case PUBLICATION_COUNT              => "publicationCount"
    case DISTINCT_COAUTHOR_COUNT        => "distinctCoauthorCount"
    case TOTAL_COAUTHOR_OCCURRENCES     => "totalCoauthorOccurrences"
    case AVERAGE_COAUTHORS_PER_PAPER    => "averageCoauthorsPerPaper"
    case MAXIMUM_COAUTHORS_ON_ONE_PAPER => "maximumCoauthorsOnOnePaper"
    case WEIGHTED_DEGREE                => "weightedDegree"
    case BRIDGE_SCORE                   => "bridgeScore"
    case PARTICIPATION_COEFFICIENT      => "participationCoefficient"
    case CROSS_COMMUNITY_DEGREE         => "crossCommunityDegree"
    case CORENESS                       => "coreness"

  /** What the metric means, for evidence and the synthesis prompt. */
  def meaning(metric: CollaborationMetric): String = metric match
    case PUBLICATION_COUNT       => "P_r, publications in the window"
    case DISTINCT_COAUTHOR_COUNT => "C_r, distinct coauthors in the window (collaboration breadth)"
    case TOTAL_COAUTHOR_OCCURRENCES  => "T_r, coauthor occurrences summed over papers (intensity)"
    case AVERAGE_COAUTHORS_PER_PAPER => "A_r, average coauthors per paper (team size)"
    case MAXIMUM_COAUTHORS_ON_ONE_PAPER => "M_r, most coauthors on one paper (largest team)"
    case WEIGHTED_DEGREE                =>
      "W_r, joint publications summed over coauthors (strength of repeated collaboration)"
    case BRIDGE_SCORE =>
      "sampled-source Brandes betweenness (approximate, unweighted hops): structural bridge importance"
    case PARTICIPATION_COEFFICIENT =>
      "participation coefficient across communities (Guimera-Amaral)"
    case CROSS_COMMUNITY_DEGREE => "coauthors outside the researcher's own community"
    case CORENESS               => "k-core number (embeddedness in dense collaboration)"

  /** Canonical decimal text of a stored number: no exponent, no trailing zeros ("3", "0.0125"). */
  def plain(d: Double): String =
    java.math.BigDecimal.valueOf(d).stripTrailingZeros.toPlainString

/** One position of a ranking: the researcher and the stored value it was ranked by. */
final case class RankedId(researcherId: String, value: Double)

/** Read port: researchers of one window ordered by one stored metric (value descending, then
  * researcher ID ascending); rows without the metric are left out. `among` restricts the ranking to
  * a candidate set (topic and institution filters). The production adapter (a rank partition or
  * index in the read model) is storage's; [[InMemoryMetricRanking]] serves the local runtime.
  */
trait MetricRanking:
  def top(
      window: TimeWindow,
      metricVersion: String,
      metric: CollaborationMetric,
      limit: Int,
      among: Option[Set[String]]
  ): Either[String, Vector[RankedId]]

final class InMemoryMetricRanking(rows: Iterable[ResearcherMetrics]) extends MetricRanking:
  private val groups: Map[(String, String), Vector[ResearcherMetrics]] =
    rows.toVector.groupBy(m => (m.window.label, m.metricVersion))
  private val cache = scala.collection.concurrent.TrieMap
    .empty[(String, String, CollaborationMetric), Vector[RankedId]]

  private def sorted(w: String, v: String, metric: CollaborationMetric): Vector[RankedId] =
    cache.getOrElseUpdate(
      (w, v, metric),
      groups
        .getOrElse((w, v), Vector.empty)
        .flatMap(m => MetricValues.of(m, metric).map(RankedId(m.researcherId, _)))
        .sortBy(r => (-r.value, r.researcherId))
    )

  def top(
      window: TimeWindow,
      metricVersion: String,
      metric: CollaborationMetric,
      limit: Int,
      among: Option[Set[String]]
  ): Either[String, Vector[RankedId]] =
    if limit < 1 || limit > 1000 then Left(s"limit $limit must be 1..1000")
    else
      val all = sorted(window.label, metricVersion, metric)
      Right(among.fold(all)(s => all.filter(r => s(r.researcherId))).take(limit))
