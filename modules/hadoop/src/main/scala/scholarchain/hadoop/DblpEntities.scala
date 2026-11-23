package scholarchain.hadoop

/** Character entities declared by dblp.dtd (the ISO 8859-1 set, code points 160 to 255) plus the
  * XML built-ins. Records are rewritten to numeric references before parsing, so one record can be
  * parsed without loading the DTD (and without any entity expansion by the parser).
  */
object DblpEntities:
  private val Latin1Names: Vector[String] =
    ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn " +
      "sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave " +
      "Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc " +
      "Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml " +
      "Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc " +
      "euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash " +
      "ugrave uacute ucirc uuml yacute thorn yuml").split(' ').toVector

  /** Entity name to code point; 96 entries, code points 160 to 255. */
  val CodePoints: Map[String, Int] = Latin1Names.zipWithIndex.map((n, i) => n -> (160 + i)).toMap
  private val Builtin = Map("amp" -> "&", "lt" -> "<", "gt" -> ">", "quot" -> "\"", "apos" -> "'")
  private val EntityRef = "&([A-Za-z][A-Za-z0-9]*);".r

  /** Rewrites named entities to numeric references; Left lists entities the DTD does not declare.
    */
  def toNumeric(xml: String): Either[String, String] =
    val unknown = EntityRef
      .findAllMatchIn(xml)
      .map(_.group(1))
      .filterNot(n => Builtin.contains(n) || CodePoints.contains(n))
      .toSet
    if unknown.nonEmpty then Left(s"undeclared entities: ${unknown.toVector.sorted.mkString(",")}")
    else
      Right(
        EntityRef.replaceAllIn(
          xml,
          m => CodePoints.get(m.group(1)).fold(m.matched)(cp => s"&#$cp;")
        )
      )

  private val AnyRef =
    "&(?:#x([0-9A-Fa-f]{1,6})|#([0-9]{1,7})|([A-Za-z][A-Za-z0-9]*));".r

  /** Decodes DTD-declared, built-in and numeric entities in plain text (Crossref titles carry them)
    * in one pass, so `&amp;#65;` becomes `&#65;`, not `A`. An undeclared name or an invalid code
    * point is left as written; the text is never rejected for it.
    */
  def decode(text: String): String =
    AnyRef.replaceAllIn(
      text,
      m =>
        val cp: Option[Int] =
          Option(m.group(1))
            .flatMap(h => scala.util.Try(Integer.parseInt(h, 16)).toOption)
            .orElse(Option(m.group(2)).flatMap(_.toIntOption))
            .orElse(Option(m.group(3)).flatMap(CodePoints.get))
        val s = Option(m.group(3)).flatMap(Builtin.get).orElse {
          cp.filter(c => c > 0 && Character.isValidCodePoint(c))
            .map(c => new String(Character.toChars(c)))
        }
        scala.util.matching.Regex.quoteReplacement(s.getOrElse(m.matched))
    )
