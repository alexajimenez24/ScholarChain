package scholarchain.adversarial.rag

import scala.collection.mutable

import scholarchain.adversarial.rag.BenchmarkStore.{Data, Edge, Metric, tokens}

/** Benchmark question set v1: structure and expected answers (Phase 09 step 3, test-engineer).
  *
  * Spec: "At least 30 benchmark questions covering coauthor breadth, maximum team size, direct
  * collaboration, shortest paths, components, communities, bridge researchers, temporal change,
  * topics, institutions, and evidence" (`docs/spec/assignment.md` line 465); "Benchmark questions
  * and relevance labels are versioned" (line 2137); Phase 09 addendum: expected answers are
  * computed directly from the deterministic stores, with code that does not call the workflow.
  *
  * The frozen file `resources/benchmark/questions-v1.json` is written by
  * `tests/benchmark/questions_v1.py`. This suite recomputes every expected answer with a second,
  * independent implementation ([[BenchmarkOracle]]) and requires exact agreement, so neither
  * implementation alone defines the answer key.
  */
class BenchmarkQuestionSetSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  override def beforeAll(): Unit = BenchmarkStore.HeavyLoad.acquire()

  override def afterAll(): Unit =
    oracleCache = None
    BenchmarkStore.release()
    BenchmarkStore.HeavyLoad.release()

  private val Categories = Vector(
    "coauthor-breadth",
    "maximum-team-size",
    "direct-collaboration",
    "shortest-path",
    "components",
    "communities",
    "bridge-researchers",
    "temporal-change",
    "topics",
    "institutions",
    "evidence"
  )

  private lazy val file = BenchmarkQuestions.json
  private lazy val qs = BenchmarkQuestions.questions

  test("the set is versioned and pinned to the Phase 08 runs") {
    assertEquals(file("version").str, "benchmark-questions-v1")
    assertEquals(file("graphRunId").str, "run-ccf619c509611f06")
    assertEquals(file("erRunId").str, "run-cb5a0421c406737d")
    assertEquals(file("windows").arr.map(_.str).toVector, Vector("2010-2014", "2015-2026"))
  }

  test("at least 30 questions, every assignment category covered, ids unique and sequential") {
    assert(qs.size >= 30, s"${qs.size} questions")
    assertEquals(qs.map(_("id").str), qs.indices.map(i => f"bq-v1-${i + 1}%02d").toVector)
    val byCat = qs.groupBy(_("category").str).view.mapValues(_.size).toMap
    assertEquals(byCat.keySet, Categories.toSet)
    Categories.foreach(c => assert(byCat(c) >= 1, c))
  }

  test("every question names its window, operation, method and expected answer") {
    val windows = file("windows").arr.map(_.str).toSet
    qs.foreach { q =>
      val id = q("id").str
      assert(windows(q("window").str), id)
      assert(q("question").str.contains(q("window").str), s"$id: the window is not in the text")
      assert(q("method").str.length > 20, id)
      assert(q("operation").str.matches("[A-Z_]+"), id)
      assert(q("expected").obj.nonEmpty, id)
    }
  }

  test("every researcher name is quoted in the question (the planner reads explicit forms)") {
    qs.foreach { q =>
      q("constraints").obj
        .get("researcherNames")
        .foreach(_.arr.foreach { n =>
          assert(q("question").str.contains("\"" + n.str + "\""), s"${q("id").str}: ${n.str}")
        })
    }
  }

  test("community questions expect the community size as missing evidence, never a number") {
    val community = qs.filter(_("category").str == "communities")
    assert(community.nonEmpty)
    community.foreach(q => assertEquals(q("expected")("communitySize").str, "MISSING_EVIDENCE"))
  }

  private def data: Data =
    assume(BenchmarkStore.present, s"run data absent at ${BenchmarkStore.runDir}")
    BenchmarkStore.data

  test("the store reader matches the rows the generator read") {
    val d = data
    file("rowsRead").obj.foreach((k, v) => assertEquals(d.rowsRead(k), v.num.toInt, k))
  }

  test("the ambiguous-name gate fixture has several nodes under one display name") {
    val d = data
    val g = file("gate")
    val name = g("ambiguousName").str
    val exact = d.researchers.collect { case (id, p) if p.name == name => id }.toVector.sorted
    assertEquals(exact, g("exactDisplayNameNodes").arr.map(_.str).toVector)
    assert(exact.size >= 2)
    val key = tokens(name).sorted
    assertEquals(
      d.researchers.values.count(p => tokens(p.name).sorted == key),
      g("sortedTokenKeyNodes").num.toInt
    )
  }

  @volatile private var oracleCache: Option[BenchmarkOracle] = None

  private def oracle: BenchmarkOracle = synchronized {
    if oracleCache.isEmpty then oracleCache = Some(BenchmarkOracle(data))
    oracleCache.get
  }

  BenchmarkQuestions.questions.foreach { q =>
    test(
      s"${q("id").str} (${q("category").str}): the expected answer equals an independent recomputation"
    ) {
      val got = canonical(oracle.expected(q))
      val want = canonical(q("expected"))
      assertEquals(ujson.write(got), ujson.write(want), q("question").str)
    }
  }

