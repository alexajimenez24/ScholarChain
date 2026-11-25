package scholarchain.services.common

import cats.effect.IO

/** Where CloudWatch Embedded Metric Format lines go. In ECS the container's stdout is shipped to
  * CloudWatch Logs, which extracts the metrics; no agent and no `/metrics` endpoint is needed.
  */
trait MetricsSink:
  def emit(line: String): IO[Unit]

object StdoutMetrics extends MetricsSink:
  def emit(line: String): IO[Unit] = IO.blocking(Console.out.println(line))

final class CollectingMetrics extends MetricsSink:
  private val lines = java.util.concurrent.ConcurrentLinkedQueue[String]()
  def emit(line: String): IO[Unit] = IO { lines.add(line); () }
  def all: Vector[String] =
    scala.jdk.CollectionConverters.IterableHasAsScala(lines).asScala.toVector

final case class Metric(name: String, value: Double, unit: String = "Count")

object Emf:
  /** One EMF log line: `_aws.CloudWatchMetrics` names the namespace, the dimension set and the
    * metrics; the values and the dimension values are top-level members. `properties` are
    * searchable log fields that are not dimensions (request and trace IDs). Never put secrets in
    * them.
    */
  def line(
      namespace: String,
      dimensions: Map[String, String],
      metrics: Seq[Metric],
      properties: Map[String, String],
      timestampMillis: Long
  ): String =
    val dimNames = dimensions.keys.toVector.sorted
    val o = ujson.Obj(
      "_aws" -> ujson.Obj(
        "Timestamp" -> ujson.Num(timestampMillis.toDouble),
        "CloudWatchMetrics" -> ujson.Arr(
          ujson.Obj(
            "Namespace" -> namespace,
            "Dimensions" -> ujson.Arr(ujson.Arr.from(dimNames)),
            "Metrics" -> ujson.Arr.from(
              metrics.map(m => ujson.Obj("Name" -> m.name, "Unit" -> m.unit))
            )
          )
        )
      )
    )
    dimensions.foreach((k, v) => o(k) = v)
    properties.foreach((k, v) => o(k) = v)
    metrics.foreach(m => o(m.name) = m.value)
    ujson.write(o)
