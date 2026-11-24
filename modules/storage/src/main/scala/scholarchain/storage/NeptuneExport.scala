package scholarchain.storage

import scholarchain.core.*
import upickle.default.{ReadWriter, read, write}
import java.nio.charset.StandardCharsets.UTF_8

/** Neptune bulk-load CSV (openCypher format) generated from the Phase 06 Spark export.
  *
  * Identifier scheme: every node `:ID` is the Spark `nodeId` and every relationship `:ID` is the
  * Spark `edgeId`, byte for byte. A COAUTHORED_WITH relationship keeps its graph edge ID (core
  * `Ids.graphEdge`), and its `coauthorEdgeId` property is the window-scoped coauthor edge ID
  * (`Ids.coauthorEdge`) of the evidence record. No identifier is made here, so a reload of the same
  * export writes the same IDs and the same bytes.
  *
  * Column types of the openCypher format:
  * https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-format-opencypher.html
  */
enum PropType(val header: String):
  case Str extends PropType("String")
  case I32 extends PropType("Int")
  case I64 extends PropType("Long")
  case F64 extends PropType("Double")
  case Bool extends PropType("Bool")

/** One property column. `derived` columns are computed by the loader (provenance, window years,
  * evidence pointer); the input may not supply them.
  */
final case class Col(name: String, tpe: PropType, derived: Boolean = false):
  def header: String = s"$name:${tpe.header}"

/** Property schemas of the nodes and relationships the Phase 06 export has. */
object NeptuneSchema:
  import PropType.*

  val Provenance: Vector[Col] = Vector(
    Col("source", Str, true),
    Col("sourceRecordId", Str, true),
    Col("snapshot", Str, true),
    Col("runId", Str, true),
    Col("policyVersion", Str, true)
  )

  val NodeColumns: Map[String, Vector[Col]] = Map(
    GraphLabels.Researcher -> Vector(
      Col("displayName", Str),
      Col("orcid", Str),
      Col("mentionCount", I64),
      Col("mentionIds", Str),
      Col("identityConfidence", F64),
      Col("unresolved", Bool),
      Col("identityConflict", Bool)
    ),
    GraphLabels.Work -> Vector(
      Col("title", Str),
      Col("year", I32),
      Col("doi", Str),
      Col("recordType", Str),
      Col("memberWorkIds", Str),
      Col("unresolved", Bool),
      Col("identityConflict", Bool)
    ),
    GraphLabels.Venue -> Vector(Col("name", Str), Col("kind", Str)),
    GraphLabels.Institution -> Vector(Col("name", Str), Col("countryCode", Str), Col("ror", Str)),
    GraphLabels.Topic -> Vector(Col("name", Str))
  ).view.mapValues(_ ++ Provenance).toMap

  val EdgeColumns: Map[String, Vector[Col]] = Map(
    GraphLabels.Authored -> Vector(
      Col("position", I32),
      Col("teamSize", I32),
      Col("year", I32),
      Col("mentionIds", Str)
    ),
    GraphLabels.CoauthoredWith -> Vector(
      Col("coauthorEdgeId", Str),
      Col("window", Str),
      Col("windowStartYear", I32, true),
      Col("windowEndYear", I32, true),
      Col("jointPublicationCount", I64),
      Col("firstCollaborationYear", I32),
      Col("lastCollaborationYear", I32),
      Col("representativeWorkIds", Str),
      Col("evidencePointer", Str, true)
    ),
    GraphLabels.PublishedIn -> Vector.empty,
    GraphLabels.HasTopic -> Vector.empty,
    GraphLabels.AffiliatedWith -> Vector(Col("supportingMentionCount", I32))
  ).view.mapValues(_ ++ Provenance).toMap

  /** (from label, to label) of each exported relationship type. */
  val Endpoints: Map[String, (String, String)] = Map(
    GraphLabels.Authored -> (GraphLabels.Researcher -> GraphLabels.Work),
    GraphLabels.CoauthoredWith -> (GraphLabels.Researcher -> GraphLabels.Researcher),
    GraphLabels.PublishedIn -> (GraphLabels.Work -> GraphLabels.Venue),
    GraphLabels.HasTopic -> (GraphLabels.Work -> GraphLabels.Topic),
    GraphLabels.AffiliatedWith -> (GraphLabels.Researcher -> GraphLabels.Institution)
  )

  /** Core labels the Phase 06 export does not produce, so this load plan does not carry them. */
  val NotExportedNodes: Vector[String] =
    Vector(GraphLabels.AuthorMention, GraphLabels.TextChunk, GraphLabels.DatasetSnapshot)
  val NotExportedEdges: Vector[String] =
    Vector(GraphLabels.ResolvesTo, GraphLabels.DerivedFrom, GraphLabels.SupportedBy)

