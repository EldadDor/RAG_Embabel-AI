package com.dex.ragpoc.api

import com.dex.ragpoc.chat.ChatAnswer
import com.dex.ragpoc.chat.ChatService
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.identity.PrincipalResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ChatRequest(
    @field:NotBlank val question: String,
    @field:Min(1) @field:Max(20) val topK: Int? = null,
    val includeDebug: Boolean = false,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val chunkingProfile: String? = null,
    val modelProfile: String? = null,
)

@RestController
@RequestMapping("/chat")
class ChatController(
    private val chat: ChatService,
    private val principals: PrincipalResolver,
    private val properties: AppProperties,
) {
    @PostMapping
    fun answer(
        @Valid @RequestBody body: ChatRequest,
        request: HttpServletRequest,
    ): ChatAnswer =
        chat.answer(
            body.question,
            principals.resolve(request),
            body.workspaceId ?: properties.rag.defaultWorkspaceId,
            body.sessionId,
            body.chunkingProfile ?: properties.rag.defaultChunkingProfile,
            body.modelProfile ?: properties.rag.modelProfile,
            body.topK ?: properties.rag.topK,
            body.includeDebug,
        )
}
