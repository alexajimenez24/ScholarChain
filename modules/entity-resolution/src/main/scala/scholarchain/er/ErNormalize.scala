package scholarchain.er

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

import scala.util.Try

import scholarchain.core.Normalize

/** A person name split into given-name tokens and one family-name token, all folded. */
final case class PersonName(givenNames: Vector[String], family: String):
  def full: String = (givenNames :+ family).filter(_.nonEmpty).mkString(" ")
  def initials: Vector[Char] = givenNames.flatMap(_.headOption)

  /** Family name plus first initial, the key used for coauthor and author-sequence comparison. */
  def key: String = initials.headOption.fold(family)(i => s"${family}_$i")

  /** The same tokens read in the other order (family first), for two-token names. */
  def swapped: Option[PersonName] =
    givenNames match
      case Vector(g) if family.nonEmpty => Some(PersonName(Vector(family), g))
      case _                            => None

/** Entity-resolution normalization, version `er-norm-v1`. Pure and deterministic; changing any rule
  * changes [[ErNormalize.Version]].
  *
  * It covers Unicode (compatibility folding, accents, letters NFKD does not decompose),
  * punctuation, whitespace, title markup (XML/HTML/MathML tags, character entities, TeX commands
  * and braces), initials, name order ("Last, First", DBLP homonym suffixes, generational suffixes,
  * particles) and identifier prefixes (DOI, arXiv, ORCID, OpenAlex, Semantic Scholar, repository
  * URLs).
  *
  * The core `Normalize` (norm-v1) defines the contract fields written by Hadoop; this object is the
  * matching-side definition and is versioned separately.
  */
