package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.ModelProfile
import com.dex.ragpoc.domain.SourceType
import com.dex.ragpoc.parsing.DocumentChunker
import com.dex.ragpoc.persistence.JdbcModelProfileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCatalogTest {
    private val secret = "offline-shared-cursor-secret-32-bytes"
    private val now = Instant.parse("2026-10-03T10:00:00Z")
    private val codec = DocumentCursorCodec(secret, Clock.fixed(now, ZoneOffset.UTC))
    private val profiles = mockk<JdbcModelProfileRepository>()

    init {
        every { profiles.get("bge-m3") } returns ModelProfile("bge-m3", "ollama", "bge", 2, "chunks")
    }

    private val reader = mockk<DocumentCatalogReader>()
    private val properties = AppProperties(documents = AppProperties.Documents(true, secret))

    private fun service(config: AppProperties = properties) = DocumentCatalogService(config, reader, profiles, DocumentChunker())

    private fun cursor(issued: Long = now.epochSecond) =
        CatalogCursor(
            "local",
            "bge-m3",
            "default",
            codec.subject("משתמש"),
            1,
            "18",
            "מסמך😀",
            Instant.parse("2026-10-02T08:30:15.123456Z"),
            issued,
            issued + 900,
        )

    @Test
    fun `immutable asset identity matches Python JSON including Unicode and controls`() {
        val mapper =
            com.fasterxml.jackson.databind
                .ObjectMapper()
        val fixture = mapper.readTree(java.io.File("src/test/resources/contracts/document-asset-python.json"))
        val values = mapper.convertValue(fixture["values"], List::class.java)
        val json = PythonJson.encode(values, ascii = false, spaced = true)
        assertEquals(fixture["json"].asText(), json)
        val hash =
            java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        assertEquals(fixture["asset_id"].asText(), hash)
    }

    @Test
    fun `real Python token verifies and Kotlin encoding is byte identical`() {
        val fixture =
            com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.io.File("src/test/resources/contracts/document-cursor-python.json"),
            )
        val payload = fixture["payload"]
        val token = fixture["token"].asText()
        val fixtureCodec =
            DocumentCursorCodec(fixture["secret"].asText(), Clock.fixed(Instant.ofEpochSecond(payload["issued"].asLong()), ZoneOffset.UTC))
        val decoded = fixtureCodec.decode(token)
        assertEquals(fixtureCodec.subject(fixture["subject_input"].asText()), decoded.subject)
        assertEquals("מסמך😀", decoded.lastId)
        assertEquals(Instant.parse("2026-10-02T08:30:15.123456Z"), decoded.lastTime)
        assertEquals(token, fixtureCodec.encode(decoded))
    }

    @Test
    fun `cursor round trip preserves Unicode identity and microseconds`() {
        val value = cursor()
        assertEquals(value, codec.decode(codec.encode(value)))
        assertEquals(
            "[\"document\",\"rag\",\"\\u05de\\u05e1\\u05de\\u05da\",\"default\",\"\\ud83d\\ude00\"]",
            PythonJson.encode(listOf("document", "rag", "מסמך", "default", "😀")),
        )
    }

    @Test
    fun `tampered malformed and oversized tokens fail before repository use`() {
        val token = codec.encode(cursor())
        for (bad in listOf("", "a.b.c", token.dropLast(1) + if (token.last() == '0') '1' else '0', "x".repeat(4097), "*.00")) {
            assertFailsWith<IllegalArgumentException> { codec.decode(bad) }
        }
        verify(exactly = 0) { reader.page(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `disabled catalog is unavailable without consulting profiles or storage`() {
        assertFailsWith<DocumentListUnavailable> { service(properties.copy(documents = AppProperties.Documents())).list("local", "user") }
        verify(exactly = 0) { profiles.get(any()) }
        verify(exactly = 0) { reader.page(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `page retains null history valid zero and exact selected scope`() {
        every { reader.page("local", "bge-m3", "default", 25, null) } returns
            CatalogSnapshot(
                "18",
                listOf(DocumentSummary("doc", "כותרת", "guide.pptx", "powerpoint", null, 0)),
            )
        val response = service().list("local", "user")
        assertEquals(DocumentScope("bge-m3", "default"), response.scope)
        assertEquals(0, response.items.single().indexedChunkCount)
        assertEquals(null, response.items.single().lastIngestedAt)
        assertFalse(response.page.hasMore)
        assertEquals(null, response.page.nextCursor)
    }

    @Test
    fun `continuation preserves first issuance and rejects principal workspace and limit changes`() {
        val issued = Instant.now().epochSecond - 100
        val token = codec.encode(cursor(issued))
        every { reader.page("local", "bge-m3", "default", 1, any()) } returns
            CatalogSnapshot(
                "18",
                listOf(DocumentSummary("a", "A", "a.txt", "text", null, 1), DocumentSummary("b", "B", "b.txt", "text", null, 1)),
            )
        val page = service().list("local", "משתמש", 1, token)
        val next = codec.decode(page.page.nextCursor!!)
        assertEquals(issued, next.issued)
        assertEquals(issued + 900, next.expires)
        for ((workspace, subject, limit) in listOf(Triple("other", "משתמש", 1), Triple("local", "other", 1), Triple("local", "משתמש", 2))) {
            assertFailsWith<IllegalArgumentException> { service().list(workspace, subject, limit, token) }
        }
    }

    @Test
    fun `expired valid token and implicit profile changes request restart`() {
        val expired = codec.encode(cursor(Instant.now().epochSecond - 901))
        assertFailsWith<DocumentListChanged> { service().list("local", "משתמש", 1, expired) }
        val fresh = codec.encode(cursor(Instant.now().epochSecond))
        val changed = properties.copy(rag = properties.rag.copy(modelProfile = "new-default"))
        assertFailsWith<DocumentListChanged> { service(changed).list("local", "משתמש", 1, fresh) }
        assertFailsWith<IllegalArgumentException> { service().list("local", "משתמש", 1, fresh, modelProfile = "other") }
    }

    @Test
    fun `invalid scope limits and profiles fail validation`() {
        for (limit in listOf(0, 101)) assertFailsWith<IllegalArgumentException> { service().list("local", "user", limit) }
        for (name in listOf("", "bad profile", "-bad", "a".repeat(101))) {
            assertFailsWith<IllegalArgumentException> { service().list("local", "user", modelProfile = name) }
        }
        assertFailsWith<IllegalArgumentException> { service().list("local", "user", chunkingProfile = "unknown") }
        every { profiles.get("missing") } returns null
        assertFailsWith<IllegalArgumentException> { service().list("local", "user", modelProfile = "missing") }
    }

    @Test
    fun `database failures return unavailable and missing configured profile does not look empty`() {
        every { profiles.get("bge-m3") } throws DataAccessResourceFailureException("private database details")
        assertFailsWith<DocumentListUnavailable> { service().list("local", "user") }
        every { profiles.get("bge-m3") } returns null
        assertFailsWith<DocumentListUnavailable> { service().list("local", "user") }
    }

    @Test
    fun `display metadata drops paths bidi controls and truncates by code point`() {
        val document = Document("id", "C:\\private\\מסמך😀.pptx", SourceType.UNKNOWN, "", title = "C:\\private\\secret")
        val display = DocumentDisplay.from(document)
        assertEquals("מסמך😀.pptx", display.fileName)
        assertEquals(display.fileName, display.title)
        assertEquals("unknown", display.type)
        val unicode = DocumentDisplay.from(document.copy(title = "😀".repeat(301)))
        assertEquals(300, unicode.title.codePointCount(0, unicode.title.length))
        assertEquals("title words", DocumentDisplay.from(document.copy(title = "title\u202E\u0000\u00a0 words")).title)
        assertTrue(unicode.title.endsWith("😀"))
    }
}
