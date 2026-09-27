package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.DocumentAsset
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.domain.SourceType
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoader
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Opt-in live check; invoke only with RDP09_LIVE_INTEGRATION=true and RDP09_SOURCE_PATH set. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "RDP09_LIVE_INTEGRATION", matches = "true")
class Rdp09LiveIngestionIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var embeddingModel: EmbeddingModel

    @Autowired
    private lateinit var sourceDocuments: JdbcSourceDocumentRepository

    @Autowired
    private lateinit var chunks: JdbcChunkRepository

    @Autowired
    private lateinit var assets: JdbcDocumentAssetRepository

    @Autowired
    private lateinit var properties: AppProperties

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Test
    fun `ingests supplied PDF into the dedicated RDP-09 workspace`() {
        val source = Path.of(requireNotNull(System.getenv("RDP09_SOURCE_PATH"))).toAbsolutePath().normalize()
        require(Files.isRegularFile(source)) { "RDP09_SOURCE_PATH is not a file: $source" }
        ensureWorkspace()
        val service = ingestionService()

        val result = service.ingest(IngestionRequest(WORKSPACE_ID, source, chunkingProfile = CHUNKING_PROFILE))
        val stored = sourceDocuments.find(WORKSPACE_ID, CHUNKING_PROFILE, result.documentId)
        val storedChunkCount =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM rag.${properties.database.chunkTable}
                    WHERE metadata ->> 'workspace_id' = ?
                      AND metadata ->> 'chunking_profile' = ?
                      AND metadata ->> 'document_id' = ?
                    """.trimIndent(),
                    Int::class.java,
                    WORKSPACE_ID,
                    CHUNKING_PROFILE,
                    result.documentId,
                ),
            )

        assertTrue(!result.dryRun)
        assertTrue(storedChunkCount > 0)
        if (result.changed) {
            assertEquals(result.chunkCount, storedChunkCount)
        } else {
            assertEquals(0, result.chunkCount)
        }
        assertEquals("pdf", stored?.sourceType?.name?.lowercase())
    }

    @Test
    fun `removes a stale nested PDF from the dedicated workspace`() {
        val original = Path.of(requireNotNull(System.getenv("RDP09_SOURCE_PATH"))).toAbsolutePath().normalize()
        require(Files.isRegularFile(original)) { "RDP09_SOURCE_PATH is not a file: $original" }
        val root = Path.of("target", "rdp09-live-input").toAbsolutePath().normalize()
        val source = root.resolve("nested/research_bikes.pdf")
        Files.createDirectories(source.parent)
        Files.copy(original, source, StandardCopyOption.REPLACE_EXISTING)
        ensureWorkspace()
        val service = ingestionService()

        val ingestion = service.ingest(IngestionRequest(WORKSPACE_ID, source, root, CHUNKING_PROFILE))
        Files.delete(source)
        val cleanup = service.cleanupMissingSources(WORKSPACE_ID, root, CHUNKING_PROFILE)

        assertEquals(listOf(ingestion.documentId), cleanup.deletedDocumentIds)
        assertEquals(null, sourceDocuments.find(WORKSPACE_ID, CHUNKING_PROFILE, ingestion.documentId))
    }

    @Test
    fun `rolls back real source and chunk writes when asset persistence fails`() {
        val root = Path.of("target", "rdp09-live-rollback").toAbsolutePath().normalize()
        val source = root.resolve("rollback.txt")
        val content = "This source must not survive a failed transactional replacement."
        val assetBytes = byteArrayOf(0x52, 0x44, 0x50, 0x30, 0x39)
        val documentId = "rdp09-live-rollback-v1"
        Files.createDirectories(source.parent)
        Files.writeString(source, content)
        ensureWorkspace()
        val document =
            Document(
                documentId = documentId,
                sourcePath = source.toString(),
                sourceType = SourceType.TEXT,
                content = content,
                title = "Rollback proof",
                assets =
                    listOf(
                        DocumentAsset(
                            anchorId = "rollback-asset",
                            relationshipId = "rId1",
                            content = assetBytes,
                            contentHash = sha256(assetBytes),
                            mediaType = "image/png",
                            originalName = "rollback.png",
                        ),
                    ),
            )
        val failingAssets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        every { failingAssets.upsert(any()) } throws IllegalStateException("forced RDP-09 asset persistence failure")
        val rollbackProperties =
            properties.copy(
                assets = properties.assets.copy(storageRoot = Path.of("target", "rdp09-live-assets")),
            )
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(mapOf(".txt" to DocumentLoader { document })),
                DocumentChunker(),
                EmbeddingGateway { texts -> texts.map { embeddingModel.embed(it).toList() } },
                ContentAddressedAssetStore(rollbackProperties),
                sourceDocuments,
                chunks,
                failingAssets,
                rollbackProperties,
                transactions,
            )

        assertFailsWith<IllegalStateException> {
            service.ingest(IngestionRequest(WORKSPACE_ID, source, root, CHUNKING_PROFILE))
        }

        val persistedChunkCount =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM rag.${properties.database.chunkTable}
                    WHERE metadata ->> 'workspace_id' = ?
                      AND metadata ->> 'chunking_profile' = ?
                      AND metadata ->> 'document_id' = ?
                    """.trimIndent(),
                    Int::class.java,
                    WORKSPACE_ID,
                    CHUNKING_PROFILE,
                    documentId,
                ),
            )
        assertEquals(null, sourceDocuments.find(WORKSPACE_ID, CHUNKING_PROFILE, documentId))
        assertEquals(0, persistedChunkCount)
    }

    private fun ensureWorkspace() {
        jdbcTemplate.update(
            "INSERT INTO rag.workspaces (workspace_id, display_name) VALUES (?, ?) ON CONFLICT (workspace_id) DO NOTHING",
            WORKSPACE_ID,
            "Kotlin RDP-09 Bikes Integration",
        )
    }

    private fun ingestionService() =
        DocumentIngestionService(
            DocumentLoaderRegistry(),
            DocumentChunker(),
            EmbeddingGateway { texts -> texts.map { embeddingModel.embed(it).toList() } },
            ContentAddressedAssetStore(properties),
            sourceDocuments,
            chunks,
            assets,
            properties,
            transactions,
        )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val WORKSPACE_ID = "kotlin-rdp09-bikes-v1"
        const val CHUNKING_PROFILE = "default"
    }
}
