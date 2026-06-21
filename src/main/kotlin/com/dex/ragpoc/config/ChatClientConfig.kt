package com.dex.ragpoc.config

import org.springframework.ai.chat.client.ChatClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides the ChatClient bean.
 *
 * In Spring AI 1.0.0-M6, ChatClient is NOT auto-configured — it must be declared
 * explicitly. The builder is auto-configured via OpenAI/Ollama starters.
 */
@Configuration
class ChatClientConfig {

    @Bean
    fun chatClient(builder: ChatClient.Builder): ChatClient =
        builder
            .defaultSystem(
                "You are a helpful infrastructure assistant. " +
                "Use ONLY the provided context to answer. " +
                "If the answer is not in the context, say \"I don't know.\""
            )
            .build()
}
