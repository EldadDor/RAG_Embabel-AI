package com.dex.ragpoc.retrieval

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.RetrievedChunk
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.util.UUID
import kotlin.test.assertEquals

class JdbcRetrievalRepositoryTest {
    @Test
    fun `semantic and lexical citations retain Python document identifiers`() {
        val repository = repository("""{"doc_id":"python-doc","chunk_id":"python-chunk","source_path":"guide.md"}""")
        for (chunk in listOf(
            repository.semantic(listOf(.1f, .2f), "local", "default", "chunks", 1).single(),
            repository.lexical("Guide", "local", "default", "chunks", 1).single(),
        )) {
            assertEquals("python-doc", chunk.documentId)
            assertEquals("python-chunk", chunk.chunkId)
            assertEquals("guide.md", chunk.sourcePath)
        }
    }

    @Test
    fun `existing Kotlin document identifiers remain readable`() {
        val repository = repository("""{"document_id":"kotlin-doc","chunk_id":"kotlin-chunk"}""")
        val chunk = repository.semantic(listOf(.1f, .2f), "local", "default", "chunks", 1).single()
        assertEquals("kotlin-doc", chunk.documentId)
        assertEquals("column-source.md", chunk.sourcePath)
    }

    @Test
    fun `canonical Python key takes precedence when legacy alias is also present`() {
        val repository = repository("""{"doc_id":"canonical","document_id":"legacy"}""")
        val chunk = repository.lexical("Guide", "local", "default", "chunks", 1).single()
        assertEquals("canonical", chunk.documentId)
    }

    private fun repository(metadata: String): JdbcRetrievalRepository {
        val jdbc = mockk<JdbcTemplate>()
        val row = mockk<ResultSet>()
        every { row.getString("metadata") } returns metadata
        every { row.getString("source") } returns "column-source.md"
        every { row.getString("content") } returns "Evidence"
        every { row.getObject("id") } returns UUID.fromString("fdd67e9a-3e02-4da4-8dc6-74c7bdcfb288")
        every { row.getDouble("score") } returns .9
        every { row.getObject("page_number", Int::class.javaObjectType) } returns null
        every { jdbc.query(any<String>(), any<RowMapper<RetrievedChunk>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<RetrievedChunk>>().mapRow(row, 0)!!)
        }
        return JdbcRetrievalRepository(jdbc, ObjectMapper(), AppProperties())
    }
}
