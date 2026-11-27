package scholarchain.rag

import scholarchain.rag.RenderFixtures.*
import scholarchain.storage.RendererProvider
import scala.jdk.CollectionConverters.*

class RendererProviderSuite extends munit.FunSuite:

  private def found: Vector[RendererProvider] =
    java.util.ServiceLoader.load(classOf[RendererProvider]).asScala.toVector

  test("ServiceLoader finds exactly one RendererProvider, the rag one") {
    val providers = found
    assertEquals(providers.size, 1)
    assert(providers.head.isInstanceOf[SemanticRendererProvider])
  }

  test("the provider builds the semantic renderer from the vector config") {
    found.head.renderer(vectors) match
      case Right(r: SemanticRenderer) => assertEquals(r.policy, renderer.policy)
      case other                      => fail(s"expected a SemanticRenderer, got $other")
  }

  test("the provider passes a bad vector config through as a Left") {
    val r = found.head.renderer(vectors.copy(indexes = Map.empty))
    assert(r.isLeft, r.toString)
  }
