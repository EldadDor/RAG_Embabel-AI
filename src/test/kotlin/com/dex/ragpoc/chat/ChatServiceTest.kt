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
    }

    private fun service(
        retrieval: RetrievalService,
        conversations: JdbcConversationRepository,
        gateway: ChatGateway,
    ) = ChatService(retrieval, conversations, gateway, mockk<WorkspaceAccessService>(relaxed = true), AppProperties())

    private fun profile() = ModelProfile("bge-m3", "ollama", "bge", 2, "chunks")
}
