package com.dex.ragpoc.api

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.identity.PrincipalResolver
import com.dex.ragpoc.ingestion.DocumentIngestionService
import com.dex.ragpoc.ingestion.IngestionBatchResult
import com.dex.ragpoc.ingestion.IngestionDocumentResult
import com.dex.ragpoc.ingestion.IngestionProviderException
import com.dex.ragpoc.ingestion.IngestionRequest
import com.dex.ragpoc.ingestion.IngestionSourcePolicy
import com.dex.ragpoc.workspace.WorkspaceAccessDeniedException
import com.dex.ragpoc.workspace.WorkspaceAccessService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.nio.file.Path

class ParityIngestionControllerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val principal = Principal("local-dev", "Local Developer", "dev@localhost")
    private val workspaces = mockk<WorkspaceAccessService>()
    private val ingestion = mockk<DocumentIngestionService>()

    @Test
    fun `authorized request uses Python field names and selected profiles`() {
        val source = Files.writeString(temporaryDirectory.resolve("guide.txt"), "Guide")
        val properties = AppProperties(ingestion = AppProperties.Ingestion(listOf(temporaryDirectory)))
        every { workspaces.requireAccess(principal, "alpha") } returns "alpha"
        every { ingestion.ingestPath(any(), true) } answers {
            val request = firstArg<IngestionRequest>()
            IngestionBatchResult(
                2,
                request.chunkingProfile,
                requireNotNull(request.modelProfile),
                request.dryRun,
                listOf(IngestionDocumentResult("doc-1", request.sourcePath.toString(), 2, false, assetsFound = 1)),
            )
        }

        mvc(properties)
            .perform(
                post("/ingest")
                    .contentType("application/json")
                    .content(
                        """{"source_path":"${source.toString().replace(
                            "\\",
                            "\\\\",
                        )}","recursive":true,"workspace_id":"alpha","chunking_profile":"custom","model_profile":"bge-m3","dry_run":true}""",
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.indexed").value(2))
            .andExpect(jsonPath("$.chunking_profile").value("custom"))
            .andExpect(jsonPath("$.model_profile").value("bge-m3"))
            .andExpect(jsonPath("$.dry_run").value(true))
            .andExpect(jsonPath("$.documents[0].doc_id").value("doc-1"))
            .andExpect(jsonPath("$.documents[0].assets_found").value(1))
        verify {
            ingestion.ingestPath(
                match { it.workspaceId == "alpha" && it.sourcePath == source.toRealPath() && it.modelProfile == "bge-m3" && it.dryRun },
                true,
            )
        }
    }

    @Test
    fun `workspace denial precedes source inspection`() {
        every { workspaces.requireAccess(principal, "local") } throws WorkspaceAccessDeniedException()
        mvc(AppProperties())
            .perform(post("/ingest").contentType("application/json").content("""{"source_path":"missing.txt"}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("workspace_access_denied"))
        verify(exactly = 0) { ingestion.ingestPath(any(), any()) }
    }

    @Test
    fun `source outside configured roots receives a safe error`() {
        val source = Files.writeString(temporaryDirectory.resolve("guide.txt"), "Guide")
        val deniedRoot = Files.createDirectory(temporaryDirectory.resolve("other"))
        every { workspaces.requireAccess(principal, "local") } returns "local"
        mvc(AppProperties(ingestion = AppProperties.Ingestion(listOf(deniedRoot))))
            .perform(
                post("/ingest")
                    .contentType("application/json")
                    .content("""{"source_path":"${source.toString().replace("\\", "\\\\")}"}"""),
            ).andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid_request"))
        verify(exactly = 0) { ingestion.ingestPath(any(), any()) }
    }

    @Test
    fun `invalid profile is rejected before work begins`() {
        mvc(AppProperties())
            .perform(post("/ingest").contentType("application/json").content("""{"source_path":"x","model_profile":"bad profile"}"""))
            .andExpect(status().isUnprocessableEntity)
        verify(exactly = 0) { ingestion.ingestPath(any(), any()) }
    }

    @Test
    fun `missing source path is a validation error`() {
        mvc(AppProperties())
            .perform(post("/ingest").contentType("application/json").content("""{"dry_run":true}"""))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid_request"))
        verify(exactly = 0) { ingestion.ingestPath(any(), any()) }
    }

    @Test
    fun `embedding failure returns a safe gateway error`() {
        val source = Files.writeString(temporaryDirectory.resolve("guide.txt"), "Guide")
        every { workspaces.requireAccess(principal, "local") } returns "local"
        every { ingestion.ingestPath(any(), false) } throws IngestionProviderException(IllegalStateException("secret provider detail"))

        mvc(AppProperties(ingestion = AppProperties.Ingestion(listOf(temporaryDirectory))))
            .perform(
                post("/ingest")
                    .contentType("application/json")
                    .content("""{"source_path":"${source.toString().replace("\\", "\\\\")}"}"""),
            ).andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("upstream_provider_error"))
            .andExpect(jsonPath("$.message").value("The embedding provider could not complete the request."))
    }

    private fun mvc(properties: AppProperties) =
        MockMvcBuilders
            .standaloneSetup(
                ParityIngestionController(
                    PrincipalResolver(properties),
                    workspaces,
                    IngestionSourcePolicy(properties),
                    ingestion,
                    properties,
                ),
            ).setControllerAdvice(ApiErrorAdvice())
            .build()
}
