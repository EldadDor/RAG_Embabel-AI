package com.dex.ragpoc.api

import com.dex.ragpoc.catalog.DocumentCatalogService
import com.dex.ragpoc.catalog.DocumentListResponse
import com.dex.ragpoc.identity.PrincipalResolver
import com.dex.ragpoc.workspace.WorkspaceAccessService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter

@RestController
class DocumentCatalogController(
    private val principals: PrincipalResolver,
    private val workspaces: WorkspaceAccessService,
    private val catalog: DocumentCatalogService,
) {
    @GetMapping("/workspaces/{workspace_id}/documents")
    fun list(
        request: HttpServletRequest,
        @PathVariable("workspace_id") workspace: String,
        @RequestParam("limit", defaultValue = "25") limit: Int,
        @RequestParam("cursor", required = false) cursor: String?,
        @RequestParam("model_profile", required = false) model: String?,
        @RequestParam("chunking_profile", required = false) chunking: String?,
    ): DocumentListResponse {
        val principal = principals.resolve(request)
        workspaces.requireAccess(principal, workspace)
        return catalog.list(workspace, principal.subject, limit, cursor, model, chunking)
    }
}

/** Runs before binding/auth, so even validation and authorization errors cannot be cached. */
@Component
class DocumentCatalogHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (Regex("^/workspaces/[^/]+/documents/?$").matches(path)) {
            response.setHeader("Cache-Control", "private, no-store")
            response.setHeader("Vary", "Cookie, Authorization")
        }
        chain.doFilter(request, response)
    }
}
