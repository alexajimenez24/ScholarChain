package scholarchain.storage

/** Storage ports. Runtime code depends only on these traits; AWS adapters and in-memory fakes
  * implement them, so every caller is tested without AWS.
  *
  * No port has an unconditional overwrite: a replayed load must never replace stored evidence
  * (idempotency law). Writes are create-only or version-guarded.
  */
enum PutOutcome:
  case Created, AlreadyExists

trait ObjectStore:
  /** Create-only write: `AlreadyExists` when the key is taken (S3 `If-None-Match: *`). */
  def putIfAbsent(uri: String, bytes: Array[Byte], contentType: String): Either[String, PutOutcome]

  /** Whole-object read. Text only: see [[S3ObjectStore]] for the binary limit. */
  def get(uri: String): Either[String, Array[Byte]]

  def getText(uri: String): Either[String, String] = get(uri).map(new String(_, "UTF-8"))

  /** All object URIs under a prefix URI (`s3://bucket/prefix`), in ascending key order. */
  def list(prefixUri: String): Either[String, Vector[String]]

/** One DynamoDB item: partition key, sort key, JSON payload, an optimistic-lock version (1 on
  * creation) and an optional TTL in epoch seconds.
  */
final case class KvItem(
    pk: String,
    sk: String,
    payload: String,
    version: Long = 1L,
    ttl: Option[Long] = None
):
  def isExpired(nowEpochSeconds: Long): Boolean = ttl.exists(_ <= nowEpochSeconds)

object KvItem:
  /** DynamoDB limits: partition key 2048 bytes, sort key 1024 bytes, item 400 KB including
    * attribute names (Constraints page, checked 2026-11-23).
    */
  val MaxPkBytes = 2048
  val MaxSkBytes = 1024
  val MaxItemBytes = 400 * 1024
  private val AttributeOverhead = "pkskpayloadversionttl".length + 40

  private def bytes(s: String) = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length

  def validate(i: KvItem): Either[String, KvItem] =
    if i.pk.isEmpty || bytes(i.pk) > MaxPkBytes then Left(s"pk must be 1..$MaxPkBytes bytes")
    else if i.sk.isEmpty || bytes(i.sk) > MaxSkBytes then Left(s"sk must be 1..$MaxSkBytes bytes")
    else if i.version < 1 then Left("version must be >= 1")
    else if bytes(i.pk) + bytes(i.sk) + bytes(i.payload) + AttributeOverhead > MaxItemBytes then
      Left(s"item exceeds the DynamoDB $MaxItemBytes byte item limit")
    else Right(i)

enum KvError:
  /** The condition failed: the item exists (create) or its version differs (replace). */
  case ConditionFailed(pk: String, sk: String)
  case Failure(message: String)

trait KeyValueStore:
  /** Succeeds only when (pk, sk) does not exist yet (`attribute_not_exists`). */
  def putIfAbsent(table: String, item: KvItem): Either[KvError, Unit]

  /** Replaces an item only when the stored `version` equals `expectedVersion`; `item.version` must
    * be `expectedVersion + 1`. A missing item fails the condition.
    */
  def replaceIfVersion(table: String, item: KvItem, expectedVersion: Long): Either[KvError, Unit]

  def get(table: String, pk: String, sk: String): Either[String, Option[KvItem]]

  /** Items of one partition in ascending sort-key order, optionally restricted to a prefix. `limit`
    * is 1..1000. Expired-by-TTL items are returned (DynamoDB deletes them lazily); the table
    * repositories filter them.
    */
  def query(
      table: String,
      pk: String,
      skPrefix: String = "",
      limit: Int = 100
  ): Either[String, Vector[KvItem]]

/** Read-only graph access through allowlisted templates only (see [[GraphTemplates]]). A runtime
  * service holds this port and nothing that can write: bulk loading is [[NeptuneBulkLoader]], used
  * by loaders only.
  */
trait GraphStore:
  def run(query: GraphQuery): Either[String, Vector[ujson.Obj]]
