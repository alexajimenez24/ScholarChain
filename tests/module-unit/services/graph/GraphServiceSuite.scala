package scholarchain.services.graph

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.typelevel.ci.CIString
import scala.concurrent.duration.*
import scholarchain.core.*
import scholarchain.services.common.*
import scholarchain.storage.*

object GFx:
  val a = "sc:r:v1:0123456789abcdef0123"
  val tok = Some(TestJwt.token())

  def get(path: String, token: Option[String] = tok): Request[IO] =
    val r = Request[IO](Method.GET, Uri.unsafeFromString(path))
    token.fold(r)(t => r.withHeaders(Headers(Header.Raw(CIString("Authorization"), s"Bearer $t"))))
  def send(routes: HttpRoutes[IO], req: Request[IO]): (Response[IO], String) =
    routes.orNotFound.run(req).flatMap(r => r.bodyText.compile.string.map(r -> _)).unsafeRunSync()
  def err(b: String): ujson.Obj = ujson.read(b)("error").obj
  def hdr(r: Response[IO], n: String): Option[String] = r.headers.get(CIString(n)).map(_.head.value)

  /** `n` neighbour rows with unique node IDs, ordered by node ID like the template. */
  def rows(n: Int): Vector[ujson.Obj] =
    (1 to n).toVector.map(i =>
      ujson.Obj(
        "edgeType" -> "AUTHORED",
        "edgeId" -> f"e$i%03d",
        "nodeId" -> f"w$i%03d",
        "labels" -> ujson.Arr("Work")
      )
    )

  def responder(
      neighbours: Vector[ujson.Obj],
      node: Option[ujson.Obj]
  ): GraphQuery => Either[String, Vector[ujson.Obj]] =
    q =>
      if q.templateId == "node-v1" then Right(node.toVector)
      else Right(neighbours.take(q.parameters("limit").num.toInt))

  final class Rig(
      neighbours: Vector[ujson.Obj] = rows(7),
      node: Option[ujson.Obj] = Some(
        ujson.Obj(
          "id" -> a,
          "labels" -> ujson.Arr("Researcher"),
          "properties" -> ujson.Obj("displayName" -> "Ada")
        )
      ),
      limits: GraphLimits = GraphLimits(),
      policy: ResiliencePolicy = ResiliencePolicy(500.millis, 1, Duration.Zero, 3, 30.seconds),
      respond: Option[GraphQuery => Either[String, Vector[ujson.Obj]]] = None,
      evidence: EvidenceLookup = new InMemoryEvidenceLookup(Nil)
  ):
    val store = new InMemoryGraphStore(respond.getOrElse(responder(neighbours, node)))
    val sink = new CollectingMetrics
    val down = new Downstream("graph", policy)
    val reader = new GraphReader(store, new GraphTemplates(Set("2015-2026")), down, limits)
    val guard = TestJwt.guard
    val kit = new ServiceKit(
      "graph-service",
      sink,
      checks = List(ReadinessCheck("graph", down.breakerOpen.map(!_)))
    )
    val routes = GraphApp.routes(kit, guard, reader, new EvidenceRoutes(guard, evidence, down))

import GFx.*

class GraphNodeSuite extends munit.FunSuite:
  test("GET a node returns id, labels and properties with request and trace IDs") {
    val rig = new Rig
    val (r, b) = send(rig.routes, get(s"/v1/graph/nodes/$a"))
    assertEquals(r.status, Status.Ok)
    val j = ujson.read(b)
    assertEquals(
      (j("id").str, j("labels")(0).str, j("properties")("displayName").str),
      (a, "Researcher", "Ada")
    )
    assert(hdr(r, "X-Request-Id").exists(_.nonEmpty) && hdr(r, "X-Trace-Id").exists(_.nonEmpty))
    assertEquals(rig.store.executed.map(_.templateId), Vector("node-v1"))
  }
  test("an unknown node is 404; a malformed node ID is 400") {
    val (r, b) = send(new Rig(node = None).routes, get(s"/v1/graph/nodes/$a"))
    assertEquals((r.status, err(b)("code").str), (Status.NotFound, "not_found"))
    val (r2, b2) = send(new Rig().routes, get("/v1/graph/nodes/" + ("x" * 500)))
    assertEquals(
      (r2.status, err(b2)("code").str, err(b2)("details")("field").str),
      (Status.BadRequest, "invalid_request", "nodeId")
    )
  }
  test("a request without a token is 401, with the wrong scope 403, with an expired token 401") {
    val rig = new Rig
    assertEquals(send(rig.routes, get(s"/v1/graph/nodes/$a", None))._1.status, Status.Unauthorized)
    assertEquals(
      send(rig.routes, get(s"/v1/graph/nodes/$a", Some(TestJwt.token(scope = "other"))))._1.status,
      Status.Forbidden
    )
    val (r, b) =
      send(rig.routes, get(s"/v1/graph/nodes/$a", Some(TestJwt.token(exp = TestJwt.Now - 9999))))
    assertEquals(
      (r.status, err(b)("code").str, err(b)("traceId").str),
      (Status.Unauthorized, "unauthorized", hdr(r, "X-Trace-Id").get)
    )
    assertEquals(rig.store.executed, Vector.empty)
  }

