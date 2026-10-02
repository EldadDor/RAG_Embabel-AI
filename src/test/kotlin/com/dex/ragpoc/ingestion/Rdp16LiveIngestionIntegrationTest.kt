package com.dex.ragpoc.ingestion

import com.dex.ragpoc.catalog.JdbcDocumentPublicationRepository
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.parsing.PresentationFixtures
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.retrieval.JdbcRetrievalRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.awt.Color
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Database-only opt-in proof; deterministic embeddings never invoke a provider. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "RDP16_LIVE_INTEGRATION", matches = "true")
class Rdp16LiveIngestionIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var sources: JdbcSourceDocumentRepository

    @Autowired private lateinit var chunks: JdbcChunkRepository

    @Autowired private lateinit var assets: JdbcDocumentAssetRepository

    @Autowired private lateinit var publications: JdbcDocumentPublicationRepository

    @Autowired private lateinit var properties: AppProperties

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var retrieval: JdbcRetrievalRepository

    @Test
    fun `persists presentation slide assets and rolls back failed replacement`() {
        val root = Path.of("target", "rdp16-live-input").toAbsolutePath().normalize()
        val source = PresentationFixtures.write(root.resolve("guide.pptx"))
        jdbc.update(
            "INSERT INTO rag.workspaces (workspace_id, display_name) VALUES (?, ?) ON CONFLICT (workspace_id) DO NOTHING",
            WORKSPACE,
            "Kotlin PPTX Integration",
        )
        val request = IngestionRequest(WORKSPACE, source, rootPath = root)
        val result = service(assets).ingest(request)
        val before = requireNotNull(sources.find(WORKSPACE, "default", result.documentId))
        assertEquals("pptx", before.metadata["document_format"])
        val retrieved = retrieval.lexical("Closing", WORKSPACE, "default", properties.database.chunkTable, 5)
        assertTrue(retrieved.any { it.documentId == result.documentId && it.page == 1 && it.text.contains("Closing") })
        val count =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM rag.chunk_assets link
                JOIN rag.document_assets asset ON asset.asset_id = link.asset_id
                JOIN rag.${properties.database.chunkTable} chunk ON chunk.metadata ->> 'chunk_id' = link.chunk_id
                    AND chunk.metadata ->> 'workspace_id' = link.workspace_id
                WHERE link.workspace_id = ? AND link.doc_id = ? AND chunk.page_number = 3
                """.trimIndent(),
                Int::class.java,
                WORKSPACE,
                result.documentId,
            )
        assertEquals(2, count)
        PresentationFixtures.write(source, PresentationFixtures.image(Color.BLUE))
        val failingAssets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        every { failingAssets.deleteForDocument(any(), any(), any()) } answers {
            assets.deleteForDocument(arg(0), arg(1), arg(2))
        }
        every { failingAssets.upsert(any()) } throws IllegalStateException("forced RDP-16 rollback")
        assertFailsWith<IllegalStateException> { service(failingAssets).ingest(request) }
        assertEquals(before.contentHash, sources.find(WORKSPACE, "default", result.documentId)?.contentHash)
        assertEquals(
            count,
            jdbc.queryForObject(
                "SELECT count(*) FROM rag.chunk_assets WHERE workspace_id = ? AND doc_id = ?",
                Int::class.java,
                WORKSPACE,
                result.documentId,
            ),
        )
    }

    private fun service(assetRepository: JdbcDocumentAssetRepository): DocumentIngestionService {
        val assetProperties = properties.copy(assets = properties.assets.copy(storageRoot = Path.of("target", "rdp16-live-assets")))
        return DocumentIngestionService(
            DocumentLoaderRegistry(),
            DocumentChunker(),
            EmbeddingGateway { _, texts -> texts.map { List(properties.database.vectorDimension) { if (it == 0) 1f else 0f } } },
            ContentAddressedAssetStore(assetProperties),
            sources,
            chunks,
            assetRepository,
            properties,
            transactions,
            publications = publications,
        )
    }

    private companion object {
        const val WORKSPACE = "kotlin-rdp16-pptx-v1"
    }
}
