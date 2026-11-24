package scholarchain.core

class ConfigSuite extends munit.FunSuite:
  private def bundledText: String =
    val in = getClass.getClassLoader.getResourceAsStream(ConfigLoader.DefaultResource)
    try new String(in.readAllBytes(), "UTF-8")
    finally in.close()

  private def load(env: Map[String, String]) = ConfigLoader.load(env).fold(e => fail(e), identity)

  private val awsEnv = Map(
    "SCHOLARCHAIN_MODE" -> "aws",
    "RAW_BUCKET" -> "raw-x",
    "CURATED_BUCKET" -> "cur-x",
    "GRAPH_BUCKET" -> "graph-x",
    "RAG_BUCKET" -> "rag-x",
    "NEPTUNE_ENDPOINT" -> "neptune.invalid",
    "VECTOR_BUCKET" -> "vec-x",
    "DDB_RUN_LEDGER" -> "t1",
    "DDB_ENTITY_LINK_CACHE" -> "t2",
    "DDB_QUERY_SESSION" -> "t3",
    "DDB_EVIDENCE_BUNDLE" -> "t4",
    "DDB_ARTIFACT_REGISTRY" -> "t5",
    "DDB_RESEARCHER_METRIC" -> "t6",
    "EMBEDDING_MODEL_ID" -> "embed-x",
    "EMBEDDING_QUEUE_URL" -> "https://queue.invalid/q",
    "COGNITO_ISSUER" -> "https://issuer.invalid/pool",
    "COGNITO_AUDIENCE" -> "123456789012",
    "COGNITO_JWKS_URL" -> "https://issuer.invalid/pool/jwks.json"
  )

  test("the bundled config loads with only file defaults") {
    val cfg = load(Map.empty)
    assertEquals(cfg.configVersion, "config-v1")
    assertEquals(cfg.application.region, "us-east-2")
    assertEquals(cfg.application.mode, "local")
    assertEquals(cfg.neptune.port, 8182)
    assertEquals(cfg.models.llmEnabled, false)
    assertEquals(cfg.vectors.indexes("researchers"), "researcher-profile-v1")
    assertEquals(cfg.graph.windows.head, TimeWindow(1950, 2026))
    assertEquals(cfg.services.query.serviceName, "query-service")
    assertEquals(cfg.services.worker.serviceName, "embedding-worker")
    assertEquals(cfg.services.auth.requestTimeoutMs, 3000)
    assertEquals(cfg.storage.rawBucket, "", "no bucket is hard-coded")
  }
  test("the file header states the JSON-compatible YAML restriction") {
    val header = bundledText.linesIterator.takeWhile(_.startsWith("#")).mkString("\n")
    assert(header.contains("JSON-compatible subset of YAML 1.2"))
    assert(header.contains("uPickle"))
  }
  test("the file holds no endpoint, ARN, account, bucket or env-file name") {
    val text = ConfigLoader.stripComments(bundledText)
    List("s3://", "arn:", "amazonaws", "http://", "https://", ".tfvars").foreach(bad =>
      assert(!text.contains(bad), s"config contains '$bad'")
    )
    assert(!"\\$\\{[^}]*\\.env[^}]*\\}".r.findFirstIn(text).isDefined)
    assert(!"\\d{12}".r.findFirstIn(text).isDefined, "no 12-digit account ID")
  }
  test("environment placeholders and explicit overrides take precedence, in that order") {
    val env = Map(
      "NEPTUNE_PORT" -> "9999",
      "AWS_REGION" -> "us-west-2",
      "SCHOLARCHAIN__GRAPH__KCOREMAXK" -> "7",
      "QUERY_SERVICE_PORT" -> "9001",
      "SCHOLARCHAIN__SERVICES__QUERY__PORT" -> "9002"
    )
    val cfg = load(env)
    assertEquals(cfg.neptune.port, 9999, "placeholder beats file default")
    assertEquals(cfg.application.region, "us-west-2")
    assertEquals(cfg.graph.kcoreMaxK, 7, "override beats file literal")
    assertEquals(cfg.services.query.port, 9002, "override beats placeholder")
    assertEquals(cfg.services.graph.port, 8080, "file default when nothing else is set")
  }
  test("shipped componentMaxIterations leaves margin over the observed 25k-run diameter") {
    val cfg = load(Map.empty)
    assertEquals(cfg.graph.componentMaxIterations, 100)
    assert(cfg.graph.componentMaxIterations > 39 + 2, "diameter 39 plus a quiet round")
  }
  test("overrides can replace the time windows and keep the type of the replaced value") {
    val cfg = load(
      Map("SCHOLARCHAIN__GRAPH__WINDOWS" -> """[{"startYear":2000,"endYear":2001}]""")
    )
    assertEquals(cfg.graph.windows, Vector(TimeWindow(2000, 2001)))
    assert(
      ConfigLoader
        .load(Map("SCHOLARCHAIN__GRAPH__KCOREMAXK" -> "abc"))
        .left
        .exists(_.contains("not a number"))
    )
  }
  test("an override naming an unknown key fails loudly") {
    assert(
      ConfigLoader
        .load(Map("SCHOLARCHAIN__GRAPH__NOPE" -> "1"))
        .left
        .exists(_.contains("unknown key"))
    )
  }
  test("required placeholders without a value are listed") {
    val text = """{"configVersion":"config-v1","x":"${REQUIRED_THING}"}"""
    assert(ConfigLoader.parse(text, Map.empty).left.exists(_.contains("REQUIRED_THING")))
  }
  test("comments are stripped, other YAML is refused") {
    val ok =
      "# c\n" + bundledText.replace("{\n  \"configVersion\"", "{\n  # inner\n  \"configVersion\"")
    assert(ConfigLoader.parse(ok, Map.empty).isRight)
    assert(
      ConfigLoader
        .parse("configVersion: config-v1\napplication:\n  mode: local\n", Map.empty)
        .left
        .exists(_.contains("JSON-compatible"))
    )
  }
  test("a numeric-looking ID stays a string; a typed placeholder becomes a number") {
    val cfg = load(awsEnv ++ Map("WORKER_BATCH_SIZE" -> "5"))
    assertEquals(cfg.services.auth.audience, "123456789012")
    assertEquals(cfg.services.worker.batchSize, 5)
    assertEquals(cfg.application.mode, "aws")
  }
  test("a bad number in a typed placeholder fails the schema, not silently") {
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "eighty")).isLeft)
  }
  test("aws mode requires every environment-supplied value") {
    assertEquals(
      ConfigLoader.load(awsEnv).map(_.services.auth.issuer),
      Right("https://issuer.invalid/pool")
    )
    val missing = ConfigLoader.load(awsEnv - "COGNITO_ISSUER" - "RAW_BUCKET")
    assert(
      missing.left.exists(m =>
        m.contains("services.auth.issuer") && m.contains("storage.rawBucket")
      )
    )
  }
  test("inconsistent entity-resolution thresholds are rejected") {
    val r = ConfigLoader.load(Map("SCHOLARCHAIN__ENTITYRESOLUTION__REJECTTHRESHOLD" -> "0.95"))
    assert(r.left.exists(_.contains("thresholds")))
  }
  test("an inverted or empty graph window is rejected") {
    val bad = ConfigLoader.load(
      Map("SCHOLARCHAIN__GRAPH__WINDOWS" -> """[{"startYear":2020,"endYear":2010}]""")
    )
    assert(bad.left.exists(_.contains("exceeds")))
    assert(
      ConfigLoader
        .load(Map("SCHOLARCHAIN__GRAPH__WINDOWS" -> "[]"))
        .left
        .exists(_.contains("empty"))
    )
  }
  test("unsupported config versions, ports and timeouts are rejected") {
    assert(
      ConfigLoader
        .load(Map("SCHOLARCHAIN__CONFIGVERSION" -> "config-v9"))
        .left
        .exists(_.contains("unsupported config version"))
    )
    assert(ConfigLoader.load(Map("GRAPH_SERVICE_PORT" -> "70000")).left.exists(_.contains("ports")))
    assert(
      ConfigLoader.load(Map("COGNITO_JWKS_TIMEOUT_MS" -> "0")).left.exists(_.contains("timeouts"))
    )
  }
  test("SCHOLARCHAIN_CONFIG_FILE names another versioned file") {
    val f = java.nio.file.Files.createTempFile("sc-config", ".yaml")
    try
      java.nio.file.Files
        .writeString(f, bundledText.replace("\"queryTimeoutMs\": 5000", "\"queryTimeoutMs\": 1234"))
      val cfg = load(Map("SCHOLARCHAIN_CONFIG_FILE" -> f.toString))
      assertEquals(cfg.neptune.queryTimeoutMs, 1234)
      assert(ConfigLoader.load(Map("SCHOLARCHAIN_CONFIG_FILE" -> "/nonexistent/x.yaml")).isLeft)
    finally java.nio.file.Files.deleteIfExists(f)
  }

  private def err(env: Map[String, String]): String = ConfigLoader.load(env).left.getOrElse("")

  test("NaN, infinite and fractional numbers are rejected for integer fields") {
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "NaN")).isLeft)
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "Infinity")).isLeft)
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "8182.9")).isLeft)
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "1e30")).isLeft)
    assert(err(Map("SCHOLARCHAIN__NEPTUNE__PORT" -> "8182.9")).contains("whole number"))
    assert(err(Map("SCHOLARCHAIN__GRAPH__KCOREMAXK" -> "NaN")).contains("not a number"))
    assert(err(Map("SCHOLARCHAIN__ENTITYRESOLUTION__ACCEPTTHRESHOLD" -> "NaN")).nonEmpty)
    assert(
      ConfigLoader.load(Map("SCHOLARCHAIN__ENTITYRESOLUTION__ACCEPTTHRESHOLD" -> "0.95")).isRight
    )
  }
  test("every numeric field has a range check") {
    assert(err(Map("NEPTUNE_PORT" -> "0")).contains("neptune.port"))
    assert(err(Map("NEPTUNE_PORT" -> "70000")).contains("neptune.port"))
    assert(err(Map("SCHOLARCHAIN__NEPTUNE__QUERYTIMEOUTMS" -> "0")).contains("queryTimeoutMs"))
    assert(err(Map("SCHOLARCHAIN__GRAPH__KCOREMAXK" -> "-5")).contains("kcoreMaxK"))
    assert(err(Map("SCHOLARCHAIN__GRAPH__BETWEENNESSSAMPLESOURCES" -> "0")).contains("betweenness"))
    assert(err(Map("SCHOLARCHAIN__GRAPH__COMMUNITYITERATIONS" -> "0")).contains("communityIter"))
    assert(err(Map("SCHOLARCHAIN__GRAPH__COMPONENTMAXITERATIONS" -> "0")).contains("componentMax"))
    assert(
      err(Map("SCHOLARCHAIN__GRAPH__REPRESENTATIVEWORKLIMIT" -> "0")).contains("representative")
    )
    assert(
      err(Map("SCHOLARCHAIN__OPERATIONS__MAXQUESTIONCHARS" -> "0")).contains("maxQuestionChars")
    )
    assert(err(Map("SCHOLARCHAIN__OPERATIONS__MAXTOKENSPERQUERY" -> "-1")).contains("maxTokens"))
    assert(err(Map("SCHOLARCHAIN__OPERATIONS__MAXWORKFLOWRETRIES" -> "-1")).contains("Retries"))
    assert(err(Map("SCHOLARCHAIN__OPERATIONS__SYNCHRONOUSDEADLINEMS" -> "0")).contains("Deadline"))
    assert(err(Map("SCHOLARCHAIN__OPERATIONS__MAXCONCURRENTLLMCALLS" -> "0")).contains("Llm"))
    assert(err(Map("EMBEDDING_DIMENSION" -> "0")).contains("dimension"))
    assert(err(Map("ER_MAX_LLM_CALLS" -> "-1")).contains("maxLlmCallsPerRun"))
    assert(err(Map("SCHOLARCHAIN__RETRIEVAL__GRAPHLIMIT" -> "0")).contains("graphLimit"))
    assert(err(Map("SCHOLARCHAIN__RETRIEVAL__CONTEXTTOKENBUDGET" -> "0")).contains("contextToken"))
  }
  test("integer values never saturate or lose digits silently") {
    // C5, C7, C8: values the schema reader would clamp to Int.MaxValue or round to 2^53.
    assert(err(Map("SCHOLARCHAIN__NEPTUNE__QUERYTIMEOUTMS" -> "1e10")).contains("clamped"))
    assert(err(Map("QUERY_REQUEST_TIMEOUT_MS" -> "9999999999")).contains("clamped"))
    assert(err(Map("SCHOLARCHAIN__GRAPH__KCOREMAXK" -> "3000000000")).contains("clamped"))
    assert(
      ConfigLoader
        .load(Map("SCHOLARCHAIN__GRAPH__BETWEENNESSSEED" -> "9007199254740993"))
        .isLeft
    )
    assert(
      ConfigLoader
        .load(Map("SCHOLARCHAIN__GRAPH__BETWEENNESSSEED" -> "9007199254740992"))
        .isLeft,
      "2^53 itself is excluded"
    )
    assertEquals(
      load(Map("SCHOLARCHAIN__GRAPH__BETWEENNESSSEED" -> "9007199254740991")).graph.betweennessSeed,
      9007199254740991L
    )
    assert(ConfigLoader.load(Map.empty).isRight, "the bundled config still loads")
  }
  test("only plain decimal number text is a number in a placeholder or an override") {
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "8182d")).isLeft)
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "0x1FF6p0")).isLeft)
    assert(ConfigLoader.load(Map("NEPTUNE_PORT" -> "8182f")).isLeft)
    assert(err(Map("SCHOLARCHAIN__NEPTUNE__PORT" -> "8182d")).contains("not a number"))
    assert(err(Map("SCHOLARCHAIN__NEPTUNE__PORT" -> "0x1FF6")).contains("not a number"))
    assertEquals(load(Map("NEPTUNE_PORT" -> "8183")).neptune.port, 8183)
  }
  test("an infinite-valued override is caught by the finiteness rule itself") {
    // 1e999 is plain decimal text and parses to Infinity, so only the finiteness rule rejects it.
    val e = err(Map("SCHOLARCHAIN__ENTITYRESOLUTION__ACCEPTTHRESHOLD" -> "1e999"))
    assert(e.contains("not a number") && e.contains("(finite)"), e)
  }
  test("each remaining range and name rule rejects its own failing input") {
    assert(err(Map("SCHOLARCHAIN__ENTITYRESOLUTION__MAXBLOCKSIZE" -> "0")).contains("maxBlockSize"))
    assert(
      err(Map("SCHOLARCHAIN__RETRIEVAL__VECTORLIMIT" -> "0")).contains("retrieval.vectorLimit")
    )
    assert(
      err(Map("SCHOLARCHAIN__ENTITYRESOLUTION__POLICYVERSION" -> " "))
        .contains("entityResolution.policyVersion is empty")
    )
    assert(
      err(Map("SCHOLARCHAIN__GRAPH__POLICYVERSION" -> " ")).contains("graph.policyVersion is empty")
    )
    assert(
      err(Map("SCHOLARCHAIN__GRAPH__METRICVERSION" -> " ")).contains("graph.metricVersion is empty")
    )
    assert(
      err(Map("SCHOLARCHAIN__RETRIEVAL__FUSIONPOLICYVERSION" -> " "))
        .contains("retrieval.fusionPolicyVersion is empty")
    )
    assert(err(Map("SCHOLARCHAIN__VECTORS__INDEXES" -> "{}")).contains("vectors.indexes"))
  }
  test("aws mode with the LLM enabled needs synthesis and verification model IDs") {
    assert(ConfigLoader.load(awsEnv).isRight, "LLM off: no model ID needed")
    val on = awsEnv + ("LLM_ENABLED" -> "true")
    assert(err(on).contains("models.synthesisModel"))
    val ok = on + ("SYNTHESIS_MODEL_ID" -> "synth-x")
    assert(ConfigLoader.load(ok).isRight, "verification model has a file default")
    assert(err(ok + ("VERIFICATION_MODEL_ID" -> "")).contains("models.verificationModel"))
    assert(ConfigLoader.load(Map("LLM_ENABLED" -> "true")).isRight, "local mode needs none")
  }
  test("retrieval.maxPathDepth is bounded by graph.pathMaxDepth from the file, not by a constant") {
    assert(
      err(Map("SCHOLARCHAIN__RETRIEVAL__MAXPATHDEPTH" -> "7")).contains("graph.pathMaxDepth (6)")
    )
    val raised = Map(
      "SCHOLARCHAIN__GRAPH__PATHMAXDEPTH" -> "9",
      "SCHOLARCHAIN__RETRIEVAL__MAXPATHDEPTH" -> "8"
    )
    assertEquals(load(raised).retrieval.maxPathDepth, 8)
    assert(err(Map("SCHOLARCHAIN__GRAPH__PATHMAXDEPTH" -> "0")).contains("pathMaxDepth"))
    assert(
      err(Map("SCHOLARCHAIN__RETRIEVAL__MAXPATHDEPTH" -> "0")).contains("graph.pathMaxDepth")
    )
  }
  test("a # inside a string value or array element is not a comment") {
    val text = ConfigLoader.stripComments(
      "# full-line comment\n{\"x\": \"a # b\",\n  # another\n \"y\": [\"#z\", \"w\"]}"
    )
    val json = ujson.read(text)
    assertEquals(json("x").str, "a # b")
    assertEquals(json("y").arr.map(_.str).toVector, Vector("#z", "w"))
    val cfgText =
      bundledText.replace("\"serviceName\": \"query-service\"", "\"serviceName\": \"q # 1\"")
    assertEquals(
      ConfigLoader.parse(cfgText, Map.empty).map(_.services.query.serviceName),
      Right("q # 1")
    )
  }
