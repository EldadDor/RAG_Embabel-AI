package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.domain.Document
import com.dex.ragpoc.domain.SourceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Explicit database-only opt-in; temporary committed fixture publications are removed in finally. No DDL/providers. */
@EnabledIfEnvironmentVariable(named = "RDP20_LIVE_INTEGRATION", matches = "true")
class Rdp20LiveCatalogIntegrationTest {
    @Test
    fun `real PostgreSQL catalog ordering counts revisions and rollback`() {
        val source =
            DriverManagerDataSource(
                requireNotNull(System.getenv("RDP20_LIVE_JDBC_URL")),
                requireNotNull(System.getenv("RDP20_LIVE_JDBC_USER")),
                requireNotNull(System.getenv("RDP20_LIVE_JDBC_PASSWORD")),
            )
        val jdbc = JdbcTemplate(source).apply { queryTimeout = 10 }
        val properties = AppProperties()
        val model = System.getenv("RDP20_LIVE_MODEL_PROFILE") ?: "bge-m3"
        val table =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT storage_target FROM rag.model_profiles WHERE profile_name=? AND status='ready'",
                    String::class.java,
                    model,
                ),
            )
        require(Regex("^[a-z_][a-z0-9_]*$").matches(table))
        assertEquals(true, jdbc.queryForObject("SELECT ready FROM rag.document_catalog_state WHERE singleton=TRUE", Boolean::class.java))
        val manager = DataSourceTransactionManager(source)
        val transaction = TransactionTemplate(manager)
        val publication = JdbcDocumentPublicationRepository(jdbc, properties)
        val reader = JdbcDocumentCatalogRepository(jdbc, NamedParameterJdbcTemplate(jdbc), properties, manager)
        val workspace = "kotlin-rdp20-fixture-${UUID.randomUUID()}"
        jdbc.update("INSERT INTO rag.workspaces(workspace_id,display_name) VALUES (?,?)", workspace, "Temporary RDP20 catalog fixture")
        try {
            val first = Document("a", "C:/fixture/a.txt", SourceType.TEXT, "", contentHash = "first")
            val second = first.copy(documentId = "b", sourcePath = "C:/fixture/b.txt")
            transaction.executeWithoutResult {
                publication.lockProfile(model)
                publication.lockDocument(workspace, "default", "a")
                publication.publish(workspace, model, "default", table, first, null, preserveTime = true)
                publication.lockDocument(workspace, "default", "b")
                publication.publish(workspace, model, "default", table, second, null, preserveTime = true)
            }
            val page = reader.page(workspace, model, "default", 1, null)
            assertEquals(listOf("a", "b"), page.rows.map { it.documentId })
            assertEquals(listOf(0L, 0L), page.rows.map { it.indexedChunkCount })
            assertEquals(listOf(null, null), page.rows.map { it.lastIngestedAt })
            val cursor = CatalogCursor(workspace, model, "default", "fixture-subject", 1, page.revision, "a", null, 0, 900)
            assertEquals(listOf("b"), reader.page(workspace, model, "default", 1, cursor).rows.map { it.documentId })
            assertFailsWith<IllegalStateException> {
                transaction.executeWithoutResult {
                    publication.lockProfile(model)
                    publication.lockDocument(workspace, "default", "a")
                    publication.publish(workspace, model, "default", table, first.copy(contentHash = "failed"), null)
                    error("Forced rollback after metadata and revision writes")
                }
            }
            assertEquals(page.revision, reader.page(workspace, model, "default", 1, null).revision)
            transaction.executeWithoutResult {
                publication.lockProfile(model)
                publication.lockDocument(workspace, "default", "a")
                publication.publish(workspace, model, "default", table, first.copy(contentHash = "changed"), null)
            }
            assertFailsWith<DocumentListChanged> { reader.page(workspace, model, "default", 1, cursor) }
        } finally {
            transaction.executeWithoutResult {
                jdbc.update("DELETE FROM rag.document_index_metadata WHERE workspace_id=?", workspace)
                jdbc.update("DELETE FROM rag.document_list_revisions WHERE workspace_id=?", workspace)
                jdbc.update("DELETE FROM rag.workspaces WHERE workspace_id=?", workspace)
            }
        }
    }
}
