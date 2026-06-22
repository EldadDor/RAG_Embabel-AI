package com.dex.ragpoc.config

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Provides a [ChatClient] bean per profile.
 *
 * Both beans share the same system prompt contract; the profile controls
 * which underlying AI backend (Ollama vs Azure OpenAI) the auto-configured
 * [ChatClient.Builder] points to.
 *
 * Profile:  local → Ollama (llama3)
 *           work  → Azure OpenAI (gpt-4o or configured deployment)
 */
@Configuration
class ChatClientConfig {

    private val log = LoggerFactory.getLogger(ChatClientConfig::class.java)

    @Bean
    @Profile("local")
    fun chatClientLocal(builder: ChatClient.Builder): ChatClient {
        log.info("[Profile=local] Building ChatClient → Ollama")
        return builder
            .defaultSystem(
                """
                You are a helpful assistant.
                Answer ONLY from the provided context.
                If the answer is not in the context, say exactly: "I don't know."
                Keep answers concise and accurate.
                """.trimIndent()
            )
            .build()
    }

    @Bean
    @Profile("work")
    fun chatClientWork(builder: ChatClient.Builder): ChatClient {
        log.info("[Profile=work] Building ChatClient → Azure OpenAI")
        return builder
            .defaultSystem(
                """
                You are a precise technical assistant.
                Answer ONLY from the provided context.
                Cite the source document and page number when possible.
                If the answer is not in the context, say exactly: "I don't know."
                """.trimIndent()
            )
            .build()
    }
}
