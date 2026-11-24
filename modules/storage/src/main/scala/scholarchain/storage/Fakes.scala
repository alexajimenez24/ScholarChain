package scholarchain.storage

import scala.collection.concurrent.TrieMap

/** In-memory object store enforcing the same create-only rule as S3 conditional writes. */
final class InMemoryObjectStore extends ObjectStore:
  private val data = TrieMap.empty[String, (Array[Byte], String)]

  def putIfAbsent(
      uri: String,
      bytes: Array[Byte],
      contentType: String
  ): Either[String, PutOutcome] =
    S3Uri.parse(uri).map { _ =>
      if data.putIfAbsent(uri, (bytes.clone(), contentType)).isEmpty then PutOutcome.Created
      else PutOutcome.AlreadyExists
    }

  def get(uri: String): Either[String, Array[Byte]] =
    data.get(uri).map(_._1.clone()).toRight(s"no object $uri")

  def list(prefixUri: String): Either[String, Vector[String]] =
    val p = prefixUri.stripSuffix("/") + "/"
    S3Uri.parse(p).map(_ => data.keys.filter(_.startsWith(p)).toVector.sorted)

  def contentType(uri: String): Option[String] = data.get(uri).map(_._2)
  def keys: Vector[String] = data.keys.toVector.sorted

/** In-memory key-value store enforcing the same conditions and key limits as DynamoDB. */
final class InMemoryKeyValueStore extends KeyValueStore:
  private val tables = TrieMap.empty[String, TrieMap[(String, String), KvItem]]
  private def t(name: String) = tables.getOrElseUpdate(name, TrieMap.empty)

  def putIfAbsent(table: String, item: KvItem): Either[KvError, Unit] =
    KvItem.validate(item).left.map(KvError.Failure(_)).flatMap { i =>
      t(table).putIfAbsent(i.pk -> i.sk, i) match
        case None    => Right(())
        case Some(_) => Left(KvError.ConditionFailed(i.pk, i.sk))
    }

  def replaceIfVersion(
      table: String,
      item: KvItem,
      expectedVersion: Long
  ): Either[KvError, Unit] =
    KvItem.validate(item).left.map(KvError.Failure(_)).flatMap { i =>
      if i.version != expectedVersion + 1 then
        Left(KvError.Failure(s"item version ${i.version} must be expected $expectedVersion + 1"))
      else
        val tbl = t(table)
        tbl.get(i.pk -> i.sk) match
          case Some(cur) if cur.version == expectedVersion =>
            // replace(k, old, new) is atomic: a concurrent writer makes it return false
            if tbl.replace(i.pk -> i.sk, cur, i) then Right(())
            else Left(KvError.ConditionFailed(i.pk, i.sk))
          case _ => Left(KvError.ConditionFailed(i.pk, i.sk))
    }

  def get(table: String, pk: String, sk: String): Either[String, Option[KvItem]] =
    Right(t(table).get(pk -> sk))

  def query(
      table: String,
      pk: String,
      skPrefix: String,
      limit: Int
  ): Either[String, Vector[KvItem]] =
    if limit < 1 || limit > 1000 then Left(s"limit $limit must be 1..1000")
    else
      Right(
        t(table).valuesIterator
          .filter(i => i.pk == pk && i.sk.startsWith(skPrefix))
          .toVector
          .sortBy(_.sk)
          .take(limit)
      )

  def size(table: String): Int = t(table).size

/** Graph port fake: records every query and answers through `responder`. */
final class InMemoryGraphStore(
    responder: GraphQuery => Either[String, Vector[ujson.Obj]] = _ => Right(Vector.empty)
) extends GraphStore:
  private val log = scala.collection.mutable.ArrayBuffer.empty[GraphQuery]

  def run(query: GraphQuery): Either[String, Vector[ujson.Obj]] =
    if !GraphTemplates.isAllowlisted(query) then Left(s"${query.templateId} is not allowlisted")
    else
      log.synchronized(log += query)
      responder(query)

  def executed: Vector[GraphQuery] = log.synchronized(log.toVector)
