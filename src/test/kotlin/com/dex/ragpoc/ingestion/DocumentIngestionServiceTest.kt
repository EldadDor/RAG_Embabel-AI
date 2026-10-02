package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.DocumentAsset
import com.dex.ragpoc.domain.SourceDocument
import com.dex.ragpoc.domain.SourceType
import com.dex.ragpoc.parsing.ChunkingProfile
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
    fun `directory dry run is deterministic and respects recursion without persistence`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("sources"))
        Files.writeString(root.resolve("b.txt"), "Second")
        Files.writeString(root.resolve("a.txt"), "First")
        Files.writeString(root.resolve("ignored.bin"), "Ignored")
        val nested = Files.createDirectory(root.resolve("nested"))
        Files.writeString(nested.resolve("c.txt"), "Third")
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>()
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { _, _ -> error("Dry runs must not embed") },
                ContentAddressedAssetStore(AppProperties()),
                sourceDocuments,
                mockk(),
                mockk(),
                AppProperties(),
                transactionTemplate(),
                modelTargets = IngestionModelTargetResolver { error("Dry runs must not resolve model profiles") },
            )

        val nonRecursive = service.ingestPath(IngestionRequest("workspace", root, dryRun = true))
        assertEquals(listOf("a.txt", "b.txt"), nonRecursive.documents.map { Path.of(it.sourcePath).fileName.toString() })
        assertEquals(2, nonRecursive.indexed)
        assertTrue(nonRecursive.documents.all { !it.skipped })
        val recursive = service.ingestPath(IngestionRequest("workspace", root, dryRun = true), recursive = true)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), recursive.documents.map { Path.of(it.sourcePath).fileName.toString() })
        assertEquals(3, recursive.indexed)
        verify(exactly = 0) { sourceDocuments.find(any(), any(), any()) }
    }

    @Test
    fun `empty document is reported as skipped and unsupported single file is rejected`() {
        val empty = Files.writeString(temporaryDirectory.resolve("empty.txt"), "")
        val unsupported = Files.writeString(temporaryDirectory.resolve("other.bin"), "binary")
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { _, _ -> error("Dry runs must not embed") },
                ContentAddressedAssetStore(AppProperties()),
                mockk(),
                mockk(),
                mockk(),
                AppProperties(),
                transactionTemplate(),
            )

        val result = service.ingestPath(IngestionRequest("workspace", empty, dryRun = true))
        assertEquals(0, result.indexed)
        assertEquals("empty", result.documents.single().skipReason)
        assertTrue(result.documents.single().skipped)
        assertFailsWith<DocumentLoadFailure> {
            service.ingestPath(IngestionRequest("workspace", unsupported, dryRun = true))
        }
    }

    @Test
    fun `selected model profile controls embedding and storage target`() {
        val source = Files.writeString(temporaryDirectory.resolve("profile.txt"), "Profiled content")
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>(relaxed = true)
        every { sourceDocuments.find("workspace", "default", any()) } returns null
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val properties = AppProperties(database = AppProperties.Database(vectorDimension = 3))
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { profile, texts ->
                    assertEquals("alternate", profile)
                    texts.map { listOf(0.1f, 0.2f, 0.3f) }
                },
                ContentAddressedAssetStore(properties),
                sourceDocuments,
                chunks,
                mockk(relaxed = true),
                properties,
                transactionTemplate(),
                modelTargets =
                    IngestionModelTargetResolver { profile ->
                        assertEquals("alternate", profile)
                        IngestionModelTarget("document_chunks_alternate", 3)
                    },
            )

        val result = service.ingestPath(IngestionRequest("workspace", source, modelProfile = "alternate"))
        assertEquals("alternate", result.modelProfile)
        assertEquals(1, result.indexed)
        verify { chunks.deleteForDocument("workspace", "default", result.documents.single().documentId!!, "document_chunks_alternate") }
        verify { chunks.upsert(any(), any(), "document_chunks_alternate", 3) }
        verify { sourceDocuments.upsert(match { it.metadata["model_profile"] == "alternate" }) }
    }

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
                EmbeddingGateway { _, _ -> error("Dry runs must not embed") },
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
                EmbeddingGateway { _, _ -> emptyList() },
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
            SourceDocument(
                "workspace",
                "default",
                "same",
                null,
                source.toString(),
                SourceType.TEXT,
                hash(document.content),
                mapOf("model_profile" to "bge-m3"),
            )
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(mapOf(".txt" to DocumentLoader { document })),
                DocumentChunker(),
                EmbeddingGateway { _, _ -> error("Unchanged sources must not embed") },
                ContentAddressedAssetStore(AppProperties()),
                sourceDocuments,
                chunks,
                assets,
                AppProperties(),
                transactionTemplate(),
            )

        assertEquals(
            IngestionResult("same", changed = false, dryRun = false, chunkCount = 0, assetCount = 0, skipReason = "unchanged"),
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
                EmbeddingGateway { _, _ ->
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
                EmbeddingGateway { _, _ -> error("embedding unavailable") },
                ContentAddressedAssetStore(AppProperties()),
                sourceDocuments,
                chunks,
                assets,
                AppProperties(),
                transactionTemplate(),
            )

        assertFailsWith<IngestionProviderException> { service.ingest(IngestionRequest("workspace", source)) }
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
        val storedAsset = slot<StoredDocumentAsset>()
        every { assets.linkToChunk(any(), any(), any(), capture(linkedChunk), any(), any()) } returns Unit
        every { assets.upsert(capture(storedAsset)) } returns Unit
        val properties = AppProperties(database = AppProperties.Database(vectorDimension = 3))
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { _, texts -> texts.map { listOf(0.1f, 0.2f, 0.3f) } },
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
        assertEquals(64, storedAsset.captured.assetId.length)
        assertEquals(storedAsset.captured.contentHash, storedAsset.captured.storageKey)
    }

    @Test
    fun `asset outside chunk bounds links to the nearest chunk`() {
        val source = temporaryDirectory.resolve("guide.txt")
        Files.writeString(source, "alpha beta gamma delta epsilon zeta")
        val assetContent = "image".toByteArray()
        val document =
            Document(
                documentId = "guide",
                sourcePath = source.toString(),
                sourceType = SourceType.TEXT,
                content = Files.readString(source),
                assets =
                    listOf(
                        DocumentAsset(
                            anchorId = "asset-1",
                            relationshipId = "rId1",
                            content = assetContent,
                            contentHash = hash("image"),
                            mediaType = "image/png",
                            sourceIndex = 10_000,
                        ),
                    ),
            )
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>(relaxed = true)
        every { sourceDocuments.find("workspace", "default", "guide") } returns null
        val storedChunks = mutableListOf<StoredChunk>()
        val chunks = mockk<JdbcChunkRepository>(relaxed = true)
        every { chunks.upsert(capture(storedChunks), any(), any(), any()) } returns Unit
        val assets = mockk<JdbcDocumentAssetRepository>(relaxed = true)
        val linkedChunk = slot<String>()
        every { assets.linkToChunk(any(), any(), any(), capture(linkedChunk), any(), any()) } returns Unit
        val properties = AppProperties(database = AppProperties.Database(vectorDimension = 3))
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(mapOf(".txt" to DocumentLoader { document })),
                DocumentChunker(mapOf("default" to ChunkingProfile(chunkSize = 12, chunkOverlap = 1))),
                EmbeddingGateway { _, texts -> texts.map { listOf(0.1f, 0.2f, 0.3f) } },
                ContentAddressedAssetStore(
                    properties.copy(assets = AppProperties.Assets(storageRoot = temporaryDirectory.resolve("assets"))),
                ),
                sourceDocuments,
                chunks,
                assets,
                properties,
                transactionTemplate(),
            )

        service.ingest(IngestionRequest("workspace", source))

        assertTrue(storedChunks.size > 1)
        assertEquals(storedChunks.maxBy { it.chunkIndex ?: -1 }.metadata["chunk_id"], linkedChunk.captured)
        verify(exactly = 1) { assets.linkToChunk("workspace", "default", "guide", any(), any(), any()) }
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
                EmbeddingGateway { _, texts -> texts.map { listOf(0.1f, 0.2f, 0.3f) } },
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
        verify(atLeast = sources.size) { chunks.upsert(any(), any(), any(), any()) }
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
        every { chunks.upsert(capture(storedChunk), any(), any(), any()) } returns Unit
        val properties = AppProperties(database = AppProperties.Database(vectorDimension = 3))
        val service =
            DocumentIngestionService(
                DocumentLoaderRegistry(),
                DocumentChunker(),
                EmbeddingGateway { _, texts -> texts.map { listOf(0.1f, 0.2f, 0.3f) } },
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
