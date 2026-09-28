package com.dex.ragpoc.api

import com.dex.ragpoc.chat.ChatService
import com.dex.ragpoc.chat.ChatStreamEvent
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.identity.PrincipalResolver
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import reactor.core.publisher.Flux
import kotlin.test.assertTrue

class ChatStreamControllerTest {
    @Test
    fun `stream endpoint emits named SSE events in order`() {
        val chat = mockk<ChatService>()
        every { chat.stream(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Flux.just(
                ChatStreamEvent("answer", mapOf("delta" to "Hello")),
                ChatStreamEvent("answer", mapOf("delta" to " world")),
                ChatStreamEvent("meta", mapOf("session_id" to "session-1")),
                ChatStreamEvent("done", mapOf("reason" to "completed")),
            )
        val properties = AppProperties()
        val mvc = MockMvcBuilders.standaloneSetup(ChatController(chat, PrincipalResolver(properties), properties)).build()

        val pending =
            mvc
                .perform(post("/chat/stream").contentType("application/json").content("""{"question":"Question"}"""))
                .andExpect(request().asyncStarted())
                .andReturn()
        val response =
            mvc
                .perform(
                    asyncDispatch(pending),
                ).andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn()
        val body = response.response.contentAsString
        assertTrue(body.indexOf("event:answer") < body.indexOf("event:meta"))
        assertTrue(body.indexOf("event:meta") < body.indexOf("event:done"))
        assertTrue(body.contains("Hello"))
        assertTrue(body.contains(" world"))
    }
}
