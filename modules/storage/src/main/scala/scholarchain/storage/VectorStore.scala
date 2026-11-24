package scholarchain.storage

import scholarchain.core.aws.AwsHttp
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.concurrent.TrieMap
import scala.util.Try

/** A failure of a remote call (or of its fake), classified for the retry policy: `Transient` is
  * worth another attempt (throttling, timeout, 5xx, no response), `Permanent` is not (validation,
  * missing resource, access denied, quota).
  */
enum RemoteError:
  case Transient(message: String)
  case Permanent(message: String)

  def text: String = this match
    case Transient(m) => m
    case Permanent(m) => m
  def isTransient: Boolean = this.isInstanceOf[Transient]

/** Bounded retry of transient failures only. `maxAttempts` counts every call, so 1 means none. The
  * wait before attempt n+1 is `backoffMillis * 2^(n-1)`, capped at 5 seconds, through `sleeper`.
  */
final case class RetryPolicy(
    maxAttempts: Int = 4,
    backoffMillis: Long = 200L,
    sleeper: Long => Unit = Thread.sleep
):
  require(maxAttempts >= 1 && maxAttempts <= 10, "maxAttempts must be 1..10")

  def run[A](op: => Either[RemoteError, A]): Either[RemoteError, A] =
    def go(attempt: Int): Either[RemoteError, A] =
      op match
        case Left(e) if e.isTransient && attempt < maxAttempts =>
          sleeper(math.min(5000L, backoffMillis * (1L << (attempt - 1))))
          go(attempt + 1)
        case other => other
    go(1)

/** A metadata value. S3 Vectors metadata supports strings, numbers, booleans and string lists; the
  * loaders use strings, whole numbers (year windows) and string lists (the researchers of a path).
  */
enum MetaValue:
  case Str(value: String)
  case Num(value: Long)
  case Strs(values: Vector[String])

  def toJson: ujson.Value = this match
    case Str(s)   => ujson.Str(s)
    case Num(n)   => ujson.Num(n.toDouble)
    case Strs(vs) => ujson.Arr.from(vs.map(ujson.Str(_)))

object MetaValue:
  def fromJson(v: ujson.Value): Either[String, MetaValue] = v match
    case ujson.Str(s)                                    => Right(Str(s))
    case ujson.Num(n) if n.isWhole && math.abs(n) < 1e15 => Right(Num(n.toLong))
    case ujson.Arr(xs) if xs.forall(_.strOpt.isDefined)  => Right(Strs(xs.map(_.str).toVector))
    case other => Left(s"unsupported metadata value ${other.render().take(60)}")

/** One vector with its key, float32 data and metadata. */
final case class VectorRecord(key: String, data: Vector[Float], metadata: Map[String, MetaValue])
final case class VectorHit(key: String, distance: Double, metadata: Map[String, MetaValue])

/** What `GetVectors` returns: the stored vector and its metadata. */
final case class StoredVector(key: String, data: Vector[Float], metadata: Map[String, MetaValue])

/** A metadata filter term; a query's terms are ANDed. `Eq` also matches an element of a list. */
enum FilterTerm:
  case Eq(key: String, value: MetaValue)
  case Gte(key: String, value: Long)
  case Lte(key: String, value: Long)

object FilterTerm:
  def toJson(terms: Vector[FilterTerm]): Option[ujson.Value] =
    def one(t: FilterTerm): ujson.Value = t match
      case FilterTerm.Eq(k, v)  => ujson.Obj(k -> ujson.Obj("$eq" -> v.toJson))
      case FilterTerm.Gte(k, n) => ujson.Obj(k -> ujson.Obj("$gte" -> ujson.Num(n.toDouble)))
      case FilterTerm.Lte(k, n) => ujson.Obj(k -> ujson.Obj("$lte" -> ujson.Num(n.toDouble)))
    terms match
      case Vector()  => None
      case Vector(t) => Some(one(t))
      case ts        => Some(ujson.Obj("$and" -> ujson.Arr.from(ts.map(one))))

  def matches(terms: Vector[FilterTerm], md: Map[String, MetaValue]): Boolean =
    terms.forall {
      case FilterTerm.Eq(k, v) =>
        md.get(k).exists {
          case MetaValue.Strs(xs) =>
            v match
              case MetaValue.Str(s) => xs.contains(s)
              case _                => false
          case other => other == v
        }
      case FilterTerm.Gte(k, n) =>
        md.get(k).exists { case MetaValue.Num(x) => x >= n; case _ => false }
      case FilterTerm.Lte(k, n) =>
        md.get(k).exists { case MetaValue.Num(x) => x <= n; case _ => false }
    }

