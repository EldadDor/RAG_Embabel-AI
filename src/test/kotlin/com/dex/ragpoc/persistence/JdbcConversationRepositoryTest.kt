package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class JdbcConversationRepositoryTest {
    private val repository = JdbcConversationRepository(mockk<JdbcTemplate>(relaxed = true), AppProperties())

    @Test
    fun `rejects unsupported conversation roles before writing`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.appendTurn("session-1", "system", "not persisted")
        }
    }

    @Test
    fun `rejects an empty conversation turn before writing`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.appendTurn("session-1", "user", "   ")
        }
    }

    @Test
    fun `bounds recent conversation history requests`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.recentTurns("session-1", 101)
        }
    }

    @Test
    fun `rejects a chunk with an incompatible embedding dimension before writing`() {
        val chunks = JdbcChunkRepository(mockk<JdbcTemplate>(relaxed = true), ObjectMapper(), AppProperties())

        assertThrows(IllegalArgumentException::class.java) {
            chunks.upsert(
                StoredChunk(UUID.randomUUID(), "fixture content", mapOf("document_id" to "fixture-doc")),
                listOf(0.0f),
            )
        }
    }

    @Test
    fun `selected storage target is validated and used for chunk writes`() {
        val jdbc = mockk<JdbcTemplate>()
        val sql = slot<String>()
        every { jdbc.update(capture(sql), *anyVararg()) } returns 1
        val chunks = JdbcChunkRepository(jdbc, ObjectMapper(), AppProperties())
        val chunk = StoredChunk(UUID.randomUUID(), "fixture content", mapOf("document_id" to "fixture-doc"))

        chunks.upsert(chunk, listOf(0.1f, 0.2f, 0.3f), "document_chunks_alternate", 3)
        assertTrue(sql.captured.contains("INSERT INTO rag.document_chunks_alternate"))
        assertThrows(IllegalArgumentException::class.java) {
            chunks.upsert(chunk, listOf(0.1f, 0.2f, 0.3f), "other;drop", 3)
        }
    }

    @Test
    fun `rejects an asset with a negative byte size before writing`() {
        val assets = JdbcDocumentAssetRepository(mockk<JdbcTemplate>(relaxed = true), AppProperties())

        assertThrows(IllegalArgumentException::class.java) {
            assets.upsert(
                StoredDocumentAsset(
                    assetId = "fixture-asset",
                    workspaceId = "fixture-workspace",
                    chunkingProfile = "default",
                    documentId = "fixture-doc",
                    storageKey = "fixtures/asset.bin",
                    contentHash = "abc",
                    mediaType = "application/octet-stream",
                    byteSize = -1,
                ),
            )
        }
    }
}
