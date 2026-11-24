package scholarchain.storage

import scholarchain.core.*
import upickle.default.{ReadWriter, read}
import java.nio.charset.StandardCharsets.UTF_8

/** The six S3 zones; each is a key prefix inside a configured bucket. */
enum Zone(val prefix: String):
  case Raw extends Zone("raw")
  case Curated extends Zone("curated")
  case Graph extends Zone("graph")
  case SemanticContext extends Zone("semantic-context")
  case Reports extends Zone("reports")
  case Logs extends Zone("logs")

/** Where objects live. Bucket names come from `StorageConfig`, never from literals.
  *
  * Zone to bucket: raw to `rawBucket`; curated, reports and logs to `curatedBucket`; graph to
  * `graphBucket`; semantic-context (long text and generated profiles) to `ragBucket`. Keys:
  *
  *   - run-scoped (versioned by the run ID): `{zone}/{dataset}/run_id={runId}/{name}`
  *   - run manifest: `{zone}/{dataset}/run_id={runId}/_manifest.json`
  *   - content-addressed (same bytes, same key): `{zone}/{dataset}/sha256={hex}/{name}`
  */
final class S3Layout(storage: StorageConfig):
  private val Segment = "^[A-Za-z0-9_][A-Za-z0-9._=:-]{0,127}$".r
  val ManifestName = "_manifest.json"

  def bucket(zone: Zone): Either[String, String] =
    val b = zone match
      case Zone.Raw                                => storage.rawBucket
      case Zone.Curated | Zone.Reports | Zone.Logs => storage.curatedBucket
      case Zone.Graph                              => storage.graphBucket
      case Zone.SemanticContext                    => storage.ragBucket
    Either.cond(b.trim.nonEmpty, b, s"no bucket configured for zone ${zone.prefix}")

  private def segments(parts: Seq[String]): Either[String, Unit] =
    parts.flatMap(_.split("/", -1)).find(!Segment.matches(_)) match
      case Some(bad) => Left(s"invalid key segment '$bad'")
      case None      => Right(())

  private def uri(zone: Zone, rest: String): Either[String, String] =
    bucket(zone).map(b => s"s3://$b/${zone.prefix}/$rest")

  def runKey(zone: Zone, dataset: String, runId: String, name: String): Either[String, String] =
    for
      _ <- Either.cond(Ids.isRunId(runId), (), s"invalid run ID $runId")
      _ <- segments(Seq(dataset, name))
      u <- uri(zone, s"$dataset/run_id=$runId/$name")
    yield u

  def manifestUri(zone: Zone, dataset: String, runId: String): Either[String, String] =
    runKey(zone, dataset, runId, ManifestName)

  def contentKey(
      zone: Zone,
      dataset: String,
      sha256Hex: String,
      name: String
  ): Either[String, String] =
    for
      _ <- Either.cond("^[0-9a-f]{64}$".r.matches(sha256Hex), (), "invalid sha256")
      _ <- segments(Seq(dataset, name))
      u <- uri(zone, s"$dataset/sha256=$sha256Hex/$name")
    yield u

/** Where a stored object came from: the source dataset record and its snapshot. */
final case class SourceRef(source: SourceType, sourceRecordId: String, snapshot: String)
    derives ReadWriter

/** One stored object: its URI, content hash (SHA-256 hex of the exact bytes), size and sources. */
final case class StoredObject(
    uri: String,
    sha256: String,
    bytes: Long,
    contentType: String,
    sources: Vector[SourceRef]
) derives ReadWriter

/** Run manifest of one stored dataset. It holds no timestamp, so the same run and the same objects
  * always serialize to the same bytes and a replay is `Unchanged`.
  */
final case class StorageManifest(
    schemaVersion: String,
    runId: String,
    stage: String,
    zone: String,
    dataset: String,
    policyVersion: Option[String],
    objects: Vector[StoredObject]
) derives ReadWriter

object StorageManifest:
  val Version = "storage-manifest-v1"

  def parse(json: String): Either[String, StorageManifest] =
    scala.util
      .Try(read[StorageManifest](json))
      .toEither
      .left
      .map(e => s"malformed storage manifest: ${e.getMessage}")
      .flatMap(m =>
        if m.schemaVersion != Version then Left(s"unsupported manifest version ${m.schemaVersion}")
        else if !Ids.isRunId(m.runId) then Left(s"manifest run ID ${m.runId} is invalid")
        else Right(m)
      )

enum WriteOutcome:
  case Created, Unchanged

enum ArtifactError:
  /** The key exists with different content; nothing was overwritten. */
  case Conflict(uri: String, existingSha256: String, newSha256: String)
  case Failure(message: String)

/** Idempotent writer: create-only put, then a content-hash comparison on replay. */
final class ArtifactWriter(store: ObjectStore, layout: S3Layout):

  def write(
      zone: Zone,
      dataset: String,
      runId: String,
      name: String,
      bytes: Array[Byte],
      contentType: String,
      sources: Vector[SourceRef] = Vector.empty
  ): Either[ArtifactError, (StoredObject, WriteOutcome)] =
    layout.runKey(zone, dataset, runId, name).left.map(ArtifactError.Failure(_)).flatMap { uri =>
      writeAt(uri, bytes, contentType, sources)
    }

  def writeAt(
      uri: String,
      bytes: Array[Byte],
      contentType: String,
      sources: Vector[SourceRef]
  ): Either[ArtifactError, (StoredObject, WriteOutcome)] =
    val hash = Hashing.sha256Hex(bytes)
    val obj = StoredObject(uri, hash, bytes.length.toLong, contentType, sources)
    store.putIfAbsent(uri, bytes, contentType).left.map(ArtifactError.Failure(_)).flatMap {
      case PutOutcome.Created       => Right(obj -> WriteOutcome.Created)
      case PutOutcome.AlreadyExists =>
        store.get(uri).left.map(ArtifactError.Failure(_)).flatMap { existing =>
          val eh = Hashing.sha256Hex(existing)
          if eh == hash then Right(obj -> WriteOutcome.Unchanged)
          else Left(ArtifactError.Conflict(uri, eh, hash))
        }
    }

  def writeManifest(m: StorageManifest): Either[ArtifactError, (StoredObject, WriteOutcome)] =
    for
      zone <- Zone.values
        .find(_.prefix == m.zone)
        .toRight(ArtifactError.Failure(s"unknown zone ${m.zone}"))
      uri <- layout.manifestUri(zone, m.dataset, m.runId).left.map(ArtifactError.Failure(_))
      _ <- Either.cond(
        m.schemaVersion == StorageManifest.Version,
        (),
        ArtifactError.Failure(s"unsupported manifest version ${m.schemaVersion}")
      )
      r <- writeAt(uri, upickle.default.write(m).getBytes(UTF_8), "application/json", Vector.empty)
    yield r

  def readManifest(zone: Zone, dataset: String, runId: String): Either[String, StorageManifest] =
    for
      uri <- layout.manifestUri(zone, dataset, runId)
      text <- store.getText(uri)
      m <- StorageManifest.parse(text)
      _ <- Either.cond(m.runId == runId, (), s"manifest at $uri names run ${m.runId}")
    yield m

  /** URIs whose current content hash differs from the manifest (or that cannot be read). */
  def verify(m: StorageManifest): Vector[String] =
    m.objects.collect {
      case o if store.get(o.uri).map(Hashing.sha256Hex(_)) != Right(o.sha256) => o.uri
    }
