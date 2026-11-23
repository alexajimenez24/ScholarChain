package scholarchain.hadoop

/** Deterministic string and set similarities used for match features. Pure, no locale or clock. */
object Similarity:
  /** Tokens of two or more characters; one-letter tokens are initials, not evidence. */
  def tokens(text: String): Set[String] =
    text.split(' ').iterator.filter(_.length > 1).toSet

  /** |A and B| / |A or B|; 0.0 when both are empty (no evidence, not agreement). */
  def jaccard[T](a: Set[T], b: Set[T]): Double =
    val union = a.union(b).size
    if union == 0 then 0.0 else a.intersect(b).size.toDouble / union

  def jaroWinkler(a: String, b: String): Double =
    if a == b then (if a.isEmpty then 0.0 else 1.0)
    else if a.isEmpty || b.isEmpty then 0.0
    else
      val window = math.max(0, math.max(a.length, b.length) / 2 - 1)
      val aMatched = new Array[Boolean](a.length)
      val bMatched = new Array[Boolean](b.length)
      var matches = 0
      var i = 0
      while i < a.length do
        val lo = math.max(0, i - window)
        val hi = math.min(b.length - 1, i + window)
        var j = lo
        var found = false
        while j <= hi && !found do
          if !bMatched(j) && a(i) == b(j) then
            aMatched(i) = true; bMatched(j) = true; matches += 1; found = true
          j += 1
        i += 1
      if matches == 0 then 0.0
      else
        var transpositions = 0
        var k = 0
        var j = 0
        while j < a.length do
          if aMatched(j) then
            while !bMatched(k) do k += 1
            if a(j) != b(k) then transpositions += 1
            k += 1
          j += 1
        val m = matches.toDouble
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        val prefix = a.zip(b).takeWhile(_ == _).take(4).length
        jaro + prefix * 0.1 * (1.0 - jaro)

  /** Same last token and same first initial, for display-order normalized names. */
  def initialsCompatible(a: String, b: String): Boolean =
    val ta = a.split(' ').filter(_.nonEmpty)
    val tb = b.split(' ').filter(_.nonEmpty)
    ta.nonEmpty && tb.nonEmpty && ta.last == tb.last && ta.head.head == tb.head.head
