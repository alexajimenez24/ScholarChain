package scholarchain.services.worker

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try
import scholarchain.core.aws.AwsHttp

/** [[QueueConsumer]] over the SQS JSON protocol with the signed `AwsHttp` client. A message's `id`
  * is its receipt handle, which is what a delete needs. A failed call throws (the port has no error
  * channel) with the operation and HTTP status only. A message that is not deleted becomes visible
  * again after the queue's visibility timeout and reaches the dead-letter queue by redrive.
  */
final class SqsQueueConsumer(
    http: AwsHttp,
    region: String,
    queueUrl: String,
    waitSeconds: Int = 10,
    endpointOverride: Option[String] = None
) extends QueueConsumer:
  require(waitSeconds >= 0 && waitSeconds <= 20, "waitSeconds must be 0..20")
  private val endpoint = endpointOverride.getOrElse(s"https://sqs.$region.amazonaws.com/")

  private def call(op: String, body: ujson.Obj): ujson.Value =
    val reply = http.send(
      "POST",
      endpoint,
      "sqs",
      body.render().getBytes(UTF_8),
      Map("content-type" -> "application/x-amz-json-1.0", "x-amz-target" -> s"AmazonSQS.$op")
    )
    reply match
      case Left(_)           => throw new IllegalStateException(s"SQS $op: no response")
      case Right(r) if !r.ok => throw new IllegalStateException(s"SQS $op: HTTP ${r.status}")
      case Right(r) if r.body.isEmpty => ujson.Obj()
      case Right(r)                   =>
        Try(ujson.read(r.body))
          .getOrElse(throw new IllegalStateException(s"SQS $op: reply is not JSON"))

  def receive(max: Int): Vector[QueueMessage] =
    val reply = call(
      "ReceiveMessage",
      ujson.Obj(
        "QueueUrl" -> queueUrl,
        "MaxNumberOfMessages" -> math.max(1, math.min(max, SqsQueueConsumer.MaxBatch)),
        "WaitTimeSeconds" -> waitSeconds
      )
    )
    reply.obj.get("Messages").fold(Vector.empty[QueueMessage]) { ms =>
      ms.arr.toVector.map(m => QueueMessage(m("ReceiptHandle").str, m("Body").str))
    }

  def acknowledge(ids: Vector[String]): Unit =
    ids.grouped(SqsQueueConsumer.MaxBatch).foreach { batch =>
      val reply = call(
        "DeleteMessageBatch",
        ujson.Obj(
          "QueueUrl" -> queueUrl,
          "Entries" -> ujson.Arr.from(
            batch.zipWithIndex.map((h, i) => ujson.Obj("Id" -> s"d$i", "ReceiptHandle" -> h))
          )
        )
      )
      val failed = reply.obj.get("Failed").fold(0)(_.arr.size)
      if failed > 0 then
        throw new IllegalStateException(s"SQS DeleteMessageBatch: $failed entries failed")
    }

object SqsQueueConsumer:
  /** SQS limit of one receive and of one delete batch. */
  val MaxBatch = 10
