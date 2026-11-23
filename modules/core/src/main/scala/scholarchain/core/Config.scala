package scholarchain.core

import upickle.default.ReadWriter

final case class ApplicationConfig(environment: String, region: String, mode: String)
    derives ReadWriter
final case class StorageConfig(
    rawBucket: String,
    curatedBucket: String,
    graphBucket: String,
    ragBucket: String,
    localRoot: String
) derives ReadWriter
final case class NeptuneConfig(endpoint: String, port: Int, queryTimeoutMs: Int, iamAuth: Boolean)
    derives ReadWriter
final case class DynamoConfig(
    runLedgerTable: String,
    entityLinkCacheTable: String,
    querySessionTable: String,
    evidenceBundleTable: String,
    artifactRegistryTable: String,
    researcherMetricTable: String
) derives ReadWriter
final case class VectorConfig(vectorBucket: String, indexes: Map[String, String], dimension: Int)
    derives ReadWriter
final case class EntityResolutionConfig(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandMin: Double,
    llmBandMax: Double,
    maxBlockSize: Int,
    maxLlmCallsPerRun: Int
) derives ReadWriter
final case class GraphConfig(
    policyVersion: String,
    metricVersion: String,
    windows: Vector[TimeWindow],
    maxTeamSizeForPairs: Int,
    representativeWorkLimit: Int,
    componentMaxIterations: Int,
    communityIterations: Int,
    betweennessSampleSources: Int,
    betweennessSeed: Long,
    kcoreMaxK: Int,
    pathMaxDepth: Int
) derives ReadWriter
final case class RetrievalConfig(
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    contextTokenBudget: Int,
    fusionPolicyVersion: String
) derives ReadWriter
final case class ModelConfig(
    embeddingModel: String,
    synthesisModel: String,
    verificationModel: String,
    llmEnabled: Boolean
) derives ReadWriter
final case class OperationsConfig(
    synchronousDeadlineMs: Int,
    maxWorkflowRetries: Int,
    maxConcurrentLlmCalls: Int,
    maxTokensPerQuery: Int,
    maxQuestionChars: Int
) derives ReadWriter

/** Settings of one HTTP deployable (query service or graph service). */
final case class HttpServiceConfig(serviceName: String, port: Int, requestTimeoutMs: Int)
    derives ReadWriter

/** Settings of the embedding worker deployable: a small HTTP surface plus its queue. */
final case class WorkerServiceConfig(
    serviceName: String,
    port: Int,
    requestTimeoutMs: Int,
    embeddingQueueUrl: String,
    batchSize: Int,
    maxInFlight: Int
) derives ReadWriter

/** Cognito JWT settings shared by the three deployables (verified in servicesCommon). */
final case class JwtConfig(
    issuer: String,
    audience: String,
    jwksUrl: String,
    requestTimeoutMs: Int
) derives ReadWriter

final case class ServicesConfig(
    query: HttpServiceConfig,
    graph: HttpServiceConfig,
    worker: WorkerServiceConfig,
    auth: JwtConfig
) derives ReadWriter

final case class ScholarChainConfig(
    configVersion: String,
    application: ApplicationConfig,
    storage: StorageConfig,
    neptune: NeptuneConfig,
    dynamo: DynamoConfig,
    vectors: VectorConfig,
    entityResolution: EntityResolutionConfig,
    graph: GraphConfig,
    retrieval: RetrievalConfig,
    models: ModelConfig,
    operations: OperationsConfig,
    services: ServicesConfig
) derives ReadWriter

