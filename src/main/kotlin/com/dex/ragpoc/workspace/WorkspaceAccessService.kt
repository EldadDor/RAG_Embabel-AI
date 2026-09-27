package com.dex.ragpoc.workspace

import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.persistence.AuthorizedWorkspace
import com.dex.ragpoc.persistence.JdbcWorkspaceRepository
import org.springframework.stereotype.Service

class WorkspaceAccessDeniedException : RuntimeException()

@Service
class WorkspaceAccessService(
    private val workspaces: JdbcWorkspaceRepository,
) {
    fun listFor(principal: Principal): List<AuthorizedWorkspace> = workspaces.listForSubject(principal.subject)

    fun requireAccess(
        principal: Principal,
        workspaceId: String,
    ): String {
        if (!workspaces.isAuthorized(principal.subject, workspaceId)) throw WorkspaceAccessDeniedException()
        return workspaceId
    }
}
