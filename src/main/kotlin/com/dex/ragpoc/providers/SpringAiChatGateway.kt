package com.dex.ragpoc.providers

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

/** Provider-neutral chat boundary backed by the active Spring AI [ChatModel]. */
fun interface ChatGateway {
    fun complete(prompt: String): String

    fun stream(prompt: String): Flux<String> = Flux.error(UnsupportedOperationException("Streaming is not supported by this chat gateway"))
}

@Service
class SpringAiChatGateway(
    private val chatModel: ChatModel,
) : ChatGateway {
    override fun complete(prompt: String): String {
        require(prompt.isNotBlank()) { "Chat prompt must not be blank" }
        return chatModel.call(prompt) ?: throw IllegalStateException("Chat provider returned no content")
    }

    override fun stream(prompt: String): Flux<String> {
        require(prompt.isNotBlank()) { "Chat prompt must not be blank" }
        return chatModel.stream(Prompt(prompt)).mapNotNull { it.result?.output?.text }.filter { it.isNotEmpty() }
    }
}
