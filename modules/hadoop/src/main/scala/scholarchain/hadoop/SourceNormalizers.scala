package scholarchain.hadoop

import scholarchain.core.*
import scala.util.Try

/** One normalizer per enrichment dataset (OpenAlex, Crossref, ORCID). Each maps a source record
  * into shared contracts and keeps the source-specific identifiers: OpenAlex work, author,
  * institution, topic and venue IDs, Crossref DOIs and ORCID iDs, affiliations and abstracts.
  *
  * A normalizer returns Left for a record it cannot map (not JSON, no usable identifier, empty
  * title, an author without a name); the mapper writes that record to the reject output.
  */
object SourceNormalizers:
  final case class Normalized(
      works: Vector[WorkRecord],
      researchers: Vector[ExternalResearcher],
      topics: Vector[Topic],
      institutions: Vector[Institution],
      abstracts: Vector[AbstractRecord],
      invalidIdentifiers: Int
  )
  private val Empty =
    Normalized(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, 0)

  type Normalizer = (String, RunContext) => Either[String, Normalized]

  private def at(v: ujson.Value, path: Seq[String]): Option[ujson.Value] =
    path.foldLeft(Option(v))((acc, k) => acc.flatMap(_.objOpt).flatMap(_.get(k)))

  private def str(v: ujson.Value, path: String*): Option[String] =
    at(v, path).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)

  private def arr(v: ujson.Value, path: String*): Vector[ujson.Value] =
    at(v, path).flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)

  /** A JSON number that is a whole number; a fractional or non-numeric value is None. */
  private def whole(v: ujson.Value): Option[Int] =
    v.numOpt.filter(d => d == math.rint(d) && math.abs(d) < Int.MaxValue).map(_.toInt)

  private def nonJson(source: String)(e: Throwable) = s"$source: not JSON"

  def forSource(source: SourceType): Either[String, Normalizer] =
    source match
      case SourceType.OPENALEX => Right(openAlex)
      case SourceType.CROSSREF => Right(crossref)
      case SourceType.ORCID    => Right(orcid)
      case other               => Left(s"no normalizer for $other")

  private def abstractOf(
      workId: String,
      text: String,
      prov: Provenance,
      run: RunContext
  ): (String, AbstractRecord) =
    val hash = Hashing.tagged(text)
    hash -> AbstractRecord(
      workId,
      hash,
      text,
      run.provenance(prov.source, prov.sourceRecordId, HadoopSchemas.Abstract)
    )

  /** Rebuilds abstract text from OpenAlex's `abstract_inverted_index` (word to positions). */
  def invertedAbstract(v: ujson.Value): Option[String] =
    v.objOpt.flatMap { o =>
      val placed = o.toVector.flatMap((word, positions) =>
        positions.arrOpt.toVector.flatMap(_.flatMap(whole)).map(_ -> word)
      )
      val limited = placed.filter((p, _) => p >= 0 && p < 100000)
      Option.when(limited.nonEmpty)(limited.sortBy(_._1).map(_._2).mkString(" "))
    }

  private val Tag = "<[^>]{1,60}>".r

  private def institutionOf(i: ujson.Value, run: RunContext): Option[Institution] =
    str(i, "id").flatMap(Identifiers.openAlex).map { id =>
      Institution(
        s"openalex:$id",
        str(i, "display_name").getOrElse(id),
        str(i, "country_code")
          .map(_.toUpperCase(java.util.Locale.ROOT))
          .filter(c => c.length == 2 && c.forall(ch => ch >= 'A' && ch <= 'Z')),
        str(i, "ror"),
        run.provenance(SourceType.OPENALEX, id, SchemaVersions.Institution)
      )
    }

  /** OpenAlex work object (snapshot `works` JSONL or API result). */
  val openAlex: Normalizer = (line, run) =>
    Try(ujson.read(line)).toEither.left.map(nonJson("OpenAlex")).flatMap { v =>
      str(v, "id").flatMap(Identifiers.openAlex) match
        case None       => Left("OpenAlex: missing or malformed work id")
        case Some(oaId) =>
          val title = str(v, "display_name").orElse(str(v, "title")).getOrElse("")
          val authorships = arr(v, "authorships")
          val names = authorships.map(a =>
            str(a, "raw_author_name").orElse(str(a, "author", "display_name")).getOrElse("")
          )
          val yearField = at(v, Seq("publication_year")).filterNot(_ == ujson.Null)
          val year = yearField.flatMap(whole)
          if title.isEmpty then Left(s"OpenAlex $oaId: empty title")
          else if yearField.nonEmpty && year.isEmpty then
            Left(s"OpenAlex $oaId: publication_year is not a whole number")
          else if names.exists(_.isEmpty) then
            Left(s"OpenAlex $oaId: an authorship has no author name")
          else
            Try {
              val workId = Ids.work("openalex", oaId)
              val mentionProv = run.provenance(SourceType.OPENALEX, oaId, SchemaVersions.Mention)
              val institutions = authorships
                .flatMap(a => arr(a, "institutions"))
                .flatMap(institutionOf(_, run))
                .distinctBy(_.institutionId)
              val rawOrcids = authorships.map(a => str(a, "author", "orcid"))
              val orcids = rawOrcids.map(_.flatMap(Normalize.orcid))
              val badIds = rawOrcids.zip(orcids).count((raw, ok) => raw.nonEmpty && ok.isEmpty)
              val mentions = authorships.zip(names).zipWithIndex.map { case ((a, name), idx) =>
                AuthorMention(
                  Ids.mention(workId, idx),
                  workId,
                  idx,
                  DblpParser.cleanName(name),
                  Normalize.personName(name),
                  orcids(idx),
                  str(a, "author", "id").flatMap(Identifiers.openAlex).map("openalex:" + _),
                  arr(a, "institutions").headOption
                    .flatMap(i => str(i, "id"))
                    .flatMap(Identifiers.openAlex)
                    .map("openalex:" + _),
                  mentionProv
                )
              }
              val topics = arr(v, "topics")
                .flatMap(t =>
                  str(t, "id")
                    .flatMap(Identifiers.openAlex)
                    .map(id =>
                      Topic(
                        s"openalex:$id",
                        str(t, "display_name").getOrElse(id),
                        run.provenance(SourceType.OPENALEX, id, SchemaVersions.Topic)
                      )
                    )
                )
                .distinctBy(_.topicId)
              val venueId = str(v, "primary_location", "source", "id")
                .flatMap(Identifiers.openAlex)
                .map("venue:openalex:" + _)
              val rawDoi = str(v, "doi")
              val doi = rawDoi.flatMap(Normalize.doi)
              val rawArxiv = str(v, "ids", "arxiv")
              val arxiv = rawArxiv.flatMap(Identifiers.arxiv)
              val badWorkIds =
                (if rawDoi.nonEmpty && doi.isEmpty then 1 else 0) +
                  (if rawArxiv.nonEmpty && arxiv.isEmpty then 1 else 0)
              val workProv = run.provenance(SourceType.OPENALEX, oaId, SchemaVersions.Work)
              val abstractText = at(v, Seq("abstract_inverted_index")).flatMap(invertedAbstract)
              val abstractRec = abstractText.map(abstractOf(workId, _, workProv, run))
              val work = WorkRecord(
                SchemaVersions.Work,
                workId,
                SourceType.OPENALEX,
                oaId,
                str(v, "type").getOrElse("work"),
                DblpParser.displayTitle(title),
                Normalize.title(title),
                year,
                venueId,
                str(v, "primary_location", "source", "display_name"),
                doi,
                arxiv,
                Some(oaId),
                doi.map(d => s"https://doi.org/$d").toVector,
                mentions,
                topics.map(_.topicId),
                institutions.map(_.institutionId),
                abstractRec.map(_._1),
                workProv
              )
              Normalized(
                Vector(work),
                Vector.empty,
                topics,
                institutions,
                abstractRec.map(_._2).toVector,
                badIds + badWorkIds
              )
            }.toEither.left.map(e => s"OpenAlex $oaId: ${e.getMessage}".take(300))
    }

  private def crossrefName(a: ujson.Value): String =
    Vector(str(a, "given"), str(a, "family")).flatten.mkString(" ").trim match
      case ""   => str(a, "name").getOrElse("")
      case name => name

  /** Crossref work item (REST `message` object or public-data-file `items` entry). */
  val crossref: Normalizer = (line, run) =>
    Try(ujson.read(line)).toEither.left.map(nonJson("Crossref")).flatMap { raw =>
      val v = raw.objOpt.flatMap(_.get("message")).getOrElse(raw)
      str(v, "DOI").flatMap(Normalize.doi) match
        case None      => Left("Crossref: missing or malformed DOI")
        case Some(doi) =>
          val title = DblpParser.displayTitle(
            DblpEntities.decode(
              Tag.replaceAllIn(arr(v, "title").flatMap(_.strOpt).headOption.getOrElse(""), " ")
            )
          )
          val authors = arr(v, "author")
          val names = authors.map(crossrefName)
          if title.isEmpty then Left(s"Crossref $doi: empty title")
          else if names.exists(_.isEmpty) then Left(s"Crossref $doi: an author has no name")
          else
            Try {
              val workId = Ids.work("crossref", doi)
              val mentionProv = run.provenance(SourceType.CROSSREF, doi, SchemaVersions.Mention)
              val rawOrcids = authors.map(a => str(a, "ORCID"))
              val orcids = rawOrcids.map(_.flatMap(Normalize.orcid))
              val badIds = rawOrcids.zip(orcids).count((raw, ok) => raw.nonEmpty && ok.isEmpty)
              val mentions = authors.zip(names).zipWithIndex.map { case ((a, name), idx) =>
                AuthorMention(
                  Ids.mention(workId, idx),
                  workId,
                  idx,
                  DblpParser.cleanName(name),
                  Normalize.personName(name),
                  orcids(idx),
                  None,
                  arr(a, "affiliation").flatMap(x => str(x, "name")).headOption,
                  mentionProv
                )
              }
              val year = Vector("published-print", "published-online", "issued").iterator
                .flatMap(k =>
                  arr(v, k, "date-parts").headOption
                    .flatMap(_.arrOpt)
                    .flatMap(_.headOption)
                    .flatMap(whole)
                )
                .nextOption()
              val workProv = run.provenance(SourceType.CROSSREF, doi, SchemaVersions.Work)
              val abstractText = str(v, "abstract")
                .map(a =>
                  DblpEntities.decode(Tag.replaceAllIn(a, " ")).trim.replaceAll("\\s+", " ")
                )
                .filter(_.nonEmpty)
              val abstractRec = abstractText.map(abstractOf(workId, _, workProv, run))
              val work = WorkRecord(
                SchemaVersions.Work,
                workId,
                SourceType.CROSSREF,
                doi,
                str(v, "type").getOrElse("work"),
                title,
                Normalize.title(title),
                year,
                None,
                arr(v, "container-title")
                  .flatMap(_.strOpt)
                  .headOption
                  .map(_.trim)
                  .filter(_.nonEmpty),
                Some(doi),
                None,
                None,
                Vector(s"https://doi.org/$doi"),
                mentions,
                Vector.empty,
                Vector.empty,
                abstractRec.map(_._1),
                workProv
              )
              Empty.copy(
                works = Vector(work),
                abstracts = abstractRec.map(_._2).toVector,
                invalidIdentifiers = badIds
              )
            }.toEither.left.map(e => s"Crossref $doi: ${e.getMessage}".take(300))
    }

  /** ORCID public record (API v3.0 `record` JSON): identity evidence, not graph works. */
  val orcid: Normalizer = (line, run) =>
    Try(ujson.read(line)).toEither.left.map(nonJson("ORCID")).flatMap { v =>
      str(v, "orcid-identifier", "path").flatMap(Normalize.orcid) match
        case None     => Left("ORCID: missing or invalid iD")
        case Some(id) =>
          val givenName = str(v, "person", "name", "given-names", "value")
          val family = str(v, "person", "name", "family-name", "value")
          val credit = str(v, "person", "name", "credit-name", "value")
          val display = credit
            .orElse(Some(Vector(givenName, family).flatten.mkString(" ")).filter(_.nonEmpty))
            .getOrElse("")
          if display.isEmpty then Left(s"ORCID $id: no name")
          else
            val others =
              arr(v, "person", "other-names", "other-name").flatMap(o => str(o, "content"))
            val employments = arr(v, "activities-summary", "employments", "affiliation-group")
              .flatMap(g => arr(g, "summaries"))
              .flatMap(s => str(s, "employment-summary", "organization", "name"))
            val rawDois = arr(v, "activities-summary", "works", "group")
              .flatMap(g => arr(g, "external-ids", "external-id"))
              .filter(e => str(e, "external-id-type").exists(_.equalsIgnoreCase("doi")))
              .flatMap(e => str(e, "external-id-value"))
            val dois = rawDois.flatMap(Normalize.doi)
            Right(
              Empty.copy(
                researchers = Vector(
                  ExternalResearcher(
                    HadoopSchemas.ExternalResearcher,
                    s"orcid:$id",
                    SourceType.ORCID,
                    Some(id),
                    None,
                    display,
                    (display +: others).distinct,
                    employments.distinct,
                    dois.distinct,
                    run.provenance(SourceType.ORCID, id, HadoopSchemas.ExternalResearcher)
                  )
                ),
                invalidIdentifiers = rawDois.size - dois.size
              )
            )
    }