/** A vector index definition. `nonFilterableKeys` are fixed at creation and cannot change later, so
  * infra-engineer's Terraform must create the indexes with exactly [[VectorIndexes.NonFilterable]].
  */
final case class IndexSpec(
    name: String,
    dimension: Int,
    nonFilterableKeys: Vector[String],
    distanceMetric: String = "cosine"
)

/** S3 Vectors limits (checked 2026-11-23, s3-vectors-limitations.html). The metadata limits used
  * are the stricter `CLASSIC` ones, so the same records fit an `ENHANCED` index.
  */
object VectorLimits:
  val MaxKeyBytes = 1024
  val MaxPutBatch = 500
  val MaxGetBatch = 100
  val MaxTopK = 100 // service allows 10,000 with paging; one page is 100
  val MaxDimension = 4096
  val MaxMetadataKeys = 50
  val MaxFilterableBytes = 2048
  val MaxTotalMetadataBytes = 40 * 1024
  val MaxStringBytes = 2048
  val MaxNonFilterableKeys = 10
  val MinNameLength = 3
  val MaxNameLength = 63

  private def bytes(s: String) = s.getBytes(UTF_8).length
  private def obj(m: Map[String, MetaValue]) =
    ujson.Obj.from(m.toVector.sortBy(_._1).map((k, v) => k -> v.toJson))

  def validateSpec(spec: IndexSpec): Either[String, IndexSpec] =
    if bytes(spec.name) < MinNameLength || bytes(spec.name) > MaxNameLength then
      Left(s"index name must be $MinNameLength..$MaxNameLength bytes")
    else if spec.dimension < 1 || spec.dimension > MaxDimension then
      Left(s"dimension must be 1..$MaxDimension")
    else if spec.nonFilterableKeys.size > MaxNonFilterableKeys then
      Left(s"at most $MaxNonFilterableKeys non-filterable metadata keys per index")
    else if !Set("cosine", "euclidean")(spec.distanceMetric) then Left("distance metric")
    else Right(spec)

  /** Key, dimension, finite float values and the metadata limits. The same rules run in the fake
    * and before every `PutVectors`, so a record the service would refuse is never sent.
    */
  def validate(r: VectorRecord, spec: IndexSpec): Either[String, VectorRecord] =
    val md = r.metadata
    val filterable = md.filterNot((k, _) => spec.nonFilterableKeys.contains(k))
    val values = md.values.toVector.flatMap {
      case MetaValue.Str(s)   => Vector(s)
      case MetaValue.Strs(xs) => xs
      case _                  => Vector.empty
    }
    if r.key.isEmpty || bytes(r.key) > MaxKeyBytes then Left(s"key must be 1..$MaxKeyBytes bytes")
    else if r.key.exists(_.isControl) then Left("key has a control character")
    else if r.data.size != spec.dimension then
      Left(s"vector has ${r.data.size} values, index ${spec.name} needs ${spec.dimension}")
    else if r.data.exists(f => f.isNaN || f.isInfinite) then Left("vector has a non-finite value")
    else if md.size > MaxMetadataKeys then Left(s"more than $MaxMetadataKeys metadata keys")
    else if md.keys.exists(_.isEmpty) then Left("empty metadata key")
    else if values.exists(bytes(_) > MaxStringBytes) then
      Left(s"a metadata string exceeds $MaxStringBytes bytes")
    else if bytes(obj(filterable).render()) > MaxFilterableBytes then
      Left(s"filterable metadata exceeds $MaxFilterableBytes bytes")
    else if bytes(obj(md).render()) > MaxTotalMetadataBytes then
      Left(s"metadata exceeds $MaxTotalMetadataBytes bytes")
    else Right(r)

