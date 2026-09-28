package com.dex.ragpoc.providers

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.persistence.JdbcEmbeddingCacheRepository
import com.dex.ragpoc.persistence.JdbcModelProfileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.ai.embedding.EmbeddingModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfiledEmbeddingServiceTest {
    @Test
    fun `query applies prefix and caches a compatible embedding`() {
        val profiles = mockk<JdbcModelProfileRepository>()
        val cache = mockk<JdbcEmbeddingCacheRepository>()
        val model = mockk<EmbeddingModel>()
        val profile = ModelProfile("profile", "ollama", "bge", 2, "chunks", queryPrefix = "query: ")
        every { profiles.get("profile") } returns profile
        every { cache.get(any(), 2) } returns null
        every { model.embed(listOf("query: hello")) } returns listOf(floatArrayOf(0.1f, 0.2f))
        every { cache.put(any(), "ollama", "bge", 2, listOf(0.1f, 0.2f)) } returns Unit

        assertEquals(listOf(0.1f, 0.2f), service(profiles, cache, model).query("profile", "hello"))
        verify { cache.put(any(), "ollama", "bge", 2, listOf(0.1f, 0.2f)) }
    }

    @Test
    fun `cache hit avoids a provider call`() {
        val profiles = mockk<JdbcModelProfileRepository>()
        val cache = mockk<JdbcEmbeddingCacheRepository>()
        val model = mockk<EmbeddingModel>()
        every { profiles.get("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")
        every { cache.get(any(), 2) } returns listOf(0.3f, 0.4f)

        assertEquals(listOf(0.3f, 0.4f), service(profiles, cache, model).document("profile", "cached"))
        verify(exactly = 0) { model.embed(any<List<String>>()) }
    }

    @Test
    fun `wrong provider dimensions are rejected before cache write`() {
        val profiles = mockk<JdbcModelProfileRepository>()
        val cache = mockk<JdbcEmbeddingCacheRepository>()
        val model = mockk<EmbeddingModel>()
        every { profiles.get("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")
        every { cache.get(any(), 2) } returns null
        every { model.embed(any<List<String>>()) } returns listOf(floatArrayOf(0.1f))

        assertFailsWith<IllegalArgumentException> { service(profiles, cache, model).document("profile", "text") }
        verify(exactly = 0) { cache.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `warming a draft profile uses a probe without chunking`() {
        val profiles = mockk<JdbcModelProfileRepository>()
        val cache = mockk<JdbcEmbeddingCacheRepository>()
        val model = mockk<EmbeddingModel>()
        every { profiles.get("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks", status = "draft")
        every { profiles.setStatus("profile", any()) } returns Unit
        every { cache.get(any(), 2) } returns null
        every { cache.put(any(), "ollama", "bge", 2, listOf(0.1f, 0.2f)) } returns Unit
        every { model.embed(listOf("model profile warmup")) } returns listOf(floatArrayOf(0.1f, 0.2f))

        assertEquals("ready", service(profiles, cache, model).warm("profile").status)

        verify { profiles.setStatus("profile", "warming") }
        verify { profiles.setStatus("profile", "ready") }
    }

    @Test
    fun `incompatible active provider is rejected before a model call`() {
        val profiles = mockk<JdbcModelProfileRepository>()
        val cache = mockk<JdbcEmbeddingCacheRepository>()
        val model = mockk<EmbeddingModel>()
        every { profiles.get("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")

        assertFailsWith<IllegalArgumentException> {
            service(profiles, cache, model, provider = "azure_openai").document("profile", "text")
        }
        verify(exactly = 0) { model.embed(any<List<String>>()) }
    }

    @Test
    fun `cache-disabled embedding avoids cache reads and writes`() {
        val profiles = mockk<JdbcModelProfileRepository>()
        val cache = mockk<JdbcEmbeddingCacheRepository>()
        val model = mockk<EmbeddingModel>()
        every { profiles.get("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")
        every { model.embed(listOf("text")) } returns listOf(floatArrayOf(0.1f, 0.2f))

        service(profiles, cache, model, cacheEnabled = false).document("profile", "text")

        verify(exactly = 0) { cache.get(any(), any()) }
        verify(exactly = 0) { cache.put(any(), any(), any(), any(), any()) }
    }

    private fun service(
        profiles: JdbcModelProfileRepository,
        cache: JdbcEmbeddingCacheRepository,
        model: EmbeddingModel,
        provider: String = "ollama",
        cacheEnabled: Boolean = true,
    ): ProfiledEmbeddingService =
        ProfiledEmbeddingService(
            profiles,
            cache,
            model,
            AppProperties(embedding = AppProperties.Embedding(provider = provider, cacheEnabled = cacheEnabled)),
        )
}
