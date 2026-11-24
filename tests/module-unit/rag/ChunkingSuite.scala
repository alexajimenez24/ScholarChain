package scholarchain.rag

import scholarchain.core.{Hashing, Ids}

class ChunkingSuite extends munit.FunSuite:
  private val small = ChunkPolicy("chunk-test", maxChars = 50, minChars = 25, overlapChars = 10, 8)

  private def spans(t: String, p: ChunkPolicy = small) =
    Chunking.spans(t, p).fold(e => fail(e), identity)

  /** Bounds, coverage, order and overlap of any split. */
  private def wellFormed(t: String, s: Vector[Span], p: ChunkPolicy): Unit =
    assertEquals(s.head.start, 0)
    assertEquals(s.last.end, t.length)
    s.foreach(x => assert(x.end - x.start <= p.maxChars && x.end > x.start, x.toString))
    s.zip(s.tail).foreach { (a, b) =>
      assert(b.start > a.start, s"no progress: $a $b")
      assert(b.start <= a.end, s"gap: $a $b")
      assert(a.end - b.start <= p.overlapChars, s"overlap too large: $a $b")
    }

  test("chunk-v1 is pinned: 2000 max, 1000 min, 200 overlap, 32 chunks") {
    assertEquals(ChunkPolicy.V1, ChunkPolicy("chunk-v1", 2000, 1000, 200, 32))
    assert(ChunkPolicy.V1.validate.isRight)
  }

  test("a text within maxChars is one chunk: the whole text") {
    assertEquals(spans("short text."), Vector(Span(0, 11)))
    assertEquals(spans("x" * 50), Vector(Span(0, 50)))
  }

  test("boundaries prefer a sentence or list end, then whitespace, then a hard cut") {
    val t = "Alpha beta gamma delta. Epsilon zeta eta theta iota kappa lambda mu nu xi omicron."
    val s = spans(t)
    wellFormed(t, s, small)
    assertEquals(
      t.substring(s.head.start, s.head.end),
      "Alpha beta gamma delta. Epsilon zeta eta theta"
    )
    val list = "aaaa (1); bbbb (1); cccc (1); dddd (1); eeee (1); ffff (1); gggg (1); hhhh (1)."
    val l = spans(list)
    wellFormed(list, l, small)
    assert(list.substring(l.head.start, l.head.end).endsWith(";"))
    val solid = "y" * 120
    val h = spans(solid)
    assertEquals(h, Vector(Span(0, 50), Span(40, 90), Span(80, 120)))
  }

  test("a following chunk starts at a word inside the overlap") {
    val t = (1 to 30).map(i => f"w$i%02d").mkString(" ")
    val s = spans(t)
    wellFormed(t, s, small)
    s.tail.foreach(x => assert(t(x.start - 1) == ' ' && t(x.start) == 'w', x.toString))
  }

  test("a hard cut never splits a surrogate pair") {
    val t = "a" * 49 + "😀" + "b" * 60 // the emoji spans units 49 and 50
    val s = spans(t)
    wellFormed(t, s, small)
    s.foreach { x =>
      assert(!Character.isLowSurrogate(t(x.start)), x.toString)
      assert(!Character.isHighSurrogate(t(x.end - 1)), x.toString)
    }
  }

  test("too many chunks is refused, never truncated; empty text is refused") {
    assert(Chunking.spans("z" * 1000, small).left.exists(_.contains("more than 8 chunks")))
    assert(Chunking.spans("", small).isLeft)
  }

  test("the maxChunks bound is exact: maxChunks spans is accepted, one more is refused") {
    // each hard-cut span of "z" * n advances 40 units, so n = 50 + 40 * (k - 1) is exactly k spans
    val eight = spans("z" * 330)
    assertEquals(eight.size, 8)
    wellFormed("z" * 330, eight, small)
    assertEquals(eight.last, Span(280, 330))
    for n <- Seq(331, 370) do
      assert(Chunking.spans("z" * n, small).left.exists(_.contains("more than 8 chunks")), n)
    val one = small.copy(maxChunks = 1)
    assertEquals(Chunking.spans("x" * 50, one), Right(Vector(Span(0, 50))))
    assert(Chunking.spans("x" * 51, one).left.exists(_.contains("more than 1 chunks")))
    // chunk-v1: a whitespace-free text of 2,000 + 31 * 1,800 units is 32 spans; one unit more is 33
    assertEquals(spans("z" * 57800, ChunkPolicy.V1).size, 32)
    assert(Chunking.spans("z" * 57801, ChunkPolicy.V1).isLeft)
  }

  test("policy validation") {
    assert(small.copy(overlapChars = 25).validate.isLeft)
    assert(small.copy(minChars = 51).validate.isLeft)
    assert(small.copy(maxChunks = 0).validate.isLeft)
    assert(small.copy(version = " ").validate.isLeft)
  }

  test("chunk-v1 on realistic lengths: bounded, covering, deterministic") {
    val t = (1 to 900).map(i => s"Item $i (1)").mkString("; ") + "."
    val s = spans(t, ChunkPolicy.V1)
    wellFormed(t, s, ChunkPolicy.V1)
    assertEquals(spans(t, ChunkPolicy.V1), s)
    assert(s.size > 1 && s.size <= 32)
  }

  test("chunk IDs: one chunk keeps the core formula, several bind to the whole text") {
    val one = "one chunk."
    assertEquals(
      Chunking.chunkIds("e1", "metrics-v1", "researcher-profile", one, spans(one), small),
      Vector(Ids.chunk("e1", "metrics-v1", "researcher-profile", 0, one))
    )
    val t = "y" * 120
    val s = spans(t)
    val ids = Chunking.chunkIds("e1", "metrics-v1", "topic-institution-summary", t, s, small)
    val parent = s"metrics-v1+chunk-test+${Hashing.sha256Hex(t)}"
    assertEquals(
      ids,
      s.zipWithIndex.map((x, i) =>
        Ids.chunk("e1", parent, "topic-institution-summary", i, t.substring(x.start, x.end))
      )
    )
    assertEquals(ids.distinct.size, ids.size)
    val t2 = "y" * 119 + "z" // same first span, different document
    val ids2 =
      Chunking.chunkIds("e1", "metrics-v1", "topic-institution-summary", t2, spans(t2), small)
    assertEquals(t.substring(0, 50), t2.substring(0, 50))
    assertNotEquals(ids.head, ids2.head)
  }