class GraphNeighborsSuite extends munit.FunSuite:
  private def page(rig: Rig, q: String) = send(rig.routes, get(s"/v1/graph/nodes/$a/neighbors$q"))
  private def ids(j: ujson.Value) = j("items").arr.map(_("nodeId").str).toVector

  test("pages are cursor-linked, ordered, complete and without duplicates") {
    val rig = new Rig
    val (r1, b1) = page(rig, "?pageSize=3")
    assertEquals(r1.status, Status.Ok)
    val j1 = ujson.read(b1)
    assertEquals(ids(j1), Vector("w001", "w002", "w003"))
    val c1 = j1("nextCursor").str
    val j2 = ujson.read(page(rig, s"?pageSize=3&cursor=$c1")._2)
    assertEquals(ids(j2), Vector("w004", "w005", "w006"))
    val j3 = ujson.read(page(rig, s"?pageSize=3&cursor=${j2("nextCursor").str}")._2)
    assertEquals(ids(j3), Vector("w007"))
    assert(!j3.obj.contains("nextCursor"))
    assertEquals(j3("truncated").bool, false)
    // each query asks for one row more than the page, never more than the bound
    assertEquals(rig.store.executed.map(_.parameters("limit").num.toInt), Vector(4, 7, 10))
  }
  test("defaults: both directions, every allowed edge type, depth 1") {
    val j = ujson.read(page(new Rig, "")._2)
    assertEquals(j("direction").str, "both")
    assertEquals(j("depth").num, 1.0)
    assertEquals(j("edgeTypes").arr.map(_.str).toSet, GraphTemplates.NeighborEdgeTypes)
  }
  test("edge types and direction select the allowlisted template and parameters") {
    val rig = new Rig
    assertEquals(page(rig, "?edgeTypes=AUTHORED,HAS_TOPIC&direction=out")._1.status, Status.Ok)
    val q = rig.store.executed.head
    assertEquals(q.templateId, "neighbors-out-v1")
    assertEquals(q.parameters("edgeTypes").arr.map(_.str).toVector, Vector("AUTHORED", "HAS_TOPIC"))
  }
  test(
    "the result count is bounded: the last allowed page is flagged truncated and has no cursor"
  ) {
    val rig = new Rig(neighbours = rows(20), limits = GraphLimits(maxResults = 5))
    val j1 = ujson.read(page(rig, "?pageSize=3")._2)
    val j2 = ujson.read(page(rig, s"?pageSize=3&cursor=${j1("nextCursor").str}")._2)
    assertEquals(ids(j2), Vector("w004", "w005"))
    assertEquals(j2("truncated").bool, true)
    assert(!j2.obj.contains("nextCursor"))
    assert(rig.store.executed.forall(_.parameters("limit").num <= 5))
  }
  test("out-of-bound requests are 422; malformed ones are 400; nothing reaches the store") {
    val rig = new Rig
    def status(q: String) = page(rig, q)._1.status
    assertEquals(status("?depth=2"), Status.UnprocessableContent)
    assertEquals(status("?pageSize=51"), Status.UnprocessableContent)
    assertEquals(status("?pageSize=0"), Status.UnprocessableContent)
    assertEquals(status("?edgeTypes=COAUTHORED_WITH"), Status.UnprocessableContent)
    assertEquals(status("?edgeTypes=%20"), Status.UnprocessableContent)
    assertEquals(status("?depth=abc"), Status.BadRequest)
    assertEquals(status("?pageSize=x"), Status.BadRequest)
    assertEquals(status("?direction=sideways"), Status.BadRequest)
    assertEquals(status("?cursor=garbage"), Status.BadRequest)
    assertEquals(rig.store.executed, Vector.empty)
    val (_, b) = page(rig, "?depth=2")
    assertEquals(err(b)("code").str, "unsupported_constraint")
  }
  test("a cursor is bound to its node, direction and edge types") {
    val rig = new Rig
    val c = ujson.read(page(rig, "?pageSize=3")._2)("nextCursor").str
    assertEquals(page(rig, s"?pageSize=3&cursor=$c&direction=out")._1.status, Status.BadRequest)
    assertEquals(
      send(
        rig.routes,
        get(s"/v1/graph/nodes/sc:r:v1:aaaaaaaaaaaaaaaaaaaa/neighbors?cursor=$c")
      )._1.status,
      Status.BadRequest
    )
  }
  test("unauthorized and wrong-scope neighbour reads are refused before the store") {
    val rig = new Rig
    assertEquals(
      send(rig.routes, get(s"/v1/graph/nodes/$a/neighbors", None))._1.status,
      Status.Unauthorized
    )
    assertEquals(
      send(
        rig.routes,
        get(s"/v1/graph/nodes/$a/neighbors", Some(TestJwt.token(scope = "x")))
      )._1.status,
      Status.Forbidden
    )
    assertEquals(rig.store.executed, Vector.empty)
  }