/** RFC 4180 CSV writer and reader for the openCypher load files (UTF-8, LF line ends). */
object NeptuneCsv:
  def field(s: String): String =
    if s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') || s.startsWith(" ") ||
      s.endsWith(" ")
    then "\"" + s.replace("\"", "\"\"") + "\""
    else s

  def line(cells: Seq[String]): String = cells.map(field).mkString(",") + "\n"

  /** Header row and data rows; a quoted field may hold commas, quotes and line breaks. */
  def parse(bytes: Array[Byte]): Either[String, (Vector[String], Vector[Vector[String]])] =
    val text = new String(bytes, UTF_8)
    val rows = Vector.newBuilder[Vector[String]]
    var cur = Vector.newBuilder[String]
    val sb = new StringBuilder
    var inQuotes = false
    var quotedField = false
    var i = 0
    var rowStarted = false
    def endField(): Unit =
      cur += sb.result(); sb.clear(); quotedField = false
    def endRow(): Unit =
      endField(); rows += cur.result(); cur = Vector.newBuilder[String]; rowStarted = false
    while i < text.length do
      val c = text.charAt(i)
      if inQuotes then
        if c == '"' then
          if i + 1 < text.length && text.charAt(i + 1) == '"' then { sb += '"'; i += 1 }
          else inQuotes = false
        else sb += c
      else
        c match
          case '"' if sb.isEmpty && !quotedField =>
            inQuotes = true; quotedField = true; rowStarted = true
          case '"'   => return Left(s"stray quote at offset $i")
          case ','   => endField(); rowStarted = true
          case '\n'  => endRow()
          case '\r'  => ()
          case other => sb += other; rowStarted = true
      i += 1
    if inQuotes then Left("unterminated quoted field")
    else
      if rowStarted || sb.nonEmpty then endRow()
      val all = rows.result()
      if all.isEmpty then Left("empty CSV")
      else
        val (header, data) = (all.head, all.tail)
        data.find(_.size != header.size) match
          case Some(bad) => Left(s"row with ${bad.size} fields, header has ${header.size}")
          case None      => Right((header, data))

/** A rejected record with its reason; written to the load manifest, never silently dropped. */
final case class Rejection(kind: String, id: String, reason: String) derives ReadWriter

/** Everything the loader reads: the Phase 06 tables of one run as typed core records. */
final case class GraphExportInput(
    runId: String,
    snapshot: String,
    policyVersion: String,
    windows: Vector[TimeWindow],
    nodes: Iterable[GraphNode],
    edges: Iterable[GraphEdge],
    coauthorEdges: Iterable[CoauthorEdge],
    evidenceBaseUri: String
)

final case class CsvFile(
    name: String,
    kind: String,
    label: String,
    header: String,
    rows: Int,
    sha256: String,
    bytes: Array[Byte]
)

final case class CountEntry(label: String, count: Int) derives ReadWriter

final case class ManifestFile(
    name: String,
    kind: String,
    label: String,
    header: String,
    rows: Int,
    sha256: String,
    bytes: Long
) derives ReadWriter

/** The Neptune bulk-load manifest of one run. It holds no timestamp: the same input gives the same
  * bytes.
  */