object ErNormalize:
  val Version = "er-norm-v1"

  private val Tags = "<[^<>]{0,200}>".r
  private val Entity = "&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});".r
  private val TexCommand = "\\\\[a-zA-Z]+\\*?".r
  private val TexSymbols = "[{}$\\\\^_~]".r
  private val Marks = "\\p{M}+".r
  private val NonAlnum = "[^\\p{L}\\p{N}]+".r
  private val Spaces = "\\s+".r
  private val Apostrophes = "['’ʼ´`]".r
  private val DblpHomonym = "\\s+\\d{4}$".r

  private val NamedEntities = Map(
    "amp" -> "&",
    "lt" -> "<",
    "gt" -> ">",
    "quot" -> "\"",
    "apos" -> "'",
    "nbsp" -> " ",
    "ndash" -> "-",
    "mdash" -> "-"
  )

  /** Letters that NFKD leaves intact but that sources spell in ASCII. */
  private val Special: Map[Char, String] = Map(
    'ß' -> "ss",
    'æ' -> "ae",
    'Æ' -> "ae",
    'œ' -> "oe",
    'Œ' -> "oe",
    'ø' -> "o",
    'Ø' -> "o",
    'ł' -> "l",
    'Ł' -> "l",
    'đ' -> "d",
    'Đ' -> "d",
    'ð' -> "d",
    'Ð' -> "d",
    'þ' -> "th",
    'Þ' -> "th",
    'ı' -> "i"
  )

  /** Stop words left out of title token sets (they carry no topic evidence). */
  val StopWords: Set[String] = Set(
    "a",
    "an",
    "the",
    "of",
    "and",
    "in",
    "on",
    "for",
    "to",
    "with",
    "by",
    "from",
    "at",
    "via",
    "using",
    "its",
    "is",
    "are",
    "or"
  )

  private val NameSuffixes = Set("jr", "sr", "ii", "iii", "iv")
  private val Particles =
    Set("van", "von", "de", "der", "den", "da", "di", "del", "della", "du", "dos", "das", "ter")

  private def decodeEntity(body: String): String =
    if body.startsWith("#x") || body.startsWith("#X") then
      Try(new String(Character.toChars(Integer.parseInt(body.drop(2), 16)))).getOrElse(" ")
    else if body.startsWith("#") then
      Try(new String(Character.toChars(body.drop(1).toInt))).getOrElse(" ")
    else NamedEntities.getOrElse(body.toLowerCase(Locale.ROOT), " ")

  /** Removes title markup: tags first, then character entities, then TeX commands and symbols. */
  def stripMarkup(raw: String): String =
    val noTags = Tags.replaceAllIn(raw, " ")
    val decoded = Entity.replaceAllIn(
      noTags,
      m => scala.util.matching.Regex.quoteReplacement(decodeEntity(m.group(1)))
    )
    TexSymbols.replaceAllIn(TexCommand.replaceAllIn(decoded, " "), " ")

  /** Compatibility fold (NFKD), accents removed, special letters spelled out, lower case. */
  def fold(text: String): String =
    val decomposed = Marks.replaceAllIn(Normalizer.normalize(text, Normalizer.Form.NFKD), "")
    val sb = new StringBuilder(decomposed.length)
    decomposed.foreach(ch => Special.get(ch).fold(sb.append(ch))(sb.append))
    sb.toString.toLowerCase(Locale.ROOT)

  /** Folded text with punctuation as single spaces and no leading or trailing space. */
  def text(raw: String): String =
    Spaces.replaceAllIn(NonAlnum.replaceAllIn(fold(raw), " "), " ").trim

  /** Normalized title: markup removed, then [[text]]. */
  def title(raw: String): String = text(stripMarkup(raw))

  /** Title tokens without stop words. */
  def titleTokens(raw: String): Set[String] =
    title(raw).split(' ').iterator.filter(t => t.nonEmpty && !StopWords(t)).toSet

  /** Parses a person name: DBLP homonym suffix and generational suffixes removed, "Last, First"
    * reordered, apostrophes dropped inside names (O'Brien is obrien), hyphens and dots split given
    * names into tokens, particles dropped, and the last token taken as the family name.
    */
  def personName(raw: String): PersonName =
    val base = DblpHomonym.replaceFirstIn(raw.trim, "")
    val ordered = base.split(",").map(_.trim).filter(_.nonEmpty).toVector match
      case Vector(last, first)       => s"$first $last"
      case Vector(last, first, rest) =>
        if NameSuffixes(text(rest)) then s"$first $last" else s"$first $rest $last"
      case parts => parts.mkString(" ")
    val tokens = text(Apostrophes.replaceAllIn(ordered, "")).split(' ').toVector.filter(_.nonEmpty)
    val cleaned = tokens.filterNot(NameSuffixes)
    cleaned match
      case Vector()  => PersonName(Vector.empty, "")
      case Vector(f) => PersonName(Vector.empty, f)
      case more      => PersonName(more.init.filterNot(Particles), more.last)

  /** Raw name with only the DBLP homonym suffix removed, for homonym comparison. */
  def stripDblpHomonym(raw: String): String = DblpHomonym.replaceFirstIn(raw.trim, "")

  // ------------------------------------------------------------------ identifiers

  private val DoiPrefix =
    "^(?:(?:https?://)?(?:dx\\.|www\\.)?doi\\.org/|doi:\\s*|urn:doi:|info:doi/)".r
  private val DoiShape = "^10\\.\\d{4,9}/\\S+$".r

  private def percentDecode(s: String): String =
    if !s.contains('%') then s
    else Try(java.net.URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)).getOrElse(s)

  /** Lower-case bare DOI with resolver and `doi:` prefixes, percent-encoding and trailing sentence
    * punctuation removed, or None when the text is not DOI-shaped.
    */
  def doi(raw: String): Option[String] =
    val lower = percentDecode(raw.trim).toLowerCase(Locale.ROOT)
    val bare = DoiPrefix.replaceFirstIn(lower, "").trim.reverse.dropWhile(".,;".contains(_)).reverse
    Option(bare).filter(DoiShape.matches)

  private val ArxivPrefix =
    "^(?:(?:https?://)?(?:www\\.|export\\.)?arxiv\\.org/(?:abs|pdf)/|arxiv:\\s*|10\\.48550/arxiv\\.)".r
  private val ArxivNew = "^(\\d{4}\\.\\d{4,5})(?:v\\d+)?(?:\\.pdf)?$".r
  private val ArxivOld = "^([a-z][a-z\\-]*(?:\\.[a-z]{2})?/\\d{7})(?:v\\d+)?(?:\\.pdf)?$".r

  /** Bare arXiv identifier without version, new (2101.00001) or old (hep-th/9901001) style. */
  def arxiv(raw: String): Option[String] =
    val bare = ArxivPrefix.replaceFirstIn(raw.trim.toLowerCase(Locale.ROOT), "").trim
    bare match
      case ArxivNew(id) => Some(id)
      case ArxivOld(id) => Some(id)
      case _            => None

  /** Hyphenated ORCID iD with a valid check digit (core rule), after removing `orcid:`, `www.` and
    * `http://` forms that the core function does not accept.
    */
  def orcid(raw: String): Option[String] =
    val t = raw.trim
    val noScheme = t
      .replaceFirst("(?i)^orcid:\\s*", "")
      .replaceFirst("(?i)^(https?://)?(www\\.)?orcid\\.org/", "")
    Normalize.orcid(noScheme)

  private val OpenAlexId = "^(?:(?:https?://)?openalex\\.org/|openalex:)?([awisvptc])(\\d{2,12})$".r

  /** OpenAlex ID as `<entity letter><digits>` (A for authors, W for works), or None. */
  def openAlex(raw: String, entity: Char): Option[String] =
    raw.trim.toLowerCase(Locale.ROOT) match
      case OpenAlexId(letter, digits) if letter.head == entity.toLower =>
        Some(s"${entity.toUpper}$digits")
      case _ => None

  private val S2PaperUrl =
    "^(?:https?://)?(?:www\\.)?semanticscholar\\.org/paper/(?:[^/]+/)?([0-9a-f]{40})/?$".r
  private val S2Sha = "^(?:s2:)?([0-9a-f]{40})$".r
  private val S2Corpus = "^(?:https?://api\\.semanticscholar\\.org/)?corpusid:\\s*(\\d{1,12})$".r
  private val S2AuthorUrl =
    "^(?:https?://)?(?:www\\.)?semanticscholar\\.org/author/(?:[^/]+/)?(\\d{1,12})/?$".r
  private val S2AuthorBare = "^s2author:(\\d{1,12})$".r

  /** Semantic Scholar paper ID: `sha:<40 hex>` or `corpus:<n>` (the two forms are not comparable
    * with each other, so they are kept apart).
    */
  def semanticScholarPaper(raw: String): Option[String] =
    raw.trim.toLowerCase(Locale.ROOT) match
      case S2PaperUrl(sha) => Some(s"sha:$sha")
      case S2Sha(sha)      => Some(s"sha:$sha")
      case S2Corpus(n)     => Some(s"corpus:${n.toLong}")
      case _               => None

  /** Semantic Scholar author ID as `author:<n>`. */
  def semanticScholarAuthor(raw: String): Option[String] =
    raw.trim.toLowerCase(Locale.ROOT) match
      case S2AuthorUrl(n)  => Some(s"author:${n.toLong}")
      case S2AuthorBare(n) => Some(s"author:${n.toLong}")
      case _               => None

  private val RepoHosts = Set("github.com", "gitlab.com", "bitbucket.org", "codeberg.org")
  private val RepoReserved =
    Set("orgs", "topics", "sponsors", "marketplace", "features", "about", "explore", "search")
  private val RepoUrl = "^(?:https?://)?(?:www\\.)?([a-z0-9.\\-]+)/([^/\\s?#]+)/([^/\\s?#]+).*$".r

  /** Stable repository URL as `host/owner/repo` (lower case, no scheme, `www.`, `.git`, deeper
    * path, query or fragment), for GitHub, GitLab, Bitbucket and Codeberg only.
    */
  def repositoryUrl(raw: String): Option[String] =
    raw.trim.toLowerCase(Locale.ROOT) match
      case RepoUrl(host, owner, repo) if RepoHosts(host) && !RepoReserved(owner) =>
        val r = repo.stripSuffix(".git")
        Option.when(r.nonEmpty)(s"$host/$owner/$r")
      case _ => None