/** Port over a vector bucket. It has create-and-query abstractions for the bucket and its indexes,
  * and put, get and query for vectors. `put` is an upsert in S3 Vectors, so the loader (not the
  * port) guards against a key silently changing meaning; see [[VectorLoader]].
  */
trait VectorStore:
  def createBucket(): Either[RemoteError, PutOutcome]
  def createIndex(spec: IndexSpec): Either[RemoteError, PutOutcome]

  /** At most [[VectorLimits.MaxPutBatch]] vectors, all valid for the index, or nothing is stored.
    */
  def put(index: String, vectors: Vector[VectorRecord]): Either[RemoteError, Unit]

  /** Existing vectors among `keys` (at most [[VectorLimits.MaxGetBatch]]); absent keys are omitted.
    */
  def get(index: String, keys: Vector[String]): Either[RemoteError, Vector[StoredVector]]

  def query(
      index: String,
      vector: Vector[Float],
      topK: Int,
      filter: Vector[FilterTerm] = Vector.empty
  ): Either[RemoteError, Vector[VectorHit]]

object VectorIndexes:
  /** Metadata keys that hold references and hashes, never filtered on. Fixed at index creation. */
  val NonFilterable: Vector[String] = Vector("textUri", "textSha256", "evidenceId", "sourceUri")

  def spec(name: String, dimension: Int): IndexSpec = IndexSpec(name, dimension, NonFilterable)

/** Cosine-distance in-memory vector bucket enforcing the same limits and filters as S3 Vectors. */
final class InMemoryVectorStore extends VectorStore:
  private var bucketCreated = false
  private val specs = TrieMap.empty[String, IndexSpec]
  private val data = TrieMap.empty[String, TrieMap[String, VectorRecord]]

  def createBucket(): Either[RemoteError, PutOutcome] = synchronized {
    val was = bucketCreated
    bucketCreated = true
    Right(if was then PutOutcome.AlreadyExists else PutOutcome.Created)
  }

  def createIndex(spec: IndexSpec): Either[RemoteError, PutOutcome] = synchronized {
    if !bucketCreated then Left(RemoteError.Permanent("vector bucket does not exist"))
    else
      VectorLimits.validateSpec(spec).left.map(RemoteError.Permanent(_)).flatMap { s =>
        specs.get(s.name) match
          case Some(old) if old == s => Right(PutOutcome.AlreadyExists)
          case Some(_) => Left(RemoteError.Permanent(s"index ${s.name} exists with another spec"))
          case None    =>
            specs(s.name) = s
            data(s.name) = TrieMap.empty
            Right(PutOutcome.Created)
      }
  }

  private def index(name: String): Either[RemoteError, (IndexSpec, TrieMap[String, VectorRecord])] =
    specs.get(name).zip(data.get(name)).toRight(RemoteError.Permanent(s"no index $name"))

  def put(indexName: String, vectors: Vector[VectorRecord]): Either[RemoteError, Unit] =
    index(indexName).flatMap { (spec, store) =>
      if vectors.isEmpty || vectors.size > VectorLimits.MaxPutBatch then
        Left(RemoteError.Permanent(s"a put holds 1..${VectorLimits.MaxPutBatch} vectors"))
      else if vectors.map(_.key).distinct.size != vectors.size then
        Left(RemoteError.Permanent("duplicate keys in one put"))
      else
        vectors
          .foldLeft[Either[String, Unit]](Right(()))((acc, v) =>
            acc.flatMap(_ => VectorLimits.validate(v, spec).map(_ => ()))
          )
          .left
          .map(RemoteError.Permanent(_))
          .map(_ => vectors.foreach(v => store(v.key) = v))
    }

  def get(indexName: String, keys: Vector[String]): Either[RemoteError, Vector[StoredVector]] =
    index(indexName).flatMap { (_, store) =>
      if keys.isEmpty || keys.size > VectorLimits.MaxGetBatch then
        Left(RemoteError.Permanent(s"a get holds 1..${VectorLimits.MaxGetBatch} keys"))
      else Right(keys.flatMap(k => store.get(k).map(v => StoredVector(v.key, v.data, v.metadata))))
    }

  def query(
      indexName: String,
      vector: Vector[Float],
      topK: Int,
      filter: Vector[FilterTerm]
  ): Either[RemoteError, Vector[VectorHit]] =
    index(indexName).flatMap { (spec, store) =>
      if topK < 1 || topK > VectorLimits.MaxTopK then
        Left(RemoteError.Permanent(s"topK must be 1..${VectorLimits.MaxTopK}"))
      else if vector.size != spec.dimension then
        Left(RemoteError.Permanent(s"query vector needs ${spec.dimension} values"))
      else
        Right(
          store.valuesIterator
            .filter(v => FilterTerm.matches(filter, v.metadata))
            .map(v => VectorHit(v.key, Embeddings.cosineDistance(vector, v.data), v.metadata))
            .toVector
            .sortBy(h => (h.distance, h.key))
            .take(topK)
        )
    }

  def keys(indexName: String): Vector[String] =
    data.get(indexName).map(_.keys.toVector.sorted).getOrElse(Vector.empty)

