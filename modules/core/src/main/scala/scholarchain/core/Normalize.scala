package scholarchain.core

import java.text.Normalizer

/** The deterministic normalization rules that define the contract fields `normalizedTitle`,
  * `normalizedName`, `doi` and `orcid`.
  *
  * Kept in core only because those fields and the ORCID researcher ID need one definition that
  * every producer shares. Every rule is pure and versioned by [[Normalize.Version]]; changing a
  * rule changes the version. Matching features (blocking keys, similarity) belong to
  * entity-resolution, not here.
  */
object Normalize:
  val Version = "norm-v1"

  private val Diacritics = "\\p{InCombiningDiacriticalMarks}+".r
  private val Markup = "<[^>]{1,40}>".r
  private val NonAlnum = "[^\\p{L}\\p{N}]+".r
  private val Spaces = "\\s+".r
  private val DblpHomonym = "\\s+\\d{4}$".r

  /** Strips accents and folds compatibility characters (NFKD), e.g. "Müller" -> "Muller". */
  def foldUnicode(text: String): String =
    Diacritics.replaceAllIn(Normalizer.normalize(text, Normalizer.Form.NFKD), "")

  /** Lower-case, accent-free, punctuation-free, single-spaced title without inline markup. */
  def title(raw: String): String =
    val noMarkup = Markup.replaceAllIn(raw, " ")
    val folded = foldUnicode(noMarkup).toLowerCase(java.util.Locale.ROOT)
    Spaces.replaceAllIn(NonAlnum.replaceAllIn(folded, " "), " ").trim

  /** Removes a DBLP homonym suffix ("Wei Wang 0003" -> "Wei Wang"). */
  def stripDblpHomonym(name: String): String = DblpHomonym.replaceFirstIn(name.trim, "")

  /** Display-order name ("Last, First" becomes "First Last"), accent-free, lower-case tokens. */
  def personName(raw: String): String =
    val base = stripDblpHomonym(raw)
    val ordered = base.split(",", 2) match
      case Array(last, first) if first.trim.nonEmpty => s"${first.trim} ${last.trim}"
      case _                                         => base
    val folded = foldUnicode(ordered).toLowerCase(java.util.Locale.ROOT).replace('-', ' ')
    Spaces.replaceAllIn(NonAlnum.replaceAllIn(folded, " "), " ").trim

  private val DoiPrefix = "^(?:https?://(?:dx\\.)?doi\\.org/|doi:\\s*)".r
  private val DoiShape = "^10\\.\\d{4,9}/\\S+$".r

  /** Lower-cased bare DOI, or None when the input is not DOI-shaped. */
  def doi(raw: String): Option[String] =
    val bare = DoiPrefix.replaceFirstIn(raw.trim.toLowerCase(java.util.Locale.ROOT), "").trim
    Option(bare).filter(DoiShape.matches)

  private val OrcidShape = "^(\\d{4})-?(\\d{4})-?(\\d{4})-?(\\d{3}[\\dX])$".r

  /** Hyphenated ORCID iD with a valid ISO 7064 MOD 11-2 check digit, else None. */
  def orcid(raw: String): Option[String] =
    val bare =
      raw.trim.toUpperCase(java.util.Locale.ROOT).replaceFirst("^HTTPS?://ORCID\\.ORG/", "")
    bare match
      case OrcidShape(a, b, c, d) =>
        val digits = a + b + c + d
        Option.when(orcidCheckDigit(digits.dropRight(1)) == digits.last)(s"$a-$b-$c-$d")
      case _ => None

  /** ISO 7064 MOD 11-2 check character for the first 15 ORCID digits. */
  def orcidCheckDigit(first15: String): Char =
    val total = first15.foldLeft(0)((acc, ch) => (acc + ch.asDigit) * 2)
    val result = (12 - total % 11) % 11
    if result == 10 then 'X' else ('0' + result).toChar
