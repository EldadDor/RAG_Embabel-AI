package com.dex.ragpoc.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore

/**
 * Unit tests for PdfIngestionService.
 *
 * Done-when gate (Task 8.1): mvn -q test passes; verifies vectorStore.add() called with
 * chunk count > 0.
 *
 * Tests requiring a real PDF are @Disabled until sample-docs/infra-codebase.pdf
 * is on the classpath (Task 3.2 gate). Do NOT delete — enable then.
 */
class PdfIngestionServiceTest {

    private val vectorStore: VectorStore = mockk(relaxed = true)
    private val service = PdfIngestionService(vectorStore)

    @Test
    @Disabled("Requires sample-docs/infra-codebase.pdf on classpath — enable in Task 3.2")
    fun `ingestPdf stores non-empty chunk list in vector store`() {
        val slot = slot<List<Document>>()
        every { vectorStore.add(capture(slot)) } returns Unit

        val count = service.ingestPdf("sample-docs/infra-codebase.pdf")

        verify(exactly = 1) { vectorStore.add(any()) }
        assertTrue(count > 0, "Expected at least 1 chunk, got $count")
        assertTrue(slot.captured.isNotEmpty(), "Captured chunk list must not be empty")
    }
}
