package com.dex.ragpoc.conversation

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationSummary
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.persistence.JdbcConversationRepository
import com.dex.ragpoc.workspace.WorkspaceAccessService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationSessionServiceTest {
    private val principal = Principal("alice", "Alice")
    private val conversations = mockk<JdbcConversationRepository>()
    private val access = mockk<WorkspaceAccessService>(relaxed = true)
    private val service =
        ConversationSessionService(conversations, access, AppProperties(memory = AppProperties.Memory(maxTurns = 3, retentionDays = 30)))

    @Test
    fun `lists only active owned sessions after workspace authorization`() {
        val session = ChatSession("session-1", "alice", "alpha", "Guide")
        every { conversations.deleteTurnsOlderThan(30) } returns 0
        every { conversations.listActiveOwned("alice", "alpha") } returns listOf(session)

        assertEquals(listOf(session), service.list(principal, "alpha"))

        verifyOrder {
            access.requireAccess(principal, "alpha")
            conversations.deleteTurnsOlderThan(30)
            conversations.listActiveOwned("alice", "alpha")
        }
    }

    @Test
    fun `foreign archived and missing sessions are masked as not found`() {
        every { conversations.deleteTurnsOlderThan(30) } returns 0
        every { conversations.findActiveOwned("session-1", "alice") } returns null

        assertFailsWith<SessionNotFoundException> { service.detail(principal, "session-1") }

        verify(exactly = 0) { access.requireAccess(any(), any()) }
    }

    @Test
    fun `detail returns bounded durable turns and summary`() {
        val session = ChatSession("session-1", "alice", "alpha", "Guide")
        val summary = ConversationSummary("session-1", "Previous discussion", 2)
        val turns = listOf(ConversationTurn(3, "session-1", "user", "What changed?"))
        every { conversations.deleteTurnsOlderThan(30) } returns 2
        every { conversations.findActiveOwned("session-1", "alice") } returns session
        every { conversations.summary("session-1") } returns summary
        every { conversations.recentTurns("session-1", 3) } returns turns

        assertEquals(SessionDetail(session, summary, turns), service.detail(principal, "session-1"))
    }
}
