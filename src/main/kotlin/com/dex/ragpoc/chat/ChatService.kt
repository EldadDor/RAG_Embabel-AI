package com.dex.ragpoc.chat

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationSummary
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.persistence.JdbcConversationRepository
import com.dex.ragpoc.providers.ChatGateway
import com.dex.ragpoc.retrieval.RetrievalService
import com.dex.ragpoc.workspace.WorkspaceAccessService
import org.springframework.stereotype.Service
import java.util.UUID

data class ChatAnswer(
    val answer: String,
    val grounded: Boolean,
    val sessionId: String,
    val sources: List<ChatSource>,
    val debug: Map<String, Any?>? = null,
)

data class ChatSource(
    val documentId: String,
    val chunkId: String,
    val sourcePath: String,
    val title: String?,
    val page: Int?,
    val section: String?,
    val score: Double,
    val snippet: String,
)

@Service
class ChatService(
    private val retrieval: RetrievalService,
    private val conversations: JdbcConversationRepository,
    private val chat: ChatGateway,
    private val workspaces: WorkspaceAccessService,
    private val properties: AppProperties,
) {
    fun answer(
        question: String,
        principal: Principal,
        workspaceId: String,
        sessionId: String? = null,
        chunkingProfile: String = properties.rag.defaultChunkingProfile,
        modelProfile: String = properties.rag.modelProfile,
        topK: Int = properties.rag.topK,
        includeDebug: Boolean = false,
    ): ChatAnswer {
        require(question.isNotBlank()) { "Question must not be blank" }
        workspaces.requireAccess(principal, workspaceId)
        val session = resolveSession(sessionId, principal, workspaceId, question)
        val history = conversations.recentTurns(session.sessionId, properties.memory.maxTurns)
        val rewritten = rewrite(question, history, conversations.summary(session.sessionId)?.summary)
        val retrieved = retrieval.retrieve(rewritten, workspaceId, chunkingProfile, modelProfile, topK)
        val grounded = retrieved.chunks.isNotEmpty()
        val answer = if (grounded) chat.complete(groundedPrompt(question, retrieved.chunks.map { it.text })) else ABSTENTION
        conversations.appendTurn(session.sessionId, "user", question)
        conversations.appendTurn(session.sessionId, "assistant", answer)
        refreshSummaryIfNeeded(session.sessionId)
        return ChatAnswer(
            answer,
            grounded,
            session.sessionId,
            retrieved.chunks.map {
                ChatSource(
                    it.documentId,
                    it.chunkId,
                    it.sourcePath,
                    it.title,
                    it.page,
                    it.section,
                    it.score,
                    it.text.take(300),
                )
            },
            if (includeDebug) {
                mapOf(
                    "retrieved_count" to retrieved.chunks.size,
                    "rewritten_question" to rewritten,
                    "model_profile" to retrieved.modelProfile.profileName,
                )
            } else {
                null
            },
        )
    }

    private fun resolveSession(
        requested: String?,
        principal: Principal,
        workspaceId: String,
        question: String,
    ): ChatSession {
        if (requested == null) {
            val session = ChatSession(UUID.randomUUID().toString(), principal.subject, workspaceId, question.take(80))
            conversations.create(session)
            return session
        }
        val session =
            conversations.findActiveOwned(requested, principal.subject) ?: throw IllegalArgumentException("Chat session was not found")
        require(session.workspaceId == workspaceId) { "Chat session does not belong to this workspace" }
        return session
    }

    private fun rewrite(
        question: String,
        history: List<ConversationTurn>,
        summary: String?,
    ): String {
        if (history.isEmpty() && summary.isNullOrBlank()) return question
        val historyText = history.takeLast(6).joinToString("\n") { "${it.role}: ${it.content}" }
        val result =
            chat
                .complete(
                    """Rewrite the latest question into a standalone retrieval query. Return only the query.
            |Summary: ${summary ?: "(none)"}
            |Conversation: ${historyText.ifBlank { "(none)" }}
            |Latest question: $question
                    """.trimMargin(),
                ).trim()
        return result.ifBlank { question }
    }

    /** Summary maintenance is best-effort so an auxiliary provider failure never loses a completed answer. */
    private fun refreshSummaryIfNeeded(sessionId: String) {
        try {
            val turns = conversations.recentTurns(sessionId, properties.memory.maxTurns)
            if (turns.size < properties.memory.summaryAfterTurns) return
            val existing = conversations.summary(sessionId)?.summary
            val replacement =
                chat
                    .complete(
                        """Maintain a concise factual working-memory summary. Keep confirmed context, decisions, constraints, and unresolved questions only.
                        |Existing summary: ${existing ?: "(none)"}
                        |New turns: ${turns.joinToString("\n") { "${it.role}: ${it.content}" }}
                        """.trimMargin(),
                    ).trim()
            if (replacement.isNotEmpty()) conversations.upsertSummary(ConversationSummary(sessionId, replacement, turns.last().id))
        } catch (_: RuntimeException) {
            // Memory enrichment is not allowed to fail an otherwise completed response.
        }
    }

    private fun groundedPrompt(
        question: String,
        passages: List<String>,
    ): String =
        """You are a developer knowledge assistant. Answer using ONLY the context passages. If absent, say: "$ABSTENTION"
            |Context passages:
            |${passages.mapIndexed { index, text -> "[${index + 1}] ${text.trim()}" }.joinToString("\n\n---\n\n")}
            |Question: $question
        """.trimMargin()

    private companion object {
        const val ABSTENTION = "I don't have enough information in the indexed documents to answer this question."
    }
}
