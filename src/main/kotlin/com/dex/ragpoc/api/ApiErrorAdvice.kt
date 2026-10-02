package com.dex.ragpoc.api

import com.dex.ragpoc.asset.AssetNotFoundException
import com.dex.ragpoc.asset.UnsupportedAssetMediaTypeException
import com.dex.ragpoc.catalog.DocumentListChanged
import com.dex.ragpoc.catalog.DocumentListUnavailable
import com.dex.ragpoc.chat.ChatProviderException
import com.dex.ragpoc.conversation.SessionNotFoundException
import com.dex.ragpoc.identity.AuthenticationRequiredException
import com.dex.ragpoc.ingestion.IngestionProviderException
import com.dex.ragpoc.workspace.WorkspaceAccessDeniedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ApiErrorResponse(
    val code: String,
    val message: String,
)

@RestControllerAdvice
class ApiErrorAdvice {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DocumentListChanged::class)
    fun documentListChanged() = error(HttpStatus.CONFLICT, "document_list_changed", "The document list changed. Reload it to continue.")

    @ExceptionHandler(DocumentListUnavailable::class)
    fun documentListUnavailable(exception: DocumentListUnavailable): ResponseEntity<ApiErrorResponse> {
        logger.warn("Document listing unavailable: {}", exception.reason)
        return error(HttpStatus.SERVICE_UNAVAILABLE, "document_list_unavailable", "The document list is temporarily unavailable.")
    }

    @ExceptionHandler(Exception::class)
    fun unexpectedFailure(exception: Exception): ResponseEntity<ApiErrorResponse> {
        // Omit exception messages, which may contain SQL values, paths or request data.
        logger.error("Unexpected API failure: {} at {}", exception.javaClass.name, exception.stackTrace.take(12).joinToString("; "))
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected server error occurred.")
    }

    @ExceptionHandler(AuthenticationRequiredException::class)
    fun authenticationRequired() = error(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication is required.")

    @ExceptionHandler(WorkspaceAccessDeniedException::class)
    fun workspaceAccessDenied() = error(HttpStatus.FORBIDDEN, "workspace_access_denied", "You do not have access to this workspace.")

    @ExceptionHandler(SessionNotFoundException::class, AssetNotFoundException::class)
    fun resourceNotFound() = error(HttpStatus.NOT_FOUND, "resource_not_found", "The requested resource was not found.")

    @ExceptionHandler(UnsupportedAssetMediaTypeException::class)
    fun unsupportedMediaType() =
        error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "The requested media type cannot be displayed.")

    @ExceptionHandler(ChatProviderException::class)
    fun chatProviderFailure() = error(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "The answer service is temporarily unavailable.")

    @ExceptionHandler(IngestionProviderException::class)
    fun ingestionProviderFailure() =
        error(HttpStatus.BAD_GATEWAY, "upstream_provider_error", "The embedding provider could not complete the request.")

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        IllegalArgumentException::class,
        MissingServletRequestParameterException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalidRequest() = error(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", "The request is invalid.")

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(status).body(ApiErrorResponse(code, message))
}
