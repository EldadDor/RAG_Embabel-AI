package com.dex.ragpoc.persistence

import com.dex.ragpoc.config.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
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
