package com.dex.ragpoc.api

import com.dex.ragpoc.conversation.ConversationSessionService
import com.dex.ragpoc.conversation.SessionDetail
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.identity.PrincipalResolver
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class ChatSessionSummaryResponse(
    @get:JsonProperty("session_id") val sessionId: String,
    @get:JsonProperty("workspace_id") val workspaceId: String,
    val title: String,
    @get:JsonProperty("last_preview") val lastPreview: String?,
    @get:JsonProperty("updated_at") val updatedAt: Instant?,
)

data class ChatTurnResponse(
    val role: String,
    val content: String,
    @get:JsonProperty("created_at") val createdAt: Instant?,
)

data class ChatSessionDetailResponse(
    @get:JsonProperty("session_id") val sessionId: String,
    @get:JsonProperty("workspace_id") val workspaceId: String,
    val title: String,
    @get:JsonProperty("last_preview") val lastPreview: String?,
    @get:JsonProperty("updated_at") val updatedAt: Instant?,
    val summary: String?,
    val turns: List<ChatTurnResponse>,
)

data class RenameChatSessionRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
)

@RestController
class ConversationSessionController(
    private val principals: PrincipalResolver,
    private val sessions: ConversationSessionService,
) {
    @GetMapping("/chat/sessions")
    fun list(
        @RequestParam("workspace_id") @NotBlank workspaceId: String,
        request: HttpServletRequest,
    ): List<ChatSessionSummaryResponse> = sessions.list(principals.resolve(request), workspaceId).map(::summaryResponse)

    @GetMapping("/chat/sessions/{sessionId}")
    fun detail(
        @PathVariable sessionId: String,
        request: HttpServletRequest,
    ): ChatSessionDetailResponse = detailResponse(sessions.detail(principals.resolve(request), sessionId))

    @PatchMapping("/chat/sessions/{sessionId}")
    fun rename(
        @PathVariable sessionId: String,
        @Valid @RequestBody requestBody: RenameChatSessionRequest,
        request: HttpServletRequest,
    ): Map<String, Boolean> {
        sessions.rename(principals.resolve(request), sessionId, requestBody.title)
        return mapOf("ok" to true)
    }

    @DeleteMapping("/chat/sessions/{sessionId}")
    fun archive(
        @PathVariable sessionId: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        sessions.archive(principals.resolve(request), sessionId)
        return ResponseEntity.noContent().build()
    }

    private fun summaryResponse(session: ChatSession) =
        ChatSessionSummaryResponse(session.sessionId, session.workspaceId, session.title, session.lastPreview, session.updatedAt)

    private fun detailResponse(detail: SessionDetail) =
        ChatSessionDetailResponse(
            sessionId = detail.session.sessionId,
            workspaceId = detail.session.workspaceId,
            title = detail.session.title,
            lastPreview = detail.session.lastPreview,
            updatedAt = detail.session.updatedAt,
            summary = detail.summary?.summary,
            turns = detail.turns.map(::turnResponse),
        )

    private fun turnResponse(turn: ConversationTurn) = ChatTurnResponse(turn.role, turn.content, turn.createdAt)
}
