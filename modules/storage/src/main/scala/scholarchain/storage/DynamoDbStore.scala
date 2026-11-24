package scholarchain.storage

import scholarchain.core.aws.{AwsHttp, AwsResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** DynamoDB JSON 1.0 API over the signed `AwsHttp` client (service `dynamodb`).
  *
  * Items are `{pk: S, sk: S, payload: S, version: N, ttl?: N}`; every table is created by Terraform
  * with `pk` (S) as hash key and `sk` (S) as range key. Expressions use name aliases (`#pk`, `#sk`,
  * `#v`) so no attribute name can collide with a DynamoDB reserved word.
  *
  * Retries: none here beyond what `AwsHttp` is configured with (429 and 5xx). A 400
  * `ProvisionedThroughputExceededException` is returned as a `Failure`, not retried.
  */
final class DynamoDbStore(http: AwsHttp, region: String, endpointOverride: Option[String] = None)
    extends KeyValueStore:
  private val endpoint = endpointOverride.getOrElse(s"https://dynamodb.$region.amazonaws.com/")

  private def call(op: String, body: ujson.Value): Either[String, AwsResponse] =
    http.send(
      "POST",
      endpoint,
      "dynamodb",
      body.render().getBytes(UTF_8),
      Map(
        "content-type" -> "application/x-amz-json-1.0",
        "x-amz-target" -> s"DynamoDB_20120810.$op"
      )
    )

  private def errorType(r: AwsResponse): String =
    Try(ujson.read(r.body)("__type").str).getOrElse("")

  private def failed(op: String, r: AwsResponse): String =
    s"$op HTTP ${r.status}: ${errorType(r).split('#').last.take(100)}"

  private def attrs(i: KvItem): ujson.Obj =
    val o = ujson.Obj(
      "pk" -> ujson.Obj("S" -> i.pk),
      "sk" -> ujson.Obj("S" -> i.sk),
      "payload" -> ujson.Obj("S" -> i.payload),
      "version" -> ujson.Obj("N" -> i.version.toString)
    )
    i.ttl.foreach(t => o("ttl") = ujson.Obj("N" -> t.toString))
    o

  private def item(v: ujson.Value): Either[String, KvItem] =
    Try(
      KvItem(
        v("pk")("S").str,
        v("sk")("S").str,
        v("payload")("S").str,
        v("version")("N").str.toLong,
        v.obj.get("ttl").map(_("N").str.toLong)
      )
    ).toEither.left.map(e => s"malformed DynamoDB item: ${e.getMessage}")

  private def put(
      table: String,
      i: KvItem,
      condition: String,
      names: ujson.Obj,
      values: Option[ujson.Obj]
  ): Either[KvError, Unit] =
    val body = ujson.Obj(
      "TableName" -> table,
      "Item" -> attrs(i),
      "ConditionExpression" -> condition,
      "ExpressionAttributeNames" -> names
    )
    values.foreach(v => body("ExpressionAttributeValues") = v)
    call("PutItem", body) match
      case Right(r) if r.ok                                                     => Right(())
      case Right(r) if errorType(r).endsWith("ConditionalCheckFailedException") =>
        Left(KvError.ConditionFailed(i.pk, i.sk))
      case Right(r) => Left(KvError.Failure(failed("PutItem", r)))
      case Left(e)  => Left(KvError.Failure(e))

  def putIfAbsent(table: String, i: KvItem): Either[KvError, Unit] =
    KvItem
      .validate(i)
      .left
      .map(KvError.Failure(_))
      .flatMap(_ => put(table, i, "attribute_not_exists(#pk)", ujson.Obj("#pk" -> "pk"), None))

  def replaceIfVersion(table: String, i: KvItem, expectedVersion: Long): Either[KvError, Unit] =
    KvItem.validate(i).left.map(KvError.Failure(_)).flatMap { _ =>
      if i.version != expectedVersion + 1 then
        Left(KvError.Failure(s"item version ${i.version} must be expected $expectedVersion + 1"))
      else
        put(
          table,
          i,
          "attribute_exists(#pk) AND #v = :expected",
          ujson.Obj("#pk" -> "pk", "#v" -> "version"),
          Some(ujson.Obj(":expected" -> ujson.Obj("N" -> expectedVersion.toString)))
        )
    }

  def get(table: String, pk: String, sk: String): Either[String, Option[KvItem]] =
    call(
      "GetItem",
      ujson.Obj(
        "TableName" -> table,
        "Key" -> ujson.Obj("pk" -> ujson.Obj("S" -> pk), "sk" -> ujson.Obj("S" -> sk)),
        "ConsistentRead" -> true
      )
    ).flatMap { r =>
      if !r.ok then Left(failed("GetItem", r))
      else
        Try(ujson.read(r.body)).toEither.left
          .map(e => s"GetItem: unreadable response: ${e.getMessage}")
          .flatMap(_.obj.get("Item") match
            case None    => Right(None)
            case Some(v) => item(v).map(Some(_)))
    }

  def query(
      table: String,
      pk: String,
      skPrefix: String,
      limit: Int
  ): Either[String, Vector[KvItem]] =
    if limit < 1 || limit > 1000 then Left(s"limit $limit must be 1..1000")
    else
      val cond = if skPrefix.isEmpty then "#pk = :pk" else "#pk = :pk AND begins_with(#sk, :p)"
      val names = ujson.Obj("#pk" -> "pk")
      val values = ujson.Obj(":pk" -> ujson.Obj("S" -> pk))
      if skPrefix.nonEmpty then
        names("#sk") = "sk"
        values(":p") = ujson.Obj("S" -> skPrefix)
      // Follow LastEvaluatedKey (a page may be short) until `limit` items or no more pages.
      def page(start: Option[ujson.Value], acc: Vector[KvItem]): Either[String, Vector[KvItem]] =
        val body = ujson.Obj(
          "TableName" -> table,
          "KeyConditionExpression" -> cond,
          "ExpressionAttributeNames" -> names,
          "ExpressionAttributeValues" -> values,
          "Limit" -> (limit - acc.size),
          "ConsistentRead" -> true
        )
        start.foreach(s => body("ExclusiveStartKey") = s)
        call("Query", body).flatMap { r =>
          if !r.ok then Left(failed("Query", r))
          else
            for
              json <- Try(ujson.read(r.body)).toEither.left.map(e =>
                s"Query: unreadable response: ${e.getMessage}"
              )
              rows <- Try(json("Items").arr.toVector).toEither.left.map(_ =>
                "Query: response has no Items"
              )
              items <- rows
                .foldLeft[Either[String, Vector[KvItem]]](Right(Vector.empty))((a, v) =>
                  a.flatMap(xs => item(v).map(xs :+ _))
                )
              all = acc ++ items
              out <-
                if all.size >= limit then Right(all.take(limit))
                else
                  json.obj.get("LastEvaluatedKey") match
                    case Some(k) => page(Some(k), all)
                    case None    => Right(all)
            yield out
        }
      page(None, Vector.empty)
