package scholarchain.spark

import scholarchain.core.{ConfigLoader, Ids}

/** Driver arguments, upstream location through the Phase 04 and Phase 05 run manifests, and the
  * Spark run manifest. No Spark session: the manifests are in-memory files.
  *
  * Fixture: a Hadoop manifest `{"pipeline":"hadoop-v2","normalization":"norm-v1","snapshot":
  * "2026-11-22"}` named by its own core `Ids.run` hash (as the Hadoop driver names it), its summary
  * with `stages.J07.output`, and an er-v1 manifest whose `upstreamRunId` is that Hadoop run, with
  * its summary naming the decisions folder.
  */
class RunManifestSuite extends munit.FunSuite:
  private val cfg = ConfigLoader.load(Map.empty).fold(e => throw new AssertionError(e), identity)

  private val hText =
    """{"pipeline":"hadoop-v2","normalization":"norm-v1","snapshot":"2026-11-22","maxTeam":300}"""
  private val hId = Ids.run(hText)
  private def erText(upstream: String, policy: String = "er-v1") =
    ujson
      .Obj(
        "stage" -> "entity-resolution",
        "upstreamRunId" -> upstream,
        "snapshot" -> "2026-11-22",
        "policyVersion" -> policy,
        "policySha256" -> "82ce9ae5"
      )
      .render()

  private def files(er: String, hadoop: String = hText): Map[String, String] =
    val eId = Ids.run(er)
    Map(
      s"/h/run-manifests/$hId.json" -> hadoop,
      s"/h/run-manifests/$hId-summary.json" ->
        ujson
          .Obj("runId" -> hId, "stages" -> ujson.Obj("J07" -> ujson.Obj("output" -> "/h/export")))
          .render(),
      s"/e/run-manifests/$eId.json" -> er,
      s"/e/run-manifests/$eId-summary.json" -> ujson
        .Obj("runId" -> eId, "decisions" -> "/e/dec")
        .render()
    )

  private def locate(fs: Map[String, String], er: String) =
    Upstream.locate(
      s"/h/run-manifests/$hId.json",
      s"/e/run-manifests/${Ids.run(er)}.json",
      "er-v1",
      p => fs.get(p).toRight(s"cannot read $p")
    )

  test("arguments: strict names, every required option, no repeated or valueless option") {
    val ok = Seq("--hadoop-manifest", "h.json", "--er-manifest", "e.json", "--output", "out")
    assertEquals(SparkArgs.parse(ok), Right(SparkArgs("h.json", "e.json", "out", None)))
    assertEquals(
      SparkArgs.parse(ok ++ Seq("--checkpoint-dir", "ck")).map(_.checkpointDir),
      Right(Some("ck"))
    )
    assertEquals(SparkArgs.parse(ok.take(4)), Left("--output is required"))
    assertEquals(SparkArgs.parse(ok ++ Seq("--seed", "1")), Left("unknown option --seed"))
    assertEquals(SparkArgs.parse(ok ++ Seq("--output", "x")), Left("--output is repeated"))
    assertEquals(
      SparkArgs.parse(Seq("--output", "--er-manifest", "e")),
      Left("--output has no value")
    )
    assertEquals(SparkArgs.parse(Seq("h.json")), Left("expected an option name, got 'h.json'"))
  }

  test("checkpoint directory: required for every non-local master") {
    val ok = Seq("--hadoop-manifest", "h.json", "--er-manifest", "e.json", "--output", "out")
    val without = SparkArgs.parse(ok).fold(e => fail(e), identity)
    val withDir =
      SparkArgs.parse(ok ++ Seq("--checkpoint-dir", "s3://b/ck")).fold(e => fail(e), identity)
    for m <- Seq("local", "local[2]", "local[*]") do
      assertEquals(SparkArgs.checkpointRequirement(m, without), Right(()), m)
    for m <- Seq("yarn", "spark://host:7077", "local-cluster[2,1,1024]") do
      assertEquals(
        SparkArgs.checkpointRequirement(m, without),
        Left(s"--checkpoint-dir is required when the master is not local (master '$m')")
      )
      assertEquals(SparkArgs.checkpointRequirement(m, withDir), Right(()), m)
    assert(SparkArgs.Usage.contains("--checkpoint-dir is required unless the master is local"))
  }

  test("upstream: located through both manifests and cross-checked") {
    val er = erText(hId)
    val up = locate(files(er), er).fold(e => fail(e), identity)
    assertEquals(
      (up.hadoopRunId, up.exportDir, up.erRunId, up.decisionsDir, up.snapshot),
      (hId, "/h/export", Ids.run(er), "/e/dec", "2026-11-22")
    )
    assertEquals(
      (up.hadoopPipeline, up.normalization, up.erPolicyVersion),
      ("hadoop-v2", "norm-v1", "er-v1")
    )
  }

  test("upstream: refused when the runs do not belong together or a manifest was edited") {
    // An er run made from another Hadoop run.
    val other = erText("run-0000000000000000")
    assert(locate(files(other), other).left.exists(_.contains("was made from hadoop run")))
    // An er run made under another policy than the configured er-v1.
    val v2 = erText(hId, "er-v2")
    assert(locate(files(v2), v2).left.exists(_.contains("is not the configured 'er-v1'")))
    // A Hadoop manifest edited after the run: it no longer hashes to its file name.
    val er = erText(hId)
    val edited = files(er, hText.replace("300", "301"))
    assert(locate(edited, er).left.exists(_.contains("hashes to")))
    // Not a manifest name.
    assert(Upstream.runIdOf("/h/run-manifests/latest.json").isLeft)
  }

  test("run manifest: the run ID hashes inputs, config and code version, and nothing else") {
    val er = erText(hId)
    val up = locate(files(er), er).fold(e => fail(e), identity)
    val m = SparkRunManifest.build(up, cfg)
    val id = SparkRunManifest.runId(m)
    assert(Ids.isRunId(id))
    // Same inputs and config: same ID (no timestamp, no output path in the manifest).
    assertEquals(SparkRunManifest.runId(SparkRunManifest.build(up, cfg)), id)
    assertEquals(m("inputs")("hadoop")("runId").str, hId)
    assertEquals(m("inputs")("entityResolution")("runId").str, Ids.run(er))
    assertEquals(upickle.default.read[scholarchain.core.GraphConfig](m("graph")), cfg.graph)
    assertEquals(m("logic").str, SparkRunManifest.LogicVersion)
    // A changed parameter (the betweenness seed) or another upstream run gives another ID.
    val seeded = cfg.copy(graph = cfg.graph.copy(betweennessSeed = cfg.graph.betweennessSeed + 1))
    assertNotEquals(SparkRunManifest.runId(SparkRunManifest.build(up, seeded)), id)
    assertNotEquals(
      SparkRunManifest.runId(
        SparkRunManifest.build(up.copy(erRunId = "run-1111111111111111"), cfg)
      ),
      id
    )
  }

  test("invariant collection: every invariant_ count with its path, windows named") {
    val report = ujson.Obj(
      "job1" -> ujson.Obj("invariant_a" -> 0, "other" -> 5),
      "windows" -> ujson.Arr(ujson.Obj("window" -> "2000-2009", "invariant_b" -> 2))
    )
    assertEquals(
      SparkPipeline.invariants(report).toSet,
      Set("job1.invariant_a" -> 0L, "windows[2000-2009].invariant_b" -> 2L)
    )
  }