final case class NeptuneLoadManifest(
    schemaVersion: String,
    runId: String,
    snapshot: String,
    policyVersion: String,
    format: String,
    idScheme: String,
    windows: Vector[String],
    coauthoredWith: String,
    files: Vector[ManifestFile],
    nodeCounts: Vector[CountEntry],
    edgeCounts: Vector[CountEntry],
    duplicatesDropped: Int,
    rejected: Vector[Rejection],
    notExportedNodes: Vector[String],
    notExportedEdges: Vector[String]
) derives ReadWriter

object NeptuneLoadManifest:
  val Version = "neptune-load-manifest-v1"
  val IdScheme = "node :ID = Spark nodeId; relationship :ID = Spark edgeId; no loader-made IDs"
  val CoauthorRule =
    "one edge per researcher pair per window; every query filters on exactly one window value"

  def parse(json: String): Either[String, NeptuneLoadManifest] =
    scala.util
      .Try(read[NeptuneLoadManifest](json))
      .toEither
      .left
      .map(e => s"malformed Neptune load manifest: ${e.getMessage}")
      .filterOrElse(_.schemaVersion == Version, "unsupported Neptune load manifest version")

final case class NeptuneLoadPlan(
    manifest: NeptuneLoadManifest,
    files: Vector[CsvFile]
):
  def rejections: Vector[Rejection] = manifest.rejected
  def clean: Boolean = manifest.rejected.isEmpty
  def manifestJson: String = write(manifest, indent = 2)

/** The Spark `neptune.json` load manifest, read for reconciliation of row counts. */
final case class SparkNeptuneManifest(
    runId: String,
    snapshot: String,
    nodeRows: Int,
    edgeRows: Int,
    nodesByLabel: Map[String, Int],
    edgesByType: Map[String, Int],
    windows: Vector[String]
)

object SparkNeptuneManifest:
  def parse(json: String): Either[String, SparkNeptuneManifest] =
    scala.util
      .Try {
        val v = ujson.read(json)
        def by(t: ujson.Value, prefix: String) =
          t("rowsByPartition").obj.map((k, n) => k.stripPrefix(prefix) -> n.num.toInt).toMap
        SparkNeptuneManifest(
          v("runId").str,
          v("snapshot").str,
          v("nodes")("rows").num.toInt,
          v("edges")("rows").num.toInt,
          by(v("nodes"), "label="),
          by(v("edges"), "edgeType="),
          v("coauthoredWith")("windows").arr.map(_.str).toVector
        )
      }
      .toEither
      .left
      .map(e => s"malformed Spark neptune load manifest: ${e.getMessage}")

  /** Differences between the manifest's row counts and the rows actually read. Empty means equal.
    */
  def reconcile(m: SparkNeptuneManifest, in: GraphExportInput): Vector[String] =
    val nodes = in.nodes.toVector
    val edges = in.edges.toVector
    def diff(what: String, expected: Map[String, Int], actual: Map[String, Int]) =
      (expected.keySet ++ actual.keySet).toVector.sorted.flatMap { k =>
        val (e, a) = (expected.getOrElse(k, 0), actual.getOrElse(k, 0))
        Option.when(e != a)(s"$what $k: manifest $e rows, read $a")
      }
    Vector(
      Option.when(m.runId != in.runId)(s"run ID: manifest ${m.runId}, input ${in.runId}"),
      Option.when(m.snapshot != in.snapshot)(
        s"snapshot: manifest ${m.snapshot}, input ${in.snapshot}"
      ),
      Option.when(m.nodeRows != nodes.size)(
        s"node rows: manifest ${m.nodeRows}, read ${nodes.size}"
      ),
      Option.when(m.edgeRows != edges.size)(
        s"edge rows: manifest ${m.edgeRows}, read ${edges.size}"
      )
    ).flatten ++
      diff("nodes", m.nodesByLabel, nodes.groupBy(_.label).view.mapValues(_.size).toMap) ++
      diff("edges", m.edgesByType, edges.groupBy(_.edgeType).view.mapValues(_.size).toMap)

