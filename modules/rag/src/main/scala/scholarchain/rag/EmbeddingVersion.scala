package scholarchain.rag

import scholarchain.core.{ModelConfig, VectorConfig}
import scholarchain.storage.{EmbeddingVersion, VectorLimits}

/** The embedding model version stored with every vector: `<modelId>@<dimension>`, for example
  * `hash-embed-v1@256` (the local fake) or `amazon.titan-embed-text-v2:0@256`.
  *
  * Rule (embedding-version-v1):
  *   - The version is the model ID together with the output dimension; both come from configuration
  *     (`models.embeddingModel`, `vectors.dimension`), never from a request.
  *   - One vector index version (the `-vN` suffix of `vectors.indexes`) holds vectors of exactly
  *     one embedding version. Vectors of two models, or of one model at two dimensions, are not
  *     comparable, and an S3 Vectors index has a fixed dimension.
  *   - A change of model ID or dimension forces a re-embed of every document into a new index
  *     version ([[EmbeddingModelVersion.nextIndexName]]); nothing in the old index is overwritten.
  *   - A changed text is a new vector key (the chunk ID hashes the text), so only that key is
  *     embedded. A template, chunk-policy or run change that leaves a chunk's text byte-identical
  *     keeps its key and is skipped.
  *   - A key that already holds a different text is refused, never overwritten. This is checked
  *     before the version: a key holding another text under another version is refused too.
  */
final case class EmbeddingModelVersion(modelId: String, dimension: Int):
  /** Storage's formula ([[scholarchain.storage.EmbeddingVersion.id]], also `Embedder.versionId`),
    * called rather than restated, so the version rag reasons about is the one the loader stores in
    * `embeddingModel` and compares when it skips.
    */
  def id: String = EmbeddingVersion.id(modelId, dimension)

/** What the loader does with one chunk, given what its index already holds under the key. */
enum EmbedDecision:
  /** The key is absent: embed and write. */
  case Embed

  /** Same key, same text hash, same embedding version: no embedding call, no write. */
  case Skip

  /** The key holds another text: refuse, never overwrite. */
  case RefuseOtherText

  /** The key was embedded under another version: re-embed everything into a new index version. */
  case NewIndexVersion(stored: String, current: String)

/** What a vector index holds under one key: its embedding version ID and text hash. */
final case class StoredEmbedding(versionId: String, textHash: String)

object EmbeddingModelVersion:
  val RuleVersion = "embedding-version-v1"
  private val IndexName = "^(.+)-v([1-9][0-9]{0,5})$".r

  def of(modelId: String, dimension: Int): Either[String, EmbeddingModelVersion] =
    if modelId.trim.isEmpty then Left("embedding model ID is empty")
    else if modelId.exists(c => c.isWhitespace || c == '@') then
      Left(s"embedding model ID '$modelId' contains whitespace or '@'")
    else if dimension < 1 || dimension > VectorLimits.MaxDimension then
      Left(s"dimension $dimension is outside 1..${VectorLimits.MaxDimension}")
    else Right(EmbeddingModelVersion(modelId, dimension))

  /** The configured version; an unset model ID (local mode) is an error, not a default. */
  def fromConfig(
      models: ModelConfig,
      vectors: VectorConfig
  ): Either[String, EmbeddingModelVersion] =
    of(models.embeddingModel, vectors.dimension)

  def parse(id: String): Either[String, EmbeddingModelVersion] =
    id.lastIndexOf('@') match
      case -1 => Left(s"embedding version '$id' has no '@<dimension>'")
      case i  =>
        id.substring(i + 1).toIntOption match
          case Some(d) if id.substring(i + 1) == d.toString => of(id.substring(0, i), d)
          case _ => Left(s"embedding version '$id' has a malformed dimension")

  /** The loader's precedence (`VectorLoader.processBatch`), pinned by IngestionConformanceSuite: an
    * absent key embeds; a key holding another text is refused whatever its version; a key holding
    * this text under another version needs a new index version; otherwise it is skipped.
    */
  def decide(
      stored: Option[StoredEmbedding],
      current: EmbeddingModelVersion,
      textHash: String
  ): EmbedDecision =
    stored match
      case None                                 => EmbedDecision.Embed
      case Some(s) if s.textHash != textHash    => EmbedDecision.RefuseOtherText
      case Some(s) if s.versionId != current.id =>
        EmbedDecision.NewIndexVersion(s.versionId, current.id)
      case Some(_) => EmbedDecision.Skip

  /** `researcher-profile-v1` to `researcher-profile-v2`: the index of the next embedding version.
    */
  def nextIndexName(name: String): Either[String, String] =
    name match
      case IndexName(base, n) => Right(s"$base-v${n.toInt + 1}")
      case _                  => Left(s"index name '$name' does not end in -v<N>")
