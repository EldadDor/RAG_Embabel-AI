package com.dex.ragpoc.retrieval

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.domain.RetrievedChunk
import com.dex.ragpoc.providers.ProfiledEmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetrievalServiceTest {
    @Test
    fun `hybrid retrieval filters then fuses deterministic rankings`() {
        val embeddings = mockk<ProfiledEmbeddingService>()
        val repository = mockk<RetrievalRepository>()
        every { embeddings.resolveReady("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")
        every { embeddings.query("profile", "PG_HOST") } returns listOf(0.1f, 0.2f)
        every { repository.semantic(any(), "alpha", "default", "chunks", 20) } returns
            listOf(chunk("semantic", .9), chunk("shared", .8), chunk("low", .2))
        every { repository.lexical("PG_HOST", "alpha", "default", "chunks", 20) } returns listOf(chunk("shared", .7), chunk("keyword", .6))

        val result = service(embeddings, repository).retrieve("PG_HOST", "alpha", modelProfile = "profile", topK = 3)

        assertEquals(listOf("shared", "semantic", "keyword"), result.chunks.map { it.chunkId })
        verify { repository.lexical("PG_HOST", "alpha", "default", "chunks", 20) }
    }

    @Test
    fun `non hybrid retrieval retains semantic order`() {
        val embeddings = mockk<ProfiledEmbeddingService>()
        val repository = mockk<RetrievalRepository>()
        every { embeddings.resolveReady("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")
        every { embeddings.query("profile", "question") } returns listOf(.1f, .2f)
        every { repository.semantic(any(), any(), any(), any(), any()) } returns listOf(chunk("first", .9), chunk("second", .8))

        val result = service(embeddings, repository, hybrid = false).retrieve("question", "alpha", modelProfile = "profile")

        assertEquals(listOf("first", "second"), result.chunks.map { it.chunkId })
        verify(exactly = 0) { repository.lexical(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `enabled reranker can reorder candidates before trimming`() {
        val embeddings = mockk<ProfiledEmbeddingService>()
        val repository = mockk<RetrievalRepository>()
        every { embeddings.resolveReady("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")
        every { embeddings.query("profile", "question") } returns listOf(.1f, .2f)
        every { repository.semantic(any(), any(), any(), any(), any()) } returns listOf(chunk("first", .9), chunk("second", .8))

        val result =
            service(embeddings, repository, hybrid = false, rerank = true, reranker = Reranker { _, candidates -> candidates.reversed() })
                .retrieve("question", "alpha", modelProfile = "profile", topK = 1)

        assertEquals(listOf("second"), result.chunks.map { it.chunkId })
    }

    @Test
    fun `database retrieval failure propagates without invoking lexical search`() {
        val embeddings = mockk<ProfiledEmbeddingService>()
        val repository = mockk<RetrievalRepository>()
        every { embeddings.resolveReady("profile") } returns ModelProfile("profile", "ollama", "bge", 2, "chunks")
        every { embeddings.query("profile", "question") } returns listOf(.1f, .2f)
        every { repository.semantic(any(), any(), any(), any(), any()) } throws IllegalStateException("database timeout")

        assertFailsWith<IllegalStateException> { service(embeddings, repository).retrieve("question", "alpha", modelProfile = "profile") }

        verify(exactly = 0) { repository.lexical(any(), any(), any(), any(), any()) }
    }

    private fun service(
        embeddings: ProfiledEmbeddingService,
        repository: RetrievalRepository,
        hybrid: Boolean = true,
        rerank: Boolean = false,
        reranker: Reranker? = null,
    ) = RetrievalService(
        embeddings,
        repository,
        AppProperties(rag = AppProperties.Rag(modelProfile = "profile", hybridSearchEnabled = hybrid, rerankEnabled = rerank)),
        reranker,
    )

    private fun chunk(
        id: String,
        score: Double,
    ) = RetrievedChunk(id, "doc-$id", "$id.md", id, score)
}