/** Builds the load plan: validation first, CSV second. Pure and deterministic: rows are sorted by
  * ID, so the same input always gives the same bytes. Nothing is repaired: a record that fails a
  * rule is rejected with its reason, and every record that depends on it is rejected too.
  *
  * Rules, nodes: label is one of the exported five; core `Validate.graphNode`; provenance run ID is
  * the input run; the ID is well formed for its label (researcher and work IDs by core `Ids`);
  * every property is known and parses as its column type; an ID that appears twice must be the same
  * record (an identical copy is dropped and counted, a different one rejects every copy, because
  * Neptune would merge labels and pick properties non-deterministically).
  *
  * Rules, relationships other than COAUTHORED_WITH: type is exported; core `Validate.graphEdge`;
  * run ID; both endpoints are loaded nodes with the labels the type fixes; properties as above;
  * duplicate IDs as above.
  *
  * Rules, COAUTHORED_WITH (each also needs the rules above): the edge carries the ID of a
  * coauthor-edge record, and that record passes core `Validate.edge` (pair order, years, window, ID
  * recomputed from endpoints, window and policy); both endpoints are loaded Researcher nodes and
  * match the record's pair, in order; the window is one of the configured windows and the graph
  * edge's `window` property equals the record's window; years are consistent (first <= last, both
  * inside the window); the graph edge ID equals `Ids.graphEdge` of the pair and the record ID; the
  * graph edge's counts, years and work list equal the record's; every representative work is a
  * loaded Work node that both researchers AUTHORED (each edge resolves to its supporting works),
  * with no more works than the joint publication count and, when the work has a year, a year inside
  * first..last. A coauthor record that no graph edge references is rejected too.
  */
