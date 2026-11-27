package scholarchain.rag

import scholarchain.core.VectorConfig
import scholarchain.storage.{DocumentRenderer, RendererProvider}

/** The storage pipeline's renderer, found by `java.util.ServiceLoader` through
  * `META-INF/services/scholarchain.storage.RendererProvider`. Storage cannot import rag, so this is
  * how `load-vectors` and `enqueue-embeddings` get the versioned semantic templates.
  */
final class SemanticRendererProvider extends RendererProvider:
  def renderer(cfg: VectorConfig): Either[String, DocumentRenderer] = SemanticRenderer(cfg)
