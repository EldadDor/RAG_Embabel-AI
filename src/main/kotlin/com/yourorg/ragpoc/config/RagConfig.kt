package com.yourorg.ragpoc.config

import com.yourorg.ragpoc.adapter.SpringAiVectorSearchAdapter
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.context.annotation.Configuration

/**
 * RAG wiring configuration.
 *
 * IMPORTANT — Task 5.1 stop condition:
 * Before implementing ToolishRag wiring below, verify the following Embabel symbols exist
 * in the resolved embabel-agent-rag-core / embabel-agent-rag-pipeline artifact:
 *   - SearchOperations (interface)
 *   - VectorSearch (interface)
 *   - ToolishRag (class + constructor params)
 *   - TryHyDE (hint object/constant)
 *   - TryTextSearch (hint object/constant)
 *   - Chunk (data class — fields: text, urtext, parentId, pathFromRoot, metadata)
 *   - PropertyFilter.eq(...)
 *   - ResultsListener
 *
 * If any of these differ from the spec, STOP and report the actual API before implementing.
 *
 * TODO (Task 5.2): Uncomment and complete after Task 5.1 gate passes.
 */
@Configuration
class RagConfig(
    private val vectorStore: VectorStore,
    private val embeddingModel: EmbeddingModel,
) {
    private val logger = LoggerFactory.getLogger(RagConfig::class.java)

    // -----------------------------------------------------------------------
    // Uncomment after verifying Embabel API in Task 5.1
    // -----------------------------------------------------------------------
    //
    // @Bean
    // fun searchOperations(adapter: SpringAiVectorSearchAdapter): SearchOperations = adapter
    //
    // @Bean
    // fun toolishRag(searchOperations: SearchOperations): ToolishRag =
    //     ToolishRag(
    //         name = "infra-codebase-rag",
    //         description = "Search the infrastructure codebase documentation",
    //         searchOperations = searchOperations,
    //         vectorSearchFor = listOf(Chunk::class.java),
    //         textSearchFor = listOf(Chunk::class.java),
    //         hints = listOf(TryHyDE, TryTextSearch),
    //         metadataFilter = PropertyFilter.eq("source", "infra-codebase.pdf"),
    //     )
    //
    // @Bean
    // fun resultsListener(): ResultsListener = ResultsListener { event ->
    //     logger.info(
    //         "RAG event: query='{}', results={}, duration={}ms",
    //         event.query, event.results.size, event.runningTime.toMillis()
    //     )
    // }
}
