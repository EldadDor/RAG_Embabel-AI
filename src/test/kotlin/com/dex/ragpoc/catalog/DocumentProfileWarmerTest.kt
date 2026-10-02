package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.providers.DocumentProfileWarmer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentProfileWarmerTest {
    private val db = ScriptedDatabase()
    private val model = mockk<EmbeddingModel>()
    private val time = Instant.parse("2026-10-02T08:30:15.123456Z")
    private var includeEmpty = true

    init {
        db.reads = { call ->
            when {
                call.sql.contains("SELECT profile_name") -> {
                    val name = call.args[1] as String
                    listOf(
                        mapOf(
                            "profile_name" to name,
                            "provider" to "ollama",
                            "model" to "bge",
                            "dimensions" to 2,
                            "storage_target" to "chunks_$name",
                            "query_prefix" to "",
                            "document_prefix" to "document: ",
                            "status" to if (name == "source") "ready" else "draft",
                        ),
                    )
                }

                call.sql.contains("SELECT * FROM rag.document_index_metadata") -> {
                    fun document(
                        id: String,
                        stamp: Instant?,
                    ) = mapOf(
                        "doc_id" to id,
                        "source_path" to "C:/private/$id.txt",
                        "document_type" to "text",
                        "title" to id,
                        "file_name" to "$id.txt",
                        "content_hash" to "hash",
                        "chunking_profile" to "default",
                        "root_path" to "C:/private",
                        "last_ingested_at" to stamp,
                    )
                    if (includeEmpty) listOf(document("empty", null), document("full", time)) else listOf(document("full", time))
                }

                call.sql.contains("SELECT id,content") -> {
                    listOf(
                        mapOf(
                            "id" to UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                            "content" to "Original chunk",
                            "metadata" to """{"doc_id":"full","chunk_id":"full:0","chunking_profile":"default"}""",
                            "source" to "C:/private/full.txt",
                            "page_number" to null,
                            "chunk_index" to 0,
                        ),
                    )
                }

                call.sql.contains("SELECT format_type") -> {
                    listOf(mapOf("type" to "vector(2)"))
                }

                call.sql.contains("SELECT count(*)") -> {
                    listOf(mapOf("count" to if (call.args[2] == "empty") 0L else 1L))
                }

                call.sql.contains("SELECT ready") -> {
                    listOf(mapOf("ready" to false))
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    private fun service() = DocumentProfileWarmer(db.dataSource, ObjectMapper(), AppProperties(), model)

    @Test
    fun `warming dry run uses one pinned connection and leaves source metadata untouched`() {
        val result = service().warm("target", "source", "space", true)
        assertEquals(1, result.chunks)
        assertEquals(1, result.providerCalls)
        assertTrue(db.calls.none { it.sql.contains("INSERT") || it.sql.contains("UPDATE") || it.sql.contains("DELETE") })
        assertEquals(1, db.events.count { it == "acquire" })
        assertEquals(2, db.calls.count { it.sql.contains("pg_advisory_lock") })
        assertEquals(2, db.calls.count { it.sql.contains("pg_advisory_unlock") })
        verify(exactly = 0) { model.embed(any<List<String>>()) }
    }

    @Test
    fun `warming republishes zero and nonzero documents preserving recency without rechunking`() {
        every { model.embed(listOf("document: Original chunk")) } returns listOf(floatArrayOf(.1f, .2f))
        val result = service().warm("target", "source", "space", false)
        assertEquals(1, result.chunks)
        val publications = db.calls.filter { it.sql.contains("INSERT INTO rag.document_index_metadata") }
        assertEquals(2, publications.size)
        assertEquals(null, publications[0].args[9])
        assertEquals(time, (publications[1].args[9] as java.sql.Timestamp).toInstant())
        assertTrue(db.calls.none { it.sql.contains("SELECT clock_timestamp") })
        assertEquals(1, db.events.count { it == "acquire" })
        assertEquals(2, db.events.count { it == "commit" })
        val statuses = db.calls.filter { it.sql.startsWith("UPDATE rag.model_profiles") }.map { it.args[1] }
        assertEquals(listOf("warming", "ready"), statuses)
        val vector = db.calls.single { it.sql.contains("INSERT INTO rag.chunks_target") }
        assertEquals("Original chunk", vector.args[2])
    }

    @Test
    fun `failed unlock aborts connection and attempts release of every held lock`() {
        val successfulReads = db.reads
        db.reads = { call ->
            if (call.sql.contains("pg_advisory_unlock")) throw java.sql.SQLException("unlock failed", "08006")
            successfulReads(call)
        }
        assertFailsWith<org.springframework.dao.DataAccessException> { service().warm("target", "source", "space", true) }
        assertEquals(2, db.calls.count { it.sql.contains("pg_advisory_unlock") })
        assertTrue("abort" in db.events)
    }

    @Test
    fun `provider failure leaves warming visible and releases both session locks`() {
        includeEmpty = false
        every { model.embed(any<List<String>>()) } throws IllegalStateException("provider failed")
        assertFailsWith<IllegalStateException> { service().warm("target", "source", "space", false) }
        assertTrue(db.calls.none { it.sql.contains("INSERT INTO rag.document_index_metadata") })
        assertEquals(listOf("warming"), db.calls.filter { it.sql.startsWith("UPDATE rag.model_profiles") }.map { it.args[1] })
        assertEquals(2, db.calls.count { it.sql.contains("pg_advisory_unlock") })
        assertEquals(1, db.events.count { it == "acquire" })
    }
}
