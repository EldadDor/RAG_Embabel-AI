package com.yourorg.ragpoc.rag

import com.yourorg.ragpoc.model.RagQueryRequest
import com.yourorg.ragpoc.model.RagQueryResponse
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * RAG query endpoints.
 *
 * /api/rag/query          — agentic (ToolishRag)
 * /api/rag/query/pipeline — fixed pipeline baseline (LCEL-equivalent)
 *
 * STOP CONDITIONS:
 * - Task 4.1: If SearchRequest.query(...).withTopK(...) API differs, STOP and report.
 * - Task 5.2: If ToolishRag injection fails, return to Task 5.1 gate.
 */
@RestController
@RequestMapping("/api/rag")
class RagController(
    private val chatClient: ChatClient,
    private val vectorStore: VectorStore,
    // TODO (Task 5.2): inject ToolishRag after Task 5.1 gate passes
    // private val toolishRag: ToolishRag,
) {
    private val logger = LoggerFactory.getLogger(RagController::class.java)

    /**
     * Agentic RAG endpoint — uses ToolishRag as a tool attached to the chat client.
     * TODO (Task 5.2): uncomment toolishRag injection and .tools(toolishRag) call.
     */
    @PostMapping("/query")
    fun query(@RequestBody req: RagQueryRequest): ResponseEntity<RagQueryResponse> {
        // Placeholder until Task 5.2 gate: falls back to pipeline for now
        return queryPipeline(req)
    }

    /**
     * Fixed pipeline baseline — direct LCEL-equivalent.
     * Done-when gate (Task 4.1): HTTP 200, non-empty answer, retrievedChunks > 0.
     */
    @PostMapping("/query/pipeline")
    fun queryPipeline(@RequestBody req: RagQueryRequest): ResponseEntity<RagQueryResponse> {
        val start = System.currentTimeMillis()

        // STOP CONDITION: verify SearchRequest.query(...).withTopK(...) exists in resolved Spring AI
        val docs = vectorStore.similaritySearch(
            SearchRequest.query(req.question).withTopK(5)
        )

        val context = docs.joinToString("\n\n") { it.content }
        val prompt = """
            You are a helpful assistant. Use ONLY the provided context to answer.
            If the answer is not in the context, say "I don't know."

            Context:
            $context

            Question: ${req.question}
        """.trimIndent()

        val answer = chatClient.prompt().user(prompt).call().content()
        val durationMs = System.currentTimeMillis() - start

        logger.info("Pipeline query completed: chunks={}, duration={}ms", docs.size, durationMs)

        return ResponseEntity.ok(
            RagQueryResponse(
                answer = answer,
                retrievedChunks = docs.size,
                durationMs = durationMs,
            )
        )
    }
}
