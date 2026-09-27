package com.dex.ragpoc.ingestion

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceType
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.parsing.DocumentLoader
import com.dex.ragpoc.parsing.DocumentLoaderRegistry
import com.dex.ragpoc.persistence.JdbcChunkRepository
import com.dex.ragpoc.persistence.JdbcDocumentAssetRepository
import com.dex.ragpoc.persistence.JdbcSourceDocumentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class DocumentIngestionServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `dry run plans chunks without embedding or persistence`() {
        val source = Files.writeString(temporaryDirectory.resolve("guide.txt"), "A short guide.")
        val document = Document("guide", source.toString(), SourceType.TEXT, "A short guide.")
        val sourceDocuments = mockk<JdbcSourceDocumentRepository>()
        every { sourceDocuments.find("workspace", "default", "guide") } returns null
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
            )

        val result = service.ingest(IngestionRequest("workspace", source, dryRun = true))

        assertEquals(IngestionResult("guide", changed = true, dryRun = true, chunkCount = 1, assetCount = 0), result)
        verify(exactly = 0) { chunks.deleteForDocument(any()) }
        verify(exactly = 0) { assets.deleteForDocument(any(), any(), any()) }
        verify(exactly = 0) { sourceDocuments.upsert(any()) }
    }
}
