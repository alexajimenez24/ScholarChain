package scholarchain.rag

import scholarchain.core.{ModelConfig, VectorConfig}
import scholarchain.rag.EmbeddingModelVersion.{decide, nextIndexName}
import scholarchain.storage.{EmbeddingVersion, HashingEmbedder}

class EmbeddingVersionSuite extends munit.FunSuite:
  private val fake = EmbeddingModelVersion.of("hash-embed-v1", 256).toOption.get
  private val h1 = "sha256:" + "1" * 64
  private val h2 = "sha256:" + "2" * 64

  test("the version is model ID and dimension: <modelId>@<dimension>") {
    assertEquals(fake.id, "hash-embed-v1@256")
    assertEquals(
      EmbeddingModelVersion.of("amazon.titan-embed-text-v2:0", 1024).map(_.id),
      Right("amazon.titan-embed-text-v2:0@1024")
    )
    assertEquals(
      EmbeddingModelVersion.parse("amazon.titan-embed-text-v2:0@256").map(_.dimension),
      Right(256)
    )
    assertEquals(EmbeddingModelVersion.parse(fake.id), Right(fake))
  }

  test("malformed versions are refused") {
    assert(EmbeddingModelVersion.of("", 256).isLeft)
    assert(EmbeddingModelVersion.of("a b", 256).isLeft)
    assert(EmbeddingModelVersion.of("a@b", 256).isLeft)
    assert(EmbeddingModelVersion.of("m", 0).isLeft)
    assert(EmbeddingModelVersion.of("m", 4097).isLeft)
    assert(EmbeddingModelVersion.parse("m").isLeft)
    assert(EmbeddingModelVersion.parse("m@025").isLeft)
    assert(EmbeddingModelVersion.parse("m@x").isLeft)
  }

  test("from configuration; an unset model ID is an error, never a default") {
    val v = VectorConfig("b", Map.empty, 256)
    assertEquals(
      EmbeddingModelVersion.fromConfig(ModelConfig("hash-embed-v1", "", "", false), v),
      Right(fake)
    )
    assert(EmbeddingModelVersion.fromConfig(ModelConfig("", "", "", false), v).isLeft)
  }

  test("decisions: absent embeds, unchanged skips, other text is refused") {
    assertEquals(decide(None, fake, h1), EmbedDecision.Embed)
    assertEquals(decide(Some(StoredEmbedding(fake.id, h1)), fake, h1), EmbedDecision.Skip)
    assertEquals(
      decide(Some(StoredEmbedding(fake.id, h2)), fake, h1),
      EmbedDecision.RefuseOtherText
    )
  }

  test("precedence: another text is refused before a version mismatch is considered") {
    val titan = EmbeddingModelVersion.of("amazon.titan-embed-text-v2:0", 256).toOption.get
    assertEquals(
      decide(Some(StoredEmbedding(fake.id, h2)), titan, h1),
      EmbedDecision.RefuseOtherText
    )
  }

  test("a model or dimension change forces a new index version, for every key") {
    val titan = EmbeddingModelVersion.of("amazon.titan-embed-text-v2:0", 256).toOption.get
    val wider = EmbeddingModelVersion.of("hash-embed-v1", 512).toOption.get
    assertEquals(
      decide(Some(StoredEmbedding(fake.id, h1)), titan, h1),
      EmbedDecision.NewIndexVersion("hash-embed-v1@256", "amazon.titan-embed-text-v2:0@256")
    )
    assertEquals(
      decide(Some(StoredEmbedding(fake.id, h1)), wider, h1),
      EmbedDecision.NewIndexVersion("hash-embed-v1@256", "hash-embed-v1@512")
    )
    assertEquals(nextIndexName("researcher-profile-v1"), Right("researcher-profile-v2"))
    assertEquals(nextIndexName("paper-abstract-v9"), Right("paper-abstract-v10"))
    assert(nextIndexName("researcher-profile").isLeft)
  }

  test("a template version change with byte-identical text keeps the key and skips") {
    val rp = RenderFixtures.summary("researcher-profile", "2010-2014")
    val d = RenderFixtures.renderer.renderSummary(rp).toOption.get
    val bumped = d.copy(template = "tpl-researcher-profile-v2")
    assertEquals(bumped.chunks.map(_.chunkId), d.chunks.map(_.chunkId))
    val c = d.chunks.head
    assertEquals(
      decide(Some(StoredEmbedding(fake.id, c.textHash)), fake, c.textHash),
      EmbedDecision.Skip
    )
  }

  test("one formula: rag's version ID is the one storage stores and compares") {
    val stored = new HashingEmbedder(256).versionId // what the loader writes to embeddingModel
    assertEquals(stored, "hash-embed-v1@256")
    assertEquals(fake.id, stored)
    assertEquals(EmbeddingModelVersion.parse(stored), Right(fake))
    for (m, d) <- Seq("amazon.titan-embed-text-v2:0" -> 1024, "hash-embed-v1" -> 16, "m" -> 1) do
      assertEquals(EmbeddingModelVersion.of(m, d).map(_.id), Right(EmbeddingVersion.id(m, d)))
  }
