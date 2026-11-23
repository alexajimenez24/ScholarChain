package scholarchain.hadoop

import scholarchain.core.*
import java.io.StringReader
import java.text.Normalizer
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamReader}
import scala.collection.mutable
import scala.util.Try

/** Parses one complete DBLP top-level record (e.g. `<article key=...>...</article>`). */
object DblpParser:
  /** Record types retained for the coauthorship graph. `www` (person pages), `proceedings`
    * (editor-only volumes) and `data` (datasets) are skipped and counted.
    */
  val RetainedTypes: Set[String] =
    Set("article", "inproceedings", "incollection", "book", "phdthesis", "mastersthesis")
  val AllTypes: Set[String] = RetainedTypes ++ Set("proceedings", "www", "data")

  final case class Parsed(
      recordType: String,
      dblpKey: String,
      title: String,
      authors: Vector[(String, Option[String])],
      year: Option[Int],
      venue: Option[String],
      ee: Vector[String],
      urls: Vector[String]
  )

  enum Outcome:
    case Retained(work: WorkRecord)
    case Skipped(recordType: String)
    case Rejected(reason: String)

  /** StAX factories are not guaranteed thread safe, so each thread has its own. DTDs and external
    * entities are off (no XXE); named entities are rewritten before parsing.
    */
  private val factory: ThreadLocal[XMLInputFactory] = ThreadLocal.withInitial(() =>
    val f = XMLInputFactory.newInstance()
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    f.setProperty(XMLInputFactory.IS_COALESCING, true)
    f
  )

  def parseRecord(xml: String): Either[String, Parsed] =
    if xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY") then
      Left("DOCTYPE or ENTITY declaration inside a record")
    else
      DblpEntities.toNumeric(xml).flatMap { numeric =>
        Try(read(factory.get.createXMLStreamReader(new StringReader(numeric)))).toEither.left
          .map(e =>
            s"malformed XML: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(200)}"
          )
          .flatten
      }

  private def read(r: XMLStreamReader): Either[String, Parsed] =
    try
      while r.hasNext && r.next() != XMLStreamConstants.START_ELEMENT do ()
      if !r.isStartElement then Left("no root element")
      else
        val recordType = r.getLocalName
        val recordKey = Option(r.getAttributeValue(null, "key")).map(_.trim).getOrElse("")
        val authors = mutable.ArrayBuffer.empty[(String, Option[String])]
        val ee = mutable.ArrayBuffer.empty[String]
        val urls = mutable.ArrayBuffer.empty[String]
        var title = ""; var year: Option[String] = None; var venue: Option[String] = None
        while r.hasNext do
          if r.next() == XMLStreamConstants.START_ELEMENT then
            r.getLocalName match
              case "author" =>
                val orcid = Option(r.getAttributeValue(null, "orcid"))
                authors += (cleanName(text(r)) -> orcid)
              case "title" => title = text(r)
              case "year"  => year = Some(text(r).trim)
              case "journal" | "booktitle" | "school" | "publisher" if venue.isEmpty =>
                venue = Some(text(r).trim).filter(_.nonEmpty)
              case "ee"  => ee += text(r).trim
              case "url" => urls += text(r).trim
              case _     => ()
        if !AllTypes(recordType) then Left(s"unknown record type '$recordType'")
        else if recordKey.isEmpty then Left(s"$recordType without key")
        else
          val y = year.flatMap(_.toIntOption)
          if year.nonEmpty && y.isEmpty then Left(s"$recordKey: non-numeric year ${year.get}")
          else
            Right(
              Parsed(
                recordType,
                recordKey,
                displayTitle(title),
                authors.toVector,
                y,
                venue,
                ee.toVector.filter(_.nonEmpty),
                urls.toVector.filter(_.nonEmpty)
              )
            )
    finally r.close()

  /** NFC form with single spaces: two spellings of one name compare equal as raw names. */
  def cleanName(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFC).trim.replaceAll("\\s+", " ")

  /** DBLP ends every title with a period by convention; drop one so titles render cleanly. */
  def displayTitle(raw: String): String =
    val t = Normalizer.normalize(raw, Normalizer.Form.NFC).trim.replaceAll("\\s+", " ")
    if t.endsWith(".") && !t.endsWith("..") then t.dropRight(1) else t

  /** Concatenated text of the current element and its children (titles hold <i>, <sub>, ...). */
  private def text(r: XMLStreamReader): String =
    val sb = new StringBuilder
    var depth = 1
    while depth > 0 && r.hasNext do
      r.next() match
        case XMLStreamConstants.START_ELEMENT => depth += 1
        case XMLStreamConstants.END_ELEMENT   => depth -= 1
        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA | XMLStreamConstants.SPACE =>
          sb.append(r.getText)
        case _ => ()
    sb.toString

  /** The venue stream of a DBLP key: `conf/sosp/X21` gives `venue:dblp:conf/sosp`. */
  def venueId(recordKey: String): Option[String] =
    recordKey.split('/') match
      case Array(kind, stream, _*) if kind == "conf" || kind == "journals" =>
        Some(s"venue:dblp:$kind/$stream")
      case _ => None

  def venueKind(venueId: String): String =
    if venueId.startsWith("venue:dblp:conf/") then "conference"
    else if venueId.startsWith("venue:dblp:journals/") then "journal"
    else "unknown"

  private def host(url: String): String =
    Try(new java.net.URI(url.trim).getHost).toOption.flatMap(Option(_)).getOrElse("").toLowerCase

  /** Identifier values `toWork` drops: an `orcid` attribute that is not a valid iD, an `ee` link at
    * doi.org or dx.doi.org that is not a valid DOI, and an `ee` at arxiv.org that is not an arXiv
    * ID. Ordinary URLs are not identifiers and are never counted.
    */
  def invalidIdentifiers(p: Parsed): Int =
    p.authors.count((_, o) => o.exists(v => Normalize.orcid(v).isEmpty)) +
      p.ee.count { e =>
        val h = host(e)
        ((h == "doi.org" || h == "dx.doi.org") && Normalize.doi(e).isEmpty) ||
        ((h == "arxiv.org" || h == "www.arxiv.org") && Identifiers.arxiv(e).isEmpty)
      }

  def toWork(p: Parsed, run: RunContext): Outcome =
    if !RetainedTypes(p.recordType) then Outcome.Skipped(p.recordType)
    else if p.title.isEmpty then Outcome.Rejected(s"${p.dblpKey}: empty title")
    else if p.year.exists(y => y < Validate.MinYear || y > Validate.MaxYear) then
      Outcome.Rejected(s"${p.dblpKey}: implausible year ${p.year.get}")
    else if p.authors.exists(_._1.isEmpty) then
      Outcome.Rejected(s"${p.dblpKey}: an author element has no name")
    else
      Try {
        val workId = Ids.work("dblp", p.dblpKey)
        val mentionProv = run.provenance(SourceType.DBLP, p.dblpKey, SchemaVersions.Mention)
        val mentions = p.authors.zipWithIndex.map { case ((name, orcid), i) =>
          AuthorMention(
            Ids.mention(workId, i),
            workId,
            i,
            name,
            Normalize.personName(name),
            orcid.flatMap(Normalize.orcid),
            None,
            None,
            mentionProv
          )
        }
        val doi = p.ee.iterator.flatMap(Normalize.doi).nextOption()
        val arxiv =
          p.ee.iterator.filter(_.contains("arxiv.org")).flatMap(Identifiers.arxiv).nextOption()
        WorkRecord(
          SchemaVersions.Work,
          workId,
          SourceType.DBLP,
          p.dblpKey,
          p.recordType,
          p.title,
          Normalize.title(p.title),
          p.year,
          venueId(p.dblpKey),
          p.venue,
          doi,
          arxiv,
          None,
          (p.ee ++ p.urls).distinct,
          mentions,
          Vector.empty,
          Vector.empty,
          None,
          run.provenance(SourceType.DBLP, p.dblpKey, SchemaVersions.Work)
        )
      }.fold(e => Outcome.Rejected(s"${p.dblpKey}: ${e.getMessage}".take(300)), Outcome.Retained(_))
