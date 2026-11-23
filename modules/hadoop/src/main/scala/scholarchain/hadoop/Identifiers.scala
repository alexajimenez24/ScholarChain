package scholarchain.hadoop

import scholarchain.core.*

/** Exact-identifier normalizers. DOI and ORCID are the core rules; arXiv, OpenAlex and Semantic
  * Scholar are defined here because only the Hadoop stage reads them. Every function is pure and
  * returns None for input that is not shaped like the identifier.
  */
object Identifiers:
  def doi(raw: String): Option[String] = Normalize.doi(raw)
  def orcid(raw: String): Option[String] = Normalize.orcid(raw)

  private val ArxivPrefix = "^(?:arxiv:\\s*|https?://(?:www\\.)?arxiv\\.org/(?:abs|pdf)/)".r
  private val ArxivNew = "^(\\d{4}\\.\\d{4,5})(?:v\\d+)?$".r
  private val ArxivOld = "^([a-z\\-]+(?:\\.[a-z]{2})?/\\d{7})(?:v\\d+)?$".r

  /** Bare arXiv ID without version: `https://arxiv.org/abs/2101.00001v2` gives `2101.00001`. */
  def arxiv(raw: String): Option[String] =
    val s = ArxivPrefix.replaceFirstIn(raw.trim.toLowerCase(java.util.Locale.ROOT), "")
    val bare = s.stripSuffix(".pdf")
    bare match
      case ArxivNew(id) => Some(id)
      case ArxivOld(id) => Some(id)
      case _            => None

  private val OpenAlexShape = "^(?:https?://openalex\\.org/)?([A-Za-z]\\d{2,12})$".r

  /** `https://openalex.org/W123` or `w123` gives `W123`. */
  def openAlex(raw: String): Option[String] = raw.trim match
    case OpenAlexShape(id) => Some(id.toUpperCase(java.util.Locale.ROOT))
    case _                 => None

  private val S2Sha =
    "^(?:https?://(?:www\\.)?semanticscholar\\.org/paper/(?:[^/]+/)?)?([0-9a-fA-F]{40})$".r
  private val S2Corpus = "^(?i:corpusid):\\s*(\\d{1,12})$".r

  /** A 40-hex Semantic Scholar paper ID (lower case) or `corpus:<digits>`. */
  def semanticScholar(raw: String): Option[String] = raw.trim match
    case S2Sha(id)     => Some(id.toLowerCase(java.util.Locale.ROOT))
    case S2Corpus(num) => Some(s"corpus:$num")
    case _             => None

  /** Researcher blocking key `<last token>_<first initial>` over the folded display-order name;
    * empty when the name has no letters or digits. DBLP homonym suffixes are ignored.
    */
  def nameBlockKey(rawName: String): String =
    val tokens = Normalize.personName(rawName).split(' ').filter(_.nonEmpty)
    if tokens.isEmpty then "" else s"${tokens.last}_${tokens.head.take(1)}"
