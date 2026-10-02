package com.dex.ragpoc.api

import com.dex.ragpoc.catalog.CatalogSnapshot
import com.dex.ragpoc.catalog.DocumentCatalogReader
import com.dex.ragpoc.catalog.DocumentCatalogService
import com.dex.ragpoc.catalog.DocumentListChanged
import com.dex.ragpoc.catalog.DocumentSummary
import com.dex.ragpoc.chat.ChatService
import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.config.JsonConfiguration
import com.dex.ragpoc.conversation.ConversationSessionService
import com.dex.ragpoc.domain.ChatSession
import com.dex.ragpoc.domain.ConversationTurn
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.domain.RetrievedChunk
import com.dex.ragpoc.identity.PrincipalResolver
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.persistence.AuthorizedWorkspace
import com.dex.ragpoc.persistence.JdbcConversationRepository
import com.dex.ragpoc.persistence.JdbcModelProfileRepository
import com.dex.ragpoc.persistence.JdbcWorkspaceRepository
import com.dex.ragpoc.providers.ChatGateway
import com.dex.ragpoc.retrieval.RetrievalResult
import com.dex.ragpoc.retrieval.RetrievalService
import com.dex.ragpoc.workspace.WorkspaceAccessService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import reactor.core.publisher.Flux
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Boot's real MVC/Jackson configuration with offline persistence/provider boundaries. */
class FrontendCompatibilityTest {
    private lateinit var context: AnnotationConfigWebApplicationContext
    private lateinit var mvc: MockMvc
    private lateinit var conversations: JdbcConversationRepository
    private lateinit var retrieval: RetrievalService
    private lateinit var gateway: ChatGateway
    private val timestamp = Instant.parse("2026-10-02T09:00:00Z")
    private val storedSessions = linkedMapOf<String, ChatSession>()
    private val storedTurns = linkedMapOf<String, MutableList<ConversationTurn>>()

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigWebApplicationContext()
        context.servletContext = MockServletContext()
        context.register(HttpConfiguration::class.java)
        context.refresh()
        mvc =
            MockMvcBuilders
                .webAppContextSetup(
                    context,
                ).addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(DocumentCatalogHeadersFilter())
                .build()
        conversations = context.getBean(JdbcConversationRepository::class.java)
        retrieval = context.getBean(RetrievalService::class.java)
        gateway = context.getBean(ChatGateway::class.java)
        val workspaces = context.getBean(JdbcWorkspaceRepository::class.java)
        every { workspaces.listForSubject("local-dev") } returns listOf(AuthorizedWorkspace("python-space", "Python Workspace", "owner"))
        every { workspaces.isAuthorized("local-dev", "python-space") } returns true
        every { workspaces.isAuthorized("local-dev", "denied") } returns false
        val profiles = context.getBean(JdbcModelProfileRepository::class.java)
        every { profiles.get("bge-m3") } returns ModelProfile("bge-m3", "ollama", "bge", 2, "chunks")
        every { retrieval.retrieve(any(), "python-space", any(), any(), any()) } returns
            RetrievalResult(
                listOf(RetrievedChunk("chunk-1", "doc-1", "guide.md", "Evidence", .9)),
                ModelProfile("bge-m3", "ollama", "bge", 2, "chunks"),
            )
        every { gateway.stream(any()) } returns Flux.just("Shared ", "answer")
        every { gateway.complete(any()) } returns "Standalone question"
        every { conversations.create(any()) } answers {
            val session = firstArg<ChatSession>()
            storedSessions[session.sessionId] = session.copy(updatedAt = timestamp)
        }
        every { conversations.findActiveOwned(any(), "local-dev") } answers {
            storedSessions[firstArg<String>()]?.takeIf { it.ownerId == "local-dev" && !it.archived }
        }
        every { conversations.listActiveOwned("local-dev", "python-space") } answers {
            storedSessions.values.filter { it.ownerId == "local-dev" && it.workspaceId == "python-space" && !it.archived }
        }
        every { conversations.recentTurns(any(), 10) } answers { storedTurns[firstArg<String>()]?.toList() ?: emptyList() }
        every { conversations.summary(any()) } returns null
        every { conversations.deleteTurnsOlderThan(90) } returns 0
        every { conversations.appendTurn(any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            val turns = storedTurns.getOrPut(id) { mutableListOf() }
            turns += ConversationTurn(turns.size.toLong() + 1, id, secondArg(), thirdArg(), timestamp)
            storedSessions[id] = storedSessions.getValue(id).copy(lastPreview = thirdArg(), updatedAt = timestamp)
        }
        every { conversations.renameActiveOwned(any(), "local-dev", any()) } answers {
            val id = firstArg<String>()
            storedSessions[id] = storedSessions.getValue(id).copy(title = thirdArg())
            true
        }
        every { conversations.archiveActiveOwned(any(), "local-dev") } answers {
            val id = firstArg<String>()
            storedSessions[id] = storedSessions.getValue(id).copy(archived = true)
            true
        }
    }

