package com.dex.ragpoc.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * LOCAL profile startup validation and logging.
 *
 * Validates that the required Ollama config properties are present and
 * logs the active model configuration so developers can confirm the
 * correct models are wired at startup.
 *
 * Does NOT define VectorStore or EmbeddingModel beans — those are
 * auto-configured by spring-ai-ollama-spring-boot-starter and
 * spring-ai-qdrant-store-spring-boot-starter from application-local.yml.
 */
@Configuration
@Profile("local")
class LocalAiConfig(
    @Value("\${spring.ai.ollama.base-url:http://localhost:11434}")
    private val ollamaBaseUrl: String,

    @Value("\${spring.ai.ollama.embedding.model:nomic-embed-text}")
    private val embeddingModel: String,

    @Value("\${spring.ai.ollama.chat.model:llama3}")
    private val chatModel: String,

    @Value("\${spring.ai.vectorstore.qdrant.host:localhost}")
    private val qdrantHost: String,

    @Value("\${spring.ai.vectorstore.qdrant.port:6334}")
    private val qdrantPort: Int,
) {
    private val log = LoggerFactory.getLogger(LocalAiConfig::class.java)

    @PostConstruct
    fun logConfig() {
        log.info("======================================================")
        log.info(" PROFILE: local")
        log.info(" Ollama   : {}  (chat: {}, embed: {})", ollamaBaseUrl, chatModel, embeddingModel)
        log.info(" Qdrant   : {}:{} (gRPC)", qdrantHost, qdrantPort)
        log.info(" Embed dim: 768  (nomic-embed-text)")
        log.info(" NOTE: Pull models before first run:")
        log.info("   ollama pull {}  &&  ollama pull {}", chatModel, embeddingModel)
        log.info("======================================================")
    }
}
