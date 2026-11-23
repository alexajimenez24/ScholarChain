package scholarchain.core

import upickle.default.{Reader, Writer, read, write}
import scala.util.{Failure, Success, Try}

/** One JSON document per line: the interchange format between pipeline stages on S3.
  *
  * Decoding is strict and total: it never throws, and it returns `Left` for malformed JSON, a JSON
  * null in a required (non-Option) field, an unknown field, and a value the record type would
  * change (a fractional `year`). A record must decode to exactly the JSON it came from.
  */
object JsonLines:
  def encode[T: Writer](value: T): String = write(value)

  def decode[T: Reader: Writer](line: String): Either[String, T] =
    Try {
      val json = ujson.read(line)
      val value = read[T](json)
      nullPath(value, "$").foreach(p => throw new IllegalArgumentException(s"null at $p"))
      if ujson.read(write(value)) != json then
        throw new IllegalArgumentException(
          "record differs from its canonical form (unknown field or lossy value)"
        )
      value
    }.toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}".take(500))

  /** Decodes and validates; failures carry the reason so the caller can emit a reject record. A
    * validator that throws is a rejected record, not a crashed task.
    */
  def decodeValid[T: Reader: Writer](
      line: String,
      validate: T => Validate.Errors
  ): Either[String, T] =
    decode[T](line).flatMap { v =>
      Try(validate(v)) match
        case Success(errors) => if errors.isEmpty then Right(v) else Left(errors.mkString("; "))
        case Failure(e)      => Left(s"validation failed: ${e.getClass.getSimpleName}".take(500))
    }

  /** Path of the first JVM null reachable through case-class fields, options, collections and map
    * values; uPickle decodes a JSON null into a null reference for every non-Option field.
    */
  private[core] def nullPath(v: Any, path: String): Option[String] = v match
    case null       => Some(path)
    case p: Product =>
      p.productIterator
        .zip(p.productElementNames)
        .iterator
        .flatMap { (x, n) =>
          nullPath(x, s"$path.$n")
        }
        .nextOption()
    case m: scala.collection.Map[?, ?] =>
      m.iterator.flatMap((k, x) => nullPath(x, s"$path.$k")).nextOption()
    case i: Iterable[?] => i.iterator.flatMap(x => nullPath(x, s"$path[]")).nextOption()
    case _              => None

/** A rejected input with its reason, written to versioned reject outputs. */
final case class RejectRecord(stage: String, reason: String, input: String, runId: String)
    derives upickle.default.ReadWriter

object RejectRecord:
  /** Inputs are truncated so a decompression bomb or giant record cannot blow up reject files. */
  def of(stage: String, reason: String, input: String, runId: String): RejectRecord =
    RejectRecord(stage, reason.take(500), input.take(2000), runId)
