package com.dex.ragpoc.providers

import org.springframework.ai.chat.model.ChatModel
import org.springframework.stereotype.Service

/** Provider-neutral chat boundary backed by the active Spring AI [ChatModel]. */
fun interface ChatGateway {
    fun complete(prompt: String): String
}

@Service
class SpringAiChatGateway(
    private val chatModel: ChatModel,
) : ChatGateway {
    override fun complete(prompt: String): String {
        require(prompt.isNotBlank()) { "Chat prompt must not be blank" }
        return chatModel.call(prompt) ?: throw IllegalStateException("Chat provider returned no content")
    }
}
