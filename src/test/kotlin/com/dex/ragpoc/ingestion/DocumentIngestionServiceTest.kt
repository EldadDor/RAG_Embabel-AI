package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.domain.SourceType
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoader
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import com.dex.ragpoc.persistence.StoredChunk
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentIngestionServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `dry run plans chunks without embedding or persistence`() {
        val source = Files.writeString(temporaryDirectory.resolve("guide.txt"), "A short guide.")
        val document = Document("guide", source.toString(), SourceType.TEXT, "A short guide.")
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>()
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(mapOf(".txt" to DocumentLoader { document })),
                DocumentChunker(),
                EmbeddingGateway { error("Dry runs must not embed") },
                ContentAddressedAssetStore(
                    AppProperties(assets = AppProperties.Assets(storageRoot = temporaryDirectory.resolve("assets"))),
                ),
                sourceDocuments,
                chunks,
                assets,
                AppProperties(),
                transactionTemplate(),
            )

        val result = service.ingest(IngestionRequest("workspace", source, dryRun = true))

        assertEquals(IngestionResult("guide", changed = true, dryRun = true, chunkCount = 1, assetCount = 0), result)
        verify(exactly = 0) { chunks.deleteForDocument(any(), any(), any()) }
        verify(exactly = 0) { assets.deleteForDocument(any(), any(), any()) }
        verify(exactly = 0) { sourceDocuments.upsert(any()) }
        verify(exactly = 0) { sourceDocuments.find(any(), any(), any()) }
    }

    @Test
    fun `cleanup dry run identifies only missing source beneath root`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("root"))
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>()
        every { sourceDocuments.listForRoot("workspace", "default", root.toAbsolutePath().toString()) } returns
            listOf(
                SourceDocument(
                    "workspace",
                    "default",
                    "missing",
                    root.toString(),
                    root.resolve("missing.txt").toString(),
                    SourceType.TEXT,
                    "hash",
                ),
            )
        every { sourceDocuments.delete("workspace", "default", "missing") } returns true
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { emptyList() },
                ContentAddressedAssetStore(AppProperties()),
                sourceDocuments,
                chunks,
                assets,
                AppProperties(),
                transactionTemplate(),
            )

        assertEquals(CleanupResult(listOf("missing"), dryRun = true), service.cleanupMissingSources("workspace", root, dryRun = true))
        verify(exactly = 0) { sourceDocuments.delete(any(), any(), any()) }
        verify(exactly = 0) { chunks.deleteForDocument(any(), any(), any()) }
        verify(exactly = 0) { assets.deleteForDocument(any(), any(), any()) }
    }

    @Test
    fun `unchanged source is a no op without embedding`() {
        val source = Files.writeString(temporaryDirectory.resolve("same.txt"), "Same content")
        val document = Document("same", source.toString(), SourceType.TEXT, "Same content")
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>()
        every { sourceDocuments.find("workspace", "default", "same") } returns
            SourceDocument("workspace", "default", "same", null, source.toString(), SourceType.TEXT, hash(document.content))
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(mapOf(".txt" to DocumentLoader { document })),
                DocumentChunker(),
                EmbeddingGateway { error("Unchanged sources must not embed") },
                ContentAddressedAssetStore(AppProperties()),
                sourceDocuments,
                chunks,
                assets,
                AppProperties(),
                transactionTemplate(),
            )

        assertEquals(
            IngestionResult("same", changed = false, dryRun = false, chunkCount = 0, assetCount = 0),
            service.ingest(IngestionRequest("workspace", source)),
        )
        verify(exactly = 0) { chunks.deleteForDocument(any(), any(), any()) }
        verify(exactly = 0) { sourceDocuments.upsert(any()) }
    }

    @Test
    fun `cleanup removes stale records only when not a dry run`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("deletion-root"))
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>()
        every { sourceDocuments.listForRoot("workspace", "default", root.toAbsolutePath().toString()) } returns
            listOf(
                SourceDocument(
                    "workspace",
                    "default",
                    "missing",
                    root.toString(),
                    root.resolve("missing.txt").toString(),
                    SourceType.TEXT,
                    "hash",
                ),
            )
        every { sourceDocuments.delete("workspace", "default", "missing") } returns true
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway {
                    emptyList()
                },
                ContentAddressedAssetStore(AppProperties()),
                sourceDocuments,
                chunks,
                assets,
                AppProperties(),
                transactionTemplate(),
            )

        assertEquals(CleanupResult(listOf("missing"), dryRun = false), service.cleanupMissingSources("workspace", root))
        verify(exactly = 1) { chunks.deleteForDocument("workspace", "default", "missing") }
        verify(exactly = 1) { assets.deleteForDocument("workspace", "default", "missing") }
        verify(exactly = 1) { sourceDocuments.delete("workspace", "default", "missing") }
    }

    @Test
    fun `embedding failure leaves persistence untouched`() {
        val source = Files.writeString(temporaryDirectory.resolve("failure.txt"), "Content to embed")
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>()
        every { sourceDocuments.find("workspace", "default", any()) } returns null
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { error("embedding unavailable") },
                ContentAddressedAssetStore(AppProperties()),
                sourceDocuments,
                chunks,
                assets,
                AppProperties(),
                transactionTemplate(),
            )

        assertFailsWith<IllegalStateException> { service.ingest(IngestionRequest("workspace", source)) }
        verify(exactly = 0) { chunks.deleteForDocument(any(), any(), any()) }
        verify(exactly = 0) { sourceDocuments.upsert(any()) }
    }

    @Test
    fun `DOCX ingestion persists image and chunk link`() {
        val source = Path.of("src/test/resources/fixtures/word-guide.docx").toAbsolutePath()
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>(relaxed = true)
        every { sourceDocuments.find("workspace", "default", any()) } returns null
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val linkedChunk = slot<String>()
        every { assets.linkToChunk(any(), any(), any(), capture(linkedChunk), any(), any()) } returns Unit
        val properties = AppProperties(database = AppProperties.Database(vectorDimension = 3))
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { texts -> texts.map { listOf(0.1f, 0.2f, 0.3f) } },
                ContentAddressedAssetStore(
                    properties.copy(assets = AppProperties.Assets(storageRoot = temporaryDirectory.resolve("assets"))),
                ),
                sourceDocuments,
                chunks,
                assets,
                properties,
                transactionTemplate(),
            )

        val result = service.ingest(IngestionRequest("workspace", source))

        assertEquals(1, result.assetCount)
        assertTrue(result.chunkCount > 0)
        verify(exactly = 1) { assets.upsert(any()) }
        verify(atLeast = 1) { assets.linkToChunk("workspace", "default", result.documentId, any(), any(), any()) }
        assertTrue(linkedChunk.captured.startsWith(result.documentId))
    }

    @Test
    fun `text markdown html and code loaders pass through ingestion`() {
        val sources =
            mapOf(
                "guide.txt" to "Plain text guide.",
                "guide.md" to "# Markdown guide\n\nA short paragraph.",
                "guide.html" to "<html><body><h1>HTML guide</h1><p>A short paragraph.</p></body></html>",
                "Guide.kt" to "class Guide { fun describe(): String = \"A short guide\" }",
            )
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>(relaxed = true)
        every { sourceDocuments.find("workspace", "default", any()) } returns null
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val properties = AppProperties(database = AppProperties.Database(vectorDimension = 3))
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { texts -> texts.map { listOf(0.1f, 0.2f, 0.3f) } },
                ContentAddressedAssetStore(properties),
                sourceDocuments,
                chunks,
                mockk(relaxed = true),
                properties,
                transactionTemplate(),
            )

        sources.forEach { (name, content) ->
            val source = Files.writeString(temporaryDirectory.resolve(name), content)
            val result = service.ingest(IngestionRequest("workspace", source))
            assertTrue(result.changed, name)
            assertTrue(result.chunkCount > 0, name)
        }
        verify(exactly = sources.size) { sourceDocuments.upsert(any()) }
        verify(atLeast = sources.size) { chunks.upsert(any(), any()) }
    }

    @Test
    fun `PDF ingestion persists page provenance and chunks`() {
        val source = temporaryDirectory.resolve("guide.pdf")
        writePdf(source)
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>(relaxed = true)
        every { sourceDocuments.find("workspace", "default", any()) } returns null
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val storedDocument = slot<SourceDocument>()
        val storedChunk = slot<StoredChunk>()
        every { sourceDocuments.upsert(capture(storedDocument)) } returns Unit
        every { chunks.upsert(capture(storedChunk), any()) } returns Unit
        val properties = AppProperties(database = AppProperties.Database(vectorDimension = 3))
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { texts -> texts.map { listOf(0.1f, 0.2f, 0.3f) } },
                ContentAddressedAssetStore(properties),
                sourceDocuments,
                chunks,
                mockk(relaxed = true),
                properties,
                transactionTemplate(),
            )

        val result = service.ingest(IngestionRequest("workspace", source))

        assertTrue(result.changed)
        assertTrue(result.chunkCount > 0)
        assertEquals(SourceType.PDF, storedDocument.captured.sourceType)
        assertEquals(2, storedDocument.captured.metadata["total_pages"])
        assertTrue(storedChunk.captured.content.contains("[PAGE 1]"))
        assertEquals("default", storedChunk.captured.metadata["chunking_profile"])
    }

    private fun writePdf(path: Path) {
        PDDocument().use { document ->
            repeat(2) { pageIndex ->
                document.addPage(PDPage())
                PDPageContentStream(document, document.getPage(pageIndex)).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(72f, 720f)
                    stream.showText("PDF page ${pageIndex + 1}")
                    stream.endText()
                }
            }
            document.save(path.toFile())
        }
    }

    private fun hash(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun transactionTemplate(): TransactionTemplate =
        mockk<TransactionTemplate>().also { template ->
            every { template.executeWithoutResult(any()) } answers {
                firstArg<java.util.function.Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
            }
        }
}
