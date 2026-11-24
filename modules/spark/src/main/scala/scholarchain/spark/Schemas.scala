package scholarchain.spark

import org.apache.spark.sql.types.*

/** Explicit read schemas for the records Spark consumes: the Phase 04 Hadoop J07 export
  * (`export/{works,mentions,pairs,authorpapers,topics,institutions,venues}`, pipeline `hadoop-v2`,
  * normalization `norm-v1`), the Phase 04 J02 OpenAlex `abstracts` and the Phase 05 er-v1
  * `MatchDecision` outputs (`match-decisions/.../{accepted,rejected,unresolved,conflict}`).
  *
  * Field names and types mirror the core records as committed (`WorkRecord`, `AuthorMention`,
  * `CoauthorPairCandidate`, `Topic`, `Institution`, `Venue`, `MatchDecision`) and the hadoop-local
  * `author-paper-v1` record. Reading with a schema (no inference) plus a corrupt-record column
  * catches part of contract drift: a line that is not JSON or holds a value of the wrong type is a
  * counted SCHEMA_MISMATCH reject, and a missing or renamed required field is a counted
  * REQUIRED_FIELD_NULL reject (see [[Inputs]]); no type is ever guessed. Not caught: the PERMISSIVE
  * JSON reader silently ignores fields the schema does not name, so an added field, or a renamed
  * optional field (which then reads as null), passes without a reject. The upstream pipeline,
  * normalization and policy versions checked by [[Upstream.locate]] are the guard against that.
  * Spark never reads raw datasets or the gold set.
  */
object Schemas:
  /** Column that receives a line Spark could not fit into the schema. */
  val CorruptColumn = "_corrupt_record"

  private def s(name: String) = StructField(name, StringType)
  private def i(name: String) = StructField(name, IntegerType)
  private def d(name: String) = StructField(name, DoubleType)
  private def arr(name: String, t: DataType = StringType) = StructField(name, ArrayType(t))

  val provenance: StructType = StructType(
    Seq(
      s("source"),
      s("sourceRecordId"),
      s("snapshot"),
      s("runId"),
      s("schemaVersion"),
      s("policyVersion")
    )
  )
  private val prov = StructField("provenance", provenance)

  /** core `AuthorMention` (author-mention-v1), export/mentions and the nested work authors. */
  val mention: StructType = StructType(
    Seq(
      s("mentionId"),
      s("workId"),
      i("position"),
      s("rawName"),
      s("normalizedName"),
      s("orcid"),
      s("externalAuthorId"),
      s("affiliation"),
      prov
    )
  )

  /** core `WorkRecord` (work-v1), export/works; one row per source record (DBLP, Crossref, ...). */
  val work: StructType = StructType(
    Seq(
      s("schemaVersion"),
      s("workId"),
      s("source"),
      s("sourceRecordId"),
      s("recordType"),
      s("title"),
      s("normalizedTitle"),
      i("year"),
      s("venueId"),
      s("venueName"),
      s("doi"),
      s("arxivId"),
      s("openAlexId"),
      arr("urls"),
      arr("authors", mention),
      arr("topicIds"),
      arr("institutionIds"),
      s("abstractTextRef"),
      prov
    )
  )

  /** hadoop `AuthorPaper` (author-paper-v1), export/authorpapers. */
  val authorPaper: StructType = StructType(
    Seq(
      s("mentionId"),
      s("workId"),
      i("position"),
      s("normalizedName"),
      s("orcid"),
      i("year"),
      i("teamSize"),
      prov
    )
  )

  /** core `CoauthorPairCandidate` (coauthor-pair-v1), export/pairs; mention level, mentionA <
    * mentionB.
    */
  val pair: StructType = StructType(
    Seq(
      s("mentionA"),
      s("mentionB"),
      s("workId"),
      i("year"),
      i("positionA"),
      i("positionB"),
      i("teamSize"),
      prov
    )
  )

  val topic: StructType = StructType(Seq(s("topicId"), s("name"), prov))

  /** hadoop `AbstractRecord` (abstract-v1), `<J02-OPENALEX output>/abstracts`: the abstract text of
    * one source work, kept apart from the work record, which holds its hash as `abstractTextRef`.
    */
  val abstractRecord: StructType =
    StructType(Seq(s("workId"), s("textHash"), s("text"), prov))

  val institution: StructType =
    StructType(Seq(s("institutionId"), s("name"), s("countryCode"), s("ror"), prov))

  val venue: StructType = StructType(Seq(s("venueId"), s("name"), s("kind"), prov))

  /** core `MatchDecision` (match-v1) as written by the er-v1 match job. */
  val decision: StructType = StructType(
    Seq(
      s("schemaVersion"),
      s("decisionId"),
      s("kind"),
      s("sourceId"),
      s("targetId"),
      StructField("features", MapType(StringType, DoubleType)),
      arr("rulesFired"),
      s("decision"),
      s("path"),
      d("confidence"),
      arr("reasonCodes"),
      s("policyVersion"),
      s("promptVersion"),
      s("modelVersion"),
      prov
    )
  )

  /** Columns that must be non-null for a row to be usable; a row missing one is a counted reject.
    */
  object Required:
    val mention: Seq[String] = Seq("mentionId", "workId")
    val work: Seq[String] = Seq("workId", "source")
    val authorPaper: Seq[String] = Seq("mentionId", "workId")
    val pair: Seq[String] = Seq("mentionA", "mentionB", "workId")
    val topic: Seq[String] = Seq("topicId")
    val institution: Seq[String] = Seq("institutionId")
    val venue: Seq[String] = Seq("venueId")
    val decision: Seq[String] = Seq("kind", "sourceId", "targetId", "decision")
    val abstractRecord: Seq[String] = Seq("workId", "textHash", "text")
