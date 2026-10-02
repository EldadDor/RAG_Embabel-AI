package com.dex.ragpoc.api

import com.dex.ragpoc.identity.PrincipalResolver
import com.dex.ragpoc.workspace.WorkspaceAccessService
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class PrincipalSummaryResponse(
    @get:JsonProperty("display_name") val displayName: String,
)

data class WorkspaceSummaryResponse(
    @get:JsonProperty("workspace_id") val workspaceId: String,
    @get:JsonProperty("display_name") val displayName: String,
    val role: String,
)

data class WorkspaceListResponse(
    val principal: PrincipalSummaryResponse,
    val workspaces: List<WorkspaceSummaryResponse>,
)

@RestController
class WorkspaceController(
    private val principals: PrincipalResolver,
    private val workspaces: WorkspaceAccessService,
) {
    @GetMapping("/workspaces")
    fun list(request: HttpServletRequest): WorkspaceListResponse {
        val principal = principals.resolve(request)
        return WorkspaceListResponse(
            principal = PrincipalSummaryResponse(principal.displayName),
            workspaces = workspaces.listFor(principal).map { WorkspaceSummaryResponse(it.workspaceId, it.displayName, it.role) },
        )
    }
}
