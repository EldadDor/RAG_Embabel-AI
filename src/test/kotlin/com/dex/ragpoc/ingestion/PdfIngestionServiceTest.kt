package com.dex.ragpoc.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore

/**
 * Unit tests for [PdfIngestionService].
 *
 * Done-when gate (Task 8.1):
 *   mvn -q test passes
 *   verifies vectorStore.add() called with chunk count > 0
 *
 * Note: Tests that require a real PDF resource are @Disabled until sample-docs/infra-codebase.pdf
 * is added (Task 3.2 gate). Do not delete — enable when the PDF is present.
 */
class PdfIngestionServiceTest {

    private val vectorStore: VectorStore = mockk(relaxed = true)
    private val embeddingModel: EmbeddingModel = mockk(relaxed = true)
    private val service = PdfIngestionService(vectorStore, embeddingModel)

    @Test
    @Disabled("Requires sample-docs/infra-codebase.pdf on classpath — enable in Task 3.2")
    fun `ingestPdf calls vectorStore add with non-empty chunk list`() {
        val documentsSlot = slot<List<Document>>()
        every { vectorStore.add(capture(documentsSlot)) } returns Unit

        val chunkCount = service.ingestPdf("sample-docs/infra-codebase.pdf")

        verify(exactly = 1) { vectorStore.add(any()) }
        assertTrue(chunkCount > 0, "Expected at least 1 chunk, got $chunkCount")
        assertTrue(documentsSlot.captured.isNotEmpty(), "Captured chunk list must not be empty")
    }
}
