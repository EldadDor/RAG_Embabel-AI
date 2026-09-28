package com.dex.ragpoc.chat

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.domain.RetrievedChunk
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.persistence.JdbcConversationRepository
import com.dex.ragpoc.providers.ChatGateway
import com.dex.ragpoc.retrieval.RetrievalResult
import com.dex.ragpoc.retrieval.RetrievalService
import com.dex.ragpoc.workspace.WorkspaceAccessService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatServiceTest {
    private val principal = Principal("alice", "Alice")

    @Test
    fun `empty retrieval abstains without provider call`() {
        val retrieval = mockk<RetrievalService>()
        val conversations = mockk<JdbcConversationRepository>()
        val gateway = mockk<ChatGateway>()
        every { retrieval.retrieve(any(), any(), any(), any(), any()) } returns RetrievalResult(emptyList(), profile())
        every { conversations.create(any()) } returns Unit
        every { conversations.recentTurns(any(), 10) } returns emptyList()
        every { conversations.summary(any()) } returns null
        justRun { conversations.appendTurn(any(), any(), any(), any()) }

        val answer = service(retrieval, conversations, gateway).answer("Question", principal, "alpha")

        assertFalse(answer.grounded)
        assertEquals(emptyList(), answer.sources)
        verify(exactly = 0) { gateway.complete(any()) }
    }

    @Test
    fun `grounded result generates citations and persists both turns`() {
        val retrieval = mockk<RetrievalService>()
        val conversations = mockk<JdbcConversationRepository>()
        val gateway = mockk<ChatGateway>()
        every { retrieval.retrieve(any(), any(), any(), any(), any()) } returns
            RetrievalResult(listOf(RetrievedChunk("chunk", "doc", "guide.md", "Evidence", .9)), profile())
        every { conversations.create(any()) } returns Unit
        every { conversations.recentTurns(any(), 10) } returns emptyList()
        every { conversations.summary(any()) } returns null
        every { gateway.complete(match { it.contains("Evidence") }) } returns "Answer"
        justRun { conversations.appendTurn(any(), any(), any(), any()) }

        val answer = service(retrieval, conversations, gateway).answer("Question", principal, "alpha", includeDebug = true)

        assertTrue(answer.grounded)
        assertEquals("chunk", answer.sources.single().chunkId)
        assertEquals("Answer", answer.answer)
        verify(exactly = 2) { conversations.appendTurn(any(), any(), any(), any()) }
    }

    @Test
    fun `provider failure does not persist a partial conversation`() {
        val retrieval = mockk<RetrievalService>()
        val conversations = mockk<JdbcConversationRepository>()
        val gateway = mockk<ChatGateway>()
        every { retrieval.retrieve(any(), any(), any(), any(), any()) } returns
            RetrievalResult(listOf(RetrievedChunk("chunk", "doc", "guide.md", "Evidence", .9)), profile())
        every { conversations.create(any()) } returns Unit
        every { conversations.recentTurns(any(), 10) } returns emptyList()
        every { conversations.summary(any()) } returns null
        every { gateway.complete(any()) } throws IllegalStateException("unavailable")

        assertFailsWith<ChatProviderException> { service(retrieval, conversations, gateway).answer("Question", principal, "alpha") }

        verify(exactly = 0) { conversations.appendTurn(any(), any(), any(), any()) }
        verify(exactly = 0) { conversations.create(any()) }
    }

    @Test
    fun `stream emits real provider deltas then metadata and completion`() {
        val (service, conversations, gateway) = streamingFixture()
        every { gateway.stream(any()) } returns Flux.just("Hello", " world")

        val events = service.stream("Question", principal, "alpha").collectList().block()!!

        assertEquals(listOf("answer", "answer", "meta", "done"), events.map { it.name })
        assertEquals("Hello", (events[0].data as Map<*, *>)["delta"])
        assertEquals(" world", (events[1].data as Map<*, *>)["delta"])
        val meta = events[2].data as Map<*, *>
        assertTrue((meta["session_id"] as String).isNotBlank())
        assertEquals("chunk", ((meta["sources"] as List<*>).single() as Map<*, *>)["chunk_id"])
        assertEquals("completed", (events[3].data as Map<*, *>)["reason"])
        verify { conversations.appendTurn(any(), "assistant", "Hello world", any()) }
    }

    @Test
    fun `stream failure emits safe error and does not persist`() {
        val (service, conversations, gateway) = streamingFixture()
        every { gateway.stream(any()) } returns Flux.concat(Flux.just("partial"), Flux.error(IllegalStateException("secret")))

        val events = service.stream("Question", principal, "alpha").collectList().block()!!

        assertEquals(listOf("answer", "error", "done"), events.map { it.name })
        assertEquals("Chat stream failed", (events[1].data as Map<*, *>)["detail"])
        verify(exactly = 0) { conversations.appendTurn(any(), any(), any(), any()) }
        verify(exactly = 0) { conversations.create(any()) }
    }

    @Test
    fun `empty provider stream is an error and does not create a session`() {
        val (service, conversations, gateway) = streamingFixture()
        every { gateway.stream(any()) } returns Flux.empty()

        val events = service.stream("Question", principal, "alpha").collectList().block()!!

        assertEquals(listOf("error", "done"), events.map { it.name })
        verify(exactly = 0) { conversations.create(any()) }
        verify(exactly = 0) { conversations.appendTurn(any(), any(), any(), any()) }
    }

    @Test
    fun `stream cancellation stops provider and does not persist`() {
        val (service, conversations, gateway) = streamingFixture()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        every { gateway.stream(any()) } returns sink.asFlux()
        val events = mutableListOf<ChatStreamEvent>()
        val subscription = service.stream("Question", principal, "alpha").subscribe { events += it }

        sink.tryEmitNext("partial")
        subscription.dispose()

        assertEquals(listOf("answer"), events.map { it.name })
        assertEquals(0, sink.currentSubscriberCount())
        verify(exactly = 0) { conversations.appendTurn(any(), any(), any(), any()) }
        verify(exactly = 0) { conversations.create(any()) }
    }

    private fun streamingFixture(): Triple<ChatService, JdbcConversationRepository, ChatGateway> {
        val retrieval = mockk<RetrievalService>()
        val conversations = mockk<JdbcConversationRepository>()
        val gateway = mockk<ChatGateway>()
        every { retrieval.retrieve(any(), any(), any(), any(), any()) } returns
            RetrievalResult(listOf(RetrievedChunk("chunk", "doc", "guide.md", "Evidence", .9)), profile())
        every { conversations.create(any()) } returns Unit
        every { conversations.recentTurns(any(), 10) } returns emptyList()
        every { conversations.summary(any()) } returns null
        justRun { conversations.appendTurn(any(), any(), any(), any()) }
        return Triple(service(retrieval, conversations, gateway), conversations, gateway)
    }

    private fun service(
        retrieval: RetrievalService,
        conversations: JdbcConversationRepository,
        gateway: ChatGateway,
    ) = ChatService(retrieval, conversations, gateway, mockk<WorkspaceAccessService>(relaxed = true), AppProperties())

    private fun profile() = ModelProfile("bge-m3", "ollama", "bge", 2, "chunks")
}
