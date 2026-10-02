package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceType
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdbcDocumentCatalogTest {
    private val db = ScriptedDatabase()
    private val jdbc = JdbcTemplate(db.dataSource)
    private val manager = DataSourceTransactionManager(db.dataSource)
    private val repository = JdbcDocumentPublicationRepository(jdbc, AppProperties())
    private val time = Instant.parse("2026-10-03T10:00:00.123456Z")
    private val document = Document("id", "C:/private/guide.txt", SourceType.TEXT, "", contentHash = "hash")

    private fun publicationReads(call: ScriptedDatabase.Call): List<Map<String, Any?>> =
        when {
            call.sql.contains("SELECT count(*)") -> listOf(mapOf("count" to 0L))
            call.sql.contains("SELECT clock_timestamp") -> listOf(mapOf("time" to time))
            call.sql.contains("SELECT ready") -> listOf(mapOf("ready" to true))
            else -> emptyList()
        }

    @Test
    fun `publication needs a transaction and shares Python lock keys`() {
        assertFailsWith<IllegalStateException> { repository.lockProfile("bge-m3") }
        TransactionTemplate(manager).executeWithoutResult {
            repository.lockProfile("bge-m3")
            repository.lockDocument("מרחב", "default", "😀")
        }
        val locks = db.calls.filter { it.sql.contains("pg_advisory_xact_lock") }
        assertEquals("document-publication:rag:bge-m3", locks[0].args[1])
        assertEquals("[\"document\",\"rag\",\"\\u05de\\u05e8\\u05d7\\u05d1\",\"default\",\"\\ud83d\\ude00\"]", locks[1].args[1])
    }

    @Test
    fun `zero chunk publication and revision commit together with selected table`() {
        db.reads = ::publicationReads
        TransactionTemplate(manager).executeWithoutResult {
            repository.publish("space", "alternate", "default", "chunks_alternate", document, "C:/private")
        }
        val metadata = db.calls.single { it.sql.contains("INSERT INTO rag.document_index_metadata") }
        assertEquals("space", metadata.args[1])
        assertEquals("alternate", metadata.args[2])
        assertEquals(0L, metadata.args[10])
        assertEquals("guide.txt", metadata.args[6])
        assertTrue(
            db.calls.any {
                it.sql.contains("FROM rag.chunks_alternate") &&
                    it.sql.contains("COALESCE(metadata->>'doc_id',metadata->>'document_id')")
            },
        )
        assertTrue(db.calls.any { it.sql.contains("INSERT INTO rag.document_list_revisions") })
        assertTrue("commit" in db.events)
    }

    @Test
    fun `warming preserves null recency without a clock query`() {
        db.reads = ::publicationReads
        TransactionTemplate(manager).executeWithoutResult {
            repository.publish("space", "target", "default", "chunks", document, null, preserveTime = true)
        }
        assertEquals(null, db.calls.single { it.sql.contains("INSERT INTO rag.document_index_metadata") }.args[9])
        assertTrue(db.calls.none { it.sql.contains("SELECT clock_timestamp") })
    }

    @Test
    fun `revision failure rolls back entire publication`() {
        db.reads = ::publicationReads
        db.writes = { call -> if (call.sql.contains("document_list_revisions")) throw SQLException("failure", "08006") else 1 }
        assertFailsWith<org.springframework.dao.DataAccessException> {
            TransactionTemplate(manager).executeWithoutResult { repository.publish("space", "model", "default", "chunks", document, null) }
        }
        assertTrue("rollback" in db.events)
        assertTrue("commit" !in db.events)
    }

    @Test
    fun `asset ownership pruning preserves references and is disabled before certification`() {
        db.reads = { listOf(mapOf("ready" to false)) }
        TransactionTemplate(
            manager,
        ).executeWithoutResult { repository.replaceAssetReferences("space", "target", "default", "id", listOf("image")) }
        assertTrue(db.calls.none { it.sql.startsWith("DELETE FROM rag.document_assets") })
        db.calls.clear()
        db.reads = { listOf(mapOf("ready" to true)) }
        TransactionTemplate(
            manager,
        ).executeWithoutResult { repository.replaceAssetReferences("space", "target", "default", "id", emptyList()) }
        val pruning = db.calls.single { it.sql.startsWith("DELETE FROM rag.document_assets") }
        assertTrue(pruning.sql.contains("NOT EXISTS") && pruning.sql.contains("refs.asset_id=assets.asset_id"))
        assertTrue(db.calls.single { it.sql.startsWith("DELETE FROM rag.document_index_assets") }.args[2] == "target")
    }

    @Test
    fun `catalog snapshot is read only repeatable read with null last keysets`() {
        db.reads = { call ->
            when {
                call.sql.contains("SELECT ready") -> {
                    listOf(mapOf("ready" to true))
                }

                call.sql.contains("SELECT revision") -> {
                    listOf(mapOf("revision" to 18L))
                }

                call.sql.contains("SELECT status") -> {
                    listOf(mapOf("status" to "ready"))
                }

                call.sql.contains("SELECT doc_id") -> {
                    listOf(
                        mapOf(
                            "doc_id" to "a",
                            "title" to "A",
                            "file_name" to "a.txt",
                            "document_type" to "text",
                            "last_ingested_at" to time,
                            "indexed_chunk_count" to 0L,
                        ),
                    )
                }

                else -> {
                    emptyList()
                }
            }
        }
        val reader = JdbcDocumentCatalogRepository(jdbc, NamedParameterJdbcTemplate(jdbc), AppProperties(), manager)
        val response = reader.page("space", "bge-m3", "default", 25, null)
        assertEquals("18", response.revision)
        assertEquals(time, response.rows.single().lastIngestedAt)
        assertTrue("readOnly=true" in db.events && "isolation=${Connection.TRANSACTION_REPEATABLE_READ}" in db.events)
        val query = db.calls.single { it.sql.contains("SELECT doc_id") }.sql
        assertTrue(query.contains("DESC NULLS LAST") && query.contains("COLLATE \"C\""))
        assertEquals(1, db.events.count { it == "acquire" })
    }

    @Test
    fun `changed revision aborts before rows and uncertified state cannot look empty`() {
        db.reads = { call -> if (call.sql.contains("SELECT ready")) listOf(mapOf("ready" to true)) else listOf(mapOf("revision" to 19L)) }
        val reader = JdbcDocumentCatalogRepository(jdbc, NamedParameterJdbcTemplate(jdbc), AppProperties(), manager)
        val cursor = CatalogCursor("space", "bge-m3", "default", "user", 25, "18", "a", null, 0, 900)
        assertFailsWith<DocumentListChanged> { reader.page("space", "bge-m3", "default", 25, cursor) }
        assertTrue(db.calls.none { it.sql.contains("SELECT doc_id") })
        db.reads = { listOf(mapOf("ready" to false)) }
        assertFailsWith<DocumentListUnavailable> { reader.page("space", "bge-m3", "default", 25, null) }
    }
}