object NeptuneExport:
  private val Loaded = Set(
    GraphLabels.Researcher,
    GraphLabels.Work,
    GraphLabels.Venue,
    GraphLabels.Institution,
    GraphLabels.Topic
  )

  private def renderValue(tpe: PropType, raw: String): Either[String, String] = tpe match
    case PropType.Str => Either.cond(!raw.contains('\u0000'), raw, "NUL character")
    case PropType.I32 => raw.toIntOption.map(_.toString).toRight(s"'${raw.take(30)}' is not an Int")
    case PropType.I64 =>
      raw.toLongOption.map(_.toString).toRight(s"'${raw.take(30)}' is not a Long")
    case PropType.F64 =>
      raw.toDoubleOption
        .filter(d => !d.isNaN && !d.isInfinite)
        .map(_.toString)
        .toRight(s"'${raw.take(30)}' is not a finite Double")
    case PropType.Bool =>
      Either.cond(raw == "true" || raw == "false", raw, s"'${raw.take(30)}' is not true or false")

  private def provenanceValues(p: Provenance): Map[String, String] =
    Map(
      "source" -> p.source.toString,
      "sourceRecordId" -> p.sourceRecordId,
      "snapshot" -> p.snapshot,
      "runId" -> p.runId,
      "policyVersion" -> p.policyVersion.getOrElse("")
    )

  /** The property cells of one record, in column order. Unknown or derived input keys are errors.
    */
  private def cells(
      cols: Vector[Col],
      input: Map[String, String],
      derived: Map[String, String]
  ): Either[String, Vector[String]] =
    val allowed = cols.filterNot(_.derived).map(_.name).toSet
    input.keys.toVector.sorted.find(!allowed(_)) match
      case Some(k) => Left(s"unknown or loader-derived property '${k.take(40)}'")
      case None    =>
        val values = input ++ derived
        cols.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) { (acc, c) =>
          acc.flatMap { out =>
            values.get(c.name).filter(_.nonEmpty) match
              case None      => Right(out :+ "")
              case Some(raw) =>
                renderValue(c.tpe, raw).left.map(e => s"${c.name}: $e").map(out :+ _)
          }
        }

  private def idOk(label: String, id: String): Boolean = label match
    case GraphLabels.Researcher => Ids.isResearcherId(id)
    case GraphLabels.Work       => Ids.isWorkId(id)
    case _                      => id.nonEmpty && id.length <= 450 && !id.exists(_.isWhitespace)

  /** Groups by ID. Identical copies collapse (counted); different copies are all rejected. */
  private def dedupe[A](
      items: Iterable[A],
      id: A => String,
      kind: String
  ): (Vector[A], Vector[Rejection], Int) =
    val groups = items.toVector.groupBy(id).toVector.sortBy(_._1)
    val kept = Vector.newBuilder[A]
    val rejected = Vector.newBuilder[Rejection]
    var dropped = 0
    groups.foreach { (k, copies) =>
      if copies.distinct.size == 1 then
        kept += copies.head
        dropped += copies.size - 1
      else
        rejected += Rejection(kind, k, s"${copies.distinct.size} different records share this ID")
    }
    (kept.result(), rejected.result(), dropped)

  def build(in: GraphExportInput): NeptuneLoadPlan =
    val rejected = Vector.newBuilder[Rejection]
    var duplicates = 0
    val windowLabels = in.windows.map(_.label)

    // nodes ------------------------------------------------------------------------------------
    val (nodeCands, nodeConflicts, nodeDups) = dedupe[GraphNode](in.nodes, _.nodeId, "node")
    rejected ++= nodeConflicts
    duplicates += nodeDups
    val nodeRows = nodeCands.flatMap { n =>
      val checks: Either[String, Vector[String]] =
        for
          _ <- Either.cond(Loaded(n.label), (), s"label ${n.label} is not exported by Phase 06")
          _ <- Validate.graphNode(n) match
            case v if v.isEmpty => Right(())
            case v              => Left(v.mkString("; "))
          _ <- Either.cond(
            n.provenance.runId == in.runId,
            (),
            s"provenance.runId ${n.provenance.runId} is not the run ${in.runId}"
          )
          _ <- Either.cond(idOk(n.label, n.nodeId), (), s"malformed ${n.label} ID")
          props <- cells(
            NeptuneSchema.NodeColumns(n.label),
            n.properties,
            provenanceValues(n.provenance)
          )
        yield props
      checks match
        case Right(props) => Vector((n, props))
        case Left(e)      => rejected += Rejection("node", n.nodeId, e); Vector.empty
    }
    val labelOf: Map[String, String] = nodeRows.map((n, _) => n.nodeId -> n.label).toMap
    val workYear: Map[String, Option[Int]] =
      nodeRows.collect {
        case (n, _) if n.label == GraphLabels.Work =>
          n.nodeId -> n.properties.get("year").flatMap(_.toIntOption)
      }.toMap

    // relationships other than COAUTHORED_WITH -------------------------------------------------
    val (edgeCands, edgeConflicts, edgeDups) = dedupe[GraphEdge](in.edges, _.edgeId, "edge")
    rejected ++= edgeConflicts
    duplicates += edgeDups

    def endpointCheck(e: GraphEdge): Either[String, Unit] =
      NeptuneSchema.Endpoints.get(e.edgeType) match
        case None => Left(s"edge type ${e.edgeType} is not exported by Phase 06")
        case Some((fromLabel, toLabel)) =>
          for
            _ <- Either.cond(
              labelOf.get(e.fromId).contains(fromLabel),
              (),
              s"start node ${e.fromId} is not a loaded $fromLabel"
            )
            _ <- Either.cond(
              labelOf.get(e.toId).contains(toLabel),
              (),
              s"end node ${e.toId} is not a loaded $toLabel"
            )
          yield ()

    def commonEdgeChecks(e: GraphEdge): Either[String, Unit] =
      for
        _ <- Validate.graphEdge(e) match
          case v if v.isEmpty => Right(())
          case v              => Left(v.mkString("; "))
        _ <- Either.cond(
          e.provenance.runId == in.runId,
          (),
          s"provenance.runId ${e.provenance.runId} is not the run ${in.runId}"
        )
        _ <- endpointCheck(e)
      yield ()

    val plain = edgeCands.filter(_.edgeType != GraphLabels.CoauthoredWith)
    val plainRows = plain.flatMap { e =>
      val r = for
        _ <- commonEdgeChecks(e)
        props <- cells(
          NeptuneSchema.EdgeColumns(e.edgeType),
          e.properties,
          provenanceValues(e.provenance)
        )
      yield props
      r match
        case Right(props) => Vector((e, props))
        case Left(err)    => rejected += Rejection("edge", e.edgeId, err); Vector.empty
    }
    val authored: Set[(String, String)] =
      plainRows.collect {
        case (e, _) if e.edgeType == GraphLabels.Authored => e.fromId -> e.toId
      }.toSet

    // COAUTHORED_WITH --------------------------------------------------------------------------
    val (recCands, recConflicts, recDups) =
      dedupe[CoauthorEdge](in.coauthorEdges, _.edgeId, "coauthor-edge")
    rejected ++= recConflicts
    duplicates += recDups
    val conflictedRecs = recConflicts.map(_.id).toSet
    val records = recCands.map(r => r.edgeId -> r).toMap
    val pointerBase = in.evidenceBaseUri.stripSuffix("/")

    def coauthorChecks(e: GraphEdge): Either[String, (Vector[String])] =
      for
        _ <- commonEdgeChecks(e)
        recId <- e.properties.get("coauthorEdgeId").toRight("no coauthorEdgeId property")
        _ <- Either.cond(!conflictedRecs(recId), (), s"coauthor edge $recId has conflicting copies")
        rec <- records.get(recId).toRight(s"no coauthor-edge record $recId (no evidence)")
        _ <- Validate.edge(rec) match
          case v if v.isEmpty => Right(())
          case v              => Left(s"coauthor record $recId: ${v.mkString("; ")}")
        _ <- Either.cond(
          rec.policyVersion == in.policyVersion,
          (),
          s"record policy ${rec.policyVersion} is not ${in.policyVersion}"
        )
        _ <- Either.cond(
          rec.provenance.runId == in.runId,
          (),
          s"record run ${rec.provenance.runId} is not ${in.runId}"
        )
        _ <- Either.cond(
          e.fromId == rec.researcherA && e.toId == rec.researcherB,
          (),
          "graph edge endpoints differ from the coauthor record pair or its order"
        )
        _ <- Either.cond(
          windowLabels.contains(rec.window.label),
          (),
          s"window ${rec.window.label} is not a configured window"
        )
        _ <- Either.cond(
          e.properties.get("window").contains(rec.window.label),
          (),
          s"graph edge window ${e.properties.get("window").getOrElse("<none>")} differs from record window ${rec.window.label}"
        )
        _ <- Either.cond(
          e.edgeId == Ids.graphEdge(
            GraphLabels.CoauthoredWith,
            rec.researcherA,
            rec.researcherB,
            in.policyVersion,
            rec.edgeId
          ),
          (),
          "graph edge ID is not the core ID of this pair, policy and coauthor edge"
        )
        _ <- Either.cond(
          e.properties
            .get("jointPublicationCount")
            .flatMap(_.toLongOption)
            .contains(rec.jointPublicationCount) &&
            e.properties
              .get("firstCollaborationYear")
              .flatMap(_.toIntOption)
              .contains(rec.firstCollaborationYear) &&
            e.properties
              .get("lastCollaborationYear")
              .flatMap(_.toIntOption)
              .contains(rec.lastCollaborationYear) &&
            e.properties
              .get("representativeWorkIds")
              .contains(rec.representativeWorkIds.mkString("|")),
          (),
          "graph edge counts, years or works differ from the coauthor record"
        )
        _ <- Either.cond(
          rec.representativeWorkIds.distinct.size == rec.representativeWorkIds.size &&
            rec.representativeWorkIds.size <= rec.jointPublicationCount,
          (),
          "representative works repeat or outnumber the joint publication count"
        )
        _ <- rec.representativeWorkIds
          .find(w => labelOf.get(w) != Some(GraphLabels.Work))
          .fold(Right(()))(w => Left(s"supporting work $w is not a loaded Work node"))
        _ <- rec.representativeWorkIds
          .find(w => !authored((rec.researcherA, w)) || !authored((rec.researcherB, w)))
          .fold(Right(()))(w => Left(s"supporting work $w is not AUTHORED by both researchers"))
        _ <- rec.representativeWorkIds
          .find(w =>
            workYear
              .getOrElse(w, None)
              .exists(y => y < rec.firstCollaborationYear || y > rec.lastCollaborationYear)
          )
          .fold(Right(()))(w =>
            Left(
              s"supporting work $w has a year outside ${rec.firstCollaborationYear}-${rec.lastCollaborationYear}"
            )
          )
        derived = Map(
          "windowStartYear" -> rec.window.startYear.toString,
          "windowEndYear" -> rec.window.endYear.toString,
          "evidencePointer" -> s"$pointerBase/coauthor-edges/windowLabel=${rec.window.label}#${rec.edgeId}"
        )
        props <- cells(
          NeptuneSchema.EdgeColumns(GraphLabels.CoauthoredWith),
          e.properties,
          provenanceValues(e.provenance) ++ derived
        )
      yield props

    val coauthorRows = edgeCands.filter(_.edgeType == GraphLabels.CoauthoredWith).flatMap { e =>
      coauthorChecks(e) match
        case Right(props) => Vector((e, props))
        case Left(err)    => rejected += Rejection("edge", e.edgeId, err); Vector.empty
    }
    val referenced = edgeCands
      .filter(_.edgeType == GraphLabels.CoauthoredWith)
      .flatMap(_.properties.get("coauthorEdgeId"))
      .toSet
    recCands.filterNot(r => referenced(r.edgeId)).foreach { r =>
      rejected += Rejection(
        "coauthor-edge",
        r.edgeId,
        "no COAUTHORED_WITH graph edge references this record"
      )
    }

    // files ------------------------------------------------------------------------------------
    def csv(
        name: String,
        kind: String,
        label: String,
        leading: Vector[String],
        cols: Vector[Col],
        rows: Vector[(String, Vector[String])]
    ): CsvFile =
      val header = (leading ++ cols.map(_.header)).mkString(",")
      val body = rows.sortBy(_._1).map((_, cs) => NeptuneCsv.line(cs)).mkString
      val bytes = (header + "\n" + body).getBytes(UTF_8)
      CsvFile(name, kind, label, header, rows.size, Hashing.sha256Hex(bytes), bytes)

    val nodeFiles = NeptuneSchema.NodeColumns.toVector.sortBy(_._1).flatMap { (label, cols) =>
      val rows = nodeRows.collect {
        case (n, ps) if n.label == label => n.nodeId -> (Vector(n.nodeId, n.label) ++ ps)
      }
      Option.when(rows.nonEmpty)(
        csv(s"nodes-$label.csv", "node", label, Vector(":ID", ":LABEL"), cols, rows)
      )
    }
    val allEdgeRows = plainRows ++ coauthorRows
    val edgeFiles = NeptuneSchema.EdgeColumns.toVector.sortBy(_._1).flatMap { (tpe, cols) =>
      val rows = allEdgeRows.collect {
        case (e, ps) if e.edgeType == tpe =>
          e.edgeId -> (Vector(e.edgeId, e.fromId, e.toId, e.edgeType) ++ ps)
      }
      Option.when(rows.nonEmpty)(
        csv(
          s"edges-$tpe.csv",
          "relationship",
          tpe,
          Vector(":ID", ":START_ID", ":END_ID", ":TYPE"),
          cols,
          rows
        )
      )
    }
    val files = nodeFiles ++ edgeFiles
    val manifest = NeptuneLoadManifest(
      NeptuneLoadManifest.Version,
      in.runId,
      in.snapshot,
      in.policyVersion,
      "opencypher",
      NeptuneLoadManifest.IdScheme,
      windowLabels,
      NeptuneLoadManifest.CoauthorRule,
      files.map(f =>
        ManifestFile(f.name, f.kind, f.label, f.header, f.rows, f.sha256, f.bytes.length.toLong)
      ),
      nodeFiles.map(f => CountEntry(f.label, f.rows)),
      edgeFiles.map(f => CountEntry(f.label, f.rows)),
      duplicates,
      rejected.result().sortBy(r => (r.kind, r.id, r.reason)),
      NeptuneSchema.NotExportedNodes,
      NeptuneSchema.NotExportedEdges
    )
    NeptuneLoadPlan(manifest, files)
