package com.dex.ragpoc.api

import com.dex.ragpoc.asset.AssetContent
import com.dex.ragpoc.asset.AssetReadService
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.conversation.ConversationSessionService
import com.dex.ragpoc.conversation.SessionNotFoundException
import com.dex.ragpoc.identity.Principal
import com.dex.ragpoc.identity.PrincipalResolver
import com.dex.ragpoc.persistence.AuthorizedWorkspace
import com.dex.ragpoc.persistence.StoredDocumentAsset
import com.dex.ragpoc.workspace.WorkspaceAccessService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class Rdp10ApiControllerTest {
    private val principal = Principal("local-dev", "Local Developer", "dev@localhost")
    private val workspaces = mockk<WorkspaceAccessService>()
    private val assets = mockk<AssetReadService>()
    private val sessions = mockk<ConversationSessionService>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val principals = PrincipalResolver(AppProperties())
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    WorkspaceController(principals, workspaces),
                    AssetController(principals, assets),
                    ConversationSessionController(principals, sessions),
                ).setControllerAdvice(ApiErrorAdvice())
                .build()
    }

    @Test
    fun `workspace discovery returns the current principal memberships`() {
        every { workspaces.listFor(principal) } returns listOf(AuthorizedWorkspace("alpha", "Alpha", "owner"))

        mockMvc
            .perform(get("/workspaces"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.principal.displayName").value("Local Developer"))
            .andExpect(jsonPath("$.workspaces[0].workspaceId").value("alpha"))
    }

    @Test
    fun `asset response supplies private cache and content protection headers`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        every { assets.read(principal, "alpha", "asset-1") } returns
            AssetContent(
                StoredDocumentAsset("asset-1", "alpha", "default", "guide", "ab/hash.png", "hash", "image/png", bytes.size.toLong()),
                bytes,
            )

        mockMvc
            .perform(get("/workspaces/alpha/assets/asset-1"))
            .andExpect(status().isOk)
            .andExpect(content().bytes(bytes))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=3600"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string(HttpHeaders.ETAG, "\"hash\""))
    }

    @Test
    fun `foreign missing and archived sessions receive the masked error`() {
        every { sessions.detail(principal, "unknown") } throws SessionNotFoundException()

        mockMvc
            .perform(get("/chat/sessions/unknown"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("resource_not_found"))
            .andExpect(jsonPath("$.message").value("The requested resource was not found."))
    }
}