/** Loads configuration. Precedence, highest first:
  *
  *   1. `SCHOLARCHAIN__<SECTION>__<KEY>` environment overrides, path segments matched
  *      case-insensitively (e.g. `SCHOLARCHAIN__GRAPH__KCOREMAXK=12`,
  *      `SCHOLARCHAIN__SERVICES__AUTH__ISSUER=...`)
  *   1. `${VAR}` / `${VAR:-default}` placeholders inside the file, resolved from the environment;
  *      the default after `:-` applies when the variable is unset
  *   1. literal values in the versioned file
  *
  * There are no defaults in code: every value, including every default, lives in the versioned
  * file. A `${VAR}` without default and without environment value fails the load.
  *
  * The file is the classpath resource `scholarchain/config/scholarchain-v1.yaml` unless
  * `SCHOLARCHAIN_CONFIG_FILE` names another path. The file is written in the JSON-compatible subset
  * of YAML 1.2 (one JSON document), because uPickle is the only JSON library. The loader accepts
  * full-line `#` comments, which it removes before parsing; no other YAML feature is supported.
  *
  * Typing: a placeholder that is the whole string takes the JSON type its file default parses as (a
  * number or boolean), and is otherwise a string, so an ID that happens to be all digits stays a
  * string. An override takes the type of the value it replaces.
  */
