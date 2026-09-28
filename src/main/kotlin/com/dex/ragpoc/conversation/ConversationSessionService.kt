package com.dex.ragpoc.conversation

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.config.RagOperation
import com.dex.ragpoc.config.RagTelemetry
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationSummary
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.persistence.JdbcConversationRepository
import com.dex.ragpoc.workspace.WorkspaceAccessService
import org.springframework.stereotype.Service

class SessionNotFoundException : RuntimeException()

data class SessionDetail(
    val session: ChatSession,
    val summary: ConversationSummary?,
    val turns: List<ConversationTurn>,
)

@Service
class ConversationSessionService(
    private val conversations: JdbcConversationRepository,
    private val workspaces: WorkspaceAccessService,
    private val properties: AppProperties,
    private val telemetry: RagTelemetry? = null,
) {
    fun list(
        principal: Principal,
        workspaceId: String,
    ): List<ChatSession> =
        measured {
            workspaces.requireAccess(principal, workspaceId)
            purgeExpiredTurns()
            conversations.listActiveOwned(principal.subject, workspaceId)
        }

    fun detail(
        principal: Principal,
        sessionId: String,
    ): SessionDetail =
        measured {
            purgeExpiredTurns()
            val session = conversations.findActiveOwned(sessionId, principal.subject) ?: throw SessionNotFoundException()
            workspaces.requireAccess(principal, session.workspaceId)
            SessionDetail(
                session = session,
                summary = conversations.summary(sessionId),
                turns = conversations.recentTurns(sessionId, properties.memory.maxTurns),
            )
        }

    fun rename(
        principal: Principal,
        sessionId: String,
        title: String,
    ) = measured {
        ownedSession(principal, sessionId)
        if (!conversations.renameActiveOwned(sessionId, principal.subject, title)) throw SessionNotFoundException()
    }

    fun archive(
        principal: Principal,
        sessionId: String,
    ) = measured {
        ownedSession(principal, sessionId)
        if (!conversations.archiveActiveOwned(sessionId, principal.subject)) throw SessionNotFoundException()
    }

    private fun ownedSession(
        principal: Principal,
        sessionId: String,
    ): ChatSession {
        val session = conversations.findActiveOwned(sessionId, principal.subject) ?: throw SessionNotFoundException()
        workspaces.requireAccess(principal, session.workspaceId)
        return session
    }

    private fun purgeExpiredTurns() {
        conversations.deleteTurnsOlderThan(properties.memory.retentionDays)
    }

    private fun <T> measured(block: () -> T): T = if (telemetry != null) telemetry.observe(RagOperation.SESSION, block) else block()
}