/** Second implementation of the answer key, from the store reader only. */
final class BenchmarkOracle(d: Data):

  private def fail(msg: String): Nothing = throw new AssertionError(msg)

  private lazy val keyCounts: (Map[Vector[String], Int], Map[Set[String], Int]) =
    val toks = d.researchers.values.map(p => tokens(p.name)).toVector
    (
      toks.groupMapReduce(_.sorted)(_ => 1)(_ + _),
      toks.filter(_.nonEmpty).groupMapReduce(u => Set(u.head, u.last))(_ => 1)(_ + _)
    )

  def resolve(name: String): String =
    val hits = d.researchers.collect { case (id, p) if p.name == name => id }.toVector
    if hits.size != 1 then fail(s"$name: ${hits.size} nodes")
    val t = tokens(name)
    val sameKey = keyCounts._1.getOrElse(t.sorted, 0)
    val sameEnds = keyCounts._2.getOrElse(Set(t.head, t.last), 0)
    if sameKey != 1 || sameEnds != 1 then fail(s"$name is not unique ($sameKey, $sameEnds)")
    val p = d.researchers(hits.head)
    if p.conflict || p.unresolved || p.confidence < 0.9 then fail(s"$name has a weak identity")
    hits.head

  private def row(w: String, id: String): Metric =
    d.metrics(w).getOrElse(id, fail(s"$id has no metrics row in $w"))

  private def strs(xs: Iterable[String]): ujson.Arr = ujson.Arr.from(xs.map(ujson.Str(_)))

  private def ranking(w: String, ids: Iterable[String], field: String): ujson.Obj =
    val value: Metric => Double = field match
      case "distinctCoauthorCount" => _.distinctCoauthorCount.toDouble
      case "bridgeScore"           => _.bridgeScore
      case other                   => fail(other)
    val m = d.metrics(w)
    val ordered = ids.toVector.sortBy(i => (-value(m(i)), i))
    if ordered.size < 2 || value(m(ordered(0))) == value(m(ordered(1))) then
      fail(s"tie in $w $field")
    ujson.Obj(
      "metric" -> field,
      "candidates" -> ordered.size,
      "top" -> ordered(0),
      "topName" -> d.researchers(ordered(0)).name,
      "topValue" -> value(m(ordered(0))),
      "runnerUp" -> ordered(1),
      "runnerUpValue" -> value(m(ordered(1)))
    )

  /** Distances and shortest-path counts from `s` over the window's coauthor edges. */
  private def bfs(w: String, s: String): (Map[String, Int], Map[String, Long]) =
    val adj = d.adjacency(w)
    val dist = mutable.HashMap(s -> 0)
    val count = mutable.HashMap(s -> 1L)
    val queue = mutable.Queue(s)
    while queue.nonEmpty do
      val u = queue.dequeue()
      adj.getOrElse(u, Map.empty).keys.foreach { v =>
        if !dist.contains(v) then
          dist(v) = dist(u) + 1
          count(v) = 0L
          queue.enqueue(v)
        if dist(v) == dist(u) + 1 then count(v) = count(v) + count(u)
      }
    (dist.toMap, count.toMap)

  private def shortest(w: String, s: String, t: String): ujson.Obj =
    val (dist, count) = bfs(w, s)
    if !dist.contains(t) then ujson.Obj("found" -> false, "reachableFromSource" -> dist.size)
    else
      if count(t) != 1L then fail(s"$s->$t has ${count(t)} shortest paths")
      val adj = d.adjacency(w)
      val back = Iterator
        .iterate(t)(v => adj(v).keys.find(u => dist.get(u).contains(dist(v) - 1)).get)
        .takeWhile(_ != s)
        .toVector
      val path = (back :+ s).reverse
      val hops = path.zip(path.tail).map { (a, b) =>
        val e = adj(a)(b)
        ujson.Obj(
          "from" -> a,
          "to" -> b,
          "edgeId" -> e.edgeId,
          "jointPublicationCount" -> e.joint,
          "supportingWorkIds" -> strs(e.supporting.sorted)
        )
      }
      ujson.Obj(
        "found" -> true,
        "hops" -> (path.size - 1),
        "path" -> strs(path),
        "pathNames" -> strs(path.map(d.researchers(_).name)),
        "hopEvidence" -> ujson.Arr.from(hops)
      )

  private def pair(w: String, a: String, b: String): ujson.Obj =
    val base = ujson.Obj("researcherA" -> a, "researcherB" -> b)
    d.adjacency(w).getOrElse(a, Map.empty).get(b) match
      case None          => base("found") = false
      case Some(e: Edge) =>
        val ws = e.supporting.sorted
        base("found") = true
        base("edgeId") = e.edgeId
        base("jointPublicationCount") = e.joint
        base("firstCollaborationYear") = e.first
        base("lastCollaborationYear") = e.last
        base("supportingWorkIds") = strs(ws)
        base("titles") =
          ujson.Obj.from(ws.filter(d.works.contains).map(x => x -> ujson.Str(d.works(x).title)))
    base

  private def uniqueName(name: String, all: Iterable[String]): Unit =
    val t = tokens(name).toSet
    val hits = all.filter(n => t.subsetOf(tokens(n).toSet)).toVector
    if hits != Vector(name) then fail(s"$name is token-contained in ${hits.size} names")

  private val bounds = Map("2010-2014" -> (2010, 2014), "2015-2026" -> (2015, 2026))

  def expected(q: ujson.Value): ujson.Value =
    val w = q("window").str
    val c = q("constraints").obj
    def names = c("researcherNames").arr.map(_.str).toVector
    def one = resolve(names.head)
    q("category").str match
      case "coauthor-breadth" if c.contains("researcherNames") =>
        val m = row(w, one)
        ujson.Obj(
          "researcherId" -> one,
          "distinctCoauthorCount" -> m.distinctCoauthorCount,
          "weightedDegree" -> m.weightedDegree,
          "publicationCount" -> m.publicationCount
        )
      case "coauthor-breadth" | "bridge-researchers" if !c.contains("researcherNames") =>
        ranking(w, d.metrics(w).keys, c("metric").str)
      case "maximum-team-size" =>
        val m = row(w, one)
        ujson.Obj(
          "researcherId" -> one,
          "maximumCoauthorsOnOnePaper" -> m.maximumCoauthorsOnOnePaper,
          "maximumTeamWorkIds" -> strs(m.maximumTeamWorkIds.sorted),
          "averageCoauthorsPerPaper" -> m.averageCoauthorsPerPaper
        )
      case "direct-collaboration" => pair(w, resolve(names(0)), resolve(names(1)))
      case "shortest-path"        => shortest(w, resolve(names(0)), resolve(names(1)))
      case "components"           =>
        val m = row(w, one)
        val reach = bfs(w, one)._1.size
        if reach != m.componentSize then
          fail(s"BFS reaches $reach, componentSize ${m.componentSize}")
        ujson.Obj(
          "researcherId" -> one,
          "componentId" -> m.componentId,
          "componentSize" -> m.componentSize
        )
      case "communities" =>
        val m = row(w, one)
        ujson.Obj(
          "researcherId" -> one,
          "communityId" -> m.communityId,
          "crossCommunityDegree" -> m.crossCommunityDegree,
          "communitiesConnected" -> m.communitiesConnected,
          "communitySize" -> "MISSING_EVIDENCE",
          "oracleMemberCountNotExpectedFromWorkflow" -> d
            .metrics(w)
            .values
            .count(_.communityId == m.communityId)
        )
      case "bridge-researchers" =>
        val m = row(w, one)
        ujson.Obj(
          "researcherId" -> one,
          "bridgeScore" -> m.bridgeScore,
          "crossCommunityDegree" -> m.crossCommunityDegree,
          "communitiesConnected" -> m.communitiesConnected,
          "distinctCoauthorCount" -> m.distinctCoauthorCount
        )
      case "temporal-change" =>
        val Vector(w1, w2) = c("windows").arr.map(_.str).toVector: @unchecked
        val (a, b) = (row(w1, one), row(w2, one))
        def part(m: Metric) = ujson.Obj(
          "distinctCoauthorCount" -> m.distinctCoauthorCount,
          "publicationCount" -> m.publicationCount,
          "maximumCoauthorsOnOnePaper" -> m.maximumCoauthorsOnOnePaper
        )
        ujson.Obj(
          "researcherId" -> one,
          "byWindow" -> ujson.Obj(w1 -> part(a), w2 -> part(b)),
          "distinctCoauthorChange" -> (b.distinctCoauthorCount - a.distinctCoauthorCount)
        )
      case "topics" =>
        val name = c("topic").str
        uniqueName(name, d.topics.values)
        val tid = d.topics.collectFirst { case (id, n) if n == name => id }.get
        val all = d.topicWorks.getOrElse(tid, Set.empty)
        if all.size > 30 then fail(s"$name has ${all.size} works")
        val (lo, hi) = bounds(w)
        val inWindow = all.filter(x => d.works(x).year >= lo && d.works(x).year <= hi)
        val ids =
          inWindow.flatMap(x => d.authors.getOrElse(x, Set.empty)).filter(d.metrics(w).contains)
        if ids.size > 200 then fail(s"$name has ${ids.size} researchers")
        val out = ranking(w, ids, "distinctCoauthorCount")
        out("topicId") = tid
        out("topicWorksTotal") = all.size
        out("topicWorksInWindow") = inWindow.size
        out
      case "institutions" =>
        val name = c("institution").str
        uniqueName(name, d.institutions.values)
        val iid = d.institutions.collectFirst { case (id, n) if n == name => id }.get
        val members = d.affiliated.getOrElse(iid, Set.empty)
        if members.size > 100 then fail(s"$name has ${members.size} affiliated researchers")
        val out = ranking(w, members.filter(d.metrics(w).contains), "distinctCoauthorCount")
        out("institutionId") = iid
        out("affiliatedTotal") = members.size
        out
      case "evidence" if q("operation").str == "SHORTEST_PATH" =>
        shortest(w, resolve(names(0)), resolve(names(1)))
      case "evidence" =>
        val out = pair(w, resolve(names(0)), resolve(names(1)))
        if !out("found").bool then fail("an evidence question needs an edge")
        out
      case other => fail(s"unknown category $other")
