package com.dex.ragpoc.api

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.identity.PrincipalResolver
import com.dex.ragpoc.ingestion.DocumentIngestionService
import com.dex.ragpoc.ingestion.IngestionBatchResult
import com.dex.ragpoc.ingestion.IngestionRequest
import com.dex.ragpoc.ingestion.IngestionSourcePolicy
import com.dex.ragpoc.workspace.WorkspaceAccessService
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ParityIngestRequest(
    @field:NotBlank @param:JsonProperty("source_path") val sourcePath: String? = null,
    val recursive: Boolean? = null,
    @field:Size(min = 1) @param:JsonProperty("workspace_id") val workspaceId: String? = null,
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$")
    @field:Size(max = 100)
    @param:JsonProperty("chunking_profile")
    val chunkingProfile: String? = null,
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$")
    @field:Size(max = 100)
    @param:JsonProperty("model_profile")
    val modelProfile: String? = null,
    @param:JsonProperty("dry_run") val dryRun: Boolean? = null,
)

@RestController
@RequestMapping("/ingest")
class ParityIngestionController(
    private val principals: PrincipalResolver,
    private val workspaces: WorkspaceAccessService,
    private val sources: IngestionSourcePolicy,
    private val ingestion: DocumentIngestionService,
    private val properties: AppProperties,
) {
    @PostMapping
    fun ingest(
        @Valid @RequestBody body: ParityIngestRequest,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val principal = principals.resolve(request)
        val workspaceId = workspaces.requireAccess(principal, body.workspaceId ?: properties.rag.defaultWorkspaceId)
        val source = sources.admit(requireNotNull(body.sourcePath))
        val result =
            ingestion.ingestPath(
                IngestionRequest(
                    workspaceId = workspaceId,
                    sourcePath = source,
                    chunkingProfile = body.chunkingProfile ?: properties.rag.defaultChunkingProfile,
                    modelProfile = body.modelProfile ?: properties.rag.modelProfile,
                    dryRun = body.dryRun == true,
                ),
                body.recursive == true,
            )
        return result.asResponse()
    }
}

private fun IngestionBatchResult.asResponse(): Map<String, Any?> =
    mapOf(
        "indexed" to indexed,
        "chunking_profile" to chunkingProfile,
        "model_profile" to modelProfile,
        "dry_run" to dryRun,
        "documents" to
            documents.map {
                mapOf(
                    "doc_id" to it.documentId,
                    "source_path" to it.sourcePath,
                    "chunks_indexed" to it.chunksIndexed,
                    "skipped" to it.skipped,
                    "skip_reason" to it.skipReason,
                    "assets_found" to it.assetsFound,
                )
            },
    )