    @AfterEach
    fun tearDown() = context.close()

    @Test
    fun `gateway authentication failures on catalog use protected safe envelope`() {
        val gatewayProperties = AppProperties(auth = AppProperties.Auth(mode = "gateway"))
        val controller =
            DocumentCatalogController(
                PrincipalResolver(gatewayProperties),
                context.getBean(WorkspaceAccessService::class.java),
                context.getBean(DocumentCatalogService::class.java),
            )
        val gatewayMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(ApiErrorAdvice())
                .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(DocumentCatalogHeadersFilter())
                .build()
        gatewayMvc
            .perform(get("/workspaces/python-space/documents"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("authentication_required"))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Vary", "Cookie, Authorization"))
    }

    @Test
    fun `document panel receives safe snake case metadata without private fields`() {
        val catalog = context.getBean(DocumentCatalogReader::class.java)
        every { catalog.page("python-space", "bge-m3", "default", 25, null) } returns
            CatalogSnapshot(
                "18",
                listOf(DocumentSummary("doc", "מסמך", "guide.pptx", "powerpoint", null, 0)),
            )
        mvc
            .perform(get("/workspaces/python-space/documents"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Vary", "Cookie, Authorization"))
            .andExpect(jsonPath("$.workspace_id").value("python-space"))
            .andExpect(jsonPath("$.scope.model_profile").value("bge-m3"))
            .andExpect(jsonPath("$.scope.chunking_profile").value("default"))
            .andExpect(jsonPath("$.items[0].doc_id").value("doc"))
            .andExpect(jsonPath("$.items[0].indexed_chunk_count").value(0))
            .andExpect(jsonPath("$.items[0].last_ingested_at").value(null as Any?))
            .andExpect(jsonPath("$.items[0].document_type").value("powerpoint"))
            .andExpect(jsonPath("$.items[0].source_path").doesNotExist())
            .andExpect(jsonPath("$.page.list_revision").value("18"))
            .andExpect(jsonPath("$.page.has_more").value(false))
            .andExpect(jsonPath("$.page.generated_at").isString)
    }

    @Test
    fun `document query errors and revoked membership always have private headers`() {
        for ((field, value) in listOf(
            "limit" to "0",
            "limit" to "101",
            "limit" to "NaN",
            "cursor" to "",
            "model_profile" to "bad profile",
            "chunking_profile" to "unknown",
        )) {
            mvc
                .perform(get("/workspaces/python-space/documents").param(field, value))
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
        }
        mvc
            .perform(get("/workspaces/denied/documents"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("workspace_access_denied"))
            .andExpect(header().string("Vary", "Cookie, Authorization"))
        verify(exactly = 0) { context.getBean(DocumentCatalogReader::class.java).page(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `document panel paginates and restarts on shared revision change`() {
        val catalog = context.getBean(DocumentCatalogReader::class.java)
        every { catalog.page("python-space", "bge-m3", "default", 1, null) } returns
            CatalogSnapshot(
                "18",
                listOf(DocumentSummary("a", "A", "a.txt", "text", timestamp, 1), DocumentSummary("b", "B", "b.txt", "text", null, 0)),
            )
        val response =
            mvc
                .perform(get("/workspaces/python-space/documents").param("limit", "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.page.has_more").value(true))
                .andReturn()
                .response.contentAsString
        val cursor = mapper().readTree(response)["page"]["next_cursor"].asText()
        every { catalog.page("python-space", "bge-m3", "default", 1, any()) } throws DocumentListChanged()
        mvc
            .perform(get("/workspaces/python-space/documents").param("limit", "1").param("cursor", cursor))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("document_list_changed"))
            .andExpect(jsonPath("$.message").value("The document list changed. Reload it to continue."))
            .andExpect(header().string("Cache-Control", "private, no-store"))
        every { catalog.page("python-space", "bge-m3", "default", 25, null) } throws
            org.springframework.dao.DataAccessResourceFailureException("private password")
        mvc
            .perform(get("/workspaces/python-space/documents"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("document_list_unavailable"))
            .andExpect(jsonPath("$.message").value("The document list is temporarily unavailable."))
    }

    @Test
    fun `frontend can discover workspaces and read a Python-origin chat`() {
        seedPythonSession()
        mvc
            .perform(get("/workspaces"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.principal.display_name").value("Local Developer"))
            .andExpect(jsonPath("$.workspaces[0].workspace_id").value("python-space"))
            .andExpect(jsonPath("$.workspaces[0].display_name").value("Python Workspace"))
            .andExpect(jsonPath("$.workspaces[0].workspaceId").doesNotExist())
        mvc
            .perform(get("/chat/sessions").param("workspace_id", "python-space"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].session_id").value("python-session"))
            .andExpect(jsonPath("$[0].workspace_id").value("python-space"))
            .andExpect(jsonPath("$[0].last_preview").value("Previous answer"))
            .andExpect(jsonPath("$[0].updated_at").value(timestamp.toString()))
        mvc
            .perform(get("/chat/sessions/python-session"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session_id").value("python-session"))
            .andExpect(jsonPath("$.turns[0].created_at").value(timestamp.toString()))
            .andExpect(jsonPath("$.turns[0].content").value("Previous answer"))
    }

    @Test
    fun `missing blank and unauthorized workspace queries have safe errors`() {
        mvc.perform(get("/chat/sessions")).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("invalid_request"))
        mvc
            .perform(get("/chat/sessions").param("workspace_id", ""))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid_request"))
        mvc
            .perform(get("/chat/sessions").param("workspace_id", "denied"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("workspace_access_denied"))
        verify(exactly = 0) { conversations.listActiveOwned(any(), "denied") }
    }

    @Test
    fun `snake case completed chat preserves every supplied scope and option`() {
        seedPythonSession()
        mvc
            .perform(
                post(
                    "/chat",
                ).contentType(
                    "application/json",
                ).content(
                    """{"question":"Next question","workspace_id":"python-space","session_id":"python-session","top_k":3,"include_debug":true,"chunking_profile":"custom","model_profile":"bge-m3"}""",
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.session_id").value("python-session"))
            .andExpect(jsonPath("$.sources[0].doc_id").value("doc-1"))
            .andExpect(jsonPath("$.sources[0].chunk_id").value("chunk-1"))
            .andExpect(jsonPath("$.sources[0].source_path").value("guide.md"))
            .andExpect(jsonPath("$.debug.retrieved_count").value(1))
        verify { retrieval.retrieve("Standalone question", "python-space", "custom", "bge-m3", 3) }
        verify(exactly = 0) { conversations.create(any()) }
    }

    @Test
    fun `legacy camel case chat input aliases remain accepted`() {
        mvc
            .perform(
                post(
                    "/chat",
                ).contentType(
                    "application/json",
                ).content(
                    """{"question":"Question","workspaceId":"python-space","topK":2,"includeDebug":true,"chunkingProfile":"custom","modelProfile":"bge-m3"}""",
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.debug.retrieved_count").value(1))
        verify { retrieval.retrieve("Question", "python-space", "custom", "bge-m3", 2) }
    }

    @Test
    fun `frontend creates lists loads and continues a chat using the returned stream session id`() {
        val events = stream("""{"question":"Question","workspace_id":"python-space","include_debug":false}""")
        assertEquals(listOf("answer", "answer", "meta", "done"), events.map { it.first })
        val meta = mapper().readTree(events[2].second)
        val id = meta["session_id"].asText()
        assertTrue(id.isNotBlank())
        assertEquals("doc-1", meta["sources"][0]["doc_id"].asText())
        assertEquals("chunk-1", meta["sources"][0]["chunk_id"].asText())
        assertFalse(meta["sources"][0].has("document_id"))
        mvc
            .perform(get("/chat/sessions").param("workspace_id", "python-space"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].session_id").value(id))
        mvc
            .perform(get("/chat/sessions/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.turns.length()").value(2))
            .andExpect(jsonPath("$.turns[1].content").value("Shared answer"))
        val continued = stream("""{"question":"Follow up","workspace_id":"python-space","session_id":"$id","include_debug":false}""")
        assertEquals(id, mapper().readTree(continued[2].second)["session_id"].asText())
        assertEquals(1, storedSessions.size)
        assertEquals(4, storedTurns.getValue(id).size)
    }

    @Test
    fun `Python-origin chat can continue through the streaming endpoint`() {
        seedPythonSession()
        val events = stream("""{"question":"Follow up","workspace_id":"python-space","session_id":"python-session"}""")
        assertEquals("python-session", mapper().readTree(events[2].second)["session_id"].asText())
        verify(exactly = 0) { conversations.create(any()) }
    }

    @Test
    fun `stream failure has the frontend error envelope and terminal event`() {
        every { gateway.stream(any()) } returns
            Flux.concat(Flux.just("partial"), Flux.error(IllegalStateException("secret provider detail")))
        val events = stream("""{"question":"Question","workspace_id":"python-space"}""")
        assertEquals(listOf("answer", "error", "done"), events.map { it.first })
        val error = mapper().readTree(events[1].second)
        assertEquals("stream_interrupted", error["code"].asText())
        assertTrue(error["message"].asText().isNotBlank())
        assertFalse(events.toString().contains("secret"))
        assertEquals("error", mapper().readTree(events[2].second)["reason"].asText())
        assertTrue(storedSessions.isEmpty())
    }

    @Test
    fun `missing foreign archived and wrong workspace continuations are masked before streaming`() {
        storedSessions["foreign"] = ChatSession("foreign", "someone-else", "python-space", "Foreign")
        storedSessions["archived"] = ChatSession("archived", "local-dev", "python-space", "Archived", archived = true)
        storedSessions["other-workspace"] = ChatSession("other-workspace", "local-dev", "other", "Other")
        for (id in listOf("missing", "foreign", "archived", "other-workspace")) {
            for (path in listOf("/chat", "/chat/stream")) {
                mvc
                    .perform(
                        post(
                            path,
                        ).contentType(
                            "application/json",
                        ).content("""{"question":"Question","workspace_id":"python-space","session_id":"$id"}"""),
                    ).andExpect(status().isNotFound)
                    .andExpect(jsonPath("$.code").value("resource_not_found"))
            }
        }
        verify(exactly = 0) { retrieval.retrieve(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `frontend rename archive and validation preserve the shared session lifecycle`() {
        seedPythonSession()
        mvc
            .perform(patch("/chat/sessions/python-session").contentType("application/json").content("""{"title":"Renamed"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
        mvc
            .perform(patch("/chat/sessions/python-session").contentType("application/json").content("""{"title":""}"""))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid_request"))
        assertEquals("Renamed", storedSessions.getValue("python-session").title)
        mvc.perform(delete("/chat/sessions/python-session")).andExpect(status().isNoContent)
        mvc.perform(get("/chat/sessions/python-session")).andExpect(status().isNotFound)
        mvc
            .perform(post("/chat").contentType("application/json").content("""{"question":"Question","top_k":0}"""))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid_request"))
    }

    private fun seedPythonSession() {
        storedSessions["python-session"] =
            ChatSession("python-session", "local-dev", "python-space", "Python chat", "Previous answer", updatedAt = timestamp)
        storedTurns["python-session"] = mutableListOf(ConversationTurn(1, "python-session", "assistant", "Previous answer", timestamp))
    }

    @Test
    fun `invalid frontend chat bodies are rejected before retrieval`() {
        for (body in listOf(
            "{",
            """{"question":"Question","workspace_id":""}""",
            """{"question":"Question","model_profile":"bad profile"}""",
        )) {
            mvc
                .perform(post("/chat").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value("invalid_request"))
        }
        verify(exactly = 0) { retrieval.retrieve(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `completed chat provider errors use the Python frontend error code`() {
        every { gateway.complete(any()) } throws IllegalStateException("secret provider detail")
        mvc
            .perform(post("/chat").contentType("application/json").content("""{"question":"Question","workspace_id":"python-space"}"""))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("upstream_unavailable"))
            .andExpect(jsonPath("$.message").value("The answer service is temporarily unavailable."))
        verify(exactly = 0) { conversations.create(any()) }
    }

    private fun stream(body: String): List<Pair<String, String>> {
        val pending =
            mvc
                .perform(post("/chat/stream").contentType("application/json").content(body))
                .andExpect(request().asyncStarted())
                .andReturn()
        val response =
            mvc
                .perform(asyncDispatch(pending))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn()
                .response.contentAsString
        return response.split("\n\n").filter { it.isNotBlank() }.map { block ->
            val lines = block.lines()
            lines.first { it.startsWith("event:") }.substringAfter(":").trim() to
                lines.filter { it.startsWith("data:") }.joinToString("\n") { it.substringAfter(":").trimStart() }
        }
    }

    private fun mapper() = context.getBean(JsonMapper::class.java)

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        JacksonAutoConfiguration::class,
        HttpMessageConvertersAutoConfiguration::class,
        WebMvcAutoConfiguration::class,
        ValidationAutoConfiguration::class,
    )
    @Import(
        JsonConfiguration::class,
        WorkspaceController::class,
        ConversationSessionController::class,
        ChatController::class,
        ApiErrorAdvice::class,
        PrincipalResolver::class,
        WorkspaceAccessService::class,
        ConversationSessionService::class,
        ChatService::class,
        DocumentCatalogController::class,
        DocumentCatalogService::class,
    )
    class HttpConfiguration {
        @Bean fun properties() = AppProperties(documents = AppProperties.Documents(true, "offline-cursor-secret-at-least-32-bytes"))

        @Bean fun catalog(): DocumentCatalogReader = mockk()

        @Bean fun profiles(): JdbcModelProfileRepository = mockk()

        @Bean fun chunker() = DocumentChunker()

        @Bean fun workspaces(): JdbcWorkspaceRepository = mockk()

        @Bean fun conversations(): JdbcConversationRepository = mockk()

        @Bean fun retrieval(): RetrievalService = mockk()

        @Bean fun gateway(): ChatGateway = mockk()
    }
}
