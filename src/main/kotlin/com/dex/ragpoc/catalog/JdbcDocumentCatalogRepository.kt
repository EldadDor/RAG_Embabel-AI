package com.dex.ragpoc.catalog

import com.dex.ragpoc.config.AppProperties
import com.dex.ragpoc.persistence.requireIdentifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp

@Repository
class JdbcDocumentCatalogRepository(
    private val jdbc: JdbcTemplate,
    private val named: NamedParameterJdbcTemplate,
    properties: AppProperties,
    transactions: PlatformTransactionManager,
) : DocumentCatalogReader {
    private val schema = requireIdentifier("PG_SCHEMA", properties.database.schema)
    private val snapshot =
        TransactionTemplate(transactions).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun page(
        workspace: String,
        model: String,
        chunking: String,
        limit: Int,
        cursor: CatalogCursor?,
    ): CatalogSnapshot =
        requireNotNull(
            snapshot.execute {
                val ready =
                    jdbc.queryForObject(
                        "SELECT ready FROM $schema.document_catalog_state WHERE singleton=TRUE",
                        Boolean::class.java,
                    )
                if (ready != true) {
                    throw DocumentListUnavailable()
                }
                val revision =
                    jdbc
                        .query(
                            """
                            SELECT revision FROM $schema.document_list_revisions
                            WHERE workspace_id=? AND model_profile=? AND chunking_profile=?
                            """.trimIndent(),
                            { rs, _ -> rs.getLong(1).toString() },
                            workspace,
                            model,
                            chunking,
                        ).firstOrNull() ?: "0"
                if (cursor != null && cursor.revision != revision) throw DocumentListChanged()
                val status =
                    jdbc
                        .query(
                            "SELECT status FROM $schema.model_profiles WHERE profile_name=?",
                            { rs, _ -> rs.getString(1) },
                            model,
                        ).firstOrNull()
                if (status != "ready") {
                    throw DocumentListUnavailable()
                }
                val args =
                    MapSqlParameterSource()
                        .addValue("workspace", workspace)
                        .addValue("model", model)
                        .addValue("chunking", chunking)
                        .addValue("lastId", cursor?.lastId)
                        .addValue("lastTime", cursor?.lastTime?.let(Timestamp::from))
                        .addValue("limit", limit + 1)
                val rows =
                    named.query(
                        """
                        SELECT doc_id,title,file_name,document_type,last_ingested_at,indexed_chunk_count
                        FROM $schema.document_index_metadata
                        WHERE workspace_id=:workspace AND model_profile=:model AND chunking_profile=:chunking
                        AND (CAST(:lastId AS text) IS NULL OR
                             (CAST(:lastTime AS timestamptz) IS NULL AND last_ingested_at IS NULL
                              AND doc_id COLLATE "C">CAST(:lastId AS text) COLLATE "C") OR
                             (CAST(:lastTime AS timestamptz) IS NOT NULL AND
                              (last_ingested_at IS NULL OR last_ingested_at<:lastTime OR
                               (last_ingested_at=:lastTime AND doc_id COLLATE "C">CAST(:lastId AS text) COLLATE "C"))))
                        ORDER BY last_ingested_at DESC NULLS LAST, doc_id COLLATE "C" ASC LIMIT :limit
                        """.trimIndent(),
                        args,
                    ) { rs, _ ->
                        DocumentSummary(
                            rs.getString("doc_id"),
                            rs.getString("title"),
                            rs.getString("file_name"),
                            rs.getString("document_type"),
                            rs.getTimestamp("last_ingested_at")?.toInstant(),
                            rs.getLong("indexed_chunk_count"),
                        )
                    }
                CatalogSnapshot(revision, rows)
            },
        )
}
