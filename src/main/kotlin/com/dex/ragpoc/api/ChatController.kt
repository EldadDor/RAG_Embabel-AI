package com.dex.ragpoc.api

import com.dex.ragpoc.chat.ChatAnswer
import com.dex.ragpoc.chat.ChatService
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.identity.PrincipalResolver
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.Disposables
import reactor.core.scheduler.Schedulers

data class ChatRequest(
    @field:NotBlank val question: String,
    @field:Min(1) @field:Max(20)
    @param:JsonProperty("top_k")
    @param:JsonAlias("topK") val topK: Int? = null,
    @param:JsonProperty("include_debug") @param:JsonAlias("includeDebug") val includeDebug: Boolean? = false,
    @param:JsonProperty("session_id") @param:JsonAlias("sessionId") val sessionId: String? = null,
    @field:Size(min = 1)
    @param:JsonProperty("workspace_id")
    @param:JsonAlias("workspaceId") val workspaceId: String? = null,
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$") @field:Size(max = 100)
    @param:JsonProperty("chunking_profile")
    @param:JsonAlias("chunkingProfile") val chunkingProfile: String? = null,
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$") @field:Size(max = 100)
    @param:JsonProperty("model_profile")
    @param:JsonAlias("modelProfile") val modelProfile: String? = null,
)

@RestController
@RequestMapping("/chat")
class ChatController(
    private val chat: ChatService,
    private val principals: PrincipalResolver,
    private val properties: AppProperties,
) {
    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @Valid @RequestBody body: ChatRequest,
        request: HttpServletRequest,
    ): SseEmitter {
        val principal = principals.resolve(request)
        val emitter =
            object : SseEmitter(300_000L) {
                override fun extendResponse(outputMessage: org.springframework.http.server.ServerHttpResponse) {
                    super.extendResponse(outputMessage)
                    outputMessage.headers.setCacheControl("no-cache")
                    outputMessage.headers.set("X-Accel-Buffering", "no")
                }
            }
        val subscription = Disposables.swap()
        val events =
            chat.stream(
                body.question,
                principal,
                body.workspaceId ?: properties.rag.defaultWorkspaceId,
                body.sessionId,
                body.chunkingProfile ?: properties.rag.defaultChunkingProfile,
                body.modelProfile ?: properties.rag.modelProfile,
                body.topK ?: properties.rag.topK,
                body.includeDebug == true,
            )
        emitter.onCompletion { subscription.dispose() }
        emitter.onTimeout {
            subscription.dispose()
            emitter.complete()
        }
        emitter.onError { subscription.dispose() }
        subscription.update(
            events.subscribeOn(Schedulers.boundedElastic()).subscribe(
                { event ->
                    try {
                        emitter.send(SseEmitter.event().name(event.name).data(event.data))
                    } catch (error: Exception) {
                        subscription.dispose()
                        emitter.completeWithError(error)
                    }
                },
                { emitter.completeWithError(it) },
                { emitter.complete() },
            ),
        )
        return emitter
    }

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
            body.includeDebug == true,
        )
}