class GraphFailureSuite extends munit.FunSuite:
  private val path = s"/v1/graph/nodes/$a/neighbors"

  test("a failing store is 502 with a typed error and no store message") {
    val rig = new Rig(respond = Some(_ => Left("neptune: password=hunter2")))
    val (r, b) = send(rig.routes, get(path))
    assertEquals((r.status, err(b)("code").str), (Status.BadGateway, "dependency_failed"))
    assert(!b.contains("hunter2"))
    assertEquals(err(b)("requestId").str, hdr(r, "X-Request-Id").get)
  }
  test("a store that hangs is 504") {
    val latch = new java.util.concurrent.CountDownLatch(1)
    val rig = new Rig(
      respond = Some(_ => { latch.await(); Right(Vector.empty) }),
      policy = ResiliencePolicy(20.millis, 1, Duration.Zero, 3, 30.seconds)
    )
    val (r, b) = send(rig.routes, get(path))
    assertEquals((r.status, err(b)("code").str), (Status.GatewayTimeout, "timeout"))
  }
  test(
    "after repeated failures the breaker answers 503 and readiness turns 503 while health stays 200"
  ) {
    val rig = new Rig(
      respond = Some(_ => Left("down")),
      policy = ResiliencePolicy(500.millis, 1, Duration.Zero, 2, 30.seconds)
    )
    assertEquals(send(rig.routes, get(path))._1.status, Status.BadGateway)
    assertEquals(send(rig.routes, get(path))._1.status, Status.BadGateway)
    val (r, b) = send(rig.routes, get(path))
    assertEquals((r.status, err(b)("code").str), (Status.ServiceUnavailable, "unavailable"))
    assertEquals(rig.store.executed.size, 2)
    val (rr, rb) = send(rig.routes, get("/readyz", None))
    assertEquals(rr.status, Status.ServiceUnavailable)
    assertEquals(err(rb)("details")("graph").str, "failing")
    assertEquals(send(rig.routes, get("/healthz", None))._1.status, Status.Ok)
  }
  test("health and readiness need no token and healthy readiness is 200") {
    val rig = new Rig
    assertEquals(send(rig.routes, get("/healthz", None))._1.status, Status.Ok)
    val (r, b) = send(rig.routes, get("/readyz", None))
    assertEquals((r.status, ujson.read(b)("status").str), (Status.Ok, "ready"))
  }
  test("metrics are EMF lines and /metrics is not exposed") {
    val rig = new Rig
    send(rig.routes, get(s"/v1/graph/nodes/$a"))
    assert(rig.sink.all.exists(l => ujson.read(l)("Operation").str == "GET /v1/graph"))
    assertEquals(send(rig.routes, get("/metrics", None))._1.status, Status.NotFound)
  }

class GraphEvidenceSuite extends munit.FunSuite:
  private val item = EvidenceItem(
    "evidence-v1",
    "ev:graph:1",
    EvidenceType.GRAPH_PATH,
    None,
    Vector(a),
    "s3://sc-graph/spark/p",
    None,
    Some(Vector(a, "sc:r:v1:bbbbbbbbbbbbbbbbbbbb")),
    1.0,
    Map.empty,
    None,
    None,
    Provenance(SourceType.SCHOLARCHAIN, "x", "2026-11-01", "run-1", "evidence-v1", None)
  )

  test("evidence by ID is served by the graph deployable with graph path and provenance") {
    val rig = new Rig(evidence = new InMemoryEvidenceLookup(Seq(item)))
    val (r, b) = send(rig.routes, get("/v1/evidence/ev:graph:1"))
    assertEquals(r.status, Status.Ok)
    val j = ujson.read(b)
    assertEquals(j("graphPath").arr.size, 2)
    assertEquals(j("provenance")("source").str, "SCHOLARCHAIN")
    assertEquals(send(rig.routes, get("/v1/evidence/missing"))._1.status, Status.NotFound)
  }
