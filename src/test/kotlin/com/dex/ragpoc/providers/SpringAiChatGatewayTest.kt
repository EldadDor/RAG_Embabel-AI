package com.dex.ragpoc.providers

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpringAiChatGatewayTest {
    @Test
    fun `delegates to the active Spring AI chat model`() {
        val model = mockk<ChatModel>()
        every { model.call("hello") } returns "answer"

        assertEquals("answer", SpringAiChatGateway(model).complete("hello"))
        verify { model.call("hello") }
    }

    @Test
    fun `rejects blank prompts without a provider call`() {
        val model = mockk<ChatModel>()

        assertFailsWith<IllegalArgumentException> { SpringAiChatGateway(model).complete("  ") }
        verify(exactly = 0) { model.call(any<String>()) }
    }

    @Test
    fun `streams provider deltas without changing whitespace`() {
        val model = mockk<ChatModel>()
        every { model.stream(any<Prompt>()) } returns
            Flux.just(
                ChatResponse(listOf(Generation(AssistantMessage("Hello")))),
                ChatResponse(listOf(Generation(AssistantMessage(" world")))),
            )

        assertEquals(listOf("Hello", " world"), SpringAiChatGateway(model).stream("prompt").collectList().block())
        verify(exactly = 0) { model.call(any<String>()) }
    }
}
