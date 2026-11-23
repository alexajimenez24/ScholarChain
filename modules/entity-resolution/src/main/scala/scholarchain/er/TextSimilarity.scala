package scholarchain.er

/** Similarity primitives of `er-features-v1`. Every function is symmetric, total and returns a
  * value in [0, 1]; two empty inputs give 0, because absence is not evidence of agreement.
  */
object TextSimilarity:

  /** Jaro-Winkler similarity with the standard prefix scale 0.1 and prefix length up to 4. */
  def jaroWinkler(a: String, b: String): Double =
    if a.isEmpty || b.isEmpty then 0.0
    else if a == b then 1.0
    else
      val window = math.max(0, math.max(a.length, b.length) / 2 - 1)
      val aMatched = Array.fill(a.length)(false)
      val bMatched = Array.fill(b.length)(false)
      var matches = 0
      for i <- a.indices do
        val lo = math.max(0, i - window)
        val hi = math.min(b.length - 1, i + window)
        var j = lo
        var found = false
        while !found && j <= hi do
          if !bMatched(j) && a(i) == b(j) then
            aMatched(i) = true; bMatched(j) = true; matches += 1; found = true
          j += 1
      if matches == 0 then 0.0
      else
        val as = a.indices.filter(aMatched).map(a(_))
        val bs = b.indices.filter(bMatched).map(b(_))
        val transpositions = as.zip(bs).count((x, y) => x != y) / 2.0
        val m = matches.toDouble
        val jaro = (m / a.length + m / b.length + (m - transpositions) / m) / 3.0
        val prefix = a.zip(b).take(4).takeWhile((x, y) => x == y).size
        math.min(1.0, jaro + prefix * 0.1 * (1.0 - jaro))

  /** Jaccard index of two sets. */
  def jaccard[T](a: Set[T], b: Set[T]): Double =
    if a.isEmpty || b.isEmpty then 0.0
    else a.intersect(b).size.toDouble / a.union(b).size

  /** Character trigrams of a normalized string, padded with one space on each side. */
  def trigrams(s: String): Map[String, Int] =
    if s.isEmpty then Map.empty
    else
      val p = s" $s "
      p.sliding(3).toVector.groupMapReduce(identity)(_ => 1)(_ + _)

  /** Sorensen-Dice coefficient over character trigram multisets. */
  def trigramDice(a: String, b: String): Double =
    if a.isEmpty || b.isEmpty then 0.0
    else if a == b then 1.0
    else
      val ta = trigrams(a)
      val tb = trigrams(b)
      val shared = ta.iterator.map((g, n) => math.min(n, tb.getOrElse(g, 0))).sum
      2.0 * shared / (ta.values.sum + tb.values.sum)

  /** Length of the longest common subsequence. */
  def lcs[T](a: Vector[T], b: Vector[T]): Int =
    if a.isEmpty || b.isEmpty then 0
    else
      var prev = Array.fill(b.length + 1)(0)
      for x <- a do
        val cur = Array.fill(b.length + 1)(0)
        for j <- b.indices do
          cur(j + 1) = if x == b(j) then prev(j) + 1 else math.max(prev(j + 1), cur(j))
        prev = cur
      prev(b.length)

  /** Order-aware sequence agreement: LCS length over the longer sequence. */
  def sequenceSimilarity[T](a: Vector[T], b: Vector[T]): Double =
    if a.isEmpty || b.isEmpty then 0.0 else lcs(a, b).toDouble / math.max(a.length, b.length)
