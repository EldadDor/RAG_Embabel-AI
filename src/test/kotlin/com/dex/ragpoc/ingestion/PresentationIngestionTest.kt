package com.dex.ragpoc.ingestion

import com.dex.ragpoc.api.ApiErrorAdvice
import com.dex.ragpoc.api.ParityIngestionController
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.identity.PrincipalResolver
import com.dex.ragpoc.parsing.ChunkingProfile
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.parsing.PresentationFixtures
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.persistence.StoredChunk
import com.dex.ragpoc.persistence.StoredDocumentAsset
import com.dex.ragpoc.workspace.WorkspaceAccessService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationIngestionTest {
    @TempDir lateinit var temp: Path
    private val sources = mockk<JdbcSourceDocumentRepository>(relaxed = true)
    private val chunks = mockk<JdbcChunkRepository>(relaxed = true)
    private val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
    private val transactions = mockk<TransactionTemplate>()
    private val persistedChunks = mutableListOf<StoredChunk>()
    private val persistedAssets = mutableListOf<StoredDocumentAsset>()
    private val links = mutableListOf<Pair<String, String>>()
    private var stored: SourceDocument? = null
    private var embeddingCalls = 0

    private fun service(failEmbeddings: Boolean = false): DocumentIngestionService {
        every { sources.find(any(), any(), any()) } answers { stored }
        every { sources.upsert(any()) } answers { stored = firstArg() }
        every { chunks.upsert(capture(persistedChunks), any(), any(), any()) } returns Unit
        every { assets.upsert(capture(persistedAssets)) } returns Unit
        every { assets.linkToChunk(any(), any(), any(), any(), any(), any()) } answers {
            links += arg<String>(3) to arg<String>(4)
        }
        every { transactions.executeWithoutResult(any()) } answers {
            firstArg<java.util.function.Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
        }
        val properties =
            AppProperties(
                database = AppProperties.Database(vectorDimension = 3),
                assets = AppProperties.Assets(storageRoot = temp.resolve("assets")),
            )
        return DocumentIngestionService(
            DocumentLoaderRegistry(),
            DocumentChunker(mapOf("default" to ChunkingProfile(), "small" to ChunkingProfile(chunkSize = 40, chunkOverlap = 5))),
            EmbeddingGateway { profile, texts ->
                embeddingCalls++
                assertEquals("selected", profile)
                if (failEmbeddings) error("provider unavailable")
                texts.map { listOf(1f, 0f, 0f) }
            },
            ContentAddressedAssetStore(properties),
            sources,
            chunks,
            assets,
            properties,
            transactions,
        )
    }

    @Test
    fun `HTTP ingestion accepts PPTX dry runs and safely rejects invalid presentations`() {
        val source = PresentationFixtures.write(temp.resolve("guide.pptx"))
        val properties = AppProperties(ingestion = AppProperties.Ingestion(listOf(temp)))
        val access = mockk<WorkspaceAccessService>()
        every { access.requireAccess(any(), any()) } answers { arg<String>(1) }
        val mvc =
            MockMvcBuilders
                .standaloneSetup(
                    ParityIngestionController(
                        PrincipalResolver(properties),
                        access,
                        IngestionSourcePolicy(properties),
                        service(),
                        properties,
                    ),
                ).setControllerAdvice(ApiErrorAdvice())
                .build()
        val body = """{"source_path":"${source.toString().replace("\\", "\\\\")}","dry_run":true,"model_profile":"selected"}"""
        mvc
            .perform(post("/ingest").contentType("application/json").content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.documents[0].assets_found").value(2))
            .andExpect(jsonPath("$.dry_run").value(true))
        Files.writeString(source, "not a presentation")
        mvc
            .perform(post("/ingest").contentType("application/json").content(body))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid_request"))
        assertEquals(0, embeddingCalls)
    }

    @Test
    fun `dry run recursively plans presentations without provider persistence or asset writes`() {
        PresentationFixtures.write(temp.resolve("top.pptx"))
        PresentationFixtures.write(temp.resolve("nested/deep.pptx"))
        Files.writeString(temp.resolve("nested/bad.pptx"), "invalid package")
        val service = service()
        val result = service.ingestPath(IngestionRequest("workspace", temp, modelProfile = "selected", dryRun = true), true)
        assertEquals(3, result.documents.size)
        assertEquals(1, result.documents.count { it.skipped })
        assertEquals(4, result.documents.sumOf { it.assetsFound })
        assertTrue(result.indexed >= 8)
        assertEquals(0, embeddingCalls)
        assertFalse(Files.exists(temp.resolve("assets")))
        verify(exactly = 0) { sources.find(any(), any(), any()) }
        verify(exactly = 0) { sources.upsert(any()) }
        verify(exactly = 0) { transactions.executeWithoutResult(any()) }
    }

    @Test
    fun `persists slide provenance same-slide assets unchanged skips and image-only replacements`() {
        val source = PresentationFixtures.write(temp.resolve("guide.pptx"))
        val service = service()
        val request = IngestionRequest("workspace", source, chunkingProfile = "small", modelProfile = "selected")
        val first = service.ingest(request)
        assertEquals(2, first.assetCount)
        assertTrue(first.chunkCount > 4)
        assertEquals("pptx", stored!!.metadata["document_format"])
        assertEquals(setOf(1, 2, 3, 4), persistedChunks.map { it.pageNumber }.toSet())
        assertEquals(2, links.size)
        links.forEach { (chunkId, assetId) ->
            val chunk = persistedChunks.single { it.metadata["chunk_id"] == chunkId }
            val asset = persistedAssets.single { it.assetId == assetId }
            assertEquals(3, chunk.pageNumber)
            assertTrue(asset.anchorBlockId!!.startsWith("slide-3-"))
        }
        val skipped = service.ingest(request)
        assertEquals("unchanged", skipped.skipReason)
        assertEquals(1, embeddingCalls)
        assertEquals(first.chunkCount, persistedChunks.size)
        PresentationFixtures.write(source, PresentationFixtures.image(Color.BLUE))
        val replaced = service.ingest(request)
        assertTrue(replaced.changed)
        assertEquals(2, embeddingCalls)
        verify(exactly = 2) { chunks.deleteForDocument("workspace", "small", first.documentId, any()) }
        verify(exactly = 2) { assets.deleteForDocument("workspace", "small", first.documentId) }
    }

    @Test
    fun `provider failure leaves database and asset storage untouched`() {
        val source = PresentationFixtures.write(temp.resolve("guide.pptx"))
        val service = service(failEmbeddings = true)
        assertFailsWith<IngestionProviderException> { service.ingest(IngestionRequest("workspace", source, modelProfile = "selected")) }
        assertTrue(persistedChunks.isEmpty())
        assertFalse(Files.exists(temp.resolve("assets")))
        verify(exactly = 0) { transactions.executeWithoutResult(any()) }
        verify(exactly = 0) { sources.upsert(any()) }
    }

    @Test
    fun `persistence failure propagates through transaction and stale cleanup remains root-scoped`() {
        val source = PresentationFixtures.write(temp.resolve("guide.pptx"))
        val service = service()
        every { assets.upsert(any()) } throws IllegalStateException("forced failure")
        assertFailsWith<IllegalStateException> {
            service.ingest(
                IngestionRequest("workspace", source, rootPath = temp, modelProfile = "selected"),
            )
        }
        verify(exactly = 1) { transactions.executeWithoutResult(any()) }
        val recorded = stored!!
        every { sources.listForRoot("workspace", "default", temp.toAbsolutePath().toString()) } returns listOf(recorded)
        every { sources.delete("workspace", "default", recorded.documentId) } returns true
        Files.delete(source)
        assertEquals(listOf(recorded.documentId), service.cleanupMissingSources("workspace", temp).deletedDocumentIds)
        verify { sources.delete("workspace", "default", recorded.documentId) }
    }
}
