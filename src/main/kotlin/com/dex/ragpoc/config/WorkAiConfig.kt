package com.dex.ragpoc.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * WORK profile startup validation and logging.
 *
 * Validates that the required Azure OpenAI + pgvector config properties
 * are present and logs the active configuration at startup.
 *
 * Does NOT define VectorStore or EmbeddingModel beans — those are
 * auto-configured by spring-ai-azure-openai-spring-boot-starter and
 * spring-ai-pgvector-store-spring-boot-starter from application-work.yml.
 *
 * STOP CONDITION: If AZURE_OPENAI_ENDPOINT or AZURE_OPENAI_API_KEY are
 * missing, Spring will fail to start with a clear binding error.
 * Set them in your .env or environment before running with profile=work.
 */
@Configuration
@Profile("work")
class WorkAiConfig(
    @Value("\${spring.ai.azure.openai.endpoint}")
    private val azureEndpoint: String,

    @Value("\${spring.ai.azure.openai.embedding.deployment-name:text-embedding-ada-002}")
    private val embedDeployment: String,

    @Value("\${spring.ai.azure.openai.chat.deployment-name:gpt-4o}")
    private val chatDeployment: String,

    @Value("\${spring.datasource.url}")
    private val pgUrl: String,

    @Value("\${spring.datasource.username}")
    private val pgUser: String,
) {
    private val log = LoggerFactory.getLogger(WorkAiConfig::class.java)

    @PostConstruct
    fun logConfig() {
        log.info("======================================================")
        log.info(" PROFILE: work")
        log.info(" Azure OpenAI endpoint  : {}", azureEndpoint)
        log.info(" Embed deployment       : {}", embedDeployment)
        log.info(" Chat deployment        : {}", chatDeployment)
        log.info(" pgvector datasource    : {} (user: {})", pgUrl, pgUser)
        log.info(" Embed dim              : 1536  (ada-002 / text-embedding-3-small)")
        log.info("======================================================")
    }
}
