package com.dex.ragpoc.chat

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.config.RagOperation
import com.dex.ragpoc.config.RagOutcome
import com.dex.ragpoc.config.RagTelemetry
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationSummary
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.persistence.JdbcConversationRepository
import com.dex.ragpoc.providers.ChatGateway
import com.dex.ragpoc.retrieval.RetrievalResult
import com.dex.ragpoc.retrieval.RetrievalService
import com.dex.ragpoc.workspace.WorkspaceAccessService
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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

class ChatProviderException : RuntimeException("Chat provider request failed")

data class ChatStreamEvent(
    val name: String,
    val data: Any,
)

@Service
class ChatService(
    private val retrieval: RetrievalService,
    private val conversations: JdbcConversationRepository,
    private val chat: ChatGateway,
    private val workspaces: WorkspaceAccessService,
    private val properties: AppProperties,
    private val telemetry: RagTelemetry? = null,
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
        val operation = {
            val prepared = prepare(question, principal, workspaceId, sessionId, chunkingProfile, modelProfile, topK)
            val answer = if (prepared.grounded) complete(prepared.prompt!!) else ABSTENTION
            persist(prepared, question, answer)
            result(prepared, answer, includeDebug)
        }
        return telemetry?.observe(RagOperation.CHAT, operation) ?: operation()
    }

    fun stream(
        question: String,
        principal: Principal,
        workspaceId: String,
        sessionId: String? = null,
        chunkingProfile: String = properties.rag.defaultChunkingProfile,
        modelProfile: String = properties.rag.modelProfile,
        topK: Int = properties.rag.topK,
        includeDebug: Boolean = false,
    ): Flux<ChatStreamEvent> {
        val started = System.nanoTime()
        val prepared = prepare(question, principal, workspaceId, sessionId, chunkingProfile, modelProfile, topK)
        return Flux
            .defer {
                val firstToken = AtomicBoolean(false)
                var outcome = RagOutcome.CANCELLED
                val observation = telemetry?.start(RagOperation.CHAT_STREAM)
                val deltas =
                    if (prepared.grounded) {
                        Flux.defer { chat.stream(prepared.prompt!!) }
                    } else {
                        Flux.just(ABSTENTION)
                    }
                val answer = StringBuilder()
                deltas
                    .doOnNext {
                        if (firstToken.compareAndSet(false, true)) telemetry?.firstToken(started)
                        telemetry?.count("rag_chat_stream_deltas_total")
                        answer.append(it)
                    }.map { ChatStreamEvent("answer", mapOf("delta" to it)) }
                    .concatWith(
                        Mono.fromCallable {
                            val completed = answer.toString()
                            if (prepared.grounded && completed.isEmpty()) throw ChatProviderException()
                            persist(prepared, question, completed)
                            ChatStreamEvent("meta", result(prepared, completed, includeDebug).streamMeta())
                        },
                    ).concatWith(Mono.just(ChatStreamEvent("done", mapOf("reason" to "completed"))))
                    .doOnComplete { outcome = RagOutcome.SUCCESS }
                    .doOnError {
                        outcome = RagOutcome.ERROR
                        observation?.error(it)
                    }.doFinally { signal ->
                        if (signal == SignalType.CANCEL) outcome = RagOutcome.CANCELLED
                        telemetry?.finish(RagOperation.CHAT_STREAM, outcome, started)
                        observation?.stop()
                    }
            }.onErrorResume {
                Flux.just(
                    ChatStreamEvent("error", mapOf("detail" to "Chat stream failed")),
                    ChatStreamEvent("done", mapOf("reason" to "error")),
                )
            }
    }

    private fun prepare(
        question: String,
        principal: Principal,
        workspaceId: String,
        sessionId: String?,
        chunkingProfile: String,
        modelProfile: String,
        topK: Int,
    ): PreparedChat {
        require(question.isNotBlank()) { "Question must not be blank" }
        workspaces.requireAccess(principal, workspaceId)
        val session = resolveSession(sessionId, principal, workspaceId, question)
        val history = if (sessionId == null) emptyList() else conversations.recentTurns(session.sessionId, properties.memory.maxTurns)
        val rewritten = rewrite(question, history, if (sessionId == null) null else conversations.summary(session.sessionId)?.summary)
        val retrieved = retrieval.retrieve(rewritten, workspaceId, chunkingProfile, modelProfile, topK)
        return PreparedChat(
            session,
            sessionId == null,
            rewritten,
            retrieved,
            retrieved.chunks.isNotEmpty(),
            if (retrieved.chunks.isNotEmpty()) groundedPrompt(question, retrieved.chunks.map { it.text }) else null,
        )
    }

    private fun persist(
        prepared: PreparedChat,
        question: String,
        answer: String,
    ) {
        val operation = {
            if (prepared.newSession) conversations.create(prepared.session)
            conversations.appendTurn(prepared.session.sessionId, "user", question)
            conversations.appendTurn(prepared.session.sessionId, "assistant", answer)
            refreshSummaryIfNeeded(prepared.session.sessionId)
        }
        telemetry?.observe(RagOperation.SESSION, operation) ?: operation()
    }

    private fun result(
        prepared: PreparedChat,
        answer: String,
        includeDebug: Boolean,
    ): ChatAnswer =
        ChatAnswer(
            answer,
            prepared.grounded,
            prepared.session.sessionId,
            prepared.retrieved.chunks.map {
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
                    "retrieved_count" to prepared.retrieved.chunks.size,
                    "rewritten_question" to prepared.rewritten,
                    "model_profile" to prepared.retrieved.modelProfile.profileName,
                )
            } else {
                null
            },
        )

    private fun ChatAnswer.streamMeta(): Map<String, Any?> =
        mapOf(
            "session_id" to sessionId,
            "grounded" to grounded,
            "sources" to
                sources.map {
                    mapOf(
                        "document_id" to it.documentId,
                        "chunk_id" to it.chunkId,
                        "source_path" to it.sourcePath,
                        "title" to it.title,
                        "page" to it.page,
                        "section" to it.section,
                        "score" to it.score,
                        "snippet" to it.snippet,
                    )
                },
            "debug" to debug,
        )

    private data class PreparedChat(
        val session: ChatSession,
        val newSession: Boolean,
        val rewritten: String,
        val retrieved: RetrievalResult,
        val grounded: Boolean,
        val prompt: String?,
    )

    private fun resolveSession(
        requested: String?,
        principal: Principal,
        workspaceId: String,
        question: String,
    ): ChatSession {
        if (requested == null) {
            return ChatSession(UUID.randomUUID().toString(), principal.subject, workspaceId, question.take(80))
        }
        val session =
            (
                if (telemetry != null) {
                    telemetry.observe(RagOperation.SESSION) { conversations.findActiveOwned(requested, principal.subject) }
                } else {
                    conversations.findActiveOwned(requested, principal.subject)
                }
            ) ?: throw IllegalArgumentException("Chat session was not found")
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
            complete(
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
                complete(
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

    private fun complete(prompt: String): String =
        try {
            chat.complete(prompt)
        } catch (error: RuntimeException) {
            throw ChatProviderException().also { it.initCause(error) }
        }

    private companion object {
        const val ABSTENTION = "I don't have enough information in the indexed documents to answer this question."
    }
}