object ConfigLoader:
  val DefaultResource = "scholarchain/config/scholarchain-v1.yaml"
  val SupportedVersions = Set("config-v1")
  private val Placeholder = "\\$\\{([A-Z][A-Z0-9_]*)(?::-([^}]*))?\\}".r
  private val WholePlaceholder = "^\\$\\{([A-Z][A-Z0-9_]*)(?::-([^}]*))?\\}$".r

  /** The process environment as an immutable map. */
  def processEnvironment: Map[String, String] =
    import scala.jdk.CollectionConverters.*
    System.getenv().asScala.toMap

  def load(env: Map[String, String] = processEnvironment): Either[String, ScholarChainConfig] =
    for
      text <- readSource(env)
      cfg <- parse(text, env)
    yield cfg

  def parse(text: String, env: Map[String, String]): Either[String, ScholarChainConfig] =
    for
      json <- scala.util
        .Try(ujson.read(stripComments(text)))
        .toEither
        .left
        .map(e => s"config is not JSON-compatible YAML: ${e.getMessage}")
      missing = collectMissing(json, env)
      _ <- Either.cond(
        missing.isEmpty,
        (),
        s"config placeholders without value: ${missing.mkString(", ")}"
      )
      badNumbers = scala.collection.mutable.ArrayBuffer.empty[String]
      resolved = resolve(json, env, badNumbers)
      _ <- Either.cond(
        badNumbers.isEmpty,
        (),
        s"config placeholders with unacceptable numbers: ${badNumbers.mkString(", ")}"
      )
      overridden <- applyOverrides(resolved, env)
      cfg <- scala.util
        .Try(upickle.default.read[ScholarChainConfig](overridden))
        .toEither
        .left
        .map(e => s"config does not match the schema: ${e.getMessage}")
      _ <- Either.cond(
        scala.util.Try(ujson.read(upickle.default.write(cfg)) == overridden).getOrElse(false),
        (),
        "config value was clamped, truncated or is not in the schema (a number out of range for its field)"
      )
      _ <- validate(cfg)
    yield cfg

  /** Removes full-line `#` comments. A JSON string never spans lines, so no value is affected. */
  def stripComments(text: String): String =
    text.linesIterator.filterNot(_.trim.startsWith("#")).mkString("\n")

  def validate(c: ScholarChainConfig): Either[String, ScholarChainConfig] =
    val er = c.entityResolution
    val aws = c.application.mode == "aws"
    val requiredInAws = Vector(
      "storage.rawBucket" -> c.storage.rawBucket,
      "storage.curatedBucket" -> c.storage.curatedBucket,
      "storage.graphBucket" -> c.storage.graphBucket,
      "storage.ragBucket" -> c.storage.ragBucket,
      "neptune.endpoint" -> c.neptune.endpoint,
      "vectors.vectorBucket" -> c.vectors.vectorBucket,
      "dynamo.runLedgerTable" -> c.dynamo.runLedgerTable,
      "dynamo.entityLinkCacheTable" -> c.dynamo.entityLinkCacheTable,
      "dynamo.querySessionTable" -> c.dynamo.querySessionTable,
      "dynamo.evidenceBundleTable" -> c.dynamo.evidenceBundleTable,
      "dynamo.artifactRegistryTable" -> c.dynamo.artifactRegistryTable,
      "dynamo.researcherMetricTable" -> c.dynamo.researcherMetricTable,
      "models.embeddingModel" -> c.models.embeddingModel,
      "services.worker.embeddingQueueUrl" -> c.services.worker.embeddingQueueUrl,
      "services.auth.issuer" -> c.services.auth.issuer,
      "services.auth.audience" -> c.services.auth.audience,
      "services.auth.jwksUrl" -> c.services.auth.jwksUrl
    ).collect { case (name, v) if aws && v.trim.isEmpty => s"$name is empty in aws mode" }
    val ports = Vector(c.services.query.port, c.services.graph.port, c.services.worker.port)
    val timeouts = Vector(
      c.services.query.requestTimeoutMs,
      c.services.graph.requestTimeoutMs,
      c.services.worker.requestTimeoutMs,
      c.services.auth.requestTimeoutMs
    )
    val g = c.graph
    val r = c.retrieval
    val o = c.operations
    val llmModels = Vector(
      "models.synthesisModel" -> c.models.synthesisModel,
      "models.verificationModel" -> c.models.verificationModel
    ).collect {
      case (name, v) if aws && c.models.llmEnabled && v.trim.isEmpty =>
        s"$name is empty in aws mode with llmEnabled"
    }
    val atLeastOne = Vector(
      "neptune.queryTimeoutMs" -> c.neptune.queryTimeoutMs.toLong,
      "vectors.dimension" -> c.vectors.dimension.toLong,
      "entityResolution.maxBlockSize" -> er.maxBlockSize.toLong,
      "graph.representativeWorkLimit" -> g.representativeWorkLimit.toLong,
      "graph.componentMaxIterations" -> g.componentMaxIterations.toLong,
      "graph.communityIterations" -> g.communityIterations.toLong,
      "graph.betweennessSampleSources" -> g.betweennessSampleSources.toLong,
      "graph.kcoreMaxK" -> g.kcoreMaxK.toLong,
      "graph.pathMaxDepth" -> g.pathMaxDepth.toLong,
      "retrieval.graphLimit" -> r.graphLimit.toLong,
      "retrieval.vectorLimit" -> r.vectorLimit.toLong,
      "retrieval.contextTokenBudget" -> r.contextTokenBudget.toLong,
      "operations.synchronousDeadlineMs" -> o.synchronousDeadlineMs.toLong,
      "operations.maxConcurrentLlmCalls" -> o.maxConcurrentLlmCalls.toLong,
      "operations.maxTokensPerQuery" -> o.maxTokensPerQuery.toLong,
      "operations.maxQuestionChars" -> o.maxQuestionChars.toLong
    ).collect { case (name, v) if v < 1 => s"$name must be >= 1 (is $v)" }
    val nonNegative = Vector(
      "entityResolution.maxLlmCallsPerRun" -> er.maxLlmCallsPerRun.toLong,
      "operations.maxWorkflowRetries" -> o.maxWorkflowRetries.toLong
    ).collect { case (name, v) if v < 0 => s"$name must be >= 0 (is $v)" }
    val versions = Vector(
      "entityResolution.policyVersion" -> er.policyVersion,
      "graph.policyVersion" -> g.policyVersion,
      "graph.metricVersion" -> g.metricVersion,
      "retrieval.fusionPolicyVersion" -> r.fusionPolicyVersion
    ).collect { case (name, v) if v.trim.isEmpty => s"$name is empty" }
    val errors =
      Vector(
        Option.when(!SupportedVersions(c.configVersion))(
          s"unsupported config version ${c.configVersion}"
        ),
        Option.when(
          !(er.rejectThreshold < er.llmBandMin && er.llmBandMin <= er.llmBandMax && er.llmBandMax <= er.acceptThreshold)
        )(
          "entity-resolution thresholds must satisfy reject < llmBandMin <= llmBandMax <= accept"
        ),
        Option.when(g.windows.isEmpty)("graph.windows is empty"),
        Option.when(g.maxTeamSizeForPairs < 2)("graph.maxTeamSizeForPairs must be >= 2"),
        Option.when(r.maxPathDepth < 1 || r.maxPathDepth > g.pathMaxDepth)(
          s"retrieval.maxPathDepth must be 1..graph.pathMaxDepth (${g.pathMaxDepth})"
        ),
        Option.when(!Set("local", "aws").contains(c.application.mode))(
          "application.mode must be local or aws"
        ),
        Option.when(c.neptune.port < 1 || c.neptune.port > 65535)(
          s"neptune.port ${c.neptune.port} must be 1..65535"
        ),
        Option.when(c.vectors.indexes.isEmpty || c.vectors.indexes.values.exists(_.trim.isEmpty))(
          "vectors.indexes must name at least one index and no blank name"
        ),
        Option.when(ports.exists(p => p < 1 || p > 65535))("service ports must be 1..65535"),
        Option.when(timeouts.exists(_ < 1))("service and JWKS request timeouts must be >= 1 ms"),
        Option.when(c.services.worker.batchSize < 1 || c.services.worker.maxInFlight < 1)(
          "worker batchSize and maxInFlight must be >= 1"
        )
      ).flatten ++ requiredInAws ++ llmModels ++ atLeastOne ++ nonNegative ++ versions ++
        g.windows.flatMap(Validate.window) ++
        Vector(er.acceptThreshold, er.rejectThreshold, er.llmBandMin, er.llmBandMax).flatMap(
          Validate.confidence(_, "threshold")
        )
    Either.cond(errors.isEmpty, c, errors.mkString("; "))

  private def readSource(env: Map[String, String]): Either[String, String] =
    env.get("SCHOLARCHAIN_CONFIG_FILE") match
      case Some(path) =>
        scala.util
          .Try(java.nio.file.Files.readString(java.nio.file.Path.of(path)))
          .toEither
          .left
          .map(e => s"cannot read SCHOLARCHAIN_CONFIG_FILE $path: ${e.getMessage}")
      case None =>
        Option(getClass.getClassLoader.getResourceAsStream(DefaultResource))
          .toRight(s"classpath resource $DefaultResource is missing")
          .map(in =>
            try new String(in.readAllBytes(), "UTF-8")
            finally in.close()
          )

  private def collectMissing(v: ujson.Value, env: Map[String, String]): Vector[String] = v match
    case ujson.Str(s) =>
      Placeholder
        .findAllMatchIn(s)
        .collect {
          case m if Option(m.group(2)).isEmpty && !env.contains(m.group(1)) => m.group(1)
        }
        .toVector
    case ujson.Obj(o) => o.values.toVector.flatMap(collectMissing(_, env))
    case ujson.Arr(a) => a.toVector.flatMap(collectMissing(_, env))
    case _            => Vector.empty

  private def resolve(
      v: ujson.Value,
      env: Map[String, String],
      bad: scala.collection.mutable.ArrayBuffer[String]
  ): ujson.Value = v match
    case ujson.Str(s) =>
      val replaced = Placeholder.replaceAllIn(
        s,
        m =>
          scala.util.matching.Regex
            .quoteReplacement(env.getOrElse(m.group(1), Option(m.group(2)).getOrElse("")))
      )
      s match
        case WholePlaceholder(name, default) if Option(default).exists(isTyped) =>
          val integral = plainDouble(default).exists(isWhole)
          val out = coerce(replaced, integral)
          if plainDouble(default).isDefined && !out.isInstanceOf[ujson.Num] then
            bad += s"$name='$replaced'" + (if integral then " (finite whole number)"
                                           else " (finite)")
          out
        case _ => ujson.Str(replaced)
    case ujson.Obj(o) => ujson.Obj.from(o.map((k, x) => k -> resolve(x, env, bad)))
    case ujson.Arr(a) => ujson.Arr.from(a.map(resolve(_, env, bad)))
    case other        => other

  private def isTyped(default: String): Boolean =
    plainDouble(default).isDefined || default.toBooleanOption.isDefined

  private val MaxExactInteger = 9007199254740992.0

  /** Plain decimal number text only: no Java float suffix (`8182d`), hex or `NaN` forms. */
  private val PlainNumber = """-?\d+(\.\d+)?([eE][+-]?\d+)?""".r

  private def plainDouble(s: String): Option[Double] =
    Option.when(PlainNumber.matches(s))(s.toDoubleOption).flatten

  private def isWhole(d: Double): Boolean = !d.isNaN && !d.isInfinite && d == math.rint(d)

  /** A finite number; a whole number within 2^53 when the field it fills is integral. */
  private def acceptableNumber(d: Double, integral: Boolean): Boolean =
    !d.isNaN && !d.isInfinite && (!integral || (isWhole(d) && math.abs(d) < MaxExactInteger))

  /** A placeholder typed by its file default takes the JSON type its text parses as. A value that
    * is not an acceptable number (NaN, infinite, fractional for an integral field) stays a string,
    * so the schema read rejects it instead of truncating it.
    */
  private def coerce(s: String, integral: Boolean): ujson.Value =
    plainDouble(s)
      .map(d => if acceptableNumber(d, integral) then ujson.Num(d) else ujson.Str(s))
      .orElse(s.toBooleanOption.map(ujson.Bool(_)))
      .getOrElse(ujson.Str(s))

  private def applyOverrides(
      v: ujson.Value,
      env: Map[String, String]
  ): Either[String, ujson.Value] =
    val overrides = env.toVector
      .collect {
        case (k, value) if k.startsWith("SCHOLARCHAIN__") =>
          k.stripPrefix("SCHOLARCHAIN__").split("__").toVector -> value
      }
      .sortBy(_._1.mkString("__"))
    overrides.foldLeft[Either[String, ujson.Value]](Right(v)) { case (acc, (path, value)) =>
      acc.flatMap(json => setPath(json, path, value))
    }

  private def setPath(
      root: ujson.Value,
      path: Vector[String],
      value: String
  ): Either[String, ujson.Value] =
    val label = path.mkString("__")
    def typed(old: ujson.Value): Either[String, ujson.Value] = old match
      case ujson.Str(_)        => Right(ujson.Str(value))
      case ujson.Num(oldValue) =>
        plainDouble(value)
          .filter(acceptableNumber(_, isWhole(oldValue)))
          .map(ujson.Num(_))
          .toRight(
            s"override $label is not a number" +
              (if isWhole(oldValue) then " (a finite whole number is required)" else " (finite)")
          )
      case _: ujson.Bool =>
        value.toBooleanOption.map(ujson.Bool(_)).toRight(s"override $label is not a boolean")
      case _ =>
        scala.util.Try(ujson.read(value)).toEither.left.map(_ => s"override $label is not JSON")
    def go(node: ujson.Value, rest: Vector[String]): Either[String, ujson.Value] =
      (node, rest) match
        case (ujson.Obj(o), head +: tail) =>
          o.keys.find(_.equalsIgnoreCase(head)) match
            case None                      => Left(s"override path $label names unknown key $head")
            case Some(key) if tail.isEmpty =>
              typed(o(key)).map { updated => o(key) = updated; node }
            case Some(key) => go(o(key), tail).map { child => o(key) = child; node }
        case _ => Left(s"override path $label does not reach a value")
    go(ujson.copy(root), path)
