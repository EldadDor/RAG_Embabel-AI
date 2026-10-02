package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.DocumentAsset
import com.dex.ragpoc.domain.SourceType
import com.dex.ragpoc.ingestion.ContentAddressedAssetStore
import com.dex.ragpoc.ingestion.DocumentIngestionService
import com.dex.ragpoc.ingestion.EmbeddingGateway
import com.dex.ragpoc.ingestion.IngestionModelTarget
import com.dex.ragpoc.ingestion.IngestionModelTargetResolver
import com.dex.ragpoc.ingestion.IngestionRequest
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoader
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.persistence.StoredChunk
import com.dex.ragpoc.persistence.StoredDocumentAsset
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogIngestionTest {
    @TempDir lateinit var root: Path
    private val publication = mockk<JdbcDocumentPublicationRepository>(relaxed = true)
    private val chunks = mockk<JdbcChunkRepository>(relaxed = true)
    private val sources = mockk<JdbcSourceDocumentRepository>(relaxed = true)
    private val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
    private val template = mockk<TransactionTemplate>()

    init {
        every { publication.unchanged(any(), any(), any(), any(), any()) } returns false
        every { template.executeWithoutResult(any()) } answers {
            firstArg<java.util.function.Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
        }
    }

    private fun service(
        content: String = "content",
        image: Boolean = false,
    ): DocumentIngestionService {
        val bytes = "image".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val properties =
            AppProperties(
                database = AppProperties.Database(vectorDimension = 2),
                assets = AppProperties.Assets(storageRoot = root.resolve("assets")),
            )
        return DocumentIngestionService(
            DocumentLoaderRegistry(
                mapOf(
                    ".txt" to
                        DocumentLoader { path ->
                            Document(
                                "doc",
                                path.toString(),
                                SourceType.TEXT,
                                content,
                                assets = if (image) listOf(DocumentAsset("image-0000", "rId1", bytes, hash, "image/png")) else emptyList(),
                            )
                        },
                ),
            ),
            DocumentChunker(),
            EmbeddingGateway { _, texts -> texts.map { listOf(.1f, .2f) } },
            ContentAddressedAssetStore(properties),
            sources,
            chunks,
            assets,
            properties,
            template,
            modelTargets = IngestionModelTargetResolver { IngestionModelTarget("chunks_alternate", 2) },
            publications = publication,
        )
    }

    @Test
    fun `catalog wired ingestion publishes canonical vectors then owned immutable assets`() {
        val source = Files.writeString(root.resolve("guide.txt"), "version 1")
        val vectors = mutableListOf<StoredChunk>()
        val images = mutableListOf<StoredDocumentAsset>()
        every { chunks.upsert(capture(vectors), any(), any(), any()) } returns Unit
        every { assets.upsert(capture(images)) } returns Unit
        val ingestion = service(image = true)
        ingestion.ingest(IngestionRequest("space", source, modelProfile = "alternate"))
        Files.writeString(source, "version 2")
        ingestion.ingest(IngestionRequest("space", source, modelProfile = "alternate"))
        assertEquals("doc", vectors.first().metadata["doc_id"])
        assertTrue(vectors.first().metadata["related_asset_ids"] == listOf(images.first().assetId))
        assertTrue(images[0].assetId != images[1].assetId)
        verify(exactly = 0) { assets.deleteForDocument(any(), any(), any()) }
        verifyOrder {
            publication.lockProfile("alternate")
            publication.lockDocument("space", "default", "doc")
            publication.requireReady("alternate")
            chunks.deleteForDocument("space", "default", "doc", "chunks_alternate")
            chunks.upsert(any(), any(), "chunks_alternate", 2)
            publication.publish("space", "alternate", "default", "chunks_alternate", any(), null)
            assets.upsert(any())
            publication.replaceAssetReferences("space", "alternate", "default", "doc", any())
        }
    }

    @Test
    fun `concurrently published same hash is skipped under shared lock`() {
        val source = Files.writeString(root.resolve("guide.txt"), "content")
        every { publication.unchanged(any(), any(), any(), any(), any()) } returnsMany listOf(false, true)
        val result = service().ingest(IngestionRequest("space", source))
        assertFalse(result.changed)
        assertEquals("unchanged", result.skipReason)
        verify(exactly = 0) { publication.publish(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { chunks.upsert(any(), any(), any(), any()) }
    }

    @Test
    fun `zero chunk success is published while dry run performs no catalog access`() {
        val source = Files.writeString(root.resolve("empty.txt"), "")
        val ingestion = service(content = "")
        ingestion.ingest(IngestionRequest("space", source, dryRun = true))
        verify(exactly = 0) { publication.unchanged(any(), any(), any(), any(), any()) }
        ingestion.ingest(IngestionRequest("space", source))
        verify(exactly = 1) { publication.publish("space", "bge-m3", "default", "chunks_alternate", any(), null) }
        verify(exactly = 0) { chunks.upsert(any(), any(), any(), any()) }
    }

    @Test
    fun `cleanup respects scan coverage refreshed rows and selected model publication`() {
        val nested = Files.createDirectory(root.resolve("nested"))
        val stamp = Instant.parse("2026-10-03T10:00:00Z")
        every { publication.listForRoot("space", "alternate", "default", root.toAbsolutePath().toString()) } returns
            listOf(
                PublishedSource("gone", root.resolve("gone.txt").toString(), "hash", stamp),
                PublishedSource("nested", nested.resolve("gone.txt").toString(), "hash", stamp),
                PublishedSource("new", root.resolve("new.txt").toString(), "hash", stamp.plusSeconds(1)),
            )
        every { publication.deletePublication("space", "alternate", "default", "gone", stamp) } returns true
        val result = service().cleanupMissingSources("space", root, modelProfile = "alternate", recursive = false, scanStarted = stamp)
        assertEquals(listOf("gone"), result.deletedDocumentIds)
        verify { chunks.deleteForDocument("space", "default", "gone", "chunks_alternate") }
        verify(exactly = 0) { sources.delete(any(), any(), any()) }
        verify(exactly = 0) { assets.deleteForDocument(any(), any(), any()) }
    }
}
