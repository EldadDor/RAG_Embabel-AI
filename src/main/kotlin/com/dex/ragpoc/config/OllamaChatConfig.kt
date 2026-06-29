package com.dex.ragpoc.config

import org.slf4j.LoggerFactory
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * LOCAL profile: creates a dedicated OllamaChatModel that points to the
 * DESKTOP's local Ollama (OLLAMA_CHAT_BASE_URL, default http://localhost:11434).
 *
 * This is separate from the auto-configured OllamaEmbeddingModel which uses
 * spring.ai.ollama.base-url (OLLAMA_EMBED_BASE_URL) pointing to the LAPTOP.
 *
 * Spring AI autoconfiguration uses @ConditionalOnMissingBean(ChatModel::class),
 * so defining this bean prevents a duplicate from being created.
 */
@Configuration
@Profile("local")
class OllamaChatConfig(
    @Value("\${OLLAMA_CHAT_BASE_URL:http://localhost:11434}")
    private val chatBaseUrl: String,

    @Value("\${spring.ai.ollama.chat.model:llama3.2:3b}")
    private val chatModel: String,
) {
    private val log = LoggerFactory.getLogger(OllamaChatConfig::class.java)

    @Bean
    fun ollamaChatModel(): OllamaChatModel {
        log.info("[Profile=local] OllamaChatModel → {} using model '{}'", chatBaseUrl, chatModel)
        val api = OllamaApi(chatBaseUrl)
        val options = OllamaOptions.builder()
            .model(chatModel)
            .temperature(0.1)
            .numCtx(2048)
            .numPredict(512)
            .build()
        return OllamaChatModel.builder()
            .ollamaApi(api)
            .defaultOptions(options)
            .build()
    }
}
