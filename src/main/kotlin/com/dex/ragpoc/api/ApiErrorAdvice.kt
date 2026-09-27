package com.dex.ragpoc.api

import com.dex.ragpoc.asset.AssetNotFoundException
import com.dex.ragpoc.asset.UnsupportedAssetMediaTypeException
import com.dex.ragpoc.conversation.SessionNotFoundException
import com.dex.ragpoc.identity.AuthenticationRequiredException
import com.dex.ragpoc.workspace.WorkspaceAccessDeniedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

data class ApiErrorResponse(
    val code: String,
    val message: String,
)

@RestControllerAdvice
class ApiErrorAdvice {
    @ExceptionHandler(AuthenticationRequiredException::class)
    fun authenticationRequired() = error(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication is required.")

    @ExceptionHandler(WorkspaceAccessDeniedException::class)
    fun workspaceAccessDenied() = error(HttpStatus.FORBIDDEN, "workspace_access_denied", "You do not have access to this workspace.")

    @ExceptionHandler(SessionNotFoundException::class, AssetNotFoundException::class)
    fun resourceNotFound() = error(HttpStatus.NOT_FOUND, "resource_not_found", "The requested resource was not found.")

    @ExceptionHandler(UnsupportedAssetMediaTypeException::class)
    fun unsupportedMediaType() =
        error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "The requested media type cannot be displayed.")

    @ExceptionHandler(MethodArgumentNotValidException::class, HandlerMethodValidationException::class, IllegalArgumentException::class)
    fun invalidRequest() = error(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", "The request is invalid.")

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(status).body(ApiErrorResponse(code, message))
}