object Embeddings:
  def cosineDistance(a: Vector[Float], b: Vector[Float]): Double =
    val dot = a.lazyZip(b).map((x, y) => x.toDouble * y).sum
    val na = math.sqrt(a.map(x => x.toDouble * x).sum)
    val nb = math.sqrt(b.map(x => x.toDouble * x).sum)
    if na == 0 || nb == 0 then 1.0 else 1.0 - dot / (na * nb)

/** S3 Vectors data-plane API over the signed `AwsHttp` client (signing name `s3vectors`, endpoint
  * `s3vectors.{region}.api.aws`, JSON POST per operation).
  *
  * Status mapping: 200 is success; 409 on a create is `AlreadyExists`; 408, 429, 500, 502, 503 and
  * 504 and a missing response are `Transient`; everything else (400 validation, 402 quota, 403,
  * 404) is `Permanent`. A 503 from `PutVectors` also means the batch exceeds the resource capacity,
  * which is why the loader uses batches of 100, not the 500 maximum. Error bodies are cut to 200
  * chars.
  *
  * `endpointOverride` is a base URL for the local stub. The adapter holds no credentials.
  */
final class S3VectorsStore(
    http: AwsHttp,
    region: String,
    bucket: String,
    endpointOverride: Option[String] = None
) extends VectorStore:
  private val base =
    endpointOverride.map(_.stripSuffix("/")).getOrElse(s"https://s3vectors.$region.api.aws")
  private val TransientStatus = Set(408, 429, 500, 502, 503, 504)

  private def call(op: String, body: ujson.Obj): Either[RemoteError, (Int, String)] =
    http
      .send(
        "POST",
        s"$base/$op",
        "s3vectors",
        body.render().getBytes(UTF_8),
        Map("content-type" -> "application/json")
      )
      .left
      .map(e => RemoteError.Transient(s"$op: $e"))
      .flatMap { r =>
        if r.ok then Right((r.status, r.body))
        else if TransientStatus(r.status) then
          Left(RemoteError.Transient(s"$op: HTTP ${r.status} ${r.body.take(200)}"))
        else if r.status == 409 then Right((409, r.body))
        else Left(RemoteError.Permanent(s"$op: HTTP ${r.status} ${r.body.take(200)}"))
      }

  def createBucket(): Either[RemoteError, PutOutcome] =
    call("CreateVectorBucket", ujson.Obj("vectorBucketName" -> bucket)).map((s, _) =>
      if s == 409 then PutOutcome.AlreadyExists else PutOutcome.Created
    )

  def createIndex(spec: IndexSpec): Either[RemoteError, PutOutcome] =
    VectorLimits.validateSpec(spec).left.map(RemoteError.Permanent(_)).flatMap { s =>
      call(
        "CreateIndex",
        ujson.Obj(
          "vectorBucketName" -> bucket,
          "indexName" -> s.name,
          "dataType" -> "float32",
          "dimension" -> s.dimension,
          "distanceMetric" -> s.distanceMetric,
          "metadataConfiguration" -> ujson.Obj(
            "nonFilterableMetadataKeys" -> ujson.Arr.from(s.nonFilterableKeys.map(ujson.Str(_)))
          )
        )
      ).map((st, _) => if st == 409 then PutOutcome.AlreadyExists else PutOutcome.Created)
    }

  private def floats(v: Vector[Float]): ujson.Value =
    ujson.Obj(
      "float32" -> ujson.Arr.from(v.map(f => ujson.Num(java.lang.Float.toString(f).toDouble)))
    )

  def put(index: String, vectors: Vector[VectorRecord]): Either[RemoteError, Unit] =
    if vectors.isEmpty || vectors.size > VectorLimits.MaxPutBatch then
      Left(RemoteError.Permanent(s"a put holds 1..${VectorLimits.MaxPutBatch} vectors"))
    else
      call(
        "PutVectors",
        ujson.Obj(
          "vectorBucketName" -> bucket,
          "indexName" -> index,
          "vectors" -> ujson.Arr.from(vectors.map { v =>
            ujson.Obj(
              "key" -> v.key,
              "data" -> floats(v.data),
              "metadata" -> ujson.Obj.from(
                v.metadata.toVector.sortBy(_._1).map((k, x) => k -> x.toJson)
              )
            )
          })
        )
      ).map(_ => ())

  private def metadataOf(v: ujson.Value): Either[String, Map[String, MetaValue]] =
    v.objOpt.fold[Either[String, Map[String, MetaValue]]](Right(Map.empty)) { o =>
      o.toVector.foldLeft[Either[String, Map[String, MetaValue]]](Right(Map.empty)) {
        case (acc, (k, x)) => acc.flatMap(m => MetaValue.fromJson(x).map(mv => m + (k -> mv)))
      }
    }

  private def parse[A](op: String, body: String)(f: ujson.Value => A): Either[RemoteError, A] =
    Try(f(ujson.read(body))).toEither.left.map(_ => RemoteError.Permanent(s"$op: malformed reply"))

  def get(index: String, keys: Vector[String]): Either[RemoteError, Vector[StoredVector]] =
    if keys.isEmpty || keys.size > VectorLimits.MaxGetBatch then
      Left(RemoteError.Permanent(s"a get holds 1..${VectorLimits.MaxGetBatch} keys"))
    else
      call(
        "GetVectors",
        ujson.Obj(
          "vectorBucketName" -> bucket,
          "indexName" -> index,
          "keys" -> ujson.Arr.from(keys.map(ujson.Str(_))),
          "returnData" -> true,
          "returnMetadata" -> true
        )
      ).flatMap { (_, body) =>
        parse("GetVectors", body)(_("vectors").arr.toVector.map { v =>
          StoredVector(
            v("key").str,
            v("data")("float32").arr.toVector.map(_.num.toFloat),
            metadataOf(v.obj.getOrElse("metadata", ujson.Null))
              .fold(e => throw new Exception(e), identity)
          )
        })
      }

  def query(
      index: String,
      vector: Vector[Float],
      topK: Int,
      filter: Vector[FilterTerm]
  ): Either[RemoteError, Vector[VectorHit]] =
    if topK < 1 || topK > VectorLimits.MaxTopK then
      Left(RemoteError.Permanent(s"topK must be 1..${VectorLimits.MaxTopK}"))
    else
      val body = ujson.Obj(
        "vectorBucketName" -> bucket,
        "indexName" -> index,
        "topK" -> topK,
        "returnMetadata" -> true,
        "returnDistance" -> true,
        "queryVector" -> floats(vector)
      )
      FilterTerm.toJson(filter).foreach(f => body("filter") = f)
      call("QueryVectors", body).flatMap { (_, reply) =>
        parse("QueryVectors", reply)(_("vectors").arr.toVector.map { h =>
          VectorHit(
            h("key").str,
            h.obj.get("distance").map(_.num).getOrElse(Double.NaN),
            metadataOf(h.obj.getOrElse("metadata", ujson.Null))
              .fold(e => throw new Exception(e), identity)
          )
        })
      }
