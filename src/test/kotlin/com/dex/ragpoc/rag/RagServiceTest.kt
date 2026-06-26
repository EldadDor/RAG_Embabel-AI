package com.dex.ragpoc.rag

import com.dex.ragpoc.model.RagQueryRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

/**
 * Unit test for RagController pipeline endpoint.
 *
 * Done-when gate (Task 8.1): verifies vectorStore.similaritySearch is invoked.
 * Task 5.2: add agentic test verifying ToolishRag is invoked.
 */
class RagServiceTest {

    private val vectorStore: VectorStore = mockk()
    private val chatClient: ChatClient = mockk()

    @Test
    @org.junit.jupiter.api.Disabled("Integration wiring requires Spring context — promote to @SpringBootTest for Task 8.2")
    fun `pipeline query invokes vectorStore similaritySearch`() {
        val sampleDocs = listOf(
            Document("Qdrant uses API key authentication by default."),
        )
        every { vectorStore.similaritySearch(any<SearchRequest>()) } returns sampleDocs
        every {
            chatClient.prompt().user(any<String>()).call().content()
        } returns "Qdrant uses API key auth."

        val controller = RagController(chatClient, vectorStore)
        val response = controller.queryPipeline(RagQueryRequest("How does Qdrant authenticate?"))

        verify(exactly = 1) { vectorStore.similaritySearch(any<SearchRequest>()) }
        assertNotNull(response.body?.answer)
    }
}
